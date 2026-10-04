# -*- coding: utf-8 -*-
"""
Train the S Log notification generator and export it for the app.

    python train_slog_model.py                         # uses slog_notifications.csv
    python train_slog_model.py --csv my.csv --epochs 60
    python train_slog_model.py --quantize              # ~4x smaller .tflite
    python train_slog_model.py --resume export/slog_model.keras --epochs 0   # only re-export

Output (in ./export):
    reminder_model.tflite   -> copy to app/src/main/assets/
    vocab.json              -> copy to app/src/main/assets/
    slog_model.keras        (to keep training later: --resume export/slog_model.keras)
    holdout.csv             the 10% of rows the model never trained on
    samples.txt             7 sample messages per category, generated exactly like the app does

Model: a small character-level LSTM. Each training example is
    <category> T i m e   t o   h y d r a t e !   💧 <eos>
and the app generates by feeding <category> and sampling one character at a time.
The category (first token) is also fed to the LSTM at every step, so long messages stay on topic.
The app only keeps messages that end properly and use words / word pairs from the training data
(vocab.json -> known_words, known_pairs), and skips near-duplicates within a week.

App contract (ReminderInference.kt) - keep it if you change the model:
    input  [1, maxlen] int32, token ids, padded with 0 (<pad>) after the sequence
    output [1, maxlen, vocab] logits; the app reads position len(seq)-1
    vocab.json: tok2id, eos_id, blocked_ids, maxlen, intents, category_keywords, known_words, known_pairs
"""
import argparse
import csv
import json
import os
import random
import re
import sys
import tempfile
import unicodedata
from collections import Counter, defaultdict

import numpy as np

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
import tensorflow as tf  # noqa: E402
import keras  # noqa: E402
from keras import layers  # noqa: E402

PAD, EOS = "<pad>", "<eos>"

# characters that glue onto the previous one (emoji variation selector, zero-width joiner, skin tones)
_GLUE = {"‍", "️", "︎"} | {chr(c) for c in range(0x1F3FB, 0x1F400)}


def tokenize(text: str):
    """Characters, but multi-code-point emoji (👨‍🍳, ✍️, 👍🏽) stay one token."""
    out = []
    i = 0
    while i < len(text):
        tok = text[i]
        i += 1
        while i < len(text) and (text[i] in _GLUE or (tok and tok[-1] == "‍")):
            tok += text[i]
            i += 1
        out.append(tok)
    return out


def clean(text: str) -> str:
    text = unicodedata.normalize("NFC", text)
    return " ".join(text.split())


# ------------------------------------------------------------------------------------------ data
def load_rows(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        need = {"id", "log_name", "category", "notification"}
        if not need.issubset(reader.fieldnames or []):
            sys.exit(f"{path}: columns must be {sorted(need)}, found {reader.fieldnames}")
        rows = []
        for r in reader:
            cat = (r["category"] or "").strip().lower().replace(" ", "_")
            msg = clean(r["notification"] or "")
            if cat and msg:
                rows.append({"id": r["id"], "log_name": r["log_name"], "category": cat, "notification": msg})
    if not rows:
        sys.exit("no rows")
    return rows


def split_rows(rows, holdout_frac, seed):
    """Stratified split: ~holdout_frac of every category goes to the holdout set."""
    rng = random.Random(seed)
    by_cat = defaultdict(list)
    for r in rows:
        by_cat[r["category"]].append(r)
    train, hold = [], []
    for cat in sorted(by_cat):
        items = by_cat[cat][:]
        rng.shuffle(items)
        k = max(1, round(len(items) * holdout_frac)) if len(items) > 5 else 0
        hold += items[:k]
        train += items[k:]
    rng.shuffle(train)
    return train, hold


def build_vocab(rows, categories):
    chars = Counter()
    for r in rows:
        chars.update(tokenize(r["notification"]))
    tok2id = {PAD: 0, EOS: 1}
    for c in categories:
        tok2id[f"<{c}>"] = len(tok2id)
    for ch in sorted(chars):
        if ch not in tok2id:
            tok2id[ch] = len(tok2id)
    return tok2id


def encode(rows, tok2id, maxlen):
    """X = [<cat>, t1..tn, <eos>, pad..]  (maxlen),  Y = X shifted left by one, W = 1 where Y is real."""
    n = len(rows)
    x = np.zeros((n, maxlen), dtype=np.int32)
    y = np.zeros((n, maxlen), dtype=np.int32)
    w = np.zeros((n, maxlen), dtype=np.float32)
    for i, r in enumerate(rows):
        seq = [tok2id[f"<{r['category']}>"]] + [tok2id[t] for t in tokenize(r["notification"])] + [tok2id[EOS]]
        seq = seq[: maxlen + 1]
        inp, tgt = seq[:-1], seq[1:]
        x[i, : len(inp)] = inp
        y[i, : len(tgt)] = tgt
        w[i, : len(tgt)] = 1.0
    return x, y, w


# ----------------------------------------------------------------------------------------- model
def build_model(vocab_size, maxlen, emb=64, units=256, dropout=0.2, for_export=False):
    """for_export=True: unrolled, no cuDNN -> plain TFLite builtin ops the app's runtime supports."""
    extra = dict(unroll=True, use_cudnn=False) if for_export else {}
    inp = keras.Input(shape=(maxlen,), batch_size=1 if for_export else None, dtype="int32", name="tokens")
    tok = layers.Embedding(vocab_size, emb, name="emb")(inp)
    # the category is token 0: copy its embedding to every step so the LSTM never "forgets" it
    first = layers.Cropping1D((0, maxlen - 1), name="cat_pick")(tok)
    cat = layers.UpSampling1D(maxlen, name="cat_repeat")(first)
    h = layers.Concatenate(name="with_cat")([tok, cat])
    h = layers.LSTM(units, return_sequences=True, name="lstm1", **extra)(h)
    if not for_export:
        h = layers.Dropout(dropout)(h)
    h = layers.LSTM(units, return_sequences=True, name="lstm2", **extra)(h)
    if not for_export:
        h = layers.Dropout(dropout)(h)
    out = layers.Dense(vocab_size, name="logits")(h)
    return keras.Model(inp, out)


def copy_weights(src, dst):
    for layer in dst.layers:
        if layer.weights:
            layer.set_weights(src.get_layer(layer.name).get_weights())


def export_tflite(train_model, vocab_size, maxlen, path, quantize):
    with tf.device("/CPU:0"):
        m = build_model(vocab_size, maxlen, emb=train_model.get_layer("emb").output_dim,
                        units=train_model.get_layer("lstm1").units, for_export=True)
        copy_weights(train_model, m)
        # Keras 3 -> SavedModel -> TFLite (the stable route; converting the Keras object directly
        # fails on some TF/Keras combinations)
        with tempfile.TemporaryDirectory() as tmp:
            saved = os.path.join(tmp, "saved")
            export = keras.export.ExportArchive()
            export.track(m)
            export.add_endpoint(
                name="serve",
                fn=lambda tokens: m(tokens, training=False),
                input_signature=[tf.TensorSpec([1, maxlen], tf.int32, name="tokens")],
            )
            export.write_out(saved)
            conv = tf.lite.TFLiteConverter.from_saved_model(saved, signature_keys=["serve"])
            conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]  # no Flex ops: app has none
            if quantize:
                conv.optimizations = [tf.lite.Optimize.DEFAULT]  # dynamic-range int8 weights
            data = conv.convert()
    with open(path, "wb") as f:
        f.write(data)
    return len(data)


# ---------------------------------------------------------------------- app-identical generation
WORD_RE = re.compile(r"[a-z0-9']+")


def words_of(text):
    return WORD_RE.findall(text.lower())


def pairs_of(text):
    """Neighbouring words, with ^ / $ for start / end: "Hey, drink up" -> ^ hey, hey drink, drink up, up $"""
    w = ["^"] + words_of(text) + ["$"]
    return [f"{a} {b}" for a, b in zip(w, w[1:])]


def too_similar(a, b, limit=0.6):
    """Word overlap (Jaccard) - "Focus 25 min, champ" vs "Focus 25 min, legend" are too similar."""
    wa, wb = set(words_of(a)), set(words_of(b))
    if not wa or not wb:
        return False
    return len(wa & wb) / len(wa | wb) >= limit


class AppLikeGenerator:
    """Mirrors ReminderInference.kt so you see what the phone will produce."""

    def __init__(self, tflite_path, vocab):
        self.it = tf.lite.Interpreter(model_path=tflite_path)
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]
        self.out = self.it.get_output_details()[0]
        self.maxlen = int(self.inp["shape"][1])
        self.tok2id = vocab["tok2id"]
        self.id2tok = {v: k for k, v in self.tok2id.items()}
        self.blocked = vocab["blocked_ids"]
        self.eos = vocab["eos_id"]
        self.known = set(vocab.get("known_words", []))
        self.pairs = set(vocab.get("known_pairs", []))

    def generate(self, category, temperature=0.7, rng=np.random):
        seq = [self.tok2id[f"<{category}>"]]
        text = []
        while len(seq) < self.maxlen and len(text) < 48:
            x = np.zeros((1, self.maxlen), dtype=self.inp["dtype"])
            x[0, : len(seq)] = seq
            self.it.set_tensor(self.inp["index"], x)
            self.it.invoke()
            logits = self.it.get_tensor(self.out["index"])[0, len(seq) - 1].astype(np.float64)
            logits[self.blocked] = -1e9
            p = np.exp((logits - logits.max()) / temperature)
            p /= p.sum()
            nxt = int(rng.choice(len(p), p=p))
            if nxt == self.eos:
                return "".join(text)
            text.append(self.id2tok.get(nxt, ""))
            seq.append(nxt)
        return None  # ran out of room without finishing -> unusable

    def is_valid(self, t):
        t = " ".join(t.split())
        if len(t) < 6:
            return False
        if self.known and not all(w in self.known for w in words_of(t)):
            return False  # made-up word
        return not self.pairs or all(p in self.pairs for p in pairs_of(t))  # odd word combination

    def batch(self, category, count=7, temperature=0.7):
        res, tries = [], 0
        while len(res) < count and tries < count * 12:
            t = self.generate(category, temperature)
            tries += 1
            if t is None or not self.is_valid(t):
                continue
            t = " ".join(t.split())
            if any(too_similar(t, r) for r in res):
                continue
            res.append(t)
        return res


def tflite_ops(path):
    try:
        from tensorflow.lite.python import schema_py_generated as schema  # type: ignore
        from tensorflow.lite.python.schema_util import get_builtin_code_from_operator_code  # type: ignore
        buf = open(path, "rb").read()
        model = schema.Model.GetRootAsModel(buf, 0)
        names = {v: k for k, v in schema.BuiltinOperator.__dict__.items() if not k.startswith("_")}
        ops = set()
        for i in range(model.OperatorCodesLength()):
            oc = model.OperatorCodes(i)
            ops.add(f"{names.get(get_builtin_code_from_operator_code(oc), '?')}(v{oc.Version()})")
        return sorted(ops)
    except Exception as e:  # pragma: no cover
        return [f"(could not list ops: {e})"]


# ------------------------------------------------------------------------------------------ main
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--csv", default=os.path.join(here, "slog_notifications.csv"))
    ap.add_argument("--categories", default=os.path.join(here, "categories.json"),
                    help="keywords per category (from make_dataset.py); copied into vocab.json")
    ap.add_argument("--out", default=os.path.join(here, "export"))
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--units", type=int, default=256)
    ap.add_argument("--emb", type=int, default=64)
    ap.add_argument("--holdout", type=float, default=0.10)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--quantize", action="store_true", help="dynamic-range quantization (smaller file)")
    ap.add_argument("--resume", help="continue from a saved .keras model (same vocab required)")
    ap.add_argument("--samples", type=int, default=7)
    args = ap.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    keras.utils.set_random_seed(args.seed)
    os.makedirs(args.out, exist_ok=True)

    gpus = tf.config.list_physical_devices("GPU")
    print(f"TensorFlow {tf.__version__} | Keras {keras.__version__} | GPU: {[g.name for g in gpus] or 'none (CPU)'}")
    if not tf.__version__.startswith("2.16"):
        print("WARNING: the app uses TFLite runtime 2.16.1. Models converted with a newer TensorFlow can fail\n"
              "         to load on the phone ('Didn't find op ... version'). Use tensorflow==2.16.2.")

    rows = load_rows(args.csv)
    categories = sorted({r["category"] for r in rows})
    train, hold = split_rows(rows, args.holdout, args.seed)
    print(f"{len(rows)} rows, {len(categories)} categories -> train {len(train)}, holdout {len(hold)}")

    # drop holdout rows whose text also appears in train (exact duplicates would leak)
    train_texts = {(r["category"], r["notification"].lower()) for r in train}
    leaked = [r for r in hold if (r["category"], r["notification"].lower()) in train_texts]
    if leaked:
        hold = [r for r in hold if r not in leaked]
        print(f"removed {len(leaked)} holdout rows duplicated in train")

    with open(os.path.join(args.out, "holdout.csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["id", "log_name", "category", "notification"])
        w.writeheader()
        w.writerows(hold)

    tok2id = build_vocab(rows, categories)
    longest = max(len(tokenize(r["notification"])) for r in rows)
    maxlen = max(32, ((longest + 2 + 7) // 8) * 8)  # <cat> + text + <eos>, rounded up to 8
    vocab_size = len(tok2id)
    print(f"vocab {vocab_size} tokens, maxlen {maxlen} (longest message {longest})")

    x_tr, y_tr, w_tr = encode(train, tok2id, maxlen)
    x_ho, y_ho, w_ho = encode(hold, tok2id, maxlen)

    if args.resume:
        model = keras.models.load_model(args.resume)
        if model.output_shape[-1] != vocab_size or model.input_shape[1] != maxlen:
            sys.exit("--resume model has a different vocab/maxlen; train from scratch instead")
    else:
        model = build_model(vocab_size, maxlen, emb=args.emb, units=args.units)
    model.compile(optimizer=keras.optimizers.Adam(args.lr),
                  loss=keras.losses.SparseCategoricalCrossentropy(from_logits=True),
                  weighted_metrics=["accuracy"])
    model.summary()

    best_path = os.path.join(args.out, "slog_model.keras")
    callbacks = [
        keras.callbacks.ModelCheckpoint(best_path, monitor="val_loss", save_best_only=True),
        keras.callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=3, min_lr=1e-4, verbose=1),
        keras.callbacks.EarlyStopping(monitor="val_loss", patience=6, restore_best_weights=True, verbose=1),
    ]
    if args.epochs > 0:
        model.fit(x_tr, y_tr, sample_weight=w_tr, validation_data=(x_ho, y_ho, w_ho),
                  epochs=args.epochs, batch_size=args.batch, shuffle=True, callbacks=callbacks, verbose=2)
        model = keras.models.load_model(best_path)
    elif not args.resume:
        sys.exit("--epochs 0 only makes sense with --resume (re-export a trained model)")

    loss, acc = model.evaluate(x_ho, y_ho, sample_weight=w_ho, verbose=0)
    print(f"holdout: loss {loss:.3f}  perplexity/char {np.exp(loss):.2f}  next-char accuracy {acc:.1%}")

    # ---- export
    tfl_path = os.path.join(args.out, "reminder_model.tflite")
    size = export_tflite(model, vocab_size, maxlen, tfl_path, args.quantize)
    print(f"wrote {tfl_path} ({size / 1024:.0f} KB)")
    print("ops:", ", ".join(tflite_ops(tfl_path)))

    keywords, emoji = {}, {}
    if os.path.exists(args.categories):
        with open(args.categories, encoding="utf-8") as f:
            meta = json.load(f)
        keywords = {c: meta[c]["keywords"] for c in categories if c in meta}
        emoji = {c: meta[c]["emoji"] for c in categories if c in meta}
    vocab = {
        "format": "slog-vocab-2",
        "tok2id": tok2id,
        "eos_id": tok2id[EOS],
        "blocked_ids": [0] + [tok2id[f"<{c}>"] for c in categories],
        "maxlen": maxlen,
        "intents": categories,
        "fallback_intent": "other" if "other" in categories else categories[0],
        "category_keywords": keywords,
        "category_emoji": emoji,
        "atomic_tokens": sorted(t for t in tok2id if len(t) > 1 and not t.startswith("<")),
        # the app drops generated messages with words / word pairs not in here (no made-up words,
        # no odd combinations like "budget us calling")
        "known_words": sorted({w for r in rows for w in words_of(r["notification"])}),
        "known_pairs": sorted({p for r in rows for p in pairs_of(r["notification"])}),
    }
    with open(os.path.join(args.out, "vocab.json"), "w", encoding="utf-8") as f:
        json.dump(vocab, f, ensure_ascii=False, indent=1)
    print(f"wrote {os.path.join(args.out, 'vocab.json')}")

    # ---- check the exported model the way the phone uses it
    gen = AppLikeGenerator(tfl_path, vocab)
    train_set = {r["notification"] for r in train}
    lines, total, novel, with_emoji = [], 0, 0, 0
    all_emoji = {e for es in emoji.values() for e in es}
    for c in categories:
        msgs = gen.batch(c, args.samples)
        lines.append(f"[{c}]")
        for m in msgs:
            total += 1
            novel += m not in train_set
            last = tokenize(m)[-1] if m else ""
            with_emoji += (last in all_emoji) if all_emoji else (not last.isascii())
            lines.append(f"  {m}")
    with open(os.path.join(args.out, "samples.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines[: 3 * (args.samples + 1)]))
    if total == 0:
        print("WARNING: no usable messages generated - the model needs more training (try more --epochs).")
    else:
        print(f"... ({total} samples in samples.txt) | new (not copied from training data): {novel / total:.0%}"
              f" | end with emoji: {with_emoji / total:.0%}")
    print("\nDone. Copy export/reminder_model.tflite and export/vocab.json to app/src/main/assets/")


if __name__ == "__main__":
    main()

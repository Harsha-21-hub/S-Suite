# -*- coding: utf-8 -*-
"""
Builds slog_notifications.csv  (id,log_name,category,notification)

    python make_dataset.py              -> 10,000 rows
    python make_dataset.py --rows 20000

Rules applied:
  * balanced categories (equal rows per category)
  * short messages: <= 36 characters and <= 7 words before the emoji
  * every message ends with one of the category's single-codepoint emoji
  * de-duplicated (case/punctuation-insensitive, emoji ignored) inside each category
Also writes categories.json (keywords per category) - the training script copies it into
vocab.json so the app maps log names to categories exactly like the model was trained.
"""
import argparse
import csv
import json
import random
import re

from categories import CATEGORIES, OPENERS, CLOSERS, NOUN_TEMPLATES, POSSESSIVE_TEMPLATES

MAX_CHARS = 36   # without the trailing " <emoji>"
MAX_WORDS = 7


def norm_key(text: str) -> str:
    """Key used for de-duplication: lower-case letters/digits only."""
    return re.sub(r"[^a-z0-9]+", "", text.lower())


def ok_length(text: str) -> bool:
    return len(text) <= MAX_CHARS and len(text.split()) <= MAX_WORDS


def cap(s: str) -> str:
    return s[:1].upper() + s[1:]


def candidates(cat: dict):
    """All candidate messages (without emoji) for one category, best ones first."""
    out = []
    # 1. hand-written lines and questions (highest quality)
    out += cat["lines"]
    out += cat["questions"]
    # 2. noun templates (fun, Zomato-like)
    for n in cat["nouns"]:
        possessive = n.split()[0].lower() in ("your", "a", "the", "today's")
        # "LeetCode" keeps its capitals; "Water" -> "water", "Your bottle" -> "your bottle"
        low = n if any(ch.isupper() for ch in n[1:]) else n[:1].lower() + n[1:]
        for t in (POSSESSIVE_TEMPLATES if possessive else NOUN_TEMPLATES):
            out.append(t.replace("{N}", cap(n)).replace("{n}", low))
    # 3. opener + action
    for o in OPENERS:
        for a in cat["actions"]:
            out.append(f"{o} {a}")
    # 4. action + closer
    for a in cat["actions"]:
        for c in CLOSERS:
            # "stay strong today" + " now" reads badly
            if a.split()[-1] in ("today", "now", "tonight", "instead") and c.strip(" ,") in ("now", "today", "rn"):
                continue
            if "," in a and c.startswith(","):   # "lock the phone, step out, please?" -> too many commas
                continue
            out.append(cap(a) + c)
    # clean + filter + dedupe (keep first occurrence)
    seen, final = set(), []
    for text in out:
        text = re.sub(r"\s+", " ", text).strip()
        if not ok_length(text):
            continue
        k = norm_key(text)
        if k in seen:
            continue
        seen.add(k)
        final.append(text)
    return final


def add_emoji(text: str, emoji_list, rng) -> str:
    e = rng.choice(emoji_list)
    # a question/exclamation keeps its mark; plain lines just get the emoji
    return f"{text} {e}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", type=int, default=10000)
    ap.add_argument("--out", default="slog_notifications.csv")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    rng = random.Random(args.seed)

    # sanity: emoji must be single code points
    for name, cat in CATEGORIES.items():
        for e in cat["emoji"]:
            assert len(e) == 1, f"{name}: emoji {e!r} is not a single code point"

    cats = list(CATEGORIES.keys())
    per_cat = args.rows // len(cats)
    extra = args.rows - per_cat * len(cats)

    # A message may belong to ONE category only (otherwise the model gets mixed signals).
    # Owner = the category where it is hand-written, else the first category that produced it.
    pools = {name: candidates(CATEGORIES[name]) for name in cats}
    owner = {}
    for name in cats:
        for t in CATEGORIES[name]["lines"] + CATEGORIES[name]["questions"]:
            owner.setdefault(norm_key(t), name)
    for name in cats:
        for t in pools[name]:
            owner.setdefault(norm_key(t), name)
    shared = 0
    for name in cats:
        before = len(pools[name])
        pools[name] = [t for t in pools[name] if owner[norm_key(t)] == name]
        shared += before - len(pools[name])

    rows = []
    report = []
    for i, name in enumerate(cats):
        cat = CATEGORIES[name]
        want = per_cat + (1 if i < extra else 0)
        pool = pools[name]
        handwritten = set(norm_key(t) for t in cat["lines"] + cat["questions"])
        hand = [t for t in pool if norm_key(t) in handwritten]
        combo = [t for t in pool if norm_key(t) not in handwritten]
        rng.shuffle(combo)

        chosen = list(hand)
        chosen += combo[: max(0, want - len(chosen))]
        if len(chosen) < want:
            raise SystemExit(f"{name}: only {len(chosen)} unique messages, need {want}. "
                             f"Add lines/actions in categories.py or lower --rows.")
        chosen = chosen[:want]
        rng.shuffle(chosen)

        for text in chosen:
            rows.append((rng.choice(cat["names"]), name, add_emoji(text, cat["emoji"], rng)))
        report.append((name, want, len(hand), len(pool)))

    rng.shuffle(rows)
    with open(args.out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["id", "log_name", "category", "notification"])
        for i, (log_name, cat, msg) in enumerate(rows, start=1):
            w.writerow([i, log_name, cat, msg])

    with open("categories.json", "w", encoding="utf-8") as f:
        json.dump({c: {"keywords": CATEGORIES[c]["keywords"], "emoji": CATEGORIES[c]["emoji"]} for c in cats},
                  f, ensure_ascii=False, indent=1)

    print(f"wrote {len(rows)} rows to {args.out} ({shared} cross-category duplicates removed)")
    print(f"{'category':14} rows  hand-written  unique-pool")
    for name, want, hand, pool in report:
        print(f"{name:14} {want:5} {hand:12} {pool:11}")


if __name__ == "__main__":
    main()

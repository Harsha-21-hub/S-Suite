# S Log AI notifications

The app writes its own reminder messages on the phone, Zomato-style (funny / emotional /
motivational, in English with a Gen Z touch: bestie, no cap, era, main character...), picked
from the **log name**:

| Log name        | Category  | Example message                  |
|-----------------|-----------|----------------------------------|
| Drink Water     | hydration | Your bottle feels ignored 💧     |
| IMD             | fasting   | Hold the fast, bestie 🥑         |
| Gym             | workout   | The dumbbells are lonely 💪      |
| LeetCode        | coding    | Arrays won't sort themselves 🐛  |
| Call Mom        | social    | Mom would love a call 📞         |

When a log is created (or renamed / imported) the app generates **7 messages, one per day of the
coming week**, and stores them. After the week a new batch is generated in the background (a daily
check runs the model only for logs whose week is over). Everything runs in the app; no server.

## Files

| File | What it is |
|------|------------|
| `slog_notifications.csv` | The dataset: 10,000 rows, `id,log_name,category,notification` |
| `categories.py` | All the writing: 32 categories, keywords, example names, emoji, hand-written lines |
| `make_dataset.py` | Builds the CSV from `categories.py` (+ `categories.json` with keywords for the app) |
| `train_slog_model.py` | Trains the model and exports `reminder_model.tflite` + `vocab.json` for the app |
| `requirements.txt` | Python packages |

### Dataset rules
- **32 categories**: hydration, meal, fasting (IMD / IF / 16:8 / OMAD / diet / keto), workout, walking, running, sleep, stretching, meds, skincare,
  wake, hygiene, meditation, journaling, mood, nofap, noporn, quit (smoking / sugar / alcohol),
  reading, studying, coding, focus, practice (music / language), chores, finance, social, creative,
  pets, plants, screen_break, prayer, other.
- **Balanced**: 312-313 rows per category.
- **Language**: English phrases and clauses with Gen Z slang; Hindi log names ("Paani", "Padhai")
  are still recognised as keywords, but every message is English.
- **Short**: at most 36 characters / 7 words before the emoji (average ~28 characters).
- **Emoji**: every message ends with one single-code-point emoji from its category's set.
- **No duplicates**: no message repeats inside a category, and no message appears in two categories.
- **Hold-out**: the training script keeps 10% of every category aside (`export/holdout.csv`).

To add your own lines: edit `categories.py` (add to `lines`, `actions`, `keywords`...), then
`python make_dataset.py`. Or edit the CSV directly - the training script only needs the 4 columns.

## Train (your RTX 4060 or any CPU)

The model is small (about 1 M parameters); on a normal laptop CPU 40 epochs take ~15 minutes.

**Windows, CPU (simplest):**
```
cd SLog\ai
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python train_slog_model.py
```

**GPU (RTX 4060)**: TensorFlow only supports NVIDIA GPUs on Linux / WSL2 (not native Windows).
In WSL2 Ubuntu with the NVIDIA driver installed on Windows:
```
cd SLog/ai
python3 -m venv .venv && source .venv/bin/activate
pip install "tensorflow[and-cuda]==2.16.2" keras==3.12.4
python train_slog_model.py
```
The first line of the output says whether the GPU was found.

Options: `--epochs 60`, `--units 384` (bigger model), `--quantize` (about 4x smaller .tflite),
`--resume export/slog_model.keras` (keep training; with `--epochs 0` it only re-exports),
`--csv my_data.csv`.

## Put the new model in the app
The script writes to `ai/export/`:
- `reminder_model.tflite` and `vocab.json` -> copy both to `app/src/main/assets/` (replace the old ones)
- `samples.txt` - 7 messages per category, generated exactly like the phone does. Read it first.
- `holdout.csv` - the 10% the model never saw; the script reports its loss / accuracy.

Rebuild the app. New logs get messages straight away; existing logs get new ones within a day.

## How the app uses it
- `AiMessages.categoryFor(name)`: picks the category from the log name with the keywords in
  `vocab.json` (longest match wins, so "walk the dog" -> pets, "walk" -> walking; unknown -> other).
- `ReminderInference`: feeds `<category>` and samples one character at a time (temperature 0.7).
  It keeps a message only if it ended properly, uses only words and word pairs from the training
  data, and isn't a near-copy of another message that week.
- Each reminder shows the message of the day; a second reminder on the same day uses the next one.

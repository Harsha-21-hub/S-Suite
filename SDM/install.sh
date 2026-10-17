#!/bin/sh
# SDM setup for macOS / Linux
set -e
cd "$(dirname "$0")"
PY=$(command -v python3 || command -v python)
[ -z "$PY" ] && { echo "Install Python 3.9+ first"; exit 1; }
echo "[1/3] Installing engine packages ..."
"$PY" -m pip install --user --upgrade -q -r requirements.txt 2>/dev/null || "$PY" -m pip install --user --upgrade -q --break-system-packages -r requirements.txt
echo "[2/3] Registering browser helper ..."
"$PY" install_helper.py install
echo "[3/3] Starting SDM ..."
"$PY" sdm.py --quit >/dev/null 2>&1 || true
nohup "$PY" sdm.py >/dev/null 2>&1 &
echo
echo "Last step: open chrome://extensions, enable Developer mode, Load unpacked -> $(pwd)/extension"

#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install -U pip
python3 -m pip install -r requirements.txt
python3 -m PyInstaller --noconfirm --windowed --name "LocalStackControl" tray_app.py

echo "Built app: $(pwd)/dist/LocalStackControl.app"
echo "You can move it to /Applications"

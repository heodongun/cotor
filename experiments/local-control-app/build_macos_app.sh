#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

python3 -m pip install --user -r requirements.txt
python3 -m PyInstaller --noconfirm --windowed --name "LocalStackControl" tray_app.py

echo "Built app: $(pwd)/dist/LocalStackControl.app"
echo "You can move it to /Applications"

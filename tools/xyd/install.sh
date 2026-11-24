#!/data/data/com.termux/files/usr/bin/sh
# Installation de l'outil xyd dans Termux :
#   curl -fsSL https://raw.githubusercontent.com/jules-mahounou/xyd/claude/intelligent-hawking-c9npwk/tools/xyd/install.sh | sh
set -e
BASE="https://raw.githubusercontent.com/jules-mahounou/xyd/claude/intelligent-hawking-c9npwk/tools/xyd"
BIN="${PREFIX:-/usr/local}/bin"
if command -v pkg >/dev/null 2>&1; then
  pkg install -y python ffmpeg
fi
pip install -q requests
curl -fsSL "$BASE/xyd.py" -o "$BIN/xyd"
chmod +x "$BIN/xyd"
[ -d "$HOME/storage" ] || { command -v termux-setup-storage >/dev/null 2>&1 && termux-setup-storage || true; }
echo "✓ xyd installé. Étape suivante :  xyd login"

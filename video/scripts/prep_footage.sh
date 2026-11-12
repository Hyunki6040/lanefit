#!/usr/bin/env bash
# Crop the raw emulator recordings to the app window and conform them for Remotion.
#   bash scripts/prep_footage.sh RAW_SIM RAW_CAR
# Timeline is left untouched (no trims), so every cut point in src/beats.ts is a raw-recording timestamp.
set -euo pipefail
[ $# -eq 2 ] || { echo "usage: $0 RAW_SIM RAW_CAR" >&2; exit 2; }
cd "$(dirname "$0")/.."
CROP="${LANEFIT_CROP:-1682:1210:853:90}" # app window inside the 2560x1440 capture
mkdir -p public/footage public/fonts

prep() {
  [ -f "$1" ] || { echo "missing recording: $1" >&2; exit 1; }
  ffmpeg -v error -y -i "$1" -an -vf "crop=$CROP,fps=30,format=yuv420p" \
    -c:v libx264 -preset slow -crf 14 -g 15 -keyint_min 15 -sc_threshold 0 -movflags +faststart \
    "public/footage/$2.mp4"
  echo "public/footage/$2.mp4  $(ffprobe -v error -show_entries format=duration -of csv=p=0 "public/footage/$2.mp4") s"
}
prep "$1" sim
prep "$2" car

# Film grain tile (synthesised) and the Pretendard weights the film uses (SIL OFL 1.1).
ffmpeg -v error -y -f lavfi -i "color=c=gray:s=512x512,noise=alls=70:allf=u,format=gray" -frames:v 1 -update 1 public/footage/grain.png
for w in Regular Medium SemiBold Bold ExtraBold; do
  cp "${PRETENDARD_DIR:-$HOME/Library/Fonts}/Pretendard-$w.otf" public/fonts/
done

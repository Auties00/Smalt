#!/usr/bin/env bash
# Regenerates the IJG jpegtran 9f vectors of smalt-image (jpeg/ijg9f/): lossless transforms of frames of five to
# seven components, which libjpeg-turbo cannot read (it finds no component after the fourth in a scan header).
# IJG libjpeg 9f writes such frames only in progressive mode, so every case is -progressive.
# The jpegtran is built with gcc from the IJG release jpegsrc.v9f.tar.gz (IJG license, https://www.ijg.org/files/,
# SHA-256 04705c110cb2469caa79fb71fba3d7bf834914706e9641a4589485c1f832565b); the sources are written by sources.py.
# Usage: make-vectors.sh <jpeg-9f source directory> <smalt checkout>
set -euo pipefail
IJG="$(cd "$1" && pwd)"
ROOT="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/image/src/test/resources/com/github/auties00/smalt/image/jpeg/ijg9f"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
LIB="jaricom jcapimin jcapistd jcarith jccoefct jccolor jcdctmgr jchuff jcinit jcmainct jcmarker jcmaster jcomapi
jcparam jcprepct jcsample jctrans jdapimin jdapistd jdarith jdatadst jdatasrc jdcoefct jdcolor jddctmgr jdhuff
jdinput jdmainct jdmarker jdmaster jdmerge jdpostct jdsample jdtrans jerror jfdctflt jfdctfst jfdctint jidctflt
jidctfst jidctint jquant1 jquant2 jutils jmemmgr jmemnobs"
cp -r "$IJG" "$WORK/src"
cp "$WORK/src/jconfig.txt" "$WORK/src/jconfig.h"
(cd "$WORK/src" && gcc -O1 -w -o "$WORK/jpegtran" jpegtran.c rdswitch.c cdjpeg.c transupp.c $(for f in $LIB; do
    printf '%s.c ' "$f"; done))
rm -rf "$OUT"
mkdir -p "$OUT"
python -I "$HERE/sources.py" "$OUT"

# output name, source, switches
CASES="progressive	five-mixed-41x35.jpg	-progressive
rotate-90	five-mixed-41x35.jpg	-progressive -rotate 90
rotate-180	five-mixed-41x35.jpg	-progressive -rotate 180
rotate-270	five-mixed-41x35.jpg	-progressive -rotate 270
flip-horizontal	five-mixed-41x35.jpg	-progressive -flip horizontal
flip-vertical	five-mixed-41x35.jpg	-progressive -flip vertical
transpose	five-mixed-41x35.jpg	-progressive -transpose
transverse	five-mixed-41x35.jpg	-progressive -transverse
rotate-90-trim	five-mixed-41x35.jpg	-progressive -rotate 90 -trim
transverse-trim	five-mixed-41x35.jpg	-progressive -transverse -trim
crop	five-mixed-41x35.jpg	-progressive -crop 20x17+5+4
crop-rotate-270	five-mixed-41x35.jpg	-progressive -crop 20x19+10+13 -rotate 270
arithmetic-flip	five-mixed-41x35.jpg	-progressive -arithmetic -flip horizontal
restart	five-mixed-41x35.jpg	-progressive -restart 1 -rotate 180
wipe	five-mixed-41x35.jpg	-progressive -wipe 20x17+5+4
six-rotate-90	six-mixed-29x21.jpg	-progressive -rotate 90
six-transverse	six-mixed-29x21.jpg	-progressive -transverse
six-flip-vertical-trim	six-mixed-29x21.jpg	-progressive -flip vertical -trim
six-crop-transpose	six-mixed-29x21.jpg	-progressive -crop 15x11+3+5 -transpose
six-arithmetic	six-mixed-29x21.jpg	-progressive -arithmetic -rotate 270
seven-rotate-180	seven-1x1-19x13.jpg	-progressive -rotate 180
seven-transpose-restart	seven-1x1-19x13.jpg	-progressive -transpose -restart 2B
small-flip-horizontal-trim	five-small-13x11.jpg	-progressive -flip horizontal -trim
small-rotate-90	five-small-13x11.jpg	-progressive -rotate 90
small-transverse-trim	five-small-13x11.jpg	-progressive -transverse -trim"
printf '%s\n' "$CASES" > "$OUT/cases.tsv"
while IFS=$'\t' read -r name source switches; do
    # shellcheck disable=SC2086
    "$WORK/jpegtran" $switches -outfile "$OUT/$name.jpg" "$OUT/$source"
done < "$OUT/cases.tsv"
(cd "$OUT" && sha256sum *.jpg cases.tsv > "$WORK/ijg9f.sha256" && mv "$WORK/ijg9f.sha256" ijg9f.sha256)

#!/usr/bin/env bash
# Writes the committed fixtures of Jp2Part2QuantizationTest (smalt-image): JPEG 2000 Part 2 codestreams with derived
# quantization over decompositions that split sub-bands in one direction only, written by Kakadu's kdu_compress, and
# Kakadu's own decodes of them (kdu_expand), which settle how equation E-5 treats a half-integer n_b.
#
# Usage: make-fixtures.sh <kakadu directory> <output directory>
#
# The Kakadu demo executables (v8.4.1, KDU841_Demo_Apps_for_Win64_231117.msi, free for non-commercial demonstration
# use; run here only as a black box, never read or redistributed) are extracted without installing them with
#   msiexec /a KDU841_Demo_Apps_for_Win64_231117.msi /qn TARGETDIR=<kakadu directory>
# kdu_compress.exe c32927f50bbe0a4475444fef73076c162035c44758c81baab6513a85297ab4e1
# kdu_expand.exe   123f2cd06677cf34786d552db71c07766727b9ca4a3a38b09428e44f6cd35abb
# kdu_v84R.dll     a1a910d0c2b0dfc77ce6e5e0fcc88d0942e4c20ff42cd84b3d55e76acfa16bd1
set -euo pipefail
kdu="$1"
out="$2"
mkdir -p "$out"
python -I - "$out/source.pgm" <<'EOF'
import math, random, sys
random.seed(5)
width, height = 96, 80
pixels = bytearray()
for y in range(height):
    for x in range(width):
        value = 128 + 60 * math.sin(x / 7.0) * math.cos(y / 5.0) + 30 * math.sin((x + y) / 3.0) + random.randint(-20, 20)
        pixels.append(max(0, min(255, int(value))))
open(sys.argv[1], 'wb').write(b'P5\n%d %d\n255\n' % (width, height) + bytes(pixels))
EOF
# name|levels|Cdecomp|other attributes
while IFS='|' read -r name levels decomposition attributes; do
  # shellcheck disable=SC2086
  "$kdu/kdu_compress.exe" -quiet -i "$out/source.pgm" -o "$out/$name.j2c" Creversible=no Qderived=yes \
    "Clevels=$levels" "Cdecomp=$decomposition" $attributes
  "$kdu/kdu_expand.exe" -quiet -i "$out/$name.j2c" -o "$out/$name.pgm"
done <<'EOF'
hv-b|3|H(-),V(-),B(-:-:-)|Qstep=0.02
vh-b-guard3|3|V(-),H(-),B(-:-:-)|Qstep=0.01 Qguard=3
b-hv-floor|3|B(-:-:-),H(-),V(-)|Qstep=0.03
b-subbands|3|B(H:V:-),B(-:-:-)|Qstep=0.02
mixed|3|B(B:-:H),V(-),B(-:-:-)|Qstep=0.015
nested|2|H(H),V(V)|Qstep=0.02
vertical|3|V(-),V(-),V(-)|Qstep=0.05
EOF
(cd "$out" && sha256sum -- *.j2c *.pgm | sed 's/ \*/  /' > SHA256SUMS)

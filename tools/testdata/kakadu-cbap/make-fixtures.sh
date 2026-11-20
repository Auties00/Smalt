#!/usr/bin/env bash
# Writes the committed fixtures of Jp2Part2AnchorBypassTest (smalt-image): JPEG 2000 Part 2 codestreams with code-block
# anchor points (Calign_blk_last, Scod bits 3 and 4) and the block coder extensions of annex P (Cmodes BYPASS_E1 and
# BYPASS_E2, SXcod and SXcoc), written by Kakadu's kdu_compress, and Kakadu's own decodes of them (kdu_expand, one PGM
# per component), which settle how the anchored partitions map into the sub-bands and where the position-driven
# progressions reach an anchored precinct, two points equations I-3 to I-7 of ISO/IEC 15444-2 leave inconsistent.
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
python -I - "$out" <<'EOF'
import math, random, sys
random.seed(23)
def write(name, width, height, function):
    pixels = bytes(max(0, min(255, int(function(x, y)))) for y in range(height) for x in range(width))
    open(sys.argv[1] + '/' + name, 'wb').write(b'P5\n%d %d\n255\n' % (width, height) + pixels)
write('source.pgm', 61, 47,
      lambda x, y: 128 + 60 * math.sin(x / 6.0) * math.cos(y / 4.0) + random.randint(-25, 25))
write('source-1.pgm', 31, 24, lambda x, y: 100 + 50 * math.cos(x / 3.0 + y / 5.0) + random.randint(-25, 25))
write('source-2.pgm', 61, 24, lambda x, y: 150 + 40 * math.sin(x / 2.0 - y / 7.0) + random.randint(-25, 25))
EOF
# name|components|attributes. Every codestream is reversible: Kakadu's decode of each is checked to be its source and
# not kept.
while IFS='|' read -r name components attributes; do
  if [ "$components" = 3 ]; then
    inputs="$out/source.pgm,$out/source-1.pgm,$out/source-2.pgm"
    outputs="$out/decoded-0.pgm,$out/decoded-1.pgm,$out/decoded-2.pgm"
  else
    inputs="$out/source.pgm"
    outputs="$out/decoded-0.pgm"
  fi
  # shellcheck disable=SC2086
  "$kdu/kdu_compress.exe" -quiet -i "$inputs" -o "$out/$name.j2c" Creversible=yes $attributes
  "$kdu/kdu_expand.exe" -quiet -i "$out/$name.j2c" -o "$outputs"
  cmp "$out/decoded-0.pgm" "$out/source.pgm"
  if [ "$components" = 3 ]; then
    cmp "$out/decoded-1.pgm" "$out/source-1.pgm"
    cmp "$out/decoded-2.pgm" "$out/source-2.pgm"
  fi
  rm -f "$out"/decoded-*.pgm
done <<'EOF'
anchor-lrcp|1|Clevels=3 Calign_blk_last={yes,yes} Cprecincts={16,16},{8,8} Cblk={8,8}
anchor-x-rpcl-tiles|1|Corder=RPCL Clevels=3 Calign_blk_last={no,yes} Cprecincts={8,8} Cblk={4,4} Stiles={30,21}
anchor-y-pcrl-origin|1|Corder=PCRL Clevels=4 Calign_blk_last={yes,no} Cprecincts={32,16},{16,8},{8,4} Cblk={4,8} Sorigin={5,3} Clayers=2
anchor-decomposition|1|Clevels=3 Calign_blk_last={yes,yes} Cdecomp=B(H:V:-),H(-),V(-) Cprecincts={16,16},{8,8} Cblk={8,8}
anchor-split-detail|1|Corder=RPCL Clevels=2 Calign_blk_last={yes,yes} Cdecomp=B(H:H:H),B(-:-:-) Cprecincts={16,16} Cblk={4,4}
anchor-rpcl-3c|3|Cycc=no Corder=RPCL Clevels=3 Calign_blk_last={yes,yes} Cprecincts={16,16},{8,8} Cblk={8,8} Stiles={30,21}
anchor-pcrl-3c|3|Cycc=no Corder=PCRL Clevels=3 Calign_blk_last={yes,yes} Cprecincts={16,16},{8,8} Cprecincts:C1={4,4} Cblk={4,4} Clayers=3
anchor-cprl-3c|3|Cycc=no Corder=CPRL Clevels=2 Calign_blk_last={yes,yes} Cprecincts={4,4} Cblk={4,4} Clevels:C2=4
bypass-e1|1|Clevels=3 Cblk={16,16} Cmodes=BYPASS|BYPASS_E1
bypass-e2|1|Clevels=3 Cblk={16,8} Cmodes=BYPASS|BYPASS_E2|RESET|CAUSAL|SEGMARK
bypass-e3|1|Clevels=2 Cblk={8,16} Cmodes=BYPASS|BYPASS_E1|BYPASS_E2|RESTART|ERTERM Clayers=3
bypass-components|3|Cycc=no Clevels=3 Cblk={8,8} Cmodes=BYPASS Cmodes:C1=BYPASS|BYPASS_E1|BYPASS_E2 Cmodes:C2=BYPASS|BYPASS_E2 Cprecincts={16,16},{8,8}
anchor-bypass|1|Corder=PCRL Clevels=3 Calign_blk_last={yes,yes} Cprecincts={16,16},{8,8} Cblk={8,8} Cmodes=BYPASS|BYPASS_E2 Stiles={30,21} Clayers=2
EOF
# Kakadu's reduced-resolution decodes of two anchored codestreams.
"$kdu/kdu_expand.exe" -quiet -i "$out/anchor-lrcp.j2c" -o "$out/anchor-lrcp-reduced-1.pgm" -reduce 1
"$kdu/kdu_expand.exe" -quiet -i "$out/anchor-y-pcrl-origin.j2c" -o "$out/anchor-y-pcrl-origin-reduced-2.pgm" -reduce 2
(cd "$out" && sha256sum -- *.j2c *.pgm | sed 's/ \*/  /' > SHA256SUMS)

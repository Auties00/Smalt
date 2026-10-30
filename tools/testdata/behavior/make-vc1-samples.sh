#!/usr/bin/env bash
# Writes the VC-1 container samples of the I/O behavior harness (core/src/test/resources/samples) by remuxing two
# synthetic elementary streams with ffmpeg 7.1, which has no VC-1 encoder. The inputs in vc1/ were written by the
# mpeg test helper Vc1Synthetic:
#
#   vc1/advanced.vc1  advanced(21, 128, 96, "IPPPPPPP", true), advanced(22, 128, 96, "IPPPPPPP", true) and
#                     advanced(23, 128, 96, "IPPPP", true) concatenated: three sequences, an entry point each
#   vc1/simple.rcv    rcv(simple(24, 128, 96, "IPPPIPPPIPP")): a simple profile RCV file at the default frame rate
#
# The committed files are the reference: their golden dumps are tied to these exact bytes.
#
#   bash tools/testdata/behavior/make-vc1-samples.sh [output directory]
set -euo pipefail

here="$(dirname "$0")"
out="${1:-core/src/test/resources/samples}"
mkdir -p "$out"/{mp4,matroska,ts,avi}

ff() {
    ffmpeg -hide_banner -loglevel error -y -bitexact "$@"
}

ff -f vc1 -r 25 -i "$here/vc1/advanced.vc1" -c copy -f mpegts "$out/ts/vc1.ts"
ff -f vc1 -r 25 -i "$here/vc1/advanced.vc1" -c copy "$out/mp4/vc1.mp4"
ff -i "$here/vc1/simple.rcv" -c copy "$out/avi/wmv3.avi"
ff -i "$here/vc1/simple.rcv" -c copy "$out/matroska/wmv3.mkv"

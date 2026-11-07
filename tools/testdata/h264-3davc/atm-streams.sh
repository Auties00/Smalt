#!/usr/bin/env bash
# Writes the 3D-AVC test streams mpeg/src/test/resources/com/github/auties00/smalt/video/h264/avc3d/atm-*.264 with
# the 3DV-ATM 14.0 encoder of ITU-T H.264.2 (02/2016) from the synthetic two-view sequences of atm-inputs.py.
#
# Usage: atm-streams.sh <3DV-ATM source tree> <work directory>
#
# The encoder is built from the 3DV-ATM 14.0 package with MinGW (gcc 15): lencod/Makefile gets -fcommon in CFLAGS
# and -lws2_32 in LIBS, lcommon/inc/win32.h and lcommon/src/win32.c take their POSIX branches under __MINGW32__ and
# files are opened with O_BINARY,
# and atm-encoder-fix.py replaces the user data SEI UUID derived from the clock with zeros, so every run writes the
# same bytes. A second build, in atm-cavlc, also takes atm-encoder-cavlc.py, which adds the CAVLC syntax of the texture
# coding tools and, with ATM_VSP_BIAS, biases the mode decision towards view synthesis prediction. Each stream's
# references are the encoder's reconstructed views; for the CABAC streams the 3DV-ATM 14.0 decoder (ldecod -p
# NormalizeDepth=0 -p PostDilation=0) reproduces them, while it stops on the CAVLC ones.
set -euo pipefail
ATM="$1"; WORK="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
build() {
  local BUILD="$1" CAVLC="$2"
  [ -x "$BUILD/bin/lencod.exe" ] || [ -x "$BUILD/bin/lencod" ] && return
  mkdir -p "$BUILD/bin"
  cp -r "$ATM/Makefile" "$ATM/makefile.base" "$ATM/lcommon" "$ATM/lencod" "$BUILD/"
  sed -i 's/^LIBS=   -lm/LIBS=   -lws2_32 -lm/; s/^CFLAGS=  -std=gnu99/CFLAGS=  -fcommon -std=gnu99/' "$BUILD/lencod/Makefile"
  sed -i 's/^#if defined(WIN32) || defined (WIN64)$/#if (defined(WIN32) || defined (WIN64)) \&\& !defined(__MINGW32__)/' \
    "$BUILD/lcommon/inc/win32.h"
  sed -i 's/^# define  OPENFLAGS_WRITE O_WRONLY|O_CREAT|O_TRUNC$/# define  OPENFLAGS_WRITE O_WRONLY|O_CREAT|O_TRUNC|O_BINARY/; s/^# define  OPENFLAGS_READ  O_RDONLY$/# define  OPENFLAGS_READ  O_RDONLY|O_BINARY/' \
    "$BUILD/lcommon/inc/win32.h"
  sed -i 's/^#ifdef _WIN32$/#if defined(_WIN32) \&\& !defined(__MINGW32__)/' "$BUILD/lcommon/src/win32.c"
  python -I "$HERE/atm-encoder-fix.py" "$BUILD/lencod/src/parset.c"
  [ "$CAVLC" = 0 ] || python -I "$HERE/atm-encoder-cavlc.py" "$BUILD"
  make -C "$BUILD/lencod" OPENMP=0
}
build "$WORK/atm" 0
build "$WORK/atm-cavlc" 1
encoder() {
  if [ -x "$WORK/$1/bin/lencod.exe" ]; then echo "$WORK/$1/bin/lencod.exe"; else echo "$WORK/$1/bin/lencod"; fi
}
ENCODER="$(encoder atm)"
OFF="tex.AdaptiveLuminanceCompensation=0 tex.VSP_Enable=0 tex.RLESkip=0"
stream() {
  local name="$1" frames="$2" entropy="$3" order="$4"; shift 4
  local dir="$WORK/$name"
  rm -rf "$dir"; mkdir -p "$dir"
  (cd "$dir" && python -I "$HERE/atm-inputs.py" . 128 96 "$frames" \
    && python -I "$HERE/atm-configs.py" "$ATM/configs" . 128 96 "$frames" "$entropy" "$order" BiPredMESearchRange=8 "$@" \
    && "$ENCODER" -d tex.cfg -depd dep.cfg > enc.log 2>&1)
  cp "$dir/test.264" "$WORK/$name.264"
  for view in texture_recV3 texture_recV1 depth_recV3 depth_recV1; do
    echo "$name $view $(python -I -c 'import hashlib, sys
data = open(sys.argv[1], "rb").read()
frame = 128 * 96 if "texture" in sys.argv[1] else 64 * 48
if len(data) == frame * int(sys.argv[2]):
    data = b"".join(data[i:i + frame] + bytes([128]) * (frame // 2) for i in range(0, len(data), frame))
print(hashlib.md5(data).hexdigest())' "$dir/$view.0000.yuv" "$frames")"
  done
}
# depth-range-based weighted prediction of P depth slices, camera parameters of the sequence (a depth range per view)
stream atm-drwp-p 8 1 T0D0D1T1 dep.WPMethod=2 dep.DepthRangeBasedWP=1 tex.DepthBasedMVP=0 $OFF NumberBFrames=0
# the same in P and B depth slices with a depth parameter set per access unit (the depth ranges change every frame)
stream atm-drwp-b 9 1 T0D0D1T1 dep.WPMethod=2 dep.DepthRangeBasedWP=1 tex.DepthBasedMVP=0 $OFF NumberBFrames=1 \
  SliceHeaderPred=0 acq=2
# depth-based motion vector prediction in CAVLC slices, depth-first and texture-first coding
stream atm-cavlc-dmvp 8 0 T0D0D1T1 tex.DepthBasedMVP=1 $OFF NumberBFrames=1
stream atm-cavlc-dmvp-texture-first 8 0 T0D0T1D1 tex.DepthBasedMVP=1 $OFF NumberBFrames=1
# every texture coding tool of CABAC slices in texture-first coding: view synthesis skip, direct and block-based
# prediction refined with the base view's depth, adaptive luminance compensation and run-length skip coding
stream atm-tools-texture-first 8 1 T0D0T1D1 tex.DepthBasedMVP=1 tex.VSP_Enable=1 tex.AdaptiveLuminanceCompensation=1 \
  tex.RLESkip=1 NumberBFrames=1
# the texture coding tools of CAVLC slices with the patched encoder, the view synthesis cost divided by 8 and 24:
# view synthesis skip runs, view synthesis direct and block-based view synthesis prediction, adaptive luminance
# compensation, texture-first and depth-first coding
ENCODER="$(encoder atm-cavlc)"
TOOLS="tex.DepthBasedMVP=1 tex.VSP_Enable=1 tex.AdaptiveLuminanceCompensation=1 NumberBFrames=1"
export ATM_VSP_BIAS=8
stream atm-cavlc-tools-texture-first 8 0 T0D0T1D1 $TOOLS tex.RLESkip=0
export ATM_VSP_BIAS=24
stream atm-cavlc-tools 8 0 T0D0D1T1 $TOOLS tex.RLESkip=0
# every texture coding tool of CABAC slices on 4:0:0 textures; the encoder writes luma-only reconstructions, which
# the references hash with 4:2:0 chroma planes of 128, as the 3DV-ATM decoder writes them
export ATM_VSP_BIAS=8
stream atm-tools-monochrome 8 1 T0D0T1D1 $TOOLS tex.RLESkip=1 tex.YUVFormat=0
unset ATM_VSP_BIAS

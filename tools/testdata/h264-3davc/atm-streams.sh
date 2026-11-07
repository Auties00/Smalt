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
# same bytes. Each stream's references are the encoder's reconstructed views; for the two CABAC streams the 3DV-ATM
# 14.0 decoder (ldecod -p NormalizeDepth=0 -p PostDilation=0) reproduces them, while it stops on the CAVLC ones.
set -euo pipefail
ATM="$1"; WORK="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
BUILD="$WORK/atm"
if [ ! -x "$BUILD/bin/lencod.exe" ] && [ ! -x "$BUILD/bin/lencod" ]; then
  mkdir -p "$BUILD/bin"
  cp -r "$ATM/Makefile" "$ATM/makefile.base" "$ATM/lcommon" "$ATM/lencod" "$BUILD/"
  sed -i 's/^LIBS=   -lm/LIBS=   -lws2_32 -lm/; s/^CFLAGS=  -std=gnu99/CFLAGS=  -fcommon -std=gnu99/' "$BUILD/lencod/Makefile"
  sed -i 's/^#if defined(WIN32) || defined (WIN64)$/#if (defined(WIN32) || defined (WIN64)) \&\& !defined(__MINGW32__)/' \
    "$BUILD/lcommon/inc/win32.h"
  sed -i 's/^# define  OPENFLAGS_WRITE O_WRONLY|O_CREAT|O_TRUNC$/# define  OPENFLAGS_WRITE O_WRONLY|O_CREAT|O_TRUNC|O_BINARY/; s/^# define  OPENFLAGS_READ  O_RDONLY$/# define  OPENFLAGS_READ  O_RDONLY|O_BINARY/' \
    "$BUILD/lcommon/inc/win32.h"
  sed -i 's/^#ifdef _WIN32$/#if defined(_WIN32) \&\& !defined(__MINGW32__)/' "$BUILD/lcommon/src/win32.c"
  python -I "$HERE/atm-encoder-fix.py" "$BUILD/lencod/src/parset.c"
  make -C "$BUILD/lencod" OPENMP=0
fi
ENCODER="$BUILD/bin/lencod.exe"
[ -x "$ENCODER" ] || ENCODER="$BUILD/bin/lencod"
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
    echo "$name $view $(md5sum < "$dir/$view.0000.yuv" | cut -c1-32)"
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

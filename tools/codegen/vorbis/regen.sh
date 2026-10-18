#!/usr/bin/env bash
# Regenerates audio/src/main/resources/com/github/auties00/smalt/audio/vorbis/internal/vorbis-setup.bin.gz
# from the libvorbis 1.3.7 sources (BSD-3, Xiph.Org). Needs curl, tar, gzip, sha256sum and a C compiler.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../../audio/src/main/resources/com/github/auties00/smalt/audio/vorbis/internal/vorbis-setup.bin.gz"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
curl -sSfLO https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.gz
curl -sSfLO https://downloads.xiph.org/releases/ogg/libogg-1.3.5.tar.gz
echo "0e982409a9c3fc82ee06e08205b1355e5c6aa4c36bca58146ef399621b0ce5ab  libvorbis-1.3.7.tar.gz" | sha256sum -c -
echo "0eb4b4b9420a0f51db142ba3f9c64b333f826532dc0f48c6410ae51f4799b664  libogg-1.3.5.tar.gz" | sha256sum -c -
tar xzf libvorbis-1.3.7.tar.gz
tar xzf libogg-1.3.5.tar.gz
cat > libogg-1.3.5/include/ogg/config_types.h <<'EOF'
#ifndef __CONFIG_TYPES_H__
#define __CONFIG_TYPES_H__
#include <stdint.h>
typedef int16_t ogg_int16_t;
typedef uint16_t ogg_uint16_t;
typedef int32_t ogg_int32_t;
typedef uint32_t ogg_uint32_t;
typedef int64_t ogg_int64_t;
typedef uint64_t ogg_uint64_t;
#endif
EOF
cp "$here/dump.c" .
sources=""
for f in analysis bitrate block codebook envelope floor0 floor1 info lookup lpc lsp mapping0 mdct psy \
         registry res0 sharedbook smallft synthesis window; do
  sources="$sources libvorbis-1.3.7/lib/$f.c"
done
${CC:-cc} -O1 -w -Ilibogg-1.3.5/include -Ilibvorbis-1.3.7/include -Ilibvorbis-1.3.7/lib -o dump dump.c \
  $sources libogg-1.3.5/src/bitwise.c libogg-1.3.5/src/framing.c -lm
./dump vorbis-setup.bin
gzip -9 -n -c vorbis-setup.bin > "$out"
echo "wrote $out"

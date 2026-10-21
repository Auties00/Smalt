#!/usr/bin/env bash
# Regenerates the image samples of the I/O behavior harness (image/src/test/resources/samples) with
# ffmpeg 7.1 and libtiff's tiffcp from ffmpeg's synthetic testsrc2 source. As for make-samples.sh, the
# committed files are the reference their golden dumps are tied to.
#
#   bash tools/testdata/behavior/make-image-samples.sh [output directory]
set -euo pipefail

out="${1:-image/src/test/resources/samples}"
mkdir -p "$out"/{gif,bmp,ico,tiff,webp,png,jp2,jpeg}

ff() {
    ffmpeg -hide_banner -loglevel error -y -bitexact "$@"
}

still="testsrc2=size=48x32:rate=1:duration=1"
moving="testsrc2=size=48x32:rate=5:duration=1"

ff -f lavfi -i "$moving" -vf "split[a][b];[a]palettegen=max_colors=32[p];[b][p]paletteuse" -loop 0 "$out/gif/animated.gif"
ff -f lavfi -i "$still" -frames:v 1 -vf "split[a][b];[a]palettegen=max_colors=16:reserve_transparent=1[p];[b][p]paletteuse" \
    "$out/gif/still.gif"

ff -f lavfi -i "$still" -frames:v 1 -pix_fmt bgr24 "$out/bmp/rgb24.bmp"
ff -f lavfi -i "$still" -frames:v 1 -vf "split[a][b];[a]palettegen=max_colors=64[p];[b][p]paletteuse" -pix_fmt pal8 \
    "$out/bmp/pal8.bmp"
ff -f lavfi -i "$still" -frames:v 1 -vf "format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='X*5'" -pix_fmt bgra \
    "$out/bmp/bgra.bmp"

ff -f lavfi -i "testsrc2=size=32x32:rate=1:duration=1" -frames:v 1 -c:v png -pix_fmt rgba "$out/ico/icon.ico"

# The animated cursor and the GIF with an ICC profile are assembled by assemble-samples.py from files ffmpeg
# writes; the profile is the CC0 Display P3 profile of saucecontrol's Compact-ICC-Profiles (the smoke vector
# compact-icc-DisplayP3-v4.icc of tools/testdata/vectors.tsv, fetched by testdata.py into the cache).
here="$(dirname "$0")"
icons="$(mktemp -d)"
ff -f lavfi -i "testsrc2=size=32x32:rate=5:duration=1" -vf "trim=start_frame=0,setpts=PTS-STARTPTS" -frames:v 1 \
    -c:v png -pix_fmt rgba "$icons/0.ico"
ff -f lavfi -i "testsrc2=size=32x32:rate=5:duration=1" -vf "trim=start_frame=2,setpts=PTS-STARTPTS" -frames:v 1 \
    -c:v bmp -pix_fmt bgra "$icons/1.ico"
ff -f lavfi -i "testsrc2=size=32x32:rate=5:duration=1" -vf "trim=start_frame=4,setpts=PTS-STARTPTS" -frames:v 1 \
    -c:v png -pix_fmt rgba "$icons/2.ico"
python -I "$here/assemble-samples.py" ani "$out/ico/animated.ani" --rate 6,3,9,3 --seq 0,1,2,1 --title "Smalt sample" \
    --artist "ffmpeg testsrc2" --hotspot 4,5 "$icons/0.ico" "$icons/1.ico" "$icons/2.ico"
rm -rf "$icons"
profile="${SMALT_CONFORMANCE_CACHE:-$HOME/.smalt/cache/conformance}/compact-icc-DisplayP3-v4.icc"
python -I "$here/assemble-samples.py" gif-icc "$out/gif/still.gif" "$profile" "$out/gif/icc.gif"

ff -f lavfi -i "$still" -frames:v 1 -c:v tiff -compression_algo lzw -pix_fmt rgb24 "$out/tiff/lzw.tif"
ff -f lavfi -i "$still" -frames:v 1 -c:v tiff -compression_algo deflate -pix_fmt rgb48le "$out/tiff/deflate16.tif"
ff -f lavfi -i "$still" -frames:v 1 -c:v tiff -compression_algo packbits -pix_fmt gray "$out/tiff/packbits-gray.tif"
pages="$(mktemp -d)"
ff -f lavfi -i "testsrc2=size=40x24:rate=1:duration=1" -frames:v 1 -c:v tiff -compression_algo raw -pix_fmt rgba \
    "$pages/b.tif"
tiffcp -t -w 16 -l 16 "$out/tiff/lzw.tif" "$out/tiff/tiled.tif"
tiffcp "$out/tiff/lzw.tif" "$pages/b.tif" "$out/tiff/pages.tif"
ff -f lavfi -i "$still" -frames:v 1 -c:v tiff -compression_algo raw -pix_fmt monob "$pages/mono.tif"
tiffcp -r 8 -c g4 "$pages/mono.tif" "$out/tiff/g4.tif"
tiffcp -r 8 -c g3:2d:fill "$pages/mono.tif" "$out/tiff/g3-2d.tif"
tiffcp -r 16 -c jpeg:75 "$out/tiff/lzw.tif" "$out/tiff/jpeg-ycbcr.tif"
rm -rf "$pages"

ff -f lavfi -i "$moving" -c:v libwebp_anim -lossless 0 -q:v 50 -loop 0 "$out/webp/animated.webp"

ff -f lavfi -i "$moving" -plays 0 -f apng "$out/png/animated.png"
ff -f lavfi -i "$still" -frames:v 1 -pix_fmt rgba64be "$out/png/rgba16.png"

ff -f lavfi -i "$still" -frames:v 1 -c:v jpeg2000 -format jp2 -tile_width 16 -tile_height 16 -pix_fmt rgb24 \
    "$out/jp2/tiles.jp2"
ff -f lavfi -i "$still" -frames:v 1 -c:v mjpeg -q:v 4 -pix_fmt yuvj420p "$out/jpeg/yuv420.jpg"
ff -f lavfi -i "$still" -frames:v 1 -c:v mjpeg -q:v 4 -pix_fmt yuvj444p "$out/jpeg/yuv444.jpg"

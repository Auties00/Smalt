#!/usr/bin/env bash
# Regenerates the 12-bit JPEG test vectors of smalt-image (cjpeg12/ and djpeg12-md5.csv) with libjpeg-turbo
# 3.1.3 (cjpeg, djpeg) and Python 3.
# Sources: testorig12.jpg of libjpeg-turbo (vectors.tsv ljt-testorig12.jpg) decoded by djpeg to a 12-bit PPM,
# cropped at the top left to 227x149, 33x65, 17x1 and 1x17; and the 64x48 synthetic pattern of pnm12.py synth.
# Usage: make-vectors.sh <testorig12.jpg> <smalt checkout>
set -euo pipefail
SOURCE="$1"
ROOT="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/image/src/test/resources/com/github/auties00/smalt/image/jpeg/cjpeg12"
CSV="$ROOT/image/src/test/resources/com/github/auties00/smalt/image/jpeg/djpeg12-md5.csv"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
rm -rf "$OUT"
mkdir -p "$OUT"
djpeg -dct int "$SOURCE" > "$WORK/testorig12.ppm"
for size in 227x149 33x65 17x1 1x17; do
    python "$HERE/pnm12.py" crop "$WORK/testorig12.ppm" "$WORK/$size.ppm" "${size%x*}" "${size#*x}"
done
python "$HERE/pnm12.py" synth "$WORK/64x48.ppm" 64 48

layout_args() {
    case "$1" in
        yuv420) echo "-sample 2x2" ;;
        yuv422) echo "-sample 2x1" ;;
        yuv444) echo "-sample 1x1" ;;
        yuv440) echo "-sample 1x2" ;;
        yuv411) echo "-sample 4x1" ;;
        monochrome) echo "-grayscale" ;;
        rgb) echo "-rgb" ;;
    esac
}

mode_args() {
    case "$1" in
        baseline) echo "" ;;
        progressive) echo "-progressive" ;;
        arithmetic) echo "-arithmetic" ;;
        progressive-arithmetic) echo "-progressive -arithmetic" ;;
    esac
}

layout_name() {
    case "$1" in
        yuv420) echo YUV420 ;;
        yuv422) echo YUV422 ;;
        yuv444) echo YUV444 ;;
        yuv440) echo YUV440 ;;
        yuv411) echo YUV411 ;;
        monochrome) echo MONOCHROME ;;
        rgb) echo RGB ;;
    esac
}

# encode <size> <layout> <mode> <restart> <quality>
encode() {
    local size="$1" layout="$2" mode="$3" restart="$4" quality="$5"
    local name="$size-$layout-$mode"
    local args="-precision 12 -dct int -quality $quality $(layout_args "$layout") $(mode_args "$mode")"
    if [ "$restart" != 0 ]; then
        name="$name-rst$restart"
        args="$args -restart $restart"
    fi
    if [ "$quality" != 75 ]; then
        name="$name-q$quality"
    fi
    # shellcheck disable=SC2086
    cjpeg $args "$WORK/$size.ppm" > "$OUT/$name.jpg"
    local w="${size%x*}" h="${size#*x}"
    for scale in 1/1 1/2 1/4 1/8; do
        local md5
        md5=$(djpeg -dct int -scale "$scale" "$OUT/$name.jpg" | md5sum | cut -d' ' -f1)
        echo "$name.jpg,$w,$h,$(layout_name "$layout"),$mode,$restart,$scale,$md5" >> "$CSV.tmp"
    done
}

echo "# file, width, height, layout, mode, restart, scale, md5 of djpeg -dct int (libjpeg-turbo 3.1.3) PPM/PGM output (maxval 4095); made by tools/testdata/jpeg12/make-vectors.sh" > "$CSV.tmp"
for layout in yuv420 yuv422 yuv444 yuv440 yuv411 monochrome; do
    for mode in baseline progressive arithmetic progressive-arithmetic; do
        encode 227x149 "$layout" "$mode" 0 75
    done
done
for mode in baseline progressive arithmetic progressive-arithmetic; do
    encode 227x149 yuv420 "$mode" 3 75
done
encode 227x149 rgb baseline 0 75
encode 227x149 rgb progressive 0 75
encode 227x149 yuv420 baseline 0 100
encode 227x149 monochrome progressive 0 10
for size in 33x65 17x1 1x17; do
    for layout in yuv420 yuv422 yuv444 yuv440 yuv411 monochrome; do
        for mode in baseline progressive progressive-arithmetic; do
            encode "$size" "$layout" "$mode" 0 75
        done
    done
done
for layout in yuv420 yuv444 monochrome rgb; do
    for mode in baseline progressive arithmetic; do
        encode 64x48 "$layout" "$mode" 0 100
    done
done
mv "$CSV.tmp" "$CSV"
(cd "$OUT" && sha256sum -- *.jpg) > "$OUT.sha256"

# A Motion-JPEG AVI of two 12-bit frames, muxed without re-encoding by ffmpeg 7.1
mkdir -p "$WORK/avi"
cp "$OUT/33x65-yuv420-baseline.jpg" "$WORK/avi/f1.jpg"
cp "$OUT/33x65-yuv420-progressive.jpg" "$WORK/avi/f2.jpg"
ffmpeg -hide_banner -loglevel error -y -framerate 2 -i "$WORK/avi/f%d.jpg" -c:v copy -fflags +bitexact \
    -flags:v +bitexact "$ROOT/image/src/test/resources/com/github/auties00/smalt/image/jpeg/mjpeg12.avi"

TRAN="$ROOT/image/src/test/resources/com/github/auties00/smalt/image/jpeg/jpegtran12-md5.csv"
echo "# file;jpegtran switches;md5 of the jpegtran (libjpeg-turbo 3.1.3) output; made by tools/testdata/jpeg12/make-vectors.sh" > "$TRAN"
for file in 227x149-yuv420-baseline.jpg 227x149-yuv422-progressive.jpg 33x65-yuv411-progressive-arithmetic.jpg \
        227x149-monochrome-arithmetic.jpg 64x48-rgb-baseline-q100.jpg; do
    for switches in "-copy none" "-rotate 90" "-rotate 270 -trim" "-flip horizontal -trim" "-transpose -optimize" \
            "-progressive" "-crop 20x18+9+5" "-grayscale" "-transverse -progressive -restart 2B"; do
        # shellcheck disable=SC2086
        if md5=$(jpegtran $switches "$OUT/$file" 2>/dev/null | md5sum | cut -d' ' -f1) \
                && jpegtran $switches "$OUT/$file" > /dev/null 2>&1; then
            echo "$file;$switches;$md5" >> "$TRAN"
        else
            echo "$file;$switches;fails" >> "$TRAN"
        fi
    done
done

# JPEG-compressed 12-bit TIFF files (tiff12.py) and the MD5 of the uncompressed samples libtiff 4.7.1 decodes
# from them (tiffcp -c none -s, which converts YCbCr to RGB through libjpeg). libtiff drops the last sample of
# a row with an odd number of samples, so the libtiff MD5 of the odd-width file is "skip".
TIFF="$ROOT/image/src/test/resources/com/github/auties00/smalt/image/tiff/jpeg12"
rm -rf "$TIFF"
mkdir -p "$TIFF"
python "$HERE/pnm12.py" crop "$WORK/testorig12.ppm" "$WORK/226x149.ppm" 226 149
TIFFCSV="$TIFF.csv"
echo "# file;width;height;md5 of the samples libtiff 4.7.1 decodes (tiffcp -c none -s); made by tools/testdata/jpeg12/make-vectors.sh" > "$TIFFCSV"
# tiff <name> <source> <tiff12.py layout options> -- <cjpeg switches>
tiff() {
    local name="$1" source="$2"
    shift 2
    python "$HERE/tiff12.py" "$WORK/$source.ppm" "$TIFF/$name.tif" "$@"
    local md5=skip
    if [ "${source%x*}" = 226 ]; then
        tiffcp -c none -s -r 100000 "$TIFF/$name.tif" "$WORK/raw.tif"
        md5=$(python "$HERE/tiff12.py" raw-md5 "$WORK/raw.tif")
    fi
    echo "$name.tif;${source%x*};${source#*x};$md5" >> "$TIFFCSV"
}
tiff 226x149-ycbcr420-strips32 226x149 --strip 32 -- -sample 2x2
tiff 226x149-ycbcr422-tiles64-tables 226x149 --tile 64 --tables -- -sample 2x1
tiff 226x149-ycbcr444-strips48-progressive 226x149 --strip 48 -- -sample 1x1 -progressive
tiff 226x149-gray-strips16-tables 226x149 --strip 16 --tables -- -grayscale
tiff 226x149-rgb-tiles32-arithmetic 226x149 --tile 32 -- -rgb -arithmetic
tiff 227x149-ycbcr420-strips32-tables 227x149 --strip 32 --tables -- -sample 2x2
(cd "$TIFF" && sha256sum -- *.tif) > "$TIFF.sha256"

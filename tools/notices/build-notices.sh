#!/usr/bin/env bash
# Assembles THIRD-PARTY-NOTICES (next to this script), the combined notices of every Smalt
# artifact, from the upstream license files in ./licenses. License texts are concatenated
# verbatim; only the prose written here (headers, package lists) is authored.
# split-notices.py then writes each artifact's META-INF/THIRD-PARTY-NOTICES from it.
set -euo pipefail

LIC="$(cd "$(dirname "$0")/licenses" && pwd)"
OUT="$(cd "$(dirname "$0")" && pwd)/THIRD-PARTY-NOTICES"

RULE='==============================================================================='
SUB='-------------------------------------------------------------------------------'

section() {
  printf '\n\n%s\n%s\n%s\n\n' "$RULE" "$1" "$RULE"
}

license_start() {
  printf '\n%s\n%s\n%s\n\n' "$SUB" "$1" "$SUB"
}

# Prints the copyright block of a libjpeg-turbo source file: the lines between the
# file name and "For conditions of distribution", without the comment prefix.
jt_header() {
  awk 'NR == 1 { next }
       /For conditions of distribution/ { exit }
       { sub(/^ \* ?/, ""); sub(/^ \*$/, ""); print }' "$LIC/jt/$1" \
    | sed '/^$/d' | sed '1s/^/  /; 2,$s/^/    /'
}

{
cat <<'EOF'
Smalt: Third-Party Notices
==========================

Smalt (https://github.com/Auties00/Smalt) is a pure-Java media library
licensed under the MIT License.

Smalt is written in Java, but parts of it are ports of, or are
otherwise derived from, the open source projects listed below: C code
translated to Java, tables copied from the C sources, or algorithms and code
structure followed closely. Each section names the project, where its code
and license text were obtained, the Smalt packages that derive from it, the
upstream copyright notices, and the full upstream license text, reproduced
as published upstream.

The derived Java code is NOT the original software. It has been translated
from C to Java and modified: restructured, adapted to Smalt's APIs,
extended, and in places vectorized with the Java Vector API. The Java
sources that derive from a project name the upstream files or functions they
follow in their documentation.

This software is based in part on the work of the Independent JPEG Group.

All package names below are relative to com.github.auties00.smalt.

Contents
  1.  libopus
  2.  KISS FFT (as modified in libopus)
  3.  libwebp
  4.  libvpx
  5.  libjpeg-turbo and the Independent JPEG Group's JPEG software
  6.  VisualOn AAC encoder (vo-aacenc)
  7.  libvorbis
  8.  libogg
  9.  libFLAC
  10. Pillow
  11. OpenH264
  12. H.264/AVC JM reference software
  13. HEVC HM reference software
  14. fdlibm (Freely Distributable LIBM)
  15. WebRTC (audio processing module)
  16. Ooura FFT
  17. musl libc math
  18. dav1d
  19. libaom
  20. libavif
  21. giflib
  22. NeuQuant
  23. Little CMS
  24. PacketVideo AAC decoder (aacdec)
  25. LibTIFF
  26. Apple Lossless Audio Codec (ALAC)
  27. libebur128
  28. smartcrop.js
  29. opencore-amr (AMR-NB codec and AMR-WB decoder)
  30. VisualOn AMR-WB encoder (vo-amrwbenc)
  31. OpenJPEG
  32. jxl-oxide
  33. libjxl
  34. Brotli
  35. colour-demosaicing
  36. arw (Go package by Sjon Kosse)
  37. Adobe Digital Negative (DNG) specification
  38. rawtoaces-data (camera spectral sensitivities)
  39. libdeflate
  40. X3F tools
  41. opusfile
  42. libxaac
  43. LERC (Limited Error Raster Compression)

The H.264 decoder and encoder implement a standard covered by patents. The
licenses below cover the code only; they grant no patent rights, which are
licensed separately (for H.264, through the Via LA AVC patent pool).
EOF

section "1. libopus"
cat <<'EOF'
Project:      libopus, the reference implementation of the Opus audio codec,
              version 1.5.2
Home:         https://opus-codec.org/
Source:       https://gitlab.xiph.org/xiph/opus
License text: https://raw.githubusercontent.com/xiph/opus/v1.5.2/COPYING
License:      BSD 3-Clause (with the Opus royalty-free patent licenses)

Derived Smalt packages:
  audio.opus
  audio.opus.internal
  audio.opus.internal.celt
  audio.opus.internal.silk

Derivation: a port of the float build of libopus 1.5.2 (CELT, SILK, the
Opus framing, multistream, projection and repacketizer layers, the
ambisonic mixing and demixing matrices, the tonality analysis and its MLP),
including tables copied from the C sources.

Copyright:
  Copyright 2001-2023 Xiph.Org, Skype Limited, Octasic,
                      Jean-Marc Valin, Timothy B. Terriberry,
                      CSIRO, Gregory Maxwell, Mark Borgerding,
                      Erik de Castro Lopo, Mozilla, Amazon
  Copyright (c) 2017 Google Inc. (src/mapping_matrix.c,
                      src/opus_projection_encoder.c,
                      src/opus_projection_decoder.c)
EOF
license_start "libopus license (COPYING)"
cat "$LIC/opus-COPYING"
license_start "License of the projection files (header of src/mapping_matrix.c)"
cat "$LIC/opus-mapping_matrix.c"

section "2. KISS FFT (as modified in libopus)"
cat <<'EOF'
Project:      KISS FFT by Mark Borgerding, in the modified form shipped as
              celt/kiss_fft.c in libopus 1.5.2
Source:       https://gitlab.xiph.org/xiph/opus
License text: https://raw.githubusercontent.com/xiph/opus/v1.5.2/celt/kiss_fft.c
              (file header)
License:      BSD-style

Derived Smalt packages:
  audio.opus.internal.celt (KissFft, KissFftState, FftSoa), used by audio.opus.internal

Derivation: a port of the float build of celt/kiss_fft.c and kiss_fft.h.

Copyright: see the first lines of the license text below.
EOF
license_start "KISS FFT license (header of celt/kiss_fft.c)"
sed -n '1,30p' "$LIC/opus-kiss_fft.c" \
  | sed '1s|^/\*|  |; 29s|^/\* ||; s|\*/$||; s/[[:space:]]*$//'

section "3. libwebp"
cat <<'EOF'
Project:      libwebp, the WebP codec library of the WebM Project, version 1.6.0
Home:         https://developers.google.com/speed/webp
Source:       https://chromium.googlesource.com/webm/libwebp
              (mirror: https://github.com/webmproject/libwebp)
License text: https://raw.githubusercontent.com/webmproject/libwebp/v1.6.0/COPYING
License:      BSD 3-Clause

Derived Smalt packages:
  image.webp
  image.webp.internal.alpha
  image.webp.internal.anim
  image.webp.internal.color
  image.webp.internal.lossless
  image.webp.internal.riff
  video.vp8.internal

Derivation: the WebP decoder and encoder (VP8L lossless codec, alpha
coding and filters, animation encoding and decoding, RIFF container rules,
YUV conversion) follow libwebp's src/dec, src/enc, src/dsp, src/utils,
src/mux and src/demux; the lossy VP8 encoder in video.vp8.internal is a
port of libwebp 1.6.0's src/enc and src/dsp encoder, including tables
copied verbatim.

Copyright:
  Copyright (c) 2010, Google Inc. All rights reserved.

libwebp also publishes an "Additional IP Rights Grant (Patents)" in its
PATENTS file (https://raw.githubusercontent.com/webmproject/libwebp/v1.6.0/PATENTS).
It is a grant by Google, not a condition of the license, and is not
reproduced here.
EOF
license_start "libwebp license (COPYING)"
cat "$LIC/webp-COPYING"

section "4. libvpx"
cat <<'EOF'
Project:      libvpx, the VP8/VP9 codec SDK of the WebM Project
Home:         https://www.webmproject.org/code/
Source:       https://chromium.googlesource.com/webm/libvpx
              (mirror: https://github.com/webmproject/libvpx)
License text: https://raw.githubusercontent.com/webmproject/libvpx/main/LICENSE
License:      BSD 3-Clause

Derived Smalt packages:
  video.vp8.internal
  video.vp9
  video.vp9.internal

Derivation: the VP8 decoder is a port of libvpx's single-threaded VP8
decoder (vp8/decoder, vp8/common and the VP8 variants of vpx_dsp intra
prediction), including its C kernels and the tables copied verbatim from
vp8/common and vp8/decoder. The real-time VP8 encoder (Vp8Encoder) is a
port of libvpx 1.17.0's one-pass real-time CBR path (vp8/encoder: onyx_if.c,
encodeframe.c, rdopt.c, pickinter.c, mcomp.c, encodemb.c, tokenize.c,
bitstream.c, ratectrl.c, picklpf.c, encodemv.c, modecosts.c, segmentation.c,
vp8_skin_detection.c, and vp8/common/findnearmv.c) with the vpx_dsp C
kernels it uses and its speed, threshold and cost tables. The VP9 decoder is
a port of libvpx's VP9 decoder (vp9/decoder, vp9/common, vp9/vp9_dx_iface.c
and the vpx_dsp C kernels it uses: inverse transforms, sub-pixel
convolution, loop filters, intra prediction and their high bit depth
variants), including the probability, scan, quantizer and filter tables
copied from the C sources. The real-time VP9 encoder (Vp9VideoEncoder) is a
port of libvpx 1.17.0's one-pass real-time CBR path (vp9/encoder:
vp9_encoder.c, vp9_ratectrl.c, vp9_pickmode.c, vp9_aq_cyclicrefresh.c,
vp9_mcomp.c, vp9_encodeframe.c, vp9_encodemb.c, vp9_tokenize.c,
vp9_bitstream.c, vp9_subexp.c, vp9_segmentation.c, vp9_speed_features.c,
vp9_svc_layercontext.c, vp9_noise_estimate.c, vp9_skin_detection.c,
vp9_rd.c, vp9_picklpf.c, vp9_quantize.c, vp9_dct.c, vp9_encodemv.c and
vp9_cost.c) with the vpx_dsp C kernels it uses (boolean encoder, forward
transforms, sums of absolute differences, variances, Hadamard transforms,
integral projections and skin detection) and its speed, threshold, rate
model and cost tables.

Copyright:
  Copyright (c) 2010, The WebM Project authors. All rights reserved.

libvpx also publishes an "Additional IP Rights Grant (Patents)" in its
PATENTS file (https://raw.githubusercontent.com/webmproject/libvpx/main/PATENTS).
It is a grant by Google, not a condition of the license, and is not
reproduced here.
EOF
license_start "libvpx license (LICENSE)"
cat "$LIC/vpx-LICENSE"

section "5. libjpeg-turbo and the Independent JPEG Group's JPEG software"
cat <<'EOF'
Project:      libjpeg-turbo, which contains the Independent JPEG Group's
              libjpeg code with libjpeg-turbo modifications
Home:         https://libjpeg-turbo.org/
Source:       https://github.com/libjpeg-turbo/libjpeg-turbo
License text: https://raw.githubusercontent.com/libjpeg-turbo/libjpeg-turbo/main/LICENSE.md
              https://raw.githubusercontent.com/libjpeg-turbo/libjpeg-turbo/main/README.ijg
License:      IJG License (see README.ijg); the libjpeg-turbo license
              overview (LICENSE.md) is reproduced as well

Derived Smalt packages:
  image.jpeg
  image.jpeg.internal
  image.ops (ColorMatrix, ColorKernel, ScalarColorKernel, ChromaUpsampling)
  video.mpeg4.internal (ScalarMpeg4Dsp, VectorMpeg4Dsp)

Derivation: ports of the libjpeg API library code of libjpeg-turbo: the
marker reader, the sequential and progressive Huffman decoders, the
arithmetic decoder, restart resynchronization, the accurate and reduced-size
integer inverse DCTs, the accurate integer forward DCT, Huffman table
optimization, the Huffman bit writer, color conversion, chroma upsampling
and downsampling, quality scaling, quantization reciprocals, and ICC profile
reassembly, including tables and constants taken from the C sources. The
lossless transforms (JpegTransforms, JpegTransformOptions and, in
image.jpeg.internal, LosslessTransform, CoefficientWriter and
JpegTranscoder) port the jpegtran sample program: the coefficient
rotations, flips, transpositions and crops of transupp.c, the transcoding
of jctrans.c, the marker writer of jcmarker.c, the progressive Huffman
encoder of jcphuff.c, the pass sequence of jcmaster.c, and the parameter
handling of jpegtran.c. The accurate integer inverse DCT is also the inverse
DCT of the MPEG-4 Part 2 and H.263 decoders.

This software is based in part on the work of the Independent JPEG Group.

Copyright (as stated in the headers of the libjpeg-turbo source files the
Java code derives from):
EOF
for f in jdmarker.c jdapimin.c jdmaster.c jdhuff.c jdphuff.c jdarith.c jaricom.c jidctint.c jidctred.c jfdctint.c jchuff.c jcdctmgr.c jcparam.c jdcolor.c jccolor.c jdsample.c jcsample.c jdicc.c jutils.c transupp.c jctrans.c jcphuff.c jcmarker.c jcmaster.c jpegtran.c; do
  jt_header "$f"
done
license_start "libjpeg-turbo license overview (LICENSE.md)"
cat "$LIC/jt-LICENSE.md"
license_start "The Independent JPEG Group's README, including the IJG License (README.ijg)"
cat "$LIC/jt-README.ijg"

section "6. VisualOn AAC encoder (vo-aacenc)"
cat <<'EOF'
Project:      VisualOn AAC encoder, as shipped in the Android Open Source
              Project (frameworks/av/media/libstagefright/codecs/aacenc) and
              maintained standalone as vo-aacenc in the opencore-amr project
Source:       https://android.googlesource.com/platform/frameworks/av
              https://github.com/mstorsjo/vo-aacenc
License text: https://raw.githubusercontent.com/mstorsjo/vo-aacenc/master/COPYING
NOTICE:       https://raw.githubusercontent.com/mstorsjo/vo-aacenc/master/NOTICE
License:      Apache License, Version 2.0

Derived Smalt packages:
  audio.aac.internal (HuffmanTables; BandLayout, EncoderChannel,
  EncoderEngine, TnsEncoder, TransientDetector)
  container.internal (AacHuffmanTables)

Derivation: the AAC Huffman codeword and length values are taken from the
tables of aacenc/src/aac_rom.c. The encoder's psychoacoustic model, block
switching, M/S decision, bit reservoir control and TNS parameters follow the
3GPP TS 26.403 encoder as implemented there. The Java code is a new
implementation and differs from the original (for example, in its rate
control); it is not a copy of the original files.

Copyright:
  Copyright 2003-2010, VisualOn, Inc.

Attribution notices from the upstream NOTICE file (Apache License 2.0,
section 4(d)):
EOF
printf '\n'
sed -n '1,9p' "$LIC/vo-NOTICE"
cat <<'EOF'

(The upstream NOTICE file continues with a copy of the Apache License 2.0,
reproduced below from COPYING, and with a Unicode, Inc. data files license
that does not pertain to any part of Smalt.)
EOF
license_start "Apache License, Version 2.0 (vo-aacenc COPYING)"
cat "$LIC/vo-COPYING"

section "7. libvorbis"
cat <<'EOF'
Project:      libvorbis, the Xiph.Org Vorbis reference implementation
Home:         https://xiph.org/vorbis/
Source:       https://gitlab.xiph.org/xiph/vorbis
License text: https://gitlab.xiph.org/xiph/vorbis/-/raw/main/COPYING
License:      BSD 3-Clause

Derived Smalt packages:
  audio.vorbis (Codebook, Floor0, Residue, VorbisEncoder)
  audio.vorbis.internal (the encoder; and the resource vorbis-setup.bin.gz)

Derivation: codeword assignment follows _make_words and the decode-table
hints of dec_firsttable (lib/sharedbook.c); floor 0 follows lib/floor0.c and
the exact vorbis_lsp_to_curve of lib/lsp.c, reproducing their mix of single
and double precision; residue partition bounds follow
vorbis_book_decodev_add.

The Vorbis encoder is a port of the libvorbis 1.3.7 encoder in its quality
mode (lib/vorbisenc.c, block.c, envelope.c, psy.c, floor1.c, res0.c,
mapping0.c, mdct.c, smallft.c, lpc.c, window.c, sharedbook.c, codebook.c and
info.c, including the aoTuV tuning merged into libvorbis), translated to Java
with libvorbis's single and double precision arithmetic kept. The setup
templates, static codebooks, psychoacoustic tables and window tables of
libvorbis 1.3.7 are copied, serialized by tools/codegen/vorbis/dump.c, into the
resource vorbis-setup.bin.gz.

Copyright:
  Copyright (c) 2002-2020 Xiph.org Foundation
  lib/lpc.c additionally: Copyright 1992, 1993, 1994 by Jutta Degener and
  Carsten Bormann, Technische Universita"t Berlin (notice below)
EOF
license_start "libvorbis license (COPYING)"
cat "$LIC/vorbis-COPYING"
license_start "lib/lpc.c notice (Jutta Degener and Carsten Bormann)"
cat "$LIC/vorbis-lpc.c-notice"

section "8. libogg"
cat <<'EOF'
Project:      libogg, the Xiph.Org Ogg container library
Home:         https://xiph.org/ogg/
Source:       https://gitlab.xiph.org/xiph/ogg
License text: https://gitlab.xiph.org/xiph/ogg/-/raw/main/COPYING
License:      BSD 3-Clause

Derived Smalt packages:
  container.ogg (OggPageReader, OggParser)

Derivation: page resynchronization follows ogg_sync_pageseek, and packet
reassembly across lost or continued pages follows libogg's rules.

Copyright:
  Copyright (c) 2002, Xiph.org Foundation
EOF
license_start "libogg license (COPYING)"
cat "$LIC/ogg-COPYING"

section "9. libFLAC"
cat <<'EOF'
Project:      libFLAC, the Xiph.Org FLAC reference library
Home:         https://xiph.org/flac/
Source:       https://github.com/xiph/flac
License text: https://raw.githubusercontent.com/xiph/flac/master/COPYING.Xiph
License:      BSD 3-Clause (the Xiph license under which libFLAC is released)

Derived Smalt packages:
  audio.flac (FlacDecoder, FlacLpc, FlacEncoder, FlacEncoderOptions,
  FlacFrameEncoder, FlacBitWriter, FlacWindow, FlacApodization and the
  FlacEncoderKernel implementations)

Derivation: the decoder follows the structure of src/libFLAC/stream_decoder.c
(residuals decoded in place and restored by the predictor), and the linear
prediction restore mirrors the per-order unrolled loops of
src/libFLAC/lpc.c. The encoder follows src/libFLAC/stream_encoder.c (the
compression level table, the stereo, subframe, predictor order and
coefficient precision decisions, the Rice parameter estimation and partition
order search, the seek table filling), stream_encoder_framing.c (the frame
and subframe writers), lpc.c (windowing, autocorrelation, Levinson-Durbin
recursion, coefficient quantization, residual filters, order estimation),
fixed.c (the fixed predictor errors and residuals), window.c (the
apodization windows) and bitwriter.c (the Rice block writer).

Copyright:
  Copyright (C) 2000-2009  Josh Coalson
  Copyright (C) 2011-2025  Xiph.Org Foundation
EOF
license_start "libFLAC license (COPYING.Xiph)"
cat "$LIC/flac-COPYING.Xiph"

section "10. Pillow"
cat <<'EOF'
Project:      Pillow, the friendly fork of the Python Imaging Library (PIL)
Home:         https://python-pillow.github.io/
Source:       https://github.com/python-pillow/Pillow
License text: https://raw.githubusercontent.com/python-pillow/Pillow/main/LICENSE
License:      MIT-CMU (Historical Permission Notice and Disclaimer)

Derived Smalt packages:
  image.ops (ImageOps, Resampler, ResampleWeights, BoxBlur, ImageFilters,
  ImageAdjustments, Median, Warp, ConvolutionKernel)

Derivation: the separable resampler (stretched kernels, normalized
fixed-point taps, the choice of the largest precision that cannot overflow a
32-bit accumulator, clamped output) follows src/libImaging/Resample.c. Tap
placement and quantization are Smalt's own. The extended box blur and its
Gaussian radius follow src/libImaging/BoxBlur.c, the unsharp mask
src/libImaging/UnsharpMask.c, the RGBa premultiplication
src/libImaging/Convert.c, the brightness, contrast and color enhancements,
autocontrast and colorize src/PIL/ImageEnhance.py, src/PIL/ImageOps.py and
src/libImaging/Blend.c, the built-in kernels src/PIL/ImageFilter.py, and the
affine sampling point and nearest-neighbor rule src/libImaging/Geometry.c;
the outputs match Pillow's for the same inputs.

Copyright: see the first lines of the license text below. In this copy the
copyright sign of the upstream text is written as "(c)".
EOF
license_start "Pillow license (LICENSE)"
LC_ALL=C sed 's/\xc2\xa9/(c)/g' "$LIC/pillow-LICENSE"

section "11. OpenH264"
cat <<'EOF'
Project:      OpenH264, Cisco's H.264 encoder and decoder
Source:       https://github.com/cisco/openh264
License text: https://raw.githubusercontent.com/cisco/openh264/master/LICENSE
License:      BSD 2-Clause License

Derived Smalt packages:
  video.h264.internal (CabacTables: the arithmetic coding engine's state
  transition and range tables, ITU-T H.264 Tables 9-44 and 9-45, as laid out
  in codec/common/src/common_tables.cpp)
  video.h264.encoder.internal (the structure of the motion search from
  codec/encoder/core/src/svc_motion_estimate.cpp)

Derivation: constant tables copied from the C sources, and one algorithm's
structure followed.

Copyright:
  Copyright (c) 2013, Cisco Systems
EOF
license_start "OpenH264 license (LICENSE)"
cat "$LIC/openh264-LICENSE"

section "12. H.264/AVC JM reference software"
cat <<'EOF'
Project:      JM, the ITU-T / ISO/IEC H.264/AVC reference software
Source:       https://vcgit.hhi.fraunhofer.de/jvet/JM
License text: COPYRIGHT_ITU.txt and COPYRIGHT_ISO_IEC.txt at the root of the
              repository
License:      royalty-free use and modification with the copyright notices
              below

Derived Smalt packages:
  video.h264.internal (CavlcTables: the CAVLC code tables of ITU-T H.264
  Tables 9-5 and 9-7 to 9-10, in the layout of the JM decoder's vlc.c;
  CabacTables: the 8x8 significance context maps of app/lencod/cabac.c)
  video.h264.encoder.internal (the coefficient decimation costs of
  app/lencod/block.c, the quantization tables of app/lencod/q_matrix.c, the
  rate-distortion lambdas of app/lencod/lambda.c, the UMHexagonS search
  patterns of app/lencod/me_umhex.c, and the JM default rounding offsets)

Derivation: constant tables transcribed from the reference software, and
its encoder decisions followed.
EOF
license_start "ITU copyright and warranty information (COPYRIGHT_ITU.txt)"
cat "$LIC/jm-COPYRIGHT_ITU.txt"
license_start "ISO/IEC software copyright licensing disclaimer (COPYRIGHT_ISO_IEC.txt)"
cat "$LIC/jm-COPYRIGHT_ISO_IEC.txt"
license_start "Disclaimer of warranty (disclaimer.txt)"
cat "$LIC/jm-disclaimer.txt"

section "13. HEVC HM reference software"
cat <<'EOF'
Project:      HM, the ITU-T / ISO/IEC H.265/HEVC reference software
Source:       https://vcgit.hhi.fraunhofer.de/jvet/HM
License text: COPYING at the root of the repository
License:      BSD 3-Clause License

Derived Smalt packages:
  video.h264.encoder.internal (CabacEncoder: the binary arithmetic encoder
  engine of source/Lib/TLibEncoder/TEncBinCoderCABAC.cpp)
  video.hevc.internal (CabacDecoder: the binary arithmetic decoder engine
  of source/Lib/TLibDecoder/TDecBinCoderCABAC.cpp)

Derivation: algorithm structure followed; H.264 and HEVC share the CABAC
engine. The rest of the HEVC decoder is written from the ITU-T H.265
specification; HM served as the reference decoder its output is compared
with.

Copyright:
  Copyright (c) 2010-2026, ITU/ISO/IEC
EOF
license_start "HM license (COPYING)"
cat "$LIC/hm-COPYING"

section "14. fdlibm (Freely Distributable LIBM)"
cat <<'EOF'
Project:      fdlibm 5.3, the reference implementation StrictMath follows
Source:       https://www.netlib.org/fdlibm/
License text: the notice at the top of every fdlibm source file
License:      fdlibm permissive notice (free use, copy, modification and
              distribution, provided the notice is preserved)

Derived Smalt packages:
  audio.opus.internal.celt (MathOps: the cosine of k_cos.c and k_sin.c with the
  one-step argument reduction of e_rem_pio2.c, so the CELT window matches
  StrictMath.cos without its per-call allocation); audio.voice.internal
  (FloatMath: the cosf/sinf kernels, which musl takes from FreeBSD's msun,
  itself derived from fdlibm; see section 17)

Derivation: algorithm and constants ported.

Copyright:
  Copyright (C) 1993 by Sun Microsystems, Inc.
EOF
license_start "fdlibm notice (k_cos.c)"
cat "$LIC/fdlibm-NOTICE"

section "15. WebRTC (audio processing module)"
cat <<'EOF'
Project:      WebRTC, the audio processing module (AEC3 echo canceller, mobile
              echo canceller, noise suppression, gain controller, high-pass
              filter) and its signal processing library, as packaged by
              webrtc-audio-processing 2.1
Source:       https://webrtc.googlesource.com/src
              https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing
              (tag v2.1)
License text: webrtc/LICENSE and webrtc/PATENTS of webrtc-audio-processing
              v2.1 (identical to LICENSE and PATENTS of the WebRTC tree)
License:      BSD 3-Clause License, plus Google's additional IP rights grant

Derived Smalt packages:
  audio.voice, audio.voice.internal, audio.voice.internal.aec3,
  audio.voice.internal.aecm, audio.voice.internal.agc,
  audio.voice.internal.ns (VoiceProcessor: the capture path of
  modules/audio_processing: aec3, aecm, agc/legacy with gain_control_impl,
  ns, the capture high-pass filter, utility/delay_estimator and the
  fixed-point routines of common_audio/signal_processing it uses)

Derivation: C++ code translated to Java, restructured, and in places
vectorized with the Java Vector API.

Copyright:
  Copyright (c) 2011, The WebRTC project authors. All rights reserved.

The patent grant below covers "this implementation of the WebRTC code
package" and does not include claims infringed only as a consequence of
further modification; whether it extends to this Java translation is not
established.
EOF
license_start "WebRTC license (LICENSE)"
cat "$LIC/webrtc-LICENSE"
license_start "WebRTC additional IP rights grant (PATENTS)"
cat "$LIC/webrtc-PATENTS"

section "16. Ooura FFT"
cat <<'EOF'
Project:      General Purpose FFT Package by Takuya Ooura, as included in
              WebRTC (common_audio/third_party/ooura)
Source:       http://www.kurims.kyoto-u.ac.jp/~ooura/fft.html
License text: the notice at the top of ooura_fft.cc
License:      Ooura permissive notice (use, copy, modification and
              distribution for any purpose without fee)

Derived Smalt packages:
  audio.voice.internal (OouraRealFft: the 128-point real FFT of the noise
  suppressor)
  audio.voice.internal.aec3 (Aec3Fft: the 128-point split-radix FFT of the
  echo canceller)

Derivation: C code translated to Java.

Copyright:
  Copyright Takuya OOURA, 1996-2001
EOF
license_start "Ooura FFT notice (ooura_fft.cc)"
cat "$LIC/ooura-NOTICE"

section "17. musl libc math"
cat <<'EOF'
Project:      musl libc 1.2.5, the C library of the WebAssembly build that
              serves as the voice processor's bit-exact reference
Source:       https://git.musl-libc.org/cgit/musl/tree/?h=v1.2.5
License text: COPYRIGHT at the root of the repository
License:      MIT License; powf and its tables come from Arm's optimized
              routines (MIT); the float kernels come from FreeBSD's msun,
              under the Sun notice reproduced in section 14

Derived Smalt packages:
  audio.voice.internal (FloatMath: powf with __powf_log2_data and
  __exp2f_data, tanhf, expm1f, cosf and sinf, rounded exactly as musl)

Derivation: C code and tables translated to Java.

Copyright:
  Copyright (c) 2005-2020 Rich Felker, et al.
  Copyright (c) 2017-2018, Arm Limited.
  Copyright (C) 1993 by Sun Microsystems, Inc.
EOF
license_start "musl license (COPYRIGHT)"
cat "$LIC/musl-COPYRIGHT"

section "18. dav1d"
cat <<'EOF'
Project:      dav1d, the AV1 decoder of VideoLAN
Source:       https://code.videolan.org/videolan/dav1d
              (revision 7f12cf23560430c02a83e67bb68eec74d93ce5fd)
License text: COPYING at the root of the repository
License:      BSD 2-Clause License

Derived Smalt packages:
  video.av1, video.av1.internal (Av1Decoder: OBU, sequence and frame header
  parsing, the entropy decoder and its CDF tables, block and coefficient
  decoding, reference motion vector prediction, reconstruction, the inverse
  transforms, motion compensation and warped motion, intra prediction, the
  deblocking filter, CDEF, loop restoration, super-resolution, film grain
  synthesis, and the two-pass frame threading of decode.c and thread_task.c:
  the entropy pass, the reconstruction pass and the reference row tracking of
  lowest_px); video.av1.internal.encoder (Av1Encoder: the symbol writer mirrors
  the block, mode and coefficient decoding order and contexts of decode.c and
  recon_tmpl.c, the CDF adaptation of msac.c, the motion mode and the local
  warp of a block (the sample collection and model fit of the decoder, and the
  warp_affine prediction of recon_tmpl.c), the projection and saving of the
  motion vectors of references (refmvs.c), and every frame is reconstructed
  and filtered with the decoder's ports of dav1d's kernels)

Derivation: C reference code (src/*.c and the *_tmpl.c bit depth templates)
translated to Java, with tables copied from its sources and the buffer
layouts of its tile-threaded mode; the Vector API kernels follow the
structure of its x86 assembly, but no assembly was translated.

Copyright:
  Copyright (c) 2018-2025, VideoLAN and dav1d authors

AV1 is also covered by the Alliance for Open Media Patent License 1.0
(https://aomedia.org/license/patent-license/), a royalty-free patent license
granted by the AOMedia members. It is not a condition of the license below
and is not reproduced here.
EOF
license_start "dav1d license (COPYING)"
cat "$LIC/dav1d-COPYING"

section "19. libaom"
cat <<'EOF'
Project:      libaom, the AV1 codec library of the Alliance for Open Media
Source:       https://aomedia.googlesource.com/aom
              (release v3.12.1)
License text: LICENSE at the root of the repository
License:      BSD 2-Clause License

Derived Smalt packages:
  video.av1, video.av1.internal.encoder (Av1Encoder, and the intra-only still
  encoder behind AvifEncoder and its animations)

Derivation: the real-time AV1 encoder is a port of libaom 3.12.1's one-pass
real-time path at speeds 7 to 10 (av1/encoder: nonrd_pickmode.c, nonrd_opt.c,
var_based_part.c, mcomp.c, ratectrl.c, aq_cyclicrefresh.c, speed_features.c,
rd.c, rdopt_utils.h, intra_mode_search.c, encodeframe.c, partition_search.c,
picklpf.c, pickcdef.c, av1_quantize.c, av1_fwd_txfm1d.c, av1_fwd_txfm2d.c,
encodetxb.c, bitstream.c, encodemv.c, segmentation.c and aom_dsp/entenc.c,
the range encoder; the temporal layer patterns of examples/svc_encoder_rtc.c)
with the aom_dsp C kernels it uses
(sums of absolute differences, variances, sub-pixel variances, Hadamard
transforms, integral projections, low-precision quantization) and its speed,
threshold, rate model and cost tables.

The intra-only still encoder follows libaom 3.12.1's all-intra mode: the
rate-distortion partition, intra mode, angle delta, filter intra,
chroma-from-luma, palette and transform size and type searches
(partition_search.c, intra_mode_search.c, intra_mode_search_utils.h with its
gradient-histogram mode pruning model, palette.c, tx_search.c with its
transform-domain distortion, encodetxb.c), the all-intra speed features
(speed_features.c), the screen content detection (encoder.c), the quantizer
(av1_quantize.c), the identity and Walsh-Hadamard forward transforms
(av1_fwd_txfm1d.c, av1_fwd_txfm2d.c), the loop filter, CDEF and loop
restoration searches (picklpf.c, pickcdef.c, pickrst.c, with the self-guided
filter of av1/common/restoration.c) and the bitstream syntax of key frames
and reduced still picture headers (bitstream.c, binary_codes_writer.c).

The good-quality AV1 encoder follows libaom 3.12.1's good-quality mode at
cpu-used 2 to 6: the golden frame group pyramid (gop_structure.c), the
reference buffer assignment (encode_strategy.c), the first-pass statistics,
scene cut detection and frame boosts (firstpass.c, pass2_strategy.c), the
constant-quality and variable-bitrate quantizer selection (ratectrl.c), the
temporal filter of alternate references (temporal_filter.c), the temporal
dependency model (tpl_model.c), the n-step motion search (mcomp.c), the
rate-distortion multiplier layer factors (rd.c), the transform type and size
searches of inter blocks (tx_search.c), the compound reference, compound
mode and skip mode syntax (bitstream.c), the recursive partition search over
the unsplit, rectangular and split candidates (partition_search.c), the
optimization of quantized coefficient levels by rate and distortion
(encodetxb.c, txb_rdopt.c), the downsampled sums of absolute differences of
the motion search (mcomp.c), the sub-pixel search tree of the temporal
filter (mcomp.c), the choice of the motion mode of a block by its coded cost
(rdopt.c, motion_mode_rd), the compound mode set of the mode search (rdopt.c)
and the speed features that widen the all-intra searches of key frames
(speed_features.c).

Copyright:
  Copyright (c) 2016, Alliance for Open Media. All rights reserved.

AV1 is also covered by the Alliance for Open Media Patent License 1.0
(https://aomedia.org/license/patent-license/), published in libaom's
PATENTS file. It is a royalty-free patent license granted by the AOMedia
members, not a condition of the license below, and is not reproduced here.
EOF
license_start "libaom license (LICENSE)"
cat "$LIC/aom-LICENSE"

section "20. libavif"
cat <<'EOF'
Project:      libavif, the reference library of the AV1 Image File Format
Source:       https://github.com/AOMediaCodec/libavif
              (revision e4db889c6b574bf221a380e9bddde0273c942023; the sample
              transforms at revision b1e4d339c1ff5b547664c85f1713279f4997d694)
License text: LICENSE at the root of the repository
License:      BSD 2-Clause License

Derived Smalt packages:
  internal.heif (the HEIF item parser: the container rules it enforces, the
  clean aperture arithmetic and the loop count of image sequences; the
  limited-range alpha expansion of the image canvas; the operators of the
  sample transform expressions, HeifSampleTransform)
  image.avif, image.avif.internal (AvifDecoder: the choice between the primary
  item and the image sequence, the color, alpha and grid tile selection, the
  grid validation, the limited-range alpha expansion and the unpremultiplying
  of premultiplied alpha, the choice of the sample transform item shown in
  place of the primary item; AvifEncoder: the box layout of still images, alpha
  auxiliary items, grids, Exif and XMP items and image sequences, the quality
  scale, the automatic tiling, the orientation properties and the RGB to YUV
  conversion defaults)

Derivation: the semantics of src/read.c, src/write.c, src/avif.c, src/alpha.c
and src/reformat.c followed in Java, and the operators of
src/sampletransform.c (Copyright 2024 Google LLC) ported; no other code was
translated line by line.

Copyright:
  Copyright 2019 Joe Drago. All rights reserved.
  Copyright 2024 Google LLC
EOF
license_start "libavif license (LICENSE)"
cat "$LIC/libavif-LICENSE"

section "21. giflib"
cat <<'EOF'
Project:      GIFLIB, the GIF encoding and decoding library
Source:       https://sourceforge.net/projects/giflib/ (release 5.2.2)
License text: COPYING at the root of the release
License:      MIT License

Derived Smalt packages:
  image.gif.internal (GifLzwEncoder: the LZW compressor and its string table
  of egif_lib.c and gif_hash.c)

Derivation: EGifSetupCompress, EGifCompressLine and EGifCompressOutput
(egif_lib.c) and the hash table of gif_hash.c translated to Java; the output
is byte-identical to giflib's for the same color indices.

Copyright:
  Copyright (c) 1997 Eric S. Raymond
EOF
license_start "giflib license (COPYING)"
cat "$LIC/giflib-COPYING"

section "22. NeuQuant"
cat <<'EOF'
Project:      NeuQuant Neural-Net Quantization Algorithm by Anthony Dekker
Source:       neuquant.c (1994); the notice below is reproduced from the copy
              in gif.js (https://github.com/jnordberg/gif.js, src/NeuQuant.js),
              which carries the original header
License text: the notice at the top of neuquant.c
License:      permissive (free use, modification and redistribution, provided
              the copyright notice remains intact)

Derived Smalt packages:
  internal.quantize (NeuQuant: the network initialization, training loop,
  competition and neighborhood updates)

Derivation: initnet, contest, altersingle, alterneigh, learn and unbiasnet
translated to Java with a configurable network size.

Copyright:
  Copyright (c) 1994 Anthony Dekker
EOF
license_start "NeuQuant notice (neuquant.c)"
cat "$LIC/neuquant-NOTICE"

section "23. Little CMS"
cat <<'EOF'
Project:      Little CMS (lcms2), a color management engine, version 2.17
Home:         https://www.littlecms.com/
Source:       https://github.com/mm2/Little-CMS (tag lcms2.17)
License text: https://raw.githubusercontent.com/mm2/Little-CMS/lcms2.17/LICENSE
License:      MIT License

Derived Smalt packages:
  image.color

Derivation: the ICC transform logic of Little CMS 2.17 translated to Java:
profile and tag reading and the version 2/4 L*a*b* encodings (cmsio1.c,
cmstypes.c), intent linking, absolute colorimetric scaling and black point
compensation (cmscnvrt.c), black point detection (cmssamp.c), parametric and
tabulated tone curves and their inversion (cmsgamma.c), the 16-bit
interpolation routines (cmsintrp.c), pipeline stages (cmslut.c), L*a*b*, XYZ
and chromatic adaptation arithmetic (cmspcs.c, cmswtpnt.c, cmsmtrx.c), and the
curve-joining, matrix-shaper, prelinearization and resampling optimizations
with their white fix-up (cmsopt.c).

Copyright:
  Copyright (c) 1998-2024 Marti Maria Saguer
EOF
license_start "Little CMS license (LICENSE)"
cat "$LIC/lcms2-LICENSE"

section "24. PacketVideo AAC decoder (aacdec)"
cat <<'EOF'
Project:      PacketVideo AAC decoder (aacdec), as shipped in the Android Open
              Source Project (frameworks/base/media/libstagefright/codecs/aacdec)
Source:       https://android.googlesource.com/platform/frameworks/base
              (tag android-4.0.4_r2.1)
License text: the license header of each source file, which grants the
              Apache License, Version 2.0, reproduced in full in section 6
              (see section 6)
License:      Apache License, Version 2.0

Derived Smalt packages:
  audio.aac.internal.sbr (PsDecoder, SbrChannel, SbrFrequencies,
  SbrHuffman, SbrTables)

Derivation: the Huffman trees of the spectral band replication envelopes and
noise floors (sbr_code_book_envlevel.cpp) and of the parametric stereo
parameters (ps_read_data.cpp) are taken from that decoder, and the QMF window
and the noise table were checked against qmf_filterbank_coeff.cpp and
sbr_envelope_calc_tbl.cpp. The limiter band table
(sbr_create_limiter_bands.cpp), the indexing of the noise and sinusoid
generators (calc_sbr_envelope.cpp) and the baseline parametric stereo decoder
(ps_*.cpp: hybrid analysis filterbank, all-pass decorrelator, transient
detection, stereo mixing) follow that decoder's floating-point paths. The
Java code is a new implementation of ISO/IEC 14496-3, written against the
standard, and differs from the original; it is not a copy of the original
files.

Copyright:
  Copyright (C) 1998-2010 PacketVideo
EOF

section "25. LibTIFF"
cat <<'EOF'
Project:      LibTIFF, the TIFF library and utilities, version 4.7.1
Home:         https://libtiff.gitlab.io/libtiff/
Source:       https://download.osgeo.org/libtiff/tiff-4.7.1.tar.gz
License text: LICENSE.md at the root of the release
License:      the LibTIFF license (permissive, BSD-like), with the original
              Berkeley notice of the LZW code

Derived Smalt packages:
  image.tiff (TiffDecoder: the interpretation of the TIFF tags and the
  tolerance of damaged files)
  image.tiff.internal (YCbCrTables: TIFFYCbCrToRGBInit and TIFFYCbCrtoRGB of
  tif_color.c; LzwDecoder and LzwEncoder: the code-width schedule, table reset
  and old-style bit order of tif_lzw.c; Predictor: the horizontal and
  floating-point predictors of tif_predict.c; ThunderScan: ThunderDecode of
  tif_thunder.c; CcittFaxDecoder: the end-of-line and fill-bit handling of
  tif_fax3.c; CcittFaxEncoder: Fax3Encode1DRow, Fax3Encode2DRow, putspan,
  Fax3PutEOL and the row alignment of tif_fax3.c; TiffPageDecoder and SampleConverter: the extra-sample, palette,
  CMYK and YCbCr rules of tif_getimage.c; TiffJpeg: the old-style JPEG stream
  reconstruction of tif_ojpeg.c; LabTables: TIFFCIELabToRGBInit,
  TIFFCIELab16ToXYZ and TIFFXYZToRGB of tif_color.c with the display_sRGB
  constants of tif_getimage.c; SgiLog: the LogL and LogLuv decoders and
  conversions of tif_luv.c and the chromaticity table of uvcode.h;
  PixarLog: PixarLogMakeTables and the horizontalAccumulate functions of
  tif_pixarlog.c; NeXT: NeXTDecode of tif_next.c; TiffLerc: the mask, alpha
  and byte-order conventions of tif_lerc.c)

Derivation: the YCbCr tables, the CIE L*a*b* conversion, the SGILog decoders
and conversions, the uvcode.h table, the PixarLog tables, NeXTDecode and
ThunderDecode translated to Java; the other listed functions followed for their
algorithms and conventions, with the Java code structured differently.

Copyright:
  Copyright (c) 1988-1997 Sam Leffler
  Copyright (c) 1991-1997 Silicon Graphics, Inc.
  Copyright (c) 1985, 1986 The Regents of the University of California
  (tif_lzw.c)
  Copyright (c) 1997 Greg Ward Larson (tif_luv.c, uvcode.h)
  Copyright (c) 1996-1997 Sam Leffler, Copyright (c) 1996 Pixar
  (tif_pixarlog.c)
  Copyright (c) 2018 Even Rouault (tif_lerc.c)
EOF
license_start "LibTIFF license (LICENSE.md)"
cat "$LIC/libtiff-LICENSE.md"

section "26. Apple Lossless Audio Codec (ALAC)"
cat <<'EOF'
Project:      Apple Lossless Audio Codec (ALAC), Apple's open-source encoder and
              decoder
Source:       https://github.com/macosforge/alac
License text: https://raw.githubusercontent.com/macosforge/alac/master/LICENSE
License:      Apache License, Version 2.0

Derived Smalt packages:
  audio.alac (AlacDecoder, AlacResidual, AlacPredictor, AlacBitReader)

Derivation: the decoder follows codec/ALACDecoder.cpp (element parsing,
shifted-off bytes, partial frames, uncompressed elements), codec/ag_dec.c
(the adaptive Golomb residual decoder with its zero runs), codec/dp_dec.c
(the adaptive predictor unpc_block) and codec/matrix_dec.c (the matrixed
stereo unmix), and the channel orders of ALACMagicCookieDescription.txt.

Copyright:
  Copyright (c) 2011 Apple Inc. All rights reserved.
EOF
license_start "ALAC license (LICENSE)"
cat "$LIC/alac-LICENSE"

section "27. libebur128"
cat <<'EOF'
Project:      libebur128, a library implementing the EBU R 128 loudness
              normalization standard, version 1.2.6
Home:         https://github.com/jiixyj/libebur128
Source:       https://github.com/jiixyj/libebur128 (ebur128/ebur128.c)
License text: COPYING at the root of the repository
License:      MIT License

Derived Smalt packages:
  audio.loudness (LoudnessMeter: the K-weighting coefficients for any
  sampling rate of ebur128_init_filter, the 100 ms steps of samples_in_100ms,
  the gating of ebur128_gated_loudness and the loudness range of
  ebur128_loudness_range_multiple; TruePeakMeter: the polyphase interpolator
  of interp_create and interp_process)

Derivation: the listed functions translated to Java and restructured: the
gating histograms also sum the block energies, the loudness range takes a
short-term block every 100 ms, and the interpolator runs in single
precision behind a Vector API kernel.

Copyright:
  Copyright (c) 2011 Jan Kokemueller
EOF
license_start "MIT License (COPYING)"
cat "$LIC/libebur128-COPYING"

section "28. smartcrop.js"
cat <<'EOF'
Project:      smartcrop.js, content-aware image cropping, version 2.0.5
Source:       https://github.com/jwagner/smartcrop.js
License text: https://raw.githubusercontent.com/jwagner/smartcrop.js/master/LICENSE
License:      MIT License

Derived Smalt packages:
  image.ops (SmartCrop: the attention analysis)

Derivation: the edge, skin and saturation detectors, the score
down-sampling, the crop candidates, the importance function with its
rule-of-thirds and edge terms, and the default weights of smartcrop.js
translated to Java; for the same pixels the chosen crop is identical.

Copyright:
  Copyright (C) 2018 Jonas Wagner
EOF
license_start "smartcrop.js license (LICENSE)"
cat "$LIC/smartcrop-LICENSE"

section "29. opencore-amr (AMR-NB codec and AMR-WB decoder)"
cat <<'EOF'
Project:      opencore-amr 0.1.6, the PacketVideo OpenCORE AMR-NB and AMR-WB
              speech codecs of the Android Open Source Project, packaged
              standalone
Home:         https://sourceforge.net/projects/opencore-amr/
Source:       https://sourceforge.net/projects/opencore-amr/files/opencore-amr/opencore-amr-0.1.6.tar.gz
              (SHA-256 483eb4061088e2b34b358e47540b5d495a96cd468e361050fae615b1809dc4a1)
License text: LICENSE at the root of the release
License:      Apache License, Version 2.0

Derived Smalt packages:
  audio.amr.internal.nb (the AMR-NB decoder and encoder: the basic
  fixed-point operators, LPC, LSP and LSF processing, quantizer tables, the
  decoder of sp_dec.cpp and dec_amr.cpp with its comfort noise, concealment
  and post-processing, the encoder of sp_enc.cpp and cod_amr.cpp with its
  pitch, codebook and gain searches, VAD option 1 and DTX, and the bit
  ordering of the RFC 4867 storage format)
  audio.amr.internal.wb (the AMR-WB decoder of pvamrwbdecoder.cpp and the
  files it calls, with its comfort noise, concealment, high-band synthesis
  and bit ordering)
  audio.amr.internal.wbcommon (the AMR-WB tables and pitch routines shared
  by the decoder and the encoder)
  audio.amr.internal (the logarithm, power and inverse square root tables
  shared by the AMR-NB and AMR-WB codecs)

Derivation: the C sources of opencore/codecs_v2/audio/gsm_amr (amr_nb/common,
amr_nb/dec, amr_nb/enc, amr_wb/dec) and the frame handling of amrnb/wrapper.cpp
and amrwb/wrapper.cpp translated to Java, with the tables copied, so that the
output stays bit-exact with the original. The homing procedure of TS 26.073,
absent from the AMR-NB code, was added. In the AMR-WB decoder, the arithmetic
of the basic operators, filters, excitation, concealment and comfort noise
routines was then aligned with the 3GPP TS 26.173 fixed-point reference (the
normative specification of AMR-WB, release 18) wherever opencore departs from
it, so that the decoder passes the TS 26.174 test sequences, and the muting of
the AMR-NB comfort noise was aligned with the TS 26.073 reference, which does
not clamp the muted energy; no 3GPP source text was copied. The source files
carry this notice about their 3GPP origin:

  Portions of this file are derived from the following 3GPP standard:
      3GPP TS 26.073 (AMR-NB) / 3GPP TS 26.173 (AMR-WB)
      ANSI-C code for the Adaptive Multi-Rate (AMR) speech codec
      Available from http://www.3gpp.org
  (C) 2004, 3GPP Organizational Partners (ARIB, ATIS, CCSA, ETSI, TTA, TTC)
  Permission to distribute, modify and use this file under the standard
  license terms listed above has been obtained from the copyright holder.

AMR-NB and AMR-WB are covered by patents; this license covers the code only
(AMR-WB patents are licensed through the VoiceAge pool).

Copyright:
  Copyright (C) 1998-2010 PacketVideo
  Copyright (C) 2009-2010 Martin Storsjo (wrapper code)
EOF
license_start "Apache License, Version 2.0 (opencore-amr LICENSE)"
cat "$LIC/opencore-amr-LICENSE"

section "30. VisualOn AMR-WB encoder (vo-amrwbenc)"
cat <<'EOF'
Project:      vo-amrwbenc 0.1.3, the VisualOn AMR-WB encoder of the Android
              Open Source Project, packaged standalone with opencore-amr
Home:         https://sourceforge.net/projects/opencore-amr/
Source:       https://sourceforge.net/projects/opencore-amr/files/vo-amrwbenc/vo-amrwbenc-0.1.3.tar.gz
              (SHA-256 5652b391e0f0e296417b841b02987d3fd33e6c0af342c69542cbb016a71d9d4e)
License text: COPYING at the root of the release
NOTICE:       NOTICE at the root of the release
License:      Apache License, Version 2.0

Derived Smalt packages:
  audio.amr.internal.wbenc (the AMR-WB encoder of voAMRWBEnc.c and the files
  it calls: pre-processing and decimation, LPC and ISF quantization,
  open- and closed-loop pitch, the algebraic codebook search, gain
  quantization, VAD and DTX, homing, and the RFC 4867 bit ordering)
  audio.amr.internal.wbcommon (the AMR-WB tables and pitch routines shared
  by the decoder and the encoder)
  audio.amr.internal (the logarithm, power and inverse square root tables
  shared by the AMR-NB and AMR-WB codecs)

Derivation: the C sources of amrwbenc/src (the portable C paths, not the ARM
assembly) translated to Java, with the tables copied. The arithmetic of the
basic operators, filters, LP analysis, pitch searches, algebraic codebook
search, gain quantization, VAD and high-band synthesis was then aligned with
the 3GPP TS 26.173 fixed-point reference (the normative specification of
AMR-WB, release 18) wherever vo-amrwbenc departs from it, including the
release's 6.60/8.85 kbit/s pitch gain clipping and codebook impulse response
scaling, so that the encoder passes the TS 26.174 test sequences; no 3GPP
source text was copied.

Copyright:
  Copyright 2003-2010, VisualOn, Inc.

Attribution notices from the upstream NOTICE file (Apache License 2.0,
section 4(d)):
EOF
printf '\n'
sed -n '1,9p' "$LIC/vo-amrwbenc-NOTICE"
cat <<'EOF'

(The upstream NOTICE file continues with a copy of the Apache License 2.0,
reproduced below from COPYING.)
EOF
license_start "Apache License, Version 2.0 (vo-amrwbenc COPYING)"
cat "$LIC/vo-amrwbenc-COPYING"

section "31. OpenJPEG"
cat <<'EOF'
Project:      OpenJPEG, the reference JPEG 2000 codec, version 2.5.3
Home:         https://www.openjpeg.org/
Source:       https://github.com/uclouvain/openjpeg (src/lib/openjp2,
              src/bin/common/color.c)
License text: https://raw.githubusercontent.com/uclouvain/openjpeg/v2.5.3/LICENSE
License:      BSD 2-Clause License

Derived Smalt packages:
  image.jp2
  image.jp2.internal

Derivation: the JPEG 2000 decoder and encoder are ports of libopenjp2: the
codestream reader and writer of j2k.c (marker segments, tile parts, packed
packet headers, coding-parameter precedence, rate budgets), the JP2 boxes of
jp2.c (including palette, component mapping and channel definitions), the
tile geometry and coding pipeline of tcd.c, the tier-1 coder of t1.c and its
MQ coder (mqc.c, including the lookup tables), the tier-2 packet coder of t2.c,
the packet iterators of pi.c, the tag trees of tgt.c, the bit I/O of bio.c,
the wavelet transforms of dwt.c, the component transforms of mct.c, the rate
allocation of tcd.c, and the sYCC, e-sYCC and CMYK conversions of the
opj_decompress tool (src/bin/common/color.c), including tables and constants
taken from the C sources.

Copyright (as stated in the headers of the OpenJPEG source files the Java
code derives from):
  Copyright (c) 2002-2014, Universite catholique de Louvain (UCL), Belgium
  Copyright (c) 2002-2014, Professor Benoit Macq
  Copyright (c) 2001-2003, David Janssens
  Copyright (c) 2002-2003, Yannick Verschueren
  Copyright (c) 2003-2007, Francois-Olivier Devaux
  Copyright (c) 2003-2014, Antonin Descampe
  Copyright (c) 2005, Herve Drolon, FreeImage Team
  Copyright (c) 2006-2007, Parvatha Elangovan
  Copyright (c) 2007, Callum Lerwick <seg@haxxed.com>
  Copyright (c) 2007, Jonathan Ballard <dzonatas@dzonux.net>
  Copyright (c) 2008, Jerome Fimes, Communications & Systemes
  Copyright (c) 2008, 2011-2012, Centre National d'Etudes Spatiales (CNES),
  France
  Copyright (c) 2010-2011, Kaori Hagihara
  Copyright (c) 2012, CS Systemes d'Information, France
  Copyright (c) 2012, Carl Hetherington
  Copyright (c) 2017, IntoPIX SA <support@intopix.com>
EOF
license_start "OpenJPEG license (LICENSE)"
cat "$LIC/openjpeg-LICENSE"

section "32. jxl-oxide"
cat <<'EOF'
Project:      jxl-oxide, a JPEG XL decoder written in Rust, version 0.12.6
              (commit c0cc4c7ea57c1207f38ff2970d94757470613be4)
Home:         https://github.com/tirr-c/jxl-oxide
Source:       https://github.com/tirr-c/jxl-oxide
License text: LICENSE-MIT and LICENSE-APACHE at the root of the repository
License:      MIT OR Apache-2.0 (dual licensed; Smalt uses it under the MIT
              License)

Derived Smalt packages:
  image.jxl
  image.jxl.internal
  image.jxl.internal.bitstream
  image.jxl.internal.color
  image.jxl.internal.entropy
  image.jxl.internal.frame
  image.jxl.internal.header
  image.jxl.internal.jpeg
  image.jxl.internal.modular
  image.jxl.internal.render
  image.jxl.internal.vardct

Derivation: the JPEG XL decoder is a port of jxl-oxide's crates translated
from Rust to Java: jxl-bitstream (bit reader, container), jxl-coding (ANS,
prefix codes, hybrid integers, LZ77, clustering), jxl-image (image and
color headers), jxl-color (ICC stream, color encodings, XYB and transfer
functions), jxl-modular (meta-adaptive trees, predictors including the
weighted predictor, RCT, palette and squeeze), jxl-vardct (dequantization
matrices, coefficient orders, transforms), jxl-frame (frame headers, TOC,
LF and pass groups, patches, splines, noise), jxl-render (restoration
filters, upsampling, blending, rendering pipeline) and jxl-jbr (JPEG
bitstream reconstruction).

Copyright:
  Copyright (c) Wonwoo Choi and the jxl-oxide contributors
EOF
license_start "MIT License (jxl-oxide LICENSE-MIT)"
cat "$LIC/jxl-oxide-LICENSE-MIT"

section "33. libjxl"
cat <<'EOF'
Project:      libjxl, the JPEG XL reference implementation
              (commit 5f92f524f1407de323c409c360ab38cae1d97b27)
Home:         https://jpeg.org/jpegxl/
Source:       https://github.com/libjxl/libjxl
License text: LICENSE and PATENTS at the root of the repository
License:      BSD 3-Clause, with the Additional IP Rights Grant (Patents)
              reproduced below

Derived Smalt packages:
  image.jxl.internal.color
  image.jxl.internal.header
  image.jxl.internal.jpeg
  image.jxl.internal.modular
  image.jxl.internal.render

Derivation: where jxl-oxide (section 32) and libjxl differ, the decoder
follows libjxl, the reference: the palette transform semantics
(GetPaletteValue in lib/jxl/modular/transform/palette), the treatment of
negative values by the transfer functions, the rational approximation of
the sRGB curve (lib/jxl/cms) and the fast power function used by the BT.709,
DCI and gamma curves (lib/jxl/base/fast_math-inl.h), the ICC profiles
synthesized for enumerated color encodings, the rendering order of the
frame pipeline (lib/jxl/render_pipeline), and the MCU grid of a JPEG scan
of one subsampled component (lib/jxl/jpeg/dec_jpeg_data_writer.cc).

Copyright:
  Copyright (c) the JPEG XL Project Authors.
EOF
license_start "libjxl license (LICENSE)"
cat "$LIC/libjxl-LICENSE"
license_start "libjxl Additional IP Rights Grant (PATENTS)"
cat "$LIC/libjxl-PATENTS"

section "34. Brotli"
cat <<'EOF'
Project:      Brotli, the generic-purpose lossless compression library
              (commit 42a2ed4355bc6287da6bb6319f090b499cba4550)
Home:         https://github.com/google/brotli
Source:       https://github.com/google/brotli
License text: LICENSE at the root of the repository
License:      MIT License

Derived Smalt packages:
  image.jxl.internal.brotli

Derivation: the Brotli decoder (RFC 7932) used for the jbrd and brob boxes
of JPEG XL containers follows the reference Java decoder
(java/org/brotli/dec): its Huffman table layout and builder, its command,
context and transform tables. The static dictionary resource is a verbatim
copy of c/common/dictionary.bin.

Copyright:
  Copyright (c) 2009, 2010, 2013-2016 by the Brotli Authors.
EOF
license_start "Brotli license (LICENSE)"
cat "$LIC/brotli-LICENSE"

section "35. colour-demosaicing"
cat <<'EOF'
Project:      Colour - Demosaicing, Colour Developers
Home:         https://www.colour-science.org/
Source:       https://github.com/colour-science/colour-demosaicing
              (colour_demosaicing/bayer/demosaicing/menon2007.py)
License text: https://raw.githubusercontent.com/colour-science/colour-demosaicing/develop/LICENSE
License:      BSD 3-Clause License

Derived Smalt packages:
  image.raw.internal (MenonDemosaic)

Derivation: the Menon (2007) demosaicing (directional filtering with a
posteriori decision, DDFAPD) and its refinement step follow
demosaicing_CFA_Bayer_Menon2007 and refining_step_Menon2007: the filters, the
decision kernel and the order of the interpolation steps, translated from
NumPy array operations to tiled single-precision Java loops. The algorithm is
that of D. Menon, S. Andriani and G. Calvagno, "Demosaicing With Directional
Filtering and a posteriori Decision", IEEE Transactions on Image Processing
16(1), 2007.

Copyright:
  Copyright 2015 Colour Developers
EOF
license_start "colour-demosaicing license (LICENSE)"
cat "$LIC/colour-demosaicing-LICENSE"

section "36. arw (Go package by Sjon Kosse)"
cat <<'EOF'
Project:      arw, a Go reader of Sony ARW files
Source:       https://github.com/Soreil/arw (arw.go, readraw.go)
License text: https://raw.githubusercontent.com/Soreil/arw/master/LICENSE
License:      MIT License

Derived Smalt packages:
  image.raw.internal (ArwParser)

Derivation: the bit layout of a cRAW block (an 11-bit maximum and minimum,
their 4-bit positions and fourteen 7-bit deltas in a little-endian 128-bit
field) and the interleaving of two blocks per 32-photosite group follow that
package's readCrawBlock and readCRAW. The delta scaling and the tone curve are
implemented from Henry Dietz's description of the format.

Copyright:
  Copyright (c) 2018 Sjon Kosse
EOF
license_start "arw license (LICENSE)"
cat "$LIC/arw-go-LICENSE"

section "37. Adobe Digital Negative (DNG) specification"
cat <<'EOF'
Project:      Digital Negative (DNG) Specification, version 1.7.1.0, Adobe
Home:         https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/digital-negative.html
License:      Adobe DNG patent license (royalty-free, for readers and writers
              of files that comply with the DNG Specification)

Derived Smalt packages:
  image.raw
  image.raw.internal

Derivation: the DNG reader, its color model (camera matrices, illuminant
interpolation, camera profiles) and its opcode lists implement the DNG
Specification. No code of the Adobe DNG SDK is used.

Notice required by the Adobe DNG patent license:
  This product includes DNG technology under license by Adobe.
EOF

section "38. rawtoaces-data (camera spectral sensitivities)"
cat <<'EOF'
Project:      rawtoaces-data, the data repository of the RAW to ACES Utility,
              Academy Software Foundation, commit
              e9b8503cf8a0641f40e5345d9757bde60c15f423
Source:       https://github.com/AcademySoftwareFoundation/rawtoaces-data
              (data/camera, data/training, data/cmf)
License text: https://raw.githubusercontent.com/AcademySoftwareFoundation/rawtoaces-data/main/LICENSE
License:      Apache License, Version 2.0, reproduced in full in section 6
              (see section 6); every camera and training file declares
              Apache-2.0. The CIE 1931 color matching functions it carries are
              CC BY 4.0: CIE 2019, Colour-matching functions of CIE 1931
              standard colorimetric observer, International Commission on
              Illumination (CIE), Vienna, DOI 10.25039/CIE.DS.xvudnb9b.

Derived Smalt packages:
  image.raw.internal (CameraMatrices)

Derivation: the per-model XYZ to camera matrices for CIE illuminants A and D65
are computed from the camera spectral sensitivities, the 190 training
reflectances and the CIE 1931 color matching functions of rawtoaces-data,
with the CIE daylight components S0, S1 and S2 as the rawtoaces project
(Apache-2.0, https://github.com/AcademySoftwareFoundation/rawtoaces,
src/rawtoaces_core/define.h) tabulates them: for each illuminant, a
white-preserving matrix fitted to the training patches in CIELAB, inverted.
Only the resulting matrices ship; the fitting code is a new implementation in
the test sources.

Copyright:
  Copyright Contributors to the rawtoaces Project
EOF
license_start "rawtoaces-data license (LICENSE)"
cat "$LIC/rawtoaces-data-LICENSE"

section "39. libdeflate"
cat <<'EOF'
Project:      libdeflate, a library for fast, whole-buffer DEFLATE-based compression
              and decompression
Home:         https://github.com/ebiggers/libdeflate
Source:       https://github.com/ebiggers/libdeflate
License text: https://raw.githubusercontent.com/ebiggers/libdeflate/master/COPYING
License:      MIT

Derived Smalt packages:
  image.internal.zlib

Derivation: the DEFLATE decompressor follows the design of libdeflate's
decompressor: a 64-bit bit buffer refilled without a branch, decode-table
entries that fuse a literal, or a length or distance base and its extra-bit
count, with a primary lookup and second-level tables, and matches copied a
word at a time. The DEFLATE compressor writes its blocks the way libdeflate
does: buffered symbols with frequency counts, length-limited Huffman codes,
a block cost comparison of stored, fixed and dynamic, a 64-bit bit
accumulator stored once per symbol, and its block split heuristic (the
distribution of literal and match classes compared every 512 symbols).

Copyright:
  Copyright 2016 Eric Biggers
  Copyright 2024 Google LLC
EOF
license_start "libdeflate license (COPYING)"
cat "$LIC/libdeflate-COPYING"

section "40. X3F tools"
cat <<'EOF'
Project:      X3F tools, a library for Sigma X3F raw files (commit e8b48ca)
Source:       https://github.com/Kalpanika/x3f (src/x3f_io.c, x3f_meta.c,
              x3f_image.c, x3f_process.c)
License text: https://github.com/Kalpanika/x3f/blob/master/doc/copyright.txt
License:      BSD 3-Clause License

Derived Smalt packages:
  image.raw.internal (X3fCamf, X3fDecoder, X3fParser)

Derivation: the decoding of the camera metadata block (the type 2 XOR stream
and the type 4 coding, the CMbM, CMbT and CMbP entries), the TRUE and the
older Huffman codings of the raw layers, the mapping of the KeepImageArea
rectangles, the dark shield and white level entries, and the color conversion
from the white balance gain, color correction, illuminant and gain factor
entries follow that library's x3f_io.c, x3f_meta.c, x3f_image.c and
x3f_process.c.

Copyright:
  Copyright 2015 Roland and Erik Karlsson
EOF
license_start "X3F tools license (doc/copyright.txt)"
cat "$LIC/x3f-tools-copyright.txt"

section "41. opusfile"
cat <<'EOF'
Project:      opusfile, the Xiph.Org library for decoding Ogg Opus files,
              version 0.12
Home:         https://opus-codec.org/
Source:       https://gitlab.xiph.org/xiph/opusfile
License text: https://gitlab.xiph.org/xiph/opusfile/-/raw/v0.12/COPYING
License:      BSD 3-Clause

Derived Smalt packages:
  audio.opus (OpusDownmix)

Derivation: the stereo downmix matrices of three to eight channels in the
Vorbis order (OP_STEREO_DOWNMIX in src/opusfile.c) are copied.

Copyright:
  Copyright (c) 1994-2013 Xiph.Org Foundation and contributors
EOF
license_start "opusfile license (COPYING)"
cat "$LIC/opusfile-COPYING"

section "42. libxaac"
cat <<'EOF'
Project:      libxaac, the Android extended HE-AAC decoder and encoder, at
              commit 2fbadd57bca46693b7a077bcf1513131106d960c
Source:       https://github.com/ittiam-systems/libxaac
              https://android.googlesource.com/platform/external/libxaac
License text: https://raw.githubusercontent.com/ittiam-systems/libxaac/2fbadd57bca46693b7a077bcf1513131106d960c/LICENSE
NOTICE:       https://raw.githubusercontent.com/ittiam-systems/libxaac/2fbadd57bca46693b7a077bcf1513131106d960c/NOTICE
License:      Apache License, Version 2.0

Derived Smalt packages:
  audio.aac.internal (CodewordReordering, ReversibleScalefactors,
  LowDelayWindows, SamplingTables)
  audio.aac.internal.sbr (LowDelayQmfWindow, SbrFrameData)

Derivation: Huffman codeword reordering follows the codebook priorities,
section sorting, segmentation and set-wise decoding of
decoder/ixheaacd_huff_code_reorder.c; the RVLC and escape codeword tables
and the decoding order of RVLC scalefactors are taken from
decoder/ixheaacd_rev_vlc.c. The 512-sample AAC-ELD synthesis window is
taken from low_delay_window_eld of encoder/ixheaace_rom.c, the CLD QMF
prototype of low delay SBR from cld_fb_64_640 of encoder/ixheaace_sbr_rom.c,
the scalefactor band offsets and TNS limits of 512- and 480-sample frames
from decoder/ixheaacd_aac_rom.c, and the LD_TRAN envelope tables from
decoder/ixheaacd_env_extr.c. The Java code is a new implementation; it is
not a copy of the original files.

Copyright:
  Copyright (C) 2018, 2023 The Android Open Source Project
  Ittiam Systems

Attribution notices from the upstream NOTICE file (Apache License 2.0,
section 4(d)):
EOF
printf '\n'
cat "$LIC/libxaac-NOTICE"
license_start "Apache License, Version 2.0 (libxaac LICENSE)"
cat "$LIC/libxaac-LICENSE"

section "43. LERC (Limited Error Raster Compression)"
cat <<'EOF'
Project:      LERC, Esri's Limited Error Raster Compression library, version 4.2.0
Source:       https://github.com/Esri/lerc
License text: https://raw.githubusercontent.com/Esri/lerc/v4.2.0/LICENSE
NOTICE:       https://raw.githubusercontent.com/Esri/lerc/v4.2.0/NOTICE
License:      Apache License, Version 2.0, with the patent grant of its
              clause 3 (see the NOTICE below)

Derived Smalt packages:
  image.internal.lerc (LercDecoder, BitStuffer, LercHuffman, LosslessFloat,
  Lerc1)

Derivation: the Lerc2 decoder of src/LercLib (Lerc.cpp, Lerc2.cpp, Lerc2.h,
BitStuffer2.cpp, Huffman.cpp, BitMask.cpp, RLE.cpp), the lossless
floating-point decoder of the fpl_ files (fpl_Lerc2Ext.cpp,
fpl_EsriHuffman.cpp, fpl_Compression.cpp, fpl_Predictor.cpp,
fpl_UnitTypes.cpp) and the Lerc1 decoder of src/LercLib/Lerc1Decode
(CntZImage.cpp, BitStuffer.cpp) translated to Java.

Copyright:
  Copyright 2015-2026 Esri
  Original coding 2021 Yuriy Yakimenko (the fpl_ files)

Attribution notices from the upstream NOTICE file (Apache License 2.0,
section 4(d)):
EOF
printf '\n'
cat "$LIC/lerc-NOTICE"
license_start "Apache License, Version 2.0 (LERC LICENSE)"
cat "$LIC/lerc-LICENSE"
} > "$OUT"

echo "wrote $OUT"

<div align="center">

# smalt-video

**VP9, AV1 and AVIF, and the editing and archival video codecs**

![Artifact](https://img.shields.io/badge/artifact-smalt--video-2f6fe6?style=flat-square&labelColor=0b2a6f)
![Module](https://img.shields.io/badge/module-com.github.auties00.smalt.video-2f6fe6?style=flat-square&labelColor=0b2a6f)

[Smalt](../README.md) | [core](../core/README.md) | [image](../image/README.md) | [audio](../audio/README.md) | **video** | [mpeg](../mpeg/README.md) | [capture](../capture/README.md) | [raw](../raw/README.md) | [desktop](../desktop/README.md) | [aot](../aot/README.md) | [bom](../bom/README.md)

</div>

`smalt-video` holds the royalty-free delivery codecs and the codecs of editing and archiving. It depends on
[`smalt-core`](../core/README.md) only.

**Contents:** [What it covers](#what-it-covers) | [Install](#install) | [Guide](#guide) |
[Format coverage](#format-coverage) | [Known limits](#known-limits) |
[Licensing of ProRes and DNxHD](#licensing-of-prores-and-dnxhd)

## What it covers

- **Delivery:** VP9 and AV1, decoded and encoded, and **AVIF** images and image sequences.
- **Editing:** Apple ProRes, Avid DNxHD and DNxHR, DV and QuickTime Animation, decoded and encoded; GoPro
  CineForm, decoded.
- **Archiving:** FFV1 and Theora, decoded and encoded.

> [!IMPORTANT]
> ProRes and DNxHD/DNxHR are written from public SMPTE documents, but Apple and Avid license implementations
> of them. Read the [licensing note](#licensing-of-prores-and-dnxhd) before you ship them.

> [!TIP]
> VP8 is in [`smalt-image`](../image/README.md) (lossy WebP is VP8). H.264, HEVC, VVC, MPEG-2 and the other
> codecs of patent pools are in [`smalt-mpeg`](../mpeg/README.md).

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-video</artifactId>
</dependency>
```

The version comes from the [BOM](../bom/README.md); Gradle coordinates and JVM flags are in the
[root README](../README.md#install).

## Guide

Decoding frames, writing video files and transcoding, whatever the codec, are in the
[core guide](../core/README.md#video); these examples use the codecs of this artifact directly.

### Write AVIF

```java
// avifenc -q 60 -s 6 photo.png photo.avif
var picture = Images.decode(MediaSource.of(Path.of("photo.png"))).picture();
byte[] avif = AvifEncoder.encode(picture, AvifEncoderOptions.lossy(60));
byte[] hdr = AvifEncoder.encode(deepPicture, AvifEncoderOptions.lossy(70).toBuilder()
        .chromaFormat(ChromaFormat.YUV444).bitDepth(10).speed(4).build());
byte[] exact = AvifEncoder.encode(picture, AvifEncoderOptions.lossless());
```

The quality scale and the speed are libavif's (quality 0 to 100, speed 0 to 10, default 60 and 6); transparency
becomes a lossless alpha item, and pictures larger than 8192 samples become an image grid. `Images.encode(picture,
ImageFormat.AVIF, ...)`, `Images.openAnimationWriter(ImageFormat.AVIF, ...)` and ImageIO reach the same encoder.

### Encode ProRes for editing

```java
var options = ProResEncoderOptions.of(ProResProfile.HQ).toBuilder().frameRate(new Rational(25, 1)).build();
var encoder = new ProResEncoder(1920, 1080, options);
try (var muxer = new Mp4Muxer(MediaSink.of(Path.of("edit.mov")), Mp4MuxerOptions.quickTime())) {
    muxer.addTrack(VideoTrack.builder().id(1).codec(Codec.PRORES).timeBase(encoder.timeBase())
            .codecConfig(encoder.codecConfig().orElseThrow()) // "apch": the QuickTime sample entry
            .width(1920).height(1080).build());
    for (var frame : frames) { // pictures of any representation: 10-bit 4:2:2 is coded as is
        encoder.encode(frame, packet -> muxer.writePacket(packet.withTrackId(1)));
    }
    muxer.finish();
}
```

`DnxhdEncoder` works the same way with `DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ)`. To convert a file in
one call, transcode to `OutputContainer.MOV` with `VideoTranscodeOptions.encode(Codec.PRORES)` (422 HQ) or
`Codec.DNXHD` (DNxHR HQ). DNxHD and DNxHR follow SMPTE ST 2019-1 where ffmpeg does not, which matters for
4:4:4: DNxHR 444 RGB and Y'CbCr files are signaled as the standard says and ffmpeg reads them the other way
round (see [the DNxHD notes](#format-coverage)). RGB input is coded as BT.709 Y'CbCr macroblocks, the form
other encoders' files use and ffmpeg decodes with the right colors; on the options' builder,
`alternateColorSpace(false)` codes the RGB samples themselves, `alpha(true)` codes the picture's alpha in band
(`losslessAlpha(true)` for DNxHR 444, `premultipliedAlpha(true)` for premultiplied video) and
`chromaFormat(ChromaFormat.YUV420)` codes 4:2:0 DNxHR; ffmpeg decodes neither. With
`alphaStorage(AlphaStorage.AVID_UNCOMPRESSED)` or `AVID_CODED` the alpha follows each frame as Avid's
QuickTime encoders store it, and `DnxhdDecoder` reads it back.

## Format coverage

Open a codec to see what is decoded, what is encoded, the package that implements it (relative to
`com.github.auties00.smalt`), where its code comes from and what to know about patents. In *Source*, "spec"
means the code was written from the specification alone; a named project means code was ported or derived from it
(see [Acknowledgments](../README.md#acknowledgments)).

### Images

<details>
<summary><b>AVIF</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** any HEIF file whose image is AV1-coded, whatever its brands (files with only `mif1`, `miaf` or `msf1` included; `Images.detect` reads the items to tell); primary item (single AV1 item or `grid`; `iden`; `iovl` composed to RGBA, at 16 bits when `highBitDepth` keeps a deeper input; grids of derived inputs; nested up to 8 deep), sample transforms of AVIF 1.2 (`sato`, every operator and intermediate width, up to 16 bits, as the primary item or as the alternative an `altr` group prefers to it, falling back to the primary item when unsupported), alpha (item, grid or per tile; premultiplied; of another size than the color, resampled with the triangle filter, or another depth, rescaled), layered images (`a1op`, `lsel`), clean aperture (applied), rotation and mirroring (reported as the orientation), `nclx`/ICC color, `clli`/`mdcv`, EXIF and XMP, 8/10/12-bit (8-bit by default, 8-bit RGBA for YCgCo-Re/Ro sources deeper than 8 bits, `highBitDepth` keeps the depth), thumbnails (`thmb`) under a reduced-size hint, image sequences (`avis`, with alpha, each alpha sample paired with the color sample of the same index as libavif pairs them, whatever the timing of the two tracks) as animations that seek through their sync samples, 16-bit RGBA frames with `highBitDepth`; gain maps (`tmap` derived image items with ISO 21496-1 metadata and the alternate color, as libavif reads them, `AvifDecoder.decodeGainMap`, rendered as libavif's `avifgainmaputil` renders them within one 10-bit code value, see Known limits); auxiliary images (alpha, depth, thumbnails, the gain map) listed and decoded (`auxiliaryImages`, `decodeAuxiliaryImage`); collections: every master image as a page (`AvifDecoder.openPages`, `Images.openPages`; hidden items, thumbnails, auxiliary images, tone maps and the inputs of derived images left out, each `altr` group one page) with the other entity groups as `PageReader.groups` (`ster` left view then right view, `brst`, `tsyn`, `albc` and any other type); images, derived-image inputs and alpha images coded with HEVC, AVC, JPEG, JPEG 2000 or as uncompressed samples decoded by the HEIC decoder when `smalt-mpeg` is present
- **Encode:** still images and image sequences (`AvifEncoder`, `Images.encode`, `Images.openAnimationWriter`, ImageIO): quality 0-100 and speed 0-10 on libavif's scales, presets `forWeb`, `forDistribution` and `forArchive`, lossy 8-bit 4:2:0 sequences coded with predicted frames by the good-quality AV1 encoder (`interFrames`, on by default), lossless (RGB as 4:4:4 identity), 8/10/12-bit, 4:2:0/4:2:2/4:4:4/monochrome, lossless or lossy alpha items (premultiplied on request), automatic AV1 tiles, image grids for pictures over 8192 samples, `nclx`/ICC color, `irot`/`imir`, `clli`/`mdcv`, Exif and XMP; AV1 coded by an intra-only rate-distortion encoder (partitions down to 4x4, every intra mode with angle deltas, filter intra, chroma-from-luma, palettes for screen content, every transform size and intra type, deblocking, CDEF and, at speeds 0-4, loop restoration searches); gain maps (`AvifEncoder.encode(picture, gainMap, ...)`: a hidden gain map item, a `tmap` item with the ISO 21496-1 metadata and the alternate color, an `altr` group and the `tmap` brand)
- **Artifact:** `smalt-video` (HEIF parser in `smalt-core`)
- **Package:** `image.avif`, `internal.heif`
- **Source:** libavif (container semantics, sample transform operators, writer layout); AV1 payloads through the dav1d port; the encoder follows libaom's all-intra mode
- **Patents:** Alliance for Open Media Patent License 1.0 (royalty-free)

</details>

### Video codecs

<details>
<summary><b>VP9</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** profiles 0-3, 8/10/12-bit (rounded to 8-bit on output unless `VideoDecoderOptions.builder().highBitDepth(true)`), 4:2:0, 4:2:2, 4:4:4 and 4:4:0 (`ChromaFormat.YUV440`); transparent WebM video (`AlphaMode` 1, the alpha frames of `BlockAdditional` 1) into pictures with an alpha plane, rescaled to the frame's bit depth, bit-exact with libvpx
- **Encode:** profiles 0-3: 4:2:0, 4:2:2, 4:4:0 and 4:4:4 at 8, 10 and 12 bits, with the color space and range of `Vp9EncoderOptions.color` signaled (BT.601, BT.709, SMPTE 170M/240M, BT.2020, sRGB 4:4:4, full or limited range); real-time (one-pass constant bitrate, libvpx speeds 5-9); good quality (`Vp9EncoderOptions.ofBitrate`, `ofQuality`, `forArchive`: lookahead of up to 25 frames, temporally filtered hidden alternate references in superframes, compound prediction, rate-distortion partition and mode search down to 4x4 blocks, trellis quantization in two passes, variable bitrate, constrained quality and constant quality, one pass or two passes with `Vp9FirstPass`, libvpx good speeds 0-4); best quality (`Vp9EncoderUsage.BEST_QUALITY`, libvpx `--best`: the good-quality coding with the fullest partition, mode, transform, filter and motion search, every block's rate-distortion estimate taken after trellis quantization); transparent video (`Vp9EncoderOptions.alpha`, `VideoEncoderOptions.alpha`, `VideoWriterOptions.alpha`): the alpha planes as a second 8-bit 4:2:0 VP9 stream at a constant quantizer (`alphaQuality`), in the packets' `PacketSideData.alphaFrame`, with key frames wherever the main stream has them, as WebM stores it
- **Artifact:** `smalt-video`
- **Package:** `video.vp9`
- **Source:** libvpx
- **Patents:** Google's royalty-free VP8/VP9 grant (`PATENTS` in libvpx)

</details>

<details>
<summary><b>AV1</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** main, high and professional, 8/10/12-bit (rounded to 8-bit on output unless `Av1DecoderOptions.builder().highBitDepth(true)`; 10 and 12-bit YCgCo-Re/Ro frames become 8-bit RGBA, exact for lossless 10-bit YCgCo-Re), film grain; scalable streams at any operating point (`operatingPoint`), the highest spatial layer or every layer (`allLayers`); low-overhead OBUs or Annex B length-delimited temporal units (`annexB`); large scale tile decoding of tile list OBUs from anchor frames (`Av1Decoder.setAnchorFrames`)
- **Encode:** real-time (one-pass, low delay, speeds 7-10); good quality (`Av1EncoderOptions.ofQuality`, `ofBitrate`, `forArchive`: lookahead, alternate reference pyramids of up to 16 frames, temporal filtering, compound prediction, constant quality or variable bitrate, two passes over first-pass statistics (`Av1FirstPass`, `firstPassStatistics`), libaom good speeds 2-6); intra-only at constant quality (`Av1EncoderOptions.forIntraOnly`); every usage 8/10/12-bit, 4:2:0 to 4:4:4 and monochrome (`pictureFormat`), main, high and professional profiles; film grain signaled from a libaom grain table (`filmGrain`) or estimated from a denoised source (`denoise`); screen content tools (`screenContent`: palette blocks in every usage, whole-sample motion in good quality); super-resolution of the predicted frames (`superResolution`); real-time and good-quality size changes within the sequence header's size without a key frame, predicting from scaled references (the good-quality lookahead keeps the pictures of the old size); timing information, constant frame rate or the Annex E decoder model with per-frame removal and presentation times chosen from the coded sizes to conform to the level, which follows the frame rate (with the hidden frames good quality decodes) and bitrate (`timingInfo`)
- **Artifact:** `smalt-video`
- **Package:** `video.av1`
- **Source:** dav1d (decoder), libaom (encoder)
- **Patents:** Alliance for Open Media Patent License 1.0 (royalty-free)

</details>

<details>
<summary><b>FFV1</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** versions 0, 1 and 3 (RFC 9043): Golomb Rice and range coding (default or custom state transition tables, coded initial states), every number of quantization table sets, slices with or without CRCs (decoded in parallel), frames that continue the context states of the previous frame; Y'CbCr 4:2:0, 4:2:2, 4:4:0, 4:4:4, 4:1:1 and 4:1:0 (the last two repeated to 4:2:2 and 4:2:0) and gray, RGB (the JPEG 2000 reversible color transform), with or without a transparency plane, 1 to 16 bits (8-bit unless `VideoDecoderOptions.builder().highBitDepth(true)`); the field order and sample aspect ratio of the slice headers; from Matroska (`V_FFV1` and `V_MS/VFW/FOURCC`), AVI and MP4 (`FFV1` with its `glbl` box)
- **Encode:** versions 0, 1 and 3, every coder (`Ffv1Coder`), the large context model, Y'CbCr 4:2:0, 4:2:2, 4:4:0, 4:4:4 and gray, RGB, 8 to 16 bits, with or without a transparency plane, slices (coded in parallel, on the chroma sample boundaries) with CRCs, a key frame interval, the field order and sample aspect ratio; lossless, decoded by ffmpeg to the source samples
- **Artifact:** `smalt-video`
- **Package:** `video.ffv1`
- **Source:** RFC 9043
- **Patents:** none

</details>

<details>
<summary><b>Theora</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes: bitstream versions 3.0 to 3.2, intra and inter frames, every macro block mode with the golden and previous reference frames, half- and quarter-sample motion vectors reaching outside the frame, up to three quality indices per frame, DC prediction and the loop filter; Y'CbCr 4:2:0, 4:2:2 and 4:4:4 cropped to the picture region; dropped frames (empty packets) repeat the previous picture; bit-exact with libtheora 1.2.0 (its post-processing off, the default); from Ogg (`.ogv`) and Matroska (`V_THEORA`)
- **Encode:** key and inter frames (`TheoraEncoder`, `VideoEncoders`): every macro block mode against the previous and golden frames with half-sample motion vectors from a predictive search backed by a grid over the vector range, one vector per luma block, intra macro blocks, uncoded unchanged blocks and empty packets (dropped frames) for unchanged pictures; key frames at a key frame interval (granule shift sized to it), on request and at scene cuts; quality indices 0-63 on libtheora's VP3 quantizers, 4:2:0, 4:2:2 and 4:4:4, any picture size (coded in whole macro blocks), the frame rate and sample aspect ratio in the identification header, the headers as the track configuration for the Ogg and Matroska muxers; decoded by libtheora 1.2.0 and ffmpeg to the samples `TheoraDecoder` returns
- **Artifact:** `smalt-video`
- **Package:** `video.theora`
- **Source:** libtheora
- **Patents:** none (royalty-free)

</details>

<details>
<summary><b>QuickTime Animation (<code>rle </code>)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** every depth: 1, 2, 4 and 8-bit indices (the sample description's color table or the default Macintosh table), 5-5-5 RGB, RGB, ARGB and 1 to 8-bit gray; key frames and the inter frames that update a row range and skip unchanged pixels; QuickTime and Matroska (`V_QUICKTIME`); pixel for pixel equal to ffmpeg's decode (`QtrleDecoder`)
- **Encode:** 5-5-5 RGB, RGB, ARGB and 8-bit gray (`QtrleEncoder`), key frames at an interval and inter frames coding only the changed rows and pixels; lossless at 24 and 32 bits, decoded by ffmpeg to the source pixels
- **Artifact:** `smalt-video`
- **Package:** `video.qtrle`
- **Source:** Apple QuickTime File Format
- **Patents:** none

</details>

<details>
<summary><b>GoPro CineForm (CFHD)</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** intra frames and groups of two frames coded with a temporal wavelet; 4:2:2 Y'CbCr at 8, 10 or 12 bits, RGB 4:4:4, RGBA 4:4:4:4 (alpha decompanded); CineForm RAW (Bayer) in its four phases to the color filter array mosaic, linearized through the encode curve of its metadata, or demosaiced to 16-bit RGB, both bit-exact with GoPro's 16-bit Bayer and RGB decodes (`CfhdDecoderOptions.bayerOutput`, phase per frame from `CfhdDecoder.bayerPhase`); progressive and interlaced frames, any frame size; every codeset, peak tables and difference-coded bands; QuickTime and Matroska `CFHD`, AVI `CFHD` (`CfhdDecoder`); other formats within a few levels of GoPro's and ffmpeg's decodes (see Known limits)
- **Encode:** no
- **Artifact:** `smalt-video`
- **Package:** `video.cfhd`
- **Source:** port of GoPro's cineform-sdk decoder (Apache 2.0 or MIT)
- **Patents:** none

</details>

<details>
<summary><b>Apple ProRes</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** every flavor (422 Proxy, LT, 422, HQ, 4444, 4444 XQ; QuickTime `apco`, `apcs`, `apcn`, `apch`, `ap4h`, `ap4x` and Matroska `V_PRORES`), any frame size, progressive and interlaced (fields woven), 8 and 16-bit alpha; 4:2:2 decodes to 10-bit and 4:4:4 to 12-bit `Yuv16Picture`s by default (`ProResDecoderOptions.builder().highBitDepth(false)` computes 8-bit pictures directly; the registry follows `VideoDecoderOptions.highBitDepth`), slices in parallel; samples within one code value of ffmpeg's decode (RDD 36 leaves the IDCT implementation-defined within its Annex A bounds, which the decoder's transform meets)
- **Encode:** every flavor with Apple's quantization matrices and target data rates (scaled to the frame size and rate, or a set bitrate or fixed quantizer), alpha for 4444, interlaced, any picture representation
- **Artifact:** `smalt-video`
- **Package:** `video.prores`, `video.intra.internal`
- **Source:** spec (SMPTE RDD 36); the encoder's quantization matrices are the ones Apple's encoders signal in their frame headers
- **Patents:** **Apple licenses ProRes implementations** (see [Licensing of ProRes and DNxHD](#licensing-of-prores-and-dnxhd)); not covered by the MIT license

</details>

<details>
<summary><b>Avid DNxHD / DNxHR (VC-3)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** every compression ID of SMPTE ST 2019-1: DNxHD 1235-1260 (1920x1080, 1440x1080, 1280x720 and 960x720, progressive, field-encoded interlaced and the adaptive field/frame macroblocks of 1260; 8 and 10-bit, the 4:4:4 RGB ID 1256) and DNxHR 1270-1274 (LB, SQ, HQ, HQX, 444; any size; 4:2:0, 4:2:2, 4:4:4 Y'CbCr and RGB; 8, 10 and 12-bit); QuickTime `AVdn`/`AVdh`, Matroska `V_QUICKTIME` (and `V_MS/VFW/FOURCC`); macroblock rows in parallel; 4:4:4 frames with the color format flag set decode to full-range RGBA, Y'CbCr-coded macroblocks converted with BT.709 (see [Licensing of ProRes and DNxHD](#licensing-of-prores-and-dnxhd)); alpha: the in-band channel of Amendment 1:2023 (DCT-coded on every ID that permits it, lossless run-length on 1256 and 1270, premultiplied video (`PMA`) divided back to straight alpha) and the out-of-band alpha of Avid's QuickTime encoders (raw 8-bit or a second coding unit)
- **Encode:** every compression ID: DNxHR LB, SQ, HQ (8-bit 4:2:2 or 4:2:0), HQX (10/12-bit 4:2:2 or 4:2:0), 444 (10/12-bit Y'CbCr or RGB; RGB coded as BT.709 Y'CbCr macroblocks by default, `alternateColorSpace`) at any size, DNxHD picked from the raster, scan, depth and bitrate (or named), ID 1260 with adaptive field/frame macroblocks; in-band alpha (`alpha`, lossless on 1256 and 1270 with `losslessAlpha`, premultiplied with `premultipliedAlpha`) or Avid's out-of-band alpha on every ID (`alphaStorage`: raw 8-bit samples or a second coding unit, declared by an `AALP` atom in the QuickTime or Matroska sample description); constant frame size
- **Artifact:** `smalt-video`
- **Package:** `video.dnxhd`, `video.intra.internal`
- **Source:** spec (SMPTE ST 2019-1:2016 and Amendment 1:2023, tables of Annexes C to E)
- **Patents:** **Avid licenses DNxHD** (see [Licensing of ProRes and DNxHD](#licensing-of-prores-and-dnxhd)); not covered by the MIT license

</details>

<details>
<summary><b>DV (IEC 61834, DVCPRO, DVCPRO50, DVCPRO HD)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** every format: 25 Mb/s DV 525/60 4:1:1 and 625/50 4:2:0 (MiniDV, DVCAM), DVCPRO 4:1:1 (SMPTE ST 314), DVCPRO50 4:2:2 and DVCPRO HD 1080/60i, 1080/50i, 720/60p and 720/50p (SMPTE ST 370), the format read from each frame; 8-8 and 2-4-8 DCT blocks, DVCPRO HD frame and field macroblocks, the three passes of the compressed data, video segments in parallel; 4:1:1 decodes to 4:2:2 (each chroma sample in two columns), pictures at the coded size (DVCPRO HD is horizontally subsampled, its sample aspect ratio on the container's track); a 720-line packet of two frames yields both; samples within 2 code values of ffmpeg's decode (the DCT is not normative)
- **Encode:** every format (`DvEncoderOptions.profile`), the 4:3 or 16:9 display, adaptive 2-4-8 or field DCT (`adaptiveDct`), a finest-fit quantizer per video segment refined per macroblock, constant frame size; whole DIF frames with header, subcode (time code from zero), VAUX and empty audio sections, which `DvMuxer` fills
- **Artifact:** `smalt-video`
- **Package:** `video.dv`
- **Source:** spec (SMPTE ST 314:2005, ST 370:2013; the IEC 61834-2 625/50 4:2:0 layout checked against ffmpeg's decoder as a black box)
- **Patents:** expired

</details>

Codec behavior worth knowing:

<details>
<summary><b>AV1 through the codec-independent API</b></summary>

AV1 through the codec-independent API (`VideoEncoders`, and so the transcoder) uses the good-quality usage
for files and on-demand streams (`VideoEncoderOptions.of`): `Av1EncoderOptions.ofBitrate` at the requested
bitrate with the `FAST` speed (libaom `--good --cpu-used=6`), whose lookahead delays the stream by one to two
seconds and needs `flush` at the end. A real-time request (`VideoEncoderOptions.forRealtime`) keeps the
real-time engine. On a 720p transcode the good-quality usage reaches the real-time engine's quality in 60%
fewer bits, at about nine times the encoding time; construct `Av1Encoder` with `Av1EncoderOptions.defaults()`
for the real-time engine's speed in a file. VP9 does the same: `Vp9EncoderOptions.ofBitrate` at the requested
bitrate with `SPEED_4` (libvpx `--good --cpu-used=4`) for files and on-demand streams, the real-time engine for
real-time requests. On the foreman clip at 80 to 320 kbit/s, the good-quality VP9 encoder's BD-PSNR is 0.5 dB
above libvpx's good-quality encoder in one pass at speeds 0, 2 and 4, and 0.1 to 0.2 dB below it in two passes
at speeds 0 to 2; the best quality is 0.5 dB above libvpx's `--best` in one pass and 0.1 dB below it in two.
VP8 follows too, with `Vp8EncoderOptions.ofBitrate` and `SPEED_4` (libvpx
`--good --cpu-used=4`); its hidden alternate references reach the muxer as packets of their own, flagged
`PacketFlag.DISCARD` and stamped with the time of the next shown frame. On the same clip the good-quality VP8
encoder lands within 3% of the requested bitrate in two passes, with a BD-PSNR within 0.2 dB of libvpx's at
speeds 0, 2 and 4 and at the best quality; in one pass it spends 5 to 11% less than requested, at a BD-PSNR
0.7 to 1.1 dB above libvpx's one-pass encoder.

</details>

<details>
<summary><b>DNxHD and DNxHR: where Smalt follows SMPTE ST 2019-1 and ffmpeg does not</b></summary>

DNxHD and DNxHR follow SMPTE ST 2019-1:2016 and Amendment 1:2023 wherever ffmpeg's dnxhd codec departs from
them, so files exchanged with ffmpeg differ in these known ways:

- AC reconstruction: equation 8.1 puts a level half a quantizer step past the coded level; ffmpeg's decoder
  uses about three quarters, so the two decode the same stream a few code values apart on coarsely quantized
  frames (45-60 dB against each other). DNxHR HQX uses the weights the standard lists for it; ffmpeg uses
  those of ID 1241 and decodes Smalt's HQX about 12 dB worse than Smalt does.
- The color format flag `CLF` of IDs 1256 and 1270 means RGB, and RGB frames may code their macroblocks as
  BT.709 Y'CbCr (`ACF`); the decoder returns RGB either way. ffmpeg reads the flag the other way round,
  orders RGB channels green, blue, red and treats them as full range. Smalt's encoder codes RGB input as
  Y'CbCr macroblocks by default, the form other encoders' files use and ffmpeg decodes with the right
  colors; `alternateColorSpace(false)` codes the RGB samples themselves.
- Alpha is coded in band, as ST 2019-1 and Amendment 1:2023 define it (DCT-coded, or lossless run-length
  codes on IDs 1256 and 1270 with `losslessAlpha`). Where the standard's prose, tables and figures
  disagree, Smalt applies the amendment's precedence rule (normative prose first), and each code site names
  both readings. Video premultiplied by alpha (`PMA`) is scaled about black for luma, red, green and blue and
  about the middle code for Cb and Cr, a subsampled chroma sample by the mean alpha it covers; the decoder
  divides it back, so pictures always carry straight alpha. The encoder also writes, and the decoder reads,
  the out-of-band alpha of Avid's QuickTime encoders (`alphaStorage`): 8-bit samples (in an area sized for
  10-bit samples on deeper streams, as Avid's encoders size it) or a coding unit of the stream's ID whose
  luma is the alpha, after the video coding units and before the size of the video in four bytes, with the
  `AALP` atom of Avid's sample descriptions (`AUA8`, `AUAX` or the sample entry type) and a 32-bit depth.
  The QuickTime and Matroska demuxers keep the `ACLR`, `APRG`, `ARES`, `AALP` and `ADHR` atoms of Avid's
  and ffmpeg's sample descriptions in the track configuration, and the muxers write them back. ffmpeg 7.1
  ignores alpha in both forms (it decodes the video of out-of-band alpha frames) and rejects some of
  Smalt's in-band alpha frames.
- 4:2:0 DNxHR (Table C.2, `SSC = 01b`) is encoded on request (`chromaFormat`) and decoded; ffmpeg 7.1
  reads such frames as 4:2:2 and fails.
- ID 1260 (thin-raster 1440x1080 interlaced) codes each macroblock in frame or field mode, chosen by the AC
  activity of the field-split blocks; the macroblocks are laid out with the 1440 samples of Table C.1.
- Matroska stores DNxHD and DNxHR as `V_QUICKTIME` with a QuickTime sample description (`AVdn` or `AVdh`), the
  form ffmpeg writes and reads.

</details>

## Known limits

> [!WARNING]
> These are the cases where a codec of this artifact leaves a feature out or differs from another
> implementation. Open the entry of the codec you work with.

### AV1 and VP9

<details>
<summary><b>AV1 large scale tile mode</b></summary>

The AV1 decoder's large scale tile mode decodes tile list OBUs only; the camera frames of libaom's light field
encoder, whose tile data uses libaom's own column-size layout rather than tile groups, are not decoded as
frames (their tiles reach the decoder through the tile lists built from them). Tiles of tile lists are decoded
without CDF adaptation whatever the camera frame header's `disable_cdf_update` says, as libaom decodes them.

</details>

<details>
<summary><b>AV1 frame delay (<code>Av1DecoderOptions.maxFrameDelay</code>)</b></summary>

With `Av1DecoderOptions.maxFrameDelay` above 1, a frame comes out up to that many packets minus one later,
and a corrupt frame's error is raised by the call that would have returned it. With more than one thread,
the ProRes, DNxHD, MPEG-4 Part 2 and VP8 decoders, like H.264, return frames during a later `decode` or
`flush` call.

</details>

<details>
<summary><b>VP9 encoder</b></summary>

The VP9 real-time encoder's speeds 5 and 9 trail libvpx (no reference partition search, no rate-distortion
key-frame intra search). The VP9 good-quality encoder codes one layer of alternate references and prunes
no partitions with libvpx's neural networks; it measures 10 and 12-bit pictures' first pass on their 8-bit
rounding. The AV1 good-quality speeds `SLOW`, `SLOWER` and `SLOWEST` take much longer than
`BALANCED` and `FAST` for compression within measurement noise.

</details>

<details>
<summary><b>AV1 encoder</b></summary>

The AV1 encoder's screen content tools code no intra block copy, and luma palettes only in the frames of the
real-time usage and the inter frames of good quality; the real-time usage detects no screen content
(`Av1ScreenContent.DETECT` codes as `NEVER` there; use `ALWAYS` for screen sharing). Super-resolution takes one
fixed denominator (libaom's `fixed` mode, without its `qthresh` and `auto` choices per frame) and leaves the
good-quality key frames at the picture width. A size change starts a key frame when a reference is more than twice
the new coded size or less than a sixteenth of it, and a new sequence header beyond the largest size so far; in
good quality it also ends the frame group, so the frames before it get no alternate reference from the frames after
it, and `Av1FirstPass` measures one picture size, so a two-pass encode that changes size takes its statistics from
pictures of one size. With the decoder model (`Av1TimingInfo.DECODER_MODEL`), constant quality coding does not
bound the bitrate, so a stream that spends more than its level's bitrate for longer than the one-second smoothing
buffer absorbs is removed late for its presentation and does not conform to the level; the frame buffers and
presentation times are kept conformant for decoding from the last key frame, not from every earlier one.

</details>

### Editing and archival codecs

<details>
<summary><b>DV</b></summary>

The decoder returns 4:1:1 frames as 4:2:2 pictures (each chroma sample in two columns), and the encoder averages
4:2:2 chroma to 4:1:1; the inverse DCT is single precision, within 2 code values of ffmpeg's fixed-point transform.
The error status of a compressed macroblock is not used to conceal it: damaged data decodes to what it codes.
`DvEncoder` writes the audio sections empty and starts the time code at zero (drop frame in the 60 Hz systems);
`DvMuxer` lays the audio into them at the nominal sample count of each frame, so an unlocked source's varying
counts are not reproduced. It lays the samples in the order they arrive from the first frame on, ignoring their
timestamps, so audio that starts after the video or has gaps is not delayed to match it, and it holds the frames
until every track has delivered their audio, so audio that ends long before the video keeps the rest of the video
in memory. A raw DV stream carries no channel layout: a track of more than two channels comes back
as one stereo track per pair. A transcode into raw DV converts the frame rate of interlaced video as it does
progressive video, dropping and repeating (or blending) whole frames, so a conversion between the 50 Hz and 60 Hz
systems does not interpolate fields; it scales fields apart only for Y'CbCr pictures that are not rotated and whose
crop, if any, starts on an even line and spans an even number of lines. A 720-line stream alternates its frames
between DIF channels 0-1 and 2-3, as SMPTE ST 370 assigns the two frames of a DIF frame; ffmpeg's encoder puts every
frame in channels 0-1, which both decoders read. ffmpeg reads the time code of subcode sync 0, where IEC 61834 puts it, so
it reports none for the SMPTE ST 314 and ST 370 layout of the DVCPRO formats. `MxfMuxer` does not write DV.

</details>

<details>
<summary><b>CineForm</b></summary>

Y'CbCrA 4:4:4:4 samples (which GoPro's SDK neither writes nor reads, so the order of their channels is
unknown), uncompressed CineForm RAW samples (which GoPro's decoder fails to decode, so there is no reference for
them) and the older field transform (transform type 1) raise `MediaException.Unsupported`; there is no CineForm
encoder. Demosaiced CineForm RAW frames are GoPro's default 16-bit demosaic in the domain of the encode curve, with
no white balance, color matrix or look applied even when the sample's metadata carries them. A source coded with
the linear encode curve decodes to full-scale 16-bit photosites where GoPro's decoder returns them divided by 4.
Frames whose wavelet coefficients reach extreme magnitudes, as GoPro's encoder writes them for its CineStyle curve
(it applies that curve to photosites it has not normalized), decode unlike GoPro's decoder, and very small frames
have no reproducible reference (GoPro's decoder returns a different 64x32 frame on every run). GoPro's encoder
writes CineForm RAW as progressive intra frames only, so CineForm RAW groups of two frames and interlaced CineForm
RAW frames decode but were checked on no stream.
Pixels of the other formats omit the rounding offsets GoPro's decoder adds to the lowpass band depending on its
output format, so they may differ from GoPro's and ffmpeg's decodes by a few levels of the coded precision. 8-bit
samples, which only old encoders write (GoPro's SDK codes 8-bit input at 10 bits and ffmpeg's encoder writes 10
and 12 bits), follow GoPro's decoder but were checked on no stream.

</details>

<details>
<summary><b>QuickTime Animation</b></summary>

No available encoder writes the index depths, so they are checked on QuickTime's own
1-bit, 2-bit gray and 4-bit gray files and on hand-built 2, 4 and 8-bit streams with color tables, against
ffmpeg's decodes.

</details>

Related limits elsewhere: [transparent video, sample aspect ratios and HEIF files](../core/README.md#known-limits)
in core, [Theora](../audio/README.md#other-codecs) with the Xiph codecs in audio,
[VP8, Motion-JPEG and HAP](../image/README.md#known-limits) in image.

## Licensing of ProRes and DNxHD

ProRes and DNxHD/DNxHR are written from public SMPTE documents (RDD 36 and ST 2019-1) with no code ported from
another implementation, but both formats belong to companies that license implementations: Apple runs a
ProRes licensing program for encoders and decoders (contact: ProRes@apple.com, named in RDD 36 for questions on
intellectual property), Avid licenses DNxHD and DNxHR, and SMPTE ST 2019-1 notes that implementing VC-3 may
involve patents whose holders assured SMPTE they would license them on reasonable and non-discriminatory
terms. The names ProRes, DNxHD and DNxHR are trademarks of their owners. Both codecs ship in `smalt-video`;
check whether your use needs a license.

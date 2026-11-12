<div align="center">

# smalt-mpeg

**The codecs licensed through patent pools or by Dolby**

![Artifact](https://img.shields.io/badge/artifact-smalt--mpeg-2f6fe6?style=flat-square&labelColor=0b2a6f)
![Module](https://img.shields.io/badge/module-com.github.auties00.smalt.mpeg-2f6fe6?style=flat-square&labelColor=0b2a6f)

[Smalt](../README.md) | [core](../core/README.md) | [image](../image/README.md) | [audio](../audio/README.md) | [video](../video/README.md) | **mpeg** | [capture](../capture/README.md) | [raw](../raw/README.md) | [desktop](../desktop/README.md) | [aot](../aot/README.md) | [bom](../bom/README.md)

</div>

`smalt-mpeg` holds the MPEG and ITU-T video codecs, the MPEG audio codecs, Dolby's and DTS, in their own
artifact so that an application can leave them out. It depends on [`smalt-core`](../core/README.md) only.

**Contents:** [What it covers](#what-it-covers) | [Install](#install) | [Guide](#guide) |
[Format coverage](#format-coverage) | [Known limits](#known-limits) | [Patents](#patents)

## What it covers

- **Video, decoded and encoded:** H.264, HEVC, MPEG-4 Part 2, H.263, JPEG XS.
- **Video, decoded:** VVC, Sorenson Spark, MPEG-1 and MPEG-2 video, VC-1.
- **Images:** HEIC (HEVC-coded HEIF) and JPEG XS.
- **Audio, decoded and encoded:** AAC in its object types (with HE-AAC and USAC), AC-3, E-AC-3, AMR-NB and
  AMR-WB.
- **Audio, decoded:** DTS and MPEG-4 CELP.

> [!IMPORTANT]
> Several of these formats are covered by patents. Smalt's MIT license grants no patent rights: read
> [Patents](#patents) and check whether your use needs a license.

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-mpeg</artifactId>
</dependency>
```

The version comes from the [BOM](../bom/README.md); Gradle coordinates and JVM flags are in the
[root README](../README.md#install).

## Guide

Decoding frames, reading audio, writing files and transcoding, whatever the codec, are in the
[core guide](../core/README.md#guide); these examples use the codecs of this artifact directly.

### Write HEIC

```java
var picture = Images.decode(MediaSource.of(Path.of("photo.png"))).picture();
byte[] heic = HeicEncoder.encode(picture, HeicEncoderOptions.ofQuality(60));
byte[] deep = HeicEncoder.encode(deepPicture, HeicEncoderOptions.forArchive()); // 10-bit, the heix brand
byte[] exact = HeicEncoder.encode(picture, HeicEncoderOptions.forLossless());   // RGB as 4:4:4 identity
try (var writer = HeicEncoder.openAnimationWriter(640, 480, HeicEncoderOptions.defaults(), sink)) {
    for (var frame : frames) {
        writer.write(frame, 40_000);                                    // an msf1 image sequence
    }
    writer.finish();
}
```

Images are HEVC still pictures at a constant quality (0 to 100, default 50); transparency becomes an alpha item,
pictures over the HEVC level 5.1 size (4096x2176) become a grid of 512-sample images, and odd sizes are cropped by a
clean aperture. `Images.encode(picture, ImageFormat.HEIC, ...)`, `Images.openAnimationWriter(ImageFormat.HEIC, ...)`
and ImageIO reach the same encoder.

### Code video in real time

```java
var encoder = new H264Encoder(1280, 720, H264EncoderOptions.forRealtime().toBuilder().bitrate(1_200_000).build());
var decoder = new H264Decoder(track, H264DecoderOptions.forRealtime()); // one picture out per access unit
encoder.encode(new VideoFrame(picture, pts, 3000, false), packet -> send(packet)); // 90 kHz timestamps
encoder.setTargetBitrate(800_000);                                         // follow the congestion controller
encoder.requestKeyframe();                                                 // a receiver lost packets
decoder.decode(packet, (frame, damaged) -> show(frame, damaged));          // decodeIncomplete if a part was lost
```

Every video codec has the same shape: `Vp8Encoder`, `Vp9Encoder` and `Av1Encoder` take the picture size and
`Vp8EncoderOptions.forRealtime()`, `Vp9EncoderOptions.forRealtime()` or `Av1EncoderOptions.forRealtime()`,
and their `defaults()` (for H.264 also `forFile()`) suit files. For AV1, VP9 and VP8 files, `ofQuality(q)`,
`ofBitrate(bps)` and `forArchive()` of `Av1EncoderOptions`, `Vp9EncoderOptions` and `Vp8EncoderOptions` select
the good-quality usage: pictures wait in a lookahead and come out a frame group at a time (one packet per
picture, in display order, plus, for VP8, a packet flagged `PacketFlag.DISCARD` for each hidden alternate
reference), so call `flush` at the end of the stream; `Vp9FirstPass` and `Vp8FirstPass` measure a clip for a
two-pass encode. Options are immutable, come from a preset or `builder()`, change through `toBuilder()`, and carry the
`MediaLimits` the codec enforces.

### Convert speech to AMR

```java
// ffmpeg -i speech.m4a -ar 8000 -ac 1 -c:a libopencore_amrnb -b:a 12.2k speech.amr
Transcoder.transcode(Path.of("speech.m4a"), Path.of("speech.amr"));    // AMR-NB 12.2 kbit/s, 8 kHz mono

// or frame by frame, with discontinuous transmission and a mode change
try (var encoder = new AmrEncoder(AmrEncoderOptions.of(AmrMode.NB_7_95).toBuilder().dtx(true).build())) {
    var frame = new byte[61];
    var length = encoder.encodeFrame(pcm, 0, frame, 0);                 // 160 samples -> one storage-format frame
    encoder.setMode(AmrMode.NB_12_2);                                  // AMR may switch modes on any frame
}
```

`AmrDecoder` decodes AMR-NB and AMR-WB from `.amr` (multichannel files included), MP4/3GP and Matroska
tracks, concealing damaged and missing frames; both codecs are bit-exact with the 3GPP reference code.
`AmrEncoderOptions.vad(AmrVad.OPTION_2)` selects the second AMR-NB voice activity detector for DTX.

## Format coverage

Open a codec to see what is decoded, what is encoded, the package that implements it (relative to
`com.github.auties00.smalt`), where its code comes from and what to know about patents. In *Source*, "spec"
means the code was written from the specification alone; a named project means code was ported or derived from it
(see [Acknowledgments](../README.md#acknowledgments)).

### Video codecs

<details>
<summary><b>H.264 / AVC</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** 4:2:0 and 4:0:0 up to High profile and the Extended profile (SP, switching SP and SI slices, slice data partitions A, B and C reassembled across packets and interleaved slices), High 10 (with High 10 Intra), High 4:2:2 (with High 4:2:2 Intra), High 4:4:4 Predictive and its Intra and CAVLC 4:4:4 Intra profiles: 4:0:0, 4:2:0, 4:2:2 and 4:4:4 (color planes coded together or separately, GBR included) at 8 to 14 bits, luma and chroma depths independent (rounded to 8-bit on output unless `highBitDepth` is set), lossless macroblocks (transform bypass with its intra prediction), CAVLC and CABAC, progressive and interlaced: field pictures, MBAFF frames and both mixed, fields woven into frames; multiview streams (Multiview High and Stereo High: inter-view prediction, two to eight views, the base view by default and any set of views or all of them through `H264DecoderOptions.outputViews` and `allViews`, each frame naming its view in `VideoFrame.sideData().layer()`; MFC High frame-compatible stereo, with the full-resolution left and right views of ITU-T H.264 G.8.6 through `enhancedResolution`; Multiview Depth High and MFC Depth High, with the depth views as monochrome frames through `depth`; Enhanced Multiview Depth High (3D-AVC): depth parameter sets, slice header prediction, depth views at a reduced resolution, the non-linear depth representation undone on output, every texture and depth view, with the texture coding tools of the non-base views: depth-based motion vector prediction, view synthesis prediction, adaptive luminance compensation, run-length skip coding, and the neighboring block disparity derivation, plain and refined with depth, of texture-first coding, and depth-range-based weighted prediction of the depth views; the depth views the texture tools sample are decoded whether or not `depth` outputs them); scalable streams (Scalable Baseline, Scalable High and Scalable High Intra: coarse-grain and medium-grain quality layers, dyadic and extended spatial layers with cropping and chroma phases, inter-layer prediction, key pictures with reference base pictures, progressive and interlaced layers with field pictures and MBAFF frames, the base layer by default and any layer through `H264DecoderOptions.targetLayer` or `highestLayer`, 4:2:0 8-bit layers); multiview and scalable MP4 tracks through `mvcC`/`svcC`, layered tracks and aggregators; temporal layers dropped through `maxTemporalId`; alpha auxiliary coded pictures attached as the alpha plane (`H264DecoderOptions.alpha`); film grain characteristics SEI rendered bit-exactly as SMPTE RDD 5 specifies (`H264DecoderOptions.filmGrain`), picture timing `pic_struct`, frame packing arrangement and display orientation reported in `VideoFrame.sideData`, alternative transfer characteristics in the color description; AVC items and image sequences of HEIF files through `HeicDecoder`
- **Encode:** Constrained Baseline, Main, High, High 10, High 4:2:2 and High 4:4:4 Predictive: 4:0:0, 4:2:0, 4:2:2 and 4:4:4 at 8 to 14 bits (`H264EncoderOptions.chromaFormat`, `bitDepth`), lossless (`lossless`, transform bypass with its intra prediction), 4:4:4 color planes coded separately (`separateColorPlanes`), CABAC or CAVLC (`cavlc`), progressive frames, field pictures and MBAFF frames (`fieldOrder`, `fieldCoding`); I, P and B pictures (`bFrames`, with CABAC; spatial direct, skipped, list 0, list 1 and bi-predicted macroblocks) in every format, depth and field structure, lossless included; slices (`slices`) and a slice size limit (`maxSliceBytes`), periodic intra refresh with recovery point SEI (`intraRefresh`), up to four temporal layers with prefix NAL units (`temporalLayers`), the sample aspect ratio, chroma sample location and fixed frame rate in the VUI and the HDR metadata SEI of the color description; constant quality, average bitrate and constant bitrate rate control, two-pass average bitrate for a target size (`H264FirstPass`, `H264EncoderOptions.firstPassStats`), NAL hypothetical reference decoder signaling with buffering period and picture timing SEI and, at a constant bitrate, filler data for broadcast transport streams (`hrd`), adaptive quantization by variance, auto-variance and dark-scene bias (`adaptiveQuantization`, `adaptiveQuantizationStrength`), the macroblock tree strength (`macroblockTreeStrength`) and texture retention in mode decision (`psychovisualStrength`); RGB input converted with any matrix, YCgCo, BT.2020 constant luminance and identity (4:4:4) included
- **Artifact:** `smalt-mpeg`
- **Package:** `video.h264`, `video.h264.encoder`
- **Source:** spec (ITU-T H.264); tables from OpenH264 and the JM reference software; CABAC encoder engine from the HM reference software
- **Patents:** **patent pool** (Via LA AVC/H.264); not covered by the MIT license

</details>

<details>
<summary><b>HEVC / H.265</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** Main, Main 10, Main Still Picture, the range extension profiles (Main 12, Main 4:2:2 10/12, Main 4:4:4 8/10/12, Main 4:4:4 16 Intra, Monochrome 8/12/16, High Throughput 4:4:4 16 Intra and their Intra and Still Picture variants) and the screen content coding profiles (Screen-Extended Main, Main 10, Main 4:4:4, Main 4:4:4 10, High Throughput 4:4:4, 4:4:4 10 and 4:4:4 14): 4:0:0, 4:2:0, 4:2:2 and 4:4:4 at 8 to 16 bits, 4:4:4 also coded as separate color planes (`separate_colour_plane_flag`) (rounded to 8-bit on output unless `highBitDepth` is set), every Main tool (tiles, wavefronts, dependent slices, scaling lists, PCM, transform skip, lossless, weighted prediction, long-term references, CRA/BLA/RASL), every range extension tool (cross-component prediction, implicit and explicit residual DPCM, transform skip rotation, contexts and large blocks, extended precision, persistent Rice adaptation, CABAC bypass alignment, chroma QP offset lists, SAO offset scaling, disabled intra smoothing, high precision weighted prediction offsets), every screen content coding tool (palette mode with SPS and PPS predictor initializers, intra block copy through current picture referencing, adaptive color transform with PPS and slice QP offsets, adaptive motion vector resolution, disabled intra boundary filtering), mastering display, content light level, alternative transfer characteristics and color description; film grain characteristics SEI rendered as SMPTE RDD 5 specifies (`HevcDecoderOptions.filmGrain`); picture timing, frame packing arrangement and display orientation reported in `VideoFrame.sideData`; field coded sequences (`field_seq_flag`) woven into frames by the picture timing `pic_struct` (parity and pairing); random access at IRAP pictures and at recovery point SEI messages (gradual decoding refresh included, missing references generated); output cropped by the conformance window, and also by the VUI default display window with `HevcDecoderOptions.defaultDisplayWindow`; the multi-layer extensions (Annexes F to H): MV-HEVC views (Multiview Main) and SHVC enhancement layers (Scalable Main, Main 10, Monochrome and the scalable range extension profiles) with spatial scalability at any ratio (reference region and scaled reference layer offsets, resampling phases), bit depth and color gamut scalability (the colour mapping table), inter-layer sample and motion prediction, POC resetting and additional layer sets, decoding the layers of the output layer set `HevcDecoderOptions.outputLayerSet` selects (the base layer by default, the last set with `lastOutputLayerSet`), output in layer order within each access unit with the layer and view in `VideoFrame.sideData().layer()`; alpha auxiliary layers (AuxId 1 with the alpha channel information SEI, as Apple's HEVC with alpha in MOV and MP4) attached to their primary pictures as the alpha plane (`HevcDecoderOptions.alpha`), depth and other auxiliary layers output as layers of their own; 3D-HEVC (Annex I, 3D Main) texture and depth views with every coding tool: inter-view predicted, shifted and disparity merging candidates with sub-block partition motion, neighboring block disparity vectors (with `depth_ref_enabled_flag` refined from the depth view without their vertical component, as in the reference decoder only together with depth-based block partitioning), the view scaling of inter-view motion vectors, advanced residual prediction, illumination compensation, view synthesis prediction and depth-based block partitioning in the texture views; the texture merging candidate with sub-block partitions, the wedgelet and contour intra modes, DC coding of intra and inter coding units with depth look-up tables, the intra skip modes and the coding quadtree and partitioning predicted from the texture in the depth views, whose whole-sample motion is decoded and which are output as depth layers; single HEIC images through `HeicDecoder`
- **Encode:** Main, Main 10, Main Still Picture and Main 10 Still Picture, and the range extension profiles Main 4:2:2 10, Main 4:4:4, Main 4:4:4 10 and Main 4:4:4 Still Picture (`HevcEncoder`, `HevcEncoderOptions.profile`, `bitDepth`, `chromaFormat`): 4:2:0, 4:2:2 and 4:4:4 at 8 and 10 bits (the identity matrix in 4:4:4); lossless coding with `cu_transquant_bypass_flag` on every coding unit (`lossless`, in every profile); I, P and hierarchical B pictures (`bFrames`, up to 7) predicted from up to three reference pictures; CABAC with a rate-distortion decision of 64x64 coding tree blocks (coding units from 64x64 to 8x8, the 35 intra modes, 4x4 intra blocks and the chroma modes, skip, merge and motion vectors searched to a quarter sample, the 2NxN and Nx2N partitions, transform tree splits) at four speeds (`HevcEncoderSpeed`), sign data hiding, deblocking and the sample adaptive offset (off in lossless streams); wavefront substreams decided and coded in parallel (`threads`, the same stream for every thread count); constant quality, average and constant bitrate (`HevcRateControl`, `setTargetBitrate`), IDR pictures periodically, on request (`requestKeyframe`) and at scene cuts; the decoded picture hash SEI (MD5, CRC or checksum, `pictureHash`), the VUI color description and sample aspect ratio and the mastering display and content light level SEI of the color description; length-prefixed packets with an `hvcC` record or Annex B access units (`HevcPacketFormat`); RGB input converted with the color matrix (8-bit RGB copied exactly with the full-range identity matrix), other Y'CbCr layouts and depths resampled; the stream through `VideoEncoders` (the identity matrix selecting Main 4:4:4) and the transcoder (MP4, fragmented MP4, MOV, Matroska, MPEG-TS, Annex B)
- **Artifact:** `smalt-mpeg`
- **Package:** `video.hevc`, `video.hevc.encoder`
- **Source:** spec (ITU-T H.265); CABAC decoder engine from the HM reference software, palette mode from HM-SCM; the encoder's arithmetic coder, bit estimates, sample adaptive offset decision and sign data hiding from HM, its reconstruction through the decoder's normative processes
- **Patents:** **patent pools** (Access Advance, Via LA HEVC); not covered by the MIT license

</details>

<details>
<summary><b>VVC / H.266</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** Main 10 and Main 10 Still Picture: 4:0:0 and 4:2:0 at 8 to 10 bits (rounded to 8-bit on output unless `highBitDepth` is set), every version 1 tool: quadtree with nested multi-type tree partitioning and dual intra trees, the 67 intra modes with wide angles, multiple reference lines, intra sub-partitions, matrix-based intra prediction, cross-component linear models and position dependent combination; merge, AMVP, history-based, temporal and pairwise candidates, merge with motion vector differences, symmetric MVD, adaptive motion vector resolution (half-sample filter included), affine motion with prediction refinement by optical flow, subblock-based temporal motion vector prediction, geometric partitioning, combined inter and intra prediction, bi-prediction with CU weights, explicit weighted prediction, decoder side motion vector refinement, bi-directional optical flow, intra block copy, reference picture resampling and reference wraparound; multiple transform selection, the low-frequency non-separable transform, subblock transform, transform skip, block DPCM, dependent quantization, sign data hiding, joint chroma residuals, scaling lists (APS), luma mapping with chroma scaling, deblocking with luma adaptive offsets, SAO, the adaptive loop filter and cross-component ALF, virtual boundaries; slices (raster and rectangular), tiles, subpictures, wavefront entry points, picture headers in their own NAL unit or in the slice header, mixed NAL unit types, IDR, CRA (RASL pictures of a stream start skipped) and GDR random access, long-term references; the decoded picture hash SEI checked on request (`VvcDecoderOptions.hashVerification`); field coded sequences (`sps_field_seq_flag`) woven into frames by the frame-field information SEI; the VUI color description, with the mastering display colour volume, content light level and alternative transfer characteristics SEI messages; film grain characteristics rendered by the bit-exact RDD 5 process (`VvcDecoderOptions.filmGrain`); the frame packing arrangement, display orientation, ATSC A/53 caption, frame-field information, ambient viewing environment, content colour volume, sample aspect ratio information and omnidirectional (projection, sphere rotation, region-wise packing, viewport) SEI messages as frame side data; output cropped by the conformance window; the base layer of multi-layer streams; length-prefixed packets with a `vvcC` record or Annex B packets; the 4:2:2 and 4:4:4 profiles, deeper streams and the range extension raise `MediaException.Unsupported`
- **Encode:** no
- **Artifact:** `smalt-mpeg`
- **Package:** `video.vvc`
- **Source:** spec (ITU-T H.266); ported from VVdeC (Clear BSD), which also serves as the reference decoder with the JVET conformance suite
- **Patents:** **patent pools** (Access Advance VVC Advance, Via LA VVC); not covered by the MIT license

</details>

<details>
<summary><b>MPEG-4 Part 2 (Visual)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** Simple and Advanced Simple profiles: I/P/B/S-VOPs in display order, quarter sample, global motion compensation (1-3 warping points), interlaced (field DCT, field prediction, field direct mode), H.263 and MPEG quantization with custom matrices, AC/DC prediction, video packets, data partitioning and reversible VLCs, not-coded VOPs and packed bitstreams (DivX/Xvid in AVI), short video header; FourCCs XVID, DIVX, DX50, FMP4 and their aliases; raw `.m4v` through `Mpeg4ElementaryStream` (the track declaring the sample aspect ratio of the video object layer); the tools of the Core, Main, N-bit, Scalable, Advanced Real Time Simple, Advanced Coding Efficiency and Advanced Core profiles: binary and grayscale shape (alpha planes, multiple auxiliary components), shape only layers, the shape-adaptive DCT, static sprites (basic and low latency, brightness change), N-bit samples (4 to 12 bits, `Yuv16Picture` with `highBitDepth`), NEWPRED, reduced resolution VOPs, spatial and temporal scalability of two tracks (`Mpeg4Decoder(base, enhancement, options)`), with the partial enhancement and background composition of `enhancement_type` 1; Studio profiles and fine granularity scalability raise `MediaException.Unsupported`
- **Encode:** Simple and Advanced Simple profiles (`Mpeg4Encoder`, `Mpeg4EncoderOptions`, `Mpeg4Profile`): I- and P-VOPs and, in the Advanced Simple profile, up to four B-VOPs between references (forward, backward, interpolated and direct mode, closed at every I-VOP), one or four half-sample or quarter-sample vectors per macroblock, AC and DC prediction, H.263 and MPEG quantization with the default or downloaded matrices, video packets with resynchronization markers (`videoPacketSize`), data partitioning (`dataPartitioning`) and reversible codes (`reversibleVlc`), a fixed quantizer or bitrate rate control (`setTargetBitrate` while running), key frames on request, the profile and level, the color description and the sample aspect ratio (`sampleAspectRatio`, a table code or an extended pixel aspect ratio) signaled; the headers in the codec configuration and the first packet; reconstruction equal to `Mpeg4Decoder`'s decode, sample for sample; written to MP4 (`mp4v`), 3GP (`Mp4MuxerOptions.threeGpp()`), AVI, Matroska, MPEG-TS and raw `.m4v`
- **Artifact:** `smalt-mpeg`
- **Package:** `video.mpeg4`
- **Source:** spec (ISO/IEC 14496-2); inverse DCT from libjpeg-turbo (`jidctint`), forward DCT from libjpeg-turbo (`jfdctint`); the object tools ported from the ISO/IEC 14496-5 reference decoder (Microsoft)
- **Patents:** **patent pool** (Via LA MPEG-4 Visual); most listed patents have expired, check the current list

</details>

<details>
<summary><b>H.263, H.263+, H.263++</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** baseline and every optional coding mode: C (continuous presence multipoint, sub-bitstreams as `VideoLayer` views), D (unrestricted vectors, reversible VLC), E (syntax-based arithmetic coding), F (advanced prediction with overlapped compensation), G (PB-frames), I (advanced intra coding), J (deblocking filter), K (slices, arbitrary order and rectangular), M (improved PB-frames), N (reference picture selection, video redundancy coding), O (B-, EI- and EP-pictures: temporal, SNR and spatial scalability, the highest layer of each instant), P (reference picture resampling, implicit and warped), Q (reduced-resolution update), R (independent segment decoding), S (alternative inter VLC), T (modified quantization), U (enhanced reference picture selection, two-picture backward prediction) and V (data partitioned slices; corrupt coefficient data keeps the header and vector partitions), custom picture formats and clock frequencies; 3GP `s263`, Matroska `V_MS/VFW/FOURCC` (`H263`), raw `.263` through `Mpeg4ElementaryStream` (the track declaring the pixel aspect ratio of the first picture header, 12:11 for the standard formats)
- **Encode:** baseline for the five standard source formats up to 30 frames per second, and H.263 version 2 with the extended picture type, custom picture formats (multiples of 4 up to 2048x1152) and custom picture clock frequencies (`H263Encoder`, `H263EncoderOptions`): I- and P-pictures with Annexes D (unrestricted vectors with the reversible vector codes), F (advanced prediction: four vectors, overlapped compensation), I (advanced intra coding), J (deblocking filter), K (slices in sequential order, `sliceSize`) and T (modified quantization), so profiles 0 to 3 of Annex X, group of blocks headers (`groupOfBlocksHeaders`), the pixel aspect ratio of a custom picture format (`sampleAspectRatio`, which moves a standard format with any ratio but its implied 12:11 to a custom one), a fixed quantizer or bitrate rate control, key frames on request; reconstruction equal to `H263Decoder`'s decode, sample for sample; written to 3GP `s263` (`Mp4MuxerOptions.threeGpp()`), MP4, AVI, Matroska and raw `.263`
- **Artifact:** `smalt-mpeg`
- **Package:** `video.h263` (decoder and encoder shared with `video.mpeg4`)
- **Source:** spec (ITU-T H.263 01/2005); forward DCT from libjpeg-turbo (`jfdctint`)
- **Patents:** none in force known (standard of 1996-2000)

</details>

<details>
<summary><b>Sorenson Spark (FLV1, Sorenson H.263)</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** versions 0 and 1 (the 7 and 11-bit coefficient escapes): I-, P- and disposable P-pictures (handed out at once, never predicted from), the standard sizes and custom sizes of 8 and 16 bits, baseline macroblocks without groups of blocks, vectors of the baseline range that may point outside the picture, four vectors per macroblock without overlapped compensation; the header's deblocking flag (a post-filter hint) is not applied; from FLV, AVI (`FLV1`) and Matroska `V_MS/VFW/FOURCC`
- **Encode:** no
- **Artifact:** `smalt-mpeg`
- **Package:** `video.h263` (`H263Decoder`)
- **Source:** spec (SWF File Format Specification 19, Sorenson H.263 bitstream format; ITU-T H.263 for the macroblock layer)
- **Patents:** none in force known

</details>

<details>
<summary><b>MPEG-1 and MPEG-2 video (H.262)</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** MPEG-1 (ISO/IEC 11172-2: I-, P-, B- and D-pictures, whole sample vectors, slices across rows) and every non-scalable MPEG-2 profile (Simple, Main, High, 4:2:2; ITU-T H.262): frame and field pictures (I-P field pairs and second fields predicting from the first), frame, field, 16x8 and dual-prime motion compensation, frame and field DCT, 4:2:0, 4:2:2 and 4:4:4, intra DC precisions of 8 to 11 bits, default and downloaded quantizer matrices (chroma matrices of the quant matrix extension included), linear and non-linear quantizer scales, zigzag and alternate scans, both intra VLC tables, skipped macroblocks, concealment motion vectors and slice vertical position extensions; samples bit-exact with the ISO/IEC 13818-5 reference decoder given the same inverse DCT (the IEEE 1180 integer transform of libjpeg's `jidctint`, as `ffmpeg -idct int` chooses it); field pictures woven into frames, frames in display order with their scan and repeated fields (`progressive_frame`, `top_field_first`, `repeat_first_field`) in `VideoFrame.sideData()` and durations that count the fields shown, ATSC closed captions attached; corrupt slices concealed from the previous reference frame; macroblock rows of MPEG-2 pictures in parallel; transport streams (`stream_type` 1 and 2), program streams, MPEG-1 system streams and DVD-Video objects, MP4 (object types 0x60 to 0x65 and 0x6A), QuickTime (`m1v `, `m2v1`, HDV, XDCAM, IMX and Avid sample entries), Matroska (`V_MPEG1`, `V_MPEG2`) and AVI (`mpg1`, `mpg2` and the MPEG-2 FourCCs of capture cards and camcorders); the scalable extensions raise `MediaException.Unsupported`
- **Encode:** none
- **Artifact:** `smalt-mpeg`
- **Package:** `video.mpeg2`
- **Source:** spec (ISO/IEC 11172-2, ITU-T H.262 / ISO/IEC 13818-2); variable length code tables from the ISO/IEC 13818-5 reference software (MPEG Software Simulation Group); inverse DCT from libjpeg-turbo (`jidctint`)
- **Patents:** **patent pool** (MPEG LA MPEG-2, now Via LA); every listed patent has expired

</details>

<details>
<summary><b>VC-1 (SMPTE ST 421, WMV3, WVC1)</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** the simple, main and advanced profiles at every level: progressive I, P, B, BI and skipped pictures, 1-MV and 4-MV macroblocks with hybrid prediction, quarter and half sample bicubic and bilinear compensation, intensity compensation, direct and interpolated B macroblocks, every bitplane mode, DC and AC prediction, the uniform and non-uniform quantizers with every macroblock quantizer profile, the 8x8, 8x4, 4x8 and 4x4 transforms, overlap smoothing, the in-loop filter, slices (with repeated picture headers), sync markers, range reduction and range mapping, multi-resolution frames; interlace field pictures (any field type pair, one and two reference fields, per-field intensity compensation, B fields) and interlace frame pictures (frame and field transforms, 1-MV, 2 field MV, 4 frame MV and 4 field MV macroblocks, field-based loop filtering) mixed freely; samples bit-exact with ffmpeg on the SMPTE conformance streams of FATE, and the I and P frames of Windows Media Encoder files with Microsoft's Media Foundation decoder; frames in display order with their field order and repeated fields; Matroska and AVI (`WMV3`, `WVC1`, `WMVA`), MP4 (`vc-1` with `dvc1`) and transport streams (`stream_type` 0xEA, Blu-ray), ASF (`.wmv`: `WMV3` with its `STRUCT_C`, `WVC1`, `WMVA`)
- **Encode:** none
- **Artifact:** `smalt-mpeg`
- **Package:** `video.vc1`
- **Source:** spec (SMPTE ST 421:2013)
- **Patents:** patent pool (Via LA VC-1)

</details>

<details>
<summary><b>JPEG XS</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** every codestream the image decoder reads (`JpegXsVideoDecoder`, also through `VideoDecoders`), a JXS file or a raw codestream per frame (temporal differential coding is rejected, see Known limits); interlaced frames of two field codestreams woven in the track's field order; MPEG-TS `stream_type` `0x32`, MP4 `jxsm`, MXF (SMPTE ST 2124), Matroska `V_QUICKTIME` with a `jxsm` sample description
- **Encode:** `JpegXsVideoEncoder` (also through `VideoEncoders`) over `JpegXsEncoder`: 4:2:2 Y'CbCr at 8 or 10 bits (Main 422.10) or 4:4:4 at 12 bits (Main 444.12) at the rate the bitrate gives per frame; the track configuration is the `jpvi` and `jxpl` boxes
- **Artifact:** `smalt-mpeg`
- **Package:** `image.jxs`
- **Source:** as JPEG XS; ISO/IEC 21122-3 Annex B, Rec. ITU-T H.222.0 Annex W, VSF TR-07, SMPTE ST 2124
- **Patents:** as JPEG XS

</details>

### Images

<details>
<summary><b>HEIC</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** primary item (`hvc1` or `avc1` item, possibly predicted from others with `pred`, an intra-coded HEVC item that is not a random access point included; `lhv1` layered HEVC item (SHVC or MV-HEVC, `lhvC` configuration, target output layer set `tols`, layer selector `lsel`, an AVC-coded base item through `exbl`), shown in place of the primary item when its alternatives group (`altr`) prefers it and it can be reconstructed; `jpeg` item with its `jpgC` header (ISO/IEC 23008-12 Annex H) and `j2k1` JPEG 2000 item with its `j2kH` channel definitions, palettes and opacity (ISO/IEC 15444-16; signed samples offset into the unsigned range, samples of 17 to 38 bits as a `ChannelFloatPicture`, CMYK and YCCK items as CMYK inks, both kept as channel pictures with `keepChannels` and converted to RGBA otherwise), both when `smalt-image` is present; `unci` uncompressed item (ISO/IEC 23001-17 and its Amendment 2: every interleaving, 4:4:4/4:2:2/4:2:0/4:1:1, unsigned and signed integer components of 1 to 256 bits, IEEE 754 binary16 to binary256 floating-point and complex components, monochrome, Y'CbCr, RGB, alpha, CMYK and filter array (`cpat`) components, palettes (`cpal`), bit-packed, byte-aligned or in blocks of any size, pixel, row and tile padding, tiles, version 1 profiles, `defl`/`zlib`/`brot` generic compression of the whole item or of the units an `icef` property indexes); `grid` with tiles decoded in parallel; `iden`; `iovl` composed to RGBA, at 16 bits when `highBitDepth` keeps a deeper input, nested up to 8 deep), alpha (`auxid:1` or CICP alpha URN: item, grid or per tile; premultiplied; limited range expanded; of another size than the color, resampled with the triangle filter, or another depth, rescaled), grids of RGBA inputs (overlays, premultiplied images) composed to RGBA, clean aperture (applied), rotation and mirroring (reported as the orientation), `nclx` color over the stream's VUI with the MIAF defaults, ICC as `colorProfile`, `clli`/`mdcv` or SEI, EXIF and XMP (`mime` items, `deflate` and `gzip` content encodings), every HEVC format of the range extensions (8 to 16-bit, 4:0:0, 4:2:0, 4:2:2 such as camera `.hif` files, 4:4:4 also as separate color planes, luma and chroma of different depths; `highBitDepth` keeps the depth) and the screen content coding tools, thumbnails (`thmb`) under a reduced-size hint, either view of the stereo pair (`ster`) the primary item belongs to (the eyes of a spatial photo, the views of a multiview item) through `HeicDecoder.decodeStereoView`, image sequences (`msf1`, HEVC or AVC tracks, with alpha (each alpha sample paired with the color sample of the same index, whatever the timing of the two tracks) and reordering, cropped by the clean aperture of their sample entry) as animations that seek through their sync samples, 16-bit RGBA frames with `highBitDepth`; gain maps (`tmap` derived image items with ISO 21496-1 metadata, and Apple's `urn:com:apple:photo:2020:aux:hdrgainmap` auxiliary images converted to ISO 21496-1 with the headroom of the Apple maker note, `HeicDecoder.decodeGainMap`); auxiliary images (depth maps, portrait and semantic mattes, alpha, thumbnails, gain maps) listed and decoded (`auxiliaryImages`, `decodeAuxiliaryImage`); items stored in other files (`dinf`/`dref` of the `meta` box) through `ImageDecoderOptions.dataReferences`; collections: every master image as a page (`HeicDecoder.openPages`, `Images.openPages`; hidden items, thumbnails, auxiliary images, tone maps and the inputs of derived images left out, each `altr` group one page) with the other entity groups as `PageReader.groups` (`ster` left view then right view, `brst`, `tsyn`, `albc` and any other type), every Nokia collection, burst, time-synchronized, album and stereo conformance file; AV1-coded images, derived-image inputs and alpha images decoded by the AVIF decoder when `smalt-video` is present, so a file with only the generic brands whose image is AV1-coded decodes exactly as under the `avif` brand (and reports `AVIF`)
- **Encode:** still images and image sequences (`HeicEncoder`, `Images.encode`, `Images.openAnimationWriter`, ImageIO): `hvc1` items of the Main Still Picture (8-bit) and Main 10 Still Picture (10-bit) profiles at a constant quality 0-100 (the HEVC quantizer `(100 - quality) * 51 / 100`) and the four HEVC encoder speeds, presets `ofQuality`, `forArchive` and `forLossless`, 4:2:0 (other layouts resampled, RGB converted with the options' color description), lossless images (`lossless`: RGB as 4:4:4 with the identity matrix, planar pictures in their own 4:2:0, 4:2:2 or 4:4:4 format, alpha lossless too, in the Main 4:4:4 Still Picture or Main 4:4:4 10 profile where the format is not 4:2:0), odd sizes cropped by an essential `clap`, image grids of hidden tiles coded in parallel (automatic 512-sample tiles above the HEVC level 5.1 picture size, or any even tile side from 64 to 5970, the largest HEVC level 6.2 codes), alpha auxiliary items or grids (`urn:mpeg:hevc:2015:auxid:1`, premultiplied on request), `nclx`/ICC color, `irot`/`imir`, `clli`/`mdcv`, Exif and XMP, the `heic` or `heix` brand with `mif1` and `miaf`; `msf1` image sequences of the Main and Main 10 profiles with P and B pictures (`interFrames`, on by default; composition offsets, sync samples, an edit list with the loop count) or independent frames, an alpha track (premultiplied on request, with a `prem` track reference), the color boxes of the primary item (`nclx`/ICC, `clli`/`mdcv`) and, for odd canvases, a `clap` in the sample entry, sample durations equal to the frame durations, the first frame as the primary item (so `heic`/`heix` is the major brand) and the `msf1` and `hevc`/`hevx` brands; lossless sequences (4:4:4 identity-matrix color frames of the Main 4:4:4 or Main 4:4:4 10 profile)
- **Artifact:** `smalt-mpeg` (HEIF parser and uncompressed items in `smalt-core`)
- **Package:** `image.heic`, `internal.heif`
- **Source:** spec (ISO/IEC 23008-12, MIAF ISO/IEC 23000-22, ISO/IEC 23001-17, ISO/IEC 15444-16, Apple's "Applying Apple HDR effect to your photos"); HEVC payloads through `video.hevc`, AVC payloads through `video.h264`, JPEG and JPEG 2000 payloads through `smalt-image`; `brot` generic compression through the Brotli decoder ported from the reference Java decoder; the writer shares the AVIF writer's box layout (libavif) and codes with `video.hevc.encoder`
- **Patents:** **patent pools** (HEVC: Access Advance, Via LA; AVC: Via LA); not covered by the MIT license

</details>

<details>
<summary><b>JPEG XS</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** ISO/IEC 21122-1 codestreams and JXS files (ISO/IEC 21122-3: `colr` enumerated, ICC and CICP) of every ISO/IEC 21122-2 profile but the TDC ones: 1 to 8 components of 8 to 16 bits (above 8 rounded to 8 unless `highBitDepth`), 4:4:4, 4:2:2, 4:2:0 and 4:0:0 Y'CbCr, RGB through the reversible color transform, alpha, the Bayer profiles through the Star-Tetrix transform (`Cpih` 3) as a gray mosaic or as components (`JpegXsDecoder.decodeImage`), the linear, quadratic and extended nonlinear transforms, every entropy mode (vertical prediction, significance coding, raw packets, separate sign coding, both quantizers, MLS); bit-exact on every single-frame ISO/IEC 21122-4 ed. 3 conformance stream; temporal differential coding (TDC) is rejected as unsupported (see Known limits)
- **Encode:** `JpegXsEncoder`: the main, high, light, MLS and Bayer profiles (automatic from the picture, or chosen), lossless or at a rate in bits per pixel with a per-precinct rate allocation, 1 to 8 components of 8 to 16 bits, 4:4:4, 4:2:2, 4:2:0, 4:0:0, RGB through the color transform, alpha, Bayer mosaics (`JpegXsEncoder.bayer`), 1 to 8 horizontal and 0 to 2 vertical decomposition levels, slice height, precinct width, deadzone or uniform quantizer; JXS file or raw codestream (`JpegXsEncoder`, `Images.encode`, ImageIO `jxs`)
- **Artifact:** `smalt-mpeg`
- **Package:** `image.jxs`
- **Source:** ISO/IEC 21122-1, -2, -3 (codec and file format); the precinct layout, packet order, entropy decoding and weight tables after SVT-JPEG-XS (BSD-2-Clause-Patent); the ISO/IEC 21122-5 reference software as a black-box oracle only
- **Patents:** **patent pool** (Vectis JPEG XS); not covered by the MIT license

</details>

### Audio codecs

<details>
<summary><b>AAC (Main, LC, SSR, LTP, Scalable, ER LC, ER LTP, ER Scalable, ER BSAC, LD, ELD), HE-AAC, HE-AAC v2, MPEG Surround, USAC</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes: LC; Main with backward-adaptive prediction; SSR (four-band PQF filterbank with gain control); LTP and ER AAC LTP with long term prediction; ER AAC LC; the error resilience tools of the ER object types (virtual codebooks, RVLC scalefactors, Huffman codeword reordering), with corrupt RVLC and reordered spectral data concealed, and every `epConfig`: 0, 1 (separate category instances through `AacDecoder.decode(List, Consumer)`), 2 and 3 (the error protection tool: CRCs, SRCPC codes decoded by Viterbi, shortened Reed-Solomon codes, interleaving, protected headers, concatenated frames, with the frames it cannot correct concealed); AAC-LD (512- and 480-sample frames, low overlap window, long term prediction); AAC-ELD (low delay filterbank, `ELDSpecificConfig`, low delay SBR at dual rate and downsampled, from 512- and 480-sample frames); HE-AAC with spectral band replication in the high quality mode (dual-rate and downsampled, 1024- and 960-sample frames, explicit, backward-compatible and implicit signaling); HE-AAC v2 with full parametric stereo (10, 20 and 34 stereo bands with the 34-band hybrid analysis, IPD/OPD phase synthesis, mixing procedures A and B); dynamic range control from `dynamic_range_info` fill elements (per-band cut and boost scales, program reference level normalization to a target level) and the DVB heavy compression of ETSI TS 101 154; every channel configuration, 22.2 (configuration 13) included, and program config elements with their height extension (ISO/IEC 14496-3 Amd. 4); ER BSAC with every layer up to `AacDecoderOptions.maxLayer()`, the multichannel extension elements and its SBR payloads, from one access unit or the elementary streams of its layers through `AacDecoder.decode(List, Consumer)`; scalable AAC and ER scalable AAC (mono, stereo, and mono under stereo layers joined by the frequency selective switch, with long term prediction, M/S and intensity stereo, noise substitution, TNS and the error resilience tools of each layer, every `epConfig`), over AAC layers or over a CELP or ER CELP core (the up-sampling filter tool with the `coreCoderDelay`, core and AAC frames that need not align, the TNS analysis of the core spectrum and the `diff_control` switching under mono and stereo layers), every layer up to `AacDecoderOptions.maxLayer()` from the tracks of its layers (the core's first, its own layers at the CELP rate) through `AacDecoder(List, AacDecoderOptions)` and `AacDecoder.decode(List, Consumer)`; MPEG Surround (ISO/IEC 23003-1, object type 30: every tree from 5-1-5 to 7-5-7, the arbitrary tree extension, residual coding, arbitrary downmix gains and residuals, temporal shaping and guided envelope shaping, spatial frames of one or several blocks or a whole fraction of one, embedded in fill elements or in a separate elementary stream through `AacDecoder(List, AacDecoderOptions)`, `AacDecoderOptions.mpegSurround()`); USAC (MPEG-D, ISO/IEC 23003-3) with the frequency domain coder (time-warped MDCT included) and the linear prediction domain (ACELP, TCX) coder, complex prediction stereo, eSBR (QMF transposer, PVC, inter-TES), MPEG Surround 2-1-2, AudioPreRoll and MPEG-D DRC (ISO/IEC 23003-4: DRC set selection by effect and target loudness, gain decoding with interpolation, multiband filter banks, shape filters, parametric DRC, equalizer, loudness normalization and the peak limiter, `AacDecoderOptions.drcEffect()`), with lost and corrupt access units concealed
- **Encode:** AAC-LC (CBR and VBR): every channel configuration (1 to 7 and 11 to 14, 22.2 included) and, through a program config element, the other layouts of front, side, back and LFE loudspeakers and of the top and bottom loudspeakers its height extension places (5.1.2 on a side pair, 7.1.4), channels in any input order; 1024- and 960-sample frames. HE-AAC (`AacProfile.HE`: spectral band replication for every single channel and channel pair element, the time grid following transients, inverse filtering, noise floors, synthetic sinusoids, coupled stereo coding; output rates of 16 to 96 kHz, every layout the core codes, 1024- and 960-sample frames) and HE-AAC v2 (`AacProfile.HE_V2`: parametric stereo with 20 or 10 stereo bands), signaled implicitly, backward compatibly or hierarchically (`AacSignaling`); AAC-LD and AAC-ELD (`AacProfile.LD`, `AacProfile.ELD`: 512- and 480-sample frames, channel configurations 1 to 7, the AAC-ELD low delay filterbank; real-time `AudioEncoders` options select AAC-ELD); AAC-ELD with low delay SBR (`AacLowDelaySbr`: dual rate and downsampled, the complex low delay filterbank, the frame-locked `LD_TRAN` grid, the `ld_sbr_header` in the `ELDSpecificConfig`)
- **Artifact:** `smalt-mpeg`
- **Package:** `audio.aac`
- **Source:** decoder: spec (ISO/IEC 14496-3, ISO/IEC 13818-7 for AAC Main prediction, ETSI TS 101 154 for heavy compression); the program config element height extension: the published syntax of US patent 9,542,952, its sync word and CRC checked against the ISO/IEC 14496-26 streams that carry it and a libfdk-aac binary as a black box; SBR and PS: spec, with tables and the baseline PS structure from the PacketVideo AAC decoder (Apache-2.0); HCR, RVLC, the low delay tables, the ELD window and the low delay SBR prototype from libxaac (Apache-2.0); USAC, its eSBR, MPEG Surround (ISO/IEC 23003-1 and 2-1-2), ER BSAC and scalable AAC: the ISO/IEC 23003-3 reference software; the error protection tool: spec, with the bit conventions of the ISO/IEC 14496-5 epTool; the USAC LPD coder and USAC error concealment from libxaac (Apache-2.0); MPEG-D DRC: the ISO/IEC 23003-4 reference software; encoder: VisualOn AAC encoder (3GPP TS 26.403, Apache-2.0); SBR and PS encoder: spec, with the tuning tables and the tonality, inverse filtering, noise floor, frame splitting and AAC-ELD transient estimation of libxaac's encoder (Apache-2.0)
- **Patents:** **patent pool** (Via LA AAC); not covered by the MIT license

</details>

<details>
<summary><b>AC-3 (Dolby Digital)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes: every channel mode with LFE, coupling, rematrixing, delta bit allocation, block switching, the half and quarter rate streams (`bsid` 9 and 10), the Annex D alternate bit stream syntax; dynamic range control (off, `dynrng` with cut and boost scales, `compr` heavy compression), dialogue normalization, mono, Lo/Ro and Lt/Rt downmix with the stream's mix levels; frames failing their CRC are concealed
- **Encode:** yes: every channel mode with LFE, 32, 44.1 and 48 kHz, 32 to 640 kbit/s constant bit rate, block switching, channel coupling with phase flags below 80 kbit/s per channel, rematrixing, exponent strategies chosen by cost over each frame, bit allocation searched to fill each frame; a faster search for real time (`forRealtime()`); the metadata of `Ac3Metadata` (type of service, center and surround mix levels, Dolby Surround mode, production information, copyright and original flags, the Annex D alternate syntax with the preferred downmix, the Lt/Rt and Lo/Ro levels, the Dolby Surround EX and Dolby Headphone modes and the converter type) and dynamic range words (`dynrng`, `compr`) computed from Dolby's film, music and speech profiles
- **Artifact:** `smalt-mpeg`
- **Package:** `audio.ac3`
- **Source:** spec (ATSC A/52, ETSI TS 102 366)
- **Patents:** patents expired (2017)

</details>

<details>
<summary><b>E-AC-3 (Dolby Digital Plus)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes: independent and dependent substreams (7.1 and other custom channel maps, AC-3 cores extended by an E-AC-3 dependent substream), 1 to 6 blocks per frame, reduced sampling rates, spectral extension, enhanced coupling, adaptive hybrid transform with gain-adaptive quantization, transient pre-noise processing (optionally with the output held back 512 samples, as Dolby's decoder holds its own, so that it corrects every sample); the Dolby Atmos (JOC) object audio is reported and its channel bed decoded
- **Encode:** yes: one independent substream for mono to 5.1, and every program beyond 5.1 whose channels have custom channel map locations (7.0, 7.1, 6.1, 5.1.2, 7.1.2, 7.1.4, 9.1, 11.1 and the like) as a 5.1 core plus dependent substreams with custom channel maps, one whenever it holds the extra channels; 32, 44.1 and 48 kHz with 1, 2, 3 or 6 blocks per frame and 16, 22.05 and 24 kHz with 6, any constant bit rate up to frames of 2048 words per substream (1024 kbit/s at 48 kHz with 6 blocks, 6144 kbit/s with 1), the AC-3 tools plus frame-based exponent strategies and spectral extension (from 44 to 92 kbit/s per channel); the adaptive hybrid transform, transient pre-noise processing (frames held until the frames their data concerns are coded) and enhanced coupling on request; the metadata of `Ac3Metadata`, with the mixing and informational metadata of the independent substream, and dynamic range words
- **Artifact:** `smalt-mpeg`
- **Package:** `audio.ac3`
- **Source:** spec (ATSC A/52 Annex E, ETSI TS 102 366, ETSI TS 103 420)
- **Patents:** **may be covered by Dolby patents**; not covered by the MIT license

</details>

<details>
<summary><b>DTS (Coherent Acoustics, DTS-HD)</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** yes: the core (1 to 5.1 channels, 8 to 48 kHz, every coding tool: Huffman, block and plain codes, ADPCM prediction, high frequency vector quantization, transients, joint intensity, sum/difference, perfect and non-perfect synthesis, LFE), the XCh (6.1), XXCH (up to 7.1 and beyond, embedded downmixes undone) and X96 (96 kHz) extensions of the core frame and of the extension substream, XBR (DTS-HD High Resolution) and XLL (DTS-HD Master Audio, lossless up to 96 kHz, bit for bit, without a core or over a core at its rate or twice it, its channels added to the fixed-point core with the downmixes of hierarchical channel sets undone; see Known limits for the other cases); the 14-bit and little-endian packings of DTS audio CDs; dialogue normalization on request (core and asset gains); DTS Express (LBR), XLL above 96 kHz and termination frames with partial subsubframes are not decoded (see Known limits)
- **Encode:** no
- **Artifact:** `smalt-mpeg`
- **Package:** `audio.dts`
- **Source:** spec (ETSI TS 102 114 V1.6.1); measured from ffmpeg's decoder as a black box: the vector code books of D.10, which the specification does not print, the perfect reconstruction prototype (D.8 prints another), the output gains of the synthesis banks, the XXCH downmix code tables and the fixed-point core reconstruction and downmix reversal XLL residuals depend on (against ffmpeg's C code, `-cpuflags 0`)
- **Patents:** core patents expired; the DTS-HD extensions may be covered by DTS (Xperi) patents

</details>

<details>
<summary><b>AMR-NB, AMR-WB</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes, bit-exact with the 3GPP fixed-point decoders (TS 26.073, TS 26.173): every mode, mode changes on any frame, comfort noise (SID) and DTX, bad and lost frame concealment, homing; storage-format frames from `.amr`, MP4/3GP `samr`/`sawb` and Matroska (`A_AMR/NB`, `A_AMR/WB`, `A_MS/ACM`), and the frame-blocks of multichannel `.amr` files (one decoder state per channel); the TS 26.074 sequences pass in linear PCM and through G.711 A-law and mu-law
- **Encode:** yes, bit-exact with the 3GPP encoders: AMR-NB in every mode with DTX and VAD option 1 or 2, AMR-WB in every mode with VAD and DTX; mode changes on any frame
- **Artifact:** `smalt-mpeg`
- **Package:** `audio.amr`
- **Source:** AMR-NB decoder and encoder and AMR-WB decoder: opencore-amr (PacketVideo, Apache-2.0); AMR-WB encoder: vo-amrwbenc (VisualOn, Apache-2.0), its arithmetic aligned with TS 26.173 (passes TS 26.174, so its frames differ from `libvo_amrwbenc` on loud or unusual input); both derived from the 3GPP reference code
- **Patents:** **patent pool** (VoiceAge, AMR-WB); AMR-NB patents are held by its developers; not covered by the MIT license

</details>

<details>
<summary><b>MPEG-4 CELP, ER CELP</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** yes (`CelpDecoder`): the multi-pulse coder at 8 and 16 kHz and the regular pulse coder at 16 kHz, every `MPE_Configuration` and `RPE_Configuration`, fine rate control, up to three bitrate scalable layers, the bandwidth scalable layer that extends 8 kHz speech to 16 kHz, the silence compression of ER CELP with its comfort noise, the error sensitivity categories of ER CELP in one stream or in separate streams (`epConfig` 1) and the error protection tool (`epConfig` 2 and 3); the layers and categories of an object decode together through `CelpDecoder.decode(List, Consumer)`, up to `CelpDecoderOptions.maxLayer()`; the informative post-processor (on by default); MP4 `mp4a` tracks and LOAS/LATM (read and written); the core layer of scalable AAC through `AacDecoder`; the ISO/IEC 14496-26 `ce` and `er_ce` streams match their reference waveforms at every layer and `epConfig` to the full accuracy of ISO/IEC 11172-4
- **Encode:** no
- **Artifact:** `smalt-mpeg`
- **Package:** `audio.celp`
- **Source:** spec (ISO/IEC 14496-3 subpart 3), its undefined details derived from the ISO/IEC 14496-26 references; the comfort noise constants and the bandwidth extension LSP prediction through non-active frames from the CELP decoder of the ISO/IEC 14496-3 reference software (as distributed with the ISO/IEC 23003-3 reference software)
- **Patents:** held by its developers; no licensing pool

</details>

<details>
<summary><b>AC-3 and E-AC-3: where the decoder follows Dolby's encoders</b></summary>

Where the AC-3 and E-AC-3 specifications leave room or disagree, the decoder follows what Dolby's encoders
write and Dolby's own decoder (the Dolby Digital Plus decoder of Windows Media Foundation, driven as a black
box) reads:

- The vectors of high-efficiency pointer 4 (ETSI TS 102 366 table E.3.4, A/52 table E4.4) are the table's
  rows in the order every edition prints them; ffmpeg 7.1 reads them shifted by one row, so it decodes those
  bins wrong in Dolby's streams and in Smalt's. That is why the encoder's adaptive hybrid transform is
  opt-in.
- Enhanced coupling carries 6 + 3 bits per band wherever its parameters flag is set, as A/52 Annex E and
  ETSI V1.2.1 define it, which is how Dolby's encoder writes it and Dolby's decoder reads it; the angle,
  chaos, interpolation and transient fields are ignored, as later ETSI editions specify.
- Transient pre-noise processing places the transient 256 samples after the location the frame carries
  (`4 * transprocloc` counts from the frame's first input sample), applies only locations from 1024 to 2559, and
  cross-fades with the windows Dolby's decoder uses, which the specification leaves to the implementation: the
  decodes of the Audition streams of ffmpeg ticket 10958 match Dolby's to 87 dB (median per frame) below the
  extension band. A correction may start up to 511 samples before the frame that completes its synthesis
  buffer. Dolby's decoder holds its output back, so it corrects those samples too; Smalt's decoder does when
  `Ac3DecoderOptions.preNoiseHoldback()` is set, emitting its output 512 samples later (`Ac3Decoder.delay()`),
  and otherwise outputs each frame as it decodes it and leaves those samples (a few frames per minute of those
  streams) as decoded. The holdback is off by default because `AudioStreamDecoder` trims and discards output per
  packet, which a held back output no longer lines up with.

</details>

## Known limits

> [!WARNING]
> These are the cases where a codec of this artifact rejects a stream, leaves a feature out or differs from
> another implementation. Open the entry of the codec you work with.

### H.264, HEVC and VVC

<details>
<summary><b>H.264 decoding</b></summary>

H.264 decoding covers 4:0:0, 4:2:0, 4:2:2 and 4:4:4 video of 8 to 14 bits, lossless or not, progressive and
interlaced, and the Extended profile's SP, switching SP and SI slices and slice data partitioning. Slice data
partitions of a CABAC picture parameter set, which no profile allows, raise `MediaException.Unsupported`; a lost
partition B or C leaves its macroblocks without residual (I_PCM ones mid-gray) and damages the picture. Redundant
coded pictures are skipped, never used in place of lost primary data. Interlaced streams come out as woven
frames, and a frame whose second field never arrives is output with that field mid-gray. 4:4:4 streams with
matrix_coefficients 0 come out in their coded order (G, B, R in the Y, Cb, Cr planes), as `ChromaFormat.YUV444`
with the identity matrix in the color description.
H.264 multiview decoding covers the Multiview High, Stereo High, MFC High, Multiview Depth High, MFC Depth High
and Enhanced Multiview Depth High (3D-AVC, ITU-T H.264 Annex I) profiles. The 3D-AVC syntax and texture coding
tools follow the ITU-T H.264.1 conformance bitstreams and the 3DV-ATM 14.0 reference decoder where they depart
from the text of Annex I (the NAL unit header carries the `view_id`, the subset sequence parameter set layout,
the closing slice header fields, one disparity per 8x8 block for view synthesis prediction, templates of one
sample for adaptive luminance compensation, `VspRefExist` set only by slices with `slice_vsp_flag`, and in CABAC
slices `mb_direct_type_flag` whatever `direct_spatial_mv_pred_flag` is and `bvsp_flag_lX` whether or not
`ref_idx_lX` is present), and the tool flags of the subset sequence parameter set parsed last apply to every
slice, as in 3DV-ATM. The texture coding tools of CAVLC slices, which 3DV-ATM codes with CABAC only, follow the
CAVLC syntax of Annex I (`mb_skip_type_flag` per skip run, `mb_direct_type_flag` only with spatial direct
prediction, `bvsp_flag_lX` only after a `ref_idx_lX` that is present); textures may be 4:2:0 or 4:0:0. Texture
views whose slices use the texture coding tools under a `profile_idc` 139 sequence parameter set the profile
forbids (`frame_mbs_only_flag` 0, samples above 8 bits, `chroma_format_idc` above 1) raise
`MediaException.Malformed` once and are not decoded, while the other views keep decoding. Depth-range-based
weighted prediction of depth views follows 3DV-ATM, which gives the depth pictures of the previous picture order
count the depth ranges of a newly selected depth parameter set. H.264 scalable decoding
covers 4:2:0 8-bit layers, progressive or interlaced, decoded on the calling thread. Where the text of ITU-T
H.264 F.8.6.2.3 and F.8.6.3.3 adds `botFieldFlag` to the row of a field macroblock or field picture in
inter-layer resampling, the decoder follows the JSVM reference decoder and the sample geometry, which leave it
out. The Scalable Baseline layers apply the
deblocking rule of ITU-T H.264 F.8.7.4.3 for macroblocks whose transform coefficients come from the reference
layer, as the JSVM 9.19.15 reference decoder does, so a few samples of some Scalable Baseline spatial layers
differ by small amounts from the reference decodings of the conformance suite, which predate that rule. A
target layer above the base layer takes effect at the first IDR picture after the subset sequence parameter set
that declares the layers. A multiview access unit whose base view component is lost outputs none of its views.
The H.264 encoder has no weighted prediction, B partitions or adaptive B-frame count, and its B pyramid
is capped by the level's picture buffer. Streams that are not 8-bit, 4:2:0, progressive and lossy are coded by a
second engine on the calling thread with one motion vector per macroblock and list, B pictures that are never
referenced (no B pyramid, so at most three in a row) with spatial direct prediction only, no look-ahead
analysis (so no macroblock tree) and no texture retention in mode decision, and decide field or frame coding
of a macroblock pair from the source rows alone. An 8-bit, 4:2:0, progressive, lossy CAVLC stream has no 8x8
transform. A two-pass encode lands near the target size (within 2% on
the test clips) when both passes see the same frames with the same options; its first pass codes the video once at the
fastest analysis. The hypothetical reference decoder runs at the nominal frame rate, whatever the frame
timestamps say, with one schedule and the initial removal delay at nine tenths of the buffer; its parameters
cannot follow `setTargetBitrate`.

</details>

<details>
<summary><b>HEVC encoder</b></summary>

The HEVC encoder codes 4:2:0, 4:2:2 and 4:4:4 pictures at 8 or 10 bits (the Main, Main 10, still picture, Main
4:2:2 10, Main 4:4:4, Main 4:4:4 10 and Main 4:4:4 Still Picture profiles; no monochrome, 12-bit or screen content
profile), one slice of wavefront substreams per picture without tiles, and progressive frames only. It uses none
of the range extension coding tools (residual DPCM, cross-component prediction, transform skip extensions), which
would compress lossless and 4:4:4 content further; a lossless stream bypasses the transform of every coding unit,
and its level is chosen from the picture size and rate alone, so a stream that exceeds the level's bit rate or
minimum compression ratio is not moved to a higher level. Every coding unit takes the slice quantizer (no adaptive
quantization), the quantizer rounds with a fixed dead zone and sign data hiding (no rate-distortion optimized
quantization), and it has no asymmetric partitions, weighted prediction, transform skip, scaling lists, long-term
references or adaptive B-frame count: every group of B pictures has the configured size, shorter only before an
IDR picture and at the end of the stream.

</details>

<details>
<summary><b>VVC decoder</b></summary>

The VVC decoder implements the Main 10 and Main 10 Still Picture profiles: streams of the 4:2:2 and 4:4:4 profiles (palette mode and the adaptive color transform among their tools), deeper than 10 bits or using the range extension raise `MediaException.Unsupported`, and of a multi-layer stream only the base layer is decoded. Field coded sequences come out as woven frames, paired by the frame-field information SEI (alternating parity from a top field without it), a field left without a pair with the other field mid-gray. Of the SEI messages the decoder reads the decoded picture hash, mastering display, content light level, alternative transfer characteristics, film grain, frame packing, display orientation, frame-field information, caption data, ambient viewing environment, content colour volume, sample aspect ratio information and the omnidirectional ones (equirectangular and generalized cubemap projection, sphere rotation, region-wise packing, viewport); other messages are not reported, and film grain of a model other than frequency filtering with additive blending is left out. AVI does not carry VVC: no FourCC is registered for it (ffmpeg writes a zero FourCC that no reader recognizes). The decoder runs the tiles and wavefront rows of a slice concurrently and the in-loop filters (deblocking, SAO, ALF) in bands of CTU rows, but it has no picture-level parallelism: a stream whose slices are single substreams (no tiles, no wavefront parallel processing) is parsed, predicted and reconstructed on the calling thread. The interpolation, weighted averaging, optical flow and inverse transform loops use Vector API kernels where 256-bit vectors are available; intra prediction, DMVR and the in-loop filters are scalar, so the decoder is slower than the HEVC decoder.

</details>

<details>
<summary><b>HEVC field coded sequences</b></summary>

HEVC field coded sequences come out as woven frames, two fields each, paired by the picture timing `pic_struct`
(alternating parity from a top field when the stream sends none); a field left without a pair is output with
the other field mid-gray. A track description derived from their parameter sets (MPEG-TS) gives the size
and rate of one field. A stream that starts at a picture carrying a recovery point SEI message
outputs nothing before the recovery point in output order; its first pictures predict from mid-gray stand-ins.

</details>

<details>
<summary><b>Multi-layer HEVC</b></summary>

A stream whose base layer is coded outside it (hybrid scalability, `vps_base_layer_internal_flag`
clear) decodes only as a HEIC `lhv1` item over an AVC-coded `exbl` base item; `HevcDecoder` raises
`MediaException.Unsupported` for it, since a track carries no base layer pictures. The 3D-HEVC depth intra modes
and DC coding in a depth layer with chroma samples, and depth-based block partitioning in a 4:2:2 texture layer,
which the 3D Main profile excludes and Annex I leaves unspecified, raise `MediaException.Unsupported`;
as in the reference decoder, the texture merging candidate of a depth view matches the reference of the texture
block at the center of the prediction unit by picture order count alone. An alpha layer of another size than its primary layer is
resampled with the triangle filter. The HEIF `oinf` operating points property is accepted but not used; the
output layer set comes from the item's `tols` property, and an `lhv1` item must carry its base layer or reference it
through `exbl`.

</details>

<details>
<summary><b>Film grain in H.264 and HEVC</b></summary>

The H.264 and HEVC decoders render the frequency filtering film grain model with additive blending, bit-exactly
as SMPTE RDD 5 specifies for 8-bit 4:2:0 progressive frames (ffmpeg's renderer gives the same samples).
Interlaced frames, deeper samples and the 4:0:0, 4:2:2 and 4:4:4 formats get the same grain, extended (blocks
averaged in the 8-bit domain, the intensity taken in units of the component's bit depth as the SEI semantics
define it, 4:2:2 chroma scaled by 181/256 with only its horizontal cut frequency doubled as the VVC reference
software does), a rendering no reference decoder offers to compare with; the
auto-regression model, multiplicative blending and film grain models in another color domain are ignored, as
are the low cut frequencies and color correlation of the model. Decoded picture hashes are checked on the
samples without grain.

</details>

### MPEG-4 Part 2, MPEG-1 and MPEG-2 video, VC-1

<details>
<summary><b>MPEG-4 Part 2 decoding</b></summary>

MPEG-4 Part 2 uses an IEEE 1180 inverse DCT, so it is close to, but not bit-exact with, other decoders; the
Advanced Simple conformance streams match the ISO/IEC 14496-5 reference decoder within that drift.
Interlaced direct mode follows ISO/IEC 14496-2 7.7.2.2, as the Windows Media Foundation decoder does;
ffmpeg's encoder predicts those macroblocks as progressive direct ones, so its interlaced B-VOPs drift
from what it meant (about 49 dB) wherever the backward reference has field-predicted macroblocks.
Overlapped motion compensation follows ITU-T H.263 F.3, and matches the TMN10 reference encoder; the H.263
encoder's Advanced Prediction streams without group of blocks headers decode in the TMN 3.2 decoder exactly as in
Smalt's with the same inverse DCT. ffmpeg's decoder takes some remote vectors differently, so H.263 Advanced Prediction streams differ from its
decode by up to about a decibel, and the encoder's own, which use four vectors often, drift further in it (to
about 40 dB PSNR against Smalt's decode within eight pictures). Across a group of blocks with a non-empty header,
F.3 takes the remote vectors as inside the group (in every edition of the Recommendation since 1996, and as
ffmpeg's encoder does), and so does Smalt; the TMN 3.2 decoder and Intel's Draft 20 H.263+ codec take the current
vector there, as in slices, so Advanced Prediction streams with group of blocks headers decode differently between
them and Smalt (Intel's `dfijst` test stream drifts to about 45 dB from its own decode, and the encoder's streams
with `groupOfBlocksHeaders` to about 50 dB in the TMN decoder).
H.263 frames are handed out once the next I-, P-, EI- or EP-picture is decoded (or at flush), since B-pictures
and the B-parts of PB-frames display before the picture that follows them. Improved PB-frames follow the
Recommendation and the TMN test model, predicting the B-part forward with rounding type 0 and deblocking it with
BQUANT; Intel's Draft 20 streams use the P-part's rounding type and quantizer, so their B-parts differ from
Intel's decode by up to about 6 sample values (45 dB). Sorenson Spark output follows the reconstruction of its
macroblocks and ignores the header's deblocking flag, a suggestion of a post filter that Flash Player may skip, so
it is not the deblocked picture of a player that applies one; no Sorenson Spark encoder is provided. No encoder or
conformance stream of the data partitioned slices of Annex V is available (the TMN test model and ffmpeg lack the
mode, and H.263 has no conformance suite), so they are verified on streams of Smalt's encoder written from the
Recommendation (`tools/testdata/h263-annexes`); the pictures of its B-picture and Improved PB-frame streams with
Annexes I, S and T are those of unpartitioned twins that the TMN decoder reproduces exactly, so only the partition
syntax itself rests on Smalt's reading. That reading takes three points the text leaves open the way the decoder
does: LMVV repeats the last vector of the slice's prediction thread (P-vectors, including those of INTRA
macroblocks of Improved PB-frames, and forward vectors), the start code emulation prevention of V.2.3.3 counts on
through LMVV, and MVDB and MVDBW are coded in the vector partition after the thread vector of their macroblock.

</details>

<details>
<summary><b>MPEG-4 Part 2 encoder</b></summary>

The MPEG-4 Part 2 encoder codes progressive, rectangular, 8-bit video object layers of the Simple and Advanced
Simple profiles; it does not use the tools its profiles leave optional for an encoder: interlaced coding, global
motion compensation and, in the other profiles, shapes, sprites, scalability and N-bit samples. The H.263 encoder
codes I- and P-pictures with Annexes D, F, I, J, K (sequential slices) and T; the other optional modes, which the
decoder reads, are not coded: PB-frames and Improved PB-frames, B-, EI- and EP-pictures, syntax-based arithmetic
coding, reference picture selection and resampling, reduced-resolution update, independent segments, the
alternative inter VLC, rectangular or arbitrarily ordered slices and data partitioned slices. Both rate controls
choose one quantizer per picture.

</details>

<details>
<summary><b>MPEG-4 Part 2 picture sizes that are not a multiple of 16</b></summary>

When the MPEG-4 Part 2 picture size is not a multiple of 16, the reference pictures keep the decoded samples of the
partial macroblocks and are padded from the macroblock-aligned rectangle, as ISO/IEC 14496-2:2004 7.6.4 (corrected
in its third edition) and the reference software of ISO/IEC 14496-5 Amd.5:2004 do, and as Xvid and ffmpeg do; the
reference software of 14496-5:2001 and the encoders that follow the 2001 text pad from the VOP boundary, so their
streams of such sizes drift in Smalt's decode and Smalt's in theirs.

</details>

<details>
<summary><b>MPEG-4 Part 2 Studio profiles and fine granularity scalability</b></summary>

MPEG-4 Part 2 Studio profiles and fine granularity scalability raise `MediaException.Unsupported`: the Studio
conformance streams of ISO/IEC 14496-4 follow a later syntax than the Studio reference software of ISO/IEC
14496-5 Amd 1, which no available decoder reconstructs, and the FGS reference software predates the final syntax,
without retrievable conformance streams. Temporal scalability with `enhancement_type` 1 over an arbitrarily shaped
base layer raises it too (the reference encoder makes such base layers rectangular), and still texture objects are
not decoded. The background composition of temporal and object-based spatial scalability (every temporal
prediction type), low latency sprites (rectangular and shaped) and rectangular sprites are verified on streams
of the reference encoder, decoded by its reference decoder with the fixes of
`tools/testdata/mpeg4-oracle/build.py` (which include giving the next base VOP an opaque shape as the backward
reference of a B-VOP with `ref_select_code` 2, as the other references of the base layer have); the composed
shape over an arbitrarily shaped reference layer follows that decoder, which leaves it transparent outside the
enhanced VOP. The sprite brightness change, which the reference encoder does not code, is checked on a sprite
stream rewritten to carry it.

</details>

<details>
<summary><b>VC-1</b></summary>

The decoder reads VC-1 from ASF (`.wmv`), Matroska, AVI, MP4 and transport streams. Only the 4:2:0
color-difference format, the only one SMPTE ST 421 defines, is decoded. The Windows Media Video 9 complex
profile (`WMV3` with STRUCT_C profile 2, which SMPTE ST 421 reserves) and the bitstreams of pre-release
Windows Media Video 9 encoders (STRUCT_C Reserved6 0, which Annex J forbids; no specification describes their
syntax) raise `MediaException.Unsupported`. The I and P frames of the two Windows Media Encoder main profile
files of the test set (overlap smoothing, differential quantization) are bit-exact with Microsoft's own decoder
(Media Foundation); ffmpeg smooths a few edges between intra blocks of their P pictures differently, by a few
levels that the following predicted frames carry up to the next key frame (55 dB PSNR at worst). Microsoft's
decoder pulls back B picture vectors at the picture edges differently from section 8.4.6.1 (on the SMPTE
stream SMM0005 too), and Smalt follows the standard there, as ffmpeg does. Main profile P pictures
buffer their motion vectors for B pictures
without the pull-back of SMPTE ST 421 Figure 68, and the second field of a B field picture scales its backward
predictors with Table 113, which is how the encoders of the reference streams read the standard. Multi-resolution
frames are up-sampled with a bilinear filter, which the standard leaves open. Interlace frame intensity
compensation and interlace frame overlap smoothing are implemented from the standard but appear in no stream of
the test suite.

</details>

<details>
<summary><b>MPEG-1 and MPEG-2 video</b></summary>

The inverse DCT is the one step ISO/IEC 11172-2 and 13818-2 leave free (within the
IEEE 1180 accuracy), so the samples equal those of the ISO/IEC 13818-5 reference decoder built with the same
transform and drift from other decoders by the rounding of theirs (ffmpeg's `-idct int` is an older version of the
libjpeg transform and rounds some blocks differently). The conformance bitstreams of ISO/IEC 13818-4 are not
published anymore; the tests use the HHI and Teracom streams the Internet Archive kept, FFmpeg's FATE samples,
streams of the reference encoder and of ffmpeg's encoder, and pseudo-random streams that reach every syntax
element. The scalable extensions (data partitioning, SNR, spatial and temporal scalability) raise
`MediaException.Unsupported`. Raw elementary streams (`.m1v`, `.m2v`) are not demuxed (program streams and
VOBs are). The picture display extension's pan-scan offsets
are not applied (frames keep their coded size). A stream starting with P-pictures, or decoded after a seek, waits
for its next I-picture, and the leading B-pictures of an open group of pictures without their forward reference
are dropped. MPEG-1 slices, which may span rows, are decoded on one thread.

</details>

### HEIC and JPEG XS

<details>
<summary><b>HEIC encoder</b></summary>

The HEIC encoder writes lossy images as 4:2:0 at 8 or 10 bits, so lossy 4:2:2, 4:4:4 and monochrome pictures are
resampled to 4:2:0 and a lossy picture with the identity matrix is rejected; lossless images keep 4:2:2 and 4:4:4
(monochrome becomes 4:2:0 with neutral chroma) and are exact at the coded depth: `HeicEncoder` rescales pictures
deeper than 10 bits, and a lossless `Images.encode` request for one raises `MediaException.Unsupported` unless its
samples are exactly 8-bit. The YCgCo-Re and YCgCo-Ro matrices are rejected. Alpha images are pictures of the color
image's chroma format with neutral chroma rather than monochrome ones (readers use their luma). It writes no
thumbnails, depth maps, gain maps or entity groups.
One play of an image sequence lasts at most 4294967294 microseconds (about 71 minutes), the longest its 32-bit
edit and media durations store at the microsecond timescale.

</details>

<details>
<summary><b>JPEG XS</b></summary>

Temporal differential coding (the TDC 444.12 and TDC MLS 444.12 profiles, and codestreams carrying the
TPC, WGR or SLI markers) is rejected as unsupported, because no source Smalt can port implements it: SVT-JPEG-XS
has none, and the license of the ISO/IEC 21122-5 reference software covers evaluation and conformance testing
only. The MP4 `jxsm` sample entry follows
ISO/IEC 21122-3 Annex B as the other mappings describe it (a `VisualSampleEntry` with the `jpvi` and `jxpl` boxes),
since the annex itself was not available to check against. The Matroska codec registry has no identifier of its own
for JPEG XS, so Matroska files carry it as `V_QUICKTIME` with the `jxsm` sample entry as the QuickTime sample
description; ffmpeg reads such a track (as an unknown codec of tag `jxsm`) and copies it to Matroska as
`V_MS/VFW/FOURCC` with the FourCC `jxsm`, which Smalt reads too, but a player that keys on a
dedicated identifier does not recognize it. The containers mark every JPEG XS packet as a key frame. Lossy coding is held to the rate the full sublevel of its profile admits, but a
lossless codestream takes what its content needs, so noise of 16-bit R'G'B'A can exceed the 48 bits per pixel the
MLS profiles admit and is then refused by decoders that check the sublevel. The encoder codes no temporal
differential coding, no nonlinear transform and no
Star-Tetrix extent other than the profile's, and its video encoder writes progressive frames only.

</details>

### AAC and MPEG-4 CELP

<details>
<summary><b>AAC streams with <code>epConfig</code> 1</b></summary>

Streams with `epConfig` 1 store each error sensitivity category instance in its own access unit (one MP4 track
each); `AacDecoder.decode(List, Consumer)` decodes them from all their instances, while `AudioReader` and
`AacDecoder.decode(Packet, Consumer)` raise `MediaException.Unsupported`. With `epConfig` 3 the error protection
tool carries those instances in one access unit; `epConfig` 3 without `directMapping`, whose mapping ISO/IEC
14496-3 leaves "to be defined", raises `MediaException.Unsupported`. An error protected frame the codes cannot
correct (header, lengths or a CRC still wrong) is concealed as a lost packet, whatever class failed. AAC-ELD with
`epConfig` 1 places its low delay SBR payload in a category 9 and a category 10 instance per channel element
(ISO/IEC 14496-3 Table 4.149: up to `sbr_dtdf`, then from `sbr_invf` on); no conformance stream has that layout,
so the tests split the `epConfig` 0 conformance streams, with and without low delay SBR, into category instances.
Concealment covers the RVLC scalefactors and the reordered spectral data; other corrupt side information
raises `MediaException.Malformed`.

</details>

<details>
<summary><b>AAC error protection tool</b></summary>

Every ISO/IEC 14496-26 stream of the error protection tool over an AAC object type yields the category instances
of its `epConfig` 1 twin and decodes to the same audio bit for bit; so do the ER CELP streams through
`CelpDecoder` and the scalable streams over a CELP core through `AacDecoder`. For the scalable streams over a
TwinVQ core, and for the ER HVXC, ER TwinVQ and ER HILN streams, only the error protection layer is checked (its
output equals the `epConfig` 0 and 1 twins): `AacDecoder` and `AudioSpecificConfig` reject those object types
and cores with `MediaException.Unsupported`.

</details>

<details>
<summary><b>ER AAC LC, ER AAC LTP, AAC-LD and AAC-ELD conformance</b></summary>

The ER AAC LC, ER AAC LTP, AAC-LD and AAC-ELD conformance streams without SBR match their reference waveforms
(in band energy where they use noise substitution), and those ffmpeg's native decoder handles (no resilience
tools, no LTP, 512-sample LD) also match it to single precision rounding. AAC-ELD with low delay SBR is checked
against libxaac and the FDK AAC decoder (both as black boxes), since ISO/IEC 14496-26 ships no reference
waveforms for it, and the 480-sample frames against streams encoded by
libxaac and FDK AAC, since the suite has none. Smalt and libxaac agree to about `2^-16` of full scale except a
few frames of the synthetic envelope streams whose core band is silent; the FDK decoder agrees with both in
the core band (at dual rate the low delay QMF banks reconstruct it to about 30 dB on speech in all three) and
in the energy of the high band, whose waveform it shapes differently. Downsampled 480-sample streams are
checked against FDK alone, as libxaac does not decode them. An SBR header that needs more than the five
patches ISO/IEC 14496-3 allows an encoder is decoded with every patch it describes. The 480-sample ELD window
is derived by least squares from the reference waveforms; the AAC-LD tables of rates outside 22.05 to 48 kHz,
which ISO/IEC 14496-3 leaves undefined, follow libxaac.

</details>

<details>
<summary><b>MPEG-4 CELP</b></summary>

Every ISO/IEC 14496-26 CELP and ER CELP stream matches its reference waveforms at every layer to the
full accuracy of ISO/IEC 11172-4. Several details the standard leaves undefined or states wrongly were derived from the references and
the ISO/IEC 14496-3 reference decoder and are documented in the code: the amplitude order of regular pulse codes,
the comb filtering of the enhancement pulses, the gain of the narrowband excitation in the bandwidth extension,
the prediction state of the bandwidth extension LSPs through non-active frames, and the levels, smoothing,
restart rules and constants of comfort noise. The conformance streams exercise a part of the configurations: every
configuration, with fine rate control, silence compression and the scalable layers, alone and combined, and streams
that start in a non-active period, is compared with the ISO/IEC 14496-3 reference decoder on frames of random bits
(`tools/testdata/celp-oracle`). Fine rate control with a bandwidth scalable layer has no such check: the syntax
allows it, but the reference decoder does not implement it, and no conformance stream uses it; Smalt decodes it as
the syntax states. The post-processor, informative in
the standard, follows the equations of its Annex 3.A, which the reference decoder's differs from. A track hands the codec registry one elementary stream, so `AudioDecoders`
decodes the base layer of a layered object; an `epConfig` 1 object needs all its category streams. LOAS/LATM
rejects CELP with `epConfig` 1, which ISO/IEC 14496-3 subclause 1.7.1 excludes from it, and error protected CELP
payloads whose lengths would come from the CELP frame length tables; `LoasMuxer` writes the base layer of a
CELP object, whose enhancement layers are streams of their own. Error protection classes without direct mapping
(`epConfig` 3 with `directMapping` 0) are rejected, as ISO/IEC 14496-3 does not define them.

</details>

<details>
<summary><b>SBR and parametric stereo around the ER object types</b></summary>

Spectral band replication or parametric stereo signaled around ER AAC LC, ER AAC LTP, AAC-LD or AAC-ELD
(object type 5 or 29) is not decoded: the core decodes alone at its own rate, as is the case around a
scalable AAC core, whose extension payloads are skipped. Scalable AAC over a TwinVQ core layer (the `ac08` to
`ac10`, `ac16` to `ac20` and `er_ac3xx` streams of ISO/IEC 14496-26) raises `MediaException.Unsupported`, as
Smalt has no TwinVQ decoder. The scalable streams over a CELP core (`ac04`, `ac06`, `ac12` to `ac14`,
`er_ac211` and `er_ac221`) match their reference waveforms at every layer to the full accuracy of ISO/IEC 11172-4
but two: the CELP core of `ac04` exceeds the peak criterion by up to 0.7 LSB in 24 samples of a loud passage (the
cause is not established; single precision synthesis does not change it; the RMS criterion holds), and two frames of the stereo layer of `er_ac211`, eight short sequences whose mono layer codes noise
substitution in window groups after the first, differ by up to 240 LSB in the left channel (limited accuracy).
Noise substitution of scalable AAC follows the generator of the reference decoder band for band, so the AAC-only
scalable streams match their reference waveforms at every layer, except the stereo noise bands of `er_ac123`
(compared by band energy); long term prediction runs on the first layer alone.
MPEG Surround (object type 30, ISO/IEC 23003-1) has no public conformance streams: its spatial decoder is
checked against the decoder of the ISO/IEC 23003-3 reference software (which carries the full 23003-1
decoder), bit for bit except the residual signals (within 0.002 of a 16-bit step, the reference dequantizes
them in double precision), on synthetic streams of every tree and tool and on the reference encoder's streams; the reference decoder
parses the arbitrary tree extension but never runs its boxes, so the oracle script patches it to run them
between the subband domain temporal processing and the hybrid synthesis, where Smalt runs them.
It upmixes the downmix of a Main, LC, SSR or LTP stream, not of the error resilient, BSAC or scalable object
types; a spatial frame lasts one block of the downmix, a whole number of them or a whole fraction of one (a
frame that is neither leaves the downmix as the output: ISO/IEC 23003-1, not available, says which block
completes a frame whose boundaries fall inside blocks), and a frame of `n` blocks is output after its last
block, so the output, downmix included, lags by `n - 1` blocks while such a configuration is in use (`flush`
hands over the last ones; a change to another configuration discards them); a block of
several frames takes them from its fill elements, or from a separate stream one access unit per block, so the
rest of such a block repeats that frame's parameters; the upmix starts at the first spatial frame whose
`bsIndependencyFlag` is set. The arbitrary tree's `bsOttDefaultCldAT` is read as the reference software reads
it, as the default CLD index (1 is +2 dB), which ISO/IEC 23003-1 may define as 150 dB, and the left and right center
channels of the 7-2-7 and 7-5-7 trees take the surround gain under the arbitrary tree, also as the reference
software has it. The arbitrary tree extension's boxes
split the outputs into as many as 32 channels on the loudspeaker positions of `bsOutputChannelPosAT`, except under
the non-uniform CLD quantization of `bsQuantMode` 1 and 2, where the upmix is the tree's alone (the energy
tracking of its boxes is not specified to Smalt, and the reference software does not implement it), and a 3D
audio downmix leaves the downmix as the output; the binaural and blind upmixes of the
reference decoder (decoder-side modes) are not implemented. `AudioReader` and `AudioStreamDecoder` fix their
output layout when they open a track, so they mix an upmix whose configuration arrives in the fill elements of
the first block down to the downmix layout the track declares, unless the options request an output layout;
they decode one track, so the side information of a separate object type 30 track needs `AacDecoder(List,
AacDecoderOptions)`, as the layers of scalable AAC in separate tracks do. Over spectral band replication at
dual rate it takes the extension's 64-band QMF matrices directly, otherwise it re-analyzes the time domain
downmix. ER BSAC
matches the reference waveforms of ISO/IEC 14496-26 at every layer they name; a few of its MP4 files list
layer tracks past their end, which are compared up to the layers the remaining tracks hold. Its SBR payloads
are read from right after their count, as ISO/IEC 14496-3 Table 4.46 specifies, rather than 4 bits later as
the reference decoder does; the suite's BSAC SBR streams have no reference waveforms. The MPEG-D DRC decoder
of USAC works in the time domain only (the subband domain gain application of ISO/IEC 23003-4 is not
implemented, nor the loudness equalization of its second edition), and its gains reach the USAC output after
the core decoder, eSBR and MPEG Surround. The time-warped MDCT of USAC (`tw_mdct`, which no ISO/IEC 23003-3
conformance stream uses and the Extended HE AAC profile excludes) matches the time-warped filterbank of the
ISO/IEC 23003-3 reference software bit for bit on synthetic frames, its transitions to and from the linear
prediction domain coder included, except in three places where the reference software's time-warped branch
departs from its plain one: it adds the frequency domain transition twice under a frame that starts with TCX,
drops the end of a long FAC signal, and with 768-sample frames starts the linear prediction domain decoder from
the overlap at the offset of 1024-sample frames; Smalt does as the plain branch does, so that an unwarped
stream decodes as with the plain filterbank half a frame later. After a linear prediction domain frame, the
bass postfilter does not reach into a time-warped frame, as in the reference software. eSBR transposes with the
QMF based harmonic transposer, not with the DFT based one the reference decoder offers as an alternative. AAC Main
prediction matches the ISO/IEC 14496-26 reference waveforms, which evaluate the predictor in double
precision, so it drifts from decoders that use single precision, ffmpeg among them, by up to about -70 dB.

</details>

<details>
<summary><b>HE-AAC encoder</b></summary>

The HE-AAC encoder sends no CRC-protected or extended SBR data, and its parametric stereo codes intensity
differences and coherences on the coarse quantizer with fixed envelope borders, without phase parameters or the
34-band configuration; out-of-phase stereo content partly cancels in the downmix. The high band of the decoded
signal stays within about 3 dB of the input's energy per band, usually 1 to 2 dB below it on tonal material,
where the decoder's gain limiter clips. `setTargetBitrate` retunes the core only: the SBR frequency range keeps
the tuning of the bitrate the encoder was created with. The tuning table covers core rates of 8 to 48 kHz
(output rates of 16 to 96 kHz), from about 8 kbit/s per single channel element (24 kbit/s with a core of 32 kHz
or more), and channel pairs from 16 kbit/s at core rates of 16 to 24 kHz and from 32 kbit/s above; a core rate
below 16 kHz codes single channel elements only. AAC-LD and AAC-ELD are coded without long term prediction and
without the error resilience tools.

</details>

<details>
<summary><b>AAC-ELD encoder: low delay SBR</b></summary>

The low delay SBR of the AAC-ELD encoder (`AacEncoderOptions.lowDelaySbr`) sends no CRC and no in-band
`sbr_header`: the `ELDSpecificConfig` carries the header, which a decoder needs before the first frame, and
`setTargetBitrate` retunes the core only. Its tuning table (libxaac's) covers core rates of 12 to 48 kHz, single
channel elements from 8 to 36 kbit/s depending on the core rate and channel pairs from 32 or 60 kbit/s; the
dual-rate mode delays the output by 896 samples with 512-sample frames (848 with 480), the downsampled mode by
416 (392). With 480-sample frames at dual rate the time grid sits a quarter of a slot (8 core samples) early, the
core's delay not being a whole number of QMF slots. ffmpeg 7.1 does not decode low delay SBR ("not implemented").
The FDK AAC decoder decodes the encoder's streams as Smalt does (60 to 70 dB in the core band, the high band's
energy within about a decibel), libxaac its dual-rate streams (50 to 70 dB, in fixed point), while libxaac's
decodes of its downsampled streams differ from both.

</details>

### AC-3, E-AC-3, DTS and AMR

<details>
<summary><b>DTS</b></summary>

DTS Express (the LBR coding of the extension substream) is recognized but not decoded, since ETSI TS 102 114
describes its residual time samples only as bit counts to skip; XLL frequency bands above 96 kHz are not
decoded, since the specification does not give their band synthesis filter (a stream with a core then outputs
the core); termination frames with a partial subsubframe, whose syntax the specification does not give, and the
core channel arrangements 10 to 15, whose channels beyond the fifth it says are packed in data arrays it never
defines, are rejected. XLL channels coded against a core add to a fixed-point reconstruction of that core, which the
specification does not define; Smalt's, at the rate of the core and at twice it, was measured from ffmpeg's lossless
output and matches it bit for bit (ffmpeg refuses 88.2 kHz channels over a 44.1 kHz core, which use the same
reconstruction), with two exceptions: ffmpeg outputs the core alone for the first frame with several
channel sets after a frame without XLL, where Smalt decodes the frame (the FATE streams show its output is the lossless
one), and ffmpeg leaves sum/difference decoding out of that reconstruction, where Smalt applies it as the specification
asks (no known lossless stream sets those flags). That reconstruction leaves the X96 extension out, adds XBR in
integers and keeps the downmixes of XCh and XXCH, all measured the same way; the DTS-HD MA samples of
samples.ffmpeg.org match too. ffmpeg refuses a core with a 128 times interpolated LFE channel in its lossless mode, so
nothing shows what channels coded against it add to; such a stream outputs its core with the other extensions, unless
all its channels are coded alone. Channel sets of different widths and parallel (not hierarchical) downmixes follow the
specification, but ffmpeg decodes neither, so nothing compares them. A peak bit rate smoothing buffer (a nonzero
`nuPeakBRCntrlBuffSzkB` or `nuExSSXLLSyncOffset`), which no stream known uses, is not implemented: a stream with a core
then outputs the core, one without raises `MediaException.Unsupported`. X96 fills the subbands without bits with noise
from a generator the specification leaves unstated, so its output agrees with other decoders only to the level of that
noise (about 50 to 60 dB).
Scale factor, step size and code products that overflow the 24-bit subband samples saturate, which other decoders
may handle differently; valid streams do not code them.

</details>

<details>
<summary><b>E-AC-3 at reduced sampling rates</b></summary>

E-AC-3 at the reduced sampling rates of 16 to 24 kHz (ATSC A/52 Annex E) is valid but little supported: ffmpeg 7.1 reports it
as not implemented, and neither Windows Media Foundation nor Core Audio (`afconvert`) has a decoder for it.
ffmpeg 7.1 decodes only programs with one dependent substream, and puts the side channels of a program whose
dependent substream adds channels without repeating them (9.1, 7.1.2) in the wrong place; Windows Media
Foundation renders the 5.1 core of the programs beyond 7.1. ffmpeg rejects every audio block coded with
enhanced coupling.

</details>

<details>
<summary><b>AMR, AC-3 and E-AC-3 after a seek</b></summary>

After `AudioReader.seek`, AMR output converges to that of a decode from the start over a few hundred
milliseconds, and AC-3 and E-AC-3 dither differs, since the dither generator continues across frames;
the other codecs except Opus and AAC give the same samples bit for bit.

</details>

Related limits elsewhere: [HEIF files, Dolby Vision, HDR metadata, ADTS and elementary streams](../core/README.md#known-limits)
in core.

## Patents

The H.264, HEVC, AAC, AMR and MPEG-4 Part 2 formats in `smalt-mpeg` are covered by patents licensed through
patent pools, and E-AC-3 (Dolby Digital Plus) may still be covered by Dolby patents; the AC-3 patents expired
in 2017. The MIT License covers Smalt's code only and grants no patent rights, and the patent notes in this
README are orientation, not legal advice: check whether your use needs a license.

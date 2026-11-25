# smalt-video

> Pure-Java video codecs: VP9, AV1, AVIF, ProRes, DNxHD/DNxHR, DV, Theora, FFV1, QuickTime Animation and CineForm.

[← Back to Smalt](../README.md) · [Javadoc](https://javadoc.io/doc/com.github.auties00/smalt-video)

## At a glance

- Decode and encode VP9 and AV1; the decoders are bit-exact with libvpx and libaom ([benchmarks](../README.md#performance)).
- Read and write AVIF stills, sequences and gain maps through core's `Images` or `AvifEncoder` and `AvifDecoder`.
- Decode and encode ProRes, DNxHD/DNxHR, DV, FFV1, Theora and QuickTime Animation; decode CineForm.
- Every codec registers with core's codec registry (`VideoDecoders`, `VideoEncoders`), so `VideoReader`, `VideoWriter` and `Transcoder` use it as soon as the jar is on the path.
- Depends only on [smalt-core](../core/README.md#at-a-glance). For SIMD speed, run with `--add-modules jdk.incubator.vector` (see [JVM flags](../README.md#jvm-flags)).
- VP8 is in [smalt-image](../image/README.md#video-codecs); H.264, HEVC and HEIC are in [smalt-mpeg](../mpeg/README.md#video).

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-video</artifactId>
</dependency>
```

The version comes from the BOM: see [Install](../README.md#install) for the BOM import and Gradle.

## Examples

PNG input needs [smalt-image](../image/README.md#at-a-glance), and H.264 input such as a typical `.mp4` needs [smalt-mpeg](../mpeg/README.md#at-a-glance).

### Convert a photo to AVIF

`AvifEncoderOptions` has presets for lossy, lossless, web, distribution and archive files.

```java
var picture = Images.decode(Path.of("photo.png")).picture();
byte[] avif = AvifEncoder.encode(picture, AvifEncoderOptions.lossy(60));
Files.write(Path.of("photo.avif"), avif);
```

### Encode a video to AV1 in WebM

`VideoWriter` finds the AV1 encoder through the registry and writes the video track only; use `Transcoder` to keep the audio.

```java
try (var source = MediaSource.of(Path.of("talk.mp4"));
     var sink = MediaSink.of(Path.of("talk.webm"));
     var reader = VideoReader.open(source);
     var writer = VideoWriter.open(sink, OutputContainer.WEBM, VideoWriterOptions.builder()
             .codec(Codec.AV1)
             .size(reader.track().width(), reader.track().height())
             .frameRate(reader.track().frameRate().orElse(VideoWriterOptions.DEFAULT_FRAME_RATE))
             .build())) {
    for (var frame = reader.read(); frame != null; frame = reader.read()) {
        writer.write(frame.picture());
    }
    writer.finish();
}
```

### Transcode to ProRes for editing

The transcoder encodes ProRes 422 HQ when you ask for `Codec.PRORES`.

```java
var options = TranscodeOptions.builder()
        .container(OutputContainer.MOV)
        .video(VideoTranscodeOptions.encode(Codec.PRORES))
        .build();
Transcoder.transcode(Path.of("talk.mp4"), Path.of("talk.mov"), options);
```

### Encode ProRes 4444 frame by frame

Call `ProResEncoder` yourself to pick a profile (Apple calls them flavors) the registry does not use.

```java
try (var source = MediaSource.of(Path.of("talk.mp4"));
     var sink = MediaSink.of(Path.of("edit.mov"));
     var reader = VideoReader.open(source);
     var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.quickTime())) {
    var in = reader.track();
    var rate = in.frameRate().orElse(new Rational(30, 1));
    var options = ProResEncoderOptions.of(ProResProfile.PRORES_4444).toBuilder()
            .timeBase(in.timeBase()).frameRate(rate).build();
    var encoder = new ProResEncoder(in.width(), in.height(), options);
    var track = VideoTrack.builder().id(1).codec(Codec.PRORES)
            .timeBase(encoder.timeBase()).frameRate(rate)
            .codecConfig(encoder.codecConfig().orElseThrow())
            .width(in.width()).height(in.height()).build();
    muxer.addTrack(track);
    for (var frame = reader.read(); frame != null; frame = reader.read()) {
        muxer.writePacket(encoder.encodeFrame(frame).withTrackId(1));
    }
    muxer.finish();
}
```

### Decode 10-bit video at full depth

Decoders round to 8 bits by default; `highBitDepth` keeps 10 and 12-bit samples.

```java
var options = VideoDecoderOptions.builder().highBitDepth(true).build();
try (var source = MediaSource.of(Path.of("hdr.mkv"));
     var reader = VideoReader.open(source, options)) {
    for (var frame = reader.read(); frame != null; frame = reader.read()) {
        var picture = frame.picture(); // a Yuv16Picture for 10 and 12-bit Y'CbCr streams
        System.out.println(picture.bitDepth() + "-bit " + picture.width() + "x" + picture.height());
    }
}
```

## Formats

### Video codecs

| Codec | Decode | Encode | Notes |
|---|:-:|:-:|---|
| VP9 | ✅ | ✅ | Profiles 0-3, 8-12 bit; alpha in WebM; bit-exact with libvpx |
| AV1 | ✅ | ✅ | Main, High, Professional; 8-12 bit; bit-exact with libaom |
| Apple ProRes | ✅ | ✅ | All 6 flavors, 4444 alpha |
| Avid DNxHD and DNxHR | ✅ | ✅ | Every VC-3 profile (SMPTE ST 2019-1); alpha |
| DV, DVCPRO, DVCPRO50, DVCPRO HD | ✅ | ✅ | 10 profiles at fixed sizes |
| FFV1 | ✅ | ✅ | Lossless; versions 0, 1 and 3 |
| Theora | ✅ | ✅ | Decode bit-exact with libtheora 1.2.0 |
| QuickTime Animation | ✅ | ✅ | Every depth; bit-exact decode |
| GoPro CineForm | ✅ | ❌ | CineForm RAW (Bayer) included |

✅ supported · ❌ not supported · ➖ not applicable

### AVIF

| Feature | Decode | Encode | Notes |
|---|:-:|:-:|---|
| Still images | ✅ | ✅ | 8-12 bit, grids, alpha, HDR metadata |
| Sequences (`avis`) | ✅ | ✅ | Color and alpha tracks |
| Gain maps (ISO 21496-1) | ✅ | ✅ | `decodeGainMap`, `encode(picture, gainMap, ...)` |
| Collections and auxiliary images | ✅ | ❌ | `openPages`, `auxiliaryImages` |
| Layered images and thumbnails | ✅ | ❌ | `a1op`/`lsel`; `thmb` under the reduced-size hint |

smalt-mpeg's HEIC decoder uses this AVIF decoder for the AV1 items inside HEIC files.

### Default settings of VideoWriter and Transcoder

The registry picks fixed settings per codec; call the encoder classes for the rest.

| Codec | What the registry encodes |
|---|---|
| VP9 | 4:2:0, good quality, `Vp9EncoderSpeed.SPEED_4`; alpha supported |
| AV1 | 4:2:0, good quality, `Av1EncoderSpeed.FAST` |
| ProRes | 422 HQ |
| DNxHD | DNxHR HQ; HQX for 10 and 12-bit requests |
| DV | The `dvFormat` requested, else the profile of the picture size |
| FFV1 | Version 3, 4:2:0, at the requested depth |
| Theora | 4:2:0 at the default quality |
| QuickTime Animation | 32-bit ARGB |

- `SPEED_4` and `FAST` match libvpx `cpu-used` 4 and libaom `cpu-used` 6.
- DV picks 720p50 below 55 fps, and 16:9 when the display aspect is wider than 14:9.
- With `realtime(true)` in `VideoWriterOptions` or `VideoEncoderOptions`, VP9 and AV1 switch to their one-pass real-time engines and FFV1 to `Ffv1EncoderOptions.forSpeed()`.

<details>
<summary><b>VP9 and AV1 in detail</b></summary>

- **VP9 decode:** profiles 0-3, 8/10/12-bit, 4:2:0, 4:2:2, 4:4:0 and 4:4:4; bit-exact on the libvpx test vectors.
- **VP9 alpha:** `VideoReader` pairs the WebM alpha stream with the color frames; `Vp9Decoder` alone does not.
- **VP9 encode usages:** `REALTIME` (speeds 5-9, CBR), `GOOD_QUALITY` (speeds 0-4) and `BEST_QUALITY`.
- **VP9 good quality:** 25-frame lookahead, hidden alternate reference frames, VBR, CQ or constant quality.
- **VP9 extras:** two-pass with `Vp9FirstPass`, up to 3 temporal layers in real time, tiles, `Vp9Encoder.reconfigure`.
- **VP9 encode alpha:** `Vp9EncoderOptions.Builder.alpha(true)` writes a second 8-bit 4:2:0 VP9 stream with aligned key frames.
- **AV1 decode:** every coding tool, including intra block copy, palette, warped motion, superres and film grain.
- **AV1 decode extras:** scalable operating points, `Av1DecoderOptions.Builder.allLayers`, Annex B (length-prefixed OBU streams), large-scale tile via `Av1Decoder.setAnchorFrames`.
- **AV1 conformance:** bit-exact on the libaom vectors and the Argon conformance suite.
- **AV1 encode usages:** `REALTIME`, `GOOD_QUALITY` (35-frame lookahead) and `ALL_INTRA` (`forIntraOnly`).
- **AV1 encode formats:** every usage codes 8/10/12-bit 4:2:0, 4:2:2, 4:4:4 and monochrome.
- **AV1 film grain:** pass an `Av1FilmGrainTable` (libaom text format) or let `Av1EncoderOptions.Builder.denoise(1..50)` estimate it.
- **AV1 extras:** two-pass with `Av1FirstPass`, screen content, superres (9-16), up to 3 temporal layers in real time, `Av1Encoder.reconfigure(w, h)`, timing info.

</details>

<details>
<summary><b>AVIF in detail</b></summary>

- **Decode:** `av01` or `grid` primary items, derived `iden`, `iovl` and AVIF 1.2 `sato` items.
- **Decode depth:** 8-bit by default; `highBitDepth` keeps 10 and 12-bit samples, and `sato` items give up to 16 bits.
- **Decode regions:** with a region, a grid image decodes only the grid images the region covers.
- **Decode alpha:** per item, grid or tile; premultiplied alpha comes back straight.
- **Decode metadata:** `clap` applied, `irot`/`imir` reported as orientation, `nclx`, ICC, `clli`, `mdcv`, EXIF, XMP.
- **Encode quality:** 0-100 on the libavif scale (100 is lossless), speed 0-10; default quality 60, speed 6.
- **Encode presets:** `AvifEncoderOptions.forWeb()` (60, speed 8), `forDistribution()` (70, speed 4), `forArchive()` (90, 4:4:4 10-bit).
- **Encode formats:** 8/10/12-bit, 4:2:0, 4:2:2, 4:4:4 and monochrome; alpha lossless by default.
- **Encode large images:** split into a grid automatically above 8192 samples per side.
- **Sequences:** `openAnimation` reads, `openAnimationWriter` writes; lossy 8-bit 4:2:0 color uses inter frames.
- **Gain maps:** written as a hidden item plus a `tmap` item; decoding renders libavif's gain-map files within one code value of libavif 1.3.0.

</details>

<details>
<summary><b>ProRes and DNxHD/DNxHR in detail</b></summary>

- **ProRes flavors:** Proxy, LT, 422, HQ, 4444 and 4444 XQ; progressive and interlaced; any size up to 65535.
- **ProRes decode:** 10-bit (422) or 12-bit (4444) `Yuv16Picture` by default; the registry decodes to 8 bit.
- **ProRes encode:** `ProResEncoderOptions` takes Apple target rates, a `bitrate`, or a fixed `quantizationIndex(1..224)`.
- **ProRes alpha:** 4444 flavors only, lossless, 16-bit by default or 8-bit with `alphaBits`.
- **DNxHD decode:** IDs 1235-1260 at 1920x1080, 1440x1080, 1280x720 and 960x720, 8 or 10-bit.
- **DNxHR decode:** IDs 1270-1274 (444, HQX, HQ, SQ, LB), any size up to 16384x16384, 8-12 bit.
- **DNxHD alpha:** in-band (ST 2019-1 Amendment 1, straight or premultiplied) and Avid's out-of-band alpha, both ways; premultiplied alpha decodes straight.
- **DNxHR encode:** LB, SQ, HQ at 8-bit; HQX at 10/12-bit; 444 at 10/12-bit Y'CbCr or RGB.
- **DNxHD encode:** `DnxhdEncoderOptions` picks the compression ID from raster, scan, depth and `bitrate`, or takes `compressionId(...)`.

</details>

<details>
<summary><b>DV, FFV1, Theora, QuickTime Animation and CineForm in detail</b></summary>

- **DV profiles:** DV and DVCPRO at 525 and 625 lines, DVCPRO50, DVCPRO HD 1080i60, 1080i50, 720p60 and 720p50.
- **DV decode:** reads the format per frame; 8-bit output, BT.601 for SD and BT.709 for HD.
- **DV encode:** `DvEncoderOptions` has `wide` for 16:9 and `adaptiveDct`; writes full DIF frames that core's `DvMuxer` stores.
- **FFV1 decode:** versions 0, 1 and 3; Golomb Rice and range coder; slices; Y'CbCr, RGB, alpha; 1-16 bit.
- **FFV1 encode:** versions 0, 1, 3 (default 3); any chroma format or RGB; 8-16 bit; alpha; 1-1024 slices.
- **FFV1 conformance:** lossless, and bit-exact with ffmpeg in both directions.
- **Theora decode:** bitstream 3.0-3.2; 4:2:0, 4:2:2 and 4:4:4 at 8 bit, cropped to the picture region.
- **Theora encode:** 4:2:0, 4:2:2 or 4:4:4; key and inter frames, quality 0-63 (default 48), key frame every 64 by default.
- **QuickTime Animation decode:** 1/2/4/8-bit indexed, 16, 24 and 32-bit RGB, 1/2/4/8-bit gray.
- **QuickTime Animation encode:** depths 16, 24 (default), 32 (ARGB) and 40 (gray); key frame every 12.
- **CineForm decode:** 4:2:2 Y'CbCr, RGB and RGBA 4:4:4; intra and two-frame groups; progressive and interlaced.
- **CineForm RAW:** `CfhdDecoderOptions.bayerOutput(CfhdBayerOutput.DEMOSAICED)` gives 16-bit RGB (default); `MOSAIC` gives the raw 16-bit sensor data.

</details>

## Known limits

- The registry encodes VP9 and AV1 at 4:2:0 only; call `Vp9Encoder` or `Av1Encoder` for other chroma formats.
- VP9 and AV1 good-quality encoders hold frames back for lookahead: call `flush` to get the last packets.
- The AV1 encoder does not use intra block copy, and cannot code 4:4:0 (AV1 has no 4:4:0).
- DV encodes only the five fixed sizes of its profiles: 720x480, 720x576, 1280x1080, 1440x1080 and 960x720.
- An AVIF file with HEVC, AVC, JPEG or uncompressed items needs [smalt-mpeg](../mpeg/README.md#at-a-glance) to decode them.

<details>
<summary><b>VP9</b></summary>

- Real-time mode is CBR only, at `SPEED_5` or faster, with no first-pass stats.
- Real-time mode has no screen-content tuning, spatial layers, dynamic resize, denoiser or ML partitioning.
- Good and best quality have no CBR, error resilience, AQ (adaptive quantization), frame dropping or temporal layers.
- Good quality uses one layer of alternate references and skips libvpx's neural-network partition pruning.
- 10 and 12-bit encodes take their decisions on the 8-bit rounding of the samples.

</details>

<details>
<summary><b>AV1</b></summary>

- Inter frames and real-time frames search luma palettes only; the still encoder also searches chroma.
- `Av1ScreenContent.DETECT` codes no frame with screen tools in real time or at `FASTER`/`FASTEST` intra speeds.
- Super-resolution uses one fixed denominator; good quality leaves key frames at full width, and `ALL_INTRA` does not support it.
- Real-time cannot take a constant quality, and `ALL_INTRA` requires one.
- First-pass stats need `GOOD_QUALITY`; timing info and superres need `REALTIME` or `GOOD_QUALITY`.
- With the decoder model on, constant quality does not bound the bitrate, so a stream can break its level.
- `reconfigure(w, h)` forces a key frame when a reference is over 2x or under 1/16 of the new size.
- `maxFrameDelay` above 1 returns frames up to that many packets minus one later; it has no effect with one thread.
- Large-scale tile: tile lists decode without CDF (entropy context) adaptation, and `setAnchorFrames` takes at most 128 anchor frames.

</details>

<details>
<summary><b>AVIF</b></summary>

- The encoder has no 4:4:0; automatic chroma codes 4:4:0 input as 4:4:4.
- More than 256 grid images per side, or a file over 2 GiB, throws `LimitExceeded`.
- Sequence alpha frames are always key frames.
- The decoder ignores EXIF orientation and reports `irot`/`imir` without applying them.
- An unknown essential property throws `Unsupported`; sequences ignore the reduced-size hint.

</details>

<details>
<summary><b>ProRes and DNxHD/DNxHR</b></summary>

- ProRes RAW is not supported; ProRes bitstream versions above 1 are rejected.
- ProRes RGB input cannot be encoded with the identity matrix.
- The registry ignores key frame and real-time settings for ProRes.
- ProRes and DNxHD decoders use a float IDCT and can differ by one code value from other decoders.
- With threads above 1, the ProRes and DNxHD decoders can return a frame in a later `decode` or `flush` call.
- DNxHR encodes progressive only; DNxHR 4:2:0 needs an even size and is not available for 444.
- DNxHD in-band alpha is not available on ID 1253 or DNxHR LB; lossless alpha only on 444 and 1256.
- DNxHD follows ST 2019-1 where ffmpeg does not, so coarse streams decode a few code values apart.
- ffmpeg ignores DNxHD alpha in both storages and reads the 4:4:4 RGB flag inverted.
- RGB DNxHR 444 is coded as Y'CbCr macroblocks by default so ffmpeg shows it right; `alternateColorSpace(false)` codes raw RGB.

</details>

<details>
<summary><b>DV</b></summary>

- 4:1:1 decodes as 4:2:2 by doubling chroma; the encoder averages 4:2:2 chroma down to 4:1:1.
- Decoded samples stay within 2 code values of ffmpeg; macroblock error status is not used for concealment.
- The encoder writes empty audio sections and starts the time code at zero.
- `wide` has no effect on DVCPRO HD, which is always 16:9.
- ffmpeg finds no time code in the DVCPRO layouts, since it reads only subcode sync 0.

</details>

<details>
<summary><b>FFV1, Theora and QuickTime Animation</b></summary>

- FFV1 versions 2, 3.0-3.3 and above 3 are rejected.
- The FFV1 encoder writes 8 bits only in version 0, and Golomb Rice only up to 8 bits (deeper uses the range coder).
- FFV1 encodes are lossless only for pictures already in the coded layout.
- The Theora encoder uses one quality index, one static Huffman code and no rate control.
- The Theora decoder skips post-processing (libtheora's default) and does not read the comment header.
- QuickTime Animation decodes to 8-bit RGBA and is single-threaded.
- QuickTime Animation encodes losslessly only at depth 32, or at depth 24 for opaque pictures.

</details>

<details>
<summary><b>CineForm</b></summary>

- Y'CbCrA 4:4:4:4, uncompressed samples and transform types other than 0 and 2 are rejected.
- Non-RAW output skips GoPro's rounding offsets on the lowpass band: within 8 levels of ffmpeg and 4 of GoPro in tests.
- RAW demosaic ignores white balance, color matrix and look metadata, and needs at least 4 photosites of width.
- Progressive RAW frames with extreme wavelet coefficients (such as CineStyle footage) decode unlike GoPro's decoder.

</details>

## API map

Add `requires com.github.auties00.smalt.video;` (it brings in core transitively). Packages are under `com.github.auties00`.

| Codec | Package | Classes |
|---|---|---|
| VP9 | `smalt.video.vp9` | `Vp9Decoder`, `Vp9Encoder`, `Vp9EncoderOptions`, `Vp9FirstPass` |
| AV1 | `smalt.video.av1` | `Av1Decoder`, `Av1DecoderOptions`, `Av1Encoder`, `Av1EncoderOptions`, `Av1FirstPass` |
| AVIF | `smalt.image.avif` | `AvifDecoder`, `AvifEncoder`, `AvifEncoderOptions` |
| ProRes | `smalt.video.prores` | `ProResDecoder`, `ProResEncoder`, `ProResEncoderOptions`, `ProResProfile` |
| DNxHD/DNxHR | `smalt.video.dnxhd` | `DnxhdDecoder`, `DnxhdEncoder`, `DnxhdEncoderOptions`, `DnxhdProfile` |
| DV | `smalt.video.dv` | `DvDecoder`, `DvEncoder`, `DvEncoderOptions`, `DvProfile` |
| FFV1 | `smalt.video.ffv1` | `Ffv1Decoder`, `Ffv1Encoder`, `Ffv1EncoderOptions` |
| Theora | `smalt.video.theora` | `TheoraDecoder`, `TheoraEncoder`, `TheoraEncoderOptions` |
| QuickTime Animation | `smalt.video.qtrle` | `QtrleDecoder`, `QtrleEncoder`, `QtrleEncoderOptions` |
| CineForm | `smalt.video.cfhd` | `CfhdDecoder`, `CfhdDecoderOptions` |

Core's `VideoDecoderOptions` and `VideoEncoderOptions`, and most codec option classes, take `threads`: 0 (the default) uses the shared pool, 1 the calling thread.

## Licensing

ProRes is an Apple technology that Apple licenses, and DNxHD and DNxHR are Avid technologies that Avid licenses.
Smalt implements both from the SMPTE documents (RDD 36 and ST 2019-1). Check whether your use needs a license before you distribute files made with these encoders.

Some codecs port code from reference implementations under permissive licenses:

| Codec | Ported from | License |
|---|---|---|
| VP9 | libvpx 1.15.1 and 1.17.0 | BSD 3-Clause |
| AV1 | dav1d, libaom 3.12.1 and 3.13.1 | BSD 2-Clause |
| AVIF | libavif | BSD 2-Clause |
| Theora | libtheora 1.2.0 | BSD 3-Clause |
| CineForm | cineform-sdk | Apache 2.0 or MIT |

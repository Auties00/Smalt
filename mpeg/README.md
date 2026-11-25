# smalt-mpeg

> Smalt's codecs for patent-licensed standards (H.264, HEVC, VVC, HEIC, AAC, AMR, AC-3, E-AC-3, DTS and more), in one artifact so you can leave them out.

[← Back to Smalt](../README.md) · [Javadoc](https://javadoc.io/doc/com.github.auties00/smalt-mpeg)

## At a glance

- **No setup:** add the jar and `Images`, `VideoReader`, `AudioReader`, `VideoWriter`, `AudioWriter` and `Transcoder` use these codecs.
- **Video:** H.264, HEVC, MPEG-4 Part 2, H.263 and JPEG XS decode and encode; VVC, MPEG-1/2, VC-1 and Sorenson Spark decode.
- **Images:** HEIC and JPEG XS decode and encode, through `Images` or their own classes.
- **Audio:** AAC (LC, HE-AAC v1/v2, LD, ELD), AMR, AC-3 and E-AC-3 decode and encode; USAC, DTS and MPEG-4 speech codecs decode.
- **Kept apart on purpose:** leave this artifact out and none of these codecs ships with your app. See [Patents](#patents).
- Depends only on [smalt-core](../core/README.md#at-a-glance).
- Faster with the optional Vector API flag: see [JVM flags](../README.md#jvm-flags).

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-mpeg</artifactId>
</dependency>
```

The version comes from the BOM: see [Install](../README.md#install) for the BOM import and Gradle.

## Examples

### Read a HEIC photo

With this jar present, core's `Images` reads HEIC; you apply the orientation yourself. A gain map is the extra layer that turns the SDR photo into its HDR version.

```java
var photo = Images.decode(Path.of("photo.heic"));
var upright = ImageOps.orient(photo.picture(), photo.orientation());
try (var source = MediaSource.of(Path.of("photo.heic"))) {
    Optional<GainMap> gainMap = HeicDecoder.decodeGainMap(source);
}
```

### Save a picture as HEIC

`HeicEncoder` takes any `Picture`; reading the PNG here needs [smalt-image](../image/README.md#formats).

```java
var picture = Images.decode(Path.of("photo.png")).picture();
Files.write(Path.of("photo.heic"), HeicEncoder.encode(picture, HeicEncoderOptions.ofQuality(60)));
Files.write(Path.of("exact.heic"), HeicEncoder.encode(picture, HeicEncoderOptions.forLossless()));
```

### Decode an AAC song

`AudioReader` finds the AAC decoder in this jar.

```java
try (var source = MediaSource.of(Path.of("song.m4a"));
     var reader = AudioReader.open(source)) {
    for (var block = reader.read(); block != null; block = reader.read()) {
        System.out.println(block.frames());
    }
}
```

### Decode HEVC at 10 bits

Without `highBitDepth`, the decoder rounds deeper streams to 8 bits. For per-track control, build an `HevcDecoder` from the track instead.

```java
var options = VideoDecoderOptions.builder().highBitDepth(true).build();
try (var source = MediaSource.of(Path.of("hdr.mkv"));
     var reader = VideoReader.open(source, options)) {
    for (var frame = reader.read(); frame != null; frame = reader.read()) {
        System.out.println(frame.pts() + " " + frame.picture().bitDepth());
    }
}
```

### Transcode a video to HEVC

`Transcoder` encodes with this jar's HEVC and AAC encoders; add smalt-video if the input is VP9 or AV1.

```java
var options = TranscodeOptions.of(OutputContainer.MP4).toBuilder()
        .video(VideoTranscodeOptions.encode(Codec.HEVC))
        .audio(AudioTranscodeOptions.encode(Codec.AAC))
        .build();
Transcoder.transcode(Path.of("talk.mkv"), Path.of("talk.mp4"), options);
```

### Encode H.264 for a video call

The real-time preset sends every frame at once at a constant bitrate. You can change the bitrate or ask for a key frame mid-stream; reading the PNG needs [smalt-image](../image/README.md#formats).

```java
var picture = Images.decode(Path.of("webcam.png")).picture();
var options = H264EncoderOptions.forRealtime().toBuilder().bitrate(1_200_000).build();
try (var encoder = new H264Encoder(picture.width(), picture.height(), options)) {
    // 90 kHz clock: 3000 ticks is one frame at 30 fps
    encoder.encode(new VideoFrame(picture, 0, 3000, true), p -> System.out.println(p.size()));
    encoder.setTargetBitrate(800_000);
    encoder.requestKeyframe();
    encoder.encode(new VideoFrame(picture, 3000, 3000, false), p -> System.out.println(p.size()));
}
```

### Encode a 7.1 mix to E-AC-3

`AudioWriter` picks the E-AC-3 encoder, which stores the channels beyond 5.1 in linked extra streams.

```java
var input = AudioReaderOptions.of(48_000, ChannelLayout.SURROUND_7_1);
var options = AudioWriterOptions.builder()
        .codec(Codec.EAC3).sampleRate(48_000).layout(ChannelLayout.SURROUND_7_1).bitrate(768_000)
        .build();
try (var source = MediaSource.of(Path.of("mix.wav"));
     var sink = MediaSink.of(Path.of("mix.ec3"));
     var reader = AudioReader.open(source, input);
     var writer = AudioWriter.open(sink, OutputContainer.EAC3, options)) {
    for (var block = reader.read(); block != null; block = reader.read()) {
        writer.write(block);
    }
    writer.finish();
}
```

## Formats

### Video

| Format | Decode | Encode | Notes |
|---|:-:|:-:|---|
| H.264 / AVC | ✅ | ✅ | Every profile up to High 4:4:4; MVC, SVC and 3D-AVC decode |
| HEVC / H.265 | ✅ | ✅ | Main, 4:4:4 and screen content (RExt, SCC), multilayer decode; 8 and 10-bit encode |
| VVC / H.266 | ✅ | ❌ | Main 10: 4:2:0 and gray, up to 10 bit |
| MPEG-1 and MPEG-2 video | ✅ | ❌ | Every non-scalable profile and level |
| MPEG-4 Part 2 | ✅ | ✅ | Simple and Advanced Simple; DivX and Xvid files |
| H.263 | ✅ | ✅ | Every optional mode decodes; baseline and H.263v2 encode |
| Sorenson Spark (FLV1) | ✅ | ❌ | Versions 0 and 1 |
| VC-1 (WMV3, WVC1, WMVA) | ✅ | ❌ | Simple, Main and Advanced, 4:2:0 |
| JPEG XS | ✅ | ✅ | In MPEG-TS, MP4, MXF and Matroska; every frame a key frame |

✅ supported · ❌ not supported · ➖ not applicable

### Images

| Format | Decode | Encode | Notes |
|---|:-:|:-:|---|
| HEIC / HEIF | ✅ | ✅ | Grids, alpha, gain maps, sequences; decode up to 16 bit, encode 8 or 10 bit |
| JPEG XS | ✅ | ✅ | `.jxs` or raw codestream; lossless or a bits-per-pixel rate |

- Both register with `Images` as `ImageFormat.HEIC` and `ImageFormat.JPEG_XS`.
- With [smalt-desktop](../desktop/README.md#at-a-glance), `ImageIO` reads and writes them too.
- The HEIC decoder also serves the HEIF decoders of other artifacts (such as AVIF files with HEVC items) when this jar is present.

### Audio

| Format | Decode | Encode | Notes |
|---|:-:|:-:|---|
| AAC-LC | ✅ | ✅ | Up to 22.2 channels; CBR or VBR encode |
| HE-AAC v1 and v2 | ✅ | ✅ | SBR and parametric stereo |
| AAC-LD and AAC-ELD | ✅ | ✅ | Low delay; ELD with low delay SBR |
| AAC Main, SSR and LTP | ✅ | ❌ | |
| ER AAC, BSAC and scalable AAC | ✅ | ❌ | Error resilience and layered streams |
| USAC (MPEG-D) | ✅ | ❌ | eSBR, MPEG-D DRC |
| MPEG Surround | ✅ | ❌ | Up to 32 outputs; binaural rendering |
| AC-3 | ✅ | ✅ | 32-640 kbit/s encode |
| E-AC-3 | ✅ | ✅ | 7.1 and up via dependent substreams |
| DTS, DTS-HD and DTS Express | ✅ | ❌ | XLL lossless up to 192 kHz |
| AMR-NB and AMR-WB | ✅ | ✅ | Bit-exact with the 3GPP reference |
| MPEG-4 CELP, TwinVQ and HVXC | ✅ | ❌ | Speech and low-rate audio |

<details>
<summary><b>Codec classes</b></summary>

| Format | Decoder | Encoder |
|---|---|---|
| H.264 | `H264Decoder` | `H264Encoder` |
| HEVC | `HevcDecoder` | `HevcEncoder` |
| VVC | `VvcDecoder` | ➖ |
| MPEG-1/2 | `Mpeg2Decoder` | ➖ |
| MPEG-4 Part 2 | `Mpeg4Decoder` | `Mpeg4Encoder` |
| H.263, Sorenson Spark | `H263Decoder` | `H263Encoder` (H.263 only) |
| VC-1 | `Vc1Decoder` | ➖ |
| HEIC | `HeicDecoder` | `HeicEncoder` |
| JPEG XS image | `JpegXsDecoder` | `JpegXsEncoder` |
| JPEG XS video | `JpegXsVideoDecoder` | `JpegXsVideoEncoder` |
| AAC, USAC | `AacDecoder` | `AacEncoder` |
| AMR | `AmrDecoder` | `AmrEncoder` |
| AC-3, E-AC-3 | `Ac3Decoder` | `Ac3Encoder`, `Eac3Encoder` |
| DTS | `DtsDecoder` | ➖ |
| CELP, TwinVQ, HVXC | `CelpDecoder`, `TwinVqDecoder`, `HvxcDecoder` | ➖ |

</details>

<details>
<summary><b>H.264 in detail</b></summary>

**Decode**

- Baseline (with FMO and ASO), Extended (SP/SI, data partitioning), Main, High, High 10, High 4:2:2, High 4:4:4 Predictive and the Intra profiles.
- 4:0:0 to 4:4:4, 8 to 14 bit, separate color planes, lossless macroblocks, CABAC and CAVLC.
- Field pictures and MBAFF; output matches the normative decoding process.
- Multiview: MVC (Multiview High, Stereo High), MFC High, Multiview Depth High, MFC Depth High and 3D-AVC.
- Scalable: Scalable Baseline, High and High Intra (CGS, MGS, spatial); pick a layer with `targetLayer`.
- Alpha auxiliary pictures, film grain, and frame-parallel threads.
- `decode(Packet, FrameConsumer)` reports damaged frames, and `keyframeNeeded()` tells you when to ask for a key frame.
- `forRealtime()` outputs each frame before the decode call returns.
- `H264StreamInfo.probe` reads profile, level, size, depth and color from `avcC` or Annex B bytes.

**Encode**

- Constrained Baseline, Main, High, High 10, High 4:2:2 and High 4:4:4 Predictive.
- 4:0:0 to 4:4:4, 8 to 14 bit; lossless in High 4:4:4 Predictive.
- Progressive, field pictures or MBAFF; CABAC or CAVLC.
- Up to 8 B-frames, with B pyramid, lookahead and macroblock tree for 8-bit 4:2:0 progressive streams.
- Constant quality, average or constant bitrate, two-pass, and HRD signaling.
- Slices, a maximum slice size, intra refresh and 1-4 temporal layers.
- Presets `forFile()` and `forRealtime()`; output is the same for any thread count.

</details>

<details>
<summary><b>HEVC and VVC in detail</b></summary>

**HEVC decode**

- Main, Main 10, Main Still Picture, every RExt profile and every SCC profile: 4:0:0 to 4:4:4, 8 to 16 bit.
- MV-HEVC, SHVC and 3D-HEVC, plus alpha and depth auxiliary layers (Apple HEVC with alpha included).
- Dolby Vision streams decode as their base layer.
- Film grain, decoded picture hash checks, and threads across pictures, rows and planes.

**HEVC encode**

- Main, Main 10, Main Still Picture, Main 10 Still Picture, Main 4:2:2 10, Main 4:4:4, Main 4:4:4 10 and Main 4:4:4 Still Picture.
- 4:2:0, 4:2:2 or 4:4:4 at 8 or 10 bit; lossless in any profile.
- Up to 7 B-frames in a pyramid, SAO, deblocking and wavefront threads.
- Constant quality, average or constant bitrate; HDR mastering and light-level SEI.
- Presets `forFile()`, `forRealtime()` and `forStillPicture()`; same stream for any thread count.

**VVC decode**

- Main 10 and Main 10 Still Picture: 4:0:0 or 4:2:0, 8 to 10 bit, every version 1 tool.
- Slices, tiles, subpictures, wavefront and reference picture resampling (the size may change mid-stream).
- Film grain, HDR SEI, captions and decoded picture hash checks.

</details>

<details>
<summary><b>MPEG-1/2, MPEG-4 Part 2, H.263 and VC-1 in detail</b></summary>

**MPEG-1/2**

- MPEG-1 and the Simple, Main, High and 4:2:2 profiles at every level.
- Frame and field pictures, dual prime, 4:2:0 to 4:4:4, ATSC captions.

**MPEG-4 Part 2 decode**

- Simple and Advanced Simple, with global motion, quarter sample, interlace and B-VOPs.
- Shape, sprites, N-bit and scalability tools of the other profiles, plus Studio I-VOPs.

**MPEG-4 Part 2 encode**

- Simple and Advanced Simple, up to 4 B-VOPs, quarter sample, data partitioning, RVLC.

**H.263**

- Decode: baseline and every optional coding mode (Annexes C-G, I-K, M-V), custom formats and clocks.
- Encode: baseline and H.263v2 custom sizes up to 2048x1152; annexes D, F, I, J, K and T.

**VC-1**

- Simple, Main and Advanced at every level, interlaced field and frame pictures, exact output.

</details>

<details>
<summary><b>HEIC and JPEG XS in detail</b></summary>

**HEIC decode**

- HEVC, H.264, layered HEVC and uncompressed (`unci`) items; JPEG, JPEG 2000 and AV1 items with the artifact that decodes them.
- Grids, overlays, alpha, clean aperture, ICC and HDR metadata, EXIF and XMP.
- 10-bit 4:2:2 `.hif` files, region decode, and thumbnails through the reduced-size hint.
- Gain maps (ISO and Apple), stereo views, auxiliary images, image sequences, and collections as pages.

**HEIC encode**

- Main Still Picture (8 bit) or Main 10 Still Picture (10 bit); quality 0-100 and 4 speeds.
- Lossless keeps RGB as 4:4:4, or the source's chroma format for YCbCr pictures.
- Alpha, ICC, EXIF, XMP, orientation and HDR metadata.
- Pictures past the HEVC level 5.1 limit are split into a grid of 512 px tiles.
- Animations through `HeicEncoder.openAnimationWriter`, with a loop count.

**JPEG XS**

- Decode: 1 to 8 components of 1 to 16 bit; 4:4:4, 4:2:2, 4:2:0, gray and Bayer; bit-identical to the reference.
- Encode: lossless or a bits-per-pixel rate, and Bayer data through `JpegXsEncoder.bayer`.
- Video encode codes 4:2:2 (Main 4:2:2 10) at up to 10 bit with an even width, else 4:4:4 12-bit (Main 4:4:4 12).
- Video encode converts the input to that layout, whatever its own chroma format.

</details>

<details>
<summary><b>Audio in detail</b></summary>

**AAC**

- Decode: 960 and 1024-sample frames, 7.35-96 kHz, channel configurations 1-7 and 11-14, and program config elements.
- Decode: error protection (CRC, Viterbi, Reed-Solomon), BSAC up to 63 layers, scalable AAC up to 8 layers.
- Decode: USAC frequency and linear-prediction domains, eSBR, and MPEG-D loudness control.
- Decode: MPEG Surround trees 5-1-5 to 7-5-7, arbitrary trees, and binaural output (KEMAR, VAST, MPSVT).
- Decode: dynamic range control (light or heavy) and loudness normalization to a target level.
- Encode: LC, HE-AAC, HE-AAC v2, LD and ELD; CBR or VBR; layouts up to 22.2 and 7.1.4.
- Encode: three ways to signal SBR (implicit, backward compatible, hierarchical).

**AC-3, E-AC-3 and DTS**

- AC-3 decode: every channel mode, coupling, downmix to Lo/Ro, Lt/Rt or mono, dialogue normalization.
- AC-3 encode: 32, 44.1 and 48 kHz, mono to 3/2 with LFE, Annex D metadata, Dolby-named DRC profiles.
- E-AC-3 decode: dependent substreams (7.1 and up), program select, spectral extension, enhanced coupling, AHT.
- E-AC-3 decode: `objectAudio()` reports Atmos object audio.
- E-AC-3 encode: mono to 5.1 in one substream; 7.1 and 5.1.2 add a dependent substream.
- E-AC-3 encode: layouts with more extra channels, such as 7.1.4, use several dependent substreams.
- DTS: core up to 5.1 and 48 kHz, plus XCh, XXCH, X96, XBR, and XLL lossless bit for bit.
- DTS Express (LBR) at 8-48 kHz.

**AMR and the MPEG-4 speech codecs**

- AMR-NB: 8 modes, 4.75-12.2 kbit/s; AMR-WB: 9 modes, 6.6-23.85 kbit/s; DTX and concealment.
- AMR encode with DTX; AMR-NB offers VAD option 1 or 2.
- CELP: MPE and RPE, up to 3 bitrate layers, a bandwidth layer to 16 kHz.
- TwinVQ: mono or stereo, scalable layers.
- HVXC: 2 to 4 kbit/s, speed and pitch change, low delay mode.

</details>

## Known limits

- VVC decodes Main 10 only: no 4:2:2 or 4:4:4, nothing past 10 bit, no multilayer streams.
- The HEVC encoder writes 8 or 10 bit, progressive, one slice per picture, no tiles.
- The H.264 encoder has no weighted prediction, and B macroblocks use 16x16 partitions only.
- JPEG XS TDC (temporal) profiles neither decode nor encode.
- E-AC-3 Atmos objects are not rendered: you get the bed.
- `AudioReader` decodes one track. For AAC, CELP, TwinVQ or HVXC layers in separate tracks, build `AacDecoder` (or its sibling) with every track.

<details>
<summary><b>H.264</b></summary>

- CABAC slice data partitioning throws `MediaException.Unsupported`.
- Deeper than 8-bit output is rounded to 8 bit unless `highBitDepth` is set.
- SVC layers decode only as 4:2:0 8 bit, on the calling thread.
- 3D-AVC texture tools with field coding, more than 8 bit or non-4:2:0 drop those views.
- Film grain matches SMPTE RDD 5 exactly only for 8-bit 4:2:0.
- A stream that starts on a non-IDR picture decodes from the first I picture or recovery point.
- Encoder: lossless needs High 4:4:4 Predictive; separate planes and the identity matrix need 4:4:4.
- Encoder: no B-frames with Constrained Baseline, CAVLC, real-time mode, intra refresh or temporal layers.
- Encoder: streams other than 8-bit 4:2:0 progressive lossy get no lookahead and no B pyramid.
- Encoder: interlaced coding needs a level from 2.1 to 4.1.

</details>

<details>
<summary><b>HEVC and VVC</b></summary>

- HEVC with an external base layer (one carried outside the stream) throws `Unsupported`, except as a layered HEIC item.
- Non-backward-compatible Dolby Vision throws `Unsupported` unless `dolbyVisionBaseLayer` is set.
- A stream that starts at a recovery point outputs nothing before it.
- HEVC encoder: no monochrome, 12 bit, SCC, interlace, AMP, scaling lists, PCM or long-term references.
- HEVC encoder: no transform skip, adaptive quantization, weighted prediction or RDOQ.
- HEVC encoder: no B-frames in real-time mode or with still picture profiles.
- VVC: no palette or range extension tools.
- VVC: single-substream slices decode on the calling thread; no picture-level parallelism.

</details>

<details>
<summary><b>MPEG-1/2, MPEG-4 Part 2, H.263 and VC-1</b></summary>

- MPEG-2 scalable extensions throw `Unsupported`; pan-scan is not applied.
- Raw `.m1v` and `.m2v` files have no demuxer: use a container.
- MPEG-1/2 samples match the ISO reference only with the same IDCT.
- MPEG-4 Part 2 samples match other decoders only with the same IDCT (FFmpeg with `-idct int`).
- MPEG-4 Part 2 shape, sprite and scalability tools follow the ISO reference decoder's floating-point IDCT.
- MPEG-4 Part 2 decode: no Core Studio P-VOPs, Studio shape or sprites, or FGS.
- MPEG-4 Part 2 encode: one rectangular, progressive 8-bit layer; B-VOPs in Advanced Simple only.
- H.263 encode: I and P pictures only.
- Sorenson Spark: the deblocking flag is not applied.
- VC-1: 4:2:0 only; the WMV9 complex profile is rejected.

</details>

<details>
<summary><b>HEIC and JPEG XS</b></summary>

- HEIC JPEG and JPEG 2000 items need smalt-image; AV1 items need smalt-video.
- HEIC JPEG 2000 items need 1, 3 or 4 color channels, and an image offset that keeps subsampled chroma whole.
- Uncompressed (`unci`) items decode to at most 16 bits per sample; CMYK becomes RGB without color management.
- A filter array (`cpat`) item decodes to its mosaic as a gray image, not demosaiced.
- The HEIC decoder ignores EXIF orientation; it reports `irot` and `imir` for `ImageOps.orient`.
- HEIC lossy encode is 4:2:0 only; the identity matrix needs lossless.
- HEIC encode writes no thumbnails, depth maps, gain maps or entity groups.
- A HEIC animation plays at most about 71 minutes per loop.
- JPEG XS encode: no nonlinear transform, 4:4:0, float pictures or more than 8 components.
- JPEG XS video encode: progressive only, and alpha is dropped.

</details>

<details>
<summary><b>Audio</b></summary>

- AAC: SBR or PS signaled as object type 5 or 29 around an error-resilient or scalable core is not decoded; the core plays alone.
- AAC-ELD's own low-delay SBR does decode.
- AAC with `epConfig` 1 (error categories in separate tracks) needs `AacDecoder.decode(List, Consumer)`.
- USAC ignores the SBR, compression and MPEG Surround options; PVC in a channel pair throws `Unsupported`.
- The AAC encoder never uses PNS or intensity stereo.
- HE-AAC v2 encode is stereo only; LD and ELD encode channel configurations 1-7 only.
- ADTS carries only configurations 1-7 with 1024-sample frames: use MP4 or Matroska otherwise.
- E-AC-3 downmix uses the 5.1 substream; channel extensions decode only with `Ac3Downmix.NONE`.
- The AC-3 encoder takes 32, 44.1 and 48 kHz only.
- E-AC-3 encode: AHT and enhanced coupling are off by default. Some decoders misread AHT, and ffmpeg rejects enhanced coupling.
- DTS: XLL with 4 frequency bands, above 192 kHz or wider than 24 bit plays its core, or throws `Unsupported` without one.
- HVXC output lags by `delay()`, which no container records.

**Through the generic APIs** (`AudioEncoders`, `AudioWriter`, `Transcoder`)

- AAC encodes as LC (64 kbit/s per channel unless you set a bitrate), or ELD in real-time mode for configurations 1-7.
- Use `AacEncoder` for HE-AAC.
- AMR encodes 8 kHz (NB) or 16 kHz (WB) mono only, without DTX.
- AC-3 and E-AC-3 move unsupported sample rates to 48 kHz and pick the closest layout.
- CELP, TwinVQ and HVXC decode the layers of one track; `epConfig` 1 streams split across tracks are rejected.

</details>

## Patents

The code is MIT-licensed, but some of these standards are covered by patents you license separately.

| Codecs | Patent status |
|---|---|
| H.264, AAC | Via LA AVC and AAC pools |
| HEVC, VVC | Access Advance and Via LA pools |
| HEIC | Codes with HEVC: the HEVC pools apply |
| AMR-WB | VoiceAge AMR-WB pool |
| E-AC-3 | May be covered by Dolby patents |

- The AC-3 patents expired in 2017.
- Other codecs here are not listed; check them yourself.
- This table is not legal advice: check the terms that apply to your product.

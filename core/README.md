<div align="center">

# smalt-core

**The media model, every container, picture and sample processing, and the transcoder**

![Artifact](https://img.shields.io/badge/artifact-smalt--core-2f6fe6?style=flat-square&labelColor=0b2a6f)
![Module](https://img.shields.io/badge/module-com.github.auties00.smalt-2f6fe6?style=flat-square&labelColor=0b2a6f)

[Smalt](../README.md) | **core** | [image](../image/README.md) | [audio](../audio/README.md) | [video](../video/README.md) | [mpeg](../mpeg/README.md) | [capture](../capture/README.md) | [raw](../raw/README.md) | [desktop](../desktop/README.md) | [aot](../aot/README.md) | [bom](../bom/README.md)

</div>

`smalt-core` is the artifact every other one builds on, and the only one with no Smalt dependency. It holds
the media model (`MediaSource`, `MediaSink`, `Picture`, `Track`, `Demuxer`, `Muxer`), the registries that find
codecs at run time, **every container**, and everything that works on decoded pictures and samples.

**Contents:** [What it covers](#what-it-covers) | [Install](#install) | [Guide](#guide) |
[Format coverage](#format-coverage) | [Known limits](#known-limits)

## What it covers

- **Containers:** MP4, MOV, 3GP, Matroska, WebM, Ogg, MPEG-TS, MPEG-PS, AVI, ASF, FLV, MXF, WAV, AIFF, CAF and
  the raw stream formats, read, seeked, probed and (for most) written. [List](#containers)
- **Streaming:** HLS and MPEG-DASH packaging and reading, Common Encryption with clear keys, RTP payload
  formats. [Streaming](#streaming) | [RTP](#rtp-payload-formats)
- **Probing and tags:** `Media.probe` describes any file from its headers; `TagEditor` edits tags, cover art
  and chapters in place. [Tags](#tags-pictures-and-chapters)
- **Picture operations and color:** resize, crop, rotate, filters, compositing, ICC color management, HDR
  gain maps. [Processing](#processing)
- **Audio processing:** resampling, filters, loudness measurement and normalization, PCM.
- **Subtitles:** text and bitmap subtitle formats, conversion and rendering. [Subtitles](#subtitles)
- **Transcoding:** one call that remuxes what it can and re-encodes what it must. [Transcoding](#transcoding)
- **Codecs that need no codec artifact:** PCM, DSD and uncompressed video. [List](#codecs-in-core)

> [!NOTE]
> Core names no codec. Decoders and encoders come from the codec artifacts ([image](../image/README.md),
> [audio](../audio/README.md), [video](../video/README.md), [mpeg](../mpeg/README.md), [raw](../raw/README.md))
> through the `spi` providers, so a format whose artifact is missing fails with a `MediaException.Unsupported`
> that names the artifact to add.

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-core</artifactId>
</dependency>
```

The version comes from the [BOM](../bom/README.md); Gradle coordinates and JVM flags are in the
[root README](../README.md#install).
 Every other Smalt artifact brings core in transitively.

## Guide

One task, one example. Examples that need a codec say which artifact provides it.

### Files and metadata

#### Probe a file

```java
try (var source = MediaSource.of(Path.of("movie.mkv"))) {
    var info = Media.probe(source);
    System.out.println(info.container().orElseThrow() + ", " + info.durationMicros().orElse(-1) + " us, "
            + info.tags().get("title"));
    info.video().ifPresent(video -> System.out.println(video.codec() + " " + video.profile().orElse("")
            + " " + video.displayWidth() + "x" + video.displayHeight() + " " + video.bitDepth().orElse(8) + "-bit"));
    info.audio().ifPresent(audio -> System.out.println(audio.codec() + " " + audio.sampleRate() + " Hz"));
    info.chapters().forEach(chapter -> System.out.println(chapter.startMicros() + " " + chapter.title().orElse("")));
}
```

The probe reads headers only and needs no codec artifact: `smalt-core` alone describes every container
and image Smalt knows, including the cover art (`info.attachedPictures()`).

#### Edit tags

```java
var file = Path.of("song.mp3");
var cover = AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/jpeg", "", Files.readAllBytes(Path.of("cover.jpg")));
var tags = TagEditor.read(file).toBuilder()
        .set(StandardTag.TITLE, "New title")
        .set(StandardTag.ARTIST, "First artist", "Second artist")
        .set(StandardTag.TRACK, "3/12")
        .set(StandardTag.REPLAYGAIN_TRACK_GAIN, "-6.50 dB")
        .set(StandardTag.MUSICBRAINZ_ALBUM_ID, "a1c35a51-d102-4ce7-aca7-a2a1d9c0a2e6")
        .pictures(List.of(cover))
        .addChapter(Chapter.of(0, 90_000_000, "Intro"))
        .build();
var result = TagEditor.write(file, tags);   // IN_PLACE when the padding fits, otherwise REWRITTEN

var movie = Path.of("movie.mkv");
var read = TagEditor.read(movie);
var commentary = read.track(2).orElseThrow().withName("Director's commentary")
        .withDisposition(Set.of(TrackDisposition.COMMENTARY));                        // no longer the default track
TagEditor.write(movie, read.toBuilder().track(commentary).build());                  // the other tracks are kept
```

`TagEditor` reads and writes MP3, ADTS, raw AC-3 and E-AC-3, and AMR (ID3v2.3/2.4, APEv2 and ID3v1), FLAC, Ogg
Vorbis/Opus/FLAC, MP4/M4A/MOV, Matroska/WebM, WAV (RIFF, RIFX, RF64), AIFF and AVI with one vocabulary (`StandardTag`,
the names `Media.probe` reports), and the names, tags and dispositions of the tracks of MP4, Matroska, AVI and MPEG-TS
files;
keys outside the vocabulary keep their native spelling. Only the metadata moves: an edit that fits the existing padding
rewrites the metadata bytes in place, a larger one streams the file to a temporary copy with fresh padding.
Muxers take the same `MediaTags` through `Muxer.setTags(tags)` before the first packet.

#### Strip location from a video

```java
TagEditor.stripLocation(Path.of("IMG_0042.MOV"));     // UNCHANGED when the file records no place

var tags = TagEditor.read(Path.of("clip.mp4"));
tags.get("com.apple.quicktime.make");                  // keyed metadata, under its key name
tags.get("loci");                                      // the 3GPP location, as ISO 6709: "+45.4642+009.19+120/"
tags.xmp().flatMap(xmp -> xmp.text(XmpPacket.DC, "title"));
TagEditor.write(Path.of("clip.mp4"), tags.toBuilder().set("com.apple.quicktime.model", "Camera").build());
```

`stripLocation` removes the location keys of the file and of its tracks (`com.apple.quicktime.location.*`,
the copyright-sign `xyz` atom, `loci`, `location`, `recording_location`) and the coordinates and place names of its XMP packet, and keeps
everything else: the media data does not move unless the metadata outgrows its space. It works on every format
`TagEditor` writes; `MediaTags.hasLocation()` and `MediaTags.Builder.removeLocation()` do the same on tags in memory.

#### Read from a buffer, a memory segment, a stream or a URL

```java
var buffer = MediaSource.of(byteBuffer);     // position to limit; a heap buffer is read in place
var mapped = MediaSource.of(segment);        // native or heap; global, auto or shared arena; over 2 GiB is fine
try (var upload = MediaSource.of(request.getInputStream(), contentLength);  // -1 when the length is unknown
     var reader = AudioReader.open(upload)) {
    // ...
}
var lazy = MediaSource.of(() -> openStream(url), -1);   // opened on the first read, closed by close()
try (var remote = MediaSource.of(URI.create("https://example.com/movie.mp4"));     // range requests
     var demuxer = Containers.openDemuxer(remote)) {
    demuxer.seek(60_000_000);                    // fetches only the blocks the seek reads
}
```

A stream is read once, front to back, while parsers read at any offset, so the source keeps what it has read:
in memory up to 16 MiB, then in a spill file that only the current user can open, that the operating system
deletes even if the process dies, and that is encrypted with a key that exists only in memory.
`StreamSourceOptions` sets the budget and the spill directory, and `StreamSourceOptions.memoryOnly()` never
touches the disk (it fails with `MediaException.LimitExceeded` instead). The demuxers and decoders release what
they have read, so MP3, AAC, WAV, Ogg, FLAC, Matroska, fast-start and fragmented MP4, JPEG and PNG keep a
small window and never spill; an MP4 whose `moov` comes last keeps its media data until the index is read. A
stream cannot seek back past what was released (`MediaException.Unsupported`); open a file with `MediaSource.of(Path)`
when seeking matters.

`MediaSource.of(URI)` reads an HTTP or HTTPS resource with range requests: it fetches blocks of 64 KiB, keeps
16 MiB of them, reads up to 2 MiB ahead of reads that follow one another (one request per window, not per block),
retries failed requests, resumes a response that ends early or stalls past the request timeout, and fails a read
with `MediaException.Io` when the resource changes underneath it (`If-Range`). A server that ignores ranges or states no length is read front to back
as a stream is. `HttpSourceOptions` sets the client (proxy, authentication, TLS), request headers, block, cache and
read-ahead sizes and retries, and `DataReferenceResolver.http(playlistUri, options)` serves the segments, keys and
playlists of an HLS or DASH presentation from the playlist's origin.

### Images and color

#### Make an image thumbnail

```java
try (var source = MediaSource.of(Path.of("photo.jpg"));
     var output = MediaSink.of(Path.of("thumbnail.webp"))) {
    var image = Images.decode(source, ImageDecoderOptions.ofMinimumSize(512, 512));
    var upright = ImageOps.orient(image.picture(), image.orientation());
    var thumbnail = ImageOps.resize(upright, 512, 512 * upright.height() / upright.width(), ResizeFilter.AREA);
    Images.encode(thumbnail, ImageFormat.WEBP, ImageEncoderOptions.ofQuality(80), output);
}
```

`ImageDecoderOptions.ofMinimumSize` lets the JPEG decoder shrink while decoding, so a 48-megapixel photo never
materializes at full size.

#### Decode a region of a large image

```java
// a 1024x768 window of a tiled whole-slide TIFF, at least 512 pixels wide
var options = ImageDecoderOptions.builder()
        .region(40_000, 25_000, 1024, 768)
        .minSize(512, 0)
        .build();
try (var source = MediaSource.of(Path.of("slide.tif"))) {
    var window = Images.decode(source, options);
}
```

Only the tiles that the window meets are read, from the smallest pyramid level that keeps it at least 512 pixels
wide, so the page itself can exceed the decoding limits; JPEG, JPEG 2000, WebP, HEIC and AVIF files decode regions
the same way.

#### Edit a photo

```java
var photo = Images.decode(MediaSource.of(Path.of("photo.jpg"))).picture();
var straight = ImageOps.rotate(photo, -2.5, Interpolation.BICUBIC, RotationCanvas.INSCRIBED, 0);
var square = ImageOps.smartCrop(straight, 1080, 1080, SmartCropStrategy.ATTENTION, ResizeFilter.LANCZOS3);
var crisp = ImageFilters.unsharpMask(square, 1.0, 0.8, 2);
var graded = ImageAdjustments.curves(crisp, ToneCurve.through(0, 0, 0.25, 0.2, 0.75, 0.8, 1, 1));
var logo = Images.decode(MediaSource.of(Path.of("logo.png"))).picture();
var marked = ImageCompositing.watermark(graded, logo, WatermarkOptions.builder().scale(0.2).build());
var gif = ImageQuantization.quantize(marked, QuantizeOptions.ofColors(64)); // palette + indices
```

`ImageFilters` (Gaussian and box blur, unsharp mask, convolution, median, minimum, maximum, rank and mode),
`ImageAdjustments` (brightness, contrast, saturation, levels, curves, auto levels, equalization, grayscale,
sepia, tint, invert, threshold, posterize, solarize, hue rotation, vignette, white balance),
`ImageCompositing` (14 blend modes, opacity, linear light, watermarks) and the geometry of `ImageOps`
(resampling, rotation, affine and perspective transforms, quadrilateral and mesh warps, flips, trim, extend,
pad, flatten, histograms, channel split and merge) work on 8 and 16-bit pictures; on 8-bit pictures the
blurs, unsharp mask, rank and mode filters, enhancements, autocontrast, equalization, posterization,
solarization and histogram statistics reproduce Pillow's output exactly.

#### Show Display P3, Adobe RGB and CMYK photos correctly

```java
var options = ImageDecoderOptions.builder().convertToSrgb(true).build();
var image = Images.decode(MediaSource.of(Path.of("iphone.jpg")), options);   // sRGB pixels, any profile
var frame = ImageOps.toSrgb(videoFrame);                                     // HDR10/HLG frame -> SDR sRGB
var p3 = JpegEncoder.encode(picture, JpegEncoderOptions.builder().colorProfile(ColorProfile.displayP3()).build());
```

Without the option, pixels come back as stored and `DecodedImage.colorProfile()` tells which space they are
in; `ColorTransform` converts between any two ICC profiles (version 2 or 4, matrix/TRC, lookup or
floating-point tables, CMYK) and through chains with device links and abstract profiles, from named colors too,
with the four rendering intents and black point compensation, matching Little CMS 2.17 bit for bit at 8 bits.
PQ and HLG sources are tone-mapped with the BT.2390 EETF; PQ and HLG targets receive standard-dynamic-range
white at the BT.2408 reference white of 203 cd/m2, and PQ and HLG convert into each other through display light:

```java
var hdr10 = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
        ColorPrimaries.BT2020, TransferCharacteristics.PQ));
var pq = ImageOps.convertColor(srgbPicture16, ColorProfile.srgb(), hdr10);         // SDR -> PQ, white at 203 cd/m2
var png = PngEncoder.encode(pq, PngEncoderOptions.builder().colorProfile(hdr10).build());  // cICP + iCCP

var effect = ColorTransform.create(List.of(ColorProfile.srgb(), ColorProfile.parse(abstractIcc),
        ColorProfile.displayP3()));                                  // sRGB -> abstract adjustment -> Display P3
var link = ColorTransform.create(List.of(ColorProfile.parse(deviceLinkIcc)));   // a device link alone
var spots = ColorProfile.parse(namedColorIcc);
var index = spots.namedColor("185 C").orElseThrow().index();         // named colors convert by index
ColorTransform.create(List.of(spots, ColorProfile.srgb())).transform(new short[]{(short) index}, 0, 1, rgb, 0, 3, 1);
```

Soft proofing shows on screen what a printer would make of an image, with out-of-gamut colors flagged; CMYK to CMYK
conversions can keep black text on the black ink; floating-point samples convert without clipping; and profiles
can be built and saved:

```java
var printer = ColorProfile.parse(fogra39Icc);
var preview = ColorTransform.createProofing(ColorProfile.srgb(), printer, ColorProfile.displayP3(),
        ColorTransformOptions.relativeColorimetric(), ProofingOptions.forGamutCheck());   // gray where out of gamut
var reprint = ColorTransform.create(List.of(ColorProfile.parse(swopIcc), printer), ColorTransformOptions.builder()
        .intent(RenderingIntent.RELATIVE_COLORIMETRIC).blackPreservation(BlackPreservation.K_PLANE).build());
ColorTransform.create(ColorProfile.srgb(), ColorProfile.rec2020()).transform(hdrFloats, 0, 3, out, 0, 3, n);
var custom = ColorProfile.rgb(red, green, blue, d65, ProfileCurve.parametric(3, 2.4, 1 / 1.055, 0.055 / 1.055,
        1 / 12.92, 0.04045)).withDescription("My display");
var warmer = ColorProfile.labAdjustment(LabAdjustments.builder().whiteBalance(6500, 5000).saturation(5).build());
var bytes = ColorProfile.deviceLink(preview).toByteArray();                           // a device link to share
```

`Images.encode` records the profile with the pixels, so a picture decoded without conversion keeps its colors
when it is encoded again, and `metadataOf` carries the EXIF block and XMP packet along:

```java
var decoded = Images.decode(MediaSource.of(Path.of("iphone.heic")));                 // Display P3 pixels
var options = ImageEncoderOptions.builder().quality(85).metadataOf(decoded).build();  // profile, EXIF, XMP
var jpeg = Images.encode(decoded.picture(), ImageFormat.JPEG, options);              // Display P3 JPEG
```

### Audio

#### Read audio as PCM

```java
try (var source = MediaSource.of(Path.of("song.mp3"));
     var reader = AudioReader.open(source, AudioReaderOptions.of(48_000, ChannelLayout.STEREO))) {
    AudioBuffer buffer;
    while ((buffer = reader.read()) != null) {
        // buffer.frames() samples per channel, resampled to 48 kHz stereo
    }
}
```

For the source's own samples, bit for bit (a 32-bit FLAC, ALAC or WAV, which floats would round to 24
significant bits), read integers instead, at the codec's rate and layout:

```java
var options = AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).verifyIntegrity(true).build();
try (var reader = AudioReader.open(MediaSource.of(Path.of("master.flac")), options)) {
    IntegerAudioBuffer block;
    while ((block = reader.readIntegers()) != null) {
        // block.plane(c)[i]: a signed sample of block.bitsPerSample() bits; at the end the MD5 is checked
    }
}
```

The reader finds the container and codec by itself, and resamples and remixes on the fly. Every reader
(`Demuxer`, `AudioReader`, `VideoReader`, `AnimationReader`) pulls with `read()`, returns `null` at the end,
and offers `seek(micros)` where the container allows it (`isSeekable()`); a capture session pulls the same
way.

#### Write audio to a file

```java
var options = AudioWriterOptions.of(44_100, ChannelLayout.STEREO); // the format of the blocks written
try (var sink = MediaSink.of(Path.of("song.opus"));
     var writer = AudioWriter.open(sink, OutputContainer.OGG, options)) {
    for (var block = reader.read(); block != null; block = reader.read()) {
        writer.write(block); // blocks of any length; integer and double blocks too
    }
    writer.finish();
}
```

`AudioWriter`, the counterpart of `AudioReader`, opens the encoder and the muxer and keeps the timing: the
codec is the profile's default (Opus for `OutputContainer.OGG`) unless `AudioWriterOptions.codec` names one,
input the codec cannot code is resampled to 48 kHz or mixed to stereo (or to the `outputSampleRate` and
`outputLayout` asked for), and the file marks the encoder's priming and the last frame's padding, so reading it
back returns exactly the samples coded (all but ADTS, AC-3 and AMR elementary streams, and MP3 written to a sink
that cannot seek). Integer blocks reach FLAC, ALAC and PCM encoders unchanged.

#### Measure loudness and normalize

```java
// ffmpeg -i song.flac -af ebur128=peak=true -f null -
var stats = Loudness.measure(MediaSource.of(Path.of("song.flac")));
System.out.printf("%.1f LUFS, LRA %.1f LU, %.1f dBTP%n", stats.integrated(), stats.loudnessRange(), stats.truePeakDb());

// two-pass normalization to -16 LUFS with a -1.5 dBTP ceiling, like ffmpeg's loudnorm in linear mode
var options = TranscodeOptions.of(OutputContainer.M4A).toBuilder()
        .audio(AudioTranscodeOptions.builder().loudnessNormalization(LoudnessNormalization.forPodcast()).build())
        .build();
Transcoder.transcode(Path.of("episode.wav"), Path.of("episode.m4a"), options);

// ReplayGain 2.0: tag an album (R128_* fields for Opus files), then play it at album gain
var gains = Loudness.writeReplayGain(List.of(Path.of("01.flac"), Path.of("02.flac")));
try (var source = MediaSource.of(Path.of("01.flac"));
     var reader = AudioReader.open(source, AudioReaderOptions.builder().replayGain(ReplayGain.Mode.ALBUM).build())) {
    System.out.printf("album gain %.2f dB%n", reader.replayGainDb());
}
```

`LoudnessMeter` measures a stream of `AudioBuffer`s with bounded memory and passes the EBU Tech 3341 and
3342 minimum requirements; `SilenceDetector` finds the stretches below a level that last long enough, like
ffmpeg's `silencedetect`. `LoudnessNormalizer` normalizes a stream in one pass, and `TruePeakLimiter` keeps a
stream's true peak under a ceiling; `ReplayGainScanner` computes the ReplayGain 2.0 gains of an album.

#### Code any audio codec in real time

```java
var decoder = AudioDecoders.create(track);                              // Opus, AAC, MP3, AMR, G.711...
decoder.decode(packet, block -> play(block));
decoder.conceal(gapSamples, block -> play(block));                      // the RTP timestamp gap of lost packets
var encoder = AudioEncoders.create(Codec.AAC, AudioEncoderOptions.builder()
        .format(48_000, ChannelLayout.STEREO).bitrate(96_000).build());
encoder.setTargetBitrate(48_000);                                       // follow the congestion controller
```

`conceal` takes the span of the stream that never arrived, in samples at the output rate, and hands the samples
that replace it to the consumer, so the output keeps its length and the next packet continues smoothly; it
returns the samples it produced, the span rounded to whole concealment units. Before the first packet the span is
silence. `supportsConcealment()` tells whether a decoder implements it (every decoder of the list below does);
`setTargetBitrate` mirrors `VideoEncoder.setTargetBitrate` and throws `MediaException.Unsupported` where the codec
has no live bitrate (FLAC, ALAC, PCM, Vorbis, MP2, AC-3, E-AC-3), and `IllegalStateException` in a
constant-quality mode (MP3 variable bitrate).

- **Opus**
  - *Concealment unit:* 2.5 ms
  - *What replaces a lost packet:* the libopus pitch-based and noise-based concealment, identical to zero-length packets; from decoder complexity 5 the deep PLC for speech, and `OpusDecoder.recover(OpusDred, ...)` rebuilds lost packets from the Deep REDundancy of a later one
- **AAC (every object type except USAC), HE-AAC, HE-AAC v2, MPEG Surround**
  - *Concealment unit:* one block (`frameLength()`)
  - *What replaces a lost packet:* the last spectrum with random signs, falling 6 dB per block and silent from the fifth; the high band repeats its last envelope; MPEG Surround upmixes with the last spatial parameters
- **USAC**
  - *Concealment unit:* one access unit (`frameLength()`)
  - *What replaces a lost packet:* libxaac's concealment in each channel's last coding mode: the last spectrum (random signs from the second unit), or the LPC filters moving toward their mean with the ACELP pitch continued over a random innovation, or the last TCX spectrum or excitation; the output falls by an eighth per unit and is silent from the ninth, then fades back in by an eighth per decoded unit; eSBR repeats its last envelope a quantization step quieter per unit, MPEG Surround 2-1-2 keeps its last parameters, MPEG-D DRC its last gains; a corrupt access unit is concealed from the element that fails
- **MP3, MP2**
  - *Concealment unit:* one frame
  - *What replaces a lost packet:* the last subband samples at half amplitude, halved again per frame and silent from the fifth, as for a frame failing its CRC
- **Vorbis**
  - *Concealment unit:* half the last block
  - *What replaces a lost packet:* a block of the last size with the last spectrum under random signs, halved per block and silent from the fifth
- **AC-3, E-AC-3**
  - *Concealment unit:* one access unit
  - *What replaces a lost packet:* the last good coefficients of each channel with a fade, dependent substreams included
- **AMR-NB, AMR-WB**
  - *Concealment unit:* one 20 ms frame
  - *What replaces a lost packet:* the bad frame handling of TS 26.091 and TS 26.191, or comfort noise during DTX
- **PCM, G.711, FLAC, ALAC, ATRAC9**
  - *Concealment unit:* one sample
  - *What replaces a lost packet:* the last pitch periods repeated and faded to silence over 60 ms, then overlapped with the next packet (ITU-T G.711 Appendix I)

- **Opus:** the explicit bitrate of `setBitrate`, from the next packet, in every bitrate mode
- **AAC:** constant bitrate: the exact average from the next frame, the bit reservoir kept; variable bitrate: the nominal bitrate of the quality; the automatic bandwidth follows; HE-AAC retunes its core and keeps its SBR frequency range
- **MP3:** constant bitrate: the version's bitrate at or below the target; average bitrate: the new target, steered from the next frame by the bitrate of the last quarter second; the automatic bandwidth follows
- **AMR:** the mode at or below the target, from the next frame

### Video

#### Take a video thumbnail

```java
try (var source = MediaSource.of(Path.of("movie.mp4"))) {
    var thumbnail = Thumbnails.at(source, 12_500_000, ThumbnailOptions.fitting(320, 180));
    // thumbnail.picture() is upright RGBA; thumbnail.timestampMicros() the frame shown
    var waveform = Waveform.measure(source, WaveformOptions.ofBuckets(64));
    var bars = waveform.scaled(Waveform.Measure.PEAK, 100); // 64 values from 0 to 100
}
```

`Thumbnails` seeks to the key frame before the time and decodes forward to the frame displayed at it;
`Thumbnails.evenly(source, count, options)` spreads frames over the whole video (measuring the duration from
the packets when the file declares none, which a source read once from an `InputStream` cannot be), and
`bestFrame(true)` on the options skips black or flat frames. Anamorphic video is stretched to its display
shape by its sample aspect ratio. Images and animations work too.

#### Decode video frames

```java
try (var source = MediaSource.of(Path.of("clip.mp4"));
     var reader = VideoReader.open(source)) {
    VideoFrame frame;
    while ((frame = reader.read()) != null) {
        var picture = frame.picture(); // valid until the next read()
    }
}
```

#### Write video to a file

```java
try (var sink = MediaSink.of(Path.of("clip.mp4"));
     var writer = VideoWriter.open(sink, OutputContainer.MP4,
             VideoWriterOptions.of(1280, 720, new Rational(30, 1)))) { // H.264, the profile's default
    for (var picture : pictures) {
        writer.write(picture); // back to back at 30 fps; write(picture, micros) for a timed frame
    }
    writer.finish();
}
```

`VideoWriter`, the counterpart of `VideoReader`, takes pictures of any representation, creates the encoder
(at the first picture when no size is given) and stamps the frames: `write(picture)` continues at the nominal
rate, `write(picture, micros)` and `write(picture, micros, durationMicros)` place a frame at a time. Times are
rounded to the track's time base, the encoder's own unless `VideoWriterOptions.timeBase` sets a finer one
(`Rational.MICROSECONDS`) for variable-rate video. `requestKeyframe()` forces a key frame, and `open(Muxer,
options)` writes through a muxer with options of its own.

### Containers and streaming

#### Remux to MP4 without re-encoding

```java
try (var input = MediaSource.of(Path.of("clip.mkv"));
     var output = MediaSink.of(Path.of("clip.mp4"))) {
    var result = Mp4Remuxer.remux(input, output, RemuxOptions.video());
}
```

The output is written with its index first (fast start), so it plays while it downloads; it is also written
strictly in order, so `MediaSink.of(OutputStream)` works too, for example to stream the result over HTTP.
`RemuxOptions` also takes several audio tracks (`allAudioTracks()`, `audioTracks(ids)`), the 3GPP timed text,
WebVTT and TTML subtitle tracks (`allSubtitleTracks()`, `subtitleTracks(ids)`) and the QuickTime brand, for ProRes,
DNxHD and PCM; the input can also be an MPEG transport stream, an AVI or an Ogg file. The tags, cover art, chapters
and track names of the input are carried over unless `noTags()` drops them (`tags(...)` and `mergeTags(...)`
replace or update them).

#### Remux to WebM

```java
try (var source = MediaSource.of(Path.of("clip.mp4"));
     var demuxer = Containers.openDemuxer(source);
     var output = MediaSink.of(Path.of("clip.webm"));
     var muxer = new MatroskaMuxer(output, MatroskaMuxerOptions.webm())) {
    demuxer.tracks().forEach(muxer::addTrack);
    Packet packet;
    while ((packet = demuxer.read()) != null) {
        muxer.writePacket(packet);
    }
    muxer.finish();
}
```

The same loop writes fragmented MP4 with `new FragmentedMp4Muxer(output)`, or any other format with
`Containers.openMuxer(format, output)`. A WebM file takes VP8, VP9, AV1, Opus and Vorbis; use
`MatroskaMuxerOptions.matroska()` for H.264, HEVC, MPEG-4 Part 2, AAC, FLAC, ALAC, AMR and PCM.

#### Read a .ts

```java
try (var source = MediaSource.of(Path.of("recording.m2ts"));
     var demuxer = new TsDemuxer(source, TsDemuxerOptions.defaults())) {
    System.out.println(demuxer.programs() + ", reading program " + demuxer.program().number());
    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
        // H.264 and HEVC arrive as access units of length-prefixed NAL units (the track's
        // codecConfig() is an avcC/hvcC built from the stream), AAC as raw data blocks
    }
}
```

`Containers.openDemuxer` recognizes 188, 192 (M2TS) and 204-byte packets on its own; `program(n)` on the
`TsDemuxerOptions` builder picks another program of a multi-program broadcast capture. Timestamps start at
zero and stay continuous across the 33-bit wrap and splices, `seek` works on any file, and
`new TsMuxer(sink)` writes the packets of any demuxer back as a transport stream (`TsMuxerOptions.m2ts()`,
`muxRate(bits)` for constant bit rate).

#### Walk the packets of any container

```java
try (var source = MediaSource.of(Path.of("movie.webm"));
     var demuxer = Containers.openDemuxer(source)) {
    System.out.println(demuxer.format() + " " + demuxer.tracks());
    Packet packet;
    while ((packet = demuxer.read()) != null) {
        // packet.trackId(), packet.data(), packet.pts(), packet.keyframe()
    }
}
```

#### Package HLS

```java
try (var high = MediaSource.of(Path.of("clip-1080p.mp4"));
     var low = MediaSource.of(Path.of("clip-480p.mp4"))) {
    Packager.pack(List.of(Rendition.of("1080p", high), Rendition.of("480p", low)),
            PackageOutput.directory(Path.of("site/clip")),
            PackagingOptions.hls());
}
// site/clip/master.m3u8, 1080p.m3u8, 1080p_00001.ts, ..., 480p.m3u8, 480p_00001.ts, ...
```

`PackagingOptions.cmaf()` writes CMAF segments with both an HLS master playlist and a DASH `manifest.mpd`,
`dash()` DASH alone, `byteRanges(true)` one file per stream addressed by byte ranges, and `dashOnDemand()` the
DASH on-demand profile (one file per stream indexed by a single `sidx`, addressed by `SegmentBase`). Segments are cut
at key frames, so encode the renditions with aligned closed GOPs (for example with `Transcoder`) to let players
switch between them. Alternate audio and subtitles are renditions of their own:

```java
try (var video = MediaSource.of(Path.of("clip-720p.mp4"));
     var english = MediaSource.of(Path.of("english.m4a"));
     var spanish = MediaSource.of(Path.of("spanish.m4a"));
     var captions = MediaSource.of(Path.of("captions.srt"))) {
    Packager.pack(List.of(Rendition.of("720p", video),
                    Rendition.builder(english).kind(Rendition.Kind.AUDIO).name("en").label("English")
                            .defaultRendition(true).build(),
                    Rendition.builder(spanish).kind(Rendition.Kind.AUDIO).name("es").label("Espanol").build(),
                    Rendition.builder(captions).kind(Rendition.Kind.SUBTITLES).name("cc").language("en").build()),
            PackageOutput.directory(Path.of("site/clip")),
            PackagingOptions.cmaf().toBuilder().trickPlay(true).build());
}
// read it back: one track per stream, on one timeline
try (var demuxer = HlsDemuxer.open(Path.of("site/clip/master.m3u8"))) {
    demuxer.tracks();
}
``` Each CMAF initialization segment carries the rendition's tags, cover art and chapters, and
the transport stream segments its track names, unless `PackagingOptions.Builder.noTags()` drops them.

### Transcoding and subtitles

#### Transcode

```java
// ffmpeg -ss 10 -to 40 -i clip.mp4 -c:v libvpx-vp9 -c:a libopus -vf scale=-2:480 clip.webm
var options = TranscodeOptions.of(OutputContainer.WEBM).toBuilder()
        .range(10_000_000, 40_000_000)
        .video(VideoTranscodeOptions.builder().maxSize(0, 480).build())
        .listener(progress -> System.out.println(progress.fraction()))
        .build();
var result = Transcoder.transcode(Path.of("clip.mp4"), Path.of("clip.webm"), options);
```

Each stream is copied when the output can carry it and no option changes it, and re-encoded otherwise
(`StreamMode` forces either). Trims are exact to the frame and the sample on encoded streams; copied streams
start at a key frame and rely on the MP4 edit list for an exact start. Codecs come from the registries, so the
artifacts on the module path decide what is available, and a missing one fails before anything is written,
naming the artifact. `Transcoder.job(...)` returns a job that another thread can `cancel()`, and
`Transcoder.transcodeImage(...)` converts, scales, crops and fits images to a byte budget:

```java
try (var input = MediaSource.of(Path.of("photo.jpg"));
     var output = MediaSink.of(Path.of("photo.webp"))) {
    Transcoder.transcodeImage(input, output, ImageTranscodeOptions.of(ImageFormat.WEBP).toBuilder()
            .maxLongEdge(1600)
            .maxBytes(300_000)
            .metadata(MetadataMode.STRIP_LOCATION)   // keep EXIF and XMP, drop the GPS position
            .build());
}
```

The output keeps the color profile of the input (or holds its pixels converted to sRGB with
`color(ColorMode.CONVERT_TO_SRGB)`) and its EXIF block and XMP packet, unless `metadata(MetadataMode.STRIP)`
removes them.

#### Join, combine, speed up and fade

```java
// ffmpeg -f concat -i parts.txt -c copy joined.mkv
Transcoder.concatFiles(List.of(Path.of("part1.mkv"), Path.of("part2.mkv")), Path.of("joined.mkv"),
        TranscodeOptions.remux(OutputContainer.MATROSKA));

// ffmpeg -i video.mp4 -i voice.wav -i subs.srt -map 0:v -map 1:a -map 2:s dubbed.mkv
try (var video = MediaSource.of(Path.of("video.mp4"));
     var voice = MediaSource.of(Path.of("voice.wav"));
     var subs = MediaSource.of(Path.of("subs.srt"));
     var output = MediaSink.of(Path.of("dubbed.mkv"))) {
    Transcoder.transcode(List.of(TranscodeInput.of(video), TranscodeInput.of(voice), TranscodeInput.of(subs)), output,
            TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder()
                    .audio(AudioTranscodeOptions.builder().input(1).build())
                    .build());
}

// ffmpeg -i talk.mp4 -af "silenceremove,atempo=1.25,highpass=f=80,afade=t=out:d=2" -vf setpts=PTS/1.25 talk.m4a
Transcoder.transcode(Path.of("talk.mp4"), Path.of("talk.m4a"), TranscodeOptions.of(OutputContainer.M4A).toBuilder()
        .speed(1.25)
        .silenceTrim(SilenceTrim.shorteningPauses(700_000))
        .audio(AudioTranscodeOptions.builder()
                .filters(List.of(Biquad.highPass(80), Biquad.peaking(3_000, 1, 3)))
                .fades(List.of(Fade.in(500_000), Fade.out(2_000_000)))
                .build())
        .build());
```

Several `TranscodeOutput`s written from one read of the input give the renditions of an adaptive streaming
ladder, decoding the video once; `Packager` then packages them.

#### Convert subtitles

```java
// ffmpeg -i movie.mkv -map 0:s:0 movie.srt
Transcoder.transcode(Path.of("movie.mkv"), Path.of("movie.srt"));

// read the cues of any file with a subtitle track, in SubRip markup
for (var cue : Subtitles.read(MediaSource.of(Path.of("clip.mp4")),
        SubtitleReaderOptions.builder().markup(SubtitleMarkup.SUBRIP).build())) {
    System.out.println(cue.startMicros() + " " + Subtitles.plainText(cue.text(), SubtitleMarkup.SUBRIP));
}
```

A transcode keeps every subtitle track the output can carry, copying it or converting it (SubRip and ASS
become 3GPP timed text in MP4 and WebVTT in WebM); `SubtitleTranscodeOptions` selects, converts or drops them.

## Format coverage

Packages are relative to `com.github.auties00.smalt`. In *Source*, "spec" means the code was written from the
specification alone; a named project means code was ported or derived from it (see
[Acknowledgments](../README.md#acknowledgments)).

### Containers

All the containers below live in `smalt-core`, so demuxing, seeking, probing and muxing never need a codec
artifact; other artifacts and applications add containers through the `spi.ContainerProvider` interface
(see [Container providers](#container-providers)). "Seek" is how `Demuxer.seek` finds the key frame at or before a time (every track
restarts at its own); "Probe" is the metadata `Media.probe` reads beyond the streams (every
container gives codec, size, color, rate, channels and language; the codec configuration records
give profile, level and bit depth, and the H.264 and HEVC sample aspect ratio when the container
declares none). A video track's `sampleAspectRatio()` and `interlacing()` (progressive, or
interlaced with the QuickTime and Matroska field order) are the container's own fields, listed per
container below; each muxer that has the fields writes them from the track.

<details>
<summary><b>MP4, MOV, 3GP, M4A, MJ2</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes, including fragmented files and Motion JPEG 2000 files (the signature box ahead of an `mjp2` brand); every packet numbered with the index of its sample in its track (`Packet.sampleIndex`); QuickTime compressed movies (`cmov`, zlib) and the compressed boxes of ISO/IEC 14496-12 (`!mov`, `!mof`, `!six`, `!ssx`); tracks whose samples are in other files (reference movies: `url `, `urn ` and `alis` data references, opened through `DemuxerOptions.dataReferences`, such as `DataReferenceResolver.directory`); a layered HEVC track's `lhvC` record joins the `hvcC` record in its codec configuration (and the muxer writes it back); VVC tracks (`vvc1`, `vvi1`) with their `vvcC` record; scalable and multiview H.264 tracks (`svcC`/`mvcC` merged into the track configuration, `svc1`/`mvc1`/`mvc3` tracks and `scal` layered tracks rebuilt with their extractors and the parameter sets of the tracks they reference); Common Encryption (`cenc`, `cbc1`, `cens`, `cbcs` and PIFF) decrypted with the keys of `DemuxerOptions.decryptionKeys` (see below)
- **Seek:** exact, from the sample tables and fragment runs (sync samples, edit lists)
- **Probe:** `ilst`, `udta` and `keys` tags, `covr` cover art, the track `udta` names, DASH role `kind` boxes and `tkhd` enabled flags as track names and dispositions, `chpl` and QuickTime chapter tracks, track matrix rotation, `pasp` sample aspect ratio, QuickTime `fiel` field order, Spherical Video V2 `st3d` stereo mode and `sv3d` spherical projection (equirectangular, cubemap, mesh, with the pose), the Spatial Audio `SA3D` ambisonic layout (ACN, SN3D, head-locked stereo, any channel map), the QuickTime `chan` higher-order ambisonics and B-format layout tags and channel labels (read and written), the QuickTime `tmcd` timecode track as `timecode()`, frame and byte counts
- **Write:** DTS and DTS-HD tracks (`dtsc`, `dtsh`, `dtsl`, `dtse` with the `ddts` box) read; MP4/M4A muxer (index first, fast start) of every codec the demuxer reads but Vorbis and DTS: H.264, HEVC, VVC (`vvc1` with `vvcC`), VP8 and VP9 (`vp08`/`vp09`, `vpcC` derived from the first key frame when the track has none), AV1, MPEG-4 Part 2 (`mp4v`, `esds` from the track or the headers of the first frame), MPEG-1 and MPEG-2 video (`mp4v` of object type `0x6A` or of the profile's, `0x60` to `0x65`, `esds` from the track or the first frame's sequence header), H.263 (`s263` with `d263`), Motion-JPEG (`mp4v` with the JPEG object type), Motion JPEG 2000 (`mjp2` with the `jp2h` box of the track, the first frame or its main header), JPEG XS (`jxsm` with the `jpvi` and `jxpl` boxes of the track, or of the first codestream), ISO/IEC 23001-17 uncompressed video (`uncv` with the `cmpd`, `uncC` and `cmpC` boxes of the track), AAC, MPEG-4 CELP, Opus, MP3, MP2, ALAC, FLAC, AMR as `samr`/`sawb` with a `damr` mode set, AC-3 and E-AC-3 as `ac-3`/`ec-3` with `dac3`/`dec3` built from the first access unit (Atmos extension included), 16/24/32-bit integer and 32/64-bit float PCM as `ipcm`/`fpcm` with `pcmC` and `chnl` (ISO/IEC 23003-5; MP4 only, M4A carries no PCM), `tx3g` and `wvtt` text (the track size in `tkhd`), TTML as `stpp` (a `subs` box delimiting the PNG images of IMSC image documents); `pasp`, `fiel`, `st3d` and `sv3d` for every video codec when the track declares them; `SA3D` for every audio codec whose track has an ambisonic layout of every component of its order in SN3D (Opus mapping family 3 keeps the RFC 8486 demixing matrix after the stream counts of `dOps`, see Known limits); a QuickTime `tmcd` timecode track, which the video tracks reference, from a `timecode` tag (`Mp4Muxer`, and `FragmentedMp4Muxer` with the sample in the first fragment); QuickTime MOV (`Mp4MuxerOptions.quickTime()`), adding ProRes, DNxHD, DV (`dvc `, `dvcp`, `dvpp`, `dv5n`, `dv5p`, `dvh6`, `dvh5`, `dvhp` or `dvhq`, from the format of the first frame), `jpeg`/`mjpa`/`mjpb` Motion-JPEG, `h263`, and every PCM format, 8-bit, A-law and mu-law included, as `twos`/`sowt`/`raw `/`in24`/`in32`/`fl32`/`fl64`/`alaw`/`ulaw` sound descriptions or version 2 `lpcm` with a `chan` layout, and `ima4`, GSM 06.10 (`agsm`) and MACE (`MAC3`, `MAC6`) as version 1 sound descriptions; 3GPP files (`Mp4MuxerOptions.threeGpp()`: the `3gp6` brand, compatible with `3gp5`, `3gp4` and `isom`) of H.263, MPEG-4 Part 2, H.264, AMR, AAC and timed text; Motion JPEG 2000 files (`Mp4MuxerOptions.mj2()`: signature box, `mjp2` brand, frames stored as their `jp2c` boxes, `mjp2` entries with `jp2h`, `fiel`, `jsub` and `orfo`) of Motion JPEG 2000 video and compressed audio; tracks declared after the first packet (`Muxer.supportsLateTracks`), which the movie header written at the end lists; lossless remux to MP4, M4A and MOV from MP4, Matroska, MPEG-TS, MPEG-PS, AVI and Ogg (several audio tracks, text and TTML subtitles, with every audio or subtitle selection also the tracks the demuxer finds while reading; protected MP4 inputs decrypted with `RemuxOptions.decryptionKeys`); fragmented MP4 and CMAF (one file, or init and media segments) of the same codecs, with ProRes and DNxHD, and a hierarchical `sidx` past 65535 fragments, encrypted with Common Encryption on request (`FragmentedMp4MuxerOptions.encryption`); `colr` with the HDR10 `mdcv` and `clli` boxes
- **Package:** `container.mp4`
- **Source:** spec (ISO/IEC 14496-12, -14, -15)

</details>

<details>
<summary><b>Matroska, WebM</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes; `ContentEncodings` of frames and codec private data undone: header stripping, zlib, bzlib (bzip2) and LZO1X compression, and the AES-CTR frame encryption of WebM (whole and partitioned frames) with the keys of `DemuxerOptions.decryptionKeys`; ordered editions (`EditionFlagOrdered`: the chapters in their order, nested chapters, `ChapterFlagEnabled`), linked segments (`ChapterSegmentUUID` spans and `ChapterSegmentEditionUID` editions) and hard-linked chains (`PrevUUID`, `NextUUID`) played as their virtual timeline (`DemuxerOptions.virtualTimeline` and `edition`; the other files through `DemuxerOptions.dataReferences`, such as `MatroskaDemuxer.open` or `DataReferenceResolver.directory`, and in a transcode `TranscodeOptions.dataReferences`, the input's directory for `Transcoder.transcode(Path, ...)`); an `A_MS/ACM` frame of several IMA or Microsoft ADPCM blocks read as one packet per block; `V_QUICKTIME` tracks of ProRes, DNxHD, Motion JPEG 2000, VVC and JPEG XS (`jxsm`, its `jpvi` and `jxpl` boxes as the configuration and, when the track declares none, its frame rate and field order), and JPEG XS under `V_MS/VFW/FOURCC` with the FourCC `jxsm` and that sample entry after the `BITMAPINFOHEADER`, as ffmpeg copies such a track
- **Seek:** `Cues`, or a scan of the cluster headers when there are none (live WebM); on a virtual timeline, within the part that plays the time
- **Probe:** `Tags` (file and track), the track `Name` and flag elements as track names and dispositions, `Chapters` (every edition that is not hidden, with nested chapters and the titles of each language; `chapters()` is the played edition, on the timeline of an ordered one), image attachments as pictures and the other attachments (fonts) as `attachments()`, a `TIMECODE` tag as `timecode()`, `StereoMode`, `Projection` (the spherical projection and pose, or the rotation of a rectangular one), `DisplayWidth`/`DisplayHeight` (every known `DisplayUnit`, after `PixelCrop`) as the sample aspect ratio, `FlagInterlaced` and `FieldOrder`, `AlphaMode` (`VideoTrack.hasAlpha`) with the alpha frames of `BlockAdditional` 1 as `PacketSideData.alphaFrame`
- **Write:** Matroska and WebM, including live WebM; in Matroska also MPEG-1 and MPEG-2 video (`V_MPEG1`, `V_MPEG2`), MPEG-4 Part 2, Motion-JPEG, Motion JPEG 2000 (`V_JPEG2000`, a frame relying on the track's `jp2h` box written as a JP2 file, or with a copy of it ahead of its two field boxes), H.263 (`V_MS/VFW/FOURCC`), DV (`V_MS/VFW/FOURCC` with `dvsd`, or `dvh1` for DVCPRO HD), ProRes, Theora (`V_THEORA`), FFV1 (`V_FFV1`), DNxHD, JPEG XS (`V_QUICKTIME` with the `jxsm` sample description and the `jpvi` and `jxpl` boxes of the track, or of the first codestream), ALAC, WavPack (`A_WAVPACK4`, packets of whole blocks written without their headers and their correction blocks as `BlockAdditional` 1), AMR, G.711, IMA and Microsoft ADPCM, GSM 06.10, G.722, G.726, ATRAC9 and 64-bit integer PCM (`A_MS/ACM`, the ADPCM, telephony and ATRAC9 formats under the `WAVEFORMATEX` or `WAVEFORMATEXTENSIBLE` a WAVE file stores, one block or superframe per packet), ATRAC1 (`A_ATRAC/AT1`), signed 8-bit, big-endian float and big-endian 64-bit integer PCM (stored unsigned and little-endian) and data tracks whose handler is a Matroska codec id; the final FLAC `STREAMINFO` and ALAC `ALACSpecificConfig` rewritten in place on a seekable sink; tracks declared after the first packet on a seekable sink (`Muxer.supportsLateTracks`, the `Tracks` element written again after the clusters), which a transcode of a demuxer that finds tracks while reading uses; the display size (in pixels when the stretched width is whole, else as a display aspect ratio) and the field order the track declares; `Colour` with chroma siting, `MaxCLL`/`MaxFALL` and `MasteringMetadata`; HDR10+ messages as `BlockAdditions` (ITU-T T.35 `BlockAdditionMapping`); `AlphaMode` 1 and the alpha frames of a transparent track as `BlockAdditional` 1; every chapter edition (default and ordered flags, names, nested chapters, `ChapLanguage` and `ChapLanguageBCP47` titles) and the attachments of the tags; `StereoMode` and the spherical `Projection` the track declares
- **Package:** `container.matroska`
- **Source:** spec

</details>

<details>
<summary><b>Ogg (Opus, Vorbis, FLAC, Speex, Theora)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes, chained files included (`OggDemuxer.links()`: start, duration, tracks, tags, chapters and pictures of every link; links above 64 MiB found by bisection on serial numbers; the later links of a stream read once whose size is unknown or exceeds its memory budget described by `read()` as it reaches them, as tracks appended to `tracks()`); Speex and Theora described and timestamped (Speex decoded by `smalt-audio`, Theora by `smalt-video`); Ogg Skeleton 3 and 4 `fishead` and `fisbone` (message fields as track tags, `Language`, granule rate and shift of unknown streams)
- **Seek:** bisection on granule positions in the link that plays at the target (Theora: then to the key frame its granule position names); only forward, by reading, on a stream read once
- **Probe:** Vorbis comments per track and per link, `CHAPTERxxx`, `METADATA_BLOCK_PICTURE`, Ogg FLAC `PICTURE`; size, rate and pixel aspect ratio of Theora streams
- **Write:** yes (Opus, Vorbis, FLAC, Speex, Theora; the final FLAC `STREAMINFO` rewritten in place on a seekable sink)
- **Package:** `container.ogg`
- **Source:** spec; libogg (page reader)

</details>

<details>
<summary><b>MPEG audio (MP3, MP2, ID3v2)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes
- **Seek:** exact through a frame index up to 64 MiB; Xing/VBRI table or bitrate estimate beyond
- **Probe:** ID3v2 text frames, `APIC`, `CHAP` (appended ID3v2.4 tags included); APEv1/APEv2 text and cover art; ID3v1
- **Write:** raw MP3/MP2 through `RawMuxer` (Xing or Info frame with frame count, size, seek table and a LAME tag with the encoder delay and padding on a seekable output)
- **Package:** `container.mpeg`
- **Source:** spec; minimp3 (free-format frame sizing)

</details>

<details>
<summary><b>ADTS</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes; implicit HE-AAC found in the first frame and declared on the track
- **Seek:** exact through a frame index up to 64 MiB; bitrate estimate beyond
- **Probe:** ID3v2 (appended ID3v2.4 included), APE, ID3v1
- **Write:** yes
- **Package:** `container.adts`
- **Source:** spec

</details>

<details>
<summary><b>LOAS/LATM (<code>.loas</code>, <code>.latm</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes (`LoasDemuxer`): `AudioSyncStream` frames with in-band `StreamMuxConfig`, any number of programs and layers, chunked payloads, fixed frame lengths and the CELP and HVXC frame length tables (the first stream becomes the track); AAC Main, LC, SSR and LTP, alone or under HE-AAC and HE-AAC v2, and CELP and ER CELP base layers (`epConfig` 0, or 2 and 3 with the error protection tool and payload lengths in `PayloadLengthInfo`); a configuration change reaches the next packet as a new codec configuration; resynchronization on damaged data
- **Seek:** exact through a frame index up to 64 MiB
- **Probe:** ID3v2 text frames ahead of the stream
- **Write:** yes (`LoasMuxer`): AAC and CELP base layer tracks, one subframe per frame, `audioMuxVersion` 0 (1 when the configuration carries explicit SBR signaling), the `StreamMuxConfig` repeated every `LoasMuxerOptions.configInterval()` frames and on a configuration change, any sink
- **Package:** `container.aac`
- **Source:** spec (ISO/IEC 14496-3 clause 1.7)

</details>

<details>
<summary><b>ADIF (<code>.adif</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes (`AdifDemuxer`): the program configuration element of the header as the configuration, raw data blocks split by walking their syntax, HE-AAC and HE-AAC v2 found in the first block
- **Seek:** exact through a block index up to 16 MiB
- **Probe:** ID3v2 text frames ahead of the stream
- **Write:** yes (`AdifMuxer`): AAC Main, LC, SSR and LTP of channel configurations 0 to 7 (explicit SBR and PS written as their core with implicit signaling), the peak bit rate written on a seekable sink
- **Package:** `container.aac`
- **Source:** spec (ISO/IEC 13818-7, ISO/IEC 14496-3 subclause 1.A.2)

</details>

<details>
<summary><b>WAV, AIFF</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: integer PCM of 8 to 32 bits and of 64 bits, 32 and 64-bit float, A-law and mu-law (AIFF and AIFF-C, WAV with `WAVE_FORMAT_EXTENSIBLE` channel masks), AIFF-C `ima4`, `GSM `, `MAC3` and `MAC6` as packets of whole frames (the `COMM` count counting frames), Ambisonic B-format WAV (the Furse-Malham layouts of `Ambisonics.ofFurseMalham`, also written), BW64 ADM metadata (`chna`, `axml`, ITU-R BS.2076, BS.2088 and the BS.2094 common definitions) read as the HOA layout of the PCM (SN3D, N3D or Furse-Malham, with a head-locked front pair), and WAV of coded audio: MPEG audio, AC-3 and E-AC-3 (tag `0x2000` and the DirectShow sub-formats), AAC in ADTS or LOAS framing or unframed (raw data blocks, split by walking their syntax, also after an ADIF header), FLAC, AMR-NB and AMR-WB, Opus in the self-delimiting framing of RFC 6716, DTS frames (tag `0x2001`, tag `0x0008` when its data holds frames rather than bursts, and the 16-bit or 14-bit DTS frames of a DTS audio CD in 16-bit stereo PCM, as `Codec.DTS` packets, DTS-HD extension substreams included), the ADPCM and telephony formats as packets of `Codec.ADPCM_IMA_WAV` (IMA ADPCM `0x0011`), `Codec.ADPCM_MS` (Microsoft ADPCM `0x0002`, its coefficient table as the configuration), `Codec.GSM_MS` (GSM 06.10 `0x0031` in 65-byte blocks), `Codec.ADPCM_G722` (`0x028F`) and `Codec.ADPCM_G726` (`0x0045`, codes of 2 to 5 bits), a block or whole codes per packet, cut at the `fact` count, ATRAC9 (`.at9` files: the ATRAC9 `WAVEFORMATEXTENSIBLE` sub-format, one superframe of the configuration word's size per packet, the `fact` chunk's encoder delay as `AudioTrack.skipSamples` and its count as the duration), and IEC 61937 (S/PDIF) bursts in 16-bit stereo PCM or the 8 channels of the high bit rate formats, declared or detected: AC-3, E-AC-3, MPEG-1 and MPEG-2 audio, MPEG-2 AAC (ADTS) and MPEG-4 AAC (LOAS), DTS types I to IV (DTS-HD) and Dolby TrueHD in MAT frames (`Codec.TRUEHD`, one packet per access unit)
- **Seek:** exact, sample frame arithmetic; coded audio through its elementary stream's index or a walk of its frames (IEC 61937 bursts from their position on the IEC 61937 timeline; TrueHD from the last major sync before the target)
- **Probe:** `LIST INFO`, AIFF text chunks, embedded ID3v2, the Broadcast Wave `bext` chunk (EBU Tech 3285), `iXML`, `axml`, `chna` (ITU-R BS.2088) and `smpl` chunks, cue points (`cue ` with `LIST adtl` labels and regions) as chapters, AIFF `MARK` markers as chapters, `INST` and `COMT`, the `fact` sample count of coded audio
- **Write:** WAV and RF64: PCM up to 64-bit integers (big-endian samples stored with their bytes swapped, signed 8-bit ones as unsigned), A-law, mu-law, and coded audio as the reader takes it back packet for packet: MP2 (`MPEG1WAVEFORMAT` with the Broadcast Wave `mext` chunk), MP3 (`MPEGLAYER3WAVEFORMAT`), AC-3 (`0x2000`), E-AC-3 (the `MEDIASUBTYPE_DOLBY_DDPLUS` sub-format), AAC Main, LC, SSR and LTP (alone or under SBR and PS) as ADTS frames (`0x1600`) or, when ADTS cannot express its configuration, raw data blocks after an `HEAACWAVEINFO` (`0x1610`) describing the core stream, FLAC, Opus (self-delimiting framing), AMR-NB and AMR-WB (multichannel files with the RFC 4867 multichannel magic), DTS, IMA and Microsoft ADPCM blocks (`IMAADPCMWAVEFORMAT`, `ADPCMWAVEFORMAT` with the configuration's or the standard coefficients), GSM 06.10 (`GSM610WAVEFORMAT`), G.722 and G.726, ATRAC9 (`.at9`: the ATRAC9 sub-format with its configuration word, and the `fact` chunk with the encoder delay), each with its `fact` sample count, the ADPCM headers as ffmpeg writes them (average byte rates as Microsoft defines them) and decoded by ffmpeg to the same samples; or, under `WavMuxerOptions.forIec61937()` or the extensible form, IEC 61937 (S/PDIF and HDMI) data bursts in 16-bit PCM (stereo, or 8 channels for the high bit rate formats) declared as PCM or by `WAVEFORMATEXTENSIBLE_IEC61937` with the codec's sub-format: AC-3, E-AC-3 (six-block groups), MPEG-1 and half-rate MPEG-2 audio, AAC as ADTS frames, DTS core frames, DTS-HD (type IV at the high bit rate) and Dolby TrueHD in MAT frames, the bursts identical to ffmpeg's `spdif` muxer; AIFF and AIFF-C, `ima4`, `GSM `, `MAC3` and `MAC6` included; the descriptive chunks and the chapters of the tags
- **Package:** `container.wav`, `container.aiff`
- **Source:** spec (Microsoft RIFF and `mmreg.h`/`ksmedia.h` format tags and sub-formats, EBU Tech 3285 Supplement 1 (MPEG audio in Broadcast Wave), EBU Tech 3306, IEC 61937-1 to -5, -9 and -11 data types, ISO/IEC 14496-3 LOAS, raw data blocks and ADIF, ETSI TS 102 114, RFC 6716 Appendix B; ffmpeg's `spdif` muxer as a black box for the MAT frame layout)

</details>

<details>
<summary><b>Sony Wave64 (<code>.w64</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes, by `WavDemuxer`: the codecs of WAV in GUID-named chunks with 64-bit sizes (`fmt `, `fact`, `data`), a zero `data` size read to the end of the file
- **Seek:** as WAV
- **Probe:** the `summarylist` chunk (`INFO` identifiers with UTF-16LE values, as ffprobe reads it), the `list` chunk (`INFO` and the `adtl` cue labels), the `id3 `, `bext`, `iXML`, `axml`, `chna`, `smpl` and `cue ` chunks as in WAV
- **Write:** yes (`Wave64Muxer`): the PCM and coded audio of `WavMuxer`'s default options, with a 64-bit `fact` count; tags in a `summarylist` chunk, an `id3 ` chunk for pictures and keys it cannot store, the WAV descriptive chunks and cue points (`cue ` and `list adtl`), no XMP; sizes written on a seekable sink, zero (read to the end) on a streaming one
- **Package:** `container.wav`
- **Source:** spec (Sony Wave64 GUIDs); the `summarylist` layout from ffprobe as a black-box reader

</details>

<details>
<summary><b>CAF (<code>.caf</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: linear PCM (8 to 64-bit integers, 32 and 64-bit floats, either byte order), A-law, mu-law, `ima4` (`Codec.ADPCM_IMA_QT`), AAC (the `kuki` elementary stream descriptor, or the object type of the format flags; HE-AAC at the rate and layout of the richest `ldsc` layer, or of its first decoded packet), ALAC (bare or atom-wrapped cookie), Opus (`OpusHead` cookie, or one built from the channel count), MPEG audio, AC-3 and E-AC-3; packets of variable size through the `pakt` table, its priming and remainder frames as `skipSamples` and duration; a `data` chunk of size -1 read to the end
- **Seek:** exact for linear PCM, A-law and mu-law, otherwise to the packet that holds the frame
- **Probe:** `chan` layout (layout tags, bitmaps, channel descriptions with the HOA and B-format channel labels, ambisonic and HOA tags), the `info` chunk
- **Write:** yes (`CafMuxer`): the same codecs (`PCM_U8` written as signed 8-bit), a `chan` layout tag, bitmap or descriptions, the codec's cookie (AAC as Core Audio writes it: the core coder in `desc`, the descriptor without `esds` version and flags, the HE-AAC layers in `ldsc`), the tags as `info`, a packet table with priming and valid frames; variable-size packets need a seekable sink
- **Package:** `container.caf`
- **Source:** spec (Apple Core Audio Format Specification 1.0)

</details>

<details>
<summary><b>Sun AU (<code>.au</code>, <code>.snd</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes, big-endian and little-endian (`dns.`) files: 8, 16, 24 and 32-bit integers, 32 and 64-bit floats, mu-law, A-law, G.721 and G.723 (3 and 5-bit) ADPCM as `Codec.ADPCM_G726` (codes repacked to its bit order), G.722; an unknown or oversized data size read to the end
- **Seek:** exact for PCM, to the packet that holds the sample for ADPCM
- **Probe:** the annotation, as `key=value` lines or one comment
- **Write:** yes (`AuMuxer`): the same encodings (little-endian and unsigned samples converted to the big-endian encoding of the same values), tags as `key=value` annotation lines, the data size written on a seekable sink
- **Package:** `container.au`
- **Source:** spec (Sun/NeXT audio file header, ITU-T G.721/G.723/G.726)

</details>

<details>
<summary><b>FLAC</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes
- **Seek:** `SEEKTABLE`, then frame-header bisection and stepping
- **Probe:** Vorbis comments, `PICTURE`, `CHAPTERxxx`, else the tracks of a `CUESHEET` block as chapters; ID3v2, APE and ID3v1 tags around the stream
- **Write:** native FLAC stream through `RawMuxer` (final `STREAMINFO` and seek table on a seekable output; chapters also as a `CUESHEET`)
- **Package:** `container.flac`
- **Source:** spec

</details>

<details>
<summary><b>AMR</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes, single-channel and multichannel (`#!AMR_MC1.0`, `#!AMR-WB_MC1.0`, 1 to 15 channels in the RFC 3551 channel order)
- **Seek:** exact through a frame index
- **Probe:** ID3v2, APE and ID3v1 tags around the frames
- **Write:** yes (raw muxer: `#!AMR` or `#!AMR-WB` magic, then the frames; the multichannel magic and channel count for a track of 2 to 15 channels, then its frame-blocks; MP4 and Matroska carry single-channel AMR only, so the transcoder re-encodes a multichannel stream for them)
- **Package:** `container.amr`, `container.raw`
- **Source:** spec (RFC 4867, RFC 3551)

</details>

<details>
<summary><b>WavPack (<code>.wv</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes, with the hybrid stream's correction file (`WavPackDemuxer(source, correction, options)`): the blocks of a frame as one packet, blocks without audio (wrapper, MD5 signature) with the frames around them
- **Seek:** exact: a frame index built from the block headers
- **Probe:** APEv2 and ID3v1 tags at the end
- **Write:** `RawMuxer` (`Containers.openMuxer(ContainerFormat.WAVPACK, ...)`): the blocks of `.wv` and Matroska (`A_WAVPACK4`, headers rebuilt) tracks, without the correction data; Matroska `A_WAVPACK4` too
- **Package:** `container.wavpack`, `container.raw`
- **Source:** WavPack file format

</details>

<details>
<summary><b>Monkey's Audio (<code>.ape</code>)</b> &nbsp;<sub>read only</sub></summary>

- **Read:** yes, file versions 3.80 onward, also after an ID3v2 tag: one frame per packet
- **Seek:** exact: the frame seek table
- **Probe:** APEv2, ID3v1 and leading ID3v2 tags
- **Write:** no
- **Package:** `container.ape`
- **Source:** Monkey's Audio SDK

</details>

<details>
<summary><b>Musepack (<code>.mpc</code>)</b> &nbsp;<sub>read only</sub></summary>

- **Read:** yes, stream versions 7 and 8
- **Seek:** a frame (version 7, whose decode converges after a few frames) or packet (version 8) index built on the first seek
- **Probe:** APEv2, ID3v1 and leading ID3v2 tags; replay gain and encoder fields are not read
- **Write:** no
- **Package:** `container.musepack`
- **Source:** libmpcdec

</details>

<details>
<summary><b>DSF (<code>.dsf</code>)</b> &nbsp;<sub>read only</sub></summary>

- **Read:** yes: one block of every channel per packet, 1 and 8-bit sample layouts, the channel types 1 to 7 as speakers
- **Seek:** exact: block arithmetic
- **Probe:** the ID3v2 tag at the end (text, pictures, chapters)
- **Write:** no
- **Package:** `container.dsd`
- **Source:** DSF 1.01 specification

</details>

<details>
<summary><b>DSDIFF (<code>.dff</code>)</b> &nbsp;<sub>read only</sub></summary>

- **Read:** yes: uncompressed (4096 bytes of every channel per packet) and DST-coded (one `DSTF` frame per packet, with the `DSTC` CRC as side data) sound, the `CHNL` speakers, the absolute start time
- **Seek:** exact: the `DSTI` index, or a walk of the frames
- **Probe:** `DIAR` artist, `DITI` title, `COMT` comments, the `ID3 ` chunk, `TrackStart` markers as chapters
- **Write:** no
- **Package:** `container.dsd`
- **Source:** DSDIFF 1.5 specification

</details>

<details>
<summary><b>AC-3, E-AC-3 (<code>.ac3</code>, <code>.ec3</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: sync frames grouped into access units (an independent frame of substream 0 with its dependent substreams), resynchronization on damaged data
- **Seek:** exact through a frame index up to 64 MiB; bitrate estimate beyond
- **Probe:** channels with dependent substreams, bit rate, dialogue level, Dolby Atmos flag (from the first access unit); ID3v2, APE and ID3v1 tags
- **Write:** yes (raw muxer: the frames back to back)
- **Package:** `container.ac3`, `container.raw`
- **Source:** spec (ATSC A/52)

</details>

<details>
<summary><b>DTS, DTS-HD (<code>.dts</code>, <code>.dtshd</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: core frames with the DTS-HD extension substream frames that follow them, extension substream frames of streams without core (DTS-HD Master Audio without core), the chunks of the DTS-HD file format (`DTSHDHDR`, frames from `STRMDATA`), raw streams in the 16-bit little-endian and 14-bit forms of DTS audio CDs, resynchronization on damaged data
- **Seek:** exact through a unit index up to 64 MiB; bitrate estimate beyond
- **Probe:** sample rate and speaker layout of the extension substream's asset
- **Write:** yes (raw muxer: the frames back to back)
- **Package:** `container.dts`, `container.raw`
- **Source:** spec (ETSI TS 102 114)

</details>

<details>
<summary><b>IVF</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes
- **Seek:** key frames through a frame index
- **Probe:** duration from the header frame count
- **Write:** yes
- **Package:** `container.ivf`
- **Source:** spec

</details>

<details>
<summary><b>Raw DV (<code>.dv</code>, <code>.dif</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: the DIF frames of DV, DVCPRO, DVCPRO50 and DVCPRO HD (`DvDemuxer`), recognized by the block IDs of the first DIF sequence, the format from the header block and VAUX source pack of the first frame; whole frames as `Codec.DV` packets, and one 16-bit stereo PCM track per audio pair the AAUX source packs name (12-bit four-channel audio expanded to 16 bits, samples holding the audio error code read as silence; the 720-line frames carry pairs 0 and 1 or 2 and 3), each frame's audio after its video; data before a header block skipped, a truncated last frame dropped
- **Seek:** any frame (constant frame size)
- **Probe:** the 4:3 or 16:9 display of the VAUX source control pack as the sample aspect ratio, the field order, the subcode time code as `timecode()`, duration from the size
- **Write:** yes (`DvMuxer`, transcoder target `OutputContainer.DV`): DIF frames as given, with 16-bit PCM tracks of one to eight channels (48 kHz, and 44.1 and 32 kHz for the IEC 61834 formats) laid into the audio blocks with their AAUX packs, a pair per DIF channel taken in track order (16-bit, 48 kHz locked); the IEC 61834 25 Mb/s formats carry two pairs, four channels, at 32 kHz in 12 bits; `DvFormat` describes each format
- **Package:** `container.dv`
- **Source:** spec (IEC 61834-2 structure as SMPTE ST 314 and ST 370 restate it, SMPTE ST 314:2005, ST 370:2013); checked against ffmpeg's dv demuxer and muxer as black boxes

</details>

<details>
<summary><b>MPEG-TS, M2TS</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: 188, 192 and 204-byte packets, any program of the PAT; JPEG XS (`stream_type` `0x32`, Rec. ITU-T H.222.0 Annex W: one access unit per PES packet, its `jxes` header turned into the track's frame rate, field order, color and `jpvi`/`jxpl` configuration); H.264, HEVC, VVC (`stream_type` `0x33`; the three split into access units with an `avcC`, `hvcC` or `vvcC` record built from the in-band parameter sets), AV1 (`AV01` registration), MPEG-4 Part 2, MPEG-1 and MPEG-2 video (`stream_type` 1 and 2, pictures split over several PES packets joined), VC-1 (`stream_type` 0xEA), AAC (ADTS, and LATM with several programs and layers, chunked payloads, fixed frame lengths and program config elements), MP3, MP2, AC-3, E-AC-3, DTS and DTS-HD (Blu-ray `0x82`, `0x85`, `0x86` and `0xA2`, the `DTS1` to `DTS3` registrations, the DVB DTS and DTS-HD descriptors, and `0x82` or `0x06` without a descriptor whose payload starts with a DTS sync word, as ffmpeg writes it), Opus, Blu-ray linear PCM and SMPTE ST 302 AES3 audio as PCM, ID3 timed metadata, SCTE 35 splice information (as a data track of whole sections, their `pts_adjustment` rebased onto the timeline), PGS, DVB subtitles and EBU teletext (see Bitmap subtitles and captions), private data; HLS `SAMPLE-AES` H.264, AAC, AC-3 and E-AC-3 streams (`stream_type` `0xDB`, `0xCF`, `0xC1`, `0xC2`) decrypted with `TsDemuxerOptions.sampleAesKeys` (one key, or a key per byte range for joined segments) to the clear tracks and packets, with the priming and HE-AAC configuration of their `apad` audio setup information, a wrong AC-3 or E-AC-3 key caught by the syncframe CRC; program map changes after the start (new streams, streams moved to other PIDs, as in concatenated recordings); timestamps continued across the 33-bit wrap and splices
- **Seek:** bisection on PES presentation times, then a forward search for each track's key frame
- **Probe:** programs, PIDs, languages; ATSC component names and ISO 639 audio types as track names and dispositions; codec configuration records built from the in-band parameter sets, with the sample aspect ratio they signal
- **Write:** yes: H.264, HEVC, VVC (`stream_type` `0x33`, access unit delimiters that flag the random access points), JPEG XS (`stream_type` `0x32` with the `JXS_video_descriptor` and a `jxes` header per access unit), AV1, MPEG-4 Part 2, MPEG-1 and MPEG-2 video, AAC, MP3, MP2, AC-3, E-AC-3, Opus, 16/20/24-bit PCM (ST 302 or Blu-ray linear PCM), ID3, SCTE 35, PGS (with the `HDMV` registration), DVB subtitles (subtitling descriptor) and teletext (teletext descriptor, PES packets padded to whole transport packets); M2TS, constant bit rate, key-frame-aligned segments
- **Package:** `container.ts`
- **Source:** spec (ISO/IEC 13818-1 with Amd. 1 Annex W, VSF TR-07, AOM Carriage of AV1 in MPEG-2 TS, SMPTE ST 302, ANSI/SCTE 35, ISO/IEC 14496-3 clause 1.7); Blu-ray linear PCM header and channel orders from ffmpeg-written samples as a black box

</details>

<details>
<summary><b>MPEG-PS, VOB, MPEG-1 system streams (<code>.mpg</code>, <code>.vob</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: MPEG-2 program streams and MPEG-1 system streams (both pack and PES header syntaxes, system headers, padding, DVD navigation packets read past, leading garbage skipped, resynchronization on the next pack); MPEG-1 and MPEG-2 video, MPEG-4 Part 2, H.264, HEVC and VVC (from the program stream map or recognized from the stream), cut into whole access units (field pairs joined) whatever the packetization and stamped from the frame rate where a PES packet carries no timestamp; MP2, MP3, AAC (ADTS, LATM); the DVD-Video substreams of private stream 1: AC-3 (and the E-AC-3 substreams of HD DVD), DTS (with DTS-HD extension substreams), 16/20/24-bit linear PCM as PCM, and subpictures (`DVD_SUBTITLE`, one unit per packet with its stop time); AC-3 in private stream 1 without substreams; streams that start after the first 8 MiB found by sampling the rest of the file
- **Seek:** bisection on PES presentation times, then a forward search for each track's key frame
- **Probe:** stream ids, the program stream map's stream types, languages and ISO 639 audio types
- **Write:** yes (`PsMuxer`): MPEG-1 and MPEG-2 video, MPEG-4 Part 2, H.264, HEVC and VVC (with a program stream map), MP2, MP3, AAC, AC-3, E-AC-3, DTS, 16/20/24-bit DVD linear PCM, DVD subpictures; MPEG-1 system streams (`PsMuxerOptions.Syntax.MPEG1`), fixed-size packs, DVD-Video objects (`PsMuxerOptions.forDvd()`: 2048-byte packs, a navigation pack with the presentation control and data search information of every video object unit: general information, search pointers to the units 0.5 s to 120 s around it and to the adjacent units with and without video, and the synchronous audio and subpicture addresses, exact on a seekable sink and within `navigationLookaheadMicros` on a stream)
- **Package:** `container.ts`
- **Source:** spec (ISO/IEC 13818-1, ISO/IEC 11172-1); the DVD-Video substream headers and the navigation pack layout from public descriptions, checked against ffmpeg-written VOBs, dvdauthor and libdvdnav as black boxes

</details>

<details>
<summary><b>AVI</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: RIFF AVI with the OpenDML extensions (`AVIX` forms, `indx` super indexes and `ix##` standard indexes), else the `idx1` index (relative or absolute offsets), else a walk of the `movi` lists (truncated files); `BITMAPINFOHEADER` and `WAVEFORMATEX`/`WAVEFORMATEXTENSIBLE` formats, MPEG-1 and MPEG-2 video, MPEG-4 Part 2, H.264, HEVC, H.263, VC-1 (`WMV3`, `WVC1`, `WMVA`), Sorenson Spark (`FLV1`), Motion-JPEG, Motion JPEG 2000, VP8/VP9, FFV1 (`FFV1`, its configuration record after the `BITMAPINFOHEADER`), DV (type-2 files: `dvsd`, `dv25`, `dv50`, `dvh1` and the other DV FourCCs; type-1 files: an interleaved `iavs` stream of DIF frames, split into a DV video track and the PCM tracks of the audio its frames carry), PCM (64-bit integers included), A-law, mu-law, MP3/MP2 (re-framed into whole frames), AAC, AC-3, E-AC-3 (the `MEDIASUBTYPE_DOLBY_DDPLUS` sub-format), DTS (tags `0x2001` and `0x0008`, identified and passed through), IMA and Microsoft ADPCM (one block per packet), GSM 06.10 (`0x0031`), G.722 (`0x028F`) and G.726 (`0x0045`) timed in samples; dropped frames (empty chunks) keep their time slots
- **Seek:** key frames through the index
- **Probe:** `LIST INFO` tags, `strn` stream names, `vprp` frame aspect ratio (as the sample aspect ratio) and field count, frame and byte counts
- **Write:** yes: OpenDML with `idx1` for the first RIFF form; a `vprp` when the track declares its sample aspect ratio or scan (16-bit frame aspect terms, the closest ratio when the exact one does not fit; the field count only, no field order); MPEG-1 and MPEG-2 video (`mpg1`, `mpg2`), MPEG-4 Part 2, H.264 and HEVC (as Annex B), H.263, Sorenson Spark (`FLV1`), Motion-JPEG, Motion JPEG 2000 (`MJ2C`, a frame relying on the track's `jp2h` box written as a JP2 file, or with a copy of it ahead of its two field boxes), VP8, VP9, FFV1, DV (type-2 files: `dvsd`, `dvh1` for DVCPRO HD), PCM, A-law, mu-law, MP3, MP2, AAC, AC-3, and IMA and Microsoft ADPCM, GSM 06.10, G.722 and G.726 under the `WAVEFORMATEX` a WAVE file stores (`dwScale` the samples of a block, `dwRate` the sampling rate, `dwSampleSize` the block alignment), which ffmpeg decodes to the samples of the WAVE source; constant frame rate with empty chunks for gaps
- **Package:** `container.avi`
- **Source:** spec (Microsoft AVI RIFF, OpenDML AVI File Format Extensions 1.02)

</details>

<details>
<summary><b>ASF (<code>.asf</code>, <code>.wmv</code>, <code>.wma</code>)</b> &nbsp;<sub>read only</sub></summary>

- **Read:** yes: the Header Object (File Properties, Stream Properties, the Header Extension with Extended Stream Properties, hidden streams included, Language List, Metadata and Metadata Library) and the data packets of the Data Object under every payload layout of the specification (single, multiple and compressed payloads, every length type, error correction data, padding), media objects reassembled from their payloads, audio spread interleaving undone; `BITMAPINFOHEADER` video (VC-1 `WMV3` with its `STRUCT_C`, `WVC1` and `WMVA` with the sequence header and entry point, H.264 as length-prefixed NAL units with an `avcC` from the parameter sets, MPEG-4 Part 2, Motion-JPEG and the other FourCCs AVI reads), JPEG streams as Motion-JPEG, `WAVEFORMATEX` audio (MP3 and MP2 cut into whole frames, PCM, A-law, mu-law, AAC, AC-3, the ADPCM formats); the Windows Media codecs other than VC-1 (WMA, WMV 7 and 8, MS-MPEG4) as unknown tracks; timestamps in milliseconds, preroll removed; files protected by DRM raise `MediaException.Unsupported`
- **Seek:** key frames, found from the send times of the fixed-size data packets (no index needed)
- **Probe:** the Content Description, Extended Content Description, Metadata and Metadata Library tags under the `StandardTag` keys of their attribute names, `WM/Picture` pictures, the Marker Object as chapters, the Script Command Object (`AsfDemuxer.scriptCommands`), stream names and languages, the frame rate of the average time per frame, the `AspectRatioX`/`AspectRatioY` sample aspect ratio, the creation date, the ID3v2 tag of an `ID3` descriptor for the keys and pictures the ASF attributes lack
- **Write:** no
- **Package:** `container.asf`
- **Source:** spec (Microsoft Advanced Systems Format Specification 01.20.05, SMPTE ST 421 Annex J); checked against ffprobe on ffmpeg-written and Windows Media Encoder files

</details>

<details>
<summary><b>FLV (<code>.flv</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: the legacy tags (Sorenson Spark, H.264, MP3, AAC, Speex, 8 and 16-bit PCM, A-law, mu-law; Screen video, VP6, Nellymoser and the SWF ADPCM as unknown tracks) and the Enhanced RTMP v2 headers (`avc1`, `hvc1`, `av01`, `vp09`, `vp08`; `mp4a`, `Opus`, `fLaC`, `.mp3`, `ac-3`, `ec-3`; multitrack messages of one or many codecs, `ModEx`, the multichannel configuration, the `colorInfo` metadata frame as the track color with its HDR metadata); H.264 and HEVC composition time offsets; sequence headers that change a track's configuration as `PacketSideData.newCodecConfig`; resynchronization on a damaged tag header; encrypted tags raise `MediaException.Unsupported`
- **Seek:** key frames through the `keyframes` index of `onMetaData` (its times and positions checked against the tags, the audio before the key frame found through the back-pointers), else an index of the tags built on the first seek
- **Probe:** the scalar values of `onMetaData` as `metadata()`, its duration (else the span of the tag timestamps), frame size and rate, `videoTrackIdInfoMap` and `audioTrackIdInfoMap`
- **Write:** yes (`FlvMuxer`, `FlvMuxerOptions`): legacy headers for the first track of Sorenson Spark, H.264, AAC, MP3, Speex, PCM, A-law and mu-law, enhanced headers for HEVC, AV1, VP9, VP8, Opus, FLAC, AC-3 and E-AC-3 and for the extra tracks of a multitrack file (up to 256 of each kind); sequence starts (derived from the first key frame for AV1, VP9 and VP8 tracks without a configuration) and sequence ends, new sequence starts for configuration changes; `onMetaData` with the duration and size patched on a seekable sink, or with a key frame index (`FlvMuxerOptions.keyframeIndex`) written in order on any sink; text tags as `onMetaData` strings and the XMP packet as `onXMPData`
- **Package:** `container.flv`
- **Source:** spec (Adobe Flash Video File Format Specification 10.1, Enhanced RTMP v2 by Veovera)

</details>

<details>
<summary><b>PGS (<code>.sup</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: each display set one packet, key when it starts an epoch or acquisition point
- **Seek:** display sets that start an epoch or acquisition point
- **Probe:** canvas size
- **Write:** yes (`SupMuxer`, `OutputContainer.SUP`)
- **Package:** `container.subtitle`
- **Source:** de facto format (Blu-ray HDMV presentation graphics)

</details>

<details>
<summary><b>MXF (<code>.mxf</code>, SMPTE ST 377-1)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: every operational pattern with internal essence, OP1a and OP-Atom included, frame or clip wrapped in the generic container (SMPTE ST 379-2), a run-in of up to 64 KiB, the final header metadata (a closed and complete partition, found through the random index pack or the footer offset, else the header); VC-3 (DNxHD and DNxHR, SMPTE ST 2019-4), ProRes (SMPTE RDD 44), AVC byte and NAL unit streams (SMPTE ST 381-3; length-prefixed packets with an `avcC` from the first access unit) including AVC-Intra (SMPTE RP 2027) without parameter sets, decoded with those of its class and raster, MPEG-1, MPEG-2 and MPEG-4 Part 2 video (SMPTE ST 381-1), JPEG 2000 (SMPTE ST 422), DV-DIF (SMPTE ST 383, frame wrapped), JPEG XS (SMPTE ST 2124, frame or clip wrapped), Broadcast Wave and AES3 PCM (SMPTE ST 382) cut per edit unit, or in groups of at most 100 ms when clip wrapped; other essence as packets of unknown or data codecs
- **Seek:** the index tables (temporal offsets for reordered streams, key frame offsets), else the positions of the frames read
- **Probe:** material package name, user comments, identification of the writer, operational pattern, track names, the timecode track as `timecode()`, picture layout, aspect ratio and color labels
- **Write:** yes (`MxfMuxer`): OP1a (`MxfMuxerOptions.defaults()`) frame wrapped in content packages, or clip wrapped for one track, and OP-Atom (`MxfMuxerOptions.forOpAtom()`), of DNxHD, ProRes, H.264 (as a plain AVC byte stream, or laid out in an AVC-Intra class 50, 100 or 200 with or without parameter sets, `MxfMuxerOptions.avcIntraClass`), MPEG-1, MPEG-2 (labeled by its profile, level and GOP structure) and MPEG-4 Part 2 video, JPEG XS (SMPTE ST 2124, labeled by its profile, with the JPEG XS sub-descriptor of the first codestream) and 8 to 32-bit PCM; an AVC sub-descriptor with the decoding delay; index tables with temporal offsets, body partitions, a header rewritten closed and complete on a seekable sink, a random index pack, a timecode track from the `timecode` tag
- **Package:** `container.mxf`
- **Source:** spec (SMPTE ST 377-1:2019, ST 379-2, ST 381-1, ST 381-3, ST 382, ST 390, ST 2019-4, ST 2124, RDD 44, RP 2027 and the SMPTE Labels, Groups and Elements registers); checked against ffmpeg- and bmx-written files and ffmpeg's and bmx's reading of Smalt's files as black boxes; the AVC-Intra parameter sets of headerless streams as encoders write them for each class (see Known limits), their decoding fields equal to the Panasonic sets bmx carries

</details>

<details>
<summary><b>VobSub (<code>.idx</code> and <code>.sub</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: `VobSubDemuxer(idx, sub, options)`, the MPEG program stream of subpicture units with one track per subpicture stream, and the index header (palette, size, time offset) and stream languages; a `.sub` alone decodes with a gray palette
- **Seek:** the index entries
- **Probe:** languages, canvas size
- **Write:** yes (`VobSubMuxer(idx, sub)`, one track)
- **Package:** `container.subtitle`
- **Source:** de facto format (DVD-Video subpictures)

</details>

<details>
<summary><b>Raw elementary streams</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: H.264, HEVC and VVC Annex B (`.h266`, `.266`, `.vvc`) (`AnnexBDemuxer`: access units split as ITU-T H.264 7.4.1.2.3, H.265 7.4.2.4.4 and H.266 7.4.2.4.3 define them, H.264 field pairs joined, `avcC`/`hvcC`/`vvcC` built from the parameter sets, timestamps derived from the picture order counts, each picture timed at the rate of its own sequence parameter set (VUI, else HEVC VPS, VVC general timing and HRD parameters, else `ElementaryStreamOptions`) for the fields its `pic_struct` displays, decoding times from the decoder's reorder depth, the pictures `no_output_of_prior_pics_flag` discards left without a presentation time), AV1 low-overhead OBUs and Annex B (`ObuDemuxer`), MPEG-4 Part 2 (`.m4v`) and H.263 (`.263`) (`Mpeg4VideoDemuxer`: timestamps from the VOP times, group of VOP time codes and H.263 temporal references and picture clock), and headerless PCM in a sample format the caller gives (`PcmDemuxer`, `PcmDemuxerOptions`)
- **Seek:** Annex B: the key frame at or before the target, resuming from the IDR or IRAP picture that starts its run (the first seek reads the stream once); AV1: key temporal units (one walk of the unit headers); MPEG-4 Part 2 and H.263: the intra picture at or before the target (the first seek reads the stream once); PCM: to the sample
- **Probe:** picture size, frame rate, color and sample aspect ratio from the sequence headers
- **Write:** H.264, HEVC and VVC Annex B, AV1 low-overhead or Annex B (`RawMuxerOptions.av1AnnexB`), MPEG-4 Part 2, H.263 and PCM through `RawMuxer` (`ContainerFormat.H264`, `HEVC`, `VVC`, `AV1`, `AV1_ANNEX_B`, `MPEG4_VIDEO`, `H263` and `PCM`, and the transcoder targets `OutputContainer.H264`, `HEVC`, `VVC` and `AV1`)
- **Package:** `container.raw`
- **Source:** spec (ITU-T H.264, H.265 and H.266 Annex B and clause 8.2.1 / 8.3.1, AV1 specification section 5 and Annex B)

</details>

<details>
<summary><b>YUV4MPEG2 (<code>.y4m</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: `C` colorspaces `420jpeg`, `420mpeg2`, `420paldv`, `422`, `444`, `411`, `444alpha`, `mono` and the 9 to 16-bit `420pN`, `422pN`, `444pN`, `monoN`; `F`, `A`, `I` and `XCOLORRANGE` parameters
- **Seek:** to the frame
- **Probe:** frame rate, sample aspect ratio, interlacing, range and chroma siting
- **Write:** yes (`Y4mMuxer`, transcoder target `OutputContainer.Y4M`)
- **Package:** `container.y4m`
- **Source:** YUV4MPEG2 description (mjpegtools)

</details>

<details>
<summary><b>HLS (<code>.m3u8</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: a master playlist with every media playlist it names, or one media playlist (`HlsDemuxer.open(Path)` for one on disk; otherwise the segments, initialization sections and keys opened through `DemuxerOptions.dataReferences`, such as `DataReferenceResolver.http` over HTTP, whose sources may stream without a known length): transport stream, fragmented MP4, WebVTT (cue times moved by each `X-TIMESTAMP-MAP`, repeated cues read once) and packed audio segments (timed by their ID3 `transportStreamTimestamp`), byte ranges, gaps, the `identity` key among several `KEYFORMAT`s, `AES-128` segments, CMAF `SAMPLE-AES` and `SAMPLE-AES-CTR` with the options' keys or the key URI's, transport stream `SAMPLE-AES` segments (H.264, AAC, AC-3, E-AC-3) with the options' key (`DecryptionKeys.single`) or the `identity` key URI's, each segment's `IV` or media sequence number as its vector, keys rotating between segments, I-frame playlists read alone; discontinuities (`EXT-X-DISCONTINUITY`) and changes of initialization section (`EXT-X-MAP`) to another encoding, each section read with its own demuxer: a track of the same kind and codec continues across a section with the new codec configuration on its first packet, any other becomes a new track, and each discontinuity sequence number is placed on the timeline after its `EXTINF` durations or at its `EXT-X-PROGRAM-DATE-TIME`, aligned across the playlists of a master playlist (`EXT-X-DISCONTINUITY-SEQUENCE`), WebVTT cues included (audio packets of a section that start past that point, encoder padding, are left out of a continued track); the tracks of all streams on one timeline
- **Seek:** through the demuxer of each stream
- **Probe:** a rendition's `LANGUAGE`, `NAME` (as the `title`), `DEFAULT`, `FORCED` and accessibility `CHARACTERISTICS`
- **Write:** presentations, through `Packager`
- **Package:** `container.hls`
- **Source:** RFC 8216

</details>

<details>
<summary><b>MPEG-DASH (<code>.mpd</code>)</b> &nbsp;<sub>read and write</sub></summary>

- **Read:** yes: every representation of every period (`DashDemuxer`, `DashDemuxer.open(Path)`; segments opened as for HLS): `SegmentTemplate` with a `SegmentTimeline` (repeats to the next entry or the period's end included) or a segment duration (over the period, or in a dynamic manifest up to its `publishTime` and within its `timeShiftBufferDepth`), `$RepresentationID$`, `$Number$`, `$Time$`, `$Bandwidth$` and their widths, `SegmentList` with media ranges, `SegmentBase`, side files such as WebVTT and TTML subtitles; nested `BaseURL`s, period starts and presentation time offsets; adaptation sets with an `EssentialProperty` (trick play) left out
- **Seek:** through the demuxer of each stream
- **Probe:** `lang`, `Label` (as the `title`), roles and audio description `Accessibility` as dispositions
- **Write:** presentations, through `Packager`
- **Package:** `container.dash`
- **Source:** ISO/IEC 23009-1

</details>

Container behavior worth knowing:

<details>
<summary><b>QuickTime movies, ProRes and DNxHD</b></summary>

QuickTime movies are written by `Mp4Muxer` with `Mp4MuxerOptions.quickTime()` (major brand `qt  `), which
also takes ProRes and DNxHD tracks with PCM audio (the deliverable of the professional intra codecs, as
`OutputContainer.MOV` writes it): their sample entry type is the track's four-character codec configuration
(`apch`, `AVdh`, ...), and the entry gets the QuickTime `colr` (`nclc`), `fiel` (the track's scan, else the scan
of the first frame) and `pasp` (the track's sample aspect ratio, else square samples) boxes, with a depth of 32
when the first frame carries alpha. `MatroskaMuxer` writes
ProRes as `V_PRORES` (frames without their size and `icpf` prefix, as Matroska stores them) and DNxHD as
`V_QUICKTIME` with a QuickTime sample description. `MxfMuxer` writes them in MXF, the other home of DNxHD and
ProRes (see MXF in the list above).

</details>

<details>
<summary><b>MP4 edit lists</b></summary>

The MP4 demuxer applies every segment of an edit list (ISO/IEC 14496-12 clause 8.6.6): several media edits,
empty edits between them and dwell edits (media rate 0, which hold a picture) become a presentation index whose
decode-only pre-roll packets carry `PacketFlag.DISCARD` and whose cut audio packets carry their discard start and
end, which `VideoReader`, `AudioReader` and the transcoder apply (the gap an empty edit, an audio dwell edit or a
media edit longer than its media leaves after the first media edit is played as silence, as `AudioStreamDecoder`
fills every gap between the timestamps of audio packets); an edit list of empty edits and one media edit,
the usual form, still shifts the timestamps alone, and so does the first media edit of a HEIF or AVIF image
sequence track (`pict`, `auxv`), whose samples are each returned once. Every `stsd` sample description is read and selected per
sample by `stsc`, `tfhd` or `trex`; a sample whose description has another codec configuration carries it as
`PacketSideData.newCodecConfig`, as do the transport stream packets where the in-band H.264 or HEVC parameter
sets or the AAC `AudioSpecificConfig` change. The readers and the transcoder then continue with a decoder of the
new configuration; `Mp4Muxer` adds a sample description, the MPEG-TS, Matroska, ADTS, AVI and raw muxers write
the change in band where the codec allows it, IVF frames describe themselves, and the other muxers reject it.
`Mp4Remuxer` copies a multi-segment edit list and every sample description as they are.

</details>

<details>
<summary><b>Protected tracks and decryption keys</b></summary>

Protected tracks are decrypted with the keys of `DemuxerOptions.decryptionKeys` (a `DecryptionKeys`: one key,
keys by key identifier, a key for any identifier, or an application's own lookup), and their tracks and packets
are then those of the clear file. The MP4 demuxer reads the ISO/IEC 23001-7 Common Encryption schemes `cenc`
(AES-CTR), `cbc1` (AES-CBC), `cens` and `cbcs` (their crypt and skip patterns) and PIFF 1.1: the `sinf`, `frma`,
`schm` and `tenc` boxes of `encv` and `enca` entries (the PIFF track encryption box too), the initialization
vectors and subsample maps of `senc` boxes (in the sample table or each track fragment, and the PIFF `uuid` one)
or, without one, of the sample auxiliary information `saiz` and `saio` locate (in the `moov`, the `moof` or the
`mdat`, one offset or one per chunk or run), constant initialization vectors, and the keys of `seig` sample groups
(`sgpd`/`sbgp` in the sample table and in fragments: key rotation), multi-key `seig` groups included (ISO/IEC
23001-7:2023: each subsample names its key, each key has its own initialization vector). The Matroska demuxer reads
the AES-CTR `ContentEncryption` of WebM, signal byte, initialization vector and partitions. Decryption matches the clear
files of Chromium's encrypted test media and of the MPEG file format conformance suite, and ffmpeg's decryption.
Without keys a protected track is rejected as unsupported when the demuxer opens, and with keys a sample whose
key is not known is rejected when it is read. `FragmentedMp4Muxer` encrypts audio and video tracks with
`FragmentedMp4MuxerOptions.encryption` (a `CommonEncryption`: the scheme, key identifier, key, initialization
vector, pattern and `pssh` boxes, the W3C Common PSSH box by default): `encv`/`enca` entries with their `sinf`,
`senc`, `saiz` and `saio` boxes in every fragment, H.264 and HEVC by subsample with NAL unit and slice headers
clear (slice header sizes match ffmpeg's `trace_headers`), VP9 by subsample with the uncompressed header of each
frame and the superframe index clear, AV1 by subsample with only the tile data protected, the other video codecs
and audio whole. The samples, initialization vectors and subsample maps of VP9 and AV1 tracks equal Shaka
Packager's for the libvpx and libaom conformance vectors (superframes, resizing, tiles, show-existing frames,
spatial and temporal layers, lossless and film grain frames).

</details>

<details>
<summary><b>Dolby Vision tracks</b></summary>

Dolby Vision tracks are tracks of their base codec (HEVC, H.264 or AV1) whose packets carry the reference
processing units (RPUs) in band, with the configuration (`DolbyVision`: profile, level, the RPU, enhancement and
base layer flags, the base layer compatibility identifier) on `VideoTrack.dolbyVision()` and
`VideoStreamInfo.dolbyVision()`. The MP4 demuxer reads it from the `dvcC`, `dvvC` and `dvwC` boxes of any visual
sample entry and maps the Dolby Vision entries `dvh1`, `dvhe`, `dva1`, `dvav` and `dav1` to their base codec; the
Matroska demuxer reads the `BlockAdditionMapping` of type `dvcC`, `dvvC` or `dvwC`, and the MPEG-TS demuxer the
DOVI video stream descriptor (tag `0xB0`). The MP4 and fragmented MP4, Matroska and MPEG-TS muxers write it back
(the box Dolby's ISO base media file format specification selects for the profile, after the codec configuration
box, in the base codec's entry, or in `dva1`, `dvh1` or `dav1` when the base layer is not backward compatible;
the `BlockAdditionMapping`, not in WebM; the `"DOVI"` registration and the DOVI descriptor), and the RPUs stay in
the packets, so a remux between any two of them keeps the stream a Dolby Vision stream; ffprobe reads the
configuration of every file Smalt writes, and Smalt reads ffmpeg's. The decoders show the base layer: a stream
whose base layer is backward compatible (profiles 8.1, 8.2, 8.4, 9, 10.1, 10.2, 10.4, 7's HDR10 base layer)
decodes as plain video of its color description, while one that is not (profile 5 and 10.0, IPTPQc2) has its
pictures reconstructed by `VideoDecoders` from its RPUs, as the composer of ETSI GS CCM 001 defines: pivots, polynomial
and MMR mappings in exact integer arithmetic, then the RPU's `YCCtoRGB` and `RGBtoLMS` matrices, to BT.2020 RGB coded
with the SMPTE ST 2084 inverse EOTF, 4:4:4 pictures of identity matrix and full range whose Y, U and V planes hold G, B
and R, of the RPU's bit depth with `VideoDecoderOptions.highBitDepth` and of 8 bits otherwise. Such a stream is told
from its container configuration or, for a single-layer HEVC or AV1 stream that declares none, from its first valid
RPU, at the first key frame from there on (the RPU parser follows the dolby_vision crate of dovi_tool, and an AV1
stream carries its RPUs in ITU-T T.35 metadata OBUs); `VideoDecoderOptions.dolbyVisionBaseLayer` asks for the base
layer anyway, and `HevcDecoder` by itself refuses the stream unless its own options do.

</details>

<details>
<summary><b>PCM in MP4</b></summary>

PCM is stored one frame per sample, in a timescale of the sampling rate, as QuickTime and ISO/IEC 23003-5
readers expect; its frames follow each other from the first packet's timestamp, so a gap between packets is
not kept. A rate above 65535 Hz leaves the 16.16 sampling rate field of an ISO entry at zero (readers then take
the media timescale), since a version 1 entry with an `srat` box is read as a QuickTime sound description by
ffmpeg. The `chnl` box writes the back pair as the rear surround positions (8, 9), the side pair as the side
surround positions (13, 14) and every other speaker at its ISO/IEC 23091-3 position (0 to 29, the 22.2 layout
included); a layout with an unknown speaker gets no `chnl`. The QuickTime `chan` box has no label for the
surround direct pair, whose labels the side pair uses, so it is written with the unknown label.

</details>

<details>
<summary><b>Demuxer options</b></summary>

`Containers.openDemuxer` hands out every demuxer above; the MPEG-TS demuxer also takes `TsDemuxerOptions` (program,
probe window) when constructed directly, and lists every program of a multi-program capture. Raw PCM has no header
to recognize, so it is read with a `PcmDemuxer` given its sample format, rate and layout.

</details>

<details>
<summary><b>Uncompressed video (<code>Codec.RAW_VIDEO</code>)</b></summary>

Uncompressed video is `Codec.RAW_VIDEO`: the track's codec configuration is a `RawVideoFormat` (a `RawPixelFormat`,
the bit depth of planar formats, the byte order of their 16-bit samples, the row alignment, the row order and a
palette), which `RawVideoDecoder` turns into `YuvPicture`, `Yuv16Picture`, `RgbaPicture` or `Rgba16Picture` frames and
`RawVideoEncoder` writes. The MP4 demuxer maps the QuickTime `raw ` sample entry (16, 24 and 32-bit RGB, 1, 2,
4 and 8-bit palette indices into the entry's color table or the default Macintosh table of their depth, 1, 2, 4 and
8-bit gray, rows padded to 2 bytes) and the `2vuy`, `yuvs`, `24BG`,
`BGRA`, `ABGR`, `RGBA`, `L565`, `B565`, `L555`, `b16g`, `b48r`, `b64a`, `v210` and `j420` entries to it, Matroska
`V_UNCOMPRESSED` (by its `ColourSpace` FourCC) and `V_MS/VFW/FOURCC` bitmaps, and AVI `BI_RGB` and `BI_BITFIELDS`
bitmaps (1, 2, 4 and 8-bit palettes, 16, 24 and 32 bits, bottom-up rows padded to 4 bytes) and the `I420`, `IYUV`, `YV12`,
`NV12`, `NV21`, `UYVY`, `YUY2`, `YVYU`, `Y800`, `Y42B`, `Y41B`, `RGBA` and `v210` FourCCs. Decoded frames equal
ffmpeg's decode of the same files sample for sample.

</details>

<details>
<summary><b>ISO/IEC 23001-17 uncompressed video</b></summary>

The MP4 demuxer maps the ISO/IEC 23001-17 `uncv` sample entry to `Codec.UNCOMPRESSED_23001_17`, whose configuration
is the entry's 23001-17 boxes (`cmpd`, `uncC`, `cmpC`, `cloc` and the others of the standard), which
`UncompressedVideoDecoder` decodes with the uncompressed-image decoder of HEIF `unci` items and
`UncompressedVideoEncoder` writes in an `UncompressedVideoFormat`; the muxers write it back as `uncv`.

</details>

<details>
<summary><b>Seeking audio: pre-roll and exact positions</b></summary>

`AudioReader.seek` adds a codec pre-roll on top of the container seek and returns the samples a
read from the start returns from the target on, bit for bit for PCM, FLAC, MP3, Vorbis, Opus and
AAC (except the bands an AAC encoder filled with perceptual noise substitution, whose random
generator runs from the start of the stream). Where the container rounds timestamps coarser than a
sample (Matroska stores milliseconds, which Vorbis and AAC packets do not fill exactly), the reader
decodes from the start of the track and drops instead, which stays exact but is slower, as it does for
AAC Main, LTP and LD, whose prediction state reaches back to the start of the stream, and AAC-ELD with low
delay SBR;
`AudioStreamDecoder.measure` tells the two cases apart (`Timing.isExact()`) for callers that drive a
demuxer themselves. `VideoReader.seek` returns the frame displayed at the
target. `Thumbnails` and `Waveform` (package `preview`) build on them.

</details>

#### Container providers

A module or class-path jar adds a container by providing a `spi.ContainerProvider` (`provides` in its module
declaration, or a `META-INF/services` entry), the way the codec artifacts add codecs. A provider names its
container (`name()`, for example `"mxf"`), recognizes it from up to 64 KiB of leading bytes (`matches`, also
asked after a leading ID3v2 tag) and implements any of reading (`openDemuxer`), writing (`openMuxer`, with the
codecs it `carries`) and tag editing (`readTags`, and `planTags`, which returns a `TagRewrite` of literal bytes and
copied ranges that `TagEditor` applies in place when the ranges stay put). Its container is then recognized by
`Containers.detect`, opened by `Containers.openDemuxer`, the readers (`AudioReader`, `VideoReader`,
`SubtitleReader`, `Subtitles.read`), `Media.probe`, `Packager` and the transcoder's input, written by
`Containers.openMuxer(String, MediaSink)` and `Subtitles.write(cues, markup, String, sink)`, and its tags read
and written by `TagEditor`. A container `ContainerFormat` does not name is `ContainerFormat.OTHER`, identified by
the provider's name (`Containers.detectName`, `Demuxer.formatName()`, `MediaInfo.containerName()`); a provider
may also name a `ContainerFormat` constant, for which it adds the operations core lacks (for example tag editing of
YUV4MPEG2 files). Core's own containers come first: recognition asks the providers after the formats with a magic
number and before the ones found by heuristics (frame sync words, elementary video, text), and a provider is used
only for the operations core lacks. A container
that is recognized but that no provider on the path reads, writes or edits raises `MediaException.Unsupported`
naming it, and the Smalt artifact that implements it when there is one.

### Codecs in core

The codecs that are plain sample or pixel layouts ship with core; every other codec is in a codec artifact.

<details>
<summary><b>Uncompressed video (<code>Codec.RAW_VIDEO</code>)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** the `RawPixelFormat` layouts: planar YUV 4:2:0, 4:2:2, 4:4:4, 4:4:0 and 4:1:1 (to 4:2:2) at 8 to 16 bits with or without alpha, gray, NV12/NV21, packed UYVY/YUYV/YVYU, v210, 8 and 16-bit RGB in every byte order, 5-6-5 and 5-5-5 RGB, palettes of 1, 2, 4 and 8-bit indices; any row alignment, top-down or bottom-up (`RawVideoDecoder`)
- **Encode:** the same layouts but palettes, from any picture (`RawVideoEncoder`; `VideoEncoders` writes 8-bit 4:2:0)
- **Artifact:** `smalt-core`
- **Package:** `video`
- **Source:** spec
- **Patents:** none

</details>

<details>
<summary><b>ISO/IEC 23001-17 uncompressed video (<code>Codec.UNCOMPRESSED_23001_17</code>)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** MP4 and MOV `uncv` tracks with their `cmpd`, `uncC` and `cmpC` boxes (`UncompressedVideoDecoder`, also through `VideoDecoders`): every interleaving (component, pixel, mixed, row, tile-component, multi-Y), 4:4:4, 4:2:2, 4:2:0 and 4:1:1 (to 4:4:4) Y'CbCr, RGB (as 4:4:4 of the identity matrix), monochrome and alpha, CMYK (to RGB) and filter arrays, unsigned and signed integers of 1 to 256 bits, floating-point and complex components (to at most 16 bits, as HEIF `unci` items), packed bit after bit, aligned to whole bytes or in blocks of any size in either byte order, pixel, row and tile padding, equal tiles, palettes (`cpal`), the `rgb3`, `rgba` and `abgr` profiles, frames compressed with `defl`, `zlib` or `brot`, whole or in `icef` units
- **Encode:** `UncompressedVideoEncoder` (also through `VideoEncoders`, which writes 8-bit 4:2:0 at the options' depth) in an `UncompressedVideoFormat`: Y'CbCr 4:2:0, 4:2:2 or 4:4:4, RGB or monochrome, with or without alpha, 1 to 16 bits packed, aligned or little-endian, any of the six interleavings the components allow, row alignment, tiles, `defl` or `zlib` frames; the track configuration is the `cmpd`, `uncC` and `cmpC` boxes; frames equal ffmpeg's packing of the same samples where ffmpeg has the layout (NV12, NV16, YUYV, `yuv420p10le`, `rgb24`, `rgba`, `rgb48le`, `gray16le`, `monob` and others)
- **Artifact:** `smalt-core`
- **Package:** `video`, `internal.heif`
- **Source:** spec (ISO/IEC 23001-17 and Amendment 2)
- **Patents:** none

</details>

<details>
<summary><b>DSD (1-bit, DSF, DSDIFF, DoP)</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** yes: converted to PCM by a linear-phase decimation filter (flat to 40% of the output rate, 120 dB from half of it) at the DSD rate divided by 8 to 512 (16 by default: 176.4 kHz for DSD64, or the ratio `AudioDecoderOptions.preferredSampleRate` selects), any DSD rate and channel count, as floats or doubles (the double output matches a direct convolution to within rounding); both bit orders and the interleaved and planar layouts of DSDIFF and DSF; DSD over PCM (DoP 1.1) in 24 and 32-bit PCM decoded to the DSD it carries with `AudioDecoderOptions.dsdOverPcm`, following a stream that switches between DoP and PCM
- **Encode:** no
- **Artifact:** `smalt-core`
- **Package:** `audio.pcm`
- **Source:** spec (DSF 1.01, DSDIFF 1.5, DoP 1.1)
- **Patents:** none

</details>

<details>
<summary><b>PCM (integer and float, any layout)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes; integer formats up to 32 bits and G.711 also as their integers bit for bit, every format as doubles (64-bit floats unchanged)
- **Encode:** 8 to 64-bit, integer and float, either byte order, G.711 A-law and mu-law, through the containers that carry it, from float, integer or double samples; triangular, high-pass triangular or noise-shaped dither for 8, 16 and 24-bit output (`PcmEncoderOptions.dither`)
- **Artifact:** `smalt-core`
- **Package:** `audio.pcm`
- **Source:** spec; Lipshitz, Vanderkooy and Wannamaker (dither, E-weighted noise shaping)
- **Patents:** none

</details>

<details>
<summary><b>ATRAC1</b> &nbsp;<sub>carried, not decoded</sub></summary>

- **Decode:** no (see Known limits); Matroska `A_ATRAC/AT1` tracks are read and written
- **Encode:** no
- **Artifact:** none
- **Package:** none
- **Source:** none
- **Patents:** Sony patents

</details>

<details>
<summary><b>Packet loss concealment</b></summary>

Every audio decoder Smalt ships also stands in for lost packets through the codec-neutral
`AudioDecoder.conceal(samples, output)`, and the Opus, AAC, MP3 (constant and average bitrate) and AMR encoders
change their bitrate while running through `AudioEncoder.setTargetBitrate` (see [Code any audio codec in real
time](#code-any-audio-codec-in-real-time)).

</details>

### Streaming

`smalt-core` package `container.packaging` packages encodes for adaptive streaming without re-encoding:
`Packager.pack` takes one or more renditions (any file the containers above read) and writes, through a
`PackageOutput` (a directory or any sink factory), the segments, media playlists and manifests;
`Packager.open` returns a `PackageWriter` that packages the packets an application writes (a live encoder's)
the same way. The `container.hls` and `container.dash` demuxers read the presentations back.

- **HLS (`PackagingOptions.hls()`)**
  - *Segments:* MPEG-TS, audio and video muxed, cut at video key frames
  - *Manifest:* master playlist (one variant per rendition with peak and average bandwidth, `CODECS`, `SUPPLEMENTAL-CODECS` of Dolby Vision with a backward compatible base layer (`dvh1.08.06/db4h`, `dav1.10.09/db1p`), `VIDEO-RANGE` of PQ, HLG and Dolby Vision video, resolution, frame rate) and media playlists, on demand, as an event or live (below)
  - *Source:* RFC 8216; Dolby Vision Streams Within the HTTP Live Streaming Format
- **HLS with CMAF (`hlsCmaf()`)**
  - *Segments:* CMAF tracks (`_init.mp4`, `.m4s`)
  - *Manifest:* as above, CMAF audio as an `EXT-X-MEDIA` audio group, `EXT-X-MAP`
  - *Source:* RFC 8216
- **DASH (`dash()`)**
  - *Segments:* CMAF tracks
  - *Manifest:* static MPD (dynamic for an event or live), one adaptation set per kind, codec, language and role, `SegmentTemplate` with `SegmentTimeline`
  - *Source:* ISO/IEC 23009-1
- **HLS and DASH (`cmaf()`)**
  - *Segments:* one set of CMAF tracks
  - *Manifest:* both
- **Byte ranges (`byteRanges(true)`)**
  - *Segments:* one file per stream
  - *Manifest:* `EXT-X-BYTERANGE`; DASH `SegmentList` with media ranges
- **DASH on demand (`dashOnDemand()`, or `segmentIndex(true)` with byte ranges)**
  - *Segments:* one CMAF file per stream, a single `sidx` after the `moov` (`FragmentedMp4MuxerOptions.SegmentIndex.SINGLE`)
  - *Manifest:* on-demand profile MPD: `BaseURL` and `SegmentBase` with `indexRange` and `Initialization range`; HLS keeps `EXT-X-BYTERANGE` past the `sidx`
  - *Source:* ISO/IEC 23009-1 clause 8.3

A `Rendition` is a variant (`Rendition.of`: its first video and audio track), an alternate audio rendition
(`Rendition.ofAudio`, or a `Rendition.builder` of kind `AUDIO` that names a track) or a subtitle rendition
(`ofSubtitles`: SubRip, WebVTT, 3GPP timed text, ASS or TTML from any container or subtitle file), with a
language, a label, and the default and forced flags:

- **Alternate audio**
  - *HLS:* one `EXT-X-MEDIA` audio group per codec that every variant names (`AUDIO`), the variants then video only; an audio-only presentation lists one variant per group
  - *DASH:* an adaptation set per codec, language and label, `Label`, roles `main`/`alternate` (and `commentary`, `dub`, `description`)
- **Subtitles**
  - *HLS:* WebVTT segments (`.vtt`) cut at the first video stream's segments, cues of the other codecs converted, spanning cues repeated, `X-TIMESTAMP-MAP` onto the segments' 90 kHz timeline; a `SUBTITLES` group with `FORCED` and `CHARACTERISTICS`
  - *DASH:* CMAF text tracks: `wvtt` (other codecs converted), TTML as `stpp` with its IMSC profile in `codecs`; `contentType="text"`, roles `subtitle`/`forced-subtitle`/`caption`
- **Trick play (`trickPlay(true)`)**
  - *HLS:* an I-frame playlist per video stream (`EXT-X-I-FRAME-STREAM-INF`): byte ranges of every key frame of transport stream segments behind the program tables of an `EXT-X-MAP`, or of each CMAF segment's first key frame
  - *DASH:* a trick-play adaptation set (`EssentialProperty` `http://dashif.org/guidelines/trickmode`) of the key frames alone, each lasting until the next, `maxPlayoutRate` and `codingDependency="false"`
- **Events and live (`presentation(EVENT)`, `presentation(LIVE)`)**
  - *HLS:* playlists rewritten after every segment, `EXT-X-PLAYLIST-TYPE:EVENT`, or a sliding window of `windowSegments` (grown to three target durations) whose expired segments `removeExpiredSegments` deletes once players cannot need them; `EXT-X-PROGRAM-DATE-TIME` from `programDateTime`; an `EXT-X-TARGETDURATION` that never changes (`targetDuration`, or the first version's longest segment), a segment that would exceed it rejected
  - *DASH:* a dynamic manifest after every segment: `availabilityStartTime`, `publishTime`, `UTCTiming`, `minimumUpdatePeriod` until the end, `timeShiftBufferDepth` and windowed timelines for live

<details>
<summary><b>Timelines of the transport stream segments</b></summary>

The transport stream segments of every rendition share one timeline (media time zero is the 700 ms
multiplexing delay), so variants and alternate renditions stay in sync. The test kit's HLS and DASH
validators check every layout, including each version of a live presentation as it is written; ffmpeg plays the
playlists and manifests and reads each WebVTT segment (its HLS demuxer skips subtitle renditions), and the HLS
and DASH demuxers read every presentation back to the input's packets and cues on one timeline.

</details>

Encryption (`PackagingOptions.encryption`, a `PackagingEncryption`) takes three methods, with clear keys
(`EXT-X-KEY` with the key URI, the initialization vector and the key format; an optional key file written with
the segments):

- **`AES_128`**
  - *Transport stream segments:* whole segments, AES-128-CBC with PKCS#7 padding, initialization vector given or the media sequence number (byte ranges each encrypted on their own)
  - *CMAF segments:* media segments as for TS, initialization segments clear
  - *DASH:* not available
- **`SAMPLE_AES`**
  - *Transport stream segments:* the SAMPLE-AES format of Apple's MPEG-2 stream encryption: H.264 slices (32-byte clear leader, 1:9 pattern, emulation prevention after encryption), AAC, AC-3 and E-AC-3 frames (16-byte clear leader), PMT `stream_type` `0xDB`, `0xCF`, `0xC1`, `0xC2` with `zavc`/`aacd`/`ac3d`/`ec3d` and `apad` audio setup descriptors
  - *CMAF segments:* `cbcs`
  - *DASH:* `ContentProtection` `cbcs` with `cenc:default_KID`, and W3C Clear Key
- **`SAMPLE_AES_CTR`**
  - *Transport stream segments:* not available
  - *CMAF segments:* `cenc`
  - *DASH:* `ContentProtection` `cenc` with `cenc:default_KID`, and W3C Clear Key

<details>
<summary><b>How the packaging is verified</b></summary>

ffmpeg decodes the AES-128 playlists and the decrypted CMAF tracks to the frames of the clear packaging, and
decrypts the SAMPLE-AES playlists to the clear packets; the SAMPLE-AES slices and audio frames equal Shaka
Packager's bit for bit. `HlsDemuxer` decrypts the SAMPLE-AES transport stream playlists to the packets of the clear
ones, as it does those encrypted by a second encryptor written from Apple's definition (key rotation, the media
sequence number as the vector). ffmpeg decrypts their audio to the clear packets, but returns the slices whose clear
form holds emulation prevention bytes without them, and decrypts the units it still holds at the end of a segment
with the next segment's vector. On the first segments of Unified Streaming's public SAMPLE-AES demo, Smalt's
decryption matches ffmpeg's but for those slices, and remuxed it decodes without an error, which ffmpeg's does not.
ffmpeg plays every layout; it cannot read DASH `SegmentList` media ranges, which dash.js and Shaka Player do.

</details>

### RTP payload formats

`smalt-core` package `rtp` carries the packets of realtime codecs in RTP: `RtpPacketizer` turns an elementary
stream's packets (as the demuxers and encoders give them) into RTP packets, and `RtpDepacketizer` reassembles
them, putting reordered packets back in sequence within a window, dropping duplicates, reporting lost, late and
unparsable packets, and flagging each unit a loss can have damaged. `RtpPacket` reads and writes the RFC 3550 packet
(contributing sources, header extension with the RFC 8285 elements, padding), and `RtpPayloadFormat` the SDP
`rtpmap` and `fmtp` of a format, derived from a track or parsed from a session description. The package opens no
socket and sends no RTCP; transport, SRTP, retransmission and FEC are the application's.

- **H.264 (`H264`)**
  - *Packetizer:* packetization modes 0, 1 (single NAL unit, STAP-A, FU-A with fragments of equal size) and 2 (STAP-B, FU-B, FU-A with decoding order numbers); Annex B or length-prefixed input; the `sprop-parameter-sets` before IDR pictures that lack them
  - *Depacketizer:* every packet type of the three modes, MTAP16 and MTAP24 included; de-interleaving by decoding order number (`sprop-interleaving-depth`, `sprop-max-don-diff`); access units with 4-byte lengths, a new `avcC` as side data when in-band parameter sets change
  - *Source:* RFC 6184
- **VP8 (`VP8`)**
  - *Packetizer:* payload descriptor with a 7- or 15-bit picture identifier or none
  - *Depacketizer:* every descriptor extension (picture identifier, `TL0PICIDX`, `TID`, `KEYIDX`)
  - *Source:* RFC 7741
- **VP9 (`VP9`)**
  - *Packetizer:* non-flexible mode, one spatial layer, superframes split into pictures, the scalability structure with the frame size on key frames
  - *Depacketizer:* flexible and non-flexible modes, layer indices, reference indices, scalability structures; the frames of a picture (and a hidden frame with the following shown one) joined into a superframe
  - *Source:* RFC 9628
- **AV1 (`AV1`)**
  - *Packetizer:* aggregation header, OBUs without size fields, aggregation and fragmentation, OBUs of other layers in other packets, the temporal delimiter and tile lists dropped, `N` on key temporal units
  - *Depacketizer:* every element layout (`W` 0 to 3, fragments over any number of packets, empty elements); temporal units of OBUs with size fields; `Av1DependencyDescriptor` reads and writes the dependency descriptor header extension (templates, decode targets, chains, resolutions, custom fields), which the packetizer attaches on request
  - *Source:* AOMedia RTP Payload Format for AV1
- **Opus (`opus`)**
  - *Packetizer:* one Opus packet per RTP packet
  - *Depacketizer:* the same, with the duration from the table of contents
  - *Source:* RFC 7587
- **AMR and AMR-WB (`AMR`, `AMR-WB`)**
  - *Packetizer:* bandwidth-efficient and octet-aligned modes, any channel count, frame CRCs, robust sorting, frame-block interleaving, several frame-blocks per packet
  - *Depacketizer:* the same; the quality bit cleared on a CRC mismatch, redundant blocks dropped
  - *Source:* RFC 4867
- **MPEG-4 generic (`mpeg4-generic`)**
  - *Packetizer:* `AAC-hbr`, `AAC-lbr` and `generic` AU header sections (sizes, indices, CTS and DTS flags, random access flags, stream states, auxiliary section), aggregation and fragmentation
  - *Depacketizer:* the same; CTS deltas, de-interleaving by AU index (`constantDuration`, `maxDisplacement`)
  - *Source:* RFC 3640

<details>
<summary><b>How the payload formats are verified</b></summary>

ffmpeg's RTP muxer and demuxer are the black-box peer of the tests over loopback UDP: H.264, VP8, VP9, Opus,
AMR, AMR-WB and AAC go both ways, packets bit for bit; the depacketizers also pass the payload vectors of libwebrtc's unit
tests (H.264, VP8, VP9, AV1), and Smalt's decoders decode what went through a reordering network exactly as they
decode the encoders' own packets.

</details>

### Subtitles

Text subtitle tracks are `DataTrack`s with the codecs `SUBRIP`, `WEBVTT`, `MOV_TEXT` and `ASS`
(`Codec.isSubtitle()`), each packet one cue, and `TTML`, each packet a document of cues. Package `subtitle`
(core) decodes and encodes cues, reads them from any file (`SubtitleReader`, `Subtitles.read`), writes every text
subtitle file (`Subtitles.write`) and converts their markup through one styled model: bold, italic, underline
and strikeout, text and background colors, fonts and font sizes, the placement (a numpad alignment such as
`{\an8}`, the WebVTT `line`/`position`/`align` settings, an ASS `\pos`, a TTML region), karaoke times (ASS
`\k`, WebVTT timestamp tags, 3GPP `krok`, LRC word times) and the speaker (a WebVTT voice, the ASS `Name`) carry over
as far as the target holds them; WebVTT classes and regions, ruby and ASS animations, borders and shadows do not.
Decoding with the track in view (`SubtitleDecoder.decode(packet, markup)`, `SubtitleReader` with a markup, the
transcoder) gives an ASS event the bold, italic, underline, strikeout, color and alignment of its style and scales
positions and sizes from the script resolution. Legacy files without a byte order mark are decoded in the
encoding `SubtitleReaderOptions.charset` or `DemuxerOptions.charset` names, or one detected among UTF-8, UTF-16,
Shift_JIS, EUC-JP, EUC-KR, GBK, Big5, Windows-1251 and KOI8-R (Windows-1252 otherwise); every text subtitle file
is read from sources of known or unknown size.

`SubtitleRenderer` draws a cue as an RGBA picture to place over video, with a `SubtitleFont` read from a
TrueType or OpenType file with TrueType (`glyf`) outlines and a `SubtitleStyle` (em size relative to the picture
height, text and outline colors, outline width, margins, line spacing, keypad placement): bold, italic and
underline are drawn, lines wrap at spaces, and the SubRip and ASS `{\anN}` override and the WebVTT `line` and
`align` settings place the cue. The transcoder burns cues into video with it (`SubtitleBurnIn`).

- **SubRip (`.srt`)**
  - *Read:* yes: any encoding (detected or named), any line breaks, missing counters and blank lines, unsorted cues, ASS override blocks
  - *Write:* yes
  - *Package:* `container.subtitle`
  - *Source:* de facto format
- **WebVTT (`.vtt`)**
  - *Read:* yes: header with `STYLE`, `REGION` and `NOTE` blocks, identifiers, settings
  - *Write:* yes
  - *Package:* `container.subtitle`
  - *Source:* W3C WebVTT
- **SSA (`.ssa`, v4.00) and ASS (`.ass`, v4.00+)**
  - *Read:* yes: the header (info, styles, `Comment` events, `[Fonts]` and other sections) as the track configuration, events in their own `Format` order as Matroska blocks with their read order, layers, styles, names, margins and effects
  - *Write:* yes, the script's own version or converted (`AssMuxer(sink, AssScriptType)`, `OutputContainer.ASS`/`SSA`), events back in read order; Matroska `S_TEXT/SSA` for a v4.00 script
  - *Package:* `container.subtitle`
  - *Source:* SSA v4.00 and ASS v4.00+ specifications, libass behavior
- **TTML (`.ttml`, `.dfxp`), IMSC 1.0.1 to 1.3**
  - *Read:* yes: one `Codec.TTML` document, decoded to cues with the TTML timing model (par and seq, clock, frame, tick and offset times, frame rate multiplier, drop frames), referential, region and inline styles, `set` animations and region placement
  - *Write:* yes, IMSC 1.0.1 text profile documents; a single document unchanged
  - *Package:* `container.subtitle`
  - *Source:* W3C TTML1/TTML2, IMSC 1.1, checked against imscJS on the W3C IMSC test suite
- **SAMI (`.smi`)**
  - *Read:* yes: one SubRip track per language class, its `lang` and `Name`, the `META` charset
  - *Write:* yes, one class per track
  - *Package:* `container.subtitle`
  - *Source:* Microsoft SAMI
- **LRC (`.lrc`)**
  - *Read:* yes: one WebVTT track, repeated time tags, enhanced word times as timestamp tags, `offset`, `length` and ID tags
  - *Write:* yes, with the title, artist and album tags
  - *Package:* `container.subtitle`
  - *Source:* de facto format
- **MicroDVD (`.sub`)**
  - *Read:* yes: one SubRip track, the declared or given frame rate (24000/1001 by default), `y`, `c`, `f` and `s` control codes
  - *Write:* yes, at a given frame rate (0.001 to 1000, declared with three decimals and frames counted at the declared rate)
  - *Package:* `container.subtitle`
  - *Source:* de facto format
- **MP4 3GPP timed text (`tx3g`) and QuickTime text (`text`)**
  - *Read:* yes: `styl` styles, colors, fonts (`ftab`) and sizes against the default style, `hlit`/`hclr` as a background, `krok` karaoke, the justification and a `tbox` (in the default text box, or the track size without one) as the placement; QuickTime text converted to 3GPP samples (Mac OS Roman text, TextEdit style scraps)
  - *Write:* yes (`tx3g`), overlapping cues merged into gapless samples, `styl`, `hlit`, `hclr` and `krok` written, and a `tbox` for anchored cues under an entry with a default text box or in a track with a size (`tkhd` width and height)
  - *Package:* `container.mp4`
  - *Source:* 3GPP TS 26.245, QuickTime File Format
- **MP4 WebVTT (`wvtt`)**
  - *Read:* yes, one packet per cue, the pieces of split cues joined by the readers
  - *Write:* yes, overlapping cues cut into `vttc`/`vtte` samples
  - *Package:* `container.mp4`
  - *Source:* ISO/IEC 14496-30
- **MP4 TTML (`stpp`), `Codec.TTML`**
  - *Read:* yes, one document per packet with the `stpp` fields as configuration, decoded to cues
  - *Write:* yes (MP4, MOV and fragmented MP4), one document per sample, an empty document in each gap; overlapping documents rejected
  - *Package:* `container.mp4`
  - *Source:* ISO/IEC 14496-30, W3C TTML
- **Matroska SubRip (`S_TEXT/UTF8`) and ASS (`S_TEXT/ASS`, `S_TEXT/SSA`)**
  - *Read:* yes, ASS events passed through
  - *Write:* yes (Matroska only)
  - *Package:* `container.matroska`
  - *Source:* Matroska codec specs
- **Matroska and WebM WebVTT (`S_TEXT/WEBVTT`, `D_WEBVTT/*`)**
  - *Read:* yes
  - *Write:* yes, as `D_WEBVTT/SUBTITLES`
  - *Package:* `container.matroska`
  - *Source:* Matroska and WebM specs

#### Bitmap subtitles and captions

Picture subtitles are `DataTrack`s with the codecs `PGS`, `DVB_SUBTITLE` and `DVD_SUBTITLE`
(`Codec.isBitmapSubtitle()`). `BitmapSubtitleDecoder` turns their packets into `BitmapCue`s: the canvas
size, the shown regions as RGBA pictures at their positions, the times and the forced flag, with
`BitmapCue.render()` compositing them onto one canvas; `Subtitles.readBitmaps` reads the cues of any file.
Teletext (`DVB_TELETEXT`) and closed captions (`CEA_608`, `CEA_708`, `Codec.isCaption()`) decode to text
cues with `SubtitleDecoder` and `SubtitleReader`, one service at a time (`CaptionService`: a teletext page,
a CEA-608 channel or a CEA-708 service), with the italic, underline, colors and placement of the screen.
Captions carried inside video are read by `ClosedCaptions.ccData` and `CaptionExtractor` (in presentation
order) or `SubtitleReaderOptions.embeddedCaptions`; the H.264 and HEVC decoders attach them to their frames
(`FrameSideData.closedCaptions`), the H.264 encoder writes a frame's captions back as SEI messages, and the
transcoder keeps them in re-encoded H.264 video and, with `SubtitleTranscodeOptions.closedCaptions`, writes
them as a text subtitle track.

- **PGS (HDMV presentation graphics)**
  - *Read:* yes: compositions, windows, palettes (BT.601 up to 576 lines, BT.709 above), objects split over several segments, palette-only updates, the cropping rectangle and the forced flag; in `.sup`, MPEG-TS (`0x90`), M2TS and Matroska (`S_HDMV/PGS`)
  - *Write:* copied: `.sup`, MPEG-TS, Matroska
  - *Package:* `subtitle`, `container.subtitle`
  - *Source:* Blu-ray format, checked against ffmpeg (black box)
- **DVB subtitles**
  - *Read:* yes: display definitions and windows, pages and their time-out, regions, the 2, 4 and 8-bit CLUTs (default and defined, full and reduced range), pixel objects with map tables, progressively coded objects; in MPEG-TS (subtitling descriptor, composition and ancillary pages) and Matroska (`S_DVBSUB`)
  - *Write:* copied: MPEG-TS, Matroska
  - *Package:* `subtitle`
  - *Source:* ETSI EN 300 743, checked against ffmpeg (black box)
- **DVD subpictures (VobSub)**
  - *Read:* yes: the run-length coded fields, the display control commands (forced start, start and stop, palette, contrast, area, field offsets, and the color and contrast areas of command `07` by screen line and column) and the palette and size of the index; in `.idx`/`.sub`, MPEG program streams and DVD-Video objects (substreams `0x20` to `0x3F` of private stream 1), Matroska (`S_VOBSUB`) and MP4 (`mp4s` with object type `0xE0`)
  - *Write:* copied: `.idx`/`.sub`, MPEG-PS, Matroska, MP4
  - *Package:* `subtitle`, `container.subtitle`
  - *Source:* DVD-Video, checked against ffmpeg (black box)
- **EBU teletext subtitles**
  - *Read:* yes: pages by magazine and number from the teletext descriptor, the subtitle and newsflash boxes, the Latin national options, Cyrillic, Greek, Hebrew and Arabic sets with their G2 supplements (Arabic letter forms read as the letters, lam-alef as lam and alef), Hebrew and Arabic rows put in reading order, X/26 enhancements (positions, G2 characters, diacritics), X/28 and M/29 designations, colors and double height
  - *Write:* copied: MPEG-TS
  - *Package:* `subtitle`
  - *Source:* ETSI EN 300 706 and EN 300 472
- **CEA-608**
  - *Read:* yes: pop-on, roll-up and paint-on captions of the four channels, preamble and mid-row codes, the special and extended characters, repeated control codes; in H.264 and HEVC SEI (ATSC A/53 `GA94`), MPEG-2 user data, AV1 ITU-T T.35 metadata, and QuickTime `c608` tracks
  - *Write:* in H.264 SEI (re-encoded video) and QuickTime `c608` tracks
  - *Package:* `subtitle`
  - *Source:* 47 CFR 15.119, CTA-608, checked against ffmpeg's `cc_dec` (black box)
- **CEA-708**
  - *Read:* yes: DTVCC packets and service blocks, the C0, C1, G0, G1 and G2 code sets, windows (define, clear, display, hide, toggle, delete), pen attributes, colors and locations; in the same carriages as CEA-608 and QuickTime `c708` tracks
  - *Write:* in H.264 SEI (re-encoded video) and QuickTime `c708` tracks
  - *Package:* `subtitle`
  - *Source:* CTA-708, ATSC A/53

### Tags, pictures and chapters

What `Media.probe` reads and `TagEditor` writes, container by container. The metadata of image files (EXIF,
XMP, IPTC, ICC) is in [`smalt-image`](../image/README.md#metadata).

<details>
<summary><b>MP3, ADTS, raw AC-3 and E-AC-3, AMR</b></summary>

- **Tags:** ID3v2.2 (read), ID3v2.3, ID3v2.4 (tags appended with a `3DI` footer or found through a `SEEK` frame are read as ID3v2.4 section 5 says, each replacing the tags before it unless flagged as an update, and merged into the leading tag), the XMP packet in a `PRIV` frame of owner `XMP`; APEv1 (read) and APEv2 (read, updated, added or removed); ID3v1 (read, updated, added or removed); Lyrics3v2 kept, Lyrics3v1 kept while an ID3v1 tag follows it
- **Pictures:** `APIC`, APE `Cover Art (...)` items
- **Chapters:** `CHAP` and `CTOC`
- **Edited in place when:** the new ID3v2 tag fits the old tags with their padding (the trailing tags never move the audio)
- **Muxers that store tags:** `RawMuxer` (MP3, MP2, AC-3, E-AC-3, AMR), `AdtsMuxer`

</details>

<details>
<summary><b>FLAC</b></summary>

- **Tags:** `VORBIS_COMMENT`; APE and ID3v1 after the frames, as for MP3
- **Pictures:** `PICTURE` (APE cover art read)
- **Chapters:** `CHAPTERxxx` comments, else the tracks of the `CUESHEET`, which is kept while the chapters are unchanged and otherwise rebuilt from them
- **Edited in place when:** the blocks fit the old ones and the `PADDING`
- **Muxers that store tags:** `RawMuxer` (FLAC)

</details>

<details>
<summary><b>Ogg Vorbis, Opus, FLAC</b></summary>

- **Tags:** comment header of every link: the first link takes the new tags, every later link keeps its own with the fields, pictures and chapters the edit changes replaced
- **Pictures:** `METADATA_BLOCK_PICTURE`
- **Chapters:** `CHAPTERxxx` comments
- **Edited in place when:** the Vorbis or Opus comment header, padded, keeps its pages; otherwise later pages are renumbered
- **Muxers that store tags:** `OggMuxer`

</details>

<details>
<summary><b>MP4, M4A, MOV</b></summary>

- **Tags:** iTunes `ilst` (text, `trkn`, `disk`, `cpil`, `gnre`, free-form `----`; the integer atoms `tmpo`, `rtng`, `stik`, `pgap`, `hdvd`, `tvsn`, `tves`, `plID`, `cnID`, `atID`, `geID`, `sfID`, `cmID`, `akID`, `shwm` and any other atom stored as a signed or unsigned integer, as decimal text in the width and signedness the file uses; other binary atoms kept), `udta` text atoms (the copyright-sign `xyz`, `mak`, ... atoms); QuickTime keyed metadata (`meta` of handler `mdta` in `moov` or `udta`: the make, model, creation date and location keys phones write, such as `com.apple.quicktime.location.ISO6709`, read under their names with numbers as decimal text, written back in the item's data type and locale; binary items kept; a new reverse-DNS key goes to the file's keyed metadata or a new `moov/meta`); the 3GPP `loci` box as the key `loci` (ISO 6709 coordinates, place name, role, body and notes kept); the XMP packet in a top-level `uuid` box or a `udta/XMP_` atom; per track the `udta` `name`, DASH role `kind` boxes, an `ilst`, keyed metadata (`trak/meta` or `trak/udta/meta` of handler `mdta`, routed like the movie's; a new reverse-DNS key goes to a new `trak/meta`) and the `tkhd` enabled flag; the `timecode` key from and to a QuickTime `tmcd` timecode track
- **Pictures:** `covr`
- **Chapters:** Nero `chpl` and a QuickTime chapter track (a disabled `text` track with UTF-8 titles that every audio and video track references through `tref/chap`; read from `chpl`, or from the track when `chpl` lacks it or the track completes the chapters or titles a full `chpl` cut), kept while the chapters are unchanged, otherwise replaced in the space of the old one
- **Edited in place when:** `moov` fits its space, the following `free` boxes and the samples of a replaced chapter track, or ends the file; otherwise `stco`/`co64`, `tfhd` and `tfra` offsets are shifted, and a `stco` table an offset overflows becomes `co64`
- **Muxers that store tags:** `Mp4Muxer` (chapter track included; keyed metadata, `loci` and XMP), `FragmentedMp4Muxer` (chapter track in the first fragment, `chpl` only for CMAF; keyed metadata, `loci` and XMP), both with the track tags

</details>

<details>
<summary><b>Matroska, WebM</b></summary>

- **Tags:** global `Tags` (sort names as nested `SORT_WITH`, track ReplayGain in a level-30 tag), `Info` title; per track `Name`, the flag elements and the `Tag` targeting its `TrackUID`
- **Pictures:** image `Attachments` (`cover.*`); the other attachments as `MediaTags.attachments()`
- **Chapters:** `Chapters`: every edition (`MediaTags.editions()`), nested chapters, titles per language
- **Edited in place when:** the elements fit their old space and the following `Void`; otherwise the segment is rewritten with a new `SeekHead` and relocated `Cues`
- **Muxers that store tags:** `MatroskaMuxer`

</details>

<details>
<summary><b>WAV (RIFF, RIFX, RF64, BW64)</b></summary>

- **Tags:** `LIST/INFO`, `id3 ` chunk, the `bext`, `iXML`, `axml`, `chna` and `smpl` chunks under their keys (`TagEditor` lists them), the XMP packet of a `_PMX` chunk (or of the `id3 ` chunk's `PRIV` frame, written back there); a RIFF file an edit takes past 4 GiB becomes RF64 (a `ds64` chunk in place of a leading `JUNK` or inserted first)
- **Pictures:** in the `id3 ` chunk
- **Chapters:** cue points (`cue ` and `LIST adtl`), or the `id3 ` chunk
- **Edited in place when:** the chunks fit their slots and `JUNK`, or end the file
- **Muxers that store tags:** `WavMuxer`

</details>

<details>
<summary><b>AIFF</b></summary>

- **Tags:** `NAME`, `AUTH`, `(c) `, `ANNO`, `ID3 ` chunk, `INST` under its keys, `COMT` texts read as comments, the XMP packet of an `APPL` chunk of signature `XMP `
- **Pictures:** in the `ID3 ` chunk
- **Chapters:** `MARK` markers, or the `ID3 ` chunk
- **Edited in place when:** as WAV
- **Muxers that store tags:** `AiffMuxer`

</details>

<details>
<summary><b>AVI</b></summary>

- **Tags:** `LIST INFO` (the entries it has identifiers for); per track the `strn` name
- **Pictures:** none
- **Chapters:** none
- **Edited in place when:** the header list, the `INFO` list and the `JUNK` before `movi` hold the new ones; otherwise the media moves with its `indx`, `ix##` and absolute `idx1` offsets
- **Muxers that store tags:** `AviMuxer`

</details>

<details>
<summary><b>ASF (WMA, WMV)</b></summary>

- **Tags:** Content Description (title, first artist, copyright, first comment, rating), Extended Content Description and Metadata Library descriptors under the `StandardTag.asfName()` attribute names (`WM/AlbumTitle`, `WM/TrackNumber` and `WM/BeatsPerMinute` as numbers, `WM/IsCompilation` as a Boolean, `MusicBrainz/...`); the records of single streams and, with `preserveUnknown`, the byte array attributes are kept
- **Pictures:** `WM/Picture`, in the Metadata Library Object past 64 KiB
- **Chapters:** the Marker Object (one level)
- **Edited in place when:** the rebuilt header fits the old one, padded with a Padding Object; otherwise the data and index objects move, which ASF allows as it locates packets relative to the Data Object
- **Muxers that store tags:** none

</details>

<details>
<summary><b>MPEG-TS</b></summary>

- **Tags:** per track an ATSC A/65 component name and the ISO 639 `audio_type` (no global tags)
- **Pictures:** none
- **Chapters:** none
- **Edited in place when:** every copy of the program map table is rewritten in its own transport packets; an edit that outgrows them is rejected
- **Muxers that store tags:** `TsMuxer`

</details>

`StandardTag` names the tags above by one vocabulary: besides the common ones, the sort names, conductor,
lyricist, tempo (`tmpo` in MP4), ISRC, description, the ReplayGain gains and peaks of the track and the album, the
MusicBrainz identifiers (the recording in an ID3v2 `UFID` frame) and the rating (0 to 5; the ID3v2 `POPM` byte),
each under the native name its format and the common taggers use. The track tags (`MediaTags.tracks()`,
`TrackTags`) hold the name, the tags and the dispositions (`TrackDisposition`: default, forced, original,
commentary, dub, hearing or visually impaired, captions, descriptions, clean effects, karaoke) of each track;
`TagEditor` reads them for every track and replaces those of the tracks a write lists, and `Media.probe` reports
them in each stream's `tags()` and `disposition()`.

### Processing

- **Resize, crop, orient, alpha weighting**
  - *Artifact:* `smalt-core`
  - *Package:* `image.ops`
  - *Source:* Pillow (resampling structure and filters); Mitchell and Netravali (1988)
- **Color conversion and chroma upsampling**
  - *Artifact:* `smalt-core`
  - *Package:* `image.ops`
  - *Source:* libjpeg-turbo; ITU-T H.273
- **Color management: ICC v2/v4 profiles (matrix/TRC, `lut8`, `lut16`, `lutAtoB`/`lutBtoA`, floating-point `multiProcessElement` `D2Bn`/`B2Dn` tables with segmented curves, matrices and float lookup tables, gray, RGB, CMYK, multi-ink (`2CLR` to `FCLR`, with their `clrt`/`clot` colorant tables), L*a*b* and XYZ connection spaces), device link, abstract and named color (`ncl2`) profiles, chains of profiles, transforms of 8-bit, 16-bit and 32-bit floating-point samples (unclamped through matrix/TRC profiles) with the four intents and black point compensation, the black-only and black-plane preserving CMYK intents, soft-proofing transforms with a gamut check alarm, profiles built from primaries and parametric or sampled curves, L*a*b* and XYZ identities, linearization, ink limiting and transform device links, L*a*b* adjustment (brightness, contrast, hue, saturation, white balance) abstract profiles, and description and copyright edits, built-in sRGB, Display P3, Adobe RGB, ProPhoto, BT.709, BT.2020 and gray profiles, CICP mapping of every H.273 primaries set (CIE XYZ included) and transfer (the logarithmic, BT.1361 and SMPTE ST 428-1 ones included), PQ and HLG profiles read from and written as ICC v4.4 `cicp`-tagged profiles, PQ/HLG to SDR tone mapping (BT.2390 EETF), SDR to PQ/HLG with the BT.2408 reference white, PQ to and from HLG through display light (BT.2390), HDR gamut conversion and tone mapping to a target's peak**
  - *Artifact:* `smalt-core`
  - *Package:* `image.color`
  - *Source:* Little CMS 2.17 (MIT); ITU-R BT.2100, BT.2390, BT.2408, SMPTE ST 2084, ARIB STD-B67, ICC.1:2022
- **HDR gain maps: ISO 21496-1 metadata (binary and Adobe `hdrgm` XMP), rendering of an image for a display headroom to 16-bit PQ or HLG with its content light level or to 16-bit sRGB (`GainMapOptions.forSdr`), with an SDR or an HDR base rendition (`hdrgm:BaseRenditionIsHDR`, a base headroom above the alternate one, the drafts' backward direction flag) toward the alternate rendition and the headrooms between, computation of a gain map from two renditions (`GainMaps`)**
  - *Artifact:* `smalt-core`
  - *Package:* `image.gainmap`
  - *Source:* ISO 21496-1 as libavif and libultrahdr implement it, the Adobe gain map specification; libultrahdr (fraction search), libavif (gain range without outliers)
- **Audio resampling (also with a variable ratio for clock drift, `Resampler.setRatio`), channel mixing (ITU-R BS.775 rules, AC-3 center, surround and LFE mix levels, ATSC A/52 Lo/Ro and Lt/Rt downmixes, a caller's matrix, `ChannelMixerOptions`; ambisonics up to order 14 in ACN order with SN3D, N3D or Furse-Malham scaling: exact conversion between orders, normalizations and channel orders, speakers encoded as plane waves, and rendering to loudspeakers by first-order cardioids, to stereo and mono as RFC 8486 section 4 does), FIFO**
  - *Artifact:* `smalt-core`
  - *Package:* `audio.process`
  - *Source:* textbook Kaiser-windowed sinc design; ATSC A/52 section 7.8; RFC 8486; Google Spatial Audio RFC (ambisonic conventions)
- **Audio filters: second-order sections of every Audio EQ Cookbook shape (low-pass, high-pass, band-pass, notch, all-pass, peaking equalizer band, low and high shelf, `Biquad`) run in cascades with 64-bit state (`BiquadFilter`); tempo change keeping the pitch (WSOLA) and pitch change keeping the tempo (WSOLA and resampling, `TimeStretcher`)**
  - *Artifact:* `smalt-core`
  - *Package:* `audio.process`
  - *Source:* Robert Bristow-Johnson's Audio EQ Cookbook; Verhelst and Roelands (1993), WSOLA
- **Loudness: momentary, short-term and integrated loudness, loudness range, sample and true peak (ITU-R BS.1770-4, EBU R128, Tech 3341 and 3342 minimum requirements), any channel layout, bounded memory; silence detection; loudness normalization gains, single-pass normalization (`LoudnessNormalizer`) and a look-ahead true-peak limiter (`TruePeakLimiter`); ReplayGain 2.0 track and album gains, written as `REPLAYGAIN_*` tags or Opus `R128_*` tags (RFC 7845) and applied by `AudioReader` (`AudioReaderOptions.replayGain`)**
  - *Artifact:* `smalt-core`
  - *Package:* `audio.loudness`
  - *Source:* libebur128 (MIT); ReplayGain 2.0 specification, RFC 7845

#### Picture operations

Everything in `image.ops` of `smalt-core`. Inputs are never modified and results are new pictures, except
those of a `YuvConverter` (below). Every operation keeps the input's depth, 8 or 16 bits, unless it names one.
Resampling and flattening keep the input's representation; the other operations that compute new pixel
values work on packed RGBA: Y'CbCr pictures are converted first, and the result is an `RgbaPicture` (8-bit
input) or an `Rgba16Picture` (16-bit input). Lossless geometry (flips, transposes, quarter turns, crops, trims)
keeps the input's representation and depth. Large pictures are split into bands of rows on the shared pool; the
result never depends on the split. A `ChannelPicture` keeps its layout and sample type through resampling (every
channel filtered in double precision, colors premultiplied by a straight alpha, floating-point values neither
rounded nor clamped), crops, orientation and quarter turns, and color transforms of its color channels;
`ImageOps.toChannel8`, `toChannel16` and `toChannelFloat` change its sample type, and the operations that compute
RGB pixels reject it until it is converted with `toRgba` or `toRgba16`.

<details>
<summary><b>Converting between RGBA and Y'CbCr</b></summary>

`ImageOps.toRgba` and `ImageOps.toYuv` convert between packed RGBA and planar Y'CbCr (4:2:0, 4:2:2, 4:4:4,
4:4:0, monochrome) with every ITU-T H.273 matrix in limited or full range: BT.601, BT.709, BT.470BG, SMPTE 240M,
BT.2020 non-constant luminance, FCC, YCgCo and identity through fixed-point kernels, and SMPTE ST 2085, BT.2020
constant luminance, the chromaticity-derived matrices (weights from the primaries), ICtCp, IPT-C2, YCgCo-Re and
YCgCo-Ro per pixel in double precision with one rounding, applying the transfer function the description names
(an unspecified one is BT.709, or PQ for ICtCp and IPT-C2) and, for YCgCo-Re and YCgCo-Ro, the integer transform
of R'G'B' samples two or one bits shallower than the Y'CbCr samples (lossless at those depths). An unspecified
matrix is BT.601 (`MatrixCoefficients.effective()`).
`ImageOps.toRgba16` and `ImageOps.toYuv16` do the same at 16 bits and at any planar depth from 8 to 16, in
floating point with one rounding and the ITU-T H.273 quantization of each range (full range scales by
`2^d - 1`); the 8-bit conversions of a 16-bit picture also compute at its full precision and round once.
Between two Y'CbCr descriptions, a change of layout or range stays in Y'CbCr, and so does a change of matrix in
an unchanged 4:2:0, 4:2:2, 4:4:0 or 4:4:4 layout (within one code value of a conversion through RGBA for colors in the
R'G'B' cube); only the identity matrix and a matrix change together with a layout change go through RGBA, and a
conversion from or to a per-pixel matrix always goes through unclamped R'G'B'.
`YuvConverter.create(format, color)` gives the samples of `toYuv` into a picture it owns and overwrites on the
next call, so a stream of frames of one size converts without allocating:

```java
var converter = YuvConverter.create(ChromaFormat.YUV420, ColorInfo.BT601_LIMITED);
while ((frame = session.read()) != null) {
    YuvPicture yuv = converter.convert(frame.picture()); // valid until the next convert
    send(yuv);
}
```

</details>

The operations, with the class that holds each, whether it works on 16-bit pictures and what its output matches:

- **Resize, fit within, fit into a box, cover a square**
  - *Class:* `ImageOps.resize`, `fitWithin`, `fitInto`, `coverSquare`, `ResizeOptions`
  - *16-bit:* yes
  - *Matches:* Pillow `resize` with `BOX`, `BILINEAR`, `HAMMING`, `BICUBIC`, `LANCZOS` (within 1 away from the edges); ffmpeg's scaler with the same kernels (38 dB at 8 bits, 50 dB at 10 bits, 60 dB at 16 bits)
  - *Notes:* nearest, box, bilinear, Hamming, Catmull-Rom, Mitchell-Netravali, Lanczos 2/3/4, area; separable, stretched when reducing, premultiplied for transparency; 8-bit in fixed point, 16-bit and linear light (`ResizeOptions.linearLight`, sRGB transfer) in double precision, rounded once
- **Pad onto a canvas, flatten over a color**
  - *Class:* `ImageOps.pad`, `flatten`
  - *16-bit:* yes
  - *Notes:* flattening a Y'CbCr picture stays in Y'CbCr at its depth
- **Rotate by any angle (expand, keep or inscribed canvas, background color), affine transform**
  - *Class:* `ImageOps.rotate`, `affine`
  - *16-bit:* yes
  - *Matches:* Pillow `rotate` (nearest: exact; bilinear: within 1 inside the picture)
  - *Notes:* nearest, bilinear, bicubic (Catmull-Rom); premultiplied, edges antialiased against the background; right angles are exact
- **Perspective transform, quadrilateral to rectangle, mesh warp**
  - *Class:* `ImageOps.perspective`, `quad`, `mesh`, `PerspectiveTransform` (`mapping` from four corners), `Quad`, `MeshCell`
  - *16-bit:* yes
  - *Matches:* Pillow `transform` with `PERSPECTIVE`, `QUAD`, `MESH` (nearest: exact; bilinear: within 1 inside the picture)
  - *Notes:* sampling as for `affine`; points behind the horizon take the background (Pillow samples them)
- **Flips, transpose, transverse**
  - *Class:* `ImageOps.flipHorizontal` ...
  - *16-bit:* yes
  - *Notes:* same as `orient`
- **Trim uniform borders, extend (background, replicate, mirror, repeat), canvas resize with an anchor**
  - *Class:* `ImageOps.trim`, `trimBounds`, `extend`, `resizeCanvas`
  - *16-bit:* yes
  - *Notes:* trimming and cropping canvases keep the representation
- **Smart crop to an aspect ratio**
  - *Class:* `ImageOps.findSmartCrop`, `smartCrop`
  - *16-bit:* analysis at 8 bits
  - *Matches:* smartcrop.js 2.0.5 (same crop for the same pixels)
  - *Notes:* `CENTER`, `ENTROPY` (libvips idea), `ATTENTION` (smartcrop.js port)
- **Gaussian blur**
  - *Class:* `ImageFilters.gaussianBlur`
  - *16-bit:* yes
  - *Matches:* Pillow `GaussianBlur` (exact, `BOX` method)
  - *Notes:* `EXACT` (sampled kernel to 3 sigma, float) or `BOX` (3-pass extended box, O(1) in sigma); `AUTO` switches at sigma 4; premultiplied
- **Box blur**
  - *Class:* `ImageFilters.boxBlur`
  - *16-bit:* yes
  - *Matches:* Pillow `BoxBlur` (exact)
- **Unsharp mask, sharpen**
  - *Class:* `ImageFilters.unsharpMask`, `sharpen`
  - *16-bit:* yes
  - *Matches:* Pillow `UnsharpMask`, `SHARPEN` (exact / interior)
  - *Notes:* alpha kept
- **Convolution with any odd kernel up to 99x99**
  - *Class:* `ImageFilters.convolve`, `ConvolutionKernel`
  - *16-bit:* yes
  - *Matches:* Pillow `Kernel` filters inside the 1-pixel border
  - *Notes:* presets: sharpen, smooth, detail, edge enhance, find edges, emboss
- **Median, minimum, maximum, any rank, mode**
  - *Class:* `ImageFilters.median`, `minimum`, `maximum`, `rank`, `mode`
  - *16-bit:* yes
  - *Matches:* Pillow `MedianFilter`, `MinFilter`, `MaxFilter`, `RankFilter`, `ModeFilter` (exact)
  - *Notes:* sliding histograms, separable minimum and maximum, radius up to 32
- **Brightness, contrast, saturation**
  - *Class:* `ImageAdjustments`
  - *16-bit:* yes
  - *Matches:* Pillow `ImageEnhance` (exact)
- **Levels, gamma, curves (monotone cubic through points, or any function)**
  - *Class:* `ImageAdjustments.levels`, `gamma`, `curves`, `ToneCurve`
  - *16-bit:* yes
  - *Notes:* lookup tables
- **Auto levels (per channel), auto contrast (tone-preserving)**
  - *Class:* `ImageAdjustments.autoLevels`, `autoContrast`
  - *16-bit:* yes
  - *Matches:* Pillow `autocontrast` (exact)
  - *Notes:* cutoff percentage
- **Grayscale (BT.601, BT.709, linear-light luminance), sepia, colorize (duotone), tint, invert, threshold**
  - *Class:* `ImageAdjustments`
  - *16-bit:* yes
  - *Matches:* Pillow `convert("L")`, `colorize`, `invert` (exact)
- **Histogram equalization (with a mask), posterize, solarize**
  - *Class:* `ImageAdjustments.equalize`, `posterize`, `solarize`
  - *16-bit:* yes
  - *Matches:* Pillow `ImageOps.equalize`, `posterize`, `solarize` (exact)
  - *Notes:* 16-bit equalization uses the exact step
- **Hue rotation, vignette, white balance (color temperature and tint)**
  - *Class:* `ImageAdjustments.rotateHue`, `vignette`, `whiteBalance`
  - *16-bit:* yes
  - *Matches:* Pillow `HSV` round trip for the hue (within 8, Pillow quantizes HSV to 8 bits)
  - *Notes:* hue in HSV keeping value and saturation; vignette and white balance in linear light (Planckian locus, Bradford adaptation to 6504 K)
- **Histogram and channel statistics (with a mask)**
  - *Class:* `ImageOps.histogram`, `Histogram`, `ChannelStatistics`
  - *16-bit:* yes (65536 bins)
  - *Matches:* Pillow `histogram`, `ImageStat.Stat` (exact)
  - *Notes:* count, extrema, median, sum, sum of squares, mean, RMS, variance, standard deviation
- **Split channels, merge channels, replace alpha**
  - *Class:* `ImageOps.split`, `merge`, `putAlpha`
  - *16-bit:* yes, and channel pictures of every sample type
  - *Matches:* Pillow `split`, `merge`, `putalpha` (exact)
  - *Notes:* channel pictures keep their sample type and layout
- **Composite with 14 blend modes, opacity, linear-light option; watermark (anchor, margins, scale, opacity)**
  - *Class:* `ImageCompositing.composite`, `watermark`
  - *16-bit:* yes
  - *Matches:* W3C Compositing and Blending formulas
  - *Notes:* straight alpha in and out, premultiplied source-over
- **Palette quantization to 2-256 colors with dithering**
  - *Class:* `ImageQuantization.quantize`
  - *16-bit:* reduced to 8 bits
  - *Notes:* median cut or NeuQuant; none, ordered, Floyd-Steinberg or Sierra dithering; exact palettes when the colors fit; the same code as the GIF encoder

Hot loops (box filter columns, float multiply-accumulate, channel mixing, unsharp masking, blend modes) run
in a kernel family with a Vector API implementation and a bit-identical scalar twin; the per-pixel
resampling of rotations and the lookup tables stay scalar (their loads are gathers). Pillow's outputs are
checked by `PillowOracleTest` (inputs, outputs and the mapping of each call in
`tools/testdata/imageops-oracle.py`), smartcrop.js's by `SmartCropOracleTest` (`tools/testdata/smartcrop-oracle.js`).

### Transcoding

`smalt-core` package `transcode` converts files on top of the containers and the codec registries, replacing
the common ffmpeg command lines. It reads every container above plus animated GIF, APNG and WebP, and writes
MP4, M4A, fragmented MP4, QuickTime MOV, Matroska, WebM, Ogg (Opus, Vorbis, FLAC; `OGG_FLAC` picks FLAC), WAV,
Wave64, AIFF, CAF (ALAC by default), Sun AU, ADTS, LOAS, ADIF, IVF, MPEG-TS, MPEG-PS, MXF (DNxHD and 24-bit PCM by default), AVI, the raw MP3, FLAC, AMR, AC-3 and E-AC-3 streams, H.264 and HEVC Annex B and AV1 OBU
elementary streams, YUV4MPEG2 files of uncompressed 8-bit 4:2:0 frames, raw DV (`OutputContainer.DV`, see below), and SubRip, WebVTT, SSA, ASS, TTML, SAMI, LRC and MicroDVD files. Which codecs it can encode depends on the
artifacts present: H.264, HEVC, AAC, AMR, AC-3 and E-AC-3 need `smalt-mpeg`, VP9, AV1, ProRes, DNxHD, Theora and FFV1 need `smalt-video`, VP8
Motion-JPEG and Motion JPEG 2000 need `smalt-image`, Opus, FLAC, ALAC, MP3 and Vorbis need `smalt-audio`; PCM is in core. A QuickTime output gets ProRes 422 HQ by default (or DNxHR HQ),
an `.amr` output AMR-NB at 12.2 kbit/s (8 kHz mono, resampled and downmixed as needed) unless a codec or
bitrate is named, a FLAC encode keeps the source's sample size (up to 32 bits) and, written as a native
file on a seekable output, its final `STREAMINFO` (length, MD5) and seek table, and an ALAC encode (M4A, MP4, MOV
or Matroska, when the caller names ALAC) keeps the source's sample size rounded up to 16, 20, 24 or 32 bits and
stores the final `ALACSpecificConfig` (largest packet, average bitrate). A stream re-encoded without a
change of rate, layout or gain moves its samples as integers when both codecs code integers (FLAC, ALAC and integer
PCM to FLAC or PCM), so FLAC to FLAC, ALAC to FLAC and their PCM conversions are bit for bit at every sample size, and
as 64-bit floats to PCM otherwise, so 64-bit float PCM re-encodes exactly; `AudioTranscodeOptions.verifyIntegrity`
checks a FLAC source's MD5 signature on the way.

<details>
<summary><b>Raw DV output</b></summary>

A raw DV output (`OutputContainer.DV`, which needs `smalt-video` for the DV encoder) is written in the DV format
`VideoTranscodeOptions.dvFormat` names (`DvFormat`: IEC 61834 DV, DVCPRO and DVCPRO50 in 525/60 and 625/50, DVCPRO HD
1080/60i, 1080/50i, 720/60p and 720/50p), or else in the one of the source: DVCPRO HD 1080 or 720 lines by the
displayed height, consumer DV otherwise, in the 50 Hz system when the rate is nearer to 25 or 50 frames per second.
The picture, its samples made square, is fitted to the format's 4:3 or 16:9 display (16:9 when wider than 14:9)
between black bars and coded at the format's size, sample aspect ratio, frame rate (with `Pulldown.TELECINE`, from
23.976 frames per second) and BT.601 or BT.709 color; interlaced frames are scaled one field at a time and moved by a
line when their field order differs from the format's. The audio becomes 16-bit PCM at 48 kHz (44.1 or 32 kHz on
request in the consumer formats) laid into the frames: a track keeps up to two, four or eight channels at 25, 50 or
100 Mb/s (four for the 720-line formats), several tracks take a stereo pair each, and the consumer formats carry four
channels, or two tracks, at 32 kHz in 12 bits. A DV source is copied when no format is named and nothing changes.

</details>

What a transcode can do, and how it treats the streams it encodes and the streams it copies:

- **Container change (remux)**
  - *Encoded streams:* none
  - *Copied streams:* MP4, M4A and MOV output of MP4, Matroska, MPEG-TS, MPEG-PS, MXF, AVI and Ogg input goes through `Mp4Remuxer`, subtitles and several audio tracks included
- **Trim start**
  - *Encoded streams:* exact frame / sample (seeks when the demuxer can)
  - *Copied streams:* key frame (video), packet (audio); exact in MP4 through edit lists
- **Trim end, duration limit**
  - *Encoded streams:* exact frame / sample
  - *Copied streams:* exact in MP4 (edit list) and Ogg (granule)
- **Scale (fit, fill, exact, edge limits), crop, rotate**
  - *Encoded streams:* yes
  - *Copied streams:* none
- **Anamorphic video (`SampleAspectMode`)**
  - *Encoded streams:* samples kept with the ratio recorded (scaled by an uneven resize) where the container stores one, else resampled to square samples; the H.264, HEVC, MPEG-4 Part 2, H.263, Theora, FFV1 and DV encoders also signal a kept ratio in the bitstream
  - *Copied streams:* ratio kept
- **Frame rate: drop / repeat, or blend the two neighboring frames (`FrameRateConversion`)**
  - *Encoded streams:* yes
  - *Copied streams:* none
- **Pixel format: chroma format, bit depth (10 bits by default for PQ and HLG output), matrix and range**
  - *Encoded streams:* yes
  - *Copied streams:* none
- **Color: primaries and transfer conversion through the color profiles (`VideoTranscodeOptions.color`, `colorConversion`): HDR10 / HLG to SDR with the BT.2390 EETF (or clipping), SDR to PQ / HLG at the BT.2408 reference white, PQ to and from HLG, gamut conversion; the HDR metadata of the track used when the pictures carry none; the output and the encoder declare the color**
  - *Encoded streams:* yes
  - *Copied streams:* none
- **Deinterlacing (`Deinterlacer`: bob, linear, edge-based line average) of frames their decoder or track flags interlaced, at the frame or the field rate**
  - *Encoded streams:* yes
  - *Copied streams:* none
- **2:3 pulldown (`Pulldown.TELECINE`, 24 to 30 frames per second, top field first) and inverse telecine (`Pulldown.INVERSE_TELECINE`: field matching and decimation, 30 to 24)**
  - *Encoded streams:* yes
  - *Copied streams:* none
- **Subtitle burn-in (`SubtitleBurnIn`: a text subtitle track of the input or given cues) and watermark (`VideoTranscodeOptions.watermark`)**
  - *Encoded streams:* yes
  - *Copied streams:* none
- **Key frame interval, or no periodic key frames (`VideoTranscodeOptions.keyframeInterval`, `VideoEncoderOptions.keyframeInterval`)**
  - *Encoded streams:* yes (H.264, HEVC, VP8, VP9, AV1, FFV1, Theora; ProRes, DNxHD and Motion-JPEG are all key frames)
  - *Copied streams:* none
- **Resample, remix (mix levels, Lt/Rt, a caller's matrix: `AudioTranscodeOptions.mixing`), gain, sample format (PCM codec)**
  - *Encoded streams:* yes
  - *Copied streams:* none
- **Loudness normalization (two passes: measure the kept range, then apply the gain under a true-peak ceiling, or reach the target and keep the ceiling with a true-peak limiter; or one pass that follows the loudness as it encodes, `singlePassNormalization`)**
  - *Encoded streams:* yes
  - *Copied streams:* none
- **Audio filters and equalizer (`AudioTranscodeOptions.filters`: `Biquad` high-pass, low-pass, peaking bands, shelves, notch, band-pass, all-pass)**
  - *Encoded streams:* yes
  - *Copied streams:* none
- **Fades in and out (`Fade`, `FadeCurve`: linear, quarter and half sine, quadratic, cubic, exponential; audio to silence, video to `VideoTranscodeOptions.fadeColor`, at a time or at the end of the output)**
  - *Encoded streams:* yes
  - *Copied streams:* none
- **Speed change (`TranscodeOptions.speed`, 0.1 to 10: video timestamps divided, audio time-scaled keeping its pitch or, with `preservePitch(false)`, resampled) and pitch change (`AudioTranscodeOptions.pitch`)**
  - *Encoded streams:* yes, the output exactly the range over the speed
  - *Copied streams:* none
- **Silence trimming (`TranscodeOptions.silenceTrim`, `SilenceTrim`: a first pass finds the silences of the first audio stream; the leading and trailing ones move the range of every stream, the longer pauses are shortened in audio-only outputs)**
  - *Encoded streams:* yes
  - *Copied streams:* trimmed to the moved range, as a trim is
- **Concatenation (`TranscodeInput.concat`, `Transcoder.concat`: each file starts where the previous one ends, timestamps continuous; chapters joined)**
  - *Encoded streams:* each file decoded with its own decoder (another codec, rate or layout allowed), the audio padded with silence or cut at each join to stay in step with the video
  - *Copied streams:* when every file has the same codec and codec configuration
- **Several inputs (`Transcoder.transcode(List<TranscodeInput>, ...)`, `input(int)` on the stream options: the video of one file, the audio of another, the subtitles of a third), interleaved by time**
  - *Encoded streams:* yes
  - *Copied streams:* yes
- **Several outputs from one read (`TranscodeOutput`, `Transcoder.transcode(MediaSource, List<TranscodeOutput>)`: the renditions of a ladder for `Packager`)**
  - *Encoded streams:* the encodes of a video track share one decoder
  - *Copied streams:* yes
- **Subtitles: SubRip, WebVTT, 3GPP timed text, ASS, TTML**
  - *Encoded streams:* converted to what the container carries (also to `.srt`, `.vtt`, `.ass`, `.ssa`, `.ttml`, `.smi`, `.lrc` and `.sub` files), cut to the kept range; a cue still shown at a trimmed start is kept however early it began
  - *Copied streams:* copied, retimed and cut to the kept range, likewise
- **Bitmap subtitles (PGS, DVB, VobSub), teletext, closed captions**
  - *Encoded streams:* teletext and caption tracks, and with `SubtitleTranscodeOptions.closedCaptions` the captions inside video, converted to a text subtitle track; captions kept in re-encoded H.264 video
  - *Copied streams:* bitmap and teletext tracks copied where the container carries them, retimed and cut to the kept range
- **Image convert, scale, crop, flatten, byte budget**
  - *Encoded streams:* yes (first frame of animations; animated output needs an animation encoder; an animation fits the budget by quality, then size, then dropped frames)
  - *Copied streams:* byte-identical pass-through when nothing changes
- **Tags, cover art, chapters, track names, tags and dispositions (`TranscodeOptions.tagMode`, also `RemuxOptions` and `PackagingOptions`: the input's by default, `noTags()`, `tags(...)` to replace them, `mergeTags(...)` to update them)**
  - *Encoded streams:* written in the output's own form (`Muxer.setTags`, `OutputContainer.supportsTags`), chapters cut to the kept range and moved to its start, track tags following their tracks; the Matroska statistics tags of the input's streams are left out (the output declares its own durations), an encoded or trimmed stream loses its encoder tags, the file its encoder once a stream is encoded and its ReplayGain once a gain or a loudness normalization changes the level; input values the output rejects are left out, also those that fit alone but not together (two large covers in one FLAC comment block)
  - *Copied streams:* likewise; the comments and pictures a copied FLAC or Vorbis configuration embeds are removed in Matroska, MP4 and Ogg, whose own tags replace them
- **Image color and metadata (`ImageTranscodeOptions.color`, `metadata`)**
  - *Encoded streams:* the input's ICC profile recorded with its pixels (`ColorMode.KEEP_PROFILE`, the default; converted to sRGB where the output format cannot record it) or the pixels converted to sRGB (`CONVERT_TO_SRGB`); the EXIF block and XMP packet kept (`MetadataMode.KEEP`, the default: orientation reset since the pixels are turned upright, `PixelXDimension` and `PixelYDimension` updated, the stale thumbnail removed, the maker note kept in place), stripped (`STRIP`) or kept without the GPS directory, the `exif:GPS` properties and the XMP place names (`STRIP_LOCATION`)
  - *Copied streams:* kept byte for byte with `KEEP` and `KEEP_PROFILE`

## Known limits

> [!WARNING]
> These are the cases where Smalt rejects a file, reads or writes it differently from another tool, or leaves
> a feature out. They are grouped by area; open the entry you need. Limits of a codec live in the README of
> its artifact.

### Containers

<details>
<summary><b>Elementary streams</b></summary>

The reorder depth and latency that place Annex B pictures and shift their decoding times come
from the first sequence parameter set, so a later sequence that reorders deeper is not accounted for. The pictures
an IDR or IRAP picture with `no_output_of_prior_pics_flag` discards are found by modelling the decoder's output
process (reordering, latency and buffer fullness, with HEVC reference picture sets and H.264 sliding window and
memory management marking applied field by field, and the frames a gap in `frame_num` infers filling the buffer).
A frame rate change that the track's time base (one field of the first rate) cannot represent
is rounded to it, the error carried from picture to picture. H.263 PB-frames (Annex G and M) are timed as one
picture, at the time of their P-picture.

</details>

<details>
<summary><b>ASF</b></summary>

Files are read, not written (no ASF muxer). The Windows Media codecs other than VC-1 (WMA 1, 2, Pro, Lossless
and Voice, WMV 7 and 8, MS-MPEG4) are demuxed as unknown tracks: Microsoft publishes no specification of them
and no permissive implementation exists. Files protected by DRM (content encryption objects, encrypted streams)
raise `MediaException.Unsupported`. Script commands sent as a command media stream instead of the Script Command
Object are not read. Seeking does not use the Simple
Index or Index Objects: it finds the data packets by their send times, which every file has. A tag edit stores
the whole-file tags without the language index a Metadata Library record had.

</details>

<details>
<summary><b>MXF</b></summary>

The essence of a file package is read whole: a material package that plays part of it, or plays the file
packages of several OP-Atom files together, is not applied, and essence in other files (external essence, OP-Atom
sets) is not opened; each OP-Atom file is read on its own. Clip-wrapped picture essence needs an index table to be
split into frames (`MediaException.Unsupported` without one). Reordered AVC whose index has no temporal offsets
(as ffmpeg writes it) takes its presentation order from its picture order counts, read ahead to the next IDR
picture; a stream of order count type 1, or read once from a stream that cannot hold the read-ahead, has no
presentation timestamps. A headerless AVC-Intra stream is decoded with the parameter sets AVC-Intra encoders write
for its class and raster (RP 2027 fixes the profile, level, raster, timing and frame layout but does not state the
sets); only the 18 rasters with a coding label are known, other headerless streams raise
`MediaException.Malformed`. `MxfMuxer` writes no JPEG 2000 (whose mapping requires a JPEG 2000 sub-descriptor), no
descriptive metadata, keeps the `title` tag, the `timecode` tag and the other tags as
user comments but no pictures, chapters or attachments, needs one edit rate for every picture track, and needs a
seekable sink for OP-Atom and clip wrapping.

</details>

<details>
<summary><b>FLV</b></summary>

Timestamps keep the millisecond time base of the tags, so the nanosecond offsets of Enhanced RTMP `ModEx`
data are read past, not applied, and the frame period of a video packet's duration is rounded to a millisecond.
The track list comes from the start of the file (at least its first second); a track of a multitrack file that
starts later is skipped. Screen video, VP6, VVC, Nellymoser and the SWF ADPCM are identified but not decoded, and
encrypted tags (FLV Annex F) raise `MediaException.Unsupported`. A seek through the `onMetaData` key frame index
sees a codec configuration change only when a sequence header lies among the tags it walks back over; a seek
through the full index (a file without that index) always restores the configuration in force. The muxer writes no
multichannel configuration messages, ModEx data or AMF3 script data.

</details>

<details>
<summary><b>MPEG-PS</b></summary>

A stream that first appears after the first 8 MiB is found only when the source has random access or fits
its memory budget, by sampling 32 windows of the rest of the file, so a stream heard only between those windows is
missed. Navigation packs written by `PsMuxer` carry the general, search (`VOBU_SRI`) and synchronous (`SYNCI`)
information; the highlight information is zero (no menu buttons), as are the seamless playback and angle fields of
a single-angle cell, and no IFO files are written, so a DVD authoring tool adds them. On a sink that cannot seek,
search pointers and synchronous addresses beyond `PsMuxerOptions.navigationLookaheadMicros()` (15 s by default)
address the farthest unit held or read as no data. HD DVD and DVD-Audio substreams other than
AC-3, E-AC-3, DTS and DVD-Video linear PCM (TrueHD, MLP, SDDS, DVD-Audio linear PCM) and extended streams such as
VC-1 are exposed as private data, one PES payload per packet. CSS-scrambled packets are dropped. An MPEG-4 Part 2
picture whose PES packet carries no timestamp follows the previous one by the frame rate of its video object
layer, and gets no timestamp when the layer declares none. A read of 20-bit or 24-bit linear PCM that starts
inside the stream (after a seek) starts at the audio frame `first_access_unit_pointer` locates; ffmpeg writes
that pointer as 4 in every PES packet while cutting sample groups across PES packets of multichannel streams, so
such a file can resume misaligned after a seek (a read from the start is exact). The system clock references
`PsMuxer` writes follow the decoding times and the mux rate; the system target decoder buffers are not modeled.
DVD linear PCM is written in whole sample groups, so `PsMuxer` completes a last 20-bit or 24-bit group that the
track's samples leave short with up to three silent sample frames.

</details>

<details>
<summary><b>MPEG-TS</b></summary>

Of a LATM stream with several programs or layers only the first stream (program 0, layer 0) is exposed,
so an object whose layers sit in separate LATM streams (a CELP base layer and its enhancement layers) decodes its
base layer. Blu-ray linear PCM of 20-bit words is read as three-byte
words whose low four bits are zero; no sample of it was available to confirm the layout (ffmpeg does not decode
it). SMPTE ST 302 streams carrying non-PCM data (SMPTE 337) are returned as PCM samples. Program map changes after
the probe window are found by reading the map near the end of the file and bisecting towards the probe window
while it differs from the map in force, so a map that appears and is replaced by the one in force again is
missed, and so is a change in a file whose last 32 MiB hold no map table; a stream read once that does not fit
its memory budget exposes only the tracks of the probe window. AV1 temporal units that span several access units are grouped at their shown frame unless the stream
carries temporal delimiters, so spatial layers without delimiters become one packet per layer.

</details>

<details>
<summary><b>Ogg</b></summary>

A chained link that reuses a serial number of the link before it (which RFC 3533 forbids) is found in a file
of up to 64 MiB, which is scanned, but in a larger file and in `TagEditor` only when bisection meets one of its
first pages. A source of unknown size with random access is measured first and then read as a sized one; a
stream read once whose size is unknown or exceeds its memory budget describes its later links only as `read()`
reaches them, without durations, and seeks only forward, and a track whose key frame before the target was
already read restarts at its next one. A transcode keeps its later links when the options take every audio
track and the output can add tracks after its first packet: MP4, M4A and MOV, and Matroska and WebM on a
seekable sink; any other output that carries several audio tracks (Ogg, or Matroska and WebM written to a
stream) fails with `MediaException.Unsupported` when a later link arrives. The output does not carry the
track tags of the later links (their packets and codec configurations are kept).
Ogg Skeleton 4 `index` packets and the `fishead` presentation and base times are returned or kept but not used,
and the packets of an unknown stream that precede its `fisbone` carry no timestamps.

</details>

<details>
<summary><b>WAV</b></summary>

Unframed AAC of the error-resilient object types (ER AAC LC, LTP, scalable, LD and ELD) raises
`MediaException.Unsupported`: their raw data blocks end with extension payloads that fill the access unit,
so only a transport can delimit them, and the raw payload of `WAVE_FORMAT_MPEG_HEAAC` is defined over the
blocks of the other object types. IEC 61937 bursts of ATRAC, WMA
Professional, DST and one-bit audio raise `MediaException.Unsupported` when the format declares them and
otherwise read as the 16-bit PCM that carries them, since Smalt has no codec for them. The MAT frame layout
(start, middle and end codes at fixed offsets of each 61424-byte payload) follows ffmpeg's `spdif` muxer,
since IEC 61937-9 and the MAT specification are not public. DTS and TrueHD tracks are demuxed only; Smalt
has no decoder for them. Opus in WAV is read in the self-delimiting framing, since
RIFF defines no other way to delimit Opus packets; without an `OpusHead` in the format's extra bytes the
pre-skip is taken as zero; other readers (ffmpeg among them) have no Opus in WAV, so `WavMuxer` writes it
for Smalt and readers of that framing. In IEC 61937 bursts `WavMuxer` writes AAC only as ADTS frames of 1024
samples (IEC 61937-6; the LOAS bursts of IEC 61937-11 are read but not written, since that part could not be
checked), no MPEG-2.5 or free-format MPEG audio (which IEC 61937-4 does not carry), DTS-HD always at the high
bit rate (8 channels at four times the sampling rate, which carries any DTS-HD stream), and only the core frame of a
DTS stream whose first packet has no extension substream; a DTS core frame that fills its repetition period is
stored without a preamble, as on a DTS audio CD. The MAT slots of 2560 bytes and the 16-byte rounding of the DTS-HD
length code follow ffmpeg's `spdif` muxer. `WavMuxer` writes no AAC of object types other than Main, LC, SSR
and LTP (the error resilient LD and ELD, USAC), whose access units neither ADTS nor the raw payload of
`WAVE_FORMAT_MPEG_HEAAC` delimits and which `WavDemuxer` therefore does not read either. The AVI and Matroska muxers take
the block size of IMA and Microsoft ADPCM from the track's first packet, as the track does not declare it; a
Matroska header written before that packet (live output, or a queue full of other tracks' packets) declares 256
bytes per channel and rejects larger blocks. 3-bit IMA ADPCM, plain 33-byte GSM
frames in WAV, the other G.722 and G.726 format tags (`0x0064`, `0x0065`, `0x0140`, whose code packing no
available writer shows), multichannel G.722, G.726 and GSM 06.10, and ADPCM in a big-endian RIFX file raise
`MediaException.Unsupported`. A transcode into WAV copies coded audio only when its options name the codec or
ask to copy, or when no installed decoder decodes it; otherwise it decodes it to PCM. On a streaming sink the
`fmt ` and `mext` fields of coded audio describe only the first packet (a block alignment of 1, an unknown bit
rate, data not known to be homogeneous). The `fact` count of ADPCM honors a packet's duration only as far as the
track's time base resolves it, so a last packet shorter than its codes by less than one tick of a coarse time base
keeps its whole count. AIFF files are written with samples of at most 32 bits, the limit of the AIFF sample size;
64-bit AIFF samples are read.

</details>

<details>
<summary><b>CAF</b></summary>

`CafMuxer` writes packets of variable size (AAC, ALAC, Opus, MPEG audio, AC-3) only to a seekable sink,
since the packet table they need must precede a `data` chunk of unknown size; constant-size formats stream. CAF
formats other than those of the Containers list (for example AMR, iLBC, MACE, QDesign) raise
`MediaException.Unsupported` naming the format. Sony Wave64: the `summarylist` layout is
confirmed only by ffprobe's reading of it, the `list` chunk (read as a RIFF `LIST`) by no reader, and `TagEditor`
does not edit Wave64 files. Sun AU encodings other than those of the Containers list
(the DSP, 12-bit, indirect and multichannel ADPCM encodings) raise `MediaException.Unsupported`; ffmpeg writes
3 and 5-bit G.723 as encoding 23 (the G.721 code), which `AuDemuxer` therefore reads as 4-bit G.721, while
`AuMuxer` writes the encodings 25 and 26 the format defines. LOAS: only the first program and layer of a
multiplex become a track, and `LoasMuxer` writes one program of one layer. ADIF: only the first program
configuration element of the header describes the track, and `AdifMuxer` rejects 960-sample frames and object
types other than AAC Main, LC, SSR and LTP, which the header cannot express.

</details>

<details>
<summary><b>ADTS</b></summary>

ADTS headers have no field for 960-sample frames, channel configuration 0
(program config element) or configurations 11 to 14, so the ADTS and MPEG-TS muxers refuse such streams; MP4 and
Matroska carry them. Channel configuration 7 follows ISO/IEC 14496-3 (its first channel pair feeds the front left
and right of center, the second the outside front pair), as Smalt's decoder reads it; ffmpeg 7.1 reads it as 7.1
with front, side and back pairs, and outputs the channels of a program config element with a side pair in coding
order without naming their loudspeakers. ffmpeg 7.1 also ignores the height extension of a program config element,
so it names a height loudspeaker as a normal one, or names none.

</details>

<details>
<summary><b>FLAC and ALAC in Matroska and Ogg written to a stream</b></summary>

FLAC in Matroska and Ogg written to a stream (or as live Matroska) keeps the initial `STREAMINFO`, so the final
length and MD5 reach only files on a seekable sink; likewise an ALAC track in such Matroska output and in
fragmented MP4 keeps a configuration whose largest packet size and average bitrate are zero (unknown). DNxHD
and DNxHR premultiplied alpha is written in band only, and the sample descriptions Smalt writes carry the
`AALP` extension of Avid's but not its `ACLR`, `APRG` and `ARES` ones (kept on remux).

</details>

<details>
<summary><b>MP4 compressed boxes and compressed movies</b></summary>

The MP4 demuxer reads the compressed boxes of ISO/IEC 14496-12 clause 8.20 (`!mov`, `!mof`, `!six`, `!ssx`)
and QuickTime compressed movies (`cmov` with the `zlib` algorithm, the only one QuickTime writes; another raises
`MediaException.Unsupported`), and writing the tags of such a file stores its movie box uncompressed; a tag
write that moves the data of compressed movie fragments with explicit base data offsets recompresses them in boxes
of their old size, and raises `MediaException.Unsupported` only when a fragment no longer fits its box and the
movie box addresses media data after it. The demuxer rejects the FEC reservoir and hint tracks of 3GPP MBMS files (`mdhd`
timescale 0) as malformed.

</details>

<details>
<summary><b>Samples and HEIF items stored in other files</b></summary>

Samples and HEIF items stored in other files are read only through a `DataReferenceResolver` given in
`DemuxerOptions` or `ImageDecoderOptions` (none by default, so such files raise `MediaException.Unsupported`); the
data reference kinds that name no file Smalt can describe (`imdt`, `snim`, QuickTime `rsrc`) are unsupported.
`DataReferenceResolver.directory` opens only files inside its directory, an alias by its file name. Only the
demuxers opened with `DemuxerOptions` (`Mp4Demuxer`, `Containers.openDemuxer`) and the HEIC and AVIF still image
decoders resolve references; probing, `AudioReader`, `VideoReader`, `SubtitleReader`, thumbnails, remuxing,
transcoding and image sequences (the movie of an AVIF or HEIC file) do not. A movie read once from a stream,
one of whose tracks keeps samples both in the file and in another one, is held in memory from its first sample
on, since the demuxer cannot tell which of its bytes are no longer needed.

</details>

<details>
<summary><b>Matroska virtual timelines</b></summary>

Chapter processing commands (`ChapProcess`), `ChapterTranslate` and `TrackTranslate`
are not run, and hidden editions cannot be played. The linked segments are opened when the demuxer is created,
through a resolver the caller supplies (none by default; the file's directory for `MatroskaDemuxer.open` and
`Transcoder.transcode(Path, ...)`), and a chapter whose segment it does not supply plays nothing. A segment that
names neither a segment before it nor one after it is presented alone, even when another file names it as its
predecessor. The tracks of a linked segment feed the file's own tracks of the same number, kind and codec, or of
the same kind and codec in order; a linked track with another sample rate keeps its packets' timestamps on the
timeline but is decoded as the file's own track. Packets flagged `PacketFlag.DISCARD` (the decode-only frames
before and after a part, and the audio frames before a part that rebuild the decoder state) and the audio packets
a part cuts are carried by the muxers as the MP4 edits below describe. The audio cut at a part boundary follows
the block timestamps less the codec delay (the Opus pre-skip, a `CodecDelay`), so it is exact to the sample only
when they count samples: it lands as far off as they are, up to half a millisecond with the usual millisecond
`TimestampScale` for a codec whose frames do not last whole milliseconds (AAC, MP3), and a millisecond in the Opus
files ffmpeg writes, which store every block after the first a millisecond late. Opus parts after a jump match a
decode of the stored file in place but not bit for bit, as after a seek. An audio track other than Opus reports
as its `skipSamples` the codec delay of the segment the first part reads, the priming its decoder drops. A seek
restarts the audio at its frame straddling the target, without the decode-only frames before it, except a seek
before the timeline, which gives the packets of a read from the start. A seek that lands within a part of another
segment than the file's own gives its first packet the codec configuration of that segment as
`PacketSideData.newCodecConfig`, so the packets after a seek are not always those a read from the start gives
from there.

</details>

<details>
<summary><b>MP4 edit lists</b></summary>

MP4 edits play at normal speed whatever their media rate other than 0. Packets flagged
`PacketFlag.DISCARD` (the decode-only pre-roll of a multi-segment edit list) are written as presented samples
by every muxer but Matroska (which marks them invisible), and the audio packets an edit cuts lose their discard
start in every muxer and their discard end in every muxer but Matroska for Opus and AAC (`DiscardPadding`), so a copy
of such a track into another container outside `Mp4Remuxer` shows the pre-roll and plays the cut audio whole; the transcoder re-encodes it correctly. After an edit that goes back in
the media, an AAC object type that predicts from the start of the stream (Main, LTP, LD, ELD with low delay
SBR) converges during its pre-roll instead of matching a decode of the whole stream. A fragmented MP4, Ogg, WAV, AIFF or subtitle file cannot store a codec configuration change,
and an MP4 sample description of another codec than the track's first is rejected when a sample uses it.

</details>

<details>
<summary><b>MP4 muxing</b></summary>

A fragmented file with a
single segment index of more than 65535 fragments indexes them hierarchically (`sidx` boxes referencing
`sidx` boxes), which ffmpeg (through 2026 builds) refuses to open; DASH players read it. ffmpeg applies no
edit list to a fragmented file, reports no duration for the text samples of a fragment and has no WebVTT
in MP4 reader. PCM tracks keep no gaps between packets, and 8-bit PCM, A-law and mu-law have no ISO sample
entry, so only QuickTime files carry them; M4A files carry no PCM (a transcode into M4A encodes it).

</details>

<details>
<summary><b>Container providers</b></summary>

The transcoder writes only the `OutputContainer` profiles, so a container a provider adds
that `ContainerFormat` does not name (`ContainerFormat.OTHER`) is a transcode input but not a transcode output;
`Containers.openMuxer(String, MediaSink)` writes it.

</details>

### Streaming, RTP and encryption

<details>
<summary><b>RTP payload formats</b></summary>

The AV1 packetizer writes the dependency descriptor of a single-layer stream only (one
frame per temporal unit), and the depacketizer does not use the descriptor, whose structures
`Av1DependencyDescriptor` reads for any scalability mode; the VP9 packetizer writes only the non-flexible mode
with one spatial layer, and the H.264 interleaved packetizer sends NAL units in decoding order. ffmpeg has no AV1 RTP payload format, so AV1 is tested against the libwebrtc
vectors and round trips only, and no AMR implementation with frame CRCs, robust sorting or interleaving was
available to interoperate with.

</details>

<details>
<summary><b>Streaming</b></summary>

The HLS demuxer does not decrypt `SAMPLE-AES` packed audio segments, and reads an I-frame playlist of
fragmented MP4 segments by the first sample of each range alone (timed by `tfdt` and the composition offset, without the edit
list, not decrypted). The DASH demuxer reads each period as streams of their own (a representation continued
across periods is not joined), does not resolve `xlink` references or `$SubNumber$`, and needs a `publishTime` to
list the segments of a live representation addressed by duration. The packager writes HLS subtitles as WebVTT
only (IMSC in fragmented MP4 is written for DASH), packages no bitmap subtitles or closed captions, addresses only
the first key frame of each CMAF segment in I-frame playlists, and cuts segments only at key frames, so a segment
longer than the HLS target duration (`PackagingOptions.targetDuration`, or the longest segment of the first
version of an event or live playlist, which RFC 8216 forbids changing) is rejected with
`MediaException.Unsupported` rather than split. The `SUPPLEMENTAL-CODECS` of a backward compatible Dolby Vision
stream is written in HLS only; the DASH manifest names the base codec alone.

</details>

<details>
<summary><b>Encryption</b></summary>

Keys are clear keys only; no digital rights management system is contacted and `pssh` data is not
interpreted. Decryption is a demuxer and remuxer option (`DemuxerOptions.decryptionKeys`,
`RemuxOptions.decryptionKeys`): `Media.probe`, the readers and the transcoder open protected files without keys
and reject them. The WebM encryption of codec private data and the encryption schemes other than Common
Encryption, PIFF and WebM AES-CTR are rejected as unsupported. The multi-key encryption of ISO/IEC 23001-7:2023
is read in the layout of the MPEG conformance files `*_mkey_*`, as no specification of it is available to the
project, and `FragmentedMp4Muxer` writes one key per track. `cbcs` samples with per-sample
initialization vectors restart the cipher block chain at each subsample, as Shaka Packager and ffmpeg read them;
the MPEG conformance file `video_cbcs.mp4` chains across subsamples. `FragmentedMp4Muxer` leaves clear the H.264
and HEVC slices whose header cannot be read (of layers above the base layer or with screen content coding
extensions), the VP9 frames whose uncompressed header cannot be read (an inter frame naming a reference no frame
filled), the AV1 samples whose OBUs cannot be read and the H.264 slice data partitions (as Shaka Packager does),
and rejects a video sample whose auxiliary information would exceed the 255 bytes of a `saiz` entry (more than
about 40 slices, VP9 frames or AV1 tiles); `Mp4Muxer` writes no encryption. SAMPLE-AES transport
streams carry H.264, AAC, AC-3 and E-AC-3 only, as the format defines; their decryption catches a wrong key by
the CRC of the first AC-3 or E-AC-3 syncframe, so a wrong key for a stream with only H.264 and AAC, which carry no
checksum, gives garbled packets rather than an error.

</details>

### Tags, chapters and track metadata

<details>
<summary><b>MP4 tags</b></summary>

A CMAF track file from `FragmentedMp4Muxer` stores chapters in the Nero `chpl` box only, since it
carries one track; other fragmented files also get a QuickTime chapter track, whose samples are in the first
fragment. A last chapter without an end gets its length when the output is seekable and a sample duration of
zero otherwise (a streaming sink or segment sinks), which Smalt reads as an end at the end of the movie and
other readers may read as an empty chapter. A binary keyed
metadata item is kept while its key is unchanged and is not counted as a location, so
`TagEditor.stripLocation` removes only the text and number items of the location keys (the ones phones write). When `TagEditor` replaces the chapter track of a fragmented file whose
fragments carry that track's samples, its track fragments (and `tfra` tables) become `free` boxes of the same
size and the old track is removed, its sample bytes left unused in the `mdat`; when the file has a movie
fragment or `mfra` box larger than 64 MiB, the old track stays as a `text` track that no chapter reference names, since a
track fragment must name a track of the movie. The iTunes integer atoms
`TagEditor` and `Mp4Muxer` know take decimal integers their width holds; other values are rejected, except by
`TagEditor` for an atom the file already stores as text, which stays text.

</details>

<details>
<summary><b>Timecode</b></summary>

`Mp4Muxer`, `FragmentedMp4Muxer` and `TagEditor` write a QuickTime timecode track, but a CMAF track
file (one track) stores a `timecode` tag as a free-form iTunes item (which `Mp4Demuxer.timecode()` reads when the
file has no timecode track), and `TagEditor` keeps such an item of another file while it is unchanged. A new
timecode track counts at the first video track's frame rate, so without one only a drop-frame address is written
(at 30000/1001 frames per second). When the timecode track of a fragmented file has its sample in a movie fragment,
`TagEditor` writes the new address into that sample at the track's own rate; removing that timecode removes the
track and turns its track fragments into `free` boxes of the same size, leaving its sample in the media data. The
timecode sample of a fragmented file lasts its
first fragment. A counter timecode track (`tmcd` flag 8) gives no timecode. Matroska and other formats store the timecode as a tag only.

</details>

<details>
<summary><b>Chapters</b></summary>

Hidden Matroska editions and chapters are not reported (the visible chapters nested in a hidden chapter
are reported in its place, and the hidden chapters of a played ordered edition play), and a `TagEditor` edit that changes the chapters rewrites them without the hidden ones, the chapter UIDs,
segment links and chapter processing commands. The formats other than Matroska store one edition of
top-level chapters, without localized titles. A FLAC `CUESHEET` gives untitled chapters starting at each track's
index point 1, its other index points, ISRC and catalog numbers are not read, and the cue sheet written from
chapters (replacing the file's when an edit changes them) is not a CD-DA one; a WAV cue point's `note` entries are not read; AIFF markers carry no end, so a
chapter ends where the next starts, and `COMT` comments are written back as `ANNO` chunks. A `smpl` chunk's
sampler-specific data is dropped when the chunk is rewritten.

</details>

<details>
<summary><b>Spherical video</b></summary>

A mesh projection is carried as the bytes of its `mshp` box, not interpreted. The MP4 `st3d`
box stores the mono, top-bottom (left eye first) and side-by-side modes only, so the other stereo modes are
written to Matroska only (and to WebM only those of the WebM specification); its stereo-custom layout is not
read. The pose is carried as stored between MP4 and Matroska, in the directions of the Spherical Video V2 WebM
binding.

</details>

<details>
<summary><b>WebM</b></summary>

The muxer and `TagEditor` write the chapters of the default edition only, without edition flags, names or
BCP 47 title languages, and add no attachment that is not a picture, as the WebM specification allows none of
them.

</details>

<details>
<summary><b>Track tags</b></summary>

MP4 stores `TrackDisposition.CAPTIONS` and `HEARING_IMPAIRED` as one `caption` role and
`DESCRIPTIONS` and `VISUAL_IMPAIRED` as one `description` role, read back as both; `ORIGINAL` and
`CLEAN_EFFECTS` have no MP4 role. MP4 marks the default track by the `tkhd` enabled flag, which players also use to
skip disabled tracks, so the first video and the first audio track stay enabled when no track of their kind is
the default. MP4 track names are plain text in the `udta` `name` box, as QuickTime, ffmpeg and exiftool write and
read it; GPAC's MP4Box reads that box as a full box. AVI stores only the track names, and a transport stream only
the name and the `audio_type` of the ISO 639 language descriptor (one of clean effects, hearing impaired, visually
impaired) of its tracks and no global tags (`TsMuxer` drops them and `TagEditor` rejects them). `TagEditor` edits a
transport stream in place only: every copy of the program map table must start its own packets and keep them to
itself, and the edited table must fit their payload (a table usually fills a fraction of its packet; 204-byte
packets whose Reed-Solomon parity is a code rather than filler are not edited). AVI stores no pictures or chapters (`TagEditor`
rejects them) and of the other tags only those `INFO` has an identifier for, the first value of each.

</details>

<details>
<summary><b>Tag vocabulary</b></summary>

The RIFF `INFO` identifier `ISRC` means the source, not the ISRC, and is read under the key
`INFO:ISRC`. A rating that is not a number from 0 to 5 is stored in ID3v2 as a `TXXX` frame, not in `POPM`.

</details>

<details>
<summary><b>Tag editing</b></summary>

The APE and ID3v1 tags `TagEditor` writes mirror what their formats hold (APE keys of printable ASCII,
one picture per role), the ID3v2 tag holding everything. APE tags without a footer (a header-only tag at the start
of a file) are not recognized. In FLAC files `TagEditor` keeps ID3v2 tags, leading or appended, without reading
them, while the FLAC demuxer reports their fields. The offset of an ID3v2.4 `SEEK` frame is a minimum, as the
specification says: the next tag is searched for up to 1 MiB past it, and a tag found beyond the offset itself
counts only when its undefined header flags are clear and it holds at least one frame.

</details>

### Subtitles and captions

<details>
<summary><b>Subtitles</b></summary>

TTML documents decode to text cues; the images of IMSC image profile documents are not decoded, and
a paragraph of a document that defines regions but selects none is not shown, as TTML specifies. A cue's
placement is written to 3GPP timed text only where the region a `tbox` is measured in is known: the default
text box of the sample entry, or else the size of the track (`DataTrack.width()`), which the default sample
entry of `SubtitleEncoder.track` and its track without a size leave unknown. Automatic encoding detection covers the encodings listed under Subtitles; others need the charset
option. SAMI files are written in UTF-8, MicroDVD and LRC files without the styles a line only partly has.

</details>

<details>
<summary><b>Bitmap subtitles and captions</b></summary>

Bitmap subtitles are copied, never encoded, and MP4 carries VobSub only (no sample
entry exists for PGS or DVB subtitles). A DVB or PGS cue keeps its regions whole; the PGS cropping rectangle is
applied (ffmpeg 7.1 shows the whole object). The X/26 mosaic and DRCS characters of teletext are not modeled, and
the Arabic teletext sets follow the glyph tables of EN 300 706 alone (the ffmpeg builds at hand have no teletext
decoder to compare with). DVB character-coded objects (whose character table EN 300 743 leaves to a local
agreement) are not drawn, and the disparity and alternative CLUT segments of stereoscopic and HDR services are
ignored. CEA-708 text keeps its italic, underline, colors and window anchor; edge styles, fonts, opacities, window
borders, justification, scrolling and the delay commands are not modeled. Roll-up and paint-on CEA-608 cues start
at their first character, where ffmpeg's `cc_dec` starts them at the end of the previous cue.

</details>

<details>
<summary><b>MP4 TTML (<code>stpp</code>)</b></summary>

The `subs` box written for a sample with image resources delimits the PNG images after the
document; resources in another format are one sub-sample together. ffmpeg (7.1) cannot write fragmented TTML;
it reads Smalt's.

</details>

### Transcodes

<details>
<summary><b>Video processing in transcodes</b></summary>

`SubtitleFont` reads TrueType outlines only (no CFF), applies no hinting and
forms neither kerning pairs nor ligatures, so scripts that need shaping (Arabic, Indic) or right-to-left
ordering are not drawn correctly; the burn-in draws bold, italic and underline but not the colors, fonts and
positions of ASS styles or WebVTT regions. Inverse telecine follows the 2:3 cadence only (not 2:2:2:4 or
irregular cadences that edits leave), decimating one frame of every five counted from the first. Field-rate
deinterlacing doubles the rate when the first frame is interlaced. VP8 and Motion-JPEG code 8 bits only, so a
PQ or HLG transcode into them is coded at 8 bits; Motion JPEG 2000 codes the depth but its samples are declared
sYCC, so only the output track's color tells a PQ or HLG stream apart.

</details>

<details>
<summary><b>Several inputs and outputs in transcodes</b></summary>

A concatenation cannot seek, so a trimmed start decodes and drops the
media before it; a copied stream of a concatenation keeps each file's encoder priming (an AAC file's first 1024
samples play at the join) and needs the same codec configuration in every file; the chapters of a concatenation
are placed by the durations the files declare. The outputs of one job share their range, duration limit, speed,
silence trim, seeking, limits and data references; their video encodes of one track share its decoder, but each
encoded audio stream decodes its track itself. Shortening pauses applies to outputs of audio alone, and a
fade-out without a start needs an output of known duration (a range, or an input that declares its duration).

</details>

<details>
<summary><b>Tags in transcodes</b></summary>

The tags of an input that is not local (`MediaSource.of(InputStream, long)`, `MediaSource.of(URI)`
over the network) are taken from its demuxer, which keeps one value per name, rather than read again from its tag
structure.

</details>

<details>
<summary><b>Sample aspect ratio in AV1, VP8 and VP9</b></summary>

The AV1, VP8 and VP9 bitstreams have no field for a sample aspect ratio, so a transcode to them that keeps
non-square samples records the ratio in the container only, and a later copy into IVF or MPEG-TS, which have no
field for it either, loses it; H.264, HEVC, MPEG-4 Part 2, H.263, Theora, FFV1 and DV streams carry it in band
(`VideoEncoderOptions.sampleAspectRatio`). AVI stores the number of fields per frame but not their order.

</details>

### Audio

<details>
<summary><b>DSD</b></summary>

DSD decodes only to PCM (or, through `DstDecoder.decodeDsd`, DST to its DSD), with one fixed filter design;
there is no DSD output, no DSD or DST encoder and no DoP writer, and DSF, DSDIFF and Monkey's Audio files are not
written. DSD over PCM is recognized only in 24 and 32-bit integer PCM, after 32 consecutive frames carrying the
DoP markers in every channel (as DoP 1.1 recommends), so a DoP section shorter than that is output as PCM.

</details>

<details>
<summary><b>Ambisonics</b></summary>

The ambisonic layout of a track survives only where a container can say it: Opus mapping families 2
and 3 in every Opus container (the identification header), `SA3D` in MP4 and MOV for every component of an order in
SN3D (N3D, Furse-Malham, mixed-order and partial sets are written without it), and the B-format sub-format in WAV for
the Furse-Malham layouts only (also read from the `WAVEFORMATEXTENSIBLE` of AVI and Matroska `A_MS/ACM` tracks, and
written for the Matroska 64-bit PCM that uses one). A codec that cannot code an ambisonic input layout, such as Opus
for Furse-Malham or N3D input, gets the sound field converted to ACN order with SN3D before any stereo fallback. The
QuickTime and CAF `chan` layouts carry every ambisonic layout through Core Audio's higher-order ambisonics and B-format
tags or, for the others, its HOA and B-format channel labels (a Furse-Malham component above first order has no label).
BW64 files read the HOA layout their ADM metadata (`chna`, `axml`, the BS.2094 common definitions) declares for PCM,
sound fields alone or with a front left and right pair; ADM is not written, and object-based programmes are read as
channels. ACN layouts in WAV without ADM, other codecs than Opus in Ogg and Matroska, and every other container carry
the channels without their meaning. A declared layout applies to AAC, ALAC and Vorbis in the order their decoders
output (the WAVE order their encoders take, as ffmpeg codes and Google's spatial media tools tag them), or in coded
order for an AAC program configuration with channels of no position; Apple's encoders replace an ambisonic layout
with a speaker layout for AAC and ALAC, so no Core Audio file carries one. Opus mapping family 3
in MP4 follows the convention Chromium reads, the RFC 8486 demixing matrix little-endian after the stream counts
of `dOps`: Opus in ISOBMFF 1.0.0 has no field for it. `ChannelMixer` renders a sound field to loudspeakers from its
first-order components only and has no binaural (HRTF) rendering.

</details>

<details>
<summary><b>Audio packet loss concealment (<code>AudioDecoder.conceal</code>)</b></summary>

Audio packet loss concealment (`AudioDecoder.conceal`) extrapolates from the signal decoded before the loss only:
no decoder interpolates toward the packet after the loss, which would delay every output by a packet. After a
loss, state the lost packets would have refreshed converges as the stream refreshes it: the AAC Main predictors
and the long term prediction history, spectral band replication envelopes coded as time deltas of the lost ones,
the CELP memories of AMR, the MPEG Surround parameters (the last ones stay in use until an independent spatial
frame), and the dither and noise substitution generators of AC-3 and AAC, which the lost
packets no longer advance, so even a Low Complexity stream with noise substitution differs from a loss-free
decode in those bands. The Vorbis, MP2, AC-3 and E-AC-3 encoders cannot change their bitrate while running
(`setTargetBitrate` raises `MediaException.Unsupported`). A USAC decoder conceals the access units after a lost
or corrupt one until the next independent one (`usacIndependencyFlag`), since they depend on the arithmetic coder
context, the delta-coded parameters and the eSBR and MPEG Surround states the lost data carried, so a stream
with independent access units far apart stays concealed, and silent from the ninth unit, until one arrives.

</details>

<details>
<summary><b>ATRAC1</b></summary>

ATRAC1 (Matroska `A_ATRAC/AT1`) is read and written by the containers but not decoded: the only public description
of it, the 1992 AES paper by Tsutsui et al., gives the structure (QMF split, block switching, block floating
units) but none of the bitstream (the sound unit layout, the unit boundaries, the scale factor and word length
tables, the QMF taps, the windows), the MiniDisc specification is licensed by Sony and the only decoders are LGPL,
so `AudioDecoders.create` raises `MediaException.Unsupported`. ATRAC3 and ATRAC3plus have no description at all.
ATRAC9 has no encoder in Smalt; its band extension fills bands with noise from a generator seeded once per decoder,
so after a seek those bands differ from a decode from the start. ATRAC9 has no MP4 mapping, and a frame coding
parameters for which LibAtrac9 has no defined result (gradient units past 31, fine precisions past 15, a band
extension outside 13 to 20 quantization units) raises `MediaException.Malformed`.

</details>

<details>
<summary><b>Exact audio outputs (<code>SampleFormat.INTEGER</code> and <code>DOUBLE</code>)</b></summary>

The exact audio outputs (`SampleFormat.INTEGER` and `DOUBLE`) keep the codec's rate and layout: a part of a stream
that changes either mid-way is converted through floats, as the float output converts it, and quantized to the
output's sample size. 64-bit integer PCM has no integer output, and its doubles round it to 53 bits. An ALAC
channel pair of 32-bit samples that keeps every byte (33-bit side information, which Apple's encoder never writes)
raises `MediaException.Unsupported`. The `STREAMINFO` MD5 check needs a decode from the stream's first frame, so a
read that seeks first is not checked.

</details>

<details>
<summary><b>Sample processing</b></summary>

The Lt/Rt downmix is the ATSC A/52 matrix, which sums the surrounds without the 90 degree
phase shift some matrix surround encoders apply. The variable-ratio resampler keeps the anti-aliasing filter of
the nominal rates, so a ratio far from the nominal one (it accepts a factor of two) aliases more than a resampler
built for the new rates; clock drift stays far below that. The noise-shaped PCM dither uses a filter designed for
44.1 kHz at every rate. The true-peak limiter aims 0.02 dB under its ceiling, and a lossy encoder that follows it
can add overshoot of its own. Single-pass loudness normalization levels changes slower than a few seconds and
raises pauses by a few decibels before it holds its gain; the two-pass normalization keeps the dynamics.

</details>

<details>
<summary><b><code>AudioStreamDecoder</code> gap filling</b></summary>

`AudioStreamDecoder` queues the silence that fills a gap between audio packets all at once, so a long gap
holds its samples in memory, and one whose silence needs more than `MediaLimits.maxBytes()` fails with
`MediaException.LimitExceeded`.

</details>

### Video

<details>
<summary><b>Transparent video</b></summary>

The alpha stream of VP8 and VP9 is coded at 8 bits in 4:2:0, so deeper alpha planes are
rounded to 8 bits (a decode rescales them back to the frame's depth), and it has no hidden frames, so the hidden
alternate references of VP8's good-quality usage carry no alpha frame. Only Matroska and WebM store the alpha
stream (`OutputContainer.storesAlpha`): MP4 has no specified way to carry it, so a transcode into MP4 flattens the
transparency and a remux into MP4 drops it. A transcode decides from the first picture: it keeps transparency
when that picture has an alpha plane (an animated image decoded with alpha, or video that carries alpha), and
otherwise flattens every picture.

</details>

<details>
<summary><b>ISO/IEC 23001-17 uncompressed video (<code>uncv</code>)</b></summary>

ISO/IEC 23001-17 uncompressed video (`uncv`) decodes every layout of uncompressed HEIF items, with the
limits the HEIF entry of Images, color and HDR lists for `unci` items (samples of at most 16 bits, CMYK converted without color
management, filter arrays as their mosaic). `UncompressedVideoEncoder` writes unsigned components only,
without blocks, pixel padding, palettes or compressed units, and neither row nor multi-Y interleaving of 4:2:0.

</details>

### Images, color and HDR

<details>
<summary><b>HEIF files (HEIC and AVIF): alpha, items and derived images</b></summary>

HEIF alpha images of another size than their color image are resampled with the triangle filter, which
HEIF leaves to the reader, so their alpha can differ by a few levels from that of readers that resample
otherwise. HEIC JPEG and JPEG 2000 items need `smalt-image` at run time and raise
`MediaException.Unsupported` without it; JPEG 2000 items with two or more than four color channels are
unsupported, samples wider than 31 bits keep their 31 most significant bits, four color channels are inks only
when a `colr` box declares CMYK or YCCK, a CMYK ICC profile describes them or a channel definition names four
colors, and three unsubsampled color channels without a component transform, an sRGB or sYCC color
specification or an `nclx` property are taken as RGB. Uncompressed (`unci`) items decode
to at most 16 bits per sample: integer components above 16 bits are rescaled to 16 bits, signed ones offset by
half their range, floating-point components map 0 to 1 onto the 16-bit range (values outside it clamped, NaN
becoming 0; binary128 and binary256 values first rounded to a double), complex components give their magnitude;
CMYK components are converted to RGB without color management, and a filter array (`cpat`) decodes to its
mosaic as a linear gray image, without demosaicing or the pattern's gains. A value stored in more bytes than
its depth (`component_align_size`) is taken from the least significant bits; `cpal` palette values are read
from the smallest of 1, 2, 4, 8, 16 or 32 bytes that holds their depth. A grid whose inputs are derived
images ignores the alpha its coded inputs carry themselves. A HEIF image decodes with the artifact of its
coding, a derived image with that of its first input: one whose inputs mix codings needs both `smalt-mpeg` and `smalt-video`, and raises
`MediaException.Unsupported` naming the missing one otherwise; an alpha auxiliary image coded in the other
family is attached only when that artifact is present (the image decodes opaque otherwise), and the alpha
images of the tiles of an AV1 grid only when every tile has one of the same family. The HEIF page readers list image items only: the tracks an entity group names
(an image sequence alternative to an item, a burst track) are not pages and drop out of `PageReader.groups`.
An AVIF sample transform (`sato`) is shown only as the primary item or as the alternative an `altr`
group prefers to it, as libavif shows it; one whose inputs are premultiplied, overlays, or partly with alpha
raises `MediaException.Unsupported` (the primary item is shown instead when the group lists it), and an `nclx`
property without its full range flag clamps its output to the limited range, as AVIF 1.2 says, where libavif
clamps to the full range. The GIF, APNG, WebP and JPEG XL animation readers return 8-bit
frames whatever `highBitDepth` asks. The PNG decoder looks for an XMP chunk after the image data only in a
datastream without animation frames. `DecodedImage.xmp()` stays empty for ICO, ANI and camera raw files, and
for HEIC and AVIF image sequences. The GIF decoder returns the ICC profile and the XMP packet only of
extensions before the first image (`ImageMetadata` finds them anywhere), and GIF holds RGB profiles only.

</details>

<details>
<summary><b>Constant-luminance, ICtCp and IPT-C2 matrices</b></summary>

The constant-luminance, ICtCp and IPT-C2 matrices mix linear light, so with PQ or HLG a channel much darker
than the other channels of its pixel (a PQ signal more than about 0.1 lower) does not survive quantization to
10-bit Y'CbCr; their per-pixel conversion also costs several times the fixed-point matrices. The DNxHD frame
header names only the BT.709 and BT.2020 color volumes, so the encoder signals every other matrix as an
out-of-band color volume (3), left to the container. The default 8-bit mode of the video decoders other than
AV1 rounds YCgCo-Re and YCgCo-Ro samples deeper than 8 bits like any others, which keeps 6 or 7 bits of R'G'B'
(`highBitDepth` keeps them exact; AV1 video and AVIF stills and sequences decode to 8-bit RGBA instead). The H.264 (4:2:0 only),
ProRes and Y'CbCr DNxHD encoders reject the identity matrix, which they cannot code or signal.

</details>

<details>
<summary><b>Gain maps</b></summary>

The backward direction flag of ISO 21496-1 is read as the drafts that introduced it define it (a
negative weight, dimming an HDR base toward its alternate rendition), which is what a base headroom above the
alternate one says; with a base headroom below the alternate one, which no writer is known to store with the
flag and whose arithmetic neither a published text nor a sample defines, it is rejected as unsupported. A gain
map of another size than its image is resampled
bilinearly at the pixel centers in the log2 domain, where libultrahdr interpolates by inverse distance
weighting and libavif resamples the coded samples, so such files render within a few 10-bit code values of
those libraries rather than within one. libavif converts the base to R'G'B' of its own depth before it applies
the gain map, where Smalt keeps the precision of the decoded samples: from the same R'G'B' Smalt renders within
one 10-bit PQ code value of `avifgainmaputil`, and from the Y'CbCr samples of an 8-bit base within one on
average and 8 at most; libultrahdr's renderings match within one. A display at or
below the base headroom (at or above it for an HDR base) shows the base rendition as it is, without the offsets,
as libavif does when it skips
the gain map; libavif and libultrahdr otherwise apply the equation with a zero weight, which differs only for
offsets that differ. HLG rendering removes the BT.2100 system gamma on the luminance, where libultrahdr
and libavif apply it per channel. Apple's gain maps are converted for their full headroom: Apple does not say
how a display of less headroom applies them, so the ISO 21496-1 weighting of the log2 gains applies. The
Ultra HDR writer leaves out the XMP form when the gain map applies in the alternate color space, which that
form cannot say; readers that only read XMP then see no gain map.

</details>

<details>
<summary><b>Channel pictures</b></summary>

Named-ink separations convert to RGB only through an ICC profile with one channel per ink
(`nCLR`) or whose colorants name every ink, so a TIFF separation of named inks without one does not decode to
RGB; a separation whose ink names do not all match the profile's colorants is read in channel order, as Little
CMS reads it. Only resampling, cropping, orientation and color transforms keep floating-point samples. JPEG
2000 keeps only CMYK images (and their alpha) as channel pictures, and its default CMYK output is 8-bit and
truncates as `opj_decompress` does. TIFF extra samples carry no meaning beyond alpha, so depth or spot channels
are written and read back as unspecified extras, and 64-bit floating-point TIFF samples are narrowed to 32 bits.

</details>

<details>
<summary><b>HDR metadata</b></summary>

MP4 has no box for the chroma siting, and Matroska cannot express a siting on the bottom luma
row (only its horizontal position is written). An MP4 `mdcv` or `clli` box without a `colr` box is read
into a color of unspecified matrix, primaries and transfer, so a remux writes it back with an unspecified
`colr`. HDR10+ messages (`PacketSideData.hdr10Plus`) are written only to Matroska and WebM; the MP4 muxers
leave them out (H.265 and AV1 streams carry HDR10+ in band, in SEI and metadata OBUs; VP9 HDR10+ needs
WebM or Matroska). The Matroska
track entry declares the HDR10+ `BlockAdditionMapping` only for a PQ track or one whose first message is
queued when the header is written; the messages of any other track (live output of a track without PQ color)
are written without it, which WebM readers of VP9 accept (WebM assigns `BlockAddID` 4 to ITU-T T.35).

</details>

<details>
<summary><b>Dolby Vision</b></summary>

The decoders show the base layer of a backward compatible stream, whose display management is not
applied, and reconstruct a stream without one (profile 5, 10.0) without display management too: the pictures keep
the mastering display's range for the application to tone map. The enhancement layer of profile 7 (in band, NAL
unit type 63, or a second track or PID) is not applied; a stream with no backward compatible base layer that
names one in its configuration or carries one in band is refused, as is one on a codec other than HEVC and AV1 or
with an alpha plane. The in-band check of an undeclared stream skips
multi-layer streams, since the RPU headers of profile 20 (MV-HEVC) read as profile 5, and pictures before the first
key frame from the first RPU on stay plain video. Polynomial pieces with `linear_interp_flag` are refused, as no
known stream uses them. Reconstruction has no vector kernels.
WebM files and SAMPLE-AES transport streams carry no Dolby Vision configuration, and HLS playlists name a
backward compatible stream by its base codec only (no `SUPPLEMENTAL-CODECS`).

</details>

<details>
<summary><b>HDR color management</b></summary>

HDR color management converts HLG through the BT.2100 reference display of 1000 cd/m2 (system gamma 1.2),
whatever the peak of the display the content was graded on, and transforms from or to a PQ or HLG profile
are computed in single-precision floating point at 16 bits, not sampled like Little CMS's. The ICC form of
a PQ or HLG profile carries no mastering display or content light level, so a profile read back from it
assumes a 1000 cd/m2 content peak; its tone curves show neutral colors as Smalt tone-maps them, but colors
only approximately, to readers that ignore the `cicp` tag. Little CMS 2.17 writes and reads a `cicp` tag of
16 bytes rather than the 12 of ICC.1:2022: Smalt reads both and writes the standard one, which Little CMS
2.17 does not see.

</details>

<details>
<summary><b>ICC chains</b></summary>

A named color profile can only start a chain, and its 8-bit transform reaches only the first 256
colors; a high-dynamic-range profile can only be converted to or from one other profile, and cannot take part in a
proofing transform. Cases that differ from Little CMS 2.17 on purpose: a device link read through its `D2B0`
fallback (no `D2Bn` of the intent) has its XYZ or L*a*b* ends scaled like any floating-point table; the colors of
a named color profile with an XYZ connection space are read as XYZ, where Little CMS reads them as L*a*b*; the
gamut check of a source with more channels than the proofing profile (CMYK proofed on RGB) spans every source
channel, where Little CMS reads only the leading ones; a black-preserving chain whose black curve, total area
coverage or colorimetric table cannot be computed converts with the ICC intent, where Little CMS fails or applies
an identity. Floating-point device samples are nominal `0..1` for ink spaces too (Little CMS reads CMY, CMYK
and five inks or more as percents, two to four generic inks as `0..1`). A transform whose lookup table would
hold more than 2^25 samples (nine inks and more at Little CMS's 7 grid points, or eight inks to more than five
outputs) evaluates the linked profiles on every pixel, as Little CMS does from ten inks; Little CMS still samples
nine-ink and eight-ink tables of up to 512 MiB, so those 8-bit and 16-bit results are more accurate than its own.
Likewise the gamut check of a proofing transform on nine inks or more checks each color through the profiles
instead of sampling a table, where Little CMS creates no transform from ten inks. Profiles Smalt writes are
version 4.3 matrix/TRC, `lutAtoBType` or `ncl2` profiles; it writes no
`lutBtoAType`, `multiProcessElement` or version 2 tables, and a device link from a transform is always sampled
into a lookup table, never kept as curves and matrices.

</details>

### Input and output

<details>
<summary><b>Reading over HTTP (<code>MediaSource.of(URI)</code>)</b></summary>

Over HTTP (`MediaSource.of(URI)`), a demuxer that jumps between distant parts of a file, such as fragmented MP4
read fragment by fragment or Matroska with its `Tags` at the end, costs a request per jump. A server that ignores
range requests or sends no length gives a forward-only source, which cannot seek back past what was released and
is not resumed when its response breaks off.
`AudioReader.seek` on Matroska Vorbis or AAC, and on AAC Main, LTP, LD and ELD with SBR and MPEG-4 CELP in any
container, restarts from the start of the track (exact, but slower), and AAC seeks are bit-identical only
without perceptual noise substitution.

</details>

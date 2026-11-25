# smalt-core

> The media model, the codec registries, every container, image and audio processing, loudness, subtitles, PCM, tag editing and XMP.

[← Back to Smalt](../README.md) · [Javadoc](https://javadoc.io/doc/com.github.auties00/smalt-core)

[Examples](#examples) · [Formats](#formats) · [Known limits](#known-limits) · [API map](#api-map)

## At a glance

- **You rarely add it yourself.** Every other Smalt artifact depends on core; add core alone only for probing, tags, remuxing or PCM.
- **Every container lives here.** Demuxing, muxing, remuxing, probing and tag editing need no codec artifact.
- **Codecs plug in.** Add a codec artifact and the registries (`Images`, `AudioDecoders`, ...) find it. Core itself codes only PCM, DSD (decode) and raw video.
- **A missing codec throws `MediaException.Unsupported`.** Its `artifact()` names the artifact to add, when Smalt has one.
- **Also inside:** [transcoding](#api-map), [HLS and DASH](#streaming), [subtitles](#subtitles-and-captions), [tags](#tag-editing), subtitle burn-in, thumbnails, and picture, color and audio processing.
- **No dependencies and no JVM flag.** `--add-modules jdk.incubator.vector` turns on the vectorized (SIMD) kernels; see [JVM flags](../README.md#jvm-flags).

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-core</artifactId>
</dependency>
```

Import the BOM first so the version is managed for you: see [Install](../README.md#install). On the module path, add `requires com.github.auties00.smalt;`.

## Examples

### Probe a file

`Media.probe` reads the headers and describes the container and each track.

```java
var info = Media.probe(Path.of("talk.mp4"));
System.out.println(info.containerName().orElse("?"));
info.durationMicros().ifPresent(us -> System.out.println(us / 1e6 + " s"));
info.video().ifPresent(video -> System.out.println(
        video.codec() + " " + video.displayWidth() + "x" + video.displayHeight()));
info.audio().ifPresent(audio -> System.out.println(
        audio.codec() + " " + audio.sampleRate() + " Hz, " + audio.channels() + " ch"));
```

### Edit tags

`TagEditor` rewrites the tags in place when they fit, or rewrites the file when they do not.

```java
var song = Path.of("song.mp3");
var cover = AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/jpeg", "", // "" = no description
        Files.readAllBytes(Path.of("cover.jpg")));
var tags = TagEditor.read(song).toBuilder()
        .set(StandardTag.TITLE, "New title")
        .set(StandardTag.ARTIST, "First artist", "Second artist")
        .pictures(List.of(cover))
        .build();
System.out.println(TagEditor.write(song, tags)); // UNCHANGED, IN_PLACE or REWRITTEN
```

### Remux to MP4

`Mp4Remuxer` copies the streams, without decoding, into an MP4 with its index at the front (fast start), so playback starts before the download ends.

```java
try (var input = MediaSource.of(Path.of("clip.mkv"));
     var output = MediaSink.of(Path.of("clip.mp4"))) {
    var options = RemuxOptions.builder().allAudioTracks().allSubtitleTracks().build();
    var result = Mp4Remuxer.remux(input, output, options);
    System.out.println(result.inputFormat() + ": " + result.tracks().size() + " tracks");
}
```

### Trim and re-encode a clip

`Transcoder` keeps 0:10 to 0:40, scales to 480 lines and writes WebM. This input needs `smalt-mpeg`, `smalt-video` and `smalt-audio`.

```java
var options = TranscodeOptions.of(OutputContainer.WEBM).toBuilder()
        .range(10_000_000, 40_000_000) // microseconds
        .video(VideoTranscodeOptions.builder().maxSize(0, 480).build()) // 0 = any width
        .listener(progress -> progress.fraction().ifPresent(f -> System.out.printf("%.0f%%%n", f * 100)))
        .build();
Transcoder.transcode(Path.of("talk.mp4"), Path.of("talk.webm"), options);
```

### Read audio as PCM

`AudioReader` decodes the first audio track into blocks of float samples, one array per channel.

```java
try (var source = MediaSource.of(Path.of("song.wav"));
     var reader = AudioReader.open(source)) {
    System.out.println(reader.sampleRate() + " Hz, " + reader.layout().channels() + " ch");
    float peak = 0;
    for (var block = reader.read(); block != null; block = reader.read()) {
        for (int c = 0; c < block.channels(); c++) {
            for (int i = 0; i < block.frames(); i++) peak = Math.max(peak, Math.abs(block.plane(c)[i]));
        }
    }
    System.out.println("peak " + peak);
}
```

### Convert subtitles

`Subtitles.read` returns the cues of a file, and `Subtitles.write` writes them in another format.

```java
try (var source = MediaSource.of(Path.of("talk.srt"));
     var sink = MediaSink.of(Path.of("talk.vtt"))) {
    var cues = Subtitles.read(source);
    Subtitles.write(cues, SubtitleMarkup.SUBRIP, ContainerFormat.WEBVTT, sink);
}
```

### Measure loudness

`Loudness.measure` reports integrated loudness (LUFS), loudness range (LU) and true peak (dBTP), and `forPodcast()` gives the gain to reach the podcast target.

```java
var stats = Loudness.measure(Path.of("song.wav"));
System.out.printf("%.1f LUFS, %.1f LU, %.1f dBTP%n", stats.integrated(), stats.loudnessRange(), stats.truePeakDb());
System.out.printf("apply %.1f dB%n", LoudnessNormalization.forPodcast().gainDb(stats));
```

## Formats

### Codecs in core

| Codec | Decode | Encode | Notes |
|---|---|---|---|
| PCM, 8 to 64-bit integer | ✅ | ✅ | Little and big endian |
| PCM, 32 and 64-bit float | ✅ | ✅ | |
| G.711 A-law, mu-law | ✅ | ✅ | |
| DSD | ✅ | ❌ | Decoded to PCM |
| DSD over PCM (DoP) | ✅ | ❌ | Opt in with `AudioDecoderOptions.builder().dsdOverPcm(true)` |
| Raw video | ✅ | ✅ | 35 pixel formats |
| Raw video in MP4 (`uncv`) | ✅ | ✅ | ISO/IEC 23001-17, in MP4 and MOV |
| Image formats | ❌ | ❌ | Add [smalt-image](../image/README.md#formats), [smalt-raw](../raw/README.md#formats), [smalt-mpeg](../mpeg/README.md#formats) or [smalt-video](../video/README.md#formats) |

✅ supported · ❌ not supported · ➖ not applicable

Every other audio and video codec comes from [smalt-audio](../audio/README.md#formats), [smalt-video](../video/README.md#formats) or [smalt-mpeg](../mpeg/README.md#formats).

### Containers

| Format | Demux | Mux | Notes |
|---|---|---|---|
| MP4, MOV, M4A, 3GP, MJ2 | ✅ | ✅ | Fragmented MP4 and CMAF too; index at the front (fast start) by default |
| Matroska | ✅ | ✅ | Ordered editions, attachments, live mode |
| WebM | ✅ | ✅ | VP8, VP9, AV1, Opus, Vorbis, WebVTT |
| Ogg | ✅ | ✅ | Opus, Vorbis, FLAC, Speex, Theora; chained links |
| MPEG-TS, M2TS | ✅ | ✅ | 188, 192 and 204-byte packets; program selection; SCTE 35 |
| MPEG-PS, VOB | ✅ | ✅ | DVD navigation packs with `PsMuxerOptions.forDvd()` |
| MXF | ✅ | ✅ | Writes OP1a and OP-Atom (SMPTE operational patterns) |
| AVI | ✅ | ✅ | OpenDML, so no 2 GiB limit; the muxer needs a seekable sink |
| FLV | ✅ | ✅ | HEVC, AV1, VP9 via Enhanced RTMP; not a transcode output |
| ASF (WMA, WMV) | ✅ | ❌ | Tags are editable |
| DV | ✅ | ✅ | DV25, DVCPRO, DVCPRO50, DVCPRO HD |
| IVF | ✅ | ✅ | VP8, VP9, AV1 |
| YUV4MPEG2 (Y4M) | ✅ | ✅ | 8 to 16-bit planar frames |
| H.264, HEVC, VVC (Annex B) | ✅ | ✅ | Timestamps from the stream, else 25 fps |
| AV1 OBU, AV1 Annex B | ✅ | ✅ | One packet per temporal unit |
| MPEG-4 Part 2, H.263 | ✅ | ✅ | Elementary streams |

### Audio files

| Format | Demux | Mux | Notes |
|---|---|---|---|
| WAV, RF64 | ✅ | ✅ | Switches to RF64 past 4 GiB; AC-3/DTS passthrough (IEC 61937) |
| BW64, RIFX | ✅ | ❌ | Read only |
| Wave64 | ✅ | ✅ | 64-bit chunk sizes |
| AIFF, AIFF-C | ✅ | ✅ | PCM, A-law, mu-law, IMA, GSM, MACE |
| CAF | ✅ | ✅ | PCM, AAC, ALAC, Opus, MPEG audio, AC-3 |
| AU | ✅ | ✅ | PCM, float, G.711, G.722, G.726 |
| MP3, MP2, MP1 | ✅ | ✅ | Xing and LAME gapless info read and written |
| FLAC | ✅ | ✅ | STREAMINFO and seek table finalized on a seekable sink |
| ADTS, LOAS/LATM, ADIF | ✅ | ✅ | AAC framings |
| AMR | ✅ | ✅ | AMR-NB, AMR-WB, multichannel |
| AC-3, E-AC-3 | ✅ | ✅ | Dependent substreams kept together |
| DTS, DTS-HD | ✅ | ❌ | See [Known limits](#known-limits) |
| WavPack | ✅ | ✅ | Correction files read, not written |
| Monkey's Audio | ✅ | ❌ | |
| Musepack | ✅ | ❌ | SV7 and SV8 |
| DSF, DSDIFF | ✅ | ❌ | DSD; DSDIFF also DST frames |
| Raw PCM | ✅ | ✅ | You state the format; never detected |

### Subtitles and captions

| Format | Decode | Encode | Notes |
|---|---|---|---|
| SubRip | ✅ | ✅ | Works on a streaming sink |
| WebVTT | ✅ | ✅ | Works on a streaming sink; header blocks kept |
| SSA, ASS | ✅ | ✅ | Script header kept |
| TTML, DFXP, IMSC | ✅ | ✅ | Text only; several documents merge to IMSC 1.0.1 |
| SAMI | ✅ | ✅ | One track per language |
| LRC | ✅ | ✅ | Enhanced word times |
| MicroDVD | ✅ | ✅ | Frame-based timing |
| 3GPP timed text (`tx3g`) | ✅ | ✅ | In MP4 and MOV |
| PGS (`.sup`) | ✅ | ❌ | Decoded to bitmaps; kept as-is in remux and transcode |
| DVB subtitles | ✅ | ❌ | Kept as-is in remux and transcode |
| VobSub (`.idx` + `.sub`) | ✅ | ❌ | Kept as-is; `VobSubMuxer` writes up to 32 tracks |
| DVB teletext | ✅ | ❌ | Level 1.5 text pages |
| CEA-608, CEA-708 | ✅ | ❌ | From video SEI, user data or QuickTime tracks |

### Streaming

| Format | Read | Write | Notes |
|---|---|---|---|
| HLS, MPEG-TS segments | ✅ | ✅ | `PackagingOptions.hls()` |
| HLS, CMAF segments | ✅ | ✅ | `PackagingOptions.hlsCmaf()` |
| DASH, CMAF segments | ✅ | ✅ | `dash()`, `dashOnDemand()`, or `cmaf()` for both |
| AES-128, SAMPLE-AES, SAMPLE-AES-CTR | ✅ | ✅ | Clear keys only |
| CENC (`cenc`, `cbc1`, `cens`, `cbcs`) | ✅ | ✅ | Writes in fragmented MP4 |
| PIFF, WebM AES-CTR | ✅ | ❌ | Decrypted with `DecryptionKeys` |
| RTP payloads | ✅ | ✅ | H.264, VP8, VP9, AV1, Opus, AMR, AAC |

CMAF segments are fragmented MP4 that HLS and DASH share. Read means `HlsDemuxer`, `DashDemuxer` or `RtpDepacketizer`, and for encrypted files `Mp4Demuxer` or `MatroskaDemuxer` with `DemuxerOptions.decryptionKeys`. Write means `Packager`, `FragmentedMp4Muxer` or `RtpPacketizer`.

### Tag editing

Every container below is read and written.

| Container | What you can edit |
|---|---|
| MP3, ADTS, AC-3, AMR | ID3v2.3 and 2.4, APEv2, ID3v1, chapters |
| FLAC | Vorbis comments, pictures, chapters |
| Ogg Vorbis, Opus, FLAC | Comment header, pictures, chapters |
| MP4, M4A, MOV | iTunes atoms, chapters, timecode, XMP |
| Matroska, WebM | Tags, chapters, attachments |
| WAV, RF64, BW64, RIFX | INFO, `bext`, iXML, XMP, cue chapters |
| AIFF | Text chunks, ID3, XMP, markers |
| AVI | INFO and track names |
| ASF (WMA, WMV) | Pictures and markers |
| MPEG-TS | Track tags only, edited in place |

`TagEditor.stripLocation` removes GPS and place data in one call. `XmpPacket` parses, edits and writes XMP.

<details>
<summary><b>Codecs each muxer writes</b></summary>

Video:

| Codec | MP4 | MOV | MKV | WebM | TS | PS | MXF | FLV | AVI | Ogg |
|---|---|---|---|---|---|---|---|---|---|---|
| H.264 | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| HEVC | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ |
| VVC | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| AV1 | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ |
| VP8, VP9 | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ |
| MPEG-1/2 video | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ | ❌ | ✅ | ❌ |
| MPEG-4 Part 2 | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ | ❌ | ✅ | ❌ |
| H.263 | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| MJPEG, JPEG 2000 | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| JPEG XS | ✅ | ✅ | ✅ | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ | ❌ |
| ProRes, DNxHD | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| DV | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| PNG, CineForm, HAP | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| FFV1 | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| Theora | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Raw video (`uncv`) | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

MOV and Matroska also take TIFF, GIF and QTRLE. FLV and AVI also take Sorenson Spark.

Audio:

| Codec | MP4 | MOV | MKV | WebM | TS | PS | MXF | FLV | AVI | Ogg |
|---|---|---|---|---|---|---|---|---|---|---|
| AAC, MP3 | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ |
| MP2 | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ✅ | ❌ |
| Opus | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ❌ | ✅ |
| Vorbis | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| FLAC | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ |
| ALAC | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| AC-3 | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ |
| E-AC-3 | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ |
| DTS | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| AMR | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Speex | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ |
| PCM | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| G.711 A-law, mu-law | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ |
| IMA ADPCM, GSM | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |

- PCM depths differ: TS and PS take 16 and 24-bit, FLV 8 and 16-bit, MXF 8 to 32-bit, MP4 16-bit and up.
- MP4 and MOV also take MPEG-4 CELP, TwinVQ and HVXC. MOV also takes MACE.
- Matroska and AVI also take Microsoft ADPCM, G.722 and G.726. Matroska also takes WavPack and ATRAC.

Subtitles and captions:

| Codec | MP4 | MOV | MKV | WebM | TS | PS |
|---|---|---|---|---|---|---|
| WebVTT | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| SubRip, ASS | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| 3GPP timed text, TTML | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| CEA-608, CEA-708 | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| VobSub | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |
| PGS, DVB subtitles | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ |
| DVB teletext | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |

MXF, FLV, AVI and Ogg take no subtitles. M4A takes MP4's compressed audio codecs only. 3GP takes H.263, MPEG-4 Part 2, H.264, AMR, AAC and 3GPP timed text.

</details>

<details>
<summary><b>Transcode outputs and their default codecs</b></summary>

`OutputContainer` picks the first codec in each list that has an encoder installed.

| Output | Default video | Default audio |
|---|---|---|
| `MP4`, `FRAGMENTED_MP4` | H.264, VP9, AV1, HEVC | AAC, Opus |
| `MOV` | ProRes, H.264, HEVC | AAC |
| `M4A` | ➖ | AAC, Opus |
| `MATROSKA` | VP9, H.264, VP8, AV1, HEVC | Opus, AAC, FLAC, PCM |
| `WEBM` | VP9, VP8, AV1 | Opus, Vorbis |
| `OGG`, `OGG_FLAC` | ➖ | Opus, Vorbis; FLAC |
| `WAV`, `W64`, `AIFF`, `AU` | ➖ | 16-bit PCM |
| `CAF` | ➖ | ALAC, 16-bit PCM |
| `ADTS`, `LOAS`, `ADIF` | ➖ | AAC |
| `MP3`, `FLAC`, `AMR`, `AC3`, `EAC3` | ➖ | The named codec |
| `IVF` | VP9, VP8, AV1 | ➖ |
| `H264`, `HEVC`, `VVC`, `AV1` | The named codec | ➖ |
| `Y4M` | 8-bit 4:2:0 raw video | ➖ |
| `DV` | DV | 16-bit PCM at 48 kHz |
| `MPEG_TS` | H.264, HEVC | AAC, MP3, Opus |
| `MPEG_PS` | H.264, HEVC | MP2, AC-3, AAC |
| `MXF` | DNxHD, ProRes, H.264 | 24-bit PCM |
| `AVI` | H.264 | AAC, MP3, PCM |
| `SRT`, `WEBVTT`, `ASS`, `SSA`, `TTML`, `SAMI`, `LRC`, `MICRODVD` | ➖ | ➖ |
| `SUP` | ➖ | ➖ |

The subtitle outputs write text cues; `SUP` copies PGS bitmaps.

</details>

## Known limits

### Codecs and artifacts

- Core has no image codec: `Images.decode` throws `MediaException.Unsupported` until you add an image artifact.
- With core alone, writers and transcodes encode only PCM, raw video and text subtitles.
- Without `smalt-mpeg` (the AAC decoder), demuxers cannot detect implicit HE-AAC (MP4, Matroska, ADTS, LOAS, CAF, FLV, MPEG-TS), so they report the AAC core rate.
- DSD decodes to PCM only. DST needs `smalt-audio`.

### Containers

- Raw DTS is read-only: `Containers.isMuxable` reports `true`, but adding a DTS track to the raw muxer throws.
- Seeking in MPEG-TS and MPEG-PS needs a source of known size.
- Write HLS and DASH through `Packager` and VobSub through `VobSubMuxer`: `Containers.openMuxer` rejects them.
- `Mp4Remuxer` reads MP4, Matroska, MPEG-TS, MPEG-PS, MXF, AVI, FLV and Ogg, and fails on codecs MP4 cannot carry.

<details>
<summary><b>More container limits</b></summary>

- An MP4 without fast start, AVI, and MXF OP-Atom or clip wrapping need a seekable sink.
- CAF with variable-size packets (AAC, ALAC, Opus, MPEG audio, AC-3) needs a seekable sink.
- Matroska timestamps keep the file's timestamp scale (1 ms by default).
- WebM drops the Dolby Vision mapping and keeps the base layer.
- MXF writes one edit rate for all picture tracks, and no pictures, chapters or attachments.
- AVI holds at most 99 tracks.
- FLV: the track list comes from the start of the file, and encrypted tags are rejected.
- The FLV muxer stores no pictures, chapters or track tags.
- ASF files with DRM are rejected; WMA and WMV 7/8 tracks read as unknown codecs.
- MPEG-TS and MPEG-PS drop scrambled packets: there is no descrambling.
- A TS read once from a stream fails when a later program table (PMT) adds a stream.
- An Ogg file read once from a stream seeks forward only.
- A raw AAC stream needs ADTS, and raw VP8 or VP9 needs IVF.
- The first seek in a raw H.264, HEVC, VVC, AV1 or MPEG-4 Part 2 stream reads the whole stream.
- G.722, G.726 and GSM tracks in WAV must be mono.
- Subtitle files hold one track each, except SAMI (one per language) and VobSub (up to 32).

</details>

### Tags

- MPEG-TS holds track tags only, edited in place: an edit that outgrows the PMT packets is rejected.
- AVI holds no pictures or chapters.
- WebM keeps chapters of the default edition only, and takes no new attachments or pictures.
- An in-place edit can leave a file inconsistent if the process dies. `TagWriteOptions` with `inPlace(false)` always rewrites to a temporary file.
- Editing one file from several threads at once is not safe.

### Transcoding

- Speed must be between 0.1 and 10, and any speed other than 1 re-encodes audio and video.
- Copied streams start at a key frame (video) or packet (audio). The cut is exact only in MP4, MOV and M4A (edit lists), or for PCM.
- Shortening pauses with `SilenceTrim` needs an audio-only output.
- `Fade.out(duration)` needs a known output end: a range or a declared input duration.
- Data tracks other than subtitles and captions (timed metadata, for example) are not written.

### Streaming

- `Packager` never re-encodes. It cuts video segments only at key frames.
- With a declared target duration, or in an event or live playlist, an HLS segment that runs longer fails with `MediaException.Unsupported`.
- DASH needs CMAF segments: MPEG-TS segments are HLS only.
- Encryption uses clear keys. DRM key servers, key formats and `pssh` boxes are up to you.
- Reading a remote presentation needs `DataReferenceResolver.http`. Live playlists are read only as far as they are listed.
- RTP covers payload formats only: no sockets, RTCP, SRTP, jitter buffer or FEC.

<details>
<summary><b>More streaming limits</b></summary>

- `AES_128` is HLS only: not with DASH, `segmentIndex` or trick play.
- `SAMPLE_AES_CTR` is CMAF only. SAMPLE-AES in MPEG-TS carries H.264, AAC, AC-3 and E-AC-3 only.
- Subtitle renditions take text codecs only, and are never encrypted.
- Next to audio renditions, a variant must have video, and it carries video only.
- Dolby Vision `SUPPLEMENTAL-CODECS` is written in HLS only; DASH names the base codec.
- HLS: SAMPLE-AES packed audio and encrypted WebVTT segments are rejected.
- DASH: `$SubNumber$` is not supported, and adaptation sets with an `EssentialProperty` are skipped.
- The VP9 packetizer writes non-flexible mode with one spatial layer.
- The H.264 mode 2 and AAC packetizers never interleave.

</details>

### Subtitles

- Bitmap subtitles are never encoded: a transcode copies them, or leaves them out when the output cannot carry them.
- Captions are copied, or converted to text when the output cannot carry them.
- `SubtitleTranscodeOptions.closedCaptions` extracts the captions of H.264, HEVC, MPEG-1/2 and AV1 video as text.
- TTML is read as text: IMSC image profile images are not decoded.
- The renderer reads TrueType `glyf` fonts only, without hinting, kerning or ligatures. It draws bold, italic and underline.
- Teletext mosaics, CEA-608 text mode and XDS, and CEA-708 window styles are not shown.
- Charset detection covers common encodings only; set `charset` for the others.

### Audio

- `SampleFormat.INTEGER` and `DOUBLE` keep the codec's rate and layout: only `FLOAT` output is resampled and remixed.
- PCM dither works only through `new PcmEncoder(options)`. PCM from `AudioEncoders` and `AudioWriter` is undithered.
- `ChannelMixer` renders ambisonics from the first-order components only, with no binaural output.
- `AudioStreamDecoder` holds the silence of a gap in memory: a gap past `MediaLimits` fails with `MediaException.LimitExceeded`.

### Images, color and video

- A PQ or HLG profile converts only to or from one other profile: no longer chains, no proofing.
- HLG converts through the BT.2100 reference display of 1000 cd/m2, whatever display the content was graded on.
- Only Matroska and WebM keep the alpha of transparent video. A transcode to any other container flattens it.

### I/O and runtime

- A source read from an `InputStream` keeps 16 MiB in memory, then spills to an encrypted temporary file.
- With `StreamSourceOptions.memoryOnly()`, a read past the memory budget fails with `MediaException.LimitExceeded`.
- An HTTP server without range support gives a forward-only source.
- Image work uses a shared pool of helper threads; `-Dsmalt.threads=N` caps it, and `1` runs on the calling thread.
- On Windows the SIMD kernels are opt-in with `-Dsmalt.simd=true` ([JDK-8365196](https://bugs.openjdk.org/browse/JDK-8365196)).
- Kernels written for 256-bit vectors run scalar on narrower hardware: NEON, SVE-128 and x86 without AVX2.

Default `MediaLimits` for untrusted input:

| Limit | Default |
|---|---|
| Side | 16384 px |
| Pixels | 128 Mi |
| Frames | 1,000,000 |
| Channels | 64 |
| Buffer | 1 GiB |

## API map

| Task | Start with | Package (`com.github.auties00.` omitted) |
|---|---|---|
| Describe a file | `Media.probe` | `smalt` |
| Read and write bytes in memory, files, streams or HTTP | `MediaSource.of`, `MediaSink.of` | `smalt.io` |
| Read and write packets | `Containers.openDemuxer`, `Containers.openMuxer` | `smalt.container` |
| Remux to MP4 | `Mp4Remuxer.remux` | `smalt.container.mp4` |
| Convert, trim, join, speed up, fade | `Transcoder.transcode`, `Transcoder.concatFiles` | `smalt.transcode` |
| Edit tags and XMP | `TagEditor`, `MediaTags`, `XmpPacket` | `smalt.metadata` |
| Decode and encode images (needs an image artifact) | `Images.decode`, `Images.encode` | `smalt.image` |
| Resize, crop, filter, adjust, composite | `ImageOps`, `ImageFilters`, `ImageAdjustments`, `ImageCompositing` | `smalt.image.ops` |
| ICC color management and HDR tone mapping | `ColorProfile`, `ColorTransform` | `smalt.image.color` |
| HDR gain maps | `GainMaps.apply`, `GainMaps.compute` | `smalt.image.gainmap` |
| Read and write audio samples | `AudioReader`, `AudioWriter` | `smalt.audio` |
| Resample, mix, filter, stretch | `Resampler`, `ChannelMixer`, `Biquad`, `TimeStretcher` | `smalt.audio.process` |
| Loudness, ReplayGain, silence | `Loudness`, `LoudnessMeter`, `ReplayGain` | `smalt.audio.loudness` |
| Read and write video frames | `VideoReader`, `VideoWriter` | `smalt.video` |
| Thumbnails and waveforms | `Thumbnails`, `Waveform` | `smalt.preview` |
| Read, write, convert and render subtitles | `Subtitles`, `SubtitleReader`, `SubtitleRenderer` | `smalt.subtitle` |
| Package HLS and DASH | `Packager` | `smalt.container.packaging` |
| Carry media over RTP | `RtpPacketizer`, `RtpDepacketizer` | `smalt.rtp` |
| Bound untrusted input, inspect SIMD and threads | `MediaLimits`, `MediaRuntime` | `smalt` |

Images and every codec except PCM, raw video and DSD (decode only) need a codec artifact.

## Plug in a codec

Implement a provider from `smalt.spi` and declare it with `provides` in `module-info.java`, or in a `META-INF/services` entry; the registries find it through `ServiceLoader`.

| Provider | Registry it feeds |
|---|---|
| `ImageCodecProvider` | `Images` |
| `AudioCodecProvider` | `AudioDecoders`, `AudioEncoders` |
| `VideoCodecProvider` | `VideoDecoders`, `VideoEncoders` |
| `ContainerProvider` | `Containers`, `Media.probe`, `TagEditor` |

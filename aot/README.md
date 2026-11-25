# smalt-aot

> Training workloads for JDK ahead-of-time (AOT) caches, used only in the training run.

[← Back to Smalt](../README.md) · [Javadoc](https://javadoc.io/doc/com.github.auties00/smalt-aot)

## At a glance

- **Why:** a JDK AOT cache lets production start with Smalt's classes loaded, profiled and, on some JDKs, compiled.
- **What it adds:** the media work for your training run (`-XX:AOTCacheOutput`). No library can ship a cache: it matches one JDK build and class path.
- `SmaltTraining` runs Smalt's codecs on synthetic media, plus your workloads, until a cycle compiles no new top-tier method in the tracked packages.
- The tracked packages are Smalt's and the Vector API's by default. A run takes up to 15 minutes (`maxDuration`, `--max-seconds`).
- On a stock JDK with the Vector API, the cache speeds up class loading only; see [Known limits](#the-cache).
- Production starts with `-XX:AOTCache=app.aot`. No smalt-aot code runs there, but keep the jar: production must use the training class path or module path.
- Pulls in [smalt-core](../core/README.md), [image](../image/README.md), [audio](../audio/README.md), [video](../video/README.md), [mpeg](../mpeg/README.md), [capture](../capture/README.md) and [raw](../raw/README.md), but not smalt-desktop.

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-aot</artifactId>
</dependency>
```

The version comes from the BOM: see [Install](../README.md#install) for the BOM import and Gradle.

## Examples

### Start fast with an AOT cache

A class-path app needs no code changes: train with `SmaltTraining` as the main class, then start production with the cache.

```sh
JDK_AOT_VM_OPTIONS=-XX:+BackgroundCompilation \
java -Xbatch --add-modules jdk.incubator.vector -XX:AOTCacheOutput=app.aot -cp "app.jar:lib/*" \
     com.github.auties00.smalt.aot.SmaltTraining --then com.example.Main

java --add-modules jdk.incubator.vector -XX:AOTCache=app.aot -cp "app.jar:lib/*" com.example.Main
```

For the module path and what each flag does, see [Training commands](#training-commands).

### Train from your own main

Put this at the top of your `main` and set your own property (here `-Dapp.aot.training=true`) in the training run.

```java
if (Boolean.getBoolean("app.aot.training")) {
    var report = SmaltTraining.train();
    System.err.println(report.summary());
    return;
}
```

### Train only the real-time paths of two artifacts

`forRealtime()` keeps only the real-time workloads (low-delay codecs, voice processing, capture conversion), and `families` narrows the run further.

```java
var options = TrainingOptions.forRealtime().toBuilder()
        .families(Set.of(TrainingFamily.VIDEO, TrainingFamily.AUDIO))
        .maxDuration(Duration.ofMinutes(5))
        .build();
var report = SmaltTraining.train(options);
System.err.println(report.converged() + " after " + report.cycles() + " cycles");
```

### Add your own workload on synthetic media

Wrap your own call in `TrainingWorkload.of` and track your package, so the run continues until your code is compiled too.

```java
var sample = TrainingMedia.mp4(640, 360, 1.0);
var options = TrainingOptions.builder()
        .addWorkloads(TrainingWorkload.of("to-webm", () -> { try (var in = MediaSource.of(sample)) {
            Transcoder.transcode(in, MediaSink.memory(), TranscodeOptions.of(OutputContainer.WEBM));
        } }))
        .addTrackedPackages("com.example.app")
        .build();
SmaltTraining.train(options);
```

### Add a workload whose setup can fail

A deferred workload runs its setup once and is skipped if setup throws or returns `null`; `families(Set.of())` runs only your workloads.

```java
var options = TrainingOptions.builder()
        .families(Set.of())
        .addWorkloads(TrainingWorkload.deferred("decode-photo", () -> {
            var bytes = Files.readAllBytes(Path.of("photo.jpg"));
            return TrainingWorkload.of("decode-photo", () -> Images.decode(MediaSource.of(bytes)));
        }))
        .build();
var report = SmaltTraining.train(options);
report.skipped().forEach(System.err::println);
```

### Generate synthetic inputs

`TrainingMedia` builds deterministic frames, samples and files, the same on every run.

```java
var frames = TrainingMedia.video(1280, 720, 30);
var speech = TrainingMedia.speech(16_000, 3.0);
var music = TrainingMedia.music(48_000, 5.0);
var gif = TrainingMedia.animatedGif(320, 240, 12);
System.out.println(frames.size() + " frames, " + speech.length + " samples, " + music.length + " channels, " + gif.length + " bytes");
```

## Formats

What each `TrainingFamily` trains, and in which `TrainingMode`.

| Family      | Artifact      | File | Realtime | Trains                                            |
|-------------|---------------|------|----------|---------------------------------------------------|
| `IMAGE`     | smalt-image   | ✅   | ✅       | Still images, animations, VP8, metadata editing   |
| `AUDIO`     | smalt-audio   | ✅   | ✅       | Opus, Vorbis, FLAC, MP3 and more; voice processor |
| `VIDEO`     | smalt-video   | ✅   | ✅       | AV1, VP9, AVIF, ProRes, DNxHR and more            |
| `MPEG`      | smalt-mpeg    | ✅   | ✅       | H.264, HEVC, VVC, MPEG-4, AAC, AC-3 and more      |
| `CAPTURE`   | smalt-capture | ❌   | ✅       | Camera and screen pixel conversion to 4:2:0       |
| `RAW`       | smalt-raw     | ✅   | ❌       | Every camera raw format; DNG writing              |
| `TRANSCODE` | smalt-core    | ✅   | ❌       | Transcoder, containers, streaming, processing     |

✅ the family has workloads in that mode · ❌ it has none

| Mode       | What it covers                                                           |
|------------|--------------------------------------------------------------------------|
| `FILE`     | File encoders and decoders, still images, transcode, remux, probe, tags  |
| `REALTIME` | Low-delay coding one frame or packet at a time, capture, voice processing |

CAPTURE needs no camera or screen: it converts synthetic frames in every platform's pixel layout.

<details>
<summary>IMAGE: every format trained</summary>

| Group | Decode | Encode |
|-------|--------|--------|
| JPEG | JPEG (baseline, reduced, lossless, hierarchical, 12-bit), JPEG-LS, MJPEG, HAP | JPEG (modes, lossless transforms, gain map), JPEG-LS, MJPEG, HAP |
| Web | PNG, APNG, GIF, WebP (animated too), JPEG XL | PNG, APNG, GIF, WebP (animated too), JPEG XL |
| TIFF and JPEG 2000 | TIFF, JPEG 2000, HTJ2K, J2K Part 2, Motion JPEG 2000 | TIFF, JPEG 2000, HTJ2K, Motion JPEG 2000 |
| Simple formats | ICO, ANI, BMP, PNM, QOI, TGA, Radiance HDR, ICNS | ICO, ANI, BMP, PNM, QOI, TGA, Radiance HDR, ICNS |
| Decode only | PSD, DDS, OpenEXR, JPEG XR | ❌ |
| Video tracks | PNG, TIFF and GIF video | PNG and TIFF video |
| VP8 | Realtime, 2 threads | Realtime, file, quality; VP8 and VP8-alpha sessions |

Also trained: region decode (TIFF, JPEG 2000, JPEG, WebP) and metadata editing.

</details>

<details>
<summary>AUDIO: every format trained</summary>

| Group | Decode | Encode |
|-------|--------|--------|
| Opus | Surround, projection | Realtime 16/48 kHz, neural, speech, music, surround, projection |
| Vorbis | ✅ | ✅ |
| Lossless | FLAC (32-bit int), ALAC | FLAC (16 and 24-bit), ALAC (5.1, realtime) |
| MPEG audio | MP3, MP2 | MP3 (CBR, VBR, realtime), MP2, MP1 |
| ADPCM | IMA, ima4, MS | ✅ |
| Speech | G.722, G.726 (16k, 40k), GSM, MACE 3/6, Speex | G.722, G.726, GSM |
| Decode only | WavPack, Monkey's Audio, Musepack, DSD to PCM, ATRAC9, DST | ❌ |
| Packet loss | Speex, MP3, Vorbis, FLAC | ➖ |

Also trained: the voice processor in several layouts.

</details>

<details>
<summary>VIDEO: every format trained</summary>

| Group | Decode | Encode |
|-------|--------|--------|
| AV1 | Realtime, 2 threads, several shapes | Realtime 1 and 2 threads, quality, intra, tools; a 2-thread session |
| VP9 | Realtime, 2 threads | Realtime, quality; a VP9-alpha session |
| Others | AVIF, ProRes, DNxHR, DV, Theora, FFV1, QuickTime RLE | AVIF, ProRes, DNxHR, DV, Theora, FFV1, QuickTime RLE |
| CineForm | ✅ (Bayer too) | ❌ |

</details>

<details>
<summary>MPEG: every format trained</summary>

| Group | Decode | Encode |
|-------|--------|--------|
| H.264 | 1/2 threads, realtime, Extended, separate planes, film grain, SVC, 3D-AVC | Realtime, Main, High, Baseline, Extended, separate planes; sessions |
| HEVC | 1/2 threads, realtime, Dolby Vision profile 5, HEIC | Realtime, fast, balanced, Main10, 4:4:4 lossless, 4:2:2 Main10, still, HEIC |
| Other video | JPEG XS, MPEG-4 Part 2 (Studio too), H.263 (optional modes), FLV1 | JPEG XS, MPEG-4 ASP / Simple, H.263 (baseline, annexes); sessions |
| Decode only | MPEG-1/2, VC-1, VVC | ❌ |
| AAC | LC, HE, HEv2, Main, SSR, LTP, LD, ELD, BSAC, scalable, MPEG Surround, USAC | LC, 960 surround, HE, HEv2, LD, ELD, realtime |
| Dolby and DTS | AC-3, E-AC-3, DTS; AC-3 and AAC as IEC 61937 in WAV | AC-3 and E-AC-3 (file, realtime) |
| Speech | AMR-NB, AMR-WB, CELP, TwinVQ, HVXC | AMR-NB (DTX too), AMR-WB |

</details>

<details>
<summary>RAW and TRANSCODE: every format trained</summary>

- **RAW decode:** DNG (Bayer, linear, HDR, GPR/VC-5), NEF, CR2, CR3, CRW, PEF, ARW, ORF, RW2, ERF, MEF, SRW, DCR, MOS, IIQ, 3FR, MRW, X3F, RAF.
- **RAW encode:** DNG.
- **TRANSCODE containers:** MP4, Matroska, WebM, MPEG-TS, MPEG-PS, MXF, WAV, Y4M, AVI, FLV, DV, Ogg, IVF, ASF, uncompressed video in MP4.
- **TRANSCODE also:** PCM and elementary audio files, joined inputs, text / PGS / VobSub subtitles, HLS and DASH.
- **Image and audio processing:** conversion, resizing, effects, color management, resampling, loudness.
- **Video processing:** deinterlacing, telecine and inverse telecine, frame-rate blending, PQ with a watermark, tone mapping.

</details>

<details>
<summary>What ships inside the jar</summary>

- About 760 KB of bundled streams (77 files) train decoders that Smalt's own encoders cannot feed.
- Formats Smalt does not encode: VVC, VC-1, MPEG-1/2, SVC, 3D-AVC, USAC, DTS, OpenEXR, JPEG XR, Speex, WavPack, Monkey's Audio, Musepack, DSD, ATRAC9.
- Coding tools Smalt's encoders do not use: Theora (motion, cropped 4:2:2), H.263 optional modes, H.264 CAVLC separate planes.
- They come from reference encoders, Smalt's test-data scripts, and synthetic streams written by Smalt's tests.
- In-code writers generate camera raw of every vendor, PSD, DDS, DST, PGS, DVD subtitles, ASF, CineForm, MPEG-4 Studio, Dolby Vision RPUs and some AAC tools.
- Licences of bundled and ported code: `META-INF/THIRD-PARTY-NOTICES` in the jar.

</details>

## Known limits

### The cache

- A cache matches one JDK build, class or module path, `--add-modules` and `--enable-native-access`. Train with the options production uses.
- A missing or rejected cache is safe: production runs correctly, with the slower start of a fresh JVM.
- **Vector API on stock JDK 26 to 28:** the cache keeps parsed classes only (no linking, profiles or compiled code).
- Vector kernels then run uncompiled, and slowly, for the first seconds; `-Xlog:aot` prints `Using AOT-linked classes: false`.
- For compiled vector code from the first frame, use a JDK built from the [Leyden premain-vector-aot branch](https://github.com/Auties00/leyden/tree/premain-vector-aot).
- Training never forces the Vector API. On Windows, pass `-Dsmalt.simd=true` to training if production uses it.

### The run

- A run ends after a cycle (at least the 3rd) with no new top-tier (C2) compilation in a tracked package.
- Your code counts only if you add its package with `addTrackedPackages`. Otherwise the run can stop before your code is compiled.
- Without the Flight Recorder (JFR) the run cannot see compilations: it does at most 8 cycles and reports `converged` as false.
- The default limit is 15 minutes, counted after setup and checked between cycles; the cycle running at the limit still finishes.
- After each cycle and at the end, the run waits up to 20 s more for queued compilations.
- A workload that cannot be created, or whose round throws, is skipped and the run goes on.
- Duplicate workload names get a `#2`, `#3` suffix.

### Your workloads

- The cache profiles only the branches training takes, so feed your workloads representative inputs.
- Keep one `round()` between a few milliseconds and a few hundred milliseconds.
- `round()` is called from one thread.
- `build()` throws `IllegalArgumentException` for a non-positive duration, empty modes or a blank package name.
- `train` throws `IllegalArgumentException` when there are no families and no workloads.

## Training commands

### Class path: no code changes

Run `SmaltTraining` as the main class; `--then` starts your own `main` in the same JVM after training.

```sh
JDK_AOT_VM_OPTIONS=-XX:+BackgroundCompilation \
java -Xbatch --add-modules jdk.incubator.vector -XX:AOTCacheOutput=app.aot -cp "app.jar:lib/*" \
     com.github.auties00.smalt.aot.SmaltTraining --then com.example.Main

java --add-modules jdk.incubator.vector -XX:AOTCache=app.aot -cp "app.jar:lib/*" com.example.Main
```

On Windows, separate class path entries with `;` instead of `:`.

### Module path: call `train` from your app

The launcher must name your main module, so call `SmaltTraining.train(...)` from a branch of your `main` ([example](#train-from-your-own-main)). Your module `requires com.github.auties00.smalt.aot`.

```sh
JDK_AOT_VM_OPTIONS=-XX:+BackgroundCompilation \
java -Xbatch --add-modules jdk.incubator.vector -XX:AOTCacheOutput=app.aot -Dapp.aot.training=true \
     -p mods -m com.example.app

java --add-modules jdk.incubator.vector -XX:AOTCache=app.aot -p mods -m com.example.app
```

Add `-Xlog:aot` to the production command to see whether the JVM loaded the cache.

### Flags

| Flag | Where | Why |
|------|-------|-----|
| `-XX:AOTCacheOutput=app.aot` | Training | Records the cache |
| `-XX:AOTCache=app.aot` | Production | Uses the cache |
| `-Xbatch` | Training | Compiles in the foreground, so two trainings record the same methods |
| `JDK_AOT_VM_OPTIONS=-XX:+BackgroundCompilation` | Training | Undoes `-Xbatch` in the JVM that writes the cache |
| `--add-modules jdk.incubator.vector` | Both | Optional; use it in both or in neither |

### `SmaltTraining` options

| Option | Default | Effect |
|--------|---------|--------|
| `--max-seconds N` | `900` | Longest time spent running workloads |
| `--families A,B` | all | `TrainingFamily` names, case-insensitive |
| `--modes A,B` | all | `TrainingMode` names, case-insensitive |
| `--then <class> [args...]` | none | Runs that class's `main` after training, with every remaining argument |

- An unknown option, a missing value or a wrong name throws `IllegalArgumentException`.
- The run prints one `smalt-aot: <summary>` line to standard error.
- It also warns there when the JVM is not recording a cache or does not resolve `jdk.incubator.vector`.
- On Windows it warns when `-Dsmalt.simd` is unset ([why](#the-cache)).

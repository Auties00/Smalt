<div align="center">

# smalt-aot

**Training workloads for JDK ahead-of-time caches**

![Artifact](https://img.shields.io/badge/artifact-smalt--aot-2f6fe6?style=flat-square&labelColor=0b2a6f)
![Module](https://img.shields.io/badge/module-com.github.auties00.smalt.aot-2f6fe6?style=flat-square&labelColor=0b2a6f)

[Smalt](../README.md) | [core](../core/README.md) | [image](../image/README.md) | [audio](../audio/README.md) | [video](../video/README.md) | [mpeg](../mpeg/README.md) | [capture](../capture/README.md) | [raw](../raw/README.md) | [desktop](../desktop/README.md) | **aot** | [bom](../bom/README.md)

</div>

`smalt-aot` is used only in the training run that records a [JDK AOT cache](https://openjdk.org/jeps/514): it
exercises Smalt's codecs so that the cache holds them and production starts warm. It depends on
[`smalt-core`](../core/README.md), every codec artifact, [`smalt-capture`](../capture/README.md) and
[`smalt-raw`](../raw/README.md).

**Contents:** [Install](#install) | [Start fast with an AOT cache](#start-fast-with-an-aot-cache) |
[Known limits](#known-limits)

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-aot</artifactId>
</dependency>
```

The version comes from the [BOM](../bom/README.md); Gradle coordinates and JVM flags are in the
[root README](../README.md#install).

Production then starts with `-XX:AOTCache=app.aot`, the cache the training run recorded.

## Start fast with an AOT cache

A fresh JVM runs Smalt's codecs in the interpreter and at the first JIT tiers for its first seconds, and
Vector API code is many times slower than scalar code until the JIT compiler has compiled it. Output is
correct from the first frame, only slow: the first frames of a real-time stream, or the first image an
application decodes, take far longer than the steady state. A [JDK AOT cache](https://openjdk.org/jeps/514)
removes that start-up cost: record the cache once from a training run of your application, then start
production with it. `smalt-aot` is the media part of that training run: `SmaltTraining` exercises the codecs
of the families you select, on synthetic content, and stops once a cycle of its workloads makes the JIT
compiler compile nothing new.

There is one family per artifact (`IMAGE`, `AUDIO`, `VIDEO`, `MPEG`, `CAPTURE`, `RAW`, and `TRANSCODE` for the
transcoder, containers and processing of `smalt-core`), and every workload belongs to one mode: `FILE` for
files and streams coded ahead of time, `REALTIME` for the low-delay encoders and decoders, the voice
processor and the capture conversion. Train the families of the artifacts you use, and only the real-time
mode if that is the latency you care about (`TrainingOptions.forRealtime()`).

The content is synthetic and written by Smalt itself: by its encoders, and for the formats it only reads by small
writers in `smalt-aot` that simulate the decoders they feed (the camera raw files of every vendor the raw decoder
reads, Photoshop documents, DDS textures, DST frames, PGS and DVD subtitles, ASF files). The coding tools no writer
of Smalt produces train on reference streams the artifact bundles, each a copy of a pinned test vector: OpenEXR
files of each compression family, JPEG XR files, USAC, the optional modes of H.263, H.264 separately coded color
planes with CAVLC B pictures or scaling matrices, and the MPEG-2, VC-1, VVC, DTS, Speex, Theora, WavPack, Monkey's
Audio, Musepack, DSD and ATRAC9 streams.

From your application's own training mode (any application, and the only route on the module path, where
the application module `requires com.github.auties00.smalt.aot`), adding your workloads so that one cache
covers both:

```java
if (Boolean.getBoolean("app.aot.training")) {
    SmaltTraining.train(TrainingOptions.builder()       // or TrainingOptions.forRealtime().toBuilder()
            .families(Set.of(TrainingFamily.IMAGE, TrainingFamily.TRANSCODE))
            .addWorkloads(TrainingWorkload.of("my-pipeline", MyApp::processSample))
            .addTrackedPackages("com.example.app") // after Smalt's and the Vector API's packages
            .build());
    return;
}
```

```
JDK_AOT_VM_OPTIONS=-XX:+BackgroundCompilation \
java -Xbatch -XX:AOTCacheOutput=app.aot -Dapp.aot.training=true <production options> -m com.example.app
java -XX:AOTCache=app.aot <production options> -m com.example.app
```

Or, for a class-path application, without code changes: run `SmaltTraining` as the main class and your
own main after it with `--then` (`--families VIDEO,AUDIO`, `--modes realtime` and `--max-seconds 600` narrow
it):

```
JDK_AOT_VM_OPTIONS=-XX:+BackgroundCompilation \
java -Xbatch --add-modules jdk.incubator.vector -XX:AOTCacheOutput=app.aot -cp "app.jar:lib/*" \
     com.github.auties00.smalt.aot.SmaltTraining --then com.example.Main --self-test
java --add-modules jdk.incubator.vector -XX:AOTCache=app.aot -cp "app.jar:lib/*" com.example.Main
```

`-Xbatch` makes the training compile in the foreground, so two trainings record the same compilations;
`JDK_AOT_VM_OPTIONS` turns background compilation back on for the cache-creation phase, whose code is worse
with `-Xbatch`. A training run takes several minutes. A cache belongs to one JDK build, one OS and CPU, the
exact jars and the same `--add-modules`, `--enable-native-access` and `-XX:StartFlightRecording` options:
record it again after any of them changes, as the last step of your build. A JVM that rejects a cache
says so on standard error and runs without it.

> [!IMPORTANT]
> On stock JDK 26 to 28 the cache does not remove the Vector API's cold start. A JVM
> that resolves the incubating `jdk.incubator.vector` module does not archive its boot layer, so the
> cache keeps parsed classes only, without linked classes, profiles or compiled code (`-Xlog:aot` prints
> `archivedBootLayer not available` while recording and `Using AOT-linked classes: false` in production):
> the first seconds of real-time work still run uncompiled vector code, with correct output and a slow
> start. The full benefit needs a JDK built from the Leyden `premain` branch with the Vector API patches,
> [Auties00/leyden `premain-vector-aot`](https://github.com/Auties00/leyden/tree/premain-vector-aot), whose
> caches also store compiled vector code: there real-time coding runs compiled kernels from its first frame.
> Use JDK 26.0.2 or later: earlier builds do not notice a changed class path and run stale cached classes.

## Known limits

<details>
<summary><b>AOT training</b></summary>

`smalt-aot` has no workload for Phase One IIQ files, whose sensor data the raw decoder does not
read; a cache trained with `SmaltTraining` covers them only when the application's own workloads use them.

---

</details>

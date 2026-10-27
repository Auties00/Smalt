<!-- Link + badge definitions (maintained in one place) -->
[maven-central]: https://central.sonatype.com/artifact/com.github.auties00/smalt-core
[javadoc]: https://javadoc.io/doc/com.github.auties00/smalt-core
[license]: LICENSE
[notices]: tools/notices
[issues]: https://github.com/Auties00/Smalt/issues
[discussions]: https://github.com/Auties00/Smalt/discussions
[java26]: https://openjdk.org/projects/jdk/26/
[vector-api]: https://docs.oracle.com/en/java/javase/26/docs/api/jdk.incubator.vector/module-summary.html

[badge-javadoc]: https://img.shields.io/badge/JAVADOC-read-2f81f7?style=for-the-badge&logo=readthedocs&logoColor=white&labelColor=0b0e14
[badge-license]: https://img.shields.io/badge/LICENSE-MIT-2f81f7?style=for-the-badge&labelColor=0b0e14
[badge-java]: https://img.shields.io/badge/JAVA-26-f89820?style=for-the-badge&logo=openjdk&logoColor=white&labelColor=0b0e14
[badge-native]: https://img.shields.io/badge/NATIVE%20CODE-NONE-2f81f7?style=for-the-badge&labelColor=0b0e14

<div align="center">

<br/>

# S M A L T

### Pure-Java image, audio and video codecs, containers and capture for JVM languages

<br/>

[![Java][badge-java]][java26]
[![License][badge-license]][license]
[![No native code][badge-native]](#what-is-smalt)
[![Javadoc][badge-javadoc]][javadoc]

</div>

## What is Smalt

Smalt decodes, encodes, demuxes, muxes and processes images, audio and video in plain Java. No FFmpeg, no
JNI, no per-platform binaries to ship, sign or keep patched: the same jar runs everywhere a JDK does.

- **Codecs written from the specifications and ported from permissive reference implementations:** H.264,
  HEVC, VP8, VP9, AV1, JPEG, JPEG 2000, JPEG XL, PNG, GIF, WebP, TIFF, Opus, AAC, AC-3, E-AC-3, MP3, Vorbis,
  FLAC, ALAC and more.
- **Bit-exact where it matters:** the decoders reproduce the reference decoders' output byte for byte on the
  official conformance suites (JVT for H.264, libvpx for VP9, libaom for AV1), and Opus matches libopus
  packet for packet.
- **Fast:** hot kernels use the [Vector API][vector-api] and run within a small factor of native C and
  assembly, with scalar twins wherever SIMD is unavailable. Work is spread over one bounded thread pool.
- **Safe on hostile input:** every decoder and demuxer runs mutation and truncation suites; malformed data
  raises `MediaException`, never a hang or an unbounded allocation, within the limits you set with
  `MediaLimits`.
- **Modular:** take only the artifacts you need. The patent-pool codecs (H.264, HEVC, AAC, AMR, E-AC-3) live
  in their own artifact, so you can leave them out.

> **_IMPORTANT:_** Smalt is pre-`1.0`: expect breaking changes between releases until the API is frozen.

---

## Donations

Maintaining Smalt is an enormous amount of work which I do in my free time.

If you like my work, you can support me through [GitHub Sponsors](https://github.com/sponsors/Auties00).

---

## How to contribute

Smalt welcomes contributions from anyone: new formats, faster kernels, bug reports with sample files, and
measurements on hardware we don't have. Performance and correctness are absolute priorities: a change to a
decoder must keep its output bit-exact, and a performance claim needs an A/B measurement against the code it
replaces.

### Test data

Conformance tests read official test vectors and Xiph clips, listed with their checksums in
[tools/testdata](tools/testdata). `python tools/testdata/testdata.py fetch --tier smoke` downloads the few
hundred megabytes CI runs on every pull request, `--tier full` everything (about 17 GB of vectors and 6 GB of archive members). Tests also download
a missing vector on first use (`-Dsmalt.conformance.download=false` turns that off), retrying a host that
answers 429 or 5xx or does not answer, and skip, rather than fail, when its data cannot be had. The full tier holds the public conformance suites: the Argon AV1 coverage
streams, ISO/IEC 14496-4 MPEG-4 Visual (with the ISO/IEC 14496-5 reference decoder as the oracle), ISO/IEC
14496-26 AAC, ISO/IEC 15444-4 JPEG 2000, the libjxl conformance set, MPEG's ISO base media file format
conformance files and the DASH-IF test vectors. Members of archives too large to download whole are fetched by
HTTP range ([tools/testdata/archives.tsv](tools/testdata)), and every source carries a licence note; none of it is
committed. The nightly workflow runs the full tier on Linux x64 (and the `it` module's part of it on Windows and
macOS), and, weekly, every module on Windows, macOS and Linux arm64.

### Oracle tools and skips

Differential tests compare Smalt with reference tools run as black boxes: ffmpeg and ffprobe, the libwebp,
libjxl, OpenJPEG, OpenJPH, libjpeg-turbo, libtiff, FLAC and opus-tools command lines, ExifTool, MP4Box, MKVToolNix,
mkvalidator, TSDuck, SoX, and small libpng, Little CMS and libopus programs. [tools/testdata/oracles.tsv](tools/testdata/oracles.tsv)
pins them per CI platform, and `python tools/testdata/oracles.py install` installs that set (`versions` prints
what is installed). A test whose tool is missing skips; CI sets `SMALT_TEST_REQUIRE_ORACLES`
(`-Dsmalt.test.requireOracles=true` locally) so that it fails instead. Every skip carries a stable reason id,
`[family:name] detail`, through the test kit's `Skip` class, and `python tools/testdata/skips.py check --job
<job>` compares the skips of a build, per CI job, module, test class and reason, with
`tools/testdata/skip-baseline.tsv`: CI fails when a count grows, and the counts of the oracle, device and
environment families may only shrink.

### Tolerances and validators

A differential or conformance test declares how far Smalt may differ from the reference, once per codec, with the
test kit's `Tolerance`, in the comparison modes of FFmpeg's FATE and the criteria the standards define: exact for the
normative decoders (H.264, HEVC, VP8, VP9, AV1, FLAC, lossless WebP and JPEG XL, JPEG luma against libjpeg-turbo's
`djpeg -dct int`), off by one, maximum and mean absolute difference, RMS, PSNR, SNR and the fraction of differing
samples for the float decoders and the inverse DCTs the standards leave free, the ISO/IEC 11172-4 full accuracy
limits for MPEG audio (the compliance streams and every ffmpeg comparison), and libopus' `opus_compare` criterion
for Opus (ported, and checked against the tool where it is installed); a violation names the rule, every
statistic and the first differing sample and frame. `Ieee1180` runs the IEEE 1180-1990 accuracy test that
ISO/IEC 14496-2 and ITU-T H.263 require of the MPEG-4 Part 2 and H.263 inverse DCT.

Files Smalt writes are checked by validators that share no code with its demuxers, written from the
specifications in the test kit's `testkit.validate` package: ISO base media files, fragmented MP4 and CMAF
(ISO/IEC 14496-12, 14496-14 and 14496-15, 23000-19: box layout, sample tables, durations, codec configurations,
fragment sequence, data offsets and timelines, `sidx` and `mfra`), Matroska and WebM (RFC 8794 and RFC 9559),
MPEG-2 transport streams (ISO/IEC 13818-1: packets, continuity, PSI with CRCs, PES headers, PCR intervals), HLS
(RFC 8216, with the checks of Apple's `mediastreamvalidator`: versions, durations, `BANDWIDTH`, `CODECS`,
`RESOLUTION`, rendition groups, every segment) and MPEG-DASH (the MPD schema of ISO/IEC 23009-1, segment
addressing, timelines against `tfdt`, `codecs`, and `bandwidth` with `minBufferTime`). Where they are installed,
GPAC's `MP4Box -info`, the Matroska foundation's `mkvalidator` and TSDuck's `analyze` plugin read the same files;
`mkvalidator` 0.6 predates AV1, the block addition mapping of WebM and several registered codec IDs, so the reports
those draw are accepted, each with its reason in `testkit.validate.Validators`.
Every output of the container tests' muxing helpers, every container and codec pair of the `it` module's
mux/demux property, every HLS and DASH presentation of the packager tests and every file a test hands to
`Oracle.assertDecodesCleanly` goes through them. The output of every encoder is also decoded by a reference
decoder (ffmpeg with libvpx, dav1d, libopus and opencore-amr, the `flac` tool, libpng, libjpeg-turbo, libwebp,
OpenJPEG, libjxl and libtiff for images and metadata edits) and compared with Smalt's own decode under the
codec's declared tolerance (`it`'s `ReferenceDecodeTest` and `ReferenceImageDecodeTest`).

### I/O behavior and golden dumps

Every demuxer, reader and image decoder runs through the test kit's I/O behavior harnesses
(`testkit.container.DemuxerAsserts`, `testkit.container.ReaderAsserts`, `testkit.image.ImageDecoderAsserts`):
the same input is read in one-byte and random-sized chunks, with its length hidden, from a stream read once,
twice over one source, and with an I/O failure injected at a bounded, evenly spread set of reads (of whole reads,
of reads cut to random sizes, and of a stream read once), and every run must give the same packets, frames and
pictures or fail with a clean `MediaException.Io`; seeks are checked against the `Demuxer.seek` contract. Each input also has a readable golden dump (per-packet timestamps, flags, sizes and CRC-32, per-frame
CRC) under the module's `src/test/resources/dumps`; `-Dsmalt.test.updateDumps=true` rewrites the dumps, whose
diff is then reviewed. The inputs are small samples made by the scripts in
[tools/testdata/behavior](tools/testdata/behavior) and pinned by SHA-256, and smoke-tier vectors. A change
that adds a container or a codec adds its inputs to these tests, as the `testkit.io` package documentation
describes.

### Fuzzing

Every decoder, demuxer, parser and metadata reader has a coverage-guided fuzz target, a
[Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) `@FuzzTest` method in the `fuzz` test package of its
module: anything but a `MediaException` escaping from the entry point is a finding, and so is a `MediaException`
that only reports an internal failure, such as an index out of bounds, that the entry point caught. Every build replays the
targets' seeds and the inputs of past findings (`src/test/resources/fuzz-regressions`) as plain tests;
[ClusterFuzzLite](https://google.github.io/clusterfuzzlite/) fuzzes every pull request for a short while and
every night for hours. `python tools/fuzz/fuzz.py run core:DemuxerFuzzTest.mp4` fuzzes one target locally;
[tools/fuzz](tools/fuzz) describes how to reproduce, minimize and save a finding.

### Hostile inputs and properties

The `hostile` tests of the `it` module feed every entry point inputs built to exhaust it: decompression bombs
(headers rewritten to declare pictures, channel counts, frame counts and sizes over the `MediaLimits`, which must
be rejected with `MediaException.LimitExceeded` before the declared memory is allocated, as a per-call allocation
meter checks), cyclic offsets (TIFF and EXIF directories chained to themselves, HEIF images derived from
themselves, a Matroska seek head pointing at itself) and nesting tens of thousands of levels deep, parsed on a
quarter of the JVM's default thread stack. Tests run at the default thread stack size, and CI replays every fuzz
seed and finding, and runs these tests, in a 256 MiB heap; a nightly job fuzzes a quarter of the targets in turn
with that heap.

The `property` tests check invariants that need no reference output: lossless coding (PNG, TIFF, JPEG 2000,
lossless JPEG, AVIF, WebP, GIF, ICO, ANI, FLAC, ALAC, PCM, and JPEG XL and other encoders' lossless JPEG encoded
by the reference tools) decodes to
every input sample; muxing then demuxing returns every packet of every codec in every container Smalt writes;
tag edits read back as written, are idempotent and never touch the media; video seeks return what a linear
decode returns from the target on; and output does not depend on the thread count, the load on the shared
threads or `smalt.threads`.

### Coverage, soak and concurrency

`mvn -Pcoverage verify` measures line and branch coverage with JaCoCo and `tools/coverage/coverage.py` turns the
report into a map of the code no test reaches, per codec and container; the nightly workflow publishes it and
fails when an area falls below [tools/coverage/baseline.tsv](tools/coverage). The smoke tier holds a vector for
each decoder feature the map would otherwise show as untested (codeword reordering and low delay SBR in AAC, FMO
slice group maps in H.264, ThunderScan and old-style JPEG in TIFF, RW2 tiles, Canon sRAW and mRAW, the 3GPP AMR
sequences); where no small vector exists the tests keep their own, coded by a reference encoder and checked
against its decoder (AV1 super-resolution and frame resizing from libaom, HEVC picture hashes on multi-row
pictures from the HM reference encoder), or compare with a reference decoder
built from its source (AMR comfort noise and bad frames against the 3GPP decoders). A weekly workflow soaks the
real-time codecs, the transcoder and the picture operations for hours, checking that heap, threads, file
descriptors and allocation stay flat, and runs the [jcstress](https://github.com/openjdk/jcstress) tests of the
shared thread pool and the codecs' wavefronts (`mvn -Pjcstress -pl it -am verify -DskipTests`).

### Benchmarks in CI

The JMH benchmarks live in each module's test sources; [tools/bench/benchmarks.tsv](tools/bench) picks the ones
CI runs, among them one round of every synthetic steady-state workload of the `it` module (the video and audio
codecs open across rounds, the image workloads coding whole images). `.github/workflows/bench.yml` always builds
the base and the head side by side and compares them in one job, since the hosted runners differ too much from run
to run to compare with recorded numbers. On
every pull request it measures the bytes each case allocates per operation (JMH's gc profiler) in both trees and
fails when the head allocates more than the base plus a tolerance; a change that allocates more on purpose carries
the `allocation-accepted` label. Every week, and on demand, it times every case in both trees, fork by fork and
interleaved, and reports each change with a 95% confidence interval; a slowdown of 20% or more fails the run, and
the head's numbers are kept on the `gh-pages` branch. Smaller effects need the quiet machine of
[Performance](#performance).

### Vector kernels and the JIT

Every Vector API kernel has a scalar twin, and an equivalence test compares the two on the same inputs. A
kernel the JIT compiler cannot compile still gives the right output, on the Vector API's allocating
fallback, many times slower, so the tests alone do not show it; three checks do:

- The kernel JIT guard (`testkit.jit.KernelGuard`, run by each module's `jit.*KernelGuardTest`) runs every
  kernel family, its `Vector*` classes and the equivalence tests that call them, in child JVMs with
  synchronous compilation and a fixed warm-up. Every kernel entry point must then have C2 code, allocate
  nothing per call (thread allocation counter), print no failed vector intrinsic and match its scalar
  twin; no failed compilation and no deoptimization of a kernel may happen in the measured round. It runs
  under tiered compilation, C2 only, a profile polluted by every vector shape the codecs use, a fixed C2
  stress seed and every vector width narrower than the machine's (AVX2 on AVX-512, 256 and 128 bits), and
  natively on arm64 (NEON). There are no tolerances and no retries: a failure prints the command line that
  reproduces it. `-Dsmalt.jit.scenarios=nightly` adds a polluted profile under C2 only and five more
  stress seeds, `-Dsmalt.jit.families=<regex>` selects families; the `it` module's `KernelGuardSelfTest`
  checks that the guard fails on broken and fragile kernels and when the Vector API intrinsics are turned off.
  The `aot` scenario starts the child from a JDK AOT cache trained on the family; on JDK 26 the cache holds
  no profiles with the Vector API (see [Start fast with an AOT cache](#start-fast-with-an-aot-cache)),
  so the scenario is not in the matrix.
- The kernel lint (`kernel-lint`, run in the `it` module's `verify`) reads the kernels' bytecode and fails
  on what keeps C2 from compiling a Vector API operation (calls on vectors of unknown class, vectors in
  and out of methods, operators that are not constants, oversized kernels, wide species outside
  `Simd.WIDE`), printing `File.java:line` and a rule id; `internal.simd.KernelLintSuppress` with a reason,
  or a row of the shrink-only `tools/lint/kernel-baseline.tsv`, accepts a finding. forbidden-apis bans the
  Vector API's convenience methods, `jdk.incubator.vector` outside the `Vector*` classes and the
  transcendental `java.lang.Math` methods, whose results may depend on the CPU (`StrictMath` does not).
- Nightly, the bit-exact suites run with C1 only (`-XX:TieredStopAtLevel=1`, on the scalar twins: C1
  leaves every Vector API operation on its fallback) and with C2 only (on the Vector API kernels), and
  `JitStressTest` runs mixed codec workloads in fresh JVMs and fails on any allocation C2 code makes in
  the Vector API fallback after warm-up (JFR allocation samples), naming the Smalt frame and the calls C2
  did not inline; samples from the interpreter, from C1 code and from deoptimized code are printed.

### Generated kernels

The most repetitive Vector API kernels (the VP9 and AV1 transforms, loop filters and motion compensation,
the image transposition networks) and the Vorbis encoder's setup data are generated by the scripts in
[tools/codegen](tools/codegen). Edit the generator or its template, not the generated file; `python
tools/codegen/check.py`, which CI also runs, fails when a generated file differs from its generator's output.

### Comments

Doc comments follow the Documentation standard in [CLAUDE.md](CLAUDE.md). `python tools/lint/doclint.py`, which
CI also runs, checks its mechanical rules (and, with a javadoc log, doclint's findings on the exported API)
against `tools/lint/baseline.tsv`; a change may shrink that baseline but never grow it.

### Licensing of contributions

Smalt is MIT licensed and ports only from permissively licensed code (MIT, BSD, Apache-2.0, ISC, zlib,
public domain). GPL and LGPL code, FFmpeg included, may be run as a black-box reference in tests, but must
never be read, copied or ported. Every ported file names its source in a `/* Ports ... */` comment above its
`package` line (project, version, files, copyright holder and license), and every port whose license asks for
attribution is also recorded in the [third-party notices][notices].

### AI contributions

AI-generated code is permitted, but the person opening the PR is accountable for every line in it, and it
will be reviewed in depth against Smalt's priorities like any other change.

No AI-generated commit messages, issue bodies, PR descriptions, discussion posts, or review comments.
Human-to-human interactions must remain in natural language: I prefer to talk with a person, and I would
rather read confused human text than an LLM's.

---

## Formats

Every format Smalt reads or writes, the artifact that carries it, the package that implements it (relative
to `com.github.auties00.smalt`), where its code comes from, and what to know about patents. In the Source
column, "spec" means the code was written from the specification alone; a named project means code was
ported or derived from it (see [Acknowledgments](#acknowledgments)), and each artifact lists those whose
license asks for it, with their licenses, in its `META-INF/THIRD-PARTY-NOTICES`.

Codecs are found at run time through the `spi` providers, so a format whose artifact is missing fails with
`MediaException.Unsupported` naming the artifact to add.

### Images

| Format | Decode | Encode | Artifact | Package | Source | Patents |
|---|---|---|---|---|---|---|
| JPEG | baseline, extended, progressive, arithmetic coding, 8-bit and 12-bit (bit-exact with `djpeg -dct int` at both depths); shrink-on-load; lossless Huffman and arithmetic (SOF3, SOF11) at 2 to 16 bits with any sampling factors, in one scan or several (bit-exact on the ITU-T T.83 compliance streams), and the RGB files of ffmpeg's lossless encoder (reversible color transform); frames of 2 or 5 to 255 components (raw channel pictures with `keepChannels`, the meaning of their channels from the ICC profile); hierarchical coding (DHP, EXP; SOF5, SOF6, SOF7, SOF13, SOF14, SOF15 differential frames over SOF0 to SOF3, SOF9 to SOF11 frames: DCT frames at 8 and 12 bits, sequential or progressive, Huffman or arithmetic, a final lossless frame, lossless hierarchies at 2 to 16 bits, any sampling factors, restart intervals, DNL; decoded at full size, exact when the last frames are lossless); JPEG-LS (ITU-T T.87: lossless and near-lossless, 2 to 16 bits, every interleave mode, subsampled components, preset coding parameters and MAXVAL, restart intervals, oversize dimensions, DNL, point transform, mapping tables as scalar maps or RGB palettes, the HP color transforms; bit-exact on the T.87 conformance data and against CharLS, the ISO/IEC 10918-7 reference software and ffmpeg); samples above 8 bits reduced to 8 bits or kept with `ImageDecoderOptions.highBitDepth` (gray and YCbCr as `Yuv16Picture`, RGB as `Rgba16Picture`); CMYK and YCCK kept as CMYK channel pictures with `keepChannels`; HDR gain maps of Ultra HDR and Adobe gain map files (ISO 21496-1 `APP2` metadata or `hdrgm` XMP, `JpegDecoder.decodeGainMap`) and Apple gain maps converted to ISO 21496-1; the secondary images of the Multi-Picture Format index or of the XMP container directory (thumbnails, stereo views, disparity and depth images, gain maps) listed and decoded (`JpegDecoder.auxiliaryImages`, `decodeAuxiliaryImage`) | baseline and extended sequential (one scan or a script of several), progressive (the default script or any custom one), arithmetic coding (SOF9, SOF10, SOF11) and lossless (SOF3, SOF11: predictors 1 to 7, point transform, 2 to 16 bits, also through `ImageEncoderOptions.lossless`); 8-bit and 12-bit; YCbCr (JFIF, with pixel density) and gray, RGB, CMYK and YCCK (Adobe `APP14`); ICC profiles (`APP2 ICC_PROFILE`), EXIF (`APP1 Exif`) and XMP (`APP1`, a large packet split into Extended XMP segments); any sampling factors (4:4:0, 4:1:1 and others), custom quantization tables, slots and per-component quality, 16-bit quantizers on request; byte-identical to `cjpeg -dct int` with the matching switches (libjpeg-turbo's bit tests included) and, for CMYK and YCCK, to the TurboJPEG API; lossless transforms of any decodable 8-bit or 12-bit DCT JPEG (`JpegTransforms`: rotations, flips, transpose, transverse, crop on MCU boundaries with trimming, perfect-only or exact sizes, crop extension beyond the image filled gray, flat or by reflection, wipe of a region filled the same ways, drop of another JPEG image with common or requantized tables, grayscale, sequential or progressive output with standard or optimized Huffman tables or arithmetic coding, restart markers every N MCUs or N MCU rows, marker copying, EXIF auto-orientation with the thumbnail transformed), byte-identical to `jpegtran`; JPEG-LS (`JpegLsEncoder`): lossless and near-lossless, 2 to 16 bits, gray, RGB and Y'CbCr in its stored subsampling, every interleave mode, preset coding parameters, the HP color transforms, restart intervals, oversize dimensions, a SPIFF header, byte-identical to CharLS; Ultra HDR files with a gain map (`JpegEncoder.encode(picture, gainMap, ...)`: ISO 21496-1 and `hdrgm` XMP metadata, container directory, Multi-Picture Format index), decoded by libultrahdr as Smalt renders them | `smalt-image` | `image.jpeg` | libjpeg-turbo and the IJG software (including jpegtran's transupp.c and jctrans.c, and the encoder's jcarith.c, jclossls.c and jclhuff.c); stb_image (Huffman lookup); CharLS (JPEG-LS); spec (ITU-T T.81 annex H for arithmetic-coded lossless, decoding and encoding, and annex J for hierarchical decoding; ITU-T T.87; Google's Ultra HDR image format and libultrahdr (gain map file layout), CIPA DC-007 (Multi-Picture Format)) | none in force |
| PNG, APNG | every color type and bit depth, interlacing, animation; `cICP` with `mDCV`/`cLLI` HDR metadata | PNG: every color type, the smallest lossless one chosen from the pixels (indexed and grayscale at 1, 2, 4 or 8 bits, grayscale with alpha, truecolor, truecolor with alpha; 16 bits for `Rgba16Picture` and for `Yuv16Picture` deeper than 8 bits; `tRNS` palettes and color keys), Adam7 interlacing, `sBIT`, `gAMA`, `cHRM`, `sRGB`, `pHYs`, ICC (`iCCP`) or PQ/HLG (`cICP`, `mDCV`, `cLLI`, then the ICC form in `iCCP` unless grayscale) profiles, EXIF (`eXIf`) and XMP (`iTXt XML:com.adobe.xmp`); APNG (changed rectangles, disposal and blend chosen by size, one color type for the file: indexed, truecolor or truecolor with alpha; exact fraction delays; optional fallback image; ICC or PQ/HLG profile, EXIF and XMP) | `smalt-image` | `image.png` | spec; libpng (vector filters), stb_image (Paeth) | none |
| GIF | still and animated; ICC profiles (`ICCRGBG1` extension, as `colorProfile`) and XMP (`XMP DataXMP` extension) | still and animated: exact palettes up to 256 colors, otherwise median cut or NeuQuant quantization with none, ordered, Floyd-Steinberg or Sierra dithering; binary transparency; global or local palettes; changed rectangles with transparent unchanged pixels and disposal chosen per frame; looping, interlacing; an RGB ICC profile and an XMP packet | `smalt-image` (quantizers in `smalt-core`) | `image.gif`, `image.gif.internal`, core `internal.quantize` | spec (GIF89a, ICC.1 Annex B); giflib (LZW, byte-identical), NeuQuant | LZW patents expired (2004) |
| WebP | lossy, lossless, alpha, animated | lossy (byte-identical to `cwebp` 1.6.0 at equal settings: content presets, sharp or dithered RGB to Y'CbCr, spatial noise shaping, loop filter strength, sharpness, simple filter and per-segment automatic filter, segments, entropy passes, target PSNR; also token partitions and a target size), lossless and near-lossless (byte-identical to `cwebp -lossless` and `-near_lossless`), alpha, animated; ICC profiles, EXIF and XMP (`VP8X` with `ICCP`, `EXIF` and `XMP ` chunks), still and animated | `smalt-image` | `image.webp` | libwebp | Google's royalty-free WebP grant (`PATENTS` in libwebp) |
| BMP | Windows and OS/2 headers (core, OS/2 2.x, `BITMAPINFOHEADER` to V5) and OS/2 bitmap arrays (`BA`, the largest bitmap or the best fit for a size hint, every bitmap as a page); 1, 2, 4 and 8-bit palettes, 16 and 32-bit pixels with any color masks (scaled by rounding, 16-bit output with `highBitDepth` for masks over 8 bits), 24-bit, 64-bit linear s2.13 pixels (sRGB-encoded, 16-bit with `highBitDepth`); RLE8, RLE4, OS/2 RLE24 and Huffman 1D (ITU-T T.4 modified Huffman); embedded JPEG and PNG files; CMYK pixels and color tables (`BI_CMYK`, `BI_CMYKRLE8`, `BI_CMYKRLE4`; naive conversion, CMYK channel pictures with `keepChannels`, converted through an embedded CMYK profile on `convertToSrgb`); V5 embedded ICC profiles and V4/V5 calibrated color spaces (endpoints and per-channel gammas as `colorProfile`); damaged files decoded as far as they go (BMP Suite references) | 1, 4 and 8-bit palettes (exact or quantized with median cut or NeuQuant and any dithering), RLE4 and RLE8, 16-bit 5-5-5 and 5-6-5, 24-bit, 32-bit with an unused byte or with alpha (V5 alpha mask); the smallest lossless layout by default; `BITMAPINFOHEADER`, V4 or V5 headers, top-down rows, resolution, an embedded ICC profile (`BmpEncoder`, `Images.encode`, ImageIO `smalt-bmp`) | `smalt-image` | `image.bmp` | spec (Microsoft bitmap headers, OS/2 2.x bitmap documentation, ITU-T T.4 through the TIFF fax decoder) | none |
| TIFF, BigTIFF | strips and tiles, interleaved and separate planes, every page (`TiffDecoder.openPages`, `Images.openPages`); none, PackBits, LZW (also pre-6.0), Deflate, JPEG (with `JPEGTables`; 8-bit and 12-bit DCT samples, and lossless JPEG, lossless hierarchical JPEG and JPEG-LS strips and tiles of 2 to 16-bit samples without subsampling), old-style JPEG (8-bit), CCITT modified Huffman, T.4 and T.6 fax, ThunderScan, Zstandard (codes 50000 and 34926), LZMA (XZ streams with the delta and branch filters, or .lzma), LERC (Lerc2 versions 1-6 and legacy Lerc1, bare or in Deflate or Zstandard per `LercParameters`, masks as alpha or NaN), PixarLog (8/16-bit and float samples), NeXT 2-bit, JBIG (every self-contained ITU-T T.82 entity), WebP (8-bit RGB and RGBA), JPEG XL (codes 50002 and DNG's 52546; up to 16-bit integer and 16/32-bit floating-point samples), JPEG 2000 (code 34712 and the Aperio codes 33003 and 33005); horizontal (also on YCbCr data units, as libtiff writes it) and floating-point predictors; bilevel, gray, palette, RGB, CMYK (naive conversion, ICC profile reported), named-ink separations (`InkSet` 2, through their `nCLR` ICC profile), YCbCr (any subsampling, interleaved or in separate planes, 1-16 bits), CIE, ICC and ITU L*a*b* of any integer depth or floating point and SGILog LogL/LogLuv (rendered for display as libtiff does), associated and unassociated alpha, transparency masks (photometric 4) applied to the page they mask; 1-32-bit integer, 16/24/32/64-bit floating-point and complex (decoded to the magnitude) samples, `BitsPerSample` differing between samples (16-bit output on request); reduced-resolution pyramid levels (`SubIFDs` or reduced main-chain pages) for the size hint; EXIF and GPS directories, orientation, ICC (CMYK through its profile on `convertToSrgb`); with `keepChannels`, CMYK and named-ink (`InkSet` 2) separations, floating-point samples and extra samples kept as stored in channel pictures | none, PackBits, LZW, Deflate, Zstandard, with the horizontal predictor on integer samples and the floating-point predictor on floating-point samples, CCITT modified Huffman (also word-aligned), T.4 (one- or two-dimensional, fill bits) and T.6 fax (byte-identical to libtiff's), JPEG (Technical Note 2: abbreviated strips or tiles, tables in `JPEGTables`, gray or Y'CbCr with the JPEG encoder's subsampling, quality and Huffman optimization), WebP (8-bit RGB and RGBA) and JPEG 2000 (every integer layout), lossless by default; gray, RGB or CMYK (separated without color management, decoding back exactly), with or without alpha, 8 or 16 bits or 32-bit floating point; palette (1, 2, 4 or 8-bit indexes, exact or quantized); bilevel (threshold or dithering); Y'CbCr (4:4:4, 4:2:2, 4:2:0 data units, a Y'CbCr picture's own matrix and range kept); channel pictures written as stored: CMYK, named inks, gray and RGB with extra samples, 8 or 16-bit integer or 32-bit floating-point samples; interleaved or separate planes; strips or tiles; either byte order; BigTIFF; multi-page (streamed, settings per page, `Images.openPageWriter`); resolution, orientation, ICC, EXIF (Exif, GPS and interoperability directories), XMP and IPTC | `smalt-image` | `image.tiff`, `image.tiff.internal` | spec (TIFF 6.0, Technical Note 2, Adobe Deflate and floating-point predictor notes, ITU-T T.4/T.6, ITU-T T.82 for JBIG, RFC 8878 for Zstandard, the .xz format for LZMA); LibTIFF (YCbCr tables, L*a*b* and SGILog conversions, LZW schedule, predictors, ThunderScan, PixarLog, NeXT, fax line handling and coding, tag semantics); Esri LERC (Lerc2 and Lerc1 decoding); XZ for Java (LZMA and the branch filters); the strip and tile conventions of LibTIFF and GDAL for WebP and JPEG XL and of Aperio for JPEG 2000 | none (LZW patents expired 2004; JPEG baseline none in force) |
| ICO, CUR | every entry (`IcoDecoder.entries`), the largest by default or the best fit for a size hint; bitmap entries of 1-32 bits with AND mask or alpha, PNG entries; cursor hotspots | bitmap (32-bit BGRA with AND mask) or PNG entries, one per picture or one square entry per size scaled from one picture; cursors with a hotspot scaled per entry | `smalt-image` | `image.ico` | spec (Microsoft ICO layout); bitmap entries through the BMP decoder | none |
| ANI | animated cursors (RIFF `ACON`): `anih`, `rate` and `seq ` steps played forever as an `AnimationReader` that seeks to any step, `INFO` title and author (`AniDecoder.header`), icon and cursor frames (`AniDecoder.frame`) drawn on a canvas as large as the largest frame, the entry chosen by the size hint | frames and steps (`AniEncoder.encode`, with `rate` and `seq ` chunks only when needed), `Images.encode` (one step), `Images.openAnimationWriter` (durations rounded to jiffies on the running end time, repeated pictures stored once); icon or cursor frames as `IcoEncoderOptions` set them; `INFO` title and author | `smalt-image` | `image.ico` | spec (Microsoft RIFF and ANI layouts); frames through the ICO decoder and encoder | none |
| JPEG 2000 | JP2 and JPX files (`jp2h`, `ihdr`, `bpcc`, `colr` enumerated and ICC chosen by precedence, `res`, `pclr`/`cmap` palettes, `cdef` channels including alpha, EXIF and XMP boxes; JPX fragment tables, several codestreams with `jpch`/`jplh` headers, `cgrp`, `opct` opacity and chroma keys, `creg` registration, `cref` cross-references, compositing layers as pages (`Jp2Decoder.openPages`) and the `comp` composition as an animation (`Jp2Decoder.openAnimation`)) and raw codestreams; tiles, subsampled components, 1-38-bit signed and unsigned samples, reference grids up to 2^32, every progression order and POC, precincts, every code-block style (bypass, reset, termination, vertical causal, segmentation symbols), SOP/EPH, PPM/PPT, scalar derived and expounded quantization, 5-3 and 9-7 wavelets, RCT/ICT, ROI max-shift; Part 2 extensions: variable DC offsets, arbitrary decompositions (`DFS`/`ADS`), arbitrary wavelet kernels (`ATK`), array-based and wavelet-based multiple component transformations (`MCT`/`MCC`/`MCO`/`CBD`), non-linear point transformations (`NLT`), rectangle and ellipse regions of interest; quality-layer and resolution reduction (size hints decode a lower resolution); sYCC, e-sRGB, e-sYCC, YCbCr(1-3), YPbPr, PhotoYCC, CMY, CMYK, YCCK, CIE L*a*b* (with its enumerated parameters and illuminant), CIECAM97s J*a*b* and ROMM converted to RGB (CMYK and its alpha kept as a channel picture with `keepChannels`); tiles and code-blocks decoded in parallel; HTJ2K (ISO/IEC 15444-15): HT cleanup, SigProp and MagRef passes, placeholder passes, several HT sets (the last one is decoded), HT and Part 1 code-blocks mixed in a tile-component, regions of interest, `CAP` and `CPF` segments, and JPH files (the `jph ` brand, the any-ICC and parameterized `colr` methods), every HTJ2K conformance codestream and JPH file of ISO/IEC 15444-4:2024 decoded within the statistics of its Kakadu transcode; bit-exact with `opj_decompress` built without `-ffast-math`, HTJ2K included wherever OpenJPEG decodes it | lossless 5-3 and lossy 9-7 (compression ratios or PSNR targets per quality layer, rate allocation by post-compression rate-distortion), JP2 (with `colr`, `cdef` for alpha or premultiplied alpha, `bpcc`, the `res` capture resolution) or raw codestream, CMYK channel pictures as inks, tiles, image and tile origins on the reference grid, code-block sizes, precinct sizes per resolution, progression orders and progression changes (`POC`), tile-parts by resolution, layer or component, `TLM` and `PLT` markers, code-block styles, SOP/EPH, RCT/ICT, guard bits, regions of interest by max-shift (a whole component, as `opj_compress -ROI`, or a rectangle of every component, its `RGN` segments in the tile-part headers), codestream and per-component size limits, the digital cinema 2K and 4K and IMF profiles (`Jp2EncoderOptions.forCinema2k`, `forCinema4k`, `forImf`, with their image checks), comments, and EXIF, XMP and IPTC `uuid` boxes; lossless output byte-identical to `opj_compress` with the same options (`-d`, `-T`, `-TP`, `-TLM`, `-PLT`, `-POC`, `-ROI`, `-GuardBits`, `-c` lists and `-IMF` included); HTJ2K (`highThroughput`): one HT set per code-block (cleanup, SigProp and MagRef passes), its bit-plane chosen per code-block by rate-distortion optimization over the layers' targets, vertically causal contexts, JPH files or HTJ2K codestreams, decoded by `opj_decompress` and OpenJPH's `ojph_expand` as Smalt decodes them (single-layer output; `opj_decompress` also the layers that keep each code-block in one packet) | `smalt-image` | `image.jp2`, `image.jp2.internal` | OpenJPEG 2.5.3; OpenJPH 0.21.3 and OpenHTJ2K (HTJ2K); spec (ISO/IEC 15444-2, Rec. ITU-T T.801) and the CIE TC1-34 CIECAM97s report for Part 2 and JPX | none known in force for Part 1 (ISO/IEC 15444-1 was made available royalty-free); Part 15 (HTJ2K) was developed to be royalty-free, like Part 1 |
| JPEG XL | bare codestreams and containers (`jxlc`, `jxlp`, `Exif`, `xml `, `jbrd`, every `brob` box decompressed, all boxes listed by `JxlDecoder.readBoxes`); lossless and lossy modular (MA trees, every predictor, RCT, palette, squeeze), VarDCT (every transform, chroma from luma, adaptive LF smoothing, LF frames, progressive passes), Gaborish and EPF, patches, splines, noise, upsampling; animation (blending, reference frames, crops) as `AnimationReader`; XYB, YCbCr and enumerated color encodings and ICC profiles (ICC as `colorProfile`, gray and RGB XYB images converted to their embedded profile, HDR PQ/HLG described by CICP), CMYK through its profile; spot color channels composited into RGBA output as libjxl renders them (kept apart in channel pictures and `decodeFloat`); alpha (straight or premultiplied) and extra channels (`JxlDecoder.decodeFloat`: every channel at full precision as planes; `keepChannels`: CMYK, floating-point and extra-channel images as channel pictures); 8-bit by default, 16-bit with `highBitDepth`; orientation reported; the preview frame (`JxlDecoder.decodePreview`), and under a size hint the preview, the LF image (1/8) of a VarDCT frame or a decode from the first progressive passes (1/2, 1/4); gain maps (`jhgm`, plain or in `brob`: ISO 21496-1 metadata, alternate color space, gain map image, `JxlDecoder.decodeGainMap`, `JxlGainMap.toGainMap` for the format-independent model); seeks starting at the independent frames of the `jxli` frame index; recompressed JPEG files rebuilt byte for byte (`JxlDecoder.reconstructJpeg`) | - | `smalt-image` | `image.jxl`, `image.jxl.internal.*` | jxl-oxide (decoder, JPEG reconstruction); libjxl (palette, transfer functions, render order, spot color stage, gain map bundle, MCU grid of single-component JPEG scans); Brotli (reference Java decoder, dictionary) | libjxl's royalty-free patent grant (`PATENTS` in libjxl) |
| EXIF | IFD0, Exif, GPS, interoperability and thumbnail directories; the EXIF 3.0 `UTF-8` type (129) and the `IFD` type (13); JPEG and uncompressed (strip) thumbnails | fresh layout, or an edit that keeps unchanged values (the maker note, sub-directory offsets of type 13, a thumbnail's strips) at their offsets and zeroes what it removes; uncompressed thumbnails as one strip | `smalt-image` | `image.exif` | spec (EXIF 2.32 and CIPA DC-008-2023 (EXIF 3.0), TIFF 6.0 and Technical Note 1) | none |
| AVIF | any HEIF file whose image is AV1-coded, whatever its brands (files with only `mif1`, `miaf` or `msf1` included; `Images.detect` reads the items to tell); primary item (single AV1 item or `grid`; `iden`; `iovl` composed to RGBA, at 16 bits when `highBitDepth` keeps a deeper input; grids of derived inputs; nested up to 8 deep), sample transforms of AVIF 1.2 (`sato`, every operator and intermediate width, up to 16 bits, as the primary item or as the alternative an `altr` group prefers to it, falling back to the primary item when unsupported), alpha (item, grid or per tile; premultiplied; of another size than the color, resampled with the triangle filter, or another depth, rescaled), layered images (`a1op`, `lsel`), clean aperture (applied), rotation and mirroring (reported as the orientation), `nclx`/ICC color, `clli`/`mdcv`, EXIF and XMP, 8/10/12-bit (8-bit by default, 8-bit RGBA for YCgCo-Re/Ro sources deeper than 8 bits, `highBitDepth` keeps the depth), thumbnails (`thmb`) under a reduced-size hint, image sequences (`avis`, with alpha) as animations that seek through their sync samples, 16-bit RGBA frames with `highBitDepth`; gain maps (`tmap` derived image items with ISO 21496-1 metadata and the alternate color, as libavif reads them, `AvifDecoder.decodeGainMap`, rendered as libavif's `avifgainmaputil` renders them within one 10-bit code value, see Known limits); auxiliary images (alpha, depth, thumbnails, the gain map) listed and decoded (`auxiliaryImages`, `decodeAuxiliaryImage`); collections: every master image as a page (`AvifDecoder.openPages`, `Images.openPages`; hidden items, thumbnails, auxiliary images, tone maps and the inputs of derived images left out, each `altr` group one page) with the other entity groups as `PageReader.groups` (`ster` left view then right view, `brst`, `tsyn`, `albc` and any other type); images, derived-image inputs and alpha images coded with HEVC, AVC, JPEG, JPEG 2000 or as uncompressed samples decoded by the HEIC decoder when `smalt-mpeg` is present | still images and image sequences (`AvifEncoder`, `Images.encode`, `Images.openAnimationWriter`, ImageIO): quality 0-100 and speed 0-10 on libavif's scales, presets `forWeb`, `forDistribution` and `forArchive`, lossy 8-bit 4:2:0 sequences coded with predicted frames by the good-quality AV1 encoder (`interFrames`, on by default), lossless (RGB as 4:4:4 identity), 8/10/12-bit, 4:2:0/4:2:2/4:4:4/monochrome, lossless or lossy alpha items (premultiplied on request), automatic AV1 tiles, image grids for pictures over 8192 samples, `nclx`/ICC color, `irot`/`imir`, `clli`/`mdcv`, Exif and XMP; AV1 coded by an intra-only rate-distortion encoder (partitions down to 4x4, every intra mode with angle deltas, filter intra, chroma-from-luma, palettes for screen content, every transform size and intra type, deblocking, CDEF and, at speeds 0-4, loop restoration searches); gain maps (`AvifEncoder.encode(picture, gainMap, ...)`: a hidden gain map item, a `tmap` item with the ISO 21496-1 metadata and the alternate color, an `altr` group and the `tmap` brand) | `smalt-video` (HEIF parser in `smalt-core`) | `image.avif`, `internal.heif` | libavif (container semantics, sample transform operators, writer layout); AV1 payloads through the dav1d port; the encoder follows libaom's all-intra mode | Alliance for Open Media Patent License 1.0 (royalty-free) |
| HEIC | primary item (`hvc1` or `avc1` item, possibly predicted from others with `pred`, an intra-coded HEVC item that is not a random access point included; `lhv1` layered HEVC item (SHVC or MV-HEVC, `lhvC` configuration, target output layer set `tols`, layer selector `lsel`, an AVC-coded base item through `exbl`), shown in place of the primary item when its alternatives group (`altr`) prefers it and it can be reconstructed; `jpeg` item with its `jpgC` header (ISO/IEC 23008-12 Annex H) and `j2k1` JPEG 2000 item with its `j2kH` channel definitions, palettes and opacity (ISO/IEC 15444-16), both when `smalt-image` is present; `unci` uncompressed item (ISO/IEC 23001-17: every interleaving, 4:4:4/4:2:2/4:2:0/4:1:1, 1 to 16-bit unsigned components bit-packed, byte-aligned or in blocks, pixel, row and tile padding, tiles, version 1 profiles, `defl`/`zlib` generic compression); `grid` with tiles decoded in parallel; `iden`; `iovl` composed to RGBA, at 16 bits when `highBitDepth` keeps a deeper input, nested up to 8 deep), alpha (`auxid:1` or CICP alpha URN: item, grid or per tile; premultiplied; limited range expanded; of another size than the color, resampled with the triangle filter, or another depth, rescaled), grids of RGBA inputs (overlays, premultiplied images) composed to RGBA, clean aperture (applied), rotation and mirroring (reported as the orientation), `nclx` color over the stream's VUI with the MIAF defaults, ICC as `colorProfile`, `clli`/`mdcv` or SEI, EXIF and XMP (`mime` items, `deflate` and `gzip` content encodings), every HEVC format of the range extensions (8 to 16-bit, 4:0:0, 4:2:0, 4:2:2 such as camera `.hif` files, 4:4:4 also as separate color planes, luma and chroma of different depths; `highBitDepth` keeps the depth) and the screen content coding tools, thumbnails (`thmb`) under a reduced-size hint, either view of the stereo pair (`ster`) the primary item belongs to (the eyes of a spatial photo, the views of a multiview item) through `HeicDecoder.decodeStereoView`, image sequences (`msf1`, HEVC or AVC tracks, with alpha and reordering) as animations that seek through their sync samples, 16-bit RGBA frames with `highBitDepth`; gain maps (`tmap` derived image items with ISO 21496-1 metadata, and Apple's `urn:com:apple:photo:2020:aux:hdrgainmap` auxiliary images converted to ISO 21496-1 with the headroom of the Apple maker note, `HeicDecoder.decodeGainMap`); auxiliary images (depth maps, portrait and semantic mattes, alpha, thumbnails, gain maps) listed and decoded (`auxiliaryImages`, `decodeAuxiliaryImage`); items stored in other files (`dinf`/`dref` of the `meta` box) through `ImageDecoderOptions.dataReferences`; collections: every master image as a page (`HeicDecoder.openPages`, `Images.openPages`; hidden items, thumbnails, auxiliary images, tone maps and the inputs of derived images left out, each `altr` group one page) with the other entity groups as `PageReader.groups` (`ster` left view then right view, `brst`, `tsyn`, `albc` and any other type), every Nokia collection, burst, time-synchronized, album and stereo conformance file; AV1-coded images, derived-image inputs and alpha images decoded by the AVIF decoder when `smalt-video` is present, so a file with only the generic brands whose image is AV1-coded decodes exactly as under the `avif` brand (and reports `AVIF`) | - | `smalt-mpeg` (HEIF parser and uncompressed items in `smalt-core`) | `image.heic`, `internal.heif` | spec (ISO/IEC 23008-12, MIAF ISO/IEC 23000-22, ISO/IEC 23001-17, ISO/IEC 15444-16, Apple's "Applying Apple HDR effect to your photos"); HEVC payloads through `video.hevc`, AVC payloads through `video.h264`, JPEG and JPEG 2000 payloads through `smalt-image` | **patent pools** (HEVC: Access Advance, Via LA; AVC: Via LA); not covered by the MIT license |

`Media.probe` describes every image format above from its headers alone, without the image
artifact: size, bit depth, transparency, EXIF or `irot`/`imir` orientation, and for GIF, APNG and
animated WebP the frame count, loop count and duration, for TIFF (and BigTIFF) the page count and for
icons the entry count, for animated cursors the step count, loop and duration (for JPEG XL, the image header: size, orientation, depth, alpha and whether it animates). `Images.openPages` reads the pages of a multi-page TIFF, the entries of an icon
and the master images of a HEIF file (HEIC or AVIF, each page decoded by the artifact of its coding, with the file's
stereo pairs, bursts and other entity groups as `PageReader.groups`) in any order (pages are independent images, not
timed frames); every other image is one page. `Images.openPageWriter`
writes them: one TIFF directory per page (a `DecodedImage` page keeps its orientation, color profile, EXIF and XMP),
or one icon entry per page (larger pages are scaled into a 256-pixel square). `Images.openAnimation` readers of GIF, APNG,
WebP, JPEG XL and the AVIF and HEIC image sequences can `seek(micros)`, and so can the animated cursor (ANI) reader
and the one-frame reader of every still image. `Images.openAnimationWriter` writes
animated WebP, GIF, APNG and ANI: GIF delays are hundredths of a second, APNG delays fractions of 16-bit terms
and ANI display times jiffies (sixtieths of a second), and these writers round each frame's end time, never its
duration, so the rounding does not accumulate. An animated cursor always loops forever.

Color profiles: the JPEG (`APP2 ICC_PROFILE`), PNG (`iCCP`, `cICP`, `sRGB`, `gAMA`, `cHRM`), WebP (`ICCP`), GIF
(`ICCRGBG1` application extension) and BMP (V5 embedded profile, V4 and V5 calibrated color space) decoders report the declared color space in `DecodedImage.colorProfile()` and convert to sRGB
with `ImageDecoderOptions.builder().convertToSrgb(true)` (CMYK and YCCK JPEGs through their embedded CMYK
profile); a high-dynamic-range profile (PNG `cICP` with its `mDCV` and `cLLI` metadata, or an ICC profile whose
version 4.4 `cicp` tag declares PQ or HLG, in any of these formats, TIFF, JPEG 2000, HEIC or AVIF) reports its
description in `ColorProfile.colorInfo()`. The JPEG, PNG, APNG, WebP, animated WebP, GIF, animated GIF, TIFF, JPEG 2000 and BMP encoders
embed the profile set with `colorProfile(...)` on their options, a PQ or HLG profile in its ICC form (a version 4.4
matrix/TRC profile with a `cicp` tag); the PNG and APNG encoders write a PQ or HLG profile as `cICP`, `mDCV` and
`cLLI` followed by that ICC form in `iCCP` (left out of a grayscale PNG, which an RGB profile cannot describe).

Decoders output 8-bit pictures by default, rounding 9 to 16-bit video and 16-bit PNG and TIFF; the AV1, VP9,
H.264, HEVC, ProRes, DNxHD, AVIF, HEIC, PNG, TIFF and JPEG XL decoders keep the full depth on request
(`highBitDepth(true)` on their options' builder, giving `Yuv16Picture` or `Rgba16Picture`). The AVIF and HEIC
animation readers then return `Rgba16Picture` frames for sequences deeper than 8 bits; `AnimationFrame.picture()`
is an `RgbaPicture` otherwise.

Regions: `ImageDecoderOptions.builder().region(x, y, width, height)` decodes a rectangle of the stored image, every
decoder giving exactly the crop of its whole decode (`ImageOps.crop`) with the metadata of the whole image; with a
size hint, the hint applies to the rectangle and the decoder returns the rectangle of its reduced picture. The TIFF,
JPEG, JPEG 2000, WebP, HEIC and AVIF decoders decode only what the rectangle needs, and their limits bound what they
decode rather than the image, so a region of a tiled slide or satellite image larger than `MediaLimits.maxPixels`
decodes: the strips or tiles of a TIFF page (and of the reduced-resolution level a hint selects) that it intersects,
read alone from the source; the tiles of a JPEG 2000 codestream that hold its samples and, in them, the code-blocks
those samples depend on, a raw codestream or a file of one codestream reading only those tiles' data
(`Jp2DecoderOptions.region` decodes the channels of an area, as `opj_decompress -d` does); the grid images of a HEIC
or AVIF grid that it intersects, with their alpha images; the MCUs of a JPEG frame from its first row to the last
row it needs, those outside it entropy-decoded without being transformed, and the restart intervals it does not
meet skipped altogether; and the rows of a WebP image down to its last one. The other decoders decode the whole
image and crop it, and so does `Images` for the providers of other libraries (`ImageCodecProvider.decodesRegions`).
The ImageIO reader of `smalt-desktop` decodes the source region of a read that does not subsample.

What packed RGBA and planar Y'CbCr cannot hold, CMYK and multi-ink separations, extra channels and
floating-point samples, is converted to RGBA by default and kept on request with
`ImageDecoderOptions.builder().keepChannels(true)`: the JPEG (CMYK, YCCK), TIFF (separated pages of any ink set,
floating-point pages, extra samples), JPEG XL (CMYK, floating-point, extra channels) and JPEG 2000 (CMYK) decoders
then return a
`ChannelPicture`, a `Channel8Picture`, `Channel16Picture` or `ChannelFloatPicture` whose `PixelLayout` names
each channel (gray, RGB, the CMYK inks or named inks, then alpha, spot, depth or unspecified extras), with the
embedded profile as its color profile. `ImageOps.toRgba` and `toRgba16` convert one without color management,
giving exactly the default decoder output for CMYK JPEGs with an Adobe segment and for the TIFF pages
`TiffDecoder` lists; `ImageOps.toSrgb(DecodedImage)` and `convertColor` convert it through its profile (a CMYK
JPEG, or a CMYK TIFF without alpha, exactly as `convertToSrgb` does); the TIFF encoder writes it back sample for
sample, and the JPEG 2000 encoder a CMYK one with its alpha.

### Camera raw

`smalt-raw` (JPMS `com.github.auties00.smalt.raw`, package `image.raw`, entry point `RawDecoder`) decodes
DNG and the vendor raw formats. Detection: `ImageFormat.DNG` (a TIFF whose first directory holds
`DNGVersion`) and `ImageFormat.RAW` (every other raw format; `RawInfo.format()` names it).
`ImageFormat.detect(byte[], int)` tells the TIFF-structured raw files from TIFF only when their
directories lie within the bytes it is given; `Images.detect(MediaSource)` follows them through the
source. Decoding renders like a raw converter (sensor data, opcode lists, linearization, Menon or
bilinear demosaicing, white balance, camera matrix to the output space, exposure and tone curve) and
returns 8-bit sRGB by default; `RawDecoderOptions` selects 16-bit, Display P3, Adobe RGB, ProPhoto or
linear sRGB and Rec. 2020 output, the white balance, highlight handling, exposure and tone curve, and for DNG
files the camera profile, the enhanced image, the user crop and an automatic black subtraction. A size
hint is met from the smallest embedded preview that is large enough, else from the smallest rendering that is: a
reduced-resolution linear raw image a DNG file stores, a half-size rendering or the full one.
`RawDecoder.decodeRaw` returns the linearized CFA samples (three samples per pixel for linear raw data and
Sigma X3F files, two per photosite for the sensors that read each photosite twice, which the rendering merges) with
the camera's color description and the layout of the photosites.

| Format | Sensor data decoded | Not decoded | Source | Patents |
|---|---|---|---|---|
| DNG 1.0-1.7 | CFA (any repeat up to 16x16: Bayer, X-Trans, others with RGB filters; four-color cyan, yellow, green and magenta or red, green, blue and emerald 2x2 arrays; every CFALayout: rectangular and the staggered layouts 2 to 9 of the Fujifilm SuperCCD and others, demosaiced at the photosites' physical places onto the stored grid; two samples per pixel, as Adobe DNG Converter stores the Fujifilm sensors of two images: merged like the RAF files' readings, or, in a rectangular layout scaled up by about the square root of 2, interleaved as the two photosites of diagonal pairs), linear raw, monochrome; uncompressed 1-32-bit integer and 16/24/32-bit float, interleaved or separately stored planes (PlanarConfiguration 2), lossless JPEG (tiles, interleaved components; also JPEG-LS codestreams in its place, without subsampling), Deflate with the integer and floating-point predictors (DNG 1.4), lossy JPEG (DNG 1.4), JPEG XL (DNG 1.7, through `image.jxl`); SubTileBlockSize, RowInterleaveFactor and ColumnInterleaveFactor (DNG 1.7 JPEG XL files store a CFA as interleaved subimages); linearization table, black level pattern and row/column deltas, white levels, active area (the CFA pattern starts at its corner), default crop and scale, DefaultUserCrop (`RawDecoderOptions.userCrop`); opcode lists 1-3 (GainMap, WarpRectilinear, WarpRectilinear2, WarpFisheye, FixVignetteRadial, FixBadPixelsConstant/List, TrimBounds, MapTable, MapPolynomial, Delta/ScalePerRow/Column); the enhanced image (NewSubFileType 16, `RawDecoderOptions.enhancedImage`); size hints met from JPEG and JPEG XL previews (one tile or several) and from the reduced-resolution linear raw previews of fast load data (RawToPreviewGain); ColorMatrix, CameraCalibration (kept only when CameraCalibrationSignature matches the profile's ProfileCalibrationSignature), AnalogBalance, ForwardMatrix with two or three illuminants (DNG 1.6, IlluminantData as xy or spectrum), AsShotNeutral/AsShotWhiteXY, BaselineExposure and BaselineExposureOffset, ProfileHueSatMap, ProfileLookTable, ProfileToneCurve, ProfileGainTableMap and ProfileGainTableMap2 (every gain encoding), RGBTables over semantic masks (weighted sum and sequential, every transfer curve and set of primaries, gamut extension), DefaultBlackRender (honored by the automatic black subtraction of `RawDecoderOptions.autoBlack`), ExtraCameraProfiles and AsShotProfileName (`RawInfo.cameraProfiles`, `RawDecoderOptions.cameraProfile`); four-color files take four multipliers, a twelve-value matrix override and blended highlights, and `decodeRaw` returns the samples of one without color matrices | the ProfileDynamicRange of high-dynamic-range camera profiles (their tables apply as standard ones); semantic masks serve only the RGB tables; JPEG XL previews are not returned by `extractPreview` | Adobe DNG Specification 1.7.1; ITU-T T.81 annex H; Wyman, Sloan and Shirley (2013) for the CIE 1931 color matching functions of spectral IlluminantData | DNG patent license (royalty-free; notice in the artifact's notices) |
| Canon CR2 | lossless JPEG with slices; SensorInfo crop, ColorData white balance, black and white levels; mRAW and sRAW (subsampled lossless JPEG, YCbCr to RGB as Adobe DNG Converter does it, ColorData versions 3, 6, 7, 9 to 13 and 15) | sRAW and mRAW of ColorData versions no sample has shown | Laurent Clevy's CR2 write-up (facts only), ExifTool tag documentation; sRAW and mRAW derived from CC0 samples against Adobe DNG Converter (see below) | none known |
| Canon CR3 | the CRX codec, as every CC0 sample (29 model names) codes it: lossless (the four color planes coded directly, in one or two tiles) and lossy C-RAW (the subbands of a three-level 5/3 wavelet transform of each plane, quantized with one step per subband in codec version 1 and through a coded quantization map in version 2, with the tiles' overlapping coefficients), both coded in raster order with an adaptive Golomb-Rice code and a run mode; the image area and crop of the `IAD1` box; white balance, black and white levels from the color data of the maker note the timed metadata track carries; the main image of dual pixel files; metadata, EXIF and the PRVW and full-size JPEGs | the frames of raw burst rolls (CRX encoding type 3), wavelet depths other than three and quantized third-level subbands in codec version 2 (rejected as unsupported; no sample has them), and the second photodiode image of dual pixel files | Laurent Clevy's CR3 write-up (box and header layout, facts only), Canon's patent application US 2016/0323602 A1 (the three-level wavelet and the Golomb-Rice parameter update); the CRX coding derived clean-room from CC0 samples against Adobe DNG Converter (see below) | Canon's US 2016/0323602 A1 describes the wavelet coding |
| Canon CRW | the CIFF files of the EOS D30, D60, 10D and 300D (Digital Rebel) and the PowerShot G1, G5 and G6: Canon's lossless compression (blocks of 64 photosites coded with the JPEG-style DC and AC Huffman codes of the file's table set 0, 1 or 2, each photosite predicted from the one two columns to its left) with the two low bits of the 12-bit files; image area, black levels measured on the masked area, white levels, white balance of the shot's setting (estimated from the preview when the file has none), ISO, exposure, aperture, focal length, lens range, capture time and rotation; the large JPEG preview and the thumbnail | Huffman codes no sample has used (rejected as unsupported), files without a decoder table and other table sets; rendering the green, magenta, yellow and cyan PowerShot G1 without a color matrix (it has none; it renders with a twelve-value `colorMatrix`, and `decodeRaw` returns its samples); the headerless CHDK dumps also named `.CRW` are not CRW files | Canon's CIFF specification 1.0 revision 4, ExifTool tag documentation (record and field names); the compression, its Huffman tables, the low-bit layout, the levels and the shot fields derived clean-room from CC0 samples against Adobe DNG Converter (see below) | none known |
| Nikon NEF/NRW | lossless and lossy Huffman compression, lossy files that switch tables at a split row, the linearization curves (versions 0x44 0x10, 0x20 and 0x40), uncompressed 16-bit words and 12-bit packing (rows interlaced in the E-series Coolpix files); white balance (tag 0x000C, or the Coolpix block 0x0014), black levels | HE and HE*; Huffman codes no sample has used (rejected as unsupported); rendering the four-color E5700 without a color matrix (it has none; it renders with a twelve-value `colorMatrix`) | layout from the ExifTool documentation and Bill Claff's notes; Huffman tables and the Coolpix layout derived clean-room from CC0 samples against Adobe DNG Converter (see below) | none known |
| Sony ARW | cRAW (ARW2, with the tone curve), uncompressed 16-bit words; centered crop from EXIF | white balance and levels of the encrypted SR2 directory (white balance is estimated from the embedded preview; black 512, white 16383) | Henry Dietz's description; block layout of the MIT `arw` Go package | none known |
| Pentax PEF | Huffman compression with the table of maker note tag 0x220 (13 or 15 codes); uncompressed 16-bit words, 12-bit packed (PackBits-labeled) data; the K-3 Mark III Monochrome | files without the table (no CC0 sample lacks it) | derived from the files' own tables and samples against Adobe DNG Converter | none known |
| Fujifilm RAF | uncompressed Bayer and X-Trans (16-bit words; 12-bit packed least significant bit first, or most significant bit first in 32-bit words as the S1 and SL1000 store them), the Bayer array as record 0x130 states it (BGGR in the X10, X-S1, XF1, HS50EXR, F900EXR and HS10, whose record lists the array in the opposite order; every photosite and filter color equals Adobe DNG Converter's on the F550EXR, F600EXR, F770EXR, F900EXR, HS10, HS20EXR, X10, S1 and SL1000 samples, the F-series EXR compacts among them); lossless and lossy compressed RAF, Bayer and X-Trans, of 12 to 16 bits (a JPEG-LS-style context coder over six-row groups of vertical strips, near-lossless in four activity classes when lossy, decoded strip by strip in parallel; every photosite equals Adobe DNG Converter's on the lossless X-T2, X-T4, X-E5, GFX 50S, GFX50S II and 16-bit GFX100S samples and the lossy X-T4, X-E5, GFX50S II and 16-bit GFX100 II ones); the bare sensor words of the older models without a sensor directory: SuperCCD sensors (S2Pro, S3Pro, S5Pro, S5000, S5200, S5600, S6000fd, S6500fd, S7000, S9500, S9600, S100FS and the F700, E550 and E900 compacts, every photosite equal to Adobe DNG Converter's; the F710 and F810, of which no file is published, through copies of the F700 and E550 samples named after them, which the converter's entries of those models read to the same photosites with the siblings' color matrices while refusing the swapped copies; and by the same rules the other SuperCCD models whose files carry the same records) as staggered photosites (DNG CFALayout 2, 2x4 filter array, scaled to square pixels, turned upright when stored transposed or turned), the SuperCCD sensor of eight outputs of the DBP for GX680 (stored as eight vertical stripes, one after the other, as its header states; Adobe DNG Converter does not read it, and its rendering matches the camera's own preview), the two images of the F700 stored row by row in turn and the Bayer S5500 and S200EXR; crop, black levels (equal to Adobe DNG Converter's on every sample), the white level of the older models' single images where their clipped highlights pile up (the converter's on the samples whose highlights clip), white balance; the second image of the sensors of two images per photosite (at the header offset 128, every photosite equal to Adobe DNG Converter's on the S3Pro, S5Pro, F700, S200EXR, F550EXR, F600EXR, F770EXR, F900EXR, HS20EXR and X10 samples): the low-sensitivity photodiodes of SuperCCD SR sensors (S3Pro, S5Pro, F700) and the second exposure (DR mode, S200EXR, F600EXR) or second reading (SN mode, F770EXR, X10) of EXR sensors become the second sample of each photosite (`RawImage.channels()` 2), which the rendering merges: the primary sample up to 70% of its saturation, the other one, brought to its scale by per-color ratios measured on the image, from 90%, so the highlights the primary sample clips render (with a negative `exposure`) as the converter's demosaiced files show them, or the mean of two readings of the same exposure (on the F770EXR the merged values keep each reading's level and about half its noise; the converter's own demosaiced SN files keep too little of the photosites' detail to compare); in EXR HR files (F550EXR, X10, F900EXR, HS20EXR and the SuperCCD EXR S200EXR: record 0x0112 states an output of twice the image's area) the two photosites of each diagonal pair interleave into one staggered grid of twice the width, as the converter's demosaiced files place them (through the lens correction it applies to the X10's and F900EXR's; the S200EXR's second image lies up and left of its first, the EXR CMOS models' down and right), every photosite equal to the converter's on an S200EXR HR sample | the F710 and F810 are checked on renamed F700 and E550 files against the converter's entries of those models, as no file of them is published (the F200EXR, the other SuperCCD EXR compact, records JPEG only); white levels the file does not record and its data does not show: files without clipped highlights, the two-image files and the sensor-directory files keep the bit depth's largest value, where the converter takes lower ones from its camera tables (3900 for the HS10 and the EXR compacts, 4000 for the X10, 16250 for the S200EXR, 15564 for older models whose highlights do not clip) | ExifTool documentation; the compressed coding and the older layouts derived from CC0 samples (and CC BY-NC-SA ones of the F700, E550, E900, S5600, HS10, F600EXR, F900EXR and HS20EXR of raw.pixls.us and of an S200EXR in HR mode and an X10 in SN mode of rawsamples.ch, used for testing only) against Adobe DNG Converter, with ITU-T T.87's context modeling | none known |
| Olympus ORF | Olympus compression (12-bit prediction residuals in an adaptive Rice code, every compressed model); uncompressed 12-bit packed (including the 10-samples-per-16-bytes layout of high-resolution shots and the strip-spanning, even-rows-first layout of the compacts), 16-bit and left-aligned 10 and 12-bit words; white balance, black levels, embedded camera matrix, crop (also from the old `OLYMP` maker note); preview from the maker note | none known | ExifTool documentation (tag names); compression and layouts derived clean-room from CC0 samples against Adobe DNG Converter (see below) | none known |
| Minolta MRW | uncompressed 12-bit samples packed two in three bytes or one in each 16-bit word (Dynax, Maxxum and Alpha 7D and 5D, Alpha Sweet Digital, DiMAGE A2, DiMAGE 5); centered crop, white balance, white levels, ISO from the shot settings when the EXIF block lacks it; the maker note preview, its damaged first byte repaired and the letterbox of the 3:2 bodies cut off when rendering from it | the green-first Bayer pattern of the DiMAGE A200, other storage methods and bit depths, the MRW structure of Sony cameras (no CC0 sample shows them) | ExifTool tag documentation (block and tag names); layouts, crop, levels and preview derived clean-room from CC0 samples against Adobe DNG Converter (see below) | none known |
| Sigma X3F | the three layers of a Foveon sensor, returned as linear raw data: the TRUE coding (each layer's Huffman-coded differences, predicted from the photosite two columns to the left) of the DP1 to DP3 Merrill compacts, the SD15 and the SD1 Merrill, the Quattro variants of the dp0 to dp3 Quattro, sd Quattro and sd Quattro H (bottom and middle layers at half the top layer's resolution, brought to it by adding their bicubically upsampled differences from the binned top layer to the full-resolution top layer; the binned layout too), the older Huffman coding of the SD14 and the Polaroid x530 (signed 16-bit running sums per layer and row) and its variant stored without Huffman codes; the camera metadata block (type 2, 4 and 5 obfuscation): active area, black levels measured on the dark shield rows (a Quattro top layer at its own resolution), white levels, the as-shot white balance gains and color correction matrix (or, for the SD14, the illuminant and correction matrices) of the setting the block or the header names, the ISO gain; the bad pixel lists and autofocus photosites, interpolated over, and the lens shading (the spatial gain tables of the white balance setting, or of Merrill and Quattro cameras interpolated between the tables of the nearest apertures and focus distances), applied as a `GainMap` when `RawDecoderOptions.opcodes()` is set; camera, shot and orientation from the preview's EXIF block and the property list; the JPEG previews (formats 18 and 25) and the 8-bit RGB thumbnails (uncompressed, format 3, and Huffman-coded, format 11, reported with `ImageFormat.RAW`) | the sd Quattro H's high-resolution-mode lens shading tables (`SpatialGainHP`, which the reference library leaves unused too); Huffman-coded thumbnails stored with a row size, which the specification excludes | Sigma's published FOVb format specification 1.2 (container, property list, previews); the metadata block, the codings, the levels, the corrections, the Quattro expansion and the color conversion ported from the BSD-licensed X3F tools library; checked against that library built from source and against Adobe DNG Converter (see below) | none known |
| Panasonic RW2, Leica RWL | Panasonic raw coding versions 4 (14 12-bit samples per 128-bit block: two values and four groups of a code and three 8-bit fields, with missing-photosite markers), 5 (nine 14-bit samples per 128-bit block), 6 (11 samples per block) and 8 (tiled Huffman-coded differences with the file's own table), with the photosites the camera zeroes or marks missing repaired; crop, CFA, black and white levels, white balance | other versions, version 8 files whose gain and offset tags are not the identity | derived clean-room from CC0 samples against Adobe DNG Converter (see below) | none known |

Color: DNG files carry their camera matrices. Of the vendor formats ORF embeds a usable camera matrix, and
X3F the gains and the matrix to sRGB of each white balance setting. For 52 camera models (Canon, Nikon, Sony, Fujifilm and others) `CameraMatrices` holds matrices for
illuminants A and D65 derived from the measured spectral sensitivities of rawtoaces-data (Apache-2.0): for
each illuminant, the white-preserving matrix that best predicts its 190 training reflectances in CIELAB
(mean error 2 to 3 dE on the training set), recomputed by a test from the pinned archive. Rendered
against the cameras' own JPEG previews, 20 CC0 Nikon files of nine of those models came out closer in
chroma than with the generic matrix (mean distance 5.9 against 7.5; Adobe DNG Converter's matrices,
measured the same way only for comparison, give 5.4). Other files are rendered with a generic matrix (the
median of seven CC0 camera DNG matrices), so their colors are approximate; `RawDecoderOptions.Builder.colorMatrix`
accepts the model's DNG `ColorMatrix` (for example from an Adobe DNG Converter file).

Provenance of the vendor codings. The NEF Huffman tables, the Olympus ORF compression and layouts, the
Panasonic RW2 versions 4, 5, 6 and 8, the Pentax PEF tables, Canon's sRAW and mRAW, Canon's CRW compression
and its tables, Canon's CRX coding of CR3 files, the layout of the Nikon E-series Coolpix files and the Minolta
MRW layouts, crop and levels were derived clean-room, without reading any other decoder: from CC0 sample files of the raw.pixls.us
archive and the sensor values Adobe DNG Converter, run as a black box writing uncompressed DNG files, stores
for them, read back with Smalt's own DNG reader. Every claimed file decodes to the converter's values
photosite for photosite (121 NEF files of 60 models, 63 ORF models, 112 RW2 and RWL models, 29 PEF models,
21 sRAW and mRAW files of 15 Canon models, the 8 CC0 MRW files of seven model names, whose crop, levels and
white balance also equal the converter's, and the 11 CC0 CRW files of eight model names, whose image area,
white levels and white balance also equal the converter's and whose black levels are within one unit of it;
the converter leaves the low bits out of the photosites after a 12-bit CRW file's last whole group of 256; and
the 41 CC0 CR3 files of 29 model names, lossless and C-RAW of both codec versions, in one and two tiles, a dual
pixel file among them, whose image area and white balance also equal the converter's, whose black levels are
within one unit of it and whose white levels equal it except where the converter writes a full-range level of
its own).
Codes no sample has used raise `MediaException.Unsupported` instead of being guessed. The test sources of
`smalt-raw` re-run every derivation (`NikonTableDerivation`, `OlympusDerivation`, `PanasonicDerivation`,
`CrwTableDerivation`, `CrxDerivationTest`) and check the decoders against the converter (`RawOracleTest`,
`RawOracleCoverageTest`, `CrwTablesTest`, `Cr3OracleTest`); `tools/testdata/nef-oracle.py` and `raw-oracle.py` write the
oracle files they need on Windows, and the tests skip without them.

Provenance of the Sigma X3F decoder. The container, the property list and the previews follow Sigma's published
FOVb specification 1.2; the camera metadata block, the TRUE, Quattro and older codings, the dark shield, white
level, bad pixel, lens shading, Quattro expansion and color conversion entries are ported from the X3F tools
library of Roland and Erik Karlsson (BSD 3-Clause, commit `e8b48ca`). Adobe DNG Converter 18.0 converts the CC0
DP1, SD14 and SD15 samples but refuses the Merrill and Quattro ones; for X3F it stores denoised,
color-converted values, not the layers, so it checks the image area (its linear DNG covers exactly the
`ActiveImageArea`), the linear relation of the layers to its values (an affine fit of 16x16 block means leaves
under 0.5% of the variance) and the rendering. The X3F tools library, built from source and run as a reference,
checks the rest: the decoded layers of the six CC0 samples (DP1, SD14, SD15, SD1 Merrill, DP2 Merrill and the
dp2 Quattro's three layers of two sizes) and the dp2 Quattro's type 5 metadata block are bit-identical to its
own, so are the layers after its bad pixel interpolation on all six, its lens shading tables (the white balance
setting's table, or the Merrill and Quattro tables interpolated in aperture and lens position) match to the
precision of the 32-bit gains, and the sRGB rendering with the bad pixels and the lens shading corrected
matches its own (its Quattro expansion run with OpenCV 4.10, which it calls for the resize) within one 8-bit
level on average over 36x24 cells of the active area, leaving out the cells that hold nearly saturated
photosites, which that library renders without highlight handling. The codings no CC0 sample shows (the
uncompressed variant of the older coding, the Polaroid x530's, the Huffman-coded thumbnail) are checked on
synthetic files, which that library decodes to the same values (the thumbnail's running sums wrap at 8 bits as
the specification says, where that library's signed 16-bit sums read as 0 past 32767). The black level is taken
from the dark shield rows only, where that library also averages the column ranges (its reference renderings
use the shield rows too): on the Merrill samples the right column range reads about twice the level of the
other shields, and the shield rows reproduce the balance of the camera's own preview. `X3fParserTest` and
`X3fReferenceTest` pin the digests and the cell means, and `RawOracleTest` the comparisons with the converter.

`Media.probe` describes DNG and raw files from their headers (and from the raw decoder when present) and
fills the tags `make`, `model`, `lens`, `iso`, `exposure_time`, `f_number`, `focal_length` and
`creation_time`. The desktop ImageIO reader serves the names `dng` and `raw`.

### Video

| Format | Decode | Encode | Artifact | Package | Source | Patents |
|---|---|---|---|---|---|---|
| H.264 / AVC | 4:2:0 and 4:0:0 up to High profile and the Extended profile (SP, switching SP and SI slices, slice data partitions A, B and C reassembled across packets and interleaved slices), High 10 (with High 10 Intra), High 4:2:2 (with High 4:2:2 Intra), High 4:4:4 Predictive and its Intra and CAVLC 4:4:4 Intra profiles: 4:0:0, 4:2:0, 4:2:2 and 4:4:4 (color planes coded together or separately, GBR included) at 8 to 14 bits, luma and chroma depths independent (rounded to 8-bit on output unless `highBitDepth` is set), lossless macroblocks (transform bypass with its intra prediction), CAVLC and CABAC, progressive and interlaced: field pictures, MBAFF frames and both mixed, fields woven into frames; multiview streams (Multiview High and Stereo High: inter-view prediction, two to eight views, the base view by default and any set of views or all of them through `H264DecoderOptions.outputViews` and `allViews`, each frame naming its view in `VideoFrame.sideData().layer()`; MFC High frame-compatible stereo, with the full-resolution left and right views of ITU-T H.264 G.8.6 through `enhancedResolution`; Multiview Depth High and MFC Depth High, with the depth views as monochrome frames through `depth`); scalable streams (Scalable Baseline, Scalable High and Scalable High Intra: coarse-grain and medium-grain quality layers, dyadic and extended spatial layers with cropping and chroma phases, inter-layer prediction, key pictures with reference base pictures, progressive and interlaced layers with field pictures and MBAFF frames, the base layer by default and any layer through `H264DecoderOptions.targetLayer` or `highestLayer`, 4:2:0 8-bit layers); multiview and scalable MP4 tracks through `mvcC`/`svcC`, layered tracks and aggregators; temporal layers dropped through `maxTemporalId`; alpha auxiliary coded pictures attached as the alpha plane (`H264DecoderOptions.alpha`); film grain characteristics SEI rendered bit-exactly as SMPTE RDD 5 specifies (`H264DecoderOptions.filmGrain`), picture timing `pic_struct`, frame packing arrangement and display orientation reported in `VideoFrame.sideData`, alternative transfer characteristics in the color description; AVC items and image sequences of HEIF files through `HeicDecoder` | Constrained Baseline, Main, High, High 10, High 4:2:2 and High 4:4:4 Predictive: 4:0:0, 4:2:0, 4:2:2 and 4:4:4 at 8 to 14 bits (`H264EncoderOptions.chromaFormat`, `bitDepth`), lossless (`lossless`, transform bypass with its intra prediction), CABAC or CAVLC (`cavlc`), progressive frames, field pictures and MBAFF frames (`fieldOrder`, `fieldCoding`); slices (`slices`) and a slice size limit (`maxSliceBytes`), periodic intra refresh with recovery point SEI (`intraRefresh`), up to four temporal layers with prefix NAL units (`temporalLayers`), the sample aspect ratio, chroma sample location and fixed frame rate in the VUI and the HDR metadata SEI of the color description; RGB input converted with any matrix, YCgCo, BT.2020 constant luminance and identity (4:4:4) included | `smalt-mpeg` | `video.h264`, `video.h264.encoder` | spec (ITU-T H.264); tables from OpenH264 and the JM reference software; CABAC encoder engine from the HM reference software | **patent pool** (Via LA AVC/H.264); not covered by the MIT license |
| HEVC / H.265 | Main, Main 10, Main Still Picture, the range extension profiles (Main 12, Main 4:2:2 10/12, Main 4:4:4 8/10/12, Main 4:4:4 16 Intra, Monochrome 8/12/16, High Throughput 4:4:4 16 Intra and their Intra and Still Picture variants) and the screen content coding profiles (Screen-Extended Main, Main 10, Main 4:4:4, Main 4:4:4 10, High Throughput 4:4:4, 4:4:4 10 and 4:4:4 14): 4:0:0, 4:2:0, 4:2:2 and 4:4:4 at 8 to 16 bits, 4:4:4 also coded as separate color planes (`separate_colour_plane_flag`) (rounded to 8-bit on output unless `highBitDepth` is set), every Main tool (tiles, wavefronts, dependent slices, scaling lists, PCM, transform skip, lossless, weighted prediction, long-term references, CRA/BLA/RASL), every range extension tool (cross-component prediction, implicit and explicit residual DPCM, transform skip rotation, contexts and large blocks, extended precision, persistent Rice adaptation, CABAC bypass alignment, chroma QP offset lists, SAO offset scaling, disabled intra smoothing, high precision weighted prediction offsets), every screen content coding tool (palette mode with SPS and PPS predictor initializers, intra block copy through current picture referencing, adaptive color transform with PPS and slice QP offsets, adaptive motion vector resolution, disabled intra boundary filtering), mastering display, content light level, alternative transfer characteristics and color description; film grain characteristics SEI rendered as SMPTE RDD 5 specifies (`HevcDecoderOptions.filmGrain`); picture timing, frame packing arrangement and display orientation reported in `VideoFrame.sideData`; field coded sequences (`field_seq_flag`) woven into frames by the picture timing `pic_struct` (parity and pairing); random access at IRAP pictures and at recovery point SEI messages (gradual decoding refresh included, missing references generated); output cropped by the conformance window, and also by the VUI default display window with `HevcDecoderOptions.defaultDisplayWindow`; the multi-layer extensions (Annexes F to H): MV-HEVC views (Multiview Main) and SHVC enhancement layers (Scalable Main, Main 10, Monochrome and the scalable range extension profiles) with spatial scalability at any ratio (reference region and scaled reference layer offsets, resampling phases), bit depth and color gamut scalability (the colour mapping table), inter-layer sample and motion prediction, POC resetting and additional layer sets, decoding the layers of the output layer set `HevcDecoderOptions.outputLayerSet` selects (the base layer by default, the last set with `lastOutputLayerSet`), output in layer order within each access unit with the layer and view in `VideoFrame.sideData().layer()`; alpha auxiliary layers (AuxId 1 with the alpha channel information SEI, as Apple's HEVC with alpha in MOV and MP4) attached to their primary pictures as the alpha plane (`HevcDecoderOptions.alpha`), depth and other auxiliary layers output as layers of their own; single HEIC images through `HeicDecoder`; the 3D-HEVC extension of a base layer raises `MediaException.Unsupported` | - | `smalt-mpeg` | `video.hevc` | spec (ITU-T H.265); CABAC decoder engine from the HM reference software, palette mode from HM-SCM | **patent pools** (Access Advance, Via LA HEVC); not covered by the MIT license |
| MPEG-4 Part 2 (Visual) | Simple and Advanced Simple profiles: I/P/B/S-VOPs in display order, quarter sample, global motion compensation (1-3 warping points), interlaced (field DCT, field prediction, field direct mode), H.263 and MPEG quantization with custom matrices, AC/DC prediction, video packets, data partitioning and reversible VLCs, not-coded VOPs and packed bitstreams (DivX/Xvid in AVI), short video header; FourCCs XVID, DIVX, DX50, FMP4 and their aliases; raw `.m4v` through `Mpeg4ElementaryStream`; the tools of the Core, Main, N-bit, Scalable, Advanced Real Time Simple, Advanced Coding Efficiency and Advanced Core profiles: binary and grayscale shape (alpha planes, multiple auxiliary components), shape only layers, the shape-adaptive DCT, static sprites (basic and low latency, brightness change), N-bit samples (4 to 12 bits, `Yuv16Picture` with `highBitDepth`), NEWPRED, reduced resolution VOPs, spatial and temporal scalability of two tracks (`Mpeg4Decoder(base, enhancement, options)`), with the partial enhancement and background composition of `enhancement_type` 1; Studio profiles and fine granularity scalability raise `MediaException.Unsupported` | - | `smalt-mpeg` | `video.mpeg4` | spec (ISO/IEC 14496-2); inverse DCT from libjpeg-turbo (`jidctint`); the object tools ported from the ISO/IEC 14496-5 reference decoder (Microsoft) | **patent pool** (Via LA MPEG-4 Visual); most listed patents have expired, check the current list |
| H.263, H.263+, H.263++ | baseline and every optional coding mode: C (continuous presence multipoint, sub-bitstreams as `VideoLayer` views), D (unrestricted vectors, reversible VLC), E (syntax-based arithmetic coding), F (advanced prediction with overlapped compensation), G (PB-frames), I (advanced intra coding), J (deblocking filter), K (slices, arbitrary order and rectangular), M (improved PB-frames), N (reference picture selection, video redundancy coding), O (B-, EI- and EP-pictures: temporal, SNR and spatial scalability, the highest layer of each instant), P (reference picture resampling, implicit and warped), Q (reduced-resolution update), R (independent segment decoding), S (alternative inter VLC), T (modified quantization), U (enhanced reference picture selection, two-picture backward prediction) and V (data partitioned slices; corrupt coefficient data keeps the header and vector partitions), custom picture formats and clock frequencies; 3GP `s263`, Matroska `V_MS/VFW/FOURCC` (`H263`), raw `.263` through `Mpeg4ElementaryStream` | - | `smalt-mpeg` | `video.h263` (decoder shared with `video.mpeg4`) | spec (ITU-T H.263 01/2005) | none in force known (standard of 1996-2000) |
| VP8 | yes (also the core of lossy WebP); transparent WebM video (`AlphaMode` 1, the alpha frames of `BlockAdditional` 1) into pictures with an alpha plane, bit-exact with libvpx | real-time (one-pass constant bitrate, libvpx speeds 4-16); good quality (`Vp8EncoderOptions.ofBitrate`, `ofQuality`, `forArchive`: lookahead of up to 25 frames, temporally filtered hidden alternate references in packets of their own flagged `PacketFlag.DISCARD`, rate-distortion mode decision with split motion vectors at speeds 0-3, trellis quantization at speed 0, variable bitrate, constrained quality and constant quality, one pass or two passes with `Vp8FirstPass`, libvpx good speeds 0-4); best quality (`Vp8EncoderUsage.BEST_QUALITY`, libvpx `--best`: the good-quality coding with the lowest mode thresholds and the full split search, exhaustive for poorly predicted partitions); transparent video (`Vp8EncoderOptions.alpha`, `VideoEncoderOptions.alpha`, `VideoWriterOptions.alpha`): the alpha planes as a second VP8 stream at a constant quantizer (`alphaQuality`), in the packets' `PacketSideData.alphaFrame`, with key frames wherever the main stream has them, as WebM stores it | `smalt-image` | `video.vp8` | libvpx, libwebp | Google's royalty-free VP8 grant (`PATENTS` in libvpx) |
| Motion-JPEG | gray and YCbCr frames, DCT or lossless (SOF3 and SOF11, the RGB frames of ffmpeg's `ljpeg` included), as the JPEG decoder reads them (`MjpegDecoder`, also for camera buffers); interlaced frames of two field images (OpenDML `AVI1`, QuickTime `mjpa`) woven in the track's field order, single-field frames of interlaced tracks doubled; QuickTime Motion-JPEG format B (`mjpb`); frames above 8 bits rounded to 8 bits unless `VideoDecoderOptions.highBitDepth` | baseline frames (`MjpegEncoder`, also through `VideoEncoders`), each the file `JpegEncoder` writes: optimized or Annex K Huffman tables, the AVI convention without DHT segments, restart markers, interlaced frames as two field images with `AVI1` segments, constant quality or rate control | `smalt-image` | `image.jpeg` | libjpeg-turbo and the IJG software; Apple QuickTime File Format (`mjpa`, `mjpb`); OpenDML AVI File Format Extensions 1.02 | none in force |
| Motion JPEG 2000 | every JPEG 2000 image the JP2 decoder reads, as a JP2 file, a raw codestream or the Contiguous Codestream boxes of an ISO/IEC 15444-3 sample (`Mj2Decoder`, also through `VideoDecoders`), under a `jp2h` box among the frame's boxes or else the track's (from the `mjp2` sample entry) when the frame is no JP2 file; gray and sYCC frames as planar Y'CbCr of their stored subsampling (4:0:0, 4:4:4, 4:2:2, 4:2:0) without color conversion, others (RGB, alpha, palettes, the other color spaces) as RGBA; above 8 bits rounded to 8 unless `highBitDepth`; interlaced frames of two field codestreams woven in the track's field order; MJ2, MP4 and QuickTime `mjp2` (and the `mp4v` object type 0x6E), Matroska `V_JPEG2000` (and `V_MS/VFW/FOURCC`, `V_QUICKTIME` `mjp2`), AVI `MJ2C`, `mjp2`, `LJ2C`, `LJ2K`, `IPJ2`, `AVj2` | `Mj2Encoder` (also through `VideoEncoders`) over `Jp2Encoder`: gray, 4:2:0, 4:2:2 or 4:4:4 Y'CbCr declared sYCC, or RGB and RGBA through the component transform, 8 to 16 bits, lossless or lossy with every `Jp2EncoderOptions` setting (HTJ2K and the cinema profiles included), JP2 file or codestream frames, interlaced frames as two field codestreams, a per-frame size cap from a target bitrate; the track configuration is the frames' `jp2h` box | `smalt-image` | `image.jp2` | OpenJPEG 2.5.3 (codec); ISO/IEC 15444-3 file format as OpenJPEG 1.5's `mj2` tools write it; the Matroska codec specification (`V_JPEG2000`) | as JPEG 2000 |
| VP9 | profiles 0-3, 8/10/12-bit (rounded to 8-bit on output unless `VideoDecoderOptions.builder().highBitDepth(true)`), 4:2:0, 4:2:2, 4:4:4 and 4:4:0 (`ChromaFormat.YUV440`); transparent WebM video (`AlphaMode` 1, the alpha frames of `BlockAdditional` 1) into pictures with an alpha plane, rescaled to the frame's bit depth, bit-exact with libvpx | profiles 0-3: 4:2:0, 4:2:2, 4:4:0 and 4:4:4 at 8, 10 and 12 bits, with the color space and range of `Vp9EncoderOptions.color` signaled (BT.601, BT.709, SMPTE 170M/240M, BT.2020, sRGB 4:4:4, full or limited range); real-time (one-pass constant bitrate, libvpx speeds 5-9); good quality (`Vp9EncoderOptions.ofBitrate`, `ofQuality`, `forArchive`: lookahead of up to 25 frames, temporally filtered hidden alternate references in superframes, compound prediction, rate-distortion partition and mode search down to 4x4 blocks, trellis quantization in two passes, variable bitrate, constrained quality and constant quality, one pass or two passes with `Vp9FirstPass`, libvpx good speeds 0-4); best quality (`Vp9EncoderUsage.BEST_QUALITY`, libvpx `--best`: the good-quality coding with the fullest partition, mode, transform, filter and motion search, every block's rate-distortion estimate taken after trellis quantization); transparent video (`Vp9EncoderOptions.alpha`, `VideoEncoderOptions.alpha`, `VideoWriterOptions.alpha`): the alpha planes as a second 8-bit 4:2:0 VP9 stream at a constant quantizer (`alphaQuality`), in the packets' `PacketSideData.alphaFrame`, with key frames wherever the main stream has them, as WebM stores it | `smalt-video` | `video.vp9` | libvpx | Google's royalty-free VP8/VP9 grant (`PATENTS` in libvpx) |
| AV1 | main, high and professional, 8/10/12-bit (rounded to 8-bit on output unless `Av1DecoderOptions.builder().highBitDepth(true)`), film grain; scalable streams at any operating point (`operatingPoint`), the highest spatial layer or every layer (`allLayers`); low-overhead OBUs or Annex B length-delimited temporal units (`annexB`); large scale tile decoding of tile list OBUs from anchor frames (`Av1Decoder.setAnchorFrames`) | real-time (one-pass, low delay, speeds 7-10); good quality (`Av1EncoderOptions.ofQuality`, `ofBitrate`, `forArchive`: lookahead, alternate reference pyramids of up to 16 frames, temporal filtering, compound prediction, constant quality or variable bitrate, two passes over first-pass statistics (`Av1FirstPass`, `firstPassStatistics`), libaom good speeds 2-6); intra-only at constant quality (`Av1EncoderOptions.forIntraOnly`); every usage 8/10/12-bit, 4:2:0 to 4:4:4 and monochrome (`pictureFormat`), main, high and professional profiles; film grain signaled from a libaom grain table (`filmGrain`) or estimated from a denoised source (`denoise`); screen content tools (`screenContent`: palette blocks in every usage, whole-sample motion in good quality); super-resolution of the predicted frames (`superResolution`); real-time size changes within the sequence header's size without a key frame, predicting from scaled references; timing information, constant frame rate or the decoder model with per-frame removal and presentation times (`timingInfo`) | `smalt-video` | `video.av1` | dav1d (decoder), libaom (encoder) | Alliance for Open Media Patent License 1.0 (royalty-free) |
| Uncompressed video (`Codec.RAW_VIDEO`) | the `RawPixelFormat` layouts: planar YUV 4:2:0, 4:2:2, 4:4:4, 4:4:0 and 4:1:1 (to 4:2:2) at 8 to 16 bits with or without alpha, gray, NV12/NV21, packed UYVY/YUYV/YVYU, v210, 8 and 16-bit RGB in every byte order, 5-6-5 and 5-5-5 RGB, palettes of 1, 2, 4 and 8-bit indices; any row alignment, top-down or bottom-up (`RawVideoDecoder`) | the same layouts but palettes, from any picture (`RawVideoEncoder`; `VideoEncoders` writes 8-bit 4:2:0) | `smalt-core` | `video` | spec | none |
| Apple ProRes | every flavor (422 Proxy, LT, 422, HQ, 4444, 4444 XQ; QuickTime `apco`, `apcs`, `apcn`, `apch`, `ap4h`, `ap4x` and Matroska `V_PRORES`), any frame size, progressive and interlaced (fields woven), 8 and 16-bit alpha; 4:2:2 decodes to 10-bit and 4:4:4 to 12-bit `Yuv16Picture`s by default (`ProResDecoderOptions.builder().highBitDepth(false)` computes 8-bit pictures directly; the registry follows `VideoDecoderOptions.highBitDepth`), slices in parallel; samples within one code value of ffmpeg's decode (RDD 36 leaves the IDCT implementation-defined within its Annex A bounds, which the decoder's transform meets) | every flavor with Apple's quantization matrices and target data rates (scaled to the frame size and rate, or a set bitrate or fixed quantizer), alpha for 4444, interlaced, any picture representation | `smalt-video` | `video.prores`, `video.intra.internal` | spec (SMPTE RDD 36); the encoder's quantization matrices are the ones Apple's encoders signal in their frame headers | **Apple licenses ProRes implementations** (see [Licensing of ProRes and DNxHD](#licensing-of-prores-and-dnxhd)); not covered by the MIT license |
| Avid DNxHD / DNxHR (VC-3) | every compression ID of SMPTE ST 2019-1: DNxHD 1235-1260 (1920x1080, 1440x1080, 1280x720 and 960x720, progressive, field-encoded interlaced and the adaptive field/frame macroblocks of 1260; 8 and 10-bit, the 4:4:4 RGB ID 1256) and DNxHR 1270-1274 (LB, SQ, HQ, HQX, 444; any size; 4:2:0, 4:2:2, 4:4:4 Y'CbCr and RGB; 8, 10 and 12-bit); QuickTime `AVdn`/`AVdh`, Matroska `V_QUICKTIME` (and `V_MS/VFW/FOURCC`); macroblock rows in parallel; 4:4:4 frames with the color format flag set decode to full-range RGBA, Y'CbCr-coded macroblocks converted with BT.709 (see [Licensing of ProRes and DNxHD](#licensing-of-prores-and-dnxhd)); alpha: the in-band channel of Amendment 1:2023 (DCT-coded on every ID that permits it, lossless run-length on 1256 and 1270, premultiplied video (`PMA`) divided back to straight alpha) and the out-of-band alpha of Avid's QuickTime encoders (raw 8-bit or a second coding unit) | every compression ID: DNxHR LB, SQ, HQ (8-bit 4:2:2 or 4:2:0), HQX (10/12-bit 4:2:2 or 4:2:0), 444 (10/12-bit Y'CbCr or RGB; RGB coded as BT.709 Y'CbCr macroblocks by default, `alternateColorSpace`) at any size, DNxHD picked from the raster, scan, depth and bitrate (or named), ID 1260 with adaptive field/frame macroblocks; in-band alpha (`alpha`, lossless on 1256 and 1270 with `losslessAlpha`, premultiplied with `premultipliedAlpha`) or Avid's out-of-band alpha on every ID (`alphaStorage`: raw 8-bit samples or a second coding unit, declared by an `AALP` atom in the QuickTime or Matroska sample description); constant frame size | `smalt-video` | `video.dnxhd`, `video.intra.internal` | spec (SMPTE ST 2019-1:2016 and Amendment 1:2023, tables of Annexes C to E) | **Avid licenses DNxHD** (see [Licensing of ProRes and DNxHD](#licensing-of-prores-and-dnxhd)); not covered by the MIT license |

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

### Audio

| Format | Decode | Encode | Artifact | Package | Source | Patents |
|---|---|---|---|---|---|---|
| Opus (SILK, CELT, hybrid) | yes, every channel mapping family: mono and stereo, surround up to 7.1, ambisonics (family 2, and family 3 demixed through the header's matrix), reported as the ambisonic layout of RFC 8486 (`ChannelLayout.ofAmbisonics`, ACN order, SN3D, with the non-diegetic stereo pair), discrete and reserved multistreams; per-packet decoding of any of them with loss concealment and in-band FEC for real time; downmix of any stream to mono or stereo (RFC 7845 and RFC 8486 matrices) | yes, 1 to 255 channels: mono and stereo, surround up to 7.1 (Vorbis channel order, LFE stream, surround masking), ambisonics coded channel by channel (family 2, chosen by default for an ambisonic input layout) or through the projection matrices of orders 1 to 5 (family 3), and discrete multistreams; buffered for files or one packet per call with live controls for real time | `smalt-audio` | `audio.opus` | libopus 1.5.2 (float build); KISS FFT as modified in libopus; opusfile 0.12 (stereo downmix matrices) | royalty-free Opus licenses (in libopus `COPYING`) |
| AAC (Main, LC, SSR, LTP, Scalable, ER LC, ER LTP, ER Scalable, ER BSAC, LD, ELD), HE-AAC, HE-AAC v2, MPEG Surround, USAC | yes: LC; Main with backward-adaptive prediction; SSR (four-band PQF filterbank with gain control); LTP and ER AAC LTP with long term prediction; ER AAC LC; the error resilience tools of the ER object types (virtual codebooks, RVLC scalefactors, Huffman codeword reordering), with corrupt RVLC and reordered spectral data concealed, and `epConfig` 0 and 1 (separate category instances through `AacDecoder.decode(List, Consumer)`); AAC-LD (512- and 480-sample frames, low overlap window, long term prediction); AAC-ELD (low delay filterbank, `ELDSpecificConfig`, low delay SBR at dual rate and downsampled, from 512- and 480-sample frames); HE-AAC with spectral band replication in the high quality mode (dual-rate and downsampled, 1024- and 960-sample frames, explicit, backward-compatible and implicit signaling); HE-AAC v2 with full parametric stereo (10, 20 and 34 stereo bands with the 34-band hybrid analysis, IPD/OPD phase synthesis, mixing procedures A and B); dynamic range control from `dynamic_range_info` fill elements (per-band cut and boost scales, program reference level normalization to a target level) and the DVB heavy compression of ETSI TS 101 154; every channel configuration, 22.2 (configuration 13) included, and program config elements; ER BSAC with every layer up to `AacDecoderOptions.maxLayer()`, the multichannel extension elements and its SBR payloads, from one access unit or the elementary streams of its layers through `AacDecoder.decode(List, Consumer)`; scalable AAC and ER scalable AAC (mono, stereo, and mono under stereo layers joined by the frequency selective switch, with long term prediction, M/S and intensity stereo, noise substitution, TNS and the error resilience tools of each layer, `epConfig` 0 and 1), every layer up to `AacDecoderOptions.maxLayer()` from the tracks of its layers through `AacDecoder(List, AacDecoderOptions)` and `AacDecoder.decode(List, Consumer)`; MPEG Surround (ISO/IEC 23003-1, object type 30: every tree from 5-1-5 to 7-5-7, residual coding, arbitrary downmix gains and residuals, temporal shaping and guided envelope shaping, embedded in fill elements or in a separate elementary stream through `AacDecoder(List, AacDecoderOptions)`, `AacDecoderOptions.mpegSurround()`); USAC (MPEG-D, ISO/IEC 23003-3) with the frequency domain coder (time-warped MDCT included) and the linear prediction domain (ACELP, TCX) coder, complex prediction stereo, eSBR (QMF transposer, PVC, inter-TES), MPEG Surround 2-1-2, AudioPreRoll and MPEG-D DRC (ISO/IEC 23003-4: DRC set selection by effect and target loudness, gain decoding with interpolation, multiband filter banks, shape filters, parametric DRC, equalizer, loudness normalization and the peak limiter, `AacDecoderOptions.drcEffect()`) | AAC-LC (CBR and VBR): every channel configuration (1 to 7 and 11 to 14, 22.2 included) and, through a program config element, the other layouts of front, side, back and LFE loudspeakers, channels in any input order; 1024- and 960-sample frames | `smalt-mpeg` | `audio.aac` | decoder: spec (ISO/IEC 14496-3, ISO/IEC 13818-7 for AAC Main prediction, ETSI TS 101 154 for heavy compression); SBR and PS: spec, with tables and the baseline PS structure from the PacketVideo AAC decoder (Apache-2.0); HCR, RVLC, the low delay tables, the ELD window and the low delay SBR prototype from libxaac (Apache-2.0); USAC, its eSBR, MPEG Surround (ISO/IEC 23003-1 and 2-1-2), ER BSAC and scalable AAC: the ISO/IEC 23003-3 reference software, the USAC LPD coder from libxaac (Apache-2.0); MPEG-D DRC: the ISO/IEC 23003-4 reference software; encoder: VisualOn AAC encoder (3GPP TS 26.403, Apache-2.0) | **patent pool** (Via LA AAC); not covered by the MIT license |
| MP3, MP2 | yes: MPEG-1, MPEG-2 and MPEG-2.5 Layers I, II and III; CRC-16 checked, failing or corrupt-flagged frames concealed; the ISO/IEC 13818-3 multichannel extension of Layer II (`3/0` to `3/2` with LFE, extension frames, dynamic crosstalk, prediction, phantom center, every dematrixing procedure) | MP3: MPEG-1, MPEG-2 and MPEG-2.5 Layer III (8 to 48 kHz, mono and stereo), constant, variable (quality 0-9) and average bitrate, joint (mid/side) stereo, bit reservoir, CRC; no intensity stereo. MP2: MPEG-1 and MPEG-2 Layer II and Layer I (16 to 48 kHz, mono, stereo, joint (intensity) stereo and dual channel), every bitrate of the layer, CRC; psychoacoustic model 1 | `smalt-audio` | `audio.mp3` | decoder: minimp3, the multichannel extension and CRC from the ISO/IEC 13818-5 reference software (dist10); MP3 encoder: spec (ISO/IEC 11172-3, 13818-3) and psychoacoustic literature, analysis window from the ISO dist10 tables; MP2 encoder: the ISO/IEC 13818-5 reference software (dist10) | MP3 patents expired (2017) |
| Vorbis | yes | quality -1 to 10 (or a nominal bitrate mapped to a quality), 1 to 255 channels, 1 Hz to 200 kHz; bitrate management: an average bitrate and hard minimum and maximum bitrates, alone or with a quality or nominal bitrate, held through a bit reservoir (size, bias and average damping settable); packets byte-identical to libvorbis 1.3.7 in every mode, except that a packet libvorbis truncates to nothing under a very low maximum keeps one byte | `smalt-audio` | `audio.vorbis` | spec; libvorbis, stb_vorbis (decoder); libvorbis 1.3.7 with aoTuV tuning (encoder) | none |
| AC-3 (Dolby Digital) | yes: every channel mode with LFE, coupling, rematrixing, delta bit allocation, block switching, the half and quarter rate streams (`bsid` 9 and 10), the Annex D alternate bit stream syntax; dynamic range control (off, `dynrng` with cut and boost scales, `compr` heavy compression), dialogue normalization, mono, Lo/Ro and Lt/Rt downmix with the stream's mix levels; frames failing their CRC are concealed | yes: every channel mode with LFE, 32, 44.1 and 48 kHz, 32 to 640 kbit/s constant bit rate, block switching, channel coupling with phase flags below 80 kbit/s per channel, rematrixing, exponent strategies chosen by cost over each frame, bit allocation searched to fill each frame; a faster search for real time (`forRealtime()`); the metadata of `Ac3Metadata` (type of service, center and surround mix levels, Dolby Surround mode, production information, copyright and original flags, the Annex D alternate syntax with the preferred downmix, the Lt/Rt and Lo/Ro levels, the Dolby Surround EX and Dolby Headphone modes and the converter type) and dynamic range words (`dynrng`, `compr`) computed from Dolby's film, music and speech profiles | `smalt-mpeg` | `audio.ac3` | spec (ATSC A/52, ETSI TS 102 366) | patents expired (2017) |
| E-AC-3 (Dolby Digital Plus) | yes: independent and dependent substreams (7.1 and other custom channel maps, AC-3 cores extended by an E-AC-3 dependent substream), 1 to 6 blocks per frame, reduced sampling rates, spectral extension, enhanced coupling, adaptive hybrid transform with gain-adaptive quantization, transient pre-noise processing; the Dolby Atmos (JOC) object audio is reported and its channel bed decoded | yes: one independent substream for mono to 5.1, and every program beyond 5.1 whose channels have custom channel map locations (7.0, 7.1, 6.1, 5.1.2, 7.1.2, 7.1.4, 9.1, 11.1 and the like) as a 5.1 core plus dependent substreams with custom channel maps, one whenever it holds the extra channels; 32, 44.1 and 48 kHz with 1, 2, 3 or 6 blocks per frame and 16, 22.05 and 24 kHz with 6, any constant bit rate up to frames of 2048 words per substream (1024 kbit/s at 48 kHz with 6 blocks, 6144 kbit/s with 1), the AC-3 tools plus frame-based exponent strategies and spectral extension (from 44 to 92 kbit/s per channel); the adaptive hybrid transform, transient pre-noise processing (frames held until the frames their data concerns are coded) and enhanced coupling on request; the metadata of `Ac3Metadata`, with the mixing and informational metadata of the independent substream, and dynamic range words | `smalt-mpeg` | `audio.ac3` | spec (ATSC A/52 Annex E, ETSI TS 102 366, ETSI TS 103 420) | **may be covered by Dolby patents**; not covered by the MIT license |
| FLAC | yes: 4 to 32-bit samples, also as their integers bit for bit (`decodeIntegers`, `SampleFormat.INTEGER`) or exact doubles; the `STREAMINFO` MD5 signature checked on request (`AudioDecoderOptions.verifyIntegrity`) | yes: libFLAC's compression levels 0-8, 4 to 32-bit integer (planar, interleaved or `IntegerAudioBuffer` blocks of any sample size) or float input, every stereo mode, MD5 and seek table | `smalt-audio` | `audio.flac` | libFLAC | none |
| ALAC (Apple Lossless) | yes: 16/20/24/32-bit, 1-8 channels, from MP4 and Matroska, also as their integers bit for bit (32-bit streams of Apple's reference encoder included) or exact doubles | yes: 16, 20, 24 and 32-bit integer or float input, 1-8 channels, adaptive stereo matrixing and predictor search (or Apple's fast mode), escaped and partial frames, any frame length from 16 to 65536; packets byte-identical to Apple's reference encoder | `smalt-audio` | `audio.alac` | Apple's ALAC (Apache-2.0) | none (royalty-free) |
| PCM (integer and float, any layout) | yes; integer formats up to 32 bits and G.711 also as their integers bit for bit, every format as doubles (64-bit floats unchanged) | 8 to 64-bit, integer and float, either byte order, G.711 A-law and mu-law, through the containers that carry it, from float, integer or double samples; triangular, high-pass triangular or noise-shaped dither for 8, 16 and 24-bit output (`PcmEncoderOptions.dither`) | `smalt-core` | `audio.pcm` | spec; Lipshitz, Vanderkooy and Wannamaker (dither, E-weighted noise shaping) | none |
| AMR-NB, AMR-WB | yes, bit-exact with the 3GPP fixed-point decoders (TS 26.073, TS 26.173): every mode, mode changes on any frame, comfort noise (SID) and DTX, bad and lost frame concealment, homing; storage-format frames from `.amr`, MP4/3GP `samr`/`sawb` and Matroska (`A_AMR/NB`, `A_AMR/WB`, `A_MS/ACM`), and the frame-blocks of multichannel `.amr` files (one decoder state per channel); the TS 26.074 sequences pass in linear PCM and through G.711 A-law and mu-law | yes, bit-exact with the 3GPP encoders: AMR-NB in every mode with DTX and VAD option 1 or 2, AMR-WB in every mode with VAD and DTX; mode changes on any frame | `smalt-mpeg` | `audio.amr` | AMR-NB decoder and encoder and AMR-WB decoder: opencore-amr (PacketVideo, Apache-2.0); AMR-WB encoder: vo-amrwbenc (VisualOn, Apache-2.0), its arithmetic aligned with TS 26.173 (passes TS 26.174, so its frames differ from `libvo_amrwbenc` on loud or unusual input); both derived from the 3GPP reference code | **patent pool** (VoiceAge, AMR-WB); AMR-NB patents are held by its developers; not covered by the MIT license |
| IMA ADPCM (WAVE `0x0011` blocks, Apple `ima4`) | yes: 4-bit, any channel count, from WAV, AVI, Matroska `A_MS/ACM` and QuickTime (`ms\0\x11`), and `ima4` from AIFF-C and QuickTime (`ima4`, classic and per-packet tables); bit-exact with Microsoft's `imaadp32.acm` (the recommendation's shift-and-add quantizer) and with ffmpeg's `adpcm_ima_qt`; also as 16-bit integers or doubles | yes: both layouts, 1-8 channels, any rate, WAVE blocks of any valid size and at most 65535 samples (256 bytes per channel per 11.025 kHz by default), each sample coded to the nearest code; the WAVE blocks byte-identical to Microsoft's `imaadp32.acm` | `smalt-audio` | `audio.adpcm` | spec (IMA Digital Audio Focus recommendation, Microsoft RIFF, Apple TN1081) | none |
| Microsoft ADPCM (WAVE `0x0002`) | yes: 4-bit, any channel count and coefficient table, from WAV, AVI, Matroska `A_MS/ACM` and QuickTime (`ms\0\x02`); bit-exact with Microsoft's `msadp32.acm`, including its 32-bit arithmetic on streams whose deltas overflow 16 bits; also as 16-bit integers or doubles | yes: 1-8 channels, any rate and valid block size of at most 65535 samples, the coefficient pair and initial delta of each channel of each block chosen for the smallest squared error (about 10 dB more accurate than `msadp32.acm` on speech) | `smalt-audio` | `audio.adpcm` | spec (Microsoft RIFF) | none |
| G.722 | yes: 64, 56 and 48 kbit/s modes, from WAV, AVI and Matroska `A_MS/ACM`; sub-band decoders bit-exact with the ITU-T G.722 Appendix II test sequences | yes: 64 kbit/s, 16 kHz mono; sub-band encoders bit-exact with the Appendix II sequences | `smalt-audio` | `audio.g722` | spec (ITU-T G.722) | none |
| G.726 | yes: 16, 24, 32 and 40 kbit/s (2 to 5-bit codes, MSB-first packing), from WAV, AVI and Matroska `A_MS/ACM`, the uniform PCM output of Annex A; bit-exact with the ITU-T G.726 Appendix II test sequences | yes: every code size, any rate, bit-exact with the Appendix II sequences | `smalt-audio` | `audio.g726` | spec (ITU-T G.726 and its Annex A) | none |
| GSM 06.10 (full rate) | yes: RFC 3551 33-byte frames (AIFF-C `GSM `, QuickTime `agsm`) and Microsoft's 65-byte blocks (WAV and AVI `0x0031`, Matroska `A_MS/ACM`); bit-exact with the ETSI EN 300 961 test sequences, codec homing included, and with libgsm | yes: both layouts, bit-exact with the EN 300 961 sequences and byte-identical to libgsm | `smalt-audio` | `audio.gsm` | libgsm 1.0.22 | none |
| MACE 3:1, 6:1 | no (see Known limits); AIFF-C and QuickTime `MAC3`/`MAC6` tracks are read and written | no | - | - | - | none |

Every decoder in the table also stands in for lost packets through the codec-neutral
`AudioDecoder.conceal(samples, output)`, and the Opus, AAC, MP3 (constant and average bitrate) and AMR encoders
change their bitrate while running through `AudioEncoder.setTargetBitrate` (see [Code any audio codec in real
time](#code-any-audio-codec-in-real-time)).

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
  extension band. Dolby's decoder holds its output back until a correction's synthesis buffer is complete, so it
  also corrects the last samples of a frame whose data concerns a transient early in the next frame; Smalt
  outputs each frame as it decodes it and leaves those samples (a few frames per minute of those streams) as
  decoded.

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

| Container | Read | Seek | Probe | Write | Package | Source |
|---|---|---|---|---|---|---|
| MP4, MOV, 3GP, M4A, MJ2 | yes, including fragmented files and Motion JPEG 2000 files (the signature box ahead of an `mjp2` brand); QuickTime compressed movies (`cmov`, zlib) and the compressed boxes of ISO/IEC 14496-12 (`!mov`, `!mof`, `!six`, `!ssx`); tracks whose samples are in other files (reference movies: `url `, `urn ` and `alis` data references, opened through `DemuxerOptions.dataReferences`, such as `DataReferenceResolver.directory`); a layered HEVC track's `lhvC` record joins the `hvcC` record in its codec configuration (and the muxer writes it back); scalable and multiview H.264 tracks (`svcC`/`mvcC` merged into the track configuration, `svc1`/`mvc1`/`mvc3` tracks and `scal` layered tracks rebuilt with their extractors and the parameter sets of the tracks they reference); Common Encryption (`cenc`, `cbc1`, `cens`, `cbcs` and PIFF) decrypted with the keys of `DemuxerOptions.decryptionKeys` (see below) | exact, from the sample tables and fragment runs (sync samples, edit lists) | `ilst`, `udta` and `keys` tags, `covr` cover art, the track `udta` names, DASH role `kind` boxes and `tkhd` enabled flags as track names and dispositions, `chpl` and QuickTime chapter tracks, track matrix rotation, `pasp` sample aspect ratio, QuickTime `fiel` field order, Spherical Video V2 `st3d` stereo mode and `sv3d` spherical projection (equirectangular, cubemap, mesh, with the pose), the Spatial Audio `SA3D` ambisonic layout (ACN, SN3D, head-locked stereo, any channel map), the QuickTime `tmcd` timecode track as `timecode()`, frame and byte counts | MP4/M4A muxer (index first, fast start) of every codec the demuxer reads but Vorbis: H.264, HEVC, VP8 and VP9 (`vp08`/`vp09`, `vpcC` derived from the first key frame when the track has none), AV1, MPEG-4 Part 2 (`mp4v`, `esds` from the track or the headers of the first frame), H.263 (`s263` with `d263`), Motion-JPEG (`mp4v` with the JPEG object type), Motion JPEG 2000 (`mjp2` with the `jp2h` box of the track, the first frame or its main header), AAC, Opus, MP3, MP2, ALAC, FLAC, AMR as `samr`/`sawb` with a `damr` mode set, AC-3 and E-AC-3 as `ac-3`/`ec-3` with `dac3`/`dec3` built from the first access unit (Atmos extension included), 16/24/32-bit integer and 32/64-bit float PCM as `ipcm`/`fpcm` with `pcmC` and `chnl` (ISO/IEC 23003-5; MP4 only, M4A carries no PCM), `tx3g` and `wvtt` text, TTML as `stpp`; `pasp`, `fiel`, `st3d` and `sv3d` for every video codec when the track declares them; `SA3D` for every audio codec whose track has an ambisonic layout of every component of its order in SN3D (Opus mapping family 3 keeps the RFC 8486 demixing matrix after the stream counts of `dOps`, see Known limits); a QuickTime `tmcd` timecode track, which the video tracks reference, from a `timecode` tag (`Mp4Muxer`, and `FragmentedMp4Muxer` with the sample in the first fragment); QuickTime MOV (`Mp4MuxerOptions.quickTime()`), adding ProRes, DNxHD, `jpeg`/`mjpa`/`mjpb` Motion-JPEG, `h263`, and every PCM format, 8-bit, A-law and mu-law included, as `twos`/`sowt`/`raw `/`in24`/`in32`/`fl32`/`fl64`/`alaw`/`ulaw` sound descriptions or version 2 `lpcm` with a `chan` layout, and `ima4`, GSM 06.10 (`agsm`) and MACE (`MAC3`, `MAC6`) as version 1 sound descriptions; Motion JPEG 2000 files (`Mp4MuxerOptions.mj2()`: signature box, `mjp2` brand, frames stored as their `jp2c` boxes, `mjp2` entries with `jp2h`, `fiel`, `jsub` and `orfo`) of Motion JPEG 2000 video and compressed audio; lossless remux to MP4, M4A and MOV from MP4, Matroska, MPEG-TS, AVI and Ogg (several audio tracks, text and TTML subtitles; protected MP4 inputs decrypted with `RemuxOptions.decryptionKeys`); fragmented MP4 and CMAF (one file, or init and media segments) of the same codecs, with ProRes and DNxHD, and a hierarchical `sidx` past 65535 fragments, encrypted with Common Encryption on request (`FragmentedMp4MuxerOptions.encryption`); `colr` with the HDR10 `mdcv` and `clli` boxes | `container.mp4` | spec (ISO/IEC 14496-12, -14, -15) |
| Matroska, WebM | yes; `ContentEncodings` of frames and codec private data undone: header stripping, zlib, bzlib (bzip2) and LZO1X compression, and the AES-CTR frame encryption of WebM (whole and partitioned frames) with the keys of `DemuxerOptions.decryptionKeys`; ordered editions (`EditionFlagOrdered`: the chapters in their order, nested chapters, `ChapterFlagEnabled`), linked segments (`ChapterSegmentUUID` spans and `ChapterSegmentEditionUID` editions) and hard-linked chains (`PrevUUID`, `NextUUID`) played as their virtual timeline (`DemuxerOptions.virtualTimeline` and `edition`; the other files through `DemuxerOptions.dataReferences`, such as `MatroskaDemuxer.open` or `DataReferenceResolver.directory`, and in a transcode `TranscodeOptions.dataReferences`, the input's directory for `Transcoder.transcode(Path, ...)`); an `A_MS/ACM` frame of several IMA or Microsoft ADPCM blocks read as one packet per block | `Cues`, or a scan of the cluster headers when there are none (live WebM); on a virtual timeline, within the part that plays the time | `Tags` (file and track), the track `Name` and flag elements as track names and dispositions, `Chapters` (every edition that is not hidden, with nested chapters and the titles of each language; `chapters()` is the played edition, on the timeline of an ordered one), image attachments as pictures and the other attachments (fonts) as `attachments()`, a `TIMECODE` tag as `timecode()`, `StereoMode`, `Projection` (the spherical projection and pose, or the rotation of a rectangular one), `DisplayWidth`/`DisplayHeight` (every known `DisplayUnit`, after `PixelCrop`) as the sample aspect ratio, `FlagInterlaced` and `FieldOrder`, `AlphaMode` (`VideoTrack.hasAlpha`) with the alpha frames of `BlockAdditional` 1 as `PacketSideData.alphaFrame` | Matroska and WebM, including live WebM; in Matroska also MPEG-4 Part 2, Motion-JPEG, Motion JPEG 2000 (`V_JPEG2000`, a frame relying on the track's `jp2h` box written as a JP2 file, or with a copy of it ahead of its two field boxes), H.263 (`V_MS/VFW/FOURCC`), ProRes, Theora (`V_THEORA`), DNxHD, ALAC, AMR, G.711, IMA and Microsoft ADPCM, GSM 06.10, G.722, G.726 and 64-bit integer PCM (`A_MS/ACM`, the ADPCM and telephony formats under the `WAVEFORMATEX` a WAVE file stores, one block per packet), signed 8-bit, big-endian float and big-endian 64-bit integer PCM (stored unsigned and little-endian) and data tracks whose handler is a Matroska codec id; the final FLAC `STREAMINFO` and ALAC `ALACSpecificConfig` rewritten in place on a seekable sink; the display size (in pixels when the stretched width is whole, else as a display aspect ratio) and the field order the track declares; `Colour` with chroma siting, `MaxCLL`/`MaxFALL` and `MasteringMetadata`; HDR10+ messages as `BlockAdditions` (ITU-T T.35 `BlockAdditionMapping`); `AlphaMode` 1 and the alpha frames of a transparent track as `BlockAdditional` 1; every chapter edition (default and ordered flags, names, nested chapters, `ChapLanguage` and `ChapLanguageBCP47` titles) and the attachments of the tags; `StereoMode` and the spherical `Projection` the track declares | `container.matroska` | spec |
| Ogg (Opus, Vorbis, FLAC, Speex, Theora) | yes, chained files included (`OggDemuxer.links()`: start, duration, tracks, tags, chapters and pictures of every link; links above 64 MiB found by bisection on serial numbers); Speex and Theora described and timestamped, not decoded; Ogg Skeleton 3 and 4 `fishead` and `fisbone` (message fields as track tags, `Language`, granule rate and shift of unknown streams) | bisection on granule positions in the link that plays at the target (Theora: then to the key frame its granule position names); only forward, by reading, on a stream read once | Vorbis comments per track and per link, `CHAPTERxxx`, `METADATA_BLOCK_PICTURE`, Ogg FLAC `PICTURE`; size, rate and pixel aspect ratio of Theora streams | yes (Opus, Vorbis, FLAC, Speex, Theora; the final FLAC `STREAMINFO` rewritten in place on a seekable sink) | `container.ogg` | spec; libogg (page reader) |
| MPEG audio (MP3, MP2, ID3v2) | yes | exact through a frame index up to 64 MiB; Xing/VBRI table or bitrate estimate beyond | ID3v2 text frames, `APIC`, `CHAP` (appended ID3v2.4 tags included); APEv1/APEv2 text and cover art; ID3v1 | raw MP3/MP2 through `RawMuxer` (Xing or Info frame with frame count, size, seek table and a LAME tag with the encoder delay and padding on a seekable output) | `container.mpeg` | spec; minimp3 (free-format frame sizing) |
| ADTS | yes; implicit HE-AAC found in the first frame and declared on the track | exact through a frame index up to 64 MiB; bitrate estimate beyond | ID3v2 (appended ID3v2.4 included), APE, ID3v1 | yes | `container.adts` | spec |
| WAV, AIFF | yes: integer PCM of 8 to 32 bits and of 64 bits, 32 and 64-bit float, A-law and mu-law (AIFF and AIFF-C, WAV with `WAVE_FORMAT_EXTENSIBLE` channel masks), AIFF-C `ima4`, `GSM `, `MAC3` and `MAC6` as packets of whole frames (the `COMM` count counting frames), Ambisonic B-format WAV (the Furse-Malham layouts of `Ambisonics.ofFurseMalham`, also written), and WAV of coded audio: MPEG audio, AC-3 and E-AC-3 (tag `0x2000` and the DirectShow sub-formats), AAC in ADTS or LOAS framing or unframed (raw data blocks, split by walking their syntax, also after an ADIF header), FLAC, AMR-NB and AMR-WB, Opus in the self-delimiting framing of RFC 6716, DTS frames (tag `0x2001`, tag `0x0008` when its data holds frames rather than bursts, and the 16-bit or 14-bit DTS frames of a DTS audio CD in 16-bit stereo PCM, as `Codec.DTS` packets, DTS-HD extension substreams included), the ADPCM and telephony formats as packets of `Codec.ADPCM_IMA_WAV` (IMA ADPCM `0x0011`), `Codec.ADPCM_MS` (Microsoft ADPCM `0x0002`, its coefficient table as the configuration), `Codec.GSM_MS` (GSM 06.10 `0x0031` in 65-byte blocks), `Codec.ADPCM_G722` (`0x028F`) and `Codec.ADPCM_G726` (`0x0045`, codes of 2 to 5 bits), a block or whole codes per packet, cut at the `fact` count, and IEC 61937 (S/PDIF) bursts in 16-bit stereo PCM or the 8 channels of the high bit rate formats, declared or detected: AC-3, E-AC-3, MPEG-1 and MPEG-2 audio, MPEG-2 AAC (ADTS) and MPEG-4 AAC (LOAS), DTS types I to IV (DTS-HD) and Dolby TrueHD in MAT frames (`Codec.TRUEHD`, one packet per access unit) | exact, sample frame arithmetic; coded audio through its elementary stream's index or a walk of its frames (IEC 61937 bursts from their position on the IEC 61937 timeline; TrueHD from the last major sync before the target) | `LIST INFO`, AIFF text chunks, embedded ID3v2, the Broadcast Wave `bext` chunk (EBU Tech 3285), `iXML`, `axml`, `chna` (ITU-R BS.2088) and `smpl` chunks, cue points (`cue ` with `LIST adtl` labels and regions) as chapters, AIFF `MARK` markers as chapters, `INST` and `COMT`, the `fact` sample count of coded audio | WAV and RF64: PCM up to 64-bit integers (big-endian samples stored with their bytes swapped, signed 8-bit ones as unsigned), A-law, mu-law, and coded audio as the reader takes it back packet for packet: MP2 (`MPEG1WAVEFORMAT` with the Broadcast Wave `mext` chunk), MP3 (`MPEGLAYER3WAVEFORMAT`), AC-3 (`0x2000`), E-AC-3 (the `MEDIASUBTYPE_DOLBY_DDPLUS` sub-format), AAC Main, LC, SSR and LTP (alone or under SBR and PS) as ADTS frames (`0x1600`) or, when ADTS cannot express its configuration, raw data blocks after an `HEAACWAVEINFO` (`0x1610`) describing the core stream, FLAC, Opus (self-delimiting framing), AMR-NB and AMR-WB (multichannel files with the RFC 4867 multichannel magic), DTS, IMA and Microsoft ADPCM blocks (`IMAADPCMWAVEFORMAT`, `ADPCMWAVEFORMAT` with the configuration's or the standard coefficients), GSM 06.10 (`GSM610WAVEFORMAT`), G.722 and G.726, each with its `fact` sample count, the ADPCM headers as ffmpeg writes them (average byte rates as Microsoft defines them) and decoded by ffmpeg to the same samples; or, under `WavMuxerOptions.forIec61937()` or the extensible form, IEC 61937 (S/PDIF and HDMI) data bursts in 16-bit PCM (stereo, or 8 channels for the high bit rate formats) declared as PCM or by `WAVEFORMATEXTENSIBLE_IEC61937` with the codec's sub-format: AC-3, E-AC-3 (six-block groups), MPEG-1 and half-rate MPEG-2 audio, AAC as ADTS frames, DTS core frames, DTS-HD (type IV at the high bit rate) and Dolby TrueHD in MAT frames, the bursts identical to ffmpeg's `spdif` muxer; AIFF and AIFF-C, `ima4`, `GSM `, `MAC3` and `MAC6` included; the descriptive chunks and the chapters of the tags | `container.wav`, `container.aiff` | spec (Microsoft RIFF and `mmreg.h`/`ksmedia.h` format tags and sub-formats, EBU Tech 3285 Supplement 1 (MPEG audio in Broadcast Wave), EBU Tech 3306, IEC 61937-1 to -5, -9 and -11 data types, ISO/IEC 14496-3 LOAS, raw data blocks and ADIF, ETSI TS 102 114, RFC 6716 Appendix B; ffmpeg's `spdif` muxer as a black box for the MAT frame layout) |
| FLAC | yes | `SEEKTABLE`, then frame-header bisection and stepping | Vorbis comments, `PICTURE`, `CHAPTERxxx`, else the tracks of a `CUESHEET` block as chapters; ID3v2, APE and ID3v1 tags around the stream | native FLAC stream through `RawMuxer` (final `STREAMINFO` and seek table on a seekable output; chapters also as a `CUESHEET`) | `container.flac` | spec |
| AMR | yes, single-channel and multichannel (`#!AMR_MC1.0`, `#!AMR-WB_MC1.0`, 1 to 15 channels in the RFC 3551 channel order) | exact through a frame index | ID3v2, APE and ID3v1 tags around the frames | yes (raw muxer: `#!AMR` or `#!AMR-WB` magic, then the frames; the multichannel magic and channel count for a track of 2 to 15 channels, then its frame-blocks; MP4 and Matroska carry single-channel AMR only, so the transcoder re-encodes a multichannel stream for them) | `container.amr`, `container.raw` | spec (RFC 4867, RFC 3551) |
| AC-3, E-AC-3 (`.ac3`, `.ec3`) | yes: sync frames grouped into access units (an independent frame of substream 0 with its dependent substreams), resynchronization on damaged data | exact through a frame index up to 64 MiB; bitrate estimate beyond | channels with dependent substreams, bit rate, dialogue level, Dolby Atmos flag (from the first access unit); ID3v2, APE and ID3v1 tags | yes (raw muxer: the frames back to back) | `container.ac3`, `container.raw` | spec (ATSC A/52) |
| IVF | yes | key frames through a frame index | duration from the header frame count | yes | `container.ivf` | spec |
| MPEG-TS, M2TS | yes: 188, 192 and 204-byte packets, any program of the PAT; H.264, HEVC, AV1 (`AV01` registration), MPEG-4 Part 2, AAC (ADTS, and LATM with several programs and layers, chunked payloads, fixed frame lengths and program config elements), MP3, MP2, AC-3, E-AC-3, Opus, Blu-ray linear PCM and SMPTE ST 302 AES3 audio as PCM, ID3 timed metadata, SCTE 35 splice information (as a data track of whole sections, their `pts_adjustment` rebased onto the timeline), PGS, DVB subtitles and EBU teletext (see Bitmap subtitles and captions), private data; program map changes after the start (new streams, streams moved to other PIDs, as in concatenated recordings); timestamps continued across the 33-bit wrap and splices | bisection on PES presentation times, then a forward search for each track's key frame | programs, PIDs, languages; ATSC component names and ISO 639 audio types as track names and dispositions; codec configuration records built from the in-band parameter sets, with the sample aspect ratio they signal | yes: H.264, HEVC, AV1, MPEG-4 Part 2, AAC, MP3, MP2, AC-3, E-AC-3, Opus, 16/20/24-bit PCM (ST 302 or Blu-ray linear PCM), ID3, SCTE 35, PGS (with the `HDMV` registration), DVB subtitles (subtitling descriptor) and teletext (teletext descriptor, PES packets padded to whole transport packets); M2TS, constant bit rate, key-frame-aligned segments | `container.ts` | spec (ISO/IEC 13818-1, AOM Carriage of AV1 in MPEG-2 TS, SMPTE ST 302, ANSI/SCTE 35, ISO/IEC 14496-3 clause 1.7); Blu-ray linear PCM header and channel orders from ffmpeg-written samples as a black box |
| AVI | yes: RIFF AVI with the OpenDML extensions (`AVIX` forms, `indx` super indexes and `ix##` standard indexes), else the `idx1` index (relative or absolute offsets), else a walk of the `movi` lists (truncated files); `BITMAPINFOHEADER` and `WAVEFORMATEX`/`WAVEFORMATEXTENSIBLE` formats, MPEG-4 Part 2, H.264, HEVC, H.263, Motion-JPEG, Motion JPEG 2000, VP8/VP9, PCM (64-bit integers included), A-law, mu-law, MP3/MP2 (re-framed into whole frames), AAC, AC-3, E-AC-3 (the `MEDIASUBTYPE_DOLBY_DDPLUS` sub-format), DTS (tags `0x2001` and `0x0008`, identified and passed through), IMA and Microsoft ADPCM (one block per packet), GSM 06.10 (`0x0031`), G.722 (`0x028F`) and G.726 (`0x0045`) timed in samples; dropped frames (empty chunks) keep their time slots | key frames through the index | `LIST INFO` tags, `strn` stream names, `vprp` frame aspect ratio (as the sample aspect ratio) and field count, frame and byte counts | yes: OpenDML with `idx1` for the first RIFF form; a `vprp` when the track declares its sample aspect ratio or scan (16-bit frame aspect terms, the closest ratio when the exact one does not fit; the field count only, no field order); MPEG-4 Part 2, H.264 and HEVC (as Annex B), H.263, Motion-JPEG, Motion JPEG 2000 (`MJ2C`, a frame relying on the track's `jp2h` box written as a JP2 file, or with a copy of it ahead of its two field boxes), VP8, VP9, PCM, A-law, mu-law, MP3, MP2, AAC, AC-3, and IMA and Microsoft ADPCM, GSM 06.10, G.722 and G.726 under the `WAVEFORMATEX` a WAVE file stores (`dwScale` the samples of a block, `dwRate` the sampling rate, `dwSampleSize` the block alignment), which ffmpeg decodes to the samples of the WAVE source; constant frame rate with empty chunks for gaps | `container.avi` | spec (Microsoft AVI RIFF, OpenDML AVI File Format Extensions 1.02) |
| PGS (`.sup`) | yes: each display set one packet, key when it starts an epoch or acquisition point | display sets that start an epoch or acquisition point | canvas size | yes (`SupMuxer`, `OutputContainer.SUP`) | `container.subtitle` | de facto format (Blu-ray HDMV presentation graphics) |
| VobSub (`.idx` and `.sub`) | yes: `VobSubDemuxer(idx, sub, options)`, the MPEG program stream of subpicture units with one track per subpicture stream, and the index header (palette, size, time offset) and stream languages; a `.sub` alone decodes with a gray palette | the index entries | languages, canvas size | yes (`VobSubMuxer(idx, sub)`, one track) | `container.subtitle` | de facto format (DVD-Video subpictures) |
| Raw elementary streams | yes: H.264 and HEVC Annex B (`AnnexBDemuxer`: access units split as ITU-T H.264 7.4.1.2.3 and H.265 7.4.2.4.4 define them, H.264 field pairs joined, `avcC`/`hvcC` built from the parameter sets, timestamps derived from the picture order counts, each picture timed at the rate of its own sequence parameter set (VUI, else HEVC VPS, else `ElementaryStreamOptions`) for the fields its `pic_struct` displays, decoding times from the decoder's reorder depth, the pictures `no_output_of_prior_pics_flag` discards left without a presentation time), AV1 low-overhead OBUs and Annex B (`ObuDemuxer`), MPEG-4 Part 2 (`.m4v`) and H.263 (`.263`) (`Mpeg4VideoDemuxer`: timestamps from the VOP times, group of VOP time codes and H.263 temporal references and picture clock), and headerless PCM in a sample format the caller gives (`PcmDemuxer`, `PcmDemuxerOptions`) | Annex B: the key frame at or before the target, resuming from the IDR or IRAP picture that starts its run (the first seek reads the stream once); AV1: key temporal units (one walk of the unit headers); MPEG-4 Part 2 and H.263: the intra picture at or before the target (the first seek reads the stream once); PCM: to the sample | picture size, frame rate, color and sample aspect ratio from the sequence headers | H.264 and HEVC Annex B, AV1 low-overhead or Annex B (`RawMuxerOptions.av1AnnexB`), MPEG-4 Part 2, H.263 and PCM through `RawMuxer` (`ContainerFormat.H264`, `HEVC`, `AV1`, `AV1_ANNEX_B`, `MPEG4_VIDEO`, `H263` and `PCM`, and the transcoder targets `OutputContainer.H264`, `HEVC` and `AV1`) | `container.raw` | spec (ITU-T H.264 and H.265 Annex B and clause 8.2.1 / 8.3.1, AV1 specification section 5 and Annex B) |
| YUV4MPEG2 (`.y4m`) | yes: `C` colorspaces `420jpeg`, `420mpeg2`, `420paldv`, `422`, `444`, `411`, `444alpha`, `mono` and the 9 to 16-bit `420pN`, `422pN`, `444pN`, `monoN`; `F`, `A`, `I` and `XCOLORRANGE` parameters | to the frame | frame rate, sample aspect ratio, interlacing, range and chroma siting | yes (`Y4mMuxer`, transcoder target `OutputContainer.Y4M`) | `container.y4m` | YUV4MPEG2 description (mjpegtools) |
| HLS (`.m3u8`) | yes: a master playlist with every media playlist it names, or one media playlist (`HlsDemuxer.open(Path)` for one on disk; otherwise the segments, initialization sections and keys opened through `DemuxerOptions.dataReferences`, such as an application's HTTP resolver, whose sources may stream without a known length): transport stream, fragmented MP4, WebVTT (cue times moved by each `X-TIMESTAMP-MAP`, repeated cues read once) and packed audio segments (timed by their ID3 `transportStreamTimestamp`), byte ranges, gaps, the `identity` key among several `KEYFORMAT`s, `AES-128` segments, CMAF `SAMPLE-AES` and `SAMPLE-AES-CTR` with the options' keys or the key URI's, I-frame playlists read alone; the tracks of all streams on one timeline | through the demuxer of each stream | a rendition's `LANGUAGE`, `NAME` (as the `title`), `DEFAULT`, `FORCED` and accessibility `CHARACTERISTICS` | presentations, through `Packager` | `container.hls` | RFC 8216 |
| MPEG-DASH (`.mpd`) | yes: every representation of every period (`DashDemuxer`, `DashDemuxer.open(Path)`; segments opened as for HLS): `SegmentTemplate` with a `SegmentTimeline` (repeats to the next entry or the period's end included) or a segment duration (over the period, or in a dynamic manifest up to its `publishTime` and within its `timeShiftBufferDepth`), `$RepresentationID$`, `$Number$`, `$Time$`, `$Bandwidth$` and their widths, `SegmentList` with media ranges, `SegmentBase`, side files such as WebVTT and TTML subtitles; nested `BaseURL`s, period starts and presentation time offsets; adaptation sets with an `EssentialProperty` (trick play) left out | through the demuxer of each stream | `lang`, `Label` (as the `title`), roles and audio description `Accessibility` as dispositions | presentations, through `Packager` | `container.dash` | ISO/IEC 23009-1 |

QuickTime movies are written by `Mp4Muxer` with `Mp4MuxerOptions.quickTime()` (major brand `qt  `), which
also takes ProRes and DNxHD tracks with PCM audio (the deliverable of the professional intra codecs, as
`OutputContainer.MOV` writes it): their sample entry type is the track's four-character codec configuration
(`apch`, `AVdh`, ...), and the entry gets the QuickTime `colr` (`nclc`), `fiel` (the track's scan, else the scan
of the first frame) and `pasp` (the track's sample aspect ratio, else square samples) boxes, with a depth of 32
when the first frame carries alpha. `MatroskaMuxer` writes
ProRes as `V_PRORES` (frames without their size and `icpf` prefix, as Matroska stores them) and DNxHD as
`V_QUICKTIME` with a QuickTime sample description. MXF, the other home of DNxHD, is not supported.

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

The MP4 demuxer applies every segment of an edit list (ISO/IEC 14496-12 clause 8.6.6): several media edits,
empty edits between them and dwell edits (media rate 0, which hold a picture) become a presentation index whose
decode-only pre-roll packets carry `PacketFlag.DISCARD` and whose cut audio packets carry their discard start and
end, which `VideoReader`, `AudioReader` and the transcoder apply; an edit list of empty edits and one media edit,
the usual form, still shifts the timestamps alone, and so does the first media edit of a HEIF or AVIF image
sequence track (`pict`, `auxv`), whose samples are each returned once. Every `stsd` sample description is read and selected per
sample by `stsc`, `tfhd` or `trex`; a sample whose description has another codec configuration carries it as
`PacketSideData.newCodecConfig`, as do the transport stream packets where the in-band H.264 or HEVC parameter
sets or the AAC `AudioSpecificConfig` change. The readers and the transcoder then continue with a decoder of the
new configuration; `Mp4Muxer` adds a sample description, the MPEG-TS, Matroska, ADTS, AVI and raw muxers write
the change in band where the codec allows it, IVF frames describe themselves, and the other muxers reject it.
`Mp4Remuxer` copies a multi-segment edit list and every sample description as they are.

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
decodes as plain video of its color description, while one that is not (profile 5 and 10.0, IPTPQc2) raises
`MediaException.Unsupported` (feature `"dolby vision reconstruction"`), from its container configuration or,
for a single-layer HEVC stream that declares none, from its first valid RPU (the RPU parser follows the
dolby_vision crate of dovi_tool), unless `VideoDecoderOptions.dolbyVisionBaseLayer` asks for the base layer anyway.

PCM is stored one frame per sample, in a timescale of the sampling rate, as QuickTime and ISO/IEC 23003-5
readers expect; its frames follow each other from the first packet's timestamp, so a gap between packets is
not kept. A rate above 65535 Hz leaves the 16.16 sampling rate field of an ISO entry at zero (readers then take
the media timescale), since a version 1 entry with an `srat` box is read as a QuickTime sound description by
ffmpeg. The `chnl` box writes the back pair as the rear surround positions (8, 9), the side pair as the side
surround positions (13, 14) and every other speaker at its ISO/IEC 23091-3 position (0 to 29, the 22.2 layout
included); a layout with an unknown speaker gets no `chnl`. The QuickTime `chan` box has no label for the
surround direct pair, whose labels the side pair uses, so it is written with the unknown label.

`Containers.openDemuxer` hands out every demuxer above; the MPEG-TS demuxer also takes `TsDemuxerOptions` (program,
probe window) when constructed directly, and lists every program of a multi-program capture. Raw PCM has no header
to recognize, so it is read with a `PcmDemuxer` given its sample format, rate and layout.

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

### Streaming

`smalt-core` package `container.packaging` packages encodes for adaptive streaming without re-encoding:
`Packager.pack` takes one or more renditions (any file the containers above read) and writes, through a
`PackageOutput` (a directory or any sink factory), the segments, media playlists and manifests;
`Packager.open` returns a `PackageWriter` that packages the packets an application writes (a live encoder's)
the same way. The `container.hls` and `container.dash` demuxers read the presentations back.

| Output | Segments | Manifest | Source |
|---|---|---|---|
| HLS (`PackagingOptions.hls()`) | MPEG-TS, audio and video muxed, cut at video key frames | master playlist (one variant per rendition with peak and average bandwidth, `CODECS`, resolution, frame rate) and media playlists, on demand, as an event or live (below) | RFC 8216 |
| HLS with CMAF (`hlsCmaf()`) | CMAF tracks (`_init.mp4`, `.m4s`) | as above, CMAF audio as an `EXT-X-MEDIA` audio group, `EXT-X-MAP` | RFC 8216 |
| DASH (`dash()`) | CMAF tracks | static MPD (dynamic for an event or live), one adaptation set per kind, codec, language and role, `SegmentTemplate` with `SegmentTimeline` | ISO/IEC 23009-1 |
| HLS and DASH (`cmaf()`) | one set of CMAF tracks | both | |
| Byte ranges (`byteRanges(true)`) | one file per stream | `EXT-X-BYTERANGE`; DASH `SegmentList` with media ranges | |
| DASH on demand (`dashOnDemand()`, or `segmentIndex(true)` with byte ranges) | one CMAF file per stream, a single `sidx` after the `moov` (`FragmentedMp4MuxerOptions.SegmentIndex.SINGLE`) | on-demand profile MPD: `BaseURL` and `SegmentBase` with `indexRange` and `Initialization range`; HLS keeps `EXT-X-BYTERANGE` past the `sidx` | ISO/IEC 23009-1 clause 8.3 |

A `Rendition` is a variant (`Rendition.of`: its first video and audio track), an alternate audio rendition
(`Rendition.ofAudio`, or a `Rendition.builder` of kind `AUDIO` that names a track) or a subtitle rendition
(`ofSubtitles`: SubRip, WebVTT, 3GPP timed text, ASS or TTML from any container or subtitle file), with a
language, a label, and the default and forced flags:

| Feature | HLS | DASH |
|---|---|---|
| Alternate audio | one `EXT-X-MEDIA` audio group per codec that every variant names (`AUDIO`), the variants then video only; an audio-only presentation lists one variant per group | an adaptation set per codec, language and label, `Label`, roles `main`/`alternate` (and `commentary`, `dub`, `description`) |
| Subtitles | WebVTT segments (`.vtt`) cut at the first video stream's segments, cues of the other codecs converted, spanning cues repeated, `X-TIMESTAMP-MAP` onto the segments' 90 kHz timeline; a `SUBTITLES` group with `FORCED` and `CHARACTERISTICS` | CMAF text tracks: `wvtt` (other codecs converted), TTML as `stpp` with its IMSC profile in `codecs`; `contentType="text"`, roles `subtitle`/`forced-subtitle`/`caption` |
| Trick play (`trickPlay(true)`) | an I-frame playlist per video stream (`EXT-X-I-FRAME-STREAM-INF`): byte ranges of every key frame of transport stream segments behind the program tables of an `EXT-X-MAP`, or of each CMAF segment's first key frame | a trick-play adaptation set (`EssentialProperty` `http://dashif.org/guidelines/trickmode`) of the key frames alone, each lasting until the next, `maxPlayoutRate` and `codingDependency="false"` |
| Events and live (`presentation(EVENT)`, `presentation(LIVE)`) | playlists rewritten after every segment, `EXT-X-PLAYLIST-TYPE:EVENT`, or a sliding window of `windowSegments` (grown to three target durations) whose expired segments `removeExpiredSegments` deletes once players cannot need them; `EXT-X-PROGRAM-DATE-TIME` from `programDateTime` | a dynamic manifest after every segment: `availabilityStartTime`, `publishTime`, `UTCTiming`, `minimumUpdatePeriod` until the end, `timeShiftBufferDepth` and windowed timelines for live |

The transport stream segments of every rendition share one timeline (media time zero is the 700 ms
multiplexing delay), so variants and alternate renditions stay in sync. The test kit's HLS and DASH
validators check every layout, including each version of a live presentation as it is written; ffmpeg plays the
playlists and manifests and reads each WebVTT segment (its HLS demuxer skips subtitle renditions), and the HLS
and DASH demuxers read every presentation back to the input's packets and cues on one timeline.

Encryption (`PackagingOptions.encryption`, a `PackagingEncryption`) takes three methods, with clear keys
(`EXT-X-KEY` with the key URI, the initialization vector and the key format; an optional key file written with
the segments):

| Method | Transport stream segments | CMAF segments | DASH |
|---|---|---|---|
| `AES_128` | whole segments, AES-128-CBC with PKCS#7 padding, initialization vector given or the media sequence number (byte ranges each encrypted on their own) | media segments as for TS, initialization segments clear | not available |
| `SAMPLE_AES` | the SAMPLE-AES format of Apple's MPEG-2 stream encryption: H.264 slices (32-byte clear leader, 1:9 pattern, emulation prevention after encryption), AAC, AC-3 and E-AC-3 frames (16-byte clear leader), PMT `stream_type` `0xDB`, `0xCF`, `0xC1`, `0xC2` with `zavc`/`aacd`/`ac3d`/`ec3d` and `apad` audio setup descriptors | `cbcs` | `ContentProtection` `cbcs` with `cenc:default_KID`, and W3C Clear Key |
| `SAMPLE_AES_CTR` | not available | `cenc` | `ContentProtection` `cenc` with `cenc:default_KID`, and W3C Clear Key |

ffmpeg decodes the AES-128 playlists and the decrypted CMAF tracks to the frames of the clear packaging, and
decrypts the SAMPLE-AES playlists to the clear packets; the SAMPLE-AES slices and audio frames equal Shaka
Packager's bit for bit. ffmpeg plays every layout; it cannot read DASH `SegmentList` media ranges, which dash.js
and Shaka Player do.

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

| Format | Read | Write | Package | Source |
|---|---|---|---|---|
| SubRip (`.srt`) | yes: any encoding (detected or named), any line breaks, missing counters and blank lines, unsorted cues, ASS override blocks | yes | `container.subtitle` | de facto format |
| WebVTT (`.vtt`) | yes: header with `STYLE`, `REGION` and `NOTE` blocks, identifiers, settings | yes | `container.subtitle` | W3C WebVTT |
| SSA (`.ssa`, v4.00) and ASS (`.ass`, v4.00+) | yes: the header (info, styles, `Comment` events, `[Fonts]` and other sections) as the track configuration, events in their own `Format` order as Matroska blocks with their read order, layers, styles, names, margins and effects | yes, the script's own version or converted (`AssMuxer(sink, AssScriptType)`, `OutputContainer.ASS`/`SSA`), events back in read order; Matroska `S_TEXT/SSA` for a v4.00 script | `container.subtitle` | SSA v4.00 and ASS v4.00+ specifications, libass behavior |
| TTML (`.ttml`, `.dfxp`), IMSC 1.0.1 to 1.3 | yes: one `Codec.TTML` document, decoded to cues with the TTML timing model (par and seq, clock, frame, tick and offset times, frame rate multiplier, drop frames), referential, region and inline styles, `set` animations and region placement | yes, IMSC 1.0.1 text profile documents; a single document unchanged | `container.subtitle` | W3C TTML1/TTML2, IMSC 1.1, checked against imscJS on the W3C IMSC test suite |
| SAMI (`.smi`) | yes: one SubRip track per language class, its `lang` and `Name`, the `META` charset | yes, one class per track | `container.subtitle` | Microsoft SAMI |
| LRC (`.lrc`) | yes: one WebVTT track, repeated time tags, enhanced word times as timestamp tags, `offset`, `length` and ID tags | yes, with the title, artist and album tags | `container.subtitle` | de facto format |
| MicroDVD (`.sub`) | yes: one SubRip track, the declared or given frame rate (24000/1001 by default), `y`, `c`, `f` and `s` control codes | yes, at a given frame rate (0.001 to 1000, declared with three decimals and frames counted at the declared rate) | `container.subtitle` | de facto format |
| MP4 3GPP timed text (`tx3g`) and QuickTime text (`text`) | yes: `styl` styles, colors, fonts (`ftab`) and sizes against the default style, `hlit`/`hclr` as a background, `krok` karaoke, the justification and a `tbox` as the placement; QuickTime text converted to 3GPP samples (Mac OS Roman text, TextEdit style scraps) | yes (`tx3g`), overlapping cues merged into gapless samples, `styl`, `hlit`, `hclr` and `krok` written, and a `tbox` for anchored cues under an entry with a default text box | `container.mp4` | 3GPP TS 26.245, QuickTime File Format |
| MP4 WebVTT (`wvtt`) | yes, one packet per cue, the pieces of split cues joined by the readers | yes, overlapping cues cut into `vttc`/`vtte` samples | `container.mp4` | ISO/IEC 14496-30 |
| MP4 TTML (`stpp`), `Codec.TTML` | yes, one document per packet with the `stpp` fields as configuration, decoded to cues | yes (MP4, MOV and fragmented MP4), one document per sample, an empty document in each gap; overlapping documents rejected | `container.mp4` | ISO/IEC 14496-30, W3C TTML |
| Matroska SubRip (`S_TEXT/UTF8`) and ASS (`S_TEXT/ASS`, `S_TEXT/SSA`) | yes, ASS events passed through | yes (Matroska only) | `container.matroska` | Matroska codec specs |
| Matroska and WebM WebVTT (`S_TEXT/WEBVTT`, `D_WEBVTT/*`) | yes | yes, as `D_WEBVTT/SUBTITLES` | `container.matroska` | Matroska and WebM specs |

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

| Format | Read | Write | Package | Source |
|---|---|---|---|---|
| PGS (HDMV presentation graphics) | yes: compositions, windows, palettes (BT.601 up to 576 lines, BT.709 above), objects split over several segments, palette-only updates, the cropping rectangle and the forced flag; in `.sup`, MPEG-TS (`0x90`), M2TS and Matroska (`S_HDMV/PGS`) | copied: `.sup`, MPEG-TS, Matroska | `subtitle`, `container.subtitle` | Blu-ray format, checked against ffmpeg (black box) |
| DVB subtitles | yes: display definitions and windows, pages and their time-out, regions, the 2, 4 and 8-bit CLUTs (default and defined, full and reduced range), pixel objects with map tables, progressively coded objects; in MPEG-TS (subtitling descriptor, composition and ancillary pages) and Matroska (`S_DVBSUB`) | copied: MPEG-TS, Matroska | `subtitle` | ETSI EN 300 743, checked against ffmpeg (black box) |
| DVD subpictures (VobSub) | yes: the run-length coded fields, the display control commands (forced start, start and stop, palette, contrast, area, field offsets, and the color and contrast areas of command `07` by screen line and column) and the palette and size of the index; in `.idx`/`.sub`, Matroska (`S_VOBSUB`) and MP4 (`mp4s` with object type `0xE0`) | copied: `.idx`/`.sub`, Matroska, MP4 | `subtitle`, `container.subtitle` | DVD-Video, checked against ffmpeg (black box) |
| EBU teletext subtitles | yes: pages by magazine and number from the teletext descriptor, the subtitle and newsflash boxes, the Latin national options, Cyrillic, Greek, Hebrew and Arabic sets with their G2 supplements (Arabic letter forms read as the letters, lam-alef as lam and alef), Hebrew and Arabic rows put in reading order, X/26 enhancements (positions, G2 characters, diacritics), X/28 and M/29 designations, colors and double height | copied: MPEG-TS | `subtitle` | ETSI EN 300 706 and EN 300 472 |
| CEA-608 | yes: pop-on, roll-up and paint-on captions of the four channels, preamble and mid-row codes, the special and extended characters, repeated control codes; in H.264 and HEVC SEI (ATSC A/53 `GA94`), MPEG-2 user data, AV1 ITU-T T.35 metadata, and QuickTime `c608` tracks | in H.264 SEI (re-encoded video) and QuickTime `c608` tracks | `subtitle` | 47 CFR 15.119, CTA-608, checked against ffmpeg's `cc_dec` (black box) |
| CEA-708 | yes: DTVCC packets and service blocks, the C0, C1, G0, G1 and G2 code sets, windows (define, clear, display, hide, toggle, delete), pen attributes, colors and locations; in the same carriages as CEA-608 and QuickTime `c708` tracks | in H.264 SEI (re-encoded video) and QuickTime `c708` tracks | `subtitle` | CTA-708, ATSC A/53 |

### Metadata

`image.metadata` (`smalt-image`) reads the metadata of every image format and of camera raw files, and edits it
in JPEG, PNG, WebP, GIF, HEIC, AVIF, TIFF, DNG, JPEG XL, JPEG 2000 and BMP files and the camera raw files with a TIFF signature
without re-encoding the pixels (`ImageMetadata`, `ImageMetadataEdit`, `ImageMetadataEditor`).
`metadata` (`smalt-core`) reads and writes the tags, cover art, chapters and XMP packets of audio and video files
(`TagEditor`, `MediaTags`, with `TagEditor.stripLocation` removing where a video or a recording was made), holds the
tag vocabulary `Media.probe` shares (`StandardTag`), and models XMP packets (`XmpPacket`). The image decoders return the embedded XMP packet with the pixels, as
`DecodedImage.xmp()` (JPEG, with Extended XMP merged; PNG; WebP; GIF; TIFF; JPEG 2000; JPEG XL; HEIC; AVIF), next to
the EXIF block and the ICC profile, and the physical pixel density (or only the pixel aspect ratio) the file
declares, as `DecodedImage.density()`: a `PixelDensity` in the unit the file uses (the JFIF header of a JPEG, or
when it is absent or gives only an aspect ratio a physical resolution in its EXIF `IFD0`; the PNG `pHYs` chunk; the TIFF `XResolution`, `YResolution` and
`ResolutionUnit` fields; the BMP pixels per meter; the JPEG 2000 capture resolution box, or the display one). The
JPEG, PNG, TIFF, BMP and JPEG 2000 encoders write it from their `density` options, each in the unit and precision its
field holds, and the TIFF page writer and the ImageIO reader (`Dimension/HorizontalPixelSize`) carry it. The
encoders write metadata too: `ImageEncoderOptions` carries a color profile, an EXIF block, an XMP packet and an
orientation to every encoder behind `Images.encode`, `Images.openAnimationWriter` and `Images.openPageWriter`
(`ImageEncoderOptions.Builder.metadataOf(decoded)` takes them from a decoded image), and the JPEG, PNG, APNG, WebP
and animated WebP encoders take `exif` and `xmp` options (GIF an `xmp` one) that embed the bytes as given, the JPEG
encoder splitting a large packet into Extended XMP segments. A format records what the table below lists for it;
a profile it cannot record (in ICO and ANI files, a non-RGB profile in GIF, WebP, BMP and AVIF, CMYK inks in a
format without CMYK) converts the pixels to sRGB instead, and a still format without an orientation field (GIF,
BMP, ICO, ANI) turns the picture upright. The EXIF block is embedded byte for byte, maker note included, with
only its orientation patched in place; TIFF and JPEG 2000 rebuild it from its entries. Everything
here is written from the specifications (EXIF 2.32, XMP parts 1-3, IPTC
IIM 4.2, ID3v2.3/2.4, APEv1/APEv2, Lyrics3, Vorbis comment, FLAC format, Apple QTFF, its keyed metadata and iTunes
metadata, 3GPP TS 26.244 asset boxes, ISO 6709, Matroska tags); the tests use exiftool and ffprobe as independent
readers.

| Image format | EXIF | XMP | IPTC-IIM | ICC | Comments, text | Editing |
|---|---|---|---|---|---|---|
| JPEG | `APP1 Exif` | `APP1`, Extended XMP split and merged | `APP13` Photoshop resources (other resources kept, digest recomputed) | `APP2 ICC_PROFILE` chunks | `COM` | yes; the scan data is copied, `APP2 MPF` image offsets are corrected |
| PNG, APNG | `eXIf` | `iTXt XML:com.adobe.xmp` | - | `iCCP` (a replaced profile also replaces `sRGB`, `cICP`, `mDCV` and `cLLI`; a PQ or HLG profile is written as `cICP`, `mDCV`, `cLLI` and `iCCP`) | `tEXt`, `zTXt`, `iTXt` | yes; the image chunks are copied |
| WebP | `EXIF` | `XMP ` | - | `ICCP` | - | yes; the `VP8X` flags follow the chunks |
| GIF | - | `XMP DataXMP` application extension | - | `ICCRGBG1` application extension (RGB profiles) | comment extensions | yes (XMP, ICC, comments) |
| HEIC, AVIF | `Exif` item | `mime` item | - | `colr` property of the primary item (`prof` or `rICC`; the `nclx` one is kept) | - | EXIF and XMP items, ICC property (replaced in place, or added and associated when shared; dropped when unused); `meta` rebuilt, item offsets shifted, removed item data zeroed; image sequences too, the `stco`, `co64`, `saio`, `tfhd` and `tfra` offsets of their tracks moved with the data |
| TIFF, BigTIFF | the descriptive entries of IFD0 and its sub-directories | tag 700 | tag 33723, or the Photoshop resources of tag 34377 (edited there too) | tag 34675 | - | yes; IFD0 and its EXIF directories are rebuilt in place, layout entries, strips, tiles and other pages keep their offsets, freed values are zeroed |
| DNG; CR2, NEF, NRW, ARW, SR2, SRF, PEF, SRW | as TIFF (DNG tags kept out) | as TIFF | as TIFF | as TIFF | - | yes, as TIFF; the sensor data keeps its offsets |
| ORF, RW2 | as TIFF, from a TIFF structure with the vendor's signature (Panasonic sensor tags below 254 left out) | as TIFF | as TIFF | as TIFF | - | read only |
| CR3 | `CMT1`, `CMT2` and `CMT4` of the Canon `uuid` box, combined (the `CMT3` maker note left out) | XMP `uuid` box | - | - | - | read only |
| RAF | the JPEG preview's `APP1 Exif` | the JPEG preview's XMP | - | - | - | read only |
| MRW | the TIFF structure of the `TTW` block | - | - | - | - | read only |
| JPEG XL | `Exif` box (or `brob`) | `xml ` box (or `brob`) | - | codestream profile (read) | - | EXIF and XMP boxes replaced by plain ones in place or before the codestream, kept boxes copied byte for byte (compressed ones included), a bare codestream wrapped in a container; the codestream and `jbrd` copied unchanged |
| JPEG 2000 (JP2, JPX) | `uuid` box `JpgTiffExif->JP2` | XMP `uuid` box, or an `xml ` box holding XMP | IPTC `uuid` box | `colr` of the JP2 header (restricted or any ICC, chosen by precedence; a new profile is written as restricted ICC ahead of the enumerated specifications, removal leaves or adds an enumerated one) | - | yes; EXIF, XMP and IPTC boxes replaced in place or added after the JP2 header, the codestream boxes copied unchanged; raw codestreams and files with fragment tables read only |
| BMP | - | - | - | V5 embedded profile (a new profile follows the pixels; a smaller header is widened to V5, its masks and color table carried over; removal declares sRGB) | - | ICC only; the color table and pixel data are copied unchanged |

| Container | Tags | Pictures | Chapters | Edited in place when | Muxers that store tags |
|---|---|---|---|---|---|
| MP3, ADTS, raw AC-3 and E-AC-3, AMR | ID3v2.2 (read), ID3v2.3, ID3v2.4 (tags appended with a `3DI` footer or found through a `SEEK` frame are read as ID3v2.4 section 5 says, each replacing the tags before it unless flagged as an update, and merged into the leading tag), the XMP packet in a `PRIV` frame of owner `XMP`; APEv1 (read) and APEv2 (read, updated, added or removed); ID3v1 (read, updated, added or removed); Lyrics3v2 kept, Lyrics3v1 kept while an ID3v1 tag follows it | `APIC`, APE `Cover Art (...)` items | `CHAP` and `CTOC` | the new ID3v2 tag fits the old tags with their padding (the trailing tags never move the audio) | `RawMuxer` (MP3, MP2, AC-3, E-AC-3, AMR), `AdtsMuxer` |
| FLAC | `VORBIS_COMMENT`; APE and ID3v1 after the frames, as for MP3 | `PICTURE` (APE cover art read) | `CHAPTERxxx` comments, else the tracks of the `CUESHEET`, which is kept while the chapters are unchanged and otherwise rebuilt from them | the blocks fit the old ones and the `PADDING` | `RawMuxer` (FLAC) |
| Ogg Vorbis, Opus, FLAC | comment header of every link: the first link takes the new tags, every later link keeps its own with the fields, pictures and chapters the edit changes replaced | `METADATA_BLOCK_PICTURE` | `CHAPTERxxx` comments | the Vorbis or Opus comment header, padded, keeps its pages; otherwise later pages are renumbered | `OggMuxer` |
| MP4, M4A, MOV | iTunes `ilst` (text, `trkn`, `disk`, `cpil`, `gnre`, free-form `----`; the integer atoms `tmpo`, `rtng`, `stik`, `pgap`, `hdvd`, `tvsn`, `tves`, `plID`, `cnID`, `atID`, `geID`, `sfID`, `cmID`, `akID`, `shwm` and any other atom stored as a signed or unsigned integer, as decimal text in the width and signedness the file uses; other binary atoms kept), `udta` text atoms (the copyright-sign `xyz`, `mak`, ... atoms); QuickTime keyed metadata (`meta` of handler `mdta` in `moov` or `udta`: the make, model, creation date and location keys phones write, such as `com.apple.quicktime.location.ISO6709`, read under their names with numbers as decimal text, written back in the item's data type and locale; binary items kept; a new reverse-DNS key goes to the file's keyed metadata or a new `moov/meta`); the 3GPP `loci` box as the key `loci` (ISO 6709 coordinates, place name, role, body and notes kept); the XMP packet in a top-level `uuid` box or a `udta/XMP_` atom; per track the `udta` `name`, DASH role `kind` boxes, an `ilst` and the `tkhd` enabled flag | `covr` | Nero `chpl` and a QuickTime chapter track (a disabled `text` track with UTF-8 titles that every audio and video track references through `tref/chap`; read from `chpl`, or from the track when `chpl` lacks it or the track completes the chapters or titles a full `chpl` cut), kept while the chapters are unchanged, otherwise replaced in the space of the old one | `moov` fits its space, the following `free` boxes and the samples of a replaced chapter track, or ends the file; otherwise `stco`/`co64`, `tfhd` and `tfra` offsets are shifted, and a `stco` table an offset overflows becomes `co64` | `Mp4Muxer` (chapter track included; keyed metadata, `loci` and XMP), `FragmentedMp4Muxer` (`chpl` only; keyed metadata, `loci` and XMP), both with the track tags |
| Matroska, WebM | global `Tags` (sort names as nested `SORT_WITH`, track ReplayGain in a level-30 tag), `Info` title; per track `Name`, the flag elements and the `Tag` targeting its `TrackUID` | image `Attachments` (`cover.*`); the other attachments as `MediaTags.attachments()` | `Chapters`: every edition (`MediaTags.editions()`), nested chapters, titles per language | the elements fit their old space and the following `Void`; otherwise the segment is rewritten with a new `SeekHead` and relocated `Cues` | `MatroskaMuxer` |
| WAV (RIFF, RIFX, RF64, BW64) | `LIST/INFO`, `id3 ` chunk, the `bext`, `iXML`, `axml`, `chna` and `smpl` chunks under their keys (`TagEditor` lists them), the XMP packet of a `_PMX` chunk (or of the `id3 ` chunk's `PRIV` frame, written back there); a RIFF file an edit takes past 4 GiB becomes RF64 (a `ds64` chunk in place of a leading `JUNK` or inserted first) | in the `id3 ` chunk | cue points (`cue ` and `LIST adtl`), or the `id3 ` chunk | the chunks fit their slots and `JUNK`, or end the file | `WavMuxer` |
| AIFF | `NAME`, `AUTH`, `(c) `, `ANNO`, `ID3 ` chunk, `INST` under its keys, `COMT` texts read as comments, the XMP packet of an `APPL` chunk of signature `XMP ` | in the `ID3 ` chunk | `MARK` markers, or the `ID3 ` chunk | as WAV | `AiffMuxer` |
| AVI | `LIST INFO` (the entries it has identifiers for); per track the `strn` name | - | - | the header list, the `INFO` list and the `JUNK` before `movi` hold the new ones; otherwise the media moves with its `indx`, `ix##` and absolute `idx1` offsets | `AviMuxer` |
| MPEG-TS | per track an ATSC A/65 component name and the ISO 639 `audio_type` (no global tags) | - | - | every copy of the program map table is rewritten in its own transport packets; an edit that outgrows them is rejected | `TsMuxer` |

`StandardTag` names the tags above by one vocabulary: besides the common ones, the sort names, conductor,
lyricist, tempo (`tmpo` in MP4), ISRC, description, the ReplayGain gains and peaks of the track and the album, the
MusicBrainz identifiers (the recording in an ID3v2 `UFID` frame) and the rating (0 to 5; the ID3v2 `POPM` byte),
each under the native name its format and the common taggers use. The track tags (`MediaTags.tracks()`,
`TrackTags`) hold the name, the tags and the dispositions (`TrackDisposition`: default, forced, original,
commentary, dub, hearing or visually impaired, captions, descriptions, clean effects, karaoke) of each track;
`TagEditor` reads them for every track and replaces those of the tracks a write lists, and `Media.probe` reports
them in each stream's `tags()` and `disposition()`.

### Processing

| Feature | Artifact | Package | Source |
|---|---|---|---|
| Resize, crop, orient, alpha weighting | `smalt-core` | `image.ops` | Pillow (resampling structure and filters); Mitchell and Netravali (1988) |
| Color conversion and chroma upsampling | `smalt-core` | `image.ops` | libjpeg-turbo; ITU-T H.273 |
| Color management: ICC v2/v4 profiles (matrix/TRC, `lut8`, `lut16`, `lutAtoB`/`lutBtoA`, floating-point `multiProcessElement` `D2Bn`/`B2Dn` tables with segmented curves, matrices and float lookup tables, gray, RGB, CMYK, n-color, L*a*b* and XYZ connection spaces), device link, abstract and named color (`ncl2`) profiles, chains of profiles, transforms with the four intents and black point compensation, built-in sRGB, Display P3, Adobe RGB, ProPhoto, BT.709, BT.2020 and gray profiles, CICP mapping of every H.273 primaries set (CIE XYZ included) and transfer (the logarithmic, BT.1361 and SMPTE ST 428-1 ones included), PQ and HLG profiles read from and written as ICC v4.4 `cicp`-tagged profiles, PQ/HLG to SDR tone mapping (BT.2390 EETF), SDR to PQ/HLG with the BT.2408 reference white, PQ to and from HLG through display light (BT.2390), HDR gamut conversion and tone mapping to a target's peak | `smalt-core` | `image.color` | Little CMS 2.17 (MIT); ITU-R BT.2100, BT.2390, BT.2408, SMPTE ST 2084, ARIB STD-B67, ICC.1:2022 |
| HDR gain maps: ISO 21496-1 metadata (binary and Adobe `hdrgm` XMP), rendering of an image for a display headroom to 16-bit PQ or HLG with its content light level, computation of a gain map from two renditions (`GainMaps`) | `smalt-core` | `image.gainmap` | ISO 21496-1 as libavif and libultrahdr implement it, the Adobe gain map specification; libultrahdr (fraction search), libavif (gain range without outliers) |
| Audio resampling (also with a variable ratio for clock drift, `Resampler.setRatio`), channel mixing (ITU-R BS.775 rules, AC-3 center, surround and LFE mix levels, ATSC A/52 Lo/Ro and Lt/Rt downmixes, a caller's matrix, `ChannelMixerOptions`; ambisonics up to order 14 in ACN order with SN3D, N3D or Furse-Malham scaling: exact conversion between orders, normalizations and channel orders, speakers encoded as plane waves, and rendering to loudspeakers by first-order cardioids, to stereo and mono as RFC 8486 section 4 does), FIFO | `smalt-core` | `audio.process` | textbook Kaiser-windowed sinc design; ATSC A/52 section 7.8; RFC 8486; Google Spatial Audio RFC (ambisonic conventions) |
| Voice processing at 8, 16, 32 and 48 kHz with up to 8 capture and render channels (band splitting and three-band filter bank, multichannel AEC3): echo cancellation (AEC3 and the mobile echo canceller), noise suppression, high-pass filter, the legacy gain controller in its digital and analog modes, the analog gain controller with clipping prediction, and the AGC2 level controller (adaptive digital gain with the RNN voice activity detector, saturation protector, limiter, input volume controller) | `smalt-audio` | `audio.voice` | WebRTC audio processing module, bit-exact with its scalar build; PFFFT; RNNoise weights; Ooura FFT; musl and fdlibm math (royalty-free WebRTC grant in `PATENTS`) |
| Loudness: momentary, short-term and integrated loudness, loudness range, sample and true peak (ITU-R BS.1770-4, EBU R128, Tech 3341 and 3342 minimum requirements), any channel layout, bounded memory; silence detection; loudness normalization gains, single-pass normalization (`LoudnessNormalizer`) and a look-ahead true-peak limiter (`TruePeakLimiter`); ReplayGain 2.0 track and album gains, written as `REPLAYGAIN_*` tags or Opus `R128_*` tags (RFC 7845) and applied by `AudioReader` (`AudioReaderOptions.replayGain`) | `smalt-core` | `audio.loudness` | libebur128 (MIT); ReplayGain 2.0 specification, RFC 7845 |

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

| Operation | Class | 16-bit | Matches | Notes |
|---|---|---|---|---|
| Resize, fit within, fit into a box, cover a square | `ImageOps.resize`, `fitWithin`, `fitInto`, `coverSquare`, `ResizeOptions` | yes | Pillow `resize` with `BOX`, `BILINEAR`, `HAMMING`, `BICUBIC`, `LANCZOS` (within 1 away from the edges); ffmpeg's scaler with the same kernels (38 dB at 8 bits, 50 dB at 10 bits, 60 dB at 16 bits) | nearest, box, bilinear, Hamming, Catmull-Rom, Mitchell-Netravali, Lanczos 2/3/4, area; separable, stretched when reducing, premultiplied for transparency; 8-bit in fixed point, 16-bit and linear light (`ResizeOptions.linearLight`, sRGB transfer) in double precision, rounded once |
| Pad onto a canvas, flatten over a color | `ImageOps.pad`, `flatten` | yes | | flattening a Y'CbCr picture stays in Y'CbCr at its depth |
| Rotate by any angle (expand, keep or inscribed canvas, background color), affine transform | `ImageOps.rotate`, `affine` | yes | Pillow `rotate` (nearest: exact; bilinear: within 1 inside the picture) | nearest, bilinear, bicubic (Catmull-Rom); premultiplied, edges antialiased against the background; right angles are exact |
| Flips, transpose, transverse | `ImageOps.flipHorizontal` ... | yes | | same as `orient` |
| Trim uniform borders, extend (background, replicate, mirror, repeat), canvas resize with an anchor | `ImageOps.trim`, `trimBounds`, `extend`, `resizeCanvas` | yes | | trimming and cropping canvases keep the representation |
| Smart crop to an aspect ratio | `ImageOps.findSmartCrop`, `smartCrop` | analysis at 8 bits | smartcrop.js 2.0.5 (same crop for the same pixels) | `CENTER`, `ENTROPY` (libvips idea), `ATTENTION` (smartcrop.js port) |
| Gaussian blur | `ImageFilters.gaussianBlur` | yes | Pillow `GaussianBlur` (exact, `BOX` method) | `EXACT` (sampled kernel to 3 sigma, float) or `BOX` (3-pass extended box, O(1) in sigma); `AUTO` switches at sigma 4; premultiplied |
| Box blur | `ImageFilters.boxBlur` | yes | Pillow `BoxBlur` (exact) | |
| Unsharp mask, sharpen | `ImageFilters.unsharpMask`, `sharpen` | yes | Pillow `UnsharpMask`, `SHARPEN` (exact / interior) | alpha kept |
| Convolution with any odd kernel up to 99x99 | `ImageFilters.convolve`, `ConvolutionKernel` | yes | Pillow `Kernel` filters inside the 1-pixel border | presets: sharpen, smooth, detail, edge enhance, find edges, emboss |
| Median | `ImageFilters.median` | yes | Pillow `MedianFilter` (exact) | sliding histogram, radius up to 32 |
| Brightness, contrast, saturation | `ImageAdjustments` | yes | Pillow `ImageEnhance` (exact) | |
| Levels, gamma, curves (monotone cubic through points, or any function) | `ImageAdjustments.levels`, `gamma`, `curves`, `ToneCurve` | yes | | lookup tables |
| Auto levels (per channel), auto contrast (tone-preserving) | `ImageAdjustments.autoLevels`, `autoContrast` | yes | Pillow `autocontrast` (exact) | cutoff percentage |
| Grayscale (BT.601, BT.709, linear-light luminance), sepia, colorize (duotone), tint, invert, threshold | `ImageAdjustments` | yes | Pillow `convert("L")`, `colorize`, `invert` (exact) | |
| Composite with 14 blend modes, opacity, linear-light option; watermark (anchor, margins, scale, opacity) | `ImageCompositing.composite`, `watermark` | yes | W3C Compositing and Blending formulas | straight alpha in and out, premultiplied source-over |
| Palette quantization to 2-256 colors with dithering | `ImageQuantization.quantize` | reduced to 8 bits | | median cut or NeuQuant; none, ordered, Floyd-Steinberg or Sierra dithering; exact palettes when the colors fit; the same code as the GIF encoder |

Hot loops (box filter columns, float multiply-accumulate, channel mixing, unsharp masking, blend modes) run
in a kernel family with a Vector API implementation and a bit-identical scalar twin; the per-pixel
resampling of rotations and the lookup tables stay scalar (their loads are gathers). Pillow's outputs are
checked by `PillowOracleTest` (inputs, outputs and the mapping of each call in
`tools/testdata/imageops-oracle.py`), smartcrop.js's by `SmartCropOracleTest` (`tools/testdata/smartcrop-oracle.js`).

### Transcoding

`smalt-core` package `transcode` converts files on top of the containers and the codec registries, replacing
the common ffmpeg command lines. It reads every container above plus animated GIF, APNG and WebP, and writes
MP4, M4A, fragmented MP4, QuickTime MOV, Matroska, WebM, Ogg (Opus, Vorbis, FLAC; `OGG_FLAC` picks FLAC), WAV,
AIFF, ADTS, IVF, MPEG-TS, AVI, the raw MP3, FLAC, AMR, AC-3 and E-AC-3 streams, H.264 and HEVC Annex B and AV1 OBU
elementary streams, YUV4MPEG2 files of uncompressed 8-bit 4:2:0 frames, and SubRip, WebVTT, SSA, ASS, TTML, SAMI, LRC and MicroDVD files. Which codecs it can encode depends on the
artifacts present: H.264, AAC, AMR, AC-3 and E-AC-3 need `smalt-mpeg`, VP9, AV1, ProRes and DNxHD need `smalt-video`, VP8
Motion-JPEG and Motion JPEG 2000 need `smalt-image`, Opus, FLAC, ALAC, MP3 and Vorbis need `smalt-audio`; PCM is in core. There is no
HEVC encoder, so HEVC streams (an `.h265` output included) can only be copied. A QuickTime output gets ProRes 422 HQ by default (or DNxHR HQ),
an `.amr` output AMR-NB at 12.2 kbit/s (8 kHz mono, resampled and downmixed as needed) unless a codec or
bitrate is named, a FLAC encode keeps the source's sample size (up to 32 bits) and, written as a native
file on a seekable output, its final `STREAMINFO` (length, MD5) and seek table, and an ALAC encode (M4A, MP4, MOV
or Matroska, when the caller names ALAC) keeps the source's sample size rounded up to 16, 20, 24 or 32 bits and
stores the final `ALACSpecificConfig` (largest packet, average bitrate). A stream re-encoded without a
change of rate, layout or gain moves its samples as integers when both codecs code integers (FLAC, ALAC and integer
PCM to FLAC or PCM), so FLAC to FLAC, ALAC to FLAC and their PCM conversions are bit for bit at every sample size, and
as 64-bit floats to PCM otherwise, so 64-bit float PCM re-encodes exactly; `AudioTranscodeOptions.verifyIntegrity`
checks a FLAC source's MD5 signature on the way.

| Operation | Encoded streams | Copied streams |
|---|---|---|
| Container change (remux) | - | MP4, M4A and MOV output of MP4, Matroska, MPEG-TS, AVI and Ogg input goes through `Mp4Remuxer`, subtitles and several audio tracks included |
| Trim start | exact frame / sample (seeks when the demuxer can) | key frame (video), packet (audio); exact in MP4 through edit lists |
| Trim end, duration limit | exact frame / sample | exact in MP4 (edit list) and Ogg (granule) |
| Scale (fit, fill, exact, edge limits), crop, rotate | yes | - |
| Anamorphic video (`SampleAspectMode`) | samples kept with the ratio recorded (scaled by an uneven resize) where the container stores one, else resampled to square samples | ratio kept |
| Frame rate: drop / repeat, or blend the two neighboring frames (`FrameRateConversion`) | yes | - |
| Pixel format: chroma format, bit depth (10 bits by default for PQ and HLG output), matrix and range | yes | - |
| Color: primaries and transfer conversion through the color profiles (`VideoTranscodeOptions.color`, `colorConversion`): HDR10 / HLG to SDR with the BT.2390 EETF (or clipping), SDR to PQ / HLG at the BT.2408 reference white, PQ to and from HLG, gamut conversion; the HDR metadata of the track used when the pictures carry none; the output and the encoder declare the color | yes | - |
| Deinterlacing (`Deinterlacer`: bob, linear, edge-based line average) of frames their decoder or track flags interlaced, at the frame or the field rate | yes | - |
| 2:3 pulldown (`Pulldown.TELECINE`, 24 to 30 frames per second, top field first) and inverse telecine (`Pulldown.INVERSE_TELECINE`: field matching and decimation, 30 to 24) | yes | - |
| Subtitle burn-in (`SubtitleBurnIn`: a text subtitle track of the input or given cues) and watermark (`VideoTranscodeOptions.watermark`) | yes | - |
| Key frame interval, or no periodic key frames (`VideoTranscodeOptions.keyframeInterval`, `VideoEncoderOptions.keyframeInterval`) | yes (H.264, VP8, VP9, AV1; ProRes, DNxHD and Motion-JPEG are all key frames) | - |
| Resample, remix (mix levels, Lt/Rt, a caller's matrix: `AudioTranscodeOptions.mixing`), gain, sample format (PCM codec) | yes | - |
| Loudness normalization (two passes: measure the kept range, then apply the gain under a true-peak ceiling, or reach the target and keep the ceiling with a true-peak limiter; or one pass that follows the loudness as it encodes, `singlePassNormalization`) | yes | - |
| Subtitles: SubRip, WebVTT, 3GPP timed text, ASS, TTML | converted to what the container carries (also to `.srt`, `.vtt`, `.ass`, `.ssa`, `.ttml`, `.smi`, `.lrc` and `.sub` files), cut to the kept range; a cue still shown at a trimmed start is kept however early it began | copied, retimed and cut to the kept range, likewise |
| Bitmap subtitles (PGS, DVB, VobSub), teletext, closed captions | teletext and caption tracks, and with `SubtitleTranscodeOptions.closedCaptions` the captions inside video, converted to a text subtitle track; captions kept in re-encoded H.264 video | bitmap and teletext tracks copied where the container carries them, retimed and cut to the kept range |
| Image convert, scale, crop, flatten, byte budget | yes (first frame of animations; animated output needs an animation encoder; an animation fits the budget by quality, then size, then dropped frames) | byte-identical pass-through when nothing changes |
| Tags, cover art, chapters, track names, tags and dispositions (`TranscodeOptions.tagMode`, also `RemuxOptions` and `PackagingOptions`: the input's by default, `noTags()`, `tags(...)` to replace them, `mergeTags(...)` to update them) | written in the output's own form (`Muxer.setTags`, `OutputContainer.supportsTags`), chapters cut to the kept range and moved to its start, track tags following their tracks; the Matroska statistics tags of the input's streams are left out (the output declares its own durations), an encoded or trimmed stream loses its encoder tags, the file its encoder once a stream is encoded and its ReplayGain once a gain or a loudness normalization changes the level; input values the output rejects are left out, also those that fit alone but not together (two large covers in one FLAC comment block) | likewise; the comments and pictures a copied FLAC or Vorbis configuration embeds are removed in Matroska, MP4 and Ogg, whose own tags replace them |
| Image color and metadata (`ImageTranscodeOptions.color`, `metadata`) | the input's ICC profile recorded with its pixels (`ColorMode.KEEP_PROFILE`, the default; converted to sRGB where the output format cannot record it) or the pixels converted to sRGB (`CONVERT_TO_SRGB`); the EXIF block and XMP packet kept (`MetadataMode.KEEP`, the default: orientation reset since the pixels are turned upright, `PixelXDimension` and `PixelYDimension` updated, the stale thumbnail removed, the maker note kept in place), stripped (`STRIP`) or kept without the GPS directory, the `exif:GPS` properties and the XMP place names (`STRIP_LOCATION`) | kept byte for byte with `KEEP` and `KEEP_PROFILE` |

### Capture

| Feature | Artifact | Package | Backends |
|---|---|---|---|
| Cameras, screens and windows | `smalt-capture` | `capture` | Windows: Media Foundation (camera controls through the source's `IAMCameraControl` and `IAMVideoProcAmp`), DXGI desktop duplication or GDI (screens), Windows Graphics Capture or `PrintWindow` (windows), configuration manager notifications of cameras and monitors. macOS: AVFoundation (focus, exposure and white balance modes, zoom), ScreenCaptureKit (screens and windows; `CGDisplayCreateImage` screens before 12.3). Linux: V4L2 (with its controls; `/dev` watched for cameras), X11 with MIT-SHM, Composite and XFixes (screens, windows, pointer). Screen and window regions, the pointer drawn or left out (`ScreenCaptureOptions`), exposure, focus, zoom, white balance, power-line frequency and the picture controls of a camera (`CameraControls`), and a listener of cameras and screens plugged in, removed or rearranged (`VideoDevices.addListener`). Called with FFM, so it needs `--enable-native-access=com.github.auties00.smalt.capture`. |
| Microphones and speakers | `smalt-capture` | `capture.audio` | Windows: WASAPI (shared mode with the engine's PCM converter and `IAudioClient3` short periods, exclusive mode, loopback of an output, endpoint notifications). macOS: CoreAudio HAL output unit (shared mode; no exclusive mode, no loopback). Linux: ALSA, whose `default` device reaches PulseAudio or PipeWire (shared mode; exclusive mode opens a device without software resampling; no loopback; changes found by polling). Float or 16-bit samples, any rate and channel count in the shared mode, capture and playback delays for echo cancellation. Run on the CI runners of all three systems against loopback devices. |

### Known limits

- Region decoding (`ImageDecoderOptions.region`): a progressive or 12-bit JPEG frame keeps the coefficients of the
  whole frame, since its scans refine every block, so the limits bound the whole frame, and it transforms only the
  blocks the region needs; the limits of a JPEG 2000 region bound each tile it decodes, at full resolution, so the
  region of an untiled codestream is bounded as the whole image is; a WebP image is decoded down to the region's last
  row, the rows above it included; a JPEG 2000 compositing layer of several codestreams, a composition, a HEIC or AVIF
  image that is not a grid of coded images, a thumbnail and the first frame of a sequence decode whole and are
  cropped, as do the PNG, GIF, BMP, ICO, ANI, JPEG XL and camera raw decoders. The data below a WebP or sequential
  JPEG region, and outside the tiles of a TIFF, JPEG 2000, HEIC or AVIF region, is not read, so damage there goes
  undetected. The picture of a region of a lossless WebP image without the alpha hint of its header declares alpha
  only when a pixel of the decoded rows is not opaque, where the whole decode looks at every pixel. The ImageIO reader
  decodes the whole image for a read that subsamples a source region.

- Transparent video: the alpha stream of VP8 and VP9 is coded at 8 bits in 4:2:0, so deeper alpha planes are
  rounded to 8 bits (a decode rescales them back to the frame's depth), and it has no hidden frames, so the hidden
  alternate references of VP8's good-quality usage carry no alpha frame. Only Matroska and WebM store the alpha
  stream (`OutputContainer.storesAlpha`): MP4 has no specified way to carry it, so a transcode into MP4 flattens the
  transparency and a remux into MP4 drops it. A transcode decides from the first picture: it keeps transparency
  when that picture has an alpha plane (an animated image decoded with alpha, or video that carries alpha), and
  otherwise flattens every picture.

- Uncompressed video: the ISO/IEC 23001-17 `uncv` sample entry (with its `uncC` and `cmpd` boxes) is not read, so such
  tracks stay `Codec.UNKNOWN_VIDEO`: the specification is not freely available and no permissively licensed
  implementation or sample files were found.
- Elementary streams: the reorder depth and latency that place Annex B pictures and shift their decoding times come
  from the first sequence parameter set, so a later sequence that reorders deeper is not accounted for. The pictures
  an IDR or IRAP picture with `no_output_of_prior_pics_flag` discards are found by modelling the decoder's output
  process (reordering, latency and buffer fullness, with HEVC reference picture sets and H.264 sliding window and
  memory management marking); H.264 field pictures are marked as the frames that hold them and gaps in `frame_num`
  are not filled, so an interlaced stream with field-level marking or a stream with frame gaps may have a waiting
  picture misjudged. A frame rate change that the track's time base (one field of the first rate) cannot represent
  is rounded to it, the error carried from picture to picture. H.263 PB-frames (Annex G and M) are timed as one
  picture, at the time of their P-picture.
- Video processing in transcodes: `SubtitleFont` reads TrueType outlines only (no CFF), applies no hinting and
  forms neither kerning pairs nor ligatures, so scripts that need shaping (Arabic, Indic) or right-to-left
  ordering are not drawn correctly; the burn-in draws bold, italic and underline but not the colors, fonts and
  positions of ASS styles or WebVTT regions. Inverse telecine follows the 2:3 cadence only (not 2:2:2:4 or
  irregular cadences that edits leave), decimating one frame of every five counted from the first. Field-rate
  deinterlacing doubles the rate when the first frame is interlaced. VP8 and Motion-JPEG code 8 bits only, so a
  PQ or HLG transcode into them is coded at 8 bits; Motion JPEG 2000 codes the depth but its samples are declared
  sYCC, so only the output track's color tells a PQ or HLG stream apart.
- MP4 tags: `FragmentedMp4Muxer` stores chapters in the Nero `chpl` box only, without a QuickTime chapter track
  (`TagEditor` adds one to its output). The keyed metadata of a track (`trak/meta`, which phones
  use for the video orientation and live photo data) is kept as it is, not read into its track tags. A binary keyed
  metadata item is kept while its key is unchanged and is not counted as a location, so
  `TagEditor.stripLocation` removes only the text and number items of the location keys (the ones phones write). When `TagEditor` replaces the chapter track of a fragmented file whose
  fragments carry that track's samples, the old track stays as a `text` track that no chapter reference names,
  since a track fragment must name a track of the movie; the fragments are not rewritten. The iTunes integer atoms
  `TagEditor` and `Mp4Muxer` know take decimal integers their width holds; other values are rejected, except by
  `TagEditor` for an atom the file already stores as text, which stays text.
- Timecode: `Mp4Muxer` and `FragmentedMp4Muxer` write a QuickTime timecode track, but a CMAF track file (one track)
  and `TagEditor` store a `timecode` tag as a free-form iTunes item (which `Mp4Demuxer.timecode()` reads when the
  file has no timecode track), and `TagEditor` keeps a file's timecode track as it is. The timecode sample of a fragmented file lasts its first fragment. A counter
  timecode track (`tmcd` flag 8) gives no timecode. Matroska and other formats store the timecode as a tag only.
- Chapters: hidden Matroska editions and chapters are not reported (the visible chapters nested in a hidden chapter
  are reported in its place, and the hidden chapters of a played ordered edition play), and a `TagEditor` edit that changes the chapters rewrites them without the hidden ones, the chapter UIDs,
  segment links and chapter processing commands. The formats other than Matroska store one edition of
  top-level chapters, without localized titles. A FLAC `CUESHEET` gives untitled chapters starting at each track's
  index point 1, its other index points, ISRC and catalog numbers are not read, and the cue sheet written from
  chapters (replacing the file's when an edit changes them) is not a CD-DA one; a WAV cue point's `note` entries are not read; AIFF markers carry no end, so a
  chapter ends where the next starts, and `COMT` comments are written back as `ANNO` chunks. A `smpl` chunk's
  sampler-specific data is dropped when the chunk is rewritten.
- Spherical video: a mesh projection is carried as the bytes of its `mshp` box, not interpreted. The MP4 `st3d`
  box stores the mono, top-bottom (left eye first) and side-by-side modes only, so the other stereo modes are
  written to Matroska only (and to WebM only those of the WebM specification); its stereo-custom layout is not
  read. The pose is carried as stored between MP4 and Matroska, in the directions of the Spherical Video V2 WebM
  binding.
- WebM: the muxer and `TagEditor` write the chapters of the default edition only, without edition flags, names or
  BCP 47 title languages, and add no attachment that is not a picture, as the WebM specification allows none of
  them.
- Track tags: MP4 stores `TrackDisposition.CAPTIONS` and `HEARING_IMPAIRED` as one `caption` role and
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
- Tags in transcodes: the tags of an input that is not local (`MediaSource.of(InputStream, long)`, a source over
  the network) are taken from its demuxer, which keeps one value per name, rather than read again from its tag
  structure.
- Tag vocabulary: the RIFF `INFO` identifier `ISRC` means the source, not the ISRC, and is read under the key
  `INFO:ISRC`. A rating that is not a number from 0 to 5 is stored in ID3v2 as a `TXXX` frame, not in `POPM`.
- Tag editing: the APE and ID3v1 tags `TagEditor` writes mirror what their formats hold (APE keys of printable ASCII,
  one picture per role), the ID3v2 tag holding everything. APE tags without a footer (a header-only tag at the start
  of a file) are not recognized. In FLAC files `TagEditor` keeps ID3v2 tags, leading or appended, without reading
  them, while the FLAC demuxer reports their fields. The offset of an ID3v2.4 `SEEK` frame is taken as the exact
  start of the next tag, while the specification makes it a minimum from which a reader may search.
- Streaming: the HLS demuxer does not decrypt `SAMPLE-AES` transport stream segments, rejects a media playlist whose
  initialization section changes (a discontinuity to another encoding), and reads an I-frame playlist of fragmented
  MP4 segments by the first sample of each range alone (timed by `tfdt` and the composition offset, without the edit
  list, not decrypted). The DASH demuxer reads each period as streams of their own (a representation continued
  across periods is not joined), does not resolve `xlink` references or `$SubNumber$`, and needs a `publishTime` to
  list the segments of a live representation addressed by duration. The packager writes HLS subtitles as WebVTT
  only (IMSC in fragmented MP4 is written for DASH), packages no bitmap subtitles or closed captions, addresses only
  the first key frame of each CMAF segment in I-frame playlists, and declares a growing live playlist's target
  duration as its longest segment so far, which changes if a later segment is longer.
- Encryption: keys are clear keys only; no digital rights management system is contacted and `pssh` data is not
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
  streams carry H.264, AAC, AC-3 and E-AC-3 only, as the format defines.
- MPEG-TS: of a LATM stream with several programs or layers only the first stream (program 0, layer 0) is exposed,
  and streams with the CELP or HVXC frame length types (3 to 7) are not exposed: Smalt has no CELP or HVXC decoder
  and their payload lengths come from those codecs' tables. Blu-ray linear PCM of 20-bit words is read as three-byte
  words whose low four bits are zero; no sample of it was available to confirm the layout (ffmpeg does not decode
  it). SMPTE ST 302 streams carrying non-PCM data (SMPTE 337) are returned as PCM samples. Program map changes after
  the probe window are found by reading the map near the end of the file and bisecting towards the probe window
  while it differs from the map in force, so a map that appears and is replaced by the one in force again is
  missed, and so is a change in a file whose last 32 MiB hold no map table; a stream read once that does not fit
  its memory budget exposes only the tracks of the probe window. AV1 temporal units that span several access units are grouped at their shown frame unless the stream
  carries temporal delimiters, so spatial layers without delimiters become one packet per layer.
- Ogg: a chained link that reuses a serial number of the link before it (which RFC 3533 forbids) is found in a file
  of up to 64 MiB, which is scanned, but in a larger file and in `TagEditor` only when bisection meets one of its
  first pages. A source of unknown size with random access is measured first and then read as a sized one; a
  stream read once describes only the first link unless it fits its memory budget (a read reaching a later link
  then fails with `MediaException.Unsupported`) and seeks only forward, and a track whose key frame before the
  target was already read restarts at its next one.
  Ogg Skeleton 4 `index` packets and the `fishead` presentation and base times are returned or kept but not used,
  and the packets of an unknown stream that precede its `fisbone` carry no timestamps.
- The constant-luminance, ICtCp and IPT-C2 matrices mix linear light, so with PQ or HLG a channel much darker
  than the other channels of its pixel (a PQ signal more than about 0.1 lower) does not survive quantization to
  10-bit Y'CbCr; their per-pixel conversion also costs several times the fixed-point matrices. The DNxHD frame
  header names only the BT.709 and BT.2020 color volumes, so the encoder signals every other matrix as an
  out-of-band color volume (3), left to the container. The default 8-bit mode of the video decoders rounds
  YCgCo-Re and YCgCo-Ro samples deeper than 8 bits like any others, which keeps 6 or 7 bits of R'G'B'
  (`highBitDepth` keeps them exact; AVIF stills and sequences decode to 8-bit RGBA instead). The H.264 (4:2:0 only),
  ProRes and Y'CbCr DNxHD encoders reject the identity matrix, which they cannot code or signal.
- H.264 decoding covers 4:0:0, 4:2:0, 4:2:2 and 4:4:4 video of 8 to 14 bits, lossless or not, progressive and
  interlaced, and the Extended profile's SP, switching SP and SI slices and slice data partitioning. Slice data
  partitions of a CABAC picture parameter set, which no profile allows, raise `MediaException.Unsupported`; a lost
  partition B or C leaves its macroblocks without residual (I_PCM ones mid-gray) and damages the picture. Redundant
  coded pictures are skipped, never used in place of lost primary data. Interlaced streams come out as woven
  frames, and a frame whose second field never arrives is output with that field mid-gray. 4:4:4 streams with
  matrix_coefficients 0 come out in their coded order (G, B, R in the Y, Cb, Cr planes), as `ChromaFormat.YUV444`
  with the identity matrix in the color description.
  H.264 multiview decoding covers the Multiview High, Stereo High, MFC High, Multiview Depth High and MFC Depth
  High profiles; the texture and depth views beyond the base view of 3D-AVC streams (Enhanced Multiview Depth
  High, ITU-T H.264 Annex I: view synthesis prediction, depth-based motion vector prediction, adaptive luminance
  compensation) raise `MediaException.Unsupported` once and the base view keeps decoding. H.264 scalable decoding
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
  second engine on the calling thread with I and P pictures only, one motion vector per macroblock, no look-ahead or
  adaptive quantization, and decide field or frame coding of a macroblock pair from the
  source rows alone; separate color planes are not coded. An 8-bit, 4:2:0, progressive, lossy CAVLC stream has no
  8x8 transform.
- HEVC field coded sequences come out as woven frames, two fields each, paired by the picture timing `pic_struct`
  (alternating parity from a top field when the stream sends none); a field left without a pair is output with
  the other field mid-gray. A track description derived from their parameter sets (MPEG-TS) gives the size
  and rate of one field. A stream that starts at a picture carrying a recovery point SEI message
  outputs nothing before the recovery point in output order; its first pictures predict from mid-gray stand-ins.
- Multi-layer HEVC: a stream whose base layer is coded outside it (hybrid scalability, `vps_base_layer_internal_flag`
  clear) decodes only as a HEIC `lhv1` item over an AVC-coded `exbl` base item; `HevcDecoder` raises
  `MediaException.Unsupported` for it, since a track carries no base layer pictures. 3D-HEVC (depth coding tools,
  the 3D-HEVC profiles) raises `MediaException.Unsupported`. An alpha layer of another size than its primary layer is
  resampled with the triangle filter. The HEIF `oinf` operating points property is accepted but not used; the
  output layer set comes from the item's `tols` property, and an `lhv1` item must carry its base layer or reference it
  through `exbl`.
- The H.264 and HEVC decoders render the frequency filtering film grain model with additive blending, bit-exactly
  as SMPTE RDD 5 specifies for 8-bit 4:2:0 progressive frames (ffmpeg's renderer gives the same samples).
  Interlaced frames, deeper samples and the 4:0:0, 4:2:2 and 4:4:4 formats get the same grain, extended (blocks
  averaged in the 8-bit domain, the intensity taken in units of the component's bit depth as the SEI semantics
  define it, 4:2:2 chroma scaled by 181/256 with only its horizontal cut frequency doubled as the VVC reference
  software does), a rendering no reference decoder offers to compare with; the
  auto-regression model, multiplicative blending and film grain models in another color domain are ignored, as
  are the low cut frequencies and color correlation of the model. Decoded picture hashes are checked on the
  samples without grain.
- The AV1 decoder's large scale tile mode decodes tile list OBUs only; the camera frames of libaom's light field
  encoder, whose tile data uses libaom's own column-size layout rather than tile groups, are not decoded as
  frames (their tiles reach the decoder through the tile lists built from them). Tiles of tile lists are decoded
  without CDF adaptation whatever the camera frame header's `disable_cdf_update` says, as libaom decodes them.
- With `Av1DecoderOptions.maxFrameDelay` above 1, a frame comes out up to that many packets minus one later,
  and a corrupt frame's error is raised by the call that would have returned it. With more than one thread,
  the ProRes, DNxHD, MPEG-4 Part 2 and VP8 decoders, like H.264, return frames during a later `decode` or
  `flush` call.
- Ambisonics: the ambisonic layout of a track survives only where a container can say it: Opus mapping families 2
  and 3 in every Opus container (the identification header), `SA3D` in MP4 and MOV for every component of an order in
  SN3D (N3D, Furse-Malham, mixed-order and partial sets are written without it), and the B-format sub-format in WAV for
  the Furse-Malham layouts only (also read from the `WAVEFORMATEXTENSIBLE` of AVI and Matroska `A_MS/ACM` tracks, and
  written for the Matroska 64-bit PCM that uses one). A codec that cannot code an ambisonic input layout, such as Opus
  for Furse-Malham or N3D input, gets the sound field converted to ACN order with SN3D before any stereo fallback. ACN layouts in WAV, other codecs than Opus in Ogg and Matroska, and every other
  container carry the channels without their meaning; BS.2076 ADM metadata (`axml`, `chna`) is kept as tags but not read as a
  layout, and the QuickTime `chan` higher-order ambisonics layout tags are not read or written. Opus mapping family 3
  in MP4 follows the convention Chromium reads, the RFC 8486 demixing matrix little-endian after the stream counts
  of `dOps`: Opus in ISOBMFF 1.0.0 has no field for it. `ChannelMixer` renders a sound field to loudspeakers from its
  first-order components only and has no binaural (HRTF) rendering.
- A surround Opus stream (5.1 and the other layouts of mapping family 1) at the automatic or maximum bitrate, or with a
  forced or maximum bandwidth, is coded at the bandwidth its bitrate and those settings allow, where libopus
  codes narrowband or ignores the settings; at an explicit bitrate it is packet-identical to libopus.
- Error resilient AAC (ER LC, ER LTP, LD, ELD) with the error protection tool (`epConfig` 2 and 3,
  `ErrorProtectionSpecificConfig`) raises `MediaException.Unsupported`: neither the tool's specification text nor
  a permissive implementation is available. Streams with `epConfig` 1 store each error sensitivity category
  instance in its own access unit (one MP4 track each); `AacDecoder.decode(List, Consumer)` decodes them from all
  their instances, while `AudioReader` and `AacDecoder.decode(Packet, Consumer)` raise
  `MediaException.Unsupported`; AAC-ELD with `epConfig` 1 is not supported: no conformance stream, specification
  text or permissive implementation places its TNS data and low delay SBR payload among the category instances.
  Concealment covers the RVLC scalefactors and the reordered spectral data; other corrupt side information
  raises `MediaException.Malformed`.
- The ER AAC LC, ER AAC LTP, AAC-LD and AAC-ELD conformance streams without SBR match their reference waveforms
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
- Spectral band replication or parametric stereo signaled around ER AAC LC, ER AAC LTP, AAC-LD or AAC-ELD
  (object type 5 or 29) is not decoded: the core decodes alone at its own rate, as is the case around a
  scalable AAC core, whose extension payloads are skipped. Scalable AAC over a CELP or TwinVQ core layer (the
  `ac04` to `ac20`, `er_ac2xx` and `er_ac3xx` streams of ISO/IEC 14496-26) raises `MediaException.Unsupported`,
  as Smalt has no CELP or TwinVQ decoder; the AAC-only scalable streams match their reference waveforms at
  every layer (noise substitution by band energy), whose long term prediction runs on the first layer alone.
  MPEG Surround (object type 30, ISO/IEC 23003-1) has no public conformance streams: its spatial decoder is
  checked against the decoder of the ISO/IEC 23003-3 reference software (which carries the full 23003-1
  decoder), bit for bit except the residual signals (within 0.002 of a 16-bit step, the reference dequantizes
  them in double precision), on synthetic streams of every tree and tool and on the reference encoder's streams.
  It upmixes the downmix of a Main, LC, SSR or LTP stream, not of the error resilient, BSAC or scalable object
  types; a spatial frame must last one block of the downmix, and the upmix starts at the first spatial frame
  whose `bsIndependencyFlag` is set; the arbitrary tree extension is decoded without its
  extra channels, and a 3D audio downmix leaves the downmix as the output; the binaural and blind upmixes of the
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
  ISO/IEC 23003-3 reference software bit for bit on synthetic frames; an element that uses it and switches to
  the linear prediction domain coder raises `MediaException.Unsupported`. eSBR transposes with the QMF based
  harmonic transposer, not with the DFT based one the reference decoder offers as an alternative. AAC Main
  prediction matches the ISO/IEC 14496-26 reference waveforms, which evaluate the predictor in double
  precision, so it drifts from decoders that use single precision, ffmpeg among them, by up to about -70 dB.
- Audio packet loss concealment (`AudioDecoder.conceal`) extrapolates from the signal decoded before the loss only:
  no decoder interpolates toward the packet after the loss, which would delay every output by a packet. After a
  loss, state the lost packets would have refreshed converges as the stream refreshes it: the AAC Main predictors
  and the long term prediction history, spectral band replication envelopes coded as time deltas of the lost ones,
  the CELP memories of AMR, the MPEG Surround parameters (the last ones stay in use until an independent spatial
  frame), and the dither and noise substitution generators of AC-3 and AAC, which the lost
  packets no longer advance, so even a Low Complexity stream with noise substitution differs from a loss-free
  decode in those bands. The Vorbis, MP2, AC-3 and E-AC-3 encoders cannot change their bitrate while running
  (`setTargetBitrate` raises `MediaException.Unsupported`), and USAC streams do not conceal (`conceal` raises
  `MediaException.Unsupported`).
- Encoders signal no sample aspect ratio of their own (the H.264 encoder's VUI says square unless
  `H264EncoderOptions.sampleAspectRatio` sets one, which transcodes do not), so a transcode
  that keeps non-square samples records the ratio in the container only, and a later copy into MPEG-TS or IVF,
  which have no field for it, loses it. AVI stores the number of fields per frame but not their order.
- WAV: unframed AAC of the error-resilient object types (ER AAC LC, LTP, scalable, LD and ELD) raises
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
- The ADPCM and telephony decoders follow the specifications and Microsoft's codecs, which ffmpeg does not
  everywhere: ffmpeg's WAVE IMA ADPCM decoder expands codes with a multiplication where the IMA recommendation and
  Microsoft's `imaadp32.acm` shift and add (about 55 dB apart); its Microsoft ADPCM decoder truncates the prediction
  toward zero where `msadp32.acm` rounds it down, which streams using the non-trivial coefficient pairs (Smalt's
  encoder picks them) reach (about 42 dB apart); its G.722 decoder keeps one bit more than the Recommendation's
  15-bit output; and its G.726 coder is not the Recommendation's (its codes differ from the Appendix II sequences at
  every rate, and its 40 kbit/s code assignment is incompatible: ffmpeg decodes standard 40 kbit/s streams, Smalt's
  included, with heavy distortion, and Smalt decodes ffmpeg's likewise). G.722's quadrature mirror filters, which the
  Recommendation leaves free in rounding and accumulator width, compute exactly and truncate.
- MACE 3:1 and 6:1 (AIFF-C and QuickTime `MAC3`, `MAC6`) are read and written by the containers but not decoded:
  the MACE patent (US 5,083,310) describes the structure but not the step, adaptation and quantizer tables, and no
  public description of them exists, so `AudioDecoders.create` raises `MediaException.Unsupported`.
- MPEG-4 Part 2 uses an IEEE 1180 inverse DCT, so it is close to, but not bit-exact with, other decoders; the
  Advanced Simple conformance streams match the ISO/IEC 14496-5 reference decoder within that drift.
  Interlaced direct mode follows ISO/IEC 14496-2 7.7.2.2, as the Windows Media Foundation decoder does;
  ffmpeg's encoder predicts those macroblocks as progressive direct ones, so its interlaced B-VOPs drift
  from what it meant (about 49 dB) wherever the backward reference has field-predicted macroblocks.
  Overlapped motion compensation follows ITU-T H.263 F.3, and matches the TMN10 reference encoder; ffmpeg's
  decoder takes some remote vectors differently, so H.263 Advanced Prediction streams differ from its decode
  by up to about a decibel.
  H.263 frames are handed out once the next I-, P-, EI- or EP-picture is decoded (or at flush), since B-pictures
  and the B-parts of PB-frames display before the picture that follows them. Improved PB-frames follow the
  Recommendation and the TMN test model, predicting the B-part forward with rounding type 0 and deblocking it with
  BQUANT; Intel's Draft 20 streams use the P-part's rounding type and quantizer, so their B-parts differ from
  Intel's decode by up to about 6 sample values (45 dB). Sorenson Spark is not supported. No encoder or
  conformance stream of the data partitioned slices of Annex V is available (the TMN test model and ffmpeg lack the
  mode, and H.263 has no conformance suite), so they are verified on streams of Smalt's encoder written from the
  Recommendation (`tools/testdata/h263-annexes`); the pictures of its B-picture and Improved PB-frame streams with
  Annexes I, S and T are those of unpartitioned twins that the TMN decoder reproduces exactly, so only the partition
  syntax itself rests on Smalt's reading. That reading takes three points the text leaves open the way the decoder
  does: LMVV repeats the last vector of the slice's prediction thread (P-vectors, including those of INTRA
  macroblocks of Improved PB-frames, and forward vectors), the start code emulation prevention of V.2.3.3 counts on
  through LMVV, and MVDB and MVDBW are coded in the vector partition after the thread vector of their macroblock.
- MPEG-4 Part 2 Studio profiles and fine granularity scalability raise `MediaException.Unsupported`: the Studio
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
- Motion-JPEG format B frames that take their quantization tables from the sample description (an `mjqt` box)
  are rejected as unsupported. AVI records no field order, so the two fields of an interlaced AVI frame are
  woven with the first field on top unless the track handed to the decoder declares a bottom-first scan;
  players commonly take the first field of an AVI frame as the bottom one.
- Motion JPEG 2000 frames decode at full resolution with every quality layer; an MJ2 file's `jp2p` and `jp2x`
  boxes are neither read nor written, and the MJ2 brand carries no PCM, whose MJ2 sound sample entries are not
  written. The `jp2h` box of an `mjp2` sample entry describes only the frames that carry no JP2 header of their own;
  ffmpeg reads the color space from the frames instead, so it decodes a raw-codestream 4:4:4 Y'CbCr track of an MP4,
  MOV or MJ2 file as RGB (Matroska and AVI frames carry the header, which the muxers add).
- JPEG 2000 decodes no Part 2 variable deadzone or trellis coded quantization, visual masking, single sample overlap
  transformations, precinct-dependent quantization, code-block anchor points or block coder extensions, nor the
  derived quantization of sub-bands split in one direction only (rejected as unsupported); JPX codestream fragments
  in other files are rejected too, and compositing layer extension boxes (`jclx`) are ignored, as ISO/IEC 15444-2
  allows. A JPX composition scales layers by sample replication, and an instruction set that repeats indefinitely
  stops after the first of its executions that assigns no new compositing layer. CIECAM97s J*a*b* samples convert to sRGB under
  the viewing conditions of the first worked example of the CIE TC1-34 report (D65 white, 318.31 cd/m^2, 20%
  background, average surround), since the file format signals none, reading a and b as `C cos h` and `C sin h`.
  No Part 2 encoder. The HTJ2K encoder codes one HT set per code-block,
  at the precision the last quality layer needs, so an earlier layer holds a code-block's cleanup pass,
  possibly without its refinement passes, or nothing of it. OpenJPEG 2.5.3 misreads layered HTJ2K output whose
  refinement passes follow a code-block's cleanup pass in a later layer, which a lossy last layer gives, and
  OpenJPH reads single-layer codestreams only. HTJ2K code-blocks of sub-bands with more than 30 magnitude
  bit-planes are not decoded (as in OpenJPEG) nor encoded, which limits lossless HTJ2K to channels of up to 27
  bits, and lossless coding with the ISO/IEC 15444-1 block coder is exact for channels of up to 24 bits only.
  The parameterized color specification of a JPH file is applied to full-range R'G'B' samples only, and others
  are ignored. A 12-bit Huffman-coded output of the lossless JPEG transforms always has optimized Huffman
  tables, and they reject frames of two or more than four components. 16-bit DCT JPEG, which T.81
  does not define, is rejected as unsupported.
- Hierarchical JPEG decodes at full size only (a size hint does not stop at a smaller frame), and the inverse DCT
  of its differential frames is the accurate integer transform without the level shift, whose output T.81 leaves
  to the decoder: its DCT frames decode within a few levels of the ISO/IEC 10918-7 reference software, which has
  its own transform, while its lossless frames decode exactly as J.1.1.2 upsamples their references. The
  reference software's encoder rounds those references its own way, so its lossless hierarchies decode one level
  off its source on some interpolated samples (a sample below zero is clamped to zero), and it takes the reference
  of a lossless frame over DCT frames from the DCT reconstruction before the clamp of T.81 A.3.1, so its samples
  near the ends of the range decode off its source by the excess. The lossless transforms reject hierarchical
  images.
- JPEG-LS mapping tables of entries of 4 to 255 bytes, and tables mapping the components of one frame to
  different precisions, are rejected as unsupported, since T.87 gives their entries no meaning; a SPIFF header's
  video-range Y'CbCr color spaces decode as full-range Y'CbCr, and its other color spaces (PhotoYCC, CMY, CIE
  Lab) are not converted. JPEG-LS part 2 (ITU-T T.870) streams are rejected as unsupported. The JPEG-LS encoder
  writes no mapping tables and no explicit MAXVAL. A line-interleaved JPEG-LS scan of subsampled components is
  read with the lines that complete its last minimum coded unit, as T.87 B.4 requires; a stream that leaves them
  out, as the ISO/IEC 10918-7 reference software writes one, decodes its last unit wrongly.
- The JPEG encoder writes no hierarchical frames (SOF5 to SOF7, SOF13 to SOF15) and uses only the accurate
  integer forward DCT (cjpeg's `-dct fast`, `-dct float` and `-smooth` have no counterpart) and the default
  arithmetic conditioning; its restart interval is the same number of MCUs in every scan, so cjpeg's
  `-restart` in rows, which counts each scan's own MCU rows, has no counterpart in a scan script that mixes
  interleaved and single-component scans.
- The APNG encoder writes 8-bit indexed, truecolor or truecolor with alpha frames only, so a deeper frame is
  written with 8 bits per sample.
- HEIF alpha images of another size than their color image are resampled with the triangle filter, which
  HEIF leaves to the reader, so their alpha can differ by a few levels from that of readers that resample
  otherwise. HEIC JPEG and JPEG 2000 items need `smalt-image` at run time and raise
  `MediaException.Unsupported` without it; JPEG 2000 items with signed samples, samples above 16 bits or
  four color channels are unsupported, and three unsubsampled color channels without a component transform,
  an sRGB or sYCC color specification or an `nclx` property are taken as RGB. Uncompressed (`unci`) items with
  signed, floating-point or complex components, components above 16 bits, palettes (`cpal`), filter arrays
  (`cpat`), CMYK components, blocks above 8 bytes, row interleaving with vertically subsampled chroma,
  multi-Y interleaving of 4:2:0, brotli compression or compressed units (`icef`) raise
  `MediaException.Unsupported`; a value stored in more bytes than its depth (`component_align_size`) is taken
  from the least significant bits. A grid whose inputs are derived images ignores the alpha its coded inputs
  carry themselves. A HEIF image decodes with the artifact of its coding, a derived image with that of its first
  input: one whose inputs mix codings needs both `smalt-mpeg` and `smalt-video`, and raises
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
- ANI: frames stored as raw data instead of icon files (an `anih` chunk without its icon flag) are
  unsupported, since no published document specifies their layout; frames are drawn at the top-left corner
  of the canvas, unscaled, and the reader does not report cursor hotspots (read them from
  `AniDecoder.frame` with `IcoDecoder.entries`). ANI files hold no metadata `ImageMetadata` reads.
- JPEG XL decoding reduces only a first displayed frame that replaces the whole canvas after LF and
  reference-only frames; a frame whose modular channels (an unsqueezed alpha channel, for example) arrive in
  its last pass decodes at full size under a size hint. The gain map metadata is handed over as stored by
  `JxlGainMap` and parsed by `JxlGainMap.toGainMap`. JPEG XL metadata editing writes plain (uncompressed) `Exif` and `xml ` boxes, since Smalt has no
  Brotli encoder; it cannot change the ICC profile, which is coded in the codestream header, and in a file
  with JPEG reconstruction data (`jbrd`) it refuses an EXIF or XMP change that alters the length the
  reconstruction records for the original JPEG's segment.
- JPEG 2000 metadata editing changes the color specification of the JP2 header only (not those of JPX
  codestream or compositing layer headers), cannot store comments (the codestream's `COM` segments are not
  rewritten) or the metadata of a raw codestream, and refuses to move the boxes of a file whose codestream is
  held in fragment tables, whose offsets would no longer point at it.
- The JPEG 2000 encoder's rectangle region of interest scales the coefficients of the code-blocks it covers
  in part, so a scaled coefficient must keep at most 25 magnitude bit-planes: channels of more than about 10 bits,
  lossless or lossy, are rejected with a rectangle; a whole-component region has no such bound. Its lossy output is
  not byte-identical to `opj_compress`'s, whose `-ROI` option only declares the shift: Smalt gives the region
  priority in the rate allocation. Progression changes are written as `opj_compress -POC` writes them, but when a
  change ends at a later layer than the change before it Smalt still writes the packets OpenJPEG 2.5.3 skips, and
  under a tile-part division Smalt writes each packet once where OpenJPEG 2.5.3 repeats or drops packets (it
  matches for a division by layer, and for a division by resolution of changes that split the components). A
  tile-part without packets gets no `PLT` segment, since a segment lists at least one length, where
  `opj_compress` writes an empty one.
- Gain maps: the backward direction flag of ISO 21496-1:2025 is rejected as unsupported, since neither a
  published text nor a sample defines its arithmetic; a gain map of another size than its image is resampled
  bilinearly at the pixel centers in the log2 domain, where libultrahdr interpolates by inverse distance
  weighting and libavif resamples the coded samples, so such files render within a few 10-bit code values of
  those libraries rather than within one. libavif converts the base to R'G'B' of its own depth before it applies
  the gain map, where Smalt keeps the precision of the decoded samples: from the same R'G'B' Smalt renders within
  one 10-bit PQ code value of `avifgainmaputil`, and from the Y'CbCr samples of an 8-bit base within one on
  average and 8 at most; libultrahdr's renderings match within one. A display at or
  below the base headroom shows the base rendition as it is, without the offsets, as libavif does when it skips
  the gain map; libavif and libultrahdr otherwise apply the equation with a zero weight, which differs only for
  offsets that differ. HLG rendering removes the BT.2100 system gamma on the luminance, where libultrahdr
  and libavif apply it per channel. Apple's gain maps are converted for their full headroom: Apple does not say
  how a display of less headroom applies them, so the ISO 21496-1 weighting of the log2 gains applies. The
  Ultra HDR writer leaves out the XMP form when the gain map applies in the alternate color space, which that
  form cannot say; readers that only read XMP then see no gain map.
- TIFF: interleaved subsampled YCbCr with extra samples is unsupported, since TIFF 6.0 defines data units of Y,
  Cb and Cr only and no writer defines where extra samples go; YCbCr above 16 bits per sample is unsupported;
  samples of different sizes, complex samples and L*a*b* other than 8- and 16-bit integers are read only from
  uncompressed, PackBits, LZW, Deflate, Zstandard or LZMA strips and tiles. PixarLog samples of 11 or 12 bits
  are unsupported (libtiff writes them in 16-bit words, inconsistently with their `BitsPerSample`), and so are
  the LERC codes 34888 and 34889, which Esri reserves without a definition, and JBIG
  entities that continue the layers or the prediction table of another entity. The TIFF encoder writes
  Zstandard; LZMA, LERC, JBIG, PixarLog, NeXT, ThunderScan, SGILog, old-style JPEG and L*a*b* pages are read
  only. It writes Y'CbCr pages at 8 bits without alpha, JPEG pages as 8-bit gray or Y'CbCr only (no CMYK, no
  alpha, no separate planes), and no return-to-control sequence after Group 3 data (as libtiff writes it).
  libtiff 4.7.1 itself misreads compression 32771 (word-aligned modified Huffman) rows that end inside the last
  byte its bit reader has loaded, so such pages, its own included, read correctly in Smalt but not always in
  libtiff.
- WebP: a target size is met by a search over whole encodes, so the file differs from `cwebp -size`; with more than
  one token partition at methods 3 to 6 the partitions are kept where libwebp's encoder writes one. libwebp's
  segment smoothing (`-pre 1`), partition limit, quality range of the PSNR search, JPEG-like size emulation and image
  hints are not offered, and the animation encoder takes the quality and method only.
- Pixel density: GIF's pixel aspect ratio byte, ICO and ANI entries, and the EXIF resolution of WebP, HEIC, AVIF
  and JPEG XL files are not reported as `DecodedImage.density()` (the EXIF block itself is returned). A density
  written to JPEG, PNG or BMP is rounded to the whole values of the field (dots per meter in PNG and BMP, so
  300 dots per inch reads back as 11811 dots per meter); BMP and JPEG 2000 store no aspect ratio, so the encoders
  leave one out.
- JPEG XL TIFF pages are read only, since Smalt has no JPEG XL encoder; WebP TIFF pages hold only interleaved
  8-bit RGB or RGBA, and JPEG 2000 TIFF pages no floating-point samples. Subsampled JPEG 2000 chroma in a TIFF
  page is replicated, not interpolated.
- BMP: no sample or oracle of `BI_CMYK` files exists, so their CMYK values are read in the byte order of the
  Windows `CMYK` color macro (black, yellow, magenta, cyan). Linked profiles (`PROFILE_LINKED`) are never opened,
  and the icon and pointer entries of OS/2 bitmap arrays are skipped. The encoder writes no delta codes, OS/2
  compressions, 64-bit or CMYK pixels, and a picture whose every pixel is fully transparent is read back as
  opaque, since readers take an alpha channel that is zero everywhere as unused. Metadata editing replaces or
  removes only the ICC profile, and not in bitmap arrays or OS/2 Huffman and RLE24 files.
- TIFF metadata editing changes only the first page's directory; it cannot store comments, text or an EXIF
  thumbnail, never moves the metadata of other pages, and reuses space an earlier edit freed in the middle of
  the file only while the edit still knows it as freed (zeros it left behind are reclaimed only at the end of
  the file, and not when a maker note, an entry of an unknown type or a `LONG` entry of an unrecognized tag,
  which may hold an offset, is kept). The same holds for DNG files and the camera raw files with a TIFF
  signature; ORF, RW2, CR3, RAF and MRW metadata is read only, and CRW and X3F files, which hold no EXIF block,
  have no metadata `ImageMetadata` reads.
- HEIF metadata editing changes the ICC profile of the primary item only (the tiles of a grid and the other
  items keep theirs), never adds or edits an `nclx` property (a high-dynamic-range profile is written in its
  ICC form), refuses image sequences without a primary item and tracks that locate items of their own (a
  track-level `meta` box with `iloc`), and fails when a moved 32-bit track offset no longer fits.
- Encoder metadata (`ImageEncoderOptions`): GIF, BMP, ICO and ANI files record no EXIF block and BMP, ICO and ANI
  no XMP packet, so these leave them out; GIF and ANI animations cannot record an orientation and reject one. TIFF
  and JPEG 2000 rebuild the EXIF block from its entries, which moves its values (a maker note with offsets relative
  to the block breaks), and leave out a block or an XMP packet that does not parse. An Ultra HDR JPEG
  (`JpegEncoder.encode(picture, gainMap, ...)`) rejects an XMP packet, which would compete with its own. An
  XMP packet a format has to parse and cannot is left out: by JPEG when it is too large for one segment, and by
  GIF when it holds a zero byte (a UTF-16 or UTF-32 packet, which GIF writes again in UTF-8 when it parses).
  The transcode leaves out of a JPEG output an EXIF block larger than one `APP1` segment (65527 bytes, after
  its thumbnail is removed) and an input's EXIF block that is not a TIFF structure, and its `STRIP_LOCATION`
  keeps maker notes as stored, so a position a manufacturer records inside one stays.
- EXIF: a fresh layout leaves out the entries of type `IFD` (13), whose sub-directories it does not hold;
  uncompressed thumbnails are written as one strip, and a lossless JPEG transform moves uncompressed thumbnails
  of 8 or 16-bit gray, RGB or 4:4:4 YCbCr samples and removes the others.
- Channel pictures: named-ink separations convert to RGB only through an ICC profile with one channel per ink
  (`nCLR`), so a TIFF separation of named inks without one does not decode to RGB; floating-point samples have
  no color transform (convert them with `ImageOps.toChannel16` first); only resampling, cropping, orientation and color
  transforms keep them. JPEG 2000 keeps only CMYK images (and their alpha) as channel pictures, and its default
  CMYK output is 8-bit and truncates as `opj_decompress` does. TIFF extra samples carry no meaning beyond
  alpha, so depth or spot channels are written and read back as unspecified extras, and 64-bit floating-point
  TIFF samples are narrowed to 32 bits.
- The AAC encoder places height loudspeakers only through channel configurations 13 and 14: its program config
  elements carry no height extension. ADTS headers have no field for 960-sample frames, channel configuration 0
  (program config element) or configurations 11 to 14, so the ADTS and MPEG-TS muxers refuse such streams; MP4 and
  Matroska carry them. Channel configuration 7 follows ISO/IEC 14496-3 (its first channel pair feeds the front left
  and right of center, the second the outside front pair), as Smalt's decoder reads it; ffmpeg 7.1 reads it as 7.1
  with front, side and back pairs, and outputs the channels of a program config element with a side pair in coding
  order without naming their loudspeakers.
- The MP3 encoder writes no intensity stereo and no mixed blocks. The MP2 encoder codes mono and stereo only (no
  ISO/IEC 13818-3 multichannel extension). The decoder outputs the main programme of a multichannel Layer II stream:
  the second stereo programme (`3/0 + 2/0`, `2/0 + 2/0`) and the multilingual channels are not output, and the
  extension of Layer I streams (three Layer I frames per extension) and of Layer III streams is not decoded, so
  such streams play their `Lo/Ro` base. A raw MP2 file carries no gapless information (the LAME tag is a Layer III
  frame).
- E-AC-3 at the reduced sampling rates of 16 to 24 kHz (ATSC A/52 Annex E) is valid but little supported: ffmpeg 7.1 reports it
  as not implemented, and neither Windows Media Foundation nor Core Audio (`afconvert`) has a decoder for it.
  ffmpeg 7.1 decodes only programs with one dependent substream, and puts the side channels of a program whose
  dependent substream adds channels without repeating them (9.1, 7.1.2) in the wrong place; Windows Media
  Foundation renders the 5.1 core of the programs beyond 7.1. ffmpeg rejects every audio block coded with
  enhanced coupling.
- The VP9 real-time encoder's speeds 5 and 9 trail libvpx (no reference partition search, no rate-distortion
  key-frame intra search). The VP9 good-quality encoder codes one layer of alternate references and prunes
  no partitions with libvpx's neural networks; it measures 10 and 12-bit pictures' first pass on their 8-bit
  rounding. The AV1 good-quality speeds `SLOW`, `SLOWER` and `SLOWEST` take much longer than
  `BALANCED` and `FAST` for compression within measurement noise.
- The AV1 encoder's screen content tools code no intra block copy, and luma palettes only in the frames of the
  real-time usage and the inter frames of good quality; the real-time usage detects no screen content
  (`Av1ScreenContent.DETECT` codes as `NEVER` there; use `ALWAYS` for screen sharing). Super-resolution takes one
  fixed denominator (libaom's `fixed` mode, without its `qthresh` and `auto` choices per frame) and leaves the
  good-quality key frames at the picture width. A good-quality encoder cannot change its picture size, and a
  real-time one starts a key frame when a reference is more than twice the new coded size, and a new sequence header
  beyond the first size. The decoder model times follow a fixed schedule (half-second buffer delays, one removal per
  frame interval) that is not checked against the buffer model of the stream's level.
- FLAC in Matroska and Ogg written to a stream (or as live Matroska) keeps the initial `STREAMINFO`, so the final
  length and MD5 reach only files on a seekable sink; likewise an ALAC track in such Matroska output and in
  fragmented MP4 keeps a configuration whose largest packet size and average bitrate are zero (unknown). DNxHD
  and DNxHR premultiplied alpha is written in band only, and the sample descriptions Smalt writes carry the
  `AALP` extension of Avid's but not its `ACLR`, `APRG` and `ARES` ones (kept on remux).
- ALAC codes each channel count in one fixed speaker arrangement, so the channels of another layout decode as the
  arrangement's free speakers in input order: side surrounds as back surrounds, and the side pair of 7.1 as front
  left and right of center. The ALAC encoder codes 16, 20, 24 and 32-bit samples only (the decoder reads 4 to 32);
  32-bit float input holds 24 bits of precision. ffmpeg 7.1 cannot decode a 32-bit channel pair sent uncompressed,
  which Apple's encoder, and so Smalt's, writes for incompressible 32-bit audio such as full-scale noise; Core Audio decodes them.
- The exact audio outputs (`SampleFormat.INTEGER` and `DOUBLE`) keep the codec's rate and layout: a part of a stream
  that changes either mid-way is converted through floats, as the float output converts it, and quantized to the
  output's sample size. 64-bit integer PCM has no integer output, and its doubles round it to 53 bits. An ALAC
  channel pair of 32-bit samples that keeps every byte (33-bit side information, which Apple's encoder never writes)
  raises `MediaException.Unsupported`. The `STREAMINFO` MD5 check needs a decode from the stream's first frame, so a
  read that seeks first is not checked.
- Sample processing: the Lt/Rt downmix is the ATSC A/52 matrix, which sums the surrounds without the 90 degree
  phase shift some matrix surround encoders apply. The variable-ratio resampler keeps the anti-aliasing filter of
  the nominal rates, so a ratio far from the nominal one (it accepts a factor of two) aliases more than a resampler
  built for the new rates; clock drift stays far below that. The noise-shaped PCM dither uses a filter designed for
  44.1 kHz at every rate. The true-peak limiter aims 0.02 dB under its ceiling, and a lossy encoder that follows it
  can add overshoot of its own. Single-pass loudness normalization levels changes slower than a few seconds and
  raises pauses by a few decibels before it holds its gain; the two-pass normalization keeps the dynamics.
- The MP4 demuxer reads the compressed boxes of ISO/IEC 14496-12 clause 8.20 (`!mov`, `!mof`, `!six`, `!ssx`)
  and QuickTime compressed movies (`cmov` with the `zlib` algorithm, the only one QuickTime writes; another raises
  `MediaException.Unsupported`), and writing the tags of such a file stores its movie box uncompressed; a tag
  write that would move the data of compressed movie fragments with explicit base data offsets raises
  `MediaException.Unsupported`. The demuxer rejects the FEC reservoir and hint tracks of 3GPP MBMS files (`mdhd`
  timescale 0) as malformed.
- Samples and HEIF items stored in other files are read only through a `DataReferenceResolver` given in
  `DemuxerOptions` or `ImageDecoderOptions` (none by default, so such files raise `MediaException.Unsupported`); the
  data reference kinds that name no file Smalt can describe (`imdt`, `snim`, QuickTime `rsrc`) are unsupported.
  `DataReferenceResolver.directory` opens only files inside its directory, an alias by its file name. Only the
  demuxers opened with `DemuxerOptions` (`Mp4Demuxer`, `Containers.openDemuxer`) and the HEIC and AVIF still image
  decoders resolve references; probing, `AudioReader`, `VideoReader`, `SubtitleReader`, thumbnails, remuxing,
  transcoding and image sequences (the movie of an AVIF or HEIC file) do not. A movie read once from a stream,
  one of whose tracks keeps samples both in the file and in another one, is held in memory from its first sample
  on, since the demuxer cannot tell which of its bytes are no longer needed.
- Matroska virtual timelines: chapter processing commands (`ChapProcess`), `ChapterTranslate` and `TrackTranslate`
  are not run, and hidden editions cannot be played. The linked segments are opened when the demuxer is created,
  through a resolver the caller supplies (none by default; the file's directory for `MatroskaDemuxer.open` and
  `Transcoder.transcode(Path, ...)`), and a chapter whose segment it does not supply plays nothing. A segment that
  names neither a segment before it nor one after it is presented alone, even when another file names it as its
  predecessor. The tracks of a linked segment feed the file's own tracks of the same number, kind and codec, or of
  the same kind and codec in order; a linked track with another sample rate keeps its packets' timestamps on the
  timeline but is decoded as the file's own track. Packets flagged `PacketFlag.DISCARD` (the decode-only frames
  before and after a part, and the audio frames before a part that rebuild the decoder state) and the audio packets
  a part cuts are carried by the muxers as the MP4 edits below describe. The audio cut at a part boundary follows
  the block timestamps, and the codec delay (the Opus pre-skip, a `CodecDelay`) is dropped once, at the start of
  the decode: the parts after the first start up to that delay (6.5 ms for a usual Opus stream) off their place. A
  seek restarts the audio at its frame straddling the target, without the decode-only frames before it, except a
  seek before the timeline, which gives the packets of a read from the start. A seek that lands within a part of
  another segment than the file's own gives its first packet the codec configuration of that segment as
  `PacketSideData.newCodecConfig`, so the packets after a seek are not always those a read from the start gives
  from there.
- MP4 edits play at normal speed whatever their media rate other than 0. A dwell edit of an audio track, or
  an empty edit after its first media edit, leaves a gap in the packet timestamps, and `AudioReader` and an
  audio re-encode join the samples on either side of it instead of filling it with silence. Packets flagged
  `PacketFlag.DISCARD` (the decode-only pre-roll of a multi-segment edit list) are written as presented samples
  by every muxer but Matroska (which marks them invisible), and the audio packets an edit cuts lose their discard
  start in every muxer and their discard end in every muxer but Matroska for Opus and AAC (`DiscardPadding`), so a copy
  of such a track into another container outside `Mp4Remuxer` shows the pre-roll and plays the cut audio whole; the transcoder re-encodes it correctly. After an edit that goes back in
  the media, an AAC object type that predicts from the start of the stream (Main, LTP, LD, ELD with low delay
  SBR) converges during its pre-roll instead of matching a decode of the whole stream. A fragmented MP4, Ogg, WAV, AIFF or subtitle file cannot store a codec configuration change,
  and an MP4 sample description of another codec than the track's first is rejected when a sample uses it.
- Subtitles: TTML documents decode to text cues; the images of IMSC image profile documents are not decoded, and
  a paragraph of a document that defines regions but selects none is not shown, as TTML specifies. A cue's
  placement is written to 3GPP timed text only under a sample entry with a default text box (a `tbox` is
  measured in a region, which the default sample entry, with an empty box, leaves to the track size the track
  model does not carry). Automatic encoding detection covers the encodings listed under Subtitles; others need the charset
  option. SAMI files are written in UTF-8, MicroDVD and LRC files without the styles a line only partly has.
- Bitmap subtitles and captions: bitmap subtitles are copied, never encoded, and MP4 carries VobSub only (no sample
  entry exists for PGS or DVB subtitles). A DVB or PGS cue keeps its regions whole; the PGS cropping rectangle is
  applied (ffmpeg 7.1 shows the whole object). The X/26 mosaic and DRCS characters of teletext are not modeled, and
  the Arabic teletext sets follow the glyph tables of EN 300 706 alone (the ffmpeg builds at hand have no teletext
  decoder to compare with). DVB character-coded objects (whose character table EN 300 743 leaves to a local
  agreement) are not drawn, and the disparity and alternative CLUT segments of stereoscopic and HDR services are
  ignored. CEA-708 text keeps its italic, underline, colors and window anchor; edge styles, fonts, opacities, window
  borders, justification, scrolling and the delay commands are not modeled. Roll-up and paint-on CEA-608 cues start
  at their first character, where ffmpeg's `cc_dec` starts them at the end of the previous cue. MPEG-1 and MPEG-2 video tracks are recognized (their captions
  are read) but neither decoded nor muxed.
- MP4 TTML (`stpp`): the image resources an IMSC image profile document references through
  sub-samples stay inside the sample, without the `subs` box that delimits them. ffmpeg (7.1) cannot write
  fragmented TTML; it reads Smalt's.
- MP4 muxing: a fragmented file with a
  single segment index of more than 65535 fragments indexes them hierarchically (`sidx` boxes referencing
  `sidx` boxes), which ffmpeg (through 2026 builds) refuses to open; DASH players read it. ffmpeg applies no
  edit list to a fragmented file, reports no duration for the text samples of a fragment and has no WebVTT
  in MP4 reader. PCM tracks keep no gaps between packets, and 8-bit PCM, A-law and mu-law have no ISO sample
  entry, so only QuickTime files carry them; M4A files carry no PCM (a transcode into M4A encodes it).
- HDR metadata: MP4 has no box for the chroma siting, and Matroska cannot express a siting on the bottom luma
  row (only its horizontal position is written). An MP4 `mdcv` or `clli` box without a `colr` box is read
  into a color of unspecified matrix, primaries and transfer, so a remux writes it back with an unspecified
  `colr`. HDR10+ messages (`PacketSideData.hdr10Plus`) are written only to Matroska and WebM; the MP4 muxers
  leave them out (H.265 and AV1 streams carry HDR10+ in band, in SEI and metadata OBUs; VP9 HDR10+ needs
  WebM or Matroska). The Matroska
  track entry declares the HDR10+ `BlockAdditionMapping` only for a PQ track or one whose first message is
  queued when the header is written; the messages of any other track (live output of a track without PQ color)
  are written without it, which WebM readers of VP9 accept (WebM assigns `BlockAddID` 4 to ITU-T T.35).
- Dolby Vision: the decoders show the base layer only. The pictures of a stream without a backward compatible
  base layer (profile 5, 10.0) are not reconstructed from its RPUs (reshaping, IPTPQc2 to RGB); such a stream is
  refused unless `VideoDecoderOptions.dolbyVisionBaseLayer` decodes its base layer with wrong colors. The
  enhancement layer of profile 7 (in band, NAL unit type 63, or a second track or PID) is not applied, and the
  in-band check of an undeclared profile 5 stream covers HEVC only (AV1 RPUs in ITU-T T.35 metadata OBUs are
  not read); it skips multi-layer streams, since the RPU headers of profile 20 (MV-HEVC) read as profile 5.
  WebM files and SAMPLE-AES transport streams carry no Dolby Vision configuration, and HLS playlists name a
  backward compatible stream by its base codec only (no `SUPPLEMENTAL-CODECS`).
- HDR color management converts HLG through the BT.2100 reference display of 1000 cd/m2 (system gamma 1.2),
  whatever the peak of the display the content was graded on, and transforms from or to a PQ or HLG profile
  are computed in single-precision floating point at 16 bits, not sampled like Little CMS's. The ICC form of
  a PQ or HLG profile carries no mastering display or content light level, so a profile read back from it
  assumes a 1000 cd/m2 content peak; its tone curves show neutral colors as Smalt tone-maps them, but colors
  only approximately, to readers that ignore the `cicp` tag. Little CMS 2.17 writes and reads a `cicp` tag of
  16 bytes rather than the 12 of ICC.1:2022: Smalt reads both and writes the standard one, which Little CMS
  2.17 does not see.
- ICC chains: a named color profile can only start a chain, and its 8-bit transform reaches only the first 256
  colors; a high-dynamic-range profile can only be converted to or from one other profile. Two cases differ from
  Little CMS 2.17 on purpose: a device link read through its `D2B0` fallback (no `D2Bn` of the intent) has its XYZ
  or L*a*b* ends scaled like any floating-point table, and the colors of a named color profile with an XYZ
  connection space are read as XYZ, where Little CMS reads them as L*a*b*.
- Camera raw: Nikon HE and HE* files and PEF files
  without a Huffman table, the green-first MRW files of the DiMAGE A200, CRW streams that use a Huffman
  code no CC0 sample has shown (set 1, used by the EOS D30 and the PowerShot G1 and G5, lacks the most) and the
  frames of CR3 raw burst rolls (CRX encoding type 3) are not decoded, and the second photodiode image of a dual
  pixel CR3 file is not exposed; CRX images of more than two tile columns or of several tile rows, and C-RAW images of
  codec version 2 in more than one tile, which no CC0 sample shows, are decoded by the rules of the files that do; the lens distortion and chromatic aberration
  correction Adobe DNG Converter applies to the PowerShot compacts' CR3 files (as a WarpRectilinear opcode) is not
  applied, so their renderings keep the lens's barrel distortion; the Nikon E5700 and the PowerShot G1 carry no color matrices, so they render only with a twelve-value
  `RawDecoderOptions.colorMatrix` (for example from an Adobe DNG Converter file of the model); RW2 version 8 gain and offset tags
  other than the identity are ignored. Cameras outside the 52 profiled models (Minolta's and the CRW bodies
  among them) use a generic color matrix, and Sony white balance, and that of the CRW files without white
  balance levels, is estimated from the embedded preview. The uncompressed and Polaroid x530 variants of the older
  X3F coding are verified only on synthetic files (no CC0 sample shows them), Sigma's noise reduction is not
  reproduced, and Merrill files render darker and greener than the camera's own preview, which Sigma's
  processing brightens and desaturates.
- After `AudioReader.seek`, AMR output converges to that of a decode from the start over a few hundred
  milliseconds, and AC-3 and E-AC-3 dither differs, since the dither generator continues across frames;
  the other codecs except Opus and AAC give the same samples bit for bit.
- The pitch analysis of the voice detector of `GainControl.MANAGED_ANALOG` reads zeros where WebRTC reads
  outside the bounds of its correlation surface, so a frame with a pitch peak at its border may get another voice
  probability than WebRTC gives it.
- Over HTTP, fragmented MP4 needs one request per fragment, and Matroska with its `Tags` at the end two extra
  requests. `AudioReader.seek` on Matroska Vorbis or AAC, and on AAC Main, LTP, LD and ELD with SBR in any
  container, restarts from the start of the track (exact, but slower), and AAC seeks are bit-identical only
  without perceptual noise substitution.
- The capture backends run on the Windows, macOS and Linux CI runners against virtual devices (loopback audio
  pairs, the runners' displays, `v4l2loopback` and `vivid` cameras on Linux), except the Windows and macOS
  cameras: no camera can be provisioned on those runners, so the camera capture of those two systems is not
  run there.
- Camera controls on macOS are the focus, exposure and white balance modes (continuous or locked) and, where
  the device offers it, the zoom: AVFoundation has no manual values for the others on macOS, and no
  power-line frequency. Windows camera controls are those the driver exposes through `IAMCameraControl` and
  `IAMVideoProcAmp`; the exposure of a Windows camera steps in powers of two. macOS reports cameras and screens
  plugged in or removed by listing them every second (cameras every five seconds), so its events follow a change
  within that interval. The Linux camera controls and camera hotplug are tested against the `vivid` and
  `v4l2loopback` drivers (and `v4l2-ctl`); the Windows camera controls only against simulated `IAMCameraControl`
  and `IAMVideoProcAmp` interfaces, and the macOS ones not at all, since no camera can be provisioned on those CI
  runners, where cameras plugged in or removed are not tested either.
- Window capture on X11 without the Composite extension reads the window itself, so the parts other windows
  cover hold whatever the X server returns; window capture on macOS needs ScreenCaptureKit (macOS 12.3), and the
  screen snapshots of older macOS versions never show the pointer. Screen and window capture of a Wayland session
  is not implemented (it needs the screen cast portal and PipeWire).
- Container providers: the transcoder writes only the `OutputContainer` profiles, so a container a provider adds
  that `ContainerFormat` does not name (`ContainerFormat.OTHER`) is a transcode input but not a transcode output;
  `Containers.openMuxer(String, MediaSink)` writes it.

---

## Install

Smalt targets **Java 26**. Import the BOM, then depend on the artifacts you need.

| Artifact | Contents |
|---|---|
| `smalt-core` | The media model, the codec registries, every container, image and audio processing, loudness, subtitles, PCM, tag editing and XMP |
| `smalt-image` | JPEG (and its lossless transforms), JPEG-LS, JPEG 2000, PNG, GIF, WebP, BMP, TIFF, ICO, ANI, JPEG XL, EXIF and image metadata editing, Motion-JPEG, Motion JPEG 2000, and VP8 (lossy WebP is VP8) |
| `smalt-audio` | Opus, Vorbis, FLAC, ALAC, MP3, and voice processing |
| `smalt-video` | VP9, AV1, AVIF, ProRes and DNxHD/DNxHR (see the [licensing note](#licensing-of-prores-and-dnxhd)) |
| `smalt-mpeg` | H.264, HEVC, HEIC, AAC, AMR, MPEG-4 Part 2, H.263, AC-3 and E-AC-3, the codecs licensed through patent pools or by Dolby |
| `smalt-capture` | Camera, screen and microphone capture, and speaker playback |
| `smalt-raw` | DNG and camera raw decoding: Canon, Nikon, Sony, Fujifilm, Olympus, Panasonic, Pentax, Minolta and Sigma raw files, demosaicing and camera color rendering |
| `smalt-desktop` | ImageIO readers and writers and Java Sound providers for the formats the other artifacts handle |
| `smalt-aot` | Training workloads for JDK ahead-of-time caches, used only in the training run (see [Start fast with an AOT cache](#start-fast-with-an-aot-cache)) |

Every artifact depends on `smalt-core` only (`smalt-capture` and `smalt-raw` also on `smalt-image`, `smalt-aot`
on every codec artifact, `smalt-capture` and `smalt-raw`). Codecs are discovered at run time: a format whose
artifact is missing fails with a `MediaException.Unsupported` naming the artifact to add. `smalt-desktop`
names no codec either: it serves, through `javax.imageio` and `javax.sound.sampled`, whatever the codec
artifacts next to it handle.

**Maven**

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.github.auties00</groupId>
            <artifactId>smalt-bom</artifactId>
            <version>0.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.github.auties00</groupId>
        <artifactId>smalt-image</artifactId>
    </dependency>
</dependencies>
```

**Gradle (Kotlin DSL)**

```kotlin
implementation(platform("com.github.auties00:smalt-bom:0.1.0"))
implementation("com.github.auties00:smalt-image")
```

**Gradle (Groovy DSL)**

```groovy
implementation platform('com.github.auties00:smalt-bom:0.1.0')
implementation 'com.github.auties00:smalt-image'
```

> **_NOTE:_** Smalt is not on Maven Central yet. Until the first release, build it from source with
> `mvn install -DskipTests` and use version `0.1.0-SNAPSHOT`.

### JVM flags

- `--add-modules jdk.incubator.vector` turns on the SIMD kernels. It is optional: without it every kernel
  runs its scalar twin, correct but several times slower for video. `MediaRuntime.isVectorized()` tells
  you which one you got.
- `--enable-native-access=com.github.auties00.smalt.capture` (or `ALL-UNNAMED` on the class path) is
  needed only by `smalt-capture`.
- `-XX:AOTCache=app.aot` starts with an ahead-of-time cache recorded from a training run; see
  [Start fast with an AOT cache](#start-fast-with-an-aot-cache).

---

## Quickstart

### Probe a file

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

### Edit tags

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

### Strip location from a photo

```java
ImageMetadataEditor.apply(Path.of("photo.jpg"), ImageMetadataEdit.stripLocation());

var metadata = ImageMetadata.read(Path.of("photo.jpg"));   // EXIF, XMP, IPTC, ICC, comments
var clean = ImageMetadataEditor.apply(Files.readAllBytes(Path.of("scan.png")),
        ImageMetadataEdit.stripAll().toBuilder().orientation(Orientation.ROTATE_90).build());
```

The edits rewrite only the metadata segments, chunks, blocks or items of JPEG, PNG, WebP, GIF, HEIC and AVIF
files and copy the compressed pixels byte for byte. `stripLocation()` removes the EXIF GPS directory, the XMP coordinates and
place names and the IPTC place fields; `stripAll()` removes every kind but the ICC profile, which defines the
colors. EXIF edits keep the maker note and every unchanged value at its offset and zero what they remove.

### Strip location from a video

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

### Take a video thumbnail

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

### Make an image thumbnail

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

### Decode a region of a large image

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

### Edit a photo

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

`ImageFilters` (Gaussian and box blur, unsharp mask, convolution, median), `ImageAdjustments` (brightness,
contrast, saturation, levels, curves, auto levels, grayscale, sepia, tint, invert, threshold),
`ImageCompositing` (14 blend modes, opacity, linear light, watermarks) and the geometry of `ImageOps`
(resampling, rotation, affine transforms, flips, trim, extend, pad, flatten) work on 8 and 16-bit pictures; on
8-bit pictures the blurs, unsharp mask, median, enhancements and autocontrast reproduce Pillow's output exactly.

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

### Show Display P3, Adobe RGB and CMYK photos correctly

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

`Images.encode` records the profile with the pixels, so a picture decoded without conversion keeps its colors
when it is encoded again, and `metadataOf` carries the EXIF block and XMP packet along:

```java
var decoded = Images.decode(MediaSource.of(Path.of("iphone.heic")));                 // Display P3 pixels
var options = ImageEncoderOptions.builder().quality(85).metadataOf(decoded).build();  // profile, EXIF, XMP
var jpeg = Images.encode(decoded.picture(), ImageFormat.JPEG, options);              // Display P3 JPEG
```

### Transcode

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

### Measure loudness and normalize

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

### Convert subtitles

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

### Remux to WebM

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

### Turn a GIF into an animated WebP

```java
try (var source = MediaSource.of(Path.of("clip.gif"));
     var reader = Images.openAnimation(source);
     var output = MediaSink.of(Path.of("clip.webp"));
     var writer = Images.openAnimationWriter(ImageFormat.WEBP, reader.width(), reader.height(),
             ImageEncoderOptions.ofQuality(75).toBuilder().loopCount(reader.loopCount()).build(), output)) {
    for (var frame = reader.read(); frame != null; frame = reader.read()) {
        writer.write(frame);
    }
    writer.finish();
}
```

Any animation `Images.openAnimation` reads (GIF, APNG, animated WebP, or a still image as one frame) can be
written this way, and `ImageFormat.GIF` or `ImageFormat.PNG` (APNG) work in place of `ImageFormat.WEBP`. For
settings beyond quality and looping, use `WebpAnimationEncoder`, `GifAnimationEncoder` (palette size,
quantization method, dithering, palette sharing, interlacing) or `ApngEncoder` (compression, filters, a fallback image)
with their options directly:

```java
var encoder = new GifAnimationEncoder(width, height, GifEncoderOptions.builder()
        .dithering(Dithering.ORDERED).maxColors(128).loopCount(0).build());
encoder.addFrame(frame, 4); // delays in hundredths of a second
byte[] gif = encoder.finish();
```

### Read audio as PCM

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

### Write audio to a file

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

### Encode FLAC

```java
// flac -8 song.wav (24-bit input): the transcode keeps the source's sample size
Transcoder.transcode(Path.of("song.wav"), Path.of("song.flac"),
        TranscodeOptions.of(OutputContainer.FLAC).toBuilder().audio(AudioTranscodeOptions.encode(Codec.FLAC)).build());

// Or drive the encoder: integer samples, libFLAC's presets, a native file with MD5 and seek table
var options = FlacEncoderOptions.ofCompressionLevel(8).toBuilder()
        .format(96_000, ChannelLayout.STEREO).bitsPerSample(24).build();
var encoder = new FlacEncoder(options);
try (var muxer = new RawMuxer(MediaSink.of(Path.of("song.flac")))) {
    muxer.addTrack(AudioTrack.builder().codec(Codec.FLAC).timeBase(Rational.timeBaseOf(96_000))
            .codecConfig(encoder.codecConfig().orElseThrow()).sampleRate(96_000).build());
    encoder.encode(samples, 0, samples[0].length, muxer::writePacket);     // int[channel][sample]
    encoder.flush(muxer::writePacket);
    muxer.updateCodecConfig(0, encoder.codecConfig().orElseThrow());      // final STREAMINFO: length, MD5
    muxer.finish();
}
```

The output matches `flac` 1.4.3 in size at every level (within 0.01% on music and speech). Apple
Lossless (ALAC) tracks in MP4 and Matroska decode through `AudioReader` like any other codec, and `AlacEncoder`
writes them the same way: `encode(int[][], ...)` or floats, `flush`, then `updateCodecConfig` with the final
`ALACSpecificConfig`; its packets are byte-identical to those of Apple's reference encoder.

### Decode video frames

```java
try (var source = MediaSource.of(Path.of("clip.mp4"));
     var reader = VideoReader.open(source)) {
    VideoFrame frame;
    while ((frame = reader.read()) != null) {
        var picture = frame.picture(); // valid until the next read()
    }
}
```

### Write video to a file

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
round (see [the DNxHD notes](#video)). RGB input is coded as BT.709 Y'CbCr macroblocks, the form
other encoders' files use and ffmpeg decodes with the right colors; on the options' builder,
`alternateColorSpace(false)` codes the RGB samples themselves, `alpha(true)` codes the picture's alpha in band
(`losslessAlpha(true)` for DNxHR 444, `premultipliedAlpha(true)` for premultiplied video) and
`chromaFormat(ChromaFormat.YUV420)` codes 4:2:0 DNxHR; ffmpeg decodes neither. With
`alphaStorage(AlphaStorage.AVID_UNCOMPRESSED)` or `AVID_CODED` the alpha follows each frame as Avid's
QuickTime encoders store it, and `DnxhdDecoder` reads it back.

### Remux to MP4 without re-encoding

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

### Read a .ts

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

### Package HLS

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

### Walk the packets of any container

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

### Read from a buffer, a memory segment or a stream

```java
var buffer = MediaSource.of(byteBuffer);     // position to limit; a heap buffer is read in place
var mapped = MediaSource.of(segment);        // native or heap; global, auto or shared arena; over 2 GiB is fine
try (var upload = MediaSource.of(request.getInputStream(), contentLength);  // -1 when the length is unknown
     var reader = AudioReader.open(upload)) {
    // ...
}
var lazy = MediaSource.of(() -> openStream(url), -1);   // opened on the first read, closed by close()
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

### Read the pages of a TIFF and write one

```java
try (var source = MediaSource.of(Path.of("scan.tif"));
     var pages = Images.openPages(source)) {          // a multi-page TIFF, or the entries of an icon
    for (var i = 0; i < pages.pageCount(); i++) {
        DecodedImage page = pages.read(i);            // RgbaPicture, or Rgba16Picture for 16-bit pages
    }                                                 // with highBitDepth(true) in ImageDecoderOptions
}
byte[] tiff = TiffEncoder.encode(List.of(first, second),
        TiffEncoderOptions.deflate().toBuilder().tiles(256, 256).build());
byte[] fax = TiffEncoder.encode(scan, TiffEncoderOptions.fax().toBuilder()      // bilevel, CCITT Group 4
        .density(PixelDensity.ofDotsPerInch(300)).build());
byte[] icon = IcoEncoder.encode(logo, IcoEncoderOptions.windowsSizes());   // 16 to 256 pixels in one file
try (var writer = Images.openPageWriter(ImageFormat.TIFF, sink)) {         // or ImageFormat.ICO
    for (var i = 0; i < pages.pageCount(); i++) {
        writer.write(pages.read(i));                  // keeps orientation, ICC, density, EXIF and XMP
    }
    writer.finish();
}
```

`TiffDecoder.openPages` also describes each page (`TiffPages.page(i)`: compression, layout, photometric
interpretation, pixel density, ICC profile) and `IcoDecoder.entries` each icon or cursor entry (size, depth,
hotspot).

### Rotate, crop or recompress a JPEG losslessly

```java
byte[] upright = JpegTransforms.autoOrient(MediaSource.of(Path.of("photo.jpg"))); // EXIF orientation applied
byte[] rotated = JpegTransforms.rotate(MediaSource.of(Path.of("photo.jpg")), 90);
byte[] smaller = JpegTransforms.transform(source, JpegTransformOptions.builder()
        .crop(new Region(64, 32, 800, 600))
        .progressive(true)                              // coefficients moved, never re-quantized
        .build());
```

The DCT coefficients are moved and mirrored block by block, so nothing is lost; partial edge blocks are
trimmed by default, and `JpegTransformOptions.jpegtran()` reproduces `jpegtran`'s output byte for byte.
`JpegTransforms.wipe` grays out a region and `JpegTransforms.drop` pastes another JPEG image into one, both
without touching the rest of the image.

### Decode and encode JPEG 2000

```java
DecodedImage image = Images.decode(MediaSource.of(Path.of("scan.jp2")));   // JP2, JPX, JPH or a codestream
Jp2Image raw = Jp2Decoder.decodeChannels(MediaSource.of(Path.of("ct.j2k")), // every component, 1-38-bit,
        Jp2DecoderOptions.builder().reduction(2).build());                  // at a quarter of the size
byte[] lossless = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossless());
byte[] layered = Jp2Encoder.encode(picture,
        Jp2EncoderOptions.lossy(20).toBuilder().compressionRatios(80, 40, 20).build());
byte[] jph = Jp2Encoder.encode(picture,                                     // HTJ2K (Part 15), a JPH file
        Jp2EncoderOptions.lossless().toBuilder().highThroughput(true).build());
byte[] dcp = Jp2Encoder.encode(frame12Bit, Jp2EncoderOptions.forCinema2k(24)); // a DCI 2K frame
byte[] served = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossy(30).toBuilder()
        .regionOfInterest(new Jp2RegionOfInterest.Rectangle(100, 80, 200, 150)) // the face first
        .tilePartDivision(Jp2TilePartDivision.RESOLUTION).tlmMarkers(true).pltMarkers(true)
        .exif(exif).xmp(xmp).build());
byte[] tagged = ImageMetadataEditor.apply(served, ImageMetadataEdit.keepAll().toBuilder()
        .iptc(iptc).build());                                               // uuid boxes, codestream untouched
try (PageReader layers = Jp2Decoder.openPages(MediaSource.of(Path.of("stack.jpx")));  // JPX compositing layers
     AnimationReader movie = Jp2Decoder.openAnimation(MediaSource.of(Path.of("movie.jpx")))) { // its composition
    DecodedImage second = layers.read(1);
    AnimationFrame first = movie.read();
}
```

### Develop a camera raw file

```java
DecodedImage photo = Images.decode(MediaSource.of(Path.of("IMG_0001.CR2")));   // 8-bit sRGB, like a camera JPEG
RawInfo info = RawDecoder.info(MediaSource.of(Path.of("DSC_0001.NEF")));       // camera, ISO, exposure, previews
DecodedImage linear = RawDecoder.decode(MediaSource.of(Path.of("photo.dng")),  // 16-bit linear Rec. 2020
        RawDecoderOptions.linear().toBuilder().whiteBalance(RawWhiteBalance.daylight()).build());
DecodedImage thumb = Images.decode(MediaSource.of(Path.of("DSC_0001.ARW")),    // from the embedded preview
        ImageDecoderOptions.ofMinimumSize(320, 240));
RawImage sensor = RawDecoder.decodeRaw(MediaSource.of(Path.of("photo.dng")));  // the CFA samples, linearized
```

### Use Smalt from ImageIO and Java Sound

Add `smalt-desktop` next to the codec artifacts and existing code that uses the JDK APIs picks the formats
up, with no Smalt types in sight:

```java
BufferedImage image = ImageIO.read(new File("photo.webp"));  // WebP, APNG, AVIF, HEIC, JPEG XL, ICO, ANI, JP2, DNG, raw
ImageIO.write(image, "webp", new File("copy.webp"));
ImageIO.write(image, "avif", new File("copy.avif"));
ImageIO.write(image, "smalt-gif", new File("copy.gif"));          // quantized and dithered by Smalt
ImageIO.write(image, "smalt-tiff", new File("copy.tif"));         // the JDK keeps "tiff"
ImageIO.write(image, "smalt-bmp", new File("copy.bmp"));         // the JDK keeps "bmp"

try (var encoded = AudioSystem.getAudioInputStream(new File("song.mp3")); // Opus, Vorbis, FLAC, MP3, AAC
     var pcm = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, encoded)) {
    // 16-bit PCM, ready for a SourceDataLine or a Clip
}
```

Animated images read as one image per frame (`ImageReader.getNumImages(true)`) and write as animated WebP,
APNG (format name `apng`) or GIF (format name `smalt-gif`; the JDK keeps `gif` and `png`) through the writer's
sequence API (`prepareWriteSequence`, `writeToSequence`, `endWriteSequence`), keeping the
frame delays of Smalt and GIF metadata. `ImageWriteParam` selects lossy quality or lossless, and
`AudioSystem.write` writes Ogg Opus (`SmaltAudioFormats.OGG_OPUS`). Smalt's TIFF reader and writer are
opt-in by the format name `smalt-tiff` (the JDK keeps `tiff` and `ImageIO.read`), and the reader exposes every
page of a multi-page TIFF as an image index; Smalt's BMP reader and writer are opt-in by `smalt-bmp` the same way,
the reader exposing every bitmap of an OS/2 bitmap array as an image index; icons and cursors read and write under
`ico`, JPEG 2000 under `jpeg2000` and `jp2`, and camera raw files (with `smalt-raw`) read under `dng` and `raw`, ahead of the
JDK's TIFF reader for the TIFF-structured ones.
`BufferedImages` and `SampledAudio` convert between Smalt's pictures and samples and `BufferedImage` and
PCM bytes. The providers work on the class path and on the module path.

### Code Opus in real time

```java
var encoder = new OpusEncoder(OpusEncoderOptions.forVoip(48_000, 1).toBuilder()
        .inbandFec(true).packetLossPercent(10).build());
var decoder = new OpusDecoder(OpusDecoderOptions.of(48_000, 1));
var packet = new byte[OpusEncoder.maxPacketBytes()];
var length = encoder.encode(pcm, 0, 960, packet, 0, packet.length);     // one 20 ms frame of 16-bit samples
encoder.setBitrate(24_000);                                             // retarget while the stream runs
var samples = decoder.decode(packet, 0, length, out, 0, 960);           // interleaved 16-bit output
decoder.conceal(out, 0, 960);                                           // a packet that never arrived
```

Real time is a preset, not a separate class: the same `OpusEncoder` also buffers `AudioBuffer`s into
timestamped packets for a file (`OpusEncoderOptions.defaults()`, `forSpeech(bitrate)`), surround
included (`builder().format(48_000, ChannelLayout.SURROUND_5_1)` codes a 5.1 multistream with its LFE
stream; `channelMapping(OpusChannelMapping.DISCRETE)`, `AMBISONIC` or `AMBISONIC_PROJECTION` picks another
mapping family), and the same `OpusDecoder` decodes an Opus track, mixing it down to stereo or mono when its
options ask for `channels(2)` or `channels(1)`. `OpusPackets` inspects, merges and splits packets without
decoding them.

### Code any audio codec in real time

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
silence. `supportsConcealment()` tells whether a decoder implements it (every decoder of the table below does, except
for USAC streams);
`setTargetBitrate` mirrors `VideoEncoder.setTargetBitrate` and throws `MediaException.Unsupported` where the codec
has no live bitrate (FLAC, ALAC, PCM, Vorbis, MP2, AC-3, E-AC-3), and `IllegalStateException` in a
constant-quality mode (MP3 variable bitrate).

| Codec | Concealment unit | What replaces a lost packet |
|---|---|---|
| Opus | 2.5 ms | the libopus pitch-based and noise-based concealment, identical to zero-length packets |
| AAC (every object type except USAC), HE-AAC, HE-AAC v2, MPEG Surround | one block (`frameLength()`) | the last spectrum with random signs, falling 6 dB per block and silent from the fifth; the high band repeats its last envelope; MPEG Surround upmixes with the last spatial parameters |
| MP3, MP2 | one frame | the last subband samples at half amplitude, halved again per frame and silent from the fifth, as for a frame failing its CRC |
| Vorbis | half the last block | a block of the last size with the last spectrum under random signs, halved per block and silent from the fifth |
| AC-3, E-AC-3 | one access unit | the last good coefficients of each channel with a fade, dependent substreams included |
| AMR-NB, AMR-WB | one 20 ms frame | the bad frame handling of TS 26.091 and TS 26.191, or comfort noise during DTX |
| PCM, G.711, FLAC, ALAC | one sample | the last pitch periods repeated and faded to silence over 60 ms, then overlapped with the next packet (ITU-T G.711 Appendix I) |

| Encoder | `setTargetBitrate` |
|---|---|
| Opus | the explicit bitrate of `setBitrate`, from the next packet, in every bitrate mode |
| AAC | constant bitrate: the exact average from the next frame, the bit reservoir kept; variable bitrate: the nominal bitrate of the quality; the automatic bandwidth follows |
| MP3 | constant bitrate: the version's bitrate at or below the target; average bitrate: the new target, steered from the next frame by the bitrate of the last quarter second; the automatic bandwidth follows |
| AMR | the mode at or below the target, from the next frame |

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

### Capture the camera

```java
var camera = VideoDevices.defaultCamera().orElseThrow();
try (var session = VideoDevices.openCapture(camera)) {
    var frame = session.read(); // blocks until the next frame
    var picture = frame.picture();
}
```

> **_IMPORTANT:_** Every operation is a plain blocking call: there is no `CompletableFuture` anywhere in the
> API. Run long work on your own (virtual) threads; Smalt parallelizes each operation internally on one
> bounded pool, sized by the `smalt.threads` system property (`MediaRuntime.parallelism()` reports it). Options
> that take a thread count use `0` for that shared pool and `1` for the calling thread only.

### Capture a window or a part of a screen

```java
var window = VideoDevices.windows().getFirst();            // front to back
var options = ScreenCaptureOptions.builder()
        .frameRate(30)
        .region(0, 0, window.width() / 2, window.height())  // the left half, in the window's pixels
        .cursor(false)                                      // leave the pointer out
        .build();
try (var session = VideoDevices.openCapture(window, options)) {
    var frame = session.read();
}
```

A camera session also adjusts the camera (`session.controls()`: exposure in microseconds, white balance in
kelvin, power-line frequency in hertz, focus, zoom, pan, tilt and the picture controls, each with its range and
automatic mode), and `VideoDevices.addListener` reports cameras and screens as they are plugged in, removed or
rearranged, on a thread of its own.

### Capture the microphone and play to the speaker

```java
var options = AudioStreamOptions.forVoice(); // 16 kHz mono, 10 ms periods
try (var microphone = AudioDevices.openCapture(AudioDevices.defaultInput().orElseThrow(), options);
     var speaker = AudioDevices.openPlayback(AudioDevices.defaultOutput().orElseThrow(), options)) {
    var frame = new short[160];
    while (microphone.read(frame, 0, 160) == 160) { // blocks until 10 ms were captured
        speaker.write(frame, 0, 160);              // blocks only while the queue is full
    }
}
```

`AudioDevices` lists inputs and outputs (id, name, default flag, native formats), opens them in the shared
or exclusive mode at any rate and channel count, captures what an output plays (`openLoopback`, Windows), and
reports new default devices and devices plugged in or removed to an `AudioDeviceListener`. Each session runs
its own high-priority device thread; `read` and `write` move float or 16-bit samples through a bounded queue,
and `delayMicros()` on both sides gives the stream delay of an echo canceller such as `VoiceProcessor`. An
unplugged device fails the next `read` or `write` with `MediaException.Io`.

### Start fast with an AOT cache

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

> **_IMPORTANT:_** On stock JDK 26 to 28 the cache does not remove the Vector API's cold start. A JVM
> that resolves the incubating `jdk.incubator.vector` module does not archive its boot layer, so the
> cache keeps parsed classes only, without linked classes, profiles or compiled code (`-Xlog:aot` prints
> `archivedBootLayer not available` while recording and `Using AOT-linked classes: false` in production):
> the first seconds of real-time work still run uncompiled vector code, with correct output and a slow
> start. The full benefit needs a JDK built from the Leyden `premain` branch with the Vector API patches,
> [Auties00/leyden `premain-vector-aot`](https://github.com/Auties00/leyden/tree/premain-vector-aot), whose
> caches also store compiled vector code: there real-time coding runs compiled kernels from its first frame.
> Use JDK 26.0.2 or later: earlier builds do not notice a changed class path and run stale cached classes.

---

## Performance

Single thread, SIMD on, measured against the native libraries on one desktop (i7-9700K, AVX2). The figure is
Smalt's time relative to native: lower is better, 1.0 is parity.

| Workload | Native reference | Smalt |
|---|---|---|
| H.264 decode, 720p and 1080p | FFmpeg | 1.7-1.8x |
| HE-AAC decode (SBR) | FFmpeg | about 1.0x |
| AMR-WB decode | FFmpeg | about 1.3x |
| HEVC decode, 720p and 1080p, x265 medium | FFmpeg | 1.9x (1.5x on all threads) |
| VP9 decode, 720p and 1080p | libvpx | 1.9x (1.2-1.5x on all threads) |
| AV1 decode, 720p | libaom / dav1d | 1.3x / 2.8x (1.5x dav1d on 8 threads with a frame delay of 8) |
| AVIF decode, 2.5 MP, 1 / 8 threads | dav1d | 3.2x / 1.9x |
| AVIF encode, speed 6, 720p / 12 MP (BD-rate 0-7% lower) | libaom 3.12 all-intra | 3.1x / 4.1x |
| AVIF encode, speed 6, 2.5 MP, 1 / 8 threads (BD-rate 2% PSNR-Y, 5% SSIM-Y lower) | libaom 3.12 all-intra cpu-used 6 | 2.9x / 2.0x |
| AV1 real-time encode, 720p, 1 / 8 threads (BD-rate 3.7% PSNR-Y higher) | libaom realtime cpu-used 9 | 1.8x / 1.3x |
| AV1 good-quality encode, 720p, `FAST`, 1 / 8 threads (BD-rate 15% PSNR-Y, 19% SSIM-Y higher) | libaom 3.12 good cpu-used 6 | 3.0x / 3.1x |
| VP8 real-time encode, 480p and 720p (BD-rate 2.6% PSNR-Y, 2.9% SSIM-Y lower) | libvpx | 1.4x |
| VP9 real-time encode, 480p and 720p (BD-rate 14% PSNR-Y, 16.5% SSIM-Y lower) | libvpx | 1.8-2.7x |
| ProRes 422 HQ decode / encode, 1080p | FFmpeg (prores, prores_ks) | 2.0x / 0.08x (encode 0.7x of FFmpeg's faster `prores` encoder, 1 thread) |
| DNxHR HQ decode / encode, 1080p | FFmpeg (dnxhd) | 2.1x / 0.8x (1 thread), 0.9-1.0x (8 threads) |
| MPEG-4 Part 2 ASP decode, 720p | FFmpeg | about 2x (1 thread; about 3x on 8 threads) |
| VP8 decode, 720p | libvpx / FFmpeg | about 1.7x (1 thread) |
| Opus encode, 60 ms frames | libopus | 0.8-1.1x |
| AC-3 / E-AC-3 decode, 5.1; E-AC-3 7.1 Atmos bed | FFmpeg | 1.3x / 1.3x; 1.5x |
| AC-3 / E-AC-3 encode, 5.1 at 448 / 384 kbit/s (with channel coupling) | FFmpeg | 2.6x / 2.5x |
| Voice processing (AEC3 + noise suppression), 10 ms frames | WebRTC APM | about 1.0x |
| ICC transforms, 12 MP: Display P3 to sRGB / RGB lookup table / CMYK to sRGB, 8-bit | Little CMS 2.17 | 0.3x / 0.9x / 0.7x |
| TIFF decode, 2048x1536 RGB, LZW / Deflate with predictor | LibTIFF 4.7 (tiffcp, libdeflate) | 0.9x / 1.6x |
| TIFF encode, same image, LZW / Deflate with predictor | LibTIFF 4.7 (tiffcp, libdeflate) | 0.8x / 2.7x |
| Image operations, 12 MP: box blur / bicubic rotation / resize + unsharp mask / 3x3 median | Pillow 12.3 | 0.5x / 0.4x / 0.5x / 0.01x |
| Image operations, 12 MP: Gaussian blur sigma 2 / bicubic rotation / 3x3 median | libvips 8.18 | 4-5x / 0.7x / 0.01x |
| JPEG 2000 decode, 2048x1536 RGB, lossless 5-3 / lossy 9-7 at 20:1 (loaded machine, wide error bars) | OpenJPEG 2.5.3 (opj_decompress) | about 1.2x / 0.3-0.9x |
| JPEG 2000 encode, 8 threads | OpenJPEG 2.5.3 (opj_compress) | 0.3-0.5x |
| JPEG XL decode, VarDCT / modular | libjxl (djxl) | 6.6-8x / 1.9-2.4x |
| GIF encode with a palette search and dithering (8% smaller, 1.6 dB better) | FFmpeg (palettegen, paletteuse) | about 0.4x |
| Opus decode | libopus | about 0.85x |
| MP3 decode / encode at 128 kbit/s / encode at V2 | FFmpeg (mp3float) / LAME | about 1.1x / 0.83x / 0.6x |
| Vorbis encode | libvorbis | about 1.1x |
| FLAC encode, level 5 / level 8 | libFLAC (flac) | about 0.85x / 1.3x |
| ALAC decode | FFmpeg | about 1.1x |
| Loudness measurement (EBU R128, true peak) | FFmpeg (ebur128) | 0.2-0.3x |
| MPEG-TS demux | FFmpeg (`-c copy`) | about 1.25x |

Multi-threaded decoding narrows the gap where the stream allows it (tiles, slices, frame parallelism).

Still images, 12 MP photo, against FFmpeg 7.1 (libwebp, zlib) on the same machine, ratio of times: lossy WebP encode
(q75, method 4, byte-identical to libwebp) 1.7x on one thread and 0.4x on eight; PNG encode (zlib level 6) about 0.6x on one
thread (Smalt's own deflate compressor, 0.4x of the JDK zlib's time at level 6) and 0.3x on eight, the PNG split into independently deflated bands of about 1 MiB; PNG decode 0.6x on one thread
and 0.5x on eight; lossless WebP encode (2.5 MP) about 1.1x on one thread and 0.8x on eight. Eight-thread figures come from
same-JVM interleaved comparisons combined with the quiet-machine FFmpeg times, and carry the noise of a shared
machine.

Absolute numbers, effects of a few percent and the comparison with FFmpeg and the other native libraries need
dedicated hardware: one quiet machine with a fixed clock, both sides run in the same session with the same
thread counts, ratios reported rather than times. The hosted CI runners are shared virtual machines whose speed
drifts from run to run and with the CPU model a job lands on, so CI measures only what holds within one job
([tools/bench](tools/bench)): a weekly A/B of the hot paths, base and head built side by side and timed fork by
fork, that fails when a case gets 20% slower, and, on every pull request, the bytes allocated per operation
against the base, which do not depend on the machine's load. The figures in this section come from the desktop
above, not from CI.

Some workloads do not speed up with threads, and some cost memory worth knowing about:

- Lossless WebP decoding is one sequential entropy stream, and VP9 decoding on several threads is bounded by
  the serial parse of the slowest tile column (frames do not overlap).
- Under saturation the shared pool splits the processors fairly between operations, so short operations see
  a higher tail latency.
- Speculative parallel JPEG decoding uses up to about 7.7 MB more per 12-megapixel image, parallel animated
  GIF decoding up to about 50 MB per 720p animation, and JPEG XL decoding about 130 bytes per pixel.
- On hardware whose vectors are 128 bits wide (ARM NEON and 128-bit SVE, such as Apple silicon and Graviton;
  x86 without AVX2), the kernels written for 256-bit vectors run their scalar twins, since the JIT compiler
  cannot compile their operations there: the H.264 decoder's motion compensation, weighted prediction, 8x8
  transform and deblocking, the H.264 encoder's pixel metrics, transforms and interpolation, the HEVC,
  MPEG-4 Part 2 and H.263, VP9, AV1 (decoder and encoder) and VP8 encoder kernels, the VP8 decoder's
  transform, interpolation and normal loop filter, the JPEG DCTs and color conversion, the JPEG 2000
  wavelets, the ProRes and DNxHD block transforms, palette search and SILK's fixed-point filters. The kernels
  that gather (Vorbis's post-rotation) do the same on ARM NEON, which has no gather instructions. The other
  vector kernels use 128-bit vectors there, the tap sums of rotations and affine warps included (their taps
  are gathered by scalar code).
- Until the JIT compiler has compiled them, Vector API kernels allocate on every call; three-layer
  error-resilient VP9 at speed 5, for example, allocates 0.2 to 0.8 MB per frame until its transform kernel
  is compiled. An AOT cache that stores compiled vector code removes that phase (see
  [Start fast with an AOT cache](#start-fast-with-an-aot-cache)).

---

## Documentation

Every public and protected member of the exported packages carries a Javadoc comment stating its contract, so
this README only covers the essentials. For anything not spelled out here, refer to the [Javadoc][javadoc]. If a
format or feature you need is missing, open an [issue][issues] or start a [discussion][discussions].

---

## Acknowledgments

Smalt stands on decades of work that others chose to share. Thank you to every author and maintainer below:
your code, your specifications and your test material made a pure-Java media library possible, and any
mistake in Smalt's reading of them is Smalt's own.

**Ported or derived code.** Smalt translates or closely follows the code of these projects, most of them
written in C, and is grateful for their permissive licenses, which made that possible:
[libopus](https://opus-codec.org/) and its modified [KISS FFT](https://github.com/mborgerding/kissfft),
[libwebp](https://chromium.googlesource.com/webm/libwebp),
[libvpx](https://chromium.googlesource.com/webm/libvpx),
[libjpeg-turbo](https://libjpeg-turbo.org/) and the [Independent JPEG Group](https://ijg.org/)'s software,
[libvorbis](https://xiph.org/vorbis/) (with its aoTuV tuning), [libogg](https://xiph.org/ogg/) and
[libFLAC](https://xiph.org/flac/) of the Xiph.Org Foundation, [libgsm](https://www.quut.com/gsm/) of Jutta
Degener and Carsten Bormann (Technische Universitaet Berlin), the stereo downmix matrices of
[opusfile](https://opus-codec.org/),
[Pillow](https://python-pillow.github.io/), [OpenH264](https://github.com/cisco/openh264), the
[JM](https://vcgit.hhi.fraunhofer.de/jvet/JM) and [HM](https://vcgit.hhi.fraunhofer.de/jvet/HM) reference
software of the H.264 and HEVC standards (HM with its SCM screen content coding branch), the film grain synthesizer of the
[VTM](https://vcgit.hhi.fraunhofer.de/jvet/VVCSoftware_VTM) reference software of VVC, [fdlibm](https://www.netlib.org/fdlibm/),
[WebRTC](https://webrtc.googlesource.com/src)'s audio processing module (as packaged by
[webrtc-audio-processing](https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing)), Takuya
Ooura's [FFT package](http://www.kurims.kyoto-u.ac.jp/~ooura/fft.html), Julien Pommier's
[PFFFT](https://bitbucket.org/jpommier/pffft), [RNNoise](https://gitlab.xiph.org/xiph/rnnoise),
[musl](https://musl.libc.org/),
[dav1d](https://code.videolan.org/videolan/dav1d), [libaom](https://aomedia.googlesource.com/aom),
[libavif](https://github.com/AOMediaCodec/libavif), [giflib](https://sourceforge.net/projects/giflib/),
Anthony Dekker's NeuQuant, [Little CMS](https://www.littlecms.com/), the PacketVideo AAC decoder, the
VisualOn AAC encoder and [libxaac](https://github.com/ittiam-systems/libxaac) of the
[Android Open Source Project](https://source.android.com/),
[LibTIFF](https://libtiff.gitlab.io/libtiff/), Apple's [ALAC](https://github.com/macosforge/alac),
[libebur128](https://github.com/jiixyj/libebur128),
[smartcrop.js](https://github.com/jwagner/smartcrop.js),
[opencore-amr and vo-amrwbenc](https://sourceforge.net/projects/opencore-amr/),
[OpenJPEG](https://www.openjpeg.org/), [OpenJPH](https://github.com/aous72/OpenJPH),
[OpenHTJ2K](https://github.com/osamu620/OpenHTJ2K), [CharLS](https://github.com/team-charls/charls),
[jxl-oxide](https://github.com/tirr-c/jxl-oxide),
[libjxl](https://github.com/libjxl/libjxl), [libultrahdr](https://github.com/google/libultrahdr),
[Brotli](https://github.com/google/brotli),
[colour-demosaicing](https://github.com/colour-science/colour-demosaicing) of the Colour Developers,
Sjon Kosse's [arw](https://github.com/Soreil/arw), Roland and Erik Karlsson's
[X3F tools](https://github.com/Kalpanika/x3f), the camera data of
[rawtoaces-data](https://github.com/AcademySoftwareFoundation/rawtoaces-data),
[libdeflate](https://github.com/ebiggers/libdeflate), Esri's [LERC](https://github.com/Esri/lerc), Julian Seward's
[bzip2](https://sourceware.org/bzip2/), whose decompressor Smalt's follows, and Jack Andersen's
[lzokay](https://github.com/jackoalan/lzokay), whose LZO1X decompressor Smalt's follows,
[Shaka Packager](https://github.com/shaka-project/shaka-packager), whose subsample rules, slice header reading
and SAMPLE-AES details Smalt's Common Encryption follows (Chromium's encrypted test media and the MPEG file
format conformance suite's protected files check its decryption), the Microsoft MPEG-4 Visual reference
software of ISO/IEC 14496-5, whose decoder of the shape, sprite, N-bit and scalability tools Smalt's
MPEG-4 object decoder is a port of, and quietvoid's
[dovi_tool](https://github.com/quietvoid/dovi_tool), whose dolby_vision crate Smalt's Dolby Vision RPU parser
follows and whose test RPUs check it. The same
thanks go to the projects whose code Smalt
follows under licenses that ask for nothing in return: [minimp3](https://github.com/lieff/minimp3), whose
decoder Smalt's MP3 decoder is a port of, [stb](https://github.com/nothings/stb),
[libpng](http://www.libpng.org/pub/png/libpng.html), [zlib](https://zlib.net),
[XZ for Java](https://tukaani.org/xz/java.html), whose LZMA decoder and branch filters Smalt's follow,
Wilco Dijkstra's integer
square root in WebRTC, and Nicolas Devillard's median networks.

**Specifications and reference implementations.** Much of Smalt is written from the specifications alone,
and the reference implementations next to them settled every question the text left open: the ITU-T and
ISO/IEC standards for JPEG, JPEG 2000, JPEG XL, H.263, H.264, HEVC, MPEG-4 Part 2, MPEG audio, AAC, the ISO
base media file format, HEIF and MPEG-TS, with the ISO MPEG Audio Software Simulation Group's dist10
distribution (the ISO/IEC 13818-5 reference software, whose Layer I and II encoder and multichannel decoder Smalt's
follow), the ISO/IEC 23003-3 reference software of MPEG-D USAC (with the MPEG Surround, SBR, ER BSAC and scalable
AAC decoders it includes, which Smalt's follow) and the ISO/IEC 23003-4 reference software of MPEG-D DRC; the [Alliance for Open Media](https://aomedia.org/)'s AV1 and AVIF specifications and its carriage of AV1 in MPEG-2 TS; the VP8
(RFC 6386) and VP9 bitstream specifications; Google's [Spatial Audio RFC](https://github.com/google/spatial-media)
(the `SA3D` box and its ambisonic conventions) and Chromium's reading of Opus mapping family 3 in MP4; the IETF's
Opus, Ogg and AMR payload RFCs (RFC 8486 for ambisonics), and its CELLAR
working group's Matroska specifications; 3GPP's AMR and AMR-WB reference code; ITU-T G.722 and G.726 and ETSI EN
300 961 (GSM 06.10) with their digital test sequences (the G.726 ones as the ITU-T Software Tools Library
distributes them), the IMA ADPCM recommendation, Microsoft's ADPCM formats and Apple's `ima4`; ATSC A/52 and ETSI TS 102 366
for AC-3 and E-AC-3; Dolby's carriage of Dolby Vision in the ISO base media file format and in MPEG-2 TS, with the
Matroska block addition mappings; SMPTE RDD 5 (film grain), RDD 36 (ProRes), ST 2019-1 (VC-3) and ST 302 (AES3 audio in MPEG-2 TS); ANSI/SCTE 35 (splice information); ITU-T T.82 (JBIG); RFC 8878 and the
[Zstandard](https://github.com/facebook/zstd) format documentation with its golden test files; the .xz file
format; Adobe's TIFF and
[DNG](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/digital-negative.html)
specifications, EXIF, XMP, IPTC and the ICC profile format; the W3C's PNG, WebVTT, TTML and IMSC, and Compositing and
Blending specifications; 3GPP TS 26.245 (timed text) and Apple's QuickTime File Format; the SubStation Alpha v4.00+
script format, with [libass](https://github.com/libass/libass) settling how players read scripts; RFC 8216 and MPEG-DASH, with the DASH-IF interoperability guidelines for trick play and live presentations; ITU-T H.273, ITU-R BT.2100, BT.2390 and BS.1770, ISO 21496-1, the Adobe gain map specification, Google's Ultra HDR
image format and Apple's description of its HDR gain map, CIPA DC-007, and EBU R 128
with EBU Tech 3341 and 3342, EBU Tech 3306 (RF64), ATSC A/65 and the MPEG-DASH role scheme for track metadata, and
the tag mapping of [MusicBrainz Picard](https://picard-docs.musicbrainz.org/)'s documentation for the native names of
the tag vocabulary; the CIE TC1-34 report on CIECAM97s, with its worked examples, as Mark Fairchild
publishes it, for the CIECAM97s J*a*b* of JPX files.
For the camera raw formats, Canon's CIFF specification, Sigma's FOVb (X3F) specification, Laurent Clevy's
CR2 and CR3 write-ups, Canon's patent application US 2016/0323602 A1 on the CRX wavelet coding, Bill Claff's notes,
Henry Dietz's description of Sony's cRAW,
[ExifTool](https://exiftool.org/)'s tag documentation and the [raw.pixls.us](https://raw.pixls.us/) archive of CC0 sample files made the clean-room derivations possible,
and Wyman, Sloan and Shirley's analytic fit of the CIE color matching functions serves the DNG
reader's spectral illuminants.
Apple's and Microsoft's TrueType and OpenType specifications describe the fonts the subtitle renderer reads,
Felzenszwalb and Huttenlocher's distance transform draws its outlines, ITU-R BT.2087 and BT.2408 guide the
color conversion of transcodes, and Doyle and Looymans' edge-based line average deinterlaces.
libopusfile's page search and link bisection, libvips' entropy crop and Microsoft's Desktop Duplication sample showed the way
for smaller pieces.

**Tools that verify Smalt.** Smalt's tests and benchmarks hold it to the output of other software, run as
black boxes: [FFmpeg](https://ffmpeg.org/) and ffprobe above all, the reference decoders and encoders of the
projects above (libvpx, libaom, dav1d, libwebp's cwebp and dwebp, OpenJPEG, OpenJPH, djxl, libultrahdr's
ultrahdr_app, libavif's avifgainmaputil, libFLAC, LAME,
libopus, opusfile, CharLS), the ISO/IEC 10918-7 reference software of JPEG (Thomas Richter's libjpeg) for
hierarchical JPEG and JPEG-LS, the TMN 3.2 H.263 encoder and decoder of the University of British Columbia
as makers of H.263 test streams, the ISO/IEC 14496-5 reference decoders and encoder of MPEG-4 Visual, the JM, JMVC and JSVM reference software of H.264 and its multiview and scalable
extensions as decoders and encoders of test streams, the [SHM](https://vcgit.hhi.fraunhofer.de/jvet/SHM) reference software of the HEVC scalable
and multiview extensions as the encoder and decoder of multi-layer test streams, Apple's AVFoundation (through
avconvert) as an encoder of HEVC with alpha, x264 and x265 as encoders of test streams, [LibTIFF](https://libtiff.gitlab.io/libtiff/)'s tiffcp and
tiff2rgba, [zstd](https://github.com/facebook/zstd) and [XZ Utils](https://tukaani.org/xz/), JBIG-KIT's
pbmtojbg and jbgtopbm, [tifffile](https://github.com/cgohlke/tifffile) and [imagecodecs](https://github.com/cgohlke/imagecodecs),
[OpenSlide](https://openslide.org/), [libvips](https://www.libvips.org/), Pillow, Little CMS,
[zimg](https://github.com/sekrit-twc/zimg) (through FFmpeg's zscale filter),
ExifTool, Adobe DNG Converter, Sandflow's [imscJS](https://github.com/sandflow/imscJS), the IMSC reference
implementation, the Fraunhofer [FDK AAC](https://github.com/mstorsjo/fdk-aac) library, and the Dolby Digital
Plus decoder of Windows Media Foundation. Code Intelligence's [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer),
on LLVM's libFuzzer, fuzzes every parser and decoder, and Google's
[ClusterFuzzLite](https://google.github.io/clusterfuzzlite/) runs it in CI. The test
vectors and samples they run on come from the ITU-T and ISO conformance suites, the WebM project, the
Alliance for Open Media, the JPEG XL conformance suite, [Xiph.Org](https://media.xiph.org/)'s test media,
Willem van Schaik's PngSuite, Jason Summers's BMP Suite, the W3C IMSC test suite, the Matroska test files, Nokia's HEIF samples, libavif's gain map test files, the FFmpeg FATE suite, Dolby's
Online Delivery Kit, the OpenSlide test data and raw.pixls.us. [JaCoCo](https://www.jacoco.org/) maps what the
tests cover and [jcstress](https://github.com/openjdk/jcstress) stresses the concurrent code.
[JUnit](https://junit.org/), [JMH](https://github.com/openjdk/jmh) and
[Chicory](https://github.com/dylibso/chicory), which runs the WebAssembly build of WebRTC's audio processing
the voice processor is compared with, keep the whole thing honest.

---

## License

Smalt is released under the [MIT License][license]. Parts of it are ports of, or derive from, permissively
licensed open source projects; each artifact lists those whose license asks for it, with their licenses, in
`META-INF/THIRD-PARTY-NOTICES`.

`smalt-raw` includes DNG technology under license by Adobe (the royalty-free DNG patent license).

The H.264, HEVC, AAC, AMR and MPEG-4 Part 2 formats in `smalt-mpeg` are covered by patents licensed through
patent pools, and E-AC-3 (Dolby Digital Plus) may still be covered by Dolby patents; the AC-3 patents expired
in 2017. The MIT License covers Smalt's code only and grants no patent rights, and the patent notes in this
README are orientation, not legal advice: check whether your use needs a license.

### Licensing of ProRes and DNxHD

ProRes and DNxHD/DNxHR are written from public SMPTE documents (RDD 36 and ST 2019-1) with no code ported from
another implementation, but both formats belong to companies that license implementations: Apple runs a
ProRes licensing program for encoders and decoders (contact: ProRes@apple.com, named in RDD 36 for questions on
intellectual property), Avid licenses DNxHD and DNxHR, and SMPTE ST 2019-1 notes that implementing VC-3 may
involve patents whose holders assured SMPTE they would license them on reasonable and non-discriminatory
terms. The names ProRes, DNxHD and DNxHR are trademarks of their owners. Both codecs ship in `smalt-video`;
check whether your use needs a license.

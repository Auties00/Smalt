# Contributing to Smalt

Smalt is a pure-Java (JDK 26) library of image, audio and video codecs, containers, processing and capture.
See the [README](README.md). Thank you for helping: new formats, faster kernels, bug reports with sample files and
measurements on hardware we don't have are all welcome.

[← Back to Smalt](README.md)

**Contents:** [Before you start](#before-you-start) · [Your first pull request](#your-first-pull-request) ·
[Build and test](#build-and-test) · [Rules for code](#rules-for-code) · [Licensing](#licensing) ·
[Add a format](#add-a-format) · [AI contributions](#ai-contributions) · [Tool guides](#tool-guides)

## Before you start

- Open an [issue](https://github.com/Auties00/Smalt/issues) before a large change.
- Code and documentation conventions are in [CLAUDE.md](CLAUDE.md) (shared by people and AI tools).
- For a decoding bug, attach the smallest file that shows it.
- Read the [licensing rules](#licensing) before you port any code. They are strict.

## Your first pull request

1. `git clone https://github.com/Auties00/Smalt`, then `cd Smalt`.
2. `mvn install -DskipTests` (once, and again after you change `core`).
3. `mvn -f image/pom.xml test`, for the module you changed.
4. Run the [other checks](#other-checks) that touch your change.
5. Open the pull request with a description in your own words.

## Build and test

You need **JDK 26** (as `JAVA_HOME`), **Maven** and **Python 3**. There is no Maven wrapper.

| Task | Command |
|---|---|
| Build everything | `mvn install -DskipTests` |
| Test one module | `mvn -f <module>/pom.xml test` |
| Run test JVMs in parallel | add `-Dsmalt.test.forks=2` (`1C` = one per core); `it` and `capture` use one |
| Cap each fork's heap | add `-Dsmalt.test.heap=-XX:MaxRAMPercentage=18` |
| Test without the Vector API | add `-Dsmalt.test.simd=false` |
| Pass extra JVM options to tests | add `-Dsmalt.test.jvmArgs=-Xmx256m` |
| Rewrite saved decode dumps | add `-Dsmalt.test.updateDumps=true`, then review the diff |

- Never run the whole suite in one command: test one module at a time.
- Module tests use `core`'s test jar, so run `mvn install` first.
- On Windows x64, tests run with AVX2 and 16-byte vectors (JDK-8365196), so 256-bit kernels run scalar.

### Test data and oracles

Tests read test vectors (sample files) and compare with oracles (reference tools such as ffmpeg). The vectors are
the project's hosted test data: pull it once into `~/.smalt/testdata` (`SMALT_TESTDATA`) with `oras pull` of each
artifact that `tools/testdata/artifacts.tsv` pins, as [tools/testdata](tools/testdata/README.md#pulling-the-data)
describes. Tests never download; a missing vector fails its test, and a missing oracle skips it.

| Command | What it does |
|---|---|
| `python tools/testdata/testdata.py verify` | Checks the pulled test data against the manifests |
| `python tools/testdata/testdata.py size` | Prints the size of each area of the test data |
| `python tools/testdata/oracles.py install` | Installs the oracles that differential tests compare with |
| `python tools/testdata/oracles.py versions` | Prints the installed oracle versions |

- `SMALT_TESTDATA` (or `-Dsmalt.testdata`) names another test data directory, and `SMALT_CACHE` (or `-Dsmalt.cache`,
  default `~/.smalt/cache`) the directory of the files tests derive from it.
- `-Dsmalt.test.requireOracles=true` (or a comma-separated list of tool names) makes a missing oracle fail; CI sets it to the tools it installs.
- Skip only through the test kit's `Skip`, with a reason id `family:name` (the message reads `[family:name] detail`).
- `python tools/testdata/skips.py check --job <job>` fails if a build skips more than the baseline.

### Other checks

CI runs these on every pull request, with each module's tests (Vector API and scalar) and the notices check.

| Check | Command |
|---|---|
| Comment lint | `python tools/lint/doclint.py` |
| Javadoc references | `mvn -Pjavadoc-check compile javadoc:javadoc-no-fork` |
| Generated kernels | `python tools/codegen/check.py` |

- Edit a generator or its template in `tools/codegen`, never the generated file.
- The lint baseline `tools/lint/baseline.tsv` may shrink, never grow.
- A new decoder, demuxer or parser gets a fuzz target with at least one `valid-*` seed (a well-formed sample it must decode).
- A fuzz finding goes under `src/test/resources/fuzz-regressions`, which every build replays.

<details>
<summary>Deeper checks (CI runs these nightly or weekly)</summary>

| Check | Command |
|---|---|
| List fuzz targets | `python tools/fuzz/fuzz.py list` |
| Fuzz one target | `python tools/fuzz/fuzz.py run core:DemuxerFuzzTest.mp4` |
| Coverage report | `mvn -Pcoverage verify` |
| Coverage summary | `python tools/coverage/coverage.py it/target/site/jacoco-aggregate/jacoco.xml --markdown coverage.md` |
| Concurrency stress | `mvn -Pjcstress -pl it -am verify -DskipTests` |

</details>

## Rules for code

### Licensing

> [!CAUTION]
> GPL and LGPL code, FFmpeg, x264, x265 and libde265 included, must **never** be read, copied or ported.
> It may only run as a black-box reference in tests.

- Port only from permissive code: MIT, BSD, Apache-2.0, ISC, zlib or public domain.
- Thank every project Smalt learns from in the README's [Acknowledgments](README.md#acknowledgments).

### Port headers

Every ported file names its source in a `/* Ports ... */` comment above `package`: project and version, files,
copyright holder, license, and "Modified for Smalt."

```
/* Ports vo-amrwbenc 0.1.3 (voAMRWBEnc.c). Copyright VisualOn; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;
```

When the license asks for attribution in copies, also record it in the third-party notices.
CI fails when the notices differ from what the scripts generate.

<details>
<summary>Update the third-party notices</summary>

1. Fetch the project's license file into `tools/notices/licenses/`.
2. Add or extend its section in `tools/notices/build-notices.sh`, naming the derived packages.
3. Run `bash tools/notices/build-notices.sh && python tools/notices/split-notices.py`.
4. Commit every regenerated file, `packages.tsv` and each jar's `META-INF/THIRD-PARTY-NOTICES` included.

</details>

### Bit-exactness

- Decoders of standardized formats stay bit-exact on the official conformance files.
- Encoders keep byte-identical output, unless a change is measured to be quality-neutral or better.
- Every differential or conformance comparison declares its tolerance with the test kit's `Tolerance`.
- Every Vector API (SIMD) kernel has a plain-Java twin with identical output and a test that compares them.

### Performance claims

- Back every speed claim with a JMH benchmark run against the old code (A/B): same session, 3 or more JVM forks.
- `python tools/bench/bench.py ab --base ../smalt-base --head .` runs one.
- Effects under about 5% need a quiet machine; CI's shared runners resolve only changes of 15 to 25% or more.
- A new hot path (a loop that dominates a codec's time) joins `JitWorkload` in the `it` (integration test) module.
- It also joins the `hot-paths` row of `tools/bench/benchmarks.tsv`.

### Threads

- Run parallel work on `internal.task.ParallelBands`; never start your own threads.

### Documentation

- To change README charts, edit `tools/readme/performance.tsv` and run `python tools/readme/charts.py`.
- Never hand-edit between the `perf` markers.

## Add a format

- Port only from permissive code ([Licensing](#licensing)); add a [port header](#port-headers).
- Add conformance or differential tests with a `Tolerance`.
- Add a fuzz target with a `valid-*` seed.
- Add the format to the module README's `#formats` table, and its sources to [Acknowledgments](README.md#acknowledgments).

## AI contributions

- AI-generated code is allowed.
- You are accountable for every line; it gets the same in-depth review as any change.
- No AI-generated commit messages, issue bodies, PR descriptions or review comments.
- Talk to people in your own words: confused human text beats polished LLM text.

## Tool guides

| Guide | Covers |
|---|---|
| [tools/testdata](tools/testdata/README.md) | Test vectors, oracles and skips |
| [tools/fuzz](tools/fuzz/README.md) | Fuzzing, reproducing and saving findings |
| [tools/bench](tools/bench/README.md) | Benchmarks, the allocation gate and A/B timing |
| [tools/coverage](tools/coverage/README.md) | The coverage map and its baseline |
| [tools/notices](tools/notices/README.md) | Third-party notices and port headers |

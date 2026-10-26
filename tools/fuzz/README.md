# Fuzzing

Smalt fuzzes every decoder, demuxer, parser and metadata reader with
[Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) (Apache-2.0), the coverage-guided fuzzer for the JVM
built on LLVM's libFuzzer. A fuzz target is a JUnit `@FuzzTest` method taking a `byte[]`; Jazzer instruments the
`com.github.auties00.smalt` classes and mutates inputs toward new coverage.

## The contract

A target hands its input to one Smalt entry point through `FuzzRun.step` (testkit `testkit.fuzz`, in core's test
jar). The only exception that entry point may throw is a `MediaException`; anything else escaping the target is a
finding: an unchecked exception, an `AssertionError` from a check of the output (an empty picture, an audio buffer
whose planes do not match its layout, a tag written and not read back), `OutOfMemoryError` or `StackOverflowError`.

Smalt's entry points run their bodies under a guard that turns any other runtime exception into a
`MediaException.Malformed` caused by it, and running out of memory or stack into a `MediaException.LimitExceeded`.
So that the guard does not hide bugs from the fuzzer, `Fuzz.check` turns such a rejection back into a finding: a
`MediaException` whose cause chain holds an `Error`, an `IndexOutOfBoundsException`, a `NegativeArraySizeException`,
a `NullPointerException`, a `ClassCastException`, an `ArrayStoreException`, a buffer underflow or overflow, a
`NoSuchElementException`, a `ConcurrentModificationException`, an `UnsupportedOperationException` or a division by
zero, and one reporting that the decoder ran out of memory or stack. An `IllegalArgumentException` or
`IllegalStateException` in the chain stays a rejection, since Smalt throws them on purpose (a muxer refusing a
packet the input timed wrongly, for example) and translates them knowingly.
Targets decode under `Fuzz.LIMITS` (8192 pixels per side, 1 megapixel, 32 frames, 16 channels, 64 MiB per
allocation) and cap the packets, frames and pages they read, so every input finishes in bounded memory and time;
an input slower than the per-input timeout (25 seconds) is a finding too.

While fuzzing, a target that would decode one input several ways (an image at full and reduced size, as an
animation and as pages; a raw file rendered three ways) runs one of those ways, chosen by a hash of the input
(`FuzzRun.sampled`); the replay tests run all of them.

## Where things are

| What | Where |
|---|---|
| Targets | `<module>/src/test/java/.../fuzz/*FuzzTest.java`, in core, image, audio, video, mpeg and raw |
| Shared drivers | `core/src/test/java/.../testkit/fuzz`: `Fuzz`, `FuzzRun`, `FuzzMedia` (image, video, audio, demuxer and tag drivers), `FuzzPackets` (the input format of the packet decoder targets) |
| Seeds | `<module>/src/test/resources/<package>/<Class>Inputs/<method>/`, where Jazzer looks for them |
| Saved findings | `<module>/src/test/resources/fuzz-regressions/<Class>/<method>/` |
| Replay tests | `<module>/src/test/java/.../fuzz/FuzzRegressionTest.java` |
| Seed writers | `<module>/src/test/java/.../fuzz/FuzzSeedWriter.java` |
| Runner | `tools/fuzz/fuzz.py` |
| ClusterFuzzLite | `.clusterfuzzlite/`, `.github/workflows/cflite_pr.yml` and `cflite_batch.yml` |

Every build runs each module's `FuzzRegressionTest`, which replays every seed and every saved finding through its
target as a plain test, without Jazzer's agent, under a 60-second deadline. A seed named `valid-*` must be
accepted by every step of its target and produce output, and every target must have one, so a target that
silently rejects everything fails the build. The only target without a valid seed is `RawFuzzTest.cr3`: Smalt does
not decode the CRX sensor data of CR3 files, so no CR3 input can be accepted by every step.

Packet decoder targets read the `FuzzPackets` format: a header (an options byte, the stream fields, the codec
configuration) followed by packets, the parts separated by the marker `FUZZ-TAG`, so that a mutation of one
packet leaves the others intact. The transparent video targets (`vp8Alpha`, `vp9Alpha`) also split each packet into
its color frame and its alpha frame at the marker `FUZZ-ALP`. `fuzz.py` hands libFuzzer a dictionary holding both
markers for these targets.

## Running the fuzzers

`fuzz.py` needs Java 26 (`JAVA_HOME`), Maven and Python 3. It compiles the fuzzed modules and runs Jazzer's
standalone driver on each module's test class path, which holds the module's own classes and every Smalt module it
depends on, built from this tree.

```
python tools/fuzz/fuzz.py list                          # the targets, as module:Class.method
python tools/fuzz/fuzz.py run core:DemuxerFuzzTest.mp4 --time 600
python tools/fuzz/fuzz.py budget --time 60 --select image,raw:RawFuzzTest.nef
python tools/fuzz/fuzz.py budget --time 60 --select shard=0/4
```

`run` and `budget` build first (`--no-build` skips it) and gather extra seeds (`--no-seeds` skips it): the
committed seeds, the saved findings, and the matching files of the conformance cache that
`python tools/testdata/testdata.py fetch --tier smoke` fills, with the packets of every cached container track
converted to `FuzzPackets` seeds of at most 32 packets. Each target keeps its corpus, its log and its findings
under `target/fuzz/smalt_<module>_<Class>_<method>/`; `budget` prints one line per target and exits with 1 when
one found something. libFuzzer's `slow-unit-*` files record inputs slower than 10 seconds and are reported but are
not findings.

A target can also be fuzzed through JUnit, which Jazzer's JUnit integration does when `JAZZER_FUZZ` is set; turn
the hooks on (the build turns them off so the other tests of the JVM run uninstrumented) and bound the run. Jazzer
then keeps its corpus in `<module>/.cifuzz-corpus/` and writes findings to the module directory, both ignored by
git:

```
JAZZER_FUZZ=1 mvn -pl core -am test -Dtest=DemuxerFuzzTest#mp4 -Djazzer.hooks=true -Djazzer.max_duration=10m -Dsurefire.failIfNoSpecifiedTests=false
```

## Reproducing and saving a finding

A finding is an input file: `crash-*`, `timeout-*` or `oom-*` under `target/fuzz/<target>/findings/` locally, or
in the artifacts of a ClusterFuzzLite run (the `crash-*` files of the failed job's artifact, named in its log).

1. Reproduce it: `python tools/fuzz/fuzz.py reproduce raw:RawFuzzTest.nef crash-1234` runs the input once and
   prints the exception and its stack trace.
2. Shrink it: `python tools/fuzz/fuzz.py minimize raw:RawFuzzTest.nef crash-1234` writes the smallest input that
   still fails to `target/fuzz/<target>/minimized/`.
3. Save it: `python tools/fuzz/fuzz.py save raw:RawFuzzTest.nef target/fuzz/smalt_raw_RawFuzzTest_nef/minimized/crash-1234 --name truncated-huffman-table.nef`
   copies it to `fuzz-regressions`, where every build replays it. Give it a name that says what it exercises.
4. Fix the code so the input raises a `MediaException` (or decodes), add a focused unit test next to the code,
   and run the module's `FuzzRegressionTest`.

## Seeds

Seeds are small: committed ones are a few kilobytes where the format allows it, and at most a few hundred
kilobytes for camera raw files. The `FuzzSeedWriter` test of each module writes them, from ffmpeg-made files,
committed test vectors, the conformance cache (vectors listed in `tools/testdata/vectors.tsv`) and Smalt's own
writers; it runs only with `-Dsmalt.fuzz.writeSeeds=true`:

```
mvn -pl raw -am test -Dtest=FuzzSeedWriter -Dsmalt.fuzz.writeSeeds=true -Dsurefire.failIfNoSpecifiedTests=false
```

The camera raw seeds are CC0 samples from raw.pixls.us cut by `RawSeedReducer` to their first 12 sensor rows,
with every large embedded JPEG replaced by a tiny one; the reducer checks that the cut file decodes, renders and
yields a preview exactly as the uncut one does.

## ClusterFuzzLite

`.clusterfuzzlite/Dockerfile` builds on OSS-Fuzz's JVM image, adds the Java 26 JDK and Maven, and
`.clusterfuzzlite/build.sh` runs `fuzz.py build`, fetches the smoke data tier for extra seeds and runs
`fuzz.py package --out $OUT`, which lays out one launcher per target, the bundled JDK, the class paths, the seed
corpus zips and the dictionaries. `cflite_pr.yml` fuzzes every pull request that touches code for 30 minutes
shared by the targets; `cflite_batch.yml` fuzzes every night for four hours, which grows the corpus the pull
request runs start from, and then prunes it. A crash fails the job; download the artifact and follow
[Reproducing and saving a finding](#reproducing-and-saving-a-finding).

## Notes

- Jazzer 0.30.0 instruments Java 26 class files (its shaded ASM reads them) and runs on JDK 26 with the incubating
  Vector API; the JVM needs `-XX:+EnableDynamicAgentLoading`, which the test argLine and `fuzz.py` pass.
- Targets run at the JVM's default thread stack size, so that recursion an input controls overflows while fuzzing
  as it would in an application. The nightly workflow fuzzes a quarter of the targets in turn with
  `fuzz.py budget --memory 256`, and CI replays every seed and finding in a 256 MiB heap, so that an allocation the
  limits do not bound fails as an `OutOfMemoryError` instead of passing on a large runner.
- jazzer-junit is built against an older JUnit; the poms exclude its JUnit dependencies so the build's JUnit wins,
  and add `junit-platform-launcher`, which the standalone driver needs to run `@FuzzTest` methods.
- Instrumented code runs several times slower than plain code. On a desktop the video and image decoders run a few
  hundred to a few thousand inputs per second, the parsers tens of thousands to millions; the camera raw targets,
  whose seeds keep a full sensor width, and the ICC target, which evaluates whole transform pipelines, run tens.
  The JPEG XL image target runs about one input per second: its valid seeds are 1024x1024 conformance images,
  each decoded several ways, and no JPEG XL encoder is at hand to make smaller ones.

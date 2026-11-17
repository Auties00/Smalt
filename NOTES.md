# c-scalar-default

Scope (revised mid-task by the coordinator): only Windows runs the scalar kernels by default, because of
JDK-8365196 (xmm16-31 lost across safepoints on Windows x64 with AVX-512). Every other platform keeps the
vector kernels as before (incubator module present, preferred bits >= 128, `-Dsmalt.simd=false` disables).
On Windows the vector kernels need `-Dsmalt.simd=true` (plus the existing conditions), and `-XX:UseAVX=2` on
AVX-512 machines. The vector code is unchanged.

## Files changed

- `core/.../internal/simd/Simd.java`: `detect()` returns false on Windows unless `smalt.simd=true`, with a
  `// TODO:` to re-enable by default on Windows once JDK-8365196 is fixed and the fixed JDK is the minimum;
  class doc explains the Windows opt-in and that the `loop-receiver` rule narrows the exposure without removing it.
- `core/.../MediaRuntime.java`: class doc (Windows opt-in, `-XX:UseAVX=2`, JDK-8365196 link).
- `core/src/main/java/module-info.java`, and the "launch requirements" sentence of the `aot`, `audio`, `image`,
  `mpeg`, `raw`, `video` module-infos.
- `README.md`: "JVM flags" (Windows opt-in with `-Dsmalt.simd=true` and `-XX:UseAVX=2`, JDK-8365196 link; the
  claim that the static-tail rule removes the crash instances is gone), the "Fast" bullet, the AOT section
  (training follows the kernel selection; Windows opt-in passes the flags to training too).
- `CLAUDE.md`: the "Vector API behind scalar twins" rule notes the Windows opt-in and the test rule.
- `aot/.../SmaltTraining.java`: training never forced vector kernels (it follows `Simd.ENABLED` through the
  kernels' own selectors); doc says so and documents the Windows opt-in, and `main` warns on Windows when the
  incubator module is resolved but `smalt.simd` is unset (scalar training).
- Tests:
  - `core/.../MediaRuntimeTest.java`: `narrowJvm` passes `-Dsmalt.simd=true`; new `defaultSelection` (child JVM
    with the module and no property: scalar on Windows, vectorized elsewhere) and `disabled`
    (`-Dsmalt.simd=false` is scalar everywhere).
  - `core/.../testkit/jit/KernelGuard.java`: child JVMs pass `-Dsmalt.simd=true`.
  - `it/.../jit/JitStressTest.java`: child JVMs pass `-Dsmalt.simd=true`.
  - `it/pom.xml`: the jcstress forks pass `-Dsmalt.simd=true`.
  - `mpeg/.../SharedPoolEncoderTest.java`: the child JVM inherits the parent's `smalt.simd`.
  - `it/.../ColdStartBenchmark.java`: `VECTOR` mode sets `smalt.simd=true`.
  - `core/.../ImageEffectsBenchmark.java`, `ImageOpsBenchmark.java`: the "Vector API not enabled" message
    mentions `-Dsmalt.simd=true` on Windows.
  - `core/.../testkit/Skip.java`: doc of the SIMD skip.

The surefire `argLine` already passes `-Dsmalt.simd=${smalt.test.simd}` (default `true`) on every platform, so
the Windows test JVMs already opt in; no Windows Maven profile was needed. The scalar CI leg keeps passing
`false`.

## UseAVX on CI (follow-up, approved)

Every Windows x64 test JVM now runs with `-XX:UseAVX=2`:

- `pom.xml`: new `smalt.test.cpuArgs` property (empty), set to `-XX:UseAVX=2` by the `windows-x64` profile
  (activated by `<os><family>windows</family><arch>amd64</arch></os>`, with a `TODO:` to remove it once
  JDK-8365196 is fixed). `smalt.test.argLine` carries the flag and repeats it as `-Dsmalt.test.cpuArgs=...`.
  No JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS.
- `core/.../testkit/ChildJvm.java` (new): `cpuArgs()` returns the `smalt.test.cpuArgs` system property's options.
  Every test that launches `java` adds them before its own options: KernelGuard (child, AOT training child and
  the HostFlags probe, so the width scenarios start from AVX2), JitStressTest, MediaRuntimeTest,
  ThreadCountInvarianceTest, SharedPoolEncoderTest, ContainerProviderTest, ModulePathTest, NativeCrashDumpsTest,
  FixtureWindow, CoreAudioHogLiveTest. `it/pom.xml` adds `${smalt.test.cpuArgs}` to both jcstress
  `-jvmArgsPrepend` arguments.
- KernelGuard's explicit `avx3` scenario is skipped on Windows (a stderr line cites JDK-8365196); Linux keeps it.
- `core/.../ChildJvmTest.java` (new): asserts `cpuArgs()` is `[-XX:UseAVX=2]` on Windows x64 and empty elsewhere,
  and that a child started with it reports `UseAVX = 2` in `-XX:+PrintFlagsFinal`. It prints the line.
- Not changed: the aot module's tests start no child JVM (JoinedAndLegacyFilesTest runs external tools only).
  ColdStartBenchmark and the other JMH benchmarks fork through JMH, which repeats the host JVM's input
  arguments, so a Windows host started with `-XX:UseAVX=2` passes it on. The benchmarks run on Linux in CI.
- Docs: CLAUDE.md (SIMD rule) and the README's "Vector kernels and the JIT" CI bullets.

## Verification

- Local: `mvn -o -T 1C test-compile -DskipTests` (JDK 26) passed; `python tools/lint/doclint.py`: no findings
  beyond the baseline.
- CI: run 44000195443 (Package tests, branch pkg/c-scalar-default, commit 8f9dcd4, all modules:
  kernel-lint,core,image,audio,video,mpeg,capture,raw,desktop,aot,it; ubuntu-latest and windows-latest, simd=vector):
  success on both runners. `MediaRuntimeTest` ran all 7 tests on both, including `defaultSelection` and `disabled`.
  NOTES.md itself was written after that push, so it is not in the CI snapshot.
- Follow-up CI: run 44052412875 (Package tests, branch pkg/c-scalar-default, commit b7ee0e5; all modules on
  ubuntu-latest and windows-latest, simd=vector): success on both runners. In the Windows job, `ChildJvmTest`
  passed (so the `windows-x64` profile was active and `smalt.test.cpuArgs` was `-XX:UseAVX=2`) and printed
  `child JVM -XX:UseAVX=2: int UseAVX = 2 {ARCH product} {command line}`. On Ubuntu it printed `UseAVX = 3 {default}`,
  so Linux still covers AVX-512. Local test-compile (JDK 26) and the comment lint passed before the push.

## UseAVX=0 (second follow-up): blocked, needs a decision

Changes made: the `windows-x64` profile sets `-XX:UseAVX=0`; `ChildJvmTest` expects `UseAVX = 0`; KernelGuard skips
the `avx2` and `avx3` scenarios on Windows (stderr line cites JDK-8365196; on Windows the host-flags probe now
runs with `UseAVX=0`, so `widths()` adds none either); the `simd:narrow` skips of `VectorCdfDspTest` and
`Vp9DspEquivalenceTest` cite JDK-8365196; the skip baseline gains
`windows-x64-vector-{smoke,full} video VectorCdfDspTest simd:narrow 18` (the count the 128-bit platforms have);
the docs (README JVM flags, AOT and CI bullets, CLAUDE.md, Simd, MediaRuntime, core module-info, SmaltTraining)
drop the `-XX:UseAVX=2` advice, say affected hosts (seen on Windows Server 2025) lose vector state across
hardware traps, that only `-XX:UseAVX=0` avoids it fully, and that opting in there is not safe until Windows or
the JDK is fixed. Local test-compile (JDK 26) and the comment lint passed.

CI run 44111306900 (commit 8c76dad, all modules, ubuntu-latest and windows-latest): ubuntu success; windows
cancelled at the job's 180-minute timeout while still in the core module (smalt-core is module 2 of 11). The
Windows log shows the flag in effect: `child JVM -XX:UseAVX=0: int UseAVX = 0 {ARCH product} {command line}`
(ChildJvmTest passed). No test failed; the job ran out of time because under `-XX:UseAVX=0` the Vector API
kernels Smalt selects (preferred width 128, so `Simd.ENABLED` stays true) are largely not intrinsified by C2 and
run on the Java fallback: `LoudnessNormalizerTest` took 5285 s (8.4 s with UseAVX=2 in run 44052412875),
`LoudnessMeter` 3830 s, the EBU 3341/3342 suites 1775/1198 s, `ResamplerTest` 1524 s (3.5 s before).

So "UseAVX=0 gives 128-bit SSE vector kernels" does not hold in practice: an SSE-only x86 JVM selects the vector
kernels and runs them hundreds of times slower. This also affects any non-Windows JVM started with
`-XX:UseAVX=0` or an SSE-only x86 CPU (pre-existing; `Simd.detect` checks only the preferred width).

Options (not chosen here):
1. Windows CI runs the scalar kernels (the Windows product default): append `-Dsmalt.simd=false` after the
   cpuArgs in the Windows argLine, skip-baseline rows mirroring `linux-x64-scalar-smoke`'s `simd:disabled` rows,
   and the `it` module's JIT tests skipped or excluded on Windows. Vector coverage stays on Linux x64/arm64 and macOS.
2. Make `Simd.detect` select the vector kernels on x86 only with AVX (or AVX2, the `WIDE` condition), which fixes
   the SSE-only slow path for everyone; on Windows CI with UseAVX=0 that also yields scalar, so the same baseline
   and `it` work as option 1 follows.
3. Go back to `-XX:UseAVX=2` on Windows CI (known to still hit the defect intermittently).

## AVX2 rule (third follow-up)

Changes:
- `Simd.detect`: on x86 the vector kernels need `UseAVX >= 2` (`MIN_X86_AVX`), read through the new
  `internal.simd.HotSpotFlags` (`HotSpotDiagnosticMXBean`); core's module-info gains `requires static jdk.management`.
  Without that module (a module-path application that does not resolve it), 256-bit preferred integer vectors
  (AVX2 and later only) stand in, so a 128-bit x86 JVM runs scalar. Non-x86 is unchanged. Docs: Simd,
  MediaRuntime, README JVM flags, CLAUDE.md, the pom comment.
- Why AVX2, not AVX1: preferred species widths cannot tell UseAVX=0 from 1 (both 128 for int) nor from an AVX2
  JVM with `-XX:MaxVectorSize=16` (which KernelGuard's `vector-128` scenario and `MediaRuntimeTest.narrowJvm`
  rely on), so the flag is read. Measured locally (i7-9700K, JDK 26): `CoreKernelGuardTest` under the `tiered`
  scenario with `-XX:UseAVX=1` fails one of 10 families, `VectorBitReverser.reverse` allocating in 11 of 16
  measured calls (38600 bytes per call), so AVX1 is not enough.
- Tests: `MediaRuntimeTest.belowAvx2` (UseAVX=0 and 1 children report `isVectorized() == false` on x86),
  `withoutManagement` (`--limit-modules java.base,jdk.incubator.vector`: same selection when the preferred width
  is 256 or more, scalar for a 128-bit x86 JVM, scalar under UseAVX=0), `narrowJvm` expects scalar when the build
  caps the children below AVX2 (Windows CI). Local run of MediaRuntimeTest and ChildJvmTest passed with the cap at
  UseAVX=0 and at UseAVX=2.
- Skip baseline: `windows-x64-vector-{smoke,full}` gain the `simd:*` rows of `linux-x64-scalar-smoke` plus
  `it KernelGuardSelfTest simd:disabled 7` (56 rows; check only fails when a count grows).

CI run 44162111133 (commit e18381a, all modules, ubuntu-latest and windows-latest): ubuntu success; windows
cancelled again at the 180-minute timeout (core took 2 h, then image, audio, video were reached). Windows CI now
runs scalar, and the same tests are as slow as before (`LoudnessNormalizer` 5270 s, `LoudnessMeter` 3722 s,
`ResamplerTest` 1506 s), so the slowness in run 44111306900 was not the Vector API fallback after all:
`-XX:UseAVX=0` turns `UseFMA` off (HotSpot: FMA3 is VEX-encoded; `-XX:+UseFMA` is refused with "FMA instructions are
not available"), so `Math.fma` runs its BigDecimal software path. 8 main-source files use `Math.fma`, among them
the true-peak and FIR kernels. This also hits any user who follows the "only UseAVX=0" advice (now noted in the
README JVM flags).

Not resolved: Windows CI cannot finish under `-XX:UseAVX=0` within the job timeout. Options for the coordinator:
1. `-XX:UseAVX=2 -XX:MaxVectorSize=16` (or `UseAVX=1` with it) on Windows CI: C2 keeps vectors in xmm (no ymm/zmm
   upper state), FMA stays available; stubs (arraycopy, string intrinsics) may still touch ymm, so it is not the
   proven-safe setting. Under it Simd would select the 128-bit vector kernels on Windows CI.
2. Keep UseAVX=0 and raise the Windows job timeout (core alone took about 2 h; the whole suite likely exceeds
   GitHub's 6 h job limit).
3. Keep UseAVX=0 and leave the fma-heavy suites (loudness, resampler, true peak) to Linux on Windows.

Local check of the cause (i7-9700K, JDK 26): 2e7 float `Math.fma` calls take 9708 ms with `-XX:UseAVX=0` and 41 ms
with `-XX:UseAVX=2` (about 240x).

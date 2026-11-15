# Benchmarks in CI

The JMH benchmarks live in each module's test sources (the parent pom runs the JMH annotation processor on them).
`benchmarks.tsv` picks the ones CI runs and `bench.py` runs them; `.github/workflows/bench.yml` calls it. The hosted
runners are shared virtual machines: their speed drifts from run to run (by up to about 1.5x between runs) and
depends on the CPU model a job lands on (AMD EPYC of three generations, Intel Xeon; AVX2 or AVX-512). So every
measurement compares a base and a head built side by side in one job, and nothing is compared with numbers recorded
in another run.

## The allocation gate (pull requests)

Every case of the `alloc` set runs with JMH's gc profiler (`-prof gc`) in the base and in the head, with
`-Dsmalt.threads=2 -XX:-DoEscapeAnalysis`. The value of a case is `gc.alloc.rate.norm`, the bytes every thread of the
benchmark JVM allocates per operation, as the least of the measured iterations (the first ones can still carry
allocation from code the JIT has not compiled yet). Allocation does not depend on how busy the runner is, but it
can depend on the machine (the vector width selects other kernels and buffer sizes), so the base is measured on the
same runner rather than recorded once. It also depends on the JIT's escape analysis, whose scalar replacement
differs from one JVM to the next, even in one job: with it on, the Opus voice-note workload measured 22,180 and
44,580 bytes per round in two JVMs of the same tree. The gate turns it off and counts the allocations the code
performs, which are the same in every JVM; it therefore also counts short-lived objects that escape analysis would
remove in production, which a hot path should not rely on.

A case fails when the head allocates more than `base * 1.01 + 256` bytes per operation (`--rel`, `--abs`), confirmed
once in fresh JVMs of both trees (the head's least measurement against the base's largest). A case the base cannot
run is reported as new (the base has no such module) or as failed on the base (for example a workload the base's
`JitWorkload` does not know yet), and neither fails the gate; a case that does not run on the head does. A change
that allocates more on purpose (a larger buffer pool, a feature that needs memory) gets the `allocation-accepted`
label on its pull request: the job then reports the excess without failing.

The `hot-paths` and `av1-encoder` rows run `it`'s `JitEndToEndBenchmark` on the synthetic workloads of
`JitWorkload` (H.264, VP8, VP9 and AV1 encoding, H.264 decoding, ProRes and DNxHD, WebP stills and animations,
JPEG, PNG and GIF, the image operations, the capture conversions, Opus, AAC, HE-AAC, AC-3, AMR, FLAC and MP3, the
resampler and the voice processor). The video and audio workloads keep their codec open across rounds, so a round's
allocation is what the codec allocates per batch of frames or samples; the image workloads decode or encode whole
images in every round, so theirs also holds each image's pictures and coder state. Every round sees the same input,
so a per-frame or per-row allocation added to a hot path shows up directly.

## The A/B timing (weekly and on demand)

Every case of the `ab` set runs in both trees one JMH fork at a time, the order alternating (base then head, then
head then base), so that drift of the runner hits both sides. The change of a case is
`median(head) / median(base) - 1` of the time per operation, with a 95% bootstrap interval over the forks (seeded,
so the same results give the same interval). A case is a regression when it is slower by at least the threshold
(20% by default; 15 to 25% is what these runners support) and the interval excludes no change, and an improvement
in the mirror case; within the threshold it is slower or faster, or the same when the interval holds no change. A
regression, or a case that did not run on the head, fails the run.

The weekly run on the default branch compares the head with the last commit at least a week old, and keeps the
head's numbers (nanoseconds per operation, with the runner's CPU and the change against the base) on the `gh-pages`
branch through [github-action-benchmark](https://github.com/benchmark-action/github-action-benchmark), whose page
draws them under `bench/`. Read those charts as a log, not as a trend: two points come from different machines.
Runs on other branches, and runs with JVM options for one side, never publish.

Dispatch inputs: `mode` (`ab`, `alloc` or `both`), `base` (any ref or commit; `HEAD` with `base_args` or
`head_args` compares JVM options on the same code, in either mode), `ab_rows` and `alloc_rows` (row ids), and for
the A/B `forks`, `warmup`, `iterations` and `threshold`.

## Running it locally

```
python tools/bench/bench.py classpath --tree ../smalt-base --modules it,audio --existing
python tools/bench/bench.py classpath --modules it,audio
python tools/bench/bench.py ab --base ../smalt-base --head . --rows hot-paths,silk-encoder
python tools/bench/bench.py alloc --base ../smalt-base --rows hot-paths
python tools/bench/bench.py report --out target/bench --threshold 0.05
```

`classpath` packages the modules (without tests) and writes each one's test class path to
`target/bench-classpath.txt`; the base is any other checkout, for example `git worktree add ../smalt-base <commit>`.
`plan` prints what the selected rows need and `base` picks the base commit as CI does. Results, JMH logs and the
reports go to `target/bench`. On a quiet machine the same A/B resolves a few percent (the project rule for a
performance claim: three or more forks against a frozen baseline); `report` recomputes the verdicts of a finished
run with another threshold.

`python -m unittest discover -s tools/bench -p "test_*.py"` tests the tool against `fake_jmh.py` and
`fake_mvn.py`, which stand in for the JVM and Maven; CI runs it in the comment-lint job and before each benchmark job.

## benchmarks.tsv

Tab-separated: the row id, the module, the sets (`ab`, `alloc`), what it needs (`-`, or `oracles` for a setup that
runs the oracle tools of `tools/testdata`), the least number of warm-up iterations its cases need (`-`, or a number
for a workload the JIT takes longer to compile, such as the AV1 encoder, whose JVMs keep allocating from kernels
not yet compiled for 5 to more than 12 seconds), the JMH include regex, and the parameters (`-`, or
`name=v1,v2;name=v1`). Every combination of the parameter values is a case of its own, so that base and head run
next to each other for each. Inputs must be synthetic or made with the oracle tools, so that both trees, and every
run, see the same data; the alloc set must not need the oracle tools, which pull requests do not install. A new hot
path joins `JitWorkload` and the `hot-paths` row; a new row picks the parameters that matter rather than every
combination, since the weekly run has a budget of a few hours (about `cases * forks * 2 * (warmup + iterations +
3)` seconds).

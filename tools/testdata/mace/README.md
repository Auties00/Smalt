# MACE tables and fixtures

No description of MACE's tables exists. US patent 5,083,310 gives the structure; `audio.mace` gets its numbers from
QuickTime as a black box, through its public sound converter API, under the user's approval of 2026-11-03. Nothing
here disassembles, decompiles or reads the binaries: only the samples they output are read.

## The oracle

- `QuickTimeInstaller.exe`, QuickTime 7.7.9 for Windows from Apple's CDN
  (`secure-appldnld.apple.com/QuickTime/031-43075-20160107-C0844134-B3CD-11E5-B1C0-43CA8D551951/`), Authenticode
  signature valid (Apple Inc.), SHA-256 `56eff77b029b5f56c47d11fe58878627065dbeacbc3108d50d98a83420152c2b`.
- It is not installed: the payload CAB and MSI streams are unpacked with the MSI's own layout into a `QTSystem`
  directory, with Apple Application Support's DLLs from the same installer. Do not run the installer itself, it asks for
  elevation. The binaries are not committed and are not on CI, so CI tests run against the checked-in fixtures.
- `q.c` is a 32-bit driver with no C runtime, built with clang for i686 and lld-link and placed in `QTSystem`. It loads
  the registry values QuickTime expects through `RegLoadAppKey` and `RegOverridePredefKey` (in-process, nothing on the
  machine changes), then calls `InitializeQTML`, `EnterMovies` and the `SoundConverter*` functions. Mode `B` ends and
  begins the conversion every given number of frames, which resets the decoder: a batch of many trials in one process.
- `qt.py` runs the driver. `MACE_QT_DIR` names the `QTSystem` directory.

## What was measured

Notation: `S` the reconstructed sample, `i` the adaptation state, row `(i >> 4) & 127`.

- Bit layout, from output timing. MAC3: one code per sample; low 3, next 2, top 3 bits of a byte. MAC6: two samples per
  code; top 3, next 2, low 3. A frame of several channels holds the channels one after the other: 2 bytes each for MAC3,
  1 for MAC6. The converter refuses more than two channels (`paramErr`, -50); `MaceDecoder` decodes more in the same
  layout, each channel on its own, which nothing here can compare.
- `i += m[code] - (i >> 5)`, floored at 0 and not wrapped, with `m` found by an exhaustive search over 24,369 row
  observations (sign-flipped streams read back at the rows where a row's step is wide enough to tell): -13, 8, 76, 222
  for a three-bit field, -18, 140 for a two-bit field. The upper codes mirror the lower ones.
- MAC3: `S = clamp(S - (S >> 3) + T)`, output `S >> 8`.
- `T`, in `tables.txt` (per row, the eight three-bit steps then the four two-bit steps): `solve-tables.py` bisects each
  entry. A probe is a walk over entries already pinned that lands in the entry's row with a chosen `S`, so that the
  entry's bisection point falls on an 8-bit output boundary. The upper codes were measured on their own and are
  `-T - 1`; entries past 32767 output the same as 32767 for every reachable `S`, and are taken as 32767. The solver
  assumes the negative saturation is not reached: it stops a walk there. The limit, `-32767` rather than `-32768`, showed
  when `validate-tables.py` compared streams that saturate.
- MAC6 uses the same tables and adaptation. Sign-flip pairs (two streams differing only in the mirror of one code, so with
  the same rows) at the top rows, where a step is 32767, give the kernel: differences of 32, 96, 128, 128, 96, 32 output
  units, so outputs are 1:4:3 and 3:4:1 of the newest three reconstructed samples, in eighths. Off-by-one mismatches at
  8-bit boundaries then showed that each term truncates on its own.
- The MAC6 predictor is the patent's sign-driven one. One-sign streams, which all follow one coefficient sequence, read
  it directly by a greedy search per code: linear from 0 with 506/32768 per code, flat at the ceiling. After a sign flip
  it falls by 314/32768 per code. An exhaustive search of start, rise, fall and update order on stream survival gave
  `S' = clamp((A * S >> 15) + T)`, then `A += (S < 0) == (T < 0) ? 506 : -314` on the old `S`, `A` limited to -32767..32767.
- QuickTime's 16-bit output repeats the 8-bit sample in both bytes (`pcm16-*`). The decoder outputs the same.

Every step was checked with streams that had not been used for the fitting: 5.4 million MAC3 samples and 3 million MAC6
samples of crafted streams, then nine real files, without a mismatch.

## The fixtures

`make-fixtures.py` writes `audio/src/test/resources/com/github/auties00/smalt/audio/mace` and the I/O behavior samples
`core/src/test/resources/samples/{aiff,mp4}/mace[36].*` (then pin them with `tools/testdata/behavior/pin-samples.py`).
It needs the oracle; its random sources are seeded, and QuickTime is deterministic, so a run reproduces the committed
files. `.mace` holds the codes, `.pcm` QuickTime's unsigned 8-bit interleaved output, `fixtures.tsv` the codec, channels
and how many bytes each fresh decoder takes.

| Fixture | Streams |
|---|---|
| `coverage-*` | Reach every table entry |
| `boundaries-mac3` | Every entry lands a sample on an 8-bit boundary from both sides, so an entry off by one changes the output; the digest of the output only, for size |
| `stress-*` | Skewed code distributions, sign runs that saturate, opposing signs that drive the MAC6 coefficient to its floor |
| `encoded-*` | 8-bit synthetic music written by QuickTime's own MACE encoder (the same `SoundConverter` API), mono and stereo |
| `random-stereo-*` | Random bytes, two channels |
| `pcm16-*` | QuickTime's 16-bit big-endian output |
| `sample-*` | QuickTime's output for the sound data of the behavior samples, whose containers the script writes itself |

The real files of `MaceConformanceTest` come from `samples.ffmpeg.org/A-codecs/MACE/` through `vectors.tsv` (no licence
stated; fetched, never committed). The digests the test pins are of QuickTime's output for their sound data.

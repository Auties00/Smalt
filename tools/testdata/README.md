# Test data

Smalt's tests read two kinds of external data, both listed here with SHA-256 checksums:

- `vectors.tsv`: the conformance vectors and sample files the tests request through the test kit's
  `Conformance.fetch` (ITU-T and ISO conformance streams, the libvpx, libaom, libwebp, Opus, Vorbis and
  FLAC test vectors, PngSuite, Xiph sequences). They are cached in `~/.smalt/cache/conformance`
  (`SMALT_CONFORMANCE_CACHE` or `-Dsmalt.conformance.cache` override it).
- `clips.tsv`: raw 4:2:0 clips for the real-time VP8 and VP9 encoder tests, the AVIF and intra encoder
  quality tests and the quality reports, derived with ffmpeg from Xiph sequences (300 frames, the 60 fps sources
  halved to 30 fps, and a crop and scale for the 640x480 ones; three frames of `parkrun`; ffmpeg 7.1 on x86-64
  reproduces every checksum, while swscale's arm64 kernels scale differently). They are cached in `~/.smalt/cache/clips` (`-Dsmalt.test.clips` for the tests,
  `SMALT_TEST_CLIPS` for the script). Without them the encoder tests use synthetic video, except those whose
  rate or quality assertions are calibrated on the clips' content, which skip (`data:clip`). CI derives the clips
  once, on Linux x64 in the oracle tools job (`.github/workflows/oracle-tools.yml`), with `--strict`, which fails
  on a clip that does not match its checksum, and keeps them in one Actions cache entry shared by every runner, so
  that the encoder golden tests see the same input everywhere. The repository's 10 GB cache evicts that entry, and
  the oracle data entries, when many branches run at once, so the nightly workflow also uploads the clips and the
  oracle data files as artifacts of its run (`testdata-clips`, `oracle-data-*`), which its jobs download when
  their cache entry is missing.

- `archives.tsv`: sets of members of remote ZIP archives too large to download whole (the Argon AV1 coverage
  streams, 6.4 GB of a 7 GB archive; the DASH-IF livesim-content representations), one row per set: tier, name,
  size, the SHA-256 of the archive's central directory, URL, a regular expression selecting the members, and a
  licence note. A set is read by HTTP range requests (the end of central directory record, the ZIP64 records, the
  central directory, then each member, neighbors coalesced into one request), and each member is checked against
  the CRC-32 and size of the pinned central directory. Members are cached under
  `<conformance cache>/archives/<archive>-<digest prefix>/`; tests open a set with `Conformance.archive(name)`.
- `licences.tsv`: the licence or terms of use of every source of `vectors.tsv` and `clips.tsv`, by URL prefix (the
  archive sets carry their own). None of this data is committed or redistributed, and `testdata.py licences`,
  which the comment lint job of CI runs, fails when a source has no note.

Each entry belongs to a tier. `smoke` is small enough for the GitHub Actions cache and runs on every pull
request; `full` (which includes `smoke`) runs nightly. A test whose data is absent is skipped.

CI keeps the smoke tier, the clips, the oracle tools and the ffmpeg builds in Actions cache entries of the default
branch, which `.github/workflows/warm-caches.yml` fills (when these files change there, and daily) and every other
branch restores; the `pkg/*` snapshot branches never save copies of their own, except of an entry whose inputs they
changed (`.github/scripts/cache-policy.sh`). The full tier does not fit in the 10 GB repository cache next to them,
so the nightly downloads it every night.

```
python tools/testdata/testdata.py fetch --tier smoke    # the vectors CI's pull request jobs use
python tools/testdata/testdata.py fetch --tier full --clips-only   # and the clips they derive (needs ffmpeg)
python tools/testdata/testdata.py fetch --tier full     # everything, clips included (needs ffmpeg)
python tools/testdata/testdata.py size                  # the size of each tier
python tools/testdata/testdata.py verify --tier full    # check the cache against the checksums
python tools/testdata/testdata.py fetch --set 'argon-av1-profile0_core'   # one archive set, whatever its tier
python tools/testdata/testdata.py archive NAME URL REGEX --tier full --licence TEXT   # pin a new archive set
python tools/testdata/testdata.py licences              # every source has a licence note
```

## Oracle tools

`oracles.tsv` pins the reference tools the differential tests run as black boxes, per CI platform (`linux-x64`,
`linux-arm64`, `windows-x64`, `macos-arm64`): an archive or package downloaded by URL and SHA-256 where an
official build exists, pinned sources built by `oracles.py` otherwise (OpenJPEG 2.5.3 on Linux and macOS, libtiff
4.7.1, FLAC 1.5.0, libjxl 0.11.2 on Linux arm64 and macOS, libjpeg-turbo 3.1.3 on Windows, libopus's
`opus_compare`, mkvalidator on Windows and the small programs of this directory), so that every runner has the same
version of every tool a test runs. The package managers provide the build dependencies, GPAC's MP4Box and
mkvtoolnix on Linux and macOS and TSDuck on macOS, which the container validators of the test kit run where they are
installed, and sox and opus-tools, which no test runs; their versions are recorded, not pinned. mkvalidator is
provisioned on Windows only, where its project builds it: its GCC build of the pinned revision misreads the Seek
elements of every file and crashes; core's `ValidatorsTest` checks the provisioned build against mkvmerge's output.

```
python tools/testdata/oracles.py install            # installs this platform's set into ~/.smalt/oracles
python tools/testdata/oracles.py versions           # prints the version and path of every provisioned tool
python tools/testdata/oracles.py required           # prints the executables the platform provisions
```

`install --github` (CI) also puts the tool directories on `PATH` and sets `SMALT_TEST_REQUIRE_ORACLES` to the
provisioned executables, which makes the test kit's `Oracle` fail instead of skip when one of them is missing;
`versions` fails when a tool is missing or resolves outside its pinned directory. Locally,
`-Dsmalt.test.requireOracles=true` (or a comma-separated list of executables) does the same. The `# Versions:`
comment of `oracles.tsv` explains each choice of version.

### ffmpeg

Every runner has ffmpeg 7.1.2 with the same components: those of ffmpeg itself and the external-library components
of `ffmpeg-oracle/features.tsv` (LAME, opencore-amr and vo-amrwbenc, Speex, Opus, Vorbis, Theora, libvpx with high
bit depth, libwebp, x264, x265 for 8, 10 and 12 bits, Xvid, libaom, dav1d, rav1e, SVT-AV1, OpenJPEG, zimg, and
libxml2 for the DASH demuxer). No prebuilt 7.1.2 has all of them (Ubuntu's and BtbN's lack `libvo_amrwbenc` and
`libspeex`, Homebrew's `libvo_amrwbenc`, and gyan.dev's full builds stop at 7.1.1, which decodes some small H.264
pictures wrongly on arm64), so `ffmpeg-oracle/build.py` builds ffmpeg and each library, static, from pinned source
releases into `~/.smalt/ffmpeg-7.1.2` (it installs its build tools with apt, Homebrew or MSYS2's pacman, and Rust
through rustup for rav1e; the first build takes most of an hour, and CI caches the prefix under a key of the
script's hash). On Windows it runs under MSYS2 (UCRT64, with MSYS2's Python, Rust and cargo-c). On Linux and macOS
ffmpeg allocates through `ffmpeg-oracle/quarantine.c` (`--malloc-prefix`), which holds back the small blocks ffmpeg
frees for a while: every ffmpeg 7.1 release writes into a 40-byte block it has just freed when it opens its libaom
encoder (FFmpeg ticket #11617, found with AddressSanitizer), which corrupted the heap and crashed libaom encodes on
some macOS and Linux arm64 runners (SIGTRAP from macOS's allocator, SIGSEGV from glibc's). `oracles.py
versions` (through `ffmpeg-oracle/check.py`) fails when ffmpeg lacks a component of `features.tsv` or a smoke run of
one fails (each encoder encodes, each decoder decodes, the DASH demuxer reads back a manifest without a warning),
and core's `SkipTest` fails when a test names an external-library component (`"libx264"`, `assumeEncoder`, `zscale`)
that the file does not list.

A test whose ffmpeg lacks a component skips with the reason id `oracle:ffmpeg-feature` (through the test kit's
`Oracle.missingFeature`), so that a local ffmpeg without, say, the AMR libraries skips the tests that need them.
Where `smalt.test.requireOracles` names ffmpeg, as on CI, it fails instead, and the skip baseline never accepts it.
To get the CI build locally (on Windows from an MSYS2 UCRT64 shell, with MSYS2's `python3`):

```
python tools/testdata/ffmpeg-oracle/build.py --prefix ~/.smalt/ffmpeg-7.1.2   # then put its bin first on PATH
```

## Skip reasons and the skip baseline

Every skip of a test goes through the test kit's `Skip`, with a stable reason id and a free-text detail,
`[family:name] detail`; JUnit's conditions put the same prefix in their `disabledReason`, and core's `SkipTest`
fails on any other way to skip. The families are `oracle` (a tool or a feature of one is missing), `device` (a
capture device), `env` (a property of the machine or JVM), which CI provisions, and the structural `data`,
`simd`, `platform`, `opt-in` and `input`.

`skips.py` reads the surefire reports and counts the skips per CI job (operating system, architecture, SIMD leg
and data tier, for example `linux-x64-vector-smoke`), module, test class and reason; `skip-baseline.tsv` holds
the accepted counts.

```
python tools/testdata/skips.py report --job linux-x64-vector-smoke     # counts the reports of the last build
python tools/testdata/skips.py check --job linux-x64-vector-smoke      # fails when a count grew
python tools/testdata/skips.py update --job linux-x64-vector-smoke     # lowers the baseline (never raises)
python tools/testdata/skips.py accept --job linux-x64-vector-smoke     # raises it for skips a change adds
```

A skip is acceptable only when the same test runs and passes on another job. `outcomes` writes the outcome of
every test case of a build, which CI uploads per job (`outcomes-<job>`), and `cover` fails when a test skipped
with a reason of the checked families passed in no other job: CI's `skip-cover` job checks the `simd` and
`platform` skips of a run, and the `device` and `opt-in` ones against the outcomes of the latest nightly
(`--evidence`); the nightly's checks its `device`, `opt-in` and `data` skips against its own jobs (the capture,
opt-in and raw coverage jobs) and its `simd` and `platform` ones against the latest CI run of the branch (of
`master` when the branch has none). `impossible-skips.tsv` lists the skips
no hosted runner can run (reason id, job glob, test class glob, reason), which `cover --impossible` accepts and
lists.

```
python tools/testdata/skips.py outcomes --job linux-x64-vector-smoke --out outcomes.tsv
python tools/testdata/skips.py cover outcomes-*.tsv --evidence nightly/outcomes-*.tsv --families simd,platform
```

CI runs `check` after every test job and uploads the job's `report` as an artifact (`skips-<job>`). A change that
adds skips on purpose (tests whose data is outside the smoke tier, kernels the scalar leg skips) runs `accept`
over that artifact (`--report <file>`) and commits the baseline; `accept` refuses to raise the `oracle`,
`device` and `env` counts, which call for provisioning the tool or device instead (`--environment` overrides,
for a new CI job), and a pull request whose baseline raises them fails (`shrink-only`).

## Capture devices

The capture workflow (`.github/workflows/capture.yml`, run by the nightly) provisions virtual devices on the
hosted runners and names them in `SMALT_CAPTURE_REQUIRE_DEVICES`, so that `Skip.unlessDevice` fails instead of
skipping for a provisioned kind: on Linux an Xvfb screen with a solid red root window (`SMALT_TEST_SCREEN_COLOR`),
a PulseAudio null sink looped to the default source and, when the runner's kernel can load it, a v4l2loopback
camera fed by ffmpeg; on Windows VB-Audio Virtual Cable (`.github/scripts/windows-audio.ps1`) and the runner's
desktop; on macOS BlackHole and the runner's display. `SMALT_TEST_AUDIO_LOOPBACK` names the loopback pair of
`AudioLoopbackLiveTest`, and `SMALT_CAPTURE_LIVE` turns on the macOS live tests.

## NEF oracle files

The raw module's `NikonTablesTest` re-derives the NEF Huffman tables from CC0 sample files against the
values Adobe DNG Converter stores for them. `python tools/testdata/nef-oracle.py` (Windows) downloads Adobe
DNG Converter 14.0 and innoextract 1.9 (both pinned by SHA-256), extracts the converter without installing
it, and writes one uncompressed DNG per sample into `<conformance cache>/nef-oracle`
(`-Dsmalt.test.nefOracle` overrides the directory the test reads). Without them the oracle tests are
skipped.

## Raw oracle files

The raw module's `RawOracleTest` checks that the ORF, RW2, PEF, Canon sRAW and mRAW, Coolpix NEF and MRW
decoders reproduce exactly the sensor values Adobe DNG Converter stores for CC0 sample files, and compares
the rendering of the converter's four-color DNG files with the previews it renders into them;
`OlympusDerivationTest` and `PanasonicDerivationTest` re-derive the Olympus compression and Panasonic's
raw format version 4 from witness samples, and `CrwTablesTest` re-derives the Canon CRW Huffman tables and
checks the CRW decoder. For the Sigma X3F samples it converts (DP1, SD14, SD15; it refuses the Merrill and
Quattro ones) the converter stores denoised, color-converted values, so `RawOracleTest` compares their image
area, the linear relation of their layers to the converter's values and their rendering.
`python tools/testdata/raw-oracle.py` (Windows, with a Windows build of Python: under an MSYS2 Python the
converter fails to open its input) downloads Adobe
DNG Converter 18.0 and innoextract 1.9 (both pinned by SHA-256), extracts the converter without installing
it, and writes one uncompressed DNG per sample into `<conformance cache>/raw-oracle`
(`-Dsmalt.test.rawOracle` overrides the directory the tests read). Without them the oracle tests are
skipped. With `--coverage` the script converts every ORF sample and every RW2 or RWL sample of version 4
in `vectors.tsv` (about 6 GB of DNG files) into `<conformance cache>/raw-oracle-coverage` for
`RawOracleCoverageTest` (`-Dsmalt.test.rawCoverage`).

## PNG oracles

The PNG encoder tests (`PngEncoderFormatsTest`, `PngEncoderChunksTest`, `ApngColorProfileTest`) read Smalt's
output with two independent readers besides ffmpeg, and skip the comparisons whose reader is missing:

- `png-oracle/smalt-pngdump.c`, a small libpng program (libpng 1.6.46 or later, for `cICP`, `mDCV` and `cLLI`)
  that prints the chunks libpng reports with its warnings, or the RGBA expansion of the pixels. Build it with
  `bash tools/testdata/png-oracle/build.sh <directory>` (from an MSYS2 UCRT64 shell on Windows, which provides
  gcc, libpng and zlib) and put the directory on `PATH`; it was checked with libpng 1.6.55.
- `png-oracle/ImageIoPngDump.java`, run by the tests as a source-file program in a separate JVM, so that no
  Smalt module reads `java.desktop`: the JDK's own PNG reader, writing the native metadata tree and the pixels
  as RGBA16.

## BMP oracles

`BmpSuiteTest` (`smalt-image`) decodes Jason Summers's public-domain BMP Suite 2.8 (`bmpsuite-2.8.zip` in
`vectors.tsv`; its generator source is GPL and is never read) and compares every good and questionable file with
the reference image the suite's `html/bmpsuite.html` names. `BmpEncoderTest` reads Smalt's BMP output with three
independent readers besides `BmpDecoder`, and skips the comparisons whose reader is missing:

- `bmp-oracle/ImageIoBmpDump.java`, run by the tests as a source-file program in a separate JVM: the JDK's own BMP
  reader, writing the pixels as RGBA8.
- `bmp-oracle/pillow-bmp-dump.py`, run with `python -P` by the Python of `-Dsmalt.test.python` or the first
  `python3` or `python` on `PATH`: Pillow's reader (checked with Pillow 12.0.0, whose RLE4 reader the test leaves
  out because it misreads BMP Suite's `g/pal4rle.bmp` too).
- ffmpeg.

## AMR reference decoders

`AmrDtxReceptionTest` (`smalt-mpeg`) decodes comfort noise streams with damaged, lost and bad-flagged frames, which
ffmpeg's libopencore decoders cannot check (they ignore the quality bit, and opencore's AMR-WB decoder predates the
current concealment and DTX rules of TS 26.173). `python -I tools/testdata/amr-oracle.py <directory>` downloads the
bit-exact ANSI-C simulations of TS 26.073 and TS 26.173 from the 3GPP archive (pinned by SHA-256) and compiles their
decoders, unchanged, with gcc into `<directory>/bin` as `smalt-amrnb-ref` (built with `MMS_IO`, so it reads the
storage format) and `smalt-amrwb-ref` (run with `-mime`). With that directory on `PATH` the test compares every
sample with them; without, it checks the SHA-256 of each decode against `dtx-reception.tsv`, which the reference
decoders produced (`-Dsmalt.amr.record=<file>` rewrites it while they are on `PATH`).

## Media Foundation oracle

`Mpeg4InterlacedDirectTest` (`smalt-mpeg`) checks interlaced direct mode of MPEG-4 Part 2 B-VOPs, which ffmpeg
neither encodes nor decodes as ISO/IEC 14496-2 7.7.2.2 specifies, against a model written in the test and, on
Windows, against the MPEG-4 Part 2 decoder of Media Foundation. `mf-oracle/MfDecode.cs` drives that decoder through
the Source Reader and writes planar 4:2:0 pictures; build it with `bash tools/testdata/mf-oracle/build.sh
<directory>` (it uses the C# compiler of the .NET Framework that ships with Windows) and pass
`-Dsmalt.test.mfDecode=<directory>/MfDecode.exe` to the tests; without it the comparison is skipped. The
decoder filters its output, so pictures match within a few sample values, and it measures B-VOP distances in
ticks of `vop_time_increment_resolution` rather than in frame periods, so only streams whose VOP times are frame
numbers are compared.

## HDR ICC profiles

The high-dynamic-range color tests of `smalt-core` (`HdrColorTest`) read two profiles kept with the tests
(`core/src/test/resources/com/github/auties00/smalt/image/color/hdr/`): `lcms-pq.icc` and `lcms-hlg.icc`, version
4.4 RGB profiles with the BT.2020 primaries, tone curves of 1024 entries and a `cicp` tag, written by Little CMS
2.17 in the 16-byte `cicp` layout it uses. `icc-hdr/smalt-lcmsdump.c` writes them (`smalt-lcmsdump write pq|hlg
<file>`, with the creation date cleared so that the output depends only on the Little CMS version), and
`icc-hdr/profiles.sha256` pins them. The same program is the oracle of the test that has Little CMS read Smalt's
PQ and HLG profiles (`info`: version, color space and `cicp` tag as Little CMS reads them; `gray`: a gray ramp
transformed to Little CMS's sRGB). Build it with `bash tools/testdata/icc-hdr/build.sh <lcms2 source directory>
<directory>` against the Little CMS 2.16 or later sources (MIT; checked with the 2.17 release tarball) and put the
directory on `PATH`; without it that test is skipped.

## Advanced ICC profiles

`AdvancedProfilesTest` (`smalt-core`) checks floating-point `multiProcessElementType` tables (`D2Bn`, `B2Dn`),
device links, abstract and named color profiles and profile chains against Little CMS 2.17. Its profiles and
reference outputs are kept with the test (`core/src/test/resources/com/github/auties00/smalt/image/color/advanced/`)
and written by `icc-advanced/smalt-lcmsadvanced.c`: profiles built through the Little CMS API (segmented curves
of every formula and sampled segments, matrices with offsets, float tables of one to six inputs, Little CMS's own
sRGB and a Display P3, device links made by `cmsTransform2DeviceLink` in versions 2 and 4, a BCHSW abstract
profile, named color lists with L*a*b* and XYZ connection spaces), with the creation date cleared; `cases.tsv`
with the outputs of each chain for the 8-bit and 16-bit input sets the program documents; `pipelines.tsv` with
`cmsPipelineEvalFloat` of every floating-point tag over a float input set; and `named.tsv` with what
`cmsNamedColorInfo` returns. Every computation reads the profiles back from their files. Build and run it with
`bash tools/testdata/icc-advanced/build.sh <lcms2 source directory> <directory>` against the Little CMS 2.17
release tarball (MIT; SHA-256 `6e6f6411db50e85ae8ff7777f01b2da0614aac13b7b9fcbea66dc56a1bc71418`), then copy
`<directory>/data` to the resource directory; `icc-advanced/profiles.sha256` pins every file, and the output is
the same on every run.

## 12-bit JPEG vectors

The 12-bit JPEG tests of `smalt-image` read vectors kept with the tests (`image/src/test/resources`): the
`cjpeg -precision 12` files in `jpeg/cjpeg12/` with the MD5 of `djpeg -dct int -scale 1/N` for each
(`jpeg/djpeg12-md5.csv`), the MD5 of `jpegtran` transforms of some of them, crop extension, `-wipe`, `-drop`,
`-arithmetic` and restart rows included (`jpeg/jpegtran12-md5.csv`), a Motion-JPEG AVI muxed by ffmpeg
(`jpeg/mjpeg12.avi`), and JPEG-compressed 12-bit TIFF files (`tiff/jpeg12/`) with the MD5 of the samples libtiff
decodes from them (`tiff/jpeg12.csv`). `bash
tools/testdata/jpeg12/make-vectors.sh <testorig12.jpg> <checkout>` regenerates them all from libjpeg-turbo's
testorig12.jpg (`ljt-testorig12.jpg` in `vectors.tsv`) and a synthetic pattern, with libjpeg-turbo 3.1.3,
libtiff 4.7.1, ffmpeg 7.1 and Python 3; the output is deterministic and `cjpeg12.sha256` and `jpeg12.sha256`
pin the files. The TIFF files are written by `tiff12.py` from strips and tiles coded by `cjpeg`, because
libtiff 4.7.1 itself writes 12-bit JPEG strips that decode to the wrong samples; libtiff also drops the last
sample of a decoded row with an odd number of samples, so the odd-width file is checked against djpeg strip
by strip instead.

## JPEG encoder mode vectors

`JpegEncoderModesTest` of `smalt-image` checks the encoder's coding modes against MD5 sums kept with the test
(`image/src/test/resources/com/github/auties00/smalt/image/jpeg/encoder/`). `cjpeg-cases.tsv` lists cjpeg runs
(input, switches, MD5) over libjpeg-turbo's test images (`ljt-testorig.ppm`, `ljt-testorig.pgm`,
`ljt-monkey16.ppm`, `ljt-monkey16.pgm`, `ljt-test1.icc` and `ljt-test.scan` in `vectors.tsv`) and the
quantization tables and scan scripts next to it (self-made: `tables2.txt` and `tables3.txt` hold seeded random
values, the `.scan` files hand-written scripts); `python -I tools/testdata/jpegenc/cjpeg-record.py <cjpeg>
<testimages> cjpeg-cases.tsv` records the MD5 column with libjpeg-turbo 3.1.3's cjpeg, and the test checks the
recorded value against a live cjpeg wherever one is installed. Ten of the cases are libjpeg-turbo's own CMake bit
tests, whose MD5 sums the recording reproduces. `turbojpeg-md5.csv` holds the MD5 of the CMYK, YCCK and JFIF
density files of the TurboJPEG API (which cjpeg cannot write) for the synthetic pattern of
`tools/testdata/jpegenc/turbojpeg-record.py`, recorded with `python -I turbojpeg-record.py <libturbojpeg>
turbojpeg-md5.csv` against libjpeg-turbo 3.1.3 (the MSYS2 UCRT64 build on Windows x64).

## Lossless Motion-JPEG vectors

`MjpegLosslessTest` of `smalt-image` reads AVI and Matroska files of two frames coded by ffmpeg's lossless JPEG
encoder (`image/src/test/resources/com/github/auties00/smalt/image/jpeg/mjpeg/`: 4:2:0, 4:2:2 and 4:4:4 Y'CbCr
with predictors 1 to 3, and RGB and RGBA through its reversible color transform), with the MD5 of the frames they
were coded from (`jpeg/mjpeg-md5.csv`). `bash tools/testdata/mjpeg/make-vectors.sh <checkout>` regenerates them
with ffmpeg 7.1, used as a black-box encoder, from its `testsrc2` pattern; the output is deterministic and
`jpeg/mjpeg.sha256` pins the files.

## 32-bit ALAC vectors

`AlacIntegerOutputTest` of `smalt-audio` reads the CAF files of Apple's reference ALAC encoder, the only encoder of
32-bit ALAC (`audio/src/test/resources/com/github/auties00/smalt/audio/alac/apple/`: 32-bit stereo and mono, each
ending with a partial frame), with the MD5 of the samples they were coded from (`apple/apple-md5.csv`, interleaved
32-bit little-endian). `python -I tools/testdata/alac-oracle/make-vectors.py <alacconvert> <checkout>` regenerates
them: alacconvert is the converter of github.com/macosforge/alac (Apache License 2.0) at commit c38887c, built from
source with gcc and g++ as the script describes; the script encodes deterministic 32-bit WAV files, checks that
alacconvert, and ffmpeg when it is on PATH, decode the CAF back to the source, and `apple.sha256` pins the files.

## TIFF codec vectors

`TiffCodecCompressionTest` of `smalt-image` reads small TIFF files whose strips or tiles are WebP, JPEG XL and
JPEG 2000 images (`image/src/test/resources/com/github/auties00/smalt/image/tiff/codecs/`), each next to a
`.ref.tif` holding the samples libwebp, libjxl or OpenJPEG decode from it. `python -I
tools/testdata/tiff-codecs/make-vectors.py <that directory>` writes them through tifffile 2026.10.15 and
imagecodecs 2026.10.10 (both BSD-licensed, used as black-box writers; `pip install tifffile imagecodecs
numpy`) from a synthetic pattern, checks that the lossless ones decode to it and the lossy ones do not, and
pins them in `tiff/codecs.sha256`; the output is deterministic for those versions.

## TIFF YCbCr data-unit vectors

`TiffYCbCrTest` of `smalt-image` reads interleaved subsampled YCbCr files (8 and 16 bits; 2x1, 2x2, 4x2 and 4x4
subsampling) whose data units libtiff compressed with LZW or Deflate and its horizontal predictor
(`image/src/test/resources/com/github/auties00/smalt/image/tiff/ycbcr/<name>.tif`), each next to the same
samples uncompressed without a predictor (`<name>.raw.tif`), and checks that both decode alike.
`bash tools/testdata/tiff-ycbcr/make-vectors.sh <checkout>` builds `smalt-ycbcrvectors.c` against libtiff 4.7.1
(libtiff licence, used as a black-box writer; an MSYS2 UCRT64 shell on Windows, or gcc and libtiff on Linux and
macOS) and writes them from a seeded pattern; the output is deterministic and `tiff/ycbcr.sha256` pins it.

## TIFF multi-ink vectors

`TiffInksTest` of `smalt-image` reads two separated TIFF files of named inks (`InkSet` 2): five 8-bit inks with
a version 4 `5CLR` profile, and six 16-bit inks plus an alpha sample in separate planes, with the predictor, and a
version 2 `6CLR` profile (`image/src/test/resources/com/github/auties00/smalt/image/tiff/inks/`), and compares
their sRGB rendering with the conversion Little CMS makes from the same profiles (`.ref.rgb`, `.ref.rgba`).
`bash tools/testdata/tiff-inks/make-vectors.sh <lcms2 source directory> <checkout>` builds
`smalt-inkvectors.c` with Little CMS 2.17 (MIT, compiled from its release tarball) and libtiff 4.7.1, both as
black boxes, writes the profiles, the files and the references from a seeded pattern, and pins them in
`tiff/inks.sha256`; the output is deterministic for those versions.

## TIFF Zstandard, LZMA, LERC, JBIG, PixarLog and NeXT vectors

`TiffCompressionsTest` of `smalt-image` reads small TIFF files of those compressions
(`image/src/test/resources/com/github/auties00/smalt/image/tiff/compressions/`), each next to a `.ref.tif` holding
libtiff's decode of it (tiffcp `-c zip`), and compares the samples exactly. `python -I
tools/testdata/tiff-compressions/make-vectors.py <that directory>` writes them with libtiff 4.7.1's tiffcp (with
zstd 1.5.7, liblzma 5.8.1, LERC 4.0.0 and JBIG-KIT 2.1) and tifffile 2026.10.15 with imagecodecs 2026.10.10, all
black boxes, from seeded images of every sample type; neither writes PixarLog or NeXT, so the script codes those
strips itself and adds PixarLog pages whose strips imagecodecs' PixarLog encoder codes. libtiff decodes one-sample
PixarLog rows wrongly, so a gray PixarLog reference is the first channel of libtiff's decode of an RGB twin. The
LERC encoder does not always code `lerc-int8.tif` the same way, which changes neither its decode nor its reference.
`tiff/compressions.sha256` pins the files.

## TIFF fax vectors

`TiffEncoderModesTest` of `smalt-image` compares the strips its encoder writes for bilevel pages with those of
small TIFF files libtiff wrote (`image/src/test/resources/com/github/auties00/smalt/image/tiff/fax/`): seeded
bilevel patterns of four widths (runs past the 2560-pixel make-up code included), uncompressed and as CCITT
modified Huffman (compression 2), its word-aligned variant (32771), T.4 (3) and T.6 (4). `python
tools/testdata/tiff-fax/make-vectors.py <that directory>` writes them through Pillow 12.0.0 and its bundled
libtiff 4.7.1 (used as a black-box writer; `pip install pillow`; run it without `-I` when Pillow is a user
install), checks that every file but the word-aligned ones decodes to its pattern (libtiff misreads its own
compression 32771 rows), and pins them in `tiff/fax.sha256`; the output is deterministic for those versions.
Pillow cannot set `T4Options`, so the script makes the T.4 variants with two-dimensional coding and fill bits
(`-g3-2d`, `-g3-fill`, `-g3-2d-fill`, and `-g3-2d-fine` at 196 rows per inch, whose parameter K is 4) with
libtiff 4.7.1's `tiffcp` and `tiffset`, which must be on `PATH`, from the uncompressed files. The test also
compares those variants, at more widths, strip heights and resolutions, with `tiffcp` at test time when it is
on `PATH`.

## Zstandard, XZ, LERC and JBIG decoder vectors

The decoders under `image/src/test/resources/com/github/auties00/smalt/image/internal/` are checked on their own:

- `zstd/frames`: frames the zstd 1.5.7 CLI writes at every level and with several frame options
  (`tools/testdata/zstd/make-vectors.py`), listed in `frames.tsv` with the size and SHA-256 of their content;
  `zstd/golden`: the golden decompression files of zstd 1.5.7's `tests/` (BSD-licensed), with the same listing
  in `golden.tsv`. `zstd.sha256` pins the frames and `golden.sha256` the golden files.
- `xz/files`: files XZ Utils 5.8.1 writes with every preset, check, LZMA2 option, filter chain and the legacy
  .lzma format, plus damaged copies (`tools/testdata/xz/make-vectors.py`), listed in `files.tsv`; `xz.sha256`
  pins them.
- `lerc/blobs`: Lerc2 blobs of every data type and version 2 to 6 that LERC 4.2.0 writes through imagecodecs,
  and Lerc1 blobs, which LERC only reads, that the script writes itself with every tile coding and checks against
  LERC's decode (`tools/testdata/lerc/make-vectors.py`, which checks that every coding path occurs), listed in
  `blobs.tsv` with the SHA-256 of the values and masks LERC decodes; `lerc.sha256` pins them. Two TIFF vectors
  (`lerc-lerc1-*`) hold Lerc1 blobs of this directory, so the TIFF script runs after this one.
- `jbig/entities`: the ITU-T T.82 clause 7.2 test image coded by JBIG-KIT's pbmtojbg (a GPL program used only as
  a black-box writer) with the test parameters of T.82, whose byte counts the script checks against T.82 Tables
  29 and 32, and smaller images coded with every option (`tools/testdata/jbig/make-vectors.py`), listed in
  `entities.tsv` with the SHA-256 of their source raster; `jbig.sha256` pins them.

## Lossless JPEG vectors

`LosslessVariantsTest` of `smalt-image` decodes the lossless streams of the ITU-T T.83 | ISO/IEC 10918-2
compliance data (`itu-t83-data-files.zip` of `vectors.tsv`: O1 and O2 Huffman, P1 and P2 arithmetic, with
subsampled interleaved and one-scan-per-component frames) against the reference samples of the same archive, and
B2 with the tables of B1, a baseline frame of 255 components. The archive is (c) ITU and ISO/IEC 1994 and comes
with a disclaimer and no license, so it is fetched into the conformance cache and never committed.
`image/src/test/resources/com/github/auties00/smalt/image/jpeg/lossless/variants/` holds small vectors of the
other variants (arithmetic coding, several scans, five components, a `cjpeg -lossless -scans` file of
libjpeg-turbo 3.1.3), pinned in `variants.sha256`; the test class `LosslessVectorWriter` writes them, from the
test encoder and the test DCT writer and with `cjpeg` on `PATH`, when run with
`-Dsmalt.jpeg.writeLosslessVectors=<directory>` (`-Dtest=LosslessVectorWriter`), and the output is deterministic.

## JPEG-LS vectors

`JpegLsConformanceTest` of `smalt-image` decodes the streams of the ITU-T T.87 | ISO/IEC 14495-1 conformance data
set (`itu-t87-data-set.zip` of `vectors.tsv`, HP's images for "testing product conformance", fetched into the
conformance cache and never committed): the lossless streams to their source images, the near-lossless ones within
their bound of them, and all of them to the SHA-256 of the decode of CharLS or, for the subsampled T8SSE streams
CharLS cannot decode, of the ISO/IEC 10918-7 reference software A (the two agree on every other stream).
`image/src/test/resources/com/github/auties00/smalt/image/jpeg/jpegls/` holds CharLS's encodings of synthetic
images (`charls/`, listed in `charls.tsv` with their options, mapping tables included) and the reference software
A's (`referenceA/`: subsampling, restart intervals, DNL), each with the SHA-256 of its encoder's decode, pinned in
`jpegls.sha256`. `python -I tools/testdata/jpegls/make-vectors.py <charls-driver> <referenceA jpeg>
<T87_data_set.zip> <that directory>` writes them: `charls-driver.cpp` of the same directory is built against CharLS
(BSD-3-Clause) at commit 1153c2529abf1d1740d245bcddb2b756df62df62 (`g++ -std=c++17 -O2 -DCHARLS_STATIC
-DCHARLS_LIBRARY_BUILD -Iinclude -Isrc src/*.cpp charls-driver.cpp`), and the reference software is the
`referenceA.zip` of https://standards.iso.org/iso-iec/10918/-7/ed-3/en/ built with `g++ -O2 -DUSE_AUTOCONF` after
its `configure`, run as a black box under its evaluation and conformance-testing license. The synthetic images are
regenerated by the tests (`JpegLsVectors.synthetic`), so the encoder is compared with CharLS byte for byte.

## Hierarchical JPEG vectors

`HierarchicalTest` of `smalt-image` decodes the hierarchical streams of
`image/src/test/resources/com/github/auties00/smalt/image/jpeg/hierarchical/`, which the ISO/IEC 10918-7 reference
software A (see above) wrote from synthetic images: DCT hierarchies at 8 and 12 bits (sequential and progressive,
Huffman and arithmetic, 4:2:0, restart intervals, DNL), DCT hierarchies with a final lossless frame and lossless
hierarchies at 8 to 16 bits. `hierarchical.tsv` lists the options, the SHA-256 of the reference's decode, of the
T.81 reconstruction of the Huffman-coded lossless hierarchies and of the source image; the reference's decode of each
DCT stream is kept as `<name>.raw` (planar 16-bit little-endian components) for the tolerance comparison, since the
inverse DCT of differential frames is the decoder's choice. Reference software A's encoder rounds its upsampled
references differently from J.1.1.2 (and its decoder differently again), so its lossless hierarchies do not decode
back to their source: `t81decode.py`, an independent Huffman lossless hierarchical decoder written from T.81 Annex J,
gives the expected samples, one off the source on some interpolated samples. Over DCT frames the encoder takes the
reference of the final lossless frame from its DCT reconstruction before the clamp of T.81 A.3.1, so the `<name>.raw`
of those cases is the source with that reference clamped (`t81decode.clamped_reference`). Each `-arith` case codes
the source of its Huffman twin, so the two decode alike.
`hierarchical.sha256` pins every file; `python -I tools/testdata/jpeg-hierarchical/make-vectors.py <referenceA
jpeg> <that directory>` writes them, deterministically. No T.83 hierarchical stream exists: the T.83 compliance
data lists them as not available.

## JPEG XL feature vectors

`JxlFeaturesTest` and the internal JPEG XL tests of `smalt-image` read small files with a preview frame, spot
color channels (RGB and gray, XYB and not), progressive passes, a gray XYB image with a parametric gray ICC
profile, `jhgm` gain map and `jumb` boxes (plain and in `brob` boxes) and animations with a `jxli` frame index
(`image/src/test/resources/com/github/auties00/smalt/image/jxl/features/`), each next to djxl's decode of it
(16-bit PNG, or float32 NumPy arrays for the previews). `python -I tools/testdata/jxl-features/make-vectors.py
<cjxl/djxl directory> <that directory>` writes them with libjxl 0.11.2's cjxl and djxl (BSD-3-Clause, the
release builds of `oracles.tsv`, black boxes) from ffmpeg 7.1's `mandelbrot` and `testsrc2` sources; since libjxl
cannot write a preview frame or a spot color channel, the script splices the image headers of cjxl's files and
checks each splice against djxl. `features.sha256` pins every file.

## AV1 super-resolution vectors

`Av1SuperresTest` of `smalt-video` decodes short IVF streams coded by libaom's aomenc with super-resolution
(fixed, random and quantizer-threshold denominators, with loop restoration, tiles and film grain, at 8, 10 and 12
bits in 4:2:0, 4:2:2, 4:4:4 and monochrome) and with random frame resizing
(`video/src/test/resources/com/github/auties00/smalt/video/av1/superres/`), which no small conformance vector
covers. `python -I tools/testdata/av1-superres/make-vectors.py <that directory>` codes them with aomenc and
records the SHA-256 of each stream and of aomdec's raw output in `superres.tsv` (aomenc and aomdec 3.15.2, the
MSYS2 UCRT64 package `mingw-w64-ucrt-x86_64-aom` 3.15.2-1, BSD-2-Clause, used as black boxes, on `PATH`); with
aomdec on `PATH` the test also compares with it directly.

## AV1 scalability, Annex B and large scale tile references

`Av1LayersTest` of `smalt-video` decodes the libaom scalability vectors (`av1-1-b8-22-svc-*`, from `vectors.tsv`)
at every operating point and with and without every spatial layer, against the size and MD5 of every frame
aomdec writes for the same settings (`video/src/test/resources/com/github/auties00/smalt/video/av1/layers/layers.tsv`,
written by `python -I tools/testdata/av1-layers/make-references.py <aomdec> <conformance cache> <that directory>`).
`Av1AnnexBTest` decodes short streams libaom's aomenc wrote in the length-delimited format of Annex B
(`.../av1/annexb/`, `python -I tools/testdata/av1-annexb/make-vectors.py <libaom build> <that directory>`, which
records the SHA-256 of each stream and of `aomdec --annexb --rawvideo` in `annexb.tsv`). `Av1LargeScaleTileTest`
decodes light fields coded with libaom's light field examples (`.../av1/lightfield/`,
`python -I tools/testdata/av1-lightfield/make-vectors.py <libaom build> <that directory>`, which checks that
`lightfield_tile_list_decoder` and `lightfield_decoder` agree and records the SHA-256 of the tile list decoder's
output in `lightfield.tsv`). All three use libaom 3.13.1 (BSD-2-Clause, the v3.13.1 tag built with CMake, MSYS2
UCRT64 gcc and nasm, with its examples) as a black box; the streams and references are kept with the tests.

## HEVC decoded picture hash vectors

`HevcPictureHashTest` of `smalt-mpeg` verifies the MD5, CRC and checksum SEI messages of short streams coded by the
HM reference encoder on pictures several coding tree block rows tall
(`mpeg/src/test/resources/com/github/auties00/smalt/video/hevc/hash/`; x265 restarts its chroma CRC at each row,
so its CRC streams can only be one row tall). `python -I tools/testdata/hevc-hash/make-vectors.py <HM source tree>
<that directory>` codes them with HM-18.0 (BSD-3-Clause, built with CMake as a black box), fails unless HM's
decoder verifies every hash, and records the SHA-256 of each stream and of HM's decoded output in `hash.tsv`.

## HEVC recovery point and field vectors

`HevcRandomAccessTest` and `HevcFieldTest` of `smalt-mpeg` decode short streams with MD5 picture hashes
(`mpeg/src/test/resources/com/github/auties00/smalt/video/hevc/random/`): HM-18.0 random access and low delay P
streams whose intra pictures are trailing I pictures carrying recovery point SEI messages (`DecodingRefreshType`
3), HM field coded streams (top and bottom field first, 8 and 10 bits, fields cropped by a conformance window),
x265 field coded streams (ffmpeg 7.1's libx265 4.0, with and without B pictures) and an HM progressive stream with
picture timing messages. `python -I tools/testdata/hevc-random-access/make-vectors.py <HM source tree> <that
directory>` codes them from ffmpeg's `testsrc2` pattern (interlaced with `tinterlace` for the fields), fails
unless HM's decoder verifies every hash and, for the fields, unless ffmpeg's decode woven two by two holds the
same frames as HM's output, and records in `vectors.tsv` the SHA-256 of each stream and of HM's output, the
picture order counts in decoding order and the MD5 of every output frame. HM's encoder output is deterministic;
x265's depends on its version.

## HEVC screen content coding and separate color plane vectors

`HevcSccConformanceTest` of `smalt-mpeg` decodes, next to the JCT-VC screen content coding conformance streams
(`hevc-scc-*.zip` in `vectors.tsv`, fetched because their licence is unstated), short streams the HM-SCM reference
encoder coded from ffmpeg's `testsrc` pattern (`mpeg/src/test/resources/com/github/auties00/smalt/video/hevc/scc/`):
all intra and low delay 4:4:4 at 8 bits with palette mode (predictor initializers in the SPS and the PPS), intra
block copy, the adaptive color transform, adaptive motion vector resolution, wavefronts and tiles; random access
4:2:0 at 10 bits; low delay monochrome at 8 bits and 4:2:2 at 10 bits with palettes small enough to need escape
samples; lossless 4:4:4, lossless and lossy coding units mixed, and 8-bit luma with 10-bit chroma.
`python -I tools/testdata/hevc-scc/make-vectors.py <HM bin> <HM cfg> <that directory>` codes them
with HM-16.21+SCM-8.8 (the `HM-SCC-extensions` branch of HM, BSD-3-Clause, built with CMake as a black box), fails
unless HM's decoder verifies every MD5 picture hash, and records the SHA-256 of each stream and of HM's output and
the MD5 of every output frame in `scc.tsv`.

`HevcSeparatePlanesTest` decodes 4:4:4 streams coded with `separate_colour_plane_flag`
(`mpeg/src/test/resources/com/github/auties00/smalt/video/hevc/planes/`), which no encoder writes:
`python -I tools/testdata/hevc-separate-planes/make-vectors.py <HM bin> <HM cfg> <that directory>` codes each plane
of a `testsrc2` clip as a monochrome stream with one HM configuration (low delay B with wavefronts, random access at
10 bits with temporal motion vector prediction, one intra picture), checks that HM's decoder verifies their picture
hashes, and splices the three streams into one: the SPS rewritten to 4:4:4 with separate planes, each slice segment
header given its `colour_plane_id` (the planes one after the other or interleaved, later planes starting with or
without `first_slice_segment_in_pic_flag`), the slice data copied, and an MD5 picture hash SEI message of the three
planes as HM decodes them appended to each access unit. The monochrome streams are kept too, and `vectors.tsv`
there records the SHA-256 of each stream and of the expected output and the MD5 of every frame.

`MpegDecoderBehaviorTest` runs both sets through the I/O behavior harness: its `HevcSamples` packs each stream into
an MP4 file in memory (one `hvc1` sample per access unit, cut after each picture hash SEI message, presentation
times in output order) and the intra separate-plane picture into an HEIC item, and compares the decodes with the
golden dumps under `mpeg/src/test/resources/dumps/hevc/` and `dumps/heif-items/planes/`.

## H.264 Extended profile vectors

`H264ExtendedProfileTest` of `smalt-mpeg` decodes short Extended profile streams with slice data partitions,
SP slices (switching ones included) and SI slices
(`mpeg/src/test/resources/com/github/auties00/smalt/video/h264/extended/`), which the JVT conformance suite lacks
apart from the SP streams SP1_BT_A and sp2_bt_b. Most are coded by the JM 19.1 reference encoder (ISO/IEC and
ITU-T reference software, run as a black box) and their expected frame hashes are the output of the JM decoder,
both built from the jvet/JM sources after `python -I tools/testdata/h264-extended/patch-jm.py <JM source tree>`,
which fixes the MinGW build and the decoder's departures from ITU-T H.264 8.6, 8.3.1.1 and 7.3.5.1 (its docstring
lists them; with them fixed, the decoder reproduces the reference YUV of SP1_BT_A and sp2_bt_b, which the unpatched
one does not). `python -I tools/testdata/h264-extended/make-vectors.py <JM bin directory> <JM cfg directory>
<output directory>` makes the inputs from ffmpeg's `testsrc2` pattern and writes every JM stream, the rows of
`streams.tsv` and `frames.tsv`; the JM encoder's output is deterministic, so the streams' SHA-256 checksums are
reproduced. The JM encoder never writes SI macroblocks, so `python -I
tools/testdata/h264-extended/make-si-streams.py <output directory>` writes two small streams with SI macroblocks bit
by bit and computes their expected frames with its own model of the standard's SI decoding, with which the patched
JM decoder agrees where it applies. `streams.tsv` records how each stream was made and which of them the encoder's
own reconstruction also matches.

## HEVC multi-layer vectors

`HevcMultilayerConformanceTest` of `smalt-mpeg` decodes the JCT-VC SHVC and JCT-3V MV-HEVC conformance streams
(`hevc-shvc-*.zip` and `hevc-mvhevc-*.zip` in `vectors.tsv`, fetched because their licence is unstated) and compares
every output layer set with the archive's output picture log, or each layer's whole output with its MD5.
`HevcMultilayerVectorTest` decodes short streams the SHM reference encoder of the scalable and multiview extensions
coded from ffmpeg's `testsrc` pattern (`mpeg/src/test/resources/com/github/auties00/smalt/video/hevc/multilayer/`):
spatial scalability at 2x (low delay) and 1.5x (random access with a CRA picture), quality scalability from an 8-bit
base to a 10-bit enhancement, two MV-HEVC views (which ffmpeg's MV-HEVC decoder must decode to the same pictures),
an alpha auxiliary layer with an alpha channel information SEI message, and a 2x enhancement over an external AVC
base layer coded by x264. `python -I tools/testdata/hevc-multilayer/make-vectors.py <SHM bin> <SHM cfg> <that
directory>` codes them with SHM-12.4 (BSD-3-Clause, compiled with g++ as a black box), fails unless SHM's decoder
verifies every MD5 picture hash of every layer and decodes the encoder's reconstruction, and records the SHA-256 of
each stream and the MD5 of every output frame of each layer in `multilayer.tsv`.

`HeicMultilayerTest` decodes the layered `lhv1` items of Nokia's multilayer001, 004 (an AVC base item referenced
with `exbl`) and 005 (MV-HEVC with a layer selector) conformance files, whose expected pictures
`python -I tools/testdata/hevc-multilayer/make-references.py <TAppDecoder> <directory of the files>` prints: the
item's `lhvC` parameter sets and data turned into an Annex B stream, decoded by SHM with the item's target output
layer set (with ffmpeg's decode of the AVC base item as SHM's external base layer pictures, and ffmpeg's MV-HEVC
decoder cross-checking the views). It also builds `lhv1` items around the first access unit of the SHM streams above.
`HevcAppleAlphaTest` runs on macOS only: avconvert's `PresetHEVCHighestQualityWithAlpha` codes an ffmpeg-made ProRes
4444 clip with alpha, and the test keeps the result in `target/media-fixtures/apple-hevc-alpha.mov`.

## H.264 auxiliary alpha vectors

`H264AuxiliaryAlphaTest` of `smalt-mpeg` decodes streams whose access units carry alpha auxiliary coded pictures
(`nal_unit_type` 19, `mpeg/src/test/resources/com/github/auties00/smalt/video/h264/auxiliary/`), next to the
`alphaconformanceG` conformance stream. The JM reference encoder cannot code auxiliary pictures, so `python -I
tools/testdata/h264-aux/make-vectors.py <JM bin directory> <JM cfg directory> <output directory>` assembles each
stream from two JM 19.1 encodes made with the same parameters (its docstring explains how), checks that the JM
decoder reproduces each encoder's reconstruction, and writes the rows of `streams.tsv` and `frames.tsv`: per
frame the MD5 of the primary picture as the JM decoder outputs it and the MD5 of its alpha plane. The inputs are
synthetic and the JM encoder is deterministic, so the streams' SHA-256 checksums are reproduced.

## H.264 multiview and scalable conformance oracles

The multiview (MVC) and scalable (SVC) conformance streams of ITU-T H.264.1 (`h264-MVC-*` in `vectors.tsv`, and the
archive sets `h264-SVC-*` of `archives.tsv`) are checked by `H264MvcConformanceTest` and `H264SvcConformanceTest` of
`smalt-mpeg`. The MVC streams ship without decoded views, so the expected MD5 of each view is the output of JM 19.1
(`ldecod -p DecodeAllLayers=1`) for the two-view streams and of JMVC 8.5 (`H264AVCDecoderLibTestStatic <stream>
<out> <views>`) for the others; the two agree on every view they both decode. Each SVC zip holds the sub-bitstream
of every layer the stream defines and its reference decoding, whose MD5 the test pins; the archive set of each zip
downloads only its sub-bitstreams, and the pinned MD5s were taken by streaming each reference decoding out of the
zip with HTTP range requests. Where the reference decoding predates the deblocking rule of the Scalable Baseline
profile for macroblocks with transform coefficients from the reference layer (ITU-T H.264 F.8.7.4.3, the
`profile_idc` 83 condition), which changes a few samples by a few levels, the pinned MD5 is that of the JSVM 9.19.15
decoder, which applies the rule, and the test marks it. The `.txt` description of a few layers lists the wrong frame
size, frame count or temporal level, so the operating points come from the sub-bitstreams themselves (their highest
DQId and `temporal_id`); the full stream of SVCBMST-1 is a copy of its second layer, so the test decodes the
sub-bitstream of the highest layer to select the others; and three layers of SVCHMTS-1 keep their quality layers in
some temporal levels only, which no target layer and temporal level select, so only their own sub-bitstreams are
decoded. Every reference decoder is ITU-T and ISO/IEC reference software, built from source (JM from jvet/JM, JMVC
8.5 and JSVM 9.19.15 from the ITU-T JVT site, with `g++ -DMSYS_WIN32` and the library defines of their projects) and
run as a black box.

## H.264 scalable vectors

`H264SvcStreamsTest` of `smalt-mpeg` decodes six small scalable streams coded by the JSVM 9.19.15 reference
encoder (`mpeg/src/test/resources/com/github/auties00/smalt/video/h264/svc/`): coarse-grain quality scalability
over three temporal layers of hierarchical P pictures, medium-grain quality scalability with hierarchical B
pictures, CABAC and key pictures that store reference base pictures, three spatial layers (dyadic CAVLC, then
1.5 times larger with CABAC), and three interlaced streams of macroblock-adaptive frames: quality scalability over
an interlaced base, dyadic spatial scalability between two interlaced layers, and an interlaced layer over a
progressive base. Field pictures are left out: with field pictures in a scalable layer the JSVM encoder writes
access units whose layer representations its own decoder rejects. `python -I tools/testdata/h264-svc/make-vectors.py <JSVM bin directory> <JSVM data
directory> <output directory>` codes them from a synthetic scene drawn at each layer's size, extracts the
sub-bitstream of every layer with JSVM's BitStreamExtractor (built without optimization, which the MinGW build
needs to run) and records in `streams.tsv` the SHA-256 of each stream and, per layer, its DQId, highest
`temporal_id`, frame count and the MD5 of the JSVM decoder's output. The JSVM encoder is deterministic, so the
streams' checksums are reproduced. The streams are also the seeds of the scalable fuzz target.

`H264SvcWrapperTest` runs the field and macroblock-adaptive codings of the AVC conformance streams
(`h264-AVCv1-*` and `h264-FRExt-*` of `vectors.tsv`) through the scalable decoding process: the test wraps each
stream into a scalable stream whose base layer is the AVC stream, with a prefix NAL unit before every base layer
slice, a subset sequence parameter set per sequence parameter set and a skipped quality refinement slice
(`quality_id` 1) over every slice, and decodes its highest layer. With transform coefficient level prediction
(`seq_tcoeff_level_prediction_flag` 1) the refinement reconstructs the base layer's own decoding, so the output
must match the stream's reference decoding, except in streams whose macroblocks change the quantization parameter:
there the refinement rescales the base layer's levels from each macroblock's quantization parameter to that of
its slice, and the expected MD5 is the JSVM decoder's decoding of the wrapped stream. Without level prediction
the refinement predicts its intra macroblocks as Intra_Base macroblocks, and the expected MD5 of the
macroblock-adaptive streams is the JSVM decoder's decoding of the wrapped stream (the JSVM decoder stops with a
failed assertion on the wrapped streams of field pictures, which are therefore checked with level prediction only).

## Aperio slide oracle files

The same test decodes a pyramid level of two Aperio slides of the OpenSlide test data
(`openslide-aperio-cmu-1-jp2k-33005.svs`, CC0, and `openslide-aperio-jp2k-33003-1.svs`, free to use and
distribute; both `full` tier) and compares it with OpenSlide's rendering and, for the 33005 slide, with the
tiles as OpenJPEG decodes them. `python -I tools/testdata/svs-oracle.py` writes both into
`<conformance cache>/svs-oracle` (`-Dsmalt.test.svsOracle` overrides the directory the test reads), running
OpenSlide 4.0.1 (openslide-bin 4.0.1.2) and imagecodecs as black boxes (`pip install openslide-python openslide-bin tifffile
imagecodecs numpy`). CI writes them on Windows, whose imagecodecs wheel decodes the 33005 level as Smalt does; the
OpenJPEG of the Linux wheel rounds the irreversible component transform of one sample otherwise. Without them the
comparison is skipped.

## Opus oracle

`OpusEncoderGoldenTest` and `OpusMultistreamGoldenTest` compare the SHA-256 of the Opus encoder's packets and
final ranges with those of libopus 1.5.2, whose float build without SIMD Smalt's encoder matches packet for
packet. `opus-oracle/` regenerates those hashes: `python -I tools/testdata/opus-oracle/build.py <dir>` (with gcc,
from an MSYS2 UCRT64 shell on Windows) downloads libopus 1.5.2 (pinned by SHA-256) and compiles it with
`msenc.c`, a harness that drives `opus_encoder_create` or `opus_multistream_surround_encoder_create` with the
tests' controls and silence padding; `python -I tools/testdata/opus-oracle/goldens.py
<conformance cache>/opus_testvectors-rfc8251 <dir>/msenc.exe <dir>/io` writes the inputs (`gen.py` builds
them from the RFC 8251 vectors with the tests' integer operations) and prints one golden line per
configuration. Its `check_` lines reproduce goldens of `OpusEncoderGoldenTest`, which validates the build; its
`p` lines, coded with `opus_projection_ambisonics_encoder_create` and decoded with the projection decoder, are
the goldens of `OpusProjectionGoldenTest`.

`build.py` also compiles `msdec.c`, which decodes a bitstream with the libopus multistream or projection
decoder while dropping packets by a fixed rule, concealing them or rebuilding them from the next packet's
in-band FEC: `python -I tools/testdata/opus-oracle/traces.py <conformance cache>/opus_testvectors-rfc8251 <dir>
<dir>/io` prints the goldens of `OpusPacketLossGoldenTest`. `python -I tools/testdata/opus-oracle/downmix.py
<conformance cache>/opus_testvectors-rfc8251 <dir> <dir>/io` downloads libogg 1.3.5 and opusfile 0.12 (pinned by
SHA-256), compiles `ofdown.c`, which muxes libopus packets into Ogg with libogg and decodes them with
opusfile's `op_read_float_stereo`, and prints the goldens of `OpusDownmixTest`.

## Vorbis oracle

`VorbisEncoderTest` and `VorbisBitrateManagementTest` compare the SHA-256 of the Vorbis encoder's
identification and setup headers and packets with those of libvorbis 1.3.7, which Smalt's encoder matches
packet for packet at a quality and in every bitrate managed mode. `vorbis-oracle/` regenerates those hashes:
`python -I tools/testdata/vorbis-oracle/build.py <dir>` (with gcc, from an MSYS2 UCRT64 shell on Windows)
downloads libvorbis 1.3.7, libogg 1.3.5 and the fdlibm 5.3 sources of netlib (pinned by SHA-256) and compiles
libvorbis with `vbm.c`, a harness that feeds it in 1024-sample chunks through `vorbis_encode_setup_vbr`, the
same followed by `OV_ECTL_RATEMANAGE2_SET`, or `vorbis_encode_setup_managed`. The build drops the `rint` that
libvorbis's `os.h` defines on Windows (it rounds exact halves up, which is the only difference between a Windows
libvorbis and Smalt), and links the libvorbis calls of `sin`, `cos`, `atan`, `exp`, `log` and `pow` to fdlibm,
the library of `StrictMath`. `python -I tools/testdata/vorbis-oracle/goldens.py <dir>/vbm.exe <dir>/io` writes
the inputs with `Signals.java` (the tests' signals, computed with `StrictMath`; it needs a JDK) and prints the
`CsvSource` rows of both tests.

## AAC-ELD low delay SBR oracle

The ISO/IEC 14496-26 streams of AAC-ELD with low delay SBR (`er_eld_sbr_*`) ship without reference waveforms, and
the suite has no stream with 480-sample frames, so `AacLowDelaySbrTest` (`smalt-mpeg`) compares its decodes with
those of libxaac (Apache-2.0) and of the Fraunhofer FDK AAC library, both used as black boxes. `python -I
tools/testdata/eld-sbr-oracle.py <work directory> [--fdk [library]]` (with CMake, Ninja and gcc; on Windows from an
MSYS2 UCRT64 shell, with a Windows Python 3.14 or later for `--fdk`) downloads libxaac at commit
`2fbadd57bca46693b7a077bcf1513131106d960c` (pinned by SHA-256), builds its `xaacdec` and `xaacenc` test
applications, and writes into `<conformance cache>/eld-sbr-oracle` (`-Dsmalt.test.eldSbrOracle` overrides the
directory the test reads):

- one libxaac decode per conformance stream, fetching the streams first when the cache lacks them;
- in `encoded/`, streams of 480- and 512-sample frames that `xaacenc` codes from a synthetic signal the script
  computes (harmonic notes with attacks, a noise floor and noise bursts), as the AudioSpecificConfig followed by
  the access units (`.raw`) with their sizes (`.meta`), and their libxaac decodes.

With `--fdk` it also encodes 480-sample streams with the FDK AAC encoder, at dual rate and downsampled (which
`xaacenc` does not code), and decodes them and the conformance streams with the FDK AAC decoder into
`<conformance cache>/eld-sbr-fdk` (`-Dsmalt.test.eldSbrFdk`); the dual-rate ones are decoded by libxaac too. On
Windows the library is the DLL of the MSYS2 package `mingw-w64-ucrt-x86_64-fdk-aac` 2.0.3-1 (pinned by SHA-256),
downloaded unless `--fdk` names a library; elsewhere `--fdk` names the system's `libfdk-aac`. The encoded streams
are deterministic for a build, and the script reports a stream whose digest differs from the reference build's.
Without these files the comparisons are skipped.

## Conformance suites read from remote archives

- Argon AV1 coverage streams (AOMedia, version 2.1.1, licence unstated: used for testing only): 25 archive sets
  `argon-av1-<group>` (`full` tier, never in the nightly's cache) for `Av1ArgonTest` (`smalt-video`, JUnit tag
  `argon`, excluded unless `-Dsmalt.test.excludedGroups=none -Dgroups=argon`; `-Dsmalt.test.argon=<groups>` picks
  groups). Each stream must decode to its `md5_ref` (aomdec's raw output), to `md5_no_film_grain` without film
  grain, and to the reference of every operating point its sequence header declares; the error groups must decode
  or fail with a `MediaException`; the streams of the six large scale tile groups are decoded as their reference
  command, libaom's `lightfield_tile_list_decoder`, does, and their tiles must have the reference MD5. The
  nightly runs the groups in eight shards with a 12 GB heap.
- DASH-IF livesim-content (Apache-2.0): 16 sets `dash-livesim-<presentation>-<representation>`, initialization
  segment and the first eight media segments, with the `_data.json.gz` segment lists in `vectors.tsv`, for
  `DashTestVectorsTest` (`smalt-core`).

## MPEG file format conformance

`FileFormatConformanceTest` (`smalt-core`) demuxes the 170 files of MPEGGroup/FileFormatConformance (commit
`767a5bcb`) and compares every track with the GPAC box dump the repository publishes with each file
(`mpegff-<file>_gpac.json`: sample sizes, decoding durations, composition offsets, sync samples) and with
ffprobe; the files ffmpeg reads otherwise than the dump are listed with the deviation, those it cannot read are
compared with the dump only, and the files with compressed boxes (`!mov`, `!mof`, `!six`, `!ssx`) are compared
with ffprobe's reading of a copy whose compressed boxes are decompressed. The dumps of the smoke-tier files are in
the smoke tier too, so that every pull request compares those files with both parsers.

## MPEG-4 Visual reference decoder

`mpeg4-oracle/build.py` builds the Microsoft MPEG-4 Visual decoder of the ISO/IEC 14496-5 reference software
(version 2.3-001213, from the Internet Archive's copy of ISO's Publicly Available Standards, pinned by SHA-256) as
`smalt-mpeg4ref`, modernizing its pre-standard C++ for g++ (clang rejects it, so macOS uses Homebrew's g++);
`oracles.py install` builds it on every platform. The decoder was written for a 32-bit `long`, the type of its
coordinates, so the build makes those `int` (with the 64-bit `long` of Linux and macOS the global motion
compensated streams `vcon-asp14L0` and `vcon-asp14L1` decode unlike on Windows, whose decode matches ffmpeg's); it
also links an `operator new` that zeroes every allocation, since the decoder reads members of the objects it
allocates before writing them, and widens the 100-byte buffers that hold its output file names.
`Mpeg4ConformanceTest` compares the ISO/IEC 14496-4 Advanced Simple streams (`mpeg4visual-vcon-asp*.bits`, Wayback
copies) and `Mpeg4InterlacedDirectTest` its synthetic interlaced direct mode streams with its decodes. Two
behaviors of the decoder shape those streams: it reads ahead of the last macroblock, so they end with a
`visual_object_sequence_end_code`, and it measures the temporal distances of interlaced direct mode in VOP time
ticks rather than in field periods of the frame period, so only the streams whose VOP times count frames are
compared with it.

## giflib encoder oracle

`gif-oracle/smalt-gifenc.c` writes a one-image GIF from raw indices with giflib 5.2.2's encoder (MIT, compiled into
the program by `oracles.py install`), the oracle of `GifLzwEncoderTest`'s live comparison.

## ALAC encoder oracle

`alac-oracle/smalt-alacenc.cpp` encodes raw interleaved samples with the encoder of Apple's ALAC library
(github.com/macosforge/alac at its last commit, c38887c; Apache-2.0, compiled into the program with g++ by
`oracles.py install`, with `TARGET_RT_LITTLE_ENDIAN` defined, since its byte-order helpers recognize only x86) and
writes the magic cookie and the packets, the oracle of `AlacEncoderOracleTest`'s byte-for-byte comparison.

## Adding a vector

1. Call `Conformance.fetch(name, url, sha256)` from the test, with a cache name unique across suites.
2. Run the tests that use it with `-Dsmalt.conformance.record=<absolute path>`; every requested vector is
   appended to that file.
3. `python tools/testdata/testdata.py record <that file>` adds the new entries to `vectors.tsv` in the
   `full` tier; move a row to `smoke` by hand when it is small and worth checking on every pull request.

The nightly workflow records what the tests request and fails when `vectors.tsv` misses an entry.

## I/O behavior samples

The I/O behavior harnesses of the test kit (see the `testkit.io` package documentation) read small committed samples
made from ffmpeg's synthetic lavfi sources: `behavior/make-samples.sh` writes the container samples of
`core/src/test/resources/samples` (MP4, fragmented MP4, QuickTime with ProRes, DNxHR and H.263, Matroska and WebM,
Ogg including a chained file, MPEG-TS and M2TS, AVI, WAVE, AIFF, MP3, MP2 with an ID3v1 tag, ADTS, AMR, AC-3,
E-AC-3, IVF, FLAC, SubRip and WebVTT), and calls `behavior/make-wav-samples.py`, which wraps encoded streams into
WAVE files with coded payloads (MPEG audio, AC-3, DTS, AAC in LOAS framing and as raw data blocks, FLAC frames, AMR
frames, self-delimited Opus, and IEC 61937 bursts of AC-3 and AAC); `behavior/make-image-samples.sh` writes the
image samples of `image/src/test/resources/samples` (GIF, BMP, ICO, TIFF with LZW, Deflate, PackBits, CCITT and JPEG
compression, tiles and pages, animated WebP, APNG, 16-bit PNG, JP2 and JPEG). The scripts need ffmpeg 7.1 with
libx264, libx265, libvpx, libaom, libopus, libvorbis, libmp3lame, opencore-amr and vo-amrwbenc, and libtiff's
`tiffcp`.

The golden dumps are tied to the samples' exact bytes, which another ffmpeg build does not reproduce, so the
committed files are the reference: `behavior/samples.tsv` pins each by size and SHA-256 (the behavior tests check
it), and `python -I tools/testdata/behavior/pin-samples.py` rewrites it after a deliberate regeneration, together
with the dumps (`-Dsmalt.test.updateDumps=true`).

# Test data

Smalt's tests read two kinds of external data, both listed here with SHA-256 checksums:

- `vectors.tsv`: the conformance vectors and sample files the tests request through the test kit's
  `Conformance.fetch` (ITU-T and ISO conformance streams, the libvpx, libaom, libwebp, Opus, Vorbis and
  FLAC test vectors, PngSuite, Xiph sequences). They are cached in `~/.smalt/cache/conformance`
  (`SMALT_CONFORMANCE_CACHE` or `-Dsmalt.conformance.cache` override it).
- `clips.tsv`: raw 4:2:0 clips for the real-time VP8 and VP9 encoder tests and quality reports, derived
  with ffmpeg from Xiph sequences (300 frames, the 60 fps sources halved to 30 fps, and a crop and scale
  for the 640x480 ones; ffmpeg 7.1 reproduces every checksum). They are
  cached in `~/.smalt/cache/clips` (`-Dsmalt.test.clips` for the tests, `SMALT_TEST_CLIPS` for the
  script). Without them the encoder tests use synthetic video.

Each entry belongs to a tier. `smoke` is small enough for the GitHub Actions cache and runs on every pull
request; `full` (which includes `smoke`) runs nightly. A test whose data is absent is skipped.

```
python tools/testdata/testdata.py fetch --tier smoke    # what CI's pull request jobs use
python tools/testdata/testdata.py fetch --tier full     # everything, clips included (needs ffmpeg)
python tools/testdata/testdata.py size                  # the size of each tier
python tools/testdata/testdata.py verify --tier full    # check the cache against the checksums
```

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

## Adding a vector

1. Call `Conformance.fetch(name, url, sha256)` from the test, with a cache name unique across suites.
2. Run the tests that use it with `-Dsmalt.conformance.record=<absolute path>`; every requested vector is
   appended to that file.
3. `python tools/testdata/testdata.py record <that file>` adds the new entries to `vectors.tsv` in the
   `full` tier; move a row to `smoke` by hand when it is small and worth checking on every pull request.

The nightly workflow records what the tests request and fails when `vectors.tsv` misses an entry.

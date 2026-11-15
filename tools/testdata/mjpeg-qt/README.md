# QuickTime Motion-JPEG tables and fixtures

The QuickTime File Format names the `mjqt` and `mjht` sample description extensions ("the default quantization table",
"the default Huffman table") and gives no layout. `MotionJpegTables` in `smalt-core` and `MjpegDecoder` in `smalt-image`
take the layout from QuickTime as a black box, through its public Image Compression Manager and Movie Toolbox, under the
user's approval of 2026-11-03. Nothing here disassembles, decompiles or reads the binaries: only the pictures they draw
and the files they write are read.

## The oracle

- `QuickTimeInstaller.exe`, QuickTime 7.7.9 for Windows from Apple's CDN
  (`secure-appldnld.apple.com/QuickTime/031-43075-20160107-C0844134-B3CD-11E5-B1C0-43CA8D551951/`), Authenticode
  signature valid (Apple Inc.), SHA-256 `56eff77b029b5f56c47d11fe58878627065dbeacbc3108d50d98a83420152c2b`;
  `QuickTime.qts` SHA-256 `a623f5a4c41c0e848800315df95883bcfaa4b2b0251a98e3661f15b140989171`, `QTMLClient.dll`
  `b8a433c774851eb0dc19d6e6d6941d60fb44b8b5c8d69fd391f1f427db09ea44`.
- It is not installed: see `tools/testdata/mace/README.md` for the `QTSystem` directory and the in-process registry
  values. The binaries are not committed and are not on CI, so CI tests run against the checked-in fixtures.
- `q.c` is a 32-bit driver with no C runtime; `build.sh` builds it into `QTSystem`. A GWorld must be the current port
  before `DecompressImage`, or nothing is drawn and the call still returns 0; `OSErr` results are 16 bits and the rest of
  the register is noise.

## What was measured

- QuickTime never writes these boxes. Movies exported through its Movie exporter (`spit`/`MooV`, the `sptl` atom set to
  `mjpb`) at qualities 0, 256, 512, 768 and 1023 carry only `fiel` and `gama`; each field has its tables inline: its own
  DQT, and the T.81 Annex K tables as its DHT. `make-fixtures.py` fails if that changes. So the box fixtures are
  QuickTime's own frames with their table offsets zeroed and the tables moved into a box, and QuickTime is the judge of
  every shape.
- `mjqt` and `mjht` each hold one marker segment from its length field on, without the marker code, the form the offsets
  of a format B header point to. Taken from a file too: a movie with the box draws exactly what the inline movie draws.
- Trailing bytes after the segment are ignored. Tables may come in any order and DQT tables may be 16 bits wide. One
  segment per table is not understood: only the first segment is read. With several boxes the first is read.
- A field's own tables win; each field decides alone (one field inline, the other from the box, draws the same). A box
  is never parsed for a field that does not need it: a broken box beside inline tables draws normally.
- Rejected with `codecErr` (-8969): no tables and no box, the payload without its length, the marker code included, bare
  tables, a length of 0, a length past the box, a length inside a table.
- A missing DHT without a box uses Annex K in format B. In format A QuickTime then fails; `MjpegDecoder` keeps Annex K
  for every form, since Motion-JPEG in AVI relies on it.
- Format A reads the boxes the same way, for frames with the `APP1 mjpg` segment (it rejects those without).
- QuickTime's decoder draws garbage rather than failing for a DQT with no tables (length 2), a table missing from the
  segment and a segment cut by the end of the box (it reads past the box). `MjpegDecoder` rejects these as malformed.

## Pictures

With a `gama` extension QuickTime converts the gamma on the way to any destination, so `export-*.argb.z` (zlib) are
drawn from a description without it, into a 32-bit ARGB GWorld, frames one after the other. Their samples, recovered with
the full-range BT.601 matrix and leaving out clipped pixels, against the coded samples of an accurate IDCT (ffmpeg's,
`MjpegDecoder`'s):

- Quality 768 and 1023: luma and chroma (averaged over the two pixels of a sample) within 1, mean about 0.3.
- Quality 0, 256 and 512: the mean stays under 1, but whole blocks depart from the accurate decoder, by up to 12 in luma
  and 48 in chroma on these frames. That is QuickTime's decoder at coarse tables, not a table problem: a wrong table
  gives tens on average, and the exact equality of box and inline drawings above is what proves the tables. This
  package did not look into why.

Regenerate with `bash build.sh <QTSystem>` and
`python -I make-fixtures.py <QTSystem> <scratch> image/src/test/resources/com/github/auties00/smalt/image/jpeg/quicktime`
(paths in Windows form; ffmpeg on `PATH` writes the JPEGs of the custom Huffman frames). `expected.tsv` records each
verdict: `equal` or `different` from the reference as the Movie Toolbox draws it, and `SHA256SUMS` pins the files.

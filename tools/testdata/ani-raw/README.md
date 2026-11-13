# Raw ANI frames against user32

`anih` has a flag, `AF_ICON`, for frames that are icon or cursor files, and fields (`cx`, `cy`, `cBitCount`, `cPlanes`)
for frames that are not. No published document gives the layout of such a raw frame, so `AniFile` was to take it from
Windows user32 as a black box, under the user's approval of 2026-11-03: draw crafted files through `LoadCursorFromFile`
and `DrawIconEx` and compare the pixels.

There is nothing to measure. `make-fixtures.py` run on Windows 11 (`10.0.22631`) found:

- **Every file without `AF_ICON` is rejected**, on all five entry points (`LoadCursorFromFile`, `LoadImage` as a cursor
  and as an icon, `CreateIconFromResourceEx` as a cursor and as an icon). The grid has 1,200 files: ten frame shapes (the
  bits alone, with a palette, with an AND mask, a DIB with and without a mask, the height doubled as in an icon image, a
  `BITMAPFILEHEADER` before them) at 32, 24, 8, 4 and 1 bits, each with `cx` and `cy` at the size, zero or double the
  height, `cPlanes` 1 or 0, `cBitCount` as stored or 0, and flags 0 or `AF_SEQUENCE`.
- **Icon frames are rejected too without the flag** (flags 0, 2, 4, `0x80000000`, `0xFFFFFFFE`), so it is the flag being
  clear that fails, not the frame shape.
- The same raw shapes with the flag set are rejected.
- The positive control, the same file with icon frames and flags 1 or 3, loads on all five, and `DrawIconEx` draws it:
  `control-*.bgra` are the pixels of its two steps, which `AniUser32Test` compares with `AniDecoder`.

So raw frames stay `MediaException.Unsupported`: a decoder of them would have no reference. An older Windows, which
might load them, was not available. The script fails if a later Windows accepts any raw shape, which would reopen this.

`AniDecoder` decodes icon frames without the flag although user32 refuses them: the frames are unambiguous, and being
lenient about a damaged header costs nothing.

Regenerate with `python -I make-fixtures.py <scratch> image/src/test/resources/com/github/auties00/smalt/image/ico/user32`
(`C:/Python314/python.exe` or another Windows Python). `expected.tsv` holds the verdicts and `SHA256SUMS` pins the files.

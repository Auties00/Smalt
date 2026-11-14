# MPEG-4 Studio profile test vectors

`make-vectors.py` writes the self-generated vectors of `Mpeg4StudioConformanceTest`
(`mpeg/src/test/resources/com/github/auties00/smalt/video/mpeg4/studio`), the Studio fuzz seeds and the
`elementary/mpeg4-studio.m4v` I/O behavior sample:

    python -I tools/testdata/mpeg4-studio/make-vectors.py <output directory>

It is a small intra-only Simple Studio encoder written for the tests, deterministic, with no input files. Its
pictures are synthetic gradients, checkers and pseudo-random noise. Every vector is listed in the `vectors.tsv`
it writes (name, mode, size, chroma format, precision, frames, the MD5 of the expected output of a lossless
vector, the SHA-256 of the stream):

- `studio-dpcm-*`: DPCM macroblocks only, which code the source losslessly, so the expected output is the source:
  4:2:0, 4:2:2 and 4:4:4, 8, 10 and 12 bits, interlaced and progressive layers, frame VOPs and top and bottom
  field first field VOP pairs, an R'G'B' layer, a size that is not a multiple of 16, and `studio-dpcm-options`
  (a visual object sequence with a Studio profile, the Core Studio object type, user data at the layer and VOP
  level, both DPCM scan orders, rice parameter 15, slice extensions, several rows per slice, intra predictor
  resets and a not coded VOP).
- `studio-dct-*` and `studio-mixed-*`: DCT macroblocks (and DPCM ones for the mixed vector) quantized with the
  default and downloaded matrices, linear and non-linear quantizer scales, macroblock quantizer changes, the
  alternate scan, DCT and intra DC precisions, field DCT and field VOPs. Their decode is pinned in `golden.tsv`;
  the 10-bit 4:2:2 and 4:4:4 frame VOP vectors without field DCT or downloaded matrices also match FFmpeg's
  decode within one unit (`Mpeg4StudioConformanceTest.Generated.againstFfmpeg`).

## Provenance of the Studio syntax

The final Studio syntax of ISO/IEC 14496-2 was not available as text. The reference decoder of ISO/IEC 14496-5
Amd 1 (MoMuSys, `StudioProfile-MoMuSys`) implements the FDAM 1 text (N3898), and it neither decodes the
ISO/IEC 14496-4 conformance streams (`vcon-stp*`) nor is decoded by FFmpeg. The syntax Smalt and this script use
was derived as follows, with FFmpeg 7.1 (`ffmpeg version 7.1-essentials_build-www.gyan.dev`, Windows x64,
`ffmpeg.exe` SHA-256 `2ce797a0f88d7f067180338fb227f7b1928ea727bd9a4d7a1d022f7c52af71a3`) run only as a black box,
its source never read:

1. FFmpeg decodes `vcon-stp11L1` (a mix of DCT and DPCM macroblocks; the FATE sample `mpeg4_sstp_dpcm.m4v` is its
   first 253110 bytes) without errors. Flipping bits of its macroblocks and reading FFmpeg's output located the
   fields of the macroblock header and of the DPCM and DCT blocks.
2. The AC coefficient group codes were measured on crafted slices: one test macroblock per slice, 675 per picture,
   with FFmpeg's `Error at MB` messages telling the codes it rejects and a DCT of its output telling the run and
   level of the coefficients it decoded. For each context, every code was found by testing candidate prefixes
   followed by a terminator whose meaning was already known (the end of block code of the context reached, or a
   run-one zero-one code followed by the end of block code of context 16). The twelve tables are complete
   prefix codes (every code space is used but the prefixes of six zeros, which would emulate start codes).
3. The macroblock layer of field DCT, quant matrix extensions, the DC sizes above 8 (with their marker bit), 8 and
   12-bit samples and the other conformance streams were checked by parsing every I-VOP of `vcon-stp1` to
   `vcon-stp11` to the last bit of every slice, and by the visual check of the decoded pictures; FFmpeg ignores
   `dct_type`, mishandles downloaded matrices and field VOPs, and does not decode 8 and 12-bit or 4:2:0 layers.
4. The DC size codes, the DPCM residual coding and prediction (but for its starting predictor, which FFmpeg's
   decode shows is half the sample range, not the block mean), the dequantization with its mismatch control and
   the inverse DCT are the reference software's.

The P-VOPs of the Core Studio conformance streams use other coefficient codes that no available decoder
reproduces; they are not decoded.

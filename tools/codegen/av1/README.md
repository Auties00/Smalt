# AV1 vector kernel generators

Three classes in `video/src/main/java/com/github/auties00/smalt/video/av1/internal/` are generated,
and four methods of a fourth: their Vector API kernels are straight-line code too repetitive to maintain
by hand, split into methods under the module's 2000-byte kernel limit.

- `VectorItxDsp.java`: `gen_vitx.py` derives the vector inverse transforms from the scalar 1-D
  transforms of `ScalarItxDsp.java`; the hand-written parts come from `VectorItxDsp.java.head`. The
  32-point DCT (8 lanes) is the largest: its even half is the generated 16-point kernel, its odd half
  and output butterflies are split into six methods that hand their live values on through the elements
  of the odd inputs they have consumed (`SPLITS` and `DCT32_FIRST` in the generator).
- `VectorLoopFilterDsp.java`: `gen_vlf.py` appends the generated filter kernels to the hand-written
  parts in `VectorLoopFilterDsp.java.head`.
- `VectorCdefDsp.java`: `gen_vcdef.py` appends the generated CDEF filter kernels to the hand-written
  parts (padding, direction search, dispatch), which it holds in its `header` literal.
- `VectorMcDsp.java` (hand-written except for four methods): `gen_vmc_vertical.py` rewrites the
  floating-point vertical filters `vertical`, `verticalPlain` (8 taps) and `verticalSix`,
  `verticalPlainSix` (taps 1 to 6) in place, between the `// Begin generated` and `// End generated`
  marker comments.

`fmt_java.py` wraps the generated lines to the 120-column limit. The generators keep the line endings
of the files they replace.

Regenerate with:

```
python regen.py
```

and run `python ../check.py`, which regenerates every kernel into a temporary copy and fails on any
difference from the committed files (CI runs it too). Never edit a generated region by hand: change the
generator or its `.head`, regenerate, and commit both. After a change, run `ItxDspTest`,
`LoopFilterDspTest`, `CdefDspTest`, `McDspTest`, `Av1ConformanceTest` and `VideoKernelGuardTest`.

# VectorVp9Dsp generator

`video/src/main/java/com/github/auties00/smalt/video/vp9/internal/VectorVp9Dsp.java` is generated:
its Vector API kernels are straight-line code (every tap, loop-filter position and transform
butterfly spelled out, every load and conversion cast to its exact vector class) that is too
repetitive to maintain by hand, and its inverse transforms are derived from the scalar 1-D
transforms of `ScalarVp9Dsp.java`.

- `VectorVp9Dsp.template` holds the hand-written parts (class javadoc, constants, dispatch,
  copy/average, DC-only and true-motion kernels) and the `//@MC`, `//@LF`, `//@TX` and `//@TXCONST`
  markers.
- `mcgen.py` writes the eight-tap motion compensation kernels.
- `lfgen.py` writes the loop filter kernels and the 8x8 transposes.
- `txgen.py` parses the scalar 1-D transforms (`idct4` to `idct32`, `iadst4` to `iadst16`) and
  writes their vector versions, split into stages under the module's 2000-byte kernel limit.

Regenerate from this directory with:

```
python gen_vp9_dsp.py
```

and run `python ../check.py` (CI runs it too), which fails when the committed file differs from the
generators' output; edit a generator or the template, never the generated file. After a change, run
`Vp9DspEquivalenceTest`, `Vp9ConformanceTest` and `VideoKernelGuardTest`.

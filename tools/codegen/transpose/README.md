# VectorTransformKernel generator

`core/src/main/java/com/github/auties00/smalt/image/ops/VectorTransformKernel.java` is generated:
its unrolled Vector API transposition networks are too repetitive to maintain by hand.
`VectorTransformKernel.template` holds every hand-written part (constants, shuffle builders, class
doc, the `transpose8`/`transpose32` drivers and the `reverse8`/`reverse32` kernels); `gen_transpose.py`
writes the tile bodies at the `@@B_LEFT@@`, `@@B_RIGHT@@`, `@@I8@@` and `@@I4@@` markers.

Regenerate from this directory with:

```
python gen_transpose.py VectorTransformKernel.template ../../../core/src/main/java/com/github/auties00/smalt/image/ops/VectorTransformKernel.java
```

and run `python ../check.py` (CI runs it too), which fails when the committed file differs from the
generator's output. After a change, run `KernelEquivalenceTest` and `JitRobustnessTest`.

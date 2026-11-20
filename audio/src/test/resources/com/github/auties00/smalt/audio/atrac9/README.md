# ATRAC9 test vectors

Self-generated: no permissively licensed ATRAC9 encoder or sample exists. Each `<name>.a9` is the 4-byte ATRAC9
configuration word followed by the superframes, written by the opt-in test `Atrac9VectorWriter`
(`-Dsmalt.atrac9.writeVectors=true`) from `Atrac9TestStreams`: the `random-*` vectors code every element of the
bitstream with random legal values (intensity stereo, the gradient modes, band extension, every scale factor coding
mode, prefix-coded and fixed-length spectra, fine precision, low-frequency effects blocks, reused band parameters),
and the `encoded-*` vectors code synthetic tones through the test encoder. `tools/testdata/atrac9-oracle/make-vectors.py`
decodes them with `smalt-at9dec` (LibAtrac9 at commit `efca2e3`, MIT, built without fused multiply-adds) and records
in `expected.tsv` the SHA-256 of each vector, its superframe count and the MD5 of the 16-bit little-endian
interleaved decode.

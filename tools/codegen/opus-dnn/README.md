# Opus neural models

`audio/src/main/resources/com/github/auties00/smalt/audio/opus/internal/dnn/*.bin.gz` hold the weights of the seven
networks of the libopus 1.5.2 neural extensions (BSD-3, Xiph.Org, Amazon, Mozilla) that the `audio.opus.internal.dnn`
port runs: the pitch estimator (`pitchdnn`), the FARGAN vocoder (`fargan`), the deep PLC feature predictor (`plc`),
the DRED encoder and decoder (`rdovae-enc`, `rdovae-dec`) and the LACE and NoLACE enhancers (`lace`, `nolace`).
About 17 MB of C initializers become 4 MB of resources instead of Java source.

- `dump.c` includes the libopus `dnn/*_data.c` files and writes each model's `WeightArray` list in the record format
  of libopus's own `dnn/write_lpcnet_weights.c` (a 64-byte header, `DNNw`, version 0, type, size, block size and
  name, then the data padded to 64 bytes), the format `WeightBlob` reads.
- `regen.py` downloads libopus 1.5.2 (pinned by SHA-256), builds and runs `dump.c` with gcc, gzips each blob with
  modification time 0, and generates from the C sources the `*Model` classes (the constants of each model header and
  one layer field per `linear_init` or `conv2d_init` call of its `init_*` function), `LpcnetTables` (the analysis
  FFT, window and DCT of `dnn/lpcnet_tables.c`) and `DredStats` (the DRED quantization statistics). Float literals
  are copied as text, which Java and C round to the same float.

Regenerate with:

```
python -I tools/codegen/opus-dnn/regen.py <work directory>
```

and check that the resources and the generated classes are identical to the committed ones; `codegen/check.py` does
not check them, since regenerating downloads and compiles libopus. After a change, run `OpusDnnUnitTest` and
`OpusDnnGoldenTest`, whose vectors pin the networks to libopus's outputs.

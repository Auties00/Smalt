# Vorbis encoder setup data

`audio/src/main/resources/com/github/auties00/smalt/audio/vorbis/internal/vorbis-setup.bin.gz` holds the
tuning data of the libvorbis 1.3.7 encoder (BSD-3, Xiph.Org) that `VorbisEncoder` ports: the
17 setup templates of `vorbisenc.c`'s `setup_list` (44.1/48 kHz coupled, uncoupled and 5.1, 32,
22, 16, 11 and 8 kHz, and the generic high and low rate ones), every static codebook they reference, the
psychoacoustic tables (`_psy_info_template`, `ATH`, `tonemasks`) and the window tables of
`window.c`. About 1.6 MB of C initializers become a 129 KB resource instead of Java source.

- `dump.c` compiles against the libvorbis sources (it includes `vorbisenc.c` to reach its static
  templates) and serializes them, little-endian, in the order `VorbisSetupData` reads them. Optional
  arrays carry a presence flag; codebooks are written once and referenced by index.
- `regen.sh` downloads libvorbis 1.3.7 and libogg 1.3.5, checks their SHA-256 digests, builds
  `dump.c` with the host C compiler and writes the gzipped resource (`gzip -9 -n`, so the output
  does not depend on the time it was made).

Regenerate with:

```
bash regen.sh
```

and check that the resource is byte-identical to the committed one. A change to `dump.c` needs the
matching change to `VorbisSetupData`; after it, run `VorbisEncoderTest`, whose golden digests pin
the encoder's packets to libvorbis's.

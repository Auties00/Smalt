# Third-party notices

Every Smalt jar ships `META-INF/THIRD-PARTY-NOTICES`, listing the open source projects its code is ported
from or derived from and whose licenses require their notices in copies, with their copyright notices and
full license texts.

A project gets a section only when both hold: Smalt's code is ported or derived from its code (C code
translated, tables copied, code structure followed closely), and its license requires attribution in copies
(BSD, MIT, ISC, Apache-2.0, the IJG, LibTIFF and similar licenses). Projects read only as documentation or
specifications, projects run as black-box test oracles (FFmpeg, x264, Adobe DNG Converter), and projects
whose license asks for no attribution in binaries (zlib, libpng, CC0, public domain, the Unlicense) get no
section; README.md thanks all of them under Acknowledgments. When unsure, keep the section.

- `licenses/` holds the upstream license files, fetched verbatim.
- `build-notices.sh` assembles `THIRD-PARTY-NOTICES` here: one numbered section per project, each naming the
  Smalt packages derived from it under `Derived Smalt packages:`. Only the prose in the script is authored;
  license texts are concatenated verbatim.
- `split-notices.py` writes each artifact's `src/main/resources/META-INF/THIRD-PARTY-NOTICES` with the sections
  whose packages it contains, plus the sections those refer to. It maps packages to artifacts from the source
  tree, writes that map to `packages.tsv` (generated, for reference), and fails when a `Derived Smalt packages:`
  list names a package that does not exist.

Attribution has two parts, both required for a port: the ported file names its source in a `/* Ports ... */`
comment above its `package` line (project and version, the files ported, copyright holder, license, and
"Modified for Smalt."), and, when the license asks for attribution in copies, the project's section here names
the file's package. For example:

```
/* Ports vo-amrwbenc 0.1.3 (voAMRWBEnc.c). Copyright VisualOn; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.wbenc;
```

When code is ported from a new project that needs a section, or a package derives from an additional one:

1. Fetch the project's license file into `licenses/`.
2. Add or extend its section in `build-notices.sh`, naming the derived packages.
3. Add the `/* Ports ... */` header to every ported file.
4. Run `bash build-notices.sh && python split-notices.py`, and commit the regenerated files (`packages.tsv`
   included).

Port only from permissively licensed code (MIT, BSD, Apache-2.0, ISC, zlib, public domain). GPL and LGPL code,
FFmpeg included, may be used only as a black-box reference, never read or ported.

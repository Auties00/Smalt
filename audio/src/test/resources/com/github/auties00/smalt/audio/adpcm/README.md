# ADPCM reference files

Self-generated test files of `AdpcmDecoderTest`, pinned in `../adpcm.sha256`:

- `ima-ffmpeg.wav`: IMA ADPCM (`0x0011`, stereo, 8 kHz, 1024-byte blocks) written by ffmpeg's `adpcm_ima_wav`
  encoder from a synthetic signal.
- `ms-acm.wav`: Microsoft ADPCM (`0x0002`, stereo, 8 kHz, 512-byte blocks) written by Windows' Audio Compression
  Manager (`msadp32.acm`) from a synthetic signal.
- `ms-random.wav`: Microsoft ADPCM (stereo, 256-byte blocks) whose blocks are random bytes with valid predictor
  indices, so that the deltas grow past 16 bits.
- `ima4-ffmpeg.aif`: AIFF-C `ima4` (mono, 8 kHz) written by ffmpeg's `adpcm_ima_qt` encoder.
- `ima-acm-mono.wav`, `ima-acm-stereo.wav`: IMA ADPCM (256-byte mono and 512-byte stereo blocks) written by
  Windows' Audio Compression Manager (`imaadp32.acm`, through `acmFormatSuggest` and `acmStreamConvert`) from
  `../gsm/speech8k.raw`, mono, and from that signal paired with itself advanced by 37 samples (wrapping around), stereo;
  `AdpcmEncoderTest` checks that Smalt's encoder writes the same blocks.
- `ms-acm-mono.wav`: Microsoft ADPCM (256-byte mono blocks) written by `msadp32.acm` from `../gsm/speech8k.raw`;
  `AdpcmEncoderTest` checks that Smalt's encoder codes that input at least as accurately.

The expected decodes in the test are the SHA-256 digests of the 16-bit little-endian output of Windows' ACM codecs
(`imaadp32.acm`, `msadp32.acm`, through `acmStreamConvert`) for the three WAVE files and of ffmpeg's `adpcm_ima_qt`
decoder for the AIFF-C file.

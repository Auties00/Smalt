# GSM 06.10 reference files

Self-generated test files of `GsmCodecTest`, pinned in `../gsm.sha256`:

- `speech8k.raw`: 2 s of 16-bit little-endian mono at 8 kHz, made by
  `ffmpeg -f lavfi -i "aevalsrc=exprs='0.5*sin(2*PI*(150+80*sin(2*PI*0.9*t))*t)*(0.6+0.4*sin(2*PI*3*t))+0.15*sin(2*PI*1200*t)+0.02*(random(7)-0.5)':s=8000:d=2" -f s16le`.
- `libgsm.gsm`: that input coded by libgsm 1.0 (ffmpeg's `libgsm` encoder, raw 33-byte frames).
- `libgsm-ms.wav`: the same input coded by libgsm in Microsoft's layout (ffmpeg's `libgsm_ms` encoder, WAVE `0x0031`).

libgsm's decode of both files (ffmpeg's `libgsm` and `libgsm_ms` decoders, identical to ffmpeg's native `gsm`
decoder) has the SHA-256 the test pins.

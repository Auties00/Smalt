# smalt-audio

> Pure-Java audio codecs (Opus, Vorbis, FLAC, ALAC, MP3, Speex, WavPack, ATRAC9 and more) and voice processing.

[← Back to Smalt](../README.md) · [Javadoc](https://javadoc.io/doc/com.github.auties00/smalt-audio)

## At a glance

- Decodes 18 formats and encodes 11 of them, from Opus and FLAC to G.722: see [Formats](#formats).
- Plugs into core's `AudioReader`, `AudioWriter` and `Transcoder` once on the class path; use a codec class directly for raw packets.
- `VoiceProcessor` brings WebRTC's echo cancellation, noise suppression and gain control to calls: see [Voice processing](#voice-processing).
- **Bit-exact**: decoded samples equal the reference decoder's. **Byte-identical**: encoded output equals the reference encoder's.
- Most decoders are bit-exact; the Opus, Vorbis, ALAC and IMA ADPCM encoders are byte-identical. Details are under [Formats](#formats).
- Needs Java 26 and [smalt-core](../core/README.md#at-a-glance) only. Optional: `--add-modules jdk.incubator.vector` (plus `-Dsmalt.simd=true` on Windows) turns on SIMD; see [JVM flags](../README.md#jvm-flags).

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-audio</artifactId>
</dependency>
```

The version comes from the BOM: see [Install](../README.md#install) for the BOM import and Gradle.
On the module path, add `requires com.github.auties00.smalt.audio;`.

## Examples

### Decode a file to samples

`AudioReader` picks the decoder from this module and returns float blocks, one plane per channel.

```java
try (var source = MediaSource.of(Path.of("song.mp3"));
     var reader = AudioReader.open(source)) {
    System.out.println(reader.sampleRate() + " Hz, " + reader.layout());
    for (var block = reader.read(); block != null; block = reader.read()) {
        float[] left = block.plane(0);
        System.out.println(block.frames() + " frames");
    }
}
```

### Transcode a file to FLAC

`Transcoder` demuxes, decodes, re-encodes and muxes in one call.

```java
Transcoder.transcode(Path.of("song.wav"), Path.of("song.flac"), TranscodeOptions.of(OutputContainer.FLAC));
```

### Encode a WAV file to Opus

`AudioWriter` finds the Opus encoder of this module through core's codec registry.

```java
var input = AudioReaderOptions.of(48_000, ChannelLayout.STEREO);
var output = AudioWriterOptions.builder()
        .codec(Codec.OPUS)
        .sampleRate(48_000)
        .layout(ChannelLayout.STEREO)
        .bitrate(96_000)
        .build();
try (var source = MediaSource.of(Path.of("song.wav"));
     var sink = MediaSink.of(Path.of("song.opus"));
     var reader = AudioReader.open(source, input);
     var writer = AudioWriter.open(sink, OutputContainer.OGG, output)) {
    for (var block = reader.read(); block != null; block = reader.read()) {
        writer.write(block);
    }
    writer.finish();
}
```

### Encode MP3 packets at variable bitrate

`Mp3Encoder` works packet by packet; `vbr(2)` picks VBR quality 2 on a scale from 0 (best) to 9 (smallest).

```java
var packets = new ArrayList<Packet>();
try (var source = MediaSource.of(Path.of("song.wav"));
     var reader = AudioReader.open(source, AudioReaderOptions.of(44_100, ChannelLayout.STEREO));
     var encoder = new Mp3Encoder(Mp3EncoderOptions.builder()
             .format(44_100, ChannelLayout.STEREO)
             .vbr(2)
             .build())) {
    for (var block = reader.read(); block != null; block = reader.read()) {
        encoder.encode(block, packets::add);
    }
    encoder.flush(packets::add);
}
```

### Read FLAC samples as exact integers

`decodeIntegers` hands you the stored samples, with no trip through floating point.

```java
try (var source = MediaSource.of(Path.of("song.flac"));
     var demuxer = Containers.openDemuxer(source);
     var decoder = new FlacDecoder((AudioTrack) demuxer.tracks().getFirst())) {
    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
        decoder.decodeIntegers(packet, block ->
                System.out.println(block.frames() + " samples of " + decoder.bitsPerSample() + " bits"));
    }
}
```

### Encode and decode Opus packets for a call

Each call fills your own arrays. At complexity 5 or more, the decoder hides lost packets with libopus 1.5's neural concealment.

```java
try (var encoder = new OpusEncoder(OpusEncoderOptions.forVoip(48_000, 1).toBuilder()
             .inbandFec(true).packetLossPercent(10).build());
     var decoder = new OpusDecoder(OpusDecoderOptions.of(48_000, 1).toBuilder().complexity(7).build())) {
    var microphone = new short[960]; // 20 ms at 48 kHz
    var speaker = new short[960];
    var packet = new byte[OpusEncoder.maxPacketBytes()];
    var length = encoder.encode(microphone, 0, 960, packet, 0, packet.length);
    decoder.decode(packet, 0, length, speaker, 0, 960);
    // The next packet was lost: fill its 20 ms
    decoder.conceal(speaker, 0, 960);
    // The network got slower: lower the bitrate mid-call
    encoder.setBitrate(24_000);
}
```

### Remove echo and noise from a call

Every 10 ms, pass what the speaker plays to `processRender`, then what the microphone records to `processCapture`; see [Voice processing](#voice-processing).

```java
var processor = new VoiceProcessor(VoiceProcessorOptions.forVoip().toBuilder()
        .gainControl(GainControl.ADAPTIVE_DIGITAL).build());
var speaker = new float[processor.frameSamples()];
var microphone = new float[processor.frameSamples()];
var cleaned = new float[processor.frameSamples()];
processor.processRender(speaker, 0);
processor.processCapture(microphone, 0, cleaned, 0);
```

<details>
<summary><b>Defaults when you encode through AudioWriter or Transcoder</b></summary>

| Codec | Default with no bitrate | `realtime(true)` |
|---|---|---|
| Opus | 32 kbit/s per channel | VOIP application instead of AUDIO |
| MP3 | 64 kbit/s per channel * | No bit reservoir |
| MP2 | Layer II, 192/96 kbit/s (stereo/mono), 128/64 below 32 kHz | No effect |
| Vorbis | Quality 3 | No effect |
| FLAC | Level 5, 16 bit | Level 0 |
| ALAC | 16 bit | Fast mode |
| G.726 | 4-bit codes | No effect |

\* 32 kbit/s per channel at 16-24 kHz, 16 below.

- MP3 is always CBR here; use `Mp3Encoder` for VBR or ABR.
- MP2 stereo at 128 kbit/s or less uses joint stereo.
- MP3 and MP2 snap to the nearest legal sample rate and bitrate.
- ALAC rounds the requested bit depth up to 16, 20, 24 or 32.

</details>

## Formats

Containers are not part of this module: core demuxes and muxes them (see [core formats](../core/README.md#formats)).

### Lossy

| Format | Decode | Encode | Notes |
|---|:-:|:-:|---|
| Opus | ✅ | ✅ | 1-255 channels; loss recovery (FEC, deep PLC, DRED), DTX |
| Vorbis | ✅ | ✅ | Encoder byte-identical to libvorbis 1.3.7 |
| MP3 (MPEG-1, 2, 2.5) | ✅ | ✅ | CBR, VBR and ABR encode |
| MP2 and MP1 | ✅ | ✅ | Layer I and II; Video CD preset |
| Speex | ✅ | ❌ | Narrowband to ultra-wideband |
| Musepack | ✅ | ❌ | SV7 and SV8 stream versions |
| ATRAC9 | ✅ | ❌ | Mono to 7.1, 11.025-192 kHz |
| MACE 3:1 and 6:1 | ✅ | ❌ | AIFF-C and QuickTime |

✅ supported · ❌ not supported · ➖ not applicable

Usual files: `.opus`, `.ogg`, `.mp3`, `.mp2`, `.spx`, `.mpc`, `.at9`, `.aifc`, plus Matroska, WebM and MP4 tracks ([core formats](../core/README.md#formats)).

### Lossless

| Format | Decode | Encode | Notes |
|---|:-:|:-:|---|
| FLAC | ✅ | ✅ | 1-8 channels, 4-32 bit, levels 0-8 |
| ALAC | ✅ | ✅ | Encoder byte-identical to Apple's reference |
| WavPack | ✅ | ❌ | Lossless, hybrid, correction files, DSD |
| Monkey's Audio | ✅ | ❌ | Fast to Insane, up to 32 channels |
| DST | ✅ | ❌ | DSD from DSDIFF and MP4 |

Usual files: `.flac`, `.m4a` and `.caf` (ALAC), `.wv`, `.ape`, `.dff` (DST) ([core formats](../core/README.md#formats)).

### ADPCM and telephony

| Format | Decode | Encode | Notes |
|---|:-:|:-:|---|
| IMA ADPCM | ✅ | ✅ | WAV blocks and QuickTime `ima4` |
| Microsoft ADPCM | ✅ | ✅ | WAV; custom coefficient tables |
| G.722 | ✅ | ✅ | Encode 64 kbit/s, 16 kHz mono only |
| G.726 | ✅ | ✅ | 16-40 kbit/s; encode mono only |
| GSM 06.10 | ✅ | ✅ | 33-byte frames and Microsoft 65-byte blocks; encode mono only |

Usual files: `.wav`, `.aif` and `.aifc`, `.mov`, `.caf` ([core formats](../core/README.md#formats)).

<details>
<summary><b>Codec constants and classes</b></summary>

| Format | `Codec` | Classes | Package (`com.github.auties00.smalt.audio.*`) |
|---|---|---|---|
| Opus | `OPUS` | `OpusDecoder`, `OpusEncoder` | `opus` |
| Vorbis | `VORBIS` | `VorbisDecoder`, `VorbisEncoder` | `vorbis` |
| MP3, MP2 and MP1 | `MP3`, `MP2` (Layer I and II) | `Mp3Decoder`, `Mp3Encoder`, `Mp2Encoder` | `mp3` |
| Speex | `SPEEX` | `SpeexDecoder` | `speex` |
| Musepack | `MUSEPACK7`, `MUSEPACK8` | `MusepackDecoder` | `musepack` |
| ATRAC9 | `ATRAC9` | `Atrac9Decoder` | `atrac9` |
| MACE | `MACE3`, `MACE6` | `MaceDecoder` | `mace` |
| FLAC | `FLAC` | `FlacDecoder`, `FlacEncoder` | `flac` |
| ALAC | `ALAC` | `AlacDecoder`, `AlacEncoder` | `alac` |
| WavPack | `WAVPACK` | `WavPackDecoder` | `wavpack` |
| Monkey's Audio | `APE` | `ApeDecoder` | `ape` |
| DST | `DST` | `DstDecoder` | `dst` |
| IMA ADPCM | `ADPCM_IMA_WAV`, `ADPCM_IMA_QT` | `AdpcmDecoder`, `AdpcmEncoder` | `adpcm` |
| Microsoft ADPCM | `ADPCM_MS` | `AdpcmDecoder`, `AdpcmEncoder` | `adpcm` |
| G.722 | `ADPCM_G722` | `G722Decoder`, `G722Encoder` | `g722` |
| G.726 | `ADPCM_G726` | `G726Decoder`, `G726Encoder` | `g726` |
| GSM 06.10 | `GSM`, `GSM_MS` | `GsmDecoder`, `GsmEncoder` | `gsm` |
| Voice processing | ➖ | `VoiceProcessor` | `voice` |

</details>

<details>
<summary><b>Opus</b></summary>

- **Decode:** every packet form, SILK, hybrid and CELT modes, frames of 2.5 to 60 ms.
- **Decode output:** native 8, 12, 16, 24 or 48 kHz; bit-exact with libopus 1.5.2 (float build, no SIMD).
- **Channel mappings:** families 0, 1 (up to 7.1), 2 and 3 (ambisonics) and 255; downmix to mono or stereo.
- **Loss handling:** classic concealment and in-band FEC (`recover`) at any complexity.
- **Neural decoding:** complexity 5+ adds deep PLC and DRED recovery; 6 adds LACE, 7+ NoLACE.
- **Encode:** 1-255 channels; surround up to 7.1 with LFE, ambisonics (any order, or orders 1-5 with projection), or discrete channels.
- **Encode controls:** total bitrate from 500 bit/s to 300 kbit/s per channel; VBR, constrained VBR or CBR; frames of 2.5 to 120 ms.
- **Encode extras:** in-band FEC, DTX, and DRED up to 1040 ms; live setters change bitrate and more mid-stream.
- **Encode output:** byte-identical to libopus 1.5.2 (float build, no SIMD), with one surround exception.
- **Presets:** `OpusEncoderOptions.defaults()`, `forSpeech(bitrate)`, `forRealtime()`, `forVoip(rate, channels)`.
- **Packets:** `OpusPackets` reads a packet's bandwidth, channels and frames, and merges or splits packets.

</details>

<details>
<summary><b>MP3 and MP2</b></summary>

- **Decode:** MPEG-1, 2 and 2.5, Layers I, II and III; CRC-16 checks with concealment.
- **Multichannel decode:** ISO 13818-3 extension up to 3/2 + LFE.
- **Programs:** pick the second stereo or a multilingual program with `Mp3DecoderOptions.builder().program(Mp2Program.SECOND_STEREO)`.
- **MP3 encode:** 9 rates from 8 to 48 kHz; mono, stereo, joint (M/S) or dual channel.
- **MP3 bitrate:** CBR, VBR quality 0-9, or ABR; 32-320 kbit/s at 32-48 kHz, 8-160 kbit/s below.
- **MP3 file tag:** core's MP3 muxer writes a Xing/LAME tag with a seek table.
- **MP3 extras:** bit reservoir, CRC, lowpass from 1 to 24 kHz; `forRealtime()` turns the reservoir off.
- **MP2 encode:** Layer I or II; 6 rates from 16 to 48 kHz; intensity joint stereo; every legal bitrate.
- **MP2 presets:** `defaults()` (192 kbit/s at 48 kHz) and `forVideoCd()` (224 kbit/s at 44.1 kHz).

</details>

<details>
<summary><b>Vorbis, FLAC and ALAC</b></summary>

- **Vorbis decode:** floor 0 and floor 1, 1-255 channels, concealment.
- **Vorbis encode:** quality -1 to 10, a nominal bitrate that picks the quality, or a managed average, minimum or maximum bitrate.
- **FLAC decode:** all of RFC 9639; MD5 check when `verifyIntegrity` is on (`md5Verified()`).
- **FLAC encode:** levels 0-8 with libFLAC's choices; 3 stereo modes, LPC up to order 32, apodizations.
- **FLAC file layout:** seek table and padding; write the final STREAMINFO with `updateCodecConfig` after `flush`.
- **ALAC decode:** 4-32 bit, 1-8 channels, from MP4 `alac` and Matroska `A_ALAC`.
- **ALAC encode:** 16, 20, 24 or 32 bit, 1-8 channels, frames of 16 to 65536 samples (default 4096), fast mode.
- **Exact output:** FLAC and ALAC give integers or doubles with `decodeIntegers` and `decodeDoubles`.

</details>

<details>
<summary><b>WavPack, Monkey's Audio and DST</b></summary>

- **WavPack:** v4 and v5 streams; integer 1-32 bit, 32-bit float and DSD; bit-exact with WavPack 5.8.1.
- **WavPack correction:** `.wvc` files through core's WavPack demuxer, or Matroska block additions.
- **Monkey's Audio:** every level from Fast to Insane; 8-32 bit integer and 32-bit float; bit-exact with SDK 13.45.
- **DST:** `decodeDsd` returns raw DSD, bit-exact with the MPEG-4 reference.
- **DST to PCM:** set `preferredSampleRate`; the default is the DSD rate divided by 16.

</details>

<details>
<summary><b>Speex, Musepack, ATRAC9 and MACE</b></summary>

- **Speex:** narrowband (8 kHz), wideband (16 kHz) and ultra-wideband (32 kHz), every sub-mode, VBR, DTX, stereo.
- **Speex sources:** Ogg with its header, or headerless FLV; bit-exact with fixed-point libspeex 1.2.1.
- **Musepack:** SV7 (stereo) and SV8 (mono or stereo); bit-exact with libmpcdec r475 (float build).
- **ATRAC9:** mono, dual mono, stereo, 4.0, 5.1 and 7.1; `.at9` WAV and Matroska; bit-exact with LibAtrac9.
- **MACE:** any channel count; bit-exact with QuickTime 7.7.9 for mono and stereo.

</details>

<details>
<summary><b>ADPCM and telephony</b></summary>

- **IMA ADPCM:** WAV decode bit-exact with `imaadp32.acm`; WAV encode byte-identical to it.
- **IMA ADPCM encode:** any rate; WAV blocks, or `ima4` frames of 34 bytes per channel.
- **Microsoft ADPCM:** decode bit-exact with `msadp32.acm`; the encoder searches the best predictor per block.
- **G.722:** decode 64, 56 or 48 kbit/s to 16 kHz; sub-band codecs bit-exact with G.722 Appendix II.
- **G.726:** 2-5 bit codes (16, 24, 32 or 40 kbit/s at 8 kHz); bit-exact with G.726 Appendix II.
- **GSM 06.10:** `GSM` for AIFF-C, QuickTime and RTP; `GSM_MS` for WAV; bit-exact with EN 300 961.

</details>

<details>
<summary><b>Terms</b></summary>

| Term | Meaning |
|---|---|
| FEC | Redundant data to rebuild a lost packet |
| DTX | Sends almost nothing during silence |
| PLC, deep PLC | Fills a lost packet with a guess; deep PLC guesses with a neural network |
| DRED | Neural redundancy for long losses |
| LACE, NoLACE | Neural enhancers for SILK speech |
| CBR, VBR, ABR | Constant, variable or average bitrate |
| M/S | Mid/side stereo: sum and difference channels |
| LFE | Subwoofer channel |
| LPC | Linear prediction, the core of FLAC's compression |
| STREAMINFO | FLAC header with the stream's length and MD5 |
| DSD | 1-bit SACD audio |
| SV7, SV8 | Musepack stream versions |

</details>

## Known limits

- MP3 and MP2 encode mono or stereo only.
- The Opus decoder runs at complexity 0 by default: deep PLC, DRED, LACE and NoLACE stay off until you raise it.
- WavPack, Monkey's Audio, Musepack and DST decoders have no packet-loss concealment.
- Codec instances are not thread-safe.

<details>
<summary><b>Opus</b></summary>

- The encoder takes 8, 12, 16, 24 or 48 kHz input only.
- DRED encoding needs a mono or stereo single-stream encoder and a multiple of 10 ms, up to 1040 ms.
- DRED networks load when the encoder is created and cost time on every frame.
- The decoder's neural networks (about 3 MB in the jar) load on first use.
- Only DRED version 10 of libopus 1.5.2 is read; other versions parse as no DRED.
- Neural output matches libopus only when libopus runs its generic C networks (no SSE, AVX or NEON).
- Surround (3+ channels) is byte-identical to libopus only at an explicit bitrate with no bandwidth setting.
- Per-packet `encode` fails while buffered input waits for a full packet.
- Float output can exceed [-1, 1]; the 16-bit methods round off peaks smoothly (soft clipping).
- The stereo downmix matches opusfile 0.12 for every channel count but four.

</details>

<details>
<summary><b>MP3 and MP2</b></summary>

- The MP3 encoder writes no intensity stereo and no mixed blocks.
- The MP2 encoder writes no ISO 13818-3 multichannel extension.
- The decoder outputs one program at a time; an extension with Layer I-syntax multilingual channels (`multi_lingual_layer`) decodes as plain stereo.
- Multichannel Layer I output lags 2 frames and Layer III 1 frame; `flush` drains them.
- The decoder keeps encoder delay and padding; `AudioReader` trims them from the container data.

</details>

<details>
<summary><b>Vorbis, FLAC and ALAC</b></summary>

- Vorbis bitrate targets work only from 8 to 50 kHz; other rates take a quality.
- The Vorbis encoder differs from libvorbis on one edge case: it writes a 1-byte packet where libvorbis writes an empty one.
- The FLAC block size must be at least the maximum LPC order.
- FLAC and ALAC conceal losses smoothly in float output only; integer and double output conceal with silence.
- The ALAC encoder writes 16, 20, 24 or 32 bit only and clips float input.
- ALAC has one fixed speaker arrangement per channel count; other speakers fill its free slots in input order.
- The ALAC decoder rejects a compressed 32-bit channel pair with no shifted bytes (`MediaException.Unsupported`).

</details>

<details>
<summary><b>WavPack, Monkey's Audio and DST</b></summary>

- WavPack float streams have no integer output: `decodeIntegers` throws `MediaException.Unsupported`.
- WavPack DSD decodes to PCM decimated 8 times, at 24 bit.
- Monkey's Audio 3.891-3.899 and 3.921-3.929 files throw `MediaException.Unsupported`.
- Monkey's Audio 32-bit integers and more than 2 channels need files from version 3.930 on.
- Monkey's Audio float streams have no integer output (`UnsupportedOperationException`).

</details>

<details>
<summary><b>Speex, Musepack, ATRAC9 and MACE</b></summary>

- The Speex decoder always runs its perceptual enhancer.
- Musepack SV7 started mid-stream converges after a few frames.

</details>

<details>
<summary><b>ADPCM and telephony</b></summary>

- The ADPCM encoder takes 1-8 channels; WAV blocks hold at most 65535 bytes and 65535 samples per channel.

</details>

## Voice processing

`VoiceProcessor` cleans 10 ms frames of `float[]` or `short[]` samples.
Default (`forVoip()`): 16 kHz mono, standard echo canceller, high noise suppression, no gain control.

| Stage | Option | Values |
|---|---|---|
| Echo cancellation | `echoCancellation` | `OFF`, `STANDARD` (AEC3), `MOBILE` (AECM) |
| Noise suppression | `noiseSuppression` | `OFF`, `LOW`, `MODERATE`, `HIGH`, `VERY_HIGH` |
| Gain control | `gainControl` | `OFF`, `ADAPTIVE_DIGITAL`, `FIXED_DIGITAL`, `ADAPTIVE_ANALOG`, `MANAGED_ANALOG` |
| Level control | `levelControl` | `LevelControlOptions`, or `noLevelControl()` |
| High-pass filter | none | 100 Hz; on with echo or noise stage |

- `STANDARD` (AEC3) suits every device; `MOBILE` (AECM) costs less but suppresses less echo.
- The standard echo canceller finds the delay itself, up to about 600 ms.
- With analog gain control, report the microphone volume with `setAnalogLevel` and apply `recommendedAnalogLevel()`.

<details>
<summary><b>Voice processing limits</b></summary>

- Rates are 8, 16, 32 or 48 kHz, with 1-8 channels.
- Not bit-exact with WebRTC: values under 1e-10 of full scale flush to zero, and the Vector API delay estimator sums in another order.
- Render and capture calls must alternate every 10 ms.
- The standard echo canceller queues up to 1 s of render frames and drops the rest.
- The standard echo canceller adds 4 ms of delay, noise suppression 6 ms.
- The mobile echo canceller needs `setStreamDelayMs`, clamped to 0-500 ms.

</details>

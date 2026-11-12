<div align="center">

# smalt-audio

**Opus, the Xiph and lossless codecs, MP3, telephony codecs and voice processing**

![Artifact](https://img.shields.io/badge/artifact-smalt--audio-2f6fe6?style=flat-square&labelColor=0b2a6f)
![Module](https://img.shields.io/badge/module-com.github.auties00.smalt.audio-2f6fe6?style=flat-square&labelColor=0b2a6f)

[Smalt](../README.md) | [core](../core/README.md) | [image](../image/README.md) | **audio** | [video](../video/README.md) | [mpeg](../mpeg/README.md) | [capture](../capture/README.md) | [raw](../raw/README.md) | [desktop](../desktop/README.md) | [aot](../aot/README.md) | [bom](../bom/README.md)

</div>

`smalt-audio` holds the audio codecs that are free of patent pools, and the voice processor. It depends on
[`smalt-core`](../core/README.md) only.

**Contents:** [What it covers](#what-it-covers) | [Install](#install) | [Guide](#guide) |
[Format coverage](#format-coverage) | [Known limits](#known-limits)

## What it covers

- **Decode and encode:** Opus (SILK, CELT, hybrid), MP3 and MP2, Vorbis, FLAC, ALAC, IMA and Microsoft ADPCM,
  G.722, G.726, GSM 06.10.
- **Decode only:** Speex, WavPack, Monkey's Audio, Musepack, DST, ATRAC9, MACE.
- **Voice processing:** echo cancellation, noise suppression and gain control for calls.

> [!TIP]
> AAC, AC-3, E-AC-3, DTS and AMR are in [`smalt-mpeg`](../mpeg/README.md), since patent pools or Dolby license
> them. PCM and DSD need no codec artifact: they are in [`smalt-core`](../core/README.md#codecs-in-core), with
> resampling, filters and loudness.

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-audio</artifactId>
</dependency>
```

The version comes from the [BOM](../bom/README.md); Gradle coordinates and JVM flags are in the
[root README](../README.md#install).

## Guide

Reading and writing audio files, whatever their codec, is in the
[core guide](../core/README.md#audio); these examples use the codecs of this artifact directly.

### Encode FLAC

```java
// flac -8 song.wav (24-bit input): the transcode keeps the source's sample size
Transcoder.transcode(Path.of("song.wav"), Path.of("song.flac"),
        TranscodeOptions.of(OutputContainer.FLAC).toBuilder().audio(AudioTranscodeOptions.encode(Codec.FLAC)).build());

// Or drive the encoder: integer samples, libFLAC's presets, a native file with MD5 and seek table
var options = FlacEncoderOptions.ofCompressionLevel(8).toBuilder()
        .format(96_000, ChannelLayout.STEREO).bitsPerSample(24).build();
var encoder = new FlacEncoder(options);
try (var muxer = new RawMuxer(MediaSink.of(Path.of("song.flac")))) {
    muxer.addTrack(AudioTrack.builder().codec(Codec.FLAC).timeBase(Rational.timeBaseOf(96_000))
            .codecConfig(encoder.codecConfig().orElseThrow()).sampleRate(96_000).build());
    encoder.encode(samples, 0, samples[0].length, muxer::writePacket);     // int[channel][sample]
    encoder.flush(muxer::writePacket);
    muxer.updateCodecConfig(0, encoder.codecConfig().orElseThrow());      // final STREAMINFO: length, MD5
    muxer.finish();
}
```

The output matches `flac` 1.4.3 in size at every level (within 0.01% on music and speech). Apple
Lossless (ALAC) tracks in MP4 and Matroska decode through `AudioReader` like any other codec, and `AlacEncoder`
writes them the same way: `encode(int[][], ...)` or floats, `flush`, then `updateCodecConfig` with the final
`ALACSpecificConfig`; its packets are byte-identical to those of Apple's reference encoder.

### Code Opus in real time

```java
var encoder = new OpusEncoder(OpusEncoderOptions.forVoip(48_000, 1).toBuilder()
        .inbandFec(true).packetLossPercent(10).build());
var decoder = new OpusDecoder(OpusDecoderOptions.of(48_000, 1));
var packet = new byte[OpusEncoder.maxPacketBytes()];
var length = encoder.encode(pcm, 0, 960, packet, 0, packet.length);     // one 20 ms frame of 16-bit samples
encoder.setBitrate(24_000);                                             // retarget while the stream runs
var samples = decoder.decode(packet, 0, length, out, 0, 960);           // interleaved 16-bit output
decoder.conceal(out, 0, 960);                                           // a packet that never arrived
```

A voice call that may lose bursts of packets adds Deep REDundancy to every packet and decodes at complexity 5 or
more, which conceals with the deep PLC and rebuilds a gap from the redundancy of the first packet after it:

```java
var encoder = new OpusEncoder(OpusEncoderOptions.forVoip(48_000, 1).toBuilder()
        .packetLossPercent(20).dredDurationMillis(1000).build());
var decoder = new OpusDecoder(OpusDecoderOptions.of(48_000, 1).toBuilder().complexity(7).build());
var dred = new OpusDred();
// packets n + 1 .. n + lost were lost, packet n + lost + 1 arrived
var available = dred.parse(packet, 0, length, lost * 960, 48_000);
for (var k = lost; k >= 1; k--) {
    if (k * 960 <= available) {
        decoder.recover(dred, k * 960, out, 0, 960);                    // rebuilt from the redundancy
    } else {
        decoder.conceal(out, 0, 960);                                   // the deep PLC
    }
}
decoder.decode(packet, 0, length, out, 0, 960);
```

Real time is a preset, not a separate class: the same `OpusEncoder` also buffers `AudioBuffer`s into
timestamped packets for a file (`OpusEncoderOptions.defaults()`, `forSpeech(bitrate)`), surround
included (`builder().format(48_000, ChannelLayout.SURROUND_5_1)` codes a 5.1 multistream with its LFE
stream; `channelMapping(OpusChannelMapping.DISCRETE)`, `AMBISONIC` or `AMBISONIC_PROJECTION` picks another
mapping family), and the same `OpusDecoder` decodes an Opus track, mixing it down to stereo or mono when its
options ask for `channels(2)` or `channels(1)`. `OpusPackets` inspects, merges and splits packets without
decoding them.

## Format coverage

Open a codec to see what is decoded, what is encoded, the package that implements it (relative to
`com.github.auties00.smalt`), where its code comes from and what to know about patents. In *Source*, "spec"
means the code was written from the specification alone; a named project means code was ported or derived from it
(see [Acknowledgments](../README.md#acknowledgments)).

### Codecs

<details>
<summary><b>Opus (SILK, CELT, hybrid)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes, every channel mapping family: mono and stereo, surround up to 7.1, ambisonics (family 2, and family 3 demixed through the header's matrix), reported as the ambisonic layout of RFC 8486 (`ChannelLayout.ofAmbisonics`, ACN order, SN3D, with the non-diegetic stereo pair), discrete and reserved multistreams; per-packet decoding of any of them with loss concealment and in-band FEC for real time; downmix of any stream to mono or stereo (RFC 7845 and RFC 8486 matrices); the neural extensions of libopus 1.5 from decoder complexity 5: the deep PLC (LPCNet features, a recurrent feature predictor and the FARGAN vocoder), Deep REDundancy decoding (`OpusDred`, extension 126 version 10) and the LACE (complexity 6) and NoLACE (7 and up) enhancers of wideband SILK speech
- **Encode:** yes, 1 to 255 channels: mono and stereo, surround up to 7.1 (Vorbis channel order, LFE stream, surround masking), ambisonics coded channel by channel (family 2, chosen by default for an ambisonic input layout) or through the projection matrices of orders 1 to 5 (family 3), and discrete multistreams; buffered for files or one packet per call with live controls for real time; Deep REDundancy of up to 1.04 s per packet for mono and stereo (`OpusEncoderOptions.dredDurationMillis`)
- **Artifact:** `smalt-audio`
- **Package:** `audio.opus`
- **Source:** libopus 1.5.2 (float build, and the generic C code of its `dnn/` networks with their model weights); KISS FFT as modified in libopus; opusfile 0.12 (stereo downmix matrices)
- **Patents:** royalty-free Opus licenses (in libopus `COPYING`)

</details>

<details>
<summary><b>MP3, MP2</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes: MPEG-1, MPEG-2 and MPEG-2.5 Layers I, II and III; CRC-16 checked, failing or corrupt-flagged frames concealed; the ISO/IEC 13818-3 multichannel extension of Layers I, II and III (`3/0` to `3/2` with LFE, extension frames, phantom center, every dematrixing procedure; dynamic crosstalk and prediction in Layers I and II; segment lists, composite coding, matrix attenuation, prediction and the extension's own bit reservoir in Layer III; the second stereo program and the multilingual channels at the full or half rate through `Mp3DecoderOptions.program()`)
- **Encode:** MP3: MPEG-1, MPEG-2 and MPEG-2.5 Layer III (8 to 48 kHz, mono and stereo), constant, variable (quality 0-9) and average bitrate, joint (mid/side) stereo, bit reservoir, CRC; no intensity stereo. MP2: MPEG-1 and MPEG-2 Layer II and Layer I (16 to 48 kHz, mono, stereo, joint (intensity) stereo and dual channel), every bitrate of the layer, CRC; psychoacoustic model 1
- **Artifact:** `smalt-audio`
- **Package:** `audio.mp3`
- **Source:** decoder: minimp3, the Layers I and II multichannel extension and CRC from the ISO/IEC 13818-5 reference software (dist10), the Layer III extension from the specification (ISO/IEC 13818-3); MP3 encoder: spec (ISO/IEC 11172-3, 13818-3) and psychoacoustic literature, analysis window from the ISO dist10 tables; MP2 encoder: the ISO/IEC 13818-5 reference software (dist10)
- **Patents:** MP3 patents expired (2017)

</details>

<details>
<summary><b>Vorbis</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes
- **Encode:** quality -1 to 10 (or a nominal bitrate mapped to a quality), 1 to 255 channels, 1 Hz to 200 kHz; bitrate management: an average bitrate and hard minimum and maximum bitrates, alone or with a quality or nominal bitrate, held through a bit reservoir (size, bias and average damping settable); packets byte-identical to libvorbis 1.3.7 in every mode, except that a packet libvorbis truncates to nothing under a very low maximum keeps one byte
- **Artifact:** `smalt-audio`
- **Package:** `audio.vorbis`
- **Source:** spec; libvorbis, stb_vorbis (decoder); libvorbis 1.3.7 with aoTuV tuning (encoder)
- **Patents:** none

</details>

<details>
<summary><b>FLAC</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes: 4 to 32-bit samples, also as their integers bit for bit (`decodeIntegers`, `SampleFormat.INTEGER`) or exact doubles; the `STREAMINFO` MD5 signature checked on request (`AudioDecoderOptions.verifyIntegrity`)
- **Encode:** yes: libFLAC's compression levels 0-8, 4 to 32-bit integer (planar, interleaved or `IntegerAudioBuffer` blocks of any sample size) or float input, every stereo mode, MD5 and seek table
- **Artifact:** `smalt-audio`
- **Package:** `audio.flac`
- **Source:** libFLAC
- **Patents:** none

</details>

<details>
<summary><b>ALAC (Apple Lossless)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes: 16/20/24/32-bit, 1-8 channels, from MP4 and Matroska, also as their integers bit for bit (32-bit streams of Apple's reference encoder included) or exact doubles
- **Encode:** yes: 16, 20, 24 and 32-bit integer or float input, 1-8 channels, adaptive stereo matrixing and predictor search (or Apple's fast mode), escaped and partial frames, any frame length from 16 to 65536; packets byte-identical to Apple's reference encoder
- **Artifact:** `smalt-audio`
- **Package:** `audio.alac`
- **Source:** Apple's ALAC (Apache-2.0)
- **Patents:** none (royalty-free)

</details>

<details>
<summary><b>Speex</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** yes: narrowband, wideband and ultra-wideband, every sub-mode, variable bit rate, discontinuous transmission, intensity stereo and packet loss concealment (`conceal`), the perceptual enhancer on; bit-exact with the fixed-point libspeex 1.2.1; from Ogg, and headerless (FLV) tracks
- **Encode:** no
- **Artifact:** `smalt-audio`
- **Package:** `audio.speex`
- **Source:** libspeex
- **Patents:** none (royalty-free)

</details>

<details>
<summary><b>WavPack</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** yes: WavPack 4 and 5 (stream versions 0x402 to 0x410) lossless, hybrid lossy, and hybrid lossless with the correction file (`.wvc`, or the Matroska `BlockAdditional` 1), 1 to 32-bit integers (as their integers bit for bit or exact doubles), 32-bit floats, DSD (as the reference's 8x decimated 24-bit PCM), any channel count and mask; block checksums checked, the MD5 signature on request (`AudioDecoderOptions.verifyIntegrity`); bit-exact with WavPack 5.8.1; from `.wv` and Matroska (`A_WAVPACK4`)
- **Encode:** no
- **Artifact:** `smalt-audio`
- **Package:** `audio.wavpack`
- **Source:** WavPack
- **Patents:** none

</details>

<details>
<summary><b>Monkey's Audio</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** yes: file versions 3.80 to 3.99 and 3.990 onward (every version but 3.891 to 3.899 and 3.921 to 3.929, which Monkey's Audio itself no longer reads), every compression level (fast to insane), 8, 16, 24 and 32-bit integers (as their integers bit for bit or exact doubles) and 32-bit floats, 1 to 32 channels; every frame checked against its CRC; bit-exact with Monkey's Audio 13.45; from `.ape`
- **Encode:** no
- **Artifact:** `smalt-audio`
- **Package:** `audio.ape`
- **Source:** Monkey's Audio SDK 13.45
- **Patents:** none

</details>

<details>
<summary><b>Musepack</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** yes: stream versions 7 (`MP+`) and 8 (`MPCK`), every profile, mono and stereo, mid/side stereo, noise substitution; the decoder's 481-sample delay and SV8 leading silence removed; bit-exact (as floats) with libmpcdec r475's floating-point build; from `.mpc`
- **Encode:** no
- **Artifact:** `smalt-audio`
- **Package:** `audio.musepack`
- **Source:** libmpcdec r475
- **Patents:** none

</details>

<details>
<summary><b>DST (Direct Stream Transfer, ISO/IEC 14496-3 subpart 10)</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** yes: every DSD rate and channel count, to its DSD bit-exact with the MPEG-4 Audio reference decoder (`DstDecoder.decodeDsd`) and to PCM as the DSD decoder converts it; DSDIFF frame CRCs checked on request (`AudioDecoderOptions.verifyIntegrity`); from DSDIFF (`DST `) and MP4 (`mp4a`, audio object type 35); checked against the ISO/IEC 14496-26 DST conformance streams
- **Encode:** no
- **Artifact:** `smalt-audio`
- **Package:** `audio.dst`
- **Source:** MPEG-4 Audio reference software (DST module)
- **Patents:** none

</details>

<details>
<summary><b>ATRAC9</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** yes: every sampling rate (11.025 to 192 kHz), the mono, dual mono, stereo, 4.0, 5.1 and 7.1 configurations, intensity stereo and band extension; bit-exact with LibAtrac9 (Alex Barney's decoder, reverse engineered from Sony's) at commit `efca2e3`; from `.at9` (WAV) files and Matroska `A_MS/ACM`
- **Encode:** no
- **Artifact:** `smalt-audio`
- **Package:** `audio.atrac9`
- **Source:** LibAtrac9 (MIT)
- **Patents:** Sony patents; not covered by the MIT license

</details>

<details>
<summary><b>IMA ADPCM (WAVE <code>0x0011</code> blocks, Apple <code>ima4</code>)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes: 4-bit, any channel count, from WAV, AVI, Matroska `A_MS/ACM` and QuickTime (`ms\0\x11`), and `ima4` from AIFF-C and QuickTime (`ima4`, classic and per-packet tables); bit-exact with Microsoft's `imaadp32.acm` (the recommendation's shift-and-add quantizer) and with ffmpeg's `adpcm_ima_qt`; also as 16-bit integers or doubles
- **Encode:** yes: both layouts, 1-8 channels, any rate, WAVE blocks of any valid size and at most 65535 samples (256 bytes per channel per 11.025 kHz by default), each sample coded to the nearest code; the WAVE blocks byte-identical to Microsoft's `imaadp32.acm`
- **Artifact:** `smalt-audio`
- **Package:** `audio.adpcm`
- **Source:** spec (IMA Digital Audio Focus recommendation, Microsoft RIFF, Apple TN1081)
- **Patents:** none

</details>

<details>
<summary><b>Microsoft ADPCM (WAVE <code>0x0002</code>)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes: 4-bit, any channel count and coefficient table, from WAV, AVI, Matroska `A_MS/ACM` and QuickTime (`ms\0\x02`); bit-exact with Microsoft's `msadp32.acm`, including its 32-bit arithmetic on streams whose deltas overflow 16 bits; also as 16-bit integers or doubles
- **Encode:** yes: 1-8 channels, any rate and valid block size of at most 65535 samples, the coefficient pair and initial delta of each channel of each block chosen for the smallest squared error (about 10 dB more accurate than `msadp32.acm` on speech)
- **Artifact:** `smalt-audio`
- **Package:** `audio.adpcm`
- **Source:** spec (Microsoft RIFF)
- **Patents:** none

</details>

<details>
<summary><b>G.722</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes: 64, 56 and 48 kbit/s modes, from WAV, AVI and Matroska `A_MS/ACM`; sub-band decoders bit-exact with the ITU-T G.722 Appendix II test sequences
- **Encode:** yes: 64 kbit/s, 16 kHz mono; sub-band encoders bit-exact with the Appendix II sequences
- **Artifact:** `smalt-audio`
- **Package:** `audio.g722`
- **Source:** spec (ITU-T G.722)
- **Patents:** none

</details>

<details>
<summary><b>G.726</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes: 16, 24, 32 and 40 kbit/s (2 to 5-bit codes, MSB-first packing), from WAV, AVI and Matroska `A_MS/ACM`, the uniform PCM output of Annex A; bit-exact with the ITU-T G.726 Appendix II test sequences
- **Encode:** yes: every code size, any rate, bit-exact with the Appendix II sequences
- **Artifact:** `smalt-audio`
- **Package:** `audio.g726`
- **Source:** spec (ITU-T G.726 and its Annex A)
- **Patents:** none

</details>

<details>
<summary><b>GSM 06.10 (full rate)</b> &nbsp;<sub>decode and encode</sub></summary>

- **Decode:** yes: RFC 3551 33-byte frames (AIFF-C `GSM `, QuickTime `agsm`) and Microsoft's 65-byte blocks (WAV and AVI `0x0031`, Matroska `A_MS/ACM`); bit-exact with the ETSI EN 300 961 test sequences, codec homing included, and with libgsm
- **Encode:** yes: both layouts, bit-exact with the EN 300 961 sequences and byte-identical to libgsm
- **Artifact:** `smalt-audio`
- **Package:** `audio.gsm`
- **Source:** libgsm 1.0.22
- **Patents:** none

</details>

<details>
<summary><b>MACE 3:1, 6:1</b> &nbsp;<sub>decode only</sub></summary>

- **Decode:** yes: AIFF-C and QuickTime `MAC3`/`MAC6` tracks, 16-bit samples repeating the 8-bit ones in both bytes; mono and stereo equal QuickTime 7.7.9 for Windows sample for sample (QuickTime takes no more channels; more are decoded in the same layout, each independently)
- **Encode:** no
- **Artifact:** `smalt-audio`
- **Package:** `audio.mace`
- **Source:** black box (QuickTime 7.7.9 for Windows, every table measured), US patent 5,083,310
- **Patents:** none

</details>

### Voice processing

- **Voice processing at 8, 16, 32 and 48 kHz with up to 8 capture and render channels (band splitting and three-band filter bank, multichannel AEC3): echo cancellation (AEC3 and the mobile echo canceller), noise suppression, high-pass filter, the legacy gain controller in its digital and analog modes, the analog gain controller with clipping prediction, and the AGC2 level controller (adaptive digital gain with the RNN voice activity detector, saturation protector, limiter, input volume controller)**
  - *Artifact:* `smalt-audio`
  - *Package:* `audio.voice`
  - *Source:* WebRTC audio processing module, bit-exact with its scalar build; PFFFT; RNNoise weights; Ooura FFT; musl and fdlibm math (royalty-free WebRTC grant in `PATENTS`)

## Known limits

> [!WARNING]
> These are the cases where a codec of this artifact leaves a feature out or differs from another
> implementation. Open the entry of the codec you work with.

### Opus

<details>
<summary><b>Opus neural extensions</b></summary>

The deep PLC, DRED and OSCE output is bit-identical to libopus 1.5.2 whose networks run
their generic C code; a libopus build with its SSE, AVX or NEON kernels quantizes the int8 layers differently and
produces slightly different samples. Libopus built with these extensions also conceals differently below complexity
5 (no energy glue after a lost 16 kHz SILK frame, noise concealment of CELT only after 80 ms); Smalt keeps the
behavior of the build without them below complexity 5, so a decoder of complexity 0 to 4 matches the usual libopus.
DRED is the experimental version 10 of libopus 1.5 (extension id 126); the encoder sends it only for mono or stereo
single streams, only for frames its tonality analysis judges active (so never below 16 kHz or under complexity
7), and the decoder rebuilds from it only at complexity 5 or more. `OpusEncoder.reset()` keeps the DRED duration,
which libopus clears on reset. The models add about 4 MB to `smalt-audio`; each loads on first use.

</details>

<details>
<summary><b>Surround Opus bandwidth</b></summary>

A surround Opus stream (5.1 and the other layouts of mapping family 1) at the automatic or maximum bitrate, or with a
forced or maximum bandwidth, is coded at the bandwidth its bitrate and those settings allow, where libopus
codes narrowband or ignores the settings; at an explicit bitrate it is packet-identical to libopus.

</details>

### Other codecs

<details>
<summary><b>Xiph and lossless codecs</b></summary>

Speex, WavPack, Monkey's Audio, Musepack and DST decode only (no encoders). The Theora
encoder codes one quality index with one static Huffman code, without rate control or rate-distortion optimized
decisions (it chooses modes by the sum of absolute errors plus an estimated rate); the Theora decoder applies no post-processing
(libtheora's optional deblocking and deringing levels, off by default), and the Speex decoder always runs the
perceptual enhancer, as speexdec does by default. FFV1 decodes and encodes versions 0, 1 and 3: version 2 was never
released and the version 4 draft is not final; 4:1:1 and 4:1:0 Y'CbCr decodes as 4:2:2 and 4:2:0, each chroma sample
repeated, and is not encoded. A WavPack floating-point stream has no integer output (`decodeIntegers` raises
`MediaException.Unsupported`); its doubles are exact. Monkey's Audio files of versions 3.891 to 3.899 and 3.921 to
3.929 (alpha builds) raise `MediaException.Unsupported`, as Monkey's Audio 13.45 does. A Musepack version 7 stream
ends where its last frame does, as with libmpcdec, so samples the header counts past it are not output; a seek into
one restarts the decoder a few frames early (32 frames of preroll) and its output converges on the continuous decode
within them. Musepack version 8 chapter packets (`CT`) are not read.

</details>

<details>
<summary><b>ADPCM and telephony decoders</b></summary>

The ADPCM and telephony decoders follow the specifications and Microsoft's codecs, which ffmpeg does not
everywhere: ffmpeg's WAVE IMA ADPCM decoder expands codes with a multiplication where the IMA recommendation and
Microsoft's `imaadp32.acm` shift and add (about 55 dB apart); its Microsoft ADPCM decoder truncates the prediction
toward zero where `msadp32.acm` rounds it down, which streams using the non-trivial coefficient pairs (Smalt's
encoder picks them) reach (about 42 dB apart); its G.722 decoder keeps one bit more than the Recommendation's
15-bit output; and its G.726 coder is not the Recommendation's (its codes differ from the Appendix II sequences at
every rate, and its 40 kbit/s code assignment is incompatible: ffmpeg decodes standard 40 kbit/s streams, Smalt's
included, with heavy distortion, and Smalt decodes ffmpeg's likewise). G.722's quadrature mirror filters, which the
Recommendation leaves free in rounding and accumulator width, compute exactly and truncate.

</details>

<details>
<summary><b>MACE 3:1 and 6:1</b></summary>

MACE 3:1 and 6:1 decode but do not encode, so `AudioEncoders.create` raises `MediaException.Unsupported`. No public
description of the tables exists: they were measured from QuickTime 7.7.9 as a black box, and the decoder equals
QuickTime sample for sample on every stream tried, which is the only evidence available for them.

</details>

<details>
<summary><b>MP3 and MP2</b></summary>

The MP3 encoder writes no intensity stereo and no mixed blocks. The MP2 encoder codes mono and stereo only (no
ISO/IEC 13818-3 multichannel extension). The decoder outputs one program of a multichannel stream at a
time (`Mp3DecoderOptions.program()`: the main program, the second stereo program or the multilingual channels),
and multilingual channels coded in the other layer's syntax (`multi_lingual_layer` set on a Layer II base, or clear
on a Layer III base, which no ISO/IEC 13818-4 set carries) are not decoded. Of the Layer III extension, ISO/IEC 13818-4
covers only set 23 (segment lists, dematrixing, phantom center, LFE, the bit reservoir and extension frames):
composite coding, matrix attenuation, prediction and the multilingual channels are checked against plain decodings
of the same spectral lines, on synthetic streams written from the standard's syntax. A multichannel Layer I stream
costs two frames of delay, and a Layer III one one frame, that `flush` drains. A raw MP2 file
carries no gapless information (the LAME tag is a Layer III frame).

</details>

<details>
<summary><b>ALAC channel layouts</b></summary>

ALAC codes each channel count in one fixed speaker arrangement, so the channels of another layout decode as the
arrangement's free speakers in input order: side surrounds as back surrounds, and the side pair of 7.1 as front
left and right of center. The ALAC encoder codes 16, 20, 24 and 32-bit samples only (the decoder reads 4 to 32);
32-bit float input holds 24 bits of precision. ffmpeg 7.1 cannot decode a 32-bit channel pair sent uncompressed,
which Apple's encoder, and so Smalt's, writes for incompressible 32-bit audio such as full-scale noise; Core Audio decodes them.

</details>

### Voice processing

<details>
<summary><b>Voice detector of <code>GainControl.MANAGED_ANALOG</code></b></summary>

The pitch analysis of the voice detector of `GainControl.MANAGED_ANALOG` reads zeros where WebRTC reads
outside the bounds of its correlation surface, so a frame with a pitch peak at its border may get another voice
probability than WebRTC gives it.

</details>

Related limits elsewhere: [ambisonics, packet loss concealment, DSD and sample processing](../core/README.md#audio-1)
in core, [AAC, AC-3, E-AC-3, DTS and AMR](../mpeg/README.md#known-limits) in mpeg.

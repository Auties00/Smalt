package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.internal.header.VorbisChannelOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the per-packet decoding of multistream and projection streams against libopus 1.5.2 over lossy packet
 * traces: packets dropped by a fixed rule are concealed, or rebuilt from the in-band FEC of the next packet, and
 * the interleaved output (float, or 16-bit soft-clipped) must equal, sample for sample, that of
 * {@code opus_multistream_decode(_float)} or {@code opus_projection_decode(_float)} driven the same way by
 * {@code tools/testdata/opus-oracle/msdec.c}. The reference hashes come from
 * {@code tools/testdata/opus-oracle/traces.py}; the packets are re-encoded by Smalt and checked against the
 * reference bitstream first.
 */
class OpusPacketLossGoldenTest {
    /**
     * The encoder configurations of the traces, in the fields of {@link OpusMultistreamGoldenTest#GOLDENS}.
     */
    private static final Map<String, String> CONFIGS = Map.of(
            "s51_256", "surround6_4 48000 6 1 audio 256000 vbr 10 20000 auto auto 0 0 0 0",
            "s51_voip32_fec", "surround6_4 48000 6 1 voip 32000 vbr 9 20000 voice auto 1 10 1 0",
            "d6", "surround6_4 48000 6 255 audio 192000 vbr 10 20000 auto auto 0 0 0 0",
            "d4_wb_voip_fec", "surround4_4 16000 4 255 voip 48000 vbr 10 20000 voice wb 1 15 0 0",
            "amb6", "surround6_4_nolfe 48000 6 2 audio 160000 vbr 10 20000 auto auto 0 0 0 0",
            "p4_128", "amb4_4 48000 4 3 audio 128000 vbr 10 20000 auto auto 0 0 0 0",
            "p4_voip_dtx", "amb4_4 48000 4 3 voip 32000 vbr 10 20000 auto auto 1 20 1 0",
            "p9_voip_fec", "amb9_2 16000 9 3 voip 72000 vbr 10 20000 voice auto 1 10 0 0",
            "p6_40ms_fec", "amb6_4 48000 6 3 voip 64000 vbr 10 40000 voice auto 1 15 0 0");

    /**
     * Name, configuration, decoding rate, FEC, loss percent, loss seed, output format, SHA-256 of the libopus
     * bitstream, SHA-256 of the libopus decoder output in coded channel order.
     */
    static final String[] TRACES = {
            "t_s51_fec_f32 s51_voip32_fec 48000 1 20 3 f32 5909737b24e673782a51f9a1c7f29696b8f47f133341a2b0e0e095110369ba06 6819a59983664041c92b3625e6890006b307bbb46d71c5e245bd07e08b066e5d",
            "t_s51_fec_s16 s51_voip32_fec 48000 1 20 3 s16 5909737b24e673782a51f9a1c7f29696b8f47f133341a2b0e0e095110369ba06 8819fdfff7252f49f6c044b65df4bbd922e2b429a0aac2f495e3168e8ee7aa64",
            "t_s51_fec_16k s51_voip32_fec 16000 1 20 3 f32 5909737b24e673782a51f9a1c7f29696b8f47f133341a2b0e0e095110369ba06 e46882f5c12b0d77d213f8cd95a06d9ac9b1db7ef42d8ac3cc1d1088799bef98",
            "t_s51_plc_f32 s51_256 48000 0 15 11 f32 e1ebaeb1f1e31ebf68430981198ec385c948c0e68bcaf28df19dc270bf0f73d8 fc32fadc62e893c84c9947261c6a38c1effbc8fcd80dcf0c66ea9158b4f9e80c",
            "t_s51_s16 s51_256 48000 0 0 0 s16 e1ebaeb1f1e31ebf68430981198ec385c948c0e68bcaf28df19dc270bf0f73d8 04d9f84f2988c13854a769ba4b1f0bfb31febf74efdd88f548d71faeb1d7d69d",
            "t_d6_plc_s16 d6 48000 0 25 7 s16 125401daae299edc9ec325b507bb50dc164e2a84185c84c9be82b5c46593453f 6e270b83d629b1c1c51b52924f2e51dc1d21a87afa82ec410fbe86a17cd10787",
            "t_d4_fec_f32 d4_wb_voip_fec 16000 1 25 1 f32 acbfc76f60f1735aca5dc0caa8bf284a76a6e5764781eb005e2883289f2f51ba 63f128b73b0eb884e1ebe91b466750d130e42f44224daf3710baf273f55f02da",
            "t_amb6_plc_f32 amb6 24000 0 10 4 f32 a8873f44aa6d105aa2818474a94c8b85ed23162382d425c2eee0bd887376c43b f2074291b938f1651efdd8ec1c32b4c7e89fa020a9379b8f0684c896f2072082",
            "t_p4_plc_f32 p4_128 48000 0 15 2 f32 ffe32244170d1ad60c533f0fd090f1b70dd0581f4212608fab1b247db1d9b8d5 9f61ccaec9b3dbd7afe5dd56744505bc099714376e77685fc1d1cb4b9cc0ec86",
            "t_p4_s16 p4_128 48000 0 0 0 s16 ffe32244170d1ad60c533f0fd090f1b70dd0581f4212608fab1b247db1d9b8d5 a22caa6e20e6955b33dfe0c4eee27257164f6e840bdc7aecabece0eec090a1bc",
            "t_p4dtx_fec_s16 p4_voip_dtx 48000 1 20 9 s16 27d9a53a492ee13e4b96fbaa8097f12797ce712ab50ba6586cb15456ad1fdf0e 9e1c85b647a4757df411a839b2215ee040c72a47fe6908ff4f94c2c8c33d9d26",
            "t_p9_fec_f32 p9_voip_fec 16000 1 20 5 f32 ca563e080fc506c15a3f9b6f6cb5e3b2814d465c58fff34ea948b85b5bf16872 12cd1784e5226473f8c290377529fb7b34342326982bec3f03344d34f2ecb1d2",
            "t_p9_fec_s16 p9_voip_fec 16000 1 20 5 s16 ca563e080fc506c15a3f9b6f6cb5e3b2814d465c58fff34ea948b85b5bf16872 3ed2085c8f85efb4d8bb683232cf527a86ffff14d773f699e4a0d5a22aab1602",
            "t_p9_fec_8k p9_voip_fec 8000 1 30 6 f32 ca563e080fc506c15a3f9b6f6cb5e3b2814d465c58fff34ea948b85b5bf16872 9633b6fc36e53d9c533612ba0216b497403402c14ebe63d7198dd897f46f3b19",
            "t_p6_fec_f32 p6_40ms_fec 48000 1 25 8 f32 92e0b36263fbc3624fc67b071d9acdfc498070368ce66370f0dca6f536b2fa97 0a729b4e2cc8fe3f5194e8631c65a6a009d13813bb50ff35631a67a04a482ae5",
    };

    static Stream<String> traces() {
        return Stream.of(TRACES);
    }

    static boolean lost(int index, int percent, int seed) {
        return index > 0 && (index * 37 + seed) % 100 < percent;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("traces")
    @DisplayName("conceals and rebuilds lost packets like the libopus multistream and projection decoders")
    void lossTrace(String line) {
        var t = line.split(" ");
        var f = (t[1] + " " + CONFIGS.get(t[1])).split(" ");
        var bitstream = new ByteArrayOutputStream();
        List<byte[]> packets = OpusProjectionGoldenTest.encode(f, 6 * 1275 + 12 + 2, bitstream);
        assertEquals(t[7], OpusProjectionGoldenTest.sha256(bitstream.toByteArray()), "bitstream of " + t[1]);
        var head = new OpusEncoder(OpusMultistreamGoldenTest.options(f)).codecConfig().orElseThrow();
        var rate = Integer.parseInt(t[2]);
        var decoder = new OpusDecoder(OpusProjectionGoldenTest.track(head, rate),
                OpusDecoderOptions.builder().sampleRate(rate).build());
        var channels = decoder.layout().channels();
        var family = Integer.parseInt(f[4]);
        var position = new int[channels];
        for (var v = 0; v < channels; v++) {
            position[v] = family == 1 ? VorbisChannelOrder.waveIndex(channels, v) : v;
        }
        var fec = t[3].equals("1");
        var percent = Integer.parseInt(t[4]);
        var seed = Integer.parseInt(t[5]);
        var s16 = t[6].equals("s16");
        var floats = new float[5760 * channels];
        var shorts = new short[5760 * channels];
        var out = ByteBuffer.allocate(4 * 5760 * channels).order(ByteOrder.LITTLE_ENDIAN);
        var digest = sha256();
        var recovered = 0;
        for (var i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            int samples;
            if (lost(i, percent, seed)) {
                var span = OpusPackets.samples(packet, 0, packet.length, rate);
                if (fec && i + 1 < packets.size() && !lost(i + 1, percent, seed)) {
                    var next = packets.get(i + 1);
                    samples = s16 ? decoder.recover(next, 0, next.length, shorts, 0, span)
                            : decoder.recover(next, 0, next.length, floats, 0, span);
                    recovered++;
                } else {
                    samples = s16 ? decoder.conceal(shorts, 0, span) : decoder.conceal(floats, 0, span);
                }
                assertEquals(span, samples, "span of lost packet " + i);
            } else {
                samples = s16 ? decoder.decode(packet, 0, packet.length, shorts, 0, 5760)
                        : decoder.decode(packet, 0, packet.length, floats, 0, 5760);
            }
            out.clear();
            for (var n = 0; n < samples; n++) {
                for (var v = 0; v < channels; v++) {
                    var index = n * channels + position[v];
                    if (s16) {
                        out.putShort(shorts[index]);
                    } else {
                        out.putFloat(floats[index]);
                    }
                }
            }
            digest.update(out.array(), 0, out.position());
        }
        assertEquals(fec && percent > 0, recovered > 0, "packets rebuilt from FEC");
        assertEquals(t[8], java.util.HexFormat.of().formatHex(digest.digest()), "decode of " + t[0]);
    }

    @Test
    @DisplayName("produces spans longer than 120 ms and rebuilds from a packet longer than the span")
    void longSpans() {
        var f = ("p9_voip_fec " + CONFIGS.get("p9_voip_fec")).split(" ");
        var packets = OpusProjectionGoldenTest.encode(f, 6 * 1275 + 12 + 2, new ByteArrayOutputStream());
        var head = new OpusEncoder(OpusMultistreamGoldenTest.options(f)).codecConfig().orElseThrow();
        var decoder = new OpusDecoder(OpusProjectionGoldenTest.track(head, 16000),
                OpusDecoderOptions.builder().sampleRate(16000).build());
        var pcm = new float[16000 * 9];
        for (var i = 0; i < 10; i++) {
            decoder.decode(packets.get(i), 0, packets.get(i).length, pcm, 0, 1920);
        }
        assertEquals(4000, decoder.conceal(pcm, 0, 4000));
        assertEquals(4000, decoder.lastPacketDuration());
        assertEquals(3200, decoder.recover(packets.get(10), 0, packets.get(10).length, pcm, 0, 3200));
        assertEquals(3200, decoder.lastPacketDuration());
        // A 10 ms span cannot hold the 20 ms copy: the span is concealed whole.
        var rebuilt = new OpusDecoder(OpusProjectionGoldenTest.track(head, 16000),
                OpusDecoderOptions.builder().sampleRate(16000).build());
        var concealed = new OpusDecoder(OpusProjectionGoldenTest.track(head, 16000),
                OpusDecoderOptions.builder().sampleRate(16000).build());
        var expected = new float[160 * 9];
        var actual = new float[160 * 9];
        for (var i = 0; i < 10; i++) {
            rebuilt.decode(packets.get(i), 0, packets.get(i).length, pcm, 0, 1920);
            concealed.decode(packets.get(i), 0, packets.get(i).length, pcm, 0, 1920);
        }
        assertEquals(160, rebuilt.recover(packets.get(10), 0, packets.get(10).length, actual, 0, 160));
        assertEquals(160, concealed.conceal(expected, 0, 160));
        assertArrayEquals(expected, actual);
        assertThrows(IllegalArgumentException.class, () -> decoder.conceal(pcm, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(packets.get(11), 0, packets.get(11).length,
                pcm, 0, 160));
        assertThrows(IndexOutOfBoundsException.class, () -> decoder.conceal(new float[320 * 9 - 1], 0, 320));
    }

    @Test
    @DisplayName("routes the channels of a single stream through a mapping table that is not the identity")
    void singleStreamMapping() {
        var encoder = new OpusEncoder(OpusEncoderOptions.builder().format(48000, 2).bitrate(128000).build());
        var planes = OpusMultistreamApiTest.tones(com.github.auties00.smalt.audio.ChannelLayout.STEREO, 48000, 9600);
        var packets = OpusMultistreamApiTest.encode(encoder, planes);
        var plain = new OpusDecoder(OpusProjectionGoldenTest.track(encoder.codecConfig().orElseThrow(), 48000));
        var swapped = new OpusDecoder(OpusProjectionGoldenTest.track(
                new OpusHead(1, 2, 312, 48000, 0, 1, 1, 1, new int[]{1, 0}).toBytes(), 48000));
        var muted = new OpusDecoder(OpusProjectionGoldenTest.track(
                new OpusHead(1, 3, 312, 48000, 0, 255, 1, 1, new int[]{0, 255, 1}).toBytes(), 48000));
        var reference = new float[960 * 2];
        var actual = new float[960 * 2];
        var three = new short[960 * 3];
        var shorts = new short[960 * 2];
        var plainShorts = new OpusDecoder(OpusProjectionGoldenTest.track(encoder.codecConfig().orElseThrow(), 48000));
        for (var packet : packets) {
            var data = packet.data();
            assertEquals(960, plain.decode(data, 0, data.length, reference, 0, 960));
            assertEquals(960, swapped.decode(data, 0, data.length, actual, 0, 960));
            assertEquals(960, muted.decode(data, 0, data.length, three, 0, 960));
            plainShorts.decode(data, 0, data.length, shorts, 0, 960);
            for (var i = 0; i < 960; i++) {
                assertEquals(reference[2 * i], actual[2 * i + 1]);
                assertEquals(reference[2 * i + 1], actual[2 * i]);
                assertEquals(shorts[2 * i], three[3 * i]);
                assertEquals(0, three[3 * i + 1]);
                assertEquals(shorts[2 * i + 1], three[3 * i + 2]);
            }
        }
    }

    private static java.security.MessageDigest sha256() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}

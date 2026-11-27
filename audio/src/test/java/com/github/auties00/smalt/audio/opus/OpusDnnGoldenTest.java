package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusStreamEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the neural extensions of libopus 1.5.2 end to end against the build with {@code --enable-dred
 * --enable-osce} (generic C networks) whose DRED is the standardized one of libopus 1.6.1: the packets of encoders with Deep REDundancy must equal libopus's byte for
 * byte, and decoders of complexity 5 to 10 must reproduce, sample for sample, libopus's output over lossy traces
 * whose lost packets are concealed with the deep PLC, rebuilt from in-band FEC, or rebuilt from the DRED of the next
 * packet as {@code opus_demo} does, with LACE and NoLACE enhancing SILK speech at complexity 6 and from 7. A trace
 * below complexity 5 must reproduce the build without the extensions. The hashes come from
 * {@code tools/testdata/opus-oracle/dnn_goldens.py}, which drives {@code dnn.c} and {@code msdec.c}.
 */
class OpusDnnGoldenTest {
    /**
     * Name, input, rate, channels, application, bitrate, mode, complexity, frame duration, signal, maximum
     * bandwidth, FEC, expected loss, DTX, DRED duration in 10 ms frames, SHA-256 of the libopus bitstream.
     */
    static final String[] ENCODERS = {
            "e_dred48 speech48k 48000 1 voip 32000 cvbr 10 20000 auto auto 0 20 0 100 a325c893e6272a7e71350ddb000a1011d4f9180921e0a4663089cf9321580d63",
            "e_dred16_fec speech16k 16000 1 voip 40000 vbr 10 20000 auto auto 1 15 0 50 760c5360d0ec1d212b772e3c93b5a43a3b17b21c1410344c294b3b7983152727",
            "e_dred48_cbr40 speech48k 48000 1 voip 24000 cbr 10 40000 auto auto 0 25 0 100 907fb287f0003aee69d20528cba53e43693c4f73963b9efe86ba8aec4f4f6052",
            "e_dred16_st60 speech16k_stereo 16000 2 voip 32000 vbr 9 60000 voice auto 0 30 0 60 c350dfca01ae10f127e4604f4bf98d71d31ce75affbe0c5001f0477cc1ceb35a",
            "e_dred48_dtx speech48k 48000 1 voip 20000 vbr 10 20000 auto auto 0 20 1 100 b9d65aa3552db8d422d3e88bc4a890cd8b6fb0b508ed922c97d58357783faa07",
            "e_dred48_hyb40 speech48k 48000 1 audio 48000 cvbr 10 40000 auto auto 0 20 0 30 d3270ed46f010c1c50ba80bf69d423517b1e7f61b72b01a0ce1972e44228af8b",
            "e_dred12 speech12k 12000 1 voip 32000 vbr 10 20000 auto auto 0 20 0 40 89a28a4d9fd4ccc07894cdfb782e1bab4eb3889cb50d2551079b9a60fdd67d49",
            "e_dred8 speech8k 8000 1 voip 28000 vbr 10 10000 auto auto 0 25 0 104 2845164d6041121f5a40b97c611ba1ced6d8fe99a23eb7dcb640cffb98bb7f0c",
            "e_dred24 speech24k 24000 1 voip 40000 vbr 10 20000 auto auto 0 15 0 20 c1e7198b8bb32451a8372f2bf32f3f960ac61b5acd21cc42a989f9e73f270867",
            "e_plain16 speech16k 16000 1 voip 20000 vbr 10 20000 auto auto 1 15 0 0 d03c9d35ec9e5403f288dfd4435f85566fb16cd1ef57aa1b265ef0ba63e93758",
            "e_celt48 music48k_mono 48000 1 audio 48000 vbr 10 20000 auto auto 0 0 0 0 c6e7fd6942daeb0a325629ede243e557f676e44807d703c3c2bbce4585fb36cf",
            "e_celt48st music48k 48000 2 audio 96000 vbr 10 20000 auto auto 0 0 0 0 2167cd8bfd7a9bdfe14f13ec1af04be534deee8b4c7224549aca488009f6afc5",
    };

    /**
     * Name, encoder configuration, decoding rate, complexity, loss percent, loss seed, mode, output format, SHA-256 of
     * the libopus decoder output, number of packets rebuilt from DRED.
     */
    static final String[] DECODERS = {
            "d_dred48_c5 e_dred48 48000 5 20 3 dred f32 418987425516c3908b757c1529efc45aa6dc95bd755a16f84e22582701ab6cc2 99",
            "d_dred48_c7_s16 e_dred48 48000 7 20 3 dred s16 74d2a20b14a391557dce4db57515c49e632063ca8c557f50dfe26ad446d810e6 99",
            "d_dred48_at16k e_dred48 16000 6 25 5 dred f32 e6d2f49f49077e391e20a14845295a860ac693a1d0b86193dc4b8d8d50a9dd6a 124",
            "d_dred16_fec e_dred16_fec 16000 10 15 2 fec f32 4442c1e1abf5ffad7be8fac3e31df300a28684cdc28b6c9d12aef3402c9ded6d 0",
            "d_dred16_dred e_dred16_fec 16000 5 30 9 dred f32 983790dbc1fe7ab248e57b4978560410670f3a6b182649d458689c210d74392c 142",
            "d_dred48_cbr40 e_dred48_cbr40 48000 5 25 1 dred f32 9cfb8ed0016b734ec694aa133d597325bd1bd1c52131e47e00d4a8571004268b 63",
            "d_dred16_st60 e_dred16_st60 16000 7 30 2 dred f32 8f5f7de33e659d7ab21c50d58b796e4c3dcb2f956e239c86e52d21c59b346a28 49",
            "d_dred48_dtx e_dred48_dtx 48000 5 20 6 dred f32 1e36a58114a802df6a948a8867759a6da7a91646a26500d8ade44dff099f948d 97",
            "d_dred48_hyb40 e_dred48_hyb40 48000 5 20 4 dred f32 0b6d4fdbd56867a3c4500160ad2e479a3d54cf622e302415236aed8d551dcd24 50",
            "d_dred12 e_dred12 12000 6 20 8 dred f32 40d21404235c6c99c0d1510a6c7f5aa4894430772b102b7a0a39f1ad77fab324 0",
            "d_dred8 e_dred8 8000 5 35 3 dred f32 5de57ae5911c8fec388080aea18af9726f2ab63bfd2653ed1a388e5e34205b0e 0",
            "d_dred24 e_dred24 24000 9 20 4 dred s16 3f78ad067a23a62c63c3be028b9078b3c0deae324109d6312d4f802f1678bc2a 99",
            "d_plc16 e_plain16 16000 5 15 2 plc f32 fba7152fbb4d39634c9012efe4d5bc5ae85b70f693c8fe09b2d1d7a14ad2c020 0",
            "d_lace16 e_plain16 16000 6 0 0 plc f32 8c70aa986c3aadb4fa14a1f392865ab77408aa9e7a6ffe4fa18ffb3970638b5e 0",
            "d_nolace16 e_plain16 16000 7 0 0 plc f32 91f109359e6feefbaf8eea59fd47370baba4d5ba1525363076ce50e965180b64 0",
            "d_nolace16_loss e_plain16 16000 10 15 2 plc s16 6f4cb45741521b05d60361ed586eba8cec6f82a070c5f9249c423b015216e6de 0",
            "d_nolace48 e_dred48 48000 8 0 0 plc f32 41aa7c70f5711a502559fd79fbbcae08200284f10273abe401f4075427bdbf39 0",
            "d_celt48 e_celt48 48000 5 15 4 plc f32 d66c4fb1138029b7590e44b113ee53a4a8411379d52e3a5d2c7aef3d69fdd2f5 0",
            "d_celt48st e_celt48st 48000 5 15 7 plc f32 f4605b08b9b7a33ed4733ce67a84a5b3a0eb29a55fc55002a37ba8a2b2ac9354 0",
            "d_celt48st_c4 e_celt48st 48000 4 15 7 plc f32 ae307e82fa6922da229c9edc5ff362d85c0dc0926f06b020c932cd75d00a47b1 0",
    };

    private static final Map<String, List<byte[]>> PACKETS = new ConcurrentHashMap<>();

    static Stream<String> encoders() {
        return Stream.of(ENCODERS);
    }

    static Stream<String> decoders() {
        return Stream.of(DECODERS);
    }

    static String[] encoder(String name) {
        for (var line : ENCODERS) {
            if (line.startsWith(name + " ")) {
                return line.split(" ");
            }
        }
        throw new IllegalArgumentException(name);
    }

    static OpusEncoderOptions options(String[] f) {
        return OpusEncoderGoldenTest.config(f).toBuilder()
                .inbandFec(f[11].equals("1"))
                .packetLossPercent(Integer.parseInt(f[12]))
                .dtx(f[13].equals("1"))
                .dredDurationMillis(10 * Integer.parseInt(f[14]))
                .build();
    }

    /** Encodes a configuration with the stream encoder, as dnn.c "enc" does, into opus_demo form. */
    static List<byte[]> encode(String[] f, ByteArrayOutputStream bitstream) {
        var config = options(f);
        var input = OpusEncoderGoldenTest.input(f[1]);
        var encoder = new OpusStreamEncoder(config.sampleRate(), config.channels(), switch (config.application()) {
            case VOIP -> OpusDefines.OPUS_APPLICATION_VOIP;
            case AUDIO -> OpusDefines.OPUS_APPLICATION_AUDIO;
            case RESTRICTED_LOWDELAY -> OpusDefines.OPUS_APPLICATION_RESTRICTED_LOWDELAY;
        });
        OpusEncoderGoldenTest.configure(encoder, config);
        if (config.inbandFec()) {
            encoder.setInbandFec(1);
        }
        if (config.packetLossPercent() != 0) {
            encoder.setPacketLossPerc(config.packetLossPercent());
        }
        if (config.dtx()) {
            encoder.setDtx(true);
        }
        encoder.setDredDuration(config.dredDurationMillis() / 10);
        var channels = config.channels();
        var frame = config.frameSize();
        var total = input.length / channels;
        var target = (long) total + encoder.lookahead();
        var buffer = new float[frame * channels];
        var packet = new byte[1276 * 6];
        var packets = new ArrayList<byte[]>();
        long position = 0;
        while (position < target) {
            for (var i = 0; i < frame * channels; i++) {
                var sample = position * channels + i;
                buffer[i] = sample < (long) total * channels ? (1.0f / 32768) * input[(int) sample] : 0;
            }
            position += frame;
            var length = encoder.encodeFloat(buffer, 0, frame, packet, 0, packet.length);
            assertTrue(length > 0, "encode failed at packet " + packets.size() + ": " + length);
            if (bitstream != null) {
                writeInt(bitstream, length);
                writeInt(bitstream, encoder.finalRange());
                bitstream.write(packet, 0, length);
            }
            packets.add(java.util.Arrays.copyOf(packet, length));
        }
        return packets;
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    static List<byte[]> packets(String name) {
        return PACKETS.computeIfAbsent(name, key -> encode(encoder(key), null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encoders")
    @DisplayName("codes Deep REDundancy into the same packets as libopus")
    void encoderBitExact(String line) {
        var f = line.split(" ");
        var bitstream = new ByteArrayOutputStream();
        var packets = encode(f, bitstream);
        PACKETS.putIfAbsent(f[0], packets);
        assertEquals(f[15], HexFormat.of().formatHex(sha256().digest(bitstream.toByteArray())), "bitstream of " + f[0]);
        var dred = Integer.parseInt(f[14]) > 0;
        var carrying = packets.stream().filter(p -> OpusPackets.hasDred(p, 0, p.length)).count();
        // Below 16 kHz libopus runs no tonality analysis, so no frame counts as active and no DRED is sent.
        assertEquals(dred && Integer.parseInt(f[2]) >= 16000, carrying > 0, "packets carrying DRED: " + carrying);
        var publicPackets = OpusEncoderGoldenTest.encodePublic(options(f), OpusEncoderGoldenTest.input(f[1]));
        assertEquals(packets.size(), publicPackets.size(), "packet count of the public encoder");
        for (var i = 0; i < packets.size(); i++) {
            assertArrayEquals(packets.get(i), publicPackets.get(i).data(), "public encoder packet " + i);
        }
    }

    static boolean lost(int index, int percent, int seed) {
        return index > 0 && (index * 37 + seed) % 100 < percent;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decoders")
    @DisplayName("conceals, enhances and rebuilds from DRED like the libopus neural build")
    void decoderBitExact(String line) {
        var t = line.split(" ");
        var f = encoder(t[1]);
        var packets = packets(t[1]);
        var rate = Integer.parseInt(t[2]);
        var channels = Integer.parseInt(f[3]);
        var decoder = new OpusDecoder(OpusDecoderOptions.builder().sampleRate(rate).channels(channels)
                .complexity(Integer.parseInt(t[3])).build());
        var percent = Integer.parseInt(t[4]);
        var seed = Integer.parseInt(t[5]);
        var mode = t[6];
        var s16 = t[7].equals("s16");
        var max = rate / 1000 * 120 * 10;
        var floats = new float[max * channels];
        var shorts = new short[max * channels];
        var out = ByteBuffer.allocate(4 * max * channels).order(ByteOrder.LITTLE_ENDIAN);
        var digest = sha256();
        var dred = new OpusDred();
        var rebuilt = 0;
        var lostCount = 0;
        for (var i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            if (lost(i, percent, seed)) {
                if (mode.equals("plc")) {
                    var span = OpusPackets.samples(packet, 0, packet.length, rate);
                    write(out, digest, s16, floats, shorts, channels,
                            s16 ? decoder.conceal(shorts, 0, span) : decoder.conceal(floats, 0, span));
                } else {
                    lostCount++;
                }
                continue;
            }
            if (lostCount > 0) {
                var duration = decoder.lastPacketDuration();
                var available = 0;
                if (mode.equals("dred")) {
                    available = dred.parse(packet, 0, packet.length, Math.min(48000, lostCount * duration), rate);
                }
                for (var fr = 0; fr < lostCount; fr++) {
                    int n;
                    if (mode.equals("fec") && fr == lostCount - 1 && OpusPackets.hasInbandFec(packet, 0, packet.length)) {
                        n = s16 ? decoder.recover(packet, 0, packet.length, shorts, 0, duration)
                                : decoder.recover(packet, 0, packet.length, floats, 0, duration);
                    } else if (available > 0) {
                        n = s16 ? decoder.recover(dred, (lostCount - fr) * duration, shorts, 0, duration)
                                : decoder.recover(dred, (lostCount - fr) * duration, floats, 0, duration);
                        rebuilt++;
                    } else {
                        n = s16 ? decoder.conceal(shorts, 0, duration) : decoder.conceal(floats, 0, duration);
                    }
                    write(out, digest, s16, floats, shorts, channels, n);
                }
                lostCount = 0;
            }
            write(out, digest, s16, floats, shorts, channels, s16 ? decoder.decode(packet, 0, packet.length, shorts, 0,
                    max) : decoder.decode(packet, 0, packet.length, floats, 0, max));
        }
        for (; lostCount > 0; lostCount--) {
            var duration = decoder.lastPacketDuration();
            write(out, digest, s16, floats, shorts, channels,
                    s16 ? decoder.conceal(shorts, 0, duration) : decoder.conceal(floats, 0, duration));
        }
        assertEquals(Integer.parseInt(t[9]), rebuilt, "packets rebuilt from DRED in " + t[0]);
        assertEquals(t[8], HexFormat.of().formatHex(digest.digest()), "decode of " + t[0]);
    }

    private static void write(ByteBuffer out, MessageDigest digest, boolean s16, float[] floats, short[] shorts,
                              int channels, int samples) {
        out.clear();
        for (var i = 0; i < samples * channels; i++) {
            if (s16) {
                out.putShort(shorts[i]);
            } else {
                out.putFloat(floats[i]);
            }
        }
        digest.update(out.array(), 0, out.position());
    }

    /** One OSCE test bitstream decoded at one complexity, with libopus's result. */
    record OsceCase(String bitstream, int complexity, String sha256, int samples) {
        @Override
        public String toString() {
            return bitstream.substring(bitstream.lastIndexOf('/') + 1) + " at complexity " + complexity;
        }
    }

    static Stream<OsceCase> osce() {
        try (var in = OpusDnnGoldenTest.class.getResourceAsStream("osce-goldens.tsv")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.split("\t"))
                    .map(f -> new OsceCase(f[0], Integer.parseInt(f[1]), f[2], Integer.parseInt(f[3])))
                    .toList().stream();
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("osce")
    @DisplayName("decodes the OSCE test bitstreams of real speech as the libopus neural build does, LACE at 6 and NoLACE at 10")
    void osceBitExact(OsceCase test) {
        // the SILK wideband bitstreams of osce_testvectors_v0 (6, 12 and 24 kbit/s and switching, encoder
        // complexity 0 and 10) carry the encoder's final range of every packet, which the decoder must reproduce
        var file = com.github.auties00.smalt.testkit.Conformance.archive("opus-osce-v0-en-subset")
                .member("osce_testvectors_v0/" + test.bitstream());
        var packets = OpusVectors.readBit(file);
        var decoder = new OpusDecoder(OpusDecoderOptions.builder().sampleRate(16000).channels(1)
                .complexity(test.complexity()).build());
        var max = 16 * 120;
        var floats = new float[max];
        var out = ByteBuffer.allocate(4 * max).order(ByteOrder.LITTLE_ENDIAN);
        var digest = sha256();
        var samples = 0;
        for (var i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            var n = decoder.decode(packet.data(), 0, packet.data().length, floats, 0, max);
            assertEquals(packet.finalRange(), decoder.finalRange(), "final range of packet " + i);
            write(out, digest, false, floats, null, 1, n);
            samples += n;
        }
        assertEquals(test.samples(), samples, "samples");
        assertEquals(test.sha256(), HexFormat.of().formatHex(digest.digest()), "decode of " + test);
    }
}

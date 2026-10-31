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
 * --enable-osce} (generic C networks): the packets of encoders with Deep REDundancy must equal libopus's byte for
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
            "e_dred48 speech48k 48000 1 voip 32000 cvbr 10 20000 auto auto 0 20 0 100 d11ece46d6e56266295d6bbaa89d57982f461589eab921df0a9e198c0d2eb112",
            "e_dred16_fec speech16k 16000 1 voip 40000 vbr 10 20000 auto auto 1 15 0 50 8e68eb4f2332eb170efc069a85a338dd2f62b86179e04f52f868cb05dcb668ad",
            "e_dred48_cbr40 speech48k 48000 1 voip 24000 cbr 10 40000 auto auto 0 25 0 100 38cfc7f64a5b01f675006c9bda62179f356ef2a54861876d781bf5a59f258d9f",
            "e_dred16_st60 speech16k_stereo 16000 2 voip 32000 vbr 9 60000 voice auto 0 30 0 60 d6a254499c86370b7883b3d13aee2ae6d181960ea36ab87acaab0ca25e0dd8aa",
            "e_dred48_dtx speech48k 48000 1 voip 20000 vbr 10 20000 auto auto 0 20 1 100 98816055ac8f8789d5e3fd5f9e2335cf66a8117f0d88a2212f1be032b25f4c0f",
            "e_dred48_hyb40 speech48k 48000 1 audio 48000 cvbr 10 40000 auto auto 0 20 0 30 c0c16646a0e44c53b835b8acc34e4f101f34cbe06420894ca50fdfb01c611b92",
            "e_dred12 speech12k 12000 1 voip 32000 vbr 10 20000 auto auto 0 20 0 40 89a28a4d9fd4ccc07894cdfb782e1bab4eb3889cb50d2551079b9a60fdd67d49",
            "e_dred8 speech8k 8000 1 voip 28000 vbr 10 10000 auto auto 0 25 0 104 2845164d6041121f5a40b97c611ba1ced6d8fe99a23eb7dcb640cffb98bb7f0c",
            "e_dred24 speech24k 24000 1 voip 40000 vbr 10 20000 auto auto 0 15 0 20 b2733c7b01effbfcfcaebe00c974b91d4f8cc77d1d0b6d7d9aa103f250d667ce",
            "e_plain16 speech16k 16000 1 voip 20000 vbr 10 20000 auto auto 1 15 0 0 d03c9d35ec9e5403f288dfd4435f85566fb16cd1ef57aa1b265ef0ba63e93758",
            "e_celt48 music48k_mono 48000 1 audio 48000 vbr 10 20000 auto auto 0 0 0 0 c6e7fd6942daeb0a325629ede243e557f676e44807d703c3c2bbce4585fb36cf",
            "e_celt48st music48k 48000 2 audio 96000 vbr 10 20000 auto auto 0 0 0 0 2167cd8bfd7a9bdfe14f13ec1af04be534deee8b4c7224549aca488009f6afc5",
    };

    /**
     * Name, encoder configuration, decoding rate, complexity, loss percent, loss seed, mode, output format, SHA-256 of
     * the libopus decoder output, number of packets rebuilt from DRED.
     */
    static final String[] DECODERS = {
            "d_dred48_c5 e_dred48 48000 5 20 3 dred f32 7c4d4b67dc12252b7904eca703dbe5ba14b42f7f30a4f51ab5aab676cbbd176b 99",
            "d_dred48_c7_s16 e_dred48 48000 7 20 3 dred s16 09f4d0f8750b0fedfed33297ba4e2a8d42c88c30f5f4c6b4f7071c8529256202 99",
            "d_dred48_at16k e_dred48 16000 6 25 5 dred f32 596fd722cf7f9209ecb5104ba6aa9cf03f213dd6f42083718b0dc5a8223b83d0 124",
            "d_dred16_fec e_dred16_fec 16000 10 15 2 fec f32 4442c1e1abf5ffad7be8fac3e31df300a28684cdc28b6c9d12aef3402c9ded6d 0",
            "d_dred16_dred e_dred16_fec 16000 5 30 9 dred f32 a5fe643d54f21a5cfa254868071c7d585e38c875822f0df3e8c0daeb080c7d70 142",
            "d_dred48_cbr40 e_dred48_cbr40 48000 5 25 1 dred f32 16fde3bc99968ad5360a35d367f95e8ae1e91acb54d84ec12ea11966e68c47ed 63",
            "d_dred16_st60 e_dred16_st60 16000 7 30 2 dred f32 0fe29ea782f1e6c09a076d6eb53f838ff555559af55edfeefd68cc2554acbe82 49",
            "d_dred48_dtx e_dred48_dtx 48000 5 20 6 dred f32 b9249e54e4305b2fadf43892263b2932b6ed2a5640a5cea1bd8ff0f3ed7a7f1a 98",
            "d_dred48_hyb40 e_dred48_hyb40 48000 5 20 4 dred f32 e44b4a5221eb2ff57533374e292d6fa03635d9fcf063f8b45245220130255667 50",
            "d_dred12 e_dred12 12000 6 20 8 dred f32 40d21404235c6c99c0d1510a6c7f5aa4894430772b102b7a0a39f1ad77fab324 0",
            "d_dred8 e_dred8 8000 5 35 3 dred f32 5de57ae5911c8fec388080aea18af9726f2ab63bfd2653ed1a388e5e34205b0e 0",
            "d_dred24 e_dred24 24000 9 20 4 dred s16 de2115e08097ae271393b12a9b5f1eb82ccbefc23907dd4840b52d8bb68c282d 99",
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
}

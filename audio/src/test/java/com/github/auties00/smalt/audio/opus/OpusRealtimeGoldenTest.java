package com.github.auties00.smalt.audio.opus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks {@link OpusEncoder}, {@link OpusDecoder} and {@link OpusPackets} through their 16-bit
 * entry points against portable C libopus 1.5.2 (float build without SIMD), driven the way a voice call drives
 * them: 16 kHz mono VOIP opened with the call control set (25 kb/s VBR, complexity 9, in-band FEC, DTX, voice,
 * 16-bit depth, wideband cap), optionally retargeted once a second from a schedule that sweeps the bitrate
 * (6-32 kb/s), the expected loss (0-25%), VBR and CBR, every complexity from 0 to 10 and every maximum bandwidth,
 * with 20 and 60 ms frames and encoder and decoder resets mid-stream. The decoder runs a jitter-buffer policy under
 * a seeded loss mask: received packets decode at a 5760-sample capacity, a lost packet whose successor arrived is
 * rebuilt from the successor's in-band copy at the successor's duration, and other losses are concealed at the
 * packet duration, whole or in 20 ms steps.
 *
 * <p>The three hashes cover the packets with their final ranges (opus_demo bitstream form), the decoded 16-bit
 * samples, and a state record of {@code OPUS_GET_BITRATE} after each retarget, the LBRR flag, sample count and
 * bandwidth of every packet, the look-ahead, and the sample count and {@code OPUS_GET_LAST_PACKET_DURATION} of
 * every decode call. The reference values come from the {@code rtgold.c} harness linked against the portable
 * libopus build, fed the same inputs as {@link OpusCallGoldenTest}.
 */
class OpusRealtimeGoldenTest {
    private static final int RATE = 16000;
    private static final int[] SCHEDULE_BITRATE = {25000, 20000, 32000, 16000, 12000, 8000, 25000, 6000, 32000, 24000};
    private static final int[] SCHEDULE_LOSS = {0, 5, 10, 15, 20, 25, 10, 0, 25, 5};
    private static final boolean[] SCHEDULE_VBR = {true, true, true, false, true, true, false, true, true, true};
    private static final int[] SCHEDULE_COMPLEXITY = {9, 9, 4, 9, 2, 10, 0, 5, 1, 9};
    private static final OpusBandwidth[] SCHEDULE_BANDWIDTH = {
            OpusBandwidth.WIDEBAND, OpusBandwidth.WIDEBAND,
            OpusBandwidth.NARROWBAND, OpusBandwidth.WIDEBAND,
            OpusBandwidth.MEDIUMBAND, OpusBandwidth.WIDEBAND,
            OpusBandwidth.SUPERWIDEBAND, OpusBandwidth.WIDEBAND,
            OpusBandwidth.FULLBAND, OpusBandwidth.WIDEBAND};

    @ParameterizedTest(name = "{0} {1} ms schedule={2} loss={3}% encReset={4} decReset={5} mask={6}%")
    @CsvSource({
            "speech16k, 60, true, 10, -1, -1, 10, 1, 2e9b0ad7386bbf5f1f72bd4f683c87a85be4919ea12f870682b5d6de990da47b, f315edcc02377c3d352f271f98d7bbcae0210d2c5ae86a58787f5afb2d5f23a9, 990894f80d43b78461b47e1074fbcac4216e4ca83ac39b2294972816bdeea4d8",
            "gated16k, 60, true, 0, 50, 80, 25, 2, 2bcfb9182b559f7cbad48c3c362b932ee72f6dbd6b62b838f4b8264c0a0737bd, c82bcb452b989ef0d5bcb1838774082a42034679c52d1d717de9551a296a064c, 49bc7100436b87cc5eb74b3ebbf652897294f07ed6b18957bbe5392add2cbe52",
            "alt16k, 20, true, 25, 120, -1, 15, 3, 7b1c6ddef52f2e612f5621f0da938eaa7a3d942a518adab7945080408a00693d, 7e08e35356fedb4314238674dbddac6ff63df55fda5c2d2ab2d439be92c40456, 024d8719d87db3c7c9cb7b291ed3a7e2c48476fc4af813af07c13f6e38426546",
            "gated16k, 20, false, 5, -1, -1, 0, 4, 136abc3476d0a68cb209cf1295804e1dfb8ec37d7be90502cda31143c0211b19, 2699c9559df89cf0bb58870485519c94b54d42a1c8ab2dcc1cabbf5060d8d38d, ea9c59048df7c623677ba8529dbe8f0d348195783942aff44f1e17f87a8c4ea8",
            "speech16k, 60, false, 25, -1, 30, 20, 5, f2382c86296bc0072beaac0f3e4cf3f799dd5da1149e6bf5436da37d7c65dcd5, a98cb76ee4bf1b31af8718b559358487ca015c2b40f132b66c4842b3d0a25ecd, 88726b87bbe1b5110ebb80efe594dbd94a12526680d34e318edebf368ede1457",
    })
    @DisplayName("encodes and decodes 16-bit audio under a live control schedule exactly like the libopus reference")
    void bitExact(String input, int frameMillis, boolean schedule, int loss, int encoderReset, int decoderReset,
                  int maskPercent, long seed, String bitstreamHash, String pcmHash, String stateHash) throws Exception {
        var pcm = OpusCallGoldenTest.input(input);
        var bitstream = new ByteArrayOutputStream();
        var decoded = new ByteArrayOutputStream();
        var state = new ByteArrayOutputStream();

        var encoder = new OpusEncoder(OpusEncoderOptions.forVoip(RATE, 1));
        encoder.setBitrate(25000);
        encoder.setBitrateMode(OpusBitrateMode.CONSTRAINED_VBR);
        encoder.setComplexity(9);
        encoder.setInbandFec(true);
        encoder.setPacketLossPercent(loss);
        encoder.setDtx(true);
        encoder.setAutomaticChannels();
        encoder.setSignal(OpusSignal.VOICE);
        encoder.setLsbDepth(16);
        encoder.setMaxBandwidth(OpusBandwidth.WIDEBAND);
        var lookahead = encoder.delay();
        var frame = RATE / 1000 * frameMillis;
        var perTick = 1000 / frameMillis;
        var target = (long) pcm.length + lookahead;
        var packets = new ArrayList<byte[]>();
        var buffer = new short[frame];
        var packet = new byte[1500];
        long coded = 0;
        long position = 0;
        while (coded < target) {
            var index = packets.size();
            if (schedule && index % perTick == 0) {
                var tick = (index / perTick) % SCHEDULE_BITRATE.length;
                encoder.setBitrate(SCHEDULE_BITRATE[tick]);
                encoder.setPacketLossPercent(SCHEDULE_LOSS[tick]);
                encoder.setBitrateMode(SCHEDULE_VBR[tick] ? OpusBitrateMode.CONSTRAINED_VBR : OpusBitrateMode.CBR);
                encoder.setComplexity(SCHEDULE_COMPLEXITY[tick]);
                encoder.setMaxBandwidth(SCHEDULE_BANDWIDTH[tick]);
                writeInt(state, encoder.bitrate());
            }
            if (index == encoderReset) {
                encoder.reset();
            }
            for (var i = 0; i < frame; i++) {
                var sample = position + i;
                buffer[i] = sample < pcm.length ? pcm[(int) sample] : 0;
            }
            position += frame;
            var length = encoder.encode(buffer, 0, frame, packet, 0, packet.length);
            writeInt(bitstream, length);
            writeInt(bitstream, encoder.finalRange());
            bitstream.write(packet, 0, length);
            writeInt(state, length > 1 && OpusPackets.hasInbandFec(packet, 0, length) ? 1 : 0);
            writeInt(state, OpusPackets.samples(packet, 0, length, RATE));
            writeInt(state, 1101 + OpusPackets.bandwidth(packet, 0).ordinal());
            packets.add(Arrays.copyOf(packet, length));
            coded += frame;
        }
        writeInt(state, lookahead);

        var received = lossMask(packets.size(), maskPercent, seed);
        var decoder = new OpusDecoder(OpusDecoderOptions.of(RATE, 1));
        var out = new short[5760];
        for (var i = 0; i < packets.size(); i++) {
            if (i == decoderReset) {
                decoder.reset();
            }
            if (received[i]) {
                var p = packets.get(i);
                record(decoder, decoder.decode(p, 0, p.length, out, 0, out.length), out, state, decoded);
            } else if (i + 1 < packets.size() && received[i + 1]) {
                var next = packets.get(i + 1);
                var duration = OpusPackets.samples(next, 0, next.length, RATE);
                record(decoder, decoder.recover(next, 0, next.length, out, 0, duration), out, state, decoded);
            } else if (i % 2 == 0) {
                record(decoder, decoder.conceal(out, 0, frame), out, state, decoded);
            } else {
                for (var k = 0; k < frame / 320; k++) {
                    record(decoder, decoder.conceal(out, 0, 320), out, state, decoded);
                }
            }
        }

        var bitstreamBytes = bitstream.toByteArray();
        var pcmBytes = decoded.toByteArray();
        var stateBytes = state.toByteArray();
        assertAll(
                () -> assertEquals(bitstreamHash, sha256(bitstreamBytes), "packets"),
                () -> assertEquals(pcmHash, sha256(pcmBytes), "decoded samples"),
                () -> assertEquals(stateHash, sha256(stateBytes), "state"));
    }

    // the harness's xorshift64 loss mask: iid loss plus 1-2 packet bursts after one in five losses
    private static boolean[] lossMask(int count, int percent, long seed) {
        var received = new boolean[count];
        Arrays.fill(received, true);
        var rng = new long[]{seed * 0x9E3779B97F4A7C15L + 1};
        for (var i = 1; i < count; i++) {
            if (next(rng) % 100 < percent) {
                received[i] = false;
                if (next(rng) % 5 == 0) {
                    var burst = 1 + (int) (next(rng) % 2);
                    for (var k = 1; k <= burst && i + k < count; k++) {
                        received[i + k] = false;
                    }
                    i += burst;
                }
            }
        }
        return received;
    }

    private static long next(long[] rng) {
        var x = rng[0];
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        rng[0] = x;
        return x >>> 32;
    }

    private static void record(OpusDecoder decoder, int samples, short[] out, ByteArrayOutputStream state,
                               ByteArrayOutputStream decoded) {
        writeInt(state, samples);
        writeInt(state, decoder.lastPacketDuration());
        for (var i = 0; i < samples; i++) {
            decoded.write(out[i]);
            decoded.write(out[i] >>> 8);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }
}

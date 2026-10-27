package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.opus.internal.celt.MathOps;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the decoder's downmix of multistream and projection streams to mono and stereo. The Vorbis-order stereo
 * downmix must equal opusfile 0.12's {@code op_read_float_stereo} bit for bit (the reference hashes come from
 * {@code tools/testdata/opus-oracle/downmix.py}, which muxes libopus packets into Ogg with libogg and decodes them
 * with opusfile), except the right channel of quadraphonic streams, where opusfile misprints a coefficient; the
 * ambisonic, discrete and mono downmixes must equal the matrices of RFC 8486 and the decoder's contract applied
 * to the full multichannel decode.
 */
class OpusDownmixTest {
    /**
     * Name, input, rate, channels, family, application, bitrate, mode, complexity, frame us, signal, max
     * bandwidth, FEC, loss, DTX, 16-bit input, SHA-256 of the libopus bitstream, SHA-256 of opusfile's stereo
     * output, SHA-256 of its left channel.
     */
    static final String[] GOLDENS = {
            "o30 surround3_4 48000 3 1 audio 128000 vbr 10 20000 auto auto 0 0 0 0 f38836529e2c3470ffe3d027d7acdd09b6f47eacc7d7a45533b173eac66dc285 7a1f339c6e8f5597ea3f4d1d249cf47cbe2dd9dc1566374dc5ddf73d947311b6 d5598552ad6371a234cb00a2eda50e568bbecbcd5153843d4ca3e21ceee38e83",
            "o40 surround4_4 48000 4 1 audio 160000 vbr 10 20000 auto auto 0 0 0 0 68fb792eeb31283c1d74993142d0487a605e74d42f977584a7d7e2162bd86cc5 a886962836b3f54dbe71100f0c8ad9d178d0642d792be54e988d3f355c1d5920 1f100d34280cd7fd585387964dd3d64bfe110db3541802a8c767d997097623fe",
            "o50 surround5_4 48000 5 1 audio 192000 vbr 10 20000 auto auto 0 0 0 0 fedaa7299cd2c1d784df5a61769c9063c44ae89f3ae182bb1168f173187e9e6c c64b7a2bc9efcabed240e7c96a870b0e8733c70bb8266abee796a0b3889ddbe4 a73fd4dba4f94dfc9a275bf1430cb24a53515f48be106d10df99dd989f4fa139",
            "o51 surround6_4 48000 6 1 audio 256000 vbr 10 20000 auto auto 0 0 0 0 e1ebaeb1f1e31ebf68430981198ec385c948c0e68bcaf28df19dc270bf0f73d8 6ce048538b881727f0f8b388c07d1b91ce0931e6a15c9831805310cbd23f5e0d e7b83f59a7920c5e1ec2e6d56b852a5b39f67049f04514a51ac6a17b8ac9c7f5",
            "o51_voip surround6_4 48000 6 1 voip 64000 vbr 10 20000 auto auto 0 0 0 0 0437f74196d4521d55b4ba50284b7fab376b580dfb0b6b48c70beada1da2d993 1896b83dac77d0af7910c8e131286f4ea74d92885c182816cfe71ee329dd8ded ab0dcc4223b19f258a5525790e4eea4a48c17861cefd0df19fe0057ce880e1d1",
            "o61 surround7_4 48000 7 1 audio 224000 vbr 10 20000 auto auto 0 0 0 0 2b4286c25a250e29d5cee5297ae43b7b5e0427fbfb673ac0929cf57c5014db54 1ec8e6939b7be6c4cbc79b2bab18be1c44c7bee548e9405e078bc41392e2f0f5 befcc1700ad796a24b8d0fbd3a621b2431541e6f56fd92bbec221ab29febb92a",
            "o71 surround8_4 48000 8 1 audio 320000 vbr 10 20000 auto auto 0 0 0 0 43b9bbe3e652bd804caf9bdcce6fe377580465f9d2ffd34ccccaef762a8c1cdd eaaaada5b9ce1f978bf33ffb52074b285b82a2a194b655ceb31a82d906c4dbda 2a3b4a63d67710f0f25f7ae2b80388efe4bea4b4f4adca43f43a52093412f397",
            "o71_40ms surround8_4 48000 8 1 audio 192000 cvbr 10 40000 auto auto 0 0 0 0 a8f0822657c8e6ae143d71a0173dd2c9965bb393905b7d598a558b827dbebd81 33fabeaae8063ba2281eb0dfc4a856c900f65b06c1000e279178cf95e8724264 262c6fb93af155ab109ba1758169d9b156605aae8a7acef2e18546ae06b800b6",
    };

    static Stream<String> goldens() {
        return Stream.of(GOLDENS);
    }

    /** Decodes a track through {@link OpusDecoder#decode(Packet, java.util.function.Consumer)} into planes. */
    static float[][] decodeTrack(byte[] head, List<byte[]> packets, OpusDecoderOptions options) {
        var decoder = new OpusDecoder(OpusProjectionGoldenTest.track(head, options.sampleRate()), options);
        var channels = decoder.layout().channels();
        var planes = new ArrayList<float[]>();
        var total = new int[1];
        for (var data : packets) {
            decoder.decode(new Packet(0, data, 0, 0, 0, true), buffer -> {
                var block = new float[channels * buffer.frames()];
                for (var c = 0; c < channels; c++) {
                    System.arraycopy(buffer.plane(c), 0, block, c * buffer.frames(), buffer.frames());
                }
                planes.add(block);
                total[0] += buffer.frames();
            });
        }
        var out = new float[channels][total[0]];
        var position = 0;
        for (var block : planes) {
            var frames = block.length / channels;
            for (var c = 0; c < channels; c++) {
                System.arraycopy(block, c * frames, out[c], position, frames);
            }
            position += frames;
        }
        return out;
    }

    static List<byte[]> encode(String line, byte[][] head) {
        var f = line.split(" ");
        var bitstream = new ByteArrayOutputStream();
        var packets = OpusProjectionGoldenTest.encode(f, 6 * 1275 + 12 + 2, bitstream);
        if (f.length > 16) {
            assertEquals(f[16], OpusProjectionGoldenTest.sha256(bitstream.toByteArray()), "bitstream of " + f[0]);
        }
        head[0] = new OpusEncoder(OpusMultistreamGoldenTest.options(f)).codecConfig().orElseThrow();
        return packets;
    }

    private static String hash(float[]... planes) {
        var frames = planes[0].length;
        var bytes = ByteBuffer.allocate(4 * frames * planes.length).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < frames; i++) {
            for (var plane : planes) {
                bytes.putFloat(plane[i]);
            }
        }
        return OpusProjectionGoldenTest.sha256(bytes.array());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldens")
    @DisplayName("mixes Vorbis-order surround to stereo like opusfile's op_read_float_stereo")
    void opusfile(String line) {
        var f = line.split(" ");
        var head = new byte[1][];
        var packets = encode(line, head);
        var stereo = decodeTrack(head[0], packets, OpusDecoderOptions.builder().channels(2).build());
        assertEquals(f[18], hash(stereo[0]), "left channel of " + f[0]);
        if (f[3].equals("4")) {
            // opusfile 0.12 gives the rear-right channel 0.336 in the right row; the decoder uses 0.366.
            assertNotEquals(f[17], hash(stereo));
            var full = decodeTrack(head[0], packets, OpusDecoderOptions.defaults());
            for (var i = 0; i < full[0].length; i++) {
                var right = 0.0f;
                right += 0.4226f * full[1][i];
                right += 0.2114f * full[2][i];
                right += 0.366f * full[3][i];
                assertEquals(right, stereo[1][i], "right sample " + i);
            }
        } else {
            assertEquals(f[17], hash(stereo), "stereo downmix of " + f[0]);
        }
    }

    @Test
    @DisplayName("uses the RFC 7845 coefficients to within 5e-4")
    void rfc7845() {
        double s = Math.sqrt(0.5);
        double h = Math.sqrt(3) / 2;
        double[][][] exact = {
                {{1, s, 0}},
                {{1, 0, h, 0.5}},
                {{1, s, 0, h, 0.5}},
                {{1, s, 0, h, 0.5, s}},
                {{1, s, 0, h, 0.5, h / Math.sqrt(2), s}},
                {{1, s, 0, h, 0.5, h, 0.5, s}}
        };
        double[] norms = {1 / (1 + s), 1 / (1 + h + 0.5), 2 / (1 + s + h + 0.5), 2 / (1 + s + h + 0.5 + s),
                2 / (1 + s + h + 0.5 + h / Math.sqrt(2) + s), 2 / (2 + 2 * s + 2 * h)};
        int[][] mirror = {{2, 1, 0}, {1, 0, 3, 2}, {2, 1, 0, 4, 3}, {2, 1, 0, 4, 3, 5}, {2, 1, 0, 4, 3, 5, 6},
                {2, 1, 0, 4, 3, 6, 5, 7}};
        for (var channels = 3; channels <= 8; channels++) {
            var matrix = OpusDownmix.matrix(1, channels, 2);
            var row = exact[channels - 3][0];
            for (var c = 0; c < channels; c++) {
                var expected = row[c] * norms[channels - 3];
                assertEquals(expected, matrix[0][c], 5e-4, "left coefficient " + c + " of " + channels);
                assertEquals(expected, matrix[1][mirror[channels - 3][c]], 5e-4, "right coefficient of " + channels);
            }
        }
    }

    @Nested
    @DisplayName("ambisonics")
    class Ambisonics {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "p4_128 amb4_4 48000 4 3 audio 128000 vbr 10 20000 auto auto 0 0 0 0",
                "p6_160 amb6_4 48000 6 3 audio 160000 vbr 10 20000 auto auto 0 0 0 0",
                "p9_192 amb9_2 48000 9 3 audio 192000 vbr 10 20000 auto auto 0 0 0 0",
                "a6 surround6_4_nolfe 48000 6 2 audio 160000 vbr 10 20000 auto auto 0 0 0 0",
                "a4 surround4_4 48000 4 2 audio 128000 vbr 10 20000 auto auto 0 0 0 0"})
        @DisplayName("mix to stereo and mono by RFC 8486")
        void downmix(String line) {
            var head = new byte[1][];
            var packets = encode(line, head);
            var full = decodeTrack(head[0], packets, OpusDecoderOptions.defaults());
            var stereo = decodeTrack(head[0], packets, OpusDecoderOptions.builder().channels(2).build());
            var mono = decodeTrack(head[0], packets, OpusDecoderOptions.builder().channels(1).build());
            var channels = full.length;
            var nonDiegetic = channels == 6 || channels == 11;
            assertEquals(ChannelLayout.STEREO, new OpusDecoder(OpusProjectionGoldenTest.track(head[0], 48000),
                    OpusDecoderOptions.builder().channels(2).build()).layout());
            for (var i = 0; i < full[0].length; i++) {
                float left = 0;
                float right = 0;
                float single = 0;
                if (nonDiegetic) {
                    left += 0.25f * full[0][i];
                    left += 0.25f * full[1][i];
                    left += 0.5f * full[channels - 2][i];
                    right += 0.25f * full[0][i];
                    right += -0.25f * full[1][i];
                    right += 0.5f * full[channels - 1][i];
                    single += 0.25f * full[0][i];
                    single += 0.25f * full[channels - 2][i];
                    single += 0.25f * full[channels - 1][i];
                } else {
                    left += 0.5f * full[0][i];
                    left += 0.5f * full[1][i];
                    right += 0.5f * full[0][i];
                    right += -0.5f * full[1][i];
                    single = full[0][i];
                }
                assertEquals(left, stereo[0][i], "left sample " + i);
                assertEquals(right, stereo[1][i], "right sample " + i);
                assertEquals(single, mono[0][i], "mono sample " + i);
            }
        }
    }

    @Test
    @DisplayName("mixes 5.1 to mono as the average of the stereo rows and discrete channels by index")
    void monoAndDiscrete() {
        var head = new byte[1][];
        var packets = encode("o51 surround6_4 48000 6 1 audio 256000 vbr 10 20000 auto auto 0 0 0 0", head);
        var coded = decodeTrack(head[0], packets, OpusDecoderOptions.defaults());
        var mono = decodeTrack(head[0], packets, OpusDecoderOptions.builder().channels(1).build());
        var matrix = OpusDownmix.matrix(1, 6, 2);
        // The full decode is in WAVE order: FL FR FC LFE BL BR; the matrix is in Vorbis order.
        int[] wave = {0, 2, 1, 4, 5, 3};
        for (var i = 0; i < mono[0].length; i++) {
            float expected = 0;
            for (var v = 0; v < 6; v++) {
                var c = 0.5f * (matrix[0][v] + matrix[1][v]);
                if (c != 0) {
                    expected += c * coded[wave[v]][i];
                }
            }
            assertEquals(expected, mono[0][i], "mono sample " + i);
        }
        var discrete = new byte[1][];
        var discretePackets = encode("d6 surround6_4 48000 6 255 audio 192000 vbr 10 20000 auto auto 0 0 0 0",
                discrete);
        var all = decodeTrack(discrete[0], discretePackets, OpusDecoderOptions.defaults());
        var two = decodeTrack(discrete[0], discretePackets, OpusDecoderOptions.builder().channels(2).build());
        var one = decodeTrack(discrete[0], discretePackets, OpusDecoderOptions.builder().channels(1).build());
        for (var i = 0; i < all[0].length; i++) {
            assertEquals(all[0][i], two[0][i]);
            assertEquals(all[1][i], two[1][i]);
            float expected = 0;
            expected += 0.5f * all[0][i];
            expected += 0.5f * all[1][i];
            assertEquals(expected, one[0][i]);
        }
    }

    @ParameterizedTest(name = "{0} channels")
    @ValueSource(ints = {1, 2})
    @DisplayName("gives the per-packet methods the downmix of the track decoder, rounded for 16-bit output")
    void perPacket(int target) {
        for (var line : new String[]{"o51 surround6_4 48000 6 1 audio 256000 vbr 10 20000 auto auto 0 0 0 0",
                "p9_192 amb9_2 48000 9 3 audio 192000 vbr 10 20000 auto auto 0 0 0 0",
                "p6_160 amb6_4 48000 6 3 audio 160000 vbr 10 20000 auto auto 0 0 0 0",
                "a6 surround6_4_nolfe 48000 6 2 audio 160000 vbr 10 20000 auto auto 0 0 0 0"}) {
            var head = new byte[1][];
            var packets = encode(line, head);
            var options = OpusDecoderOptions.builder().channels(target).build();
            var track = decodeTrack(head[0], packets, options);
            assertEquals(target, track.length, line);
            var floats = new OpusDecoder(OpusProjectionGoldenTest.track(head[0], 48000), options);
            var preSkip = OpusHead.parse(head[0], MediaLimits.DEFAULT).preSkip();
            var pcm = new float[5760 * target];
            var position = -preSkip;
            for (var p : packets) {
                var samples = floats.decode(p, 0, p.length, pcm, 0, 5760);
                for (var i = 0; i < samples; i++) {
                    for (var c = 0; position + i >= 0 && c < target; c++) {
                        assertEquals(track[c][position + i], pcm[target * i + c], line);
                    }
                }
                position += samples;
            }
            // 16-bit output soft-clips each stream before mixing, which leaves a stream within range unchanged.
            var shorts = new OpusDecoder(OpusProjectionGoldenTest.track(head[0], 48000), options);
            var reference = new OpusDecoder(OpusProjectionGoldenTest.track(head[0], 48000), options);
            var s16 = new short[5760 * target];
            for (var p : packets.subList(0, 20)) {
                var samples = shorts.decode(p, 0, p.length, s16, 0, 5760);
                reference.decode(p, 0, p.length, pcm, 0, 5760);
                for (var i = 0; i < target * samples; i++) {
                    assertEquals(MathOps.float2int16(pcm[i]), s16[i], "sample " + i + " of " + line);
                }
            }
            // Concealment and FEC rebuilding mix the concealed streams the same way in both formats.
            var lossFloats = new OpusDecoder(OpusProjectionGoldenTest.track(head[0], 48000), options);
            var lossShorts = new OpusDecoder(OpusProjectionGoldenTest.track(head[0], 48000), options);
            var full = new OpusDecoder(OpusProjectionGoldenTest.track(head[0], 48000));
            var fullPcm = new float[5760 * full.layout().channels()];
            for (var n = 0; n < 12; n++) {
                var p = packets.get(n);
                int a;
                int b;
                if (n % 4 == 2) {
                    var next = packets.get(n + 1);
                    a = lossFloats.recover(next, 0, next.length, pcm, 0, 960);
                    b = lossShorts.recover(next, 0, next.length, s16, 0, 960);
                    full.recover(next, 0, next.length, fullPcm, 0, 960);
                } else if (n % 4 == 3) {
                    a = lossFloats.conceal(pcm, 0, 960);
                    b = lossShorts.conceal(s16, 0, 960);
                    full.conceal(fullPcm, 0, 960);
                } else {
                    a = lossFloats.decode(p, 0, p.length, pcm, 0, 5760);
                    b = lossShorts.decode(p, 0, p.length, s16, 0, 5760);
                    full.decode(p, 0, p.length, fullPcm, 0, 5760);
                }
                assertEquals(a, b);
                var anySignal = false;
                for (var i = 0; i < target * a; i++) {
                    assertEquals(MathOps.float2int16(pcm[i]), s16[i], "lossy sample " + i + " of " + line);
                    anySignal |= pcm[i] != 0;
                }
                var fullSignal = false;
                for (var i = 0; i < a * full.layout().channels(); i++) {
                    fullSignal |= fullPcm[i] != 0;
                }
                assertEquals(fullSignal, anySignal, "packet " + n + " of " + line);
            }
        }
    }
}

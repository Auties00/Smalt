package com.github.auties00.smalt.video.hevc.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the HEVC encoder over whole sequences: the timestamps it stamps (missing, repeated and irregular ones),
 * where it places IDR pictures (requests, periods and scene cuts while B pictures are held), its counters, the
 * buffering verifier of its rate control, the conversion of input layouts and depths, and the decoded picture
 * hashes recomputed from ITU-T H.265 D.3.20 independently of the decoder's own hash code (ffmpeg checks only MD5).
 */
@DisplayName("HEVC encoder sequences")
class HevcEncoderSequenceTest {
    private static HevcEncoderOptions.Builder quality(int qp) {
        return HevcEncoderOptions.builder().rateControl(HevcRateControl.CONSTANT_QUALITY).quality(qp)
                .pictureHash(HevcPictureHash.MD5);
    }

    /** Encodes pictures with the given timestamps, flushing at the end. */
    private static HevcEncoderHarness.Encoded encode(int width, int height, HevcEncoderOptions options,
                                                     List<? extends Picture> pictures, long[] pts) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new HevcEncoder(width, height, options)) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new VideoFrame(pictures.get(i), pts[i], 0, false), packets::add);
            }
            encoder.flush(packets::add);
            return new HevcEncoderHarness.Encoded(packets, encoder.codecConfig().orElse(null), options);
        }
    }

    private static void assertDecodingOrder(List<Packet> packets) {
        var previous = Long.MIN_VALUE;
        for (var i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            assertTrue(packet.dts() <= packet.pts(), "packet " + i + ": dts " + packet.dts() + " after pts "
                    + packet.pts());
            assertTrue(packet.dts() > previous, "packet " + i + ": dts " + packet.dts() + " after " + previous);
            previous = packet.dts();
        }
    }

    @Nested
    @DisplayName("timestamps")
    class Timestamps {
        @Test
        @DisplayName("a frame without a timestamp is placed one nominal frame after the previous one")
        void missing() {
            var pictures = HevcEncoderHarness.sequence(64, 48, 4, 3);
            var none = Packet.NO_TIMESTAMP;
            var encoded = encode(64, 48, quality(30).bFrames(0).build(), pictures, new long[]{none, none, 9000, none});
            assertArrayEquals(new long[]{0, 3000, 9000, 12000},
                    encoded.packets().stream().mapToLong(Packet::pts).toArray());
            assertEquals(4, HevcEncoderHarness.decode(encoded).size());
        }

        @Test
        @DisplayName("real-time mode accepts timestamps that do not increase and stamps them one unit apart")
        void repeated() {
            var options = HevcEncoderOptions.forRealtime().toBuilder().pictureHash(HevcPictureHash.MD5).build();
            var pictures = HevcEncoderHarness.sequence(64, 48, 4, 5);
            var encoded = encode(64, 48, options, pictures, new long[]{500, 500, 400, 501});
            assertArrayEquals(new long[]{500, 501, 502, 503},
                    encoded.packets().stream().mapToLong(Packet::pts).toArray());
            assertDecodingOrder(encoded.packets());
            assertEquals(4, HevcEncoderHarness.decode(encoded).size());
        }

        @ParameterizedTest(name = "{0} B pictures")
        @ValueSource(ints = {1, 3, 7})
        @DisplayName("irregular timestamps keep every decoding timestamp increasing and before its presentation")
        void irregular(int bFrames) {
            var gaps = new long[]{3000, 90_000, 1, 45_000, 3000, 300_000, 2, 3000, 60_000, 7, 3000, 120_000, 5, 3000,
                    3000, 900_000, 1, 1};
            var pts = new long[gaps.length];
            for (var i = 1; i < pts.length; i++) {
                pts[i] = pts[i - 1] + gaps[i];
            }
            var pictures = HevcEncoderHarness.sequence(64, 48, pts.length, 7);
            var encoded = encode(64, 48, quality(32).bFrames(bFrames).keyframeInterval(100).build(), pictures, pts);
            assertDecodingOrder(encoded.packets());
            var decoded = HevcEncoderHarness.decode(encoded);
            assertArrayEquals(pts, decoded.stream().mapToLong(VideoFrame::pts).toArray(), "output order");
        }
    }

    @Nested
    @DisplayName("IDR pictures")
    class Keyframes {
        /** Returns the presentation timestamps of the key frames, in time base units. */
        private static List<Long> keyframes(List<Packet> packets) {
            return packets.stream().filter(Packet::keyframe).map(Packet::pts).sorted().toList();
        }

        @Test
        @DisplayName("a request while B pictures are held codes them first and makes the next frame an IDR picture")
        void requestWithHeldFrames() {
            var options = quality(30).bFrames(3).noPeriodicKeyframes().build();
            var pictures = HevcEncoderHarness.sequence(64, 48, 12, 9);
            var packets = new ArrayList<Packet>();
            byte[] config;
            try (var encoder = new HevcEncoder(64, 48, options)) {
                config = encoder.codecConfig().orElseThrow();
                for (var i = 0; i < 12; i++) {
                    if (i == 6) {
                        encoder.requestKeyframe();
                    }
                    encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
                }
                encoder.flush(packets::add);
                assertEquals(2, encoder.stats().keyframesEncoded());
            }
            assertEquals(List.of(0L, 18_000L), keyframes(packets));
            assertDecodingOrder(packets);
            var encoded = new HevcEncoderHarness.Encoded(packets, config, options);
            var decoded = HevcEncoderHarness.decode(encoded);
            for (var i = 0; i < 12; i++) {
                assertEquals(i * 3000L, decoded.get(i).pts(), "output order");
            }
            var reference = HevcEncoderHarness.ffmpeg("request-held", encoded.annexB(), "yuv420p");
            assertArrayEquals(HevcEncoderHarness.raw(decoded), reference, "ffmpeg's decode differs");
        }

        @ParameterizedTest(name = "every {0} frames")
        @ValueSource(ints = {1, 2, 5})
        @DisplayName("periodic IDR pictures cut the groups of B pictures and every picture still decodes in order")
        void periodicWithBFrames(int interval) {
            var options = quality(30).bFrames(3).keyframeInterval(interval).build();
            var pictures = HevcEncoderHarness.sequence(64, 48, 13, 11);
            var encoded = HevcEncoderHarness.encode(64, 48, options, pictures);
            var expected = new ArrayList<Long>();
            for (var i = 0; i < 13; i += interval) {
                expected.add(i * 3000L);
            }
            assertEquals(expected, keyframes(encoded.packets()));
            assertDecodingOrder(encoded.packets());
            var decoded = HevcEncoderHarness.decode(encoded);
            for (var i = 0; i < 13; i++) {
                assertEquals(i * 3000L, decoded.get(i).pts(), "output order");
            }
            var reference = HevcEncoderHarness.ffmpeg("periodic-" + interval, encoded.annexB(), "yuv420p");
            assertArrayEquals(HevcEncoderHarness.raw(decoded), reference, "ffmpeg's decode differs");
        }

        @ParameterizedTest(name = "{0} B pictures")
        @ValueSource(ints = {0, 3})
        @DisplayName("a scene cut starts an IDR picture and continuous content does not")
        void sceneCut(int bFrames) {
            // still content with a little noise in every frame, inverted from frame 12 on
            var base = (YuvPicture) HevcEncoderHarness.picture(96, 64, 0, 13, 8);
            var pictures = new ArrayList<Picture>();
            for (var i = 0; i < 20; i++) {
                var random = new java.util.Random(i);
                var picture = YuvPicture.allocate(96, 64, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
                for (var j = 0; j < 64; j++) {
                    for (var x = 0; x < 96; x++) {
                        var value = (base.y()[j * base.yStride() + x] & 0xFF) + random.nextInt(3) - 1;
                        picture.y()[j * picture.yStride() + x] = (byte) Math.clamp(i < 12 ? value : 255 - value, 0, 255);
                    }
                }
                System.arraycopy(base.u(), 0, picture.u(), 0, picture.u().length);
                System.arraycopy(base.v(), 0, picture.v(), 0, picture.v().length);
                pictures.add(picture);
            }
            var encoded = HevcEncoderHarness.encode(96, 64, quality(30).bFrames(bFrames).noPeriodicKeyframes().build(),
                    pictures);
            assertEquals(List.of(0L, 36_000L), keyframes(encoded.packets()));
            assertEquals(20, HevcEncoderHarness.decode(encoded).size());
        }
    }

    @Test
    @DisplayName("the counters add up to the packets produced")
    void stats() {
        var options = quality(30).bFrames(3).build();
        var pictures = HevcEncoderHarness.sequence(64, 48, 7, 15);
        var packets = new ArrayList<Packet>();
        try (var encoder = new HevcEncoder(64, 48, options)) {
            for (var i = 0; i < 7; i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
            }
            assertEquals(packets.size(), encoder.stats().framesEncoded(), "frames held for B pictures are not counted");
            encoder.flush(packets::add);
            var stats = encoder.stats();
            assertEquals(7, stats.framesEncoded());
            assertEquals(packets.stream().mapToLong(Packet::size).sum(), stats.bytesEncoded());
            assertEquals(1, stats.keyframesEncoded());
            assertEquals(0, stats.keyframeRequests());
        }
    }

    @Test
    @DisplayName("frames given after a flush continue the stream")
    void encodeAfterFlush() {
        var options = quality(30).bFrames(3).build();
        var pictures = HevcEncoderHarness.sequence(64, 48, 10, 17);
        var packets = new ArrayList<Packet>();
        byte[] config;
        try (var encoder = new HevcEncoder(64, 48, options)) {
            config = encoder.codecConfig().orElseThrow();
            for (var i = 0; i < 10; i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
                if (i == 5) {
                    encoder.flush(packets::add);
                    assertEquals(6, packets.size(), "the flush codes every held frame");
                }
            }
            encoder.flush(packets::add);
            encoder.flush(packets::add);
        }
        assertEquals(10, packets.size());
        assertDecodingOrder(packets);
        var decoded = HevcEncoderHarness.decode(new HevcEncoderHarness.Encoded(packets, config, options));
        for (var i = 0; i < 10; i++) {
            assertEquals(i * 3000L, decoded.get(i).pts(), "output order");
        }
    }

    @Test
    @DisplayName("a still picture encoder has no target bitrate")
    void stillHasNoBitrate() {
        var options = HevcEncoderOptions.forStillPicture().toBuilder().rateControl(HevcRateControl.AVERAGE_BITRATE)
                .build();
        try (var encoder = new HevcEncoder(64, 48, options)) {
            assertThrows(IllegalStateException.class, () -> encoder.setTargetBitrate(100_000));
        }
    }

    @Nested
    @DisplayName("rate control")
    class RateControl {
        private static final int FPS = 30;

        /** Returns the lowest fullness of a buffering verifier that starts full, negative when it underflowed. */
        private static double lowestFullness(List<Packet> packets, double size, double fillRate) {
            var fill = size;
            var lowest = size;
            for (var packet : packets) {
                fill -= packet.size() * 8.0;
                lowest = Math.min(lowest, fill);
                fill = Math.min(size, fill + fillRate / FPS);
            }
            return lowest;
        }

        static Stream<Arguments> modes() {
            return Stream.of(
                    Arguments.of(HevcRateControl.AVERAGE_BITRATE, 0, 200_000),
                    Arguments.of(HevcRateControl.AVERAGE_BITRATE, 3, 120_000),
                    Arguments.of(HevcRateControl.CONSTANT_BITRATE, 0, 150_000));
        }

        @ParameterizedTest(name = "{0} with {1} B pictures at {2} bit/s")
        @MethodSource("modes")
        @DisplayName("ten seconds land within 10% of the target and never underflow the buffering verifier")
        void tenSeconds(HevcRateControl mode, int bFrames, int bitrate) {
            var options = HevcEncoderOptions.builder().rateControl(mode).bitrate(bitrate).bFrames(bFrames)
                    .speed(HevcEncoderSpeed.FASTEST).build();
            var pictures = HevcEncoderHarness.sequence(160, 96, 10 * FPS, 19);
            var encoded = HevcEncoderHarness.encode(160, 96, options, pictures);
            var rate = encoded.bytes() * 8.0 / 10;
            assertTrue(Math.abs(rate - bitrate) <= 0.1 * bitrate, "rate " + rate + " for target " + bitrate);
            var peak = mode == HevcRateControl.CONSTANT_BITRATE ? bitrate : 2.0 * bitrate;
            var size = peak * (mode == HevcRateControl.CONSTANT_BITRATE ? 0.5 : 1.0);
            assertTrue(lowestFullness(encoded.packets(), size, peak) >= 0, "buffer underflow");
        }

        @Test
        @DisplayName("a buffer smaller than an intra picture codes pictures again instead of underflowing, and the "
                + "stream still decodes with every hash verified")
        void tightBuffer() {
            var options = HevcEncoderOptions.builder().rateControl(HevcRateControl.AVERAGE_BITRATE).bitrate(200_000)
                    .maxBitrate(200_000).bufferSize(Duration.ofMillis(150)).bFrames(0).speed(HevcEncoderSpeed.FAST)
                    .pictureHash(HevcPictureHash.MD5).build();
            var pictures = HevcEncoderHarness.sequence(160, 96, 60, 21);
            var encoded = HevcEncoderHarness.encode(160, 96, options, pictures);
            assertTrue(lowestFullness(encoded.packets(), 30_000, 200_000) >= 0, "buffer underflow");
            var decoded = HevcEncoderHarness.decode(encoded);
            assertEquals(60, decoded.size(), "decoded frames");
            var reference = HevcEncoderHarness.ffmpeg("tight-buffer", encoded.annexB(), "yuv420p");
            assertArrayEquals(HevcEncoderHarness.raw(decoded), reference, "ffmpeg's decode differs");
        }
    }

    @Nested
    @DisplayName("input conversion")
    class Input {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ChromaFormat.class, names = {"YUV422", "YUV444"})
        @DisplayName("other chroma layouts are resampled to 4:2:0")
        void chromaLayouts(ChromaFormat format) {
            var source = (YuvPicture) HevcEncoderHarness.picture(64, 48, 0, 23, 8);
            var picture = YuvPicture.allocate(64, 48, format, false, ColorInfo.BT709_LIMITED);
            for (var y = 0; y < 48; y++) {
                System.arraycopy(source.y(), y * source.yStride(), picture.y(), y * picture.yStride(), 64);
            }
            for (var y = 0; y < picture.chromaHeight(); y++) {
                for (var x = 0; x < picture.chromaWidth(); x++) {
                    var sy = y * source.chromaHeight() / picture.chromaHeight();
                    var sx = x * source.chromaWidth() / picture.chromaWidth();
                    picture.u()[y * picture.uvStride() + x] = source.u()[sy * source.uvStride() + sx];
                    picture.v()[y * picture.uvStride() + x] = source.v()[sy * source.uvStride() + sx];
                }
            }
            var encoded = HevcEncoderHarness.encode(64, 48, quality(4).bFrames(0).build(), List.of(picture));
            var decoded = (YuvPicture) HevcEncoderHarness.decode(encoded).getFirst().picture();
            assertTrue(HevcEncoderHarness.psnr(decoded, source) > 40, "luma");
            var sse = 0.0;
            for (var y = 0; y < 24; y++) {
                for (var x = 0; x < 32; x++) {
                    var e = (decoded.u()[y * decoded.uvStride() + x] & 0xFF) - (source.u()[y * source.uvStride() + x] & 0xFF);
                    sse += e * e;
                }
            }
            assertTrue(sse / (24 * 32) < 12, "chroma mean squared error " + sse / (24 * 32));
        }

        @Test
        @DisplayName("10-bit input is scaled to an 8-bit stream")
        void deeperInput() {
            var source = (Yuv16Picture) HevcEncoderHarness.picture(64, 48, 0, 25, 10);
            var encoded = HevcEncoderHarness.encode(64, 48, quality(4).bFrames(0).build(), List.of(source));
            var decoded = (YuvPicture) HevcEncoderHarness.decode(encoded).getFirst().picture();
            var sse = 0.0;
            for (var y = 0; y < 48; y++) {
                for (var x = 0; x < 64; x++) {
                    var e = (decoded.y()[y * decoded.yStride() + x] & 0xFF) - (source.y()[y * source.yStride() + x] >> 2);
                    sse += e * e;
                }
            }
            assertTrue(sse / (64 * 48) < 2, "mean squared error " + sse / (64 * 48));
        }
    }

    @Nested
    @DisplayName("decoded picture hashes")
    class Hashes {
        static Stream<Arguments> kinds() {
            var arguments = new ArrayList<Arguments>();
            for (var hash : List.of(HevcPictureHash.MD5, HevcPictureHash.CRC, HevcPictureHash.CHECKSUM)) {
                for (var depth : new int[]{8, 10}) {
                    arguments.add(Arguments.of(hash, depth));
                }
            }
            return arguments.stream();
        }

        @ParameterizedTest(name = "{0} at {1} bits")
        @MethodSource("kinds")
        @DisplayName("every SEI hash equals the hash of ITU-T H.265 D.3.20 recomputed from the decoded picture")
        void recomputed(HevcPictureHash hash, int depth) {
            // a size of whole coding blocks, so the decoded output is the whole picture the hash covers
            var width = 64;
            var height = 48;
            var options = quality(30).bFrames(0).pictureHash(hash).packetFormat(HevcPacketFormat.ANNEX_B);
            if (depth == 10) {
                options.profile(HevcProfile.MAIN_10).bitDepth(10);
            }
            var pictures = HevcEncoderHarness.sequence(width, height, 3, 27, depth);
            var encoded = HevcEncoderHarness.encode(width, height, options.build(), pictures);
            var decoded = HevcEncoderHarness.decode(encoded);
            assertEquals(3, decoded.size());
            var type = switch (hash) {
                case MD5 -> 0;
                case CRC -> 1;
                default -> 2;
            };
            for (var i = 0; i < 3; i++) {
                var sei = suffixSei(encoded.packets().get(i).data());
                // payloadType 132, payloadSize, hash_type
                assertEquals(132, sei[0] & 0xFF, "payloadType");
                assertEquals(type, sei[2] & 0xFF, "hash_type");
                var expected = new ByteArrayOutputStream();
                for (var c = 0; c < 3; c++) {
                    expected.writeBytes(hash(decoded.get(i).picture(), c, depth, type));
                }
                var expectedBytes = expected.toByteArray();
                assertEquals(expectedBytes.length, (sei[1] & 0xFF) - 1, "payloadSize");
                assertArrayEquals(expectedBytes, Arrays.copyOfRange(sei, 3, 3 + expectedBytes.length), "picture " + i);
            }
        }

        /** Returns the unescaped payload of the suffix SEI NAL unit of an Annex B access unit. */
        private static byte[] suffixSei(byte[] data) {
            for (var i = 0; i + 5 < data.length; i++) {
                if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1
                        && ((data[i + 4] >> 1) & 0x3F) == 40) {
                    var end = i + 6;
                    while (end + 3 < data.length && !(data[end] == 0 && data[end + 1] == 0 && data[end + 2] == 0
                            && data[end + 3] == 1)) {
                        end++;
                    }
                    if (end + 3 >= data.length) {
                        end = data.length;
                    }
                    var out = new ByteArrayOutputStream();
                    var zeros = 0;
                    for (var j = i + 6; j < end; j++) {
                        var value = data[j] & 0xFF;
                        if (zeros >= 2 && value == 3) {
                            zeros = 0;
                            continue;
                        }
                        out.write(value);
                        zeros = value == 0 ? zeros + 1 : 0;
                    }
                    return out.toByteArray();
                }
            }
            throw new AssertionError("no suffix SEI");
        }

        /** Returns the samples of a component, row by row. */
        private static int[] samples(Picture picture, int component) {
            return switch (picture) {
                case YuvPicture p -> {
                    var plane = component == 0 ? p.y() : component == 1 ? p.u() : p.v();
                    var stride = component == 0 ? p.yStride() : p.uvStride();
                    var w = component == 0 ? p.width() : p.chromaWidth();
                    var h = component == 0 ? p.height() : p.chromaHeight();
                    var out = new int[w * h];
                    for (var y = 0; y < h; y++) {
                        for (var x = 0; x < w; x++) {
                            out[y * w + x] = plane[y * stride + x] & 0xFF;
                        }
                    }
                    yield out;
                }
                case Yuv16Picture p -> {
                    var plane = component == 0 ? p.y() : component == 1 ? p.u() : p.v();
                    var stride = component == 0 ? p.yStride() : p.uvStride();
                    var w = component == 0 ? p.width() : p.chromaWidth();
                    var h = component == 0 ? p.height() : p.chromaHeight();
                    var out = new int[w * h];
                    for (var y = 0; y < h; y++) {
                        for (var x = 0; x < w; x++) {
                            out[y * w + x] = plane[y * stride + x] & 0xFFFF;
                        }
                    }
                    yield out;
                }
                default -> throw new IllegalStateException("unexpected picture " + picture);
            };
        }

        /** Computes the hash of one component as ITU-T H.265 D.3.20 specifies it. */
        private static byte[] hash(Picture picture, int component, int depth, int type) {
            var values = samples(picture, component);
            var width = component == 0 ? picture.width() : (picture.width() + 1) / 2;
            var data = new ByteArrayOutputStream();
            for (var value : values) {
                data.write(value & 0xFF);
                if (depth > 8) {
                    data.write(value >> 8);
                }
            }
            var pictureData = data.toByteArray();
            switch (type) {
                case 0 -> {
                    try {
                        return MessageDigest.getInstance("MD5").digest(pictureData);
                    } catch (NoSuchAlgorithmException exception) {
                        throw new AssertionError(exception);
                    }
                }
                case 1 -> {
                    var crc = 0xFFFF;
                    var padded = Arrays.copyOf(pictureData, pictureData.length + 2);
                    for (var bit = 0; bit < padded.length * 8; bit++) {
                        var dataByte = padded[bit >> 3] & 0xFF;
                        var msb = (crc >> 15) & 1;
                        var bitValue = (dataByte >> (7 - (bit & 7))) & 1;
                        crc = (((crc << 1) + bitValue) & 0xFFFF) ^ (msb * 0x1021);
                    }
                    return new byte[]{(byte) (crc >> 8), (byte) crc};
                }
                default -> {
                    var sum = 0L;
                    for (var i = 0; i < values.length; i++) {
                        var x = i % width;
                        var y = i / width;
                        var mask = (x & 0xFF) ^ (y & 0xFF) ^ (x >> 8) ^ (y >> 8);
                        sum = (sum + ((values[i] & 0xFF) ^ mask)) & 0xFFFF_FFFFL;
                        if (depth > 8) {
                            sum = (sum + ((values[i] >> 8) ^ mask)) & 0xFFFF_FFFFL;
                        }
                    }
                    return new byte[]{(byte) (sum >> 24), (byte) (sum >> 16), (byte) (sum >> 8), (byte) sum};
                }
            }
        }
    }

    @Test
    @DisplayName("a frame without a duration gives a packet of one nominal frame")
    void nominalDuration() {
        var options = quality(30).frameRate(new Rational(25, 1)).timeBase(new Rational(1, 25)).bFrames(0).build();
        var pictures = HevcEncoderHarness.sequence(64, 48, 3, 29);
        var encoded = encode(64, 48, options, pictures, new long[]{0, 1, 2});
        assertEquals(3, HevcEncoderHarness.decode(encoded).size());
        for (var packet : encoded.packets()) {
            assertEquals(1, packet.duration(), "one frame of the 1/25 time base");
        }
    }
}

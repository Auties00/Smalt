package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.image.webp.internal.riff.WebpParser;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.vp8.internal.Vp8;
import com.github.auties00.smalt.video.vp8.internal.Vp8KeyFrameSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Feeds mutated key frames and streams (byte flips, truncations, header bit flips, overwritten and
 * spliced runs, corrupted size fields) to every public entry point and checks that each call either
 * succeeds or throws a {@link MediaException}, within a time bound, and that the stream decoder
 * recovers bit-exactly at the next key frame.
 */
@DisplayName("VP8 robustness against hostile input")
class Vp8RobustnessTest {
    static final List<String> VECTORS = List.of("vp80-00-comprehensive-001.ivf", "vp80-00-comprehensive-007.ivf",
            "vp80-03-segmentation-1437.ivf", "vp80-04-partitions-1405.ivf", "vp80-05-sharpness-1438.ivf");

    static byte[] vector(String name) {
        try {
            return Files.readAllBytes(Conformance.fetch(name, Vp8Vectors.BASE_URL + name, Vp8Vectors.SHA256.get(name)));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    static YuvPicture picture(int width, int height, long seed) {
        var random = new Random(seed);
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        var y = picture.y();
        for (var row = 0; row < height; row++) {
            for (var col = 0; col < width; col++) {
                y[row * picture.yStride() + col] = (byte) ((row * 7 + col * 3) ^ random.nextInt(32));
            }
        }
        var chromaWidth = (width + 1) / 2;
        for (var row = 0; row < (height + 1) / 2; row++) {
            for (var col = 0; col < chromaWidth; col++) {
                picture.u()[row * picture.uvStride() + col] = (byte) (row * 5 + random.nextInt(8));
                picture.v()[row * picture.uvStride() + col] = (byte) (col * 5 + random.nextInt(8));
            }
        }
        return picture;
    }

    static Stream<byte[]> keyFrames() {
        var frames = new ArrayList<byte[]>();
        frames.add(Vp8.encodeKeyFrame(picture(1, 1, 1), Vp8KeyFrameSettings.quality(75)));
        frames.add(Vp8.encodeKeyFrame(picture(17, 31, 2), Vp8KeyFrameSettings.quality(90)));
        frames.add(Vp8.encodeKeyFrame(picture(64, 48, 3), Vp8KeyFrameSettings.quality(30)));
        frames.add(Vp8.encodeKeyFrame(picture(100, 75, 4), new Vp8KeyFrameSettings(60, 4, 3, 30, 3, 4, 50)));
        for (var name : VECTORS.subList(0, 3)) {
            frames.add(Ivf.parse(vector(name)).frames().getFirst().data());
        }
        return frames.stream();
    }

    static byte[] mutate(byte[] input, Random random) {
        var data = input.clone();
        switch (random.nextInt(7)) {
            case 0 -> {
                for (var n = 1 + random.nextInt(8); n > 0 && data.length > 0; n--) {
                    data[random.nextInt(data.length)] ^= (byte) (1 + random.nextInt(255));
                }
            }
            case 1 -> data = Arrays.copyOf(data, random.nextInt(data.length + 1));
            case 2 -> {
                var span = Math.min(data.length, 32);
                for (var n = 1 + random.nextInt(4); n > 0 && span > 0; n--) {
                    data[random.nextInt(span)] ^= (byte) (1 << random.nextInt(8));
                }
            }
            case 3 -> {
                if (data.length > 0) {
                    var start = random.nextInt(data.length);
                    var end = Math.min(data.length, start + 1 + random.nextInt(64));
                    var fill = random.nextInt(3);
                    for (var i = start; i < end; i++) {
                        data[i] = (byte) (fill == 0 ? 0 : fill == 1 ? 0xFF : random.nextInt(256));
                    }
                }
            }
            case 4 -> {
                if (data.length > 1) {
                    var from = random.nextInt(data.length);
                    var to = random.nextInt(data.length);
                    var count = Math.min(random.nextInt(128), Math.min(data.length - from, data.length - to));
                    System.arraycopy(input, from, data, to, count);
                }
            }
            case 5 -> {
                var extended = Arrays.copyOf(data, data.length + 1 + random.nextInt(256));
                for (var i = data.length; i < extended.length; i++) {
                    extended[i] = (byte) random.nextInt(256);
                }
                data = extended;
            }
            default -> {
                if (data.length >= 10) {
                    var field = random.nextInt(3);
                    if (field == 0) {
                        var size = random.nextInt(1 << 19);
                        data[0] = (byte) ((data[0] & 0x1F) | (size << 5));
                        data[1] = (byte) (size >> 3);
                        data[2] = (byte) (size >> 11);
                    } else if (field == 1) {
                        data[6] = (byte) random.nextInt(256);
                        data[7] = (byte) random.nextInt(256);
                    } else {
                        data[8] = (byte) random.nextInt(256);
                        data[9] = (byte) random.nextInt(256);
                    }
                }
            }
        }
        return data;
    }

    static void expectMediaException(Runnable call, String label) {
        try {
            call.run();
        } catch (MediaException expected) {
            // Rejecting corrupt input is the contract.
        } catch (Throwable unexpected) {
            throw new AssertionError(label + ": " + unexpected, unexpected);
        }
    }

    @Nested
    @DisplayName("key frame entry points")
    class KeyFrames {
        @ParameterizedTest(name = "seed frame {index}")
        @MethodSource("com.github.auties00.smalt.video.vp8.Vp8RobustnessTest#keyFrames")
        @DisplayName("the header probe and the decoder only throw media exceptions on 1500 mutants")
        void mutants(byte[] frame) {
            var random = new Random(Arrays.hashCode(frame));
            var small = MediaLimits.builder().maxDimension(512).maxPixels(1 << 18).maxFrames(1).maxChannels(4)
                    .maxBytes(1 << 22).build();
            assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
                for (var trial = 0; trial < 1500; trial++) {
                    var data = mutate(frame, random);
                    var label = "mutant " + trial;
                    if (data.length >= 10) {
                        expectMediaException(() -> WebpParser.vp8Dimensions(Arrays.copyOf(data, 10), data.length), label);
                    }
                    expectMediaException(() -> Vp8.decodeKeyFrame(data, 0, data.length, MediaLimits.DEFAULT), label);
                    expectMediaException(() -> Vp8.decodeKeyFrame(data, 0, data.length, small), label + " (small limits)");
                }
            });
        }

        @Test
        @DisplayName("every truncation of a key frame")
        void truncations() {
            var frame = Vp8.encodeKeyFrame(picture(48, 32, 9), new Vp8KeyFrameSettings(80, 4, 4, 40, 0, 2, 50));
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                for (var length = 0; length < frame.length; length++) {
                    var n = length;
                    expectMediaException(() -> Vp8.decodeKeyFrame(frame, 0, n, MediaLimits.DEFAULT), "length " + n);
                }
            });
        }

        @Test
        @DisplayName("the largest frame size is rejected by the limits before allocation")
        void hugeDimensions() {
            var frame = Vp8.encodeKeyFrame(picture(16, 16, 10), Vp8KeyFrameSettings.quality(50));
            frame[6] = (byte) 0xFF;
            frame[7] = (byte) 0x3F;
            frame[8] = (byte) 0xFF;
            frame[9] = (byte) 0x3F;
            assertEquals(16383, WebpParser.vp8Dimensions(Arrays.copyOf(frame, 10), frame.length)[0]);
            assertThrows(MediaException.LimitExceeded.class, () -> Vp8.decodeKeyFrame(frame, 0, frame.length,
                    MediaLimits.builder().maxDimension(16_384).maxPixels(1 << 20).maxFrames(1).maxChannels(4)
                            .maxBytes(1L << 30).build()));
            assertThrows(MediaException.LimitExceeded.class, () -> Vp8.decodeKeyFrame(frame, 0, frame.length,
                    MediaLimits.builder().maxDimension(16_384).maxPixels(1L << 30).maxFrames(1).maxChannels(4)
                            .maxBytes(1 << 20).build()));
        }
    }

    @Nested
    @DisplayName("stream decoder")
    class Streams {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"vp80-00-comprehensive-001.ivf", "vp80-00-comprehensive-007.ivf",
                "vp80-03-segmentation-1437.ivf", "vp80-04-partitions-1405.ivf", "vp80-05-sharpness-1438.ivf"})
        @DisplayName("mutated, dropped and reordered packets only throw media exceptions")
        void mutants(String name) {
            var ivf = Ivf.parse(vector(name));
            var random = new Random(name.hashCode());
            assertTimeoutPreemptively(Duration.ofSeconds(180), () -> {
                for (var trial = 0; trial < 60; trial++) {
                    var packets = new ArrayList<byte[]>();
                    for (var frame : ivf.frames()) {
                        packets.add(frame.data());
                    }
                    for (var n = 1 + random.nextInt(4); n > 0; n--) {
                        var index = random.nextInt(packets.size());
                        switch (random.nextInt(4)) {
                            case 0 -> packets.set(index, mutate(packets.get(index), random));
                            case 1 -> packets.remove(index);
                            case 2 -> packets.add(index, packets.get(random.nextInt(packets.size())));
                            default -> packets.set(index, new byte[random.nextInt(3)]);
                        }
                    }
                    try (var decoder = new Vp8Decoder(Vp8ConformanceTest.track(ivf.width(), ivf.height()))) {
                        for (var i = 0; i < packets.size(); i++) {
                            var packet = new Packet(1, packets.get(i), i, i, 1, false);
                            expectMediaException(() -> decoder.decode(packet, frame -> {
                                var picture = (YuvPicture) frame.picture();
                                if (picture.width() <= 0 || picture.height() <= 0) {
                                    fail("empty picture");
                                }
                            }), name + " trial " + trial + " packet " + i);
                        }
                    }
                }
            });
        }

        @Test
        @DisplayName("recovers bit-exactly at the next key frame after corrupt frames")
        void recovery() {
            var file = Vp8DifferentialTest.encode(96, 64, "2", null, List.of("-g", "6", "-keyint_min", "6", "-b:v", "300k"));
            var ivf = Ivf.parse(readAll(file));
            var clean = decodeAll(ivf.frames().stream().map(Ivf.Frame::data).toList());
            var keys = new ArrayList<Integer>();
            for (var i = 0; i < ivf.frames().size(); i++) {
                if ((ivf.frames().get(i).data()[0] & 1) == 0) {
                    keys.add(i);
                }
            }
            if (keys.size() < 3) {
                fail("expected periodic key frames, found " + keys);
            }
            var random = new Random(7);
            for (var trial = 0; trial < 12; trial++) {
                var packets = new ArrayList<>(ivf.frames().stream().map(Ivf.Frame::data).toList());
                var key = keys.get(1 + random.nextInt(keys.size() - 2));
                var victim = key - 1 - random.nextInt(3);
                var broken = packets.get(victim).clone();
                for (var i = 0; i < broken.length; i += 1 + random.nextInt(4)) {
                    broken[i] ^= (byte) (1 + random.nextInt(255));
                }
                broken[0] |= 1;
                packets.set(victim, broken);
                var outputs = new ArrayList<byte[]>();
                try (var decoder = new Vp8Decoder(Vp8ConformanceTest.track(ivf.width(), ivf.height()))) {
                    for (var i = 0; i < packets.size(); i++) {
                        var index = i;
                        try {
                            decoder.decode(new Packet(1, packets.get(i), i, i, 1, false),
                                    frame -> outputs.add(index >= key ? Ivf.i420((YuvPicture) frame.picture()) : null));
                        } catch (MediaException expected) {
                            // A corrupt frame and the inter frames that depend on it may be rejected.
                        }
                    }
                }
                var tail = outputs.subList(outputs.size() - (clean.size() - key), outputs.size());
                for (var i = 0; i < tail.size(); i++) {
                    if (!Arrays.equals(clean.get(key + i), tail.get(i))) {
                        fail("trial " + trial + ": frame " + (key + i) + " differs after corrupting frame " + victim);
                    }
                }
            }
        }

        @Test
        @DisplayName("rejects an inter frame before any key frame and ignores empty packets")
        void startup() {
            var ivf = Ivf.parse(vector("vp80-00-comprehensive-001.ivf"));
            try (var decoder = new Vp8Decoder(Vp8ConformanceTest.track(ivf.width(), ivf.height()))) {
                var outputs = new ArrayList<Object>();
                decoder.decode(new Packet(1, new byte[0], 0, 0, 1, false), outputs::add);
                assertEquals(0, outputs.size());
                assertThrows(MediaException.Malformed.class,
                        () -> decoder.decode(new Packet(1, ivf.frames().get(1).data(), 1, 1, 1, false), outputs::add));
                assertThrows(MediaException.Malformed.class,
                        () -> decoder.decode(new Packet(1, new byte[]{0x10, 0x02}, 2, 2, 1, false), outputs::add));
                decoder.decode(new Packet(1, ivf.frames().getFirst().data(), 3, 3, 1, false), outputs::add);
                decoder.decode(new Packet(1, ivf.frames().get(1).data(), 4, 4, 1, false), outputs::add);
                assertEquals(2, outputs.size());
            }
        }

        static byte[] readAll(java.nio.file.Path file) {
            try {
                return Files.readAllBytes(file);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        }

        static List<byte[]> decodeAll(List<byte[]> packets) {
            var frames = new ArrayList<byte[]>();
            var first = packets.getFirst();
            var size = WebpParser.vp8Dimensions(Arrays.copyOf(first, 10), first.length);
            try (var decoder = new Vp8Decoder(Vp8ConformanceTest.track(size[0], size[1]))) {
                for (var i = 0; i < packets.size(); i++) {
                    decoder.decode(new Packet(1, packets.get(i), i, i, 1, false), frame -> frames.add(Ivf.i420((YuvPicture) frame.picture())));
                }
            }
            return frames;
        }
    }

    @Test
    @DisplayName("the encoder rejects pictures it cannot represent")
    void encoderArguments() {
        assertThrows(IllegalArgumentException.class, () -> Vp8.encodeKeyFrame(
                YuvPicture.allocate(16, 16, ChromaFormat.YUV444, false, ColorInfo.BT601_LIMITED), Vp8KeyFrameSettings.quality(75)));
        assertThrows(IllegalArgumentException.class, () -> Vp8.encodeKeyFrame(
                YuvPicture.allocate(16384, 1, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED), Vp8KeyFrameSettings.quality(75)));
        var encoded = Vp8.encodeKeyFrame(picture(8, 8, 3), Vp8KeyFrameSettings.quality(75));
        assertEquals(ColorInfo.BT601_LIMITED, Vp8.decodeKeyFrame(encoded, 0, encoded.length, MediaLimits.DEFAULT).color());
    }
}

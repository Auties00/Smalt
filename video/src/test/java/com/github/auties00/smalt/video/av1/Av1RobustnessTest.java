package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Feeds mutated AV1 streams (byte flips, truncations, header bit flips, overwritten and spliced
 * runs, corrupted OBU headers and sizes, dropped, duplicated and emptied packets) to the public
 * decoder and checks that each call either succeeds or throws a {@link MediaException} within a
 * time bound, that frame sizes are checked against the limits before allocation, and that the
 * decoder recovers bit-exactly at the next key frame.
 */
@DisplayName("AV1 robustness against hostile input")
class Av1RobustnessTest {
    private static final String BASE = "https://storage.googleapis.com/aom-test-data/";

    // SHA-256 digests of the libaom test vectors, as pinned by Av1ConformanceTest.
    private static final Map<String, String> VECTORS = Map.of(
            "av1-1-b8-01-size-66x66.ivf", "5f9285cbc417b0457c806df9154a1898f0f9ca09066abfdfd828cb3b2ba0371b",
            "av1-1-b8-04-cdfupdate.ivf", "14a3dbf537b6bf15efc003182d9916d61438c93624a8bd26e6e3ae7eaf33ea82",
            "av1-1-b8-06-mfmv.ivf", "b59bf9586d8546dfda81dfec4ee4e32ceb502c9d22412ab0b63a2abb534a1f14",
            "av1-1-b8-16-intra_only-intrabc-extreme-dv.ivf", "0252716fe7a7c7f1ebb1eb23920a6737f45c10dfba942304bd339ae42b5c576c",
            "av1-1-b8-22-svc-L2T2-2.ivf", "f2af0263e91d460366d4230e83724a91dbcc8b86fb217ca0f417b58df4e2c0ad",
            "av1-1-b8-23-film_grain-50.ivf", "ba3edd82a58414f009e1c821b947ec8de4e0420f33803ca7bfea39af6aeea155",
            "av1-1-b10-00-quantizer-10.ivf", "a93c66b9ad673049b7ad123dfa00c190f70ac3d208de5d49b3653dcf61225f33",
            "av1-1-b10-24-monochrome.ivf", "6a1b0729305a167f10737a5375f0570139f055bcd7916df260b653ab2210adc1");

    // Small enough that a corrupted frame size is rejected rather than allocated.
    private static final MediaLimits SMALL = MediaLimits.builder().maxDimension(1024).maxPixels(1 << 20)
            .maxFrames(1_000).maxChannels(4).maxBytes(1 << 26).build();

    record Clip(VideoTrack track, List<Packet> packets) {
    }

    static Stream<String> vectors() {
        return VECTORS.keySet().stream().sorted();
    }

    static Clip load(Path file) {
        try (var source = MediaSource.of(Files.readAllBytes(file));
             var demuxer = Containers.openDemuxer(source)) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return new Clip(track, packets);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Clip vector(String name) {
        return load(Conformance.fetch("av1-" + name, BASE + name, VECTORS.get(name)));
    }

    /**
     * Returns the offsets of the OBU headers of a temporal unit whose OBUs all carry a size field.
     */
    static List<Integer> obuOffsets(byte[] data) {
        var offsets = new ArrayList<Integer>();
        var pos = 0;
        while (pos < data.length) {
            offsets.add(pos);
            var header = data[pos];
            var sizeAt = pos + 1 + ((header & 4) != 0 ? 1 : 0);
            if ((header & 2) == 0 || sizeAt >= data.length) {
                break;
            }
            long size = 0;
            var shift = 0;
            var at = sizeAt;
            while (at < data.length && shift < 56) {
                var b = data[at++];
                size |= (long) (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            if (size > data.length - at) {
                break;
            }
            pos = at + (int) size;
        }
        return offsets;
    }

    static byte[] mutate(byte[] input, Random random) {
        var data = input.clone();
        if (data.length == 0) {
            return data;
        }
        switch (random.nextInt(8)) {
            case 0 -> {
                for (var n = 1 + random.nextInt(8); n > 0; n--) {
                    data[random.nextInt(data.length)] ^= (byte) (1 + random.nextInt(255));
                }
            }
            case 1 -> data = Arrays.copyOf(data, random.nextInt(data.length + 1));
            case 2 -> {
                var span = Math.min(data.length, 48);
                for (var n = 1 + random.nextInt(4); n > 0; n--) {
                    data[random.nextInt(span)] ^= (byte) (1 << random.nextInt(8));
                }
            }
            case 3 -> {
                var start = random.nextInt(data.length);
                var end = Math.min(data.length, start + 1 + random.nextInt(64));
                var fill = random.nextInt(3);
                for (var i = start; i < end; i++) {
                    data[i] = (byte) (fill == 0 ? 0 : fill == 1 ? 0xFF : random.nextInt(256));
                }
            }
            case 4 -> {
                var from = random.nextInt(data.length);
                var to = random.nextInt(data.length);
                var count = Math.min(random.nextInt(256), Math.min(data.length - from, data.length - to));
                System.arraycopy(input, from, data, to, count);
            }
            case 5 -> {
                var extended = Arrays.copyOf(data, data.length + 1 + random.nextInt(256));
                for (var i = data.length; i < extended.length; i++) {
                    extended[i] = (byte) random.nextInt(256);
                }
                data = extended;
            }
            case 6 -> {
                var offsets = obuOffsets(data);
                var at = offsets.get(random.nextInt(offsets.size()));
                data[at] = (byte) ((data[at] & 0x87) | random.nextInt(16) << 3);
            }
            default -> {
                var offsets = obuOffsets(data);
                var header = offsets.get(random.nextInt(offsets.size()));
                var at = header + 1 + ((data[header] & 4) != 0 ? 1 : 0);
                for (var i = at; i < Math.min(data.length, at + 1 + random.nextInt(5)); i++) {
                    data[i] = (byte) switch (random.nextInt(4)) {
                        case 0 -> 0;
                        case 1 -> 0xFF;
                        case 2 -> 0x80;
                        default -> random.nextInt(256);
                    };
                }
            }
        }
        return data;
    }

    static String decodeQuietly(Av1Decoder decoder, Packet packet, String label) {
        try {
            decoder.decode(packet, frame -> {
                var picture = (YuvPicture) frame.picture();
                if (picture.width() <= 0 || picture.height() <= 0) {
                    fail(label + ": empty picture");
                }
            });
            return "ok";
        } catch (MediaException expected) {
            return expected.getClass().getSimpleName();
        } catch (Throwable unexpected) {
            throw new AssertionError(label + ": " + unexpected, unexpected);
        }
    }

    static String flushQuietly(Av1Decoder decoder, String label) {
        try {
            decoder.flush(frame -> {
                var picture = (YuvPicture) frame.picture();
                if (picture.width() <= 0 || picture.height() <= 0) {
                    fail(label + ": empty picture");
                }
            });
            return "ok";
        } catch (MediaException expected) {
            return expected.getClass().getSimpleName();
        } catch (Throwable unexpected) {
            throw new AssertionError(label + ": " + unexpected, unexpected);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("mutated, dropped, duplicated and emptied packets only throw media exceptions")
    void mutants(String name) {
        var stream = vector(name);
        var random = new Random(name.hashCode());
        var outcomes = new TreeMap<String, Integer>();
        assertTimeoutPreemptively(Duration.ofSeconds(240), () -> {
            for (var trial = 0; trial < 40; trial++) {
                var packets = new ArrayList<>(stream.packets().stream().map(Packet::data).toList());
                for (var n = 1 + random.nextInt(4); n > 0; n--) {
                    var index = random.nextInt(packets.size());
                    switch (random.nextInt(5)) {
                        case 0, 1 -> packets.set(index, mutate(packets.get(index), random));
                        case 2 -> {
                            if (packets.size() > 1) {
                                packets.remove(index);
                            }
                        }
                        case 3 -> packets.add(index, packets.get(random.nextInt(packets.size())));
                        default -> packets.set(index, new byte[random.nextInt(3)]);
                    }
                }
                var limits = trial % 2 == 0 ? MediaLimits.DEFAULT : SMALL;
                var options = Av1DecoderOptions.builder().limits(limits).maxFrameDelay(trial % 4 < 2 ? 1 : 3).build();
                try (var decoder = new Av1Decoder(stream.track(), options)) {
                    for (var i = 0; i < packets.size(); i++) {
                        var packet = new Packet(1, packets.get(i), i, i, 1, false);
                        outcomes.merge(decodeQuietly(decoder, packet, name + " trial " + trial + " packet " + i), 1, Integer::sum);
                    }
                    outcomes.merge(flushQuietly(decoder, name + " trial " + trial + " flush"), 1, Integer::sum);
                }
            }
        });
        assertTrue(outcomes.containsKey("ok"), "no packet decoded: " + outcomes);
    }

    @Test
    @DisplayName("every truncation of a key frame temporal unit")
    void truncations() {
        var stream = vector("av1-1-b8-01-size-66x66.ivf");
        var first = stream.packets().getFirst().data();
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (var length = 0; length < first.length; length++) {
                try (var decoder = new Av1Decoder(stream.track())) {
                    var packet = new Packet(1, Arrays.copyOf(first, length), 0, 0, 1, false);
                    decodeQuietly(decoder, packet, "length " + length);
                }
            }
        });
    }

    @Test
    @DisplayName("random bytes never decode into anything but media exceptions")
    void noise() {
        var stream = vector("av1-1-b8-01-size-66x66.ivf");
        var random = new Random(11);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            try (var decoder = new Av1Decoder(stream.track(), Av1DecoderOptions.builder().limits(SMALL).build())) {
                for (var trial = 0; trial < 2000; trial++) {
                    var data = new byte[random.nextInt(512)];
                    random.nextBytes(data);
                    decodeQuietly(decoder, new Packet(1, data, trial, trial, 1, false), "noise " + trial);
                }
            }
        });
    }

    @Test
    @DisplayName("frames larger than the limits are rejected before allocation")
    void limits() {
        var stream = vector("av1-1-b10-00-quantizer-10.ivf");
        var tight = MediaLimits.builder().maxDimension(256).maxPixels(1 << 20).maxFrames(1_000).maxChannels(4)
                .maxBytes(1 << 26).build();
        try (var decoder = new Av1Decoder(stream.track(), Av1DecoderOptions.builder().limits(tight).build())) {
            assertThrows(MediaException.LimitExceeded.class,
                    () -> decoder.decode(stream.packets().getFirst(), frame -> fail("decoded an oversized frame")));
        }
        var pixels = MediaLimits.builder().maxDimension(1024).maxPixels(352 * 288 - 1).maxFrames(1_000).maxChannels(4)
                .maxBytes(1 << 26).build();
        try (var decoder = new Av1Decoder(stream.track(), Av1DecoderOptions.builder().limits(pixels).build())) {
            assertThrows(MediaException.LimitExceeded.class,
                    () -> decoder.decode(stream.packets().getFirst(), frame -> fail("decoded an oversized frame")));
        }
    }

    @Test
    @DisplayName("rejects an inter frame before any key frame and ignores empty packets")
    void startup() {
        var stream = vector("av1-1-b8-01-size-66x66.ivf");
        var packets = stream.packets();
        try (var decoder = new Av1Decoder(stream.track())) {
            var outputs = new ArrayList<Object>();
            decoder.decode(new Packet(1, new byte[0], 0, 0, 1, false), outputs::add);
            assertEquals(0, outputs.size());
            var inter = withoutSequenceHeader(packets.get(1).data());
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(new Packet(1, inter, 1, 1, 1, false), outputs::add));
            decoder.decode(packets.getFirst(), outputs::add);
            decoder.decode(packets.get(1), outputs::add);
            assertEquals(2, outputs.size());
        }
    }

    /**
     * Returns a temporal unit without its temporal delimiter and sequence header OBUs.
     */
    private static byte[] withoutSequenceHeader(byte[] data) {
        var offsets = obuOffsets(data);
        for (var offset : offsets) {
            var type = (data[offset] >> 3) & 15;
            if (type != 1 && type != 2) {
                return Arrays.copyOfRange(data, offset, data.length);
            }
        }
        return data;
    }

    @Nested
    @DisplayName("recovery")
    class Recovery {
        @ParameterizedTest(name = "frame delay {0}")
        @ValueSource(ints = {1, 4})
        @DisplayName("recovers bit-exactly at the next key frame after corrupt frames")
        void recovery(int frameDelay) {
            var foreman = Conformance.fetch("foreman_cif.y4m", "https://media.xiph.org/video/derf/y4m/foreman_cif.y4m",
                    "b0e3c35eff9fe1da91521a02eb9f0e3ef4bc5aec341ecef1567cd87f1711267b");
            var file = Fixtures.ffmpeg("ivf", List.of("-i", foreman.toString(), "-frames:v", "24", "-vf", "scale=176:144"),
                    List.of("-c:v", "libaom-av1", "-usage", "realtime", "-cpu-used", "8", "-b:v", "200k", "-g", "6",
                            "-keyint_min", "6"));
            var stream = load(file);
            var packets = stream.packets();
            var clean = decodeAll(stream.track(), packets.stream().map(Packet::data).toList());
            var keys = new ArrayList<Integer>();
            for (var i = 0; i < packets.size(); i++) {
                if (packets.get(i).keyframe()) {
                    keys.add(i);
                }
            }
            if (keys.size() < 3) {
                fail("expected periodic key frames, found " + keys);
            }
            var random = new Random(7);
            for (var trial = 0; trial < 12; trial++) {
                var data = new ArrayList<>(packets.stream().map(Packet::data).toList());
                var key = keys.get(1 + random.nextInt(keys.size() - 2));
                var victim = key - 1 - random.nextInt(3);
                var broken = data.get(victim).clone();
                var offsets = obuOffsets(broken);
                for (var i = offsets.getLast() + 4; i < broken.length; i += 1 + random.nextInt(4)) {
                    broken[i] ^= (byte) (1 + random.nextInt(255));
                }
                data.set(victim, broken);
                var outputs = new TreeMap<Integer, byte[]>();
                var options = Av1DecoderOptions.builder().maxFrameDelay(frameDelay).build();
                try (var decoder = new Av1Decoder(stream.track(), options)) {
                    for (var i = 0; i < data.size(); i++) {
                        try {
                            decoder.decode(new Packet(1, data.get(i), i, i, 1, false),
                                    frame -> outputs.put((int) frame.pts(), i420((YuvPicture) frame.picture())));
                        } catch (MediaException expected) {
                            // A corrupt frame and the frames that depend on it may be rejected.
                        }
                    }
                    try {
                        decoder.flush(frame -> outputs.put((int) frame.pts(), i420((YuvPicture) frame.picture())));
                    } catch (MediaException expected) {
                        // The last frames may depend on the corrupt one too.
                    }
                }
                for (var i = key; i < data.size(); i++) {
                    if (!Arrays.equals(clean.get(i), outputs.get(i))) {
                        fail("trial " + trial + ": frame " + i + " differs after corrupting frame " + victim);
                    }
                }
            }
        }

        private static TreeMap<Integer, byte[]> decodeAll(VideoTrack track, List<byte[]> packets) {
            var frames = new TreeMap<Integer, byte[]>();
            try (var decoder = new Av1Decoder(track)) {
                for (var i = 0; i < packets.size(); i++) {
                    var index = i;
                    decoder.decode(new Packet(1, packets.get(i), i, i, 1, false),
                            frame -> frames.put(index, i420((YuvPicture) frame.picture())));
                }
            }
            return frames;
        }

        private static byte[] i420(YuvPicture picture) {
            var out = new byte[picture.width() * picture.height() + 2 * picture.chromaWidth() * picture.chromaHeight()];
            var pos = 0;
            for (var y = 0; y < picture.height(); y++, pos += picture.width()) {
                System.arraycopy(picture.y(), y * picture.yStride(), out, pos, picture.width());
            }
            for (var plane : List.of(picture.u(), picture.v())) {
                for (var y = 0; y < picture.chromaHeight(); y++, pos += picture.chromaWidth()) {
                    System.arraycopy(plane, y * picture.uvStride(), out, pos, picture.chromaWidth());
                }
            }
            return out;
        }
    }
}

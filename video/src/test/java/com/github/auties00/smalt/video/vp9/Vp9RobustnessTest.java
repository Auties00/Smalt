package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.vp9.internal.Vp9FrameDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Feeds mutated VP9 streams (byte flips, truncations, header bit flips, overwritten and spliced
 * runs, corrupt tile sizes and superframe indexes, dropped, duplicated and emptied packets) to
 * the public decoder and the frame decoder, single-threaded and parallel. Every call must return
 * or raise a {@link MediaException} within the time bound, and the decoder must recover
 * bit-exactly at the next key frame.
 */
@DisplayName("VP9 robustness against hostile input")
class Vp9RobustnessTest {
    static final MediaLimits SMALL = MediaLimits.builder().maxDimension(2048).maxPixels(1 << 21).maxFrames(1000)
            .maxChannels(4).maxBytes(1 << 26).build();

    static VideoTrack track() {
        return VideoTrack.builder().id(1).codec(Codec.VP9).timeBase(new Rational(1, 30)).build();
    }

    static List<byte[]> packets(String name) {
        try {
            return Vp9Vectors.packets(name, Files.readAllBytes(Vp9Vectors.stream(name)));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
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
                var span = Math.min(data.length, 24);
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
                var count = Math.min(random.nextInt(128), Math.min(data.length - from, data.length - to));
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
                var marker = 0xC0 | random.nextInt(32);
                data[data.length - 1] = (byte) marker;
                if (random.nextBoolean()) {
                    var size = 2 + ((marker >> 3) & 3 + 1) * ((marker & 7) + 1);
                    if (size <= data.length) {
                        data[data.length - size] = (byte) marker;
                    }
                }
            }
            default -> {
                for (var n = 1 + random.nextInt(3); n > 0; n--) {
                    var at = random.nextInt(Math.max(1, data.length - 4));
                    var value = random.nextInt();
                    for (var i = 0; i < 4 && at + i < data.length; i++) {
                        data[at + i] = (byte) (value >> (24 - 8 * i));
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vp90-2-03-size-196x198.webm", "vp90-2-16-intra-only.webm", "vp91-2-04-yuv444.webm",
            "vp92-2-20-10bit-yuv420.webm", "vp93-2-20-12bit-yuv444.webm", "vp90-2-21-resize_inter_320x180_5_1-2.webm",
            "vp90-2-10-show-existing-frame.webm", "vp90-2-09-lf_deltas.webm", "vp91-2-04-yuv440.webm",
            "vp90-2-22-svc_1280x720_3.ivf", "vp90-2-02-size-08x08.webm"})
    @DisplayName("mutated, dropped and duplicated packets only throw media exceptions")
    void mutants(String name) {
        var clean = packets(name);
        var random = new Random(name.hashCode());
        assertTimeoutPreemptively(Duration.ofSeconds(240), () -> {
            for (var trial = 0; trial < 40; trial++) {
                var packets = new ArrayList<>(clean);
                for (var n = 1 + random.nextInt(4); n > 0; n--) {
                    var index = random.nextInt(packets.size());
                    switch (random.nextInt(5)) {
                        case 0, 1 -> packets.set(index, mutate(packets.get(index), random));
                        case 2 -> packets.remove(index);
                        case 3 -> packets.add(index, packets.get(random.nextInt(packets.size())));
                        default -> packets.set(index, new byte[random.nextInt(3)]);
                    }
                    if (packets.isEmpty()) {
                        packets.add(new byte[0]);
                    }
                }
                var limits = trial % 2 == 0 ? MediaLimits.DEFAULT : SMALL;
                try (var decoder = new Vp9Decoder(track(), VideoDecoderOptions.builder().limits(limits).build())) {
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
                var parallel = new Vp9FrameDecoder(limits, true);
                for (var i = 0; i < packets.size(); i++) {
                    var data = packets.get(i);
                    expectMediaException(() -> parallel.decodePacket(data, 0, data.length),
                            name + " trial " + trial + " parallel packet " + i);
                }
            }
        });
    }

    @Test
    @DisplayName("every truncation of a key frame only throws media exceptions")
    void truncations() {
        var frame = packets("vp90-2-03-size-196x198.webm").getFirst();
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (var length = 0; length < frame.length; length += 1 + length / 64) {
                var data = Arrays.copyOf(frame, length);
                var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
                expectMediaException(() -> decoder.decodePacket(data, 0, data.length), "length " + length);
            }
        });
    }

    @Test
    @DisplayName("frame sizes beyond the limits are rejected before allocation")
    void hugeDimensions() {
        var frame = packets("vp90-2-02-size-08x08.webm").getFirst().clone();
        // Profile 0 key frame: marker(2) profile(2) show_existing(1) type(1) show(1) error_res(1),
        // sync code (3 bytes), color (4 bits), then width - 1 and height - 1 in 16 bits each.
        setBits(frame, 8 + 24 + 4, 16, 0xFFFF);
        setBits(frame, 8 + 24 + 4 + 16, 16, 0xFFFF);
        var decoder = new Vp9FrameDecoder(MediaLimits.builder().maxDimension(16_384).maxPixels(1L << 30).maxFrames(10)
                .maxChannels(4).maxBytes(1L << 30).build(), false);
        assertThrows(MediaException.LimitExceeded.class, () -> decoder.decodePacket(frame, 0, frame.length));
        var small = new Vp9FrameDecoder(MediaLimits.builder().maxDimension(65_536).maxPixels(1 << 20).maxFrames(10)
                .maxChannels(4).maxBytes(1L << 30).build(), false);
        assertThrows(MediaException.LimitExceeded.class, () -> small.decodePacket(frame, 0, frame.length));
    }

    private static void setBits(byte[] data, int bitOffset, int bits, int value) {
        for (var i = 0; i < bits; i++) {
            var bit = (value >> (bits - 1 - i)) & 1;
            var position = bitOffset + i;
            var mask = 1 << (7 - (position & 7));
            if (bit != 0) {
                data[position >> 3] |= (byte) mask;
            } else {
                data[position >> 3] &= (byte) ~mask;
            }
        }
    }

    @Test
    @DisplayName("rejects inter frames before a key frame and ignores empty packets")
    void startup() {
        var packets = packets("vp90-2-03-size-196x198.webm");
        try (var decoder = new Vp9Decoder(track())) {
            var outputs = new ArrayList<Object>();
            decoder.decode(new Packet(1, new byte[0], 0, 0, 1, false), outputs::add);
            assertEquals(0, outputs.size());
            assertThrows(MediaException.Malformed.class,
                    () -> decoder.decode(new Packet(1, packets.get(1), 1, 1, 1, false), outputs::add));
            decoder.decode(new Packet(1, packets.getFirst(), 2, 2, 1, false), outputs::add);
            decoder.decode(new Packet(1, packets.get(1), 3, 3, 1, false), outputs::add);
            assertEquals(2, outputs.size());
        }
    }

    @Test
    @DisplayName("recovers bit-exactly at the next key frame after a corrupt frame")
    void recovery() {
        var file = Vp9DifferentialTest.encode("recovery", 160, 96, List.of("-g", "5", "-keyint_min", "5", "-b:v", "200k",
                "-deadline", "good", "-cpu-used", "4"), 20);
        var packets = Vp9DifferentialTest.webmPackets(file);
        var clean = Vp9DifferentialTest.frameMd5s(packets, false);
        var random = new Random(11);
        for (var trial = 0; trial < 8; trial++) {
            var damaged = new ArrayList<>(packets);
            var victim = 1 + random.nextInt(8);
            var broken = damaged.get(victim).clone();
            for (var i = 2; i < broken.length; i += 1 + random.nextInt(3)) {
                broken[i] ^= (byte) (1 + random.nextInt(255));
            }
            damaged.set(victim, broken);
            var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
            var outputs = new ArrayList<String>();
            var keyAfter = -1;
            for (var i = 0; i < damaged.size(); i++) {
                var data = damaged.get(i);
                try {
                    decoder.decodePacket(data, 0, data.length);
                    var frame = decoder.shownFrame();
                    if (i > victim && keyAfter < 0 && decoder.shownKeyFrame()) {
                        keyAfter = i;
                    }
                    outputs.add(frame == null ? null : Vp9Vectors.md5(frame));
                } catch (MediaException expected) {
                    outputs.add(null);
                }
            }
            if (keyAfter < 0) {
                fail("no key frame after the corrupt frame " + victim);
            }
            for (var i = keyAfter; i < damaged.size(); i++) {
                assertEquals(clean.get(i), outputs.get(i), "trial " + trial + " frame " + i + " after corrupting " + victim);
            }
        }
    }
}

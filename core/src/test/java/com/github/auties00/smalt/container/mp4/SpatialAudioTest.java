package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the spatial audio box ({@code SA3D}, Google Spatial Audio RFC): Smalt's files read back by Smalt and by
 * ffprobe, files of another writer (an ffmpeg QuickTime file with the box the spatial media injector writes),
 * remuxing, and damaged boxes, which leave the track without ambisonics instead of failing.
 */
@DisplayName("MP4 spatial audio box")
class SpatialAudioTest {
    private static final Path OUTPUT = Scratch.directory("mp4-spatial-audio");

    private static final ChannelLayout HEAD_LOCKED = ChannelLayout.ofAmbisonics(Ambisonics.of(1),
            List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT));

    private static Path write(String name, Mp4MuxerOptions.Brand brand, ChannelLayout layout) {
        var target = OUTPUT.resolve(name + (brand == Mp4MuxerOptions.Brand.QUICKTIME ? ".mov" : ".mp4"));
        var track = AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(48000))
                .sampleRate(48000).layout(layout).build();
        try (var channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING);
             var muxer = new Mp4Muxer(MediaSink.of(channel), Mp4MuxerOptions.builder().brand(brand).build())) {
            muxer.addTrack(track);
            var random = new Random(layout.channels());
            for (var index = 0; index < 10; index++) {
                var data = new byte[2 * layout.channels() * 480];
                random.nextBytes(data);
                muxer.writePacket(new Packet(1, data, index * 480L, index * 480L, 480, true));
            }
            muxer.finish();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return target;
    }

    private static AudioTrack read(Path file) {
        return (AudioTrack) ContainerProbe.demux(file, Mp4Demuxer::new).tracks().stream()
                .filter(track -> track.kind() == TrackKind.AUDIO).findFirst().orElseThrow();
    }

    private static int indexOf(byte[] data, String type) {
        return Mp4MuxerCodecCoverageTest.indexOf(data, type);
    }

    /**
     * Returns the payload of the {@code SA3D} box of a file, or {@code null}.
     */
    private static byte[] sa3d(byte[] file) {
        var at = indexOf(file, "SA3D");
        if (at < 0) {
            return null;
        }
        var size = ByteBuffer.wrap(file, at - 4, 4).getInt();
        return Arrays.copyOfRange(file, at + 4, at - 4 + size);
    }

    /**
     * Builds an {@code SA3D} box from its fields.
     */
    static byte[] box(int version, int type, long order, int ordering, int normalization, long channels, long... map) {
        var payload = ByteBuffer.allocate(20 + 4 * map.length);
        payload.putInt(20 + 4 * map.length).put("SA3D".getBytes(StandardCharsets.US_ASCII));
        payload.put((byte) version).put((byte) type).putInt((int) order).put((byte) ordering)
                .put((byte) normalization).putInt((int) channels);
        for (var entry : map) {
            payload.putInt((int) entry);
        }
        return payload.array();
    }

    /**
     * Appends a box to the first audio sample entry of a file whose {@code moov} follows its media, growing every
     * enclosing box.
     */
    static byte[] inject(byte[] file, byte[] box) {
        var parents = new java.util.ArrayList<Integer>();
        var insert = find(file, 0, file.length, parents);
        assertTrue(insert > 0, "audio sample entry");
        var data = ByteBuffer.wrap(file);
        for (var offset : parents) {
            data.putInt(offset, data.getInt(offset) + box.length);
        }
        var out = new ByteArrayOutputStream();
        out.write(file, 0, insert);
        out.writeBytes(box);
        out.write(file, insert, file.length - insert);
        return out.toByteArray();
    }

    private static int find(byte[] file, int start, int end, List<Integer> parents) {
        var containers = Set.of("moov", "trak", "mdia", "minf", "stbl");
        var data = ByteBuffer.wrap(file);
        for (var position = start; position + 8 <= end; ) {
            var size = data.getInt(position);
            var type = new String(file, position + 4, 4, StandardCharsets.US_ASCII);
            if (containers.contains(type)) {
                parents.add(position);
                var hit = find(file, position + 8, position + size, parents);
                if (hit > 0) {
                    return hit;
                }
                parents.removeLast();
            } else if (type.equals("stsd")) {
                parents.add(position);
                parents.add(position + 16);
                return position + 16 + data.getInt(position + 16);
            }
            position += size;
        }
        return -1;
    }

    private static Path foreign(int channels, byte[] box, String name) {
        var pan = new StringBuilder("pan=" + channels + "C");
        for (var c = 0; c < channels; c++) {
            pan.append("|c").append(c).append('=').append(1.0 / (c + 1)).append("*c0");
        }
        var mov = Fixtures.ffmpeg("mov", List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=0.2"),
                List.of("-af", pan.toString(), "-c:a", "pcm_s16le"));
        var target = OUTPUT.resolve(name + ".mov");
        try {
            Files.write(target, inject(Fixtures.read(mov), box));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return target;
    }

    static Stream<ChannelLayout> writable() {
        return Stream.of(ChannelLayout.ofAmbisonics(0), ChannelLayout.ofAmbisonics(1), HEAD_LOCKED, ChannelLayout.ofAmbisonics(2),
                ChannelLayout.ofAmbisonics(3), ChannelLayout.ofAmbisonics(Ambisonics.of(2), List.of(Speaker.FRONT_RIGHT,
                        Speaker.FRONT_LEFT)),
                ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.SN3D, List.of(0, 3, 1, 2))));
    }

    @Nested
    @DisplayName("written by Smalt")
    class Written {
        @ParameterizedTest
        @MethodSource("com.github.auties00.smalt.container.mp4.SpatialAudioTest#writable")
        @DisplayName("round trips full SN3D layouts through both brands")
        void roundTrip(ChannelLayout layout) {
            for (var brand : List.of(Mp4MuxerOptions.Brand.MP4, Mp4MuxerOptions.Brand.QUICKTIME)) {
                var file = write("round-trip-" + layout.channels() + "-" + Integer.toHexString(layout.hashCode()) + "-"
                        + brand, brand, layout);
                assertEquals(layout, read(file).layout().orElseThrow(), brand.toString());
                assertTrue(sa3d(Fixtures.read(file)) != null, "SA3D written");
            }
        }

        @Test
        @DisplayName("writes the fields of the RFC, the head-locked flag and the channel map")
        void fields() {
            var order1 = sa3d(Fixtures.read(write("fields-1", Mp4MuxerOptions.Brand.MP4, ChannelLayout.ofAmbisonics(1))));
            assertArrayEquals(Arrays.copyOfRange(box(0, 0, 1, 0, 0, 4, 0, 1, 2, 3), 8, 36), order1);
            var locked = sa3d(Fixtures.read(write("fields-locked", Mp4MuxerOptions.Brand.QUICKTIME, HEAD_LOCKED)));
            assertArrayEquals(Arrays.copyOfRange(box(0, 0x80, 1, 0, 0, 6, 0, 1, 2, 3, 4, 5), 8, 44), locked);
            var permuted = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.UNKNOWN,
                    Speaker.UNKNOWN, Speaker.UNKNOWN, Speaker.UNKNOWN), Optional.of(new Ambisonics(
                    Ambisonics.Normalization.SN3D, List.of(0, 3, 1, 2))));
            var reordered = sa3d(Fixtures.read(write("fields-permuted", Mp4MuxerOptions.Brand.MP4, permuted)));
            assertArrayEquals(Arrays.copyOfRange(box(0, 0x80, 1, 0, 0, 6, 4, 5, 0, 3, 1, 2), 8, 44), reordered);
        }

        @Test
        @DisplayName("writes no box for layouts the RFC cannot describe")
        void notWritable() {
            var layouts = List.of(ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4)),
                    ChannelLayout.ofAmbisonics(Ambisonics.of(1, Ambisonics.Normalization.N3D)),
                    ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.SN3D, List.of(0, 1, 3))),
                    ChannelLayout.ofAmbisonics(Ambisonics.of(1), List.of(Speaker.FRONT_CENTER)),
                    ChannelLayout.SURROUND_5_1);
            for (var layout : layouts) {
                var file = write("not-writable-" + layout.channels() + "-" + layout.hashCode(),
                        Mp4MuxerOptions.Brand.MP4, layout);
                assertEquals(null, sa3d(Fixtures.read(file)), layout.toString());
                assertTrue(read(file).layout().orElseThrow().ambisonics().isEmpty(), layout.toString());
            }
        }

        @ParameterizedTest
        @EnumSource(value = Mp4MuxerOptions.Brand.class, names = {"MP4", "QUICKTIME"})
        @DisplayName("ffprobe reads Smalt's box as an ambisonic layout")
        void ffprobe(Mp4MuxerOptions.Brand brand) {
            Oracle.assume(Oracle.Tool.FFPROBE);
            var cases = List.of(ChannelLayout.ofAmbisonics(1), HEAD_LOCKED, ChannelLayout.ofAmbisonics(2),
                    ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.SN3D, List.of(0, 3, 1, 2))));
            var names = List.of("\"ambisonic 1\"", "\"ambisonic 1+stereo\"", "\"ambisonic 2\"",
                    "AMBI0+AMBI3+AMBI1+AMBI2");
            for (var i = 0; i < cases.size(); i++) {
                var file = write("ffprobe-" + i + "-" + brand, brand, cases.get(i));
                var probe = Oracle.probe(file);
                assertTrue(probe.contains(names.get(i)), names.get(i) + " in " + probe);
                Oracle.assertDecodesCleanly(file);
            }
        }

        @Test
        @DisplayName("keeps the box when remuxed")
        void remux() {
            for (var layout : List.of(ChannelLayout.ofAmbisonics(1), HEAD_LOCKED)) {
                var file = write("remux-source-" + layout.channels(), Mp4MuxerOptions.Brand.MP4, layout);
                for (var brand : List.of(Mp4MuxerOptions.Brand.MP4, Mp4MuxerOptions.Brand.QUICKTIME)) {
                    var remuxed = Mp4MuxerCodecCoverageTest.mux(file, "spatial-remux-" + layout.channels() + "-" + brand,
                            brand);
                    assertEquals(layout, read(remuxed).layout().orElseThrow(), brand.toString());
                    assertArrayEquals(sa3d(Fixtures.read(file)), sa3d(Fixtures.read(remuxed)));
                }
            }
        }
    }

    @Nested
    @DisplayName("written by other tools")
    class Foreign {
        @Test
        @DisplayName("reads the box the spatial media injector writes, agreeing with ffprobe")
        void injector() {
            var plain = foreign(4, box(0, 0, 1, 0, 0, 4, 0, 1, 2, 3), "injector-foa");
            assertEquals(ChannelLayout.ofAmbisonics(1), read(plain).layout().orElseThrow());
            var locked = foreign(6, box(0, 0x80, 1, 0, 0, 6, 0, 1, 2, 3, 4, 5), "injector-locked");
            assertEquals(HEAD_LOCKED, read(locked).layout().orElseThrow());
            var permuted = foreign(4, box(0, 0, 1, 0, 0, 4, 0, 3, 1, 2), "injector-permuted");
            assertEquals(ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.SN3D, List.of(0, 3, 1, 2))),
                    read(permuted).layout().orElseThrow());
            var stereoFirst = foreign(6, box(0, 0x80, 1, 0, 0, 6, 4, 5, 0, 1, 2, 3), "injector-stereo-first");
            assertEquals(new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.UNKNOWN,
                    Speaker.UNKNOWN, Speaker.UNKNOWN, Speaker.UNKNOWN), Optional.of(Ambisonics.of(1))),
                    read(stereoFirst).layout().orElseThrow());
            if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
                assertTrue(Oracle.probe(permuted).contains("AMBI0+AMBI3+AMBI1+AMBI2"));
                assertTrue(Oracle.probe(stereoFirst).contains("FL+FR+AMBI0+AMBI1+AMBI2+AMBI3"));
            }
        }

        static Stream<byte[]> damaged() {
            return Stream.of(
                    box(1, 0, 1, 0, 0, 4, 0, 1, 2, 3),
                    box(0, 1, 1, 0, 0, 4, 0, 1, 2, 3),
                    box(0, 0, 1, 1, 0, 4, 0, 1, 2, 3),
                    box(0, 0, 1, 0, 1, 4, 0, 1, 2, 3),
                    box(0, 0, 2, 0, 0, 4, 0, 1, 2, 3),
                    box(0, 0, 1, 0, 0, 4, 0, 1, 1, 3),
                    box(0, 0, 1, 0, 0, 4, 0, 1, 2, 4),
                    box(0, 0, 1, 0, 0, 4, 0, 1, 2),
                    box(0, 0, 1, 0, 0, 0x7FFFFFFF, 0, 1, 2, 3),
                    box(0, 0, 15, 0, 0, 4, 0, 1, 2, 3),
                    box(0, 0, 0xFFFFFFFFL, 0, 0, 4, 0, 1, 2, 3),
                    Arrays.copyOf(box(0, 0, 1, 0, 0, 4), 12));
        }

        @ParameterizedTest
        @MethodSource("com.github.auties00.smalt.container.mp4.SpatialAudioTest$Foreign#damaged")
        @DisplayName("ignores damaged or unknown boxes")
        void damagedBoxes(byte[] box) {
            if (box.length == 12) {
                ByteBuffer.wrap(box).putInt(0, 12);
            }
            var file = foreign(4, box, "damaged-" + Arrays.hashCode(box));
            var layout = read(file).layout().orElseThrow();
            assertEquals(4, layout.channels());
            assertFalse(layout.ambisonics().isPresent(), layout.toString());
        }
    }
}

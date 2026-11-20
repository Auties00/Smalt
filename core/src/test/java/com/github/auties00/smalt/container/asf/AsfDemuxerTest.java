package com.github.auties00.smalt.container.asf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.internal.asf.AsfGuid;
import com.github.auties00.smalt.internal.asf.AsfTags;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.container.DemuxerAsserts;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demuxes ASF files written by {@link AsfWriter} under every payload layout and length type, and checks that every
 * media object comes back whole, in order, with its time and flags; the same files go through ffprobe as a black-box
 * check of the writer and reader against another implementation, and through the I/O behavior harness. The
 * descriptive objects, extended stream properties, audio spread interleaving, MPEG audio framing, seeking and the
 * failures of corrupt and protected files are covered by their own tests.
 */
@DisplayName("AsfDemuxer")
class AsfDemuxerTest {
    /** Two streams: 16-bit stereo PCM audio (stream 1) and an opaque video FourCC (stream 2). */
    static AsfWriter twoStreams(AsfWriter.Layout layout, long seed) {
        var writer = new AsfWriter();
        writer.layout = layout;
        writer.streams.add(AsfWriter.pcmAudio(1, 2, 8000));
        writer.streams.add(AsfWriter.video(2, "SMLT", 64, 48, new byte[0]));
        var random = new Random(seed);
        for (var frame = 0; frame < 40; frame++) {
            var millis = writer.preroll + frame * 40L;
            var size = frame % 10 == 0 ? 1500 + random.nextInt(800) : 20 + random.nextInt(300);
            writer.objects.add(new AsfWriter.MediaObject(2, millis, frame % 10 == 0, bytes(random, size)));
            if (frame % 2 == 0) {
                writer.objects.add(new AsfWriter.MediaObject(1, millis, false, bytes(random, 160 * 4)));
            }
        }
        return writer;
    }

    static byte[] bytes(Random random, int size) {
        var data = new byte[size];
        random.nextBytes(data);
        return data;
    }

    static List<Packet> readAll(byte[] file) {
        try (var source = MediaSource.of(file); var demuxer = new AsfDemuxer(source)) {
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return packets;
        }
    }

    /** Checks that the packets of each stream are its objects, in order, with their times and flags. */
    static void assertObjects(AsfWriter writer, List<Packet> packets) {
        for (var stream : writer.streams) {
            var objects = writer.objects.stream().filter(object -> object.stream() == stream.number).toList();
            var ours = packets.stream().filter(packet -> packet.trackId() == stream.number).toList();
            assertEquals(objects.size(), ours.size(), "packets of stream " + stream.number);
            for (var index = 0; index < objects.size(); index++) {
                var object = objects.get(index);
                var packet = ours.get(index);
                var name = "object " + index + " of stream " + stream.number;
                assertArrayEquals(object.data(), packet.data(), name);
                assertEquals(object.millis() - writer.preroll, packet.pts(), name + " pts");
                assertEquals(packet.pts(), packet.dts(), name + " dts");
                var video = Arrays.equals(stream.type, AsfGuid.VIDEO_MEDIA);
                assertEquals(!video || object.key(), packet.keyframe(), name + " key");
            }
        }
    }

    static Stream<Arguments> layouts() {
        var cases = new ArrayList<Arguments>();
        for (var layout : AsfWriter.Layout.values()) {
            cases.add(Arguments.of(layout, "defaults", (java.util.function.Consumer<AsfWriter>) writer -> {
            }));
            cases.add(Arguments.of(layout, "no error correction, dword fields",
                    (java.util.function.Consumer<AsfWriter>) writer -> {
                        writer.errorCorrectionData = false;
                        writer.numberWidth = 4;
                        writer.offsetWidth = 4;
                        writer.replicatedWidth = 4;
                        writer.paddingWidth = 4;
                        writer.payloadLengthWidth = 4;
                    }));
            cases.add(Arguments.of(layout, "word fields, explicit packet length",
                    (java.util.function.Consumer<AsfWriter>) writer -> {
                        writer.numberWidth = 2;
                        writer.offsetWidth = 2;
                        writer.replicatedWidth = 2;
                        writer.paddingWidth = 1;
                        writer.payloadLengthWidth = 1;
                        writer.explicitPacketLength = true;
                        writer.packetSize = 250;
                    }));
            cases.add(Arguments.of(layout, "no padding field, packet length ends the payloads",
                    (java.util.function.Consumer<AsfWriter>) writer -> {
                        writer.paddingWidth = 0;
                        writer.explicitPacketLength = true;
                        writer.packetSize = 1024;
                    }));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("layouts")
    @DisplayName("returns every media object whole, with its time and key flag")
    void objects(AsfWriter.Layout layout, String variant, java.util.function.Consumer<AsfWriter> setup) {
        var writer = twoStreams(layout, variant.hashCode());
        setup.accept(writer);
        var file = writer.build();
        try (var source = MediaSource.of(file)) {
            assertEquals(ContainerFormat.ASF, Containers.detect(source).orElseThrow());
        }
        assertObjects(writer, readAll(file));
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("layouts")
    @DisplayName("ffprobe reads the same packets")
    void ffprobe(AsfWriter.Layout layout, String variant, java.util.function.Consumer<AsfWriter> setup)
            throws IOException {
        var writer = twoStreams(layout, variant.hashCode());
        setup.accept(writer);
        var file = Scratch.directory("asf").resolve("synthetic-" + layout + "-" + Math.abs(variant.hashCode())
                + ".asf");
        Files.write(file, writer.build());
        assertTrue(AsfProbe.compare(file) > 0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    @DisplayName("passes the I/O behavior harness")
    void behavior(AsfWriter.Layout layout, String variant, java.util.function.Consumer<AsfWriter> setup) {
        var writer = twoStreams(layout, variant.hashCode());
        setup.accept(writer);
        DemuxerAsserts.of("synthetic-" + layout, writer.build()).noDump().run();
    }

    @Nested
    @DisplayName("audio spread error correction")
    class Spread {
        static AsfWriter spread() {
            var writer = new AsfWriter();
            writer.layout = AsfWriter.Layout.SINGLE;
            writer.packetSize = 600;
            writer.streams.add(AsfWriter.pcmAudio(1, 1, 8000).spread(4, 200, 50));
            var random = new Random(5);
            for (var block = 0; block < 12; block++) {
                writer.objects.add(new AsfWriter.MediaObject(1, writer.preroll + block * 50L, true,
                        bytes(random, 800)));
            }
            return writer;
        }

        @Test
        @DisplayName("puts interleaved chunks back in order")
        void descrambled() {
            var writer = spread();
            assertObjects(writer, readAll(writer.build()));
        }

        @Test
        @DisplayName("ffprobe puts them back in the same order")
        void ffprobe() throws IOException {
            var file = Scratch.directory("asf").resolve("spread.asf");
            Files.write(file, spread().build());
            AsfProbe.compare(file);
        }

        @Test
        @DisplayName("the chunk permutation is the inverse of the writer's")
        void permutation() {
            var original = new byte[24];
            for (var i = 0; i < original.length; i++) {
                original[i] = (byte) i;
            }
            var stored = AsfWriter.scramble(original, 3, 4, 2);
            assertFalse(Arrays.equals(original, stored));
            assertArrayEquals(new byte[]{0, 1, 6, 7, 12, 13, 18, 19, 2, 3, 8, 9, 14, 15, 20, 21, 4, 5, 10, 11, 16,
                    17, 22, 23}, stored);
            assertArrayEquals(original, AsfDemuxer.descramble(stored, 3, 4, 2));
        }
    }

    @Nested
    @DisplayName("stream properties")
    class Properties {
        @Test
        @DisplayName("extended stream properties give the frame rate, language, name and sample durations")
        void extended() {
            var writer = twoStreams(AsfWriter.Layout.MULTIPLE, 3);
            writer.languages.addAll(List.of("en-us", "fr-ca"));
            var video = writer.streams.get(1);
            video.extended = true;
            video.averageTimePerFrame = 400_000;
            video.languageIndex = 1;
            video.names.add("Main camera");
            video.extensions.add(new AsfWriter.Extension(AsfGuid.of("399595EC-8667-4E2D-8FDB-98814CE76C1E"), 14));
            video.extensions.add(new AsfWriter.Extension(AsfGuid.SAMPLE_DURATION_EXTENSION, 2));
            var objects = new ArrayList<AsfWriter.MediaObject>();
            for (var object : writer.objects) {
                if (object.stream() == 2) {
                    var extension = new byte[16];
                    extension[14] = 40;
                    objects.add(new AsfWriter.MediaObject(2, object.millis(), object.key(), object.data(),
                            extension));
                } else {
                    objects.add(object);
                }
            }
            writer.objects.clear();
            writer.objects.addAll(objects);
            writer.metadata.add(new AsfWriter.Attribute("AspectRatioX", 3, new byte[]{4, 0, 0, 0}, 2, 0));
            writer.metadata.add(new AsfWriter.Attribute("AspectRatioY", 3, new byte[]{3, 0, 0, 0}, 2, 0));
            writer.metadata.add(new AsfWriter.Attribute("IsVBR", 2, new byte[]{1, 0}, 2, 0));
            var file = writer.build();
            try (var source = MediaSource.of(file); var demuxer = new AsfDemuxer(source)) {
                var track = (VideoTrack) demuxer.tracks().get(1);
                assertEquals(new Rational(25, 1), track.frameRate().orElseThrow());
                assertEquals("fre", track.language().orElseThrow());
                assertEquals(new Rational(4, 3), track.sampleAspectRatio().orElseThrow());
                assertEquals("Main camera", demuxer.trackMetadata(2).get("title"));
                assertEquals("1", demuxer.trackMetadata(2).get("IsVBR"));
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.trackId() == 2) {
                        assertEquals(40, packet.duration());
                    } else {
                        assertEquals(20, packet.duration(), "640 PCM bytes at 32000 bytes per second");
                    }
                }
            }
            assertObjects(writer, readAll(file));
        }

        @Test
        @DisplayName("a stream whose properties only an extended stream properties object holds is a track")
        void hidden() {
            var writer = twoStreams(AsfWriter.Layout.MULTIPLE, 4);
            writer.streams.get(1).hidden = true;
            var file = writer.build();
            try (var source = MediaSource.of(file); var demuxer = new AsfDemuxer(source)) {
                assertEquals(2, demuxer.tracks().size());
                assertEquals(64, ((VideoTrack) demuxer.tracks().get(1)).width());
            }
            assertObjects(writer, readAll(file));
        }

        @Test
        @DisplayName("maps the codecs of video FourCCs and audio format tags")
        void codecs() {
            var writer = new AsfWriter();
            writer.streams.add(AsfWriter.video(1, "WMV3", 128, 96, new byte[]{0x4E, (byte) 0xF9, 0x18, 0x01}));
            writer.streams.add(AsfWriter.video(2, "WVC1", 128, 96, new byte[]{0x25, 0, 0, 1, 0x0F, 0x00}));
            writer.streams.add(AsfWriter.video(3, "H264", 64, 48, new byte[0]));
            writer.streams.add(AsfWriter.audio(4, 0x55, 2, 44100, 1, new byte[12]));
            writer.streams.add(AsfWriter.audio(5, 0x161, 2, 44100, 4096, new byte[10]));
            writer.streams.add(AsfWriter.audio(6, 0x2000, 2, 48000, 1, new byte[0]));
            writer.streams.add(new AsfWriter.Stream(7, AsfGuid.JFIF_MEDIA, new byte[]{32, 0, 0, 0, 16, 0, 0, 0, 0, 0,
                    0, 0}));
            writer.streams.add(new AsfWriter.Stream(8, AsfGuid.COMMAND_MEDIA, new byte[0]));
            writer.objects.add(new AsfWriter.MediaObject(4, 1000, true, new byte[10]));
            try (var source = MediaSource.of(writer.build()); var demuxer = new AsfDemuxer(source)) {
                var codecs = demuxer.tracks().stream().map(track -> track.codec()).toList();
                assertEquals(List.of(Codec.VC1, Codec.VC1, Codec.H264, Codec.MP3, Codec.UNKNOWN_AUDIO, Codec.AC3,
                        Codec.MJPEG), codecs);
                assertArrayEquals(new byte[]{0x4E, (byte) 0xF9, 0x18, 0x01},
                        demuxer.tracks().get(0).codecConfig().orElseThrow());
                assertEquals(44100, ((AudioTrack) demuxer.tracks().get(3)).sampleRate());
                assertEquals(new Rational(1, 1000), demuxer.tracks().get(0).timeBase());
            }
        }
    }

    @Nested
    @DisplayName("MPEG audio")
    class MpegAudio {
        /** An MPEG-1 Layer III frame at 128 kbit/s and 48 kHz: 384 bytes, 1152 samples (24 ms). */
        static byte[] frame(int fill) {
            var frame = new byte[384];
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xFB;
            frame[2] = (byte) 0x94;
            frame[3] = (byte) 0x44;
            Arrays.fill(frame, 4, frame.length, (byte) fill);
            return frame;
        }

        @Test
        @DisplayName("cuts objects into whole frames, timing those after the first by their samples")
        void framed() {
            var writer = new AsfWriter();
            writer.streams.add(AsfWriter.audio(1, 0x55, 2, 48000, 1, new byte[12]));
            var three = new byte[3 * 384];
            for (var i = 0; i < 3; i++) {
                System.arraycopy(frame(i + 1), 0, three, i * 384, 384);
            }
            // a frame split across two objects: 200 bytes end the first, the rest start the second
            var first = Arrays.copyOf(three, three.length + 200);
            System.arraycopy(frame(4), 0, first, three.length, 200);
            var second = new byte[184 + 384];
            System.arraycopy(frame(4), 200, second, 0, 184);
            System.arraycopy(frame(5), 0, second, 184, 384);
            writer.objects.add(new AsfWriter.MediaObject(1, 1000, true, first));
            writer.objects.add(new AsfWriter.MediaObject(1, 1096, true, second));
            var packets = readAll(writer.build());
            assertEquals(5, packets.size());
            for (var i = 0; i < 5; i++) {
                assertArrayEquals(frame(i + 1), packets.get(i).data(), "frame " + i);
                assertEquals(24, packets.get(i).duration());
            }
            assertEquals(List.of(0L, 24L, 48L, 72L, 96L), packets.stream().map(Packet::pts).toList());
        }
    }

    @Nested
    @DisplayName("descriptive objects")
    class Descriptive {
        @Test
        @DisplayName("reads tags, pictures, chapters, script commands and the creation date")
        void read() {
            var writer = twoStreams(AsfWriter.Layout.MULTIPLE, 6);
            writer.contentDescription = new String[]{"A Title", "An Author", "(c) 2026", "Some comment", null};
            writer.descriptors.add(new AsfWriter.Attribute("WM/AlbumTitle", 0, AsfWriter.utf16z("An Album"), 0, 0));
            writer.descriptors.add(new AsfWriter.Attribute("WM/TrackNumber", 3, new byte[]{9, 0, 0, 0}, 0, 0));
            writer.descriptors.add(new AsfWriter.Attribute("WM/Genre", 0, AsfWriter.utf16z("Jazz"), 0, 0));
            writer.descriptors.add(new AsfWriter.Attribute("WM/Genre", 0, AsfWriter.utf16z("Blues"), 0, 0));
            writer.descriptors.add(new AsfWriter.Attribute("WM/IsCompilation", 2, new byte[]{1, 0, 0, 0}, 0, 0));
            writer.descriptors.add(new AsfWriter.Attribute("WM/Mood", 0, AsfWriter.utf16z("Calm"), 0, 0));
            writer.descriptors.add(new AsfWriter.Attribute("WM/MCDI", 1, new byte[]{1, 2, 3}, 0, 0));
            var small = AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "front",
                    new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0});
            writer.descriptors.add(new AsfWriter.Attribute(AsfTags.PICTURE, 1, AsfTags.picture(small), 0, 0));
            var large = new byte[100_000];
            large[0] = (byte) 0xFF;
            large[1] = (byte) 0xD8;
            var back = AttachedPicture.of(AttachedPicture.Type.BACK_COVER, "image/jpeg", "", large);
            writer.library.add(new AsfWriter.Attribute(AsfTags.PICTURE, 1, AsfTags.picture(back), 0, 0));
            writer.library.add(new AsfWriter.Attribute("WM/Composer", 0, AsfWriter.utf16z("A Composer"), 0, 0));
            writer.metadata.add(new AsfWriter.Attribute("WM/Writer", 0, AsfWriter.utf16z("A Writer"), 0, 0));
            writer.markers.add(new AsfWriter.Marker(writer.preroll * 10_000, "Start"));
            writer.markers.add(new AsfWriter.Marker((writer.preroll + 800) * 10_000, "Middle"));
            writer.commands.add(new AsfWriter.Command(writer.preroll + 500, "URL", "https://example.com/"));
            writer.commands.add(new AsfWriter.Command(writer.preroll + 900, "TEXT", "Hello"));
            writer.creationDate = 133_000_000_000_000_000L;
            try (var source = MediaSource.of(writer.build()); var demuxer = new AsfDemuxer(source)) {
                var tags = demuxer.metadata();
                assertEquals("A Title", tags.get("title"));
                assertEquals("An Author", tags.get("artist"));
                assertEquals("(c) 2026", tags.get("copyright"));
                assertEquals("Some comment", tags.get("comment"));
                assertEquals("An Album", tags.get("album"));
                assertEquals("9", tags.get("track"));
                assertEquals("Jazz; Blues", tags.get("genre"));
                assertEquals("1", tags.get("compilation"));
                assertEquals("Calm", tags.get("WM/Mood"));
                assertEquals("A Composer", tags.get("composer"));
                assertEquals("A Writer", tags.get("lyricist"));
                assertFalse(tags.containsKey("WM/MCDI"), "byte arrays are not tags");
                assertEquals("2022-06-18T04:26:40Z", tags.get("creation_time"));
                assertEquals(List.of(small, back), demuxer.attachedPictures());
                var chapters = demuxer.chapters();
                assertEquals(2, chapters.size());
                assertEquals(0, chapters.get(0).startMicros());
                assertEquals(800_000, chapters.get(0).endMicros());
                assertEquals("Middle", chapters.get(1).title().orElseThrow());
                assertEquals(demuxer.durationMicros(), chapters.get(1).endMicros());
                assertEquals(List.of(new AsfDemuxer.ScriptCommand(500_000, "URL", "https://example.com/"),
                        new AsfDemuxer.ScriptCommand(900_000, "TEXT", "Hello")), demuxer.scriptCommands());
            }
        }

        @Test
        @DisplayName("takes the track from WM/Track, zero-based, when WM/TrackNumber is absent")
        void zeroBasedTrack() {
            var writer = twoStreams(AsfWriter.Layout.MULTIPLE, 7);
            writer.descriptors.add(new AsfWriter.Attribute("WM/Track", 3, new byte[]{4, 0, 0, 0}, 0, 0));
            try (var source = MediaSource.of(writer.build()); var demuxer = new AsfDemuxer(source)) {
                assertEquals("5", demuxer.metadata().get("track"));
            }
        }
    }

    @Nested
    @DisplayName("seeking")
    class Seeking {
        @Test
        @DisplayName("restarts video at the last key frame and audio at the last object at or before the target")
        void seek() {
            var writer = twoStreams(AsfWriter.Layout.MULTIPLE, 8);
            var file = writer.build();
            try (var source = MediaSource.of(file); var demuxer = new AsfDemuxer(source)) {
                assertTrue(demuxer.isSeekable());
                demuxer.seek(500_000);
                var packets = new ArrayList<Packet>();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    packets.add(packet);
                }
                var video = packets.stream().filter(packet -> packet.trackId() == 2).toList();
                var audio = packets.stream().filter(packet -> packet.trackId() == 1).toList();
                assertEquals(400, video.getFirst().pts(), "the key frame of frame 10");
                assertTrue(video.getFirst().keyframe());
                assertEquals(480, audio.getFirst().pts(), "the last audio object at or before 500 ms");
                assertEquals(30, video.size());
                demuxer.seek(10_000_000);
                assertNull(demuxer.read());
                demuxer.seek(-5);
                assertEquals(readAll(file).size(), countRemaining(demuxer));
            }
        }

        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.EnumSource(AsfWriter.Layout.class)
        @DisplayName("a seek to the end of every track leaves nothing to read")
        void end(AsfWriter.Layout layout) throws ReflectiveOperationException {
            var file = twoStreams(layout, 8).build();
            try (var source = MediaSource.of(file); var demuxer = new AsfDemuxer(source)) {
                demuxer.seek(1_600_000);
                var packet = demuxer.read();
                var field = AsfDemuxer.class.getDeclaredField("trackEnds");
                field.setAccessible(true);
                var ends = (long[]) field.get(demuxer);
                assertNull(packet, "track ends " + ends[1] + ", " + ends[2] + ", read " + packet);
                demuxer.seek(1_559_000);
                var video = new ArrayList<Long>();
                for (var next = demuxer.read(); next != null; next = demuxer.read()) {
                    assertEquals(2, next.trackId(), "audio ends at 1540 ms");
                    video.add(next.pts());
                }
                assertEquals(1200, video.getFirst(), "the key frame before the last frame");
                assertEquals(10, video.size());
            }
        }

        private static int countRemaining(AsfDemuxer demuxer) {
            var count = 0;
            while (demuxer.read() != null) {
                count++;
            }
            return count;
        }
    }

    @Nested
    @DisplayName("invalid input")
    class Invalid {
        @Test
        @DisplayName("a file protected by content encryption or with an encrypted stream is unsupported")
        void protectedContent() {
            var writer = twoStreams(AsfWriter.Layout.MULTIPLE, 9);
            writer.extraObjects.add(AsfWriter.object(AsfGuid.CONTENT_ENCRYPTION, new byte[16]));
            assertThrows(MediaException.Unsupported.class, () -> new AsfDemuxer(MediaSource.of(writer.build())));
            var encrypted = twoStreams(AsfWriter.Layout.MULTIPLE, 9);
            encrypted.streams.getFirst().encrypted = true;
            assertThrows(MediaException.Unsupported.class, () -> new AsfDemuxer(MediaSource.of(encrypted.build())));
        }

        @Test
        @DisplayName("a truncated header, a missing data object or a variable packet size is malformed")
        void malformed() {
            var file = twoStreams(AsfWriter.Layout.MULTIPLE, 10).build();
            for (var length : new int[]{0, 10, 29, 100, 400}) {
                var cut = Arrays.copyOf(file, length);
                assertThrows(MediaException.Malformed.class, () -> new AsfDemuxer(MediaSource.of(cut)), "cut at "
                        + length);
            }
            var wrong = file.clone();
            wrong[0] ^= 1;
            assertThrows(MediaException.Malformed.class, () -> new AsfDemuxer(MediaSource.of(wrong)));
        }

        @Test
        @DisplayName("a file cut inside the data object gives the whole packets before the cut, then ends")
        void truncatedData() {
            var writer = twoStreams(AsfWriter.Layout.MULTIPLE, 11);
            var file = writer.build();
            var full = readAll(file);
            var cut = Arrays.copyOf(file, file.length * 2 / 3);
            var partial = readAll(cut);
            assertTrue(partial.size() < full.size() && !partial.isEmpty());
            for (var index = 0; index < partial.size() - 1; index++) {
                assertArrayEquals(full.get(index).data(), partial.get(index).data());
            }
            // the object the input ends inside of comes with the bytes received
            var last = partial.getLast();
            var whole = full.get(partial.size() - 1).data();
            assertTrue(last.size() <= whole.length);
            assertArrayEquals(Arrays.copyOf(whole, last.size()), last.data());
        }

        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.EnumSource(AsfWriter.Layout.class)
        @DisplayName("a file cut inside its last data packet gives the payload bytes of that packet received")
        void truncatedLastPacket(AsfWriter.Layout layout) {
            var writer = twoStreams(layout, 14);
            writer.simpleIndex = false;
            var file = writer.build();
            var full = readAll(file);
            var packetStart = file.length - writer.packetSize;
            var before = totalBytes(readAll(Arrays.copyOf(file, packetStart)));
            var previous = before;
            for (var length = packetStart + 1; length < file.length; length += 23) {
                var partial = readAll(Arrays.copyOf(file, length));
                for (var index = 0; index < partial.size(); index++) {
                    var packet = partial.get(index);
                    var whole = full.stream().filter(candidate -> candidate.trackId() == packet.trackId()
                            && candidate.pts() == packet.pts()).findFirst().orElseThrow().data();
                    assertArrayEquals(Arrays.copyOf(whole, packet.size()), packet.data(), "cut at " + length);
                }
                var total = totalBytes(partial);
                assertTrue(total >= previous, "cut at " + length);
                previous = total;
            }
            assertTrue(previous > before, "the last packet adds the bytes received");
            assertEquals(totalBytes(full), totalBytes(readAll(file)));
        }

        private static long totalBytes(List<Packet> packets) {
            return packets.stream().mapToLong(Packet::size).sum();
        }

        @Test
        @DisplayName("random corruption raises only MediaException")
        void corrupt() {
            var file = twoStreams(AsfWriter.Layout.COMPRESSED, 12).build();
            var random = new Random(13);
            for (var round = 0; round < 300; round++) {
                var damaged = file.clone();
                for (var hit = 0; hit < 1 + random.nextInt(8); hit++) {
                    damaged[random.nextInt(damaged.length)] = (byte) random.nextInt(256);
                }
                try {
                    readAll(damaged);
                } catch (MediaException expected) {
                    // corrupt input fails cleanly
                }
            }
        }
    }
}

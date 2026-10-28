package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.internal.SegmentSource;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.github.auties00.smalt.container.packaging.Presentations.Input;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packages an encode with a key frame every half second into 2-second segments with trick play: HLS I-frame
 * playlists over transport stream and CMAF segments (one, byte-range and indexed files), and DASH trick-play
 * adaptation sets. The I-frame byte ranges are checked by the HLS validator and read back through the HLS demuxer,
 * which must give exactly the input's key frames at their times; the trick-play representation must hold every key
 * frame and nothing else, each lasting until the next.
 */
@DisplayName("Packager trick play")
class PackagerTrickPlayTest {
    private static Path input() {
        return Presentations.video("320x240", "400k", 15);
    }

    private static PackagingResult pack(Path directory, PackagingOptions preset) {
        return Presentations.pack(directory, preset.toBuilder().segmentMicros(2_000_000).trickPlay(true).build(),
                List.of(new Input(input(), builder -> builder.name("main"))));
    }

    /** Returns the key frames of the input's video. */
    private static Presentations.Stream keyFrames() {
        var video = Presentations.source(input(), TrackKind.VIDEO);
        return new Presentations.Stream(video.track(), video.packets().stream().filter(Packet::keyframe).toList());
    }

    @Nested
    @DisplayName("HLS I-frame playlists")
    class IFrames {
        @Test
        @DisplayName("address every key frame of the transport stream segments, behind the program tables")
        void transportStream() {
            var directory = Presentations.directory("trick-ts");
            var result = pack(directory, PackagingOptions.hls());
            var master = directory.resolve(result.masterPlaylist().orElseThrow());
            Validators.assertValidHls(master);
            var text = Presentations.text(master);
            assertTrue(Pattern.compile("#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=\\d+,AVERAGE-BANDWIDTH=\\d+,"
                    + "CODECS=\"avc1\\.6400[0-9a-f]{2}\",RESOLUTION=320x240,URI=\"main_iframes\\.m3u8\"").matcher(text)
                    .find(), text);
            assertEquals(List.of("main_iframes.m3u8"), result.streams().stream()
                    .flatMap(stream -> stream.iFramePlaylist().stream()).toList());
            var playlist = Presentations.text(directory.resolve("main_iframes.m3u8"));
            assertTrue(playlist.contains("#EXT-X-VERSION:5\n"), playlist);
            assertTrue(playlist.contains("#EXT-X-I-FRAMES-ONLY\n#EXT-X-MAP:URI=\"main_00001.ts\",BYTERANGE=\"376@0\"\n"),
                    playlist);
            var keyFrames = keyFrames();
            assertEquals(12, keyFrames.packets().size(), "the input has a key frame every half second");
            var durations = Pattern.compile("#EXTINF:([0-9.]+),").matcher(playlist);
            var total = 0.0;
            var entries = 0;
            while (durations.find()) {
                assertEquals(0.5, Double.parseDouble(durations.group(1)), 0.001, "each I-frame lasts until the next");
                total += Double.parseDouble(durations.group(1));
                entries++;
            }
            assertEquals(12, entries, playlist);
            assertEquals(6.0, total, 0.05);
            assertReadBack(directory.resolve("main_iframes.m3u8"), keyFrames, false);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"segments", "byte-ranges", "indexed"})
        @DisplayName("address the key frame that starts each CMAF segment, from its movie fragment to its end")
        void cmaf(String layout) {
            var directory = Presentations.directory("trick-cmaf-" + layout);
            var preset = switch (layout) {
                case "segments" -> PackagingOptions.hlsCmaf();
                case "byte-ranges" -> PackagingOptions.hlsCmaf().toBuilder().byteRanges(true).build();
                default -> PackagingOptions.cmaf().toBuilder().byteRanges(true).segmentIndex(true).build();
            };
            var result = pack(directory, preset);
            var master = directory.resolve(result.masterPlaylist().orElseThrow());
            Validators.assertValidHls(master);
            var playlist = Presentations.text(directory.resolve("main_video_iframes.m3u8"));
            assertTrue(playlist.contains("#EXT-X-I-FRAMES-ONLY\n#EXT-X-MAP:URI=\"main_video"), playlist);
            var ranges = Pattern.compile("#EXT-X-BYTERANGE:(\\d+)@(\\d+)\n([^\n]+)").matcher(playlist);
            var keyFrames = keyFrames();
            var segmentStarts = new ArrayList<Packet>();
            for (var index = 0; index < keyFrames.packets().size(); index += 4) {
                segmentStarts.add(keyFrames.packets().get(index));
            }
            var count = 0;
            while (ranges.find()) {
                var file = Presentations.read(directory.resolve(ranges.group(3)));
                var offset = Integer.parseInt(ranges.group(2));
                var length = Integer.parseInt(ranges.group(1));
                var type = new String(file, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                assertTrue(type.equals("moof") || type.equals("styp"), "the range starts with " + type);
                // the range ends with the key frame, the first sample of the fragment's mdat
                var frame = segmentStarts.get(count).data();
                assertArrayEquals(frame, java.util.Arrays.copyOfRange(file, offset + length - frame.length,
                        offset + length), "range " + count + " ends with its key frame");
                count++;
            }
            assertEquals(3, count, playlist);
            assertReadBack(directory.resolve("main_video_iframes.m3u8"),
                    new Presentations.Stream(keyFrames.track(), segmentStarts), true);
        }

        @Test
        @DisplayName("read a key frame whose track fragment has a base data offset and first-sample and sample flags")
        void foreignFragment() {
            var directory = Presentations.directory("trick-cmaf-foreign");
            pack(directory, PackagingOptions.hlsCmaf());
            var playlist = Presentations.text(directory.resolve("main_video_iframes.m3u8"));
            var range = Pattern.compile("#EXT-X-BYTERANGE:(\\d+)@(\\d+)\n([^\n]+)").matcher(playlist);
            assertTrue(range.find(), playlist);
            var file = Presentations.read(directory.resolve(range.group(3)));
            var offset = Integer.parseInt(range.group(2));
            var end = offset + Integer.parseInt(range.group(1));
            var init = Presentations.read(directory.resolve("main_video_init.mp4"));
            var expected = keyFrame(init, file, offset, end - offset);

            var moof = offset;
            while (!new String(file, moof + 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("moof")) {
                moof += (int) u32(file, moof);
            }
            var moofSize = (int) u32(file, moof);
            var traf = child(file, moof + 8, moof + moofSize, "traf");
            var trafEnd = traf + (int) u32(file, traf);
            var tfhd = child(file, traf + 8, trafEnd, "tfhd");
            var trun = child(file, traf + 8, trafEnd, "trun");
            var tfhdFlags = (int) u32(file, tfhd + 8) & 0xFFFFFF;
            var trunFlags = (int) u32(file, trun + 8) & 0xFFFFFF;
            assertTrue((tfhdFlags & 0x020000) != 0 && (trunFlags & 0x01) != 0, "the muxer counts data from the moof");
            var sampleStart = moof + (int) u32(file, trun + 16);
            // the track fragment header gives the file offset of the first sample instead
            var newTfhd = new java.io.ByteArrayOutputStream();
            writeBox(newTfhd, "tfhd", ((tfhdFlags | 0x01) & ~0x020000), java.util.Arrays.copyOfRange(file, tfhd + 12,
                    tfhd + 16), new byte[8], java.util.Arrays.copyOfRange(file, tfhd + 16, tfhd + (int) u32(file, tfhd)));
            // the run gives the first sample's flags and every sample's own, all marked non-sync, and offsets
            var count = (int) u32(file, trun + 12);
            var at = trun + 16 + ((trunFlags & 0x01) != 0 ? 4 : 0) + ((trunFlags & 0x04) != 0 ? 4 : 0);
            var newFlags = trunFlags | 0x01 | 0x04 | 0x400 | 0x800;
            var entries = new java.io.ByteArrayOutputStream();
            entries.writeBytes(new byte[]{0, 0, 0, 0, 2, 0, 0, 0});
            for (var sample = 0; sample < count; sample++) {
                if ((trunFlags & 0x100) != 0) {
                    entries.writeBytes(java.util.Arrays.copyOfRange(file, at, at + 4));
                    at += 4;
                }
                if ((trunFlags & 0x200) != 0) {
                    entries.writeBytes(java.util.Arrays.copyOfRange(file, at, at + 4));
                    at += 4;
                }
                if ((trunFlags & 0x400) != 0) {
                    at += 4;
                }
                entries.writeBytes(new byte[]{0, 1, 0, 0});
                if ((trunFlags & 0x800) != 0) {
                    entries.writeBytes(java.util.Arrays.copyOfRange(file, at, at + 4));
                    at += 4;
                } else {
                    entries.writeBytes(new byte[4]);
                }
            }
            var newTrun = new java.io.ByteArrayOutputStream();
            writeBox(newTrun, "trun", (file[trun + 8] & 0xFF) << 24 | newFlags, java.util.Arrays.copyOfRange(file,
                    trun + 12, trun + 16), entries.toByteArray());
            var trafBody = new java.io.ByteArrayOutputStream();
            for (var box = traf + 8; box < trafEnd; box += (int) u32(file, box)) {
                trafBody.writeBytes(box == tfhd ? newTfhd.toByteArray() : box == trun ? newTrun.toByteArray()
                        : java.util.Arrays.copyOfRange(file, box, box + (int) u32(file, box)));
            }
            var moofBody = new java.io.ByteArrayOutputStream();
            for (var box = moof + 8; box < moof + moofSize; box += (int) u32(file, box)) {
                if (box == traf) {
                    writeBox(moofBody, "traf", -1, trafBody.toByteArray());
                } else {
                    moofBody.writeBytes(java.util.Arrays.copyOfRange(file, box, box + (int) u32(file, box)));
                }
            }
            var newMoof = new java.io.ByteArrayOutputStream();
            writeBox(newMoof, "moof", -1, moofBody.toByteArray());
            var moofBytes = newMoof.toByteArray();
            // the range sits behind other bytes of its file, which the base data offset counts
            var lead = 37;
            var prefix = java.util.Arrays.copyOfRange(file, offset, moof);
            var rest = java.util.Arrays.copyOfRange(file, moof + moofSize, end);
            var base = lead + prefix.length + moofBytes.length + (sampleStart - moof - moofSize);
            var tfhdAt = indexOf(moofBytes, "tfhd") - 4;
            for (var index = 0; index < 8; index++) {
                moofBytes[tfhdAt + 23 - index] = (byte) ((long) base >>> (8 * index));
            }
            var foreign = new java.io.ByteArrayOutputStream();
            foreign.writeBytes(new byte[lead]);
            foreign.writeBytes(prefix);
            foreign.writeBytes(moofBytes);
            foreign.writeBytes(rest);
            var bytes = foreign.toByteArray();
            var actual = keyFrame(init, bytes, lead, bytes.length - lead);
            assertArrayEquals(expected.data(), actual.data());
            assertEquals(expected.pts(), actual.pts());
            assertEquals(expected.dts(), actual.dts());
            assertTrue(actual.keyframe());
        }

        @Test
        @DisplayName("reject a key frame range whose decode time box is cut short")
        void truncatedFragment() {
            var directory = Presentations.directory("trick-cmaf-truncated");
            pack(directory, PackagingOptions.hlsCmaf());
            var init = Presentations.read(directory.resolve("main_video_init.mp4"));
            var traf = new java.io.ByteArrayOutputStream();
            writeBox(traf, "tfhd", 0x020000, new byte[]{0, 0, 0, 1});
            writeBox(traf, "trun", 0, new byte[4]);
            traf.writeBytes(new byte[]{0, 0, 0, 8, 't', 'f', 'd', 't'});
            var trafBox = new java.io.ByteArrayOutputStream();
            writeBox(trafBox, "traf", -1, traf.toByteArray());
            var moof = new java.io.ByteArrayOutputStream();
            writeBox(moof, "moof", -1, trafBox.toByteArray());
            var bytes = moof.toByteArray();
            assertThrows(com.github.auties00.smalt.MediaException.Malformed.class,
                    () -> keyFrame(init, bytes, 0, bytes.length));
        }

        @Test
        @DisplayName("cannot address the key frames of AES-128 segments")
        void aes128() {
            var encryption = PackagingEncryption.forAes128(new byte[16], "key.bin");
            assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder().encryption(encryption)
                    .trickPlay(true).build());
        }
    }

    @Nested
    @DisplayName("DASH trick-play adaptation sets")
    class TrickMode {
        @Test
        @DisplayName("hold every key frame, each lasting until the next, and name the video adaptation set")
        void adaptationSet() {
            var directory = Presentations.directory("trick-dash");
            var result = pack(directory, PackagingOptions.cmaf());
            var manifest = directory.resolve(result.manifest().orElseThrow());
            Validators.assertValidDash(manifest);
            var mpd = Presentations.text(manifest);
            assertTrue(mpd.contains("<EssentialProperty schemeIdUri=\"http://dashif.org/guidelines/trickmode\" "
                    + "value=\"0\"/>"), mpd);
            assertTrue(Pattern.compile("<Representation id=\"main_trick\" bandwidth=\"\\d+\" codecs=\"avc1\\.6400"
                    + "[0-9a-f]{2}\" width=\"320\" height=\"240\" maxPlayoutRate=\"15\" codingDependency=\"false\">")
                    .matcher(mpd).find(), mpd);
            assertTrue(result.streams().stream().anyMatch(PackagedStream::trickPlay));
            assertFalse(Files.isRegularFile(directory.resolve("main_trick.m3u8")), "HLS does not list the stream");
            var parts = new ArrayList<SegmentSource.Part>();
            parts.add(SegmentSource.Part.of("main_trick_init.mp4"));
            for (var index = 1; Files.isRegularFile(directory.resolve(String.format("main_trick_%05d.m4s", index)));
                 index++) {
                parts.add(SegmentSource.Part.of(String.format("main_trick_%05d.m4s", index)));
            }
            var keyFrames = keyFrames();
            try (var source = new SegmentSource(parts, DataReferenceResolver.directory(directory),
                    com.github.auties00.smalt.MediaLimits.DEFAULT);
                 var demuxer = new Mp4Demuxer(source)) {
                var read = Presentations.streams(demuxer).getFirst();
                assertTrue(read.packets().stream().allMatch(Packet::keyframe));
                Presentations.assertSamePackets("trick play", keyFrames, read, read.startMicros()
                        - keyFrames.startMicros(), true);
                for (var index = 0; index + 1 < read.packets().size(); index++) {
                    assertEquals(read.track().timeBase().toMicros(read.packets().get(index).duration()), 500_000,
                            read.track().timeBase().toMicros(1) + 1, "key frame " + index + " lasts until the next");
                }
            }
            try (var demuxer = Presentations.dash(manifest)) {
                assertEquals(1, demuxer.tracks().stream().filter(track -> track.kind() == TrackKind.VIDEO).count(),
                        "the DASH demuxer leaves the trick-play adaptation set out");
            }
        }
    }

    /** Reads the key frame of one I-frame byte range of a file behind an initialization section. */
    private static Packet keyFrame(byte[] init, byte[] file, int offset, int length) {
        var files = java.util.Map.of("init.mp4", init, "segment.m4s", file);
        DataReferenceResolver resolver = reference -> java.util.Optional.ofNullable(files.get(reference.location()))
                .map(com.github.auties00.smalt.io.MediaSource::of);
        var frames = new com.github.auties00.smalt.container.internal.FragmentKeyFrames(SegmentSource.Part.of("init.mp4"),
                List.of(new SegmentSource.Part("segment.m4s", offset, length, null, null)), resolver,
                com.github.auties00.smalt.container.DemuxerOptions.defaults());
        try (frames) {
            var packet = frames.read();
            assertTrue(packet != null && frames.read() == null, "one key frame per range");
            return packet;
        }
    }

    /** Writes a box: a full box with its version and flags, or a plain box when they are -1. */
    private static void writeBox(java.io.ByteArrayOutputStream out, String type, int versionAndFlags, byte[]... parts) {
        var size = 8 + (versionAndFlags == -1 ? 0 : 4);
        for (var part : parts) {
            size += part.length;
        }
        out.writeBytes(new byte[]{(byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size});
        out.writeBytes(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        if (versionAndFlags != -1) {
            out.writeBytes(new byte[]{(byte) (versionAndFlags >>> 24), (byte) (versionAndFlags >>> 16),
                    (byte) (versionAndFlags >>> 8), (byte) versionAndFlags});
        }
        for (var part : parts) {
            out.writeBytes(part);
        }
    }

    private static long u32(byte[] data, int at) {
        return (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8 | data[at + 3] & 0xFFL;
    }

    /** Returns the offset of the first child box of a type among siblings. */
    private static int child(byte[] data, int from, int to, String type) {
        for (var at = from; at + 8 <= to; at += (int) u32(data, at)) {
            if (new String(data, at + 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals(type)) {
                return at;
            }
        }
        throw new AssertionError("no " + type + " box");
    }

    private static int indexOf(byte[] data, String type) {
        var bytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (var at = 0; at + 4 <= data.length; at++) {
            if (java.util.Arrays.equals(data, at, at + 4, bytes, 0, 4)) {
                return at;
            }
        }
        throw new AssertionError("no " + type);
    }

    /** Reads an I-frame playlist back and compares its video with the expected key frames. */
    private static void assertReadBack(Path playlist, Presentations.Stream expected, boolean sameBytes) {
        try (var demuxer = Presentations.hls(playlist)) {
            var video = Presentations.streams(demuxer).stream().filter(stream -> stream.track().kind() == TrackKind.VIDEO)
                    .findFirst().orElseThrow();
            assertTrue(video.packets().stream().allMatch(Packet::keyframe));
            Presentations.assertSamePackets("I-frames of " + playlist.getFileName(), expected, video,
                    video.startMicros() - expected.startMicros(), sameBytes);
        }
    }
}

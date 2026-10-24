package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagMode;
import com.github.auties00.smalt.metadata.TrackTags;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.track.TrackDisposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how the tags of an input are mapped to the output of a conversion: chapters cut to the kept range, track
 * tags following their tracks, the merge rules, the removal of stale tags, and the lenient handover to a muxer.
 */
@DisplayName("TagCarry")
class TagCarryTest {
    private static final TagCarry.Mapping WHOLE = new TagCarry.Mapping(Map.of(1, 1, 2, 2), Set.of(), false, false, 0,
            -1);

    private static MediaTags input() {
        return MediaTags.builder()
                .set(StandardTag.TITLE, "Title")
                .set(StandardTag.ARTIST, "One", "Two")
                .set(StandardTag.ALBUM, "Album")
                .set(StandardTag.ENCODER, "Encoder 1.0")
                .set(StandardTag.REPLAYGAIN_TRACK_GAIN, "-3.00 dB")
                .set("MOOD", "calm")
                .addPicture(AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "front", new byte[]{1, 2}))
                .chapters(List.of(Chapter.of(0, 1_000_000, "A"), Chapter.of(1_000_000, 2_000_000, "B"),
                        Chapter.of(2_000_000, 3_000_000, "C")))
                .track(new TrackTags(1, MediaTags.builder().set(StandardTag.TITLE, "Video").set("BPS", "1000")
                        .set("DURATION", "00:00:03.000000000").set("_STATISTICS_TAGS", "BPS DURATION OTHER")
                        .set("OTHER", "1").set(StandardTag.ENCODER, "x").set("KEEP", "yes").build(),
                        Set.of(TrackDisposition.DEFAULT)))
                .track(new TrackTags(2, MediaTags.builder().set(StandardTag.TITLE, "Audio")
                        .set(StandardTag.REPLAYGAIN_TRACK_PEAK, "0.9").build(), Set.of(TrackDisposition.COMMENTARY)))
                .track(new TrackTags(7, MediaTags.builder().set(StandardTag.TITLE, "Dropped").build(), Set.of()))
                .build();
    }

    @Nested
    @DisplayName("chapters")
    class Chapters {
        @ParameterizedTest(name = "[{0}, {1}) in [{2}, {3}) -> {4}")
        @CsvSource(nullValues = "null", value = {
                "0, 1000, 0, -1, 0:1000",
                "0, 1000, 500, -1, 0:500",
                "0, 1000, 1000, -1, null",
                "1000, 2000, 500, 1500, 500:1000",
                "1500, 2500, 500, 1500, null",
                "0, 5000, 1000, 2000, 0:1000",
                "700, 700, 500, 1500, 200:200",
                "500, 500, 500, 1500, 0:0",
                "1500, 1500, 500, 1500, null",
                "200, 200, 500, -1, null"})
        @DisplayName("are cut to the kept range and moved to its start")
        void cut(long start, long end, long rangeStart, long rangeEnd, String expected) {
            var cut = TagCarry.cut(Chapter.of(start, end, "x"), rangeStart, rangeEnd);
            if (expected == null) {
                assertNull(cut);
            } else {
                var parts = expected.split(":");
                assertEquals(Chapter.of(Long.parseLong(parts[0]), Long.parseLong(parts[1]), "x"), cut);
            }
        }

        @Test
        @DisplayName("of the whole input follow a trimmed transcode")
        void resolved() {
            var mapping = new TagCarry.Mapping(Map.of(1, 1), Set.of(1), false, false, 500_000, 2_500_000);
            var tags = TagCarry.resolve(TagMode.COPY, null, input(), mapping);
            assertEquals(List.of(Chapter.of(0, 500_000, "A"), Chapter.of(500_000, 1_500_000, "B"),
                    Chapter.of(1_500_000, 2_000_000, "C")), tags.chapters());
        }
    }

    @Nested
    @DisplayName("modes")
    class Modes {
        @Test
        @DisplayName("COPY keeps every tag and maps the track tags to the output tracks")
        void copy() {
            var mapping = new TagCarry.Mapping(Map.of(1, 2, 2, 1), Set.of(), false, false, 0, -1);
            var tags = TagCarry.resolve(TagMode.COPY, null, input(), mapping);
            assertEquals(input().entries(), tags.entries());
            assertEquals(input().pictures(), tags.pictures());
            assertEquals(input().chapters(), tags.chapters());
            assertEquals(2, tags.tracks().size());
            assertEquals("Video", tags.track(2).orElseThrow().name().orElseThrow());
            assertEquals(Set.of(TrackDisposition.DEFAULT), tags.track(2).orElseThrow().disposition());
            assertEquals("Audio", tags.track(1).orElseThrow().name().orElseThrow());
            assertTrue(tags.track(7).isEmpty());
        }

        @Test
        @DisplayName("DROP writes nothing")
        void drop() {
            assertTrue(TagCarry.resolve(TagMode.DROP, null, null, WHOLE).isEmpty());
        }

        @Test
        @DisplayName("REPLACE writes the given tags, mapped")
        void replace() {
            var given = MediaTags.builder().set(StandardTag.TITLE, "Given")
                    .chapters(List.of(Chapter.of(0, 4_000_000, "Only")))
                    .track(TrackTags.of(2, "Renamed")).track(TrackTags.of(9, "Missing")).build();
            var mapping = new TagCarry.Mapping(Map.of(2, 1), Set.of(), true, true, 1_000_000, -1);
            var tags = TagCarry.resolve(TagMode.REPLACE, given, null, mapping);
            assertEquals(given.entries(), tags.entries());
            assertEquals(List.of(Chapter.of(0, 3_000_000, "Only")), tags.chapters());
            assertEquals(List.of(TrackTags.of(1, "Renamed")), tags.tracks());
        }

        @Test
        @DisplayName("MERGE replaces the given keys, removes the empty ones and keeps the rest")
        void merge() {
            var picture = AttachedPicture.of(AttachedPicture.Type.BACK_COVER, "image/jpeg", "", new byte[]{9});
            var given = MediaTags.builder()
                    .set(StandardTag.TITLE, "New Title")
                    .set(StandardTag.ARTIST, "")
                    .add("GENRE", "Rock").add("GENRE", "Pop")
                    .addPicture(picture)
                    .track(new TrackTags(2, MediaTags.builder().set(StandardTag.TITLE, "Narration").build(), Set.of()))
                    .track(new TrackTags(1, MediaTags.builder().set("KEEP", "").build(),
                            Set.of(TrackDisposition.FORCED)))
                    .build();
            var tags = TagCarry.resolve(TagMode.MERGE, given, input(), WHOLE);
            assertEquals(List.of("New Title"), tags.getAll(StandardTag.TITLE));
            assertTrue(tags.getAll(StandardTag.ARTIST).isEmpty());
            assertEquals(List.of("Album"), tags.getAll(StandardTag.ALBUM));
            assertEquals(List.of("Rock", "Pop"), tags.getAll(StandardTag.GENRE));
            assertEquals(List.of("calm"), tags.getAll("MOOD"));
            assertEquals(List.of(picture), tags.pictures());
            assertEquals(input().chapters(), tags.chapters());
            var audio = tags.track(2).orElseThrow();
            assertEquals("Narration", audio.name().orElseThrow());
            assertEquals(List.of("0.9"), audio.tags().getAll(StandardTag.REPLAYGAIN_TRACK_PEAK));
            assertEquals(Set.of(TrackDisposition.COMMENTARY), audio.disposition());
            var video = tags.track(1).orElseThrow();
            assertTrue(video.tags().getAll("KEEP").isEmpty());
            assertEquals("Video", video.name().orElseThrow());
            assertEquals(Set.of(TrackDisposition.FORCED), video.disposition());
        }
    }

    @Nested
    @DisplayName("stale tags")
    class Stale {
        @Test
        @DisplayName("are dropped from encoded and trimmed streams only")
        void statistics() {
            var mapping = new TagCarry.Mapping(Map.of(1, 1, 2, 2), Set.of(1), true, false, 0, -1);
            var tags = TagCarry.resolve(TagMode.COPY, null, input(), mapping);
            var video = tags.track(1).orElseThrow().tags();
            assertEquals(List.of("title", "keep"), video.keys().stream().map(String::toLowerCase).toList());
            assertTrue(tags.get(StandardTag.ENCODER).isEmpty());
            assertEquals(List.of("-3.00 dB"), tags.getAll(StandardTag.REPLAYGAIN_TRACK_GAIN));
            assertEquals(List.of("0.9"), tags.track(2).orElseThrow().tags().getAll(StandardTag.REPLAYGAIN_TRACK_PEAK));
            var copied = TagCarry.resolve(TagMode.COPY, null, input(), WHOLE);
            assertEquals(input().track(1).orElseThrow().tags(), copied.track(1).orElseThrow().tags());
            assertEquals(List.of("Encoder 1.0"), copied.getAll(StandardTag.ENCODER));
        }

        @Test
        @DisplayName("ReplayGain is dropped when an encoded stream changes its level")
        void replayGain() {
            var mapping = new TagCarry.Mapping(Map.of(1, 1, 2, 2), Set.of(2), true, true, 0, -1);
            var tags = TagCarry.resolve(TagMode.COPY, null, input(), mapping);
            assertTrue(tags.get(StandardTag.REPLAYGAIN_TRACK_GAIN).isEmpty());
            assertTrue(tags.track(2).orElseThrow().tags().get(StandardTag.REPLAYGAIN_TRACK_PEAK).isEmpty());
            var merged = TagCarry.resolve(TagMode.MERGE, MediaTags.builder()
                    .set(StandardTag.REPLAYGAIN_TRACK_GAIN, "-1.00 dB").set(StandardTag.ENCODER, "Mine").build(),
                    input(), mapping);
            assertEquals(List.of("-1.00 dB"), merged.getAll(StandardTag.REPLAYGAIN_TRACK_GAIN));
            assertEquals(List.of("Mine"), merged.getAll(StandardTag.ENCODER));
        }
    }

    @Nested
    @DisplayName("embedded comments")
    class Embedded {
        private static byte[] block(int type, boolean last, int length, int fill) {
            var block = new byte[4 + length];
            block[0] = (byte) (type | (last ? 0x80 : 0));
            block[1] = (byte) (length >> 16);
            block[2] = (byte) (length >> 8);
            block[3] = (byte) length;
            Arrays.fill(block, 4, block.length, (byte) fill);
            return block;
        }

        private static byte[] concat(byte[]... parts) {
            var out = new ByteArrayOutputStream();
            for (var part : parts) {
                out.writeBytes(part);
            }
            return out.toByteArray();
        }

        private static final byte[] MARKER = "fLaC".getBytes(StandardCharsets.US_ASCII);

        @Test
        @DisplayName("FLAC loses its comment and picture blocks and keeps the others, the last one flagged")
        void flac() {
            var config = concat(MARKER, block(0, false, 34, 1), block(4, false, 20, 2), block(2, false, 8, 3),
                    block(6, true, 40, 4));
            var reduced = TagCarry.flacWithoutTags(config);
            assertArrayEquals(concat(MARKER, block(0, false, 34, 1), block(2, true, 8, 3)), reduced);
            var track = AudioTrack.builder().id(1).codec(Codec.FLAC).timeBase(Rational.timeBaseOf(44_100))
                    .sampleRate(44_100).codecConfig(config).build();
            assertArrayEquals(reduced, TagCarry.withoutEmbeddedTags(track).codecConfig().orElseThrow());
            var plain = concat(MARKER, block(0, true, 34, 1));
            assertNull(TagCarry.flacWithoutTags(plain));
            assertNull(TagCarry.flacWithoutTags(Arrays.copyOf(config, config.length - 3)));
            assertNull(TagCarry.flacWithoutTags(new byte[]{1, 2, 3}));
        }

        @Test
        @DisplayName("Vorbis keeps its vendor string and no comment")
        void vorbis() {
            var identification = new byte[]{1, 'v', 'o', 'r', 'b', 'i', 's', 9, 9};
            var setup = new byte[]{5, 'v', 'o', 'r', 'b', 'i', 's', 7};
            var vendor = "Vendor".getBytes(StandardCharsets.US_ASCII);
            var field = "TITLE=x".getBytes(StandardCharsets.US_ASCII);
            var comment = concat(new byte[]{3, 'v', 'o', 'r', 'b', 'i', 's'}, le(vendor.length), vendor, le(1),
                    le(field.length), field, new byte[]{1});
            var reduced = TagCarry.vorbisWithoutTags(XiphLacing.lace(identification, comment, setup));
            var packets = XiphLacing.unlace(reduced);
            assertArrayEquals(identification, packets[0]);
            assertArrayEquals(concat(new byte[]{3, 'v', 'o', 'r', 'b', 'i', 's'}, le(vendor.length), vendor, le(0),
                    new byte[]{1}), packets[1]);
            assertArrayEquals(setup, packets[2]);
            assertNull(TagCarry.vorbisWithoutTags(reduced));
            assertNull(TagCarry.vorbisWithoutTags(new byte[]{2, 1}));
        }

        private static byte[] le(int value) {
            return new byte[]{(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
        }
    }

    @Nested
    @DisplayName("handover to a muxer")
    class Apply {
        private static final PcmPackets TONE = PcmPackets.tone(Codec.PCM_S16LE, 8000, ChannelLayout.MONO, 800);

        private Mp4Muxer muxer() {
            return muxer(MediaSink.memory());
        }

        private Mp4Muxer muxer(MediaSink sink) {
            var muxer = new Mp4Muxer(sink);
            muxer.addTrack(TONE.track());
            return muxer;
        }

        @Test
        @DisplayName("leaves out input values the container rejects")
        void lenient() {
            var picture = AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "", new byte[]{1, 2, 3});
            var tags = MediaTags.builder().set(StandardTag.TITLE, "Kept").set(StandardTag.BPM, "fast")
                    .set(StandardTag.ALBUM, "Album").addPicture(picture)
                    .track(new TrackTags(TONE.track().id(), MediaTags.builder().set(StandardTag.TITLE, "Tone")
                            .set(StandardTag.BPM, "slow").build(), Set.of())).build();
            var sink = MediaSink.memory();
            try (var muxer = muxer(sink)) {
                assertThrows(MediaException.Unsupported.class, () -> muxer.setTags(tags));
                TagCarry.apply(muxer, tags, TagMode.COPY, null);
                TONE.packets().forEach(muxer::writePacket);
                muxer.finish();
            }
            var read = TagEditor.read(MediaSource.of(sink.toByteArray()));
            assertEquals(List.of("Kept"), read.getAll(StandardTag.TITLE));
            assertEquals(List.of("Album"), read.getAll(StandardTag.ALBUM));
            assertTrue(read.get(StandardTag.BPM).isEmpty());
            assertEquals(1, read.pictures().size());
            assertEquals(1, read.tracks().size(), read.tracks().toString());
            var track = read.tracks().getFirst();
            assertEquals("Tone", track.name().orElseThrow());
            assertTrue(track.tags().get(StandardTag.BPM).isEmpty());
        }

        @Test
        @DisplayName("fails on a rejected value the options give")
        void strict() {
            var given = MediaTags.builder().set(StandardTag.BPM, "fast").build();
            try (var muxer = muxer()) {
                assertThrows(MediaException.Unsupported.class, () -> TagCarry.apply(muxer, given, TagMode.REPLACE, given));
                assertThrows(MediaException.Unsupported.class, () -> TagCarry.apply(muxer, given, TagMode.MERGE, given));
            }
        }
    }
}

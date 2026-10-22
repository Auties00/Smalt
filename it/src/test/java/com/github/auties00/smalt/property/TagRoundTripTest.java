package com.github.auties00.smalt.property;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.transcode.OutputContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tag editing round trips, for every container {@link TagEditor} writes: every standard tag the format's tag system
 * stores (with non-ASCII, multi-valued and long values), a custom key and a front cover are read back as written;
 * writing what was read back changes nothing (the edit is idempotent); clearing the tags leaves none; and through
 * every edit the media packets stay byte for byte those of the original file.
 */
@DisplayName("Tag edits")
class TagRoundTripTest {
    record Base(String name, OutputContainer container, Codec codec, ContainerFormat format) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Base> bases() {
        return Stream.of(
                new Base("M4A AAC", OutputContainer.M4A, Codec.AAC, ContainerFormat.MP4),
                new Base("MP4 Opus", OutputContainer.MP4, Codec.OPUS, ContainerFormat.MP4),
                new Base("Matroska Opus", OutputContainer.MATROSKA, Codec.OPUS, ContainerFormat.MATROSKA),
                new Base("WebM Vorbis", OutputContainer.WEBM, Codec.VORBIS, ContainerFormat.MATROSKA),
                new Base("Ogg Opus", OutputContainer.OGG, Codec.OPUS, ContainerFormat.OGG),
                new Base("Ogg Vorbis", OutputContainer.OGG, Codec.VORBIS, ContainerFormat.OGG),
                new Base("Ogg FLAC", OutputContainer.OGG_FLAC, Codec.FLAC, ContainerFormat.OGG),
                new Base("FLAC", OutputContainer.FLAC, Codec.FLAC, ContainerFormat.FLAC),
                new Base("MP3", OutputContainer.MP3, Codec.MP3, ContainerFormat.MPEG_AUDIO),
                new Base("ADTS", OutputContainer.ADTS, Codec.AAC, ContainerFormat.ADTS),
                new Base("WAV", OutputContainer.WAV, Codec.PCM_S16LE, ContainerFormat.WAV),
                new Base("AIFF", OutputContainer.AIFF, Codec.PCM_S16BE, ContainerFormat.AIFF),
                new Base("AC-3", OutputContainer.AC3, Codec.AC3, ContainerFormat.AC3));
    }

    static Stream<Arguments> cases() {
        var arguments = new ArrayList<Arguments>();
        bases().forEach(base -> {
            arguments.add(Arguments.of(base, false));
            arguments.add(Arguments.of(base, true));
        });
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}, cover {1}")
    @MethodSource("cases")
    @DisplayName("reads back what it wrote, is idempotent, clears, and never touches the media")
    void roundTrip(Base base, boolean cover) {
        var original = file(base);
        assertTrue(TagEditor.isSupported(base.format()), base + " is not tag-editable");
        var packets = packets(original);
        var tags = tags(base, cover);
        var options = TagWriteOptions.defaults();

        var written = TagEditor.write(original, tags, options);
        assertSamePackets(base + " after writing", packets, packets(written));
        var read = TagEditor.read(MediaSource.of(written));
        for (var tag : StandardTag.values()) {
            if (stores(base.format(), tag)) {
                assertEquals(tags.get(tag), read.get(tag), base + " " + tag);
            }
        }
        assertEquals(List.of("first artist", "second artist"), values(read, StandardTag.ARTIST), base + " artists");
        assertEquals(tags.get(StandardTag.LYRICS).map(String::length), read.get(StandardTag.LYRICS).map(String::length),
                base + " long lyrics");
        if (cover) {
            assertEquals(1, read.pictures().size(), base + " pictures");
            assertArrayEquals(tags.pictures().getFirst().data(), read.pictures().getFirst().data(), base + " cover");
        }

        var rewritten = TagEditor.write(written, read, options);
        var reread = TagEditor.read(MediaSource.of(rewritten));
        assertEquals(read.entries(), reread.entries(), base + " entries after rewriting what was read");
        assertEquals(read.pictures().size(), reread.pictures().size(), base + " pictures after rewriting");
        assertSamePackets(base + " after rewriting", packets, packets(rewritten));

        var cleared = TagEditor.write(written, MediaTags.empty(), options);
        var empty = TagEditor.read(MediaSource.of(cleared));
        for (var tag : StandardTag.values()) {
            assertEquals(java.util.Optional.empty(), empty.get(tag), base + " " + tag + " after clearing");
        }
        assertEquals(0, empty.pictures().size(), base + " pictures after clearing");
        assertSamePackets(base + " after clearing", packets, packets(cleared));
    }

    /** Returns whether the tag system a container is written with stores a standard tag natively. */
    static boolean stores(ContainerFormat format, StandardTag tag) {
        return switch (format) {
            case MP4 -> tag.mp4Atom().isPresent();
            case MATROSKA, OGG, FLAC -> true;
            case WAV -> tag.riffInfo().isPresent() || tag.id3Frame().isPresent();
            default -> tag.id3Frame().isPresent();
        };
    }

    static MediaTags tags(Base base, boolean cover) {
        var builder = MediaTags.builder();
        for (var tag : StandardTag.values()) {
            var value = switch (tag) {
                case TRACK -> "3/12";
                case DISC -> "1/2";
                case DATE -> "2021-07-04";
                case COMPILATION -> "1";
                case CREATION_TIME -> "2021-07-04T10:00:00.000000Z";
                case LANGUAGE -> "ita";
                case LYRICS -> "la la ".repeat(2000) + "fine";
                default -> tag.key() + " välüë ✓ 漢字";
            };
            builder.set(tag, value);
        }
        builder.set(StandardTag.ARTIST, "first artist", "second artist");
        builder.set("smalt_custom", "custom value");
        if (cover) {
            var png = com.github.auties00.smalt.image.Images.encode(
                    com.github.auties00.smalt.image.RgbaPicture.allocate(4, 3, false),
                    com.github.auties00.smalt.image.ImageFormat.PNG);
            builder.addPicture(AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "cover", png));
        }
        return builder.build();
    }

    private static List<String> values(MediaTags tags, StandardTag tag) {
        var values = new ArrayList<String>();
        for (var entry : tags.entries()) {
            if (StandardTag.fromName(entry.key()).orElse(null) == tag) {
                values.add(entry.value());
            }
        }
        return values;
    }

    static byte[] file(Base base) {
        var stream = Catalog.stream(base.codec());
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(base.container(), sink)) {
            muxer.addTrack(stream.track());
            stream.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        var data = sink.toByteArray();
        com.github.auties00.smalt.testkit.validate.Validators.assertValid("tags-" + base.container().name().toLowerCase(java.util.Locale.ROOT) + "."
                + base.container().extension(), data);
        return data;
    }

    static List<byte[]> packets(byte[] file) {
        var packets = new ArrayList<byte[]>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            for (Packet packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet.data());
            }
        }
        return packets;
    }

    static void assertSamePackets(String what, List<byte[]> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size(), what + " packet count");
        for (var index = 0; index < expected.size(); index++) {
            assertTrue(Arrays.equals(expected.get(index), actual.get(index)), what + " packet " + index);
        }
    }
}

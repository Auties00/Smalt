package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.testkit.audio.AudioMutations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the APEv1/APEv2 codec against tags laid out by hand from the APEv2 specification: header
 * and footer parsing, item flags and types, multi-value UTF-8 text, cover art items, the byte
 * layout of written tags, and robustness against corrupt input.
 */
class ApeTagTest {
    /** A tiny PNG signature followed by filler, enough for format sniffing. */
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 1, 2, 3};

    static byte[] item(String key, int flags, byte[] value) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(value.length).putInt(flags).array());
        output.writeBytes(key.getBytes(StandardCharsets.ISO_8859_1));
        output.write(0);
        output.writeBytes(value);
        return output.toByteArray();
    }

    static byte[] text(String key, String value) {
        return item(key, 0, value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] footer(int version, int size, int count, int flags) {
        return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).put("APETAGEX".getBytes(StandardCharsets.US_ASCII))
                .putInt(version).putInt(size).putInt(count).putInt(flags).putLong(0).array();
    }

    /** Builds a tag: optional header, items, footer; {@code tagFlags} excludes the header bits. */
    static byte[] tag(int version, boolean header, int tagFlags, byte[]... items) {
        var body = new ByteArrayOutputStream();
        for (var item : items) {
            body.writeBytes(item);
        }
        var size = body.size() + 32;
        var output = new ByteArrayOutputStream();
        var flags = tagFlags | (header ? 0x80000000 : 0);
        if (header) {
            output.writeBytes(footer(version, size, items.length, flags | 0x20000000));
        }
        output.writeBytes(body.toByteArray());
        output.writeBytes(footer(version, size, items.length, flags));
        return output.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        var output = new ByteArrayOutputStream();
        for (var part : parts) {
            output.writeBytes(part);
        }
        return output.toByteArray();
    }

    @Nested
    @DisplayName("reading")
    class Reading {
        @Test
        @DisplayName("decodes a footer-only APEv2 tag with UTF-8 multi-value items")
        void footerOnly() {
            var parsed = ApeTag.parse(tag(2000, false, 0, text("Title", "Tèst 中"), text("Artist", "One\u0000Two"),
                    text("Year", "2019"), text("Track", "3/9"), text("Album Artist", "AA"), text("REPLAYGAIN_TRACK_GAIN",
                            "-1.00 dB"), text("Encoded By", "me")));
            assertEquals(2000, parsed.version());
            var tags = parsed.decode();
            var media = MediaTags.builder();
            tags.entries().forEach(entry -> media.add(entry.key(), entry.value()));
            var built = media.build();
            assertEquals("Tèst 中", built.get(StandardTag.TITLE).orElseThrow());
            assertEquals(List.of("One", "Two"), built.getAll(StandardTag.ARTIST));
            assertEquals("2019", built.get(StandardTag.DATE).orElseThrow());
            assertEquals("3/9", built.get(StandardTag.TRACK).orElseThrow());
            assertEquals("AA", built.get(StandardTag.ALBUM_ARTIST).orElseThrow());
            assertEquals("me", built.get(StandardTag.ENCODED_BY).orElseThrow());
            assertEquals("-1.00 dB", built.get("REPLAYGAIN_TRACK_GAIN").orElseThrow());
            assertTrue(tags.opaque().isEmpty());
            assertEquals("One; Two", parsed.fields().get("artist"));
        }

        @Test
        @DisplayName("reads the same items from a tag with header and footer, and reports the read-only flag")
        void headerAndFooter() {
            var withHeader = ApeTag.parse(tag(2000, true, 1, text("Title", "x"), text("Album", "y")));
            assertTrue(withHeader.isReadOnly());
            assertEquals(List.of("Title", "Album"), withHeader.items().stream().map(ApeTag.Item::key).toList());
            var plain = ApeTag.parse(tag(2000, false, 0, text("Title", "x"), text("Album", "y")));
            assertFalse(plain.isReadOnly());
            assertEquals(withHeader.decode().entries(), plain.decode().entries());
        }

        @Test
        @DisplayName("decodes APEv1 items as text whatever their flags, falling back to ISO-8859-1")
        void version1() {
            var latin = new byte[]{'C', 'a', 'f', (byte) 0xE9};
            var parsed = ApeTag.parse(tag(1000, false, 0, item("Title", 2, latin), item("Artist", 0,
                    "naïve".getBytes(StandardCharsets.UTF_8))));
            assertEquals(1000, parsed.version());
            var entries = parsed.decode().entries();
            assertEquals(List.of(new MediaTags.Entry("title", "Café"), new MediaTags.Entry("artist", "naïve")),
                    entries);
            assertTrue(parsed.items().stream().allMatch(item -> item.flags() == 0));
        }

        @Test
        @DisplayName("turns cover art items into pictures and keeps other binary and locator items opaque")
        void coverArt() {
            var parsed = ApeTag.parse(tag(2000, true, 0,
                    item("Cover Art (Front)", 2, concat("front.png\0".getBytes(StandardCharsets.UTF_8), PNG)),
                    item("cover art (back)", 2, concat("\0".getBytes(StandardCharsets.UTF_8), PNG)),
                    item("Cover Art (Publisher Logotype)", 2, PNG),
                    item("Cover Art (Unknown)", 2, PNG),
                    item("Cover Art (Media)", 2, "only-a-name\0".getBytes(StandardCharsets.UTF_8)),
                    item("Private", 2, new byte[]{1, 2, 3}),
                    item("Link", 4, "http://example.com".getBytes(StandardCharsets.UTF_8)),
                    item("Reserved", 6, new byte[]{9}),
                    item("Bad=Key", 0, "v".getBytes(StandardCharsets.UTF_8))));
            var content = parsed.decode();
            assertEquals(3, content.pictures().size());
            var front = content.pictures().get(0);
            assertEquals(AttachedPicture.Type.FRONT_COVER, front.type());
            assertEquals("front", front.description());
            assertEquals("image/png", front.mediaType());
            assertArrayEquals(PNG, front.data());
            assertEquals(AttachedPicture.Type.BACK_COVER, content.pictures().get(1).type());
            assertEquals("", content.pictures().get(1).description());
            assertEquals(AttachedPicture.Type.PUBLISHER_LOGOTYPE, content.pictures().get(2).type());
            assertArrayEquals(PNG, content.pictures().get(2).data());
            assertEquals(List.of("Cover Art (Unknown)", "Cover Art (Media)", "Private", "Link", "Reserved", "Bad=Key"),
                    content.opaque().stream().map(ApeTag.Item::key).toList());
            assertEquals(List.of(ApeTag.BINARY, ApeTag.BINARY, ApeTag.BINARY, ApeTag.LOCATOR, 3, ApeTag.TEXT),
                    content.opaque().stream().map(ApeTag.Item::type).toList());
            assertTrue(content.entries().isEmpty());
        }

        @Test
        @DisplayName("keeps the items before one whose sizes run past the tag")
        void truncatedItem() {
            var good = text("Title", "kept");
            var bad = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1 << 20).putInt(0).array();
            var body = concat(good, bad, "Artist\0x".getBytes(StandardCharsets.US_ASCII));
            var tag = concat(body, footer(2000, body.length + 32, 2, 0));
            assertEquals(List.of(new MediaTags.Entry("title", "kept")), ApeTag.parse(tag).decode().entries());
            var unterminated = concat(good, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(0).array(),
                    "A".repeat(300).getBytes(StandardCharsets.US_ASCII));
            var second = concat(unterminated, footer(2000, unterminated.length + 32, 2, 0));
            assertEquals(1, ApeTag.parse(second).items().size());
        }

        @Test
        @DisplayName("stops at the item count of the footer")
        void itemCount() {
            var body = concat(text("Title", "a"), text("Album", "b"));
            var tag = concat(body, footer(2000, body.length + 32, 1, 0));
            assertEquals(1, ApeTag.parse(tag).items().size());
        }

        @Test
        @DisplayName("rejects a missing footer and a size larger than the tag")
        void malformed() {
            assertThrows(MediaException.Malformed.class, () -> ApeTag.parse(new byte[40]));
            var tag = tag(2000, false, 0, text("Title", "a"));
            ByteBuffer.wrap(tag).order(ByteOrder.LITTLE_ENDIAN).putInt(tag.length - 32 + 12, tag.length + 1);
            assertThrows(MediaException.Malformed.class, () -> ApeTag.parse(tag));
        }

        @Test
        @DisplayName("resists random mutations")
        void mutations() {
            var inputs = List.of(tag(2000, true, 0, text("Title", "Title"), text("Artist", "a\u0000b"),
                    item("Cover Art (Front)", 2, concat("f.png\0".getBytes(StandardCharsets.UTF_8), PNG))),
                    tag(1000, false, 0, text("Album", "x")));
            AudioMutations.assertRobust(inputs, 400, 0xA9EL, Duration.ofSeconds(2), bytes -> {
                var parsed = ApeTag.parse(bytes);
                parsed.decode();
                parsed.fields();
                ApeTag.encode(MediaTags.empty(), parsed.decode().opaque());
            });
        }
    }

    @Nested
    @DisplayName("writing")
    class Writing {
        @Test
        @DisplayName("lays out an APEv2 tag with header, items and footer as the specification defines")
        void layout() {
            var tags = MediaTags.builder()
                    .set(StandardTag.TITLE, "T€")
                    .set(StandardTag.ARTIST, "A", "B")
                    .set("MY KEY", "v")
                    .addPicture(AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "f", PNG))
                    .build();
            var expected = tag(2000, true, 0,
                    item("Title", 0, "T€".getBytes(StandardCharsets.UTF_8)),
                    item("Artist", 0, "A\0B".getBytes(StandardCharsets.UTF_8)),
                    item("MY KEY", 0, "v".getBytes(StandardCharsets.UTF_8)),
                    item("Cover Art (Front)", 2, concat("f.png\0".getBytes(StandardCharsets.UTF_8), PNG)));
            assertArrayEquals(expected, ApeTag.encode(tags, List.of()));
        }

        @Test
        @DisplayName("names every standard tag by its usual APE key and reads it back")
        void standardKeys() {
            var builder = MediaTags.builder();
            for (var tag : StandardTag.values()) {
                builder.set(tag, "value of " + tag.key());
            }
            var tags = builder.build();
            var encoded = ApeTag.encode(tags, List.of());
            var keys = ApeTag.parse(encoded).items().stream().map(ApeTag.Item::key).toList();
            assertEquals(List.of("Title", "Artist", "Album", "Album Artist", "Composer", "Conductor", "Lyricist", "Genre",
                    "Year", "Track", "Disc", "Comment", "DESCRIPTION", "Lyrics", "Copyright", "Encoder", "Encoded By",
                    "Publisher", "Language", "Grouping", "CREATION_TIME", "Compilation", "BPM", "ISRC", "TITLESORT",
                    "ARTISTSORT", "ALBUMSORT", "ALBUMARTISTSORT", "COMPOSERSORT", "REPLAYGAIN_TRACK_GAIN",
                    "REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_ALBUM_PEAK", "MUSICBRAINZ_TRACKID",
                    "MUSICBRAINZ_RELEASETRACKID", "MUSICBRAINZ_ALBUMID", "MUSICBRAINZ_ARTISTID",
                    "MUSICBRAINZ_ALBUMARTISTID", "MUSICBRAINZ_RELEASEGROUPID", "MUSICBRAINZ_WORKID", "MUSICBRAINZ_DISCID",
                    "RATING"), keys);
            assertEquals(tags.entries(), ApeTag.parse(encoded).decode().entries());
        }

        @Test
        @DisplayName("leaves out keys APE cannot store and second pictures of one type")
        void mirrorLimits() {
            var tags = MediaTags.builder()
                    .set("x", "one-character key")
                    .set("ID3", "reserved")
                    .set("oggs", "reserved in any case")
                    .set("Kéy", "not ASCII")
                    .set("k".repeat(256), "too long")
                    .set("Fine Key", "kept")
                    .addPicture(AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "", PNG))
                    .addPicture(AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "second", PNG))
                    .build();
            var parsed = ApeTag.parse(ApeTag.encode(tags, List.of()));
            assertEquals(List.of("Fine Key", "Cover Art (Front)"), parsed.items().stream().map(ApeTag.Item::key).toList());
            assertArrayEquals(concat("Cover Art (Front).png\0".getBytes(StandardCharsets.UTF_8), PNG),
                    parsed.items().get(1).value());
            assertEquals("", parsed.decode().pictures().getFirst().description());
            assertEquals(0, ApeTag.encode(MediaTags.builder().set("x", "y").build(), List.of()).length);
            var colliding = MediaTags.builder().set("cover art (front)", "text under a cover key")
                    .addPicture(AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "", PNG)).build();
            assertEquals(List.of("Cover Art (Front)"), ApeTag.parse(ApeTag.encode(colliding, List.of())).items().stream()
                    .map(ApeTag.Item::key).toList(), "no key repeats");
        }

        @Test
        @DisplayName("carries preserved items over unless a written item uses their key")
        void preserved() {
            var preserved = List.of(new ApeTag.Item("Private", 2, new byte[]{1, 2}), new ApeTag.Item("title", 2, new byte[]{3}),
                    new ApeTag.Item("Cover Art (Front)", 2, PNG), new ApeTag.Item("Link", 5, "u".getBytes(StandardCharsets.UTF_8)));
            var tags = MediaTags.builder().set(StandardTag.TITLE, "t")
                    .addPicture(AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "", "f", PNG)).build();
            var parsed = ApeTag.parse(ApeTag.encode(tags, preserved));
            assertEquals(List.of("Title", "Private", "Link", "Cover Art (Front)"),
                    parsed.items().stream().map(ApeTag.Item::key).toList());
            assertEquals(5, parsed.items().get(2).flags());
            assertArrayEquals(new byte[]{1, 2}, parsed.items().get(1).value());
            var onlyPreserved = ApeTag.parse(ApeTag.encode(MediaTags.empty(), preserved));
            assertEquals(4, onlyPreserved.items().size());
        }

        @Test
        @DisplayName("round trips tags and pictures")
        void roundTrip() {
            var tags = MediaTags.builder()
                    .set(StandardTag.TITLE, "Title è中")
                    .set(StandardTag.GENRE, "Rock", "Jazz")
                    .set(StandardTag.DATE, "2024-05-01")
                    .set("REPLAYGAIN_ALBUM_PEAK", "0.98")
                    .addPicture(AttachedPicture.of(AttachedPicture.Type.BACK_COVER, "image/png", "back.png", PNG))
                    .addPicture(AttachedPicture.of(AttachedPicture.Type.MEDIA, "image/png", "", PNG))
                    .addPicture(AttachedPicture.of(AttachedPicture.Type.ARTIST, "image/png", "artist", PNG))
                    .build();
            var content = ApeTag.parse(ApeTag.encode(tags, List.of())).decode();
            assertEquals(tags.entries(), content.entries());
            assertEquals(tags.pictures(), content.pictures());
        }
    }
}

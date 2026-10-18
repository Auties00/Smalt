package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the ID3v2 codec on hand-built tags: the storage forms of versions 2.2 to 2.4
 * (unsynchronization, compression, data length indicators, extended headers), the frames it maps
 * to tags, and hostile sizes.
 */
@DisplayName("Id3v2Tag")
class Id3v2TagTest {
    /** Builds a tag from frames, each given as id, flags and payload. */
    static byte[] tag(int major, int flags, byte[]... frames) {
        var body = new ByteArrayOutputStream();
        for (var frame : frames) {
            body.writeBytes(frame);
        }
        var data = body.toByteArray();
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'I', 'D', '3', (byte) major, 0, (byte) flags});
        out.writeBytes(syncsafe(data.length));
        out.writeBytes(data);
        return out.toByteArray();
    }

    static byte[] frame(int major, String id, int flags, byte[] payload) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        if (major == 2) {
            out.write(payload.length >>> 16);
            out.write(payload.length >>> 8);
            out.write(payload.length);
        } else {
            out.writeBytes(major == 4 ? syncsafe(payload.length) : new byte[]{(byte) (payload.length >>> 24),
                    (byte) (payload.length >>> 16), (byte) (payload.length >>> 8), (byte) payload.length});
            out.write(flags >>> 8);
            out.write(flags);
        }
        out.writeBytes(payload);
        return out.toByteArray();
    }

    static byte[] text(String value) {
        var bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        var out = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, out, 1, bytes.length);
        return out;
    }

    static byte[] syncsafe(int value) {
        return new byte[]{(byte) ((value >>> 21) & 0x7F), (byte) ((value >>> 14) & 0x7F), (byte) ((value >>> 7) & 0x7F),
                (byte) (value & 0x7F)};
    }

    static byte[] unsynchronize(byte[] data) {
        var out = new ByteArrayOutputStream();
        for (var i = 0; i < data.length; i++) {
            out.write(data[i]);
            if ((data[i] & 0xFF) == 0xFF && (i + 1 == data.length || (data[i + 1] & 0xE0) == 0xE0 || data[i + 1] == 0)) {
                out.write(0);
            }
        }
        return out.toByteArray();
    }

    static MediaTags decode(byte[] tag) {
        return Id3v2Tag.parse(tag, MediaLimits.DEFAULT).decode().tags();
    }

    @Nested
    @DisplayName("storage forms")
    class Storage {
        @Test
        @DisplayName("reverses whole-tag unsynchronization of version 2.3")
        void unsync23() {
            var title = "\u00FF\u00E0 y\u00FF";
            var frames = frame(3, "TIT2", 0, text(title));
            var tag = tag(3, 0x80, unsynchronize(frames));
            assertEquals(title, decode(tag).get(StandardTag.TITLE).orElseThrow());
        }

        @Test
        @DisplayName("reverses per-frame unsynchronization and skips the data length indicator of version 2.4")
        void unsync24() {
            var payload = text("\u00FF\u00FFz");
            var stored = unsynchronize(payload);
            var withLength = new byte[4 + stored.length];
            System.arraycopy(syncsafe(payload.length), 0, withLength, 0, 4);
            System.arraycopy(stored, 0, withLength, 4, stored.length);
            var tag = tag(4, 0, frame(4, "TIT2", 0x0003, withLength));
            assertEquals("\u00FF\u00FFz", decode(tag).get(StandardTag.TITLE).orElseThrow());
        }

        @ParameterizedTest(name = "version 2.{0}")
        @ValueSource(ints = {3, 4})
        @DisplayName("inflates compressed frames")
        void compressed(int major) {
            var payload = text("compressed title");
            var deflater = new Deflater();
            deflater.setInput(payload);
            deflater.finish();
            var buffer = new byte[256];
            var length = deflater.deflate(buffer);
            var out = new ByteArrayOutputStream();
            out.writeBytes(major == 4 ? syncsafe(payload.length) : new byte[]{0, 0, 0, (byte) payload.length});
            out.write(buffer, 0, length);
            var flags = major == 4 ? 0x0009 : 0x0080;
            var tag = tag(major, 0, frame(major, "TIT2", flags, out.toByteArray()));
            assertEquals("compressed title", decode(tag).get(StandardTag.TITLE).orElseThrow());
        }

        @Test
        @DisplayName("reads version 2.2 frames and pictures")
        void version22() {
            var picture = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
            var pic = new ByteArrayOutputStream();
            pic.write(0);
            pic.writeBytes("PNG".getBytes(StandardCharsets.ISO_8859_1));
            pic.write(3);
            pic.writeBytes("cover\0".getBytes(StandardCharsets.ISO_8859_1));
            pic.writeBytes(picture);
            var tag = tag(2, 0, frame(2, "TT2", 0, text("Old")), frame(2, "TP1", 0, text("Artist")),
                    frame(2, "PIC", 0, pic.toByteArray()));
            var tags = decode(tag);
            assertEquals("Old", tags.get(StandardTag.TITLE).orElseThrow());
            assertEquals("Artist", tags.get(StandardTag.ARTIST).orElseThrow());
            assertEquals("image/png", tags.pictures().getFirst().mediaType());
            assertArrayEquals(picture, tags.pictures().getFirst().data());
        }

        @Test
        @DisplayName("skips an extended header")
        void extendedHeader() {
            var frames = frame(3, "TIT2", 0, text("After"));
            var extended = new byte[]{0, 0, 0, 6, 0, 0, 0, 0, 0, 0};
            var body = new byte[extended.length + frames.length];
            System.arraycopy(extended, 0, body, 0, extended.length);
            System.arraycopy(frames, 0, body, extended.length, frames.length);
            assertEquals("After", decode(tag(3, 0x40, body)).get(StandardTag.TITLE).orElseThrow());
        }
    }

    @Nested
    @DisplayName("frames")
    class Frames {
        @Test
        @DisplayName("expands genre references and composes version 2.3 dates")
        void genresAndDates() {
            var tag = tag(3, 0, frame(3, "TCON", 0, text("(17)(26)Eurodisco")), frame(3, "TYER", 0, text("2020")),
                    frame(3, "TDAT", 0, text("0105")), frame(3, "TIME", 0, text("1230")));
            var tags = decode(tag);
            assertEquals(List.of("Rock", "Ambient", "Eurodisco"), tags.getAll(StandardTag.GENRE));
            assertEquals("2020-05-01T12:30", tags.get(StandardTag.DATE).orElseThrow());
            assertEquals("Psybient", Id3Genres.name(191));
        }

        @ParameterizedTest(name = "version 2.{0}")
        @ValueSource(ints = {3, 4})
        @DisplayName("round trips text, user text, URLs, comments, lyrics, pictures, chapters and opaque frames")
        void roundTrip(int major) {
            var opaque = frame(major, "PRIV", 0, "owner\0data".getBytes(StandardCharsets.ISO_8859_1));
            var parsed = Id3v2Tag.parse(tag(major, 0, opaque), MediaLimits.DEFAULT).decode();
            var tags = MediaTags.builder()
                    .set(StandardTag.TITLE, "T\u00EDtulo \u4E2D")
                    .set(StandardTag.DATE, "2024-05-01")
                    .set("TBPM", "120")
                    .set("WOAR", "https://example.com/")
                    .set("MusicBrainz Album Id", "abc")
                    .set(StandardTag.COMMENT, "comment")
                    .set(StandardTag.LYRICS, "lyrics")
                    .addPicture(AttachedPicture.of(AttachedPicture.Type.BACK_COVER, "image/png", "back \u00E8", new byte[]{1, 2}))
                    .chapters(List.of(Chapter.of(0, 1000_000, "One"), Chapter.of(1000_000, 2_000_000, null)))
                    .build();
            var encoded = Id3v2Tag.encode(tags, parsed.opaque(), null, major, 0, 64);
            var content = Id3v2Tag.parse(encoded, MediaLimits.DEFAULT).decode();
            var read = content.tags();
            assertEquals(tags.entries(), read.entries());
            assertEquals(tags.pictures(), read.pictures());
            assertEquals(tags.chapters(), read.chapters());
            assertEquals(1, content.opaque().size());
            assertEquals("PRIV", content.opaque().getFirst().id());
        }

        @Test
        @DisplayName("pads a tag to an exact size")
        void exactSize() {
            var tags = MediaTags.builder().set(StandardTag.TITLE, "x").build();
            assertEquals(1000, Id3v2Tag.encode(tags, List.of(), null, 4, 1000, 0).length);
        }
    }

    @Nested
    @DisplayName("hostile tags")
    class Hostile {
        @Test
        @DisplayName("stops at a frame that runs past the tag")
        void oversizedFrame() {
            var good = frame(3, "TIT2", 0, text("Kept"));
            var bad = new byte[]{'T', 'P', 'E', '1', 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0};
            var body = new byte[good.length + bad.length];
            System.arraycopy(good, 0, body, 0, good.length);
            System.arraycopy(bad, 0, body, good.length, bad.length);
            var tags = decode(tag(3, 0, body));
            assertEquals("Kept", tags.get(StandardTag.TITLE).orElseThrow());
            assertTrue(tags.get(StandardTag.ARTIST).isEmpty());
        }

        @Test
        @DisplayName("bounds decompression by the limits")
        void bomb() {
            var payload = new byte[4 << 20];
            var deflater = new Deflater(Deflater.BEST_COMPRESSION);
            deflater.setInput(payload);
            deflater.finish();
            var buffer = new byte[1 << 16];
            var length = deflater.deflate(buffer);
            var out = new ByteArrayOutputStream();
            out.writeBytes(new byte[]{0, (byte) 0x40, 0, 0});
            out.write(buffer, 0, length);
            var tag = tag(3, 0, frame(3, "TIT2", 0x0080, out.toByteArray()));
            assertThrows(MediaException.LimitExceeded.class, () -> Id3v2Tag.parse(tag, MediaLimits.builder()
                    .maxBytes(1 << 20).build()));
        }

        @Test
        @DisplayName("rejects a header that is not ID3v2")
        void header() {
            assertThrows(MediaException.Malformed.class, () -> Id3v2Tag.parse(new byte[]{'I', 'D', '3', 9, 0, 0, 0, 0, 0, 0},
                    MediaLimits.DEFAULT));
            assertEquals(-1, Id3v2Tag.size(new byte[]{'I', 'D', '3', 4, 0, 0, (byte) 0x80, 0, 0, 0}, 0));
        }
    }
}

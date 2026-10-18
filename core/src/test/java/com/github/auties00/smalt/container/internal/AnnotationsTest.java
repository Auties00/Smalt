package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.internal.tags.FlacPicture;
import com.github.auties00.smalt.internal.tags.VorbisComment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the chapter and picture collector the demuxers share, and the Vorbis comment decoding it
 * drives.
 */
class AnnotationsTest {
    static byte[] block(String vendor, long declaredCount, String... comments) {
        var output = new ByteArrayOutputStream();
        var vendorBytes = vendor.getBytes(StandardCharsets.UTF_8);
        output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(vendorBytes.length).array());
        output.writeBytes(vendorBytes);
        output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) declaredCount).array());
        for (var comment : comments) {
            var bytes = comment.getBytes(StandardCharsets.UTF_8);
            output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.length).array());
            output.writeBytes(bytes);
        }
        return output.toByteArray();
    }

    @Nested
    @DisplayName("chapter clocks")
    class Clocks {
        @ParameterizedTest
        @CsvSource({
                "00:00:00.000, 0",
                "00:01:02.500, 62500000",
                "1:2:3, 3723000000",
                "' 00:00:01.5 ', 1500000",
                "00:00:59.9999995, 60000000",
                "00:00:00.123456789, 123457",
                "123456:00:00, 444441600000000"
        })
        @DisplayName("parses well-formed clocks, rounding the fraction half up to microseconds")
        void parses(String clock, long micros) {
            assertEquals(micros, VorbisComment.parseClock(clock));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "00:00", "00:60:00", "00:00:60", "00:00:5E-5", "00:00:-1", "+1:00:00",
                "00:00:01.1234567891", "00:00:01.", "1234567:00:00", "aa:bb:cc"})
        @DisplayName("rejects malformed clocks")
        void rejects(String clock) {
            assertEquals(-1, VorbisComment.parseClock(clock));
            assertEquals(-1, VorbisComment.parseClock(null));
        }

        @Test
        @DisplayName("rejects an exponent clock in constant time instead of expanding it")
        void exponent() {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                assertEquals(-1, VorbisComment.parseClock("00:00:5E-500000000"));
                assertEquals(-1, VorbisComment.parseClock("00:00:1E+999999999"));
            });
        }

        @Test
        @DisplayName("drops a chapter whose clock is an exponent without hanging the demux path")
        void exponentChapter() {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                var annotations = new Annotations();
                assertTrue(annotations.acceptVorbisComment("CHAPTER001", "00:00:5E-500000000"));
                assertTrue(annotations.acceptVorbisComment("chapter002", "00:00:02.000"));
                assertTrue(annotations.acceptVorbisComment("CHAPTER002NAME", "Two"));
                assertEquals(List.of(Chapter.of(2_000_000, 5_000_000, "Two")), annotations.chapters(5_000_000));
            });
        }
    }

    @Nested
    @DisplayName("chapters")
    class Chapters {
        @Test
        @DisplayName("sorts by start and ends each open chapter at the next start or the duration")
        void resolvesEnds() {
            var annotations = new Annotations();
            annotations.addChapter(3_000_000, -1, "C");
            annotations.addChapter(0, 1_000_000, "A");
            annotations.addChapter(1_000_000, -1, "");
            annotations.addChapter(-5, -1, "ignored");
            assertEquals(List.of(Chapter.of(0, 1_000_000, "A"), Chapter.of(1_000_000, 3_000_000, null),
                    Chapter.of(3_000_000, 4_000_000, "C")), annotations.chapters(4_000_000));
            assertEquals(3_000_000, annotations.chapters(-1).getLast().endMicros());
        }
    }

    @Nested
    @DisplayName("Vorbis comments")
    class Comments {
        @Test
        @DisplayName("decodes comments, joins repeated names and skips pictures and nameless entries")
        void decode() {
            var data = block("vendor", 5, "TITLE=Tïtle", "ARTIST=a", "ARTIST=b", "METADATA_BLOCK_PICTURE=AAAA", "nothing");
            var metadata = new LinkedHashMap<String, String>();
            assertEquals(data.length, Annotations.readVorbisComments(data, 0, data.length, metadata, null));
            assertEquals(Map.of("TITLE", "Tïtle", "ARTIST", "a; b"), metadata);
        }

        @Test
        @DisplayName("routes chapters and pictures to the collector")
        void routes() {
            var picture = AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "cover", new byte[]{1, 2, 3});
            var encoded = Base64.getEncoder().encodeToString(FlacPicture.encode(picture));
            var data = block("vendor", 4, "TITLE=t", "CHAPTER001=00:00:01.000", "CHAPTER001NAME=One",
                    "METADATA_BLOCK_PICTURE=" + encoded);
            var metadata = new LinkedHashMap<String, String>();
            var annotations = new Annotations();
            Annotations.readVorbisComments(data, 0, data.length, metadata, annotations);
            assertEquals(Map.of("TITLE", "t"), metadata);
            assertEquals(List.of(Chapter.of(1_000_000, 2_000_000, "One")), annotations.chapters(2_000_000));
            assertEquals(1, annotations.pictures().size());
            assertEquals("cover", annotations.pictures().getFirst().description());
        }

        @Test
        @DisplayName("rejects counts and lengths that exceed the block")
        void rejects() {
            var tooMany = block("v", 10, "A=1");
            assertThrows(MediaException.Malformed.class, () -> Annotations.readVorbisComments(tooMany, 0, tooMany.length, new LinkedHashMap<>(), null));
            var longVendor = block("v", 0);
            longVendor[0] = 100;
            assertThrows(MediaException.Malformed.class, () -> Annotations.readVorbisComments(longVendor, 0, longVendor.length, new LinkedHashMap<>(), null));
            var truncated = block("v", 1, "ABC=1");
            assertThrows(MediaException.Malformed.class, () -> Annotations.readVorbisComments(truncated, 0, truncated.length - 1, new LinkedHashMap<>(), null));
            assertThrows(MediaException.Malformed.class, () -> Annotations.readVorbisComments(new byte[3], 0, 3, new LinkedHashMap<>(), null));
        }
    }
}

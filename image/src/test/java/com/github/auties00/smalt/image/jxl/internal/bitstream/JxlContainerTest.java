package com.github.auties00.smalt.image.jxl.internal.bitstream;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.JxlCodestream;
import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the {@code jxli} frame index and the {@code jhgm} gain map bundle: hand-made indexes, cjxl's index of the
 * {@code indexed.jxl} vector, and the golden bundles of libjxl's {@code gain_map_test.cc} (BSD-3-Clause).
 */
class JxlContainerTest {
    static byte[] resource(String name) throws IOException {
        try (var input = JxlContainerTest.class.getResourceAsStream("/com/github/auties00/smalt/image/jxl/features/"
                + name)) {
            return input.readAllBytes();
        }
    }

    @Nested
    @DisplayName("the jxli frame index")
    class FrameIndexes {
        static byte[] index(int... values) {
            var out = new ByteArrayOutputStream();
            out.write(values[0]);
            out.writeBytes(new byte[]{0, 0, 0, (byte) values[1], 0, 0, 3, (byte) 0xE8});
            for (var i = 2; i < values.length; i++) {
                var value = values[i];
                while (value > 127) {
                    out.write((value & 127) | 128);
                    value >>>= 7;
                }
                out.write(value);
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("parses offsets as deltas from the previous indexed frame")
        void parse() {
            var data = index(3, 1, 7, 0, 1, 300, 100, 3, 200, 300, 3);
            var index = JxlContainer.readFrameIndex(data, 0, data.length);
            assertNotNull(index);
            assertEquals(1, index.ticksNumerator());
            assertEquals(1000, index.ticksDenominator());
            assertEquals(List.of(7L, 307L, 507L), index.entries().stream().map(JxlContainer.FrameIndex.Entry::offset)
                    .toList());
            assertEquals(List.of(0L, 100L, 300L), index.entries().stream().map(JxlContainer.FrameIndex.Entry::ticks)
                    .toList());
        }

        @Test
        @DisplayName("rejects truncated or invalid indexes")
        void invalid() {
            for (var data : List.of(index(3, 1, 7, 0, 1, 300, 100), index(0, 1), index(2, 0, 7, 0, 1, 9, 1, 1),
                    index(2, 1, 7, 0, 1, 0, 1, 1), new byte[]{(byte) 0x80}, new byte[]{1, 0, 0, 0, 1})) {
                assertNull(JxlContainer.readFrameIndex(data, 0, data.length));
            }
            var tooLong = new byte[20];
            java.util.Arrays.fill(tooLong, (byte) 0xFF);
            assertNull(JxlContainer.readFrameIndex(tooLong, 0, tooLong.length));
        }

        @Test
        @DisplayName("cjxl's index points at the starts of the frames it lists")
        void cjxl() throws IOException {
            var data = resource("indexed.jxl");
            var container = JxlContainer.parse(data, MediaLimits.DEFAULT);
            var index = container.frameIndex();
            assertNotNull(index);
            var frames = JxlCodestream.open(container, MediaLimits.DEFAULT).scanFrames(100);
            assertEquals(8, frames.size());
            assertEquals(frames.stream().map(JxlCodestream.FrameStart::offset).toList(),
                    index.entries().stream().map(JxlContainer.FrameIndex.Entry::offset).toList());
        }

        @Test
        @DisplayName("an invalid index is left out without failing the file")
        void ignored() throws IOException {
            var codestream = resource("progressive.jxl");
            var bad = new byte[]{2, 0, 0, 0, 0, 0, 0, 3};
            var file = new ByteArrayOutputStream();
            file.writeBytes(new byte[]{0, 0, 0, 12, 'J', 'X', 'L', ' ', 13, 10, (byte) 0x87, 10});
            file.writeBytes(box("jxlc", codestream));
            file.writeBytes(box("jxli", bad));
            var container = JxlContainer.parse(file.toByteArray(), MediaLimits.DEFAULT);
            assertNull(container.frameIndex());
            assertEquals(List.of("jxli"), container.boxes().stream().map(JxlContainer.Box::type).toList());
        }
    }

    static byte[] box(String type, byte[] payload) {
        var out = new ByteArrayOutputStream();
        var size = 8 + payload.length;
        out.writeBytes(new byte[]{(byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size});
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    @Nested
    @DisplayName("the jhgm bundle")
    class Bundles {
        static final String METADATA = "placeholder gain map metadata, fill with actual example after (ISO 21496-1) "
                + "is finalized";

        static final String GAIN_MAP = "placeholder for an actual naked JPEG XL codestream";

        /** libjxl's GoldenTestGainMap(has_icc = false, has_color_encoding). */
        static byte[] golden(boolean colorEncoding) {
            var out = new ByteArrayOutputStream();
            out.write(0);
            out.writeBytes(new byte[]{0x00, 0x58});
            out.writeBytes(METADATA.getBytes(StandardCharsets.US_ASCII));
            out.write(colorEncoding ? 3 : 0);
            if (colorEncoding) {
                out.writeBytes(new byte[]{0x50, (byte) 0xB4, 0x00});
            }
            out.writeBytes(new byte[]{0, 0, 0, 0});
            out.writeBytes(GAIN_MAP.getBytes(StandardCharsets.US_ASCII));
            return out.toByteArray();
        }

        @Test
        @DisplayName("parses libjxl's golden bundle with a linear sRGB color encoding")
        void colorEncoding() {
            assertEquals(88, METADATA.length());
            var bundle = GainMapBundle.parse(golden(true), MediaLimits.DEFAULT);
            assertEquals(0, bundle.version());
            assertEquals(METADATA, new String(bundle.metadata(), StandardCharsets.US_ASCII));
            var encoding = bundle.colorEncoding();
            assertNotNull(encoding);
            assertFalse(encoding.wantIcc());
            assertEquals(ColorEncoding.RGB, encoding.colorSpace());
            assertEquals(ColorEncoding.WHITE_D65, encoding.whitePoint());
            assertEquals(ColorEncoding.PRIMARIES_SRGB, encoding.primaries());
            assertEquals(ColorEncoding.TF_LINEAR, encoding.transfer());
            assertNull(bundle.icc());
            assertEquals(GAIN_MAP, new String(bundle.codestream(), StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("parses libjxl's golden bundle without a color encoding")
        void noColorEncoding() {
            var bundle = GainMapBundle.parse(golden(false), MediaLimits.DEFAULT);
            assertNull(bundle.colorEncoding());
            assertEquals(GAIN_MAP, new String(bundle.codestream(), StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("rejects every truncation")
        void truncated() {
            var full = golden(true);
            var end = full.length - GAIN_MAP.length();
            for (var length = 0; length <= end; length++) {
                var cut = java.util.Arrays.copyOf(full, length);
                assertThrows(MediaException.Malformed.class, () -> GainMapBundle.parse(cut, MediaLimits.DEFAULT),
                        "length " + length);
            }
        }
    }
}

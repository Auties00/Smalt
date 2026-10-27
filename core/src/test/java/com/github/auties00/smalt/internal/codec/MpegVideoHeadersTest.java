package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses MPEG-1 and MPEG-2 video sequence headers written field by field from ISO/IEC 11172-2 2.4.2.3 and ISO/IEC
 * 13818-2 6.2.2: sizes with their extension bits, the aspect ratio and frame rate tables, quantizer matrices in zigzag
 * order, the sequence, display and scalable extensions, the colors derived from them, the configuration a key frame
 * carries, and the headers it rejects.
 */
@DisplayName("MPEG video sequence headers")
class MpegVideoHeadersTest {
    /** The fields of a sequence header and its extensions; {@code extension} false writes an MPEG-1 header. */
    static final class Header {
        int width = 720;
        int height = 576;
        int aspect = 2;
        int rate = 3;
        int bitRate = 15000;
        int vbv = 112;
        int[] intra;
        int[] nonIntra;
        boolean extension = true;
        int profileLevel = 0x48;
        boolean progressive;
        int chroma = 1;
        boolean lowDelay;
        int rateN;
        int rateD;
        boolean display;
        int videoFormat = 1;
        int primaries = -1;
        int transfer = -1;
        int matrix = -1;
        int displayWidth;
        int displayHeight;
        int scalableMode = -1;
        boolean userData;

        byte[] bytes() {
            var out = new ByteArrayOutputStream();
            var bits = new MsbBitWriter();
            bits.write(width & 0xFFF, 12);
            bits.write(height & 0xFFF, 12);
            bits.write(aspect, 4);
            bits.write(rate, 4);
            bits.write(bitRate & 0x3FFFF, 18);
            bits.writeBit(1);
            bits.write(vbv & 0x3FF, 10);
            bits.writeBit(0);
            matrix(bits, intra);
            matrix(bits, nonIntra);
            unit(out, 0xB3, bits);
            if (extension) {
                bits = new MsbBitWriter();
                bits.write(1, 4);
                bits.write(profileLevel, 8);
                bits.writeBit(progressive ? 1 : 0);
                bits.write(chroma, 2);
                bits.write(width >> 12, 2);
                bits.write(height >> 12, 2);
                bits.write(bitRate >> 18, 12);
                bits.writeBit(1);
                bits.write(vbv >> 10, 8);
                bits.writeBit(lowDelay ? 1 : 0);
                bits.write(rateN, 2);
                bits.write(rateD, 5);
                unit(out, 0xB5, bits);
            }
            if (display) {
                bits = new MsbBitWriter();
                bits.write(2, 4);
                bits.write(videoFormat, 3);
                bits.writeBit(primaries >= 0 ? 1 : 0);
                if (primaries >= 0) {
                    bits.write(primaries, 8);
                    bits.write(transfer, 8);
                    bits.write(matrix, 8);
                }
                bits.write(displayWidth, 14);
                bits.writeBit(1);
                bits.write(displayHeight, 14);
                unit(out, 0xB5, bits);
            }
            if (scalableMode >= 0) {
                bits = new MsbBitWriter();
                bits.write(5, 4);
                bits.write(scalableMode, 2);
                bits.write(1, 4);
                unit(out, 0xB5, bits);
            }
            if (userData) {
                out.writeBytes(new byte[]{0, 0, 1, (byte) 0xB2, 'G', 'A', '9', '4'});
            }
            return out.toByteArray();
        }

        private static void matrix(MsbBitWriter bits, int[] raster) {
            bits.writeBit(raster == null ? 0 : 1);
            if (raster != null) {
                var zigzag = MpegVideoHeaders.zigzag();
                for (var index = 0; index < 64; index++) {
                    bits.write(raster[zigzag[index]], 8);
                }
            }
        }

        private static void unit(ByteArrayOutputStream out, int code, MsbBitWriter bits) {
            bits.alignWithZeros();
            out.writeBytes(new byte[]{0, 0, 1, (byte) code});
            out.writeBytes(bits.toByteArray());
        }
    }

    /** A group of pictures header and an I-picture header, then a slice start code. */
    static byte[] picture(int type) {
        return new byte[]{0, 0, 1, (byte) 0xB8, 0, 8, 0, 0, 0, 0, 1, 0, 0, (byte) (type << 3), (byte) 0xFF, (byte) 0xF8,
                0, 0, 1, 1, 0x12};
    }

    static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    @Nested
    @DisplayName("MPEG-2")
    class Mpeg2 {
        @Test
        @DisplayName("reads every field of the header and its sequence extension")
        void fields() {
            var h = new Header();
            h.width = 0x2345;
            h.height = 0x1F10;
            h.bitRate = 0x3_FFFF | 0x5A5 << 18;
            h.vbv = 0x3FF | 0x7B << 10;
            h.profileLevel = 0x14;
            h.progressive = true;
            h.chroma = 2;
            h.lowDelay = true;
            h.rate = 5;
            h.rateN = 1;
            h.rateD = 2;
            var sequence = MpegVideoHeaders.parse(h.bytes(), 0, h.bytes().length);
            assertNotNull(sequence);
            assertTrue(sequence.mpeg2());
            assertEquals(0x2345, sequence.width());
            assertEquals(0x1F10, sequence.height());
            assertEquals(0x3_FFFF | 0x5A5L << 18, sequence.bitRate());
            assertEquals(0x3FF | 0x7B << 10, sequence.vbvBufferSize());
            assertTrue(sequence.progressive());
            assertTrue(sequence.lowDelay());
            assertEquals(ChromaFormat.YUV422, sequence.chroma());
            assertEquals("High", sequence.profile());
            assertEquals(4, sequence.level());
            // frame_rate_code 5 is 30 Hz; the extension scales it by (n + 1) / (d + 1)
            assertEquals(new Rational(20, 1), sequence.frameRate());
            assertEquals(-1, sequence.scalableMode());
            assertEquals(h.bytes().length, sequence.end());
        }

        @ParameterizedTest(name = "frame_rate_code {0}")
        @CsvSource({"1, 24000, 1001", "2, 24, 1", "3, 25, 1", "4, 30000, 1001", "5, 30, 1", "6, 50, 1",
                "7, 60000, 1001", "8, 60, 1"})
        @DisplayName("maps frame_rate_code to the rates of Table 6-4")
        void frameRates(int code, long numerator, long denominator) {
            var h = new Header();
            h.rate = code;
            assertEquals(new Rational(numerator, denominator).reduce(), MpegVideoHeaders.find(h.bytes()).frameRate());
        }

        @ParameterizedTest(name = "frame_rate_code {0}")
        @CsvSource({"0", "9", "15"})
        @DisplayName("has no frame rate for a forbidden or reserved code")
        void reservedRates(int code) {
            var h = new Header();
            h.rate = code;
            assertNull(MpegVideoHeaders.find(h.bytes()).frameRate());
        }

        @ParameterizedTest(name = "aspect {0} at {1}x{2}, display {3}x{4}")
        @CsvSource({"1, 720, 576, 0, 0, 1, 1", "2, 720, 576, 0, 0, 16, 15", "3, 720, 576, 0, 0, 64, 45",
                "4, 720, 480, 0, 0, 221, 150", "2, 720, 576, 704, 576, 12, 11", "3, 1440, 1080, 1920, 1080, 1, 1",
                "3, 1440, 1080, 0, 0, 4, 3"})
        @DisplayName("derives the sample aspect ratio from the display aspect ratio over the display size")
        void aspect(int code, int width, int height, int displayWidth, int displayHeight, long n, long d) {
            var h = new Header();
            h.aspect = code;
            h.width = width;
            h.height = height;
            if (displayWidth > 0) {
                h.display = true;
                h.displayWidth = displayWidth;
                h.displayHeight = displayHeight;
            }
            assertEquals(new Rational(n, d), MpegVideoHeaders.find(h.bytes()).sampleAspect());
        }

        @ParameterizedTest(name = "aspect_ratio_information {0}")
        @CsvSource({"0", "5", "15"})
        @DisplayName("has no sample aspect ratio for a forbidden or reserved code")
        void reservedAspect(int code) {
            var h = new Header();
            h.aspect = code;
            assertNull(MpegVideoHeaders.find(h.bytes()).sampleAspect());
        }

        @Test
        @DisplayName("reads the color description of the sequence display extension")
        void signaledColor() {
            var h = new Header();
            h.display = true;
            h.primaries = 1;
            h.transfer = 1;
            h.matrix = 1;
            h.displayWidth = 720;
            h.displayHeight = 576;
            var sequence = MpegVideoHeaders.find(h.bytes());
            var color = sequence.signaledColor();
            assertEquals(ColorPrimaries.BT709, color.primaries());
            assertEquals(TransferCharacteristics.BT709, color.transfer());
            assertEquals(MatrixCoefficients.BT709, color.matrix());
            assertEquals(ChromaSiting.LEFT, color.chromaSiting());
            assertEquals(color, sequence.color());
            assertEquals(1, sequence.videoFormat());
        }

        @Test
        @DisplayName("takes unspecified and missing colors as BT.601 up to 1024x576 and BT.709 above")
        void fallbackColor() {
            var h = new Header();
            assertNull(MpegVideoHeaders.find(h.bytes()).signaledColor());
            assertEquals(ColorInfo.BT601_LIMITED.withChromaSiting(ChromaSiting.LEFT),
                    MpegVideoHeaders.find(h.bytes()).color());
            h.width = 1920;
            h.height = 1080;
            assertEquals(ColorInfo.BT709_LIMITED.withChromaSiting(ChromaSiting.LEFT),
                    MpegVideoHeaders.find(h.bytes()).color());
            // unspecified primaries and transfer (2) with a BT.470 BG matrix (5)
            h.width = 720;
            h.height = 576;
            h.display = true;
            h.primaries = 2;
            h.transfer = 2;
            h.matrix = 5;
            var color = MpegVideoHeaders.find(h.bytes()).color();
            assertEquals(ColorInfo.BT601_LIMITED.primaries(), color.primaries());
            assertEquals(ColorInfo.BT601_LIMITED.transfer(), color.transfer());
            assertEquals(MatrixCoefficients.of(5), color.matrix());
        }

        @Test
        @DisplayName("loads the quantizer matrices, transmitted in zigzag order, into raster order")
        void matrices() {
            var h = new Header();
            h.intra = new int[64];
            h.nonIntra = new int[64];
            for (var i = 0; i < 64; i++) {
                h.intra[i] = 8 + i;
                h.nonIntra[i] = 100 + i;
            }
            var sequence = MpegVideoHeaders.find(h.bytes());
            assertArrayEquals(h.intra, sequence.intraWeights());
            assertArrayEquals(h.nonIntra, sequence.nonIntraWeights());
            var defaults = MpegVideoHeaders.find(new Header().bytes());
            assertArrayEquals(MpegVideoHeaders.defaultIntraMatrix(), defaults.intraWeights());
            var flat = new int[64];
            Arrays.fill(flat, 16);
            assertArrayEquals(flat, defaults.nonIntraWeights());
            assertEquals(8, MpegVideoHeaders.defaultIntraMatrix()[0]);
            assertEquals(83, MpegVideoHeaders.defaultIntraMatrix()[63]);
        }

        @Test
        @DisplayName("records a sequence scalable extension's mode")
        void scalable() {
            var h = new Header();
            h.scalableMode = 1;
            assertEquals(1, MpegVideoHeaders.find(h.bytes()).scalableMode());
        }

        @ParameterizedTest(name = "profile_and_level_indication {0}")
        @CsvSource({"0x58, Simple, 8", "0x48, Main, 8", "0x44, Main, 4", "0x14, High, 4", "0x85, 4:2:2, 5",
                "0x82, 4:2:2, 2", "0x8E, Multiview, 14", "0x38, SNR Scalable, 8", "0x28, Spatially Scalable, 8"})
        @DisplayName("names the profile of profile_and_level_indication")
        void profiles(String code, String profile, int level) {
            var h = new Header();
            h.profileLevel = Integer.decode(code);
            var sequence = MpegVideoHeaders.find(h.bytes());
            assertEquals(profile, sequence.profile());
            assertEquals(level, sequence.level());
        }
    }

    @Nested
    @DisplayName("MPEG-1")
    class Mpeg1 {
        @Test
        @DisplayName("reads a header without extensions as MPEG-1 4:2:0 progressive, centered chroma")
        void fields() {
            var h = new Header();
            h.extension = false;
            h.width = 352;
            h.height = 288;
            h.aspect = 1;
            var sequence = MpegVideoHeaders.find(h.bytes());
            assertFalse(sequence.mpeg2());
            assertEquals(352, sequence.width());
            assertEquals(288, sequence.height());
            assertTrue(sequence.progressive());
            assertEquals(ChromaFormat.YUV420, sequence.chroma());
            assertEquals(ChromaSiting.CENTER, sequence.siting());
            assertNull(sequence.profile());
            assertEquals(-1, sequence.level());
            assertEquals(new Rational(25, 1), sequence.frameRate());
        }

        // the closest fractions with terms up to 255, which ffprobe reports for these codes too
        @ParameterizedTest(name = "pel_aspect_ratio {0}")
        @CsvSource({"1, 1, 1", "2, 49, 33", "3, 64, 45", "4, 239, 182", "8, 178, 163", "10, 196, 201",
                "12, 200, 219", "14, 134, 161"})
        @DisplayName("inverts the pel aspect ratio into the closest sample aspect ratio with terms up to 255")
        void aspect(int code, long n, long d) {
            var h = new Header();
            h.extension = false;
            h.aspect = code;
            assertEquals(new Rational(n, d).reduce(), MpegVideoHeaders.find(h.bytes()).sampleAspect());
        }

        @ParameterizedTest(name = "pel_aspect_ratio {0}")
        @CsvSource({"0", "15"})
        @DisplayName("has no sample aspect ratio for a forbidden or reserved code")
        void reserved(int code) {
            var h = new Header();
            h.extension = false;
            h.aspect = code;
            assertNull(MpegVideoHeaders.find(h.bytes()).sampleAspect());
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {
        @Test
        @DisplayName("is the sequence header with its extensions and user data, up to the group of pictures")
        void config() {
            var h = new Header();
            h.display = true;
            h.displayWidth = 720;
            h.displayHeight = 576;
            h.userData = true;
            var headers = h.bytes();
            var frame = join(headers, picture(1));
            assertArrayEquals(headers, MpegVideoHeaders.config(frame));
            assertEquals(1, MpegVideoHeaders.firstPictureType(frame));
            assertEquals(3, MpegVideoHeaders.firstPictureType(picture(3)));
            assertEquals(0, MpegVideoHeaders.firstPictureType(headers));
            assertNull(MpegVideoHeaders.config(picture(1)));
            assertNull(MpegVideoHeaders.config(join(picture(1), headers)), "a header after the picture");
        }

        @Test
        @DisplayName("is described by VideoConfigs for both codecs")
        void describe() {
            var h = new Header();
            h.width = 1920;
            h.height = 1080;
            h.aspect = 3;
            h.rate = 4;
            h.profileLevel = 0x44;
            for (var codec : new Codec[]{Codec.MPEG1_VIDEO, Codec.MPEG2_VIDEO}) {
                var info = VideoConfigs.describe(codec, h.bytes());
                assertEquals(1920, info.width());
                assertEquals(1080, info.height());
                assertEquals(new Rational(1, 1), info.sampleAspect());
                assertEquals(new Rational(30000, 1001), info.frameRate());
                assertEquals("Main", info.profile());
                assertEquals(4, info.level());
                assertEquals(8, info.bitDepth());
                assertEquals(ChromaFormat.YUV420, info.chroma());
            }
            assertNull(VideoConfigs.describe(Codec.MPEG2_VIDEO, new byte[]{0, 0, 1, (byte) 0xB3, 0}));
        }

        @Test
        @DisplayName("compares repeated headers field by field")
        void sameAs() {
            var a = MpegVideoHeaders.find(new Header().bytes());
            var b = MpegVideoHeaders.find(join(new Header().bytes(), picture(1)));
            assertTrue(a.sameAs(b));
            var h = new Header();
            h.chroma = 2;
            assertFalse(a.sameAs(MpegVideoHeaders.find(h.bytes())));
            assertFalse(a.sameAs(null));
        }
    }

    @Nested
    @DisplayName("rejection")
    class Rejection {
        @Test
        @DisplayName("rejects zero sizes, zero weights, chroma format 0 and truncated headers")
        void invalid() {
            var h = new Header();
            h.width = 0;
            assertNull(MpegVideoHeaders.find(h.bytes()));
            h = new Header();
            h.height = 0;
            assertNull(MpegVideoHeaders.find(h.bytes()));
            h = new Header();
            h.intra = new int[64];
            Arrays.fill(h.intra, 16);
            h.intra[17] = 0;
            assertNull(MpegVideoHeaders.find(h.bytes()));
            h = new Header();
            h.chroma = 0;
            assertNull(MpegVideoHeaders.find(h.bytes()));
            var bytes = new Header().bytes();
            for (var length = 0; length < 12; length++) {
                assertNull(MpegVideoHeaders.find(Arrays.copyOf(bytes, length)), "header cut to " + length);
            }
            // a sequence extension cut before its frame rate fields
            assertNull(MpegVideoHeaders.find(Arrays.copyOf(bytes, 12 + 4 + 3)));
            assertNull(MpegVideoHeaders.find(new byte[0]));
            assertNull(MpegVideoHeaders.parse(bytes, 1, bytes.length));
        }
    }
}

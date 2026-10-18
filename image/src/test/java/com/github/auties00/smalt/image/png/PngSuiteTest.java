package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs Willem van Schaik's PngSuite (2017-07-19 release, SHA-256 pinned): every valid image must
 * decode bit-exactly against ffmpeg (16-bit images through rgba64le reduced with the documented
 * rounding), and every x*.png corrupt image must be rejected as malformed.
 */
class PngSuiteTest {
    private static final String ARCHIVE = "PngSuite-2017jul19.tgz";
    private static final String URL = "http://www.schaik.com/pngsuite/PngSuite-2017jul19.tgz";
    private static final String SHA256 = "0294b244c95a8342c01b00010cf34abdcabc7c6a34fd0fe1bd963917537bfdc8";

    private static final String NAMES = """
            PngSuite basi0g01 basi0g02 basi0g04 basi0g08 basi0g16 basi2c08 basi2c16 basi3p01 basi3p02 basi3p04 basi3p08
            basi4a08 basi4a16 basi6a08 basi6a16 basn0g01 basn0g02 basn0g04 basn0g08 basn0g16 basn2c08 basn2c16 basn3p01
            basn3p02 basn3p04 basn3p08 basn4a08 basn4a16 basn6a08 basn6a16 bgai4a08 bgai4a16 bgan6a08 bgan6a16 bgbn4a08
            bggn4a16 bgwn6a08 bgyn6a16 ccwn2c08 ccwn3p08 cdfn2c08 cdhn2c08 cdsn2c08 cdun2c08 ch1n3p04 ch2n3p08 cm0n0g04
            cm7n0g04 cm9n0g04 cs3n2c16 cs3n3p08 cs5n2c08 cs5n3p08 cs8n2c08 cs8n3p08 ct0n0g04 ct1n0g04 cten0g04 ctfn0g04
            ctgn0g04 cthn0g04 ctjn0g04 ctzn0g04 exif2c08 f00n0g08 f00n2c08 f01n0g08 f01n2c08 f02n0g08 f02n2c08 f03n0g08
            f03n2c08 f04n0g08 f04n2c08 f99n0g04 g03n0g16 g03n2c08 g03n3p04 g04n0g16 g04n2c08 g04n3p04 g05n0g16 g05n2c08
            g05n3p04 g07n0g16 g07n2c08 g07n3p04 g10n0g16 g10n2c08 g10n3p04 g25n0g16 g25n2c08 g25n3p04 oi1n0g16 oi1n2c16
            oi2n0g16 oi2n2c16 oi4n0g16 oi4n2c16 oi9n0g16 oi9n2c16 pp0n2c16 pp0n6a08 ps1n0g08 ps1n2c16 ps2n0g08 ps2n2c16
            s01i3p01 s01n3p01 s02i3p01 s02n3p01 s03i3p01 s03n3p01 s04i3p01 s04n3p01 s05i3p02 s05n3p02 s06i3p02 s06n3p02
            s07i3p02 s07n3p02 s08i3p02 s08n3p02 s09i3p02 s09n3p02 s32i3p04 s32n3p04 s33i3p04 s33n3p04 s34i3p04 s34n3p04
            s35i3p04 s35n3p04 s36i3p04 s36n3p04 s37i3p04 s37n3p04 s38i3p04 s38n3p04 s39i3p04 s39n3p04 s40i3p04 s40n3p04
            tbbn0g04 tbbn2c16 tbbn3p08 tbgn2c16 tbgn3p08 tbrn2c08 tbwn0g16 tbwn3p08 tbyn3p08 tm3n3p02 tp0n0g08 tp0n2c08
            tp0n3p08 tp1n3p08 xc1n0g08 xc9n2c08 xcrn0g04 xcsn0g01 xd0n2c08 xd3n2c08 xd9n2c08 xdtn0g01 xhdn0g08 xlfn0g04
            xs1n0g01 xs2n0g01 xs4n0g01 xs7n0g01 z00n2c08 z03n2c08 z06n2c08 z09n2c08
            """;

    private static Map<String, byte[]> suite;

    private static synchronized Map<String, byte[]> suite() {
        if (suite == null) {
            suite = RasterTesting.untarGz(Conformance.fetch(ARCHIVE, URL, SHA256));
        }
        return suite;
    }

    static Stream<String> valid() {
        return Arrays.stream(NAMES.trim().split("\\s+")).filter(name -> !name.startsWith("x"));
    }

    static Stream<String> corrupt() {
        return Arrays.stream(NAMES.trim().split("\\s+")).filter(name -> name.startsWith("x"));
    }

    private static byte[] file(String name) {
        var data = suite().get(name + ".png");
        assertNotNull(data, name + ".png is missing from the archive");
        return data;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valid")
    @DisplayName("decodes every valid image bit-exactly against ffmpeg")
    void decodesExactly(String name) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var data = file(name);
        var image = PngDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults());
        var picture = (RgbaPicture) image.picture();
        var depth = data[24];
        var colorType = data[25];
        var path = RasterTesting.write("pngsuite-" + name + ".png", data);
        var expected = depth == 16 ? RasterTesting.ffmpegReduced16(path) : RasterTesting.ffmpegFrames(path, "rgba");
        var trns = indexOf(data, "tRNS");
        if (colorType == 0 && depth < 8 && trns >= 0) {
            // ffmpeg compares a low-depth gray tRNS key with the expanded 8-bit sample, so it never
            // matches; PNG 3rd edition 11.3.1.1 compares at the image bit depth (tbbn0g04 makes
            // level 15 transparent), which the expanded value represents one-to-one.
            var key = ((data[trns + 4] & 0xFF) << 8 | (data[trns + 5] & 0xFF)) * 255 / ((1 << depth) - 1);
            for (var i = 0; i < expected.length; i += 4) {
                if ((expected[i] & 0xFF) == key) {
                    expected[i + 3] = 0;
                }
            }
        }
        RasterTesting.assertSameRgba(expected, RasterTesting.tight(picture), picture.width(), name);
        var transparency = colorType == 4 || colorType == 6 || indexOf(data, "tRNS") >= 0;
        assertEquals(transparency, picture.hasAlpha(), "hasAlpha");
        assertEquals(ImageFormat.PNG, image.format());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valid")
    @DisplayName("keeps 16-bit images at full depth on request, reducing to the default output")
    void decodesHighBitDepth(String name) {
        var data = file(name);
        var options = ImageDecoderOptions.builder().highBitDepth(true).build();
        var narrow = (RgbaPicture) PngDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults()).picture();
        var picture = PngDecoder.decode(MediaSource.of(data), options).picture();
        if (data[24] != 16) {
            var same = assertInstanceOf(RgbaPicture.class, picture);
            assertArrayEquals(RasterTesting.tight(narrow), RasterTesting.tight(same), name);
            return;
        }
        var wide = assertInstanceOf(Rgba16Picture.class, picture);
        assertEquals(narrow.hasAlpha(), wide.hasAlpha(), "hasAlpha");
        assertArrayEquals(RasterTesting.tight(narrow), RasterTesting.tight(ImageOps.toEightBit(wide)), name);
        if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
            var expected = RasterTesting.ffmpegFrames(RasterTesting.write("pngsuite-" + name + ".png", data), "rgba64le");
            var actual = new byte[wide.pixels().length * 2];
            for (var i = 0; i < wide.pixels().length; i++) {
                actual[i * 2] = (byte) wide.pixels()[i];
                actual[i * 2 + 1] = (byte) (wide.pixels()[i] >> 8);
            }
            assertArrayEquals(expected, actual, name);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valid")
    @DisplayName("probes the size and transparency of every valid image")
    void probes(String name) {
        var data = file(name);
        var info = PngDecoder.probe(MediaSource.of(data));
        var width = ((data[16] & 0xFF) << 24) | ((data[17] & 0xFF) << 16) | ((data[18] & 0xFF) << 8) | (data[19] & 0xFF);
        var height = ((data[20] & 0xFF) << 24) | ((data[21] & 0xFF) << 16) | ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
        assertEquals(width, info.width());
        assertEquals(height, info.height());
        assertFalse(info.animated());
        assertEquals(1, info.frameCount());
        var decoded = PngDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults());
        assertEquals(decoded.picture().hasAlpha(), info.hasAlpha());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corrupt")
    @DisplayName("rejects every corrupt image as malformed")
    void rejectsCorrupt(String name) {
        var data = file(name);
        assertThrows(MediaException.Malformed.class, () -> PngDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults()));
    }

    @Test
    @DisplayName("exposes the eXIf block of exif2c08 with its orientation")
    void exif() {
        var image = PngDecoder.decode(MediaSource.of(file("exif2c08")), ImageDecoderOptions.defaults());
        var exif = image.exif().orElseThrow();
        assertEquals(978, exif.length);
        assertEquals('M', exif[0]);
        assertEquals(Orientation.NORMAL, image.orientation());
    }

    @Test
    @DisplayName("opens every valid image as a one-frame animation equal to the still decode")
    void oneFrameAnimations() {
        valid().forEach(name -> {
            var data = file(name);
            var still = (RgbaPicture) PngDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults()).picture();
            try (var animation = PngDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.defaults())) {
                assertEquals(1, animation.frameCount());
                var frame = animation.read();
                assertArrayEquals(RasterTesting.tight(still), RasterTesting.tight(frame.picture()), name);
                assertEquals(0, frame.durationMillis());
                assertNull(animation.read());
            }
        });
    }

    private static int indexOf(byte[] data, String type) {
        var needle = PngFiles.ascii(type);
        outer:
        for (var i = 0; i + 4 <= data.length; i++) {
            for (var j = 0; j < 4; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}

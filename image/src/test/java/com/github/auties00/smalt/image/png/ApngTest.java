package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers APNG decoding: Philip Taylor's APNG test suite (61 pinned images with documented
 * expected renderings), ffmpeg-generated animations compared frame by frame against ffmpeg's
 * compositing decoder, and hand-built animations for blending, disposal, delays and structural
 * errors.
 */
class ApngTest {
    private static final String BASE = "http://philip.html5.org/tests/apng/";

    private static final String HASHES = """
            000 f8bb08c2c717a0ca4d0a513613797acb174b493001a7bd7bb84bc40b67138f50
            001 def05de6147136409ac8133e98d5e25de5ba90f151ac98430c62f7835bb64788
            002 328ae6149b8c65f0f7bb4afbc5cee51db44e332d8d4b8ca1619831f254efd836
            003 27b99d5a125aa7539cb7e804152ceb9b090f71d1d0a63a65cb07b7761f6aa3e0
            004 3e0ffdbe7dc6dad05dfc4cacd712b76c1121cd7378671212ae000d76c07b1a4e
            005 e3b10731287532eb2fe7d3c82ea338e49201e8d3366f8c295ba38c8ea30508c4
            006 a61b0d873abd84fd4051500189c4387deb7f33603ccb0057e3e3514b47a63199
            007 1abab0c7de5252a16da34777ff34c4a29c6000493d23ac1777cd17415e6aab33
            008 8f26f544d5f7f0c8d4448ca020c93f79b64e1d607c7c561082bc989ca2e91fad
            009 72ede26035cd5259568014444ae6d0adfc6e17a0a32a0cdab0efd636140ac0c2
            010 eaea7da1805f17d8d0914574e797f700d52745ea7f37304e2c7510faafb43d85
            011 24ba2f2427076d76284a32ae7f5b57ea82b134df4c71471d90f17fc8b3b549f9
            012 28138dd4a4ad56f86c18216b051b96a1bb353b69ebd85ce272928b085bb84400
            013 d0495c63d383f963704123f44272209877656fb0cee4b59a2099486c2d761e06
            014 e3d4ba499c333a600dd1e42f374a9a68fb783b0f3274091ab34f5b395462eae8
            015 6db2a90911b40067b7f35b01869115f081858ee15b28374e57c51c7e5c0cb524
            016 62a455c588f84633ed9bbbe8993d58e834e9c7c30a4f9e495bf144caf38351c7
            017 68ad45a3c5f082981b2229d6be192a492d067051d3ef307916385b60f5856522
            018 fcfb37fa3cbdff3733a1b4158daa4d79e8b73fb2451dfd26a060fd2fdc12fd59
            019 0ea550eed2be77a58dca9a2799669d4f501793dca16d723bd4b13d973a657d11
            020 a291d9b89156d4d8eba9e658b44f38ca1543fb4fab10d3b00c3fd4ea306d5844
            021 2b571f7034ef1fb355182cf00fa6ccd7d784720709f229e3bcc5948abf2f81ee
            022 dc13592222a89d9d68c9f9e36dd16f716217fad2da7026e273b524eaaf11a231
            023 94a663efd047fd6557178a19559bd3615a31aba574e3a9a4fa958fd3e6a15e05
            024 382ad9a12422764d71eecf212ca63197a85cfb3469e729aa90ba925fb01ed3d8
            025 d0806bb174bd52a1591cefbe061d81403de6ccc0b608babb99ac57dd1e0aec3f
            026 dbf96c3cc3be0f3ce4cf6dfb36bb676459b3afcfc001765adbce276d17873f7d
            027 cb5f16080740c2413b0de42055e5cccf2c0e4e959582ed4da38d88a65e095e88
            028 c078e6c56dcadc50ee85009647289aecd52e893c37c50bce622ef8d648a47c07
            029 964b047e1e08b8eb4fba4e1d007b8ed466552db0beb384f8e3fcaa26b254d85b
            030 96e11a4777d431af686188e0d0de0588c953b649442fa6ffcdeaf50e8cbd76a0
            031 3529e70621fe74654db60838bbef96ac055bc0248615d6e3ef51b7db0bda1ff2
            032 14efff3306cab1c0fa02691b812d8e88582498c8ba8804ab12fa1a23b3dc86f2
            033 82da2ad317a5b3ed2659f063354271ae4051fc3fad726c0092db8b7e6442d351
            034 343fff1a6b66347fb9b1a14d37f93c20600b58cd853114de54326be1a68666a4
            035 05693186fb3d9c91ed0d4451399b3c4340741789c373ff352b2dc49dd79c7b46
            036 06195c323179b78da31ec2e38cee19f69c637f4f6201b44631d184ad9749d9d0
            037 82b702bd90bfac4b03cdb635bc7b9800f7c61d28e588811632b2a33706b0a693
            038 2a9179d9263c2b0bb3140abce3ec1b088b06a49320c4705111599a087d22b585
            039 e334338085eef64d8ffae1b58c2512c2d4b01c8c95621e8e799e6fa34d7a2be1
            040 59068aa0bfc605418e01847be21a9014ee5fc54102f4e9d85719b2a0a7e38407
            041 41ae985b03b960195e022a2423041ffe4838d21415c4f8b329eca75a590f3a01
            042 c76009a8070ba0f2f1d88738e6d75c598e9a3da571e042c6b253da3e1d856eaf
            043 a50d974cac6b8d05f0e6708133133480867d6e2f8ac6632429936f0186b009e6
            044 b0227c0faf35a64e2e4f3f5b886dcdd03731fa9462fd339864fe04e059ea7b6c
            045 d8ee2d43e7f1c50bc0685bbe9cfab24637562f6bf22730c982e68bd35fa08fa2
            046 508d4aef37919636dd094392564824e493f5e39b1f0fbe7e3776005820506997
            047 f36dbc297317c54b2f89924b923dbd87e912362fa7f7ef3a06b5fb85ca68474e
            048 302d05341f1b1c0ca1d659564e9a800b627ac505dd679a4e5b7495989cc0570e
            049 5dda3b6b1675725c14e020f9b9048472d76d7587660b309bc95025e5baa01c17
            050 da82a37bd07f9d215d0e4afa201b62c8c91d6900b5ded2faae77a5584c6ff7f0
            051 951961565e23de5c98c71663dfac216468d9a7f2941bde97147c596a4de2d6ab
            052 340c7eceb27ea6941d4883acfea3a491a626d6435e76e930661d2fb6aa3c4937
            053 6b97190824fe6fb92621efe716bf559e2ce079e4954ad65655e36a33f0642b0b
            054 45ad1e3a15c34fc8becb77787503d46c4f08f3206e2e5d3967426ed217743ce1
            055 bc9294f9ea864533a2a58e3afc7b68f921383b67f7c164406bed4b3a0224366e
            056 0da68015c8eb0a048ac01ebdb04717e85d3580f2086c8668d59b6cc0bd99e34c
            057 fc20e7ebe22a864c07842eaebf868a35035daf078aa4845798d91189056957ec
            058 20089731e12ba9662a465ec3370e93032939f1140bfbf3230f2cde0b2f412afb
            059 0d4f83e58c6d6c75eee9980b86349adb5f0e30308cc0741753186993d3a23232
            060 7f3d4ea1c3f754bcd70f2f8d6f153166860eca7a0517e2ac03a9a593ce47f5bb
            """;

    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFFFF0000;
    private static final int YELLOW = 0xFF00FFFF;

    private static byte[] philip(String id) {
        for (var line : HASHES.strip().split("\n")) {
            var parts = line.trim().split(" ");
            if (parts[0].equals(id)) {
                try {
                    return Files.readAllBytes(Conformance.fetch("apng-philip-" + id + ".png", BASE + id + ".png", parts[1]));
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }
        }
        throw new IllegalArgumentException(id);
    }

    /** One decoded frame: packed canvas copy and duration. */
    record Frame(byte[] pixels, int duration) {
        int pixel(int width, int x, int y) {
            var i = (y * width + x) * 4;
            return (pixels[i] & 0xFF) | (pixels[i + 1] & 0xFF) << 8 | (pixels[i + 2] & 0xFF) << 16 | (pixels[i + 3] & 0xFF) << 24;
        }
    }

    static List<Frame> frames(AnimationReader reader) {
        var frames = new ArrayList<Frame>();
        for (var current = reader.read(); current != null; current = reader.read()) {
            frames.add(new Frame(RasterTesting.tight(current.picture()), current.durationMillis()));
        }
        return frames;
    }

    static List<Frame> frames(byte[] data) {
        try (var reader = PngDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.defaults())) {
            return frames(reader);
        }
    }

    private static void assertSolid(Frame frame, int width, int height, int color, String what) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                assertEquals(Integer.toHexString(color), Integer.toHexString(frame.pixel(width, x, y)), what + " at " + x + "," + y);
            }
        }
    }

    private static void assertTransparent(Frame frame, String what) {
        for (var i = 3; i < frame.pixels().length; i += 4) {
            assertEquals(0, frame.pixels()[i], what + ": alpha of pixel " + i / 4);
        }
    }

    @Nested
    @DisplayName("Philip Taylor's APNG test suite")
    class PhilipSuite {
        static Stream<String> solidGreen() {
            return Stream.of("000", "001", "002", "003", "004", "005", "006", "007", "009", "010", "011", "013", "016",
                    "017", "020", "021", "024", "036", "037");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("solidGreen")
        @DisplayName("ends on solid green")
        void green(String id) {
            var frames = frames(philip(id));
            assertSolid(frames.getLast(), 128, 64, GREEN, id);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("transparent")
        @DisplayName("ends fully transparent")
        void transparent(String id) {
            assertTransparent(frames(philip(id)).getLast(), id);
        }

        static Stream<String> transparent() {
            return Stream.of("008", "012", "014", "018");
        }

        @Test
        @DisplayName("015 disposes a region to background inside a blue frame")
        void backgroundInRegion() {
            var last = frames(philip("015")).getLast();
            for (var y = 0; y < 64; y++) {
                for (var x = 0; x < 128; x++) {
                    var inside = x >= 32 && x < 96 && y >= 16 && y < 48;
                    var pixel = last.pixel(128, x, y);
                    if (inside) {
                        assertEquals(0, pixel >>> 24, "inner pixel " + x + "," + y);
                    } else {
                        assertEquals(Integer.toHexString(BLUE), Integer.toHexString(pixel), "outer pixel " + x + "," + y);
                    }
                }
            }
        }

        @Test
        @DisplayName("019 keeps a nearly transparent green written with BLEND_OP_SOURCE")
        void nearlyTransparent() {
            var pixel = frames(philip("019")).getLast().pixel(128, 5, 5);
            assertEquals(0x00FF00, pixel & 0xFFFFFF);
            assertTrue((pixel >>> 24) > 0 && (pixel >>> 24) <= 3, "alpha " + (pixel >>> 24));
        }

        @Test
        @DisplayName("022 and 023 blend half-transparent frames OVER opaque ones (gAMA is ignored)")
        void blending() {
            var green = frames(philip("022")).getLast().pixel(128, 64, 32);
            assertEquals(0xFF000000, green & 0xFFFF00FF);
            assertTrue(((green >>> 8) & 0xFF) >= 225 && ((green >>> 8) & 0xFF) <= 235, Integer.toHexString(green));
            // 023 is "nearly black" only under its gAMA of 1/2200, which this decoder does not apply
            var red = frames(philip("023")).getLast().pixel(128, 64, 32);
            assertEquals(0xFF000000, red & 0xFFFFFF00);
            assertTrue((red & 0xFF) >= 225 && (red & 0xFF) <= 235, Integer.toHexString(red));
        }

        static Stream<Arguments> delays() {
            return Stream.of(
                    Arguments.of("025", new int[]{500, 1000, 500, 1000}),
                    Arguments.of("026", new int[]{500, 1000}),
                    Arguments.of("027", new int[]{500, 1000}),
                    Arguments.of("028", new int[]{500, 1000}),
                    Arguments.of("029", new int[]{0, 0, 500, 1000}));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("delays")
        @DisplayName("converts delay fractions to milliseconds")
        void delay(String id, int[] expected) {
            var frames = frames(philip(id));
            assertArrayEquals(expected, frames.stream().mapToInt(Frame::duration).toArray());
            assertSolid(frames.getLast(), 128, 64, YELLOW, id);
        }

        @ParameterizedTest(name = "{0} plays {1} times")
        @org.junit.jupiter.params.provider.CsvSource({"030, 0", "031, 1", "032, 2"})
        @DisplayName("reports num_plays as the loop count")
        void plays(String id, int plays) {
            try (var reader = PngDecoder.openAnimation(MediaSource.of(philip(id)), ImageDecoderOptions.defaults())) {
                assertEquals(plays, reader.loopCount());
                assertEquals(2, reader.frameCount());
                var frames = frames(reader);
                assertSolid(frames.getLast(), 128, 64, BLUE, id);
            }
        }

        @Test
        @DisplayName("decodes other depths and color types with blending")
        void depths() {
            assertSolid(frames(philip("033")).getLast(), 128, 64, 0xFF800000, "033 16-bit color");
            var gray = frames(philip("034")).getLast();
            assertEquals(0xFF808080, gray.pixel(128, 0, 0));
            assertEquals(0xFFFFFFFF, gray.pixel(128, 64, 32));
            assertSolid(frames(philip("035")).getLast(), 128, 64, 0xFF808080, "035 gray alpha");
            assertSolid(frames(philip("038")).getLast(), 128, 64, 0xFF800000, "038 1-bit palette with alpha");
        }

        static Stream<String> invalid() {
            return Stream.of("039", "040", "041", "042", "043", "044", "045", "046", "047", "048", "049", "050", "051",
                    "052", "053", "054", "055", "056", "057", "058", "059", "060");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalid")
        @DisplayName("shows the green default image or raises an error, never an invalid frame")
        void invalidImages(String id) {
            var data = philip(id);
            try {
                var still = (RgbaPicture) PngDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults()).picture();
                assertSolid(new Frame(RasterTesting.tight(still), 0), 128, 64, GREEN, id + " still");
            } catch (MediaException.Malformed expected) {
                // 045 has no IDAT and 058 has a half-height IDAT: an error is the accepted outcome
                assertTrue(id.equals("045") || id.equals("058"), id + ": " + expected.getMessage());
            }
            try (var reader = PngDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.defaults())) {
                for (var current = reader.read(); current != null; current = reader.read()) {
                    assertSolid(new Frame(RasterTesting.tight(current.picture()), 0), 128, 64, GREEN, id + " frame");
                }
            } catch (MediaException.Malformed expected) {
                // an error is an accepted outcome for every invalid image
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("comparable")
        @DisplayName("matches ffmpeg's compositing decoder frame by frame")
        void againstFfmpeg(String id) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var data = philip(id);
            var expected = RasterTesting.ffmpegFrames(RasterTesting.write("apng-" + id + ".png", data), "rgba");
            var frames = frames(data);
            var frameSize = 128 * 64 * 4;
            assertEquals(expected.length / frameSize, frames.size(), "frame count");
            for (var i = 0; i < frames.size(); i++) {
                RasterTesting.assertSameRgba(java.util.Arrays.copyOfRange(expected, i * frameSize, (i + 1) * frameSize),
                        frames.get(i).pixels(), 128, id + " frame " + i);
            }
        }

        static Stream<String> comparable() {
            // 033 is omitted because ffmpeg 7.1 yields only one frame for that 16-bit APNG, and 024
            // because ffmpeg rejects an fcTL before acTL, which the suite marks valid
            return Stream.of("000", "001", "002", "003", "004", "005", "006", "007", "008", "009", "010", "011", "012",
                    "013", "014", "015", "016", "017", "018", "019", "020", "021", "022", "023", "025", "026",
                    "027", "028", "029", "030", "031", "032", "034", "035", "036", "037", "038");
        }
    }

    @Nested
    @DisplayName("ffmpeg-generated animations")
    class Generated {
        static Stream<Arguments> variants() {
            var alpha = "format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='mod(X*3+Y*5+N*40,256)'";
            return Stream.of(
                    Arguments.of("rgb24", "format=rgb24", "none", false),
                    Arguments.of("rgba", alpha, "mixed", false),
                    Arguments.of("rgba-paeth", alpha, "paeth", false),
                    Arguments.of("pal8", "split[a][b];[a]palettegen=reserve_transparent=1[p];[b][p]paletteuse", "none", false),
                    Arguments.of("gray", "format=gray", "sub", false),
                    Arguments.of("ya8", "format=ya8", "avg", false),
                    Arguments.of("rgba64be", alpha + ",format=rgba64be", "mixed", true),
                    Arguments.of("gray16be", "format=gray16be", "up", true));
        }

        @ParameterizedTest(name = "{0} pred={2}")
        @MethodSource("variants")
        @DisplayName("matches ffmpeg frame by frame")
        void matches(String name, String filter, String pred, boolean wide) {
            var file = Fixtures.ffmpeg("apng", List.of("-f", "lavfi", "-i", "testsrc2=size=68x46:rate=10:duration=1.5"),
                    List.of("-vf", "format=rgb24,crop=67:45:0:0," + filter, "-pred", pred, "-plays", "0", "-f", "apng"));
            var data = Fixtures.read(file);
            var expected = wide ? RasterTesting.ffmpegReduced16(file) : RasterTesting.ffmpegFrames(file, "rgba");
            try (var reader = PngDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.defaults())) {
                assertEquals(0, reader.loopCount());
                var frames = frames(reader);
                var frameSize = 67 * 45 * 4;
                assertEquals(expected.length / frameSize, frames.size(), "frame count");
                assertEquals(15, frames.size());
                for (var i = 0; i < frames.size(); i++) {
                    RasterTesting.assertSameRgba(java.util.Arrays.copyOfRange(expected, i * frameSize, (i + 1) * frameSize),
                            frames.get(i).pixels(), 67, name + " frame " + i);
                    assertEquals(100, frames.get(i).duration());
                }
            }
            var info = PngDecoder.probe(MediaSource.of(data));
            assertTrue(info.animated());
            assertEquals(15, info.frameCount());
        }
    }

    @Nested
    @DisplayName("hand-built animations")
    class HandBuilt {
        private static byte[] solid(int width, int height, int r, int g, int b, int a) {
            var pixels = new byte[width * height * 4];
            for (var i = 0; i < pixels.length; i += 4) {
                pixels[i] = (byte) r;
                pixels[i + 1] = (byte) g;
                pixels[i + 2] = (byte) b;
                pixels[i + 3] = (byte) a;
            }
            return PngFiles.zlib(PngFiles.unfiltered(pixels, width * 4));
        }

        @Test
        @DisplayName("blends partial alpha OVER partial alpha with the documented rounding")
        void overFormula() {
            var data = PngFiles.create().ihdr(4, 4, 8, 6, 0).actl(2, 0)
                    .fctl(0, 4, 4, 0, 0, 1, 10, 0, 0).idat(solid(4, 4, 200, 100, 50, 100))
                    .fctl(1, 2, 2, 1, 1, 1, 10, 0, 1).fdat(2, solid(2, 2, 10, 220, 90, 150))
                    .iend().bytes();
            var frames = frames(data);
            assertEquals(2, frames.size());
            // sa = 150, da = 100: alpha = 150 + round(105 * 100 / 255) = 191,
            // color = floor((src * 150 + floor(dst * 100 * 105 / 255)) / 191)
            var weight = 100 * 105;
            var alpha = 150 + Math.round(weight / 255f);
            var expected = ((10 * 150 + 200 * weight / 255) / alpha) | ((220 * 150 + 100 * weight / 255) / alpha) << 8
                    | ((90 * 150 + 50 * weight / 255) / alpha) << 16 | alpha << 24;
            assertEquals(Integer.toHexString(expected), Integer.toHexString(frames.get(1).pixel(4, 1, 1)));
            assertEquals(Integer.toHexString(0x643264C8), Integer.toHexString(frames.get(1).pixel(4, 0, 0)));
            assertEquals(100, frames.get(0).duration());
        }

        @Test
        @DisplayName("restores the region of a PREVIOUS frame and clears a BACKGROUND one")
        void disposal() {
            var data = PngFiles.create().ihdr(4, 4, 8, 6, 0).actl(4, 0)
                    .fctl(0, 4, 4, 0, 0, 0, 0, 0, 0).idat(solid(4, 4, 255, 0, 0, 255))
                    .fctl(1, 2, 2, 2, 2, 0, 0, 2, 0).fdat(2, solid(2, 2, 0, 255, 0, 255))
                    .fctl(3, 2, 2, 0, 0, 0, 0, 1, 0).fdat(4, solid(2, 2, 0, 0, 255, 255))
                    .fctl(5, 1, 1, 3, 0, 0, 0, 0, 0).fdat(6, solid(1, 1, 9, 9, 9, 255))
                    .iend().bytes();
            var frames = frames(data);
            assertEquals(0xFF00FF00, frames.get(1).pixel(4, 3, 3));
            assertEquals(0xFF0000FF, frames.get(2).pixel(4, 3, 3), "restored after PREVIOUS");
            assertEquals(0xFFFF0000, frames.get(2).pixel(4, 0, 0));
            assertEquals(0, frames.get(3).pixel(4, 0, 0), "cleared after BACKGROUND");
            assertEquals(0xFF090909, frames.get(3).pixel(4, 3, 0));
            assertEquals(0xFF0000FF, frames.get(3).pixel(4, 2, 2));
        }

        @Test
        @DisplayName("rejects a frame extending past the canvas before showing any frame")
        void regionOutsideCanvas() {
            var data = PngFiles.create().ihdr(4, 4, 8, 6, 0).actl(2, 0)
                    .fctl(0, 4, 4, 0, 0, 0, 0, 0, 0).idat(solid(4, 4, 255, 0, 0, 255))
                    .fctl(1, 3, 3, 2, 2, 0, 0, 0, 0).fdat(2, solid(3, 3, 0, 255, 0, 255))
                    .iend().bytes();
            assertThrows(MediaException.Malformed.class,
                    () -> PngDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.defaults()));
        }

        @Test
        @DisplayName("rejects corrupt frame data before exposing that frame")
        void corruptFrame() {
            var bad = solid(2, 2, 0, 255, 0, 255);
            bad[bad.length / 2] ^= 0x55;
            var data = PngFiles.create().ihdr(2, 2, 8, 6, 0).actl(2, 0)
                    .fctl(0, 2, 2, 0, 0, 0, 0, 0, 0).idat(solid(2, 2, 255, 0, 0, 255))
                    .fctl(1, 2, 2, 0, 0, 0, 0, 0, 0).fdat(2, bad)
                    .iend().bytes();
            try (var reader = PngDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.defaults())) {
                assertNotNull(reader.read());
                assertThrows(MediaException.Malformed.class, reader::read);
                assertNull(reader.read());
            }
        }

        @Test
        @DisplayName("checks the frame limit against acTL before decoding")
        void frameLimit() {
            var data = PngFiles.create().ihdr(1, 1, 8, 6, 0).actl(3, 0)
                    .fctl(0, 1, 1, 0, 0, 0, 0, 0, 0).idat(solid(1, 1, 1, 2, 3, 4))
                    .fctl(1, 1, 1, 0, 0, 0, 0, 0, 0).fdat(2, solid(1, 1, 1, 2, 3, 4))
                    .fctl(3, 1, 1, 0, 0, 0, 0, 0, 0).fdat(4, solid(1, 1, 1, 2, 3, 4))
                    .iend().bytes();
            var limits = com.github.auties00.smalt.MediaLimits.builder().maxDimension(100).maxPixels(10_000)
                    .maxFrames(2).maxChannels(8).maxBytes(1 << 20).build();
            assertThrows(MediaException.LimitExceeded.class,
                    () -> PngDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.builder().limits(limits)
                            .build()));
            assertEquals(3, frames(data).size());
        }

        @Test
        @DisplayName("tolerates a missing IEND after the last frame")
        void missingIend() {
            var full = PngFiles.create().ihdr(2, 2, 8, 6, 0).actl(2, 0)
                    .fctl(0, 2, 2, 0, 0, 0, 0, 0, 0).idat(solid(2, 2, 255, 0, 0, 255))
                    .fctl(1, 2, 2, 0, 0, 0, 0, 0, 0).fdat(2, solid(2, 2, 0, 255, 0, 255))
                    .bytes();
            assertEquals(2, frames(full).size());
        }

        @Test
        @DisplayName("rejects truncation anywhere inside the APNG chunks")
        void truncation() {
            var full = PngFiles.create().ihdr(2, 2, 8, 6, 0).actl(2, 0)
                    .fctl(0, 2, 2, 0, 0, 0, 0, 0, 0).idat(solid(2, 2, 255, 0, 0, 255))
                    .fctl(1, 2, 2, 0, 0, 0, 0, 0, 0).fdat(2, solid(2, 2, 0, 255, 0, 255))
                    .iend().bytes();
            var end = full.length - 12;
            for (var length = 0; length < end; length++) {
                var prefix = java.util.Arrays.copyOf(full, length);
                assertThrows(MediaException.Malformed.class, () -> frames(prefix), "prefix " + length);
            }
            assertEquals(2, frames(java.util.Arrays.copyOf(full, end)).size());
        }
    }
}

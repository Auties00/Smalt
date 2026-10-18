package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.probe.ImageStreamInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the public surface of the JPEG XL decoder on conformance files: format detection, the
 * probes, the registry integration, the output picture types, animation playback, the float
 * decode and JPEG reconstruction.
 */
class JxlDecoderTest {
    static byte[] file(String name) {
        return JxlVectors.read(JxlVectors.find(name).input());
    }

    @Nested
    @DisplayName("detection")
    class Detection {
        @Test
        @DisplayName("recognizes a bare codestream and a container")
        void signatures() {
            assertEquals(ImageFormat.JXL, ImageFormat.detect(new byte[]{(byte) 0xFF, 0x0A}, 2).orElseThrow());
            var container = new byte[]{0, 0, 0, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A};
            assertEquals(ImageFormat.JXL, ImageFormat.detect(container, container.length).orElseThrow());
            assertTrue(ImageFormat.detect(Arrays.copyOf(container, 11), 11).isEmpty());
            assertEquals("image/jxl", ImageFormat.JXL.mimeType());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"grayscale", "patches", "cafe"})
        @DisplayName("routes conformance files to this decoder")
        void registry(String name) {
            var data = file(name);
            assertEquals(ImageFormat.JXL, Images.detect(MediaSource.of(data)).orElseThrow());
            assertTrue(Images.isDecodable(ImageFormat.JXL));
            assertEquals(ImageFormat.JXL, Images.decode(MediaSource.of(data)).format());
        }
    }

    @Nested
    @DisplayName("probing")
    class Probing {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "grayscale, 200, 200, false, false, 1, NORMAL, 8",
                "alpha_triangles, 1024, 1024, true, false, 1, NORMAL, 9",
                "animation_icos4d, 128, 128, true, true, 48, NORMAL, 8",
                "sunset_logo, 1386, 924, true, false, 1, TRANSVERSE, 10",
                "lossless_pfm, 500, 500, false, false, 1, NORMAL, 32",
                "patches, 1600, 1096, true, false, 1, NORMAL, 8"
        })
        @DisplayName("describes the image from its headers")
        void probe(String name, int width, int height, boolean alpha, boolean animated, int frames,
                   Orientation orientation, int bitDepth) {
            var data = file(name);
            var info = JxlDecoder.probe(MediaSource.of(data));
            assertEquals(width, info.width());
            assertEquals(height, info.height());
            assertEquals(alpha, info.hasAlpha());
            assertEquals(animated, info.animated());
            assertEquals(frames, info.frameCount());
            assertEquals(orientation, info.orientation());
            var stream = (ImageStreamInfo) Media.probe(MediaSource.of(data)).streams().getFirst();
            assertEquals(width, stream.width());
            assertEquals(height, stream.height());
            assertEquals(alpha, stream.hasAlpha());
            assertEquals(animated, stream.isAnimated());
            assertEquals(bitDepth, stream.bitDepth().orElseThrow());
        }

        @Test
        @DisplayName("applies the default byte limit before reading the file")
        void probeBound() {
            var reads = new int[1];
            var huge = new MediaSource() {
                @Override
                public long size() {
                    return 3L << 30;
                }

                @Override
                public int read(long position, byte[] buffer, int offset, int length) {
                    reads[0]++;
                    return -1;
                }

                @Override
                public void close() {
                }
            };
            var failure = assertThrows(MediaException.LimitExceeded.class, () -> JxlDecoder.probe(huge));
            assertTrue(failure.getMessage().contains(" " + MediaLimits.DEFAULT.maxBytes() + " "), failure.getMessage());
            assertEquals(0, reads[0]);
        }
    }

    @Nested
    @DisplayName("decoding")
    class Decoding {
        @Test
        @DisplayName("returns 8-bit RGBA by default and 16-bit RGBA on request for deep images")
        void depth() {
            var data = file("alpha_triangles");
            assertInstanceOf(RgbaPicture.class, JxlDecoder.decode(MediaSource.of(data)).picture());
            var deep = JxlDecoder.decode(MediaSource.of(data), ImageDecoderOptions.builder().highBitDepth(true)
                    .build());
            assertInstanceOf(Rgba16Picture.class, deep.picture());
            var shallow = JxlDecoder.decode(MediaSource.of(file("grayscale")),
                    ImageDecoderOptions.builder().highBitDepth(true).build());
            assertInstanceOf(RgbaPicture.class, shallow.picture());
        }

        @Test
        @DisplayName("keeps the orientation, the color profile and the EXIF block")
        void metadata() {
            var sunset = JxlDecoder.decode(MediaSource.of(file("sunset_logo")));
            assertEquals(Orientation.TRANSVERSE, sunset.orientation());
            assertEquals(1386, sunset.picture().width());
            assertTrue(sunset.iccProfile().isPresent());
            var patches = JxlDecoder.decode(MediaSource.of(file("patches")));
            assertTrue(patches.exif().isPresent());
        }

        @Test
        @DisplayName("exposes the container's EXIF and the codestream's ICC profile as image metadata")
        void imageMetadata() {
            var metadata = com.github.auties00.smalt.image.metadata.ImageMetadata.read(file("patches"));
            assertEquals(ImageFormat.JXL, metadata.format());
            assertTrue(metadata.exifBytes().isPresent());
            assertTrue(metadata.iccProfile().isPresent());
        }

        @Test
        @DisplayName("converts to sRGB on request")
        void srgb() {
            var image = JxlDecoder.decode(MediaSource.of(file("patches")),
                    ImageDecoderOptions.builder().convertToSrgb(true).build());
            assertEquals(1600, image.picture().width());
        }

        @Test
        @DisplayName("returns every channel at full precision")
        void floats() {
            var spot = JxlDecoder.decodeFloat(MediaSource.of(file("spot")));
            assertEquals(3, spot.colorChannels());
            assertEquals(3, spot.extraChannelTypes().size());
            assertTrue(spot.extraChannel(JxlChannelType.SPOT_COLOR).isPresent());
            assertTrue(spot.extraChannel(JxlChannelType.ALPHA).isPresent());
            var gray = JxlDecoder.decodeFloat(MediaSource.of(file("grayscale")));
            assertEquals(1, gray.colorChannels());
            var cmyk = JxlDecoder.decodeFloat(MediaSource.of(file("cmyk_layers")));
            assertTrue(cmyk.extraChannel(JxlChannelType.BLACK).isPresent());
        }

        @Test
        @DisplayName("decodes a corrupt file only to a MediaException")
        void corrupt() {
            assertThrows(MediaException.Malformed.class,
                    () -> JxlDecoder.decode(MediaSource.of(new byte[]{(byte) 0xFF, 0x0A, 0x00})));
            assertThrows(MediaException.Malformed.class, () -> JxlDecoder.decode(MediaSource.of(new byte[]{1, 2, 3})));
        }
    }

    @Nested
    @DisplayName("animation")
    class Animation {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"animation_icos4d, 48, 0", "animation_newtons_cradle, 36, 0", "animation_spline, 60, 0"})
        @DisplayName("plays every displayed frame with its timing")
        void frames(String name, int count, int loops) {
            try (var reader = Images.openAnimation(MediaSource.of(file(name)))) {
                assertEquals(count, reader.frameCount());
                assertEquals(loops, reader.loopCount());
                var frames = 0;
                var timestamp = 0L;
                com.github.auties00.smalt.image.AnimationFrame frame;
                while ((frame = reader.read()) != null) {
                    assertEquals(timestamp, frame.timestampMicros());
                    assertTrue(frame.durationMicros() > 0);
                    timestamp += frame.durationMicros();
                    frames++;
                }
                assertEquals(count, frames);
            }
        }

        @Test
        @DisplayName("presents a still image as one frame")
        void still() {
            try (var reader = JxlDecoder.openAnimation(MediaSource.of(file("grayscale")))) {
                assertEquals(1, reader.frameCount());
                assertNotNull(reader.read());
                assertNull(reader.read());
            }
        }
    }

    @Nested
    @DisplayName("JPEG reconstruction")
    class Reconstruction {
        @Test
        @DisplayName("is empty for a file that did not recompress a JPEG")
        void absent() {
            assertTrue(JxlDecoder.reconstructJpeg(MediaSource.of(file("grayscale"))).isEmpty());
        }

        @Test
        @DisplayName("gives a JPEG that Smalt decodes to the size of the JPEG XL image")
        void decodable() {
            var jpeg = JxlDecoder.reconstructJpeg(MediaSource.of(file("cafe"))).orElseThrow();
            var image = Images.decode(MediaSource.of(jpeg));
            assertEquals(ImageFormat.JPEG, image.format());
            assertEquals(JxlDecoder.probe(MediaSource.of(file("cafe"))).width(), image.sourceWidth());
        }
    }

    @Nested
    @DisplayName("reconstruction with options and plane access")
    class OptionsAndPlanes {
        @Test
        @DisplayName("reconstructJpeg(source, options) gives the file the one-argument form gives")
        void reconstructWithOptions() {
            var expected = JxlDecoder.reconstructJpeg(MediaSource.of(file("cafe"))).orElseThrow();
            var actual = JxlDecoder.reconstructJpeg(MediaSource.of(file("cafe")), ImageDecoderOptions.defaults());
            assertArrayEquals(expected, actual.orElseThrow());
            assertTrue(JxlDecoder.reconstructJpeg(MediaSource.of(file("grayscale")), ImageDecoderOptions.defaults())
                    .isEmpty());
        }

        @Test
        @DisplayName("reconstructJpeg(source, options) reads the source under the limits of the options")
        void reconstructLimits() {
            var tiny = ImageDecoderOptions.builder()
                    .limits(com.github.auties00.smalt.MediaLimits.builder().maxBytes(16).build()).build();
            assertThrows(MediaException.LimitExceeded.class,
                    () -> JxlDecoder.reconstructJpeg(MediaSource.of(file("cafe")), tiny));
        }

        @Test
        @DisplayName("plane(index) returns the shared color planes then the extra planes, and checks the index")
        void plane() {
            var red = new float[]{0.25f};
            var alpha = new float[]{1};
            var image = new JxlFloatImage(1, 1, 3, java.util.List.of(JxlChannelType.ALPHA),
                    new float[][]{red, new float[1], new float[1], alpha}, false, new byte[0],
                    Orientation.NORMAL);
            assertSame(red, image.plane(0));
            assertSame(alpha, image.plane(3));
            assertThrows(IndexOutOfBoundsException.class, () -> image.plane(4));
            assertThrows(IndexOutOfBoundsException.class, () -> image.plane(-1));
        }
    }
}

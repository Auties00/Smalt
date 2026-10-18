package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that sequential scans large enough to be decoded on several threads (speculatively when
 * they have no restart markers, by restart intervals when they do) produce exactly the planes of
 * a sequential decode: the reference decodes the quantized coefficients sequentially and
 * transforms every block with the per-block transforms. Intact, truncated and corrupted streams
 * are compared at every reduced size.
 */
class ParallelScanTest {
    private static RgbaPicture picture(int width, int height, long seed) {
        var random = new SplittableRandom(seed);
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = 4 * (y * width + x);
                var noise = random.nextInt(48) - 24;
                pixels[i] = (byte) Math.clamp(128 + 100 * Math.sin(x / 29.0 + y / 71.0) + noise, 0, 255);
                pixels[i + 1] = (byte) Math.clamp(((x * 3) ^ (y * 5)) % 256 + noise, 0, 255);
                pixels[i + 2] = (byte) Math.clamp(255.0 * y / height + noise, 0, 255);
                pixels[i + 3] = (byte) 255;
            }
        }
        return picture;
    }

    static Stream<Arguments> streams() {
        var cases = Stream.<Arguments>builder();
        var formats = new ChromaFormat[]{ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444, ChromaFormat.MONOCHROME};
        for (var f = 0; f < formats.length; f++) {
            for (var restart : new int[]{0, 13}) {
                var jpeg = JpegEncoder.encode(picture(1601 + f, 1203 - f, f), JpegEncoderOptions.builder().quality(92)
                        .chromaFormat(formats[f]).restartInterval(restart).build());
                cases.add(Arguments.of(formats[f] + " restart " + restart, jpeg));
                cases.add(Arguments.of(formats[f] + " restart " + restart + " truncated", Arrays.copyOf(jpeg, jpeg.length * 2 / 3)));
                var random = new SplittableRandom(jpeg.length);
                var corrupt = jpeg.clone();
                for (var k = 0; k < 12; k++) {
                    corrupt[1000 + random.nextInt(corrupt.length - 1000)] ^= (byte) (1 << random.nextInt(8));
                }
                cases.add(Arguments.of(formats[f] + " restart " + restart + " corrupted", corrupt));
            }
        }
        // several megabytes of entropy-coded data, so the speculative decode spans several windows
        var large = JpegEncoder.encode(picture(4032, 3024, 9), JpegEncoderOptions.builder().quality(95)
                .chromaFormat(ChromaFormat.YUV420).build());
        cases.add(Arguments.of("YUV420 12 MP", large));
        cases.add(Arguments.of("YUV420 12 MP truncated", Arrays.copyOf(large, large.length * 5 / 7)));
        var corrupt = large.clone();
        var random = new SplittableRandom(large.length);
        for (var k = 0; k < 12; k++) {
            corrupt[1000 + random.nextInt(corrupt.length - 1000)] ^= (byte) (1 << random.nextInt(8));
        }
        cases.add(Arguments.of("YUV420 12 MP corrupted", corrupt));
        return cases.build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("parallel decoding equals the sequential coefficient decode at every size")
    void equalsSequential(String name, byte[] jpeg) {
        for (var shift = 0; shift <= 3; shift++) {
            JpegPlanes planes;
            try {
                planes = new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT).decodePlanes(shift);
            } catch (MediaException exception) {
                var expected = exception.getClass();
                var reference = shift;
                org.junit.jupiter.api.Assertions.assertThrows(expected,
                        () -> new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT).decodeCoefficients(), name + " " + reference);
                continue;
            }
            var coefficients = new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT).decodeCoefficients();
            var frame = coefficients.frame();
            var size = 8 >> shift;
            var block = new int[64];
            for (var c = 0; c < frame.components.length; c++) {
                var component = frame.components[c];
                var stride = planes.stride(c);
                var expected = new byte[planes.plane(c).length];
                for (var by = 0; by < component.blocksHigh; by++) {
                    for (var bx = 0; bx < component.blocksWide; bx++) {
                        JpegReader.transformBlock(coefficients.coefficients(c), (by * component.paddedBlocksWide + bx) * 64,
                                coefficients.quantization(c), block, expected, by * size * stride + bx * size, stride, shift);
                    }
                }
                var width = component.blocksWide * size;
                for (var row = 0; row < component.blocksHigh * size; row++) {
                    var from = row * stride;
                    assertArrayEquals(Arrays.copyOfRange(expected, from, from + width),
                            Arrays.copyOfRange(planes.plane(c), from, from + width),
                            name + " scale 1/" + (1 << shift) + " component " + c + " row " + row);
                }
            }
            assertEquals(frame.width, planes.frame().width);
        }
    }
}

package com.github.auties00.smalt.image.tiff;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Builds small TIFF files of the rarer layouts from seeded patterns with {@link TiffFiles}: YCbCr in separate
 * subsampled planes and of depths other than 8 bits, a transparency mask, samples of different sizes, complex,
 * 24-bit floating-point and L*a*b* samples other than 8- and 16-bit integers. {@code TiffVariantsBehaviorTest}
 * runs them through the I/O behavior harness and the image {@code FuzzSeedWriter} writes them as seeds of the
 * TIFF fuzz target. The files depend only on the seeds, so their golden dumps stay valid.
 */
public final class TiffVariantSamples {
    private static final int WIDTH = 13;
    private static final int HEIGHT = 9;

    private TiffVariantSamples() {
        throw new AssertionError();
    }

    /**
     * Returns the files by name, in a fixed order.
     *
     * @return the files, keyed by a file name that ends in {@code .tif}
     */
    public static Map<String, byte[]> files() {
        var files = new LinkedHashMap<String, byte[]>();
        files.put("ycbcr-planar-22-16-alpha-tiles.tif", TiffYCbCrTest.planar(
                TiffYCbCrTest.Samples.random(24, 10, 2, 2, 16, true, 1), 0, 16, 16, true).bytes());
        files.put("ycbcr-planar-42-8-strips-be.tif", TiffYCbCrTest.planar(
                TiffYCbCrTest.Samples.random(21, 11, 4, 2, 8, false, 2), 4, 0, 0, false).bytes());
        files.put("ycbcr-interleaved-21-10.tif", TiffYCbCrTest.interleaved(
                TiffYCbCrTest.Samples.random(WIDTH, HEIGHT, 2, 1, 10, false, 3), true).bytes());
        files.put("mask-following.tif", TiffFiles.chain(TiffMaskTest.rgb(4),
                TiffMaskTest.mask(TiffMaskTest.bits(20, 16, 5), 20, 16)));
        int[] rgb565 = {5, 6, 5};
        files.put("mixed-565-be.tif", TiffFiles.of(WIDTH, HEIGHT, 3, 8).bits(rgb565).bigEndian()
                .data(TiffFiles.packMixed(TiffSampleVariantsTest.random(WIDTH * HEIGHT * 3, rgb565, 6), WIDTH,
                        HEIGHT, rgb565, false)).bytes());
        files.put("mixed-8-16-predictor.tif", TiffFiles.of(WIDTH, HEIGHT, 4, 8).bits(8, 16, 8, 16).extra(2)
                .tag(259, 8).tag(317, 2).chunks(TiffYCbCrTest.deflate(randomBytes(WIDTH * HEIGHT * 6, 7))).bytes());
        files.put("complex-int32.tif", TiffFiles.of(WIDTH, HEIGHT, 1, 32).format(5)
                .data(randomBytes(WIDTH * HEIGHT * 4, 8)).bytes());
        files.put("complex-float64-be.tif", TiffFiles.of(WIDTH, HEIGHT, 1, 64).format(6).bigEndian()
                .data(doubles(WIDTH * HEIGHT * 2, 9)).bytes());
        files.put("float24-alpha-be.tif", TiffFiles.of(WIDTH, HEIGHT, 2, 24).format(3).extra(2).bigEndian()
                .data(randomBytes(WIDTH * HEIGHT * 6, 10)).bytes());
        files.put("lab-float32.tif", TiffFiles.of(WIDTH, HEIGHT, 3, 32).photometric(8).format(3)
                .data(lab(11)).bytes());
        files.put("lab-12.tif", TiffFiles.of(WIDTH, HEIGHT, 3, 12).photometric(8)
                .data(TiffFiles.pack(TiffFiles.pattern(WIDTH, HEIGHT, 3, 4095, 12), WIDTH, HEIGHT, 3, 12, true))
                .bytes());
        files.put("itu-lab-24.tif", TiffFiles.of(WIDTH, HEIGHT, 3, 24).photometric(10)
                .data(TiffFiles.pack(TiffFiles.pattern(WIDTH, HEIGHT, 3, (1L << 24) - 1, 13), WIDTH, HEIGHT, 3,
                        24, true)).bytes());
        return files;
    }

    private static byte[] randomBytes(int count, long seed) {
        var bytes = new byte[count];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    /** Big-endian doubles between -2 and 2. */
    private static byte[] doubles(int count, long seed) {
        var random = new Random(seed);
        var buffer = ByteBuffer.allocate(count * 8).order(ByteOrder.BIG_ENDIAN);
        for (var i = 0; i < count; i++) {
            buffer.putDouble(random.nextDouble(-2, 2));
        }
        return buffer.array();
    }

    /** Little-endian floating-point L*, a* and b* within their ranges. */
    private static byte[] lab(long seed) {
        var random = new Random(seed);
        var buffer = ByteBuffer.allocate(WIDTH * HEIGHT * 12).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            buffer.putFloat((float) random.nextDouble(0, 100));
            buffer.putFloat((float) random.nextDouble(-128, 127));
            buffer.putFloat((float) random.nextDouble(-128, 127));
        }
        return buffer.array();
    }
}

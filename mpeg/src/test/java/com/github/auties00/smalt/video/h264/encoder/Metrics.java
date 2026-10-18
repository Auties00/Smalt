package com.github.auties00.smalt.video.h264.encoder;

/**
 * Objective quality metrics of decoded 4:2:0 sequences: PSNR with the 4:1:1 plane weighting
 * ffmpeg reports as "average", and mean luma SSIM over 8x8 windows on a 4-sample grid.
 */
final class Metrics {
    private Metrics() {
        throw new AssertionError();
    }

    static double[] compare(byte[] original, byte[] decoded, int width, int height, int frames) {
        var chromaWidth = (width + 1) / 2;
        var chromaHeight = (height + 1) / 2;
        var lumaSize = width * height;
        var frameSize = lumaSize + 2 * chromaWidth * chromaHeight;
        frames = Math.min(frames, Math.min(original.length, decoded.length) / frameSize);
        double lumaError = 0;
        double chromaError = 0;
        double ssim = 0;
        for (var f = 0; f < frames; f++) {
            var base = f * frameSize;
            for (var i = 0; i < lumaSize; i++) {
                var d = (original[base + i] & 0xFF) - (decoded[base + i] & 0xFF);
                lumaError += d * d;
            }
            for (var i = lumaSize; i < frameSize; i++) {
                var d = (original[base + i] & 0xFF) - (decoded[base + i] & 0xFF);
                chromaError += d * d;
            }
            ssim += ssim(original, decoded, base, width, height);
        }
        var lumaMse = lumaError / ((double) lumaSize * frames);
        var chromaMse = chromaError / (2.0 * chromaWidth * chromaHeight * frames);
        var mse = (4 * lumaMse + 2 * chromaMse) / 6;
        var psnr = 10 * Math.log10(255.0 * 255.0 / Math.max(mse, 1e-10));
        var psnrY = 10 * Math.log10(255.0 * 255.0 / Math.max(lumaMse, 1e-10));
        return new double[]{psnr, ssim / frames, psnrY};
    }

    private static double ssim(byte[] a, byte[] b, int base, int width, int height) {
        var c1 = 0.01 * 255 * 0.01 * 255;
        var c2 = 0.03 * 255 * 0.03 * 255;
        double total = 0;
        var count = 0;
        for (var y = 0; y + 8 <= height; y += 4) {
            for (var x = 0; x + 8 <= width; x += 4) {
                double sa = 0;
                double sb = 0;
                double saa = 0;
                double sbb = 0;
                double sab = 0;
                for (var j = 0; j < 8; j++) {
                    var row = base + (y + j) * width + x;
                    for (var i = 0; i < 8; i++) {
                        var va = a[row + i] & 0xFF;
                        var vb = b[row + i] & 0xFF;
                        sa += va;
                        sb += vb;
                        saa += va * va;
                        sbb += vb * vb;
                        sab += va * vb;
                    }
                }
                var ma = sa / 64;
                var mb = sb / 64;
                var va = saa / 64 - ma * ma;
                var vb = sbb / 64 - mb * mb;
                var cov = sab / 64 - ma * mb;
                total += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2));
                count++;
            }
        }
        return count == 0 ? 1 : total / count;
    }
}

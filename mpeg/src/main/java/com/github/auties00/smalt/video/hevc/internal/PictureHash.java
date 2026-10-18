package com.github.auties00.smalt.video.hevc.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Checks decoded pictures against the hashes of decoded picture hash SEI messages (ITU-T H.265
 * D.3.20): MD5, CRC and checksum, over the samples of each color component.
 */
final class PictureHash {
    /**
     * Prevents instantiation.
     */
    private PictureHash() {
        throw new AssertionError();
    }

    /**
     * Returns whether the sample values of a picture match a decoded picture hash (D.3.20).
     *
     * @param frame the picture
     * @param sps its sequence parameter set
     * @param type the hash type, as {@link HevcSei#hashType}
     * @param hash the hash of each color component, as {@link HevcSei#hash}
     * @return {@code true} when every component matches
     */
    static boolean matches(HevcFrame frame, HevcSps sps, int type, byte[][] hash) {
        var components = sps.chromaFormatIdc == 0 ? 1 : 3;
        for (var c = 0; c < components; c++) {
            var plane = c == 0 ? frame.y : c == 1 ? frame.cb : frame.cr;
            var plane8 = c == 0 ? frame.y8 : c == 1 ? frame.cb8 : frame.cr8;
            var width = c == 0 ? frame.width : frame.chromaWidth;
            var height = c == 0 ? frame.height : frame.chromaHeight;
            var depth = c == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
            var expected = hash[c];
            switch (type) {
                case HevcSei.HASH_MD5 -> {
                    var digest = plane8 != null ? md5(plane8, width, height)
                            : md5(plane, width, height, depth);
                    if (!Arrays.equals(digest, 0, 16, expected, 0, 16)) {
                        return false;
                    }
                }
                case HevcSei.HASH_CRC -> {
                    var crc = plane8 != null ? crc(plane8, width, height)
                            : crc(plane, width, height, depth);
                    if (crc != (((expected[0] & 0xFF) << 8) | (expected[1] & 0xFF))) {
                        return false;
                    }
                }
                default -> {
                    var sum = plane8 != null ? checksum(plane8, width, height)
                            : checksum(plane, width, height, depth);
                    var value = ((expected[0] & 0xFFL) << 24) | ((expected[1] & 0xFF) << 16)
                            | ((expected[2] & 0xFF) << 8) | (expected[3] & 0xFF);
                    if (sum != value) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Computes the MD5 picture hash of a component of 8-bit samples.
     *
     * @param plane the samples
     * @param width the component width
     * @param height the component height
     * @return the digest
     */
    private static byte[] md5(byte[] plane, int width, int height) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        for (var y = 0; y < height; y++) {
            digest.update(plane, y * width, width);
        }
        return digest.digest();
    }

    /**
     * Computes the CRC picture hash of a component of 8-bit samples.
     *
     * @param plane the samples
     * @param width the component width
     * @param height the component height
     * @return the CRC
     */
    private static int crc(byte[] plane, int width, int height) {
        var crc = 0xFFFF;
        for (var i = 0; i < width * height + 2; i++) {
            var dataByte = i < width * height ? plane[i] & 0xFF : 0;
            for (var bit = 0; bit < 8; bit++) {
                var msb = (crc >> 15) & 1;
                var bitVal = (dataByte >> (7 - bit)) & 1;
                crc = (((crc << 1) + bitVal) & 0xFFFF) ^ (msb * 0x1021);
            }
        }
        return crc;
    }

    /**
     * Computes the checksum picture hash of a component of 8-bit samples.
     *
     * @param plane the samples
     * @param width the component width
     * @param height the component height
     * @return the checksum
     */
    private static long checksum(byte[] plane, int width, int height) {
        long sum = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var mask = (x & 0xFF) ^ (y & 0xFF) ^ (x >> 8) ^ (y >> 8);
                sum = (sum + ((plane[y * width + x] & 0xFF) ^ mask)) & 0xFFFFFFFFL;
            }
        }
        return sum;
    }

    /**
     * Computes the MD5 picture hash of a component.
     *
     * @param plane the samples
     * @param width the component width
     * @param height the component height
     * @param depth the bit depth
     * @return the digest
     */
    private static byte[] md5(short[] plane, int width, int height, int depth) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        var bytes = depth > 8 ? 2 : 1;
        var row = new byte[width * bytes];
        for (var y = 0; y < height; y++) {
            var offset = y * width;
            for (var x = 0; x < width; x++) {
                var value = plane[offset + x];
                if (bytes == 1) {
                    row[x] = (byte) value;
                } else {
                    row[2 * x] = (byte) value;
                    row[2 * x + 1] = (byte) (value >> 8);
                }
            }
            digest.update(row);
        }
        return digest.digest();
    }

    /**
     * Computes the CRC picture hash of a component.
     *
     * @param plane the samples
     * @param width the component width
     * @param height the component height
     * @param depth the bit depth
     * @return the CRC
     */
    private static int crc(short[] plane, int width, int height, int depth) {
        var crc = 0xFFFF;
        var bytes = depth > 8 ? 2 : 1;
        for (var i = 0; i < width * height + (bytes == 1 ? 2 : 1); i++) {
            for (var b = 0; b < bytes; b++) {
                var dataByte = 0;
                if (i < width * height) {
                    var value = plane[i] & 0xFFFF;
                    dataByte = b == 0 ? value & 0xFF : value >> 8;
                }
                for (var bit = 0; bit < 8; bit++) {
                    var msb = (crc >> 15) & 1;
                    var bitVal = (dataByte >> (7 - bit)) & 1;
                    crc = (((crc << 1) + bitVal) & 0xFFFF) ^ (msb * 0x1021);
                }
            }
        }
        return crc;
    }

    /**
     * Computes the checksum picture hash of a component.
     *
     * @param plane the samples
     * @param width the component width
     * @param height the component height
     * @param depth the bit depth
     * @return the checksum
     */
    private static long checksum(short[] plane, int width, int height, int depth) {
        long sum = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var mask = (x & 0xFF) ^ (y & 0xFF) ^ (x >> 8) ^ (y >> 8);
                var value = plane[y * width + x] & 0xFFFF;
                sum = (sum + ((value & 0xFF) ^ mask)) & 0xFFFFFFFFL;
                if (depth > 8) {
                    sum = (sum + ((value >> 8) ^ mask)) & 0xFFFFFFFFL;
                }
            }
        }
        return sum;
    }
}

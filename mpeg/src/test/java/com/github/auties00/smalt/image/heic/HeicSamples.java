package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.validate.Findings;
import com.github.auties00.smalt.testkit.validate.IsoBmff;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderSpeed;

import java.util.List;

/**
 * Synthetic pictures and parsing helpers shared by the HEIC encoder tests: smooth gradients with a little
 * deterministic noise, which code well at moderate quality, and an alpha ramp.
 */
final class HeicSamples {
    private HeicSamples() {
    }

    /** Returns an 8-bit picture whose content moves with {@code frame}. */
    static RgbaPicture rgba(int width, int height, int frame, boolean alpha) {
        var pixels = new byte[width * height * 4];
        var state = 12345 + frame * 7919;
        for (var i = 0; i < pixels.length; i += 4) {
            state = state * 1664525 + 1013904223;
            var x = i / 4 % width + frame * 2;
            var y = i / 4 / width + frame;
            pixels[i] = (byte) Math.min(255, x * 200 / width + 20 + ((state >>> 20) & 3));
            pixels[i + 1] = (byte) Math.min(255, y * 200 / height + 30 + ((state >>> 16) & 3));
            pixels[i + 2] = (byte) (128 + 60 * Math.sin((x + y) / 9.0));
            pixels[i + 3] = (byte) (alpha ? Math.min(255, 40 + (i / 4 % width) * 215 / Math.max(1, width - 1)) : 255);
        }
        return RgbaPicture.of(width, height, width * 4, pixels, alpha);
    }

    /** Returns a 16-bit picture of the same content with 10 significant bits of detail. */
    static Rgba16Picture rgba16(int width, int height, boolean alpha) {
        var pixels = new short[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = (y * width + x) * 4;
                pixels[i] = (short) (x * 60000 / width + 2000);
                pixels[i + 1] = (short) (y * 60000 / height + 1000);
                pixels[i + 2] = (short) (32768 + 20000 * Math.sin((x + y) / 11.0));
                pixels[i + 3] = (short) (alpha ? 65535 * x / Math.max(1, width - 1) : 65535);
            }
        }
        return Rgba16Picture.of(width, height, width * 4, pixels, alpha);
    }

    /** Returns quick settings: quality 80 at the fastest speed on the calling thread. */
    static HeicEncoderOptions.Builder fast() {
        return HeicEncoderOptions.ofQuality(80).toBuilder().speed(HevcEncoderSpeed.FASTEST).threads(1);
    }

    /** Parses a file strictly, enforcing the rules on essential properties and repeated inputs. */
    static HeifFile parse(byte[] file) {
        return HeifFile.parse(MediaSource.of(file), MediaLimits.DEFAULT, true);
    }

    /** Returns the top-level boxes of a file. */
    static List<IsoBmff.Box> boxes(byte[] file) {
        return IsoBmff.parse(file, new Findings("heic"));
    }

    /** Returns the {@code general_profile_idc} of an {@code hvcC} record. */
    static int profileIdc(byte[] hvcC) {
        return hvcC[1] & 0x1F;
    }

    /** Returns the luma bit depth of an {@code hvcC} record. */
    static int bitDepth(byte[] hvcC) {
        return (hvcC[17] & 7) + 8;
    }
}

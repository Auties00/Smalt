package com.github.auties00.smalt.capture.internal.macos;

import java.util.Objects;

/**
 * Converts between four-character codes ({@code OSType}) and their text.
 *
 * <p>A four-character code packs four ASCII characters big-endian into 32 bits, so
 * {@code '420v'} is {@code 0x34323076}.
 */
final class FourCc {
    /**
     * {@code kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange} ({@code CVPixelBuffer.h}): NV12,
     * luma in 16..235 and chroma in 16..240.
     */
    static final int NV12_VIDEO_RANGE = of("420v");

    /**
     * {@code kCVPixelFormatType_420YpCbCr8BiPlanarFullRange} ({@code CVPixelBuffer.h}): NV12 with
     * full-range samples.
     */
    static final int NV12_FULL_RANGE = of("420f");

    /**
     * {@code kCVPixelFormatType_32BGRA} ({@code CVPixelBuffer.h}): packed 8-bit B, G, R, A.
     */
    static final int BGRA = of("BGRA");

    /**
     * Prevents instantiation.
     */
    private FourCc() {
        throw new AssertionError();
    }

    /**
     * Packs four ASCII characters into a code.
     *
     * @param text the four characters
     * @return the code
     * @throws NullPointerException if {@code text} is {@code null}
     * @throws IllegalArgumentException if {@code text} is not four ASCII characters
     */
    static int of(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() != 4) {
            throw new IllegalArgumentException("a four-character code needs four characters: " + text);
        }
        var code = 0;
        for (var i = 0; i < 4; i++) {
            var c = text.charAt(i);
            if (c > 0x7F) {
                throw new IllegalArgumentException("not an ASCII four-character code: " + text);
            }
            code = (code << 8) | c;
        }
        return code;
    }

    /**
     * Renders a code as text.
     *
     * @return the four characters in quotes when they are printable ASCII, else the code in
     * hexadecimal
     */
    static String toString(int code) {
        var text = new StringBuilder(6).append('\'');
        for (var shift = 24; shift >= 0; shift -= 8) {
            var c = (code >>> shift) & 0xFF;
            if (c < 0x20 || c > 0x7E) {
                return String.format("0x%08x", code);
            }
            text.append((char) c);
        }
        return text.append('\'').toString();
    }
}

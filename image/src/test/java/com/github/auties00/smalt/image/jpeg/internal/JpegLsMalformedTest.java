package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Feeds the JPEG-LS and hierarchical JPEG vectors, cut short and with flipped bits, to the decoder, which must
 * either decode them or fail with a {@link MediaException} other than an I/O failure, and checks the rejection of
 * damaged JPEG-LS headers.
 */
@DisplayName("JPEG-LS and hierarchical JPEG damage")
class JpegLsMalformedTest {
    static List<String> streams() {
        var names = new ArrayList<String>();
        for (var vector : JpegLsVectors.charls()) {
            names.add("jpegls/charls/" + vector.name() + ".jls");
        }
        for (var vector : JpegLsVectors.reference()) {
            names.add("jpegls/referenceA/" + vector.name() + ".jls");
        }
        for (var vector : HierarchicalTest.referenceCases()) {
            names.add("hierarchical/" + vector.name() + ".jpg");
        }
        return names;
    }

    private static byte[] read(String name) {
        try (var input = JpegLsMalformedTest.class.getResourceAsStream("/com/github/auties00/smalt/image/jpeg/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return input.readAllBytes();
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static void decodeOrReject(byte[] data, String what) {
        try {
            JpegDecoder.decode(MediaSource.of(data));
        } catch (MediaException exception) {
            assertFalse(exception instanceof MediaException.Io, what + ": " + exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("decode or fail cleanly when cut short or corrupted")
    void damage(String name) {
        var stream = read(name);
        var step = Math.max(1, stream.length / 400);
        for (var cut = 0; cut < stream.length; cut += step) {
            decodeOrReject(Arrays.copyOf(stream, cut), name + " cut at " + cut);
        }
        var random = new Random(name.hashCode());
        for (var trial = 0; trial < 150; trial++) {
            var damaged = stream.clone();
            for (var flips = 0; flips < 1 + trial % 4; flips++) {
                // half of the flips land in the first 64 bytes, where the headers are
                var at = trial % 2 == 0 ? random.nextInt(Math.min(64, damaged.length)) : random.nextInt(damaged.length);
                damaged[at] ^= (byte) (1 << random.nextInt(8));
            }
            decodeOrReject(damaged, name + " trial " + trial);
        }
    }

    // SOI, SOF55 of 8x8 8-bit gray, one LSE segment, SOS, two bytes of data and EOI
    private static byte[] withLse(byte[] lse) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xF7, 0, 11, 8, 0, 8, 0, 8, 1, 1, 0x11, 0});
        out.write(0xFF);
        out.write(0xF8);
        out.write((lse.length + 2) >> 8);
        out.write(lse.length + 2);
        out.writeBytes(lse);
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDA, 0, 8, 1, 1, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    @Test
    @DisplayName("reject preset parameters out of the ranges of T.87 table C.1 and a truncated preset segment as"
            + " malformed")
    void presets() {
        // MAXVAL 255, T1 above T2
        assertThrows(MediaException.Malformed.class, () -> JpegDecoder.decode(MediaSource.of(withLse(
                new byte[]{1, 0, (byte) 255, 0, 40, 0, 30, 0, 50, 0, 64}))));
        // RESET 2
        assertThrows(MediaException.Malformed.class, () -> JpegDecoder.decode(MediaSource.of(withLse(
                new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2}))));
        // MAXVAL beyond the 8-bit precision
        assertThrows(MediaException.Malformed.class, () -> JpegDecoder.decode(MediaSource.of(withLse(
                new byte[]{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0}))));
        // a truncated preset segment
        assertThrows(MediaException.Malformed.class, () -> JpegDecoder.decode(MediaSource.of(withLse(
                new byte[]{1, 0, 0, 0}))));
        // the defaults, written out, decode
        assertEquals(8, JpegDecoder.decode(MediaSource.of(withLse(new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0})))
                .picture().width());
    }
}

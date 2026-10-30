package com.github.auties00.smalt.hostile;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared machinery of the hostile-input tests: the limits they decode under, the allocation meter, the
 * small-stack runner and the byte and bit writers that forge headers.
 */
final class Hostile {
    /**
     * The limits the bombs exceed: 4096 pixels per side, 4 megapixels, 16 frames, 8 channels and 4 MiB per buffer.
     */
    static final MediaLimits LIMITS = MediaLimits.builder()
            .maxDimension(4096)
            .maxPixels(4L << 20)
            .maxFrames(16)
            .maxChannels(8)
            .maxBytes(4L << 20)
            .build();

    /**
     * The most a rejected bomb may allocate, on every thread together: well under what any declared size would
     * need, and well over the bookkeeping of opening a file.
     */
    static final long MAX_ALLOCATION = 24L << 20;

    /**
     * The stack of the threads that parse nested inputs: a quarter of the 1 MiB JVM default, so that a parser
     * whose depth is bounded passes with a wide margin and one whose depth follows the input overflows.
     */
    static final long SMALL_STACK = 256 * 1024;

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private Hostile() {
        throw new AssertionError();
    }

    /**
     * A step that may throw anything.
     */
    @FunctionalInterface
    interface Step {
        void run() throws Exception;
    }

    /**
     * Runs a step that must be rejected with a {@code MediaException} of the expected type, without allocating
     * more than {@link #MAX_ALLOCATION} and within ten seconds.
     *
     * @param what the input, for messages
     * @param expected the exception the step must throw
     * @param step the step
     * @return the exception
     */
    static MediaException assertRejected(String what, Class<? extends MediaException> expected, Step step) {
        var thrown = new AtomicReference<Throwable>();
        var before = THREADS.getTotalThreadAllocatedBytes();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try {
                step.run();
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        }, what + " did not finish");
        var allocated = THREADS.getTotalThreadAllocatedBytes() - before;
        var failure = thrown.get();
        if (failure == null) {
            fail(what + " was accepted; expected " + expected.getSimpleName());
        }
        if (!(failure instanceof MediaException media)) {
            throw new AssertionError(what + " threw " + failure + " instead of " + expected.getSimpleName(), failure);
        }
        assertNoHiddenFailure(what, media);
        if (!expected.isInstance(media)) {
            throw new AssertionError(what + " threw " + media + " instead of " + expected.getSimpleName(), media);
        }
        if (allocated > MAX_ALLOCATION) {
            fail(what + " allocated " + (allocated >> 20) + " MiB before rejecting the input: " + media.getMessage());
        }
        return media;
    }

    /**
     * Runs a step that may either return a result or be rejected with a {@code MediaException}, without allocating
     * more than {@link #MAX_ALLOCATION} and within ten seconds.
     *
     * @param <T> the result type
     * @param what the input, for messages
     * @param step the step
     * @return the result, or {@code null} when the step was rejected
     */
    static <T> T assertBounded(String what, Callable<T> step) {
        var thrown = new AtomicReference<Throwable>();
        var result = new AtomicReference<T>();
        var before = THREADS.getTotalThreadAllocatedBytes();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try {
                result.set(step.call());
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        }, what + " did not finish");
        var allocated = THREADS.getTotalThreadAllocatedBytes() - before;
        if (allocated > MAX_ALLOCATION) {
            fail(what + " allocated " + (allocated >> 20) + " MiB");
        }
        var failure = thrown.get();
        if (failure == null) {
            return result.get();
        }
        if (!(failure instanceof MediaException media)) {
            throw new AssertionError(what + " threw " + failure + " instead of a MediaException", failure);
        }
        assertNoHiddenFailure(what, media);
        return null;
    }

    /**
     * Fails when a {@code MediaException} only reports that the decoder ran out of memory or stack, which the
     * entry-point guard translates, instead of a check of the input.
     *
     * @param what the input, for messages
     * @param exception the exception
     */
    static void assertNoHiddenFailure(String what, MediaException exception) {
        var message = String.valueOf(exception.getMessage());
        if (message.endsWith(" needs more memory than is available") || message.endsWith(" nests too deeply")) {
            throw new AssertionError(what + " ran out of memory or stack instead of checking its input: " + message,
                    exception);
        }
        for (var cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof Error) {
                throw new AssertionError(what + " hid " + cause, exception);
            }
        }
    }

    /**
     * Runs a step on a new platform thread with a {@link #SMALL_STACK} stack, within thirty seconds.
     *
     * @param what the input, for messages
     * @param step the step
     * @return what the step threw, or {@code null}
     */
    static Throwable onSmallStack(String what, Step step) {
        var thrown = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                step.run();
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        }, "hostile-small-stack", SMALL_STACK);
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(Duration.ofSeconds(30));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
        if (thread.isAlive()) {
            fail(what + " did not finish within 30 seconds");
        }
        return thrown.get();
    }

    static byte[] concat(byte[]... parts) {
        var out = new java.io.ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static void be16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    static void be32(byte[] data, int offset, long value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    static void le16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    static void le32(byte[] data, int offset, long value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    static int be16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
    }

    static long be32(byte[] data, int offset) {
        return (long) be16(data, offset) << 16 | be16(data, offset + 2);
    }

    static int le16(byte[] data, int offset) {
        return data[offset] & 0xFF | (data[offset + 1] & 0xFF) << 8;
    }

    static long le32(byte[] data, int offset) {
        return le16(data, offset) | (long) le16(data, offset + 2) << 16;
    }

    /**
     * Returns the CRC-32 of a byte range, as PNG and Ogg's companions use it.
     */
    static long crc32(byte[] data, int offset, int length) {
        var crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue();
    }

    /**
     * Returns the most-significant-bit-first CRC-32 of polynomial {@code 0x04C11DB7} without final inversion, the CRC
     * of Ogg pages (initial value 0) and of MPEG-2 sections (initial value all ones).
     */
    static long crc32Msb(byte[] data, int offset, int length, int initial) {
        var crc = initial;
        for (var index = offset; index < offset + length; index++) {
            crc ^= (data[index] & 0xFF) << 24;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000_0000) != 0 ? crc << 1 ^ 0x04C1_1DB7 : crc << 1;
            }
        }
        return crc & 0xFFFF_FFFFL;
    }

    /**
     * Returns the first offset of a byte pattern at or after a position, or -1.
     */
    static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (var index = Math.max(0, from); index <= data.length - pattern.length; index++) {
            for (var at = 0; at < pattern.length; at++) {
                if (data[index + at] != pattern[at]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    static int indexOf(byte[] data, String ascii, int from) {
        return indexOf(data, ascii.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), from);
    }

    /**
     * Writes bits most significant first, with the Exp-Golomb codes of H.264 and HEVC.
     */
    static final class MsbBits {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        private int current;
        private int count;

        MsbBits u(int bits, long value) {
            for (var bit = bits - 1; bit >= 0; bit--) {
                current = current << 1 | (int) (value >>> bit & 1);
                if (++count == 8) {
                    out.write(current);
                    current = 0;
                    count = 0;
                }
            }
            return this;
        }

        MsbBits ue(long value) {
            var coded = value + 1;
            var length = 64 - Long.numberOfLeadingZeros(coded);
            u(length - 1, 0);
            return u(length, coded);
        }

        MsbBits se(long value) {
            return ue(value <= 0 ? -2 * value : 2 * value - 1);
        }

        /** Ends an RBSP: a one bit, then zero bits up to the byte boundary. */
        byte[] trailing() {
            u(1, 1);
            return align();
        }

        byte[] align() {
            while (count != 0) {
                u(1, 0);
            }
            return out.toByteArray();
        }

        /** Writes the byte_alignment() of HEVC: a one bit, then zero bits up to the byte boundary. */
        MsbBits byteAlignment() {
            u(1, 1);
            while (count != 0) {
                u(1, 0);
            }
            return this;
        }

        /** Ends with one bits up to the byte boundary, as MPEG-4 Part 2 stuffing does. */
        byte[] alignOnes() {
            while (count != 0) {
                u(1, 1);
            }
            return out.toByteArray();
        }
    }

    /**
     * Writes bits least significant first, as JPEG XL and VP8L read them.
     */
    static final class LsbBits {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        private long current;
        private int count;

        LsbBits u(int bits, long value) {
            for (var bit = 0; bit < bits; bit++) {
                current |= (value >>> bit & 1) << count;
                if (++count == 8) {
                    out.write((int) current);
                    current = 0;
                    count = 0;
                }
            }
            return this;
        }

        /** Writes zero bits up to the byte boundary. */
        LsbBits pad() {
            while (count != 0) {
                u(1, 0);
            }
            return this;
        }

        byte[] bytes() {
            if (count > 0) {
                out.write((int) current);
                current = 0;
                count = 0;
            }
            return out.toByteArray();
        }
    }
}

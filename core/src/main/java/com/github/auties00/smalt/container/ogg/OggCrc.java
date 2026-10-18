package com.github.auties00.smalt.container.ogg;

import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Computes the CRC-32 that protects every Ogg page (RFC 3533 section 6).
 *
 * <p>The checksum uses the generator polynomial {@code 0x04C11DB7}, processes bits
 * most-significant first, starts from zero and is not complemented at the end; it is computed
 * over the whole page with the four checksum bytes set to zero. An instance holds scratch space
 * and is not thread-safe.
 *
 * @implNote This implementation runs the bytes through {@link CRC32}, whose update is a JVM
 * intrinsic built on carry-less multiplication where the processor has one. That CRC uses the
 * same polynomial processed least-significant bit first, so every byte is bit-reversed first
 * ({@link BitReverser}) and the 32-bit result is bit-reversed back. {@link CRC32} also starts
 * from all ones and complements its result: four {@code 0xFF} bytes fed first drive its register
 * from all ones to zero, the start the Ogg CRC needs, and complementing the reported value undoes
 * the final complement. The result is identical to the byte-at-a-time table walk of libogg.
 */
final class OggCrc {
    /**
     * The offset of the checksum field in a page header.
     */
    private static final int CHECKSUM_OFFSET = 22;

    /**
     * The number of {@code 0xFF} bytes that clear the {@link CRC32} register.
     */
    private static final int PREFIX = 4;

    /**
     * The initial scratch size, enough for a voice-note page.
     */
    private static final int INITIAL_SCRATCH = 4096;

    /**
     * The JDK checksum engine.
     */
    private final CRC32 engine;

    /**
     * The {@code 0xFF} prefix followed by the bit-reversed bytes being checked.
     */
    private byte[] scratch;

    /**
     * Constructs a calculator.
     */
    OggCrc() {
        this.engine = new CRC32();
        this.scratch = new byte[INITIAL_SCRATCH];
        Arrays.fill(scratch, 0, PREFIX, (byte) 0xFF);
    }

    /**
     * Computes the checksum of a range of bytes as they are.
     *
     * @param data   the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return the checksum
     */
    int checksum(byte[] data, int offset, int length) {
        prepare(data, offset, length);
        return finish(length);
    }

    /**
     * Computes the checksum of a page, treating its checksum field as zero.
     *
     * @param data   the array holding the page
     * @param offset the index of the page's capture pattern
     * @param length the page size, at least the {@value OggPageReader#HEADER_SIZE}-byte header
     * @return the checksum the page must store
     */
    int page(byte[] data, int offset, int length) {
        prepare(data, offset, length);
        Arrays.fill(scratch, PREFIX + CHECKSUM_OFFSET, PREFIX + CHECKSUM_OFFSET + 4, (byte) 0);
        return finish(length);
    }

    /**
     * Writes the bit-reversed bytes after the prefix, growing the scratch space when needed.
     *
     * @param data   the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     */
    private void prepare(byte[] data, int offset, int length) {
        if (PREFIX + length > scratch.length) {
            scratch = Arrays.copyOf(scratch, Math.max(PREFIX + length, 2 * scratch.length));
        }
        BitReverser.INSTANCE.reverse(data, offset, scratch, PREFIX, length);
    }

    /**
     * Runs the prefix and the prepared bytes through the engine.
     *
     * @param length the number of prepared bytes after the prefix
     * @return the checksum
     */
    private int finish(int length) {
        engine.reset();
        engine.update(scratch, 0, PREFIX + length);
        return Integer.reverse(~(int) engine.getValue());
    }
}

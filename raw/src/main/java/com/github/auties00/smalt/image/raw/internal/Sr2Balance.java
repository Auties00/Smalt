package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;

import java.util.List;

/**
 * Reads the as-shot white balance of a Sony file from its encrypted {@code SR2} directory, for the files whose raw
 * directory carries no {@code WB_RGGBLevels} tag.
 *
 * <p>The first directory's {@code DNGPrivateData} tag ({@code 0xC634}) holds the offset of the {@code SR2Private}
 * directory, whose tags {@code 0x7200} and {@code 0x7201} give the offset and length of the encrypted
 * {@code SR2SubIFD} block and tag {@code 0x7221} its key. Every file of the raw.pixls.us archive uses the key
 * 1144201745, and with one key every block is the same keystream XORed onto its plain bytes, so a value whose
 * plain bytes are known in some file reveals the keystream where that file stores it, for the blocks of every other
 * file too. A block's first byte is the low byte of its encrypted entry count, and the blocks of one count keep their
 * black levels at one offset and the as-shot red, green, green and blue levels (signed 16-bit little-endian
 * values) sixteen bytes after them, whatever the block's length:
 * <ul>
 * <li>first byte 133: as-shot levels at 2526 (the RX0 to RX100 compacts, the ILCA-68, 77 II and 99 II, the
 * ILCE-3000 to 7S II, the NEX-3N, 5N, 5T, 6 and F3, the QX1, the SLT-A37 to A99 and the UMC-R10C);</li>
 * <li>first byte 157: at 2434 (the DSLR-A560, NEX-5, NEX-C3 and SLT-A33, A35 and A55);</li>
 * <li>first byte 220: at 1654 (the DSLR-A200, A350 and A700);</li>
 * <li>first byte 224: at 2182 (the DSLR-A230, A290, A380, A390, A450, A500 and A900).</li>
 * </ul>
 *
 * @implNote This implementation's layouts and keystream bytes were derived from the encrypted blocks of the 195 CC0
 * ARW files of the archive, without any description of the cipher. The files whose raw directory also carries the
 * {@code BlackLevel} and {@code WB_RGGBLevels} tags show, by the offsets at which those values sit under one
 * keystream, the offset and keystream bytes of the first layout. For the others, copies of a CC0 file with block
 * bytes inverted, converted by Adobe DNG Converter run as a black box, show which bytes its as-shot neutral depends
 * on (the four levels, the black levels before them, without which the converter writes no file, and nothing else),
 * and copies with one bit of a level flipped show, by the direction the converter's neutral moves, each plain bit:
 * the DSLR-A450 file gives the keystream at 2182 and the DSLR-A700 file the keystream at 1654, which decrypt the
 * levels of every other file of their layouts to the converter's white balance (the DSLR-A500 and A900, and the
 * A200 to A390, which the converter reads but this decoder does not); the layout of first byte 157 was found from
 * the converter's white balance of its six files, and copies of the SLT-A55 file with the bytes at 2434 inverted
 * move the converter's neutral, while those with the bytes 16 before them inverted make it refuse the file. Files
 * of other layouts or keys are not read.
 */
final class Sr2Balance {
    /**
     * The key every known file uses.
     */
    private static final long KEY = 1144201745L;

    /**
     * A layout of the encrypted block: its first byte, the offset of the as-shot levels and the keystream bytes there.
     *
     * @param first the block's first byte
     * @param offset the offset of the as-shot levels in the block
     * @param keystream the eight keystream bytes at the offset
     */
    private record Layout(int first, int offset, byte[] keystream) {
    }

    /**
     * The known layouts.
     */
    private static final List<Layout> LAYOUTS = List.of(
            new Layout(133, 2526, bytes(0x43, 0x25, 0x71, 0x5f, 0x88, 0xb3, 0x09, 0x75)),
            new Layout(157, 2434, bytes(0x14, 0x4a, 0x05, 0x3b, 0x0d, 0xe6, 0xe1, 0xc9)),
            new Layout(220, 1654, bytes(0x99, 0x22, 0xb6, 0xa0, 0x1f, 0xc5, 0x6e, 0x36)),
            new Layout(224, 2182, bytes(0x1d, 0x62, 0xed, 0xd7, 0x0d, 0xac, 0x33, 0xdf)));

    /**
     * Prevents instantiation.
     */
    private Sr2Balance() {
        throw new AssertionError();
    }

    /**
     * Returns the as-shot neutral of a file from its encrypted directory.
     *
     * @param reader the TIFF structure
     * @param first the first directory
     * @return the neutral as red, green and blue, or {@code null} when the file has no encrypted directory of a
     * known layout and key, its levels are not positive, or its structure is damaged or exceeds the limits
     */
    static double[] neutral(TiffReader reader, Ifd first) {
        try {
            var pointer = first.bytes(0xC634);
            if (pointer == null || pointer.length != 4) {
                return null;
            }
            return read(reader, reader.directory(u32(pointer, reader.little())));
        } catch (MediaException.Malformed | MediaException.LimitExceeded exception) {
            // a damaged or oversized structure leaves the white balance to the estimate
            return null;
        }
    }

    /**
     * Returns the as-shot neutral the encrypted block an {@code SR2Private} directory points to holds.
     *
     * @param reader the TIFF structure
     * @param sr2 the {@code SR2Private} directory
     * @return the neutral, or {@code null} for a block of an unknown layout or key or with levels that are not
     * positive
     * @throws MediaException.Malformed if the block lies outside the file
     * @throws MediaException.LimitExceeded if a tag of the directory holds more bytes than the limits allow
     */
    private static double[] read(TiffReader reader, Ifd sr2) {
        var offset = reader.base() + sr2.longValue(0x7200, -1);
        var length = sr2.longValue(0x7201, -1);
        var key = sr2.bytes(0x7221);
        if (offset <= reader.base() || length <= 0 || key == null || key.length != 4
                || u32(key, reader.little()) != KEY) {
            return null;
        }
        var head = reader.bytes(offset, 1);
        for (var layout : LAYOUTS) {
            if ((head[0] & 0xFF) != layout.first() || layout.offset() + 8 > length) {
                continue;
            }
            var data = reader.bytes(offset + layout.offset(), 8);
            var levels = new int[4];
            for (var k = 0; k < 4; k++) {
                var low = (data[2 * k] ^ layout.keystream()[2 * k]) & 0xFF;
                var high = (data[2 * k + 1] ^ layout.keystream()[2 * k + 1]) & 0xFF;
                levels[k] = (short) (low | high << 8);
            }
            if (levels[0] <= 0 || levels[1] <= 0 || levels[2] <= 0 || levels[3] <= 0) {
                return null;
            }
            var green = (levels[1] + levels[2]) / 2.0;
            return new double[]{green / levels[0], 1, green / levels[3]};
        }
        return null;
    }

    /**
     * Returns the unsigned 32-bit integer of the first four bytes of an array.
     *
     * @param data the bytes, at least four
     * @param little whether the integer is little endian
     * @return the integer
     */
    private static long u32(byte[] data, boolean little) {
        return little ? (data[0] & 0xFFL) | (data[1] & 0xFFL) << 8 | (data[2] & 0xFFL) << 16 | (data[3] & 0xFFL) << 24
                : (data[3] & 0xFFL) | (data[2] & 0xFFL) << 8 | (data[1] & 0xFFL) << 16 | (data[0] & 0xFFL) << 24;
    }

    /**
     * Returns the bytes of a list of values, each truncated to its low eight bits.
     *
     * @param values the byte values, of which only the low eight bits count
     * @return the bytes
     */
    private static byte[] bytes(int... values) {
        var out = new byte[values.length];
        for (var k = 0; k < values.length; k++) {
            out[k] = (byte) values[k];
        }
        return out;
    }
}

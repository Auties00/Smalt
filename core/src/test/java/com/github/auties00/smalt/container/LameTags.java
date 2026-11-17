package com.github.auties00.smalt.container;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the Xing and LAME tags of an MP3 file that starts with its tag frame, for the gapless tests.
 */
final class LameTags {
    private LameTags() {
    }

    /** Returns the offset of the Xing or Info word: after the header and the side information. */
    static int xingOffset(byte[] file) {
        var mpeg1 = (file[1] & 0x08) != 0;
        var mono = (file[3] & 0xC0) == 0xC0;
        var side = mpeg1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
        var offset = 4 + side;
        var word = new String(file, offset, 4, StandardCharsets.US_ASCII);
        assertTrue(word.equals("Info") || word.equals("Xing"), "no tag frame: " + word);
        return offset;
    }

    /** Returns the offset of the LAME tag, past the Xing fields its flags announce. */
    static int lameOffset(byte[] file) {
        var xing = xingOffset(file);
        var flags = file[xing + 7] & 0xFF;
        var offset = xing + 8;
        offset += (flags & 1) != 0 ? 4 : 0;
        offset += (flags & 2) != 0 ? 4 : 0;
        offset += (flags & 4) != 0 ? 100 : 0;
        offset += (flags & 8) != 0 ? 4 : 0;
        return offset;
    }

    /** Returns the encoder delay and padding of the LAME tag. */
    static int[] delayAndPadding(byte[] file) {
        var p = lameOffset(file) + 21;
        return new int[]{(file[p] & 0xFF) << 4 | (file[p + 1] & 0xFF) >> 4, (file[p + 1] & 0x0F) << 8 | file[p + 2] & 0xFF};
    }

    /** Returns whether the LAME tag area holds no encoder string. */
    static boolean isAbsent(byte[] file) {
        var p = lameOffset(file);
        for (var i = 0; i < 9; i++) {
            if (file[p + i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** Checks the music length, the music CRC and the tag CRC against the file. */
    static void assertChecksums(byte[] file) {
        var lame = lameOffset(file);
        assertEquals("LAME", new String(file, lame, 4, StandardCharsets.US_ASCII));
        var length = java.nio.ByteBuffer.wrap(file).getInt(lame + 28) & 0xFFFFFFFFL;
        assertEquals(file.length, length, "music length");
        var tagFrame = frameLength(file);
        var music = crc(0, file, tagFrame, file.length - tagFrame);
        assertEquals(music, (file[lame + 32] & 0xFF) << 8 | file[lame + 33] & 0xFF, "music CRC");
        var head = java.util.Arrays.copyOf(file, 190);
        java.util.Arrays.fill(head, Math.min(tagFrame, 190), 190, (byte) 0);
        if (lame + 34 < 190) {
            head[lame + 34] = 0;
            head[lame + 35] = 0;
        }
        assertEquals(crc(0, head, 0, 190), (file[lame + 34] & 0xFF) << 8 | file[lame + 35] & 0xFF, "tag CRC");
    }

    /** Returns the length of the tag frame from its header. */
    private static int frameLength(byte[] file) {
        var header = java.nio.ByteBuffer.wrap(file).getInt(0);
        return com.github.auties00.smalt.internal.header.MpegAudioHeader.frameBytes(header, 0)
                + com.github.auties00.smalt.internal.header.MpegAudioHeader.padding(header);
    }

    /** The reflected CRC-16 (polynomial 0xA001, initial value 0) of the LAME tag. */
    private static int crc(int crc, byte[] data, int offset, int length) {
        for (var i = offset; i < offset + length; i++) {
            crc ^= data[i] & 0xFF;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? crc >>> 1 ^ 0xA001 : crc >>> 1;
            }
        }
        return crc;
    }
}

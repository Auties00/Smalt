package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Checks the {@code DNGPrivateData} a converted DNG file holds for a source with an EXIF maker note, on synthetic
 * TIFF structures in either byte order and at a non-zero base: the exact Adobe {@code MakN} block, and that a
 * maker note lying outside the file or of a numeric type is not carried over.
 */
class DngPrivateDataTest {
    /** The position of the maker note relative to the TIFF header in {@link #tiff}. */
    static final int NOTE_OFFSET = 44;

    /**
     * Writes a TIFF structure, after {@code prefix} unrelated bytes, whose EXIF directory holds a maker note of
     * {@code length} bytes at {@link #NOTE_OFFSET}, recorded with the given type and count.
     */
    static byte[] tiff(ByteOrder order, int prefix, int type, int count, byte[] note) {
        var b = ByteBuffer.allocate(prefix + NOTE_OFFSET + note.length).order(order);
        for (var index = 0; index < prefix; index++) {
            b.put((byte) 0x5A);
        }
        var mark = order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M';
        b.put(mark).put(mark).putShort((short) 42).putInt(8);
        b.putShort((short) 1).putShort((short) TiffTree.EXIF_IFD).putShort((short) 4).putInt(1).putInt(26).putInt(0);
        b.putShort((short) 1).putShort((short) TiffTree.MAKER_NOTE).putShort((short) type).putInt(count)
                .putInt(NOTE_OFFSET).putInt(0);
        b.put(note);
        return b.array();
    }

    static RawFile describe(byte[] data, int base) {
        var source = MediaSource.of(data);
        var file = new RawFile(source, MediaLimits.DEFAULT, RawFormat.NEF);
        new TiffTree(TiffReader.open(source, base, MediaLimits.DEFAULT)).describe(file);
        return file;
    }

    static byte[] note() {
        var note = "Nikon\0\2\0\0\0MM\0*\0\0\0\10 some maker note bytes".getBytes(StandardCharsets.US_ASCII);
        return Arrays.copyOf(note, 40);
    }

    @ParameterizedTest(name = "little endian {0}, base {1}")
    @CsvSource({"true, 0", "false, 0", "true, 12", "false, 7"})
    @DisplayName("holds the maker note in Adobe's MakN form, with its byte order and its offset from the TIFF header")
    void makerNote(boolean little, int prefix) {
        var note = note();
        var data = tiff(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN, prefix, 7, note.length, note);
        var block = DngWriting.privateData(describe(data, prefix));
        var expected = ByteBuffer.allocate(20 + note.length).order(ByteOrder.BIG_ENDIAN);
        expected.put("Adobe\0MakN".getBytes(StandardCharsets.US_ASCII)).putInt(6 + note.length);
        var mark = little ? (byte) 'I' : (byte) 'M';
        expected.put(mark).put(mark).putInt(NOTE_OFFSET).put(note);
        assertArrayEquals(expected.array(), block);
    }

    @Test
    @DisplayName("leaves out a maker note that runs past the end of the file, is of a numeric type or is empty")
    void invalid() {
        var note = note();
        assertNull(DngWriting.privateData(describe(tiff(ByteOrder.LITTLE_ENDIAN, 0, 7, note.length + 1, note), 0)));
        assertNull(DngWriting.privateData(describe(tiff(ByteOrder.LITTLE_ENDIAN, 0, 3, note.length / 2, note), 0)));
        assertNull(DngWriting.privateData(describe(tiff(ByteOrder.BIG_ENDIAN, 0, 7, 0, note), 0)));
        var plain = describe(tiff(ByteOrder.LITTLE_ENDIAN, 0, 1, note.length, note), 0);
        assertEquals(NOTE_OFFSET, plain.makerNoteOffset, "a maker note of bytes is carried over too");
    }
}

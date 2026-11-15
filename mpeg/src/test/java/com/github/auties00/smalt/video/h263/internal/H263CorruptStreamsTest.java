package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edits the headers and macroblock data of committed annex streams at known bit positions, or builds pictures from
 * their header bits, to check that the annex engine refuses inconsistent pictures as malformed and keeps decoding
 * the rest: enhancement pictures whose reference layer holds no picture, pictures and B-pictures of another size
 * than their references, slices and groups of blocks outside the picture or cut short, data that ends inside a
 * header, end of sub-bitstream codes inside a picture, and codes past the end of the vector and reference index
 * tables.
 */
@DisplayName("H.263 corrupt annex streams")
class H263CorruptStreamsTest {
    /**
     * The bit offsets of the fields of a picture header with PLUSPTYPE, UFEP 1, no CPM and no custom format.
     */
    private static final int UFEP = 38;
    private static final int SOURCE_FORMAT = 41;
    private static final int PICTURE_TYPE = 59;
    private static final int UUI = 69;

    /**
     * The decode of a stream picture start code by picture start code.
     */
    record Units(List<H263AnnexStreamsTest.Decoded> pictures, List<Integer> refused) {
    }

    private static Units decodeUnits(byte[] data) {
        var stream = new H263PlusStream(MediaLimits.defaults());
        stream.useReferenceTransform(true);
        var pictures = new ArrayList<H263AnnexStreamsTest.Decoded>();
        H263PlusStream.Sink sink = (picture, time, keyframe, layer, subBitstream) -> pictures.add(
                new H263AnnexStreamsTest.Decoded(picture.width(), picture.height(),
                        H263AnnexStreamsTest.planar(picture), time, keyframe, layer));
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        var refused = new ArrayList<Integer>();
        for (var k = 0; k < starts.size(); k++) {
            var end = k + 1 < starts.size() ? starts.get(k + 1) : data.length;
            try {
                stream.decode(data, starts.get(k), end - starts.get(k), sink);
            } catch (MediaException.Malformed expected) {
                refused.add(k);
            }
        }
        try {
            stream.flush(sink);
        } catch (MediaException.Malformed expected) {
            refused.add(starts.size());
        }
        return new Units(pictures, refused);
    }

    private static int field(byte[] data, long at, int width) {
        var value = 0;
        for (var i = 0; i < width; i++) {
            var position = at + i;
            value = value << 1 | data[(int) (position >>> 3)] >>> (7 - (int) (position & 7)) & 1;
        }
        return value;
    }

    private static void setField(byte[] data, long at, int width, int value) {
        for (var i = 0; i < width; i++) {
            var position = at + i;
            var mask = 1 << (7 - (int) (position & 7));
            if ((value >>> (width - 1 - i) & 1) != 0) {
                data[(int) (position >>> 3)] |= (byte) mask;
            } else {
                data[(int) (position >>> 3)] &= (byte) ~mask;
            }
        }
    }

    /**
     * A sequence of bits.
     */
    private static final class Bits {
        private final StringBuilder bits = new StringBuilder();

        Bits code(String code) {
            bits.append(code.replace(" ", ""));
            return this;
        }

        Bits put(int value, int width) {
            for (var i = width - 1; i >= 0; i--) {
                bits.append((char) ('0' + (value >>> i & 1)));
            }
            return this;
        }

        Bits copy(byte[] data, long from, long to) {
            for (var at = from; at < to; at++) {
                put(field(data, at, 1), 1);
            }
            return this;
        }

        long length() {
            return bits.length();
        }

        byte[] bytes() {
            var out = new byte[(bits.length() + 7) / 8];
            for (var i = 0; i < bits.length(); i++) {
                if (bits.charAt(i) == '1') {
                    out[i >>> 3] |= (byte) (0x80 >>> (i & 7));
                }
            }
            return out;
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(first);
        out.writeBytes(second);
        return out.toByteArray();
    }

    private static byte[] picture(byte[] data, int index) {
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        var end = index + 1 < starts.size() ? starts.get(index + 1) : data.length;
        return java.util.Arrays.copyOfRange(data, starts.get(index), end);
    }

    /**
     * Returns the bit positions of the group of blocks and slice start codes: 16 zeros, a one, and a group number
     * other than 0.
     */
    private static List<Long> segmentStarts(byte[] data, int from, int to) {
        var out = new ArrayList<Long>();
        for (long at = from * 8L, zeros = 0; at + 22 <= to * 8L; at++) {
            if (field(data, at, 1) == 0) {
                zeros++;
                continue;
            }
            if (zeros >= 16 && field(data, at + 1, 5) != 0) {
                out.add(at - 16);
            }
            zeros = 0;
        }
        return out;
    }

    @Test
    @DisplayName("refuses enhancement pictures whose reference layer has no picture")
    void enhancementWithoutReferenceLayer() {
        // smalt-snr: base pictures, each followed by an EI- or EP-picture of layer 2 that references layer 1; the
        // edit moves the enhancement pictures to layer 3, the EI-picture still referencing layer 1 and the
        // EP-pictures layer 2, which holds no picture
        var data = H263AnnexStreamsTest.resource("smalt-snr.263");
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        var edited = 0;
        var predicted = 0;
        for (int start : starts) {
            var at = start * 8L;
            var type = field(data, at + PICTURE_TYPE, 3);
            if (type != 4 && type != 5) {
                continue;
            }
            assertEquals(1, field(data, at + UFEP, 3), "UFEP");
            assertEquals(2, field(data, at + UUI + 1, 4), "ELNUM");
            assertEquals(1, field(data, at + UUI + 5, 4), "RLNUM");
            setField(data, at + UUI + 1, 4, 3);
            if (type == 5) {
                setField(data, at + UUI + 5, 4, 2);
                predicted++;
            }
            edited++;
        }
        assertEquals(4, edited);
        var units = decodeUnits(data);
        // one refusal for every EP-picture, and one when the flush finds nothing decoded for them
        assertEquals(predicted, units.refused().stream().filter(k -> k < starts.size()).count(), "refused");
        assertTrue(units.pictures().stream().anyMatch(picture -> picture.layer() == 3), "the EI-picture of layer 3");
        assertFalse(units.pictures().stream().anyMatch(picture -> picture.layer() == 2), "nothing in layer 2");
    }

    @Test
    @DisplayName("refuses a B-picture of another size than its backward reference")
    void bPictureOfAnotherSize() {
        var data = H263AnnexStreamsTest.resource("smalt-b.263");
        var original = decodeUnits(data.clone());
        assertTrue(original.refused().isEmpty());
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        var edited = new ArrayList<Integer>();
        for (var k = 0; k < starts.size(); k++) {
            var at = starts.get(k) * 8L;
            if (field(data, at + UFEP, 3) == 1 && field(data, at + PICTURE_TYPE, 3) == 3) {
                assertEquals(2, field(data, at + SOURCE_FORMAT, 3), "QCIF");
                setField(data, at + SOURCE_FORMAT, 3, 1);
                edited.add(k);
            }
        }
        assertFalse(edited.isEmpty());
        var units = decodeUnits(data);
        assertEquals(edited, units.refused().stream().filter(k -> k < starts.size()).toList());
        assertEquals(original.pictures().size() - edited.size(), units.pictures().size());
        for (var picture : units.pictures()) {
            assertEquals(176, picture.width());
        }
    }

    @Test
    @DisplayName("refuses a picture without PLUSPTYPE of another source format than its reference")
    void referenceOfAnotherFormat() {
        // smalt-v1-umv: an I-picture and P-pictures, all QCIF; PTYPE starts at bit 30 and its source format at 35
        var data = H263AnnexStreamsTest.resource("smalt-v1-umv.263");
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        var at = starts.get(1) * 8L;
        assertEquals(0b10, field(data, at + 30, 2), "PTYPE");
        assertEquals(2, field(data, at + 35, 3), "QCIF");
        setField(data, at + 35, 3, 1);
        var units = decodeUnits(data);
        assertTrue(units.refused().contains(1), "the SQCIF P-picture after a QCIF picture");
        assertFalse(units.refused().contains(0));

        // a 176x96 INTRA picture of a custom format (PLUSPTYPE, CPFMT), its data missing, then a QCIF P-picture
        // without PLUSPTYPE: the same width and another height
        var custom = new Bits()
                .code("0000 0000 0000 0000 1000 00").put(0, 8).code("1000 0111").code("001")
                .code("110 0 0000000000 1 0 0 0").code("000 0 0 0 001").code("0")
                .code("0010").put(176 / 4 - 1, 9).code("1").put(96 / 4, 9).put(8, 5).code("0");
        custom.put(0, (int) ((8 - custom.length() % 8) % 8));
        var p = new Bits()
                .code("0000 0000 0000 0000 1000 00").put(1, 8)
                .code("10 000 010 1 0 0 0 0").put(8, 5).code("0 0").code("1".repeat(48));
        var mixed = decodeUnits(concat(custom.bytes(), p.bytes()));
        assertEquals(List.of(0, 1), mixed.refused().stream().filter(k -> k < 2).toList());
        assertEquals(1, mixed.pictures().size(), "the concealed INTRA picture");
        assertEquals(96, mixed.pictures().getFirst().height());
    }

    @Test
    @DisplayName("refuses a picture whose first slice starts outside it")
    void firstSliceOutsidePicture() {
        // smalt-large-slices: a 4CIF picture header of 78 bits (UUI and SSS, no custom format) followed by SEPB1 and
        // the 11-bit address of the first slice, 0
        var data = H263AnnexStreamsTest.resource("smalt-large-slices.263");
        var at = H263AnnexStreamsTest.pictureStarts(data).getFirst() * 8L;
        assertEquals(4, field(data, at + SOURCE_FORMAT, 3), "4CIF");
        assertEquals(1, field(data, at + 78, 1), "SEPB1");
        assertEquals(0, field(data, at + 79, 11), "MBA");
        setField(data, at + 79, 11, 2047);
        var units = decodeUnits(data);
        assertTrue(units.refused().contains(0));
        assertEquals(1, units.pictures().size(), "the 16CIF picture");
        assertEquals(1408, units.pictures().getFirst().width());
    }

    @Test
    @DisplayName("refuses a picture whose data ends inside the quantizer of a group of blocks header")
    void truncatedGroupOfBlocksHeader() {
        // smalt-stuffing has groups of blocks headers at any bit position: GBSC, GN, GFID and then GQUANT at 24 bits
        // from the start code; the data is cut at a byte boundary inside GQUANT, whose first bit is set so that the
        // quantizer reads as nonzero, and the reader overruns
        var data = H263AnnexStreamsTest.resource("smalt-stuffing.263");
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        var cut = -1L;
        var picture = -1;
        for (var k = 0; k + 1 < starts.size() && cut < 0; k++) {
            for (long header : segmentStarts(data, starts.get(k), starts.get(k + 1))) {
                var quant = header + 24;
                var end = (quant + 7) & ~7L;
                if (end > quant && end <= quant + 4) {
                    setField(data, quant, 1, 1);
                    cut = end;
                    picture = k;
                    break;
                }
            }
        }
        assertEquals(4, picture, "the P-picture with a group of blocks header to cut");
        var truncated = java.util.Arrays.copyOf(data, (int) (cut >>> 3));
        var units = decodeUnits(truncated);
        assertTrue(units.refused().contains(picture), "the truncated picture");
        assertEquals(picture + 1, units.pictures().size(), "the pictures up to the truncated one");
    }

    @Test
    @DisplayName("conceals a data partitioned slice whose data ends after its header")
    void emptyPartitionedSlice() {
        // smalt-dps: rectangular data partitioned slices whose byte aligned headers take 37 bits (SSC, SEPB1, a 7-bit
        // MBA, SQUANT, a 4-bit SWI, SEPB3 and GFID); the data is cut after the first slice header but the one
        // following the picture header, with the rest of its last byte cleared
        var data = H263AnnexStreamsTest.resource("smalt-dps.263");
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        var slice = -1;
        for (var i = starts.getFirst() + 3; i + 2 < starts.get(1); i++) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] & 0xC0) == 0xC0) {
                slice = i;
                break;
            }
        }
        assertTrue(slice > 0, "a slice header");
        var truncated = java.util.Arrays.copyOf(data, slice + 5);
        setField(truncated, slice * 8L + 37, 3, 0);
        var units = decodeUnits(truncated);
        assertEquals(List.of(0), units.refused(), "the concealed picture");
        assertEquals(1, units.pictures().size());
    }

    @Test
    @DisplayName("finishes a picture at an end of sub-bitstream code and skips the group of blocks headers after it")
    void endOfSubBitstreamInsidePicture() {
        // smalt-stuffing, whose first picture has groups of blocks headers: an end of sub-bitstream code (GN 30,
        // sub-bitstream 0) before the first of them ends the picture and the sub-bitstream; the headers that follow
        // belong to no picture, and the next P-picture has no reference
        var data = H263AnnexStreamsTest.resource("smalt-stuffing.263");
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        var header = segmentStarts(data, starts.get(0), starts.get(1)).getFirst();
        var edited = new Bits()
                .copy(data, 0, header)
                .code("0000 0000 0000 0000 1 11110 0 00")
                .copy(data, header, starts.get(1) * 8L);
        var padding = (8 - edited.length() % 8) % 8;
        edited.put(0, (int) padding);
        var units = decodeUnits(concat(edited.bytes(), java.util.Arrays.copyOfRange(data, starts.get(1),
                starts.get(2))));
        assertEquals(1, units.pictures().size(), "the first picture, concealed below the end code");
        assertTrue(units.refused().contains(0), "the concealed picture");
        assertTrue(units.refused().contains(1), "the P-picture without a reference");
    }

    @Test
    @DisplayName("refuses the vector code of +16 samples, which Table 14 lacks")
    void vectorCodePastTable() {
        // a P-picture without PLUSPTYPE (Unrestricted Motion Vector mode) after the I-picture of smalt-v1-umv, whose
        // first macroblock is INTER with no coefficient and the 13-bit MVD code of +32 half samples
        var data = H263AnnexStreamsTest.resource("smalt-v1-umv.263");
        var bits = new Bits()
                .code("0000 0000 0000 0000 1000 00").put(1, 8)
                .code("10 000 010 1 1 0 0 0").put(8, 5).code("0 0")
                .code("0 1 11").code("0000 0000 0010 0").code("1".repeat(48));
        assertMacroblockRefused(picture(data, 0), bits);
    }

    @Test
    @DisplayName("refuses a reversible vector code longer than the table")
    void reversibleCodePastTable() {
        // the 76-bit header of the first P-picture of smalt-b (PLUSPTYPE, Unrestricted Motion Vector mode), then an
        // INTER macroblock with no coefficient whose horizontal MVD continues past 12 information bits
        var data = H263AnnexStreamsTest.resource("smalt-b.263");
        var p = pictureOfType(data, 1);
        assertEquals(0, field(p, 75, 1), "PEI");
        var bits = new Bits().copy(p, 0, 76)
                .code("0 1 11").code("0" + "01".repeat(12)).code("1".repeat(48));
        assertMacroblockRefused(picture(data, 0), bits);
        // a horizontal component of 0 and a vertical one past the table
        bits = new Bits().copy(p, 0, 76)
                .code("0 1 11").code("1").code("0" + "01".repeat(12)).code("1".repeat(48));
        assertMacroblockRefused(picture(data, 0), bits);
    }

    @Test
    @DisplayName("refuses a PR0 code longer than Table U.1")
    void referenceIndexPastTable() {
        // the 94-bit header of the first P-picture of smalt-erps (RPSMF, PN, MRPA 1, the end of re-mapping, the
        // sliding window, PQUANT and PEI), then a macroblock whose PR0 continues past 12 information bits
        var data = H263AnnexStreamsTest.resource("smalt-erps.263");
        var p = picture(data, 1);
        assertEquals(1, field(p, PICTURE_TYPE, 3), "P-picture");
        assertEquals(0b100, field(p, UUI + 1, 3), "RPSMF");
        assertEquals(0b1001_1, field(p, 83, 5), "MRPA, RMPNI and the sliding window");
        assertEquals(0, field(p, 93, 1), "PEI");
        var bits = new Bits().copy(p, 0, 94).code("0").code("0" + "01".repeat(12)).code("1".repeat(48));
        assertMacroblockRefused(picture(data, 0), bits);
    }

    private static byte[] pictureOfType(byte[] data, int type) {
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        for (var k = 0; k < starts.size(); k++) {
            var at = starts.get(k) * 8L;
            if (field(data, at + UFEP, 3) == 1 && field(data, at + PICTURE_TYPE, 3) == type) {
                return picture(data, k);
            }
        }
        throw new AssertionError("no picture of type " + type);
    }

    /**
     * Decodes an intra picture followed by a picture whose first macroblock is corrupt: the second picture is
     * concealed from the first and refused as malformed.
     */
    private static void assertMacroblockRefused(byte[] intra, Bits corrupt) {
        var units = decodeUnits(concat(intra, corrupt.bytes()));
        assertEquals(List.of(1), units.refused().stream().filter(k -> k < 2).toList());
        assertEquals(2, units.pictures().size());
    }
}

package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.mpeg4.internal.BitReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses hand-written H.263 picture headers field by field: the PLUSPTYPE with every optional field (custom format
 * and clock, UUI, slice submodes, enhancement layers, reference picture selection with back-channel messages,
 * reference picture resampling with each fill mode), the slice field widths of Annex K, and every corrupt or
 * contradictory field, which must be refused as malformed.
 */
@DisplayName("H.263 picture header")
class PictureHeaderTest {
    /**
     * Writes bits most significant first.
     */
    static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int buffer;
        private int count;

        Bits put(long value, int width) {
            for (var bit = width - 1; bit >= 0; bit--) {
                buffer = buffer << 1 | (int) (value >>> bit & 1);
                if (++count == 8) {
                    out.write(buffer);
                    buffer = 0;
                    count = 0;
                }
            }
            return this;
        }

        /**
         * Writes a signed value of the reversible code of Table D.3.
         */
        Bits reversible(int value) {
            if (value == 0) {
                return put(1, 1);
            }
            var magnitude = Math.abs(value);
            var bits = 31 - Integer.numberOfLeadingZeros(magnitude);
            put(0, 1);
            for (var index = bits - 1; index >= 0; index--) {
                put(magnitude >>> index & 1, 1).put(1, 1);
            }
            return put(value < 0 ? 1 : 0, 1).put(0, 1);
        }

        byte[] bytes() {
            var copy = new ByteArrayOutputStream();
            copy.writeBytes(out.toByteArray());
            if (count > 0) {
                copy.write(buffer << (8 - count));
            }
            copy.writeBytes(new byte[16]);
            return copy.toByteArray();
        }
    }

    static Bits start() {
        return new Bits().put(0x20, 22).put(5, 8).put(1, 1).put(0, 1).put(0, 3);
    }

    /**
     * Writes a PLUSPTYPE header up to the CPM bit included.
     *
     * @param opptype the ten flags of the OPPTYPE after the clock flag, UMV first
     */
    static Bits plus(int ufep, int format, boolean clock, int opptype, boolean erps, boolean dps, int type, int rpr) {
        var bits = start().put(7, 3).put(ufep, 3);
        if (ufep == 1) {
            bits.put(format, 3).put(clock ? 1 : 0, 1).put(opptype, 10).put(1, 1).put(erps ? 1 : 0, 1)
                    .put(dps ? 1 : 0, 1).put(0, 1);
        }
        return bits.put(type, 3).put(rpr, 1).put(0, 1).put(0, 1).put(0, 2).put(1, 1).put(0, 1);
    }

    static final int UMV = 1 << 9;
    static final int SAC = 1 << 8;
    static final int SS = 1 << 4;
    static final int RPS = 1 << 3;

    static SubBitstream[] streams() {
        var pool = new PicturePool();
        var streams = new SubBitstream[4];
        for (var index = 0; index < 4; index++) {
            streams[index] = new SubBitstream(index, pool);
        }
        return streams;
    }

    static PictureHeader parse(byte[] data, SubBitstream[] streams) {
        var reader = new BitReader();
        reader.load(data, 0, data.length);
        var header = new PictureHeader();
        header.parse(reader, streams);
        return header;
    }

    static PictureHeader parse(Bits bits) {
        return parse(bits.bytes(), streams());
    }

    @ParameterizedTest(name = "{0}x{1}, reduced {2}")
    @CsvSource({"128,96,false,6,4", "176,144,false,7,4", "352,288,false,9,5", "704,576,false,11,6",
            "1408,1152,false,13,7", "2048,1152,false,14,7", "176,144,true,6,3", "128,96,true,5,3",
            "352,288,true,7,4", "704,576,true,9,5", "1408,1152,true,11,6", "2048,1152,true,12,6", "96,64,true,5,3",
            "320,160,true,7,4"})
    @DisplayName("sizes the slice address and width fields of Tables K.2 and K.3")
    void sliceFields(int width, int height, boolean reduced, int address, int widthBits) {
        var header = new PictureHeader();
        header.width = width;
        header.height = height;
        header.reducedResolution = reduced;
        assertEquals(address, header.sliceAddressBits());
        assertEquals(widthBits, header.sliceWidthBits());
    }

    @ParameterizedTest(name = "aspect {0}")
    @CsvSource({"1,1,1", "2,12,11", "3,10,11", "4,16,11", "5,40,33", "15,3,2", "0,0,0", "8,0,0"})
    @DisplayName("reads the custom picture format and its pixel aspect ratio")
    void customFormat(int aspect, int parWidth, int parHeight) {
        var bits = plus(1, 6, false, 0, false, false, 0, 0).put(aspect, 4).put(43, 9).put(1, 1).put(36, 9);
        if (aspect == 15) {
            bits.put(3, 8).put(2, 8);
        }
        var header = parse(bits.put(8, 5).put(0, 1));
        assertEquals(176, header.width);
        assertEquals(144, header.height);
        assertEquals(parWidth, header.parWidth);
        assertEquals(parHeight, header.parHeight);
    }

    @Test
    @DisplayName("reads the custom clock, the extended reference, UUI and the slice submodes")
    void optionalFields() {
        var bits = plus(1, 2, true, UMV | SS, false, false, 1, 0).put(1, 1).put(3, 7).put(2, 2).put(0, 1).put(1, 1)
                .put(1, 1).put(1, 1).put(8, 5).put(0, 1);
        var header = parse(bits);
        assertEquals(3003, header.periodTicks);
        assertEquals(5 | 2 << 8, header.temporalReference);
        assertTrue(header.unlimitedVectors);
        assertTrue(header.rectangularSlices);
        assertTrue(header.arbitrarySliceOrder);
        var limited = parse(plus(1, 2, false, UMV, false, false, 1, 0).put(1, 1).put(8, 5).put(0, 1));
        assertFalse(limited.unlimitedVectors);
    }

    @Test
    @DisplayName("gives a header without OPPTYPE the modes of its sub-bitstream's last full header")
    void inherited() {
        var streams = streams();
        parse(plus(1, 3, false, SS, false, false, 0, 0).put(0, 1).put(1, 1).put(8, 5).put(0, 1).bytes(), streams);
        var next = parse(plus(0, 0, false, 0, false, false, 1, 0).put(8, 5).put(0, 1).bytes(), streams);
        assertEquals(352, next.width);
        assertTrue(next.arbitrarySliceOrder);
        assertThrows(MediaException.Malformed.class, () -> parse(plus(0, 0, false, 0, false, false, 1, 0)
                .put(8, 5).put(0, 1)));
    }

    @Test
    @DisplayName("reads enhancement layers with their reference layer and the improved PB-frame fields")
    void layers() {
        var streams = streams();
        parse(plus(1, 2, false, 0, false, false, 0, 0).put(8, 5).put(0, 1).bytes(), streams);
        var spatial = parse(plus(1, 3, false, 0, false, false, PictureHeader.EI, 0).put(2, 4).put(1, 4).put(8, 5)
                .put(0, 1).bytes(), streams);
        assertEquals(2, spatial.layer);
        assertEquals(1, spatial.referenceLayer);
        var next = parse(plus(0, 0, false, 0, false, false, PictureHeader.EP, 0).put(2, 4).put(8, 5).put(0, 1).bytes(),
                streams);
        assertEquals(1, next.referenceLayer, "the reference layer of the layer's last picture");
        var bidirectional = parse(plus(0, 0, false, 0, false, false, PictureHeader.B, 0).put(2, 4).put(8, 5).put(0, 1)
                .bytes(), streams);
        assertEquals(2, bidirectional.referenceLayer, "a B picture after an EP picture predicts from its own layer");
        var improved = parse(plus(1, 2, false, 0, false, false, PictureHeader.IMPROVED_PB, 0).put(8, 5).put(5, 3)
                .put(2, 2).put(0, 1));
        assertEquals(5, improved.trb);
        assertEquals(2, improved.dbquant);
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 2, false, 0, false, false, PictureHeader.EI, 0)
                .put(0, 4).put(0, 4).put(8, 5).put(0, 1)), "layer 0");
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 2, false, 0, false, false, PictureHeader.EI, 0)
                .put(2, 4).put(3, 4).put(8, 5).put(0, 1)), "a reference layer above the layer");
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 2, false, 0, false, false, PictureHeader.EP, 0)
                .put(2, 4).put(2, 4).put(8, 5).put(0, 1)), "an EP picture predicting from its own layer");
    }

    @Test
    @DisplayName("reads the RPS fields and back-channel messages, with and without slices")
    void referencePictureSelection() {
        var header = parse(plus(1, 2, false, RPS, false, false, 1, 0).put(5, 3).put(1, 1).put(700, 10)
                .put(1, 1).put(2, 2).put(0, 1).put(9, 10).put(1, 1).put(3, 4).put(1, 1).put(1, 2).put(1, 1).put(4, 5)
                .put(1, 1).put(77, 10)
                .put(1, 1).put(0, 2).put(0, 1).put(9, 10).put(0, 1).put(0, 1).put(1, 1).put(4, 5).put(1, 1)
                .put(0, 1).put(1, 1).put(8, 5).put(0, 1));
        assertEquals(700, header.trp);
        var sliced = parse(plus(1, 2, false, RPS | SS, false, false, 1, 0).put(0, 1).put(0, 1).put(5, 3).put(0, 1)
                .put(1, 1).put(1, 2).put(0, 1).put(9, 10).put(0, 1).put(0, 1).put(1, 1).put(4, 7).put(1, 1)
                .put(0, 1).put(1, 1).put(8, 5).put(0, 1));
        assertEquals(-1, sliced.trp);
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 2, false, RPS, false, false, 1, 0)
                .put(5, 3).put(0, 1).put(0, 1).put(0, 1).put(8, 5).put(0, 1)), "an invalid BCI");
        var runaway = plus(1, 2, false, RPS, false, false, 1, 0).put(5, 3).put(0, 1);
        for (var index = 0; index < 5000; index++) {
            runaway.put(1, 1).put(0, 2).put(0, 11).put(0, 1).put(0, 1).put(1, 1).put(0, 5).put(1, 1);
        }
        assertThrows(MediaException.Malformed.class, () -> parse(runaway));
        // a message of type 10 whose last field, after both markers, runs past the end of the data
        var message = plus(1, 2, false, RPS, false, false, 1, 0).put(5, 3).put(0, 1).put(1, 1).put(2, 2).put(0, 11)
                .put(0, 1).put(0, 1).put(1, 1).put(0, 5).put(1, 1).bytes();
        var cut = java.util.Arrays.copyOf(message, message.length - 16);
        var reader = new BitReader();
        reader.load(cut, 0, cut.length);
        var exception = assertThrows(MediaException.Malformed.class, () -> new PictureHeader().parse(reader,
                streams()), "a back-channel message cut by the end of the data");
        assertTrue(exception.getMessage().contains("back-channel"), exception.getMessage());
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 2, false, RPS, true, false, 1, 0)
                .put(8, 5).put(0, 1)), "RPS and ERPS together");
    }

    @ParameterizedTest(name = "fill mode {0}")
    @CsvSource({"0,30,40,50", "1,16,128,128", "2,128,128,128", "3,128,128,128"})
    @DisplayName("reads the resampling warping parameters and each fill mode")
    void resampling(int fill, int y, int cb, int cr) {
        var bits = plus(1, 2, false, 0, false, false, 1, 1).put(2, 2);
        for (var value : new int[]{0, 3, -2, 1, 5, 0, -7, 4}) {
            bits.reversible(value);
        }
        bits.put(fill, 2);
        if (fill == 0) {
            bits.put(30, 8).put(1, 1).put(40, 8).put(1, 1).put(50, 8);
        }
        var header = parse(bits.put(8, 5).put(0, 1));
        assertTrue(header.warpingSent);
        assertEquals(2, header.warpingAccuracy);
        assertEquals(3, header.warping[1]);
        assertEquals(-2, header.warping[2]);
        assertEquals(-7, header.warping[6]);
        assertEquals(y, header.fillColor[0]);
        assertEquals(cb, header.fillColor[1]);
        assertEquals(cr, header.fillColor[2]);
    }

    @Test
    @DisplayName("refuses resampling with a reserved accuracy, in an INTRA picture, or with an overlong code")
    void resamplingMalformed() {
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 2, false, 0, false, false, 1, 1).put(1, 2)
                .put(0, 40)));
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 2, false, 0, false, false, 0, 1).put(3, 2)
                .put(0, 40)));
        var overlong = plus(1, 2, false, 0, false, false, 1, 1).put(3, 2).put(0, 1);
        for (var index = 0; index < 13; index++) {
            overlong.put(1, 1).put(1, 1);
        }
        assertThrows(MediaException.Malformed.class, () -> parse(overlong.put(0, 30)));
        var badY = plus(1, 2, false, 0, false, false, 1, 1).put(3, 2).reversible(0).put(0, 1);
        for (var index = 0; index < 13; index++) {
            badY.put(1, 1).put(1, 1);
        }
        assertThrows(MediaException.Malformed.class, () -> parse(badY.put(0, 30)), "an overlong vertical parameter");
        var corner = plus(1, 2, false, 0, false, false, 1, 1).put(3, 2).reversible(1).reversible(1).put(0, 1);
        assertThrows(MediaException.Malformed.class, () -> parse(corner.put(0, 60)), "the marker after (1, 1)");
    }

    @Test
    @DisplayName("reads the resampling refinement of EP pictures and the parameters of B and improved PB pictures")
    void resamplingKinds() {
        var streams = streams();
        parse(plus(1, 2, false, 0, false, false, 0, 0).put(8, 5).put(0, 1).bytes(), streams);
        var ep = parse(plus(1, 2, false, 0, false, false, PictureHeader.EP, 1).put(2, 4).put(1, 4).put(3, 2).put(8, 5)
                .put(0, 1).bytes(), streams);
        assertFalse(ep.warpingRefinement);
        assertEquals(-1, ep.warping[0]);
        assertEquals(16, ep.warpingAccuracy);
        for (var type : new int[]{PictureHeader.B, PictureHeader.IMPROVED_PB}) {
            var bits = plus(1, 2, true, 0, false, false, type, 1).put(0, 1).put(1, 7).put(0, 2);
            if (type == PictureHeader.B) {
                bits.put(1, 4).put(1, 4);
            }
            bits.put(2, 2);
            for (var index = 0; index < 8; index++) {
                bits.reversible(index - 4);
            }
            bits.put(1, 2).put(8, 5);
            if (type == PictureHeader.IMPROVED_PB) {
                bits.put(17, 5).put(1, 2);
            }
            var header = parse(bits.put(0, 1));
            assertTrue(header.warpingSent);
            assertEquals(16, header.fillColor[0]);
            if (type == PictureHeader.IMPROVED_PB) {
                assertEquals(17, header.trb, "a five-bit TRB with the custom clock");
            }
        }
    }

    @Test
    @DisplayName("refuses a header that ends in its PSUPP")
    void truncatedPsupp() {
        var data = plus(1, 2, false, 0, false, false, 0, 0).put(8, 5).put(1, 1).put(0x55, 8).put(1, 1).bytes();
        var cut = java.util.Arrays.copyOf(data, data.length - 16);
        var reader = new BitReader();
        reader.load(cut, 0, cut.length);
        assertThrows(MediaException.Malformed.class, () -> new PictureHeader().parse(reader, streams()));
        var baseline = start().put(2, 3).put(1, 1).put(0, 4).put(8, 5).put(1, 1).put(3, 2).put(0, 1);
        assertEquals(3, parse(baseline).subBitstream);
    }

    @Test
    @DisplayName("refuses corrupt and contradictory fields")
    void malformed() {
        assertThrows(MediaException.Malformed.class, () -> parse(new Bits().put(0x21, 22).put(0, 40)));
        assertThrows(MediaException.Malformed.class, () -> parse(new Bits().put(0x20, 22).put(0, 8).put(0, 30)));
        assertThrows(MediaException.Malformed.class, () -> parse(start().put(0, 3).put(0, 30)), "format 0");
        assertThrows(MediaException.Malformed.class, () -> parse(start().put(6, 3).put(0, 30)), "format 6");
        assertThrows(MediaException.Malformed.class, () -> parse(start().put(2, 3).put(0, 1).put(0, 3).put(1, 1)
                .put(8, 5).put(0, 30)), "a PB-frame of an INTRA picture");
        assertThrows(MediaException.Malformed.class, () -> parse(start().put(7, 3).put(2, 3).put(0, 30)), "UFEP 2");
        assertThrows(MediaException.Malformed.class, () -> parse(start().put(7, 3).put(1, 3).put(2, 3).put(0, 11)
                .put(0, 1).put(0, 30)), "OPPTYPE marker");
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 0, false, 0, false, false, 0, 0)
                .put(0, 30)), "format 0 in OPPTYPE");
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 7, false, 0, false, false, 0, 0)
                .put(0, 30)), "format 7 in OPPTYPE");
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 2, false, 0, false, false, 6, 0)
                .put(0, 30)), "picture type 6");
        assertThrows(MediaException.Malformed.class, () -> parse(start().put(7, 3).put(1, 3).put(2, 3).put(0, 11)
                .put(1, 1).put(0, 3).put(0, 3).put(0, 5).put(0, 1).put(0, 30)), "MPPTYPE marker");
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 6, false, 0, false, false, 0, 0).put(1, 4)
                .put(43, 9).put(0, 1).put(0, 30)), "CPFMT marker");
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 2, true, 0, false, false, 0, 0).put(0, 1)
                .put(0, 7).put(0, 30)), "clock divisor 0");
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 2, false, UMV, false, false, 1, 0).put(0, 1)
                .put(0, 1).put(8, 5).put(0, 30)), "UUI");
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 2, false, 0, false, true, 0, 0).put(8, 5)
                .put(0, 1)), "DPS without slices");
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 2, false, SS | SAC, false, true, 1, 0)
                .put(0, 2).put(8, 5).put(0, 1)), "DPS with arithmetic coding");
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 2, false, 0, false, false, 0, 0).put(0, 5)
                .put(0, 1)), "PQUANT 0");
        assertThrows(MediaException.Malformed.class, () -> parse(plus(1, 6, false, 0, false, false, 0, 0).put(1, 4)
                .put(43, 9).put(1, 1).put(0, 9).put(8, 5).put(0, 1)), "height 0");
        var psupp = plus(1, 2, false, 0, false, false, 0, 0).put(8, 5);
        for (var index = 0; index < 70_000; index++) {
            psupp.put(1, 1).put(0, 8);
        }
        assertThrows(MediaException.Malformed.class, () -> parse(psupp.put(0, 1)));
    }

    @Test
    @DisplayName("finds the sub-bitstream number of baseline and PLUSPTYPE headers")
    void subBitstreams() {
        var baseline = start().put(2, 3).put(1, 1).put(0, 4).put(8, 5).put(1, 1).put(2, 2).put(0, 1).bytes();
        var reader = new BitReader();
        reader.load(baseline, 0, baseline.length);
        assertEquals(2, PictureHeader.peekSubBitstream(reader));
        var withCpm = start().put(7, 3).put(1, 3).put(2, 3).put(0, 1).put(0, 10).put(1, 1).put(0, 3).put(1, 3).put(0, 5)
                .put(1, 1).put(1, 1).put(3, 2).put(8, 5).put(0, 1).bytes();
        reader.load(withCpm, 0, withCpm.length);
        assertEquals(3, PictureHeader.peekSubBitstream(reader));
        var inherited = start().put(7, 3).put(0, 3).put(1, 3).put(0, 5).put(1, 1).put(1, 1).put(1, 2).put(0, 10).bytes();
        reader.load(inherited, 0, inherited.length);
        assertEquals(1, PictureHeader.peekSubBitstream(reader));
    }

    @Test
    @DisplayName("refuses a PTYPE whose second bit is set and a header cut before its quantizer")
    void truncatedOrInvalidType() {
        assertThrows(MediaException.Malformed.class, () -> parse(new Bits().put(0x20, 22).put(0, 8).put(1, 1)
                .put(1, 1).put(0, 30)));
        var data = plus(1, 2, false, 0, false, false, 0, 0).bytes();
        var cut = java.util.Arrays.copyOf(data, data.length - 16);
        var reader = new BitReader();
        reader.load(cut, 0, cut.length);
        var exception = assertThrows(MediaException.Malformed.class, () -> new PictureHeader().parse(reader,
                streams()));
        assertTrue(exception.getMessage().contains("truncated"), exception.getMessage());
    }

    @Test
    @DisplayName("gives a new enhancement layer without OPPTYPE the modes of the base layer, which must be known")
    void enhancementInheritsBase() {
        var streams = streams();
        assertThrows(MediaException.Malformed.class, () -> parse(plus(0, 0, false, 0, false, false,
                PictureHeader.EP, 0).put(3, 4).put(8, 5).put(0, 1).bytes(), streams));
        parse(plus(1, 3, false, 0, false, false, 0, 0).put(8, 5).put(0, 1).bytes(), streams);
        var ep = parse(plus(0, 0, false, 0, false, false, PictureHeader.EP, 0).put(3, 4).put(8, 5).put(0, 1).bytes(),
                streams);
        assertEquals(3, ep.layer);
        assertEquals(1, ep.referenceLayer);
        assertEquals(352, ep.width);
        assertEquals(288, ep.height);
    }

    @Test
    @DisplayName("lets a B-picture after a B-picture of an enhancement layer keep its reference layer")
    void consecutiveEnhancementB() {
        var streams = streams();
        parse(plus(1, 2, false, 0, false, false, 0, 0).put(8, 5).put(0, 1).bytes(), streams);
        parse(plus(1, 3, false, 0, false, false, PictureHeader.EI, 0).put(2, 4).put(1, 4).put(8, 5).put(0, 1).bytes(),
                streams);
        var first = parse(plus(0, 0, false, 0, false, false, PictureHeader.B, 0).put(2, 4).put(8, 5).put(0, 1)
                .bytes(), streams);
        assertEquals(2, first.referenceLayer);
        var second = parse(plus(0, 0, false, 0, false, false, PictureHeader.B, 0).put(2, 4).put(8, 5).put(0, 1)
                .bytes(), streams);
        assertEquals(2, second.referenceLayer);
    }

    @Test
    @DisplayName("sizes a B-picture without OPPTYPE as its subsequent anchor picture")
    void bPictureTakesAnchorSize() {
        var streams = streams();
        parse(plus(1, 2, false, 0, false, false, 0, 0).put(8, 5).put(0, 1).bytes(), streams);
        var anchor = new PlusPicture(352, 288);
        streams[0].addAnchor(anchor, false);
        var b = parse(plus(0, 0, false, 0, false, false, PictureHeader.B, 0).put(1, 4).put(8, 5).put(0, 1).bytes(),
                streams);
        assertEquals(352, b.width);
        assertEquals(288, b.height);
    }

    @ParameterizedTest(name = "reference {0}x{1}")
    @CsvSource({"88,72,true,true", "88,144,true,false", "176,72,false,true"})
    @DisplayName("reads the upsampling refinement bits of an EP picture larger than its reference layer picture")
    void resamplingRefinement(int width, int height, boolean horizontal, boolean vertical) {
        var streams = streams();
        parse(plus(1, 2, false, 0, false, false, 0, 0).put(8, 5).put(0, 1).bytes(), streams);
        streams[0].addAnchor(new PlusPicture(width, height), false);
        var bits = plus(1, 2, false, 0, false, false, PictureHeader.EP, 1).put(2, 4).put(1, 4).put(3, 2);
        var expected = new int[8];
        for (var i = 0; i < 8; i++) {
            var upsampled = (i & 1) == 0 ? horizontal : vertical;
            expected[i] = upsampled ? (i >> 1) & 1 : -1;
            if (upsampled) {
                bits.put(expected[i], 1);
            }
        }
        var ep = parse(bits.put(8, 5).put(0, 1).bytes(), streams);
        assertTrue(ep.warpingRefinement);
        assertEquals(8, ep.quant);
        for (var i = 0; i < 8; i++) {
            assertEquals(expected[i], ep.warping[i], "parameter " + i);
        }
    }

    private static boolean required(Bits bits, int width, int height) {
        var data = bits.bytes();
        var reader = new BitReader();
        reader.load(data, 0, data.length);
        return H263PlusStream.required(reader, width, height);
    }

    @Test
    @DisplayName("tells the headers only the annex decoder can decode")
    void requiresAnnexDecoder() {
        assertFalse(required(new Bits().put(0x21, 22).put(0, 40), 0, 0), "no picture start code");
        assertFalse(required(start().put(2, 3).put(1, 1).put(0b1010, 4).put(8, 5).put(0, 1), 0, 0), "UMV and AP");
        assertTrue(required(start().put(2, 3).put(1, 1).put(0b0100, 4).put(8, 5).put(0, 1), 0, 0), "SAC");
        assertTrue(required(start().put(2, 3).put(1, 1).put(0b0001, 4).put(8, 5).put(0, 1), 0, 0), "PB-frames");
        assertTrue(required(start().put(2, 3).put(1, 1).put(0, 4).put(8, 5).put(1, 1), 0, 0), "CPM");
        for (var mode : new int[]{SAC, RPS, 1 << 2}) {
            assertTrue(required(plus(1, 2, false, mode, false, false, 1, 0), 0, 0), "OPPTYPE mode " + mode);
        }
        assertTrue(required(plus(1, 2, false, 0, true, false, 1, 0), 0, 0), "ERPS");
        assertTrue(required(plus(1, 2, false, 0, false, true, 1, 0), 0, 0), "DPS");
        assertTrue(required(plus(1, 2, false, 0, false, false, PictureHeader.B, 0), 0, 0), "B picture");
        assertTrue(required(plus(1, 2, false, 0, false, false, 1, 1), 0, 0), "RPR");
        assertFalse(required(plus(0, 0, false, 0, false, false, 1, 0), 176, 144), "no OPPTYPE");
        assertTrue(required(plus(1, 2, false, 0, false, false, 1, 0), 352, 288), "a P picture of another size");
        assertFalse(required(plus(1, 2, false, 0, false, false, 1, 0), 176, 144), "the same size");
        assertFalse(required(plus(1, 2, false, 0, false, false, 1, 0), 0, 0), "a P picture before any size is known");
        assertFalse(required(plus(1, 2, false, 0, false, false, 0, 0), 352, 288), "an INTRA picture of another size");
        assertTrue(required(plus(1, 6, false, 0, false, false, 1, 0).put(15, 4).put(43, 9).put(1, 1).put(36, 9)
                .put(1, 8).put(1, 8), 352, 288), "a custom size");
        assertTrue(required(plus(1, 2, true, UMV | SS, false, false, 1, 0).put(0, 10).put(0, 1).put(1, 1).put(1, 1),
                176, 144), "rectangular slices after the clock and UUI");
        assertFalse(required(plus(1, 2, false, UMV | SS, false, false, 1, 0).put(1, 1).put(0, 1), 176, 144),
                "slices without the rectangular submode");
    }
}

package com.github.auties00.smalt.video.mpeg4.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Parses hand-written H.263 and Sorenson H.263 picture headers: every source format, the PLUSPTYPE fields with the
 * custom picture format, aspect ratios and clock, and every mode the short-header decoder leaves to the H.263
 * decoder, which it must refuse as unsupported, as it must refuse corrupt fields as malformed.
 */
@DisplayName("H.263 picture headers of the MPEG-4 short-header decoder")
class H263HeaderTest {
    /**
     * Writes bits most significant first.
     */
    static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long buffer;
        private int count;

        Bits put(long value, int width) {
            for (var bit = width - 1; bit >= 0; bit--) {
                buffer = buffer << 1 | (value >>> bit & 1);
                if (++count == 8) {
                    out.write((int) buffer);
                    buffer = 0;
                    count = 0;
                }
            }
            return this;
        }

        byte[] bytes() {
            var copy = new Bits();
            copy.out.writeBytes(out.toByteArray());
            copy.buffer = buffer;
            copy.count = count;
            while (copy.count != 0) {
                copy.put(0, 1);
            }
            copy.out.writeBytes(new byte[8]);
            return copy.out.toByteArray();
        }
    }

    /**
     * Starts a picture: start code, temporal reference and the first PTYPE bits.
     */
    static Bits start(int reference) {
        return new Bits().put(0x20, 22).put(reference, 8).put(1, 1).put(0, 1).put(0, 3);
    }

    /**
     * Writes a baseline header of a source format.
     */
    static byte[] baseline(int format, int inter, int annexes, int quant, int cpm) {
        return start(0).put(format, 3).put(inter, 1).put(annexes, 4).put(quant, 5).put(cpm, 1).put(0, 1).bytes();
    }

    /**
     * Writes a PLUSPTYPE header with an OPPTYPE.
     */
    static Bits plus(int reference, int extendedFormat, boolean customClock, int modes, int type, int rpr, int rru) {
        return start(reference).put(7, 3).put(1, 3).put(extendedFormat, 3).put(customClock ? 1 : 0, 1).put(modes, 10)
                .put(1, 1).put(0, 3).put(type, 3).put(rpr, 1).put(rru, 1).put(0, 1).put(0, 2).put(1, 1).put(0, 1);
    }

    static VideoObjectLayer parse(H263Header header, byte[] data, VideoObjectLayer previous) {
        var reader = new BitReader();
        reader.load(data, 0, data.length);
        return header.parse(reader, true, previous, MediaLimits.defaults());
    }

    static VideoObjectLayer parse(byte[] data) {
        return parse(new H263Header(), data, null);
    }

    @ParameterizedTest(name = "format {0}")
    @CsvSource({"1,128,96", "2,176,144", "3,352,288", "4,704,576", "5,1408,1152"})
    @DisplayName("reads the baseline source formats")
    void baselineFormats(int format, int width, int height) {
        var layer = parse(baseline(format, 0, 0, 8, 0));
        assertEquals(width, layer.width);
        assertEquals(height, layer.height);
        assertEquals(12, layer.parWidth);
    }

    @ParameterizedTest(name = "annex bits {0}")
    @CsvSource({"4", "1"})
    @DisplayName("leaves arithmetic coding and PB-frames to the H.263 decoder")
    void baselineUnsupported(int annexes) {
        assertThrows(MediaException.Unsupported.class, () -> parse(baseline(2, 1, annexes, 8, 0)));
        assertThrows(MediaException.Unsupported.class, () -> parse(baseline(2, 0, 0, 8, 1)), "CPM");
    }

    @Test
    @DisplayName("refuses reserved formats, a zero quantizer, a corrupt PTYPE and runaway PSUPP")
    void baselineMalformed() {
        assertThrows(MediaException.Malformed.class, () -> parse(baseline(0, 0, 0, 8, 0)));
        assertThrows(MediaException.Malformed.class, () -> parse(baseline(6, 0, 0, 8, 0)));
        assertThrows(MediaException.Malformed.class, () -> parse(baseline(2, 0, 0, 0, 0)));
        assertThrows(MediaException.Malformed.class, () -> parse(new Bits().put(0x20, 22).put(0, 8).put(0, 1)
                .put(0, 20).bytes()));
        assertThrows(MediaException.Malformed.class, () -> parse(new Bits().put(0x20, 22).put(0, 8).put(1, 1)
                .put(1, 1).put(0, 20).bytes()));
        var psupp = start(0).put(2, 3).put(0, 1).put(0, 4).put(8, 5).put(0, 1);
        for (var index = 0; index < 5000; index++) {
            psupp.put(1, 1).put(0xAB, 8);
        }
        assertThrows(MediaException.Malformed.class, () -> parse(psupp.bytes()));
        var truncated = start(0).put(2, 3).put(0, 1).put(0, 4).put(8, 5).put(0, 1).put(1, 1).put(1, 4);
        var cut = truncated.bytes();
        assertThrows(MediaException.Malformed.class, () -> parse(java.util.Arrays.copyOf(cut, 6)));
        assertNull(parse(new byte[8]), "no picture start code");
    }

    @Test
    @DisplayName("keeps the layer of an unchanged size and counts clock periods across references")
    void sameLayer() {
        var header = new H263Header();
        var first = parse(header, start(10).put(2, 3).put(0, 1).put(0, 4).put(8, 5).put(0, 2).bytes(), null);
        var second = parse(header, start(10).put(2, 3).put(1, 1).put(0, 4).put(8, 5).put(0, 2).bytes(), first);
        assertSame(first, second);
        var third = parse(header, start(12).put(3, 3).put(1, 1).put(0, 4).put(8, 5).put(0, 2).bytes(), second);
        assertEquals(352, third.width);
    }

    @ParameterizedTest(name = "aspect {0}")
    @CsvSource({"1,1,1", "2,12,11", "3,10,11", "4,16,11", "5,40,33", "15,7,9", "0,0,0", "9,0,0"})
    @DisplayName("reads the custom picture format with each pixel aspect ratio")
    void customFormat(int aspect, int parWidth, int parHeight) {
        var bits = plus(0, 6, false, 0, 0, 0, 0).put(aspect, 4).put(79, 9).put(1, 1).put(30, 9);
        if (aspect == 15) {
            bits.put(7, 8).put(9, 8);
        }
        var layer = parse(bits.put(8, 5).put(0, 1).bytes());
        assertEquals(320, layer.width);
        assertEquals(120, layer.height);
        assertEquals(parWidth, layer.parWidth);
        assertEquals(parHeight, layer.parHeight);
    }

    @Test
    @DisplayName("reads the custom clock, extended references, unlimited vectors and slices")
    void optionalFields() {
        var clock = plus(1, 2, true, 0x200 | 0x10, 1, 0, 0).put(1, 1).put(2, 7).put(3, 2).put(0, 1).put(0, 1).put(0, 1)
                .put(1, 1).put(8, 5).put(0, 1);
        var layer = parse(clock.bytes());
        assertEquals(176, layer.width);
        assertEquals(2002, layer.fixedTimeIncrement);
        var limited = plus(1, 3, false, 0x200, 0, 0, 0).put(1, 1).put(8, 5).put(0, 1);
        assertEquals(352, parse(limited.bytes()).width);
        var zeroDivisor = plus(1, 2, true, 0, 0, 0, 0).put(0, 1).put(0, 7).put(8, 5).put(0, 1);
        assertThrows(MediaException.Malformed.class, () -> parse(zeroDivisor.bytes()));
        var rectangular = plus(1, 2, false, 0x10, 0, 0, 0).put(1, 1).put(8, 5).put(0, 1);
        assertThrows(MediaException.Unsupported.class, () -> parse(rectangular.bytes()));
    }

    @ParameterizedTest(name = "modes {0}, type {1}, RPR {2}, RRU {3}")
    @CsvSource({"256,0,0,0", "8,0,0,0", "4,0,0,0", "0,2,0,0", "0,0,1,0", "0,0,0,1"})
    @DisplayName("leaves the modes it lacks to the H.263 decoder")
    void plusUnsupported(int modes, int type, int rpr, int rru) {
        var bits = plus(0, 2, false, modes, type, rpr, rru).put(8, 5).put(0, 1);
        assertThrows(MediaException.Unsupported.class, () -> parse(bits.bytes()));
    }

    @Test
    @DisplayName("refuses corrupt PLUSPTYPE fields")
    void plusMalformed() {
        var reservedUfep = start(0).put(7, 3).put(5, 3).put(0, 30);
        assertThrows(MediaException.Malformed.class, () -> parse(reservedUfep.bytes()));
        var noPrevious = start(0).put(7, 3).put(0, 3).put(0, 3).put(0, 1).put(0, 1).put(0, 3).put(1, 1).put(0, 6)
                .put(8, 5).put(0, 1);
        assertThrows(MediaException.Malformed.class, () -> parse(noPrevious.bytes()));
        var badOpptype = start(0).put(7, 3).put(1, 3).put(2, 3).put(0, 1).put(0, 10).put(0, 1).put(0, 20);
        assertThrows(MediaException.Malformed.class, () -> parse(badOpptype.bytes()));
        var badMpptype = start(0).put(7, 3).put(1, 3).put(2, 3).put(0, 1).put(0, 10).put(1, 1).put(0, 3).put(0, 3)
                .put(0, 1).put(0, 1).put(0, 1).put(0, 2).put(0, 1).put(0, 10);
        assertThrows(MediaException.Malformed.class, () -> parse(badMpptype.bytes()));
        var badCpfmt = plus(0, 6, false, 0, 0, 0, 0).put(1, 4).put(79, 9).put(0, 1).put(30, 9).put(8, 5).put(0, 1);
        assertThrows(MediaException.Malformed.class, () -> parse(badCpfmt.bytes()));
        var reservedFormat = plus(0, 7, false, 0, 0, 0, 0).put(8, 5).put(0, 1);
        assertThrows(MediaException.Malformed.class, () -> parse(reservedFormat.bytes()));
        var zeroSize = plus(0, 6, false, 0, 0, 0, 0).put(1, 4).put(79, 9).put(1, 1).put(0, 9).put(8, 5).put(0, 1);
        assertThrows(MediaException.Malformed.class, () -> parse(zeroSize.bytes()));
    }

    @Test
    @DisplayName("reuses the format of the previous PLUSPTYPE when UFEP is zero")
    void ufepZero() {
        var header = new H263Header();
        var first = parse(header, plus(0, 3, false, 0, 0, 0, 0).put(8, 5).put(0, 1).bytes(), null);
        var next = start(1).put(7, 3).put(0, 3).put(1, 3).put(0, 1).put(0, 1).put(0, 1).put(0, 2).put(1, 1).put(0, 1)
                .put(8, 5).put(0, 1);
        assertSame(first, parse(header, next.bytes(), first));
    }

    /**
     * Writes a Sorenson header.
     */
    static Bits sorenson(int version, int size) {
        return new Bits().put(1, 17).put(version, 5).put(0, 8).put(size, 3);
    }

    static VideoObjectLayer parseSorenson(byte[] data) {
        var reader = new BitReader();
        reader.load(data, 0, data.length);
        return new H263Header().parseSorenson(reader, null, MediaLimits.defaults());
    }

    @ParameterizedTest(name = "size code {0}")
    @CsvSource({"0,40,30", "1,1000,500", "2,352,288", "3,176,144", "4,128,96", "5,320,240", "6,160,120"})
    @DisplayName("reads every Sorenson picture size")
    void sorensonSizes(int code, int width, int height) {
        var bits = sorenson(1, code);
        if (code == 0) {
            bits.put(width, 8).put(height, 8);
        } else if (code == 1) {
            bits.put(width, 16).put(height, 16);
        }
        var layer = parseSorenson(bits.put(2, 2).put(0, 1).put(8, 5).put(1, 1).put(0x55, 8).put(0, 1).bytes());
        assertEquals(width, layer.width);
        assertEquals(height, layer.height);
        assertEquals(1, layer.parWidth);
    }

    @Test
    @DisplayName("refuses corrupt Sorenson headers")
    void sorensonMalformed() {
        assertThrows(MediaException.Unsupported.class, () -> parseSorenson(sorenson(2, 2).put(0, 30).bytes()));
        assertThrows(MediaException.Malformed.class, () -> parseSorenson(sorenson(0, 7).put(0, 30).bytes()));
        assertThrows(MediaException.Malformed.class, () -> parseSorenson(sorenson(0, 2).put(3, 2).put(0, 30)
                .bytes()));
        assertThrows(MediaException.Malformed.class, () -> parseSorenson(sorenson(0, 2).put(0, 2).put(0, 1)
                .put(0, 5).put(0, 8).bytes()));
        assertThrows(MediaException.Malformed.class, () -> parseSorenson(sorenson(0, 0).put(0, 8).put(9, 8)
                .put(0, 2).put(0, 1).put(8, 5).put(0, 1).bytes()));
        assertThrows(MediaException.Malformed.class, () -> parseSorenson(new byte[8]));
        var runaway = sorenson(0, 2).put(0, 2).put(0, 1).put(8, 5);
        for (var index = 0; index < 5000; index++) {
            runaway.put(1, 1).put(0, 8);
        }
        assertThrows(MediaException.Malformed.class, () -> parseSorenson(runaway.bytes()));
        var cut = sorenson(0, 2).put(0, 2).put(0, 1).put(8, 5).put(1, 1).bytes();
        assertThrows(MediaException.Malformed.class, () -> parseSorenson(java.util.Arrays.copyOf(cut, 5)));
    }
}

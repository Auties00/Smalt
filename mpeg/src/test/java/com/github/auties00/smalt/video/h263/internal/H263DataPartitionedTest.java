package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.video.mpeg4.internal.BitReader;
import com.github.auties00.smalt.video.mpeg4.internal.Vlc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Data Partitioned Slice mode of ITU-T H.263 Annex V beyond the golden decodes of the smalt-dps-* streams
 * in H263AnnexStreamsTest: the reversible code tables, the streams that code the same pictures with and without data
 * partitioning, the resilience the partitions give to corrupt coefficient data, and the mode combinations Annex V
 * forbids.
 */
@DisplayName("H.263 data partitioned slices (Annex V)")
class H263DataPartitionedTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource({"DPS_MCBPC_I, 9", "DPS_MCBPC_P, 26", "DPS_MBTYPE_B, 15", "DPS_MBTYPE_EP, 12", "DPS_MCBPC_EI, 18",
            "DPS_MODB_PB, 3", "DPS_MODB_IMPROVED, 6"})
    @DisplayName("holds the reversible codes of Tables V.1 to V.7, each its own mirror image")
    void reversibleTables(String name, int count) throws ReflectiveOperationException {
        var table = (Vlc) PlusTables.class.getDeclaredField(name).get(null);
        var codes = codes(table);
        assertEquals(count, codes.size(), "codes of " + name + ": " + codes);
        for (var code : codes) {
            assertEquals(new StringBuilder(code).reverse().toString(), code, "code " + code + " of " + name);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"smalt-dps-b.263, smalt-b.263", "smalt-dps-ipb.263, smalt-ipb-df.263",
            "smalt-dps-ipb-rru.263, smalt-ipb-rru.263", "smalt-dps-b-ist.263, smalt-b-ist.263",
            "smalt-dps-ipb-ist.263, smalt-ipb-ist.263"})
    @DisplayName("decodes to the pictures of the same stream coded without data partitioning")
    void sameAsUnpartitioned(String partitioned, String plain) {
        var a = H263AnnexStreamsTest.decode(H263AnnexStreamsTest.resource(partitioned), false);
        var b = H263AnnexStreamsTest.decode(H263AnnexStreamsTest.resource(plain), false);
        assertEquals(b.keySet(), a.keySet(), "sub-bitstreams");
        var expected = b.get(0);
        var actual = a.get(0);
        assertEquals(expected.size(), actual.size(), "pictures");
        for (var i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i).samples(), actual.get(i).samples(), "picture " + i);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"smalt-dps.263", "smalt-dps-ist.263"})
    @DisplayName("keeps the macroblocks and the motion of a slice whose coefficient data is cut short")
    void corruptCoefficients(String name) {
        var data = H263AnnexStreamsTest.resource(name);
        var golden = decodeLast(data).getLast();
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        var last = starts.getLast();
        // the last picture codes its slices in the order A C B D; D, the last slice, covers macroblock columns 7 to
        // 10 of rows 5 to 8
        var sliceD = data.length;
        // a slice start code is byte aligned: 16 zero bits and a one
        for (var i = data.length - 3; i > last; i--) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] & 0x80) != 0) {
                sliceD = i;
                break;
            }
        }
        assertTrue(sliceD < data.length, "slice start code of D");
        var cut = Arrays.copyOf(data, data.length - 3);
        var dropped = Arrays.copyOf(data, sliceD);
        var damaged = decodeLast(cut).getLast();
        var concealed = decodeLast(dropped).getLast();
        var damagedError = 0L;
        var concealedError = 0L;
        for (var plane = 0; plane < 3; plane++) {
            var width = plane == 0 ? 176 : 88;
            var height = plane == 0 ? 144 : 72;
            var offset = plane == 0 ? 0 : plane == 1 ? 176 * 144 : 176 * 144 + 88 * 72;
            var unit = plane == 0 ? 16 : 8;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = offset + y * width + x;
                    // the deblocking filter reaches 2 samples into the slices next to D
                    if (x < 7 * unit - 2 || y < 5 * unit - 2) {
                        assertEquals(golden[at], damaged[at], "sample outside slice D at plane " + plane + " (" + x
                                + ", " + y + ")");
                        continue;
                    }
                    if (x < 7 * unit || y < 5 * unit) {
                        continue;
                    }
                    damagedError += Math.abs((golden[at] & 0xFF) - (damaged[at] & 0xFF));
                    concealedError += Math.abs((golden[at] & 0xFF) - (concealed[at] & 0xFF));
                }
            }
        }
        assertTrue(damagedError > 0, "the cut changes slice D");
        assertTrue(damagedError * 4 < concealedError, "the header and vector partitions of slice D are kept: error "
                + damagedError + " against " + concealedError + " without the slice");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"slice structured mode off, 6, 0x20", "arithmetic coding on, 5, 0x02"})
    @DisplayName("rejects the mode combinations Annex V forbids")
    void forbiddenModes(String name, int index, int mask) {
        var data = H263AnnexStreamsTest.resource("smalt-dps.263");
        // OPPTYPE starts at bit 41 of the first picture header: SAC is bit 46 and SS bit 50
        data[index] ^= (byte) mask;
        var stream = new H263PlusStream(MediaLimits.defaults());
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        var end = starts.size() > 1 ? starts.get(1) : data.length;
        var failure = assertThrows(MediaException.Malformed.class,
                () -> stream.decode(data, 0, end, (picture, time, keyframe, layer, subBitstream) -> {
                }));
        assertTrue(failure.getMessage().contains("data partitioned"), failure.getMessage());
    }

    /**
     * Decodes a stream with the reference inverse DCT, ignoring the failures of corrupt data, and returns the
     * pictures of its first sub-bitstream.
     */
    private static List<byte[]> decodeLast(byte[] data) {
        var stream = new H263PlusStream(MediaLimits.defaults());
        stream.useReferenceTransform(true);
        var out = new ArrayList<byte[]>();
        H263PlusStream.Sink sink = (picture, time, keyframe, layer, subBitstream) ->
                out.add(H263AnnexStreamsTest.planar(picture));
        var starts = H263AnnexStreamsTest.pictureStarts(data);
        for (var k = 0; k < starts.size(); k++) {
            var end = k + 1 < starts.size() ? starts.get(k + 1) : data.length;
            try {
                stream.decode(data, starts.get(k), end - starts.get(k), sink);
            } catch (MediaException.Malformed ignored) {
                // the damaged picture is handed out before the failure
            }
        }
        stream.flush(sink);
        return out;
    }

    /**
     * Lists the codes of a table by probing every bit pattern of up to 11 bits.
     */
    private static TreeSet<String> codes(Vlc table) {
        var codes = new TreeSet<String>();
        var reader = new BitReader();
        var bytes = new byte[2];
        for (var length = 1; length <= 11; length++) {
            for (var value = 0; value < 1 << length; value++) {
                var word = value << (16 - length);
                bytes[0] = (byte) (word >>> 8);
                bytes[1] = (byte) word;
                reader.load(bytes, 0, 2);
                var symbol = table.decode(reader);
                if (symbol != Vlc.INVALID && reader.position() == length) {
                    codes.add(Integer.toBinaryString(value | 1 << length).substring(1));
                }
            }
        }
        return codes;
    }
}

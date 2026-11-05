package com.github.auties00.smalt.video.vvc;

import com.github.auties00.smalt.internal.codec.VvcSyntax;
import com.github.auties00.smalt.internal.nal.NalUnits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the picture and slice header reading the elementary stream demuxers rely on ({@link VvcSyntax#slice}) on
 * JVET conformance streams and the VVenC vectors: the first slice header of every base layer picture must parse,
 * through its reference picture lists, and every short-term and long-term entry of the lists of a picture that is not
 * an IRAP picture must name a picture decoded earlier in its coded video sequence, which a header read at a wrong bit
 * position does not.
 */
@DisplayName("VVC picture and slice header syntax")
class VvcHeaderSyntaxTest {
    /**
     * One first slice header: the picture order count, the NAL unit type and the fields read.
     */
    private record Picture(long poc, int type, VvcSyntax.Slice slice) {
    }

    /**
     * Reads the first slice header of every base layer picture of an Annex B stream, deriving the picture order counts
     * (H.266 clause 8.3.1).
     */
    private static List<Picture> pictures(byte[] stream) {
        Map<Integer, VvcSyntax.Sps> sequences = new HashMap<>();
        Map<Integer, VvcSyntax.Pps> parameters = new HashMap<>();
        var result = new ArrayList<Picture>();
        var header = new VvcSyntax.PictureHeader[1];
        var first = new boolean[]{true};
        var previous = new long[2];
        NalUnits.forEachAnnexB(stream, (data, offset, length) -> {
            var type = VvcSyntax.type(data, offset);
            if (VvcSyntax.layer(data, offset) != 0) {
                return;
            }
            if (type == VvcSyntax.SPS) {
                var sps = VvcSyntax.sps(data, offset, length);
                assertNotNull(sps, "sequence parameter set");
                assertNotNull(sps.tools(), "sequence parameter set slice fields");
                sequences.put(sps.id(), sps);
            } else if (type == VvcSyntax.PPS) {
                var pps = VvcSyntax.pps(data, offset, length);
                assertNotNull(pps, "picture parameter set");
                assertNotNull(pps.tools(), "picture parameter set slice fields");
                parameters.put(pps.id(), pps);
            } else if (type == VvcSyntax.PH) {
                header[0] = VvcSyntax.pictureHeader(data, offset, length, sequences, parameters);
                assertNotNull(header[0], "picture header");
                assertTrue(header[0].complete(), "picture header read whole");
                first[0] = true;
            } else if (VvcSyntax.isSlice(type)) {
                var inSlice = (data[offset + 2] & 0x80) != 0;
                if (!inSlice && !first[0]) {
                    return;
                }
                first[0] = false;
                var slice = VvcSyntax.slice(data, offset, length, inSlice ? null : header[0], sequences,
                        parameters);
                assertNotNull(slice, "slice header of picture " + result.size());
                var picture = slice.header();
                var maxLsb = 1L << picture.sps().log2MaxPocLsb();
                var lsb = picture.pocLsb();
                var idr = type == VvcSyntax.IDR_W_RADL || type == VvcSyntax.IDR_N_LP;
                long msb;
                if (picture.pocMsb() >= 0) {
                    msb = picture.pocMsb() * maxLsb;
                } else if (idr || result.isEmpty()) {
                    msb = 0;
                } else if (lsb < previous[1] && previous[1] - lsb >= maxLsb / 2) {
                    msb = previous[0] + maxLsb;
                } else if (lsb > previous[1] && lsb - previous[1] > maxLsb / 2) {
                    msb = previous[0] - maxLsb;
                } else {
                    msb = previous[0];
                }
                // RASL (3) and RADL (2) pictures and non-reference pictures do not anchor later order counts
                if (VvcSyntax.temporalId(data, offset) == 0 && !picture.nonReference() && type != 2 && type != 3) {
                    previous[0] = msb;
                    previous[1] = lsb;
                }
                result.add(new Picture(msb + lsb, type, slice));
            }
        });
        return result;
    }

    /**
     * Asserts that the reference picture lists of every picture after the first IRAP picture of a coded video
     * sequence name earlier pictures of the sequence.
     */
    private static void assertReferencesDecoded(List<Picture> pictures, String name) {
        Set<Long> decoded = new HashSet<>();
        var index = 0;
        for (var picture : pictures) {
            var type = picture.type();
            if (type == VvcSyntax.IDR_W_RADL || type == VvcSyntax.IDR_N_LP) {
                decoded.clear();
            }
            if (!VvcSyntax.isIrap(type)) {
                var references = picture.slice().references();
                var log2MaxPocLsb = picture.slice().header().sps().log2MaxPocLsb();
                for (var delta : references.shortTerm()) {
                    assertTrue(decoded.contains(picture.poc() + delta), name + " picture " + index + " (order count "
                            + picture.poc() + ") names the undecoded short-term reference " + (picture.poc() + delta));
                }
                var single = new VvcSyntax.References(new int[0], new int[1], new int[1]);
                for (var i = 0; i < references.longTermLsb().length; i++) {
                    single.longTermLsb()[0] = references.longTermLsb()[i];
                    single.longTermMsbCycle()[0] = references.longTermMsbCycle()[i];
                    var found = false;
                    for (var other : decoded) {
                        found |= single.keeps(other, picture.poc(), log2MaxPocLsb);
                    }
                    assertTrue(found, name + " picture " + index + " names an undecoded long-term reference");
                }
            }
            decoded.add(picture.poc());
            index++;
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"RPL_A_2", "LTRP_A_3", "BUMP_A_2", "BUMP_B_2", "DPB_A_2", "POUT_A_2", "PPS_B_1", "PPS_C_1",
            "RAP_C_1", "SUBPIC_A_3", "SUBPIC_B_3", "WP_A_3", "WP_B_3", "OLS_C_5", "RPR_A_4", "HRD_A_3", "SPS_B_1",
            "VPS_A_3", "IBC_A_2", "DCI_A_3", "CodingToolsSets_A_2", "ALF_A_3", "DEBLOCKING_E_3", "QUANT_D_4",
            "SCALING_A_1", "PHSH_B_1", "FIELD_A_4", "WRAP_A_4", "ENTMAINTIER_D_3"})
    @DisplayName("reads every first slice header of a conformance stream through its reference picture lists")
    void conformance(String name) throws IOException {
        var pictures = pictures(VvcConformanceTest.bitstream(name, name + ".bit"));
        assertTrue(pictures.size() > 1);
        assertReferencesDecoded(pictures, name);
        for (var picture : pictures) {
            assertFalse(picture.slice().noOutputOfPriorPics(), "no conformance stream discards prior pictures");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ra-420-10bit", "ld-420-8bit", "intra-400-10bit", "tiles-420-10bit", "hdr-420-10bit",
            "concat-420-8bit"})
    @DisplayName("reads every first slice header of a VVenC stream through its reference picture lists")
    void vvenc(String name) {
        var pictures = pictures(VvcDecoderTest.vector(name).stream());
        assertEquals(VvcDecoderTest.vector(name).frames(), pictures.size());
        assertReferencesDecoded(pictures, name);
    }

    @Test
    @DisplayName("reads sh_no_output_of_prior_pics_flag from the IDR picture that starts the second sequence")
    void noOutputOfPriorPics() {
        var pictures = pictures(VvcDecoderTest.vector("no-output-420-8bit").stream());
        assertEquals(18, pictures.size());
        assertReferencesDecoded(pictures, "no-output-420-8bit");
        for (var i = 0; i < pictures.size(); i++) {
            assertEquals(i == 9, pictures.get(i).slice().noOutputOfPriorPics(), "flag of picture " + i);
        }
        // VVenC's random access structure codes each sequence's IDR picture first, at order count 7, and leads it
        // with RADL pictures 3, 1, 0, 2, 5, 4 and 6 before the trailing picture 8
        assertEquals(List.of(7L, 3L, 1L, 0L, 2L, 5L, 4L, 6L, 8L), pictures.stream().limit(9).map(Picture::poc).toList());
    }
}

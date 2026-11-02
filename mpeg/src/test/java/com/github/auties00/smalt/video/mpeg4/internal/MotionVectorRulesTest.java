package com.github.auties00.smalt.video.mpeg4.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the vector derivations of interlaced direct mode (ISO/IEC 14496-2 7.7.2.2) and of
 * overlapped block motion compensation (7.6.6, ITU-T H.263 F.3) against values worked out by
 * hand from the standards.
 */
@DisplayName("MPEG-4 Part 2 vector rules")
class MotionVectorRulesTest {
    @Nested
    @DisplayName("interlaced direct mode")
    class InterlacedDirect {
        @ParameterizedTest(name = "top field first {0}, field {1}, reference {2}: {3}")
        @CsvSource({
                "true, 0, 0, 0", "true, 0, 1, -1", "true, 1, 0, 1", "true, 1, 1, 0",
                "false, 0, 0, 0", "false, 0, 1, 1", "false, 1, 0, -1", "false, 1, 1, 0"
        })
        @DisplayName("corrects the field distance as Table 7-12 does")
        void fieldDelta(boolean topFieldFirst, int field, int reference, int expected) {
            assertEquals(expected, VopParser.fieldDelta(topFieldFirst, field, reference));
        }

        @Test
        @DisplayName("scales the co-located field vectors in frame coordinates without a delta vector")
        void withoutDelta() {
            // Bottom field first, both co-located fields reference the top field, TRB/TRD in
            // frames 1/3: the top field scales by 2/6, the bottom field by 1/5 (delta -1).
            int[] colocated = {0, 8, 0, 8};
            var forward = new int[4];
            var backward = new int[4];
            VopParser.fieldDirectVectors(colocated, 0, 0, false, 1, 3, 0, 0, forward, backward);
            // 2 * 8 / 6 = 2, -4 * 8 / 6 = -5 rounded down to -6; 8 / 5 = 1 rounded down to 0,
            // -4 * 8 / 5 = -6.
            assertArrayEquals(new int[]{0, 2, 0, 0}, forward);
            assertArrayEquals(new int[]{0, -6, 0, -6}, backward);
        }

        @Test
        @DisplayName("adds the delta vector in frame coordinates, component by component")
        void withDelta() {
            // Top field first, the top field references the bottom field (delta -1, TRB/TRD 3/5)
            // and the bottom field the top field (delta 1, TRB/TRD 5/7); TRB/TRD in frames 2/3.
            int[] colocated = {10, -22, -8, 4};
            var forward = new int[4];
            var backward = new int[4];
            VopParser.fieldDirectVectors(colocated, 0, 1, true, 2, 3, 3, 0, forward, backward);
            // Top: 3 * 10 / 5 + 3 = 9, 9 - 10 = -1; 3 * -22 / 5 = -13 rounded down to -14,
            // (3 - 5) * -22 / 5 = 8. Bottom: 5 * -8 / 7 + 3 = -2, -2 + 8 = 6; 5 * 4 / 7 = 2,
            // (5 - 7) * 4 / 7 = -1 rounded down to -2.
            assertArrayEquals(new int[]{9, -14, -2, 2}, forward);
            assertArrayEquals(new int[]{-1, 8, 6, -2}, backward);
        }

        @Test
        @DisplayName("writes at the macroblock's index and survives a zero distance")
        void offsetAndZeroDistance() {
            int[] colocated = {0, 0, 0, 0, 4, 6, -4, -6};
            var forward = new int[8];
            var backward = new int[8];
            VopParser.fieldDirectVectors(colocated, 4, 0b10, true, 0, 0, 0, 2, forward, backward);
            // TRD is 0 for both fields and taken as 1; TRB is 0.
            assertArrayEquals(new int[]{0, 0, 0, 0, 0, 2, 0, 2}, forward);
            assertArrayEquals(new int[]{0, 0, 0, 0, -4, -4, 4, 8}, backward);
        }

        @ParameterizedTest(name = "{0} // {1} = {2}")
        @CsvSource({"0, 2, 0", "1, 2, 1", "3, 2, 2", "5, 2, 3", "4, 3, 1", "5, 3, 2", "7, 1, 7"})
        @DisplayName("rounds VOP times to frame numbers with the // operator")
        void frameNumber(long time, long period, long expected) {
            assertEquals(expected, Mpeg4DecoderEngine.frameNumber(time, period));
        }
    }

    @Nested
    @DisplayName("overlapped motion compensation")
    class Overlapped {
        // A 3x3 picture whose centre macroblock has four vectors, with vector (10 * mb + block, -block).
        private Frame picture() {
            var frame = new Frame(3, 3);
            for (var mb = 0; mb < 9; mb++) {
                frame.mbType[mb] = Frame.MB_INTER4V;
                for (var block = 0; block < 4; block++) {
                    frame.mv[mb * 8 + 2 * block] = 10 * mb + block;
                    frame.mv[mb * 8 + 2 * block + 1] = -block;
                }
            }
            return frame;
        }

        private int[] gather(Frame frame, int mb, int block) {
            return gather(frame, null, mb, block);
        }

        private int[] gather(Frame frame, int[] segments, int mb, int block) {
            var out = new int[10];
            Reconstructor.overlappedVectors(frame, frame.mv, segments, mb, mb % 3, mb / 3, block, out);
            return out;
        }

        @Test
        @DisplayName("takes the nearest blocks of the neighbors, and the block's own vector below the bottom row")
        void interior() {
            var frame = picture();
            // Block 0 of the centre: above is block 2 of macroblock 1, below block 2 of the centre,
            // left block 1 of macroblock 3, right block 1 of the centre.
            assertArrayEquals(new int[]{40, 0, 12, -2, 42, -2, 31, -1, 41, -1}, gather(frame, 4, 0));
            // Block 1: above block 3 of macroblock 1, below block 3, left block 0, right block 0
            // of macroblock 5.
            assertArrayEquals(new int[]{41, -1, 13, -3, 43, -3, 40, 0, 50, 0}, gather(frame, 4, 1));
            // Block 3: above block 1, below its own vector, left block 2, right block 2 of
            // macroblock 5.
            assertArrayEquals(new int[]{43, -3, 41, -1, 43, -3, 42, -2, 52, -2}, gather(frame, 4, 3));
        }

        @Test
        @DisplayName("replaces intra and absent neighbors by the block's own vector and skipped ones by zero")
        void substitutes() {
            var frame = picture();
            frame.mbType[1] = Frame.MB_INTRA;
            frame.mbType[3] = Frame.MB_SKIPPED;
            frame.mbType[5] = Frame.MB_INTER;
            assertArrayEquals(new int[]{40, 0, 40, 0, 42, -2, 0, 0, 41, -1}, gather(frame, 4, 0));
            assertArrayEquals(new int[]{41, -1, 41, -1, 43, -3, 40, 0, 50, 0}, gather(frame, 4, 1));
            // Macroblock 0 has no neighbor above or to the left.
            assertArrayEquals(new int[]{0, 0, 0, 0, 2, -2, 0, 0, 1, -1}, gather(frame, 0, 0));
            // Macroblock 8 has none to the right or below.
            assertArrayEquals(new int[]{83, -3, 81, -1, 83, -3, 82, -2, 83, -3}, gather(frame, 8, 3));
        }

        @Test
        @DisplayName("replaces neighbors in other slices by the block's own vector (Annex K)")
        void slices() {
            var frame = picture();
            frame.mbType[6] = Frame.MB_SKIPPED;
            // One slice holds macroblocks 0 to 4, the next 5 to 8.
            int[] segments = {0, 0, 0, 0, 0, 5, 5, 5, 5};
            // Block 1 of the centre: macroblock 1 above is in the slice, macroblock 5 to the right
            // is not.
            assertArrayEquals(new int[]{41, -1, 13, -3, 43, -3, 40, 0, 41, -1}, gather(frame, segments, 4, 1));
            // Block 0 of macroblock 7: macroblock 4 above is in the other slice; macroblock 6 to the
            // left is in the slice, and skipped.
            assertArrayEquals(new int[]{70, 0, 70, 0, 72, -2, 0, 0, 71, -1}, gather(frame, segments, 7, 0));
            // Without slices the same neighbors take part.
            assertArrayEquals(new int[]{41, -1, 13, -3, 43, -3, 40, 0, 50, 0}, gather(frame, 4, 1));
        }
    }
}

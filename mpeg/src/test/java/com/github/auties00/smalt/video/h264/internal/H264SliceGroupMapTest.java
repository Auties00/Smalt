package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.video.h264.H264BitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Checks the macroblock to slice group mapping of ITU-T H.264 8.2.2.8 for the three picture
 * kinds of a sequence that allows field coding, on a 2x4 macroblock frame whose map units are
 * macroblock pairs, with a raster scan map (type 4) placing three map units in slice group 0.
 * The expected maps are derived by hand from the equations of 8.2.2.8.
 */
@DisplayName("H.264 slice group map")
class H264SliceGroupMapTest {
    private static int[] map(boolean field, boolean mbaff) {
        var spsPayload = unescape(H264BitWriter.sps(77, 2, 4, 1));
        var sps = H264Sps.parse(spsPayload, 0, spsPayload.length - H264BitReader.PADDING);
        sps.frameMbsOnly = false;
        sps.widthInMbs = 2;
        sps.heightInMapUnits = 2;
        sps.frameHeightInMbs = 4;
        var ppsPayload = unescape(H264BitWriter.pps());
        var pps = H264Pps.parse(ppsPayload, 0, ppsPayload.length - H264BitReader.PADDING, sps);
        pps.numSliceGroups = 2;
        pps.sliceGroupMapType = 4;
        pps.sliceGroupChangeRate = 1;
        pps.sliceGroupChangeDirection = false;
        var target = new int[8];
        Arrays.fill(target, -1);
        SliceGroupMap.compute(pps, sps, 3, field, mbaff, target);
        return field ? Arrays.copyOf(target, 4) : target;
    }

    /** Returns the payload of a NAL unit, unescaped and padded for the bit reader. */
    private static byte[] unescape(byte[] nal) {
        var out = new byte[nal.length + H264BitReader.PADDING];
        var size = NalUnits.unescape(nal, 1, nal.length - 1, out);
        return Arrays.copyOf(out, size + H264BitReader.PADDING);
    }

    @Test
    @DisplayName("a field uses the map units as its macroblocks")
    void field() {
        assertArrayEquals(new int[]{0, 0, 0, 1}, map(true, false));
    }

    @Test
    @DisplayName("an MBAFF frame gives both macroblocks of a pair the group of the pair's map unit")
    void mbaff() {
        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 0, 1, 1}, map(false, true));
    }

    @Test
    @DisplayName("a non-MBAFF frame gives each map unit the two macroblocks above each other")
    void frame() {
        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 1, 0, 1}, map(false, false));
    }
}

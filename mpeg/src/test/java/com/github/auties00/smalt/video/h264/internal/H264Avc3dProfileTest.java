package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.video.h264.H264BitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks which sequence parameter sets of a texture slice using the 3D-AVC texture coding tools break the
 * Enhanced Multiview Depth High profile (ITU-T H.264 I.10.1.1): field and MBAFF coding, samples above 8 bits and
 * chroma formats other than 4:0:0 and 4:2:0 under profile_idc 139, and none of them under another profile.
 */
@DisplayName("H.264 3D-AVC profile constraints of the texture coding tools")
class H264Avc3dProfileTest {
    private static String violation(boolean fieldPic, Consumer<H264Sps> change) {
        var payload = H264BitWriter.sps(100, 2, 2, 1);
        var rbsp = new byte[payload.length + H264BitReader.PADDING];
        var size = NalUnits.unescape(payload, 1, payload.length - 1, rbsp);
        rbsp = Arrays.copyOf(rbsp, size + H264BitReader.PADDING);
        var sps = H264Sps.parse(rbsp, 0, size);
        sps.profileIdc = 139;
        change.accept(sps);
        var slice = new H264SliceHeader();
        slice.fieldPic = fieldPic;
        return H264DecoderEngine.avc3dViolation(slice, sps);
    }

    @Test
    @DisplayName("accepts 8-bit 4:2:0 and 4:0:0 frames")
    void accepted() {
        assertNull(violation(false, sps -> { }));
        assertNull(violation(false, sps -> sps.chromaFormatIdc = 0));
    }

    @Test
    @DisplayName("rejects field pictures and MBAFF frames")
    void fieldCoding() {
        assertTrue(violation(false, sps -> sps.frameMbsOnly = false).contains("frame_mbs_only_flag 0"));
        assertTrue(violation(true, sps -> sps.frameMbsOnly = false).contains("frame_mbs_only_flag 0"));
        assertTrue(violation(true, sps -> { }).contains("frame_mbs_only_flag 0"));
    }

    @Test
    @DisplayName("rejects samples above 8 bits and chroma formats above 4:2:0")
    void sampleFormat() {
        assertTrue(violation(false, sps -> sps.bitDepthLuma = 10).contains("above 8 bits"));
        assertTrue(violation(false, sps -> sps.bitDepthChroma = 9).contains("above 8 bits"));
        assertTrue(violation(false, sps -> sps.chromaFormatIdc = 2).contains("chroma_format_idc 2"));
        assertTrue(violation(false, sps -> sps.chromaFormatIdc = 3).contains("chroma_format_idc 3"));
    }

    @Test
    @DisplayName("leaves sequence parameter sets of other profiles alone")
    void otherProfiles() {
        assertNull(violation(true, sps -> {
            sps.profileIdc = 138;
            sps.frameMbsOnly = false;
            sps.bitDepthLuma = 10;
        }));
    }
}

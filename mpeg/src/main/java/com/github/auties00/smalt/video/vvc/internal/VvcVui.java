/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/HLSyntaxReader.cpp). Copyright (c) 2018-2026, Fraunhofer-Gesellschaft
 * zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Holds the video usability information of a sequence parameter set (ITU-T H.266 7.3.2.21 and ITU-T H.274).
 */
public final class VvcVui {
    /**
     * Constructs the parameters of a sequence without VUI.
     */
    public VvcVui() {
    }

    /**
     * The sample aspect ratios of {@code vui_aspect_ratio_idc} 1 to 16 (Rec. ITU-T H.273 Table 7).
     */
    private static final int[][] SAR_RATIOS = {
            {1, 1}, {12, 11}, {10, 11}, {16, 11}, {40, 33}, {24, 11}, {20, 11}, {32, 11}, {80, 33}, {18, 11},
            {15, 11}, {64, 33}, {160, 99}, {4, 3}, {3, 2}, {2, 1}
    };

    /**
     * {@code vui_progressive_source_flag}.
     */
    public boolean progressiveSource;
    /**
     * {@code vui_interlaced_source_flag}.
     */
    public boolean interlacedSource;
    /**
     * The width of the sample aspect ratio, or 0 when unknown.
     */
    public int sarWidth;
    /**
     * The height of the sample aspect ratio, or 0 when unknown.
     */
    public int sarHeight;
    /**
     * {@code vui_colour_description_present_flag}.
     */
    public boolean colourDescriptionPresent;
    /**
     * {@code vui_colour_primaries}, 2 (unspecified) when absent.
     */
    public int colourPrimaries = 2;
    /**
     * {@code vui_transfer_characteristics}, 2 (unspecified) when absent.
     */
    public int transferCharacteristics = 2;
    /**
     * {@code vui_matrix_coeffs}, 2 (unspecified) when absent.
     */
    public int matrixCoefficients = 2;
    /**
     * {@code vui_full_range_flag}.
     */
    public boolean fullRange;
    /**
     * {@code vui_chroma_loc_info_present_flag}.
     */
    public boolean chromaLocPresent;
    /**
     * {@code vui_chroma_sample_loc_type_frame} (or the top field's), or -1 when absent.
     */
    public int chromaSampleLocType = -1;

    /**
     * Parses the payload; the caller skips to its end afterwards.
     *
     * @param r the reader
     * @param payloadBits the payload size in bits
     */
    void parse(H264BitReader r, long payloadBits) {
        progressiveSource = r.readFlag();
        interlacedSource = r.readFlag();
        r.readFlag();
        r.readFlag();
        if (r.readFlag()) {
            r.readFlag();
            var idc = r.readBits(8);
            if (idc == 255) {
                sarWidth = r.readBits(16);
                sarHeight = r.readBits(16);
            } else if (idc > 0 && idc <= SAR_RATIOS.length) {
                sarWidth = SAR_RATIOS[idc - 1][0];
                sarHeight = SAR_RATIOS[idc - 1][1];
            }
        }
        if (r.readFlag()) {
            r.readFlag();
        }
        colourDescriptionPresent = r.readFlag();
        if (colourDescriptionPresent) {
            colourPrimaries = r.readBits(8);
            transferCharacteristics = r.readBits(8);
            matrixCoefficients = r.readBits(8);
            fullRange = r.readFlag();
        }
        chromaLocPresent = r.readFlag();
        if (chromaLocPresent) {
            if (progressiveSource && !interlacedSource) {
                chromaSampleLocType = r.readUe(6, "vui_chroma_sample_loc_type_frame");
            } else {
                chromaSampleLocType = r.readUe(6, "vui_chroma_sample_loc_type_top_field");
                r.readUe(6, "vui_chroma_sample_loc_type_bottom_field");
            }
        }
    }
}

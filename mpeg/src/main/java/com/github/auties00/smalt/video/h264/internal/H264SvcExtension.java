package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Holds a parsed sequence parameter set SVC extension (ITU-T H.264 F.7.3.2.1.4): the geometry of
 * the inter-layer prediction of a scalable layer and the syntax switches of its slice headers.
 *
 * <p>Instances are immutable after parsing.
 */
public final class H264SvcExtension {
    /**
     * The {@code inter_layer_deblocking_filter_control_present_flag}.
     */
    final boolean interLayerDeblockingControl;

    /**
     * The {@code extended_spatial_scalability_idc}, 0 to 2.
     */
    final int extendedSpatialScalability;

    /**
     * The {@code chroma_phase_x_plus1_flag}, 1 when absent.
     */
    final int chromaPhaseXPlus1;

    /**
     * The {@code chroma_phase_y_plus1}, 1 when absent.
     */
    final int chromaPhaseYPlus1;

    /**
     * The {@code seq_ref_layer_chroma_phase_x_plus1_flag}.
     */
    final int refLayerChromaPhaseXPlus1;

    /**
     * The {@code seq_ref_layer_chroma_phase_y_plus1}.
     */
    final int refLayerChromaPhaseYPlus1;

    /**
     * The {@code seq_scaled_ref_layer_left_offset}.
     */
    final int scaledLeftOffset;

    /**
     * The {@code seq_scaled_ref_layer_top_offset}.
     */
    final int scaledTopOffset;

    /**
     * The {@code seq_scaled_ref_layer_right_offset}.
     */
    final int scaledRightOffset;

    /**
     * The {@code seq_scaled_ref_layer_bottom_offset}.
     */
    final int scaledBottomOffset;

    /**
     * The {@code seq_tcoeff_level_prediction_flag}.
     */
    final boolean tcoeffLevelPrediction;

    /**
     * The {@code adaptive_tcoeff_level_prediction_flag}.
     */
    final boolean adaptiveTcoeffLevelPrediction;

    /**
     * The {@code slice_header_restriction_flag}.
     */
    final boolean sliceHeaderRestriction;

    /**
     * Creates the extension from its parsed syntax elements.
     *
     * @param interLayerDeblockingControl the {@code inter_layer_deblocking_filter_control_present_flag}
     * @param extendedSpatialScalability the {@code extended_spatial_scalability_idc}
     * @param chromaPhaseXPlus1 the {@code chroma_phase_x_plus1_flag}
     * @param chromaPhaseYPlus1 the {@code chroma_phase_y_plus1}
     * @param refLayerChromaPhaseXPlus1 the {@code seq_ref_layer_chroma_phase_x_plus1_flag}
     * @param refLayerChromaPhaseYPlus1 the {@code seq_ref_layer_chroma_phase_y_plus1}
     * @param scaledLeftOffset the {@code seq_scaled_ref_layer_left_offset}
     * @param scaledTopOffset the {@code seq_scaled_ref_layer_top_offset}
     * @param scaledRightOffset the {@code seq_scaled_ref_layer_right_offset}
     * @param scaledBottomOffset the {@code seq_scaled_ref_layer_bottom_offset}
     * @param tcoeffLevelPrediction the {@code seq_tcoeff_level_prediction_flag}
     * @param adaptiveTcoeffLevelPrediction the {@code adaptive_tcoeff_level_prediction_flag}
     * @param sliceHeaderRestriction the {@code slice_header_restriction_flag}
     */
    private H264SvcExtension(boolean interLayerDeblockingControl, int extendedSpatialScalability,
                             int chromaPhaseXPlus1, int chromaPhaseYPlus1, int refLayerChromaPhaseXPlus1,
                             int refLayerChromaPhaseYPlus1, int scaledLeftOffset, int scaledTopOffset,
                             int scaledRightOffset, int scaledBottomOffset, boolean tcoeffLevelPrediction,
                             boolean adaptiveTcoeffLevelPrediction, boolean sliceHeaderRestriction) {
        this.interLayerDeblockingControl = interLayerDeblockingControl;
        this.extendedSpatialScalability = extendedSpatialScalability;
        this.chromaPhaseXPlus1 = chromaPhaseXPlus1;
        this.chromaPhaseYPlus1 = chromaPhaseYPlus1;
        this.refLayerChromaPhaseXPlus1 = refLayerChromaPhaseXPlus1;
        this.refLayerChromaPhaseYPlus1 = refLayerChromaPhaseYPlus1;
        this.scaledLeftOffset = scaledLeftOffset;
        this.scaledTopOffset = scaledTopOffset;
        this.scaledRightOffset = scaledRightOffset;
        this.scaledBottomOffset = scaledBottomOffset;
        this.tcoeffLevelPrediction = tcoeffLevelPrediction;
        this.adaptiveTcoeffLevelPrediction = adaptiveTcoeffLevelPrediction;
        this.sliceHeaderRestriction = sliceHeaderRestriction;
    }

    /**
     * Parses a {@code seq_parameter_set_svc_extension()}.
     *
     * @param reader the reader positioned at the extension
     * @param chromaArrayType the {@code ChromaArrayType} of the set
     * @return the extension
     * @throws MediaException.Malformed if a field is out of range or the extension is truncated
     */
    static H264SvcExtension parse(H264BitReader reader, int chromaArrayType) {
        var deblocking = reader.readFlag();
        var spatial = reader.readBits(2);
        if (spatial > 2) {
            throw new MediaException.Malformed("extended_spatial_scalability_idc out of range");
        }
        var phaseX = 1;
        var phaseY = 1;
        if (chromaArrayType == 1 || chromaArrayType == 2) {
            phaseX = reader.readBits(1);
        }
        if (chromaArrayType == 1) {
            phaseY = reader.readBits(2);
            if (phaseY > 2) {
                throw new MediaException.Malformed("chroma_phase_y_plus1 out of range");
            }
        }
        var refPhaseX = phaseX;
        var refPhaseY = phaseY;
        int left = 0;
        int top = 0;
        int right = 0;
        int bottom = 0;
        if (spatial == 1) {
            if (chromaArrayType > 0) {
                refPhaseX = reader.readBits(1);
                refPhaseY = reader.readBits(2);
                if (refPhaseY > 2) {
                    throw new MediaException.Malformed("seq_ref_layer_chroma_phase_y_plus1 out of range");
                }
            }
            left = reader.readSe();
            top = reader.readSe();
            right = reader.readSe();
            bottom = reader.readSe();
        }
        var tcoeff = reader.readFlag();
        var adaptiveTcoeff = tcoeff && reader.readFlag();
        var restriction = reader.readFlag();
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated sequence parameter set SVC extension");
        }
        return new H264SvcExtension(deblocking, spatial, phaseX, phaseY, refPhaseX, refPhaseY, left, top, right,
                bottom, tcoeff, adaptiveTcoeff, restriction);
    }
}

package com.github.auties00.smalt.video;

import java.util.Objects;

/**
 * Identifies the layer of a multi-layer video stream that a frame was decoded from: a view of a
 * multiview stream, a quality or resolution layer of a scalable stream, or an auxiliary layer
 * such as a depth map.
 *
 * <p>For HEVC the identifier is the {@code nuh_layer_id} of the layer's NAL units (ITU-T H.265
 * Annex F), from 0 to 63, 0 for the base layer, and the view identifier is the {@code ViewId} the
 * video parameter set assigns to the layer's view. For an H.264 multiview stream (ITU-T H.264
 * Annex G) the identifier is the view order index {@code VOIdx} of the view, from 0 to 1023, 0 for
 * the base view, and the view identifier is its {@code view_id}. For an H.264 scalable stream
 * (ITU-T H.264 Annex F) the identifier is the {@code DQId} of the layer the frame was decoded at
 * ({@code dependency_id * 16 + quality_id}), from 0 to 127, and the view identifier is 0.
 *
 * @param id the layer identifier, from 0 to 1023
 * @param viewId the view identifier of the layer's view, zero or more; 0 for a stream with one view
 * @param type what the layer's pictures hold
 */
public record VideoLayer(int id, int viewId, Type type) {
    /**
     * Validates the layer.
     *
     * @throws IllegalArgumentException if {@code id} is not from 0 to 1023 or {@code viewId} is
     * negative
     */
    public VideoLayer {
        if (id < 0 || id > 1023) {
            throw new IllegalArgumentException("layer identifier outside 0 to 1023: " + id);
        }
        if (viewId < 0) {
            throw new IllegalArgumentException("negative view identifier " + viewId);
        }
        Objects.requireNonNull(type, "type");
    }

    /**
     * What the pictures of a layer hold.
     *
     * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Type {
        /**
         * Pictures meant for display: a view or a quality or resolution layer.
         */
        PRIMARY,

        /**
         * Alpha planes of the pictures of a primary layer, in the luma samples ({@code AUX_ALPHA}).
         */
        ALPHA,

        /**
         * Depth maps of the pictures of a primary layer, in the luma samples ({@code AUX_DEPTH}).
         */
        DEPTH,

        /**
         * Auxiliary pictures of another or an unspecified kind.
         */
        AUXILIARY
    }
}

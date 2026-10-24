package com.github.auties00.smalt.video;

import java.util.Objects;

/**
 * Identifies the layer of a multi-layer video stream that a frame was decoded from: a view of a
 * multiview stream, a quality or resolution layer of a scalable stream, or an auxiliary layer
 * such as a depth map.
 *
 * <p>For HEVC the identifier is the {@code nuh_layer_id} of the layer's NAL units (ITU-T H.265
 * Annex F), 0 for the base layer, and the view identifier is the {@code ViewId} the video
 * parameter set assigns to the layer's view.
 *
 * @param id the layer identifier, from 0 to 63
 * @param viewId the view identifier of the layer's view, zero or more; 0 for a stream with one view
 * @param type what the layer's pictures hold
 */
public record VideoLayer(int id, int viewId, Type type) {
    /**
     * Validates the layer.
     *
     * @throws IllegalArgumentException if {@code id} is not from 0 to 63 or {@code viewId} is
     * negative
     */
    public VideoLayer {
        if (id < 0 || id > 63) {
            throw new IllegalArgumentException("layer identifier outside 0 to 63: " + id);
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

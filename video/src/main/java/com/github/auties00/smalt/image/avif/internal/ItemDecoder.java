package com.github.auties00.smalt.image.avif.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.video.av1.internal.Av1Context;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the AV1 payloads of AVIF image items and sequence samples into pictures of the AV1
 * decoder.
 *
 * <p>An item is one temporal unit, coded as a key frame. For an item decoder each call starts
 * from a fresh decoding state, so the items it decodes one after the other (the tiles of a grid)
 * never depend on one another; a sequence decoder keeps its state, because each sample predicts
 * from the ones before it. Instances are not thread-safe: a grid decoded in parallel uses one
 * instance per thread.
 */
final class ItemDecoder {
    /**
     * The AV1 decoding state.
     */
    private final Av1Context context;

    /**
     * The pictures shown by the current payload.
     */
    private final List<Av1Picture> shown = new ArrayList<>();

    /**
     * Whether the configuration record was decoded since the last reset.
     */
    private boolean configured;

    /**
     * Creates a decoder.
     *
     * @param limits   the bounds checked against every frame size
     * @param threaded whether a frame may decode on the module's helper threads; a grid whose
     *                 tiles decode in parallel decodes each tile on one thread
     */
    ItemDecoder(MediaLimits limits, boolean threaded) {
        this.context = new Av1Context(limits);
        if (!threaded) {
            context.threading(Av1Context.Threading.OFF);
        }
    }

    /**
     * Decodes one item.
     *
     * @param config         the {@code av1C} configuration of the item, whose configuration OBUs
     *                       are decoded first
     * @param payload        the item data
     * @param operatingPoint the operating point of the {@code a1op} property, or {@code 0}
     * @param layer          the spatial layer of the {@code lsel} property, or
     *                       {@link HeifProperty.LayerSelector#ALL_LAYERS} for the highest layer
     *                       of the operating point
     * @return the picture, which the caller releases
     * @throws MediaException.Malformed     if the payload is corrupt or shows no frame of the
     *                                      requested layer
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    Av1Picture decodeItem(HeifProperty.Av1Configuration config, byte[] payload, int operatingPoint, int layer) {
        context.reset();
        context.operatingPoint(operatingPoint);
        var selectsLayer = layer != HeifProperty.LayerSelector.ALL_LAYERS;
        context.allLayers(selectsLayer);
        if (config != null) {
            context.decodeConfig(config.record());
        }
        shown.clear();
        try {
            context.decode(payload, 0, payload.length, 0, 0, shown);
            Av1Picture chosen = null;
            for (var i = shown.size() - 1; i >= 0; i--) {
                var picture = shown.get(i);
                if (!selectsLayer || picture.frameHdr.spatialId == layer) {
                    chosen = picture;
                    shown.remove(i);
                    break;
                }
            }
            if (chosen == null) {
                throw new MediaException.Malformed(selectsLayer
                        ? "avif: the item has no frame of spatial layer " + layer
                        : "avif: the item shows no frame");
            }
            return chosen;
        } finally {
            for (var picture : shown) {
                picture.release();
            }
            shown.clear();
        }
    }

    /**
     * Decodes the next sample of a sequence, keeping the decoding state of the samples before it.
     *
     * @param config  the {@code av1C} configuration of the track, decoded before the first sample
     * @param payload the sample data
     * @return the picture the sample shows, which the caller releases, or {@code null} when the
     *         sample shows none
     * @throws MediaException.Malformed     if the sample is corrupt
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    Av1Picture decodeSample(byte[] config, byte[] payload) {
        if (!configured) {
            configured = true;
            if (config != null && config.length > 4) {
                context.decodeConfig(config);
            }
        }
        shown.clear();
        try {
            context.decode(payload, 0, payload.length, 0, 0, shown);
            if (shown.isEmpty()) {
                return null;
            }
            return shown.removeLast();
        } finally {
            for (var picture : shown) {
                picture.release();
            }
            shown.clear();
        }
    }

    /**
     * Returns the mastering display of the last {@code METADATA_TYPE_HDR_MDCV} metadata OBU.
     *
     * @return the mastering display, or {@code null} when none was seen
     */
    com.github.auties00.smalt.image.MasteringDisplay masteringDisplay() {
        return context.masteringDisplay();
    }

    /**
     * Returns the light level of the last {@code METADATA_TYPE_HDR_CLL} metadata OBU.
     *
     * @return the light level, or {@code null} when none was seen
     */
    com.github.auties00.smalt.image.ContentLightLevel contentLightLevel() {
        return context.contentLightLevel();
    }

    /**
     * Discards the references and the decoding state.
     */
    void close() {
        context.reset();
        configured = false;
    }
}

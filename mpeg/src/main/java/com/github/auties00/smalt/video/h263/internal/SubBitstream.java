package com.github.auties00.smalt.video.h263.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the decoding state of one sub-bitstream of the Continuous Presence Multipoint and Video Multiplex mode (ITU-T
 * H.263 C.4), the whole stream when the mode is off: the headers, reference pictures and timing of each layer of the
 * Temporal, SNR and Spatial Scalability mode (Annex O), and the picture being decoded.
 *
 * <p>An instance is not thread-safe.
 */
final class SubBitstream {
    /**
     * The most layers: the base layer 1 and enhancement layers 2 to 15.
     */
    static final int LAYERS = 16;

    /**
     * The pictures the Reference Picture Selection mode keeps for each layer.
     */
    private static final int RPS_PICTURES = 16;

    /**
     * The sub-bitstream number.
     */
    final int number;

    /**
     * The picture pool.
     */
    private final PicturePool pool;

    /**
     * Whether the last picture header set Continuous Presence Multipoint.
     */
    boolean cpm;

    /**
     * Whether a custom picture clock frequency is in effect.
     */
    boolean customClock;

    /**
     * The duration of the picture clock period in ticks of 1/1800000 second.
     */
    int periodTicks = 60060;

    /**
     * The header state of each layer.
     */
    private final LayerState[] states = new LayerState[LAYERS];

    /**
     * The most recent reference picture of each layer.
     */
    private final PlusPicture[] anchors = new PlusPicture[LAYERS];

    /**
     * The reference picture of each layer before the most recent one.
     */
    private final PlusPicture[] previousAnchors = new PlusPicture[LAYERS];

    /**
     * The multi-picture buffer of the Enhanced Reference Picture Selection mode of each layer.
     */
    private final ReferenceBuffer[] buffers = new ReferenceBuffer[LAYERS];

    /**
     * The pictures the Reference Picture Selection mode keeps for each layer, the most recent first.
     */
    @SuppressWarnings("unchecked")
    private final ArrayDeque<PlusPicture>[] stores = new ArrayDeque[LAYERS];

    /**
     * The picture being decoded, or {@code null}.
     */
    PictureDecoder decoding;

    /**
     * Whether the time of a picture was computed.
     */
    boolean timed;

    /**
     * The temporal reference of the last picture.
     */
    int lastReference;

    /**
     * Whether {@link #lastReference} has 10 bits.
     */
    boolean lastExtended;

    /**
     * The time of the last picture in ticks.
     */
    long lastTime;

    /**
     * The temporal reference of the last picture of each layer, or -1, which redundant pictures repeat.
     */
    final int[] lastLayerReference = new int[LAYERS];

    /**
     * The picture number of the last picture of each layer.
     */
    final int[] lastLayerNumber = new int[LAYERS];

    /**
     * The pictures decoded and not yet handed out, in decoding order.
     */
    final List<PlusPicture> pending = new ArrayList<>();

    /**
     * The time of the most recent reference picture handed to the output queue, or {@code Long.MIN_VALUE}.
     */
    long lastAnchorTime = Long.MIN_VALUE;

    /**
     * The time of the reference picture handed to the output queue before the most recent one, or
     * {@code Long.MIN_VALUE}.
     */
    long earlierAnchorTime = Long.MIN_VALUE;

    /**
     * Creates the state of a sub-bitstream.
     *
     * @param number the sub-bitstream number
     * @param pool the picture pool
     */
    SubBitstream(int number, PicturePool pool) {
        this.number = number;
        this.pool = pool;
        for (var i = 0; i < LAYERS; i++) {
            states[i] = new LayerState();
            buffers[i] = new ReferenceBuffer(pool::release);
            stores[i] = new ArrayDeque<>();
            lastLayerReference[i] = -1;
        }
    }

    /**
     * Returns the header state of a layer.
     *
     * @param layer the layer number, 1 to 15
     * @return the state
     */
    LayerState layer(int layer) {
        return states[layer];
    }

    /**
     * Returns the most recent reference picture of a layer.
     *
     * @param layer the layer number
     * @return the picture, or {@code null}
     */
    PlusPicture anchor(int layer) {
        return anchors[layer];
    }

    /**
     * Returns the reference picture of a layer before the most recent one.
     *
     * @param layer the layer number
     * @return the picture, or {@code null}
     */
    PlusPicture previousAnchor(int layer) {
        return previousAnchors[layer];
    }

    /**
     * Returns the multi-picture buffer of a layer.
     *
     * @param layer the layer number
     * @return the buffer
     */
    ReferenceBuffer buffer(int layer) {
        return buffers[layer];
    }

    /**
     * Returns the picture of a layer the Reference Picture Selection mode keeps with a temporal reference.
     *
     * @param layer the layer number
     * @param temporalReference the temporal reference, 10 bits
     * @return the picture, or {@code null}
     */
    PlusPicture stored(int layer, int temporalReference) {
        for (var picture : stores[layer]) {
            if ((picture.temporalReference & 1023) == temporalReference) {
                return picture;
            }
        }
        return null;
    }

    /**
     * Makes a decoded reference picture the most recent of its layer, and keeps it for the Reference Picture
     * Selection mode.
     *
     * @param picture the decoded I-, P-, EI- or EP-picture
     * @param keep whether the Reference Picture Selection mode keeps it
     */
    void addAnchor(PlusPicture picture, boolean keep) {
        var layer = picture.layer;
        pool.release(previousAnchors[layer]);
        previousAnchors[layer] = anchors[layer];
        anchors[layer] = picture;
        pool.retain(picture);
        if (keep) {
            var store = stores[layer];
            store.addFirst(picture);
            pool.retain(picture);
            while (store.size() > RPS_PICTURES) {
                pool.release(store.removeLast());
            }
        }
    }

    /**
     * Forgets every reference picture and the header state, as after the end of the sub-bitstream; the time of the
     * next picture continues from the last.
     */
    void reset() {
        for (var i = 0; i < LAYERS; i++) {
            pool.release(anchors[i]);
            pool.release(previousAnchors[i]);
            anchors[i] = null;
            previousAnchors[i] = null;
            buffers[i].clear();
            buffers[i] = new ReferenceBuffer(pool::release);
            for (var picture : stores[i]) {
                pool.release(picture);
            }
            stores[i].clear();
            states[i] = new LayerState();
            lastLayerReference[i] = -1;
        }
        customClock = false;
        periodTicks = 60060;
    }

    /**
     * Computes the time of a picture from its temporal reference (5.1.2): a picture other than a B-picture follows
     * the last such picture by the forward difference of their references, measured in the picture clock period
     * before the header (5.1.7); a B-picture, which precedes the last reference picture in display order, lies the
     * signed difference of the references away from it.
     *
     * @param temporalReference the temporal reference
     * @param extended whether the reference has 10 bits
     * @param period the picture clock period the difference is measured in
     * @param bPicture whether the picture is a B-picture
     * @return the time in ticks of 1/1800000 second
     */
    long time(int temporalReference, boolean extended, int period, boolean bPicture) {
        if (!timed) {
            timed = true;
            lastReference = temporalReference;
            lastExtended = extended;
            lastTime = 0;
            return 0;
        }
        var difference = difference(lastReference, lastExtended, temporalReference, extended);
        if (bPicture) {
            var modulus = lastExtended && extended ? 1024 : 256;
            if (difference > modulus / 2) {
                difference -= modulus;
            }
            return lastTime + (long) difference * period;
        }
        lastTime += (long) difference * period;
        lastReference = temporalReference;
        lastExtended = extended;
        return lastTime;
    }

    /**
     * Returns the forward difference of two temporal references, modulo 1024 when both have 10 bits and 256
     * otherwise.
     *
     * @param from the earlier reference
     * @param fromExtended whether it has 10 bits
     * @param to the later reference
     * @param toExtended whether it has 10 bits
     * @return the difference, 0 or more
     */
    private static int difference(int from, boolean fromExtended, int to, boolean toExtended) {
        var modulus = fromExtended && toExtended ? 1024 : 256;
        return (to - from) & (modulus - 1);
    }
}

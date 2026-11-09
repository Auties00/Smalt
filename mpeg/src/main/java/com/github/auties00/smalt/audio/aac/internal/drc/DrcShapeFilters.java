/* Ports the ISO/IEC 23003-4 reference software shape filters (uniDrcShapeFilter.c). Copyright Apple Inc.,
   Fraunhofer IIS and ISO/IEC 2014; ISO/IEC reference software licence (use in products conforming to
   ISO/IEC 23003-4). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.drc;

import com.github.auties00.smalt.MediaException;

import static com.github.auties00.smalt.audio.aac.internal.drc.DrcModel.*;

/**
 * Applies the block of shape filters of a channel group, which adapt to the DRC gain to tilt the spectrum of the
 * compressed signal (ISO/IEC 23003-4 clause 6.4.7).
 *
 * <p>Instances are mutable and not thread-safe.
 */
final class DrcShapeFilters {
    private static final class ShapeFilter {
        int type;
        float gainOffset;
        float y1Bound;
        float warpedGainMax;
        float factor;
        float coeffSum;
        float partialCoeffSum;
        float gNorm;
        float a1;
        float a2;
        float b1;
        float b2;
        final float[] audioInState1 = new float[CHANNEL_COUNT_MAX];
        final float[] audioInState2 = new float[CHANNEL_COUNT_MAX];
        final float[] audioOutState1 = new float[CHANNEL_COUNT_MAX];
        final float[] audioOutState2 = new float[CHANNEL_COUNT_MAX];

        void init(ShapeFilterParams params, int shapeFilterType) {
            type = shapeFilterType;
            var corner = params.cornerFreqIndex;
            var strength = params.filterStrengthIndex;
            float x1;
            var x2 = 0.0f;
            if (shapeFilterType == SHAPE_FILTER_TYPE_LF_CUT || shapeFilterType == SHAPE_FILTER_TYPE_LF_BOOST) {
                if (corner >= DrcTables.SHAPE_RADIUS_LF.length || strength >= DrcTables.SHAPE_GAIN_OFFSET_LF[0].length) {
                    throw new MediaException.Malformed("MPEG-D DRC shape filter parameters are out of range");
                }
                gainOffset = DrcTables.SHAPE_GAIN_OFFSET_LF[corner][strength];
                y1Bound = DrcTables.SHAPE_Y1_BOUND_LF[corner][strength];
                x1 = -DrcTables.SHAPE_RADIUS_LF[corner];
            } else {
                if (corner >= DrcTables.SHAPE_RADIUS_HF.length || strength >= DrcTables.SHAPE_GAIN_OFFSET_HF[0].length) {
                    throw new MediaException.Malformed("MPEG-D DRC shape filter parameters are out of range");
                }
                gainOffset = DrcTables.SHAPE_GAIN_OFFSET_HF[corner][strength];
                y1Bound = DrcTables.SHAPE_Y1_BOUND_HF[corner][strength];
                var radius = DrcTables.SHAPE_RADIUS_HF[corner];
                x1 = (float) (-2.0f * radius * StrictMath.cos(2.0f * Math.PI * DrcTables.SHAPE_CUTOFF_HF[corner]));
                x2 = radius * radius;
            }
            warpedGainMax = SHAPE_FILTER_DRC_GAIN_MAX_MINUS_ONE / (SHAPE_FILTER_DRC_GAIN_MAX_MINUS_ONE + gainOffset);
            factor = (y1Bound - x1) / warpedGainMax;
            switch (shapeFilterType) {
                case SHAPE_FILTER_TYPE_HF_CUT -> {
                    coeffSum = 1.0f + x1 + x2;
                    partialCoeffSum = 1.0f + x2;
                    a1 = x1;
                    a2 = x2;
                    b2 = x2;
                }
                case SHAPE_FILTER_TYPE_LF_CUT -> a1 = x1;
                case SHAPE_FILTER_TYPE_HF_BOOST -> {
                    coeffSum = 1.0f + x1 + x2;
                    partialCoeffSum = 1.0f + x2;
                    b1 = x1;
                    b2 = x2;
                    a2 = x2;
                }
                default -> b1 = x1;
            }
        }

        void adapt(float drcGain) {
            float warpedGain;
            float x1;
            switch (type) {
                case SHAPE_FILTER_TYPE_LF_CUT, SHAPE_FILTER_TYPE_HF_CUT -> {
                    warpedGain = drcGain < 1.0f ? -1.0f : (drcGain - 1.0f) / (drcGain - 1.0f + gainOffset);
                    x1 = a1;
                }
                case SHAPE_FILTER_TYPE_LF_BOOST, SHAPE_FILTER_TYPE_HF_BOOST -> {
                    warpedGain = drcGain >= 1.0f ? -1.0f : (1.0f - drcGain) / (1.0f + drcGain * (gainOffset - 1.0f));
                    x1 = b1;
                }
                default -> {
                    return;
                }
            }
            float y1;
            if (warpedGain <= 0.0f) {
                y1 = x1;
            } else if (warpedGain < warpedGainMax) {
                y1 = x1 + factor * warpedGain;
            } else {
                y1 = y1Bound;
            }
            switch (type) {
                case SHAPE_FILTER_TYPE_HF_CUT -> {
                    gNorm = coeffSum / (partialCoeffSum + y1);
                    b1 = y1;
                }
                case SHAPE_FILTER_TYPE_LF_CUT -> b1 = y1;
                case SHAPE_FILTER_TYPE_HF_BOOST -> {
                    gNorm = (partialCoeffSum + y1) / coeffSum;
                    a1 = y1;
                }
                default -> a1 = y1;
            }
        }

        float process(int channel, float audioIn) {
            switch (type) {
                case SHAPE_FILTER_TYPE_LF_CUT, SHAPE_FILTER_TYPE_LF_BOOST -> {
                    var out = audioIn + b1 * audioInState1[channel] - a1 * audioOutState1[channel];
                    audioInState1[channel] = audioIn;
                    audioOutState1[channel] = out;
                    return out;
                }
                case SHAPE_FILTER_TYPE_HF_CUT, SHAPE_FILTER_TYPE_HF_BOOST -> {
                    var out = gNorm * audioIn + b1 * audioInState1[channel] + b2 * audioInState2[channel]
                            - a1 * audioOutState1[channel] - a2 * audioOutState2[channel];
                    audioInState2[channel] = audioInState1[channel];
                    audioInState1[channel] = gNorm * audioIn;
                    audioOutState2[channel] = audioOutState1[channel];
                    audioOutState1[channel] = out;
                    return out;
                }
                default -> {
                    return audioIn;
                }
            }
        }
    }

    private final ShapeFilter[] filters = {new ShapeFilter(), new ShapeFilter(), new ShapeFilter(), new ShapeFilter()};
    private boolean present;
    private float drcGainLast;

    /**
     * {@return whether the block holds shape filters}
     */
    boolean present() {
        return present;
    }

    /**
     * {@return the DRC gain the filters were last adapted to}
     */
    float drcGainLast() {
        return drcGainLast;
    }

    /**
     * Configures the block ({@code initShapeFilterBlock}) and resets its state.
     *
     * @param params the shape filters of the block
     */
    void init(ShapeFilterBlockParams params) {
        initFilter(0, params.lfCutFilterPresent, params.lfCutParams, SHAPE_FILTER_TYPE_LF_CUT);
        initFilter(1, params.lfBoostFilterPresent, params.lfBoostParams, SHAPE_FILTER_TYPE_LF_BOOST);
        initFilter(2, params.hfCutFilterPresent, params.hfCutParams, SHAPE_FILTER_TYPE_HF_CUT);
        initFilter(3, params.hfBoostFilterPresent, params.hfBoostParams, SHAPE_FILTER_TYPE_HF_BOOST);
        drcGainLast = -1.0f;
        adapt(1.0f);
        for (var filter : filters) {
            java.util.Arrays.fill(filter.audioInState1, 0);
            java.util.Arrays.fill(filter.audioInState2, 0);
            java.util.Arrays.fill(filter.audioOutState1, 0);
            java.util.Arrays.fill(filter.audioOutState2, 0);
        }
        present = true;
    }

    /**
     * Disables the block.
     */
    void disable() {
        present = false;
    }

    private void initFilter(int index, int filterPresent, ShapeFilterParams params, int type) {
        if (filterPresent != 0) {
            filters[index].init(params, type);
        } else {
            filters[index].type = SHAPE_FILTER_TYPE_OFF;
        }
    }

    /**
     * Adapts the filters to a DRC gain ({@code adaptShapeFilterBlock}).
     *
     * @param drcGain the linear DRC gain
     */
    void adapt(float drcGain) {
        drcGainLast = drcGain;
        for (var filter : filters) {
            filter.adapt(drcGain);
        }
    }

    /**
     * Filters one sample of a band signal ({@code processShapeFilterBlock}).
     *
     * @param channel the index of the band signal
     * @param audioIn the sample
     * @return the filtered sample
     */
    float process(int channel, float audioIn) {
        if (!present) {
            return audioIn;
        }
        var value = audioIn;
        for (var filter : filters) {
            value = filter.process(channel, value);
        }
        return value;
    }
}

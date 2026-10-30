/* Ports the ISO/IEC 23003-4 reference software interface parser (uniDrcInterfaceParser.c) and the application of the
   interface to the selection process (uniDrcSelectionProcessInit_uniDrcInterfaceParams of
   uniDrcSelectionProcess_init.c). Copyright Fraunhofer IIS and ISO/IEC 2014; ISO/IEC reference software licence (use
   in products conforming to ISO/IEC 23003-4). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.drc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

/**
 * Reads the playback request of a {@code uniDrcInterface()} payload (ISO/IEC 23003-4 clause 7.6) into the parameters
 * of the selection process.
 */
final class DrcInterface {
    private static final int EXT_COUNT_MAX = 2;
    private static final int UNIDRCINTERFACEEXT_EQ = 1;

    /**
     * Prevents instantiation.
     */
    private DrcInterface() {
        throw new AssertionError();
    }

    /**
     * Applies a {@code uniDrcInterface()} payload to the selection parameters.
     *
     * @param reader the payload
     * @param params receives the requests the payload carries
     * @return whether the payload requests the peak limiter
     * @throws MediaException.Malformed if the payload is invalid
     */
    static boolean apply(AacBitReader reader, DrcSelection.Params params) {
        var peakLimiterPresent = false;
        if (bits(reader, 1) == 1) {
            bits(reader, 8);
            var length = bits(reader, 8) + 1;
            for (var i = 0; i < length; i++) {
                bits(reader, 8);
            }
        }
        if (bits(reader, 1) == 1) {
            params.targetConfigRequestType = bits(reader, 2);
            switch (params.targetConfigRequestType) {
                case 0 -> {
                    var count = bits(reader, 4);
                    if (count == 0) {
                        params.numDownmixIdRequests = 1;
                        params.downmixIdRequested[0] = 0;
                    } else {
                        params.numDownmixIdRequests = count;
                        for (var i = 0; i < count; i++) {
                            params.downmixIdRequested[i] = bits(reader, 7);
                        }
                    }
                }
                case 1 -> params.targetLayoutRequested = bits(reader, 8);
                case 2 -> params.targetChannelCountRequested = bits(reader, 7) + 1;
                default -> throw new MediaException.Malformed("MPEG-D DRC interface target request is reserved");
            }
        }
        if (bits(reader, 1) == 1) {
            params.loudnessNormalizationOn = bits(reader, 1);
            if (params.loudnessNormalizationOn == 1) {
                params.targetLoudness = -bits(reader, 12) * 0.03125f;
            }
        }
        if (bits(reader, 1) == 1) {
            params.albumMode = bits(reader, 1);
            params.peakLimiterPresent = bits(reader, 1);
            peakLimiterPresent = params.peakLimiterPresent == 1;
            if (bits(reader, 1) == 1) {
                params.loudnessDeviationMax = bits(reader, 6);
            }
            if (bits(reader, 1) == 1) {
                params.loudnessMeasurementMethod = bits(reader, 3);
            }
            if (bits(reader, 1) == 1) {
                params.loudnessMeasurementSystem = bits(reader, 4);
            }
            if (bits(reader, 1) == 1) {
                params.loudnessMeasurementPreProc = bits(reader, 2);
            }
            if (bits(reader, 1) == 1) {
                params.deviceCutOffFrequency = Math.max(Math.min(bits(reader, 6) * 10, 500), 20);
            }
            if (bits(reader, 1) == 1) {
                var value = bits(reader, 6);
                params.loudnessNormalizationGainDbMax = value < 63 ? value * 0.5f
                        : DrcSelection.LOUDNESS_NORMALIZATION_GAIN_MAX_DEFAULT;
            }
            if (bits(reader, 1) == 1) {
                params.loudnessNormalizationGainModificationDb = -16 + bits(reader, 10) * 0.03125f;
            }
            if (bits(reader, 1) == 1) {
                params.outputPeakLevelMax = bits(reader, 6) * 0.5f;
            }
        }
        if (bits(reader, 1) == 1) {
            params.dynamicRangeControlOn = bits(reader, 1);
            if (params.dynamicRangeControlOn == 1) {
                params.numDrcFeatureRequests = bits(reader, 3);
                if (params.numDrcFeatureRequests > DrcSelection.MAX_NUM_DRC_FEATURE_REQUESTS) {
                    throw new MediaException.Malformed("MPEG-D DRC interface has too many feature requests");
                }
                for (var i = 0; i < params.numDrcFeatureRequests; i++) {
                    params.drcFeatureRequestType[i] = bits(reader, 2);
                    switch (params.drcFeatureRequestType[i]) {
                        case 0 -> {
                            params.numDrcEffectTypeRequests[i] = bits(reader, 4);
                            params.numDrcEffectTypeRequestsDesired[i] = bits(reader, 4);
                            for (var j = 0; j < params.numDrcEffectTypeRequests[i]; j++) {
                                params.drcEffectTypeRequest[i][j] = bits(reader, 4);
                            }
                        }
                        case 1 -> {
                            params.dynamicRangeMeasurementRequestType[i] = bits(reader, 2);
                            params.dynamicRangeRequestedIsRange[i] = bits(reader, 1);
                            if (params.dynamicRangeRequestedIsRange[i] == 0) {
                                params.dynamicRangeRequestValue[i] = dynamicRangeValue(bits(reader, 8));
                            } else {
                                params.dynamicRangeRequestValueMin[i] = dynamicRangeValue(bits(reader, 8));
                                params.dynamicRangeRequestValueMax[i] = dynamicRangeValue(bits(reader, 8));
                            }
                        }
                        case 2 -> params.drcCharacteristicRequest[i] = bits(reader, 7);
                        default -> throw new MediaException.Malformed("MPEG-D DRC interface feature request is "
                                + "reserved");
                    }
                }
            } else {
                params.numDrcFeatureRequests = 0;
            }
        }
        if (bits(reader, 1) == 1) {
            var changeCompress = bits(reader, 1);
            var changeBoost = bits(reader, 1);
            if (changeCompress == 1) {
                var value = bits(reader, 8);
                params.compress = value < 255 ? 1 - value * 0.00390625f : 0.0f;
            }
            if (changeBoost == 1) {
                var value = bits(reader, 8);
                params.boost = value < 255 ? 1 - value * 0.00390625f : 0.0f;
            }
            if (bits(reader, 1) == 1) {
                params.drcCharacteristicTarget = bits(reader, 8);
            }
        }
        if (bits(reader, 1) == 1) {
            var count = 0;
            var type = bits(reader, 4);
            while (type != 0) {
                var extSizeBits = bits(reader, 4) + 4;
                var extBitSize = bits(reader, extSizeBits) + 1;
                if (type == UNIDRCINTERFACEEXT_EQ) {
                    if (bits(reader, 1) == 1) {
                        var requestPresent = bits(reader, 1);
                        var request = requestPresent == 1 ? bits(reader, 2) : 0;
                        var sensitivity = bits(reader, 1) == 1 ? bits(reader, 7) + 23.0f : 0.0f;
                        var playbackGain = bits(reader, 1) == 1 ? (float) -bits(reader, 7) : 0.0f;
                        if (requestPresent == 1) {
                            params.loudnessEqRequest = request;
                            params.sensitivity = sensitivity;
                            params.playbackGain = playbackGain;
                        }
                    }
                    if (bits(reader, 1) == 1) {
                        params.eqSetPurposeRequest = bits(reader, 16);
                    }
                } else {
                    for (var remaining = extBitSize; remaining > 0; remaining -= Math.min(remaining, 16)) {
                        bits(reader, Math.min(remaining, 16));
                    }
                }
                if (++count == EXT_COUNT_MAX) {
                    throw new MediaException.Malformed("MPEG-D DRC interface has too many extensions");
                }
                type = bits(reader, 4);
            }
        }
        return peakLimiterPresent;
    }

    private static float dynamicRangeValue(int code) {
        if (code == 0) {
            return 0.0f;
        }
        if (code <= 128) {
            return code * 0.25f;
        }
        if (code <= 204) {
            return 0.5f * code - 32.0f;
        }
        return code - 134.0f;
    }

    private static int bits(AacBitReader reader, int count) {
        if (count > 0 && reader.bitsLeft() <= 0) {
            throw new MediaException.Malformed("MPEG-D DRC interface payload is truncated");
        }
        return DrcParser.bits(reader, count);
    }
}

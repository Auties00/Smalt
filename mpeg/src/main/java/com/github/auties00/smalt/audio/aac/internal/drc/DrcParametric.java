/* Ports the time domain part of the ISO/IEC 23003-4 reference software parametric DRC (parametricDrcDecoder.c).
   Copyright Apple Inc., Fraunhofer IIS and ISO/IEC 2014; ISO/IEC reference software licence (use in products
   conforming to ISO/IEC 23003-4). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.drc;

import com.github.auties00.smalt.MediaException;

import static com.github.auties00.smalt.audio.aac.internal.drc.DrcModel.*;

/**
 * Computes the gains of the parametric DRC sets of Amendment 1 from the audio itself: the feed-forward compressor
 * with its K-weighted level estimation, and the peak limiter (ISO/IEC 23003-4 clause 6.4.9).
 *
 * <p>Instances are mutable and not thread-safe.
 */
final class DrcParametric {
    private static final float PI = 3.14159265f;

    /**
     * Holds the state of one feed-forward compressor ({@code PARAMETRIC_DRC_TYPE_FF_PARAMS}).
     */
    private static final class FeedForward {
        int audioChannelCount;
        int frameSize;
        int levelEstimIntegrationTime;
        int levelEstimFrameIndex;
        int levelEstimFrameCount;
        final float[] level = new float[PARAM_DRC_TYPE_FF_LEVEL_ESTIM_FRAME_COUNT_MAX];
        boolean startUpPhase;
        final float[] levelEstimChannelWeight = new float[CHANNEL_COUNT_MAX];
        int levelEstimKWeightingType;
        final float[] preFilter = new float[5];
        final float[] rlbFilter = new float[5];
        final float[] preState = new float[2 * CHANNEL_COUNT_MAX];
        final float[] rlbState = new float[2 * CHANNEL_COUNT_MAX];
        float referenceLevelParametricDrc;
        int nodeCount;
        final int[] nodeLevel = new int[PARAM_DRC_TYPE_FF_NODE_COUNT_MAX];
        final int[] nodeGain = new int[PARAM_DRC_TYPE_FF_NODE_COUNT_MAX];
        float gainSmoothAttackAlphaSlow;
        float gainSmoothReleaseAlphaSlow;
        float gainSmoothAttackAlphaFast;
        float gainSmoothReleaseAlphaFast;
        int gainSmoothAttackThreshold;
        int gainSmoothReleaseThreshold;
        int gainSmoothHoldOffCount;
        float levelSmoothDb;
        float gainSmoothDb;
        int holdCounter;

        void reset() {
            levelEstimFrameIndex = 0;
            startUpPhase = true;
            java.util.Arrays.fill(level, 0);
            java.util.Arrays.fill(preState, 0);
            java.util.Arrays.fill(rlbState, 0);
            levelSmoothDb = -135.0f;
            gainSmoothDb = 0.0f;
            holdCounter = 0;
        }
    }

    /**
     * Holds the state of one parametric peak limiter ({@code PARAMETRIC_DRC_TYPE_LIM_PARAMS}).
     */
    private static final class Limiter {
        int frameSize;
        final float[] levelEstimChannelWeight = new float[CHANNEL_COUNT_MAX];
        int attack;
        float attackConst;
        float releaseConst;
        float threshold;
        int channels;
        float cor;
        float[] maxBuf;
        float[] maxBufSlow;
        int maxBufIdx;
        int maxBufSlowIdx;
        int secLen;
        int nMaxBufSec;
        int maxBufSecIdx;
        int maxBufSecCtr;
        double smoothState0;

        void reset() {
            maxBufIdx = 0;
            maxBufSlowIdx = 0;
            maxBufSecIdx = 0;
            maxBufSecCtr = 0;
            cor = 1.0f;
            smoothState0 = 1.0;
            java.util.Arrays.fill(maxBuf, 0);
            java.util.Arrays.fill(maxBufSlow, 0);
        }
    }

    /**
     * Holds one parametric DRC instance ({@code PARAMETRIC_DRC_INSTANCE_PARAMS}).
     */
    static final class Instance {
        int disableParamtricDrc;
        int parametricDrcType;
        final SplineNodes splineNodes = new SplineNodes();
        FeedForward feedForward;
        Limiter limiter;
    }

    final int sampleRate;
    final int drcFrameSize;
    int audioChannelCount;
    int nNodes;
    int drcFrameSizeParametricDrc;
    int parametricDrcInstanceCount;
    final int[] parametricDrcIndex = new int[PARAM_DRC_INSTANCE_COUNT_MAX];
    final int[] gainSetIndex = new int[PARAM_DRC_INSTANCE_COUNT_MAX];
    final int[] downmixIdFromDrcInstructions = new int[PARAM_DRC_INSTANCE_COUNT_MAX];
    final int[][] channelMap = new int[PARAM_DRC_INSTANCE_COUNT_MAX][CHANNEL_COUNT_MAX];
    final Instance[] instances = array(PARAM_DRC_INSTANCE_COUNT_MAX, Instance.class, Instance::new);

    /**
     * Constructs the parametric DRC of a gain decoder ({@code initParametricDrc}).
     *
     * @param drcFrameSize the number of samples per DRC frame
     * @param sampleRate the sampling rate
     */
    DrcParametric(int drcFrameSize, int sampleRate) {
        this.drcFrameSize = drcFrameSize;
        this.sampleRate = sampleRate;
    }

    /**
     * Adds an instance of a channel group of a selected DRC set, before {@link #initAfterConfig}.
     *
     * @throws MediaException.Malformed if the DRC sets use too many instances
     */
    int addInstance() {
        if (parametricDrcInstanceCount >= PARAM_DRC_INSTANCE_COUNT_MAX) {
            throw new MediaException.Malformed("MPEG-D DRC uses too many parametric DRC instances");
        }
        return parametricDrcInstanceCount++;
    }

    /**
     * Initializes the instances once the DRC sets are selected ({@code initParametricDrcAfterConfig}).
     *
     * @param config the configuration
     * @param loudnessInfoSet the loudness information, which provides the input loudness of the compressors
     * @throws MediaException.Malformed if an instance cannot be configured
     */
    void initAfterConfig(UniDrcConfig config, LoudnessInfoSet loudnessInfoSet) {
        var coefficients = config.uniDrcConfigExt.drcCoefficientsParametricDrc;
        drcFrameSizeParametricDrc = coefficients.parametricDrcFrameSize;
        if (drcFrameSizeParametricDrc <= 0) {
            throw new MediaException.Malformed("MPEG-D DRC parametric frame size is invalid");
        }
        nNodes = drcFrameSize / drcFrameSizeParametricDrc;
        for (var instanceIndex = 0; instanceIndex < parametricDrcInstanceCount; instanceIndex++) {
            var setIndex = gainSetIndex[instanceIndex];
            var set = coefficients.parametricDrcGainSetParams[setIndex];
            var channelCountFromDownmixId = set.sideChainConfigType == 1
                    && set.downmixId == downmixIdFromDrcInstructions[instanceIndex] ? set.channelCountFromDownmixId : 0;
            if (set.drcInputLoudnessPresent == 0) {
                var found = findInputLoudness(loudnessInfoSet, downmixIdFromDrcInstructions[instanceIndex]);
                if (Float.isNaN(found)) {
                    found = findInputLoudness(loudnessInfoSet, 0);
                }
                if (Float.isNaN(found)) {
                    throw new MediaException.Malformed("MPEG-D DRC parametric DRC has no input loudness");
                }
                set.drcInputLoudness = found;
            }
            initInstance(config, instanceIndex, channelCountFromDownmixId);
        }
    }

    private static float findInputLoudness(LoudnessInfoSet loudnessInfoSet, int downmixId) {
        var value = Float.NaN;
        var found = false;
        for (var n = 0; n < loudnessInfoSet.loudnessInfoCount; n++) {
            var info = loudnessInfoSet.loudnessInfo[n];
            if (info.downmixId != downmixId || info.drcSetId != 0) {
                continue;
            }
            for (var m = 0; m < info.measurementCount; m++) {
                if (info.loudnessMeasure[m].methodDefinition == METHOD_DEFINITION_PROGRAM_LOUDNESS) {
                    value = info.loudnessMeasure[m].methodValue;
                    found = true;
                    break;
                }
            }
            if (!found) {
                for (var m = 0; m < info.measurementCount; m++) {
                    if (info.loudnessMeasure[m].methodDefinition == METHOD_DEFINITION_ANCHOR_LOUDNESS) {
                        value = info.loudnessMeasure[m].methodValue;
                        found = true;
                        break;
                    }
                }
            }
        }
        return value;
    }

    private void initInstance(UniDrcConfig config, int instanceIndex, int channelCountFromDownmixId) {
        var instructions = config.uniDrcConfigExt.parametricDrcInstructions[parametricDrcIndex[instanceIndex]];
        var instance = instances[instanceIndex];
        instance.disableParamtricDrc = instructions.disableParamtricDrc;
        instance.parametricDrcType = instructions.parametricDrcType;
        instance.splineNodes.nNodes = nNodes;
        if (instance.disableParamtricDrc != 0) {
            return;
        }
        if (instance.parametricDrcType == PARAM_DRC_TYPE_FF) {
            initFeedForward(config, instanceIndex, channelCountFromDownmixId);
        } else if (instance.parametricDrcType == PARAM_DRC_TYPE_LIM) {
            instance.splineNodes.nNodes = drcFrameSize;
            initLimiter(config, instanceIndex, channelCountFromDownmixId);
        } else {
            throw new MediaException.Malformed("MPEG-D DRC parametric DRC type is reserved");
        }
    }

    private void channelWeights(UniDrcConfig config, int instanceIndex, int channelCountFromDownmixId,
                                int channelCount, float[] weights) {
        var set = config.uniDrcConfigExt.drcCoefficientsParametricDrc.parametricDrcGainSetParams[gainSetIndex[instanceIndex]];
        if (channelCountFromDownmixId != 0) {
            System.arraycopy(set.levelEstimChannelWeight, 0, weights, 0, channelCountFromDownmixId);
        } else {
            for (var i = 0; i < channelCount; i++) {
                weights[i] = channelMap[instanceIndex][i];
            }
        }
    }

    private void initFeedForward(UniDrcConfig config, int instanceIndex, int channelCountFromDownmixId) {
        var coefficients = config.uniDrcConfigExt.drcCoefficientsParametricDrc;
        var bs = config.uniDrcConfigExt.parametricDrcInstructions[parametricDrcIndex[instanceIndex]]
                .parametricDrcTypeFeedForward;
        var ff = new FeedForward();
        instances[instanceIndex].feedForward = ff;
        ff.frameSize = drcFrameSizeParametricDrc;
        ff.audioChannelCount = audioChannelCount;
        ff.levelEstimKWeightingType = bs.levelEstimKWeightingType;
        ff.levelEstimIntegrationTime = bs.levelEstimIntegrationTime;
        ff.levelEstimFrameCount = bs.levelEstimIntegrationTime / ff.frameSize;
        if (ff.levelEstimFrameCount > PARAM_DRC_TYPE_FF_LEVEL_ESTIM_FRAME_COUNT_MAX) {
            throw new MediaException.Malformed("MPEG-D DRC parametric integration time is too long");
        }
        channelWeights(config, instanceIndex, channelCountFromDownmixId, ff.audioChannelCount,
                ff.levelEstimChannelWeight);
        initLevelEstimationFilters(ff.levelEstimKWeightingType, sampleRate, ff.preFilter, ff.rlbFilter);
        ff.nodeCount = bs.nodeCount;
        System.arraycopy(bs.nodeLevel, 0, ff.nodeLevel, 0, ff.nodeCount);
        System.arraycopy(bs.nodeGain, 0, ff.nodeGain, 0, ff.nodeCount);
        ff.referenceLevelParametricDrc = coefficients.parametricDrcGainSetParams[gainSetIndex[instanceIndex]]
                .drcInputLoudness;
        var frameSize = drcFrameSizeParametricDrc;
        ff.gainSmoothAttackAlphaFast = alpha(frameSize, bs.gainSmoothAttackTimeFast);
        ff.gainSmoothReleaseAlphaFast = alpha(frameSize, bs.gainSmoothReleaseTimeFast);
        ff.gainSmoothAttackAlphaSlow = alpha(frameSize, bs.gainSmoothAttackTimeSlow);
        ff.gainSmoothReleaseAlphaSlow = alpha(frameSize, bs.gainSmoothReleaseTimeSlow);
        ff.gainSmoothHoldOffCount = bs.gainSmoothHoldOff * 256 * sampleRate / (frameSize * 48000);
        ff.gainSmoothAttackThreshold = bs.gainSmoothAttackThreshold;
        ff.gainSmoothReleaseThreshold = bs.gainSmoothReleaseThreshold;
        ff.reset();
    }

    private float alpha(int frameSize, int timeMs) {
        return 1 - (float) StrictMath.exp(-1.0 * frameSize / (timeMs * sampleRate * 0.001));
    }

    private static void initLevelEstimationFilters(int kWeightingType, int sampleRate, float[] pre, float[] rlb) {
        if (kWeightingType == 2) {
            var w0 = 2.0f * PI * 1500.0f / (float) sampleRate;
            var sinw0 = (float) StrictMath.sin(w0);
            var cosw0 = (float) StrictMath.cos(w0);
            var a = 1.25892541f;
            var sqrtA = (float) StrictMath.sqrt(a);
            var alpha = (float) (sinw0 * 0.5 * StrictMath.sqrt(2));
            var b0 = a * ((a + 1) + (a - 1) * cosw0 + 2 * sqrtA * alpha);
            var b1 = -2 * a * ((a - 1) + (a + 1) * cosw0);
            var b2 = a * ((a + 1) + (a - 1) * cosw0 - 2 * sqrtA * alpha);
            var a0 = (a + 1) - (a - 1) * cosw0 + 2 * sqrtA * alpha;
            var a1 = 2 * ((a - 1) - (a + 1) * cosw0);
            var a2 = (a + 1) - (a - 1) * cosw0 - 2 * sqrtA * alpha;
            pre[0] = b0 / a0;
            pre[1] = b1 / a0;
            pre[2] = b2 / a0;
            pre[3] = a1 / a0;
            pre[4] = a2 / a0;
        }
        if (kWeightingType == 1 || kWeightingType == 2) {
            var w0 = 2.0f * PI * 38.0f / (float) sampleRate;
            var sinw0 = (float) StrictMath.sin(w0);
            var cosw0 = (float) StrictMath.cos(w0);
            var alpha = sinw0;
            var b0 = (1 + cosw0) / 2;
            var b1 = -(1 + cosw0);
            var b2 = (1 + cosw0) / 2;
            var a0 = 1 + alpha;
            var a1 = -2 * cosw0;
            var a2 = 1 - alpha;
            rlb[0] = b0 / a0;
            rlb[1] = b1 / a0;
            rlb[2] = b2 / a0;
            rlb[3] = a1 / a0;
            rlb[4] = a2 / a0;
        }
    }

    private void initLimiter(UniDrcConfig config, int instanceIndex, int channelCountFromDownmixId) {
        var bs = config.uniDrcConfigExt.parametricDrcInstructions[parametricDrcIndex[instanceIndex]].parametricDrcTypeLim;
        var lim = new Limiter();
        instances[instanceIndex].limiter = lim;
        lim.frameSize = drcFrameSize;
        lim.channels = audioChannelCount;
        channelWeights(config, instanceIndex, channelCountFromDownmixId, audioChannelCount, lim.levelEstimChannelWeight);
        var attack = bs.parametricLimAttack * sampleRate / 1000;
        var secLen = (int) StrictMath.sqrt(attack + 1);
        if (secLen < 1) {
            throw new MediaException.Malformed("MPEG-D DRC parametric limiter attack is too short");
        }
        lim.secLen = secLen;
        lim.nMaxBufSec = (attack + 1) / secLen;
        if (lim.nMaxBufSec * secLen < attack + 1) {
            lim.nMaxBufSec++;
        }
        lim.maxBuf = new float[lim.nMaxBufSec * secLen];
        lim.maxBufSlow = new float[lim.nMaxBufSec];
        lim.attack = attack;
        lim.attackConst = (float) StrictMath.pow(0.1, 1.0 / (attack + 1));
        lim.releaseConst = (float) StrictMath.pow(0.1, 1.0 / (bs.parametricLimRelease * sampleRate / 1000 + 1));
        lim.threshold = (float) StrictMath.pow(10.0f, 0.05f * bs.parametricLimThreshold);
        lim.reset();
    }

    /**
     * Computes the gain nodes of a feed-forward instance for one DRC frame ({@code processParametricDrcInstance}).
     *
     * @param audio the channels of the frame, before the DRC delay
     * @param base the index of the first sample of the frame in {@code audio}
     * @param instance the parametric DRC instance
     */
    void processInstance(float[][] audio, int base, Instance instance) {
        if (instance.disableParamtricDrc != 0) {
            for (var i = 0; i < nNodes; i++) {
                var node = instance.splineNodes.node[i];
                node.gainDb = 0.0f;
                node.slope = 0.0f;
                node.time = (i + 1) * drcFrameSizeParametricDrc - 1;
            }
            return;
        }
        if (instance.parametricDrcType != PARAM_DRC_TYPE_FF) {
            throw new MediaException.Malformed("MPEG-D DRC parametric limiter has no gain nodes");
        }
        for (var i = 0; i < nNodes; i++) {
            processFeedForward(audio, base, i, instance.feedForward, instance.splineNodes);
        }
    }

    private static void processFeedForward(float[][] audio, int base, int nodeIdx, FeedForward ff,
                                           SplineNodes splineNodes) {
        var frameSize = ff.frameSize;
        var offset = nodeIdx * frameSize;
        var level = 0.0f;
        var pre = ff.preFilter;
        var rlb = ff.rlbFilter;
        for (var c = 0; c < ff.audioChannelCount; c++) {
            if (ff.levelEstimChannelWeight[c] == 0) {
                continue;
            }
            var channelLevel = 0.0f;
            var input = audio[c];
            switch (ff.levelEstimKWeightingType) {
                case 0 -> {
                    for (var t = 0; t < frameSize; t++) {
                        var x = input[base + offset + t];
                        channelLevel += x * x;
                    }
                }
                case 1, 2 -> {
                    var usePre = ff.levelEstimKWeightingType == 2;
                    for (var t = 0; t < frameSize; t++) {
                        var x = input[base + offset + t];
                        if (usePre) {
                            var z0 = x - pre[3] * ff.preState[2 * c] - pre[4] * ff.preState[2 * c + 1];
                            x = pre[0] * z0 + pre[1] * ff.preState[2 * c] + pre[2] * ff.preState[2 * c + 1];
                            ff.preState[2 * c + 1] = ff.preState[2 * c];
                            ff.preState[2 * c] = z0;
                        }
                        var z0 = x - rlb[3] * ff.rlbState[2 * c] - rlb[4] * ff.rlbState[2 * c + 1];
                        x = rlb[0] * z0 + rlb[1] * ff.rlbState[2 * c] + rlb[2] * ff.rlbState[2 * c + 1];
                        ff.rlbState[2 * c + 1] = ff.rlbState[2 * c];
                        ff.rlbState[2 * c] = z0;
                        channelLevel += x * x;
                    }
                }
                default -> throw new MediaException.Malformed("MPEG-D DRC K-weighting type is reserved");
            }
            level += ff.levelEstimChannelWeight[c] * channelLevel;
        }
        ff.level[ff.levelEstimFrameIndex] = level;
        ff.levelEstimFrameIndex++;
        level = 0.0f;
        if (ff.startUpPhase) {
            for (var i = 0; i < ff.levelEstimFrameIndex; i++) {
                level += ff.level[i];
            }
            level /= ff.levelEstimFrameIndex * ff.frameSize;
        } else {
            for (var i = 0; i < ff.levelEstimFrameCount; i++) {
                level += ff.level[i];
            }
            level /= ff.levelEstimIntegrationTime;
        }
        if (ff.levelEstimFrameIndex == ff.levelEstimFrameCount) {
            ff.levelEstimFrameIndex = 0;
            ff.startUpPhase = false;
        }
        if (level < 1e-10f) {
            level = 1e-10f;
        }
        float levelDb;
        if (ff.levelEstimKWeightingType == 2) {
            levelDb = -0.691f + 10 * (float) StrictMath.log10(level) + 3;
        } else {
            levelDb = 10 * (float) StrictMath.log10(level) + 3;
        }
        levelDb -= ff.referenceLevelParametricDrc;
        var n = 0;
        for (; n < ff.nodeCount; n++) {
            if (levelDb <= (float) ff.nodeLevel[n]) {
                break;
            }
        }
        float gainDb;
        if (n == 0) {
            gainDb = ff.nodeGain[n];
        } else if (n == ff.nodeCount) {
            gainDb = (float) ff.nodeGain[n - 1] - levelDb + (float) ff.nodeLevel[n - 1];
        } else {
            gainDb = (float) ff.nodeGain[n] + (levelDb - (float) ff.nodeLevel[n])
                    / (float) (ff.nodeLevel[n - 1] - ff.nodeLevel[n]) * (float) (ff.nodeGain[n - 1] - ff.nodeGain[n]);
        }
        var levelDelta = levelDb - ff.levelSmoothDb;
        float alpha;
        if (gainDb < ff.gainSmoothDb) {
            alpha = levelDelta > ff.gainSmoothAttackThreshold ? ff.gainSmoothAttackAlphaFast
                    : ff.gainSmoothAttackAlphaSlow;
        } else {
            alpha = levelDelta < -ff.gainSmoothReleaseThreshold ? ff.gainSmoothReleaseAlphaFast
                    : ff.gainSmoothReleaseAlphaSlow;
        }
        if (gainDb < ff.gainSmoothDb || ff.holdCounter == 0) {
            ff.levelSmoothDb = (1 - alpha) * ff.levelSmoothDb + alpha * levelDb;
            ff.gainSmoothDb = (1 - alpha) * ff.gainSmoothDb + alpha * gainDb;
        }
        if (ff.holdCounter != 0) {
            ff.holdCounter -= 1;
        }
        if (gainDb < ff.gainSmoothDb) {
            ff.holdCounter = ff.gainSmoothHoldOffCount;
        }
        var node = splineNodes.node[nodeIdx];
        node.gainDb = ff.gainSmoothDb;
        node.slope = 0.0f;
        node.time = ff.frameSize + offset - 1;
    }

    /**
     * Computes the per-sample gains of a parametric peak limiter for one DRC frame
     * ({@code processParametricDrcTypeLim}).
     *
     * @param audio the channels of the frame, before the DRC delay
     * @param base the index of the first sample of the frame in {@code audio}
     * @param loudnessNormalizationGainDb the loudness normalization gain applied after the DRC
     * @param instance the parametric DRC instance
     * @param gains receives the linear gains
     * @param offset the index of the first gain in {@code gains}
     */
    static void processLimiter(float[][] audio, int base, float loudnessNormalizationGainDb, Instance instance,
                               float[] gains, int offset) {
        var lim = instance.limiter;
        var loudnessNormalizationGain = (float) StrictMath.pow(10.0f, 0.05f * loudnessNormalizationGainDb);
        var channels = lim.channels;
        var attack = lim.attack;
        var attackConst = lim.attackConst;
        var releaseConst = lim.releaseConst;
        var threshold = lim.threshold;
        var weights = lim.levelEstimChannelWeight;
        var maxBuf = lim.maxBuf;
        var maxBufIdx = lim.maxBufIdx;
        var maxBufSlow = lim.maxBufSlow;
        var maxBufSlowIdx = lim.maxBufSlowIdx;
        var maxBufSecIdx = lim.maxBufSecIdx;
        var maxBufSecCtr = lim.maxBufSecCtr;
        var secLen = lim.secLen;
        var nMaxBufSec = lim.nMaxBufSec;
        var cor = lim.cor;
        var smoothState0 = lim.smoothState0;
        for (var i = 0; i < lim.frameSize; i++) {
            var tmp = threshold;
            for (var j = 0; j < channels; j++) {
                if (weights[j] == 0) {
                    continue;
                }
                tmp = Math.max(tmp, Math.abs(loudnessNormalizationGain * weights[j] * audio[j][base + i]));
            }
            maxBuf[maxBufIdx] = tmp;
            var sectionMaximum = maxBuf[maxBufSecIdx];
            for (var j = 1; j < secLen; j++) {
                if (maxBuf[maxBufSecIdx + j] > sectionMaximum) {
                    sectionMaximum = maxBuf[maxBufSecIdx + j];
                }
            }
            var maximum = sectionMaximum;
            for (var j = 0; j < nMaxBufSec; j++) {
                if (maxBufSlow[j] > maximum) {
                    maximum = maxBufSlow[j];
                }
            }
            maxBufIdx++;
            maxBufSecCtr++;
            if (maxBufSecCtr >= secLen || maxBufIdx >= attack + 1) {
                maxBufSecCtr = 0;
                maxBufSlow[maxBufSlowIdx] = sectionMaximum;
                maxBufSlowIdx++;
                if (maxBufSlowIdx >= nMaxBufSec) {
                    maxBufSlowIdx = 0;
                }
                maxBufSlow[maxBufSlowIdx] = 0.0f;
                maxBufSecIdx += secLen;
            }
            if (maxBufIdx >= attack + 1) {
                maxBufIdx = 0;
                maxBufSecIdx = 0;
            }
            var gain = maximum > threshold ? threshold / maximum : 1.0f;
            if (gain < smoothState0) {
                cor = Math.min(cor, (gain - 0.1f * (float) smoothState0) * 1.11111111f);
            } else {
                cor = gain;
            }
            if (cor < smoothState0) {
                smoothState0 = attackConst * (smoothState0 - cor) + cor;
                smoothState0 = Math.max(smoothState0, gain);
            } else {
                smoothState0 = releaseConst * (smoothState0 - cor) + cor;
            }
            gains[offset + i] = (float) smoothState0;
        }
        lim.maxBufIdx = maxBufIdx;
        lim.maxBufSecIdx = maxBufSecIdx;
        lim.maxBufSecCtr = maxBufSecCtr;
        lim.maxBufSlowIdx = maxBufSlowIdx;
        lim.cor = cor;
        lim.smoothState0 = smoothState0;
    }
}

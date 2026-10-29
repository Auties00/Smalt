/* Ports the ISO/IEC 23003-4 reference software DRC filter banks (uniDrcFilterBank.c). Copyright Apple Inc.,
   Fraunhofer IIS and ISO/IEC 2014; ISO/IEC reference software licence (use in products conforming to
   ISO/IEC 23003-4). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.drc;

import com.github.auties00.smalt.MediaException;

import static com.github.auties00.smalt.audio.aac.internal.drc.DrcModel.*;

/**
 * Splits the channels of a multi-band DRC set into the bands of their gain sets with Linkwitz-Riley crossovers, and
 * aligns the phase of the channel groups with all-pass filters (ISO/IEC 23003-4 clause 6.4.6).
 *
 * <p>Instances are mutable and not thread-safe.
 */
final class DrcFilterBanks {
    private static final float[][] FILTER_BANK_PARAMS = {
            {2.0f / 1024.0f, 0.0000373252f, 0.9913600345f},
            {3.0f / 1024.0f, 0.0000836207f, 0.9870680830f},
            {4.0f / 1024.0f, 0.0001480220f, 0.9827947083f},
            {5.0f / 1024.0f, 0.0002302960f, 0.9785398263f},
            {6.0f / 1024.0f, 0.0003302134f, 0.9743033527f},
            {2.0f / 256.0f, 0.0005820761f, 0.9658852897f},
            {3.0f / 256.0f, 0.0012877837f, 0.9492662926f},
            {2.0f / 128.0f, 0.0022515827f, 0.9329321561f},
            {3.0f / 128.0f, 0.0049030350f, 0.9010958535f},
            {2.0f / 64.0f, 0.0084426929f, 0.8703307793f},
            {3.0f / 64.0f, 0.0178631928f, 0.8118317459f},
            {2.0f / 32.0f, 0.0299545822f, 0.7570763753f},
            {3.0f / 32.0f, 0.0604985076f, 0.6574551915f},
            {2.0f / 16.0f, 0.0976310729f, 0.5690355937f},
            {3.0f / 16.0f, 0.1866943331f, 0.4181633458f},
            {2.0f / 8.0f, 0.2928932188f, 0.2928932188f}};

    /**
     * Holds a second order section and its state per channel; Linkwitz-Riley filters run it twice.
     */
    private static final class Filter {
        float a1;
        float a2;
        float b0;
        float b1;
        float b2;
        final float[] state;

        Filter(int statesPerChannel) {
            state = new float[CHANNEL_COUNT_MAX * statesPerChannel];
        }

        static Filter lowPass(int crossoverFreqIndex) {
            var filter = new Filter(4);
            var gamma = gamma(crossoverFreqIndex);
            var delta = delta(crossoverFreqIndex);
            filter.a1 = 2.0f * (gamma - delta);
            filter.a2 = 2.0f * (gamma + delta) - 1.0f;
            filter.b0 = gamma;
            filter.b1 = 2.0f * gamma;
            filter.b2 = gamma;
            return filter;
        }

        static Filter highPass(int crossoverFreqIndex) {
            var filter = new Filter(4);
            var gamma = gamma(crossoverFreqIndex);
            var delta = delta(crossoverFreqIndex);
            filter.a1 = 2.0f * (gamma - delta);
            filter.a2 = 2.0f * (gamma + delta) - 1.0f;
            filter.b0 = delta;
            filter.b1 = -2.0f * delta;
            filter.b2 = delta;
            return filter;
        }

        static Filter allPass(int crossoverFreqIndex) {
            var filter = new Filter(2);
            var gamma = gamma(crossoverFreqIndex);
            var delta = delta(crossoverFreqIndex);
            filter.a1 = 2.0f * (gamma - delta);
            filter.a2 = 2.0f * (gamma + delta) - 1.0f;
            filter.b0 = filter.a2;
            filter.b1 = filter.a1;
            filter.b2 = 1.0f;
            return filter;
        }

        void runLr(int c, int size, float[] in, float[] out) {
            var s = 4 * c;
            var s00 = state[s];
            var s01 = state[s + 1];
            var s10 = state[s + 2];
            var s11 = state[s + 3];
            for (var n = 0; n < size; n++) {
                var x = in[n];
                var y = b0 * x + s00;
                s00 = b1 * x - a1 * y + s01;
                s01 = b2 * x - a2 * y;
                var z = b0 * y + s10;
                out[n] = z;
                s10 = b1 * y - a1 * z + s11;
                s11 = b2 * y - a2 * z;
            }
            state[s] = s00;
            state[s + 1] = s01;
            state[s + 2] = s10;
            state[s + 3] = s11;
        }

        void runAllPass(int c, int size, float[] in, float[] out) {
            var s = 2 * c;
            var s0 = state[s];
            var s1 = state[s + 1];
            for (var n = 0; n < size; n++) {
                var x = in[n];
                var y = b0 * x + s0;
                out[n] = y;
                s0 = b1 * x - a1 * y + s1;
                s1 = b2 * x - a2 * y;
            }
            state[s] = s0;
            state[s + 1] = s1;
        }
    }

    /**
     * Holds the filter bank of one channel group and its phase alignment all-pass cascade.
     */
    private static final class Bank {
        int nBands;
        int complexity;
        Filter lowPass1;
        Filter highPass1;
        Filter lowPass2;
        Filter highPass2;
        Filter allPass2;
        Filter allPass2Low;
        Filter allPass2High;
        Filter lowPass3Low;
        Filter highPass3Low;
        Filter lowPass3High;
        Filter highPass3High;
        Filter[] cascade;
    }

    private final Bank[] banks;
    private int complexity;

    /**
     * Builds the filter banks of a multi-band DRC set ({@code initAllFilterBanks}).
     *
     * @param coefficients the coefficients, or {@code null}
     * @param instructions the DRC set
     */
    DrcFilterBanks(DrcCoefficientsUniDrc coefficients, DrcInstructionsUniDrc instructions) {
        var nChannelsInGroups = 0;
        for (var g = 0; g < instructions.nDrcChannelGroups; g++) {
            nChannelsInGroups += instructions.nChannelsPerChannelGroup[g];
        }
        var nPhaseAlignmentChannelGroups = instructions.nDrcChannelGroups;
        if (nChannelsInGroups < instructions.audioChannelCount) {
            nPhaseAlignmentChannelGroups++;
        }
        banks = new Bank[nPhaseAlignmentChannelGroups];
        for (var g = 0; g < banks.length; g++) {
            banks[g] = new Bank();
        }
        if (coefficients == null) {
            if (banks.length > 0) {
                banks[0].nBands = 1;
            }
            return;
        }
        for (var g = 0; g < instructions.nDrcChannelGroups; g++) {
            var index = instructions.gainSetIndexForChannelGroup[g];
            if (index >= 0) {
                initFilterBank(coefficients.gainSetParams[index].bandCount, coefficients.gainSetParams[index].gainParams,
                        banks[g]);
            }
        }
        var count = new int[CHANNEL_GROUP_COUNT_MAX + 1];
        var cascadeCrossoverIndices = new int[CHANNEL_GROUP_COUNT_MAX + 1][CHANNEL_GROUP_COUNT_MAX * 3 + 1];
        for (var g = 0; g < instructions.nDrcChannelGroups; g++) {
            var index = instructions.gainSetIndexForChannelGroup[g];
            if (index >= 0) {
                var set = coefficients.gainSetParams[index];
                for (var b = 1; b < set.bandCount; b++) {
                    var crossoverFreqIndex = set.gainParams[b].crossoverFreqIndex;
                    for (var k = 0; k < nPhaseAlignmentChannelGroups; k++) {
                        if (k != g) {
                            cascadeCrossoverIndices[k][count[k]] = crossoverFreqIndex;
                            count[k]++;
                            if (count[k] > CHANNEL_GROUP_COUNT_MAX * 3) {
                                throw new MediaException.Malformed("MPEG-D DRC phase alignment is too complex");
                            }
                        }
                    }
                }
            }
        }
        var i = 0;
        while (i < count[0]) {
            var crossoverFreqIndex = cascadeCrossoverIndices[0][i];
            var found = false;
            for (var g = 1; g < nPhaseAlignmentChannelGroups; g++) {
                found = false;
                for (var k = 0; k < count[g]; k++) {
                    if (cascadeCrossoverIndices[g][k] == crossoverFreqIndex) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    break;
                }
            }
            if (found) {
                for (var g = 0; g < nPhaseAlignmentChannelGroups; g++) {
                    for (var m = 0; m < count[g]; m++) {
                        if (cascadeCrossoverIndices[g][m] == crossoverFreqIndex) {
                            System.arraycopy(cascadeCrossoverIndices[g], m + 1, cascadeCrossoverIndices[g], m,
                                    count[g] - m - 1);
                            count[g]--;
                            break;
                        }
                    }
                }
                i = 0;
            } else {
                i++;
            }
        }
        for (var g = 0; g < nPhaseAlignmentChannelGroups; g++) {
            if (count[g] > 0) {
                var cascade = new Filter[count[g]];
                for (var n = 0; n < count[g]; n++) {
                    cascade[n] = Filter.allPass(checkedIndex(cascadeCrossoverIndices[g][n]));
                    banks[g].complexity += 2;
                }
                banks[g].cascade = cascade;
            }
        }
        for (var g = 0; g < instructions.nDrcChannelGroups; g++) {
            complexity += instructions.nChannelsPerChannelGroup[g] * banks[g].complexity;
        }
    }

    private static int checkedIndex(int crossoverFreqIndex) {
        if (crossoverFreqIndex < 0 || crossoverFreqIndex >= FILTER_BANK_PARAMS.length) {
            throw new MediaException.Malformed("MPEG-D DRC crossover frequency index is invalid");
        }
        return crossoverFreqIndex;
    }

    private static float gamma(int crossoverFreqIndex) {
        return FILTER_BANK_PARAMS[checkedIndex(crossoverFreqIndex)][1];
    }

    private static float delta(int crossoverFreqIndex) {
        return FILTER_BANK_PARAMS[checkedIndex(crossoverFreqIndex)][2];
    }

    private static void initFilterBank(int nBands, GainParams[] gainParams, Bank bank) {
        bank.complexity = 0;
        bank.nBands = nBands;
        switch (nBands) {
            case 1 -> {
            }
            case 2 -> {
                bank.lowPass1 = Filter.lowPass(gainParams[1].crossoverFreqIndex);
                bank.highPass1 = Filter.highPass(gainParams[1].crossoverFreqIndex);
                bank.complexity = 8;
            }
            case 3 -> {
                bank.lowPass2 = Filter.lowPass(gainParams[1].crossoverFreqIndex);
                bank.highPass2 = Filter.highPass(gainParams[1].crossoverFreqIndex);
                bank.lowPass1 = Filter.lowPass(gainParams[2].crossoverFreqIndex);
                bank.highPass1 = Filter.highPass(gainParams[2].crossoverFreqIndex);
                bank.allPass2 = Filter.allPass(gainParams[1].crossoverFreqIndex);
                bank.complexity = 18;
            }
            case 4 -> {
                bank.lowPass1 = Filter.lowPass(gainParams[2].crossoverFreqIndex);
                bank.highPass1 = Filter.highPass(gainParams[2].crossoverFreqIndex);
                bank.allPass2Low = Filter.allPass(gainParams[3].crossoverFreqIndex);
                bank.allPass2High = Filter.allPass(gainParams[1].crossoverFreqIndex);
                bank.lowPass3Low = Filter.lowPass(gainParams[1].crossoverFreqIndex);
                bank.highPass3Low = Filter.highPass(gainParams[1].crossoverFreqIndex);
                bank.lowPass3High = Filter.lowPass(gainParams[3].crossoverFreqIndex);
                bank.highPass3High = Filter.highPass(gainParams[3].crossoverFreqIndex);
                bank.complexity = 28;
            }
            default -> throw new MediaException.Malformed("MPEG-D DRC time domain filter banks support up to 4 bands");
        }
    }

    /**
     * {@return the complexity of the filter banks, in units of second order sections per sample}
     */
    int complexity() {
        return complexity;
    }

    /**
     * Splits the channels of a DRC set into bands ({@code applyFilterBanks}).
     *
     * <p>The phase alignment filters run in place on the input.
     *
     * @param banks the filter banks of the multi-band set, or {@code null} to pass every channel through as one band
     * @param instructions the DRC set
     * @param frameSize the number of samples per frame
     * @param input the channels, filtered in place by the phase alignment
     * @param bands receives the bands of all channels in order
     */
    static void apply(DrcFilterBanks banks, DrcInstructionsUniDrc instructions, int frameSize, float[][] input,
                      float[][] bands) {
        var e = 0;
        for (var c = 0; c < instructions.audioChannelCount; c++) {
            var audioIn = input[c];
            Bank bank = null;
            var nBands = 1;
            if (banks != null && instructions.drcSetId >= 0) {
                var g = instructions.channelGroupForChannel[c];
                if (g == -1) {
                    bank = banks.banks[instructions.nDrcChannelGroups];
                } else {
                    nBands = instructions.bandCountForChannelGroup[g];
                    bank = banks.banks[g];
                }
                if (bank.cascade != null) {
                    for (var filter : bank.cascade) {
                        filter.runAllPass(c, frameSize, audioIn, audioIn);
                    }
                }
            }
            switch (nBands) {
                case 1 -> {
                    System.arraycopy(audioIn, 0, bands[e], 0, frameSize);
                    e++;
                }
                case 2 -> {
                    bank.lowPass1.runLr(c, frameSize, audioIn, bands[e]);
                    bank.highPass1.runLr(c, frameSize, audioIn, bands[e + 1]);
                    e += 2;
                }
                case 3 -> {
                    var buf = bands[e + 2];
                    bank.lowPass1.runLr(c, frameSize, audioIn, buf);
                    bank.lowPass2.runLr(c, frameSize, buf, bands[e]);
                    bank.highPass2.runLr(c, frameSize, buf, bands[e + 1]);
                    bank.highPass1.runLr(c, frameSize, audioIn, buf);
                    bank.allPass2.runAllPass(c, frameSize, buf, bands[e + 2]);
                    e += 3;
                }
                case 4 -> {
                    var buf = bands[e + 3];
                    bank.lowPass1.runLr(c, frameSize, audioIn, buf);
                    bank.allPass2Low.runAllPass(c, frameSize, buf, buf);
                    bank.lowPass3Low.runLr(c, frameSize, buf, bands[e]);
                    bank.highPass3Low.runLr(c, frameSize, buf, bands[e + 1]);
                    bank.highPass1.runLr(c, frameSize, audioIn, buf);
                    bank.allPass2High.runAllPass(c, frameSize, buf, buf);
                    bank.lowPass3High.runLr(c, frameSize, buf, bands[e + 2]);
                    bank.highPass3High.runLr(c, frameSize, buf, bands[e + 3]);
                    e += 4;
                }
                default -> throw new MediaException.Malformed("MPEG-D DRC time domain filter banks support up to 4 "
                        + "bands");
            }
        }
    }
}

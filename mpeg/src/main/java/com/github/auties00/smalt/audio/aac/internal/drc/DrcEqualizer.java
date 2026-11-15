/* Ports the time domain part of the ISO/IEC 23003-4 reference software equalizer (uniDrcEq.c). Copyright Apple Inc.,
   Fraunhofer IIS and ISO/IEC 2014; ISO/IEC reference software licence (use in products conforming to
   ISO/IEC 23003-4). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.drc;

import com.github.auties00.smalt.MediaException;

import static com.github.auties00.smalt.audio.aac.internal.drc.DrcModel.*;

/**
 * Applies an equalizer set of the MPEG-D DRC extension in the time domain: cascades of pole/zero and FIR filter
 * blocks per channel group, with the phase alignment filters that keep parallel filter elements and aligned channel
 * groups in phase (ISO/IEC 23003-4 clause 6.4.11).
 *
 * <p>Instances are mutable and not thread-safe.
 */
final class DrcEqualizer {
    private static final int CONFIG_REAL_POLE = 0;
    private static final int CONFIG_COMPLEX_POLE = 1;
    private static final int CONFIG_REAL_ZERO_RADIUS_ONE = 2;
    private static final int CONFIG_REAL_ZERO = 3;
    private static final int CONFIG_GENERIC_ZERO = 4;
    private static final int FILTER_ELEMENT_FORMAT_POLE_ZERO = 0;
    private static final int FILTER_ELEMENT_FORMAT_FIR = 1;
    private static final int EQ_AUDIO_DELAY_MAX = 1024;
    private static final int SECTION_COUNT_MAX = 64;
    private static final int PARAMS_COUNT_MAX = 2 * (REAL_ZERO_COUNT_MAX + COMPLEX_ZERO_COUNT_MAX);

    private static final class AudioDelay {
        int delay;
        float[][] state;

        AudioDelay copy(int channels) {
            var copy = new AudioDelay();
            copy.delay = delay;
            copy.state = new float[channels][Math.max(1, delay)];
            return copy;
        }

        void allocate(int channels) {
            if (delay > EQ_AUDIO_DELAY_MAX) {
                throw new MediaException.Malformed("MPEG-D DRC equalizer delay is too long");
            }
            state = new float[channels][Math.max(1, delay)];
        }

        float process(int channel, float audioIn) {
            if (delay <= 0) {
                return audioIn;
            }
            var s = state[channel];
            var out = s[delay - 1];
            System.arraycopy(s, 0, s, 1, delay - 1);
            s[0] = audioIn;
            return out;
        }
    }

    private static final class FilterSection {
        float a1;
        float a2;
        float b1;
        float b2;
        float[] state;

        FilterSection copy(int channels) {
            var copy = new FilterSection();
            copy.a1 = a1;
            copy.a2 = a2;
            copy.b1 = b1;
            copy.b2 = b2;
            copy.state = new float[4 * channels];
            return copy;
        }

        float process(int channel, float audioIn) {
            var s = 4 * channel;
            var out = audioIn + b1 * state[s] + b2 * state[s + 1] - a1 * state[s + 2] - a2 * state[s + 3];
            state[s + 1] = state[s];
            state[s] = audioIn;
            state[s + 3] = state[s + 2];
            state[s + 2] = out;
            return out;
        }
    }

    private static final class FirFilter {
        int coeffCount;
        final float[] coeff = new float[FIR_ORDER_MAX + 2 + 2 * PARAMS_COUNT_MAX];
        float[][] state;

        float process(int channel, float audioIn) {
            var s = state[channel];
            var sum = coeff[0] * audioIn;
            for (var i = 1; i < coeffCount; i++) {
                sum += coeff[i] * s[i - 1];
            }
            for (var i = coeffCount - 2; i > 0; i--) {
                s[i] = s[i - 1];
            }
            s[0] = audioIn;
            return sum;
        }
    }

    private static final class PhaseAlignmentFilter {
        boolean valid;
        int matchesFilterCount;
        final int[] matchesFilter = new int[FILTER_ELEMENT_COUNT_MAX + 1];
        float gain = 1.0f;
        int sectionCount;
        final FilterSection[] sections = new FilterSection[SECTION_COUNT_MAX];
        AudioDelay audioDelay = new AudioDelay();

        PhaseAlignmentFilter copy(int channels) {
            var copy = new PhaseAlignmentFilter();
            copy.valid = valid;
            copy.matchesFilterCount = matchesFilterCount;
            System.arraycopy(matchesFilter, 0, copy.matchesFilter, 0, matchesFilter.length);
            copy.gain = gain;
            copy.sectionCount = sectionCount;
            for (var i = 0; i < sectionCount; i++) {
                copy.sections[i] = sections[i].copy(channels);
            }
            copy.audioDelay = audioDelay.copy(channels);
            return copy;
        }

        float process(int channel, float audioIn) {
            var value = audioIn;
            for (var i = 0; i < sectionCount; i++) {
                value = sections[i].process(channel, value);
            }
            return audioDelay.process(channel, value) * gain;
        }
    }

    private static final class PoleZeroFilter {
        int sectionCount;
        final FilterSection[] sections = new FilterSection[SECTION_COUNT_MAX];
        boolean firCoeffsPresent;
        final FirFilter firFilter = new FirFilter();
        final AudioDelay audioDelay = new AudioDelay();

        float process(int channel, float audioIn) {
            var value = audioIn;
            for (var i = 0; i < sectionCount; i++) {
                value = sections[i].process(channel, value);
            }
            if (firCoeffsPresent) {
                value = firFilter.process(channel, value);
            }
            return audioDelay.process(channel, value);
        }
    }

    private static final class FilterElement {
        float elementGainLinear;
        int format;
        final PoleZeroFilter poleZeroFilter = new PoleZeroFilter();
        final FirFilter firFilter = new FirFilter();
        int phaseAlignmentFilterCount;
        final PhaseAlignmentFilter[] phaseAlignmentFilters = new PhaseAlignmentFilter[FILTER_ELEMENT_COUNT_MAX + 1];

        float process(int channel, float audioIn) {
            var value = switch (format) {
                case FILTER_ELEMENT_FORMAT_POLE_ZERO -> poleZeroFilter.process(channel, audioIn);
                case FILTER_ELEMENT_FORMAT_FIR -> firFilter.process(channel, audioIn);
                default -> audioIn;
            };
            value *= elementGainLinear;
            for (var i = 0; i < phaseAlignmentFilterCount; i++) {
                value = phaseAlignmentFilters[i].process(channel, value);
            }
            return value;
        }
    }

    private static final class EqFilterBlock {
        int elementCount;
        final FilterElement[] elements = new FilterElement[FILTER_ELEMENT_COUNT_MAX];
        PhaseAlignmentFilter matchingPhaseFilterElement0;

        float process(int channel, float audioIn) {
            var sum = 0.0f;
            for (var i = 0; i < elementCount; i++) {
                sum += elements[i].process(channel, audioIn);
            }
            return sum;
        }
    }

    private static final class FilterCascade {
        float cascadeGainLinear;
        int blockCount;
        final EqFilterBlock[] blocks = new EqFilterBlock[EQ_FILTER_BLOCK_COUNT_MAX];
        int phaseAlignmentFilterCount;
        PhaseAlignmentFilter[] phaseAlignmentFilters = new PhaseAlignmentFilter[0];

        float process(int channel, float audioIn) {
            var value = audioIn;
            for (var i = 0; i < blockCount; i++) {
                value = blocks[i].process(channel, value);
            }
            for (var i = 0; i < phaseAlignmentFilterCount; i++) {
                value = phaseAlignmentFilters[i].process(channel, value);
            }
            return value * cascadeGainLinear;
        }

        void addPhaseAlignmentFilter(PhaseAlignmentFilter filter) {
            if (phaseAlignmentFilterCount == phaseAlignmentFilters.length) {
                phaseAlignmentFilters = java.util.Arrays.copyOf(phaseAlignmentFilters,
                        Math.max(4, 2 * phaseAlignmentFilterCount));
            }
            phaseAlignmentFilters[phaseAlignmentFilterCount++] = filter;
        }
    }

    private static final class SecondOrderParams {
        float radius;
        final float[] coeff = new float[2];
    }

    private final int audioChannelCount;
    private final int[] eqChannelGroupForChannel;
    private final FilterCascade[] cascades;
    private final int channels;

    /**
     * Derives the filters of an equalizer set ({@code deriveEqSet}) for the time domain.
     *
     * @param eqCoefficients the filter coefficients of the configuration
     * @param eqInstructions the equalizer set
     */
    DrcEqualizer(EqCoefficients eqCoefficients, EqInstructions eqInstructions) {
        audioChannelCount = eqInstructions.eqChannelCount;
        channels = Math.max(1, audioChannelCount);
        eqChannelGroupForChannel = new int[channels];
        for (var i = 0; i < audioChannelCount; i++) {
            eqChannelGroupForChannel[i] = eqInstructions.eqChannelGroupForChannel[i];
        }
        cascades = new FilterCascade[EQ_CHANNEL_GROUP_COUNT_MAX];
        for (var g = 0; g < cascades.length; g++) {
            cascades[g] = new FilterCascade();
        }
        if (eqInstructions.tdFilterCascadePresent == 1) {
            deriveFilterCascade(eqCoefficients.uniqueTdFilterElement, eqCoefficients.filterBlock,
                    eqInstructions.tdFilterCascade, eqInstructions.eqChannelGroupCount);
        }
    }

    /**
     * {@return the number of channels the equalizer processes}
     */
    int audioChannelCount() {
        return audioChannelCount;
    }

    /**
     * {@return the delay of the equalizer in samples} ({@code getEqSetDelay})
     */
    int delay() {
        var delay = 0.0f;
        if (audioChannelCount > 0) {
            var g = eqChannelGroupForChannel[0];
            if (g >= 0) {
                var cascade = cascades[g];
                for (var b = 0; b < cascade.blockCount; b++) {
                    var element = cascade.blocks[b].elements[0];
                    switch (element.format) {
                        case FILTER_ELEMENT_FORMAT_POLE_ZERO -> {
                            delay += sectionDelay(element.poleZeroFilter.sectionCount, element.poleZeroFilter.sections);
                            if (element.poleZeroFilter.firCoeffsPresent) {
                                delay += 0.5f * (element.poleZeroFilter.firFilter.coeffCount - 1);
                            }
                        }
                        case FILTER_ELEMENT_FORMAT_FIR -> delay += 0.5f * (element.firFilter.coeffCount - 1);
                        default -> {
                        }
                    }
                    for (var k = 0; k < element.phaseAlignmentFilterCount; k++) {
                        var filter = element.phaseAlignmentFilters[k];
                        delay += sectionDelay(filter.sectionCount, filter.sections);
                    }
                }
                for (var b = 0; b < cascade.phaseAlignmentFilterCount; b++) {
                    var filter = cascade.phaseAlignmentFilters[b];
                    delay += sectionDelay(filter.sectionCount, filter.sections);
                }
            }
        }
        return (int) delay;
    }

    private static float sectionDelay(int sectionCount, FilterSection[] sections) {
        var d = 0.0f;
        for (var i = 0; i < sectionCount; i++) {
            if (sections[i].b2 != 0.0f) {
                d += 1.0f;
            } else if (sections[i].b1 != 0.0f) {
                d += 0.5f;
            }
        }
        return d;
    }

    /**
     * Filters a frame of one channel in place ({@code processEqSetTimeDomain}).
     *
     * @param channel the channel index
     * @param audio the samples
     * @param count the number of samples
     */
    void process(int channel, float[] audio, int count) {
        var g = eqChannelGroupForChannel[channel];
        if (g < 0) {
            return;
        }
        var cascade = cascades[g];
        for (var i = 0; i < count; i++) {
            audio[i] = cascade.process(channel, audio[i]);
        }
    }

    private void deriveFilterCascade(UniqueTdFilterElement[] uniqueElements,
                                     FilterBlock[] filterBlocks,
                                     TdFilterCascade tdFilterCascade, int channelGroupCount) {
        if (channelGroupCount > EQ_CHANNEL_GROUP_COUNT_MAX) {
            throw new MediaException.Malformed("MPEG-D DRC equalizer has too many channel groups");
        }
        for (var g = 0; g < channelGroupCount; g++) {
            var refs = tdFilterCascade.filterBlockRefs[g];
            var i = 0;
            for (; i < refs.filterBlockCount; i++) {
                cascades[g].blocks[i] = deriveFilterBlock(uniqueElements, filterBlocks[refs.filterBlockIndex[i]]);
            }
            cascades[g].blockCount = i;
            cascades[g].cascadeGainLinear = (float) StrictMath.pow(10.0f, 0.05f * tdFilterCascade.eqCascadeGain[g]);
        }
        var groups = deriveCascadeAlignmentGroups(channelGroupCount, tdFilterCascade.eqPhaseAlignmentPresent,
                tdFilterCascade.eqPhaseAlignment);
        for (var group : groups) {
            var chains = new PhaseAlignmentFilter[group.length][];
            var matchesCascadeIndex = new int[group.length];
            for (var k = 0; k < group.length; k++) {
                chains[k] = deriveAllpassChain(cascades[group[k]]);
                matchesCascadeIndex[k] = group[k];
            }
            for (var k = 0; k < group.length; k++) {
                var cascadeIndex = group[k];
                for (var m = 0; m < group.length; m++) {
                    if (cascadeIndex != matchesCascadeIndex[m]) {
                        for (var filter : chains[m]) {
                            cascades[cascadeIndex].addPhaseAlignmentFilter(filter.copy(channels));
                        }
                    }
                }
            }
        }
    }

    private static int[][] deriveCascadeAlignmentGroups(int eqChannelGroupCount, int eqPhaseAlignmentPresent,
                                                        int[][] eqPhaseAlignment) {
        var members = new int[EQ_CHANNEL_GROUP_COUNT_MAX][EQ_CHANNEL_GROUP_COUNT_MAX];
        var memberCount = new int[EQ_CHANNEL_GROUP_COUNT_MAX];
        var groupCount = 0;
        if (eqPhaseAlignmentPresent == 0) {
            if (eqChannelGroupCount > 1) {
                for (var i = 0; i < eqChannelGroupCount; i++) {
                    members[0][i] = i;
                }
                memberCount[0] = eqChannelGroupCount;
                groupCount = 1;
            }
        } else {
            for (var i = 0; i < eqChannelGroupCount; i++) {
                for (var k = i + 1; k < eqChannelGroupCount; k++) {
                    if (eqPhaseAlignment[i][k] == 1) {
                        var done = false;
                        for (var g = 0; g < groupCount; g++) {
                            done = checkPresenceAndAddCascade(members[g], memberCount, g, i, k);
                            if (!done) {
                                done = checkPresenceAndAddCascade(members[g], memberCount, g, k, i);
                            }
                        }
                        if (!done) {
                            members[groupCount][0] = i;
                            members[groupCount][1] = k;
                            memberCount[groupCount] = 2;
                            groupCount++;
                        }
                    }
                }
            }
        }
        var groups = new int[groupCount][];
        for (var g = 0; g < groupCount; g++) {
            groups[g] = java.util.Arrays.copyOf(members[g], memberCount[g]);
        }
        return groups;
    }

    private static boolean checkPresenceAndAddCascade(int[] members, int[] memberCount, int group, int c1, int c2) {
        var done = false;
        for (var m = 0; m < memberCount[group]; m++) {
            if (members[m] == c1) {
                for (var n = 0; n < memberCount[group]; n++) {
                    if (members[n] == c2) {
                        done = true;
                    }
                }
                if (!done) {
                    if (memberCount[group] >= members.length) {
                        throw new MediaException.Malformed("MPEG-D DRC equalizer phase alignment is invalid");
                    }
                    members[memberCount[group]++] = c2;
                    done = true;
                }
            }
        }
        return done;
    }

    private PhaseAlignmentFilter[] deriveAllpassChain(FilterCascade cascade) {
        var chain = new java.util.ArrayList<PhaseAlignmentFilter>();
        for (var b = 0; b < cascade.blockCount; b++) {
            var block = cascade.blocks[b];
            if (!block.matchingPhaseFilterElement0.valid) {
                throw new MediaException.Malformed("MPEG-D DRC equalizer cannot align the phase of its cascades");
            }
            chain.add(block.matchingPhaseFilterElement0);
            var element = block.elements[0];
            for (var k = 0; k < element.phaseAlignmentFilterCount; k++) {
                if (!element.phaseAlignmentFilters[k].valid) {
                    throw new MediaException.Malformed("MPEG-D DRC equalizer cannot align the phase of its cascades");
                }
                chain.add(element.phaseAlignmentFilters[k]);
            }
        }
        return chain.toArray(new PhaseAlignmentFilter[0]);
    }

    private PhaseAlignmentFilter deriveMatchingPhaseFilter(UniqueTdFilterElement element, int filterElementIndex) {
        var filter = new PhaseAlignmentFilter();
        if (element.eqFilterFormat == FILTER_ELEMENT_FORMAT_POLE_ZERO) {
            for (var i = 0; i < element.realPoleCount; i++) {
                addMatchingPhaseSection(filter, CONFIG_REAL_POLE, element.realPoleRadius[i], 0.0f);
            }
            for (var i = 0; i < element.complexPoleCount; i++) {
                addMatchingPhaseSection(filter, CONFIG_COMPLEX_POLE, element.complexPoleRadius[i],
                        element.complexPoleAngle[i]);
            }
        }
        var delay = 0;
        if (element.eqFilterFormat == FILTER_ELEMENT_FORMAT_POLE_ZERO && element.realZeroRadiusOneCount == 0) {
            delay = Math.max(0, element.realZeroCount + 2 * element.genericZeroCount - element.realPoleCount
                    - 2 * element.complexPoleCount);
            filter.valid = true;
        }
        filter.audioDelay.delay = delay;
        filter.audioDelay.allocate(channels);
        filter.matchesFilterCount = 1;
        filter.matchesFilter[0] = filterElementIndex;
        return filter;
    }

    private void addMatchingPhaseSection(PhaseAlignmentFilter filter, int config, float radius, float angle) {
        if (filter.sectionCount >= SECTION_COUNT_MAX) {
            throw new MediaException.Malformed("MPEG-D DRC equalizer has too many filter sections");
        }
        var section = new FilterSection();
        section.state = new float[4 * channels];
        if (config == CONFIG_REAL_POLE) {
            filter.gain *= -radius;
            section.a1 = -radius;
            section.a2 = 0.0f;
            section.b1 = -1.0f / radius;
            section.b2 = 0.0f;
        } else {
            var zReal = (float) (radius * StrictMath.cos(Math.PI * angle));
            var zImag = (float) (radius * StrictMath.sin(Math.PI * angle));
            var prod = zReal * zReal + zImag * zImag;
            filter.gain *= prod;
            section.a1 = -2.0f * zReal;
            section.a2 = prod;
            section.b1 = -2.0f * zReal / prod;
            section.b2 = 1.0f / prod;
        }
        filter.sections[filter.sectionCount++] = section;
    }

    private static boolean phaseFilterIsEqual(PhaseAlignmentFilter filter1, PhaseAlignmentFilter filter2) {
        var equal = true;
        if (filter1.sectionCount != filter2.sectionCount) {
            equal = false;
        } else {
            for (var i = 0; i < filter1.sectionCount; i++) {
                var s1 = filter1.sections[i];
                var s2 = filter2.sections[i];
                if (s1.a1 != s2.a1 || s1.a2 != s2.a2 || s1.b1 != s2.b1 || s1.b2 != s2.b2) {
                    equal = false;
                    break;
                }
            }
        }
        if (filter1.audioDelay.delay != filter2.audioDelay.delay) {
            equal = false;
        }
        return equal;
    }

    private void deriveElementPhaseAlignmentFilters(PhaseAlignmentFilter[] matching, EqFilterBlock block) {
        var optimized = new PhaseAlignmentFilter[block.elementCount];
        var optimizedCount = 0;
        for (var i = 0; i < block.elementCount; i++) {
            var equal = false;
            var k = 0;
            for (; k < optimizedCount; k++) {
                equal = phaseFilterIsEqual(matching[i], optimized[k]);
                if (equal) {
                    break;
                }
            }
            if (equal) {
                optimized[k].matchesFilter[optimized[k].matchesFilterCount++] = i;
            } else {
                optimized[optimizedCount++] = matching[i].copy(channels);
            }
        }
        for (var i = 0; i < block.elementCount; i++) {
            var element = block.elements[i];
            for (var k = 0; k < optimizedCount; k++) {
                var skip = false;
                for (var m = 0; m < optimized[k].matchesFilterCount; m++) {
                    if (optimized[k].matchesFilter[m] == i) {
                        skip = true;
                        break;
                    }
                }
                if (!skip) {
                    if (!optimized[k].valid) {
                        throw new MediaException.Malformed("MPEG-D DRC equalizer cannot align the phase of its elements");
                    }
                    if (element.phaseAlignmentFilterCount >= element.phaseAlignmentFilters.length) {
                        throw new MediaException.Malformed("MPEG-D DRC equalizer has too many phase alignment filters");
                    }
                    element.phaseAlignmentFilters[element.phaseAlignmentFilterCount++] = optimized[k].copy(channels);
                }
            }
        }
        var pathDelayMin = 100000;
        for (var i = 0; i < block.elementCount; i++) {
            var element = block.elements[i];
            var pathDelay = 0;
            for (var m = 0; m < element.phaseAlignmentFilterCount; m++) {
                pathDelay += element.phaseAlignmentFilters[m].audioDelay.delay;
            }
            pathDelayMin = Math.min(pathDelayMin, pathDelay);
        }
        if (pathDelayMin > 0) {
            for (var i = 0; i < block.elementCount; i++) {
                var element = block.elements[i];
                var pathDelayToRemove = pathDelayMin;
                for (var m = 0; m < element.phaseAlignmentFilterCount; m++) {
                    var delay = element.phaseAlignmentFilters[m].audioDelay;
                    var pathDelayNew = Math.max(0, delay.delay - pathDelayToRemove);
                    pathDelayToRemove -= delay.delay - pathDelayNew;
                    delay.delay = pathDelayNew;
                }
            }
        }
    }

    private int convertPoleZeroToFilterParams(int config, float radius, float angle, SecondOrderParams[] params,
                                              int index) {
        if (index + 2 > params.length) {
            throw new MediaException.Malformed("MPEG-D DRC equalizer element has too many poles or zeros");
        }
        switch (config) {
            case CONFIG_REAL_ZERO_RADIUS_ONE -> {
                params[index].radius = 1.0f;
                var coeff = params[index].coeff;
                if (radius != angle) {
                    coeff[0] = 0.0f;
                    coeff[1] = -1.0f;
                } else if (radius == 1.0f) {
                    coeff[0] = -2.0f;
                    coeff[1] = 1.0f;
                } else {
                    coeff[0] = 2.0f;
                    coeff[1] = 1.0f;
                }
                return 1;
            }
            case CONFIG_REAL_ZERO -> {
                params[index].radius = radius;
                if (Math.abs(radius) == 1.0f) {
                    throw new MediaException.Malformed("MPEG-D DRC equalizer real zero has a unit radius");
                }
                params[index].coeff[0] = -(radius + 1.0f / radius);
                params[index].coeff[1] = 1.0f;
                return 1;
            }
            case CONFIG_GENERIC_ZERO -> {
                var zReal = (float) (radius * StrictMath.cos(Math.PI * angle));
                params[index].radius = radius;
                params[index].coeff[0] = -2.0f * zReal;
                params[index].coeff[1] = radius * radius;
                zReal = (float) (StrictMath.cos(Math.PI * angle) / radius);
                params[index + 1].radius = radius;
                params[index + 1].coeff[0] = -2.0f * zReal;
                params[index + 1].coeff[1] = 1.0f / (radius * radius);
                return 2;
            }
            case CONFIG_REAL_POLE -> {
                params[index].radius = radius;
                params[index].coeff[0] = -2.0f * radius;
                params[index].coeff[1] = radius * radius;
                return 1;
            }
            default -> {
                var zReal = (float) (radius * StrictMath.cos(Math.PI * angle));
                params[index].radius = radius;
                params[index].coeff[0] = -2.0f * zReal;
                params[index].coeff[1] = radius * radius;
                params[index + 1].radius = radius;
                params[index + 1].coeff[0] = params[index].coeff[0];
                params[index + 1].coeff[1] = params[index].coeff[1];
                return 2;
            }
        }
    }

    private void convertFirFilterParams(int firFilterOrder, int firSymmetry, float[] firCoefficient,
                                        FirFilter firFilter) {
        var coeff = firFilter.coeff;
        firFilter.coeffCount = firFilterOrder + 1;
        for (var i = 0; i < firFilterOrder / 2 + 1; i++) {
            coeff[i] = firCoefficient[i];
        }
        for (var i = 0; i < (firFilterOrder + 1) / 2; i++) {
            coeff[firFilterOrder - i] = firSymmetry == 1 ? -coeff[i] : coeff[i];
        }
        if (firSymmetry == 1 && (firFilterOrder & 1) == 0) {
            coeff[firFilterOrder / 2] = 0.0f;
        }
        firFilter.state = new float[channels][firFilterOrder + 1];
    }

    private static SecondOrderParams[] newParams() {
        var params = new SecondOrderParams[PARAMS_COUNT_MAX];
        for (var i = 0; i < params.length; i++) {
            params[i] = new SecondOrderParams();
        }
        return params;
    }

    private void deriveEqFilterElements(SecondOrderParams[] zeros, int zeroCount, SecondOrderParams[] poles,
                                        int poleCount, FilterElement element) {
        var polesDone = new boolean[Math.max(1, poleCount)];
        var zerosDone = new boolean[Math.max(1, zeroCount)];
        var filter = element.poleZeroFilter;
        var section = 0;
        int polesIndex;
        var poleOrder = 0;
        do {
            var radiusMax = -1.0f;
            polesIndex = -1;
            for (var i = 0; i < poleCount; i++) {
                if (!polesDone[i] && radiusMax < Math.abs(poles[i].radius)) {
                    radiusMax = Math.abs(poles[i].radius);
                    polesIndex = i;
                    poleOrder = poles[i].coeff[1] != 0.0f ? 2 : 1;
                }
            }
            if (polesIndex >= 0) {
                var radiusDiff = 10.0f;
                var zerosIndex = -1;
                for (var pass = 0; pass < 2 && (pass == 0 || zerosIndex == -1); pass++) {
                    var wantSecondOrder = (poleOrder == 2) == (pass == 0);
                    for (var i = 0; i < zeroCount; i++) {
                        if (!zerosDone[i] && (zeros[i].coeff[1] != 0.0f) == wantSecondOrder) {
                            var diff = Math.abs((double) Math.abs(zeros[i].radius) - radiusMax);
                            if (radiusDiff > diff) {
                                radiusDiff = (float) diff;
                                zerosIndex = i;
                            }
                        }
                    }
                }
                if (section >= SECTION_COUNT_MAX) {
                    throw new MediaException.Malformed("MPEG-D DRC equalizer has too many filter sections");
                }
                var target = new FilterSection();
                target.state = new float[4 * channels];
                target.a1 = poles[polesIndex].coeff[0];
                target.a2 = poles[polesIndex].coeff[1];
                if (zerosIndex >= 0) {
                    target.b1 = zeros[zerosIndex].coeff[0];
                    target.b2 = zeros[zerosIndex].coeff[1];
                    zerosDone[zerosIndex] = true;
                } else {
                    target.b1 = 0.0f;
                    target.b2 = 0.0f;
                    filter.audioDelay.delay++;
                }
                polesDone[polesIndex] = true;
                filter.sections[section++] = target;
            }
        } while (polesIndex >= 0);
        filter.sectionCount = section;
        var coeffCount = 1;
        var coeff = filter.firFilter.coeff;
        coeff[0] = 1.0f;
        for (var i = 0; i < zeroCount; i++) {
            if (!zerosDone[i]) {
                var b1 = zeros[i].coeff[0];
                var b2 = zeros[i].coeff[1];
                coeffCount += 2;
                var k = coeffCount - 1;
                coeff[k] = b2 * coeff[k - 2];
                k--;
                if (k > 1) {
                    coeff[k] = b1 * coeff[k - 1] + b2 * coeff[k - 2];
                    k--;
                    for (; k > 1; k--) {
                        coeff[k] += b1 * coeff[k - 1] + b2 * coeff[k - 2];
                    }
                    coeff[1] += b1 * coeff[0];
                } else {
                    coeff[1] = b1 * coeff[0];
                }
            }
            zerosDone[i] = true;
        }
        if (coeffCount > 1) {
            filter.firCoeffsPresent = true;
            filter.firFilter.coeffCount = coeffCount;
            filter.firFilter.state = new float[channels][coeffCount];
        } else {
            filter.firCoeffsPresent = false;
            filter.firFilter.coeffCount = 0;
        }
        filter.audioDelay.allocate(channels);
    }

    private EqFilterBlock deriveFilterBlock(UniqueTdFilterElement[] uniqueElements, FilterBlock source) {
        for (var i = 0; i < source.filterElementCount; i++) {
            if (uniqueElements[source.filterElement[i].filterElementIndex].eqFilterFormat == FILTER_ELEMENT_FORMAT_FIR
                    && source.filterElementCount > 1) {
                throw new MediaException.Malformed("MPEG-D DRC equalizer combines an FIR element with others");
            }
        }
        var block = new EqFilterBlock();
        var matching = new PhaseAlignmentFilter[Math.max(1, source.filterElementCount)];
        for (var i = 0; i < source.filterElementCount; i++) {
            var element = new FilterElement();
            block.elements[i] = element;
            var reference = source.filterElement[i];
            var unique = uniqueElements[reference.filterElementIndex];
            if (unique.eqFilterFormat == FILTER_ELEMENT_FORMAT_POLE_ZERO) {
                var zeros = newParams();
                var poles = newParams();
                var zeroIndex = 0;
                for (var k = 0; k < unique.realZeroRadiusOneCount; k += 2) {
                    var second = k + 1 < unique.zeroSign.length ? unique.zeroSign[k + 1] : 0;
                    zeroIndex += convertPoleZeroToFilterParams(CONFIG_REAL_ZERO_RADIUS_ONE, unique.zeroSign[k], second,
                            zeros, zeroIndex);
                }
                for (var k = 0; k < unique.realZeroCount; k++) {
                    zeroIndex += convertPoleZeroToFilterParams(CONFIG_REAL_ZERO, unique.realZeroRadius[k], 0.0f, zeros,
                            zeroIndex);
                }
                for (var k = 0; k < unique.genericZeroCount; k++) {
                    zeroIndex += convertPoleZeroToFilterParams(CONFIG_GENERIC_ZERO, unique.genericZeroRadius[k],
                            unique.genericZeroAngle[k], zeros, zeroIndex);
                }
                var poleIndex = 0;
                for (var k = 0; k < unique.realPoleCount; k++) {
                    poleIndex += convertPoleZeroToFilterParams(CONFIG_REAL_POLE, unique.realPoleRadius[k], 0.0f, poles,
                            poleIndex);
                }
                for (var k = 0; k < unique.complexPoleCount; k++) {
                    poleIndex += convertPoleZeroToFilterParams(CONFIG_COMPLEX_POLE, unique.complexPoleRadius[k],
                            unique.complexPoleAngle[k], poles, poleIndex);
                }
                deriveEqFilterElements(zeros, zeroIndex, poles, poleIndex, element);
                element.format = FILTER_ELEMENT_FORMAT_POLE_ZERO;
            } else {
                convertFirFilterParams(unique.firFilterOrder, unique.firSymmetry, unique.firCoefficient,
                        element.firFilter);
                element.format = FILTER_ELEMENT_FORMAT_FIR;
            }
            element.elementGainLinear = reference.filterElementGainPresent == 1
                    ? (float) StrictMath.pow(10.0f, 0.05f * reference.filterElementGain) : 1.0f;
            for (var k = 0; k < unique.realZeroCount; k++) {
                if (unique.realZeroRadius[k] > 0.0f) {
                    element.elementGainLinear = -element.elementGainLinear;
                }
            }
            matching[i] = deriveMatchingPhaseFilter(unique, i);
        }
        block.elementCount = source.filterElementCount;
        block.matchingPhaseFilterElement0 = source.filterElementCount > 0 ? matching[0].copy(channels)
                : new PhaseAlignmentFilter();
        deriveElementPhaseAlignmentFilters(matching, block);
        return block;
    }
}

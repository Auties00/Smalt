package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.header.AacRates;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes how the encoder codes a channel layout: the channel configuration or program config
 * element it signals, its channel elements in coding order, and the input channel of each coded
 * channel.
 *
 * <p>A layout whose loudspeakers, in any order, are those of channel configuration 1 to 7 or 11 to
 * 14 is coded with that configuration. Any other layout is coded with channel configuration 0 and a
 * program config element whose element lists place every loudspeaker where {@link ChannelMapping}
 * reads it back: front elements from the center outwards (front center, front left and right of
 * center, front left and right), the side pair, the back pair then the back center, and the LFE.
 * Instance tags count the elements of each type in coding order. Instances are immutable and
 * thread-safe.
 *
 * @param channelConfiguration the channel configuration, from 1 to 14, or 0 with a program config element
 * @param programConfig the program config element of configuration 0, or {@code null}
 * @param elementTypes the {@link ElementId} type of each channel element, in coding order
 * @param elementTags the instance tag of each channel element
 * @param inputChannels the input channel of each coded channel
 * @param lfe whether each coded channel is an LFE channel
 */
public record EncoderLayout(int channelConfiguration, ProgramConfig programConfig, int[] elementTypes,
                            int[] elementTags, int[] inputChannels, boolean[] lfe) {
    /**
     * The channel configurations, in the order they are tried.
     */
    private static final int[] CONFIGURATIONS = {1, 2, 3, 4, 5, 6, 7, 11, 12, 13, 14};

    /**
     * The two-bit object type field of AAC Low Complexity in a program config element.
     */
    private static final int LOW_COMPLEXITY_PROFILE = 1;

    /**
     * Returns how a layout is coded.
     *
     * @param layout the input layout
     * @param sampleRate the sampling rate, a standard AAC rate
     * @return the coding, or empty when neither a channel configuration nor a program config
     * element expresses the layout
     */
    public static Optional<EncoderLayout> of(ChannelLayout layout, int sampleRate) {
        var wanted = sorted(layout.speakers());
        for (var configuration : CONFIGURATIONS) {
            var mapping = ChannelMapping.ofConfiguration(configuration, MediaLimits.DEFAULT);
            if (mapping.layout().speakers().equals(wanted)) {
                return Optional.of(build(configuration, null, mapping, layout));
            }
        }
        var program = program(layout, sampleRate);
        if (program == null || program.channels() != layout.channels()
                || program.channels() > MediaLimits.DEFAULT.maxChannels()) {
            return Optional.empty();
        }
        var mapping = ChannelMapping.ofProgram(program, MediaLimits.DEFAULT);
        if (!mapping.layout().speakers().equals(wanted)) {
            return Optional.empty();
        }
        return Optional.of(build(0, program, mapping, layout));
    }

    /**
     * Returns the number of LFE channels.
     *
     * @return the count
     */
    public int lfeChannels() {
        var count = 0;
        for (var value : lfe) {
            count += value ? 1 : 0;
        }
        return count;
    }

    /**
     * Builds the program config element of a layout made of positioned loudspeakers.
     *
     * @param layout the input layout
     * @param sampleRate the sampling rate
     * @return the element, or {@code null} when the layout holds a loudspeaker no element list positions
     */
    private static ProgramConfig program(ChannelLayout layout, int sampleRate) {
        var speakers = layout.speakers();
        for (var speaker : speakers) {
            switch (speaker) {
                case FRONT_CENTER, FRONT_LEFT_OF_CENTER, FRONT_RIGHT_OF_CENTER, FRONT_LEFT, FRONT_RIGHT, SIDE_LEFT,
                     SIDE_RIGHT, BACK_LEFT, BACK_RIGHT, BACK_CENTER, LOW_FREQUENCY -> {
                }
                default -> {
                    // TODO: height loudspeakers outside configurations 13 and 14 need the height extension of the
                    // program config element comment field, which ChannelMapping does not read either.
                    return null;
                }
            }
        }
        var tags = new int[4];
        var front = new ArrayList<ProgramConfig.Element>();
        single(speakers, Speaker.FRONT_CENTER, front, tags);
        pair(speakers, Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER, front, tags);
        pair(speakers, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, front, tags);
        var side = new ArrayList<ProgramConfig.Element>();
        pair(speakers, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, side, tags);
        var back = new ArrayList<ProgramConfig.Element>();
        pair(speakers, Speaker.BACK_LEFT, Speaker.BACK_RIGHT, back, tags);
        single(speakers, Speaker.BACK_CENTER, back, tags);
        var lfe = new ArrayList<Integer>();
        if (speakers.contains(Speaker.LOW_FREQUENCY)) {
            lfe.add(tags[ElementId.LFE]++);
        }
        var channels = lfe.size();
        for (var list : List.of(front, side, back)) {
            for (var element : list) {
                channels += element.pair() ? 2 : 1;
            }
        }
        return new ProgramConfig(0, LOW_COMPLEXITY_PROFILE, AacRates.index(sampleRate), front, side, back, lfe,
                List.of(), channels);
    }

    /**
     * Appends a single channel element when the layout holds its loudspeaker.
     *
     * @param speakers the loudspeakers of the layout
     * @param speaker the loudspeaker of the element
     * @param elements the element list, appended to
     * @param tags the next instance tag of each element type
     */
    private static void single(List<Speaker> speakers, Speaker speaker, List<ProgramConfig.Element> elements,
                               int[] tags) {
        if (speakers.contains(speaker)) {
            elements.add(new ProgramConfig.Element(false, tags[ElementId.SCE]++));
        }
    }

    /**
     * Appends a channel pair element when the layout holds either of its loudspeakers; a layout with
     * only one of them is then rejected, since the pair codes two channels.
     *
     * @param speakers the loudspeakers of the layout
     * @param left the left loudspeaker of the pair
     * @param right the right loudspeaker of the pair
     * @param elements the element list, appended to
     * @param tags the next instance tag of each element type
     */
    private static void pair(List<Speaker> speakers, Speaker left, Speaker right, List<ProgramConfig.Element> elements,
                             int[] tags) {
        if (speakers.contains(left) || speakers.contains(right)) {
            elements.add(new ProgramConfig.Element(true, tags[ElementId.CPE]++));
        }
    }

    /**
     * Assembles the coding of a layout from the mapping a decoder applies to it.
     *
     * @param configuration the channel configuration, or 0
     * @param program the program config element, or {@code null}
     * @param mapping the decoder's mapping of the configuration or element
     * @param layout the input layout
     * @return the coding
     */
    private static EncoderLayout build(int configuration, ProgramConfig program, ChannelMapping mapping,
                                       ChannelLayout layout) {
        var types = mapping.elementTypes();
        var tags = new int[types.length];
        var counters = new int[4];
        for (var i = 0; i < types.length; i++) {
            tags[i] = counters[types[i]]++;
        }
        var inputs = new int[mapping.channels()];
        var lfe = new boolean[mapping.channels()];
        var channel = 0;
        for (var type : types) {
            var width = type == ElementId.CPE ? 2 : 1;
            for (var i = channel; i < channel + width; i++) {
                inputs[i] = layout.speakers().indexOf(mapping.speaker(i));
                lfe[i] = type == ElementId.LFE;
            }
            channel += width;
        }
        return new EncoderLayout(configuration, program, types, tags, inputs, lfe);
    }

    /**
     * Returns loudspeakers in WAVE order, the order of a decoder's output layout.
     *
     * @param speakers the loudspeakers
     * @return the sorted list
     */
    private static List<Speaker> sorted(List<Speaker> speakers) {
        var array = speakers.toArray(Speaker[]::new);
        Arrays.sort(array);
        return List.of(array);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof EncoderLayout that
                && channelConfiguration == that.channelConfiguration
                && Objects.equals(programConfig, that.programConfig)
                && Arrays.equals(elementTypes, that.elementTypes)
                && Arrays.equals(elementTags, that.elementTags)
                && Arrays.equals(inputChannels, that.inputChannels)
                && Arrays.equals(lfe, that.lfe);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelConfiguration, programConfig, Arrays.hashCode(elementTypes),
                Arrays.hashCode(elementTags), Arrays.hashCode(inputChannels), Arrays.hashCode(lfe));
    }

    @Override
    public String toString() {
        return "EncoderLayout[channelConfiguration=" + channelConfiguration + ", elementTypes="
                + Arrays.toString(elementTypes) + ", inputChannels=" + Arrays.toString(inputChannels) + "]";
    }
}

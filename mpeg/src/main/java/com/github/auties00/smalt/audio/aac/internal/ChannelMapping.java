package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;

import com.github.auties00.smalt.internal.limits.LimitChecks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Maps the channel elements of an AAC frame to output channels in WAVE order.
 *
 * <p>AAC codes channels as a sequence of single channel (SCE), channel pair (CPE) and LFE
 * elements in a fixed front-to-back order (center, left, right, surrounds, LFE for the 5.1
 * configuration). A mapping lists the element slots of a configuration, the loudspeaker each
 * coded channel feeds, and the output channel that receives it once the channels are reordered
 * by {@link Speaker} declaration order (the WAVE channel mask order).
 *
 * <p>Channel configurations match elements by their order within each element type, ignoring
 * instance tags; program configurations match them by instance tag.
 */
public final class ChannelMapping {
    /**
     * The element types of each channel configuration in coding order, indexed by configuration.
     */
    private static final int[][] CONFIGURATION_ELEMENTS = {
            null,
            {ElementId.SCE},
            {ElementId.CPE},
            {ElementId.SCE, ElementId.CPE},
            {ElementId.SCE, ElementId.CPE, ElementId.SCE},
            {ElementId.SCE, ElementId.CPE, ElementId.CPE},
            {ElementId.SCE, ElementId.CPE, ElementId.CPE, ElementId.LFE},
            {ElementId.SCE, ElementId.CPE, ElementId.CPE, ElementId.CPE, ElementId.LFE},
            null, null, null,
            {ElementId.SCE, ElementId.CPE, ElementId.CPE, ElementId.SCE, ElementId.LFE},
            {ElementId.SCE, ElementId.CPE, ElementId.CPE, ElementId.CPE, ElementId.LFE},
            {ElementId.SCE, ElementId.CPE, ElementId.CPE, ElementId.CPE, ElementId.CPE, ElementId.SCE, ElementId.LFE,
                    ElementId.LFE, ElementId.SCE, ElementId.CPE, ElementId.CPE, ElementId.SCE, ElementId.CPE,
                    ElementId.SCE, ElementId.SCE, ElementId.CPE},
            {ElementId.SCE, ElementId.CPE, ElementId.CPE, ElementId.LFE, ElementId.CPE}
    };

    /**
     * The loudspeaker of each coded channel of each channel configuration, in coding order.
     */
    private static final Speaker[][] CONFIGURATION_SPEAKERS = {
            null,
            {Speaker.FRONT_CENTER},
            {Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_CENTER},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
                    Speaker.LOW_FREQUENCY},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER, Speaker.FRONT_LEFT,
                    Speaker.FRONT_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.LOW_FREQUENCY},
            null, null, null,
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
                    Speaker.BACK_CENTER, Speaker.LOW_FREQUENCY},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.LOW_FREQUENCY},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER, Speaker.FRONT_LEFT,
                    Speaker.FRONT_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
                    Speaker.BACK_CENTER, Speaker.LOW_FREQUENCY, Speaker.LOW_FREQUENCY_2, Speaker.TOP_FRONT_CENTER,
                    Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_RIGHT, Speaker.TOP_SIDE_LEFT, Speaker.TOP_SIDE_RIGHT,
                    Speaker.TOP_CENTER, Speaker.TOP_BACK_LEFT, Speaker.TOP_BACK_RIGHT, Speaker.TOP_BACK_CENTER,
                    Speaker.BOTTOM_FRONT_CENTER, Speaker.BOTTOM_FRONT_LEFT, Speaker.BOTTOM_FRONT_RIGHT},
            {Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
                    Speaker.LOW_FREQUENCY, Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_RIGHT}
    };

    /**
     * The element type of each slot, in coding order.
     */
    private final int[] slotTypes;

    /**
     * The instance tag each slot matches, or {@code -1} when slots match by order.
     */
    private final int[] slotTags;

    /**
     * The coded channel index of the first channel of each slot.
     */
    private final int[] slotChannels;

    /**
     * The output channel of each coded channel.
     */
    private final int[] outputIndex;

    /**
     * The output layout.
     */
    private final ChannelLayout layout;

    /**
     * Builds a mapping.
     *
     * @param slotTypes the element type of each slot
     * @param slotTags the instance tag of each slot, {@code -1} to match by order
     * @param speakers the loudspeaker of each coded channel
     * @param limits the resource limits
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     */
    private ChannelMapping(int[] slotTypes, int[] slotTags, Speaker[] speakers, MediaLimits limits) {
        LimitChecks.checkChannels(limits, speakers.length);
        this.slotTypes = slotTypes;
        this.slotTags = slotTags;
        this.slotChannels = new int[slotTypes.length];
        var channel = 0;
        for (var i = 0; i < slotTypes.length; i++) {
            slotChannels[i] = channel;
            channel += slotTypes[i] == ElementId.CPE ? 2 : 1;
        }
        var order = new Integer[speakers.length];
        for (var i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (first, second) -> {
            var compare = Integer.compare(speakers[first].ordinal(), speakers[second].ordinal());
            return compare != 0 ? compare : Integer.compare(first, second);
        });
        this.outputIndex = new int[speakers.length];
        var ordered = new ArrayList<Speaker>(speakers.length);
        for (var output = 0; output < order.length; output++) {
            outputIndex[order[output]] = output;
            ordered.add(speakers[order[output]]);
        }
        this.layout = new ChannelLayout(ordered);
    }

    /**
     * Returns the mapping of a channel configuration.
     *
     * @param configuration the channel configuration, from 1 to 14 and not reserved
     * @param limits the resource limits
     * @return the mapping
     */
    public static ChannelMapping ofConfiguration(int configuration, MediaLimits limits) {
        var types = CONFIGURATION_ELEMENTS[configuration];
        var tags = new int[types.length];
        Arrays.fill(tags, -1);
        return new ChannelMapping(types, tags, CONFIGURATION_SPEAKERS[configuration], limits);
    }

    /**
     * Returns the mapping a program configuration describes.
     *
     * <p>Loudspeakers are assigned from the channel positions each list implies, whatever mix
     * of single and pair elements carries them. Front channels: an odd count starts with the
     * center; the remaining channels form left and right pairs from the center outwards, the
     * outermost pair feeding front left and right and the next one the left and right of
     * center. Side channels: the first pair feeds the side speakers. Back channels: an odd count
     * ends with the back center; the last pair feeds back left and right and the one before it
     * the side speakers when no side pair did. LFE channels feed the LFE speaker. Any further
     * channel feeds {@link Speaker#UNKNOWN} and is output after the positioned channels, in
     * coding order.
     *
     * @param config the program configuration
     * @param limits the resource limits
     * @return the mapping
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     */
    public static ChannelMapping ofProgram(ProgramConfig config, MediaLimits limits) {
        LimitChecks.checkChannels(limits, config.channels());
        var types = new ArrayList<Integer>();
        var tags = new ArrayList<Integer>();
        var speakers = new ArrayList<Speaker>();
        var used = new boolean[Speaker.values().length];
        var front = addElements(config.front(), types, tags);
        var frontPairs = front / 2;
        for (var channel = 0; channel < front; channel++) {
            if (front % 2 == 1 && channel == 0) {
                addSingle(speakers, used, Speaker.FRONT_CENTER);
                continue;
            }
            var pair = (channel - front % 2) / 2;
            var left = (channel - front % 2) % 2 == 0;
            var fromOutside = frontPairs - 1 - pair;
            if (fromOutside == 0) {
                addSingle(speakers, used, left ? Speaker.FRONT_LEFT : Speaker.FRONT_RIGHT);
            } else if (fromOutside == 1) {
                addSingle(speakers, used, left ? Speaker.FRONT_LEFT_OF_CENTER : Speaker.FRONT_RIGHT_OF_CENTER);
            } else {
                addSingle(speakers, used, Speaker.UNKNOWN);
            }
        }
        var side = addElements(config.side(), types, tags);
        for (var channel = 0; channel < side; channel++) {
            addSingle(speakers, used, channel < 2 && side >= 2
                    ? (channel == 0 ? Speaker.SIDE_LEFT : Speaker.SIDE_RIGHT) : Speaker.UNKNOWN);
        }
        var back = addElements(config.back(), types, tags);
        var backPairs = back / 2;
        for (var channel = 0; channel < back; channel++) {
            if (back % 2 == 1 && channel == back - 1) {
                addSingle(speakers, used, Speaker.BACK_CENTER);
                continue;
            }
            var pair = channel / 2;
            var left = channel % 2 == 0;
            var fromLast = backPairs - 1 - pair;
            if (fromLast == 0) {
                addSingle(speakers, used, left ? Speaker.BACK_LEFT : Speaker.BACK_RIGHT);
            } else if (fromLast == 1 && side < 2) {
                addSingle(speakers, used, left ? Speaker.SIDE_LEFT : Speaker.SIDE_RIGHT);
            } else {
                addSingle(speakers, used, Speaker.UNKNOWN);
            }
        }
        for (var tag : config.lfe()) {
            types.add(ElementId.LFE);
            tags.add(tag);
            addSingle(speakers, used, Speaker.LOW_FREQUENCY);
        }
        return new ChannelMapping(toArray(types), toArray(tags), speakers.toArray(Speaker[]::new), limits);
    }

    /**
     * Appends the slots of an element list and counts its channels.
     *
     * @param elements the channel elements of one program configuration position
     * @param types the slot types, appended to
     * @param tags the slot tags, appended to
     * @return the number of channels the elements carry
     */
    private static int addElements(List<ProgramConfig.Element> elements, List<Integer> types, List<Integer> tags) {
        var channels = 0;
        for (var element : elements) {
            types.add(element.pair() ? ElementId.CPE : ElementId.SCE);
            tags.add(element.tag());
            channels += element.pair() ? 2 : 1;
        }
        return channels;
    }

    /**
     * Returns the mapping of an element sequence whose elements name their loudspeakers, as the elements of
     * ER BSAC do.
     *
     * <p>A loudspeaker that an earlier element took already is replaced by {@link Speaker#UNKNOWN}, a pair as a
     * whole.
     *
     * @param types the element types in coding order
     * @param speakers the preferred loudspeaker of each coded channel, two per pair element
     * @param limits the resource limits
     * @return the mapping
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     */
    public static ChannelMapping ofSpeakers(int[] types, Speaker[] speakers, MediaLimits limits) {
        LimitChecks.checkChannels(limits, Math.max(speakers.length, 1));
        var assigned = new ArrayList<Speaker>(speakers.length);
        var used = new boolean[Speaker.values().length];
        var channel = 0;
        for (var type : types) {
            if (type == ElementId.CPE) {
                addPair(assigned, used, speakers[channel], speakers[channel + 1]);
                channel += 2;
            } else {
                addSingle(assigned, used, speakers[channel++]);
            }
        }
        var tags = new int[types.length];
        Arrays.fill(tags, -1);
        return new ChannelMapping(types.clone(), tags, assigned.toArray(Speaker[]::new), limits);
    }

    /**
     * Returns a mapping for an element sequence no configuration announced.
     *
     * <p>A sequence equal to that of a channel configuration takes that configuration's mapping
     * (the 7.1 configuration 12 wins over configuration 7, which codes the same elements);
     * any other sequence feeds the first single channel element to the center, the first pair
     * to front left and right, the first LFE element to the LFE speaker, and every other channel
     * to {@link Speaker#UNKNOWN}.
     *
     * @param types the element types in coding order
     * @param limits the resource limits
     * @return the mapping
     * @throws MediaException.LimitExceeded if the channel count exceeds the limits
     */
    public static ChannelMapping infer(int[] types, MediaLimits limits) {
        for (var configuration : new int[]{1, 2, 3, 4, 5, 6, 12, 11, 14}) {
            if (Arrays.equals(CONFIGURATION_ELEMENTS[configuration], types)) {
                return ofConfiguration(configuration, limits);
            }
        }
        var channels = 0;
        for (var type : types) {
            channels += type == ElementId.CPE ? 2 : 1;
        }
        LimitChecks.checkChannels(limits, Math.max(channels, 1));
        var speakers = new ArrayList<Speaker>();
        var used = new boolean[Speaker.values().length];
        var tags = new int[types.length];
        Arrays.fill(tags, -1);
        for (var type : types) {
            switch (type) {
                case ElementId.CPE -> addPair(speakers, used, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT);
                case ElementId.LFE -> addSingle(speakers, used, Speaker.LOW_FREQUENCY);
                default -> addSingle(speakers, used, Speaker.FRONT_CENTER);
            }
        }
        return new ChannelMapping(types.clone(), tags, speakers.toArray(Speaker[]::new), limits);
    }

    /**
     * Returns the number of coded channels.
     *
     * @return the channel count
     */
    public int channels() {
        return outputIndex.length;
    }

    /**
     * Returns the output layout.
     *
     * @return the layout, in WAVE order
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * Returns the output channel that receives a coded channel.
     *
     * @param codedChannel the coded channel index
     * @return the output channel index
     */
    public int outputIndex(int codedChannel) {
        return outputIndex[codedChannel];
    }

    /**
     * Returns the loudspeaker a coded channel feeds.
     *
     * @param codedChannel the coded channel index
     * @return the speaker
     */
    public Speaker speaker(int codedChannel) {
        return layout.speakers().get(outputIndex[codedChannel]);
    }

    /**
     * Returns the element type of each slot, in coding order.
     *
     * @return a new array of {@link ElementId} values
     */
    public int[] elementTypes() {
        return slotTypes.clone();
    }

    /**
     * Returns the coded channel index of the first channel an element feeds.
     *
     * @param type the element type
     * @param tag the element instance tag
     * @param ordinal the number of elements of the same type that precede it in the frame
     * @return the coded channel index, or {@code -1} when no slot of this mapping takes the element
     */
    public int channelOf(int type, int tag, int ordinal) {
        var seen = 0;
        for (var slot = 0; slot < slotTypes.length; slot++) {
            if (slotTypes[slot] != type) {
                continue;
            }
            if (slotTags[slot] < 0) {
                if (seen++ == ordinal) {
                    return slotChannels[slot];
                }
            } else if (slotTags[slot] == tag) {
                return slotChannels[slot];
            }
        }
        return -1;
    }

    /**
     * Returns whether an element sequence fills exactly the slots of this mapping.
     *
     * @param types the element types of a frame, in coding order
     * @param tags the element instance tags, in coding order
     * @param count the number of elements
     * @return {@code true} when every element has a distinct slot and every slot is filled
     */
    public boolean matches(int[] types, int[] tags, int count) {
        if (count != slotTypes.length) {
            return false;
        }
        var filled = new boolean[slotTypes.length];
        var ordinals = new int[4];
        for (var i = 0; i < count; i++) {
            var channel = channelOf(types[i], tags[i], ordinals[types[i]]++);
            if (channel < 0) {
                return false;
            }
            var slot = slotOfChannel(channel);
            if (filled[slot]) {
                return false;
            }
            filled[slot] = true;
        }
        return true;
    }

    /**
     * Returns the slot whose first coded channel is {@code channel}.
     *
     * @param channel the coded channel index
     * @return the slot index
     */
    private int slotOfChannel(int channel) {
        for (var slot = 0; slot < slotChannels.length; slot++) {
            if (slotChannels[slot] == channel) {
                return slot;
            }
        }
        throw new IllegalStateException("no slot starts at channel " + channel);
    }

    /**
     * Appends one channel, falling back to {@link Speaker#UNKNOWN} if its speaker is taken.
     *
     * @param speakers the speakers assigned so far
     * @param used which speakers are taken
     * @param speaker the preferred speaker
     */
    private static void addSingle(List<Speaker> speakers, boolean[] used, Speaker speaker) {
        if (speaker != Speaker.UNKNOWN && !used[speaker.ordinal()]) {
            used[speaker.ordinal()] = true;
            speakers.add(speaker);
        } else {
            speakers.add(Speaker.UNKNOWN);
        }
    }

    /**
     * Appends a channel pair, falling back to {@link Speaker#UNKNOWN} if its speakers are taken.
     *
     * @param speakers the speakers assigned so far
     * @param used which speakers are taken
     * @param left the preferred left speaker
     * @param right the preferred right speaker
     */
    private static void addPair(List<Speaker> speakers, boolean[] used, Speaker left, Speaker right) {
        if (left != Speaker.UNKNOWN && !used[left.ordinal()] && !used[right.ordinal()]) {
            used[left.ordinal()] = true;
            used[right.ordinal()] = true;
            speakers.add(left);
            speakers.add(right);
        } else {
            speakers.add(Speaker.UNKNOWN);
            speakers.add(Speaker.UNKNOWN);
        }
    }

    /**
     * Copies a list of integers into an array.
     *
     * @param values the integers to copy
     * @return the array
     */
    private static int[] toArray(List<Integer> values) {
        var result = new int[values.size()];
        for (var i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }
}

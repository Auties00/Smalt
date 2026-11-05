package com.github.auties00.smalt.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes which {@link Speaker} each channel of an audio signal feeds, in channel order.
 *
 * <p>Channel {@code i} of an {@link AudioBuffer} feeds {@code speakers().get(i)}. Codecs whose
 * native order differs from the canonical WAVE order (AAC, Vorbis, Opus) either reorder their
 * output or declare their native order here; mixers rely only on this mapping.
 *
 * <p>A layout with {@link #ambisonics()} carries an ambisonic sound field: its channels of
 * {@link Speaker#UNKNOWN} position are the ambisonic components that {@link Ambisonics} describes, in channel
 * order, and its other channels are non-diegetic (head-locked) channels that play at their speaker position
 * whatever the orientation of the listener, such as the stereo pair of Opus mapping family 2.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param speakers the speaker fed by each channel, in channel order; not empty
 * @param ambisonics the ambisonic components carried by the channels of unknown position, or empty for a
 * layout of loudspeaker feeds
 */
public record ChannelLayout(List<Speaker> speakers, Optional<Ambisonics> ambisonics) {
    /**
     * One channel feeding the front center.
     */
    public static final ChannelLayout MONO = new ChannelLayout(List.of(Speaker.FRONT_CENTER));

    /**
     * Two channels feeding front left and front right.
     */
    public static final ChannelLayout STEREO = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT));

    /**
     * The 5.1 layout with back surrounds, in WAVE order: FL, FR, FC, LFE, BL, BR.
     */
    public static final ChannelLayout SURROUND_5_1 = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
            Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT));

    /**
     * The 7.1 layout in WAVE order: FL, FR, FC, LFE, BL, BR, SL, SR.
     */
    public static final ChannelLayout SURROUND_7_1 = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
            Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
            Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT));

    /**
     * Validates and freezes the speaker list.
     *
     * @throws NullPointerException if {@code speakers} or an element is {@code null}
     * @throws IllegalArgumentException if {@code speakers} is empty, or {@code ambisonics} is present and its
     * channel count differs from the number of {@link Speaker#UNKNOWN} speakers
     */
    public ChannelLayout {
        speakers = List.copyOf(Objects.requireNonNull(speakers, "speakers"));
        Objects.requireNonNull(ambisonics, "ambisonics");
        if (speakers.isEmpty()) {
            throw new IllegalArgumentException("a channel layout needs at least one channel");
        }
        if (ambisonics.isPresent()) {
            var unknown = Collections.frequency(speakers, Speaker.UNKNOWN);
            if (unknown != ambisonics.get().channels()) {
                throw new IllegalArgumentException(ambisonics.get().channels() + " ambisonic components need as many"
                        + " channels of unknown position, not " + unknown);
            }
        }
    }

    /**
     * Constructs a layout of loudspeaker feeds, without ambisonics.
     *
     * @param speakers the speaker fed by each channel, in channel order; not empty
     * @throws NullPointerException if {@code speakers} or an element is {@code null}
     * @throws IllegalArgumentException if {@code speakers} is empty
     */
    public ChannelLayout(List<Speaker> speakers) {
        this(speakers, Optional.empty());
    }

    /**
     * Returns the layout of every component of an ambisonic order in ACN order with SN3D normalization, the
     * AmbiX convention.
     *
     * @param order the ambisonic order, from 0 to {@value Ambisonics#MAX_ORDER}
     * @return the layout of {@code (order + 1)^2} channels
     * @throws IllegalArgumentException if {@code order} is out of range
     */
    public static ChannelLayout ofAmbisonics(int order) {
        return ofAmbisonics(Ambisonics.of(order));
    }

    /**
     * Returns the layout of an ambisonic sound field alone.
     *
     * @param ambisonics the components, one channel each
     * @return the layout
     */
    public static ChannelLayout ofAmbisonics(Ambisonics ambisonics) {
        return new ChannelLayout(Collections.nCopies(ambisonics.channels(), Speaker.UNKNOWN), Optional.of(ambisonics));
    }

    /**
     * Returns the layout of an ambisonic sound field followed by non-diegetic channels, such as the head-locked
     * stereo pair {@code List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT)}.
     *
     * @param ambisonics the components, one channel each
     * @param nonDiegetic the speakers of the channels that follow the components, possibly empty
     * @return the layout
     * @throws IllegalArgumentException if {@code nonDiegetic} holds {@link Speaker#UNKNOWN}
     */
    public static ChannelLayout ofAmbisonics(Ambisonics ambisonics, List<Speaker> nonDiegetic) {
        if (nonDiegetic.contains(Speaker.UNKNOWN)) {
            throw new IllegalArgumentException("a non-diegetic channel needs a speaker position");
        }
        var speakers = new ArrayList<Speaker>(ambisonics.channels() + nonDiegetic.size());
        speakers.addAll(Collections.nCopies(ambisonics.channels(), Speaker.UNKNOWN));
        speakers.addAll(nonDiegetic);
        return new ChannelLayout(speakers, Optional.of(ambisonics));
    }

    /**
     * Returns the number of channels.
     *
     * @return the channel count, positive
     */
    public int channels() {
        return speakers.size();
    }

    /**
     * Returns the index of the channel feeding {@code speaker}.
     *
     * @param speaker the speaker to find
     * @return the channel index, or {@code -1} when no channel feeds it
     */
    public int indexOf(Speaker speaker) {
        return speakers.indexOf(speaker);
    }

    /**
     * Returns the index of the channel carrying an ambisonic component.
     *
     * @param component the ACN of the component
     * @return the channel index, or {@code -1} when the layout has no ambisonics or no channel carries it
     */
    public int indexOfComponent(int component) {
        if (ambisonics.isEmpty()) {
            return -1;
        }
        var k = ambisonics.get().indexOf(component);
        if (k < 0) {
            return -1;
        }
        for (var channel = 0; channel < speakers.size(); channel++) {
            if (speakers.get(channel) == Speaker.UNKNOWN && k-- == 0) {
                return channel;
            }
        }
        return -1;
    }

    /**
     * Returns the conventional layout for a bare channel count.
     *
     * <p>Counts 1 through 8 map to mono, stereo, 3.0, quad, 5.0, 5.1, 6.1 and 7.1 in WAVE order;
     * larger counts map to channels with {@link Speaker#UNKNOWN} positions and no ambisonics.
     *
     * @param channels the number of channels; positive
     * @return the layout
     * @throws IllegalArgumentException if {@code channels} is not positive
     */
    public static ChannelLayout defaultFor(int channels) {
        return switch (channels) {
            case 1 -> MONO;
            case 2 -> STEREO;
            case 3 -> new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER));
            case 4 -> new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT));
            case 5 -> new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT));
            case 6 -> SURROUND_5_1;
            case 7 -> new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                    Speaker.LOW_FREQUENCY, Speaker.BACK_CENTER, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT));
            case 8 -> SURROUND_7_1;
            default -> {
                if (channels <= 0) {
                    throw new IllegalArgumentException("invalid channel count " + channels);
                }
                yield new ChannelLayout(Collections.nCopies(channels, Speaker.UNKNOWN));
            }
        };
    }
}

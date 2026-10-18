package com.github.auties00.smalt.audio;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Describes which {@link Speaker} each channel of an audio signal feeds, in channel order.
 *
 * <p>Channel {@code i} of an {@link AudioBuffer} feeds {@code speakers().get(i)}. Codecs whose
 * native order differs from the canonical WAVE order (AAC, Vorbis, Opus) either reorder their
 * output or declare their native order here; mixers rely only on this mapping.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param speakers the speaker fed by each channel, in channel order; not empty
 */
public record ChannelLayout(List<Speaker> speakers) {
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
     * @throws NullPointerException     if {@code speakers} or an element is {@code null}
     * @throws IllegalArgumentException if {@code speakers} is empty
     */
    public ChannelLayout {
        speakers = List.copyOf(Objects.requireNonNull(speakers, "speakers"));
        if (speakers.isEmpty()) {
            throw new IllegalArgumentException("a channel layout needs at least one channel");
        }
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
     * Returns the conventional layout for a bare channel count.
     *
     * <p>Counts 1 through 8 map to mono, stereo, 3.0, quad, 5.0, 5.1, 6.1 and 7.1 in WAVE order;
     * larger counts map to channels with {@link Speaker#UNKNOWN} positions.
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

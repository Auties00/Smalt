package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.codec.Ac3Configs;

import java.util.ArrayList;
import java.util.List;

/**
 * Names the loudspeaker each coded channel of an AC-3 frame or E-AC-3 substream feeds, from its
 * audio coding mode or its custom channel map (ATSC A/52 table 5.8 and Annex E table E2.5).
 *
 * <p>Surround channels map the way the 5.1 layouts of other formats do: the two surrounds of 2/2
 * and 3/2 to the side speakers, the single surround of 2/1 and 3/1 to the back center; the rear
 * surround pair of a channel extension maps to the back speakers, so a 7.1 program comes out as
 * front, center, LFE, back and side pairs. The surround direct pair, the wide pair and the second
 * LFE map to {@link Speaker#SURROUND_DIRECT_LEFT} and {@link Speaker#SURROUND_DIRECT_RIGHT},
 * {@link Speaker#WIDE_LEFT} and {@link Speaker#WIDE_RIGHT}, and {@link Speaker#LOW_FREQUENCY_2}.
 */
public final class ChannelMap {
    /**
     * Prevents instantiation.
     */
    private ChannelMap() {
        throw new AssertionError();
    }

    /**
     * Returns the speakers of the coded channels of a frame, LFE last.
     *
     * <p>A dependent substream with a custom channel map takes its locations from the map when the
     * map names exactly as many channels as the substream codes; otherwise, and for every other
     * frame, the audio coding mode and the LFE flag decide.
     *
     * @param header the frame header
     * @return the speaker of each coded channel
     */
    public static Speaker[] speakers(FrameHeader header) {
        var coded = header.channels + (header.lfe ? 1 : 0);
        if (header.channelMap >= 0) {
            var speakers = new Speaker[coded];
            var count = 0;
            for (var bit = 0; bit < 16; bit++) {
                if ((header.channelMap & (0x8000 >>> bit)) == 0) {
                    continue;
                }
                for (var speaker : Ac3Configs.LOCATIONS[bit]) {
                    if (count < coded) {
                        speakers[count] = speaker;
                    }
                    count++;
                }
            }
            if (count == coded) {
                return speakers;
            }
        }
        var mode = Ac3Configs.MODES[header.acmod];
        var speakers = new Speaker[coded];
        System.arraycopy(mode, 0, speakers, 0, mode.length);
        if (header.lfe) {
            speakers[coded - 1] = Speaker.LOW_FREQUENCY;
        }
        return speakers;
    }

    /**
     * Maps a layout onto the closest channel configuration an AC-3 frame or an E-AC-3 independent
     * substream codes, in WAVE order.
     *
     * <p>The front pair, the center, the surround pair (side or back) or the single back center,
     * and the LFE are kept when present, everything else is dropped; a layout of bare channels
     * maps to the default layout of its channel count, at most 5.1, and a layout without front
     * pair or center codes the front pair.
     *
     * @param layout the requested layout
     * @return the codable layout
     */
    public static ChannelLayout codable(ChannelLayout layout) {
        var speakers = layout.speakers();
        if (speakers.stream().allMatch(speaker -> speaker == Speaker.UNKNOWN)) {
            layout = ChannelLayout.defaultFor(Math.min(6, layout.channels()));
            speakers = layout.speakers();
        }
        var left = speakers.contains(Speaker.FRONT_LEFT);
        var right = speakers.contains(Speaker.FRONT_RIGHT);
        var center = speakers.contains(Speaker.FRONT_CENTER);
        var lfe = speakers.contains(Speaker.LOW_FREQUENCY);
        var sides = speakers.contains(Speaker.SIDE_LEFT) && speakers.contains(Speaker.SIDE_RIGHT);
        var backs = speakers.contains(Speaker.BACK_LEFT) && speakers.contains(Speaker.BACK_RIGHT);
        var backCenter = speakers.contains(Speaker.BACK_CENTER);
        var front = left && right;
        var result = new ArrayList<Speaker>();
        if (!front && !center) {
            front = true;
        }
        if (front) {
            result.add(Speaker.FRONT_LEFT);
            result.add(Speaker.FRONT_RIGHT);
        }
        if (center) {
            result.add(Speaker.FRONT_CENTER);
        }
        if (lfe) {
            result.add(Speaker.LOW_FREQUENCY);
        }
        if (front) {
            if (sides) {
                result.add(Speaker.SIDE_LEFT);
                result.add(Speaker.SIDE_RIGHT);
            } else if (backs) {
                result.add(Speaker.BACK_LEFT);
                result.add(Speaker.BACK_RIGHT);
            } else if (backCenter) {
                result.add(Speaker.BACK_CENTER);
            }
        }
        return new ChannelLayout(List.copyOf(result));
    }

    /**
     * The custom channel map of the dependent substream of a 7.1 program (A/52 Annex E table
     * E2.5): the left and right surround locations (bits 3 and 4) and the left and right rear
     * surround pair (bit 6), coded in that order.
     */
    public static final int EXTENSION_MAP = 0x8000 >>> 3 | 0x8000 >>> 4 | 0x8000 >>> 6;

    /**
     * Maps a layout onto the closest channel configuration E-AC-3 codes, in WAVE order: the core
     * {@link #codable(ChannelLayout)} keeps and every other channel with a custom channel map
     * location (see {@link ProgramPlan}), which dependent substreams carry. A layout of bare
     * channels beyond six codes 7.1 from eight channels on, otherwise as {@link #codable(ChannelLayout)}
     * maps it.
     *
     * @param layout the requested layout
     * @return the codable layout
     */
    public static ChannelLayout codableEnhanced(ChannelLayout layout) {
        if (layout.speakers().stream().allMatch(speaker -> speaker == Speaker.UNKNOWN)) {
            return layout.channels() >= 8 ? ChannelLayout.SURROUND_7_1 : codable(layout);
        }
        var core = codable(layout);
        var speakers = new ArrayList<>(core.speakers());
        speakers.addAll(ProgramPlan.extras(layout, core));
        speakers.sort(null);
        return new ChannelLayout(List.copyOf(speakers));
    }

    /**
     * Returns whether a layout is a program beyond 5.1 that E-AC-3 codes with dependent
     * substreams: one with a channel outside the core {@link #codable(ChannelLayout)} keeps that
     * has a custom channel map location.
     *
     * @param layout the requested layout
     * @return {@code true} for 7.1, 7.0 and the larger programs
     */
    public static boolean extended(ChannelLayout layout) {
        return !ProgramPlan.extras(layout, codable(layout)).isEmpty();
    }

    /**
     * Returns the full-bandwidth speakers of a codable layout in the coded order of A/52 table
     * 5.8; the length is the channel count of the audio coding mode
     * {@link #codingMode(ChannelLayout)} returns.
     *
     * @param layout a layout {@link #codable(ChannelLayout)} returns
     * @return the speakers, LFE excluded
     */
    public static Speaker[] codedSpeakers(ChannelLayout layout) {
        var speakers = layout.speakers();
        var front = speakers.contains(Speaker.FRONT_LEFT);
        var center = speakers.contains(Speaker.FRONT_CENTER);
        var sides = speakers.contains(Speaker.SIDE_LEFT);
        var backs = speakers.contains(Speaker.BACK_LEFT);
        var backCenter = speakers.contains(Speaker.BACK_CENTER);
        if (!front) {
            return new Speaker[]{Speaker.FRONT_CENTER};
        }
        if (sides || backs) {
            var left = sides ? Speaker.SIDE_LEFT : Speaker.BACK_LEFT;
            var right = sides ? Speaker.SIDE_RIGHT : Speaker.BACK_RIGHT;
            return center ? new Speaker[]{Speaker.FRONT_LEFT, Speaker.FRONT_CENTER, Speaker.FRONT_RIGHT, left, right}
                    : new Speaker[]{Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, left, right};
        }
        if (backCenter) {
            return center ? new Speaker[]{Speaker.FRONT_LEFT, Speaker.FRONT_CENTER, Speaker.FRONT_RIGHT,
                    Speaker.BACK_CENTER} : new Speaker[]{Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_CENTER};
        }
        return center ? new Speaker[]{Speaker.FRONT_LEFT, Speaker.FRONT_CENTER, Speaker.FRONT_RIGHT}
                : new Speaker[]{Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT};
    }

    /**
     * Returns the number of full-bandwidth channels of a layout: every channel but the LFE.
     *
     * @param layout a channel layout
     * @return the channel count, LFE excluded
     */
    public static int fullBandwidthChannels(ChannelLayout layout) {
        return layout.channels() - (layout.indexOf(Speaker.LOW_FREQUENCY) >= 0 ? 1 : 0);
    }

    /**
     * Returns the bit rate an encoder uses for a layout when none is configured: 96, 192, 320, 384
     * and 448 kbit/s for one to five full-bandwidth channels, 768 kbit/s for six or seven, which only
     * an E-AC-3 program beyond 5.1 has, and 96 kbit/s more for each full-bandwidth channel beyond.
     *
     * @param layout a layout {@link #codable(ChannelLayout)} or {@link #codableEnhanced(ChannelLayout)}
     * returns
     * @return the bit rate in bits per second
     */
    public static int defaultBitrate(ChannelLayout layout) {
        return switch (fullBandwidthChannels(layout)) {
            case 1 -> 96_000;
            case 2 -> 192_000;
            case 3 -> 320_000;
            case 4 -> 384_000;
            case 5 -> 448_000;
            case 6, 7 -> 768_000;
            default -> 768_000 + 96_000 * (fullBandwidthChannels(layout) - 7);
        };
    }

    /**
     * Returns the audio coding mode {@code acmod} of a codable layout (A/52 table 5.8).
     *
     * @param layout a layout {@link #codable(ChannelLayout)} returns
     * @return the mode, from 1 to 7
     */
    public static int codingMode(ChannelLayout layout) {
        var speakers = layout.speakers();
        var center = speakers.contains(Speaker.FRONT_CENTER);
        if (!speakers.contains(Speaker.FRONT_LEFT)) {
            return 1;
        }
        if (speakers.contains(Speaker.SIDE_LEFT) || speakers.contains(Speaker.BACK_LEFT)) {
            return center ? 7 : 6;
        }
        if (speakers.contains(Speaker.BACK_CENTER)) {
            return center ? 5 : 4;
        }
        return center ? 3 : 2;
    }
}

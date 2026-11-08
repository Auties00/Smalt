package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;

import java.util.ArrayList;
import java.util.List;

/**
 * Divides a program among the substreams that code it: one AC-3 frame or E-AC-3 independent
 * substream for up to 5.1 channels and, for an E-AC-3 program beyond 5.1, dependent substreams with
 * custom channel maps (ETSI TS 102 366 clause E.2.8 and table E.1.4).
 *
 * <p>The independent substream codes the 5.1 core of the program: the front pair, the center, the
 * surround pair (the side pair, else the back pair) or the single back center, and the LFE, as
 * {@link ChannelMap#codable(ChannelLayout)} keeps them. Every other channel with a channel map
 * location is an extra channel: the back pair beside a side pair, the back center beside a
 * surround pair, the left and right of center pair, the top center, the surround direct pair, the
 * wide pair, the top front pair, the top front center, the top back pair and the second LFE. A
 * pair is coded only when both its channels are present, and the second LFE only with the first.
 *
 * <p>So that a decoder rendering only the core plays the extra channels too, extra channels are mixed
 * into the core channels nearest them, and every core channel that receives a mix is scaled by -3 dB
 * (see {@link #mixes(Speaker, boolean, boolean)}). The dependent substreams carry the extra channels
 * and the unmixed core channels that received a mix, in channel map order; a decoder that renders
 * them replaces those core channels with them (clause E.2.8.2). A dependent substream codes at most
 * five full-bandwidth channels and an LFE channel, and decoders such as ffmpeg and Windows Media
 * Foundation reproduce only programs of one, so an extra channel is mixed into the core only while
 * the dependent channels fit one dependent substream (see {@code mixed}); a program whose extra
 * channels alone exceed one takes as many dependent substreams as its dependent channels need,
 * every extra channel mixed in, never splitting a pair.
 *
 * @param substreams the substreams, the independent first
 */
public record ProgramPlan(List<Substream> substreams) {
    /**
     * The gain of each channel mixed into a core channel, and of the core channel itself: -3 dB,
     * which keeps the power of uncorrelated channels.
     */
    static final float MIX = (float) Math.sqrt(0.5);

    /**
     * The audio coding mode of each number of full-bandwidth channels of a dependent substream.
     */
    private static final int[] DEPENDENT_MODES = {0, 1, 2, 3, 6, 7};

    /**
     * The speakers of each channel map location, bit 0 (the most significant) first.
     */
    private static final Speaker[][] LOCATIONS = com.github.auties00.smalt.internal.codec.Ac3Configs.LOCATIONS;

    /**
     * One substream of a program.
     *
     * @param acmod the audio coding mode
     * @param lfe whether the substream codes an LFE channel
     * @param channelMap the custom channel map of a dependent substream, or {@code -1} for the
     * independent substream
     * @param sources the input channels each coded channel mixes, in coded order (LFE last)
     * @param weights the gain of each source
     * @param scales the gain applied to the mix of each coded channel
     */
    public record Substream(int acmod, boolean lfe, int channelMap, int[][] sources, float[][] weights,
                            float[] scales) {
        /**
         * {@return the number of full-bandwidth channels the substream codes}
         */
        public int fullBandwidthChannels() {
            return Ac3Tables.CHANNELS[acmod];
        }
    }

    /**
     * Returns the plan of an AC-3 program or an E-AC-3 program of up to 5.1 channels: one substream
     * coding the channels of a layout {@link ChannelMap#codable(ChannelLayout)} returns.
     *
     * @param layout a codable layout
     * @return the plan
     */
    public static ProgramPlan single(ChannelLayout layout) {
        var lfe = layout.speakers().contains(Speaker.LOW_FREQUENCY);
        var order = ChannelMap.codedSpeakers(layout);
        var coded = order.length + (lfe ? 1 : 0);
        var sources = new int[coded][];
        var weights = new float[coded][];
        var scales = new float[coded];
        for (var ch = 0; ch < order.length; ch++) {
            sources[ch] = new int[]{layout.indexOf(order[ch])};
            weights[ch] = new float[]{1};
            scales[ch] = 1;
        }
        if (lfe) {
            sources[coded - 1] = new int[]{layout.indexOf(Speaker.LOW_FREQUENCY)};
            weights[coded - 1] = new float[]{1};
            scales[coded - 1] = 1;
        }
        return new ProgramPlan(List.of(new Substream(ChannelMap.codingMode(layout), lfe, -1, sources, weights,
                scales)));
    }

    /**
     * Returns the plan of an E-AC-3 program.
     *
     * @param layout a layout {@link ChannelMap#codableEnhanced(ChannelLayout)} returns
     * @return the plan
     */
    public static ProgramPlan enhanced(ChannelLayout layout) {
        var core = ChannelMap.codable(layout);
        var extras = extras(layout, core);
        if (extras.isEmpty()) {
            return single(core);
        }
        var coreSpeakers = new ArrayList<Speaker>(List.of(ChannelMap.codedSpeakers(core)));
        var coreLfe = core.speakers().contains(Speaker.LOW_FREQUENCY);
        if (coreLfe) {
            coreSpeakers.add(Speaker.LOW_FREQUENCY);
        }
        var center = coreSpeakers.contains(Speaker.FRONT_CENTER);
        var surrounds = coreSpeakers.contains(Speaker.SIDE_LEFT) || coreSpeakers.contains(Speaker.BACK_LEFT);
        var coreLeftSurround = coreSpeakers.contains(Speaker.SIDE_LEFT) ? Speaker.SIDE_LEFT : Speaker.BACK_LEFT;
        var coreRightSurround = coreSpeakers.contains(Speaker.SIDE_RIGHT) ? Speaker.SIDE_RIGHT : Speaker.BACK_RIGHT;
        var coreSources = new ArrayList<List<Integer>>();
        var coreWeights = new ArrayList<List<Float>>();
        for (var speaker : coreSpeakers) {
            coreSources.add(new ArrayList<>(List.of(layout.indexOf(speaker))));
            coreWeights.add(new ArrayList<>(List.of(1f)));
        }
        var touched = new boolean[coreSpeakers.size()];
        for (var extra : mixed(extras, coreSpeakers, center, surrounds, coreLeftSurround, coreRightSurround)) {
            for (var mix : mixes(extra, center, surrounds)) {
                var target = switch (mix.target()) {
                    case SIDE_LEFT -> coreLeftSurround;
                    case SIDE_RIGHT -> coreRightSurround;
                    default -> mix.target();
                };
                var index = coreSpeakers.indexOf(target);
                coreSources.get(index).add(layout.indexOf(extra));
                coreWeights.get(index).add(mix.gain());
                touched[index] = true;
            }
        }
        var substreams = new ArrayList<Substream>();
        var coreChannels = coreSpeakers.size();
        var sources = new int[coreChannels][];
        var weights = new float[coreChannels][];
        var scales = new float[coreChannels];
        for (var ch = 0; ch < coreChannels; ch++) {
            scales[ch] = touched[ch] ? MIX : 1;
            sources[ch] = coreSources.get(ch).stream().mapToInt(Integer::intValue).toArray();
            weights[ch] = new float[sources[ch].length];
            for (var k = 0; k < weights[ch].length; k++) {
                weights[ch][k] = coreWeights.get(ch).get(k);
            }
        }
        substreams.add(new Substream(ChannelMap.codingMode(core), coreLfe, -1, sources, weights, scales));
        // the dependent channels in channel map order: the unmixed core channels that received a mix
        // and the extra channels
        var dependent = new ArrayList<Speaker>();
        for (var bit = 0; bit < 16; bit++) {
            for (var speaker : LOCATIONS[bit]) {
                var coreIndex = coreSpeakers.indexOf(speaker);
                if (coreIndex >= 0 ? touched[coreIndex] : extras.contains(speaker)) {
                    dependent.add(speaker);
                }
            }
        }
        var group = new ArrayList<Speaker>();
        var groupMap = 0;
        var groupLfe = false;
        var full = 0;
        for (var index = 0; index < dependent.size(); index++) {
            var speaker = dependent.get(index);
            var bit = bitOf(speaker);
            var width = LOCATIONS[bit].length;
            if (bit == 15) {
                groupLfe = true;
                groupMap |= 0x8000 >>> bit;
                continue;
            }
            if (full + width > 5) {
                substreams.add(dependentSubstream(layout, group, groupMap, groupLfe));
                group.clear();
                groupMap = 0;
                groupLfe = false;
                full = 0;
            }
            for (var k = 0; k < width; k++) {
                group.add(dependent.get(index + k));
            }
            index += width - 1;
            full += width;
            groupMap |= 0x8000 >>> bit;
        }
        if (full > 0 || groupLfe) {
            substreams.add(dependentSubstream(layout, group, groupMap, groupLfe));
        }
        return new ProgramPlan(List.copyOf(substreams));
    }

    /**
     * The channel map locations of the extra channels in the order {@code mixed} tries to mix them
     * into the core: the back pair, the back center, the surround direct pair, the left and right of
     * center pair, the wide pair, the top back pair, the top front pair, the top front center, the
     * top center and the second LFE.
     */
    private static final int[] MIX_PRIORITY = {6, 7, 9, 5, 10, 13, 11, 12, 8, 14};

    /**
     * Chooses the extra channels mixed into the core.
     *
     * <p>When the extra channels alone take more than the five full-bandwidth channels of one
     * dependent substream, every extra channel is mixed in. Otherwise extra channels are mixed in, in
     * the order of {@link #MIX_PRIORITY}, while the extra channels and the core channels receiving a
     * mix still fit one dependent substream, the most decoders reproduce.
     *
     * @param extras the extra channels
     * @param coreSpeakers the core channels in coded order
     * @param center whether the core has a center
     * @param surrounds whether the core has a surround pair
     * @param leftSurround the left surround of the core
     * @param rightSurround the right surround of the core
     * @return the extra channels to mix in
     */
    private static List<Speaker> mixed(List<Speaker> extras, List<Speaker> coreSpeakers, boolean center,
                                       boolean surrounds, Speaker leftSurround, Speaker rightSurround) {
        var full = extras.size();
        if (full > 5) {
            return extras;
        }
        var chosen = new ArrayList<Speaker>();
        var touched = new ArrayList<Speaker>();
        for (var bit : MIX_PRIORITY) {
            var location = List.of(LOCATIONS[bit]);
            if (!extras.containsAll(location)) {
                continue;
            }
            var candidate = new ArrayList<>(touched);
            for (var extra : location) {
                for (var mix : mixes(extra, center, surrounds)) {
                    var target = switch (mix.target()) {
                        case SIDE_LEFT -> leftSurround;
                        case SIDE_RIGHT -> rightSurround;
                        default -> mix.target();
                    };
                    if (target != Speaker.LOW_FREQUENCY && !candidate.contains(target)) {
                        candidate.add(target);
                    }
                }
            }
            if (full + candidate.size() <= 5) {
                touched = candidate;
                chosen.addAll(location);
            }
        }
        return chosen;
    }

    /**
     * Builds a dependent substream coding a group of channels.
     *
     * @param layout the program layout
     * @param group the full-bandwidth channels in channel map order
     * @param channelMap the channel map
     * @param lfe whether the substream codes the LFE channel
     * @return the substream
     */
    private static Substream dependentSubstream(ChannelLayout layout, List<Speaker> group, int channelMap, boolean lfe) {
        var coded = group.size() + (lfe ? 1 : 0);
        var sources = new int[coded][];
        var weights = new float[coded][];
        var scales = new float[coded];
        for (var ch = 0; ch < group.size(); ch++) {
            sources[ch] = new int[]{layout.indexOf(group.get(ch))};
            weights[ch] = new float[]{1};
            scales[ch] = 1;
        }
        if (lfe) {
            sources[coded - 1] = new int[]{layout.indexOf(Speaker.LOW_FREQUENCY)};
            weights[coded - 1] = new float[]{1};
            scales[coded - 1] = 1;
        }
        return new Substream(DEPENDENT_MODES[group.size()], lfe, channelMap, sources, weights, scales);
    }

    /**
     * Returns the channel map location of a speaker.
     *
     * @param speaker the speaker to locate
     * @return the bit, 0 for the left channel to 15 for the LFE, or {@code -1}
     */
    static int bitOf(Speaker speaker) {
        for (var bit = 0; bit < 16; bit++) {
            for (var candidate : LOCATIONS[bit]) {
                if (candidate == speaker) {
                    return bit;
                }
            }
        }
        return -1;
    }

    /**
     * Returns the extra channels of a layout: those beyond its core with a channel map location,
     * pairs only when complete and the second LFE only with the first.
     *
     * @param layout the program layout
     * @param core the core of the layout
     * @return the extra speakers
     */
    static List<Speaker> extras(ChannelLayout layout, ChannelLayout core) {
        var speakers = layout.speakers();
        var extras = new ArrayList<Speaker>();
        for (var bit = 5; bit < 15; bit++) {
            var location = LOCATIONS[bit];
            var present = true;
            var inCore = false;
            for (var speaker : location) {
                present &= speakers.contains(speaker);
                inCore |= core.speakers().contains(speaker);
            }
            if (!present || inCore) {
                continue;
            }
            if (bit == 14 && !core.speakers().contains(Speaker.LOW_FREQUENCY)) {
                continue;
            }
            extras.addAll(List.of(location));
        }
        return extras;
    }

    /**
     * Returns the core channels an extra channel is mixed into and the gain of each.
     *
     * <p>The left and right of center channels go to the front channel of their side and the
     * center at -3 dB each (to the front channel alone without a center); the back pair to the
     * surround of its side and the back center to both surrounds at -3 dB; the top center to the
     * front pair and the surrounds at -6 dB each; the surround direct channels to the surround of
     * their side; the wide channels to the front channel and the surround of their side at -3 dB
     * each; the top front channels to the front channel of their side at -3 dB and the top front
     * center to the center at -3 dB; the top back channels to the surround of their side at -3 dB;
     * the second LFE to the LFE at -3 dB. Without surrounds, what goes to a surround goes to the
     * front channel of its side, at -3 dB, and the back center and the top center to both front
     * channels; without a center, the top front center goes to both front channels at -6 dB.
     *
     * @param extra the extra speaker
     * @param center whether the core has a center
     * @param surrounds whether the core has a surround pair; {@link Speaker#SIDE_LEFT} and
     * {@link Speaker#SIDE_RIGHT} stand for the core's surround pair
     * @return the mixes
     */
    static List<Mix> mixes(Speaker extra, boolean center, boolean surrounds) {
        var half = 0.5f;
        return switch (extra) {
            case FRONT_LEFT_OF_CENTER -> center ? List.of(new Mix(Speaker.FRONT_LEFT, MIX), new Mix(Speaker.FRONT_CENTER, MIX))
                    : List.of(new Mix(Speaker.FRONT_LEFT, 1));
            case FRONT_RIGHT_OF_CENTER -> center ? List.of(new Mix(Speaker.FRONT_RIGHT, MIX), new Mix(Speaker.FRONT_CENTER, MIX))
                    : List.of(new Mix(Speaker.FRONT_RIGHT, 1));
            case BACK_LEFT, SURROUND_DIRECT_LEFT -> surrounds ? List.of(new Mix(Speaker.SIDE_LEFT, 1))
                    : List.of(new Mix(Speaker.FRONT_LEFT, MIX));
            case BACK_RIGHT, SURROUND_DIRECT_RIGHT -> surrounds ? List.of(new Mix(Speaker.SIDE_RIGHT, 1))
                    : List.of(new Mix(Speaker.FRONT_RIGHT, MIX));
            case BACK_CENTER -> surrounds ? List.of(new Mix(Speaker.SIDE_LEFT, MIX), new Mix(Speaker.SIDE_RIGHT, MIX))
                    : List.of(new Mix(Speaker.FRONT_LEFT, half), new Mix(Speaker.FRONT_RIGHT, half));
            case TOP_CENTER -> surrounds ? List.of(new Mix(Speaker.FRONT_LEFT, half), new Mix(Speaker.FRONT_RIGHT, half),
                    new Mix(Speaker.SIDE_LEFT, half), new Mix(Speaker.SIDE_RIGHT, half))
                    : List.of(new Mix(Speaker.FRONT_LEFT, MIX), new Mix(Speaker.FRONT_RIGHT, MIX));
            case WIDE_LEFT -> surrounds ? List.of(new Mix(Speaker.FRONT_LEFT, MIX), new Mix(Speaker.SIDE_LEFT, MIX))
                    : List.of(new Mix(Speaker.FRONT_LEFT, 1));
            case WIDE_RIGHT -> surrounds ? List.of(new Mix(Speaker.FRONT_RIGHT, MIX), new Mix(Speaker.SIDE_RIGHT, MIX))
                    : List.of(new Mix(Speaker.FRONT_RIGHT, 1));
            case TOP_FRONT_LEFT -> List.of(new Mix(Speaker.FRONT_LEFT, MIX));
            case TOP_FRONT_RIGHT -> List.of(new Mix(Speaker.FRONT_RIGHT, MIX));
            case TOP_FRONT_CENTER -> center ? List.of(new Mix(Speaker.FRONT_CENTER, MIX))
                    : List.of(new Mix(Speaker.FRONT_LEFT, half), new Mix(Speaker.FRONT_RIGHT, half));
            case TOP_BACK_LEFT -> List.of(new Mix(surrounds ? Speaker.SIDE_LEFT : Speaker.FRONT_LEFT, MIX));
            case TOP_BACK_RIGHT -> List.of(new Mix(surrounds ? Speaker.SIDE_RIGHT : Speaker.FRONT_RIGHT, MIX));
            case LOW_FREQUENCY_2 -> List.of(new Mix(Speaker.LOW_FREQUENCY, MIX));
            default -> List.of();
        };
    }

    /**
     * A mix of an extra channel into a core channel.
     *
     * @param target the core speaker
     * @param gain the gain of the extra channel in the core channel
     */
    record Mix(Speaker target, float gain) {
    }

    /**
     * {@return the number of full-bandwidth channels the program codes over all substreams}
     */
    public int fullBandwidthChannels() {
        var total = 0;
        for (var substream : substreams) {
            total += substream.fullBandwidthChannels();
        }
        return total;
    }

    /**
     * Returns the bit rate of each substream: the program's bit rate divided in proportion to the
     * full-bandwidth channels of each, rounded down, the last substream taking what rounding leaves.
     *
     * @param bitrate the bit rate of the program
     * @return the bit rate of each substream, in plan order
     */
    public int[] bitrates(int bitrate) {
        var total = fullBandwidthChannels();
        var rates = new int[substreams.size()];
        var given = 0L;
        for (var index = 0; index < rates.length - 1; index++) {
            rates[index] = (int) ((long) bitrate * substreams.get(index).fullBandwidthChannels() / total);
            given += rates[index];
        }
        rates[rates.length - 1] = (int) (bitrate - given);
        return rates;
    }
}

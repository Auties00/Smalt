package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;

import java.util.Arrays;
import java.util.List;

/**
 * Maps the 5-bit loudspeaker positions of MPEG-D ({@code bsOutputChannelPos} of USAC, {@code bsOutputChannelPosAT} of
 * the MPEG Surround arbitrary tree) onto speakers and into WAVE order.
 */
public final class OutputChannelPositions {
    private OutputChannelPositions() {
    }

    /**
     * Returns the speaker of each position.
     *
     * <p>Left and right surround (positions 4 and 5) are the side speakers when the rear surround pair (8 and 9) is
     * present and the back speakers otherwise; positions 30 and 31, and a speaker that an earlier position already
     * takes, are {@link Speaker#UNKNOWN}.
     *
     * @param positions the position of each channel, 0 to 31
     * @return a new array holding the speaker of each channel
     */
    public static Speaker[] speakers(int[] positions) {
        var hasRear = false;
        for (var p : positions) {
            hasRear |= p == 8 || p == 9;
        }
        var out = new Speaker[positions.length];
        for (var i = 0; i < positions.length; i++) {
            out[i] = switch (positions[i]) {
                case 0 -> Speaker.FRONT_LEFT;
                case 1 -> Speaker.FRONT_RIGHT;
                case 2 -> Speaker.FRONT_CENTER;
                case 3 -> Speaker.LOW_FREQUENCY;
                case 4 -> hasRear ? Speaker.SIDE_LEFT : Speaker.BACK_LEFT;
                case 5 -> hasRear ? Speaker.SIDE_RIGHT : Speaker.BACK_RIGHT;
                case 6 -> Speaker.FRONT_LEFT_OF_CENTER;
                case 7 -> Speaker.FRONT_RIGHT_OF_CENTER;
                case 8 -> Speaker.BACK_LEFT;
                case 9 -> Speaker.BACK_RIGHT;
                case 10 -> Speaker.BACK_CENTER;
                case 11 -> Speaker.SURROUND_DIRECT_LEFT;
                case 12 -> Speaker.SURROUND_DIRECT_RIGHT;
                case 13 -> Speaker.SIDE_LEFT;
                case 14 -> Speaker.SIDE_RIGHT;
                case 15 -> Speaker.WIDE_LEFT;
                case 16 -> Speaker.WIDE_RIGHT;
                case 17 -> Speaker.TOP_FRONT_LEFT;
                case 18 -> Speaker.TOP_FRONT_RIGHT;
                case 19 -> Speaker.TOP_FRONT_CENTER;
                case 20 -> Speaker.TOP_BACK_LEFT;
                case 21 -> Speaker.TOP_BACK_RIGHT;
                case 22 -> Speaker.TOP_BACK_CENTER;
                case 23 -> Speaker.TOP_SIDE_LEFT;
                case 24 -> Speaker.TOP_SIDE_RIGHT;
                case 25 -> Speaker.TOP_CENTER;
                case 26 -> Speaker.LOW_FREQUENCY_2;
                case 27 -> Speaker.BOTTOM_FRONT_LEFT;
                case 28 -> Speaker.BOTTOM_FRONT_RIGHT;
                case 29 -> Speaker.BOTTOM_FRONT_CENTER;
                default -> Speaker.UNKNOWN;
            };
        }
        for (var i = 0; i < out.length; i++) {
            for (var j = 0; j < i; j++) {
                if (out[i] == out[j] && out[i] != Speaker.UNKNOWN) {
                    out[i] = Speaker.UNKNOWN;
                }
            }
        }
        return out;
    }

    /**
     * Returns the output index of each channel when the channels are sorted into WAVE order (the order of
     * {@link Speaker}), ties keeping the channel order.
     *
     * @param speakers the speaker of each channel
     * @return a new array holding, for every channel, its index in WAVE order
     */
    public static int[] waveOrder(Speaker[] speakers) {
        var order = new Integer[speakers.length];
        for (var i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(speakers[a].ordinal(), speakers[b].ordinal()));
        var output = new int[speakers.length];
        for (var i = 0; i < order.length; i++) {
            output[order[i]] = i;
        }
        return output;
    }

    /**
     * Returns the layout of channels placed in WAVE order.
     *
     * @param speakers the speaker of each channel
     * @param waveOrder the output index of each channel, from {@link #waveOrder(Speaker[])}
     * @return the layout of the reordered channels
     */
    public static ChannelLayout layout(Speaker[] speakers, int[] waveOrder) {
        var placed = new Speaker[speakers.length];
        for (var i = 0; i < speakers.length; i++) {
            placed[waveOrder[i]] = speakers[i];
        }
        return new ChannelLayout(List.of(placed));
    }
}

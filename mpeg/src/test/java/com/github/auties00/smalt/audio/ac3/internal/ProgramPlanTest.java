package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks how programs are divided among substreams: the 7.1 plan the encoder has always coded, the
 * extra channels that stay out of the core so that one dependent substream carries them, the
 * programs that need several, and the bit rate shares.
 */
class ProgramPlanTest {
    private static ChannelLayout layout(Speaker... speakers) {
        return new ChannelLayout(List.of(speakers));
    }

    @Test
    @DisplayName("code 7.1 as a mixed 5.1 core and one dependent substream with the side and back pairs")
    void sevenOne() {
        var plan = ProgramPlan.enhanced(ChannelLayout.SURROUND_7_1);
        assertEquals(2, plan.substreams().size());
        var core = plan.substreams().get(0);
        assertEquals(7, core.acmod());
        assertTrue(core.lfe());
        var layout = ChannelLayout.SURROUND_7_1;
        // the left surround of the core mixes the side and back channels of its side at -3 dB
        assertArrayEquals(new int[]{layout.indexOf(Speaker.SIDE_LEFT), layout.indexOf(Speaker.BACK_LEFT)}, core.sources()[3]);
        assertEquals(ProgramPlan.MIX, core.scales()[3]);
        assertEquals(1, core.scales()[0]);
        var dependent = plan.substreams().get(1);
        assertEquals(ChannelMap.EXTENSION_MAP, dependent.channelMap());
        assertEquals(6, dependent.acmod());
        assertFalse(dependent.lfe());
        assertEquals(9, plan.fullBandwidthChannels());
        for (var bitrate : new int[]{768000, 640001, 1843200}) {
            var rates = plan.bitrates(bitrate);
            // five ninths: the five full-bandwidth channels of the core among the nine coded
            assertEquals((int) (bitrate * 5L / 9), rates[0]);
            assertEquals(bitrate, rates[0] + rates[1]);
        }
        assertTrue(ChannelMap.extended(layout));
        assertEquals(layout, ChannelMap.codableEnhanced(layout));
    }

    @Test
    @DisplayName("keep a program of up to 5.1 channels in one substream")
    void single() {
        var plan = ProgramPlan.enhanced(ChannelLayout.SURROUND_5_1);
        assertEquals(1, plan.substreams().size());
        assertEquals(-1, plan.substreams().getFirst().channelMap());
        assertArrayEquals(new int[]{448000}, plan.bitrates(448000));
        assertFalse(ChannelMap.extended(ChannelLayout.SURROUND_5_1));
    }

    @Test
    @DisplayName("leave the extra channels of 9.1 out of the core so that one dependent substream carries them")
    void nineOne() {
        var layout = layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.WIDE_LEFT,
                Speaker.WIDE_RIGHT);
        var plan = ProgramPlan.enhanced(layout);
        assertEquals(2, plan.substreams().size());
        for (var scale : plan.substreams().getFirst().scales()) {
            assertEquals(1, scale);
        }
        var dependent = plan.substreams().get(1);
        assertEquals(0x8000 >>> 6 | 0x8000 >>> 10, dependent.channelMap());
        assertArrayEquals(new int[]{layout.indexOf(Speaker.BACK_LEFT)}, dependent.sources()[0]);
        assertArrayEquals(new int[]{layout.indexOf(Speaker.WIDE_RIGHT)}, dependent.sources()[3]);
    }

    @Test
    @DisplayName("mix every extra channel in when they take several dependent substreams, and number them")
    void several() {
        var layout = layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.TOP_FRONT_LEFT,
                Speaker.TOP_FRONT_RIGHT, Speaker.TOP_BACK_LEFT, Speaker.TOP_BACK_RIGHT);
        var plan = ProgramPlan.enhanced(layout);
        var maps = plan.substreams().stream().mapToInt(ProgramPlan.Substream::channelMap).toArray();
        assertArrayEquals(new int[]{-1, 0xB800, 0x0210, 0x0004}, maps);
        var core = plan.substreams().getFirst();
        // the left front channel takes the top front left channel at -3 dB, and is scaled by -3 dB
        assertArrayEquals(new int[]{layout.indexOf(Speaker.FRONT_LEFT), layout.indexOf(Speaker.TOP_FRONT_LEFT)},
                core.sources()[0]);
        assertArrayEquals(new float[]{1, ProgramPlan.MIX}, core.weights()[0]);
        assertEquals(ProgramPlan.MIX, core.scales()[0]);
        var rates = plan.bitrates(1152000);
        var sum = 0L;
        for (var rate : rates) {
            sum += rate;
        }
        assertEquals(1152000, sum);
        assertEquals(1152000L * 5 / 15, rates[0]);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SIDE_LEFT", "WIDE_LEFT", "TOP_FRONT_RIGHT", "SURROUND_DIRECT_LEFT"})
    @DisplayName("drop a pair channel without its partner")
    void incompletePair(Speaker single) {
        var speakers = new ArrayList<>(ChannelLayout.SURROUND_5_1.speakers());
        speakers.add(single);
        var layout = new ChannelLayout(List.copyOf(speakers));
        assertEquals(ChannelLayout.SURROUND_5_1, ChannelMap.codableEnhanced(layout));
    }

    @Test
    @DisplayName("code a second LFE only beside the first, and drop speakers without a location")
    void secondLfe() {
        var withLfe = layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.LOW_FREQUENCY_2);
        assertEquals(withLfe, ChannelMap.codableEnhanced(withLfe));
        var plan = ProgramPlan.enhanced(withLfe);
        var dependent = plan.substreams().get(1);
        // the second LFE goes to the LFE of the core, which the dependent substream sends unmixed
        assertEquals(0x8000 >>> 14 | 0x8000 >>> 15, dependent.channelMap());
        assertTrue(dependent.lfe());
        var withoutLfe = layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.LOW_FREQUENCY_2);
        assertEquals(ChannelLayout.STEREO, ChannelMap.codableEnhanced(withoutLfe));
        var bottom = layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BOTTOM_FRONT_CENTER);
        assertEquals(ChannelLayout.STEREO, ChannelMap.codableEnhanced(bottom));
    }
}

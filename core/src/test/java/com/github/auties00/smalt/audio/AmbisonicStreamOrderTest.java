package com.github.auties00.smalt.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers how {@link AudioStreamDecoder} applies a container's ambisonic layout to the channels of a codec that
 * reorders them: the declared order is the codec's output order when the codec positions every channel, and the
 * coded order, the codec's reordering undone, when it does not.
 */
@DisplayName("ambisonic stream order")
class AmbisonicStreamOrderTest {
    @Test
    @DisplayName("a codec that positions every channel keeps the declared layout on its output order")
    void positioned() {
        var declared = ChannelLayout.ofAmbisonics(1);
        var codec = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                Speaker.BACK_CENTER));
        // an AAC channel configuration 4 decoder outputs coded channels C L R Cs as L R C Cs
        assertSame(declared, AudioStreamDecoder.streamOrder(declared, codec, new int[]{1, 2, 0, 3}));
    }

    @Test
    @DisplayName("a codec with channels of no position has its reordering undone")
    void unpositioned() {
        var declared = ChannelLayout.ofAmbisonics(1);
        var codec = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.UNKNOWN,
                Speaker.UNKNOWN));
        // output channel k carries coded channel coded[k]: coded 2 and 3 were moved to the front
        var relabeled = AudioStreamDecoder.streamOrder(declared, codec, new int[]{2, 3, 0, 1});
        assertEquals(ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.SN3D, List.of(2, 3, 0, 1))),
                relabeled);
    }

    @Test
    @DisplayName("non-diegetic channels move with their coded channels")
    void headLocked() {
        var declared = ChannelLayout.ofAmbisonics(Ambisonics.of(1, Ambisonics.Normalization.N3D),
                List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT));
        var codec = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                Speaker.UNKNOWN, Speaker.UNKNOWN, Speaker.UNKNOWN));
        var relabeled = AudioStreamDecoder.streamOrder(declared, codec, new int[]{4, 5, 0, 1, 2, 3});
        var speakers = new ArrayList<Speaker>(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT));
        speakers.addAll(Collections.nCopies(4, Speaker.UNKNOWN));
        assertEquals(new ChannelLayout(speakers, Optional.of(Ambisonics.of(1, Ambisonics.Normalization.N3D))),
                relabeled);
    }

    @Test
    @DisplayName("an identity or invalid coded order keeps the declared layout")
    void invalidOrders() {
        var declared = ChannelLayout.ofAmbisonics(1);
        var codec = new ChannelLayout(Collections.nCopies(4, Speaker.UNKNOWN));
        assertSame(declared, AudioStreamDecoder.streamOrder(declared, codec, new int[]{0, 1, 2, 3}));
        assertSame(declared, AudioStreamDecoder.streamOrder(declared, codec, new int[]{0, 1, 2}));
        assertSame(declared, AudioStreamDecoder.streamOrder(declared, codec, new int[]{0, 1, 1, 3}));
        assertSame(declared, AudioStreamDecoder.streamOrder(declared, codec, new int[]{0, 1, 2, 4}));
        assertSame(declared, AudioStreamDecoder.streamOrder(declared, codec, new int[]{-1, 1, 2, 3}));
        assertSame(declared, AudioStreamDecoder.streamOrder(declared, codec, null));
    }

    @Test
    @DisplayName("the default coded order of a decoder is the identity")
    void defaultOrder() {
        AudioDecoder decoder = new AudioDecoder() {
            @Override
            public int sampleRate() {
                return 48000;
            }

            @Override
            public ChannelLayout layout() {
                return ChannelLayout.SURROUND_5_1;
            }

            @Override
            public void decode(com.github.auties00.smalt.track.Packet packet, java.util.function.Consumer<AudioBuffer> output) {
            }

            @Override
            public void flush(java.util.function.Consumer<AudioBuffer> output) {
            }
        };
        assertEquals(List.of(0, 1, 2, 3, 4, 5), java.util.Arrays.stream(decoder.codedChannelOrder()).boxed().toList());
    }
}

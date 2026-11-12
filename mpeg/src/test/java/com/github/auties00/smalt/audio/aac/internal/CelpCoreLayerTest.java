package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.celp.internal.CelpObject;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the up-sampling filter tool of the CELP core of scalable AAC against a direct computation: the core speech
 * of the {@code er_ac211_ep0} conformance stream (8 kHz, 160 samples per frame) decoded by the CELP decoder, each
 * sample multiplied by 3 with two zeros after it, behind {@code coreCoderDelay} zeros, cut into 960-sample frames,
 * each transformed with the previous one by the forward MDCT and cleared above a third of its lines.
 */
class CelpCoreLayerTest {
    private static final int FRAME = 960;

    private static final int FACTOR = 3;

    @ParameterizedTest(name = "delay {0}")
    @ValueSource(ints = {0, 1, 123, 480, 960, 2000, 8000, 16383})
    @DisplayName("matches the direct up-sampling, delay and transform, long and short windows alike")
    void matchesDirectComputation(int delay) {
        var core = coreFrames();
        var config = core.config();
        var frames = core.frames();
        var speech = new CelpObject(List.of(config), -1, false, MediaLimits.defaults());
        var decoded = new float[160];
        var signal = new float[delay + frames.size() * 160 * FACTOR + 2 * FRAME];
        for (var f = 0; f < frames.size(); f++) {
            speech.decode(new byte[][]{frames.get(f)}, new int[]{frames.get(f).length}, 1, decoded);
            for (var i = 0; i < 160; i++) {
                signal[delay + (f * 160 + i) * FACTOR] = decoded[i] * FACTOR;
            }
        }
        var layer = new CelpCoreLayer(List.of(config), 24000, FRAME, delay, MediaLimits.defaults(),
                AacKernels.INSTANCE);
        var mdct = new ForwardMdct(FRAME, AacKernels.INSTANCE);
        var actual = new float[FRAME];
        var expected = new float[FRAME];
        var input = new float[2 * FRAME];
        var next = 0;
        var previousShape = 0;
        for (var aac = 0; aac < frames.size() / 2; aac++) {
            // the core frames of a 40 ms AAC frame, handed in ahead of it
            while (next < frames.size() && next < 2 * aac + 2) {
                layer.decode(new byte[][]{frames.get(next)}, new int[]{frames.get(next).length}, 1);
                next++;
            }
            var sequence = switch (aac % 7) {
                case 2 -> ChannelStream.LONG_START_SEQUENCE;
                case 3 -> ChannelStream.EIGHT_SHORT_SEQUENCE;
                case 4 -> ChannelStream.LONG_STOP_SEQUENCE;
                default -> ChannelStream.ONLY_LONG_SEQUENCE;
            };
            var shape = aac % 3 == 0 ? 1 : 0;
            layer.transform(sequence, previousShape, shape, actual);
            System.arraycopy(signal, aac * FRAME, input, FRAME, FRAME);
            if (aac > 0) {
                System.arraycopy(signal, (aac - 1) * FRAME, input, 0, FRAME);
            } else {
                Arrays.fill(input, 0, FRAME, 0);
            }
            mdct.transform(input, 0, sequence, previousShape, shape, expected);
            if (sequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
                for (var window = 0; window < 8; window++) {
                    Arrays.fill(expected, window * 120 + 40, (window + 1) * 120, 0);
                }
            } else {
                Arrays.fill(expected, FRAME / FACTOR, FRAME, 0);
            }
            assertArrayEquals(expected, actual, "AAC frame " + aac);
            previousShape = shape;
        }
    }

    @Test
    @DisplayName("transforms silence when the core falls behind, then the queued samples once they come")
    void underrun() {
        var core = coreFrames();
        var layer = new CelpCoreLayer(List.of(core.config()), 24000, FRAME, 0, MediaLimits.defaults(),
                AacKernels.INSTANCE);
        var spectrum = new float[FRAME];
        layer.transform(ChannelStream.ONLY_LONG_SEQUENCE, 0, 0, spectrum);
        assertArrayEquals(new float[FRAME], spectrum);
        for (var f = 0; f < 40; f++) {
            layer.decode(new byte[][]{core.frames().get(f)}, new int[]{core.frames().get(f).length}, 1);
        }
        var energy = 0.0;
        for (var aac = 0; aac < 20; aac++) {
            layer.transform(ChannelStream.ONLY_LONG_SEQUENCE, 0, 0, spectrum);
            for (var value : spectrum) {
                assertTrue(Float.isFinite(value));
                energy += value * value;
            }
        }
        assertTrue(energy > 0, "the queued speech reaches the spectrum");
    }

    @Test
    @DisplayName("bounds the queue when the core runs far ahead of the AAC frames")
    void overrun() {
        var core = coreFrames();
        var layer = new CelpCoreLayer(List.of(core.config()), 24000, FRAME, 0, MediaLimits.defaults(),
                AacKernels.INSTANCE);
        for (var f = 0; f < core.frames().size(); f++) {
            layer.decode(new byte[][]{core.frames().get(f)}, new int[]{core.frames().get(f).length}, 1);
        }
        var spectrum = new float[FRAME];
        layer.transform(ChannelStream.ONLY_LONG_SEQUENCE, 0, 0, spectrum);
        for (var value : spectrum) {
            assertTrue(Float.isFinite(value));
        }
    }

    @Test
    @DisplayName("rejects an AAC rate that is not a multiple of the core rate, and an excessive delay")
    void invalidConfigurations() {
        var config = coreFrames().config();
        assertThrows(MediaException.Malformed.class, () -> new CelpCoreLayer(List.of(config), 22050, FRAME, 0,
                MediaLimits.defaults(), AacKernels.INSTANCE));
        assertThrows(MediaException.Malformed.class, () -> new CelpCoreLayer(List.of(config), 24000, FRAME,
                1 << 14, MediaLimits.defaults(), AacKernels.INSTANCE));
        var layer = new CelpCoreLayer(List.of(config), 24000, FRAME, 0, MediaLimits.defaults(), AacKernels.INSTANCE);
        assertEquals(FACTOR, layer.factor());
        assertEquals(8000, layer.sampleRate());
        assertEquals(1, layer.layers());
        assertEquals(1, layer.streams());
    }

    /**
     * The configuration and access units of a core track.
     *
     * @param config the {@code AudioSpecificConfig}
     * @param frames the access units
     */
    private record Core(byte[] config, List<byte[]> frames) {
    }

    /**
     * {@return the CELP core track of {@code er_ac211_ep0}}
     */
    private static Core coreFrames() {
        var file = Fixtures.read(Conformance.fetchListed("mpeg4audio-er_ac211_ep0.mp4"));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            AudioTrack core = null;
            for (var track : demuxer.tracks()) {
                if (track instanceof AudioTrack audio && audio.codec() == Codec.CELP) {
                    core = audio;
                    break;
                }
            }
            assertTrue(core != null, "a CELP track");
            var frames = new ArrayList<byte[]>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == core.id()) {
                    frames.add(packet.data());
                }
            }
            return new Core(core.codecConfig().orElseThrow(), frames);
        }
    }
}

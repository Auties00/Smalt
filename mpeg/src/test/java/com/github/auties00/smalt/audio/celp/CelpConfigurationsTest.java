package com.github.auties00.smalt.audio.celp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every configuration of ISO/IEC 14496-3 Tables 3.73 to 3.75 through the decoder, most of which no conformance
 * stream uses: frames of random bits, the valid input of a decoder whose frame fields all have fixed widths, must give
 * finite blocks of the size the tables state, with fine rate control and silence compression, at every layer. The
 * reserved values must be rejected.
 */
@DisplayName("CELP configurations")
class CelpConfigurationsTest {
    /**
     * Writes the {@code AudioSpecificConfig} of a base layer.
     */
    private static byte[] base(int objectType, boolean rpe, boolean wideband, boolean fineRateControl, boolean silence,
                               int configuration, int layers, boolean bandwidth, int epConfig) {
        var writer = new MsbBitWriter();
        writer.write(objectType, 5);
        writer.write(wideband ? 8 : 11, 4);
        writer.write(1, 4);
        writer.write(1, 1);
        writer.write(rpe ? 1 : 0, 1);
        writer.write(wideband ? 1 : 0, 1);
        writer.write(fineRateControl ? 1 : 0, 1);
        if (objectType == 24) {
            writer.write(silence ? 1 : 0, 1);
        }
        if (rpe) {
            writer.write(configuration, 3);
        } else {
            writer.write(configuration, 5);
            writer.write(layers, 2);
            writer.write(bandwidth ? 1 : 0, 1);
        }
        if (objectType == 24) {
            writer.write(epConfig, 2);
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    /**
     * Writes the {@code AudioSpecificConfig} of an enhancement layer.
     */
    private static byte[] layer(int objectType, boolean bandwidth, int id) {
        var writer = new MsbBitWriter();
        writer.write(objectType, 5);
        writer.write(bandwidth ? 8 : 11, 4);
        writer.write(1, 4);
        writer.write(0, 1);
        writer.write(bandwidth ? 1 : 0, 1);
        writer.write(id, 2);
        if (objectType == 24) {
            writer.write(0, 2);
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    private static List<AudioTrack> tracks(byte[]... configs) {
        var tracks = new ArrayList<AudioTrack>();
        for (var config : configs) {
            tracks.add(AudioTrack.builder().id(tracks.size() + 1).codec(Codec.CELP).timeBase(new Rational(1, 16000))
                    .codecConfig(config).build());
        }
        return tracks;
    }

    /**
     * {@return the samples of a frame per Tables 3.73 to 3.75}
     */
    private static int frameSize(boolean rpe, boolean wideband, int configuration) {
        if (rpe) {
            return configuration == 1 ? 160 : 240;
        }
        if (wideband) {
            return configuration < 16 ? 320 : 160;
        }
        if (configuration < 3) {
            return 320;
        }
        if (configuration < 6 || configuration == 27) {
            return 240;
        }
        return configuration < 22 ? 160 : 80;
    }

    /**
     * Decodes frames of random bits and checks the blocks.
     *
     * @param decoder the decoder, closed on return
     * @param units the elementary streams of the object
     * @param frameSize the samples each block must hold
     * @param seed the seed of the random bits
     */
    private static void run(CelpDecoder decoder, int units, int frameSize, long seed) {
        var random = new Random(seed);
        var blocks = new int[1];
        var energy = new double[1];
        try (decoder) {
            assertEquals(frameSize, decoder.frameSize());
            for (var frame = 0; frame < 48; frame++) {
                var packets = new ArrayList<Packet>();
                for (var unit = 0; unit < units; unit++) {
                    var data = new byte[120];
                    random.nextBytes(data);
                    packets.add(new Packet(unit + 1, data, frame, frame, 1, true));
                }
                decoder.decode(packets, buffer -> {
                    blocks[0]++;
                    assertEquals(frameSize, buffer.frames());
                    for (var i = 0; i < buffer.frames(); i++) {
                        var sample = buffer.plane(0)[i];
                        assertTrue(Float.isFinite(sample), "finite samples");
                        energy[0] += sample * sample;
                    }
                });
            }
        }
        assertEquals(48, blocks[0]);
        assertTrue(energy[0] > 0, "the frames decode to a signal");
    }

    static Stream<Arguments> configurations() {
        var list = new ArrayList<Arguments>();
        for (var mode = 0; mode < 3; mode++) {
            var rpe = mode == 2;
            var wideband = mode > 0;
            var count = rpe ? 4 : wideband ? 32 : 28;
            for (var configuration = 0; configuration < count; configuration++) {
                if (!rpe && wideband && (configuration == 7 || configuration == 23)) {
                    continue;
                }
                list.add(Arguments.of(rpe, wideband, configuration));
            }
        }
        return list.stream();
    }

    @ParameterizedTest(name = "RPE {0}, 16 kHz {1}, configuration {2}")
    @MethodSource("configurations")
    @DisplayName("every configuration decodes random frames, with fine rate control and silence compression")
    void everyConfiguration(boolean rpe, boolean wideband, int configuration) {
        var size = frameSize(rpe, wideband, configuration);
        var seed = 31L * configuration + (rpe ? 2 : wideband ? 1 : 0);
        var plain = CelpDecoderOptions.builder().postFilter(false).build();
        var base = CelpDecoderOptions.defaults();
        run(new CelpDecoder(tracks(base(8, rpe, wideband, false, false, configuration, 0, false, 0)), plain), 1, size,
                seed);
        run(new CelpDecoder(tracks(base(8, rpe, wideband, true, false, configuration, 0, false, 0)), base), 1, size,
                seed + 1);
        run(new CelpDecoder(tracks(base(24, rpe, wideband, false, true, configuration, 0, false, 0)), base), 1, size,
                seed + 2);
        run(new CelpDecoder(tracks(base(24, rpe, wideband, true, true, configuration, 0, false, 0)), plain), 1, size,
                seed + 3);
        var category = base(24, rpe, wideband, false, true, configuration, 0, false, 1);
        run(new CelpDecoder(tracks(category, category, category, category, category), base), 5, size, seed + 4);
    }

    static Stream<Arguments> layered() {
        var list = new ArrayList<Arguments>();
        for (var configuration = 0; configuration < 27; configuration++) {
            list.add(Arguments.of(configuration, configuration % 4));
        }
        return list.stream();
    }

    @ParameterizedTest(name = "MPE_Configuration {0}, BWS_configuration {1}")
    @MethodSource("layered")
    @DisplayName("every narrowband configuration decodes with three bitrate layers and the bandwidth layer")
    void narrowbandLayers(int configuration, int bandwidth) {
        var size = frameSize(false, false, configuration);
        for (var objectType : new int[]{8, 24}) {
            var configs = new byte[][]{
                    base(objectType, false, false, configuration % 2 == 1, objectType == 24, configuration, 3, true, 0),
                    layer(objectType, false, 1), layer(objectType, false, 2), layer(objectType, false, 3),
                    layer(objectType, true, bandwidth)};
            for (var max = 0; max <= 3; max++) {
                var options = CelpDecoderOptions.builder().maxLayer(max).build();
                run(new CelpDecoder(tracks(configs), options), 5, size, 7L * configuration + max);
            }
            run(new CelpDecoder(tracks(configs), CelpDecoderOptions.defaults()), 5, 2 * size, 11L * configuration);
        }
    }

    @ParameterizedTest(name = "MPE_Configuration {0}")
    @ValueSource(ints = {0, 3, 6, 8, 15, 16, 22, 24, 31})
    @DisplayName("wideband configurations decode with three bitrate layers")
    void widebandLayers(int configuration) {
        var size = frameSize(false, true, configuration);
        var configs = new byte[][]{base(8, false, true, false, false, configuration, 3, false, 0),
                layer(8, false, 1), layer(8, false, 2), layer(8, false, 3)};
        run(new CelpDecoder(tracks(configs), CelpDecoderOptions.defaults()), 4, size, 13L * configuration);
    }

    @Nested
    @DisplayName("rejected configurations")
    class Rejected {
        @ParameterizedTest(name = "MPE_Configuration {0} at 8 kHz")
        @ValueSource(ints = {28, 29, 30, 31})
        @DisplayName("the reserved narrowband configurations")
        void narrowband(int configuration) {
            var tracks = tracks(base(8, false, false, false, false, configuration, 0, false, 0));
            assertThrows(MediaException.Malformed.class, () -> new CelpDecoder(tracks.getFirst()));
        }

        @ParameterizedTest(name = "MPE_Configuration {0} at 16 kHz")
        @ValueSource(ints = {7, 23})
        @DisplayName("the reserved wideband configurations")
        void wideband(int configuration) {
            var tracks = tracks(base(8, false, true, false, false, configuration, 0, false, 0));
            assertThrows(MediaException.Malformed.class, () -> new CelpDecoder(tracks.getFirst()));
        }

        @ParameterizedTest(name = "RPE_Configuration {0}")
        @ValueSource(ints = {4, 5, 6, 7})
        @DisplayName("the reserved regular pulse configurations")
        void regularPulse(int configuration) {
            var tracks = tracks(base(8, true, true, false, false, configuration, 0, false, 0));
            assertThrows(MediaException.Malformed.class, () -> new CelpDecoder(tracks.getFirst()));
        }

        @Test
        @DisplayName("the combinations the standard leaves out")
        void combinations() {
            for (var config : List.of(base(8, true, false, false, false, 0, 0, false, 0),
                    base(8, false, false, false, false, 27, 1, false, 0),
                    base(8, false, false, false, false, 27, 0, true, 0),
                    base(8, false, true, false, false, 0, 0, true, 0))) {
                assertThrows(MediaException.Malformed.class, () -> new CelpDecoder(tracks(config).getFirst()));
            }
        }

        @Test
        @DisplayName("a category stream of another object type or epConfig")
        void categories() {
            var category = base(24, false, false, false, false, 0, 0, false, 1);
            var other = base(24, false, false, false, false, 0, 0, false, 0);
            assertThrows(MediaException.Malformed.class, () -> new CelpDecoder(
                    tracks(category, category, other, category, category), CelpDecoderOptions.defaults()));
        }

        @Test
        @DisplayName("an object with two base layers")
        void twoBases() {
            var config = base(8, false, false, false, false, 8, 1, false, 0);
            assertThrows(MediaException.Malformed.class, () -> new CelpDecoder(tracks(config, config),
                    CelpDecoderOptions.defaults()));
        }
    }

    /**
     * Writes the {@code AudioSpecificConfig} of an ER CELP base layer with {@code epConfig} 2: one pre-defined set of
     * one unprotected class of a fixed length, so that an EP frame holds the source coder frames as they are.
     */
    private static byte[] protectedBase(int configuration, int classBits, int concatenated) {
        var writer = new MsbBitWriter();
        writer.write(24, 5);
        writer.write(11, 4);
        writer.write(1, 4);
        writer.write(1, 1);
        writer.write(0, 4);
        writer.write(configuration, 5);
        writer.write(0, 3);
        writer.write(2, 2);
        writer.write(1, 8);
        writer.write(0, 5);
        writer.write(concatenated, 3);
        writer.write(1, 6);
        writer.write(0, 3);
        if (concatenated > 1) {
            writer.write(0, 1);
        }
        writer.write(0, 2);
        writer.write(0, 2);
        writer.write(classBits, 16);
        writer.write(0, 10);
        writer.write(0, 2);
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    @ParameterizedTest(name = "{0} concatenated frames")
    @ValueSource(ints = {1, 2, 7})
    @DisplayName("epConfig 2 decodes the frames of an EP frame as the same frames without the tool")
    void errorProtection(int concatenated) {
        var options = CelpDecoderOptions.builder().postFilter(false).build();
        var bytes = 20;
        var plain = new Random(concatenated);
        var expected = new CelpTestSupport.Samples();
        var actual = new CelpTestSupport.Samples();
        try (var unprotected = new CelpDecoder(tracks(base(24, false, false, false, false, 0, 0, false, 0)), options);
             var protectedDecoder = new CelpDecoder(tracks(protectedBase(0, 8 * bytes, concatenated)), options)) {
            for (var unit = 0; unit < 8; unit++) {
                var data = new byte[bytes * concatenated];
                plain.nextBytes(data);
                for (var frame = 0; frame < concatenated; frame++) {
                    var single = java.util.Arrays.copyOfRange(data, frame * bytes, (frame + 1) * bytes);
                    unprotected.decode(new Packet(1, single, unit, unit, 1, true),
                            buffer -> expected.add(buffer.plane(0), buffer.frames()));
                }
                var blocks = new int[1];
                protectedDecoder.decode(new Packet(1, data, unit, unit, 1, true), buffer -> {
                    blocks[0]++;
                    actual.add(buffer.plane(0), buffer.frames());
                });
                assertEquals(concatenated, blocks[0]);
            }
        }
        var decoded = expected.toArray();
        assertTrue(decoded.length == 8 * concatenated * 320, "samples");
        org.junit.jupiter.api.Assertions.assertArrayEquals(decoded, actual.toArray());
    }

    @Test
    @DisplayName("tracks of layers the base layer does not announce are ignored, however many come before a real one")
    void ignoredTracks() {
        var configs = new ArrayList<byte[]>();
        configs.add(base(8, false, false, false, false, 8, 1, false, 0));
        for (var i = 0; i < 14; i++) {
            configs.add(layer(8, false, 3));
        }
        configs.add(layer(8, false, 1));
        var tracks = tracks(configs.toArray(byte[][]::new));
        var options = CelpDecoderOptions.builder().postFilter(false).build();
        var layered = new float[160];
        var single = new float[160];
        var random = new Random(9);
        var unit = new byte[40];
        random.nextBytes(unit);
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < tracks.size(); i++) {
            packets.add(new Packet(i + 1, unit, 0, 0, 1, true));
        }
        try (var decoder = new CelpDecoder(tracks, options)) {
            assertDoesNotThrow(() -> decoder.decode(packets, buffer -> System.arraycopy(buffer.plane(0), 0, layered, 0,
                    160)));
        }
        try (var decoder = new CelpDecoder(tracks(configs.getFirst(), configs.getLast()), options)) {
            decoder.decode(List.of(packets.getFirst(), packets.getLast()), buffer -> System.arraycopy(buffer.plane(0),
                    0, single, 0, 160));
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(single, layered);
    }
}

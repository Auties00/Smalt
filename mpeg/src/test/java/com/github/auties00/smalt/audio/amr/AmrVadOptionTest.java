package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.audio.amr.internal.nb.NbEncoder;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the AMR-NB voice activity detector option of {@link AmrEncoderOptions}: its default and builder, that the
 * public encoder codes exactly the frames of the frame coder the TS 26.074 VAD option 2 sequences check
 * ({@code AmrConformanceTest}), that the option changes the DTX decisions, that the reference decoder (ffmpeg's
 * {@code libopencore_amrnb}) decodes its streams to Smalt's samples, and that AMR-WB ignores it.
 */
class AmrVadOptionTest {
    @Test
    @DisplayName("defaults to option 1 and keeps the option through the builder")
    void options() {
        assertEquals(AmrVad.OPTION_1, AmrEncoderOptions.defaults().vad());
        assertEquals(AmrVad.OPTION_1, AmrEncoderOptions.of(AmrMode.NB_5_15).vad());
        var options = AmrEncoderOptions.builder().mode(AmrMode.NB_6_70).dtx(true).vad(AmrVad.OPTION_2).build();
        assertEquals(AmrVad.OPTION_2, options.vad());
        assertEquals(options, options.toBuilder().build());
        assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
        assertNotEquals(options, options.toBuilder().vad(AmrVad.OPTION_1).build());
        assertTrue(options.toString().contains("vad=OPTION_2"), options.toString());
        assertThrows(NullPointerException.class, () -> AmrEncoderOptions.builder().vad(null));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = AmrMode.class, names = "NB_.*", mode = EnumSource.Mode.MATCH_ALL)
    @DisplayName("codes the frames of the conformance-tested frame coder in every AMR-NB mode")
    void matchesFrameCoder(AmrMode mode) {
        var pcm = AmrTestSupport.signal(8000);
        var options = AmrEncoderOptions.builder().mode(mode).dtx(true).vad(AmrVad.OPTION_2).build();
        var packets = AmrTestSupport.encode(options, pcm);
        var coder = new NbEncoder(true, true);
        var frame = new byte[32];
        var frames = pcm.length / 160;
        // the flush codes the encoder delay as one more frame of silence
        assertEquals(frames + 1, packets.size());
        for (var k = 0; k < frames; k++) {
            var size = coder.encode(mode.frameType(), pcm, k * 160, frame, 0);
            assertArrayEquals(Arrays.copyOf(frame, size), packets.get(k).data(), "frame " + k);
        }
    }

    @Test
    @DisplayName("takes other DTX decisions than option 1, and replaces the background by comfort noise")
    void decisions() {
        var pcm = AmrTestSupport.signal(8000);
        var first = AmrTestSupport.encode(AmrEncoderOptions.builder().mode(AmrMode.NB_7_40).dtx(true).build(), pcm);
        var second = AmrTestSupport.encode(AmrEncoderOptions.builder().mode(AmrMode.NB_7_40).dtx(true)
                .vad(AmrVad.OPTION_2).build(), pcm);
        assertEquals(first.size(), second.size());
        var firstTypes = types(first);
        var secondTypes = types(second);
        assertFalse(Arrays.equals(firstTypes, secondTypes), "the detectors decide alike on every frame");
        // the last second is digital silence: after the hangover every frame is comfort noise or empty
        for (var k = second.size() - 25; k < second.size(); k++) {
            assertTrue(secondTypes[k] == 8 || secondTypes[k] == 15, "frame " + k + " has type " + secondTypes[k]);
        }
        // the signal is loud, voiced speech from 0.24 to 0.6 seconds: no frame there is comfort noise
        for (var k = 12; k < 30; k++) {
            assertTrue(secondTypes[k] < 8, "frame " + k + " has type " + secondTypes[k]);
        }
        var noDtx = AmrTestSupport.encode(AmrEncoderOptions.builder().mode(AmrMode.NB_7_40).vad(AmrVad.OPTION_2)
                .build(), pcm);
        var plain = AmrTestSupport.encode(AmrEncoderOptions.of(AmrMode.NB_7_40), pcm);
        assertEquals(data(plain), data(noDtx), "the detector has no effect without DTX");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = AmrMode.class, names = {"NB_4_75", "NB_10_2", "NB_12_2"})
    @DisplayName("decodes like the reference decoder of ffmpeg, comfort noise included")
    void referenceDecoder(AmrMode mode, @TempDir Path directory) {
        var pcm = AmrTestSupport.signal(8000);
        var packets = AmrTestSupport.encode(AmrEncoderOptions.builder().mode(mode).dtx(true).vad(AmrVad.OPTION_2)
                .build(), pcm);
        var file = AmrTestSupport.storage(Codec.AMR_NB, packets);
        AmrTestSupport.assertDecodesCleanly(Codec.AMR_NB, write(directory, file));
        assertArrayEquals(AmrTestSupport.ffmpegDecode(Codec.AMR_NB, file, directory),
                AmrTestSupport.decode(Codec.AMR_NB, packets));
    }

    @Test
    @DisplayName("has no effect on AMR-WB, which has a single detector")
    void wideband() {
        var pcm = AmrTestSupport.signal(16000);
        var first = AmrTestSupport.encode(AmrEncoderOptions.builder().mode(AmrMode.WB_12_65).dtx(true).build(), pcm);
        var second = AmrTestSupport.encode(AmrEncoderOptions.builder().mode(AmrMode.WB_12_65).dtx(true)
                .vad(AmrVad.OPTION_2).build(), pcm);
        assertEquals(data(first), data(second));
    }

    private static int[] types(List<Packet> packets) {
        return packets.stream().mapToInt(packet -> (packet.data()[0] >> 3) & 0x0F).toArray();
    }

    private static List<String> data(List<Packet> packets) {
        return packets.stream().map(packet -> Arrays.toString(packet.data())).toList();
    }

    private static Path write(Path directory, byte[] file) {
        try {
            return java.nio.file.Files.write(directory.resolve("vad2.amr"), file);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}

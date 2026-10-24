package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.ac3.internal.DitherClearing;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Lo/Ro stereo and the mono downmix of every audio coding mode, with and without LFE, at every center and
 * surround mix level an encoder declares, against ffmpeg's decoder downmixing the same streams: ffmpeg's AC-3
 * encoder writes them from a test signal with a distinct tone per channel, their dither flags are cleared so that
 * both decoders reconstruct the same coefficients, and the downmixes must agree to the decoders' rounding.
 */
@DisplayName("AC-3 downmix of every channel configuration")
class Ac3DownmixTest {
    @TempDir
    Path directory;

    static Stream<Arguments> configurations() {
        var arguments = new ArrayList<Arguments>();
        // Every audio coding mode, 1/0 to 3/2, with and without LFE, in ffmpeg's layout names.
        var layouts = List.of("mono", "stereo", "3.0", "3.0(back)", "2.1", "3.1", "4.0", "4.1", "quad", "5.0", "5.1",
                "5.0(side)", "5.1(side)");
        for (var layout : layouts) {
            arguments.add(Arguments.of(layout, "0.707", "0.707"));
        }
        for (var center : List.of("0.595", "0.500")) {
            arguments.add(Arguments.of("5.1", center, "0.500"));
        }
        arguments.add(Arguments.of("5.1", "0.707", "0.000"));
        arguments.add(Arguments.of("3.1", "0.500", "0.707"));
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}, center {1}, surround {2}")
    @MethodSource("configurations")
    @DisplayName("matches ffmpeg's stereo and mono downmix")
    void downmix(String layout, String center, String surround) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var source = directory.resolve("source.ac3");
        var channels = channelCount(layout);
        var expression = new StringBuilder();
        for (var channel = 0; channel < channels; channel++) {
            if (channel > 0) {
                expression.append('|');
            }
            expression.append("0.25*sin(2*PI*").append(220 + 110 * channel).append("*t)");
        }
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-y", "-f", "lavfi", "-i",
                "aevalsrc=" + expression + ":s=48000:c=" + layout + ":d=0.5",
                "-c:a", "ac3", "-b:a", "448k", "-center_mixlev", center, "-surround_mixlev", surround, source.toString()));
        var stream = DitherClearing.clear(Files.readAllBytes(source));
        var cleared = directory.resolve("cleared.ac3");
        Files.write(cleared, stream);
        var stereo = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange().toBuilder()
                .downmix(Ac3Downmix.STEREO).build());
        assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT), stereo.layout().speakers(),
                layout + " downmix layout");
        if (channels > 1) {
            var expected = Ac3TestSupport.ffmpeg(cleared, "-drc_scale", "0", "-downmix", "stereo");
            var snr = Ac3TestSupport.compare(expected, stereo.interleaved())[0];
            assertTrue(snr >= 80, layout + " center " + center + " surround " + surround + " stereo SNR " + snr);
        } else {
            // A mono program is spread to both channels of the stereo downmix.
            assertTrue(java.util.Arrays.equals(java.util.Arrays.copyOf(stereo.planes().get(0), stereo.frames()),
                    java.util.Arrays.copyOf(stereo.planes().get(1), stereo.frames())), "left and right of a mono program");
        }
        var mono = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange().toBuilder()
                .downmix(Ac3Downmix.MONO).build());
        assertEquals(List.of(Speaker.FRONT_CENTER), mono.layout().speakers(), layout + " mono layout");
        var expected = Ac3TestSupport.ffmpeg(cleared, "-drc_scale", "0", "-downmix", "mono");
        var actual = mono.interleaved();
        assertEquals(expected.length, actual.length, layout + " mono length");
        // The mono downmix is ffmpeg's up to a constant, overflow-safe gain: compare the direction of the signals.
        var dot = 0.0;
        var expectedEnergy = 0.0;
        var actualEnergy = 0.0;
        for (var index = 0; index < expected.length; index++) {
            dot += expected[index] * (double) actual[index];
            expectedEnergy += expected[index] * (double) expected[index];
            actualEnergy += actual[index] * (double) actual[index];
        }
        var correlation = dot / Math.sqrt(expectedEnergy * actualEnergy);
        assertTrue(correlation > 0.9999, layout + " mono correlation " + correlation);
    }

    private static int channelCount(String layout) {
        return switch (layout) {
            case "mono" -> 1;
            case "stereo" -> 2;
            case "3.0", "2.1", "3.0(back)" -> 3;
            case "3.1", "4.0", "quad" -> 4;
            case "5.0", "4.1", "5.0(side)" -> 5;
            default -> 6;
        };
    }
}

package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.ac3.internal.DitherClearing;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes Smalt's E-AC-3 streams with Core Audio, through {@code afconvert} on macOS. The streams
 * are muxed into MP4 by Smalt and decoded without dither, which the decoders generate differently,
 * and the decodes are lined up with Smalt's before they are compared. Core Audio, like ffmpeg 7.1
 * and Windows Media Foundation, has no decoder for the reduced sampling rates of 16 to 24 kHz
 * (ATSC A/52 Annex E section 2.3.1.5): {@code afconvert} fails on them with {@code '!dat'}.
 */
class Eac3CoreAudioTest {
    @TempDir
    Path directory;

    @ParameterizedTest(name = "{0} Hz")
    @ValueSource(ints = {48000, 44100, 32000})
    @DisplayName("Core Audio decodes the streams as Smalt does")
    void afconvert(int sampleRate) throws IOException {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Skip.unless(os.startsWith("mac"), "platform:macos", "afconvert is part of macOS");
        var afconvert = Oracle.find("afconvert").orElseThrow(() -> new AssertionError("macOS without afconvert"));
        var input = Ac3EncoderTest.stereoMusic(sampleRate, 3);
        var options = Eac3EncoderOptions.of(sampleRate, ChannelLayout.STEREO).toBuilder().spectralExtension(false).build();
        var packets = Eac3EncoderTest.encode(options, input);
        var stream = DitherClearing.clear(Ac3EncoderTest.concatenate(packets));
        var track = Ac3ContainersTest.track(Codec.EAC3, sampleRate, ChannelLayout.STEREO);
        var file = Files.write(directory.resolve("eac3-" + sampleRate + ".mp4"),
                Ac3ContainersTest.mux(track, Ac3ContainersTest.units(stream), Mp4Muxer::new));
        var wav = directory.resolve("eac3-" + sampleRate + ".wav");
        var result = Oracle.execute(afconvert, Duration.ofMinutes(1), null, List.of("-f", "WAVE", "-d", "LEF32",
                file.toString(), wav.toString()));
        assertEquals(0, result.exitCode(), result.stderr());
        var decoded = samples(wav);
        var expected = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange()).interleaved();
        var offset = offset(expected, decoded, 2);
        System.out.printf("Core Audio at %d Hz: output %d samples ahead of Smalt's%n", sampleRate, offset);
        var from = Math.max(0, offset) * 2;
        var to = Math.min(decoded.length, expected.length + offset * 2) - 2 * 1536;
        var aligned = Arrays.copyOfRange(decoded, from, to);
        var reference = Arrays.copyOfRange(expected, from - offset * 2, to - offset * 2);
        Tolerance.snr(50).assertPcm("Core Audio at " + sampleRate + " Hz", reference, aligned);
    }

    /**
     * Returns the offset {@code d} in samples per channel for which {@code decoded[i + d]} best
     * matches {@code expected[i]}, from -3072 to 3072.
     */
    private static int offset(float[] expected, float[] decoded, int channels) {
        var best = 0;
        var bestError = Double.MAX_VALUE;
        var frames = Math.min(expected.length, decoded.length) / channels;
        for (var d = -3072; d <= 3072; d++) {
            var error = 0.0;
            for (var i = 12000; i < 12000 + 4096 && i + d < frames && i < frames; i++) {
                var difference = (double) decoded[(i + d) * channels] - expected[i * channels];
                error += difference * difference;
            }
            if (error < bestError) {
                bestError = error;
                best = d;
            }
        }
        return best;
    }

    /**
     * Reads the float samples of a WAVE file.
     */
    private static float[] samples(Path wav) {
        try {
            var bytes = Files.readAllBytes(wav);
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            var position = 12;
            while (position + 8 <= bytes.length) {
                var id = new String(bytes, position, 4, java.nio.charset.StandardCharsets.US_ASCII);
                var size = buffer.getInt(position + 4);
                if (id.equals("data")) {
                    var out = new float[size / 4];
                    buffer.position(position + 8);
                    buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
                    return out;
                }
                position += 8 + size + (size & 1);
            }
            throw new AssertionError("no data chunk in " + wav);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

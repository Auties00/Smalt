package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.ac3.Ac3Encoder;
import com.github.auties00.smalt.audio.ac3.Ac3EncoderOptions;
import com.github.auties00.smalt.audio.ac3.Eac3Encoder;
import com.github.auties00.smalt.audio.ac3.Eac3EncoderOptions;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes real programme material, the Blender open movies' soundtracks, with the AAC, HE-AAC, AC-3 and E-AC-3
 * encoders and compares them with ffmpeg's native encoders at the same bit rate: ten seconds of the Tears of Steel
 * 5.1 DVD mix (its six PCM stems, CC BY-ND 3.0, {@code blender-ToS-copyright.txt}) and of the Sintel stereo music
 * and effects mix. Each stream is decoded by ffmpeg, aligned with the input and measured by its segmental SNR over
 * the full-bandwidth channels; Smalt's encoders must come within the margin their synthetic-signal tests allow of
 * ffmpeg's (1 dB for AAC, 3 dB for AC-3 and E-AC-3), and HE-AAC must keep more bandwidth than AAC-LC at a low rate.
 */
@DisplayName("encoders on real programme material")
class ProgrammeEncodingTest {
    private static final Path WORK = com.github.auties00.smalt.testkit.Scratch.directory("programme-encoding");

    private static final String[] STEMS = {"L", "R", "C", "LFE", "Ls", "Rs"};

    private static final int SECONDS = 10;

    /** Reads ten seconds of a file from a start time, through ffmpeg, as planes of a channel count. */
    private static float[][] read(Path file, int start, int channels) {
        var samples = Oracle.decodeAudio(file, List.of("-ss", Integer.toString(start), "-t", Integer.toString(SECONDS)));
        return AacTestSupport.deinterleave(samples, channels);
    }

    static AudioBuffer surround() {
        var planes = new float[6][];
        for (var c = 0; c < 6; c++) {
            planes[c] = read(Conformance.vector("blender-ToS-TOS_DVDSURROUND." + STEMS[c] + ".aif"), 120, 1)[0];
        }
        return new AudioBuffer(ChannelLayout.SURROUND_5_1, 48000, planes, planes[0].length);
    }

    static AudioBuffer stereo() {
        var planes = read(Conformance.vector("blender-sintel-m+e-st.flac"), 120, 2);
        return new AudioBuffer(ChannelLayout.STEREO, 48000, planes, planes[0].length);
    }

    private static AudioBuffer programme(String name) {
        return name.equals("surround") ? surround() : stereo();
    }

    private static Path write(String name, byte[] data) {
        try {
            return Files.write(WORK.resolve(name), data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] pcm(AudioBuffer input) {
        var out = ByteBuffer.allocate(input.frames() * input.channels() * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < input.frames(); i++) {
            for (var c = 0; c < input.channels(); c++) {
                out.putFloat(input.plane(c)[i]);
            }
        }
        return out.array();
    }

    /** Encodes the input with an ffmpeg encoder into a file of a format. */
    private static Path ffmpegEncode(String name, AudioBuffer input, String codec, int bitrate, String format) {
        var source = write(name + ".f32", pcm(input));
        var layout = input.channels() == 6 ? "5.1" : "stereo";
        var output = WORK.resolve(name + "-ffmpeg." + format);
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-y", "-f", "f32le", "-ar", "48000",
                "-ch_layout", layout, "-i", source.toString(), "-c:a", codec, "-b:a", Integer.toString(bitrate), "-f",
                format, output.toString()));
        return output;
    }

    /**
     * Returns the segmental SNR of ffmpeg's decode of a file against the input over the channels but the LFE, the
     * decode aligned by the delay, up to 4096 samples, that best matches the first channel.
     */
    private static double quality(AudioBuffer input, Path file) {
        var decoded = AacTestSupport.deinterleave(Oracle.decodeAudio(file), input.channels());
        var reference = input.plane(0);
        var bestDelay = 0;
        var bestError = Double.POSITIVE_INFINITY;
        for (var delay = 0; delay <= 4096; delay++) {
            double error = 0;
            for (var i = 48000; i < 72000 && i + delay < decoded[0].length; i++) {
                var difference = reference[i] - decoded[0][i + delay];
                error += difference * difference;
            }
            if (error < bestError) {
                bestError = error;
                bestDelay = delay;
            }
        }
        var channels = new ArrayList<float[]>();
        var outputs = new ArrayList<float[]>();
        for (var c = 0; c < input.channels(); c++) {
            if (input.layout().speakers().get(c) == com.github.auties00.smalt.audio.Speaker.LOW_FREQUENCY) {
                continue;
            }
            var length = Math.min(input.frames(), decoded[c].length - bestDelay);
            channels.add(java.util.Arrays.copyOf(input.plane(c), length));
            outputs.add(java.util.Arrays.copyOfRange(decoded[c], bestDelay, bestDelay + length));
        }
        return TestSignals.segmentalSnr(channels.toArray(float[][]::new), outputs.toArray(float[][]::new));
    }

    private static byte[] concatenate(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        packets.forEach(packet -> out.writeBytes(packet.data()));
        return out.toByteArray();
    }

    private static Path aac(String name, AacEncoderOptions options, AudioBuffer input) {
        var encoder = new AacEncoder(options);
        var packets = new ArrayList<Packet>();
        encoder.encode(input, packets::add);
        encoder.flush(packets::add);
        return write(name + ".aac", AacTestSupport.writeAdts(encoder.codecConfig().orElseThrow(),
                packets.stream().map(Packet::data).toList()));
    }

    @ParameterizedTest(name = "{0} at {1} bit/s")
    @CsvSource({"stereo, 128000", "surround, 384000"})
    @DisplayName("AAC matches or beats ffmpeg's segmental SNR within 1 dB")
    void aac(String programme, int bitrate) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = programme(programme);
        var name = "aac-" + programme + "-" + bitrate;
        var mine = quality(input, aac(name, AacEncoderOptions.of(48000, input.layout(), bitrate), input));
        var theirs = quality(input, ffmpegEncode(name, input, "aac", bitrate, "adts"));
        System.out.printf("%s: Smalt %.2f dB, ffmpeg %.2f dB%n", name, mine, theirs);
        assertTrue(mine >= theirs - 1, name + ": segmental SNR " + mine + " vs ffmpeg " + theirs);
    }

    @ParameterizedTest(name = "{0} at {1} bit/s")
    @CsvSource({"stereo, 192000", "surround, 448000"})
    @DisplayName("AC-3 comes within 3 dB of ffmpeg's segmental SNR")
    void ac3(String programme, int bitrate) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = programme(programme);
        var name = "ac3-" + programme + "-" + bitrate;
        var packets = new ArrayList<Packet>();
        try (var encoder = new Ac3Encoder(Ac3EncoderOptions.of(48000, input.layout()).toBuilder().bitrate(bitrate)
                .build())) {
            encoder.encode(input, packets::add);
            encoder.flush(packets::add);
        }
        var mine = quality(input, write(name + ".ac3", concatenate(packets)));
        var theirs = quality(input, ffmpegEncode(name, input, "ac3", bitrate, "ac3"));
        System.out.printf("%s: Smalt %.2f dB, ffmpeg %.2f dB%n", name, mine, theirs);
        assertTrue(mine >= theirs - 3, name + ": segmental SNR " + mine + " vs ffmpeg " + theirs);
    }

    @ParameterizedTest(name = "{0} at {1} bit/s")
    @CsvSource({"stereo, 192000", "surround, 448000"})
    @DisplayName("E-AC-3 comes within 3 dB of ffmpeg's segmental SNR")
    void eac3(String programme, int bitrate) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = programme(programme);
        var name = "eac3-" + programme + "-" + bitrate;
        var packets = new ArrayList<Packet>();
        try (var encoder = new Eac3Encoder(Eac3EncoderOptions.of(48000, input.layout()).toBuilder().bitrate(bitrate)
                .build())) {
            encoder.encode(input, packets::add);
            encoder.flush(packets::add);
        }
        var mine = quality(input, write(name + ".eac3", concatenate(packets)));
        var theirs = quality(input, ffmpegEncode(name, input, "eac3", bitrate, "eac3"));
        System.out.printf("%s: Smalt %.2f dB, ffmpeg %.2f dB%n", name, mine, theirs);
        assertTrue(mine >= theirs - 3, name + ": segmental SNR " + mine + " vs ffmpeg " + theirs);
    }

    @Test
    @DisplayName("HE-AAC at 48 kbit/s decodes in ffmpeg and keeps more bandwidth than ffmpeg's AAC-LC at that rate")
    void heAac() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = stereo();
        var options = AacEncoderOptions.builder().format(48000, ChannelLayout.STEREO).bitrate(48000)
                .profile(AacProfile.HE).signaling(AacSignaling.IMPLICIT).build();
        var mine = AacTestSupport.deinterleave(Oracle.decodeAudio(aac("heaac-stereo-48000", options, input)), 2);
        var theirs = AacTestSupport.deinterleave(Oracle.decodeAudio(ffmpegEncode("heaac-stereo-48000", input, "aac",
                48000, "adts")), 2);
        assertEquals(2, mine.length);
        assertTrue(mine[0].length >= input.frames(), "decoded length " + mine[0].length);
        var mineWidth = TestSignals.bandwidth(input.plane(0), mine[0], 48000);
        var theirWidth = TestSignals.bandwidth(input.plane(0), theirs[0], 48000);
        System.out.printf("HE-AAC bandwidth %.0f Hz, ffmpeg AAC-LC %.0f Hz%n", mineWidth, theirWidth);
        assertTrue(mineWidth > theirWidth, "bandwidth " + mineWidth + " vs ffmpeg AAC-LC " + theirWidth);
    }

    @Test
    @DisplayName("the Tears of Steel stems ship with their licence")
    void licence() throws IOException {
        var text = Files.readString(Conformance.vector("blender-ToS-copyright.txt"));
        assertTrue(text.contains("Creative Commons Attribution-NoDerivs 3.0"), text);
    }
}

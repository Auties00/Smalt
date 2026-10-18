package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers content-based container recognition and the {@link AudioReader} pipeline: any supported
 * file decodes to a 440 Hz tone at the requested rate and layout, with exactly the container's
 * declared number of samples where the container declares it.
 */
class AudioReaderTest {
    private static final double SECONDS = 2.0;

    // ffmpeg's sine source has amplitude 1/8 (RMS 1/8/sqrt(2)); the tone fixtures upmix it to
    // stereo, which lowers each channel by 3 dB to an RMS of 1/16
    private static final double MONO_FIXTURE_RMS = 0.125 / Math.sqrt(2);
    private static final double STEREO_FIXTURE_RMS = 0.0625;

    static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of("mp3", ContainerFormat.MPEG_AUDIO, List.of("-c:a", "libmp3lame", "-b:a", "128k"), true),
                Arguments.of("mp3", ContainerFormat.MPEG_AUDIO, List.of("-c:a", "libmp3lame", "-b:a", "96k", "-id3v2_version", "4",
                        "-metadata", "title=tagged"), true),
                Arguments.of("m4a", ContainerFormat.MP4, List.of("-c:a", "aac", "-b:a", "128k"), true),
                Arguments.of("ogg", ContainerFormat.OGG, List.of("-c:a", "libopus", "-b:a", "64k"), true),
                Arguments.of("oga", ContainerFormat.OGG, List.of("-c:a", "libvorbis", "-q:a", "4", "-f", "ogg"), true),
                Arguments.of("wav", ContainerFormat.WAV, List.of("-c:a", "pcm_s16le"), true),
                Arguments.of("flac", ContainerFormat.FLAC, List.of("-c:a", "flac"), true),
                Arguments.of("aac", ContainerFormat.ADTS, List.of("-c:a", "aac", "-b:a", "96k", "-f", "adts"), false),
                Arguments.of("aiff", ContainerFormat.AIFF, List.of("-c:a", "pcm_s16be"), true),
                Arguments.of("webm", ContainerFormat.MATROSKA, List.of("-c:a", "libopus", "-b:a", "64k"), true));
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("formats")
    @DisplayName("recognizes the container and reads a 16 kHz mono tone of the declared length")
    void readsEveryFormat(String extension, ContainerFormat format, List<String> codec, boolean exactLength) {
        var file = Fixtures.tone(extension, 440, 44_100, 2, SECONDS, codec);
        var source = MediaSource.of(Fixtures.read(file));
        assertEquals(Optional.of(format), Containers.detect(source));
        try (var reader = AudioReader.open(source, AudioReaderOptions.of(16_000, ChannelLayout.MONO))) {
            assertEquals(16_000, reader.sampleRate());
            assertEquals(ChannelLayout.MONO, reader.layout());
            var samples = readAll(reader);
            var expected = (int) (SECONDS * 16_000);
            if (exactLength) {
                assertEquals(expected, samples.length, 2, "exact trimmed length");
            } else {
                // ADTS carries no priming or end-trim information: the encoder's priming frame and
                // the last frame's padding stay in the output, at most two frames in total
                assertTrue(samples.length >= expected && samples.length <= expected + 2 * 1024 * 16_000 / 44_100 + 2,
                        "length " + samples.length + " within two frames above " + expected);
            }
            assertTone(samples, 16_000, 440, STEREO_FIXTURE_RMS);
        }
    }

    @Test
    @DisplayName("reads the audio track of a video file and keeps the source rate and layout when asked")
    void videoSourceFormat() {
        var file = Fixtures.testVideo("mp4", 160, 120, SECONDS, List.of("-c:v", "libx264", "-c:a", "aac", "-shortest"));
        try (var reader = AudioReader.open(MediaSource.of(Fixtures.read(file)))) {
            assertEquals(ContainerFormat.MP4, reader.format());
            assertEquals(48_000, reader.sampleRate());
            assertTone(readAll(reader), 48_000, 440, MONO_FIXTURE_RMS);
        }
    }

    @Test
    @DisplayName("rejects inputs without a decodable audio track")
    void rejectsNonAudio() {
        var video = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=5:duration=1"),
                List.of("-c:v", "libx264"));
        var noAudio = assertThrows(MediaException.Unsupported.class,
                () -> AudioReader.open(MediaSource.of(Fixtures.read(video))));
        assertEquals("no audio", noAudio.format());
        var text = MediaSource.of("plain text, not media".getBytes());
        assertEquals(Optional.empty(), Containers.detect(text));
        assertThrows(MediaException.Unsupported.class, () -> AudioReader.open(text, AudioReaderOptions.defaults()));
    }

    private static float[] readAll(AudioReader reader) {
        var chunks = new ArrayList<float[]>();
        var total = 0;
        AudioBuffer buffer;
        while ((buffer = reader.read()) != null) {
            chunks.add(java.util.Arrays.copyOf(buffer.plane(0), buffer.frames()));
            total += buffer.frames();
        }
        var samples = new float[total];
        var offset = 0;
        for (var chunk : chunks) {
            System.arraycopy(chunk, 0, samples, offset, chunk.length);
            offset += chunk.length;
        }
        return samples;
    }

    /**
     * Checks that the middle of the signal is a sine of the expected frequency and level,
     * measured by zero crossings and RMS.
     */
    private static void assertTone(float[] samples, int rate, int frequency, double expectedRms) {
        var from = samples.length / 4;
        var to = samples.length * 3 / 4;
        var crossings = 0;
        double energy = 0;
        for (var i = from + 1; i < to; i++) {
            if ((samples[i - 1] < 0) != (samples[i] < 0)) {
                crossings++;
            }
            energy += (double) samples[i] * samples[i];
        }
        var measured = crossings / 2.0 / ((to - from) / (double) rate);
        assertEquals(frequency, measured, frequency * 0.02, "tone frequency");
        var rms = Math.sqrt(energy / (to - from));
        assertEquals(expectedRms, rms, 0.005, "tone level");
    }
}

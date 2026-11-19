package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.audio.process.Resampler;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.transcode.OutputContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link AudioWriter} with the codecs of every artifact: a tone written in blocks of
 * awkward sizes comes back from an {@link AudioReader}, and from ffmpeg, with the written length
 * where the container stores the trim and aligned with the input (a misaligned decode loses the
 * waveform match the SNR bound asks for); lossless codecs return the samples bit for bit.
 */
class AudioWriterCodecTest {
    private static final Path DIRECTORY = Scratch.directory("audio-writer");
    private static final AtomicInteger OUTPUTS = new AtomicInteger();
    private static final int RATE = 44_100;
    private static final int FRAMES = 2 * RATE + 123;
    private static final int[] BLOCKS = {4096, 1, 777, 2048, 13, 1500};

    static Stream<Arguments> lossy() {
        // container, codec, whether the container stores the encoder delay and the end trim, the lowest SNR in dB,
        // and the extra samples ffmpeg decodes: it ignores the end of an MP4 edit list, keeping up to one frame of
        // padding, applies the Matroska CodecDelay of Opus only, keeping the AAC priming frame, and Matroska stores no
        // end trim for Vorbis, whose last block size varies
        return Stream.of(
                Arguments.of(OutputContainer.OGG, Codec.OPUS, true, 12.0, 0),
                Arguments.of(OutputContainer.WEBM, Codec.OPUS, true, 12.0, 0),
                Arguments.of(OutputContainer.MP4, Codec.OPUS, true, 12.0, 960),
                Arguments.of(OutputContainer.OGG, Codec.VORBIS, true, 12.0, 0),
                Arguments.of(OutputContainer.WEBM, Codec.VORBIS, true, 12.0, 2048),
                Arguments.of(OutputContainer.M4A, Codec.AAC, true, 12.0, 1024),
                Arguments.of(OutputContainer.MATROSKA, Codec.AAC, true, 12.0, 1024),
                Arguments.of(OutputContainer.MP3, Codec.MP3, true, 12.0, 0),
                Arguments.of(OutputContainer.ADTS, Codec.AAC, false, 0.0, 0),
                Arguments.of(OutputContainer.AC3, Codec.AC3, false, 0.0, 0));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("lossy")
    @DisplayName("returns the written tone aligned and, where the container stores the trim, of the written length")
    void lossyRoundTrip(OutputContainer container, Codec codec, boolean trimmed, double minimumSnr, int ffmpegSlack) {
        var input = tone(2, RATE, FRAMES);
        var options = AudioWriterOptions.of(RATE, ChannelLayout.STEREO).toBuilder().codec(codec).build();
        var sink = MediaSink.memory();
        int codedRate;
        try (var writer = AudioWriter.open(sink, container, options)) {
            codedRate = writer.track().sampleRate();
            assertEquals(codec, writer.codec());
            writeFloats(writer, input);
            assertEquals(FRAMES, writer.framesWritten());
            writer.finish();
        }
        var file = save(container, sink.toByteArray());
        var expected = codedRate == RATE ? input : resample(input, RATE, codedRate);
        var expectedFrames = expected[0].length;
        float[][] read;
        String declared;
        try (var reader = AudioReader.open(MediaSource.of(sink.toByteArray()))) {
            declared = "skip " + reader.track().skipSamples() + ", duration " + reader.track().duration();
            assertEquals(codec, reader.track().codec());
            assertEquals(codedRate, reader.sampleRate());
            read = readAll(reader);
        }
        if (trimmed) {
            assertEquals(expectedFrames, read[0].length, "decoded length; the file declares " + declared);
            for (var channel = 0; channel < 2; channel++) {
                var snr = Quality.snr(expected[channel], read[channel]);
                assertTrue(snr > minimumSnr, "channel " + channel + " SNR " + snr + " dB");
            }
        } else {
            assertTrue(read[0].length >= expectedFrames && read[0].length <= expectedFrames + 3 * 1536,
                    "untrimmed length " + read[0].length + " within three frames above " + expectedFrames);
        }
        Oracle.assertDecodesCleanly(file);
        if (trimmed) {
            var ffmpegFrames = Oracle.decodeAudio(file).length / 2;
            assertTrue(ffmpegFrames >= expectedFrames && ffmpegFrames <= expectedFrames + ffmpegSlack,
                    "ffmpeg's decoded length " + ffmpegFrames + ", written " + expectedFrames);
        }
    }

    static Stream<Arguments> lossless() {
        return Stream.of(
                Arguments.of(OutputContainer.FLAC, Codec.FLAC, 16),
                Arguments.of(OutputContainer.OGG_FLAC, Codec.FLAC, 24),
                Arguments.of(OutputContainer.MATROSKA, Codec.FLAC, 16),
                Arguments.of(OutputContainer.M4A, Codec.ALAC, 16),
                Arguments.of(OutputContainer.MP4, Codec.FLAC, 24));
    }

    @ParameterizedTest(name = "{0} {1} {2} bits")
    @MethodSource("lossless")
    @DisplayName("returns lossless integer samples bit for bit, and ffmpeg decodes the same samples")
    void losslessRoundTrip(OutputContainer container, Codec codec, int bits) {
        var random = new Random(bits);
        var samples = new int[2][FRAMES];
        for (var channel = 0; channel < 2; channel++) {
            for (var index = 0; index < FRAMES; index++) {
                var signal = StrictMath.sin(2 * StrictMath.PI * 440 * index / RATE) * 0.5 * (1 << (bits - 1));
                samples[channel][index] = (int) Math.round(signal) + random.nextInt(64) - 32;
            }
        }
        var options = AudioWriterOptions.of(RATE, ChannelLayout.STEREO).toBuilder().codec(codec).bitsPerSample(bits)
                .build();
        if (!container.supports(codec)) {
            fail(container + " should carry " + codec);
        }
        var sink = MediaSink.memory();
        try (var writer = AudioWriter.open(sink, container, options)) {
            var offset = 0;
            for (var index = 0; offset < FRAMES; index++) {
                var frames = Math.min(BLOCKS[index % BLOCKS.length], FRAMES - offset);
                var planes = new int[2][];
                for (var channel = 0; channel < 2; channel++) {
                    planes[channel] = Arrays.copyOfRange(samples[channel], offset, offset + frames);
                }
                writer.write(new IntegerAudioBuffer(ChannelLayout.STEREO, RATE, bits, planes, frames));
                offset += frames;
            }
            writer.finish();
        }
        var file = save(container, sink.toByteArray());
        try (var reader = AudioReader.open(MediaSource.of(sink.toByteArray()),
                AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).verifyIntegrity(true).build())) {
            var read = new int[2][FRAMES];
            var count = 0;
            for (var block = reader.readIntegers(); block != null; block = reader.readIntegers()) {
                assertEquals(bits, block.bitsPerSample());
                assertTrue(count + block.frames() <= FRAMES, "more samples than written");
                for (var channel = 0; channel < 2; channel++) {
                    System.arraycopy(block.plane(channel), 0, read[channel], count, block.frames());
                }
                count += block.frames();
            }
            assertEquals(FRAMES, count);
            assertArrayEquals(samples[0], read[0]);
            assertArrayEquals(samples[1], read[1]);
        }
        Oracle.assertDecodesCleanly(file);
        var ffmpeg = Oracle.decodeAudio(file);
        assertEquals(FRAMES * 2, ffmpeg.length, "ffmpeg's decoded length");
        var scale = StrictMath.scalb(1.0, 1 - bits);
        for (var index = 0; index < FRAMES; index += 97) {
            assertEquals(samples[0][index] * scale, ffmpeg[2 * index], 1e-6, "ffmpeg sample " + index);
        }
    }

    @Test
    @DisplayName("codes Opus at 48 kHz from 44.1 kHz input when the rate is left free, and mono at the input layout")
    void opusFallsBackTo48k() {
        var input = tone(1, RATE, FRAMES);
        var sink = MediaSink.memory();
        try (var writer = AudioWriter.open(sink, OutputContainer.OGG, AudioWriterOptions.of(RATE, ChannelLayout.MONO))) {
            assertEquals(Codec.OPUS, writer.codec());
            assertEquals(48_000, writer.track().sampleRate());
            assertEquals(ChannelLayout.MONO, writer.track().layout().orElseThrow());
            writeFloats(writer, input);
            writer.finish();
        }
        try (var reader = AudioReader.open(MediaSource.of(sink.toByteArray()))) {
            var read = readAll(reader);
            assertEquals(Math.round(FRAMES * 48_000.0 / RATE), read[0].length);
            var snr = Quality.snr(resample(input, RATE, 48_000)[0], read[0]);
            assertTrue(snr > 12, "SNR " + snr + " dB");
        }
        assertThrows(IllegalArgumentException.class, () -> AudioWriter.open(MediaSink.memory(), OutputContainer.OGG,
                AudioWriterOptions.of(RATE, ChannelLayout.MONO).toBuilder().outputSampleRate(RATE).build()),
                "Opus codes no 44.1 kHz and the rate was fixed");
    }

    private static void writeFloats(AudioWriter writer, float[][] input) {
        var offset = 0;
        for (var index = 0; offset < FRAMES; index++) {
            var frames = Math.min(BLOCKS[index % BLOCKS.length], FRAMES - offset);
            var planes = new float[input.length][];
            for (var channel = 0; channel < input.length; channel++) {
                planes[channel] = Arrays.copyOfRange(input[channel], offset, offset + frames);
            }
            writer.write(new AudioBuffer(writer.layout(), RATE, planes, frames));
            offset += frames;
        }
    }

    private static float[][] readAll(AudioReader reader) {
        var channels = reader.layout().channels();
        var planes = new float[channels][0];
        var count = 0;
        for (var block = reader.read(); block != null; block = reader.read()) {
            for (var channel = 0; channel < channels; channel++) {
                planes[channel] = Arrays.copyOf(planes[channel], count + block.frames());
                System.arraycopy(block.plane(channel), 0, planes[channel], count, block.frames());
            }
            count += block.frames();
        }
        return planes;
    }

    private static float[][] tone(int channels, int rate, int frames) {
        var planes = new float[channels][frames];
        for (var channel = 0; channel < channels; channel++) {
            for (var index = 0; index < frames; index++) {
                planes[channel][index] = (float) (0.4 * StrictMath.sin(2 * StrictMath.PI * (440 + 220 * channel) * index
                        / rate));
            }
        }
        return planes;
    }

    private static float[][] resample(float[][] input, int inputRate, int outputRate) {
        var resampler = new Resampler(inputRate, outputRate, input.length, Resampler.Quality.STANDARD);
        var output = new float[input.length][resampler.maxOutputFrames(input[0].length) + 64];
        var count = resampler.process(input, input[0].length, output, 0);
        count += resampler.flush(output, count);
        var trimmed = new float[input.length][];
        for (var channel = 0; channel < input.length; channel++) {
            trimmed[channel] = Arrays.copyOf(output[channel], count);
        }
        return trimmed;
    }

    private static Path save(OutputContainer container, byte[] data) {
        try {
            Files.createDirectories(DIRECTORY);
            var file = DIRECTORY.resolve("writer-" + OUTPUTS.incrementAndGet() + "." + container.extension());
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

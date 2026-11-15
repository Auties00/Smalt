package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.process.ChannelMixer;
import com.github.auties00.smalt.audio.process.Resampler;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.transcode.OutputContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link AudioWriter} with the PCM encoders of core: every block written comes back from an
 * {@link AudioReader} of the file, exactly for integer, float and double samples, and through the
 * mixer and the resampler when the coded format differs from the input.
 */
class AudioWriterTest {
    private static final AtomicInteger OUTPUTS = new AtomicInteger();

    // block sizes that never align with a codec frame or with each other
    private static final int[] BLOCKS = {1, 4096, 333, 17, 2048, 999, 7};

    static Stream<Arguments> integerFormats() {
        return Stream.of(
                Arguments.of(OutputContainer.WAV, Codec.PCM_S16LE, 16),
                Arguments.of(OutputContainer.WAV, Codec.PCM_S24LE, 24),
                Arguments.of(OutputContainer.AIFF, Codec.PCM_S16BE, 16),
                Arguments.of(OutputContainer.MATROSKA, Codec.PCM_S16LE, 16),
                Arguments.of(OutputContainer.MATROSKA, Codec.PCM_S32LE, 32),
                Arguments.of(OutputContainer.MOV, Codec.PCM_S24LE, 24));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("integerFormats")
    @DisplayName("returns the written integer samples unchanged and as many of them")
    void integerRoundTrip(OutputContainer container, Codec codec, int bits) {
        var layout = ChannelLayout.STEREO;
        var samples = randomIntegers(layout.channels(), 20_000, bits, 1);
        var options = AudioWriterOptions.of(44_100, layout).toBuilder().codec(codec).bitsPerSample(bits).build();
        var file = write(container, options, writer -> writeIntegers(writer, samples, bits));
        try (var reader = AudioReader.open(MediaSource.of(file),
                AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).build())) {
            assertEquals(44_100, reader.sampleRate());
            assertEquals(layout, reader.layout());
            assertEquals(codec, reader.track().codec());
            var read = readIntegers(reader, layout.channels());
            assertEquals(bits, reader.bitsPerSample());
            for (var channel = 0; channel < samples.length; channel++) {
                assertArrayEquals(samples[channel], read[channel], "channel " + channel);
            }
        }
    }

    @Test
    @DisplayName("keeps 64-bit float samples exactly in PCM_F64LE, in WAV and Matroska")
    void doubleRoundTrip() {
        var random = new Random(2);
        var samples = new double[2][12_345];
        for (var plane : samples) {
            for (var index = 0; index < plane.length; index++) {
                plane[index] = random.nextDouble() * 2 - 1;
            }
        }
        for (var container : new OutputContainer[]{OutputContainer.WAV, OutputContainer.MATROSKA}) {
            var options = AudioWriterOptions.of(48_000, ChannelLayout.STEREO).toBuilder().codec(Codec.PCM_F64LE).build();
            var file = write(container, options, writer -> {
                var offset = 0;
                for (var index = 0; offset < samples[0].length; index++) {
                    var frames = Math.min(BLOCKS[index % BLOCKS.length], samples[0].length - offset);
                    var block = new DoubleAudioBuffer(ChannelLayout.STEREO, 48_000, slice(samples, offset, frames), frames);
                    writer.write(block);
                    offset += frames;
                }
            });
            try (var reader = AudioReader.open(MediaSource.of(file),
                    AudioReaderOptions.builder().sampleFormat(SampleFormat.DOUBLE).build())) {
                var read = new double[2][samples[0].length];
                var count = 0;
                for (var block = reader.readDoubles(); block != null; block = reader.readDoubles()) {
                    for (var channel = 0; channel < 2; channel++) {
                        System.arraycopy(block.plane(channel), 0, read[channel], count, block.frames());
                    }
                    count += block.frames();
                }
                assertEquals(samples[0].length, count, container + " sample count");
                assertArrayEquals(samples[0], read[0]);
                assertArrayEquals(samples[1], read[1]);
            }
        }
    }

    @Test
    @DisplayName("keeps float samples exactly in PCM_F32LE and reports the written length")
    void floatRoundTrip() {
        var samples = tone(1, 22_050, 22_050, 1_000, 0.5f);
        var options = AudioWriterOptions.of(22_050, ChannelLayout.MONO).toBuilder().codec(Codec.PCM_F32LE).build();
        var sink = MediaSink.memory();
        try (var writer = AudioWriter.open(sink, OutputContainer.WAV, options)) {
            assertEquals(Codec.PCM_F32LE, writer.codec());
            assertEquals(Codec.PCM_F32LE, writer.track().codec());
            assertEquals(22_050, writer.sampleRate());
            assertEquals(ChannelLayout.MONO, writer.layout());
            writeFloats(writer, samples, 22_050, ChannelLayout.MONO);
            assertEquals(22_050, writer.framesWritten());
            assertEquals(1_000_000, writer.durationMicros());
            writer.finish();
        }
        var file = validated("wav", sink.toByteArray());
        assertArrayEquals(samples[0], readFloats(file, AudioReaderOptions.defaults())[0]);
    }

    @Test
    @DisplayName("resamples and upmixes to the coded format exactly as the resampler and the mixer do")
    void convertsRateAndLayout() {
        var input = tone(1, 44_100, 30_001, 997, 0.4f);
        var options = AudioWriterOptions.of(44_100, ChannelLayout.MONO).toBuilder().codec(Codec.PCM_F32LE)
                .outputSampleRate(48_000).outputLayout(ChannelLayout.STEREO).build();
        var file = write(OutputContainer.MATROSKA, options, writer -> writeFloats(writer, input, 44_100, ChannelLayout.MONO));
        var expected = reference(input, 44_100, 48_000, ChannelLayout.MONO, ChannelLayout.STEREO);
        try (var reader = AudioReader.open(MediaSource.of(file))) {
            assertEquals(48_000, reader.sampleRate());
            assertEquals(ChannelLayout.STEREO, reader.layout());
        }
        var read = readFloats(file, AudioReaderOptions.defaults());
        assertEquals(Math.round(30_001 * 48_000 / 44_100.0), read[0].length, "resampled length");
        assertArrayEquals(expected[0], read[0]);
        assertArrayEquals(expected[1], read[1]);
    }

    @Test
    @DisplayName("downmixes before resampling, matching the mixer then the resampler")
    void downmixThenResample() {
        var input = tone(2, 48_000, 24_000, 440, 0.3f);
        var options = AudioWriterOptions.of(48_000, ChannelLayout.STEREO).toBuilder().codec(Codec.PCM_F32LE)
                .outputSampleRate(16_000).outputLayout(ChannelLayout.MONO).build();
        var file = write(OutputContainer.WAV, options, writer -> writeFloats(writer, input, 48_000, ChannelLayout.STEREO));
        var expected = reference(input, 48_000, 16_000, ChannelLayout.STEREO, ChannelLayout.MONO);
        var read = readFloats(file, AudioReaderOptions.defaults());
        assertEquals(8_000, read[0].length);
        assertArrayEquals(expected[0], read[0]);
    }

    @Test
    @DisplayName("converts integer and double blocks to floats when the input is resampled, as float blocks would be")
    void convertedIntegerAndDoubleInput() {
        var integers = randomIntegers(1, 9_000, 16, 3);
        var floats = new float[1][9_000];
        var doubles = new double[1][9_000];
        for (var index = 0; index < 9_000; index++) {
            floats[0][index] = integers[0][index] / 32768f;
            doubles[0][index] = floats[0][index];
        }
        var options = AudioWriterOptions.of(32_000, ChannelLayout.MONO).toBuilder().codec(Codec.PCM_F32LE)
                .outputSampleRate(48_000).build();
        var fromFloats = write(OutputContainer.WAV, options, writer -> writeFloats(writer, floats, 32_000, ChannelLayout.MONO));
        var fromIntegers = write(OutputContainer.WAV, options, writer -> writeIntegers(writer, integers, 16));
        var fromDoubles = write(OutputContainer.WAV, options, writer ->
                writer.write(new DoubleAudioBuffer(ChannelLayout.MONO, 32_000, doubles, 9_000)));
        assertArrayEquals(fromFloats, fromIntegers);
        assertArrayEquals(fromFloats, fromDoubles);
        assertEquals(13_500, readFloats(fromFloats, AudioReaderOptions.defaults())[0].length);
    }

    @Test
    @DisplayName("mixes integer and float blocks in one stream")
    void mixedBlockKinds() {
        var integers = randomIntegers(2, 6_000, 16, 4);
        var options = AudioWriterOptions.of(48_000, ChannelLayout.STEREO).toBuilder().codec(Codec.PCM_S16LE).build();
        var file = write(OutputContainer.WAV, options, writer -> {
            writer.write(new IntegerAudioBuffer(ChannelLayout.STEREO, 48_000, 16, slice(integers, 0, 3_000), 3_000));
            var floats = new float[2][3_000];
            for (var channel = 0; channel < 2; channel++) {
                for (var index = 0; index < 3_000; index++) {
                    floats[channel][index] = integers[channel][3_000 + index] / 32768f;
                }
            }
            writer.write(new AudioBuffer(ChannelLayout.STEREO, 48_000, floats, 3_000));
            assertEquals(6_000, writer.framesWritten());
        });
        try (var reader = AudioReader.open(MediaSource.of(file),
                AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).build())) {
            var read = readIntegers(reader, 2);
            assertArrayEquals(integers[0], read[0]);
            assertArrayEquals(integers[1], read[1]);
        }
    }

    @Test
    @DisplayName("picks the profile's first encodable codec and falls back to 48 kHz stereo only when the format is free")
    void automaticChoices() {
        var sink = MediaSink.memory();
        try (var writer = AudioWriter.open(sink, OutputContainer.WAV)) {
            assertEquals(Codec.PCM_S16LE, writer.codec());
            assertEquals(48_000, writer.track().sampleRate());
            assertEquals(ChannelLayout.STEREO, writer.track().layout().orElseThrow());
            writer.finish();
        }
        try (var reader = AudioReader.open(MediaSource.of(sink.toByteArray()))) {
            assertNull(reader.read(), "a writer finished without samples writes an empty track");
        }
        assertThrows(MediaException.Unsupported.class, () -> AudioWriter.open(MediaSink.memory(), OutputContainer.OGG),
                "core alone has no Opus or Vorbis encoder");
        assertThrows(IllegalArgumentException.class, () -> AudioWriter.open(MediaSink.memory(), OutputContainer.IVF));
        assertThrows(IllegalArgumentException.class, () -> AudioWriter.open(MediaSink.memory(), OutputContainer.WEBM,
                AudioWriterOptions.builder().codec(Codec.PCM_S16LE).build()));
    }

    @Test
    @DisplayName("writes the language, the tags and through a caller's muxer")
    void metadataAndMuxer() {
        var tags = MediaTags.builder().set(StandardTag.TITLE, "writer test").build();
        var samples = randomIntegers(1, 4_000, 16, 5);
        var stream = new ByteArrayOutputStream();
        var options = AudioWriterOptions.of(16_000, ChannelLayout.MONO).toBuilder().codec(Codec.PCM_S16LE)
                .language("ita").tags(tags).build();
        var muxer = new MatroskaMuxer(MediaSink.of(stream), MatroskaMuxerOptions.matroska().toBuilder().live(true).build());
        try (var writer = AudioWriter.open(muxer, options)) {
            assertEquals(1, writer.track().id());
            writeIntegers(writer, samples, 16);
            writer.finish();
        }
        var file = validated("mkv", stream.toByteArray());
        assertEquals(Optional.of("writer test"), TagEditor.read(MediaSource.of(file)).get(StandardTag.TITLE));
        try (var reader = AudioReader.open(MediaSource.of(file),
                AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).build())) {
            assertEquals(Optional.of("ita"), reader.track().language());
            assertArrayEquals(samples[0], readIntegers(reader, 1)[0]);
        }
        var wav = write(OutputContainer.WAV, options.toBuilder().noLanguage().build(), writer -> writeIntegers(writer, samples, 16));
        assertEquals(Optional.of("writer test"), TagEditor.read(MediaSource.of(wav)).get(StandardTag.TITLE));
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {
        @Test
        @DisplayName("blocks in another format and calls after finishing")
        void misuse() {
            var sink = MediaSink.memory();
            try (var writer = AudioWriter.open(sink, OutputContainer.WAV, AudioWriterOptions.of(44_100, ChannelLayout.STEREO))) {
                assertThrows(IllegalArgumentException.class,
                        () -> writer.write(new AudioBuffer(ChannelLayout.STEREO, 48_000, 10)));
                assertThrows(IllegalArgumentException.class,
                        () -> writer.write(new IntegerAudioBuffer(ChannelLayout.MONO, 44_100, 16, 10)));
                writer.write(new AudioBuffer(ChannelLayout.STEREO, 44_100, 10));
                writer.finish();
                assertThrows(IllegalStateException.class, writer::finish);
                assertThrows(IllegalStateException.class,
                        () -> writer.write(new AudioBuffer(ChannelLayout.STEREO, 44_100, 10)));
            }
        }

        @Test
        @DisplayName("a muxer writer without a codec, tags a muxer cannot store, and invalid options")
        void invalidSetup() {
            var memory = MediaSink.memory();
            assertThrows(IllegalArgumentException.class,
                    () -> AudioWriter.open(Containers.openMuxer(OutputContainer.WAV, memory), AudioWriterOptions.defaults()));
            var tagged = AudioWriterOptions.builder().codec(Codec.PCM_S16LE)
                    .tags(MediaTags.builder().set(StandardTag.TITLE, "x").build()).build();
            assertThrows(MediaException.Unsupported.class, () -> AudioWriter.open(new UntaggedMuxer(
                    Containers.openMuxer(OutputContainer.WAV, memory)), tagged));
            assertThrows(IllegalArgumentException.class, () -> AudioWriterOptions.builder().sampleRate(0).build());
            assertThrows(IllegalArgumentException.class, () -> AudioWriterOptions.builder().bitsPerSample(33).build());
            assertThrows(IllegalArgumentException.class, () -> AudioWriterOptions.builder().language("IT").build());
            assertThrows(IllegalArgumentException.class, () -> AudioWriterOptions.builder().codec(Codec.H264));
            assertThrows(IllegalArgumentException.class, () -> AudioWriter.open(MediaSink.memory(), OutputContainer.WAV,
                    AudioWriterOptions.builder().codec(Codec.PCM_S16LE).outputSampleRate(8_000).sampleRate(8_000 * 300)
                            .build()), "a ratio the resampler cannot reach");
        }

        @Test
        @DisplayName("every call after a failed write, and the failure reaches the caller")
        void afterFailure() {
            var failing = new UntaggedMuxer(Containers.openMuxer(OutputContainer.WAV, MediaSink.memory())) {
                @Override
                public void writePacket(Packet packet) {
                    throw new MediaException.Io("disk full", new java.io.IOException("disk full"));
                }
            };
            try (var writer = AudioWriter.open(failing, AudioWriterOptions.builder().codec(Codec.PCM_S16LE).build())) {
                var block = new AudioBuffer(ChannelLayout.STEREO, 48_000, 8_192);
                block.setFrames(8_192);
                assertThrows(MediaException.Io.class, () -> {
                    writer.write(block);
                    writer.write(block);
                    writer.finish();
                });
                assertThrows(IllegalStateException.class, () -> writer.write(block));
                assertThrows(IllegalStateException.class, writer::finish);
            }
        }

        @Test
        @DisplayName("options compare by value and rebuild unchanged")
        void optionsValues() {
            var options = AudioWriterOptions.builder().codec(Codec.FLAC).sampleRate(96_000).layout(ChannelLayout.MONO)
                    .outputSampleRate(48_000).outputLayout(ChannelLayout.STEREO).bitrate(64_000).bitsPerSample(24)
                    .realtime(true).language("eng").build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            var reset = options.toBuilder().automaticCodec().automaticOutputSampleRate().automaticOutputLayout()
                    .automaticBitrate().automaticBitsPerSample().realtime(false).noLanguage()
                    .sampleRate(48_000).layout(ChannelLayout.STEREO).build();
            assertEquals(AudioWriterOptions.defaults(), reset);
            assertNotEquals(AudioWriterOptions.defaults(), options);
        }
    }

    /**
     * Delegates to a muxer but reports that it stores no tags.
     */
    private static class UntaggedMuxer implements Muxer {
        private final Muxer muxer;

        UntaggedMuxer(Muxer muxer) {
            this.muxer = muxer;
        }

        @Override
        public void addTrack(Track track) {
            muxer.addTrack(track);
        }

        @Override
        public void writePacket(Packet packet) {
            muxer.writePacket(packet);
        }

        @Override
        public void finish() {
            muxer.finish();
        }

        @Override
        public void close() {
            muxer.close();
        }
    }

    private static byte[] write(OutputContainer container, AudioWriterOptions options, Consumer<AudioWriter> body) {
        var sink = MediaSink.memory();
        try (var writer = AudioWriter.open(sink, container, options)) {
            body.accept(writer);
            writer.finish();
        }
        return validated(container.extension(), sink.toByteArray());
    }

    private static byte[] validated(String extension, byte[] data) {
        Validators.assertValid("audio-writer-" + OUTPUTS.incrementAndGet() + "." + extension, data);
        return data;
    }

    private static void writeIntegers(AudioWriter writer, int[][] samples, int bits) {
        var layout = writer.layout();
        var offset = 0;
        for (var index = 0; offset < samples[0].length; index++) {
            var frames = Math.min(BLOCKS[index % BLOCKS.length], samples[0].length - offset);
            writer.write(new IntegerAudioBuffer(layout, writer.sampleRate(), bits, slice(samples, offset, frames), frames));
            offset += frames;
        }
    }

    private static void writeFloats(AudioWriter writer, float[][] samples, int rate, ChannelLayout layout) {
        var offset = 0;
        for (var index = 0; offset < samples[0].length; index++) {
            var frames = Math.min(BLOCKS[index % BLOCKS.length], samples[0].length - offset);
            writer.write(new AudioBuffer(layout, rate, slice(samples, offset, frames), frames));
            offset += frames;
        }
    }

    private static int[][] readIntegers(AudioReader reader, int channels) {
        var planes = new int[channels][0];
        var count = 0;
        for (var block = reader.readIntegers(); block != null; block = reader.readIntegers()) {
            for (var channel = 0; channel < channels; channel++) {
                planes[channel] = Arrays.copyOf(planes[channel], count + block.frames());
                System.arraycopy(block.plane(channel), 0, planes[channel], count, block.frames());
            }
            count += block.frames();
        }
        return planes;
    }

    private static float[][] readFloats(byte[] file, AudioReaderOptions options) {
        try (var reader = AudioReader.open(MediaSource.of(file), options)) {
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
    }

    /**
     * Converts a whole signal in one pass with the mixer and the resampler, in the order the writer
     * applies them.
     */
    private static float[][] reference(float[][] input, int inputRate, int outputRate, ChannelLayout from,
                                       ChannelLayout to) {
        var frames = input[0].length;
        var mixer = new ChannelMixer(from, to);
        if (to.channels() <= from.channels()) {
            var mixed = new float[to.channels()][frames];
            mixer.process(input, mixed, frames);
            return resample(mixed, inputRate, outputRate);
        }
        var resampled = resample(input, inputRate, outputRate);
        var mixed = new float[to.channels()][resampled[0].length];
        mixer.process(resampled, mixed, resampled[0].length);
        return mixed;
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

    private static float[][] tone(int channels, int rate, int frames, double hertz, float amplitude) {
        var planes = new float[channels][frames];
        for (var channel = 0; channel < channels; channel++) {
            for (var index = 0; index < frames; index++) {
                planes[channel][index] = (float) (amplitude * StrictMath.sin(2 * StrictMath.PI * hertz * (index + channel * 7)
                        / rate));
            }
        }
        return planes;
    }

    private static int[][] randomIntegers(int channels, int frames, int bits, long seed) {
        var random = new Random(seed);
        var planes = new int[channels][frames];
        var min = bits == 32 ? Integer.MIN_VALUE : -(1 << (bits - 1));
        var max = bits == 32 ? Integer.MAX_VALUE : (1 << (bits - 1)) - 1;
        for (var plane : planes) {
            for (var index = 0; index < frames; index++) {
                plane[index] = index % 997 == 0 ? (index % 2 == 0 ? min : max)
                        : (int) (random.nextLong(max - (long) min + 1) + min);
            }
        }
        return planes;
    }

    private static float[][] slice(float[][] planes, int offset, int frames) {
        var result = new float[planes.length][];
        for (var channel = 0; channel < planes.length; channel++) {
            result[channel] = Arrays.copyOfRange(planes[channel], offset, offset + frames);
        }
        return result;
    }

    private static int[][] slice(int[][] planes, int offset, int frames) {
        var result = new int[planes.length][];
        for (var channel = 0; channel < planes.length; channel++) {
            result[channel] = Arrays.copyOfRange(planes[channel], offset, offset + frames);
        }
        return result;
    }

    private static double[][] slice(double[][] planes, int offset, int frames) {
        var result = new double[planes.length][];
        for (var channel = 0; channel < planes.length; channel++) {
            result[channel] = Arrays.copyOfRange(planes[channel], offset, offset + frames);
        }
        return result;
    }
}

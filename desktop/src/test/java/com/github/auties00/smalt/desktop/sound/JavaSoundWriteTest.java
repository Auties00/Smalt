package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Java Sound writing")
class JavaSoundWriteTest {
    static AudioInputStream sine(int sampleRate, int channels, int frames) {
        var format = SampledAudio.pcm16(sampleRate, ChannelLayout.defaultFor(channels));
        var bytes = new byte[frames * channels * 2];
        for (var i = 0; i < frames; i++) {
            for (var c = 0; c < channels; c++) {
                var value = (short) Math.round(12_000 * Math.sin(2 * Math.PI * (440 + 110 * c) * i / sampleRate));
                var at = (i * channels + c) * 2;
                bytes[at] = (byte) value;
                bytes[at + 1] = (byte) (value >> 8);
            }
        }
        return new AudioInputStream(new ByteArrayInputStream(bytes), format, frames);
    }

    static float[][] decode(Path path) {
        try (var source = MediaSource.of(path); var reader = AudioReader.open(source)) {
            assertEquals(ContainerFormat.OGG, reader.format());
            assertEquals(Codec.OPUS, reader.track().codec());
            var channels = reader.layout().channels();
            var planes = new float[channels][0];
            var count = 0;
            for (var block = reader.read(); block != null; block = reader.read()) {
                for (var c = 0; c < channels; c++) {
                    planes[c] = Arrays.copyOf(planes[c], count + block.frames());
                    System.arraycopy(block.plane(c), 0, planes[c], count, block.frames());
                }
                count += block.frames();
            }
            return planes;
        }
    }

    @Test
    @DisplayName("Ogg Opus is offered for PCM streams")
    void types() {
        assertTrue(Arrays.asList(AudioSystem.getAudioFileTypes()).contains(SmaltAudioFormats.OGG_OPUS));
        assertTrue(AudioSystem.isFileTypeSupported(SmaltAudioFormats.OGG_OPUS, sine(48_000, 1, 10)));
    }

    @ParameterizedTest(name = "{0} Hz, {1} channels")
    @CsvSource({"48000, 1, 48000, 1", "48000, 2, 48000, 2", "16000, 1, 48000, 1", "44100, 2, 48000, 2", "48000, 6, 48000, 6",
            "48000, 8, 48000, 8", "48000, 3, 48000, 3"})
    @DisplayName("the file decodes to the input's length at the decoder's rate")
    void lengthAndQuality(int rate, int channels, int decodedRate, int decodedChannels, @TempDir Path directory)
            throws Exception {
        var frames = rate;
        var file = directory.resolve("out.opus");
        var written = AudioSystem.write(sine(rate, channels, frames), SmaltAudioFormats.OGG_OPUS, file.toFile());
        assertEquals(Files.size(file), written);
        var decoded = decode(file);
        assertEquals(decodedChannels, decoded.length);
        var expectedFrames = Math.round((double) frames * decodedRate / rate);
        assertEquals(expectedFrames, decoded[0].length);
        // Opus preserves a pure tone well: compare each decoded channel with its input tone at 48 kHz,
        // except the LFE channel, which codes only the lowest band
        var layout = ChannelLayout.defaultFor(channels);
        for (var c = 0; c < (channels <= 2 ? 1 : channels); c++) {
            if (layout.speakers().get(c) == com.github.auties00.smalt.audio.Speaker.LOW_FREQUENCY) {
                continue;
            }
            var input = new double[(int) expectedFrames];
            for (var i = 0; i < input.length; i++) {
                input[i] = 12_000.0 / 32768 * Math.sin(2 * Math.PI * (440 + 110 * c) * i / decodedRate);
            }
            double signal = 0;
            double noise = 0;
            for (var i = 2_000; i < input.length - 2_000; i++) {
                signal += input[i] * input[i];
                var error = decoded[c][i] - input[i];
                noise += error * error;
            }
            assertTrue(10 * Math.log10(signal / noise) > 15, "SNR of channel " + c + " " + 10 * Math.log10(signal / noise));
        }
    }

    @Test
    @DisplayName("writing to a stream gives the same bytes count as the file")
    void outputStream(@TempDir Path directory) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var written = AudioSystem.write(sine(48_000, 2, 24_000), SmaltAudioFormats.OGG_OPUS, bytes);
        assertEquals(bytes.size(), written);
        var file = directory.resolve("stream.opus");
        Files.write(file, bytes.toByteArray());
        assertEquals(24_000, decode(file)[0].length);
    }

    @Test
    @DisplayName("a compressed stream read through Java Sound transcodes to Opus")
    void transcode(@TempDir Path directory) throws Exception {
        var source = JavaSoundReadTest.Clip.MP3.path();
        long sourceFrames;
        try (var media = MediaSource.of(source); var reader = AudioReader.open(media)) {
            var count = 0L;
            for (var block = reader.read(); block != null; block = reader.read()) {
                count += block.frames();
            }
            sourceFrames = count;
        }
        var file = directory.resolve("transcoded.opus");
        try (var encoded = AudioSystem.getAudioInputStream(source.toFile())) {
            AudioSystem.write(encoded, SmaltAudioFormats.OGG_OPUS, file.toFile());
        }
        assertEquals(Math.round(sourceFrames * 48_000.0 / 44_100), decode(file)[0].length);
        try (var reread = AudioSystem.getAudioInputStream(file.toFile())) {
            assertEquals("OPUS", reread.getFormat().getEncoding().toString());
        }
    }

    @Test
    @DisplayName("an unsupported type is rejected")
    void unsupportedType() {
        var writer = new SmaltAudioFileWriter();
        assertThrows(IllegalArgumentException.class,
                () -> writer.write(sine(48_000, 1, 10), AudioFileFormat.Type.WAVE, new ByteArrayOutputStream()));
        assertArrayEquals(new AudioFileFormat.Type[0], writer.getAudioFileTypes(
                new AudioInputStream(new ByteArrayInputStream(new byte[0]),
                        new AudioFormat(new AudioFormat.Encoding("NOT_AUDIO"), 8000, 8, 1, 1, 8000, false), 0)));
    }
}

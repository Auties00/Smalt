package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the compressed file types {@link SmaltAudioFileWriter} writes and the PCM-to-compressed conversions of
 * {@link SmaltFormatConversionProvider}: every file is read back through {@link SmaltAudioFileReader} and decoded
 * by FFmpeg, FLAC exactly and the lossy codecs within a waveform SNR after aligning the encoder's delay.
 */
@DisplayName("Java Sound encoding")
class JavaSoundEncodeTest {
    enum Kind {
        OPUS(SmaltAudioFormats.OGG_OPUS, ContainerFormat.OGG, Codec.OPUS, 48_000, 2, 15),
        VORBIS(SmaltAudioFormats.OGG, ContainerFormat.OGG, Codec.VORBIS, 44_100, 2, 15),
        FLAC(SmaltAudioFormats.FLAC, ContainerFormat.FLAC, Codec.FLAC, 44_100, 2, Double.POSITIVE_INFINITY),
        MP3(SmaltAudioFormats.MP3, ContainerFormat.MPEG_AUDIO, Codec.MP3, 44_100, 2, 15),
        M4A(SmaltAudioFormats.MPEG4, ContainerFormat.MP4, Codec.AAC, 48_000, 2, 15),
        ADTS(SmaltAudioFormats.AAC, ContainerFormat.ADTS, Codec.AAC, 48_000, 2, 15),
        AMR_WB(SmaltAudioFormats.AMR, ContainerFormat.AMR, Codec.AMR_WB, 16_000, 1, 5),
        AMR_NB(SmaltAudioFormats.AMR, ContainerFormat.AMR, Codec.AMR_NB, 8_000, 1, 5),
        AC3(SmaltAudioFormats.AC3, ContainerFormat.AC3, Codec.AC3, 48_000, 2, 15),
        EAC3(SmaltAudioFormats.EAC3, ContainerFormat.AC3, Codec.EAC3, 48_000, 2, 15);

        final AudioFileFormat.Type type;
        final ContainerFormat container;
        final Codec codec;
        final int rate;
        final int channels;
        final double minimumSnr;

        Kind(AudioFileFormat.Type type, ContainerFormat container, Codec codec, int rate, int channels,
             double minimumSnr) {
            this.type = type;
            this.container = container;
            this.codec = codec;
            this.rate = rate;
            this.channels = channels;
            this.minimumSnr = minimumSnr;
        }
    }

    /** A tone of 440 Hz (plus 110 Hz per channel) at 16 bits, with a little noise so FLAC has work to do. */
    static byte[] tone(int rate, int channels, int frames, int bits) {
        var container = (bits + 7) / 8;
        var format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, bits, channels, channels * container, rate,
                false);
        var random = new Random(rate + channels);
        var planes = new float[channels][frames];
        for (var c = 0; c < channels; c++) {
            for (var i = 0; i < frames; i++) {
                var value = 0.4 * Math.sin(2 * Math.PI * (440 + 110 * c) * i / rate) + 0.001 * random.nextGaussian();
                planes[c][i] = (float) (Math.round(value * (1 << (bits - 1))) / (double) (1 << (bits - 1)));
            }
        }
        var bytes = new byte[frames * channels * container];
        SampledAudio.write(planes, 0, frames, format, bytes, 0);
        return bytes;
    }

    static AudioInputStream stream(byte[] bytes, int rate, int channels, int bits) {
        var container = (bits + 7) / 8;
        var format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, bits, channels, channels * container, rate,
                false);
        return new AudioInputStream(new ByteArrayInputStream(bytes), format, bytes.length / format.getFrameSize());
    }

    static float[][] planes(byte[] bytes, AudioFormat format) {
        var frames = bytes.length / format.getFrameSize();
        var planes = new float[format.getChannels()][frames];
        SampledAudio.read(bytes, 0, frames, format, planes, 0);
        return planes;
    }

    static float[][] decode(Path file, Kind kind) {
        try (var source = MediaSource.of(file); var reader = AudioReader.open(source)) {
            assertEquals(kind.container, reader.format(), "container");
            assertEquals(kind.codec, reader.track().codec(), "codec");
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

    /** The best SNR in dB of {@code decoded} against {@code reference} over delays of the decoded signal. */
    static double alignedSnr(float[] reference, float[] decoded, int maxDelay) {
        var best = Double.NEGATIVE_INFINITY;
        for (var delay = 0; delay <= maxDelay; delay++) {
            double signal = 0;
            double noise = 0;
            var end = Math.min(reference.length, decoded.length - delay) - 2_000;
            for (var i = 2_000; i < end; i++) {
                signal += (double) reference[i] * reference[i];
                var error = decoded[i + delay] - reference[i];
                noise += (double) error * error;
            }
            if (end > 4_000) {
                best = Math.max(best, noise == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signal / noise));
            }
        }
        return best;
    }

    @Nested
    @DisplayName("file types")
    class FileTypes {
        @Test
        @DisplayName("every type whose encoder is present is offered")
        void offered() {
            var types = Arrays.asList(AudioSystem.getAudioFileTypes());
            for (var type : List.of(SmaltAudioFormats.OGG_OPUS, SmaltAudioFormats.OGG, SmaltAudioFormats.FLAC,
                    SmaltAudioFormats.MP3, SmaltAudioFormats.MPEG4, SmaltAudioFormats.AAC, SmaltAudioFormats.AMR,
                    SmaltAudioFormats.AC3, SmaltAudioFormats.EAC3)) {
                assertTrue(types.contains(type), type.toString());
                assertTrue(AudioSystem.isFileTypeSupported(type, stream(new byte[4], 44_100, 2, 16)), type.toString());
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("a written file reads back through Java Sound and decodes in FFmpeg")
        void roundTrip(Kind kind, @TempDir Path directory) throws Exception {
            var frames = kind.rate * 2;
            var pcm = tone(kind.rate, kind.channels, frames, 16);
            var file = directory.resolve("out." + kind.type.getExtension());
            var written = AudioSystem.write(stream(pcm, kind.rate, kind.channels, 16), kind.type, file.toFile());
            assertEquals(Files.size(file), written);
            var decoded = decode(file, kind);
            var input = planes(pcm, stream(pcm, kind.rate, kind.channels, 16).getFormat());
            if (kind.codec == Codec.FLAC) {
                for (var c = 0; c < kind.channels; c++) {
                    assertArrayEquals(input[c], decoded[c], "channel " + c);
                }
            } else {
                for (var c = 0; c < kind.channels; c++) {
                    var snr = alignedSnr(input[c], decoded[c], 4096);
                    assertTrue(snr > kind.minimumSnr, kind + " channel " + c + " SNR " + snr);
                }
            }
            try (var reread = AudioSystem.getAudioInputStream(file.toFile())) {
                assertEquals(kind.codec.name(), reread.getFormat().getEncoding().toString());
                assertEquals(kind.channels, reread.getFormat().getChannels());
                assertEquals(kind.rate, Math.round(reread.getFormat().getSampleRate()));
            }
            Oracle.assume(Oracle.Tool.FFMPEG);
            Oracle.assertFfmpegDecodes(file);
        }

        @ParameterizedTest(name = "{0} bits")
        @CsvSource({"8", "12", "16", "20", "24", "32"})
        @DisplayName("FLAC keeps integer input of every size exactly")
        void flacSizes(int bits, @TempDir Path directory) throws Exception {
            var pcm = tone(44_100, 2, 20_000, bits);
            var file = directory.resolve("out.flac");
            AudioSystem.write(stream(pcm, 44_100, 2, bits), SmaltAudioFormats.FLAC, file.toFile());
            try (var source = MediaSource.of(file); var reader = AudioReader.open(source)) {
                assertEquals(bits, reader.track().bitsPerSample());
            }
            try (var encoded = AudioSystem.getAudioInputStream(file.toFile())) {
                var format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44_100, bits, 2,
                        2 * ((bits + 7) / 8), 44_100, false);
                var decoded = AudioSystem.getAudioInputStream(format, encoded).readAllBytes();
                assertArrayEquals(pcm, decoded);
            }
        }

        @Test
        @DisplayName("AMR files code mono at 16 kHz or 8 kHz, from any input")
        void amrRates(@TempDir Path directory) throws Exception {
            var wide = directory.resolve("wide.amr");
            AudioSystem.write(stream(tone(44_100, 2, 44_100, 16), 44_100, 2, 16), SmaltAudioFormats.AMR, wide.toFile());
            try (var source = MediaSource.of(wide); var reader = AudioReader.open(source)) {
                assertEquals(Codec.AMR_WB, reader.track().codec());
                assertEquals(16_000, reader.sampleRate());
                assertEquals(1, reader.layout().channels());
            }
            var narrow = directory.resolve("narrow.amr");
            AudioSystem.write(stream(tone(11_025, 1, 11_025, 16), 11_025, 1, 16), SmaltAudioFormats.AMR,
                    narrow.toFile());
            try (var source = MediaSource.of(narrow); var reader = AudioReader.open(source)) {
                assertEquals(Codec.AMR_NB, reader.track().codec());
                assertEquals(8_000, reader.sampleRate());
            }
        }

        @Test
        @DisplayName("G.711, half-float and unsigned input is encoded")
        void otherInputs(@TempDir Path directory) throws Exception {
            var pcm = tone(8_000, 1, 8_000, 16);
            var floats = planes(pcm, stream(pcm, 8_000, 1, 16).getFormat());
            for (var encoding : List.of(AudioFormat.Encoding.ULAW, AudioFormat.Encoding.ALAW,
                    AudioFormat.Encoding.PCM_FLOAT, AudioFormat.Encoding.PCM_UNSIGNED)) {
                var bits = AudioFormat.Encoding.PCM_FLOAT.equals(encoding) ? 16 : 8;
                var format = SampledAudio.format(encoding, 8_000, bits, ChannelLayout.MONO, false);
                var bytes = new byte[8_000 * format.getFrameSize()];
                SampledAudio.write(floats, 0, 8_000, format, bytes, 0);
                var expected = planes(bytes, format);
                var file = directory.resolve(encoding + ".flac");
                AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(bytes), format, 8_000),
                        SmaltAudioFormats.FLAC, file.toFile());
                var decoded = decode(file, Kind.FLAC);
                assertEquals(expected[0].length, decoded[0].length, encoding.toString());
                // float input is coded at 24 bits, which rounds the smallest half-precision values
                var bound = AudioFormat.Encoding.PCM_FLOAT.equals(encoding) ? 0x1p-24f : 0f;
                for (var i = 0; i < expected[0].length; i++) {
                    assertTrue(Math.abs(expected[0][i] - decoded[0][i]) <= bound, encoding + " sample " + i);
                }
            }
        }

        @Test
        @DisplayName("writing to a stream gives the bytes count of the stream, and a decodable file")
        void outputStream(@TempDir Path directory) throws Exception {
            for (var kind : List.of(Kind.FLAC, Kind.MP3, Kind.M4A, Kind.AC3)) {
                var bytes = new ByteArrayOutputStream();
                var written = AudioSystem.write(stream(tone(kind.rate, 2, kind.rate, 16), kind.rate, 2, 16),
                        kind.type, bytes);
                assertEquals(bytes.size(), written);
                var file = directory.resolve("stream." + kind.type.getExtension());
                Files.write(file, bytes.toByteArray());
                assertTrue(decode(file, kind)[0].length >= kind.rate - 2048, kind.toString());
            }
        }
    }

    @Nested
    @DisplayName("conversion of PCM to compressed encodings")
    class Conversion {
        @Test
        @DisplayName("the provider offers the encodable encodings for PCM sources and none for others")
        void encodings() {
            var source = new AudioFormat(44_100, 16, 2, true, false);
            var targets = Arrays.asList(AudioSystem.getTargetEncodings(source));
            for (var kind : Kind.values()) {
                assertTrue(targets.contains(SmaltAudioFormats.encodingOf(kind.codec)), kind.toString());
                assertTrue(AudioSystem.isConversionSupported(SmaltAudioFormats.encodingOf(kind.codec), source));
            }
            assertTrue(SmaltAudioFormats.encodableEncodings().contains(SmaltAudioFormats.encodingOf(Codec.FLAC)));
            assertFalse(SmaltAudioFormats.encodableEncodings().contains(SmaltAudioFormats.encodingOf(Codec.ALAC)));
            var provider = new SmaltFormatConversionProvider();
            assertFalse(provider.isConversionSupported(SmaltAudioFormats.encodingOf(Codec.FLAC),
                    new AudioFormat(44_100.5f, 16, 2, true, false)));
            assertEquals(0, provider.getTargetEncodings(new AudioFormat(new AudioFormat.Encoding("NOPE"), 8000, 8, 1,
                    1, 8000, false)).length);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Kind.class, mode = EnumSource.Mode.EXCLUDE, names = "M4A")
        @DisplayName("an encoded stream holds a file of the codec that decodes back through Java Sound")
        void encodeAndDecode(Kind kind, @TempDir Path directory) throws Exception {
            var pcm = tone(kind.rate, kind.channels, kind.rate, 16);
            var encoding = SmaltAudioFormats.encodingOf(kind.codec);
            byte[] file;
            try (var encoded = AudioSystem.getAudioInputStream(encoding, stream(pcm, kind.rate, kind.channels, 16))) {
                assertEquals(encoding, encoded.getFormat().getEncoding());
                assertEquals(kind.rate, Math.round(encoded.getFormat().getSampleRate()));
                assertEquals(kind.channels, encoded.getFormat().getChannels());
                assertEquals(AudioSystem.NOT_SPECIFIED, encoded.getFrameLength());
                file = encoded.readAllBytes();
            }
            var path = directory.resolve("stream." + kind.type.getExtension());
            Files.write(path, file);
            var decoded = decode(path, kind);
            var input = planes(pcm, stream(pcm, kind.rate, kind.channels, 16).getFormat());
            assertEquals(kind.channels, decoded.length);
            for (var c = 0; c < kind.channels; c++) {
                if (kind.codec == Codec.FLAC) {
                    assertArrayEquals(input[c], decoded[c], "channel " + c);
                } else {
                    var snr = alignedSnr(input[c], decoded[c], 4096);
                    assertTrue(snr > kind.minimumSnr, kind + " channel " + c + " SNR " + snr);
                }
            }
            var again = new AudioInputStream(new ByteArrayInputStream(file), new AudioFormat(encoding, kind.rate,
                    AudioSystem.NOT_SPECIFIED, kind.channels, AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED,
                    false), AudioSystem.NOT_SPECIFIED);
            var pcmAgain = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, again).readAllBytes();
            assertEquals(decoded[0].length * kind.channels * 2L, pcmAgain.length);
        }

        @ParameterizedTest(name = "{0}")
        // an Ogg stream draws a random serial number, so two encodings of it differ; the elementary streams do not
        @EnumSource(value = Kind.class, names = {"FLAC", "MP3", "ADTS", "AMR_WB", "AMR_NB", "AC3", "EAC3"})
        @DisplayName("the writer copies an encoded stream of its type unchanged")
        void copied(Kind kind) throws Exception {
            assertCopied(kind.type, SmaltAudioFormats.encodingOf(kind.codec), kind.rate);
        }

        @Test
        @DisplayName("an AMR stream of either codec is copied into an AMR file whatever the input rate")
        void copiedAmrAcrossRates() throws Exception {
            // 8 kHz input defaults to AMR-NB files, 16 kHz input to AMR-WB files
            assertCopied(SmaltAudioFormats.AMR, SmaltAudioFormats.encodingOf(Codec.AMR_WB), 8_000);
            assertCopied(SmaltAudioFormats.AMR, SmaltAudioFormats.encodingOf(Codec.AMR_NB), 16_000);
        }

        private static void assertCopied(AudioFileFormat.Type type, AudioFormat.Encoding encoding, int rate)
                throws Exception {
            var pcm = tone(rate, 2, rate / 2, 16);
            byte[] direct;
            try (var encoded = AudioSystem.getAudioInputStream(encoding, stream(pcm, rate, 2, 16))) {
                direct = encoded.readAllBytes();
            }
            var written = new ByteArrayOutputStream();
            try (var encoded = AudioSystem.getAudioInputStream(encoding, stream(pcm, rate, 2, 16))) {
                AudioSystem.write(encoded, type, written);
            }
            assertArrayEquals(direct, written.toByteArray(), type + " from " + encoding + " at " + rate);
        }

        @Test
        @DisplayName("a full target format codes its rate and channel count, and an impossible one is refused")
        void fullFormat() throws Exception {
            var encoding = SmaltAudioFormats.encodingOf(Codec.OPUS);
            var source = stream(tone(44_100, 2, 44_100, 16), 44_100, 2, 16);
            var target = new AudioFormat(encoding, 24_000, AudioSystem.NOT_SPECIFIED, 1, AudioSystem.NOT_SPECIFIED,
                    AudioSystem.NOT_SPECIFIED, false);
            assertTrue(AudioSystem.isConversionSupported(target, source.getFormat()));
            try (var encoded = AudioSystem.getAudioInputStream(target, source)) {
                assertEquals(24_000, Math.round(encoded.getFormat().getSampleRate()));
                assertEquals(1, encoded.getFormat().getChannels());
                assertTrue(encoded.readAllBytes().length > 0);
            }
            var impossible = new AudioFormat(encoding, 44_100, AudioSystem.NOT_SPECIFIED, 1, AudioSystem.NOT_SPECIFIED,
                    AudioSystem.NOT_SPECIFIED, false);
            assertFalse(new SmaltFormatConversionProvider().isConversionSupported(impossible, source.getFormat()));
            var formats = new SmaltFormatConversionProvider().getTargetFormats(encoding, source.getFormat());
            assertEquals(1, formats.length);
            assertEquals(48_000, Math.round(formats[0].getSampleRate()));
            assertThrows(IllegalArgumentException.class,
                    () -> new SmaltFormatConversionProvider().getAudioInputStream(impossible,
                            stream(tone(44_100, 2, 100, 16), 44_100, 2, 16)));
        }

        @Test
        @DisplayName("closing the encoded stream closes the source, and reading it afterwards fails")
        void closing() throws Exception {
            var closed = new boolean[1];
            var bytes = tone(8_000, 1, 8_000, 16);
            var input = new ByteArrayInputStream(bytes) {
                @Override
                public void close() {
                    closed[0] = true;
                }
            };
            var source = new AudioInputStream(input, new AudioFormat(8_000, 16, 1, true, false), 8_000);
            var encoded = AudioSystem.getAudioInputStream(SmaltAudioFormats.encodingOf(Codec.FLAC), source);
            assertTrue(encoded.read(new byte[16]) > 0);
            encoded.close();
            assertTrue(closed[0]);
            assertThrows(java.io.IOException.class, () -> encoded.read(new byte[16]));
        }
    }
}

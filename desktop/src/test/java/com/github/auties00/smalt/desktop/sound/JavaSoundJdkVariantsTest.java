package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.spi.AudioFileReader;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that {@link SmaltAudioFileReader} reads exactly the WAVE, AIFF and AU variants the JDK's own readers
 * decline: for every sample, made by FFmpeg or built here byte by byte, either the JDK's providers (found
 * through {@link ServiceLoader}, Smalt's excluded) or Smalt's reader accept it, never both and never neither.
 */
@DisplayName("Java Sound WAVE, AIFF and AU variants")
class JavaSoundJdkVariantsTest {
    private static final int RATE = 16_000;

    private static List<AudioFileReader> jdkReaders() {
        var readers = new ArrayList<AudioFileReader>();
        for (var reader : ServiceLoader.load(AudioFileReader.class)) {
            if (!(reader instanceof SmaltAudioFileReader)) {
                readers.add(reader);
            }
        }
        return readers;
    }

    private static boolean jdkReads(Path file) {
        for (var reader : jdkReaders()) {
            try {
                reader.getAudioFileFormat(file.toFile());
                try (var stream = reader.getAudioInputStream(file.toFile())) {
                    stream.readAllBytes();
                }
                return true;
            } catch (UnsupportedAudioFileException | IOException | RuntimeException exception) {
                // the next provider may read it
            }
        }
        return false;
    }

    private static boolean smaltReads(Path file) throws IOException {
        try {
            new SmaltAudioFileReader().getAudioFileFormat(file.toFile());
            return true;
        } catch (UnsupportedAudioFileException exception) {
            return false;
        }
    }

    private static Path tone(String extension, String... options) {
        return Fixtures.tone(extension, 440, RATE, 1, 0.25, List.of(options));
    }

    static Stream<Arguments> samples() {
        return Stream.of(
                Arguments.of("WAV u8", tone("wav", "-c:a", "pcm_u8")),
                Arguments.of("WAV s16", tone("wav", "-c:a", "pcm_s16le")),
                Arguments.of("WAV s24", tone("wav", "-c:a", "pcm_s24le")),
                Arguments.of("WAV s32", tone("wav", "-c:a", "pcm_s32le")),
                Arguments.of("WAV f32", tone("wav", "-c:a", "pcm_f32le")),
                Arguments.of("WAV f64", tone("wav", "-c:a", "pcm_f64le")),
                Arguments.of("WAV A-law", tone("wav", "-c:a", "pcm_alaw")),
                Arguments.of("WAV mu-law", tone("wav", "-c:a", "pcm_mulaw")),
                Arguments.of("WAV extensible 3 channels", Fixtures.tone("wav", 440, RATE, 3, 0.25,
                        List.of("-c:a", "pcm_s24le"))),
                Arguments.of("WAV extensible float 3 channels", Fixtures.tone("wav", 440, RATE, 3, 0.25,
                        List.of("-c:a", "pcm_f32le"))),
                Arguments.of("WAV IMA ADPCM", tone("wav", "-c:a", "adpcm_ima_wav")),
                Arguments.of("WAV MS ADPCM", tone("wav", "-c:a", "adpcm_ms")),
                Arguments.of("WAV MP3", Fixtures.tone("wav", 440, 44_100, 1, 0.25, List.of("-c:a", "libmp3lame"))),
                Arguments.of("RF64", tone("wav", "-c:a", "pcm_s16le", "-rf64", "always")),
                Arguments.of("AIFF s8", tone("aiff", "-c:a", "pcm_s8")),
                Arguments.of("AIFF s16", tone("aiff", "-c:a", "pcm_s16be")),
                Arguments.of("AIFF s24", tone("aiff", "-c:a", "pcm_s24be")),
                Arguments.of("AIFF-C sowt", tone("aifc", "-c:a", "pcm_s16le")),
                Arguments.of("AIFF-C fl32", tone("aifc", "-c:a", "pcm_f32be")),
                Arguments.of("AIFF-C fl64", tone("aifc", "-c:a", "pcm_f64be")),
                Arguments.of("AIFF-C alaw", tone("aifc", "-c:a", "pcm_alaw")),
                Arguments.of("AIFF-C ulaw", tone("aifc", "-c:a", "pcm_mulaw")),
                Arguments.of("AIFF-C ima4", tone("aifc", "-c:a", "adpcm_ima_qt")),
                Arguments.of("AU s16", tone("au", "-c:a", "pcm_s16be")),
                Arguments.of("AU f32", tone("au", "-c:a", "pcm_f32be")),
                Arguments.of("AU f64", tone("au", "-c:a", "pcm_f64be")),
                Arguments.of("AU s8", tone("au", "-c:a", "pcm_s8")),
                Arguments.of("AU A-law", tone("au", "-c:a", "pcm_alaw")),
                Arguments.of("AU mu-law", tone("au", "-c:a", "pcm_mulaw")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("exactly one of the JDK's readers and Smalt's reader accepts an FFmpeg sample")
    void complementary(String name, Path file) throws IOException {
        assertComplementary(name, file);
    }

    @Test
    @DisplayName("a WAVE file whose format chunk lies past the recognized header is still left to the JDK")
    void formatChunkPastHeader(@TempDir Path directory) throws IOException {
        // an 8 KiB JUNK chunk pushes the fmt chunk past the 4096 bytes a stream is recognized from
        var plain = wave(16, 2, new long[]{1, -2, 3, -4});
        var body = new ByteArrayOutputStream();
        body.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
        chunk(body, "JUNK", new byte[8_192], true);
        body.write(plain, 12, plain.length - 12);
        var file = new ByteArrayOutputStream();
        chunk(file, "RIFF", body.toByteArray(), true);
        assertComplementary("WAV with JUNK", Files.write(directory.resolve("junk.wav"), file.toByteArray()));
    }

    private static void assertComplementary(String name, Path file) throws IOException {
        var jdk = jdkReads(file);
        assertNotEquals(jdk, smaltReads(file), name + (jdk ? " is read by both" : " is read by neither"));
        try (var stream = new BufferedInputStream(Files.newInputStream(file))) {
            var smalt = new SmaltAudioFileReader();
            if (jdk) {
                assertThrows(UnsupportedAudioFileException.class, () -> smalt.getAudioInputStream(stream));
                assertEquals(Files.size(file), stream.readAllBytes().length, "the declined stream is reset");
            }
        }
    }

    /** Builds an AIFF-C file of 16-bit big-endian samples under a compression type. */
    static byte[] aifc(String compression, short[] samples) {
        var data = ByteBuffer.allocate(samples.length * 2);
        for (var sample : samples) {
            data.putShort(sample);
        }
        var comm = ByteBuffer.allocate(24).putShort((short) 1).putInt(samples.length).putShort((short) 16);
        // 16000 as an 80-bit IEEE 754 extended value
        comm.putShort((short) (16383 + 13)).putLong(16_000L << (63 - 13));
        comm.put(compression.getBytes(StandardCharsets.US_ASCII)).putShort((short) 0);
        var body = new ByteArrayOutputStream();
        body.writeBytes("AIFC".getBytes(StandardCharsets.US_ASCII));
        chunk(body, "FVER", ByteBuffer.allocate(4).putInt(0xA2805140).array(), false);
        chunk(body, "COMM", comm.array(), false);
        var ssnd = ByteBuffer.allocate(8 + data.capacity()).putInt(0).putInt(0).put(data.array());
        chunk(body, "SSND", ssnd.array(), false);
        var file = new ByteArrayOutputStream();
        chunk(file, "FORM", body.toByteArray(), false);
        return file.toByteArray();
    }

    /** Builds a WAVE file of samples of {@code bits} bits in {@code container} bytes, MSB-justified. */
    static byte[] wave(int bits, int container, long[] samples) {
        var format = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * container).putShort((short) container).putShort((short) bits);
        var data = ByteBuffer.allocate(samples.length * container).order(ByteOrder.LITTLE_ENDIAN);
        for (var sample : samples) {
            var raw = sample << (container * 8 - bits);
            for (var i = 0; i < container; i++) {
                data.put((byte) (raw >>> (8 * i)));
            }
        }
        var body = new ByteArrayOutputStream();
        body.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
        chunk(body, "fmt ", format.array(), true);
        chunk(body, "data", data.array(), true);
        var file = new ByteArrayOutputStream();
        chunk(file, "RIFF", body.toByteArray(), true);
        return file.toByteArray();
    }

    static void chunk(ByteArrayOutputStream out, String id, byte[] payload, boolean littleEndian) {
        out.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(ByteBuffer.allocate(4).order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN)
                .putInt(payload.length).array());
        out.writeBytes(payload);
        if ((payload.length & 1) != 0) {
            out.write(0);
        }
    }

    static short[] ramp() {
        var samples = new short[2_000];
        for (var i = 0; i < samples.length; i++) {
            samples[i] = (short) (i * 31 - 30_000);
        }
        return samples;
    }

    @Test
    @DisplayName("AIFF-C NONE is the JDK's; twos and raw are Smalt's, and read the same samples")
    void aifcCompressionTypes(@TempDir Path directory) throws Exception {
        var samples = ramp();
        var none = Files.write(directory.resolve("none.aifc"), aifc("NONE", samples));
        assertTrue(jdkReads(none));
        assertTrue(!smaltReads(none));
        var twos = Files.write(directory.resolve("twos.aifc"), aifc("twos", samples));
        assertTrue(!jdkReads(twos));
        assertTrue(smaltReads(twos));
        assertEquals(AudioFileFormat.Type.AIFC, AudioSystem.getAudioFileFormat(twos.toFile()).getType());
        byte[] expected;
        try (var stream = AudioSystem.getAudioInputStream(none.toFile())) {
            expected = AudioSystem.getAudioInputStream(new AudioFormat(RATE, 16, 1, true, false), stream).readAllBytes();
        }
        try (var stream = AudioSystem.getAudioInputStream(twos.toFile())) {
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, stream.getFormat().getEncoding());
            var actual = AudioSystem.getAudioInputStream(new AudioFormat(RATE, 16, 1, true, false), stream)
                    .readAllBytes();
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    @DisplayName("12 and 20-bit WAVE files stay the JDK's, which reads the sizes SampledAudio supports")
    void oddSizedWave(@TempDir Path directory) throws Exception {
        for (var bits : new int[]{12, 20}) {
            var container = (bits + 7) / 8;
            var samples = new long[1_000];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (long) (Math.sin(i * 0.05) * ((1 << (bits - 1)) - 1));
            }
            var file = Files.write(directory.resolve(bits + ".wav"), wave(bits, container, samples));
            assertTrue(jdkReads(file));
            assertTrue(!smaltReads(file));
            try (var stream = AudioSystem.getAudioInputStream(file.toFile())) {
                assertEquals(bits, stream.getFormat().getSampleSizeInBits());
                assertTrue(SampledAudio.isSupported(stream.getFormat()));
                var bytes = stream.readAllBytes();
                var planes = new float[1][samples.length];
                SampledAudio.read(bytes, 0, samples.length, stream.getFormat(), planes, 0);
                for (var i = 0; i < samples.length; i++) {
                    assertEquals(samples[i] * Math.scalb(1.0, 1 - bits), planes[0][i], 0.0, bits + "-bit sample " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("an A-law AIFF-C file reads as ALAW codes equal to the file's")
    void alawCodes() throws Exception {
        var file = tone("aifc", "-c:a", "pcm_alaw");
        var bytes = Files.readAllBytes(file);
        try (var stream = AudioSystem.getAudioInputStream(file.toFile())) {
            assertEquals(AudioFormat.Encoding.ALAW, stream.getFormat().getEncoding());
            var codes = stream.readAllBytes();
            var data = indexOf(bytes, "SSND".getBytes(StandardCharsets.US_ASCII)) + 16;
            assertArrayEquals(java.util.Arrays.copyOfRange(bytes, data, data + codes.length), codes);
        }
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (var i = 0; i + pattern.length <= data.length; i++) {
            for (var j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("pattern not found");
    }

    @Test
    @DisplayName("a WAVE header too short or cut inside its format chunk is not taken for a JDK file")
    void truncatedHeaders() throws Exception {
        var full = wave(16, 2, new long[]{1, 2, 3});
        for (var length = 0; length < 44; length++) {
            var cut = java.util.Arrays.copyOf(full, length);
            var stream = new BufferedInputStream(new ByteArrayInputStream(cut));
            assertThrows(UnsupportedAudioFileException.class, () -> new SmaltAudioFileReader().getAudioInputStream(stream));
        }
    }
}

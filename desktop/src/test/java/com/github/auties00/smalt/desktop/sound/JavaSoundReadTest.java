package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.tags.Id3v2Tag;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Java Sound reading")
class JavaSoundReadTest {
    // One fixture per format the reader handles, encoded by FFmpeg (a black-box oracle only).
    enum Clip {
        OPUS("opus", 48_000, List.of("-c:a", "libopus", "-b:a", "96k"), SmaltAudioFormats.OGG_OPUS, "OPUS"),
        VORBIS("ogg", 44_100, List.of("-c:a", "libvorbis"), SmaltAudioFormats.OGG, "VORBIS"),
        OGG_FLAC("oga", 44_100, List.of("-c:a", "flac"), SmaltAudioFormats.OGG, "FLAC"),
        FLAC("flac", 44_100, List.of("-c:a", "flac"), SmaltAudioFormats.FLAC, "FLAC"),
        MP3("mp3", 44_100, List.of("-c:a", "libmp3lame", "-b:a", "128k"), SmaltAudioFormats.MP3, "MP3"),
        M4A("m4a", 48_000, List.of("-c:a", "aac", "-b:a", "128k"), SmaltAudioFormats.MPEG4, "AAC"),
        ADTS("aac", 48_000, List.of("-c:a", "aac", "-b:a", "128k"), SmaltAudioFormats.AAC, "AAC"),
        MATROSKA("mka", 48_000, List.of("-c:a", "libopus"), SmaltAudioFormats.MATROSKA, "OPUS"),
        AC3("ac3", 48_000, List.of("-c:a", "ac3", "-b:a", "192k"), SmaltAudioFormats.AC3, "AC3"),
        EAC3("eac3", 48_000, List.of("-c:a", "eac3", "-b:a", "192k"), SmaltAudioFormats.EAC3, "EAC3"),
        DTS("dts", 48_000, List.of("-c:a", "dca", "-strict", "-2"), SmaltAudioFormats.DTS, "DTS"),
        LOAS("latm", 48_000, List.of("-c:a", "aac", "-b:a", "128k"), SmaltAudioFormats.LOAS, "AAC"),
        CAF_ALAC("caf", 44_100, List.of("-c:a", "alac"), SmaltAudioFormats.CAF, "ALAC"),
        WAVPACK("wv", 44_100, List.of("-c:a", "wavpack"), SmaltAudioFormats.WAVPACK, "WAVPACK"),
        W64("w64", 44_100, List.of("-c:a", "pcm_s16le"), SmaltAudioFormats.W64, "PCM_SIGNED"),
        RF64("wav", 44_100, List.of("-c:a", "pcm_s16le", "-rf64", "always"), AudioFileFormat.Type.WAVE, "PCM_SIGNED"),
        WAV_ADPCM("wav", 44_100, List.of("-c:a", "adpcm_ms"), AudioFileFormat.Type.WAVE, "ADPCM_MS"),
        AIFC_SOWT("aifc", 44_100, List.of("-c:a", "pcm_s16le"), AudioFileFormat.Type.AIFC, "PCM_SIGNED"),
        AIFC_FLOAT("aifc", 44_100, List.of("-c:a", "pcm_f32be"), AudioFileFormat.Type.AIFC, "PCM_FLOAT"),
        AIFC_ALAW("aifc", 8_000, List.of("-c:a", "pcm_alaw"), AudioFileFormat.Type.AIFC, "ALAW"),
        AVI("avi", 48_000, List.of("-c:v", "mjpeg", "-c:a", "libmp3lame", "-ac", "2"), SmaltAudioFormats.AVI, "MP3",
                true),
        MPEG_TS("ts", 48_000, List.of("-c:v", "mpeg2video", "-c:a", "aac", "-ac", "2"), SmaltAudioFormats.MPEG_TS,
                "AAC", true),
        MPEG_PS("mpg", 48_000, List.of("-c:v", "mpeg1video", "-c:a", "mp2", "-ac", "2"), SmaltAudioFormats.MPEG_PS,
                "MP2", true);

        final String extension;
        final int sampleRate;
        final List<String> options;
        final AudioFileFormat.Type type;
        final String encoding;
        final boolean video;

        Clip(String extension, int sampleRate, List<String> options, AudioFileFormat.Type type, String encoding) {
            this(extension, sampleRate, options, type, encoding, false);
        }

        Clip(String extension, int sampleRate, List<String> options, AudioFileFormat.Type type, String encoding,
             boolean video) {
            this.extension = extension;
            this.sampleRate = sampleRate;
            this.options = options;
            this.type = type;
            this.encoding = encoding;
            this.video = video;
        }

        Path path() {
            if (video) {
                return Fixtures.testVideo(extension, 160, 120, 1.5, options);
            }
            return Fixtures.tone(extension, 440, sampleRate, 2, 1.5, options);
        }
    }

    static float[] reference(Path path, AudioReaderOptions options) {
        var samples = new float[0];
        var count = 0;
        try (var source = MediaSource.of(path); var reader = AudioReader.open(source, options)) {
            for (var block = reader.read(); block != null; block = reader.read()) {
                var channels = block.channels();
                if (samples.length < (count + block.frames()) * channels) {
                    samples = Arrays.copyOf(samples, Math.max(samples.length * 2, (count + block.frames()) * channels));
                }
                for (var i = 0; i < block.frames(); i++) {
                    for (var c = 0; c < channels; c++) {
                        samples[(count + i) * channels + c] = block.plane(c)[i];
                    }
                }
                count += block.frames();
            }
            return Arrays.copyOf(samples, count * reader.layout().channels());
        }
    }

    static float[] floats(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        var result = new float[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    static short[] shorts(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        var result = new short[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    static short[] quantize(float[] samples) {
        var result = new short[samples.length];
        for (var i = 0; i < samples.length; i++) {
            var value = Math.round((double) samples[i] * 32768);
            result[i] = (short) Math.clamp(value, -32768, 32767);
        }
        return result;
    }

    static AudioFormat float32(AudioFormat source) {
        return new AudioFormat(AudioFormat.Encoding.PCM_FLOAT, source.getSampleRate(), 32, source.getChannels(),
                source.getChannels() * 4, source.getSampleRate(), false);
    }

    static void assertSameFloats(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length, "sample count");
        for (var i = 0; i < expected.length; i++) {
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i])) {
                throw new AssertionError("sample " + i + ": expected " + expected[i] + " but was " + actual[i]);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Clip.class)
    @DisplayName("the file format names the container, the codec, the rate and the channels")
    void fileFormat(Clip clip) throws Exception {
        var format = AudioSystem.getAudioFileFormat(clip.path().toFile());
        assertEquals(clip.type, format.getType());
        assertEquals(clip.encoding, format.getFormat().getEncoding().toString());
        assertEquals(2, format.getFormat().getChannels());
        try (var source = MediaSource.of(clip.path()); var reader = AudioReader.open(source)) {
            assertEquals(reader.sampleRate(), Math.round(format.getFormat().getSampleRate()));
        }
        if (format.getProperty("duration") instanceof Long duration) {
            assertTrue(Math.abs(duration - 1_500_000) < 100_000, "duration " + duration);
        }
    }

    static boolean pcm(Clip clip) {
        return clip.encoding.startsWith("PCM_") || clip.encoding.equals("ALAW") || clip.encoding.equals("ULAW");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Clip.class)
    @DisplayName("a PCM track is returned in its own format, its bytes those of AudioReader's samples")
    void pcmTracks(Clip clip) throws Exception {
        if (!pcm(clip)) {
            return;
        }
        var expected = reference(clip.path(), AudioReaderOptions.defaults());
        try (var stream = AudioSystem.getAudioInputStream(clip.path().toFile())) {
            var format = stream.getFormat();
            assertEquals(clip.encoding, format.getEncoding().toString());
            var channels = format.getChannels();
            var frames = expected.length / channels;
            var planes = new float[channels][frames];
            for (var i = 0; i < frames; i++) {
                for (var c = 0; c < channels; c++) {
                    planes[c][i] = expected[i * channels + c];
                }
            }
            var bytes = new byte[frames * format.getFrameSize()];
            SampledAudio.write(planes, 0, frames, format, bytes, 0);
            assertArrayEquals(bytes, stream.readAllBytes());
        }
    }

    // The JDK converts a PCM track's own format, so these compare compressed tracks, which Smalt decodes
    @ParameterizedTest(name = "{0}")
    @EnumSource(Clip.class)
    @DisplayName("float PCM through Java Sound is bit-identical to AudioReader's output")
    void floatExact(Clip clip) throws Exception {
        if (pcm(clip)) {
            return;
        }
        var expected = reference(clip.path(), AudioReaderOptions.defaults());
        try (var encoded = AudioSystem.getAudioInputStream(clip.path().toFile());
             var pcm = AudioSystem.getAudioInputStream(float32(encoded.getFormat()), encoded)) {
            assertEquals(clip.encoding, encoded.getFormat().getEncoding().toString());
            assertSameFloats(expected, floats(pcm.readAllBytes()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Clip.class)
    @DisplayName("PCM_SIGNED decodes to 16-bit little-endian samples rounded from AudioReader's output")
    void signed16(Clip clip) throws Exception {
        if (pcm(clip)) {
            return;
        }
        var expected = quantize(reference(clip.path(), AudioReaderOptions.defaults()));
        try (var encoded = AudioSystem.getAudioInputStream(clip.path().toFile());
             var pcm = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, encoded)) {
            var format = pcm.getFormat();
            assertEquals(16, format.getSampleSizeInBits());
            assertEquals(false, format.isBigEndian());
            assertEquals(encoded.getFormat().getSampleRate(), format.getSampleRate());
            assertArrayEquals(expected, shorts(pcm.readAllBytes()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Clip.class, names = {"OPUS", "MP3", "M4A"})
    @DisplayName("streams and URLs decode like files")
    void streamsAndUrls(Clip clip) throws Exception {
        var expected = reference(clip.path(), AudioReaderOptions.defaults());
        var data = Files.readAllBytes(clip.path());
        try (var encoded = AudioSystem.getAudioInputStream(new BufferedInputStream(new ByteArrayInputStream(data)));
             var pcm = AudioSystem.getAudioInputStream(float32(encoded.getFormat()), encoded)) {
            assertSameFloats(expected, floats(pcm.readAllBytes()));
        }
        try (var encoded = AudioSystem.getAudioInputStream(clip.path().toUri().toURL());
             var pcm = AudioSystem.getAudioInputStream(float32(encoded.getFormat()), encoded)) {
            assertSameFloats(expected, floats(pcm.readAllBytes()));
        }
        var stream = new BufferedInputStream(new ByteArrayInputStream(data));
        assertEquals(clip.type, AudioSystem.getAudioFileFormat(stream).getType());
        assertEquals(data.length, stream.readAllBytes().length, "the stream is reset after describing it");
    }

    @Test
    @DisplayName("a full target format resamples and remixes as AudioReader does")
    void resampleAndRemix() throws Exception {
        var path = Clip.VORBIS.path();
        var expected = quantize(reference(path, AudioReaderOptions.of(16_000, ChannelLayout.MONO)));
        var target = new AudioFormat(16_000, 16, 1, true, false);
        try (var encoded = AudioSystem.getAudioInputStream(path.toFile());
             var pcm = AudioSystem.getAudioInputStream(target, encoded)) {
            assertTrue(pcm.getFormat().matches(target));
            assertArrayEquals(expected, shorts(pcm.readAllBytes()));
        }
    }

    @Test
    @DisplayName("the decoded stream opens on an output line")
    void playable() throws Exception {
        try (var encoded = AudioSystem.getAudioInputStream(Clip.MP3.path().toFile());
             var pcm = AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, encoded)) {
            var info = new DataLine.Info(SourceDataLine.class, pcm.getFormat());
            Skip.unlessDevice(AudioSystem.isLineSupported(info), "audio-output", "no audio output on this machine");
            try (var line = (SourceDataLine) AudioSystem.getLine(info)) {
                line.open(pcm.getFormat());
                assertEquals(pcm.getFormat(), line.getFormat());
            } catch (LineUnavailableException | IllegalArgumentException exception) {
                Skip.unlessDevice(false, "audio-output", "the audio output is unavailable: " + exception.getMessage());
            }
        }
    }

    @Test
    @DisplayName("WAV files are left to the JDK's readers")
    void wavDeclined(@TempDir Path directory) throws Exception {
        var wav = directory.resolve("tone.wav").toFile();
        var format = new AudioFormat(8_000, 16, 1, true, false);
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(new byte[1600]), format, 800),
                AudioFileFormat.Type.WAVE, wav);
        assertThrows(UnsupportedAudioFileException.class, () -> new SmaltAudioFileReader().getAudioFileFormat(wav));
        assertEquals(AudioFileFormat.Type.WAVE, AudioSystem.getAudioFileFormat(wav).getType());
    }

    @Test
    @DisplayName("bytes that are not audio are declined")
    void garbage() {
        var reader = new SmaltAudioFileReader();
        var data = new byte[8192];
        Arrays.fill(data, (byte) 0x5A);
        assertThrows(UnsupportedAudioFileException.class,
                () -> reader.getAudioInputStream(new BufferedInputStream(new ByteArrayInputStream(data))));
    }

    @Test
    @DisplayName("a leading ID3v2 tag is skipped, and one too large to skip within the mark is declined")
    void id3Prefix() throws Exception {
        var file = Files.readAllBytes(Clip.MP3.path());
        var mp3 = Arrays.copyOfRange(file, (int) Math.max(0, Id3v2Tag.size(file, 0)), file.length);
        var tagged = new byte[20 + 100 + mp3.length];
        System.arraycopy(id3v24Header(100, false), 0, tagged, 0, 10);
        System.arraycopy(mp3, 0, tagged, 20 + 100, mp3.length);
        System.arraycopy(id3v24Header(100, true), 0, tagged, 10 + 100, 10);
        tagged[5] = 0x10;
        tagged[10 + 100 + 5] = 0x10;
        var stream = new BufferedInputStream(new ByteArrayInputStream(tagged));
        assertEquals(SmaltAudioFormats.MP3, AudioSystem.getAudioFileFormat(stream).getType());
        assertEquals(tagged.length, stream.readAllBytes().length, "the stream is reset after describing it");

        // A 64 MiB body plus the footer and the bytes read after the tag would overrun the stream's mark.
        var body = 64 * 1024 * 1024;
        var huge = new byte[10 + body + 10 + 8192];
        System.arraycopy(id3v24Header(body, false), 0, huge, 0, 10);
        huge[5] = 0x10;
        assertThrows(UnsupportedAudioFileException.class, () -> new SmaltAudioFileReader()
                .getAudioFileFormat(new BufferedInputStream(new ByteArrayInputStream(huge))));
    }

    static byte[] id3v24Header(int body, boolean footer) {
        return new byte[]{(byte) (footer ? '3' : 'I'), 'D', (byte) (footer ? 'I' : '3'), 4, 0, 0,
                (byte) ((body >>> 21) & 0x7F), (byte) ((body >>> 14) & 0x7F), (byte) ((body >>> 7) & 0x7F),
                (byte) (body & 0x7F)};
    }

    @Test
    @DisplayName("the conversion provider is found and lists the decodable encodings")
    void providers() throws IOException, UnsupportedAudioFileException {
        var encodings = Arrays.stream(new SmaltFormatConversionProvider().getSourceEncodings()).map(Object::toString).toList();
        assertTrue(encodings.containsAll(List.of("OPUS", "VORBIS", "FLAC", "MP3", "AAC")), encodings.toString());
        try (var encoded = AudioSystem.getAudioInputStream(Clip.FLAC.path().toFile())) {
            assertInstanceOf(EncodedAudioInputStream.class, encoded);
            assertTrue(AudioSystem.isConversionSupported(AudioFormat.Encoding.PCM_SIGNED, encoded.getFormat()));
            assertTrue(Arrays.asList(AudioSystem.getTargetEncodings(encoded.getFormat())).contains(AudioFormat.Encoding.PCM_FLOAT));
        }
    }
}

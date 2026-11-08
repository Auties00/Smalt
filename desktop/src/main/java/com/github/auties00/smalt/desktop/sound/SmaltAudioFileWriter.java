package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSink;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.spi.AudioFileWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Writes compressed audio files for Java Sound with the encoders of the codec artifacts present at run
 * time.
 *
 * <p>The types, each offered when an encoder of its codec is present ({@code smalt-audio} for Opus,
 * Vorbis, FLAC and MP3, {@code smalt-mpeg} for AAC, AMR, AC-3 and E-AC-3), are:
 * <table class="striped">
 * <caption>File types written</caption>
 * <thead><tr><th scope="col">Type</th><th scope="col">File</th></tr></thead>
 * <tbody>
 * <tr><td>{@link SmaltAudioFormats#OGG_OPUS}</td><td>Opus in Ogg ({@code .opus})</td></tr>
 * <tr><td>{@link SmaltAudioFormats#OGG}</td><td>Vorbis in Ogg ({@code .ogg})</td></tr>
 * <tr><td>{@link SmaltAudioFormats#FLAC}</td><td>native FLAC, at the input's sample size for integer
 * input, 16 bits for G.711 input and 24 bits for float input</td></tr>
 * <tr><td>{@link SmaltAudioFormats#MP3}</td><td>MPEG-1 or MPEG-2 Layer III</td></tr>
 * <tr><td>{@link SmaltAudioFormats#MPEG4}</td><td>AAC in an M4A file</td></tr>
 * <tr><td>{@link SmaltAudioFormats#AAC}</td><td>AAC in ADTS framing</td></tr>
 * <tr><td>{@link SmaltAudioFormats#AMR}</td><td>an AMR-WB file for input at 16 kHz or more, AMR-NB
 * otherwise (the other when only one encoder is present), mono, resampled to 16 or 8 kHz</td></tr>
 * <tr><td>{@link SmaltAudioFormats#AC3}</td><td>an AC-3 elementary stream</td></tr>
 * <tr><td>{@link SmaltAudioFormats#EAC3}</td><td>an E-AC-3 elementary stream</td></tr>
 * </tbody>
 * </table>
 *
 * <p>The input is any stream {@link SampledAudio} reads at a positive whole sample rate, or any stream
 * Java Sound converts to {@link AudioFormat.Encoding#PCM_SIGNED} (such as a compressed stream of
 * {@link SmaltAudioFileReader}). A rate or channel layout the codec cannot code is converted to one it
 * can (48 kHz, stereo), as {@link com.github.auties00.smalt.audio.AudioWriter} does. Integer samples reach
 * FLAC unchanged, so a FLAC file decodes to exactly the input samples. A file whose container marks the
 * encoder's priming and padding (Ogg, M4A, and an MP3 file written to a {@link File}) decodes to as many
 * samples as were written. A stream that {@link SmaltFormatConversionProvider} encoded to the codec of the
 * type, in the container the type names, is copied byte for byte instead of being encoded again. WAV,
 * AIFF and AU are left to the JDK's writers.
 *
 * <p>This class is thread-safe.
 */
public final class SmaltAudioFileWriter extends AudioFileWriter {
    /**
     * Constructs the writer.
     */
    public SmaltAudioFileWriter() {
    }

    @Override
    public AudioFileFormat.Type[] getAudioFileTypes() {
        var types = new ArrayList<AudioFileFormat.Type>();
        for (var type : EncodedFiles.WRITTEN_TYPES) {
            if (EncodedFiles.forFileType(type, null).isPresent()) {
                types.add(type);
            }
        }
        return types.toArray(AudioFileFormat.Type[]::new);
    }

    @Override
    public AudioFileFormat.Type[] getAudioFileTypes(AudioInputStream stream) {
        Objects.requireNonNull(stream, "stream");
        return isWritable(stream.getFormat()) ? getAudioFileTypes() : new AudioFileFormat.Type[0];
    }

    /**
     * {@inheritDoc}
     *
     * <p>The stream is not closed.
     */
    @Override
    public int write(AudioInputStream stream, AudioFileFormat.Type fileType, OutputStream out) throws IOException {
        Objects.requireNonNull(out, "out");
        check(stream, fileType);
        try (var sink = MediaSink.of(out)) {
            return write(stream, fileType, sink);
        } catch (MediaException.Io exception) {
            throw exception.getCause();
        } catch (MediaException exception) {
            throw new IOException("cannot encode the audio: " + exception.getMessage(), exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The stream is not closed.
     */
    @Override
    public int write(AudioInputStream stream, AudioFileFormat.Type fileType, File out) throws IOException {
        Objects.requireNonNull(out, "out");
        check(stream, fileType);
        try (var sink = MediaSink.of(out.toPath())) {
            return write(stream, fileType, sink);
        } catch (MediaException.Io exception) {
            throw exception.getCause();
        } catch (MediaException exception) {
            throw new IOException("cannot encode the audio: " + exception.getMessage(), exception);
        }
    }

    /**
     * Checks a write request.
     *
     * @param stream the input
     * @param fileType the requested type
     * @throws IllegalArgumentException if the type or the stream's format is not supported
     */
    private void check(AudioInputStream stream, AudioFileFormat.Type fileType) {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(fileType, "fileType");
        if (!isFileTypeSupported(fileType, stream)) {
            throw new IllegalArgumentException("cannot write " + stream.getFormat() + " as " + fileType);
        }
    }

    /**
     * Returns whether a stream format can be written.
     *
     * @param format the stream format
     * @return {@code true} for a format {@link SampledAudio} reads at a whole sample rate, or a format
     * Java Sound converts to {@link AudioFormat.Encoding#PCM_SIGNED}
     */
    private static boolean isWritable(AudioFormat format) {
        if (SampledAudio.isSupported(format)) {
            return isWholeRate(format);
        }
        return AudioSystem.isConversionSupported(AudioFormat.Encoding.PCM_SIGNED, format);
    }

    /**
     * Returns whether a format's sample rate is a positive whole number.
     *
     * @param format the format whose rate is checked
     * @return {@code true} for a positive whole rate
     */
    static boolean isWholeRate(AudioFormat format) {
        var rate = format.getSampleRate();
        return rate > 0 && rate == Math.rint(rate);
    }

    /**
     * Writes a stream as a file of a type.
     *
     * @param stream the input
     * @param fileType the type
     * @param sink the output
     * @return the number of bytes written, capped at {@link Integer#MAX_VALUE}
     * @throws IOException if reading the input fails
     */
    private static int write(AudioInputStream stream, AudioFileFormat.Type fileType, MediaSink sink)
            throws IOException {
        if (stream instanceof EncodingAudioInputStream encoded
                && EncodedFiles.isFileOf(fileType, encoded.target(), encoded.input())) {
            var buffer = new byte[64 * 1024];
            for (var read = encoded.read(buffer); read >= 0; read = encoded.read(buffer)) {
                sink.write(ByteBuffer.wrap(buffer, 0, read));
            }
            return (int) Math.min(Integer.MAX_VALUE, sink.position());
        }
        var input = SampledAudio.isSupported(stream.getFormat()) && isWholeRate(stream.getFormat())
                ? stream
                : AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, stream);
        var format = input.getFormat();
        var target = EncodedFiles.forFileType(fileType, format).orElseThrow();
        try (var encoder = target.open(format, sink)) {
            var frameSize = SampledAudio.frameSize(format);
            var bytes = new byte[EncodedFiles.PcmEncoder.BLOCK_FRAMES * frameSize];
            while (true) {
                var read = input.readNBytes(bytes, 0, bytes.length);
                encoder.write(bytes, 0, read / frameSize);
                if (read < bytes.length) {
                    break;
                }
            }
            encoder.finish();
            return (int) Math.min(Integer.MAX_VALUE, sink.position());
        }
    }
}

package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.process.ChannelMixer;
import com.github.auties00.smalt.audio.process.Resampler;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.internal.codec.EncoderTail;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.spi.AudioFileWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Writes audio files for Java Sound with the encoders of the codec artifacts present at run time.
 *
 * <p>The only type is {@link SmaltAudioFormats#OGG_OPUS} (Opus in Ogg, {@code .opus}), offered
 * when an Opus encoder is present ({@code smalt-audio}). The input is any PCM stream
 * {@link SampledAudio} reads, or any stream Java Sound converts to
 * {@link AudioFormat.Encoding#PCM_SIGNED} (such as a compressed stream of
 * {@link SmaltAudioFileReader}). Opus codes 8, 12, 16, 24 or 48 kHz: other rates are resampled to
 * 48 kHz; up to 255 channels are coded as they are (surround layouts up to 7.1 with the Opus
 * surround mapping) and more are downmixed to stereo. The file ends exactly at the last input
 * sample (the Ogg end granule position trims the encoder's padding). WAV, AIFF and AU are left to
 * the JDK's writers.
 *
 * <p>This class is thread-safe.
 */
public final class SmaltAudioFileWriter extends AudioFileWriter {
    /**
     * The track identifier of the encoded stream.
     */
    private static final int TRACK_ID = 0;

    /**
     * The most channels an Opus stream carries.
     */
    private static final int MAX_CHANNELS = 255;

    /**
     * The number of frames read from the input per block.
     */
    private static final int BLOCK_FRAMES = 4096;

    /**
     * Constructs the writer.
     */
    public SmaltAudioFileWriter() {
    }

    @Override
    public AudioFileFormat.Type[] getAudioFileTypes() {
        return AudioEncoders.isSupported(Codec.OPUS)
                ? new AudioFileFormat.Type[]{SmaltAudioFormats.OGG_OPUS}
                : new AudioFileFormat.Type[0];
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
            return encode(stream, sink);
        } catch (MediaException.Io exception) {
            throw exception.getCause();
        } catch (MediaException exception) {
            throw new IOException("cannot encode the audio: " + exception.getMessage(), exception);
        }
    }

    @Override
    public int write(AudioInputStream stream, AudioFileFormat.Type fileType, File out) throws IOException {
        Objects.requireNonNull(out, "out");
        check(stream, fileType);
        try (var sink = MediaSink.of(out.toPath())) {
            return encode(stream, sink);
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
     * @return {@code true} for a PCM format {@link SampledAudio} reads at a whole sample rate, or a
     * format Java Sound converts to {@link AudioFormat.Encoding#PCM_SIGNED}
     */
    private static boolean isWritable(AudioFormat format) {
        if (SampledAudio.isSupported(format)) {
            var rate = format.getSampleRate();
            return rate > 0 && rate == Math.rint(rate);
        }
        return AudioSystem.isConversionSupported(AudioFormat.Encoding.PCM_SIGNED, format);
    }

    /**
     * Encodes a stream as Ogg Opus.
     *
     * @param stream the input
     * @param sink the output
     * @return the number of bytes written, capped at {@link Integer#MAX_VALUE}
     * @throws IOException if reading the input fails
     */
    private static int encode(AudioInputStream stream, MediaSink sink) throws IOException {
        var input = SampledAudio.isSupported(stream.getFormat())
                ? stream
                : AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, stream);
        var format = input.getFormat();
        var inputRate = Math.round(format.getSampleRate());
        var inputLayout = SampledAudio.layoutOf(format);
        var outputLayout = inputLayout.channels() <= MAX_CHANNELS ? inputLayout : ChannelLayout.STEREO;
        try (var encoder = EncoderTail.open(Codec.OPUS, inputRate, outputLayout, false, true, AudioEncoderOptions::of);
             var muxer = new OggMuxer(sink)) {
            muxer.addTrack(EncoderTail.track(encoder, TRACK_ID).build());
            var pipeline = new Pipeline(encoder, new EncoderTail(TRACK_ID, muxer::writePacket), inputRate,
                    inputLayout);
            var frameSize = SampledAudio.frameSize(format);
            var bytes = new byte[BLOCK_FRAMES * frameSize];
            var planes = new float[inputLayout.channels()][BLOCK_FRAMES];
            while (true) {
                var read = input.readNBytes(bytes, 0, bytes.length);
                var frames = read / frameSize;
                if (frames > 0) {
                    SampledAudio.read(bytes, 0, frames, format, planes, 0);
                    pipeline.accept(planes, frames);
                }
                if (read < bytes.length) {
                    break;
                }
            }
            pipeline.finish();
            muxer.finish();
            return (int) Math.min(Integer.MAX_VALUE, sink.position());
        }
    }

    /**
     * Downmixes, resamples and encodes blocks of input.
     */
    private static final class Pipeline {
        /**
         * The encoder.
         */
        private final AudioEncoder encoder;

        /**
         * Receives the packets one behind and trims the last.
         */
        private final EncoderTail writer;

        /**
         * The downmixer, or {@code null} when the input layout is coded as is.
         */
        private final ChannelMixer mixer;

        /**
         * The resampler, or {@code null} when the input rate is coded as is.
         */
        private final Resampler resampler;

        /**
         * The downmixed block, or {@code null} without a downmixer.
         */
        private float[][] mixed;

        /**
         * The resampled block, or {@code null} without a resampler.
         */
        private float[][] resampled;

        /**
         * The number of frames passed to the encoder.
         */
        private long samples;

        /**
         * Constructs the pipeline.
         *
         * @param encoder the open Opus encoder
         * @param writer receives the packets
         * @param inputRate the input rate, in hertz
         * @param inputLayout the input layout
         */
        private Pipeline(AudioEncoder encoder, EncoderTail writer, int inputRate, ChannelLayout inputLayout) {
            this.encoder = encoder;
            this.writer = writer;
            var layout = encoder.layout();
            this.mixer = layout.equals(inputLayout) ? null : new ChannelMixer(inputLayout, layout);
            this.resampler = encoder.sampleRate() == inputRate
                    ? null
                    : new Resampler(inputRate, encoder.sampleRate(), layout.channels(), Resampler.Quality.STANDARD);
        }

        /**
         * Encodes a block of input.
         *
         * @param planes the block, at the input rate and layout
         * @param frames the number of frames in the block
         */
        private void accept(float[][] planes, int frames) {
            var block = planes;
            if (mixer != null) {
                mixed = capacity(mixed, frames);
                mixer.process(block, mixed, frames);
                block = mixed;
            }
            if (resampler == null) {
                push(block, frames);
                return;
            }
            resampled = capacity(resampled, resampler.maxOutputFrames(frames));
            push(resampled, resampler.process(block, frames, resampled, 0));
        }

        /**
         * Encodes the resampler's remaining output, flushes the encoder and ends the stream at the
         * last input sample.
         */
        private void finish() {
            if (resampler != null) {
                resampled = capacity(resampled, resampler.maxOutputFrames(0));
                push(resampled, resampler.flush(resampled, 0));
            }
            encoder.flush(writer);
            writer.finish(samples + encoder.delay());
        }

        /**
         * Encodes samples at the coded rate and layout.
         *
         * @param planes the samples
         * @param frames the number of frames
         */
        private void push(float[][] planes, int frames) {
            if (frames > 0) {
                encoder.encode(new AudioBuffer(encoder.layout(), encoder.sampleRate(), planes, frames), writer);
                samples += frames;
            }
        }

        /**
         * Returns planes of the coded layout holding at least a number of frames.
         *
         * @param planes the current planes, or {@code null}
         * @param frames the frames needed
         * @return {@code planes}, or larger new planes
         */
        private float[][] capacity(float[][] planes, int frames) {
            if (planes != null && planes[0].length >= frames) {
                return planes;
            }
            return new float[encoder.layout().channels()][Math.max(frames, BLOCK_FRAMES)];
        }
    }
}

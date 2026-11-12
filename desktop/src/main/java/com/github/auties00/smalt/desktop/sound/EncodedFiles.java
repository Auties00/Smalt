package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.AudioWriter;
import com.github.auties00.smalt.audio.AudioWriterOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.transcode.OutputContainer;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chooses the codec and container a compressed file type or encoding is written in, and encodes interleaved
 * sample bytes into it.
 */
final class EncodedFiles {
    /**
     * The file types {@link SmaltAudioFileWriter} writes, in the order it lists them.
     */
    static final List<AudioFileFormat.Type> WRITTEN_TYPES = List.of(SmaltAudioFormats.OGG_OPUS, SmaltAudioFormats.OGG,
            SmaltAudioFormats.FLAC, SmaltAudioFormats.MP3, SmaltAudioFormats.MPEG4, SmaltAudioFormats.AAC,
            SmaltAudioFormats.AMR, SmaltAudioFormats.AC3, SmaltAudioFormats.EAC3);

    /**
     * Prevents instantiation.
     */
    private EncodedFiles() {
        throw new AssertionError();
    }

    /**
     * Returns the encoding of a written file type, with its container.
     *
     * @param type the file type
     * @param input the format of the samples to write
     * @return the target, or empty when the type is not written or no encoder of its codec is present
     */
    static Optional<Target> forFileType(AudioFileFormat.Type type, AudioFormat input) {
        Objects.requireNonNull(type, "type");
        if (type.equals(SmaltAudioFormats.OGG_OPUS)) {
            return target(OutputContainer.OGG, Codec.OPUS);
        }
        if (type.equals(SmaltAudioFormats.OGG)) {
            return target(OutputContainer.OGG, Codec.VORBIS);
        }
        if (type.equals(SmaltAudioFormats.FLAC)) {
            return target(OutputContainer.FLAC, Codec.FLAC);
        }
        if (type.equals(SmaltAudioFormats.MP3)) {
            return target(OutputContainer.MP3, Codec.MP3);
        }
        if (type.equals(SmaltAudioFormats.MPEG4)) {
            return target(OutputContainer.M4A, Codec.AAC);
        }
        if (type.equals(SmaltAudioFormats.AAC)) {
            return target(OutputContainer.ADTS, Codec.AAC);
        }
        if (type.equals(SmaltAudioFormats.AMR)) {
            var wide = input == null || input.getSampleRate() == -1 || input.getSampleRate() >= 16_000;
            var preferred = target(OutputContainer.AMR, wide ? Codec.AMR_WB : Codec.AMR_NB);
            return preferred.isPresent() ? preferred : target(OutputContainer.AMR, wide ? Codec.AMR_NB : Codec.AMR_WB);
        }
        if (type.equals(SmaltAudioFormats.AC3)) {
            return target(OutputContainer.AC3, Codec.AC3);
        }
        if (type.equals(SmaltAudioFormats.EAC3)) {
            return target(OutputContainer.EAC3, Codec.EAC3);
        }
        return Optional.empty();
    }

    /**
     * Returns whether a file of a target is a file of a written type.
     *
     * @param type the file type
     * @param target the codec and container of the file
     * @param input the format of the samples the file was encoded from
     * @return {@code true} when {@link #forFileType} gives the target for the input, or the type is
     * {@link SmaltAudioFormats#AMR} and the target an AMR-NB or AMR-WB file
     */
    static boolean isFileOf(AudioFileFormat.Type type, Target target, AudioFormat input) {
        if (type.equals(SmaltAudioFormats.AMR)) {
            return target.container() == OutputContainer.AMR;
        }
        return forFileType(type, input).filter(target::equals).isPresent();
    }

    /**
     * Returns the container a stream of an encoding is written in.
     *
     * @param codec the compressed codec
     * @return the elementary or Ogg container of the codec, or empty when PCM cannot be encoded to it
     */
    static Optional<OutputContainer> streamContainerOf(Codec codec) {
        return Optional.ofNullable(switch (codec) {
            case OPUS, VORBIS -> OutputContainer.OGG;
            case FLAC -> OutputContainer.FLAC;
            case MP3 -> OutputContainer.MP3;
            case AAC -> OutputContainer.ADTS;
            case AMR_NB, AMR_WB -> OutputContainer.AMR;
            case AC3 -> OutputContainer.AC3;
            case EAC3 -> OutputContainer.EAC3;
            default -> null;
        });
    }

    /**
     * Returns the target of a codec in a container, when an encoder of the codec is present.
     *
     * @param container the file container
     * @param codec the codec of the audio track
     * @return the target, or empty without an encoder
     */
    static Optional<Target> target(OutputContainer container, Codec codec) {
        return AudioEncoders.isSupported(codec) ? Optional.of(new Target(container, codec)) : Optional.empty();
    }

    /**
     * Returns the sample rate and channel layout a codec codes an input format in, when the codec has a
     * fixed format.
     *
     * @param codec the codec of the audio track
     * @param builder the options to complete
     */
    private static void fixedFormat(Codec codec, AudioWriterOptions.Builder builder) {
        switch (codec) {
            case AMR_NB -> builder.outputSampleRate(8_000).outputLayout(ChannelLayout.MONO);
            case AMR_WB -> builder.outputSampleRate(16_000).outputLayout(ChannelLayout.MONO);
            default -> {
            }
        }
    }

    /**
     * Describes the codec and container of an encoded file.
     *
     * @param container the file container
     * @param codec the codec of its audio track
     */
    record Target(OutputContainer container, Codec codec) {
        /**
         * Opens an encoder of samples of a format into a sink.
         *
         * @param input the format of the sample bytes, one {@link SampledAudio} supports at a positive whole
         * rate
         * @param sink the output, not closed by the encoder
         * @return the encoder, which the caller finishes and closes
         */
        PcmEncoder open(AudioFormat input, MediaSink sink) {
            return open(input, AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED, sink);
        }

        /**
         * Opens an encoder of samples of a format into a sink, coding a given sample rate and channel count.
         *
         * @param input the format of the sample bytes, one {@link SampledAudio} supports at a positive whole
         * rate
         * @param outputRate the coded sample rate, or {@link AudioSystem#NOT_SPECIFIED} for the one the codec
         * picks
         * @param outputChannels the coded channel count, or {@link AudioSystem#NOT_SPECIFIED} for the one the
         * codec picks
         * @param sink the output, not closed by the encoder
         * @return the encoder, which the caller finishes and closes
         * @throws IllegalArgumentException if the codec cannot code the input, or the coded format asked for
         */
        PcmEncoder open(AudioFormat input, int outputRate, int outputChannels, MediaSink sink) {
            var rate = Math.round(input.getSampleRate());
            var layout = SampledAudio.layoutOf(input);
            var builder = AudioWriterOptions.of(rate, layout).toBuilder().codec(codec);
            var integer = PcmEncoder.isInteger(input);
            if (codec == Codec.FLAC) {
                builder.bitsPerSample(integer ? input.getSampleSizeInBits()
                        : AudioFormat.Encoding.PCM_FLOAT.equals(input.getEncoding()) ? 24 : 16);
            }
            fixedFormat(codec, builder);
            if (outputRate != AudioSystem.NOT_SPECIFIED) {
                builder.outputSampleRate(outputRate);
            }
            if (outputChannels != AudioSystem.NOT_SPECIFIED) {
                builder.outputLayout(ChannelLayout.defaultFor(outputChannels));
            }
            return new PcmEncoder(AudioWriter.open(sink, container, builder.build()), input, integer);
        }
    }

    /**
     * Encodes interleaved sample bytes through an {@link AudioWriter}, integer samples as integers so that a
     * lossless codec keeps them exactly.
     *
     * <p>Instances are not thread-safe.
     */
    static final class PcmEncoder implements AutoCloseable {
        /**
         * The frames converted per block.
         */
        static final int BLOCK_FRAMES = 4096;

        /**
         * The writer.
         */
        private final AudioWriter writer;

        /**
         * The format of the bytes.
         */
        private final AudioFormat format;

        /**
         * The integer block, or {@code null} for float input.
         */
        private final IntegerAudioBuffer integers;

        /**
         * The float block, or {@code null} for integer input.
         */
        private final AudioBuffer floats;

        /**
         * Constructs the encoder.
         *
         * @param writer the open writer, owned by the encoder
         * @param format the format of the bytes
         * @param integer whether the bytes hold integer samples
         */
        private PcmEncoder(AudioWriter writer, AudioFormat format, boolean integer) {
            this.writer = writer;
            this.format = format;
            var rate = Math.round(format.getSampleRate());
            var layout = SampledAudio.layoutOf(format);
            this.integers = integer
                    ? new IntegerAudioBuffer(layout, rate, format.getSampleSizeInBits(), BLOCK_FRAMES)
                    : null;
            this.floats = integer ? null : new AudioBuffer(layout, rate, BLOCK_FRAMES);
        }

        /**
         * Returns whether a format holds integer samples.
         *
         * @param format a format {@link SampledAudio} supports
         * @return {@code true} for signed and unsigned PCM
         */
        static boolean isInteger(AudioFormat format) {
            var encoding = format.getEncoding();
            return AudioFormat.Encoding.PCM_SIGNED.equals(encoding) || AudioFormat.Encoding.PCM_UNSIGNED.equals(encoding);
        }

        /**
         * {@return the writer}
         */
        AudioWriter writer() {
            return writer;
        }

        /**
         * Encodes whole frames of sample bytes.
         *
         * @param data the bytes
         * @param offset the index of the first byte
         * @param frames the number of frames, at most {@link #BLOCK_FRAMES}
         */
        void write(byte[] data, int offset, int frames) {
            if (frames == 0) {
                return;
            }
            if (integers != null) {
                SampledAudio.readIntegers(data, offset, frames, format, integers.planes(), 0);
                integers.setFrames(frames);
                writer.write(integers);
            } else {
                SampledAudio.read(data, offset, frames, format, floats.planes(), 0);
                floats.setFrames(frames);
                writer.write(floats);
            }
        }

        /**
         * Encodes the rest and completes the file.
         */
        void finish() {
            writer.finish();
        }

        @Override
        public void close() {
            writer.close();
        }
    }
}

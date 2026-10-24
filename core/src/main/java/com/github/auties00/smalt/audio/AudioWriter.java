package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.process.ChannelMixer;
import com.github.auties00.smalt.audio.process.Resampler;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.internal.codec.EncoderTail;
import com.github.auties00.smalt.internal.codec.WriterSupport;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.transcode.OutputContainer;

import java.util.Objects;

/**
 * Encodes planar samples into the audio track of a new media file, the writing counterpart of
 * {@link AudioReader}.
 *
 * <p>The writer opens the encoder and the muxer, declares the track and keeps its timing: blocks
 * of any length are passed in order through {@code write} in the input format of the
 * {@link AudioWriterOptions}, mixed and resampled to the coded format when the two differ, and
 * {@link #finish()} encodes the rest and completes the file. The file marks the encoder's priming
 * and the padding of the last frame where the container can (the MP4 edit list, the Ogg granule
 * positions, the Matroska codec delay and discard padding), so an {@link AudioReader} of it
 * returns as many samples as were coded (the samples written, resampled when the rates differ),
 * neither delayed nor padded, unless the container leaves the trim unsaid (the ADTS, AC-3 and AMR
 * elementary streams, and an MP3 file written to a sink that cannot seek back to its LAME tag).
 * An instance
 * that is closed without finishing leaves the output incomplete, so it is typically used as:
 * {@snippet :
 * try (var writer = AudioWriter.open(sink, OutputContainer.OGG, AudioWriterOptions.of(44_100, ChannelLayout.STEREO))) {
 *     for (var block = reader.read(); block != null; block = reader.read()) {
 *         writer.write(block);
 *     }
 *     writer.finish();
 * }
 * }
 *
 * <p>Integer blocks reach an encoder that codes integers ({@link AudioEncoder#supportsIntegerInput()})
 * unchanged, and 64-bit float blocks an encoder that keeps their precision, when the input needs no
 * mixing or resampling; otherwise they are converted to floats first. Integer, float and double
 * blocks may be mixed in one stream.
 *
 * <p>Instances are not thread-safe.
 */
public final class AudioWriter implements AutoCloseable {
    /**
     * The identifier of the written track.
     */
    private static final int TRACK_ID = 1;

    private final Muxer muxer;
    private final AudioEncoder encoder;
    private final AudioTrack track;

    /**
     * Writes the encoder's packets one behind, so the last one can end at the last sample.
     */
    private final EncoderTail tail;

    private final int sampleRate;
    private final ChannelLayout layout;

    /**
     * The mixer from the input to the coded layout, or {@code null} when they are equal.
     */
    private final ChannelMixer mixer;

    /**
     * The resampler from the input to the coded rate, or {@code null} when they are equal.
     */
    private final Resampler resampler;

    /**
     * Whether the mixer runs before the resampler, so the resampler filters the fewer channels.
     */
    private final boolean mixFirst;

    /**
     * The input converted to floats, grown to the largest block, or {@code null} before the first
     * integer or double block that needs it.
     */
    private float[][] converted;

    /**
     * The output of the mixer, grown to the largest block, or {@code null}.
     */
    private float[][] mixed;

    /**
     * The output of the resampler, grown to the largest block, or {@code null}.
     */
    private float[][] resampled;

    /**
     * The block handed to the encoder, wrapping one of the arrays above, or {@code null}.
     */
    private AudioBuffer block;

    /**
     * The number of input samples per channel written.
     */
    private long framesWritten;

    /**
     * The number of samples per channel passed to the encoder, at the coded rate.
     */
    private long encodedFrames;

    private boolean finished;

    /**
     * Whether a call failed after it started encoding, which leaves the output unusable.
     */
    private boolean failed;

    /**
     * Constructs a writer and declares its track.
     *
     * @param muxer the muxer, owned by the writer, to which nothing was written
     * @param encoder the encoder, owned by the writer
     * @param options the settings
     */
    private AudioWriter(Muxer muxer, AudioEncoder encoder, AudioWriterOptions options) {
        this.muxer = muxer;
        this.encoder = encoder;
        this.sampleRate = options.sampleRate();
        this.layout = options.layout();
        var codedLayout = encoder.layout();
        this.mixer = codedLayout.equals(layout) ? null : new ChannelMixer(layout, codedLayout);
        this.mixFirst = codedLayout.channels() <= layout.channels();
        this.resampler = encoder.sampleRate() == sampleRate ? null : new Resampler(sampleRate, encoder.sampleRate(),
                mixFirst ? codedLayout.channels() : layout.channels(), Resampler.Quality.STANDARD);
        this.track = EncoderTail.track(encoder, TRACK_ID).language(options.language().orElse(null)).build();
        this.tail = new EncoderTail(TRACK_ID, muxer::writePacket);
        WriterSupport.requireTags(muxer, options.tags());
        muxer.addTrack(track);
        WriterSupport.writeTags(muxer, options.tags());
    }

    /**
     * Creates a writer of a file of a container profile for 48 kHz stereo input, coded with the
     * profile's default codec.
     *
     * @param output the sink, written from its position and not closed by the writer
     * @param container the file profile
     * @return the writer, which the caller finishes and closes
     * @throws IllegalArgumentException if the profile carries no audio
     * @throws MediaException.Unsupported if no encoder is available for a default codec of the
     * profile
     * @throws MediaException.Io if writing the sink fails
     * @see #open(MediaSink, OutputContainer, AudioWriterOptions)
     */
    public static AudioWriter open(MediaSink output, OutputContainer container) {
        return open(output, container, AudioWriterOptions.defaults());
    }

    /**
     * Creates a writer of a file of a container profile.
     *
     * <p>The codec is {@link AudioWriterOptions#codec()}, or else the first codec of
     * {@link OutputContainer#audioCodecs()} that an encoder is available for. The muxer is the one
     * {@link Containers#openMuxer(OutputContainer, MediaSink)} returns.
     *
     * @param output the sink, written from its position and not closed by the writer
     * @param container the file profile
     * @param options the input format, codec and coding settings
     * @return the writer, which the caller finishes and closes
     * @throws IllegalArgumentException if the profile cannot carry the codec or carries no audio,
     * or the codec cannot code the input format or bitrate in any of the formats the options allow
     * @throws MediaException.Unsupported if no encoder is available for the codec, or the tags are not
     * empty and the profile cannot store tags
     * @throws MediaException.Io if writing the sink fails
     */
    public static AudioWriter open(MediaSink output, OutputContainer container, AudioWriterOptions options) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(options, "options");
        var codec = WriterSupport.codec(options.codec(), container, container.audioCodecs(), AudioEncoders::isSupported,
                "audio");
        var encoder = openEncoder(codec, options);
        Muxer muxer = null;
        try {
            muxer = Containers.openMuxer(container, output);
            return new AudioWriter(muxer, encoder, options);
        } catch (RuntimeException exception) {
            encoder.close();
            if (muxer != null) {
                muxer.close();
            }
            throw exception;
        }
    }

    /**
     * Creates a writer that writes its track to a caller's muxer, for muxer options the profiles of
     * {@link OutputContainer} do not choose.
     *
     * <p>The track is declared with the identifier 1. The writer owns the muxer from the moment
     * this method returns: {@link #finish()} finishes it and {@link #close()} closes it. When this
     * method fails, the muxer stays with the caller.
     *
     * @param muxer the muxer, to which nothing was written yet
     * @param options the input format, codec and coding settings; the codec is required
     * @return the writer, which the caller finishes and closes
     * @throws IllegalArgumentException if the options name no codec, the muxer cannot carry the
     * codec, or the codec cannot code the input format or bitrate in any of the formats the options
     * allow
     * @throws IllegalStateException if a packet was already written to the muxer
     * @throws MediaException.Unsupported if no encoder is available for the codec, or the tags are not
     * empty and the muxer cannot store tags
     */
    public static AudioWriter open(Muxer muxer, AudioWriterOptions options) {
        Objects.requireNonNull(muxer, "muxer");
        Objects.requireNonNull(options, "options");
        var codec = WriterSupport.codec(options.codec(), AudioEncoders::isSupported);
        var encoder = openEncoder(codec, options);
        try {
            return new AudioWriter(muxer, encoder, options);
        } catch (RuntimeException exception) {
            encoder.close();
            throw exception;
        }
    }

    /**
     * Opens the encoder, falling back to 48 kHz and to stereo where the options leave the coded
     * format free.
     *
     * @param codec the codec to encode
     * @param options the settings
     * @return the encoder
     * @throws IllegalArgumentException if the codec rejects every candidate format
     */
    private static AudioEncoder openEncoder(Codec codec, AudioWriterOptions options) {
        var rate = options.outputSampleRate().orElse(options.sampleRate());
        var codedLayout = options.outputLayout().orElse(options.layout());
        return EncoderTail.open(codec, rate, codedLayout, options.outputSampleRate().isPresent(),
                options.outputLayout().isPresent(), (candidateRate, candidateLayout) -> {
                    var builder = AudioEncoderOptions.of(candidateRate, candidateLayout).toBuilder()
                            .realtime(options.realtime());
                    options.bitrate().ifPresent(builder::bitrate);
                    options.bitsPerSample().ifPresent(builder::bitsPerSample);
                    return builder.build();
                });
    }

    /**
     * {@return the codec of the written track}
     */
    public Codec codec() {
        return encoder.codec();
    }

    /**
     * {@return the track as declared to the muxer: its codec, coded rate and layout, time base of
     * one sample and the encoder's configuration and priming}
     *
     * <p>A codec whose configuration is complete only at the end of the stream (the FLAC
     * {@code STREAMINFO}) stores its final form at {@link #finish()}, where the container allows.
     */
    public AudioTrack track() {
        return track;
    }

    /**
     * {@return the sampling rate of the input in hertz}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the channel layout of the input}
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the number of input samples per channel written so far}
     */
    public long framesWritten() {
        return framesWritten;
    }

    /**
     * {@return the duration of the input written so far, in microseconds, rounded down}
     */
    public long durationMicros() {
        return Rational.timeBaseOf(sampleRate).toMicros(framesWritten);
    }

    /**
     * Encodes a block of float samples.
     *
     * <p>Samples outside {@code [-1, 1]} are clipped by the encoder. The block is not retained.
     *
     * @param input the samples, of any length, in the input rate and layout
     * @throws IllegalArgumentException if the block's rate or layout differs from the input format
     * @throws IllegalStateException if the writer is finished or an earlier call failed
     * @throws MediaException.Unsupported if the muxer cannot store a change of the codec configuration
     * @throws MediaException.Io if writing the output fails
     */
    public void write(AudioBuffer input) {
        Objects.requireNonNull(input, "input");
        check(input.layout(), input.sampleRate());
        try {
            if (mixer == null && resampler == null) {
                if (input.frames() > 0) {
                    encoder.encode(input, tail);
                    encodedFrames += input.frames();
                }
            } else {
                encodeFloats(input.planes(), input.frames());
            }
            framesWritten += input.frames();
        } catch (RuntimeException exception) {
            failed = true;
            throw exception;
        }
    }

    /**
     * Encodes a block of integer samples, of {@link IntegerAudioBuffer#bitsPerSample()} bits.
     *
     * <p>An encoder that codes integers receives them as {@link AudioEncoder#encode(IntegerAudioBuffer,
     * java.util.function.Consumer)} describes when the input needs no mixing or resampling; otherwise each sample
     * becomes the float {@code sample * 2^-(bitsPerSample - 1)}. The block is not retained.
     *
     * @param input the samples, of any length, in the input rate and layout
     * @throws IllegalArgumentException if the block's rate or layout differs from the input format
     * @throws IllegalStateException if the writer is finished or an earlier call failed
     * @throws MediaException.Unsupported if the muxer cannot store a change of the codec configuration
     * @throws MediaException.Io if writing the output fails
     */
    public void write(IntegerAudioBuffer input) {
        Objects.requireNonNull(input, "input");
        check(input.layout(), input.sampleRate());
        try {
            var frames = input.frames();
            if (mixer == null && resampler == null && encoder.supportsIntegerInput()) {
                encoder.encode(input, tail);
                encodedFrames += frames;
            } else {
                var scale = (float) Math.scalb(1.0, 1 - input.bitsPerSample());
                var target = convertedPlanes(frames);
                for (var channel = 0; channel < target.length; channel++) {
                    var source = input.plane(channel);
                    var plane = target[channel];
                    for (var index = 0; index < frames; index++) {
                        plane[index] = source[index] * scale;
                    }
                }
                encodeFloats(target, frames);
            }
            framesWritten += frames;
        } catch (RuntimeException exception) {
            failed = true;
            throw exception;
        }
    }

    /**
     * Encodes a block of 64-bit float samples.
     *
     * <p>An encoder that keeps more than single precision receives them unchanged when the input
     * needs no mixing or resampling; otherwise they are rounded to floats. The block is not retained.
     *
     * @param input the samples, of any length, in the input rate and layout
     * @throws IllegalArgumentException if the block's rate or layout differs from the input format
     * @throws IllegalStateException if the writer is finished or an earlier call failed
     * @throws MediaException.Unsupported if the muxer cannot store a change of the codec configuration
     * @throws MediaException.Io if writing the output fails
     */
    public void write(DoubleAudioBuffer input) {
        Objects.requireNonNull(input, "input");
        check(input.layout(), input.sampleRate());
        try {
            var frames = input.frames();
            if (mixer == null && resampler == null && encoder.supportsDoubleInput()) {
                encoder.encode(input, tail);
                encodedFrames += frames;
            } else {
                var target = convertedPlanes(frames);
                for (var channel = 0; channel < target.length; channel++) {
                    var source = input.plane(channel);
                    var plane = target[channel];
                    for (var index = 0; index < frames; index++) {
                        plane[index] = (float) source[index];
                    }
                }
                encodeFloats(target, frames);
            }
            framesWritten += frames;
        } catch (RuntimeException exception) {
            failed = true;
            throw exception;
        }
    }

    /**
     * Rejects a block in another format, or a write once the writer is finished or failed.
     *
     * @param blockLayout the layout of the block
     * @param blockRate the rate of the block
     * @throws IllegalArgumentException if the block's format differs from the input format
     * @throws IllegalStateException if the writer is finished or failed
     */
    private void check(ChannelLayout blockLayout, int blockRate) {
        requireOpen();
        if (blockRate != sampleRate || !blockLayout.equals(layout)) {
            throw new IllegalArgumentException("block of " + blockRate + " Hz " + blockLayout + " does not match the "
                    + "input format, " + sampleRate + " Hz " + layout);
        }
    }

    /**
     * Rejects a call once the writer is finished or failed.
     *
     * @throws IllegalStateException if the writer is finished or failed
     */
    private void requireOpen() {
        if (finished) {
            throw new IllegalStateException("the writer is finished");
        }
        if (failed) {
            throw new IllegalStateException("an earlier call failed; the output is incomplete");
        }
    }

    /**
     * Mixes and resamples float input as needed and encodes it.
     *
     * @param planes the input planes, in the input layout
     * @param frames the number of samples per plane
     */
    private void encodeFloats(float[][] planes, int frames) {
        var current = planes;
        var count = frames;
        if (mixer != null && mixFirst) {
            current = mix(current, count);
        }
        if (resampler != null) {
            var capacity = resampler.maxOutputFrames(count);
            resampled = grow(resampled, current.length, capacity);
            count = resampler.process(current, count, resampled, 0);
            current = resampled;
        }
        if (mixer != null && !mixFirst) {
            current = mix(current, count);
        }
        encode(current, count);
    }

    /**
     * Mixes planes to the coded layout.
     *
     * @param planes the planes in the input layout
     * @param frames the number of samples per plane
     * @return the mixed planes
     */
    private float[][] mix(float[][] planes, int frames) {
        mixed = grow(mixed, encoder.layout().channels(), frames);
        mixer.process(planes, mixed, frames);
        return mixed;
    }

    /**
     * Passes planes in the coded format to the encoder.
     *
     * @param planes the planes, one of the writer's own arrays
     * @param frames the number of samples per plane
     */
    private void encode(float[][] planes, int frames) {
        if (frames == 0) {
            return;
        }
        if (block == null || block.planes() != planes) {
            block = new AudioBuffer(encoder.layout(), encoder.sampleRate(), planes, frames);
        } else {
            block.setFrames(frames);
        }
        encoder.encode(block, tail);
        encodedFrames += frames;
    }

    /**
     * Returns the planes integer and double input is converted into, grown to a block size.
     *
     * @param frames the number of samples per plane
     * @return the planes, in the input layout
     */
    private float[][] convertedPlanes(int frames) {
        converted = grow(converted, layout.channels(), frames);
        return converted;
    }

    /**
     * Returns planes that hold at least a number of samples, reusing the given ones when they do.
     *
     * @param planes the current planes, or {@code null}
     * @param channels the number of planes
     * @param frames the number of samples per plane needed
     * @return the planes
     */
    private static float[][] grow(float[][] planes, int channels, int frames) {
        if (planes != null && planes[0].length >= frames) {
            return planes;
        }
        var capacity = planes == null ? frames : Math.max(frames, planes[0].length + (planes[0].length >> 1));
        return new float[channels][Math.max(capacity, 1)];
    }

    /**
     * Encodes the samples the resampler and the encoder still hold and completes the file.
     *
     * <p>The last packet is shortened to end at the last sample written, and a codec configuration
     * that the encoder completes at the end of the stream replaces the declared one where the
     * container can store it.
     *
     * @throws IllegalStateException if the writer is already finished or an earlier call failed
     * @throws MediaException.Io if writing the output fails
     */
    public void finish() {
        requireOpen();
        try {
            if (resampler != null) {
                resampled = grow(resampled, resampler.channels(), resampler.maxOutputFrames(0));
                var count = resampler.flush(resampled, 0);
                var current = resampled;
                if (mixer != null && !mixFirst) {
                    current = mix(current, count);
                }
                encode(current, count);
            }
            encoder.flush(tail);
            tail.finish(encodedFrames + encoder.delay());
            encoder.codecConfig().ifPresent(config -> muxer.updateCodecConfig(TRACK_ID, config));
            muxer.finish();
            finished = true;
        } catch (RuntimeException exception) {
            failed = true;
            throw exception;
        }
    }

    /**
     * Releases the encoder and the muxer; the sink is not closed, and an unfinished file is left
     * incomplete.
     */
    @Override
    public void close() {
        try {
            encoder.close();
        } finally {
            muxer.close();
        }
    }
}

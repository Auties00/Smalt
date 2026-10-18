package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.header.VorbisChannelOrder;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes a Vorbis I audio stream into planar float samples.
 *
 * <p>The decoder is configured from the three Vorbis header packets (identification, comment and
 * setup) that {@link AudioTrack#codecConfig()} carries Xiph-laced, exactly as Matroska stores
 * them in {@code CodecPrivate}; Ogg demuxers lace the first three packets of the logical stream
 * the same way. Every audio packet is then passed to {@link #decode(Packet, Consumer)} in stream
 * order and yields at most one {@link AudioBuffer}, holding
 * {@code previousBlocksize / 4 + currentBlocksize / 4} samples per channel: the span from the
 * center of the previous block to the center of the current one. The first packet only primes
 * the overlap and yields no audio. Empty packets and packets whose type bit marks a header are
 * ignored, as the specification requires.
 *
 * <p>Output channels are in WAVE order. For one to eight channels the Vorbis I channel order
 * (section 4.3.9) is mapped to {@link ChannelLayout#defaultFor(int)}: for example 5.1 is coded
 * as FL, FC, FR, BL, BR, LFE and returned as FL, FR, FC, LFE, BL, BR. Streams with more than eight
 * channels have no defined order; their channels are returned in stream order as
 * {@link com.github.auties00.smalt.audio.Speaker#UNKNOWN} speakers.
 *
 * <p>Trimming is the caller's job: the decoder returns every sample the packets code. The
 * container signals how much to discard, through the granule position of the last Ogg page (or
 * the Matroska block duration) for the end, and through a negative starting granule (or
 * {@link AudioTrack#skipSamples()}) for the beginning; the caller drops those samples from the
 * decoded output.
 *
 * <p>A packet rejected with {@link MediaException.Malformed} leaves the decoder usable: the next
 * packet laps with the last one decoded successfully. The output does not depend on whether the
 * Vector API is enabled, nor on the vector width.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation follows libvorbis where the specification leaves behavior open.
 * Floor 1, residue decoding and the window arithmetic follow the specification's algorithms exactly;
 * the inverse MDCT is a quarter-size complex FFT in one fixed operation order, vectorized when the
 * Vector API is enabled.
 */
public final class VorbisDecoder implements AudioDecoder {
    /**
     * The stream configuration.
     */
    private final VorbisSetup setup;

    /**
     * The packet decoder holding the lapping state.
     */
    private final PacketDecoder decoder;

    /**
     * The output channel layout.
     */
    private final ChannelLayout layout;

    /**
     * The output buffer handed to consumers, reused for every packet.
     */
    private final AudioBuffer buffer;

    /**
     * The output plane of each stream channel.
     */
    private final float[][] streamPlanes;

    /**
     * Constructs a decoder for a Vorbis track with the default {@link MediaLimits}.
     *
     * @param track the track, whose codec configuration holds the three Xiph-laced header packets
     * @throws IllegalArgumentException if the track's codec is not {@link Codec#VORBIS}
     * @throws MediaException.Malformed if the configuration is missing or a header is invalid
     * @throws MediaException.Unsupported if a header uses a reserved type value
     * @throws MediaException.LimitExceeded if the stream exceeds the default limits
     */
    public VorbisDecoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a Vorbis track under the limits of codec-independent options.
     *
     * <p>Only {@link AudioDecoderOptions#limits()} applies: the decoder always outputs the
     * stream's own sampling rate, whatever {@link AudioDecoderOptions#preferredSampleRate()}
     * asks for. The channel count is checked against {@link MediaLimits#maxChannels()}, and the
     * combined size of every table and buffer the setup header makes the decoder allocate against
     * {@link MediaLimits#maxBytes()}, before allocating.
     *
     * @param track the track, whose codec configuration holds the three Xiph-laced header packets
     * @param options the codec-independent options, of which only the limits apply
     * @throws IllegalArgumentException if the track's codec is not {@link Codec#VORBIS}
     * @throws MediaException.Malformed if the configuration is missing or a header is invalid
     * @throws MediaException.Unsupported if a header uses a reserved type value
     * @throws MediaException.LimitExceeded if the stream exceeds the options' limits
     */
    public VorbisDecoder(AudioTrack track, AudioDecoderOptions options) {
        this(track, options.limits(), VorbisKernel.INSTANCE);
    }

    /**
     * Constructs a decoder for a Vorbis track that runs a specific DSP kernel.
     *
     * @param track the track, whose codec configuration holds the three Xiph-laced header
     * packets
     * @param limits the resource limits
     * @param kernel the DSP kernel
     * @throws IllegalArgumentException if the track's codec is not {@link Codec#VORBIS}
     * @throws MediaException.Malformed if the configuration is missing or a header is invalid
     * @throws MediaException.Unsupported if a header uses a reserved type value
     * @throws MediaException.LimitExceeded if the stream exceeds {@code limits}
     */
    VorbisDecoder(AudioTrack track, MediaLimits limits, VorbisKernel kernel) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(limits, "limits");
        if (track.codec() != Codec.VORBIS) {
            throw new IllegalArgumentException(track.codec() + " is not Vorbis");
        }
        var config = track.codecConfig()
                .orElseThrow(() -> new MediaException.Malformed("Vorbis track without header packets"));
        var budget = new AllocationBudget(limits);
        try {
            this.setup = VorbisSetup.parse(config, budget);
            this.decoder = new PacketDecoder(setup, kernel, budget);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt Vorbis headers", exception);
        }
        var channels = setup.channels;
        this.layout = ChannelLayout.defaultFor(channels);
        this.buffer = new AudioBuffer(layout, setup.sampleRate, decoder.maxSamples());
        var order = VorbisChannelOrder.toWave(channels);
        this.streamPlanes = new float[channels][];
        for (var i = 0; i < channels; i++) {
            streamPlanes[i] = buffer.plane(order != null ? order[i] : i);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return the rate declared by the identification header, in hertz
     */
    @Override
    public int sampleRate() {
        return setup.sampleRate;
    }

    /**
     * {@inheritDoc}
     *
     * @return the WAVE-ordered layout for the stream's channel count
     */
    @Override
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * Decodes one audio packet and hands the samples it completes to {@code output}.
     *
     * <p>The first packet after construction or {@link #flush(Consumer)} yields nothing; every
     * later audio packet yields one buffer.
     *
     * @param packet the next packet in stream order
     * @param output receives at most one block, valid only during the call
     * @throws MediaException.Malformed if the packet is corrupt; the decoder stays usable
     */
    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        int samples;
        try {
            samples = decoder.decode(packet.data(), streamPlanes);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt Vorbis audio packet", exception);
        }
        if (samples > 0) {
            buffer.setFrames(samples);
            output.accept(buffer);
        }
    }

    /**
     * Ends the stream and resets the lapping state.
     *
     * <p>Vorbis buffers no complete samples: the right half of the last block lies past the end
     * of the stream, so nothing is handed to {@code output}. The next packet starts a new stream
     * position (after a seek, for example) and only primes the decoder.
     *
     * @param output receives nothing
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
        decoder.reset();
    }
}

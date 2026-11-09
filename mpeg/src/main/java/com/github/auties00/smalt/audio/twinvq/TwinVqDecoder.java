package com.github.auties00.smalt.audio.twinvq;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.twinvq.internal.TwinVqObject;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes MPEG-4 TwinVQ, audio object types 7 (TwinVQ) and 21 (ER TwinVQ) of ISO/IEC 14496-3 subpart 4, into mono or
 * stereo samples.
 *
 * <p>Every tool of the standard is decoded: the interleaved vector quantizer, the LPC, Bark-scale and gain envelopes,
 * the periodic peak components, the window switching and shapes of the AAC filterbank, long term prediction, TNS, M/S
 * stereo, the scalable layers, the error sensitivity category order of ER TwinVQ and the error protection tool
 * ({@code epConfig} 1 to 3). An object carries each scalable layer, and with {@code epConfig} 1 each of its two error
 * sensitivity categories, in an elementary stream of its own; {@link #decode(List, Consumer)} takes an access unit of
 * each and decodes the layers present up to the first missing one, while {@link #decode(Packet, Consumer)} decodes an
 * object of a single stream.
 *
 * <p>Every frame decodes to one block of 1024 or 960 samples per channel, scaled so that 16-bit full scale spans
 * {@code [-1, 1]}; a block can exceed that range. The filterbank delays the output by one frame.
 *
 * <p>Instances are not thread-safe.
 */
public final class TwinVqDecoder implements AudioDecoder {
    /**
     * The object decoder.
     */
    private final TwinVqObject object;

    /**
     * The options the decoder was created with.
     */
    private final TwinVqDecoderOptions options;

    /**
     * The channel layout of the output.
     */
    private final ChannelLayout layout;

    /**
     * The buffer handed to the caller, reused for every block.
     */
    private final AudioBuffer buffer;

    /**
     * The samples of the frames of one call, per channel.
     */
    private final float[][] samples;

    /**
     * The access units of one call and their lengths.
     */
    private final byte[][] units;

    private final int[] lengths;

    /**
     * Whether {@link #close()} ran.
     */
    private boolean closed;

    /**
     * Constructs a decoder for a single-stream object under {@link TwinVqDecoderOptions#defaults()}.
     *
     * @param track the TwinVQ track
     * @throws IllegalArgumentException if the track is not a TwinVQ track
     * @throws MediaException.Malformed if the track has no configuration or its configuration is invalid
     * @throws MediaException.Unsupported if the configuration is not of object type 7 or 21 with one or two channels,
     * or maps error protection classes without direct mapping
     * @throws MediaException.LimitExceeded if the configuration declares more channels than the limits allow
     */
    public TwinVqDecoder(AudioTrack track) {
        this(List.of(Objects.requireNonNull(track, "track")), TwinVqDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a single-stream object under the codec-independent options, which give the limits.
     *
     * @param track the TwinVQ track
     * @param options the decoder settings; the preferred sample rate is ignored
     * @throws IllegalArgumentException if the track is not a TwinVQ track
     * @throws MediaException.Malformed if the track has no configuration or its configuration is invalid
     * @throws MediaException.Unsupported if the configuration is not of object type 7 or 21 with one or two channels,
     * or maps error protection classes without direct mapping
     * @throws MediaException.LimitExceeded if the configuration declares more channels than the limits allow
     */
    public TwinVqDecoder(AudioTrack track, AudioDecoderOptions options) {
        this(List.of(Objects.requireNonNull(track, "track")),
                TwinVqDecoderOptions.builder().limits(Objects.requireNonNull(options, "options").limits()).build());
    }

    /**
     * Constructs a decoder for an object carried in several elementary streams.
     *
     * <p>The first track is the base layer, followed by the scalable layers in order; with {@code epConfig} 1 each
     * layer takes two tracks, its two error sensitivity categories.
     *
     * @param tracks the tracks of the object's elementary streams, in stream order
     * @param options the decoder settings
     * @throws IllegalArgumentException if {@code tracks} is empty or a track is not a TwinVQ track
     * @throws NullPointerException if a track of {@code tracks} is {@code null}
     * @throws MediaException.Malformed if a track has no configuration, a configuration is invalid, or the
     * configurations do not describe the layers of one object
     * @throws MediaException.Unsupported if a configuration is not of object type 7 or 21 with one or two channels,
     * or maps error protection classes without direct mapping
     * @throws MediaException.LimitExceeded if a configuration declares more channels than the limits allow
     */
    public TwinVqDecoder(List<AudioTrack> tracks, TwinVqDecoderOptions options) {
        Objects.requireNonNull(tracks, "tracks");
        this.options = Objects.requireNonNull(options, "options");
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("no track");
        }
        var configs = new ArrayList<byte[]>();
        for (var track : tracks) {
            Objects.requireNonNull(track, "track");
            if (track.codec() != Codec.TWINVQ) {
                throw new IllegalArgumentException(track.codec().displayName() + " is not TwinVQ");
            }
            configs.add(track.codecConfig().orElseThrow(
                    () -> new MediaException.Malformed("TwinVQ track without an AudioSpecificConfig")));
        }
        this.object = new TwinVqObject(configs, options.maxLayer().orElse(-1), options.limits());
        this.layout = object.channels() == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO;
        this.buffer = new AudioBuffer(layout, object.sampleRate(), object.frameLength());
        this.samples = new float[object.channels()][object.frameLength() * object.framesPerUnit()];
        this.units = new byte[object.streams()][];
        this.lengths = new int[object.streams()];
    }

    /**
     * {@return the options the decoder was created with}
     */
    public TwinVqDecoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * @return the sampling frequency of the configuration
     */
    @Override
    public int sampleRate() {
        return object.sampleRate();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link ChannelLayout#MONO} or {@link ChannelLayout#STEREO}
     */
    @Override
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the number of samples per channel one frame decodes to, 1024 or 960}
     */
    public int frameSize() {
        return object.frameLength();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The packet is an access unit of the base layer; an object of several elementary streams decodes its base
     * layer alone, and an object with {@code epConfig} 1, whose base layer needs its two category streams, decodes to
     * silence. An empty packet decodes to the tail of the previous frame followed by silence.
     *
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.Malformed if the access unit is too short for its fields, or the error protection tool
     * rejects it
     */
    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        if (closed) {
            throw new IllegalStateException("the decoder is closed");
        }
        var data = packet.data();
        units[0] = data;
        lengths[0] = data.length;
        emit(1, output);
    }

    /**
     * Decodes the access units of one frame of each elementary stream of the object, or of one EP frame with the error
     * protection tool, which may carry several frames.
     *
     * @param packets the access units, in the order of the tracks the decoder was created with; fewer than the tracks
     * decode the layers they hold up to the first missing one
     * @param output receives one block per frame, valid until the next call
     * @throws IllegalArgumentException if {@code packets} is empty
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.Malformed if an access unit is too short for its fields, or the error protection tool
     * rejects it
     */
    public void decode(List<Packet> packets, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(packets, "packets");
        Objects.requireNonNull(output, "output");
        if (closed) {
            throw new IllegalStateException("the decoder is closed");
        }
        if (packets.isEmpty()) {
            throw new IllegalArgumentException("no packet");
        }
        var count = Math.min(packets.size(), units.length);
        for (var i = 0; i < count; i++) {
            var data = Objects.requireNonNull(packets.get(i), "packet").data();
            units[i] = data;
            lengths[i] = data.length;
        }
        emit(count, output);
    }

    /**
     * Decodes the first access units of {@link #units} and hands out the blocks of their frames.
     *
     * @param count the number of access units
     * @param output receives one block per frame
     */
    private void emit(int count, Consumer<AudioBuffer> output) {
        var frames = object.decode(units, lengths, count, samples);
        var size = object.frameLength();
        for (var frame = 0; frame < frames; frame++) {
            for (var channel = 0; channel < samples.length; channel++) {
                System.arraycopy(samples[channel], frame * size, buffer.plane(channel), 0, size);
            }
            buffer.setFrames(size);
            output.accept(buffer);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The tail of the filterbank overlap is not a sample of the stream, so this emits nothing.
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    @Override
    public void close() {
        closed = true;
    }
}

package com.github.auties00.smalt.audio.celp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.celp.internal.CelpObject;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes MPEG-4 CELP speech, audio object types 8 (CELP) and 24 (ER CELP) of ISO/IEC 14496-3 subpart 3, into mono
 * 8 or 16 kHz samples.
 *
 * <p>Every tool of the standard is decoded: the multi-pulse coder at 8 and 16 kHz and the regular pulse coder at
 * 16 kHz, fine rate control, up to three bitrate scalable enhancement layers, the bandwidth scalable layer that
 * extends 8 kHz speech to 16 kHz, the silence compression of ER CELP with its comfort noise, the error sensitivity
 * category order of ER CELP and the error protection tool ({@code epConfig} 1 to 3). An object carries each layer,
 * and with {@code epConfig} 1 each error sensitivity category of its base layer, in an elementary stream of its own;
 * {@link #decode(List, Consumer)} takes an access unit of each and decodes the layers present up to the first missing
 * one, while {@link #decode(Packet, Consumer)} decodes an object of a single stream.
 *
 * <p>Every frame decodes to one block of 10 to 40 milliseconds, scaled so that 16-bit full scale spans
 * {@code [-1, 1]}; a block can exceed that range. The decoder adds no delay. The post-processor runs unless the options
 * turn it off; without it the output is the decoded signal the standard defines.
 *
 * <p>Instances are not thread-safe.
 */
public final class CelpDecoder implements AudioDecoder {
    /**
     * The object decoder.
     */
    private final CelpObject object;

    /**
     * The options the decoder was created with.
     */
    private final CelpDecoderOptions options;

    /**
     * The buffer handed to the caller, reused for every block.
     */
    private final AudioBuffer buffer;

    /**
     * The samples of the frames of one call.
     */
    private final float[] samples;

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
     * Constructs a decoder for a single-stream object under {@link CelpDecoderOptions#defaults()}.
     *
     * @param track the CELP track
     * @throws IllegalArgumentException if the track is not a CELP track
     * @throws MediaException.Malformed if the track has no configuration, its configuration is invalid, or it is that
     * of an enhancement layer or of a base layer with {@code epConfig} 1, which needs its other category streams
     * @throws MediaException.Unsupported if the configuration is not of object type 8 or 24, or maps error protection
     * classes without direct mapping
     */
    public CelpDecoder(AudioTrack track) {
        this(List.of(Objects.requireNonNull(track, "track")), CelpDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a single-stream object under the codec-independent options, which give the limits.
     *
     * @param track the CELP track
     * @param options the decoder settings; the preferred sample rate is ignored
     * @throws IllegalArgumentException if the track is not a CELP track
     * @throws MediaException.Malformed if the track has no configuration, its configuration is invalid, or it is that
     * of an enhancement layer or of a base layer with {@code epConfig} 1, which needs its other category streams
     * @throws MediaException.Unsupported if the configuration is not of object type 8 or 24, or maps error protection
     * classes without direct mapping
     */
    public CelpDecoder(AudioTrack track, AudioDecoderOptions options) {
        this(List.of(Objects.requireNonNull(track, "track")),
                CelpDecoderOptions.builder().limits(Objects.requireNonNull(options, "options").limits()).build());
    }

    /**
     * Constructs a decoder for an object carried in several elementary streams.
     *
     * <p>The first track is the base layer, followed with {@code epConfig} 1 by the four other error sensitivity
     * categories of the base layer, then by the enhancement layers in any order; tracks of layers the base layer does
     * not announce are ignored.
     *
     * @param tracks the tracks of the object's elementary streams, in stream order
     * @param options the decoder settings
     * @throws IllegalArgumentException if {@code tracks} is empty or a track is not a CELP track
     * @throws NullPointerException if a track of {@code tracks} is {@code null}
     * @throws MediaException.Malformed if a track has no configuration, a configuration is invalid, or the
     * configurations do not describe the layers of one object
     * @throws MediaException.Unsupported if a configuration is not of object type 8 or 24, or maps error protection
     * classes without direct mapping
     */
    public CelpDecoder(List<AudioTrack> tracks, CelpDecoderOptions options) {
        Objects.requireNonNull(tracks, "tracks");
        this.options = Objects.requireNonNull(options, "options");
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("no track");
        }
        var configs = new ArrayList<byte[]>();
        for (var track : tracks) {
            Objects.requireNonNull(track, "track");
            if (track.codec() != Codec.CELP) {
                throw new IllegalArgumentException(track.codec().displayName() + " is not CELP");
            }
            configs.add(track.codecConfig().orElseThrow(
                    () -> new MediaException.Malformed("CELP track without an AudioSpecificConfig")));
        }
        this.object = new CelpObject(configs, options.maxLayer().orElse(-1), options.postFilter(), options.limits());
        this.buffer = new AudioBuffer(ChannelLayout.MONO, object.sampleRate(), object.frameSize());
        this.samples = new float[object.frameSize() * object.framesPerUnit()];
        this.units = new byte[object.streams()][];
        this.lengths = new int[object.streams()];
    }

    /**
     * {@return the options the decoder was created with}
     */
    public CelpDecoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * @return 8000 or 16000 for the standard rates, the base layer's rate doubled when the bandwidth scalable layer
     * is decoded
     */
    @Override
    public int sampleRate() {
        return object.sampleRate();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link ChannelLayout#MONO}
     */
    @Override
    public ChannelLayout layout() {
        return ChannelLayout.MONO;
    }

    /**
     * {@return the number of samples one frame decodes to}
     */
    public int frameSize() {
        return object.frameSize();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The packet is an access unit of the base layer; an object of several elementary streams decodes its base
     * layer alone, and one with {@code epConfig} 1, whose base layer needs its five category streams, decodes to
     * silence. An empty packet decodes to silence.
     *
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.Malformed if the error protection tool rejects the access unit
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
     * @throws MediaException.Malformed if the error protection tool rejects an access unit
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
        var size = object.frameSize();
        var plane = buffer.plane(0);
        for (var f = 0; f < frames; f++) {
            System.arraycopy(samples, f * size, plane, 0, size);
            buffer.setFrames(size);
            output.accept(buffer);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>CELP buffers no samples, so this emits nothing.
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

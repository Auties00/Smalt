package com.github.auties00.smalt.audio.hvxc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.hvxc.internal.HvxcCore;
import com.github.auties00.smalt.audio.hvxc.internal.HvxcObject;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes MPEG-4 HVXC parametric speech, audio object types 9 (HVXC) and 25 (ER HVXC) of ISO/IEC 14496-3 subpart 2,
 * into mono 8 kHz samples.
 *
 * <p>Every tool of the standard is decoded: the fixed rate coder at 2, 3.7 and 4 kbit/s, the variable rate coder at 2
 * and 4 kbit/s with its background noise frames, the enhancement layer that raises a 2 kbit/s object to 4 kbit/s,
 * the error sensitivity category order of ER HVXC with the error protection tool ({@code epConfig} 1 to 3) and its
 * error concealment, and the speed and pitch change of the parameter interpolation. An object carries each layer,
 * and with {@code epConfig} 1 each error sensitivity category of a layer, in an elementary stream of its own;
 * {@link #decode(List, Consumer)} takes an access unit of each and decodes the base layer alone when the
 * enhancement layer is missing, while {@link #decode(Packet, Consumer)} decodes the base layer of a single stream.
 *
 * <p>Every frame decodes to 160 samples divided by the speed change factor on average, scaled so that 16-bit full
 * scale spans {@code [-1, 1]}. The output lags the coded speech by the decoder's delay, {@link #delay()} samples: 10
 * milliseconds, or 7.5 in the low delay mode, which no container records; the conformance references of
 * ISO/IEC 14496-26 start after it. The postfilter and the post-processing run unless the options turn them off;
 * without them the output is the decoded signal the standard defines. The voiced synthesis draws random phases and
 * noise from a generator seeded identically for every decoder, so the output depends only on the stream and the
 * options, and its samples equal those of the ISO/IEC 14496-3 reference decoder.
 *
 * <p>Instances are not thread-safe.
 */
public final class HvxcDecoder implements AudioDecoder {
    /**
     * The sampling rate of HVXC speech.
     */
    private static final int SAMPLE_RATE = 8000;

    /**
     * The object decoder.
     */
    private final HvxcObject object;

    /**
     * The options the decoder was created with.
     */
    private final HvxcDecoderOptions options;

    /**
     * The buffer handed to the caller, reused for every block.
     */
    private final AudioBuffer buffer;

    /**
     * The samples of one call.
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
     * Constructs a decoder for a single-stream object under {@link HvxcDecoderOptions#defaults()}.
     *
     * @param track the HVXC track
     * @throws IllegalArgumentException if the track is not an HVXC track
     * @throws MediaException.Malformed if the track has no configuration, its configuration is invalid, or it is that
     * of an enhancement layer or of a base layer with {@code epConfig} 1, which needs its other category streams
     * @throws MediaException.Unsupported if the configuration is not of object type 9 or 25, or maps error protection
     * classes without direct mapping
     */
    public HvxcDecoder(AudioTrack track) {
        this(List.of(Objects.requireNonNull(track, "track")), HvxcDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a single-stream object under the codec-independent options, which give the limits.
     *
     * @param track the HVXC track
     * @param options the decoder settings; the preferred sample rate is ignored
     * @throws IllegalArgumentException if the track is not an HVXC track
     * @throws MediaException.Malformed if the track has no configuration, its configuration is invalid, or it is that
     * of an enhancement layer or of a base layer with {@code epConfig} 1, which needs its other category streams
     * @throws MediaException.Unsupported if the configuration is not of object type 9 or 25, or maps error protection
     * classes without direct mapping
     */
    public HvxcDecoder(AudioTrack track, AudioDecoderOptions options) {
        this(List.of(Objects.requireNonNull(track, "track")),
                HvxcDecoderOptions.builder().limits(Objects.requireNonNull(options, "options").limits()).build());
    }

    /**
     * Constructs a decoder for an object carried in several elementary streams.
     *
     * <p>The first track is the base layer, followed with {@code epConfig} 1 by the other error sensitivity
     * categories of the base layer, then by the enhancement layer (with {@code epConfig} 1, its categories in order);
     * further tracks are ignored.
     *
     * @param tracks the tracks of the object's elementary streams, in stream order
     * @param options the decoder settings
     * @throws IllegalArgumentException if {@code tracks} is empty or a track is not an HVXC track
     * @throws NullPointerException if a track of {@code tracks} is {@code null}
     * @throws MediaException.Malformed if a track has no configuration, a configuration is invalid, or the
     * configurations do not describe the layers of one object
     * @throws MediaException.Unsupported if a configuration is not of object type 9 or 25, or maps error protection
     * classes without direct mapping
     */
    public HvxcDecoder(List<AudioTrack> tracks, HvxcDecoderOptions options) {
        Objects.requireNonNull(tracks, "tracks");
        this.options = Objects.requireNonNull(options, "options");
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("no track");
        }
        var configs = new ArrayList<byte[]>();
        for (var track : tracks) {
            Objects.requireNonNull(track, "track");
            if (track.codec() != Codec.HVXC) {
                throw new IllegalArgumentException(track.codec().displayName() + " is not HVXC");
            }
            configs.add(track.codecConfig().orElseThrow(
                    () -> new MediaException.Malformed("HVXC track without an AudioSpecificConfig")));
        }
        this.object = new HvxcObject(configs, options.maxLayer().orElse(-1), options.lowDelay(),
                options.postFilter() ? 0 : HvxcCore.TEST_NO_POSTFILTER, options.speedFactor(),
                options.pitchFactor(), options.limits());
        this.samples = new float[object.maxSamples()];
        this.buffer = new AudioBuffer(ChannelLayout.MONO, SAMPLE_RATE, samples.length);
        this.units = new byte[object.streams()][];
        this.lengths = new int[object.streams()];
    }

    /**
     * {@return the options the decoder was created with}
     */
    public HvxcDecoderOptions options() {
        return options;
    }

    /**
     * {@inheritDoc}
     *
     * @return 8000
     */
    @Override
    public int sampleRate() {
        return SAMPLE_RATE;
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
     * {@return the delay of the decoder, in samples at 8 kHz: 80, or 60 in the low delay mode}
     *
     * <p>The first {@code delay()} samples of the output precede the first coded sample; dropping them lines the
     * output up with the speech the encoder coded.
     */
    public int delay() {
        return object.delay();
    }

    /**
     * {@return the number of layers decoded: 1, or 2 with the enhancement layer}
     */
    public int layers() {
        return object.layers();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The packet is an access unit of the base layer; an object of several elementary streams decodes its base
     * layer alone, and one with {@code epConfig} 1, whose base layer needs its category streams, decodes nothing. An
     * empty packet decodes as a frame of zero bits. Nothing is emitted for a frame that the speed change skips.
     *
     * @throws IllegalStateException if the decoder is closed
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
     * @param packets the access units, in the order of the tracks the decoder was created with; without the
     * enhancement layer's units the frame decodes from the base layer alone
     * @param output receives one block with the samples of the frames, valid until the next call; nothing when the
     * speed change skips them
     * @throws IllegalArgumentException if {@code packets} is empty
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.LimitExceeded if an EP frame needs more memory than the limits allow
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
     * Decodes the first access units of {@link #units} and hands out their samples.
     *
     * @param count the number of access units
     * @param output receives the block
     */
    private void emit(int count, Consumer<AudioBuffer> output) {
        var written = object.decode(units, lengths, count, samples);
        if (written == 0) {
            return;
        }
        System.arraycopy(samples, 0, buffer.plane(0), 0, written);
        buffer.setFrames(written);
        output.accept(buffer);
    }

    /**
     * {@inheritDoc}
     *
     * <p>HVXC buffers no samples the caller could still receive, so this emits nothing.
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

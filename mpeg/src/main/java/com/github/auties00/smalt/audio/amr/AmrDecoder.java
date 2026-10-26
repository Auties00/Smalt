package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.amr.internal.AmrFrameDecoder;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.codec.AmrConfigs;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes AMR-NB (8 kHz) and AMR-WB (16 kHz) speech, bit-exactly with the 3GPP fixed-point
 * reference decoders (TS 26.073 and TS 26.173).
 *
 * <p>Each {@link Packet} holds one or more frames in the RFC 4867 storage format, back to back:
 * a header byte ({@code P FT(4) Q P(2)}) followed by the frame's speech bits, as the
 * {@code .amr} demuxer, the MP4 {@code samr}/{@code sawb} sample entries and Matroska
 * {@code A_AMR/NB}/{@code A_AMR/WB} tracks deliver them. Every frame decodes to one block of 20
 * milliseconds: 160 samples for AMR-NB, 320 for AMR-WB, scaled so that full-scale 16-bit
 * output spans {@code [-1, 1]}. The decoder adds no delay of its own and outputs every frame,
 * whatever its type, so the output stays aligned with the packet timestamps.
 *
 * <p>A track of more than one channel, as the multichannel storage format (RFC 4867 section 5.2)
 * delivers it, holds frame-blocks instead of frames: one frame per channel, in channel order, each
 * decoded by its own decoder state into its channel of one block. Every channel of such a track
 * decodes exactly as a single-channel track carrying only that channel's frames would. A packet
 * that ends inside a frame-block conceals the channels it is missing.
 *
 * <p>Frame types are handled as the specifications require:
 * <ul>
 *   <li>speech frames of every mode (AMR-NB frame types 0 to 7, AMR-WB 0 to 8) are decoded,
 *       and the mode may change from one frame to the next;</li>
 *   <li>comfort noise frames (SID, frame type 8 for AMR-NB and 9 for AMR-WB) and the empty
 *       frames (type 15, "no data") between them generate comfort noise during discontinuous
 *       transmission;</li>
 *   <li>a frame whose quality bit {@code Q} is 0, a frame of a packet marked
 *       {@link PacketFlag#CORRUPT}, a frame cut short by the end of its packet, an empty packet,
 *       an AMR-WB "speech lost" frame (type 14) and a no-data frame outside discontinuous
 *       transmission are concealed from the preceding frames (bad frame handling of TS 26.091 and
 *       TS 26.191);</li>
 *   <li>the comfort noise frames of the GSM-EFR, TDMA-EFR and PDC-EFR codecs (AMR-NB types 9
 *       to 11) and the reserved types are treated as no-data frames.</li>
 * </ul>
 * The homing frames of TS 26.073 and TS 26.173 reset the decoder, as the reference decoders do.
 *
 * <p>Instances are not thread-safe.
 */
public final class AmrDecoder implements AudioDecoder {
    /**
     * The frame type of an empty ("no data") frame.
     */
    private static final int NO_DATA = 15;

    /**
     * The payload of a frame that carries no data.
     */
    private static final byte[] NO_PAYLOAD = new byte[0];

    /**
     * The scale from 16-bit samples to the {@code [-1, 1]} range.
     */
    private static final float SCALE = 1f / 32768f;

    /**
     * The options the decoder was created with.
     */
    private final AudioDecoderOptions options;

    /**
     * Whether the track is AMR-WB.
     */
    private final boolean wideband;

    /**
     * The frame decoder of each channel.
     */
    private final AmrFrameDecoder[] frames;

    /**
     * The number of channels, which is the number of frames of a frame-block.
     */
    private final int channels;

    /**
     * The number of samples of one frame.
     */
    private final int frameSamples;

    /**
     * The 16-bit samples of the frame-block being decoded, one array per channel.
     */
    private final short[][] pcm;

    /**
     * The channel layout of the track.
     */
    private final ChannelLayout layout;

    /**
     * The buffer handed to the caller, reused for every frame.
     */
    private final AudioBuffer buffer;

    /**
     * Whether {@link #close()} ran.
     */
    private boolean closed;

    /**
     * Constructs a decoder for a track under {@link AudioDecoderOptions#defaults()}.
     *
     * @param track the AMR-NB or AMR-WB track
     * @throws IllegalArgumentException if the track is not an AMR track
     * @throws MediaException.Unsupported if the track has more than 15 channels
     */
    public AmrDecoder(AudioTrack track) {
        this(track, AudioDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a track.
     *
     * <p>The track needs no codec configuration: the codec fixes the rate, the track layout gives the number of
     * channels, and an MP4 {@code damr} box only lists the modes the stream uses. The decoder allocates nothing whose
     * size depends on the input beyond one decoder state per channel, so no limit of
     * {@link AudioDecoderOptions#limits()} binds it; {@link AudioDecoderOptions#preferredSampleRate()} is ignored,
     * because each codec decodes at one rate only.
     *
     * @param track the AMR-NB or AMR-WB track
     * @param options the decoder settings
     * @throws IllegalArgumentException if the track is not an AMR track
     * @throws MediaException.Unsupported if the track has more than 15 channels, the most a multichannel storage
     * file holds
     */
    public AmrDecoder(AudioTrack track, AudioDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        this.options = Objects.requireNonNull(options, "options");
        this.wideband = switch (track.codec()) {
            case AMR_WB -> true;
            case AMR_NB -> false;
            default -> throw new IllegalArgumentException(track.codec().displayName() + " is not AMR");
        };
        this.layout = track.layout().orElse(ChannelLayout.MONO);
        this.channels = layout.channels();
        if (channels > 15) {
            throw new MediaException.Unsupported("amr", "an AMR track holds at most 15 channels, not " + channels);
        }
        this.frames = new AmrFrameDecoder[channels];
        this.frameSamples = wideband ? 320 : 160;
        this.pcm = new short[channels][frameSamples];
        for (var channel = 0; channel < channels; channel++) {
            frames[channel] = AmrFrameDecoder.create(wideband);
        }
        this.buffer = new AudioBuffer(layout, sampleRate(), frameSamples);
    }

    /**
     * {@return the options the decoder was created with}
     */
    public AudioDecoderOptions options() {
        return options;
    }

    /**
     * {@return the codec of the track, {@link Codec#AMR_NB} or {@link Codec#AMR_WB}}
     */
    public Codec codec() {
        return wideband ? Codec.AMR_WB : Codec.AMR_NB;
    }

    /**
     * {@inheritDoc}
     *
     * @return 8000 for AMR-NB, 16000 for AMR-WB
     */
    @Override
    public int sampleRate() {
        return wideband ? 16_000 : 8_000;
    }

    /**
     * {@inheritDoc}
     *
     * @return the layout of the track, {@link ChannelLayout#MONO} for a single-channel track
     */
    @Override
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the number of samples one frame decodes to, 160 for AMR-NB and 320 for AMR-WB}
     */
    public int frameSize() {
        return frameSamples;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every frame (or frame-block, for a multichannel track) of the packet yields one block of
     * {@link #frameSize()} samples per channel; an empty packet yields one concealed block.
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
        var corrupt = packet.flags().contains(PacketFlag.CORRUPT);
        if (data.length == 0) {
            for (var channel = 0; channel < channels; channel++) {
                decodeFrame(channel, NO_DATA, false, data, 0, 0);
            }
            emit(output);
            return;
        }
        var position = 0;
        while (position < data.length) {
            var truncated = false;
            for (var channel = 0; channel < channels; channel++) {
                if (position >= data.length) {
                    decodeFrame(channel, NO_DATA, false, data, data.length, 0);
                    truncated = true;
                    continue;
                }
                var header = data[position] & 0xFF;
                var type = (header >> 3) & 0x0F;
                var quality = (header & 0x04) != 0 && !corrupt;
                var available = data.length - position - 1;
                var size = AmrConfigs.payloadSize(wideband, type);
                if (available < size) {
                    decodeFrame(channel, type, false, data, position + 1, available);
                    position = data.length;
                    truncated = true;
                    continue;
                }
                decodeFrame(channel, type, quality, data, position + 1, size);
                position += 1 + size;
            }
            emit(output);
            if (truncated) {
                return;
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsConcealment() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The concealment unit is one frame, {@link #frameSize()} samples. Every lost frame is decoded as an empty
     * ("no data") frame of every channel: concealed from the preceding frames by the bad frame handling of TS 26.091
     * and TS 26.191 during speech, and continued as comfort noise during discontinuous transmission.
     *
     * @throws IllegalArgumentException {@inheritDoc}
     * @throws IllegalStateException if the decoder is closed
     */
    @Override
    public int conceal(int samples, Consumer<AudioBuffer> output) {
        Checks.positive("samples", samples);
        Objects.requireNonNull(output, "output");
        if (closed) {
            throw new IllegalStateException("the decoder is closed");
        }
        var frames = Math.min(Math.max(1, (samples + frameSamples / 2) / frameSamples), Integer.MAX_VALUE / frameSamples);
        for (var frame = 0; frame < frames; frame++) {
            for (var channel = 0; channel < channels; channel++) {
                decodeFrame(channel, NO_DATA, false, NO_PAYLOAD, 0, 0);
            }
            emit(output);
        }
        return frames * frameSamples;
    }

    /**
     * Decodes one frame of a channel into its array of {@link #pcm}.
     *
     * @param channel the channel index, which selects the decoder state and the output array
     * @param type the frame type
     * @param quality the quality bit, {@code false} for a damaged frame
     * @param data the packet
     * @param offset the offset of the payload
     * @param length the payload bytes available
     * @throws MediaException.Malformed if the frame decoder fails on the payload
     */
    private void decodeFrame(int channel, int type, boolean quality, byte[] data, int offset, int length) {
        try {
            frames[channel].decode(type, quality, data, offset, length, pcm[channel], 0);
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt AMR frame", exception);
        }
    }

    /**
     * Converts the decoded frame-block to floats and hands it to the caller.
     *
     * @param output receives the block
     */
    private void emit(Consumer<AudioBuffer> output) {
        for (var channel = 0; channel < channels; channel++) {
            var plane = buffer.plane(channel);
            var samples = pcm[channel];
            for (var i = 0; i < frameSamples; i++) {
                plane[i] = samples[i] * SCALE;
            }
        }
        buffer.setFrames(frameSamples);
        output.accept(buffer);
    }

    /**
     * {@inheritDoc}
     *
     * <p>AMR buffers no samples, so this emits nothing.
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * Resets the decoder to its initial state, as at a homing frame, for example after a seek.
     *
     * @throws IllegalStateException if the decoder is closed
     */
    public void reset() {
        if (closed) {
            throw new IllegalStateException("the decoder is closed");
        }
        for (var decoder : frames) {
            decoder.reset();
        }
    }

    /**
     * Closes the decoder; later calls to {@link #decode(Packet, Consumer)} and {@link #reset()} throw
     * {@link IllegalStateException}. Closing twice has no further effect.
     */
    @Override
    public void close() {
        closed = true;
    }
}

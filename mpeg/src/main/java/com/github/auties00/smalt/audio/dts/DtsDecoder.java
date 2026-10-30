package com.github.auties00.smalt.audio.dts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.dts.internal.CoreDecoder;
import com.github.auties00.smalt.audio.dts.internal.DtsFraming;
import com.github.auties00.smalt.audio.dts.internal.ExtensionSubstream;
import com.github.auties00.smalt.audio.dts.internal.Speakers;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes DTS Coherent Acoustics audio and its DTS-HD extensions, as ETSI TS 102 114 specifies them, to planar
 * float PCM.
 *
 * <p>Each {@link Packet} holds one access unit: a core frame, a core frame followed by an extension substream
 * frame, or an extension substream frame alone, as MP4, Matroska, MPEG-TS and the raw {@code .dts} and
 * {@code .dtshd} demuxers deliver them; several access units back to back decode one after the other. The core
 * sync word may come in any of its four packings (16-bit or 14-bit words, either byte order), as DTS audio CDs
 * store it in 16-bit PCM.
 *
 * <p>The decoder implements the core (5.1 channels up to 48 kHz) and its extensions: XCh (6.1), XXCH (up to 32
 * extra channels), X96 (96 kHz), XBR (higher bit rate) and XLL (lossless, DTS-HD Master Audio, up to 96 kHz). A
 * stream without core is output at the lossless resolution, bit for bit. The XLL extension of a stream with a core
 * is not decoded yet: such a stream outputs its core with the other extensions. When the extension substream of a
 * frame with a core needs what the decoder does not implement, the core is output; a stream without core that
 * needs it (XLL frequency bands above 96 kHz), DTS Express (LBR) and termination frames with a partial
 * subsubframe raise {@link MediaException.Unsupported}.
 * {@link #layout()} and {@link #sampleRate()} report the track's declared format until the first access unit is
 * decoded and that unit's output format afterwards; every buffer carries its own layout, its channels in the order
 * of {@link Speaker}. Samples are scaled so that 24-bit full scale maps to 1.0.
 *
 * <p>Instances are not thread-safe.
 */
public final class DtsDecoder implements AudioDecoder {
    /**
     * The options the decoder was created with.
     */
    private final DtsDecoderOptions options;

    /**
     * The decoder of the core frame.
     */
    private final CoreDecoder core = new CoreDecoder();

    /**
     * The decoder of the extension substream.
     */
    private final ExtensionSubstream substream = new ExtensionSubstream();

    /**
     * The output layouts by speaker masks, built on demand.
     */
    private final Map<Long, Mapping> mappings = new HashMap<>();

    /**
     * The access unit converted from the 14-bit or little-endian packings.
     */
    private byte[] plain = new byte[0];

    /**
     * The sample rate of the last decoded unit.
     */
    private int sampleRate;

    /**
     * The layout of the last decoded unit.
     */
    private ChannelLayout layout;

    /**
     * Whether the decoder is closed.
     */
    private boolean closed;

    /**
     * The speaker bit of each channel of the last core output, reused while the channel count holds.
     */
    private int[] coreMasks = new int[0];

    /**
     * Constructs a decoder for a track under {@link DtsDecoderOptions#defaults()}.
     *
     * @param track the DTS track
     * @throws IllegalArgumentException if the track is not a DTS track
     */
    public DtsDecoder(AudioTrack track) {
        this(track, DtsDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a track under explicit options.
     *
     * <p>The track needs no codec configuration: every frame describes itself.
     *
     * @param track the DTS track
     * @param options the extension and resource settings
     * @throws IllegalArgumentException if the track is not a DTS track
     */
    public DtsDecoder(AudioTrack track, DtsDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        this.options = Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.DTS) {
            throw new IllegalArgumentException(track.codec().displayName() + " is not DTS");
        }
        this.sampleRate = track.sampleRate() > 0 ? track.sampleRate() : 48000;
        this.layout = track.layout().orElse(ChannelLayout.SURROUND_5_1);
    }

    /**
     * {@return the options the decoder was created with}
     */
    public DtsDecoderOptions options() {
        return options;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each access unit of the packet yields one buffer. Bytes that start no frame are skipped up to the next
     * sync word, and a frame cut short by the end of the packet is dropped. An exception thrown by {@code output}
     * propagates unchanged.
     *
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.Malformed if a frame breaks the syntax
     * @throws MediaException.Unsupported if a frame uses a coding this decoder does not implement
     */
    @Override
    public void decode(Packet packet, Consumer<AudioBuffer> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        if (closed) {
            throw new IllegalStateException("the decoder is closed");
        }
        var data = packet.data();
        ConsumerFailure.<AudioBuffer>run("DTS packet", output, emit -> decodeUnits(data, emit));
    }

    /**
     * Splits a packet into access units and decodes them.
     */
    private void decodeUnits(byte[] data, Consumer<AudioBuffer> output) {
        var position = 0;
        while (position + 6 <= data.length) {
            var packing = DtsFraming.packing(data, position, data.length - position);
            if (packing >= 0) {
                position = decodeCoreUnit(data, position, packing, output);
                continue;
            }
            if (DtsFraming.readInt(data, position) == DtsFraming.EXSS_SYNC) {
                var size = substream.frameSize(data, position, data.length - position);
                if (size > 0 && size <= data.length - position) {
                    decodeSubstreamOnly(data, position, size, output);
                    position += size;
                    continue;
                }
                if (size > 0) {
                    return;
                }
            }
            position++;
        }
    }

    /**
     * Decodes an access unit that starts with a core frame.
     *
     * @return the position after the unit
     */
    private int decodeCoreUnit(byte[] data, int position, int packing, Consumer<AudioBuffer> output) {
        var available = data.length - position;
        var plainLength = packing == DtsFraming.BIG_ENDIAN_16 ? available
                : DtsFraming.unpack(data, position, available & ~1, packing, plainBuffer(available));
        if (plainLength < 16) {
            return data.length;
        }
        var source = packing == DtsFraming.BIG_ENDIAN_16 ? data : plain;
        var offset = packing == DtsFraming.BIG_ENDIAN_16 ? position : 0;
        var frameSize = ((source[offset + 5] & 0x03) << 12 | (source[offset + 6] & 0xFF) << 4
                | (source[offset + 7] & 0xF0) >> 4) + 1;
        if (frameSize > plainLength) {
            return data.length;
        }
        core.decode(source, offset, frameSize, options.extensions());
        var consumed = DtsFraming.packedLength(frameSize, packing);
        var next = position + consumed;
        var substreamDecoded = false;
        if (packing == DtsFraming.BIG_ENDIAN_16 && options.extensions()) {
            if (next + 4 <= data.length && DtsFraming.readInt(data, next) == DtsFraming.EXSS_SYNC) {
                var size = substream.frameSize(data, next, data.length - next);
                if (size > 0 && size <= data.length - next) {
                    try {
                        substream.decode(data, next, size, core);
                        substreamDecoded = true;
                    } catch (MediaException.Unsupported unsupported) {
                        // the core alone is output when the extension substream needs what is not implemented
                    }
                    next += size;
                }
            }
        }
        if (substreamDecoded && substream.ownOutput()) {
            emitSubstream(output);
        } else {
            emit(output);
        }
        return next;
    }

    /**
     * Decodes an access unit made of an extension substream frame alone.
     */
    private void decodeSubstreamOnly(byte[] data, int position, int size, Consumer<AudioBuffer> output) {
        if (!options.extensions()) {
            return;
        }
        if (substream.decodeAlone(data, position, size)) {
            emitSubstream(output);
        }
    }

    /**
     * Synthesizes the decoded core frame and passes it on.
     */
    private void emit(Consumer<AudioBuffer> output) {
        var samples = core.outputSamples();
        var channels = core.channels() + (core.hasLfe() ? 1 : 0);
        LimitChecks.checkChannels(options.limits(), channels);
        core.synthesize();
        if (coreMasks.length != channels) {
            coreMasks = new int[channels];
        }
        var masks = coreMasks;
        for (var ch = 0; ch < channels; ch++) {
            masks[ch] = core.mask(ch);
        }
        var mapping = mapping(masks);
        sampleRate = core.outputRate();
        var buffer = mapping.buffer(sampleRate, samples);
        var scale = gain(core.header().dialogueNormalization);
        for (var ch = 0; ch < channels; ch++) {
            var target = buffer.plane(mapping.order[ch]);
            var source = core.output(ch);
            for (var i = 0; i < samples; i++) {
                target[i] = source[i] * scale;
            }
        }
        buffer.setFrames(samples);
        layout = mapping.layout;
        output.accept(buffer);
    }

    /**
     * Passes on the output of an extension substream decoded without core.
     */
    private void emitSubstream(Consumer<AudioBuffer> output) {
        var samples = substream.outputSamples();
        var masks = substream.masks();
        var channels = masks.length;
        LimitChecks.checkChannels(options.limits(), channels);
        var mapping = mapping(masks);
        sampleRate = substream.outputRate();
        var buffer = mapping.buffer(sampleRate, samples);
        var scale = gain(substream.dialogueNormalization());
        for (var ch = 0; ch < channels; ch++) {
            var target = buffer.plane(mapping.order[ch]);
            var source = substream.output(ch);
            if (scale == 1.0f) {
                System.arraycopy(source, 0, target, 0, samples);
            } else {
                for (var i = 0; i < samples; i++) {
                    target[i] = source[i] * scale;
                }
            }
        }
        buffer.setFrames(samples);
        layout = mapping.layout;
        output.accept(buffer);
    }

    /**
     * {@return the linear gain of a dialogue normalization gain in dB, or 1 when the options do not apply it}
     *
     * @param decibels the gain the stream signals, from -31 to 0
     */
    private float gain(int decibels) {
        return options.dialogueNormalization() && decibels != 0 ? (float) StrictMath.pow(10, decibels / 20.0) : 1.0f;
    }

    /**
     * {@return the buffer of the converted access unit, grown to a length}
     *
     * @param length the minimum length in bytes
     */
    private byte[] plainBuffer(int length) {
        if (plain.length < length) {
            plain = new byte[length];
        }
        return plain;
    }

    /**
     * Returns the output layout and channel order of a list of DTS speaker bits.
     */
    private Mapping mapping(int[] masks) {
        var key = 0L;
        for (var mask : masks) {
            key = key * 31 + mask;
        }
        key = key * 31 + masks.length;
        var cached = mappings.get(key);
        if (cached != null && Arrays.equals(cached.masks, masks)) {
            return cached;
        }
        var all = 0;
        for (var mask : masks) {
            all |= mask;
        }
        var sideTaken = (all & (Speakers.LS | Speakers.RS)) != 0;
        var speakers = new Speaker[masks.length];
        for (var ch = 0; ch < masks.length; ch++) {
            speakers[ch] = Speakers.speaker(masks[ch], sideTaken);
        }
        var sorted = new ArrayList<Integer>();
        for (var ch = 0; ch < masks.length; ch++) {
            sorted.add(ch);
        }
        sorted.sort((a, b) -> Integer.compare(speakers[a].ordinal(), speakers[b].ordinal()));
        var order = new int[masks.length];
        List<Speaker> list = new ArrayList<>();
        for (var i = 0; i < sorted.size(); i++) {
            order[sorted.get(i)] = i;
            list.add(speakers[sorted.get(i)]);
        }
        var mapping = new Mapping(masks.clone(), new ChannelLayout(list), order);
        mappings.put(key, mapping);
        return mapping;
    }

    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * Clears the decoder state, as at the start of a stream, for example after a seek.
     *
     * @throws IllegalStateException if the decoder is closed
     */
    public void reset() {
        if (closed) {
            throw new IllegalStateException("the decoder is closed");
        }
        core.reset();
        substream.reset();
    }

    @Override
    public void close() {
        closed = true;
    }

    /**
     * The output layout, channel order and output buffer of a list of DTS speaker bits.
     */
    private static final class Mapping {
        /**
         * The speaker bit of each decoded channel.
         */
        private final int[] masks;

        /**
         * The output layout.
         */
        private final ChannelLayout layout;

        /**
         * The output channel of each decoded channel.
         */
        private final int[] order;

        /**
         * The output buffer, or {@code null} before the first frame.
         */
        private AudioBuffer buffer;

        /**
         * Constructs a mapping.
         *
         * @param masks the speaker bit of each decoded channel
         * @param layout the output layout
         * @param order the output channel of each decoded channel
         */
        private Mapping(int[] masks, ChannelLayout layout, int[] order) {
            this.masks = masks;
            this.layout = layout;
            this.order = order;
        }

        /**
         * {@return the output buffer of a frame, reused while the rate holds and the frame fits}
         *
         * @param sampleRate the output sample rate
         * @param samples the samples per channel of the frame
         */
        private AudioBuffer buffer(int sampleRate, int samples) {
            if (buffer == null || buffer.sampleRate() != sampleRate || buffer.capacity() < samples) {
                buffer = new AudioBuffer(layout, sampleRate, samples);
            }
            return buffer;
        }
    }
}

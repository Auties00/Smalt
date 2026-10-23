package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.ac3.internal.Ac3Kernel;
import com.github.auties00.smalt.audio.ac3.internal.Ac3Kernels;
import com.github.auties00.smalt.audio.ac3.internal.ChannelMap;
import com.github.auties00.smalt.audio.ac3.internal.Downmix;
import com.github.auties00.smalt.audio.ac3.internal.FrameDecoder;
import com.github.auties00.smalt.audio.ac3.internal.FrameHeader;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Decodes Dolby Digital (AC-3) and Dolby Digital Plus (E-AC-3) audio, as ATSC A/52 and its Annex E
 * specify it, to planar float PCM.
 *
 * <p>Each {@link Packet} holds one or more sync frames back to back: one AC-3 sync frame, or an
 * E-AC-3 access unit (an independent frame followed by its dependent substream frames and possibly
 * by the frames of other programs), as MP4, Matroska, MPEG-TS and the raw {@code .ac3} and
 * {@code .ec3} demuxers deliver them. Every frame decodes to 256 samples per audio block: 1536 for
 * AC-3 and six-block E-AC-3 frames. Bit streams with {@code bsid} up to 8 (and the half- and
 * quarter-rate variants 9 and 10) decode as AC-3, 11 to 16 as E-AC-3.
 *
 * <p>The decoder implements the whole AC-3 syntax (exponent strategies, parametric and delta bit
 * allocation, grouped mantissas with dither, coupling, rematrixing, block switching) and the
 * E-AC-3 tools: up to six blocks per frame, frame-based exponent strategies, the adaptive hybrid
 * transform with vector and gain-adaptive quantization, spectral extension, enhanced coupling, and
 * channel extensions through dependent substreams (7.1 and beyond); object audio (Atmos joint
 * object coding) is out of scope, and such streams decode to their 5.1 or 7.1 channel bed.
 *
 * <p>The output follows {@link Ac3DecoderOptions}: every coded channel in WAVE order or a
 * downmix, light or heavy dynamic range compression, and optional dialogue normalization. Frames
 * that fail their CRC, are flagged {@link PacketFlag#CORRUPT}, or break the syntax are concealed by
 * repeating the last good spectrum with a fade, so the output keeps its timing. {@link #layout()}
 * and {@link #sampleRate()} report the track's declared format until the first frame is decoded,
 * and that frame's output format afterwards; every buffer carries its own layout. Every access
 * unit is emitted by the {@link #decode(Packet, Consumer)} call that delivers it.
 *
 * <p>Instances are not thread-safe.
 */
public final class Ac3Decoder implements AudioDecoder {
    /**
     * The options the decoder was created with.
     */
    private final Ac3DecoderOptions options;

    /**
     * The codec of the track.
     */
    private final Codec codec;

    /**
     * The DSP kernels.
     */
    private final Ac3Kernel kernel = Ac3Kernels.INSTANCE;

    /**
     * The decoder of the independent substream (or the AC-3 stream).
     */
    private final FrameDecoder independent;

    /**
     * The decoders of the dependent substreams, by substream identifier, created on demand.
     */
    private final FrameDecoder[] dependents = new FrameDecoder[8];

    /**
     * The dependent substream frames of the current access unit, in stream order.
     */
    private final List<int[]> pendingDependents = new ArrayList<>();

    /**
     * The header used to scan frames before decoding them.
     */
    private final FrameHeader scan = new FrameHeader();

    /**
     * The output buffers, by layout.
     */
    private final Map<ChannelLayout, AudioBuffer> buffers = new HashMap<>();

    /**
     * The per-channel PCM of one substream before downmixing, by coded channel.
     */
    private final float[][] scratch = new float[6][1536];

    /**
     * The gain of each block and program channel.
     */
    private final float[][] gains = new float[6][2];

    /**
     * The downmix coefficients, by output and coded channel.
     */
    private final float[][] mix = new float[2][6];

    /**
     * The sampling rate reported by {@link #sampleRate()}.
     */
    private int sampleRate;

    /**
     * The layout reported by {@link #layout()}.
     */
    private ChannelLayout layout;

    /**
     * Whether a frame was decoded, which fixes {@link #sampleRate} and {@link #layout}.
     */
    private boolean started;

    /**
     * The dialogue level of the last decoded frame in dBFS, or {@code 0} before any.
     */
    private int dialogueLevel;

    /**
     * Whether the last decoded frame signals object audio.
     */
    private boolean objectAudio;

    /**
     * The bytes of the access unit being assembled: the packet data.
     */
    private byte[] unitData;

    /**
     * The offset of the independent frame of the access unit being assembled, or {@code -1}.
     */
    private int unitOffset = -1;

    /**
     * The length of the independent frame of the access unit being assembled.
     */
    private int unitLength;

    /**
     * The audio coding mode and LFE flag ({@code acmod * 2 + lfeon}) the cached native layout of
     * a program without channel extensions was built for, or {@code -1}.
     */
    private int cachedMode = -1;

    /**
     * The cached native layout of a program without channel extensions.
     */
    private ChannelLayout cachedLayout;

    /**
     * The output channel of each coded channel in the cached native layout.
     */
    private int[] cachedTargets;

    /**
     * The output planes of each coded channel of the independent substream, reused.
     */
    private final float[][] independentTargets = new float[6][];

    /**
     * Whether the access unit being assembled is flagged corrupt.
     */
    private boolean unitCorrupt;

    /**
     * Whether {@link #close()} ran.
     */
    private boolean closed;

    /**
     * Constructs a decoder for a track under {@link Ac3DecoderOptions#defaults()}.
     *
     * @param track the AC-3 or E-AC-3 track
     * @throws IllegalArgumentException if the track is not an AC-3 or E-AC-3 track
     */
    public Ac3Decoder(AudioTrack track) {
        this(track, Ac3DecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a track under explicit options.
     *
     * <p>The track needs no codec configuration: every frame describes itself.
     *
     * @param track the AC-3 or E-AC-3 track
     * @param options the output format, dynamic range and robustness settings
     * @throws IllegalArgumentException if the track is not an AC-3 or E-AC-3 track
     */
    public Ac3Decoder(AudioTrack track, Ac3DecoderOptions options) {
        Objects.requireNonNull(track, "track");
        this.options = Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.AC3 && track.codec() != Codec.EAC3) {
            throw new IllegalArgumentException(track.codec().displayName() + " is not AC-3 or E-AC-3");
        }
        this.codec = track.codec();
        this.independent = new FrameDecoder(kernel);
        this.sampleRate = track.sampleRate() > 0 ? track.sampleRate() : 48000;
        this.layout = switch (options.downmix()) {
            case MONO -> ChannelLayout.MONO;
            case STEREO, MATRIX_STEREO, PREFERRED_STEREO -> ChannelLayout.STEREO;
            case NONE -> track.layout().orElse(ChannelLayout.STEREO);
        };
    }

    /**
     * {@return the options the decoder was created with}
     */
    public Ac3DecoderOptions options() {
        return options;
    }

    /**
     * {@return the codec of the track, {@link Codec#AC3} or {@link Codec#EAC3}}
     */
    public Codec codec() {
        return codec;
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
     * {@return the dialogue level ({@code dialnorm}) of the last decoded frame in dBFS, from -31 to
     * -1, or empty before the first frame}
     */
    public OptionalInt dialogueLevel() {
        return started ? OptionalInt.of(dialogueLevel) : OptionalInt.empty();
    }

    /**
     * {@return whether the last decoded frame signals E-AC-3 object audio}
     *
     * <p>Object audio is the extension of ETSI TS 103 420 that carries Dolby Atmos joint object
     * coding; the decoder outputs the channel bed of such a stream. This is {@code false} before
     * the first frame.
     */
    public boolean objectAudio() {
        return objectAudio;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each AC-3 frame or E-AC-3 access unit of the packet yields one buffer of 256 samples per
     * audio block. Bytes that do not start a valid sync frame are skipped up to the next sync word,
     * and so is a frame of the reserved E-AC-3 stream type 3; a frame cut short by the end of the
     * packet is dropped. An exception thrown by {@code output} propagates unchanged.
     *
     * @throws IllegalStateException if the decoder is closed
     * @throws MediaException.Malformed if the packet is damaged beyond what concealment absorbs
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
        ConsumerFailure.<AudioBuffer>run("AC-3 packet", output, emit -> decodeFrames(data, corrupt, emit));
    }

    /**
     * Splits a packet into sync frames and decodes them.
     *
     * @param data the packet data
     * @param corrupt whether the packet is flagged corrupt
     * @param output receives the decoded buffers
     */
    private void decodeFrames(byte[] data, boolean corrupt, Consumer<AudioBuffer> output) {
        var position = 0;
        var selected = false;
        unitData = data;
        unitOffset = -1;
        unitCorrupt = corrupt;
        pendingDependents.clear();
        while (position + 6 <= data.length) {
            if (data[position] != 0x0B || data[position + 1] != 0x77) {
                position++;
                continue;
            }
            var length = Ac3Configs.frameLength(data, position, data.length - position);
            if (length < 0) {
                position++;
                continue;
            }
            if (length > data.length - position) {
                break;
            }
            var bsid = (data[position + 5] & 0xFF) >> 3;
            if (bsid <= 10) {
                finishUnit(output);
                selected = options.program() == 0;
                if (selected) {
                    unitOffset = position;
                    unitLength = length;
                }
            } else {
                var streamType = (data[position + 2] & 0xFF) >> 6;
                var substream = (data[position + 2] >> 3) & 0x07;
                if (streamType != 1) {
                    finishUnit(output);
                    selected = substream == options.program();
                    if (selected) {
                        unitOffset = position;
                        unitLength = length;
                    }
                } else if (selected && unitOffset >= 0) {
                    pendingDependents.add(new int[]{position, length, substream});
                }
            }
            position += length;
        }
        finishUnit(output);
    }

    /**
     * Decodes the access unit assembled so far, if any, and clears it.
     *
     * @param output receives the decoded buffer
     */
    private void finishUnit(Consumer<AudioBuffer> output) {
        var offset = unitOffset;
        if (offset < 0) {
            pendingDependents.clear();
            return;
        }
        unitOffset = -1;
        var checkCrc = options.crcCheck();
        if (!independent.parse(unitData, offset, unitLength, unitCorrupt, checkCrc)) {
            pendingDependents.clear();
            return;
        }
        var header = independent.header();
        var extensions = options.downmix() == Ac3Downmix.NONE && options.channelExtensions();
        var dependentCount = 0;
        FrameDecoder lastDependent = null;
        if (extensions) {
            for (var frame : pendingDependents) {
                var decoder = dependents[frame[2]];
                if (decoder == null) {
                    decoder = new FrameDecoder(kernel);
                    dependents[frame[2]] = decoder;
                }
                if (!decoder.parse(unitData, frame[0], frame[1], unitCorrupt, checkCrc)
                        || decoder.header().blocks != header.blocks) {
                    frame[2] = -1;
                }
            }
            for (var frame : pendingDependents) {
                if (frame[2] >= 0) {
                    var decoder = dependents[frame[2]];
                    decoder.reconstruct();
                    lastDependent = decoder;
                    dependentCount++;
                }
            }
        }
        independent.reconstruct();
        var blocks = header.blocks;
        var samples = 256 * blocks;
        computeGains(header, lastDependent);
        var scale = options.dialogueNormalization()
                ? (float) StrictMath.pow(10, (normalizedDialnorm(header.dialogueNormalization[0]) - 31) / 20.0) : 1.0f;
        AudioBuffer buffer;
        if (options.downmix() == Ac3Downmix.NONE) {
            buffer = nativeOutput(header, samples, scale, dependentCount > 0);
        } else {
            buffer = downmixOutput(header, samples, scale);
        }
        sampleRate = header.sampleRate;
        layout = buffer.layout();
        started = true;
        dialogueLevel = -normalizedDialnorm(header.dialogueNormalization[0]);
        var objects = header.objectAudio;
        for (var frame : pendingDependents) {
            var reader = new MsbBitReader(unitData, frame[0], frame[1]);
            if (scan.parse(reader, frame[1])) {
                objects |= scan.objectAudio;
            }
        }
        objectAudio = objects;
        pendingDependents.clear();
        output.accept(buffer);
    }

    /**
     * Maps the reserved dialogue normalization value 0 to 31 (A/52 section 5.4.2.8).
     *
     * @param dialnorm the coded value
     * @return the value from 1 to 31
     */
    private static int normalizedDialnorm(int dialnorm) {
        return dialnorm == 0 ? 31 : dialnorm;
    }

    /**
     * Computes the gain of every block from the dynamic range or compression words.
     *
     * @param header the independent frame header
     * @param lastDependent the last decoded dependent substream, whose words then apply to the
     * whole program (A/52 Annex E section 3.8.5), or {@code null}
     */
    private void computeGains(FrameHeader header, FrameDecoder lastDependent) {
        var source = lastDependent != null ? lastDependent : independent;
        var sourceHeader = source.header();
        for (var b = 0; b < header.blocks; b++) {
            for (var program = 0; program < 2; program++) {
                var gain = 1.0f;
                switch (options.compression()) {
                    case LIGHT -> gain = dynamicRangeGain(source.valid() ? source.dynamicRange(b, program) : 0);
                    case HEAVY -> {
                        var word = sourceHeader.compression[Math.min(program, sourceHeader.acmod == 0 ? 1 : 0)];
                        gain = word >= 0 ? compressionGain(word)
                                : dynamicRangeGain(source.valid() ? source.dynamicRange(b, program) : 0);
                    }
                    default -> {
                    }
                }
                gains[b][program] = gain;
            }
        }
    }

    /**
     * Returns the gain of a dynamic range word (A/52 section 7.7.1.2), scaled by the cut or boost
     * factor.
     *
     * @param word the signed 8-bit word
     * @return the linear gain
     */
    private float dynamicRangeGain(int word) {
        var exponent = word >> 5;
        var mantissa = word & 0x1F;
        var gain = Math.scalb((32 + mantissa) / 64.0, exponent + 1);
        var scale = gain > 1 ? options.boostScale() : options.cutScale();
        return (float) (scale == 1 ? gain : StrictMath.pow(gain, scale));
    }

    /**
     * Returns the gain of a heavy compression word (A/52 section 7.7.2.2).
     *
     * @param word the unsigned 8-bit word
     * @return the linear gain
     */
    private static float compressionGain(int word) {
        var exponent = ((byte) word) >> 4;
        var mantissa = word & 0x0F;
        return (float) Math.scalb((16 + mantissa) / 32.0, exponent + 1);
    }

    /**
     * Synthesizes every coded channel of the program in WAVE order.
     *
     * @param header the independent frame header
     * @param samples the samples per channel
     * @param scale the gain applied to every channel
     * @param extensions whether dependent substreams were decoded
     * @return the buffer
     */
    private AudioBuffer nativeOutput(FrameHeader header, int samples, float scale, boolean extensions) {
        if (!extensions) {
            return plainOutput(header, samples, scale);
        }
        var speakers = ChannelMap.speakers(header);
        var ordered = new ArrayList<Speaker>(Arrays.asList(speakers));
        for (var frame : pendingDependents) {
            if (frame[2] < 0) {
                continue;
            }
            for (var speaker : ChannelMap.speakers(dependents[frame[2]].header())) {
                if (speaker == Speaker.UNKNOWN || !ordered.contains(speaker)) {
                    ordered.add(speaker);
                }
            }
        }
        ordered.sort(null);
        var outputLayout = new ChannelLayout(ordered);
        var buffer = buffer(outputLayout, header.sampleRate, samples);
        var planes = buffer.planes();
        var targets = new float[speakers.length][];
        var used = new boolean[ordered.size()];
        for (var ch = 0; ch < speakers.length; ch++) {
            targets[ch] = planes[claim(ordered, used, speakers[ch])];
        }
        independent.synthesize(gains, scale, targets, 0);
        for (var frame : pendingDependents) {
            if (frame[2] < 0) {
                continue;
            }
            var decoder = dependents[frame[2]];
            var dependentSpeakers = ChannelMap.speakers(decoder.header());
            var dependentTargets = new float[dependentSpeakers.length][];
            for (var ch = 0; ch < dependentSpeakers.length; ch++) {
                var index = ordered.indexOf(dependentSpeakers[ch]);
                if (dependentSpeakers[ch] == Speaker.UNKNOWN || index < 0) {
                    index = claim(ordered, used, dependentSpeakers[ch]);
                }
                dependentTargets[ch] = planes[index];
            }
            decoder.synthesize(gains, scale, dependentTargets, 0);
        }
        buffer.setFrames(samples);
        return buffer;
    }

    /**
     * Synthesizes the coded channels of a program without channel extensions in WAVE order,
     * reusing the layout built for the previous frame of the same configuration.
     *
     * @param header the independent frame header
     * @param samples the samples per channel
     * @param scale the gain applied to every channel
     * @return the buffer
     */
    private AudioBuffer plainOutput(FrameHeader header, int samples, float scale) {
        var mode = header.acmod * 2 + (header.lfe ? 1 : 0);
        if (mode != cachedMode) {
            var speakers = ChannelMap.speakers(header);
            var ordered = new ArrayList<Speaker>(Arrays.asList(speakers));
            ordered.sort(null);
            var used = new boolean[ordered.size()];
            var targets = new int[speakers.length];
            for (var ch = 0; ch < speakers.length; ch++) {
                targets[ch] = claim(ordered, used, speakers[ch]);
            }
            cachedLayout = new ChannelLayout(ordered);
            cachedTargets = targets;
            cachedMode = mode;
        }
        var buffer = buffer(cachedLayout, header.sampleRate, samples);
        var planes = buffer.planes();
        var targets = cachedTargets;
        for (var ch = 0; ch < targets.length; ch++) {
            independentTargets[ch] = planes[targets[ch]];
        }
        independent.synthesize(gains, scale, independentTargets, 0);
        buffer.setFrames(samples);
        return buffer;
    }

    /**
     * Claims the first unclaimed channel of the output layout that feeds a speaker, or the first
     * channel feeding it when every one is claimed.
     *
     * @param ordered the output speakers
     * @param used which output channels are claimed
     * @param speaker the speaker the coded channel feeds
     * @return the output channel
     */
    private static int claim(List<Speaker> ordered, boolean[] used, Speaker speaker) {
        var fallback = -1;
        for (var i = 0; i < ordered.size(); i++) {
            if (ordered.get(i) == speaker) {
                if (!used[i]) {
                    used[i] = true;
                    return i;
                }
                if (fallback < 0) {
                    fallback = i;
                }
            }
        }
        return Math.max(fallback, 0);
    }

    /**
     * Synthesizes the independent substream and downmixes it.
     *
     * @param header the independent frame header
     * @param samples the samples per channel
     * @param scale the gain applied to every channel
     * @return the buffer
     */
    private AudioBuffer downmixOutput(FrameHeader header, int samples, float scale) {
        var coded = header.channels + (header.lfe ? 1 : 0);
        independent.synthesize(gains, scale, scratch, 0);
        var mode = switch (options.downmix()) {
            case MONO -> Downmix.Mode.MONO;
            case STEREO -> Downmix.Mode.STEREO;
            case MATRIX_STEREO -> Downmix.Mode.MATRIX;
            case PREFERRED_STEREO -> header.downmixMode == 1 ? Downmix.Mode.MATRIX : Downmix.Mode.STEREO;
            case NONE -> throw new AssertionError("no downmix requested");
        };
        var outputs = Downmix.coefficients(header, mode, mix);
        var outputLayout = outputs == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO;
        var buffer = buffer(outputLayout, header.sampleRate, samples);
        buffer.setFrames(samples);
        mix(outputs, coded, buffer);
        return buffer;
    }

    /**
     * Mixes the synthesized coded channels in {@link #scratch} into the output channels of a
     * buffer with the coefficients in {@link #mix}.
     *
     * @param outputs the number of output channels
     * @param coded the number of coded channels
     * @param buffer the buffer, whose frame count is set
     */
    private void mix(int outputs, int coded, AudioBuffer buffer) {
        var samples = buffer.frames();
        for (var out = 0; out < outputs; out++) {
            var plane = buffer.plane(out);
            kernel.scale(scratch[0], 0, mix[out][0], plane, 0, samples);
            for (var ch = 1; ch < coded; ch++) {
                if (mix[out][ch] != 0) {
                    kernel.accumulate(scratch[ch], 0, mix[out][ch], plane, 0, samples);
                }
            }
        }
    }

    /**
     * Returns the output buffer of a layout, creating it on first use.
     *
     * @param layout the channel layout of the buffer
     * @param sampleRate the sampling rate
     * @param samples the samples per channel needed
     * @return the buffer
     */
    private AudioBuffer buffer(ChannelLayout layout, int sampleRate, int samples) {
        var buffer = buffers.get(layout);
        if (buffer == null || buffer.sampleRate() != sampleRate || buffer.capacity() < samples) {
            LimitChecks.checkChannels(options.limits(), layout.channels());
            buffer = new AudioBuffer(layout, sampleRate, 1536);
            buffers.put(layout, buffer);
        }
        return buffer;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits nothing: every access unit was emitted by the call that decoded it.
     */
    @Override
    public void flush(Consumer<AudioBuffer> output) {
        Objects.requireNonNull(output, "output");
    }

    /**
     * Resets the decoder to its initial state, for example after a seek: the overlap and
     * concealment state of every substream is cleared.
     *
     * @throws IllegalStateException if the decoder is closed
     */
    public void reset() {
        if (closed) {
            throw new IllegalStateException("the decoder is closed");
        }
        independent.reset();
        for (var decoder : dependents) {
            if (decoder != null) {
                decoder.reset();
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Later calls to {@link #decode(Packet, Consumer)} and {@link #reset()} throw
     * {@link IllegalStateException}; closing again has no effect.
     */
    @Override
    public void close() {
        closed = true;
    }
}

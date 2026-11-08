/* Ports the ISO/IEC 23003-3:2019 reference software (decoder_usac.c USACDecodeFrame and the extension
   element reader). Copyright Sony Corporation, Fraunhofer IIS, VoiceAge Corp. and ISO/IEC 2008; ISO/IEC
   reference software licence (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.AacKernels;
import com.github.auties00.smalt.audio.aac.internal.SamplingTables;
import com.github.auties00.smalt.audio.aac.internal.drc.MpegDrcDecoder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Decodes USAC access units ({@code UsacFrame}, ISO/IEC 23003-3 subclause 5.3.1) into blocks of samples.
 *
 * <p>Samples are scaled so that a full-scale 16-bit decoder output spans {@code [-1, 1]}; output channels follow
 * the WAVE order of {@link ChannelLayout}. The access units of an {@code AudioPreRoll()} at the start of the stream
 * are decoded before the first access unit and their output is discarded. A stream with MPEG-D DRC metadata (a
 * {@code uniDrc} extension element or a {@code loudnessInfoSet()} configuration extension) is processed by the
 * MPEG-D DRC decoder of ISO/IEC 23003-4 after the core decoder, whose delay is absorbed: the first block is shorter
 * by it and {@link #flush(Consumer)} returns the rest. Instances are not thread-safe.
 */
public final class UsacDecoder {
    private static final double OUTPUT_SCALE = 1.0 / 32768.0;

    /**
     * The delay of the DRC gains relative to the audio when spectral band replication runs, in samples.
     */
    private static final int SBR_GAIN_DELAY = 257;

    /**
     * Holds the playback request of the MPEG-D DRC decoder.
     *
     * @param targetLoudness the target loudness in LKFS, or {@code NaN} to leave loudness normalization off
     * @param effectRequest the requested DRC effect types, packed as {@code MpegDrcDecoder} takes them
     * @param boost the scale of the DRC gain boosts, from 0 to 1
     * @param compress the scale of the DRC gain attenuations, from 0 to 1
     */
    public record DrcRequest(float targetLoudness, long effectRequest, float boost, float compress) {
        /**
         * The request of a decoder without preferences: no loudness normalization, no compression effect requested
         * and unscaled gains.
         */
        public static final DrcRequest DEFAULT = new DrcRequest(Float.NaN, 0, 1.0f, 1.0f);
    }

    private final UsacConfig config;
    private final int coreLength;
    private final int outputLength;
    private final CoreDecoder core;
    private final EsbrContext esbr;
    private final ElementState[] elements;
    private final ChannelLayout layout;
    private final int[] outputOrder;
    private final AudioBuffer output;
    private final AacBitReader reader = new AacBitReader(new byte[0]);
    private final float[][] channelOutputs;
    private MpegDrcDecoder drc;
    private final byte[] drcConfig;
    private final DrcRequest drcRequest;
    private final float[][] drcWork;
    private byte[] drcPayload = new byte[0];
    private int drcPayloadLength;
    private boolean drcPayloadComplete;
    private int drcDiscard = -1;
    private boolean started;

    /**
     * Whether a frame was lost or failed to decode since the last independent access unit, which makes the dependent
     * access units until the next independent one undecodable.
     */
    private boolean recovering;

    private static final class ElementState {
        final UsacConfig.Element config;
        final UsacChannel[] channels;
        final CoreDecoder.PairState pair;
        final int firstOutput;
        final EsbrElement sbr;
        final float[][] sbrInput;
        final float[][] sbrOutput;
        final Mps212Decoder mps;
        final float[][][] mpsInputReal;
        final float[][][] mpsInputImag;
        final float[][] mpsOutput;
        final EsbrChannel[] mpsHighBand;

        ElementState(UsacConfig.Element config, UsacChannel[] channels, CoreDecoder.PairState pair, int firstOutput,
                     EsbrElement sbr, Mps212Decoder mps, int outputLength) {
            this.config = config;
            this.channels = channels;
            this.pair = pair;
            this.firstOutput = firstOutput;
            this.sbr = sbr;
            this.mps = mps;
            if (sbr != null) {
                var count = sbr.channels().length;
                this.sbrInput = new float[count][];
                this.sbrOutput = new float[count][outputLength];
                for (var i = 0; i < count; i++) {
                    sbrInput[i] = channels[i].output;
                }
            } else {
                this.sbrInput = null;
                this.sbrOutput = null;
            }
            if (mps != null) {
                var count = sbr.channels().length;
                this.mpsInputReal = new float[count][][];
                this.mpsInputImag = new float[count][][];
                for (var i = 0; i < count; i++) {
                    mpsInputReal[i] = sbr.channels()[i].outputReal;
                    mpsInputImag[i] = sbr.channels()[i].outputImag;
                }
                this.mpsOutput = new float[2][outputLength];
                this.mpsHighBand = config.stereoConfigIndex() == 3 ? sbr.channels() : null;
            } else {
                this.mpsInputReal = null;
                this.mpsInputImag = null;
                this.mpsOutput = null;
                this.mpsHighBand = null;
            }
        }

        float[] output(int channel) {
            if (mpsOutput != null) {
                return mpsOutput[channel];
            }
            if (sbrOutput != null) {
                return sbrOutput[Math.min(channel, sbrOutput.length - 1)];
            }
            return channels[Math.min(channel, channels.length - 1)].output;
        }
    }

    /**
     * Constructs a decoder for a configuration with the {@linkplain DrcRequest#DEFAULT default} DRC request.
     *
     * @param config the USAC configuration
     * @param limits the resource limits
     * @throws MediaException.Unsupported if the configuration uses a tool this decoder lacks
     * @throws MediaException.Malformed if the elements do not produce the configured channels or the DRC metadata is
     * invalid
     * @throws MediaException.LimitExceeded if the configuration declares more channels than the limits allow
     */
    public UsacDecoder(UsacConfig config, MediaLimits limits) {
        this(config, limits, DrcRequest.DEFAULT);
    }

    /**
     * Constructs a decoder for a configuration.
     *
     * @param config the USAC configuration
     * @param limits the resource limits
     * @param request the playback request of the MPEG-D DRC decoder
     * @throws MediaException.Unsupported if the configuration uses a tool this decoder lacks
     * @throws MediaException.Malformed if the elements do not produce the configured channels or the DRC metadata is
     * invalid
     * @throws MediaException.LimitExceeded if the configuration declares more channels than the limits allow
     */
    public UsacDecoder(UsacConfig config, MediaLimits limits, DrcRequest request) {
        this.config = config;
        this.coreLength = config.coreFrameLength();
        this.outputLength = config.outputFrameLength();
        var channelsOut = config.outputChannels();
        if (channelsOut > limits.maxChannels()) {
            throw new MediaException.LimitExceeded("USAC configuration declares " + channelsOut + " channels, over the "
                    + limits.maxChannels() + " allowed");
        }
        var coreRate = config.coreSamplingFrequency();
        this.esbr = config.sbrRatioIndex() > 0
                ? new EsbrContext(config.sbrRatioIndex(), coreLength, config.samplingFrequency()) : null;
        var tableRate = coreLength == 768 ? coreRate * 4 / 3 : coreRate;
        var tableIndex = SamplingTables.tableIndexFor(tableRate);
        this.core = new CoreDecoder(coreLength, coreRate, AacKernels.INSTANCE);
        var states = new ArrayList<ElementState>();
        var next = 0;
        for (var element : config.elements()) {
            var count = element.coreChannels();
            var channels = new UsacChannel[count];
            for (var i = 0; i < count; i++) {
                channels[i] = new UsacChannel(coreLength, tableIndex, i == 0 ? 0x3039 : 0x10932);
            }
            var pair = element.type() == UsacConfig.CPE ? new CoreDecoder.PairState(coreLength) : null;
            EsbrElement sbr = null;
            Mps212Decoder mps = null;
            if (esbr != null && count > 0) {
                sbr = new EsbrElement(esbr, element, count);
                if (element.type() == UsacConfig.CPE && element.stereoConfigIndex() > 0) {
                    mps = new Mps212Decoder(element.mps(), element.stereoConfigIndex(), esbr.columns);
                }
            }
            states.add(new ElementState(element, channels, pair, next, sbr, mps, outputLength));
            next += element.outputChannels();
        }
        if (next != channelsOut) {
            throw new MediaException.Malformed("USAC elements produce " + next + " channels, the configuration declares "
                    + channelsOut);
        }
        this.elements = states.toArray(ElementState[]::new);
        this.channelOutputs = new float[channelsOut][];
        var speakers = speakersOf(config.outputPositions());
        var order = new Integer[channelsOut];
        for (var i = 0; i < channelsOut; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(speakers[a].ordinal(), speakers[b].ordinal()));
        this.outputOrder = new int[channelsOut];
        var sorted = new ArrayList<Speaker>();
        for (var i = 0; i < channelsOut; i++) {
            outputOrder[order[i]] = i;
            sorted.add(speakers[order[i]]);
        }
        this.layout = new ChannelLayout(sorted);
        this.output = new AudioBuffer(layout, config.samplingFrequency(), outputLength);
        byte[] drcConfig = null;
        for (var element : config.elements()) {
            if (element.type() == UsacConfig.EXT && element.extensionType() == UsacConfig.EXT_UNI_DRC) {
                drcConfig = element.extensionConfig();
            }
        }
        this.drcConfig = drcConfig;
        this.drcRequest = request;
        if (drcConfig != null || config.loudnessInfo() != null) {
            this.drc = newDrc();
            this.drcWork = new float[channelsOut][outputLength];
        } else {
            this.drc = null;
            this.drcWork = null;
        }
    }

    /**
     * {@return a new MPEG-D DRC decoder for the configuration and the playback request}
     */
    private MpegDrcDecoder newDrc() {
        return new MpegDrcDecoder(config.samplingFrequency(), channelOutputs.length, outputLength,
                esbr != null ? SBR_GAIN_DELAY : 0, drcConfig, config.loudnessInfo(), drcRequest.targetLoudness(),
                drcRequest.effectRequest(), drcRequest.boost(), drcRequest.compress(), true);
    }

    private static Speaker[] speakersOf(int[] positions) {
        var hasRear = false;
        for (var p : positions) {
            hasRear |= p == 8 || p == 9;
        }
        var out = new Speaker[positions.length];
        for (var i = 0; i < positions.length; i++) {
            out[i] = switch (positions[i]) {
                case 0 -> Speaker.FRONT_LEFT;
                case 1 -> Speaker.FRONT_RIGHT;
                case 2 -> Speaker.FRONT_CENTER;
                case 3 -> Speaker.LOW_FREQUENCY;
                case 4 -> hasRear ? Speaker.SIDE_LEFT : Speaker.BACK_LEFT;
                case 5 -> hasRear ? Speaker.SIDE_RIGHT : Speaker.BACK_RIGHT;
                case 6 -> Speaker.FRONT_LEFT_OF_CENTER;
                case 7 -> Speaker.FRONT_RIGHT_OF_CENTER;
                case 8 -> Speaker.BACK_LEFT;
                case 9 -> Speaker.BACK_RIGHT;
                case 10 -> Speaker.BACK_CENTER;
                case 11 -> Speaker.SURROUND_DIRECT_LEFT;
                case 12 -> Speaker.SURROUND_DIRECT_RIGHT;
                case 13 -> Speaker.SIDE_LEFT;
                case 14 -> Speaker.SIDE_RIGHT;
                case 15 -> Speaker.WIDE_LEFT;
                case 16 -> Speaker.WIDE_RIGHT;
                case 17 -> Speaker.TOP_FRONT_LEFT;
                case 18 -> Speaker.TOP_FRONT_RIGHT;
                case 19 -> Speaker.TOP_FRONT_CENTER;
                case 20 -> Speaker.TOP_BACK_LEFT;
                case 21 -> Speaker.TOP_BACK_RIGHT;
                case 22 -> Speaker.TOP_BACK_CENTER;
                case 23 -> Speaker.TOP_SIDE_LEFT;
                case 24 -> Speaker.TOP_SIDE_RIGHT;
                case 25 -> Speaker.TOP_CENTER;
                case 26 -> Speaker.LOW_FREQUENCY_2;
                case 27 -> Speaker.BOTTOM_FRONT_LEFT;
                case 28 -> Speaker.BOTTOM_FRONT_RIGHT;
                case 29 -> Speaker.BOTTOM_FRONT_CENTER;
                default -> Speaker.UNKNOWN;
            };
        }
        for (var i = 0; i < out.length; i++) {
            for (var j = 0; j < i; j++) {
                if (out[i] == out[j] && out[i] != Speaker.UNKNOWN) {
                    out[i] = Speaker.UNKNOWN;
                }
            }
        }
        return out;
    }

    /**
     * {@return the output sampling rate in hertz}
     */
    public int sampleRate() {
        return config.samplingFrequency();
    }

    /**
     * {@return the output channel layout}
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return a new array holding the coded channel each output channel of {@link #layout()} carries}
     */
    public int[] codedChannelOrder() {
        var coded = new int[outputOrder.length];
        for (var channel = 0; channel < outputOrder.length; channel++) {
            coded[outputOrder[channel]] = channel;
        }
        return coded;
    }

    /**
     * {@return whether the output includes a replicated high band}
     */
    public boolean sbrActive() {
        return config.sbrRatioIndex() > 0;
    }

    /**
     * {@return the number of samples per channel each access unit decodes to}
     */
    public int frameLength() {
        return outputLength;
    }

    /**
     * Decodes one access unit.
     *
     * <p>The first access unit of a stream also decodes the access units of its {@code AudioPreRoll()}, whose output
     * is discarded. With MPEG-D DRC, the block of the first access unit is shorter by the DRC delay. An empty access
     * unit is concealed as {@link #conceal(Consumer)} describes, so is a corrupt one from the first element that fails
     * to decode, and so are the dependent access units after either until an independent one.
     *
     * @param data the input array
     * @param offset the index of the access unit's first byte
     * @param length the number of bytes
     * @param sink receives the decoded block, valid only during the call
     * @throws MediaException.Unsupported if the access unit uses a tool the decoder does not implement; it emits no
     * block, and the dependent access units after it are concealed
     */
    public void decode(byte[] data, int offset, int length, Consumer<AudioBuffer> sink) {
        if (!started) {
            started = true;
            byte[][] preRoll;
            try {
                preRoll = readPreRoll(data, offset, length);
            } catch (MediaException.Malformed failure) {
                conceal(sink);
                return;
            }
            if (preRoll != null) {
                for (var unit : preRoll) {
                    decodeUnit(unit, 0, unit.length, null);
                }
            }
        }
        decodeUnit(data, offset, length, sink);
    }

    /**
     * Emits the samples the MPEG-D DRC decoder still delays, at the end of the stream.
     *
     * @param sink receives the remaining samples, valid only during the call
     */
    public void flush(Consumer<AudioBuffer> sink) {
        if (drc == null || drcDiscard < 0) {
            return;
        }
        var remaining = drcDiscard;
        while (remaining > 0) {
            for (var channel : drcWork) {
                Arrays.fill(channel, 0.0f);
            }
            drc.process(drcWork, null, 0, 0);
            var count = Math.min(remaining, outputLength);
            emit(drcWork, 0, count, sink);
            remaining -= count;
        }
        drcDiscard = 0;
    }

    /**
     * Conceals one lost access unit, emitting a block of {@link #frameLength()} samples (shorter by the DRC delay
     * when it is the first block): each core channel repeats or extrapolates its last frame in its last coding mode,
     * fading to silence over eight frames, spectral band replication repeats its last envelope a quantization step
     * lower each frame, MPEG Surround keeps its last parameters and the MPEG-D DRC decoder its last gains. The access
     * units that follow are concealed too until one is independent.
     *
     * @param sink receives the block, valid only during the call
     * @return the number of samples per channel emitted
     */
    public int conceal(Consumer<AudioBuffer> sink) {
        drcPayloadComplete = false;
        for (var element : elements) {
            if (element.config.type() != UsacConfig.EXT) {
                concealElement(element);
            }
        }
        recovering = true;
        return emitFrame(sink);
    }

    private void decodeUnit(byte[] data, int offset, int length, Consumer<AudioBuffer> sink) {
        if (length == 0) {
            conceal(sink);
            return;
        }
        reader.reset(data, offset, length);
        drcPayloadComplete = false;
        var independent = reader.readFlag();
        if (recovering && !independent) {
            conceal(sink);
            return;
        }
        var failed = false;
        try {
            for (var element : elements) {
                if (failed) {
                    if (element.config.type() != UsacConfig.EXT) {
                        concealElement(element);
                    }
                    continue;
                }
                failed = !decodeElement(element, independent) || reader.overrun();
            }
        } catch (MediaException.Unsupported failure) {
            // The access unit emits no block; the dependent ones after it lack its state.
            recovering = true;
            throw failure;
        }
        recovering = failed;
        emitFrame(sink);
    }

    /**
     * Emits the block of the current frame.
     *
     * @param sink receives the block, or {@code null} to discard it
     * @return the number of samples per channel emitted
     */
    private int emitFrame(Consumer<AudioBuffer> sink) {
        if (drc == null) {
            if (sink == null) {
                return 0;
            }
            for (var element : elements) {
                var channels = element.config.outputChannels();
                for (var c = 0; c < channels; c++) {
                    var source = element.output(c);
                    var plane = output.plane(outputOrder[element.firstOutput + c]);
                    for (var i = 0; i < outputLength; i++) {
                        plane[i] = (float) (source[i] * OUTPUT_SCALE);
                    }
                }
            }
            output.setFrames(outputLength);
            sink.accept(output);
            return outputLength;
        }
        fillDrcWork();
        try {
            drc.process(drcWork, drcPayloadComplete ? drcPayload : null, 0, drcPayloadLength);
        } catch (MediaException.Malformed failure) {
            // A corrupt gain payload can leave gains the decoder cannot apply in this and later frames; a new
            // decoder of the same configuration and delay continues from neutral gains.
            drc = newDrc();
            fillDrcWork();
            drc.process(drcWork, null, 0, 0);
        }
        if (drcDiscard < 0) {
            drcDiscard = 0;
        }
        if (sink == null) {
            return 0;
        }
        var skip = Math.min(drc.delay() - drcDiscard, outputLength);
        drcDiscard += skip;
        emit(drcWork, skip, outputLength - skip, sink);
        return outputLength - skip;
    }

    /**
     * Copies the core output of the current frame into the input of the MPEG-D DRC decoder.
     */
    private void fillDrcWork() {
        for (var element : elements) {
            var channels = element.config.outputChannels();
            for (var c = 0; c < channels; c++) {
                var source = element.output(c);
                var plane = drcWork[element.firstOutput + c];
                for (var i = 0; i < outputLength; i++) {
                    plane[i] = (float) (source[i] * OUTPUT_SCALE);
                }
            }
        }
    }

    private void emit(float[][] source, int start, int count, Consumer<AudioBuffer> sink) {
        if (count <= 0) {
            return;
        }
        for (var c = 0; c < source.length; c++) {
            System.arraycopy(source[c], start, output.plane(outputOrder[c]), 0, count);
        }
        output.setFrames(count);
        sink.accept(output);
    }

    /**
     * Reads the access units of the {@code AudioPreRoll()} that opens an access unit, when the first element of the
     * configuration is an audio pre-roll extension element.
     *
     * @return the access units, or {@code null} without a valid pre-roll
     */
    private byte[][] readPreRoll(byte[] data, int offset, int length) {
        var first = config.elements().getFirst();
        if (first.type() != UsacConfig.EXT || first.extensionType() != UsacConfig.EXT_AUDIO_PRE_ROLL) {
            return null;
        }
        reader.reset(data, offset, length);
        if (!reader.readFlag() || !reader.readFlag() || reader.readFlag()) {
            return null;
        }
        var payloadLength = reader.read(8);
        if (payloadLength == 255) {
            payloadLength += reader.read(16) - 2;
        }
        if (first.extensionFragmented() && (!reader.readFlag() || !reader.readFlag())) {
            return null;
        }
        if (payloadLength * 8L > reader.bitsLeft()) {
            throw new MediaException.Malformed("USAC audio pre-roll is truncated");
        }
        var configLength = UsacConfig.escaped(reader, 4, 4, 8);
        reader.skip(8L * configLength);
        reader.read(2);
        var count = UsacConfig.escaped(reader, 2, 4, 0);
        var units = new byte[count][];
        for (var i = 0; i < count; i++) {
            var unitLength = UsacConfig.escaped(reader, 16, 16, 0);
            if (unitLength * 8L > reader.bitsLeft()) {
                throw new MediaException.Malformed("USAC audio pre-roll is truncated");
            }
            var unit = new byte[unitLength];
            for (var j = 0; j < unitLength; j++) {
                unit[j] = (byte) reader.read(8);
            }
            units[i] = unit;
        }
        return units;
    }

    /**
     * Decodes one element of an access unit; when its data is corrupt, the rest of the element is concealed.
     *
     * @return whether the element decoded, which leaves the reader at the next element
     */
    private boolean decodeElement(ElementState element, boolean independent) {
        var config = element.config;
        if (config.type() == UsacConfig.EXT) {
            try {
                readExtension(config);
                return true;
            } catch (MediaException.Malformed failure) {
                drcPayloadComplete = false;
                return false;
            }
        }
        for (var channel : element.channels) {
            channel.mark();
        }
        var ok = true;
        try {
            switch (config.type()) {
                case UsacConfig.SCE -> core.decodeSingle(reader, element.channels[0], false, config, independent);
                case UsacConfig.LFE -> core.decodeSingle(reader, element.channels[0], true, config, independent);
                default -> {
                    if (config.stereoConfigIndex() == 1) {
                        core.decodeSingle(reader, element.channels[0], false, config, independent);
                    } else {
                        core.decodePair(reader, element.channels[0], element.channels[1], element.pair, config,
                                independent);
                    }
                }
            }
        } catch (MediaException.Malformed failure) {
            ok = false;
            for (var channel : element.channels) {
                if (!channel.complete) {
                    channel.restore();
                    core.conceal(channel, config);
                }
            }
        } catch (MediaException.Unsupported failure) {
            for (var channel : element.channels) {
                if (!channel.complete) {
                    channel.restore();
                }
            }
            throw failure;
        }
        return finishElement(element, independent, ok);
    }

    /**
     * Conceals every channel of an element and the tools that follow its core coder.
     */
    private void concealElement(ElementState element) {
        for (var channel : element.channels) {
            core.conceal(channel, element.config);
        }
        finishElement(element, false, false);
    }

    /**
     * Runs the tools that follow the core coder of an element: the pseudo left and right rotation, spectral band
     * replication and MPEG Surround, whose data is read only while the element's data is intact and concealed
     * otherwise.
     *
     * @param ok whether the element's data decoded so far
     * @return whether the element's data decoded
     */
    private boolean finishElement(ElementState element, boolean independent, boolean ok) {
        var config = element.config;
        var mps = element.mps;
        if (mps != null && config.stereoConfigIndex() > 1 && config.mps().pseudoLr()) {
            rotatePseudoLr(element.channels[0].output, element.channels[1].output);
        }
        var sbr = element.sbr;
        if (sbr != null) {
            if (config.type() != UsacConfig.LFE) {
                if (ok) {
                    try {
                        sbr.read(reader, independent);
                    } catch (MediaException.Malformed failure) {
                        ok = false;
                    }
                }
                if (!ok) {
                    sbr.conceal();
                }
            }
            sbr.process(element.sbrInput, element.sbrOutput, config.stereoConfigIndex(), mps == null);
            if (!ok && muted(element)) {
                for (var plane : element.sbrOutput) {
                    Arrays.fill(plane, 0f);
                }
            }
        }
        if (mps != null) {
            if (ok) {
                try {
                    mps.read(reader, independent);
                } catch (MediaException.Malformed failure) {
                    ok = false;
                }
            }
            if (!ok) {
                mps.conceal();
            }
            mps.apply(element.mpsInputReal, element.mpsInputImag, esbr.qmf, element.mpsOutput, element.mpsHighBand);
            if (!ok && muted(element)) {
                for (var plane : element.mpsOutput) {
                    Arrays.fill(plane, 0f);
                }
            }
        }
        return ok;
    }

    /**
     * {@return whether the concealment has faded every core channel of an element to silence}, which silences the
     * high band and the upmix too
     */
    private static boolean muted(ElementState element) {
        for (var channel : element.channels) {
            if (channel.ec.fadeIndex < UsacConcealment.FADE_FRAMES) {
                return false;
            }
        }
        return true;
    }

    /**
     * Turns the pseudo left and right channels of a residual coded element into the downmix and the residual.
     */
    private void rotatePseudoLr(float[] left, float[] right) {
        var root = Math.sqrt(2.0);
        for (var i = 0; i < coreLength; i++) {
            var l = left[i];
            var r = right[i];
            left[i] = (float) ((l + r) / root);
            right[i] = (float) ((l - r) / root);
        }
    }

    /**
     * Reads one {@code UsacExtElement}: an MPEG-D DRC gain payload is kept for the DRC decoder (and assembled from
     * its fragments), any other payload is skipped.
     */
    private void readExtension(UsacConfig.Element config) {
        if (!reader.readFlag()) {
            return;
        }
        int length;
        if (reader.readFlag()) {
            length = config.extensionDefaultLength();
        } else {
            length = reader.read(8);
            if (length == 255) {
                length += reader.read(16) - 2;
            }
        }
        if (length > 0) {
            var start = true;
            var stop = true;
            if (config.extensionFragmented()) {
                start = reader.readFlag();
                stop = reader.readFlag();
            }
            if (length * 8L > reader.bitsLeft()) {
                throw new MediaException.Malformed("USAC extension element payload is truncated");
            }
            if (drc != null && config.extensionType() == UsacConfig.EXT_UNI_DRC) {
                if (start) {
                    drcPayloadLength = 0;
                }
                if (drcPayload.length < drcPayloadLength + length) {
                    drcPayload = Arrays.copyOf(drcPayload, Math.max(2 * drcPayload.length, drcPayloadLength + length));
                }
                for (var i = 0; i < length; i++) {
                    drcPayload[drcPayloadLength++] = (byte) reader.read(8);
                }
                drcPayloadComplete = stop;
            } else {
                reader.skip(8L * length);
            }
        }
    }
}

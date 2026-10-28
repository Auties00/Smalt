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
    private final MpegDrcDecoder drc;
    private final float[][] drcWork;
    private byte[] drcPayload = new byte[0];
    private int drcPayloadLength;
    private boolean drcPayloadComplete;
    private int drcDiscard = -1;
    private boolean started;

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
        if (drcConfig != null || config.loudnessInfo() != null) {
            this.drc = new MpegDrcDecoder(config.samplingFrequency(), channelsOut, outputLength,
                    esbr != null ? SBR_GAIN_DELAY : 0, drcConfig, config.loudnessInfo(), request.targetLoudness(),
                    request.effectRequest(), request.boost(), request.compress(), true);
            this.drcWork = new float[channelsOut][outputLength];
        } else {
            this.drc = null;
            this.drcWork = null;
        }
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
     * is discarded. With MPEG-D DRC, the block of the first access unit is shorter by the DRC delay.
     *
     * @param data the input array
     * @param offset the index of the access unit's first byte
     * @param length the number of bytes
     * @param sink receives the decoded block, valid only during the call
     * @throws MediaException.Malformed if the access unit is corrupt
     */
    public void decode(byte[] data, int offset, int length, Consumer<AudioBuffer> sink) {
        if (!started) {
            started = true;
            var preRoll = readPreRoll(data, offset, length);
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
        var remaining = drc.delay() - drcDiscard;
        while (remaining > 0) {
            for (var channel : drcWork) {
                Arrays.fill(channel, 0.0f);
            }
            drc.process(drcWork, null, 0, 0);
            var count = Math.min(remaining, outputLength);
            emit(drcWork, 0, count, sink);
            remaining -= count;
        }
        drcDiscard = drc.delay();
    }

    private void decodeUnit(byte[] data, int offset, int length, Consumer<AudioBuffer> sink) {
        reader.reset(data, offset, length);
        drcPayloadComplete = false;
        var independent = reader.readFlag();
        for (var element : elements) {
            decodeElement(element, independent);
            reader.checkNotOverrun();
        }
        if (drc == null) {
            if (sink == null) {
                return;
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
            return;
        }
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
        drc.process(drcWork, drcPayloadComplete ? drcPayload : null, 0, drcPayloadLength);
        if (drcDiscard < 0) {
            drcDiscard = 0;
        }
        if (sink == null) {
            return;
        }
        var skip = Math.min(drc.delay() - drcDiscard, outputLength);
        drcDiscard += skip;
        emit(drcWork, skip, outputLength - skip, sink);
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

    private void decodeElement(ElementState element, boolean independent) {
        var config = element.config;
        switch (config.type()) {
            case UsacConfig.SCE -> core.decodeSingle(reader, element.channels[0], false, config, independent);
            case UsacConfig.LFE -> core.decodeSingle(reader, element.channels[0], true, config, independent);
            case UsacConfig.CPE -> {
                if (config.stereoConfigIndex() == 1) {
                    core.decodeSingle(reader, element.channels[0], false, config, independent);
                } else {
                    core.decodePair(reader, element.channels[0], element.channels[1], element.pair, config,
                            independent);
                }
            }
            default -> {
                readExtension(config);
                return;
            }
        }
        var mps = element.mps;
        if (mps != null && config.stereoConfigIndex() > 1 && config.mps().pseudoLr()) {
            rotatePseudoLr(element.channels[0].output, element.channels[1].output);
        }
        var sbr = element.sbr;
        if (sbr != null) {
            if (config.type() != UsacConfig.LFE) {
                sbr.read(reader, independent);
            }
            sbr.process(element.sbrInput, element.sbrOutput, config.stereoConfigIndex(), mps == null);
        }
        if (mps != null) {
            mps.read(reader, independent);
            mps.apply(element.mpsInputReal, element.mpsInputImag, esbr.qmf, element.mpsOutput, element.mpsHighBand);
        }
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

/* Ports the scalable AAC decoder of the ISO/IEC 14496-3 reference software as distributed with the ISO/IEC 23003-3
   reference software (tfScaleableAUDecode and its helpers in scal_dec_frame.c of src_tf, with the scalable paths of
   decoder_tf.c and huffdec2.c), developed by Bodo Teichmann (Fraunhofer IIS) and edited by Bernhard Grill
   (Fraunhofer IIS), Takashi Koike (Sony Corporation), Markus Werner (SEED / Software Development Karlsruhe) and Olaf
   Kaehler (Fraunhofer IIS). ISO/IEC gives You a royalty-free, worldwide, non-exclusive, copyright license to copy,
   distribute, and make derivative works of this software module or modifications thereof for use in implementations
   of ISO/IEC 23003 in products that satisfy conformance criteria (if any). Copyright (c) ISO/IEC 2008.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.twinvq.internal.TwinVqObject;

import java.util.Arrays;
import java.util.List;

/**
 * Decodes the coding layers of a scalable AAC audio object (object types 6 and 20, ISO/IEC 14496-3 subclause
 * 4.5.2.2) into the spectrum of its output layer.
 *
 * <p>Each layer is an elementary stream: the first carries an {@code aac_scalable_main_element}, the others an
 * {@code aac_scalable_extension_element}. Layers are mono or stereo, the mono layers first; a frame decodes the
 * layers it holds up to the first missing one. The spectra of the mono layers are added, as are those of the
 * stereo layers; with mono and stereo layers, the frequency selective switch adds the mono spectrum, doubled in
 * the bands coded left and right, to the stereo spectra where {@code diff_control_lr} selects it, before the
 * inverse M/S matrix. The TNS filters of the first layer then run on the combined spectrum, and those of the first
 * stereo layer after a mono one on the stereo spectra.
 *
 * <p>Long term prediction belongs to the first layer: its spectrum is predicted from the history of its own
 * reconstruction, which runs it through its TNS filters and a filterbank of its own, whatever the output layer.
 *
 * <p>The error resilient object type reads the fields of each layer in the order of their error sensitivity
 * categories; with {@code epConfig} 1 every category instance of a layer comes in an access unit of its own: a
 * mono layer the instances of categories 1, 2 (with RVLC scalefactors), 3 and 4, a stereo layer the instances of
 * categories 0 to 4 of its first channel and 1 to 4 of its second.
 *
 * <p>Over a CELP core ({@link CelpCoreLayer}), the first AAC layer carries no long term prediction; its
 * {@code diff_control} flags tell, per dc group of four bands (per window of an eight short sequence), whether the
 * frequency selective switch adds the up-sampled core spectrum, run through the TNS analysis filters of the first
 * layer, to the combined mono spectrum, or with a stereo first layer, as {@code diff_control_lr} tells per band and
 * channel, to the combined stereo spectra before the inverse M/S matrix (subclause 4.6.14.1).
 *
 * <p>Over a TwinVQ core ({@link TwinVqObject}, subclause 4.5.2.2.6), the core decodes first and gives the window
 * sequence and shape of the frame, which the first AAC layer then does not code; the core's spectrum takes the place of
 * the CELP core's, and its bandwidth, through the {@code max_sfb} it stands for, gives the dc groups.
 */
final class ScalableDecoder {
    /**
     * The categories a channel of a layer reads its fields from: side information, RVLC codewords, TNS data and
     * spectral data.
     */
    private static final int SIDE = 0;

    private static final int RVLC = 1;

    private static final int TNS = 2;

    private static final int SPECTRAL = 3;

    /**
     * The tools of a band of a combined spectrum: intensity stereo, and noise substitution in the left and in the
     * right channel.
     */
    private static final int INTENSITY = 1;

    private static final int NOISE_LEFT = 2;

    private static final int NOISE_RIGHT = 4;

    /**
     * The initial state of the noise generator: that of the reference decoder, so that noise substitution matches
     * its output sample by sample when every band is generated in its order (window group, window, band).
     */
    private static final int NOISE_SEED = 0;

    /**
     * The number of coding layers the configurations describe.
     */
    private final int layerCount;

    /**
     * The number of channels of each layer, 1 or 2.
     */
    private final int[] layerChannels;

    /**
     * Whether the layers use the error resilient syntax.
     */
    private final boolean errorResilient;

    /**
     * Whether each category instance of a layer comes in an access unit of its own ({@code epConfig} 1).
     */
    private final boolean separateCategories;

    /**
     * The number of category instances of each layer with {@code epConfig} 1.
     */
    private final int[] instanceCounts;

    /**
     * Whether each layer codes its scalefactors with RVLC.
     */
    private final boolean[] reversible;

    /**
     * The channel streams of each layer.
     */
    private final ChannelStream[][] streams;

    /**
     * The layer whose output this decoder produces.
     */
    private final int outputLayer;

    /**
     * The first stereo layer, or -1 when every layer is mono.
     */
    private final int firstStereo;

    /**
     * The last mono layer, or -1 when every layer is stereo.
     */
    private final int lastMono;

    /**
     * The number of spectral lines of a frame.
     */
    private final int frameLength;

    /**
     * The long-window band offsets.
     */
    private final int[] longOffsets;

    /**
     * The short-window band offsets.
     */
    private final int[] shortOffsets;

    /**
     * The reader of each layer with {@code epConfig} 0.
     */
    private final AacBitReader[] layerReaders;

    /**
     * The reader of each category instance with {@code epConfig} 1, per layer.
     */
    private final AacBitReader[][] instanceReaders;

    /**
     * The window sequence of the current frame.
     */
    private int windowSequence;

    /**
     * The window shape of the current frame.
     */
    private int windowShape;

    /**
     * The {@code scale_factor_grouping} of the current frame, 0 for a long window sequence.
     */
    private int grouping;

    /**
     * The {@code max_sfb} of each layer of the current frame, 0 for the layers it lacks.
     */
    private final int[] maxBands = new int[8];

    /**
     * The {@code ms_used} flag of each window and band, indexed {@code window * MAX_BANDS + band}.
     */
    private final boolean[] msUsed = new boolean[8 * ChannelStream.MAX_BANDS];

    /**
     * The {@code diff_control_lr} flag of each channel: per band of a long window, per window of an eight short
     * sequence.
     */
    private final boolean[][] diffControl = new boolean[2][ChannelStream.MAX_BANDS];

    /**
     * The number of bands {@link #diffControl} covers in a long window.
     */
    private int diffBands;

    /**
     * The {@code tns_data_present} flag of each channel of the first stereo layer after a mono layer.
     */
    private final boolean[] extensionTns = new boolean[2];

    /**
     * The combined spectrum of each output channel.
     */
    private final float[][] spectra;

    /**
     * The mono spectrum the frequency selective switch adds to the stereo spectra.
     */
    private final float[] core;

    /**
     * The long term predictor of each channel of the first layer.
     */
    private final LongTermPredictor[] predictors;

    /**
     * The filterbank of the reconstruction of the first layer, which feeds the long term predictors.
     */
    private final Synthesis synthesis;

    /**
     * The overlap memory of the reconstruction of each channel of the first layer.
     */
    private final float[][] predictionOverlaps;

    /**
     * The window shape of the previous frame.
     */
    private int previousShape;

    /**
     * The scratch spectrum and time signal of the reconstruction of the first layer.
     */
    private final float[] scratch;

    private final float[] reconstruction;

    /**
     * The number of windows, their length, the band offsets of a window and their count, of the current frame.
     */
    private int windows;

    private int windowLength;

    private int[] offsets;

    private int bandCount;

    /**
     * The number of window groups of the current frame, and the first window of each with one entry past the last.
     */
    private int groupCount;

    private final int[] groupStarts = new int[9];

    /**
     * The tools of each band of the combined mono and stereo spectra, indexed {@code group * MAX_BANDS + band}.
     */
    private final int[] monoTools = new int[8 * ChannelStream.MAX_BANDS];

    private final int[] stereoTools = new int[8 * ChannelStream.MAX_BANDS];

    /**
     * The signed intensity scale of each intensity band of the combined stereo spectrum.
     */
    private final float[] intensityScales = new float[8 * ChannelStream.MAX_BANDS];

    /**
     * The noise generator state.
     */
    private int noiseState = NOISE_SEED;

    /**
     * The number of dc groups of a long window and the lines of the single dc group of a short window, by sampling
     * frequency index, for a narrowband and for a wideband CELP core (ISO/IEC 14496-3 Table 4.112).
     */
    private static final int[] DC_GROUPS = {4, 4, 5, 5, 5, 6, 8, 8, 8, 10, 10, 9, 9};

    private static final int[] DIFF_SHORT_LINES = {8, 8, 13, 18, 18, 26, 36, 36, 54, 104, 104, 104, 104};

    private static final int[] WIDEBAND_DC_GROUPS = {6, 6, 6, 7, 7, 8, 9, 9, 10};

    private static final int[] WIDEBAND_DIFF_SHORT_LINES = {18, 18, 26, 36, 36, 54, 104, 104, 104};

    /**
     * The CELP core layer, or {@code null} when every layer is AAC.
     */
    private final CelpCoreLayer celp;

    /**
     * The TwinVQ core layer, or {@code null} when it has none.
     */
    private final TwinVqObject twinVq;

    /**
     * Whether the first AAC layer has a core under it.
     */
    private final boolean hasCore;

    /**
     * The up-sampled spectrum of the core of the current frame, or {@code null} without a core.
     */
    private final float[] coreSpectrum;

    /**
     * The number of dc groups of a long window and the lines of the dc group of a short window.
     */
    private int dcGroups;

    private int diffShortLines;

    /**
     * The decoded {@code diff_control} flags of the first layer over a core, {@code true} where the switch takes the
     * AAC spectrum alone: per band of a long window, per window of an eight short sequence.
     */
    private final boolean[] coreControl = new boolean[ChannelStream.MAX_BANDS];

    /**
     * The number of bands the {@code diff_control_lr} flags of a stereo first layer over a core cover.
     */
    private int coreDiffBands;

    /**
     * Whether the core takes the TNS filters of the right channel of a stereo first layer
     * ({@code tns_channel_mono_layer}).
     */
    private boolean tnsFromRight;

    /**
     * Creates a decoder for the layers of a scalable audio object.
     *
     * @param configs the configuration of each elementary stream, in stream order: one per layer, or with
     * {@code epConfig} 1 one per category instance
     * @param maxLayer the highest layer to output, or -1 for the last one
     * @param kernel the inverse quantization kernel
     * @param celp the CELP core layer, or {@code null} when it has none
     * @param twinVq the TwinVQ core layer, decoded before each frame, or {@code null} when it has none
     * @throws MediaException.Unsupported if the first layer depends on a core coder and no core is given
     * @throws MediaException.Malformed if the configurations do not describe the layers of one object
     */
    ScalableDecoder(List<AudioSpecificConfig> configs, int maxLayer, AacKernel kernel, CelpCoreLayer celp,
                    TwinVqObject twinVq) {
        var first = configs.getFirst();
        this.hasCore = celp != null || twinVq != null;
        if (first.coreCoderDelay() >= 0 && !hasCore) {
            throw new MediaException.Unsupported("aac scalable core",
                    "scalable AAC over a core coder decodes only with the tracks of its core layer");
        }
        if (first.coreCoderDelay() < 0 && celp != null) {
            throw new MediaException.Malformed("a CELP core under scalable AAC layers that do not depend on it");
        }
        this.celp = celp;
        this.twinVq = twinVq;
        var layers = new AudioSpecificConfig[8];
        var count = 0;
        for (var config : configs) {
            if (!config.scalable() || config.objectType() != first.objectType()
                    || config.sampleRate() != first.sampleRate() || config.frameLength() != first.frameLength()
                    || config.epConfig() != first.epConfig()) {
                throw new MediaException.Malformed("scalable AAC streams of different configurations");
            }
            if (count > 0 && config.layer() == count - 1) {
                continue;
            }
            if (config.layer() != count) {
                throw new MediaException.Malformed("scalable AAC layer " + config.layer() + " where layer " + count
                        + " is expected");
            }
            layers[count++] = config;
        }
        this.layerCount = count;
        this.layerChannels = new int[count];
        this.reversible = new boolean[count];
        this.instanceCounts = new int[count];
        this.streams = new ChannelStream[count][];
        this.errorResilient = first.errorResilient();
        this.separateCategories = errorResilient && (first.epConfig() == 1 || first.epConfig() == 3);
        this.frameLength = first.frameLength();
        this.longOffsets = SamplingTables.longOffsets(first.tableIndex(), frameLength);
        this.shortOffsets = SamplingTables.shortOffsets(first.tableIndex(), frameLength);
        var stereo = -1;
        for (var layer = 0; layer < count; layer++) {
            var config = layers[layer];
            var channels = config.channelConfiguration();
            if (stereo >= 0 && channels == 1) {
                throw new MediaException.Malformed("scalable AAC mono layer after a stereo layer");
            }
            if (stereo < 0 && channels == 2) {
                stereo = layer;
            }
            layerChannels[layer] = channels;
            reversible[layer] = (config.resilience() & AudioSpecificConfig.RVLC_SCALEFACTORS) != 0;
            var categories = reversible[layer] ? 4 : 3;
            instanceCounts[layer] = channels == 1 ? categories : 2 * categories + 1;
            streams[layer] = new ChannelStream[channels];
            for (var channel = 0; channel < channels; channel++) {
                streams[layer][channel] = new ChannelStream(frameLength, first.tableIndex(), first.objectType(),
                        config.resilience(), kernel);
            }
        }
        this.firstStereo = stereo;
        this.lastMono = stereo >= 0 ? stereo - 1 : count - 1;
        this.outputLayer = maxLayer < 0 ? count - 1 : Math.min(maxLayer, count - 1);
        this.layerReaders = new AacBitReader[count];
        this.instanceReaders = new AacBitReader[count][];
        for (var layer = 0; layer < count; layer++) {
            layerReaders[layer] = new AacBitReader(new byte[0]);
            instanceReaders[layer] = new AacBitReader[instanceCounts[layer]];
            for (var i = 0; i < instanceCounts[layer]; i++) {
                instanceReaders[layer][i] = new AacBitReader(new byte[0]);
            }
        }
        this.spectra = new float[2][frameLength];
        this.core = new float[frameLength];
        this.predictors = new LongTermPredictor[layerChannels[0]];
        for (var channel = 0; channel < predictors.length; channel++) {
            predictors[channel] = new LongTermPredictor(frameLength, kernel);
        }
        this.synthesis = new Synthesis(frameLength, kernel);
        this.predictionOverlaps = new float[layerChannels[0]][frameLength];
        this.scratch = new float[frameLength];
        this.reconstruction = new float[frameLength];
        this.coreSpectrum = hasCore ? new float[frameLength] : null;
        var index = Math.min(first.tableIndex(), DC_GROUPS.length - 1);
        var wideband = celp != null && celp.sampleRate() > 12000 && index < WIDEBAND_DC_GROUPS.length;
        this.dcGroups = wideband ? WIDEBAND_DC_GROUPS[index] : DC_GROUPS[index];
        this.diffShortLines = wideband ? WIDEBAND_DIFF_SHORT_LINES[index] : DIFF_SHORT_LINES[index];
    }

    /**
     * {@return the number of output channels: those of the output layer}
     */
    int channels() {
        return layerChannels[outputLayer];
    }

    /**
     * {@return the window sequence of the last decoded frame}
     */
    int windowSequence() {
        return windowSequence;
    }

    /**
     * {@return the window shape of the last decoded frame}
     */
    int windowShape() {
        return windowShape;
    }

    /**
     * Returns the combined spectrum of an output channel of the last decoded frame.
     *
     * @param channel the output channel
     * @return the spectrum, laid out as {@link ChannelStream#spectrum()}; overwritten by the next frame
     */
    float[] spectrum(int channel) {
        return spectra[channel];
    }

    /**
     * Decodes one frame from the access units of its elementary streams.
     *
     * @param units the access units in stream order, in their first {@code count} entries: one per layer, or with
     * {@code epConfig} 1 one per category instance; read only during the call
     * @param sizes the number of bytes of each unit, or {@code null} when each fills its array
     * @param count the number of access units
     * @throws MediaException.Malformed if the first layer is missing or a layer is corrupt
     */
    void decode(byte[][] units, int[] sizes, int count) {
        var present = 0;
        var unit = 0;
        for (var layer = 0; layer < layerCount; layer++) {
            if (separateCategories) {
                if (unit + instanceCounts[layer] > count) {
                    break;
                }
                for (var i = 0; i < instanceCounts[layer]; i++) {
                    var data = units[unit];
                    instanceReaders[layer][i].reset(data, 0, sizes == null ? data.length : sizes[unit]);
                    unit++;
                }
            } else {
                if (unit == count) {
                    break;
                }
                var data = units[unit];
                layerReaders[layer].reset(data, 0, sizes == null ? data.length : sizes[unit]);
                unit++;
            }
            present++;
        }
        if (present == 0) {
            throw new MediaException.Malformed("scalable AAC frame without its main layer");
        }
        Arrays.fill(maxBands, 0);
        Arrays.fill(msUsed, false);
        Arrays.fill(diffControl[0], false);
        Arrays.fill(diffControl[1], false);
        diffBands = 0;
        coreDiffBands = 0;
        tnsFromRight = false;
        extensionTns[0] = false;
        extensionTns[1] = false;
        readMainLayer();
        var lastStereoBands = layerChannels[0] == 2 ? maxBands[0] : 0;
        for (var layer = 1; layer < present; layer++) {
            readExtensionLayer(layer, lastStereoBands);
            if (layerChannels[layer] == 2) {
                lastStereoBands = Math.max(lastStereoBands, maxBands[layer]);
            }
        }
        if (celp != null) {
            celp.transform(windowSequence, previousShape, windowShape, coreSpectrum);
        } else if (twinVq != null) {
            System.arraycopy(twinVq.spectrum(0), 0, coreSpectrum, 0, frameLength);
        }
        if (hasCore) {
            streams[0][layerChannels[0] == 2 && tnsFromRight ? 1 : 0].applyTnsAnalysis(coreSpectrum);
        }
        combine(Math.min(outputLayer, present - 1), present);
    }

    /**
     * Returns the source of a channel's fields of one category in a layer.
     *
     * @param layer the layer index, 0 for the base layer
     * @param channel the channel index of the layer, 0 for left or mono, 1 for right
     * @param category {@link #SIDE}, {@link #RVLC}, {@link #TNS} or {@link #SPECTRAL}
     * @return the reader
     */
    private AacBitReader source(int layer, int channel, int category) {
        if (!separateCategories) {
            return layerReaders[layer];
        }
        var instances = instanceReaders[layer];
        var perChannel = reversible[layer] ? 4 : 3;
        var index = category == SIDE ? 0 : category == RVLC ? 1 : category == TNS ? perChannel - 2 : perChannel - 1;
        if (layerChannels[layer] == 1) {
            return instances[index];
        }
        return instances[1 + channel * perChannel + index];
    }

    /**
     * Returns the source of the header fields of a layer that belong to no channel.
     *
     * @param layer the layer index, 0 for the base layer
     * @return the reader of category 0 of a stereo layer, or of the side information of a mono layer
     */
    private AacBitReader header(int layer) {
        if (!separateCategories) {
            return layerReaders[layer];
        }
        return instanceReaders[layer][0];
    }

    /**
     * Reads the {@code aac_scalable_main_element} of the first layer.
     *
     * @throws MediaException.Malformed if the element is corrupt
     */
    private void readMainLayer() {
        var reader = header(0);
        reader.read(1);
        if (twinVq != null) {
            windowSequence = twinVq.windowSequence();
            windowShape = twinVq.windowShape();
            // TODO: the standard omits tns_channel_mono_layer and the first layer's TNS fields under a TwinVQ core with
            //  TNS (ISO/IEC 14496-3 Table 4.90) and runs the core's filters over the combined spectrum (Figures 4.9,
            //  4.10, 4.14 and 4.15); the reference decoder, which this follows, reads them always and leaves the
            //  filters out. No ISO/IEC 14496-26 stream has such a frame.
            var coreBands = twinVq.maxBand();
            dcGroups = (coreBands + 3) / 4;
            diffShortLines = windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE ? shortOffsets[coreBands] : 0;
        } else {
            windowSequence = reader.read(2);
            windowShape = reader.read(1);
        }
        grouping = 0;
        if (windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            maxBands[0] = reader.read(4);
            grouping = reader.read(7);
        } else {
            maxBands[0] = reader.read(6);
        }
        var channels = layerChannels[0];
        for (var channel = 0; channel < channels; channel++) {
            streams[0][channel].setScalableInfo(windowSequence, windowShape, maxBands[0], grouping);
        }
        if (channels == 2) {
            readMidSide(reader, 0, maxBands[0], streams[0][0]);
            tnsFromRight = hasCore && reader.readFlag();
        }
        reader.checkNotOverrun();
        for (var channel = 0; channel < channels; channel++) {
            var stream = streams[0][channel];
            var side = source(0, channel, SIDE);
            var tns = side.readFlag();
            if (!errorResilient && tns) {
                stream.readScalableTns(side);
            }
            if (hasCore) {
                if (channel == 0) {
                    readCoreControl(side);
                }
                if (channels == 2) {
                    readCoreControlLr(side, channel);
                }
            } else if (side.readFlag()) {
                stream.readScalableLtp(side);
            }
            if (errorResilient) {
                stream.parseScalable(side, source(0, channel, RVLC), source(0, channel, TNS),
                        source(0, channel, SPECTRAL), channel == 1, tns);
            }
        }
        if (!errorResilient) {
            for (var channel = 0; channel < channels; channel++) {
                streams[0][channel].parseScalable(reader, reader, reader, reader, channel == 1, false);
            }
        }
        // TODO: the extension payloads after the channel streams of a layer (spectral band replication and dynamic
        //  range control of a scalable object) are skipped; no ISO/IEC 14496-26 stream carries them.
    }

    /**
     * Reads the {@code aac_scalable_extension_element} of a layer after the first.
     *
     * @param layer the layer index, 0 for the base layer
     * @param lastStereoBands the largest {@code max_sfb} of the stereo layers below, 0 when there is none
     * ({@code last_max_sfb_ms} of the first stereo layer)
     * @throws MediaException.Malformed if the element is corrupt
     */
    private void readExtensionLayer(int layer, int lastStereoBands) {
        var reader = header(layer);
        maxBands[layer] = reader.read(windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE ? 4 : 6);
        var channels = layerChannels[layer];
        for (var channel = 0; channel < channels; channel++) {
            streams[layer][channel].setScalableInfo(windowSequence, windowShape, maxBands[layer], grouping);
        }
        var stereo = channels == 2;
        if (stereo) {
            var start = layerChannels[layer - 1] == 2 ? maxBands[layer - 1] : 0;
            readMidSide(reader, start, maxBands[layer], streams[0][0]);
        }
        reader.checkNotOverrun();
        var monoStereo = stereo && layerChannels[layer - 1] == 1;
        var switched = stereo && (layerChannels[0] == 1 || hasCore);
        if (errorResilient) {
            for (var channel = 0; channel < channels; channel++) {
                var side = source(layer, channel, SIDE);
                if (monoStereo) {
                    extensionTns[channel] = side.readFlag();
                }
                if (switched) {
                    readDiffControl(side, channel, layer, lastStereoBands);
                }
                streams[layer][channel].parseScalable(side, source(layer, channel, RVLC), source(layer, channel, TNS),
                        source(layer, channel, SPECTRAL), channel == 1, monoStereo && extensionTns[channel]);
            }
            return;
        }
        if (monoStereo) {
            for (var channel = 0; channel < 2; channel++) {
                extensionTns[channel] = reader.readFlag();
                if (extensionTns[channel]) {
                    streams[layer][channel].readScalableTns(reader);
                }
            }
        }
        if (switched) {
            for (var channel = 0; channel < 2; channel++) {
                readDiffControl(reader, channel, layer, lastStereoBands);
            }
        }
        for (var channel = 0; channel < channels; channel++) {
            streams[layer][channel].parseScalable(reader, reader, reader, reader, channel == 1, false);
        }
    }

    /**
     * Reads {@code ms_mask_present} and the {@code ms_used} flags of the bands a stereo layer adds.
     *
     * @param reader the bit source
     * @param start the first band the layer codes the flags of
     * @param end the {@code max_sfb} of the layer
     * @param stream a stream of the first layer, for the window groups
     * @throws MediaException.Malformed if the mask mode is reserved
     */
    private void readMidSide(AacBitReader reader, int start, int end, ChannelStream stream) {
        var mode = reader.read(2);
        if (mode == 3) {
            throw new MediaException.Malformed("reserved AAC M/S mask mode");
        }
        var window = 0;
        for (var group = 0; group < stream.groupCount(); group++) {
            var length = stream.groupLength(group);
            for (var band = start; band < end; band++) {
                var used = mode == 2 || (mode == 1 && reader.readFlag());
                for (var w = window; w < window + length; w++) {
                    msUsed[w * ChannelStream.MAX_BANDS + band] = used;
                }
            }
            window += length;
        }
    }

    /**
     * Reads the {@code diff_control_lr} flags of one channel of a stereo layer above a mono layer.
     *
     * @param reader the bit source
     * @param channel the channel index, 0 for left or mono, 1 for right
     * @param layer the layer index, 0 for the base layer
     * @param lastStereoBands the largest {@code max_sfb} of the stereo layers below
     */
    private void readDiffControl(AacBitReader reader, int channel, int layer, int lastStereoBands) {
        var bands = Math.min(maxBands[layer], lastMono >= 0 ? maxBands[lastMono] : coreBands());
        diffBands = bands;
        if (windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            if (lastStereoBands == 0) {
                for (var window = 0; window < 8; window++) {
                    diffControl[channel][window] = reader.readFlag();
                }
            }
            return;
        }
        for (var band = lastStereoBands; band < bands; band++) {
            diffControl[channel][band] = !msUsed[band] && reader.readFlag();
        }
    }

    /**
     * Reads the {@code diff_control} flags of the first layer over a core: one flag per window of an eight short
     * sequence, otherwise one code of Table 4.165 per dc group, whose four bits from the most significant give the
     * flags of its four bands. The bands above the dc groups take the AAC spectrum alone.
     *
     * @param reader the bit source
     */
    private void readCoreControl(AacBitReader reader) {
        Arrays.fill(coreControl, true);
        if (windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            for (var window = 0; window < 8; window++) {
                coreControl[window] = reader.readFlag();
            }
            return;
        }
        for (var group = 0; group < dcGroups; group++) {
            var code = reader.read(2);
            int flags;
            if (code < 2) {
                flags = code == 0 ? 0 : 15;
            } else {
                code = code << 2 | reader.read(2);
                if (code == 8 || code == 9) {
                    flags = code - 1;
                } else {
                    code = code << 1 | reader.read(1);
                    flags = code <= 25 ? code - 19 : code - 17;
                }
            }
            for (var i = 0; i < 4; i++) {
                coreControl[group * 4 + i] = (flags & 8 >> i) != 0;
            }
        }
    }

    /**
     * Reads the {@code diff_control_lr} flags of one channel of a stereo first layer over a mono core. The bands
     * coded M/S take the core's {@code diff_control} flag in the mid channel and the AAC spectrum alone in the side
     * channel.
     *
     * @param reader the bit source
     * @param channel the channel index, 0 for left or mid, 1 for right or side
     */
    private void readCoreControlLr(AacBitReader reader, int channel) {
        if (windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            for (var window = 0; window < 8; window++) {
                diffControl[channel][window] = reader.readFlag();
            }
            return;
        }
        var bands = Math.min(twinVq != null ? twinVq.maxBand() : 4 * dcGroups, maxBands[0]);
        coreDiffBands = bands;
        for (var band = 0; band < bands; band++) {
            if (!msUsed[band]) {
                diffControl[channel][band] = reader.readFlag();
            } else {
                diffControl[0][band] = coreControl[band];
                diffControl[1][band] = true;
            }
        }
    }

    /**
     * {@return the {@code max_sfb} the core stands for as the last mono layer: {@code 4 * no_of_dc_groups - 1} for a
     * long window, the band holding the last of the {@code diff_short_lines} for short windows, and the {@code max_sfb}
     * of a TwinVQ core}
     */
    private int coreBands() {
        if (twinVq != null) {
            return twinVq.maxBand();
        }
        if (windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            var band = 0;
            while (band < shortOffsets.length - 1 && shortOffsets[band] < diffShortLines) {
                band++;
            }
            return band - 1;
        }
        return 4 * dcGroups - 1;
    }

    /**
     * Combines the decoded layers up to the output layer into {@link #spectra} and applies the TNS filters.
     *
     * <p>A band of a layer that codes noise substitution or intensity stereo combines with the layer below as
     * ISO/IEC 14496-3 subclause 4.5.2.2.7 tabulates: noise of a lower layer remains only where the higher layer
     * codes the band as zero, intensity bands of consecutive layers add their left channels and take the higher
     * layer's positions, and every other band that a higher layer codes with a tool replaces the lower layers. The
     * noise bands of every decoded layer are generated, those above the output layer too, so that the generator
     * follows the same sequence whatever the output layer.
     *
     * @param output the highest decoded layer to combine
     * @param present the number of layers decoded
     */
    private void combine(int output, int present) {
        var eightShort = windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE;
        windows = eightShort ? 8 : 1;
        windowLength = eightShort ? frameLength / 8 : frameLength;
        offsets = eightShort ? shortOffsets : longOffsets;
        bandCount = offsets.length - 1;
        var groups = streams[0][0];
        groupCount = groups.groupCount();
        groupStarts[0] = 0;
        for (var group = 0; group < groupCount; group++) {
            groupStarts[group + 1] = groupStarts[group] + groups.groupLength(group);
        }
        for (var layer = 0; layer < present; layer++) {
            fillNoise(layer);
        }
        var mono = layerChannels[0] == 1;
        System.arraycopy(streams[0][0].spectrum(), 0, spectra[0], 0, frameLength);
        if (mono) {
            setTools(monoTools, 0);
        } else {
            System.arraycopy(streams[0][1].spectrum(), 0, spectra[1], 0, frameLength);
            setTools(stereoTools, 0);
        }
        if (!hasCore) {
            predict();
        } else if (!mono) {
            matrix(1.0f);
            applyIntensity();
            matrix(0.5f);
        }
        if (mono) {
            for (var layer = 1; layer <= Math.min(output, lastMono); layer++) {
                addMono(layer);
            }
        }
        var stereoOutput = firstStereo >= 0 && output >= firstStereo;
        if (stereoOutput) {
            if (mono) {
                var stereo = streams[firstStereo];
                System.arraycopy(stereo[0].spectrum(), 0, core, 0, frameLength);
                System.arraycopy(stereo[1].spectrum(), 0, spectra[1], 0, frameLength);
                setTools(stereoTools, firstStereo);
                for (var layer = firstStereo + 1; layer <= output; layer++) {
                    addStereo(layer, core);
                }
                // TODO: frames 204 and 205 of er_ac211 (eight short sequences whose mono layer codes noise
                //  substitution in window groups after the first, under a stereo layer coded left and right) differ
                //  from the reference decoder by up to 240 LSB in the left channel: the reference keeps part of those
                //  mono noise bands, which this decoder clears.
                for (var group = 0; group < groupCount; group++) {
                    for (var band = 0; band < bandCount; band++) {
                        var index = group * ChannelStream.MAX_BANDS + band;
                        if (monoTools[index] != 0 || stereoTools[index] != 0) {
                            fillBand(spectra[0], group, band, 0.0f);
                        }
                    }
                }
                if (hasCore) {
                    switchCore(spectra[0]);
                }
                switchFrequencies();
            } else {
                for (var layer = 1; layer <= output; layer++) {
                    addStereo(layer, spectra[0]);
                }
                if (hasCore) {
                    // the core adds to both channels of an intensity band, after its right channel takes the left
                    applyIntensity();
                    switchCoreStereo();
                }
            }
            matrix(1.0f);
            if (mono || !hasCore) {
                applyIntensity();
            }
        } else {
            if (hasCore) {
                switchCore(spectra[0]);
            }
            if (channels() == 2) {
                System.arraycopy(spectra[0], 0, spectra[1], 0, frameLength);
            }
        }
        applyTns(output);
        previousShape = windowShape;
    }

    /**
     * Returns the tools a layer codes a band with.
     *
     * @param layer the layer index, 0 for the base layer
     * @param group the window group
     * @param band the scalefactor band index
     * @return a combination of {@link #INTENSITY}, {@link #NOISE_LEFT} and {@link #NOISE_RIGHT}, 0 when the layer
     * codes the band without a tool or does not code it
     */
    private int tools(int layer, int group, int band) {
        if (band >= maxBands[layer]) {
            return 0;
        }
        var index = group * ChannelStream.MAX_BANDS + band;
        var left = streams[layer][0].bandType(index);
        var tools = left == Codebooks.NOISE_HCB ? NOISE_LEFT : 0;
        if (layerChannels[layer] == 2) {
            var right = streams[layer][1].bandType(index);
            if (right == Codebooks.NOISE_HCB) {
                tools |= NOISE_RIGHT;
            } else if (right == Codebooks.INTENSITY_HCB || right == Codebooks.INTENSITY_HCB2) {
                tools |= INTENSITY;
            }
        }
        return tools;
    }

    /**
     * Sets the tools of every band of the combined spectrum to those of a layer, and the intensity positions of a
     * stereo layer.
     *
     * @param target {@link #monoTools} or {@link #stereoTools}
     * @param layer the layer index, 0 for the base layer
     */
    private void setTools(int[] target, int layer) {
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < bandCount; band++) {
                var tools = tools(layer, group, band);
                target[group * ChannelStream.MAX_BANDS + band] = tools;
                if ((tools & INTENSITY) != 0) {
                    takePosition(layer, group, band);
                }
            }
        }
    }

    /**
     * Takes the intensity position and phase of a band from a stereo layer.
     *
     * @param layer the layer index, 0 for the base layer
     * @param group the window group
     * @param band the scalefactor band index
     */
    private void takePosition(int layer, int group, int band) {
        var index = group * ChannelStream.MAX_BANDS + band;
        var right = streams[layer][1];
        var scale = Dequantizer.gain(-right.bandValue(index));
        intensityScales[index] = right.bandType(index) == Codebooks.INTENSITY_HCB ? scale : -scale;
    }

    /**
     * Fills the noise substitution bands of a layer with scaled pseudo-random noise, band by band, the left channel
     * before the right; a band coded with noise in both channels of a stereo layer and flagged for M/S uses one noise
     * vector for both.
     *
     * @param layer the layer index, 0 for the base layer
     */
    private void fillNoise(int layer) {
        var channels = layerChannels[layer];
        var left = streams[layer][0];
        var right = channels == 2 ? streams[layer][1] : null;
        for (var group = 0; group < groupCount; group++) {
            for (var window = groupStarts[group]; window < groupStarts[group + 1]; window++) {
                var base = window * windowLength;
                for (var band = 0; band < maxBands[layer]; band++) {
                    var index = group * ChannelStream.MAX_BANDS + band;
                    var start = base + offsets[band];
                    var end = base + offsets[band + 1];
                    var state = noiseState;
                    var leftNoise = left.bandType(index) == Codebooks.NOISE_HCB;
                    if (leftNoise) {
                        fillNoiseBand(left, index, start, end);
                    }
                    if (right != null && right.bandType(index) == Codebooks.NOISE_HCB) {
                        if (leftNoise && msUsed[groupStarts[group] * ChannelStream.MAX_BANDS + band]) {
                            noiseState = state;
                        }
                        fillNoiseBand(right, index, start, end);
                    }
                }
            }
        }
    }

    /**
     * Fills one band of one window of a channel with pseudo-random noise of the energy its scalefactor gives.
     *
     * @param stream the channel
     * @param index the index of the band among the bands of the frame
     * @param start the first line
     * @param end the line after the last one
     */
    private void fillNoiseBand(ChannelStream stream, int index, int start, int end) {
        var spectrum = stream.spectrum();
        var state = noiseState;
        var energy = 0.0;
        for (var k = start; k < end; k++) {
            state = state * 1664525 + 1013904223;
            var value = (float) state;
            spectrum[k] = value;
            energy += (double) value * value;
        }
        noiseState = state;
        var scale = (float) (Dequantizer.gain(stream.bandValue(index)) / Math.sqrt(energy));
        for (var k = start; k < end; k++) {
            spectrum[k] *= scale;
        }
    }

    /**
     * Combines a mono extension layer with the mono layers below it in {@link #spectra}{@code [0]}.
     *
     * @param layer the layer index, 0 for the base layer
     */
    private void addMono(int layer) {
        var source = streams[layer][0].spectrum();
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < maxBands[layer]; band++) {
                var index = group * ChannelStream.MAX_BANDS + band;
                var noise = tools(layer, group, band) != 0;
                if (monoTools[index] != 0) {
                    if (noise || !silent(source, group, band)) {
                        copyBand(spectra[0], source, group, band);
                        monoTools[index] = noise ? NOISE_LEFT : 0;
                    }
                } else if (noise) {
                    copyBand(spectra[0], source, group, band);
                    monoTools[index] = NOISE_LEFT;
                } else {
                    addBand(spectra[0], source, group, band);
                }
            }
        }
    }

    /**
     * Combines a stereo extension layer with the stereo layers below it.
     *
     * @param layer the layer index, 0 for the base layer
     * @param left the combined left (or mid) spectrum; the right (or side) one is {@link #spectra}{@code [1]}
     */
    private void addStereo(int layer, float[] left) {
        var sources = streams[layer];
        var right = spectra[1];
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < maxBands[layer]; band++) {
                var index = group * ChannelStream.MAX_BANDS + band;
                var combined = stereoTools[index];
                var tools = tools(layer, group, band);
                if ((tools & INTENSITY) != 0) {
                    if ((combined & INTENSITY) != 0 && tools == INTENSITY) {
                        addBand(left, sources[0].spectrum(), group, band);
                    } else {
                        copyBand(left, sources[0].spectrum(), group, band);
                        copyBand(right, sources[1].spectrum(), group, band);
                        stereoTools[index] = tools;
                    }
                    takePosition(layer, group, band);
                    continue;
                }
                if ((combined & INTENSITY) != 0) {
                    copyBand(left, sources[0].spectrum(), group, band);
                    copyBand(right, sources[1].spectrum(), group, band);
                    stereoTools[index] = tools;
                    continue;
                }
                var midSide = msUsed[groupStarts[group] * ChannelStream.MAX_BANDS + band];
                var bothSilent = silent(sources[0].spectrum(), group, band) && silent(sources[1].spectrum(), group, band);
                for (var channel = 0; channel < 2; channel++) {
                    var bit = channel == 0 ? NOISE_LEFT : NOISE_RIGHT;
                    var target = channel == 0 ? left : right;
                    var source = sources[channel].spectrum();
                    if ((tools & bit) != 0) {
                        copyBand(target, source, group, band);
                        stereoTools[index] |= bit;
                    } else if ((combined & bit) == 0) {
                        addBand(target, source, group, band);
                    } else if (!(midSide ? bothSilent : silent(source, group, band))) {
                        copyBand(target, source, group, band);
                        stereoTools[index] &= ~bit;
                    }
                }
            }
        }
    }

    /**
     * Returns whether every line of a band of a spectrum is zero, in every window of its group.
     *
     * @param spectrum the spectrum to test, window after window
     * @param group the window group
     * @param band the scalefactor band index
     * @return {@code true} when the band holds no coefficient
     */
    private boolean silent(float[] spectrum, int group, int band) {
        for (var window = groupStarts[group]; window < groupStarts[group + 1]; window++) {
            var base = window * windowLength;
            for (var line = base + offsets[band]; line < base + offsets[band + 1]; line++) {
                if (spectrum[line] != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Copies a band of a spectrum over the same band of another, in every window of its group.
     *
     * @param target the spectrum to overwrite
     * @param source the spectrum to copy
     * @param group the window group
     * @param band the scalefactor band index
     */
    private void copyBand(float[] target, float[] source, int group, int band) {
        for (var window = groupStarts[group]; window < groupStarts[group + 1]; window++) {
            var start = window * windowLength + offsets[band];
            System.arraycopy(source, start, target, start, offsets[band + 1] - offsets[band]);
        }
    }

    /**
     * Adds a band of a spectrum to the same band of another, in every window of its group.
     *
     * @param target the spectrum to add to
     * @param source the spectrum to add
     * @param group the window group
     * @param band the scalefactor band index
     */
    private void addBand(float[] target, float[] source, int group, int band) {
        for (var window = groupStarts[group]; window < groupStarts[group + 1]; window++) {
            var base = window * windowLength;
            for (var line = base + offsets[band]; line < base + offsets[band + 1]; line++) {
                target[line] += source[line];
            }
        }
    }

    /**
     * Sets every line of a band of a spectrum to a value, in every window of its group.
     *
     * @param target the spectrum to overwrite
     * @param group the window group
     * @param band the scalefactor band index
     * @param value the coefficient every line of the band takes
     */
    private void fillBand(float[] target, int group, int band, float value) {
        for (var window = groupStarts[group]; window < groupStarts[group + 1]; window++) {
            var start = window * windowLength + offsets[band];
            Arrays.fill(target, start, window * windowLength + offsets[band + 1], value);
        }
    }

    /**
     * Adds the long term prediction to the spectrum of the first layer, which {@link #spectra} holds, and appends
     * the layer's reconstruction to the history of the predictors.
     *
     * <p>A stereo layer is predicted in the left and right domain, with its intensity bands decoded and excluded
     * from the prediction.
     */
    private void predict() {
        var channels = layerChannels[0];
        if (channels == 2) {
            matrix(1.0f);
            applyIntensity();
            for (var band = 0; band < Math.min(maxBands[0], ChannelStream.LTP_MAX_BANDS); band++) {
                if ((stereoTools[band] & INTENSITY) != 0) {
                    streams[0][0].clearLtpUsed(band);
                    streams[0][1].clearLtpUsed(band);
                }
            }
        }
        for (var channel = 0; channel < channels; channel++) {
            var stream = streams[0][channel];
            var spectrum = stream.spectrum();
            System.arraycopy(spectra[channel], 0, spectrum, 0, frameLength);
            predictors[channel].predict(stream, predictionOverlaps[channel], previousShape, synthesis);
            System.arraycopy(spectrum, 0, spectra[channel], 0, frameLength);
            System.arraycopy(spectrum, 0, scratch, 0, frameLength);
            for (var window = 0; window < windows; window++) {
                stream.applyTns(scratch, window, maxBands[0]);
            }
            synthesis.synthesize(scratch, windowSequence, previousShape, windowShape, predictionOverlaps[channel],
                    reconstruction, 0);
            predictors[channel].update(reconstruction, 0);
        }
        if (channels == 2) {
            matrix(0.5f);
        }
    }

    /**
     * Runs the M/S matrix over the bands of {@link #spectra} with {@code ms_used} set and no tool in
     * {@link #stereoTools}: {@code l' = scale * (l + r)} and {@code r' = scale * (l - r)}, the inverse matrix for a
     * scale of 1.
     *
     * @param scale the scale of the sum and the difference
     */
    private void matrix(float scale) {
        for (var group = 0; group < groupCount; group++) {
            for (var window = groupStarts[group]; window < groupStarts[group + 1]; window++) {
                for (var band = 0; band < bandCount; band++) {
                    if (!msUsed[window * ChannelStream.MAX_BANDS + band]
                            || stereoTools[group * ChannelStream.MAX_BANDS + band] != 0) {
                        continue;
                    }
                    var base = window * windowLength;
                    for (var line = base + offsets[band]; line < base + offsets[band + 1]; line++) {
                        var l = spectra[0][line];
                        var r = spectra[1][line];
                        spectra[0][line] = scale * (l + r);
                        spectra[1][line] = scale * (l - r);
                    }
                }
            }
        }
    }

    /**
     * Reconstructs the intensity bands of the right channel of {@link #spectra} from the left channel; the M/S
     * flags do not invert their phase.
     */
    private void applyIntensity() {
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < bandCount; band++) {
                var index = group * ChannelStream.MAX_BANDS + band;
                if ((stereoTools[index] & INTENSITY) == 0) {
                    continue;
                }
                var scale = intensityScales[index];
                for (var window = groupStarts[group]; window < groupStarts[group + 1]; window++) {
                    var base = window * windowLength;
                    for (var line = base + offsets[band]; line < base + offsets[band + 1]; line++) {
                        spectra[1][line] = scale * spectra[0][line];
                    }
                }
            }
        }
    }

    /**
     * Runs the frequency selective switch of a mono/stereo configuration: {@link #spectra}{@code [0]} holds the
     * combined mono spectrum, {@link #core} and {@link #spectra}{@code [1]} the combined left (or mid) and right
     * (or side) spectra; the left and right outputs replace them.
     */
    private void switchFrequencies() {
        var monoSpectrum = spectra[0];
        var left = core;
        var right = spectra[1];
        for (var window = 0; window < windows; window++) {
            var base = window * windowLength;
            for (var band = 0; band < bandCount; band++) {
                var ms = msUsed[window * ChannelStream.MAX_BANDS + band];
                var addLeft = ms || !switchedOff(0, window, band);
                var addRight = !ms && !switchedOff(1, window, band);
                var scale = ms ? 1.0f : 2.0f;
                for (var line = base + offsets[band]; line < base + offsets[band + 1]; line++) {
                    var m = scale * monoSpectrum[line];
                    var l = left[line];
                    var r = right[line];
                    monoSpectrum[line] = addLeft ? m + l : l;
                    right[line] = addRight ? m + r : r;
                }
            }
            for (var line = base + offsets[bandCount]; line < base + windowLength; line++) {
                monoSpectrum[line] = left[line];
            }
        }
    }

    /**
     * Runs the frequency selective switch of a mono first layer over a core: the core spectrum is added to the
     * combined mono spectrum in the bands of the dc groups (the {@code diff_short_lines} of each short window) whose
     * {@code diff_control} flag is clear.
     *
     * @param mono the combined mono spectrum, updated in place
     */
    private void switchCore(float[] mono) {
        for (var window = 0; window < windows; window++) {
            var base = window * windowLength;
            if (windows == 8) {
                if (!coreControl[window]) {
                    for (var line = base; line < base + Math.min(diffShortLines, windowLength); line++) {
                        mono[line] += coreSpectrum[line];
                    }
                }
                continue;
            }
            for (var band = 0; band < Math.min(4 * dcGroups, bandCount); band++) {
                if (coreControl[band]) {
                    continue;
                }
                for (var line = base + offsets[band]; line < base + offsets[band + 1]; line++) {
                    mono[line] += coreSpectrum[line];
                }
            }
        }
    }

    /**
     * Runs the frequency selective switch of a stereo first layer over a mono core, before the inverse M/S matrix:
     * the core spectrum, doubled in the bands coded left and right, is added to a channel where its
     * {@code diff_control_lr} flag is clear, and in the bands coded M/S to the mid channel alone. Over short windows
     * the switch covers the {@code diff_short_lines} of each window, and an M/S band takes the core's flag of its
     * window.
     */
    private void switchCoreStereo() {
        var shortWindows = windows == 8;
        for (var window = 0; window < windows; window++) {
            var base = window * windowLength;
            var top = base + (shortWindows ? Math.min(diffShortLines, windowLength) : windowLength);
            for (var band = 0; band < bandCount; band++) {
                var midSide = msUsed[window * ChannelStream.MAX_BANDS + band];
                var scale = midSide ? 1.0f : 2.0f;
                for (var channel = 0; channel < 2; channel++) {
                    boolean off;
                    if (!shortWindows) {
                        off = band >= coreDiffBands || diffControl[channel][band];
                    } else if (midSide) {
                        off = channel == 1 || coreControl[window];
                    } else {
                        off = diffControl[channel][window];
                    }
                    if (off) {
                        continue;
                    }
                    var target = spectra[channel];
                    var end = Math.min(base + offsets[band + 1], top);
                    for (var line = base + offsets[band]; line < end; line++) {
                        target[line] += scale * coreSpectrum[line];
                    }
                }
            }
        }
    }

    /**
     * Returns whether the frequency selective switch takes a band of a channel from the stereo spectrum alone.
     *
     * @param channel the channel index, 0 for left or mono, 1 for right
     * @param window the window index, below 8
     * @param band the scalefactor band index
     * @return {@code true} when {@code diff_control_lr} is set or the band lies above those it covers
     */
    private boolean switchedOff(int channel, int window, int band) {
        if (band >= diffBands) {
            return true;
        }
        return windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE ? diffControl[channel][window]
                : diffControl[channel][band];
    }

    /**
     * Applies the TNS filters of the first layer and of the first stereo layer after a mono layer to the combined
     * spectra.
     *
     * @param output the highest combined layer
     */
    private void applyTns(int output) {
        var channels = channels();
        var limit = maxBands[output];
        var monoTop = lastMono >= 0 ? maxBands[Math.min(output, lastMono)] : limit;
        var monoBands = lastMono >= 0 ? maxBands[lastMono] : 0;
        var extension = firstStereo >= 1 ? streams[firstStereo] : null;
        for (var channel = 0; channel < channels; channel++) {
            var main = streams[0][layerChannels[0] == 1 ? 0 : channel];
            // a layer below the stereo layers decodes as if they were absent, so their TNS data leaves it alone
            var hasExtension = extension != null && output >= firstStereo && extensionTns[channel];
            if (hasExtension) {
                limit = monoTop;
            }
            for (var window = 0; window < windows; window++) {
                if (!hasExtension || (extension[channel].tnsFilterCount(window) > 0
                        && monoBands <= extension[channel].tnsFirstBand(window))) {
                    main.applyTns(spectra[channel], window, limit);
                }
            }
        }
        if (extension != null && output >= firstStereo) {
            for (var channel = 0; channel < channels; channel++) {
                if (extensionTns[channel]) {
                    for (var window = 0; window < windows; window++) {
                        extension[channel].applyTns(spectra[channel], window, maxBands[output]);
                    }
                }
            }
        }
    }
}

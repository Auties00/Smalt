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
     * The seed of the noise generator.
     */
    private static final int NOISE_SEED = 0x1F2E3D4C;

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
     * The noise generator seed of each band and window of the left channel of the stereo layer being filled,
     * indexed {@code window * MAX_BANDS + band}.
     */
    private final int[] noiseSeeds = new int[8 * ChannelStream.MAX_BANDS];

    /**
     * Creates a decoder for the layers of a scalable audio object.
     *
     * @param configs the configuration of each elementary stream, in stream order: one per layer, or with
     * {@code epConfig} 1 one per category instance
     * @param maxLayer the highest layer to output, or -1 for the last one
     * @param kernel the inverse quantization kernel
     * @throws MediaException.Unsupported if a layer depends on a core coder
     * @throws MediaException.Malformed if the configurations do not describe the layers of one object
     */
    ScalableDecoder(List<AudioSpecificConfig> configs, int maxLayer, AacKernel kernel) {
        var first = configs.getFirst();
        // TODO: CELP (object types 8 and 24) and TwinVQ (7 and 21) core layers, which the frequency selective switch
        //  combines with the AAC layers (ISO/IEC 14496-3 subclauses 4.5.2.2.5 and 4.5.2.2.6), need decoders of
        //  those object types, which Smalt does not have.
        for (var config : configs) {
            if (config.coreCoderDelay() >= 0) {
                throw new MediaException.Unsupported("aac scalable core",
                        "scalable AAC over a CELP or TwinVQ core layer is not supported");
            }
        }
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
        this.separateCategories = errorResilient && first.epConfig() == 1;
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
     * @param count the number of access units
     * @throws MediaException.Malformed if the first layer is missing or a layer is corrupt
     */
    void decode(byte[][] units, int count) {
        var present = 0;
        var unit = 0;
        for (var layer = 0; layer < layerCount; layer++) {
            if (separateCategories) {
                if (unit + instanceCounts[layer] > count) {
                    break;
                }
                for (var i = 0; i < instanceCounts[layer]; i++) {
                    var data = units[unit++];
                    instanceReaders[layer][i].reset(data, 0, data.length);
                }
            } else {
                if (unit == count) {
                    break;
                }
                var data = units[unit++];
                layerReaders[layer].reset(data, 0, data.length);
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
        combine(Math.min(outputLayer, present - 1));
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
        windowSequence = reader.read(2);
        windowShape = reader.read(1);
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
        }
        reader.checkNotOverrun();
        for (var channel = 0; channel < channels; channel++) {
            var stream = streams[0][channel];
            var side = source(0, channel, SIDE);
            var tns = side.readFlag();
            if (!errorResilient && tns) {
                stream.readScalableTns(side);
            }
            if (side.readFlag()) {
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
        var switched = stereo && layerChannels[0] == 1;
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
        var bands = Math.min(maxBands[layer], maxBands[lastMono]);
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
     * Combines the decoded layers up to the output layer into {@link #spectra} and applies the TNS filters.
     *
     * <p>A band of a layer that codes noise substitution or intensity stereo combines with the layer below as
     * ISO/IEC 14496-3 subclause 4.5.2.2.7 tabulates: noise of a lower layer remains only where the higher layer
     * codes the band as zero, intensity bands of consecutive layers add their left channels and take the higher
     * layer's positions, and every other band that a higher layer codes with a tool replaces the lower layers.
     *
     * @param output the highest decoded layer to combine
     */
    private void combine(int output) {
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
        for (var layer = 0; layer <= output; layer++) {
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
        predict();
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
                for (var group = 0; group < groupCount; group++) {
                    for (var band = 0; band < bandCount; band++) {
                        var index = group * ChannelStream.MAX_BANDS + band;
                        if (monoTools[index] != 0 || stereoTools[index] != 0) {
                            fillBand(spectra[0], group, band, 0.0f);
                        }
                    }
                }
                switchFrequencies();
            } else {
                for (var layer = 1; layer <= output; layer++) {
                    addStereo(layer, spectra[0]);
                }
            }
            matrix(1.0f);
            applyIntensity();
        } else if (channels() == 2) {
            System.arraycopy(spectra[0], 0, spectra[1], 0, frameLength);
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
     * Fills the noise substitution bands of a layer with scaled pseudo-random noise; a band coded with noise in
     * both channels of a stereo layer and flagged for M/S uses one noise vector for both.
     *
     * @param layer the layer index, 0 for the base layer
     */
    private void fillNoise(int layer) {
        var channels = layerChannels[layer];
        for (var channel = 0; channel < channels; channel++) {
            var stream = streams[layer][channel];
            var spectrum = stream.spectrum();
            for (var group = 0; group < groupCount; group++) {
                for (var band = 0; band < maxBands[layer]; band++) {
                    var index = group * ChannelStream.MAX_BANDS + band;
                    if (stream.bandType(index) != Codebooks.NOISE_HCB) {
                        continue;
                    }
                    var reuse = channel == 1 && streams[layer][0].bandType(index) == Codebooks.NOISE_HCB
                            && msUsed[groupStarts[group] * ChannelStream.MAX_BANDS + band];
                    var gain = Dequantizer.gain(stream.bandValue(index));
                    for (var window = groupStarts[group]; window < groupStarts[group + 1]; window++) {
                        var seedIndex = window * ChannelStream.MAX_BANDS + band;
                        int state;
                        if (reuse) {
                            state = noiseSeeds[seedIndex];
                        } else {
                            state = noiseState;
                            noiseSeeds[seedIndex] = state;
                        }
                        var start = window * windowLength + offsets[band];
                        var end = window * windowLength + offsets[band + 1];
                        var energy = 0.0;
                        for (var k = start; k < end; k++) {
                            state = state * 1664525 + 1013904223;
                            var value = (float) state;
                            spectrum[k] = value;
                            energy += (double) value * value;
                        }
                        if (!reuse) {
                            noiseState = state;
                        }
                        var scale = (float) (gain / Math.sqrt(energy));
                        for (var k = start; k < end; k++) {
                            spectrum[k] *= scale;
                        }
                    }
                }
            }
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
            var hasExtension = extension != null && extensionTns[channel];
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

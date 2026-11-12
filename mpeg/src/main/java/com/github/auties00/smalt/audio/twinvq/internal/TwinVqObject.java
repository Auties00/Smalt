/* Ports the TwinVQ frame decoder of the ISO/IEC 14496-3 reference software (ISO/IEC 14496-5, src_tf: tvqAUDecode.c,
   ntt_headerdec.c, ntt_vq_decoder.c, ntt_scale_vq_decoder.c, ntt_TfInit.c, ntt_scale_init.c), developed by
   Naoki Iwakami, Akio Jin, Takehiro Moriya and Kazuaki Chikira (NTT), Takeshi Norimatsu, Mineo Tsushima and Tomokazu
   Ishikawa (Matsushita Electric Industrial Co Ltd.), and edited by them. ISO/IEC gives users of the MPEG-2 NBC/MPEG-4
   Audio standards free license to this software module or modifications thereof for use in hardware or software
   products claiming conformance to the MPEG-2 NBC/MPEG-4 Audio standards. Copyright (c) 1996-1997.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.twinvq.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.AacKernels;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.ChannelStream;
import com.github.auties00.smalt.audio.aac.internal.LongTermPredictor;
import com.github.auties00.smalt.audio.aac.internal.SamplingTables;
import com.github.auties00.smalt.audio.aac.internal.Synthesis;
import com.github.auties00.smalt.audio.aac.internal.ep.ErrorProtectionDecoder;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;
import java.util.List;

/**
 * Decodes a TwinVQ audio object from the access units of its elementary streams: the base layer and the scalable
 * layers that add to its spectrum (ISO/IEC 14496-3 subclause 4.5.2.5), through the long term prediction and TNS tools
 * of AAC into the time domain.
 *
 * <p>The number of bits of a frame gives the number of interleaved vectors and the active band, so every access unit
 * of a layer codes its own bitrate. With {@code epConfig} 1 or 3 each layer comes as two category instances, each
 * filled up to a byte boundary; the bits of the side information tell which of the two possible sizes of the unaligned
 * frame the instances hold.
 *
 * <p>Instances are not thread-safe.
 */
public final class TwinVqObject {
    /**
     * The number of windows of an eight short sequence.
     */
    private static final int SHORT_WINDOWS = 8;

    /**
     * The number of category instances of a layer of a stream with {@code epConfig} 1 or 3.
     */
    private static final int CATEGORIES = 2;

    /**
     * The {@code scale_factor_grouping} that puts every short window in one group, which a layer uses when no layer
     * below it transmits one (subclause 4.5.2.5.3.3).
     */
    private static final int WHOLE_GROUP = 0x7F;

    /**
     * The contents of an access unit that is absent.
     */
    private static final byte[] NO_DATA = new byte[0];

    private final TwinVqConfig base;

    private final int channels;

    private final int frameLength;

    private final int sampleRate;

    private final int rateKhz;

    /**
     * The number of layers of the object, and of the layers decoded.
     */
    private final int layerCount;

    private final int decodedLayers;

    /**
     * The elementary streams of the object, the units {@link #decode} expects.
     */
    private final int streams;

    /**
     * Whether each layer comes in two category instances.
     */
    private final boolean split;

    /**
     * The error protection decoder of each elementary stream, or {@code null} without error protection.
     */
    private final ErrorProtectionDecoder[] protection;

    private final TwinVqLayer[] layers;

    /**
     * The window tools of each channel: TNS and long term prediction belong to the base layer.
     */
    private final ChannelStream[] tools;

    private final LongTermPredictor[] predictors;

    private final Synthesis synthesis;

    private final float[][] predictionOverlaps;

    private final float[][] overlaps;

    private final float[] scratch;

    private final float[] reconstruction;

    private final int[] longOffsets;

    private final int[] shortOffsets;

    /**
     * The window sequence and shape of the current frame, and the window shape of the previous frame.
     */
    private int windowSequence;

    private int windowShape;

    private int previousShape;

    /**
     * The {@code max_sfb} each layer stands for in the current frame.
     */
    private final int[] maxBands;

    /**
     * The highest layer the current frame decoded, whose {@code max_sfb} stands for the frame.
     */
    private int topLayer;

    /**
     * The {@code ms_used} flag of each window and band, indexed {@code window * MAX_BANDS + band}.
     */
    private final boolean[] msUsed = new boolean[SHORT_WINDOWS * ChannelStream.MAX_BANDS];

    private int grouping;

    /**
     * The number of bits of the unaligned frame of each layer, once a frame has told.
     */
    private final int[] frameBits;

    /**
     * Whether the LSP quantizers predict from a previous frame: the base layer has its own state, the scalable layers
     * share one.
     */
    private boolean baseHistory;

    private boolean scalableHistory;

    /**
     * The combined spectrum of each channel.
     */
    private final double[][] combined;

    private final float[][] spectra;

    /**
     * The reader of the frame fields, and the buffer the category instances of a layer are joined in.
     */
    private final AacBitReader reader = new AacBitReader(NO_DATA);

    private byte[] joined = new byte[512];

    /**
     * The units of a frame after error protection decoding, and their sizes.
     */
    private final byte[][] units;

    private final int[] sizes;

    /**
     * The number of units {@link #protectedUnits} found.
     */
    private int protectedCount;

    /**
     * The number of frames of the EP frame the last {@link #decodeCore} call took, 0 when it took none.
     */
    private int coreFrames;

    /**
     * The frame of that EP frame {@link #decodeNextCore()} decodes next.
     */
    private int nextCoreFrame;

    /**
     * The number of streams that had an access unit in the last {@link #decodeCore} call.
     */
    private int coreAvailable;

    /**
     * Creates the decoder of an object.
     *
     * @param configs the {@code AudioSpecificConfig} of each elementary stream, in stream order, the base layer first
     * @param maxLayer the highest layer to decode, or -1 for every layer
     * @param limits the resource limits
     * @throws MediaException.Malformed if a configuration is invalid or the configurations do not describe the layers
     * of one object
     * @throws MediaException.Unsupported if a configuration is not of a TwinVQ object of one or two channels, or maps
     * error protection classes without direct mapping
     * @throws MediaException.LimitExceeded if the object has more channels than the limits allow
     */
    public TwinVqObject(List<byte[]> configs, int maxLayer, MediaLimits limits) {
        if (configs.isEmpty()) {
            throw new IllegalArgumentException("no configuration");
        }
        var parsed = new TwinVqConfig[configs.size()];
        for (var i = 0; i < parsed.length; i++) {
            parsed[i] = TwinVqConfig.parse(configs.get(i));
            if (parsed[i].objectType() != parsed[0].objectType() || parsed[i].sampleRate() != parsed[0].sampleRate()
                    || parsed[i].channels() != parsed[0].channels()
                    || parsed[i].frameLength() != parsed[0].frameLength()
                    || parsed[i].epConfig() != parsed[0].epConfig()) {
                throw new MediaException.Malformed("TwinVQ streams of different configurations");
            }
        }
        this.base = parsed[0];
        this.channels = base.channels();
        LimitChecks.checkChannels(limits, channels);
        this.frameLength = base.frameLength();
        this.sampleRate = base.sampleRate();
        this.rateKhz = sampleRate / 1000;
        this.split = base.epConfig() == 1 || base.epConfig() == 3;
        var perLayer = base.epConfig() == 1 ? CATEGORIES : 1;
        this.layerCount = parsed.length / perLayer;
        if (layerCount == 0 || parsed.length % perLayer != 0) {
            throw new MediaException.Malformed("ER TwinVQ layers with epConfig 1 in an odd number of streams");
        }
        if (layerCount > 8) {
            throw new MediaException.Malformed("TwinVQ object with " + layerCount + " layers");
        }
        this.streams = parsed.length;
        this.decodedLayers = maxLayer < 0 ? layerCount : Math.min(maxLayer + 1, layerCount);
        if (base.epConfig() >= 2) {
            this.protection = new ErrorProtectionDecoder[parsed.length];
            for (var i = 0; i < parsed.length; i++) {
                if (parsed[i].errorProtection().concatenatedFrames() != base.errorProtection().concatenatedFrames()) {
                    throw new MediaException.Malformed("TwinVQ streams with different error protection framing");
                }
                protection[i] = new ErrorProtectionDecoder(parsed[i].errorProtection(), limits);
            }
        } else {
            this.protection = null;
        }
        var kernel = AacKernels.INSTANCE;
        var cosines = new double[2 * frameLength + 1];
        var step = Math.PI / (4 * frameLength);
        for (var i = 0; i < 2 * frameLength; i++) {
            cosines[i] = StrictMath.cos(step * i);
        }
        this.layers = new TwinVqLayer[layerCount];
        for (var layer = 0; layer < layerCount; layer++) {
            layers[layer] = new TwinVqLayer(layer > 0, channels, frameLength, sampleRate, cosines);
        }
        var table = SamplingTables.tableIndexFor(sampleRate);
        this.longOffsets = SamplingTables.longOffsets(table, frameLength);
        this.shortOffsets = SamplingTables.shortOffsets(table, frameLength);
        this.tools = new ChannelStream[channels];
        this.predictors = new LongTermPredictor[channels];
        for (var channel = 0; channel < channels; channel++) {
            tools[channel] = new ChannelStream(frameLength, table, AudioSpecificConfig.AOT_SCALABLE, kernel);
            predictors[channel] = new LongTermPredictor(frameLength, kernel);
        }
        this.synthesis = new Synthesis(frameLength, kernel);
        this.predictionOverlaps = new float[channels][frameLength];
        this.overlaps = new float[channels][frameLength];
        this.scratch = new float[frameLength];
        this.reconstruction = new float[frameLength];
        this.maxBands = new int[layerCount];
        this.frameBits = new int[layerCount];
        this.combined = new double[channels][frameLength];
        this.spectra = new float[channels][frameLength];
        var unitCount = perLayer * layerCount;
        if (base.epConfig() == 3) {
            unitCount = CATEGORIES * layerCount;
        }
        this.units = new byte[unitCount][];
        this.sizes = new int[unitCount];
    }

    /**
     * {@return the number of channels}
     */
    public int channels() {
        return channels;
    }

    /**
     * {@return the sampling frequency, in hertz}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the number of samples of a frame, per channel}
     */
    public int frameLength() {
        return frameLength;
    }

    /**
     * {@return the number of elementary streams of the object}
     */
    public int streams() {
        return streams;
    }

    /**
     * {@return the number of coding layers of the object}
     */
    public int layers() {
        return layerCount;
    }

    /**
     * {@return the most frames one access unit of each stream decodes to: above 1 when the error protection tool
     * concatenates frames}
     */
    public int framesPerUnit() {
        return protection == null ? 1 : base.errorProtection().concatenatedFrames();
    }

    /**
     * {@return the window sequence of the last decoded frame}
     */
    public int windowSequence() {
        return windowSequence;
    }

    /**
     * {@return the window shape of the last decoded frame}
     */
    public int windowShape() {
        return windowShape;
    }

    /**
     * {@return the {@code max_sfb} the highest layer the last frame decoded stands for, 0 for a silent frame}
     */
    public int maxBand() {
        return maxBands[topLayer];
    }

    /**
     * Returns the spectrum of a channel of the last decoded frame, before the TNS filters.
     *
     * @param channel the channel, from 0
     * @return the spectrum, overwritten by the next frame
     */
    public float[] spectrum(int channel) {
        return spectra[channel];
    }

    /**
     * Runs the TNS filters the base layer of the last decoded frame carries over a spectrum.
     *
     * @param channel the channel whose filters to run
     * @param target the spectrum to filter in place
     * @param bands the number of bands the filters may cover
     */
    public void applyTns(int channel, float[] target, int bands) {
        var windows = windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE ? SHORT_WINDOWS : 1;
        for (var window = 0; window < windows; window++) {
            tools[channel].applyTns(target, window, bands);
        }
    }

    /**
     * Returns whether the base layer of the last decoded frame carries TNS data for a channel.
     *
     * @param channel the channel, from 0
     * @return {@code true} when it does
     */
    public boolean tnsPresent(int channel) {
        return tools[channel].tnsPresent();
    }

    /**
     * Decodes the access units of one frame of each elementary stream, or of one EP frame with the error protection
     * tool, into time samples.
     *
     * @param data the access units, in stream order; fewer than {@link #streams()} decode the layers they hold up to
     * the first missing one
     * @param lengths the length of each access unit
     * @param count the number of access units
     * @param out receives the samples of each channel, {@link #frameLength()} per frame back to back, scaled so that
     * 16-bit full scale spans {@code [-1, 1]}
     * @return the number of frames decoded
     * @throws MediaException.Malformed if an access unit is too short for its fields, or the error protection tool
     * rejects it
     */
    public int decode(byte[][] data, int[] lengths, int count, float[][] out) {
        if (protection == null) {
            decodeSpectrum(data, lengths, Math.min(count, streams));
            synthesize(out, 0);
            return 1;
        }
        var available = Math.min(count, streams);
        for (var i = 0; i < available; i++) {
            protection[i].decode(data[i], 0, lengths[i]);
        }
        var frames = protection[0].frames();
        for (var frame = 0; frame < frames; frame++) {
            decodeSpectrum(protectedUnits(available, frame), sizes, protectedCount);
            synthesize(out, frame * frameLength);
        }
        return frames;
    }

    /**
     * Decodes the access units of one core frame into {@link #spectrum(int)}, without the TNS filters and the
     * filterbank of the output, for the AAC layers above a TwinVQ core. When the error protection tool concatenates
     * frames, the units hold an EP frame and this decodes its first frame; {@link #decodeNextCore()} decodes the
     * others.
     *
     * @param data the access units, in stream order
     * @param lengths the length of each access unit
     * @param count the number of access units; 0 makes the frame silent, of long windows
     * @throws MediaException.Malformed if an access unit is too short for its fields, or the error protection tool
     * rejects it
     */
    public void decodeCore(byte[][] data, int[] lengths, int count) {
        coreFrames = 0;
        if (protection == null) {
            decodeSpectrum(data, lengths, Math.min(count, streams));
            previousShape = windowShape;
            return;
        }
        var available = Math.min(count, streams);
        for (var i = 0; i < available; i++) {
            protection[i].decode(data[i], 0, lengths[i]);
        }
        decodeSpectrum(protectedUnits(available, 0), sizes, protectedCount);
        previousShape = windowShape;
        if (available > 0) {
            coreFrames = protection[0].frames();
            nextCoreFrame = 1;
            coreAvailable = available;
        }
    }

    /**
     * Decodes the next frame of the EP frame the last {@link #decodeCore} call took into {@link #spectrum(int)}, as
     * that call decodes its first.
     *
     * @return {@code false}, leaving the spectrum as it is, when that EP frame has no frame left, or the last call
     * took no EP frame
     * @throws MediaException.Malformed if the frame is too short for its fields
     */
    public boolean decodeNextCore() {
        if (nextCoreFrame >= coreFrames) {
            return false;
        }
        var frame = nextCoreFrame++;
        decodeSpectrum(protectedUnits(coreAvailable, frame), sizes, protectedCount);
        previousShape = windowShape;
        return true;
    }

    /**
     * Reassembles the units of one EP frame from the error protection decoders.
     *
     * @param available the number of streams with an access unit
     * @param frame the EP frame
     * @return the units, with their sizes in {@link #sizes} and their number in {@link #protectedCount}; the units of
     * layers from the first damaged one on are left out
     */
    private byte[][] protectedUnits(int available, int frame) {
        var intact = 0;
        while (intact < available && protection[intact].intact(frame)) {
            intact++;
        }
        var unitCount = 0;
        for (var i = 0; i < intact; i++) {
            var stream = protection[i];
            if (split) {
                if (stream.classCount() != CATEGORIES) {
                    throw new MediaException.Malformed("ER TwinVQ with epConfig 3 and " + stream.classCount()
                            + " error protection classes");
                }
                for (var category = 0; category < CATEGORIES; category++) {
                    units[unitCount] = stream.classData(frame, category);
                    sizes[unitCount] = (stream.classBits(frame, category) + 7) >>> 3;
                    unitCount++;
                }
            } else {
                var bytes = (int) Math.min((stream.frameBits(frame) + 7) >>> 3, Integer.MAX_VALUE - 8);
                var target = units[unitCount] == null || units[unitCount].length < bytes
                        ? new byte[Math.max(bytes, 1)] : units[unitCount];
                sizes[unitCount] = stream.concatenate(frame, target);
                units[unitCount] = target;
                unitCount++;
            }
        }
        protectedCount = unitCount;
        return units;
    }

    /**
     * Runs the TNS filters and the filterbank of the output over {@link #spectra}.
     *
     * @param out receives the samples of each channel
     * @param offset the index of the first sample
     */
    private void synthesize(float[][] out, int offset) {
        var top = maxBand();
        for (var channel = 0; channel < channels; channel++) {
            applyTns(channel, spectra[channel], top);
            synthesis.synthesize(spectra[channel], windowSequence, previousShape, windowShape, overlaps[channel],
                    out[channel], offset);
        }
        previousShape = windowShape;
    }

    /**
     * Decodes the spectrum of one frame into {@link #spectra}; a frame without its base layer is silent.
     *
     * @param data the units
     * @param lengths their sizes
     * @param count their number
     */
    private void decodeSpectrum(byte[][] data, int[] lengths, int count) {
        var perLayer = split ? CATEGORIES : 1;
        var present = Math.min(count / perLayer, decodedLayers);
        topLayer = 0;
        if (present == 0 || lengths[0] == 0 || data[0] == null) {
            windowSequence = ChannelStream.ONLY_LONG_SEQUENCE;
            Arrays.fill(maxBands, 0);
            for (var channel = 0; channel < channels; channel++) {
                tools[channel].setScalableInfo(windowSequence, windowShape, 0, 0);
                Arrays.fill(spectra[channel], 0);
            }
            return;
        }
        for (var channel = 0; channel < channels; channel++) {
            Arrays.fill(combined[channel], 0);
        }
        Arrays.fill(maxBands, 0);
        Arrays.fill(msUsed, false);
        grouping = WHOLE_GROUP;
        decodeBaseLayer(data, lengths);
        for (var channel = 0; channel < channels; channel++) {
            var target = spectra[channel];
            var source = combined[channel];
            for (var line = 0; line < frameLength; line++) {
                target[line] = (float) source[line];
            }
        }
        predict();
        for (var layer = 1; layer < present; layer++) {
            if (lengths[layer * perLayer] == 0 || data[layer * perLayer] == null) {
                break;
            }
            decodeScalableLayer(layer, data, lengths);
            topLayer = layer;
        }
    }

    /**
     * Points {@link #reader} at the access unit of a layer, joining its category instances.
     *
     * @param layer the layer index, 0 for the base layer
     * @param data the units
     * @param lengths their sizes
     * @return the number of bytes of the first category instance, or 0 when the layer comes in one unit
     */
    private int openLayer(int layer, byte[][] data, int[] lengths) {
        if (!split) {
            reader.reset(data[layer], 0, lengths[layer]);
            return 0;
        }
        var first = data[layer * CATEGORIES];
        var second = data[layer * CATEGORIES + 1];
        var firstLength = first == null ? 0 : lengths[layer * CATEGORIES];
        var secondLength = second == null ? 0 : lengths[layer * CATEGORIES + 1];
        if (joined.length < firstLength + secondLength) {
            joined = new byte[firstLength + secondLength];
        }
        if (firstLength > 0) {
            System.arraycopy(first, 0, joined, 0, firstLength);
        }
        if (secondLength > 0) {
            System.arraycopy(second, 0, joined, firstLength, secondLength);
        }
        reader.reset(joined, 0, firstLength + secondLength);
        return firstLength;
    }

    /**
     * Returns the number of bands a frequency line falls within.
     *
     * @param offsets the band offsets of a window
     * @param line the highest line the layer codes
     * @return the number of bands, at least 1
     */
    private static int bandsCovering(int[] offsets, int line) {
        var bands = 1;
        while (bands < offsets.length - 1 && offsets[bands] < line) {
            bands++;
        }
        return bands;
    }

    /**
     * Reads the {@code ms_mask_present} of a stereo layer and the {@code ms_used} flags of the bands it adds.
     *
     * @param readGrouping whether a mask mode of 1 also carries the {@code scale_factor_grouping}
     * @param eightShort whether the frame is an eight short sequence
     * @param start the first band the layer codes the flags of
     * @param end the {@code max_sfb} of the layer
     * @throws MediaException.Malformed if the mask mode is reserved
     */
    private void readMidSide(boolean readGrouping, boolean eightShort, int start, int end) {
        var mode = reader.read(2);
        if (mode == 3) {
            throw new MediaException.Malformed("reserved TwinVQ M/S mask mode");
        }
        if (mode == 1 && eightShort && readGrouping) {
            grouping = reader.read(7);
        }
        var window = 0;
        var windows = eightShort ? SHORT_WINDOWS : 1;
        var mask = 1 << 6;
        while (window < windows) {
            var length = 1;
            if (eightShort) {
                while (window + length < windows && (grouping & mask) != 0) {
                    length++;
                    mask >>= 1;
                }
                mask >>= 1;
            }
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
     * Decodes the base layer of a frame into {@link #combined}.
     */
    private void decodeBaseLayer(byte[][] data, int[] lengths) {
        var layer = layers[0];
        var firstLength = openLayer(0, data, lengths);
        int total;
        int side;
        if (split) {
            var size = (int) reader.bitsLeft();
            total = frameBits[0] != 0 ? frameBits[0] : size - 8;
            side = readBaseHeader(layer, total);
            var exact = side % 8 == 0 ? size : size - 8;
            if (exact != total) {
                total = exact;
                openLayer(0, data, lengths);
                side = readBaseHeader(layer, total);
            }
        } else {
            total = lengths[0] * 8;
            side = readBaseHeader(layer, total);
        }
        frameBits[0] = total;
        var eightShort = windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE;
        layer.readBaseSide(reader);
        readBody(layer, eightShort, total, side, firstLength);
        reader.checkNotOverrun();
        var lines = layer.reconstructBase(eightShort, baseHistory);
        baseHistory = true;
        addLayer(lines, 0, eightShort);
    }

    /**
     * Reads the window fields, the M/S mask and the tools of the base layer and the flags of its element, leaving the
     * reader after the flags.
     *
     * @param layer the tools of the base layer
     * @param total the number of bits of the frame
     * @return the bits of the header and the side information, those of the shape code excluded
     */
    private int readBaseHeader(TwinVqLayer layer, int total) {
        windowSequence = reader.read(2);
        windowShape = reader.read(1);
        var eightShort = windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE;
        var upper = baseBand(total);
        layer.setBaseBand(upper);
        var line = (int) ((float) upper * (float) (eightShort ? frameLength / SHORT_WINDOWS : frameLength));
        var bands = bandsCovering(eightShort ? shortOffsets : longOffsets, line);
        maxBands[0] = bands;
        Arrays.fill(msUsed, false);
        grouping = WHOLE_GROUP;
        if (channels == 2) {
            readMidSide(true, eightShort, 0, bands);
        }
        for (var channel = 0; channel < channels; channel++) {
            var tool = tools[channel];
            tool.setScalableInfo(windowSequence, windowShape, bands, eightShort ? grouping : 0);
            if (reader.readFlag()) {
                tool.readScalableLtp(reader);
            }
            if (reader.readFlag()) {
                tool.readScalableTns(reader);
            }
        }
        var header = (int) reader.bitPosition();
        return header + layer.readBaseFlags(reader, eightShort);
    }

    /**
     * Reads the normalization fields and the shape code of a layer, in the order of its syntax.
     *
     * @param layer the tools of the layer
     * @param eightShort whether the frame is an eight short sequence
     * @param total the number of bits of the frame
     * @param side the bits of the header and the side information
     * @param firstLength the number of bytes of the first category instance, or 0 when the layer comes in one unit
     */
    private void readBody(TwinVqLayer layer, boolean eightShort, int total, int side, int firstLength) {
        if (!base.errorResilient()) {
            layer.readPlain(reader, eightShort, total - side);
            return;
        }
        layer.readErrorResilientSide(reader, eightShort);
        if (split) {
            reader.checkNotOverrun();
            if (reader.bitPosition() > firstLength * 8L) {
                throw new MediaException.Malformed("ER TwinVQ side information past its category instance");
            }
            reader.seek(firstLength * 8L);
        }
        layer.readErrorResilientShapes(reader, total - side);
    }

    /**
     * {@return the upper limit of the active band of the base layer, as a ratio of the Nyquist frequency}
     *
     * @param bits the number of bits of the frame
     */
    private double baseBand(int bits) {
        var perChannel = (int) ((long) bits * sampleRate / frameLength / channels);
        var upper = (int) Math.min(100000, 95L * perChannel / rateKhz);
        upper = (upper * 16384 + 1562) / 3125;
        return upper / 16384.0 / 32.0;
    }

    /**
     * Sets the active bands of a scalable layer from the bitrates of the layers up to it (subclause 4.6.5.4.4).
     *
     * @param layer the layer index, from 1
     */
    private void setScalableBands(int layer) {
        var rate = (float) sampleRate;
        var total = (double) (frameBits[0] * rate / (float) frameLength / (float) channels);
        var current = 0.0f;
        for (var i = 1; i <= layer; i++) {
            current = frameBits[i] * rate / frameLength / channels;
            total += current;
        }
        var upper = (int) Math.min(100000, (long) ((total * 100) / rateKhz));
        upper = (upper * 1024 * 16 + 1562) / 3125;
        var width = (int) Math.min(100000, (long) (int) current * 130 / rateKhz);
        width = (width * 1024 * 16 + 1562) / 3125;
        var spacing = (upper - width) / 3;
        if (width < spacing) {
            width = (int) (upper / 4.0);
        }
        var qsample = width / 16384.0 / 32.0;
        var bias = Math.max((upper - width) / 3 / 16384.0 / 32.0, 0.0);
        layers[layer].setScalableBands(qsample, bias);
    }

    /**
     * Decodes a scalable layer of a frame and adds it to {@link #combined} and {@link #spectra}.
     *
     * @param index the layer
     * @param data the units
     * @param lengths their sizes
     */
    private void decodeScalableLayer(int index, byte[][] data, int[] lengths) {
        var layer = layers[index];
        var eightShort = windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE;
        var firstLength = openLayer(index, data, lengths);
        int total;
        int side;
        if (split) {
            var size = (int) reader.bitsLeft();
            total = frameBits[index] != 0 ? frameBits[index] : size - 8;
            side = readScalableHeader(index, layer, eightShort, total);
            var exact = side % 8 == 0 ? size : size - 8;
            if (exact != total) {
                total = exact;
                openLayer(index, data, lengths);
                side = readScalableHeader(index, layer, eightShort, total);
            }
        } else {
            total = lengths[index] * 8;
            side = readScalableHeader(index, layer, eightShort, total);
        }
        frameBits[index] = total;
        layer.readScalableShift(reader);
        readBody(layer, eightShort, total, side, firstLength);
        reader.checkNotOverrun();
        var lines = layer.reconstructScalable(eightShort, scalableHistory);
        scalableHistory = true;
        for (var channel = 0; channel < channels; channel++) {
            Arrays.fill(combined[channel], 0);
        }
        addLayer(lines, index, eightShort);
        for (var channel = 0; channel < channels; channel++) {
            var target = spectra[channel];
            var source = combined[channel];
            for (var line = 0; line < frameLength; line++) {
                target[line] += (float) source[line];
            }
        }
    }

    /**
     * Reads the {@code tvq_scalable_extension_header} of a layer.
     *
     * @param index the layer
     * @param layer its tools
     * @param eightShort whether the frame is an eight short sequence
     * @param total the number of bits of the frame
     * @return the bits of the header and the side information, those of the shape code excluded
     */
    private int readScalableHeader(int index, TwinVqLayer layer, boolean eightShort, int total) {
        frameBits[index] = total;
        setScalableBands(index);
        var line = (int) ((float) layer.highestTop() * (float) (eightShort ? frameLength / SHORT_WINDOWS : frameLength));
        var bands = bandsCovering(eightShort ? shortOffsets : longOffsets, line);
        maxBands[index] = bands;
        if (channels == 2) {
            readMidSide(false, eightShort, maxBands[index - 1], bands);
        }
        return (int) reader.bitPosition() + layer.scalableSideBits(eightShort);
    }

    /**
     * Runs the inverse M/S matrix of a layer and scales its spectrum into {@link #combined}.
     *
     * @param lines the spectrum of the layer, channel after channel
     * @param layer the tools of the layer
     * @param eightShort whether the frame is an eight short sequence
     */
    private void addLayer(double[] lines, int layer, boolean eightShort) {
        var windows = eightShort ? SHORT_WINDOWS : 1;
        var length = frameLength / windows;
        if (channels == 2) {
            var offsets = eightShort ? shortOffsets : longOffsets;
            for (var window = 0; window < windows; window++) {
                var base = window * length;
                for (var band = 0; band < maxBands[layer]; band++) {
                    if (!msUsed[window * ChannelStream.MAX_BANDS + band]) {
                        continue;
                    }
                    for (var line = base + offsets[band]; line < base + offsets[band + 1]; line++) {
                        var mid = lines[line];
                        var side = lines[line + frameLength];
                        lines[line] = mid + side;
                        lines[line + frameLength] = mid - side;
                    }
                }
            }
        }
        var gain = Math.sqrt(length * 2.0);
        for (var channel = 0; channel < channels; channel++) {
            var top = channel * frameLength;
            var target = combined[channel];
            for (var line = 0; line < frameLength; line++) {
                target[line] += lines[line + top] * gain;
            }
        }
    }

    /**
     * Adds the long term prediction to the base layer spectrum in {@link #spectra} and appends the base layer's
     * reconstruction to the history of the predictors.
     */
    private void predict() {
        var windows = windowSequence == ChannelStream.EIGHT_SHORT_SEQUENCE ? SHORT_WINDOWS : 1;
        for (var channel = 0; channel < channels; channel++) {
            var tool = tools[channel];
            var spectrum = tool.spectrum();
            System.arraycopy(spectra[channel], 0, spectrum, 0, frameLength);
            predictors[channel].predict(tool, predictionOverlaps[channel], previousShape, synthesis);
            System.arraycopy(spectrum, 0, spectra[channel], 0, frameLength);
            System.arraycopy(spectrum, 0, scratch, 0, frameLength);
            for (var window = 0; window < windows; window++) {
                tool.applyTns(scratch, window, maxBands[0]);
            }
            synthesis.synthesize(scratch, windowSequence, previousShape, windowShape, predictionOverlaps[channel],
                    reconstruction, 0);
            predictors[channel].update(reconstruction, 0);
        }
    }
}

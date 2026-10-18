package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.internal.sbr.SbrElement;

import com.github.auties00.smalt.internal.limits.LimitChecks;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Decodes AAC Low Complexity {@code raw_data_block}s, with their spectral band replication and
 * parametric stereo extensions, into planar float samples.
 *
 * <p>Each block is decoded in two phases. The first parses every syntactic element in order,
 * inverse-quantizes the channel streams and applies the element-level tools (M/S stereo,
 * perceptual noise substitution, intensity stereo). The second assigns the parsed channels to
 * output channels through the active {@link ChannelMapping} and, channel by channel, adds the
 * dependently switched coupling channels that apply before TNS, applies TNS, adds those that
 * apply after it, runs the synthesis filterbank into the output buffer (whose channels are in
 * WAVE order) and adds the independently switched coupling channels. When a block's elements
 * do not fit the configured mapping, the mapping is replaced by one inferred from the block
 * and the filterbank memory is reset.
 *
 * <p>Data stream elements and fill elements are skipped, except the spectral band replication
 * payloads of fill elements while the {@link SbrMode} decodes them; program configuration
 * elements replace the mapping. When spectral band replication is decoded, every channel element
 * runs through an {@link SbrElement}: the filterbank writes the core samples of each coded
 * channel to a scratch buffer, and the element turns them into the output samples, at twice the
 * core rate unless the mode is downsampled. A single channel element that is the whole layout
 * outputs two channels when parametric stereo is enabled.
 */
public final class FrameDecoder {
    /**
     * The largest number of raw data blocks a packet may hold; ADTS frames carry at most four.
     */
    public static final int MAX_BLOCKS = 8;

    /**
     * The seed of the noise generator.
     */
    private static final int NOISE_SEED = 0x1F2E3D4C;

    /**
     * The extension type of a fill element holding spectral band replication data.
     */
    private static final int EXT_SBR_DATA = 13;

    /**
     * The extension type of a fill element holding spectral band replication data with a CRC.
     */
    private static final int EXT_SBR_DATA_CRC = 14;

    /**
     * Selects how a decoder treats spectral band replication.
     */
    public enum SbrMode {
        /**
         * Decodes the Low Complexity core only and skips the extension payloads.
         */
        OFF,

        /**
         * Decodes spectral band replication and outputs twice the core rate.
         */
        DUAL_RATE,

        /**
         * Decodes spectral band replication with the downsampled synthesis filterbank, keeping
         * the core rate.
         */
        DOWNSAMPLED,

        /**
         * Decides on the first raw data block: {@link #DUAL_RATE} when it carries spectral band
         * replication data, {@link #OFF} otherwise.
         */
        DETECT_DUAL_RATE,

        /**
         * Decides on the first raw data block: {@link #DOWNSAMPLED} when it carries spectral
         * band replication data, {@link #OFF} otherwise.
         */
        DETECT_DOWNSAMPLED
    }

    /**
     * The number of samples per frame.
     */
    private final int frameLength;

    /**
     * The sampling table index.
     */
    private final int tableIndex;

    /**
     * The core sampling rate.
     */
    private final int coreRate;

    /**
     * The output sampling rate.
     */
    private int sampleRate;

    /**
     * The number of output samples per channel and block.
     */
    private int outputLength;

    /**
     * How spectral band replication is treated; the detecting modes change to their outcome on
     * the first block.
     */
    private SbrMode sbrMode;

    /**
     * Whether a single channel element that is the whole layout outputs two channels through
     * parametric stereo once spectral band replication is decoded.
     */
    private final boolean parametricStereo;

    /**
     * The spectral band replication decoder of each channel element, by position in the block,
     * or {@code null} until the element is first decoded.
     */
    private SbrElement[] sbrElements = new SbrElement[8];

    /**
     * The element type each entry of {@link #sbrElements} was created for.
     */
    private int[] sbrTypes = new int[8];

    /**
     * Whether the current block carried spectral band replication data.
     */
    private boolean sbrInBlock;

    /**
     * The core samples of each coded channel when spectral band replication is decoded.
     */
    private float[][] coreSamples = new float[0][];

    /**
     * The first coded channel of each channel element of the current block.
     */
    private int[] elementChannels = new int[8];

    /**
     * The core sample buffers handed to an element.
     */
    private final float[][] elementCore = new float[2][];

    /**
     * The output planes handed to an element.
     */
    private final float[][] elementOutput = new float[2][];

    /**
     * The resource limits.
     */
    private final MediaLimits limits;

    /**
     * The DSP kernel.
     */
    private final AacKernel kernel;

    /**
     * The synthesis filterbank.
     */
    private final Synthesis synthesis;

    /**
     * The active channel mapping, or {@code null} until one is known.
     */
    private ChannelMapping mapping;

    /**
     * The layout reported while no mapping is known.
     */
    private final ChannelLayout fallbackLayout;

    /**
     * The parsed channel streams of the current block, in parse order.
     */
    private ChannelStream[] streams = new ChannelStream[0];

    /**
     * The number of streams parsed in the current block.
     */
    private int streamCount;

    /**
     * The type of each channel element of the current block.
     */
    private int[] elementTypes = new int[8];

    /**
     * The instance tag of each channel element of the current block.
     */
    private int[] elementTags = new int[8];

    /**
     * The index of the first stream of each channel element of the current block.
     */
    private int[] elementStreams = new int[8];

    /**
     * The number of channel elements of the current block.
     */
    private int elementCount;

    /**
     * The stream assigned to each coded channel.
     */
    private ChannelStream[] channelStreams = new ChannelStream[0];

    /**
     * The overlap memory of each coded channel.
     */
    private float[][] overlaps = new float[0][];

    /**
     * The window shape of the previous frame of each coded channel.
     */
    private int[] previousShapes = new int[0];

    /**
     * The output buffer.
     */
    private AudioBuffer output;

    /**
     * The M/S flags of the channel pair being decoded, indexed like band arrays.
     */
    private final boolean[] msUsed = new boolean[8 * ChannelStream.MAX_BANDS];

    /**
     * The noise generator seed of each band and window of the left channel of the pair being
     * decoded, indexed {@code window * MAX_BANDS + band}.
     */
    private final int[] noiseSeeds = new int[8 * ChannelStream.MAX_BANDS];

    /**
     * The noise generator state.
     */
    private int noiseState = NOISE_SEED;

    /**
     * The coupling channel decoder of each element instance tag, created on first use; each
     * keeps the synthesis memory of its independently switched channel across frames.
     */
    private final CouplingChannel[] couplings = new CouplingChannel[16];

    /**
     * The coupling channels of the current block, in parse order.
     */
    private final CouplingChannel[] frameCouplings = new CouplingChannel[16];

    /**
     * The number of coupling channels in the current block.
     */
    private int couplingCount;

    /**
     * The target streams of the coupling channels of the current block, two slots per target
     * element: left (or single) then right, {@code null} where no channel is coupled.
     */
    private final ChannelStream[][] couplingTargets = new ChannelStream[16][16];

    /**
     * The gain list of each coupling target slot.
     */
    private final int[][] couplingLists = new int[16][16];

    /**
     * Creates a decoder for a configuration.
     *
     * @param config the stream configuration
     * @param limits the resource limits
     * @param fallbackLayout the layout to report while the configuration leaves it to the
     * bitstream and no block was decoded yet
     * @param sbrMode how spectral band replication is treated
     * @param parametricStereo whether a mono layout outputs two channels through parametric
     * stereo once spectral band replication is decoded
     * @throws MediaException.LimitExceeded if the configuration exceeds the limits
     */
    public FrameDecoder(AudioSpecificConfig config, MediaLimits limits, ChannelLayout fallbackLayout, SbrMode sbrMode,
                        boolean parametricStereo) {
        this.frameLength = config.frameLength();
        this.tableIndex = config.tableIndex();
        this.coreRate = config.sampleRate();
        this.sbrMode = sbrMode;
        this.parametricStereo = parametricStereo;
        this.sampleRate = sbrMode == SbrMode.DUAL_RATE ? 2 * coreRate : coreRate;
        this.outputLength = sbrMode == SbrMode.DUAL_RATE ? 2 * frameLength : frameLength;
        this.limits = limits;
        this.kernel = AacKernels.INSTANCE;
        this.synthesis = new Synthesis(frameLength, kernel);
        this.fallbackLayout = fallbackLayout;
        if (config.programConfig() != null) {
            useMapping(ChannelMapping.ofProgram(config.programConfig(), limits));
        } else if (config.channelConfiguration() != 0) {
            useMapping(ChannelMapping.ofConfiguration(config.channelConfiguration(), limits));
        }
    }

    /**
     * Returns the output sampling rate.
     *
     * @return the rate in hertz
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * Returns the output layout of the last decoded block, or the configured one before any.
     *
     * @return the layout in WAVE order
     */
    public ChannelLayout layout() {
        if (mapping == null) {
            return stereoOutput(fallbackLayout.channels() == 1) ? ChannelLayout.STEREO : fallbackLayout;
        }
        return stereoOutput(mapping.channels() == 1) ? ChannelLayout.STEREO : mapping.layout();
    }

    /**
     * Returns whether a layout outputs two channels through parametric stereo.
     *
     * @param mono whether the coded layout is a single channel
     * @return {@code true} when a mono layout is decoded as stereo
     */
    private boolean stereoOutput(boolean mono) {
        return mono && parametricStereo && sbrActive();
    }

    /**
     * Returns whether spectral band replication is decoded.
     *
     * @return {@code true} in the dual-rate and downsampled modes
     */
    public boolean sbrActive() {
        return sbrMode == SbrMode.DUAL_RATE || sbrMode == SbrMode.DOWNSAMPLED;
    }

    /**
     * Returns the number of samples per channel each block decodes to.
     *
     * @return 1024 or 960, or twice that at dual rate
     */
    public int frameLength() {
        return outputLength;
    }

    /**
     * Decodes every raw data block of a packet and hands each decoded block to {@code output}.
     *
     * <p>A packet normally holds one {@code raw_data_block}; a CRC-less ADTS frame with several
     * blocks arrives as one packet holding them back to back, each ending with its end marker
     * and byte alignment. Blocks are decoded until the packet is exhausted; trailing zero bytes
     * after a block are taken as padding. Blocks decoded before a corrupt one have already been
     * handed over when the exception is raised.
     *
     * @param data the array holding the packet
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @param output receives each block's samples, valid only during the call
     * @throws MediaException.Malformed if a block is corrupt, the packet is empty, or it
     * holds more than {@link #MAX_BLOCKS} blocks
     * @throws MediaException.Unsupported if a block uses a coding tool this decoder lacks
     * @throws MediaException.LimitExceeded if a block declares too many channels
     */
    public void decode(byte[] data, int offset, int length, Consumer<AudioBuffer> output) {
        if (length == 0) {
            throw new MediaException.Malformed("empty AAC packet");
        }
        var reader = new AacBitReader(data, offset, length);
        for (var block = 0; ; block++) {
            if (block == MAX_BLOCKS) {
                throw new MediaException.Malformed("AAC packet holds more than " + MAX_BLOCKS + " raw data blocks");
            }
            output.accept(decodeBlock(reader));
            reader.alignToByte();
            if (onlyPadding(data, offset + length, reader)) {
                return;
            }
        }
    }

    /**
     * Returns whether the reader stands at the end of the packet or before zero bytes only.
     *
     * @param data the packet array
     * @param end the index after the packet's last byte
     * @param reader the reader, byte-aligned
     * @return {@code true} when no further block follows
     */
    private static boolean onlyPadding(byte[] data, int end, AacBitReader reader) {
        var remaining = reader.bitsLeft();
        if (remaining < 8) {
            return true;
        }
        for (var index = end - (int) (remaining >>> 3); index < end; index++) {
            if (data[index] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes one raw data block from the reader's position up to and including its end marker.
     *
     * @param reader the bit source
     * @return the decoded samples, valid until the next block
     */
    private AudioBuffer decodeBlock(AacBitReader reader) {
        streamCount = 0;
        elementCount = 0;
        couplingCount = 0;
        sbrInBlock = false;
        ChannelMapping announced = null;
        while (true) {
            var id = reader.read(3);
            if (id == ElementId.END) {
                break;
            }
            switch (id) {
                case ElementId.SCE, ElementId.LFE -> decodeSingle(reader, id);
                case ElementId.CPE -> decodePair(reader);
                case ElementId.CCE -> decodeCoupling(reader);
                case ElementId.DSE -> skipDataStream(reader);
                case ElementId.PCE -> announced = ChannelMapping.ofProgram(ProgramConfig.parse(reader), limits);
                case ElementId.FIL -> readFill(reader);
                default -> throw new AssertionError(id);
            }
            reader.checkNotOverrun();
        }
        if (sbrMode == SbrMode.DETECT_DUAL_RATE || sbrMode == SbrMode.DETECT_DOWNSAMPLED) {
            decideSbr();
        }
        if (announced != null && (mapping == null || !announced.layout().equals(mapping.layout()))) {
            useMapping(announced);
        }
        if (elementCount == 0) {
            return silence();
        }
        if (mapping == null || !mapping.matches(elementTypes, elementTags, elementCount)) {
            useMapping(ChannelMapping.infer(Arrays.copyOf(elementTypes, elementCount), limits));
        }
        assignChannels();
        prepareCouplings();
        var sbr = sbrActive();
        for (var channel = 0; channel < channelStreams.length; channel++) {
            var stream = channelStreams[channel];
            if (couplingCount != 0) {
                coupleSpectrum(stream, false);
            }
            stream.applyTns();
            if (couplingCount != 0) {
                coupleSpectrum(stream, true);
            }
            var plane = sbr ? coreSamples[channel] : output.plane(mapping.outputIndex(channel));
            synthesis.synthesize(stream.spectrum(), stream.windowSequence(), previousShapes[channel],
                    stream.windowShape(), overlaps[channel], plane, 0);
            previousShapes[channel] = stream.windowShape();
            if (couplingCount != 0) {
                coupleTime(stream, plane);
            }
        }
        if (sbr) {
            applySbr();
        }
        output.setFrames(outputLength);
        return output;
    }

    /**
     * Settles a detecting {@link SbrMode} after the first block was parsed.
     */
    private void decideSbr() {
        if (sbrInBlock) {
            sbrMode = sbrMode == SbrMode.DETECT_DUAL_RATE ? SbrMode.DUAL_RATE : SbrMode.DOWNSAMPLED;
        } else {
            sbrMode = SbrMode.OFF;
            Arrays.fill(sbrElements, null);
        }
        sampleRate = sbrMode == SbrMode.DUAL_RATE ? 2 * coreRate : coreRate;
        outputLength = sbrMode == SbrMode.DUAL_RATE ? 2 * frameLength : frameLength;
        if (mapping != null) {
            var current = mapping;
            mapping = null;
            useMapping(current);
        }
    }

    /**
     * Runs every channel element of the block through its spectral band replication decoder,
     * from the core samples to the output planes.
     */
    private void applySbr() {
        var stereo = stereoOutput(mapping.channels() == 1);
        for (var element = 0; element < elementCount; element++) {
            var type = elementTypes[element];
            var sbr = sbrElement(element, type);
            var channel = elementChannels[element];
            var pair = type == ElementId.CPE;
            elementCore[0] = coreSamples[channel];
            elementCore[1] = pair ? coreSamples[channel + 1] : null;
            elementOutput[0] = output.plane(mapping.outputIndex(channel));
            if (pair) {
                elementOutput[1] = output.plane(mapping.outputIndex(channel + 1));
            } else if (stereo) {
                elementOutput[1] = output.plane(1);
            } else {
                elementOutput[1] = null;
            }
            sbr.decode(elementCore, elementOutput, stereo);
        }
    }

    /**
     * Returns the spectral band replication decoder of a channel element, creating it when the
     * element at that position is new or changed type.
     *
     * @param element the element's position in the block
     * @param type the element type
     * @return the decoder
     */
    private SbrElement sbrElement(int element, int type) {
        if (element >= sbrElements.length) {
            sbrElements = Arrays.copyOf(sbrElements, element + 8);
            sbrTypes = Arrays.copyOf(sbrTypes, element + 8);
        }
        var current = sbrElements[element];
        if (current == null || sbrTypes[element] != type) {
            current = new SbrElement(type == ElementId.CPE ? 2 : 1, coreRate, frameLength,
                    sbrMode == SbrMode.DOWNSAMPLED || sbrMode == SbrMode.DETECT_DOWNSAMPLED,
                    parametricStereo && type == ElementId.SCE);
            sbrElements[element] = current;
            sbrTypes[element] = type;
        }
        return current;
    }

    /**
     * Returns a block of silence in the current layout, for blocks without channel elements.
     *
     * @return the output buffer, zeroed
     */
    private AudioBuffer silence() {
        if (output == null) {
            useMapping(ChannelMapping.infer(new int[]{
                    fallbackLayout.channels() == 1 ? ElementId.SCE : ElementId.CPE}, limits));
        }
        for (var plane : output.planes()) {
            Arrays.fill(plane, 0, outputLength, 0);
        }
        output.setFrames(outputLength);
        return output;
    }

    /**
     * Switches to a mapping, resetting the filterbank memory when the channel count changes.
     *
     * @param next the new mapping
     */
    private void useMapping(ChannelMapping next) {
        var channels = next.channels();
        if (mapping == null || mapping.channels() != channels || !mapping.layout().equals(next.layout())) {
            LimitChecks.checkAllocation(limits, (long) channels * (frameLength * 8L + outputLength * 4L));
            overlaps = new float[channels][frameLength];
            previousShapes = new int[channels];
            channelStreams = new ChannelStream[channels];
            coreSamples = new float[channels][frameLength];
            var layout = stereoOutput(channels == 1) ? ChannelLayout.STEREO : next.layout();
            output = new AudioBuffer(layout, sampleRate, outputLength);
            if (mapping != null) {
                Arrays.fill(sbrElements, null);
            }
        }
        mapping = next;
    }

    /**
     * Assigns the parsed streams to coded channels through the active mapping.
     */
    private void assignChannels() {
        var ordinals = new int[4];
        if (elementChannels.length < elementCount) {
            elementChannels = new int[elementTypes.length];
        }
        for (var element = 0; element < elementCount; element++) {
            var type = elementTypes[element];
            var channel = mapping.channelOf(type, elementTags[element], ordinals[type]++);
            elementChannels[element] = channel;
            channelStreams[channel] = streams[elementStreams[element]];
            if (type == ElementId.CPE) {
                channelStreams[channel + 1] = streams[elementStreams[element] + 1];
            }
        }
    }

    /**
     * Returns the next free stream, growing the pool when needed.
     *
     * @return the stream
     * @throws MediaException.LimitExceeded if the block holds more channels than the limits allow
     */
    private ChannelStream nextStream() {
        if (streamCount == streams.length) {
            LimitChecks.checkChannels(limits, streamCount + 1);
            streams = Arrays.copyOf(streams, streamCount + 2);
            for (var i = streamCount; i < streams.length; i++) {
                streams[i] = new ChannelStream(frameLength, tableIndex, kernel);
            }
        }
        return streams[streamCount++];
    }

    /**
     * Records a channel element of the current block.
     *
     * @param type the element type
     * @param tag the element instance tag
     * @param firstStream the index of its first stream
     */
    private void addElement(int type, int tag, int firstStream) {
        if (elementCount == elementTypes.length) {
            elementTypes = Arrays.copyOf(elementTypes, elementCount * 2);
            elementTags = Arrays.copyOf(elementTags, elementCount * 2);
            elementStreams = Arrays.copyOf(elementStreams, elementCount * 2);
        }
        elementTypes[elementCount] = type;
        elementTags[elementCount] = tag;
        elementStreams[elementCount] = firstStream;
        elementCount++;
    }

    /**
     * Decodes a single channel element or an LFE element.
     *
     * @param reader the bit source
     * @param type the element type
     */
    private void decodeSingle(AacBitReader reader, int type) {
        var tag = reader.read(4);
        var first = streamCount;
        var stream = nextStream();
        stream.parse(reader, false, false);
        fillNoise(stream, null, false);
        addElement(type, tag, first);
    }

    /**
     * Decodes a channel pair element and applies its stereo tools.
     *
     * @param reader the bit source
     * @throws MediaException.Malformed if the M/S mask mode is reserved
     */
    private void decodePair(AacBitReader reader) {
        var tag = reader.read(4);
        var first = streamCount;
        var left = nextStream();
        var right = nextStream();
        var commonWindow = reader.readFlag();
        var msMode = 0;
        if (commonWindow) {
            left.readInfo(reader);
            right.copyInfo(left);
            msMode = reader.read(2);
            if (msMode == 3) {
                throw new MediaException.Malformed("reserved AAC M/S mask mode");
            }
            for (var group = 0; group < left.groupCount(); group++) {
                var base = group * ChannelStream.MAX_BANDS;
                for (var band = 0; band < left.maxBands(); band++) {
                    msUsed[base + band] = msMode == 2 || (msMode == 1 && reader.readFlag());
                }
            }
        }
        left.parse(reader, commonWindow, false);
        right.parse(reader, commonWindow, true);
        if (msMode != 0) {
            applyMidSide(left, right);
        }
        fillNoise(left, null, false);
        fillNoise(right, left, msMode != 0);
        applyIntensity(left, right, msMode != 0);
        addElement(ElementId.CPE, tag, first);
    }

    /**
     * Applies M/S stereo to every flagged band coded in both channels.
     *
     * @param left the mid channel, becoming left
     * @param right the side channel, becoming right
     */
    private void applyMidSide(ChannelStream left, ChannelStream right) {
        var offsets = left.bandOffsets();
        var windowLength = left.windowLength();
        var l = left.spectrum();
        var r = right.spectrum();
        var firstWindow = 0;
        for (var group = 0; group < left.groupCount(); group++) {
            var base = group * ChannelStream.MAX_BANDS;
            var lastWindow = firstWindow + left.groupLength(group);
            for (var band = 0; band < left.maxBands(); band++) {
                var index = base + band;
                if (!msUsed[index] || left.bandType(index) >= Codebooks.NOISE_HCB
                        || right.bandType(index) >= Codebooks.NOISE_HCB) {
                    continue;
                }
                for (var window = firstWindow; window < lastWindow; window++) {
                    kernel.midSide(l, r, window * windowLength + offsets[band], window * windowLength + offsets[band + 1]);
                }
            }
            firstWindow = lastWindow;
        }
    }

    /**
     * Fills the noise bands of a stream with scaled pseudo-random noise.
     *
     * <p>When {@code partner} is given and {@code correlated} is set, a band that is a noise band
     * in both channels and flagged for M/S reuses the partner's noise vector, as ISO/IEC 14496-3
     * subclause 4.6.13 requires.
     *
     * @param stream the stream to fill
     * @param partner the left channel of the pair when {@code stream} is the right one, or
     * {@code null}
     * @param correlated whether M/S flags are present
     */
    private void fillNoise(ChannelStream stream, ChannelStream partner, boolean correlated) {
        var offsets = stream.bandOffsets();
        var windowLength = stream.windowLength();
        var spectrum = stream.spectrum();
        var firstWindow = 0;
        for (var group = 0; group < stream.groupCount(); group++) {
            var base = group * ChannelStream.MAX_BANDS;
            var lastWindow = firstWindow + stream.groupLength(group);
            for (var band = 0; band < stream.maxBands(); band++) {
                var index = base + band;
                if (stream.bandType(index) != Codebooks.NOISE_HCB) {
                    continue;
                }
                var reuse = partner != null && correlated && msUsed[index]
                        && partner.bandType(index) == Codebooks.NOISE_HCB && partner.sameWindows(stream);
                var gain = Dequantizer.gain(stream.bandValue(index));
                for (var window = firstWindow; window < lastWindow; window++) {
                    var seedIndex = window * ChannelStream.MAX_BANDS + band;
                    int state;
                    if (reuse) {
                        state = noiseSeeds[seedIndex];
                    } else {
                        state = noiseState;
                        if (partner == null) {
                            noiseSeeds[seedIndex] = state;
                        }
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
            firstWindow = lastWindow;
        }
    }

    /**
     * Reconstructs the intensity bands of the right channel from the left channel.
     *
     * @implNote This implementation lets the M/S flags invert the intensity phase whenever an
     * M/S mask is present, including mask mode 2 where every band is flagged. ISO/IEC 14496-3
     * subclause 4.6.8.2.3 words the inversion for mask mode 1; the conformance streams exercise
     * only modes 0 and 1, and ffmpeg's encoder, the common producer of intensity bands under
     * mask mode 2, codes them for the inverting reading its own decoder implements.
     *
     * @param left the left channel
     * @param right the right channel
     * @param msInvert whether an M/S mask is present, whose flags invert the intensity phase
     */
    private void applyIntensity(ChannelStream left, ChannelStream right, boolean msInvert) {
        var offsets = right.bandOffsets();
        var windowLength = right.windowLength();
        var l = left.spectrum();
        var r = right.spectrum();
        var firstWindow = 0;
        for (var group = 0; group < right.groupCount(); group++) {
            var base = group * ChannelStream.MAX_BANDS;
            var lastWindow = firstWindow + right.groupLength(group);
            for (var band = 0; band < right.maxBands(); band++) {
                var index = base + band;
                var type = right.bandType(index);
                if (type != Codebooks.INTENSITY_HCB && type != Codebooks.INTENSITY_HCB2) {
                    continue;
                }
                var inPhase = type == Codebooks.INTENSITY_HCB;
                if (msInvert && msUsed[index]) {
                    inPhase = !inPhase;
                }
                var scale = Dequantizer.gain(-right.bandValue(index));
                if (!inPhase) {
                    scale = -scale;
                }
                for (var window = firstWindow; window < lastWindow; window++) {
                    var end = window * windowLength + offsets[band + 1];
                    for (var k = window * windowLength + offsets[band]; k < end; k++) {
                        r[k] = l[k] * scale;
                    }
                }
            }
            firstWindow = lastWindow;
        }
    }

    /**
     * Parses a coupling channel element.
     *
     * @param reader the bit source
     * @throws MediaException.Malformed if two coupling channel elements of a block share a tag
     */
    private void decodeCoupling(AacBitReader reader) {
        var tag = reader.read(4);
        var coupling = couplings[tag];
        if (coupling == null) {
            coupling = new CouplingChannel(frameLength, tableIndex, kernel);
            couplings[tag] = coupling;
        }
        for (var i = 0; i < couplingCount; i++) {
            if (frameCouplings[i] == coupling) {
                throw new MediaException.Malformed("two AAC coupling channel elements share tag " + tag);
            }
        }
        coupling.parse(reader, tag);
        fillNoise(coupling.stream(), null, false);
        frameCouplings[couplingCount++] = coupling;
    }

    /**
     * Applies the coupling channels' own TNS, synthesizes the independently switched ones and
     * resolves every coupling target to a parsed stream and gain list.
     *
     * <p>Targets name an element by type and instance tag; a target absent from the block is
     * ignored.
     */
    private void prepareCouplings() {
        for (var i = 0; i < couplingCount; i++) {
            var coupling = frameCouplings[i];
            coupling.stream().applyTns();
            if (coupling.independent()) {
                coupling.synthesize(synthesis);
            }
            var targets = couplingTargets[i];
            var lists = couplingLists[i];
            Arrays.fill(targets, null);
            var list = 0;
            for (var target = 0; target < coupling.targetCount(); target++) {
                var pair = coupling.targetIsPair(target);
                var first = findElementStream(pair ? ElementId.CPE : ElementId.SCE, coupling.targetTag(target));
                if (!pair) {
                    if (first >= 0) {
                        targets[2 * target] = streams[first];
                        lists[2 * target] = list;
                    }
                    list++;
                    continue;
                }
                var select = coupling.targetSelect(target);
                if (select != 1) {
                    if (first >= 0) {
                        targets[2 * target] = streams[first];
                        lists[2 * target] = list;
                    }
                    if (select != 0) {
                        list++;
                    }
                }
                if (select != 2) {
                    if (first >= 0) {
                        targets[2 * target + 1] = streams[first + 1];
                        lists[2 * target + 1] = list;
                    }
                    list++;
                }
            }
        }
    }

    /**
     * Returns the first stream of the block's element of a type and tag.
     *
     * @param type the element type
     * @param tag the instance tag
     * @return the stream index, or {@code -1} when the block has no such element
     */
    private int findElementStream(int type, int tag) {
        for (var element = 0; element < elementCount; element++) {
            if (elementTypes[element] == type && elementTags[element] == tag) {
                return elementStreams[element];
            }
        }
        return -1;
    }

    /**
     * Adds the dependently switched coupling channels that target a stream to its spectrum.
     *
     * @param stream the target stream
     * @param afterTns whether to apply the couplings of the after-TNS domain rather than the
     * before-TNS one
     */
    private void coupleSpectrum(ChannelStream stream, boolean afterTns) {
        for (var i = 0; i < couplingCount; i++) {
            var coupling = frameCouplings[i];
            if (coupling.independent() || coupling.afterTns() != afterTns) {
                continue;
            }
            var targets = couplingTargets[i];
            for (var slot = 0; slot < 2 * coupling.targetCount(); slot++) {
                if (targets[slot] == stream) {
                    coupling.addSpectrum(couplingLists[i][slot], stream.spectrum());
                }
            }
        }
    }

    /**
     * Adds the independently switched coupling channels that target a stream to its output.
     *
     * @param stream the target stream
     * @param plane the stream's output samples
     */
    private void coupleTime(ChannelStream stream, float[] plane) {
        for (var i = 0; i < couplingCount; i++) {
            var coupling = frameCouplings[i];
            if (!coupling.independent()) {
                continue;
            }
            var targets = couplingTargets[i];
            for (var slot = 0; slot < 2 * coupling.targetCount(); slot++) {
                if (targets[slot] == stream) {
                    coupling.addTimeSignal(couplingLists[i][slot], plane, frameLength);
                }
            }
        }
    }

    /**
     * Skips a data stream element.
     *
     * @param reader the bit source
     */
    private void skipDataStream(AacBitReader reader) {
        reader.read(4);
        var align = reader.readFlag();
        var count = reader.read(8);
        if (count == 255) {
            count += reader.read(8);
        }
        if (align) {
            reader.alignToByte();
        }
        reader.skip(8L * count);
    }

    /**
     * Reads a fill element: hands a spectral band replication payload that follows a single
     * channel or channel pair element to that element's decoder, and skips every other payload.
     *
     * @param reader the bit source
     */
    private void readFill(AacBitReader reader) {
        var count = reader.read(4);
        if (count == 15) {
            count += reader.read(8) - 1;
        }
        if (count == 0) {
            return;
        }
        var bits = 8L * count;
        var previous = elementCount == 0 ? -1 : elementTypes[elementCount - 1];
        if (sbrMode == SbrMode.OFF || (previous != ElementId.SCE && previous != ElementId.CPE)) {
            reader.skip(bits);
            return;
        }
        var type = reader.read(4);
        if (type != EXT_SBR_DATA && type != EXT_SBR_DATA_CRC) {
            reader.skip(bits - 4);
            return;
        }
        sbrInBlock = true;
        sbrElement(elementCount - 1, previous).parse(reader, bits - 4, type == EXT_SBR_DATA_CRC);
    }
}

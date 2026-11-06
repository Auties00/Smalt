package com.github.auties00.smalt.audio.atrac9;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Writes ATRAC9 streams for the tests, as no permissively licensed ATRAC9 encoder or sample exists: a syntax-random
 * writer, which codes every element of the bitstream (band and intensity stereo parameters, the four gradient modes,
 * the band extension modes, every scale factor coding mode, prefix-coded and fixed-length spectra, fine precision,
 * low-frequency effects blocks, reused band parameters) with random legal values, and a signal encoder, which codes
 * real audio through a forward transform derived from the decoder's synthesis so that the decode reproduces the
 * input.
 *
 * <p>The writers track the decoder state the syntax depends on with the decoder's own block and channel objects and
 * its bit allocation, so a mistake there is caught by the comparison with LibAtrac9, whose reading of the stream
 * would then differ.
 */
final class Atrac9TestStreams {
    /**
     * A stream: the configuration word and the superframes, each of the configuration's superframe size.
     */
    record Stream(byte[] config, List<byte[]> superframes) {
        /** {@return the configuration word followed by the superframes, the input of smalt-at9dec} */
        byte[] concatenated() {
            var out = new ByteArrayOutputStream();
            out.writeBytes(config);
            for (var superframe : superframes) {
                out.writeBytes(superframe);
            }
            return out.toByteArray();
        }

        Atrac9Config parsed() {
            return Atrac9Config.parse(config, 0);
        }
    }

    /** A growable most-significant-bit-first writer. */
    static final class BitWriter {
        private byte[] data = new byte[64];
        private int bits;

        void write(int value, int count) {
            for (var i = count - 1; i >= 0; i--) {
                if ((bits >> 3) >= data.length) {
                    data = Arrays.copyOf(data, data.length * 2);
                }
                if ((value >>> i & 1) != 0) {
                    data[bits >> 3] |= (byte) (0x80 >>> (bits & 7));
                }
                bits++;
            }
        }

        void append(BitWriter other) {
            for (var i = 0; i < other.bits; i++) {
                write(other.data[i >> 3] >>> (7 - (i & 7)) & 1, 1);
            }
        }

        void align() {
            bits = (bits + 7) & ~7;
            if ((bits >> 3) > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
        }

        int bytes() {
            return (bits + 7) >> 3;
        }

        byte[] toBytes() {
            return Arrays.copyOf(data, bytes());
        }
    }

    /** The gradient each frame of the last {@link #encode} call was coded with, for diagnostics. */
    static final List<Integer> LAST_GRADIENTS = new ArrayList<>();

    private Atrac9TestStreams() {
    }

    static byte[] configWord(int rateIndex, int channelConfig, int frameBytes, int superframeIndex) {
        var word = 0xFE << 24 | rateIndex << 20 | channelConfig << 17 | (frameBytes - 1) << 5 | superframeIndex << 3;
        return new byte[]{(byte) (word >>> 24), (byte) (word >>> 16), (byte) (word >>> 8), (byte) word};
    }

    private static Atrac9Block[] blocks(Atrac9Config config) {
        var types = Atrac9Tables.CHANNEL_CONFIGS[config.channelConfigIndex()];
        var blocks = new Atrac9Block[types.length];
        for (var i = 0; i < types.length; i++) {
            blocks[i] = new Atrac9Block(config, types[i]);
        }
        return blocks;
    }

    /**
     * Packs frames into superframes of one size: the smallest frame size, in bytes, that holds the largest
     * superframe.
     */
    private static Stream pack(int rateIndex, int channelConfig, int superframeIndex, List<List<byte[]>> superframes,
                               int minimumFrameBytes) {
        var frames = 1 << superframeIndex;
        var largest = 1;
        for (var superframe : superframes) {
            var total = superframe.stream().mapToInt(frame -> frame.length).sum();
            largest = Math.max(largest, total);
        }
        var frameBytes = Math.max(minimumFrameBytes, (largest + frames - 1) / frames);
        if (frameBytes > 2048) {
            throw new IllegalStateException("superframe of " + largest + " bytes does not fit");
        }
        var packed = new ArrayList<byte[]>();
        for (var superframe : superframes) {
            var bytes = new byte[frameBytes * frames];
            var at = 0;
            for (var frame : superframe) {
                System.arraycopy(frame, 0, bytes, at, frame.length);
                at += frame.length;
            }
            packed.add(bytes);
        }
        return new Stream(configWord(rateIndex, channelConfig, frameBytes, superframeIndex), packed);
    }

    /**
     * Writes a stream of random legal syntax.
     *
     * @param rateIndex the sampling rate index
     * @param channelConfig the channel configuration index
     * @param superframeIndex the base-2 logarithm of the frames per superframe
     * @param superframes the number of superframes
     * @param seed the seed of the choices
     */
    static Stream random(int rateIndex, int channelConfig, int superframeIndex, int superframes, long seed) {
        var probe = new Atrac9Config(rateIndex, channelConfig, 2048, superframeIndex);
        var blocks = blocks(probe);
        var random = new Random(seed);
        var hasBandParams = new boolean[blocks.length];
        var all = new ArrayList<List<byte[]>>();
        for (var s = 0; s < superframes; s++) {
            var frames = new ArrayList<byte[]>();
            for (var f = 0; f < probe.framesPerSuperframe(); f++) {
                var writer = new BitWriter();
                for (var b = 0; b < blocks.length; b++) {
                    writeRandomBlock(blocks[b], writer, f == 0, hasBandParams[b], random);
                    hasBandParams[b] = true;
                }
                frames.add(writer.toBytes());
            }
            all.add(frames);
        }
        return pack(rateIndex, channelConfig, superframeIndex, all, 1);
    }

    private static int between(Random random, int low, int high) {
        return low + random.nextInt(high - low + 1);
    }

    private static void writeRandomBlock(Atrac9Block block, BitWriter writer, boolean first, boolean hasBandParams,
                                         Random random) {
        var config = block.config;
        var high = config.highSampleRate() ? 1 : 0;
        var lfe = block.type == Atrac9Tables.LFE;
        var reuse = lfe ? random.nextBoolean() : !first && hasBandParams && random.nextInt(4) == 0;
        writer.write(first ? 0 : 1, 1);
        writer.write(reuse ? 1 : 0, 1);
        block.firstInSuperframe = first;
        block.reuseBandParams = reuse;
        if (lfe) {
            var channel = block.channels[0];
            for (var i = 0; i < 2; i++) {
                writer.write(random.nextInt(32), 5);
            }
            var precision = reuse ? 8 : 4;
            for (var j = 0; j < 4; j++) {
                writer.write(random.nextInt(1 << (precision + 1)), precision + 1);
            }
            channel.codedQuantUnits = 2;
            writer.align();
            return;
        }
        var minBand = Atrac9Tables.MIN_BAND_COUNT[high];
        if (!reuse) {
            block.bandCount = between(random, minBand, Atrac9Tables.MAX_BAND_COUNT[config.sampleRateIndex()]);
            writer.write(block.bandCount - minBand, 4);
            block.quantizationUnitCount = Atrac9Tables.BAND_TO_QUANT_UNIT_COUNT[block.bandCount];
            if (block.type == Atrac9Tables.STEREO) {
                block.stereoBand = random.nextBoolean() ? block.bandCount : between(random, minBand, block.bandCount);
                writer.write(block.stereoBand - minBand, 4);
                block.stereoQuantizationUnit = Atrac9Tables.BAND_TO_QUANT_UNIT_COUNT[block.stereoBand];
            } else {
                block.stereoBand = block.bandCount;
            }
            var maxExtension = Atrac9Tables.MAX_EXTENSION_BAND[high];
            var bexPossible = block.quantizationUnitCount >= 13 && block.quantizationUnitCount <= 20
                    && block.bandCount <= maxExtension;
            block.bandExtensionEnabled = bexPossible && random.nextInt(3) != 0;
            writer.write(block.bandExtensionEnabled ? 1 : 0, 1);
            if (block.bandExtensionEnabled) {
                block.extensionBand = between(random, block.bandCount, Math.min(maxExtension, minBand + 15));
                writer.write(block.extensionBand - minBand, 4);
                block.extensionUnit = Atrac9Tables.BAND_TO_QUANT_UNIT_COUNT[block.extensionBand];
            } else {
                block.extensionBand = block.bandCount;
                block.extensionUnit = block.quantizationUnitCount;
            }
        }
        block.gradientMode = random.nextInt(4);
        writer.write(block.gradientMode, 2);
        if (block.gradientMode > 0) {
            block.gradientEndUnit = 31;
            block.gradientEndValue = 31;
            block.gradientStartUnit = random.nextInt(32);
            block.gradientStartValue = random.nextInt(32);
            writer.write(block.gradientStartUnit, 5);
            writer.write(block.gradientStartValue, 5);
        } else {
            block.gradientStartUnit = random.nextInt(31);
            block.gradientEndUnit = between(random, Math.max(1, block.gradientStartUnit), 31);
            block.gradientStartValue = random.nextInt(32);
            block.gradientEndValue = random.nextInt(32);
            writer.write(block.gradientStartUnit, 6);
            writer.write(block.gradientEndUnit - 1, 6);
            writer.write(block.gradientStartValue, 5);
            writer.write(block.gradientEndValue, 5);
        }
        block.gradientBoundary = random.nextInt(Math.min(15, block.quantizationUnitCount) + 1);
        writer.write(block.gradientBoundary, 4);
        Atrac9Unpacker.createGradient(block);
        if (block.type == Atrac9Tables.STEREO) {
            block.primaryChannelIndex = random.nextInt(2);
            block.hasJointStereoSigns = random.nextBoolean();
            writer.write(block.primaryChannelIndex, 1);
            writer.write(block.hasJointStereoSigns ? 1 : 0, 1);
            Arrays.fill(block.jointStereoSigns, 0);
            if (block.hasJointStereoSigns) {
                for (var i = block.stereoQuantizationUnit; i < block.quantizationUnitCount; i++) {
                    block.jointStereoSigns[i] = random.nextInt(2);
                    writer.write(block.jointStereoSigns[i], 1);
                }
            }
        }
        writeRandomExtension(block, writer, random);
        for (var channel : block.channels) {
            channel.codedQuantUnits = block.primaryChannelIndex == channel.index
                    ? block.quantizationUnitCount : block.stereoQuantizationUnit;
            writer.append(randomScaleFactors(channel, random));
            System.arraycopy(channel.scaleFactors, 0, channel.scaleFactorsPrevious, 0, 31);
            writeRandomSpectra(channel, writer, random);
        }
        block.quantizationUnitsPrevious = block.bandExtensionEnabled ? block.extensionUnit
                : block.quantizationUnitCount;
        writer.align();
    }

    private static final int[][] BEX_COUNTS = {
            {0, 0, 0, 4, 4, 2}, {0, 0, 0, 0, 0, 0}, {0, 0, 0, 2, 2, 1}, {0, 0, 0, 2, 2, 2}, {1, 1, 1, 0, 0, 0}
    };

    private static final int[][][] BEX_LENGTHS = {
            {{0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {5, 4, 3, 3}, {4, 4, 3, 4}, {4, 5, 0, 0}},
            {{0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}},
            {{0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {6, 6, 0, 0}, {6, 6, 0, 0}, {6, 0, 0, 0}},
            {{0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {4, 4, 0, 0}, {4, 4, 0, 0}, {4, 4, 0, 0}},
            {{3, 0, 0, 0}, {3, 0, 0, 0}, {3, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}}
    };

    private static void writeRandomExtension(Atrac9Block block, BitWriter writer, Random random) {
        var bexBand = 0;
        var stereo = block.type == Atrac9Tables.STEREO;
        if (block.bandExtensionEnabled) {
            bexBand = Atrac9Unpacker.BEX_GROUP_INFO[block.quantizationUnitCount - 13][2];
            if (stereo) {
                var mode = random.nextInt(4);
                writer.write(mode, 2);
                block.channels[1].bexMode = bexBand > 2 ? mode : 4;
            } else {
                writer.write(random.nextInt(2), 1);
            }
        }
        block.hasExtensionData = random.nextBoolean();
        writer.write(block.hasExtensionData ? 1 : 0, 1);
        if (!block.hasExtensionData) {
            return;
        }
        if (!block.bandExtensionEnabled) {
            var length = random.nextInt(32);
            writer.write(random.nextInt(4), 2);
            writer.write(length, 5);
            for (var i = 0; i < length; i++) {
                writer.write(random.nextInt(2), 1);
            }
            return;
        }
        var mode = random.nextInt(4);
        writer.write(mode, 2);
        block.channels[0].bexMode = bexBand > 2 ? mode : 4;
        var length = 0;
        for (var c = 0; c < (stereo ? 2 : 1); c++) {
            var channelMode = block.channels[c].bexMode;
            for (var i = 0; i < BEX_COUNTS[channelMode][bexBand]; i++) {
                length += BEX_LENGTHS[channelMode][bexBand][i];
            }
        }
        writer.write(length, 5);
        if (length == 0) {
            return;
        }
        for (var c = 0; c < (stereo ? 2 : 1); c++) {
            var channelMode = block.channels[c].bexMode;
            for (var i = 0; i < BEX_COUNTS[channelMode][bexBand]; i++) {
                var bits = BEX_LENGTHS[channelMode][bexBand][i];
                writer.write(random.nextInt(1 << bits), bits);
            }
        }
    }

    /** Picks a symbol of a codebook that has a code. */
    private static int symbol(Atrac9Codebook codebook, Random random, boolean small) {
        while (true) {
            var symbol = small && random.nextInt(4) != 0
                    ? (random.nextInt(3) - 1) & (codebook.bits.length - 1)
                    : random.nextInt(codebook.bits.length);
            if (codebook.bits[symbol] != 0) {
                return symbol;
            }
        }
    }

    private static void code(BitWriter writer, Atrac9Codebook codebook, int symbol) {
        writer.write(codebook.codes[symbol], codebook.bits[symbol]);
    }

    /**
     * Writes random scale factors in a random legal mode, retrying until every value is in range and the bit
     * allocation that follows is defined; leaves the scale factors and the allocation in the channel.
     */
    private static BitWriter randomScaleFactors(Atrac9Channel channel, Random random) {
        var block = channel.block;
        var units = block.extensionUnit;
        for (var attempt = 0; ; attempt++) {
            var writer = new BitWriter();
            var sf = channel.scaleFactors;
            Arrays.fill(sf, 0);
            var modes = channel.index == 0 ? (block.firstInSuperframe ? 2 : 4)
                    : (block.firstInSuperframe ? 3 : 4);
            var mode = attempt > 200 ? 1 : random.nextInt(modes);
            if (attempt > 200 && channel.index == 1) {
                mode = 1;
            }
            writer.write(mode, 2);
            var ok = true;
            if (mode == 0) {
                ok = deltaOffset(writer, sf, units, random);
            } else if (channel.index == 0 && mode == 1) {
                var length = between(random, 2, 5);
                writer.write(length - 2, 2);
                var base = 0;
                if (length < 5) {
                    base = random.nextInt(32 - (1 << length) + 1);
                    writer.write(base, 5);
                }
                for (var i = 0; i < units; i++) {
                    var raw = attempt > 200 ? 0 : random.nextInt(1 << length);
                    writer.write(raw, length);
                    sf[i] = raw + base;
                }
            } else if (channel.index == 0 && mode == 2 || channel.index == 1 && (mode == 1 || mode == 3)) {
                var own = channel.index == 0 || mode == 3;
                var baseline = own ? channel.scaleFactorsPrevious : block.channels[0].scaleFactors;
                var baselineLength = own ? block.quantizationUnitsPrevious : block.extensionUnit;
                distance(writer, sf, units, baseline, baselineLength, random, attempt > 200);
            } else {
                var own = channel.index == 0;
                var baseline = own ? channel.scaleFactorsPrevious : block.channels[0].scaleFactors;
                var baselineLength = own ? block.quantizationUnitsPrevious : block.extensionUnit;
                ok = deltaOffsetWithBaseline(writer, sf, units, baseline, baselineLength, random);
            }
            for (var i = 0; ok && i < units; i++) {
                ok = sf[i] >= 0 && sf[i] <= 31;
            }
            if (!ok) {
                continue;
            }
            Atrac9Unpacker.calculateMask(channel);
            Atrac9Unpacker.calculatePrecisions(channel);
            for (var i = 0; ok && i < channel.codedQuantUnits; i++) {
                ok = channel.precisionsFine[i] <= 15;
            }
            if (!ok) {
                if (attempt > 400) {
                    throw new IllegalStateException("no legal scale factors");
                }
                continue;
            }
            Atrac9Unpacker.calculateSpectrumCodebookIndex(channel);
            return writer;
        }
    }

    private static boolean deltaOffset(BitWriter writer, int[] sf, int units, Random random) {
        var weightIndex = random.nextInt(8);
        var weights = Atrac9Tables.SCALE_FACTOR_WEIGHTS[weightIndex];
        var length = between(random, 3, 6);
        var codebook = Atrac9Codebooks.SCALE_FACTORS_UNSIGNED[length];
        var deltas = new int[units];
        sf[0] = random.nextInt(1 << length);
        for (var i = 1; i < units; i++) {
            deltas[i] = symbol(codebook, random, true);
            sf[i] = (sf[i - 1] + deltas[i]) & (codebook.valueMax - 1);
        }
        // the base that brings every value into range, if one does
        var low = 0;
        var high = 31;
        for (var i = 0; i < units; i++) {
            low = Math.max(low, weights[i] - sf[i]);
            high = Math.min(high, 31 + weights[i] - sf[i]);
        }
        if (low > high) {
            return false;
        }
        var base = between(random, low, high);
        writer.write(weightIndex, 3);
        writer.write(base, 5);
        writer.write(length - 3, 2);
        writer.write(sf[0], length);
        for (var i = 1; i < units; i++) {
            code(writer, codebook, deltas[i]);
        }
        for (var i = 0; i < units; i++) {
            sf[i] += base - weights[i];
        }
        return true;
    }

    private static void distance(BitWriter writer, int[] sf, int units, int[] baseline, int baselineLength,
                                 Random random, boolean zero) {
        var length = between(random, 2, 5);
        var codebook = Atrac9Codebooks.SCALE_FACTORS_SIGNED[length];
        writer.write(length - 2, 2);
        var count = Math.min(units, baselineLength);
        for (var i = 0; i < count; i++) {
            var symbol = zero && codebook.bits[0] != 0 ? 0 : symbol(codebook, random, true);
            code(writer, codebook, symbol);
            sf[i] = (baseline[i] + Atrac9BitReader.signExtend(symbol, codebook.valueBits)) & 31;
        }
        for (var i = count; i < units; i++) {
            sf[i] = zero ? 0 : random.nextInt(32);
            writer.write(sf[i], 5);
        }
    }

    private static boolean deltaOffsetWithBaseline(BitWriter writer, int[] sf, int units, int[] baseline,
                                                   int baselineLength, Random random) {
        var length = between(random, 1, 4);
        var codebook = Atrac9Codebooks.SCALE_FACTORS_UNSIGNED[length];
        var count = Math.min(units, baselineLength);
        var deltas = new int[units];
        sf[0] = random.nextInt(1 << length);
        for (var i = 1; i < count; i++) {
            deltas[i] = symbol(codebook, random, true);
            sf[i] = (sf[i - 1] + deltas[i]) & (codebook.valueMax - 1);
        }
        var low = -16;
        var high = 15;
        for (var i = 0; i < count; i++) {
            low = Math.max(low, -sf[i] - baseline[i]);
            high = Math.min(high, 31 - sf[i] - baseline[i]);
        }
        if (low > high) {
            return false;
        }
        var base = between(random, low, high);
        writer.write(base + 16, 5);
        writer.write(length - 1, 2);
        writer.write(sf[0], length);
        for (var i = 1; i < count; i++) {
            code(writer, codebook, deltas[i]);
        }
        for (var i = 0; i < count; i++) {
            sf[i] += base + baseline[i];
        }
        for (var i = count; i < units; i++) {
            sf[i] = random.nextInt(32);
            writer.write(sf[i], 5);
        }
        return true;
    }

    private static void writeRandomSpectra(Atrac9Channel channel, BitWriter writer, Random random) {
        var maxHuff = Atrac9Tables.MAX_HUFF_PRECISION[channel.block.config.highSampleRate() ? 1 : 0];
        for (var i = 0; i < channel.codedQuantUnits; i++) {
            var count = Atrac9Tables.QUANT_UNIT_TO_COEFF_COUNT[i];
            var precision = channel.precisions[i] + 1;
            if (precision <= maxHuff) {
                var codebook = Atrac9Codebooks.SPECTRUM[channel.codebookSet[i]][precision]
                        [Atrac9Tables.QUANT_UNIT_TO_CODEBOOK_INDEX[i]];
                for (var j = 0; j < count >> codebook.valueCountPower; j++) {
                    code(writer, codebook, symbol(codebook, random, false));
                }
            } else {
                for (var j = 0; j < count; j++) {
                    writer.write(random.nextInt(1 << precision), precision);
                }
            }
        }
        for (var i = 0; i < channel.codedQuantUnits; i++) {
            if (channel.precisionsFine[i] > 0) {
                var bits = channel.precisionsFine[i] + 1;
                for (var j = 0; j < count(i); j++) {
                    writer.write(random.nextInt(1 << bits), bits);
                }
            }
        }
    }

    /** Returns the symbol with a code whose values are nearest to a group of quantized values. */
    private static int nearest(Atrac9Codebook codebook, int[] values, int from) {
        var mask = (1 << codebook.valueBits) - 1;
        var best = -1;
        var bestError = Long.MAX_VALUE;
        for (var symbol = 0; symbol < codebook.bits.length; symbol++) {
            if (codebook.bits[symbol] == 0) {
                continue;
            }
            var error = 0L;
            for (var v = 0; v < codebook.valueCount; v++) {
                var value = Atrac9BitReader.signExtend(symbol >> (v * codebook.valueBits) & mask, codebook.valueBits);
                var difference = (long) value - values[from + v];
                error += difference * difference;
            }
            if (error < bestError) {
                bestError = error;
                best = symbol;
            }
        }
        return best;
    }

    private static int count(int unit) {
        return Atrac9Tables.QUANT_UNIT_TO_COEFF_COUNT[unit];
    }

    /**
     * The forward transform that inverts the decoder's synthesis: the analysis matrix of one frame size, in the
     * layout {@code [coefficient][sample]} over the two frames a spectrum spans.
     */
    static double[][] analysis(int power) {
        var size = 1 << power;
        var config = new Atrac9Config(power == 6 ? 0 : power == 7 ? 2 : 5, 0, 1, 0);
        var block = new Atrac9Block(config, Atrac9Tables.MONO);
        var synthesis = new Atrac9Synthesis();
        var basis = new double[size][2 * size];
        for (var k = 0; k < size; k++) {
            var channel = new Atrac9Channel(block, 0);
            channel.spectra[k] = 1;
            synthesis.imdct(channel, power);
            System.arraycopy(channel.pcm, 0, basis[k], 0, size);
            // the next frame adds the first half of the overlap and subtracts the second
            for (var i = 0; i < size; i++) {
                basis[k][size + i] = i < size / 2 ? channel.imdctPrevious[i] : -channel.imdctPrevious[i];
            }
        }
        var window = new double[size];
        for (var i = 0; i < size; i++) {
            window[i] = (StrictMath.sin(((i + 0.5) / size - 0.5) * Math.PI) + 1.0) * 0.5;
        }
        var analysis = new double[size][2 * size];
        var trace = 0.0;
        for (var k = 0; k < size; k++) {
            for (var r = 0; r < 2 * size; r++) {
                var index = r < size ? r : 2 * size - 1 - r;
                var weight = window[index] * window[index] + window[size - 1 - index] * window[size - 1 - index];
                analysis[k][r] = basis[k][r] * weight;
                trace += analysis[k][r] * basis[k][r];
            }
        }
        var norm = trace / size;
        for (var k = 0; k < size; k++) {
            for (var r = 0; r < 2 * size; r++) {
                analysis[k][r] /= norm;
            }
        }
        return analysis;
    }

    /**
     * Encodes audio: every channel of the configuration, in its block order, at the full band count, without
     * intensity stereo or band extension, with a constant gradient raised until each frame fits its size.
     *
     * @param rateIndex the sampling rate index
     * @param channelConfig the channel configuration index
     * @param superframeIndex the base-2 logarithm of the frames per superframe
     * @param frameBytes the frame size
     * @param samples the samples of each channel, in 16-bit units; the decoded stream reproduces them after a delay of
     * one frame
     * @param vlc whether every channel codes its scale factors as deltas (mode 0), rather than the first channel of
     * each block fixed-length and the second as distances to the first
     */
    static Stream encode(int rateIndex, int channelConfig, int superframeIndex, int frameBytes, double[][] samples,
                         boolean vlc) {
        return encode(rateIndex, channelConfig, superframeIndex, frameBytes, samples, vlc, 0);
    }

    /**
     * Encodes audio as {@link #encode(int, int, int, int, double[][], boolean)} does, coding at most a number of
     * bands.
     *
     * @param bands the largest band count to code, or 0 for the rate's largest
     */
    static Stream encode(int rateIndex, int channelConfig, int superframeIndex, int frameBytes, double[][] samples,
                         boolean vlc, int bands) {
        var config = new Atrac9Config(rateIndex, channelConfig, frameBytes, superframeIndex);
        var power = config.frameSamplesPower();
        var size = 1 << power;
        var analysis = analysis(power);
        LAST_GRADIENTS.clear();
        var blocks = blocks(config);
        var length = samples[0].length;
        var frameCount = (length + size + size - 1) / size;
        frameCount = (frameCount + config.framesPerSuperframe() - 1) / config.framesPerSuperframe()
                * config.framesPerSuperframe();
        var coefficients = new double[samples.length][size];
        var segment = new double[2 * size];
        var all = new ArrayList<List<byte[]>>();
        var frames = new ArrayList<byte[]>();
        for (var n = 0; n < frameCount; n++) {
            var channelIndex = 0;
            var spectra = new double[samples.length][];
            for (var c = 0; c < samples.length; c++) {
                // the input is delayed by one frame: spectrum n spans the input frames n - 1 and n
                for (var t = 0; t < 2 * size; t++) {
                    var at = (n - 1) * size + t;
                    segment[t] = at >= 0 && at < length ? samples[c][at] : 0;
                }
                for (var k = 0; k < size; k++) {
                    var sum = 0.0;
                    for (var t = 0; t < 2 * size; t++) {
                        sum += analysis[k][t] * segment[t];
                    }
                    coefficients[c][k] = sum;
                }
                spectra[c] = coefficients[c].clone();
            }
            var first = n % config.framesPerSuperframe() == 0;
            byte[] frame = null;
            var chosen = 0;
            for (var gradient = 1; gradient <= 31 && frame == null; gradient++) {
                chosen = gradient;
                var candidate = signalFrame(blocks, spectra, first, gradient, bands, vlc);
                if (candidate.length <= frameBytes || gradient == 31) {
                    frame = candidate;
                }
            }
            LAST_GRADIENTS.add(chosen);
            frames.add(frame);
            if (frames.size() == config.framesPerSuperframe()) {
                all.add(frames);
                frames = new ArrayList<>();
            }
        }
        return pack(rateIndex, channelConfig, superframeIndex, all, frameBytes);
    }

    /**
     * Writes one frame of every block; every block but the last ends on a byte boundary, padded by the skipped
     * extension data where it would not, as a decoder that does not align between blocks needs.
     */
    private static byte[] signalFrame(Atrac9Block[] blocks, double[][] spectra, boolean first, int gradient,
                                      int bands, boolean vlc) {
        var writer = new BitWriter();
        var channelIndex = 0;
        for (var b = 0; b < blocks.length; b++) {
            var block = blocks[b];
            var blockSpectra = new double[block.channels.length][];
            for (var c = 0; c < block.channels.length; c++) {
                blockSpectra[c] = spectra[channelIndex++];
            }
            var blockPad = 0;
            if (b < blocks.length - 1) {
                // a low-frequency effects block is 32 or 48 bits, already whole bytes
                blockPad = 0;
                for (var candidate = 0; candidate <= 38 && block.type != Atrac9Tables.LFE;
                     candidate = candidate == 0 ? 7 : candidate + 1) {
                    var bits = writeSignalBlock(block, new BitWriter(), first, gradient, candidate, bands,
                            blockSpectra, vlc);
                    if (bits % 8 == 0) {
                        blockPad = candidate;
                        break;
                    }
                }
            }
            writeSignalBlock(block, writer, first, gradient, blockPad, bands, blockSpectra, vlc);
        }
        return writer.toBytes();
    }

    private static int scaleFactorOf(double peak, double ceiling) {
        for (var sf = 0; sf < 31; sf++) {
            if (peak <= Atrac9Tables.SPECTRUM_SCALE[sf] * ceiling) {
                return sf;
            }
        }
        return 31;
    }

    /** Writes one block of the signal encoder and returns its length in bits before its byte alignment. */
    private static int writeSignalBlock(Atrac9Block block, BitWriter writer, boolean first, int gradient, int pad,
                                        int bands, double[][] spectra, boolean vlc) {
        var start = writer.bits;
        var config = block.config;
        writer.write(first ? 0 : 1, 1);
        writer.write(0, 1);
        block.firstInSuperframe = first;
        block.reuseBandParams = false;
        if (block.type == Atrac9Tables.LFE) {
            var sf = new int[2];
            for (var u = 0; u < 2; u++) {
                var peak = 0.0;
                for (var k = 2 * u; k < 2 * u + 2; k++) {
                    peak = Math.max(peak, Math.abs(spectra[0][k]));
                }
                sf[u] = scaleFactorOf(peak, 15 * Atrac9Tables.QUANTIZER_STEP_SIZE[4]);
                writer.write(sf[u], 5);
            }
            for (var k = 0; k < 4; k++) {
                var step = Atrac9Tables.QUANTIZER_STEP_SIZE[4] * Atrac9Tables.SPECTRUM_SCALE[sf[k / 2]];
                var q = (int) Math.clamp(Math.round(spectra[0][k] / step), -15, 15);
                writer.write(q & 31, 5);
            }
            var length = writer.bits - start;
            writer.align();
            return length;
        }
        var high = config.highSampleRate() ? 1 : 0;
        var minBand = Atrac9Tables.MIN_BAND_COUNT[high];
        block.bandCount = Atrac9Tables.MAX_BAND_COUNT[config.sampleRateIndex()];
        if (bands > 0) {
            block.bandCount = Math.min(block.bandCount, bands);
        }
        block.quantizationUnitCount = Atrac9Tables.BAND_TO_QUANT_UNIT_COUNT[block.bandCount];
        block.stereoBand = block.bandCount;
        block.stereoQuantizationUnit = Atrac9Tables.BAND_TO_QUANT_UNIT_COUNT[block.stereoBand];
        block.bandExtensionEnabled = false;
        block.extensionBand = block.bandCount;
        block.extensionUnit = block.quantizationUnitCount;
        writer.write(block.bandCount - minBand, 4);
        if (block.type == Atrac9Tables.STEREO) {
            writer.write(block.stereoBand - minBand, 4);
        }
        writer.write(0, 1);
        block.gradientMode = 0;
        block.gradientStartUnit = 0;
        block.gradientEndUnit = 1;
        block.gradientStartValue = gradient;
        block.gradientEndValue = gradient;
        block.gradientBoundary = 0;
        writer.write(0, 2);
        writer.write(0, 6);
        writer.write(0, 6);
        writer.write(gradient, 5);
        writer.write(gradient, 5);
        writer.write(block.gradientBoundary, 4);
        Atrac9Unpacker.createGradient(block);
        if (block.type == Atrac9Tables.STEREO) {
            block.primaryChannelIndex = 0;
            writer.write(0, 1);
            writer.write(0, 1);
        }
        if (pad > 0) {
            // extension data without band extension, which the decoder skips: 7 bits of header, then pad - 7 bits
            writer.write(1, 1);
            writer.write(0, 2);
            writer.write(pad - 7, 5);
            writer.write(0, pad - 7);
        } else {
            writer.write(0, 1);
        }
        var units = block.quantizationUnitCount;
        for (var channel : block.channels) {
            channel.codedQuantUnits = channel.index == 0 ? units : block.stereoQuantizationUnit;
            var sf = channel.scaleFactors;
            Arrays.fill(sf, 0);
            for (var u = 0; u < units; u++) {
                var peak = 0.0;
                for (var k = Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[u]; k < Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[u + 1];
                     k++) {
                    peak = Math.max(peak, Math.abs(spectra[channel.index][k]));
                }
                sf[u] = scaleFactorOf(peak, 0.95);
            }
            if (vlc) {
                writeDeltaOffset(writer, sf, units);
            } else if (channel.index == 0) {
                writer.write(1, 2);
                writer.write(3, 2);
                for (var u = 0; u < units; u++) {
                    writer.write(sf[u], 5);
                }
            } else {
                // distances to the first channel's scale factors, in the 5-bit signed codebook
                writer.write(1, 2);
                writer.write(3, 2);
                var codebook = Atrac9Codebooks.SCALE_FACTORS_SIGNED[5];
                for (var u = 0; u < units; u++) {
                    code(writer, codebook, (sf[u] - block.channels[0].scaleFactors[u]) & 31);
                }
            }
            System.arraycopy(sf, 0, channel.scaleFactorsPrevious, 0, 31);
            Atrac9Unpacker.calculateMask(channel);
            Atrac9Unpacker.calculatePrecisions(channel);
            Atrac9Unpacker.calculateSpectrumCodebookIndex(channel);
            writeSignalSpectra(channel, writer, spectra[channel.index]);
        }
        block.quantizationUnitsPrevious = units;
        var length = writer.bits - start;
        writer.align();
        return length;
    }

    /** Writes scale factors as deltas (mode 0) with the first weights, a zero base and 6-bit deltas. */
    private static void writeDeltaOffset(BitWriter writer, int[] sf, int units) {
        var weights = Atrac9Tables.SCALE_FACTOR_WEIGHTS[0];
        var codebook = Atrac9Codebooks.SCALE_FACTORS_UNSIGNED[6];
        writer.write(0, 2);
        writer.write(0, 3);
        writer.write(0, 5);
        writer.write(3, 2);
        var previous = sf[0] + weights[0];
        writer.write(previous, 6);
        for (var u = 1; u < units; u++) {
            var raw = sf[u] + weights[u];
            code(writer, codebook, (raw - previous) & 63);
            previous = raw;
        }
    }

    private static void writeSignalSpectra(Atrac9Channel channel, BitWriter writer, double[] spectra) {
        var maxHuff = Atrac9Tables.MAX_HUFF_PRECISION[channel.block.config.highSampleRate() ? 1 : 0];
        var fine = new int[256];
        for (var u = 0; u < channel.codedQuantUnits; u++) {
            var start = Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[u];
            var count = count(u);
            var p = channel.precisions[u];
            var scale = Atrac9Tables.SPECTRUM_SCALE[channel.scaleFactors[u]];
            var step = Atrac9Tables.QUANTIZER_STEP_SIZE[p] * scale;
            var limit = (1 << p) - 1;
            var q = new int[count];
            for (var j = 0; j < count; j++) {
                q[j] = (int) Math.clamp(Math.round(spectra[start + j] / step), -limit, limit);
                if (channel.precisionsFine[u] > 0) {
                    var f = channel.precisionsFine[u];
                    var fineStep = Atrac9Tables.QUANTIZER_FINE_STEP_SIZE[f] * scale;
                    var fineLimit = (1 << f) - 1;
                    fine[start + j] = (int) Math.clamp(Math.round((spectra[start + j] - q[j] * step) / fineStep),
                            -fineLimit, fineLimit);
                }
            }
            var precision = p + 1;
            if (precision <= maxHuff) {
                var codebook = Atrac9Codebooks.SPECTRUM[channel.codebookSet[u]][precision]
                        [Atrac9Tables.QUANT_UNIT_TO_CODEBOOK_INDEX[u]];
                var mask = (1 << codebook.valueBits) - 1;
                for (var g = 0; g < count >> codebook.valueCountPower; g++) {
                    var symbol = 0;
                    for (var v = 0; v < codebook.valueCount; v++) {
                        symbol |= (q[g * codebook.valueCount + v] & mask) << (v * codebook.valueBits);
                    }
                    if (codebook.bits[symbol] == 0) {
                        symbol = nearest(codebook, q, g * codebook.valueCount);
                    }
                    code(writer, codebook, symbol);
                }
            } else {
                for (var j = 0; j < count; j++) {
                    writer.write(q[j] & ((1 << precision) - 1), precision);
                }
            }
        }
        for (var u = 0; u < channel.codedQuantUnits; u++) {
            if (channel.precisionsFine[u] > 0) {
                var bits = channel.precisionsFine[u] + 1;
                for (var j = 0; j < count(u); j++) {
                    writer.write(fine[Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[u] + j] & ((1 << bits) - 1), bits);
                }
            }
        }
    }
}

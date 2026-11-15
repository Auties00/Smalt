/* Ports LibAtrac9 (C/src/unpack.c, C/src/scale_factors.c, C/src/bit_allocation.c) at commit efca2e3. Copyright 2018
   Alex Barney; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.audio.atrac9;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Unpacks the blocks of an ATRAC9 frame: the band, gradient, stereo and band extension parameters, the scale
 * factors, the bit allocation and the quantized spectra.
 *
 * <p>Instances are not thread-safe.
 */
final class Atrac9Unpacker {
    /**
     * The quantization units of a low-frequency effects block.
     */
    private static final int LFE_UNITS = 2;

    /**
     * The quantization unit count minus 13, the first one a band extension can follow, of each band extension
     * group: the first quantization units of groups B and C and the band count ({@code BexGroupInfo}).
     */
    static final int[][] BEX_GROUP_INFO = {
            {16, 21, 0}, {18, 22, 1}, {20, 22, 2}, {21, 22, 3}, {21, 22, 3}, {23, 24, 4}, {23, 24, 4}, {24, 24, 5}
    };

    /**
     * The values a band extension codes, per mode and band count ({@code BexEncodedValueCounts}).
     */
    private static final int[][] BEX_ENCODED_VALUE_COUNTS = {
            {0, 0, 0, 4, 4, 2},
            {0, 0, 0, 0, 0, 0},
            {0, 0, 0, 2, 2, 1},
            {0, 0, 0, 2, 2, 2},
            {1, 1, 1, 0, 0, 0}
    };

    /**
     * The width in bits of each value a band extension codes, per mode and band count ({@code BexDataLengths}).
     */
    private static final int[][][] BEX_DATA_LENGTHS = {
            {{0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {5, 4, 3, 3}, {4, 4, 3, 4}, {4, 5, 0, 0}},
            {{0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}},
            {{0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {6, 6, 0, 0}, {6, 6, 0, 0}, {6, 0, 0, 0}},
            {{0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {4, 4, 0, 0}, {4, 4, 0, 0}, {4, 4, 0, 0}},
            {{3, 0, 0, 0}, {3, 0, 0, 0}, {3, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}}
    };

    private final int[] values = new int[16];

    /**
     * Unpacks one block and aligns the reader to the next byte ({@code UnpackBlock}).
     *
     * @param block the block to fill, whose state carries over from its previous frame
     * @param reader the reader at the block's first bit
     * @throws MediaException.Malformed if the block codes invalid parameters
     */
    void unpackBlock(Atrac9Block block, Atrac9BitReader reader) {
        block.firstInSuperframe = reader.read(1) == 0;
        block.reuseBandParams = reader.read(1) == 1;
        if (block.firstInSuperframe && block.reuseBandParams && block.type != Atrac9Tables.LFE) {
            throw malformed("reuses the band parameters in the first frame of a superframe");
        }
        if (block.type == Atrac9Tables.LFE) {
            unpackLfeBlock(block, reader);
        } else {
            unpackStandardBlock(block, reader);
        }
        reader.alignByte();
    }

    private void unpackStandardBlock(Atrac9Block block, Atrac9BitReader reader) {
        if (!block.reuseBandParams) {
            readBandParams(block, reader);
        }
        readGradientParams(block, reader);
        createGradient(block);
        readStereoParams(block, reader);
        readExtensionParams(block, reader);
        for (var channel : block.channels) {
            channel.codedQuantUnits = block.primaryChannelIndex == channel.index
                    ? block.quantizationUnitCount : block.stereoQuantizationUnit;
            readScaleFactors(channel, reader);
            calculateMask(channel);
            calculatePrecisions(channel);
            calculateSpectrumCodebookIndex(channel);
            readSpectra(channel, reader);
            readSpectraFine(channel, reader);
        }
        block.quantizationUnitsPrevious = block.bandExtensionEnabled
                ? block.extensionUnit : block.quantizationUnitCount;
    }

    /**
     * Reads the band parameters; the block keeps its previous ones when they are invalid, so that a later frame
     * reusing them never sees a partial update.
     */
    private static void readBandParams(Atrac9Block block, Atrac9BitReader reader) {
        var high = block.config.highSampleRate() ? 1 : 0;
        var minBandCount = Atrac9Tables.MIN_BAND_COUNT[high];
        var maxExtensionBand = Atrac9Tables.MAX_EXTENSION_BAND[high];
        var bandCount = reader.read(4) + minBandCount;
        if (bandCount > Atrac9Tables.MAX_BAND_COUNT[block.config.sampleRateIndex()]) {
            throw malformed("codes " + bandCount + " bands");
        }
        var stereoBand = bandCount;
        if (block.type == Atrac9Tables.STEREO) {
            stereoBand = reader.read(4) + minBandCount;
            if (stereoBand > bandCount) {
                throw malformed("codes stereo band " + stereoBand + " above band count " + bandCount);
            }
        }
        var bandExtension = reader.read(1) == 1;
        var extensionBand = bandCount;
        if (bandExtension) {
            extensionBand = reader.read(4) + minBandCount;
            if (extensionBand < bandCount || extensionBand > maxExtensionBand) {
                throw malformed("codes band extension band " + extensionBand);
            }
        }
        block.bandCount = bandCount;
        block.quantizationUnitCount = Atrac9Tables.BAND_TO_QUANT_UNIT_COUNT[bandCount];
        block.stereoBand = stereoBand;
        if (block.type == Atrac9Tables.STEREO) {
            block.stereoQuantizationUnit = Atrac9Tables.BAND_TO_QUANT_UNIT_COUNT[stereoBand];
        }
        block.bandExtensionEnabled = bandExtension;
        block.extensionBand = extensionBand;
        block.extensionUnit = Atrac9Tables.BAND_TO_QUANT_UNIT_COUNT[extensionBand];
    }

    private static void readGradientParams(Atrac9Block block, Atrac9BitReader reader) {
        block.gradientMode = reader.read(2);
        if (block.gradientMode > 0) {
            block.gradientEndUnit = 31;
            block.gradientEndValue = 31;
            block.gradientStartUnit = reader.read(5);
            block.gradientStartValue = reader.read(5);
        } else {
            block.gradientStartUnit = reader.read(6);
            block.gradientEndUnit = reader.read(6) + 1;
            block.gradientStartValue = reader.read(5);
            block.gradientEndValue = reader.read(5);
        }
        block.gradientBoundary = reader.read(4);
        if (block.gradientBoundary > block.quantizationUnitCount) {
            throw malformed("codes gradient boundary " + block.gradientBoundary);
        }
        // The reference rejects units from 48 on; past 31 it writes beyond its 31-entry gradient (undefined in
        // the C library, an exception in the C# one), so they are rejected here.
        if (block.gradientStartUnit >= 48 || block.gradientEndUnit >= 48
                || block.gradientStartUnit > block.gradientEndUnit || block.gradientEndUnit > 31) {
            throw malformed("codes gradient units " + block.gradientStartUnit + " to " + block.gradientEndUnit);
        }
    }

    /**
     * Builds the gradient of every quantization unit ({@code CreateGradient}).
     */
    static void createGradient(Atrac9Block block) {
        var valueCount = block.gradientEndValue - block.gradientStartValue;
        var unitCount = block.gradientEndUnit - block.gradientStartUnit;
        var gradient = block.gradient;
        for (var i = 0; i < block.gradientEndUnit; i++) {
            gradient[i] = block.gradientStartValue;
        }
        for (var i = block.gradientEndUnit; i <= block.quantizationUnitCount; i++) {
            gradient[i] = block.gradientEndValue;
        }
        if (unitCount <= 0 || valueCount == 0) {
            return;
        }
        var curve = Atrac9Tables.GRADIENT_CURVES[unitCount - 1];
        if (valueCount < 0) {
            var scale = (-valueCount - 1) / 31.0;
            var base = block.gradientStartValue - 1;
            for (var i = block.gradientStartUnit; i < block.gradientEndUnit; i++) {
                gradient[i] = base - (int) (curve[i - block.gradientStartUnit] * scale);
            }
        } else {
            var scale = (valueCount - 1) / 31.0;
            var base = block.gradientStartValue + 1;
            for (var i = block.gradientStartUnit; i < block.gradientEndUnit; i++) {
                gradient[i] = base + (int) (curve[i - block.gradientStartUnit] * scale);
            }
        }
    }

    private static void readStereoParams(Atrac9Block block, Atrac9BitReader reader) {
        if (block.type != Atrac9Tables.STEREO) {
            return;
        }
        block.primaryChannelIndex = reader.read(1);
        block.hasJointStereoSigns = reader.read(1) == 1;
        if (block.hasJointStereoSigns) {
            for (var i = block.stereoQuantizationUnit; i < block.quantizationUnitCount; i++) {
                block.jointStereoSigns[i] = reader.read(1);
            }
        } else {
            Arrays.fill(block.jointStereoSigns, 0);
        }
    }

    /**
     * {@return the band count of the band extension group of a block's quantization unit count}
     */
    static int bexBand(Atrac9Block block) {
        var group = block.quantizationUnitCount - 13;
        if (group < 0 || group >= BEX_GROUP_INFO.length) {
            // the reference indexes its group table out of bounds
            throw malformed("enables the band extension above " + block.quantizationUnitCount + " quantization units");
        }
        return BEX_GROUP_INFO[group][2];
    }

    private static void bexReadHeader(Atrac9Channel channel, Atrac9BitReader reader, int bexBand) {
        var mode = reader.read(2);
        channel.bexMode = bexBand > 2 ? mode : 4;
        channel.bexValueCount = BEX_ENCODED_VALUE_COUNTS[channel.bexMode][bexBand];
    }

    private static void bexReadData(Atrac9Channel channel, Atrac9BitReader reader, int bexBand) {
        for (var i = 0; i < channel.bexValueCount; i++) {
            channel.bexValues[i] = reader.read(BEX_DATA_LENGTHS[channel.bexMode][bexBand][i]);
        }
    }

    private static void readExtensionParams(Atrac9Block block, Atrac9BitReader reader) {
        var bexBand = 0;
        if (block.bandExtensionEnabled) {
            bexBand = bexBand(block);
            if (block.type == Atrac9Tables.STEREO) {
                bexReadHeader(block.channels[1], reader, bexBand);
            } else {
                reader.skip(1);
            }
        }
        block.hasExtensionData = reader.read(1) == 1;
        if (!block.hasExtensionData) {
            return;
        }
        if (!block.bandExtensionEnabled) {
            block.bexMode = reader.read(2);
            block.bexDataLength = reader.read(5);
            reader.skip(block.bexDataLength);
            return;
        }
        bexReadHeader(block.channels[0], reader, bexBand);
        block.bexDataLength = reader.read(5);
        if (block.bexDataLength == 0) {
            return;
        }
        var end = reader.position() + block.bexDataLength;
        bexReadData(block.channels[0], reader, bexBand);
        if (block.type == Atrac9Tables.STEREO) {
            bexReadData(block.channels[1], reader, bexBand);
        }
        if (reader.position() > end) {
            throw malformed("reads band extension data past its " + block.bexDataLength + " bits");
        }
    }

    /**
     * Reads the scale factors of a channel ({@code ReadScaleFactors}).
     */
    private void readScaleFactors(Atrac9Channel channel, Atrac9BitReader reader) {
        var block = channel.block;
        var sf = channel.scaleFactors;
        Arrays.fill(sf, 0);
        channel.scaleFactorCodingMode = reader.read(2);
        if (channel.index == 0) {
            switch (channel.scaleFactorCodingMode) {
                case 0 -> readVlcDeltaOffset(channel, reader);
                case 1 -> readClcOffset(channel, reader);
                case 2 -> {
                    requireNotFirst(block);
                    readVlcDistanceToBaseline(channel, reader, channel.scaleFactorsPrevious,
                            block.quantizationUnitsPrevious);
                }
                default -> {
                    requireNotFirst(block);
                    readVlcDeltaOffsetWithBaseline(channel, reader, channel.scaleFactorsPrevious,
                            block.quantizationUnitsPrevious);
                }
            }
        } else {
            switch (channel.scaleFactorCodingMode) {
                case 0 -> readVlcDeltaOffset(channel, reader);
                case 1 -> readVlcDistanceToBaseline(channel, reader, block.channels[0].scaleFactors,
                        block.extensionUnit);
                case 2 -> readVlcDeltaOffsetWithBaseline(channel, reader, block.channels[0].scaleFactors,
                        block.extensionUnit);
                default -> {
                    requireNotFirst(block);
                    readVlcDistanceToBaseline(channel, reader, channel.scaleFactorsPrevious,
                            block.quantizationUnitsPrevious);
                }
            }
        }
        // the units past the extension unit hold zeros, but for the first unit of a block without units, whose
        // value the reference reads unchecked and then uses as a table index
        for (var value : sf) {
            if (value < 0 || value > 31) {
                throw malformed("codes scale factor " + value);
            }
        }
        System.arraycopy(sf, 0, channel.scaleFactorsPrevious, 0, sf.length);
    }

    private static void requireNotFirst(Atrac9Block block) {
        if (block.firstInSuperframe) {
            throw malformed("codes scale factors against the previous frame in the first frame of a superframe");
        }
    }

    private static void readClcOffset(Atrac9Channel channel, Atrac9BitReader reader) {
        var sf = channel.scaleFactors;
        var bitLength = reader.read(2) + 2;
        var base = bitLength < 5 ? reader.read(5) : 0;
        for (var i = 0; i < channel.block.extensionUnit; i++) {
            sf[i] = reader.read(bitLength) + base;
        }
    }

    private static void readVlcDeltaOffset(Atrac9Channel channel, Atrac9BitReader reader) {
        var weights = Atrac9Tables.SCALE_FACTOR_WEIGHTS[reader.read(3)];
        var sf = channel.scaleFactors;
        var base = reader.read(5);
        var bitLength = reader.read(2) + 3;
        var codebook = Atrac9Codebooks.SCALE_FACTORS_UNSIGNED[bitLength];
        var units = channel.block.extensionUnit;
        sf[0] = reader.read(bitLength);
        for (var i = 1; i < units; i++) {
            var delta = codebook.read(reader, false);
            sf[i] = (sf[i - 1] + delta) & (codebook.valueMax - 1);
        }
        for (var i = 0; i < units; i++) {
            sf[i] += base - weights[i];
        }
    }

    private static void readVlcDistanceToBaseline(Atrac9Channel channel, Atrac9BitReader reader, int[] baseline,
                                                  int baselineLength) {
        var sf = channel.scaleFactors;
        var bitLength = reader.read(2) + 2;
        var codebook = Atrac9Codebooks.SCALE_FACTORS_SIGNED[bitLength];
        var units = channel.block.extensionUnit;
        var unitCount = Math.min(units, baselineLength);
        for (var i = 0; i < unitCount; i++) {
            var distance = codebook.read(reader, true);
            sf[i] = (baseline[i] + distance) & 31;
        }
        for (var i = unitCount; i < units; i++) {
            sf[i] = reader.read(5);
        }
    }

    private static void readVlcDeltaOffsetWithBaseline(Atrac9Channel channel, Atrac9BitReader reader, int[] baseline,
                                                       int baselineLength) {
        var sf = channel.scaleFactors;
        var base = reader.readOffsetBinary(5);
        var bitLength = reader.read(2) + 1;
        var codebook = Atrac9Codebooks.SCALE_FACTORS_UNSIGNED[bitLength];
        var units = channel.block.extensionUnit;
        var unitCount = Math.min(units, baselineLength);
        sf[0] = reader.read(bitLength);
        for (var i = 1; i < unitCount; i++) {
            var delta = codebook.read(reader, false);
            sf[i] = (sf[i - 1] + delta) & (codebook.valueMax - 1);
        }
        for (var i = 0; i < unitCount; i++) {
            sf[i] += base + baseline[i];
        }
        for (var i = unitCount; i < units; i++) {
            sf[i] = reader.read(5);
        }
    }

    /**
     * Raises the precision next to each steep scale factor step ({@code CalculateMask}).
     */
    static void calculateMask(Atrac9Channel channel) {
        var mask = channel.precisionMask;
        var sf = channel.scaleFactors;
        Arrays.fill(mask, 0);
        for (var i = 1; i < channel.block.quantizationUnitCount; i++) {
            var delta = sf[i] - sf[i - 1];
            if (delta > 1) {
                mask[i] += Math.min(delta - 1, 5);
            } else if (delta < -1) {
                mask[i - 1] += Math.min(-delta - 1, 5);
            }
        }
    }

    /**
     * Derives the coarse and fine precision of each quantization unit ({@code CalculatePrecisions}).
     */
    static void calculatePrecisions(Atrac9Channel channel) {
        var block = channel.block;
        var units = block.quantizationUnitCount;
        var precisions = channel.precisions;
        var sf = channel.scaleFactors;
        if (block.gradientMode != 0) {
            for (var i = 0; i < units; i++) {
                var precision = sf[i] + channel.precisionMask[i] - block.gradient[i];
                if (precision > 0) {
                    precision = switch (block.gradientMode) {
                        case 1 -> precision / 2;
                        case 2 -> 3 * precision / 8;
                        default -> precision / 4;
                    };
                }
                precisions[i] = precision;
            }
        } else {
            for (var i = 0; i < units; i++) {
                precisions[i] = sf[i] - block.gradient[i];
            }
        }
        for (var i = 0; i < units; i++) {
            if (precisions[i] < 1) {
                precisions[i] = 1;
            }
        }
        for (var i = 0; i < block.gradientBoundary; i++) {
            precisions[i]++;
        }
        for (var i = 0; i < units; i++) {
            channel.precisionsFine[i] = 0;
            if (precisions[i] > 15) {
                channel.precisionsFine[i] = precisions[i] - 15;
                precisions[i] = 15;
            }
        }
    }

    /**
     * Selects the spectrum codebook set of each quantization unit from the shape of the scale factors
     * ({@code CalculateSpectrumCodebookIndex}).
     */
    static void calculateSpectrumCodebookIndex(Atrac9Channel channel) {
        var set = channel.codebookSet;
        Arrays.fill(set, 0);
        var units = channel.codedQuantUnits;
        var sf = channel.scaleFactors;
        if (units <= 1 || channel.block.config.highSampleRate()) {
            return;
        }
        // the unit past the last coded one temporarily repeats it, so that the last unit has a next neighbor
        var saved = sf[units];
        sf[units] = sf[units - 1];
        var average = 0;
        if (units > 12) {
            for (var i = 0; i < 12; i++) {
                average += sf[i];
            }
            average = (average + 6) / 12;
        }
        for (var i = 8; i < units; i++) {
            var previous = sf[i - 1];
            var next = sf[i + 1];
            var min = Math.min(previous, next);
            if (sf[i] - min >= 3 || sf[i] - previous + sf[i] - next >= 3) {
                set[i] = 1;
            }
        }
        for (var i = 12; i < units; i++) {
            if (set[i] == 0) {
                var min = Math.min(sf[i - 1], sf[i + 1]);
                if (sf[i] - min >= 2 && sf[i] >= average - (Atrac9Tables.QUANT_UNIT_TO_COEFF_COUNT[i] == 16 ? 1 : 0)) {
                    set[i] = 1;
                }
            }
        }
        sf[units] = saved;
    }

    private void readSpectra(Atrac9Channel channel, Atrac9BitReader reader) {
        var spectra = channel.quantizedSpectra;
        Arrays.fill(spectra, 0);
        var maxHuffPrecision = Atrac9Tables.MAX_HUFF_PRECISION[channel.block.config.highSampleRate() ? 1 : 0];
        for (var i = 0; i < channel.codedQuantUnits; i++) {
            var count = Atrac9Tables.QUANT_UNIT_TO_COEFF_COUNT[i];
            var start = Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[i];
            var precision = channel.precisions[i] + 1;
            if (precision <= maxHuffPrecision) {
                var codebook = Atrac9Codebooks.SPECTRUM[channel.codebookSet[i]][precision]
                        [Atrac9Tables.QUANT_UNIT_TO_CODEBOOK_INDEX[i]];
                var groups = count >> codebook.valueCountPower;
                for (var j = 0; j < groups; j++) {
                    values[j] = codebook.read(reader, false);
                }
                var mask = (1 << codebook.valueBits) - 1;
                var index = start;
                for (var j = 0; j < groups; j++) {
                    var value = values[j];
                    for (var k = 0; k < codebook.valueCount; k++) {
                        spectra[index++] = Atrac9BitReader.signExtend(value & mask, codebook.valueBits);
                        value >>= codebook.valueBits;
                    }
                }
            } else {
                for (var j = start; j < start + count; j++) {
                    spectra[j] = reader.readSigned(precision);
                }
            }
        }
    }

    private static void readSpectraFine(Atrac9Channel channel, Atrac9BitReader reader) {
        var fine = channel.quantizedSpectraFine;
        Arrays.fill(fine, 0);
        for (var i = 0; i < channel.codedQuantUnits; i++) {
            if (channel.precisionsFine[i] > 15) {
                // past the reference's 16-entry fine step table (undefined in the C library)
                throw malformed("codes fine precision " + channel.precisionsFine[i]);
            }
            if (channel.precisionsFine[i] > 0) {
                var bits = channel.precisionsFine[i] + 1;
                for (var j = Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[i]; j < Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[i + 1];
                     j++) {
                    fine[j] = reader.readSigned(bits);
                }
            }
        }
    }

    private static void unpackLfeBlock(Atrac9Block block, Atrac9BitReader reader) {
        var channel = block.channels[0];
        block.quantizationUnitCount = LFE_UNITS;
        Arrays.fill(channel.scaleFactors, 0);
        for (var i = 0; i < LFE_UNITS; i++) {
            channel.scaleFactors[i] = reader.read(5);
        }
        var precision = block.reuseBandParams ? 8 : 4;
        for (var i = 0; i < LFE_UNITS; i++) {
            channel.precisions[i] = precision;
            channel.precisionsFine[i] = 0;
        }
        channel.codedQuantUnits = LFE_UNITS;
        var spectra = channel.quantizedSpectra;
        Arrays.fill(spectra, 0);
        for (var i = 0; i < LFE_UNITS; i++) {
            for (var j = Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[i]; j < Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[i + 1];
                 j++) {
                spectra[j] = reader.readSigned(precision + 1);
            }
        }
    }

    static MediaException.Malformed malformed(String detail) {
        return new MediaException.Malformed("ATRAC9 block " + detail);
    }
}

/* Ports LibAtrac9 (C/src/quantization.c, C/src/band_extension.c, C/src/imdct.c, C/src/decoder.c) at commit efca2e3.
   Copyright 2018 Alex Barney; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.audio.atrac9;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Reconstructs the samples of an unpacked ATRAC9 block: dequantization, intensity stereo, scaling, band extension
 * and the inverse MDCT with its overlap-add.
 *
 * <p>Instances are not thread-safe.
 */
final class Atrac9Synthesis {
    /**
     * The band extension scales of mode 0 with three bands, each row padded to 32 entries with zeros.
     */
    private static final double[][] BEX_MODE0_BANDS3 = padded(32,
            new double[]{
                    0.000000e+0, 1.988220e-1, 2.514343e-1, 2.960510e-1, 3.263550e-1, 3.771362e-1, 3.786926e-1,
                    4.540405e-1, 4.877625e-1, 5.262451e-1, 5.447083e-1, 5.737000e-1, 6.212158e-1, 6.222839e-1,
                    6.560974e-1, 6.896667e-1, 7.555542e-1, 7.677917e-1, 7.918091e-1, 7.971497e-1, 8.188171e-1,
                    8.446045e-1, 9.790649e-1, 9.822083e-1, 9.846191e-1, 9.859314e-1, 9.863586e-1, 9.863892e-1,
                    9.873352e-1, 9.881287e-1, 9.898682e-1, 9.913330e-1
            },
            new double[]{
                    0.000000e+0, 9.982910e-1, 7.592773e-2, 7.179565e-1, 9.851379e-1, 5.340271e-1, 9.013672e-1,
                    6.349182e-1, 7.226257e-1, 1.948547e-1, 7.628174e-1, 9.873657e-1, 8.112183e-1, 2.715454e-1,
                    9.734192e-1, 1.443787e-1, 4.640198e-1, 3.249207e-1, 3.790894e-1, 8.276367e-2, 5.954590e-1,
                    2.864380e-1, 9.806824e-1, 7.929077e-1, 6.292114e-1, 4.887085e-1, 2.905273e-1, 1.301880e-1,
                    3.140869e-1, 5.482483e-1, 4.210815e-1, 1.182861e-1
            },
            new double[]{
                    0.000000e+0, 3.155518e-2, 8.581543e-2, 1.364746e-1, 1.858826e-1, 2.368469e-1, 2.888184e-1,
                    3.432617e-1, 4.012451e-1, 4.623108e-1, 5.271301e-1, 5.954895e-1, 6.681213e-1, 7.448425e-1,
                    8.245239e-1, 9.097290e-1
            },
            new double[]{
                    0.000000e+0, 4.418945e-2, 1.303711e-1, 2.273560e-1, 3.395996e-1, 4.735718e-1, 6.267090e-1,
                    8.003845e-1
            },
            new double[]{
                    0.000000e+0, 2.804565e-2, 9.683228e-2, 1.849976e-1, 3.005981e-1, 4.470520e-1, 6.168518e-1,
                    8.007813e-1
            });

    /**
     * The band extension scales of mode 0 with four bands, each row padded to 16 entries with zeros.
     */
    private static final double[][] BEX_MODE0_BANDS4 = padded(16,
            new double[]{
                    0.000000e+0, 2.708740e-1, 3.479614e-1, 3.578186e-1, 5.083618e-1, 5.299072e-1, 5.819092e-1,
                    6.381836e-1, 7.276917e-1, 7.595520e-1, 7.878723e-1, 9.707336e-1, 9.713135e-1, 9.736023e-1,
                    9.759827e-1, 9.832458e-1
            },
            new double[]{
                    0.000000e+0, 2.330627e-1, 5.891418e-1, 7.170410e-1, 2.036438e-1, 1.613464e-1, 6.668701e-1,
                    9.481201e-1, 9.769897e-1, 5.111694e-1, 3.522644e-1, 8.209534e-1, 2.933960e-1, 9.757690e-1,
                    5.289917e-1, 4.372253e-1
            },
            new double[]{
                    0.000000e+0, 4.360962e-2, 1.056519e-1, 1.590576e-1, 2.078857e-1, 2.572937e-1, 3.082581e-1,
                    3.616028e-1, 4.191589e-1, 4.792175e-1, 5.438538e-1, 6.125183e-1, 6.841125e-1, 7.589417e-1,
                    8.365173e-1, 9.148254e-1
            },
            new double[]{
                    0.000000e+0, 4.074097e-2, 1.164551e-1, 2.077026e-1, 3.184509e-1, 4.532166e-1, 6.124268e-1,
                    7.932129e-1
            },
            new double[]{
                    0.000000e+0, 8.880615e-3, 2.932739e-2, 5.593872e-2, 8.825684e-2, 1.259155e-1, 1.721497e-1,
                    2.270813e-1, 2.901611e-1, 3.579712e-1, 4.334106e-1, 5.147095e-1, 6.023254e-1, 6.956177e-1,
                    7.952881e-1, 8.977356e-1
            });

    /**
     * The band extension scales of mode 0 with five bands, each row padded to 32 entries with zeros.
     */
    private static final double[][] BEX_MODE0_BANDS5 = padded(32,
            new double[]{
                    0.000000e+0, 7.379150e-2, 1.806335e-1, 2.687073e-1, 3.407898e-1, 4.047546e-1, 4.621887e-1,
                    5.168762e-1, 5.703125e-1, 6.237488e-1, 6.763611e-1, 7.288208e-1, 7.808533e-1, 8.337708e-1,
                    8.874512e-1, 9.418030e-1
            },
            new double[]{
                    0.000000e+0, 7.980347e-2, 1.615295e-1, 1.665649e-1, 1.822205e-1, 2.185669e-1, 2.292175e-1,
                    2.456665e-1, 2.666321e-1, 3.306580e-1, 3.330688e-1, 3.765259e-1, 4.085083e-1, 4.400024e-1,
                    4.407654e-1, 4.817505e-1, 4.924011e-1, 5.320740e-1, 5.893860e-1, 6.131287e-1, 6.212463e-1,
                    6.278076e-1, 6.308899e-1, 7.660828e-1, 7.850647e-1, 7.910461e-1, 7.929382e-1, 8.038330e-1,
                    9.834900e-1, 9.846191e-1, 9.852295e-1, 9.862671e-1
            },
            new double[]{
                    0.000000e+0, 6.084290e-1, 3.672791e-1, 3.151855e-1, 1.488953e-1, 2.571716e-1, 5.103455e-1,
                    3.311157e-1, 5.426025e-2, 4.254456e-1, 7.998352e-1, 7.873230e-1, 5.418701e-1, 2.925110e-1,
                    8.468628e-2, 1.410522e-1, 9.819641e-1, 9.609070e-1, 3.530884e-2, 9.729004e-2, 5.758362e-1,
                    9.941711e-1, 7.215576e-1, 7.183228e-1, 2.028809e-1, 9.588623e-2, 2.032166e-1, 1.338806e-1,
                    5.003357e-1, 1.874390e-1, 9.804993e-1, 1.107788e-1
            });

    private static final double[] BEX_MODE2_SCALE = {
            4.272461e-4, 1.312256e-3, 2.441406e-3, 3.692627e-3, 4.913330e-3, 6.134033e-3, 7.507324e-3, 8.972168e-3,
            1.049805e-2, 1.223755e-2, 1.406860e-2, 1.599121e-2, 1.800537e-2, 2.026367e-2, 2.264404e-2, 2.517700e-2,
            2.792358e-2, 3.073120e-2, 3.344727e-2, 3.631592e-2, 3.952026e-2, 4.275513e-2, 4.608154e-2, 4.968262e-2,
            5.355835e-2, 5.783081e-2, 6.195068e-2, 6.677246e-2, 7.196045e-2, 7.745361e-2, 8.319092e-2, 8.993530e-2,
            9.759521e-2, 1.056213e-1, 1.138916e-1, 1.236267e-1, 1.348267e-1, 1.470337e-1, 1.603394e-1, 1.755676e-1,
            1.905823e-1, 2.071228e-1, 2.245178e-1, 2.444153e-1, 2.658997e-1, 2.897644e-1, 3.146057e-1, 3.450012e-1,
            3.766174e-1, 4.122620e-1, 4.505615e-1, 4.893799e-1, 5.305481e-1, 5.731201e-1, 6.157837e-1, 6.580811e-1,
            6.985168e-1, 7.435303e-1, 7.865906e-1, 8.302612e-1, 8.718567e-1, 9.125671e-1, 9.575806e-1, 9.996643e-1
    };

    private static final double[] BEX_MODE3_INITIAL = {
            3.491211e-1, 5.371094e-1, 6.782227e-1, 7.910156e-1, 9.057617e-1, 1.024902e+0, 1.156250e+0, 1.290527e+0,
            1.458984e+0, 1.664551e+0, 1.929688e+0, 2.278320e+0, 2.831543e+0, 3.659180e+0, 5.257813e+0, 8.373047e+0
    };

    private static final double[] BEX_MODE3_RATE = {
            -2.913818e-1, -2.541504e-1, -1.664429e-1, -1.476440e-1, -1.342163e-1, -1.220703e-1, -1.117554e-1,
            -1.026611e-1, -9.436035e-2, -8.483887e-2, -7.476807e-2, -6.304932e-2, -4.492188e-2, -2.447510e-2,
            +1.831055e-4, +4.174805e-2
    };

    private static final double[] BEX_MODE4_MULTIPLIER = {
            3.610229e-2, 1.260681e-1, 2.227478e-1, 3.338318e-1, 4.662170e-1, 6.221313e-1, 7.989197e-1, 9.939575e-1
    };

    private final double[] scales = new double[6];

    private final double[] dctOut = new double[256];

    private final double[] dctTemp = new double[256];

    private static double[][] padded(int width, double[]... rows) {
        var out = new double[rows.length][];
        for (var i = 0; i < rows.length; i++) {
            out[i] = Arrays.copyOf(rows[i], width);
        }
        return out;
    }

    /**
     * Reconstructs the samples of a block into each channel's {@link Atrac9Channel#pcm}.
     *
     * @param block the unpacked block
     * @throws MediaException.Malformed if the band extension refers to values outside its tables
     */
    void synthesize(Atrac9Block block) {
        for (var channel : block.channels) {
            dequantize(channel);
        }
        applyIntensityStereo(block);
        for (var channel : block.channels) {
            scaleSpectrum(channel);
        }
        if (block.bandExtensionEnabled && block.hasExtensionData) {
            for (var channel : block.channels) {
                applyBandExtension(channel);
            }
        }
        for (var channel : block.channels) {
            imdct(channel, block.config.frameSamplesPower());
        }
    }

    private static void dequantize(Atrac9Channel channel) {
        var spectra = channel.spectra;
        Arrays.fill(spectra, 0);
        for (var unit = 0; unit < channel.codedQuantUnits; unit++) {
            var start = Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[unit];
            var count = Atrac9Tables.QUANT_UNIT_TO_COEFF_COUNT[unit];
            var step = Atrac9Tables.QUANTIZER_STEP_SIZE[channel.precisions[unit]];
            var fineStep = Atrac9Tables.QUANTIZER_FINE_STEP_SIZE[channel.precisionsFine[unit]];
            for (var k = start; k < start + count; k++) {
                var coarse = channel.quantizedSpectra[k] * step;
                var fine = channel.quantizedSpectraFine[k] * fineStep;
                spectra[k] = coarse + fine;
            }
        }
    }

    private static void applyIntensityStereo(Atrac9Block block) {
        if (block.type != Atrac9Tables.STEREO || block.stereoQuantizationUnit >= block.quantizationUnitCount) {
            return;
        }
        var source = block.channels[block.primaryChannelIndex == 0 ? 0 : 1].spectra;
        var destination = block.channels[block.primaryChannelIndex == 0 ? 1 : 0].spectra;
        for (var unit = block.stereoQuantizationUnit; unit < block.quantizationUnitCount; unit++) {
            var invert = block.jointStereoSigns[unit] > 0;
            for (var k = Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[unit]; k < Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[unit + 1];
                 k++) {
                destination[k] = invert ? -source[k] : source[k];
            }
        }
    }

    private static void scaleSpectrum(Atrac9Channel channel) {
        var spectra = channel.spectra;
        for (var unit = 0; unit < channel.block.quantizationUnitCount; unit++) {
            var scale = Atrac9Tables.SPECTRUM_SCALE[channel.scaleFactors[unit]];
            for (var k = Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[unit]; k < Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[unit + 1];
                 k++) {
                spectra[k] *= scale;
            }
        }
    }

    /**
     * Fills the bands above the coded ones from mirrored copies of the bands below, then shapes them as the
     * channel's band extension mode codes ({@code ApplyBandExtensionChannel}).
     */
    private void applyBandExtension(Atrac9Channel channel) {
        var block = channel.block;
        var groupAUnit = block.quantizationUnitCount;
        var sf = channel.scaleFactors;
        var spectra = channel.spectra;
        var values = channel.bexValues;
        var info = Atrac9Unpacker.BEX_GROUP_INFO[groupAUnit - 13];
        var groupBUnit = info[0];
        var groupCUnit = info[1];
        var bandCount = info[2];
        var totalUnits = Math.max(groupCUnit, 22);
        var bexQuantUnits = totalUnits - groupAUnit;
        var groupABin = Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[groupAUnit];
        var groupBBin = Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[groupBUnit];
        var groupCBin = Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[groupCUnit];
        var totalBins = Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[totalUnits];

        for (var i = 0; i < groupBBin - groupABin; i++) {
            spectra[groupABin + i] = spectra[groupABin - i - 1];
        }
        for (var i = 0; i < groupCBin - groupBBin; i++) {
            spectra[groupBBin + i] = spectra[groupBBin - i - 1];
        }
        for (var i = 0; i < totalBins - groupCBin; i++) {
            spectra[groupCBin + i] = spectra[groupCBin - i - 1];
        }

        switch (channel.bexMode) {
            case 0 -> {
                switch (bandCount) {
                    case 3 -> {
                        scales[0] = entry(BEX_MODE0_BANDS3[0], values[0]);
                        scales[1] = entry(BEX_MODE0_BANDS3[1], values[0]);
                        scales[2] = entry(BEX_MODE0_BANDS3[2], values[1]);
                        scales[3] = entry(BEX_MODE0_BANDS3[3], values[2]);
                        scales[4] = entry(BEX_MODE0_BANDS3[4], values[3]);
                    }
                    case 4 -> {
                        scales[0] = entry(BEX_MODE0_BANDS4[0], values[0]);
                        scales[1] = entry(BEX_MODE0_BANDS4[1], values[0]);
                        scales[2] = entry(BEX_MODE0_BANDS4[2], values[1]);
                        scales[3] = entry(BEX_MODE0_BANDS4[3], values[2]);
                        scales[4] = entry(BEX_MODE0_BANDS4[4], values[3]);
                    }
                    default -> {
                        scales[0] = entry(BEX_MODE0_BANDS5[0], values[0]);
                        scales[1] = entry(BEX_MODE0_BANDS5[1], values[1]);
                        scales[2] = entry(BEX_MODE0_BANDS5[2], values[1]);
                    }
                }
                scales[bexQuantUnits - 1] = Atrac9Tables.SPECTRUM_SCALE[sf[groupAUnit]];
                addNoise(channel, Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[totalUnits - 1],
                        Atrac9Tables.QUANT_UNIT_TO_COEFF_COUNT[totalUnits - 1]);
                scaleUnits(spectra, groupAUnit, totalUnits);
            }
            case 1 -> {
                for (var i = groupAUnit; i < totalUnits; i++) {
                    scales[i - groupAUnit] = Atrac9Tables.SPECTRUM_SCALE[sf[i]];
                }
                addNoise(channel, groupABin, totalBins - groupABin);
                scaleUnits(spectra, groupAUnit, totalUnits);
            }
            case 2 -> {
                var groupAScale = entry(BEX_MODE2_SCALE, values[0]);
                var groupBScale = entry(BEX_MODE2_SCALE, values[1]);
                for (var i = groupABin; i < groupBBin; i++) {
                    spectra[i] *= groupAScale;
                }
                for (var i = groupBBin; i < groupCBin; i++) {
                    spectra[i] *= groupBScale;
                }
            }
            case 3 -> {
                var rate = StrictMath.pow(2, entry(BEX_MODE3_RATE, values[1]));
                var scale = entry(BEX_MODE3_INITIAL, values[0]);
                for (var i = groupABin; i < totalBins; i++) {
                    scale *= rate;
                    spectra[i] *= scale;
                }
            }
            default -> {
                var multiplier = entry(BEX_MODE4_MULTIPLIER, values[0]);
                var groupAScale = 0.7079468 * multiplier;
                var groupBScale = 0.5011902 * multiplier;
                var groupCScale = 0.3548279 * multiplier;
                for (var i = groupABin; i < groupBBin; i++) {
                    spectra[i] *= groupAScale;
                }
                for (var i = groupBBin; i < groupCBin; i++) {
                    spectra[i] *= groupBScale;
                }
                for (var i = groupCBin; i < totalBins; i++) {
                    spectra[i] *= groupCScale;
                }
            }
        }
    }

    /**
     * Returns a band extension table entry.
     *
     * @throws MediaException.Malformed if the index is outside the table, which a frame that codes no band extension
     * values leaves possible by reusing values of an earlier frame of another mode
     */
    private static double entry(double[] table, int index) {
        if (index >= table.length) {
            throw Atrac9Unpacker.malformed("reuses band extension value " + index + " outside its table");
        }
        return table[index];
    }

    private void scaleUnits(double[] spectra, int startUnit, int totalUnits) {
        for (var unit = startUnit; unit < totalUnits; unit++) {
            var scale = scales[unit - startUnit];
            for (var k = Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[unit]; k < Atrac9Tables.QUANT_UNIT_TO_COEFF_INDEX[unit + 1];
                 k++) {
                spectra[k] *= scale;
            }
        }
    }

    /**
     * Replaces coefficients with uniform noise in [-1, 1], seeding the channel's generator from its scale factors
     * at its first use ({@code AddNoiseToSpectrum}).
     */
    private static void addNoise(Atrac9Channel channel, int index, int count) {
        if (!channel.rngInitialized) {
            var sf = channel.scaleFactors;
            channel.seedRng((543 * (sf[8] + sf[12] + sf[15] + 1)) & 0xFFFF);
        }
        for (var i = 0; i < count; i++) {
            channel.spectra[i + index] = channel.nextRng() / 65535.0 * 2.0 - 1.0;
        }
    }

    /**
     * Runs the inverse MDCT of a channel's spectrum and overlaps it with the previous frame ({@code RunImdct}).
     */
    void imdct(Atrac9Channel channel, int bits) {
        var size = 1 << bits;
        var half = size / 2;
        var window = Atrac9Tables.IMDCT_WINDOWS[bits - 6];
        var previous = channel.imdctPrevious;
        var output = channel.pcm;
        dct4(channel.spectra, bits);
        for (var i = 0; i < half; i++) {
            output[i] = window[i] * dctOut[i + half] + previous[i];
            output[i + half] = window[i + half] * -dctOut[size - 1 - i] - previous[i + half];
            previous[i] = window[size - 1 - i] * -dctOut[half - i - 1];
            previous[i + half] = window[half - i - 1] * dctOut[i];
        }
    }

    /**
     * Computes a DCT-IV into {@link #dctOut} ({@code Dct4}).
     */
    private void dct4(double[] input, int bits) {
        var size = 1 << bits;
        var last = size - 1;
        var halfSize = size / 2;
        var temp = dctTemp;
        var sinTable = Atrac9Tables.SIN_TABLES[bits];
        var cosTable = Atrac9Tables.COS_TABLES[bits];
        for (var i = 0; i < halfSize; i++) {
            var i2 = i * 2;
            var a = input[i2];
            var b = input[last - i2];
            var sin = sinTable[i];
            var cos = cosTable[i];
            temp[i2] = a * cos + b * sin;
            temp[i2 + 1] = a * sin - b * cos;
        }
        var stageCount = bits - 1;
        for (var stage = 0; stage < stageCount; stage++) {
            var blockCount = 1 << stage;
            var blockSizeBits = stageCount - stage;
            var blockHalfSizeBits = blockSizeBits - 1;
            var blockSize = 1 << blockSizeBits;
            var blockHalfSize = 1 << blockHalfSizeBits;
            sinTable = Atrac9Tables.SIN_TABLES[blockHalfSizeBits];
            cosTable = Atrac9Tables.COS_TABLES[blockHalfSizeBits];
            for (var block = 0; block < blockCount; block++) {
                for (var i = 0; i < blockHalfSize; i++) {
                    var front = (block * blockSize + i) * 2;
                    var back = front + blockSize;
                    var a = temp[front] - temp[back];
                    var b = temp[front + 1] - temp[back + 1];
                    var sin = sinTable[i];
                    var cos = cosTable[i];
                    temp[front] += temp[back];
                    temp[front + 1] += temp[back + 1];
                    temp[back] = a * cos + b * sin;
                    temp[back + 1] = a * sin - b * cos;
                }
            }
        }
        var shuffle = Atrac9Tables.SHUFFLE_TABLES[bits];
        for (var i = 0; i < size; i++) {
            dctOut[i] = temp[shuffle[i]];
        }
    }
}

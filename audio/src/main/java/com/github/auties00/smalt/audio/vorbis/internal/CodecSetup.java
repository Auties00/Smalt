package com.github.auties00.smalt.audio.vorbis.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the complete configuration of a Vorbis encoder: the stream parameters written into the
 * headers (block sizes, codebooks, floors, residues, mappings, modes) and the psychoacoustic
 * parameters the analysis uses.
 *
 * <p>{@link #variableBitrate(int, int, float)} builds it from libvorbis's templates for a quality
 * between -0.1 and 1.0, {@link #nominalBitrate(int, int, int)} for the quality approximating a
 * bitrate, and {@link #managedQuality} and {@link #managedBitrate} for the bitrate managed modes,
 * which also carry the {@link Management} parameters of the bit reservoir.
 *
 * @implNote This implementation ports libvorbis 1.3.7's {@code vorbisenc.c} (BSD-3): template
 * selection, the interpolation of the psychoacoustic settings between quality steps, the floor,
 * residue and mapping setup with the lowpass and point stereo limits, and the managed setup (the
 * per-blob point stereo and lowpass limits and the managed residue books).
 */
public final class CodecSetup {
    /**
     * The number of channels.
     */
    public final int channels;

    /**
     * The sampling rate.
     */
    public final int rate;

    /**
     * The short (0) and long (1) block sizes.
     */
    public final int[] blockSizes = new int[2];

    /**
     * The codebooks in setup header order.
     */
    public final List<StaticCodebook> books = new ArrayList<>();

    /**
     * The floors.
     */
    public final List<Floor1Info> floors = new ArrayList<>();

    /**
     * The residues, indexed by residue number.
     */
    public final Residue0Info[] residues = new Residue0Info[64];

    /**
     * The type of each residue.
     */
    public final int[] residueTypes = new int[64];

    /**
     * The number of residues.
     */
    public int residueCount;

    /**
     * The mappings, one per mode.
     */
    public final Mapping0Info[] maps = new Mapping0Info[2];

    /**
     * The number of modes and mappings.
     */
    public int modes;

    /**
     * The psychoacoustic parameters of impulse, padding, transition and long blocks.
     */
    public final PsyInfo[] psy = new PsyInfo[4];

    /**
     * The number of psychoacoustic parameter sets.
     */
    public int psys;

    /**
     * The stream-wide psychoacoustic parameters.
     */
    public PsyGlobal psyGlobal;

    /**
     * The nominal bitrate written into the identification header, or {@code -1}.
     */
    public int bitrateNominal;

    /**
     * The maximum bitrate written into the identification header, {@code 0} when unset.
     */
    public int bitrateUpper;

    /**
     * The minimum bitrate written into the identification header, {@code 0} when unset.
     */
    public int bitrateLower;

    /**
     * The bit reservoir parameters ({@code bitrate_manager_info}), or {@code null} for an unmanaged
     * stream, which codes only the middle packet blob.
     */
    public Management management;

    /**
     * The parameters of the bit reservoir of a managed stream ({@code bitrate_manager_info}).
     *
     * @param averageRate the average bitrate the reservoir steers to, in bits per second, {@code 0}
     * for none
     * @param minimumRate the hard minimum bitrate in bits per second, {@code 0} for none
     * @param maximumRate the hard maximum bitrate in bits per second, {@code 0} for none
     * @param reservoirBits the reservoir size in bits, positive
     * @param reservoirBias the preferred fill of the reservoir, from 0 to 1
     * @param slewDamp the seconds the average tracker takes to sweep the whole blob range, positive
     */
    public record Management(long averageRate, long minimumRate, long maximumRate, long reservoirBits,
                             double reservoirBias, double slewDamp) {
    }

    /**
     * The template the setup comes from.
     */
    private VorbisSetupData.Template template;

    /**
     * The template setting: the quality step with its fraction.
     */
    private double baseSetting;

    /**
     * The lowpass in kilohertz.
     */
    private double lowpassKhz;

    /**
     * The floating threshold in quiet, in decibels.
     */
    private double athFloatingDb;

    /**
     * The absolute threshold in quiet, in decibels.
     */
    private double athAbsoluteDb;

    /**
     * The amplitude tracker decay, in decibels per second.
     */
    private double amplitudeTrackDbPerSecond;

    /**
     * Creates an empty setup.
     *
     * @param channels the number of channels
     * @param rate     the sampling rate
     */
    private CodecSetup(int channels, int rate) {
        this.channels = channels;
        this.rate = rate;
    }

    /**
     * Builds the setup of a quality-based variable bitrate stream.
     *
     * @param channels the number of channels, 1 to 255
     * @param rate     the sampling rate in hertz
     * @param quality  the quality, from -0.1 to 1.0 (libvorbis's scale; {@code -q 4} is 0.4)
     * @return the setup
     * @throws IllegalArgumentException if no template covers the rate, channels and quality
     */
    public static CodecSetup variableBitrate(int channels, int rate, float quality) {
        var setup = new CodecSetup(channels, rate);
        quality += .0000001f;
        if (quality >= 1.f) {
            quality = .9999f;
        }
        setup.selectTemplate(quality, false);
        setup.applySetting();
        setup.initialize();
        return setup;
    }

    /**
     * Builds the setup of a variable bitrate stream whose quality is chosen to approximate a
     * nominal bitrate ({@code vorbis_encode_setup_managed} followed by turning bitrate management
     * off, as {@code oggenc -b} does without {@code --managed}).
     *
     * @param channels the number of channels, 1 to 255
     * @param rate     the sampling rate in hertz
     * @param bitrate  the nominal bitrate in bits per second, positive
     * @return the setup
     * @throws IllegalArgumentException if no template covers the rate, channels and bitrate
     */
    public static CodecSetup nominalBitrate(int channels, int rate, int bitrate) {
        var setup = new CodecSetup(channels, rate);
        setup.selectTemplate(bitrate, true);
        setup.applySetting();
        setup.initialize();
        return setup;
    }

    /**
     * Builds the setup of a bitrate managed stream at a quality, with hard limits and no average
     * ({@code vorbis_encode_setup_vbr} followed by {@code OV_ECTL_RATEMANAGE2_SET}).
     *
     * @param channels the number of channels, 1 to 255
     * @param rate the sampling rate in hertz
     * @param quality the quality, from -0.1 to 1.0
     * @param minimum the hard minimum bitrate in bits per second, {@code 0} for none
     * @param maximum the hard maximum bitrate in bits per second, {@code 0} for none
     * @param reservoirBits the reservoir size in bits, or {@code 0} for twice the approximate bitrate
     * of the setting (twice the maximum, or the minimum, when the setting has none)
     * @param reservoirBias the preferred fill of the reservoir, from 0 to 1
     * @param damping the average damping in seconds, unused without an average
     * @return the setup
     * @throws IllegalArgumentException if no template covers the rate, channels and quality
     */
    public static CodecSetup managedQuality(int channels, int rate, float quality, int minimum, int maximum,
                                            long reservoirBits, double reservoirBias, double damping) {
        var setup = new CodecSetup(channels, rate);
        quality += .0000001f;
        if (quality >= 1.f) {
            quality = .9999f;
        }
        setup.selectTemplate(quality, false);
        setup.applySetting();
        if (reservoirBits <= 0) {
            var approximate = setup.approximateBitrate();
            reservoirBits = approximate > 0 ? approximate * 2L : 2L * (maximum > 0 ? maximum : minimum);
        }
        setup.management = new Management(0, minimum, maximum, reservoirBits, reservoirBias, damping);
        setup.initialize();
        return setup;
    }

    /**
     * Builds the setup of a bitrate managed stream whose settings are chosen from a nominal bitrate
     * ({@code vorbis_encode_setup_managed}, with {@code OV_ECTL_RATEMANAGE2_SET} for the reservoir
     * parameters and to turn the average off).
     *
     * @param channels the number of channels, 1 to 255
     * @param rate the sampling rate in hertz
     * @param nominal the bitrate the settings are chosen from, in bits per second, positive
     * @param average the average bitrate the reservoir steers to, in bits per second, {@code 0} for
     * none
     * @param minimum the hard minimum bitrate in bits per second, {@code 0} for none
     * @param maximum the hard maximum bitrate in bits per second, {@code 0} for none
     * @param reservoirBits the reservoir size in bits, or {@code 0} for twice {@code nominal}
     * @param reservoirBias the preferred fill of the reservoir, from 0 to 1
     * @param damping the average damping in seconds, positive
     * @return the setup
     * @throws IllegalArgumentException if no template covers the rate, channels and bitrate
     */
    public static CodecSetup managedBitrate(int channels, int rate, int nominal, int average, int minimum,
                                            int maximum, long reservoirBits, double reservoirBias, double damping) {
        var setup = new CodecSetup(channels, rate);
        setup.selectTemplate(nominal, true);
        setup.applySetting();
        setup.management = new Management(average, minimum, maximum,
                reservoirBits > 0 ? reservoirBits : nominal * 2L, reservoirBias, damping);
        setup.initialize();
        return setup;
    }

    /**
     * Returns whether a template serves a stream at a quality.
     *
     * @param channels the number of channels
     * @param rate     the sampling rate in hertz
     * @param quality  the quality, from -0.1 to 1.0
     * @return whether {@link #variableBitrate(int, int, float)} accepts the arguments
     */
    public static boolean supportsQuality(int channels, int rate, float quality) {
        try {
            var setup = new CodecSetup(channels, rate);
            quality += .0000001f;
            if (quality >= 1.f) {
                quality = .9999f;
            }
            setup.selectTemplate(quality, false);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Clamps a nominal bitrate into the range the first template serving a stream reaches, so
     * {@link #nominalBitrate(int, int, int)} accepts the result.
     *
     * @param channels the number of channels
     * @param rate     the sampling rate in hertz
     * @param bitrate  the requested bitrate in bits per second
     * @return the clamped bitrate, or {@code -1} when no template with approximate bitrates serves
     *         the stream
     */
    public static int clampBitrate(int channels, int rate, int bitrate) {
        for (var candidate : VorbisSetupData.get().templates) {
            if (candidate.couplingRestriction != -1 && candidate.couplingRestriction != channels) {
                continue;
            }
            if (rate < candidate.sampleRateMin || rate > candidate.sampleRateMax || candidate.rateMapping == null
                    || candidate.rateMapping[0] < 0) {
                continue;
            }
            var low = (int) Math.ceil(candidate.rateMapping[0] * channels);
            var high = (int) Math.floor(candidate.rateMapping[candidate.mappings] * channels);
            return Math.clamp(bitrate, low, high);
        }
        return -1;
    }

    /**
     * Returns whether a template serves a stream at a nominal bitrate.
     *
     * @param channels the number of channels
     * @param rate     the sampling rate in hertz
     * @param bitrate  the nominal bitrate in bits per second
     * @return whether {@link #nominalBitrate(int, int, int)} accepts the arguments
     */
    public static boolean supportsBitrate(int channels, int rate, int bitrate) {
        try {
            new CodecSetup(channels, rate).selectTemplate(bitrate, true);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Finds the template serving the stream and the setting within it ({@code get_setup_template}).
     *
     * @param request   the requested quality, or bitrate in bits per second
     * @param byBitrate whether {@code request} is a bitrate, matched per channel against the
     *                  templates' approximate bitrates
     * @throws IllegalArgumentException if no template serves the stream
     */
    private void selectTemplate(double request, boolean byBitrate) {
        var quality = byBitrate ? request / channels : request;
        for (var candidate : VorbisSetupData.get().templates) {
            if (candidate.couplingRestriction != -1 && candidate.couplingRestriction != channels) {
                continue;
            }
            if (rate < candidate.sampleRateMin || rate > candidate.sampleRateMax) {
                continue;
            }
            var mappings = candidate.mappings;
            var map = byBitrate ? candidate.rateMapping : candidate.qualityMapping;
            if (map == null) {
                continue;
            }
            if (quality < map[0] || quality > map[mappings]) {
                continue;
            }
            var j = 0;
            for (; j < mappings; j++) {
                if (quality >= map[j] && quality < map[j + 1]) {
                    break;
                }
            }
            if (j == mappings) {
                baseSetting = j - .001;
            } else {
                var low = (float) map[j];
                var high = (float) map[j + 1];
                var delta = (float) ((quality - low) / (high - low));
                baseSetting = j + delta;
            }
            template = candidate;
            return;
        }
        throw new IllegalArgumentException("Vorbis has no setup for " + channels + " channels at " + rate + " Hz");
    }

    /**
     * Derives the high-level settings from the setting ({@code vorbis_encode_setup_setting}).
     */
    private void applySetting() {
        var is = (int) baseSetting;
        var ds = baseSetting - is;
        var t = template;
        lowpassKhz = t.lowpass[is] * (1. - ds) + t.lowpass[is + 1] * ds;
        athFloatingDb = t.athFloat[is] * (1. - ds) + t.athFloat[is + 1] * ds;
        athAbsoluteDb = t.athAbsolute[is] * (1. - ds) + t.athAbsolute[is + 1] * ds;
        amplitudeTrackDbPerSecond = -6.;
    }

    /**
     * Builds every part of the setup ({@code vorbis_encode_setup_init}).
     */
    private void initialize() {
        athFloatingDb = Math.clamp(athFloatingDb, -200, -80);
        amplitudeTrackDbPerSecond = Math.clamp(amplitudeTrackDbPerSecond, -99999., 0.);
        var t = template;
        var s = baseSetting;
        var is = (int) s;
        blockSizes[0] = t.blockSizeShort[is];
        blockSizes[1] = t.blockSizeLong[is];
        var singleBlock = blockSizes[0] == blockSizes[1];
        for (var list : t.floorMappingList) {
            floorSetup(list[is]);
        }
        globalPsychSetup(s);
        globalStereo(s);
        psySetup(s, t.normalStart[0], t.normalPartition[0], 0);
        psySetup(s, t.normalStart[0], t.normalPartition[0], 1);
        if (!singleBlock) {
            psySetup(s, t.normalStart[1], t.normalPartition[1], 2);
            psySetup(s, t.normalStart[1], t.normalPartition[1], 3);
        }
        toneMaskSetup(s, 0, t.toneAdjust[0]);
        toneMaskSetup(s, 1, t.toneAdjust[2]);
        if (!singleBlock) {
            toneMaskSetup(s, 2, t.toneAdjust[2]);
            toneMaskSetup(s, 3, t.toneAdjust[1]);
        }
        compandSetup(s, 0, t.compandShortMapping);
        compandSetup(s, 1, t.compandShortMapping);
        if (!singleBlock) {
            compandSetup(s, 2, t.compandLongMapping);
            compandSetup(s, 3, t.compandLongMapping);
        }
        peakSetup(s, 0);
        peakSetup(s, 1);
        if (!singleBlock) {
            peakSetup(s, 2);
            peakSetup(s, 3);
        }
        noiseBiasSetup(s, 0, t.noiseBias[0]);
        noiseBiasSetup(s, 1, t.noiseBias[1]);
        if (!singleBlock) {
            noiseBiasSetup(s, 2, t.noiseBias[2]);
            noiseBiasSetup(s, 3, t.noiseBias[3]);
        }
        athSetup(0);
        athSetup(1);
        if (!singleBlock) {
            athSetup(2);
            athSetup(3);
        }
        mapAndResidueSetup(is);
        if (management != null) {
            bitrateNominal = management.averageRate() > 0 ? (int) management.averageRate() : approximateBitrate();
            bitrateUpper = (int) management.maximumRate();
            bitrateLower = (int) management.minimumRate();
        } else {
            bitrateNominal = approximateBitrate();
        }
    }

    /**
     * Returns the typical bitrate of the setting, truncated ({@code setting_to_approx_bitrate}).
     *
     * @return the bitrate in bits per second, or {@code -1} when the template has no typical
     * bitrates
     */
    private int approximateBitrate() {
        var t = template;
        if (t.rateMapping == null) {
            return -1;
        }
        var is = (int) baseSetting;
        var ds = baseSetting - is;
        return (int) ((t.rateMapping[is] * (1. - ds) + t.rateMapping[is + 1] * ds) * channels);
    }

    /**
     * Appends a floor and its books ({@code vorbis_encode_floor_setup}).
     *
     * @param index the floor index in the template
     */
    private void floorSetup(int index) {
        var f = template.floorParams[index].copy();
        var maxClass = -1;
        for (var i = 0; i < f.partitions; i++) {
            maxClass = Math.max(maxClass, f.partitionClass[i]);
        }
        var base = books.size();
        for (var i = 0; i <= maxClass; i++) {
            f.classBook[i] += base;
            for (var k = 0; k < (1 << f.classSubs[i]); k++) {
                if (f.classSubbook[i][k] >= 0) {
                    f.classSubbook[i][k] += base;
                }
            }
        }
        var data = VorbisSetupData.get();
        for (var id : template.floorBooks[index]) {
            books.add(data.books[id]);
        }
        floors.add(f);
    }

    /**
     * Sets the global psychoacoustic parameters ({@code vorbis_encode_global_psych_setup}).
     *
     * @param s the setting
     */
    private void globalPsychSetup(double s) {
        var x = template.globalMapping;
        var in = template.globalParams;
        var is = (int) s;
        var ds = s - is;
        var g = in[(int) x[is]].copy();
        ds = x[is] * (1. - ds) + x[is + 1] * ds;
        is = (int) ds;
        ds -= is;
        if (ds == 0 && is > 0) {
            is--;
            ds = 1.;
        }
        for (var i = 0; i < 4; i++) {
            g.preEchoThreshold[i] = (float) (in[is].preEchoThreshold[i] * (1. - ds) + in[is + 1].preEchoThreshold[i] * ds);
            g.postEchoThreshold[i] = (float) (in[is].postEchoThreshold[i] * (1. - ds) + in[is + 1].postEchoThreshold[i] * ds);
        }
        g.ampmaxAttenuationPerSecond = (float) amplitudeTrackDbPerSecond;
        psyGlobal = g;
    }

    /**
     * Sets the point stereo and sliding lowpass limits ({@code vorbis_encode_global_stereo}): one
     * limit per packet blob in a managed stream, the middle blob's everywhere otherwise.
     *
     * @param setting the stereo point setting
     */
    private void globalStereo(double setting) {
        var s = (float) setting;
        var is = (int) s;
        double ds = s - is;
        var g = psyGlobal;
        var p = template.stereoModes;
        var blobs = PsyGlobal.PACKET_BLOBS;
        if (p != null && management != null) {
            System.arraycopy(p[is].prePoint(), 0, g.couplingPrePointAmp, 0, blobs);
            System.arraycopy(p[is].postPoint(), 0, g.couplingPostPointAmp, 0, blobs);
            for (var i = 0; i < blobs; i++) {
                var khz = (float) (p[is].khz()[i] * (1. - ds) + p[is + 1].khz()[i] * ds);
                g.couplingPointLimit[0][i] = (int) (khz * 1000. / rate * blockSizes[0]);
                g.couplingPointLimit[1][i] = (int) (khz * 1000. / rate * blockSizes[1]);
                g.couplingKhz[i] = (int) khz;
                khz = (float) (p[is].lowpassKhz()[i] * (1. - ds) + p[is + 1].lowpassKhz()[i] * ds);
                g.slidingLowpass[0][i] = (int) (khz * 1000. / rate * blockSizes[0]);
                g.slidingLowpass[1][i] = (int) (khz * 1000. / rate * blockSizes[1]);
            }
        } else if (p != null) {
            System.arraycopy(p[is].prePoint(), 0, g.couplingPrePointAmp, 0, blobs);
            System.arraycopy(p[is].postPoint(), 0, g.couplingPostPointAmp, 0, blobs);
            var khz = (float) (p[is].khz()[blobs / 2] * (1. - ds) + p[is + 1].khz()[blobs / 2] * ds);
            for (var i = 0; i < blobs; i++) {
                g.couplingPointLimit[0][i] = (int) (khz * 1000. / rate * blockSizes[0]);
                g.couplingPointLimit[1][i] = (int) (khz * 1000. / rate * blockSizes[1]);
                g.couplingKhz[i] = (int) khz;
            }
            khz = (float) (p[is].lowpassKhz()[blobs / 2] * (1. - ds) + p[is + 1].lowpassKhz()[blobs / 2] * ds);
            for (var i = 0; i < blobs; i++) {
                g.slidingLowpass[0][i] = (int) (khz * 1000. / rate * blockSizes[0]);
                g.slidingLowpass[1][i] = (int) (khz * 1000. / rate * blockSizes[1]);
            }
        } else {
            for (var i = 0; i < blobs; i++) {
                g.slidingLowpass[0][i] = blockSizes[0];
                g.slidingLowpass[1][i] = blockSizes[1];
            }
        }
    }

    /**
     * Creates the psychoacoustic parameters of a block type with noise normalization
     * ({@code vorbis_encode_psyset_setup}).
     *
     * @param s         the setting
     * @param start     the normalization start of each step
     * @param partition the normalization partition of each step
     * @param block     the block type
     */
    private void psySetup(double s, int[] start, int[] partition, int block) {
        var is = (int) s;
        if (block >= psys) {
            psys = block + 1;
        }
        var p = VorbisSetupData.get().psyTemplate.copy();
        p.blockFlag = block >> 1;
        p.normalP = 1;
        p.normalStart = start[is];
        p.normalPartition = partition[is];
        p.normalThreshold = template.normalThreshold[is];
        psy[block] = p;
    }

    /**
     * Sets the tone masking of a block type ({@code vorbis_encode_tonemask_setup}).
     *
     * @param s     the setting
     * @param block the block type
     * @param adjust the per-band tone adjustment of each step
     */
    private void toneMaskSetup(double s, int block, int[][] adjust) {
        var is = (int) s;
        var ds = s - is;
        var p = psy[block];
        var att = template.toneMasterAttenuation;
        for (var k = 0; k < 3; k++) {
            p.toneMasterAttenuation[k] = (float) (att[is][k] * (1. - ds) + att[is + 1][k] * ds);
        }
        p.toneCenterBoost = (float) (att[is][3] * (1. - ds) + att[is + 1][3] * ds);
        p.toneDecay = (float) (att[is][4] * (1. - ds) + att[is + 1][4] * ds);
        p.maxCurveDb = (float) (template.tone0dB[is] * (1. - ds) + template.tone0dB[is + 1] * ds);
        for (var i = 0; i < PsyInfo.BANDS; i++) {
            p.toneAttenuation[i] = (float) (adjust[is][i] * (1. - ds) + adjust[is + 1][i] * ds);
        }
    }

    /**
     * Sets the noise compander of a block type ({@code vorbis_encode_compand_setup}).
     *
     * @param s     the setting
     * @param block the block type
     * @param x     the compander index of each step
     */
    private void compandSetup(double s, int block, double[] x) {
        var is = (int) s;
        var ds = s - is;
        var p = psy[block];
        var in = template.noiseCompand;
        ds = x[is] * (1. - ds) + x[is + 1] * ds;
        is = (int) ds;
        ds -= is;
        if (ds == 0 && is > 0) {
            is--;
            ds = 1.;
        }
        for (var i = 0; i < PsyInfo.COMPAND_LEVELS; i++) {
            p.noiseCompand[i] = (float) (in[is][i] * (1. - ds) + in[is + 1][i] * ds);
        }
    }

    /**
     * Sets the tone peak limit of a block type ({@code vorbis_encode_peak_setup}).
     *
     * @param s     the setting
     * @param block the block type
     */
    private void peakSetup(double s, int block) {
        var is = (int) s;
        var ds = s - is;
        psy[block].toneAbsoluteLimit = (float) (template.toneSuppress[is] * (1. - ds) + template.toneSuppress[is + 1] * ds);
    }

    /**
     * Sets the noise bias of a block type ({@code vorbis_encode_noisebias_setup}).
     *
     * @param s     the setting
     * @param block the block type
     * @param in    the noise bias of each step, curve and band
     */
    private void noiseBiasSetup(double s, int block, int[][][] in) {
        var is = (int) s;
        var ds = s - is;
        var p = psy[block];
        var suppress = template.noiseSuppress;
        var guard = template.noiseGuards;
        p.noiseMaxSuppression = (float) (suppress[is] * (1. - ds) + suppress[is + 1] * ds);
        p.noiseWindowLowMin = guard[block][0];
        p.noiseWindowHighMin = guard[block][1];
        p.noiseWindowFixed = guard[block][2];
        for (var j = 0; j < PsyInfo.NOISE_CURVES; j++) {
            for (var i = 0; i < PsyInfo.BANDS; i++) {
                p.noiseOffset[j][i] = (float) (in[is][j][i] * (1. - ds) + in[is + 1][j][i] * ds);
            }
        }
        for (var j = 0; j < PsyInfo.NOISE_CURVES; j++) {
            var min = p.noiseOffset[j][0] + 6;
            for (var i = 0; i < PsyInfo.BANDS; i++) {
                p.noiseOffset[j][i] += 0;
                if (p.noiseOffset[j][i] < min) {
                    p.noiseOffset[j][i] = min;
                }
            }
        }
    }

    /**
     * Sets the threshold in quiet of a block type ({@code vorbis_encode_ath_setup}).
     *
     * @param block the block type
     */
    private void athSetup(int block) {
        psy[block].athAdjustAttenuation = (float) athFloatingDb;
        psy[block].athMaxAttenuation = (float) athAbsoluteDb;
    }

    /**
     * Returns the index of a book, appending it when it is not used yet ({@code book_dup_or_new}).
     *
     * @param id the book id in the setup data
     * @return the index in {@link #books}
     */
    private int bookIndex(int id) {
        var book = VorbisSetupData.get().books[id];
        for (var i = 0; i < books.size(); i++) {
            if (books.get(i) == book) {
                return i;
            }
        }
        books.add(book);
        return books.size() - 1;
    }

    /**
     * Sets the mappings, modes and residues ({@code vorbis_encode_map_n_res_setup}).
     *
     * @param is the quality step
     */
    private void mapAndResidueSetup(int is) {
        var maps = template.maps[is];
        modes = blockSizes[0] == blockSizes[1] ? 1 : 2;
        for (var i = 0; i < modes; i++) {
            this.maps[i] = maps.mappings()[i].copy();
            for (var j = 0; j < this.maps[i].submaps; j++) {
                var number = this.maps[i].residueSubmap[j];
                residueSetup(number, i, maps.residues()[number]);
            }
        }
    }

    /**
     * Creates a residue from its template, with its books and coded range
     * ({@code vorbis_encode_residue_setup}).
     *
     * @param number   the residue number
     * @param block    the mode, whose floor and block size apply
     * @param residue  the template
     */
    private void residueSetup(int number, int block, VorbisSetupData.ResidueTemplate residue) {
        var r = residue.residue().copy();
        residues[number] = r;
        if (residueCount <= number) {
            residueCount = number + 1;
        }
        r.grouping = residue.grouping();
        residueTypes[number] = residue.type();
        var booklist = 0;
        var stageBooks = management != null ? residue.managedBooks() : residue.books();
        for (var i = 0; i < r.partitions; i++) {
            for (var k = 0; k < 4; k++) {
                if (stageBooks[i][k] >= 0) {
                    r.secondStages[i] |= 1 << k;
                }
            }
        }
        r.groupBook = bookIndex(management != null ? residue.managedAuxBook() : residue.auxBook());
        for (var i = 0; i < r.partitions; i++) {
            for (var k = 0; k < 4; k++) {
                if (stageBooks[i][k] >= 0) {
                    r.bookList[booklist++] = bookIndex(stageBooks[i][k]);
                }
            }
        }
        var freq = lowpassKhz * 1000.;
        var f = floors.get(block);
        double nyquist = rate / 2.;
        long blockSize = blockSizes[block] >> 1;
        if (freq > nyquist) {
            freq = nyquist;
        }
        f.n = (int) (freq / nyquist * blockSize);
        switch (residue.limitType()) {
            case 1 -> {
                freq = psyGlobal.couplingKhz[management != null ? PsyGlobal.PACKET_BLOBS - 1 : PsyGlobal.PACKET_BLOBS / 2]
                        * 1000.;
                if (freq > nyquist) {
                    freq = nyquist;
                }
            }
            case 2 -> freq = 250;
            default -> {
            }
        }
        if (residueTypes[number] == 2) {
            var ch = 0;
            for (var i = 0; i < modes && ch == 0; i++) {
                var mi = maps[i];
                if (mi == null) {
                    continue;
                }
                for (var j = 0; j < mi.submaps && ch == 0; j++) {
                    if (mi.residueSubmap[j] == number) {
                        for (var k = 0; k < channels; k++) {
                            if (mi.channelMux[k] == j) {
                                ch++;
                            }
                        }
                    }
                }
            }
            r.end = (int) ((freq / nyquist * blockSize * ch) / r.grouping + .9) * r.grouping;
            if (r.end > blockSize * ch) {
                r.end = (int) (blockSize * ch / r.grouping * r.grouping);
            }
        } else {
            r.end = (int) ((freq / nyquist * blockSize) / r.grouping + .9) * r.grouping;
            if (r.end > blockSize) {
                r.end = (int) (blockSize / r.grouping * r.grouping);
            }
        }
        if (r.end == 0) {
            r.end = r.grouping;
        }
    }
}

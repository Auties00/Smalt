/* Ports the ISO/IEC 23003-3:2019 reference software (ct_envextr.h SBR_HEADER_DATA, ct_envextr.c
   sbrParseInfoUsac, sbrParseHeaderUsac, sbrAssignDfltHeaderUsac, sbrGetHeaderDataUsac). Copyright Coding
   Technologies, Sony Corporation and ISO/IEC 2002-2008; ISO/IEC reference software licence (use in products
   conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

/**
 * Holds the SBR header and info of a USAC element ({@code SbrInfo()} and {@code SbrHeader()}, ISO/IEC 23003-3
 * subclause 5.3.4).
 *
 * <p>Instances are mutable and not thread-safe.
 */
final class EsbrHeader {
    /**
     * {@code bs_amp_res}.
     */
    int ampResolution;

    /**
     * {@code bs_xover_band}.
     */
    int crossoverBand;

    /**
     * {@code bs_sbr_preprocessing}.
     */
    boolean preFlattening;

    /**
     * {@code bs_pvc_mode}, 0 without predictive vector coding.
     */
    int pvcMode;

    /**
     * {@code bs_start_freq}.
     */
    int startFrequency;

    /**
     * {@code bs_stop_freq}.
     */
    int stopFrequency;

    /**
     * {@code bs_freq_scale}.
     */
    int frequencyScale;

    /**
     * {@code bs_alter_scale}.
     */
    int alterScale;

    /**
     * {@code bs_noise_bands}.
     */
    int noiseBands;

    /**
     * {@code bs_limiter_bands}.
     */
    int limiterBands;

    /**
     * {@code bs_limiter_gains}.
     */
    int limiterGains;

    /**
     * {@code bs_interpol_freq}.
     */
    int interpolation;

    /**
     * {@code bs_smoothing_mode}.
     */
    int smoothing;

    /**
     * Copies every field of another header.
     *
     * @param other the source
     */
    void copyFrom(EsbrHeader other) {
        ampResolution = other.ampResolution;
        crossoverBand = other.crossoverBand;
        preFlattening = other.preFlattening;
        pvcMode = other.pvcMode;
        startFrequency = other.startFrequency;
        stopFrequency = other.stopFrequency;
        frequencyScale = other.frequencyScale;
        alterScale = other.alterScale;
        noiseBands = other.noiseBands;
        limiterBands = other.limiterBands;
        limiterGains = other.limiterGains;
        interpolation = other.interpolation;
        smoothing = other.smoothing;
    }

    /**
     * Copies the default header of the configuration.
     *
     * @param header the default header
     */
    void assignDefault(UsacConfig.SbrHeader header) {
        startFrequency = header.startFrequency();
        stopFrequency = header.stopFrequency();
        frequencyScale = header.frequencyScale();
        alterScale = header.alterScale();
        noiseBands = header.noiseBands();
        limiterBands = header.limiterBands();
        limiterGains = header.limiterGains();
        interpolation = header.interpolation();
        smoothing = header.smoothing();
    }

    /**
     * Reads the {@code SbrInfo()} and {@code SbrHeader()} parts of {@code UsacSbrData()}.
     *
     * @param reader the bit source
     * @param defaults the default header of the configuration
     * @param independent the frame's {@code usacIndependencyFlag}
     * @param pvc whether the element enables predictive vector coding
     * @param active whether a header was read before
     * @return whether the band tables must be rebuilt
     */
    boolean read(AacBitReader reader, UsacConfig.SbrHeader defaults, boolean independent, boolean pvc,
                 boolean active) {
        var lastStart = startFrequency;
        var lastStop = stopFrequency;
        var lastCrossover = crossoverBand;
        var lastScale = frequencyScale;
        var lastAlter = alterScale;
        var lastNoise = noiseBands;
        boolean infoPresent;
        boolean headerPresent;
        if (independent) {
            infoPresent = true;
            headerPresent = true;
        } else {
            infoPresent = reader.readFlag();
            headerPresent = infoPresent && reader.readFlag();
        }
        if (infoPresent) {
            ampResolution = reader.read(1);
            crossoverBand = reader.read(4);
            preFlattening = reader.readFlag();
            pvcMode = pvc ? reader.read(2) : 0;
        }
        if (headerPresent) {
            if (reader.readFlag()) {
                assignDefault(defaults);
            } else {
                startFrequency = reader.read(4);
                stopFrequency = reader.read(4);
                var extra1 = reader.readFlag();
                var extra2 = reader.readFlag();
                if (extra1) {
                    frequencyScale = reader.read(2);
                    alterScale = reader.read(1);
                    noiseBands = reader.read(2);
                } else {
                    frequencyScale = 2;
                    alterScale = 1;
                    noiseBands = 2;
                }
                if (extra2) {
                    limiterBands = reader.read(2);
                    limiterGains = reader.read(2);
                    interpolation = reader.read(1);
                    smoothing = reader.read(1);
                } else {
                    limiterBands = 2;
                    limiterGains = 2;
                    interpolation = 1;
                    smoothing = 1;
                }
            }
        }
        if (!active) {
            return true;
        }
        return lastStart != startFrequency || lastStop != stopFrequency || lastCrossover != crossoverBand
                || lastScale != frequencyScale || lastAlter != alterScale || lastNoise != noiseBands;
    }
}

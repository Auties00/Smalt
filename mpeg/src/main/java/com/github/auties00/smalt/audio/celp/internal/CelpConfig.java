package com.github.auties00.smalt.audio.celp.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.ep.ErrorProtectionConfig;

/**
 * The configuration of one elementary stream of a CELP audio object: the {@code AudioSpecificConfig} of object type
 * 8 (CELP) or 24 (ER CELP) with its {@code CelpSpecificConfig} or {@code ErrorResilientCelpSpecificConfig}
 * (ISO/IEC 14496-3 Tables 3.10 to 3.12, 3.23 and 3.24).
 *
 * @param objectType the audio object type, 8 or 24
 * @param sampleRate the sampling frequency of the stream
 * @param layer the kind of layer: {@link #BASE}, {@link #BITRATE} or {@link #BANDWIDTH}
 * @param enhancementId {@code CELP-BRS-id} of a bitrate scalable layer, from 1 to 3; 0 for the other layers
 * @param rpe {@code ExcitationMode}: whether the base layer uses the regular pulse excitation tool
 * @param wideband {@code SampleRateMode}: whether the base layer codes 16 kHz speech
 * @param fineRateControl {@code FineRateControl}
 * @param silenceCompression {@code SilenceCompression}, always {@code false} for object type 8
 * @param configuration {@code RPE_Configuration} or {@code MPE_Configuration} of the base layer
 * @param enhancementLayers {@code NumEnhLayers}, the number of bitrate scalable layers
 * @param bandwidthScalable {@code BandwidthScalabilityMode}
 * @param bandwidthConfiguration {@code BWS_configuration} of a bandwidth scalable layer
 * @param epConfig {@code epConfig} of object type 24, 0 for object type 8
 * @param errorProtection the {@code ErrorProtectionSpecificConfig} of {@code epConfig} 2 and 3, or {@code null}
 */
public record CelpConfig(int objectType, int sampleRate, int layer, int enhancementId, boolean rpe, boolean wideband,
                         boolean fineRateControl, boolean silenceCompression, int configuration, int enhancementLayers,
                         boolean bandwidthScalable, int bandwidthConfiguration, int epConfig,
                         ErrorProtectionConfig errorProtection) {
    /**
     * The base layer.
     */
    public static final int BASE = 0;

    /**
     * A bitrate scalable enhancement layer.
     */
    public static final int BITRATE = 1;

    /**
     * The bandwidth scalable enhancement layer.
     */
    public static final int BANDWIDTH = 2;

    /**
     * The sampling frequencies of {@code samplingFrequencyIndex} (ISO/IEC 14496-3 Table 1.18).
     */
    private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025,
            8000, 7350};

    /**
     * Parses an {@code AudioSpecificConfig} of object type 8 or 24.
     *
     * @param data the configuration
     * @return the configuration
     * @throws MediaException.Malformed if the configuration is truncated or declares reserved values
     * @throws MediaException.Unsupported if the object type is neither 8 nor 24, or the error protection classes are
     * not mapped directly to category instances
     */
    public static CelpConfig parse(byte[] data) {
        if (data.length == 0) {
            throw new MediaException.Malformed("empty AudioSpecificConfig");
        }
        var reader = new AacBitReader(data);
        var objectType = reader.read(5);
        if (objectType == 31) {
            objectType = 32 + reader.read(6);
        }
        if (objectType != 8 && objectType != 24) {
            throw new MediaException.Unsupported("celp object type", "audio object type " + objectType
                    + " is not CELP");
        }
        var index = reader.read(4);
        int sampleRate;
        if (index == 15) {
            sampleRate = reader.read(24);
        } else if (index < RATES.length) {
            sampleRate = RATES[index];
        } else {
            throw new MediaException.Malformed("reserved samplingFrequencyIndex " + index);
        }
        if (sampleRate == 0) {
            throw new MediaException.Malformed("CELP configuration with a zero sampling frequency");
        }
        var channels = reader.read(4);
        if (channels != 1 && channels != 0) {
            throw new MediaException.Malformed("CELP configuration with channel configuration " + channels);
        }
        var errorResilient = objectType == 24;
        var layer = BASE;
        var enhancementId = 0;
        var rpe = false;
        var wideband = false;
        var fineRateControl = false;
        var silenceCompression = false;
        var configuration = 0;
        var enhancementLayers = 0;
        var bandwidthScalable = false;
        var bandwidthConfiguration = 0;
        if (reader.readFlag()) {
            rpe = reader.readFlag();
            wideband = reader.readFlag();
            fineRateControl = reader.readFlag();
            if (errorResilient) {
                silenceCompression = reader.readFlag();
            }
            if (rpe) {
                configuration = reader.read(3);
                if (configuration > 3) {
                    throw new MediaException.Malformed("reserved RPE_Configuration " + configuration);
                }
                if (!wideband) {
                    throw new MediaException.Malformed("regular pulse excitation at 8 kHz");
                }
            } else {
                configuration = reader.read(5);
                enhancementLayers = reader.read(2);
                bandwidthScalable = reader.readFlag();
                if (wideband ? configuration == 7 || configuration == 23 : configuration > 27) {
                    throw new MediaException.Malformed("reserved MPE_Configuration " + configuration);
                }
                if (bandwidthScalable && (wideband || configuration == 27)) {
                    throw new MediaException.Malformed("bandwidth scalability with MPE_Configuration "
                            + configuration + (wideband ? " at 16 kHz" : ""));
                }
                if (enhancementLayers > 0 && !wideband && configuration == 27) {
                    throw new MediaException.Malformed("bitrate scalability with MPE_Configuration 27");
                }
            }
        } else if (reader.readFlag()) {
            layer = BANDWIDTH;
            bandwidthConfiguration = reader.read(2);
        } else {
            layer = BITRATE;
            enhancementId = reader.read(2);
            if (enhancementId == 0) {
                throw new MediaException.Malformed("CELP-BRS-id 0");
            }
        }
        var epConfig = 0;
        ErrorProtectionConfig errorProtection = null;
        if (errorResilient) {
            epConfig = reader.read(2);
            if (epConfig >= 2) {
                errorProtection = ErrorProtectionConfig.read(reader);
            }
            if (epConfig == 3 && !reader.readFlag()) {
                throw new MediaException.Unsupported("celp epconfig 3 mapping",
                        "error protection classes without direct mapping (epConfig 3, directMapping 0) are not defined");
            }
        }
        reader.checkNotOverrun();
        return new CelpConfig(objectType, sampleRate, layer, enhancementId, rpe, wideband, fineRateControl,
                silenceCompression, configuration, enhancementLayers, bandwidthScalable, bandwidthConfiguration,
                epConfig, errorProtection);
    }

    /**
     * {@return whether the stream uses the error resilient syntax (object type 24)}
     */
    public boolean errorResilient() {
        return objectType == 24;
    }
}

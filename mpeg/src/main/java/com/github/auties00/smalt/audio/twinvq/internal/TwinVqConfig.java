package com.github.auties00.smalt.audio.twinvq.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.ep.ErrorProtectionConfig;

/**
 * The configuration of one elementary stream of a TwinVQ audio object: the {@code AudioSpecificConfig} of object type
 * 7 (TwinVQ) or 21 (ER TwinVQ) with its {@code GASpecificConfig} (ISO/IEC 14496-3 Tables 1.15 and 4.1).
 *
 * @param objectType the audio object type, 7 or 21
 * @param sampleRate the sampling frequency of the stream
 * @param channels the number of channels, 1 or 2
 * @param frameLength the number of samples of a frame, 1024 or 960
 * @param epConfig {@code epConfig} of object type 21, 0 for object type 7
 * @param errorProtection the {@code ErrorProtectionSpecificConfig} of {@code epConfig} 2 and 3, or {@code null}
 */
public record TwinVqConfig(int objectType, int sampleRate, int channels, int frameLength, int epConfig,
                           ErrorProtectionConfig errorProtection) {
    /**
     * The audio object type of TwinVQ.
     */
    public static final int AOT_TWINVQ = 7;

    /**
     * The audio object type of ER TwinVQ.
     */
    public static final int AOT_ER_TWINVQ = 21;

    /**
     * The sampling frequencies of {@code samplingFrequencyIndex} (ISO/IEC 14496-3 Table 1.18).
     */
    private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025,
            8000, 7350};

    /**
     * The lowest sampling frequency the band tables divide by, whose rate in kilohertz is not 0.
     */
    private static final int MIN_RATE = 1000;

    /**
     * Parses an {@code AudioSpecificConfig} of object type 7 or 21.
     *
     * @param data the configuration
     * @return the configuration
     * @throws MediaException.Malformed if the configuration is truncated or declares reserved values
     * @throws MediaException.Unsupported if the object type is neither 7 nor 21, the channels are not those of the
     * standard's one and two channel configurations, or the error protection classes are not mapped directly to
     * category instances
     */
    public static TwinVqConfig parse(byte[] data) {
        if (data.length == 0) {
            throw new MediaException.Malformed("empty AudioSpecificConfig");
        }
        var reader = new AacBitReader(data);
        var objectType = reader.read(5);
        if (objectType == 31) {
            objectType = 32 + reader.read(6);
        }
        if (objectType != AOT_TWINVQ && objectType != AOT_ER_TWINVQ) {
            throw new MediaException.Unsupported("twinvq object type", "audio object type " + objectType
                    + " is not TwinVQ");
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
        if (sampleRate < MIN_RATE) {
            throw new MediaException.Malformed("TwinVQ configuration with a sampling frequency of " + sampleRate
                    + " Hz");
        }
        var channels = reader.read(4);
        if (channels != 1 && channels != 2) {
            throw new MediaException.Unsupported("twinvq channels", "TwinVQ with channel configuration " + channels);
        }
        var frameLength = reader.readFlag() ? 960 : 1024;
        if (reader.readFlag()) {
            reader.read(14);
        }
        if (reader.readFlag()) {
            reader.read(1);
        }
        var epConfig = 0;
        ErrorProtectionConfig errorProtection = null;
        if (objectType == AOT_ER_TWINVQ) {
            epConfig = reader.read(2);
            if (epConfig >= 2) {
                errorProtection = ErrorProtectionConfig.read(reader);
            }
            if (epConfig == 3 && !reader.readFlag()) {
                throw new MediaException.Unsupported("twinvq epconfig 3 mapping",
                        "error protection classes without direct mapping (epConfig 3, directMapping 0) are not defined");
            }
        }
        reader.checkNotOverrun();
        return new TwinVqConfig(objectType, sampleRate, channels, frameLength, epConfig, errorProtection);
    }

    /**
     * {@return whether the stream uses the error resilient syntax (object type 21)}
     */
    public boolean errorResilient() {
        return objectType == AOT_ER_TWINVQ;
    }
}

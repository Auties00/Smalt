package com.github.auties00.smalt.audio.hvxc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.ep.ErrorProtectionConfig;

/**
 * The configuration of one elementary stream of an HVXC audio object: the {@code AudioSpecificConfig} of object type
 * 9 (HVXC) or 25 (ER HVXC) with its {@code HvxcSpecificConfig} or {@code ErrorResilientHvxcSpecificConfig}
 * (ISO/IEC 14496-3 subclauses 2.3.1 and 2.3.3, Tables 2.1 and 2.19).
 *
 * @param objectType the audio object type, 9 or 25
 * @param base {@code isBaseLayer}
 * @param variable {@code HVXCvarMode}: whether the base layer uses the variable rate syntax
 * @param rateMode {@code HVXCrateMode} of the base layer, {@link HvxcCore#RATE_2K}, {@link HvxcCore#RATE_4K} or
 * {@link HvxcCore#RATE_3K}
 * @param extension {@code extensionFlag} of the base layer
 * @param variableScalable {@code var_ScalableFlag} of the base layer, {@code false} without the extension
 * @param epConfig {@code epConfig} of object type 25, 0 for object type 9
 * @param errorProtection the {@code ErrorProtectionSpecificConfig} of {@code epConfig} 2 and 3, or {@code null}
 */
public record HvxcConfig(int objectType, boolean base, boolean variable, int rateMode, boolean extension,
                         boolean variableScalable, int epConfig, ErrorProtectionConfig errorProtection) {
    /**
     * Parses an {@code AudioSpecificConfig} of object type 9 or 25.
     *
     * @param data the configuration
     * @return the configuration
     * @throws MediaException.Malformed if the configuration is truncated, declares reserved values, a sampling
     * frequency other than 8 kHz or a channel configuration other than mono
     * @throws MediaException.Unsupported if the object type is neither 9 nor 25, or the error protection classes are
     * not mapped directly to category instances
     */
    public static HvxcConfig parse(byte[] data) {
        if (data.length == 0) {
            throw new MediaException.Malformed("empty AudioSpecificConfig");
        }
        var reader = new AacBitReader(data);
        var objectType = reader.read(5);
        if (objectType == 31) {
            objectType = 32 + reader.read(6);
        }
        if (objectType != 9 && objectType != 25) {
            throw new MediaException.Unsupported("hvxc object type", "audio object type " + objectType
                    + " is not HVXC");
        }
        var index = reader.read(4);
        var rate = index == 15 ? reader.read(24) : index == 11 ? 8000 : -index;
        if (rate != 8000) {
            throw new MediaException.Malformed("HVXC configuration with samplingFrequencyIndex " + index
                    + (index == 15 ? " (" + rate + " Hz)" : ""));
        }
        var channels = reader.read(4);
        if (channels != 1) {
            throw new MediaException.Malformed("HVXC configuration with channel configuration " + channels);
        }
        var base = reader.readFlag();
        var variable = false;
        var rateMode = HvxcCore.RATE_2K;
        var extension = false;
        var variableScalable = false;
        if (base) {
            variable = reader.readFlag();
            rateMode = reader.read(2);
            if (rateMode == 3) {
                throw new MediaException.Malformed("reserved HVXCrateMode 3");
            }
            extension = reader.readFlag();
            if (extension) {
                variableScalable = reader.readFlag();
            }
        }
        var epConfig = 0;
        ErrorProtectionConfig errorProtection = null;
        if (objectType == 25) {
            epConfig = reader.read(2);
            if (epConfig >= 2) {
                errorProtection = ErrorProtectionConfig.read(reader);
            }
            if (epConfig == 3 && !reader.readFlag()) {
                throw new MediaException.Unsupported("hvxc epconfig 3 mapping",
                        "error protection classes without direct mapping (epConfig 3, directMapping 0) are "
                                + "not defined");
            }
        }
        reader.checkNotOverrun();
        return new HvxcConfig(objectType, base, variable, rateMode, extension, variableScalable, epConfig,
                errorProtection);
    }
}

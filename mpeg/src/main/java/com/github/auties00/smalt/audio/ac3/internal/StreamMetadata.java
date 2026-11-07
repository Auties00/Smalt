package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.audio.ac3.Ac3ConverterType;
import com.github.auties00.smalt.audio.ac3.Ac3Indication;
import com.github.auties00.smalt.audio.ac3.Ac3Metadata;
import com.github.auties00.smalt.audio.ac3.Ac3MixLevel;
import com.github.auties00.smalt.audio.ac3.Ac3PreferredDownmix;
import com.github.auties00.smalt.audio.ac3.Ac3SurroundExMode;

/**
 * The bit stream codes of the metadata an AC-3 or E-AC-3 frame carries, derived from an
 * {@link Ac3Metadata}.
 *
 * @param bsmod the bit stream mode
 * @param cmixlev the AC-3 center mix level code
 * @param surmixlev the AC-3 surround mix level code
 * @param dsurmod the Dolby Surround mode code
 * @param mixlevel the mixing level code, {@code mixlevel} in dB SPL minus 80, or {@code -1} without
 * production information
 * @param roomtyp the room type code
 * @param copyrightb the copyright bit
 * @param origbs the original bit stream bit
 * @param extraMixing whether the AC-3 frames carry the extra bit stream information #1 of Annex D
 * @param extraModes whether the AC-3 frames carry the extra bit stream information #2 of Annex D
 * @param dmixmod the preferred stereo downmix code
 * @param ltrtcmixlev the Lt/Rt center mix level code
 * @param ltrtsurmixlev the Lt/Rt surround mix level code
 * @param lorocmixlev the Lo/Ro center mix level code
 * @param lorosurmixlev the Lo/Ro surround mix level code
 * @param dsurexmod the Dolby Surround EX mode code
 * @param dheadphonmod the Dolby Headphone mode code
 * @param adconvtyp the converter type code
 * @param mixing whether the E-AC-3 frames carry mixing metadata
 * @param lfemixlevcod the LFE mix level code, or {@code -1} without one
 * @param pgmscl the program scale factor code, or {@code -1} without one
 * @param extpgmscl the external program scale factor code, or {@code -1} without one
 * @param informational whether the E-AC-3 frames carry informational metadata
 * @param sourcefscod the source sample rate code
 */
public record StreamMetadata(int bsmod, int cmixlev, int surmixlev, int dsurmod, int mixlevel, int roomtyp,
                             int copyrightb, int origbs, boolean extraMixing, boolean extraModes, int dmixmod,
                             int ltrtcmixlev, int ltrtsurmixlev, int lorocmixlev, int lorosurmixlev, int dsurexmod,
                             int dheadphonmod, int adconvtyp, boolean mixing, int lfemixlevcod, int pgmscl,
                             int extpgmscl, boolean informational, int sourcefscod) {
    /**
     * The codes of {@link Ac3Metadata#defaults()}.
     */
    public static final StreamMetadata DEFAULTS = of(Ac3Metadata.defaults());

    /**
     * Returns the codes of a metadata.
     *
     * @param metadata the metadata to code
     * @return the codes
     */
    public static StreamMetadata of(Ac3Metadata metadata) {
        var defaults = Ac3Metadata.defaults();
        var center = metadata.centerMixLevel();
        var surround = metadata.surroundMixLevel();
        var levelsSet = metadata.ltrtCenterMixLevel().isPresent() || metadata.ltrtSurroundMixLevel().isPresent()
                || metadata.loroCenterMixLevel().isPresent() || metadata.loroSurroundMixLevel().isPresent();
        var extraMixing = metadata.preferredDownmix() != Ac3PreferredDownmix.NOT_INDICATED || levelsSet;
        var extraModes = metadata.surroundExMode() != Ac3SurroundExMode.NOT_INDICATED
                || metadata.headphoneMode() != Ac3Indication.NOT_INDICATED
                || metadata.converterType() != Ac3ConverterType.STANDARD;
        var mixing = extraMixing || center != defaults.centerMixLevel() || surround != defaults.surroundMixLevel()
                || metadata.lfeMixLevel().isPresent() || metadata.programScale().isPresent()
                || metadata.externalProgramScale().isPresent();
        var informational = metadata.serviceType() != defaults.serviceType() || metadata.copyright()
                || !metadata.original() || metadata.surroundMode() != Ac3Indication.NOT_INDICATED
                || metadata.headphoneMode() != Ac3Indication.NOT_INDICATED
                || metadata.surroundExMode() != Ac3SurroundExMode.NOT_INDICATED || metadata.mixingLevel().isPresent()
                || metadata.doubleRateSource();
        return new StreamMetadata(metadata.serviceType().code(),
                center == Ac3MixLevel.MINUS_3_DB ? 0 : center == Ac3MixLevel.MINUS_4_5_DB ? 1 : 2,
                surround == Ac3MixLevel.MINUS_3_DB ? 0 : surround == Ac3MixLevel.MINUS_6_DB ? 1 : 2,
                metadata.surroundMode().ordinal(),
                metadata.mixingLevel().isPresent() ? metadata.mixingLevel().getAsInt() - 80 : -1,
                metadata.roomType().ordinal(),
                metadata.copyright() ? 1 : 0, metadata.original() ? 1 : 0, extraMixing, extraModes,
                metadata.preferredDownmix().ordinal(),
                metadata.ltrtCenterMixLevel().orElse(center).ordinal(),
                metadata.ltrtSurroundMixLevel().orElse(surround).ordinal(),
                metadata.loroCenterMixLevel().orElse(center).ordinal(),
                metadata.loroSurroundMixLevel().orElse(surround).ordinal(),
                metadata.surroundExMode().ordinal(), metadata.headphoneMode().ordinal(),
                metadata.converterType().ordinal(), mixing,
                metadata.lfeMixLevel().isPresent() ? 10 - metadata.lfeMixLevel().getAsInt() : -1,
                metadata.programScale().isPresent() ? metadata.programScale().getAsInt() + 51 : -1,
                metadata.externalProgramScale().isPresent() ? metadata.externalProgramScale().getAsInt() + 51 : -1,
                informational, metadata.doubleRateSource() ? 1 : 0);
    }

    /**
     * {@return whether the AC-3 frames use the alternate bit stream syntax of Annex D}
     */
    public boolean alternateSyntax() {
        return extraMixing || extraModes;
    }
}

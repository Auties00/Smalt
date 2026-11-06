package com.github.auties00.smalt.track;

/**
 * A Dolby Vision configuration: the fields of the {@code DOVIDecoderConfigurationRecord} that an MP4
 * {@code dvcC}, {@code dvvC} or {@code dvwC} box, a Matroska {@code BlockAdditionMapping} of the same type and an
 * MPEG-TS DOVI video stream descriptor carry.
 *
 * <p>A Dolby Vision track is coded as its base codec (HEVC, H.264 or AV1): the base layer is an ordinary stream of
 * that codec, and the reference processing units (RPUs, HEVC NAL unit type 62, an H.264 NAL unit or an AV1 ITU-T
 * T.35 metadata OBU) inside its packets carry the metadata that reconstructs the Dolby Vision picture. The
 * {@linkplain #baseLayerCompatibility() compatibility identifier} tells what the base layer shows to a decoder
 * that ignores the RPUs:
 * <table class="striped">
 * <caption>Base layer compatibility identifiers</caption>
 * <thead><tr><th scope="col">Identifier</th><th scope="col">Base layer</th><th scope="col">Profiles</th></tr>
 * </thead>
 * <tbody>
 * <tr><td>0</td><td>none: the base layer is in a Dolby Vision specific color space (IPTPQc2) and shows wrong
 * colors when decoded as plain video</td><td>5, 10.0</td></tr>
 * <tr><td>1</td><td>HDR10 (BT.2020, PQ)</td><td>8.1, 10.1</td></tr>
 * <tr><td>2</td><td>SDR (BT.709)</td><td>4, 8.2, 9.2, 10.2</td></tr>
 * <tr><td>4</td><td>HLG (BT.2020, BT.2100 HLG)</td><td>8.4, 10.4</td></tr>
 * <tr><td>6</td><td>the HDR10 base layer of Ultra HD Blu-ray</td><td>7</td></tr>
 * </tbody>
 * </table>
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param versionMajor the major version of the configuration ({@code dv_version_major}), from 0 to 255; 1 for
 * every published version
 * @param versionMinor the minor version ({@code dv_version_minor}), from 0 to 255
 * @param profile the Dolby Vision profile ({@code dv_profile}), from 0 to 127, for example 5, 7, 8, 9 or 10
 * @param level the Dolby Vision level ({@code dv_level}), from 0 to 63, which bounds the picture size and rate
 * @param rpuPresent whether the stream carries RPUs ({@code rpu_present_flag})
 * @param enhancementLayerPresent whether the stream carries an enhancement layer ({@code el_present_flag})
 * @param baseLayerPresent whether the stream carries a base layer ({@code bl_present_flag}); an enhancement layer
 * stored apart from its base layer has none
 * @param baseLayerCompatibility the base layer signal compatibility identifier
 * ({@code dv_bl_signal_compatibility_id}), from 0 to 15, as the table above lists them
 * @param metadataCompression the RPU metadata compression ({@code dv_md_compression}), from 0 to 3: 0 for none, 1
 * for limited and 3 for extended compression
 */
public record DolbyVision(int versionMajor, int versionMinor, int profile, int level, boolean rpuPresent,
                          boolean enhancementLayerPresent, boolean baseLayerPresent, int baseLayerCompatibility,
                          int metadataCompression) {
    /**
     * Validates the field ranges.
     *
     * @throws IllegalArgumentException if a field is outside the range its component documents
     */
    public DolbyVision {
        check("versionMajor", versionMajor, 255);
        check("versionMinor", versionMinor, 255);
        check("profile", profile, 127);
        check("level", level, 63);
        check("baseLayerCompatibility", baseLayerCompatibility, 15);
        check("metadataCompression", metadataCompression, 3);
    }

    /**
     * Returns the configuration of a single-layer stream with RPUs: version 1.0, a base layer, no enhancement
     * layer and no metadata compression, as profiles 5, 8, 9 and 10 store it.
     *
     * @param profile the Dolby Vision profile, from 0 to 127
     * @param level the Dolby Vision level, from 0 to 63
     * @param baseLayerCompatibility the base layer signal compatibility identifier, from 0 to 15
     * @return the configuration
     * @throws IllegalArgumentException if a value is out of range
     */
    public static DolbyVision of(int profile, int level, int baseLayerCompatibility) {
        return new DolbyVision(1, 0, profile, level, true, false, true, baseLayerCompatibility, 0);
    }

    /**
     * Returns whether a decoder that ignores the RPUs shows a correct picture: the stream has a base layer and
     * its compatibility identifier is not 0.
     *
     * <p>A profile 5 stream, or any stream whose identifier is 0, is not backward compatible: its base layer is
     * coded in a Dolby Vision specific color space.
     *
     * @return {@code true} when the base layer can be shown as plain video
     */
    public boolean isBackwardCompatible() {
        return baseLayerPresent && baseLayerCompatibility != 0;
    }

    /**
     * Checks that a field is within its range.
     *
     * @param name the component name
     * @param value the field value to check
     * @param max the largest allowed value
     */
    private static void check(String name, int value, int max) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(name + " must be from 0 to " + max + ": " + value);
        }
    }
}

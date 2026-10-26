/* Ports Little CMS 2.17 (cmsio1.c). Copyright Marti Maria Saguer; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;

/**
 * Builds the pipelines that take a profile's device values to its connection space (input
 * direction) and back (output direction), for one rendering intent, and the pipelines of device
 * link, abstract and named color profiles.
 *
 * <p>The floating-point tag of the intent ({@code D2Bn} for input, {@code B2Dn} for output) is
 * used when present, its XYZ and L*a*b* ends scaled from and to the normalized encoding. Otherwise
 * a lookup-table tag of the intent is used when present ({@code A2Bn} for input, {@code B2An} for
 * output, falling back to the perceptual tag), otherwise the matrix and tone curves
 * ({@code rXYZ}, {@code gXYZ}, {@code bXYZ} with {@code rTRC}, {@code gTRC}, {@code bTRC}, or
 * {@code kTRC} for gray). Version 2 {@code lut16Type} tables in a L*a*b* connection space are
 * rescaled to the version 4 encoding, and output tables in a L*a*b* connection space interpolate
 * trilinearly.
 *
 * <p>A device link or abstract profile has one pipeline from its data color space to the space its
 * connection space field names: the floating-point tag of the intent, else {@code D2B0}, else the
 * {@code A2Bn} tag of the intent, else {@code A2B0}. A named color profile maps a color index to the
 * connection space values of the color, or, alone in a transform, to its device coordinates.
 *
 * @implNote This implementation follows Little CMS 2.17 ({@code cmsio1.c}: {@code _cmsReadInputLUT},
 * {@code _cmsReadOutputLUT}, {@code _cmsReadDevicelinkLUT}, {@code _cmsReadFloatInputTag},
 * {@code _cmsReadFloatOutputTag}, {@code _cmsReadFloatDevicelinkTag}, {@code BuildGrayInputMatrixPipeline},
 * {@code BuildRGBInputMatrixShaper}, {@code BuildGrayOutputPipeline}, {@code BuildRGBOutputMatrixShaper},
 * {@code _cmsReadMediaWhitePoint}, {@code cmsIsMatrixShaper}, {@code cmsIsCLUT} and {@code cmsIsIntentSupported}).
 * It diverges in two places: a device link read through its {@code D2B0} fallback has its XYZ and L*a*b* ends
 * scaled like the tag of the intent (Little CMS leaves them unscaled), and the colors of a named color profile with
 * an XYZ connection space are XYZ values (Little CMS reads them as L*a*b*).
 */
final class ProfileLuts {
    /**
     * The scale from 0..1 matrix output to the XYZ encoding: {@code 1 / MAX_ENCODEABLE_XYZ}.
     */
    private static final double INPUT_ADJUST = 1.0 / ColorMath.MAX_ENCODEABLE_XYZ;

    /**
     * The scale from the XYZ encoding to 0..1 matrix input: {@code MAX_ENCODEABLE_XYZ}.
     */
    private static final double OUTPUT_ADJUST = ColorMath.MAX_ENCODEABLE_XYZ;

    /**
     * The device-to-connection tags by intent.
     */
    private static final String[] DEVICE_TO_PCS = {"A2B0", "A2B1", "A2B2", "A2B1"};

    /**
     * The connection-to-device tags by intent.
     */
    private static final String[] PCS_TO_DEVICE = {"B2A0", "B2A1", "B2A2", "B2A1"};

    /**
     * The floating-point device-to-connection tags by intent.
     */
    private static final String[] DEVICE_TO_PCS_FLOAT = {"D2B0", "D2B1", "D2B2", "D2B3"};

    /**
     * The floating-point connection-to-device tags by intent.
     */
    private static final String[] PCS_TO_DEVICE_FLOAT = {"B2D0", "B2D1", "B2D2", "B2D3"};

    /**
     * Prevents instantiation.
     */
    private ProfileLuts() {
        throw new AssertionError();
    }

    /**
     * Returns the media white point: the {@code wtpt} tag, or D50 when it is absent or the
     * profile is a version 2 display profile.
     *
     * @param profile the parsed ICC profile
     * @return the white point, XYZ
     */
    static double[] mediaWhite(IccReader profile) {
        var white = profile.xyz(IccReader.signature("wtpt"));
        if (white == null) {
            return ColorMath.d50();
        }
        if (!profile.isVersion4() && profile.deviceClass == IccReader.signature("mntr")) {
            return ColorMath.d50();
        }
        return white;
    }

    /**
     * Returns whether a profile has the tags of a matrix-shaper.
     *
     * @param profile the parsed ICC profile
     * @return {@code true} for a gray profile with {@code kTRC} or an RGB profile with the three colorants and the
     * three curves
     */
    static boolean isMatrixShaper(IccReader profile) {
        if (profile.colorSpace == IccReader.signature("GRAY")) {
            return profile.has(IccReader.signature("kTRC"));
        }
        if (profile.colorSpace == IccReader.signature("RGB ")) {
            return profile.hasAll("rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC");
        }
        return false;
    }

    /**
     * Returns whether an intent is implemented by a lookup-table tag.
     *
     * @param profile the parsed ICC profile
     * @param intent the intent code, {@code 0..3}
     * @param input {@code true} for the input direction
     * @return {@code true} if the intent's tag is present
     */
    static boolean isClut(IccReader profile, int intent, boolean input) {
        if (profile.deviceClass == IccReader.signature("link")) {
            return profile.intent == intent;
        }
        return profile.has(IccReader.signature((input ? DEVICE_TO_PCS : PCS_TO_DEVICE)[intent]));
    }

    /**
     * Returns whether a profile supports an intent in the input direction.
     *
     * @param profile the parsed ICC profile
     * @param intent the intent code, {@code 0..3}
     * @return {@code true} if a table implements it or the profile is a matrix-shaper
     */
    static boolean isIntentSupported(IccReader profile, int intent) {
        return isClut(profile, intent, true) || isMatrixShaper(profile);
    }

    /**
     * Builds the input pipeline: device values to the connection space.
     *
     * @param profile the parsed ICC profile
     * @param intent the intent code, {@code 0..3}
     * @return the pipeline
     * @throws MediaException.Malformed if a needed tag is missing or invalid
     * @throws MediaException.Unsupported if the profile class cannot be used as input
     */
    static Pipeline input(IccReader profile, int intent) {
        checkClass(profile, true);
        if (profile.deviceClass == IccReader.signature("nmcl")) {
            return namedColors(profile, true);
        }
        var channels = IccReader.channels(profile.colorSpace);
        var floating = IccReader.signature(DEVICE_TO_PCS_FLOAT[intent]);
        if (profile.has(floating)) {
            var lut = floatLut(profile, floating, channels, 3);
            normalizeInput(lut, profile.colorSpace);
            normalizeOutput(lut, profile.pcs);
            return lut;
        }
        var tag = IccReader.signature(DEVICE_TO_PCS[intent]);
        if (!profile.has(tag)) {
            tag = IccReader.signature(DEVICE_TO_PCS[0]);
        }
        if (profile.has(tag)) {
            var lut = profile.lut(tag);
            checkChannels(lut, channels, 3, tag);
            if (profile.typeOf(tag) != IccReader.signature("mft2") || profile.pcs != IccReader.signature("Lab ")) {
                return lut;
            }
            if (profile.colorSpace == IccReader.signature("Lab ")) {
                lut.prepend(Stage.Matrix.labV4ToV2());
            }
            return lut.append(Stage.Matrix.labV2ToV4());
        }
        if (profile.colorSpace == IccReader.signature("GRAY")) {
            return grayInput(profile);
        }
        return rgbInput(profile);
    }

    /**
     * Builds the output pipeline: the connection space to device values.
     *
     * @param profile the parsed ICC profile
     * @param intent the intent code, {@code 0..3}
     * @return the pipeline
     * @throws MediaException.Malformed if a needed tag is missing or invalid
     * @throws MediaException.Unsupported if the profile class cannot be used as output
     */
    static Pipeline output(IccReader profile, int intent) {
        checkClass(profile, false);
        var channels = IccReader.channels(profile.colorSpace);
        var floating = IccReader.signature(PCS_TO_DEVICE_FLOAT[intent]);
        if (profile.has(floating)) {
            var lut = floatLut(profile, floating, 3, channels);
            normalizeInput(lut, profile.pcs);
            normalizeOutput(lut, profile.colorSpace);
            return lut;
        }
        var tag = IccReader.signature(PCS_TO_DEVICE[intent]);
        if (!profile.has(tag)) {
            tag = IccReader.signature(PCS_TO_DEVICE[0]);
        }
        if (profile.has(tag)) {
            var lut = profile.lut(tag);
            checkChannels(lut, 3, channels, tag);
            var labPcs = profile.pcs == IccReader.signature("Lab ");
            if (labPcs) {
                var stages = lut.stages();
                for (var i = 0; i < stages.size(); i++) {
                    if (stages.get(i) instanceof Stage.Clut clut) {
                        stages.set(i, clut.trilinear());
                    }
                }
            }
            if (profile.typeOf(tag) != IccReader.signature("mft2") || !labPcs) {
                return lut;
            }
            lut.prepend(Stage.Matrix.labV4ToV2());
            if (profile.colorSpace == IccReader.signature("Lab ")) {
                lut.append(Stage.Matrix.labV2ToV4());
            }
            return lut;
        }
        if (profile.colorSpace == IccReader.signature("GRAY")) {
            return grayOutput(profile);
        }
        return rgbOutput(profile);
    }

    /**
     * Builds the pipeline of a device link or abstract profile, or of a named color profile that is alone in a
     * transform: data color space to the space the connection space field names.
     *
     * @param profile the parsed ICC profile
     * @param intent the intent code, {@code 0..3}
     * @return the pipeline
     * @throws MediaException.Malformed if the profile has no table or a tag is invalid
     */
    static Pipeline deviceLink(IccReader profile, int intent) {
        if (profile.deviceClass == IccReader.signature("nmcl")) {
            return namedColors(profile, false);
        }
        var inputs = IccReader.channels(profile.colorSpace);
        var outputs = IccReader.channels(profile.pcs);
        if (outputs == 0) {
            throw new MediaException.Malformed("ICC profile with unknown output space " + IccReader.name(profile.pcs));
        }
        var floating = IccReader.signature(DEVICE_TO_PCS_FLOAT[intent]);
        if (!profile.has(floating)) {
            floating = IccReader.signature(DEVICE_TO_PCS_FLOAT[0]);
        }
        if (profile.has(floating)) {
            var lut = floatLut(profile, floating, inputs, outputs);
            normalizeInput(lut, profile.colorSpace);
            normalizeOutput(lut, profile.pcs);
            return lut;
        }
        var tag = IccReader.signature(DEVICE_TO_PCS[intent]);
        if (!profile.has(tag)) {
            tag = IccReader.signature(DEVICE_TO_PCS[0]);
        }
        if (!profile.has(tag)) {
            throw new MediaException.Malformed("ICC profile of class " + IccReader.name(profile.deviceClass)
                    + " has no A2B0 or D2B0 table");
        }
        var lut = profile.lut(tag);
        checkChannels(lut, inputs, outputs, tag);
        var lab = IccReader.signature("Lab ");
        if (profile.pcs == lab) {
            var stages = lut.stages();
            for (var i = 0; i < stages.size(); i++) {
                if (stages.get(i) instanceof Stage.Clut clut) {
                    stages.set(i, clut.trilinear());
                }
            }
        }
        if (profile.typeOf(tag) != IccReader.signature("mft2")) {
            return lut;
        }
        if (profile.colorSpace == lab) {
            lut.prepend(Stage.Matrix.labV4ToV2());
        }
        if (profile.pcs == lab) {
            lut.append(Stage.Matrix.labV2ToV4());
        }
        return lut;
    }

    /**
     * Builds the lookup of a named color profile: the color index to the connection space values of the color,
     * or to its device coordinates.
     *
     * @param profile the parsed named color profile
     * @param connectionSpace {@code true} for the connection space values, {@code false} for the device
     * coordinates
     * @return the pipeline
     * @throws MediaException.Malformed if the {@code ncl2} tag is missing or invalid, or holds no device
     * coordinates matching the data color space when they are asked for
     */
    private static Pipeline namedColors(IccReader profile, boolean connectionSpace) {
        var colors = profile.namedColors();
        if (colors == null) {
            throw new MediaException.Malformed("ICC named color profile lacks the ncl2 tag");
        }
        var lab = IccReader.signature("Lab ");
        if (connectionSpace) {
            var pipeline = new Pipeline(1, 3).append(new Stage.NamedColor(3, colors.pcs()));
            if (profile.pcs == lab) {
                pipeline.append(Stage.Matrix.labV2ToV4());
            }
            return pipeline;
        }
        var channels = IccReader.channels(profile.colorSpace);
        if (colors.devices() != channels) {
            throw new MediaException.Malformed("ICC named colors have " + colors.devices()
                    + " device coordinates for a " + channels + "-channel color space");
        }
        var pipeline = new Pipeline(1, channels).append(new Stage.NamedColor(channels, colors.device()));
        if (profile.colorSpace == lab) {
            pipeline.append(Stage.Matrix.labV2ToV4());
        }
        return pipeline;
    }

    /**
     * Reads a floating-point {@code multiProcessElementType} tag and checks its channels.
     *
     * @param profile the parsed ICC profile
     * @param tag the tag signature
     * @param inputs the expected inputs
     * @param outputs the expected outputs
     * @return the pipeline, on natural XYZ and L*a*b* values
     * @throws MediaException.Malformed if the tag is not a {@code multiProcessElementType} or its channels differ
     */
    private static Pipeline floatLut(IccReader profile, int tag, int inputs, int outputs) {
        if (profile.typeOf(tag) != IccReader.signature("mpet")) {
            throw new MediaException.Malformed("ICC tag " + IccReader.name(tag) + " has type "
                    + IccReader.name(profile.typeOf(tag)) + ", expected mpet");
        }
        var lut = profile.lut(tag);
        checkChannels(lut, inputs, outputs, tag);
        return lut;
    }

    /**
     * Prepends the scaling from the normalized encoding to natural units when the input is XYZ or L*a*b*.
     *
     * @param lut the floating-point pipeline
     * @param space the color space signature of its input
     */
    private static void normalizeInput(Pipeline lut, int space) {
        if (space == IccReader.signature("Lab ")) {
            lut.prepend(Stage.Matrix.toLabFloat());
        } else if (space == IccReader.signature("XYZ ")) {
            lut.prepend(Stage.Matrix.toXyzFloat());
        }
    }

    /**
     * Appends the scaling from natural units to the normalized encoding when the output is XYZ or L*a*b*.
     *
     * @param lut the floating-point pipeline
     * @param space the color space signature of its output
     */
    private static void normalizeOutput(Pipeline lut, int space) {
        if (space == IccReader.signature("Lab ")) {
            lut.append(Stage.Matrix.fromLabFloat());
        } else if (space == IccReader.signature("XYZ ")) {
            lut.append(Stage.Matrix.fromXyzFloat());
        }
    }

    /**
     * Rejects the profile classes that have no pipeline in a direction: device link and abstract profiles in
     * both, which {@link #deviceLink} reads, and named color profiles in the output direction.
     *
     * @param profile the parsed ICC profile
     * @param input {@code true} for the input direction
     * @throws MediaException.Unsupported for those classes
     */
    private static void checkClass(IccReader profile, boolean input) {
        var deviceClass = profile.deviceClass;
        if (deviceClass == IccReader.signature("link") || deviceClass == IccReader.signature("abst")
                || (!input && deviceClass == IccReader.signature("nmcl"))) {
            throw new MediaException.Unsupported("icc", "ICC profile class " + IccReader.name(deviceClass)
                    + " cannot be used in the " + (input ? "input" : "output") + " direction");
        }
    }

    /**
     * Checks the channel counts of a lookup table tag.
     *
     * @param lut the table
     * @param inputs the expected inputs
     * @param outputs the expected outputs
     * @param tag the tag signature, for the message
     * @throws MediaException.Malformed if they differ
     */
    private static void checkChannels(Pipeline lut, int inputs, int outputs, int tag) {
        if (lut.inputs() != inputs || lut.outputs() != outputs) {
            throw new MediaException.Malformed("ICC tag " + IccReader.name(tag) + " maps " + lut.inputs() + " to "
                    + lut.outputs() + " channels, expected " + inputs + " to " + outputs);
        }
        for (var stage : lut.stages()) {
            if (stage.inputs != inputs) {
                throw new MediaException.Malformed("ICC tag " + IccReader.name(tag) + " has inconsistent channels");
            }
            inputs = stage.outputs;
        }
        if (inputs != outputs) {
            throw new MediaException.Malformed("ICC tag " + IccReader.name(tag) + " has inconsistent channels");
        }
    }

    /**
     * Reads a required curve tag.
     *
     * @param profile the parsed ICC profile
     * @param tag the tag as a four-character string
     * @return the curve
     * @throws MediaException.Malformed if the tag is missing
     */
    private static ToneCurve requiredCurve(IccReader profile, String tag) {
        var curve = profile.curve(IccReader.signature(tag));
        if (curve == null) {
            throw new MediaException.Malformed("ICC profile without a lookup table lacks the " + tag + " curve");
        }
        return curve;
    }

    /**
     * Reads the colorant matrix: the columns are {@code rXYZ}, {@code gXYZ} and {@code bXYZ}.
     *
     * @param profile the parsed ICC profile
     * @return the matrix
     * @throws MediaException.Malformed if a colorant is missing
     */
    static double[] colorants(IccReader profile) {
        var red = profile.xyz(IccReader.signature("rXYZ"));
        var green = profile.xyz(IccReader.signature("gXYZ"));
        var blue = profile.xyz(IccReader.signature("bXYZ"));
        if (red == null || green == null || blue == null) {
            throw new MediaException.Malformed("ICC profile without a lookup table lacks a colorant tag");
        }
        return new double[]{
                red[0], green[0], blue[0],
                red[1], green[1], blue[1],
                red[2], green[2], blue[2]
        };
    }

    /**
     * Builds the input pipeline of a gray matrix-shaper profile.
     *
     * @param profile the parsed ICC profile
     * @return the pipeline
     */
    private static Pipeline grayInput(IccReader profile) {
        var trc = requiredCurve(profile, "kTRC");
        var pipeline = new Pipeline(1, 3);
        if (profile.pcs == IccReader.signature("Lab ")) {
            var empty = ToneCurve.tabulated(new int[]{0x8080, 0x8080});
            pipeline.append(new Stage.Matrix(1, 3, new double[]{1, 1, 1}, null, Stage.Role.PLAIN));
            pipeline.append(new Stage.Curves(trc, empty, empty));
        } else {
            pipeline.append(new Stage.Curves(trc));
            pipeline.append(new Stage.Matrix(1, 3, new double[]{
                    INPUT_ADJUST * ColorMath.D50_X, INPUT_ADJUST * ColorMath.D50_Y, INPUT_ADJUST * ColorMath.D50_Z
            }, null, Stage.Role.PLAIN));
        }
        return pipeline;
    }

    /**
     * Builds the input pipeline of an RGB matrix-shaper profile.
     *
     * @param profile the parsed ICC profile
     * @return the pipeline
     */
    private static Pipeline rgbInput(IccReader profile) {
        var matrix = colorants(profile);
        for (var i = 0; i < 9; i++) {
            matrix[i] *= INPUT_ADJUST;
        }
        var pipeline = new Pipeline(3, 3);
        pipeline.append(new Stage.Curves(requiredCurve(profile, "rTRC"), requiredCurve(profile, "gTRC"),
                requiredCurve(profile, "bTRC")));
        pipeline.append(new Stage.Matrix(matrix, null));
        if (profile.pcs == IccReader.signature("Lab ")) {
            pipeline.append(new Stage.XyzToLab());
        }
        return pipeline;
    }

    /**
     * Builds the output pipeline of a gray matrix-shaper profile.
     *
     * @param profile the parsed ICC profile
     * @return the pipeline
     */
    private static Pipeline grayOutput(IccReader profile) {
        var reversed = requiredCurve(profile, "kTRC").reverse();
        var pipeline = new Pipeline(3, 1);
        var pick = profile.pcs == IccReader.signature("Lab ")
                ? new double[]{1, 0, 0}
                : new double[]{0, OUTPUT_ADJUST * ColorMath.D50_Y, 0};
        pipeline.append(new Stage.Matrix(3, 1, pick, null, Stage.Role.PLAIN));
        pipeline.append(new Stage.Curves(reversed));
        return pipeline;
    }

    /**
     * Builds the output pipeline of an RGB matrix-shaper profile.
     *
     * @param profile the parsed ICC profile
     * @return the pipeline
     * @throws MediaException.Malformed if the colorant matrix is singular
     */
    private static Pipeline rgbOutput(IccReader profile) {
        var inverse = ColorMath.invert(colorants(profile));
        if (inverse == null) {
            throw new MediaException.Malformed("ICC profile has a singular colorant matrix");
        }
        for (var i = 0; i < 9; i++) {
            inverse[i] *= OUTPUT_ADJUST;
        }
        var pipeline = new Pipeline(3, 3);
        if (profile.pcs == IccReader.signature("Lab ")) {
            pipeline.append(new Stage.LabToXyz());
        }
        pipeline.append(new Stage.Matrix(inverse, null));
        pipeline.append(new Stage.Curves(requiredCurve(profile, "rTRC").reverse(),
                requiredCurve(profile, "gTRC").reverse(), requiredCurve(profile, "bTRC").reverse()));
        return pipeline;
    }
}

/* Ports Little CMS 2.17 (cmscnvrt.c, cmssamp.c). Copyright Marti Maria Saguer; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;

import java.util.List;

/**
 * Links a chain of profiles into one pipeline, applying rendering intents, black point
 * compensation and absolute colorimetric scaling, and detects the black points that black point
 * compensation needs.
 *
 * <p>The first profile of a chain is used in the input direction (device values to the
 * connection space); every later profile is used in the output direction when the chain is in a
 * connection space at that point, otherwise in the input direction. Device link and abstract
 * profiles are applied wherever they stand, from their data color space to the space their
 * connection space field names. A named color profile may only start a chain: it maps color
 * indexes to its connection space, or, alone, to its device coordinates. Between a connection space
 * and an output or abstract profile the linker inserts the conversion its intent asks for: the media
 * white scaling of absolute colorimetric, or the black point compensation scaling, and the XYZ and
 * L*a*b* conversions when the two connection spaces differ.
 *
 * @implNote This implementation follows Little CMS 2.17: {@code DefaultICCintents}, {@code ComputeConversion},
 * {@code AddConversion}, {@code ComputeAbsoluteIntent} (for an observer fully adapted, the default),
 * {@code ComputeBlackPointCompensation} and the black point compensation rules of {@code _cmsLinkProfiles}
 * ({@code cmscnvrt.c}), and {@code cmsDetectBlackPoint} and {@code cmsDetectDestinationBlackPoint} ({@code cmssamp.c}),
 * including the version 4 perceptual black, the darker-colorant method and the least-squares fit of the round-trip L*
 * curve.
 */
final class Linker {
    /**
     * The perceptual intent code.
     */
    static final int PERCEPTUAL = 0;

    /**
     * The relative colorimetric intent code.
     */
    static final int RELATIVE = 1;

    /**
     * The saturation intent code.
     */
    static final int SATURATION = 2;

    /**
     * The absolute colorimetric intent code.
     */
    static final int ABSOLUTE = 3;

    /**
     * The black preservation code of an ICC intent.
     */
    static final int NO_PRESERVATION = 0;

    /**
     * The black preservation code of the intents that keep black-only colors black only
     * ({@code INTENT_PRESERVE_K_ONLY_*}).
     */
    static final int K_ONLY = 1;

    /**
     * The black preservation code of the intents that keep the black plane ({@code INTENT_PRESERVE_K_PLANE_*}).
     */
    static final int K_PLANE = 2;

    /**
     * The version 4 perceptual reference medium black.
     */
    private static final double[] PERCEPTUAL_BLACK = {0.00336, 0.0034731, 0.00287};

    /**
     * Describes one element of a chain: a profile, or the abstract identity L*a*b* profile of
     * version 2 or 4 that black point detection uses.
     *
     * @param profile the profile, or {@code null} for the L*a*b* identity
     * @param labVersion the version of the L*a*b* identity, {@code 2} or {@code 4}
     * @param intent the intent code, {@code 0..3}
     * @param bpc whether black point compensation applies
     * @param preservation how black is preserved: {@link #NO_PRESERVATION}, {@link #K_ONLY} or {@link #K_PLANE}, the
     * intent then being the ICC intent the black-preserving intent is based on
     */
    record Node(IccReader profile, int labVersion, int intent, boolean bpc, int preservation) {
        /**
         * Creates a node for a profile.
         *
         * @param profile the parsed ICC profile the node applies
         * @param intent the intent code
         * @param bpc whether black point compensation applies
         * @return the node
         */
        static Node of(IccReader profile, int intent, boolean bpc) {
            return new Node(profile, 0, intent, bpc, NO_PRESERVATION);
        }

        /**
         * Creates a node for a profile with a black-preserving intent.
         *
         * @param profile the parsed ICC profile the node applies
         * @param intent the ICC intent code the black-preserving intent is based on, {@code 0..2}
         * @param bpc whether black point compensation applies
         * @param preservation {@link #NO_PRESERVATION}, {@link #K_ONLY} or {@link #K_PLANE}
         * @return the node
         */
        static Node of(IccReader profile, int intent, boolean bpc, int preservation) {
            return new Node(profile, 0, intent, bpc, preservation);
        }

        /**
         * Creates a node for the abstract identity L*a*b* profile.
         *
         * @param version the profile version, {@code 2} or {@code 4}
         * @param intent the intent code
         * @return the node
         */
        static Node lab(int version, int intent) {
            return new Node(null, version, intent, false, NO_PRESERVATION);
        }

        /**
         * Returns this node with an ICC intent and a black point compensation flag in place of its own, black
         * preservation dropped.
         *
         * @param bpc whether black point compensation applies
         * @return the node
         */
        Node plain(boolean bpc) {
            return new Node(profile, labVersion, intent, bpc, NO_PRESERVATION);
        }

        /**
         * Returns whether the node is the abstract L*a*b* identity.
         *
         * @return {@code true} for the identity
         */
        boolean isLab() {
            return profile == null;
        }

        /**
         * Returns whether the node's profile is version 4 or later.
         *
         * @return {@code true} for version 4
         */
        boolean isVersion4() {
            return isLab() ? labVersion >= 4 : profile.isVersion4();
        }

        /**
         * Returns the data color space signature.
         *
         * @return the signature
         */
        int colorSpace() {
            return isLab() ? IccReader.signature("Lab ") : profile.colorSpace;
        }

        /**
         * Returns the connection space signature.
         *
         * @return the signature
         */
        int pcs() {
            return isLab() ? IccReader.signature("Lab ") : profile.pcs;
        }
    }

    /**
     * Prevents instantiation.
     */
    private Linker() {
        throw new AssertionError();
    }

    /**
     * Links a chain of profiles ({@code _cmsLinkProfiles}).
     *
     * <p>Black point compensation never applies to the absolute colorimetric intent and always applies to the
     * perceptual and saturation intents of version 4 profiles, except at nodes with a black-preserving intent. The
     * black preservation of the first node decides how the chain links: with the ICC intents, or with
     * {@link BlackPreservingIntents}.
     *
     * @param chain the nodes, at least one
     * @return the pipeline from the first node's device values (a named color index for a named color profile) to
     * the values of the space the chain ends in
     * @throws MediaException.Unsupported if consecutive color spaces do not match, or a named color profile does not
     * start the chain
     * @throws MediaException.Malformed if a profile lacks the tags its role needs
     */
    static Pipeline link(List<Node> chain) {
        var nodes = chain.toArray(Node[]::new);
        var bpc = new boolean[nodes.length];
        for (var i = 0; i < nodes.length; i++) {
            bpc[i] = nodes[i].bpc();
            if (nodes[i].preservation() != NO_PRESERVATION) {
                continue;
            }
            if (nodes[i].intent() == ABSOLUTE) {
                bpc[i] = false;
            }
            if ((nodes[i].intent() == PERCEPTUAL || nodes[i].intent() == SATURATION) && nodes[i].isVersion4()) {
                bpc[i] = true;
            }
        }
        var plain = new Node[nodes.length];
        for (var i = 0; i < nodes.length; i++) {
            plain[i] = nodes[i].plain(bpc[i]);
        }
        return switch (nodes[0].preservation()) {
            case K_ONLY -> BlackPreservingIntents.kOnly(plain);
            case K_PLANE -> BlackPreservingIntents.kPlane(plain);
            default -> link(plain);
        };
    }

    /**
     * Links a chain of profiles with the ICC intents, without adjusting black point compensation
     * ({@code DefaultICCintents}).
     *
     * @param nodes the nodes, at least one, each with the black point compensation that applies to it
     * @return the pipeline, as {@link #link(List)} describes it
     * @throws MediaException.Unsupported if consecutive color spaces do not match, or a named color profile does not
     * start the chain
     * @throws MediaException.Malformed if a profile lacks the tags its role needs
     */
    static Pipeline link(Node[] nodes) {
        var bpc = new boolean[nodes.length];
        for (var i = 0; i < nodes.length; i++) {
            bpc[i] = nodes[i].bpc();
        }
        var lab = IccReader.signature("Lab ");
        var xyz = IccReader.signature("XYZ ");
        var abstractClass = IccReader.signature("abst");
        var namedClass = IccReader.signature("nmcl");
        var current = nodes[0].colorSpace();
        var result = new Pipeline(1, 1);
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var deviceClass = node.isLab() ? abstractClass : node.profile().deviceClass;
            var deviceLink = deviceClass == abstractClass || deviceClass == IccReader.signature("link");
            var alone = deviceClass == namedClass && nodes.length == 1;
            if (deviceClass == namedClass && i > 0) {
                throw new MediaException.Unsupported("icc", "a named color profile can only start a chain");
            }
            var input = i == 0 && !deviceLink || (current != xyz && current != lab);
            int spaceIn;
            int spaceOut;
            if (input || deviceLink) {
                spaceIn = node.colorSpace();
                spaceOut = alone ? node.colorSpace() : node.pcs();
            } else {
                spaceIn = node.pcs();
                spaceOut = node.colorSpace();
            }
            if (!compatible(spaceIn, current)) {
                throw new MediaException.Unsupported("icc", "cannot link color space " + IccReader.name(current)
                        + " to " + IccReader.name(spaceIn));
            }
            if (deviceLink || alone) {
                var lut = node.isLab() ? null : ProfileLuts.deviceLink(node.profile(), node.intent());
                var conversion = deviceClass == abstractClass && i > 0 ? conversion(nodes, i, bpc[i])
                        : new double[][]{ColorMath.identity(), new double[3]};
                addConversion(result, current, spaceIn, conversion[0], conversion[1]);
                if (lut != null) {
                    result.append(lut);
                }
            } else if (input) {
                result.append(ProfileLuts.input(node.profile(), node.intent()));
            } else {
                var lut = ProfileLuts.output(node.profile(), node.intent());
                var conversion = conversion(nodes, i, bpc[i]);
                addConversion(result, current, spaceIn, conversion[0], conversion[1]);
                result.append(lut);
            }
            current = spaceOut;
        }
        var inputs = nodes[0].isLab() || nodes[0].profile().deviceClass != namedClass
                ? IccReader.channels(nodes[0].colorSpace()) : 1;
        var outputs = IccReader.channels(current);
        if (outputs == 0) {
            throw new MediaException.Malformed("ICC profile chain ends in unknown color space " + IccReader.name(current));
        }
        return result.copy(inputs, outputs);
    }

    /**
     * Returns the color space a chain of profiles ends in, as {@link #link} tracks it.
     *
     * @param profiles the parsed profiles of the chain, at least one
     * @return the signature of the last color space
     */
    static int exitSpace(List<IccReader> profiles) {
        var lab = IccReader.signature("Lab ");
        var xyz = IccReader.signature("XYZ ");
        var current = profiles.getFirst().colorSpace;
        for (var i = 0; i < profiles.size(); i++) {
            var profile = profiles.get(i);
            var deviceClass = profile.deviceClass;
            var deviceLink = deviceClass == IccReader.signature("abst") || deviceClass == IccReader.signature("link");
            if (deviceClass == IccReader.signature("nmcl")) {
                current = profiles.size() == 1 ? profile.colorSpace : profile.pcs;
            } else if (deviceLink || i == 0 || (current != xyz && current != lab)) {
                current = profile.pcs;
            } else {
                current = profile.colorSpace;
            }
        }
        return current;
    }

    /**
     * Returns whether two color spaces may follow one another.
     *
     * @param a the first signature
     * @param b the second signature
     * @return {@code true} if they are equal, are CMYK and generic four-color, or are XYZ and L*a*b*, which convert
     * into one another
     */
    private static boolean compatible(int a, int b) {
        if (a == b) {
            return true;
        }
        var cmyk = IccReader.signature("CMYK");
        var four = IccReader.signature("4CLR");
        var xyz = IccReader.signature("XYZ ");
        var lab = IccReader.signature("Lab ");
        return (a == four && b == cmyk) || (a == cmyk && b == four) || (a == xyz && b == lab) || (a == lab && b == xyz);
    }

    /**
     * Computes the connection-space conversion into node {@code i}: absolute colorimetric
     * scaling or black point compensation, with the offset expressed in the XYZ encoding.
     *
     * @param nodes the chain
     * @param i the index of the node being entered, at least one
     * @param bpc whether black point compensation applies
     * @return the matrix and the offset
     */
    private static double[][] conversion(Node[] nodes, int i, boolean bpc) {
        var matrix = ColorMath.identity();
        var offset = new double[3];
        var intent = nodes[i].intent();
        if (intent == ABSOLUTE) {
            var whiteIn = mediaWhite(nodes[i - 1]);
            var whiteOut = mediaWhite(nodes[i]);
            matrix = new double[]{
                    whiteIn[0] / whiteOut[0], 0, 0,
                    0, whiteIn[1] / whiteOut[1], 0,
                    0, 0, whiteIn[2] / whiteOut[2]
            };
        } else if (bpc) {
            var blackIn = detectBlackPoint(nodes[i - 1], intent);
            var blackOut = detectDestinationBlackPoint(nodes[i], intent);
            if (blackIn[0] != blackOut[0] || blackIn[1] != blackOut[1] || blackIn[2] != blackOut[2]) {
                var d50 = ColorMath.d50();
                for (var k = 0; k < 3; k++) {
                    var t = blackIn[k] - d50[k];
                    matrix[k * 4] = (blackOut[k] - d50[k]) / t;
                    offset[k] = -d50[k] * (blackOut[k] - blackIn[k]) / t;
                }
            }
        }
        for (var k = 0; k < 3; k++) {
            offset[k] /= ColorMath.MAX_ENCODEABLE_XYZ;
        }
        return new double[][]{matrix, offset};
    }

    /**
     * Returns the media white point of a node.
     *
     * @param node the link node: a profile with its intent and direction
     * @return the white point, D50 for the L*a*b* identity
     */
    private static double[] mediaWhite(Node node) {
        return node.isLab() ? ColorMath.d50() : ProfileLuts.mediaWhite(node.profile());
    }

    /**
     * Appends the stages that convert between two connection spaces with a matrix and offset in
     * XYZ.
     *
     * @param result the pipeline
     * @param from the current connection space
     * @param to the connection space of the next node
     * @param matrix the XYZ matrix
     * @param offset the XYZ offset in the XYZ encoding
     * @throws MediaException.Unsupported if the spaces do not match
     */
    private static void addConversion(Pipeline result, int from, int to, double[] matrix, double[] offset) {
        var lab = IccReader.signature("Lab ");
        var xyz = IccReader.signature("XYZ ");
        var empty = isEmptyLayer(matrix, offset);
        if (from == xyz) {
            if (to == xyz) {
                if (!empty) {
                    result.append(new Stage.Matrix(matrix, offset));
                }
            } else if (to == lab) {
                if (!empty) {
                    result.append(new Stage.Matrix(matrix, offset));
                }
                result.append(new Stage.XyzToLab());
            } else {
                throw new MediaException.Unsupported("icc", "cannot link XYZ to " + IccReader.name(to));
            }
        } else if (from == lab) {
            if (to == xyz) {
                result.append(new Stage.LabToXyz());
                if (!empty) {
                    result.append(new Stage.Matrix(matrix, offset));
                }
            } else if (to == lab) {
                if (!empty) {
                    result.append(new Stage.LabToXyz());
                    result.append(new Stage.Matrix(matrix, offset));
                    result.append(new Stage.XyzToLab());
                }
            } else {
                throw new MediaException.Unsupported("icc", "cannot link L*a*b* to " + IccReader.name(to));
            }
        } else if (from != to) {
            throw new MediaException.Unsupported("icc", "cannot link " + IccReader.name(from) + " to " + IccReader.name(to));
        }
    }

    /**
     * Returns whether a matrix and offset leave values unchanged within {@code 0.002}.
     *
     * @param matrix the 3x3 matrix, row-major
     * @param offset the offset added after the matrix
     * @return {@code true} for a no-op
     */
    private static boolean isEmptyLayer(double[] matrix, double[] offset) {
        var difference = 0.0;
        var identity = ColorMath.identity();
        for (var i = 0; i < 9; i++) {
            difference += Math.abs(matrix[i] - identity[i]);
        }
        for (var i = 0; i < 3; i++) {
            difference += Math.abs(offset[i]);
        }
        return difference < 0.002;
    }

    /**
     * Detects the black point of a node used as a source.
     *
     * @param node the link node: a profile with its intent and direction
     * @param intent the intent code
     * @return the black point, D50 XYZ, zero when it cannot be detected
     */
    static double[] detectBlackPoint(Node node, int intent) {
        var black = blackPoint(node, intent);
        return black == null ? new double[3] : black;
    }

    /**
     * Detects the black point of a node used as a source, telling a failed detection apart.
     *
     * @param node the link node: a profile with its intent and direction
     * @param intent the intent code
     * @return the black point, D50 XYZ, or {@code null} when the detection failed
     */
    private static double[] blackPoint(Node node, int intent) {
        if (node.isLab() || !isDeviceProfile(node.profile())) {
            return null;
        }
        var profile = node.profile();
        if (intent != PERCEPTUAL && intent != RELATIVE && intent != SATURATION) {
            return null;
        }
        if (profile.isVersion4() && (intent == PERCEPTUAL || intent == SATURATION)) {
            if (ProfileLuts.isMatrixShaper(profile)) {
                return blackPointAsDarkerColorant(profile, RELATIVE);
            }
            return PERCEPTUAL_BLACK.clone();
        }
        if (intent == RELATIVE && profile.deviceClass == IccReader.signature("prtr")
                && profile.colorSpace == IccReader.signature("CMYK")) {
            return blackPointUsingPerceptualBlack(profile);
        }
        return blackPointAsDarkerColorant(profile, intent);
    }

    /**
     * Returns whether a profile converts device values, rather than being a device link, an
     * abstract or a named color profile.
     *
     * @param profile the parsed ICC profile
     * @return {@code true} for a device profile
     */
    private static boolean isDeviceProfile(IccReader profile) {
        var deviceClass = profile.deviceClass;
        return deviceClass != IccReader.signature("link") && deviceClass != IccReader.signature("abst")
                && deviceClass != IccReader.signature("nmcl");
    }

    /**
     * Detects the black point of a node used as a destination.
     *
     * @param node the link node: a profile with its intent and direction
     * @param intent the intent code
     * @return the black point, D50 XYZ, zero when it cannot be detected
     */
    static double[] detectDestinationBlackPoint(Node node, int intent) {
        if (node.isLab() || !isDeviceProfile(node.profile())) {
            return new double[3];
        }
        var profile = node.profile();
        if (intent != PERCEPTUAL && intent != RELATIVE && intent != SATURATION) {
            return new double[3];
        }
        if (profile.isVersion4() && (intent == PERCEPTUAL || intent == SATURATION)) {
            if (ProfileLuts.isMatrixShaper(profile)) {
                var black = blackPointAsDarkerColorant(profile, RELATIVE);
                return black == null ? new double[3] : black;
            }
            return PERCEPTUAL_BLACK.clone();
        }
        var space = profile.colorSpace;
        if (!ProfileLuts.isClut(profile, intent, false) || (space != IccReader.signature("GRAY")
                && space != IccReader.signature("RGB ") && space != IccReader.signature("CMYK"))) {
            return detectBlackPoint(node, intent);
        }
        double[] initialLab;
        if (intent == RELATIVE) {
            var initial = blackPoint(node, intent);
            if (initial == null) {
                return new double[3];
            }
            initialLab = ColorMath.xyzToLab(initial);
        } else {
            initialLab = new double[3];
        }
        var roundTrip = roundTrip(profile, intent);
        var inRamp = new double[256];
        var outRamp = new double[256];
        for (var l = 0; l < 256; l++) {
            var lab = new double[]{(l * 100.0) / 255.0, Math.min(50, Math.max(-50, initialLab[1])),
                    Math.min(50, Math.max(-50, initialLab[2]))};
            inRamp[l] = lab[0];
            outRamp[l] = evaluateLab(roundTrip, lab)[0];
        }
        for (var l = 254; l > 0; --l) {
            outRamp[l] = Math.min(outRamp[l], outRamp[l + 1]);
        }
        if (!(outRamp[0] < outRamp[255])) {
            return new double[3];
        }
        var minL = outRamp[0];
        var maxL = outRamp[255];
        if (intent == RELATIVE) {
            var straight = true;
            for (var l = 0; l < 256; l++) {
                if (!((inRamp[l] <= minL + 0.2 * (maxL - minL)) || (Math.abs(inRamp[l] - outRamp[l]) < 4.0))) {
                    straight = false;
                }
            }
            if (straight) {
                return ColorMath.labToXyz(initialLab);
            }
        }
        var yRamp = new double[256];
        for (var l = 0; l < 256; l++) {
            yRamp[l] = (outRamp[l] - minL) / (maxL - minL);
        }
        double lo;
        double hi;
        if (intent == RELATIVE) {
            lo = 0.1;
            hi = 0.5;
        } else {
            lo = 0.03;
            hi = 0.25;
        }
        var x = new double[256];
        var y = new double[256];
        var n = 0;
        for (var l = 0; l < 256; l++) {
            var ff = yRamp[l];
            if (ff >= lo && ff < hi) {
                x[n] = inRamp[l];
                y[n] = yRamp[l];
                n++;
            }
        }
        if (n < 3) {
            return new double[3];
        }
        var lightness = Math.max(rootOfQuadraticFit(n, x, y), 0);
        return ColorMath.labToXyz(new double[]{lightness, initialLab[1], initialLab[2]});
    }

    /**
     * Finds the black point as the L*a*b* of the darkest device color, made neutral and clipped
     * to {@code L* <= 50}.
     *
     * @param profile the parsed ICC profile
     * @param intent the intent code
     * @return the black point, D50 XYZ, or {@code null} when it cannot be found
     */
    private static double[] blackPointAsDarkerColorant(IccReader profile, int intent) {
        if (!ProfileLuts.isIntentSupported(profile, intent)) {
            return null;
        }
        var black = darkestColorant(profile.colorSpace);
        if (black == null) {
            return null;
        }
        Pipeline pipeline;
        try {
            pipeline = link(List.of(Node.of(profile, intent, false), Node.lab(2, intent)));
        } catch (MediaException exception) {
            return null;
        }
        var in = new float[black.length];
        for (var i = 0; i < black.length; i++) {
            in[i] = black[i] / 65535.0f;
        }
        var out = new float[3];
        pipeline.evaluate(in, out);
        var lightness = out[0] * 100.0;
        if (lightness > 50 || lightness < 0) {
            lightness = 0;
        }
        return ColorMath.labToXyz(new double[]{lightness, 0, 0});
    }

    /**
     * Finds the black point by sending L*a*b* black through the perceptual output table and back
     * through the colorimetric input table.
     *
     * @param profile the parsed ICC profile
     * @return the black point, D50 XYZ, or {@code null} when the round trip cannot be built
     */
    private static double[] blackPointUsingPerceptualBlack(IccReader profile) {
        if (!ProfileLuts.isIntentSupported(profile, PERCEPTUAL)) {
            return new double[3];
        }
        Pipeline roundTrip;
        try {
            roundTrip = roundTrip(profile, PERCEPTUAL);
        } catch (MediaException exception) {
            return null;
        }
        var lab = evaluateLab(roundTrip, new double[3]);
        return ColorMath.labToXyz(new double[]{Math.min(lab[0], 50), 0, 0});
    }

    /**
     * Builds the L*a*b* round trip through a profile: output direction with an intent, input
     * direction colorimetrically.
     *
     * @param profile the parsed ICC profile
     * @param intent the intent of the output direction
     * @return the pipeline
     */
    private static Pipeline roundTrip(IccReader profile, int intent) {
        return link(List.of(Node.lab(4, RELATIVE), Node.of(profile, intent, false), Node.of(profile, RELATIVE, false),
                Node.lab(4, RELATIVE)));
    }

    /**
     * Evaluates a pipeline from L*a*b* to L*a*b*.
     *
     * @param pipeline the pipeline, in the version 4 encoding at both ends
     * @param lab the input L*a*b*
     * @return the output L*a*b*
     */
    private static double[] evaluateLab(Pipeline pipeline, double[] lab) {
        var in = new float[]{(float) (lab[0] / 100.0), (float) ((lab[1] + 128.0) / 255.0), (float) ((lab[2] + 128.0) / 255.0)};
        var out = new float[3];
        pipeline.evaluate(in, out);
        return new double[]{out[0] * 100.0, out[1] * 255.0 - 128.0, out[2] * 255.0 - 128.0};
    }

    /**
     * Returns the darkest device color of a color space in 16-bit samples.
     *
     * @param space the color space signature
     * @return the samples, or {@code null} for a space without a defined darkest color
     */
    private static int[] darkestColorant(int space) {
        return switch (IccReader.name(space)) {
            case "GRAY" -> new int[]{0};
            case "RGB " -> new int[]{0, 0, 0};
            case "Lab " -> new int[]{0, 0x8080, 0x8080};
            case "CMYK" -> new int[]{0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF};
            case "CMY " -> new int[]{0xFFFF, 0xFFFF, 0xFFFF};
            default -> null;
        };
    }

    /**
     * Fits a quadratic to points by least squares and returns its root in {@code 0..50}.
     *
     * @param n the number of points
     * @param x the abscissas
     * @param y the ordinates
     * @return the root, or {@code 0} when the fit is degenerate
     */
    private static double rootOfQuadraticFit(int n, double[] x, double[] y) {
        if (n < 4) {
            return 0;
        }
        double sumX = 0, sumX2 = 0, sumX3 = 0, sumX4 = 0, sumY = 0, sumYX = 0, sumYX2 = 0;
        for (var i = 0; i < n; i++) {
            var xn = x[i];
            var yn = y[i];
            sumX += xn;
            sumX2 += xn * xn;
            sumX3 += xn * xn * xn;
            sumX4 += xn * xn * xn * xn;
            sumY += yn;
            sumYX += yn * xn;
            sumYX2 += yn * xn * xn;
        }
        var m = new double[]{n, sumX, sumX2, sumX, sumX2, sumX3, sumX2, sumX3, sumX4};
        var inverse = ColorMath.invert(m);
        if (inverse == null) {
            return 0;
        }
        var res = ColorMath.apply(inverse, new double[]{sumY, sumYX, sumYX2});
        var a = res[2];
        var b = res[1];
        var c = res[0];
        if (Math.abs(a) < 1.0E-10) {
            if (Math.abs(b) < 1.0E-10) {
                return 0;
            }
            return Math.min(0, Math.max(50, -c / b));
        }
        var d = b * b - 4.0 * a * c;
        if (d <= 0) {
            return 0;
        }
        var root = (-b + Math.sqrt(d)) / (2.0 * a);
        return Math.max(0, Math.min(50, root));
    }
}

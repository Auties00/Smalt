package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.TransferCharacteristics;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes how the values of a picture map to color: an ICC profile, or the CICP description
 * of a video frame.
 *
 * <p>Profiles come from four places:
 * <ul>
 *   <li>{@link #parse(byte[])} reads an ICC profile of version 2 or 4, as embedded in JPEG
 *       {@code APP2}, PNG {@code iCCP}, WebP {@code ICCP} or HEIF {@code colr} data;</li>
 *   <li>the built-in profiles {@link #srgb()}, {@link #displayP3()}, {@link #adobeRgb()},
 *       {@link #proPhotoRgb()}, {@link #rec709()}, {@link #rec2020()} and
 *       {@link #grayGamma22()};</li>
 *   <li>{@link #of(ColorInfo)} maps the color primaries and transfer characteristics of a video
 *       frame or an AVIF/HEIF {@code nclx} box (ITU-T H.273);</li>
 *   <li>{@link #rgb} and {@link #gray} build a profile from primaries and a gamma, as PNG
 *       {@code cHRM} and {@code gAMA} chunks describe them.</li>
 * </ul>
 * Every profile except a high-dynamic-range one ({@link #isHighDynamicRange()}) is an ICC
 * profile whose bytes {@link #toByteArray()} returns, ready to embed in a file.
 *
 * <p>Parsing validates the header and the tag directory; the tags a transform needs are decoded
 * when a {@link ColorTransform} is first created, which reports a damaged tag as
 * {@link MediaException.Malformed}.
 *
 * <p>Instances are immutable and thread-safe. Two profiles are equal when their ICC bytes are, or
 * for high-dynamic-range profiles when their primaries, transfer and peak luminance are.
 */
public final class ColorProfile {
    /**
     * The parsed ICC profile, or {@code null} for a high-dynamic-range profile.
     */
    private final IccReader icc;

    /**
     * The ICC bytes, or {@code null} for a high-dynamic-range profile.
     */
    private final byte[] bytes;

    /**
     * The high-dynamic-range description, or {@code null} for an ICC profile.
     */
    private final HdrSpec hdr;

    /**
     * The hash code, computed on first use; zero until then.
     */
    private int hash;

    /**
     * Describes a high-dynamic-range profile.
     *
     * @param red the red chromaticity
     * @param green the green chromaticity
     * @param blue the blue chromaticity
     * @param white the white chromaticity
     * @param transfer {@link TransferCharacteristics#PQ} or {@link TransferCharacteristics#HLG}
     * @param peak the peak luminance of the content in candelas per square meter
     */
    record HdrSpec(double[] red, double[] green, double[] blue, double[] white, TransferCharacteristics transfer,
                   double peak) {
        @Override
        public boolean equals(Object other) {
            return other instanceof HdrSpec that && Arrays.equals(red, that.red) && Arrays.equals(green, that.green)
                    && Arrays.equals(blue, that.blue) && Arrays.equals(white, that.white) && transfer == that.transfer
                    && Double.compare(peak, that.peak) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(red), Arrays.hashCode(green), Arrays.hashCode(blue),
                    Arrays.hashCode(white), transfer, peak);
        }
    }

    /**
     * Creates an ICC profile.
     *
     * @param bytes the profile bytes, owned by the new instance
     */
    private ColorProfile(byte[] bytes) {
        this.icc = new IccReader(bytes);
        this.bytes = bytes;
        this.hdr = null;
    }

    /**
     * Creates a high-dynamic-range profile.
     *
     * @param hdr the description
     */
    private ColorProfile(HdrSpec hdr) {
        this.icc = null;
        this.bytes = null;
        this.hdr = hdr;
    }

    /**
     * Parses an ICC profile.
     *
     * @param data the profile bytes; copied
     * @return the profile
     * @throws MediaException.Malformed if the header or the tag directory is invalid
     */
    public static ColorProfile parse(byte[] data) {
        Objects.requireNonNull(data, "data");
        return new ColorProfile(data.clone());
    }

    /**
     * Returns the sRGB profile (IEC 61966-2-1): BT.709 primaries, D65 white and the sRGB tone
     * curve.
     *
     * @return the profile
     */
    public static ColorProfile srgb() {
        return BuiltIn.SRGB;
    }

    /**
     * Returns the Display P3 profile: DCI-P3 primaries with a D65 white and the sRGB tone curve,
     * the color space of photographs taken by recent phones.
     *
     * @return the profile
     */
    public static ColorProfile displayP3() {
        return BuiltIn.DISPLAY_P3;
    }

    /**
     * Returns a profile compatible with Adobe RGB (1998): its primaries, a D65 white and a gamma of
     * {@code 563 / 256}.
     *
     * @return the profile
     */
    public static ColorProfile adobeRgb() {
        return BuiltIn.ADOBE_RGB;
    }

    /**
     * Returns the ProPhoto RGB (ROMM RGB) profile: its primaries, a D50 white and a gamma of 1.8.
     *
     * @return the profile
     */
    public static ColorProfile proPhotoRgb() {
        return BuiltIn.PRO_PHOTO;
    }

    /**
     * Returns the ITU-R BT.709 profile: BT.709 primaries, a D65 white and the inverse of the BT.709
     * opto-electronic transfer function.
     *
     * @return the profile
     */
    public static ColorProfile rec709() {
        return BuiltIn.REC_709;
    }

    /**
     * Returns the ITU-R BT.2020 standard-dynamic-range profile: BT.2020 primaries, a D65 white and
     * the inverse of the BT.709 opto-electronic transfer function.
     *
     * @return the profile
     */
    public static ColorProfile rec2020() {
        return BuiltIn.REC_2020;
    }

    /**
     * Returns a gray profile with a gamma of 2.2.
     *
     * @return the profile
     */
    public static ColorProfile grayGamma22() {
        return BuiltIn.GRAY_22;
    }

    /**
     * Builds an RGB profile from primaries, a white point and a pure power tone curve, as a PNG
     * {@code cHRM} and {@code gAMA} chunk pair describes one.
     *
     * @param red the red primary
     * @param green the green primary
     * @param blue the blue primary
     * @param white the white point
     * @param gamma the exponent that decodes values to linear light, for example {@code 2.2} for a PNG {@code gAMA} of
     * {@code 45455}
     * @return the profile
     * @throws IllegalArgumentException if the gamma is not positive and finite, or the primaries do not span a color
     * space
     */
    public static ColorProfile rgb(Chromaticity red, Chromaticity green, Chromaticity blue, Chromaticity white,
                                   double gamma) {
        Objects.requireNonNull(red, "red");
        Objects.requireNonNull(green, "green");
        Objects.requireNonNull(blue, "blue");
        Objects.requireNonNull(white, "white");
        checkGamma(gamma);
        if (white.y() == 0) {
            throw new IllegalArgumentException("white point with y = 0");
        }
        return new ColorProfile(IccWriter.rgb("RGB, gamma " + gamma, xy(red), xy(green), xy(blue), xy(white),
                IccWriter.Curve.gamma(gamma)));
    }

    /**
     * Builds a gray profile with a pure power tone curve and a D65 white.
     *
     * @param gamma the exponent that decodes values to linear light
     * @return the profile
     * @throws IllegalArgumentException if the gamma is not positive and finite
     */
    public static ColorProfile gray(double gamma) {
        checkGamma(gamma);
        return new ColorProfile(IccWriter.gray("Gray, gamma " + gamma, new double[]{0.3127, 0.3290},
                IccWriter.Curve.gamma(gamma)));
    }

    /**
     * Maps the color description of a video frame, or of an AVIF/HEIF {@code nclx} box, to a
     * profile.
     *
     * <p>The primaries map to their ITU-T H.273 chromaticities ({@code UNSPECIFIED} meaning
     * BT.709). The transfer characteristics map as follows:
     * <ul>
     *   <li>{@code SRGB}, {@code BT709}, {@code SMPTE170M}, {@code BT2020_10},
     *       {@code BT2020_12}, {@code IEC61966_2_4} and {@code UNSPECIFIED}: the sRGB tone curve,
     *       as web browsers display standard-dynamic-range video;</li>
     *   <li>{@code GAMMA22}, {@code GAMMA28} and {@code LINEAR}: the power curves 2.2, 2.8 and
     *       1.0; {@code SMPTE240M}: its own curve;</li>
     *   <li>{@code PQ} and {@code HLG}: a high-dynamic-range profile, whose content peak is the
     *       mastering display's maximum luminance, else the maximum content light level, else
     *       1000 candelas per square meter (always 1000 for HLG).</li>
     * </ul>
     * The matrix coefficients and range are not part of the profile: they convert Y'CbCr to
     * R'G'B' before the profile applies.
     *
     * @param color the color description
     * @return the profile
     * @throws MediaException.Unsupported for CIE XYZ primaries and for the logarithmic, BT.1361 and SMPTE 428 transfer
     * characteristics
     */
    public static ColorProfile of(ColorInfo color) {
        Objects.requireNonNull(color, "color");
        var c = Hdr.chromaticities(color.primaries());
        var description = "CICP " + color.primaries() + " / " + color.transfer();
        return switch (color.transfer()) {
            case SRGB, BT709, SMPTE170M, BT2020_10, BT2020_12, IEC61966_2_4, UNSPECIFIED ->
                    new ColorProfile(IccWriter.rgb(description, c[0], c[1], c[2], c[3], IccWriter.Curve.SRGB));
            case GAMMA22 -> new ColorProfile(IccWriter.rgb(description, c[0], c[1], c[2], c[3], IccWriter.Curve.gamma(2.2)));
            case GAMMA28 -> new ColorProfile(IccWriter.rgb(description, c[0], c[1], c[2], c[3], IccWriter.Curve.gamma(2.8)));
            case LINEAR -> new ColorProfile(IccWriter.rgb(description, c[0], c[1], c[2], c[3], IccWriter.Curve.gamma(1.0)));
            case SMPTE240M -> new ColorProfile(IccWriter.rgb(description, c[0], c[1], c[2], c[3],
                    new IccWriter.Curve(3, 1 / 0.45, 1 / 1.1115, 0.1115 / 1.1115, 1 / 4.0, 0.0913)));
            case PQ -> new ColorProfile(new HdrSpec(c[0], c[1], c[2], c[3], TransferCharacteristics.PQ, pqPeak(color)));
            case HLG -> new ColorProfile(new HdrSpec(c[0], c[1], c[2], c[3], TransferCharacteristics.HLG, Hdr.HLG_PEAK));
            default -> throw new MediaException.Unsupported("cicp", color.transfer() + " transfer characteristics");
        };
    }

    /**
     * Returns the content peak of a PQ description.
     *
     * @param color the color description
     * @return the peak luminance in candelas per square meter, within {@code 100..10000}
     */
    private static double pqPeak(ColorInfo color) {
        var peak = color.masteringDisplay().map(display -> display.maxLuminance()).filter(value -> value > 0)
                .or(() -> color.contentLightLevel().map(level -> (double) level.maxContentLightLevel())
                        .filter(value -> value > 0))
                .orElse(Hdr.HLG_PEAK);
        return Math.min(Math.max(peak, 100.0), Hdr.PQ_PEAK);
    }

    /**
     * Checks a gamma.
     *
     * @param gamma the exponent to check
     * @throws IllegalArgumentException if it is not positive and finite, or exceeds 64
     */
    private static void checkGamma(double gamma) {
        if (!(gamma > 0 && gamma <= 64)) {
            throw new IllegalArgumentException("invalid gamma " + gamma);
        }
    }

    /**
     * Converts a chromaticity to an array.
     *
     * @param chromaticity the CIE 1931 chromaticity
     * @return {@code {x, y}}
     */
    private static double[] xy(Chromaticity chromaticity) {
        return new double[]{chromaticity.x(), chromaticity.y()};
    }

    /**
     * {@return the color space of the data; {@link ProfileColorSpace#RGB} for a high-dynamic-range profile}
     */
    public ProfileColorSpace colorSpace() {
        return icc == null ? ProfileColorSpace.RGB : ProfileColorSpace.of(icc.colorSpace);
    }

    /**
     * {@return the number of channels of the data, 3 for a high-dynamic-range profile}
     */
    public int channels() {
        return icc == null ? 3 : IccReader.channels(icc.colorSpace);
    }

    /**
     * {@return the profile connection space, {@link ProfileColorSpace#XYZ} or {@link ProfileColorSpace#LAB}; a device
     * link reports its output space}
     */
    public ProfileColorSpace connectionSpace() {
        return icc == null ? ProfileColorSpace.XYZ : ProfileColorSpace.of(icc.pcs);
    }

    /**
     * {@return the device class; {@link ProfileClass#DISPLAY} for a high-dynamic-range profile}
     */
    public ProfileClass deviceClass() {
        return icc == null ? ProfileClass.DISPLAY : ProfileClass.of(icc.deviceClass);
    }

    /**
     * Returns the ICC version of the profile.
     *
     * @return the version as {@code "major.minor.bugfix"}, for example {@code "4.3.0"}; {@code "4.4.0"} for a
     * high-dynamic-range profile
     */
    public String version() {
        if (icc == null) {
            return "4.4.0";
        }
        var version = icc.version;
        return ((version >>> 24) & 0xFF) + "." + ((version >>> 20) & 0x0F) + "." + ((version >>> 16) & 0x0F);
    }

    /**
     * Returns the rendering intent the header recommends.
     *
     * @return the intent; {@link RenderingIntent#PERCEPTUAL} when the header holds an invalid code
     */
    public RenderingIntent renderingIntent() {
        if (icc == null || icc.intent < 0 || icc.intent > 3) {
            return RenderingIntent.PERCEPTUAL;
        }
        return RenderingIntent.values()[icc.intent];
    }

    /**
     * Returns the description of the profile ({@code desc} tag).
     *
     * @return the description, preferring English, or empty when the profile has none
     */
    public Optional<String> description() {
        if (icc == null) {
            return Optional.of(hdr.transfer() == TransferCharacteristics.PQ ? "PQ (SMPTE ST 2084)" : "HLG (ARIB STD-B67)");
        }
        return icc.text(IccReader.signature("desc"));
    }

    /**
     * {@return the copyright notice of the profile ({@code cprt} tag), or empty when the profile has none}
     */
    public Optional<String> copyright() {
        return icc == null ? Optional.empty() : icc.text(IccReader.signature("cprt"));
    }

    /**
     * Returns whether the profile is a matrix/TRC profile: an RGB profile with three colorants and
     * three tone curves, or a gray profile with a tone curve.
     *
     * @return {@code true} for a matrix/TRC profile
     */
    public boolean isMatrixShaper() {
        return icc != null && ProfileLuts.isMatrixShaper(icc);
    }

    /**
     * Returns whether the profile describes a high-dynamic-range signal (PQ or HLG), which
     * {@link #of(ColorInfo)} creates and which has no ICC form.
     *
     * @return {@code true} for a high-dynamic-range profile
     */
    public boolean isHighDynamicRange() {
        return hdr != null;
    }

    /**
     * Holds the sRGB tone curve {@link #isSrgb()} compares against, built on first use.
     */
    private static final class SrgbReference {
        /**
         * The sRGB tone curve at the precision profiles encode it.
         */
        private static final ToneCurve CURVE = IccWriter.Curve.SRGB.toToneCurve();

        /**
         * Prevents instantiation.
         */
        private SrgbReference() {
            throw new AssertionError();
        }
    }

    /**
     * Returns whether the profile describes sRGB: an RGB matrix/TRC profile whose D50 colorants are
     * within {@code 0.002} of sRGB's and whose tone curves are within half an 8-bit code value of
     * the sRGB curve.
     *
     * @apiNote Decoders use it to skip converting images whose embedded profile is sRGB, which is the case for most of
     * them.
     *
     * @return {@code true} if converting from this profile to {@link #srgb()} would leave 8-bit values unchanged within
     * rounding
     */
    public boolean isSrgb() {
        if (this == BuiltIn.SRGB) {
            return true;
        }
        if (icc == null || icc.colorSpace != IccReader.signature("RGB ") || !ProfileLuts.isMatrixShaper(icc)
                || hasLookupTables(icc)) {
            return false;
        }
        try {
            var colorants = ProfileLuts.colorants(icc);
            var reference = ProfileLuts.colorants(BuiltIn.SRGB.icc);
            for (var i = 0; i < 9; i++) {
                if (Math.abs(colorants[i] - reference[i]) > 0.002) {
                    return false;
                }
            }
            var curve = SrgbReference.CURVE;
            for (var tag : new String[]{"rTRC", "gTRC", "bTRC"}) {
                var trc = icc.curve(IccReader.signature(tag));
                for (var i = 0; i <= 64; i++) {
                    var x = i / 64.0f;
                    if (Math.abs(trc.evaluate(x) - curve.evaluate(x)) > 0.5f / 255.0f) {
                        return false;
                    }
                }
            }
            return true;
        } catch (MediaException exception) {
            return false;
        }
    }

    /**
     * Returns whether a profile has lookup-table tags, which take precedence over its matrix and
     * curves.
     *
     * @param icc the profile
     * @return {@code true} if an {@code A2Bn} or {@code B2An} tag is present
     */
    private static boolean hasLookupTables(IccReader icc) {
        for (var tag : new String[]{"A2B0", "A2B1", "A2B2", "B2A0", "B2A1", "B2A2"}) {
            if (icc.has(IccReader.signature(tag))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the ICC bytes of the profile.
     *
     * @return a copy of the bytes
     * @throws MediaException.Unsupported for a high-dynamic-range profile, which has no ICC form
     */
    public byte[] toByteArray() {
        if (bytes == null) {
            throw new MediaException.Unsupported("icc", "a high-dynamic-range profile has no ICC form");
        }
        return bytes.clone();
    }

    /**
     * {@return the parsed ICC profile, or {@code null} for a high-dynamic-range profile}
     */
    IccReader icc() {
        return icc;
    }

    /**
     * {@return the high-dynamic-range description, or {@code null} for an ICC profile}
     */
    HdrSpec hdr() {
        return hdr;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ColorProfile that)) {
            return false;
        }
        return bytes != null ? Arrays.equals(bytes, that.bytes) : Objects.equals(hdr, that.hdr);
    }

    @Override
    public int hashCode() {
        var h = hash;
        if (h == 0) {
            h = bytes != null ? Arrays.hashCode(bytes) : hdr.hashCode();
            hash = h;
        }
        return h;
    }

    @Override
    public String toString() {
        return "ColorProfile[" + description().orElse("unnamed") + ", " + colorSpace() + ", " + deviceClass() + ", v"
                + version() + "]";
    }

    /**
     * Holds the built-in profiles, created on first use.
     */
    private static final class BuiltIn {
        /**
         * The D65 white chromaticity.
         */
        private static final double[] D65 = {0.3127, 0.3290};

        /**
         * The sRGB profile.
         */
        static final ColorProfile SRGB = cicp("sRGB", ColorPrimaries.BT709, IccWriter.Curve.SRGB);

        /**
         * The Display P3 profile.
         */
        static final ColorProfile DISPLAY_P3 = cicp("Display P3", ColorPrimaries.SMPTE432, IccWriter.Curve.SRGB);

        /**
         * The Adobe RGB (1998) compatible profile.
         */
        static final ColorProfile ADOBE_RGB = new ColorProfile(IccWriter.rgb("Adobe RGB (1998) compatible",
                new double[]{0.64, 0.33}, new double[]{0.21, 0.71}, new double[]{0.15, 0.06}, D65,
                new IccWriter.Curve(-1, 563.0 / 256.0)));

        /**
         * The ProPhoto RGB profile.
         */
        static final ColorProfile PRO_PHOTO = new ColorProfile(IccWriter.rgb("ProPhoto RGB",
                new double[]{0.7347, 0.2653}, new double[]{0.1596, 0.8404}, new double[]{0.0366, 0.0001},
                new double[]{0.3457, 0.3585}, IccWriter.Curve.gamma(1.8)));

        /**
         * The BT.709 profile.
         */
        static final ColorProfile REC_709 = cicp("Rec. ITU-R BT.709", ColorPrimaries.BT709, IccWriter.Curve.BT709);

        /**
         * The BT.2020 profile.
         */
        static final ColorProfile REC_2020 = cicp("Rec. ITU-R BT.2020", ColorPrimaries.BT2020, IccWriter.Curve.BT709);

        /**
         * The gray gamma 2.2 profile.
         */
        static final ColorProfile GRAY_22 = new ColorProfile(IccWriter.gray("Gray gamma 2.2", D65,
                IccWriter.Curve.gamma(2.2)));

        /**
         * Prevents instantiation.
         */
        private BuiltIn() {
            throw new AssertionError();
        }

        /**
         * Builds a matrix-shaper RGB profile from the chromaticities of CICP primaries.
         *
         * @param description the profile description
         * @param primaries the primaries, which define chromaticities
         * @param curve the transfer curve of every channel
         * @return the profile
         */
        private static ColorProfile cicp(String description, ColorPrimaries primaries, IccWriter.Curve curve) {
            var c = Hdr.chromaticities(primaries);
            return new ColorProfile(IccWriter.rgb(description, c[0], c[1], c[2], c[3], curve));
        }
    }
}

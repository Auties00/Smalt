package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.image.Orientation;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Describes a camera raw file from its metadata, without decoding the sensor data: the format and
 * camera, the sensor image and its color filter array, the shot's settings and the embedded
 * previews.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class RawInfo {
    /**
     * The EXIF date and time form, with lenient separators.
     */
    private static final Pattern CAPTURE_TIME =
            Pattern.compile("(\\d{4})[:-](\\d{2})[:-](\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})");

    private final RawFormat format;

    private final String make;

    private final String model;

    private final int width;

    private final int height;

    private final int sensorWidth;

    private final int sensorHeight;

    private final Orientation orientation;

    private final int bitsPerSample;

    /**
     * The color filter array of the default rendering's area, or {@code null}.
     */
    private final CfaPattern cfa;

    private final String compression;

    /**
     * The feature keeping the sensor data from decoding, or {@code null}.
     */
    private final String unsupported;

    /**
     * Whether the decoder knows the camera's colors.
     */
    private final boolean calibrated;

    private final long iso;

    private final double exposureTime;

    private final double fNumber;

    private final double focalLength;

    private final String lens;

    private final String captureTime;

    private final String dngVersion;

    private final List<RawPreview> previews;

    private final CfaLayout layout;

    private final List<String> profiles;

    /**
     * Whether the file holds an enhanced image that decodes.
     */
    private final boolean enhanced;

    /**
     * Constructs a description; {@link RawDecoder} creates instances.
     *
     * @param format the raw file format
     * @param make the camera maker, or {@code null}
     * @param model the camera model, or {@code null}
     * @param width the rendered width, or {@code 0}
     * @param height the rendered height, or {@code 0}
     * @param sensorWidth the stored sensor width, or {@code 0}
     * @param sensorHeight the stored sensor height, or {@code 0}
     * @param orientation the display orientation
     * @param bitsPerSample the stored bits per sample, or {@code 0}
     * @param cfa the color filter array of the rendered area, or {@code null}
     * @param compression the compression, or {@code null}
     * @param unsupported the feature keeping the sensor data from decoding, or {@code null}
     * @param calibrated whether the decoder knows the camera's colors
     * @param iso the ISO speed, or {@code 0}
     * @param exposureTime the exposure time in seconds, or {@code 0}
     * @param fNumber the f-number, or {@code 0}
     * @param focalLength the focal length in millimeters, or {@code 0}
     * @param lens the lens model, or {@code null}
     * @param captureTime the capture time, or {@code null}
     * @param dngVersion the DNG version, or {@code null}
     * @param previews the previews, largest first
     * @param layout the spatial layout of the color filter array
     * @param profiles the names of the camera profiles, the primary one first
     * @param enhanced whether the file holds an enhanced image that decodes
     */
    RawInfo(RawFormat format, String make, String model, int width, int height, int sensorWidth, int sensorHeight,
            Orientation orientation, int bitsPerSample, CfaPattern cfa, String compression, String unsupported,
            boolean calibrated, long iso, double exposureTime, double fNumber, double focalLength, String lens,
            String captureTime, String dngVersion, List<RawPreview> previews, CfaLayout layout, List<String> profiles,
            boolean enhanced) {
        this.format = Objects.requireNonNull(format, "format");
        this.make = make;
        this.model = model;
        this.width = width;
        this.height = height;
        this.sensorWidth = sensorWidth;
        this.sensorHeight = sensorHeight;
        this.orientation = Objects.requireNonNull(orientation, "orientation");
        this.bitsPerSample = bitsPerSample;
        this.cfa = cfa;
        this.compression = compression;
        this.unsupported = unsupported;
        this.calibrated = calibrated;
        this.iso = iso;
        this.exposureTime = exposureTime;
        this.fNumber = fNumber;
        this.focalLength = focalLength;
        this.lens = lens;
        this.captureTime = captureTime;
        this.dngVersion = dngVersion;
        this.previews = List.copyOf(previews);
        this.layout = Objects.requireNonNull(layout, "layout");
        this.profiles = List.copyOf(profiles);
        this.enhanced = enhanced;
    }

    /**
     * {@return the raw file format}
     */
    public RawFormat format() {
        return format;
    }

    /**
     * {@return the camera maker, such as {@code "Canon"}, or empty when the file does not name it}
     */
    public Optional<String> make() {
        return Optional.ofNullable(make);
    }

    /**
     * {@return the camera model, such as {@code "Canon EOS 5D Mark III"}, or empty when the file does
     * not name it}
     */
    public Optional<String> model() {
        return Optional.ofNullable(model);
    }

    /**
     * {@return whether the sensor data decodes: whether
     * {@link RawDecoder#decode(com.github.auties00.smalt.io.MediaSource, RawDecoderOptions)} can
     * render the sensor image}
     */
    public boolean isDecodable() {
        return unsupported == null && width > 0;
    }

    /**
     * {@return the feature the decoder lacks to decode the sensor data, or empty when it decodes}
     *
     * <p>A four-color sensor whose file carries no color matrices for its colors is reported here,
     * since the default rendering cannot render it; it still renders with a twelve-value
     * {@link RawDecoderOptions#colorMatrix()}, and {@link RawDecoder#decodeRaw} returns its samples.
     */
    public Optional<String> unsupportedFeature() {
        return Optional.ofNullable(unsupported);
    }

    /**
     * {@return the width of the default rendering (the default crop, scaled to square pixels) in
     * pixels, before the orientation is applied, or 0 when the sensor data does not decode}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the height of the default rendering in pixels, before the orientation is applied, or 0
     * when the sensor data does not decode}
     */
    public int height() {
        return height;
    }

    /**
     * {@return the stored width of the sensor image, masked borders included, or 0 when the sensor
     * data does not decode}
     */
    public int sensorWidth() {
        return sensorWidth;
    }

    /**
     * {@return the stored height of the sensor image, masked borders included, or 0 when the sensor
     * data does not decode}
     */
    public int sensorHeight() {
        return sensorHeight;
    }

    /**
     * {@return the display orientation; {@link Orientation#NORMAL} when the file declares none}
     */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * {@return the number of bits of each stored sensor sample, such as 12 or 14, or 0 when unknown}
     */
    public int bitsPerSample() {
        return bitsPerSample;
    }

    /**
     * {@return the color filter array of the default rendering's area, whose top-left photosite is the
     * rendering's top-left pixel, or empty for linear raw, monochrome or undecodable images}
     */
    public Optional<CfaPattern> cfaPattern() {
        return Optional.ofNullable(cfa);
    }

    /**
     * {@return the spatial layout of the photosites of the color filter array of the default
     * rendering's area, aligned as {@link #cfaPattern()} is; {@link CfaLayout#RECTANGULAR} for most
     * sensors and for linear raw, monochrome or undecodable images}
     */
    public CfaLayout cfaLayout() {
        return layout;
    }

    /**
     * {@return the names of the camera profiles a DNG file holds, the primary one first, then those of
     * {@code ExtraCameraProfiles} in file order, an unnamed profile as an empty string; unmodifiable}
     *
     * <p>Every DNG file has a primary profile; other files list one unnamed profile.
     * {@link RawDecoderOptions.Builder#cameraProfile(String)} selects one by name.
     */
    public List<String> cameraProfiles() {
        return profiles;
    }

    /**
     * {@return whether the file is a DNG file holding an enhanced image (DNG 1.5) that decodes, which
     * {@link RawDecoderOptions#enhancedImage()} renders instead of the raw image}
     */
    public boolean hasEnhancedImage() {
        return enhanced;
    }

    /**
     * {@return a description of the compression of the sensor data, such as {@code "lossless JPEG"},
     * or empty when unknown}
     */
    public Optional<String> compression() {
        return Optional.ofNullable(compression);
    }

    /**
     * {@return whether the decoder knows the camera's colors}
     *
     * <p>The colors come from the file (DNG color matrices, or a camera to RGB matrix a vendor
     * format embeds) or, for some camera models whose files carry none, from matrices the decoder
     * derives from the model's measured spectral sensitivities. Other files are rendered with a
     * generic camera matrix, so their colors are approximate unless a matrix is supplied with
     * {@link RawDecoderOptions.Builder#colorMatrix(double[])}.
     */
    public boolean isColorCalibrated() {
        return calibrated;
    }

    /**
     * {@return the ISO speed of the shot, or empty when the file does not record it}
     */
    public OptionalLong iso() {
        return iso > 0 ? OptionalLong.of(iso) : OptionalLong.empty();
    }

    /**
     * {@return the exposure time of the shot in seconds, or empty when the file does not record it}
     */
    public OptionalDouble exposureTime() {
        return exposureTime > 0 ? OptionalDouble.of(exposureTime) : OptionalDouble.empty();
    }

    /**
     * {@return the f-number of the shot, or empty when the file does not record it}
     */
    public OptionalDouble fNumber() {
        return fNumber > 0 ? OptionalDouble.of(fNumber) : OptionalDouble.empty();
    }

    /**
     * {@return the focal length of the shot in millimeters, or empty when the file does not record it}
     */
    public OptionalDouble focalLength() {
        return focalLength > 0 ? OptionalDouble.of(focalLength) : OptionalDouble.empty();
    }

    /**
     * {@return the lens model, or empty when the file does not name it}
     */
    public Optional<String> lens() {
        return Optional.ofNullable(lens);
    }

    /**
     * {@return the capture time as the file records it, in EXIF {@code DateTimeOriginal} text form
     * such as {@code "2017:01:05 13:52:55"}, or empty when the file does not record it}
     */
    public Optional<String> captureTimeText() {
        return Optional.ofNullable(captureTime);
    }

    /**
     * Returns the capture time as a local date and time, without the time zone, which the EXIF
     * {@code DateTimeOriginal} field does not carry.
     *
     * <p>The text is read leniently: the date may separate its fields with colons or hyphens and the
     * time may follow a space or a {@code T}; anything after the seconds (fractions, padding) is
     * ignored. Text that does not name a valid date and time, such as the placeholder
     * {@code "0000:00:00 00:00:00"}, gives an empty result; {@link #captureTimeText()} still returns it.
     *
     * @return the time, or empty when the file does not record a valid one
     */
    public Optional<LocalDateTime> captureTime() {
        if (captureTime == null) {
            return Optional.empty();
        }
        var matcher = CAPTURE_TIME.matcher(captureTime.strip());
        if (!matcher.lookingAt()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.of(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)),
                    Integer.parseInt(matcher.group(6))));
        } catch (DateTimeException _) {
            return Optional.empty();
        }
    }

    /**
     * {@return the DNG version of a DNG file, such as {@code "1.4.0.0"}, or empty for other formats}
     */
    public Optional<String> dngVersion() {
        return Optional.ofNullable(dngVersion);
    }

    /**
     * {@return the JPEG previews the file embeds, largest first; unmodifiable}
     */
    public List<RawPreview> previews() {
        return previews;
    }

    @Override
    public String toString() {
        return "RawInfo[format=" + format + ", make=" + make + ", model=" + model + ", size=" + width + "x" + height
                + ", sensor=" + sensorWidth + "x" + sensorHeight + ", orientation=" + orientation + ", bits="
                + bitsPerSample + ", cfa=" + cfa + ", compression=" + compression
                + (unsupported != null ? ", unsupported=" + unsupported : "") + ", calibrated=" + calibrated
                + ", previews=" + previews.size() + "]";
    }
}

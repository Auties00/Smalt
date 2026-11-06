package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.internal.tiff.ExifBlocks;
import com.github.auties00.smalt.internal.tiff.TiffExif;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.metadata.XmpValue;

import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * Applies the metadata of {@link ImageEncoderOptions} for the image codec providers: the EXIF block and
 * XMP packet with the orientation recorded in them, the orientation to turn a picture upright with in a
 * format that records none, and the conversion to sRGB of a picture whose profile a format cannot embed.
 *
 * <p>This class is thread-safe.
 */
public final class EncodeMetadata {
    /**
     * The {@code tiff:Orientation} property name.
     */
    private static final String ORIENTATION = "Orientation";

    private EncodeMetadata() {
        throw new AssertionError();
    }

    /**
     * {@return the orientation the options mean the picture to display with: the explicit one, or the one
     * of the EXIF block, or {@link Orientation#NORMAL}}
     *
     * @param options the encoder options of the file
     */
    public static Orientation displayOrientation(ImageEncoderOptions options) {
        var explicit = options.orientation();
        if (explicit.isPresent()) {
            return explicit.get();
        }
        return options.exif().map(exif -> TiffExif.orientation(exif, 0)).orElse(Orientation.NORMAL);
    }

    /**
     * {@return the EXIF block a format that records the orientation in EXIF writes: the options' block with
     * the explicit orientation patched in, a block holding only the orientation when the options have no
     * block and the orientation is not {@link Orientation#NORMAL}, or empty}
     *
     * @param options the encoder options of the file
     */
    public static Optional<byte[]> exif(ImageEncoderOptions options) {
        var exif = options.exif().orElse(null);
        var orientation = options.orientation().orElse(null);
        if (orientation == null) {
            return Optional.ofNullable(exif);
        }
        if (exif == null) {
            return orientation == Orientation.NORMAL ? Optional.empty()
                    : Optional.of(ExifBlocks.withOrientation(new byte[0], orientation));
        }
        return Optional.of(ExifBlocks.withOrientation(exif, orientation));
    }

    /**
     * {@return the EXIF block of the options with an orientation recorded in it, or empty when the options
     * have no block}
     *
     * @param options the encoder options of the file
     * @param orientation the orientation the file displays its pixels with
     */
    public static Optional<byte[]> exif(ImageEncoderOptions options, Orientation orientation) {
        return options.exif().map(exif -> ExifBlocks.withOrientation(exif, orientation));
    }

    /**
     * {@return the XMP packet of the options, its {@code tiff:Orientation} property set to the explicit
     * orientation when the packet has one, or empty}
     *
     * @param options the encoder options of the file
     */
    public static Optional<byte[]> xmp(ImageEncoderOptions options) {
        var orientation = options.orientation().orElse(null);
        return orientation == null ? options.xmp() : options.xmp().map(xmp -> withOrientation(xmp, orientation));
    }

    /**
     * {@return the XMP packet of the options with its {@code tiff:Orientation} property, when it has one, set
     * to an orientation, or empty}
     *
     * @param options the encoder options of the file
     * @param orientation the orientation the file displays its pixels with
     */
    public static Optional<byte[]> xmp(ImageEncoderOptions options, Orientation orientation) {
        return options.xmp().map(xmp -> withOrientation(xmp, orientation));
    }

    /**
     * Sets the {@code tiff:Orientation} property of a packet that has one.
     *
     * @param xmp the serialized packet
     * @param orientation the orientation to record
     * @return the packet, serialized again when it changed; {@code xmp} itself when it does not parse,
     * has no such property or already holds the orientation
     */
    public static byte[] withOrientation(byte[] xmp, Orientation orientation) {
        XmpPacket packet;
        try {
            packet = XmpPacket.parse(xmp);
        } catch (MediaException exception) {
            return xmp;
        }
        var current = packet.text(XmpPacket.TIFF, ORIENTATION);
        var code = Integer.toString(orientation.exifCode());
        if (current.isEmpty() || current.get().trim().equals(code)) {
            return xmp;
        }
        return packet.with(XmpPacket.TIFF, ORIENTATION, XmpValue.text(code)).toBytes();
    }

    /**
     * Turns a picture upright for a still format that records no orientation.
     *
     * @param picture the stored pixels
     * @param options the encoder options of the file
     * @return the picture as displayed, {@code picture} itself when the orientation is
     * {@link Orientation#NORMAL}
     */
    public static Picture upright(Picture picture, ImageEncoderOptions options) {
        return ImageOps.orient(picture, displayOrientation(options));
    }

    /**
     * Rejects an orientation an animation format cannot record.
     *
     * @param options the encoder options of the file
     * @param format the name of the format, for the message
     * @throws MediaException.Unsupported if the options mean the frames to display turned
     */
    public static void requireUpright(ImageEncoderOptions options, String format) {
        var orientation = displayOrientation(options);
        if (orientation != Orientation.NORMAL) {
            throw new MediaException.Unsupported(format, "an animation cannot record the orientation " + orientation);
        }
    }

    /**
     * Checks that a profile can describe a picture.
     *
     * @param picture the picture to encode
     * @param profile the profile of its pixels
     * @throws IllegalArgumentException if a channel picture's color channels are not as many as the
     * profile's, or another picture has a profile that is neither of one nor of three channels
     */
    public static void checkProfile(Picture picture, ColorProfile profile) {
        var channels = profile.channels();
        if (picture instanceof ChannelPicture channelPicture) {
            var colors = channelPicture.layout().colorChannels();
            if (colors != channels) {
                throw new IllegalArgumentException("a " + profile.colorSpace() + " color profile does not describe a"
                        + " picture of " + colors + " color channels");
            }
        } else if (channels != 1 && channels != 3) {
            throw new IllegalArgumentException("a " + profile.colorSpace() + " color profile does not describe a"
                    + " picture of RGB or gray pixels");
        }
    }

    /**
     * {@return whether a picture holds CMYK inks}
     *
     * @param picture the picture to encode
     */
    public static boolean isCmyk(Picture picture) {
        return picture instanceof ChannelPicture channels && channels.layout().colorModel() == ColorModel.CMYK;
    }

    /**
     * {@return the color model a format that keeps channel pictures as they are codes a picture in: the
     * layout's model for a channel picture, RGB otherwise}
     *
     * @param picture the picture to encode
     */
    public static ColorModel codedModel(Picture picture) {
        return picture instanceof ChannelPicture channels ? channels.layout().colorModel() : ColorModel.RGB;
    }

    /**
     * {@return whether a profile describes samples of a color model: an RGB profile RGB samples, a gray
     * profile gray ones and a CMYK profile inks}
     *
     * @param profile the color profile of the pixels
     * @param model the color model of the coded samples
     */
    public static boolean describes(ColorProfile profile, ColorModel model) {
        return switch (profile.colorSpace()) {
            case RGB -> model == ColorModel.RGB;
            case GRAY -> model == ColorModel.GRAY;
            case CMYK -> model == ColorModel.CMYK;
            default -> false;
        };
    }

    /**
     * Holds a picture ready to encode and the profile its format records for it.
     *
     * @param picture the picture, converted to sRGB when its profile cannot be recorded
     * @param profile the profile the file records, or {@code null} for none
     */
    public record Prepared(Picture picture, ColorProfile profile) {
    }

    /**
     * Decides how a format honors the color profile of the options: it records the profile when it can,
     * and otherwise holds the picture converted to sRGB.
     *
     * @param picture the picture to encode
     * @param options the encoder options of the file
     * @param records whether the format records a profile for a picture
     * @return the picture and the profile to record
     * @throws IllegalArgumentException if the profile does not describe the picture, as
     * {@link #checkProfile(Picture, ColorProfile)} checks
     */
    public static Prepared color(Picture picture, ImageEncoderOptions options,
                                 BiPredicate<ColorProfile, Picture> records) {
        var profile = options.colorProfile().orElse(null);
        if (profile == null) {
            return new Prepared(picture, null);
        }
        checkProfile(picture, profile);
        if (records.test(profile, picture)) {
            return new Prepared(picture, profile);
        }
        return new Prepared(toSrgb(picture, profile), null);
    }

    /**
     * {@return whether a profile is an RGB profile and a picture does not hold inks, the condition of the
     * formats that record only RGB profiles}
     *
     * @param profile the color profile of the pixels
     * @param picture the picture to encode
     */
    public static boolean rgbOnly(ColorProfile profile, Picture picture) {
        return profile.colorSpace() == ProfileColorSpace.RGB && !isCmyk(picture);
    }

    /**
     * Converts a picture to sRGB through its profile, for a format that cannot embed the profile.
     *
     * @param picture the picture, whose pixels are in {@code profile}
     * @param profile the profile, checked with {@link #checkProfile(Picture, ColorProfile)}
     * @return the converted picture, an RGBA picture of the input's depth, or {@code picture} itself when the
     * profile is sRGB or cannot be applied
     */
    public static Picture toSrgb(Picture picture, ColorProfile profile) {
        var image = new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, picture.width(), picture.height(),
                Optional.empty(), Optional.empty(), Optional.of(profile));
        return ImageOps.toSrgb(image).picture();
    }

    /**
     * {@return the transform that converts the frames of an animation from a profile to sRGB, or
     * {@code null} when the profile is sRGB}
     *
     * @param profile the profile of the frames, RGB or gray
     * @throws IllegalArgumentException if the profile is neither RGB nor gray
     * @throws MediaException.Unsupported if the profile cannot be used as a source
     */
    public static ColorTransform frameTransform(ColorProfile profile) {
        if (profile.isSrgb()) {
            return null;
        }
        if (profile.channels() != 1 && profile.channels() != 3) {
            throw new IllegalArgumentException("a " + profile.colorSpace() + " color profile does not describe an"
                    + " animation frame");
        }
        return ColorTransform.create(profile, ColorProfile.srgb());
    }

    /**
     * Holds how an animation format honors the color profile of its frames.
     *
     * @param profile the profile the file records, or {@code null} for none
     * @param transform the conversion of every frame to sRGB, or {@code null} to write the frames as given
     */
    public record AnimationColor(ColorProfile profile, ColorTransform transform) {
        /**
         * {@return a writer that converts the frames as {@link #transform()} says}
         *
         * @param writer the writer of the format
         */
        public AnimationWriter wrap(AnimationWriter writer) {
            return transform == null ? writer : new SrgbAnimationWriter(writer, transform);
        }
    }

    /**
     * Decides how an animation format honors the color profile of the options: it records an RGB profile
     * when it can, and otherwise converts every frame to sRGB.
     *
     * @param options the encoder options of the file
     * @param recordsRgb whether the format records RGB profiles
     * @return the profile to record and the frame conversion
     * @throws IllegalArgumentException if the profile is neither RGB nor gray
     * @throws MediaException.Unsupported if the profile cannot be used as a source
     */
    public static AnimationColor animationColor(ImageEncoderOptions options, boolean recordsRgb) {
        var profile = options.colorProfile().orElse(null);
        if (profile == null) {
            return new AnimationColor(null, null);
        }
        if (recordsRgb && profile.colorSpace() == ProfileColorSpace.RGB) {
            return new AnimationColor(profile, null);
        }
        return new AnimationColor(null, frameTransform(profile));
    }

    /**
     * Converts the frames of an animation to sRGB into one reused picture, so that a long animation
     * allocates no picture per frame.
     *
     * <p>The picture {@link #convert(RgbaPicture)} returns holds the converted frame until the next call;
     * a caller hands it to a writer, which copies what it keeps, before converting the next frame. Instances
     * are not thread-safe.
     */
    public static final class FrameConverter {
        /**
         * The work per pixel of the conversion, weighed against {@link ParallelBands#MIN_PARALLEL_WORK}.
         */
        private static final long WORK = 24;

        /**
         * The conversion, or {@code null} to return the frames as given.
         */
        private final ColorTransform transform;

        /**
         * The picture the converted frames are written into, or {@code null} before the first frame.
         */
        private RgbaPicture converted;

        /**
         * Creates a converter.
         *
         * @param transform the transform of {@link #frameTransform(ColorProfile)}, or {@code null} to return
         * the frames as given
         */
        public FrameConverter(ColorTransform transform) {
            this.transform = transform;
        }

        /**
         * {@return whether the converter changes the frames}
         */
        public boolean converts() {
            return transform != null;
        }

        /**
         * Converts a frame to sRGB.
         *
         * @param frame the frame to convert
         * @return the converted frame, valid until the next call, or {@code frame} itself when the converter
         * has no transform
         */
        public RgbaPicture convert(RgbaPicture frame) {
            if (transform == null) {
                return frame;
            }
            var width = frame.width();
            var height = frame.height();
            if (converted == null || converted.width() != width || converted.height() != height
                    || converted.hasAlpha() != frame.hasAlpha()) {
                converted = RgbaPicture.allocate(width, height, frame.hasAlpha());
            }
            var out = converted;
            var src = frame.pixels();
            var dst = out.pixels();
            try (var _ = ParallelBands.operation()) {
                var bands = ParallelBands.bands(height, WORK * width * height);
                ParallelBands.run(bands, band -> {
                    var top = ParallelBands.start(band, bands, height);
                    var bottom = ParallelBands.start(band + 1, bands, height);
                    for (var row = top; row < bottom; row++) {
                        var d = row * out.stride();
                        System.arraycopy(src, row * frame.stride(), dst, d, 4 * width);
                        transform.transform(dst, d, 4, dst, d, 4, width);
                    }
                });
            }
            return out;
        }
    }
}

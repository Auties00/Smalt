package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.anim.SeekableAnimationReader;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;
import java.util.Optional;

/**
 * Decodes PNG images and APNG animations (PNG 3rd edition, W3C 2025) to packed RGBA8, or 16-bit
 * images to packed 16-bit RGBA on request.
 *
 * <p>Every color type (grayscale, truecolor, indexed color, grayscale with alpha, truecolor
 * with alpha) and every legal bit depth (1, 2, 4, 8, 16) is supported, with and without Adam7
 * interlacing and {@code tRNS} transparency. Samples are converted to 8 bits:
 * <ul>
 *   <li>grayscale samples of 1, 2 and 4 bits are scaled by bit replication, which equals
 *       {@code v * 255 / (2^depth - 1)};</li>
 *   <li>16-bit samples are reduced with exact rounding, {@code (v * 255 + 32767) / 65535};</li>
 *   <li>a {@code tRNS} key is compared with the samples at the image's full bit depth, before
 *       reduction.</li>
 * </ul>
 * With {@link ImageDecoderOptions#highBitDepth()} set, {@link #decode(MediaSource,
 * ImageDecoderOptions)} returns a 16-bit image as an {@link Rgba16Picture} holding the stored
 * samples instead; reducing it with
 * {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(Rgba16Picture)} gives exactly the
 * default 8-bit output. Animations are always 8-bit.
 * Pixels are returned as stored, without color management unless
 * {@link ImageDecoderOptions#convertToSrgb()} asks for sRGB: {@link DecodedImage#colorProfile()}
 * describes them from the {@code cICP}, {@code iCCP}, {@code sRGB}, {@code gAMA} and {@code cHRM}
 * chunks, a high-dynamic-range {@code cICP} profile carrying the {@code mDCV} and {@code cLLI}
 * metadata in its {@link com.github.auties00.smalt.image.color.ColorProfile#colorInfo()}, and an
 * {@code iCCP} profile is also returned in {@link DecodedImage#iccProfile()}. An
 * {@code eXIf} chunk before the image data supplies {@link DecodedImage#exif()} and the
 * orientation, and the first {@code iTXt} chunk with the keyword {@code XML:com.adobe.xmp}
 * supplies {@link DecodedImage#xmp()}. The first {@code pHYs} chunk before the image data supplies
 * {@link DecodedImage#density()}, in dots per meter or as an aspect ratio; one of another length than 9 bytes,
 * with a bad CRC, a zero value, a value above {@code 2^31 - 1} or an unknown unit gives none.
 *
 * <p>A CRC-32 mismatch in a critical chunk ({@code IHDR}, {@code PLTE}, {@code IDAT},
 * {@code IEND}) is malformed, while an ancillary chunk with a bad CRC is skipped; an unknown
 * critical chunk is unsupported; a palette index past the palette is malformed. The image data
 * is inflated only to the size of the scanlines the header declares, which is checked against the
 * {@link MediaLimits} before any allocation, so a decompression bomb costs no more than the
 * declared image. Of the chunks after the chunk in which the image data ends,
 * {@link #decode(MediaSource, ImageDecoderOptions)} reads only those of a datastream without
 * animation frames, and only to find an XMP packet not found before the image data; a missing
 * {@code IEND} or a corrupt chunk there is tolerated.
 *
 * <p>This class is thread-safe.
 */
public final class PngDecoder {
    /**
     * Prevents instantiation.
     */
    private PngDecoder() {
        throw new AssertionError();
    }

    /**
     * Reads the header chunks of a PNG datastream without decoding pixels.
     *
     * <p>The chunks up to the first {@code IDAT} are read. {@link ImageInfo#animated()} is
     * {@code true} when a valid {@code acTL} declares more than one frame, and
     * {@link ImageInfo#frameCount()} reports the declared frame count (1 for a static image).
     * {@link ImageInfo#hasAlpha()} is {@code true} when the color type has an alpha channel or a
     * valid {@code tRNS} chunk is present. The size is reported as declared, without checking it
     * against any limit.
     *
     * @param source the input, read from offset zero
     * @return the image summary
     * @throws MediaException.Malformed if the datastream is not a valid PNG up to its image data
     * @throws MediaException.Unsupported if an unknown critical chunk precedes the image data
     * @throws MediaException.LimitExceeded if probing runs out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed("PNG datastream", () -> {
                var reader = new PngReader(source, MediaLimits.DEFAULT, false);
                reader.readPrelude();
                var header = reader.header();
                var frames = reader.animated() ? reader.animationFrames() : 1;
                return new ImageInfo(ImageFormat.PNG, header.width(), header.height(), reader.hasAlpha(), frames > 1,
                        frames, reader.orientation());
            });
        }
    }

    /**
     * Decodes a PNG image at full size under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input
     * @return the decoded image
     * @throws MediaException.Malformed if the datastream is invalid, truncated or corrupt
     * @throws MediaException.Unsupported if an unknown critical chunk precedes the image data
     * @throws MediaException.LimitExceeded if the image exceeds the limits of {@code options} or
     * decoding runs out of memory
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the static image of a PNG datastream.
     *
     * <p>For an APNG this is the default image (the {@code IDAT} data), whether or not it is the
     * first frame of the animation. The result is an {@link RgbaPicture} of the full image size
     * (the reduced-size hint of {@code options} is ignored) whose
     * {@link RgbaPicture#hasAlpha()} is {@code true} exactly when the color type has an alpha
     * channel or a valid {@code tRNS} chunk is present; for a 16-bit image decoded with
     * {@link ImageDecoderOptions#highBitDepth()} set it is an {@link Rgba16Picture} instead.
     *
     * @param source the input, read from offset zero
     * @param options the limits and the bit depth choice; the size hint is ignored
     * @return the decoded image
     * @throws MediaException.Malformed if the datastream is invalid, truncated or corrupt
     * @throws MediaException.Unsupported if an unknown critical chunk precedes the image data
     * @throws MediaException.LimitExceeded if the image exceeds the limits of {@code options} or
     * decoding runs out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return ImageRegions.decode(options, null, whole -> decodeWhole(source, whole));
    }

    /**
     * Decodes the whole image of a datastream.
     *
     * @param source the datastream
     * @param options the decoding options; the region is ignored
     * @return the decoded image
     */
    private static DecodedImage decodeWhole(MediaSource source, ImageDecoderOptions options) {
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed("PNG datastream", () -> {
                var limits = options.limits();
                var reader = new PngReader(source, limits, true);
                reader.releaseWhileReading();
                reader.readPrelude();
                var header = reader.header();
                var width = header.width();
                var height = header.height();
                LimitChecks.checkPicture(limits, width, height);
                var converter = PngRowConverter.of(header, reader.palette(), reader.paletteSize(), reader.transparencyKey());
                Picture picture;
                if (options.highBitDepth() && header.bitDepth() == 16) {
                    LimitChecks.checkAllocation(limits, (long) width * height * 8);
                    var wide = Rgba16Picture.allocate(width, height, reader.hasAlpha());
                    try (var pixels = new PngPixelDecoder(header, converter, PngFilterKernel.INSTANCE)) {
                        pixels.decodeWide(reader, width, height, wide.pixels(), 0, width * 4);
                    }
                    picture = wide;
                } else {
                    LimitChecks.checkAllocation(limits, (long) width * height * 4);
                    var rgba = RgbaPicture.allocate(width, height, reader.hasAlpha());
                    try (var pixels = new PngPixelDecoder(header, converter, PngFilterKernel.INSTANCE)) {
                        pixels.decode(reader, width, height, rgba.pixels(), 0, width * 4);
                    }
                    picture = rgba;
                }
                reader.finishImageData();
                reader.readTrailingMetadata();
                var image = new DecodedImage(picture, ImageFormat.PNG, reader.orientation(), width, height,
                        Optional.ofNullable(reader.iccProfile()), Optional.ofNullable(reader.exif()),
                        Optional.ofNullable(reader.xmp()), Optional.ofNullable(reader.colorProfile()),
                        Optional.ofNullable(reader.density()));
                return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
            });
        }
    }

    /**
     * Opens a PNG image as an animation under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input
     * @return the animation reader
     * @throws MediaException.Malformed if the datastream or its APNG chunks before the image
     * data are invalid
     * @throws MediaException.Unsupported if an unknown critical chunk precedes the image data
     * @throws MediaException.LimitExceeded if the canvas or declared frame count exceeds the
     * limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     * @see #openAnimation(MediaSource, ImageDecoderOptions)
     */
    public static AnimationReader openAnimation(MediaSource source) {
        return openAnimation(source, ImageDecoderOptions.defaults());
    }

    /**
     * Opens the frames of an APNG, or of a static PNG as a one-frame animation.
     *
     * <p>Frames are decoded on demand and composited onto a canvas of the {@code IHDR} size with
     * the APNG blend and dispose operations. For an APNG, {@link AnimationReader#frameCount()} and
     * {@link AnimationReader#loopCount()} report the {@code acTL} frame count and play count (0
     * meaning forever), and each frame's {@link AnimationFrame#durationMillis()} is its
     * {@code fcTL} delay fraction {@code num / den} seconds (a zero denominator meaning 100),
     * converted to milliseconds rounding halves up. When the first {@code fcTL} follows
     * {@code IDAT}, the default image is not part of the animation. A static PNG yields one frame
     * with duration 0 and loop count 1.
     *
     * <p>Before returning, the whole APNG chunk structure is validated without decoding pixels:
     * {@code fcTL}/{@code fdAT} sequence numbers, frame regions inside the canvas, a data chunk
     * for every frame, and a frame count equal to the {@code acTL} count. Structural errors, which
     * PNG 3rd edition 11.3.6 says must be treated as errors, are therefore reported here, before
     * any frame is shown. Errors inside the image data of a frame (truncated, corrupt, or holding
     * more scanlines than the frame) raise {@link MediaException.Malformed} from
     * {@link AnimationReader#read()} before that frame is exposed.
     *
     * <p>The returned reader can seek: a seek backwards composites again from the first frame.
     * It reads {@code source} lazily, so the source must stay open until the
     * reader is closed. The reader holds native zlib state; close it when done.
     *
     * @param source the input, read from offset zero
     * @param options the limits; the size hint is ignored
     * @return the animation reader
     * @throws MediaException.Malformed if the datastream or its APNG chunks before the image
     * data are invalid
     * @throws MediaException.Unsupported if an unknown critical chunk precedes the image data
     * @throws MediaException.LimitExceeded if the canvas or declared frame count exceeds the
     * limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     */
    public static AnimationReader openAnimation(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return Guards.malformed("PNG datastream", () -> {
            var scan = new PngReader(source, options.limits(), false);
            scan.readPrelude();
            if (scan.animated()) {
                scan.validateAnimation();
            }
            return SeekableAnimationReader.of(() -> {
                var reader = new PngReader(source, options.limits(), false);
                reader.readPrelude();
                return new ApngAnimationReader(reader, options.limits());
            });
        });
    }
}

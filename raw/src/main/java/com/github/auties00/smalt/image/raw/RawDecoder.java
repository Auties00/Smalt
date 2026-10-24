/* Ports the Digital Negative (DNG) Specification 1.7.1.0, Adobe. This product includes DNG technology under license
   by Adobe. */
package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.raw.internal.RawDecoding;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;
import java.util.Optional;

/**
 * Decodes camera raw images: DNG, and the vendor formats of Canon (CR2; CR3 metadata and
 * previews), Nikon (NEF), Sony (ARW), Fujifilm (RAF), Olympus (ORF), Panasonic (RW2) and Pentax
 * (PEF), as far as each is supported.
 *
 * <p>Decoding renders the sensor image the way a raw converter does: it reads and decompresses the
 * sensor data, applies the DNG opcode lists, linearizes the samples, demosaics them, white balances
 * them, maps the camera's colors to the output color space through the camera matrices, and
 * applies the exposure and tone curve; {@link RawDecoderOptions} configures each step. The result
 * is an 8-bit sRGB {@linkplain com.github.auties00.smalt.image.RgbaPicture picture} by default, or a
 * {@linkplain com.github.auties00.smalt.image.Rgba16Picture 16-bit one}, tagged with the output
 * color space's profile. Thumbnails come from the embedded JPEG previews when a size hint allows
 * it. {@link #decodeRaw(MediaSource, RawDecoderOptions)} returns the linear sensor image itself, for
 * callers that render it on their own.
 *
 * <p>The picture is returned as stored: {@link DecodedImage#orientation()} reports the rotation
 * that displays it upright. Its EXIF metadata is returned too, without the maker note.
 *
 * <p>This class is thread-safe. Every method reads the source through positional reads, so calls
 * on different sources may run concurrently; large images decode on several threads of the shared
 * pool.
 *
 * @implNote This implementation follows the Adobe DNG specification 1.7.1 for DNG files, ITU-T T.81
 * annex H for lossless JPEG, and public descriptions of the vendor formats.
 */
public final class RawDecoder {
    /**
     * Prevents instantiation.
     */
    private RawDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes a raw image as the image registry does, without decoding the sensor data.
     *
     * <p>The size is that of the default rendering; for a file whose sensor data does not decode
     * but which embeds a preview, the size of the largest preview.
     *
     * @param source the input
     * @return the summary, with format {@link ImageFormat#DNG} or {@link ImageFormat#RAW}
     * @throws MediaException.Malformed if the structure is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format, or neither
     * its sensor data nor a preview decodes
     * @throws MediaException.LimitExceeded if the structure exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        var info = info(source);
        var format = info.format() == RawFormat.DNG ? ImageFormat.DNG : ImageFormat.RAW;
        if (info.isDecodable()) {
            return new ImageInfo(format, info.width(), info.height(), false, false, 1, info.orientation());
        }
        if (!info.previews().isEmpty()) {
            var preview = info.previews().getFirst();
            return new ImageInfo(format, preview.width(), preview.height(), false, false, 1, info.orientation());
        }
        throw new MediaException.Unsupported(info.format().extension(), info.unsupportedFeature().orElse(
                "the " + info.format().extension() + " sensor data cannot be decoded"));
    }

    /**
     * Describes a raw file under the {@linkplain MediaLimits#DEFAULT default limits}, without
     * decoding its sensor data.
     *
     * @param source the input
     * @return the description
     * @throws MediaException.Malformed if the structure is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format
     * @throws MediaException.LimitExceeded if the structure exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static RawInfo info(MediaSource source) {
        return info(source, MediaLimits.DEFAULT);
    }

    /**
     * Describes a raw file without decoding its sensor data.
     *
     * @param source the input
     * @param limits the bounds of the structures read
     * @return the description
     * @throws MediaException.Malformed if the structure is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format
     * @throws MediaException.LimitExceeded if the structure exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static RawInfo info(MediaSource source, MediaLimits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        var facts = RawDecoding.facts(RawDecoding.open(source, limits));
        return new RawInfo(facts.format(), facts.make(), facts.model(), facts.width(), facts.height(),
                facts.sensorWidth(), facts.sensorHeight(), facts.orientation(), facts.bitsPerSample(), facts.cfa(),
                facts.compression(), facts.unsupported(), facts.calibrated(), facts.iso(), facts.exposureTime(),
                facts.fNumber(), facts.focalLength(), facts.lens(), facts.captureTime(), facts.dngVersion(),
                facts.previews(), facts.layout(), facts.profiles(), facts.enhanced());
    }

    /**
     * Decodes a raw image with the {@linkplain RawDecoderOptions#defaults() default rendering}, at
     * full size.
     *
     * @param source the input
     * @return the decoded image
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format or uses a
     * feature the decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, RawDecoderOptions.defaults());
    }

    /**
     * Decodes a raw image with generic image decoder options, as the image registry does: their
     * limits, size hint and bit depth choice apply, with the default rendering otherwise.
     *
     * @param source the input
     * @param options the limits, size hint and bit depth
     * @return the decoded image
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format or uses a
     * feature the decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        return decode(source, RawDecoderOptions.of(Objects.requireNonNull(options, "options")));
    }

    /**
     * Decodes a raw image.
     *
     * <p>Without a size hint the sensor image is rendered at full size. With one, the result is
     * the smallest embedded preview (JPEG, or JPEG XL in a DNG 1.7 file) at least as large as the
     * hint when the options allow it, else the smallest rendering at least as large as the hint
     * among a rendering of a reduced-resolution linear raw image the DNG file stores (when the
     * options apply the default crop and the opcode lists, which such an image has applied), a half-size
     * rendering and the full rendering; {@link DecodedImage#sourceWidth()} and
     * {@link DecodedImage#sourceHeight()} always give the size of the full rendering. A file whose
     * sensor data does not decode returns its largest preview when a size hint is set and it has
     * one.
     *
     * @param source the input
     * @param options the rendering settings, size hint and limits
     * @return the decoded image
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format or uses a
     * feature the decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, RawDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            var file = RawDecoding.open(source, options.limits());
            var facts = RawDecoding.facts(file);
            var rendering = RawDecoding.render(file, options);
            var format = facts.format() == RawFormat.DNG ? ImageFormat.DNG : ImageFormat.RAW;
            var exif = Optional.ofNullable(RawDecoding.exif(file));
            var profile = rendering.preview() ? Optional.<ColorProfile>empty()
                    : Optional.of(options.colorSpace().profile());
            var icc = profile.map(ColorProfile::toByteArray);
            return new DecodedImage(rendering.picture(), format, facts.orientation(), rendering.width(),
                    rendering.height(), icc, exif, profile);
        }
    }

    /**
     * Extracts the linear sensor image of a raw file, before demosaicing and color rendering, with
     * the {@linkplain RawDecoderOptions#defaults() default options}.
     *
     * @param source the input
     * @return the sensor image of the default crop, with the opcode lists applied
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format or its sensor
     * data uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static RawImage decodeRaw(MediaSource source) {
        return decodeRaw(source, RawDecoderOptions.defaults());
    }

    /**
     * Extracts the linear sensor image of a raw file, before demosaicing and color rendering.
     *
     * <p>The options' limits, opcode, default crop, user crop, enhanced image and camera profile
     * choices apply, and their white balance and color matrix decide the image's color description;
     * the rendering choices are ignored. A four-color sensor whose file carries no color matrices
     * decodes with an empty color matrix unless the options supply one.
     *
     * @param source the input
     * @param options the limits, the opcode and crop choices and the color description
     * @return the sensor image
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format or its sensor
     * data uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static RawImage decodeRaw(MediaSource source, RawDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            var file = RawDecoding.open(source, options.limits());
            var image = RawDecoding.sensorImage(file, options);
            return new RawImage(image.width(), image.height(), image.channels(), image.samples(), image.cfa(),
                    image.neutral(), image.balancedToXyz(), image.layout());
        }
    }

    /**
     * Returns the largest JPEG preview a raw file embeds, as stored: usually the camera's own
     * rendering of the shot at full or near full size. JPEG XL previews are left out.
     *
     * @param source the input
     * @return the JPEG stream, or empty when the file embeds no JPEG preview
     * @throws MediaException.Malformed if the structure is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format
     * @throws MediaException.LimitExceeded if the file or the preview exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     */
    public static Optional<byte[]> extractPreview(MediaSource source) {
        return extractPreview(source, MediaLimits.DEFAULT);
    }

    /**
     * Returns the largest JPEG preview a raw file embeds, as stored, reading the file and the preview
     * within given resource bounds.
     *
     * <p>The limits bound the structures read to find the previews and the length of the preview
     * returned, as {@link #info(MediaSource, MediaLimits)} bounds the description.
     *
     * @param source the input
     * @param limits the resource bounds
     * @return the JPEG stream, or empty when the file embeds no JPEG preview
     * @throws MediaException.Malformed if the structure is corrupt
     * @throws MediaException.Unsupported if the input is not a recognized raw format
     * @throws MediaException.LimitExceeded if the file or the preview exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static Optional<byte[]> extractPreview(MediaSource source, MediaLimits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        var file = RawDecoding.open(source, limits);
        return Optional.ofNullable(RawDecoding.largestPreview(file));
    }
}

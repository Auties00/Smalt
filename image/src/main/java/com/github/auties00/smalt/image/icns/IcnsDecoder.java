package com.github.auties00.smalt.image.icns;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.internal.raster.RasterOutput;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decodes Apple icon image (ICNS) files.
 *
 * <p>A file is an icon family: a sequence of typed elements, each holding one image or the mask of one. Supported:
 * the 1-bit icons with and without mask ({@code ICON}, {@code ICN#}, {@code icm#}, {@code ics#}, {@code ich#}), the
 * 4 and 8-bit icons in the Mac OS system palettes ({@code icm4}, {@code icm8}, {@code ics4}, {@code ics8},
 * {@code icl4}, {@code icl8}, {@code ich4}, {@code ich8}), masked by the 1-bit element of their size, the 24-bit
 * icons ({@code is32}, {@code il32}, {@code ih32}, {@code it32}), run-length coded or stored, masked by the 8-bit
 * element of their size ({@code s8mk}, {@code l8mk}, {@code h8mk}, {@code t8mk}), the ARGB icons ({@code ic04},
 * {@code ic05} and the others that hold the {@code ARGB} tag) and the PNG and JPEG 2000 images of every modern type
 * ({@code icp4} to {@code icp6}, {@code ic04} to {@code ic14}, {@code icsb}, {@code icsB}, {@code sb24},
 * {@code SB24}). The images of the dark appearance nested in a {@code 0xFDD92FA8} element are listed after the
 * others. The table of contents, version, name, info and selection elements carry no image and are skipped, as
 * are unknown elements.
 *
 * <p>{@link #decode(MediaSource, ImageDecoderOptions)} decodes the largest image (the most pixels, then the most
 * bits per pixel, then the light appearance), or, when the options carry a size hint, the smallest image at least
 * that large; {@link #entries(MediaSource)} lists every image and
 * {@link #decode(MediaSource, int, ImageDecoderOptions)} decodes any of them. PNG and JPEG 2000 images decode as
 * {@link PngDecoder} and {@link Jp2Decoder} decode them, with their color profile; the other images decode to packed
 * RGBA in sRGB. A file that ends inside an element keeps the elements before it; one that holds no image is
 * malformed. The number of images is checked against {@link MediaLimits#maxFrames()}.
 *
 * <p>This class is thread-safe.
 */
public final class IcnsDecoder {
    private static final String WHAT = "ICNS icon";

    /**
     * The PNG file signature.
     */
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private IcnsDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes the image {@link #decode(MediaSource)} decodes.
     *
     * <p>{@link ImageInfo#hasAlpha()} is {@code true} when the image may carry transparency: a PNG, JPEG 2000 or
     * ARGB image, a 1-bit icon with mask, or an icon whose family holds the mask of its size.
     *
     * @param source the input, read from offset zero
     * @return the summary; {@link ImageInfo#frameCount()} is the number of images
     * @throws MediaException.Malformed if the file is invalid or holds no image
     * @throws MediaException.LimitExceeded if the image count exceeds the default frame limit
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        return Guards.malformed(WHAT, () -> {
            var family = Family.read(source, ImageDecoderOptions.defaults().limits());
            return family.info(choose(family.entries(), 0, 0));
        });
    }

    /**
     * Lists the images of an icon family under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the images, in file order (the dark appearance last), at least one
     * @throws MediaException.Malformed if the file is invalid or holds no image
     * @throws MediaException.LimitExceeded if the image count exceeds the default frame limit
     * @throws MediaException.Io if reading the source fails
     * @see #entries(MediaSource, ImageDecoderOptions)
     */
    public static List<IcnsEntry> entries(MediaSource source) {
        return entries(source, ImageDecoderOptions.defaults());
    }

    /**
     * Lists the images of an icon family, reading it under the limits of {@code options}.
     *
     * @param source the input, read from offset zero
     * @param options the limits; the other options are ignored
     * @return the images, in file order (the dark appearance last), at least one
     * @throws MediaException.Malformed if the file is invalid or holds no image
     * @throws MediaException.LimitExceeded if the file or the image count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static List<IcnsEntry> entries(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return Guards.malformed(WHAT, () -> Family.read(source, options.limits()).entries());
    }

    /**
     * Decodes the largest image under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded image
     * @throws MediaException.Malformed if the file or the image is invalid
     * @throws MediaException.Unsupported if the image uses a feature its decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits or decoding runs out of memory
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the image that best fits the options: the largest one, or, with a size hint, the smallest one at
     * least {@link ImageDecoderOptions#minWidth()} by {@link ImageDecoderOptions#minHeight()} (the largest when none
     * is).
     *
     * @param source the input, read from offset zero
     * @param options the limits, the size hint, the region (cut out of the whole image), the sRGB conversion and,
     * for PNG and JPEG 2000 images, the bit depth and channel choices
     * @return the decoded image
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the file or the image is invalid
     * @throws MediaException.Unsupported if the image uses a feature its decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits or decoding runs out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return ImageRegions.decode(options, () -> probe(source), whole -> Guards.malformed(WHAT, () -> {
            var family = Family.read(source, whole.limits());
            var index = choose(family.entries(), whole.minWidth(), whole.minHeight());
            return family.decode(index, whole.toBuilder().fullSize().build());
        }));
    }

    /**
     * Decodes one image.
     *
     * @param source the input, read from offset zero
     * @param index the image index, from zero to the image count minus one
     * @param options the limits, the region (cut out of the whole image), the sRGB conversion and, for PNG and JPEG
     * 2000 images, the bit depth and channel choices; the size hint is ignored
     * @return the decoded image
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the file or the image is invalid
     * @throws MediaException.Unsupported if the image uses a feature its decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits or decoding runs out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, int index, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var family = Guards.malformed(WHAT, () -> Family.read(source, options.limits()));
        Objects.checkIndex(index, family.entries().size());
        return ImageRegions.decode(options, null, whole -> Guards.malformed(WHAT,
                () -> family.decode(index, whole.toBuilder().fullSize().build())));
    }

    /**
     * Opens the images of an icon family as pages, in the order of {@link #entries(MediaSource)}.
     *
     * <p>Each page decodes its image at its own size: the size hint of {@code options} is ignored.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the region (cut out of every whole image) and the decoding choices applied to every
     * image; an image that the region does not lie inside raises {@link IllegalArgumentException} when it is read
     * @return the page reader
     * @throws MediaException.Malformed if the file is invalid or holds no image
     * @throws MediaException.LimitExceeded if the file or the image count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static PageReader openPages(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var region = options.region();
        if (region.isPresent()) {
            return ImageRegions.cropPages(openPages(source, options.toBuilder().wholeImage().build()), region.get());
        }
        var family = Guards.malformed(WHAT, () -> Family.read(source, options.limits()));
        var full = options.toBuilder().fullSize().build();
        return new PageReader() {
            @Override
            public int pageCount() {
                return family.entries().size();
            }

            @Override
            public ImageInfo probe(int index) {
                return family.info(Objects.checkIndex(index, pageCount()));
            }

            @Override
            public DecodedImage read(int index) {
                Objects.checkIndex(index, pageCount());
                return Guards.malformed(WHAT, () -> family.decode(index, full));
            }
        };
    }

    /**
     * Opens the images of an icon family as pages under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the page reader
     * @throws MediaException.Malformed if the file is invalid or holds no image
     * @throws MediaException.LimitExceeded if the image count exceeds the default frame limit
     * @throws MediaException.Io if reading the source fails
     * @see #openPages(MediaSource, ImageDecoderOptions)
     */
    public static PageReader openPages(MediaSource source) {
        return openPages(source, ImageDecoderOptions.defaults());
    }

    /**
     * Picks the image to decode.
     *
     * @param entries the images to choose from
     * @param minWidth the smallest acceptable width, or zero
     * @param minHeight the smallest acceptable height, or zero
     * @return the index of the chosen image: with a size hint, the acceptable image with the fewest pixels;
     * otherwise, or when no image is acceptable, the image with the most pixels; ties go to the most bits per pixel,
     * then to the light appearance, then to the first image
     */
    static int choose(List<IcnsEntry> entries, int minWidth, int minHeight) {
        var best = -1;
        if (minWidth > 0 || minHeight > 0) {
            for (var i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                if (entry.width() >= minWidth && entry.height() >= minHeight
                        && (best < 0 || better(entry, entries.get(best), true))) {
                    best = i;
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        for (var i = 0; i < entries.size(); i++) {
            if (best < 0 || better(entries.get(i), entries.get(best), false)) {
                best = i;
            }
        }
        return best;
    }

    /**
     * Returns whether an image is a better choice than the current one.
     *
     * @param candidate the image that may replace {@code current}
     * @param current the current choice
     * @param smaller whether fewer pixels are better
     * @return {@code true} when the candidate has fewer (or, without {@code smaller}, more) pixels, or as many and
     * more bits per pixel, or as many bits and the current choice is dark while the candidate is not
     */
    private static boolean better(IcnsEntry candidate, IcnsEntry current, boolean smaller) {
        var pixels = Long.compare((long) candidate.width() * candidate.height(),
                (long) current.width() * current.height());
        if (pixels != 0) {
            return smaller ? pixels < 0 : pixels > 0;
        }
        if (candidate.bitDepth() != current.bitDepth()) {
            return candidate.bitDepth() > current.bitDepth();
        }
        return current.dark() && !candidate.dark();
    }

    /**
     * Returns whether bytes start with a signature.
     */
    private static boolean startsWith(byte[] data, int offset, int length, byte[] signature) {
        if (length < signature.length) {
            return false;
        }
        for (var i = 0; i < signature.length; i++) {
            if (data[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether an element holds a JPEG 2000 file (its signature box) or codestream (SOC then SIZ).
     */
    private static boolean isJpeg2000(byte[] data, int offset, int length) {
        if (length >= 12 && ByteArrays.u32be(data, offset) == 12 && ByteArrays.u32be(data, offset + 4) == 0x6A502020
                && ByteArrays.u32be(data, offset + 8) == 0x0D0A870A) {
            return true;
        }
        return length >= 4 && ByteArrays.u32be(data, offset) == 0xFF4FFF51L;
    }

    /**
     * Holds the parsed elements of a file.
     *
     * @param data the whole file
     * @param limits the resource bounds checked while decoding images
     * @param entries the image descriptions
     * @param types the element type of each image
     * @param offsets the offset of each image's element data
     * @param lengths the length of each image's element data
     * @param masks the offset of each image's mask data, or -1
     * @param maskLengths the length of each image's mask data
     */
    private record Family(byte[] data, MediaLimits limits, List<IcnsEntry> entries, List<IcnsElements.Type> types,
                          int[] offsets, int[] lengths, int[] masks, int[] maskLengths) {
        /**
         * Reads the elements of a file.
         *
         * @param source the input
         * @param limits the resource bounds
         * @return the family
         * @throws MediaException.Malformed if the header is invalid or the file holds no image
         * @throws MediaException.LimitExceeded if the file or the image count exceeds the limits
         */
        static Family read(MediaSource source, MediaLimits limits) {
            var data = SourceBytes.readAll(source, limits, false);
            if (data.length < 8 || data[0] != 'i' || data[1] != 'c' || data[2] != 'n' || data[3] != 's') {
                throw new MediaException.Malformed("invalid ICNS header");
            }
            var builder = new Builder(data);
            builder.scan(0, data.length, false);
            if (builder.entries.isEmpty()) {
                throw new MediaException.Malformed("the ICNS file holds no image");
            }
            LimitChecks.checkFrames(limits, builder.entries.size());
            var count = builder.entries.size();
            return new Family(data, limits, List.copyOf(builder.entries), List.copyOf(builder.types),
                    Arrays.copyOf(builder.offsets, count), Arrays.copyOf(builder.lengths, count),
                    Arrays.copyOf(builder.masks, count), Arrays.copyOf(builder.maskLengths, count));
        }

        /**
         * Summarizes one image.
         *
         * @param index the image index
         * @return the summary
         */
        ImageInfo info(int index) {
            var entry = entries.get(index);
            var alpha = switch (entry.encoding()) {
                case PNG, JPEG_2000, ARGB -> true;
                case RGB, INDEXED -> masks[index] >= 0;
            };
            return new ImageInfo(ImageFormat.ICNS, entry.width(), entry.height(), alpha, false, entries.size(),
                    Orientation.NORMAL);
        }

        /**
         * Decodes one image.
         *
         * @param index the image index
         * @param options the decoding options, without size hint
         * @return the decoded image
         * @throws MediaException.Malformed if the image is invalid
         */
        DecodedImage decode(int index, ImageDecoderOptions options) {
            var entry = entries.get(index);
            var offset = offsets[index];
            var length = lengths[index];
            switch (entry.encoding()) {
                case PNG, JPEG_2000 -> {
                    var slice = MediaSource.of(Arrays.copyOfRange(data, offset, offset + length));
                    var image = entry.encoding() == IcnsEntry.Encoding.PNG ? PngDecoder.decode(slice, options)
                            : Jp2Decoder.decode(slice, options);
                    return new DecodedImage(image.picture(), ImageFormat.ICNS, Orientation.NORMAL,
                            image.sourceWidth(), image.sourceHeight(), image.iccProfile(), image.exif(),
                            image.xmp(), image.colorProfile());
                }
                default -> {
                }
            }
            var width = entry.width();
            var height = entry.height();
            LimitChecks.checkPicture(limits, width, height);
            var count = width * height;
            var alpha = entry.encoding() == IcnsEntry.Encoding.ARGB || masks[index] >= 0;
            var picture = RgbaPicture.allocate(width, height, alpha);
            var pixels = picture.pixels();
            var type = types.get(index);
            switch (entry.encoding()) {
                case ARGB -> {
                    var planes = IcnsElements.unpack(data, offset + 4, offset + length, 4, count);
                    for (var i = 0; i < count; i++) {
                        pixels[i * 4] = planes[count + i];
                        pixels[i * 4 + 1] = planes[2 * count + i];
                        pixels[i * 4 + 2] = planes[3 * count + i];
                        pixels[i * 4 + 3] = planes[i];
                    }
                }
                case RGB -> {
                    var start = type.code().equals("it32") ? offset + 4 : offset;
                    var size = offset + length - start;
                    if (size == count * 3) {
                        for (var i = 0; i < count; i++) {
                            pixels[i * 4] = data[start + i * 3];
                            pixels[i * 4 + 1] = data[start + i * 3 + 1];
                            pixels[i * 4 + 2] = data[start + i * 3 + 2];
                        }
                    } else if (size == count * 4) {
                        for (var i = 0; i < count; i++) {
                            pixels[i * 4] = data[start + i * 4 + 1];
                            pixels[i * 4 + 1] = data[start + i * 4 + 2];
                            pixels[i * 4 + 2] = data[start + i * 4 + 3];
                        }
                    } else {
                        var planes = IcnsElements.unpack(data, start, offset + length, 3, count);
                        for (var i = 0; i < count; i++) {
                            pixels[i * 4] = planes[i];
                            pixels[i * 4 + 1] = planes[count + i];
                            pixels[i * 4 + 2] = planes[2 * count + i];
                        }
                    }
                    if (masks[index] >= 0) {
                        if (maskLengths[index] < count) {
                            throw new MediaException.Malformed("the ICNS mask of " + type.code() + " holds "
                                    + maskLengths[index] + " of " + count + " bytes");
                        }
                        for (var i = 0; i < count; i++) {
                            pixels[i * 4 + 3] = data[masks[index] + i];
                        }
                    }
                }
                case INDEXED -> {
                    IcnsElements.expand(data, offset, length, type, pixels);
                    var applied = false;
                    if (masks[index] >= 0) {
                        applied = IcnsElements.applyBitMask(data, masks[index], maskLengths[index], width, height,
                                pixels);
                    }
                    if (alpha && !applied) {
                        for (var i = 0; i < count; i++) {
                            pixels[i * 4 + 3] = (byte) 0xFF;
                        }
                    }
                }
                default -> throw new AssertionError(entry.encoding());
            }
            if (!alpha) {
                for (var i = 0; i < count; i++) {
                    pixels[i * 4 + 3] = (byte) 0xFF;
                }
            }
            return RasterOutput.finish(picture, ImageFormat.ICNS, Orientation.NORMAL, null, null, options);
        }

        /**
         * Collects the elements of a family and of its nested dark appearance.
         */
        private static final class Builder {
            private final byte[] data;

            private final List<IcnsEntry> entries = new ArrayList<>();

            private final List<IcnsElements.Type> types = new ArrayList<>();

            private int[] offsets = new int[16];

            private int[] lengths = new int[16];

            private int[] masks = new int[16];

            private int[] maskLengths = new int[16];

            private Builder(byte[] data) {
                this.data = data;
            }

            /**
             * Scans one icon family.
             *
             * @param start the offset of its {@code icns} header
             * @param end the offset past the bytes available to it
             * @param dark whether it is the dark appearance
             */
            void scan(int start, int end, boolean dark) {
                var declared = ByteArrays.u32be(data, start + 4);
                if (declared < 8) {
                    throw new MediaException.Malformed("the ICNS family is shorter than its header");
                }
                var limit = (int) Math.min(end, start + declared);
                var images = new ArrayList<int[]>();
                var imageTypes = new ArrayList<IcnsElements.Type>();
                Map<String, int[]> maskElements = new HashMap<>();
                var position = start + 8;
                int[] nested = null;
                while (position + 8 <= limit) {
                    var code = new String(data, position, 4, StandardCharsets.ISO_8859_1);
                    var length = ByteArrays.u32be(data, position + 4);
                    if (length < 8) {
                        throw new MediaException.Malformed("the ICNS element " + code + " is shorter than its header");
                    }
                    if (position + length > limit) {
                        break;
                    }
                    var body = position + 8;
                    var size = (int) length - 8;
                    var type = IcnsElements.of(code);
                    if (type != null) {
                        switch (type.kind()) {
                            case MASK -> maskElements.putIfAbsent(code, new int[]{body, size});
                            case MONO_MASKED -> {
                                maskElements.putIfAbsent(code, new int[]{body, size});
                                images.add(new int[]{body, size});
                                imageTypes.add(type);
                            }
                            default -> {
                                images.add(new int[]{body, size});
                                imageTypes.add(type);
                            }
                        }
                    } else if (code.equals(IcnsElements.DARK) && !dark && size >= 8 && data[body] == 'i'
                            && data[body + 1] == 'c' && data[body + 2] == 'n' && data[body + 3] == 's') {
                        nested = new int[]{body, body + size};
                    }
                    position += (int) length;
                }
                for (var i = 0; i < images.size(); i++) {
                    add(imageTypes.get(i), images.get(i)[0], images.get(i)[1], maskElements, dark);
                }
                if (nested != null) {
                    scan(nested[0], nested[1], true);
                }
            }

            /**
             * Adds one image.
             */
            private void add(IcnsElements.Type type, int offset, int length, Map<String, int[]> maskElements,
                             boolean dark) {
                var width = type.width();
                var height = type.height();
                var mask = -1;
                var maskLength = 0;
                IcnsEntry.Encoding encoding;
                var depth = 0;
                switch (type.kind()) {
                    case MONO, MONO_MASKED, INDEXED -> {
                        encoding = IcnsEntry.Encoding.INDEXED;
                        depth = type.depth();
                        var rows = IcnsElements.rowBytes(width, 1) * height;
                        var maskElement = type.kind() == IcnsElements.Kind.INDEXED ? maskElements.get(type.mask())
                                : null;
                        if (type.kind() == IcnsElements.Kind.MONO_MASKED) {
                            mask = offset + rows;
                            maskLength = length - rows;
                        } else if (maskElement != null) {
                            mask = maskElement[0] + rows;
                            maskLength = maskElement[1] - rows;
                        }
                        if (mask >= 0 && maskLength < rows) {
                            mask = -1;
                            maskLength = 0;
                        }
                    }
                    case RGB -> {
                        encoding = IcnsEntry.Encoding.RGB;
                        var maskElement = maskElements.get(type.mask());
                        if (maskElement != null) {
                            mask = maskElement[0];
                            maskLength = maskElement[1];
                        }
                        depth = mask >= 0 ? 32 : 24;
                    }
                    default -> {
                        if (startsWith(data, offset, length, PNG_SIGNATURE)) {
                            encoding = IcnsEntry.Encoding.PNG;
                            if (length >= 26) {
                                width = (int) Math.min(Integer.MAX_VALUE, ByteArrays.u32be(data, offset + 16));
                                height = (int) Math.min(Integer.MAX_VALUE, ByteArrays.u32be(data, offset + 20));
                                var channels = switch (data[offset + 25]) {
                                    case 0, 3 -> 1;
                                    case 4 -> 2;
                                    case 2 -> 3;
                                    default -> 4;
                                };
                                depth = (data[offset + 24] & 0xFF) * channels;
                            }
                        } else if (isJpeg2000(data, offset, length)) {
                            encoding = IcnsEntry.Encoding.JPEG_2000;
                        } else if (length >= 4 && data[offset] == 'A' && data[offset + 1] == 'R'
                                && data[offset + 2] == 'G' && data[offset + 3] == 'B') {
                            encoding = IcnsEntry.Encoding.ARGB;
                            depth = 32;
                        } else {
                            encoding = IcnsEntry.Encoding.RGB;
                            depth = 24;
                        }
                    }
                }
                var index = entries.size();
                if (index == offsets.length) {
                    offsets = Arrays.copyOf(offsets, index * 2);
                    lengths = Arrays.copyOf(lengths, index * 2);
                    masks = Arrays.copyOf(masks, index * 2);
                    maskLengths = Arrays.copyOf(maskLengths, index * 2);
                }
                entries.add(new IcnsEntry(index, type.code(), width, height, depth, encoding, dark));
                types.add(type);
                offsets[index] = offset;
                lengths[index] = length;
                masks[index] = mask;
                maskLengths[index] = maskLength;
            }
        }
    }
}

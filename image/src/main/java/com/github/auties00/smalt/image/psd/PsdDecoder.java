package com.github.auties00.smalt.image.psd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.internal.raster.RasterOutput;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodes the composite (merged) image of Adobe Photoshop documents, PSD and PSB.
 *
 * <p>Supported: versions 1 (PSD) and 2 (PSB, the large document format); the bitmap, grayscale, indexed, RGB, CMYK,
 * multichannel, duotone and L*a*b* color modes at their depths (1 bit for bitmap, 8, 16 and 32 bits otherwise); raw,
 * PackBits run-length, ZIP and ZIP-with-prediction compression of the image data. Layers are not composited: a
 * document that declares it has no merged image (its version information resource says so) is unsupported, since
 * its composite would have to be rendered from its layers.
 *
 * <p>The composite has one channel per color of its mode followed by extra channels. The first extra channel is the
 * transparency of the merged image when the layer section's layer count is negative, as the specification defines;
 * every other extra channel is an alpha channel (a saved selection) or, when the display information resource marks
 * it so, a spot color. Packed RGBA output keeps the transparency and drops the other extra channels; with
 * {@link ImageDecoderOptions#keepChannels()} the image decodes to a
 * {@link com.github.auties00.smalt.image.ChannelPicture ChannelPicture} of every channel, the extra channels typed
 * {@link ChannelType#SELECTION_MASK} or {@link ChannelType#SPOT}, for the modes whose colors it can hold (gray, RGB,
 * CMYK, multichannel).
 *
 * <p>Bitmap pixels are black for 1 and white for 0; an indexed image maps through its 256-entry color table, the
 * transparency index resource making its entry transparent; a duotone image decodes as its gray channel; CMYK
 * values are stored inverted and decode to ink amounts (0 no ink), converted to RGB as
 * {@link com.github.auties00.smalt.image.ops.ImageOps#toRgba(Picture)} converts CMYK without
 * {@link ImageDecoderOptions#convertToSrgb()}; L*a*b* converts to sRGB through the CIE L*a*b* profile; a multichannel
 * image decodes as its first channel, read as gray, without {@code keepChannels}. 16-bit samples decode to 16 bits
 * with {@link ImageDecoderOptions#highBitDepth()} and are reduced to 8 otherwise; 32-bit samples are floating point,
 * kept with {@code keepChannels} and clamped to packed RGBA otherwise. The embedded ICC profile (resource 1039), EXIF
 * block (1058) and XMP packet (1060) are returned; a 32-bit document without a profile reports linear light in the
 * BT.709 primaries. The resolution information resource (1005) supplies the pixel density.
 *
 * <p>This class is thread-safe.
 */
public final class PsdDecoder {
    private static final String WHAT = "Photoshop document";

    private static final int BITMAP = 0;
    private static final int GRAYSCALE = 1;
    private static final int INDEXED = 2;
    private static final int RGB = 3;
    private static final int CMYK = 4;
    private static final int MULTICHANNEL = 7;
    private static final int DUOTONE = 8;
    private static final int LAB = 9;

    private PsdDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes a Photoshop document from its header and layer section.
     *
     * <p>{@link ImageInfo#hasAlpha()} is {@code true} when the composite has a transparency channel or an indexed
     * image a transparent entry.
     *
     * @param source the input, read from offset zero
     * @return the summary
     * @throws MediaException.Malformed if the headers are invalid
     * @throws MediaException.Unsupported if the color mode or depth is unsupported
     * @throws MediaException.LimitExceeded if the file exceeds the default byte limit
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, MediaLimits.DEFAULT, false);
                var document = Document.parse(data);
                return new ImageInfo(ImageFormat.PSD, document.width, document.height, document.hasAlpha(), false, 1,
                        Orientation.NORMAL);
            });
        }
    }

    /**
     * Decodes the composite image of a Photoshop document under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded image
     * @throws MediaException.Malformed if the headers or the image data are invalid
     * @throws MediaException.Unsupported if the color mode, depth or compression is unsupported, or the document
     * has no merged image
     * @throws MediaException.LimitExceeded if the image exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the composite image of a Photoshop document.
     *
     * <p>The size hint of {@code options} is ignored.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the region, the bit depth, the channel and the sRGB conversion choices
     * @return the decoded image
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the headers or the image data are invalid
     * @throws MediaException.Unsupported if the color mode, depth or compression is unsupported, or the document
     * has no merged image
     * @throws MediaException.LimitExceeded if the image exceeds the limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return ImageRegions.decode(options, null, whole -> Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, whole.limits(), false);
                return decode(data, Document.parse(data), whole);
            }));
        }
    }

    /**
     * The structure of a document.
     */
    private static final class Document {
        boolean large;
        int channels;
        int width;
        int height;
        int depth;
        int mode;
        byte[] colorData = new byte[0];
        byte[] icc;
        byte[] exif;
        byte[] xmp;
        int transparentIndex = -1;
        boolean mergedData = true;
        boolean mergedAlpha;
        boolean[] spot = new boolean[0];
        List<String> alphaNames = new ArrayList<>();
        PixelDensity density;
        int imageOffset;

        boolean hasAlpha() {
            return (mergedAlpha && channels > colors())
                    || (mode == INDEXED && transparentIndex >= 0 && transparentIndex < 256);
        }

        int colors() {
            return switch (mode) {
                case RGB, LAB -> 3;
                case CMYK -> 4;
                case MULTICHANNEL -> channels;
                default -> 1;
            };
        }

        static Document parse(byte[] data) {
            if (data.length < 26 || data[0] != '8' || data[1] != 'B' || data[2] != 'P' || data[3] != 'S') {
                throw new MediaException.Malformed("the file does not start with a Photoshop signature");
            }
            var document = new Document();
            var version = ByteArrays.u16be(data, 4);
            if (version != 1 && version != 2) {
                throw new MediaException.Malformed("invalid Photoshop version " + version);
            }
            document.large = version == 2;
            document.channels = ByteArrays.u16be(data, 12);
            var height = ByteArrays.u32be(data, 14);
            var width = ByteArrays.u32be(data, 18);
            document.depth = ByteArrays.u16be(data, 22);
            document.mode = ByteArrays.u16be(data, 24);
            var limit = document.large ? 300_000 : 30_000;
            if (width == 0 || height == 0 || width > limit || height > limit) {
                throw new MediaException.Malformed("invalid Photoshop size " + width + "x" + height);
            }
            if (document.channels < 1 || document.channels > 56) {
                throw new MediaException.Malformed("invalid Photoshop channel count " + document.channels);
            }
            document.width = (int) width;
            document.height = (int) height;
            switch (document.mode) {
                case BITMAP -> {
                    if (document.depth != 1) {
                        throw new MediaException.Malformed("a bitmap document has depth " + document.depth);
                    }
                }
                case GRAYSCALE, RGB, CMYK, MULTICHANNEL, DUOTONE, LAB -> {
                    if (document.depth != 8 && document.depth != 16 && document.depth != 32) {
                        throw new MediaException.Unsupported("psd", "Photoshop depth " + document.depth);
                    }
                }
                case INDEXED -> {
                    if (document.depth != 8) {
                        throw new MediaException.Malformed("an indexed document has depth " + document.depth);
                    }
                }
                default -> throw new MediaException.Unsupported("psd", "Photoshop color mode " + document.mode);
            }
            if (document.channels < document.colors()) {
                throw new MediaException.Malformed("a Photoshop document of mode " + document.mode + " has "
                        + document.channels + " channels");
            }
            var position = 26L;
            var colorLength = u32(data, position);
            if (document.mode == INDEXED) {
                if (colorLength < 768) {
                    throw new MediaException.Malformed("the color table of an indexed document is missing");
                }
                document.colorData = slice(data, position + 4, 768);
            }
            position += 4 + colorLength;
            var resourcesLength = u32(data, position);
            resources(data, position + 4, position + 4 + resourcesLength, document);
            position += 4 + resourcesLength;
            var sectionLength = document.large ? u64(data, position) : u32(data, position);
            var sectionStart = position + (document.large ? 8 : 4);
            if (sectionLength >= (document.large ? 10 : 6)) {
                var lengthSize = document.large ? 8 : 4;
                var infoLength = document.large ? u64(data, sectionStart) : u32(data, sectionStart);
                if (infoLength >= 2) {
                    var count = (short) ByteArrays.u16be(data, (int) (sectionStart + lengthSize));
                    document.mergedAlpha = count < 0;
                } else {
                    document.mergedAlpha = taggedLayerCount(data, sectionStart + lengthSize + infoLength,
                            Math.min(data.length, sectionStart + sectionLength), document.large) < 0;
                }
            }
            position = sectionStart + sectionLength;
            if (position + 2 > data.length) {
                throw new MediaException.Malformed("the Photoshop document has no image data section");
            }
            document.imageOffset = (int) position;
            return document;
        }

        /**
         * Returns the layer count of the 16 or 32-bit layer info ({@code Lr16} or {@code Lr32}) of the additional
         * layer information that follows the global layer mask, where documents of those depths keep their layers,
         * or 0 when there is none.
         *
         * @param data the document
         * @param position the offset of the global layer mask information
         * @param end the end of the layer and mask information section
         * @param large whether the document is a PSB, whose long blocks have 8-byte lengths
         * @return the signed layer count, negative when the composite's first extra channel is its transparency
         */
        private static int taggedLayerCount(byte[] data, long position, long end, boolean large) {
            if (position + 4 > end) {
                return 0;
            }
            position += 4 + u32(data, position);
            while (position + 12 <= end) {
                var signature = new String(data, (int) position, 4, StandardCharsets.ISO_8859_1);
                if (!signature.equals("8BIM") && !signature.equals("8B64")) {
                    return 0;
                }
                var key = new String(data, (int) position + 4, 4, StandardCharsets.ISO_8859_1);
                var wide = large && switch (key) {
                    case "LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid",
                         "FXid", "PxSD" -> true;
                    default -> false;
                };
                var length = wide ? u64(data, position + 8) : u32(data, position + 8);
                var body = position + 8 + (wide ? 8 : 4);
                if (key.equals("Lr16") || key.equals("Lr32")) {
                    return length >= 2 && body + 2 <= end ? (short) ByteArrays.u16be(data, (int) body) : 0;
                }
                position = body + ((length + 1) & ~1L);
            }
            return 0;
        }

        private static long u32(byte[] data, long position) {
            if (position + 4 > data.length) {
                throw new MediaException.Malformed("the Photoshop document is truncated at offset " + position);
            }
            return ByteArrays.u32be(data, (int) position);
        }

        private static long u64(byte[] data, long position) {
            var high = u32(data, position);
            var low = u32(data, position + 4);
            var value = high << 32 | low;
            if (value < 0 || value > data.length) {
                throw new MediaException.Malformed("a Photoshop section length exceeds the file");
            }
            return value;
        }

        private static byte[] slice(byte[] data, long position, long length) {
            if (position + length > data.length || length < 0) {
                throw new MediaException.Malformed("a Photoshop block runs past the end of the file");
            }
            var result = new byte[(int) length];
            System.arraycopy(data, (int) position, result, 0, result.length);
            return result;
        }

        /**
         * Reads the image resources the decoder uses.
         */
        private static void resources(byte[] data, long position, long end, Document document) {
            if (end > data.length) {
                throw new MediaException.Malformed("the Photoshop image resources run past the end of the file");
            }
            while (position + 12 <= end) {
                var signature = new String(data, (int) position, 4, StandardCharsets.ISO_8859_1);
                if (!signature.equals("8BIM") && !signature.equals("MeSa") && !signature.equals("AgHg")
                        && !signature.equals("PHUT") && !signature.equals("DCSR")) {
                    break;
                }
                var id = ByteArrays.u16be(data, (int) position + 4);
                var nameLength = data[(int) position + 6] & 0xFF;
                var nameBytes = (1 + nameLength + 1) & ~1;
                var sizeAt = position + 6 + nameBytes;
                var size = u32(data, sizeAt);
                var body = sizeAt + 4;
                if (body + size > end) {
                    throw new MediaException.Malformed("Photoshop image resource " + id + " runs past its section");
                }
                var value = slice(data, body, size);
                switch (id) {
                    case 1039 -> document.icc = value;
                    case 1058 -> document.exif = value;
                    case 1060 -> document.xmp = value;
                    case 1047 -> {
                        if (value.length >= 2) {
                            document.transparentIndex = ByteArrays.u16be(value, 0);
                        }
                    }
                    case 1057 -> {
                        if (value.length >= 5) {
                            document.mergedData = value[4] != 0;
                        }
                    }
                    case 1005 -> {
                        if (value.length >= 16) {
                            var horizontal = ByteArrays.u32be(value, 0) / 65536.0;
                            var vertical = ByteArrays.u32be(value, 8) / 65536.0;
                            if (horizontal > 0 && vertical > 0) {
                                document.density = PixelDensity.ofDotsPerInch(horizontal, vertical);
                            }
                        }
                    }
                    case 1077 -> {
                        // DisplayInfo: a version, then 13 bytes per alpha channel whose last byte is its kind
                        var count = (value.length - 4) / 13;
                        document.spot = new boolean[Math.max(0, count)];
                        for (var index = 0; index < count; index++) {
                            document.spot[index] = value[4 + index * 13 + 12] == 2;
                        }
                    }
                    case 1045 -> {
                        var at = 0;
                        if (value.length >= 4) {
                            var names = new ArrayList<String>();
                            while (at + 4 <= value.length) {
                                var characters = (int) ByteArrays.u32be(value, at);
                                at += 4;
                                if (characters < 0 || at + 2L * characters > value.length) {
                                    break;
                                }
                                var name = new String(value, at, 2 * characters, StandardCharsets.UTF_16BE);
                                names.add(name.endsWith("\0") ? name.substring(0, name.length() - 1) : name);
                                at += 2 * characters;
                            }
                            document.alphaNames = names;
                        }
                    }
                    default -> {
                    }
                }
                position = body + ((size + 1) & ~1L);
            }
        }
    }

    /**
     * Decodes the composite.
     */
    private static DecodedImage decode(byte[] data, Document document, ImageDecoderOptions options) {
        if (!document.mergedData) {
            // TODO: render the composite of a document saved without one by compositing its layers
            throw new MediaException.Unsupported("psd", "documents without a merged image, which would need their"
                    + " layers composited");
        }
        var limits = options.limits();
        var width = document.width;
        var height = document.height;
        LimitChecks.checkPicture(limits, width, height);
        LimitChecks.checkChannels(limits, document.channels);
        var bytesPerSample = document.depth == 1 ? 0 : document.depth / 8;
        var rowBytes = document.depth == 1 ? (width + 7) / 8 : width * bytesPerSample;
        LimitChecks.checkAllocation(limits, (long) rowBytes * height * document.channels);
        var planes = planes(data, document, rowBytes);
        var image = switch (document.mode) {
            case BITMAP -> bitmap(planes[0], width, height, rowBytes);
            case INDEXED -> indexed(planes[0], document, width, height);
            case LAB -> lab(planes, document, options);
            default -> channels(planes, document, options);
        };
        ColorProfile profile;
        if (document.mode == LAB || document.mode == MULTICHANNEL) {
            profile = null;
        } else if (document.icc != null) {
            profile = parse(document.icc);
        } else if (document.depth == 32 && document.mode != CMYK && document.mode != INDEXED
                && document.mode != BITMAP) {
            profile = RasterOutput.linearFor(document.mode == RGB ? PixelLayout.RGB : PixelLayout.GRAY);
        } else {
            profile = null;
        }
        var decoded = RasterOutput.finish(image, ImageFormat.PSD, Orientation.NORMAL, profile, document.icc, options);
        decoded = new DecodedImage(decoded.picture(), ImageFormat.PSD, Orientation.NORMAL, width, height,
                decoded.iccProfile(), Optional.ofNullable(document.exif), Optional.ofNullable(document.xmp),
                decoded.colorProfile(), Optional.ofNullable(document.density));
        return decoded;
    }

    /**
     * Parses an embedded ICC profile, returning {@code null} when it does not parse.
     */
    private static ColorProfile parse(byte[] icc) {
        try {
            return ColorProfile.parse(icc);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Decompresses the planes of the image data section, one array per channel.
     */
    private static byte[][] planes(byte[] data, Document document, int rowBytes) {
        var position = document.imageOffset;
        var compression = ByteArrays.u16be(data, position);
        position += 2;
        var height = document.height;
        var channels = document.channels;
        var planeBytes = (long) rowBytes * height;
        var planes = new byte[channels][];
        switch (compression) {
            case 0 -> {
                for (var channel = 0; channel < channels; channel++) {
                    planes[channel] = new byte[(int) planeBytes];
                    var available = (int) Math.max(0, Math.min(planeBytes, data.length - position));
                    System.arraycopy(data, position, planes[channel], 0, available);
                    position += available;
                }
            }
            case 1 -> {
                var countBytes = document.large ? 4 : 2;
                var rows = (long) channels * height;
                if (position + rows * countBytes > data.length) {
                    throw new MediaException.Malformed("the Photoshop row byte counts are truncated");
                }
                var counts = position;
                position += (int) (rows * countBytes);
                for (var channel = 0; channel < channels; channel++) {
                    var plane = new byte[(int) planeBytes];
                    for (var y = 0; y < height; y++) {
                        var index = (long) channel * height + y;
                        var count = countBytes == 2 ? ByteArrays.u16be(data, (int) (counts + index * 2))
                                : ByteArrays.u32be(data, (int) (counts + index * 4));
                        var end = Math.min(data.length, position + count);
                        unpack(data, position, (int) end, plane, y * rowBytes, rowBytes);
                        position = (int) end;
                    }
                    planes[channel] = plane;
                }
            }
            case 2, 3 -> {
                var inflater = new Inflater();
                try {
                    inflater.setInput(data, position, data.length - position);
                    for (var channel = 0; channel < channels; channel++) {
                        var plane = new byte[(int) planeBytes];
                        var filled = 0;
                        while (filled < plane.length) {
                            var count = inflater.inflate(plane, filled, plane.length - filled);
                            if (count == 0 && (inflater.finished() || inflater.needsInput()
                                    || inflater.needsDictionary())) {
                                break;
                            }
                            filled += count;
                        }
                        if (compression == 3) {
                            unpredict(plane, rowBytes, height, document.depth, document.width);
                        }
                        planes[channel] = plane;
                    }
                } catch (DataFormatException exception) {
                    throw new MediaException.Malformed("corrupt ZIP data in the Photoshop image data", exception);
                } finally {
                    inflater.end();
                }
            }
            default -> throw new MediaException.Unsupported("psd", "Photoshop compression " + compression);
        }
        return planes;
    }

    /**
     * Unpacks one PackBits row; a row that ends early leaves the rest zero.
     */
    private static void unpack(byte[] data, int position, int end, byte[] out, int offset, int length) {
        var written = 0;
        while (position < end && written < length) {
            var header = data[position++];
            if (header >= 0) {
                var count = Math.min(header + 1, Math.min(length - written, end - position));
                System.arraycopy(data, position, out, offset + written, count);
                position += header + 1;
                written += count;
            } else if (header != -128) {
                if (position >= end) {
                    break;
                }
                var count = Math.min(1 - header, length - written);
                java.util.Arrays.fill(out, offset + written, offset + written + count, data[position++]);
                written += count;
            }
        }
    }

    /**
     * Undoes the prediction of ZIP-with-prediction data: horizontal differences of 8 and 16-bit samples, and of the
     * bytes of 32-bit rows, whose four byte planes are stored one after another.
     */
    private static void unpredict(byte[] plane, int rowBytes, int height, int depth, int width) {
        var row = new byte[rowBytes];
        for (var y = 0; y < height; y++) {
            var base = y * rowBytes;
            switch (depth) {
                case 16 -> {
                    var previous = 0;
                    for (var x = 0; x < width; x++) {
                        var at = base + 2 * x;
                        previous = (previous + ((plane[at] & 0xFF) << 8 | (plane[at + 1] & 0xFF))) & 0xFFFF;
                        plane[at] = (byte) (previous >>> 8);
                        plane[at + 1] = (byte) previous;
                    }
                }
                case 32 -> {
                    for (var x = 1; x < rowBytes; x++) {
                        plane[base + x] += plane[base + x - 1];
                    }
                    for (var x = 0; x < width; x++) {
                        for (var b = 0; b < 4; b++) {
                            row[4 * x + b] = plane[base + b * width + x];
                        }
                    }
                    System.arraycopy(row, 0, plane, base, rowBytes);
                }
                default -> {
                    for (var x = 1; x < rowBytes; x++) {
                        plane[base + x] += plane[base + x - 1];
                    }
                }
            }
        }
    }

    /**
     * Expands a bitmap plane, 1 being black.
     */
    private static Picture bitmap(byte[] plane, int width, int height, int rowBytes) {
        var picture = Channel8Picture.allocate(width, height, PixelLayout.GRAY);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var bit = (plane[y * rowBytes + (x >>> 3)] >>> (7 - (x & 7))) & 1;
                pixels[y * width + x] = bit != 0 ? 0 : (byte) 255;
            }
        }
        return picture;
    }

    /**
     * Maps an indexed plane through the color table.
     */
    private static Picture indexed(byte[] plane, Document document, int width, int height) {
        var alpha = document.transparentIndex >= 0 && document.transparentIndex < 256;
        var picture = Channel8Picture.allocate(width, height, alpha ? PixelLayout.RGBA : PixelLayout.RGB);
        var pixels = picture.pixels();
        var table = document.colorData;
        var channels = alpha ? 4 : 3;
        for (var index = 0; index < width * height; index++) {
            var entry = plane[index] & 0xFF;
            var out = index * channels;
            pixels[out] = table[entry];
            pixels[out + 1] = table[256 + entry];
            pixels[out + 2] = table[512 + entry];
            if (alpha) {
                pixels[out + 3] = entry == document.transparentIndex ? 0 : (byte) 255;
            }
        }
        return picture;
    }

    /**
     * Interleaves the planes of the gray, duotone, RGB, CMYK and multichannel modes into a channel picture.
     */
    private static Picture channels(byte[][] planes, Document document, ImageDecoderOptions options) {
        var keep = options.keepChannels() && !options.convertToSrgb();
        var colors = document.mode == DUOTONE ? 1 : document.colors();
        var extra = document.mode == DUOTONE ? document.channels - 1 : document.channels - colors;
        var layoutChannels = new ArrayList<Channel>();
        switch (document.mode) {
            case RGB -> layoutChannels.addAll(PixelLayout.RGB.channels());
            case CMYK -> layoutChannels.addAll(PixelLayout.CMYK.channels());
            case MULTICHANNEL -> {
                if (keep) {
                    for (var index = 0; index < colors; index++) {
                        layoutChannels.add(Channel.ofInk(index < document.alphaNames.size()
                                ? document.alphaNames.get(index) : "Channel " + (index + 1)));
                    }
                } else {
                    layoutChannels.add(Channel.of(ChannelType.GRAY));
                }
            }
            default -> layoutChannels.add(Channel.of(ChannelType.GRAY));
        }
        var sources = new ArrayList<Integer>();
        var multichannelGray = document.mode == MULTICHANNEL && !keep;
        for (var index = 0; index < (multichannelGray ? 1 : colors); index++) {
            sources.add(index);
        }
        var firstExtra = document.mode == DUOTONE ? 1 : colors;
        for (var index = 0; index < extra; index++) {
            var plane = firstExtra + index;
            if (index == 0 && document.mergedAlpha) {
                layoutChannels.add(Channel.of(ChannelType.ALPHA));
                sources.add(plane);
            } else if (keep && document.mode != MULTICHANNEL) {
                var alphaIndex = document.mergedAlpha ? index - 1 : index;
                var spot = alphaIndex >= 0 && alphaIndex < document.spot.length && document.spot[alphaIndex];
                var name = alphaIndex >= 0 && alphaIndex < document.alphaNames.size()
                        ? document.alphaNames.get(alphaIndex) : "Alpha " + (alphaIndex + 1);
                layoutChannels.add(spot ? Channel.ofSpot(name) : new Channel(ChannelType.SELECTION_MASK, name));
                sources.add(plane);
            }
        }
        var layout = PixelLayout.of(layoutChannels.toArray(Channel[]::new));
        var width = document.width;
        var height = document.height;
        var count = width * height;
        var channels = sources.size();
        var invert = document.mode == CMYK;
        switch (document.depth) {
            case 8 -> {
                var picture = Channel8Picture.allocate(width, height, layout);
                var pixels = picture.pixels();
                for (var channel = 0; channel < channels; channel++) {
                    var plane = planes[sources.get(channel)];
                    var inverted = invert && channel < 4;
                    for (var index = 0; index < count; index++) {
                        var value = plane[index];
                        pixels[index * channels + channel] = inverted ? (byte) ~value : value;
                    }
                }
                return picture;
            }
            case 16 -> {
                var picture = Channel16Picture.allocate(width, height, layout);
                var pixels = picture.pixels();
                for (var channel = 0; channel < channels; channel++) {
                    var plane = planes[sources.get(channel)];
                    var inverted = invert && channel < 4;
                    for (var index = 0; index < count; index++) {
                        var value = (short) ((plane[2 * index] & 0xFF) << 8 | (plane[2 * index + 1] & 0xFF));
                        pixels[index * channels + channel] = inverted ? (short) ~value : value;
                    }
                }
                return picture;
            }
            default -> {
                var picture = ChannelFloatPicture.allocate(width, height, layout);
                var pixels = picture.pixels();
                for (var channel = 0; channel < channels; channel++) {
                    var plane = planes[sources.get(channel)];
                    var inverted = invert && channel < 4;
                    for (var index = 0; index < count; index++) {
                        var value = Float.intBitsToFloat((int) ByteArrays.u32be(plane, 4 * index));
                        pixels[index * channels + channel] = inverted ? 1f - value : value;
                    }
                }
                return picture;
            }
        }
    }

    /**
     * Converts L*a*b* planes to sRGB, keeping the transparency channel.
     */
    private static Picture lab(byte[][] planes, Document document, ImageDecoderOptions options) {
        var width = document.width;
        var height = document.height;
        var count = width * height;
        var alpha = document.mergedAlpha && document.channels > 3;
        var channels = alpha ? 4 : 3;
        var values = new float[count * 3];
        var alphaValues = alpha ? new float[count] : null;
        for (var index = 0; index < count; index++) {
            for (var channel = 0; channel < 3; channel++) {
                var plane = planes[channel];
                double value = switch (document.depth) {
                    case 8 -> channel == 0 ? (plane[index] & 0xFF) * 100.0 / 255 : (plane[index] & 0xFF) - 128.0;
                    case 16 -> {
                        var sample = (plane[2 * index] & 0xFF) << 8 | (plane[2 * index + 1] & 0xFF);
                        yield channel == 0 ? sample * 100.0 / 65535 : (sample - 32768) / 256.0;
                    }
                    default -> {
                        var sample = Float.intBitsToFloat((int) ByteArrays.u32be(plane, 4 * index));
                        yield channel == 0 ? sample * 100.0 : sample * 255.0 - 128.0;
                    }
                };
                values[3 * index + channel] = (float) value;
            }
            if (alpha) {
                var plane = planes[3];
                alphaValues[index] = switch (document.depth) {
                    case 8 -> (plane[index] & 0xFF) / 255f;
                    case 16 -> ((plane[2 * index] & 0xFF) << 8 | (plane[2 * index + 1] & 0xFF)) / 65535f;
                    default -> Float.intBitsToFloat((int) ByteArrays.u32be(plane, 4 * index));
                };
            }
        }
        var transform = ColorTransform.create(List.of(ColorProfile.lab(), ColorProfile.srgb()));
        var picture = ChannelFloatPicture.allocate(width, height, alpha ? PixelLayout.RGBA : PixelLayout.RGB);
        var pixels = picture.pixels();
        transform.transform(values, 0, 3, pixels, 0, channels, count);
        if (alpha) {
            for (var index = 0; index < count; index++) {
                pixels[4 * index + 3] = alphaValues[index];
            }
        }
        if (document.depth == 32 && options.keepChannels()) {
            return picture;
        }
        return document.depth == 16 ? com.github.auties00.smalt.image.ops.ImageOps.toRgba16(picture)
                : com.github.auties00.smalt.image.ops.ImageOps.toRgba(picture);
    }
}

package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifGeometry;
import com.github.auties00.smalt.internal.heif.HeifGrid;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifMovie;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.tiff.Ifd;
import com.github.auties00.smalt.internal.tiff.TiffExif;
import com.github.auties00.smalt.internal.tiff.TiffHeader;
import com.github.auties00.smalt.internal.tiff.TiffInput;
import com.github.auties00.smalt.internal.tiff.TiffTypes;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Describes an image file from its headers, without decoding pixels.
 *
 * <p>Each format is walked as far as its headers go: PNG chunks up to the image data
 * ({@code IHDR}, {@code tRNS}, {@code acTL}, {@code eXIf}), JPEG markers up to the frame header
 * (the EXIF {@code APP1} and the {@code SOFn}), every GIF block (its frames are counted, their
 * LZW data skipped), the WebP chunks ({@code VP8X}, {@code VP8}, {@code VP8L}, {@code ALPH},
 * {@code ANIM}, {@code ANMF}, {@code EXIF}), the BMP information header, every TIFF image file
 * directory (the first one describes the picture), and the HEIF {@code meta} box of HEIC and
 * AVIF files (the primary item, its {@code ispe}, {@code pixi}, {@code av1C}/{@code hvcC},
 * {@code irot} and {@code imir} properties and any alpha auxiliary image), the boxes and
 * {@code SIZ} segment of a JPEG 2000 file ({@link Jp2Headers}), the JPEG XL image header
 * ({@link JxlHeaders}), and the directories of a DNG or camera raw file ({@link RawHeaders}).
 */
final class ImageHeaders {
    /**
     * The largest number of chunks, markers, blocks or boxes examined.
     */
    private static final int MAX_STEPS = 1 << 16;

    /**
     * The largest metadata body read into memory (EXIF, HEIF {@code meta}).
     */
    private static final int MAX_BODY = 16 << 20;

    /**
     * The most pages of a TIFF file counted.
     */
    private static final int MAX_PAGES = 10_000;

    /**
     * Prevents instantiation.
     */
    private ImageHeaders() {
        throw new AssertionError();
    }

    /**
     * Describes the picture of an image file.
     *
     * @param source the input
     * @param format the format recognized from its leading bytes
     * @return the picture description
     * @throws MediaException.Malformed if the headers are corrupt or declare no picture size
     * @throws MediaException.LimitExceeded if the headers need more memory than is available
     * @throws MediaException.Io if reading the source fails
     */
    static Header read(MediaSource source, ImageFormat format) {
        return Guards.malformed("image headers", () -> {
            var header = switch (format) {
                case PNG -> png(source);
                case JPEG -> jpeg(source);
                case GIF -> gif(source);
                case WEBP -> webp(source);
                case BMP -> bmp(source);
                case TIFF -> tiff(source);
                case ICO -> ico(source);
                case HEIC, AVIF -> heif(source);
                case JP2 -> Jp2Headers.read(source);
                case JXL -> JxlHeaders.read(source);
                case DNG, RAW -> RawHeaders.read(source);
            };
            if (header.width <= 0 || header.height <= 0) {
                throw new MediaException.Malformed("the " + format.name().toLowerCase(Locale.ROOT)
                        + " headers declare no picture size");
            }
            return header;
        });
    }

    /**
     * Collects what the headers of an image say; a zero field is unknown.
     */
    static final class Header {
        /**
         * The stored width, before the orientation is applied.
         */
        int width;

        int height;

        /**
         * The bits per sample.
         */
        int bitDepth;

        /**
         * Whether the image declares transparency.
         */
        boolean alpha;

        /**
         * The number of frames, or {@code -1} when unknown.
         */
        long frames = 1;

        boolean animated;

        /**
         * The number of plays, 0 for endless, or {@code -1} when unknown.
         */
        int loops = -1;

        Orientation orientation = Orientation.NORMAL;

        /**
         * The duration of an animation in microseconds, or {@code -1} when unknown.
         */
        long durationMicros = -1;

        /**
         * The normalized tags of the file, such as the camera of a raw image.
         */
        Map<String, String> tags = Map.of();
    }

    /**
     * Walks the chunks of a PNG file up to the first {@code IDAT}.
     *
     * @param source the input
     * @return the header
     */
    private static Header png(MediaSource source) {
        var result = new Header();
        var reader = new SourceReader(source, 8192);
        reader.seek(8);
        for (var step = 0; step < MAX_STEPS && reader.remaining() >= 8; step++) {
            var length = reader.u32be();
            var type = reader.fourCc();
            var body = reader.position();
            switch (FourCc.toString(type)) {
                case "IHDR" -> {
                    result.width = (int) reader.u32be();
                    result.height = (int) reader.u32be();
                    var depth = reader.u8();
                    var colorType = reader.u8();
                    result.bitDepth = colorType == 3 ? 8 : depth;
                    result.alpha = colorType == 4 || colorType == 6;
                }
                case "tRNS" -> result.alpha = true;
                case "acTL" -> {
                    var frames = reader.u32be();
                    var plays = reader.u32be();
                    result.frames = frames;
                    result.animated = frames > 1;
                    result.loops = (int) Math.min(Integer.MAX_VALUE, plays);
                }
                case "fcTL" -> {
                    if (result.durationMicros < 0) {
                        result.durationMicros = 0;
                    }
                    reader.skip(20);
                    var numerator = reader.u16be();
                    var denominator = reader.u16be();
                    result.durationMicros += numerator * 1_000_000L / (denominator == 0 ? 100 : denominator);
                }
                case "eXIf" -> {
                    if (length <= MAX_BODY) {
                        result.orientation = TiffExif.orientation(reader.bytes(length, MAX_BODY), 0);
                    }
                }
                case "IDAT", "IEND" -> {
                    if (!result.animated) {
                        result.durationMicros = -1;
                        return result;
                    }
                }
                default -> {
                }
            }
            reader.seek(body + length + 4);
        }
        return result;
    }

    /**
     * Walks the markers of a JPEG file up to the frame header.
     *
     * @param source the input
     * @return the header
     */
    private static Header jpeg(MediaSource source) {
        var result = new Header();
        var reader = new SourceReader(source, 8192);
        reader.seek(2);
        for (var step = 0; step < MAX_STEPS && reader.remaining() >= 4; step++) {
            var lead = reader.u8();
            if (lead != 0xFF) {
                continue;
            }
            var marker = reader.u8();
            while (marker == 0xFF && reader.remaining() > 0) {
                marker = reader.u8();
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return result;
            }
            var length = reader.u16be();
            var body = reader.position();
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                result.bitDepth = reader.u8();
                result.height = reader.u16be();
                result.width = reader.u16be();
                return result;
            }
            if (marker == 0xE1 && length > 8) {
                var bytes = reader.bytes(length - 2, MAX_BODY);
                var prefix = TiffExif.prefixLength(bytes, 0);
                if (prefix > 0) {
                    result.orientation = TiffExif.orientation(bytes, prefix);
                }
            }
            reader.seek(body + length - 2);
        }
        return result;
    }

    /**
     * Walks every block of a GIF file, counting its frames.
     *
     * @param source the input
     * @return the header
     */
    private static Header gif(MediaSource source) {
        var result = new Header();
        var reader = new SourceReader(source, 64 * 1024);
        reader.seek(6);
        result.width = reader.u16le();
        result.height = reader.u16le();
        var packed = reader.u8();
        reader.skip(2);
        if ((packed & 0x80) != 0) {
            reader.skip(3L << ((packed & 0x07) + 1));
        }
        result.bitDepth = 8;
        var frames = 0L;
        var duration = 0L;
        var delay = 0;
        var loops = -1;
        for (var step = 0; step < Integer.MAX_VALUE && reader.remaining() > 0; step++) {
            var introducer = reader.u8();
            if (introducer == 0x3B) {
                break;
            }
            if (introducer == 0x21) {
                var label = reader.u8();
                if (label == 0xF9) {
                    var size = reader.u8();
                    var start = reader.position();
                    if (size >= 4) {
                        var flags = reader.u8();
                        delay = reader.u16le();
                        if ((flags & 1) != 0) {
                            result.alpha = true;
                        }
                    }
                    reader.seek(start + size);
                    skipSubBlocks(reader);
                } else if (label == 0xFF) {
                    var size = reader.u8();
                    var start = reader.position();
                    var identifier = size >= 11 ? new String(reader.bytes(11, 11), StandardCharsets.ISO_8859_1) : "";
                    reader.seek(start + size);
                    if (identifier.equals("NETSCAPE2.0") || identifier.equals("ANIMEXTS1.0")) {
                        var subSize = reader.u8();
                        var subStart = reader.position();
                        if (subSize >= 3 && reader.u8() == 1) {
                            loops = reader.u16le();
                        }
                        reader.seek(subStart + subSize);
                    }
                    skipSubBlocks(reader);
                } else {
                    skipSubBlocks(reader);
                }
            } else if (introducer == 0x2C) {
                reader.skip(8);
                var local = reader.u8();
                if ((local & 0x80) != 0) {
                    reader.skip(3L << ((local & 0x07) + 1));
                }
                reader.u8();
                skipSubBlocks(reader);
                frames++;
                duration += delay * 10_000L;
                delay = 0;
            } else {
                break;
            }
        }
        result.frames = frames;
        result.animated = frames > 1;
        if (result.animated) {
            result.loops = loops < 0 ? 1 : loops == 0 ? 0 : loops + 1;
            result.durationMicros = duration;
        }
        return result;
    }

    /**
     * Skips a chain of GIF data sub-blocks and its terminator.
     *
     * @param reader the reader, at the first sub-block size
     */
    private static void skipSubBlocks(SourceReader reader) {
        while (reader.remaining() > 0) {
            var size = reader.u8();
            if (size == 0) {
                return;
            }
            reader.skip(size);
        }
    }

    /**
     * Walks the chunks of a WebP file.
     *
     * @param source the input
     * @return the header
     */
    private static Header webp(MediaSource source) {
        var result = new Header();
        var reader = new SourceReader(source, 64 * 1024);
        reader.seek(12);
        var frames = 0L;
        var duration = 0L;
        for (var step = 0; step < MAX_STEPS && reader.remaining() >= 8; step++) {
            var type = FourCc.toString(reader.fourCc());
            var size = reader.u32le();
            var body = reader.position();
            switch (type) {
                case "VP8X" -> {
                    var flags = reader.u8();
                    reader.skip(3);
                    result.width = reader.u24le() + 1;
                    result.height = reader.u24le() + 1;
                    result.alpha = (flags & 0x10) != 0;
                    result.animated = (flags & 0x02) != 0;
                }
                case "VP8 " -> {
                    if (result.width == 0) {
                        reader.skip(6);
                        result.width = reader.u16le() & 0x3FFF;
                        result.height = reader.u16le() & 0x3FFF;
                    }
                }
                case "VP8L" -> {
                    reader.u8();
                    var bits = reader.u32le();
                    if (result.width == 0) {
                        result.width = (int) (bits & 0x3FFF) + 1;
                        result.height = (int) ((bits >> 14) & 0x3FFF) + 1;
                    }
                    result.alpha |= ((bits >> 28) & 1) != 0;
                }
                case "ALPH" -> result.alpha = true;
                case "ANIM" -> {
                    reader.skip(4);
                    result.loops = reader.u16le();
                }
                case "ANMF" -> {
                    reader.skip(12);
                    duration += (reader.u24le()) * 1000L;
                    frames++;
                }
                case "EXIF" -> {
                    if (size <= MAX_BODY) {
                        var bytes = reader.bytes(size, MAX_BODY);
                        result.orientation = TiffExif.orientation(bytes, TiffExif.prefixLength(bytes, 0));
                    }
                }
                default -> {
                }
            }
            reader.seek(body + size + (size & 1));
        }
        result.bitDepth = 8;
        if (result.animated) {
            result.frames = frames;
            result.durationMicros = duration;
        } else {
            result.loops = -1;
        }
        return result;
    }

    /**
     * Reads the information header of a BMP file, or of the bitmap an OS/2 bitmap array decodes to:
     * its bitmap entry of the largest area, the first among equal ones.
     *
     * @param source the input
     * @return the header, whose frame count is the number of bitmap entries of an array
     */
    private static Header bmp(MediaSource source) {
        var reader = new SourceReader(source, 256);
        if (reader.u16le() != ('A' << 8 | 'B')) {
            return bmpEntry(reader, 0);
        }
        Header best = null;
        var count = 0;
        var position = 0L;
        for (var step = 0; step < MAX_STEPS; step++) {
            reader.seek(position + 14);
            if (reader.u16le() == ('M' << 8 | 'B')) {
                var entry = bmpEntry(reader, position + 14);
                count++;
                if (best == null || (long) entry.width * entry.height > (long) best.width * best.height) {
                    best = entry;
                }
            }
            reader.seek(position + 6);
            var next = reader.u32le();
            if (next == 0 || next <= position) {
                break;
            }
            position = next;
        }
        if (best == null) {
            throw new MediaException.Malformed("the OS/2 bitmap array holds no bitmap");
        }
        best.frames = count;
        return best;
    }

    /**
     * Reads the information header of one bitmap.
     *
     * @param reader the input
     * @param start the offset of the bitmap's file header
     * @return the header
     */
    private static Header bmpEntry(SourceReader reader, long start) {
        var result = new Header();
        reader.seek(start + 14);
        var headerSize = reader.u32le();
        if (headerSize == 12) {
            result.width = reader.u16le();
            result.height = reader.u16le();
            reader.u16le();
            var bits = reader.u16le();
            result.bitDepth = bits == 16 ? 5 : 8;
            return result;
        }
        result.width = reader.s32le();
        result.height = Math.abs(reader.s32le());
        reader.u16le();
        var bits = reader.u16le();
        result.bitDepth = bits == 16 ? 5 : bits == 64 ? 16 : 8;
        if (bits == 32 && headerSize >= 56) {
            reader.seek(start + 14 + 40 + 12);
            result.alpha = reader.u32le() != 0;
        }
        result.alpha |= bits == 64;
        return result;
    }

    /**
     * Walks the image file directories of a TIFF or BigTIFF file, counting its pages.
     *
     * <p>The picture is described by the first directory; a chain of directories that loops ends
     * the count.
     *
     * @param source the input
     * @return the header
     */
    private static Header tiff(MediaSource source) {
        var result = new Header();
        var size = source.size();
        var head = new byte[TiffHeader.BIG_SIZE];
        source.readFully(0, head, 0, TiffHeader.SIZE);
        var little = head[0] == 'I';
        var big = ByteArrays.u16(head, 2, little) == 43;
        long first;
        if (big) {
            source.readFully(TiffHeader.SIZE, head, TiffHeader.SIZE, TiffHeader.BIG_SIZE - TiffHeader.SIZE);
            first = little ? ByteArrays.i64le(head, 8) : ByteArrays.i64be(head, 8);
        } else {
            first = ByteArrays.u32(head, 4, little);
        }
        var header = new TiffHeader(little, big, first);
        var input = TiffInput.of(source);
        var countSize = big ? 8 : 2;
        var pages = 0L;
        var seen = new HashSet<Long>();
        var offset = first;
        while (offset > 0 && (size < 0 || offset + countSize <= size) && pages < MAX_PAGES && seen.add(offset)) {
            var ifd = Ifd.read(input, 0, header, offset, Ifd.Policy.STRICT);
            if (pages == 0) {
                describe(ifd, result);
            }
            pages++;
            offset = ifd.next();
        }
        result.frames = pages;
        return result;
    }

    /**
     * Reads the picture size, bit depth, orientation and extra samples of the first directory of a
     * TIFF file.
     *
     * @param ifd the first directory
     * @param result the header to fill
     */
    private static void describe(Ifd ifd, Header result) {
        for (var index = 0; index < ifd.count(); index++) {
            var tag = ifd.tag(index);
            if (tag == 338) {
                result.alpha = true;
                continue;
            }
            var type = ifd.type(index);
            if (!TiffTypes.isInteger(type)) {
                continue;
            }
            var value = ifd.firstInteger(index, 0);
            var narrowed = type == TiffTypes.LONG8 ? (int) Math.min(Integer.MAX_VALUE, value) : (int) value;
            switch (tag) {
                case 256 -> result.width = narrowed;
                case 257 -> result.height = narrowed;
                case 258 -> result.bitDepth = narrowed;
                case 274 -> result.orientation = Orientation.ofExif(narrowed);
                default -> {
                }
            }
        }
    }

    /**
     * Reads the directory of an icon or cursor file, describing its largest entry.
     *
     * <p>An entry's width or height byte of zero means 256 pixels; the largest entry is the one
     * with the most pixels, then the most bits per pixel, as icon decoders choose it.
     *
     * @param source the input
     * @return the header, whose frame count is the number of entries
     */
    private static Header ico(MediaSource source) {
        var result = new Header();
        var reader = new SourceReader(source, 4096);
        reader.seek(4);
        var count = reader.u16le();
        var bestPixels = -1L;
        var bestBits = -1;
        for (var index = 0; index < count && reader.remaining() >= 16; index++) {
            var width = reader.u8();
            var height = reader.u8();
            reader.u8();
            reader.u8();
            reader.u16le();
            var bits = reader.u16le();
            reader.u32le();
            reader.u32le();
            width = width == 0 ? 256 : width;
            height = height == 0 ? 256 : height;
            var pixels = (long) width * height;
            if (pixels > bestPixels || (pixels == bestPixels && bits > bestBits)) {
                bestPixels = pixels;
                bestBits = bits;
                result.width = width;
                result.height = height;
                result.bitDepth = 8;
                result.alpha = bits == 0 || bits == 32;
            }
        }
        result.frames = count;
        return result;
    }

    /**
     * Reads the structure of a HEIF file (AVIF or HEIC): the primary item, its properties and its
     * alpha auxiliary images, or the image sequence a file without a primary item (or an AVIF file
     * whose major brand is {@code avis}) shows.
     *
     * <p>The size is that of the image the AVIF and HEIC decoders return: after the clean aperture
     * and before the orientation the rotation and mirroring properties compose to.
     *
     * @param source the input
     * @return the header
     */
    private static Header heif(MediaSource source) {
        var result = new Header();
        var limits = MediaLimits.builder().maxBytes(MAX_BODY).build();
        var file = HeifFile.parse(source, limits, false);
        var primary = file.primaryItem();
        if (file.hasMovie() && (primary == null || file.majorBrand().equals("avis"))) {
            var movie = HeifMovie.parse(source, file, limits);
            for (var track : movie.tracks()) {
                if (isSequenceTrack(track)) {
                    result.width = track.width();
                    result.height = track.height();
                    result.frames = track.sampleCount();
                    result.animated = track.sampleCount() > 1;
                    result.loops = track.loopCount();
                    result.bitDepth = depthOf(track.sampleProperties());
                    for (var other : movie.tracks()) {
                        var type = other.property(HeifProperty.AuxiliaryType.class);
                        if (other.references("auxl", track.id()) && (type == null || type.isAlpha())) {
                            result.alpha = true;
                        }
                    }
                    return result;
                }
            }
        }
        if (primary == null) {
            return result;
        }
        long width;
        long height;
        var extent = primary.property(HeifProperty.SpatialExtent.class);
        if (extent != null) {
            width = extent.width();
            height = extent.height();
        } else if (primary.type().equals("grid") && primary.size() <= 12) {
            var grid = HeifGrid.parse(file.readItem(source, primary, limits));
            width = grid.outputWidth();
            height = grid.outputHeight();
        } else {
            return result;
        }
        if (width <= 0 || height <= 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
            return result;
        }
        var geometry = HeifGeometry.resolve(primary.properties(), (int) width, (int) height);
        result.width = geometry.width();
        result.height = geometry.height();
        result.orientation = geometry.orientation();
        result.alpha = hasAlpha(file, primary);
        var coded = primary;
        for (var depth = 0; coded != null && depth < 8 && !coded.type().equals("av01") && !coded.type().equals("hvc1");
             depth++) {
            var inputs = coded.references("dimg");
            coded = inputs.length == 0 ? null : file.item(inputs[0]);
        }
        result.bitDepth = depthOf(primary.properties());
        if (result.bitDepth == 0 && coded != null) {
            result.bitDepth = depthOf(coded.properties());
        }
        return result;
    }

    /**
     * Returns whether a track of a HEIF image sequence is the color track a decoder shows.
     *
     * @param track a track of the image sequence
     * @return {@code true} for an AV1 or HEVC track with samples and a size that is neither an
     * auxiliary nor a thumbnail track
     */
    private static boolean isSequenceTrack(HeifMovie.Track track) {
        var entry = track.sampleEntry();
        return (entry.equals("av01") || entry.equals("hvc1") || entry.equals("hev1")) && track.sampleCount() > 0
                && track.width() > 0 && track.height() > 0 && !track.references().containsKey("auxl")
                && !track.references().containsKey("thmb");
    }

    /**
     * Returns whether an image item has an alpha plane: an alpha auxiliary image, or for a grid
     * one alpha auxiliary image per tile.
     *
     * @param file the parsed file
     * @param item the image item
     * @return {@code true} when the decoders attach an alpha plane
     */
    private static boolean hasAlpha(HeifFile file, HeifItem item) {
        if (hasAlphaItem(file, item)) {
            return true;
        }
        var inputs = item.references("dimg");
        if (!item.type().equals("grid") || inputs.length == 0) {
            return false;
        }
        for (var id : inputs) {
            var tile = file.item(id);
            if (tile == null || !hasAlphaItem(file, tile)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether an auxiliary item with the alpha URN references an item.
     *
     * @param file the parsed file
     * @param item the auxiliary item to check
     * @return {@code true} when such an item exists
     */
    private static boolean hasAlphaItem(HeifFile file, HeifItem item) {
        for (var auxiliary : file.auxiliariesOf(item.id())) {
            var type = auxiliary.property(HeifProperty.AuxiliaryType.class);
            if (type != null && type.isAlpha()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the bit depth that a list of HEIF properties declares.
     *
     * @param properties the properties of an item or a sample entry
     * @return the first {@code pixi} depth, or the depth of the AV1 or HEVC configuration, or
     * {@code 0} when neither tells
     */
    private static int depthOf(List<HeifProperty> properties) {
        for (var property : properties) {
            if (property instanceof HeifProperty.PixelInformation pixels && pixels.depths().length > 0) {
                return pixels.depths()[0];
            }
        }
        for (var property : properties) {
            var info = switch (property) {
                case HeifProperty.Av1Configuration config -> VideoConfigs.describe(Codec.AV1, config.record());
                case HeifProperty.HevcConfiguration config -> VideoConfigs.describe(Codec.HEVC, config.record());
                default -> null;
            };
            if (info != null) {
                return info.bitDepth();
            }
        }
        return 0;
    }
}

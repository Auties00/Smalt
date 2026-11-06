package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.tiff.Ifd;
import com.github.auties00.smalt.internal.tiff.TiffHeader;
import com.github.auties00.smalt.internal.tiff.TiffInput;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Describes a DNG or camera raw file from its directories, without decoding the sensor data.
 *
 * <p>The camera and the shot are read from the TIFF directories of the TIFF-structured formats
 * (DNG, CR2, NEF, ARW, PEF, ORF, RW2 and the others, whose signature need not be the TIFF one),
 * from the {@code CMT1} and {@code CMT2} TIFF blocks of a Canon CR3 file, from the TIFF block of
 * a Minolta MRW file, from the CIFF records of a Canon CRW file, and from the EXIF block of the
 * preview JPEG a Fujifilm RAF file or the largest preview JPEG a Sigma X3F file embeds. They become
 * the tags {@code make}, {@code model}, {@code lens}, {@code iso}, {@code exposure_time} (seconds, as
 * a fraction such as {@code 1/125} or a decimal), {@code f_number}, {@code focal_length}
 * (millimeters) and {@code creation_time} (ISO 8601, from {@code DateTimeOriginal}). A directory that
 * cannot be read is skipped.
 *
 * <p>The size is that of the picture the raw decoder returns when a decoder is present (the
 * default crop of the sensor image, scaled to square pixels); without one it is the default crop
 * of a DNG file, the image size of an MRW, CRW or X3F file, or otherwise the EXIF pixel dimensions or
 * the largest image the directories describe. The bit depth is the stored depth of the sensor samples.
 */
final class RawHeaders {
    /**
     * The most directories walked.
     */
    private static final int MAX_DIRECTORIES = 32;

    /**
     * The most entries of one directory examined.
     */
    private static final int MAX_ENTRIES = 1024;

    /**
     * The longest string value read.
     */
    private static final int MAX_STRING = 256;

    /**
     * The longest XMP packet read from a first directory.
     */
    private static final int MAX_XMP = 64 * 1024;

    /**
     * The largest metadata block (CR3 {@code CMT} box) read into memory.
     */
    private static final int MAX_BLOCK = 4 << 20;

    /**
     * The most boxes of a CR3 file examined.
     */
    private static final int MAX_BOXES = 4096;

    /**
     * How the directories are read: truncated directories keep their whole entries and values of
     * an unknown type count as bytes.
     */
    private static final Ifd.Policy POLICY = new Ifd.Policy(1 << 16, true, 1, MAX_BLOCK);

    /**
     * The kind of an image file directory.
     */
    private static final int MAIN = 0;

    /**
     * The kind of an EXIF directory.
     */
    private static final int EXIF = 1;

    /**
     * The UUID of the CR3 box holding the metadata blocks.
     */
    private static final byte[] CANON_UUID = {(byte) 0x85, (byte) 0xC0, (byte) 0xB6, (byte) 0x87, (byte) 0x82, 0x0F,
            0x11, (byte) 0xE0, (byte) 0x81, 0x11, (byte) 0xF4, (byte) 0xCE, 0x46, 0x2B, 0x6A, 0x48};

    /**
     * Prevents instantiation.
     */
    private RawHeaders() {
        throw new AssertionError();
    }

    /**
     * Describes a DNG or camera raw file.
     *
     * @param source the input
     * @return the header, whose size is zero when nothing gives one
     * @throws MediaException.Malformed if the raw decoder finds the file corrupt
     * @throws MediaException.Io if reading the source fails
     */
    static ImageHeaders.Header read(MediaSource source) {
        var result = new ImageHeaders.Header();
        var state = new State();
        var head = new byte[16];
        var length = SourceBytes.readAvailable(source, 0, head, 0, head.length);
        if (length >= 15 && new String(head, 0, 15, StandardCharsets.ISO_8859_1).equals("FUJIFILMCCD-RAW")) {
            raf(source, state);
        } else if (length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            cr3(source, state);
        } else if (length >= 8 && head[0] == 0 && head[1] == 'M' && head[2] == 'R' && head[3] == 'M') {
            mrw(source, state);
        } else if (length >= 14 && (head[0] == 'I' || head[0] == 'M') && head[1] == head[0]
                && new String(head, 6, 8, StandardCharsets.ISO_8859_1).equals("HEAPCCDR")) {
            crw(source, state);
        } else if (length >= 4 && head[0] == 'F' && head[1] == 'O' && head[2] == 'V' && head[3] == 'b') {
            x3f(source, state);
        } else if (length >= 8 && ((head[0] == 'I' && head[1] == 'I') || (head[0] == 'M' && head[1] == 'M'))) {
            tiff(TiffInput.of(source), state, MAIN);
        }
        result.orientation = Orientation.ofExif(state.orientation);
        result.bitDepth = state.bitDepth;
        result.tags = state.tags();
        try {
            if (Images.isDecodable(ImageFormat.RAW)) {
                var info = Images.probe(source);
                result.width = info.width();
                result.height = info.height();
                result.orientation = info.orientation();
                return result;
            }
        } catch (MediaException.Unsupported exception) {
            // the decoder does not handle this variant: fall back to the directories
        }
        if (state.cropWidth > 0 && state.cropHeight > 0) {
            result.width = state.cropWidth;
            result.height = state.cropHeight;
        } else if (state.pixelWidth > 0 && state.pixelHeight > 0) {
            result.width = state.pixelWidth;
            result.height = state.pixelHeight;
        } else {
            result.width = state.largestWidth;
            result.height = state.largestHeight;
        }
        return result;
    }

    /**
     * Collects what the directories say; a zero or {@code null} field is unknown.
     */
    private static final class State {
        String make;

        String model;

        String lens;

        /**
         * The {@code tiff:Make} of the first directory's XMP packet, used when no directory has a make tag.
         */
        String xmpMake;

        /**
         * The {@code tiff:Model} of the first directory's XMP packet, used when no directory has a model tag.
         */
        String xmpModel;

        /**
         * The capture time, as EXIF writes it ({@code YYYY:MM:DD HH:MM:SS}).
         */
        String dateTimeOriginal;

        /**
         * The numerator of the exposure time, in seconds.
         */
        long exposureNumerator;

        long exposureDenominator;

        double fNumber;

        /**
         * The focal length, in millimeters.
         */
        double focalLength;

        long iso;

        /**
         * The ISO speed a Panasonic RW2 file holds in tag 23 of its first directory.
         */
        long panasonicIso;

        /**
         * The EXIF orientation code, 1 when absent.
         */
        int orientation = 1;

        /**
         * The bits per sample of the largest image.
         */
        int bitDepth;

        int largestWidth;

        int largestHeight;

        int pixelWidth;

        int pixelHeight;

        /**
         * The DNG default crop width scaled by the default scale.
         */
        int cropWidth;

        int cropHeight;

        /**
         * {@return the normalized tags, unmodifiable}
         */
        Map<String, String> tags() {
            var tags = new LinkedHashMap<String, String>();
            var make = this.make != null && !this.make.isBlank() ? this.make : xmpMake;
            var model = this.model != null && !this.model.isBlank() ? this.model : xmpModel(make);
            if (make != null && !make.isBlank()) {
                tags.put("make", make.strip());
            }
            if (model != null && !model.isBlank()) {
                tags.put("model", model.strip());
            }
            if (lens != null && !lens.isBlank()) {
                tags.put("lens", lens.strip());
            }
            var speed = iso > 0 ? iso : panasonicIso;
            if (speed > 0) {
                tags.put("iso", Long.toString(speed));
            }
            if (exposureNumerator > 0 && exposureDenominator > 0) {
                tags.put("exposure_time", exposure(exposureNumerator, exposureDenominator));
            }
            if (fNumber > 0) {
                tags.put("f_number", decimal(fNumber));
            }
            if (focalLength > 0) {
                tags.put("focal_length", decimal(focalLength));
            }
            if (dateTimeOriginal != null && dateTimeOriginal.length() >= 19) {
                var date = dateTimeOriginal;
                tags.put("creation_time", date.substring(0, 4) + "-" + date.substring(5, 7) + "-" + date.substring(8, 10)
                        + "T" + date.substring(11, 19));
            }
            return Map.copyOf(tags);
        }

        /**
         * Returns the camera name of the XMP {@code tiff:Model}: the text before a serial number in parentheses,
         * without a leading maker name, as a Leaf MOS file writes {@code Leaf Aptus 22(LF10043)/Mamiya 645 AFD}.
         *
         * @param make the maker name, or {@code null}
         * @return the name, or {@code null} without one
         */
        private String xmpModel(String make) {
            if (xmpModel == null) {
                return null;
            }
            var name = xmpModel;
            var serial = name.indexOf('(');
            if (serial >= 0) {
                name = name.substring(0, serial);
            }
            name = name.strip();
            if (make != null && !make.isBlank() && name.startsWith(make.strip() + " ")) {
                name = name.substring(make.strip().length()).strip();
            }
            return name.isEmpty() ? null : name;
        }
    }

    /**
     * Formats an exposure time as a reduced fraction of a second when shorter than one, a
     * decimal number of seconds otherwise.
     *
     * @param numerator the numerator, positive
     * @param denominator the denominator, positive
     * @return the text, such as {@code 1/125} or {@code 2.5}
     */
    private static String exposure(long numerator, long denominator) {
        if (numerator >= denominator) {
            return decimal((double) numerator / denominator);
        }
        if (denominator % numerator == 0) {
            return "1/" + denominator / numerator;
        }
        return "1/" + decimal((double) denominator / numerator);
    }

    /**
     * {@return a number with at most two decimals and no trailing zeros}
     *
     * @param value the number to format
     */
    private static String decimal(double value) {
        var rounded = Math.round(value * 100) / 100.0;
        if (rounded == Math.rint(rounded)) {
            return Long.toString((long) rounded);
        }
        var text = Double.toString(rounded);
        return text.endsWith("0") ? text.substring(0, text.length() - 1) : text;
    }

    /**
     * Walks the directories of a classic TIFF structure at the start of an input, whatever its
     * signature (ORF and RW2 files use their own version field).
     *
     * @param input the bytes holding the structure, its byte order mark first
     * @param state the collected values
     * @param firstKind {@link #MAIN}, or {@link #EXIF} for a CR3 {@code CMT2} block whose first
     * directory holds EXIF tags
     */
    private static void tiff(TiffInput input, State state, int firstKind) {
        var head = new byte[8];
        if (input.readAvailable(0, head, 0, head.length) < head.length) {
            return;
        }
        var little = head[0] == 'I';
        var header = new TiffHeader(little, false, ByteArrays.u32(head, 4, little));
        var pending = new long[MAX_DIRECTORIES];
        var kinds = new int[MAX_DIRECTORIES];
        var visited = new HashSet<Long>();
        var count = 0;
        pending[count] = header.firstIfd();
        kinds[count++] = firstKind;
        while (count > 0 && visited.size() < MAX_DIRECTORIES) {
            count--;
            var offset = pending[count];
            var kind = kinds[count];
            if (offset < 8 || !visited.add(offset)) {
                continue;
            }
            Ifd ifd;
            try {
                ifd = Ifd.read(input, 0, header, offset, POLICY);
            } catch (MediaException.Malformed exception) {
                continue;
            }
            var entries = Math.min(ifd.count(), MAX_ENTRIES);
            var firstDirectory = visited.size() == 1;
            var width = 0L;
            var height = 0L;
            var bits = 0L;
            var subfile = 0L;
            var cropWidth = 0.0;
            var cropHeight = 0.0;
            var scaleH = 1.0;
            var scaleV = 1.0;
            for (var index = 0; index < entries; index++) {
                var tag = ifd.tag(index);
                var type = ifd.type(index);
                var pointer = ifd.valueOffset(index);
                // the first value as the field holds it: a SHORT sits in the first two bytes
                var first = type == 3 ? (little ? pointer & 0xFFFF : pointer >>> 16) : pointer;
                if (kind == EXIF) {
                    switch (tag) {
                        case 0x829A -> {
                            var rational = rational(input, ifd, index);
                            state.exposureNumerator = rational[0];
                            state.exposureDenominator = rational[1];
                        }
                        case 0x829D -> state.fNumber = ratio(rational(input, ifd, index));
                        case 0x8827 -> state.iso = state.iso == 0 ? first : state.iso;
                        case 0x9003 -> state.dateTimeOriginal = string(input, ifd, index);
                        case 0x920A -> state.focalLength = ratio(rational(input, ifd, index));
                        case 0xA002 -> state.pixelWidth = (int) Math.min(first, Integer.MAX_VALUE);
                        case 0xA003 -> state.pixelHeight = (int) Math.min(first, Integer.MAX_VALUE);
                        case 0xA434 -> state.lens = string(input, ifd, index);
                        default -> {
                        }
                    }
                    continue;
                }
                switch (tag) {
                    case 0xFE -> subfile = first;
                    case 0x100 -> width = first;
                    case 0x101 -> height = first;
                    case 0x102 -> bits = ifd.isInline(index) ? first : readU16(input, pointer, little);
                    case 0x10F -> state.make = state.make == null ? string(input, ifd, index) : state.make;
                    case 0x110 -> state.model = state.model == null ? string(input, ifd, index) : state.model;
                    case 0x112 -> state.orientation = firstDirectory ? (int) first : state.orientation;
                    case 0x17 -> state.panasonicIso = firstDirectory ? first : state.panasonicIso;
                    case 0x14A -> {
                        if (type == 4 || type == 13) {
                            var children = (int) Math.min(ifd.valueCount(index), MAX_DIRECTORIES);
                            for (var child = 0; child < children && count < pending.length; child++) {
                                pending[count] = children == 1 ? pointer : readU32(input, pointer + 4L * child, little);
                                kinds[count++] = MAIN;
                            }
                        }
                    }
                    case 0x8769 -> {
                        if (count < pending.length) {
                            pending[count] = pointer;
                            kinds[count++] = EXIF;
                        }
                    }
                    case 0x8827 -> state.iso = first;
                    case 0x2BC -> {
                        if (firstDirectory && state.xmpModel == null) {
                            xmp(input, ifd, index, state);
                        }
                    }
                    case 0xC620 -> {
                        var size = numbers(input, ifd, index);
                        cropWidth = size[0];
                        cropHeight = size[1];
                    }
                    case 0xC61E -> {
                        var scale = numbers(input, ifd, index);
                        scaleH = scale[0];
                        scaleV = scale[1];
                    }
                    default -> {
                    }
                }
            }
            if (width > 0 && height > 0 && width * height > (long) state.largestWidth * state.largestHeight
                    && width <= Integer.MAX_VALUE && height <= Integer.MAX_VALUE) {
                state.largestWidth = (int) width;
                state.largestHeight = (int) height;
                state.bitDepth = (int) Math.min(bits, 32);
            }
            if (subfile == 0 && cropWidth > 0 && cropHeight > 0 && scaleH > 0 && scaleV > 0) {
                var smaller = Math.min(scaleH, scaleV);
                var scaledWidth = cropWidth * scaleH / smaller;
                var scaledHeight = cropHeight * scaleV / smaller;
                if (scaledWidth < Integer.MAX_VALUE && scaledHeight < Integer.MAX_VALUE) {
                    state.cropWidth = (int) Math.round(scaledWidth);
                    state.cropHeight = (int) Math.round(scaledHeight);
                }
            }
            if (kind == MAIN && entries > 0 && ifd.next() > 0 && count < pending.length) {
                pending[count] = ifd.next();
                kinds[count++] = MAIN;
            }
        }
    }

    /**
     * Reads the {@code CMT1} and {@code CMT2} TIFF blocks of a Canon CR3 file.
     *
     * @param source the CR3 file
     * @param state the collected values
     */
    private static void cr3(MediaSource source, State state) {
        var size = source.size();
        var header = new byte[16];
        var position = 0L;
        var moovEnd = -1L;
        for (var step = 0; step < MAX_BOXES; step++) {
            if (SourceBytes.readAvailable(source, position, header, 0, 8) < 8) {
                return;
            }
            var length = ByteArrays.u32be(header, 0);
            var type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
            var body = position + 8;
            if (length == 1) {
                if (SourceBytes.readAvailable(source, position + 8, header, 0, 8) < 8) {
                    return;
                }
                length = ByteArrays.i64be(header, 0);
                body += 8;
            } else if (length == 0) {
                length = size < 0 ? Long.MAX_VALUE / 2 : size - position;
            }
            if (length < body - position) {
                return;
            }
            switch (type) {
                case "moov" -> {
                    moovEnd = position + length;
                    position = body;
                    continue;
                }
                case "uuid" -> {
                    var uuid = new byte[16];
                    if (SourceBytes.readAvailable(source, body, uuid, 0, 16) == 16 && Arrays.equals(uuid, CANON_UUID)) {
                        position = body + 16;
                        continue;
                    }
                }
                case "CMT1", "CMT2" -> {
                    var block = length - (body - position);
                    if (block > 8 && block <= MAX_BLOCK) {
                        var bytes = new byte[(int) block];
                        if (SourceBytes.readAvailable(source, body, bytes, 0, bytes.length) == bytes.length) {
                            tiff(TiffInput.of(bytes), state, type.equals("CMT2") ? EXIF : MAIN);
                        }
                    }
                }
                default -> {
                }
            }
            position += length;
            if (moovEnd > 0 && position >= moovEnd) {
                return;
            }
        }
    }

    /**
     * Reads the camera, the shot, the bit depth and the image size of a Canon CRW file from the
     * records of its CIFF heap: the maker and model ({@code 0x080A}), the rotation
     * ({@code 0x1810}), the capture time ({@code 0x180E}), the shot settings ({@code 0x102A}), the
     * camera settings ({@code 0x102D}) and focal length ({@code 0x1029}), the bits of a sample
     * ({@code 0x1030}) and the image edges of the sensor geometry ({@code 0x1031}).
     *
     * @param source the input
     * @param state the description to fill
     */
    private static void crw(MediaSource source, State state) {
        var size = source.size();
        var header = new byte[26];
        if (size < 0 || SourceBytes.readAvailable(source, 0, header, 0, header.length) < header.length) {
            return;
        }
        var little = header[0] == 'I';
        var records = new HashMap<Integer, byte[]>();
        crwHeap(source, ByteArrays.u32(header, 2, little), size, little, 0, records, new int[]{MAX_ENTRIES});
        var names = records.get(0x080A);
        if (names != null) {
            var parts = new String(names, StandardCharsets.ISO_8859_1).split("\0");
            state.make = parts.length > 0 ? parts[0] : null;
            state.model = parts.length > 1 ? parts[1] : null;
        }
        var spec = records.get(0x1810);
        if (spec != null && spec.length >= 16) {
            state.orientation = switch ((int) ByteArrays.u32(spec, 12, little)) {
                case 90 -> 6;
                case 180 -> 3;
                case 270 -> 8;
                default -> 1;
            };
        }
        var time = records.get(0x180E);
        if (time != null && time.length >= 12) {
            var seconds = ByteArrays.u32(time, 0, little);
            if ((ByteArrays.u32(time, 8, little) & 0x8000_0000L) != 0) {
                seconds -= (int) ByteArrays.u32(time, 4, little);
            }
            if (seconds > 0) {
                state.dateTimeOriginal = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT));
            }
        }
        var shot = crwWords(records.get(0x102A), little);
        if (shot.length > 5) {
            if (shot[2] > 0) {
                state.iso = Math.round(100 * StrictMath.pow(2, (shot[2] - 160 + shot[1]) / 32.0));
            }
            if (shot[4] != Short.MIN_VALUE && shot[4] != 0) {
                state.fNumber = StrictMath.pow(2, shot[4] / 64.0);
            }
            if (shot[5] != Short.MIN_VALUE && shot[5] != 0) {
                state.exposureNumerator = 1_000_000;
                state.exposureDenominator = Math.max(1, Math.round(1_000_000 * StrictMath.pow(2, shot[5] / 32.0)));
            }
        }
        var camera = crwWords(records.get(0x102D), little);
        var units = camera.length > 25 && camera[25] > 0 ? camera[25] : 1.0;
        var focal = crwWords(records.get(0x1029), little);
        if (focal.length > 1 && focal[1] != 0) {
            state.focalLength = (focal[1] & 0xFFFF) / units;
        }
        var sample = crwWords(records.get(0x1030), little);
        state.bitDepth = sample.length > 5 && sample[5] == 10 ? 10 : 12;
        var info = crwWords(records.get(0x1031), little);
        if (info.length > 8) {
            var width = (info[7] & 0xFFFF) - (info[5] & 0xFFFF) + 1;
            var height = (info[8] & 0xFFFF) - (info[6] & 0xFFFF) + 1;
            if (width > 1 && height > 1) {
                state.cropWidth = width;
                state.cropHeight = height;
            }
        }
    }

    /**
     * Reads the directory of a CIFF heap, keeping the value of the first record of each kind and
     * following sub-heaps; a damaged heap is skipped.
     *
     * @param source the input
     * @param start the absolute offset of the heap
     * @param end the absolute offset after the heap
     * @param little whether integers are little endian
     * @param depth the nesting of the heap
     * @param records the values found, by record kind
     * @param budget the directory entries left to examine, in its only element
     */
    private static void crwHeap(MediaSource source, long start, long end, boolean little, int depth,
                                Map<Integer, byte[]> records, int[] budget) {
        var word = new byte[4];
        if (depth > 8 || end - start < 6 || SourceBytes.readAvailable(source, end - 4, word, 0, 4) < 4) {
            return;
        }
        var directory = start + ByteArrays.u32(word, 0, little);
        if (directory > end - 6 || SourceBytes.readAvailable(source, directory, word, 0, 2) < 2) {
            return;
        }
        var count = ByteArrays.u16(word, 0, little);
        if (count > budget[0] || directory + 2 + 10L * count > end - 4) {
            return;
        }
        budget[0] -= count;
        var entries = new byte[10 * count];
        if (SourceBytes.readAvailable(source, directory + 2, entries, 0, entries.length) < entries.length) {
            return;
        }
        for (var index = 0; index < count; index++) {
            var at = index * 10;
            var tag = ByteArrays.u16(entries, at, little);
            var kind = tag & 0x3FFF;
            if ((tag & 0xC000) == 0x4000) {
                records.putIfAbsent(kind, Arrays.copyOfRange(entries, at + 2, at + 10));
                continue;
            }
            var length = ByteArrays.u32(entries, at + 2, little);
            var offset = start + ByteArrays.u32(entries, at + 6, little);
            if ((tag & 0xC000) != 0 || offset > directory || length > directory - offset) {
                continue;
            }
            var format = kind & 0x3800;
            if (format == 0x2800 || format == 0x3000) {
                crwHeap(source, offset, offset + length, little, depth + 1, records, budget);
            } else if (length <= MAX_STRING && !records.containsKey(kind)) {
                var value = new byte[(int) length];
                if (SourceBytes.readAvailable(source, offset, value, 0, value.length) == value.length) {
                    records.put(kind, value);
                }
            }
        }
    }

    /**
     * {@return the signed 16-bit words of a CIFF record, or an empty array}
     *
     * @param value the record's value, or {@code null}
     * @param little whether integers are little endian
     */
    private static short[] crwWords(byte[] value, boolean little) {
        if (value == null) {
            return new short[0];
        }
        var words = new short[value.length / 2];
        for (var index = 0; index < words.length; index++) {
            words[index] = (short) ByteArrays.u16(value, index * 2, little);
        }
        return words;
    }

    /**
     * Reads the camera and the shot of a Minolta MRW file from the TIFF structure of its
     * {@code "\0TTW"} block, and the bit depth and the image size from its {@code "\0PRD"} block.
     *
     * @param source the input
     * @param state the description to fill
     */
    private static void mrw(MediaSource source, State state) {
        var header = new byte[8];
        if (SourceBytes.readAvailable(source, 0, header, 0, header.length) < header.length) {
            return;
        }
        var end = 8 + ByteArrays.u32be(header, 4);
        var position = 8L;
        var block = new byte[24];
        for (var count = 0; count < MAX_BOXES && position + 8 <= end; count++) {
            if (SourceBytes.readAvailable(source, position, block, 0, 8) < 8) {
                return;
            }
            var name = ByteArrays.u32be(block, 0);
            var length = ByteArrays.u32be(block, 4);
            var body = position + 8;
            if (length > end - body) {
                return;
            }
            if (name == 0x00505244 && length >= 24 && SourceBytes.readAvailable(source, body, block, 0, 24) == 24) {
                state.bitDepth = block[17] & 0xFF;
                state.cropHeight = ByteArrays.u16be(block, 12);
                state.cropWidth = ByteArrays.u16be(block, 14);
            } else if (name == 0x00545457 && length >= 8 && length <= MAX_BLOCK) {
                var tiff = new byte[(int) length];
                if (SourceBytes.readAvailable(source, body, tiff, 0, tiff.length) == tiff.length) {
                    var depth = state.bitDepth;
                    tiff(TiffInput.of(tiff), state, MAIN);
                    state.bitDepth = depth;
                }
            }
            position = body + length;
        }
    }

    /**
     * Reads the camera and the shot of a Sigma X3F file from the EXIF block of its largest preview
     * JPEG, and the image size from its header (format versions before 4.0).
     *
     * @param source the X3F file
     * @param state the collected values
     */
    private static void x3f(MediaSource source, State state) {
        var size = source.size();
        var header = new byte[36];
        var tail = new byte[4];
        if (size < 48 || SourceBytes.readAvailable(source, 0, header, 0, header.length) < header.length
                || SourceBytes.readAvailable(source, size - 4, tail, 0, 4) < 4) {
            return;
        }
        if (ByteArrays.u32(header, 4, true) < 0x40000) {
            state.cropWidth = (int) Math.min(ByteArrays.u32(header, 28, true), Integer.MAX_VALUE);
            state.cropHeight = (int) Math.min(ByteArrays.u32(header, 32, true), Integer.MAX_VALUE);
        }
        var directory = ByteArrays.u32(tail, 0, true);
        var head = new byte[12];
        if (directory > size - 12 || SourceBytes.readAvailable(source, directory, head, 0, 12) < 12
                || head[0] != 'S' || head[1] != 'E' || head[2] != 'C' || head[3] != 'd') {
            return;
        }
        var count = ByteArrays.u32(head, 8, true);
        if (count > MAX_BOXES || directory + 12 + 12 * count > size) {
            return;
        }
        var entries = new byte[(int) (12 * count)];
        if (SourceBytes.readAvailable(source, directory + 12, entries, 0, entries.length) < entries.length) {
            return;
        }
        var bestOffset = -1L;
        var bestLength = 0L;
        var bestArea = -1L;
        var section = new byte[28];
        for (var index = 0; index < count; index++) {
            var offset = ByteArrays.u32(entries, 12 * index, true);
            var length = ByteArrays.u32(entries, 12 * index + 4, true);
            if (length < 28 || offset > size - length
                    || SourceBytes.readAvailable(source, offset, section, 0, 28) < 28) {
                continue;
            }
            var area = ByteArrays.u32(section, 16, true) * ByteArrays.u32(section, 20, true);
            if (section[0] == 'S' && section[1] == 'E' && section[2] == 'C' && section[3] == 'i'
                    && ByteArrays.u32(section, 8, true) == 2 && ByteArrays.u32(section, 12, true) == 18
                    && area > bestArea) {
                bestOffset = offset + 28;
                bestLength = length - 28;
                bestArea = area;
            }
        }
        if (bestOffset > 0) {
            var crop = new int[]{state.cropWidth, state.cropHeight};
            jpegExif(source, bestOffset, bestLength, state);
            state.bitDepth = 0;
            state.cropWidth = crop[0];
            state.cropHeight = crop[1];
        }
    }

    /**
     * Reads the EXIF block of the preview JPEG of a Fujifilm RAF file.
     *
     * @param source the RAF file
     * @param state the collected values
     */
    private static void raf(MediaSource source, State state) {
        var header = new byte[92];
        if (SourceBytes.readAvailable(source, 0, header, 0, header.length) < header.length) {
            return;
        }
        var jpegOffset = ByteArrays.u32be(header, 84);
        var jpegLength = ByteArrays.u32be(header, 88);
        var make = "FUJIFILM";
        state.make = make;
        var model = new String(header, 28, 32, StandardCharsets.ISO_8859_1);
        var end = model.indexOf('\0');
        state.model = end >= 0 ? model.substring(0, end) : model;
        if (jpegLength < 4) {
            return;
        }
        var camera = state.model;
        jpegExif(source, jpegOffset, jpegLength, state);
        state.model = camera;
        state.make = make;
    }

    /**
     * Reads the EXIF block of an embedded JPEG stream into the collected values, leaving out the
     * size of the images it describes.
     *
     * @param source the file
     * @param offset the offset of the JPEG stream
     * @param length the length of the stream
     * @param state the collected values
     */
    private static void jpegExif(MediaSource source, long offset, long length, State state) {
        var limit = (int) Math.min(length, 1 << 16);
        var jpeg = new byte[limit];
        var read = SourceBytes.readAvailable(source, offset, jpeg, 0, limit);
        var position = 2;
        while (position + 4 <= read && (jpeg[position] & 0xFF) == 0xFF) {
            var marker = jpeg[position + 1] & 0xFF;
            var size = ByteArrays.u16be(jpeg, position + 2);
            if (marker == 0xE1 && size >= 8 && position + 2 + size <= read && jpeg[position + 4] == 'E'
                    && jpeg[position + 5] == 'x' && jpeg[position + 6] == 'i' && jpeg[position + 7] == 'f') {
                tiff(TiffInput.of(Arrays.copyOfRange(jpeg, position + 10, position + 2 + size)), state, MAIN);
                state.largestWidth = 0;
                state.largestHeight = 0;
                return;
            }
            if (marker == 0xDA || size < 2) {
                return;
            }
            position += 2 + size;
        }
    }

    /**
     * Reads the {@code tiff:Make} and {@code tiff:Model} elements of an XMP packet entry.
     *
     * @param input the bytes holding the structure
     * @param ifd the directory
     * @param index the entry index
     * @param state the collected values
     */
    private static void xmp(TiffInput input, Ifd ifd, int index, State state) {
        var bytes = new byte[(int) Math.min(ifd.valueCount(index), MAX_XMP)];
        var count = input.readAvailable(ifd.valuePosition(index), bytes, 0, bytes.length);
        var text = new String(bytes, 0, Math.max(count, 0), StandardCharsets.UTF_8);
        state.xmpMake = element(text, "tiff:Make");
        state.xmpModel = element(text, "tiff:Model");
    }

    /**
     * Returns the text of the first element of a name, at most {@link #MAX_STRING} characters.
     *
     * @param text the XML text
     * @param name the qualified element name
     * @return the element's text, or {@code null} without one
     */
    private static String element(String text, String name) {
        var open = text.indexOf("<" + name + ">");
        if (open < 0) {
            return null;
        }
        var start = open + name.length() + 2;
        var close = text.indexOf("</" + name + ">", start);
        if (close < 0) {
            return null;
        }
        var value = text.substring(start, Math.min(close, start + MAX_STRING)).strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * Reads the string value of an entry.
     *
     * @param input the bytes holding the structure
     * @param ifd the directory
     * @param index the entry index
     * @return the string up to its first NUL, at most {@link #MAX_STRING} characters
     */
    private static String string(TiffInput input, Ifd ifd, int index) {
        var bytes = new byte[(int) Math.min(ifd.valueCount(index), MAX_STRING)];
        var count = input.readAvailable(ifd.valuePosition(index), bytes, 0, bytes.length);
        var end = 0;
        while (end < count && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, StandardCharsets.ISO_8859_1);
    }

    /**
     * Reads the first value of a {@code RATIONAL} or {@code SRATIONAL} entry.
     *
     * @param input the bytes holding the structure
     * @param ifd the directory
     * @param index the entry index
     * @return the numerator and denominator, both 0 for another type or when unreadable
     */
    private static long[] rational(TiffInput input, Ifd ifd, int index) {
        var type = ifd.type(index);
        if (type != 5 && type != 10) {
            return new long[2];
        }
        var bytes = new byte[8];
        if (input.readAvailable(ifd.valueOffset(index), bytes, 0, 8) < 8) {
            return new long[2];
        }
        var little = ifd.header().little();
        var numerator = ByteArrays.u32(bytes, 0, little);
        var denominator = ByteArrays.u32(bytes, 4, little);
        if (type == 10) {
            numerator = (int) numerator;
            denominator = (int) denominator;
        }
        return new long[]{numerator, denominator};
    }

    /**
     * Reads the first two values of a {@code SHORT}, {@code LONG} or {@code RATIONAL} entry.
     *
     * @param input the bytes holding the structure
     * @param ifd the directory
     * @param index the entry index
     * @return the two values, 0 where absent, unreadable or of another type
     */
    private static double[] numbers(TiffInput input, Ifd ifd, int index) {
        var result = new double[2];
        var type = ifd.type(index);
        var little = ifd.header().little();
        var size = type == 3 ? 2 : type == 4 ? 4 : type == 5 ? 8 : 0;
        var values = (int) Math.min(ifd.valueCount(index), result.length);
        if (size == 0) {
            return result;
        }
        var bytes = new byte[size * values];
        var position = ifd.isInline(index) ? ifd.valuePosition(index) : ifd.valueOffset(index);
        if (input.readAvailable(position, bytes, 0, bytes.length) < bytes.length) {
            return result;
        }
        for (var value = 0; value < values; value++) {
            result[value] = switch (type) {
                case 3 -> ByteArrays.u16(bytes, 2 * value, little);
                case 4 -> ByteArrays.u32(bytes, 4 * value, little);
                default -> ratio(new long[]{ByteArrays.u32(bytes, 8 * value, little),
                        ByteArrays.u32(bytes, 8 * value + 4, little)});
            };
        }
        return result;
    }

    /**
     * {@return a fraction as a number, 0 for a zero denominator}
     *
     * @param rational the numerator and denominator
     */
    private static double ratio(long[] rational) {
        return rational[1] == 0 ? 0 : (double) rational[0] / rational[1];
    }

    /**
     * {@return the unsigned 16-bit integer at a position, 0 when unreadable}
     *
     * @param input the bytes to read
     * @param position the absolute position
     * @param little whether the integer is little endian
     */
    private static long readU16(TiffInput input, long position, boolean little) {
        var bytes = new byte[2];
        return input.readAvailable(position, bytes, 0, 2) == 2 ? ByteArrays.u16(bytes, 0, little) : 0;
    }

    /**
     * {@return the unsigned 32-bit integer at a position, 0 when unreadable}
     *
     * @param input the bytes to read
     * @param position the absolute position
     * @param little whether the integer is little endian
     */
    private static long readU32(TiffInput input, long position, boolean little) {
        var bytes = new byte[4];
        return input.readAvailable(position, bytes, 0, 4) == 4 ? ByteArrays.u32(bytes, 0, little) : 0;
    }
}

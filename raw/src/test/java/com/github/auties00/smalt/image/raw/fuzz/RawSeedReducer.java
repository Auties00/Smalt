package com.github.auties00.smalt.image.raw.fuzz;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawImage;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reduces a camera raw file of a TIFF-structured vendor format to a small seed of the same format: the sensor image
 * keeps its width and its first rows, every large embedded JPEG becomes a tiny one, and the bytes no longer needed are
 * cut out with every TIFF offset that follows them moved down.
 *
 * <p>The reduced file is checked against the same file before its sensor data is cut: both must decode, render and
 * yield a preview, to the same sensor samples, color data and rendered pixels, so the cut keeps every byte the
 * decoder reads. Unreferenced stretches between the parts of the file are cut too when the decoded result does not
 * change. Maker notes are copied as opaque blocks that do not move, which holds for the samples used because
 * every maker note lies before the data that is cut.
 */
final class RawSeedReducer {
    /** An embedded JPEG at least this long is replaced. */
    private static final int LARGE = 16 * 1024;

    /** The vendor layouts the reducer knows. */
    enum Layout {
        /** Nikon NEF: the CFA image is the largest sub-directory. */
        NEF,
        /** Pentax PEF: the CFA image is the first directory. */
        PEF,
        /** Sony ARW: the CFA image is the largest sub-directory; tag 0xC634 is an offset. */
        ARW,
        /** Olympus ORF: the CFA image is the first directory. */
        ORF,
        /** Panasonic RW2: the sensor size is in tags 2 and 3 and its data at the offset of tag 0x118. */
        RW2,
        /** Canon CR2: the lossless JPEG of the fourth directory, sized by its frame header. */
        CR2,
        /** Fujifilm RAF, uncompressed: a header pointing to a JPEG, a record list and a TIFF sensor block. */
        RAF,
        /** Canon CR3: an ISO base media file whose tracks and preview box hold JPEGs and CRX data. */
        CR3
    }

    private RawSeedReducer() {
        throw new AssertionError();
    }

    /**
     * Reduces a raw file.
     *
     * @param original the raw file
     * @param layout the vendor layout
     * @param rows the sensor rows to keep
     * @param jpeg the tiny JPEG that replaces the large embedded ones
     * @return the reduced file
     * @throws IllegalStateException if the reduced file does not decode as the uncut one does
     */
    static byte[] reduce(byte[] original, Layout layout, int rows, byte[] jpeg) {
        if (layout == Layout.RAF) {
            return reduceRaf(original, rows, jpeg);
        }
        if (layout == Layout.CR3) {
            return reduceCr3(original, jpeg);
        }
        var compression = RawDecoder.info(MediaSource.of(original)).compression();
        IllegalStateException failure = null;
        // the data length a compressed image claims, in multiples of its share of the rows kept: decoders that infer
        // the layout from the length read the data as uncompressed when it is too long
        for (var slack : new double[]{2, 1.25, 1.05}) {
            try {
                return reduce(original, layout, rows, jpeg, slack, compression);
            } catch (IllegalStateException exception) {
                failure = exception;
            }
        }
        throw failure;
    }

    /**
     * Reduces an uncompressed RAF file: the JPEG keeps its application segments (the EXIF metadata) around the tiny
     * picture, the sensor block keeps its first rows, and the header offsets follow.
     */
    private static byte[] reduceRaf(byte[] original, int rows, byte[] jpeg) {
        var jpegOffset = (int) be32(original, 84);
        var jpegLength = (int) be32(original, 88);
        var recordsOffset = (int) be32(original, 92);
        var sensorOffset = (int) be32(original, 100);
        if (!(jpegOffset < recordsOffset && recordsOffset < sensorOffset) || be32(original, 108) != 0) {
            throw new IllegalStateException("not an uncompressed RAF file with the JPEG, records and sensor in order");
        }
        var preview = withSegments(Arrays.copyOfRange(original, jpegOffset, jpegOffset + jpegLength), jpeg);
        var block = Tiff.parse(Arrays.copyOfRange(original, sensorOffset, original.length), Layout.RAF);
        var directory = block.child(block.ifds().getFirst(), 0xF000);
        var height = block.value(directory, 0xF002, 0);
        var dataOffset = block.value(directory, 0xF007, 0);
        var dataLength = block.value(directory, 0xF008, 0);
        if (height <= 0 || dataLength % height != 0) {
            throw new IllegalStateException("the RAF sensor data is not stored in whole rows");
        }
        var kept = dataLength / height * rows;
        block.set(directory, 0xF002, rows);
        block.set(directory, 0xF008, kept);
        var reference = raf(original, jpegOffset, jpegLength, preview, sensorOffset, block.data(), block.data().length);
        var cut = raf(original, jpegOffset, jpegLength, preview, sensorOffset, block.data(), (int) (dataOffset + kept));
        var expected = Decoded.of(reference);
        if (!expected.equals(Decoded.tryOf(cut))) {
            throw new IllegalStateException("the cut RAF file decodes differently");
        }
        return cut;
    }

    private static byte[] raf(byte[] original, int jpegOffset, int jpegLength, byte[] preview, int sensorOffset,
                              byte[] block, int blockLength) {
        var shift = jpegLength - preview.length;
        var out = new byte[sensorOffset - shift + blockLength];
        System.arraycopy(original, 0, out, 0, jpegOffset);
        System.arraycopy(preview, 0, out, jpegOffset, preview.length);
        System.arraycopy(original, jpegOffset + jpegLength, out, jpegOffset + preview.length,
                sensorOffset - jpegOffset - jpegLength);
        System.arraycopy(block, 0, out, sensorOffset - shift, blockLength);
        putBe32(out, 88, preview.length);
        putBe32(out, 92, be32(original, 92) - shift);
        putBe32(out, 100, sensorOffset - shift);
        putBe32(out, 104, blockLength);
        return out;
    }

    /**
     * Returns a JPEG made of the application segments of another JPEG followed by the segments and image of the tiny
     * one.
     */
    private static byte[] withSegments(byte[] original, byte[] tiny) {
        var at = 2;
        while (at + 4 <= original.length && (original[at] & 0xFF) == 0xFF && (original[at + 1] & 0xF0) == 0xE0) {
            at += 2 + ((original[at + 2] & 0xFF) << 8 | original[at + 3] & 0xFF);
        }
        var out = new byte[at + tiny.length - 2];
        System.arraycopy(original, 0, out, 0, at);
        System.arraycopy(tiny, 2, out, at, tiny.length - 2);
        return out;
    }

    /** The bytes a CR3 track other than a JPEG keeps from the start of its sample. */
    private static final int CR3_SAMPLE = 4096;

    /** The UUID prefix of the CR3 preview box. */
    private static final long CR3_PREVIEW = 0xEAF42B5EL;

    /**
     * Reduces a CR3 file, whose CRX sensor data the decoder does not read: the preview box and the JPEG track hold the
     * tiny JPEG, the other tracks keep the start of their sample (a sample of fixed size stays whole), and the chunk
     * offsets and sample sizes follow. The result must describe the camera, the shot and the previews as the original
     * does.
     */
    private static byte[] reduceCr3(byte[] original, byte[] jpeg) {
        var boxes = boxes(original, 0, original.length);
        var tracks = new ArrayList<int[]>();
        var moov = -1;
        for (var box : boxes) {
            if (type(original, box[0]).equals("moov")) {
                moov = box[0];
                collectTracks(original, box[0] + 8, box[0] + box[1], tracks);
            }
        }
        if (moov < 0) {
            throw new IllegalStateException("no moov box");
        }
        var copy = original.clone();
        var samples = new ByteArrayOutputStream();
        var offsets = new long[tracks.size()];
        var order = new ArrayList<Integer>();
        for (var index = 0; index < tracks.size(); index++) {
            order.add(index);
        }
        order.sort(Comparator.comparingLong(index -> be64(original, tracks.get(index)[1])));
        for (var index : order) {
            var track = tracks.get(index);
            var offset = (int) be64(original, track[1]);
            var size = track[3] > 0 ? track[3] : (int) be32(original, track[2]);
            byte[] sample;
            if (isJpeg(original, offset)) {
                sample = jpeg;
            } else if (track[3] > 0) {
                sample = Arrays.copyOfRange(original, offset, offset + size);
            } else {
                sample = Arrays.copyOfRange(original, offset, offset + Math.min(size, CR3_SAMPLE));
            }
            if (track[3] == 0) {
                putBe32(copy, track[2], sample.length);
            }
            offsets[index] = samples.size();
            samples.writeBytes(sample);
        }
        var out = new ByteArrayOutputStream();
        var mdat = -1;
        var moovStart = -1;
        for (var box : boxes) {
            var type = type(original, box[0]);
            if (type.equals("moov")) {
                moovStart = out.size();
            }
            if (type.equals("mdat")) {
                mdat = out.size();
                writeBe32(out, 8 + samples.size());
                out.write(copy, box[0] + 4, 4);
                out.writeBytes(samples.toByteArray());
            } else if (type.equals("uuid") && be32(original, box[0] + 8) == CR3_PREVIEW) {
                // the 16-byte UUID and 8 bytes, then a PRVW box whose JPEG follows a 16-byte header
                var prvw = box[0] + 8 + 16 + 8;
                writeBe32(out, 8 + 16 + 8 + 8 + 16 + jpeg.length);
                out.write(copy, box[0] + 4, 4 + 16 + 8);
                writeBe32(out, 8 + 16 + jpeg.length);
                out.write(copy, prvw + 4, 4);
                var fields = Arrays.copyOfRange(copy, prvw + 8, prvw + 8 + 16);
                if (be32(fields, 12) == be32(copy, prvw) - 8 - 16) {
                    putBe32(fields, 12, jpeg.length);
                }
                out.writeBytes(fields);
                out.writeBytes(jpeg);
            } else {
                out.write(copy, box[0], box[1]);
            }
        }
        var result = out.toByteArray();
        for (var index = 0; index < tracks.size(); index++) {
            putBe64(result, tracks.get(index)[1] - moov + moovStart, mdat + 8 + offsets[index]);
        }
        var before = RawDecoder.info(MediaSource.of(original));
        var after = RawDecoder.info(MediaSource.of(result));
        if (!before.toString().equals(after.toString()) || !before.iso().equals(after.iso())
                || !before.lens().equals(after.lens()) || !before.captureTime().equals(after.captureTime())) {
            throw new IllegalStateException("the reduced CR3 file is described as " + after + ", not " + before);
        }
        var preview = RawDecoder.extractPreview(MediaSource.of(result)).orElseThrow();
        Images.decode(MediaSource.of(preview), ImageDecoderOptions.builder().limits(Fuzz.LIMITS).build());
        return result;
    }

    /**
     * Collects the tracks of a movie box, each as the position of its stsz box, of its first chunk offset, of its
     * first sample size (or -1) and its fixed sample size (or 0).
     */
    private static void collectTracks(byte[] data, int start, int end, List<int[]> tracks) {
        for (var box : boxes(data, start, end)) {
            if (type(data, box[0]).equals("trak")) {
                var track = new int[]{-1, -1, -1, 0};
                findTables(data, box[0] + 8, box[0] + box[1], track);
                if (track[1] >= 0 && (track[2] >= 0 || track[3] > 0)) {
                    tracks.add(track);
                }
            }
        }
    }

    private static void findTables(byte[] data, int start, int end, int[] track) {
        for (var box : boxes(data, start, end)) {
            switch (type(data, box[0])) {
                case "mdia", "minf", "stbl" -> findTables(data, box[0] + 8, box[0] + box[1], track);
                case "stsz" -> {
                    track[0] = box[0];
                    var fixed = (int) be32(data, box[0] + 12);
                    if (be32(data, box[0] + 16) != 1) {
                        throw new IllegalStateException("a CR3 track of several samples");
                    }
                    if (fixed > 0) {
                        track[3] = fixed;
                    } else {
                        track[2] = box[0] + 20;
                    }
                }
                case "co64" -> {
                    if (be32(data, box[0] + 12) != 1) {
                        throw new IllegalStateException("a CR3 track of several chunks");
                    }
                    track[1] = box[0] + 16;
                }
                default -> {
                }
            }
        }
    }

    /**
     * Returns the boxes between two offsets, each as its offset and size.
     */
    private static List<int[]> boxes(byte[] data, int start, int end) {
        var result = new ArrayList<int[]>();
        var at = start;
        while (at + 8 <= end) {
            var size = be32(data, at);
            if (size == 1) {
                size = be64(data, at + 8);
            } else if (size == 0) {
                size = end - at;
            }
            if (size < 8 || at + size > end) {
                throw new IllegalStateException("damaged box at " + at);
            }
            result.add(new int[]{at, (int) size});
            at += (int) size;
        }
        return result;
    }

    private static String type(byte[] data, int box) {
        return new String(data, box + 4, 4, StandardCharsets.ISO_8859_1);
    }

    private static long be64(byte[] data, int at) {
        return be32(data, at) << 32 | be32(data, at + 4);
    }

    private static void putBe64(byte[] data, int at, long value) {
        putBe32(data, at, value >>> 32);
        putBe32(data, at + 4, value);
    }

    private static void writeBe32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }

    private static long be32(byte[] data, int at) {
        return (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8 | data[at + 3] & 0xFFL;
    }

    private static void putBe32(byte[] data, int at, long value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }

    private static byte[] reduce(byte[] original, Layout layout, int rows, byte[] jpeg, double slack,
                                 Optional<String> compression) {
        var edited = original.clone();
        var tiff = Tiff.parse(edited, layout);
        tiff.slack = slack;
        var raw = editRows(tiff, layout, rows);
        var removed = new ArrayList<long[]>();
        for (var blob : tiff.blobs()) {
            if (blob.start() != raw.start() && blob.length() >= LARGE && isJpeg(edited, blob.start())) {
                System.arraycopy(jpeg, 0, edited, (int) blob.start(), jpeg.length);
                tiff.setLength(blob, jpeg.length);
                removed.add(new long[]{blob.start() + jpeg.length, blob.start() + blob.length()});
            }
        }
        for (var image : tiff.thumbnails(raw)) {
            removed.add(image);
        }
        replacePreviews(edited, tiff, removed, jpeg);
        var expected = Decoded.of(tiff.compact(removed));
        if (!expected.compression().equals(compression)) {
            throw new IllegalStateException("the reduced " + layout + " file is read as " + expected.compression()
                    + ", not " + compression);
        }
        for (var gap : tiff.gaps()) {
            var trial = new ArrayList<>(removed);
            trial.add(gap);
            if (expected.equals(Decoded.tryOf(tiff.compact(trial)))) {
                removed.add(gap);
            }
        }
        if (raw.exact() >= 0) {
            var cut = cut(tiff, raw, raw.exact(), removed);
            if (expected.equals(Decoded.tryOf(cut))) {
                return cut;
            }
        }
        var low = 0L;
        var high = raw.length();
        while (high - low > 1) {
            var middle = (low + high) >>> 1;
            if (expected.equals(Decoded.tryOf(cut(tiff, raw, middle, removed)))) {
                high = middle;
            } else {
                low = middle;
            }
        }
        // a margin past the shortest cut that decodes, so the seed does not end on the last byte the decoder reads
        var length = Math.min(raw.length(), high + 64);
        var cut = cut(tiff, raw, length, removed);
        if (!expected.equals(Decoded.tryOf(cut))) {
            throw new IllegalStateException("no cut of the " + layout + " file decodes as the uncut one");
        }
        return cut;
    }

    /**
     * Replaces the large previews the decoder finds through maker notes, which no TIFF tag delimits: each is found
     * by its bytes and overwritten with the tiny JPEG in place, the 32-bit fields that hold its length next to its
     * offset are set to the new length, and its tail is cut when the file still decodes.
     */
    private static void replacePreviews(byte[] edited, Tiff tiff, List<long[]> removed, byte[] jpeg) {
        for (var round = 0; round < 4; round++) {
            var preview = RawDecoder.extractPreview(MediaSource.of(edited)).orElse(null);
            if (preview == null || preview.length < LARGE) {
                return;
            }
            var offset = indexOf(edited, preview);
            if (offset < 0) {
                return;
            }
            var saved = Arrays.copyOfRange(edited, offset, offset + jpeg.length);
            System.arraycopy(jpeg, 0, edited, offset, jpeg.length);
            var fields = tiff.lengthFields(offset, preview.length);
            fields.forEach(field -> tiff.put32(field, jpeg.length));
            var tail = new long[]{offset + jpeg.length, offset + preview.length};
            removed.add(tail);
            if (Decoded.tryOf(tiff.compact(removed)) == null) {
                removed.remove(tail);
                if (Decoded.tryOf(tiff.compact(removed)) == null) {
                    System.arraycopy(saved, 0, edited, offset, saved.length);
                    fields.forEach(field -> tiff.put32(field, preview.length));
                    return;
                }
            }
        }
    }

    private static int indexOf(byte[] data, byte[] part) {
        outer:
        for (var start = 0; start <= data.length - part.length; start++) {
            for (var index = 0; index < Math.min(part.length, 256); index++) {
                if (data[start + index] != part[index]) {
                    continue outer;
                }
            }
            if (Arrays.equals(data, start, start + part.length, part, 0, part.length)) {
                return start;
            }
        }
        return -1;
    }

    private static byte[] cut(Tiff tiff, Blob raw, long length, List<long[]> removed) {
        var copy = tiff.copy();
        if (raw.countEntry() != null) {
            copy.setLength(raw, length);
        }
        var ranges = new ArrayList<>(removed);
        if (length < raw.length()) {
            ranges.add(new long[]{raw.start() + length, raw.start() + raw.length()});
        }
        return copy.compact(ranges);
    }

    private static boolean isJpeg(byte[] data, long offset) {
        return offset + 2 <= data.length && (data[(int) offset] & 0xFF) == 0xFF && (data[(int) offset + 1] & 0xFF) == 0xD8;
    }

    private static Blob editRows(Tiff tiff, Layout layout, int rows) {
        return switch (layout) {
            case NEF, ARW -> tiff.singleStrip(tiff.largestImage(), rows);
            case PEF, ORF -> tiff.singleStrip(tiff.ifds().getFirst(), rows);
            case RW2 -> {
                var ifd = tiff.ifds().getFirst();
                tiff.set(ifd, 3, rows);
                if (tiff.value(ifd, 6, 0) > rows) {
                    tiff.set(ifd, 6, rows);
                }
                if (tiff.entry(ifd, 257) != null) {
                    tiff.set(ifd, 257, rows);
                }
                var offset = tiff.value(ifd, 0x118, 0);
                yield new Blob(offset, tiff.data().length - offset, -1, null, null, -1);
            }
            case CR2 -> {
                var ifd = tiff.chain().get(3);
                var blob = tiff.strip(ifd);
                tiff.setFrameRows(blob.start(), rows);
                yield blob;
            }
            case RAF, CR3 -> throw new IllegalArgumentException(layout + " is not TIFF-structured");
        };
    }

    /**
     * A region of data a directory points to.
     *
     * @param start the absolute offset
     * @param length the length in bytes
     * @param exact the length the cut must keep, or -1 to search for the shortest one
     * @param offsetEntry the entry holding the offset, or {@code null}
     * @param countEntry the entry holding the length, or {@code null} for out-of-line values
     * @param valueEntry the entry whose out-of-line values the region is, or -1
     */
    record Blob(long start, long length, long exact, Entry offsetEntry, Entry countEntry, int valueEntry) {
    }

    /**
     * One directory entry.
     *
     * @param position the absolute offset of the 12-byte entry
     * @param tag the tag
     * @param type the TIFF type
     * @param count the value count
     */
    record Entry(int position, int tag, int type, long count) {
    }

    /**
     * One directory.
     *
     * @param position the absolute offset of the directory
     * @param entries the entries
     * @param pointerTags the tags of this directory whose values are offsets
     */
    record Ifd(int position, List<Entry> entries, Set<Integer> pointerTags) {
    }

    /** A TIFF structure over a mutable copy of the file. */
    static final class Tiff {
        private static final Set<Integer> SUB_DIRECTORIES = Set.of(330, 34665, 34853, 40965);
        private static final Set<Integer> DATA_POINTERS = Set.of(273, 324, 513);
        private static final int[] SIZES = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4};

        private final byte[] data;
        private final boolean little;
        private final Layout layout;
        private final List<Ifd> ifds = new ArrayList<>();
        private final List<Ifd> chain = new ArrayList<>();
        private final List<Integer> extraSlots = new ArrayList<>();
        private double slack = 2;

        private Tiff(byte[] data, Layout layout) {
            this.data = data;
            this.little = data[0] == 'I';
            this.layout = layout;
        }

        static Tiff parse(byte[] data, Layout layout) {
            var tiff = new Tiff(data, layout);
            tiff.walk();
            return tiff;
        }

        Tiff copy() {
            return parse(data.clone(), layout);
        }

        byte[] data() {
            return data;
        }

        List<Ifd> ifds() {
            return ifds;
        }

        /**
         * {@return the directories of the top-level chain, in order}
         */
        List<Ifd> chain() {
            return chain;
        }

        private void walk() {
            ifds.clear();
            chain.clear();
            extraSlots.clear();
            if (layout == Layout.CR2) {
                extraSlots.add(12);
            }
            var seen = new HashSet<Integer>();
            var next = (int) u32(4);
            while (next > 0 && next < data.length && seen.add(next)) {
                var ifd = directory(next, true);
                chain.add(ifd);
                walkChildren(ifd, seen, 0);
                next = (int) u32(ifd.position() + 2 + ifd.entries().size() * 12);
            }
        }

        private void walkChildren(Ifd ifd, Set<Integer> seen, int depth) {
            for (var entry : ifd.entries()) {
                if (SUB_DIRECTORIES.contains(entry.tag()) && depth < 4) {
                    for (var offset : values(entry)) {
                        if (offset > 0 && offset < data.length && seen.add((int) offset)) {
                            walkChildren(directory((int) offset, false), seen, depth + 1);
                        }
                    }
                }
            }
        }

        private Ifd directory(int position, boolean top) {
            var count = u16(position);
            var entries = new ArrayList<Entry>();
            for (var index = 0; index < count; index++) {
                var at = position + 2 + index * 12;
                entries.add(new Entry(at, u16(at), u16(at + 2), u32(at + 4)));
            }
            var pointers = new HashSet<>(SUB_DIRECTORIES);
            pointers.addAll(DATA_POINTERS);
            if (layout == Layout.ARW) {
                pointers.add(0xC634);
            }
            if (layout == Layout.RW2 && top && ifds.isEmpty()) {
                pointers.add(0x118);
            }
            var ifd = new Ifd(position, entries, pointers);
            ifds.add(ifd);
            return ifd;
        }

        /**
         * Returns the directory a directory-typed entry points to.
         */
        Ifd child(Ifd ifd, int tag) {
            var offset = (int) value(ifd, tag, 0);
            for (var candidate : ifds) {
                if (candidate.position() == offset) {
                    return candidate;
                }
            }
            return directory(offset, false);
        }

        Entry entry(Ifd ifd, int tag) {
            for (var entry : ifd.entries()) {
                if (entry.tag() == tag) {
                    return entry;
                }
            }
            return null;
        }

        long value(Ifd ifd, int tag, long fallback) {
            var entry = entry(ifd, tag);
            if (entry == null) {
                return fallback;
            }
            var values = values(entry);
            return values.length == 0 ? fallback : values[0];
        }

        private int valuePosition(Entry entry) {
            var size = size(entry);
            return size > 4 ? (int) u32(entry.position() + 8) : entry.position() + 8;
        }

        private long size(Entry entry) {
            var unit = entry.type() > 0 && entry.type() < SIZES.length ? SIZES[entry.type()] : 1;
            return unit * entry.count();
        }

        long[] values(Entry entry) {
            if (entry.type() != 3 && entry.type() != 4 && entry.type() != 13) {
                return new long[0];
            }
            if (entry.count() > 1 << 20) {
                return new long[0];
            }
            var at = valuePosition(entry);
            var result = new long[(int) entry.count()];
            for (var index = 0; index < result.length; index++) {
                var position = at + index * (entry.type() == 3 ? 2 : 4);
                if (position + 4 > data.length) {
                    return new long[0];
                }
                result[index] = entry.type() == 3 ? u16(position) : u32(position);
            }
            return result;
        }

        void set(Ifd ifd, int tag, long value) {
            var entry = Objects.requireNonNull(entry(ifd, tag), () -> "no tag " + tag);
            if (entry.count() != 1) {
                throw new IllegalStateException("tag " + tag + " holds " + entry.count() + " values");
            }
            write(entry, valuePosition(entry), value);
        }

        private void write(Entry entry, int position, long value) {
            if (entry.type() == 3) {
                put16(position, (int) value);
            } else {
                put32(position, value);
            }
        }

        /**
         * Rewrites an entry as a single inline value, dropping its other values.
         */
        private void single(Entry entry, int type, long value) {
            put16(entry.position() + 2, type);
            put32(entry.position() + 4, 1);
            put32(entry.position() + 8, 0);
            if (type == 3) {
                put16(entry.position() + 8, (int) value);
            } else {
                put32(entry.position() + 8, value);
            }
        }

        Ifd largestImage() {
            Ifd best = null;
            var area = -1L;
            for (var ifd : ifds) {
                var size = value(ifd, 256, 0) * value(ifd, 257, 0);
                if (size > area && entry(ifd, 273) != null) {
                    best = ifd;
                    area = size;
                }
            }
            return Objects.requireNonNull(best, "no image directory");
        }

        Blob strip(Ifd ifd) {
            var offsets = values(entry(ifd, 273));
            var counts = values(entry(ifd, 279));
            var start = Arrays.stream(offsets).min().orElseThrow();
            var end = 0L;
            for (var index = 0; index < offsets.length; index++) {
                end = Math.max(end, offsets[index] + counts[index]);
            }
            return new Blob(start, end - start, -1, entry(ifd, 273), entry(ifd, 279), -1);
        }

        /**
         * Makes an image its first rows, in one strip. Data of a whole number of bytes per row (uncompressed, or
         * coded in fixed-size blocks) keeps that length per row, which the decoders of such data check, so the cut is
         * exact; other data is searched.
         */
        Blob singleStrip(Ifd ifd, int rows) {
            var whole = strip(ifd);
            var height = value(ifd, 257, 0);
            var total = Arrays.stream(values(entry(ifd, 279))).sum();
            var exact = height > 0 && total % height == 0 ? total / height * rows : -1;
            var length = height > 0 ? Math.min(whole.length(), (long) (total * slack * rows / height) + 4096)
                    : whole.length();
            set(ifd, 257, rows);
            single(entry(ifd, 273), 4, whole.start());
            single(entry(ifd, 279), 4, exact >= 0 ? exact : length);
            if (entry(ifd, 278) != null) {
                single(entry(ifd, 278), 4, rows);
            }
            return new Blob(whole.start(), exact >= 0 ? whole.length() : length, exact, entry(ifd, 273),
                    entry(ifd, 279), -1);
        }

        /**
         * Sets the line count of the lossless JPEG frame that starts a stream.
         */
        void setFrameRows(long start, int rows) {
            var at = (int) start + 2;
            while (at + 4 < data.length) {
                if ((data[at] & 0xFF) != 0xFF) {
                    throw new IllegalStateException("no JPEG marker at " + at);
                }
                var marker = data[at + 1] & 0xFF;
                if (marker == 0xC3) {
                    data[at + 5] = (byte) (rows >>> 8);
                    data[at + 6] = (byte) rows;
                    return;
                }
                at += 2 + ((data[at + 2] & 0xFF) << 8 | data[at + 3] & 0xFF);
            }
            throw new IllegalStateException("no lossless JPEG frame header");
        }

        List<Blob> blobs() {
            var result = new ArrayList<Blob>();
            for (var ifd : ifds) {
                for (var pair : new int[][]{{273, 279}, {324, 325}, {513, 514}}) {
                    var offsets = entry(ifd, pair[0]);
                    var counts = entry(ifd, pair[1]);
                    if (offsets == null || counts == null) {
                        continue;
                    }
                    var starts = values(offsets);
                    var lengths = values(counts);
                    if (starts.length == 1 && lengths.length == 1 && starts[0] + lengths[0] <= data.length) {
                        result.add(new Blob(starts[0], lengths[0], -1, offsets, counts, -1));
                    }
                }
                for (var index = 0; index < ifd.entries().size(); index++) {
                    var entry = ifd.entries().get(index);
                    if (entry.type() == 7 && size(entry) > 4) {
                        var start = valuePosition(entry);
                        if (start + size(entry) <= data.length && entry.tag() != 37500) {
                            result.add(new Blob(start, size(entry), -1, null, entry, index));
                        }
                    }
                }
            }
            return result;
        }

        void setLength(Blob blob, long length) {
            if (blob.countEntry() == null) {
                throw new IllegalStateException("the length of the blob is not stored");
            }
            var entry = find(blob.countEntry().position());
            if (blob.valueEntry() >= 0) {
                put32(entry.position() + 4, length);
            } else {
                write(entry, valuePosition(entry), length);
            }
        }

        private Entry find(int position) {
            for (var ifd : ifds) {
                for (var entry : ifd.entries()) {
                    if (entry.position() == position) {
                        return entry;
                    }
                }
            }
            throw new IllegalStateException("no entry at " + position);
        }

        /**
         * Returns the data of the uncompressed images other than the sensor image cut to one row each, as ranges to
         * remove, after making their directories say so.
         */
        List<long[]> thumbnails(Blob raw) {
            var result = new ArrayList<long[]>();
            for (var ifd : ifds) {
                if (value(ifd, 259, 0) != 1 || entry(ifd, 273) == null || entry(ifd, 257) == null) {
                    continue;
                }
                var strip = strip(ifd);
                if (strip.start() == raw.start() || strip.length() < LARGE || strip.start() + strip.length() > data.length) {
                    continue;
                }
                var row = strip.length() / Math.max(1, value(ifd, 257, 1));
                set(ifd, 257, 1);
                single(entry(ifd, 273), 4, strip.start());
                single(entry(ifd, 279), 4, row);
                if (entry(ifd, 278) != null) {
                    single(entry(ifd, 278), 4, 1);
                }
                result.add(new long[]{strip.start() + row, strip.start() + strip.length()});
            }
            return result;
        }

        /**
         * Returns the stretches of at least {@value #LARGE} bytes that no directory, value, data region or maker note
         * covers.
         */
        List<long[]> gaps() {
            walk();
            var covered = new ArrayList<long[]>();
            covered.add(new long[]{0, 16});
            for (var ifd : ifds) {
                covered.add(new long[]{ifd.position(), ifd.position() + 6L + ifd.entries().size() * 12L});
                for (var entry : ifd.entries()) {
                    if (size(entry) > 4) {
                        covered.add(new long[]{valuePosition(entry), valuePosition(entry) + size(entry)});
                    }
                }
                for (var pair : new int[][]{{273, 279}, {324, 325}, {513, 514}}) {
                    if (entry(ifd, pair[0]) != null && entry(ifd, pair[1]) != null) {
                        var starts = values(entry(ifd, pair[0]));
                        var lengths = values(entry(ifd, pair[1]));
                        for (var index = 0; index < Math.min(starts.length, lengths.length); index++) {
                            covered.add(new long[]{starts[index], starts[index] + lengths[index]});
                        }
                    }
                }
                if (layout == Layout.RW2 && entry(ifd, 0x118) != null) {
                    covered.add(new long[]{value(ifd, 0x118, 0), data.length});
                }
            }
            var gaps = new ArrayList<long[]>();
            var end = 0L;
            var inside = covered.stream().filter(range -> range[0] < data.length)
                    .map(range -> new long[]{range[0], Math.min(range[1], data.length)}).toList();
            for (var range : merge(inside)) {
                if (range[0] - end >= LARGE) {
                    gaps.add(new long[]{end, range[0]});
                }
                end = Math.max(end, range[1]);
            }
            if (data.length - end >= LARGE) {
                gaps.add(new long[]{end, data.length});
            }
            return gaps;
        }

        /**
         * Returns a copy of the file without some byte ranges, every offset past a removed range moved down.
         */
        byte[] compact(List<long[]> removed) {
            walk();
            var ranges = merge(removed);
            var slots = new ArrayList<Integer>(extraSlots);
            for (var ifd : ifds) {
                for (var entry : ifd.entries()) {
                    if (size(entry) > 4) {
                        slots.add(entry.position() + 8);
                    }
                    if (ifd.pointerTags().contains(entry.tag()) && (entry.type() == 4 || entry.type() == 13)) {
                        var at = valuePosition(entry);
                        for (var index = 0; index < entry.count(); index++) {
                            slots.add(at + index * 4);
                        }
                    }
                }
                slots.add(ifd.position() + 2 + ifd.entries().size() * 12);
            }
            var out = new byte[(int) (data.length - ranges.stream().mapToLong(range -> range[1] - range[0]).sum())];
            var position = 0;
            var from = 0L;
            for (var range : ranges) {
                System.arraycopy(data, (int) from, out, position, (int) (range[0] - from));
                position += (int) (range[0] - from);
                from = range[1];
            }
            System.arraycopy(data, (int) from, out, position, (int) (data.length - from));
            for (var slot : new HashSet<>(slots)) {
                var value = u32(slot);
                if (value == 0 || value >= data.length) {
                    continue;
                }
                var target = (int) map(ranges, slot);
                var moved = map(ranges, value);
                if (little) {
                    out[target] = (byte) moved;
                    out[target + 1] = (byte) (moved >>> 8);
                    out[target + 2] = (byte) (moved >>> 16);
                    out[target + 3] = (byte) (moved >>> 24);
                } else {
                    out[target] = (byte) (moved >>> 24);
                    out[target + 1] = (byte) (moved >>> 16);
                    out[target + 2] = (byte) (moved >>> 8);
                    out[target + 3] = (byte) moved;
                }
            }
            return out;
        }

        private static List<long[]> merge(List<long[]> removed) {
            var sorted = new ArrayList<>(removed.stream().filter(range -> range[1] > range[0]).toList());
            sorted.sort(Comparator.comparingLong(range -> range[0]));
            var result = new ArrayList<long[]>();
            for (var range : sorted) {
                if (!result.isEmpty() && range[0] <= result.getLast()[1]) {
                    result.getLast()[1] = Math.max(result.getLast()[1], range[1]);
                } else {
                    result.add(range.clone());
                }
            }
            return result;
        }

        private static long map(List<long[]> ranges, long offset) {
            var shift = 0L;
            for (var range : ranges) {
                if (offset >= range[1]) {
                    shift += range[1] - range[0];
                } else if (offset > range[0]) {
                    return range[0] - shift;
                }
            }
            return offset - shift;
        }

        private int u16(int at) {
            return little ? (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 : (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
        }

        private long u32(int at) {
            return little
                    ? (data[at] & 0xFFL) | (data[at + 1] & 0xFFL) << 8 | (data[at + 2] & 0xFFL) << 16 | (data[at + 3] & 0xFFL) << 24
                    : (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8 | data[at + 3] & 0xFFL;
        }

        private void put16(int at, int value) {
            if (little) {
                data[at] = (byte) value;
                data[at + 1] = (byte) (value >>> 8);
            } else {
                data[at] = (byte) (value >>> 8);
                data[at + 1] = (byte) value;
            }
        }

        /**
         * Returns the positions of the 32-bit fields before a data region that hold its length and, in one of the 12
         * bytes before them, its offset: absolute, or relative to the start of a maker note or of the TIFF header a
         * maker note embeds.
         */
        List<Integer> lengthFields(int offset, long length) {
            var bases = new HashSet<Long>(List.of(0L));
            for (var ifd : ifds) {
                var note = entry(ifd, 37500);
                if (note != null && size(note) > 4) {
                    for (var skip : new int[]{0, 10, 12, 18}) {
                        bases.add((long) valuePosition(note) + skip);
                    }
                }
            }
            var result = new ArrayList<Integer>();
            for (var at = 12; at + 4 <= offset; at++) {
                if (u32(at) != length) {
                    continue;
                }
                for (var before = at - 12; before < at; before++) {
                    var value = u32(before);
                    if (value <= offset && bases.contains(offset - value)) {
                        result.add(at);
                        break;
                    }
                }
            }
            return result;
        }

        private void put32(int at, long value) {
            if (little) {
                data[at] = (byte) value;
                data[at + 1] = (byte) (value >>> 8);
                data[at + 2] = (byte) (value >>> 16);
                data[at + 3] = (byte) (value >>> 24);
            } else {
                data[at] = (byte) (value >>> 24);
                data[at + 1] = (byte) (value >>> 16);
                data[at + 2] = (byte) (value >>> 8);
                data[at + 3] = (byte) value;
            }
        }
    }

    /**
     * What decoding a raw file yields, compared to check that a cut kept every byte the decoder reads.
     *
     * @param info the description
     * @param width the sensor width
     * @param height the sensor height
     * @param samples the sensor samples
     * @param color the camera neutral and the balanced-to-XYZ matrix
     * @param rendered the default rendering as RGBA
     * @param rendering the rendering with every option changed, as RGBA
     * @param preview whether a preview decodes
     * @param compression the compression of the sensor data
     */
    record Decoded(String info, int width, int height, short[] samples, double[] color, byte[] rendered,
                   byte[] rendering, boolean preview, Optional<String> compression) {
        private static final RawDecoderOptions PLAIN = RawDecoderOptions.builder().limits(Fuzz.LIMITS).build();

        static Decoded of(byte[] file) {
            var info = RawDecoder.info(MediaSource.of(file), Fuzz.LIMITS);
            RawImage raw = RawDecoder.decodeRaw(MediaSource.of(file), PLAIN);
            var color = new double[12];
            System.arraycopy(raw.cameraNeutral(), 0, color, 0, Math.min(3, raw.cameraNeutral().length));
            System.arraycopy(raw.balancedToXyz(), 0, color, 3, Math.min(9, raw.balancedToXyz().length));
            var rendered = ImageOps.toRgba(RawDecoder.decode(MediaSource.of(file), PLAIN).picture()).pixels();
            var rendering = ImageOps.toRgba(RawDecoder.decode(MediaSource.of(file), PLAIN.toBuilder()
                    .highBitDepth(true).opcodes(false).defaultCrop(false).build()).picture()).pixels();
            var preview = RawDecoder.extractPreview(MediaSource.of(file), Fuzz.LIMITS).map(bytes -> {
                Images.decode(MediaSource.of(bytes), ImageDecoderOptions.builder().limits(Fuzz.LIMITS).build());
                return true;
            }).orElse(false);
            return new Decoded(info.toString(), raw.width(), raw.height(), raw.samples(), color, rendered,
                    rendering, preview, info.compression());
        }

        static Decoded tryOf(byte[] file) {
            try {
                return of(file);
            } catch (MediaException exception) {
                return null;
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Decoded that && info.equals(that.info) && width == that.width
                    && height == that.height && Arrays.equals(samples, that.samples)
                    && Arrays.equals(color, that.color) && Arrays.equals(rendered, that.rendered)
                    && Arrays.equals(rendering, that.rendering) && preview == that.preview
                    && compression.equals(that.compression);
        }

        @Override
        public int hashCode() {
            return Objects.hash(info, width, height, Arrays.hashCode(samples));
        }
    }
}

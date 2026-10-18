package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.audio.vorbis.internal.Ilog;
import com.github.auties00.smalt.internal.bits.LsbBitWriter;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Builds Vorbis header packets from a mutable, field-by-field description of a stream, and
 * generates random valid descriptions. Tests use it to hand-construct streams libvorbis never
 * writes (floor 0, residue 0, lattice books with sequence_p, several submaps, odd partition
 * sizes) and to corrupt one header field at a time.
 */
final class VorbisStreamBuilder {
    int version = 0;
    int channels = 1;
    long sampleRate = 44100;
    int blocksize0Exponent = 8;
    int blocksize1Exponent = 11;
    int identificationFraming = 1;
    int setupFraming = 1;
    int timeCount = 1;
    int timeValue = 0;
    final List<Book> books = new ArrayList<>();
    final List<Object> floors = new ArrayList<>();
    final List<ResidueSpec> residues = new ArrayList<>();
    final List<MappingSpec> mappings = new ArrayList<>();
    final List<ModeSpec> modes = new ArrayList<>();

    /** A codebook: lengths (0 = unused), encoding flags and the optional value lookup. */
    static final class Book {
        int dimensions;
        int[] lengths;
        boolean ordered;
        boolean sparse;
        int lookupType;
        int minimum;
        int delta;
        int valueBits = 4;
        boolean sequence;
        int[] multiplicands = new int[0];
        int sync = 0x564342;

        Book(int dimensions, int[] lengths) {
            this.dimensions = dimensions;
            this.lengths = lengths;
        }

        int entries() {
            return lengths.length;
        }

        /** The value vector of an entry, in single precision like libvorbis. */
        float[] values(int entry) {
            var out = new float[dimensions];
            var min = Codebook.float32Unpack(minimum);
            var step = Codebook.float32Unpack(delta);
            var last = 0.0f;
            if (lookupType == 1) {
                var count = Codebook.lookup1Values(entries(), dimensions);
                long divisor = 1;
                for (var i = 0; i < dimensions; i++) {
                    var value = multiplicands[(int) ((entry / divisor) % count)] * step + min + last;
                    if (sequence) {
                        last = value;
                    }
                    out[i] = value;
                    divisor = divisor > entries() ? divisor : divisor * count;
                }
            } else {
                for (var i = 0; i < dimensions; i++) {
                    var value = multiplicands[entry * dimensions + i] * step + min + last;
                    if (sequence) {
                        last = value;
                    }
                    out[i] = value;
                }
            }
            return out;
        }
    }

    record Floor0Spec(int order, int rate, int barkMapSize, int amplitudeBits, int amplitudeOffset, int[] books) {
    }

    /** subclassBooks holds book numbers, -1 for none. */
    record Floor1Spec(int[] partitionClasses, int[] classDimensions, int[] classSubclasses, int[] classMasterbooks,
                      int[][] subclassBooks, int multiplier, int rangeBits, int[] xs) {
        int values() {
            return xs.length + 2;
        }
    }

    /** books[class][pass] holds book numbers, -1 for unused; cascade is derived from them. */
    record ResidueSpec(int type, int begin, int end, int partitionSize, int classifications, int classbook,
                       int[][] books) {
    }

    record MappingSpec(int submaps, int[] magnitude, int[] angle, int[] mux, int[] submapFloors, int[] submapResidues,
                       int type, int reserved) {
        MappingSpec(int submaps, int[] magnitude, int[] angle, int[] mux, int[] submapFloors, int[] submapResidues) {
            this(submaps, magnitude, angle, mux, submapFloors, submapResidues, 0, 0);
        }
    }

    record ModeSpec(boolean longBlock, int windowType, int transformType, int mapping) {
        ModeSpec(boolean longBlock, int mapping) {
            this(longBlock, 0, 0, mapping);
        }
    }

    /**
     * Returns a small valid stream: a two-entry scalar book, a four-entry lattice book, one floor
     * 1, one residue 1, one mapping (coupling the first two channels when there are several) and
     * a short and a long mode.
     */
    static VorbisStreamBuilder minimal(int channels) {
        var stream = new VorbisStreamBuilder();
        stream.channels = channels;
        stream.books.add(new Book(1, new int[]{1, 1}));
        var lattice = new Book(2, new int[]{2, 2, 2, 2});
        lattice.lookupType = 1;
        lattice.minimum = pack(-1);
        lattice.delta = pack(1);
        lattice.valueBits = 1;
        lattice.multiplicands = new int[]{0, 1};
        stream.books.add(lattice);
        stream.floors.add(new Floor1Spec(new int[]{0}, new int[]{2}, new int[]{0}, new int[]{-1}, new int[][]{{0}},
                2, 7, new int[]{32, 64}));
        var books = new int[2][8];
        java.util.Arrays.fill(books[0], -1);
        java.util.Arrays.fill(books[1], -1);
        books[0][0] = 1;
        stream.residues.add(new ResidueSpec(1, 0, 128, 16, 2, 0, books));
        var magnitude = channels > 1 ? new int[]{0} : new int[0];
        var angle = channels > 1 ? new int[]{1} : new int[0];
        stream.mappings.add(new MappingSpec(1, magnitude, angle, new int[channels], new int[]{0}, new int[]{0}));
        stream.modes.add(new ModeSpec(false, 0));
        stream.modes.add(new ModeSpec(true, 0));
        return stream;
    }

    int blocksize(boolean longBlock) {
        return 1 << (longBlock ? blocksize1Exponent : blocksize0Exponent);
    }

    byte[] identification() {
        var writer = new LsbBitWriter();
        writeCommon(writer, 1);
        writer.write(version, 32);
        writer.write(channels, 8);
        writer.write((int) sampleRate, 32);
        writer.write(0, 32);
        writer.write(128000, 32);
        writer.write(0, 32);
        writer.write(blocksize0Exponent, 4);
        writer.write(blocksize1Exponent, 4);
        writer.write(identificationFraming, 1);
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    static byte[] comment() {
        var writer = new LsbBitWriter();
        writeCommon(writer, 3);
        var vendor = "smalt test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writer.write(vendor.length, 32);
        writer.writeBytes(vendor, 0, vendor.length);
        writer.write(0, 32);
        writer.write(1, 1);
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    byte[] setup() {
        var writer = new LsbBitWriter();
        writeCommon(writer, 5);
        writer.write(books.size() - 1, 8);
        for (var book : books) {
            writeBook(writer, book);
        }
        writer.write(timeCount - 1, 6);
        for (var i = 0; i < timeCount; i++) {
            writer.write(timeValue, 16);
        }
        writer.write(floors.size() - 1, 6);
        for (var floor : floors) {
            switch (floor) {
                case Floor0Spec f -> {
                    writer.write(0, 16);
                    writer.write(f.order(), 8);
                    writer.write(f.rate(), 16);
                    writer.write(f.barkMapSize(), 16);
                    writer.write(f.amplitudeBits(), 6);
                    writer.write(f.amplitudeOffset(), 8);
                    writer.write(f.books().length - 1, 4);
                    for (var book : f.books()) {
                        writer.write(book, 8);
                    }
                }
                case Floor1Spec f -> {
                    writer.write(1, 16);
                    writer.write(f.partitionClasses().length, 5);
                    for (var c : f.partitionClasses()) {
                        writer.write(c, 4);
                    }
                    var maxClass = -1;
                    for (var c : f.partitionClasses()) {
                        maxClass = Math.max(maxClass, c);
                    }
                    for (var c = 0; c <= maxClass; c++) {
                        writer.write(f.classDimensions()[c] - 1, 3);
                        writer.write(f.classSubclasses()[c], 2);
                        if (f.classSubclasses()[c] != 0) {
                            writer.write(f.classMasterbooks()[c], 8);
                        }
                        for (var j = 0; j < 1 << f.classSubclasses()[c]; j++) {
                            writer.write(f.subclassBooks()[c][j] + 1, 8);
                        }
                    }
                    writer.write(f.multiplier() - 1, 2);
                    writer.write(f.rangeBits(), 4);
                    for (var x : f.xs()) {
                        writer.write(x, f.rangeBits());
                    }
                }
                case Integer type -> writer.write(type, 16);
                default -> throw new IllegalStateException();
            }
        }
        writer.write(residues.size() - 1, 6);
        for (var residue : residues) {
            writer.write(residue.type(), 16);
            writer.write(residue.begin(), 24);
            writer.write(residue.end(), 24);
            writer.write(residue.partitionSize() - 1, 24);
            writer.write(residue.classifications() - 1, 6);
            writer.write(residue.classbook(), 8);
            for (var c = 0; c < residue.classifications(); c++) {
                var cascade = 0;
                for (var pass = 0; pass < 8; pass++) {
                    if (residue.books()[c][pass] >= 0) {
                        cascade |= 1 << pass;
                    }
                }
                writer.write(cascade & 7, 3);
                if (cascade >= 8) {
                    writer.write(1, 1);
                    writer.write(cascade >>> 3, 5);
                } else {
                    writer.write(0, 1);
                }
            }
            for (var c = 0; c < residue.classifications(); c++) {
                for (var pass = 0; pass < 8; pass++) {
                    if (residue.books()[c][pass] >= 0) {
                        writer.write(residue.books()[c][pass], 8);
                    }
                }
            }
        }
        writer.write(mappings.size() - 1, 6);
        for (var mapping : mappings) {
            writer.write(mapping.type(), 16);
            if (mapping.submaps() > 1) {
                writer.write(1, 1);
                writer.write(mapping.submaps() - 1, 4);
            } else {
                writer.write(0, 1);
            }
            if (mapping.magnitude().length > 0) {
                writer.write(1, 1);
                writer.write(mapping.magnitude().length - 1, 8);
                var bits = Ilog.ilog(channels - 1);
                for (var i = 0; i < mapping.magnitude().length; i++) {
                    writer.write(mapping.magnitude()[i], bits);
                    writer.write(mapping.angle()[i], bits);
                }
            } else {
                writer.write(0, 1);
            }
            writer.write(mapping.reserved(), 2);
            if (mapping.submaps() > 1) {
                for (var i = 0; i < channels; i++) {
                    writer.write(mapping.mux()[i], 4);
                }
            }
            for (var i = 0; i < mapping.submaps(); i++) {
                writer.write(0, 8);
                writer.write(mapping.submapFloors()[i], 8);
                writer.write(mapping.submapResidues()[i], 8);
            }
        }
        writer.write(modes.size() - 1, 6);
        for (var mode : modes) {
            writer.write(mode.longBlock() ? 1 : 0, 1);
            writer.write(mode.windowType(), 16);
            writer.write(mode.transformType(), 16);
            writer.write(mode.mapping(), 8);
        }
        writer.write(setupFraming, 1);
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    private static void writeBook(LsbBitWriter writer, Book book) {
        writer.write(book.sync, 24);
        writer.write(book.dimensions, 16);
        writer.write(book.entries(), 24);
        writer.write(book.ordered ? 1 : 0, 1);
        if (book.ordered) {
            var entry = 0;
            var length = book.lengths[0];
            writer.write(length - 1, 5);
            while (entry < book.entries()) {
                var number = 0;
                while (entry + number < book.entries() && book.lengths[entry + number] == length) {
                    number++;
                }
                writer.write(number, Ilog.ilog(book.entries() - entry));
                entry += number;
                length++;
            }
        } else {
            writer.write(book.sparse ? 1 : 0, 1);
            for (var length : book.lengths) {
                if (book.sparse) {
                    writer.write(length > 0 ? 1 : 0, 1);
                    if (length > 0) {
                        writer.write(length - 1, 5);
                    }
                } else {
                    writer.write(length - 1, 5);
                }
            }
        }
        writer.write(book.lookupType, 4);
        if (book.lookupType == 1 || book.lookupType == 2) {
            writer.write(book.minimum, 32);
            writer.write(book.delta, 32);
            writer.write(book.valueBits - 1, 4);
            writer.write(book.sequence ? 1 : 0, 1);
            for (var multiplicand : book.multiplicands) {
                writer.write(multiplicand, book.valueBits);
            }
        }
    }

    private static void writeCommon(LsbBitWriter writer, int type) {
        writer.write(type, 8);
        for (var c : "vorbis".toCharArray()) {
            writer.write(c, 8);
        }
    }

    byte[] config() {
        return XiphLacing.lace(identification(), comment(), setup());
    }

    AudioTrack track() {
        return track(config());
    }

    static AudioTrack track(byte[] config) {
        return AudioTrack.builder().codec(Codec.VORBIS).timeBase(new Rational(1, 44100)).codecConfig(config).build();
    }

    /** Packs a value in the Vorbis codebook float format (21-bit mantissa, exponent bias 788). */
    static int pack(double value) {
        if (value == 0) {
            return 0;
        }
        var sign = value < 0 ? 0x80000000 : 0;
        var magnitude = Math.abs(value);
        var exponent = Math.getExponent(magnitude) - 20;
        var mantissa = (long) Math.round(Math.scalb(magnitude, -exponent));
        if (mantissa >= 1 << 21) {
            mantissa >>= 1;
            exponent++;
        }
        return sign | (exponent + 788) << 21 | (int) mantissa;
    }

    /** Returns lengths of a complete prefix code with the given number of leaves, in random order. */
    static int[] completeLengths(Random random, int leaves, int maxLength) {
        var lengths = new ArrayList<Integer>(List.of(0));
        while (lengths.size() < leaves) {
            var candidates = new ArrayList<Integer>();
            for (var i = 0; i < lengths.size(); i++) {
                if (lengths.get(i) < maxLength) {
                    candidates.add(i);
                }
            }
            // Favor splitting deep leaves sometimes to produce long codewords.
            var pick = candidates.get(random.nextInt(4) == 0 ? candidates.size() - 1 : random.nextInt(candidates.size()));
            var depth = lengths.remove((int) pick) + 1;
            lengths.add(depth);
            lengths.add(depth);
        }
        var result = lengths.stream().mapToInt(Integer::intValue).toArray();
        for (var i = result.length - 1; i > 0; i--) {
            var j = random.nextInt(i + 1);
            var t = result[i];
            result[i] = result[j];
            result[j] = t;
        }
        return result;
    }

    /** Whether the spec's leftmost-free assignment can place the lengths (no overspecification). */
    static boolean assignable(int[] lengths) {
        var marker = new long[33];
        for (var length : lengths) {
            if (length == 0) {
                continue;
            }
            var code = marker[length];
            if ((code >>> length) != 0) {
                return false;
            }
            for (var j = length; j > 0; j--) {
                if ((marker[j] & 1) != 0) {
                    marker[j] = j == 1 ? marker[1] + 1 : marker[j - 1] << 1;
                    break;
                }
                marker[j]++;
            }
            for (var j = length + 1; j < 33; j++) {
                if ((marker[j] >>> 1) != code) {
                    break;
                }
                code = marker[j];
                marker[j] = marker[j - 1] << 1;
            }
        }
        return true;
    }

    /** A random scalar codebook with a complete code over at least one entry. */
    static Book randomScalarBook(Random random, int minEntries, int maxEntries) {
        var entries = minEntries + random.nextInt(maxEntries - minEntries + 1);
        return new Book(random.nextInt(3), randomLengths(random, entries));
    }

    static int[] randomLengths(Random random, int entries) {
        if (entries == 1) {
            return new int[]{1};
        }
        int[] lengths;
        do {
            lengths = completeLengths(random, entries, Math.min(24, entries));
        } while (!assignable(lengths));
        return lengths;
    }

    /** A random VQ codebook with values of lookup type 1 or 2. */
    static Book randomValueBook(Random random, int dimensions, int entries) {
        var book = new Book(dimensions, randomLengths(random, entries));
        book.lookupType = 1 + random.nextInt(2);
        book.minimum = pack(-(random.nextInt(64) + 1) / 16.0);
        book.delta = pack((random.nextInt(32) + 1) / 64.0);
        book.valueBits = 3 + random.nextInt(4);
        book.sequence = random.nextInt(4) == 0;
        var count = book.lookupType == 1 ? (int) Codebook.lookup1Values(entries, dimensions) : entries * dimensions;
        book.multiplicands = new int[count];
        for (var i = 0; i < count; i++) {
            book.multiplicands[i] = random.nextInt(1 << book.valueBits);
        }
        if (random.nextInt(4) == 0) {
            makeSparse(random, book);
        }
        return book;
    }

    /** Interleaves unused entries into a book, keeping the used lengths in order. */
    static void makeSparse(Random random, Book book) {
        var used = book.lengths;
        var lengths = new ArrayList<Integer>();
        for (var length : used) {
            while (random.nextInt(3) == 0) {
                lengths.add(0);
            }
            lengths.add(length);
        }
        var expanded = lengths.stream().mapToInt(Integer::intValue).toArray();
        if (book.lookupType == 2) {
            var multiplicands = new int[expanded.length * book.dimensions];
            for (var i = 0; i < multiplicands.length; i++) {
                multiplicands[i] = book.multiplicands[i % book.multiplicands.length];
            }
            book.multiplicands = multiplicands;
        } else if (book.lookupType == 1) {
            var count = (int) Codebook.lookup1Values(expanded.length, book.dimensions);
            book.multiplicands = Arrays.copyOf(book.multiplicands, count);
        }
        book.lengths = expanded;
        book.sparse = true;
    }
}

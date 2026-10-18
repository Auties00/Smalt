package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Implements the three Vorbis residue formats: partitioned, classified, multi-pass vector
 * quantization of the spectral fine structure (Vorbis I section 8).
 *
 * <p>Format 0 interleaves each codeword's scalars across the partition, format 1 stores them in
 * order, and format 2 codes all vectors of a submap as one interleaved vector decoded like format
 * 1. End-of-packet stops decoding and keeps every value decoded before it.
 *
 * @implNote This implementation bounds a format 1 or 2 codeword that overruns its partition to
 * the partition, as libvorbis' {@code vorbis_book_decodev_add} does, and adds format 0 codewords
 * one at a time as the specification describes. Format 2 maps interleaved position {@code p} to
 * vector {@code p % ch}, element {@code p / ch}, for any begin offset and partition size.
 */
final class Residue {
    /**
     * The residue format, 0, 1 or 2.
     */
    final int type;

    /**
     * The first coded element, before clamping to the vector size.
     */
    private final int begin;

    /**
     * The end of the coded range, before clamping to the vector size.
     */
    private final int end;

    /**
     * The number of elements in one partition.
     */
    private final int partitionSize;

    /**
     * The number of partition classes.
     */
    private final int classifications;

    /**
     * The codebook coding the partition classes.
     */
    private final Codebook classbook;

    /**
     * The number of partition classes one classbook codeword codes.
     */
    private final int classesPerCodeword;

    /**
     * The book of each class in each pass, {@code [class * 8 + pass]}, or {@code null}.
     */
    private final Codebook[] books;

    /**
     * The number of passes to run: one past the highest pass any class codes, at least one.
     */
    private final int passes;

    /**
     * Constructs a residue from its parsed fields.
     *
     * @param type               the residue format
     * @param begin              the first coded element
     * @param end                the end of the coded range
     * @param partitionSize      the partition size
     * @param classifications    the number of classes
     * @param classbook          the class codebook
     * @param books              the per class and pass books
     * @param passes             the number of passes to run
     */
    private Residue(int type, int begin, int end, int partitionSize, int classifications, Codebook classbook,
                    Codebook[] books, int passes) {
        this.type = type;
        this.begin = begin;
        this.end = end;
        this.partitionSize = partitionSize;
        this.classifications = classifications;
        this.classbook = classbook;
        this.classesPerCodeword = classbook.dimensions;
        this.books = books;
        this.passes = passes;
    }

    /**
     * Reads a residue configuration from the setup header (Vorbis I section 8.6.1).
     *
     * @param reader the setup header reader, positioned after the residue type
     * @param type   the residue format, 0, 1 or 2
     * @param books  the codebooks of the stream
     * @return the residue
     * @throws MediaException.Malformed if the configuration is truncated, references a missing
     *                                  codebook, uses a book without values, or its classbook
     *                                  cannot code every class combination
     */
    static Residue read(BitReader reader, int type, Codebook[] books) {
        var begin = reader.read(24);
        var end = reader.read(24);
        var partitionSize = reader.read(24) + 1;
        var classifications = reader.read(6) + 1;
        var classbookNumber = reader.read(8);
        if (classbookNumber >= books.length) {
            throw new MediaException.Malformed("Vorbis residue references missing classbook " + classbookNumber);
        }
        var classbook = books[classbookNumber];
        if (classbook.dimensions == 0) {
            throw new MediaException.Malformed("Vorbis residue classbook with zero dimensions");
        }
        long combinations = 1;
        for (var i = 0; i < classbook.dimensions; i++) {
            combinations *= classifications;
            if (combinations > classbook.entries) {
                throw new MediaException.Malformed("Vorbis residue classbook cannot code " + classifications
                        + "^" + classbook.dimensions + " class combinations");
            }
        }
        var cascades = new int[classifications];
        for (var i = 0; i < classifications; i++) {
            var low = reader.read(3);
            var high = reader.readBit() == 1 ? reader.read(5) : 0;
            cascades[i] = high << 3 | low;
        }
        var passBooks = new Codebook[classifications * 8];
        var passes = 1;
        for (var i = 0; i < classifications; i++) {
            for (var pass = 0; pass < 8; pass++) {
                if ((cascades[i] & (1 << pass)) == 0) {
                    continue;
                }
                var number = reader.read(8);
                if (number >= books.length) {
                    throw new MediaException.Malformed("Vorbis residue references missing codebook " + number);
                }
                var book = books[number];
                if (!book.hasValues()) {
                    throw new MediaException.Malformed("Vorbis residue uses codebook " + number + " without values");
                }
                passBooks[i * 8 + pass] = book;
                passes = Math.max(passes, pass + 1);
            }
        }
        VorbisSetup.checkNotTruncated(reader);
        return new Residue(type, begin, end, partitionSize, classifications, classbook, passBooks, passes);
    }

    /**
     * Returns the number of partitions coded in one vector of a block.
     *
     * @param half     the half block size
     * @param channels the number of vectors in the bundle, used only by format 2
     * @return the number of partitions to read
     */
    int partitions(int half, int channels) {
        var actual = type == 2 ? (long) half * channels : half;
        var limitBegin = Math.min(begin, actual);
        var limitEnd = Math.min(end, actual);
        return limitEnd <= limitBegin ? 0 : (int) ((limitEnd - limitBegin) / partitionSize);
    }

    /**
     * Decodes the residue vectors of one submap (Vorbis I section 8.6.2).
     *
     * <p>Every vector is zeroed over {@code half} elements first, including vectors marked not to
     * be decoded.
     *
     * @param reader      the packet reader, positioned at the residue data
     * @param vectors     the residue vectors of the submap, at least {@code half} long each
     * @param count       the number of vectors in the submap
     * @param doNotDecode whether each vector is excluded from decoding
     * @param half        the half block size
     * @param classes     the class scratch, one row per vector (one row for format 2), each at
     *                    least {@link #partitions(int, int)} long
     */
    void decode(BitReader reader, float[][] vectors, int count, boolean[] doNotDecode, int half, int[][] classes) {
        for (var i = 0; i < count; i++) {
            Arrays.fill(vectors[i], 0, half, 0.0f);
        }
        if (type == 2) {
            var any = false;
            for (var i = 0; i < count; i++) {
                any |= !doNotDecode[i];
            }
            if (any) {
                decodeInterleaved(reader, vectors, count, half, classes[0]);
            }
            return;
        }
        var partitions = partitions(half, count);
        if (partitions == 0) {
            return;
        }
        var limitBegin = Math.min(begin, half);
        var perCodeword = classesPerCodeword;
        for (var pass = 0; pass < passes; pass++) {
            var partition = 0;
            while (partition < partitions) {
                if (pass == 0) {
                    for (var j = 0; j < count; j++) {
                        if (!doNotDecode[j] && !readClasses(reader, classes[j], partition, partitions)) {
                            return;
                        }
                    }
                }
                for (var i = 0; i < perCodeword && partition < partitions; i++, partition++) {
                    var offset = limitBegin + partition * partitionSize;
                    for (var j = 0; j < count; j++) {
                        if (doNotDecode[j]) {
                            continue;
                        }
                        var book = books[classes[j][partition] * 8 + pass];
                        if (book == null) {
                            continue;
                        }
                        var ok = type == 0
                                ? decodeFormat0(reader, book, vectors[j], offset)
                                : decodeFormat1(reader, book, vectors[j], offset);
                        if (!ok) {
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * Decodes a format 2 residue: one interleaved vector over all vectors of the submap.
     *
     * @param reader  the packet reader
     * @param vectors the residue vectors of the submap
     * @param count   the number of vectors
     * @param half    the half block size
     * @param classes the class scratch of the interleaved vector
     */
    private void decodeInterleaved(BitReader reader, float[][] vectors, int count, int half, int[] classes) {
        var partitions = partitions(half, count);
        if (partitions == 0) {
            return;
        }
        var limitBegin = (int) Math.min(begin, (long) half * count);
        var perCodeword = classesPerCodeword;
        for (var pass = 0; pass < passes; pass++) {
            var partition = 0;
            while (partition < partitions) {
                if (pass == 0 && !readClasses(reader, classes, partition, partitions)) {
                    return;
                }
                for (var i = 0; i < perCodeword && partition < partitions; i++, partition++) {
                    var book = books[classes[partition] * 8 + pass];
                    if (book == null) {
                        continue;
                    }
                    var offset = limitBegin + partition * partitionSize;
                    var ok = switch (count) {
                        case 1 -> decodeFormat1(reader, book, vectors[0], offset);
                        case 2 -> decodeStereo(reader, book, vectors[0], vectors[1], offset);
                        default -> decodeFormat2(reader, book, vectors, count, offset);
                    };
                    if (!ok) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * Reads one classbook codeword and stores the partition classes it codes.
     *
     * @param reader     the packet reader
     * @param classes    the class row of the vector
     * @param partition  the first partition the codeword covers
     * @param partitions the number of partitions in the vector
     * @return {@code false} at end-of-packet
     */
    private boolean readClasses(BitReader reader, int[] classes, int partition, int partitions) {
        var temp = classbook.decode(reader);
        if (temp < 0) {
            return false;
        }
        var divisor = classifications;
        for (var i = classesPerCodeword - 1; i >= 0; i--) {
            if (partition + i < partitions) {
                classes[partition + i] = temp % divisor;
            }
            temp /= divisor;
        }
        return true;
    }

    /**
     * Returns whether a partition's codewords fit in the remaining packet bits whatever they are,
     * so its reads need no end-of-packet checks.
     *
     * @param reader    the packet reader
     * @param book      the partition's codebook
     * @param codewords the largest number of codewords the partition can read
     * @return {@code true} when end-of-packet cannot occur within the partition
     */
    private static boolean safe(BitReader reader, Codebook book, int codewords) {
        return reader.bitsLeft() >= (long) codewords * book.maxLength;
    }

    /**
     * Decodes one format 0 partition, interleaving each codeword's scalars with a stride of the
     * partition size over the codebook dimensions.
     *
     * @param reader the packet reader
     * @param book   the partition's codebook
     * @param vector the residue vector
     * @param offset the first element of the partition
     * @return {@code false} at end-of-packet
     */
    private boolean decodeFormat0(BitReader reader, Codebook book, float[] vector, int offset) {
        var dimensions = book.dimensions;
        var step = partitionSize / dimensions;
        var values = book.values;
        var safe = safe(reader, book, step);
        for (var i = 0; i < step; i++) {
            var entry = book.decodeUnchecked(reader);
            if (entry < 0 || !safe && reader.bitsLeft() < 0) {
                return false;
            }
            var base = entry * dimensions;
            for (var j = 0; j < dimensions; j++) {
                vector[offset + i + j * step] += values[base + j];
            }
        }
        return true;
    }

    /**
     * Decodes one format 1 partition, adding each codeword's scalars in order.
     *
     * @param reader the packet reader
     * @param book   the partition's codebook
     * @param vector the residue vector
     * @param offset the first element of the partition
     * @return {@code false} at end-of-packet
     */
    private boolean decodeFormat1(BitReader reader, Codebook book, float[] vector, int offset) {
        var dimensions = book.dimensions;
        var values = book.values;
        var position = offset;
        var end = offset + partitionSize;
        var safe = safe(reader, book, partitionSize);
        if (safe) {
            return decodeRun(reader, book, vector, position, end);
        }
        while (position < end) {
            var entry = book.decodeUnchecked(reader);
            if (entry < 0 || !safe && reader.bitsLeft() < 0) {
                return false;
            }
            var base = entry * dimensions;
            var take = Math.min(dimensions, end - position);
            for (var j = 0; j < take; j++) {
                vector[position + j] += values[base + j];
            }
            position += take;
        }
        return true;
    }

    /**
     * Decodes one format 2 partition of a two-vector submap.
     *
     * @param reader the packet reader
     * @param book   the partition's codebook
     * @param left   the first residue vector
     * @param right  the second residue vector
     * @param offset the first interleaved position of the partition
     * @return {@code false} at end-of-packet
     */
    private boolean decodeStereo(BitReader reader, Codebook book, float[] left, float[] right, int offset) {
        var dimensions = book.dimensions;
        var values = book.values;
        var safe = safe(reader, book, partitionSize);
        if (((offset | partitionSize | dimensions) & 1) == 0) {
            var index = offset >>> 1;
            var end = (offset + partitionSize) >>> 1;
            if (safe) {
                return decodePairs(reader, book, left, right, index, end);
            }
            if (dimensions == 2) {
                for (; index < end; index++) {
                    var entry = book.decodeUnchecked(reader);
                    if (entry < 0 || !safe && reader.bitsLeft() < 0) {
                        return false;
                    }
                    left[index] += values[2 * entry];
                    right[index] += values[2 * entry + 1];
                }
                return true;
            }
            var pairs = dimensions >>> 1;
            while (index < end) {
                var entry = book.decodeUnchecked(reader);
                if (entry < 0 || !safe && reader.bitsLeft() < 0) {
                    return false;
                }
                var base = entry * dimensions;
                var take = Math.min(pairs, end - index);
                for (var j = 0; j < take; j++) {
                    left[index + j] += values[base + 2 * j];
                    right[index + j] += values[base + 2 * j + 1];
                }
                index += take;
            }
            return true;
        }
        var position = offset;
        var end = offset + partitionSize;
        while (position < end) {
            var entry = book.decodeUnchecked(reader);
            if (entry < 0 || !safe && reader.bitsLeft() < 0) {
                return false;
            }
            var base = entry * dimensions;
            var take = Math.min(dimensions, end - position);
            for (var j = 0; j < take; j++) {
                var p = position + j;
                var target = (p & 1) == 0 ? left : right;
                target[p >>> 1] += values[base + j];
            }
            position += take;
        }
        return true;
    }

    /**
     * Decodes the codewords of a format 1 run that cannot reach end-of-packet, adding each
     * codeword's scalars to consecutive elements.
     *
     * <p>The reader's bit cache is held in local variables for the whole run and written back at
     * the end, or around the rare codewords longer than the direct lookup table.
     *
     * @param reader   the packet reader, with enough bits left for every codeword of the run
     * @param book     the run's codebook
     * @param vector   the residue vector
     * @param position the first element of the run
     * @param end      the end of the run, exclusive
     * @return {@code false} for a codebook without used entries
     */
    private static boolean decodeRun(BitReader reader, Codebook book, float[] vector, int position, int end) {
        var dimensions = book.dimensions;
        var values = book.values;
        var fast = book.fast;
        var mask = fast.length - 1;
        var data = reader.data;
        var limit = reader.end - 8;
        var cache = reader.cache;
        var count = reader.count;
        var next = reader.next;
        while (position < end) {
            if (count < 32) {
                if (next <= limit) {
                    cache |= (long) BitReader.LONG_LE.get(data, next) << count;
                    var bytes = (63 - count) >>> 3;
                    next += bytes;
                    count += bytes << 3;
                } else {
                    reader.cache = cache;
                    reader.count = count;
                    reader.next = next;
                    reader.refill();
                    cache = reader.cache;
                    count = reader.count;
                    next = reader.next;
                }
            }
            var slot = (int) cache & mask;
            var value = fast[slot];
            int entry;
            if (value >= 0) {
                var length = value & 31;
                cache >>>= length;
                count -= length;
                entry = value >>> 5;
            } else {
                reader.cache = cache;
                reader.count = count;
                reader.next = next;
                entry = book.decodeLong(reader, slot);
                if (entry < 0) {
                    return false;
                }
                cache = reader.cache;
                count = reader.count;
                next = reader.next;
            }
            var base = entry * dimensions;
            var take = Math.min(dimensions, end - position);
            for (var j = 0; j < take; j++) {
                vector[position + j] += values[base + j];
            }
            position += take;
        }
        reader.cache = cache;
        reader.count = count;
        reader.next = next;
        return true;
    }

    /**
     * Decodes the codewords of an even-aligned format 2 run over two vectors that cannot reach
     * end-of-packet: each codeword's scalars alternate between the two vectors.
     *
     * <p>The reader's bit cache is held in local variables for the whole run and written back at
     * the end, or around the rare codewords longer than the direct lookup table.
     *
     * @param reader the packet reader, with enough bits left for every codeword of the run
     * @param book   the run's codebook, with an even number of dimensions
     * @param left   the first residue vector
     * @param right  the second residue vector
     * @param index  the first element of each vector the run covers
     * @param end    the end of the run in each vector, exclusive
     * @return {@code false} for a codebook without used entries
     */
    private static boolean decodePairs(BitReader reader, Codebook book, float[] left, float[] right, int index, int end) {
        var dimensions = book.dimensions;
        var pairs = dimensions >>> 1;
        var values = book.values;
        var fast = book.fast;
        var mask = fast.length - 1;
        var data = reader.data;
        var limit = reader.end - 8;
        var cache = reader.cache;
        var count = reader.count;
        var next = reader.next;
        while (index < end) {
            if (count < 32) {
                if (next <= limit) {
                    cache |= (long) BitReader.LONG_LE.get(data, next) << count;
                    var bytes = (63 - count) >>> 3;
                    next += bytes;
                    count += bytes << 3;
                } else {
                    reader.cache = cache;
                    reader.count = count;
                    reader.next = next;
                    reader.refill();
                    cache = reader.cache;
                    count = reader.count;
                    next = reader.next;
                }
            }
            var slot = (int) cache & mask;
            var value = fast[slot];
            int entry;
            if (value >= 0) {
                var length = value & 31;
                cache >>>= length;
                count -= length;
                entry = value >>> 5;
            } else {
                reader.cache = cache;
                reader.count = count;
                reader.next = next;
                entry = book.decodeLong(reader, slot);
                if (entry < 0) {
                    return false;
                }
                cache = reader.cache;
                count = reader.count;
                next = reader.next;
            }
            var base = entry * dimensions;
            if (pairs == 1) {
                left[index] += values[base];
                right[index] += values[base + 1];
                index++;
                continue;
            }
            var take = Math.min(pairs, end - index);
            for (var j = 0; j < take; j++) {
                left[index + j] += values[base + 2 * j];
                right[index + j] += values[base + 2 * j + 1];
            }
            index += take;
        }
        reader.cache = cache;
        reader.count = count;
        reader.next = next;
        return true;
    }

    /**
     * Decodes one format 2 partition of a submap with any number of vectors.
     *
     * @param reader  the packet reader
     * @param book    the partition's codebook
     * @param vectors the residue vectors
     * @param count   the number of vectors
     * @param offset  the first interleaved position of the partition
     * @return {@code false} at end-of-packet
     */
    private boolean decodeFormat2(BitReader reader, Codebook book, float[][] vectors, int count, int offset) {
        var dimensions = book.dimensions;
        var values = book.values;
        var channel = offset % count;
        var index = offset / count;
        var remaining = partitionSize;
        var safe = safe(reader, book, partitionSize);
        while (remaining > 0) {
            var entry = book.decodeUnchecked(reader);
            if (entry < 0 || !safe && reader.bitsLeft() < 0) {
                return false;
            }
            var base = entry * dimensions;
            var take = Math.min(dimensions, remaining);
            for (var j = 0; j < take; j++) {
                vectors[channel][index] += values[base + j];
                if (++channel == count) {
                    channel = 0;
                    index++;
                }
            }
            remaining -= take;
        }
        return true;
    }
}

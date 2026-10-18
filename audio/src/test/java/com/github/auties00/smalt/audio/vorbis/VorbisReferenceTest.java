package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.vorbis.internal.Ilog;
import com.github.auties00.smalt.internal.bits.LsbBitWriter;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Differential test against {@link ReferenceVorbisDecoder} on randomly generated streams whose
 * headers exercise what libvorbis never writes: floor 0, residue formats 0 and 2 over more than
 * two channels, several submaps, lattice books with sequence_p, sparse and length-ordered books,
 * single-entry books, codewords longer than the decoder's direct table, odd partition sizes and
 * residue ranges beyond the block. Packets are random bits behind a valid mode number, so they
 * also end at every possible point of the floor and residue data.
 */
class VorbisReferenceTest {
    private static final int[][] STREAM_TO_WAVE = {null, {0}, {0, 1}, {0, 2, 1}, {0, 1, 2, 3}, {0, 2, 1, 3, 4},
            {0, 2, 1, 4, 5, 3}};

    static IntStream seeds() {
        return IntStream.range(0, 160);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    @DisplayName("decodes random streams like the literal specification decoder")
    void random(int seed) {
        var random = new Random(0xC0BA17L * (seed + 1));
        var stream = randomStream(random);
        var decoder = new VorbisDecoder(stream.track());
        var reference = new ReferenceVorbisDecoder(stream);
        for (var index = 0; index < 14; index++) {
            var packet = randomPacket(random, stream, index);
            double[][] expected;
            try {
                expected = reference.decode(packet);
            } catch (IllegalArgumentException undecodable) {
                assertThrows(MediaException.Malformed.class, () -> decode(decoder, packet));
                continue;
            }
            var actual = decode(decoder, packet);
            if (expected == null || expected.length == 0) {
                assertNull(actual, "seed " + seed + " packet " + index + " should yield no samples");
                continue;
            }
            assertNotNull(actual, "seed " + seed + " packet " + index + " yielded no samples");
            compare(seed, index, stream.channels, expected, actual);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3})
    @DisplayName("decodes random streams with 4096 and 8192 sample blocks like the literal decoder")
    void largeBlocks(int seed) {
        var random = new Random(0xB10CL * seed);
        var stream = randomStream(random);
        stream.blocksize0Exponent = 12;
        stream.blocksize1Exponent = 13;
        var decoder = new VorbisDecoder(stream.track());
        var reference = new ReferenceVorbisDecoder(stream);
        for (var index = 0; index < 5; index++) {
            var packet = randomPacket(random, stream, index);
            var expected = reference.decode(packet);
            var actual = decode(decoder, packet);
            if (expected == null || expected.length == 0) {
                assertNull(actual);
            } else {
                compare(seed, index, stream.channels, expected, actual);
            }
        }
    }

    @Test
    @DisplayName("covers floor 0, every residue format, several submaps and long codewords across the seeds")
    void coverage() {
        var floor0 = 0;
        var residue0 = 0;
        var residue2Wide = 0;
        var submaps = 0;
        var longCodes = 0;
        var lattice = 0;
        for (var seed : seeds().toArray()) {
            var stream = randomStream(new Random(0xC0BA17L * (seed + 1)));
            floor0 += stream.floors.stream().anyMatch(f -> f instanceof VorbisStreamBuilder.Floor0Spec) ? 1 : 0;
            residue0 += stream.residues.stream().anyMatch(r -> r.type() == 0) ? 1 : 0;
            residue2Wide += stream.channels > 2 && stream.residues.stream().anyMatch(r -> r.type() == 2) ? 1 : 0;
            submaps += stream.mappings.stream().anyMatch(m -> m.submaps() > 1) ? 1 : 0;
            longCodes += stream.books.stream().anyMatch(b -> IntStream.of(b.lengths).max().orElse(0) > 10) ? 1 : 0;
            lattice += stream.books.stream().anyMatch(b -> b.lookupType == 1 && b.sequence) ? 1 : 0;
        }
        assertTrue(floor0 > 20 && residue0 > 20 && residue2Wide > 10 && submaps > 10 && longCodes > 20 && lattice > 10,
                floor0 + " " + residue0 + " " + residue2Wide + " " + submaps + " " + longCodes + " " + lattice);
    }

    private static float[][] decode(VorbisDecoder decoder, byte[] packet) {
        var result = new float[1][][];
        decoder.decode(new Packet(0, packet, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true), (AudioBuffer buffer) -> {
            var copy = new float[buffer.channels()][buffer.frames()];
            for (var c = 0; c < copy.length; c++) {
                System.arraycopy(buffer.plane(c), 0, copy[c], 0, buffer.frames());
            }
            result[0] = copy;
        });
        return result[0];
    }

    private static void compare(int seed, int index, int channels, double[][] expected, float[][] actual) {
        for (var ch = 0; ch < channels; ch++) {
            var plane = channels < STREAM_TO_WAVE.length ? STREAM_TO_WAVE[channels][ch] : ch;
            var want = expected[ch];
            var got = actual[plane];
            assertEquals(want.length, got.length, "seed " + seed + " packet " + index + " length");
            var signal = 0.0;
            var noise = 0.0;
            var finite = true;
            for (var i = 0; i < want.length; i++) {
                finite &= Double.isFinite(want[i]);
                signal += want[i] * want[i];
                var difference = want[i] - got[i];
                noise += difference * difference;
            }
            if (!finite || !Double.isFinite(signal)) {
                continue;
            }
            if (signal == 0) {
                for (var value : got) {
                    assertEquals(0.0f, value, 0.0f, "seed " + seed + " packet " + index + " should be silent");
                }
                continue;
            }
            var snr = 10 * Math.log10(signal / Math.max(noise, Double.MIN_VALUE));
            assertTrue(snr >= 90, "seed " + seed + " packet " + index + " channel " + ch + " SNR " + snr);
        }
    }

    static byte[] randomPacket(Random random, VorbisStreamBuilder stream, int index) {
        var writer = new LsbBitWriter();
        writer.write(0, 1);
        var modes = stream.modes.size();
        var mode = random.nextInt(modes);
        writer.write(mode, Ilog.ilog(modes - 1));
        if (stream.modes.get(mode).longBlock()) {
            writer.write(random.nextInt(2), 1);
            writer.write(random.nextInt(2), 1);
        }
        var length = switch (random.nextInt(4)) {
            case 0 -> random.nextInt(8);
            case 1 -> random.nextInt(64);
            default -> 64 + random.nextInt(1200);
        };
        for (var i = 0; i < length; i++) {
            writer.write(random.nextInt(256), 8);
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    static VorbisStreamBuilder randomStream(Random random) {
        var stream = new VorbisStreamBuilder();
        stream.channels = random.nextInt(8) == 0 ? 6 : 1 + random.nextInt(4);
        stream.blocksize0Exponent = 6 + random.nextInt(3);
        stream.blocksize1Exponent = stream.blocksize0Exponent + random.nextInt(11 - stream.blocksize0Exponent);
        var half1 = 1 << (stream.blocksize1Exponent - 1);
        var scalar = new ArrayList<Integer>();
        for (var i = 0; i < 3; i++) {
            var entries = random.nextInt(3) == 0 ? 1 : 2 + random.nextInt(i == 2 ? 300 : 40);
            var book = new VorbisStreamBuilder.Book(1, VorbisStreamBuilder.randomLengths(random, entries));
            if (random.nextInt(3) == 0 && entries > 1) {
                book.lengths = java.util.Arrays.stream(book.lengths).sorted().toArray();
                book.ordered = true;
            } else if (random.nextInt(3) == 0) {
                VorbisStreamBuilder.makeSparse(random, book);
            }
            scalar.add(stream.books.size());
            stream.books.add(book);
        }
        var vq = new ArrayList<Integer>();
        for (var i = 0; i < 4; i++) {
            var dimensions = random.nextInt(6) == 0 ? 8 : 1 + random.nextInt(4);
            var entries = random.nextInt(8) == 0 ? 1 : 2 + random.nextInt(i == 3 ? 400 : 60);
            var book = VorbisStreamBuilder.randomValueBook(random, dimensions, entries);
            if (book.lookupType == 1 && random.nextInt(2) == 0) {
                book.sequence = true;
            }
            vq.add(stream.books.size());
            stream.books.add(book);
        }
        var floor0Books = new ArrayList<Integer>();
        for (var i = 0; i < 2; i++) {
            var book = VorbisStreamBuilder.randomValueBook(random, 1 + random.nextInt(3), 2 + random.nextInt(30));
            book.minimum = VorbisStreamBuilder.pack(0.05);
            book.delta = VorbisStreamBuilder.pack(0.02);
            book.valueBits = 3;
            for (var j = 0; j < book.multiplicands.length; j++) {
                book.multiplicands[j] &= 7;
            }
            book.sequence = false;
            floor0Books.add(stream.books.size());
            stream.books.add(book);
        }
        var floorCount = 1 + random.nextInt(2);
        for (var i = 0; i < floorCount; i++) {
            if (random.nextInt(3) == 0) {
                var books = new int[1 + random.nextInt(2)];
                for (var j = 0; j < books.length; j++) {
                    books[j] = floor0Books.get(random.nextInt(floor0Books.size()));
                }
                stream.floors.add(new VorbisStreamBuilder.Floor0Spec(1 + random.nextInt(16), 8000 + random.nextInt(40000),
                        16 + random.nextInt(240), 4 + random.nextInt(5), 1 + random.nextInt(10), books));
            } else {
                stream.floors.add(randomFloor1(random, scalar, half1));
            }
        }
        var residueCount = 1 + random.nextInt(2);
        for (var i = 0; i < residueCount; i++) {
            var type = random.nextInt(3);
            var classifications = 1 + random.nextInt(4);
            var classDimensions = 1 + random.nextInt(classifications == 1 ? 4 : 2);
            var combinations = (int) Math.pow(classifications, classDimensions);
            var classbook = new VorbisStreamBuilder.Book(classDimensions,
                    VorbisStreamBuilder.randomLengths(random, combinations + random.nextInt(4)));
            var classbookNumber = stream.books.size();
            stream.books.add(classbook);
            var books = new int[classifications][8];
            for (var c = 0; c < classifications; c++) {
                java.util.Arrays.fill(books[c], -1);
                for (var pass = 0; pass < 3; pass++) {
                    if (random.nextInt(3) != 0) {
                        books[c][pass] = vq.get(random.nextInt(vq.size()));
                    }
                }
                if (random.nextInt(10) == 0) {
                    books[c][5] = vq.get(random.nextInt(vq.size()));
                }
            }
            var span = type == 2 ? half1 * stream.channels : half1;
            var begin = random.nextInt(4) == 0 ? random.nextInt(64) : 0;
            var end = random.nextInt(5) == 0 ? span + random.nextInt(100) : begin + random.nextInt(span);
            var partition = random.nextInt(3) == 0 ? 1 + random.nextInt(48) : 8 * (1 + random.nextInt(4));
            stream.residues.add(new VorbisStreamBuilder.ResidueSpec(type, begin, end, partition, classifications,
                    classbookNumber, books));
        }
        var mappingCount = 1 + random.nextInt(2);
        for (var i = 0; i < mappingCount; i++) {
            var submaps = stream.channels > 1 && random.nextInt(3) == 0 ? 2 : 1;
            var mux = new int[stream.channels];
            for (var ch = 0; ch < stream.channels; ch++) {
                mux[ch] = random.nextInt(submaps);
            }
            var steps = stream.channels > 1 ? random.nextInt(stream.channels) : 0;
            var magnitude = new int[steps];
            var angle = new int[steps];
            for (var s = 0; s < steps; s++) {
                magnitude[s] = random.nextInt(stream.channels);
                do {
                    angle[s] = random.nextInt(stream.channels);
                } while (angle[s] == magnitude[s]);
            }
            var floors = new int[submaps];
            var residues = new int[submaps];
            for (var s = 0; s < submaps; s++) {
                floors[s] = random.nextInt(stream.floors.size());
                residues[s] = random.nextInt(stream.residues.size());
            }
            stream.mappings.add(new VorbisStreamBuilder.MappingSpec(submaps, magnitude, angle, mux, floors, residues));
        }
        var modeCount = 1 + random.nextInt(4);
        for (var i = 0; i < modeCount; i++) {
            stream.modes.add(new VorbisStreamBuilder.ModeSpec(random.nextBoolean(), random.nextInt(stream.mappings.size())));
        }
        return stream;
    }

    private static VorbisStreamBuilder.Floor1Spec randomFloor1(Random random, List<Integer> scalar, int half) {
        var classes = 1 + random.nextInt(3);
        var classDimensions = new int[classes];
        var classSubclasses = new int[classes];
        var classMasterbooks = new int[classes];
        var subclassBooks = new int[classes][];
        for (var c = 0; c < classes; c++) {
            classDimensions[c] = 1 + random.nextInt(4);
            classSubclasses[c] = random.nextInt(3);
            classMasterbooks[c] = classSubclasses[c] == 0 ? -1 : scalar.get(random.nextInt(scalar.size()));
            subclassBooks[c] = new int[1 << classSubclasses[c]];
            for (var j = 0; j < subclassBooks[c].length; j++) {
                subclassBooks[c][j] = random.nextInt(4) == 0 ? -1 : scalar.get(random.nextInt(scalar.size()));
            }
        }
        var rangeBits = Math.max(4, Integer.numberOfTrailingZeros(half) + random.nextInt(3) - 1);
        var partitions = new ArrayList<Integer>();
        var total = 0;
        var available = (1 << rangeBits) - 1;
        var count = random.nextInt(6);
        for (var i = 0; i < count; i++) {
            var c = random.nextInt(classes);
            if (total + classDimensions[c] > Math.min(63, available)) {
                break;
            }
            partitions.add(c);
            total += classDimensions[c];
        }
        var xs = new int[total];
        var used = new java.util.HashSet<Integer>();
        used.add(0);
        for (var i = 0; i < total; i++) {
            int x;
            do {
                x = 1 + random.nextInt(available);
            } while (!used.add(x));
            xs[i] = x;
        }
        return new VorbisStreamBuilder.Floor1Spec(partitions.stream().mapToInt(Integer::intValue).toArray(),
                classDimensions, classSubclasses, classMasterbooks, subclassBooks, 1 + random.nextInt(4), rangeBits, xs);
    }
}

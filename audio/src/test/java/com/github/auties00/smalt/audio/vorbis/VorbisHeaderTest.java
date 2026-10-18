package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.internal.header.XiphLacing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that every condition Vorbis I calls undecodable in the three headers is rejected with the
 * right exception, starting each case from a valid hand-built stream and corrupting one field.
 */
class VorbisHeaderTest {
    private static void assertRejects(Class<? extends MediaException> expected, Consumer<VorbisStreamBuilder> corruption) {
        var stream = VorbisStreamBuilder.minimal(2);
        corruption.accept(stream);
        assertThrows(expected, () -> new VorbisDecoder(stream.track()));
    }

    @Test
    @DisplayName("accepts the unmodified minimal streams")
    void valid() {
        for (var channels = 1; channels <= 3; channels++) {
            var decoder = new VorbisDecoder(VorbisStreamBuilder.minimal(channels).track());
            assertEquals(channels, decoder.layout().channels());
            assertEquals(44100, decoder.sampleRate());
        }
    }

    @Nested
    @DisplayName("codec configuration lacing")
    class Lacing {
        @Test
        @DisplayName("rejects an empty configuration, a wrong packet count and lacing past the end")
        void lacing() {
            var config = VorbisStreamBuilder.minimal(1).config();
            assertThrows(MediaException.Malformed.class, () -> new VorbisDecoder(VorbisStreamBuilder.track(new byte[0])));
            var two = config.clone();
            two[0] = 1;
            assertThrows(MediaException.Malformed.class, () -> new VorbisDecoder(VorbisStreamBuilder.track(two)));
            var four = config.clone();
            four[0] = 3;
            assertThrows(MediaException.Malformed.class, () -> new VorbisDecoder(VorbisStreamBuilder.track(four)));
            assertThrows(MediaException.Malformed.class,
                    () -> new VorbisDecoder(VorbisStreamBuilder.track(new byte[]{2, (byte) 255, (byte) 255})));
            var oversized = config.clone();
            oversized[1] = (byte) 250;
            oversized[2] = (byte) 250;
            assertThrows(MediaException.Malformed.class, () -> new VorbisDecoder(VorbisStreamBuilder.track(oversized)));
        }

        @Test
        @DisplayName("rejects every truncation of the configuration")
        void truncation() {
            var config = VorbisStreamBuilder.minimal(2).config();
            for (var length = 0; length < config.length; length++) {
                var prefix = Arrays.copyOf(config, length);
                assertThrows(MediaException.class, () -> new VorbisDecoder(VorbisStreamBuilder.track(prefix)),
                        "prefix of " + length + " bytes");
            }
        }

        @Test
        @DisplayName("parses lacing values of 255 and longer comment packets")
        void longComment() {
            var stream = VorbisStreamBuilder.minimal(1);
            var comment = new byte[700];
            System.arraycopy(VorbisStreamBuilder.comment(), 0, comment, 0, 7);
            var config = XiphLacing.lace(stream.identification(), comment, stream.setup());
            assertEquals(1, new VorbisDecoder(VorbisStreamBuilder.track(config)).layout().channels());
        }
    }

    @Nested
    @DisplayName("identification header")
    class Identification {
        @Test
        @DisplayName("rejects a nonzero version as unsupported")
        void version() {
            assertRejects(MediaException.Unsupported.class, stream -> stream.version = 1);
        }

        @Test
        @DisplayName("rejects zero channels, a zero rate and a cleared framing bit")
        void fields() {
            assertRejects(MediaException.Malformed.class, stream -> stream.channels = 0);
            assertRejects(MediaException.Malformed.class, stream -> stream.sampleRate = 0);
            assertRejects(MediaException.Malformed.class, stream -> stream.identificationFraming = 0);
        }

        @Test
        @DisplayName("rejects rates beyond the int range as unsupported")
        void hugeRate() {
            assertRejects(MediaException.Unsupported.class, stream -> stream.sampleRate = 0x80000000L);
        }

        @ParameterizedTest(name = "exponents {0}")
        @ValueSource(strings = {"5/11", "8/14", "11/8", "0/0", "15/15", "6/5"})
        @DisplayName("rejects block sizes outside 64..8192 or out of order")
        void blocksizes(String exponents) {
            var parts = exponents.split("/");
            assertRejects(MediaException.Malformed.class, stream -> {
                stream.blocksize0Exponent = Integer.parseInt(parts[0]);
                stream.blocksize1Exponent = Integer.parseInt(parts[1]);
            });
        }

        @ParameterizedTest(name = "exponents {0}")
        @ValueSource(strings = {"6/6", "6/13", "13/13", "8/8"})
        @DisplayName("accepts every legal block size pair, including equal sizes")
        void legalBlocksizes(String exponents) {
            var parts = exponents.split("/");
            var stream = VorbisStreamBuilder.minimal(1);
            stream.blocksize0Exponent = Integer.parseInt(parts[0]);
            stream.blocksize1Exponent = Integer.parseInt(parts[1]);
            assertNotNull(new VorbisDecoder(stream.track()));
        }

        @Test
        @DisplayName("rejects wrong packet types and signatures on all three headers")
        void signatures() {
            var stream = VorbisStreamBuilder.minimal(1);
            byte[][] headers = {stream.identification(), VorbisStreamBuilder.comment(), stream.setup()};
            for (var header = 0; header < 3; header++) {
                for (var position : new int[]{0, 1, 6}) {
                    var copy = new byte[][]{headers[0].clone(), headers[1].clone(), headers[2].clone()};
                    copy[header][position] ^= 0x20;
                    var config = XiphLacing.lace(copy);
                    assertThrows(MediaException.Malformed.class, () -> new VorbisDecoder(VorbisStreamBuilder.track(config)),
                            "header " + header + " byte " + position);
                }
            }
            var swapped = XiphLacing.lace(headers[1], headers[0], headers[2]);
            assertThrows(MediaException.Malformed.class, () -> new VorbisDecoder(VorbisStreamBuilder.track(swapped)));
        }

        @Test
        @DisplayName("enforces the channel and allocation limits before allocating")
        void limits() {
            var stream = VorbisStreamBuilder.minimal(1);
            stream.channels = 65;
            stream.mappings.set(0, new VorbisStreamBuilder.MappingSpec(1, new int[0], new int[0], new int[65],
                    new int[]{0}, new int[]{0}));
            assertThrows(MediaException.LimitExceeded.class, () -> new VorbisDecoder(stream.track()));
            var small = AudioDecoderOptions.builder().limits(MediaLimits.builder()
                    .maxDimension(16).maxPixels(16).maxFrames(16).maxChannels(2).maxBytes(4096).build()).build();
            assertThrows(MediaException.LimitExceeded.class,
                    () -> new VorbisDecoder(VorbisStreamBuilder.minimal(2).track(), small));
            var wide = AudioDecoderOptions.builder().limits(MediaLimits.builder()
                    .maxDimension(16).maxPixels(16).maxFrames(16).maxChannels(255).maxBytes(1L << 30).build()).build();
            assertEquals(65, new VorbisDecoder(stream.track(), wide).layout().channels());
        }
    }

    @Nested
    @DisplayName("codebooks")
    class Codebooks {
        @Test
        @DisplayName("rejects a wrong sync pattern")
        void sync() {
            assertRejects(MediaException.Malformed.class, stream -> stream.books.get(1).sync = 0x564343);
        }

        @Test
        @DisplayName("rejects overspecified and underspecified Huffman trees")
        void trees() {
            assertRejects(MediaException.Malformed.class, stream -> stream.books.get(0).lengths = new int[]{1, 1, 1});
            assertRejects(MediaException.Malformed.class, stream -> stream.books.get(0).lengths = new int[]{1, 2});
            assertRejects(MediaException.Malformed.class, stream -> stream.books.get(0).lengths = new int[]{2, 2, 2});
            assertRejects(MediaException.Malformed.class, stream -> stream.books.get(0).lengths = new int[]{1, 2, 3, 3, 3});
        }

        @Test
        @DisplayName("rejects a single-entry book whose codeword is not one bit long (errata 20150226)")
        void singleEntry() {
            assertRejects(MediaException.Malformed.class, stream -> {
                var book = stream.books.get(0);
                book.lengths = new int[]{0, 2, 0};
                book.sparse = true;
            });
            var stream = VorbisStreamBuilder.minimal(1);
            stream.books.get(0).lengths = new int[]{1};
            stream.residues.set(0, new VorbisStreamBuilder.ResidueSpec(1, 0, 128, 16, 1, 0,
                    new int[][]{{1, -1, -1, -1, -1, -1, -1, -1}}));
            assertNotNull(new VorbisDecoder(stream.track()));
        }

        @Test
        @DisplayName("rejects reserved lookup types as unsupported and a lattice book without dimensions")
        void lookups() {
            assertRejects(MediaException.Unsupported.class, stream -> stream.books.get(1).lookupType = 3);
            assertRejects(MediaException.Malformed.class, stream -> {
                var book = stream.books.get(1);
                book.dimensions = 0;
                book.multiplicands = new int[0];
            });
        }

        @Test
        @DisplayName("rejects ordered lengths that grow past 32 bits")
        void orderedOverflow() {
            assertRejects(MediaException.Malformed.class, stream -> {
                var book = stream.books.get(0);
                book.lengths = new int[]{32, 33};
                book.ordered = true;
            });
        }

        @Test
        @DisplayName("charges huge lattice tables against the allocation limit")
        void hugeLattice() {
            var stream = VorbisStreamBuilder.minimal(1);
            var book = new VorbisStreamBuilder.Book(4000, new int[1 << 12]);
            Arrays.fill(book.lengths, 12);
            book.ordered = true;
            book.lookupType = 1;
            book.valueBits = 1;
            book.multiplicands = new int[1];
            stream.books.add(book);
            var limits = AudioDecoderOptions.builder().limits(MediaLimits.builder()
                    .maxDimension(16).maxPixels(16).maxFrames(16).maxChannels(8).maxBytes(16L << 20).build()).build();
            assertThrows(MediaException.LimitExceeded.class, () -> new VorbisDecoder(stream.track(), limits));
        }
    }

    @Nested
    @DisplayName("time, floor and residue configurations")
    class Configurations {
        @Test
        @DisplayName("rejects nonzero time types and reserved floor types as unsupported")
        void reserved() {
            assertRejects(MediaException.Unsupported.class, stream -> stream.timeValue = 1);
            assertRejects(MediaException.Unsupported.class, stream -> stream.floors.add(2));
        }

        @Test
        @DisplayName("rejects floor 1 references to missing books, more than 65 values and repeated X")
        void floor1() {
            assertRejects(MediaException.Malformed.class, stream -> stream.floors.set(0, new VorbisStreamBuilder.Floor1Spec(
                    new int[]{0}, new int[]{2}, new int[]{1}, new int[]{9}, new int[][]{{0, 0}}, 2, 7, new int[]{32, 64})));
            assertRejects(MediaException.Malformed.class, stream -> stream.floors.set(0, new VorbisStreamBuilder.Floor1Spec(
                    new int[]{0}, new int[]{2}, new int[]{0}, new int[]{-1}, new int[][]{{9}}, 2, 7, new int[]{32, 64})));
            var classes = new int[9];
            var xs = new int[72];
            for (var i = 0; i < xs.length; i++) {
                xs[i] = i + 1;
            }
            assertRejects(MediaException.Malformed.class, stream -> stream.floors.set(0, new VorbisStreamBuilder.Floor1Spec(
                    classes, new int[]{8}, new int[]{0}, new int[]{-1}, new int[][]{{0}}, 2, 7, xs)));
            assertRejects(MediaException.Malformed.class, stream -> stream.floors.set(0, new VorbisStreamBuilder.Floor1Spec(
                    new int[]{0}, new int[]{2}, new int[]{0}, new int[]{-1}, new int[][]{{0}}, 2, 7, new int[]{32, 32})));
            assertRejects(MediaException.Malformed.class, stream -> stream.floors.set(0, new VorbisStreamBuilder.Floor1Spec(
                    new int[]{0}, new int[]{2}, new int[]{0}, new int[]{-1}, new int[][]{{0}}, 2, 7, new int[]{0, 64})));
        }

        @Test
        @DisplayName("accepts floor 1 with exactly 65 values")
        void floor1Maximum() {
            var stream = VorbisStreamBuilder.minimal(1);
            var xs = new int[63];
            for (var i = 0; i < xs.length; i++) {
                xs[i] = i + 1;
            }
            var classes = new int[21];
            stream.floors.set(0, new VorbisStreamBuilder.Floor1Spec(classes, new int[]{3}, new int[]{0}, new int[]{-1},
                    new int[][]{{0}}, 2, 7, xs));
            assertNotNull(new VorbisDecoder(stream.track()));
        }

        @Test
        @DisplayName("rejects floor 0 books without values or out of range and a zero rate or Bark map size")
        void floor0() {
            assertRejects(MediaException.Malformed.class, stream -> stream.floors.set(0,
                    new VorbisStreamBuilder.Floor0Spec(8, 44100, 64, 6, 40, new int[]{0})));
            assertRejects(MediaException.Malformed.class, stream -> stream.floors.set(0,
                    new VorbisStreamBuilder.Floor0Spec(8, 44100, 64, 6, 40, new int[]{7})));
            assertRejects(MediaException.Malformed.class, stream -> stream.floors.set(0,
                    new VorbisStreamBuilder.Floor0Spec(8, 0, 64, 6, 40, new int[]{1})));
            assertRejects(MediaException.Malformed.class, stream -> stream.floors.set(0,
                    new VorbisStreamBuilder.Floor0Spec(8, 44100, 0, 6, 40, new int[]{1})));
            var stream = VorbisStreamBuilder.minimal(1);
            stream.floors.set(0, new VorbisStreamBuilder.Floor0Spec(8, 44100, 64, 6, 40, new int[]{1}));
            assertNotNull(new VorbisDecoder(stream.track()));
        }

        @Test
        @DisplayName("rejects reserved residue types, bad book references and undersized classbooks")
        void residue() {
            var books = new int[2][8];
            Arrays.fill(books[0], -1);
            Arrays.fill(books[1], -1);
            assertRejects(MediaException.Unsupported.class, stream -> stream.residues.set(0,
                    new VorbisStreamBuilder.ResidueSpec(3, 0, 128, 16, 2, 0, books)));
            assertRejects(MediaException.Malformed.class, stream -> stream.residues.set(0,
                    new VorbisStreamBuilder.ResidueSpec(1, 0, 128, 16, 2, 5, books)));
            // 3 classes cannot be coded by the two-entry classbook
            var three = new int[3][8];
            for (var row : three) {
                Arrays.fill(row, -1);
            }
            assertRejects(MediaException.Malformed.class, stream -> stream.residues.set(0,
                    new VorbisStreamBuilder.ResidueSpec(1, 0, 128, 16, 3, 0, three)));
            var scalarBook = new int[2][8];
            Arrays.fill(scalarBook[0], -1);
            Arrays.fill(scalarBook[1], -1);
            scalarBook[0][2] = 0;
            assertRejects(MediaException.Malformed.class, stream -> stream.residues.set(0,
                    new VorbisStreamBuilder.ResidueSpec(1, 0, 128, 16, 2, 0, scalarBook)));
            var missing = new int[2][8];
            Arrays.fill(missing[0], -1);
            Arrays.fill(missing[1], -1);
            missing[1][7] = 42;
            assertRejects(MediaException.Malformed.class, stream -> stream.residues.set(0,
                    new VorbisStreamBuilder.ResidueSpec(1, 0, 128, 16, 2, 0, missing)));
            assertRejects(MediaException.Malformed.class, stream -> {
                stream.books.get(0).dimensions = 0;
            });
        }
    }

    @Nested
    @DisplayName("mappings and modes")
    class Mappings {
        @Test
        @DisplayName("rejects reserved mapping types and invalid coupling, reserved bits and submap references")
        void mappings() {
            assertRejects(MediaException.Unsupported.class, stream -> stream.mappings.set(0,
                    new VorbisStreamBuilder.MappingSpec(1, new int[0], new int[0], new int[2], new int[]{0}, new int[]{0}, 1, 0)));
            assertRejects(MediaException.Malformed.class, stream -> stream.mappings.set(0,
                    new VorbisStreamBuilder.MappingSpec(1, new int[]{1}, new int[]{1}, new int[2], new int[]{0}, new int[]{0})));
            assertRejects(MediaException.Malformed.class, stream -> stream.mappings.set(0,
                    new VorbisStreamBuilder.MappingSpec(1, new int[0], new int[0], new int[2], new int[]{0}, new int[]{0}, 0, 2)));
            assertRejects(MediaException.Malformed.class, stream -> stream.mappings.set(0,
                    new VorbisStreamBuilder.MappingSpec(2, new int[0], new int[0], new int[]{0, 2}, new int[]{0, 0}, new int[]{0, 0})));
            assertRejects(MediaException.Malformed.class, stream -> stream.mappings.set(0,
                    new VorbisStreamBuilder.MappingSpec(1, new int[0], new int[0], new int[2], new int[]{1}, new int[]{0})));
            assertRejects(MediaException.Malformed.class, stream -> stream.mappings.set(0,
                    new VorbisStreamBuilder.MappingSpec(1, new int[0], new int[0], new int[2], new int[]{0}, new int[]{1})));
        }

        @Test
        @DisplayName("rejects coupling channels beyond the channel count")
        void couplingRange() {
            var stream = VorbisStreamBuilder.minimal(3);
            stream.mappings.set(0, new VorbisStreamBuilder.MappingSpec(1, new int[]{3}, new int[]{0}, new int[3],
                    new int[]{0}, new int[]{0}));
            assertThrows(MediaException.Malformed.class, () -> new VorbisDecoder(stream.track()));
        }

        @Test
        @DisplayName("rejects coupling in a mono stream, where both channel numbers read zero bits")
        void monoCoupling() {
            var stream = VorbisStreamBuilder.minimal(1);
            stream.mappings.set(0, new VorbisStreamBuilder.MappingSpec(1, new int[]{0}, new int[]{0}, new int[1],
                    new int[]{0}, new int[]{0}));
            assertThrows(MediaException.Malformed.class, () -> new VorbisDecoder(stream.track()));
        }

        @Test
        @DisplayName("rejects reserved window and transform types, missing mappings and a cleared framing bit")
        void modes() {
            assertRejects(MediaException.Unsupported.class, stream -> stream.modes.set(0, new VorbisStreamBuilder.ModeSpec(false, 1, 0, 0)));
            assertRejects(MediaException.Unsupported.class, stream -> stream.modes.set(0, new VorbisStreamBuilder.ModeSpec(false, 0, 1, 0)));
            assertRejects(MediaException.Malformed.class, stream -> stream.modes.set(0, new VorbisStreamBuilder.ModeSpec(false, 1)));
            assertRejects(MediaException.Malformed.class, stream -> stream.setupFraming = 0);
        }
    }
}

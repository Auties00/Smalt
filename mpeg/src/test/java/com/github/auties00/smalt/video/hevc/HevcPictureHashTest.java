package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the decoded picture hash SEI messages (MD5, CRC and checksum) of streams the HM reference encoder wrote on
 * pictures several coding tree block rows tall, in 4:0:0, 4:2:0, 4:2:2 and 4:4:4 at 8 to 12 bits, which x265 cannot
 * serve for CRC (it restarts the chroma CRC at each coding tree block row). The streams and the SHA-256 of the HM
 * reference decoder's output are kept with the test ({@code hash/hash.tsv}, written by
 * {@code tools/testdata/hevc-hash/make-vectors.py}, which checks that HM verified every hash): with hash verification
 * on, every picture must decode without a mismatch and the output must equal HM's, and a hash altered by one bit
 * must be reported as a mismatch.
 */
@DisplayName("HEVC decoded picture hashes from the HM reference encoder")
class HevcPictureHashTest {
    record Vector(String name, int pictures, int hashType, String streamSha256, String decodedSha256) {
        @Override
        public String toString() {
            return name;
        }

        byte[] stream() {
            try (var input = HevcPictureHashTest.class.getResourceAsStream("hash/" + name + ".hevc")) {
                if (input == null) {
                    throw new IllegalStateException("missing vector " + name);
                }
                var data = input.readAllBytes();
                assertEquals(streamSha256, sha256(data), name + " stream");
                return data;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    static Stream<Vector> vectors() {
        try (var input = HevcPictureHashTest.class.getResourceAsStream("hash/hash.tsv")) {
            var vectors = new ArrayList<Vector>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var columns = line.strip().split("\t");
                vectors.add(new Vector(columns[0], Integer.parseInt(columns[1]), Integer.parseInt(columns[2]),
                        columns[3], columns[4]));
            }
            return vectors.stream();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("verifies every hash and decodes like the HM reference decoder")
    void verified(Vector vector) {
        var pictures = new int[1];
        var decoded = decode(vector.stream(), pictures);
        assertEquals(vector.pictures(), pictures[0], vector + " pictures");
        assertEquals(vector.decodedSha256(), sha256(decoded), vector + " decoded");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("reports a hash altered by one bit as a mismatch")
    void altered(Vector vector) {
        var nals = new ArrayList<>(HevcTestStreams.splitAnnexB(vector.stream()));
        var altered = 0;
        for (var i = 0; i < nals.size(); i++) {
            var nal = nals.get(i);
            // the units keep their start code; the decoded picture hash is the suffix SEI (NAL type 40) with payload
            // type 132, whose first hash byte follows the payload type, the payload size and the hash type
            var header = nal[2] == 1 ? 3 : 4;
            var type = (nal[header] >> 1) & 0x3F;
            if (type == 40 && nal.length > header + 5 && (nal[header + 2] & 0xFF) == 132) {
                var copy = nal.clone();
                copy[header + 5] ^= 0x01;
                nals.set(i, copy);
                altered++;
                break;
            }
        }
        assertEquals(1, altered, "no picture hash SEI message");
        var exception = assertThrows(MediaException.Malformed.class, () -> decode(nals, new int[1]));
        assertTrue(exception.getMessage().contains("decoded picture hash mismatch"), exception.getMessage());
    }

    private static byte[] decode(byte[] stream, int[] pictures) {
        return decode(HevcTestStreams.splitAnnexB(stream), pictures);
    }

    private static byte[] decode(List<byte[]> nals, int[] pictures) {
        var raw = new ByteArrayOutputStream();
        var options = HevcDecoderOptions.builder().highBitDepth(true).hashVerification(true).build();
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), options)) {
            Consumer<VideoFrame> sink = frame -> {
                pictures[0]++;
                raw.writeBytes(HevcTestStreams.raw(frame.picture()));
            };
            for (var nal : nals) {
                decoder.decode(HevcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        return raw.toByteArray();
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.hevc.HevcRandomVectors.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers random access at recovery point SEI messages (ITU-T H.265 D.3.8) on streams the HM reference encoder wrote
 * with its intra pictures coded as trailing I pictures that carry recovery point messages instead of CRA pictures,
 * in a random access and a low delay P structure. The full decode of every vector must equal the HM decoder's output
 * frame by frame. A stream cut at any access unit must decode from the first recovery point at or after the cut:
 * the frames before the recovery point in output order are left out and every other frame must equal HM's full
 * decode. A recovery point message inserted at a cut, with {@code recovery_poc_cnt} reaching the next intra picture,
 * makes the decoder start at that non-intra picture, generate its missing references and output exactly HM's frames
 * from the recovery point on, with their picture hashes verified. HM numbers the pictures of these streams so that
 * the picture order count equals the output index.
 */
@DisplayName("HEVC random access at recovery points")
class HevcRandomAccessTest {
    private static final HevcDecoderOptions VERIFY = HevcDecoderOptions.builder().highBitDepth(true)
            .hashVerification(true).build();

    static Stream<Vector> vectors() {
        return HevcRandomVectors.all().stream();
    }

    static Stream<Vector> recoveryVectors() {
        return vectors().filter(vector -> vector.name().startsWith("rp-"));
    }

    @ParameterizedTest(name = "{0}, {1} threads")
    @MethodSource("fullDecodes")
    @DisplayName("decodes every vector from its start exactly as the HM reference decoder")
    void fullDecode(Vector vector, int threads) {
        var options = VERIFY.toBuilder().threads(threads).build();
        var frames = HevcRandomVectors.decode(options, vector.accessUnits());
        assertEquals(vector.frameMd5s(), HevcRandomVectors.md5s(frames));
        assertEquals(vector.width(), frames.getFirst().picture().width());
        assertEquals(vector.height(), frames.getFirst().picture().height());
    }

    static Stream<Arguments> fullDecodes() {
        return vectors().flatMap(vector -> Stream.of(Arguments.of(vector, 1), Arguments.of(vector, 4)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recoveryVectors")
    @DisplayName("flags the IDR picture and the I pictures carrying recovery points as keyframes")
    void keyframes(Vector vector) {
        var units = vector.accessUnits();
        var frames = HevcRandomVectors.decode(VERIFY, units);
        var expected = new boolean[frames.size()];
        for (var index = 0; index < units.size(); index++) {
            if (index == 0 || hasRecoveryPoint(units.get(index))) {
                expected[vector.decodingPocs()[index]] = true;
            }
        }
        for (var poc = 0; poc < frames.size(); poc++) {
            assertEquals(expected[poc], frames.get(poc).keyframe(), "keyframe flag of POC " + poc);
        }
    }

    @ParameterizedTest(name = "{0}, cut at access unit {1}")
    @MethodSource("cuts")
    @DisplayName("starts a cut stream at the first recovery point at or after the cut")
    void startsAtRecoveryPoint(Vector vector, int cut) {
        var units = vector.accessUnits();
        var recovery = nextRecovery(units, cut);
        var frames = HevcRandomVectors.decode(VERIFY, cutAt(units, cut, null));
        var recoveryPoc = vector.decodingPocs()[recovery];
        assertEquals(vector.frameMd5s().subList(recoveryPoc, vector.frames()), HevcRandomVectors.md5s(frames));
        assertTrue(frames.getFirst().keyframe(), "the I picture of the recovery point is a keyframe");
    }

    @ParameterizedTest(name = "{0}, cut at access unit {1}")
    @MethodSource("cuts")
    @DisplayName("starts at a non-intra picture whose recovery point message reaches the next intra picture")
    void gradualRecovery(Vector vector, int cut) {
        var units = vector.accessUnits();
        var recovery = nextRecovery(units, cut);
        var pocs = vector.decodingPocs();
        var count = pocs[recovery] - pocs[cut];
        for (var threads : new int[]{1, 3}) {
            var options = VERIFY.toBuilder().threads(threads).build();
            var frames = HevcRandomVectors.decode(options, cutAt(units, cut, HevcRandomVectors.recoveryPoint(count,
                    true)));
            assertEquals(vector.frameMd5s().subList(pocs[recovery], vector.frames()), HevcRandomVectors.md5s(frames),
                    "frames from the recovery point, " + threads + " threads");
            assertTrue(frames.getFirst().keyframe(), "the first frame is the I picture of the recovery point");
        }
    }

    static Stream<Arguments> cuts() {
        return recoveryVectors().flatMap(vector -> {
            var units = vector.accessUnits();
            var lastRecovery = IntStream.range(1, units.size()).filter(index -> hasRecoveryPoint(units.get(index)))
                    .max().orElseThrow();
            return IntStream.rangeClosed(1, lastRecovery).mapToObj(cut -> Arguments.of(vector, cut));
        });
    }

    @Test
    @DisplayName("ends the recovery at the next IDR picture, whose sequence is output whole")
    void recoveryEndsAtIdr() {
        var vector = HevcRandomVectors.named("rp-ld");
        var units = vector.accessUnits();
        var cut = 3;
        var recovery = nextRecovery(units, cut);
        var stream = new ArrayList<>(cutAt(units, cut, HevcRandomVectors.recoveryPoint(
                vector.decodingPocs()[recovery] - vector.decodingPocs()[cut], true)));
        stream.addAll(units);
        var frames = HevcRandomVectors.decode(VERIFY, stream);
        var expected = new ArrayList<>(vector.frameMd5s().subList(vector.decodingPocs()[recovery], vector.frames()));
        expected.addAll(vector.frameMd5s());
        assertEquals(expected, HevcRandomVectors.md5s(frames));
    }

    @Test
    @DisplayName("outputs nothing when the recovery point lies past the end of the stream")
    void recoveryPointPastTheEnd() {
        var units = HevcRandomVectors.named("rp-ld").accessUnits();
        // the later I pictures carrying recovery points do not restart a recovery in progress
        // 127 is the largest recovery_poc_cnt a stream with MaxPicOrderCntLsb 256 may send
        var frames = HevcRandomVectors.decode(VERIFY, cutAt(units, 2, HevcRandomVectors.recoveryPoint(127, true)));
        assertEquals(0, frames.size());
    }

    @Nested
    @DisplayName("checks picture hashes after a recovery point by its exact_match_flag")
    class ExactMatch {
        @Test
        @DisplayName("verifies the hashes of the pictures from the recovery point on when an exact match is promised")
        void exact() {
            var exception = assertThrows(MediaException.Malformed.class,
                    () -> HevcRandomVectors.decode(VERIFY, corruptLastHash(true)));
            assertTrue(exception.getMessage().contains("decoded picture hash mismatch"), exception.getMessage());
        }

        @Test
        @DisplayName("skips the hashes of every picture of the sequence when no exact match is promised")
        void approximate() {
            var vector = HevcRandomVectors.named("rp-ld");
            var frames = HevcRandomVectors.decode(VERIFY, corruptLastHash(false));
            var recovery = nextRecovery(vector.accessUnits(), 3);
            assertEquals(vector.frameMd5s().subList(vector.decodingPocs()[recovery], vector.frames()),
                    HevcRandomVectors.md5s(frames));
        }

        @Test
        @DisplayName("verifies the hashes again once an IDR picture ends the recovery")
        void afterIdr() {
            var vector = HevcRandomVectors.named("rp-ld");
            var units = vector.accessUnits();
            var recovery = nextRecovery(units, 3);
            var stream = new ArrayList<>(cutAt(units, 3, HevcRandomVectors.recoveryPoint(
                    vector.decodingPocs()[recovery] - vector.decodingPocs()[3], false)));
            stream.addAll(corruptHash(units, units.size() - 1));
            var exception = assertThrows(MediaException.Malformed.class, () -> HevcRandomVectors.decode(VERIFY,
                    stream));
            assertTrue(exception.getMessage().contains("decoded picture hash mismatch"), exception.getMessage());
        }

        /**
         * Returns rp-ld cut at its fourth access unit with an inserted recovery point reaching the next intra
         * picture, and the decoded picture hash of its last picture corrupted.
         */
        private static List<List<byte[]>> corruptLastHash(boolean exactMatch) {
            var vector = HevcRandomVectors.named("rp-ld");
            var units = corruptHash(vector.accessUnits(), vector.frames() - 1);
            var recovery = nextRecovery(units, 3);
            return cutAt(units, 3, HevcRandomVectors.recoveryPoint(
                    vector.decodingPocs()[recovery] - vector.decodingPocs()[3], exactMatch));
        }

        private static List<List<byte[]>> corruptHash(List<List<byte[]>> units, int index) {
            var result = new ArrayList<>(units);
            var unit = new ArrayList<byte[]>();
            var corrupted = false;
            for (var nal : units.get(index)) {
                var rbsp = HevcRandomVectors.unescape(nal);
                if (HevcTestStreams.type(nal) == 40 && (rbsp[0] & 0xFF) == 132) {
                    // the first byte of the luma hash follows the payload type, size and hash_type bytes
                    rbsp[3] ^= 0x01;
                    unit.add(HevcRandomVectors.nal(40, rbsp));
                    corrupted = true;
                } else {
                    unit.add(nal);
                }
            }
            assertTrue(corrupted, "access unit " + index + " has a decoded picture hash");
            result.set(index, unit);
            return result;
        }
    }

    @Nested
    @DisplayName("ignores recovery point messages it cannot use")
    class Unusable {
        @ParameterizedTest(name = "payload {0}")
        @ValueSource(strings = {"", "01", "00000000", "0000000000000000"})
        @DisplayName("treats a truncated or invalid message as absent and starts at the next recovery point")
        void malformed(String payloadHex) {
            var vector = HevcRandomVectors.named("rp-ld");
            var units = vector.accessUnits();
            var cut = 3;
            var payload = HexFormat.of().parseHex(payloadHex);
            var frames = HevcRandomVectors.decode(VERIFY, cutAt(units, cut, HevcRandomVectors.sei(6, payload)));
            var recovery = nextRecovery(units, cut);
            assertEquals(vector.frameMd5s().subList(vector.decodingPocs()[recovery], vector.frames()),
                    HevcRandomVectors.md5s(frames));
        }

        @ParameterizedTest(name = "recovery_poc_cnt {0}")
        @ValueSource(ints = {128, -129, 1000, -32768})
        @DisplayName("treats a message whose recovery_poc_cnt exceeds half of MaxPicOrderCntLsb as absent")
        void outOfRange(int count) {
            var vector = HevcRandomVectors.named("rp-ld");
            var units = vector.accessUnits();
            var cut = 3;
            var frames = HevcRandomVectors.decode(VERIFY, cutAt(units, cut, HevcRandomVectors.recoveryPoint(count,
                    true)));
            var recovery = nextRecovery(units, cut);
            assertEquals(vector.frameMd5s().subList(vector.decodingPocs()[recovery], vector.frames()),
                    HevcRandomVectors.md5s(frames));
        }

        @Test
        @DisplayName("does not restart decoding at a recovery point once decoding started")
        void midStream() {
            var vector = HevcRandomVectors.named("rp-ra");
            var units = new ArrayList<>(vector.accessUnits());
            var withMessage = new ArrayList<>(units.get(5));
            withMessage.addFirst(HevcRandomVectors.recoveryPoint(30, true));
            units.set(5, withMessage);
            var frames = HevcRandomVectors.decode(VERIFY, units);
            assertEquals(vector.frameMd5s(), HevcRandomVectors.md5s(frames));
            assertFalse(frames.get(vector.decodingPocs()[5]).keyframe());
        }
    }

    /**
     * Returns the access units from {@code cut} on, preceded by the parameter sets of the stream's first access unit
     * and, when not {@code null}, the given SEI NAL unit, which replaces any recovery point message of the access
     * unit at the cut.
     */
    static List<List<byte[]>> cutAt(List<List<byte[]>> units, int cut, byte[] sei) {
        var result = new ArrayList<List<byte[]>>();
        var first = new ArrayList<byte[]>();
        for (var nal : units.getFirst()) {
            var type = HevcTestStreams.type(nal);
            if (type >= 32 && type <= 34) {
                first.add(nal);
            }
        }
        if (sei != null) {
            first.add(sei);
        }
        for (var nal : units.get(cut)) {
            var type = HevcTestStreams.type(nal);
            if (type >= 32 && type <= 34 || sei != null && HevcRandomVectors.isSei(nal, 6)) {
                continue;
            }
            first.add(nal);
        }
        result.add(first);
        result.addAll(units.subList(cut + 1, units.size()));
        return result;
    }

    static boolean hasRecoveryPoint(List<byte[]> unit) {
        return unit.stream().anyMatch(nal -> HevcRandomVectors.isSei(nal, 6));
    }

    static int nextRecovery(List<List<byte[]>> units, int from) {
        return IntStream.range(from, units.size()).filter(index -> hasRecoveryPoint(units.get(index))).findFirst()
                .orElseThrow();
    }
}

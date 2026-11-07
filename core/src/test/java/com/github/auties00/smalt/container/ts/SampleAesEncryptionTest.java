package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the HLS SAMPLE-AES encryption of the transport stream muxer bit for bit: against the units Shaka Packager
 * encrypts from the same clip (tools/testdata/cenc/make-sample-aes-golden.sh), against a decryption written here from
 * the format's definition, and on hand-made units at the corner cases of the pattern.
 */
@DisplayName("SAMPLE-AES encryption")
class SampleAesEncryptionTest {
    private static final byte[] KEY = HexFormat.of().parseHex("8899aabbccddeeff0011223344556677");
    private static final byte[] IV = HexFormat.of().parseHex("f0e1d2c3b4a5968778695a4b3c2d1e0f");
    private static final Path RESOURCES = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "container", "ts", "sample-aes");
    private static final int[] AC3_KBPS = {32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448,
            512, 576, 640};

    private record Unit(String kind, byte[] data) {
    }

    private static byte[] muxEncrypted() {
        return TsMuxerTest.remux(RESOURCES.resolve("sample-aes-input.mp4"), sink -> new TsMuxer(sink,
                TsMuxerOptions.builder().sampleAes(KEY, IV).build()));
    }

    /**
     * Returns the elementary stream of each PID with a stream type of a SAMPLE-AES codec, in PID order.
     */
    private static Map<String, byte[]> streams(byte[] ts) {
        var kinds = Map.of(0x1B, "h264", 0xDB, "h264", 0x0F, "aac", 0xCF, "aac", 0x81, "ac3", 0xC1, "ac3", 0x87,
                "eac3", 0xC2, "eac3");
        var pmt = -1;
        var pids = new java.util.TreeMap<Integer, String>();
        var units = new java.util.TreeMap<Integer, List<ByteArrayOutputStream>>();
        for (var offset = 0; offset + 188 <= ts.length; offset += 188) {
            assertEquals(0x47, ts[offset] & 0xFF, "sync byte at " + offset);
            var pid = ((ts[offset + 1] & 0x1F) << 8) | (ts[offset + 2] & 0xFF);
            var start = (ts[offset + 1] & 0x40) != 0;
            var control = (ts[offset + 3] >> 4) & 3;
            var position = offset + 4;
            if (control == 2 || control == 3) {
                position += 1 + (ts[position] & 0xFF);
            }
            if (control == 2 || position >= offset + 188) {
                continue;
            }
            if (pid == 0 && start) {
                var section = position + 1 + (ts[position] & 0xFF);
                pmt = ((ts[section + 10] & 0x1F) << 8) | (ts[section + 11] & 0xFF);
            } else if (pid == pmt && start) {
                var section = position + 1 + (ts[position] & 0xFF);
                var end = section + 3 + (((ts[section + 1] & 0x0F) << 8) | (ts[section + 2] & 0xFF)) - 4;
                var index = section + 12 + (((ts[section + 10] & 0x0F) << 8) | (ts[section + 11] & 0xFF));
                while (index < end) {
                    var kind = kinds.get(ts[index] & 0xFF);
                    if (kind != null) {
                        pids.put(((ts[index + 1] & 0x1F) << 8) | (ts[index + 2] & 0xFF), kind);
                    }
                    index += 5 + (((ts[index + 3] & 0x0F) << 8) | (ts[index + 4] & 0xFF));
                }
            } else if (pids.containsKey(pid)) {
                var list = units.computeIfAbsent(pid, _ -> new ArrayList<>());
                if (start) {
                    list.add(new ByteArrayOutputStream());
                }
                if (!list.isEmpty()) {
                    list.getLast().write(ts, position, offset + 188 - position);
                }
            }
        }
        var result = new LinkedHashMap<String, byte[]>();
        for (var entry : units.entrySet()) {
            var stream = new ByteArrayOutputStream();
            for (var unit : entry.getValue()) {
                var pes = unit.toByteArray();
                stream.write(pes, 9 + (pes[8] & 0xFF), pes.length - 9 - (pes[8] & 0xFF));
            }
            result.put(pids.get(entry.getKey()), stream.toByteArray());
        }
        return result;
    }

    /**
     * Splits the elementary streams into the units the golden file lists: H.264 slices as carried, ADTS frames
     * without their header, AC-3 and E-AC-3 syncframes.
     */
    private static List<Unit> units(byte[] ts) {
        var result = new ArrayList<Unit>();
        for (var entry : streams(ts).entrySet()) {
            var kind = entry.getKey();
            var stream = entry.getValue();
            if (kind.equals("h264")) {
                var starts = new ArrayList<Integer>();
                for (var index = 0; index + 3 < stream.length; index++) {
                    if (stream[index] == 0 && stream[index + 1] == 0 && stream[index + 2] == 1) {
                        starts.add(index + 3);
                        index += 2;
                    }
                }
                for (var number = 0; number < starts.size(); number++) {
                    int start = starts.get(number);
                    var end = number + 1 < starts.size() ? starts.get(number + 1) - 3 : stream.length;
                    while (end > start && stream[end - 1] == 0) {
                        end--;
                    }
                    var type = stream[start] & 0x1F;
                    if (type == 1 || type == 5) {
                        result.add(new Unit(kind, Arrays.copyOfRange(stream, start, end)));
                    }
                }
                continue;
            }
            for (var position = 0; position < stream.length; ) {
                int length;
                var from = position;
                if (kind.equals("aac")) {
                    from += (stream[position + 1] & 1) != 0 ? 7 : 9;
                    length = ((stream[position + 3] & 3) << 11) | ((stream[position + 4] & 0xFF) << 3)
                            | ((stream[position + 5] & 0xFF) >>> 5);
                } else {
                    assertEquals(0x0B77, ((stream[position] & 0xFF) << 8) | (stream[position + 1] & 0xFF), kind);
                    length = kind.equals("ac3") ? 4 * AC3_KBPS[(stream[position + 4] & 0x3F) >> 1]
                            : 2 * ((((stream[position + 2] & 7) << 8) | (stream[position + 3] & 0xFF)) + 1);
                }
                result.add(new Unit(kind, Arrays.copyOfRange(stream, from, position + length)));
                position += length;
            }
        }
        return result;
    }

    private static List<String> listing(List<Unit> units) throws GeneralSecurityException {
        var lines = new ArrayList<String>();
        var numbers = new LinkedHashMap<String, Integer>();
        for (var unit : units) {
            var number = numbers.merge(unit.kind(), 1, Integer::sum) - 1;
            lines.add(unit.kind() + " " + number + " " + unit.data().length + " "
                    + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(unit.data())));
        }
        return lines;
    }

    private static byte[] unescape(byte[] nal) {
        var out = new ByteArrayOutputStream();
        var zeros = 0;
        for (var value : nal) {
            if (zeros >= 2 && value == 3) {
                zeros = 0;
                continue;
            }
            out.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    /**
     * Decrypts, in place, the whole blocks at the given offsets of {@code data} as one AES-CBC chain started from
     * the initialization vector.
     */
    private static void decryptBlocks(byte[] data, List<Integer> blocks) throws GeneralSecurityException {
        if (blocks.isEmpty()) {
            return;
        }
        var joined = new byte[16 * blocks.size()];
        for (var index = 0; index < blocks.size(); index++) {
            System.arraycopy(data, blocks.get(index), joined, 16 * index, 16);
        }
        var cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(IV));
        var plain = cipher.doFinal(joined);
        for (var index = 0; index < blocks.size(); index++) {
            System.arraycopy(plain, 16 * index, data, blocks.get(index), 16);
        }
    }

    /**
     * Decrypts a carried slice: one level of emulation prevention removed, then the first of every ten blocks after
     * the 32-byte leader while more than 16 bytes are left.
     */
    private static byte[] decryptSlice(byte[] carried) throws GeneralSecurityException {
        var nal = unescape(carried);
        if (nal.length <= 48) {
            return nal;
        }
        var blocks = new ArrayList<Integer>();
        for (var position = 32; nal.length - position > 16; position += 160) {
            blocks.add(position);
        }
        decryptBlocks(nal, blocks);
        return nal;
    }

    /**
     * Decrypts an audio frame: in each syncframe, the whole blocks after the 16-byte leader, one chain over the
     * frame.
     */
    private static byte[] decryptAudio(byte[] frame, List<int[]> syncframes) throws GeneralSecurityException {
        var copy = frame.clone();
        var blocks = new ArrayList<Integer>();
        for (var syncframe : syncframes) {
            for (var position = syncframe[0] + 16; position + 16 <= syncframe[0] + syncframe[1]; position += 16) {
                blocks.add(position);
            }
        }
        decryptBlocks(copy, blocks);
        return copy;
    }

    private static List<int[]> whole(byte[] frame) {
        return List.of(new int[]{0, frame.length});
    }

    private static List<byte[]> clearSlices(byte[] sample) {
        var result = new ArrayList<byte[]>();
        for (var position = 0; position + 4 <= sample.length; ) {
            var length = ((sample[position] & 0xFF) << 24) | ((sample[position + 1] & 0xFF) << 16)
                    | ((sample[position + 2] & 0xFF) << 8) | (sample[position + 3] & 0xFF);
            var type = sample[position + 4] & 0x1F;
            if (type == 1 || type == 5) {
                result.add(Arrays.copyOfRange(sample, position + 4, position + 4 + length));
            }
            position += 4 + length;
        }
        return result;
    }

    @Test
    @DisplayName("encrypts every slice and audio frame as Shaka Packager does")
    void matchesShakaPackager() throws Exception {
        var expected = Files.readAllLines(RESOURCES.resolve("sample-aes-shaka.txt")).stream()
                .filter(line -> !line.isBlank()).toList();
        var actual = listing(units(muxEncrypted()));
        assertEquals(String.join("\n", expected), String.join("\n", actual));
        // the clip reaches the corner cases: emulation prevention, short slices, a last block of exactly 16 bytes
        var lengths = expected.stream().filter(line -> line.startsWith("h264")).mapToInt(line -> Integer.parseInt(
                line.split(" ")[2])).toArray();
        assertTrue(Arrays.stream(lengths).anyMatch(length -> length <= 48));
        assertEquals(206, lengths.length);
    }

    @Test
    @DisplayName("decrypts by the format's definition to the samples of the input")
    void decryptsToTheInput() throws Exception {
        var units = units(muxEncrypted());
        var clear = new LinkedHashMap<String, List<byte[]>>();
        try (var source = MediaSource.of(RESOURCES.resolve("sample-aes-input.mp4"));
             var demuxer = new Mp4Demuxer(source)) {
            var kinds = new java.util.HashMap<Integer, String>();
            for (var track : demuxer.tracks()) {
                kinds.put(track.id(), switch (track.codec()) {
                    case H264 -> "h264";
                    case AAC -> "aac";
                    case AC3 -> "ac3";
                    default -> "eac3";
                });
            }
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                var kind = kinds.get(packet.trackId());
                var list = clear.computeIfAbsent(kind, _ -> new ArrayList<>());
                if (kind.equals("h264")) {
                    list.addAll(clearSlices(packet.data()));
                } else {
                    list.add(packet.data());
                }
            }
        }
        var counters = new LinkedHashMap<String, Integer>();
        var protectedUnits = 0;
        var escaped = 0;
        for (var unit : units) {
            var number = counters.merge(unit.kind(), 1, Integer::sum) - 1;
            var expected = clear.get(unit.kind()).get(number);
            var decrypted = unit.kind().equals("h264") ? decryptSlice(unit.data())
                    : decryptAudio(unit.data(), whole(unit.data()));
            assertArrayEquals(expected, decrypted, unit.kind() + " " + number);
            if (!Arrays.equals(expected, unit.data())) {
                protectedUnits++;
            }
            if (unit.kind().equals("h264") && unescape(expected).length != expected.length) {
                escaped++;
            }
        }
        assertEquals(clear.values().stream().mapToInt(List::size).sum(), units.size());
        assertTrue(protectedUnits > units.size() / 2, protectedUnits + " of " + units.size() + " encrypted");
        assertTrue(escaped > 0, "the clip has slices with emulation prevention bytes");
    }

    @Nested
    @DisplayName("slices")
    class Slices {
        private static byte[] accessUnit(int lengthSize, byte[]... nals) {
            var out = new ByteArrayOutputStream();
            for (var nal : nals) {
                for (var index = lengthSize - 1; index >= 0; index--) {
                    out.write(nal.length >>> (8 * index));
                }
                out.writeBytes(nal);
            }
            return out.toByteArray();
        }

        private static List<byte[]> split(byte[] unit) {
            var result = new ArrayList<byte[]>();
            for (var position = 0; position < unit.length; ) {
                var length = ((unit[position] & 0xFF) << 24) | ((unit[position + 1] & 0xFF) << 16)
                        | ((unit[position + 2] & 0xFF) << 8) | (unit[position + 3] & 0xFF);
                result.add(Arrays.copyOfRange(unit, position + 4, position + 4 + length));
                position += 4 + length;
            }
            return result;
        }

        private static byte[] slice(int type, int length, long seed) {
            var nal = new byte[length];
            new Random(seed).nextBytes(nal);
            nal[0] = (byte) (0x60 | type);
            // a stored slice carries no start code prefix and ends with its stop bit
            for (var index = 2; index < length; index++) {
                if (nal[index - 2] == 0 && nal[index - 1] == 0 && (nal[index] & 0xFF) <= 3) {
                    nal[index] = 4;
                }
            }
            nal[length - 1] = (byte) 0x80;
            return nal;
        }

        private static byte[] encrypt(int lengthSize, byte[]... nals) {
            return new SampleAesEncryptor(KEY, IV).video(accessUnit(lengthSize, nals), lengthSize);
        }

        @ParameterizedTest(name = "{0} bytes")
        @ValueSource(ints = {49, 63, 64, 65, 192, 207, 208, 209, 223, 224, 368, 369, 1000, 4096})
        @DisplayName("keep the 32-byte leader, a last block with nothing after it and a partial block clear")
        void pattern(int length) throws Exception {
            var nal = slice(5, length, length);
            var carried = split(encrypt(4, nal)).getFirst();
            assertArrayEquals(nal, decryptSlice(carried));
            var raw = unescape(carried);
            assertArrayEquals(Arrays.copyOf(nal, 32), Arrays.copyOf(raw, 32));
            // the clear bytes are the nine skipped blocks of each pattern, a block of exactly 16 bytes left at the
            // end and the partial block
            for (var position = 32; position < length; position += 160) {
                var encrypted = length - position > 16;
                var block = Arrays.copyOfRange(raw, position, Math.min(position + 16, length));
                assertEquals(encrypted, !Arrays.equals(Arrays.copyOfRange(nal, position, Math.min(position + 16,
                        length)), block), "block at " + position);
                assertArrayEquals(Arrays.copyOfRange(nal, Math.min(position + 16, length),
                        Math.min(position + 160, length)), Arrays.copyOfRange(raw, Math.min(position + 16, length),
                        Math.min(position + 160, length)), "skipped blocks after " + position);
            }
        }

        @Test
        @DisplayName("leave slices of at most 48 bytes and other NAL units clear")
        void clearUnits() {
            var sps = slice(7, 300, 1);
            var shortSlice = slice(1, 48, 2);
            var sei = slice(6, 100, 3);
            var units = split(encrypt(4, sps, shortSlice, sei));
            assertArrayEquals(sps, units.get(0));
            assertArrayEquals(shortSlice, units.get(1));
            assertArrayEquals(sei, units.get(2));
        }

        @Test
        @DisplayName("encrypt the stored unit, emulation prevention bytes included, and escape the result again")
        void escaping() throws Exception {
            var nal = slice(1, 2000, 7);
            // an emulation prevention byte in the leader and one in a skipped block stay; one in an encrypted
            // block is encrypted with its unit
            for (var position : new int[]{10, 60, 192 + 3}) {
                nal[position] = 0;
                nal[position + 1] = 0;
                nal[position + 2] = 3;
                nal[position + 3] = 1;
            }
            var carried = split(encrypt(4, nal)).getFirst();
            assertArrayEquals(nal, decryptSlice(carried));
            for (var index = 2; index < carried.length; index++) {
                assertFalse(carried[index - 2] == 0 && carried[index - 1] == 0 && (carried[index] & 0xFF) < 3,
                        "a start code or reserved sequence at " + index);
            }
            assertTrue(carried[carried.length - 1] != 0);
        }

        @Test
        @DisplayName("escape a ciphertext block of zeros")
        void zeroRuns() throws Exception {
            // the plaintext block is chosen so that its ciphertext is all zeros: E(p ^ iv) = 0 for p = D(0) ^ iv
            var cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"));
            var zeroInput = cipher.doFinal(new byte[16]);
            var nal = slice(1, 49, 11);
            nal[31] = 0x55;
            for (var index = 0; index < 16; index++) {
                nal[32 + index] = (byte) (zeroInput[index] ^ IV[index]);
            }
            var carried = split(encrypt(4, nal)).getFirst();
            var expected = new ByteArrayOutputStream();
            expected.write(nal, 0, 32);
            expected.writeBytes(new byte[]{0, 0});
            for (var run = 0; run < 7; run++) {
                expected.writeBytes(new byte[]{3, 0, 0});
            }
            expected.write(nal[48]);
            assertEquals(HexFormat.of().formatHex(expected.toByteArray()), HexFormat.of().formatHex(carried));
            assertArrayEquals(nal, decryptSlice(carried));
        }

        @Test
        @DisplayName("write 4-byte lengths whatever the input's length size, and reject a length that overruns")
        void lengths() {
            var nal = slice(5, 500, 5);
            var two = encrypt(2, nal);
            assertEquals(4 + split(two).getFirst().length, two.length);
            var encryptor = new SampleAesEncryptor(KEY, IV);
            var unit = accessUnit(4, nal);
            assertThrows(IllegalArgumentException.class, () -> encryptor.video(Arrays.copyOf(unit, 300), 4));
            assertThrows(IllegalArgumentException.class, () -> encryptor.video(new byte[]{0, 0}, 4));
        }
    }

    @Nested
    @DisplayName("audio frames")
    class Audio {
        private static byte[] random(int length, long seed) {
            var data = new byte[length];
            new Random(seed).nextBytes(data);
            return data;
        }

        @ParameterizedTest(name = "{0} bytes")
        @ValueSource(ints = {7, 16, 23, 24, 38, 39, 40, 55, 300})
        @DisplayName("an ADTS frame keeps its header and 16 bytes clear and encrypts the whole blocks after them")
        void adts(int length) throws Exception {
            var frame = random(length, length);
            var encrypted = frame.clone();
            new SampleAesEncryptor(KEY, IV).audio(encrypted, Codec.AAC, 7);
            var payload = Arrays.copyOfRange(encrypted, 7, length);
            assertArrayEquals(Arrays.copyOfRange(frame, 7, length), decryptAudio(payload, whole(payload)));
            var protectedBlocks = Math.max(0, (length - 7 - 16) / 16);
            var lead = Math.min(length, 7 + 16);
            assertArrayEquals(Arrays.copyOf(frame, lead), Arrays.copyOf(encrypted, lead));
            var tail = 7 + 16 + 16 * protectedBlocks;
            if (tail < length) {
                assertArrayEquals(Arrays.copyOfRange(frame, tail, length), Arrays.copyOfRange(encrypted, tail, length));
            }
            assertEquals(protectedBlocks > 0, !Arrays.equals(frame, encrypted));
        }

        @Test
        @DisplayName("an AC-3 syncframe keeps its first 16 bytes clear")
        void ac3() throws Exception {
            var frame = random(384, 3);
            var encrypted = frame.clone();
            new SampleAesEncryptor(KEY, IV).audio(encrypted, Codec.AC3, 0);
            assertArrayEquals(Arrays.copyOf(frame, 16), Arrays.copyOf(encrypted, 16));
            assertArrayEquals(frame, decryptAudio(encrypted, whole(encrypted)));
        }

        @Test
        @DisplayName("the syncframes of an E-AC-3 frame share one chain started from the vector")
        void eac3() throws Exception {
            // two syncframes of 100 and 58 bytes: frmsiz is the size in 16-bit words minus one
            var frame = random(158, 9);
            for (var syncframe : new int[][]{{0, 100}, {100, 58}}) {
                var frmsiz = syncframe[1] / 2 - 1;
                frame[syncframe[0]] = 0x0B;
                frame[syncframe[0] + 1] = 0x77;
                frame[syncframe[0] + 2] = (byte) (frmsiz >>> 8);
                frame[syncframe[0] + 3] = (byte) frmsiz;
            }
            var encrypted = frame.clone();
            new SampleAesEncryptor(KEY, IV).audio(encrypted, Codec.EAC3, 0);
            var syncframes = List.of(new int[]{0, 100}, new int[]{100, 58});
            assertArrayEquals(frame, decryptAudio(encrypted, syncframes));
            assertArrayEquals(Arrays.copyOfRange(frame, 100, 116), Arrays.copyOfRange(encrypted, 100, 116));
            // restarting the chain at the second syncframe decrypts something else
            var second = Arrays.copyOfRange(encrypted, 100, 158);
            assertFalse(Arrays.equals(Arrays.copyOfRange(frame, 100, 158), decryptAudio(second, whole(second))));
        }

        @Test
        @DisplayName("each frame restarts the chain from the vector")
        void restartsPerFrame() throws Exception {
            var encryptor = new SampleAesEncryptor(KEY, IV);
            var first = random(200, 1);
            var second = first.clone();
            encryptor.audio(first, Codec.AC3, 0);
            encryptor.audio(second, Codec.AC3, 0);
            assertArrayEquals(first, second);
        }
    }

    @Test
    @DisplayName("the golden clip's input is the committed file")
    void pinnedInput() throws IOException, GeneralSecurityException {
        assertEquals("41d67aaa97f19f44a6f474e6c716c0a9bc5e18ff203f051c3556cec40a5f7ef7", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(RESOURCES.resolve(
                        "sample-aes-input.mp4")))));
    }
}

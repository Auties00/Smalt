package com.github.auties00.smalt.container;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Packet;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The keys of Chromium's encrypted test files and the comparisons the encryption tests share: packets read from a
 * protected file and decrypted must equal those of the clear file it was made from, byte for byte.
 */
public final class EncryptedFixtures {
    /**
     * The key identifier of Chromium's test files, "0123456789012345" in ASCII.
     */
    public static final byte[] KEY_ID = HexFormat.of().parseHex("30313233343536373839303132333435");

    /**
     * The key of Chromium's test files.
     */
    public static final byte[] KEY = HexFormat.of().parseHex("ebdd62f16814d27b68ef122afce4ae3c");

    /**
     * Packet data and timestamps compare exactly.
     */
    public static final Tolerance EXACT = Tolerance.exact();

    private EncryptedFixtures() {
        throw new AssertionError();
    }

    /**
     * Returns the keys of Chromium's key rotation files, whose key identifiers and keys are those of
     * {@link #KEY_ID} and {@link #KEY} rotated left by one byte per crypto period.
     *
     * @return the keys of every rotation
     */
    public static DecryptionKeys rotatedKeys() {
        var keys = new LinkedHashMap<String, String>();
        for (var rotation = 0; rotation < 16; rotation++) {
            keys.put(HexFormat.of().formatHex(rotate(KEY_ID, rotation)), HexFormat.of().formatHex(rotate(KEY, rotation)));
        }
        return DecryptionKeys.ofHex(keys);
    }

    private static byte[] rotate(byte[] value, int count) {
        var result = new byte[value.length];
        for (var index = 0; index < value.length; index++) {
            result[index] = value[(index + count) % value.length];
        }
        return result;
    }

    /**
     * Returns every packet of a demuxer, grouped by track identifier.
     *
     * @param demuxer the demuxer, read to its end
     * @return the packets of each track, in the order the demuxer returned them
     */
    public static Map<Integer, List<Packet>> packets(Demuxer demuxer) {
        var result = new LinkedHashMap<Integer, List<Packet>>();
        Packet packet;
        while ((packet = demuxer.read()) != null) {
            result.computeIfAbsent(packet.trackId(), ignored -> new ArrayList<>()).add(packet);
        }
        return result;
    }

    /**
     * Asserts that the packets of a track equal those of a reference track: same count, timestamps, key frame flags
     * and bytes.
     *
     * @param label the name of the comparison
     * @param expected the reference packets
     * @param actual the packets compared
     */
    public static void assertSamePackets(String label, List<Packet> expected, List<Packet> actual) {
        assertSameData(label, expected, actual);
        for (var index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).pts(), actual.get(index).pts(), label + ": pts of packet " + index);
            assertEquals(expected.get(index).dts(), actual.get(index).dts(), label + ": dts of packet " + index);
        }
    }

    /**
     * Asserts that the packets of a track hold the bytes of those of a reference track, in the same order and with
     * the same key frame flags, whatever their timestamps (packaging tools that encrypt a file may rewrite its edit
     * list).
     *
     * @param label the name of the comparison
     * @param expected the reference packets
     * @param actual the packets compared
     */
    public static void assertSameData(String label, List<Packet> expected, List<Packet> actual) {
        assertEquals(expected.size(), actual.size(), label + ": packet count");
        for (var index = 0; index < expected.size(); index++) {
            var want = expected.get(index);
            var got = actual.get(index);
            assertEquals(want.keyframe(), got.keyframe(), label + ": key frame flag of packet " + index);
            EXACT.assertWithin(label + ": packet " + index, want.data(), got.data());
        }
    }

    /**
     * Returns the MD5 of every packet ffmpeg reads from a file, decrypting it with a key, per stream.
     *
     * @param file the file
     * @param key the key, or {@code null} for a clear file
     * @param extra input options placed before the input, for example the demuxer options of a playlist
     * @return the hexadecimal MD5 of each packet, by stream index
     */
    public static Map<Integer, List<String>> ffmpegPacketHashes(Path file, byte[] key, List<String> extra) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var arguments = new ArrayList<>(List.of("-v", "error"));
        if (key != null) {
            arguments.addAll(List.of("-decryption_key", HexFormat.of().formatHex(key)));
        }
        arguments.addAll(extra);
        arguments.addAll(List.of("-i", file.toString(), "-map", "0", "-c", "copy", "-f", "framemd5", "-"));
        var output = new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments), StandardCharsets.UTF_8);
        var result = new LinkedHashMap<Integer, List<String>>();
        for (var line : output.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var fields = line.split(",");
            result.computeIfAbsent(Integer.parseInt(fields[0].trim()), ignored -> new ArrayList<>())
                    .add(fields[5].trim());
        }
        return result;
    }

    /**
     * Returns the MD5 of every packet, in order.
     *
     * @param packets the packets
     * @return their hexadecimal MD5 hashes
     */
    public static List<String> md5s(List<Packet> packets) {
        var result = new ArrayList<String>();
        for (var packet : packets) {
            result.add(md5(packet.data()));
        }
        return result;
    }

    /**
     * Returns the MD5 of bytes.
     *
     * @param data the bytes
     * @return the hexadecimal MD5
     */
    public static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

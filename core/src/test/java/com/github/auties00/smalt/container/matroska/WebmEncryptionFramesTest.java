package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.DecryptionKeys;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static com.github.auties00.smalt.container.matroska.EbmlWriter.binary;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.block;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.file;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.master;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.uint;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Builds WebM files whose frames take every layout of the WebM encryption format (clear, whole, partitioned with
 * zero to three partitions, empty partitions) from frames encrypted with the JDK's AES-CTR, and damaged or
 * unsupported variants, and checks what the Matroska demuxer makes of them.
 */
@DisplayName("WebM encrypted frames")
class WebmEncryptionFramesTest {
    private static final byte[] KEY_ID = HexFormat.of().parseHex("5d4c3b2a19087f6e5d4c3b2a19087f6e");
    private static final byte[] KEY = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
    private static final DecryptionKeys KEYS = DecryptionKeys.of(KEY_ID, KEY);

    /**
     * Marks the whole-frame layout of {@link #encrypted}, which writes a partitioned frame for any other offsets.
     */
    private static final int[] WHOLE = new int[0];

    /**
     * Returns the stored form of a frame: the bytes of its odd partitions (of the whole frame for {@link #WHOLE})
     * encrypted as one AES-CTR stream from the 8-byte initialization vector followed by a zero block counter, behind
     * the signal byte, the vector and the partition offsets.
     */
    private static byte[] encrypted(byte[] plain, byte[] iv, int... offsets) throws Exception {
        var whole = offsets == WHOLE;
        var bounds = new int[offsets.length + 2];
        System.arraycopy(offsets, 0, bounds, 1, offsets.length);
        bounds[bounds.length - 1] = plain.length;
        var protectedBytes = new ByteArrayOutputStream();
        for (var range = 0; range + 1 < bounds.length; range++) {
            if (whole || range % 2 == 1) {
                protectedBytes.write(plain, bounds[range], bounds[range + 1] - bounds[range]);
            }
        }
        var counter = new byte[16];
        System.arraycopy(iv, 0, counter, 0, 8);
        var cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(counter));
        var stream = cipher.doFinal(protectedBytes.toByteArray());
        var stored = plain.clone();
        var used = 0;
        for (var range = 0; range + 1 < bounds.length; range++) {
            if (whole || range % 2 == 1) {
                for (var index = bounds[range]; index < bounds[range + 1]; index++) {
                    stored[index] = stream[used++];
                }
            }
        }
        var out = new ByteArrayOutputStream();
        out.write(whole ? 1 : 3);
        out.writeBytes(iv);
        if (!whole) {
            out.write(offsets.length);
            for (var offset : offsets) {
                out.writeBytes(new byte[]{(byte) (offset >>> 24), (byte) (offset >>> 16), (byte) (offset >>> 8),
                        (byte) offset});
            }
        }
        out.writeBytes(stored);
        return out.toByteArray();
    }

    private static EbmlWriter.Node encryption(byte[] keyId, long algorithm, long cipherMode, long scope) {
        var settings = new ArrayList<EbmlWriter.Node>(List.of(uint(EbmlIds.CONTENT_ENC_ALGO, algorithm)));
        if (keyId != null) {
            settings.add(binary(EbmlIds.CONTENT_ENC_KEY_ID, keyId));
        }
        settings.add(master(EbmlIds.CONTENT_ENC_AES_SETTINGS, uint(EbmlIds.AES_SETTINGS_CIPHER_MODE, cipherMode)));
        return master(EbmlIds.CONTENT_ENCODINGS, master(EbmlIds.CONTENT_ENCODING,
                uint(EbmlIds.CONTENT_ENCODING_SCOPE, scope), uint(EbmlIds.CONTENT_ENCODING_TYPE, 1),
                master(EbmlIds.CONTENT_ENCRYPTION, settings)));
    }

    private static byte[] webm(EbmlWriter.Node encryption, List<byte[]> frames) {
        var blocks = new ArrayList<EbmlWriter.Node>();
        blocks.add(uint(EbmlIds.TIMESTAMP, 0));
        for (var index = 0; index < frames.size(); index++) {
            blocks.add(binary(EbmlIds.SIMPLE_BLOCK, block(1, index * 20, 0x80, frames.get(index))));
        }
        return file("webm", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                MatroskaDemuxerTest.pcmTrack(1, 0, encryption), master(EbmlIds.CLUSTER, blocks));
    }

    private static List<Packet> demux(byte[] data, DecryptionKeys keys) {
        try (var source = MediaSource.of(data);
             var demuxer = new MatroskaDemuxer(source, DemuxerOptions.builder().decryptionKeys(keys).build())) {
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return packets;
        }
    }

    private static byte[] plain(Random random, int size) {
        var bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    @Test
    @DisplayName("decrypts clear, whole and partitioned frames to the JDK's AES-CTR plaintext")
    void layouts() throws Exception {
        var random = new Random(41);
        var plains = new ArrayList<byte[]>();
        var frames = new ArrayList<byte[]>();
        var iv = new byte[8];
        int[][] layouts = {null, WHOLE, {10}, {6, 120}, {16, 16, 70}, {0, 40, 40, 200}, {}};
        for (var layout : layouts) {
            var plain = plain(random, 256 + 2 * random.nextInt(64));
            random.nextBytes(iv);
            plains.add(plain);
            if (layout == null) {
                var clear = new byte[plain.length + 1];
                System.arraycopy(plain, 0, clear, 1, plain.length);
                frames.add(clear);
            } else {
                frames.add(encrypted(plain, iv, layout));
            }
        }
        var packets = demux(webm(encryption(KEY_ID, 5, 1, 1), frames), KEYS);
        assertEquals(plains.size(), packets.size());
        for (var index = 0; index < plains.size(); index++) {
            assertArrayEquals(plains.get(index), packets.get(index).data(), "frame " + index);
        }
    }

    @Test
    @DisplayName("finds the key of a key identifier that is not 16 bytes long through a key for every identifier")
    void shortKeyId() throws Exception {
        var plain = plain(new Random(3), 300);
        var file = webm(encryption(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, 5, 1, 1),
                List.of(encrypted(plain, new byte[8], WHOLE)));
        assertArrayEquals(plain, demux(file, DecryptionKeys.single(KEY)).getFirst().data());
        assertEquals("matroska encryption", assertThrows(MediaException.Unsupported.class,
                () -> demux(file, KEYS)).format());
    }

    @Test
    @DisplayName("rejects damaged encrypted frames as malformed")
    void damaged() throws Exception {
        var plain = plain(new Random(9), 200);
        var good = encrypted(plain, new byte[8], 50, 100);
        var badSignal = good.clone();
        badSignal[0] = 0x07;
        var truncatedIv = new byte[]{1, 1, 2, 3, 4, 5};
        var truncatedOffsets = java.util.Arrays.copyOf(good, 1 + 8 + 1 + 6);
        var decreasing = good.clone();
        // the first offset, 50, becomes 120, past the second
        decreasing[1 + 8 + 1 + 3] = 120;
        var pastEnd = encrypted(plain, new byte[8], 50, 100);
        // the second offset, 100, becomes 0x0100_0064, past the frame
        pastEnd[1 + 8 + 1 + 4] = 1;
        var partitionCountOnly = new byte[]{3, 0, 0, 0, 0, 0, 0, 0, 0};
        for (var frame : List.of(badSignal, truncatedIv, truncatedOffsets, decreasing, pastEnd, partitionCountOnly,
                new byte[0])) {
            assertThrows(MediaException.Malformed.class, () -> demux(webm(encryption(KEY_ID, 5, 1, 1),
                    List.of(good, frame)), KEYS), HexFormat.of().formatHex(frame, 0, Math.min(frame.length, 16)));
        }
        assertThrows(MediaException.Malformed.class, () -> demux(webm(encryption(null, 5, 1, 1), List.of(good)), KEYS));
    }

    @Test
    @DisplayName("rejects encryption other than WebM's AES-CTR of frames as unsupported")
    void unsupported() throws Exception {
        var frames = List.of(encrypted(plain(new Random(1), 64), new byte[8], WHOLE));
        for (var encryption : List.of(encryption(KEY_ID, 1, 1, 1), encryption(KEY_ID, 5, 2, 1),
                encryption(KEY_ID, 5, 1, 3))) {
            assertEquals("matroska encryption", assertThrows(MediaException.Unsupported.class,
                    () -> demux(webm(encryption, frames), KEYS)).format());
        }
        assertEquals("matroska encryption", assertThrows(MediaException.Unsupported.class,
                () -> demux(webm(encryption(KEY_ID, 5, 1, 1), frames), DecryptionKeys.none())).format());
    }
}

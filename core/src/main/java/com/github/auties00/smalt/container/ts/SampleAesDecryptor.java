package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.cenc.CencCipher;
import com.github.auties00.smalt.internal.cenc.CencScheme;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.header.Crc16;

/**
 * Decrypts the units of one elementary stream encrypted in the HLS {@code SAMPLE-AES} format, as
 * {@link TsDemuxerOptions#sampleAesKeys()} describes, with the key of the PES packet each unit starts in. Instances
 * are not thread-safe.
 */
final class SampleAesDecryptor {
    /**
     * The clear bytes at the start of an encrypted H.264 slice.
     */
    private static final int VIDEO_LEADER = 32;

    /**
     * The clear bytes at the start of an audio frame, after its ADTS header.
     */
    private static final int AUDIO_LEADER = 16;

    /**
     * The bytes of one AES block.
     */
    private static final int BLOCK = 16;

    /**
     * The clear bytes after each encrypted block of a slice: nine blocks.
     */
    private static final int VIDEO_SKIP = 144;

    private final SampleAesKeys keys;

    /**
     * The name of the stream, for messages.
     */
    private final String name;

    /**
     * The key of {@link #cipher}, or {@code null}.
     */
    private SampleAesKey current;

    private CencCipher cipher;

    /**
     * The PES packet offset whose key {@link #current} is, so that the keys are asked once per PES packet, or
     * {@code -1}.
     */
    private long lastOffset = -1;

    /**
     * Whether a syncframe decrypted with {@link #current} passed its CRC check.
     */
    private boolean verified;

    private final int[] subsamples = new int[4];

    /**
     * The decrypted slice, reused across slices.
     */
    private byte[] slice = new byte[4096];

    /**
     * Constructs a decryptor.
     *
     * @param keys the keys of the stream, by PES packet offset
     * @param name the name of the stream, for messages
     */
    SampleAesDecryptor(SampleAesKeys keys, String name) {
        this.keys = keys;
        this.name = name;
    }

    /**
     * Returns whether a NAL unit is an encrypted H.264 slice: of type 1 or 5 and carried in more than 48 bytes.
     *
     * @param data the bytes
     * @param offset the index of the NAL unit header
     * @param length the carried length of the NAL unit
     * @return {@code true} when {@link #slice} must decrypt it
     */
    static boolean isEncryptedSlice(byte[] data, int offset, int length) {
        var type = data[offset] & 0x1F;
        return (type == 1 || type == 5) && length > VIDEO_LEADER + BLOCK;
    }

    /**
     * Decrypts an encrypted slice into {@link #slice()}: one level of emulation prevention removed, then the blocks
     * of the 1:9 pattern after the 32-byte leader while more than 16 bytes are left.
     *
     * @param data the bytes, not modified
     * @param offset the index of the NAL unit header
     * @param length the carried length of the NAL unit
     * @param fileOffset the offset of the transport packet that started the PES packet holding the slice
     * @return the length of the decrypted slice in {@link #slice()}
     * @throws MediaException.Unsupported if no key is known for the PES packet
     */
    int slice(byte[] data, int offset, int length, long fileOffset) {
        var cipher = cipher(fileOffset);
        if (slice.length < length) {
            slice = new byte[Math.max(length, 2 * slice.length)];
        }
        var out = slice;
        var size = 0;
        var zeros = 0;
        for (var index = offset; index < offset + length; index++) {
            var value = data[index];
            if (zeros >= 2 && value == 3) {
                zeros = 0;
                continue;
            }
            out[size++] = value;
            zeros = value == 0 ? zeros + 1 : 0;
        }
        if (size > VIDEO_LEADER + BLOCK) {
            var protectedBytes = size - VIDEO_LEADER;
            // a block with nothing after it stays clear: the pattern encrypts a block only while more than 16
            // bytes are left
            if (protectedBytes % (BLOCK + VIDEO_SKIP) == BLOCK) {
                protectedBytes--;
            }
            subsamples[0] = VIDEO_LEADER;
            subsamples[1] = protectedBytes;
            subsamples[2] = size - VIDEO_LEADER - protectedBytes;
            subsamples[3] = 0;
            cipher.decrypt(CencScheme.CBCS, out, 0, size, current.ivBytes(), 0, BLOCK, subsamples, 0, 2, 1,
                    VIDEO_SKIP / BLOCK);
        }
        return size;
    }

    /**
     * {@return the array holding the slice {@link #slice(byte[], int, int, long)} decrypted last}
     */
    byte[] slice() {
        return slice;
    }

    /**
     * Decrypts an ADTS frame in place: the whole blocks after its header and the 16-byte leader.
     *
     * @param data the bytes
     * @param offset the index of the frame
     * @param length the frame length, header included
     * @param header the header length, 7 or 9 bytes
     * @param fileOffset the offset of the transport packet that started the PES packet holding the frame
     * @throws MediaException.Unsupported if no key is known for the PES packet
     */
    void adts(byte[] data, int offset, int length, int header, long fileOffset) {
        var cipher = cipher(fileOffset);
        cipher.restartChain(current.ivBytes());
        if (length - header > AUDIO_LEADER) {
            cipher.cbcChained(false, data, offset + header + AUDIO_LEADER, length - header - AUDIO_LEADER);
        }
    }

    /**
     * Decrypts an AC-3 syncframe, or an E-AC-3 frame of one or more syncframes, in place: the whole blocks after
     * the 16-byte leader of each syncframe, one chain over the frame.
     *
     * @param data the bytes
     * @param offset the index of the frame
     * @param length the frame length
     * @param fileOffset the offset of the transport packet that started the PES packet holding the frame
     * @param corrupt whether a damaged PES packet contributed to the frame, which then does not check the key
     * @throws MediaException.Unsupported if no key is known for the PES packet
     * @throws MediaException.Malformed if the first syncframe the key decrypts fails its CRC
     */
    void ac3(byte[] data, int offset, int length, long fileOffset, boolean corrupt) {
        var cipher = cipher(fileOffset);
        cipher.restartChain(current.ivBytes());
        var position = offset;
        var end = offset + length;
        while (end - position >= 6) {
            var size = Ac3Configs.frameLength(data, position, end - position);
            if (size <= 0 || size > end - position) {
                size = end - position;
            }
            if (size > AUDIO_LEADER) {
                cipher.cbcChained(false, data, position + AUDIO_LEADER, size - AUDIO_LEADER);
                if (!verified && !corrupt && size >= AUDIO_LEADER + BLOCK) {
                    // crc2 makes the CRC-16 of a whole syncframe after its sync word zero (ATSC A/52 clause 5.4.1.1
                    // and Annex E)
                    if (Crc16.crc16(0, data, position + 2, size - 2) != 0) {
                        throw new MediaException.Malformed("the " + name + " does not decrypt to valid syncframes: the "
                                + "SAMPLE-AES key or initialization vector is wrong");
                    }
                    verified = true;
                }
            }
            position += size;
        }
    }

    /**
     * Returns the cipher of the key of a PES packet.
     *
     * @param fileOffset the offset of the transport packet that started the PES packet
     * @return the cipher, whose key is {@link #current}
     */
    private CencCipher cipher(long fileOffset) {
        if (fileOffset == lastOffset && cipher != null) {
            return cipher;
        }
        var key = keys.find(fileOffset).orElse(null);
        if (key == null) {
            throw new MediaException.Unsupported("mpeg-ts sample-aes", "the " + name + " is encrypted with "
                    + "SAMPLE-AES and no key is known for the PES packet at offset " + fileOffset);
        }
        if (key != current && !key.equals(current)) {
            cipher = new CencCipher(key.keyBytes());
            current = key;
            verified = false;
        }
        lastOffset = fileOffset;
        return cipher;
    }
}

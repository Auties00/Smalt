/* Ports Shaka Packager v3.9.3 (packager/media/crypto/sample_aes_ec3_cryptor.cc, the E-AC-3 syncframe walk;
 * packager/media/codecs/nal_unit_to_byte_stream_converter.cc, the escaping of encrypted NAL units; and
 * packager/media/codecs/hls_audio_util.cc, the audio setup information). Copyright 2016-2018 Google LLC; BSD 3-Clause
 * License. Modified for Smalt. */
package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.internal.cenc.CencCipher;
import com.github.auties00.smalt.internal.cenc.CencScheme;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Codec;


/**
 * Encrypts the elementary streams of a transport stream in the HLS SAMPLE-AES format, as
 * {@link TsMuxerOptions#sampleAesKey()} describes. Instances are not thread-safe.
 */
final class SampleAesEncryptor {
    /**
     * The clear bytes at the start of an encrypted H.264 slice.
     */
    private static final int VIDEO_LEADER = 32;

    /**
     * The clear bytes at the start of an encrypted audio frame, after its ADTS header.
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

    private final CencCipher cipher;
    private final byte[] iv;
    private final int[] subsamples = new int[4];

    /**
     * The copy of the NAL unit being encrypted, reused across units.
     */
    private byte[] nal = new byte[4096];

    /**
     * Constructs an encryptor.
     *
     * @param key the 16-byte AES key
     * @param iv the 16-byte initialization vector
     */
    SampleAesEncryptor(byte[] key, byte[] iv) {
        this.cipher = new CencCipher(key);
        this.iv = iv.clone();
    }

    /**
     * Returns the {@code stream_type} of an encrypted stream.
     *
     * @param codec the stream's codec
     * @return the stream type, or {@code -1} for a codec SAMPLE-AES does not cover
     */
    static int streamType(Codec codec) {
        return switch (codec) {
            case H264 -> 0xDB;
            case AAC -> 0xCF;
            case AC3 -> 0xC1;
            case EAC3 -> 0xC2;
            default -> -1;
        };
    }

    /**
     * Writes the descriptors of an encrypted stream: the private data indicator descriptor, and for audio the
     * registration descriptor {@code apad} with the audio setup information.
     *
     * @param descriptors the descriptor loop being written
     * @param codec the stream's codec
     * @param config the codec configuration: the {@code AudioSpecificConfig} of AAC, the {@code dac3} or
     * {@code dec3} payload of AC-3 and E-AC-3
     * @throws IllegalArgumentException if the configuration of an audio stream is missing or longer than 255 bytes
     */
    static void descriptors(ByteWriter descriptors, Codec codec, byte[] config) {
        descriptors.u8(0x0F);
        descriptors.u8(4);
        descriptors.fourCc(switch (codec) {
            case H264 -> "zavc";
            case AAC -> "aacd";
            case AC3 -> "ac3d";
            default -> "ec3d";
        });
        if (codec == Codec.H264) {
            return;
        }
        if (config == null || config.length > 255 || 4 + 4 + 2 + 1 + 1 + config.length > 255) {
            throw new IllegalArgumentException("a SAMPLE-AES " + codec.displayName()
                    + " stream needs a codec configuration of at most 241 bytes");
        }
        var type = switch (codec) {
            case AAC -> switch (config.length > 0 ? (config[0] & 0xFF) >>> 3 : 2) {
                case 5 -> "zach";
                case 29 -> "zacp";
                default -> "zaac";
            };
            case AC3 -> "zac3";
            default -> "zec3";
        };
        descriptors.u8(0x05);
        descriptors.u8(4 + 4 + 2 + 1 + 1 + config.length);
        descriptors.fourCc("apad");
        descriptors.fourCc(type);
        descriptors.u16be(0);
        descriptors.u8(1);
        descriptors.u8(config.length);
        descriptors.bytes(config);
    }

    /**
     * Encrypts the slices of a length-prefixed H.264 access unit.
     *
     * @param data the access unit, not modified
     * @param lengthSize the size of its NAL unit lengths, 1 to 4 bytes
     * @return the encrypted access unit, its NAL units behind 4-byte lengths
     * @throws IllegalArgumentException if a NAL unit length overruns the access unit
     */
    byte[] video(byte[] data, int lengthSize) {
        var out = new ByteWriter(data.length + data.length / 64 + 16);
        var position = 0;
        while (position < data.length) {
            if (data.length - position < lengthSize) {
                throw new IllegalArgumentException("a NAL unit length overruns the access unit");
            }
            var size = NalUnits.readLength(data, position, lengthSize);
            var start = position + lengthSize;
            if (size < 0 || size > data.length - start) {
                throw new IllegalArgumentException("a NAL unit of " + size + " bytes overruns the access unit");
            }
            var length = (int) size;
            var type = length > 0 ? data[start] & 0x1F : 0;
            if ((type == 1 || type == 5) && length > VIDEO_LEADER + BLOCK) {
                // the NAL unit as stored, emulation prevention bytes included, is encrypted and then escaped again,
                // so a player that removes one level of escaping and decrypts gets the stored NAL unit back; the
                // 1:9 cbcs pattern, except that a last block with nothing after it stays clear
                if (nal.length < length) {
                    nal = new byte[Math.max(length, 2 * nal.length)];
                }
                System.arraycopy(data, start, nal, 0, length);
                var protectedBytes = length - VIDEO_LEADER;
                if (protectedBytes % (BLOCK + VIDEO_SKIP) == BLOCK) {
                    protectedBytes--;
                }
                subsamples[0] = VIDEO_LEADER;
                subsamples[1] = protectedBytes;
                subsamples[2] = length - VIDEO_LEADER - protectedBytes;
                cipher.encrypt(CencScheme.CBCS, nal, 0, length, iv, 0, BLOCK, subsamples, 0, 2, 1,
                        VIDEO_SKIP / BLOCK);
                var lengthField = out.size();
                out.u32be(0);
                escape(nal, length, out);
                out.patch32be(lengthField, out.size() - lengthField - 4L);
            } else {
                out.u32be(length);
                out.bytes(data, start, length);
            }
            position = start + length;
        }
        return out.toByteArray();
    }

    /**
     * Writes an encrypted NAL unit with the emulation prevention bytes it needs: a {@code 03} after every
     * {@code 00 00} followed by a byte of value 0 to 3, and after a final {@code 00}.
     */
    private static void escape(byte[] nal, int length, ByteWriter out) {
        var zeros = 0;
        for (var index = 0; index < length; index++) {
            var b = nal[index] & 0xFF;
            if (zeros == 2 && b <= 3) {
                out.u8(3);
                zeros = 0;
            }
            out.u8(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        if (length > 0 && nal[length - 1] == 0) {
            out.u8(3);
        }
    }

    /**
     * Encrypts an audio frame in place: the whole blocks after the 16-byte leader that follows the header of an ADTS
     * frame, of an AC-3 syncframe, or of each syncframe of an E-AC-3 frame. The CBC chain starts from the
     * initialization vector at each frame and runs on through the syncframes of an E-AC-3 frame; the bytes after
     * the last whole block of a syncframe stay clear.
     *
     * @param frame the frame, encrypted in place
     * @param codec the codec of the frame: AAC, AC-3 or E-AC-3
     * @param header the size of the ADTS header, 0 for AC-3 and E-AC-3
     */
    void audio(byte[] frame, Codec codec, int header) {
        cipher.restartChain(iv);
        if (codec != Codec.EAC3) {
            encryptSyncframe(frame, header, frame.length - header);
            return;
        }
        var position = 0;
        while (position + 4 <= frame.length && (frame[position] & 0xFF) == 0x0B && (frame[position + 1] & 0xFF) == 0x77) {
            var size = (((frame[position + 2] & 0x07) << 8) | (frame[position + 3] & 0xFF)) + 1;
            var length = Math.min(2 * size, frame.length - position);
            encryptSyncframe(frame, position, length);
            position += length;
        }
    }

    /**
     * Encrypts the whole blocks of one syncframe after its 16-byte leader, continuing the chain.
     */
    private void encryptSyncframe(byte[] frame, int offset, int length) {
        if (length > AUDIO_LEADER) {
            cipher.cbcChained(true, frame, offset + AUDIO_LEADER, length - AUDIO_LEADER);
        }
    }
}

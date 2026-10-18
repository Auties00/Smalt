package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Rewrites an AC-3 or E-AC-3 stream so that no frame uses dither, and recomputes its CRC words, so
 * that a decoder comparison is not limited by the decoders' different dither generators:
 * zero-bit mantissas then decode to exactly zero in every decoder. AC-3 frames and E-AC-3 frames
 * with dither flag syntax get their flags cleared in place; E-AC-3 frames without it (where every
 * channel dithers) are rebuilt with the syntax enabled and every flag zero, which grows them by
 * one bit per channel and block.
 */
public final class DitherClearing {
    private DitherClearing() {
    }

    public static byte[] clear(byte[] stream) {
        return clear(stream, -1, -1, -1);
    }

    /**
     * Clears the dither flags and, in one frame and block, toggles the block switch flag of one
     * channel.
     */
    public static byte[] clear(byte[] stream, int toggleFrame, int toggleBlock, int toggleChannel) {
        var frameIndex = 0;
        var output = new ByteArrayOutputStream();
        var decoder = new FrameDecoder(Ac3Kernels.scalar());
        decoder.ditherPositions = new int[64];
        var header = new FrameHeader();
        var position = 0;
        while (position + 8 <= stream.length) {
            if (stream[position] != 0x0B || stream[position + 1] != 0x77) {
                position++;
                continue;
            }
            var reader = new MsbBitReader(stream, position, stream.length - position);
            if (!header.parse(reader, stream.length - position)) {
                position++;
                continue;
            }
            var length = header.frameBytes;
            var frame = Arrays.copyOfRange(stream, position, position + length);
            decoder.ditherSyntaxPosition = -1;
            decoder.parse(frame, 0, length, false, false);
            for (var i = 0; i < decoder.ditherCount; i++) {
                var bit = decoder.ditherPositions[i];
                frame[bit / 8] &= (byte) ~(0x80 >>> (bit % 8));
            }
            if (frameIndex == toggleFrame && decoder.ditherCount > 0) {
                var channels = header.channels;
                var bit = decoder.ditherPositions[toggleBlock * channels] - channels + toggleChannel;
                frame[bit / 8] ^= (byte) (0x80 >>> (bit % 8));
            }
            frameIndex++;
            if (header.enhanced) {
                if (decoder.ditherCount == 0 && decoder.ditherSyntaxPosition >= 0) {
                    frame = rebuild(frame, decoder, header.channels, header.blocks);
                }
                var crc = Ac3Crc.compute(frame, 2, frame.length - 4);
                frame[frame.length - 2] = (byte) (crc >> 8);
                frame[frame.length - 1] = (byte) crc;
            } else {
                var words = length >> 1;
                var fiveEighths = ((words >> 1) + (words >> 3)) * 2;
                frame[2] = 0;
                frame[3] = 0;
                var residue = Ac3Crc.compute(frame, 2, fiveEighths - 2);
                var crc1 = Ac3Crc.prefix(residue, fiveEighths - 2);
                frame[2] = (byte) (crc1 >> 8);
                frame[3] = (byte) crc1;
                var crc2 = Ac3Crc.compute(frame, fiveEighths, length - fiveEighths - 2);
                frame[length - 2] = (byte) (crc2 >> 8);
                frame[length - 1] = (byte) crc2;
            }
            output.writeBytes(frame);
            position += length;
        }
        return output.toByteArray();
    }

    /**
     * Rebuilds an E-AC-3 frame with dither flag syntax enabled and every flag zero.
     */
    private static byte[] rebuild(byte[] frame, FrameDecoder decoder, int channels, int blocks) {
        var totalBits = frame.length * 8;
        var contentBits = totalBits - 17;
        var bits = new boolean[contentBits + channels * blocks + 16];
        var length = 0;
        var insertion = 0;
        for (var bit = 0; bit < contentBits; bit++) {
            while (insertion < blocks && decoder.ditherInsertions[insertion] == bit) {
                for (var ch = 0; ch < channels; ch++) {
                    bits[length++] = false;
                }
                insertion++;
            }
            var value = (frame[bit / 8] & (0x80 >>> (bit % 8))) != 0;
            if (bit == decoder.ditherSyntaxPosition) {
                value = true;
            }
            bits[length++] = value;
        }
        var auxiliaryExists = bits[length - 1];
        length--;
        while ((length + 1 + 17) % 16 != 0) {
            bits[length++] = false;
        }
        bits[length++] = auxiliaryExists;
        var encinfo = (frame[(totalBits - 17) / 8] & (0x80 >>> ((totalBits - 17) % 8))) != 0;
        var out = new byte[(length + 17) / 8];
        for (var bit = 0; bit < length; bit++) {
            if (bits[bit]) {
                out[bit / 8] |= (byte) (0x80 >>> (bit % 8));
            }
        }
        if (encinfo) {
            out[length / 8] |= (byte) (0x80 >>> (length % 8));
        }
        var words = out.length / 2;
        var frmsiz = words - 1;
        out[2] = (byte) ((out[2] & 0xF8) | ((frmsiz >> 8) & 0x07));
        out[3] = (byte) frmsiz;
        return out;
    }
}

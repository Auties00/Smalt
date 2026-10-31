/* Ports OpenEXR 3.5.2 (src/lib/OpenEXRCore/internal_ht.cpp, internal_ht_common.cpp, internal_ht_common.h).
   Copyright Contributors to the OpenEXR Project; BSD-3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.image.exr.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.internal.Jp2Reader;

import java.util.Arrays;

/**
 * Decompresses the chunks of the OpenEXR compressions built on JPEG 2000 with the high-throughput block coder:
 * HTJ2K256 (method 10) and HTJ2K32 (method 11), lossless, and LJ2K (method 12), lossy on the RGB channels.
 *
 * <p>A compressed chunk is a big-endian header followed by a JPEG 2000 codestream with one component per channel.
 * The header holds a magic ({@code "HT"}, or {@code "HL"} for an LJ2K chunk whose lossy components carry their
 * transfer function in an {@code NLT} segment), the length of the payload that follows, and in the payload the
 * channel count and, for each codestream component, the index of its channel in name order:
 *
 * {@snippet :
 * u16 magic      0x4854 "HT" or 0x484C "HL"
 * u32 length     of the payload
 * u16 count      channels
 * u16 channel    for each component, its channel index
 * ...            opaque bytes up to the payload length
 * JPEG 2000 codestream
 * }
 *
 * <p>A component holds the bits of a half or float sample as a signed 16-bit or 32-bit integer (with the
 * sign-magnitude {@code NLT} of type 3 when lossless), or an unsigned integer sample as is. Lossy chunks may extend
 * the codestream past the chunk by repeating its last row and column; those samples are dropped.
 */
final class ExrHt {
    /**
     * The magic of a chunk.
     */
    private static final int MAGIC = 0x4854;

    /**
     * The magic of an LJ2K chunk whose transfer function is an {@code NLT} segment.
     */
    private static final int LOOKUP_MAGIC = 0x484C;

    /**
     * The largest number of decomposition levels of a codestream padded past its chunk.
     */
    private static final int PADDED_LEVELS = 5;

    /**
     * The factor of the legacy LJ2K transfer function of 16-bit samples, 32767 over the transfer of the largest
     * half.
     */
    private static final double LEGACY_HALF_FACTOR = 5424.23808866629;

    /**
     * The factor of the legacy LJ2K transfer function of 32-bit samples, 2^31 - 1 over the transfer of the largest
     * float.
     */
    private static final double LEGACY_FLOAT_FACTOR = 51961246.180338;

    private ExrHt() {
        throw new AssertionError();
    }

    /**
     * Decompresses a chunk.
     *
     * @param method the compression method, 10 to 12
     * @param data the bytes
     * @param offset the offset of the compressed chunk
     * @param length the compressed bytes
     * @param block the chunk's pixels
     * @param size the uncompressed bytes
     * @param limits the limits bounding the codestream's decode
     * @return the uncompressed chunk
     * @throws MediaException.Malformed if the chunk is invalid
     */
    static byte[] decompress(int method, byte[] data, int offset, int length, ExrBlock block, int size,
                             MediaLimits limits) {
        var channels = block.channels();
        var count = channels.size();
        if (length < 8) {
            throw new MediaException.Malformed("an OpenEXR HTJ2K chunk of " + length + " bytes");
        }
        var magic = u16(data, offset);
        if (magic != MAGIC && !(method == 12 && magic == LOOKUP_MAGIC)) {
            throw new MediaException.Malformed("an OpenEXR HTJ2K chunk of magic " + Integer.toHexString(magic));
        }
        var payload = ExrHeader.i32(data, offset + 2);
        payload = Integer.reverseBytes(payload);
        if (payload < 2 || payload > length - 6) {
            throw new MediaException.Malformed("an OpenEXR HTJ2K chunk header of " + payload + " bytes");
        }
        if (u16(data, offset + 6) != count || payload < 2 + 2 * count) {
            throw new MediaException.Malformed("an OpenEXR HTJ2K channel map of another channel count");
        }
        var map = new int[count];
        var seen = new boolean[count];
        for (var component = 0; component < count; component++) {
            var channel = u16(data, offset + 8 + 2 * component);
            if (channel >= count || seen[channel]) {
                throw new MediaException.Malformed("an OpenEXR HTJ2K channel map with channel " + channel);
            }
            seen[channel] = true;
            map[component] = channel;
        }
        var start = offset + 6 + payload;
        if (start >= offset + length) {
            throw new MediaException.Malformed("an OpenEXR HTJ2K chunk without a codestream");
        }
        var codestream = Arrays.copyOfRange(data, start, offset + length);
        var headers = Jp2Reader.headers(codestream, limits);
        var stream = headers.codestream();
        if (stream.components() != count || stream.x0() != 0 || stream.y0() != 0) {
            throw new MediaException.Malformed("an OpenEXR HTJ2K codestream of another layout");
        }
        var width = stream.width();
        var height = stream.height();
        var padded = width != block.width() || height != block.height();
        var legacy = method == 12 && magic == MAGIC;
        var lossy = new boolean[count];
        // the padding is declared by the main coding style, that of the first component
        var levels = Jp2Reader.decompositionLevels(stream, 0);
        var reversibleMain = Jp2Reader.reversible(stream, 0);
        for (var component = 0; component < count; component++) {
            lossy[component] = legacy && !Jp2Reader.reversible(stream, component);
            var channel = map[component];
            var precision = channels.get(channel).pixelType() == 1 ? 16 : 32;
            if (stream.precision(component) != precision
                    || stream.signed(component) != (channels.get(channel).pixelType() != 0)) {
                throw new MediaException.Malformed("an OpenEXR HTJ2K component of another sample type");
            }
            if (padded) {
                if (reversibleMain || levels > PADDED_LEVELS || width != paddedLength(block.width(), levels)
                        || height != paddedLength(block.height(), levels) || stream.subsamplingX(component) != 1
                        || stream.subsamplingY(component) != 1 || block.channelWidth(channel) != block.width()
                        || block.channelHeight(channel) != block.height()) {
                    throw new MediaException.Malformed("an OpenEXR HTJ2K codestream of another size");
                }
            } else if (Math.ceilDiv(width, stream.subsamplingX(component)) != block.channelWidth(channel)
                    || Math.ceilDiv(height, stream.subsamplingY(component)) != block.channelHeight(channel)) {
                throw new MediaException.Malformed("an OpenEXR HTJ2K component of another size");
            }
        }
        var image = Jp2Reader.decode(headers, 0, 0, limits);
        var components = image.channels();
        if (components.size() != count) {
            throw new MediaException.Malformed("an OpenEXR HTJ2K codestream of another component count");
        }
        var byChannel = new Jp2Channel[count];
        var lossyChannel = new boolean[count];
        for (var component = 0; component < count; component++) {
            byChannel[map[component]] = components.get(component);
            lossyChannel[map[component]] = lossy[component];
        }
        var out = new byte[size];
        var position = 0;
        var rows = new int[count];
        for (var row = 0; row < block.height(); row++) {
            for (var channel = 0; channel < count; channel++) {
                var samples = block.channelWidth(channel);
                if (block.channelHeight(channel) == 0 || !block.samples(channel, block.y() + row) || samples == 0) {
                    continue;
                }
                var component = byChannel[channel];
                var line = rows[channel]++;
                if (line >= component.height() || samples > component.width()) {
                    throw new MediaException.Malformed("an OpenEXR HTJ2K component of another size");
                }
                var half = channels.get(channel).pixelType() == 1;
                var base = line * component.width();
                for (var column = 0; column < samples; column++) {
                    var value = component.wide() ? component.wideSamples()[base + column]
                            : component.samples()[base + column];
                    if (half) {
                        var bits = lossyChannel[channel] ? legacyHalf(value) : (int) value;
                        out[position++] = (byte) bits;
                        out[position++] = (byte) (bits >>> 8);
                    } else {
                        var bits = lossyChannel[channel] ? legacyFloat(value) : (int) value;
                        out[position++] = (byte) bits;
                        out[position++] = (byte) (bits >>> 8);
                        out[position++] = (byte) (bits >>> 16);
                        out[position++] = (byte) (bits >>> 24);
                    }
                }
            }
        }
        if (position != size) {
            throw new MediaException.Malformed("an OpenEXR HTJ2K chunk decodes to " + position + " of " + size
                    + " bytes");
        }
        return out;
    }

    /**
     * {@return the smallest length of at least {@code length} congruent to 1 modulo {@code 2^levels}}
     *
     * @param length the chunk's length
     * @param levels the decomposition levels
     */
    static long paddedLength(int length, int levels) {
        var modulus = 1L << levels;
        return length + Math.floorMod(1L - length, modulus);
    }

    /**
     * Undoes the transfer function of a lossy 16-bit sample of an LJ2K chunk of OpenEXR 3.5.0 and 3.5.1.
     *
     * @param value the decoded sample
     * @return the bits of the half
     */
    static int legacyHalf(long value) {
        var clamped = Math.clamp(value, -32767, 32767);
        return Float.floatToFloat16((float) toLinear(clamped / LEGACY_HALF_FACTOR));
    }

    /**
     * Undoes the transfer function of a lossy 32-bit sample of an LJ2K chunk of OpenEXR 3.5.0 and 3.5.1.
     *
     * @param value the decoded sample
     * @return the bits of the float
     */
    static int legacyFloat(long value) {
        var linear = toLinear((int) value / LEGACY_FLOAT_FACTOR);
        return Float.floatToRawIntBits((float) Math.clamp(linear, -Float.MAX_VALUE, Float.MAX_VALUE));
    }

    /**
     * Maps a value of the transfer function domain back to linear light: the power 2.2 up to a magnitude of 1 and
     * an exponential above, with the sign of the value.
     *
     * @param value the transfer function domain value
     * @return the linear value
     */
    static double toLinear(double value) {
        var magnitude = Math.abs(value);
        var linear = magnitude <= 1 ? StrictMath.pow(magnitude, 2.2f) : StrictMath.exp(2.2f * (magnitude - 1));
        return value < 0 ? -linear : linear;
    }

    /**
     * Reads a big-endian 16-bit value.
     */
    private static int u16(byte[] data, int position) {
        return (data[position] & 0xFF) << 8 | data[position + 1] & 0xFF;
    }
}

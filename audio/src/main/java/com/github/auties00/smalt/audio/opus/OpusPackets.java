/* Ports libopus 1.5.2 (src/opus.c, src/repacketizer.c). Copyright Xiph.Org Foundation and Skype Limited; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusPacket;
import com.github.auties00.smalt.audio.opus.internal.Repacketizer;
import com.github.auties00.smalt.audio.opus.internal.dnn.Dred;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Inspects Opus packets (RFC 6716 section 3) and rearranges their frames without decoding them.
 *
 * <p>The table-of-contents byte that starts every packet names the coding mode, the audio bandwidth,
 * the frame duration and the coded channel count; the frame count follows from its code bits and,
 * for code 3, from the second byte. {@link #hasInbandFec(byte[], int, int)} reads the in-band forward
 * error correction flag of the first SILK frame and {@link #hasDred(byte[], int, int)} looks for Deep REDundancy.
 * {@link #merge(List)} joins the frames of several packets that share a configuration into one packet and
 * {@link #split(byte[], int, int)} undoes it; neither keeps the padding bytes of its input, but both keep the padding
 * extensions (DRED among them), renumbered to the frames they belong to in the output.
 */
public final class OpusPackets {
    private OpusPackets() {
        throw new AssertionError();
    }

    /**
     * Returns the audio bandwidth a packet codes, read from its table-of-contents byte.
     *
     * @param packet the buffer holding the packet
     * @param offset the index of the table-of-contents byte
     * @return the bandwidth
     * @throws IndexOutOfBoundsException if {@code offset} lies outside {@code packet}
     */
    public static OpusBandwidth bandwidth(byte[] packet, int offset) {
        Objects.requireNonNull(packet, "packet");
        Objects.checkIndex(offset, packet.length);
        return OpusBandwidth.ofCode(OpusPacket.getBandwidth(packet[offset] & 0xFF));
    }

    /**
     * Returns the number of channels a packet codes, read from its table-of-contents byte.
     *
     * @param packet the buffer holding the packet
     * @param offset the index of the table-of-contents byte
     * @return 1 or 2
     * @throws IndexOutOfBoundsException if {@code offset} lies outside {@code packet}
     */
    public static int channels(byte[] packet, int offset) {
        Objects.requireNonNull(packet, "packet");
        Objects.checkIndex(offset, packet.length);
        return OpusPacket.getNbChannels(packet[offset] & 0xFF);
    }

    /**
     * Returns the duration of each frame of a packet, read from its table-of-contents byte.
     *
     * @param packet the buffer holding the packet
     * @param offset the index of the table-of-contents byte
     * @param sampleRate the rate the samples are counted at, in hertz
     * @return the number of samples per channel in one frame: 2.5, 5, 10, 20, 40 or 60 ms worth
     * @throws IllegalArgumentException if {@code sampleRate} is not positive
     * @throws IndexOutOfBoundsException if {@code offset} lies outside {@code packet}
     */
    public static int samplesPerFrame(byte[] packet, int offset, int sampleRate) {
        Objects.requireNonNull(packet, "packet");
        Objects.checkIndex(offset, packet.length);
        checkRate(sampleRate);
        return OpusPacket.getSamplesPerFrame(packet[offset] & 0xFF, sampleRate);
    }

    /**
     * Returns the number of frames in a packet, read from its code bits and, for code 3, its second
     * byte.
     *
     * @param packet the buffer holding the packet
     * @param offset the index of the table-of-contents byte
     * @param length the packet length, at least 1
     * @return the frame count, 0 to 63
     * @throws IllegalArgumentException if {@code length} is zero
     * @throws IndexOutOfBoundsException if the packet lies outside {@code packet}
     * @throws MediaException.Malformed if a code 3 packet lacks its frame count byte
     */
    public static int frames(byte[] packet, int offset, int length) {
        checkPacket(packet, offset, length);
        var frames = OpusPacket.getNbFrames(packet, offset, length);
        if (frames < 0) {
            throw new MediaException.Malformed("invalid Opus packet: " + OpusDefines.strerror(frames));
        }
        return frames;
    }

    /**
     * Returns the number of samples a packet decodes to: its frame count times its frame duration.
     *
     * @param packet the buffer holding the packet
     * @param offset the index of the table-of-contents byte
     * @param length the packet length, at least 1
     * @param sampleRate the rate the samples are counted at, in hertz
     * @return the number of samples per channel, at most 120 ms worth
     * @throws IllegalArgumentException if {@code length} is zero or {@code sampleRate} is not
     * positive
     * @throws IndexOutOfBoundsException if the packet lies outside {@code packet}
     * @throws MediaException.Malformed if a code 3 packet lacks its frame count byte or the packet
     * is longer than 120 ms
     */
    public static int samples(byte[] packet, int offset, int length, int sampleRate) {
        checkPacket(packet, offset, length);
        checkRate(sampleRate);
        var samples = OpusPacket.getNbSamples(packet, offset, length, sampleRate);
        if (samples < 0) {
            throw new MediaException.Malformed("invalid Opus packet: " + OpusDefines.strerror(samples));
        }
        return samples;
    }

    /**
     * Returns whether a packet carries the in-band forward error correction data that
     * {@link OpusDecoder#recover(byte[], int, int, short[], int, int)} rebuilds the lost packet
     * before it from.
     *
     * <p>The flag is read from the first SILK frame of the packet, for the mid channel and, in a
     * stereo packet, the side channel. A CELT-only packet, and a packet whose first frame is empty
     * (a discontinuous transmission packet), carry no such data.
     *
     * @param packet the buffer holding the packet
     * @param offset the index of the table-of-contents byte
     * @param length the packet length, at least 1
     * @return {@code true} when the packet carries forward error correction data
     * @throws IllegalArgumentException if {@code length} is zero
     * @throws IndexOutOfBoundsException if the packet lies outside {@code packet}
     * @throws MediaException.Malformed if the packet framing is invalid
     */
    public static boolean hasInbandFec(byte[] packet, int offset, int length) {
        checkPacket(packet, offset, length);
        var result = OpusPacket.hasLbrr(packet, offset, length, new OpusPacket.Layout());
        if (result < 0) {
            throw new MediaException.Malformed("invalid Opus packet: " + OpusDefines.strerror(result));
        }
        return result == 1;
    }

    /**
     * Returns whether a packet carries Deep REDundancy that {@link OpusDred#parse} can decode: a DRED extension of
     * the experimental version libopus 1.5.2 writes (id 126, version 10) in its padding.
     *
     * @param packet the buffer holding the packet
     * @param offset the index of the table-of-contents byte
     * @param length the packet length, at least 1
     * @return {@code true} when the packet carries DRED
     * @throws IllegalArgumentException if {@code length} is zero
     * @throws IndexOutOfBoundsException if the packet lies outside {@code packet}
     * @throws MediaException.Malformed if the packet framing is invalid
     */
    public static boolean hasDred(byte[] packet, int offset, int length) {
        checkPacket(packet, offset, length);
        var result = Dred.findPayload(packet, offset, length, new OpusPacket.Layout(), new int[3]);
        if (result < 0) {
            throw new MediaException.Malformed("invalid Opus packet: " + OpusDefines.strerror(result));
        }
        return result == 1;
    }

    /**
     * Joins the frames of several packets into one packet.
     *
     * <p>Every packet must share the mode, bandwidth, frame duration and channel count of the first,
     * and together they may hold at most 120 ms. A single packet holding one frame comes back as a
     * code 0 packet with the same configuration and frame.
     *
     * @param packets the packets, in playback order
     * @return a fresh array holding the joined packet
     * @throws NullPointerException if an element of {@code packets} is {@code null}
     * @throws IllegalArgumentException if {@code packets} is empty
     * @throws MediaException.Malformed if a packet is invalid, differs in configuration from the
     * first, or the frames exceed 120 ms
     */
    public static byte[] merge(List<byte[]> packets) {
        Objects.requireNonNull(packets, "packets");
        if (packets.isEmpty()) {
            throw new IllegalArgumentException("no Opus packets to merge");
        }
        var repacketizer = new Repacketizer();
        var total = 0;
        for (var packet : packets) {
            Objects.requireNonNull(packet, "packet");
            var result = repacketizer.cat(packet, 0, packet.length);
            if (result != OpusDefines.OPUS_OK) {
                throw new MediaException.Malformed("cannot merge Opus packet: " + OpusDefines.strerror(result));
            }
            total += packet.length;
        }
        var out = new int[1];
        var packet = write(repacketizer, 0, repacketizer.nbFrames(), total + 2 + 4 * repacketizer.nbFrames() + 64,
                total, out);
        if (packet == null) {
            throw new MediaException.Malformed("cannot merge Opus packets: " + OpusDefines.strerror(out[0]));
        }
        return packet;
    }

    /**
     * Writes frames {@code [begin, end)} of a repacketizer as one packet, growing the output buffer while the
     * regenerated padding extensions do not fit: they may need frame separators and length bytes that the input
     * packets did not.
     *
     * @param repacketizer the frames
     * @param begin the first frame
     * @param end one past the last frame
     * @param capacity the first buffer size to try
     * @param input the total size of the input packets, which bounds the output
     * @param error receives the error code when the packet cannot be written
     * @return the packet, or {@code null} on an error other than a full buffer
     */
    private static byte[] write(Repacketizer repacketizer, int begin, int end, int capacity, int input, int[] error) {
        // Every extension grows by at most a header, a 2-byte separator and its length bytes, so 4 times the input
        // plus some slack always fits.
        var limit = 4L * input + 1024;
        while (true) {
            var out = new byte[capacity];
            var length = repacketizer.outRange(begin, end, out, 0, capacity, false);
            if (length >= 0) {
                return Arrays.copyOf(out, length);
            }
            if (length != OpusDefines.OPUS_BUFFER_TOO_SMALL || capacity >= limit) {
                error[0] = length;
                return null;
            }
            capacity = (int) Math.min(limit, 2L * capacity);
        }
    }

    /**
     * Splits a packet into one packet per frame.
     *
     * <p>Each returned packet holds one frame under a code 0 table of contents with the original
     * configuration.
     *
     * @param packet the buffer holding the packet
     * @param offset the index of the table-of-contents byte
     * @param length the packet length, at least 1
     * @return fresh arrays, one per frame, in playback order
     * @throws IllegalArgumentException if {@code length} is zero
     * @throws IndexOutOfBoundsException if the packet lies outside {@code packet}
     * @throws MediaException.Malformed if the packet is invalid
     */
    public static List<byte[]> split(byte[] packet, int offset, int length) {
        checkPacket(packet, offset, length);
        var repacketizer = new Repacketizer();
        var result = repacketizer.cat(packet, offset, length);
        if (result != OpusDefines.OPUS_OK) {
            throw new MediaException.Malformed("cannot split Opus packet: " + OpusDefines.strerror(result));
        }
        var count = repacketizer.nbFrames();
        var frames = new ArrayList<byte[]>(count);
        var error = new int[1];
        for (var i = 0; i < count; i++) {
            var frame = write(repacketizer, i, i + 1, length + 64, length, error);
            if (frame == null) {
                throw new MediaException.Malformed("cannot split Opus packet: " + OpusDefines.strerror(error[0]));
            }
            frames.add(frame);
        }
        return frames;
    }

    /**
     * Checks that a packet span is non-empty and inside its buffer.
     *
     * @param packet the buffer
     * @param offset the index of the first byte
     * @param length the span length
     * @throws IllegalArgumentException if {@code length} is zero
     * @throws IndexOutOfBoundsException if the span lies outside {@code packet}
     */
    private static void checkPacket(byte[] packet, int offset, int length) {
        Objects.requireNonNull(packet, "packet");
        Objects.checkFromIndexSize(offset, length, packet.length);
        if (length == 0) {
            throw new IllegalArgumentException("empty Opus packet");
        }
    }

    /**
     * Checks that a counting rate is positive.
     *
     * @param sampleRate the rate in hertz
     * @throws IllegalArgumentException if the rate is not positive
     */
    private static void checkRate(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("invalid sample rate " + sampleRate);
        }
    }
}

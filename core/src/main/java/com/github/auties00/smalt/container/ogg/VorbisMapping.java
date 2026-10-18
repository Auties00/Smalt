package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.internal.bits.LsbBitReader;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Implements the Ogg Vorbis mapping of the Vorbis I specification.
 *
 * <p>The stream opens with the identification, comment and setup headers; the three are
 * Xiph-laced into the track's codec configuration. Granule positions count samples at the
 * stream's rate. A packet's duration is a quarter of the previous block size plus a quarter of
 * its own, the block size being selected by the packet's mode; the first audio packet, which
 * decodes to no output, is given a duration as if it followed a block of its own size, so it ends
 * where the second packet's output begins.
 *
 * <p>Knowing the block size of each mode requires walking the whole setup header (codebooks,
 * floors, residues and mappings precede the modes), which also validates it.
 */
final class VorbisMapping extends OggMapping {
    /**
     * The three header packets.
     */
    private final byte[][] headers = new byte[3][];

    /**
     * The channel count.
     */
    private int channels;

    /**
     * The sampling rate.
     */
    private int sampleRate;

    /**
     * The short block size.
     */
    private int shortBlock;

    /**
     * The long block size.
     */
    private int longBlock;

    /**
     * Whether each mode uses the long block.
     */
    private boolean[] modeLong;

    /**
     * The number of bits of the mode number in an audio packet.
     */
    private int modeBits;

    /**
     * The block size of the previous audio packet, or {@code 0} before the first.
     */
    private int previousBlock;

    /**
     * The duration of the first audio packet, which the decoder turns into no output.
     */
    private long firstDuration = -1;

    @Override
    int headerCount() {
        return 3;
    }

    @Override
    void header(int index, byte[] packet, MediaLimits limits, Map<String, String> metadata) {
        var type = index * 2 + 1;
        if (packet.length < 7 || (packet[0] & 0xFF) != type || !startsWith(packet, 1, "vorbis")) {
            throw new MediaException.Malformed("Vorbis header " + (index + 1) + " is invalid");
        }
        headers[index] = packet;
        switch (index) {
            case 0 -> parseIdentification(packet, limits);
            case 1 -> {
                if (metadata != null) {
                    try {
                        Annotations.readVorbisComments(packet, 7, packet.length - 7, metadata, annotations);
                    } catch (MediaException.Malformed ignored) {
                        // comments are optional; a damaged comment header does not prevent decoding
                    }
                }
            }
            default -> parseSetup(packet);
        }
    }

    /**
     * Parses the identification header.
     *
     * @param packet the header
     * @param limits the resource bounds
     */
    private void parseIdentification(byte[] packet, MediaLimits limits) {
        if (packet.length < 30) {
            throw new MediaException.Malformed("Vorbis identification header truncated");
        }
        if (ByteArrays.i32le(packet, 7) != 0) {
            throw new MediaException.Unsupported("vorbis version " + ByteArrays.i32le(packet, 7), "unsupported Vorbis version");
        }
        channels = packet[11] & 0xFF;
        LimitChecks.checkChannels(limits, channels);
        sampleRate = ByteArrays.i32le(packet, 12);
        if (sampleRate <= 0) {
            throw new MediaException.Malformed("invalid Vorbis sample rate " + sampleRate);
        }
        var sizes = packet[28] & 0xFF;
        shortBlock = 1 << (sizes & 0x0F);
        longBlock = 1 << (sizes >> 4);
        if (shortBlock < 64 || longBlock > 8192 || shortBlock > longBlock || (packet[29] & 1) == 0) {
            throw new MediaException.Malformed("invalid Vorbis block sizes or framing");
        }
    }

    /**
     * Walks the setup header and records the block flag of every mode.
     *
     * @param packet the setup header
     */
    private void parseSetup(byte[] packet) {
        if (channels == 0) {
            throw new MediaException.Malformed("Vorbis setup header before the identification header");
        }
        var reader = new LsbBitReader(packet, 7, packet.length - 7);
        var codebooks = reader.read(8) + 1;
        for (var i = 0; i < codebooks; i++) {
            skipCodebook(reader);
        }
        var times = reader.read(6) + 1;
        for (var i = 0; i < times; i++) {
            if (reader.read(16) != 0) {
                throw new MediaException.Malformed("invalid Vorbis time domain transform");
            }
        }
        var floors = reader.read(6) + 1;
        for (var i = 0; i < floors; i++) {
            skipFloor(reader);
        }
        var residues = reader.read(6) + 1;
        for (var i = 0; i < residues; i++) {
            skipResidue(reader);
        }
        var mappings = reader.read(6) + 1;
        for (var i = 0; i < mappings; i++) {
            skipMapping(reader);
        }
        var modes = reader.read(6) + 1;
        modeLong = new boolean[modes];
        for (var i = 0; i < modes; i++) {
            modeLong[i] = reader.read(1) != 0;
            if (reader.read(16) != 0 || reader.read(16) != 0 || reader.read(8) >= mappings) {
                throw new MediaException.Malformed("invalid Vorbis mode " + i);
            }
        }
        if (reader.read(1) != 1) {
            throw new MediaException.Malformed("Vorbis setup header without framing bit");
        }
        reader.checkNotOverrun();
        modeBits = ilog(modes - 1);
    }

    /**
     * Skips one codebook of the setup header.
     *
     * @param reader the setup header reader
     */
    private static void skipCodebook(LsbBitReader reader) {
        if (reader.read(24) != 0x564342) {
            throw new MediaException.Malformed("invalid Vorbis codebook sync pattern");
        }
        var dimensions = reader.read(16);
        var entries = reader.read(24);
        if (reader.read(1) == 0) {
            var sparse = reader.read(1) != 0;
            for (var i = 0; i < entries; i++) {
                if (!sparse || reader.read(1) != 0) {
                    reader.read(5);
                }
                checkRemaining(reader);
            }
        } else {
            reader.read(5);
            var current = 0;
            while (current < entries) {
                current += reader.read(ilog(entries - current));
                checkRemaining(reader);
            }
            if (current > entries) {
                throw new MediaException.Malformed("Vorbis codebook lengths exceed its entries");
            }
        }
        var lookup = reader.read(4);
        if (lookup == 0) {
            return;
        }
        if (lookup > 2) {
            throw new MediaException.Malformed("invalid Vorbis codebook lookup type " + lookup);
        }
        reader.read(32);
        reader.read(32);
        var valueBits = reader.read(4) + 1;
        reader.read(1);
        long values;
        if (lookup == 1) {
            if (dimensions == 0) {
                throw new MediaException.Malformed("Vorbis codebook with zero dimensions");
            }
            values = lookup1Values(entries, dimensions);
        } else {
            values = (long) entries * dimensions;
        }
        var bits = values * valueBits;
        if (bits > reader.bitsLeft()) {
            throw new MediaException.Malformed("Vorbis codebook lookup table exceeds the setup header");
        }
        reader.skip(bits);
    }

    /**
     * Returns the largest integer whose {@code dimensions}-th power does not exceed
     * {@code entries} (Vorbis I section 9.2.3).
     *
     * @param entries the codebook entry count
     * @param dimensions the codebook dimension count, positive
     * @return the lookup value count
     */
    static long lookup1Values(int entries, int dimensions) {
        var root = (long) Math.floor(Math.pow(entries, 1.0 / dimensions));
        while (root > 0 && power(root, dimensions) > entries) {
            root--;
        }
        while (power(root + 1, dimensions) <= entries) {
            root++;
        }
        return root;
    }

    /**
     * Returns {@code base} raised to {@code exponent}, saturating above {@code 2^31}.
     *
     * @param base the base, non-negative
     * @param exponent the exponent, positive
     * @return the power, or a value above {@code 2^31} when it overflows that bound
     */
    private static long power(long base, int exponent) {
        var result = 1L;
        for (var i = 0; i < exponent; i++) {
            result *= base;
            if (result > 1L << 31) {
                return (1L << 31) + 1;
            }
        }
        return result;
    }

    /**
     * Skips one floor configuration.
     *
     * @param reader the setup header reader
     */
    private static void skipFloor(LsbBitReader reader) {
        var type = reader.read(16);
        if (type == 0) {
            reader.read(8);
            reader.read(16);
            reader.read(16);
            reader.read(6);
            reader.read(8);
            var books = reader.read(4) + 1;
            reader.skip(8L * books);
            return;
        }
        if (type != 1) {
            throw new MediaException.Malformed("invalid Vorbis floor type " + type);
        }
        var partitions = reader.read(5);
        var classes = new int[partitions];
        var maximumClass = -1;
        for (var i = 0; i < partitions; i++) {
            classes[i] = reader.read(4);
            maximumClass = Math.max(maximumClass, classes[i]);
        }
        var dimensions = new int[maximumClass + 1];
        for (var i = 0; i <= maximumClass; i++) {
            dimensions[i] = reader.read(3) + 1;
            var subclasses = reader.read(2);
            if (subclasses != 0) {
                reader.read(8);
            }
            reader.skip(8L << subclasses);
        }
        reader.read(2);
        var rangeBits = reader.read(4);
        var values = 0;
        for (var i = 0; i < partitions; i++) {
            values += dimensions[classes[i]];
        }
        if (values + 2 > 65) {
            throw new MediaException.Malformed("Vorbis floor 1 with " + (values + 2) + " values");
        }
        reader.skip((long) values * rangeBits);
    }

    /**
     * Skips one residue configuration.
     *
     * @param reader the setup header reader
     */
    private static void skipResidue(LsbBitReader reader) {
        var type = reader.read(16);
        if (type > 2) {
            throw new MediaException.Malformed("invalid Vorbis residue type " + type);
        }
        reader.read(24);
        reader.read(24);
        reader.read(24);
        var classifications = reader.read(6) + 1;
        reader.read(8);
        var books = 0;
        for (var i = 0; i < classifications; i++) {
            var cascade = reader.read(3);
            if (reader.read(1) != 0) {
                cascade |= reader.read(5) << 3;
            }
            books += Integer.bitCount(cascade);
        }
        reader.skip(8L * books);
    }

    /**
     * Skips one mapping configuration.
     *
     * @param reader the setup header reader
     */
    private void skipMapping(LsbBitReader reader) {
        if (reader.read(16) != 0) {
            throw new MediaException.Malformed("invalid Vorbis mapping type");
        }
        var submaps = reader.read(1) != 0 ? reader.read(4) + 1 : 1;
        if (reader.read(1) != 0) {
            var steps = reader.read(8) + 1;
            reader.skip(2L * steps * ilog(channels - 1));
        }
        if (reader.read(2) != 0) {
            throw new MediaException.Malformed("invalid Vorbis mapping reserved bits");
        }
        if (submaps > 1) {
            reader.skip(4L * channels);
        }
        reader.skip(24L * submaps);
    }

    /**
     * Fails when the reader ran past the end of the setup header.
     *
     * @param reader the setup header reader
     */
    private static void checkRemaining(LsbBitReader reader) {
        if (reader.bitsLeft() < 0) {
            throw new MediaException.Malformed("Vorbis setup header truncated");
        }
    }

    /**
     * Returns the number of bits needed to represent a value (Vorbis I section 9.2.1).
     *
     * @param value a non-negative value
     * @return the bit count, zero for zero and negative values
     */
    static int ilog(int value) {
        return value <= 0 ? 0 : 32 - Integer.numberOfLeadingZeros(value);
    }

    /**
     * Returns the speaker layout of the Vorbis channel order (Vorbis I section 4.3.9).
     *
     * @param channels the channel count
     * @return the layout; channels beyond eight have unknown positions
     */
    static ChannelLayout layout(int channels) {
        var fl = Speaker.FRONT_LEFT;
        var fr = Speaker.FRONT_RIGHT;
        var fc = Speaker.FRONT_CENTER;
        var lfe = Speaker.LOW_FREQUENCY;
        var bl = Speaker.BACK_LEFT;
        var br = Speaker.BACK_RIGHT;
        var sl = Speaker.SIDE_LEFT;
        var sr = Speaker.SIDE_RIGHT;
        return switch (channels) {
            case 1 -> ChannelLayout.MONO;
            case 2 -> ChannelLayout.STEREO;
            case 3 -> new ChannelLayout(List.of(fl, fc, fr));
            case 4 -> new ChannelLayout(List.of(fl, fr, bl, br));
            case 5 -> new ChannelLayout(List.of(fl, fc, fr, bl, br));
            case 6 -> new ChannelLayout(List.of(fl, fc, fr, bl, br, lfe));
            case 7 -> new ChannelLayout(List.of(fl, fc, fr, sl, sr, Speaker.BACK_CENTER, lfe));
            case 8 -> new ChannelLayout(List.of(fl, fc, fr, sl, sr, bl, br, lfe));
            default -> new ChannelLayout(Collections.nCopies(channels, Speaker.UNKNOWN));
        };
    }

    @Override
    Track track(int id, long duration, long skipSamples) {
        return AudioTrack.builder().id(id).codec(Codec.VORBIS).timeBase(Rational.timeBaseOf(sampleRate))
                .duration(duration)
                .codecConfig(XiphLacing.lace(headers))
                .sampleRate(sampleRate)
                .layout(layout(channels))
                .skipSamples(skipSamples)
                .build();
    }

    @Override
    Rational timeBase() {
        return Rational.timeBaseOf(sampleRate);
    }

    @Override
    boolean timestamped() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The next packet's duration is computed as if it were the first of the stream, half its
     * block size, because the size of the block before it is unknown.
     */
    @Override
    void reset() {
        previousBlock = 0;
    }

    @Override
    long packetDuration(byte[] packet) {
        if (packet.length == 0 || (packet[0] & 1) != 0 || modeLong == null) {
            return 0;
        }
        // at most 64 modes, so the mode number always fits in the 7 bits after the packet type bit
        var mode = ((packet[0] & 0xFF) >> 1) & ((1 << modeBits) - 1);
        if (mode >= modeLong.length) {
            return 0;
        }
        var block = modeLong[mode] ? longBlock : shortBlock;
        var duration = previousBlock == 0 ? block / 2 : previousBlock / 4 + block / 4;
        previousBlock = block;
        if (firstDuration < 0) {
            firstDuration = duration;
        }
        return duration;
    }

    @Override
    long defaultStart() {
        return -Math.max(0, firstDuration);
    }

    @Override
    long decoderDelay() {
        return Math.max(0, firstDuration);
    }
}

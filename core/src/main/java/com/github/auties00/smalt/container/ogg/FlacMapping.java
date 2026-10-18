package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Implements the Ogg FLAC mapping, version 1.0.
 *
 * <p>The first packet holds {@code 0x7F "FLAC"}, the mapping version, the number of header
 * packets that follow (zero when unknown), the {@code fLaC} marker and the {@code STREAMINFO}
 * block; each following header packet is one metadata block, and every data packet is one FLAC
 * frame. The track's codec configuration is {@code fLaC} followed by {@code STREAMINFO} and the
 * other metadata blocks except {@code PADDING} and {@code PICTURE}, with the last-block flag set
 * on the final block. Granule positions count samples; packet durations are the block sizes coded
 * in the frame headers.
 */
final class FlacMapping extends OggMapping {
    /**
     * The metadata block type of {@code PADDING}.
     */
    private static final int PADDING = 1;

    /**
     * The metadata block type of {@code VORBIS_COMMENT}.
     */
    private static final int VORBIS_COMMENT = 4;

    /**
     * The metadata block type of {@code PICTURE}.
     */
    private static final int PICTURE = 6;

    /**
     * The metadata blocks kept for the codec configuration, {@code STREAMINFO} first.
     */
    private final List<byte[]> blocks = new ArrayList<>();

    /**
     * The number of header packets after the first, or {@code -1} when the stream does not say.
     */
    private int declaredHeaders;

    /**
     * Whether a metadata block with the last-block flag was seen.
     */
    private boolean lastBlockSeen;

    /**
     * The sampling rate.
     */
    private int sampleRate;

    /**
     * The channel count.
     */
    private int channels;

    /**
     * The bits per sample.
     */
    private int bitsPerSample;

    @Override
    boolean isHeader(int index, byte[] packet) {
        if (index == 0) {
            return true;
        }
        if (declaredHeaders >= 0) {
            return index <= declaredHeaders;
        }
        return !lastBlockSeen && packet.length >= 4 && (packet[0] & 0xFF) != 0xFF && (packet[0] & 0x7F) != 0x7F;
    }

    @Override
    int headerCount() {
        return declaredHeaders < 0 ? Integer.MAX_VALUE : declaredHeaders + 1;
    }

    @Override
    void header(int index, byte[] packet, MediaLimits limits, Map<String, String> metadata) {
        if (index == 0) {
            if (packet.length < 51 || (packet[5] & 0xFF) != 1 || !startsWith(packet, 9, "fLaC")
                    || (packet[13] & 0x7F) != 0 || blockLength(packet, 13) != 34) {
                throw new MediaException.Malformed("invalid Ogg FLAC identification packet");
            }
            var count = (packet[7] & 0xFF) << 8 | (packet[8] & 0xFF);
            declaredHeaders = count == 0 ? -1 : count;
            var info = 17;
            sampleRate = (packet[info + 10] & 0xFF) << 12 | (packet[info + 11] & 0xFF) << 4 | (packet[info + 12] & 0xFF) >> 4;
            channels = ((packet[info + 12] >> 1) & 7) + 1;
            bitsPerSample = ((packet[info + 12] & 1) << 4 | (packet[info + 13] & 0xFF) >> 4) + 1;
            if (sampleRate == 0) {
                throw new MediaException.Malformed("Ogg FLAC STREAMINFO without a sample rate");
            }
            LimitChecks.checkChannels(limits, channels);
            lastBlockSeen = (packet[13] & 0x80) != 0;
            var streamInfo = new byte[38];
            System.arraycopy(packet, 13, streamInfo, 0, 38);
            blocks.add(streamInfo);
            return;
        }
        if (packet.length < 4 || blockLength(packet, 0) != packet.length - 4) {
            throw new MediaException.Malformed("invalid Ogg FLAC metadata block");
        }
        var type = packet[0] & 0x7F;
        lastBlockSeen |= (packet[0] & 0x80) != 0;
        if (type == VORBIS_COMMENT && metadata != null) {
            try {
                Annotations.readVorbisComments(packet, 4, packet.length - 4, metadata, annotations);
            } catch (MediaException.Malformed ignored) {
                // comments are optional; a damaged comment block does not prevent decoding
            }
        }
        if (type != PADDING && type != PICTURE && type != 0x7F) {
            LimitChecks.checkAllocation(limits, packet.length);
            blocks.add(packet);
        }
    }

    /**
     * Returns the body length coded in a metadata block header.
     *
     * @param data the bytes
     * @param offset the index of the block header
     * @return the 24-bit length
     */
    private static int blockLength(byte[] data, int offset) {
        return (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8 | (data[offset + 3] & 0xFF);
    }

    @Override
    Track track(int id, long duration, long skipSamples) {
        var config = new ByteArrayOutputStream();
        config.writeBytes(new byte[]{'f', 'L', 'a', 'C'});
        for (var i = 0; i < blocks.size(); i++) {
            var block = blocks.get(i).clone();
            block[0] = (byte) ((block[0] & 0x7F) | (i == blocks.size() - 1 ? 0x80 : 0));
            config.writeBytes(block);
        }
        return AudioTrack.builder().id(id).codec(Codec.FLAC).timeBase(Rational.timeBaseOf(sampleRate))
                .duration(duration)
                .codecConfig(config.toByteArray())
                .sampleRate(sampleRate)
                .layout(ChannelLayout.defaultFor(channels))
                .bitsPerSample(bitsPerSample)
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

    @Override
    long packetDuration(byte[] packet) {
        return blockSize(packet);
    }

    /**
     * Returns the block size coded in a FLAC frame header (RFC 9639 section 9.1.1).
     *
     * @param frame the bytes of a FLAC frame, starting with its header
     * @return the number of samples per channel, or {@code 0} when the header is invalid
     */
    static int blockSize(byte[] frame) {
        if (frame.length < 5 || (frame[0] & 0xFF) != 0xFF || (frame[1] & 0xFE) != 0xF8) {
            return 0;
        }
        var code = (frame[2] & 0xFF) >> 4;
        return switch (code) {
            case 0 -> 0;
            case 1 -> 192;
            case 2, 3, 4, 5 -> 576 << (code - 2);
            case 6, 7 -> {
                var first = frame[4] & 0xFF;
                var length = first < 0x80 ? 1 : Integer.numberOfLeadingZeros(~first << 24);
                if (length == 1 && first >= 0x80 || length > 7) {
                    yield 0;
                }
                var at = 4 + length;
                if (code == 6) {
                    yield at < frame.length ? (frame[at] & 0xFF) + 1 : 0;
                }
                yield at + 1 < frame.length ? ((frame[at] & 0xFF) << 8 | (frame[at + 1] & 0xFF)) + 1 : 0;
            }
            default -> 256 << (code - 8);
        };
    }
}

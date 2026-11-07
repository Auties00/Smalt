package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;
import java.util.Set;

/**
 * Turns the linear PCM audio substreams of DVD-Video ({@code sub_stream_id 0xA0} to {@code 0xA7} of private stream
 * 1) into packets of interleaved big-endian PCM samples.
 *
 * <p>Each PES payload, after the substream identifier, holds a six-byte header and the samples:
 * {@snippet :
 * number_of_frame_headers (8), first_access_unit_pointer (16),
 * audio_emphasis (1), audio_mute (1), reserved (1), audio_frame_number (5),
 * quantization_word_length (2), audio_sampling_frequency (2), reserved (1), number_of_audio_channels_minus1 (3),
 * dynamic_range_control (8)
 * }
 * The word length is 16, 20 or 24 bits, the rate 48 or 96 kHz (and, outside DVD-Video, 44.1 or 32 kHz), with one to
 * eight channels. 16-bit samples are big-endian words, channel after channel. 20-bit and 24-bit samples come in
 * chunks of four interleaved samples (two for one channel): the 16 most significant bits of each sample of the
 * chunk, then their low bits in the same order, four bits per sample (two per byte, the first sample in the high
 * nibble) at 20 bits and a byte per sample at 24 bits; a sample group is the shortest run of whole sample frames
 * that is whole chunks. The packets are {@link Codec#PCM_S16BE} or
 * {@link Codec#PCM_S24BE} (20-bit samples occupy three bytes, their low four bits zero, and the track declares 20
 * {@linkplain AudioTrack#bitsPerSample() bits per sample}), in the default layout of the channel count.
 *
 * <p>A sample group cut by the end of a PES packet completes in the next one, so every PES packet becomes one packet
 * of the whole groups it completes. A read that starts inside the stream (after a seek) or that follows a damaged PES
 * packet starts at the audio frame that {@code first_access_unit_pointer} locates, a PES packet that starts none being
 * skipped. The PES presentation time stamps the sample that
 * {@code first_access_unit_pointer} locates (counted from the last byte of the pointer); timestamps count samples
 * of the track's rate and follow the PES times as for the other audio streams. The format is taken from the first
 * header; a later PES packet whose header declares another format is dropped. Every packet is a key frame.
 * Instances are not thread-safe.
 */
final class DvdLpcmParser extends ElementaryParser {
    /**
     * The length of the header after the substream identifier.
     */
    static final int HEADER = 6;

    /**
     * The flags of an intact packet.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

    /**
     * The flags of a packet a damaged PES packet contributed to.
     */
    private static final Set<PacketFlag> KEY_CORRUPT = Set.of(PacketFlag.KEY, PacketFlag.CORRUPT);

    /**
     * The timestamps of the packets.
     */
    private final SampleClock samples = new SampleClock();

    /**
     * The format byte the track was configured from, without its reserved bit, or {@code -1}.
     */
    private int format = -1;

    /**
     * The bytes of a sample group the previous PES packet cut.
     */
    private byte[] carry = new byte[0];

    /**
     * The number of bytes in {@link #carry}.
     */
    private int carried;

    /**
     * Whether a damaged PES packet contributed to the carried bytes.
     */
    private boolean carriedCorrupt;

    /**
     * Whether the bytes read so far are known to end where {@link #carried} says, so that the next PES packet
     * continues them; until then a PES packet is read from the audio frame its pointer locates.
     */
    private boolean aligned;

    /**
     * Constructs a parser.
     *
     * @param entry the stream entry
     * @param limits the resource bounds
     */
    DvdLpcmParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    @Override
    ElementaryParser fresh() {
        var parser = new DvdLpcmParser(entry, limits);
        parser.track = track;
        parser.format = format;
        parser.samples.inherit(samples);
        return parser;
    }

    /**
     * Returns the sampling rate of an {@code audio_sampling_frequency} code.
     *
     * @param code the code, from 0 to 3
     * @return the rate in hertz
     */
    static int rate(int code) {
        return switch (code) {
            case 0 -> 48_000;
            case 1 -> 96_000;
            case 2 -> 44_100;
            default -> 32_000;
        };
    }

    /**
     * Returns the word length of a {@code quantization_word_length} code.
     *
     * @param code the code, from 0 to 3
     * @return the bits per sample, or 0 for the reserved code 3
     */
    static int bits(int code) {
        return switch (code) {
            case 0 -> 16;
            case 1 -> 20;
            case 2 -> 24;
            default -> 0;
        };
    }

    /**
     * Returns the number of interleaved samples whose low bits follow their high words: two for one channel, four
     * otherwise.
     *
     * @param channels the channel count
     * @return the samples of a chunk
     */
    static int chunkSamples(int channels) {
        return channels == 1 ? 2 : 4;
    }

    /**
     * Returns the number of sample frames of a sample group, the smallest run of whole frames that the chunks of
     * 20-bit and 24-bit samples tile: one frame at 16 bits.
     *
     * @param bits the word length
     * @param channels the channel count
     * @return the frames of a group
     */
    static int groupFrames(int bits, int channels) {
        if (bits == 16) {
            return 1;
        }
        var chunk = chunkSamples(channels);
        var frames = 1;
        while (frames * channels % chunk != 0) {
            frames++;
        }
        return frames;
    }

    /**
     * Returns the size of a sample group.
     *
     * @param bits the word length
     * @param channels the channel count
     * @return the size in bytes
     */
    static int groupBytes(int bits, int channels) {
        var samples = groupFrames(bits, channels) * channels;
        return switch (bits) {
            case 16 -> 2 * samples;
            case 20 -> samples * 5 / 2;
            default -> 3 * samples;
        };
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
        if (pes.length() < HEADER) {
            return;
        }
        var data = pes.data();
        var offset = pes.offset();
        var key = data[offset + 4] & 0xF7;
        if (format < 0) {
            if (!configure(key)) {
                return;
            }
            format = key;
        } else if (key != format) {
            return;
        }
        var audio = (AudioTrack) track;
        var channels = audio.layout().orElseThrow().channels();
        var bits = audio.bitsPerSample();
        var group = groupBytes(bits, channels);
        var framesPerGroup = groupFrames(bits, channels);
        var start = offset + HEADER;
        var length = pes.length() - HEADER;
        var pointer = (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF);
        var lead = pointer >= HEADER - 2 ? pointer - (HEADER - 2) : -1;
        if (pes.corrupt()) {
            aligned = false;
        }
        if (!aligned) {
            if (lead < 0 || lead >= length) {
                return;
            }
            carried = 0;
            carriedCorrupt = false;
            start += lead;
            length -= lead;
            lead = 0;
            aligned = true;
        }
        var anchor = TsClock.NONE;
        if (pes.pts() != TsClock.NONE) {
            var before = (long) (carried + Math.max(0, Math.min(length, lead))) / group * framesPerGroup;
            anchor = clock.ticks(trackId(), pes.pts(), true) - Math.floorDiv(before * 90_000, audio.sampleRate());
        }
        var available = carried + length;
        var groups = available / group;
        var corrupt = pes.corrupt() || carriedCorrupt;
        if (groups == 0) {
            keep(data, start, length, pes.corrupt());
            return;
        }
        var frames = groups * framesPerGroup;
        var width = bits == 16 ? 2 : 3;
        var output = new byte[frames * channels * width];
        var consumed = 0;
        var written = 0;
        if (carried > 0) {
            consumed = group - carried;
            keep(data, start, consumed, false);
            convert(carry, 0, 1, bits, channels, output, 0);
            written = framesPerGroup * channels * width;
            groups--;
        }
        convert(data, start + consumed, groups, bits, channels, output, written);
        consumed += groups * group;
        carried = 0;
        carriedCorrupt = false;
        keep(data, start + consumed, length - consumed, pes.corrupt());
        var timestamp = samples.stamp(anchor, clock.origin(), audio.sampleRate(), frames);
        if (timestamp == TsClock.NONE) {
            return;
        }
        sink.accept(new Packet(trackId(), output, timestamp, timestamp, frames, corrupt ? KEY_CORRUPT : KEY,
                PacketSideData.NONE), pes.fileOffset());
    }

    /**
     * Appends the bytes of an incomplete sample group to the carried bytes.
     *
     * @param data the bytes
     * @param start the index of the first byte
     * @param length the number of bytes, less than one group with the bytes already carried
     * @param corrupt whether the PES packet they come from was damaged
     */
    private void keep(byte[] data, int start, int length, boolean corrupt) {
        if (length <= 0) {
            return;
        }
        if (carry.length < carried + length) {
            carry = Arrays.copyOf(carry, carried + length);
        }
        System.arraycopy(data, start, carry, carried, length);
        carried += length;
        carriedCorrupt |= corrupt;
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
        carried = 0;
    }

    /**
     * Describes the stream from its first header.
     *
     * @param format the fifth header byte, without its reserved bit
     * @return {@code false} when the header declares the reserved word length
     */
    private boolean configure(int format) {
        var bits = bits(format >> 6);
        if (bits == 0) {
            return false;
        }
        var rate = rate((format >> 4) & 0x03);
        var channels = (format & 0x07) + 1;
        LimitChecks.checkChannels(limits, channels);
        track = AudioTrack.builder()
                .id(trackId())
                .codec(bits == 16 ? Codec.PCM_S16BE : Codec.PCM_S24BE)
                .timeBase(Rational.timeBaseOf(rate))
                .language(entry.language())
                .sampleRate(rate)
                .layout(ChannelLayout.defaultFor(channels))
                .bitsPerSample(bits)
                .build();
        return true;
    }

    /**
     * Converts sample groups to interleaved big-endian samples of two or three bytes.
     *
     * @param data the groups
     * @param offset the index of the first group
     * @param groups the number of groups
     * @param bits the word length: 16, 20 or 24
     * @param channels the channel count
     * @param output receives the samples, {@code groups * groupFrames(bits, channels) * channels} samples of
     * {@code bits == 16 ? 2 : 3} bytes
     * @param at the index of the first sample byte written
     */
    static void convert(byte[] data, int offset, int groups, int bits, int channels, byte[] output, int at) {
        var samples = groups * groupFrames(bits, channels) * channels;
        if (bits == 16) {
            System.arraycopy(data, offset, output, at, 2 * samples);
            return;
        }
        var chunk = chunkSamples(channels);
        var chunkBytes = bits == 24 ? 3 * chunk : chunk * 5 / 2;
        var out = at;
        for (var base = offset; base < offset + samples / chunk * chunkBytes; base += chunkBytes) {
            var low = base + 2 * chunk;
            for (var sample = 0; sample < chunk; sample++) {
                output[out] = data[base + 2 * sample];
                output[out + 1] = data[base + 2 * sample + 1];
                var nibbles = data[low + (sample >> 1)];
                output[out + 2] = bits == 24 ? data[low + sample]
                        : (byte) ((sample & 1) == 0 ? nibbles & 0xF0 : (nibbles & 0x0F) << 4);
                out += 3;
            }
        }
    }

}

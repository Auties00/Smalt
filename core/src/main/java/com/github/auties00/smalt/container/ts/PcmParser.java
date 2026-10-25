package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;

import java.util.List;
import java.util.Set;

/**
 * Turns the PES packets of an uncompressed audio stream into packets of interleaved PCM samples: Blu-ray and
 * AVCHD linear PCM ({@code stream_type 0x80} in an {@code "HDMV"} program) and SMPTE ST 302 AES3 data
 * ({@code stream_type 0x06} with the {@code "BSSD"} registration).
 *
 * <p>Every PES packet starts with a four-byte header and becomes one packet of whole sample frames.
 * <ul>
 *   <li>Blu-ray linear PCM: the header holds the payload size, the channel assignment, the sampling frequency
 *       (48, 96 or 192 kHz) and the word size (16, 20 or 24 bits). Samples are big-endian, in words of two or
 *       three bytes, with an odd channel count padded by one channel. The packets are
 *       {@link Codec#PCM_S16BE} or {@link Codec#PCM_S24BE} (20-bit words occupy three bytes, their low four bits
 *       zero, and the track declares 20 {@linkplain AudioTrack#bitsPerSample() bits per sample}), without the
 *       padding channel, with the channels reordered to the track's layout (front left, front right, front
 *       center, low frequency, back, side).</li>
 *   <li>SMPTE ST 302: the header holds the payload size, the channel count (2, 4, 6 or 8) and the word size
 *       (16, 20 or 24 bits); the rate is 48 kHz. Each word is sent least significant bit first and followed by
 *       its validity, user, channel status and framing bits, packed without byte alignment (ST 302 clause 5.9).
 *       The packets are {@link Codec#PCM_S16LE} for 16-bit words and {@link Codec#PCM_S24LE} otherwise
 *       (20-bit words shifted up by four bits, with 20 bits per sample declared).</li>
 * </ul>
 * The format is taken from the first valid header; a later PES packet whose header declares another format is
 * dropped. Timestamps count samples of the track's rate and follow the PES times as for the other audio
 * streams. Every packet is a key frame. Instances are not thread-safe.
 */
final class PcmParser extends ElementaryParser {
    /**
     * The Blu-ray channel assignments, by {@code channel_assignment}: for each, the stream channel feeding each
     * output channel, in the order of {@link #LPCM_LAYOUTS}; {@code null} for a reserved value.
     */
    private static final int[][] LPCM_ORDER = {
            null,
            {0},
            null,
            {0, 1},
            {0, 1, 2},
            {0, 1, 2},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            {0, 1, 2, 3, 4},
            {0, 1, 2, 5, 3, 4},
            {0, 1, 2, 4, 5, 3, 6},
            {0, 1, 2, 7, 4, 5, 3, 6}
    };

    /**
     * The layouts of the Blu-ray channel assignments, in output order.
     */
    private static final ChannelLayout[] LPCM_LAYOUTS = {
            null,
            ChannelLayout.MONO,
            null,
            ChannelLayout.STEREO,
            layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER),
            layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.BACK_CENTER),
            layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.BACK_CENTER),
            layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT),
            layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.SIDE_LEFT,
                    Speaker.SIDE_RIGHT),
            layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT),
            layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.BACK_LEFT,
                    Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT),
            ChannelLayout.SURROUND_7_1
    };

    /**
     * The flags of an intact packet.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

    /**
     * The flags of a packet a lost transport packet may have damaged.
     */
    private static final Set<PacketFlag> KEY_CORRUPT = Set.of(PacketFlag.KEY, PacketFlag.CORRUPT);

    /**
     * Whether the stream is SMPTE ST 302 rather than Blu-ray linear PCM.
     */
    private final boolean aes3;

    /**
     * The timestamps of the packets.
     */
    private final SampleClock samples = new SampleClock();

    /**
     * The format the track was configured from: the third and fourth header bytes, or {@code -1}.
     */
    private int format = -1;

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     * @param aes3 {@code true} for SMPTE ST 302, {@code false} for Blu-ray linear PCM
     */
    PcmParser(Psi.StreamEntry entry, MediaLimits limits, boolean aes3) {
        super(entry, limits);
        this.aes3 = aes3;
    }

    /**
     * Returns the Blu-ray linear PCM stream slot of each channel of a channel assignment's layout.
     *
     * @param assignment the {@code channel_assignment}, 1 or 3 to 11
     * @return the slots, in layout order; shared, not to be modified
     */
    static int[] lpcmOrder(int assignment) {
        return LPCM_ORDER[assignment];
    }

    /**
     * Returns a layout of speakers.
     *
     * @param speakers the speakers, in channel order
     * @return the layout
     */
    private static ChannelLayout layout(Speaker... speakers) {
        return new ChannelLayout(List.of(speakers));
    }

    @Override
    ElementaryParser fresh() {
        var parser = new PcmParser(entry, limits, aes3);
        parser.track = track;
        parser.format = format;
        parser.samples.inherit(samples);
        return parser;
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
        if (pes.length() < 4) {
            return;
        }
        var data = pes.data();
        var offset = pes.offset();
        var header = (data[offset + 2] & 0xFF) << 8 | (data[offset + 3] & 0xFF);
        var key = aes3 ? header & 0xFFF0 : header & 0xFFC0;
        if (format < 0) {
            if (!configure(header)) {
                return;
            }
            format = key;
        } else if (key != format) {
            return;
        }
        var anchor = pes.pts() == TsClock.NONE ? TsClock.NONE : clock.ticks(trackId(), pes.pts(), true);
        var audio = (AudioTrack) track;
        var payload = aes3 ? aes3Samples(data, offset + 4, pes.length() - 4, audio)
                : lpcmSamples(data, offset + 4, pes.length() - 4, header, audio);
        if (payload == null) {
            return;
        }
        var channels = audio.layout().orElseThrow().channels();
        var frames = payload.length / (channels * (audio.codec() == Codec.PCM_S16BE || audio.codec() == Codec.PCM_S16LE ? 2 : 3));
        var timestamp = samples.stamp(anchor, clock.origin(), audio.sampleRate(), frames);
        if (timestamp == TsClock.NONE) {
            return;
        }
        sink.accept(new Packet(trackId(), payload, timestamp, timestamp, frames, pes.corrupt() ? KEY_CORRUPT : KEY,
                PacketSideData.NONE), pes.fileOffset());
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
    }

    /**
     * Describes the stream from its first header.
     *
     * @param header the third and fourth header bytes
     * @return {@code false} when the header declares a reserved value
     */
    private boolean configure(int header) {
        int rate;
        int bits;
        ChannelLayout layout;
        if (aes3) {
            var channels = 2 * (((header >> 14) & 0x03) + 1);
            bits = switch ((header >> 4) & 0x03) {
                case 0 -> 16;
                case 1 -> 20;
                case 2 -> 24;
                default -> 0;
            };
            rate = 48_000;
            layout = ChannelLayout.defaultFor(channels);
        } else {
            var assignment = (header >> 12) & 0x0F;
            rate = switch ((header >> 8) & 0x0F) {
                case 1 -> 48_000;
                case 4 -> 96_000;
                case 5 -> 192_000;
                default -> 0;
            };
            bits = switch ((header >> 6) & 0x03) {
                case 1 -> 16;
                case 2 -> 20;
                case 3 -> 24;
                default -> 0;
            };
            layout = assignment < LPCM_LAYOUTS.length ? LPCM_LAYOUTS[assignment] : null;
        }
        if (rate == 0 || bits == 0 || layout == null) {
            return false;
        }
        var codec = bits == 16 ? (aes3 ? Codec.PCM_S16LE : Codec.PCM_S16BE) : (aes3 ? Codec.PCM_S24LE : Codec.PCM_S24BE);
        track = AudioTrack.builder()
                .id(trackId())
                .codec(codec)
                .timeBase(Rational.timeBaseOf(rate))
                .language(entry.language())
                .sampleRate(rate)
                .layout(layout)
                .bitsPerSample(bits)
                .build();
        return true;
    }

    /**
     * Converts the payload of a Blu-ray linear PCM packet.
     *
     * @param data the bytes
     * @param offset the index of the first sample
     * @param length the payload length
     * @param header the third and fourth header bytes
     * @param audio the track
     * @return the interleaved samples, or {@code null} when the payload holds no whole frame
     */
    private static byte[] lpcmSamples(byte[] data, int offset, int length, int header, AudioTrack audio) {
        var order = LPCM_ORDER[(header >> 12) & 0x0F];
        var channels = order.length;
        var stored = channels + (channels & 1);
        var width = audio.codec() == Codec.PCM_S16BE ? 2 : 3;
        var frames = length / (stored * width);
        if (frames == 0) {
            return null;
        }
        var output = new byte[frames * channels * width];
        var in = offset;
        var out = 0;
        for (var frame = 0; frame < frames; frame++) {
            for (var channel = 0; channel < channels; channel++) {
                System.arraycopy(data, in + order[channel] * width, output, out, width);
                out += width;
            }
            in += stored * width;
        }
        return output;
    }

    /**
     * Converts the payload of a SMPTE ST 302 packet.
     *
     * <p>A pair of words takes 5 bytes at 16 bits, 6 at 20 bits and 7 at 24 bits. Each word is read bit by bit,
     * the first bit of a byte being its most significant one and the first bit of a word its least significant
     * one, and the four bits that follow it are skipped.
     *
     * @param data the bytes
     * @param offset the index of the first word
     * @param length the payload length
     * @param audio the track
     * @return the interleaved samples, or {@code null} when the payload holds no whole frame
     */
    private static byte[] aes3Samples(byte[] data, int offset, int length, AudioTrack audio) {
        var bits = audio.bitsPerSample();
        var channels = audio.layout().orElseThrow().channels();
        var pairBytes = (bits + 4) * 2 / 8;
        var frameBytes = pairBytes * channels / 2;
        var frames = length / frameBytes;
        if (frames == 0) {
            return null;
        }
        var width = bits == 16 ? 2 : 3;
        var shift = bits == 20 ? 4 : 0;
        var output = new byte[frames * channels * width];
        var out = 0;
        var bit = (long) offset * 8;
        var words = frames * channels;
        for (var word = 0; word < words; word++) {
            var value = 0;
            for (var index = 0; index < bits; index++, bit++) {
                value |= ((data[(int) (bit >>> 3)] >> (7 - (int) (bit & 7))) & 1) << index;
            }
            bit += 4;
            value <<= shift;
            output[out] = (byte) value;
            output[out + 1] = (byte) (value >> 8);
            if (width == 3) {
                output[out + 2] = (byte) (value >> 16);
            }
            out += width;
        }
        return output;
    }
}

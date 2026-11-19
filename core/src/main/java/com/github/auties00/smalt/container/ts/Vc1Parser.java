package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * Splits a VC-1 advanced profile stream ({@code stream_type 0xEA}, SMPTE RP 227) into one packet per PES packet,
 * the frame the writer put in it. A PES packet whose payload starts with a field or slice start code, or with no
 * start code at all, continues the frame of the packet before it.
 *
 * <p>The track's configuration is the sequence header and entry point (with any user data between them) that the
 * first key frame carries in band, and its size, frame rate and sample aspect ratio come from that sequence header
 * (SMPTE ST 421 section 6.1): the maximum coded size and the display extension. A packet is a key frame when it
 * holds an entry point, which encoders place before every random access point; one whose headers differ from the
 * configuration in force carries them as its {@link PacketSideData#newCodecConfig()}. Packets before the first key
 * frame with a sequence header are dropped. Timestamps are the PES ones on the 90 kHz timeline (a frame whose PES
 * carries none follows the previous one by its duration, presented when decoded), and a packet lasts until the next
 * one's decoding time. Instances are not thread-safe.
 */
final class Vc1Parser extends ElementaryParser {
    /**
     * The start code suffix of a sequence header.
     */
    private static final int SEQUENCE = 0x0F;

    /**
     * The start code suffix of an entry-point header.
     */
    private static final int ENTRY_POINT = 0x0E;

    /**
     * The start code suffix of a frame.
     */
    private static final int FRAME = 0x0D;

    /**
     * The start code suffix of a second field.
     */
    private static final int FIELD = 0x0C;

    /**
     * The start code suffix of a slice.
     */
    private static final int SLICE = 0x0B;

    /**
     * The sample aspect ratios of {@code ASPECT_RATIO} 1 to 13 (SMPTE ST 421 Table 7), as numerator and denominator
     * pairs.
     */
    private static final int[][] ASPECT_RATIOS = {{1, 1}, {12, 11}, {10, 11}, {16, 11}, {40, 33}, {24, 11},
            {20, 11}, {32, 11}, {80, 33}, {18, 11}, {15, 11}, {64, 33}, {160, 99}};

    /**
     * The frame rates of {@code FRAMERATENR} 1 to 7 (SMPTE ST 421 Table 8).
     */
    private static final int[] FRAME_RATES = {24, 25, 30, 50, 60, 48, 72};

    /**
     * The configuration last reported downstream, or {@code null}.
     */
    private byte[] established;

    /**
     * The packet waiting for its successor's decoding time, or {@code null}.
     */
    private Packet held;

    /**
     * The file offset of {@link #held}.
     */
    private long heldOffset;

    /**
     * The duration of the last emitted packet, in 90 kHz ticks.
     */
    private long lastDuration;

    /**
     * Whether a key frame was met, after which every packet is emitted.
     */
    private boolean seenKey;

    /**
     * Whether packets before the first key frame are emitted too.
     */
    private boolean keepLeading;

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    Vc1Parser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    @Override
    ElementaryParser fresh() {
        var parser = new Vc1Parser(entry, limits);
        parser.track = track;
        parser.established = track == null ? null : track.codecConfig().orElse(null);
        return parser;
    }

    @Override
    void keepLeadingUnits() {
        keepLeading = true;
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
        var dts = pes.dts() == TsClock.NONE ? TsClock.NONE : clock.ticks(trackId(), pes.dts(), true);
        var pts = pes.pts() == TsClock.NONE ? TsClock.NONE : clock.ticks(trackId(), pes.pts(), dts == TsClock.NONE);
        if (dts == TsClock.NONE) {
            dts = pts;
        }
        if (pes.length() == 0) {
            return;
        }
        var data = pes.data();
        var start = pes.offset();
        var end = start + pes.length();
        var first = startCode(data, start, end);
        var code = first < 0 ? -1 : data[first + 3] & 0xFF;
        if (first < 0 || !allZero(data, start, first) || code == FIELD || code == SLICE) {
            if (held != null) {
                var joined = Arrays.copyOf(held.data(), held.data().length + pes.length());
                System.arraycopy(data, start, joined, held.data().length, pes.length());
                held = held.withData(joined);
                if (pes.corrupt() && !held.flags().contains(PacketFlag.CORRUPT)) {
                    var flags = EnumSet.of(PacketFlag.CORRUPT);
                    flags.addAll(held.flags());
                    held = held.withFlags(flags);
                }
            }
            return;
        }
        var payload = Arrays.copyOfRange(data, start, end);
        var key = hasStartCode(payload, ENTRY_POINT);
        var headers = key ? config(payload) : null;
        byte[] newConfig = null;
        if (headers != null) {
            if (track == null) {
                configure(headers);
            } else if (!Arrays.equals(headers, established)) {
                newConfig = headers;
                established = headers;
            }
        }
        if (track == null || (!seenKey && !(key && established != null) && !keepLeading)) {
            return;
        }
        if (dts == TsClock.NONE) {
            if (held == null) {
                return;
            }
            dts = held.dts() + Math.max(1, lastDuration);
            pts = dts;
        }
        seenKey = true;
        var flags = EnumSet.noneOf(PacketFlag.class);
        if (key) {
            flags.add(PacketFlag.KEY);
        }
        if (pes.corrupt()) {
            flags.add(PacketFlag.CORRUPT);
        }
        var packet = new Packet(trackId(), payload, pts, dts, Packet.UNKNOWN_DURATION, flags,
                newConfig == null ? PacketSideData.NONE : PacketSideData.builder().newCodecConfig(newConfig).build());
        release(dts, sink);
        held = packet;
        heldOffset = pes.fileOffset();
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
        if (held != null) {
            sink.accept(held.withTiming(held.pts(), held.dts(), lastDuration > 0 ? lastDuration
                    : Packet.UNKNOWN_DURATION), heldOffset);
            held = null;
        }
    }

    /**
     * Passes the held packet on, lasting until a decoding time.
     *
     * @param dts the decoding time of the packet that follows it
     * @param sink receives the packet
     */
    private void release(long dts, UnitSink sink) {
        if (held == null) {
            return;
        }
        var duration = dts - held.dts();
        if (duration <= 0) {
            duration = lastDuration;
        }
        lastDuration = Math.max(0, duration);
        sink.accept(held.withTiming(held.pts(), held.dts(), lastDuration > 0 ? lastDuration
                : Packet.UNKNOWN_DURATION), heldOffset);
        held = null;
    }

    /**
     * Builds the track from the headers of the first key frame.
     *
     * @param headers the sequence header and entry point
     */
    private void configure(byte[] headers) {
        var builder = VideoTrack.builder()
                .id(trackId())
                .codec(Codec.VC1)
                .timeBase(NalParser.TIME_BASE)
                .codecConfig(headers)
                .language(entry.language());
        var sequence = sequence(headers);
        if (sequence != null) {
            LimitChecks.checkPicture(limits, sequence.width(), sequence.height());
            builder.width(sequence.width()).height(sequence.height());
            if (sequence.frameRate() != null) {
                builder.frameRate(sequence.frameRate());
            }
            if (sequence.aspect() != null) {
                builder.sampleAspectRatio(sequence.aspect());
            }
        }
        track = builder.build();
        established = headers;
    }

    /**
     * Describes an advanced profile sequence header.
     *
     * @param width the maximum coded width
     * @param height the maximum coded height
     * @param frameRate the frame rate of the display extension, or {@code null}
     * @param aspect the sample aspect ratio of the display extension, or {@code null}
     */
    record Sequence(int width, int height, Rational frameRate, Rational aspect) {
    }

    /**
     * Parses the first advanced profile sequence header of a configuration (SMPTE ST 421 Table 3).
     *
     * @param headers the configuration, start codes included, emulation prevention bytes in place
     * @return the description, or {@code null} when no advanced profile sequence header parses
     */
    static Sequence sequence(byte[] headers) {
        var at = findStartCode(headers, 0, headers.length, SEQUENCE);
        if (at < 0) {
            return null;
        }
        var end = startCode(headers, at + 4, headers.length);
        var body = unescape(headers, at + 4, end < 0 ? headers.length : end);
        var bits = new MsbBitReader(body, 0, body.length);
        if (bits.read(2) != 3) {
            return null;
        }
        bits.skip(3 + 2 + 3 + 5 + 1);
        var width = bits.read(12) * 2 + 2;
        var height = bits.read(12) * 2 + 2;
        bits.skip(6);
        Rational frameRate = null;
        Rational aspect = null;
        if (bits.readFlag()) {
            bits.skip(28);
            if (bits.readFlag()) {
                var code = bits.read(4);
                if (code == 15) {
                    aspect = new Rational(bits.read(8) + 1, bits.read(8) + 1).reduce();
                } else if (code >= 1 && code <= 13) {
                    aspect = new Rational(ASPECT_RATIOS[code - 1][0], ASPECT_RATIOS[code - 1][1]);
                }
            }
            if (bits.readFlag()) {
                if (bits.readFlag()) {
                    frameRate = new Rational(bits.read(16) + 1, 32).reduce();
                } else {
                    var numerator = bits.read(8);
                    var denominator = bits.read(4);
                    if (numerator >= 1 && numerator <= 7 && denominator >= 1 && denominator <= 2) {
                        frameRate = new Rational(FRAME_RATES[numerator - 1] * 1000L,
                                denominator == 1 ? 1000 : 1001).reduce();
                    }
                }
            }
        }
        if (bits.overrun()) {
            return null;
        }
        return new Sequence(width, height, frameRate, aspect);
    }

    /**
     * Returns the headers of a payload: from its first sequence header to the first frame, field or slice start
     * code after it.
     *
     * @param payload the PES payload
     * @return the headers, or {@code null} when the payload has no sequence header
     */
    static byte[] config(byte[] payload) {
        var at = findStartCode(payload, 0, payload.length, SEQUENCE);
        if (at < 0) {
            return null;
        }
        var position = startCode(payload, at + 4, payload.length);
        while (position >= 0) {
            var code = payload[position + 3] & 0xFF;
            if (code == FRAME || code == FIELD || code == SLICE) {
                break;
            }
            position = startCode(payload, position + 4, payload.length);
        }
        return Arrays.copyOfRange(payload, at, position < 0 ? payload.length : position);
    }

    /**
     * Returns whether a payload holds a start code.
     *
     * @param data the payload
     * @param code the start code suffix
     * @return {@code true} when found
     */
    private static boolean hasStartCode(byte[] data, int code) {
        return findStartCode(data, 0, data.length, code) >= 0;
    }

    /**
     * Returns the index of the first start code of a suffix in a range.
     *
     * @param data the bytes
     * @param from the first index
     * @param to the index after the last
     * @param code the suffix
     * @return the index, or -1
     */
    private static int findStartCode(byte[] data, int from, int to, int code) {
        var position = startCode(data, from, to);
        while (position >= 0 && (data[position + 3] & 0xFF) != code) {
            position = startCode(data, position + 4, to);
        }
        return position;
    }

    /**
     * Returns the index of the first {@code 00 00 01 xx} start code in a range.
     *
     * @param data the bytes
     * @param from the first index
     * @param to the index after the last
     * @return the index, or -1
     */
    private static int startCode(byte[] data, int from, int to) {
        for (var index = from; index + 3 < to; index++) {
            if (data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 1) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Returns whether a range holds only zero bytes.
     *
     * @param data the bytes
     * @param from the first index
     * @param to the index after the last
     * @return {@code true} when every byte is zero
     */
    private static boolean allZero(byte[] data, int from, int to) {
        for (var index = from; index < to; index++) {
            if (data[index] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes the emulation prevention bytes ({@code 00 00 03}) of a BDU payload.
     *
     * @param data the bytes
     * @param from the first index of the payload
     * @param to the index after its last
     * @return the unescaped payload
     */
    private static byte[] unescape(byte[] data, int from, int to) {
        var out = new byte[to - from];
        var length = 0;
        var zeros = 0;
        for (var index = from; index < to; index++) {
            var value = data[index];
            if (zeros >= 2 && value == 3 && index + 1 < to && (data[index + 1] & 0xFF) <= 3) {
                zeros = 0;
                continue;
            }
            out[length++] = value;
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return Arrays.copyOf(out, length);
    }
}

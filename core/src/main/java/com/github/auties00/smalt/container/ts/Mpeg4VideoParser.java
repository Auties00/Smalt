package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
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
 * Splits an MPEG-4 Part 2 video stream ({@code stream_type 0x10}, ISO/IEC 14496-2) into one packet per PES
 * packet, the picture or pictures the writer put in it.
 *
 * <p>The track's configuration is the visual object sequence, visual object and video object layer headers
 * that the first key frame carries in band (as the {@code DecoderSpecificInfo} of an {@code esds} box holds
 * them), and its size, frame rate and sample aspect ratio come from the video object layer header. A packet is
 * a key frame when its first video object plane is intra coded; one whose headers differ from the
 * configuration in force carries them as its {@link PacketSideData#newCodecConfig()}. Packets before the first
 * key frame with headers are dropped. Timestamps are the PES ones on the 90 kHz timeline, and a packet lasts
 * until the next one's decoding time. Instances are not thread-safe.
 */
final class Mpeg4VideoParser extends ElementaryParser {
    /**
     * The start code value of a video object plane.
     */
    private static final int VOP = 0xB6;

    /**
     * The sample aspect ratios of {@code aspect_ratio_info} 1 to 5 (ISO/IEC 14496-2 table 6-12), as numerator
     * and denominator pairs.
     */
    private static final int[][] ASPECT_RATIOS = {{1, 1}, {12, 11}, {10, 11}, {16, 11}, {40, 33}};

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
    Mpeg4VideoParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    @Override
    ElementaryParser fresh() {
        var parser = new Mpeg4VideoParser(entry, limits);
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
        var payload = Arrays.copyOfRange(pes.data(), pes.offset(), pes.offset() + pes.length());
        var key = startsIntra(payload);
        var headers = key ? CodecConfigs.mpeg4Config(payload) : null;
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
            sink.accept(held.withTiming(held.pts(), held.dts(), lastDuration > 0 ? lastDuration : Packet.UNKNOWN_DURATION),
                    heldOffset);
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
        sink.accept(held.withTiming(held.pts(), held.dts(), lastDuration > 0 ? lastDuration : Packet.UNKNOWN_DURATION),
                heldOffset);
        held = null;
    }

    /**
     * Returns whether the first video object plane of a payload is intra coded.
     *
     * @param data the payload
     * @return {@code true} when its {@code vop_coding_type} is 0
     */
    static boolean startsIntra(byte[] data) {
        for (var index = 0; index + 4 < data.length; index++) {
            if (data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 1 && (data[index + 3] & 0xFF) == VOP) {
                return (data[index + 4] & 0xC0) == 0;
            }
        }
        return false;
    }

    /**
     * Builds the track from the headers of the first key frame.
     *
     * @param headers the visual object sequence, visual object and video object layer headers
     */
    private void configure(byte[] headers) {
        var builder = VideoTrack.builder()
                .id(trackId())
                .codec(Codec.MPEG4_PART2)
                .timeBase(NalParser.TIME_BASE)
                .codecConfig(headers)
                .language(entry.language());
        var layer = layer(headers);
        if (layer != null) {
            LimitChecks.checkPicture(limits, layer.width(), layer.height());
            builder.width(layer.width()).height(layer.height());
            if (layer.frameRate() != null) {
                builder.frameRate(layer.frameRate());
            }
            if (layer.aspect() != null) {
                builder.sampleAspectRatio(layer.aspect());
            }
        }
        track = builder.build();
        established = headers;
    }

    /**
     * Describes a video object layer header.
     *
     * @param width {@code video_object_layer_width}
     * @param height {@code video_object_layer_height}
     * @param frameRate the frame rate of a fixed-rate layer, or {@code null}
     * @param aspect the sample aspect ratio, or {@code null} when unspecified
     */
    record Layer(int width, int height, Rational frameRate, Rational aspect) {
    }

    /**
     * Parses the first rectangular video object layer header among decoder headers (ISO/IEC 14496-2 clause
     * 6.2.3).
     *
     * @param headers the decoder configuration, start codes included
     * @return the description, or {@code null} when no rectangular layer header parses
     */
    static Layer layer(byte[] headers) {
        for (var index = 0; index + 4 < headers.length; index++) {
            if (headers[index] != 0 || headers[index + 1] != 0 || headers[index + 2] != 1
                    || (headers[index + 3] & 0xF0) != 0x20) {
                continue;
            }
            var bits = new MsbBitReader(headers, index + 4, headers.length - index - 4);
            bits.skip(1);
            bits.skip(8);
            var verid = 1;
            if (bits.readFlag()) {
                verid = bits.read(4);
                bits.skip(3);
            }
            Rational aspect = null;
            var aspectInfo = bits.read(4);
            if (aspectInfo == 15) {
                var parWidth = bits.read(8);
                var parHeight = bits.read(8);
                if (parWidth > 0 && parHeight > 0) {
                    aspect = new Rational(parWidth, parHeight).reduce();
                }
            } else if (aspectInfo >= 1 && aspectInfo <= 5) {
                aspect = new Rational(ASPECT_RATIOS[aspectInfo - 1][0], ASPECT_RATIOS[aspectInfo - 1][1]);
            }
            if (bits.readFlag()) {
                bits.skip(3);
                if (bits.readFlag()) {
                    bits.skip(79);
                }
            }
            var shape = bits.read(2);
            if (shape == 3 && verid != 1) {
                bits.skip(4);
            }
            bits.skip(1);
            var resolution = bits.read(16);
            bits.skip(1);
            Rational frameRate = null;
            if (bits.readFlag()) {
                var length = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(0, resolution - 1)));
                var increment = bits.read(length);
                if (increment > 0 && resolution > 0) {
                    frameRate = new Rational(resolution, increment).reduce();
                }
            }
            if (shape != 0) {
                return null;
            }
            bits.skip(1);
            var width = bits.read(13);
            bits.skip(1);
            var height = bits.read(13);
            if (bits.overrun() || width == 0 || height == 0) {
                return null;
            }
            return new Layer(width, height, frameRate, aspect);
        }
        return null;
    }
}

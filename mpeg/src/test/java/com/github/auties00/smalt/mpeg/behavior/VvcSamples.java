package com.github.auties00.smalt.mpeg.behavior;

import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vvc.VvcDecoder;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Packages the VVenC Annex B vectors of the VVC tests into an MP4 file: one {@code vvc1} sample per access unit,
 * presentation times in output order, and a {@code vvcC} record of the parameter sets before the first slice.
 */
final class VvcSamples {
    /**
     * The duration of a picture, in the 90 kHz time base of the track.
     */
    private static final int FRAME = 3600;

    private VvcSamples() {
    }

    /**
     * Returns an MP4 file holding an Annex B stream as one {@code vvc1} track.
     *
     * @param stream the stream
     * @param width the picture width
     * @param height the picture height
     */
    static byte[] mp4(byte[] stream, int width, int height) {
        var nals = nalUnits(stream);
        var sets = new ArrayList<byte[]>();
        for (var nal : nals) {
            if (type(nal) <= 11) {
                break;
            }
            if (type(nal) >= 12 && type(nal) <= 17) {
                sets.add(nal);
            }
        }
        var units = accessUnits(nals);
        var ranks = outputRanks(units);
        var delay = 0;
        for (var i = 0; i < ranks.length; i++) {
            delay = Math.max(delay, i - ranks[i]);
        }
        var track = VideoTrack.builder().id(1).codec(Codec.VVC).timeBase(new Rational(1, 90_000)).width(width)
                .height(height).codecConfig(vvcC(sets)).build();
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink)) {
            muxer.addTrack(track);
            for (var i = 0; i < units.size(); i++) {
                var sample = new ByteArrayOutputStream();
                var key = false;
                for (var nal : units.get(i)) {
                    sample.write(nal.length >>> 24);
                    sample.write(nal.length >>> 16);
                    sample.write(nal.length >>> 8);
                    sample.write(nal.length);
                    sample.writeBytes(nal);
                    key |= type(nal) >= 7 && type(nal) <= 9;
                }
                muxer.writePacket(new Packet(1, sample.toByteArray(), (long) (ranks[i] + delay) * FRAME,
                        (long) i * FRAME, FRAME, key));
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /**
     * Returns, for each access unit in decoding order, its index in output order, as the decoder under test outputs
     * them; the frame contents are checked against the reference decoder by the VVC tests.
     */
    private static int[] outputRanks(List<List<byte[]>> units) {
        var order = new ArrayList<Long>();
        var track = VideoTrack.builder().id(1).codec(Codec.VVC).timeBase(new Rational(1, 90_000)).build();
        try (var decoder = new VvcDecoder(track)) {
            Consumer<VideoFrame> sink = frame -> order.add(frame.pts());
            for (var i = 0; i < units.size(); i++) {
                var data = new ByteArrayOutputStream();
                for (var nal : units.get(i)) {
                    data.writeBytes(new byte[]{0, 0, 1});
                    data.writeBytes(nal);
                }
                decoder.decode(new Packet(1, data.toByteArray(), i, i, 1, false), sink);
            }
            decoder.flush(sink);
        }
        if (order.size() != units.size()) {
            throw new IllegalStateException(order.size() + " frames decoded from " + units.size() + " pictures");
        }
        var ranks = new int[units.size()];
        for (var rank = 0; rank < order.size(); rank++) {
            ranks[(int) (long) order.get(rank)] = rank;
        }
        return ranks;
    }

    /**
     * Groups NAL units into access units, each starting at a delimiter, a picture header or a slice carrying its
     * picture header that follows a slice, with the prefix units before it.
     */
    private static List<List<byte[]>> accessUnits(List<byte[]> nals) {
        var units = new ArrayList<List<byte[]>>();
        var current = new ArrayList<byte[]>();
        var pending = new ArrayList<byte[]>();
        var slice = false;
        for (var nal : nals) {
            var type = type(nal);
            var vcl = type <= 11;
            var suffix = type == 18 || type == 21 || type == 22 || type == 24 || type == 25;
            if (slice && (type == 19 || type == 20 || vcl && (nal[2] & 0x80) != 0)) {
                units.add(current);
                current = new ArrayList<>(pending);
                pending.clear();
                slice = false;
            }
            if (slice && !vcl && !suffix) {
                pending.add(nal);
                continue;
            }
            current.addAll(pending);
            pending.clear();
            current.add(nal);
            slice |= vcl;
        }
        current.addAll(pending);
        if (!current.isEmpty()) {
            units.add(current);
        }
        return units;
    }

    /**
     * Returns the NAL units of an Annex B stream without start codes and trailing zero bytes.
     */
    private static List<byte[]> nalUnits(byte[] data) {
        var starts = new ArrayList<Integer>();
        for (var i = 0; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                starts.add(i + 3);
                i += 2;
            }
        }
        var result = new ArrayList<byte[]>();
        for (var k = 0; k < starts.size(); k++) {
            var end = k + 1 < starts.size() ? starts.get(k + 1) - 3 : data.length;
            while (end > starts.get(k) && data[end - 1] == 0) {
                end--;
            }
            result.add(Arrays.copyOfRange(data, starts.get(k), end));
        }
        return result;
    }

    private static int type(byte[] nal) {
        return (nal[1] >> 3) & 0x1F;
    }

    /**
     * Builds a {@code VvcDecoderConfigurationRecord} without a profile, tier and level record, carrying the
     * parameter sets with four-byte length prefixes.
     */
    private static byte[] vvcC(List<byte[]> sets) {
        var out = new ByteArrayOutputStream();
        out.write(0xF8 | (3 << 1));
        var types = sets.stream().map(VvcSamples::type).distinct().toList();
        out.write(types.size());
        for (var type : types) {
            var ofType = sets.stream().filter(set -> type(set) == type).toList();
            out.write(0x80 | type);
            if (type != 12 && type != 13) {
                out.write(ofType.size() >> 8);
                out.write(ofType.size());
            }
            for (var set : ofType) {
                out.write(set.length >> 8);
                out.write(set.length);
                out.writeBytes(set);
            }
        }
        return out.toByteArray();
    }
}

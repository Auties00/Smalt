package com.github.auties00.smalt.mpeg.behavior;

import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.hevc.HevcDecoder;
import com.github.auties00.smalt.video.hevc.HevcDecoderOptions;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;

/**
 * Packages the HEVC Annex B vectors of the screen content coding and separate color plane tests, whose pictures each
 * end with a decoded picture hash (a suffix SEI message), into an MP4 file (one sample per picture, presentation
 * times in output order) or an HEIC item, and the multi-layer vectors into an MP4 file of one sample per access unit
 * whose configuration carries the parameter sets of every layer.
 */
final class HevcSamples {
    /**
     * The duration of a picture, in the 90 kHz time base of the track.
     */
    private static final int FRAME = 3600;

    private HevcSamples() {
    }

    /**
     * Returns an MP4 file holding an Annex B stream as one {@code hvc1} track.
     *
     * @param stream the stream, every picture of which ends with a suffix SEI message
     * @param width the picture width
     * @param height the picture height
     * @param chromaFormatIdc the {@code chroma_format_idc} of the stream
     * @param bitDepth the bit depth of the stream
     */
    static byte[] mp4(byte[] stream, int width, int height, int chromaFormatIdc, int bitDepth) {
        return mp4(pictures(nalUnits(stream)), width, height, chromaFormatIdc, bitDepth);
    }

    /**
     * Returns an MP4 file holding a multi-layer Annex B stream as one {@code hvc1} track: one sample per access unit
     * holding the pictures of every layer, the parameter sets of the layers above the base layer in the {@code lhvC}
     * record the muxer splits off the configuration.
     *
     * @param stream the stream
     * @param width the base layer's picture width
     * @param height the base layer's picture height
     */
    static byte[] mp4Layered(byte[] stream, int width, int height) {
        return mp4(accessUnits(nalUnits(stream)), width, height, 1, 8);
    }

    private static byte[] mp4(List<List<byte[]>> pictures, int width, int height, int chromaFormatIdc, int bitDepth) {
        var sets = new ArrayList<byte[]>();
        for (var nal : pictures.getFirst()) {
            if (type(nal) >= 32 && type(nal) <= 34) {
                sets.add(nal);
            }
        }
        var ranks = outputRanks(pictures);
        var delay = 0;
        for (var i = 0; i < ranks.length; i++) {
            delay = Math.max(delay, i - ranks[i]);
        }
        var track = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(new Rational(1, 90_000)).width(width)
                .height(height).codecConfig(hvcC(sets, chromaFormatIdc, bitDepth)).build();
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink)) {
            muxer.addTrack(track);
            for (var i = 0; i < pictures.size(); i++) {
                var sample = new ByteArrayOutputStream();
                var key = false;
                for (var nal : pictures.get(i)) {
                    lengthPrefixed(sample, nal);
                    key |= type(nal) >= 16 && type(nal) <= 23;
                }
                muxer.writePacket(new Packet(1, sample.toByteArray(), (long) (ranks[i] + delay) * FRAME,
                        (long) i * FRAME, FRAME, key));
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /**
     * Returns an HEIC file whose primary item is the first picture of an Annex B stream.
     *
     * @param stream the stream
     * @param width the picture width
     * @param height the picture height
     * @param chromaFormatIdc the {@code chroma_format_idc} of the stream
     * @param bitDepth the bit depth of the stream
     */
    static byte[] heic(byte[] stream, int width, int height, int chromaFormatIdc, int bitDepth) {
        var sets = new ArrayList<byte[]>();
        var payload = new ByteArrayOutputStream();
        for (var nal : pictures(nalUnits(stream)).getFirst()) {
            if (type(nal) >= 32 && type(nal) <= 34) {
                sets.add(nal);
            } else if (type(nal) < 32) {
                lengthPrefixed(payload, nal);
            }
        }
        var builder = new HeifBuilder().brands("heic", "heic", "mif1");
        var item = builder.item("hvc1", payload.toByteArray(), plain(HeifBuilder.ispe(width, height)),
                essential(HeifBuilder.box("hvcC", hvcC(sets, chromaFormatIdc, bitDepth))));
        builder.primary(item);
        return builder.build();
    }

    /**
     * Returns, for each picture in decoding order, its index in output order, as the decoder under test outputs
     * them; the frame contents are checked against the reference decoder by the conformance tests.
     */
    private static int[] outputRanks(List<List<byte[]>> pictures) {
        var order = new ArrayList<Long>();
        var options = HevcDecoderOptions.builder().highBitDepth(true).build();
        var track = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(new Rational(1, 90_000)).build();
        try (var decoder = new HevcDecoder(track, options)) {
            Consumer<VideoFrame> sink = frame -> order.add(frame.pts());
            for (var i = 0; i < pictures.size(); i++) {
                var data = new ByteArrayOutputStream();
                for (var nal : pictures.get(i)) {
                    data.write(0);
                    data.write(0);
                    data.write(1);
                    data.writeBytes(nal);
                }
                decoder.decode(new Packet(1, data.toByteArray(), i, i, 1, false), sink);
            }
            decoder.flush(sink);
        }
        if (order.size() != pictures.size()) {
            throw new IllegalStateException(order.size() + " frames decoded from " + pictures.size() + " pictures");
        }
        var ranks = new HashMap<Long, Integer>();
        for (var k = 0; k < order.size(); k++) {
            ranks.put(order.get(k), k);
        }
        var result = new int[pictures.size()];
        for (var i = 0; i < result.length; i++) {
            result[i] = ranks.get((long) i);
        }
        return result;
    }

    /**
     * Groups the NAL units of a multi-layer stream into access units, each starting at a parameter set, delimiter,
     * prefix SEI message or first slice segment of the base layer that follows a slice.
     */
    private static List<List<byte[]>> accessUnits(List<byte[]> nals) {
        var units = new ArrayList<List<byte[]>>();
        var current = new ArrayList<byte[]>();
        var slice = false;
        for (var nal : nals) {
            var type = type(nal);
            var baseLayer = (nal[0] & 1) == 0 && (nal[1] & 0xF8) == 0;
            // parameter sets, delimiters and prefix SEI messages start an access unit; end of sequence and end of
            // bitstream units end one
            var starts = type >= 32 && type <= 35 || type == 39 || type < 32 && (nal[2] & 0x80) != 0;
            if (slice && baseLayer && starts) {
                units.add(current);
                current = new ArrayList<>();
                slice = false;
            }
            current.add(nal);
            slice |= type < 32;
        }
        if (!current.isEmpty()) {
            units.add(current);
        }
        return units;
    }

    /**
     * Groups NAL units into pictures, each ending with a suffix SEI message (NAL unit type 40).
     */
    private static List<List<byte[]>> pictures(List<byte[]> nals) {
        var pictures = new ArrayList<List<byte[]>>();
        var current = new ArrayList<byte[]>();
        for (var nal : nals) {
            current.add(nal);
            if (type(nal) == 40) {
                pictures.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            throw new IllegalStateException("a picture does not end with a suffix SEI message");
        }
        return pictures;
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
        return (nal[0] >> 1) & 0x3F;
    }

    private static void lengthPrefixed(ByteArrayOutputStream out, byte[] nal) {
        out.write(nal.length >>> 24);
        out.write(nal.length >>> 16);
        out.write(nal.length >>> 8);
        out.write(nal.length);
        out.writeBytes(nal);
    }

    /**
     * Builds an {@code HEVCDecoderConfigurationRecord} carrying the parameter sets, with the general profile, tier
     * and level copied from the sequence parameter set.
     */
    private static byte[] hvcC(List<byte[]> sets, int chromaFormatIdc, int bitDepth) {
        var sps = sets.stream().filter(set -> type(set) == 33).findFirst().orElseThrow();
        var rbsp = unescape(sps);
        var out = new ByteArrayOutputStream();
        out.write(1);
        out.write(rbsp, 3, 12);
        out.write(0xF0);
        out.write(0);
        out.write(0xFC);
        out.write(0xFC | chromaFormatIdc);
        out.write(0xF8 | (bitDepth - 8));
        out.write(0xF8 | (bitDepth - 8));
        out.write(0);
        out.write(0);
        out.write(0x0F);
        var types = new int[]{32, 33, 34};
        out.write(types.length);
        for (var setType : types) {
            var ofType = sets.stream().filter(set -> type(set) == setType).toList();
            out.write(0x80 | setType);
            out.write(ofType.size() >> 8);
            out.write(ofType.size());
            for (var set : ofType) {
                out.write(set.length >> 8);
                out.write(set.length);
                out.writeBytes(set);
            }
        }
        return out.toByteArray();
    }

    private static byte[] unescape(byte[] nal) {
        var out = new ByteArrayOutputStream();
        var zeros = 0;
        for (var b : nal) {
            if (zeros >= 2 && b == 3) {
                zeros = 0;
                continue;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }
}

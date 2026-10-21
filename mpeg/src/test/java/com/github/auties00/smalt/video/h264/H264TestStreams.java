package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Test helpers that split Annex B byte streams into NAL units and access units, repackage them
 * as length-prefixed samples with an {@code avcC} record, and run the decoder.
 */
final class H264TestStreams {
    private H264TestStreams() {
        throw new AssertionError();
    }

    /** Returns each NAL unit of an Annex B stream including its start code. */
    static List<byte[]> splitAnnexB(byte[] data) {
        var result = new ArrayList<byte[]>();
        var starts = startCodes(data);
        for (var k = 0; k < starts.size(); k++) {
            var s = starts.get(k);
            var e = k + 1 < starts.size() ? starts.get(k + 1) : data.length;
            result.add(Arrays.copyOfRange(data, s, e));
        }
        return result;
    }

    /** Returns the payload of each NAL unit (header byte first, no start code, no trailing zeros). */
    static List<byte[]> nalUnits(byte[] data) {
        var result = new ArrayList<byte[]>();
        var starts = startCodes(data);
        for (var k = 0; k < starts.size(); k++) {
            var s = starts.get(k) + 3;
            var e = k + 1 < starts.size() ? starts.get(k + 1) : data.length;
            while (e > s && data[e - 1] == 0) {
                e--;
            }
            if (e > s) {
                result.add(Arrays.copyOfRange(data, s, e));
            }
        }
        return result;
    }

    private static List<Integer> startCodes(byte[] data) {
        var starts = new ArrayList<Integer>();
        for (var i = 0; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                starts.add(i);
                i += 2;
            }
        }
        return starts;
    }

    /**
     * Groups NAL units into access units: a new unit starts at an AUD, SPS, PPS or SEI after a slice, or at a slice
     * with first_mb_in_slice 0 (of the Y plane when the sequence codes its color planes separately).
     */
    static List<List<byte[]>> accessUnits(List<byte[]> nals) {
        var units = new ArrayList<List<byte[]>>();
        var current = new ArrayList<byte[]>();
        var sawSlice = false;
        var separatePlanes = false;
        for (var nal : nals) {
            var type = nal[0] & 0x1F;
            if (type == 7) {
                separatePlanes = separatePlanes(nal);
            }
            var boundary = false;
            if (type == 9 || ((type == 7 || type == 8 || type == 6) && sawSlice)) {
                boundary = true;
            } else if ((type == 1 || type == 5) && sawSlice && firstMbIsZero(nal)
                    && (!separatePlanes || colorPlane(nal) == 0)) {
                boundary = true;
            }
            if (boundary && !current.isEmpty()) {
                units.add(current);
                current = new ArrayList<>();
                sawSlice = false;
            }
            current.add(nal);
            if (type == 1 || type == 5) {
                sawSlice = true;
            }
        }
        if (!current.isEmpty()) {
            units.add(current);
        }
        return units;
    }

    private static boolean firstMbIsZero(byte[] nal) {
        return nal.length > 1 && (nal[1] & 0x80) != 0;
    }

    /** Returns separate_colour_plane_flag of an SPS NAL unit (false for profiles without chroma_format_idc). */
    private static boolean separatePlanes(byte[] nal) {
        var profile = nal[1] & 0xFF;
        if (profile != 100 && profile != 110 && profile != 122 && profile != 244 && profile != 44 && profile != 83
                && profile != 86 && profile != 118 && profile != 128 && profile != 138 && profile != 139
                && profile != 134 && profile != 135) {
            return false;
        }
        var position = new int[]{32};
        ue(nal, position);
        return ue(nal, position) == 3 && bit(nal, position) == 1;
    }

    /** Returns colour_plane_id of a slice NAL unit of a sequence that codes its color planes separately. */
    private static int colorPlane(byte[] nal) {
        var position = new int[]{8};
        ue(nal, position);
        ue(nal, position);
        ue(nal, position);
        return (bit(nal, position) << 1) | bit(nal, position);
    }

    private static int bit(byte[] data, int[] position) {
        var at = position[0]++;
        return at >> 3 < data.length ? (data[at >> 3] >> (7 - (at & 7))) & 1 : 0;
    }

    private static int ue(byte[] data, int[] position) {
        var zeros = 0;
        while (zeros < 32 && bit(data, position) == 0) {
            zeros++;
        }
        var value = 0;
        for (var i = 0; i < zeros; i++) {
            value = (value << 1) | bit(data, position);
        }
        return (1 << zeros) - 1 + value;
    }

    /** Concatenates NAL units with four-byte start codes. */
    static byte[] annexB(List<byte[]> nals) {
        var out = new ByteArrayOutputStream();
        for (var nal : nals) {
            out.writeBytes(new byte[]{0, 0, 0, 1});
            out.writeBytes(nal);
        }
        return out.toByteArray();
    }

    /** Concatenates NAL units with big-endian length prefixes of the given size. */
    static byte[] lengthPrefixed(List<byte[]> nals, int lengthSize) {
        var out = new ByteArrayOutputStream();
        for (var nal : nals) {
            for (var i = lengthSize - 1; i >= 0; i--) {
                out.write((nal.length >>> (8 * i)) & 0xFF);
            }
            out.writeBytes(nal);
        }
        return out.toByteArray();
    }

    /** Builds an AVCDecoderConfigurationRecord from the first SPS and PPS of a stream. */
    static byte[] avcC(List<byte[]> nals, int lengthSize, boolean withParameterSets) {
        byte[] sps = null;
        byte[] pps = null;
        for (var nal : nals) {
            var type = nal[0] & 0x1F;
            if (type == 7 && sps == null) {
                sps = nal;
            } else if (type == 8 && pps == null) {
                pps = nal;
            }
        }
        var out = new ByteArrayOutputStream();
        out.write(1);
        out.write(sps != null ? sps[1] : 100);
        out.write(sps != null ? sps[2] : 0);
        out.write(sps != null ? sps[3] : 40);
        out.write(0xFC | (lengthSize - 1));
        if (withParameterSets && sps != null) {
            out.write(0xE1);
            out.write(sps.length >>> 8);
            out.write(sps.length & 0xFF);
            out.writeBytes(sps);
            out.write(1);
            out.write(pps.length >>> 8);
            out.write(pps.length & 0xFF);
            out.writeBytes(pps);
        } else {
            out.write(0xE0);
            out.write(0);
        }
        return out.toByteArray();
    }

    /** Returns a track description for decoding. */
    static VideoTrack track(Optional<byte[]> codecConfig, Optional<Rational> frameRate) {
        return VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000))
                .codecConfig(codecConfig.orElse(null))
                .frameRate(frameRate.orElse(null))
                .build();
    }

    /** Serializes a picture as tightly packed planar YUV (luma only for monochrome). */
    static byte[] serialize(YuvPicture picture) {
        var w = picture.width();
        var h = picture.height();
        var cw = picture.chromaWidth();
        var ch = picture.chromaHeight();
        var out = new byte[w * h + 2 * cw * ch];
        for (var y = 0; y < h; y++) {
            System.arraycopy(picture.y(), y * picture.yStride(), out, y * w, w);
        }
        if (picture.u() != null) {
            for (var y = 0; y < ch; y++) {
                System.arraycopy(picture.u(), y * picture.uvStride(), out, w * h + y * cw, cw);
                System.arraycopy(picture.v(), y * picture.uvStride(), out, w * h + cw * ch + y * cw, cw);
            }
        }
        return out;
    }

    /** A decoded frame copied out of the decoder, which reuses its picture buffers. */
    record Decoded(byte[] samples, int width, int height, long pts, long duration, boolean keyframe, ChromaFormat format,
                   ColorInfo color) {
    }

    /** Decodes packets and returns every frame, copied. */
    static List<Decoded> decode(VideoTrack track, List<Packet> packets, int threads) {
        var frames = new ArrayList<Decoded>();
        java.util.function.Consumer<VideoFrame> sink = frame -> {
            var picture = (YuvPicture) frame.picture();
            frames.add(new Decoded(serialize(picture), picture.width(), picture.height(), frame.pts(), frame.duration(),
                    frame.keyframe(), picture.chromaFormat(), picture.color()));
        };
        try (var decoder = new H264Decoder(track, H264DecoderOptions.builder().threads(threads).build())) {
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return frames;
    }

    /** Wraps payloads into packets without timestamps. */
    static List<Packet> packets(List<byte[]> payloads) {
        var result = new ArrayList<Packet>();
        for (var payload : payloads) {
            result.add(new Packet(1, payload, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false));
        }
        return result;
    }
}

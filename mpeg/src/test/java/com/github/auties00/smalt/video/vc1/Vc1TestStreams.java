package com.github.auties00.smalt.video.vc1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Cuts the VC-1 test bitstream formats into packets (the advanced profile elementary stream of start codes, and the
 * RCV serialization of SMPTE ST 421 Annex L), decodes them through {@link Vc1Decoder}, and describes where two decodes
 * first differ.
 */
public final class Vc1TestStreams {
    private Vc1TestStreams() {
    }

    /**
     * One decoded frame: the planar Y, U and V samples at the output size, and what the frame says.
     */
    public record Frame(byte[] raw, long pts, long duration, boolean keyframe, FrameSideData side, int width,
                        int height) {
    }

    /**
     * The frames of a decode in output order, and the messages of the failures the decoder raised.
     */
    public record Decoded(List<Frame> frames, List<String> failures) {
        public byte[] raw() {
            var out = new ByteArrayOutputStream();
            for (var frame : frames) {
                out.writeBytes(frame.raw());
            }
            return out.toByteArray();
        }

        public int count() {
            return frames.size();
        }
    }

    /**
     * A stream cut into packets, with the configuration a container would carry.
     */
    public record Stream(byte[] config, int width, int height, List<Packet> packets) {
    }

    /**
     * Cuts an advanced profile elementary stream into packets: each packet holds one frame start code unit with the
     * field and slice units after it, preceded by the sequence and entry-point headers before it.
     */
    public static Stream elementary(byte[] stream) {
        var starts = new ArrayList<Integer>();
        for (var i = 0; i + 3 < stream.length; i++) {
            if (stream[i] == 0 && stream[i + 1] == 0 && stream[i + 2] == 1) {
                starts.add(i);
                i += 2;
            }
        }
        var packets = new ArrayList<Packet>();
        var from = -1;
        var hasFrame = false;
        for (var start : starts) {
            var type = stream[start + 3] & 0xFF;
            var opensPacket = type == 0x0F || type == 0x0E || type == 0x0D;
            if (opensPacket && hasFrame) {
                packets.add(packet(Arrays.copyOfRange(stream, from, start), Packet.NO_TIMESTAMP));
                from = -1;
                hasFrame = false;
            }
            if (from < 0) {
                from = start;
            }
            if (type == 0x0D) {
                hasFrame = true;
            }
        }
        if (from >= 0) {
            packets.add(packet(Arrays.copyOfRange(stream, from, stream.length), Packet.NO_TIMESTAMP));
        }
        return new Stream(null, 0, 0, packets);
    }

    /**
     * Cuts an RCV file (SMPTE ST 421 Annex L) into its sequence layer, used as the configuration, and one packet per
     * frame, stamped with the frame layer's millisecond timestamp.
     */
    public static Stream rcv(byte[] file) {
        var extra = littleEndian(file, 4);
        var headerSize = 8 + extra + 24;
        var height = littleEndian(file, 8 + extra);
        var width = littleEndian(file, 12 + extra);
        var config = Arrays.copyOf(file, headerSize);
        var packets = new ArrayList<Packet>();
        var position = headerSize;
        while (position + 8 <= file.length) {
            var size = littleEndian(file, position) & 0xFFFFFF;
            var timestamp = littleEndian(file, position + 4) & 0xFFFFFFFFL;
            var end = Math.min(file.length, position + 8 + size);
            packets.add(packet(Arrays.copyOfRange(file, position + 8, end), timestamp));
            position = end;
        }
        return new Stream(config, width, height, packets);
    }

    private static Packet packet(byte[] data, long pts) {
        return new Packet(1, data, pts, pts, Packet.UNKNOWN_DURATION, false);
    }

    static int littleEndian(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
    }

    /**
     * Returns the track of a stream, on a millisecond time base.
     */
    public static VideoTrack track(Stream stream) {
        var builder = VideoTrack.builder().id(1).codec(Codec.VC1).timeBase(new Rational(1, 1000));
        if (stream.config() != null) {
            builder.codecConfig(stream.config());
        }
        if (stream.width() > 0) {
            builder.width(stream.width()).height(stream.height());
        }
        return builder.build();
    }

    /**
     * Decodes a stream; malformed packets are recorded and decoding goes on.
     */
    public static Decoded decode(Stream stream, VideoDecoderOptions options) {
        var frames = new ArrayList<Frame>();
        var failures = new ArrayList<String>();
        try (var decoder = new Vc1Decoder(track(stream), options)) {
            Consumer<VideoFrame> sink = frame -> frames.add(frame(frame));
            var index = 0;
            for (var packet : stream.packets()) {
                try {
                    decoder.decode(packet, sink);
                } catch (MediaException.Malformed exception) {
                    failures.add("packet " + index + ": " + exception.getMessage());
                }
                index++;
            }
            decoder.flush(sink);
        }
        return new Decoded(frames, failures);
    }

    /**
     * Decodes a stream with the default options.
     */
    public static Decoded decode(Stream stream) {
        return decode(stream, VideoDecoderOptions.defaults());
    }

    static Frame frame(VideoFrame frame) {
        var picture = (YuvPicture) frame.picture();
        return new Frame(raw(picture), frame.pts(), frame.duration(), frame.keyframe(), frame.sideData(),
                picture.width(), picture.height());
    }

    /** Returns the planes of a picture, rows packed. */
    public static byte[] raw(YuvPicture picture) {
        var out = new ByteArrayOutputStream();
        for (var y = 0; y < picture.height(); y++) {
            out.write(picture.y(), y * picture.yStride(), picture.width());
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            out.write(picture.u(), y * picture.uvStride(), picture.chromaWidth());
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            out.write(picture.v(), y * picture.uvStride(), picture.chromaWidth());
        }
        return out.toByteArray();
    }

    /**
     * Describes where two raw 4:2:0 frame sequences first differ: the frame, the plane, the sample and its
     * macroblock, the values, and how many samples of that frame differ.
     */
    public static String firstDifference(byte[] expected, byte[] actual, int width, int height) {
        var chromaWidth = (width + 1) / 2;
        var chromaHeight = (height + 1) / 2;
        var frameSize = width * height + 2 * chromaWidth * chromaHeight;
        var frames = Math.min(expected.length, actual.length) / frameSize;
        for (var f = 0; f < frames; f++) {
            var base = f * frameSize;
            var first = -1;
            var count = 0;
            for (var i = 0; i < frameSize; i++) {
                if (expected[base + i] != actual[base + i]) {
                    if (first < 0) {
                        first = i;
                    }
                    count++;
                }
            }
            if (first < 0) {
                continue;
            }
            String where;
            if (first < width * height) {
                var x = first % width;
                var y = first / width;
                where = "Y (" + x + ", " + y + ") macroblock (" + x / 16 + ", " + y / 16 + ")";
            } else {
                var offset = first - width * height;
                var plane = offset < chromaWidth * chromaHeight ? "U" : "V";
                offset %= chromaWidth * chromaHeight;
                var x = offset % chromaWidth;
                var y = offset / chromaWidth;
                where = plane + " (" + x + ", " + y + ") macroblock (" + x / 8 + ", " + y / 8 + ")";
            }
            return "frame " + f + " first differs at " + where + ": expected "
                    + (expected[base + first] & 0xFF) + ", actual " + (actual[base + first] & 0xFF) + "; "
                    + count + " of " + frameSize + " samples differ";
        }
        return expected.length == actual.length ? "identical" : "the first " + frames + " frames are identical, "
                + "sizes " + expected.length + " and " + actual.length;
    }
}

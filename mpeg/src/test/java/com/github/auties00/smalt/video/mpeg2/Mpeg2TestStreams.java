package com.github.auties00.smalt.video.mpeg2;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Decodes MPEG-1 and MPEG-2 video through Smalt (from container files and from raw elementary streams split into
 * packets here), through the ISO/IEC 13818-5 reference decoder built with Smalt's inverse DCT
 * ({@code tools/testdata/mpeg2-oracle}) and through ffmpeg, for the MPEG video tests.
 */
public final class Mpeg2TestStreams {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private Mpeg2TestStreams() {
    }

    /**
     * One decoded frame: the planar Y, U and V samples of the picture at its display size, and what the frame says.
     */
    public record Frame(byte[] raw, long pts, long duration, boolean keyframe, FrameSideData side, int width,
                        int height, ChromaFormat chroma) {
    }

    /**
     * The frames of a decode, in output order, and the description of the first failure the decoder raised (the
     * decode goes on after a {@code MediaException.Malformed}).
     */
    public record Decoded(List<Frame> frames, List<String> failures) {
        public byte[] raw() {
            var out = new ByteArrayOutputStream();
            for (var frame : frames) {
                out.writeBytes(frame.raw());
            }
            return out.toByteArray();
        }

        public List<Long> timestamps() {
            return frames.stream().map(Frame::pts).toList();
        }

        public int count() {
            return frames.size();
        }

        public Frame first() {
            return frames.getFirst();
        }
    }

    /** How a raw elementary stream is cut into packets. */
    public enum Split {
        /** One picture (with the headers before it) per packet. */
        PICTURES,
        /** Three pictures per packet. */
        TRIPLES,
        /** The whole stream in one packet. */
        WHOLE
    }

    /** Returns a track of the given codec without configuration, on a 90 kHz time base. */
    public static VideoTrack track(Codec codec) {
        return VideoTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(90_000)).build();
    }

    /** Decodes the first video track of a container file with the decoder the registry finds for it. */
    public static Decoded decodeFile(Path file, VideoDecoderOptions options) {
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            var track = demuxer.tracks().stream().filter(t -> t instanceof VideoTrack).map(t -> (VideoTrack) t)
                    .findFirst().orElseThrow();
            var frames = new ArrayList<Frame>();
            var failures = new ArrayList<String>();
            try (var decoder = VideoDecoders.create(track, options)) {
                Consumer<VideoFrame> sink = frame -> frames.add(frame(frame));
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    if (packet.trackId() == track.id()) {
                        feed(decoder, packet, sink, failures);
                    }
                }
                decoder.flush(sink);
            }
            return new Decoded(frames, failures);
        }
    }

    /** Decodes the first video track of a container file with the default options. */
    public static Decoded decodeFile(Path file) {
        return decodeFile(file, VideoDecoderOptions.defaults());
    }

    /** Decodes a raw elementary stream cut into packets without timestamps. */
    public static Decoded decodeElementary(byte[] stream, Codec codec, Split split, VideoDecoderOptions options) {
        var frames = new ArrayList<Frame>();
        var failures = new ArrayList<String>();
        try (var decoder = new Mpeg2Decoder(track(codec), options)) {
            Consumer<VideoFrame> sink = frame -> frames.add(frame(frame));
            for (var packet : packets(stream, split)) {
                feed(decoder, packet, sink, failures);
            }
            decoder.flush(sink);
        }
        return new Decoded(frames, failures);
    }

    /** Decodes a raw elementary stream one picture per packet with the default options. */
    public static Decoded decodeElementary(byte[] stream) {
        return decodeElementary(stream, Codec.MPEG2_VIDEO, Split.PICTURES, VideoDecoderOptions.defaults());
    }

    private static void feed(VideoDecoder decoder, Packet packet, Consumer<VideoFrame> sink, List<String> failures) {
        try {
            decoder.decode(packet, sink);
        } catch (com.github.auties00.smalt.MediaException.Malformed exception) {
            failures.add(exception.getMessage());
        }
    }

    static Frame frame(VideoFrame frame) {
        var picture = (YuvPicture) frame.picture();
        return new Frame(raw(picture), frame.pts(), frame.duration(), frame.keyframe(), frame.sideData(),
                picture.width(), picture.height(), picture.chromaFormat());
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
     * Cuts a raw elementary stream into packets: a packet starts at a sequence header, a group of pictures header or
     * a picture header that follows a picture of the packet before.
     */
    public static List<Packet> packets(byte[] stream, Split split) {
        var starts = new ArrayList<Integer>();
        var pictures = 0;
        var limit = switch (split) {
            case PICTURES -> 1;
            case TRIPLES -> 3;
            case WHOLE -> Integer.MAX_VALUE;
        };
        var open = false;
        for (var i = 0; i + 3 < stream.length; i++) {
            if (stream[i] != 0 || stream[i + 1] != 0 || stream[i + 2] != 1) {
                continue;
            }
            var code = stream[i + 3] & 0xFF;
            if (code == 0xB3 || code == 0xB8 || code == 0x00) {
                if (pictures >= limit && !open) {
                    starts.add(i);
                    pictures = 0;
                    open = true;
                }
                if (code == 0x00) {
                    pictures++;
                    open = false;
                }
            }
            i += 3;
        }
        var packets = new ArrayList<Packet>();
        var from = 0;
        for (var start : starts) {
            if (start > from) {
                packets.add(new Packet(1, java.util.Arrays.copyOfRange(stream, from, start), Packet.NO_TIMESTAMP,
                        Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION, false));
            }
            from = start;
        }
        if (from < stream.length) {
            packets.add(new Packet(1, java.util.Arrays.copyOfRange(stream, from, stream.length), Packet.NO_TIMESTAMP,
                    Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION, false));
        }
        return packets;
    }

    /**
     * Decodes a raw elementary stream with the ISO/IEC 13818-5 reference decoder: with Smalt's inverse DCT, or with
     * its double precision reference transform.
     *
     * @return the frames in display order, planar, the chroma planes of a 4:2:0 or 4:2:2 stream
     * {@code horizontal_size >> 1} wide
     */
    public static byte[] reference(byte[] stream, boolean referenceIdct) {
        Oracle.assume(Oracle.Tool.MPEG2DEC);
        var directory = Scratch.directory("mpeg2-reference");
        var id = ProcessHandle.current().pid() + "-" + SEQUENCE.incrementAndGet();
        var input = directory.resolve(id + ".m2v");
        var output = directory.resolve(id + ".yuv");
        try {
            Files.write(input, stream);
            Files.deleteIfExists(output);
            var arguments = new ArrayList<>(List.of("-b", input.toString(), "-q", "-f", "-o0", output.toString()));
            if (referenceIdct) {
                arguments.add("-r");
            }
            Oracle.run(Oracle.Tool.MPEG2DEC, Duration.ofMinutes(5), arguments);
            var decoded = Files.isRegularFile(output) ? Files.readAllBytes(output) : new byte[0];
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            return decoded;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Returns Smalt's frames in the layout of the reference decoder's output: the chroma planes of a 4:2:0 or 4:2:2
     * stream of odd width cut to {@code horizontal_size >> 1} columns, and those of a 4:2:0 stream of odd height to
     * {@code vertical_size >> 1} rows.
     */
    public static byte[] referenceLayout(Decoded decoded) {
        var out = new ByteArrayOutputStream();
        for (var frame : decoded.frames()) {
            var width = frame.width();
            var height = frame.height();
            var chromaWidth = frame.chroma() == ChromaFormat.YUV444 ? width : (width + 1) >> 1;
            var chromaHeight = frame.chroma() == ChromaFormat.YUV420 ? (height + 1) >> 1 : height;
            var cutWidth = frame.chroma() == ChromaFormat.YUV444 ? width : width >> 1;
            var cutHeight = frame.chroma() == ChromaFormat.YUV420 ? height >> 1 : height;
            var raw = frame.raw();
            out.write(raw, 0, width * height);
            for (var plane = 0; plane < 2; plane++) {
                var base = width * height + plane * chromaWidth * chromaHeight;
                for (var row = 0; row < cutHeight; row++) {
                    out.write(raw, base + row * chromaWidth, cutWidth);
                }
            }
        }
        return out.toByteArray();
    }

    /** Decodes a file with ffmpeg's C decoder and the given inverse DCT into planar frames of a pixel format. */
    public static byte[] ffmpeg(Path file, String idct, String pixelFormat) {
        var arguments = new ArrayList<String>(List.of("-v", "error"));
        if (idct != null) {
            // -cpuflags 0: the reference is ffmpeg's C inverse DCT, which its SIMD kernels do not reproduce.
            arguments.addAll(List.of("-cpuflags", "0", "-idct", idct));
        }
        arguments.addAll(List.of("-i", file.toString(), "-map", "0:v:0", "-fps_mode", "passthrough", "-f", "rawvideo",
                "-pix_fmt", pixelFormat, "-"));
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), arguments);
    }

    /** Returns ffmpeg's pixel format name of a chroma format. */
    public static String pixelFormat(ChromaFormat chroma) {
        return switch (chroma) {
            case YUV422 -> "yuv422p";
            case YUV444 -> "yuv444p";
            default -> "yuv420p";
        };
    }

    /** Writes bytes to a new scratch file with an extension. */
    public static Path scratch(byte[] data, String extension) {
        var directory = Scratch.directory("mpeg2-streams");
        var file = directory.resolve(ProcessHandle.current().pid() + "-" + SEQUENCE.incrementAndGet() + "."
                + extension);
        try {
            return Files.write(file, data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Reads a file. */
    public static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Decodes container files through Smalt and through ffmpeg for the MPEG-4 Part 2 and H.263
 * tests.
 */
final class Mpeg4TestStreams {
    private Mpeg4TestStreams() {
    }

    record Decoded(byte[] raw, int frames, List<Long> timestamps, int width, int height) {
    }

    static Decoded decode(Path file, Function<VideoTrack, VideoDecoder> factory) {
        var raw = new ByteArrayOutputStream();
        var timestamps = new ArrayList<Long>();
        var size = new int[2];
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            var track = demuxer.tracks().stream().filter(t -> t instanceof VideoTrack).map(t -> (VideoTrack) t)
                    .findFirst().orElseThrow();
            try (var decoder = factory.apply(track)) {
                Consumer<VideoFrame> sink = frame -> {
                    timestamps.add(frame.pts());
                    var picture = (YuvPicture) frame.picture();
                    size[0] = picture.width();
                    size[1] = picture.height();
                    raw.writeBytes(raw(picture));
                };
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    if (packet.trackId() == track.id()) {
                        decoder.decode(packet, sink);
                    }
                }
                decoder.flush(sink);
            }
        }
        return new Decoded(raw.toByteArray(), timestamps.size(), timestamps, size[0], size[1]);
    }

    static Decoded decodeElementary(Path file, Function<VideoTrack, VideoDecoder> factory) {
        var raw = new ByteArrayOutputStream();
        var timestamps = new ArrayList<Long>();
        var size = new int[2];
        try (var source = MediaSource.of(file); var stream = Mpeg4ElementaryStream.open(source)) {
            try (var decoder = factory.apply(stream.track())) {
                Consumer<VideoFrame> sink = frame -> {
                    timestamps.add(frame.pts());
                    var picture = (YuvPicture) frame.picture();
                    size[0] = picture.width();
                    size[1] = picture.height();
                    raw.writeBytes(raw(picture));
                };
                Packet packet;
                while ((packet = stream.read()) != null) {
                    decoder.decode(packet, sink);
                }
                decoder.flush(sink);
            }
        }
        return new Decoded(raw.toByteArray(), timestamps.size(), timestamps, size[0], size[1]);
    }

    /**
     * Returns the decode without the next-to-last picture when it holds exactly one picture more
     * than ffmpeg's decode of a packed bitstream: ffmpeg keeps the B-VOP packed behind the last
     * P-VOP for a following packet, and drops it when no packet follows.
     */
    static Decoded alignPacked(Decoded decoded, int expectedLength) {
        var frameSize = decoded.frames() == 0 ? 0 : decoded.raw().length / decoded.frames();
        if (frameSize == 0 || decoded.raw().length != expectedLength + frameSize || decoded.frames() < 2) {
            return decoded;
        }
        var cut = (decoded.frames() - 2) * frameSize;
        var raw = new byte[expectedLength];
        System.arraycopy(decoded.raw(), 0, raw, 0, cut);
        System.arraycopy(decoded.raw(), cut + frameSize, raw, cut, expectedLength - cut);
        var timestamps = new ArrayList<>(decoded.timestamps());
        timestamps.remove(decoded.frames() - 2);
        return new Decoded(raw, decoded.frames() - 1, timestamps, decoded.width(), decoded.height());
    }

    static byte[] raw(YuvPicture picture) {
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

    static byte[] ffmpeg(Path file, String idct) {
        var arguments = new ArrayList<String>(List.of("-v", "error"));
        if (idct != null) {
            // -cpuflags 0: the reference is ffmpeg's C inverse DCT, which its arm64 NEON kernels do not reproduce.
            arguments.addAll(List.of("-cpuflags", "0", "-idct", idct));
        }
        arguments.addAll(List.of("-i", file.toString(), "-map", "0:v:0", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"));
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), arguments);
    }
}

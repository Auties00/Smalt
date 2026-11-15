package com.github.auties00.smalt.video.dv;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared helpers of the DV tests: the ffmpeg pixel formats of the profiles, source pictures rendered by ffmpeg, the
 * raw layout of decoded pictures and the encoding of picture sequences.
 */
final class DvTesting {
    private DvTesting() {
    }

    /** Returns ffmpeg's pixel format of the coded samples of a profile. */
    static String pixelFormat(DvProfile profile) {
        return switch (profile) {
            case DV_525, DVCPRO_525, DVCPRO_625 -> "yuv411p";
            case DV_625 -> "yuv420p";
            default -> "yuv422p";
        };
    }

    /** Returns the frame rate of a profile as ffmpeg's lavfi sources take it. */
    static String rate(DvProfile profile) {
        var rate = profile.frameRate();
        return rate.numerator() + "/" + rate.denominator();
    }

    /** Returns the picture size of a profile as ffmpeg takes it. */
    static String size(DvProfile profile) {
        return profile.width() + "x" + profile.height();
    }

    /**
     * Renders frames of a blend of ffmpeg's testsrc2 and mandelbrot patterns at a profile's size, as 8-bit 4:2:2
     * (4:2:0 for {@link DvProfile#DV_625}) pictures labeled with the profile's matrix.
     */
    static List<Picture> sourceFrames(DvProfile profile, int frames) {
        return sourceFrames(profile, frames, false);
    }

    /**
     * Renders frames as {@link #sourceFrames(DvProfile, int)} does, with strong temporal noise added when asked, which
     * makes most blocks overflow their compressed-data areas.
     */
    static List<Picture> sourceFrames(DvProfile profile, int frames, boolean noisy) {
        var width = profile.width();
        var height = profile.height();
        var chroma = profile == DvProfile.DV_625 ? ChromaFormat.YUV420 : ChromaFormat.YUV422;
        var pixelFormat = chroma == ChromaFormat.YUV420 ? "yuv420p" : "yuv422p";
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error",
                "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25", "-f", "lavfi", "-i", "mandelbrot=size=320x240:rate=25",
                "-filter_complex", "[0][1]blend=all_mode=average,scale=" + width + ":" + height
                        + ":flags=bicubic" + (noisy ? ",noise=alls=40:allf=t" : "") + ",format=" + pixelFormat,
                "-frames:v", Integer.toString(frames), "-f", "rawvideo", "-pix_fmt", pixelFormat, "-"));
        var color = width > 720 ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
        var chromaWidth = width / 2;
        var chromaHeight = chroma == ChromaFormat.YUV420 ? height / 2 : height;
        var frameSize = width * height + 2 * chromaWidth * chromaHeight;
        var pictures = new ArrayList<Picture>();
        for (var f = 0; f < frames; f++) {
            var at = f * frameSize;
            var y = Arrays.copyOfRange(raw, at, at + width * height);
            at += width * height;
            var u = Arrays.copyOfRange(raw, at, at + chromaWidth * chromaHeight);
            at += chromaWidth * chromaHeight;
            var v = Arrays.copyOfRange(raw, at, at + chromaWidth * chromaHeight);
            pictures.add(YuvPicture.builder().size(width, height).chromaFormat(chroma).color(color)
                    .luma(y, width).chroma(u, v, chromaWidth).build());
        }
        return pictures;
    }

    /**
     * Lays decoded pictures out as ffmpeg's raw video of a profile's pixel format: 4:1:1 chroma is taken from every
     * other column of the 4:2:2 planes the decoder returns.
     */
    static byte[] raw(Picture picture, String pixelFormat) {
        var yuv = (YuvPicture) picture;
        var out = new ByteArrayOutputStream();
        for (var row = 0; row < yuv.height(); row++) {
            out.write(yuv.y(), row * yuv.yStride(), yuv.width());
        }
        for (var plane : List.of(yuv.u(), yuv.v())) {
            for (var row = 0; row < yuv.chromaHeight(); row++) {
                if (pixelFormat.equals("yuv411p")) {
                    for (var column = 0; column < yuv.width() / 4; column++) {
                        out.write(plane[row * yuv.uvStride() + 2 * column]);
                    }
                } else {
                    out.write(plane, row * yuv.uvStride(), yuv.chromaWidth());
                }
            }
        }
        return out.toByteArray();
    }

    /** Returns the luma plane of a picture, without padding. */
    static byte[] luma(Picture picture) {
        var yuv = (YuvPicture) picture;
        var out = new byte[yuv.width() * yuv.height()];
        for (var row = 0; row < yuv.height(); row++) {
            System.arraycopy(yuv.y(), row * yuv.yStride(), out, row * yuv.width(), yuv.width());
        }
        return out;
    }

    /** Encodes pictures and returns the packets. */
    static List<Packet> encode(DvEncoderOptions options, List<Picture> pictures) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new DvEncoder(options)) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i, 1, true), packets::add);
            }
            encoder.flush(packets::add);
        }
        return packets;
    }

    /** Concatenates the data of packets, the raw DIF stream ffmpeg reads as a .dv file. */
    static byte[] concatenate(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        for (var packet : packets) {
            out.writeBytes(packet.data());
        }
        return out.toByteArray();
    }

    /** Splits a raw DIF stream into packets of a profile's frame size. */
    static List<Packet> split(byte[] stream, DvProfile profile) {
        var packets = new ArrayList<Packet>();
        var size = profile.frameSize();
        for (var at = 0; at + size <= stream.length; at += size) {
            packets.add(new Packet(0, Arrays.copyOfRange(stream, at, at + size), packets.size(), packets.size(), 1,
                    true));
        }
        return packets;
    }
}

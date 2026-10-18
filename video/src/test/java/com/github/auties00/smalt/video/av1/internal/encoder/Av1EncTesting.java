package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.Av1Decoder;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Helpers shared by the AV1 encoder tests: decoding with the module's decoder and with ffmpeg,
 * IVF writing and picture comparison.
 */
public final class Av1EncTesting {
    private Av1EncTesting() {
    }

    /** Wraps temporal units in an IVF file with the {@code AV01} fourcc. */
    public static byte[] ivf(int width, int height, List<byte[]> units) {
        var out = new ByteArrayOutputStream();
        var header = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'D').put((byte) 'K').put((byte) 'I').put((byte) 'F');
        header.putShort((short) 0).putShort((short) 32);
        header.put((byte) 'A').put((byte) 'V').put((byte) '0').put((byte) '1');
        header.putShort((short) width).putShort((short) height);
        header.putInt(30).putInt(1).putInt(units.size()).putInt(0);
        out.writeBytes(header.array());
        for (var i = 0; i < units.size(); i++) {
            var unit = units.get(i);
            var frame = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            frame.putInt(unit.length).putLong(i);
            out.writeBytes(frame.array());
            out.writeBytes(unit);
        }
        return out.toByteArray();
    }

    /** Decodes temporal units with the module's decoder, returning one picture per shown frame. */
    public static List<YuvPicture> decode(int width, int height, List<byte[]> units) {
        var track = track(width, height);
        var out = new ArrayList<YuvPicture>();
        try (var decoder = new Av1Decoder(track)) {
            for (var i = 0; i < units.size(); i++) {
                decoder.decode(new Packet(1, units.get(i), i, i, 1, false), frame -> out.add(copy((YuvPicture) frame.picture())));
            }
        }
        return out;
    }

    /** Returns an AV1 track description without codec configuration. */
    public static VideoTrack track(int width, int height) {
        return VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(Rational.MICROSECONDS).width(width)
                .height(height).build();
    }

    /** Copies a picture whose buffers the decoder reuses. */
    public static YuvPicture copy(YuvPicture p) {
        var c = YuvPicture.allocate(p.width(), p.height(), p.chromaFormat(), false, p.color());
        System.arraycopy(p.y(), 0, c.y(), 0, p.y().length);
        System.arraycopy(p.u(), 0, c.u(), 0, p.u().length);
        System.arraycopy(p.v(), 0, c.v(), 0, p.v().length);
        return c;
    }

    /** Returns the index of the first sample where a decoded picture differs from a reconstruction, or -1. */
    public static int firstDifference(YuvPicture decoded, Av1Picture recon) {
        for (var pl = 0; pl < 3; pl++) {
            var w = pl == 0 ? decoded.width() : decoded.chromaWidth();
            var h = pl == 0 ? decoded.height() : decoded.chromaHeight();
            var d = pl == 0 ? decoded.y() : pl == 1 ? decoded.u() : decoded.v();
            var ds = pl == 0 ? decoded.yStride() : decoded.uvStride();
            var r = recon.data[pl];
            var rs = recon.stride[pl != 0 ? 1 : 0];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    if ((d[y * ds + x] & 0xFF) != r[y * rs + x]) {
                        return pl << 28 | y << 14 | x;
                    }
                }
            }
        }
        return -1;
    }

    /** Converts a reconstruction to raw 4:2:0 bytes. */
    public static byte[] raw(Av1Picture recon, int width, int height) {
        var out = new ByteArrayOutputStream();
        for (var pl = 0; pl < 3; pl++) {
            var w = pl == 0 ? width : (width + 1) / 2;
            var h = pl == 0 ? height : (height + 1) / 2;
            var r = recon.data[pl];
            var rs = recon.stride[pl != 0 ? 1 : 0];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    out.write(r[y * rs + x]);
                }
            }
        }
        return out.toByteArray();
    }

    /** Converts a picture to raw 4:2:0 bytes. */
    public static byte[] raw(YuvPicture p) {
        var out = new ByteArrayOutputStream();
        for (var pl = 0; pl < 3; pl++) {
            var w = pl == 0 ? p.width() : p.chromaWidth();
            var h = pl == 0 ? p.height() : p.chromaHeight();
            var d = pl == 0 ? p.y() : pl == 1 ? p.u() : p.v();
            var s = pl == 0 ? p.yStride() : p.uvStride();
            for (var y = 0; y < h; y++) {
                out.write(d, y * s, w);
            }
        }
        return out.toByteArray();
    }

    /** Decodes an IVF file with ffmpeg's libaom decoder to raw 4:2:0 frames, failing on any error. */
    public static byte[] ffmpegDecode(Path ivf) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-xerror",
                "-c:v", "libaom-av1", "-i", ivf.toString(), "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"));
    }

    /** Writes an IVF file to a temporary location. */
    public static Path writeTemp(byte[] data) throws IOException {
        var file = Files.createTempFile("av1enc", ".ivf");
        file.toFile().deleteOnExit();
        Files.write(file, data);
        return file;
    }

    /** Returns the PSNR of two 8-bit sample arrays. */
    public static double psnr(byte[] a, byte[] b) {
        var sse = 0L;
        for (var i = 0; i < a.length; i++) {
            var d = (a[i] & 0xFF) - (b[i] & 0xFF);
            sse += (long) d * d;
        }
        if (sse == 0) {
            return 99;
        }
        return 10 * Math.log10(255.0 * 255.0 * a.length / sse);
    }

    /** Keeps the compiler from flagging the frame type as unused in helpers. */
    static boolean isKey(VideoFrame frame) {
        return frame.keyframe();
    }
}

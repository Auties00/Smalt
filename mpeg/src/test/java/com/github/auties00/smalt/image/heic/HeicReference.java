package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifGrid;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reconstructs HEIC images with ffmpeg as the HEVC decoder: every coded item (after the items it
 * is predicted from) is rewritten as an Annex B stream and decoded by the ffmpeg on {@code PATH},
 * and grids are stitched here, so the result is an independent reference for the samples of
 * {@link HeicDecoder} at the source bit depth.
 */
final class HeicReference {
    /**
     * Planes of one picture: luma, the two chroma planes (absent for monochrome) and alpha (absent
     * without alpha), each row-major without padding.
     */
    record Planes(int width, int height, ChromaFormat format, int depth, int[][] planes) {
        static Planes of(Picture picture) {
            return switch (picture) {
                case YuvPicture yuv -> {
                    var planes = new int[4][];
                    planes[0] = unpack(yuv.y(), yuv.yStride(), yuv.width(), yuv.height());
                    if (yuv.chromaFormat().hasChroma()) {
                        planes[1] = unpack(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                        planes[2] = unpack(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    }
                    if (yuv.hasAlpha()) {
                        planes[3] = unpack(yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height());
                    }
                    yield new Planes(yuv.width(), yuv.height(), yuv.chromaFormat(), 8, planes);
                }
                case Yuv16Picture yuv -> {
                    var planes = new int[4][];
                    planes[0] = unpack(yuv.y(), yuv.yStride(), yuv.width(), yuv.height());
                    if (yuv.chromaFormat().hasChroma()) {
                        planes[1] = unpack(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                        planes[2] = unpack(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    }
                    if (yuv.hasAlpha()) {
                        planes[3] = unpack(yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height());
                    }
                    yield new Planes(yuv.width(), yuv.height(), yuv.chromaFormat(), yuv.bitDepth(), planes);
                }
                default -> throw new IllegalArgumentException("not a planar picture: " + picture);
            };
        }

        int chromaWidth() {
            return format.chromaWidth(width);
        }

        int chromaHeight() {
            return format.chromaHeight(height);
        }

        /**
         * Returns the rectangle of these planes; the chroma rectangle starts at the chroma sample
         * covering the luma corner.
         */
        Planes crop(int x, int y, int w, int h) {
            var out = new int[4][];
            out[0] = crop(planes[0], width, x, y, w, h);
            if (planes[1] != null) {
                var cx = x >> format.shiftX();
                var cy = y >> format.shiftY();
                out[1] = crop(planes[1], chromaWidth(), cx, cy, format.chromaWidth(w), format.chromaHeight(h));
                out[2] = crop(planes[2], chromaWidth(), cx, cy, format.chromaWidth(w), format.chromaHeight(h));
            }
            if (planes[3] != null) {
                out[3] = crop(planes[3], width, x, y, w, h);
            }
            return new Planes(w, h, format, depth, out);
        }

        /**
         * Returns the rectangle of these planes as the decoder crops a clean aperture: chroma is
         * cropped as it is when the rectangle starts on a chroma sample, and otherwise the result
         * is 4:4:4 with each pixel taking the chroma sample that covers it.
         */
        Planes cropExact(int x, int y, int w, int h) {
            if ((x & ((1 << format.shiftX()) - 1)) == 0 && (y & ((1 << format.shiftY()) - 1)) == 0) {
                return crop(x, y, w, h);
            }
            var out = new int[4][];
            out[0] = crop(planes[0], width, x, y, w, h);
            if (planes[1] != null) {
                out[1] = new int[w * h];
                out[2] = new int[w * h];
                for (var row = 0; row < h; row++) {
                    for (var col = 0; col < w; col++) {
                        var sample = ((y + row) >> format.shiftY()) * chromaWidth() + ((x + col) >> format.shiftX());
                        out[1][row * w + col] = planes[1][sample];
                        out[2][row * w + col] = planes[2][sample];
                    }
                }
            }
            if (planes[3] != null) {
                out[3] = crop(planes[3], width, x, y, w, h);
            }
            return new Planes(w, h, planes[1] == null ? format : ChromaFormat.YUV444, depth, out);
        }

        Planes withAlpha(int[] alpha) {
            var out = planes.clone();
            out[3] = alpha;
            return new Planes(width, height, format, depth, out);
        }

        private static int[] crop(int[] plane, int stride, int x, int y, int w, int h) {
            var out = new int[w * h];
            for (var row = 0; row < h; row++) {
                System.arraycopy(plane, (y + row) * stride + x, out, row * w, w);
            }
            return out;
        }

        private static int[] unpack(byte[] plane, int stride, int width, int height) {
            var out = new int[width * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    out[y * width + x] = plane[y * stride + x] & 0xFF;
                }
            }
            return out;
        }

        private static int[] unpack(short[] plane, int stride, int width, int height) {
            var out = new int[width * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    out[y * width + x] = plane[y * stride + x] & 0xFFFF;
                }
            }
            return out;
        }
    }

    private HeicReference() {
    }

    static String pixelFormat(ChromaFormat format, int depth) {
        var base = switch (format) {
            case MONOCHROME -> "gray";
            case YUV420 -> "yuv420p";
            case YUV422 -> "yuv422p";
            default -> "yuv444p";
        };
        return depth == 8 ? base : base + depth + "le";
    }

    /**
     * Converts an {@code hvcC} record and length-prefixed payloads into one Annex B stream.
     */
    static byte[] annexB(byte[] hvcC, List<byte[]> payloads) {
        var out = new ByteArrayOutputStream();
        var lengthSize = (hvcC[21] & 3) + 1;
        var arrays = hvcC[22] & 0xFF;
        var position = 23;
        for (var a = 0; a < arrays; a++) {
            var count = ((hvcC[position + 1] & 0xFF) << 8) | (hvcC[position + 2] & 0xFF);
            position += 3;
            for (var n = 0; n < count; n++) {
                var length = ((hvcC[position] & 0xFF) << 8) | (hvcC[position + 1] & 0xFF);
                position += 2;
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.write(hvcC, position, length);
                position += length;
            }
        }
        for (var payload : payloads) {
            var offset = 0;
            while (offset < payload.length) {
                var length = 0;
                for (var i = 0; i < lengthSize; i++) {
                    length = (length << 8) | (payload[offset + i] & 0xFF);
                }
                offset += lengthSize;
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.write(payload, offset, length);
                offset += length;
            }
        }
        return out.toByteArray();
    }

    /**
     * Decodes the last picture of an Annex B stream with ffmpeg.
     */
    static Planes decode(byte[] annexB, int frames, int width, int height, ChromaFormat format, int depth) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), annexB,
                List.of("-v", "error", "-f", "hevc", "-i", "pipe:0", "-f", "rawvideo", "-pix_fmt",
                        pixelFormat(format, depth), "-"));
        var bytes = depth == 8 ? 1 : 2;
        var luma = width * height;
        var chroma = format.hasChroma() ? format.chromaWidth(width) * format.chromaHeight(height) : 0;
        var frameSize = (luma + 2L * chroma) * bytes;
        if (raw.length != frameSize * frames) {
            throw new AssertionError("ffmpeg returned " + raw.length + " bytes for " + frames + " " + width + "x" + height
                    + " " + pixelFormat(format, depth) + " pictures");
        }
        return planes(raw, (int) (frameSize * (frames - 1)), width, height, format, depth);
    }

    /**
     * Decodes every picture of a file ffmpeg can demux (an image sequence) with ffmpeg.
     */
    static List<Planes> decodeFile(Path file, int width, int height, ChromaFormat format, int depth, boolean editList) {
        // the first stream of more than one frame is the sequence track; image items come as one-frame streams
        var streams = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1),
                List.of("-v", "error", "-show_entries", "stream=index,nb_frames", "-of", "csv=p=0", file.toString())));
        var stream = "0";
        for (var line : streams.strip().split("\\R")) {
            var fields = line.strip().split(",");
            if (fields.length == 2 && fields[1].matches("[0-9]+") && Integer.parseInt(fields[1]) > 1) {
                stream = fields[0];
                break;
            }
        }
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2),
                List.of("-v", "error", "-ignore_editlist", editList ? "0" : "1", "-i", file.toString(), "-map", "0:" + stream, "-f",
                        "rawvideo", "-pix_fmt", pixelFormat(format, depth), "-"));
        var bytes = depth == 8 ? 1 : 2;
        var luma = width * height;
        var chroma = format.hasChroma() ? format.chromaWidth(width) * format.chromaHeight(height) : 0;
        var frameSize = (int) ((luma + 2L * chroma) * bytes);
        if (raw.length % frameSize != 0) {
            throw new AssertionError("ffmpeg returned " + raw.length + " bytes, not a multiple of " + frameSize);
        }
        var frames = new ArrayList<Planes>();
        for (var offset = 0; offset < raw.length; offset += frameSize) {
            frames.add(planes(raw, offset, width, height, format, depth));
        }
        return frames;
    }

    private static Planes planes(byte[] raw, int start, int width, int height, ChromaFormat format, int depth) {
        var bytes = depth == 8 ? 1 : 2;
        var luma = width * height;
        var chroma = format.hasChroma() ? format.chromaWidth(width) * format.chromaHeight(height) : 0;
        var planes = new int[4][];
        var offset = start;
        for (var plane = 0; plane < (format.hasChroma() ? 3 : 1); plane++) {
            var size = plane == 0 ? luma : chroma;
            var samples = new int[size];
            for (var i = 0; i < size; i++, offset += bytes) {
                samples[i] = bytes == 1 ? raw[offset] & 0xFF : (raw[offset] & 0xFF) | ((raw[offset + 1] & 0xFF) << 8);
            }
            planes[plane] = samples;
        }
        return new Planes(width, height, format, depth, planes);
    }

    /**
     * Decodes one coded item, after the items it is predicted from, with ffmpeg.
     */
    static Planes item(MediaSource source, HeifFile file, HeifItem item, int width, int height, ChromaFormat format,
                       int depth) {
        var chain = new ArrayList<HeifItem>();
        for (var id : item.references("pred")) {
            chain.add(file.item(id));
        }
        chain.add(item);
        var payloads = new ArrayList<byte[]>();
        for (var coded : chain) {
            payloads.add(file.readItem(source, coded, MediaLimits.DEFAULT));
        }
        var hvcC = item.property(HeifProperty.HevcConfiguration.class).record();
        return decode(annexB(hvcC, payloads), chain.size(), width, height, format, depth);
    }

    /**
     * Reconstructs a grid of coded tiles with ffmpeg decoding every tile.
     */
    static Planes grid(MediaSource source, HeifFile file, HeifItem gridItem, ChromaFormat format, int depth) {
        var grid = HeifGrid.parse(file.readItem(source, gridItem, MediaLimits.DEFAULT));
        var ids = gridItem.references("dimg");
        var first = file.item(ids[0]).property(HeifProperty.SpatialExtent.class);
        var tileWidth = (int) first.width();
        var tileHeight = (int) first.height();
        var width = (int) grid.outputWidth();
        var height = (int) grid.outputHeight();
        var chromaWidth = format.chromaWidth(width);
        var out = new int[4][];
        out[0] = new int[width * height];
        if (format.hasChroma()) {
            out[1] = new int[chromaWidth * format.chromaHeight(height)];
            out[2] = new int[chromaWidth * format.chromaHeight(height)];
        }
        for (var index = 0; index < ids.length; index++) {
            var tile = item(source, file, file.item(ids[index]), tileWidth, tileHeight, format, depth);
            var x = (index % grid.columns()) * tileWidth;
            var y = (index / grid.columns()) * tileHeight;
            var w = Math.min(tileWidth, width - x);
            var h = Math.min(tileHeight, height - y);
            paste(tile.planes()[0], tileWidth, out[0], width, x, y, w, h);
            if (format.hasChroma()) {
                var cx = x >> format.shiftX();
                var cy = y >> format.shiftY();
                var cw = format.chromaWidth(x + w) - cx;
                var ch = format.chromaHeight(y + h) - cy;
                paste(tile.planes()[1], tile.chromaWidth(), out[1], chromaWidth, cx, cy, cw, ch);
                paste(tile.planes()[2], tile.chromaWidth(), out[2], chromaWidth, cx, cy, cw, ch);
            }
        }
        return new Planes(width, height, format, depth, out);
    }

    private static void paste(int[] src, int srcStride, int[] dst, int dstStride, int x, int y, int w, int h) {
        for (var row = 0; row < h; row++) {
            System.arraycopy(src, row * srcStride, dst, (y + row) * dstStride + x, w);
        }
    }
}

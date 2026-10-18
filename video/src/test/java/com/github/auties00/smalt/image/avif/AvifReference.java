package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifGeometry;
import com.github.auties00.smalt.internal.heif.HeifGrid;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reconstructs the primary image of an AVIF file with ffmpeg as the AV1 decoder: every coded
 * item is wrapped in an IVF stream and decoded by the ffmpeg on {@code PATH} (libaom or dav1d),
 * grids are stitched and the clean aperture is applied here, so the result is an independent
 * reference for {@link AvifDecoder} at the source bit depth.
 */
final class AvifReference {
    /**
     * Planes of one picture: luma, the two chroma planes (absent for monochrome) and alpha (absent
     * without alpha), each row-major without padding.
     */
    record Planes(int width, int height, ChromaFormat format, int depth, int[][] planes) {
        int chromaWidth() {
            return format.chromaWidth(width);
        }

        int chromaHeight() {
            return format.chromaHeight(height);
        }

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

    private AvifReference() {
    }

    /**
     * Returns the format of an {@code av1C} configuration.
     */
    static ChromaFormat formatOf(HeifProperty.Av1Configuration config) {
        if (config.monochrome()) {
            return ChromaFormat.MONOCHROME;
        }
        return config.subsamplingX() ? (config.subsamplingY() ? ChromaFormat.YUV420 : ChromaFormat.YUV422)
                : ChromaFormat.YUV444;
    }

    /**
     * Returns the ffmpeg pixel format of a layout and depth.
     */
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
     * Wraps one temporal unit into an IVF stream.
     */
    static byte[] ivf(byte[] payload, int width, int height) {
        var out = ByteBuffer.allocate(32 + 12 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[]{'D', 'K', 'I', 'F'}).putShort((short) 0).putShort((short) 32);
        out.put(new byte[]{'A', 'V', '0', '1'}).putShort((short) width).putShort((short) height);
        out.putInt(1).putInt(1).putInt(1).putInt(0);
        out.putInt(payload.length).putLong(0).put(payload);
        return out.array();
    }

    /**
     * Decodes one AV1 temporal unit with ffmpeg.
     */
    static Planes decode(byte[] payload, int width, int height, ChromaFormat format, int depth) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), ivf(payload, width, height),
                List.of("-v", "error", "-f", "ivf", "-i", "pipe:0", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt",
                        pixelFormat(format, depth), "-"));
        var bytes = depth == 8 ? 1 : 2;
        var luma = width * height;
        var chroma = format.hasChroma() ? format.chromaWidth(width) * format.chromaHeight(height) : 0;
        if (raw.length != (luma + 2L * chroma) * bytes) {
            throw new AssertionError("ffmpeg returned " + raw.length + " bytes for a " + width + "x" + height + " "
                    + pixelFormat(format, depth) + " picture");
        }
        var planes = new int[4][];
        var offset = 0;
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
     * Returns whether ffprobe reports an AV1 temporal unit as limited range.
     */
    static boolean isLimitedRange(byte[] payload, int width, int height) {
        var report = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), ivf(payload, width, height),
                List.of("-v", "error", "-f", "ivf", "-i", "pipe:0", "-show_entries", "stream=color_range", "-of", "csv=p=0")));
        return report.trim().equals("tv") || report.trim().equals("unknown");
    }

    /**
     * Expands a limited-range sample to full range as libavif's {@code avifLimitedToFullY} does.
     */
    static int limitedToFull(int value, int depth) {
        var max = (1 << depth) - 1;
        var low = 16 << (depth - 8);
        var span = 219 << (depth - 8);
        return Math.clamp(((value - low) * max + span / 2) / span, 0, max);
    }

    /**
     * Reconstructs the primary image of a file at its source depth, cropped by its clean aperture,
     * with its alpha plane when it has a single alpha item.
     */
    static Planes primary(byte[] data) {
        var source = MediaSource.of(data);
        var file = HeifFile.parse(source, MediaLimits.DEFAULT);
        var primary = file.primaryItem();
        var image = reconstruct(source, file, primary, false);
        for (var auxiliary : file.auxiliariesOf(primary.id())) {
            var type = auxiliary.property(HeifProperty.AuxiliaryType.class);
            if (type != null && type.isAlpha()) {
                var alpha = reconstruct(source, file, auxiliary, true);
                image.planes()[3] = alpha.planes()[0];
                break;
            }
        }
        var geometry = HeifGeometry.resolve(primary.properties(), image.width(), image.height());
        return crop(image, geometry.x(), geometry.y(), geometry.width(), geometry.height());
    }

    private static Planes reconstruct(MediaSource source, HeifFile file, HeifItem item, boolean alpha) {
        if (item.type().equals("av01")) {
            var config = item.property(HeifProperty.Av1Configuration.class);
            var extent = item.property(HeifProperty.SpatialExtent.class);
            var format = alpha ? ChromaFormat.MONOCHROME : formatOf(config);
            var decoded = decode(file.readItem(source, item, MediaLimits.DEFAULT), (int) extent.width(),
                    (int) extent.height(), alpha ? formatOf(config) : format, config.bitDepth());
            if (!alpha) {
                return decoded;
            }
            var luma = decoded.planes()[0];
            if (isLimitedRange(file.readItem(source, item, MediaLimits.DEFAULT), (int) extent.width(), (int) extent.height())) {
                for (var i = 0; i < luma.length; i++) {
                    luma[i] = limitedToFull(luma[i], decoded.depth());
                }
            }
            return new Planes(decoded.width(), decoded.height(), ChromaFormat.MONOCHROME, decoded.depth(),
                    new int[][]{luma, null, null, null});
        }
        var grid = HeifGrid.parse(file.readItem(source, item, MediaLimits.DEFAULT));
        var ids = item.references("dimg");
        var tiles = new ArrayList<Planes>();
        for (var id : ids) {
            tiles.add(reconstruct(source, file, file.item(id), alpha));
        }
        var first = tiles.getFirst();
        var width = (int) grid.outputWidth();
        var height = (int) grid.outputHeight();
        var format = first.format();
        var out = new Planes(width, height, format, first.depth(), new int[4][]);
        var planeCount = format.hasChroma() ? 3 : 1;
        for (var plane = 0; plane < planeCount; plane++) {
            var sx = plane == 0 ? 0 : format.shiftX();
            var sy = plane == 0 ? 0 : format.shiftY();
            var outWidth = plane == 0 ? width : format.chromaWidth(width);
            var outHeight = plane == 0 ? height : format.chromaHeight(height);
            var samples = new int[outWidth * outHeight];
            for (var t = 0; t < tiles.size(); t++) {
                var tile = tiles.get(t);
                var tileWidth = plane == 0 ? tile.width() : tile.chromaWidth();
                var tileHeight = plane == 0 ? tile.height() : tile.chromaHeight();
                var x0 = ((t % grid.columns()) * first.width()) >> sx;
                var y0 = ((t / grid.columns()) * first.height()) >> sy;
                for (var y = 0; y < tileHeight && y0 + y < outHeight; y++) {
                    for (var x = 0; x < tileWidth && x0 + x < outWidth; x++) {
                        samples[(y0 + y) * outWidth + x0 + x] = tile.planes()[plane][y * tileWidth + x];
                    }
                }
            }
            out.planes()[plane] = samples;
        }
        return out;
    }

    /**
     * Crops planes, turning subsampled chroma into 4:4:4 when the rectangle does not start on a
     * chroma sample.
     */
    static Planes crop(Planes image, int x, int y, int width, int height) {
        if (x == 0 && y == 0 && width == image.width() && height == image.height()) {
            return image;
        }
        var format = image.format();
        var aligned = (x & ((1 << format.shiftX()) - 1)) == 0 && (y & ((1 << format.shiftY()) - 1)) == 0;
        var outFormat = aligned ? format : ChromaFormat.YUV444;
        var planes = new int[4][];
        planes[0] = cropPlane(image.planes()[0], image.width(), x, y, width, height, 0, 0);
        if (image.planes()[3] != null) {
            planes[3] = cropPlane(image.planes()[3], image.width(), x, y, width, height, 0, 0);
        }
        if (format.hasChroma()) {
            for (var plane = 1; plane < 3; plane++) {
                if (aligned) {
                    planes[plane] = cropPlane(image.planes()[plane], image.chromaWidth(), x >> format.shiftX(),
                            y >> format.shiftY(), format.chromaWidth(width), format.chromaHeight(height), 0, 0);
                } else {
                    var out = new int[width * height];
                    for (var row = 0; row < height; row++) {
                        for (var col = 0; col < width; col++) {
                            out[row * width + col] = image.planes()[plane][((y + row) >> format.shiftY()) * image.chromaWidth()
                                    + ((x + col) >> format.shiftX())];
                        }
                    }
                    planes[plane] = out;
                }
            }
        }
        return new Planes(width, height, outFormat, image.depth(), planes);
    }

    private static int[] cropPlane(int[] plane, int stride, int x, int y, int width, int height, int unusedX, int unusedY) {
        var out = new int[width * height];
        for (var row = 0; row < height; row++) {
            System.arraycopy(plane, (y + row) * stride + x, out, row * width, width);
        }
        return out;
    }

    /**
     * Encodes planes as bytes for diagnostics.
     */
    static byte[] bytes(int[] plane) {
        var out = new ByteArrayOutputStream();
        for (var value : plane) {
            out.write(value);
        }
        return out.toByteArray();
    }
}

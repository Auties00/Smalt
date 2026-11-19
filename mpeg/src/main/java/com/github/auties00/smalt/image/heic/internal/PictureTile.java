package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.heif.DecodedTile;
import com.github.auties00.smalt.internal.heif.HeifPictures;
import com.github.auties00.smalt.internal.heif.HeifProperty;

/**
 * Presents a planar picture decoded by a codec other than a video codec (JPEG, JPEG 2000 or the
 * uncompressed format) as a {@link CodedFrame}.
 *
 * <p>An RGB picture is held as 4:4:4 planes with the identity matrix: green in the luma plane,
 * blue and red in the chroma planes. The alpha plane of the picture, if any, is the alpha the
 * frame carries itself.
 */
final class PictureTile implements CodedFrame {
    /**
     * The 8-bit picture, or {@code null}.
     */
    private final YuvPicture narrow;

    /**
     * The deep picture, or {@code null}.
     */
    private final Yuv16Picture wide;

    /**
     * Whether the planes hold RGB samples.
     */
    private final boolean rgb;

    /**
     * Whether the color is premultiplied by the alpha plane.
     */
    private final boolean premultiplied;

    /**
     * Wraps a planar picture.
     *
     * @param picture a {@link YuvPicture} or a {@link Yuv16Picture}
     * @param rgb whether the planes hold RGB samples with the identity matrix
     * @param premultiplied whether the color is premultiplied by the alpha plane
     */
    private PictureTile(Picture picture, boolean rgb, boolean premultiplied) {
        this.narrow = picture instanceof YuvPicture yuv ? yuv : null;
        this.wide = picture instanceof Yuv16Picture yuv ? yuv : null;
        this.rgb = rgb;
        this.premultiplied = premultiplied;
    }

    /**
     * Wraps a decoded picture, converting packed RGBA to 4:4:4 planes with the identity matrix.
     *
     * @param picture a {@link YuvPicture}, {@link Yuv16Picture}, {@link RgbaPicture} or
     * {@link Rgba16Picture}
     * @param premultiplied whether the color is premultiplied by the alpha
     * @return the frame
     * @throws com.github.auties00.smalt.MediaException.Unsupported if the picture is of another kind
     */
    static PictureTile of(Picture picture, boolean premultiplied) {
        return switch (picture) {
            case YuvPicture yuv -> new PictureTile(yuv, yuv.color().matrix() == MatrixCoefficients.IDENTITY,
                    premultiplied);
            case Yuv16Picture yuv -> new PictureTile(yuv, yuv.color().matrix() == MatrixCoefficients.IDENTITY,
                    premultiplied);
            case RgbaPicture rgba -> new PictureTile(planar(rgba), true, premultiplied);
            case Rgba16Picture rgba -> new PictureTile(planar(rgba), true, premultiplied);
            default -> throw new com.github.auties00.smalt.MediaException.Unsupported(FORMAT, "items decoding to "
                    + picture.getClass().getSimpleName());
        };
    }

    /**
     * Converts an 8-bit RGBA picture to 4:4:4 planes with the identity matrix, alpha included when
     * the picture has it.
     *
     * @param rgba the picture
     * @return the planes
     */
    private static YuvPicture planar(RgbaPicture rgba) {
        var width = rgba.width();
        var height = rgba.height();
        var g = new byte[width * height];
        var b = new byte[width * height];
        var r = new byte[width * height];
        var a = rgba.hasAlpha() ? new byte[width * height] : null;
        var pixels = rgba.pixels();
        for (var y = 0; y < height; y++) {
            var in = y * rgba.stride();
            var out = y * width;
            for (var x = 0; x < width; x++, in += 4, out++) {
                r[out] = pixels[in];
                g[out] = pixels[in + 1];
                b[out] = pixels[in + 2];
                if (a != null) {
                    a[out] = pixels[in + 3];
                }
            }
        }
        var color = Colors.MIAF_DEFAULT.withMatrix(MatrixCoefficients.IDENTITY);
        var builder = YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.YUV444).color(color)
                .luma(g, width).chroma(b, r, width);
        if (a != null) {
            builder.alpha(a, width);
        }
        return builder.build();
    }

    /**
     * Converts a 16-bit RGBA picture to 16-bit 4:4:4 planes with the identity matrix, alpha
     * included when the picture has it.
     *
     * @param rgba the picture
     * @return the planes
     */
    private static Yuv16Picture planar(Rgba16Picture rgba) {
        var width = rgba.width();
        var height = rgba.height();
        var g = new short[width * height];
        var b = new short[width * height];
        var r = new short[width * height];
        var a = rgba.hasAlpha() ? new short[width * height] : null;
        var pixels = rgba.pixels();
        for (var y = 0; y < height; y++) {
            var in = y * rgba.stride();
            var out = y * width;
            for (var x = 0; x < width; x++, in += 4, out++) {
                r[out] = pixels[in];
                g[out] = pixels[in + 1];
                b[out] = pixels[in + 2];
                if (a != null) {
                    a[out] = pixels[in + 3];
                }
            }
        }
        var color = Colors.MIAF_DEFAULT.withMatrix(MatrixCoefficients.IDENTITY);
        var builder = Yuv16Picture.builder().size(width, height).chromaFormat(ChromaFormat.YUV444).bitDepth(16)
                .color(color).luma(g, width).chroma(b, r, width);
        if (a != null) {
            builder.alpha(a, width);
        }
        return builder.build();
    }

    @Override
    public int width() {
        return narrow != null ? narrow.width() : wide.width();
    }

    @Override
    public int height() {
        return narrow != null ? narrow.height() : wide.height();
    }

    @Override
    public ChromaFormat format() {
        return narrow != null ? narrow.chromaFormat() : wide.chromaFormat();
    }

    @Override
    public int bitDepth() {
        return narrow != null ? 8 : wide.bitDepth();
    }

    @Override
    public ColorInfo color() {
        return narrow != null ? narrow.color() : wide.color();
    }

    @Override
    public boolean rgb() {
        return rgb;
    }

    @Override
    public boolean limitedAlpha(HeifProperty.ColorNclx nclx) {
        if (nclx != null) {
            return !nclx.fullRange();
        }
        return color().range() == ColorRange.LIMITED;
    }

    @Override
    public Picture lumaPicture() {
        if (narrow != null) {
            return HeifPictures.lumaPicture(narrow.y(), 0, narrow.yStride(), width(), height());
        }
        return HeifPictures.lumaPicture(wide.y(), 0, wide.yStride(), width(), height(), wide.bitDepth());
    }

    @Override
    public DecodedTile alpha() {
        if (narrow != null ? !narrow.hasAlpha() : !wide.hasAlpha()) {
            return null;
        }
        return new AlphaView();
    }

    @Override
    public boolean premultiplied() {
        return premultiplied && alpha() != null;
    }

    @Override
    public boolean eightBit() {
        return narrow != null;
    }

    @Override
    public byte[] plane8(int plane) {
        return switch (plane) {
            case 0 -> narrow.y();
            case 1 -> narrow.u();
            default -> narrow.v();
        };
    }

    @Override
    public short[] plane16(int plane) {
        return switch (plane) {
            case 0 -> wide.y();
            case 1 -> wide.u();
            default -> wide.v();
        };
    }

    @Override
    public int offset(int plane) {
        return 0;
    }

    @Override
    public int stride(int plane) {
        if (narrow != null) {
            return plane == 0 ? narrow.yStride() : narrow.uvStride();
        }
        return plane == 0 ? wide.yStride() : wide.uvStride();
    }

    @Override
    public int depth(int plane) {
        return bitDepth();
    }

    /**
     * Presents the alpha plane of the picture as plane 0 of a tile.
     */
    private final class AlphaView implements DecodedTile {
        @Override
        public boolean eightBit() {
            return narrow != null;
        }

        @Override
        public byte[] plane8(int plane) {
            return narrow.alpha();
        }

        @Override
        public short[] plane16(int plane) {
            return wide.alpha();
        }

        @Override
        public int offset(int plane) {
            return 0;
        }

        @Override
        public int stride(int plane) {
            return narrow != null ? narrow.alphaStride() : wide.alphaStride();
        }

        @Override
        public int depth(int plane) {
            return bitDepth();
        }
    }
}

package com.github.auties00.smalt.testkit.image;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Dumps;

import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Describes decoded pictures on one line each, with the CRC-32 of every plane's visible samples, in the
 * spirit of FFmpeg's {@code framecrc}: two pictures with the same line hold the same samples whatever
 * their strides.
 *
 * <p>Samples wider than a byte enter the CRC little-endian, and floating-point samples by their IEEE 754
 * bit patterns, so the line is exact for every picture type.
 */
public final class PictureDump {
    private PictureDump() {
        throw new AssertionError();
    }

    /**
     * Describes a picture: its type, size, depth and the CRC of each plane.
     *
     * @param picture the picture
     * @return the description, without a line terminator
     */
    public static String picture(Picture picture) {
        var text = new StringBuilder();
        text.append(picture.width()).append('x').append(picture.height()).append(' ');
        switch (picture) {
            case RgbaPicture rgba -> text.append("rgba8 alpha=").append(rgba.hasAlpha()).append(" crc=")
                    .append(bytes(rgba.pixels(), rgba.stride(), rgba.width() * 4, rgba.height()));
            case Rgba16Picture rgba -> text.append("rgba16 depth=").append(rgba.bitDepth()).append(" alpha=")
                    .append(rgba.hasAlpha()).append(" crc=")
                    .append(shorts(rgba.pixels(), rgba.stride(), rgba.width() * 4, rgba.height()));
            case YuvPicture yuv -> {
                text.append("yuv8 ").append(yuv.chromaFormat()).append(' ').append(color(yuv.color())).append(" y=")
                        .append(bytes(yuv.y(), yuv.yStride(), yuv.width(), yuv.height()));
                if (yuv.u() != null) {
                    text.append(" u=").append(bytes(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight()))
                            .append(" v=").append(bytes(yuv.v(), yuv.uvStride(), yuv.chromaWidth(),
                                    yuv.chromaHeight()));
                }
                if (yuv.alpha() != null) {
                    text.append(" a=").append(bytes(yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height()));
                }
            }
            case Yuv16Picture yuv -> {
                text.append("yuv16 depth=").append(yuv.bitDepth()).append(' ').append(yuv.chromaFormat()).append(' ')
                        .append(color(yuv.color())).append(" y=").append(shorts(yuv.y(), yuv.yStride(), yuv.width(),
                                yuv.height()));
                if (yuv.u() != null) {
                    text.append(" u=").append(shorts(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight()))
                            .append(" v=").append(shorts(yuv.v(), yuv.uvStride(), yuv.chromaWidth(),
                                    yuv.chromaHeight()));
                }
                if (yuv.alpha() != null) {
                    text.append(" a=").append(shorts(yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height()));
                }
            }
            case Channel8Picture channels -> text.append("channels8 ").append(channels.layout()).append(" crc=")
                    .append(bytes(channels.pixels(), channels.stride(), channels.width() * channels.channelCount(),
                            channels.height()));
            case Channel16Picture channels -> text.append("channels16 depth=").append(channels.bitDepth()).append(' ')
                    .append(channels.layout()).append(" crc=").append(shorts(channels.pixels(), channels.stride(),
                            channels.width() * channels.channelCount(), channels.height()));
            case ChannelFloatPicture channels -> text.append("channelsf ").append(channels.layout()).append(" crc=")
                    .append(floats(channels.pixels(), channels.stride(), channels.width() * channels.channelCount(),
                            channels.height()));
        }
        return text.toString();
    }

    /**
     * Describes a decoded image: the picture line, then the format, orientation, source size and the
     * sizes and CRCs of its ICC, EXIF and XMP blocks, its color profile and, when the file declares one, its
     * pixel density.
     *
     * @param image the decoded image
     * @return the description, one property per line, with a final line terminator
     */
    public static String image(DecodedImage image) {
        return "picture " + picture(image.picture()) + "\n"
                + "  format=" + image.format() + " orientation=" + image.orientation() + " source="
                + image.sourceWidth() + "x" + image.sourceHeight() + "\n"
                + "  icc=" + block(image.iccProfile()) + " exif=" + block(image.exif()) + " xmp=" + block(image.xmp())
                + "\n"
                + "  profile=" + image.colorProfile().map(Object::toString).orElse("none") + "\n"
                + image.density().map(PictureDump::density).orElse("");
    }

    private static String density(PixelDensity density) {
        return "  density=" + density.unit() + " " + density.horizontal() + "x" + density.vertical() + "\n";
    }

    private static String color(ColorInfo color) {
        var text = new StringBuilder("color=").append(color.matrix()).append('/').append(color.range()).append('/')
                .append(color.primaries()).append('/').append(color.transfer());
        if (color.chromaSiting() != ChromaSiting.UNSPECIFIED) {
            text.append('/').append(color.chromaSiting());
        }
        color.masteringDisplay().ifPresent(display -> text.append(" mastering=").append(display));
        color.contentLightLevel().ifPresent(level -> text.append(" light=").append(level));
        return text.toString();
    }

    private static String block(Optional<byte[]> block) {
        return block.map(bytes -> bytes.length + ":" + Dumps.crc32(bytes)).orElse("none");
    }

    private static String bytes(byte[] plane, int stride, int rowLength, int rows) {
        var crc = new CRC32();
        for (var row = 0; row < rows; row++) {
            crc.update(plane, row * stride, rowLength);
        }
        return hex(crc);
    }

    private static String shorts(short[] plane, int stride, int rowLength, int rows) {
        var crc = new CRC32();
        var buffer = new byte[rowLength * 2];
        for (var row = 0; row < rows; row++) {
            var base = row * stride;
            for (var i = 0; i < rowLength; i++) {
                var value = plane[base + i];
                buffer[2 * i] = (byte) value;
                buffer[2 * i + 1] = (byte) (value >> 8);
            }
            crc.update(buffer);
        }
        return hex(crc);
    }

    private static String floats(float[] plane, int stride, int rowLength, int rows) {
        var crc = new CRC32();
        var buffer = new byte[rowLength * 4];
        for (var row = 0; row < rows; row++) {
            var base = row * stride;
            for (var i = 0; i < rowLength; i++) {
                var bits = Float.floatToRawIntBits(plane[base + i]);
                buffer[4 * i] = (byte) bits;
                buffer[4 * i + 1] = (byte) (bits >> 8);
                buffer[4 * i + 2] = (byte) (bits >> 16);
                buffer[4 * i + 3] = (byte) (bits >> 24);
            }
            crc.update(buffer);
        }
        return hex(crc);
    }

    private static String hex(CRC32 crc) {
        return "%08x".formatted(crc.getValue());
    }
}

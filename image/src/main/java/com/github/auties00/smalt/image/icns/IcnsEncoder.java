package com.github.auties00.smalt.image.icns;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.png.PngEncoderOptions;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.io.MediaSink;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Encodes pictures as Apple icon image (ICNS) files.
 *
 * <p>Each image is square and of one of the sizes an icon family holds (16, 18, 24, 32, 36, 48, 64, 128, 256, 512 and
 * 1024 pixels), and is stored in the element type {@link IcnsEncoderOptions#encoding()} selects for its size: an
 * ARGB image of four run-length coded planes, a 24-bit image of three run-length coded planes with an 8-bit mask
 * element, or a PNG file. A single picture becomes one image of its own size, or, with
 * {@link IcnsEncoderOptions#sizes()}, one image per size: the picture is scaled to fit the square (area averaging
 * when shrinking, bicubic when enlarging) and centered on a transparent background. A list of pictures becomes one
 * image per picture. The elements follow a table of contents ({@code TOC }) in increasing size. Pixels are written
 * as 8-bit sRGB; a 24-bit image with a mask keeps the color of transparent pixels.
 *
 * <p>This class is thread-safe.
 */
public final class IcnsEncoder {
    private IcnsEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture as a one-image icon family with the {@link IcnsEncoderOptions#defaults()}.
     *
     * @param picture the picture, square and of a size an icon family holds
     * @return the complete file
     * @throws IllegalArgumentException if the picture is not square or of a size an icon family holds
     * @see #encode(Picture, IcnsEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, IcnsEncoderOptions.defaults());
    }

    /**
     * Encodes a picture as an icon family.
     *
     * @param picture the picture; square and of a size an icon family holds unless the options set sizes
     * @param options the element types and the image sizes
     * @return the complete file
     * @throws IllegalArgumentException if the options set no sizes and the picture is not square or of a size an
     * icon family holds
     */
    public static byte[] encode(Picture picture, IcnsEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(picture, options, sink);
        return sink.toByteArray();
    }

    /**
     * Encodes a picture as an icon family and writes it to a sink.
     *
     * <p>The bytes written are those {@link #encode(Picture, IcnsEncoderOptions)} returns.
     *
     * @param picture the picture; square and of a size an icon family holds unless the options set sizes
     * @param options the element types and the image sizes
     * @param sink the output, written from its position; not closed
     * @throws IllegalArgumentException if the options set no sizes and the picture is not square or of a size an
     * icon family holds
     * @throws com.github.auties00.smalt.MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, IcnsEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        var rgba = ImageOps.toRgba(picture);
        var sizes = options.sizes();
        var images = new ArrayList<RgbaPicture>();
        if (sizes.length == 0) {
            images.add(rgba);
        } else {
            for (var size : sizes) {
                images.add(fit(rgba, size));
            }
        }
        write(images, options.encoding(), sink);
    }

    /**
     * Encodes pictures as an icon family of several images.
     *
     * @param pictures the pictures, one image each, each square, of a size an icon family holds, and of a size no
     * other picture has
     * @param options the element types; the sizes must be empty
     * @return the complete file
     * @throws NullPointerException if a picture is {@code null}
     * @throws IllegalArgumentException if {@code pictures} is empty, a picture is not square or of a size an icon
     * family holds, two pictures have the same size, or the options set sizes
     */
    public static byte[] encode(List<? extends Picture> pictures, IcnsEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(pictures, options, sink);
        return sink.toByteArray();
    }

    /**
     * Encodes pictures as an icon family of several images and writes it to a sink.
     *
     * @param pictures the pictures, one image each, each square, of a size an icon family holds, and of a size no
     * other picture has
     * @param options the element types; the sizes must be empty
     * @param sink the output, written from its position; not closed
     * @throws NullPointerException if a picture is {@code null}
     * @throws IllegalArgumentException if {@code pictures} is empty, a picture is not square or of a size an icon
     * family holds, two pictures have the same size, or the options set sizes
     * @throws com.github.auties00.smalt.MediaException.Io if writing the sink fails
     */
    public static void encode(List<? extends Picture> pictures, IcnsEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(pictures, "pictures");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        if (pictures.isEmpty()) {
            throw new IllegalArgumentException("an ICNS file needs at least one image");
        }
        if (options.sizes().length > 0) {
            throw new IllegalArgumentException("sizes apply to a single picture, not to a list");
        }
        var images = new ArrayList<RgbaPicture>();
        for (var picture : pictures) {
            images.add(ImageOps.toRgba(Objects.requireNonNull(picture, "picture")));
        }
        write(images, options.encoding(), sink);
    }

    /**
     * Scales a picture into a square image.
     *
     * @param picture the source pixels
     * @param size the side of the image in pixels
     * @return the image
     */
    static RgbaPicture fit(RgbaPicture picture, int size) {
        if (picture.width() == size && picture.height() == size) {
            return picture;
        }
        var scale = Math.min((double) size / picture.width(), (double) size / picture.height());
        var filter = scale < 1 ? ResizeFilter.AREA : ResizeFilter.BICUBIC;
        return (RgbaPicture) ImageOps.fitInto(picture, size, size, filter, 0);
    }

    /**
     * Writes the table of contents and the elements.
     *
     * @param images the square images to store, one element each
     * @param encoding the element types
     * @param sink the output
     * @throws IllegalArgumentException if an image is not square or of a size an icon family holds, or two images
     * have the same size
     */
    private static void write(List<RgbaPicture> images, IcnsEncoderOptions.Encoding encoding, MediaSink sink) {
        var seen = new HashSet<Integer>();
        for (var image : images) {
            if (image.width() != image.height() || !IcnsEncoderOptions.supported(image.width())) {
                throw new IllegalArgumentException("an ICNS image is square and of 16, 18, 24, 32, 36, 48, 64, 128, "
                        + "256, 512 or 1024 pixels, not " + image.width() + "x" + image.height());
            }
            if (!seen.add(image.width())) {
                throw new IllegalArgumentException("two ICNS images have " + image.width() + " pixels");
            }
        }
        var sorted = new ArrayList<>(images);
        sorted.sort(Comparator.comparingInt(RgbaPicture::width));
        var codes = new ArrayList<String>();
        var bodies = new ArrayList<byte[]>();
        for (var image : sorted) {
            element(tight(image), encoding, codes, bodies);
        }
        var total = 8L + 8 + 8L * codes.size();
        for (var body : bodies) {
            total += 8 + body.length;
        }
        if (total > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("the ICNS file would exceed 4 GiB");
        }
        var header = new byte[16 + 8 * codes.size()];
        header[0] = 'i';
        header[1] = 'c';
        header[2] = 'n';
        header[3] = 's';
        ByteArrays.put32be(header, 4, (int) total);
        header[8] = 'T';
        header[9] = 'O';
        header[10] = 'C';
        header[11] = ' ';
        ByteArrays.put32be(header, 12, 8 + 8 * codes.size());
        for (var i = 0; i < codes.size(); i++) {
            System.arraycopy(codes.get(i).getBytes(StandardCharsets.ISO_8859_1), 0, header, 16 + 8 * i, 4);
            ByteArrays.put32be(header, 20 + 8 * i, 8 + bodies.get(i).length);
        }
        sink.write(header);
        var elementHeader = new byte[8];
        for (var i = 0; i < codes.size(); i++) {
            System.arraycopy(codes.get(i).getBytes(StandardCharsets.ISO_8859_1), 0, elementHeader, 0, 4);
            ByteArrays.put32be(elementHeader, 4, 8 + bodies.get(i).length);
            sink.write(elementHeader);
            sink.write(bodies.get(i));
        }
    }

    /**
     * Codes one image as its element, or its image and mask elements.
     *
     * @param image the image, with a tight stride
     * @param encoding the element types
     * @param codes the element types written, appended to
     * @param bodies the element data written, appended to
     */
    private static void element(RgbaPicture image, IcnsEncoderOptions.Encoding encoding, List<String> codes,
                                List<byte[]> bodies) {
        var size = image.width();
        var rgb = switch (encoding) {
            case AUTO -> size == 48 ? "ih32" : null;
            case PNG -> null;
            case RLE -> switch (size) {
                case 16 -> "is32";
                case 32 -> "il32";
                case 48 -> "ih32";
                case 128 -> "it32";
                default -> null;
            };
        };
        var pixels = image.pixels();
        var count = size * size;
        if (rgb != null) {
            var out = new ByteArrayOutputStream();
            if (rgb.equals("it32")) {
                out.writeBytes(new byte[4]);
            }
            for (var channel = 0; channel < 3; channel++) {
                IcnsElements.pack(pixels, channel, count, out);
            }
            codes.add(rgb);
            bodies.add(out.toByteArray());
            var mask = new byte[count];
            for (var i = 0; i < count; i++) {
                mask[i] = pixels[i * 4 + 3];
            }
            codes.add(IcnsElements.of(rgb).mask());
            bodies.add(mask);
            return;
        }
        if (encoding == IcnsEncoderOptions.Encoding.AUTO && (size == 16 || size == 32)) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(new byte[]{'A', 'R', 'G', 'B'});
            for (var channel : new int[]{3, 0, 1, 2}) {
                IcnsElements.pack(pixels, channel, count, out);
            }
            codes.add(size == 16 ? "ic04" : "ic05");
            bodies.add(out.toByteArray());
            return;
        }
        codes.add(switch (size) {
            case 16 -> "icp4";
            case 18 -> "icsb";
            case 24 -> "sb24";
            case 32 -> "icp5";
            case 36 -> "icsB";
            case 48 -> "icp6";
            case 64 -> "ic12";
            case 128 -> "ic07";
            case 256 -> "ic08";
            case 512 -> "ic09";
            default -> "ic10";
        });
        bodies.add(PngEncoder.encode(image, PngEncoderOptions.defaults()));
    }

    /**
     * Returns a picture whose rows follow each other without padding and whose alpha is meaningful.
     *
     * @param picture the image to prepare
     * @return {@code picture} itself when its stride is tight and it has alpha, a copy otherwise, opaque when
     * {@code picture} has no alpha
     */
    private static RgbaPicture tight(RgbaPicture picture) {
        var row = picture.width() * 4;
        if (picture.stride() == row && picture.hasAlpha()) {
            return picture;
        }
        var copy = RgbaPicture.allocate(picture.width(), picture.height(), true);
        var target = copy.pixels();
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.pixels(), y * picture.stride(), target, y * row, row);
        }
        if (!picture.hasAlpha()) {
            for (var i = 3; i < target.length; i += 4) {
                target[i] = (byte) 0xFF;
            }
        }
        return copy;
    }
}

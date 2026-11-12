package com.github.auties00.smalt.image.pnm;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.internal.raster.RasterInput;
import com.github.auties00.smalt.image.internal.raster.RasterOutput;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Encodes pictures as Netpbm images (PBM, PGM, PPM and PAM) and portable float maps (PFM and PHM).
 *
 * <p>Samples are written as the picture holds them, without color conversion:
 * <ul>
 *   <li>PBM writes 1 (black) for a pixel whose BT.601 luma, {@code (299 R + 587 G + 114 B + 500) / 1000} of the
 *       8-bit samples, is below 128;</li>
 *   <li>PGM writes gray samples, the luma of a color picture;</li>
 *   <li>PPM writes red, green and blue, a gray picture's samples replicated;</li>
 *   <li>PAM writes the channels of a gray, gray and alpha, RGB, RGBA, CMYK or CMYK and alpha channel picture with
 *       their tuple type, the channels of another channel picture without one, and any other picture as
 *       {@code RGB} or {@code RGB_ALPHA} as it reports alpha;</li>
 *   <li>PFM and PHM write a gray picture as gray and any other as RGB, the values of a
 *       {@link ChannelFloatPicture} as they are and integer samples divided by their largest value, rows bottom to
 *       top; alpha is dropped.</li>
 * </ul>
 * Integer samples are scaled to maxval from their 16-bit form (8-bit samples widened as {@code v * 257}) as
 * {@code (v * maxval + 32767) / 65535}, so a maxval of 255 keeps 8-bit samples and 65535 keeps 16-bit ones exactly.
 * PBM, PGM and PPM drop alpha. The output decodes with {@link PnmDecoder} to the samples written.
 *
 * <p>This class is thread-safe.
 */
public final class PnmEncoder {
    /**
     * The longest line of a plain raster, as the Netpbm specification recommends.
     */
    private static final int PLAIN_LINE = 70;

    private PnmEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture with the {@link PnmEncoderOptions#defaults()}.
     *
     * @param picture the picture to encode
     * @return the complete file
     * @see #encode(Picture, PnmEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, PnmEncoderOptions.defaults());
    }

    /**
     * Encodes a picture.
     *
     * @param picture the picture to encode
     * @param options the variant, the raster form, the maxval and the float map byte order
     * @return the complete file
     * @throws IllegalArgumentException if the picture is CMYK or has ink channels and the variant is not
     * {@link PnmVariant#PAM}, which would need a color conversion
     */
    public static byte[] encode(Picture picture, PnmEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(picture, options, sink);
        return sink.toByteArray();
    }

    /**
     * Encodes a picture and writes it to a sink.
     *
     * <p>The bytes written are those {@link #encode(Picture, PnmEncoderOptions)} returns.
     *
     * @param picture the picture to encode
     * @param options the variant, the raster form, the maxval and the float map byte order
     * @param sink the output, written from its position; not closed
     * @throws IllegalArgumentException if the picture is CMYK or has ink channels and the variant is not
     * {@link PnmVariant#PAM}
     * @throws com.github.auties00.smalt.MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, PnmEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        try (var _ = ParallelBands.operation()) {
            var variant = options.variant().orElseGet(() -> automatic(picture));
            if (variant != PnmVariant.PAM && picture instanceof ChannelPicture channels
                    && !RasterOutput.plain(channels.layout()) && channels.layout().colorChannels() != 1
                    && !channels.layout().colorOnly().equals(PixelLayout.RGB)) {
                throw new IllegalArgumentException("a " + channels.layout().colorModel() + " picture needs a color"
                        + " conversion to be written as " + variant);
            }
            var writer = new ByteWriter(1024);
            switch (variant) {
                case PFM, PHM -> floats(picture, variant, options, writer);
                case PAM -> pam(picture, options, writer);
                default -> classic(picture, variant, options, writer);
            }
            writer.writeTo(sink);
        }
    }

    /**
     * Chooses the variant of a picture as {@link PnmEncoderOptions#variant()} describes.
     */
    private static PnmVariant automatic(Picture picture) {
        if (picture instanceof ChannelFloatPicture) {
            return PnmVariant.PFM;
        }
        if (picture.hasAlpha()) {
            return PnmVariant.PAM;
        }
        if (picture instanceof ChannelPicture channels && !RasterOutput.plain(channels.layout())) {
            return PnmVariant.PAM;
        }
        return RasterInput.isGray(picture) ? PnmVariant.PGM : PnmVariant.PPM;
    }

    /**
     * Returns the maxval of an integer raster.
     */
    private static int maxval(Picture picture, PnmEncoderOptions options) {
        return options.maxval().orElse(picture.bitDepth() > 8 ? 65535 : 255);
    }

    /**
     * Writes a PBM, PGM or PPM image.
     */
    private static void classic(Picture picture, PnmVariant variant, PnmEncoderOptions options, ByteWriter writer) {
        var plain = options.plain();
        var width = picture.width();
        var height = picture.height();
        var magic = switch (variant) {
            case PBM -> plain ? '1' : '4';
            case PGM -> plain ? '2' : '5';
            default -> plain ? '3' : '6';
        };
        var samples = RasterInput.samples(picture, variant != PnmVariant.PPM, false);
        var pixels = samples.pixels();
        if (variant == PnmVariant.PBM) {
            ascii(writer, "P" + magic + "\n" + width + " " + height + "\n");
            if (plain) {
                var line = 0;
                for (var index = 0; index < pixels.length; index++) {
                    writer.u8(black(pixels[index]) ? '1' : '0');
                    if (++line == PLAIN_LINE || index % width == width - 1) {
                        writer.u8('\n');
                        line = 0;
                    }
                }
                return;
            }
            var row = new byte[(width + 7) / 8];
            for (var y = 0; y < height; y++) {
                java.util.Arrays.fill(row, (byte) 0);
                for (var x = 0; x < width; x++) {
                    if (black(pixels[y * width + x])) {
                        row[x >>> 3] |= (byte) (0x80 >>> (x & 7));
                    }
                }
                writer.bytes(row);
            }
            return;
        }
        var maxval = maxval(picture, options);
        ascii(writer, "P" + magic + "\n" + width + " " + height + "\n" + maxval + "\n");
        if (plain) {
            plainSamples(writer, pixels, maxval, width * samples.layout().count());
        } else {
            rawSamples(writer, pixels, maxval);
        }
    }

    /**
     * Writes a PAM image.
     */
    private static void pam(Picture picture, PnmEncoderOptions options, ByteWriter writer) {
        Channel16Picture samples;
        String tuple;
        if (picture instanceof ChannelPicture channels && !RasterOutput.plain(channels.layout())) {
            var wide = ImageOps.toChannel16(picture);
            samples = wide.stride() == wide.width() * wide.layout().count() ? wide
                    : Channel16Picture.of(wide.width(), wide.height(), wide.width() * wide.layout().count(),
                    tight(wide), wide.layout());
            var layout = wide.layout();
            tuple = layout.equals(PixelLayout.CMYK) ? "CMYK" : layout.equals(PixelLayout.CMYKA) ? "CMYK_ALPHA" : null;
        } else {
            var gray = RasterInput.isGray(picture);
            samples = RasterInput.samples(picture, gray, true);
            var alpha = samples.layout().hasAlpha();
            tuple = (gray ? "GRAYSCALE" : "RGB") + (alpha ? "_ALPHA" : "");
        }
        var maxval = maxval(picture, options);
        if (maxval == 1 && "GRAYSCALE".equals(tuple)) {
            tuple = "BLACKANDWHITE";
        } else if (maxval == 1 && "GRAYSCALE_ALPHA".equals(tuple)) {
            tuple = "BLACKANDWHITE_ALPHA";
        }
        var header = new StringBuilder("P7\nWIDTH ").append(samples.width()).append("\nHEIGHT ")
                .append(samples.height()).append("\nDEPTH ").append(samples.layout().count()).append("\nMAXVAL ")
                .append(maxval).append('\n');
        if (tuple != null) {
            header.append("TUPLTYPE ").append(tuple).append('\n');
        }
        header.append("ENDHDR\n");
        ascii(writer, header.toString());
        rawSamples(writer, samples.pixels(), maxval);
    }

    /**
     * Writes a PFM or PHM image.
     */
    private static void floats(Picture picture, PnmVariant variant, PnmEncoderOptions options, ByteWriter writer) {
        var gray = RasterInput.isGray(picture);
        var samples = RasterInput.floats(picture, gray, false);
        var width = samples.width();
        var height = samples.height();
        var little = options.littleEndian();
        var magic = variant == PnmVariant.PFM ? (gray ? "Pf" : "PF") : (gray ? "Ph" : "PH");
        ascii(writer, magic + "\n" + width + " " + height + "\n" + (little ? "-1.0" : "1.0") + "\n");
        var rowSamples = width * samples.layout().count();
        var pixels = samples.pixels();
        for (var y = height - 1; y >= 0; y--) {
            var base = y * rowSamples;
            for (var x = 0; x < rowSamples; x++) {
                var value = pixels[base + x];
                if (variant == PnmVariant.PHM) {
                    var bits = Float.floatToFloat16(value);
                    if (little) {
                        writer.u16le(bits & 0xFFFF);
                    } else {
                        writer.u16be(bits & 0xFFFF);
                    }
                } else {
                    var bits = Float.floatToRawIntBits(value) & 0xFFFFFFFFL;
                    if (little) {
                        writer.u32le(bits);
                    } else {
                        writer.u32be(bits);
                    }
                }
            }
        }
    }

    /**
     * {@return whether a 16-bit gray sample is written as a black PBM pixel}
     */
    private static boolean black(short sample) {
        return ((sample & 0xFFFF) * 255 + 32767) / 65535 < 128;
    }

    /**
     * {@return a 16-bit sample scaled to maxval}
     */
    private static int scale(short sample, int maxval) {
        return (int) (((sample & 0xFFFF) * (long) maxval + 32767) / 65535);
    }

    /**
     * Writes raw samples, one byte each when maxval is below 256 and two big-endian bytes otherwise.
     */
    private static void rawSamples(ByteWriter writer, short[] pixels, int maxval) {
        if (maxval < 256) {
            var row = new byte[pixels.length];
            for (var index = 0; index < pixels.length; index++) {
                row[index] = (byte) scale(pixels[index], maxval);
            }
            writer.bytes(row);
            return;
        }
        var row = new byte[pixels.length * 2];
        for (var index = 0; index < pixels.length; index++) {
            var value = scale(pixels[index], maxval);
            row[2 * index] = (byte) (value >>> 8);
            row[2 * index + 1] = (byte) value;
        }
        writer.bytes(row);
    }

    /**
     * Writes plain samples, rows starting new lines and no line longer than {@link #PLAIN_LINE} characters.
     */
    private static void plainSamples(ByteWriter writer, short[] pixels, int maxval, int rowSamples) {
        var line = 0;
        for (var index = 0; index < pixels.length; index++) {
            var text = Integer.toString(scale(pixels[index], maxval));
            if (line > 0 && line + 1 + text.length() > PLAIN_LINE) {
                writer.u8('\n');
                line = 0;
            } else if (line > 0) {
                writer.u8(' ');
                line++;
            }
            ascii(writer, text);
            line += text.length();
            if (index % rowSamples == rowSamples - 1) {
                writer.u8('\n');
                line = 0;
            }
        }
    }

    /**
     * {@return the samples of a channel picture without row padding}
     */
    private static short[] tight(Channel16Picture picture) {
        var rowSamples = picture.width() * picture.layout().count();
        var result = new short[rowSamples * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.pixels(), y * picture.stride(), result, y * rowSamples, rowSamples);
        }
        return result;
    }

    /**
     * Writes ASCII text.
     */
    private static void ascii(ByteWriter writer, String text) {
        writer.bytes(text.getBytes(StandardCharsets.US_ASCII));
    }
}

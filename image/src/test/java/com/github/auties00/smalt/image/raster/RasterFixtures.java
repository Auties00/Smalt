package com.github.auties00.smalt.image.raster;

import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.image.RasterTesting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synthetic pictures and ffmpeg round trips shared by the tests of the simple raster formats.
 */
public final class RasterFixtures {
    private static final AtomicInteger FILES = new AtomicInteger();

    private RasterFixtures() {
    }

    /** A picture of gradients, flat areas and noise; alpha varies when asked. */
    public static RgbaPicture rgba(int width, int height, long seed, boolean alpha) {
        var random = new Random(seed);
        var pixels = new byte[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = (y * width + x) * 4;
                var zone = (x * 4 / width) + (y * 2 / height) * 4;
                int r;
                int g;
                int b;
                switch (zone % 4) {
                    case 0 -> {
                        r = x * 255 / Math.max(1, width - 1);
                        g = y * 255 / Math.max(1, height - 1);
                        b = 128;
                    }
                    case 1 -> {
                        r = 200;
                        g = 30;
                        b = 90;
                    }
                    case 2 -> {
                        r = random.nextInt(256);
                        g = random.nextInt(256);
                        b = random.nextInt(256);
                    }
                    default -> {
                        var v = ((x + y) & 1) * 255;
                        r = v;
                        g = 255 - v;
                        b = (x * 7) & 0xFF;
                    }
                }
                pixels[at] = (byte) r;
                pixels[at + 1] = (byte) g;
                pixels[at + 2] = (byte) b;
                pixels[at + 3] = alpha ? (byte) ((x * 37 + y * 11) & 0xFF) : (byte) 255;
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, alpha);
    }

    /** A 16-bit picture of gradients and noise. */
    public static Rgba16Picture rgba16(int width, int height, long seed, boolean alpha) {
        var random = new Random(seed);
        var pixels = new short[width * height * 4];
        for (var index = 0; index < width * height; index++) {
            var x = index % width;
            var y = index / width;
            pixels[4 * index] = (short) (x * 65535 / Math.max(1, width - 1));
            pixels[4 * index + 1] = (short) random.nextInt(65536);
            pixels[4 * index + 2] = (short) (y * 65535 / Math.max(1, height - 1));
            pixels[4 * index + 3] = alpha ? (short) random.nextInt(65536) : (short) 0xFFFF;
        }
        return Rgba16Picture.of(width, height, width * 4, pixels, alpha);
    }

    /** A 16-bit gray picture. */
    public static Channel16Picture gray16(int width, int height, long seed) {
        var random = new Random(seed);
        var picture = Channel16Picture.allocate(width, height, PixelLayout.GRAY);
        var pixels = picture.pixels();
        for (var index = 0; index < pixels.length; index++) {
            pixels[index] = (short) ((index % 3 == 0) ? random.nextInt(65536) : index * 65535 / pixels.length);
        }
        return picture;
    }

    /** A floating-point picture of values from 0 to about 40, negative values and zero included. */
    public static ChannelFloatPicture floats(int width, int height, PixelLayout layout, long seed) {
        var random = new Random(seed);
        var picture = ChannelFloatPicture.allocate(width, height, layout);
        var pixels = picture.pixels();
        for (var index = 0; index < pixels.length; index++) {
            pixels[index] = switch (index % 5) {
                case 0 -> 0f;
                case 1 -> random.nextFloat();
                case 2 -> random.nextFloat() * 40f;
                case 3 -> (float) Math.scalb(random.nextFloat(), -random.nextInt(20));
                default -> index * 0.01f;
            };
        }
        return picture;
    }

    /** {@return tightly packed 8-bit RGBA written as ffmpeg's raw rgba input} */
    public static byte[] raw(RgbaPicture picture) {
        return RasterTesting.tight(picture);
    }

    /**
     * Encodes raw frames with ffmpeg and returns the file.
     *
     * @param raw the raw input
     * @param inputFormat the ffmpeg pixel format of the raw input
     * @param width the width
     * @param height the height
     * @param extension the output file extension, which picks the muxer
     * @param arguments the output arguments, such as {@code -pix_fmt gray -c:v pgm}
     */
    public static byte[] ffmpegEncode(byte[] raw, String inputFormat, int width, int height, String extension,
                                      List<String> arguments) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var id = FILES.incrementAndGet();
        var input = RasterTesting.write("raster-in-" + id + ".raw", raw);
        var output = RasterTesting.write("raster-out-" + id + "." + extension, new byte[0]);
        var command = new ArrayList<>(List.of("-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", inputFormat, "-s",
                width + "x" + height, "-i", input.toString(), "-frames:v", "1"));
        command.addAll(arguments);
        command.add("-update");
        command.add("1");
        command.add(output.toString());
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), command);
        try {
            return java.nio.file.Files.readAllBytes(output);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    /** Decodes a file with ffmpeg to raw frames of a pixel format. */
    public static byte[] ffmpegDecode(byte[] file, String extension, String pixelFormat) {
        return ffmpegDecode(file, extension, List.of(), pixelFormat);
    }

    /** Decodes a file with ffmpeg, extra input arguments placed before {@code -i}, to raw frames. */
    public static byte[] ffmpegDecode(byte[] file, String extension, List<String> inputArguments, String pixelFormat) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var path = RasterTesting.write("raster-dec-" + FILES.incrementAndGet() + "." + extension, file);
        var command = new ArrayList<>(List.of("-v", "error"));
        command.addAll(inputArguments);
        command.addAll(List.of("-i", path.toString(), "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", pixelFormat,
                "-"));
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), command);
    }

    /** {@return little-endian 32-bit floats} */
    public static float[] floatsLe(byte[] data) {
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        var out = new float[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    /** {@return little-endian 16-bit samples} */
    public static short[] shortsLe(byte[] data) {
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        var out = new short[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    /** {@return interleaved samples of ffmpeg's planar float output, its planes ordered G, B, R and then A} */
    public static float[] interleaveGbr(float[] planar, int width, int height, int channels) {
        var plane = width * height;
        var out = new float[plane * channels];
        int[] order = channels == 4 ? new int[]{2, 0, 1, 3} : new int[]{2, 0, 1};
        for (var index = 0; index < plane; index++) {
            for (var channel = 0; channel < channels; channel++) {
                out[index * channels + channel] = planar[order[channel] * plane + index];
            }
        }
        return out;
    }

    /** {@return the tightly packed samples of a floating-point picture} */
    public static float[] tight(ChannelFloatPicture picture) {
        var row = picture.width() * picture.channelCount();
        var out = new float[row * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.pixels(), y * picture.stride(), out, y * row, row);
        }
        return out;
    }

    /** {@return the tightly packed samples of a 16-bit picture} */
    public static short[] tight(Rgba16Picture picture) {
        var row = picture.width() * 4;
        var out = new short[row * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.pixels(), y * picture.stride(), out, y * row, row);
        }
        return out;
    }
}

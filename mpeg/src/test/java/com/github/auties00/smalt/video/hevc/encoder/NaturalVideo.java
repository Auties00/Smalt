package com.github.auties00.smalt.video.hevc.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Natural-content encoder input of the hosted test data, cut to a window of a few frames so that the encoder tests
 * stay fast: the Xiph {@code ducks_take_off} 720p50 4:2:2 and 4:4:4 Y4M files, the {@code football} NTSC 4:2:2
 * interlaced Y4M file (fields woven) and the first frames of Netflix's 10-bit 4:2:0 Sintel "Weapon" 4K source.
 */
public final class NaturalVideo {
    private NaturalVideo() {
    }

    /**
     * Reads 8-bit frames of a Y4M vector, cropped to a window.
     *
     * @param vector the vector name
     * @param frames the frame count
     * @param left   the left edge of the window, even
     * @param top    the top edge of the window, even
     * @param width  the window width
     * @param height the window height
     * @return the pictures
     */
    public static List<Picture> y4m(String vector, int frames, int left, int top, int width, int height) {
        try (var channel = FileChannel.open(Conformance.vector(vector), StandardOpenOption.READ)) {
            var head = java.nio.ByteBuffer.allocate(256);
            channel.read(head, 0);
            var text = new String(head.array(), StandardCharsets.US_ASCII);
            var header = text.substring(0, text.indexOf('\n'));
            int sourceWidth = 0;
            int sourceHeight = 0;
            var format = ChromaFormat.YUV420;
            for (var field : header.split(" ")) {
                switch (field.charAt(0)) {
                    case 'W' -> sourceWidth = Integer.parseInt(field.substring(1));
                    case 'H' -> sourceHeight = Integer.parseInt(field.substring(1));
                    case 'C' -> format = switch (field.substring(1)) {
                        case "422" -> ChromaFormat.YUV422;
                        case "444" -> ChromaFormat.YUV444;
                        default -> field.startsWith("C420") ? ChromaFormat.YUV420
                                : fail(vector + ": colour space " + field);
                    };
                    default -> {
                    }
                }
            }
            var chromaWidth = format.chromaWidth(sourceWidth);
            var chromaHeight = format.chromaHeight(sourceHeight);
            var frameSize = sourceWidth * sourceHeight + 2L * chromaWidth * chromaHeight;
            var position = (long) header.length() + 1;
            var result = new ArrayList<Picture>();
            for (var f = 0; f < frames; f++) {
                var marker = java.nio.ByteBuffer.allocate(6);
                channel.read(marker, position);
                if (!new String(marker.array(), StandardCharsets.US_ASCII).equals("FRAME\n")) {
                    fail(vector + ": no FRAME marker at " + position);
                }
                position += 6;
                var data = java.nio.ByteBuffer.allocate((int) frameSize);
                channel.read(data, position);
                position += frameSize;
                var raw = data.array();
                var scaleX = sourceWidth / chromaWidth;
                var scaleY = sourceHeight / chromaHeight;
                var luma = crop(raw, 0, sourceWidth, left, top, width, height);
                var cw = format.chromaWidth(width);
                var ch = format.chromaHeight(height);
                var u = crop(raw, sourceWidth * sourceHeight, chromaWidth, left / scaleX, top / scaleY, cw, ch);
                var v = crop(raw, sourceWidth * sourceHeight + chromaWidth * chromaHeight, chromaWidth, left / scaleX,
                        top / scaleY, cw, ch);
                result.add(YuvPicture.builder().size(width, height).chromaFormat(format).color(ColorInfo.BT709_LIMITED)
                        .luma(luma, width).chroma(u, v, cw).build());
            }
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Reads 10-bit 4:2:0 frames ({@code yuv420p10le}) of a raw YUV vector, cropped to a window.
     *
     * @param vector       the vector name
     * @param sourceWidth  the width of the source frames
     * @param sourceHeight the height of the source frames
     * @param frames       the frame count
     * @param left         the left edge of the window, even
     * @param top          the top edge of the window, even
     * @param width        the window width
     * @param height       the window height
     * @return the pictures
     */
    public static List<Picture> yuv420p10(String vector, int sourceWidth, int sourceHeight, int frames, int left,
                                          int top, int width, int height) {
        try (var channel = FileChannel.open(Conformance.vector(vector), StandardOpenOption.READ)) {
            var lumaSize = (long) sourceWidth * sourceHeight;
            var chromaSize = lumaSize / 4;
            var frameSize = 2 * (lumaSize + 2 * chromaSize);
            var result = new ArrayList<Picture>();
            for (var f = 0; f < frames; f++) {
                var data = java.nio.ByteBuffer.allocate((int) frameSize).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                channel.read(data, f * frameSize);
                data.flip();
                var samples = new short[(int) (frameSize / 2)];
                data.asShortBuffer().get(samples);
                var luma = crop(samples, 0, sourceWidth, left, top, width, height);
                var u = crop(samples, (int) lumaSize, sourceWidth / 2, left / 2, top / 2, width / 2, height / 2);
                var v = crop(samples, (int) (lumaSize + chromaSize), sourceWidth / 2, left / 2, top / 2, width / 2,
                        height / 2);
                result.add(Yuv16Picture.builder().size(width, height).chromaFormat(ChromaFormat.YUV420).bitDepth(10)
                        .color(ColorInfo.BT709_LIMITED).luma(luma, width).chroma(u, v, width / 2).build());
            }
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] crop(byte[] plane, int offset, int stride, int left, int top, int width, int height) {
        var out = new byte[width * height];
        for (var y = 0; y < height; y++) {
            System.arraycopy(plane, offset + (top + y) * stride + left, out, y * width, width);
        }
        return out;
    }

    private static short[] crop(short[] plane, int offset, int stride, int left, int top, int width, int height) {
        var out = new short[width * height];
        for (var y = 0; y < height; y++) {
            System.arraycopy(plane, offset + (top + y) * stride + left, out, y * width, width);
        }
        return out;
    }

    private static <T> T fail(String message) {
        throw new AssertionError(message);
    }
}

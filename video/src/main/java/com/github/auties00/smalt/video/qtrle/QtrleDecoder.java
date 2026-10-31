package com.github.auties00.smalt.video.qtrle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.codec.IntermediateFrames;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes QuickTime Animation ({@link Codec#QTRLE}), the run-length codec of the QuickTime {@code rle } sample entry.
 *
 * <p>Every depth of the format decodes: 1, 2, 4 and 8-bit indices through the track's color table or the default
 * Macintosh table of the depth, 16-bit 5-5-5 RGB, 24-bit RGB, 32-bit ARGB, and gray indices of 1, 2, 4 or 8 bits
 * (depths 33, 34, 36 and 40) from white at index 0 to black at the last index. Frames decode to an
 * {@link RgbaPicture}, opaque except at depth 32, which keeps the stored alpha; 5-bit components widen to 8 bits by
 * repeating their high bits. The output is exact: decoding a stream returns the very pixels it codes.
 *
 * <p>Frames of 1-bit pixels (depths 1 and 33) are laid out as QuickTime writes them, without line terminators: every
 * code is preceded by a skip byte whose high bit starts the next row, and a code of 0 ends the frame. The other depths
 * end each row with a -1 code and the frame with a zero skip byte.
 *
 * <p>A frame updates the picture left by the frame before it: pixels the frame skips, and rows outside its row range,
 * keep their previous value, and a packet of fewer than 8 bytes repeats the previous picture. Before the first frame
 * the picture is black, transparent at depth 32. Every packet yields one frame carrying the packet's timestamps and key
 * frame flag; {@link #flush(Consumer)} has nothing to drain. A corrupt frame raises {@link MediaException.Malformed}
 * and leaves the rows it decoded in place for the next frame.
 *
 * <p>Instances are not thread-safe.
 */
public final class QtrleDecoder implements VideoDecoder {
    /**
     * The track being decoded.
     */
    private final VideoTrack track;

    /**
     * The sample description's depth.
     */
    private final int depth;

    /**
     * The palette of the index depths, as {@code 0xAARRGGBB} entries, or {@code null}.
     */
    private final int[] palette;

    /**
     * The bytes of a coded unit.
     */
    private final int unitBytes;

    /**
     * The pixels a coded unit covers.
     */
    private final int unitPixels;

    /**
     * The bits of an index, or 0 for the direct color depths.
     */
    private final int indexBits;

    private final int width;

    private final int height;

    /**
     * The picture the frames update, packed RGBA with a stride of {@code width * 4}.
     */
    private final byte[] canvas;

    /**
     * The repeated unit of a run, expanded to RGBA.
     */
    private final byte[] run;

    /**
     * Creates a decoder for a QuickTime Animation track.
     *
     * @param track the track description; its codec must be {@link Codec#QTRLE} and its configuration, when present,
     * the depth and color table as {@link Codec#QTRLE} describes
     * @param options the limits the picture size is checked against before allocating; the thread count and the high
     * bit depth setting do not apply
     * @throws IllegalArgumentException if the track is not a QuickTime Animation track
     * @throws MediaException.Malformed if the track has no picture size
     * @throws MediaException.Unsupported if the configuration declares a depth the format does not define
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    public QtrleDecoder(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.QTRLE) {
            throw new IllegalArgumentException("not a qtrle track: " + track.codec());
        }
        this.track = track;
        var config = track.codecConfig().orElse(null);
        this.depth = IntermediateFrames.qtrleDepth(config);
        switch (depth) {
            case 1, 33 -> {
                unitBytes = 2;
                unitPixels = 16;
                indexBits = 1;
            }
            case 2, 34 -> {
                unitBytes = 4;
                unitPixels = 16;
                indexBits = 2;
            }
            case 4, 36 -> {
                unitBytes = 4;
                unitPixels = 8;
                indexBits = 4;
            }
            case 8, 40 -> {
                unitBytes = 4;
                unitPixels = 4;
                indexBits = 8;
            }
            case 16 -> {
                unitBytes = 2;
                unitPixels = 1;
                indexBits = 0;
            }
            case 24 -> {
                unitBytes = 3;
                unitPixels = 1;
                indexBits = 0;
            }
            case 32 -> {
                unitBytes = 4;
                unitPixels = 1;
                indexBits = 0;
            }
            default -> throw new MediaException.Unsupported("QuickTime Animation", "depth " + depth);
        }
        this.palette = indexBits == 0 ? null : fullPalette(IntermediateFrames.qtrlePalette(config), indexBits);
        this.width = track.width();
        this.height = track.height();
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("QuickTime Animation track has no picture size");
        }
        MediaLimits limits = options.limits();
        LimitChecks.checkPicture(limits, width, height);
        LimitChecks.checkAllocation(limits, 4L * width * height);
        this.canvas = new byte[width * height * 4];
        this.run = new byte[unitPixels * 4];
        if (depth != 32) {
            for (var i = 3; i < canvas.length; i += 4) {
                canvas[i] = (byte) 0xFF;
            }
        }
    }

    /**
     * {@return a palette of exactly {@code 1 << bits} entries: the given entries, then opaque black}
     *
     * @param entries the palette, or {@code null}
     * @param bits the index bits
     */
    private static int[] fullPalette(int[] entries, int bits) {
        var full = new int[1 << bits];
        Arrays.fill(full, 0xFF000000);
        if (entries != null) {
            System.arraycopy(entries, 0, full, 0, Math.min(entries.length, full.length));
        }
        return full;
    }

    /**
     * {@return the track being decoded}
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * {@return the depth the track's configuration declares}
     */
    public int depth() {
        return depth;
    }

    /**
     * Decodes one packet holding one frame.
     *
     * @param packet the next packet in decoding order
     * @param output receives the frame, carrying the packet's timestamps and key frame flag
     * @throws MediaException.Malformed if the frame is damaged
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        var data = packet.data();
        decodeInto(data, 0, data.length);
        output.accept(new VideoFrame(picture(), packet.pts(),
                packet.duration() > 0 ? packet.duration() : Track.UNKNOWN_DURATION, packet.keyframe()));
    }

    /**
     * Decodes one frame held in part of an array and returns the updated picture.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @return a copy of the picture after the frame; it shares no state with the decoder
     * @throws IndexOutOfBoundsException if the range is invalid
     * @throws MediaException.Malformed if the frame is damaged
     */
    public RgbaPicture decodeFrame(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        decodeInto(data, offset, length);
        return picture();
    }

    /**
     * {@return a copy of the current picture}
     */
    private RgbaPicture picture() {
        return RgbaPicture.of(width, height, width * 4, canvas.clone(), depth == 32);
    }

    /**
     * Applies one frame to the canvas.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     */
    private void decodeInto(byte[] data, int offset, int length) {
        if (length < 8) {
            return;
        }
        var size = u32(data, offset) & 0x3FFFFFFFL;
        var end = offset + (int) Math.min(length, size >= 8 ? size : length);
        var at = offset + 4;
        var header = u16(data, at);
        at += 2;
        int start;
        int lines;
        if ((header & 0x0008) != 0) {
            if (end - at < 8) {
                throw new MediaException.Malformed("QuickTime Animation frame header is truncated");
            }
            start = u16(data, at);
            lines = u16(data, at + 4);
            at += 8;
        } else {
            start = 0;
            lines = height;
        }
        if (start + lines > height) {
            throw new MediaException.Malformed("QuickTime Animation frame codes rows " + start + " to "
                    + (start + lines) + " of a " + height + "-row picture");
        }
        if (indexBits == 1) {
            decodeBitmap(data, at, end, start, start + lines);
            return;
        }
        var row = start;
        while (lines-- > 0) {
            if (at >= end) {
                throw new MediaException.Malformed("QuickTime Animation frame is truncated");
            }
            var skip = data[at++] & 0xFF;
            if (skip == 0) {
                return;
            }
            var x = (long) (skip - 1) * unitPixels;
            while (true) {
                if (at >= end) {
                    throw new MediaException.Malformed("QuickTime Animation line is truncated");
                }
                int code = data[at++];
                if (code == -1) {
                    break;
                }
                if (code == 0) {
                    if (at >= end) {
                        throw new MediaException.Malformed("QuickTime Animation skip is truncated");
                    }
                    skip = data[at++] & 0xFF;
                    if (skip == 0) {
                        throw new MediaException.Malformed("QuickTime Animation skip code is zero");
                    }
                    x += (long) (skip - 1) * unitPixels;
                } else if (code > 0) {
                    if ((long) code * unitBytes > end - at) {
                        throw new MediaException.Malformed("QuickTime Animation literal run is truncated");
                    }
                    if (x + (long) code * unitPixels > (long) width + unitPixels - 1) {
                        throw new MediaException.Malformed("QuickTime Animation run passes the end of the row");
                    }
                    for (var i = 0; i < code; i++) {
                        unit(data, at, run);
                        at += unitBytes;
                        put(row, (int) x, run);
                        x += unitPixels;
                    }
                } else {
                    if (unitBytes > end - at) {
                        throw new MediaException.Malformed("QuickTime Animation repeat run is truncated");
                    }
                    var count = -code;
                    if (x + (long) count * unitPixels > (long) width + unitPixels - 1) {
                        throw new MediaException.Malformed("QuickTime Animation run passes the end of the row");
                    }
                    unit(data, at, run);
                    at += unitBytes;
                    for (var i = 0; i < count; i++) {
                        put(row, (int) x, run);
                        x += unitPixels;
                    }
                }
            }
            row++;
        }
    }

    /**
     * Applies the rows of a 1-bit frame to the canvas.
     *
     * <p>A 1-bit frame has no line terminators: every code is preceded by a skip byte, whose high bit moves to the
     * start of the next row and whose low 7 bits count the 16-pixel units to skip, and a code of 0 ends the frame.
     * A positive code is a literal run of that many units and a negative one repeats the unit that follows.
     *
     * @param data the array holding the frame
     * @param at the index of the first skip byte
     * @param end the index after the frame
     * @param start the first row the frame codes
     * @param limit the row after the last one the frame codes
     */
    private void decodeBitmap(byte[] data, int at, int end, int start, int limit) {
        var row = start - 1;
        var x = 0L;
        while (at < end) {
            var skip = data[at++] & 0xFF;
            if ((skip & 0x80) != 0) {
                row++;
                x = (long) (skip & 0x7F) * unitPixels;
            } else {
                x += (long) skip * unitPixels;
            }
            if (at >= end) {
                throw new MediaException.Malformed("QuickTime Animation code is truncated");
            }
            int code = data[at++];
            if (code == 0) {
                return;
            }
            if (row < start || row >= limit) {
                throw new MediaException.Malformed("QuickTime Animation frame codes a row outside its range");
            }
            if (code == -1) {
                throw new MediaException.Malformed("QuickTime Animation 1-bit frame holds a -1 code");
            }
            var count = Math.abs(code);
            var literal = code > 0;
            if ((literal ? (long) count * unitBytes : unitBytes) > end - at) {
                throw new MediaException.Malformed("QuickTime Animation run is truncated");
            }
            if (x + (long) count * unitPixels > (long) width + unitPixels - 1) {
                throw new MediaException.Malformed("QuickTime Animation run passes the end of the row");
            }
            if (!literal) {
                unit(data, at, run);
                at += unitBytes;
            }
            for (var i = 0; i < count; i++) {
                if (literal) {
                    unit(data, at, run);
                    at += unitBytes;
                }
                put(row, (int) x, run);
                x += unitPixels;
            }
        }
    }

    /**
     * Expands one coded unit to RGBA pixels.
     *
     * @param data the array holding the unit
     * @param at the index of its first byte
     * @param pixels receives {@link #unitPixels} RGBA pixels
     */
    private void unit(byte[] data, int at, byte[] pixels) {
        switch (depth) {
            case 16 -> {
                var value = (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
                pixels[0] = widen5(value >>> 10);
                pixels[1] = widen5(value >>> 5);
                pixels[2] = widen5(value);
                pixels[3] = (byte) 0xFF;
            }
            case 24 -> {
                pixels[0] = data[at];
                pixels[1] = data[at + 1];
                pixels[2] = data[at + 2];
                pixels[3] = (byte) 0xFF;
            }
            case 32 -> {
                pixels[0] = data[at + 1];
                pixels[1] = data[at + 2];
                pixels[2] = data[at + 3];
                pixels[3] = data[at];
            }
            default -> {
                var mask = (1 << indexBits) - 1;
                var perByte = 8 / indexBits;
                for (var i = 0; i < unitPixels; i++) {
                    var b = data[at + i / perByte] & 0xFF;
                    var index = b >>> (8 - indexBits * (i % perByte + 1)) & mask;
                    var color = palette[index];
                    pixels[i * 4] = (byte) (color >>> 16);
                    pixels[i * 4 + 1] = (byte) (color >>> 8);
                    pixels[i * 4 + 2] = (byte) color;
                    pixels[i * 4 + 3] = (byte) 0xFF;
                }
            }
        }
    }

    /**
     * {@return a 5-bit component widened to 8 bits}
     *
     * @param value the component in its low 5 bits
     */
    private static byte widen5(int value) {
        var v = value & 0x1F;
        return (byte) (v << 3 | v >>> 2);
    }

    /**
     * Writes the pixels of one unit into a row, dropping those past its end.
     *
     * @param row the picture row
     * @param x the column of the unit's first pixel
     * @param pixels the RGBA pixels of the unit
     */
    private void put(int row, int x, byte[] pixels) {
        var count = Math.min(unitPixels, width - x);
        if (count > 0) {
            System.arraycopy(pixels, 0, canvas, (row * width + x) * 4, count * 4);
        }
    }

    private static int u16(byte[] data, int at) {
        return (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
    }

    private static long u32(byte[] data, int at) {
        return (long) u16(data, at) << 16 | u16(data, at + 2);
    }

    /**
     * Does nothing: QuickTime Animation frames are never held back.
     *
     * @param output ignored
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
    }
}

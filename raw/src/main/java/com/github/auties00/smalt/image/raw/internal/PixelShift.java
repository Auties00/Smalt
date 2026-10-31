package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaColor;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.List;

/**
 * Merges the frames of a pixel-shift capture, Bayer frames taken with the sensor moved by one photosite
 * between them, into one image of red, green and blue at every pixel.
 *
 * <p>Frame {@code i} is offset by {@code (dx[i], dy[i])}: the pixel at column {@code x} and row {@code y}
 * of the merged image is the photosite at column {@code x + dx[i]} and row {@code y + dy[i]} of the frame.
 * Four offsets that cover a 2x2 square give every pixel one red, two green and one blue photosite: red
 * and blue are taken as they are, green is the mean of its two photosites rounded down. Where an offset
 * leaves the frame, the pixel takes the photosite two rows or columns back, of the same color.
 *
 * @implNote This implementation merges the stored values, before black subtraction, which is exact
 * because the frames share their levels; frames with a linearization table, black level deltas or
 * different levels are not merged. Opcode list 1 applies to each frame before the merge; opcode
 * list 2, which addresses the photosites of one frame, is not applied.
 */
final class PixelShift {
    /**
     * The column offset of each frame of a Pentax pixel-shift capture, in the order the file stores them.
     */
    static final int[] PENTAX_DX = {0, 0, 1, 1};

    /**
     * The row offset of each frame of a Pentax pixel-shift capture, in the order the file stores them.
     */
    static final int[] PENTAX_DY = {0, 1, 1, 0};

    /**
     * Prevents instantiation.
     */
    private PixelShift() {
        throw new AssertionError();
    }

    /**
     * Returns the sensor description of the merged image of the frames of a pixel-shift capture.
     *
     * @param frames the frames, the first frame first, each of one sample per photosite
     * @param dx the column offset of each frame, 0 or 1, covering a 2x2 square with {@code dy}
     * @param dy the row offset of each frame, 0 or 1
     * @return the merged image, of three samples per pixel, or {@code null} when the frames cannot be
     * merged: not of one Bayer pattern, size, bit depth and set of levels
     */
    static Sensor merge(List<Sensor> frames, int[] dx, int[] dy) {
        var first = frames.getFirst();
        if (frames.size() != dx.length || first.cfa == null || !first.cfa.isBayer() || first.samplesPerPixel != 1
                || first.linearization != null || first.blackDeltaH != null || first.blackDeltaV != null
                || first.floating || first.wide) {
            return null;
        }
        for (var frame : frames) {
            if (frame.width != first.width || frame.height != first.height || frame.samplesPerPixel != 1
                    || !first.cfa.equals(frame.cfa) || frame.bitsPerSample != first.bitsPerSample
                    || frame.linearization != null || frame.blackDeltaH != null || frame.blackDeltaV != null
                    || frame.blackRows != first.blackRows || frame.blackColumns != first.blackColumns
                    || !Arrays.equals(frame.black, first.black) || !Arrays.equals(frame.white, first.white)
                    || frame.floating || frame.wide) {
                return null;
            }
        }
        var sensor = new Sensor();
        sensor.width = first.width;
        sensor.height = first.height;
        sensor.bitsPerSample = first.bitsPerSample;
        sensor.samplesPerPixel = 3;
        sensor.compression = first.compression;
        sensor.activeArea = first.activeArea;
        sensor.defaultCrop = first.defaultCrop;
        sensor.userCrop = first.userCrop;
        sensor.gain = first.gain;
        sensor.scaleH = first.scaleH;
        sensor.scaleV = first.scaleV;
        sensor.opcodes3 = first.opcodes3;
        sensor.tags.addAll(first.tags);
        sensor.black = colorBlack(first);
        if (first.white != null) {
            var white = first.white[0];
            for (var value : first.white) {
                white = Math.min(white, value);
            }
            sensor.white = new double[]{white, white, white};
        }
        sensor.frames = List.copyOf(frames);
        var cfa = first.cfa;
        var merged = sensor.frames;
        sensor.reader = new Sensor.Reader() {
            @Override
            public void read(SensorPlane plane, MediaLimits limits) {
                read(plane, limits, false);
            }

            @Override
            public void read(SensorPlane plane, MediaLimits limits, boolean corrections) {
                fill(merged, cfa, dx, dy, plane, limits, corrections);
            }
        };
        return sensor;
    }

    /**
     * Returns the black level of each color of a Bayer frame, the mean of its positions in the black
     * level repeat pattern.
     *
     * @param frame a Bayer frame of one sample per photosite
     * @return the red, green and blue black levels, or {@code null} when the frame records none
     */
    private static double[] colorBlack(Sensor frame) {
        if (frame.black == null) {
            return null;
        }
        var black = new double[3];
        var counts = new int[3];
        var rows = Math.max(frame.blackRows, frame.cfa.height());
        var columns = Math.max(frame.blackColumns, frame.cfa.width());
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < columns; column++) {
                var color = frame.cfa.color(row % frame.cfa.height(), column % frame.cfa.width()).code();
                black[color] += frame.black[(row % frame.blackRows) * frame.blackColumns + column % frame.blackColumns];
                counts[color]++;
            }
        }
        for (var color = 0; color < 3; color++) {
            black[color] /= counts[color];
        }
        return black;
    }

    /**
     * Reads the frames and merges them into a plane.
     *
     * @param frames the frames, each read into a scratch plane in turn
     * @param cfa the color filter array of the frames
     * @param dx the column offset of each frame
     * @param dy the row offset of each frame
     * @param plane the plane to fill, three samples per pixel
     * @param limits the resource bounds
     * @param corrections whether the frames' sensor corrections and opcode list 1 are applied
     */
    private static void fill(List<Sensor> frames, CfaPattern cfa, int[] dx, int[] dy, SensorPlane plane,
                             MediaLimits limits, boolean corrections) {
        var width = plane.width;
        var height = plane.height;
        if (width < 2 || height < 2 || plane.integers == null) {
            throw new MediaException.Malformed("pixel-shift frames too small to merge");
        }
        var scratch = new SensorPlane(width, height, 1, false, limits);
        var out = plane.integers;
        var count = frames.size();
        // role[frame][parity]: 0 red, 2 blue, 1 the first green, 3 the second, for a pixel of parity (row, column)
        var role = new int[count][4];
        for (var parity = 0; parity < 4; parity++) {
            var greens = 0;
            for (var frame = 0; frame < count; frame++) {
                var color = cfa.color(((parity >> 1) + dy[frame]) % 2, ((parity & 1) + dx[frame]) % 2);
                if (color == CfaColor.GREEN) {
                    role[frame][parity] = greens++ == 0 ? 1 : 3;
                } else {
                    role[frame][parity] = color == CfaColor.RED ? 0 : 2;
                }
            }
        }
        for (var frame = 0; frame < count; frame++) {
            var source = frames.get(frame);
            if (frame > 0) {
                // a truncated frame leaves its missing rows at 0, not at the previous frame's values
                Arrays.fill(scratch.integers, (short) 0);
            }
            source.reader.read(scratch, limits, corrections);
            if (corrections && source.opcodes1 != null) {
                Opcodes.applyToSensor(source, scratch, source.opcodes1);
            }
            var in = scratch.integers;
            var shiftX = dx[frame];
            var shiftY = dy[frame];
            var roles = role[frame];
            var bands = ParallelBands.bands(height, (long) width * height);
            ParallelBands.run(bands, band -> {
                var start = ParallelBands.start(band, bands, height);
                var end = ParallelBands.start(band + 1, bands, height);
                for (var y = start; y < end; y++) {
                    var row = y + shiftY < height ? y + shiftY : y + shiftY - 2;
                    var line = row * width;
                    var target = y * width * 3;
                    var parity = (y & 1) << 1;
                    for (var x = 0; x < width; x++) {
                        var column = x + shiftX < width ? x + shiftX : x + shiftX - 2;
                        var value = in[line + column] & 0xFFFF;
                        var at = target + x * 3;
                        switch (roles[parity | (x & 1)]) {
                            case 0 -> out[at] = (short) value;
                            case 1 -> out[at + 1] = (short) value;
                            case 2 -> out[at + 2] = (short) value;
                            default -> out[at + 1] = (short) (((out[at + 1] & 0xFFFF) + value) >>> 1);
                        }
                    }
                }
            });
        }
    }
}

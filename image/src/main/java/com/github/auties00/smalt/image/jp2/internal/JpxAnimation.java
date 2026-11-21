package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Renders the composition of a JPX file (ISO/IEC 15444-2 M.5.3 and M.11.10) as an animation: executes its
 * instructions in order, each set as many times as it repeats, compositing the cropped, rotated and scaled
 * compositing layer of each instruction over the render area, and returns a frame whenever an instruction with a
 * non-zero life completes a display update.
 *
 * <p>Layers are assigned to instructions as M.5.3.2.2 states: an instruction takes the layer an earlier instruction's
 * {@code NEXT-USE} reserved for it, or else the next unused layer; the animation ends when an instruction finds no
 * layer or when no instruction is left. An instruction of zero life that persists starts or continues a frame
 * composition sequence, shown with the next instruction of non-zero life; one of zero life that does not persist
 * performs no action. The samples an instruction renders without persisting are removed again before the next
 * instruction, together with those of the frame composition sequence it closes. The render area starts fully
 * transparent; layers are composited with the "over" operator of equation M-1, and scaled with the sample whose area
 * covers each rendered sample's center. A file without a composition box is a one-frame animation of its first
 * compositing layer.
 *
 * <p>Instances are not thread-safe.
 */
public final class JpxAnimation implements AnimationReader {
    /**
     * The composition, or {@code null} for the one-frame animation of the first layer.
     */
    private final Jp2Boxes.Composition composition;

    /**
     * Decodes a compositing layer to packed RGBA samples of 16 bits.
     */
    private final IntFunction<Layer> decoder;

    /**
     * The number of compositing layers.
     */
    private final int layers;

    /**
     * The render area width.
     */
    private final int width;

    /**
     * The render area height.
     */
    private final int height;

    /**
     * Whether frames are 16-bit pictures.
     */
    private final boolean wide;

    /**
     * The render area, four 16-bit samples per pixel, straight alpha.
     */
    private final int[] canvas;

    /**
     * The render area as it was before the current frame composition sequence, for an instruction that does not
     * persist.
     */
    private final int[] saved;

    /**
     * The number of frames, counted by a dry run of the instructions.
     */
    private final int frameCount;

    /**
     * The instructions not yet executed.
     */
    private final Schedule schedule;

    /**
     * The limits bounding the memory the cached layers hold.
     */
    private final MediaLimits limits;

    /**
     * The decoded layers kept for a later instruction that reuses them.
     */
    private final Map<Integer, Layer> cache = new HashMap<>();

    /**
     * The number of bytes the layers of {@link #cache} hold.
     */
    private long cachedBytes;

    /**
     * The time the next frame starts, in microseconds.
     */
    private long timestamp;

    /**
     * Whether the render area holds samples of a frame composition sequence not shown yet.
     */
    private boolean pending;

    /**
     * Whether {@link #saved} holds the area before the pending sequence.
     */
    private boolean sequenceSaved;

    /**
     * Whether the last frame was returned.
     */
    private boolean finished;

    /**
     * Whether the render area returns to {@link #saved} before the next instruction, because the last frame did not
     * persist.
     */
    private boolean restore;

    /**
     * Holds a decoded compositing layer.
     *
     * @param width the layer width
     * @param height the layer height
     * @param samples the samples, four 16-bit values per pixel, straight alpha
     * @param alpha whether the layer has an opacity channel
     */
    public record Layer(int width, int height, int[] samples, boolean alpha) {
        /**
         * Converts a decoded picture to a layer.
         *
         * @param picture the picture, an {@link RgbaPicture} or an {@link Rgba16Picture}
         * @return the layer
         */
        public static Layer of(Picture picture) {
            var width = picture.width();
            var height = picture.height();
            var samples = new int[width * height * 4];
            switch (picture) {
                case RgbaPicture rgba -> {
                    var pixels = rgba.pixels();
                    for (var y = 0; y < height; y++) {
                        for (var x = 0; x < 4 * width; x++) {
                            samples[y * width * 4 + x] = (pixels[y * rgba.stride() + x] & 0xFF) * 257;
                        }
                    }
                    return new Layer(width, height, samples, rgba.hasAlpha());
                }
                case Rgba16Picture rgba -> {
                    var pixels = rgba.pixels();
                    for (var y = 0; y < height; y++) {
                        for (var x = 0; x < 4 * width; x++) {
                            samples[y * width * 4 + x] = pixels[y * rgba.stride() + x] & 0xFFFF;
                        }
                    }
                    return new Layer(width, height, samples, rgba.hasAlpha());
                }
                default -> throw new IllegalArgumentException("not a packed RGBA picture");
            }
        }
    }

    /**
     * Opens the animation of a file.
     *
     * @param composition the composition, or {@code null} for a one-frame animation of the first layer
     * @param layers the number of compositing layers
     * @param firstLayerSize the size of the first layer, the render area without a composition
     * @param wide whether frames are 16-bit pictures
     * @param decoder decodes a compositing layer
     * @param limits the limits bounding the render area and the frame count
     * @throws MediaException.LimitExceeded if the render area or the frame count exceeds the limits
     */
    public JpxAnimation(Jp2Boxes.Composition composition, int layers, int[] firstLayerSize, boolean wide,
                        IntFunction<Layer> decoder, MediaLimits limits) {
        this.composition = composition;
        this.layers = layers;
        this.decoder = decoder;
        this.wide = wide;
        if (composition == null) {
            width = firstLayerSize[0];
            height = firstLayerSize[1];
        } else {
            if (composition.width() > Integer.MAX_VALUE || composition.height() > Integer.MAX_VALUE) {
                throw new MediaException.LimitExceeded("JPEG 2000 render area of " + composition.width() + "x"
                        + composition.height());
            }
            width = (int) composition.width();
            height = (int) composition.height();
        }
        LimitChecks.checkPicture(limits, width, height);
        LimitChecks.checkAllocation(limits, (long) width * height * 4 * 4 * 3);
        this.limits = limits;
        frameCount = composition == null ? 1 : countFrames(composition, layers, limits);
        canvas = new int[width * height * 4];
        saved = new int[canvas.length];
        schedule = composition == null ? null : new Schedule(composition, layers, limits);
    }

    /**
     * Counts the frames a composition renders, without decoding any layer.
     *
     * @param composition the composition box whose instructions are walked
     * @param layers the number of compositing layers
     * @param limits the limits bounding the frame count and the number of instructions executed
     * @return the number of frames, 0 when no instruction shows a layer
     * @throws MediaException.LimitExceeded if the frame count or the number of instructions exceeds the limits
     */
    public static int countFrames(Jp2Boxes.Composition composition, int layers, MediaLimits limits) {
        var counter = new Schedule(composition, layers, limits);
        var frames = 0;
        var open = false;
        for (var step = counter.next(); step != null; step = counter.next()) {
            var instruction = step.instruction();
            if (instruction.life() > 0) {
                frames++;
                open = false;
                LimitChecks.checkFrames(limits, frames);
            } else if (instruction.persist()) {
                open = true;
            }
        }
        return frames + (open ? 1 : 0);
    }

    /**
     * Holds one instruction to execute with its layer.
     *
     * @param instruction the instruction to execute
     * @param layer the compositing layer it renders
     * @param tick the duration of a timer tick of its set, in milliseconds
     * @param reused whether a later instruction reuses the layer
     */
    private record Step(Jp2Boxes.Instruction instruction, int layer, long tick, boolean reused) {
    }

    /**
     * Walks the instructions of a composition in execution order, assigning each its layer.
     */
    private static final class Schedule {
        /**
         * The composition.
         */
        private final Jp2Boxes.Composition composition;

        /**
         * The number of compositing layers.
         */
        private final int layers;

        /**
         * The limits bounding the number of instructions executed.
         */
        private final MediaLimits limits;

        /**
         * The layers reserved for later instructions, by instruction number.
         */
        private final Map<Long, Integer> reserved = new HashMap<>();

        /**
         * The index of the current instruction set.
         */
        private int set;

        /**
         * The number of executions of the current set completed.
         */
        private int repetition;

        /**
         * The index of the next instruction in the current set.
         */
        private int instruction;

        /**
         * The number of instructions executed.
         */
        private long executed;

        /**
         * The next layer never assigned.
         */
        private int nextLayer;

        /**
         * The value of {@link #nextLayer} when the current execution of an indefinitely repeating set started.
         */
        private int layerAtRepetition;

        /**
         * Whether the walk has ended.
         */
        private boolean ended;

        /**
         * Constructs a walk from the first instruction.
         *
         * @param composition the composition box
         * @param layers the number of compositing layers
         * @param limits the limits bounding the number of instructions executed
         */
        Schedule(Jp2Boxes.Composition composition, int layers, MediaLimits limits) {
            this.composition = composition;
            this.layers = layers;
            this.limits = limits;
        }

        /**
         * Returns the next instruction with its layer.
         *
         * @return the step, or {@code null} at the end of the composition
         */
        Step next() {
            while (!ended) {
                if (set >= composition.sets().size()) {
                    ended = true;
                    break;
                }
                var current = composition.sets().get(set);
                if (instruction >= current.instructions().size()) {
                    instruction = 0;
                    repetition++;
                    var indefinite = current.repeat() == 65535;
                    // an indefinitely repeating set ends when an execution assigned no new layer
                    var stalled = indefinite && nextLayer == layerAtRepetition;
                    layerAtRepetition = nextLayer;
                    if (current.instructions().isEmpty() || stalled || !indefinite && repetition > current.repeat()) {
                        set++;
                        repetition = 0;
                    }
                    continue;
                }
                var entry = current.instructions().get(instruction++);
                var number = executed++;
                if (executed > (long) limits.maxFrames() * 64 + 65536) {
                    throw new MediaException.LimitExceeded("JPEG 2000 composition of more than " + executed
                            + " instructions");
                }
                var layer = reserved.remove(number);
                if (layer == null) {
                    if (nextLayer >= layers) {
                        ended = true;
                        break;
                    }
                    layer = nextLayer++;
                }
                // a layer holds at most one reservation: it is either new or taken from its reservation above
                var reused = entry.nextUse() > 0;
                if (reused) {
                    reserved.put(number + entry.nextUse(), layer);
                }
                return new Step(entry, layer, current.tick(), reused);
            }
            return null;
        }
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int loopCount() {
        if (composition == null) {
            return 1;
        }
        return composition.globalRepeat() == 255 ? 0 : composition.globalRepeat() + 1;
    }

    @Override
    public int frameCount() {
        return frameCount;
    }

    @Override
    public AnimationFrame read() {
        if (finished) {
            return null;
        }
        if (composition == null) {
            finished = true;
            draw(decoder.apply(0), new Jp2Boxes.Instruction(0, 0, -1, -1, true, 0, 0, 0, 0, -1, -1, 0));
            return new AnimationFrame(picture(), 0, 0);
        }
        while (true) {
            var step = schedule.next();
            if (step == null) {
                finished = true;
                if (pending) {
                    pending = false;
                    return new AnimationFrame(picture(), timestamp, 0);
                }
                return null;
            }
            var instruction = step.instruction();
            var layer = uncache(step.layer());
            if (!instruction.persist() && instruction.life() == 0) {
                if (layer != null && step.reused()) {
                    cache(step.layer(), layer);
                }
                continue;
            }
            if (layer == null) {
                layer = decoder.apply(step.layer());
            }
            if (step.reused()) {
                cache(step.layer(), layer);
            }
            if (restore) {
                System.arraycopy(saved, 0, canvas, 0, canvas.length);
                restore = false;
            }
            if (!sequenceSaved) {
                System.arraycopy(canvas, 0, saved, 0, canvas.length);
                sequenceSaved = true;
            }
            draw(layer, instruction);
            if (instruction.life() == 0) {
                pending = true;
                continue;
            }
            pending = false;
            // a life of up to 2^31 - 1 ticks of up to 2^32 - 1 ms is at most 2^63 ms: saturate the microseconds
            var millis = instruction.life() * step.tick();
            var duration = millis < 0 || millis > Long.MAX_VALUE / 4000 ? Long.MAX_VALUE / 4 : millis * 1000;
            var frame = new AnimationFrame(picture(), timestamp, duration);
            timestamp = Math.min(timestamp + duration, Long.MAX_VALUE / 2);
            sequenceSaved = false;
            restore = !instruction.persist();
            return frame;
        }
    }

    /**
     * Removes a layer from the cache.
     *
     * @param index the layer index
     * @return the cached layer, or {@code null} when it is not cached
     */
    private Layer uncache(int index) {
        var layer = cache.remove(index);
        if (layer != null) {
            cachedBytes -= 4L * layer.samples().length;
        }
        return layer;
    }

    /**
     * Keeps a decoded layer for the instruction that reuses it, unless the cached layers would then hold more than
     * the allocation limit; a layer that is not kept is decoded again when it is reused.
     *
     * @param index the layer index
     * @param layer the decoded layer
     */
    private void cache(int index, Layer layer) {
        var bytes = 4L * layer.samples().length;
        if (cachedBytes + bytes <= limits.maxBytes()) {
            var previous = cache.put(index, layer);
            cachedBytes += bytes - (previous == null ? 0 : 4L * previous.samples().length);
        }
    }

    /**
     * Composites a layer over the render area as an instruction asks: crop, rotation and flip, scaling, placement.
     *
     * @param layer the decoded compositing layer
     * @param instruction the instruction placing it
     */
    private void draw(Layer layer, Jp2Boxes.Instruction instruction) {
        var cropX = (int) Math.min(instruction.cropX(), layer.width());
        var cropY = (int) Math.min(instruction.cropY(), layer.height());
        var cropWidth = (int) Math.min(instruction.cropWidth() < 0 ? layer.width() - cropX : instruction.cropWidth(),
                layer.width() - cropX);
        var cropHeight = (int) Math.min(instruction.cropHeight() < 0 ? layer.height() - cropY
                : instruction.cropHeight(), layer.height() - cropY);
        if (cropWidth <= 0 || cropHeight <= 0) {
            return;
        }
        var turn = Math.max((instruction.rotation() & 7) - 1, 0);
        var flip = (instruction.rotation() & 0x10) != 0;
        var rotatedWidth = (turn & 1) != 0 ? cropHeight : cropWidth;
        var rotatedHeight = (turn & 1) != 0 ? cropWidth : cropHeight;
        var targetWidth = instruction.width() < 0 ? rotatedWidth : instruction.width();
        var targetHeight = instruction.height() < 0 ? rotatedHeight : instruction.height();
        if (targetWidth <= 0 || targetHeight <= 0) {
            return;
        }
        var startX = Math.max(0, -instruction.x());
        var startY = Math.max(0, -instruction.y());
        var endX = Math.min(targetWidth, width - instruction.x());
        var endY = Math.min(targetHeight, height - instruction.y());
        var samples = layer.samples();
        for (var v = startY; v < endY; v++) {
            var ry = (int) Math.min(((2 * v + 1) * rotatedHeight) / (2 * targetHeight), rotatedHeight - 1);
            for (var u = startX; u < endX; u++) {
                var rx = (int) Math.min(((2 * u + 1) * rotatedWidth) / (2 * targetWidth), rotatedWidth - 1);
                if (flip) {
                    rx = rotatedWidth - 1 - rx;
                }
                int sx;
                int sy;
                switch (turn) {
                    case 1 -> {
                        sx = ry;
                        sy = cropHeight - 1 - rx;
                    }
                    case 2 -> {
                        sx = cropWidth - 1 - rx;
                        sy = cropHeight - 1 - ry;
                    }
                    case 3 -> {
                        sx = cropWidth - 1 - ry;
                        sy = rx;
                    }
                    default -> {
                        sx = rx;
                        sy = ry;
                    }
                }
                var source = ((cropY + sy) * layer.width() + cropX + sx) * 4;
                var target = (int) (((instruction.y() + v) * width + instruction.x() + u) * 4);
                blend(samples, source, layer.alpha(), target);
            }
        }
    }

    /**
     * Composites one sample over the render area with the "over" operator of equation M-1, in straight alpha.
     *
     * @param samples the layer samples
     * @param source the index of the layer sample
     * @param alpha whether the layer has an opacity channel
     * @param target the index of the render area sample
     */
    private void blend(int[] samples, int source, boolean alpha, int target) {
        var top = alpha ? samples[source + 3] : 65535;
        if (top == 65535) {
            canvas[target] = samples[source];
            canvas[target + 1] = samples[source + 1];
            canvas[target + 2] = samples[source + 2];
            canvas[target + 3] = 65535;
            return;
        }
        if (top == 0) {
            return;
        }
        var at = top / 65535.0;
        var ab = canvas[target + 3] / 65535.0;
        var ac = 1 - (1 - at) * (1 - ab);
        for (var i = 0; i < 3; i++) {
            var value = (at * samples[source + i] + (1 - at) * ab * canvas[target + i]) / ac;
            canvas[target + i] = (int) Math.clamp(Math.round(value), 0, 65535);
        }
        canvas[target + 3] = (int) Math.clamp(Math.round(ac * 65535), 0, 65535);
    }

    /**
     * Copies the render area into a new picture.
     *
     * @return the picture, 16-bit when the frames are wide
     */
    private Picture picture() {
        var hasAlpha = false;
        for (var i = 3; i < canvas.length && !hasAlpha; i += 4) {
            hasAlpha = canvas[i] != 65535;
        }
        if (wide) {
            var pixels = new short[canvas.length];
            for (var i = 0; i < pixels.length; i++) {
                pixels[i] = (short) canvas[i];
            }
            return Rgba16Picture.of(width, height, width * 4, pixels, hasAlpha);
        }
        var pixels = new byte[canvas.length];
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) ((canvas[i] * 255L + 32767) / 65535);
        }
        return RgbaPicture.of(width, height, width * 4, pixels, hasAlpha);
    }
}

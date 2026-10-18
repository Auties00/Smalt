package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Reads the frames of an APNG, or the single frame of a static PNG, composited onto the canvas.
 *
 * <p>The canvas has the {@code IHDR} size and starts fully transparent black. Each frame is
 * decoded into a frame buffer, its region is saved first when its dispose operation is
 * {@code PREVIOUS}, and it is written onto the canvas with its blend operation; the frame's
 * disposal is applied when the next frame is requested. A first frame whose dispose operation is
 * {@code PREVIOUS} is treated as {@code BACKGROUND} (PNG 3rd edition 11.3.6.2). When the first
 * {@code fcTL} follows {@code IDAT}, the static image is not part of the animation and is
 * skipped.
 *
 * <p>Sequence numbers must run from 0 without gaps across {@code fcTL} and {@code fdAT}; frame
 * regions must lie inside the canvas; the default image's frame must cover the whole canvas; any
 * violation, a truncated or corrupt frame, or an {@code fcTL}/{@code fdAT} CRC mismatch raises
 * {@link MediaException.Malformed}. Frames beyond the count declared by {@code acTL} are ignored;
 * when the datastream holds fewer, the animation ends after the last one present. A missing
 * {@code IEND} is tolerated.
 *
 * <p>{@code OVER} is straight-alpha Porter-Duff compositing in 8-bit integers. With source
 * alpha {@code sa} and canvas alpha {@code da}: a source pixel with {@code sa = 0} leaves the
 * canvas unchanged; one with {@code sa = 255}, or over a canvas pixel with {@code da = 0}, is
 * copied; over an opaque canvas pixel each color becomes
 * {@code round((src * sa + dst * (255 - sa)) / 255)}, the integer form of the PNG 3rd edition
 * 13.16 compositing equation; over a translucent canvas pixel the alpha becomes
 * {@code sa + round((255 - sa) * da / 255)} and each color
 * {@code floor((src * sa + floor(dst * da * (255 - sa) / 255)) / alpha)}.
 *
 * @implNote This implementation's rounding for {@code OVER} was chosen to agree bit for bit with
 * the APNG decoder of ffmpeg, the test oracle (measured on randomized pixel pairs covering every
 * alpha combination), so that composited frames can be compared exactly. The specification
 * fixes only the real-valued equation.
 */
final class ApngAnimationReader implements AnimationReader {
    /**
     * The operation the calls of this reader form, whose computing time decides how long a call keeps
     * its carrier thread.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The chunk reader, positioned inside the datastream.
     */
    private final PngReader reader;

    /**
     * The pixel decoder shared by every frame.
     */
    private final PngPixelDecoder pixels;

    /**
     * The composited canvas handed out by {@link #frame()}.
     */
    private final RgbaPicture canvas;

    /**
     * The canvas samples.
     */
    private final byte[] canvasPixels;

    /**
     * The canvas width.
     */
    private final int width;

    /**
     * The canvas height.
     */
    private final int height;

    /**
     * Whether the datastream is an APNG rather than a static PNG.
     */
    private final boolean animated;

    /**
     * The number of frames declared.
     */
    private final int frameCount;

    /**
     * The number of plays declared, {@code 0} for forever.
     */
    private final int loopCount;

    /**
     * The decoded pixels of the current frame, before blending.
     */
    private byte[] frameBuffer;

    /**
     * The canvas region saved for a {@code PREVIOUS} disposal.
     */
    private byte[] saved;

    /**
     * The frame control of the last rendered frame, or {@code null}.
     */
    private ApngFrameControl previous;

    /**
     * The effective dispose operation of the last rendered frame.
     */
    private int previousDispose;

    /**
     * The number of frames rendered.
     */
    private int index;

    /**
     * Whether the animation has ended or failed.
     */
    private boolean ended;

    /**
     * The display time of the current frame, in milliseconds.
     */
    private int duration;

    /**
     * Constructs a reader over a datastream whose prelude has been read.
     *
     * @param reader the chunk reader, positioned at the start of the image data
     * @param limits the limits checked before allocating
     * @throws MediaException.Malformed if the APNG chunks before {@code IDAT} are invalid
     * @throws MediaException.LimitExceeded if the canvas or frame count exceeds the limits
     */
    ApngAnimationReader(PngReader reader, MediaLimits limits) {
        var header = reader.header();
        this.reader = reader;
        this.width = header.width();
        this.height = header.height();
        LimitChecks.checkPicture(limits, width, height);
        LimitChecks.checkAllocation(limits, (long) width * height * 4);
        this.animated = reader.animated();
        if (animated) {
            if (reader.animationError() != null) {
                throw new MediaException.Malformed("invalid APNG: " + reader.animationError());
            }
            LimitChecks.checkFrames(limits, reader.animationFrames());
            var control = reader.defaultFrameControl();
            if (control != null) {
                reader.checkSequence(control.sequence());
                if (control.x() != 0 || control.y() != 0 || control.width() != width || control.height() != height) {
                    throw new MediaException.Malformed("APNG default image frame does not cover the canvas");
                }
                control.validate(width, height);
            }
            this.frameCount = reader.animationFrames();
            this.loopCount = reader.animationPlays();
        } else {
            this.frameCount = 1;
            this.loopCount = 1;
        }
        this.canvas = RgbaPicture.allocate(width, height, animated || reader.hasAlpha());
        this.canvasPixels = canvas.pixels();
        this.pixels = new PngPixelDecoder(header,
                PngRowConverter.of(header, reader.palette(), reader.paletteSize(), reader.transparencyKey()),
                PngFilterKernel.INSTANCE);
    }

    /**
     * The sum of the durations of the frames returned so far, in microseconds.
     */
    private long elapsedMicros;

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
        return loopCount;
    }

    @Override
    public int frameCount() {
        return frameCount;
    }

    @Override
    public AnimationFrame read() {
        if (!advance()) {
            return null;
        }
        var duration = durationMillis() * 1000L;
        var frame = new AnimationFrame(frame(), elapsedMicros, duration);
        elapsedMicros += duration;
        return frame;
    }

    /**
     * Composites the next frame onto the canvas.
     *
     * @return {@code true} when a frame was rendered, {@code false} at the end of the animation
     */
    private boolean advance() {
        try (var _ = ParallelBands.operation(operation)) {
            if (ended) {
                return false;
            }
            try {
                if (renderNext()) {
                    return true;
                }
                ended = true;
                return false;
            } catch (RuntimeException exception) {
                ended = true;
                throw Guards.translate("APNG frame", exception);
            }
        }
    }

    /**
     * Renders the next frame onto the canvas.
     *
     * @return {@code true} when a frame was rendered, {@code false} at the end
     */
    private boolean renderNext() {
        if (!animated) {
            if (index == 1) {
                return false;
            }
            pixels.decode(reader, width, height, canvasPixels, 0, width * 4);
            index = 1;
            duration = 0;
            return true;
        }
        if (index >= frameCount) {
            return false;
        }
        if (previous != null) {
            dispose();
        }
        ApngFrameControl control;
        if (index == 0 && reader.defaultFrameControl() != null) {
            control = reader.defaultFrameControl();
        } else {
            if (index == 0) {
                reader.drainImageData();
            }
            control = nextFrameControl();
            if (control == null) {
                return false;
            }
            control.validate(width, height);
            reader.beginFrameData();
        }
        var frameWidth = (int) control.width();
        var frameHeight = (int) control.height();
        var dispose = control.dispose();
        if (index == 0 && dispose == ApngFrameControl.DISPOSE_PREVIOUS) {
            dispose = ApngFrameControl.DISPOSE_BACKGROUND;
        }
        var frameBytes = frameWidth * frameHeight * 4;
        if (frameBuffer == null || frameBuffer.length < frameBytes) {
            frameBuffer = new byte[frameBytes];
        }
        if (dispose == ApngFrameControl.DISPOSE_PREVIOUS) {
            saveRegion(control);
        }
        pixels.decode(reader, frameWidth, frameHeight, frameBuffer, 0, frameWidth * 4);
        if (index > 0 || reader.defaultFrameControl() == null) {
            pixels.checkEnd(reader);
        }
        reader.drainImageData();
        if (control.blend() == ApngFrameControl.BLEND_OVER && index > 0) {
            blendOver(control);
        } else {
            blendSource(control);
        }
        previous = control;
        previousDispose = dispose;
        duration = control.durationMillis();
        index++;
        return true;
    }

    /**
     * Reads chunks up to the next {@code fcTL} and parses it.
     *
     * @return the frame control, or {@code null} at {@code IEND} or the end of the input
     * @throws MediaException.Malformed if a chunk is misplaced, corrupt or out of sequence
     */
    private ApngFrameControl nextFrameControl() {
        while (reader.nextChunk()) {
            switch (reader.chunkType()) {
                case PngChunks.FCTL -> {
                    if (reader.chunkLength() != ApngFrameControl.LENGTH) {
                        throw new MediaException.Malformed("fcTL has invalid length " + reader.chunkLength());
                    }
                    var data = reader.readChunk(false);
                    if (data == null) {
                        throw new MediaException.Malformed("CRC mismatch in fcTL");
                    }
                    var control = ApngFrameControl.parse(data);
                    reader.checkSequence(control.sequence());
                    return control;
                }
                case PngChunks.IEND -> {
                    return null;
                }
                case PngChunks.FDAT -> throw new MediaException.Malformed("fdAT chunk without a preceding fcTL");
                case PngChunks.IDAT -> throw new MediaException.Malformed("IDAT chunks are not consecutive");
                default -> reader.skipUnknown();
            }
        }
        return null;
    }

    /**
     * Applies the disposal of the last rendered frame.
     */
    private void dispose() {
        var x = (int) previous.x();
        var y = (int) previous.y();
        var frameWidth = (int) previous.width();
        var frameHeight = (int) previous.height();
        var rowBytes = frameWidth * 4;
        switch (previousDispose) {
            case ApngFrameControl.DISPOSE_BACKGROUND -> {
                for (var row = 0; row < frameHeight; row++) {
                    var start = ((y + row) * width + x) * 4;
                    Arrays.fill(canvasPixels, start, start + rowBytes, (byte) 0);
                }
            }
            case ApngFrameControl.DISPOSE_PREVIOUS -> {
                for (var row = 0; row < frameHeight; row++) {
                    System.arraycopy(saved, row * rowBytes, canvasPixels, ((y + row) * width + x) * 4, rowBytes);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Saves the canvas region a frame covers, for its {@code PREVIOUS} disposal.
     *
     * @param control the frame control
     */
    private void saveRegion(ApngFrameControl control) {
        var x = (int) control.x();
        var y = (int) control.y();
        var frameHeight = (int) control.height();
        var rowBytes = (int) control.width() * 4;
        if (saved == null || saved.length < rowBytes * frameHeight) {
            saved = new byte[rowBytes * frameHeight];
        }
        for (var row = 0; row < frameHeight; row++) {
            System.arraycopy(canvasPixels, ((y + row) * width + x) * 4, saved, row * rowBytes, rowBytes);
        }
    }

    /**
     * Replaces the frame region of the canvas with the frame.
     *
     * @param control the frame control
     */
    private void blendSource(ApngFrameControl control) {
        var x = (int) control.x();
        var y = (int) control.y();
        var frameHeight = (int) control.height();
        var rowBytes = (int) control.width() * 4;
        for (var row = 0; row < frameHeight; row++) {
            System.arraycopy(frameBuffer, row * rowBytes, canvasPixels, ((y + row) * width + x) * 4, rowBytes);
        }
    }

    /**
     * Composites the frame over its region of the canvas with the formula described on the class.
     *
     * @param control the frame control
     */
    private void blendOver(ApngFrameControl control) {
        var x = (int) control.x();
        var y = (int) control.y();
        var frameWidth = (int) control.width();
        var frameHeight = (int) control.height();
        var source = frameBuffer;
        var target = canvasPixels;
        for (var row = 0; row < frameHeight; row++) {
            var s = row * frameWidth * 4;
            var t = ((y + row) * width + x) * 4;
            for (var column = 0; column < frameWidth; column++, s += 4, t += 4) {
                var sourceAlpha = source[s + 3] & 0xFF;
                if (sourceAlpha == 0) {
                    continue;
                }
                var targetAlpha = target[t + 3] & 0xFF;
                if (sourceAlpha == 0xFF || targetAlpha == 0) {
                    target[t] = source[s];
                    target[t + 1] = source[s + 1];
                    target[t + 2] = source[s + 2];
                    target[t + 3] = source[s + 3];
                    continue;
                }
                var inverse = 255 - sourceAlpha;
                if (targetAlpha == 0xFF) {
                    target[t] = (byte) (((source[s] & 0xFF) * sourceAlpha + (target[t] & 0xFF) * inverse + 127) / 255);
                    target[t + 1] = (byte) (((source[s + 1] & 0xFF) * sourceAlpha + (target[t + 1] & 0xFF) * inverse + 127) / 255);
                    target[t + 2] = (byte) (((source[s + 2] & 0xFF) * sourceAlpha + (target[t + 2] & 0xFF) * inverse + 127) / 255);
                    continue;
                }
                var weight = targetAlpha * inverse;
                var alpha = sourceAlpha + (weight * 2 + 255) / 510;
                target[t] = (byte) (((source[s] & 0xFF) * sourceAlpha + (target[t] & 0xFF) * weight / 255) / alpha);
                target[t + 1] = (byte) (((source[s + 1] & 0xFF) * sourceAlpha + (target[t + 1] & 0xFF) * weight / 255) / alpha);
                target[t + 2] = (byte) (((source[s + 2] & 0xFF) * sourceAlpha + (target[t + 2] & 0xFF) * weight / 255) / alpha);
                target[t + 3] = (byte) alpha;
            }
        }
    }

    /**
     * Returns the canvas holding the frame rendered by the last successful {@link #advance()}.
     *
     * @return the canvas
     * @throws IllegalStateException if no frame was rendered yet
     */
    private RgbaPicture frame() {
        if (index == 0) {
            throw new IllegalStateException("no frame has been rendered yet");
        }
        return canvas;
    }

    /**
     * Returns how long the frame rendered by the last successful {@link #advance()} is displayed.
     *
     * @return the duration in milliseconds, as stored
     * @throws IllegalStateException if no frame was rendered yet
     */
    private int durationMillis() {
        if (index == 0) {
            throw new IllegalStateException("no frame has been rendered yet");
        }
        return duration;
    }

    /**
     * Releases the native zlib state; the source is not closed.
     */
    @Override
    public void close() {
        ended = true;
        pixels.close();
    }
}

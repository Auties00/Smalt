package com.github.auties00.smalt.video.mpeg4.internal.object;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes the VOPs of a video object layer that needs the object decoder and hands its pictures out in display order,
 * as the reference decoder of ISO/IEC 14496-5 writes them.
 *
 * <p>Pictures of arbitrarily shaped layers cover the display rectangle that starts at the origin of the VOP
 * coordinates: the size given at construction, or, when none is given, the extent of the first VOP with a bounding
 * rectangle (its right and bottom edges). {@link VopRenderer} describes the samples of the pictures.
 *
 * <p>An instance is not thread-safe; the pictures it hands out are valid until the next call.
 */
public final class ObjectStream {
    /**
     * Receives the pictures in display order.
     */
    public interface Sink {
        /**
         * Hands out one picture.
         *
         * @param picture the picture, valid until the call returns
         * @param time the VOP time in ticks of the layer's time resolution
         * @param keyframe whether the picture comes from an intra VOP
         */
        void picture(Picture picture, long time, boolean keyframe);
    }

    /**
     * The display size the probe decoder of a layer without a display size is built with.
     */
    private static final int PROBE_SIZE = 16;

    private final byte[] volHeader;
    private final MediaLimits limits;
    private ObjectDecoder decoder;
    private boolean deferred;
    private final List<byte[]> pending = new ArrayList<>();
    private boolean cachedRef;
    private boolean cachedCoded;
    private boolean cachedKey;
    private long cachedTime;
    private boolean emitNotCoded;
    private final VopRenderer renderer;

    /**
     * Builds the decoder of a layer.
     *
     * @param volHeader the bytes of the layer from its start code to the end of its header
     * @param offset the index of the start code
     * @param length the length of the header with its start code
     * @param displayWidth the width of the display of an arbitrarily shaped layer, 0 to take it from the first VOP
     * @param displayHeight the height of the display of an arbitrarily shaped layer, 0 to take it from the first VOP
     * @param color the color description of the pictures
     * @param highBitDepth whether samples of more than 8 bits keep their depth
     * @param limits the limits bounding the picture size
     * @throws MediaException.Malformed if the header is corrupt
     * @throws MediaException.Unsupported if the layer uses a tool the object decoder lacks
     * @throws MediaException.LimitExceeded if the pictures exceed the limits
     */
    public ObjectStream(byte[] volHeader, int offset, int length, int displayWidth, int displayHeight,
                        ColorInfo color, boolean highBitDepth, MediaLimits limits) {
        this.volHeader = Arrays.copyOfRange(volHeader, offset, offset + length);
        this.limits = limits;
        this.renderer = new VopRenderer(color, highBitDepth);
        var known = displayWidth > 0 && displayHeight > 0;
        decoder = build(known ? displayWidth : PROBE_SIZE, known ? displayHeight : PROBE_SIZE);
        if (decoder.volMode().volType == VolMode.ENHN_LAYER) {
            throw new MediaException.Unsupported("mpeg-4 scalability", "an enhancement layer decodes only with its "
                    + "base layer, as the second track of a scalable object");
        }
        deferred = !known && decoder.volMode().alphaUsage != VopPlanes.RECTANGLE;
        if (!deferred) {
            LimitChecks.checkPicture(limits, width(), height());
        }
    }

    private ObjectDecoder build(int displayWidth, int displayHeight) {
        try {
            return new ObjectDecoder(volHeader, 0, volHeader.length, displayWidth, displayHeight, 0, 0);
        } catch (CorruptStream exception) {
            throw new MediaException.Malformed("corrupt MPEG-4 video object layer header");
        } catch (UnsupportedTool exception) {
            throw new MediaException.Unsupported(exception.getMessage(), exception.getMessage()
                    + " is not supported");
        } catch (IndexOutOfBoundsException | ArithmeticException | NegativeArraySizeException exception) {
            throw new MediaException.Malformed("corrupt MPEG-4 video object layer header", exception);
        }
    }

    /**
     * {@return the width of the pictures, 0 while it is not known yet}
     */
    public int width() {
        return deferred ? 0 : decoder.displayWidth();
    }

    /**
     * {@return the height of the pictures, 0 while it is not known yet}
     */
    public int height() {
        return deferred ? 0 : decoder.displayHeight();
    }

    /**
     * {@return the ticks per second of the VOP times}
     */
    public int timeResolution() {
        return decoder.volMode().clockRate;
    }

    /**
     * {@return whether the pictures have an alpha plane}
     */
    public boolean hasAlpha() {
        return decoder.volMode().alphaUsage != VopPlanes.RECTANGLE;
    }

    /**
     * Makes VOPs that are not coded produce a picture, the previous one of a rectangular layer and an empty one of a
     * shaped layer, as the reference decoder writes them; by default they produce none.
     */
    void emitNotCoded(boolean enabled) {
        this.emitNotCoded = enabled;
    }

    /**
     * Decodes the VOP that starts at a group of VOP or VOP start code.
     *
     * @param data the bytes
     * @param offset the index of the start code
     * @param length the length up to the next VOP
     * @param sink the receiver of the pictures that became due
     * @return whether the data held a coded VOP
     * @throws MediaException.Malformed if the VOP is corrupt
     * @throws MediaException.Unsupported if the VOP uses a tool the object decoder lacks
     * @throws MediaException.LimitExceeded if the display taken from the VOP exceeds the limits
     */
    public boolean decode(byte[] data, int offset, int length, Sink sink) {
        if (deferred && !resolveDisplay(data, offset, length, sink)) {
            return false;
        }
        return decodeVop(data, offset, length, sink);
    }

    /**
     * Takes the display of a layer without a display size from the bounding rectangle of a VOP; the VOPs before it
     * carry no picture and are kept to be decoded once the display is known.
     *
     * @return whether the display is known now
     */
    private boolean resolveDisplay(byte[] data, int offset, int length, Sink sink) {
        int[] extent;
        try {
            extent = decoder.vopExtent(data, offset, length);
        } catch (CorruptStream | IndexOutOfBoundsException | ArithmeticException exception) {
            throw new MediaException.Malformed("corrupt MPEG-4 video object plane");
        }
        if (extent == null) {
            LimitChecks.checkAllocation(limits, pendingBytes() + (long) length);
            pending.add(Arrays.copyOfRange(data, offset, offset + length));
            return false;
        }
        var width = (Math.max(PROBE_SIZE, extent[0]) + 1) & ~1;
        var height = (Math.max(PROBE_SIZE, extent[1]) + 1) & ~1;
        LimitChecks.checkPicture(limits, width, height);
        decoder = build(width, height);
        deferred = false;
        for (var vop : pending) {
            decodeVop(vop, 0, vop.length, sink);
        }
        pending.clear();
        return true;
    }

    private long pendingBytes() {
        var total = 0L;
        for (var vop : pending) {
            total += vop.length;
        }
        return total;
    }

    private boolean decodeVop(byte[] data, int offset, int length, Sink sink) {
        int result;
        try {
            result = decoder.decode(data, offset, length, null);
        } catch (CorruptStream exception) {
            throw new MediaException.Malformed("corrupt MPEG-4 video object plane");
        } catch (UnsupportedTool exception) {
            throw new MediaException.Unsupported(exception.getMessage(), exception.getMessage()
                    + " is not supported");
        } catch (IndexOutOfBoundsException | ArithmeticException | NegativeArraySizeException exception) {
            throw new MediaException.Malformed("corrupt MPEG-4 video object plane", exception);
        }
        if (result == ObjectDecoder.EOF) {
            return false;
        }
        var coded = result == ObjectDecoder.CODED;
        var vopmd = decoder.vopMode();
        var key = vopmd.predType == VopMode.IVOP;
        if (decoder.sprite() == 1) {
            if (coded && vopmd.predType == VopMode.SPRITE) {
                show(decoder.reconCurr(), decoder.time(), false, sink);
                return true;
            }
            return false;
        }
        if (vopmd.predType == VopMode.BVOP) {
            if (coded) {
                show(decoder.reconCurr(), decoder.time(), false, sink);
            } else {
                notCoded(decoder.time(), sink);
            }
            return coded;
        }
        if (cachedRef) {
            if (cachedCoded) {
                show(decoder.pastReference(), cachedTime, cachedKey, sink);
            } else {
                notCoded(cachedTime, sink);
            }
        }
        cachedRef = true;
        cachedCoded = coded;
        cachedKey = key;
        cachedTime = decoder.time();
        return coded;
    }

    /**
     * Hands out the reference picture held for reordering.
     *
     * @param sink the receiver
     */
    public void flush(Sink sink) {
        if (cachedRef) {
            cachedRef = false;
            if (cachedCoded) {
                show(decoder.futureReference(), cachedTime, cachedKey, sink);
            } else {
                notCoded(cachedTime, sink);
            }
        }
    }

    /**
     * Forgets the held picture, as after a seek.
     */
    public void reset() {
        cachedRef = false;
    }

    private void notCoded(long time, Sink sink) {
        if (!emitNotCoded) {
            return;
        }
        if (decoder.volMode().alphaUsage == VopPlanes.RECTANGLE && renderer.last() != null) {
            sink.picture(renderer.last(), time, false);
            return;
        }
        sink.picture(renderer.empty(decoder, width(), height()), time, false);
    }

    private void show(VopPlanes vop, long time, boolean key, Sink sink) {
        sink.picture(renderer.render(decoder, vop, width(), height()), time, key);
    }
}

/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * app/decoder/decoder.cpp: the decoding loops of spatial and temporal scalability). Copyright 1996-2000 Microsoft
 * Corporation, Sharp Corporation, Samsung AIT and the contributors named in the sources; ISO/IEC free license for
 * products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Decodes the two layers of a scalable video object, a base layer and an enhancement layer of spatial or temporal
 * scalability (ISO/IEC 14496-2 7.9), each given as its own sequence of VOPs, and hands out the enhanced pictures in
 * display order as the reference decoder of ISO/IEC 14496-5 writes them: the enhancement layer's pictures for spatial
 * scalability, the pictures of both layers for temporal scalability. The pictures of an enhancement layer with
 * {@code enhancement_type} 1 are composed over their reference layer by a {@link BackgroundComposer}: always for
 * temporal scalability, with {@code background_composition} for spatial scalability.
 *
 * <p>The layers are told apart as the reference decoder tells them apart: spatial scalability when the first VOPs of
 * both layers have the same time, temporal scalability otherwise. VOPs are queued per layer and decoded in the order
 * the reference decoder reads them, which looks ahead in both layers; pictures come out once enough of both layers
 * is queued, and the rest at {@link #flush(ObjectStream.Sink)}.
 *
 * <p>An instance is not thread-safe; the pictures it hands out are valid until the next call.
 *
 * @implNote This implementation starts the next step of the reference decoder's loop of temporal scalability only
 * when each layer has {@value #LOOKAHEAD} VOPs queued (or at the end), which covers the B-VOPs and enhancement VOPs
 * one step reads; a step that runs out of VOPs before that treats the layer as ended.
 */
public final class ScalableStream {
    /**
     * The VOPs of each layer queued before a step of temporal scalability starts.
     */
    static final int LOOKAHEAD = 16;

    /**
     * The time of a VOP after every other, for a base layer that ends with a B-VOP.
     */
    private static final int MAX_TIME = 99999;

    private final byte[] baseHeader;
    private final byte[] enhancementHeader;
    private final int displayWidth;
    private final int displayHeight;
    private final ObjectDecoder base;
    private final ObjectDecoder enhancement;
    private final MediaLimits limits;
    private final VopRenderer baseRenderer;
    private final VopRenderer enhancementRenderer;
    private final BackgroundComposer composer = new BackgroundComposer();
    private final ArrayDeque<byte[]> baseQueue = new ArrayDeque<>();
    private final ArrayDeque<byte[]> enhancementQueue = new ArrayDeque<>();
    private long queuedBytes;
    private boolean modeKnown;
    private boolean temporal;
    private boolean ended;
    private boolean emitNotCoded;
    private int iEof;

    private int baseTime = -1;
    private int basePredType;
    private VopPlanes stackVop;
    private int stackTime = -1;
    private final Rct stackRctBase = new Rct();
    private int[] stackShapeModes;
    private MotionVector[] stackShapeVectors;
    private int stackMbx;
    private int stackMby;

    private boolean temporalStarted;
    private int tNextBase;
    private int tNextEnhc;
    private int tPvopBase;
    private boolean cacheRefFrame;
    private boolean cachedRefFrameCoded;
    private int cachedTime;
    private boolean cachedKey;

    /**
     * Builds the decoders of the two layers.
     *
     * @param baseHeader the bytes of the base layer's video object layer header, from its start code
     * @param enhancementHeader the bytes of the enhancement layer's video object layer header, from its start code
     * @param displayWidth the width of the display of an arbitrarily shaped base layer, or 0 for 352
     * @param displayHeight the height of the display of an arbitrarily shaped base layer, or 0 for 288
     * @param enhancedWidth the width of the display of an arbitrarily shaped enhancement layer of spatial
     * scalability, or 0 for the base display scaled by the layer's sampling factors
     * @param enhancedHeight the height of that display, or 0
     * @param color the color description of the pictures
     * @param highBitDepth whether samples of more than 8 bits keep their depth
     * @param limits the limits bounding the picture size
     * @throws MediaException.Malformed if a header is corrupt or the layers are not a base layer and an enhancement
     * layer
     * @throws MediaException.Unsupported if a layer uses a tool the object decoder lacks
     * @throws MediaException.LimitExceeded if the pictures exceed the limits
     */
    public ScalableStream(byte[] baseHeader, byte[] enhancementHeader, int displayWidth, int displayHeight,
                          int enhancedWidth, int enhancedHeight, ColorInfo color, boolean highBitDepth,
                          MediaLimits limits) {
        this.baseHeader = baseHeader.clone();
        this.enhancementHeader = enhancementHeader.clone();
        this.limits = limits;
        this.displayWidth = displayWidth > 0 ? displayWidth : 352;
        this.displayHeight = displayHeight > 0 ? displayHeight : 288;
        base = build(this.baseHeader, this.displayWidth, this.displayHeight, 0, 0);
        var probe = build(this.enhancementHeader, this.displayWidth, this.displayHeight, 0, 0);
        var volmd = probe.volMode();
        if (volmd.volType != VolMode.ENHN_LAYER || base.volMode().volType != VolMode.BASE_LAYER) {
            throw new MediaException.Malformed("the layers of a scalable MPEG-4 object are not a base layer and an "
                    + "enhancement layer");
        }
        if (volmd.alphaUsage == VopPlanes.ONE_BIT) {
            var width = enhancedWidth > 0 ? enhancedWidth : this.displayWidth * volmd.horSamplingFactorNShape
                    / Math.max(1, volmd.horSamplingFactorMShape);
            var height = enhancedHeight > 0 ? enhancedHeight : this.displayHeight * volmd.verSamplingFactorNShape
                    / Math.max(1, volmd.verSamplingFactorMShape);
            LimitChecks.checkPicture(limits, width, height);
            enhancement = build(this.enhancementHeader, width, height, width, height);
        } else {
            enhancement = probe;
        }
        LimitChecks.checkPicture(limits, base.displayWidth(), base.displayHeight());
        LimitChecks.checkPicture(limits, enhancement.displayWidth(), enhancement.displayHeight());
        baseRenderer = new VopRenderer(color, highBitDepth);
        enhancementRenderer = new VopRenderer(color, highBitDepth);
    }

    private static ObjectDecoder build(byte[] header, int width, int height, int spatialWidth, int spatialHeight) {
        try {
            return new ObjectDecoder(header, 0, header.length, width, height, spatialWidth, spatialHeight);
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
     * {@return the width of the pictures: the enhancement layer's for spatial scalability, the base layer's for
     * temporal scalability, 0 until the first VOPs of both layers tell the kind of scalability}
     */
    public int width() {
        return !modeKnown ? 0 : temporal ? base.displayWidth() : enhancement.displayWidth();
    }

    /**
     * {@return the height of the pictures, 0 until the kind of scalability is known}
     */
    public int height() {
        return !modeKnown ? 0 : temporal ? base.displayHeight() : enhancement.displayHeight();
    }

    /**
     * {@return the ticks per second of the times the pictures are handed out with}
     */
    public int timeResolution() {
        return enhancement.volMode().clockRate;
    }

    /**
     * Makes VOPs that are not coded produce a picture, as the reference decoder writes them; by default they produce
     * none.
     */
    void emitNotCoded(boolean enabled) {
        this.emitNotCoded = enabled;
    }

    /**
     * Queues the VOP that starts at a group of VOP or VOP start code and decodes what became decodable.
     *
     * @param enhancementLayer whether the VOP belongs to the enhancement layer
     * @param data the bytes
     * @param offset the index of the start code
     * @param length the length up to the next VOP
     * @param sink the receiver of the pictures that became due
     * @throws MediaException.Malformed if a VOP is corrupt
     * @throws MediaException.Unsupported if a VOP uses a tool the object decoder lacks
     * @throws MediaException.LimitExceeded if the queued VOPs exceed the allocation limit
     */
    public void decode(boolean enhancementLayer, byte[] data, int offset, int length, ObjectStream.Sink sink) {
        LimitChecks.checkAllocation(limits, queuedBytes + length);
        queuedBytes += length;
        (enhancementLayer ? enhancementQueue : baseQueue).add(Arrays.copyOfRange(data, offset, offset + length));
        run(false, sink);
    }

    /**
     * Decodes the queued VOPs of both layers to their end and hands out the remaining pictures.
     *
     * @param sink the receiver
     * @throws MediaException.Malformed if a VOP is corrupt
     * @throws MediaException.Unsupported if a VOP uses a tool the object decoder lacks
     */
    public void flush(ObjectStream.Sink sink) {
        run(true, sink);
    }

    private void run(boolean end, ObjectStream.Sink sink) {
        try {
            if (!modeKnown) {
                if (baseQueue.isEmpty() || enhancementQueue.isEmpty()) {
                    return;
                }
                chooseMode();
            }
            if (temporal) {
                runTemporal(end, sink);
            } else {
                runSpatial(end, sink);
            }
        } catch (CorruptStream exception) {
            throw new MediaException.Malformed("corrupt MPEG-4 video object plane");
        } catch (UnsupportedTool exception) {
            throw new MediaException.Unsupported(exception.getMessage(), exception.getMessage()
                    + " is not supported");
        } catch (IndexOutOfBoundsException | ArithmeticException | NegativeArraySizeException exception) {
            throw new MediaException.Malformed("corrupt MPEG-4 video object plane", exception);
        }
    }

    /**
     * Tells spatial from temporal scalability by the times of the first VOPs of the layers, read by decoders of their
     * own as the reference decoder reads them, and sets the layers up for it.
     */
    private void chooseMode() {
        var probeBase = build(baseHeader, displayWidth, displayHeight, 0, 0);
        var probeEnhancement = build(enhancementHeader, displayWidth, displayHeight, 0, 0);
        probeBase.setClockRateScale(probeEnhancement);
        var head = baseQueue.peekFirst();
        var firstBase = probeBase.senseTime(head, 0, head.length);
        head = enhancementQueue.peekFirst();
        var firstEnhancement = probeEnhancement.senseTime(head, 0, head.length);
        temporal = firstBase != firstEnhancement;
        modeKnown = true;
        if (base.sprite() == 1) {
            throw new UnsupportedTool("mpeg-4 scalability of sprite layers");
        }
        if (temporal) {
            base.setClockRateScale(enhancement);
            enhancement.disableSpatialScalability();
            if (enhancement.volMode().alphaUsage != VopPlanes.RECTANGLE && enhancement.volMode().enhnType != 0
                    && base.volMode().alphaUsage != VopPlanes.RECTANGLE) {
                // TODO: temporal scalability with enhancement_type 1 over an arbitrarily shaped base layer. Blocked:
                //  the reference encoder makes the base layer of such an enhancement layer rectangular, so no stream
                //  exists to verify against, and the reference decoder's output for it is undefined (it writes the
                //  enhancement layer's shapes through a file it never opens).
                throw new UnsupportedTool("mpeg-4 temporal scalability with background composition over an "
                        + "arbitrarily shaped base layer");
            }
        } else if (enhancement.volMode().alphaUsage == VopPlanes.RECTANGLE) {
            enhancement.enableSpatialScalability();
        }
    }

    private int senseBase() {
        var head = baseQueue.peekFirst();
        return base.senseTime(head, 0, head == null ? 0 : head.length);
    }

    private int senseEnhancement() {
        var head = enhancementQueue.peekFirst();
        return enhancement.senseTime(head, 0, head == null ? 0 : head.length);
    }

    private int decodeBase() {
        var vop = baseQueue.pollFirst();
        if (vop == null) {
            return ObjectDecoder.EOF;
        }
        queuedBytes -= vop.length;
        return base.decode(vop, 0, vop.length, null);
    }

    private int decodeEnhancement(VopPlanes reference) {
        var vop = enhancementQueue.pollFirst();
        if (vop == null) {
            return ObjectDecoder.EOF;
        }
        queuedBytes -= vop.length;
        return enhancement.decode(vop, 0, vop.length, reference);
    }

    /**
     * Runs the loop of spatial scalability of the reference decoder, one base VOP at a time.
     */
    private void runSpatial(boolean end, ObjectStream.Sink sink) {
        while (!ended && (end || (!baseQueue.isEmpty() && enhancementQueue.size() >= 2))) {
            iEof = decodeBase();
            if (iEof == ObjectDecoder.EOF && baseTime == -1) {
                ended = true;
                return;
            }
            var baseMode = base.volMode();
            var enhancementMode = enhancement.volMode();
            VopPlanes quant;
            if (iEof == ObjectDecoder.NOT_CODED) {
                quant = null;
                if (baseMode.alphaUsage == VopPlanes.ONE_BIT) {
                    baseTime = base.lastTime();
                    basePredType = base.vopMode().predType;
                }
            } else {
                quant = base.reconCurr();
                baseTime = base.lastTime();
                basePredType = base.vopMode().predType;
            }
            var enhancementTime = senseEnhancement();
            var referenceShape = enhancementMode.hierarchyType == 0 && enhancementMode.useRefShape == 1
                    && enhancementMode.enhnType != 0;
            if (baseTime != enhancementTime && basePredType != VopMode.BVOP) {
                stackRctBase.set(base.rctBase);
                stackTime = baseTime;
                if (enhancementMode.alphaUsage == VopPlanes.ONE_BIT && !referenceShape) {
                    if (iEof != ObjectDecoder.NOT_CODED) {
                        stackVop = new VopPlanes(quant, baseMode.alphaUsage, base.currQ.whereY);
                    }
                    stackMbx = base.numMBXRef;
                    stackMby = base.numMBYRef;
                    var count = stackMbx * stackMby;
                    stackShapeModes = Arrays.copyOf(base.baseShapeModes, count);
                    stackShapeVectors = MotionVector.array(count);
                    for (var i = 0; i < count; i++) {
                        stackShapeVectors[i].set(base.mvBaseBY[i]);
                    }
                } else if (enhancementMode.alphaUsage == VopPlanes.RECTANGLE || referenceShape) {
                    if (baseMode.alphaUsage == VopPlanes.RECTANGLE) {
                        stackVop = new VopPlanes(quant, baseMode.alphaUsage, stackRctBase);
                    } else if (iEof != ObjectDecoder.NOT_CODED) {
                        stackVop = new VopPlanes(quant, baseMode.alphaUsage, base.currQ.whereY);
                    }
                }
            } else if (baseTime != -1 && baseTime == enhancementTime) {
                if (enhancementMode.alphaUsage == VopPlanes.ONE_BIT) {
                    enhancement.takeBaseShape(base.baseShapeModes, base.mvBaseBY, base.numMBBaseXRef,
                            base.numMBBaseYRef, base.rctBase);
                }
                iEof = decodeEnhancement(quant);
                showSpatial(quant, sink);
                if (stackTime != -1 && stackTime == senseEnhancement()) {
                    var stackedShape = enhancementMode.alphaUsage == VopPlanes.ONE_BIT && !referenceShape;
                    var savedModes = enhancement.baseShapeModes;
                    var savedVectors = enhancement.mvBaseBY;
                    if (stackedShape) {
                        enhancement.takeBaseShape(stackShapeModes, stackShapeVectors, stackMbx, stackMby,
                                stackRctBase);
                    }
                    var stacked = stackVop;
                    iEof = decodeEnhancement(stacked);
                    if (stackedShape) {
                        enhancement.baseShapeModes = savedModes;
                        enhancement.mvBaseBY = savedVectors;
                        stackShapeModes = null;
                        stackShapeVectors = null;
                    }
                    stackVop = null;
                    stackTime = -1;
                    showSpatial(stacked, sink);
                }
            }
            if (iEof == ObjectDecoder.EOF) {
                ended = true;
            }
        }
    }

    private void showSpatial(VopPlanes reference, ObjectStream.Sink sink) {
        if (iEof == ObjectDecoder.EOF) {
            return;
        }
        var volmd = enhancement.volMode();
        var vopmd = enhancement.vopMode();
        var key = vopmd.predType == VopMode.IVOP;
        if (vopmd.bgComposition && volmd.hierarchyType == 0 && volmd.enhnType == 1) {
            // the reference decoder composes a VOP that was not coded too, and then writes the empty picture
            if (iEof == ObjectDecoder.CODED || (emitNotCoded && reference != null)) {
                composer.composeSpatial(enhancement, reference, enhancement.displayWidth(),
                        enhancement.displayHeight());
                sink.picture(enhancementRenderer.render(enhancement, composer, true), enhancement.lastTime(), key);
            }
            if (iEof != ObjectDecoder.CODED && emitNotCoded) {
                sink.picture(enhancementRenderer.empty(enhancement, enhancement.displayWidth(),
                        enhancement.displayHeight()), enhancement.lastTime(), false);
            }
            return;
        }
        show(enhancement, enhancementRenderer, iEof == ObjectDecoder.CODED, enhancement.reconCurr(),
                enhancement.lastTime(), key, sink);
    }

    private void show(ObjectDecoder layer, VopRenderer renderer, boolean coded, VopPlanes vop, int time,
                      boolean key, ObjectStream.Sink sink) {
        var width = layer.displayWidth();
        var height = layer.displayHeight();
        if (coded) {
            sink.picture(renderer.render(layer, vop, width, height), time, key);
        } else if (emitNotCoded) {
            if (layer.volMode().alphaUsage == VopPlanes.RECTANGLE && renderer.last() != null) {
                sink.picture(renderer.last(), time, false);
            } else {
                sink.picture(renderer.empty(layer, width, height), time, false);
            }
        }
    }

    private boolean ready(boolean end) {
        return end || (baseQueue.size() >= LOOKAHEAD && enhancementQueue.size() >= LOOKAHEAD);
    }

    private void decodeTemporalEnhancement(ObjectStream.Sink sink) {
        iEof = decodeEnhancement(null);
        if (iEof != ObjectDecoder.EOF) {
            var key = enhancement.vopMode().predType == VopMode.IVOP;
            var coded = iEof != ObjectDecoder.NOT_CODED;
            if (enhancement.volMode().alphaUsage != VopPlanes.RECTANGLE && enhancement.volMode().enhnType != 0) {
                // the enhancement VOP is handed out composed over the base layer; the reference decoder composes a
                // VOP that was not coded too, and then writes the empty picture
                if (coded || emitNotCoded) {
                    composer.composeTemporal(enhancement, base.displayWidth(), base.displayHeight());
                    sink.picture(enhancementRenderer.render(enhancement, composer, false), enhancement.lastTime(),
                            key);
                }
                if (!coded && emitNotCoded) {
                    sink.picture(enhancementRenderer.empty(enhancement, base.displayWidth(), base.displayHeight()),
                            enhancement.lastTime(), false);
                }
            } else {
                show(enhancement, enhancementRenderer, coded, enhancement.reconCurr(), enhancement.lastTime(), key,
                        sink);
            }
        }
        tNextEnhc = senseEnhancement();
    }

    /**
     * Runs the loop of temporal scalability of the reference decoder, one base layer reference VOP (with the
     * B-VOPs and enhancement VOPs before it) at a time.
     */
    private void runTemporal(boolean end, ObjectStream.Sink sink) {
        if (!temporalStarted) {
            if (!ready(end)) {
                return;
            }
            temporalStarted = true;
            iEof = ObjectDecoder.CODED;
            tNextBase = senseBase();
        }
        while (!ended && ready(end)) {
            if (base.vopMode().predType != VopMode.BVOP) {
                iEof = decodeBase();
                if (iEof == ObjectDecoder.EOF) {
                    ended = true;
                    return;
                }
                base.updateBuffVOPsBase(enhancement);
                if (cacheRefFrame) {
                    show(base, baseRenderer, cachedRefFrameCoded, base.pastReference(), cachedTime, cachedKey, sink);
                } else {
                    cacheRefFrame = true;
                }
                cachedRefFrameCoded = iEof != ObjectDecoder.NOT_CODED;
                cachedTime = base.lastTime();
                cachedKey = base.vopMode().predType == VopMode.IVOP;
                tPvopBase = base.lastTime();
            } else {
                tPvopBase = MAX_TIME;
                base.copyRefQ1ToQ0();
            }
            tNextBase = senseBase();
            while (tPvopBase > tNextBase && tNextBase != ObjectDecoder.EOF) {
                iEof = decodeBase();
                base.updateBuffVOPsBase(enhancement);
                if (tNextEnhc != ObjectDecoder.EOF) {
                    tNextEnhc = senseEnhancement();
                    while (tNextBase > tNextEnhc && tNextEnhc != ObjectDecoder.EOF) {
                        decodeTemporalEnhancement(sink);
                    }
                }
                show(base, baseRenderer, iEof != ObjectDecoder.NOT_CODED, base.reconCurr(), base.lastTime(), false,
                        sink);
                tNextBase = senseBase();
            }
            if ((tNextEnhc = senseEnhancement()) != ObjectDecoder.EOF) {
                enhancement.bufferB2flush();
                while (tPvopBase > tNextEnhc && tNextEnhc != ObjectDecoder.EOF) {
                    decodeTemporalEnhancement(sink);
                }
                iEof = tNextBase;
                if (iEof == ObjectDecoder.EOF) {
                    show(base, baseRenderer, cachedRefFrameCoded, base.futureReference(), cachedTime, cachedKey,
                            sink);
                }
                enhancement.bufferB1flush();
                if (tNextBase == ObjectDecoder.EOF && tNextEnhc != ObjectDecoder.EOF) {
                    enhancement.copyBufP2ToB1();
                    while (tNextEnhc != ObjectDecoder.EOF) {
                        decodeTemporalEnhancement(sink);
                    }
                    enhancement.bufferB1flush();
                }
            }
            if (iEof == ObjectDecoder.EOF) {
                ended = true;
            }
        }
    }
}

package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.mpeg4.internal.BitReader;
import com.github.auties00.smalt.video.mpeg4.internal.Mpeg4Dsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes one picture of ITU-T H.263 with every optional mode: its video picture segments (groups of blocks or
 * slices), the macroblocks they hold, and the reconstruction of the picture and, for PB- and Improved PB-frames, of
 * its B-part.
 *
 * <p>Segments arrive one at a time as the stream router finds their headers, possibly interleaved with those of other
 * sub-bitstreams, so the decoder keeps its state between {@link #decodeSegment} calls until {@link #finish} conceals
 * the macroblocks no segment carried and applies the in-loop filters. An instance is reused from picture to picture
 * and is not thread-safe.
 */
final class PictureDecoder {
    /**
     * The picture pool.
     */
    private final PicturePool pool;

    /**
     * The macroblock parser.
     */
    private final MacroblockParser parser = new MacroblockParser();

    /**
     * The picture header.
     */
    private PictureHeader header;

    /**
     * The sub-bitstream of the picture.
     */
    private SubBitstream stream;

    /**
     * The picture being decoded: the picture itself, or the P-part of a PB- or Improved PB-frame.
     */
    PlusPicture picture;

    /**
     * The B-part of a PB- or Improved PB-frame, or {@code null}.
     */
    PlusPicture part;

    /**
     * The macroblock size scale: 1, or 2 in the Reduced-Resolution Update mode.
     */
    private int scale;

    /**
     * The macroblock columns of the coding grid.
     */
    private int columns;

    /**
     * The macroblock rows of the coding grid.
     */
    private int rows;

    /**
     * The macroblock rows of a group of blocks.
     */
    private int gobRows;

    /**
     * The macroblocks decoded.
     */
    private int decodedCount;

    /**
     * Whether each macroblock was coded or INTRA, for the loop filters.
     */
    private boolean[] coded = new boolean[0];

    /**
     * The quantizer of each macroblock, for the loop filters.
     */
    private int[] quants = new int[0];

    /**
     * The quantizer of the B-part of each macroblock of a PB- or Improved PB-frame, for the loop filters.
     */
    private int[] partQuants = new int[0];

    /**
     * Whether the blocks are transformed with the double precision reference inverse DCT instead of the integer one.
     */
    boolean referenceTransform;

    /**
     * The video picture segment being decoded.
     */
    private int segment = -1;

    /**
     * The next macroblock to decode, or -1 when the segment ended.
     */
    private int next;

    /**
     * The first macroblock of the current slice.
     */
    private int sliceStart;

    /**
     * The width of the current rectangular slice in macroblocks, or 0 for a free-running slice.
     */
    private int sliceWidth;

    /**
     * The left, top, right and bottom macroblock of every video picture segment, for the Independent Segment
     * Decoding mode, indexed by segment.
     */
    private int[] segmentBounds = new int[64];

    /**
     * The forward reference pictures of the current segment in relative index order.
     */
    private final List<PlusPicture> forward = new ArrayList<>();

    /**
     * The backward reference pictures of the current segment in relative index order.
     */
    private final List<PlusPicture> backward = new ArrayList<>();

    /**
     * The forward reference pictures of the picture layer.
     */
    private final List<PlusPicture> pictureForward = new ArrayList<>();

    /**
     * The backward reference pictures of the picture layer.
     */
    private final List<PlusPicture> pictureBackward = new ArrayList<>();

    /**
     * The reference picture warped for the Reference Picture Resampling mode, or {@code null}.
     */
    private PlusPicture warped;

    /**
     * The reference layer picture of EI- and EP-pictures, or {@code null}.
     */
    private PlusPicture upward;

    /**
     * Whether the upward reference is interpolated horizontally.
     */
    private boolean upwardHorizontal;

    /**
     * Whether the upward reference is interpolated vertically.
     */
    private boolean upwardVertical;

    /**
     * The rounding control of the picture's interpolation.
     */
    private int rounding;

    /**
     * The temporal distance of a B-picture or B-part from its forward reference (TRB).
     */
    private int trb;

    /**
     * The temporal distance between the forward and backward references (TRD).
     */
    private int trd;

    /**
     * The macroblock being parsed.
     */
    private Macroblock current = new Macroblock();

    /**
     * The macroblock parsed before, waiting for the vectors of the next one for overlapped motion compensation.
     */
    private Macroblock waiting = new Macroblock();

    /**
     * Whether {@link #waiting} holds a macroblock to reconstruct.
     */
    private boolean hasWaiting;

    /**
     * Whether some data of the picture was corrupt.
     */
    boolean damaged;

    /**
     * The ERPS layer of the current segment.
     */
    private final ErpsLayer segmentErps = new ErpsLayer();

    /**
     * The reference picture order buffer of the ERPS mode.
     */
    private final List<PlusPicture> order = new ArrayList<>();

    /**
     * The luminance prediction of a block, up to 32 by 32 samples.
     */
    private final int[] prediction = new int[32 * 32];

    /**
     * A second prediction, for bidirectional modes.
     */
    private final int[] second = new int[32 * 32];

    /**
     * The predictions of overlapped motion compensation with the remote vectors.
     */
    private final int[][] remote = new int[4][16 * 16];

    /**
     * The residual of a block.
     */
    private final int[] residual = new int[64];

    /**
     * The residual of a block upsampled to 16 by 16 samples in the Reduced-Resolution Update mode.
     */
    private final int[] upsampled = new int[256];

    /**
     * The reconstructed P-macroblock (PREC of G.5) before the loop filter: 32 by 32 luminance samples, then two 16
     * by 16 chrominance blocks.
     */
    private final int[] prec = new int[32 * 32 + 2 * 16 * 16];

    /**
     * The clip region of a prediction.
     */
    private final int[] clip = new int[4];

    /**
     * The warping parameters of the picture being resampled.
     */
    private final int[] warping = new int[8];

    /**
     * The fill color of the picture being resampled.
     */
    private final int[] fillColor = new int[3];

    /**
     * The horizontal backward vectors of the B-blocks of a PB-frame.
     */
    private final int[] partBackwardX = new int[4];

    /**
     * The vertical backward vectors of the B-blocks of a PB-frame.
     */
    private final int[] partBackwardY = new int[4];

    /**
     * Creates a picture decoder.
     *
     * @param pool the picture pool
     */
    PictureDecoder(PicturePool pool) {
        this.pool = pool;
    }

    /**
     * Returns the header of the picture.
     *
     * @return the header
     */
    PictureHeader header() {
        return header;
    }

    /**
     * Returns whether every macroblock of the picture was decoded.
     *
     * @return whether the picture is complete
     */
    boolean complete() {
        return decodedCount >= columns * rows;
    }

    /**
     * Returns whether the picture uses the Slice Structured mode.
     *
     * @return whether it does
     */
    boolean slices() {
        return header.has(LayerState.SS);
    }

    /**
     * Starts a picture after its header.
     *
     * @param header the parsed header, owned by the decoder until the next picture
     * @param stream the sub-bitstream
     * @param time the picture time in ticks
     * @param reader the stream, positioned after the picture header
     * @throws MediaException.Malformed if a reference picture is missing or the first slice header is corrupt
     */
    void begin(PictureHeader header, SubBitstream stream, long time, BitReader reader) {
        this.header = header;
        this.stream = stream;
        damaged = false;
        hasWaiting = false;
        scale = header.reducedResolution ? 2 : 1;
        var size = 16 * scale;
        var mbWidth = (header.width + 15) >> 4;
        var mbHeight = (header.height + 15) >> 4;
        columns = (mbWidth * 16 + size - 1) / size;
        rows = (mbHeight * 16 + size - 1) / size;
        var lines = header.height;
        var k = !header.reducedResolution ? (lines <= 400 ? 1 : lines <= 800 ? 2 : 4) : (lines <= 800 ? 2 : 4);
        gobRows = Math.max(1, k * 16 / size);
        var count = columns * rows;
        if (coded.length < count) {
            coded = new boolean[count];
            quants = new int[count];
            partQuants = new int[count];
        }
        Arrays.fill(coded, 0, count, false);
        Arrays.fill(quants, 0, count, header.quant);
        decodedCount = 0;
        segment = -1;
        picture = pool.acquire(header.width, header.height);
        picture.type = header.type;
        picture.layer = header.layer;
        picture.temporalReference = header.temporalReference;
        picture.extendedReference = header.extendedReference;
        picture.time = time;
        picture.rounding = header.rounding;
        picture.keyframe = header.type == PictureHeader.I || header.type == PictureHeader.EI;
        Arrays.fill(picture.modes, PlusPicture.SKIPPED);
        part = null;
        if (header.pbFrame || header.type == PictureHeader.IMPROVED_PB) {
            part = pool.acquire(header.width, header.height);
            part.type = PictureHeader.B;
            part.layer = header.layer;
        }
        rounding = header.plus ? header.rounding : 0;
        resolveReferences();
        parser.start(header, columns, rows, trb, trd);
        parser.multipleReferences = header.has(LayerState.ERPS) && header.erps.multiple;
        parser.twoBackward = header.has(LayerState.ERPS) && header.erps.twoBackward;
        if (slices()) {
            reader.seek(header.dataPosition);
            firstSlice(reader);
        } else {
            startSegment(0, 0);
            parser.startSegment(reader);
        }
    }

    /**
     * Resolves the reference pictures of the picture: the forward, backward and upward references its type uses, with
     * the selections of the Reference Picture Selection modes and the resampling of Annex P.
     *
     * @throws MediaException.Malformed if a needed reference picture is missing
     */
    private void resolveReferences() {
        pictureForward.clear();
        pictureBackward.clear();
        upward = null;
        trb = 0;
        trd = 1;
        var type = header.type;
        var layer = header.layer;
        var referenceLayer = header.referenceLayer;
        var erps = header.has(LayerState.ERPS);
        switch (type) {
            case PictureHeader.I -> {
            }
            case PictureHeader.P, PictureHeader.IMPROVED_PB -> {
                if (erps) {
                    stream.buffer(layer).order(header.erps, header.pictureNumber, order);
                    pictureForward.addAll(order);
                } else {
                    var reference = header.trp >= 0 ? stream.stored(layer, header.trp) : null;
                    pictureForward.add(reference != null ? reference : stream.anchor(layer));
                }
                var previous = stream.anchor(layer);
                if (previous != null) {
                    trd = delta(previous.temporalReference, header.temporalReference);
                    trb = header.trb;
                }
            }
            case PictureHeader.B -> {
                if (erps) {
                    stream.buffer(referenceLayer).order(header.erps, header.pictureNumber, order);
                    var backwardCount = header.erps.twoBackward ? 2 : 1;
                    for (var i = 0; i < order.size(); i++) {
                        (i < backwardCount ? pictureBackward : pictureForward).add(order.get(i));
                    }
                } else {
                    pictureBackward.add(stream.anchor(referenceLayer));
                    var reference = header.trp >= 0 ? stream.stored(referenceLayer, header.trp) : null;
                    pictureForward.add(reference != null ? reference : stream.previousAnchor(referenceLayer));
                }
                if (!pictureForward.isEmpty() && !pictureBackward.isEmpty() && pictureForward.getFirst() != null
                        && pictureBackward.getFirst() != null) {
                    var forwardReference = pictureForward.getFirst().temporalReference;
                    trd = delta(forwardReference, pictureBackward.getFirst().temporalReference);
                    trb = delta(forwardReference, header.temporalReference);
                }
            }
            case PictureHeader.EI -> upward = stream.anchor(referenceLayer);
            default -> {
                if (erps) {
                    stream.buffer(layer).order(header.erps, header.pictureNumber, order);
                    pictureForward.addAll(order);
                } else {
                    var reference = header.trp >= 0 ? stream.stored(layer, header.trp) : null;
                    pictureForward.add(reference != null ? reference : stream.anchor(layer));
                }
                upward = stream.anchor(referenceLayer);
            }
        }
        if (type != PictureHeader.I && type != PictureHeader.EI) {
            if (pictureForward.isEmpty() || pictureForward.getFirst() == null) {
                throw new MediaException.Malformed("H.263 picture without its reference picture");
            }
        }
        if (type == PictureHeader.B && (pictureBackward.isEmpty() || pictureBackward.getFirst() == null)) {
            throw new MediaException.Malformed("H.263 B-picture without its backward reference picture");
        }
        if (upward != null) {
            upwardHorizontal = header.width > upward.width;
            upwardVertical = header.height > upward.height;
        } else if (type == PictureHeader.EI || type == PictureHeader.EP) {
            throw new MediaException.Malformed("H.263 enhancement picture without its reference layer picture");
        }
        resample();
        if (type == PictureHeader.B && pictureBackward.getFirst().width != header.width) {
            throw new MediaException.Malformed("H.263 B-picture of another size than its backward reference");
        }
        forward.clear();
        forward.addAll(pictureForward);
        backward.clear();
        backward.addAll(pictureBackward);
    }

    /**
     * Warps the forward reference picture when the Reference Picture Resampling mode is invoked, explicitly or by a
     * change of the picture size (P.1).
     */
    private void resample() {
        pool.release(warped);
        warped = null;
        var type = header.type;
        if (type == PictureHeader.I || type == PictureHeader.EI || pictureForward.isEmpty()) {
            return;
        }
        var reference = pictureForward.getFirst();
        var implicit = header.plus && !header.resampling
                && (reference.width != header.width || reference.height != header.height);
        if (!header.resampling && !implicit) {
            if (reference.width != header.width || reference.height != header.height) {
                throw new MediaException.Malformed("H.263 reference picture of another size");
            }
            return;
        }
        var parameters = warping;
        Arrays.fill(parameters, 0);
        var accuracy = 16;
        var fillMode = 3;
        var fill = fillColor;
        fill[0] = 16;
        fill[1] = 128;
        fill[2] = 128;
        if (header.resampling) {
            accuracy = header.warpingAccuracy;
            if (type == PictureHeader.EP) {
                var lower = upward;
                if (lower != null && lower.warping != null) {
                    for (var i = 0; i < 8; i++) {
                        parameters[i] = header.warping[i] < 0 ? lower.warping[i]
                                : 2 * lower.warping[i] + header.warping[i];
                    }
                    fillMode = lower.fillMode;
                    System.arraycopy(lower.fillColor, 0, fill, 0, 3);
                }
            } else {
                System.arraycopy(header.warping, 0, parameters, 0, 8);
                fillMode = header.fillMode;
                System.arraycopy(header.fillColor, 0, fill, 0, 3);
            }
        }
        if (picture.warping == null) {
            picture.warping = new int[8];
        }
        System.arraycopy(parameters, 0, picture.warping, 0, 8);
        picture.fillMode = fillMode;
        System.arraycopy(fill, 0, picture.fillColor, 0, 3);
        var control = type == PictureHeader.B ? pictureBackward.getFirst().rounding : header.rounding;
        warped = pool.acquire(header.width, header.height);
        Resampler.warp(reference, warped, parameters, accuracy, fillMode, fill, control);
        pictureForward.set(0, warped);
    }

    /**
     * Returns the difference of two temporal references, positive within half the modulus.
     *
     * @param from the earlier reference
     * @param to the later reference
     * @return the difference, at least 1
     */
    private int delta(int from, int to) {
        var modulus = header.extendedReference ? 1024 : 256;
        var value = (to - from) & (modulus - 1);
        return value == 0 ? modulus : value;
    }

    /**
     * Starts a video picture segment at a macroblock.
     *
     * @param first the first macroblock
     * @param width the width of a rectangular slice in macroblocks, or 0
     */
    private void startSegment(int first, int width) {
        segment++;
        if (segmentBounds.length < (segment + 1) * 4) {
            segmentBounds = Arrays.copyOf(segmentBounds, (segment + 1) * 8);
        }
        var column = first % columns;
        var row = first / columns;
        var at = segment * 4;
        if (slices()) {
            segmentBounds[at] = width > 0 ? column : 0;
            segmentBounds[at + 1] = row;
            segmentBounds[at + 2] = width > 0 ? Math.min(columns, column + width) - 1 : columns - 1;
        } else {
            segmentBounds[at] = 0;
            segmentBounds[at + 1] = row;
            segmentBounds[at + 2] = columns - 1;
        }
        sliceStart = first;
        sliceWidth = width;
        next = first;
    }

    /**
     * Parses the slice header that follows the picture header (K.2), which lacks the start code, SSBI and SQUANT.
     *
     * @param reader the stream, positioned after the picture header
     */
    private void firstSlice(BitReader reader) {
        reader.marker("SEPB1");
        var address = reader.read(header.sliceAddressBits());
        var width = 0;
        if (header.rectangularSlices) {
            reader.marker("SEPB2");
            width = reader.read(header.sliceWidthBits()) + 1;
        }
        reader.marker("SEPB3");
        if (address >= columns * rows) {
            throw new MediaException.Malformed("H.263 slice address " + address + " outside the picture");
        }
        startSegment(address, width);
        segmentTail(reader, false);
    }

    /**
     * Parses the rest of a slice header after its start code, SEPB1 and SSBI (K.2).
     *
     * @param reader the stream
     */
    void sliceHeader(BitReader reader) {
        flushWaiting();
        var bits = header.sliceAddressBits();
        var address = reader.read(bits);
        if ((bits > 11 && !header.cpm) || (bits > 9 && header.cpm)) {
            reader.marker("SEPB2");
        }
        parser.quant = reader.read(5);
        var width = 0;
        if (header.rectangularSlices) {
            width = reader.read(header.sliceWidthBits()) + 1;
        }
        reader.marker("SEPB3");
        reader.skip(2);
        if (parser.quant == 0 || address >= columns * rows) {
            throw new MediaException.Malformed("corrupt H.263 slice header");
        }
        startSegment(address, width);
        segmentTail(reader, true);
    }

    /**
     * Parses the rest of a group of blocks header after GN and GSBI (5.2).
     *
     * @param reader the stream
     * @param number the group number
     */
    void gobHeader(BitReader reader, int number) {
        flushWaiting();
        reader.skip(2);
        parser.quant = reader.read(5);
        var first = number * gobRows * columns;
        if (parser.quant == 0 || first >= columns * rows) {
            throw new MediaException.Malformed("corrupt H.263 GOB header");
        }
        startSegment(first, 0);
        segmentTail(reader, true);
    }

    /**
     * Parses the fields the Reference Picture Selection modes add to group of blocks and slice headers (N.4.1 and
     * U.3.1), resolves the references of the segment and starts its macroblock data.
     *
     * @param reader the stream
     * @param rps whether the fields of Annex N are present
     */
    private void segmentTail(BitReader reader, boolean rps) {
        forward.clear();
        forward.addAll(pictureForward);
        backward.clear();
        backward.addAll(pictureBackward);
        var multiple = header.has(LayerState.ERPS) && header.erps.multiple;
        var twoBackward = header.has(LayerState.ERPS) && header.erps.twoBackward;
        if (rps && header.has(LayerState.RPS)) {
            if (reader.readFlag()) {
                reader.skip(header.extendedReference ? 10 : 8);
            }
            var bPicture = header.type == PictureHeader.B;
            var layer = bPicture ? header.referenceLayer : header.layer;
            PlusPicture reference;
            if (reader.readFlag()) {
                reference = stream.stored(layer, reader.read(10));
            } else {
                reference = bPicture ? stream.previousAnchor(layer) : stream.anchor(layer);
            }
            if (reference != null && !forward.isEmpty() && warped == null) {
                forward.set(0, reference);
            }
            header.readBackChannel(reader, header.cpm);
        }
        if (header.has(LayerState.ERPS)) {
            if (reader.readFlag()) {
                reader.skip(10);
            }
            if (!reader.readFlag()) {
                var layer = header.type == PictureHeader.B ? header.referenceLayer : header.layer;
                var buffer = stream.buffer(layer);
                segmentErps.parse(reader, header.type, header.width, header.height, buffer.spwi, buffer.sphi);
                if (header.type != PictureHeader.I && header.type != PictureHeader.EI) {
                    buffer.order(segmentErps, header.pictureNumber, order);
                    forward.clear();
                    backward.clear();
                    var backwardCount = header.type == PictureHeader.B ? (segmentErps.twoBackward ? 2 : 1) : 0;
                    for (var i = 0; i < order.size(); i++) {
                        (i < backwardCount ? backward : forward).add(order.get(i));
                    }
                    if (warped != null && !forward.isEmpty()) {
                        forward.set(0, warped);
                    }
                    multiple = segmentErps.multiple;
                    twoBackward = segmentErps.twoBackward;
                }
            }
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated H.263 segment header");
        }
        parser.multipleReferences = multiple;
        parser.twoBackward = twoBackward;
        parser.startSegment(reader);
    }

    /**
     * Decodes macroblocks of the current segment until a start code or the end of the data.
     *
     * @param reader the stream
     * @return the bit position of the start code that ended the segment, or -1 at the end of the data or of the
     * picture
     */
    long decodeSegment(BitReader reader) {
        var count = columns * rows;
        try {
            while (next >= 0 && next < count) {
                if (next != sliceStart && boundary(next)) {
                    var start = startCode(reader);
                    if (start >= 0) {
                        flushWaiting();
                        return start;
                    }
                }
                if (exhausted(reader)) {
                    flushWaiting();
                    return -1;
                }
                if (parser.parsed[next]) {
                    throw MacroblockParser.CORRUPT;
                }
                var mb = current;
                mb.reset(next, next % columns, next / columns);
                parser.segments[next] = segment;
                if (!parser.parse(mb)) {
                    flushWaiting();
                    return startCode(reader);
                }
                decodedCount++;
                coded[mb.index] = mb.coded || mb.intra();
                quants[mb.index] = mb.quant;
                if (hasWaiting) {
                    reconstruct(waiting);
                }
                current = waiting;
                waiting = mb;
                hasWaiting = true;
                next = advance(next);
            }
            flushWaiting();
            next = -1;
            return parser.arithmeticCoded() ? parser.arithmetic.nextStartCode(Integer.MAX_VALUE >> 1)
                    : skipToStartCode(reader);
        } catch (MediaException.Malformed | IndexOutOfBoundsException | ArithmeticException exception) {
            damaged = true;
            next = -1;
            Arrays.fill(current.coefficients, (short) 0);
            Arrays.fill(current.partCoefficients, (short) 0);
            if (hasWaiting) {
                try {
                    flushWaiting();
                } catch (MediaException.Malformed | IndexOutOfBoundsException | ArithmeticException failure) {
                    parser.parsed[waiting.index] = false;
                    Arrays.fill(waiting.coefficients, (short) 0);
                    Arrays.fill(waiting.partCoefficients, (short) 0);
                }
            }
            hasWaiting = false;
            return skipToStartCode(reader);
        }
    }

    /**
     * Returns whether a start code may precede a macroblock: at the start of a group of blocks, or anywhere in a
     * slice.
     *
     * @param mb the macroblock
     * @return whether a header may come before it
     */
    private boolean boundary(int mb) {
        if (slices()) {
            return true;
        }
        return (mb % (gobRows * columns)) == 0;
    }

    /**
     * Returns the macroblock after one in the order of the current segment, or -1 when the segment covers no more.
     *
     * @param mb the macroblock decoded
     * @return the next macroblock, or -1
     */
    private int advance(int mb) {
        if (sliceWidth > 0) {
            var column = mb % columns;
            var left = sliceStart % columns;
            var right = Math.min(columns, left + sliceWidth) - 1;
            if (column < right) {
                return mb + 1;
            }
            var row = mb / columns + 1;
            return row < rows ? row * columns + left : -1;
        }
        var result = mb + 1;
        return result < columns * rows ? result : -1;
    }

    /**
     * Returns whether the data ends before the next macroblock: nothing but stuffing remains, or the arithmetic
     * decoder reached the end without a start code. A picture of a sub-bitstream may continue in the data of a later
     * picture start code.
     *
     * @param reader the stream
     * @return whether no macroblock data remains
     */
    private boolean exhausted(BitReader reader) {
        if (parser.arithmeticCoded()) {
            return parser.arithmetic.stopped() && parser.arithmetic.nextStartCode(64) < 0;
        }
        var remaining = reader.remaining();
        return remaining <= 0 || (remaining < 32 && reader.peek((int) remaining) == 0);
    }

    /**
     * Returns the position of the start code that follows the data decoded so far.
     *
     * @param reader the stream
     * @return the bit position of the start code, or -1 when none follows
     */
    private long startCode(BitReader reader) {
        if (parser.arithmeticCoded()) {
            return parser.arithmetic.nextStartCode(64);
        }
        if (reader.remaining() < 17) {
            return -1;
        }
        var position = reader.position();
        if (reader.peek(17) == 1) {
            return position;
        }
        var aligned = (position + 7) & ~7L;
        if (aligned != position && reader.peek((int) (aligned - position)) == 0 && reader.peekAt(aligned, 17) == 1) {
            return aligned;
        }
        return -1;
    }

    /**
     * Finds the next start code after corrupt data.
     *
     * @param reader the stream
     * @return the bit position of the start code, or -1
     */
    private static long skipToStartCode(BitReader reader) {
        var limit = reader.limit();
        var run = 0;
        for (var at = Math.max(0, reader.position()); at < limit; at++) {
            if (reader.peekAt(at, 1) == 0) {
                run++;
            } else {
                if (run >= 16) {
                    return at - 16;
                }
                run = 0;
            }
        }
        return -1;
    }

    /**
     * Reconstructs the macroblock waiting for its right neighbor, if any.
     */
    private void flushWaiting() {
        if (hasWaiting) {
            hasWaiting = false;
            reconstruct(waiting);
        }
    }

    /**
     * Finishes the picture: conceals the macroblocks no segment carried from the forward reference, applies the
     * loop filters and records the macroblock data later pictures read.
     */
    void finish() {
        flushWaiting();
        var count = columns * rows;
        var concealed = false;
        for (var mb = 0; mb < count; mb++) {
            if (!parser.parsed[mb]) {
                conceal(mb);
                concealed = true;
            }
        }
        if (concealed) {
            damaged = true;
        }
        picture.damaged = damaged;
        recordSegments();
        var deblocking = header.has(LayerState.DF) && header.type != PictureHeader.B;
        var segments = header.has(LayerState.ISD) ? parser.segments : null;
        if (deblocking || header.reducedResolution) {
            LoopFilter.filter(picture, columns, rows, scale, coded, quants, segments, deblocking,
                    header.has(LayerState.MQ));
        }
        if (part != null && (deblocking || header.reducedResolution)) {
            for (var mb = 0; mb < count; mb++) {
                partQuants[mb] = Math.min(31, (5 + header.dbquant) * quants[mb] / 4);
            }
            LoopFilter.filter(part, columns, rows, scale, coded, partQuants, segments, deblocking,
                    header.has(LayerState.MQ));
        }
        pool.release(warped);
        warped = null;
    }

    /**
     * Records the video picture segments of the picture, which the Independent Segment Decoding mode of later
     * pictures with the same segmentation reads: every cell's segment and every segment's bounding rectangle.
     */
    private void recordSegments() {
        var count = segment + 1;
        Arrays.fill(picture.segmentMap, -1);
        if (picture.segmentRects.length < count * 4) {
            picture.segmentRects = new int[count * 4];
        }
        var rects = picture.segmentRects;
        for (var i = 0; i < count; i++) {
            rects[i * 4] = Integer.MAX_VALUE;
            rects[i * 4 + 1] = Integer.MAX_VALUE;
            rects[i * 4 + 2] = -1;
            rects[i * 4 + 3] = -1;
        }
        var size = 16 * scale;
        for (var mb = 0; mb < columns * rows; mb++) {
            if (!parser.parsed[mb]) {
                continue;
            }
            var id = parser.segments[mb];
            var x = (mb % columns) * size;
            var y = (mb / columns) * size;
            var at = id * 4;
            rects[at] = Math.min(rects[at], x);
            rects[at + 1] = Math.min(rects[at + 1], y);
            rects[at + 2] = Math.max(rects[at + 2], x + size - 1);
            rects[at + 3] = Math.max(rects[at + 3], y + size - 1);
            for (var cy = 0; cy < scale; cy++) {
                for (var cx = 0; cx < scale; cx++) {
                    var cell = ((y >> 4) + cy) * picture.gridWidth + (x >> 4) + cx;
                    if (cell < picture.segmentMap.length) {
                        picture.segmentMap[cell] = id;
                    }
                }
            }
        }
        picture.segmentCount = count;
    }

    /**
     * Copies the co-located macroblock of the forward reference, or mid-gray without one, into a macroblock that was
     * not decoded.
     *
     * @param mb the macroblock
     */
    private void conceal(int mb) {
        var reference = forward.isEmpty() ? (upward != null && !upwardHorizontal && !upwardVertical ? upward : null)
                : forward.getFirst();
        var column = mb % columns;
        var row = mb / columns;
        var size = 16 * scale;
        for (var component = 0; component < 3; component++) {
            var shift = component == 0 ? 0 : 1;
            var plane = picture.plane(component);
            var stride = component == 0 ? picture.lumaStride() : picture.chromaStride();
            var x = (column * size) >> shift;
            var y = (row * size) >> shift;
            var length = size >> shift;
            for (var line = 0; line < length; line++) {
                var at = (y + line) * stride + x;
                if (reference != null) {
                    System.arraycopy(reference.plane(component), at, plane, at, length);
                } else {
                    Arrays.fill(plane, at, at + length, (byte) 128);
                }
                if (part != null) {
                    System.arraycopy(plane, at, part.plane(component), at, length);
                }
            }
        }
        storeMetadata(mb, PlusPicture.SKIPPED, null, null);
    }

    /**
     * Drops the picture after its header failed to start, releasing what the decoder acquired.
     */
    void abandon() {
        pool.release(picture);
        pool.release(part);
        picture = null;
        part = null;
        header = null;
        close();
    }

    /**
     * Releases the reference pictures the decoder holds.
     */
    void close() {
        pool.release(warped);
        warped = null;
    }

    /**
     * Reconstructs one macroblock and, for PB- and Improved PB-frames, its B-part.
     *
     * @param mb the parsed macroblock
     */
    private void reconstruct(Macroblock mb) {
        switch (header.type) {
            case PictureHeader.I -> reconstructIntra(mb);
            case PictureHeader.P, PictureHeader.IMPROVED_PB -> reconstructInter(mb);
            case PictureHeader.B -> reconstructB(mb);
            default -> reconstructEnhancement(mb);
        }
    }

    /**
     * Reconstructs an INTRA macroblock: the inverse transform of each block, upsampled in the Reduced-Resolution
     * Update mode.
     *
     * @param mb the macroblock
     */
    private void reconstructIntra(Macroblock mb) {
        var samples = 64 * scale * scale;
        for (var block = 0; block < 6; block++) {
            Arrays.fill(prediction, 0, samples, 0);
            addResidual(mb.coefficients, block, prediction);
            write(picture, mb, block, prediction);
        }
        storeMetadata(mb.index, PlusPicture.INTRA, mb.forwardX, mb.forwardY);
        if (part != null) {
            reconstructPart(mb);
        }
    }

    /**
     * Reconstructs a macroblock of a P-picture or the P-part of a PB- or Improved PB-frame (6.1, F.3, Q.2).
     *
     * @param mb the macroblock
     */
    private void reconstructInter(Macroblock mb) {
        if (mb.intra()) {
            reconstructIntra(mb);
            return;
        }
        var overlapped = header.has(LayerState.AP);
        for (var block = 0; block < 4; block++) {
            var reference = reference(mb.forwardReference[block]);
            if (overlapped) {
                predictOverlapped(mb, block, reference);
            } else {
                predictLuma(reference, mb, block, mb.forwardX[block], mb.forwardY[block], rounding, prediction);
            }
            if ((mb.pattern & (32 >> block)) != 0) {
                addResidual(mb.coefficients, block, prediction);
            }
            write(picture, mb, block, prediction);
        }
        int chromaX;
        int chromaY;
        if (mb.kind == Macroblock.INTER4V) {
            chromaX = Prediction.chromaOfFour(mb.forwardX[0] + mb.forwardX[1] + mb.forwardX[2] + mb.forwardX[3]);
            chromaY = Prediction.chromaOfFour(mb.forwardY[0] + mb.forwardY[1] + mb.forwardY[2] + mb.forwardY[3]);
        } else {
            chromaX = Prediction.chroma(mb.forwardX[0]);
            chromaY = Prediction.chroma(mb.forwardY[0]);
        }
        var chromaReference = reference(mb.forwardReference[0]);
        for (var block = 4; block < 6; block++) {
            predictChroma(chromaReference, mb, block, chromaX, chromaY, rounding, prediction);
            if ((mb.pattern & (32 >> block)) != 0) {
                addResidual(mb.coefficients, block, prediction);
            }
            write(picture, mb, block, prediction);
        }
        storeMetadata(mb.index, mb.kind == Macroblock.SKIPPED ? PlusPicture.SKIPPED : PlusPicture.INTER,
                mb.forwardX, mb.forwardY);
        if (part != null) {
            reconstructPart(mb);
        }
    }

    /**
     * Returns a forward reference picture of the current segment.
     *
     * @param index the relative index
     * @return the picture
     * @throws MediaException.Malformed if the index exceeds the references
     */
    private PlusPicture reference(int index) {
        if (index >= forward.size() || forward.get(index) == null) {
            throw MacroblockParser.CORRUPT;
        }
        var result = forward.get(index);
        if (result.width != header.width || result.height != header.height) {
            throw MacroblockParser.CORRUPT;
        }
        return result;
    }

    /**
     * Predicts a luminance block of a macroblock with overlapped motion compensation (F.3, Q.5).
     *
     * @param mb the macroblock
     * @param block the block, 0 to 3
     * @param reference the reference picture
     */
    private void predictOverlapped(Macroblock mb, int block, PlusPicture reference) {
        var size = 8 * scale;
        var ownX = mb.forwardX[block];
        var ownY = mb.forwardY[block];
        var top = (block & 2) == 0;
        var leftColumn = (block & 1) == 0;
        int aboveX;
        int aboveY;
        if (!top) {
            aboveX = mb.forwardX[block - 2];
            aboveY = mb.forwardY[block - 2];
        } else {
            var vector = remoteVector(mb, mb.row - 1, mb.column, block + 2, ownX, ownY);
            aboveX = vector[0];
            aboveY = vector[1];
        }
        int belowX;
        int belowY;
        if (top) {
            belowX = mb.forwardX[block + 2];
            belowY = mb.forwardY[block + 2];
        } else {
            belowX = ownX;
            belowY = ownY;
        }
        int leftX;
        int leftY;
        if (!leftColumn) {
            leftX = mb.forwardX[block - 1];
            leftY = mb.forwardY[block - 1];
        } else {
            var vector = remoteVector(mb, mb.row, mb.column - 1, block + 1, ownX, ownY);
            leftX = vector[0];
            leftY = vector[1];
        }
        int rightX;
        int rightY;
        if (leftColumn) {
            rightX = mb.forwardX[block + 1];
            rightY = mb.forwardY[block + 1];
        } else {
            var vector = remoteVector(mb, mb.row, mb.column + 1, block - 1, ownX, ownY);
            rightX = vector[0];
            rightY = vector[1];
        }
        predictLuma(reference, mb, block, ownX, ownY, rounding, second);
        predictLuma(reference, mb, block, aboveX, aboveY, rounding, remote[0]);
        predictLuma(reference, mb, block, belowX, belowY, rounding, remote[1]);
        predictLuma(reference, mb, block, leftX, leftY, rounding, remote[2]);
        predictLuma(reference, mb, block, rightX, rightY, rounding, remote[3]);
        Prediction.overlap(second, remote[0], remote[1], remote[2], remote[3], size, prediction);
    }

    /**
     * The vector a remote neighbor contributes.
     */
    private final int[] remoteResult = new int[2];

    /**
     * Returns the remote vector of a block of a neighboring macroblock for overlapped motion compensation (F.3): the
     * current vector outside the picture, in another video picture segment when segments are independent, or for an
     * INTRA neighbor outside PB-frames; zero for a neighbor that was not coded.
     *
     * @param mb the current macroblock
     * @param row the neighbor's row
     * @param column the neighbor's column
     * @param block the neighbor's block
     * @param ownX the current block's horizontal vector
     * @param ownY the current block's vertical vector
     * @return the vector, valid until the next call
     */
    private int[] remoteVector(Macroblock mb, int row, int column, int block, int ownX, int ownY) {
        remoteResult[0] = ownX;
        remoteResult[1] = ownY;
        if (row < 0 || column < 0 || column >= columns || row >= rows) {
            return remoteResult;
        }
        var other = row * columns + column;
        if (!parser.parsed[other]) {
            return remoteResult;
        }
        var separated = header.has(LayerState.SS) || header.has(LayerState.ISD);
        if (separated && parser.segments[other] != parser.segments[mb.index]) {
            return remoteResult;
        }
        if (other == waiting.index && hasWaiting && other != mb.index) {
            return remoteFrom(waiting, block, ownX, ownY);
        }
        if (other == current.index && other != mb.index) {
            return remoteFrom(current, block, ownX, ownY);
        }
        var cell = cellOf(row, column, block);
        var mode = picture.modes[cell >> 2];
        if (mode == PlusPicture.SKIPPED) {
            remoteResult[0] = 0;
            remoteResult[1] = 0;
        } else if (mode == PlusPicture.INTRA && part == null) {
            return remoteResult;
        } else {
            remoteResult[0] = picture.vectorX[cell];
            remoteResult[1] = picture.vectorY[cell];
        }
        return remoteResult;
    }

    /**
     * Returns the remote vector of a block of a macroblock not yet reconstructed.
     *
     * @param other the macroblock
     * @param block its block
     * @param ownX the current block's horizontal vector
     * @param ownY the current block's vertical vector
     * @return the vector
     */
    private int[] remoteFrom(Macroblock other, int block, int ownX, int ownY) {
        if (other.kind == Macroblock.SKIPPED) {
            remoteResult[0] = 0;
            remoteResult[1] = 0;
        } else if (other.intra() && part == null) {
            remoteResult[0] = ownX;
            remoteResult[1] = ownY;
        } else {
            remoteResult[0] = other.forwardX[block];
            remoteResult[1] = other.forwardY[block];
        }
        return remoteResult;
    }

    /**
     * Returns the index of the vector of a luminance block of a coding grid macroblock in the picture's vectors.
     *
     * @param row the macroblock row
     * @param column the macroblock column
     * @param block the block, 0 to 3
     * @return the vector index: the cell times four plus the 8 by 8 block in it
     */
    private int cellOf(int row, int column, int block) {
        var gridWidth = picture.gridWidth;
        if (scale == 1) {
            return (row * gridWidth + column) * 4 + block;
        }
        var cellX = column * 2 + (block & 1);
        var cellY = row * 2 + (block >> 1);
        return (cellY * gridWidth + cellX) * 4;
    }

    /**
     * Records the mode and vectors of a macroblock in the picture for later pictures and for overlapped motion
     * compensation.
     *
     * @param mb the macroblock index in the coding grid
     * @param mode the macroblock mode: {@link PlusPicture#SKIPPED}, {@link PlusPicture#INTER} or
     *             {@link PlusPicture#INTRA}
     * @param vectorX the horizontal vectors of the four blocks, or {@code null} for zero vectors
     * @param vectorY the vertical vectors
     */
    private void storeMetadata(int mb, byte mode, int[] vectorX, int[] vectorY) {
        var column = mb % columns;
        var row = mb / columns;
        var gridWidth = picture.gridWidth;
        if (scale == 1) {
            var cell = row * gridWidth + column;
            picture.modes[cell] = mode;
            for (var block = 0; block < 4; block++) {
                picture.vectorX[cell * 4 + block] = vectorX == null ? 0 : vectorX[block];
                picture.vectorY[cell * 4 + block] = vectorY == null ? 0 : vectorY[block];
            }
            return;
        }
        for (var block = 0; block < 4; block++) {
            var cell = (row * 2 + (block >> 1)) * gridWidth + column * 2 + (block & 1);
            if (cell >= picture.modes.length) {
                continue;
            }
            picture.modes[cell] = mode;
            for (var sub = 0; sub < 4; sub++) {
                picture.vectorX[cell * 4 + sub] = vectorX == null ? 0 : vectorX[block];
                picture.vectorY[cell * 4 + sub] = vectorY == null ? 0 : vectorY[block];
            }
        }
    }

    /**
     * Reconstructs the B-part of a macroblock of a PB- or Improved PB-frame (G.4, G.5, M.2) from the forward
     * reference and the reconstructed P-macroblock.
     *
     * @param mb the macroblock
     */
    private void reconstructPart(Macroblock mb) {
        var size = 16 * scale;
        var half = 8 * scale;
        capturePrec(mb);
        var reference = reference(mb.partMode == Macroblock.PB_FORWARD ? mb.partReference : mb.forwardReference[0]);
        if (mb.partMode == Macroblock.PB_BACKWARD) {
            for (var block = 0; block < 6; block++) {
                copyPrec(block, prediction);
                if ((mb.partPattern & (32 >> block)) != 0) {
                    addResidual(mb.partCoefficients, block, prediction);
                }
                write(part, mb, block, prediction);
            }
            return;
        }
        if (mb.partMode == Macroblock.PB_FORWARD) {
            for (var block = 0; block < 4; block++) {
                predictLuma(reference, mb, block, mb.partX, mb.partY, 0, prediction);
                if ((mb.partPattern & (32 >> block)) != 0) {
                    addResidual(mb.partCoefficients, block, prediction);
                }
                write(part, mb, block, prediction);
            }
            var cx = Prediction.chroma(mb.partX);
            var cy = Prediction.chroma(mb.partY);
            for (var block = 4; block < 6; block++) {
                predictChroma(reference, mb, block, cx, cy, 0, prediction);
                if ((mb.partPattern & (32 >> block)) != 0) {
                    addResidual(mb.partCoefficients, block, prediction);
                }
                write(part, mb, block, prediction);
            }
            return;
        }
        var forwardX = mb.backwardX;
        var forwardY = mb.backwardY;
        var backwardX = partBackwardX;
        var backwardY = partBackwardY;
        var deltaX = header.pbFrame && mb.partVector ? mb.partX : 0;
        var deltaY = header.pbFrame && mb.partVector ? mb.partY : 0;
        for (var block = 0; block < 4; block++) {
            var vx = mb.forwardX[block];
            var vy = mb.forwardY[block];
            forwardX[block] = scaleForward(vx, deltaX);
            forwardY[block] = scaleForward(vy, deltaY);
            backwardX[block] = deltaX == 0 ? scaleBackward(vx) : forwardX[block] - vx;
            backwardY[block] = deltaY == 0 ? scaleBackward(vy) : forwardY[block] - vy;
        }
        for (var block = 0; block < 4; block++) {
            predictLuma(reference, mb, block, forwardX[block], forwardY[block], 0, prediction);
            var bx = (block & 1) * half;
            var by = (block >> 1) * half;
            bidirectionalPrec(prediction, half, bx, by, size, 0, backwardX[block], backwardY[block]);
            if ((mb.partPattern & (32 >> block)) != 0) {
                addResidual(mb.partCoefficients, block, prediction);
            }
            write(part, mb, block, prediction);
        }
        var cfx = Prediction.chromaOfFour(forwardX[0] + forwardX[1] + forwardX[2] + forwardX[3]);
        var cfy = Prediction.chromaOfFour(forwardY[0] + forwardY[1] + forwardY[2] + forwardY[3]);
        var cbx = Prediction.chromaOfFour(backwardX[0] + backwardX[1] + backwardX[2] + backwardX[3]);
        var cby = Prediction.chromaOfFour(backwardY[0] + backwardY[1] + backwardY[2] + backwardY[3]);
        for (var block = 4; block < 6; block++) {
            predictChroma(reference, mb, block, cfx, cfy, 0, prediction);
            bidirectionalPrec(prediction, half, 0, 0, half, block == 4 ? 1024 : 1024 + 256, cbx, cby);
            if ((mb.partPattern & (32 >> block)) != 0) {
                addResidual(mb.partCoefficients, block, prediction);
            }
            write(part, mb, block, prediction);
        }
    }

    /**
     * Scales a P-vector component into the forward vector of a B-block (G.4), in the pseudo vectors of the
     * Reduced-Resolution Update mode when it is on (Q.4 item 4).
     *
     * @param vector the P-vector component
     * @param delta the delta vector component of MVDB
     * @return the forward component
     */
    private int scaleForward(int vector, int delta) {
        if (scale == 2) {
            return MacroblockParser.actual(trb * MacroblockParser.pseudo(vector) / trd);
        }
        return trb * vector / trd + delta;
    }

    /**
     * Scales a P-vector component into the backward vector of a B-block without a delta vector (G.4).
     *
     * @param vector the P-vector component
     * @return the backward component
     */
    private int scaleBackward(int vector) {
        if (scale == 2) {
            return MacroblockParser.actual((trb - trd) * MacroblockParser.pseudo(vector) / trd);
        }
        return (trb - trd) * vector / trd;
    }

    /**
     * Copies the reconstructed P-macroblock, before the loop filter, into {@link #prec}.
     *
     * @param mb the macroblock
     */
    private void capturePrec(Macroblock mb) {
        var size = 16 * scale;
        var x = mb.column * size;
        var y = mb.row * size;
        var lumaStride = picture.lumaStride();
        for (var row = 0; row < size; row++) {
            for (var column = 0; column < size; column++) {
                prec[row * size + column] = picture.y[(y + row) * lumaStride + x + column] & 0xFF;
            }
        }
        var half = size >> 1;
        var chromaStride = picture.chromaStride();
        for (var row = 0; row < half; row++) {
            for (var column = 0; column < half; column++) {
                var at = (y / 2 + row) * chromaStride + x / 2 + column;
                prec[1024 + row * half + column] = picture.u[at] & 0xFF;
                prec[1024 + 256 + row * half + column] = picture.v[at] & 0xFF;
            }
        }
    }

    /**
     * Copies a block of the reconstructed P-macroblock as the prediction of the backward mode of an Improved PB-frame.
     *
     * @param block the block, 0 to 5
     * @param out the prediction
     */
    private void copyPrec(int block, int[] out) {
        var half = 8 * scale;
        var size = 16 * scale;
        if (block < 4) {
            var bx = (block & 1) * half;
            var by = (block >> 1) * half;
            for (var row = 0; row < half; row++) {
                System.arraycopy(prec, (by + row) * size + bx, out, row * half, half);
            }
        } else {
            System.arraycopy(prec, block == 4 ? 1024 : 1024 + 256, out, 0, half * half);
        }
    }

    /**
     * Averages a forward prediction with the backward prediction from the reconstructed P-macroblock where the
     * backward vector points inside it (G.5).
     *
     * @param forward the forward prediction of the block, replaced by the result
     * @param blockSize the block size
     * @param offsetX the block's column in the P-macroblock component
     * @param offsetY the block's row in the P-macroblock component
     * @param precWidth the row stride of the P-macroblock component in {@link #prec}
     * @param precOffset the index of the component in {@link #prec}
     * @param vectorX the backward vector, horizontal component in half samples
     * @param vectorY the backward vector, vertical component
     */
    private void bidirectionalPrec(int[] forward, int blockSize, int offsetX, int offsetY, int precWidth,
                                   int precOffset, int vectorX, int vectorY) {
        var extent = precWidth - 1;
        var fx = vectorX & 1;
        var fy = vectorY & 1;
        var ix = vectorX >> 1;
        var iy = vectorY >> 1;
        for (var row = 0; row < blockSize; row++) {
            var y0 = offsetY + row + iy;
            var y1 = y0 + fy;
            if (y0 < 0 || y1 > extent) {
                continue;
            }
            for (var column = 0; column < blockSize; column++) {
                var x0 = offsetX + column + ix;
                var x1 = x0 + fx;
                if (x0 < 0 || x1 > extent) {
                    continue;
                }
                var a = prec[precOffset + y0 * precWidth + x0];
                int value;
                if (fx == 0 && fy == 0) {
                    value = a;
                } else if (fy == 0) {
                    value = (a + prec[precOffset + y0 * precWidth + x1] + 1) >> 1;
                } else if (fx == 0) {
                    value = (a + prec[precOffset + y1 * precWidth + x0] + 1) >> 1;
                } else {
                    value = (a + prec[precOffset + y0 * precWidth + x1] + prec[precOffset + y1 * precWidth + x0]
                            + prec[precOffset + y1 * precWidth + x1] + 2) >> 2;
                }
                var at = row * blockSize + column;
                forward[at] = (forward[at] + value) >> 1;
            }
        }
    }

    /**
     * Reconstructs a macroblock of a B-picture (O.4, O.5).
     *
     * @param mb the macroblock
     */
    private void reconstructB(Macroblock mb) {
        if (mb.intra()) {
            reconstructIntra(mb);
            return;
        }
        var direct = mb.kind == Macroblock.DIRECT;
        var forwardX = mb.forwardX;
        var forwardY = mb.forwardY;
        var backwardX = mb.backwardX;
        var backwardY = mb.backwardY;
        var subsequent = backwardPicture(0);
        if (direct) {
            for (var block = 0; block < 4; block++) {
                var cell = cellOf(mb.row, mb.column, block);
                var vx = 0;
                var vy = 0;
                if (cell >> 2 < subsequent.modes.length && subsequent.modes[cell >> 2] == PlusPicture.INTER) {
                    vx = subsequent.vectorX[cell];
                    vy = subsequent.vectorY[cell];
                }
                forwardX[block] = scaleForward(vx, 0);
                forwardY[block] = scaleForward(vy, 0);
                backwardX[block] = scaleBackward(vx);
                backwardY[block] = scaleBackward(vy);
            }
        }
        var useForward = direct || mb.kind == Macroblock.FORWARD || mb.kind == Macroblock.BIDIRECTIONAL
                || mb.kind == Macroblock.INTER || mb.kind == Macroblock.SKIPPED;
        var useBackward = direct || mb.kind == Macroblock.BACKWARD || mb.kind == Macroblock.BIDIRECTIONAL;
        var forwardReference = useForward ? reference(mb.forwardReference[0]) : null;
        var backwardReference = useBackward ? backwardPicture(mb.backwardReference) : null;
        for (var block = 0; block < 4; block++) {
            if (useForward) {
                predictLuma(forwardReference, mb, block, forwardX[block], forwardY[block], rounding, prediction);
            }
            if (useBackward) {
                predictLuma(backwardReference, mb, block, backwardX[block], backwardY[block], rounding,
                        useForward ? second : prediction);
                if (useForward) {
                    average(prediction, second, 64 * scale * scale);
                }
            }
            if ((mb.pattern & (32 >> block)) != 0) {
                addResidual(mb.coefficients, block, prediction);
            }
            write(picture, mb, block, prediction);
        }
        int fcx;
        int fcy;
        int bcx;
        int bcy;
        if (direct) {
            fcx = Prediction.chromaOfFour(forwardX[0] + forwardX[1] + forwardX[2] + forwardX[3]);
            fcy = Prediction.chromaOfFour(forwardY[0] + forwardY[1] + forwardY[2] + forwardY[3]);
            bcx = Prediction.chromaOfFour(backwardX[0] + backwardX[1] + backwardX[2] + backwardX[3]);
            bcy = Prediction.chromaOfFour(backwardY[0] + backwardY[1] + backwardY[2] + backwardY[3]);
        } else {
            fcx = Prediction.chroma(forwardX[0]);
            fcy = Prediction.chroma(forwardY[0]);
            bcx = Prediction.chroma(backwardX[0]);
            bcy = Prediction.chroma(backwardY[0]);
        }
        for (var block = 4; block < 6; block++) {
            if (useForward) {
                predictChroma(forwardReference, mb, block, fcx, fcy, rounding, prediction);
            }
            if (useBackward) {
                predictChroma(backwardReference, mb, block, bcx, bcy, rounding, useForward ? second : prediction);
                if (useForward) {
                    average(prediction, second, 64 * scale * scale);
                }
            }
            if ((mb.pattern & (32 >> block)) != 0) {
                addResidual(mb.coefficients, block, prediction);
            }
            write(picture, mb, block, prediction);
        }
        storeMetadata(mb.index, PlusPicture.INTER, mb.forwardX, mb.forwardY);
    }

    /**
     * Returns a backward reference picture of a B-picture.
     *
     * @param index 0, or 1 for the second backward reference
     * @return the picture
     */
    private PlusPicture backwardPicture(int index) {
        if (index >= backward.size() || backward.get(index) == null) {
            throw MacroblockParser.CORRUPT;
        }
        var result = backward.get(index);
        if (result.width != header.width || result.height != header.height) {
            throw MacroblockParser.CORRUPT;
        }
        return result;
    }

    /**
     * Averages a second prediction into a first, dividing the sum by two with truncation.
     *
     * @param first the first prediction, replaced by the average
     * @param other the second prediction
     * @param count the samples
     */
    private static void average(int[] first, int[] other, int count) {
        for (var i = 0; i < count; i++) {
            first[i] = (first[i] + other[i]) >> 1;
        }
    }

    /**
     * Reconstructs a macroblock of an EI- or EP-picture (O.4).
     *
     * @param mb the macroblock
     */
    private void reconstructEnhancement(Macroblock mb) {
        if (mb.intra()) {
            reconstructIntra(mb);
            return;
        }
        var useForward = mb.kind == Macroblock.FORWARD || mb.kind == Macroblock.BIDIRECTIONAL;
        var useUpward = mb.kind == Macroblock.UPWARD || mb.kind == Macroblock.BIDIRECTIONAL;
        var forwardReference = useForward ? reference(mb.forwardReference[0]) : null;
        var cx = Prediction.chroma(mb.forwardX[0]);
        var cy = Prediction.chroma(mb.forwardY[0]);
        for (var block = 0; block < 6; block++) {
            if (useForward) {
                if (block < 4) {
                    predictLuma(forwardReference, mb, block, mb.forwardX[block], mb.forwardY[block], rounding,
                            prediction);
                } else {
                    predictChroma(forwardReference, mb, block, cx, cy, rounding, prediction);
                }
            }
            if (useUpward) {
                predictUpward(mb, block, useForward ? second : prediction);
                if (useForward) {
                    average(prediction, second, 64 * scale * scale);
                }
            }
            if ((mb.pattern & (32 >> block)) != 0) {
                addResidual(mb.coefficients, block, prediction);
            }
            write(picture, mb, block, prediction);
        }
        storeMetadata(mb.index, mb.coded || mb.kind != Macroblock.UPWARD ? PlusPicture.INTER : PlusPicture.SKIPPED,
                useForward ? mb.forwardX : null, useForward ? mb.forwardY : null);
    }

    /**
     * Predicts a block from the reference layer picture: interpolated for spatial scalability (O.6), copied for SNR
     * scalability.
     *
     * @param mb the macroblock
     * @param block the block, 0 to 5
     * @param out the prediction
     */
    private void predictUpward(Macroblock mb, int block, int[] out) {
        var size = 8 * scale;
        var chroma = block >= 4;
        var x = mb.column * 16 * scale + (chroma ? 0 : (block & 1) * size);
        var y = mb.row * 16 * scale + (chroma ? 0 : (block >> 1) * size);
        if (chroma) {
            x >>= 1;
            y >>= 1;
        }
        var component = chroma ? block - 3 : 0;
        var source = upward.plane(component);
        var stride = component == 0 ? upward.lumaStride() : upward.chromaStride();
        var shift = component == 0 ? 0 : 1;
        var right = (upward.width >> shift) - 1;
        var bottom = (upward.height >> shift) - 1;
        var left = 0;
        var top = 0;
        if (header.has(LayerState.ISD)) {
            segmentRegion(mb, forward.isEmpty() ? null : forward.getFirst());
            var hx = (upwardHorizontal ? 1 : 0) + shift;
            var hy = (upwardVertical ? 1 : 0) + shift;
            left = segmentRect[0] >> hx;
            top = segmentRect[1] >> hy;
            right = Math.min(right, ((segmentRect[2] + 1) >> hx) - 1);
            bottom = Math.min(bottom, ((segmentRect[3] + 1) >> hy) - 1);
        }
        Resampler.upsample(source, stride, left, top, right, bottom, upwardHorizontal, upwardVertical, x, y, size,
                size, out, 0, size);
    }

    /**
     * Predicts a luminance block of a macroblock with one vector.
     *
     * @param reference the reference picture
     * @param mb the macroblock
     * @param block the block, 0 to 3
     * @param vectorX the horizontal vector in half samples
     * @param vectorY the vertical vector in half samples
     * @param control the rounding control
     * @param out the prediction
     */
    private void predictLuma(PlusPicture reference, Macroblock mb, int block, int vectorX, int vectorY, int control,
                             int[] out) {
        var size = 8 * scale;
        var x = mb.column * 16 * scale + (block & 1) * size;
        var y = mb.row * 16 * scale + (block >> 1) * size;
        clipRegion(mb, reference, 0);
        Prediction.block(reference.y, reference.lumaStride(), clip, x, y, size, size, vectorX, vectorY, control, out,
                0, size);
    }

    /**
     * Predicts a chrominance block of a macroblock.
     *
     * @param reference the reference picture
     * @param mb the macroblock
     * @param block 4 for Cb, 5 for Cr
     * @param vectorX the horizontal chrominance vector in half samples
     * @param vectorY the vertical chrominance vector in half samples
     * @param control the rounding control
     * @param out the prediction
     */
    private void predictChroma(PlusPicture reference, Macroblock mb, int block, int vectorX, int vectorY, int control,
                               int[] out) {
        var size = 8 * scale;
        var x = mb.column * size;
        var y = mb.row * size;
        clipRegion(mb, reference, 1);
        Prediction.block(block == 4 ? reference.u : reference.v, reference.chromaStride(), clip, x, y, size, size,
                vectorX, vectorY, control, out, 0, size);
    }

    /**
     * Sets {@link #clip} to the region predictions of a macroblock take samples from: the coded area of the reference
     * picture, or the video picture segment of the macroblock in the Independent Segment Decoding mode (R.2).
     *
     * @param mb the macroblock
     * @param reference the reference picture
     * @param shift 0 for luminance, 1 for chrominance
     */
    private void clipRegion(Macroblock mb, PlusPicture reference, int shift) {
        clip[0] = 0;
        clip[1] = 0;
        clip[2] = (reference.codedWidth >> shift) - 1;
        clip[3] = (reference.codedHeight >> shift) - 1;
        if (header.has(LayerState.ISD)) {
            segmentRegion(mb, reference);
            clip[0] = segmentRect[0] >> shift;
            clip[1] = segmentRect[1] >> shift;
            clip[2] = Math.min(clip[2], ((segmentRect[2] + 1) >> shift) - 1);
            clip[3] = Math.min(clip[3], ((segmentRect[3] + 1) >> shift) - 1);
        }
    }

    /**
     * The luminance rectangle of the segment of the macroblock being predicted.
     */
    private final int[] segmentRect = new int[4];

    /**
     * Sets {@link #segmentRect} to the video picture segment of a macroblock (R.2): the segment of the reference
     * picture at its position, which has the same segmentation (R.3.2), or, when the reference recorded none, the
     * part of the current segment known so far, extending to the bottom of the picture.
     *
     * @param mb the macroblock
     * @param reference the reference picture, or {@code null}
     */
    private void segmentRegion(Macroblock mb, PlusPicture reference) {
        var size = 16 * scale;
        var x = mb.column * size;
        var y = mb.row * size;
        if (reference != null && reference.segmentRect(x, y, segmentRect)) {
            return;
        }
        var at = parser.segments[mb.index] * 4;
        segmentRect[0] = segmentBounds[at] * size;
        segmentRect[1] = segmentBounds[at + 1] * size;
        segmentRect[2] = (segmentBounds[at + 2] + 1) * size - 1;
        segmentRect[3] = rows * size - 1;
    }

    /**
     * Adds the inverse transform of a block, upsampled in the Reduced-Resolution Update mode (Q.6), to its
     * prediction.
     *
     * @param coefficients the coefficients of the six blocks, the block's cleared on return
     * @param block the block, 0 to 5
     * @param out the prediction, receiving the sum without clipping
     */
    private void addResidual(short[] coefficients, int block, int[] out) {
        if (referenceTransform) {
            ReferenceTransform.inverse(coefficients, block * 64, residual);
        } else {
            Mpeg4Dsp.inverseTransform(coefficients, block * 64, residual);
        }
        if (scale == 1) {
            for (var i = 0; i < 64; i++) {
                out[i] += residual[i];
            }
            return;
        }
        for (var i = 0; i < 64; i++) {
            var value = residual[i];
            residual[i] = value < -256 ? -256 : Math.min(value, 255);
        }
        Resampler.upsampleResidual(residual, upsampled);
        for (var i = 0; i < 256; i++) {
            out[i] += upsampled[i];
        }
    }

    /**
     * Writes a reconstructed block, clipped to 0 to 255, into a picture.
     *
     * @param target the picture
     * @param mb the macroblock
     * @param block the block, 0 to 5
     * @param values the reconstructed samples
     */
    private void write(PlusPicture target, Macroblock mb, int block, int[] values) {
        var size = 8 * scale;
        var chroma = block >= 4;
        var stride = chroma ? target.chromaStride() : target.lumaStride();
        var plane = target.plane(chroma ? block - 3 : 0);
        var x = chroma ? mb.column * size : mb.column * 16 * scale + (block & 1) * size;
        var y = chroma ? mb.row * size : mb.row * 16 * scale + (block >> 1) * size;
        for (var row = 0; row < size; row++) {
            var at = (y + row) * stride + x;
            for (var column = 0; column < size; column++) {
                var value = values[row * size + column];
                plane[at + column] = (byte) (value < 0 ? 0 : Math.min(value, 255));
            }
        }
    }
}

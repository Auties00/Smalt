package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.video.mpeg4.internal.BitReader;
import com.github.auties00.smalt.video.mpeg4.internal.Frame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decodes ITU-T H.263 streams with the optional modes the macroblock engine shared with MPEG-4 Part 2 lacks:
 * Continuous Presence Multipoint (Annex C), Syntax-based Arithmetic Coding (Annex E), PB-frames (Annex G),
 * rectangular slices (Annex K), Improved PB-frames (Annex M), Reference Picture Selection (Annex N), Temporal, SNR and
 * Spatial Scalability (Annex O), Reference Picture Resampling (Annex P), Reduced-Resolution Update (Annex Q),
 * Independent Segment Decoding (Annex R) and Enhanced Reference Picture Selection (Annex U), together with every mode
 * that engine has.
 *
 * <p>The stream receives one picture start code at a time with the data up to the next one; the segments of a
 * picture may be interleaved with those of the other sub-bitstreams of Annex C. It hands out the decoded pictures in
 * display order, one reference instant late: B-pictures follow the reference picture they precede. For every instant
 * of a sub-bitstream it hands out the picture of the highest layer that codes the instant. Pictures cover the display
 * area of their header; their time is in ticks of 1/1800000 second from the first picture of their sub-bitstream.
 *
 * <p>An instance is not thread-safe.
 */
public final class H263PlusStream {
    /**
     * Receives the decoded pictures.
     */
    public interface Sink {
        /**
         * Hands out one picture.
         *
         * @param picture the picture, valid until the call returns
         * @param time the picture time in ticks of 1/1800000 second
         * @param keyframe whether the picture is an I- or EI-picture
         * @param layer the enhancement layer number of the picture, 1 for the base layer
         * @param subBitstream the sub-bitstream of the picture, 0 without Continuous Presence Multipoint
         */
        void picture(YuvPicture picture, long time, boolean keyframe, int layer, int subBitstream);
    }

    /**
     * The sub-bitstreams of Continuous Presence Multipoint.
     */
    private static final int SUB_BITSTREAMS = 4;

    /**
     * The widths of the source formats of PLUSPTYPE, indexed by the format code; 0 for the custom and reserved codes.
     */
    private static final int[] WIDTHS = {0, 128, 176, 352, 704, 1408, 0, 0};

    /**
     * The heights of the source formats of PLUSPTYPE, indexed by the format code.
     */
    private static final int[] HEIGHTS = {0, 96, 144, 288, 576, 1152, 0, 0};

    /**
     * The sub-bitstream numbers of the SSBI codes 0 to 15 (Table K.1), -1 for codes that name none.
     */
    private static final int[] SLICE_SUB_BITSTREAMS = {
            -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, -1, 3, -1, -1
    };

    /**
     * The limits bounding the picture sizes.
     */
    private final MediaLimits limits;

    /**
     * The picture pool.
     */
    private final PicturePool pool = new PicturePool();

    /**
     * The sub-bitstreams.
     */
    private final SubBitstream[] streams = new SubBitstream[SUB_BITSTREAMS];

    /**
     * The picture decoder of each sub-bitstream.
     */
    private final PictureDecoder[] decoders = new PictureDecoder[SUB_BITSTREAMS];

    /**
     * The reader of the stream.
     */
    private final BitReader reader = new BitReader();

    /**
     * The header the next picture start code is parsed into.
     */
    private PictureHeader spare = new PictureHeader();

    /**
     * Whether the last picture header set Continuous Presence Multipoint.
     */
    private boolean cpm;

    /**
     * Whether data since the last call was corrupt.
     */
    private boolean damaged;

    /**
     * The pictures due for output, gathered before they are handed out.
     */
    private final List<PlusPicture> due = new ArrayList<>();

    /**
     * The output picture of each size, reused from call to call.
     */
    private YuvPicture output;

    /**
     * Creates a stream decoder.
     *
     * @param limits the limits bounding the picture sizes
     */
    public H263PlusStream(MediaLimits limits) {
        this.limits = limits;
        for (var i = 0; i < SUB_BITSTREAMS; i++) {
            streams[i] = new SubBitstream(i, pool);
            decoders[i] = new PictureDecoder(pool);
        }
    }

    /**
     * Makes the decoder transform blocks with the double precision reference inverse DCT, whose output reproduces
     * the reference decoders of the ITU-T test models sample for sample, instead of the integer transform.
     *
     * @param enabled whether the reference transform is used
     */
    void useReferenceTransform(boolean enabled) {
        for (var decoder : decoders) {
            decoder.referenceTransform = enabled;
        }
    }

    /**
     * Returns whether a picture header uses a mode only this decoder has: an arithmetic coded, PB, Improved PB, B, EI
     * or EP picture, Continuous Presence Multipoint, the Reference Picture Selection, Independent Segment Decoding or
     * Enhanced Reference Picture Selection mode, rectangular slices, Reference Picture Resampling, explicit or by a
     * change of size of a predicted picture, or the Reduced-Resolution Update mode.
     *
     * @param reader the stream, positioned at the picture start code; its position is left past the fields read
     * @param width the width of the previous picture, or 0
     * @param height the height of the previous picture, or 0
     * @return whether the picture needs this decoder
     */
    public static boolean required(BitReader reader, int width, int height) {
        if (reader.read(22) != 0x20) {
            return false;
        }
        reader.skip(8 + 2 + 3);
        var format = reader.read(3);
        if (format != 7) {
            reader.skip(1);
            var umvSacApPb = reader.read(4);
            reader.skip(5);
            var cpmBit = reader.readBit();
            return (umvSacApPb & 0b0101) != 0 || cpmBit == 1;
        }
        var ufep = reader.read(3);
        var extendedFormat = 0;
        var customClock = false;
        var modes = 0;
        if (ufep == 1) {
            extendedFormat = reader.read(3);
            customClock = reader.readFlag();
            modes = reader.read(10);
            reader.skip(1);
            var erps = reader.readBit();
            var dps = reader.readBit();
            reader.skip(1);
            if ((modes & 0x100) != 0 || (modes & 0x8) != 0 || (modes & 0x4) != 0 || erps == 1 || dps == 1) {
                return true;
            }
        }
        var type = reader.read(3);
        var rpr = reader.readBit();
        var rru = reader.readBit();
        reader.skip(4);
        var cpmBit = reader.readBit();
        if (type > 1 || rpr == 1 || rru == 1 || cpmBit == 1) {
            return true;
        }
        if (ufep != 1) {
            return false;
        }
        int pictureWidth;
        int pictureHeight;
        if (extendedFormat == 6) {
            var par = reader.read(4);
            pictureWidth = (reader.read(9) + 1) * 4;
            reader.skip(1);
            pictureHeight = reader.read(9) * 4;
            if (par == 15) {
                reader.skip(16);
            }
        } else {
            pictureWidth = WIDTHS[extendedFormat];
            pictureHeight = HEIGHTS[extendedFormat];
        }
        if (type == PictureHeader.P && width > 0 && (pictureWidth != width || pictureHeight != height)) {
            return true;
        }
        if (customClock) {
            reader.skip(8 + 2);
        }
        if ((modes & 0x200) != 0 && reader.readBit() == 0) {
            reader.skip(1);
        }
        return (modes & 0x10) != 0 && reader.readBit() == 1;
    }

    /**
     * Continues a stream whose earlier pictures another decoder decoded: takes over its two most recent reference
     * pictures and the header state its pictures left.
     *
     * @param state the header state after the last picture
     * @param past the reference picture before the most recent one, or {@code null}
     * @param pastTime the time of {@code past} in ticks
     * @param pastShown whether {@code past} was handed out
     * @param future the most recent reference picture, or {@code null}
     * @param futureTime the time of {@code future} in ticks
     * @param futureShown whether {@code future} was handed out
     * @param width the picture width
     * @param height the picture height
     */
    public void adopt(HeaderState state, Frame past, long pastTime, boolean pastShown, Frame future, long futureTime,
                      boolean futureShown, int width, int height) {
        var stream = streams[0];
        var layer = stream.layer(1);
        layer.known = true;
        layer.modes = state.plus() ? state.modes() << 2 : 0;
        layer.format = state.format();
        layer.customWidth = state.customWidth();
        layer.customHeight = state.customHeight();
        layer.parWidth = state.parWidth();
        layer.parHeight = state.parHeight();
        layer.unlimitedVectors = state.unlimitedVectors();
        layer.arbitrarySliceOrder = state.arbitrarySliceOrder();
        layer.lastType = PictureHeader.P;
        stream.customClock = state.customClock();
        stream.periodTicks = state.periodTicks();
        stream.timed = true;
        stream.lastReference = state.lastReference();
        stream.lastExtended = state.customClock();
        stream.lastTime = state.time();
        var period = Math.max(1, state.periodTicks());
        var modulus = state.customClock() ? 1024 : 256;
        for (var frame : new Frame[]{past, future}) {
            if (frame == null) {
                continue;
            }
            var time = frame == past ? pastTime : futureTime;
            var picture = pool.acquire(width, height);
            copy(frame, picture);
            picture.time = time;
            picture.temporalReference = (int) ((state.lastReference() - (state.time() - time) / period) & (modulus - 1));
            picture.extendedReference = state.customClock();
            picture.type = frame.codingType == com.github.auties00.smalt.video.mpeg4.internal.Vop.I
                    ? PictureHeader.I : PictureHeader.P;
            picture.keyframe = frame.keyframe;
            stream.addAnchor(picture, false);
            var shown = frame == past ? pastShown : futureShown;
            if (!shown) {
                pool.retain(picture);
                stream.pending.add(picture);
            }
            pool.release(picture);
            stream.lastAnchorTime = Math.max(stream.lastAnchorTime, time);
        }
    }

    /**
     * Copies the samples, macroblock types and vectors of a picture of the other decoder.
     *
     * @param frame the source
     * @param picture the target
     */
    private static void copy(Frame frame, PlusPicture picture) {
        var lumaWidth = Math.min(picture.codedWidth, frame.mbWidth * 16);
        var lumaHeight = Math.min(picture.codedHeight, frame.mbHeight * 16);
        for (var row = 0; row < lumaHeight; row++) {
            System.arraycopy(frame.y, frame.lumaOrigin + row * frame.lumaStride, picture.y, row * picture.lumaStride(),
                    lumaWidth);
        }
        for (var row = 0; row < lumaHeight / 2; row++) {
            System.arraycopy(frame.u, frame.chromaOrigin + row * frame.chromaStride, picture.u,
                    row * picture.chromaStride(), lumaWidth / 2);
            System.arraycopy(frame.v, frame.chromaOrigin + row * frame.chromaStride, picture.v,
                    row * picture.chromaStride(), lumaWidth / 2);
        }
        for (var mby = 0; mby < Math.min(picture.mbHeight, frame.mbHeight); mby++) {
            for (var mbx = 0; mbx < Math.min(picture.mbWidth, frame.mbWidth); mbx++) {
                var mb = mby * frame.mbWidth + mbx;
                var cell = mby * picture.gridWidth + mbx;
                var type = frame.mbType[mb];
                picture.modes[cell] = type == Frame.MB_INTRA ? PlusPicture.INTRA
                        : type == Frame.MB_SKIPPED ? PlusPicture.SKIPPED : PlusPicture.INTER;
                for (var block = 0; block < 4; block++) {
                    picture.vectorX[cell * 4 + block] = frame.mv[mb * 8 + 2 * block];
                    picture.vectorY[cell * 4 + block] = frame.mv[mb * 8 + 2 * block + 1];
                }
            }
        }
    }

    /**
     * Decodes the data from one picture start code up to the next.
     *
     * @param data the bytes
     * @param offset the index of the picture start code
     * @param length the length of the data
     * @param sink the receiver of the pictures that became due
     * @throws MediaException.Malformed if the data is corrupt; the decodable pictures are handed out first
     * @throws MediaException.LimitExceeded if a picture exceeds the limits
     */
    public void decode(byte[] data, int offset, int length, Sink sink) {
        damaged = false;
        reader.load(data, offset, length);
        if (reader.peek(22) != 0x20) {
            return;
        }
        var number = PictureHeader.peekSubBitstream(reader);
        var stream = streams[number];
        if (stream.decoding != null) {
            finishPicture(stream, sink);
        }
        var header = spare;
        try {
            header.parse(reader, streams);
        } catch (MediaException.Malformed exception) {
            damaged = true;
            throw exception;
        }
        cpm = header.cpm;
        LimitChecks.checkPicture(limits, header.width, header.height);
        var held = 0L;
        for (var other : streams) {
            held += other.heldPictures();
        }
        LimitChecks.checkAllocation(limits, (4 + held) * PlusPicture.bytes(header.width, header.height));
        var layer = header.layer;
        var redundant = (header.has(LayerState.RPS) || header.has(LayerState.ERPS))
                && stream.lastLayerReference[layer] == header.temporalReference
                && (!header.has(LayerState.ERPS) || stream.lastLayerNumber[layer] == header.pictureNumber);
        var position = -1L;
        if (!redundant) {
            stream.lastLayerReference[layer] = header.temporalReference;
            stream.lastLayerNumber[layer] = header.pictureNumber;
            var time = stream.time(header.temporalReference, header.extendedReference, header.previousPeriodTicks,
                    header.type == PictureHeader.B);
            var decoder = decoders[number];
            var previous = decoder.header();
            try {
                decoder.begin(header, stream, time, reader);
                stream.decoding = decoder;
                spare = previous != null ? previous : new PictureHeader();
                position = decoder.decodeSegment(reader);
            } catch (MediaException.Malformed exception) {
                damaged = true;
                if (stream.decoding == null) {
                    decoder.abandon();
                    spare = header;
                }
                position = -1;
            }
        }
        route(position, sink);
        if (!cpm) {
            for (var other : streams) {
                if (other.decoding != null) {
                    finishPicture(other, sink);
                }
            }
        } else {
            for (var other : streams) {
                if (other.decoding != null && other.decoding.complete()) {
                    finishPicture(other, sink);
                }
            }
        }
        if (damaged) {
            throw new MediaException.Malformed("corrupt H.263 picture data");
        }
    }

    /**
     * Follows the start codes inside the data of a picture: group of blocks and slice headers, which continue the
     * picture of their sub-bitstream, and the end of sequence and end of sub-bitstream codes.
     *
     * @param position the bit position of the first start code, or -1
     * @param sink the receiver of the pictures that became due
     */
    private void route(long position, Sink sink) {
        var guard = 0;
        while (position >= 0 && position + 22 <= reader.limit()) {
            if (++guard > 1 << 16) {
                damaged = true;
                return;
            }
            reader.seek(position + 17);
            var gn = reader.read(5);
            if (gn == 0) {
                return;
            }
            if (gn == 31) {
                for (var stream : streams) {
                    if (stream.decoding != null) {
                        finishPicture(stream, sink);
                    }
                    release(stream, sink);
                }
                return;
            }
            if (gn == 30) {
                reader.skip(1);
                var ended = streams[reader.read(2)];
                if (ended.decoding != null) {
                    finishPicture(ended, sink);
                }
                release(ended, sink);
                ended.reset();
                position = next(reader.position());
                continue;
            }
            PictureDecoder target;
            try {
                if (cpm) {
                    var slice = SLICE_SUB_BITSTREAMS[gn & 15];
                    if ((gn & 16) != 0 && slice >= 0 && gn != 28) {
                        target = streams[slice].decoding;
                        if (target != null) {
                            target.sliceHeader(reader);
                        }
                    } else {
                        target = streams[reader.read(2)].decoding;
                        if (target != null) {
                            target.gobHeader(reader, gn);
                        }
                    }
                } else {
                    target = streams[0].decoding;
                    if (target != null) {
                        if (target.slices()) {
                            reader.seek(position + 17);
                            reader.marker("SEPB1");
                            target.sliceHeader(reader);
                        } else {
                            target.gobHeader(reader, gn);
                        }
                    }
                }
                if (target == null) {
                    position = next(reader.position());
                    continue;
                }
                position = target.decodeSegment(reader);
            } catch (MediaException.Malformed exception) {
                damaged = true;
                position = next(reader.position());
            }
        }
    }

    /**
     * Returns the position of the next start code from a position on.
     *
     * @param from the bit position to search from
     * @return the start code position, or -1
     */
    private long next(long from) {
        var limit = reader.limit();
        var run = 0;
        for (var at = from; at < limit; at++) {
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
     * Finishes the picture a sub-bitstream is decoding: conceals what is missing, updates the references and queues
     * the picture, and its B-part, for output.
     *
     * @param stream the sub-bitstream
     * @param sink the receiver of the pictures that became due
     */
    private void finishPicture(SubBitstream stream, Sink sink) {
        var decoder = stream.decoding;
        stream.decoding = null;
        decoder.finish();
        if (decoder.damaged) {
            damaged = true;
        }
        var header = decoder.header();
        var picture = decoder.picture;
        var type = header.type;
        var anchor = type != PictureHeader.B;
        if (decoder.part != null) {
            var part = decoder.part;
            var previous = stream.anchor(header.layer);
            var trb = header.trb;
            part.time = previous != null ? previous.time + (long) trb * stream.periodTicks : picture.time;
            part.temporalReference = picture.temporalReference;
            part.layer = header.layer;
            queue(stream, part, false, false, sink);
            pool.release(part);
        }
        if (anchor) {
            stream.addAnchor(picture, header.has(LayerState.RPS));
            if (header.has(LayerState.ERPS)) {
                stream.buffer(header.layer).store(picture, header.pictureNumber, header.erps, pool::retain);
            }
        }
        queue(stream, picture, anchor, header.has(LayerState.ERPS), sink);
        pool.release(picture);
        decoder.close();
    }

    /**
     * Adds a decoded picture to the output queue of its sub-bitstream; a reference picture of a later instant than
     * the last makes the pictures up to that last instant due, or up to the instant before it when B-pictures may
     * follow two later reference pictures (two-picture backward prediction of U.3.1.5.5).
     *
     * @param stream the sub-bitstream
     * @param picture the decoded picture, which the queue holds until it is handed out
     * @param anchor whether the picture is a reference picture
     * @param deep whether B-pictures may follow two later reference pictures
     * @param sink the receiver of the pictures that became due
     */
    private void queue(SubBitstream stream, PlusPicture picture, boolean anchor, boolean deep, Sink sink) {
        pool.retain(picture);
        stream.pending.add(picture);
        if (anchor && picture.time > stream.lastAnchorTime) {
            var limit = deep ? stream.earlierAnchorTime : stream.lastAnchorTime;
            stream.earlierAnchorTime = stream.lastAnchorTime;
            stream.lastAnchorTime = picture.time;
            emit(stream, limit, sink);
        }
    }

    /**
     * Hands out the pending pictures of a sub-bitstream up to a time, for each instant the one of the highest layer.
     *
     * @param stream the sub-bitstream
     * @param limit the last time handed out
     * @param sink the receiver
     */
    private void emit(SubBitstream stream, long limit, Sink sink) {
        due.clear();
        for (var iterator = stream.pending.iterator(); iterator.hasNext(); ) {
            var picture = iterator.next();
            if (picture.time <= limit) {
                iterator.remove();
                due.add(picture);
            }
        }
        due.sort(Comparator.comparingLong((PlusPicture picture) -> picture.time)
                .thenComparingInt(picture -> -picture.layer));
        var lastTime = Long.MIN_VALUE;
        for (var picture : due) {
            if (picture.time != lastTime) {
                lastTime = picture.time;
                sink.picture(render(picture), picture.time, picture.keyframe, picture.layer, stream.number);
            }
            pool.release(picture);
        }
        due.clear();
    }

    /**
     * Hands out every pending picture of a sub-bitstream.
     *
     * @param stream the sub-bitstream
     * @param sink the receiver
     */
    private void release(SubBitstream stream, Sink sink) {
        emit(stream, Long.MAX_VALUE, sink);
    }

    /**
     * Copies the display area of a picture into the output picture.
     *
     * @param picture the decoded picture, whose planes carry a border
     * @return the output picture
     */
    private YuvPicture render(PlusPicture picture) {
        var width = picture.width;
        var height = picture.height;
        var color = width > 1024 || height > 576 ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
        var target = output;
        if (target == null || target.width() != width || target.height() != height) {
            target = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, color);
            output = target;
        }
        var y = target.y();
        for (var row = 0; row < height; row++) {
            System.arraycopy(picture.y, row * picture.lumaStride(), y, row * target.yStride(), width);
        }
        var chromaWidth = target.chromaWidth();
        for (var row = 0; row < target.chromaHeight(); row++) {
            System.arraycopy(picture.u, row * picture.chromaStride(), target.u(), row * target.uvStride(),
                    chromaWidth);
            System.arraycopy(picture.v, row * picture.chromaStride(), target.v(), row * target.uvStride(),
                    chromaWidth);
        }
        return target;
    }

    /**
     * Finishes the pictures in progress and hands out every pending picture.
     *
     * @param sink the receiver
     */
    public void flush(Sink sink) {
        for (var stream : streams) {
            if (stream.decoding != null) {
                finishPicture(stream, sink);
            }
            release(stream, sink);
        }
    }

    /**
     * Forgets the pictures in progress, the pending pictures and the reference pictures, as after a seek.
     */
    public void reset() {
        for (var stream : streams) {
            if (stream.decoding != null) {
                stream.decoding.close();
                pool.release(stream.decoding.picture);
                pool.release(stream.decoding.part);
                stream.decoding = null;
            }
            for (var picture : stream.pending) {
                pool.release(picture);
            }
            stream.pending.clear();
            stream.lastAnchorTime = Long.MIN_VALUE;
            stream.earlierAnchorTime = Long.MIN_VALUE;
            stream.reset();
        }
        pool.clear();
    }

    /**
     * The header state the pictures of a stream leave for the next one.
     *
     * @param plus whether the last header carried PLUSPTYPE
     * @param modes the optional modes of the last optional part of PLUSPTYPE, as OPPTYPE bits 5 to 14
     * @param format the source format code in effect
     * @param customWidth the width of the custom format
     * @param customHeight the height of the custom format
     * @param parWidth the pixel aspect ratio width
     * @param parHeight the pixel aspect ratio height
     * @param customClock whether a custom picture clock frequency is in effect
     * @param periodTicks the picture clock period in ticks of 1/1800000 second
     * @param unlimitedVectors whether the vectors are unlimited (UUI "01")
     * @param arbitrarySliceOrder whether the Arbitrary Slice Ordering submode is in use
     * @param lastReference the temporal reference of the last picture
     * @param time the time of the last picture in ticks
     */
    public record HeaderState(boolean plus, int modes, int format, int customWidth, int customHeight, int parWidth,
                              int parHeight, boolean customClock, int periodTicks, boolean unlimitedVectors,
                              boolean arbitrarySliceOrder, int lastReference, long time) {
    }
}

package com.github.auties00.smalt.video.vc1.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FrameSideData;

import java.util.Arrays;

/**
 * Decodes a VC-1 stream (SMPTE ST 421): the simple and main profile frames of a track with STRUCT_C initialization
 * metadata, and the advanced profile bitstream data units with their start codes.
 *
 * <p>The engine parses the sequence and entry-point headers, keeps the reference frames, decodes each picture and
 * hands frames to its {@link Output} in display order. Instances are not thread-safe.
 */
public final class Vc1DecoderEngine {
    /**
     * The most packet timestamps waiting for their frames.
     */
    private static final int MAX_PENDING = 32;

    /**
     * The start code suffix of an end of sequence.
     */
    private static final int SC_END_OF_SEQUENCE = 0x0A;

    /**
     * The start code suffix of a slice.
     */
    private static final int SC_SLICE = 0x0B;

    /**
     * The start code suffix of the second field of a field picture.
     */
    private static final int SC_FIELD = 0x0C;

    /**
     * The start code suffix of a frame.
     */
    private static final int SC_FRAME = 0x0D;

    /**
     * The start code suffix of an entry-point header.
     */
    private static final int SC_ENTRY_POINT = 0x0E;

    /**
     * The start code suffix of a sequence header.
     */
    private static final int SC_SEQUENCE = 0x0F;

    /**
     * Receives the frames of the engine.
     */
    public interface Output {
        /**
         * Receives one frame in display order.
         *
         * @param picture the picture, valid until the call returns
         * @param pts the presentation timestamp, or {@link Packet#NO_TIMESTAMP}
         * @param duration the duration, or {@link Packet#UNKNOWN_DURATION}
         * @param keyframe whether the frame is an I picture
         * @param sideData the display information of the frame
         */
        void emit(YuvPicture picture, long pts, long duration, boolean keyframe, FrameSideData sideData);
    }

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The time base of the timestamps.
     */
    private final Rational timeBase;

    /**
     * The frame rate the track declares, or {@code null}.
     */
    private final Rational trackFrameRate;

    /**
     * The picture width the track declares, for the simple and main profiles.
     */
    private final int trackWidth;

    /**
     * The picture height the track declares, for the simple and main profiles.
     */
    private final int trackHeight;

    /**
     * The receiver of the frames.
     */
    private final Output output;

    /**
     * The sequence in force.
     */
    private final SequenceHeader sequence = new SequenceHeader();

    /**
     * The entry point in force.
     */
    private final EntryPoint entry = new EntryPoint();

    /**
     * The header of the picture being decoded.
     */
    private final PictureHeader header = new PictureHeader();

    /**
     * The macroblock layer decoder.
     */
    private final PictureDecoder decoder = new PictureDecoder();

    /**
     * The reader of the sequence, entry-point and slice headers.
     */
    private final Bits headerBits = new Bits();

    /**
     * Whether a sequence is known.
     */
    private boolean configured;

    /**
     * Whether an entry point of the sequence in force was read.
     */
    private boolean entrySeen;

    /**
     * The most recent anchor (I or P) frame, the backward reference of B pictures.
     */
    private Frame lastAnchor;

    /**
     * The anchor before {@link #lastAnchor}, the forward reference of B pictures.
     */
    private Frame previousAnchor;

    /**
     * The anchor frame held back until the B pictures displayed before it are out, or {@code null}.
     */
    private Frame held;

    /**
     * The frames allocated, recycled once no reference holds them.
     */
    private Frame[] pool = new Frame[0];

    /**
     * The rounding control: toggled by each P picture of the simple and main profiles, coded in the advanced profile.
     */
    private int rnd = 1;

    /**
     * The horizontal RESPIC of the last I picture, which the following P and B pictures keep.
     */
    private int multiresHalfWidth;

    /**
     * The vertical RESPIC of the last I picture.
     */
    private int multiresHalfHeight;

    /**
     * Whether the B pictures after the last entry point have lost their forward anchor.
     */
    private boolean brokenLink;

    /**
     * The packet timestamps waiting for their frames, in increasing order.
     */
    private final long[] pending = new long[MAX_PENDING];

    /**
     * The number of entries of {@link #pending}.
     */
    private int pendingCount;

    /**
     * The timestamp of the last frame handed out.
     */
    private long lastPts = Packet.NO_TIMESTAMP;

    /**
     * The duration of the last frame handed out, or 0.
     */
    private long lastDuration;

    /**
     * The duration of a frame from the frame rate, or 0 when unknown.
     */
    private long frameDuration;

    /**
     * The color description of the output.
     */
    private ColorInfo color = ColorInfo.BT601_LIMITED;

    /**
     * The recycled output picture.
     */
    private YuvPicture target;

    /**
     * The unescaped bytes of the last unit read, followed by at least 8 zero bytes.
     */
    private byte[] unescaped = new byte[0];

    /**
     * The index of the first payload byte of each unit of the packet.
     */
    private int[] unitStarts = new int[64];

    /**
     * The index after the last byte of each unit.
     */
    private int[] unitEnds = new int[64];

    /**
     * The start code suffix of each unit, or -1 for a start code at the end of the data.
     */
    private int[] unitTypes = new int[64];

    /**
     * The number of units of the packet.
     */
    private int unitCount;

    /**
     * The message of a corrupt picture of the packet, raised once the packet is decoded, or {@code null}.
     */
    private String damage;

    /**
     * The unescaped picture, field or slice the macroblock reader reads.
     */
    private byte[] payload = new byte[0];

    /**
     * A packet whose frame unit lacks its start code, with the start code put back.
     */
    private byte[] prefixed = new byte[0];

    /**
     * The units of the slices of the picture being decoded.
     */
    private int[] sliceUnits = new int[16];

    /**
     * The reference fields of the field being decoded.
     */
    private final Frame[] fieldReferences = new Frame[4];

    /**
     * The parities of {@link #fieldReferences}.
     */
    private final int[] fieldParities = new int[4];

    /**
     * The repeated picture header of a slice.
     */
    private final PictureHeader repeatHeader = new PictureHeader();

    /**
     * Constructs an engine.
     *
     * @param limits the limits that picture sizes are checked against
     * @param timeBase the time base of the packet timestamps
     * @param trackFrameRate the frame rate of the track, or {@code null} if unknown
     * @param trackWidth the coded width of the track, used by the simple and main profiles
     * @param trackHeight the coded height of the track, used by the simple and main profiles
     * @param output the receiver of the frames
     */
    public Vc1DecoderEngine(MediaLimits limits, Rational timeBase, Rational trackFrameRate, int trackWidth,
                            int trackHeight, Output output) {
        this.limits = limits;
        this.timeBase = timeBase;
        this.trackFrameRate = trackFrameRate;
        this.trackWidth = trackWidth;
        this.trackHeight = trackHeight;
        this.output = output;
        updateDuration();
    }

    /**
     * Reads the decoder configuration: an advanced profile sequence header with its entry point (with start codes,
     * possibly after other bytes), the 36-byte sequence layer of SMPTE ST 421 Annex L, or a 4-byte STRUCT_C.
     *
     * @param config the configuration bytes
     * @throws MediaException.Malformed if the configuration is corrupt
     * @throws MediaException.Unsupported if the configuration declares a feature this decoder does not implement
     */
    public void configure(byte[] config) {
        var start = findStartCode(config, 0, config.length);
        if (start >= 0 && start + 3 < config.length && (config[start + 3] & 0xFF) == SC_SEQUENCE) {
            decodeUnits(config, start, config.length, Packet.NO_TIMESTAMP);
            return;
        }
        var width = trackWidth;
        var height = trackHeight;
        var offset = 0;
        if (config.length >= 36 && (config[3] & 0xFF) == 0xC5) {
            var extra = littleEndian(config, 4);
            if (extra < 4 || extra > config.length - 32) {
                throw new MediaException.Malformed("invalid VC-1 sequence layer");
            }
            offset = 8;
            height = littleEndian(config, 8 + extra);
            width = littleEndian(config, 12 + extra);
        }
        if (config.length < offset + 4) {
            throw new MediaException.Malformed("VC-1 configuration shorter than STRUCT_C");
        }
        headerBits.reset(config, offset, 4);
        sequence.parseStructC(headerBits);
        if (sequence.advanced()) {
            configured = false;
            return;
        }
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("VC-1 simple or main profile track without a picture size");
        }
        if (width > 8192 || height > 8192) {
            throw new MediaException.Malformed("VC-1 picture size above 8192");
        }
        LimitChecks.checkPicture(limits, width, height);
        entry.fromSequence(sequence, width, height);
        configured = true;
        entrySeen = true;
        color = colorOf(false);
        updateDuration();
    }

    /**
     * Reads a 32-bit little-endian value.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @return the value
     */
    private static int littleEndian(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
    }

    /**
     * Decodes the data of one packet.
     *
     * @param data the packet bytes
     * @param pts the presentation timestamp of the packet, or {@link Packet#NO_TIMESTAMP}
     * @throws MediaException.Malformed if the data is corrupt, after the frames due were handed out
     */
    public void decode(byte[] data, long pts) {
        damage = null;
        if (sequence.advanced() || !configured) {
            var start = findStartCode(data, 0, data.length);
            if (start < 0 && !configured) {
                throw new MediaException.Malformed("VC-1 packet before the sequence configuration");
            }
            if (start != 0) {
                var leading = start < 0 ? data.length : start;
                if (configured && leading > 0 && !allZero(data, 0, leading)) {
                    decodeUnitsWithLeadingFrame(data, leading, pts);
                    finishPacket();
                    return;
                }
            }
            if (start >= 0) {
                decodeUnits(data, start, data.length, pts);
            }
        } else {
            addPending(pts);
            decodeSimpleMainFrame(data, 0, data.length);
        }
        finishPacket();
    }

    /**
     * Raises the damage of the packet, if any.
     *
     * @throws MediaException.Malformed if a picture of the packet was corrupt
     */
    private void finishPacket() {
        if (damage != null) {
            var message = damage;
            damage = null;
            throw new MediaException.Malformed(message);
        }
    }

    /**
     * Returns whether a range holds only zero bytes.
     *
     * @param data the bytes
     * @param from the first index
     * @param to the index after the last
     * @return {@code true} when every byte is zero
     */
    private static boolean allZero(byte[] data, int from, int to) {
        for (var i = from; i < to; i++) {
            if (data[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Hands out the frame held for reordering and drops the references.
     */
    public void flush() {
        emitHeld();
        lastAnchor = null;
        previousAnchor = null;
        pendingCount = 0;
    }

    /**
     * Releases the frames.
     */
    public void close() {
        lastAnchor = null;
        previousAnchor = null;
        held = null;
        pool = new Frame[0];
        target = null;
    }

    /**
     * Returns the index of the first start code prefix ({@code 00 00 01}) of a range with a suffix byte.
     *
     * @param data the bytes
     * @param from the first index
     * @param to the index after the last
     * @return the index, or -1
     */
    private static int findStartCode(byte[] data, int from, int to) {
        for (var i = from; i + 3 < to; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Decodes a packet whose first bytes are a frame unit without its start code, as Windows Media and Matroska store
     * advanced profile frames.
     *
     * @param data the packet
     * @param leading the length of the frame unit
     * @param pts the packet timestamp
     */
    private void decodeUnitsWithLeadingFrame(byte[] data, int leading, long pts) {
        var length = data.length + 4;
        if (prefixed.length < length) {
            prefixed = new byte[length];
        }
        prefixed[0] = 0;
        prefixed[1] = 0;
        prefixed[2] = 1;
        prefixed[3] = SC_FRAME;
        System.arraycopy(data, 0, prefixed, 4, data.length);
        decodeUnits(prefixed, 0, length, pts);
    }

    /**
     * Splits a range into its bitstream data units and decodes them in order: sequence headers, entry points, and each
     * frame with the field, slice and user data units that follow it.
     *
     * @param data the bytes
     * @param from the index of the first start code
     * @param to the index after the last byte
     * @param pts the packet timestamp, given to the first frame
     * @throws MediaException.Malformed if a sequence or entry-point header is corrupt
     */
    private void decodeUnits(byte[] data, int from, int to, long pts) {
        unitCount = 0;
        var position = from;
        while (position >= 0 && position < to) {
            var next = findStartCode(data, position + 3, to);
            var end = next < 0 ? to : next;
            if (unitCount == unitStarts.length) {
                unitStarts = Arrays.copyOf(unitStarts, unitCount * 2);
                unitEnds = Arrays.copyOf(unitEnds, unitCount * 2);
                unitTypes = Arrays.copyOf(unitTypes, unitCount * 2);
            }
            unitTypes[unitCount] = position + 3 < to ? data[position + 3] & 0xFF : -1;
            unitStarts[unitCount] = position + 4;
            unitEnds[unitCount] = end;
            unitCount++;
            position = next;
        }
        var pictureCount = 0;
        for (var i = 0; i < unitCount; i++) {
            if (unitTypes[i] == SC_FRAME) {
                pictureCount++;
            }
        }
        var ptsUsed = false;
        for (var i = 0; i < unitCount; i++) {
            var type = unitTypes[i];
            switch (type) {
                case SC_SEQUENCE -> {
                    var length = unescape(data, unitStarts[i], unitEnds[i]);
                    headerBits.reset(unescaped, 0, length);
                    try {
                        sequenceHeader();
                    } catch (MediaException.Malformed exception) {
                        configured = false;
                        throw exception;
                    }
                }
                case SC_ENTRY_POINT -> {
                    if (!configured) {
                        continue;
                    }
                    var length = unescape(data, unitStarts[i], unitEnds[i]);
                    headerBits.reset(unescaped, 0, length);
                    entrySeen = false;
                    entry.parse(headerBits, sequence);
                    if (entry.codedWidth > sequence.maxCodedWidth || entry.codedHeight > sequence.maxCodedHeight) {
                        throw new MediaException.Malformed("VC-1 coded size above the sequence maximum");
                    }
                    LimitChecks.checkPicture(limits, entry.codedWidth, entry.codedHeight);
                    entrySeen = true;
                    brokenLink = entry.brokenLink;
                    if (entry.closedEntry) {
                        brokenLink = false;
                    }
                }
                case SC_FRAME -> {
                    if (!configured || !entrySeen) {
                        continue;
                    }
                    if (!ptsUsed) {
                        addPending(pts);
                        ptsUsed = true;
                    } else if (pictureCount > 1) {
                        addPending(Packet.NO_TIMESTAMP);
                    }
                    var last = i + 1;
                    while (last < unitCount && (unitTypes[last] == SC_SLICE || unitTypes[last] == SC_FIELD
                            || (unitTypes[last] >= 0x1B && unitTypes[last] <= 0x1D)
                            || (unitTypes[last] >= 0x20 && unitTypes[last] <= 0x40))) {
                        last++;
                    }
                    try {
                        decodeAdvancedPicture(data, i, last);
                    } catch (MediaException.Malformed exception) {
                        damage = exception.getMessage();
                    }
                    i = last - 1;
                }
                case SC_END_OF_SEQUENCE -> {
                }
                default -> {
                }
            }
        }
    }

    /**
     * Parses an advanced profile sequence header from {@link #headerBits}; a change of the maximum size requires a new
     * entry point.
     *
     * @throws MediaException.Malformed if the header is corrupt
     * @throws MediaException.Unsupported if the header declares a color-difference format other than 4:2:0
     */
    private void sequenceHeader() {
        var wasAdvanced = configured && sequence.advanced();
        var oldWidth = sequence.maxCodedWidth;
        var oldHeight = sequence.maxCodedHeight;
        sequence.parseAdvanced(headerBits);
        LimitChecks.checkPicture(limits, sequence.maxCodedWidth, sequence.maxCodedHeight);
        if (!wasAdvanced || oldWidth != sequence.maxCodedWidth || oldHeight != sequence.maxCodedHeight) {
            entrySeen = false;
        }
        configured = true;
        color = colorOf(true);
        updateDuration();
    }

    /**
     * Returns the color description of the output.
     *
     * @param advanced whether the color description of the sequence header applies
     * @return the description
     */
    private ColorInfo colorOf(boolean advanced) {
        var primaries = ColorPrimaries.BT709;
        var transfer = TransferCharacteristics.BT709;
        var matrix = MatrixCoefficients.BT601;
        if (advanced && sequence.colorFormatPresent) {
            if (ColorPrimaries.of(sequence.colorPrimaries) != ColorPrimaries.UNSPECIFIED) {
                primaries = ColorPrimaries.of(sequence.colorPrimaries);
            }
            var transferCode = sequence.transferCharacteristics == 8 ? 12 : sequence.transferCharacteristics;
            if (TransferCharacteristics.of(transferCode) != TransferCharacteristics.UNSPECIFIED) {
                transfer = TransferCharacteristics.of(transferCode);
            }
            if (MatrixCoefficients.of(sequence.matrixCoefficients) != MatrixCoefficients.UNSPECIFIED) {
                matrix = MatrixCoefficients.of(sequence.matrixCoefficients);
            }
        }
        return new ColorInfo(matrix, ColorRange.LIMITED, primaries, transfer).withChromaSiting(ChromaSiting.CENTER);
    }

    /**
     * Computes {@link #frameDuration} from the frame rate of the sequence, or of the track.
     */
    private void updateDuration() {
        Rational rate = null;
        if (configured && sequence.advanced() && sequence.frameRateNumerator > 0) {
            rate = new Rational(sequence.frameRateNumerator, sequence.frameRateDenominator);
        }
        if (rate == null) {
            rate = trackFrameRate;
        }
        frameDuration = 0;
        if (rate != null && rate.numerator() > 0 && timeBase != null) {
            try {
                frameDuration = Math.max(0, rate.invert().rescale(1, timeBase));
            } catch (ArithmeticException | IllegalArgumentException exception) {
                frameDuration = 0;
            }
        }
    }

    /**
     * Removes the emulation prevention bytes of a bitstream data unit into {@link #unescaped}.
     *
     * @return the number of bytes
     */
    private int unescape(byte[] data, int from, int to) {
        if (unescaped.length < to - from + 8) {
            unescaped = new byte[to - from + 8];
        }
        var length = 0;
        var zeros = 0;
        for (var i = from; i < to; i++) {
            var value = data[i];
            if (zeros >= 2 && value == 3 && i + 1 < to && (data[i + 1] & 0xFF) <= 3) {
                zeros = 0;
                continue;
            }
            zeros = value == 0 ? zeros + 1 : 0;
            unescaped[length++] = value;
        }
        Arrays.fill(unescaped, length, Math.min(unescaped.length, length + 8), (byte) 0);
        return length;
    }

    /**
     * Copies the unescaped unit into {@link #payload}, which the macroblock reader keeps reading while
     * {@link #unescaped} serves the slice headers.
     *
     * @param length the length of the unit in {@link #unescaped}
     * @return the payload buffer, followed by at least 8 zero bytes
     */
    private byte[] payload(int length) {
        if (payload.length < length + 8) {
            payload = new byte[length + 8 + (length >> 1)];
        }
        System.arraycopy(unescaped, 0, payload, 0, length + 8);
        return payload;
    }

    /**
     * Returns a frame of a size that no reference holds, recycled or allocated, its picture state reset.
     *
     * @param width the luma width
     * @param height the luma height
     * @return the frame
     */
    private Frame acquire(int width, int height) {
        for (var frame : pool) {
            if (frame != lastAnchor && frame != previousAnchor && frame != held && frame.width == width
                    && frame.height == height) {
                frame.emitted = false;
                frame.hasMotion = false;
                frame.rangeReduced = false;
                frame.fieldPicture = false;
                frame.fieldHasMotion[0] = false;
                frame.fieldHasMotion[1] = false;
                for (var parity = 0; parity < 2; parity++) {
                    frame.lumaLut[parity] = null;
                    frame.chromaLut[parity] = null;
                }
                return frame;
            }
        }
        LimitChecks.checkAllocation(limits, (long) width * height * 3 / 2);
        var frame = new Frame(width, height);
        var grown = Arrays.copyOf(pool, pool.length + 1);
        grown[pool.length] = frame;
        pool = grown;
        if (pool.length > 6) {
            var kept = new Frame[0];
            for (var candidate : pool) {
                if (candidate == lastAnchor || candidate == previousAnchor || candidate == held
                        || candidate == frame) {
                    kept = Arrays.copyOf(kept, kept.length + 1);
                    kept[kept.length - 1] = candidate;
                }
            }
            pool = kept;
        }
        return frame;
    }

    /**
     * Decodes one simple or main profile frame.
     *
     * @param data the bytes
     * @param offset the index of the frame
     * @param length the frame length
     */
    private void decodeSimpleMainFrame(byte[] data, int offset, int length) {
        if (length <= 1) {
            skippedPicture();
            return;
        }
        try {
            decoder.bits.reset(data, offset, length);
            header.parseSimpleMain(decoder.bits, sequence, entry);
            if (header.type == PictureHeader.I) {
                multiresHalfWidth = header.halfWidth;
                multiresHalfHeight = header.halfHeight;
            }
            var mbWidth = (entry.codedWidth + 15) / 16;
            var mbHeight = (entry.codedHeight + 15) / 16;
            if (sequence.multiResolution) {
                if (multiresHalfWidth != 0) {
                    mbWidth = (mbWidth * 16 / 2 + 15) / 16;
                }
                if (multiresHalfHeight != 0) {
                    mbHeight = (mbHeight * 16 / 2 + 15) / 16;
                }
            }
            switch (header.type) {
                case PictureHeader.I, PictureHeader.BI -> rnd = 1;
                case PictureHeader.P -> rnd ^= 1;
                default -> {
                }
            }
            decodeProgressive(mbWidth, mbHeight, mbWidth * 16, mbHeight * 16);
        } catch (MediaException.Malformed exception) {
            damage = exception.getMessage();
        }
    }

    /**
     * Decodes one advanced profile picture: a progressive, interlace frame or interlace field picture with its slices.
     *
     * @param data the bytes
     * @param unit the frame unit
     * @param end the unit after the last unit of the picture
     * @throws MediaException.Malformed if the header is corrupt
     */
    private void decodeAdvancedPicture(byte[] data, int unit, int end) {
        var length = unescape(data, unitStarts[unit], unitEnds[unit]);
        decoder.bits.reset(payload(length), 0, length);
        var coded = header.parseAdvancedStart(decoder.bits, sequence, entry);
        if (header.frameCodingMode == PictureHeader.FIELD_INTERLACE) {
            decodeFieldPicture(data, unit, end);
            return;
        }
        if (!coded) {
            skippedPicture();
            return;
        }
        rnd = header.rnd;
        var mbWidth = (entry.codedWidth + 15) / 16;
        var mbHeight = (entry.codedHeight + 15) / 16;
        if (sliceUnits.length < end - unit) {
            sliceUnits = new int[end - unit];
        }
        var sliceCount = 0;
        for (var i = unit + 1; i < end; i++) {
            if (unitTypes[i] == SC_SLICE) {
                sliceUnits[sliceCount++] = i;
            }
        }
        decodeProgressiveAdvanced(data, mbWidth, mbHeight, sliceUnits, sliceCount);
    }

    /**
     * Decodes an interlace field picture: its frame header, then both fields, the second from its field unit.
     *
     * @param data the bytes
     * @param unit the frame unit
     * @param end the unit after the last unit of the picture
     */
    private void decodeFieldPicture(byte[] data, int unit, int end) {
        header.parseFieldFrameHeader(decoder.bits, sequence, entry);
        rnd = header.rnd;
        var firstType = header.firstFieldType;
        var anchor = firstType == PictureHeader.I || firstType == PictureHeader.P;
        var mbWidth = (entry.codedWidth + 15) / 16;
        var fieldRows = (entry.codedHeight + 31) / 32;
        var width = mbWidth * 16;
        var height = fieldRows * 32;
        if (!anchor) {
            if (previousAnchor == null || lastAnchor == null || lastAnchor.width != width
                    || lastAnchor.height != height || previousAnchor.width != width
                    || previousAnchor.height != height || brokenLink) {
                return;
            }
        } else {
            emitHeld();
        }
        var frame = acquire(width, height);
        frame.validWidth = entry.codedWidth;
        frame.validHeight = entry.codedHeight;
        frame.outputWidth = entry.codedWidth;
        frame.outputHeight = entry.codedHeight;
        frame.interlaced = true;
        frame.fieldPicture = true;
        frame.type = anchor ? PictureHeader.P : PictureHeader.B;
        frame.rangeReducedFrame = false;
        frame.rangeReduced = false;
        frame.rangeMapY = entry.rangeMapY;
        frame.rangeMapUv = entry.rangeMapUv;
        frame.topFieldFirst = header.topFieldFirst;
        frame.repeatFirstField = header.repeatFirstField;
        frame.repeatFrameCount = header.repeatFrameCount;
        frame.keyframe = firstType == PictureHeader.I;
        frame.halfWidth = 0;
        frame.halfHeight = 0;
        frame.progressiveDisplay = sequence.psf;
        frame.referenceDistance = header.referenceDistance;
        var firstParity = header.topFieldFirst ? 0 : 1;
        var fieldUnit = -1;
        for (var i = unit + 1; i < end; i++) {
            if (unitTypes[i] == SC_FIELD) {
                fieldUnit = i;
                break;
            }
        }
        try {
            header.type = firstType;
            decodeField(data, frame, firstParity, false, mbWidth, fieldRows, unit + 1,
                    fieldUnit < 0 ? end : fieldUnit, 0);
            if (fieldUnit < 0) {
                throw new MediaException.Malformed("VC-1 field picture without its second field");
            }
            var length = unescape(data, unitStarts[fieldUnit], unitEnds[fieldUnit]);
            decoder.bits.reset(payload(length), 0, length);
            header.type = header.secondFieldType;
            decodeField(data, frame, 1 - firstParity, true, mbWidth, fieldRows, fieldUnit + 1, end, fieldRows);
        } catch (MediaException.Malformed exception) {
            damage = exception.getMessage();
        }
        if (anchor) {
            previousAnchor = lastAnchor;
            lastAnchor = frame;
            held = frame;
            if (firstType == PictureHeader.I && brokenLink) {
                brokenLink = false;
            }
        } else {
            emit(frame);
        }
    }

    /**
     * Decodes one field of a field picture, the reader at its field picture layer.
     *
     * @param sliceFrom the first unit that may hold a slice of the field
     * @param sliceTo the unit after the last
     * @param addressOffset the slice address of the field's first macroblock row
     */
    private void decodeField(byte[] data, Frame frame, int parity, boolean second, int mbWidth, int fieldRows,
                             int sliceFrom, int sliceTo, int addressOffset) {
        header.parseQuantizer(decoder.bits, sequence, entry, true);
        var type = header.type;
        var references = fieldReferences;
        var parities = fieldParities;
        Arrays.fill(references, null);
        Arrays.fill(parities, 0);
        if (type == PictureHeader.P) {
            references[0] = lastAnchor;
            parities[0] = parity;
            references[1] = second ? frame : lastAnchor;
            parities[1] = 1 - parity;
        } else if (type == PictureHeader.B) {
            references[0] = previousAnchor;
            parities[0] = parity;
            references[1] = second ? frame : previousAnchor;
            parities[1] = 1 - parity;
            references[2] = lastAnchor;
            parities[2] = parity;
            references[3] = lastAnchor;
            parities[3] = 1 - parity;
        }
        var backwardDistance = lastAnchor != null ? lastAnchor.referenceDistance : 0;
        decoder.beginField(sequence, entry, header, frame, parity, second, references, parities, mbWidth, fieldRows,
                rnd, backwardDistance);
        var row = 0;
        try {
            for (var i = sliceFrom; i <= sliceTo; i++) {
                if (i < sliceTo && unitTypes[i] != SC_SLICE) {
                    continue;
                }
                var nextRow = fieldRows;
                if (i < sliceTo) {
                    var length = unescape(data, unitStarts[i], unitEnds[i]);
                    headerBits.reset(unescaped, 0, length);
                    nextRow = Math.min(fieldRows, Math.max(0, headerBits.read(9) - addressOffset));
                }
                if (nextRow > row) {
                    decoder.decodeRows(row, nextRow);
                }
                if (i == sliceTo) {
                    break;
                }
                var length = unescape(data, unitStarts[i], unitEnds[i]);
                decoder.bits.reset(payload(length), 0, length);
                var address = decoder.bits.read(9) - addressOffset;
                if (address < row || address >= fieldRows) {
                    throw new MediaException.Malformed("invalid VC-1 slice address");
                }
                if (decoder.bits.readFlag()) {
                    var repeat = repeatHeader;
                    repeat.parseAdvancedStart(decoder.bits, sequence, entry);
                    if (repeat.frameCodingMode != PictureHeader.FIELD_INTERLACE) {
                        throw new MediaException.Malformed("VC-1 field slice repeating a frame picture header");
                    }
                    repeat.parseFieldFrameHeader(decoder.bits, sequence, entry);
                    repeat.type = second ? repeat.secondFieldType : repeat.firstFieldType;
                    repeat.parseQuantizer(decoder.bits, sequence, entry, true);
                    decoder.skipRepeatedFieldHeader(repeat, backwardDistance);
                }
                decoder.slice(address);
                row = address;
            }
        } finally {
            decoder.finish();
        }
    }

    /**
     * Decodes the macroblocks of an advanced profile frame picture, progressive or interlace frame, the reader after
     * its header.
     *
     * @param data the bytes
     * @param mbWidth the macroblock columns
     * @param mbHeight the macroblock rows
     * @param slices the slice units
     * @param sliceCount the number of slice units
     */
    private void decodeProgressiveAdvanced(byte[] data, int mbWidth, int mbHeight, int[] slices, int sliceCount) {
        var frame = beginPicture(mbWidth, mbHeight, entry.codedWidth, entry.codedHeight);
        if (frame == null) {
            return;
        }
        try {
            decodeSlices(data, mbHeight, slices, sliceCount);
        } catch (MediaException.Malformed exception) {
            damage = exception.getMessage();
        }
        endPicture(frame);
    }

    /**
     * Decodes the macroblock rows of a frame picture slice by slice.
     *
     * @param data the bytes
     * @param mbHeight the macroblock rows
     * @param slices the slice units
     * @param sliceCount the number of slice units
     * @throws MediaException.Malformed if a slice is corrupt
     */
    private void decodeSlices(byte[] data, int mbHeight, int[] slices, int sliceCount) {
        var row = 0;
        for (var s = 0; s <= sliceCount; s++) {
            var nextRow = mbHeight;
            if (s < sliceCount) {
                var unit = slices[s];
                var length = unescape(data, unitStarts[unit], unitEnds[unit]);
                headerBits.reset(unescaped, 0, length);
                nextRow = Math.min(mbHeight, headerBits.read(9));
            }
            if (nextRow > row) {
                decoder.decodeRows(row, nextRow);
            }
            if (s == sliceCount) {
                break;
            }
            var unit = slices[s];
            var length = unescape(data, unitStarts[unit], unitEnds[unit]);
            decoder.bits.reset(payload(length), 0, length);
            var address = decoder.bits.read(9);
            if (address < row || address >= mbHeight) {
                throw new MediaException.Malformed("invalid VC-1 slice address");
            }
            if (decoder.bits.readFlag()) {
                var repeat = repeatHeader;
                repeat.parseAdvancedStart(decoder.bits, sequence, entry);
                decoder.skipRepeatedHeader(repeat);
            }
            decoder.slice(address);
            row = address;
        }
    }

    /**
     * Starts a frame picture: finds its references, takes a frame and reads the rest of the header.
     *
     * @param mbWidth the macroblock columns
     * @param mbHeight the macroblock rows
     * @param validWidth the width motion compensation reads
     * @param validHeight the height motion compensation reads
     * @return the frame, or {@code null} when the picture cannot be decoded for want of references
     */
    private Frame beginPicture(int mbWidth, int mbHeight, int validWidth, int validHeight) {
        var type = header.type;
        var width = mbWidth * 16;
        var height = mbHeight * 16;
        if (sequence.interlace) {
            height = (height + 31) & ~31;
        }
        Frame forward = null;
        Frame backward = null;
        if (type == PictureHeader.P) {
            forward = lastAnchor;
            if (forward == null || forward.width != width || forward.height != height) {
                damage = "VC-1 P picture without a reference frame";
                return null;
            }
        } else if (type == PictureHeader.B) {
            forward = previousAnchor;
            backward = lastAnchor;
            if (forward == null || backward == null || forward.width != width || backward.width != width
                    || forward.height != height || backward.height != height || brokenLink) {
                return null;
            }
        }
        var anchor = type == PictureHeader.I || type == PictureHeader.P;
        if (anchor) {
            emitHeld();
        }
        if (!sequence.advanced() && sequence.rangeReduction) {
            if (forward != null && forward.rangeReduced != header.rangeReducedFrame) {
                forward.scaleRange(header.rangeReducedFrame);
            }
        }
        var frame = acquire(width, height);
        frame.validWidth = validWidth;
        frame.validHeight = validHeight;
        frame.outputWidth = entry.codedWidth;
        frame.outputHeight = entry.codedHeight;
        frame.interlaced = header.frameCodingMode != PictureHeader.PROGRESSIVE;
        frame.fieldPicture = false;
        frame.type = type;
        frame.rangeReducedFrame = header.rangeReducedFrame;
        frame.rangeReduced = header.rangeReducedFrame;
        frame.rangeMapY = sequence.advanced() ? entry.rangeMapY : -1;
        frame.rangeMapUv = sequence.advanced() ? entry.rangeMapUv : -1;
        frame.topFieldFirst = header.topFieldFirst;
        frame.repeatFirstField = header.repeatFirstField;
        frame.repeatFrameCount = header.repeatFrameCount;
        frame.keyframe = type == PictureHeader.I;
        frame.halfWidth = sequence.advanced() ? 0 : multiresHalfWidth;
        frame.halfHeight = sequence.advanced() ? 0 : multiresHalfHeight;
        frame.progressiveDisplay = !sequence.interlace || sequence.psf;
        decoder.begin(sequence, entry, header, frame, forward, backward, mbWidth, mbHeight, rnd);
        return frame;
    }

    /**
     * Completes a frame picture: the in-loop filters, and the anchor bookkeeping or the output of a B picture.
     *
     * @param frame the decoded frame
     */
    private void endPicture(Frame frame) {
        decoder.finish();
        var type = header.type;
        if (type == PictureHeader.I || type == PictureHeader.P) {
            previousAnchor = lastAnchor;
            lastAnchor = frame;
            held = frame;
            if (type == PictureHeader.I && brokenLink) {
                brokenLink = false;
            }
        } else {
            emit(frame);
        }
    }

    /**
     * Decodes a simple or main profile frame picture.
     *
     * @param mbWidth the macroblock columns
     * @param mbHeight the macroblock rows
     * @param validWidth the width motion compensation reads
     * @param validHeight the height motion compensation reads
     */
    private void decodeProgressive(int mbWidth, int mbHeight, int validWidth, int validHeight) {
        var frame = beginPicture(mbWidth, mbHeight, validWidth, validHeight);
        if (frame == null) {
            return;
        }
        try {
            decoder.decodeRows(0, mbHeight);
        } catch (MediaException.Malformed exception) {
            damage = exception.getMessage();
        }
        endPicture(frame);
    }

    /**
     * Repeats the last anchor for a skipped picture; the copy becomes the new anchor, without motion vectors.
     */
    private void skippedPicture() {
        if (lastAnchor == null) {
            damage = "VC-1 skipped picture without a reference frame";
            return;
        }
        emitHeld();
        var reference = lastAnchor;
        var frame = acquire(reference.width, reference.height);
        frame.copyFrom(reference);
        frame.type = PictureHeader.SKIPPED;
        frame.rangeReducedFrame = reference.rangeReduced;
        frame.rangeMapY = sequence.advanced() ? entry.rangeMapY : -1;
        frame.rangeMapUv = sequence.advanced() ? entry.rangeMapUv : -1;
        frame.topFieldFirst = sequence.advanced() ? header.topFieldFirst : true;
        frame.repeatFirstField = sequence.advanced() && header.repeatFirstField;
        frame.repeatFrameCount = sequence.advanced() ? header.repeatFrameCount : 0;
        frame.keyframe = false;
        frame.halfWidth = reference.halfWidth;
        frame.halfHeight = reference.halfHeight;
        frame.progressiveDisplay = reference.progressiveDisplay;
        frame.hasMotion = false;
        previousAnchor = lastAnchor;
        lastAnchor = frame;
        held = frame;
    }

    /**
     * Hands out the anchor held back, if it was not yet.
     */
    private void emitHeld() {
        if (held != null && !held.emitted) {
            emit(held);
        }
        held = null;
    }

    /**
     * Records a packet timestamp for the next frame without one.
     *
     * @param pts the timestamp; {@link Packet#NO_TIMESTAMP} records nothing
     */
    private void addPending(long pts) {
        if (pts == Packet.NO_TIMESTAMP) {
            return;
        }
        if (pendingCount == MAX_PENDING) {
            System.arraycopy(pending, 1, pending, 0, --pendingCount);
        }
        var index = pendingCount;
        while (index > 0 && pending[index - 1] > pts) {
            pending[index] = pending[index - 1];
            index--;
        }
        pending[index] = pts;
        pendingCount++;
    }

    /**
     * Hands a frame to the output with its timestamp, duration and field order.
     *
     * @param frame the decoded frame
     */
    private void emit(Frame frame) {
        frame.emitted = true;
        FieldOrder order = null;
        var repeated = 0;
        if (sequence.advanced()) {
            if (frame.progressiveDisplay) {
                order = FieldOrder.PROGRESSIVE;
                repeated = 2 * frame.repeatFrameCount;
            } else {
                order = frame.topFieldFirst ? FieldOrder.TOP_FIRST : FieldOrder.BOTTOM_FIRST;
                repeated = frame.repeatFirstField ? 1 : 0;
            }
        }
        var duration = frameDuration > 0 ? frameDuration * (2 + repeated) / 2 : Packet.UNKNOWN_DURATION;
        var step = Math.max(1, lastDuration > 0 ? lastDuration : frameDuration);
        long pts;
        if (pendingCount > 0) {
            pts = pending[0];
            System.arraycopy(pending, 1, pending, 0, --pendingCount);
        } else {
            pts = lastPts == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP : lastPts + step;
        }
        if (lastPts != Packet.NO_TIMESTAMP && pts != Packet.NO_TIMESTAMP && pts <= lastPts) {
            pts = lastPts + step;
        }
        if (pts != Packet.NO_TIMESTAMP) {
            lastPts = pts;
        }
        lastDuration = duration > 0 ? duration : 0;
        var side = order == null ? FrameSideData.NONE
                : FrameSideData.builder().fieldOrder(order).repeatedFields(repeated).build();
        output.emit(render(frame), pts, duration, frame.keyframe, side);
    }

    /**
     * Writes a frame into the output picture: the coded size it was decoded at, range mapped and up-sampled.
     *
     * @param frame the decoded frame
     * @return the picture
     */
    private YuvPicture render(Frame frame) {
        var width = frame.outputWidth;
        var height = frame.outputHeight;
        var picture = target;
        if (picture == null || picture.width() != width || picture.height() != height
                || !picture.color().equals(color)) {
            picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, color);
            target = picture;
        }
        var lumaMap = frame.rangeReducedFrame ? -2 : frame.rangeMapY;
        var chromaMap = frame.rangeReducedFrame ? -2 : frame.rangeMapUv;
        var lumaWidth = frame.halfWidth != 0 ? ((width + 15) & ~15) / 2 : frame.width;
        var lumaHeight = frame.halfHeight != 0 ? ((height + 15) & ~15) / 2 : frame.height;
        copyPlane(frame.y, frame.width, lumaWidth, lumaHeight, frame.halfWidth, frame.halfHeight, picture.y(),
                picture.yStride(), width, height, lumaMap);
        copyPlane(frame.u, frame.chromaWidth, lumaWidth / 2, lumaHeight / 2, frame.halfWidth, frame.halfHeight,
                picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), chromaMap);
        copyPlane(frame.v, frame.chromaWidth, lumaWidth / 2, lumaHeight / 2, frame.halfWidth, frame.halfHeight,
                picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), chromaMap);
        return picture;
    }

    /**
     * Copies a plane into the output, applying the display mapping and the multi-resolution up-sampling.
     *
     * @param source the decoded plane
     * @param stride the distance between the rows of {@code source}
     * @param sourceWidth the width of the samples coded in {@code source}
     * @param sourceHeight the height of the samples coded in {@code source}
     * @param halfWidth 1 when the plane is coded at half width
     * @param halfHeight 1 when the plane is coded at half height
     * @param out the output plane
     * @param outStride the distance between the rows of {@code out}
     * @param width the output width
     * @param height the output height
     * @param map -1 for none, -2 for range reduction, otherwise the RANGE_MAPY or RANGE_MAPUV value
     */
    private static void copyPlane(byte[] source, int stride, int sourceWidth, int sourceHeight, int halfWidth,
                                  int halfHeight, byte[] out, int outStride, int width, int height, int map) {
        if (halfWidth == 0 && halfHeight == 0 && map == -1) {
            for (var y = 0; y < height; y++) {
                System.arraycopy(source, y * stride, out, y * outStride, width);
            }
            return;
        }
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                int value;
                if (halfWidth == 0 && halfHeight == 0) {
                    value = source[y * stride + x] & 0xFF;
                } else {
                    value = upsample(source, stride, sourceWidth, sourceHeight, x, y, halfWidth, halfHeight);
                }
                if (map == -2) {
                    value = Frame.clip((value - 128) * 2 + 128);
                } else if (map >= 0) {
                    value = Frame.clip((((value - 128) * (map + 9) + 4) >> 3) + 128);
                }
                out[y * outStride + x] = (byte) value;
            }
        }
    }

    /**
     * Returns one sample of a plane coded at half width or height, up-sampled with the 1/4, 3/4 bilinear filter.
     *
     * @param source the plane
     * @param stride the distance between its rows
     * @param sourceWidth the coded width
     * @param sourceHeight the coded height
     * @param x the output column
     * @param y the output row
     * @param halfWidth 1 when the plane is coded at half width
     * @param halfHeight 1 when the plane is coded at half height
     * @return the sample
     */
    private static int upsample(byte[] source, int stride, int sourceWidth, int sourceHeight, int x, int y,
                                int halfWidth, int halfHeight) {
        int x0;
        int x1;
        int wx;
        if (halfWidth != 0) {
            x0 = (x - 1) >> 1;
            x1 = x0 + 1;
            wx = (x & 1) == 0 ? 3 : 1;
        } else {
            x0 = x;
            x1 = x;
            wx = 0;
        }
        int y0;
        int y1;
        int wy;
        if (halfHeight != 0) {
            y0 = (y - 1) >> 1;
            y1 = y0 + 1;
            wy = (y & 1) == 0 ? 3 : 1;
        } else {
            y0 = y;
            y1 = y;
            wy = 0;
        }
        x0 = Math.clamp(x0, 0, sourceWidth - 1);
        x1 = Math.clamp(x1, 0, sourceWidth - 1);
        y0 = Math.clamp(y0, 0, sourceHeight - 1);
        y1 = Math.clamp(y1, 0, sourceHeight - 1);
        var a = source[y0 * stride + x0] & 0xFF;
        var b = source[y0 * stride + x1] & 0xFF;
        var c = source[y1 * stride + x0] & 0xFF;
        var d = source[y1 * stride + x1] & 0xFF;
        var top = a * (4 - wx) + b * wx;
        var bottom = c * (4 - wx) + d * wx;
        return (top * (4 - wy) + bottom * wy + 8) >> 4;
    }
}

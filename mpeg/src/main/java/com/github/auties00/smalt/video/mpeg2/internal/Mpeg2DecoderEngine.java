package com.github.auties00.smalt.video.mpeg2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.caption.CcData;
import com.github.auties00.smalt.internal.codec.MpegVideoHeaders;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FrameSideData;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Decodes an MPEG-1 or MPEG-2 video elementary stream (ISO/IEC 11172-2, ISO/IEC 13818-2): it parses the sequence,
 * group of pictures and picture headers, pairs field pictures into frames, keeps the reference frames, decodes the
 * slices of each picture (in parallel rows for MPEG-2), conceals what is missing or damaged, and hands the frames
 * out in display order with their timing and side data.
 *
 * <p>Packets carry whole pictures, with any headers before them; a packet may carry several pictures, and the two
 * field pictures of a frame may come in one packet or in two. Slices before the first picture header of a packet are
 * ignored. Instances are not thread-safe.
 */
public final class Mpeg2DecoderEngine {
    /**
     * The most timestamps waiting for a frame.
     */
    private static final int MAX_PENDING = 32;

    /**
     * The {@code extension_start_code_identifier} of a quant matrix extension.
     */
    private static final int QUANT_MATRIX_EXTENSION = 3;

    /**
     * The {@code extension_start_code_identifier} of a picture coding extension.
     */
    private static final int PICTURE_CODING_EXTENSION = 8;

    /**
     * The {@code extension_start_code_identifier} of a picture spatial scalable extension.
     */
    private static final int PICTURE_SPATIAL_EXTENSION = 9;

    /**
     * The {@code extension_start_code_identifier} of a picture temporal scalable extension.
     */
    private static final int PICTURE_TEMPORAL_EXTENSION = 10;

    /**
     * Receives the frames of the engine.
     */
    public interface Output {
        /**
         * Accepts one frame in display order.
         *
         * @param picture the picture, valid until the call returns
         * @param pts the presentation timestamp, or {@link Packet#NO_TIMESTAMP}
         * @param duration the duration, or {@link Packet#UNKNOWN_DURATION}
         * @param keyframe whether the frame comes from an I- or D-picture
         * @param sideData the scan, repetition and captions of the frame
         */
        void emit(YuvPicture picture, long pts, long duration, boolean keyframe, FrameSideData sideData);
    }

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The most threads to decode the rows of a picture with, the caller included.
     */
    private final int threads;

    /**
     * The time base of timestamps and durations.
     */
    private final Rational timeBase;

    /**
     * The frame rate the track declares, or {@code null}.
     */
    private final Rational trackFrameRate;

    /**
     * The receiver of the frames.
     */
    private final Output output;

    /**
     * The slice decoders, one per band of rows.
     */
    private SliceDecoder[] decoders = new SliceDecoder[]{new SliceDecoder()};

    /**
     * The parameters of the picture being decoded.
     */
    private final Picture picture = new Picture();

    /**
     * The frames recycled between pictures.
     */
    private final Frame[] pool = new Frame[4];

    /**
     * The sequence in force, or {@code null} before the first sequence header.
     */
    private MpegVideoHeaders.Sequence sequence;

    /**
     * The color of the sequence's pictures.
     */
    private ColorInfo color;

    /**
     * The macroblock columns of the sequence.
     */
    private int mbWidth;

    /**
     * The macroblock rows of a frame of the sequence.
     */
    private int mbHeight;

    /**
     * The intra quantizer matrix in force, raster order.
     */
    private int[] intraMatrix;

    /**
     * The non-intra quantizer matrix in force, raster order.
     */
    private int[] nonIntraMatrix;

    /**
     * The chroma intra quantizer matrix in force, raster order.
     */
    private int[] chromaIntraMatrix;

    /**
     * The chroma non-intra quantizer matrix in force, raster order.
     */
    private int[] chromaNonIntraMatrix;

    /**
     * The nominal frame duration in the time base, or 0 when unknown.
     */
    private long frameDuration;

    /**
     * The older reference frame, or {@code null}.
     */
    private Frame forward;

    /**
     * The newer reference frame, or {@code null}; it is handed out when the next reference frame starts.
     */
    private Frame backward;

    /**
     * The frame whose first field was decoded and whose second field is awaited, or {@code null}.
     */
    private Frame open;

    /**
     * The parity of the first field of {@link #open}: 0 top, 1 bottom.
     */
    private int openParity;

    /**
     * Whether the first field of a frame was skipped, so that its second field is skipped too.
     */
    private boolean openSkipped;

    /**
     * The parity of the skipped first field.
     */
    private int skippedParity;

    /**
     * {@code closed_gop} of the last group of pictures header.
     */
    private boolean closedGop;

    /**
     * {@code broken_link} of the last group of pictures header.
     */
    private boolean brokenLink;

    /**
     * The number of reference frames started since the last group of pictures header.
     */
    private int gopReferences;

    /**
     * The presentation timestamps of the frames started and not yet handed out, ascending.
     */
    private final long[] pending = new long[MAX_PENDING];

    /**
     * The number of entries of {@link #pending}.
     */
    private int pendingCount;

    /**
     * The timestamp of the last frame handed out, or {@link Packet#NO_TIMESTAMP}.
     */
    private long lastPts = Packet.NO_TIMESTAMP;

    /**
     * The timestamp of the packet being decoded, or {@link Packet#NO_TIMESTAMP}.
     */
    private long packetPts = Packet.NO_TIMESTAMP;

    /**
     * Whether a frame of the packet being decoded took its timestamp.
     */
    private boolean packetPtsUsed;

    /**
     * The description of the first damage met in the packet being decoded, or {@code null}.
     */
    private String damage;

    /**
     * The recycled output picture.
     */
    private YuvPicture target;

    /**
     * The closed captions of the picture being decoded.
     */
    private final ByteArrayOutputStream captions = new ByteArrayOutputStream();

    /**
     * The slice start code positions of the picture being decoded.
     */
    private int[] sliceStarts = new int[64];

    /**
     * The end of each slice of the picture being decoded.
     */
    private int[] sliceEnds = new int[64];

    /**
     * The macroblock row of each slice of the picture being decoded.
     */
    private int[] sliceRows = new int[64];

    /**
     * The number of slices of the picture being decoded.
     */
    private int sliceCount;

    /**
     * The data of the picture being decoded.
     */
    private byte[] sliceData;

    /**
     * The number of bands the rows of the picture being decoded are split into.
     */
    private int bandCount;

    /**
     * Whether a band of the picture being decoded met an error.
     */
    private boolean[] bandFaults = new boolean[1];

    /**
     * The task decoding one band of rows, {@link #decodeBand(int)}.
     */
    private final ParallelBands.Band bandTask = this::decodeBand;

    /**
     * The reader of picture and group of pictures headers.
     */
    private final MsbBitReader headerBits = new MsbBitReader(new byte[0]);

    /**
     * The side data of frames without captions, by scan (progressive, top field first, bottom field first) and
     * repeated field count.
     */
    private final FrameSideData[] sideData = new FrameSideData[15];

    /**
     * Constructs an engine.
     *
     * @param limits the resource bounds checked against every picture size
     * @param threads the most threads to use, the caller included; 1 decodes on the calling thread
     * @param timeBase the time base of the timestamps
     * @param trackFrameRate the frame rate the container declares, or {@code null}
     * @param output the receiver of the frames
     */
    public Mpeg2DecoderEngine(MediaLimits limits, int threads, Rational timeBase, Rational trackFrameRate,
                              Output output) {
        this.limits = limits;
        this.threads = Math.max(1, threads);
        this.timeBase = timeBase;
        this.trackFrameRate = trackFrameRate;
        this.output = output;
    }

    /**
     * Reads the sequence header of a track's configuration.
     *
     * @param config the configuration bytes
     * @throws MediaException.Malformed if the configuration holds no valid sequence header
     * @throws MediaException.Unsupported if the sequence uses scalability
     * @throws MediaException.LimitExceeded if the picture size exceeds the limits
     */
    public void configure(byte[] config) {
        var header = MpegVideoHeaders.find(config);
        if (header == null) {
            throw new MediaException.Malformed("MPEG video configuration holds no valid sequence header");
        }
        startSequence(header);
    }

    /**
     * Decodes the pictures of one packet.
     *
     * @param data the packet data
     * @param pts the packet's presentation timestamp, or {@link Packet#NO_TIMESTAMP}
     * @throws MediaException.Malformed if a header is corrupt or a picture was damaged, after the frames due were
     * handed out
     * @throws MediaException.Unsupported if a sequence header declares scalability
     * @throws MediaException.LimitExceeded if a picture size exceeds the limits
     */
    public void decode(byte[] data, long pts) {
        packetPts = pts;
        packetPtsUsed = false;
        damage = null;
        var end = data.length;
        var position = NalUnits.findStartCode(data, 0, end);
        while (position >= 0 && position + 3 < end) {
            var code = data[position + 3] & 0xFF;
            int next;
            switch (code) {
                case MpegVideoHeaders.SEQUENCE_HEADER -> {
                    var header = MpegVideoHeaders.parse(data, position, end);
                    if (header == null) {
                        damage("corrupt MPEG video sequence header");
                        next = NalUnits.findStartCode(data, position + 4, end);
                    } else {
                        startSequence(header);
                        next = header.end() >= end ? -1 : header.end();
                    }
                }
                case MpegVideoHeaders.GROUP -> {
                    group(data, position, end);
                    next = NalUnits.findStartCode(data, position + 4, end);
                }
                case MpegVideoHeaders.PICTURE_START -> next = picture(data, position, end);
                case MpegVideoHeaders.SEQUENCE_END -> {
                    endSequence();
                    next = NalUnits.findStartCode(data, position + 4, end);
                }
                default -> next = NalUnits.findStartCode(data, position + 4, end);
            }
            position = next;
        }
        if (damage != null) {
            var message = damage;
            damage = null;
            throw new MediaException.Malformed(message);
        }
    }

    /**
     * Hands out the frames still held: an unpaired first field and the last reference frame. The references are
     * dropped, so that the next packet decodes as after a seek.
     */
    public void flush() {
        finishOpen();
        emitHeld();
        forward = null;
        backward = null;
        openSkipped = false;
        pendingCount = 0;
        lastPts = Packet.NO_TIMESTAMP;
        gopReferences = 2;
    }

    /**
     * Releases the frames.
     */
    public void close() {
        Arrays.fill(pool, null);
        forward = null;
        backward = null;
        open = null;
        target = null;
    }

    /**
     * Records damage, reported once the packet is decoded.
     *
     * @param message the description
     */
    private void damage(String message) {
        if (damage == null) {
            damage = message;
        }
    }

    /**
     * Makes a sequence header the one in force: resets the quantizer matrices and, when the coded size or chroma
     * format changes, hands out the frames held and drops the references.
     *
     * @param header the sequence header
     */
    private void startSequence(MpegVideoHeaders.Sequence header) {
        if (header.scalableMode() >= 0) {
            throw new MediaException.Unsupported("mpeg-2 scalability",
                    "MPEG-2 scalable extensions (data partitioning, SNR, spatial and temporal scalability) are not "
                            + "decoded");
        }
        LimitChecks.checkPicture(limits, header.width(), header.height());
        var columns = (header.width() + 15) >> 4;
        var rows = header.mpeg2() && !header.progressive() ? ((header.height() + 31) >> 5) << 1
                : (header.height() + 15) >> 4;
        if (sequence == null || columns != mbWidth || rows != mbHeight
                || header.chromaFormat() != sequence.chromaFormat()) {
            if (sequence != null) {
                finishOpen();
                emitHeld();
            }
            forward = null;
            backward = null;
            openSkipped = false;
            LimitChecks.checkAllocation(limits, 3L * columns * rows * 256 * (header.chromaFormat() == 3 ? 3 : 2));
        }
        sequence = header;
        mbWidth = columns;
        mbHeight = rows;
        color = header.color();
        intraMatrix = header.intraWeights();
        nonIntraMatrix = header.nonIntraWeights();
        chromaIntraMatrix = intraMatrix.clone();
        chromaNonIntraMatrix = nonIntraMatrix.clone();
        var rate = header.frameRate();
        if (rate == null) {
            rate = trackFrameRate;
        }
        frameDuration = 0;
        if (rate != null && rate.numerator() > 0) {
            try {
                frameDuration = Math.max(0, rate.invert().rescale(1, timeBase));
            } catch (ArithmeticException | IllegalArgumentException exception) {
                frameDuration = 0;
            }
        }
    }

    /**
     * Ends the sequence: hands out the frames held and drops the references.
     */
    private void endSequence() {
        finishOpen();
        emitHeld();
        forward = null;
        backward = null;
        openSkipped = false;
    }

    /**
     * Reads a group of pictures header (ISO/IEC 13818-2 6.2.2.6).
     *
     * @param data the bytes
     * @param position the index of the start code prefix
     * @param end the end of the data
     */
    private void group(byte[] data, int position, int end) {
        var bits = headerBits;
        bits.reset(data, position + 4, Math.min(end, position + 8) - position - 4);
        bits.skip(25);
        var closed = bits.readFlag();
        var broken = bits.readFlag();
        if (bits.overrun()) {
            damage("corrupt MPEG video group of pictures header");
            return;
        }
        closedGop = closed;
        brokenLink = broken;
        gopReferences = 0;
    }

    /**
     * Decodes one picture: its header and extensions, then its slices.
     *
     * @param data the bytes
     * @param position the index of the picture start code prefix
     * @param end the end of the data
     * @return the index of the first start code after the picture's slices, or -1 when the data ends
     */
    private int picture(byte[] data, int position, int end) {
        var next = NalUnits.findStartCode(data, position + 4, end);
        var headerEnd = next < 0 ? end : next;
        if (sequence == null) {
            return skipSlices(data, next, end);
        }
        var picture = this.picture;
        var bits = headerBits;
        bits.reset(data, position + 4, headerEnd - position - 4);
        bits.skip(10);
        var type = bits.read(3);
        bits.skip(16);
        var mpeg2 = sequence.mpeg2();
        if (type == Picture.P || type == Picture.B) {
            picture.fullPel[0] = bits.readFlag();
            picture.fCode[0][0] = picture.fCode[0][1] = bits.read(3);
        }
        if (type == Picture.B) {
            picture.fullPel[1] = bits.readFlag();
            picture.fCode[1][0] = picture.fCode[1][1] = bits.read(3);
        }
        while (bits.readBit() == 1 && !bits.overrun()) {
            bits.skip(8);
        }
        if (bits.overrun() || type < Picture.I || type > Picture.D || mpeg2 && type == Picture.D) {
            damage("corrupt MPEG video picture header");
            return skipSlices(data, next, end);
        }
        picture.mpeg2 = mpeg2;
        picture.codingType = type;
        picture.structure = Picture.FRAME;
        picture.intraDcPrecision = 0;
        picture.topFieldFirst = false;
        picture.framePredFrameDct = true;
        picture.concealmentVectors = false;
        picture.qScaleType = false;
        picture.intraVlcFormat = false;
        picture.alternateScan = false;
        picture.repeatFirstField = false;
        picture.progressiveFrame = true;
        captions.reset();
        var coded = false;
        var cursor = next;
        while (cursor >= 0 && cursor + 3 < end) {
            var code = data[cursor + 3] & 0xFF;
            if (code != MpegVideoHeaders.EXTENSION && code != MpegVideoHeaders.USER_DATA) {
                break;
            }
            var following = NalUnits.findStartCode(data, cursor + 4, end);
            var unitEnd = following < 0 ? end : following;
            if (code == MpegVideoHeaders.USER_DATA) {
                CcData.ga94(data, cursor + 4, unitEnd, captions);
            } else if (unitEnd > cursor + 4) {
                var id = (data[cursor + 4] & 0xFF) >> 4;
                if (id == PICTURE_CODING_EXTENSION && mpeg2) {
                    coded = codingExtension(data, cursor + 4, unitEnd);
                    if (!coded) {
                        damage("corrupt MPEG-2 picture coding extension");
                        return skipSlices(data, following, end);
                    }
                } else if (id == QUANT_MATRIX_EXTENSION && mpeg2) {
                    if (!quantMatrixExtension(data, cursor + 4, unitEnd)) {
                        damage("corrupt MPEG-2 quant matrix extension");
                    }
                } else if ((id == PICTURE_SPATIAL_EXTENSION || id == PICTURE_TEMPORAL_EXTENSION) && mpeg2) {
                    // only a scalable sequence, which the sequence header already rejected, may carry them
                    damage("MPEG-2 picture scalable extension in a sequence without scalability");
                }
            }
            cursor = following;
        }
        if (mpeg2 && !coded) {
            damage("MPEG-2 picture without a picture coding extension");
            return skipSlices(data, cursor, end);
        }
        picture.chromaFormat = sequence.chromaFormat();
        picture.blockCount = picture.chromaFormat == 3 ? 12 : picture.chromaFormat == 2 ? 8 : 6;
        picture.mbWidth = mbWidth;
        picture.mbRows = picture.structure == Picture.FRAME ? mbHeight : mbHeight >> 1;
        picture.verticalSize = sequence.height();
        picture.setWeights(intraMatrix, nonIntraMatrix, chromaIntraMatrix, chromaNonIntraMatrix);
        var after = collectSlices(data, cursor, end);
        if (!begin()) {
            return after;
        }
        decodeSlices(data);
        finish();
        return after;
    }

    /**
     * Records the slices that follow a picture header in {@link #sliceStarts}, {@link #sliceEnds} and
     * {@link #sliceRows}, and their number in {@link #sliceCount}.
     *
     * @param data the bytes
     * @param cursor the index of the first start code after the headers, or -1
     * @param end the end of the data
     * @return the index of the first start code after the slices, or -1 when the data ends
     */
    private int collectSlices(byte[] data, int cursor, int end) {
        var count = 0;
        while (cursor >= 0 && cursor + 3 < end) {
            var code = data[cursor + 3] & 0xFF;
            if (code < 1 || code > MpegVideoHeaders.SLICE_LAST) {
                break;
            }
            var following = NalUnits.findStartCode(data, cursor + 4, end);
            if (count == sliceStarts.length) {
                sliceStarts = Arrays.copyOf(sliceStarts, count * 2);
                sliceEnds = Arrays.copyOf(sliceEnds, count * 2);
                sliceRows = Arrays.copyOf(sliceRows, count * 2);
            }
            var row = code - 1;
            if (picture.mpeg2 && picture.verticalSize > 2800 && cursor + 4 < end) {
                row += ((data[cursor + 4] & 0xFF) >> 5) << 7;
            }
            sliceStarts[count] = cursor;
            sliceEnds[count] = following < 0 ? end : following;
            sliceRows[count] = row;
            count++;
            cursor = following;
        }
        sliceCount = count;
        return cursor >= 0 && cursor + 3 < end ? cursor : -1;
    }

    /**
     * Skips the slices of a picture that is not decoded.
     *
     * @param data the bytes
     * @param cursor the index of the first start code after the picture header, or -1
     * @param end the end of the data
     * @return the index of the first start code after the slices, or -1
     */
    private int skipSlices(byte[] data, int cursor, int end) {
        while (cursor >= 0 && cursor + 3 < end) {
            var code = data[cursor + 3] & 0xFF;
            if (code == MpegVideoHeaders.PICTURE_START || code > MpegVideoHeaders.SLICE_LAST) {
                return cursor;
            }
            cursor = NalUnits.findStartCode(data, cursor + 4, end);
        }
        return -1;
    }

    /**
     * Reads a picture coding extension (ISO/IEC 13818-2 6.2.3.1).
     *
     * @param data the bytes
     * @param offset the index of the byte holding the extension identifier
     * @param end the end of the extension
     * @return {@code false} when it is truncated or names a reserved picture structure
     */
    private boolean codingExtension(byte[] data, int offset, int end) {
        var bits = new MsbBitReader(data, offset, end - offset);
        bits.skip(4);
        var picture = this.picture;
        picture.fCode[0][0] = bits.read(4);
        picture.fCode[0][1] = bits.read(4);
        picture.fCode[1][0] = bits.read(4);
        picture.fCode[1][1] = bits.read(4);
        picture.fullPel[0] = false;
        picture.fullPel[1] = false;
        picture.intraDcPrecision = bits.read(2);
        picture.structure = bits.read(2);
        picture.topFieldFirst = bits.readFlag();
        picture.framePredFrameDct = bits.readFlag();
        picture.concealmentVectors = bits.readFlag();
        picture.qScaleType = bits.readFlag();
        picture.intraVlcFormat = bits.readFlag();
        picture.alternateScan = bits.readFlag();
        picture.repeatFirstField = bits.readFlag();
        bits.skip(1);
        picture.progressiveFrame = bits.readFlag();
        return !bits.overrun() && picture.structure != 0;
    }

    /**
     * Reads a quant matrix extension (ISO/IEC 13818-2 6.2.3.2) into the matrices in force.
     *
     * @param data the bytes
     * @param offset the index of the byte holding the extension identifier
     * @param end the end of the extension
     * @return {@code false} when it is truncated or has a zero weight, in which case the matrices are unchanged
     */
    private boolean quantMatrixExtension(byte[] data, int offset, int end) {
        var bits = new MsbBitReader(data, offset, end - offset);
        bits.skip(4);
        var loaded = new int[4][];
        for (var matrix = 0; matrix < 4; matrix++) {
            if (bits.readFlag()) {
                var weights = new int[64];
                for (var index = 0; index < 64; index++) {
                    weights[Picture.ZIGZAG[index]] = bits.read(8);
                }
                for (var weight : weights) {
                    if (weight == 0) {
                        return false;
                    }
                }
                loaded[matrix] = weights;
            }
        }
        if (bits.overrun()) {
            return false;
        }
        if (loaded[0] != null) {
            intraMatrix = loaded[0];
            chromaIntraMatrix = loaded[0].clone();
        }
        if (loaded[1] != null) {
            nonIntraMatrix = loaded[1];
            chromaNonIntraMatrix = loaded[1].clone();
        }
        if (loaded[2] != null) {
            chromaIntraMatrix = loaded[2];
        }
        if (loaded[3] != null) {
            chromaNonIntraMatrix = loaded[3];
        }
        return true;
    }

    /**
     * Assigns the picture its frame and references, starting a new frame, pairing a second field with its first or
     * deciding to skip the picture.
     *
     * @return {@code false} when the picture is skipped
     */
    private boolean begin() {
        var picture = this.picture;
        var type = picture.codingType;
        var field = picture.structure != Picture.FRAME;
        var parity = picture.parity();
        var reference = type == Picture.I || type == Picture.P;
        var skippedFirst = openSkipped;
        openSkipped = false;
        if (field && skippedFirst && parity == skippedParity) {
            return false;
        }
        if (field && open != null && parity != openParity
                && reference == (open.codingType == Picture.I || open.codingType == Picture.P)) {
            picture.secondField = true;
            picture.current = open;
            picture.forward = forward;
            picture.backward = type == Picture.B ? backward : open;
            appendCaptions(open);
            return true;
        }
        finishOpen();
        picture.secondField = false;
        Frame frame;
        if (reference) {
            if (type == Picture.P && backward == null) {
                return skip(field, parity);
            }
            emitHeld();
            forward = backward;
            frame = newFrame();
            backward = frame;
            gopReferences++;
            picture.forward = forward;
            picture.backward = type == Picture.P ? frame : null;
        } else if (type == Picture.B) {
            var leading = gopReferences <= 1;
            if (backward == null || leading && brokenLink || forward == null && !(leading && closedGop)) {
                return skip(field, parity);
            }
            frame = newFrame();
            picture.forward = forward;
            picture.backward = backward;
        } else {
            frame = newFrame();
            picture.forward = backward;
            picture.backward = null;
        }
        frame.codingType = type;
        frame.structure = picture.structure;
        frame.fields = field ? 1 : 0;
        frame.progressive = !picture.mpeg2 || picture.progressiveFrame && !field;
        frame.topFieldFirst = field ? parity == 0 : picture.topFieldFirst;
        frame.repeatFirstField = picture.repeatFirstField && !field;
        frame.damaged = false;
        frame.emitted = false;
        frame.captions = null;
        appendCaptions(frame);
        picture.current = frame;
        if (!packetPtsUsed && packetPts != Packet.NO_TIMESTAMP) {
            addPending(packetPts);
            packetPtsUsed = true;
        }
        if (field) {
            open = frame;
            openParity = parity;
        }
        return true;
    }

    /**
     * Skips a picture whose references are missing, and the second field of its frame.
     *
     * @param field whether the picture is a field picture
     * @param parity its parity
     * @return {@code false}
     */
    private boolean skip(boolean field, int parity) {
        if (field) {
            openSkipped = true;
            skippedParity = 1 - parity;
        }
        return false;
    }

    /**
     * Adds the captions of the picture being decoded to a frame.
     *
     * @param frame the frame the picture belongs to
     */
    private void appendCaptions(Frame frame) {
        if (captions.size() == 0) {
            return;
        }
        var bytes = captions.toByteArray();
        if (frame.captions == null) {
            frame.captions = bytes;
        } else {
            var joined = Arrays.copyOf(frame.captions, frame.captions.length + bytes.length);
            System.arraycopy(bytes, 0, joined, frame.captions.length, bytes.length);
            frame.captions = joined;
        }
    }

    /**
     * Returns a recycled frame of the sequence's size that holds no reference and no awaited first field.
     *
     * @return the frame
     */
    private Frame newFrame() {
        var format = sequence.chromaFormat();
        for (var index = 0; index < pool.length; index++) {
            var frame = pool[index];
            if (frame != null && !frame.fits(mbWidth, mbHeight, format)) {
                pool[index] = null;
                frame = null;
            }
            if (frame == null) {
                frame = new Frame(mbWidth, mbHeight, format);
                pool[index] = frame;
                return frame;
            }
            if (frame != forward && frame != backward && frame != open) {
                return frame;
            }
        }
        throw new IllegalStateException("no free frame");
    }

    /**
     * Decodes the collected slices of the picture into its frame, then conceals the macroblocks no slice
     * decoded.
     *
     * @param data the bytes
     */
    private void decodeSlices(byte[] data) {
        var picture = this.picture;
        var count = sliceCount;
        var frame = picture.current;
        var macroblocks = picture.mbCount();
        frame.startPicture(macroblocks);
        var ordered = picture.mpeg2;
        for (var index = 1; index < count && ordered; index++) {
            ordered = sliceRows[index] >= sliceRows[index - 1];
        }
        var rows = picture.mbRows;
        var bands = ordered && threads > 1 && count > 1
                ? Math.min(threads, ParallelBands.bands(rows, (long) macroblocks * 384)) : 1;
        if (decoders.length < bands) {
            var grown = Arrays.copyOf(decoders, bands);
            for (var index = decoders.length; index < bands; index++) {
                grown[index] = new SliceDecoder();
            }
            decoders = grown;
            bandFaults = new boolean[bands];
        }
        for (var band = 0; band < bands; band++) {
            decoders[band].missingReference = false;
            bandFaults[band] = false;
        }
        sliceData = data;
        bandCount = bands;
        try {
            if (bands <= 1) {
                decodeBand(0);
            } else {
                ParallelBands.run(bands, bandTask);
            }
        } finally {
            sliceData = null;
        }
        var damaged = false;
        for (var band = 0; band < bands; band++) {
            damaged |= bandFaults[band] || decoders[band].missingReference;
        }
        var concealer = decoders[0];
        for (var address = 0; address < macroblocks; address++) {
            if (!frame.decoded[address]) {
                concealer.conceal(picture, address);
                damaged = true;
            }
        }
        if (damaged) {
            frame.damaged = true;
            damage("corrupt MPEG video picture data");
        }
    }

    /**
     * Decodes the slices of one band of macroblock rows of the picture; with a single band, every slice in stream
     * order.
     *
     * @param band the band index
     */
    private void decodeBand(int band) {
        var picture = this.picture;
        var decoder = decoders[band];
        var data = sliceData;
        var rows = picture.mbRows;
        var total = bandCount;
        var firstRow = ParallelBands.start(band, total, rows);
        var lastRow = ParallelBands.start(band + 1, total, rows);
        for (var index = 0; index < sliceCount; index++) {
            var row = sliceRows[index];
            if (total == 1 || row >= firstRow && (row < lastRow || band == total - 1 && row >= rows)) {
                if (!decoder.decode(picture, data, sliceStarts[index], sliceEnds[index])) {
                    bandFaults[band] = true;
                }
            }
        }
    }

    /**
     * Completes the picture: a frame picture or a second field completes its frame.
     */
    private void finish() {
        var picture = this.picture;
        if (picture.structure == Picture.FRAME) {
            complete(picture.current);
        } else if (picture.secondField) {
            var frame = picture.current;
            frame.fields = 2;
            open = null;
            complete(frame);
        }
    }

    /**
     * Completes a frame whose second field never came, filling the missing field from the decoded one.
     */
    private void finishOpen() {
        if (open == null) {
            return;
        }
        var frame = open;
        open = null;
        frame.doubleField(openParity == 1);
        complete(frame);
    }

    /**
     * Hands a complete frame out, or holds it when it is a reference frame of a stream with B-pictures.
     *
     * @param frame the frame whose last picture was decoded
     */
    private void complete(Frame frame) {
        var reference = frame.codingType == Picture.I || frame.codingType == Picture.P;
        if (!reference || sequence.lowDelay()) {
            emit(frame);
        }
    }

    /**
     * Hands out the newer reference frame if it is complete and was not handed out yet.
     */
    private void emitHeld() {
        if (backward != null && !backward.emitted && backward != open) {
            emit(backward);
        }
    }

    /**
     * Inserts a timestamp into {@link #pending}, dropping the smallest when it is full.
     *
     * @param pts the timestamp
     */
    private void addPending(long pts) {
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
     * Hands one frame to the output.
     *
     * @param frame the frame to show next in display order
     */
    private void emit(Frame frame) {
        frame.emitted = true;
        var mpeg2 = sequence.mpeg2();
        var repeated = 0;
        FieldOrder order = null;
        if (mpeg2) {
            if (sequence.progressive()) {
                order = FieldOrder.PROGRESSIVE;
                repeated = frame.repeatFirstField ? frame.topFieldFirst ? 4 : 2 : 0;
            } else if (frame.progressive && !frame.repeatFirstField) {
                order = FieldOrder.PROGRESSIVE;
            } else {
                order = frame.topFieldFirst ? FieldOrder.TOP_FIRST : FieldOrder.BOTTOM_FIRST;
                repeated = frame.repeatFirstField ? 1 : 0;
            }
        }
        var duration = frameDuration > 0 ? frameDuration * (2 + repeated) / 2 : Packet.UNKNOWN_DURATION;
        long pts;
        if (pendingCount > 0) {
            pts = pending[0];
            System.arraycopy(pending, 1, pending, 0, --pendingCount);
        } else {
            pts = lastPts == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP : lastPts + Math.max(1, frameDuration);
        }
        if (lastPts != Packet.NO_TIMESTAMP && pts != Packet.NO_TIMESTAMP && pts <= lastPts) {
            pts = lastPts + Math.max(1, frameDuration);
        }
        if (pts != Packet.NO_TIMESTAMP) {
            lastPts = pts;
        }
        FrameSideData side;
        if (order == null && frame.captions == null) {
            side = FrameSideData.NONE;
        } else if (frame.captions == null) {
            var index = order.ordinal() * 5 + repeated;
            side = sideData[index];
            if (side == null) {
                side = FrameSideData.builder().fieldOrder(order).repeatedFields(repeated).build();
                sideData[index] = side;
            }
        } else {
            var builder = FrameSideData.builder().repeatedFields(repeated).closedCaptions(frame.captions);
            if (order != null) {
                builder.fieldOrder(order);
            }
            side = builder.build();
        }
        var keyframe = frame.codingType == Picture.I || frame.codingType == Picture.D;
        output.emit(render(frame), pts, duration, keyframe, side);
    }

    /**
     * Copies the displayed area of a frame into the recycled output picture.
     *
     * @param frame the decoded frame, its planes padded to whole macroblocks
     * @return the output picture
     */
    private YuvPicture render(Frame frame) {
        var width = sequence.width();
        var height = sequence.height();
        var format = sequence.chroma();
        var picture = target;
        if (picture == null || picture.width() != width || picture.height() != height
                || picture.chromaFormat() != format || !picture.color().equals(color)) {
            picture = YuvPicture.allocate(width, height, format, false, color);
            target = picture;
        }
        Planes.copy(frame.y, 0, frame.width, picture.y(), 0, picture.yStride(), width, height);
        Planes.copy(frame.u, 0, frame.chromaWidth, picture.u(), 0, picture.uvStride(), picture.chromaWidth(),
                picture.chromaHeight());
        Planes.copy(frame.v, 0, frame.chromaWidth, picture.v(), 0, picture.uvStride(), picture.chromaWidth(),
                picture.chromaHeight());
        return picture;
    }
}

package com.github.auties00.smalt.video.mpeg4.internal.encoder;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.mpeg4.internal.Frame;
import com.github.auties00.smalt.video.mpeg4.internal.Tables;
import com.github.auties00.smalt.video.mpeg4.internal.Vop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Codes a sequence of pictures as an MPEG-4 Visual elementary stream of one rectangular video object layer
 * (ISO/IEC 14496-2): the visual object sequence, visual object and video object layer headers, then I-, P- and
 * B-VOPs in decoding order.
 *
 * <p>Pictures arrive in presentation order. With B-VOPs, up to {@link Settings#bFrames()} pictures wait for the next
 * reference; a key frame closes the group, so a stream can be decoded from every I-VOP. The VOP times count the
 * {@link Settings#timeResolution()} ticks from the first picture. Instances are not thread-safe.
 */
public final class Mpeg4EncoderCore {
    /**
     * The settings of a stream.
     *
     * @param width the picture width, from 1 to 8191
     * @param height the picture height, from 1 to 8191
     * @param advancedSimple whether the stream is of the Advanced Simple profile rather than the Simple profile
     * @param bFrames the most B-VOPs between references, 0 for none
     * @param quarterSample whether vectors are in quarter samples
     * @param mpegQuantization whether the MPEG quantization method is used
     * @param intraMatrix the intra matrix in raster order, or {@code null} for the default
     * @param interMatrix the non-intra matrix in raster order, or {@code null} for the default
     * @param fourVectors whether macroblocks may have four vectors
     * @param acPrediction whether intra macroblocks may use AC prediction
     * @param packetBytes the size after which a video packet ends, or 0 for one packet per VOP
     * @param dataPartitioned whether the macroblock data of I- and P-VOPs is data partitioned
     * @param reversibleVlc whether data partitioned texture uses the reversible codes
     * @param searchRange the largest whole-sample vector component searched
     * @param threads the most threads of the analysis
     * @param bitrate the target bitrate in bits per second
     * @param frameRate the nominal frame rate
     * @param fixedQuant the fixed quantizer, or 0 for rate control
     * @param minQuant the smallest quantizer
     * @param maxQuant the largest quantizer
     * @param keyframeInterval the most pictures from one I-VOP to the next, or 0 for none but the first
     * @param timeBase the unit of the timestamps
     * @param timeResolution the {@code vop_time_increment_resolution}, from 1 to 65535
     * @param color the color description the stream signals
     */
    public record Settings(int width, int height, boolean advancedSimple, int bFrames, boolean quarterSample,
                           boolean mpegQuantization, int[] intraMatrix, int[] interMatrix, boolean fourVectors,
                           boolean acPrediction, int packetBytes, boolean dataPartitioned, boolean reversibleVlc,
                           int searchRange, int threads, long bitrate,
                           Rational frameRate, int fixedQuant, int minQuant, int maxQuant, int keyframeInterval,
                           Rational timeBase, int timeResolution, ColorInfo color) {
    }

    /**
     * A picture waiting to be coded.
     *
     * @param source the source planes
     * @param pts the presentation timestamp
     * @param duration the duration in time base units
     * @param ticks the VOP time in time increments
     */
    private record Pending(Frame source, long pts, long duration, long ticks) {
    }

    /**
     * The flags of a key frame packet.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

    /**
     * The flags of a B-VOP packet, which no other picture references.
     */
    private static final Set<PacketFlag> BIDIRECTIONAL = Set.of(PacketFlag.DISCARDABLE);

    /**
     * The flags of a P-VOP packet.
     */
    private static final Set<PacketFlag> PREDICTED = Set.of();

    private final Settings settings;
    private final PictureCoder coder;
    private final SourceFrames sources;
    private final RateControl rateControl;
    private final BitWriter writer = new BitWriter();
    private final byte[] headers;
    private final int timeIncrementBits;
    private final Rational ticksUnit;

    /**
     * The reconstructions: the older and the newer reference, and spares.
     */
    private Frame olderReference;
    private Frame newerReference;
    private final ArrayDeque<Frame> spareFrames = new ArrayDeque<>();

    /**
     * The VOP times of the references.
     */
    private long olderTicks;
    private long newerTicks;

    /**
     * The pictures waiting to be coded as B-VOPs, in presentation order.
     */
    private final List<Pending> pending = new ArrayList<>();

    /**
     * The presentation timestamps of the pictures accepted and not yet emitted, in presentation order: the
     * decoding timestamps of the packets to come.
     */
    private final ArrayDeque<Long> decodeTimes = new ArrayDeque<>();

    /**
     * The decoding delay of the stream in time base units.
     */
    private final long delay;

    private long firstPts = Long.MIN_VALUE;
    private long lastTicks = -1;
    private int framesSinceKey;
    private int lastQuant;
    private int rounding;
    private boolean headersWritten;
    private long timeBaseSeconds;
    private long previousTimeBaseSeconds;
    private Frame lastReconstruction;

    /**
     * Constructs the core of a stream.
     *
     * @param settings the picture size, tools and rate control of the stream
     */
    public Mpeg4EncoderCore(Settings settings) {
        this.settings = settings;
        var intra = settings.intraMatrix() != null ? settings.intraMatrix() : Tables.DEFAULT_INTRA_MATRIX;
        var inter = settings.interMatrix() != null ? settings.interMatrix() : Tables.DEFAULT_INTER_MATRIX;
        var tools = new PictureCoder.Tools(false, false, true, false, settings.fourVectors(), false, false, false,
                false, 0, settings.quarterSample(), settings.mpegQuantization(), intra.clone(), inter.clone(),
                settings.acPrediction(), settings.packetBytes() * 8, settings.dataPartitioned(),
                settings.reversibleVlc(), settings.searchRange(), settings.threads());
        this.coder = new PictureCoder(settings.width(), settings.height(), tools);
        this.sources = new SourceFrames(settings.width(), settings.height());
        this.rateControl = new RateControl(settings.bitrate(), settings.frameRate().toDouble(), settings.fixedQuant(),
                settings.minQuant(), settings.maxQuant(), settings.keyframeInterval(), settings.bFrames());
        this.timeIncrementBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(settings.timeResolution() - 1));
        this.ticksUnit = new Rational(1, settings.timeResolution());
        var nominal = Math.max(1, Math.round(1.0 / (settings.frameRate().toDouble()
                * settings.timeBase().toDouble())));
        this.delay = settings.bFrames() > 0 ? nominal : 0;
        writeSequenceHeaders(writer);
        this.headers = writer.toByteArray();
        writer.reset();
    }

    /**
     * {@return the visual object sequence, visual object and video object layer headers of the stream}
     */
    public byte[] headers() {
        return headers.clone();
    }

    /**
     * {@return the reconstruction of the picture coded last, as a decoder of the stream produces it, or
     * {@code null} before the first}
     */
    public Frame lastReconstruction() {
        return lastReconstruction;
    }

    /**
     * {@return the quantizer of the picture coded last}
     */
    public int lastQuantizer() {
        return lastQuant;
    }

    /**
     * Changes the target bitrate from the next picture on.
     *
     * @param bitrate the new target in bits per second
     */
    public void retarget(long bitrate) {
        rateControl.retarget(bitrate);
    }

    /**
     * Accepts one picture and emits the packets that became ready.
     *
     * @param picture the picture, 8-bit 4:2:0 of the stream's size
     * @param pts the presentation timestamp, larger than the previous one
     * @param duration the duration, positive
     * @param key whether the picture must be an I-VOP
     * @param output receives the packets in decoding order
     */
    public void encode(YuvPicture picture, long pts, long duration, boolean key, Consumer<Packet> output) {
        if (firstPts == Long.MIN_VALUE) {
            firstPts = pts;
        }
        var ticks = Math.max(lastTicks + 1, settings.timeBase().rescale(pts - firstPts, ticksUnit));
        lastTicks = ticks;
        decodeTimes.add(pts);
        var source = sources.copy(picture);
        var frame = new Pending(source, pts, duration, ticks);
        var interval = settings.keyframeInterval();
        var intra = framesSinceKey == 0 || key || interval > 0 && framesSinceKey >= interval;
        framesSinceKey = intra ? 1 : framesSinceKey + 1;
        if (intra) {
            flushPending(output);
            code(Vop.I, frame, output);
            return;
        }
        if (pending.size() < settings.bFrames()) {
            pending.add(frame);
            return;
        }
        code(Vop.P, frame, output);
        codeBidirectional(output);
    }

    /**
     * Codes the pictures still waiting: the last as a P-VOP, the others as B-VOPs before it.
     *
     * @param output receives the packets
     */
    public void flush(Consumer<Packet> output) {
        flushPending(output);
    }

    /**
     * Codes the waiting pictures, the last as a P-VOP reference.
     *
     * @param output receives the packets
     */
    private void flushPending(Consumer<Packet> output) {
        if (pending.isEmpty()) {
            return;
        }
        var last = pending.removeLast();
        code(Vop.P, last, output);
        codeBidirectional(output);
    }

    /**
     * Codes the waiting pictures as B-VOPs between the two references.
     *
     * @param output receives the packets
     */
    private void codeBidirectional(Consumer<Packet> output) {
        for (var frame : pending) {
            code(Vop.B, frame, output);
        }
        pending.clear();
    }

    /**
     * Codes one picture and emits its packet.
     *
     * @param type the VOP coding type
     * @param frame the picture
     * @param output receives the packet
     */
    private void code(int type, Pending frame, Consumer<Packet> output) {
        var rcType = type == Vop.I ? RateControl.INTRA : type == Vop.P ? RateControl.PREDICTED
                : RateControl.BIDIRECTIONAL;
        var quant = rateControl.quantizer(rcType, coder.mbWidth() * coder.mbHeight());
        lastQuant = quant;
        Frame target;
        if (type == Vop.B) {
            target = spare();
            var trd = newerTicks - olderTicks;
            var trb = frame.ticks() - olderTicks;
            coder.beginPicture(type, frame.source(), target, olderReference, newerReference, quant, 0,
                    Math.min(Math.max(1, trb), Math.max(1, trd)), Math.max(1, trd));
        } else {
            target = spare();
            if (type == Vop.P) {
                rounding ^= 1;
            }
            coder.beginPicture(type, frame.source(), target, newerReference, null, quant, rounding, 0, 0);
        }
        target.time = frame.ticks();
        coder.analyze();
        writer.reset();
        if (!headersWritten) {
            writeSequenceHeaders(writer);
            headersWritten = true;
        }
        var start = writer.position();
        writeVopHeader(writer, type, frame.ticks(), quant);
        coder.code(writer);
        coder.finish();
        writer.stuffing();
        rateControl.update(rcType, quant, writer.position() - start);
        sources.release(frame.source());
        lastReconstruction = target;
        if (type == Vop.B) {
            spareFrames.push(target);
        } else {
            if (olderReference != null) {
                spareFrames.push(olderReference);
            }
            olderReference = newerReference;
            olderTicks = newerTicks;
            newerReference = target;
            newerTicks = frame.ticks();
        }
        var dts = decodeTimes.poll() - delay;
        var flags = type == Vop.I ? KEY : type == Vop.B ? BIDIRECTIONAL : PREDICTED;
        output.accept(new Packet(0, writer.toByteArray(), frame.pts(), Math.min(dts, frame.pts()), frame.duration(),
                flags, PacketSideData.NONE));
    }

    /**
     * Returns a spare reconstruction frame.
     *
     * @return the frame
     */
    private Frame spare() {
        var frame = spareFrames.poll();
        return frame != null ? frame : sources.acquire();
    }

    /**
     * Writes a VOP header (6.2.5).
     *
     * @param out the stream
     * @param type the coding type
     * @param ticks the VOP time
     * @param quant the quantizer
     */
    private void writeVopHeader(BitWriter out, int type, long ticks, int quant) {
        out.write(0x1B6, 32);
        out.write(type, 2);
        var resolution = settings.timeResolution();
        var seconds = ticks / resolution;
        long modulo;
        if (type == Vop.B) {
            modulo = seconds - previousTimeBaseSeconds;
        } else {
            modulo = seconds - timeBaseSeconds;
            previousTimeBaseSeconds = timeBaseSeconds;
            timeBaseSeconds = seconds;
        }
        for (var i = 0; i < modulo; i++) {
            out.bit(1);
        }
        out.bit(0);
        out.bit(1);
        out.write((int) (ticks % resolution), timeIncrementBits);
        out.bit(1);
        out.bit(1);
        if (type == Vop.P) {
            out.bit(rounding);
        }
        out.write(0, 3);
        out.write(quant, 5);
        if (type != Vop.I) {
            out.write(coder.forwardFcode(), 3);
        }
        if (type == Vop.B) {
            out.write(coder.backwardFcode(), 3);
        }
    }

    /**
     * Writes the visual object sequence, visual object and video object layer headers (6.2.2 and 6.2.3).
     *
     * @param out the stream
     */
    private void writeSequenceHeaders(BitWriter out) {
        out.write(0x1B0, 32);
        out.write(profileAndLevel(), 8);
        out.write(0x1B5, 32);
        out.bit(0);
        out.write(1, 4);
        out.bit(1);
        out.write(5, 3);
        var color = settings.color();
        out.flag(color.range() == ColorRange.FULL);
        out.bit(1);
        out.write(color.primaries().code(), 8);
        out.write(color.transfer().code(), 8);
        out.write(color.matrix().code(), 8);
        out.stuffing();
        out.write(0x100, 32);
        out.write(0x120, 32);
        out.bit(0);
        out.write(settings.advancedSimple() ? 17 : 1, 8);
        var verid = settings.quarterSample() ? 2 : 1;
        out.bit(1);
        out.write(verid, 4);
        out.write(1, 3);
        out.write(1, 4);
        out.bit(1);
        out.write(1, 2);
        out.flag(settings.bFrames() == 0);
        out.bit(0);
        out.write(0, 2);
        out.bit(1);
        out.write(settings.timeResolution(), 16);
        out.bit(1);
        out.bit(0);
        out.bit(1);
        out.write(settings.width(), 13);
        out.bit(1);
        out.write(settings.height(), 13);
        out.bit(1);
        out.bit(0);
        out.bit(1);
        out.write(0, verid == 1 ? 1 : 2);
        out.bit(0);
        out.flag(settings.mpegQuantization());
        if (settings.mpegQuantization()) {
            writeMatrix(out, settings.intraMatrix());
            writeMatrix(out, settings.interMatrix());
        }
        if (verid != 1) {
            out.flag(settings.quarterSample());
        }
        out.bit(1);
        out.flag(settings.packetBytes() == 0 && !settings.dataPartitioned());
        out.flag(settings.dataPartitioned());
        if (settings.dataPartitioned()) {
            out.flag(settings.reversibleVlc());
        }
        if (verid != 1) {
            out.bit(0);
            out.bit(0);
        }
        out.bit(0);
        out.stuffing();
    }

    /**
     * Writes a {@code load_*_quant_mat} flag and, for a downloaded matrix, its 64 values in zigzag order.
     *
     * @param out the stream
     * @param matrix the matrix in raster order, or {@code null} for the default
     */
    private static void writeMatrix(BitWriter out, int[] matrix) {
        out.flag(matrix != null);
        if (matrix == null) {
            return;
        }
        for (var i = 0; i < 64; i++) {
            out.write(matrix[Tables.ZIGZAG[i]], 8);
        }
    }

    /**
     * Returns the {@code profile_and_level_indication} of the stream: the lowest level of its profile (Tables N-1
     * and N-2 of ISO/IEC 14496-2) whose picture size, macroblock rate and bitrate hold the stream's, or the highest
     * level.
     *
     * @return the indication
     */
    private int profileAndLevel() {
        var macroblocks = (long) ((settings.width() + 15) >> 4) * ((settings.height() + 15) >> 4);
        var rate = macroblocks * settings.frameRate().toDouble();
        var bitrate = settings.fixedQuant() > 0 ? 0 : settings.bitrate();
        // {code, macroblocks per VOP, macroblocks per second, kilobits per second}
        long[][] levels = settings.advancedSimple()
                ? new long[][]{{0xF0, 99, 2970, 128}, {0xF1, 99, 2970, 128}, {0xF2, 396, 5940, 384},
                {0xF3, 396, 11880, 768}, {0xF7, 396, 11880, 1500}, {0xF4, 792, 23760, 3000},
                {0xF5, 1620, 48600, 8000}}
                : new long[][]{{0x08, 99, 1485, 64}, {0x01, 99, 1485, 64}, {0x02, 396, 5940, 128},
                {0x03, 396, 11880, 384}, {0x04, 1200, 36000, 4000}, {0x05, 1620, 40500, 8000},
                {0x06, 3600, 108000, 12000}};
        for (var level : levels) {
            if (macroblocks <= level[1] && rate <= level[2] && bitrate <= level[3] * 1000) {
                return (int) level[0];
            }
        }
        return (int) levels[levels.length - 1][0];
    }
}

package com.github.auties00.smalt.audio.aac.internal.usac;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Writes synthetic USAC streams of one single channel or channel pair element, frequency domain and linear prediction
 * domain frames in a given order, with the time-warped MDCT or the plain filterbank, for the tests of the transitions
 * between the two coders. Every parameter the bitstream carries is drawn from a seeded generator, so that two streams of the same
 * plan and seed differ only in the time warping data: {@code tw_data_present} is 0 in every frequency domain frame
 * unless {@link Plan#warps} is set. The arithmetic coder mirrors {@link ArithmeticDecoder} (and the encoder of the
 * ISO/IEC 23003-3 reference software, {@code usac_arith_dec.c}).
 */
public final class UsacTestStream {
    /**
     * A frame of a plan.
     *
     * @param lpd whether the frame is coded in the linear prediction domain
     * @param sequence the coded window sequence of a frequency domain frame
     * @param lpdMode the {@code lpd_mode} of a linear prediction domain frame, whose divisions are ACELP or TCX-256
     */
    public record Frame(boolean lpd, int sequence, int lpdMode) {
        static Frame fd(int sequence) {
            return new Frame(false, sequence, 0);
        }

        static Frame lpd(int... modes) {
            var mode = 0;
            for (var i = 0; i < 4; i++) {
                mode |= modes[i] << i;
            }
            return new Frame(true, 0, mode);
        }
    }

    /**
     * The content of a stream.
     *
     * @param frameLength the core frame length, 768 or 1024
     * @param timeWarping whether the element uses the time-warped MDCT
     * @param warps whether frequency domain frames carry random warp contours
     * @param bassPostFilter whether linear prediction domain frames enable the bass postfilter
     * @param seed the seed of every drawn parameter
     * @param frames the frames
     * @param warpRatios the {@code tw_ratio} of all the nodes of each frame, or -1 for a frame without warp data, drawn
     * from no generator so that it changes nothing else in the stream; {@code null} to go by {@code warps}
     */
    public record Plan(int frameLength, boolean timeWarping, boolean warps, boolean bassPostFilter, long seed,
                       List<Frame> frames, int[] warpRatios) {
        /**
         * Constructs a plan whose warp data follows {@code warps}.
         */
        public Plan(int frameLength, boolean timeWarping, boolean warps, boolean bassPostFilter, long seed,
                    List<Frame> frames) {
            this(frameLength, timeWarping, warps, bassPostFilter, seed, frames, null);
        }
    }

    /**
     * A frame of a channel pair element.
     *
     * @param left the frame of the first channel
     * @param right the frame of the second channel
     * @param commonWindow {@code common_window}, read when both channels are frequency domain frames, whose window
     * sequences must then map alike
     * @param commonTw {@code common_tw}, read when both channels are frequency domain frames
     */
    public record PairFrame(Frame left, Frame right, boolean commonWindow, boolean commonTw) {
    }

    /**
     * Pair frames that switch each channel between the coders on its own and both together, with every combination of
     * a shared and a separate window and warp in the frames where both channels are frequency domain frames.
     */
    public static final List<PairFrame> PAIR_TRANSITIONS = List.of(
            new PairFrame(Frame.fd(FdChannel.ONLY_LONG), Frame.fd(FdChannel.ONLY_LONG), true, true),
            new PairFrame(Frame.fd(FdChannel.ONLY_LONG), Frame.fd(FdChannel.LONG_START), false, false),
            new PairFrame(Frame.fd(FdChannel.LONG_START), Frame.lpd(0, 0, 0, 0), false, false),
            new PairFrame(Frame.lpd(0, 1, 0, 1), Frame.fd(FdChannel.LONG_STOP), false, false),
            new PairFrame(Frame.lpd(1, 1, 1, 0), Frame.fd(FdChannel.LONG_START), false, false),
            new PairFrame(Frame.lpd(1, 0, 1, 0), Frame.lpd(1, 1, 1, 0), false, false),
            new PairFrame(Frame.fd(FdChannel.LONG_STOP), Frame.lpd(0, 1, 1, 0), false, false),
            new PairFrame(Frame.fd(FdChannel.LONG_START), Frame.fd(FdChannel.EIGHT_SHORT), false, false),
            new PairFrame(Frame.fd(FdChannel.EIGHT_SHORT), Frame.fd(FdChannel.EIGHT_SHORT), true, true),
            new PairFrame(Frame.lpd(0, 0, 1, 1), Frame.lpd(1, 1, 0, 0), false, false),
            new PairFrame(Frame.fd(FdChannel.EIGHT_SHORT), Frame.fd(FdChannel.LONG_STOP), false, false),
            new PairFrame(Frame.fd(FdChannel.LONG_STOP), Frame.fd(FdChannel.ONLY_LONG), false, true),
            new PairFrame(Frame.fd(FdChannel.ONLY_LONG), Frame.fd(FdChannel.LONG_START), false, false),
            new PairFrame(Frame.fd(FdChannel.ONLY_LONG), Frame.fd(FdChannel.EIGHT_SHORT), false, false),
            new PairFrame(Frame.fd(FdChannel.LONG_START), Frame.fd(FdChannel.LONG_STOP), false, false),
            new PairFrame(Frame.lpd(0, 1, 0, 1), Frame.fd(FdChannel.ONLY_LONG), false, false),
            new PairFrame(Frame.lpd(1, 0, 0, 1), Frame.fd(FdChannel.ONLY_LONG), false, false),
            new PairFrame(Frame.fd(FdChannel.LONG_STOP), Frame.fd(FdChannel.ONLY_LONG), false, false),
            new PairFrame(Frame.fd(FdChannel.ONLY_LONG), Frame.fd(FdChannel.ONLY_LONG), true, true),
            new PairFrame(Frame.fd(FdChannel.LONG_START), Frame.fd(FdChannel.LONG_START), true, false),
            new PairFrame(Frame.lpd(0, 0, 0, 0), Frame.lpd(0, 0, 0, 0), false, false),
            new PairFrame(Frame.fd(FdChannel.LONG_STOP), Frame.fd(FdChannel.LONG_STOP), true, true),
            new PairFrame(Frame.fd(FdChannel.ONLY_LONG), Frame.fd(FdChannel.ONLY_LONG), true, false),
            new PairFrame(Frame.fd(FdChannel.ONLY_LONG), Frame.fd(FdChannel.ONLY_LONG), false, true));

    /**
     * Frames that make every frequency domain to linear prediction domain transition (after long, start, short and
     * stop-start windows, into ACELP and into TCX) and back (from ACELP with FAC and from TCX without, into stop,
     * stop-start and short windows), and ACELP to TCX switches inside and between linear prediction domain frames.
     */
    public static final List<Frame> TRANSITIONS = List.of(
            Frame.fd(FdChannel.ONLY_LONG),
            Frame.fd(FdChannel.ONLY_LONG),
            Frame.fd(FdChannel.LONG_START),
            Frame.lpd(0, 0, 0, 0),
            Frame.lpd(0, 1, 0, 1),
            Frame.lpd(1, 0, 1, 0),
            Frame.fd(FdChannel.LONG_STOP),
            Frame.fd(FdChannel.LONG_START),
            Frame.lpd(1, 1, 1, 1),
            Frame.fd(FdChannel.EIGHT_SHORT),
            Frame.lpd(0, 1, 1, 0),
            Frame.fd(FdChannel.LONG_START),
            Frame.fd(FdChannel.EIGHT_SHORT),
            Frame.lpd(1, 0, 0, 1),
            Frame.lpd(1, 1, 1, 0),
            Frame.fd(FdChannel.EIGHT_SHORT),
            Frame.fd(FdChannel.EIGHT_SHORT),
            Frame.lpd(0, 0, 1, 1),
            Frame.fd(FdChannel.LONG_START),
            Frame.lpd(0, 1, 1, 1),
            Frame.fd(FdChannel.LONG_STOP),
            Frame.fd(FdChannel.ONLY_LONG),
            Frame.fd(FdChannel.ONLY_LONG));

    /**
     * The sampling frequency of the streams, a rate the linear prediction domain coder is used at.
     */
    public static final int SAMPLING_FREQUENCY = 16000;

    private UsacTestStream() {
    }

    /**
     * {@return the {@code AudioSpecificConfig} of a stream: USAC, mono, without SBR}
     */
    public static byte[] audioSpecificConfig(Plan plan) {
        var bits = new Bits();
        bits.write(31, 5);
        bits.write(42 - 32, 6);
        bits.write(8, 4);
        bits.write(1, 4);
        bits.write(8, 5);
        bits.write(plan.frameLength() == 768 ? 0 : 1, 3);
        bits.write(1, 5);
        bits.write(0, 4);
        bits.write(UsacConfig.SCE, 2);
        bits.flag(plan.timeWarping());
        bits.flag(false);
        bits.flag(false);
        return bits.toByteArray();
    }

    /**
     * {@return the access units of a stream, the first one independent}
     */
    public static List<byte[]> accessUnits(Plan plan) {
        var n = plan.frameLength();
        var random = new Random(plan.seed());
        var coder = new ArithmeticEncoder();
        var fd = new FdChannel(n, com.github.auties00.smalt.audio.aac.internal.SamplingTables.tableIndexFor(
                SAMPLING_FREQUENCY * (n == 768 ? 4 : 3) / 3));
        var units = new ArrayList<byte[]>();
        var lastLpd = false;
        var lastSequence = FdChannel.ONLY_LONG;
        var lastMode = -1;
        for (var f = 0; f < plan.frames().size(); f++) {
            var frame = plan.frames().get(f);
            var independent = f == 0;
            var bits = new Bits();
            bits.flag(independent);
            bits.flag(frame.lpd());
            if (frame.lpd()) {
                var previousMode = lastLpd ? lastMode : lastSequence == FdChannel.EIGHT_SHORT ? -2 : -1;
                lastMode = writeLpd(bits, plan, frame, random, coder, independent, lastLpd, previousMode,
                        lastSequence == FdChannel.EIGHT_SHORT);
                lastLpd = true;
                lastSequence = FdChannel.EIGHT_SHORT;
            } else {
                var facPresent = lastLpd && lastMode == 0;
                bits.flag(false);
                lastSequence = writeFdStream(bits, plan, f, frame, random, coder, fd, independent, lastSequence,
                        facPresent, false, false);
                lastLpd = false;
            }
            units.add(bits.toByteArray());
        }
        return units;
    }

    /**
     * {@return the {@code AudioSpecificConfig} of a stereo stream of one channel pair element, without SBR}
     */
    public static byte[] audioSpecificConfigOfPair(Plan plan) {
        var bits = new Bits();
        bits.write(31, 5);
        bits.write(42 - 32, 6);
        bits.write(8, 4);
        bits.write(2, 4);
        bits.write(8, 5);
        bits.write(plan.frameLength() == 768 ? 0 : 1, 3);
        bits.write(2, 5);
        bits.write(0, 4);
        bits.write(UsacConfig.CPE, 2);
        bits.flag(plan.timeWarping());
        bits.flag(false);
        bits.flag(false);
        return bits.toByteArray();
    }

    /**
     * {@return the access units of a stereo stream, the first one independent}
     *
     * @param plan the content of the stream, whose {@code frames} are ignored
     * @param frames the frames of the channel pair element
     */
    public static List<byte[]> accessUnits(Plan plan, List<PairFrame> frames) {
        var n = plan.frameLength();
        var random = new Random(plan.seed());
        var coders = new ArithmeticEncoder[]{new ArithmeticEncoder(), new ArithmeticEncoder()};
        var fd = new FdChannel(n, com.github.auties00.smalt.audio.aac.internal.SamplingTables.tableIndexFor(
                SAMPLING_FREQUENCY * (n == 768 ? 4 : 3) / 3));
        var units = new ArrayList<byte[]>();
        var lastLpd = new boolean[2];
        var lastSequence = new int[]{FdChannel.ONLY_LONG, FdChannel.ONLY_LONG};
        var lastMode = new int[]{-1, -1};
        for (var f = 0; f < frames.size(); f++) {
            var frame = frames.get(f);
            var channels = new Frame[]{frame.left(), frame.right()};
            var independent = f == 0;
            var bits = new Bits();
            bits.flag(independent);
            bits.flag(frame.left().lpd());
            bits.flag(frame.right().lpd());
            var bothFd = !frame.left().lpd() && !frame.right().lpd();
            if (bothFd) {
                bits.flag(false);
                bits.flag(frame.commonWindow());
                if (frame.commonWindow()) {
                    icsInfo(bits, frame.left().sequence(), fd, lastSequence[0]);
                    bits.flag(true);
                    bits.write(f % 2 == 0 ? 0 : 2, 2);
                }
                if (plan.timeWarping()) {
                    bits.flag(frame.commonTw());
                    if (frame.commonTw()) {
                        timeWarping(bits, plan, f, random);
                    }
                }
            }
            var firstSequence = lastSequence[0];
            for (var c = 0; c < 2; c++) {
                if (channels[c].lpd()) {
                    var previousMode = lastLpd[c] ? lastMode[c] : lastSequence[c] == FdChannel.EIGHT_SHORT ? -2 : -1;
                    lastMode[c] = writeLpd(bits, plan, channels[c], random, coders[c], independent, lastLpd[c],
                            previousMode, lastSequence[c] == FdChannel.EIGHT_SHORT);
                    lastSequence[c] = FdChannel.EIGHT_SHORT;
                } else {
                    if (!bothFd) {
                        bits.flag(false);
                    }
                    var common = bothFd && frame.commonWindow();
                    lastSequence[c] = writeFdStream(bits, plan, f, channels[c], random, coders[c], fd, independent,
                            common ? firstSequence : lastSequence[c], lastLpd[c] && lastMode[c] == 0, common,
                            bothFd && frame.commonTw());
                }
                lastLpd[c] = channels[c].lpd();
            }
            units.add(bits.toByteArray());
        }
        return units;
    }

    /**
     * Writes {@code ics_info()} and leaves the window sequence it maps to in {@code fd}.
     */
    private static void icsInfo(Bits bits, int coded, FdChannel fd, int lastSequence) {
        bits.write(coded, 2);
        bits.write(0, 1);
        fd.windowSequence = FdChannel.mapSequence(coded, lastSequence);
        if (coded == FdChannel.EIGHT_SHORT) {
            bits.write(maxSfb(fd), 4);
            bits.write(0, 7);
        } else {
            bits.write(maxSfb(fd), 6);
        }
    }

    private static int maxSfb(FdChannel fd) {
        return Math.min(fd.bandCount(), fd.windowSequence == FdChannel.EIGHT_SHORT ? 10 : 36);
    }

    /**
     * Writes {@code tw_data()} of a frame.
     */
    private static void timeWarping(Bits bits, Plan plan, int frame, Random random) {
        if (plan.warpRatios() != null) {
            var ratio = plan.warpRatios()[frame];
            bits.flag(ratio >= 0);
            if (ratio >= 0) {
                for (var i = 0; i < 16; i++) {
                    bits.write(ratio, 3);
                }
            }
            return;
        }
        var present = plan.warps() && random.nextInt(4) != 0;
        bits.flag(present);
        if (present) {
            for (var i = 0; i < 16; i++) {
                bits.write(random.nextInt(8), 3);
            }
        }
    }

    /**
     * Writes {@code fd_channel_stream()}.
     *
     * @param commonWindow whether {@code ics_info()} was written before the stream
     * @param commonTw whether {@code tw_data()} was written before the stream
     * @return the window sequence the frame maps to
     */
    private static int writeFdStream(Bits bits, Plan plan, int index, Frame frame, Random random,
                                     ArithmeticEncoder coder, FdChannel fd, boolean independent, int lastSequence,
                                     boolean facPresent, boolean commonWindow, boolean commonTw) {
        var n = plan.frameLength();
        bits.write(120 + random.nextInt(8), 8);
        var coded = frame.sequence();
        if (commonWindow) {
            fd.windowSequence = FdChannel.mapSequence(coded, lastSequence);
        } else {
            icsInfo(bits, coded, fd, lastSequence);
        }
        var shortWindows = coded == FdChannel.EIGHT_SHORT;
        var maxSfb = maxSfb(fd);
        if (plan.timeWarping() && !commonTw) {
            timeWarping(bits, plan, index, random);
        }
        var groups = shortWindows ? 8 : 1;
        for (var g = 0; g < groups; g++) {
            for (var b = 0; b < maxSfb; b++) {
                if (g != 0 || b != 0) {
                    bits.write(0, 1);
                }
            }
        }
        var window = shortWindows ? n / 8 : n;
        var windows = shortWindows ? 8 : 1;
        var reset = independent || random.nextInt(4) == 0;
        if (!independent) {
            bits.flag(reset);
        }
        coder.prepare(window, reset);
        var lines = fd.offsets()[maxSfb];
        var quantized = new int[window];
        for (var w = 0; w < windows; w++) {
            spectrum(random, quantized, lines);
            coder.encode(bits, quantized, lines / 2, window / 2);
        }
        bits.flag(facPresent);
        if (facPresent) {
            fac(bits, random, shortWindows ? n / 16 : n / 8);
        }
        return fd.windowSequence;
    }

    private static int writeLpd(Bits bits, Plan plan, Frame frame, Random random, ArithmeticEncoder coder,
                                boolean independent, boolean lastLpd, int previousMode, boolean afterShort) {
        var n = plan.frameLength();
        var division = n / 4;
        var subframes = n / 256;
        var mod = new int[4];
        for (var i = 0; i < 4; i++) {
            mod[i] = (frame.lpdMode() >> i) & 1;
        }
        var coreMode = random.nextInt(6);
        bits.write(coreMode, 3);
        bits.write(frame.lpdMode(), 5);
        bits.flag(plan.bassPostFilter());
        bits.flag(lastLpd);
        var facPresent = lastLpd ? (previousMode == 0) != (mod[0] == 0) : mod[0] == 0;
        bits.flag(facPresent);
        var lastMode = previousMode;
        var firstTcx = true;
        for (var k = 0; k < 4; k++) {
            var readFac = k == 0 ? lastLpd && facPresent : (lastMode == 0 && mod[k] > 0) || (lastMode > 0 && mod[k] == 0);
            if (readFac) {
                for (var i = 0; i < division / 2; i += 8) {
                    avq(bits, random);
                }
            }
            if (mod[k] == 0) {
                bits.write(random.nextInt(4), 2);
                for (var sfr = 0; sfr < subframes; sfr++) {
                    if (sfr == 0 || (subframes == 4 && sfr == 2)) {
                        bits.write(random.nextInt(256), 9);
                    } else {
                        bits.write(random.nextInt(64), 6);
                    }
                    bits.write(random.nextInt(2), 1);
                    if (coreMode == 5) {
                        for (var i = 0; i < 4; i++) {
                            bits.write(random.nextInt(4), 2);
                        }
                        for (var i = 0; i < 4; i++) {
                            bits.write(random.nextInt(1 << 14), 14);
                        }
                    } else {
                        for (var width : ICB_BITS[coreMode]) {
                            bits.write(random.nextInt(1 << width), width);
                        }
                    }
                    bits.write(40 + random.nextInt(48), 7);
                }
            } else {
                bits.write(random.nextInt(8), 3);
                bits.write(60 + random.nextInt(30), 7);
                var reset = false;
                if (firstTcx) {
                    reset = independent || random.nextInt(4) == 0;
                    if (!independent) {
                        bits.flag(reset);
                    }
                }
                coder.prepare(division, firstTcx && reset);
                var quantized = new int[division];
                spectrum(random, quantized, division);
                coder.encode(bits, quantized, division / 2, division / 2);
                firstTcx = false;
            }
            lastMode = mod[k];
        }
        lpc(bits, random, !lastLpd, mod);
        if (!lastLpd && facPresent) {
            bits.flag(afterShort);
            bits.write(random.nextInt(128), 7);
            for (var i = 0; i < (afterShort ? n / 16 : n / 8); i += 8) {
                avq(bits, random);
            }
        }
        return mod[3];
    }

    private static final int[][] ICB_BITS = {
            {5, 5, 5, 5}, {9, 9, 5, 5}, {9, 9, 9, 9}, {13, 13, 9, 9}, {13, 13, 13, 13}, {16, 16, 16, 16}
    };

    /**
     * Writes the LPC filters with absolute first stage indices and second stage codebooks 2 or 3.
     */
    private static void lpc(Bits bits, Random random, boolean first, int[] mod) {
        bits.write(random.nextInt(256), 8);
        lpcCodebooks(bits, random);
        if (first) {
            bits.write(0, 1);
            bits.write(random.nextInt(256), 8);
            lpcCodebooks(bits, random);
        }
        if (mod[0] < 3) {
            bits.write(0, 1);
            bits.write(random.nextInt(256), 8);
            lpcCodebooks(bits, random);
        }
        if (mod[0] < 2) {
            bits.write(0b10, 2);
            bits.write(random.nextInt(256), 8);
            lpcCodebooks(bits, random);
        }
        if (mod[2] < 2) {
            bits.write(0b10, 2);
            bits.write(random.nextInt(256), 8);
            lpcCodebooks(bits, random);
        }
    }

    private static void lpcCodebooks(Bits bits, Random random) {
        var qn0 = 2 + random.nextInt(2);
        var qn1 = 2 + random.nextInt(2);
        bits.write(qn0 - 2, 2);
        bits.write(qn1 - 2, 2);
        bits.write(random.nextInt(1 << (4 * qn0)), 4 * qn0);
        bits.write(random.nextInt(1 << (4 * qn1)), 4 * qn1);
    }

    /**
     * Writes one point of the algebraic vector quantizer in its unary form: codebook 0, 2 or 3.
     */
    private static void avq(Bits bits, Random random) {
        switch (random.nextInt(3)) {
            case 0 -> bits.write(0, 1);
            case 1 -> {
                bits.write(0b10, 2);
                bits.write(random.nextInt(256), 8);
            }
            default -> {
                bits.write(0b110, 3);
                bits.write(random.nextInt(1 << 12), 12);
            }
        }
    }

    private static void fac(Bits bits, Random random, int lfac) {
        bits.write(random.nextInt(128), 7);
        for (var i = 0; i < lfac; i += 8) {
            avq(bits, random);
        }
    }

    /**
     * Draws quantized lines: mostly small values, a few that need escapes, zeros past {@code lines}.
     */
    private static void spectrum(Random random, int[] out, int lines) {
        Arrays.fill(out, 0);
        for (var i = 0; i < lines; i++) {
            var magnitude = random.nextInt(16) == 0 ? random.nextInt(40) : random.nextInt(4);
            out[i] = random.nextBoolean() ? magnitude : -magnitude;
        }
    }

    /**
     * Writes bits most significant first.
     */
    static final class Bits {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int current;
        private int count;

        void write(int value, int width) {
            for (var i = width - 1; i >= 0; i--) {
                bit((value >>> i) & 1);
            }
        }

        void flag(boolean value) {
            bit(value ? 1 : 0);
        }

        void bit(int bit) {
            current = current << 1 | bit;
            if (++count == 8) {
                bytes.write(current);
                current = 0;
                count = 0;
            }
        }

        byte[] toByteArray() {
            var out = new ByteArrayOutputStream();
            out.writeBytes(bytes.toByteArray());
            if (count > 0) {
                out.write(current << (8 - count));
            }
            return out.toByteArray();
        }
    }

    /**
     * Encodes the 2-tuples of USAC spectra with the context of {@link ArithmeticDecoder}.
     */
    static final class ArithmeticEncoder {
        private static final int Q4 = (1 << 16) - 1;
        private static final int Q1 = Q4 / 4 + 1;
        private static final int Q2 = 2 * Q1;
        private static final int Q3 = 3 * Q1;
        private static final int CAPACITY = 1024 / 2 + 4;

        private final int[] context = new int[CAPACITY];
        private final int[] previous = new int[CAPACITY];
        private final int[] scratch = new int[CAPACITY];
        private final int[] scratchPrevious = new int[CAPACITY];
        private int previousSize;
        private int low;
        private int high;
        private int pending;

        void prepare(int size, boolean reset) {
            if (reset) {
                Arrays.fill(context, 0);
                Arrays.fill(previous, 0);
            } else {
                var from = previousSize;
                var count = from / 2 + 4;
                System.arraycopy(context, 0, scratch, 0, count);
                System.arraycopy(previous, 0, scratchPrevious, 0, count);
                var ratio = (float) from / (float) size;
                for (var i = 0; i < size / 2; i++) {
                    var k = (int) ((float) i * ratio);
                    context[2 + i] = scratch[2 + k];
                    previous[2 + i] = scratchPrevious[2 + k];
                }
                context[size / 2 + 2] = scratch[from / 2 + 2];
                previous[size / 2 + 2] = scratchPrevious[from / 2 + 2];
                context[size / 2 + 3] = scratch[from / 2 + 3];
                previous[size / 2 + 3] = scratchPrevious[from / 2 + 3];
            }
            previousSize = size;
        }

        /**
         * Codes the first {@code count} 2-tuples of {@code lines}; the rest up to {@code total} must be zero.
         */
        void encode(Bits bits, int[] lines, int count, int total) {
            for (var i = 0; i < total; i++) {
                previous[i + 2] = context[i + 2];
                context[i + 2] = 1;
            }
            low = 0;
            high = Q4;
            pending = 0;
            var state = previous[2] << 12;
            for (var i = 0; i < count; i++) {
                var q = i + 2;
                state = state >> 4;
                state += previous[q + 1] << 12;
                state = (state & 0xFFF0) + context[q - 1];
                var s = state;
                if (i > 3 && context[q - 1] + context[q - 2] + context[q - 3] < 5) {
                    s += 0x10000;
                }
                var a = Math.abs(lines[2 * i]);
                var b = Math.abs(lines[2 * i + 1]);
                var a1 = a;
                var b1 = b;
                var lsbs = new int[24];
                var lev = 0;
                var escapes = 0;
                while (a1 > 3 || b1 > 3) {
                    symbol(bits, ESCAPE, ArithmeticTables.MODELS[model(s + (escapes << 17))]);
                    lsbs[lev++] = (a1 & 1) | ((b1 & 1) << 1);
                    a1 >>= 1;
                    b1 >>= 1;
                    escapes = Math.min(lev, 7);
                }
                symbol(bits, a1 + 4 * b1, ArithmeticTables.MODELS[model(s + (escapes << 17))]);
                var a2 = a1;
                var b2 = b1;
                for (var l = lev - 1; l >= 0; l--) {
                    var model = a2 == 0 ? 1 : b2 == 0 ? 0 : 2;
                    symbol(bits, lsbs[l], ArithmeticTables.LSB_MODELS[model]);
                    a2 = a2 << 1 | lsbs[l] & 1;
                    b2 = b2 << 1 | lsbs[l] >> 1 & 1;
                }
                context[q] = a == 0 && b == 0 ? 1 : Math.min(a + b + 1, 0xF);
            }
            pending++;
            emit(bits, low < Q1 ? 0 : 1);
            for (var i = 0; i < 2 * count; i++) {
                if (lines[i] != 0) {
                    bits.bit(lines[i] > 0 ? 1 : 0);
                }
            }
        }

        private static final int ESCAPE = 16;

        private void symbol(Bits bits, int symbol, int[] frequencies) {
            var range = high - low + 1;
            if (symbol > 0) {
                high = low + (range * frequencies[symbol - 1] >> 14) - 1;
            }
            low += range * frequencies[symbol] >> 14;
            while (true) {
                if (high < Q2) {
                    emit(bits, 0);
                } else if (low >= Q2) {
                    emit(bits, 1);
                    low -= Q2;
                    high -= Q2;
                } else if (low >= Q1 && high < Q3) {
                    pending++;
                    low -= Q1;
                    high -= Q1;
                } else {
                    break;
                }
                low += low;
                high += high + 1;
            }
        }

        private void emit(Bits bits, int bit) {
            bits.bit(bit);
            while (pending > 0) {
                bits.bit(1 - bit);
                pending--;
            }
        }

        private static int model(int s) {
            var min = -1;
            var max = ArithmeticTables.HASH_KEYS.length - 1;
            while (max - min > 1) {
                var i = min + (max - min) / 2;
                var key = ArithmeticTables.HASH_KEYS[i];
                if (s < key) {
                    max = i;
                } else if (s > key) {
                    min = i;
                } else {
                    return ArithmeticTables.HASH_MODELS[i];
                }
            }
            return ArithmeticTables.GAP_MODELS[max];
        }
    }
}

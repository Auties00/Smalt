package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.BitReader;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An implementation of the decoder model of the AV1 specification's Annex E (decoding schedule
 * mode, first operating point) written from the specification text alone, independently of the
 * encoder's model, with exact rational arithmetic. It checks every conformance requirement of
 * section E.6 a stream's frame headers and sizes decide, on the whole stream and on the stream
 * that starts at each key frame.
 */
public final class AnnexEChecker {
    private AnnexEChecker() {
    }

    /** An exact rational number. */
    record Q(BigInteger n, BigInteger d) implements Comparable<Q> {
        static final Q ZERO = of(0);

        static Q of(long n) {
            return new Q(BigInteger.valueOf(n), BigInteger.ONE);
        }

        static Q of(long n, long d) {
            return new Q(BigInteger.valueOf(n), BigInteger.valueOf(d)).norm();
        }

        Q norm() {
            var g = n.gcd(d);
            if (d.signum() < 0) {
                g = g.negate();
            }
            return g.signum() == 0 ? this : new Q(n.divide(g), d.divide(g));
        }

        Q add(Q o) {
            return new Q(n.multiply(o.d).add(o.n.multiply(d)), d.multiply(o.d)).norm();
        }

        Q sub(Q o) {
            return add(new Q(o.n.negate(), o.d));
        }

        Q mul(Q o) {
            return new Q(n.multiply(o.n), d.multiply(o.d)).norm();
        }

        Q div(Q o) {
            return new Q(n.multiply(o.d), d.multiply(o.n)).norm();
        }

        BigInteger ceil() {
            var qr = n.divideAndRemainder(d);
            return qr[1].signum() > 0 ? qr[0].add(BigInteger.ONE) : qr[0];
        }

        static Q max(Q a, Q b) {
            return a.compareTo(b) >= 0 ? a : b;
        }

        static Q min(Q a, Q b) {
            return a.compareTo(b) <= 0 ? a : b;
        }

        @Override
        public int compareTo(Q o) {
            return n.multiply(o.d).compareTo(o.n.multiply(d));
        }

        double value() {
            return n.doubleValue() / d.doubleValue();
        }
    }

    /**
     * The decoder model parameters of a sequence (operating point 0).
     *
     * @param decCt the decoding clock tick, seconds
     * @param dispCt the display clock tick, seconds
     * @param decoderDelay {@code decoder_buffer_delay}, 1/90000 s
     * @param encoderDelay {@code encoder_buffer_delay}, 1/90000 s
     * @param level {@code seq_level_idx}
     * @param tier {@code seq_tier}
     * @param profile {@code seq_profile}
     * @param maxWidth the largest frame width
     * @param maxHeight the largest frame height
     * @param initialDisplay {@code initial_display_delay_minus_1 + 1}
     * @param lowDelay {@code low_delay_mode_flag}
     */
    public record Params(Q decCt, Q dispCt, long decoderDelay, long encoderDelay, int level, int tier, int profile,
                         long maxWidth, long maxHeight, int initialDisplay, boolean lowDelay) {
    }

    /**
     * A frame header of the stream and the bits that end with it.
     *
     * @param showExisting {@code show_existing_frame}
     * @param key whether {@code frame_type} is {@code KEY_FRAME} (of the shown frame, for a
     * show-existing header)
     * @param intra whether the frame is a key or intra-only frame
     * @param showFrame {@code show_frame}
     * @param refresh {@code refresh_frame_flags}
     * @param slot {@code frame_to_show_map_idx}
     * @param removal {@code buffer_removal_time[0]}
     * @param presentation {@code frame_presentation_time}
     * @param bits the bits of the OBUs since the previous frame header's OBU, its own included
     * @param luma {@code UpscaledWidth * FrameHeight} of the (shown) frame
     * @param unit the temporal unit the header is in
     */
    public record Frame(boolean showExisting, boolean key, boolean intra, boolean showFrame, int refresh, int slot,
                        long removal, long presentation, long bits, long luma, int unit) {
    }

    /** Level limits of Annex A: MaxDisplayRate, MaxDecodeRate, MaxHeaderRate, MainMbps*10, HighMbps*10. */
    private static long[] limits(int level) {
        return switch (level) {
            case 0 -> new long[]{4_423_680L, 5_529_600L, 150, 15, 0};
            case 1 -> new long[]{8_363_520L, 10_454_400L, 150, 30, 0};
            case 4 -> new long[]{19_975_680L, 24_969_600L, 150, 60, 0};
            case 5 -> new long[]{31_950_720L, 39_938_400L, 150, 100, 0};
            case 8 -> new long[]{70_778_880L, 77_856_768L, 300, 120, 300};
            case 9 -> new long[]{141_557_760L, 155_713_536L, 300, 200, 500};
            case 12 -> new long[]{267_386_880L, 273_715_200L, 300, 300, 1000};
            case 13 -> new long[]{534_773_760L, 547_430_400L, 300, 400, 1600};
            case 14 -> new long[]{1_069_547_520L, 1_094_860_800L, 300, 600, 2400};
            case 15 -> new long[]{1_069_547_520L, 1_176_502_272L, 300, 600, 2400};
            case 16 -> new long[]{1_069_547_520L, 1_176_502_272L, 300, 600, 2400};
            case 17 -> new long[]{2_139_095_040L, 2_189_721_600L, 300, 1000, 4800};
            case 18, 19 -> new long[]{4_278_190_080L, level == 18 ? 4_379_443_200L : 4_706_009_088L, 300, 1600, 8000};
            default -> null;
        };
    }

    /** Reads the decoder model parameters of a sequence header. */
    public static Params params(SequenceHeader seq) {
        if (!seq.decoderModelInfoPresent || !seq.decoderModelParamPresent[0]) {
            throw new IllegalArgumentException("no decoder model for operating point 0");
        }
        return new Params(Q.of(seq.numUnitsInDecodingTick, seq.timeScale), Q.of(seq.numUnitsInTick, seq.timeScale),
                seq.decoderBufferDelay[0], seq.encoderBufferDelay[0], (seq.majorLevel[0] - 2) * 4 + seq.minorLevel[0],
                seq.tier[0], seq.profile, seq.maxWidth, seq.maxHeight, seq.initialDisplayDelay[0], seq.lowDelayMode[0]);
    }

    /** One stretch of a stream under one sequence header. */
    public record Run(SequenceHeader seq, List<Frame> frames) {
    }

    /** Splits temporal units into runs of identical sequence headers and lists their frame headers. */
    public static List<Run> runs(List<byte[]> units) {
        var runs = new ArrayList<Run>();
        byte[] seqBytes = null;
        SequenceHeader seq = null;
        List<Frame> frames = null;
        var refs = new FrameHeader[8];
        long pending = 0;
        for (var u = 0; u < units.size(); u++) {
            var data = units.get(u);
            var pos = 0;
            while (pos < data.length) {
                var start = pos;
                var header = data[pos++] & 0xFF;
                var type = (header >> 3) & 15;
                var temporalId = 0;
                if ((header & 4) != 0) {
                    temporalId = (data[pos++] & 0xFF) >> 5;
                }
                long size = 0;
                var shift = 0;
                int b;
                do {
                    b = data[pos++] & 0xFF;
                    size |= (long) (b & 0x7F) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0);
                var end = pos + (int) size;
                pending += (end - start) * 8L;
                if (type == 1) {
                    var bytes = Arrays.copyOfRange(data, pos, end);
                    if (seqBytes == null || !Arrays.equals(seqBytes, bytes)) {
                        seqBytes = bytes;
                        var gb = new BitReader();
                        gb.init(data, pos, (int) size);
                        seq = new SequenceHeader();
                        if (!seq.parse(gb, true)) {
                            throw new IllegalArgumentException("sequence header");
                        }
                        frames = new ArrayList<>();
                        runs.add(new Run(seq, frames));
                    }
                } else if (type == 3 || type == 6) {
                    var gb = new BitReader();
                    gb.init(data, pos, (int) size);
                    var h = new FrameHeader();
                    h.temporalId = temporalId;
                    if (!h.parse(seq, gb, refs, false)) {
                        throw new IllegalArgumentException("frame header");
                    }
                    if (h.showExistingFrame) {
                        var shown = refs[h.existingFrameIdx];
                        frames.add(new Frame(true, shown.frameType == FrameHeader.KEY, shown.isKeyOrIntra(), true, 0,
                                h.existingFrameIdx, 0, h.framePresentationTime, pending,
                                (long) shown.upscaledWidth * shown.height, u));
                    } else {
                        frames.add(new Frame(false, h.frameType == FrameHeader.KEY, h.isKeyOrIntra(), h.showFrame,
                                h.refreshFrameFlags, 0, h.bufferRemovalTime[0], h.framePresentationTime, pending,
                                (long) h.upscaledWidth * h.height, u));
                        for (var s = 0; s < 8; s++) {
                            if ((h.refreshFrameFlags & (1 << s)) != 0) {
                                refs[s] = h;
                            }
                        }
                    }
                    pending = 0;
                } else if (type != 2 && type != 5 && type != 15) {
                    throw new IllegalArgumentException("unexpected OBU type " + type);
                }
                pos = end;
            }
        }
        return runs;
    }

    /**
     * Checks a stream: every run, and the stream that starts at each key frame of a run.
     *
     * @return the broken requirements, empty for a conformant stream
     */
    public static List<String> checkStream(List<byte[]> units) {
        var out = new ArrayList<String>();
        for (var run : runs(units)) {
            out.addAll(checkCuts(params(run.seq()), run.frames()));
        }
        return out;
    }

    /**
     * Checks a stream of frames as a whole, and the stream that starts at each of its later key
     * frames: every requirement up to the next key frame, the smoothing buffer's to the end.
     *
     * @return the broken requirements, empty for a conformant stream
     */
    public static List<String> checkCuts(Params p, List<Frame> frames) {
        var out = new ArrayList<String>();
        out.addAll(check(p, frames, false).stream().map(s -> "stream: " + s).toList());
        var starts = new ArrayList<Integer>();
        for (var i = 1; i < frames.size(); i++) {
            var f = frames.get(i);
            if (!f.showExisting() && f.key() && f.showFrame()) {
                var first = i;
                while (first > 0 && frames.get(first - 1).unit() == f.unit()) {
                    first--;
                }
                starts.add(first);
            }
        }
        for (var k = 0; k < starts.size(); k++) {
            var first = starts.get(k);
            var next = k + 1 < starts.size() ? starts.get(k + 1) : frames.size();
            var label = "from frame " + first + ": ";
            out.addAll(check(p, frames.subList(first, next), false).stream().map(s -> label + s).toList());
            out.addAll(check(p, frames.subList(first, frames.size()), true).stream()
                    .map(s -> label + "to the end: " + s).toList());
        }
        return out;
    }

    /**
     * The removal and presentation outcome of one decode process run.
     *
     * @param presented whether the buffer pool held the initial display delay's frames, so that the
     * presentation schedule started
     */
    private record Process(Q[] removal, List<String> errors, boolean presented) {
    }

    /**
     * Checks a stream of frames that starts at a key frame.
     *
     * @param bufferOnly whether to check only the requirements of the smoothing buffer and the
     * removal times: underflow, overflow, the minimum decode time and the decoder buffer delay of
     * the key frames
     * @return the broken requirements, empty for a conformant stream
     */
    public static List<String> check(Params p, List<Frame> frames, boolean bufferOnly) {
        var errors = new ArrayList<String>();
        var lim = limits(p.level());
        if (lim == null) {
            return errors;
        }
        if (frames.isEmpty() || frames.getFirst().showExisting() || !frames.getFirst().key()) {
            errors.add("does not start at a key frame");
            return errors;
        }
        var mbps10 = p.tier() == 0 ? lim[3] : lim[4];
        var factor = p.profile() == 0 ? 1 : p.profile() == 1 ? 2 : 3;
        var bitRate = Q.of(mbps10 * 100_000L * factor);
        var bufferSize = bitRate;
        var decodeRate = Q.of(lim[1]);
        var displayRate = Q.of(lim[0]);
        var headerInterval = Q.of(1, lim[2]);
        if (p.decoderDelay() == 0 || Q.of(p.decoderDelay()).compareTo(Q.of(90_000).mul(bufferSize).div(bitRate)) > 0) {
            errors.add("decoder_buffer_delay " + p.decoderDelay() + " out of range");
        }
        // decodable frame groups
        var dfgBits = new ArrayList<Long>();
        var dfgFrame = new ArrayList<Frame>();
        long acc = 0;
        for (var f : frames) {
            acc += f.bits();
            if (!f.showExisting()) {
                dfgBits.add(acc);
                dfgFrame.add(f);
                acc = 0;
            }
        }
        var n = dfgFrame.size();
        var scheduled = new Q[n];
        var ttd = new Q[n];
        var lastRap = -1;
        for (var i = 0; i < n; i++) {
            var f = dfgFrame.get(i);
            if (i == 0) {
                scheduled[i] = Q.of(p.decoderDelay(), 90_000);
            } else {
                scheduled[i] = scheduled[lastRap].add(Q.of(f.removal()).mul(p.decCt()));
            }
            if (f.key() && f.showFrame()) {
                lastRap = i;
            }
            var samples = f.intra() ? f.luma() : p.maxWidth() * p.maxHeight();
            ttd[i] = Q.of(samples).div(decodeRate);
        }
        // smoothing buffer
        var first = new Q[n];
        var last = new Q[n];
        var removal = new Q[n];
        var delays = Q.of(p.encoderDelay() + p.decoderDelay(), 90_000);
        for (var i = 0; i < n; i++) {
            first[i] = i == 0 ? Q.ZERO : Q.max(last[i - 1], scheduled[i].sub(delays));
            last[i] = first[i].add(Q.of(dfgBits.get(i)).div(bitRate));
            if (last[i].compareTo(scheduled[i]) > 0) {
                if (!p.lowDelay()) {
                    errors.add("smoothing buffer underflow at frame group " + i + " (late by "
                            + last[i].sub(scheduled[i]).value() + " s)");
                }
                var ticks = last[i].div(p.decCt()).ceil();
                removal[i] = new Q(ticks, BigInteger.ONE).mul(p.decCt());
            } else {
                removal[i] = scheduled[i];
            }
        }
        for (var i = 0; i < n; i++) {
            var fullness = Q.ZERO;
            for (var k = i; k < n && first[k].compareTo(scheduled[i]) < 0; k++) {
                var arrived = Q.min(scheduled[i], last[k]).sub(first[k]).mul(bitRate);
                fullness = fullness.add(arrived);
            }
            if (fullness.compareTo(bufferSize) > 0) {
                errors.add("smoothing buffer overflow before frame group " + i);
            }
            if (i + 1 < n && scheduled[i + 1].sub(removal[i]).compareTo(Q.max(ttd[i], headerInterval)) < 0) {
                errors.add("minimum decode time broken after frame group " + i);
            }
            var f = dfgFrame.get(i);
            if (i > 0 && f.key() && f.showFrame()) {
                var delta = scheduled[i].sub(last[i - 1]).mul(Q.of(90_000)).ceil();
                if (BigInteger.valueOf(p.decoderDelay()).compareTo(delta) > 0) {
                    errors.add("decoder buffer delay exceeds TimeDelta at key frame group " + i);
                }
            }
        }
        if (bufferOnly) {
            return errors;
        }
        // presentation offsets from InitialPresentationDelay, in decode order of the shown frames
        var offsets = new ArrayList<Q>();
        var shownLuma = new ArrayList<Long>();
        Q keyOffset = Q.ZERO;
        for (var f : frames) {
            if (f.showExisting() || f.showFrame()) {
                Q offset;
                var rap = !f.showExisting() && f.key();
                if (offsets.isEmpty()) {
                    offset = Q.ZERO;
                } else {
                    offset = keyOffset.add(Q.of(f.presentation()).mul(p.dispCt()));
                    if (!rap && offset.compareTo(offsets.getLast()) <= 0) {
                        errors.add("presentation time not increasing at shown frame " + offsets.size());
                    }
                }
                if (rap) {
                    keyOffset = offset;
                }
                offsets.add(offset);
                shownLuma.add(f.luma());
            }
        }
        var minFrameTime = decodeRate.div(Q.of(lim[2]).mul(displayRate));
        var sorted = new ArrayList<Integer>();
        for (var j = 0; j < offsets.size(); j++) {
            sorted.add(j);
        }
        sorted.sort((a, b) -> offsets.get(a).compareTo(offsets.get(b)));
        for (var k = 0; k + 1 < sorted.size(); k++) {
            var j = sorted.get(k);
            var interval = offsets.get(sorted.get(k + 1)).sub(offsets.get(j));
            if (interval.compareTo(Q.max(Q.of(shownLuma.get(j)).div(displayRate), minFrameTime)) < 0) {
                errors.add("presentation interval too short after shown frame " + j);
            }
        }
        var schedule = process(p, frames, removal, ttd, offsets, false);
        errors.addAll(schedule.errors());
        // InitialPresentationDelay stays 0 when the buffer pool never holds the initial display
        // delay's frames, and then no frame is presented and no presentation requirement applies
        if (!schedule.presented() && !offsets.isEmpty()) {
            errors.add("no frame is presented: the buffer pool never holds " + p.initialDisplay() + " frames");
        }
        var resource = process(p, frames, removal, ttd, offsets, true);
        for (var i = 0; i < n; i++) {
            if (scheduled[i].compareTo(resource.removal()[i]) < 0) {
                errors.add("removal of frame group " + i + " precedes the resource availability mode's");
                break;
            }
        }
        return errors;
    }

    /** The decode_process() of section E.5.2. */
    private static Process process(Params p, List<Frame> frames, Q[] removal, Q[] ttd, List<Q> offsets,
                                   boolean resourceMode) {
        var errors = new ArrayList<String>();
        var pool = 10;
        var decoderRef = new int[pool];
        var playerRef = new int[pool];
        var presentationTimes = new Q[pool];
        var vbi = new int[8];
        var vbiKey = new boolean[8];
        Arrays.fill(vbi, -1);
        var removals = new Q[removal.length];
        Q time = Q.ZERO;
        var dfg = -1;
        var shownNum = -1;
        Q initial = null;
        for (var f : frames) {
            if (!f.showExisting()) {
                dfg++;
            }
            var shown = f.showFrame() || f.showExisting();
            if (shown) {
                shownNum++;
            }
            int displayIdx;
            if (!f.showExisting()) {
                Q r;
                if (resourceMode) {
                    if (dfg == 0) {
                        time = Q.of(p.decoderDelay(), 90_000);
                    }
                    r = null;
                    Q bufFree = null;
                    for (var k = 0; k < pool && r == null; k++) {
                        if (decoderRef[k] == 0) {
                            if (playerRef[k] == 0) {
                                r = time;
                            } else if (bufFree == null || presentationTimes[k].compareTo(bufFree) < 0) {
                                bufFree = presentationTimes[k];
                            }
                        }
                    }
                    if (r == null) {
                        r = bufFree;
                    }
                } else {
                    r = removal[dfg];
                }
                removals[dfg] = r;
                for (var k = 0; k < pool; k++) {
                    if (playerRef[k] > 0 && presentationTimes[k].compareTo(r) <= 0) {
                        playerRef[k] = 0;
                        if (decoderRef[k] == 0) {
                            presentationTimes[k] = null;
                        }
                    }
                }
                time = r;
                if (f.showFrame() && initial != null && time.compareTo(initial.add(offsets.get(shownNum))) > 0) {
                    errors.add("DECODE_BUFFER_AVAILABLE_LATE at frame group " + dfg);
                }
                var cfbi = -1;
                for (var k = 0; k < pool && cfbi < 0; k++) {
                    if (decoderRef[k] == 0 && playerRef[k] == 0) {
                        cfbi = k;
                    }
                }
                if (cfbi < 0) {
                    errors.add("DECODE_FRAME_BUF_UNAVAILABLE at frame group " + dfg);
                    return new Process(removals, errors, initial != null);
                }
                time = time.add(ttd[dfg]);
                for (var s = 0; s < 8; s++) {
                    if ((f.refresh() & (1 << s)) != 0) {
                        if (vbi[s] != -1) {
                            decoderRef[vbi[s]]--;
                        }
                        vbi[s] = cfbi;
                        vbiKey[s] = f.key();
                        decoderRef[cfbi]++;
                    }
                }
                displayIdx = cfbi;
                if (initial == null) {
                    var inPool = 0;
                    for (var k = 0; k < pool; k++) {
                        if (decoderRef[k] != 0 || playerRef[k] != 0) {
                            inPool++;
                        }
                    }
                    if (inPool >= p.initialDisplay()) {
                        initial = time;
                    }
                }
            } else {
                displayIdx = vbi[f.slot()];
                if (displayIdx == -1) {
                    errors.add("DECODE_EXISTING_FRAME_BUF_EMPTY at shown frame " + shownNum);
                    return new Process(removals, errors, initial != null);
                }
                if (vbiKey[f.slot()]) {
                    for (var s = 0; s < 8; s++) {
                        if (vbi[s] != -1) {
                            decoderRef[vbi[s]]--;
                        }
                        vbi[s] = displayIdx;
                        vbiKey[s] = true;
                        decoderRef[displayIdx]++;
                    }
                }
            }
            if (initial != null && shown) {
                var at = initial.add(offsets.get(shownNum));
                if (time.compareTo(at) > 0) {
                    errors.add((resourceMode ? "resource mode " : "") + "DISPLAY_FRAME_LATE at shown frame "
                            + shownNum + " (late by " + time.sub(at).value() + " s)");
                }
                presentationTimes[displayIdx] = at;
                playerRef[displayIdx]++;
            }
        }
        return new Process(removals, errors, initial != null);
    }
}

package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.internal.H264Nal;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Simulates the coded picture buffer of the hypothetical reference decoder a stream signals (ITU-T H.264
 * C.1), writes the buffering period and picture timing SEI messages of each access unit and sizes the filler
 * data that keeps a constant bitrate buffer from overflowing.
 *
 * <p>The decoder has one schedule: bits arrive at {@link #rate} bits per second into a buffer of
 * {@link #cpbSize} bits. The first access unit arrives from time 0 and is removed after its
 * {@code initial_cpb_removal_delay}, nine tenths of the buffer's duration; every later access unit is removed
 * a whole number of clock ticks ({@code num_units_in_tick / time_scale} seconds, half a frame) after the
 * first. With {@code cbr_flag} 1 an access unit starts to arrive as soon as the previous one has arrived, and
 * the buffer never holds more than its duration on the 90 kHz clock allows, so that every initial removal
 * delay can be signaled; with {@code cbr_flag} 0 it starts no earlier than its removal time less the buffer's
 * duration, the sum of {@code initial_cpb_removal_delay} and {@code initial_cpb_removal_delay_offset}. Every
 * IDR access unit starts a buffering period whose {@code initial_cpb_removal_delay} is the time from the
 * arrival of the previous access unit's last bit to its removal, rounded down to the 90 kHz clock (and at most
 * the buffer's duration with {@code cbr_flag} 0), as C.3 allows.
 *
 * <p>The bits of an access unit are those of its byte stream form with four-byte start codes: every byte of a
 * packet, whose length prefixes count as the start codes they become. Every access unit starts with an
 * access unit delimiter and every IDR access unit carries the parameter sets, in both packet formats, so the
 * stream a transport stream multiplexer makes of the packets is the stream the model checks.
 *
 * <p>For each access unit the engine calls {@link #begin}, which places it in time and returns the most bits
 * that arrive before its removal, writes the {@linkplain #appendDelimiter delimiter}, the parameter sets of an
 * IDR access unit and the {@linkplain #appendTiming timing SEI unit} before the access unit's other SEI NAL
 * units, codes the access unit within those bits, then calls {@link #appendFiller} and {@link #end}. Times are
 * exact: a whole number of units of {@code 1 / (90000 * time_scale)} seconds plus a fraction of a unit in steps
 * of {@code 1 / rate}.
 *
 * <p>Instances are not thread-safe.
 */
final class HrdModel {
    /**
     * The length in bits of {@code initial_cpb_removal_delay} and its offset.
     */
    static final int INITIAL_DELAY_BITS = 24;

    /**
     * The length in bits of {@code cpb_removal_delay}.
     */
    static final int REMOVAL_DELAY_BITS = 24;

    /**
     * The length in bits of {@code dpb_output_delay}.
     */
    static final int OUTPUT_DELAY_BITS = 24;

    /**
     * The share of the buffer that has arrived when the first access unit is removed.
     */
    private static final double INITIAL_FULLNESS = 0.9;

    /**
     * The SEI {@code payloadType} of a buffering period.
     */
    private static final int SEI_BUFFERING_PERIOD = 0;

    /**
     * The SEI {@code payloadType} of picture timing.
     */
    private static final int SEI_PIC_TIMING = 1;

    /**
     * The frequency of the clock of the initial removal delays.
     */
    private static final long CLOCK = 90_000;

    /**
     * The delivery rate in bits per second.
     */
    private final long rate;

    /**
     * The coded picture buffer size in bits.
     */
    private final long cpbSize;

    /**
     * The most bits a constant bitrate buffer holds: its size, or less when its duration on the 90 kHz clock
     * rounds down.
     */
    private final long capacity;

    /**
     * Whether the delivery is constant ({@code cbr_flag}).
     */
    private final boolean cbr;

    /**
     * The payload of an access unit delimiter that allows every slice type: {@code primary_pic_type} 7 and the
     * trailing bits.
     */
    private static final byte[] DELIMITER = {(byte) 0xF0};

    /**
     * The bytes a filler data NAL unit takes besides its {@code 0xFF} bytes: start code or length prefix, header
     * and trailing byte.
     */
    private static final int FILLER_OVERHEAD = 6;

    /**
     * The {@code time_scale}.
     */
    private final long timeScale;

    /**
     * A time unit's reciprocal, {@code 90000 * time_scale} units per second.
     */
    private final long unitsPerSecond;

    /**
     * A clock tick in time units.
     */
    private final long tick;

    /**
     * The buffer's duration on the 90 kHz clock, rounded down: the largest initial removal delay.
     */
    private final long maxDelay;

    /**
     * The initial removal delay of the first access unit on the 90 kHz clock.
     */
    private final long firstDelay;

    /**
     * The number of access units begun.
     */
    private long units;

    /**
     * The removal time of the current access unit in clock ticks after the first one's.
     */
    private long ticks;

    /**
     * The removal time, in clock ticks after the first one's, of the access unit that starts the current
     * buffering period.
     */
    private long periodTicks;

    /**
     * The {@code cpb_removal_delay} of the current access unit: the clock ticks from the removal of the access
     * unit that starts the most recent buffering period of a preceding access unit to this one's removal.
     */
    private long removalDelay;

    /**
     * Whether the current access unit starts a buffering period.
     */
    private boolean period;

    /**
     * The {@code initial_cpb_removal_delay} of the current access unit when it starts a buffering period.
     */
    private long periodDelay;

    /**
     * The removal time of the current access unit in time units.
     */
    private long removal;

    /**
     * The time the current access unit starts to arrive: whole time units and a fraction in {@code 1 / rate}
     * steps.
     */
    private long startWhole;

    private long startFraction;

    /**
     * The time the last bit of the previous access unit arrived: whole time units and fraction.
     */
    private long endWhole;

    private long endFraction;

    private final MsbBitWriter message = new MsbBitWriter(16);

    private final MsbBitWriter sei = new MsbBitWriter(32);

    /**
     * The payload of the filler data NAL units: {@code 0xFF} bytes and the trailing byte.
     */
    private byte[] filler = new byte[256];

    /**
     * Constructs the model of a stream.
     *
     * @param settings the stream settings, whose configuration signals the decoder
     * @throws IllegalArgumentException if the buffer lasts less than two frame periods, or longer than the
     * initial removal delay can signal
     */
    HrdModel(StreamParameters settings) {
        var config = settings.config();
        this.rate = config.effectiveMaxBitrate();
        this.cpbSize = config.effectiveBufferSize();
        this.cbr = config.rateControl() == H264RateControl.CONSTANT_BITRATE;
        this.timeScale = settings.timeScale();
        this.unitsPerSecond = Math.multiplyExact(CLOCK, timeScale);
        this.tick = Math.multiplyExact(settings.numUnitsInTick(), CLOCK);
        this.maxDelay = CLOCK * cpbSize / rate;
        if (maxDelay >= 1L << INITIAL_DELAY_BITS) {
            throw new IllegalArgumentException("a buffer of " + cpbSize + " bits at " + rate
                    + " bits per second lasts longer than the initial removal delay can signal");
        }
        if (maxDelay * timeScale < 4 * tick) {
            throw new IllegalArgumentException("a buffer of " + cpbSize + " bits at " + rate
                    + " bits per second lasts less than two frame periods");
        }
        this.capacity = maxDelay * rate / CLOCK;
        this.firstDelay = Math.max(1, (long) (INITIAL_FULLNESS * maxDelay));
    }

    /**
     * Writes the {@code hrd_parameters} of the video usability information of a stream: one schedule whose
     * rate and size are multiples of 64 and 16 with scales of 0.
     *
     * @param writer the sequence parameter set being written
     * @param settings the stream settings
     */
    static void writeParameters(MsbBitWriter writer, StreamParameters settings) {
        var config = settings.config();
        writer.writeUe(0);
        writer.write(0, 4);
        writer.write(0, 4);
        writer.writeUe(config.effectiveMaxBitrate() / 64 - 1);
        writer.writeUe(config.effectiveBufferSize() / 16 - 1);
        writer.writeBit(config.rateControl() == H264RateControl.CONSTANT_BITRATE ? 1 : 0);
        writer.write(INITIAL_DELAY_BITS - 1, 5);
        writer.write(REMOVAL_DELAY_BITS - 1, 5);
        writer.write(OUTPUT_DELAY_BITS - 1, 5);
        writer.write(0, 5);
    }

    /**
     * Places the next access unit in time.
     *
     * @param step the clock ticks from the previous access unit's removal to this one's: 2 for a frame, 1 for
     * a field; ignored for the first access unit
     * @param bufferingPeriod whether the access unit starts a buffering period, as every IDR access unit does
     * @return the most bits the access unit may take: those that arrive between the start of its arrival and its
     * removal
     */
    long begin(int step, boolean bufferingPeriod) {
        period = bufferingPeriod || units == 0;
        if (units == 0) {
            ticks = 0;
            periodTicks = 0;
            removalDelay = 0;
            periodDelay = firstDelay;
            removal = firstDelay * timeScale;
            startWhole = 0;
            startFraction = 0;
        } else {
            ticks += step;
            removal = firstDelay * timeScale + ticks * tick;
            removalDelay = ticks - periodTicks;
            if (period) {
                periodTicks = ticks;
                periodDelay = periodDelay();
            }
            if (cbr) {
                startWhole = endWhole;
                startFraction = endFraction;
            } else {
                var limit = removal - (period ? periodDelay : maxDelay) * timeScale;
                if (limit > endWhole || limit == endWhole && endFraction == 0) {
                    startWhole = limit;
                    startFraction = 0;
                } else {
                    startWhole = endWhole;
                    startFraction = endFraction;
                }
            }
        }
        units++;
        return Math.max(0, bitsBetween(startWhole, startFraction, removal));
    }

    /**
     * Returns the {@code initial_cpb_removal_delay} of the current access unit, which starts a buffering
     * period after the first access unit: the time from the arrival of the previous access unit's last bit to
     * this one's removal on the 90 kHz clock, rounded down, at least 1, and with {@code cbr_flag} 0 at most
     * the buffer's duration.
     *
     * @return the delay
     */
    private long periodDelay() {
        var gap = removal - endWhole;
        // the exact gap is gap - endFraction / rate units with 0 <= endFraction / rate < 1, so its quotient by
        // timeScale rounds down to floorDiv(gap, timeScale) unless gap is a multiple of timeScale
        var delay = Math.floorDiv(endFraction > 0 && Math.floorMod(gap, timeScale) == 0 ? gap - 1 : gap, timeScale);
        return Math.max(1, cbr ? delay : Math.min(maxDelay, delay));
    }

    /**
     * Returns the whole bits that arrive between a time and a later whole time.
     *
     * @param whole the earlier time's whole units
     * @param fraction the earlier time's fraction
     * @param until the later time in whole units
     * @return the bits, rounded down, or a negative number when the later time is earlier
     */
    private long bitsBetween(long whole, long fraction, long until) {
        try {
            return Math.floorDiv(Math.multiplyExact(until - whole, rate) - fraction, unitsPerSecond);
        } catch (ArithmeticException overflow) {
            return BigInteger.valueOf(until - whole).multiply(BigInteger.valueOf(rate))
                    .subtract(BigInteger.valueOf(fraction)).divide(BigInteger.valueOf(unitsPerSecond)).longValue();
        }
    }

    /**
     * Appends the SEI NAL unit of the current access unit's buffering period, when it starts one, and picture
     * timing.
     *
     * @param nal the access unit being assembled, before its other SEI NAL units
     * @param outputTicks the {@code dpb_output_delay}, the clock ticks from the access unit's removal to its
     * output, from 0 to {@code 2^24 - 1}
     * @param structure the {@code pic_struct}: 0 for a frame, 1 for a top field, 2 for a bottom field, 3 for
     * a frame whose top field comes first, 4 for one whose bottom field comes first
     */
    void appendTiming(NalWriter nal, int outputTicks, int structure) {
        sei.reset();
        if (period) {
            message.reset();
            message.writeUe(0);
            message.writeLong(periodDelay, INITIAL_DELAY_BITS);
            message.writeLong(cbr ? 0 : maxDelay - periodDelay, INITIAL_DELAY_BITS);
            appendMessage(SEI_BUFFERING_PERIOD);
        }
        message.reset();
        message.writeLong(removalDelay & ((1L << REMOVAL_DELAY_BITS) - 1), REMOVAL_DELAY_BITS);
        message.writeLong(outputTicks, OUTPUT_DELAY_BITS);
        message.write(structure, 4);
        var timestamps = structure <= 2 ? 1 : 2;
        for (var i = 0; i < timestamps; i++) {
            message.writeBit(0);
        }
        appendMessage(SEI_PIC_TIMING);
        sei.writeTrailingBits();
        nal.appendUnit(0, H264Nal.SEI, sei.array(), sei.size());
    }

    /**
     * Appends the message in {@link #message} to {@link #sei}, aligning its payload.
     *
     * @param type the {@code payloadType}
     */
    private void appendMessage(int type) {
        if (!message.isByteAligned()) {
            message.writeBit(1);
            message.alignWithZeros();
        }
        var size = message.size();
        sei.write(type, 8);
        for (var value = size; ; value -= 255) {
            if (value < 255) {
                sei.write(value, 8);
                break;
            }
            sei.write(255, 8);
        }
        sei.writeBytes(message.array(), 0, size);
    }

    /**
     * Appends the access unit delimiter every access unit starts with.
     *
     * @param nal the access unit being assembled, empty
     */
    void appendDelimiter(NalWriter nal) {
        nal.appendUnit(0, H264Nal.AUD, DELIMITER, DELIMITER.length);
    }

    /**
     * Returns the bits of an access unit the decoder counts.
     *
     * @param nal the access unit being assembled
     * @param start the index of the access unit's first byte in the writer
     * @return the bits, every byte from {@code start} on
     */
    long countedBits(NalWriter nal, int start) {
        return 8L * (nal.size() - start);
    }

    /**
     * Appends to a constant bitrate access unit the filler data that keeps the buffer within its capacity
     * until the next removal, and returns the counted bits of the access unit.
     *
     * @param nal the access unit being assembled, complete apart from the filler
     * @param start the index of the access unit's first byte in the writer
     * @param nextStep the clock ticks to the next access unit's removal
     * @return the counted bits of the access unit, filler included
     */
    long appendFiller(NalWriter nal, int start, int nextStep) {
        var bits = countedBits(nal, start);
        if (!cbr) {
            return bits;
        }
        // just before the next removal the buffer holds the bits that arrived since this access unit started
        // to arrive, less this access unit's own; one more bit covers the rounding down
        var arriving = bitsBetween(startWhole, startFraction, removal + nextStep * tick) + 1;
        var excess = arriving - bits - capacity;
        if (excess <= 0) {
            return bits;
        }
        var payload = (int) Math.max(0, Math.ceilDiv(excess, 8) - FILLER_OVERHEAD);
        if (filler.length < payload + 1) {
            filler = new byte[Math.max(payload + 1, 2 * filler.length)];
        }
        Arrays.fill(filler, 0, payload, (byte) 0xFF);
        filler[payload] = (byte) 0x80;
        nal.appendUnit(0, H264Nal.FILLER, filler, payload + 1);
        return countedBits(nal, start);
    }

    /**
     * Ends the current access unit.
     *
     * @param bits the counted bits of the access unit, filler included
     */
    void end(long bits) {
        // the last bit arrives bits / rate seconds after the first: bits * unitsPerSecond / rate units
        try {
            var elapsed = Math.addExact(Math.multiplyExact(bits, unitsPerSecond), startFraction);
            endWhole = startWhole + elapsed / rate;
            endFraction = elapsed % rate;
        } catch (ArithmeticException overflow) {
            var parts = BigInteger.valueOf(bits).multiply(BigInteger.valueOf(unitsPerSecond))
                    .add(BigInteger.valueOf(startFraction)).divideAndRemainder(BigInteger.valueOf(rate));
            endWhole = startWhole + parts[0].longValueExact();
            endFraction = parts[1].longValue();
        }
    }
}

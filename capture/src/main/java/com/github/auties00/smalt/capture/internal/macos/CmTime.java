package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.track.Rational;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Mirrors a CoreMedia {@code CMTime}, a rational time of {@code value / timescale} seconds.
 *
 * <p>The native structure is 24 bytes:
 * {@snippet :
 * typedef struct {
 *     CMTimeValue value;      //  0: int64_t
 *     CMTimeScale timescale;  //  8: int32_t
 *     CMTimeFlags flags;      // 12: uint32_t
 *     CMTimeEpoch epoch;      // 16: int64_t
 * } CMTime;
 * }
 *
 * @implNote This implementation declares {@link #LAYOUT} with natural 8-byte alignment although
 * {@code CMTime.h} wraps the declaration in {@code #pragma pack(push, 4)}: the Foreign
 * Function and Memory API accepts only naturally aligned layouts, and the field offsets
 * and the 24-byte size are identical under both packings, so every standalone
 * {@code CMTime} is passed and returned the same way (in memory, being larger than 16
 * bytes, on both arm64 and x86_64).
 *
 * @param value the numerator, in units of {@code 1 / timescale} seconds
 * @param timescale the units per second
 * @param flags the {@code kCMTimeFlags_*} bits
 * @param epoch the epoch, distinguishing equal values of different loops
 */
record CmTime(long value, int timescale, int flags, long epoch) {
    /**
     * The {@code kCMTimeFlags_Valid} flag.
     */
    static final int VALID = 1;

    /**
     * The {@code kCMTimeFlags_ImpliedValueFlagsMask}: positive infinity, negative infinity and
     * indefinite ({@code 1 << 2 | 1 << 3 | 1 << 4}).
     */
    static final int IMPLIED_VALUE_MASK = (1 << 2) | (1 << 3) | (1 << 4);

    /**
     * The layout of the native structure.
     */
    static final StructLayout LAYOUT = MemoryLayout.structLayout(
            JAVA_LONG.withName("value"),
            JAVA_INT.withName("timescale"),
            JAVA_INT.withName("flags"),
            JAVA_LONG.withName("epoch"));

    /**
     * The offset of {@code value}.
     */
    static final long VALUE_OFFSET = LAYOUT.byteOffset(groupElement("value"));

    /**
     * The offset of {@code timescale}.
     */
    static final long TIMESCALE_OFFSET = LAYOUT.byteOffset(groupElement("timescale"));

    /**
     * The offset of {@code flags}.
     */
    static final long FLAGS_OFFSET = LAYOUT.byteOffset(groupElement("flags"));

    /**
     * The offset of {@code epoch}.
     */
    static final long EPOCH_OFFSET = LAYOUT.byteOffset(groupElement("epoch"));

    /**
     * Returns a valid time in epoch zero.
     *
     * @param value the numerator
     * @param timescale the units per second
     * @return the time
     */
    static CmTime of(long value, int timescale) {
        return new CmTime(value, timescale, VALID, 0);
    }

    /**
     * Returns the duration of one frame at a frame rate.
     *
     * <p>A rate of {@code n / d} frames per second is a duration of {@code d / n} seconds. The
     * rate is reduced first; a numerator too large for a {@code CMTimeScale} is approximated on a
     * microsecond timescale.
     *
     * @param rate the frames per second
     * @return the frame duration
     */
    static CmTime frameDuration(Rational rate) {
        var reduced = rate.reduce();
        if (reduced.numerator() <= Integer.MAX_VALUE) {
            return of(reduced.denominator(), (int) reduced.numerator());
        }
        var micros = Math.max(1, Math.round(1_000_000.0 * reduced.denominator() / reduced.numerator()));
        return of(micros, 1_000_000);
    }

    /**
     * Reads a time from native memory.
     *
     * @param segment the memory, at least {@link #LAYOUT}'s size long
     * @return the time
     * @throws IndexOutOfBoundsException if {@code segment} is too short
     */
    static CmTime read(MemorySegment segment) {
        return new CmTime(segment.get(JAVA_LONG, VALUE_OFFSET), segment.get(JAVA_INT, TIMESCALE_OFFSET),
                segment.get(JAVA_INT, FLAGS_OFFSET), segment.get(JAVA_LONG, EPOCH_OFFSET));
    }

    /**
     * Writes this time to native memory.
     *
     * @param segment the memory, at least {@link #LAYOUT}'s size long
     * @throws IndexOutOfBoundsException if {@code segment} is too short
     */
    void write(MemorySegment segment) {
        segment.set(JAVA_LONG, VALUE_OFFSET, value);
        segment.set(JAVA_INT, TIMESCALE_OFFSET, timescale);
        segment.set(JAVA_INT, FLAGS_OFFSET, flags);
        segment.set(JAVA_LONG, EPOCH_OFFSET, epoch);
    }

    /**
     * Allocates native memory holding this time.
     *
     * @return the memory, laid out as {@link #LAYOUT}
     */
    MemorySegment allocate(SegmentAllocator allocator) {
        var segment = allocator.allocate(LAYOUT);
        write(segment);
        return segment;
    }

    /**
     * Returns whether this time is a finite number of seconds.
     *
     * <p>That is {@code CMTIME_IS_NUMERIC} with a positive timescale.
     *
     * @return whether the time is valid, finite and has a positive timescale
     */
    boolean isNumeric() {
        return (flags & (VALID | IMPLIED_VALUE_MASK)) == VALID && timescale > 0;
    }

    /**
     * Converts this time to whole microseconds, rounding toward negative infinity.
     *
     * <p>The whole seconds and the fraction are converted separately, so no intermediate product
     * overflows for any timescale; only a result beyond the range of a {@code long} fails.
     *
     * @return the time in microseconds
     * @throws IllegalStateException if the time is not {@link #isNumeric() numeric}
     * @throws ArithmeticException if the result does not fit in a {@code long}
     */
    long toMicros() {
        if (!isNumeric()) {
            throw new IllegalStateException("not a numeric time: " + this);
        }
        long scale = timescale;
        var seconds = Math.floorDiv(value, scale);
        var remainder = Math.floorMod(value, scale);
        return Math.addExact(Math.multiplyExact(seconds, 1_000_000L), remainder * 1_000_000L / scale);
    }

    /**
     * Compares the durations of two numeric times exactly.
     *
     * @param other the time to compare with
     * @return a negative number, zero or a positive number as this time is shorter than, equal to
     * or longer than {@code other}
     * @throws IllegalStateException if either time is not {@link #isNumeric() numeric}
     */
    int compareDuration(CmTime other) {
        if (!isNumeric() || !other.isNumeric()) {
            throw new IllegalStateException("not a numeric time: " + this + " or " + other);
        }
        var leftHigh = Math.multiplyHigh(value, other.timescale);
        var leftLow = value * other.timescale;
        var rightHigh = Math.multiplyHigh(other.value, timescale);
        var rightLow = other.value * timescale;
        if (leftHigh != rightHigh) {
            return Long.compare(leftHigh, rightHigh);
        }
        return Long.compareUnsigned(leftLow, rightLow);
    }

    /**
     * Returns the frame rate whose frame duration is this time.
     *
     * @return {@code timescale / value}, reduced
     * @throws IllegalStateException if the time is not numeric or not positive
     */
    Rational toRate() {
        if (!isNumeric() || value <= 0) {
            throw new IllegalStateException("not a positive numeric time: " + this);
        }
        return new Rational(timescale, value).reduce();
    }
}

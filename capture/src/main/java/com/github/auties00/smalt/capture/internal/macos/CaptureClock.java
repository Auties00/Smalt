package com.github.auties00.smalt.capture.internal.macos;

/**
 * Converts capture timestamps from the host clock to the session clock.
 *
 * <p>AVFoundation and ScreenCaptureKit stamp sample buffers with presentation times on the host
 * time clock ({@code CMClockGetHostTimeClock}). A session reads that clock once when it opens, at
 * {@code originMicros} on its own clock, and maps every later presentation time by the same
 * offset. A presentation time that is not numeric, or that lands implausibly far from the moment
 * the frame arrived (a device on another clock), is replaced by the arrival time.
 *
 * @param hostOriginMicros the host clock reading when the session opened, in microseconds
 * @param originMicros     the session clock reading at the same moment, in microseconds
 */
record CaptureClock(long hostOriginMicros, long originMicros) {
    /**
     * How far after its arrival a capture time may be before it is distrusted.
     */
    private static final long MAX_AHEAD_MICROS = 1_000_000;

    /**
     * How far before its arrival a capture time may be before it is distrusted.
     */
    private static final long MAX_BEHIND_MICROS = 10_000_000;

    /**
     * Returns the session timestamp of a frame.
     *
     * @param presentationTime the frame's presentation time on the host clock
     * @param arrivalMicros    the session clock reading when the frame arrived
     * @return the capture time on the session clock, in microseconds
     */
    long timestamp(CmTime presentationTime, long arrivalMicros) {
        if (!presentationTime.isNumeric()) {
            return arrivalMicros;
        }
        long captured;
        try {
            captured = Math.addExact(Math.subtractExact(presentationTime.toMicros(), hostOriginMicros), originMicros);
        } catch (ArithmeticException overflow) {
            return arrivalMicros;
        }
        if (captured > arrivalMicros + MAX_AHEAD_MICROS || captured < arrivalMicros - MAX_BEHIND_MICROS) {
            return arrivalMicros;
        }
        return captured;
    }
}

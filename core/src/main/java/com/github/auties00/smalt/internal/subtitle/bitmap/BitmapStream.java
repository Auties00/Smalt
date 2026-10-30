package com.github.auties00.smalt.internal.subtitle.bitmap;

import com.github.auties00.smalt.subtitle.BitmapRegion;

import java.util.List;

/**
 * Decodes the packets of one bitmap subtitle stream into the screens it shows.
 *
 * <p>Implementations are stateful and not thread-safe; they report each change of the screen to a {@link Sink}
 * in presentation order.
 */
public interface BitmapStream {
    /**
     * The deadline of a screen that stays until the stream replaces or clears it.
     */
    long NO_DEADLINE = Long.MIN_VALUE;

    /**
     * Decodes one packet.
     *
     * @param data the packet payload
     * @param micros the presentation time of the packet in microseconds
     * @param sink receives the screens the packet shows and clears
     */
    void decode(byte[] data, long micros, Sink sink);

    /**
     * Forgets the state built from earlier packets, as after a seek.
     */
    void reset();

    /**
     * Receives the changes of the screen.
     */
    interface Sink {
        /**
         * Receives a screen shown from a time on.
         *
         * @param micros the time the screen appears, in microseconds
         * @param screen what it shows
         * @param deadlineMicros the latest time it disappears, or {@link #NO_DEADLINE} when only a later change
         * removes it
         */
        void show(long micros, Screen screen, long deadlineMicros);

        /**
         * Receives the time the screen is cleared.
         *
         * @param micros the time, in microseconds
         */
        void clear(long micros);
    }

    /**
     * Holds the pictures shown at once.
     *
     * @param width the canvas width in pixels
     * @param height the canvas height in pixels
     * @param regions the pictures, in drawing order, possibly empty
     * @param forced whether the stream marks the screen as forced
     */
    record Screen(int width, int height, List<BitmapRegion> regions, boolean forced) {
    }
}

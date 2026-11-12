package com.github.auties00.smalt.internal.sei;

/**
 * Tracks which message of one SEI message type applies to each picture as pictures are output,
 * following the persistence rule that the film grain characteristics, frame packing arrangement and
 * display orientation messages of ITU-T H.264 and H.265 share.
 *
 * <p>A message applies to the picture whose access unit carries it. A message that persists (an
 * H.264 repetition period above 0, an H.265 persistence flag of 1) also applies to the pictures
 * output after it until a picture whose access unit carries another message of the type is output,
 * or a new coded video sequence starts. A cancelling message applies to nothing and ends the
 * persistence of the previous one.
 *
 * <p>Instances are not thread-safe.
 *
 * @param <T> the message type
 */
public final class SeiPersistence<T extends SeiPersistence.Message> {
    /**
     * A message whose persistence this class tracks.
     */
    public interface Message {
        /**
         * {@return whether the message cancels the persistence of the previous one}
         */
        boolean cancel();

        /**
         * {@return whether the message applies to the pictures output after its own}
         */
        boolean persists();
    }

    /**
     * The persisting message, or {@code null}.
     */
    private T active;

    /**
     * Constructs a tracker with no message in effect.
     */
    public SeiPersistence() {
    }

    /**
     * Returns the message that applies to the picture being output, and records the picture's own
     * message.
     *
     * @param newSequence whether the picture starts a new coded video sequence
     * @param own the message of the picture's access unit, or {@code null}
     * @return the message that applies, or {@code null} for none
     */
    public T resolve(boolean newSequence, T own) {
        if (newSequence) {
            active = null;
        }
        if (own == null) {
            return active;
        }
        if (own.cancel()) {
            active = null;
            return null;
        }
        active = own.persists() ? own : null;
        return own;
    }

    /**
     * Forgets the persisting message.
     */
    public void reset() {
        active = null;
    }
}

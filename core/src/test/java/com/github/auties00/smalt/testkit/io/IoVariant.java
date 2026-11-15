package com.github.auties00.smalt.testkit.io;

import com.github.auties00.smalt.io.MediaSource;

import java.io.InputStream;
import java.util.List;

/**
 * The ways the behavior harnesses deliver one input to a parser; every variant must give the parser's
 * reference result, the one of {@link #MEMORY}.
 */
public enum IoVariant {
    /** {@link MediaSource#of(byte[])}: the reference. */
    MEMORY(false, true),
    /** Every read delivers one byte. */
    ONE_BYTE(false, true),
    /** Reads deliver pseudo-random chunk sizes. */
    RANDOM_CHUNKS(false, true),
    /** The length is hidden ({@code size() == -1}) and reads deliver pseudo-random chunk sizes. */
    UNKNOWN_LENGTH(false, false),
    /** The length is hidden and every read delivers one byte. */
    UNKNOWN_LENGTH_ONE_BYTE(false, false),
    /**
     * {@link MediaSource#of(InputStream, long)} over a {@link SimulatedStream}, which delivers short reads,
     * with the length declared.
     */
    READ_ONCE(true, true),
    /** {@link MediaSource#of(InputStream, long)} over a {@link SimulatedStream}, length unknown. */
    READ_ONCE_UNKNOWN_LENGTH(true, false);

    private final boolean readOnce;
    private final boolean sized;

    IoVariant(boolean readOnce, boolean sized) {
        this.readOnce = readOnce;
        this.sized = sized;
    }

    /**
     * {@return whether the source reads its input once, front to back, so that seeking back may fail
     * with {@link com.github.auties00.smalt.MediaException.Unsupported}}
     */
    public boolean isReadOnce() {
        return readOnce;
    }

    /**
     * {@return whether the source reports its length}
     */
    public boolean isSized() {
        return sized;
    }

    /**
     * {@return every variant other than {@link #MEMORY}}
     */
    public static List<IoVariant> alternatives() {
        return List.of(ONE_BYTE, RANDOM_CHUNKS, UNKNOWN_LENGTH, UNKNOWN_LENGTH_ONE_BYTE, READ_ONCE,
                READ_ONCE_UNKNOWN_LENGTH);
    }

    /**
     * Returns a new source over {@code data} with this variant's behavior; the caller closes it.
     *
     * @param data the input, not copied
     * @return the source
     */
    public MediaSource open(byte[] data) {
        return switch (this) {
            case MEMORY -> MediaSource.of(data);
            case ONE_BYTE -> SimulatedSource.of(data, true, SimulatedSource.Chunking.ONE_BYTE, 0);
            case RANDOM_CHUNKS -> SimulatedSource.of(data, true, SimulatedSource.Chunking.RANDOM, 1);
            case UNKNOWN_LENGTH -> SimulatedSource.of(data, false, SimulatedSource.Chunking.RANDOM, 2);
            case UNKNOWN_LENGTH_ONE_BYTE -> SimulatedSource.of(data, false, SimulatedSource.Chunking.ONE_BYTE, 0);
            case READ_ONCE -> SimulatedStream.of(data).source(true);
            case READ_ONCE_UNKNOWN_LENGTH -> SimulatedStream.of(data).source(false);
        };
    }
}

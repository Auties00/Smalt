package com.github.auties00.smalt;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Signals that a media operation failed.
 *
 * <p>Every failure raised by this module is one of the four permitted subclasses, so callers can
 * pattern-match exhaustively on the cause: {@link Malformed} for input that violates its format
 * specification or ends early, {@link Unsupported} for well-formed input that uses a format or
 * feature this module does not implement, {@link LimitExceeded} for input whose declared size
 * exceeds a {@link MediaLimits} bound, and {@link Io} for a failure of the underlying byte source
 * or sink. Decoders never leak an {@link ArrayIndexOutOfBoundsException},
 * {@link OutOfMemoryError} from an attacker-chosen allocation, or an endless loop on malformed
 * input; they raise {@link Malformed} instead.
 */
public sealed abstract class MediaException extends RuntimeException
        permits MediaException.Malformed, MediaException.Unsupported, MediaException.LimitExceeded, MediaException.Io {
    /**
     * Constructs an exception with the given detail message.
     *
     * @param message the detail message
     */
    private MediaException(String message) {
        super(message);
    }

    /**
     * Constructs an exception with the given detail message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause, or {@code null}
     */
    private MediaException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Signals input that violates its format specification, is truncated, or is internally
     * inconsistent.
     */
    public static final class Malformed extends MediaException {
        /**
         * Constructs the exception with the given detail message.
         *
         * @param message the description of the violation
         */
        public Malformed(String message) {
            super(message);
        }

        /**
         * Constructs the exception with the given detail message and cause.
         *
         * @param message the description of the violation
         * @param cause   the underlying cause, or {@code null}
         */
        public Malformed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Signals well-formed input that uses a container, codec, or coding feature this module does
     * not implement.
     *
     * <p>The {@link #format()} names what was detected, for example {@code "hevc"} or
     * {@code "jpeg arithmetic coding"}, so callers can report exactly which input was rejected.
     * When the format is implemented by a Smalt artifact that is not on the module or class path,
     * {@link #artifact()} names that artifact (for example {@code "smalt-mpeg"} for H.264 or AAC),
     * so the caller can suggest adding it.
     */
    public static final class Unsupported extends MediaException {
        /**
         * The short name of the unsupported format or feature.
         *
         * @serial
         */
        private final String format;

        /**
         * The artifact id of the Smalt artifact that implements the format, or {@code null}.
         *
         * @serial
         */
        private final String artifact;

        /**
         * What is unsupported.
         *
         * @serial
         */
        private final Kind kind;

        /**
         * Constructs the exception for the named format or feature.
         *
         * @param format  the short name of the unsupported format or feature
         * @param message the detail message
         * @throws NullPointerException if {@code format} is {@code null}
         */
        public Unsupported(String format, String message) {
            this(format, message, null);
        }

        /**
         * Constructs the exception for a format that a Smalt artifact missing at run time
         * implements.
         *
         * @param format   the short name of the unsupported format or feature
         * @param message  the detail message
         * @param artifact the artifact id of the Smalt artifact that implements the format, such as
         *                 {@code "smalt-mpeg"}, or {@code null} when none does
         * @throws NullPointerException if {@code format} is {@code null}
         */
        public Unsupported(String format, String message, String artifact) {
            this(Kind.FEATURE, format, message, artifact);
        }

        /**
         * Constructs the exception for an unsupported format, codec, feature or operation.
         *
         * @param kind what is unsupported
         * @param format the short name of the unsupported format, codec, feature or operation
         * @param message the detail message
         */
        public Unsupported(Kind kind, String format, String message) {
            this(kind, format, message, null);
        }

        /**
         * Constructs the exception for a format or codec that a Smalt artifact missing at run time
         * implements.
         *
         * @param kind what is unsupported
         * @param format the short name of the unsupported format, codec, feature or operation
         * @param message the detail message
         * @param artifact the artifact id of the Smalt artifact that implements the format, such as
         *     {@code "smalt-mpeg"}, or {@code null} when none does
         */
        public Unsupported(Kind kind, String format, String message, String artifact) {
            super(message);
            this.kind = Objects.requireNonNull(kind, "kind");
            this.format = Objects.requireNonNull(format, "format");
            this.artifact = artifact;
        }

        /**
         * {@return what is unsupported}
         *
         * <p>The constructors without a kind parameter report {@link Kind#FEATURE}.
         */
        public Kind kind() {
            return kind;
        }

        /**
         * Returns the short name of the unsupported format or feature.
         *
         * @return the format or feature name, never {@code null}
         */
        public String format() {
            return format;
        }

        /**
         * Returns the Smalt artifact that implements the format, when one does.
         *
         * <p>An artifact is reported only when the format is unsupported because that artifact is
         * missing at run time; a format no Smalt artifact implements, or a feature the present
         * decoder lacks, reports none.
         *
         * @return the artifact id (for example {@code "smalt-mpeg"}), or empty
         */
        public Optional<String> artifact() {
            return Optional.ofNullable(artifact);
        }

        /**
         * Classifies what an {@link Unsupported} exception rejects, so callers can branch on it.
         *
         * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
         * {@code default} branch.
         */
        public enum Kind {
            /**
             * A container or file format, for example an unrecognized container or a camera raw
             * format without a decoder.
             */
            FORMAT,

            /**
             * A codec, for example a track whose codec has no decoder or encoder on the module path.
             */
            CODEC,

            /**
             * A coding feature of an otherwise supported format, for example JPEG arithmetic coding
             * or an encrypted track.
             */
            FEATURE,

            /**
             * An operation the object cannot perform on its input, for example seeking in a
             * non-seekable source.
             */
            OPERATION
        }
    }

    /**
     * Signals input whose declared dimensions, frame count, duration, or allocation size exceeds
     * a bound of the active {@link MediaLimits}.
     *
     * <p>The check runs before the corresponding allocation, so a hostile header cannot force an
     * oversized buffer.
     */
    public static final class LimitExceeded extends MediaException {
        /**
         * Constructs the exception with the given detail message.
         *
         * @param message the description of the exceeded bound
         */
        public LimitExceeded(String message) {
            super(message);
        }
    }

    /**
     * Signals that reading from a byte source or writing to a byte sink failed.
     */
    public static final class Io extends MediaException {
        /**
         * Constructs the exception wrapping the given I/O failure.
         *
         * @param message the detail message
         * @param cause   the I/O failure
         * @throws NullPointerException if {@code cause} is {@code null}
         */
        public Io(String message, IOException cause) {
            super(message, Objects.requireNonNull(cause, "cause"));
        }

        /**
         * Returns the wrapped I/O failure.
         *
         * @return the cause, never {@code null}
         */
        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }
}

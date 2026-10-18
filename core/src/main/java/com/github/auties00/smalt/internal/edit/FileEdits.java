package com.github.auties00.smalt.internal.edit;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Function;

/**
 * Applies an {@link EditPlan} to a file, in place when the plan allows it and otherwise by
 * writing a temporary file next to the original and moving it over the original.
 *
 * <p>The class is stateless and thread-safe; concurrent edits of the same file are not.
 */
public final class FileEdits {
    /**
     * Prevents instantiation.
     */
    private FileEdits() {
        throw new AssertionError();
    }

    /**
     * Plans and applies an edit of a file.
     *
     * <p>The file is opened for reading and handed to {@code planner}. When the plan is
     * {@linkplain EditPlan#isIdentity(long) the identity} or otherwise
     * {@linkplain EditPlan#reproduces(MediaSource) reproduces} the original, nothing is written. When it is
     * {@linkplain EditPlan#isInPlace() in place} and {@code allowInPlace} is set, its literal
     * pieces are written over the original and the file is truncated or extended to the plan's
     * length. Otherwise the whole output is streamed to a temporary file in the same directory,
     * which then replaces the original, atomically where the file system supports it.
     *
     * @param file the file to edit
     * @param allowInPlace whether the original may be modified in place
     * @param planner builds the plan from the opened original
     * @return how the file was changed
     * @throws MediaException.Io if reading or writing the file fails
     */
    public static Outcome apply(Path file, boolean allowInPlace, Function<MediaSource, EditPlan> planner) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(planner, "planner");
        EditPlan plan;
        long originalLength;
        var source = MediaSource.of(file);
        try {
            originalLength = source.size();
            plan = planner.apply(source);
            if (plan.isIdentity(originalLength) || plan.reproduces(source)) {
                return Outcome.UNCHANGED;
            }
            if (!allowInPlace || !plan.isInPlace()) {
                rewrite(file, source, plan);
                return Outcome.REWRITTEN;
            }
        } finally {
            source.close();
        }
        writeInPlace(file, plan);
        return Outcome.IN_PLACE;
    }

    /**
     * Names how {@link #apply(Path, boolean, Function)} changed a file.
     */
    public enum Outcome {
        /**
         * The plan reproduced the file, which was left untouched.
         */
        UNCHANGED,

        /**
         * The changed bytes were written over the original.
         */
        IN_PLACE,

        /**
         * The file was rewritten and replaced.
         */
        REWRITTEN
    }

    /**
     * Writes the literal pieces of an in-place plan over the original and sets its length.
     *
     * @param file the original, overwritten
     * @param plan the in-place plan
     * @throws MediaException.Io if writing fails
     */
    private static void writeInPlace(Path file, EditPlan plan) {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var position = 0L;
            for (var piece : plan.pieces()) {
                if (piece instanceof EditPlan.Bytes(var data)) {
                    var buffer = ByteBuffer.wrap(data);
                    var at = position;
                    while (buffer.hasRemaining()) {
                        at += channel.write(buffer, at);
                    }
                }
                position += piece.length();
            }
            if (channel.size() > plan.length()) {
                channel.truncate(plan.length());
            }
            channel.force(true);
        } catch (IOException exception) {
            throw new MediaException.Io("cannot edit " + file + " in place", exception);
        }
    }

    /**
     * Streams the whole output to a temporary file and moves it over the original.
     *
     * @param file the original
     * @param source the opened original, closed before the move
     * @param plan the edits to apply
     * @throws MediaException.Io if writing or moving fails
     */
    private static void rewrite(Path file, MediaSource source, EditPlan plan) {
        var directory = file.toAbsolutePath().getParent();
        Path temporary;
        try {
            temporary = Files.createTempFile(directory, ".smalt-edit-", ".tmp");
        } catch (IOException exception) {
            throw new MediaException.Io("cannot create a temporary file next to " + file, exception);
        }
        var moved = false;
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 var sink = MediaSink.of(channel)) {
                plan.writeTo(source, sink);
                channel.force(true);
            } catch (IOException exception) {
                throw new MediaException.Io("cannot write " + temporary, exception);
            }
            source.close();
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } catch (IOException exception) {
            throw new MediaException.Io("cannot replace " + file, exception);
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // the temporary file is left behind; the original is intact
                }
            }
        }
    }

    /**
     * Applies a plan to an in-memory original.
     *
     * @param original the original bytes
     * @param plan the edits to apply
     * @return the output bytes
     * @throws MediaException.LimitExceeded if the output does not fit an array
     */
    public static byte[] apply(byte[] original, EditPlan plan) {
        if (plan.length() > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("edited file of " + plan.length() + " bytes does not fit an array");
        }
        var sink = MediaSink.memory();
        plan.writeTo(MediaSource.of(original), sink);
        return sink.toByteArray();
    }
}

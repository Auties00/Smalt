package com.github.auties00.smalt.container;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Small hand-written input files shared by the container tests.
 */
public final class TestFiles {
    private TestFiles() {
        throw new AssertionError();
    }

    /** Returns an SRT subtitle file with two cues. */
    public static Path subtitles() {
        try {
            var srt = Path.of("target", "media-fixtures", "subtitles.srt");
            Files.createDirectories(srt.getParent());
            Files.writeString(srt, "1\n00:00:00,000 --> 00:00:00,400\nHello\n\n2\n00:00:00,500 --> 00:00:00,900\nWorld\n");
            return srt;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

package com.github.auties00.smalt.container;

import com.github.auties00.smalt.testkit.Scratch;

import java.nio.charset.StandardCharsets;
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
        return Scratch.share(Path.of("target", "media-fixtures", "subtitles.srt"),
                "1\n00:00:00,000 --> 00:00:00,400\nHello\n\n2\n00:00:00,500 --> 00:00:00,900\nWorld\n"
                        .getBytes(StandardCharsets.UTF_8));
    }
}

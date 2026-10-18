package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link SubtitleReaderOptions.Builder#sourceMarkup()}.
 */
@DisplayName("SubtitleReader options")
class SubtitleReaderOptionsTest {
    @Test
    @DisplayName("sourceMarkup keeps the markup of the track")
    void sourceMarkup() {
        var options = SubtitleReaderOptions.builder().markup(SubtitleMarkup.WEBVTT).sourceMarkup().build();
        assertEquals(Optional.empty(), options.markup());
        assertEquals(SubtitleReaderOptions.defaults(), options);
        var srt = "1\n00:00:00,000 --> 00:00:01,000\nHello\n".getBytes(StandardCharsets.UTF_8);
        try (var reader = SubtitleReader.open(MediaSource.of(srt), options)) {
            assertEquals(SubtitleMarkup.SUBRIP, reader.markup());
        }
    }
}

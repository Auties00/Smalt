package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Scratch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Streaming test content of the hosted test data: the CTA WAVE CMAF sets, each a zip of an MPD, an initialization
 * segment ({@code init.mp4}) and media segments named after their start time ({@code <time>.m4s}), and the DASH-IF
 * multichannel audio segments, joined into one fragmented MP4 file as a player appends them.
 */
public final class StreamingVectors {
    private StreamingVectors() {
    }

    /**
     * Returns the initialization segment of a CTA WAVE set followed by its media segments in time order.
     *
     * @param vector the name of the set's zip in {@code vectors.tsv}
     * @return the fragmented MP4 file
     */
    public static byte[] cmaf(String vector) {
        try (var zip = new ZipFile(Conformance.vector(vector).toFile())) {
            var entries = zip.stream().filter(entry -> !entry.isDirectory()).toList();
            var init = entries.stream().filter(entry -> entry.getName().endsWith("/init.mp4")).findFirst()
                    .orElseThrow(() -> new AssertionError(vector + " has no init.mp4"));
            var segments = new ArrayList<>(entries.stream().filter(entry -> entry.getName().endsWith(".m4s")).toList());
            segments.sort(Comparator.comparingLong(entry -> Long.parseLong(
                    entry.getName().substring(entry.getName().lastIndexOf('/') + 1, entry.getName().length() - 4))));
            var out = new ByteArrayOutputStream();
            out.writeBytes(zip.getInputStream(init).readAllBytes());
            for (var segment : segments) {
                out.writeBytes(zip.getInputStream(segment).readAllBytes());
            }
            return out.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Returns hosted vectors joined in order, an initialization segment then media segments.
     *
     * @param vectors the vector names
     * @return the joined file
     */
    public static byte[] join(List<String> vectors) {
        var out = new ByteArrayOutputStream();
        for (var vector : vectors) {
            try {
                out.writeBytes(Files.readAllBytes(Conformance.vector(vector)));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return out.toByteArray();
    }

    /**
     * Writes a file where an oracle tool can read it.
     *
     * @param name the file name
     * @param data the content
     * @return the file
     */
    public static Path write(String name, byte[] data) {
        try {
            return Files.write(Scratch.directory("streaming-vectors").resolve(name), data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

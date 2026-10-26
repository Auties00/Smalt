package com.github.auties00.smalt.testkit.fuzz;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The input format and the driver of the fuzz targets of adaptive streaming presentations (HLS playlists and DASH
 * manifests), which name other files: the manifest, then each file it names, the parts separated by the marker
 * {@code FUZZ-TAG}; a file part is its name, a line feed and its bytes. The driver resolves the names the demuxer asks
 * for among the parts, so that a mutation of one segment leaves the manifest and the others intact.
 */
public final class FuzzPresentations {
    /** The marker between the parts. */
    private static final byte[] MARKER = "FUZZ-TAG".getBytes(StandardCharsets.US_ASCII);

    private FuzzPresentations() {
        throw new AssertionError();
    }

    /**
     * Packs a presentation into the input format.
     *
     * @param manifest the manifest
     * @param files the files it names, by the name it gives them
     * @return the input
     */
    public static byte[] pack(byte[] manifest, Map<String, byte[]> files) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(manifest);
        for (var entry : files.entrySet()) {
            out.writeBytes(MARKER);
            out.writeBytes((entry.getKey() + "\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(entry.getValue());
        }
        return out.toByteArray();
    }

    /**
     * An input unpacked.
     *
     * @param manifest the manifest
     * @param files the files, by name
     */
    public record Presentation(byte[] manifest, Map<String, byte[]> files) {
        /** {@return the options of a demuxer that reads the files of the presentation} */
        public DemuxerOptions options() {
            DataReferenceResolver resolver = reference -> Optional.ofNullable(files.get(reference.location()))
                    .map(MediaSource::of);
            return DemuxerOptions.builder().limits(Fuzz.LIMITS).dataReferences(resolver).build();
        }
    }

    /**
     * Unpacks an input.
     *
     * @param data the input
     * @return the presentation; a part without a line feed names a file of no bytes
     */
    public static Presentation unpack(byte[] data) {
        var files = new LinkedHashMap<String, byte[]>();
        var end = indexOf(data, 0);
        var manifest = Arrays.copyOfRange(data, 0, end < 0 ? data.length : end);
        while (end >= 0) {
            var start = end + MARKER.length;
            end = indexOf(data, start);
            var part = Arrays.copyOfRange(data, start, end < 0 ? data.length : end);
            var newline = 0;
            while (newline < part.length && part[newline] != '\n') {
                newline++;
            }
            var name = new String(part, 0, newline, StandardCharsets.UTF_8);
            files.put(name, newline < part.length ? Arrays.copyOfRange(part, newline + 1, part.length) : new byte[0]);
        }
        return new Presentation(manifest, files);
    }

    private static int indexOf(byte[] data, int from) {
        outer:
        for (var at = from; at + MARKER.length <= data.length; at++) {
            for (var index = 0; index < MARKER.length; index++) {
                if (data[at + index] != MARKER[index]) {
                    continue outer;
                }
            }
            return at;
        }
        return -1;
    }

    /**
     * Demuxes a presentation of one format: recognizes its manifest, opens it with the files of the input, reads its
     * tracks and every packet, and seeks to the start and the middle.
     *
     * <p>An input whose manifest {@link Containers#detect} does not recognize as {@code format} is skipped.
     *
     * @param format {@link ContainerFormat#HLS} or {@link ContainerFormat#DASH}
     * @param data the input
     * @return the run
     */
    public static FuzzRun demuxer(ContainerFormat format, byte[] data) {
        var run = Fuzz.start(format.name());
        var presentation = unpack(data);
        var detected = run.value("detect", () -> Containers.detect(MediaSource.of(presentation.manifest()))
                .orElse(null));
        if (detected.isEmpty() || detected.get() != format) {
            run.skip("detected " + detected.map(Enum::name).orElse("nothing"));
            return run;
        }
        run.step("demux", () -> {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(presentation.manifest()), presentation.options())) {
                for (var track : demuxer.tracks()) {
                    demuxer.trackMetadata(track.id());
                    demuxer.trackDisposition(track.id());
                }
                var duration = demuxer.durationMicros();
                drain(run, demuxer, Fuzz.MAX_PACKETS);
                if (demuxer.isSeekable()) {
                    demuxer.seek(0);
                    drain(run, demuxer, 16);
                    if (duration > 0) {
                        demuxer.seek(duration / 2);
                        drain(run, demuxer, 16);
                    }
                }
            }
        });
        return run;
    }

    private static void drain(FuzzRun run, Demuxer demuxer, int limit) {
        for (var count = 0; count < limit; count++) {
            var packet = demuxer.read();
            if (packet == null) {
                return;
            }
            packet.size();
            run.output();
        }
    }
}

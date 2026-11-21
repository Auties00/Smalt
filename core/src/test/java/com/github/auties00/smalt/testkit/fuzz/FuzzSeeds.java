package com.github.auties00.smalt.testkit.fuzz;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the seed corpora of the fuzz targets.
 *
 * <p>Seeds live where Jazzer looks for the inputs of a fuzz test, {@code
 * src/test/resources/<package>/<Class>Inputs/<method>/}, so that a normal build replays them and a fuzzing run
 * starts from them. A seed named {@code valid-*} must be accepted by every step of its target
 * ({@link FuzzRegressions} checks it). The writers write under {@code -Dsmalt.fuzz.seedRoot} instead of the module
 * directory when it is set, so that a run on another machine can hand the seeds back as files (for example under
 * {@code target/media-fixtures}, which CI uploads).
 */
public final class FuzzSeeds {
    /** The prefix of the seeds every step of a target must accept. */
    public static final String VALID = "valid-";

    private FuzzSeeds() {
        throw new AssertionError();
    }

    /**
     * Returns the source directory of the seeds of a fuzz test, relative to the module directory.
     *
     * @param fuzzClass the class declaring the fuzz test
     * @param method the name of the fuzz test method
     * @return the directory
     */
    public static Path directory(Class<?> fuzzClass, String method) {
        return directory(fuzzClass.getName(), method);
    }

    /**
     * Returns the source directory of the seeds of a fuzz test, relative to the module directory, for a class
     * that the caller cannot name.
     *
     * @param fuzzClass the binary name of the class declaring the fuzz test, which is a top-level class
     * @param method the name of the fuzz test method
     * @return the directory
     */
    public static Path directory(String fuzzClass, String method) {
        var dot = fuzzClass.lastIndexOf('.');
        return Path.of("src", "test", "resources")
                .resolve(fuzzClass.substring(0, Math.max(dot, 0)).replace('.', '/'))
                .resolve(fuzzClass.substring(dot + 1) + "Inputs")
                .resolve(method);
    }

    /**
     * Writes one seed of a fuzz test class that the caller cannot name.
     *
     * @param fuzzClass the binary name of the class declaring the fuzz test, which is a top-level class
     * @param method the name of the fuzz test method
     * @param name the file name of the seed
     * @param data the seed
     */
    public static void write(String fuzzClass, String method, String name, byte[] data) {
        try {
            var root = System.getProperty("smalt.fuzz.seedRoot");
            var directory = root == null || root.isBlank() ? directory(fuzzClass, method)
                    : Path.of(root).resolve(directory(fuzzClass, method));
            Files.createDirectories(directory);
            Files.write(directory.resolve(name), data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Writes one seed.
     *
     * @param fuzzClass the class declaring the fuzz test
     * @param method the name of the fuzz test method
     * @param name the file name of the seed
     * @param data the seed
     */
    public static void write(Class<?> fuzzClass, String method, String name, byte[] data) {
        write(fuzzClass.getName(), method, name, data);
    }

    /**
     * Writes one seed copied from a file.
     *
     * @param fuzzClass the class declaring the fuzz test
     * @param method the name of the fuzz test method
     * @param name the file name of the seed
     * @param file the file to copy
     */
    public static void copy(Class<?> fuzzClass, String method, String name, Path file) {
        write(fuzzClass, method, name, Fixtures.read(file));
    }

    /**
     * Returns a conformance vector listed in {@code tools/testdata/vectors.tsv}, fetched through
     * {@link Conformance#fetch}; the calling test is skipped when it is not available.
     *
     * @param name the name of the vector in the manifest
     * @return the cached file
     * @throws IllegalArgumentException if the manifest has no such vector
     */
    public static Path vector(String name) {
        var manifest = Path.of("..", "tools", "testdata", "vectors.tsv");
        try (var lines = Files.lines(manifest)) {
            var row = lines.filter(line -> !line.startsWith("#"))
                    .map(line -> line.split("\t"))
                    .filter(columns -> columns.length >= 5 && columns[1].equals(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(name + " is not in " + manifest));
            return Conformance.fetch(row[1], row[4], row[3]);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Returns ffmpeg input arguments for a short {@code testsrc2} video at 10 frames per second.
     *
     * @param width the width
     * @param height the height
     * @param frames the frame count
     * @return the arguments
     */
    public static List<String> video(int width, int height, int frames) {
        return List.of("-f", "lavfi", "-i", "testsrc2=size=" + width + "x" + height + ":rate=10:duration="
                + frames / 10.0);
    }

    /**
     * Returns ffmpeg input arguments for a short sine tone.
     *
     * @param sampleRate the sample rate
     * @param seconds the duration
     * @return the arguments
     */
    public static List<String> tone(int sampleRate, double seconds) {
        return List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=" + sampleRate + ":duration=" + seconds);
    }

    /**
     * Returns the concatenation of argument lists.
     *
     * @param parts the lists
     * @return the arguments
     */
    @SafeVarargs
    public static List<String> args(List<String>... parts) {
        var all = new ArrayList<String>();
        for (var part : parts) {
            all.addAll(part);
        }
        return all;
    }

    /**
     * Returns an ffmpeg-made file.
     *
     * @param extension the extension, which selects the muxer
     * @param inputs the input arguments
     * @param outputs the output arguments, to which the bit-exact flags are added
     * @return the file, cached under {@code target/media-fixtures}
     */
    public static Path ffmpeg(String extension, List<String> inputs, String... outputs) {
        var arguments = new ArrayList<>(List.of(outputs));
        arguments.addAll(List.of("-fflags", "+bitexact", "-flags:v", "+bitexact", "-flags:a", "+bitexact"));
        return Fixtures.ffmpeg(extension, inputs, arguments);
    }

    /**
     * Converts the first track of a codec in a media file to the {@link FuzzPackets} format.
     *
     * @param file the media file, in any container Smalt demuxes
     * @param codec the codec of the track to convert
     * @param options the options byte of the header
     * @param maxPackets the most packets to keep, from the start of the track
     * @return the input
     * @throws IllegalArgumentException if the file has no track of {@code codec}
     */
    public static byte[] packets(Path file, Codec codec, int options, int maxPackets) {
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            Track track = null;
            for (var candidate : demuxer.tracks()) {
                if (candidate.codec() == codec) {
                    track = candidate;
                    break;
                }
            }
            if (track == null) {
                throw new IllegalArgumentException(file + " has no " + codec + " track");
            }
            var parts = new ArrayList<FuzzPackets.Part>();
            for (var packet = demuxer.read(); packet != null && parts.size() < maxPackets; packet = demuxer.read()) {
                if (packet.trackId() == track.id()) {
                    parts.add(new FuzzPackets.Part(packet.data(), packet.keyframe()));
                }
            }
            return packets(track, options, parts);
        }
    }

    /**
     * Converts the first video track of a transparent media file to the input of
     * {@link FuzzMedia#videoAlpha(Codec, byte[])}: each packet's payload followed by
     * {@link FuzzMedia#ALPHA_MARKER} and its alpha frame, when it has one.
     *
     * @param file the media file, whose first video track carries alpha
     * @param maxPackets the most packets to keep, from the start of the track
     * @return the input
     */
    public static byte[] alphaPackets(Path file, int maxPackets) {
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            var track = demuxer.tracks().stream().filter(VideoTrack.class::isInstance).findFirst().orElseThrow();
            var parts = new ArrayList<FuzzPackets.Part>();
            for (var packet = demuxer.read(); packet != null && parts.size() < maxPackets; packet = demuxer.read()) {
                if (packet.trackId() != track.id()) {
                    continue;
                }
                var payload = new java.io.ByteArrayOutputStream();
                payload.writeBytes(packet.data());
                packet.sideData().alphaFrame().ifPresent(alpha -> {
                    payload.writeBytes(FuzzMedia.ALPHA_MARKER);
                    payload.writeBytes(alpha);
                });
                parts.add(new FuzzPackets.Part(payload.toByteArray(), packet.keyframe()));
            }
            return packets(track, 0, parts);
        }
    }

    /**
     * Converts a track and its packets to the {@link FuzzPackets} format.
     *
     * @param track the track, which gives the header fields and the codec configuration
     * @param options the options byte of the header
     * @param parts the packets
     * @return the input
     */
    public static byte[] packets(Track track, int options, List<FuzzPackets.Part> parts) {
        var config = track.codecConfig().orElse(null);
        return switch (track) {
            case VideoTrack video -> FuzzPackets.video(options, video.width(), video.height(), config, parts);
            case AudioTrack audio -> FuzzPackets.audio(options, audio.sampleRate(),
                    audio.layout().map(ChannelLayout::channels).orElse(0), audio.bitsPerSample(), config, parts);
            default -> throw new IllegalArgumentException(track.codec() + " is not an audio or video codec");
        };
    }
}

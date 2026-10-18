package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Converts media files: the entry point of the transcode API, covering what the common ffmpeg
 * command lines do.
 *
 * <p>An audio/video transcode ({@link #transcode(MediaSource, MediaSink, TranscodeOptions)}) reads
 * any container {@link com.github.auties00.smalt.container.Containers} demuxes, or an animated
 * GIF, WebP or APNG image, and writes an {@link OutputContainer}. Each selected stream is copied
 * when nothing requires re-encoding it and encoded otherwise ({@link StreamMode}); the encoders
 * and decoders come from the registries
 * ({@link com.github.auties00.smalt.video.VideoEncoders},
 * {@link com.github.auties00.smalt.audio.AudioEncoders} and their decoder counterparts), so the
 * Smalt artifacts on the module or class path decide which codecs are available, and a missing
 * one fails before anything is written with a {@link MediaException.Unsupported} naming the
 * artifact to add. An image transcode
 * ({@link #transcodeImage(MediaSource, MediaSink, ImageTranscodeOptions)}) converts a still or
 * animated image to another image format.
 *
 * <p>Transcodes stream: packets go from the demuxer through the decoders, the picture and sample
 * operations and the encoders to the muxer as they are read, so memory does not grow with the
 * length of the input (a fast-start MP4 spools its media data to a temporary file past a memory
 * threshold). The calling thread runs the job; codecs parallelize internally on the module's
 * shared pool, and no other thread is started.
 *
 * @apiNote Typical replacements of ffmpeg command lines:
 * {@snippet :
 * // ffmpeg -i in.mkv -c copy out.mp4
 * Transcoder.transcode(MediaSource.of(in), MediaSink.of(out), TranscodeOptions.remux(OutputContainer.MP4));
 *
 * // ffmpeg -i in.mp4 -c:v libvpx-vp9 -c:a libopus -vf scale=-2:480 out.webm
 * var options = TranscodeOptions.of(OutputContainer.WEBM).toBuilder()
 *         .video(VideoTranscodeOptions.encode(Codec.VP9).toBuilder().maxSize(0, 480).build())
 *         .build();
 * Transcoder.transcode(MediaSource.of(in), MediaSink.of(out), options);
 *
 * // ffmpeg -ss 10 -to 20 -i in.mp4 out.mp4 (frame-exact, with progress)
 * var job = Transcoder.job(MediaSource.of(in), MediaSink.of(out), TranscodeOptions.builder()
 *         .range(10_000_000, 20_000_000)
 *         .listener(progress -> System.out.println(progress.fraction()))
 *         .build());
 * job.run(); // job.cancel() from another thread stops it
 * }
 */
public final class Transcoder {
    /**
     * Prevents instantiation.
     */
    private Transcoder() {
        throw new AssertionError();
    }

    /**
     * Transcodes an audio/video input on the calling thread.
     *
     * @param input   the input; not closed
     * @param output  the output, written from its position; not closed
     * @param options the job description
     * @return what was written
     * @throws NullPointerException         if an argument is {@code null}
     * @throws CancellationException        if the calling thread is interrupted
     * @throws IllegalArgumentException     if the options select a track the input lacks, or ask
     *                                      for a codec or a stream the container cannot carry
     * @throws MediaException.Unsupported   if the input is not recognized, a stream must be copied
     *                                      but cannot be, or a needed decoder or encoder is missing
     * @throws MediaException.Malformed     if the input is corrupt
     * @throws MediaException.LimitExceeded if the input exceeds the limits
     * @throws MediaException.Io            if reading the input or writing the output fails
     * @see TranscodeJob#run()
     */
    public static TranscodeResult transcode(MediaSource input, MediaSink output, TranscodeOptions options) {
        return job(input, output, options).run();
    }

    /**
     * Transcodes an audio/video file into another, choosing the container from the output file's
     * extension (see {@link OutputContainer#forFileName(String)}).
     *
     * <p>The output file is created or truncated, and deleted when the transcode fails.
     *
     * @param input  the input file
     * @param output the output file
     * @return what was written
     * @throws NullPointerException         if an argument is {@code null}
     * @throws IllegalArgumentException     if the output extension names no container, or the
     *                                      container cannot carry the input's streams
     * @throws CancellationException        if the calling thread is interrupted
     * @throws MediaException.Unsupported   if the input is not recognized or a needed codec is
     *                                      missing
     * @throws MediaException.Malformed     if the input is corrupt
     * @throws MediaException.LimitExceeded if the input exceeds the limits
     * @throws MediaException.Io            if reading or writing a file fails
     */
    public static TranscodeResult transcode(Path input, Path output) {
        Objects.requireNonNull(output, "output");
        var container = OutputContainer.forFileName(output.getFileName().toString())
                .orElseThrow(() -> new IllegalArgumentException("no output container has the extension of " + output));
        return transcode(input, output, TranscodeOptions.of(container));
    }

    /**
     * Transcodes an audio/video file into another.
     *
     * <p>The output file is created or truncated, and deleted when the transcode fails.
     *
     * @param input   the input file
     * @param output  the output file
     * @param options the job description
     * @return what was written
     * @throws NullPointerException         if an argument is {@code null}
     * @throws IllegalArgumentException     if the options select a track the input lacks, or ask
     *                                      for a codec or a stream the container cannot carry
     * @throws CancellationException        if the calling thread is interrupted
     * @throws MediaException.Unsupported   if the input is not recognized or a needed codec is
     *                                      missing
     * @throws MediaException.Malformed     if the input is corrupt
     * @throws MediaException.LimitExceeded if the input exceeds the limits
     * @throws MediaException.Io            if reading or writing a file fails
     */
    public static TranscodeResult transcode(Path input, Path output, TranscodeOptions options) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        var completed = false;
        try (var source = MediaSource.of(input); var sink = MediaSink.of(output)) {
            var result = transcode(source, sink, options);
            completed = true;
            return result;
        } finally {
            if (!completed) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // the failure that got here is the one worth reporting
                }
            }
        }
    }

    /**
     * Prepares an audio/video transcode that a thread runs with {@link TranscodeJob#run()} and any
     * thread may cancel with {@link TranscodeJob#cancel()}.
     *
     * @param input   the input; not closed
     * @param output  the output, written from its position; not closed
     * @param options the job description
     * @return the job, not started
     * @throws NullPointerException if an argument is {@code null}
     */
    public static TranscodeJob job(MediaSource input, MediaSink output, TranscodeOptions options) {
        return new TranscodeJob(input, output, options);
    }

    /**
     * Transcodes an image on the calling thread.
     *
     * @param input   the input; not closed
     * @param output  the output, written from its position; not closed
     * @param options the job description
     * @return what was written
     * @throws NullPointerException         if an argument is {@code null}
     * @throws IllegalArgumentException     if the crop region is not inside the picture
     * @throws MediaException.Unsupported   if the input is not a decodable image or the output
     *                                      format is not encodable (the exception names the
     *                                      artifact to add)
     * @throws MediaException.Malformed     if the image is corrupt
     * @throws MediaException.LimitExceeded if the image exceeds the limits, or no encoding fits the
     *                                      byte budget
     * @throws MediaException.Io            if reading the input or writing the output fails
     */
    public static ImageTranscodeResult transcodeImage(MediaSource input, MediaSink output, ImageTranscodeOptions options) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        return new ImageTranscode(input, output, options, AnimationWriters.REGISTRY).run();
    }

    /**
     * Transcodes an image file into another on the calling thread.
     *
     * <p>The output file is created or truncated, and deleted when the transcode fails.
     *
     * @param input the input file
     * @param output the output file
     * @param options the job description
     * @return what was written
     * @throws IllegalArgumentException if the crop region is not inside the picture
     * @throws MediaException.Unsupported if the input is not a decodable image or the output format
     *     is not encodable (the exception names the artifact to add)
     * @throws MediaException.Malformed if the image is corrupt
     * @throws MediaException.LimitExceeded if the image exceeds the limits, or no encoding fits the
     *     byte budget
     * @throws MediaException.Io if reading or writing a file fails
     */
    public static ImageTranscodeResult transcodeImage(Path input, Path output, ImageTranscodeOptions options) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        var completed = false;
        try (var source = MediaSource.of(input); var sink = MediaSink.of(output)) {
            var result = transcodeImage(source, sink, options);
            completed = true;
            return result;
        } finally {
            if (!completed) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // the failure that got here is the one worth reporting
                }
            }
        }
    }
}

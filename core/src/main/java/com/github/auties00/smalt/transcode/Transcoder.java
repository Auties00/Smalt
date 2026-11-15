package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * <p>A transcode may read several inputs ({@link TranscodeInput}): their tracks are all candidates for the
 * stream selections, which {@link VideoTranscodeOptions#input()}, {@link AudioTranscodeOptions#input()} and
 * {@link SubtitleTranscodeOptions#input()} narrow to one input, and their packets are interleaved by time. An input
 * may be a concatenation of files played one after the other ({@link TranscodeInput#concat(List)}). A transcode may
 * also write several outputs ({@link TranscodeOutput}) from one read of its inputs, such as the renditions of an
 * adaptive streaming ladder: the outputs that encode the same video track share its decoder, so the video is decoded
 * once whatever the number of renditions.
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
 * // ffmpeg -i video.mp4 -i audio.wav -i subs.srt -map 0:v -map 1:a -map 2:s out.mkv
 * Transcoder.transcode(List.of(TranscodeInput.of(video), TranscodeInput.of(audio), TranscodeInput.of(subs)),
 *         MediaSink.of(out), TranscodeOptions.of(OutputContainer.MATROSKA));
 *
 * // ffmpeg -f concat -i list.txt -c copy out.mkv
 * Transcoder.concat(List.of(part1, part2), MediaSink.of(out), TranscodeOptions.remux(OutputContainer.MATROSKA));
 *
 * // ffmpeg -i in.mp4 -filter:v setpts=PTS/1.5 -filter:a atempo=1.5,highpass=f=80,afade=t=out:d=2 out.mp4
 * Transcoder.transcode(MediaSource.of(in), MediaSink.of(out), TranscodeOptions.builder().speed(1.5)
 *         .audio(AudioTranscodeOptions.builder().filters(List.of(Biquad.highPass(80)))
 *                 .fades(List.of(Fade.out(2_000_000))).build())
 *         .build());
 *
 * // ffmpeg -i in.mp4 -s 1280x720 720.mp4 -s 640x360 360.mp4 (one decode, two renditions)
 * Transcoder.transcode(MediaSource.of(in), List.of(
 *         new TranscodeOutput(MediaSink.of(hd), TranscodeOptions.of(OutputContainer.MP4).toBuilder()
 *                 .video(VideoTranscodeOptions.builder().maxSize(1280, 720).build()).build()),
 *         new TranscodeOutput(MediaSink.of(sd), TranscodeOptions.of(OutputContainer.MP4).toBuilder()
 *                 .video(VideoTranscodeOptions.builder().maxSize(640, 360).build()).build())));
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
     * <p>The output file is created or truncated, and deleted when the transcode fails. When the options resolve no
     * other file ({@link TranscodeOptions#dataReferences()}), the files the input names are those of its directory
     * that {@link DataReferenceResolver#directory(Path)} supplies, such as the linked segments of a Matroska file.
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
        var directory = input.toAbsolutePath().getParent();
        var resolved = directory == null || !options.dataReferences().equals(DataReferenceResolver.none()) ? options
                : options.toBuilder().dataReferences(DataReferenceResolver.directory(directory)).build();
        var completed = false;
        try (var source = MediaSource.of(input); var sink = MediaSink.of(output)) {
            var result = transcode(source, sink, resolved);
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
     * Transcodes several inputs into one output on the calling thread.
     *
     * @param inputs the inputs, at least one, in the order the input indices of the options count them; not closed
     * @param output the output, written from its position; not closed
     * @param options the job description
     * @return what was written
     * @throws NullPointerException         if an input is {@code null}
     * @throws CancellationException        if the calling thread is interrupted
     * @throws IllegalArgumentException     if the list is empty, the options select a track or an input the job
     *                                      lacks, ask for a codec or a stream the container cannot carry, or a
     *                                      concatenation's files have different tracks
     * @throws MediaException.Unsupported   if an input is not recognized, a stream must be copied but cannot be, or
     *                                      a needed decoder or encoder is missing
     * @throws MediaException.Malformed     if an input is corrupt
     * @throws MediaException.LimitExceeded if an input exceeds the limits
     * @throws MediaException.Io            if reading an input or writing the output fails
     */
    public static TranscodeResult transcode(List<TranscodeInput> inputs, MediaSink output, TranscodeOptions options) {
        return job(inputs, List.of(new TranscodeOutput(output, options))).run();
    }

    /**
     * Transcodes one input into several outputs on the calling thread, reading the input once.
     *
     * @param input the input; not closed
     * @param outputs the outputs, at least one, which share the range, duration limit, seeking, limits, data
     * references, speed and silence trim of their options
     * @return what was written, one result per output in order
     * @throws NullPointerException         if an output is {@code null}
     * @throws CancellationException        if the calling thread is interrupted
     * @throws IllegalArgumentException     if the list is empty, the outputs differ in a setting they must share, or
     *                                      the options select a track the input lacks or ask for a codec or a stream
     *                                      the container cannot carry
     * @throws MediaException.Unsupported   if the input is not recognized, a stream must be copied but cannot be, or
     *                                      a needed decoder or encoder is missing
     * @throws MediaException.Malformed     if the input is corrupt
     * @throws MediaException.LimitExceeded if the input exceeds the limits
     * @throws MediaException.Io            if reading the input or writing an output fails
     */
    public static List<TranscodeResult> transcode(MediaSource input, List<TranscodeOutput> outputs) {
        return job(List.of(TranscodeInput.of(input)), outputs).runAll();
    }

    /**
     * Transcodes several inputs into several outputs on the calling thread, reading the inputs once.
     *
     * @param inputs the inputs, at least one; not closed
     * @param outputs the outputs, at least one, which share the settings of the input side of their options
     * @return what was written, one result per output in order
     * @throws NullPointerException         if an element is {@code null}
     * @throws CancellationException        if the calling thread is interrupted
     * @throws IllegalArgumentException     if a list is empty, the outputs differ in a setting they must share, the
     *                                      options select a track or an input the job lacks, or ask for a codec or a
     *                                      stream the container cannot carry
     * @throws MediaException.Unsupported   if an input is not recognized, a stream must be copied but cannot be, or
     *                                      a needed decoder or encoder is missing
     * @throws MediaException.Malformed     if an input is corrupt
     * @throws MediaException.LimitExceeded if an input exceeds the limits
     * @throws MediaException.Io            if reading an input or writing an output fails
     */
    public static List<TranscodeResult> transcode(List<TranscodeInput> inputs, List<TranscodeOutput> outputs) {
        return job(inputs, outputs).runAll();
    }

    /**
     * Prepares a transcode of several inputs or outputs that a thread runs with {@link TranscodeJob#runAll()} and
     * any thread may cancel with {@link TranscodeJob#cancel()}.
     *
     * @param inputs the inputs, at least one; not closed
     * @param outputs the outputs, at least one
     * @return the job, not started
     * @throws NullPointerException if an element is {@code null}
     * @throws IllegalArgumentException if a list is empty
     */
    public static TranscodeJob job(List<TranscodeInput> inputs, List<TranscodeOutput> outputs) {
        return new TranscodeJob(inputs, outputs);
    }

    /**
     * Concatenates files into one output on the calling thread, as {@link TranscodeInput#concat(List)} describes.
     *
     * @param inputs the files, in playing order; not closed
     * @param output the output, written from its position; not closed
     * @param options the job description
     * @return what was written
     * @throws NullPointerException         if an input is {@code null}
     * @throws CancellationException        if the calling thread is interrupted
     * @throws IllegalArgumentException     if the list is empty, the files have different tracks, or the options
     *                                      ask for a codec or a stream the container cannot carry
     * @throws MediaException.Unsupported   if a file is not recognized, a stream must be copied but its files differ
     *                                      in codec or codec configuration, or a needed codec is missing
     * @throws MediaException.Malformed     if a file is corrupt
     * @throws MediaException.LimitExceeded if a file exceeds the limits
     * @throws MediaException.Io            if reading a file or writing the output fails
     */
    public static TranscodeResult concat(List<MediaSource> inputs, MediaSink output, TranscodeOptions options) {
        return transcode(List.of(TranscodeInput.concat(inputs)), output, options);
    }

    /**
     * Concatenates files into another file on the calling thread.
     *
     * <p>The output file is created or truncated, and deleted when the transcode fails.
     *
     * @param inputs the files, in playing order
     * @param output the output file
     * @param options the job description
     * @return what was written
     * @throws NullPointerException         if an input is {@code null}
     * @throws CancellationException        if the calling thread is interrupted
     * @throws IllegalArgumentException     if the list is empty, the files have different tracks, or the options
     *                                      ask for a codec or a stream the container cannot carry
     * @throws MediaException.Unsupported   if a file is not recognized, a stream must be copied but its files differ
     *                                      in codec or codec configuration, or a needed codec is missing
     * @throws MediaException.Malformed     if a file is corrupt
     * @throws MediaException.LimitExceeded if a file exceeds the limits
     * @throws MediaException.Io            if reading or writing a file fails
     */
    public static TranscodeResult concatFiles(List<Path> inputs, Path output, TranscodeOptions options) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        var sources = new ArrayList<MediaSource>();
        var completed = false;
        try {
            for (var input : inputs) {
                sources.add(MediaSource.of(Objects.requireNonNull(input, "input")));
            }
            try (var sink = MediaSink.of(output)) {
                var result = concat(sources, sink, options);
                completed = true;
                return result;
            }
        } finally {
            for (var source : sources) {
                try {
                    source.close();
                } catch (RuntimeException ignored) {
                    // the sources were only read
                }
            }
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

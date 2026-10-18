package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.testkit.Oracle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes a media file the way ffprobe and ffmpeg see it, for the transcode tests: the streams
 * with their start times, the decoded video frame count and the decoded audio sample count.
 */
record MediaProbe(List<StreamInfo> streams) {
    /**
     * One stream as ffprobe reports it, plus what ffmpeg decodes from it.
     *
     * @param index      the stream index
     * @param type       {@code video} or {@code audio}
     * @param codec      the ffmpeg codec name
     * @param start      the start time in seconds, or {@code NaN}
     * @param duration   the stream duration in seconds as the container declares it, or {@code NaN}
     * @param width      the width, or {@code 0}
     * @param height     the height, or {@code 0}
     * @param sampleRate the sampling rate, or {@code 0}
     * @param channels   the channel count, or {@code 0}
     * @param decoded    the decoded frames (video) or samples per channel (audio)
     * @param rotation   the display rotation in degrees, {@code 0} when absent
     */
    record StreamInfo(int index, String type, String codec, double start, double duration, int width, int height, int sampleRate,
                      int channels, long decoded, int rotation) {
        /**
         * Returns the decoded duration of an audio stream.
         *
         * @return the duration in seconds
         */
        double audioSeconds() {
            return (double) decoded / sampleRate;
        }
    }

    /**
     * Probes a file.
     *
     * @param file the file
     * @return the description
     */
    static MediaProbe of(Path file) {
        var text = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(2), List.of("-v", "error",
                "-show_entries", "stream=index,codec_type,codec_name,start_time,duration,width,height,sample_rate,channels:stream_side_data=rotation",
                "-of", "compact=p=0", file.toString())), StandardCharsets.UTF_8);
        var streams = new ArrayList<StreamInfo>();
        for (var line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            var fields = new HashMap<String, String>();
            for (var pair : line.split("\\|")) {
                var equals = pair.indexOf('=');
                if (equals > 0) {
                    fields.put(pair.substring(0, equals), pair.substring(equals + 1));
                }
            }
            var type = fields.getOrDefault("codec_type", "");
            if (!type.equals("video") && !type.equals("audio")) {
                continue;
            }
            var index = Integer.parseInt(fields.get("index"));
            var channels = integer(fields, "channels");
            var decoded = type.equals("video") ? countFrames(file, index) : countSamples(file, index, channels);
            streams.add(new StreamInfo(index, type, fields.get("codec_name"), number(fields, "start_time"), number(fields, "duration"),
                    integer(fields, "width"), integer(fields, "height"), integer(fields, "sample_rate"), channels, decoded,
                    integer(fields, "side_datum/display_matrix:rotation")));
        }
        return new MediaProbe(List.copyOf(streams));
    }

    /**
     * Returns the streams of a type.
     *
     * @param type {@code video} or {@code audio}
     * @return the streams
     */
    List<StreamInfo> of(String type) {
        return streams.stream().filter(stream -> stream.type().equals(type)).toList();
    }

    /**
     * Returns the only video stream.
     *
     * @return the stream
     */
    StreamInfo video() {
        var video = of("video");
        if (video.size() != 1) {
            throw new AssertionError("expected one video stream, found " + video.size());
        }
        return video.getFirst();
    }

    /**
     * Returns the only audio stream.
     *
     * @return the stream
     */
    StreamInfo audio() {
        var audio = of("audio");
        if (audio.size() != 1) {
            throw new AssertionError("expected one audio stream, found " + audio.size());
        }
        return audio.getFirst();
    }

    /**
     * Counts the frames ffmpeg decodes from a video stream.
     *
     * @param file  the file
     * @param index the stream index
     * @return the frame count
     */
    private static long countFrames(Path file, int index) {
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(2), List.of("-v", "error",
                "-count_frames", "-select_streams", Integer.toString(index), "-show_entries", "stream=nb_read_frames",
                "-of", "csv=p=0", file.toString())), StandardCharsets.UTF_8).trim();
        return output.isEmpty() || output.equals("N/A") ? 0 : Long.parseLong(output.split("\\R")[0].replace(",", "").trim());
    }

    /**
     * Counts the samples per channel ffmpeg decodes from an audio stream.
     *
     * @param file     the file
     * @param index    the stream index
     * @param channels the channel count
     * @return the sample count
     */
    private static long countSamples(Path file, int index, int channels) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:" + index, "-f", "f32le", "-"));
        return raw.length / 4L / Math.max(1, channels);
    }

    /**
     * Decodes an audio stream to mono floats.
     *
     * @param file the file
     * @return the samples
     */
    static float[] monoSamples(Path file) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:a:0", "-ac", "1", "-f", "f32le", "-"));
        var samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }

    /**
     * Parses an integer field.
     *
     * @param fields the fields
     * @param key    the key
     * @return the value, or {@code 0} when absent
     */
    private static int integer(Map<String, String> fields, String key) {
        var value = fields.get(key);
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * Parses a decimal field.
     *
     * @param fields the fields
     * @param key    the key
     * @return the value, or {@code NaN} when absent
     */
    private static double number(Map<String, String> fields, String key) {
        var value = fields.get(key);
        try {
            return value == null ? Double.NaN : Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }
}

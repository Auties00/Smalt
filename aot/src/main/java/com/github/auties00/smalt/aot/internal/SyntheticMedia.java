package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.YuvPicture;

import java.util.List;

/**
 * Validates the arguments of {@link com.github.auties00.smalt.aot.TrainingMedia} and synthesizes
 * the media it returns.
 */
public final class SyntheticMedia {
    /**
     * The lowest sample rate accepted, in hertz.
     */
    private static final int MIN_RATE = 8000;

    /**
     * The highest sample rate accepted, in hertz.
     */
    private static final int MAX_RATE = 192_000;

    /**
     * Prevents instantiation.
     */
    private SyntheticMedia() {
        throw new AssertionError();
    }

    /**
     * Returns natural-looking video.
     *
     * @param width  the width in samples
     * @param height the height in samples
     * @param frames the number of frames
     * @return the pictures
     * @throws IllegalArgumentException if an argument is not positive
     */
    public static List<YuvPicture> video(int width, int height, int frames) {
        if (width <= 0 || height <= 0 || frames <= 0) {
            throw new IllegalArgumentException("width, height and frames must be positive: " + width + "x" + height
                    + ", " + frames);
        }
        return SyntheticVideo.natural(width, height, frames);
    }

    /**
     * Returns mono speech.
     *
     * @param sampleRate the sample rate in hertz
     * @param seconds    the duration
     * @return the samples
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static float[] speech(int sampleRate, double seconds) {
        checkAudio(sampleRate, seconds);
        return SyntheticAudio.speech(sampleRate, seconds);
    }

    /**
     * Returns stereo music.
     *
     * @param sampleRate the sample rate in hertz
     * @param seconds    the duration
     * @return the left and right planes
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static float[][] music(int sampleRate, double seconds) {
        checkAudio(sampleRate, seconds);
        return SyntheticAudio.music(sampleRate, seconds);
    }

    /**
     * Returns an MP4 file with H.264 video and AAC audio.
     *
     * @param width   the width of the video
     * @param height  the height of the video
     * @param seconds the duration
     * @return the file
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static byte[] mp4(int width, int height, double seconds) {
        return SyntheticFiles.mp4(width, height, seconds);
    }

    /**
     * Returns an audio-only MP4 file with AAC audio.
     *
     * @param seconds the duration
     * @return the file
     * @throws IllegalArgumentException if {@code seconds} is not positive
     */
    public static byte[] m4a(double seconds) {
        return SyntheticFiles.m4a(seconds);
    }

    /**
     * Returns a looping animated GIF.
     *
     * @param width  the width in pixels
     * @param height the height in pixels
     * @param frames the number of frames
     * @return the file
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static byte[] animatedGif(int width, int height, int frames) {
        if (width <= 200 || height <= 150 || frames <= 0) {
            throw new IllegalArgumentException("an animated GIF needs more than 200x150 pixels and a frame: "
                    + width + "x" + height + ", " + frames);
        }
        return SyntheticImages.animatedGif(width, height, frames);
    }

    /**
     * Validates the arguments of synthesized audio.
     *
     * @param sampleRate the sample rate in hertz
     * @param seconds    the duration
     * @throws IllegalArgumentException if the rate is outside 8 to 192 kHz or the duration is not
     *                                  positive
     */
    private static void checkAudio(int sampleRate, double seconds) {
        if (sampleRate < MIN_RATE || sampleRate > MAX_RATE) {
            throw new IllegalArgumentException("sampleRate must be within " + MIN_RATE + " and " + MAX_RATE + ": "
                    + sampleRate);
        }
        if (!(seconds > 0)) {
            throw new IllegalArgumentException("seconds must be positive: " + seconds);
        }
    }
}

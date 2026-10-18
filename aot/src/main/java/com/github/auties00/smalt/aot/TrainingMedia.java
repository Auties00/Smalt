package com.github.auties00.smalt.aot;

import com.github.auties00.smalt.aot.internal.SyntheticMedia;
import com.github.auties00.smalt.image.YuvPicture;

import java.util.List;

/**
 * Synthesizes deterministic media for an application's own training workloads: the same video,
 * speech, music and files Smalt's {@link TrainingFamily families} train with.
 *
 * <p>A training run records the profiles of the branches its inputs take, so these inputs cover
 * the properties that steer the codecs: smooth natural texture with sub-sample motion, a flat band
 * and black frames whose blocks quantize to zero, speech with pauses of digital silence, and
 * harmonic music with transients. Equal arguments give equal media.
 */
public final class TrainingMedia {
    /**
     * Prevents instantiation.
     */
    private TrainingMedia() {
        throw new AssertionError();
    }

    /**
     * Returns natural-looking 4:2:0 video: smooth noise and waves panning at sub-sample speeds, a
     * moving bright spot, a flat band over the left quarter of every frame and a black frame every
     * sixth.
     *
     * @param width  the width in samples, positive
     * @param height the height in samples, positive
     * @param frames the number of frames, positive
     * @return the pictures, in limited-range BT.709
     * @throws IllegalArgumentException if an argument is not positive
     */
    public static List<YuvPicture> video(int width, int height, int frames) {
        return SyntheticMedia.video(width, height, frames);
    }

    /**
     * Returns mono speech: syllables of a voiced source gliding in pitch, or of noise, shaped by
     * formant resonators, with phrases separated by digital silence.
     *
     * @param sampleRate the sample rate in hertz, from 8000 to 192000
     * @param seconds    the duration, positive
     * @return the samples, peaking at 0.5
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static float[] speech(int sampleRate, double seconds) {
        return SyntheticMedia.speech(sampleRate, seconds);
    }

    /**
     * Returns stereo music: a chord progression of plucked harmonic notes, a bass line and a noise
     * hi-hat, panned differently in each channel.
     *
     * @param sampleRate the sample rate in hertz, from 8000 to 192000
     * @param seconds    the duration, positive
     * @return the left and right planes, each peaking at 0.45
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static float[][] music(int sampleRate, double seconds) {
        return SyntheticMedia.music(sampleRate, seconds);
    }

    /**
     * Returns an MP4 file with {@link #video} coded by the H.264 file encoder at 30 frames per
     * second and {@link #music} coded as 44.1 kHz stereo AAC.
     *
     * @param width   the width of the video, positive and even
     * @param height  the height of the video, positive and even
     * @param seconds the duration, positive
     * @return the file
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static byte[] mp4(int width, int height, double seconds) {
        return SyntheticMedia.mp4(width, height, seconds);
    }

    /**
     * Returns an audio-only MP4 (M4A) file with {@link #music} coded as 44.1 kHz stereo AAC.
     *
     * @param seconds the duration, positive
     * @return the file
     * @throws IllegalArgumentException if {@code seconds} is not positive
     */
    public static byte[] m4a(double seconds) {
        return SyntheticMedia.m4a(seconds);
    }

    /**
     * Returns a looping animated GIF of {@link #video}: a full first frame, then frames that update
     * a moving 200x150 window and leave unchanged pixels transparent.
     *
     * @param width  the width in pixels, more than 200
     * @param height the height in pixels, more than 150
     * @param frames the number of frames, positive
     * @return the file
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static byte[] animatedGif(int width, int height, int frames) {
        return SyntheticMedia.animatedGif(width, height, frames);
    }
}

package com.github.auties00.smalt.capture.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;

/**
 * Plays audio on an open output device.
 *
 * <p>The caller appends audio at {@link #sampleRate()} and {@link #channels()} to the session's
 * queue with one of the {@code write} methods, which block while the queue is full; a device
 * thread owned by the session moves it to the operating system every
 * {@linkplain AudioStreamOptions#period() period}. When the queue runs dry the device plays
 * silence, counted by {@link #underruns()}, and the session resumes with the next write, so a
 * late writer never accumulates delay. A writer paced by its own clock, such as a jitter buffer,
 * keeps the queue short by checking {@link #queued()} or {@link #delayMicros()} before each
 * write:
 * {@snippet :
 * var speaker = AudioDevices.defaultOutput().orElseThrow();
 * try (var session = AudioDevices.openPlayback(speaker, AudioStreamOptions.forVoice())) {
 *     var frame = new short[160];
 *     while (call.isActive()) {
 *         jitterBuffer.pull(frame); // 10 ms of 16 kHz mono
 *         session.write(frame, 0, 160);
 *     }
 *     session.drain();
 * }
 * }
 *
 * <p>When the device is unplugged or fails, the next write or {@link #drain()} throws a
 * {@link MediaException.Io} once, and every later write discards its audio.
 *
 * <p>A session is written by one thread at a time; {@link #close()} may be called from any
 * thread, also while another thread is blocked in a write, and the other methods are safe from
 * any thread. Implementations must be safe for a {@link #close()} from any thread while another
 * thread is blocked in a write, which then returns; {@link #close()} must be idempotent and release
 * the device.
 */
public interface AudioPlaybackSession extends AutoCloseable {
    /**
     * Returns the device this session plays on.
     *
     * @return the device
     */
    AudioDevice.Output device();

    /**
     * Returns the sampling rate the session expects.
     *
     * @return the rate in hertz
     */
    int sampleRate();

    /**
     * Returns the channel count the session expects.
     *
     * @return the channel count
     */
    int channels();

    /**
     * Returns the speaker layout the session expects.
     *
     * @return the layout, {@link ChannelLayout#defaultFor(int)} of {@link #channels()}
     */
    ChannelLayout layout();

    /**
     * Returns the number of frames the device thread moves to the device at a time.
     *
     * @return the frames of one {@linkplain AudioStreamOptions#period() period}
     */
    int periodFrames();

    /**
     * Returns the capacity of the session's queue.
     *
     * @return the largest number of frames the queue holds before a write blocks
     */
    int bufferFrames();

    /**
     * Returns the latency of the device buffer the operating system granted.
     *
     * @return the latency in microseconds
     */
    long latencyMicros();

    /**
     * Queues a buffer of planar samples, blocking while the queue is full.
     *
     * @param buffer the samples in {@code [-1, 1]}; its sampling rate and channel count must be
     * the session's
     * @return the number of frames queued, {@code buffer.frames()} unless the session ended
     * meanwhile
     * @throws IllegalArgumentException if the buffer's rate or channel count differs from the
     * session's
     * @throws InterruptedException if the calling thread is interrupted while waiting; the
     * frames queued so far stay queued
     * @throws MediaException.Io if the device failed, once
     */
    int write(AudioBuffer buffer) throws InterruptedException;

    /**
     * Queues interleaved float samples, blocking while the queue is full.
     *
     * @param samples the {@code frames * channels()} samples in {@code [-1, 1]}; values beyond are
     * clamped
     * @param offset the index of the first sample
     * @param frames the number of frames; non-negative
     * @return the number of frames queued, {@code frames} unless the session ended meanwhile
     * @throws IndexOutOfBoundsException if the frames do not fit in {@code samples} from
     * {@code offset}
     * @throws InterruptedException if the calling thread is interrupted while waiting; the
     * frames queued so far stay queued
     * @throws MediaException.Io if the device failed, once
     */
    int write(float[] samples, int offset, int frames) throws InterruptedException;

    /**
     * Queues interleaved signed 16-bit samples, blocking while the queue is full.
     *
     * <p>Samples {@code s} convert to {@code s / 32768f}.
     *
     * @param samples the {@code frames * channels()} samples
     * @param offset the index of the first sample
     * @param frames the number of frames; non-negative
     * @return the number of frames queued, {@code frames} unless the session ended meanwhile
     * @throws IndexOutOfBoundsException if the frames do not fit in {@code samples} from
     * {@code offset}
     * @throws InterruptedException if the calling thread is interrupted while waiting; the
     * frames queued so far stay queued
     * @throws MediaException.Io if the device failed, once
     */
    int write(short[] samples, int offset, int frames) throws InterruptedException;

    /**
     * Returns the number of frames queued and not yet handed to the device.
     *
     * @return the frames in the queue
     */
    int queued();

    /**
     * Returns the number of frames a write can queue without blocking.
     *
     * @return {@link #bufferFrames()} minus {@link #queued()}
     */
    int available();

    /**
     * Returns how long a frame written now waits before the device plays it: the session's queue
     * plus the device buffer and the device's own latency.
     *
     * <p>Called right before a write, this is the render half of the delay an echo canceller
     * needs (see {@link AudioDevices}).
     *
     * @return the delay in microseconds
     */
    long delayMicros();

    /**
     * Returns the number of frames of silence the device played because the queue ran dry after
     * the first write.
     *
     * @return the silent frames since the first write
     */
    long underruns();

    /**
     * Waits until every queued frame has been handed to the device and played.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws MediaException.Io if the device failed, once
     */
    void drain() throws InterruptedException;

    /**
     * Stops playing, discarding the queued audio, and releases the device; a write blocked in
     * another thread returns. Closing a closed session does nothing.
     */
    @Override
    void close();
}

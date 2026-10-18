package com.github.auties00.smalt.capture.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;

/**
 * Delivers the audio of an open input device, or of an output device's loopback.
 *
 * <p>A device thread owned by the session receives the audio from the operating system every
 * {@linkplain AudioStreamOptions#period() period} and appends it to the session's queue; the
 * caller pulls it at {@link #sampleRate()} and {@link #channels()} with one of the {@code read}
 * methods, which block until enough audio has arrived. A caller slower than the device loses the
 * oldest audio once the queue holds {@link #bufferFrames()} frames, counted by
 * {@link #overruns()}, so the delay never grows beyond the queue. Like every Smalt reader a
 * session pulls: {@link #read()} returns {@code null} and the array overloads return {@code -1}
 * at the end, here once the session is closed or the device stopped. A session is
 * {@link AutoCloseable}:
 * {@snippet :
 * var microphone = AudioDevices.defaultInput().orElseThrow();
 * try (var session = AudioDevices.openCapture(microphone, AudioStreamOptions.forVoice())) {
 *     var frame = new short[160];
 *     while (session.read(frame, 0, 160) == 160) {
 *         encode(frame); // 10 ms of 16 kHz mono
 *     }
 * }
 * }
 *
 * <p>When the device is unplugged or fails, the next read throws a {@link MediaException.Io}
 * once, after the audio captured before the failure, and every later read reports the end.
 *
 * <p>A session is read by one thread at a time; {@link #close()} may be called from any thread,
 * also while another thread is blocked in a read, and the other methods are safe from any
 * thread. Implementations must be safe for a {@link #close()} from any thread while another thread
 * is blocked in a read, which then returns the end; {@link #close()} must be idempotent and release
 * the device.
 */
public interface AudioCaptureSession extends AutoCloseable {
    /**
     * Returns the device this session captures: an {@link AudioDevice.Input}, or the
     * {@link AudioDevice.Output} of a loopback session.
     *
     * @return the device
     */
    AudioDevice device();

    /**
     * Returns the sampling rate of the delivered audio.
     *
     * @return the rate in hertz
     */
    int sampleRate();

    /**
     * Returns the channel count of the delivered audio.
     *
     * @return the channel count
     */
    int channels();

    /**
     * Returns the speaker layout of the delivered audio.
     *
     * @return the layout, {@link ChannelLayout#defaultFor(int)} of {@link #channels()}
     */
    ChannelLayout layout();

    /**
     * Returns the number of frames {@link #read()} delivers at a time.
     *
     * @return the frames of one {@linkplain AudioStreamOptions#period() period}
     */
    int periodFrames();

    /**
     * Returns the capacity of the session's queue.
     *
     * @return the largest number of frames the queue holds before the oldest are dropped
     */
    int bufferFrames();

    /**
     * Returns the latency of the device buffer the operating system granted.
     *
     * @return the latency in microseconds
     */
    long latencyMicros();

    /**
     * Returns the next period of audio, blocking until it has arrived.
     *
     * @return planar float samples in {@code [-1, 1]} of {@link #periodFrames()} frames (fewer
     * only for the last chunk before the end), owned by the session and valid until the
     * next call; or {@code null} once the session is closed or the device stopped
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws MediaException.Io if the device failed, once
     */
    AudioBuffer read() throws InterruptedException;

    /**
     * Reads interleaved float samples, blocking until {@code frames} frames have arrived.
     *
     * <p>A read longer than {@link #bufferFrames()} is served in parts as the audio arrives.
     *
     * @param samples receives {@code frames * channels()} samples in {@code [-1, 1]}
     * @param offset the index of the first sample written
     * @param frames the number of frames to read; non-negative
     * @return the number of frames read, {@code frames} unless the session ended meanwhile or a
     * read longer than half the queue was interrupted part-way (then the interrupt status
     * is set); or {@code -1} when the session had already ended
     * @throws IndexOutOfBoundsException if the frames do not fit in {@code samples} from
     * {@code offset}
     * @throws InterruptedException if the calling thread is interrupted before any frame was
     * read; the queued audio stays queued
     * @throws MediaException.Io if the device failed, once
     */
    int read(float[] samples, int offset, int frames) throws InterruptedException;

    /**
     * Reads interleaved signed 16-bit samples, blocking until {@code frames} frames have arrived.
     *
     * <p>Float samples {@code f} convert to {@code round(f * 32768)} clamped to the 16-bit range.
     *
     * @param samples receives {@code frames * channels()} samples
     * @param offset the index of the first sample written
     * @param frames the number of frames to read; non-negative
     * @return the number of frames read, {@code frames} unless the session ended meanwhile or a
     * read longer than half the queue was interrupted part-way (then the interrupt status
     * is set); or {@code -1} when the session had already ended
     * @throws IndexOutOfBoundsException if the frames do not fit in {@code samples} from
     * {@code offset}
     * @throws InterruptedException if the calling thread is interrupted before any frame was
     * read; the queued audio stays queued
     * @throws MediaException.Io if the device failed, once
     */
    int read(short[] samples, int offset, int frames) throws InterruptedException;

    /**
     * Returns the number of frames a read can return without blocking.
     *
     * @return the frames in the queue
     */
    int available();

    /**
     * Returns the capture time of the first frame the last read returned.
     *
     * @return the time in microseconds on a monotonic clock that starts near zero when the session
     * opens; {@code 0} before the first read
     */
    long timestampMicros();

    /**
     * Returns how long ago the device captured the first frame the last read returned.
     *
     * <p>Called right after a read, this is the capture half of the delay an echo canceller needs
     * (see {@link AudioDevices}).
     *
     * @return the age in microseconds; {@code 0} before the first read
     */
    long delayMicros();

    /**
     * Returns the number of frames dropped because the reader fell behind by more than
     * {@link #bufferFrames()}.
     *
     * @return the dropped frames since the session opened
     */
    long overruns();

    /**
     * Stops capturing and releases the device; a read blocked in another thread returns the end.
     * Closing a closed session does nothing.
     */
    @Override
    void close();
}

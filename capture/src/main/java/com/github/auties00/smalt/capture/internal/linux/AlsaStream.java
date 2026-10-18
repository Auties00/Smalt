package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;
import com.github.auties00.smalt.capture.audio.AudioShareMode;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import com.github.auties00.smalt.capture.internal.PumpThread;
import com.github.auties00.smalt.capture.internal.audio.DeviceEngine;
import com.github.auties00.smalt.capture.internal.audio.RingCaptureSession;
import com.github.auties00.smalt.capture.internal.audio.RingPlaybackSession;
import com.github.auties00.smalt.capture.internal.audio.SampleCodec;
import com.github.auties00.smalt.capture.internal.audio.StreamPlan;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Streams one ALSA PCM: a capture or a playback.
 *
 * <p>The stream owns a platform thread at the highest Java priority that opens the PCM without
 * blocking, configures it with {@code snd_pcm_set_params} (interleaved access, the caller's rate
 * and channel count, a buffer of the requested latency, and software resampling unless the
 * exclusive mode was asked for) in the first encoding the device accepts (float, 32-bit, packed
 * 24-bit, then 16-bit), and then waits on the PCM with {@code snd_pcm_wait}, so it notices a
 * close within {@value #WAIT_MILLIS} ms. Overruns, underruns and suspends are recovered with
 * {@code snd_pcm_recover}; a vanished device ({@code -ENODEV}, {@code -EBADFD}) fails the
 * session.
 *
 * <p>Captured chunks are stamped from {@code snd_pcm_delay}, which counts the frames captured but
 * not yet read. A playback stream keeps the device buffer full and, after each write, reports
 * {@code snd_pcm_delay}, the frames until a frame written next is heard.
 */
final class AlsaStream implements DeviceEngine, Runnable {
    /**
     * The longest wait on the PCM, in milliseconds.
     */
    private static final int WAIT_MILLIS = 50;

    /**
     * The longest wait for the stream thread to open the PCM.
     */
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The longest wait for the stream thread to stop.
     */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(3);

    /**
     * The sampling rate of a converting device whose options leave the format open.
     */
    private static final int DEFAULT_RATE = 48_000;

    /**
     * The channel count of a converting device whose options leave the format open.
     */
    private static final int DEFAULT_CHANNELS = 2;

    /**
     * The encodings tried, in order of preference.
     */
    private static final List<AudioSampleFormat> ENCODINGS = List.of(AudioSampleFormat.FLOAT32, AudioSampleFormat.PCM_S32,
            AudioSampleFormat.PCM_S24, AudioSampleFormat.PCM_S16);

    /**
     * Whether the stream plays rather than captures.
     */
    private final boolean playback;

    /**
     * The device.
     */
    private final AudioDevice device;

    /**
     * The stream options.
     */
    private final AudioStreamOptions options;

    /**
     * The stream thread.
     */
    private final PumpThread pump;

    /**
     * Whether {@link #stop()} was called.
     */
    private volatile boolean stopping;

    /**
     * The capture session, set before the pump is told the PCM is open.
     */
    private volatile RingCaptureSession captureSession;

    /**
     * The playback session, set before the pump is told the PCM is open.
     */
    private volatile RingPlaybackSession playbackSession;

    /**
     * The PCM, owned by the stream thread.
     */
    private MemorySegment pcm = MemorySegment.NULL;

    /**
     * The encoding of the PCM.
     */
    private AudioSampleFormat sampleFormat;

    /**
     * The stream geometry.
     */
    private StreamPlan plan;

    /**
     * The device buffer in frames.
     */
    private int bufferFrames;

    /**
     * Creates the state of a stream.
     *
     * @param playback whether the stream plays
     * @param options the stream options
     */
    private AlsaStream(boolean playback, AudioDevice device, AudioStreamOptions options) {
        this.playback = playback;
        this.device = device;
        this.options = options;
        this.pump = new PumpThread(playback ? "smalt-audio-playback" : "smalt-audio-capture", Thread.MAX_PRIORITY);
    }

    /**
     * Opens an input device.
     *
     * @param options the stream options
     * @return the session
     * @throws MediaException.Io if the device cannot be opened
     * @throws MediaException.Unsupported if the device accepts no encoding at the format
     */
    static AudioCaptureSession openCapture(AudioDevice.Input device, AudioStreamOptions options) {
        return start(new AlsaStream(false, device, options)).captureSession;
    }

    /**
     * Opens an output device.
     *
     * @param options the stream options
     * @return the session
     * @throws MediaException.Io if the device cannot be opened
     * @throws MediaException.Unsupported if the device accepts no encoding at the format
     */
    static AudioPlaybackSession openPlayback(AudioDevice.Output device, AudioStreamOptions options) {
        return start(new AlsaStream(true, device, options)).playbackSession;
    }

    /**
     * Starts the stream thread and waits until it opened the PCM.
     *
     * @return the stream
     * @throws MediaException if opening fails or times out
     */
    private static AlsaStream start(AlsaStream stream) {
        stream.pump.start(stream);
        stream.pump.awaitOpen(OPEN_TIMEOUT, "the audio device " + stream.device.name(), stream::stop);
        return stream;
    }

    @Override
    public void stop() {
        stopping = true;
        pump.join(STOP_TIMEOUT);
    }

    @Override
    public void run() {
        try (var arena = Arena.ofConfined()) {
            try {
                openPcm(arena);
            } catch (MediaException exception) {
                pump.failOpen(exception);
                return;
            } catch (RuntimeException exception) {
                var message = "cannot open the audio device " + device.name();
                pump.failOpen(new MediaException.Io(message, new IOException(message, exception)));
                return;
            } finally {
                pump.markOpen();
            }
            if (stopping) {
                return;
            }
            try {
                if (playback) {
                    play(arena);
                } else {
                    capture(arena);
                }
            } catch (MediaException exception) {
                fail(exception);
            } catch (RuntimeException exception) {
                var message = "the audio device " + device.name() + " failed";
                fail(new MediaException.Io(message, new IOException(message, exception)));
            }
        } finally {
            if (!pcm.equals(MemorySegment.NULL)) {
                Alsa.drop(pcm);
                Alsa.close(pcm);
                pcm = MemorySegment.NULL;
            }
            if (captureSession != null) {
                captureSession.end();
            }
        }
    }

    /**
     * Reports a failure of the running stream to its session.
     *
     * @param exception the failure
     */
    private void fail(MediaException exception) {
        if (captureSession != null) {
            captureSession.fail(exception);
        }
        if (playbackSession != null) {
            playbackSession.fail(exception);
        }
    }

    /**
     * Opens and configures the PCM and creates the session.
     *
     * @param arena the arena of the stream thread
     * @throws MediaException.Io if the PCM cannot be opened
     * @throws MediaException.Unsupported if the PCM accepts no encoding at the format
     */
    private void openPcm(Arena arena) {
        var nativeFormat = device.formats().isEmpty() ? null : device.formats().getFirst();
        plan = StreamPlan.resolve(options, nativeFormat == null ? DEFAULT_RATE : nativeFormat.sampleRate(),
                nativeFormat == null ? DEFAULT_CHANNELS : nativeFormat.channels());
        var slot = arena.allocate(ADDRESS);
        var result = Alsa.open(device.id(), playback ? Alsa.STREAM_PLAYBACK : Alsa.STREAM_CAPTURE, Alsa.NONBLOCK, arena, slot);
        if (result < 0) {
            throw Alsa.failure(result == Alsa.EBUSY ? "the audio device " + device.name() + " is used by another application"
                    : "cannot open the audio device " + device.name(), result);
        }
        pcm = slot.get(ADDRESS, 0);
        var latencyMicros = (int) Math.min(Integer.MAX_VALUE, plan.latencyNanos() / 1_000);
        var resample = options.shareMode() != AudioShareMode.EXCLUSIVE;
        var last = 0;
        for (var encoding : ENCODINGS) {
            last = Alsa.setParams(pcm, Alsa.format(encoding), plan.channels(), plan.sampleRate(), resample, latencyMicros);
            if (last == 0) {
                sampleFormat = encoding;
                break;
            }
        }
        if (sampleFormat == null) {
            throw new MediaException.Unsupported("audio device format", "the audio device " + device.name() + " cannot run at "
                    + plan.sampleRate() + " Hz with " + plan.channels() + " channels: " + Alsa.describe(last));
        }
        var sizes = arena.allocate(JAVA_LONG, 2);
        if (Alsa.getParams(pcm, sizes) == 0) {
            bufferFrames = (int) Math.min(Integer.MAX_VALUE / 64, sizes.get(JAVA_LONG, 0));
        } else {
            bufferFrames = (int) (plan.latencyNanos() * plan.sampleRate() / 1_000_000_000L);
        }
        var grantedNanos = plan.nanos(bufferFrames);
        if (playback) {
            var session = new RingPlaybackSession((AudioDevice.Output) device, plan);
            session.attach(this, grantedNanos);
            playbackSession = session;
        } else {
            var session = new RingCaptureSession(device, plan);
            session.attach(this, grantedNanos);
            captureSession = session;
        }
    }

    /**
     * Delivers captured chunks to the session until it stops.
     *
     * @param arena the arena of the stream thread
     */
    private void capture(Arena arena) {
        var session = captureSession;
        var channels = plan.channels();
        var frameBytes = (long) channels * sampleFormat.bytes();
        var chunk = plan.periodFrames();
        var buffer = arena.allocate(chunk * frameBytes, 16);
        var samples = new float[chunk * channels];
        var scratch = arena.allocate(JAVA_LONG);
        Alsa.start(pcm);
        while (!stopping && !session.stopped()) {
            var ready = Alsa.await(pcm, WAIT_MILLIS);
            if (ready < 0) {
                recover(ready);
                continue;
            }
            var count = Alsa.readi(pcm, buffer, chunk);
            if (count == Alsa.EAGAIN || count == 0) {
                continue;
            }
            if (count < 0) {
                recover((int) count);
                continue;
            }
            var now = System.nanoTime();
            var pending = Math.max(0, Alsa.delay(pcm, scratch));
            SampleCodec.decode(sampleFormat, buffer, samples, 0, (int) count * channels);
            session.deliver(samples, (int) count, now - plan.nanos(pending + count));
        }
    }

    /**
     * Keeps the device buffer filled from the session until it stops.
     *
     * @param arena the arena of the stream thread
     */
    private void play(Arena arena) {
        var session = playbackSession;
        var channels = plan.channels();
        var frameBytes = (long) channels * sampleFormat.bytes();
        var capacity = Math.max(bufferFrames, plan.periodFrames());
        var buffer = arena.allocate(capacity * frameBytes, 16);
        var samples = new float[capacity * channels];
        var scratch = arena.allocate(JAVA_LONG);
        while (!stopping && !session.stopped()) {
            var ready = Alsa.await(pcm, WAIT_MILLIS);
            if (ready < 0) {
                recover(ready);
                continue;
            }
            var free = Alsa.availUpdate(pcm);
            if (free < 0) {
                recover((int) free);
                continue;
            }
            var count = (int) Math.min(free, capacity);
            if (count == 0) {
                continue;
            }
            session.render(samples, count);
            SampleCodec.encode(sampleFormat, samples, 0, buffer, count * channels);
            var written = Alsa.writei(pcm, buffer, count);
            if (written < 0 && written != Alsa.EAGAIN) {
                recover((int) written);
                continue;
            }
            var pending = Alsa.delay(pcm, scratch);
            if (pending >= 0) {
                session.reportDeviceDelay(plan.nanos(pending));
            }
        }
    }

    /**
     * Recovers from an error, or fails when the device is gone.
     *
     * @param error the negative error
     * @throws MediaException.Io if the error cannot be recovered
     */
    private void recover(int error) {
        if (error == Alsa.EINTR || error == Alsa.EAGAIN) {
            return;
        }
        if (error == Alsa.ENODEV || error == Alsa.EBADFD) {
            throw Alsa.failure("the audio device " + device.name() + " was removed", error);
        }
        var result = Alsa.recover(pcm, error);
        if (result < 0) {
            throw Alsa.failure("the audio device " + device.name() + " failed", result);
        }
        if (!playback) {
            Alsa.start(pcm);
        }
    }
}

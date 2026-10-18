package com.github.auties00.smalt.capture.internal.audio;

import com.github.auties00.smalt.capture.audio.AudioDeviceEvent;
import com.github.auties00.smalt.capture.audio.AudioDeviceListener;
import com.github.auties00.smalt.capture.internal.PumpThread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Delivers the device changes of the {@link AudioBackend} to the registered
 * {@link AudioDeviceListener}s.
 *
 * <p>The backend's watch runs while at least one listener is registered. It hands events to a
 * queue without blocking, whatever thread the operating system reports them on, and a dispatch
 * thread of this hub ({@value #THREAD_NAME}) takes them in order and calls every listener
 * registered at that time, so a listener may block or call back into the audio API. The hub is
 * thread-safe.
 */
public final class DeviceEventHub {
    /**
     * The name of the dispatch thread.
     */
    private static final String THREAD_NAME = "smalt-audio-events";

    /**
     * The longest wait, when the last listener is removed, for the dispatch thread to finish the
     * event it is delivering.
     */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(1);

    /**
     * The hub of the process.
     */
    private static final DeviceEventHub INSTANCE = new DeviceEventHub();

    /**
     * The token that stops the dispatch thread.
     */
    private static final Object STOP = new Object();

    /**
     * Guards every field below.
     */
    private final Object lock = new Object();

    /**
     * The registered listeners, in registration order.
     */
    private final List<AudioDeviceListener> listeners = new ArrayList<>();

    /**
     * An immutable copy of {@link #listeners}, read by the dispatch thread without the lock.
     */
    private volatile List<AudioDeviceListener> snapshot = List.of();

    /**
     * The backend's watch while listeners are registered, or {@code null}.
     */
    private AudioBackend.DeviceWatch watch;

    /**
     * The events waiting for dispatch, followed by {@link #STOP} once the watch stops.
     */
    private LinkedBlockingQueue<Object> queue;

    /**
     * The dispatch thread while listeners are registered, or {@code null}.
     */
    private Thread dispatcher;

    /**
     * Creates the hub.
     */
    private DeviceEventHub() {
    }

    /**
     * Returns the hub of the process.
     *
     * @return the hub
     */
    public static DeviceEventHub instance() {
        return INSTANCE;
    }

    /**
     * Registers a listener, starting the backend's watch if it is the first.
     *
     * @throws com.github.auties00.smalt.MediaException.Io if the watch cannot be started; the
     * listener is then not registered
     */
    public void add(AudioDeviceListener listener) {
        synchronized (lock) {
            if (watch == null) {
                start();
            }
            listeners.add(listener);
            snapshot = List.copyOf(listeners);
        }
    }

    /**
     * Unregisters a listener, stopping the backend's watch if it was the last.
     *
     * @param listener the listener; an unknown one is ignored
     */
    public void remove(AudioDeviceListener listener) {
        Thread stopped = null;
        synchronized (lock) {
            if (!listeners.remove(listener)) {
                return;
            }
            snapshot = List.copyOf(listeners);
            if (listeners.isEmpty()) {
                watch.close();
                watch = null;
                queue.offer(STOP);
                stopped = dispatcher;
                dispatcher = null;
                queue = null;
            }
        }
        if (stopped != null && stopped != Thread.currentThread()) {
            PumpThread.join(stopped, STOP_TIMEOUT);
        }
    }

    /**
     * Starts the dispatch thread and the backend's watch; called with the lock held.
     */
    private void start() {
        var events = new LinkedBlockingQueue<>();
        var thread = Thread.ofPlatform().name(THREAD_NAME).daemon().unstarted(() -> dispatch(events));
        thread.start();
        try {
            watch = AudioBackend.current().watch(events::offer);
        } catch (RuntimeException | Error failure) {
            events.offer(STOP);
            throw failure;
        }
        queue = events;
        dispatcher = thread;
    }

    /**
     * Delivers queued events until the stop token arrives.
     *
     * @param events the queue
     */
    private void dispatch(LinkedBlockingQueue<Object> events) {
        while (true) {
            Object next;
            try {
                next = events.take();
            } catch (InterruptedException exception) {
                return;
            }
            if (next == STOP) {
                return;
            }
            var event = (AudioDeviceEvent) next;
            for (var listener : snapshot) {
                try {
                    listener.onEvent(event);
                } catch (Throwable ignored) {
                    // a failing listener must not stop the delivery to the others
                }
            }
        }
    }
}

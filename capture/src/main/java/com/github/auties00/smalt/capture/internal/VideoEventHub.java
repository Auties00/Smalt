package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.capture.VideoDeviceEvent;
import com.github.auties00.smalt.capture.VideoDeviceListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Delivers the camera and screen changes to the registered {@link VideoDeviceListener}s.
 *
 * <p>The device watch runs while at least one listener is registered. It hands events to a queue without
 * blocking, and a dispatch thread of this hub ({@value #THREAD_NAME}) takes them in order and calls every
 * listener registered at that time, so a listener may block or call back into the capture API. The hub is
 * thread-safe.
 */
public final class VideoEventHub {
    /**
     * The name of the dispatch thread.
     */
    static final String THREAD_NAME = "smalt-video-events";

    /**
     * The longest wait, when the last listener is removed, for the dispatch thread to finish the event it
     * is delivering.
     */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(1);

    /**
     * The token that stops the dispatch thread.
     */
    private static final Object STOP = new Object();

    /**
     * Starts the device watch over a sink of events and returns what stops it.
     */
    private final Function<Consumer<VideoDeviceEvent>, AutoCloseable> starter;

    /**
     * Guards every field below.
     */
    private final Object lock = new Object();

    /**
     * The registered listeners, in registration order.
     */
    private final List<VideoDeviceListener> listeners = new ArrayList<>();

    /**
     * An immutable copy of {@link #listeners}, read by the dispatch thread without the lock.
     */
    private volatile List<VideoDeviceListener> snapshot = List.of();

    /**
     * The running watch while listeners are registered, or {@code null}.
     */
    private AutoCloseable watch;

    /**
     * The events waiting for dispatch, followed by {@link #STOP} once the watch stops.
     */
    private LinkedBlockingQueue<Object> queue;

    /**
     * The dispatch thread while listeners are registered, or {@code null}.
     */
    private Thread dispatcher;

    /**
     * Creates a hub.
     *
     * @param starter starts the device watch over a sink of events, raising
     * {@link com.github.auties00.smalt.MediaException.Io} when it cannot, and returns what stops it
     */
    public VideoEventHub(Function<Consumer<VideoDeviceEvent>, AutoCloseable> starter) {
        this.starter = Objects.requireNonNull(starter, "starter");
    }

    /**
     * Returns the hub of the process, watching the devices of {@link CaptureBackend#current()}.
     *
     * @return the hub
     */
    public static VideoEventHub instance() {
        return Holder.INSTANCE;
    }

    /**
     * Registers a listener, starting the watch if it is the first.
     *
     * @throws com.github.auties00.smalt.MediaException.Io if the watch cannot be started; the listener is
     * then not registered
     */
    public void add(VideoDeviceListener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (watch == null) {
                start();
            }
            listeners.add(listener);
            snapshot = List.copyOf(listeners);
        }
    }

    /**
     * Unregisters a listener, stopping the watch if it was the last.
     *
     * @param listener the listener; an unknown one is ignored
     */
    public void remove(VideoDeviceListener listener) {
        Objects.requireNonNull(listener, "listener");
        Thread stopped = null;
        AutoCloseable closing = null;
        synchronized (lock) {
            if (!listeners.remove(listener)) {
                return;
            }
            snapshot = List.copyOf(listeners);
            if (listeners.isEmpty()) {
                closing = watch;
                watch = null;
                queue.offer(STOP);
                stopped = dispatcher;
                dispatcher = null;
                queue = null;
            }
        }
        if (closing != null) {
            try {
                closing.close();
            } catch (Exception ignored) {
                // the watch is stopped either way; nothing is left to release
            }
        }
        if (stopped != null && stopped != Thread.currentThread()) {
            PumpThread.join(stopped, STOP_TIMEOUT);
        }
    }

    /**
     * Returns whether the watch is running, that is whether a listener is registered.
     *
     * @return whether it runs
     */
    public boolean watching() {
        synchronized (lock) {
            return watch != null;
        }
    }

    /**
     * Starts the dispatch thread and the watch; called with the lock held.
     */
    private void start() {
        var events = new LinkedBlockingQueue<>();
        var thread = Thread.ofPlatform().name(THREAD_NAME).daemon().unstarted(() -> dispatch(events));
        thread.start();
        try {
            watch = Objects.requireNonNull(starter.apply(events::offer), "watch");
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
            var event = (VideoDeviceEvent) next;
            for (var listener : snapshot) {
                try {
                    listener.onEvent(event);
                } catch (Throwable ignored) {
                    // a failing listener must not stop the delivery to the others
                }
            }
        }
    }

    /**
     * Holds the hub of the process.
     */
    private static final class Holder {
        private static final VideoEventHub INSTANCE = new VideoEventHub(sink -> CaptureBackend.current().watch(sink));

        private Holder() {
            throw new AssertionError();
        }
    }
}

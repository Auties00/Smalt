package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.VideoDeviceEvent;
import com.github.auties00.smalt.capture.VideoDeviceListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the listener registry and the dispatch thread of {@link VideoEventHub} over a fake watch whose sink
 * the test drives.
 */
@DisplayName("video event hub")
class VideoEventHubTest {
    /**
     * A watch the hub starts, recording its sink and how often it was started and stopped.
     */
    private static final class FakeWatch {
        final AtomicReference<Consumer<VideoDeviceEvent>> sink = new AtomicReference<>();
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger stopped = new AtomicInteger();

        AutoCloseable start(Consumer<VideoDeviceEvent> sink) {
            this.sink.set(sink);
            started.incrementAndGet();
            return stopped::incrementAndGet;
        }

        void emit(VideoDeviceEvent event) {
            sink.get().accept(event);
        }
    }

    @Test
    @DisplayName("the watch starts with the first listener and stops with the last")
    void lifecycle() {
        var watch = new FakeWatch();
        var hub = new VideoEventHub(watch::start);
        VideoDeviceListener first = event -> {
        };
        VideoDeviceListener second = event -> {
        };
        assertFalse(hub.watching());
        hub.add(first);
        hub.add(second);
        assertEquals(1, watch.started.get());
        assertTrue(hub.watching());
        hub.remove(first);
        assertEquals(0, watch.stopped.get());
        hub.remove(first);
        hub.remove(second);
        assertEquals(1, watch.stopped.get());
        assertFalse(hub.watching());
        hub.add(first);
        assertEquals(2, watch.started.get());
        hub.remove(first);
        assertEquals(2, watch.stopped.get());
    }

    @Test
    @DisplayName("events reach every listener in order on the dispatch thread, a failing listener included")
    void dispatch() throws InterruptedException {
        var watch = new FakeWatch();
        var hub = new VideoEventHub(watch::start);
        var received = new LinkedBlockingQueue<String>();
        var threads = new CopyOnWriteArrayList<String>();
        VideoDeviceListener failing = event -> {
            received.add("failing " + event);
            throw new IllegalStateException("listener failure");
        };
        VideoDeviceListener recording = event -> {
            threads.add(Thread.currentThread().getName());
            received.add("recording " + event);
        };
        hub.add(failing);
        hub.add(recording);
        try {
            var added = new VideoDeviceEvent.CameraAdded("camera");
            var removed = new VideoDeviceEvent.ScreenRemoved("screen");
            watch.emit(added);
            watch.emit(removed);
            assertEquals("failing " + added, received.poll(5, TimeUnit.SECONDS));
            assertEquals("recording " + added, received.poll(5, TimeUnit.SECONDS));
            assertEquals("failing " + removed, received.poll(5, TimeUnit.SECONDS));
            assertEquals("recording " + removed, received.poll(5, TimeUnit.SECONDS));
            assertEquals(List.of(VideoEventHub.THREAD_NAME, VideoEventHub.THREAD_NAME), threads);
            assertNotEquals(Thread.currentThread().getName(), threads.getFirst());
        } finally {
            hub.remove(failing);
            hub.remove(recording);
        }
    }

    @Test
    @DisplayName("a listener may remove itself and add another while an event is delivered")
    void reentrant() throws InterruptedException {
        var watch = new FakeWatch();
        var hub = new VideoEventHub(watch::start);
        var delivered = new CountDownLatch(1);
        var later = new LinkedBlockingQueue<VideoDeviceEvent>();
        VideoDeviceListener next = later::add;
        var self = new AtomicReference<VideoDeviceListener>();
        self.set(event -> {
            hub.add(next);
            hub.remove(self.get());
            delivered.countDown();
        });
        hub.add(self.get());
        watch.emit(new VideoDeviceEvent.CameraAdded("one"));
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        var second = new VideoDeviceEvent.CameraAdded("two");
        watch.emit(second);
        assertEquals(second, later.poll(5, TimeUnit.SECONDS));
        hub.remove(next);
        assertFalse(hub.watching());
    }

    @Test
    @DisplayName("a watch that cannot start leaves the listener unregistered and the hub idle")
    void startFailure() {
        var attempts = new AtomicInteger();
        var hub = new VideoEventHub(sink -> {
            attempts.incrementAndGet();
            throw new MediaException.Io("no notifications", new IOException("refused"));
        });
        VideoDeviceListener listener = event -> {
        };
        assertThrows(MediaException.Io.class, () -> hub.add(listener));
        assertFalse(hub.watching());
        hub.remove(listener);
        assertThrows(MediaException.Io.class, () -> hub.add(listener));
        assertEquals(2, attempts.get());
    }

    @Test
    @DisplayName("events emitted after the last listener left are not delivered")
    void afterRemoval() throws InterruptedException {
        var watch = new FakeWatch();
        var hub = new VideoEventHub(watch::start);
        var received = new LinkedBlockingQueue<VideoDeviceEvent>();
        VideoDeviceListener listener = received::add;
        hub.add(listener);
        var sink = watch.sink.get();
        hub.remove(listener);
        sink.accept(new VideoDeviceEvent.CameraAdded("late"));
        assertNull(received.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("null listeners are rejected")
    void nulls() {
        var hub = new VideoEventHub(new FakeWatch()::start);
        assertThrows(NullPointerException.class, () -> hub.add(null));
        assertThrows(NullPointerException.class, () -> hub.remove(null));
        assertThrows(NullPointerException.class, () -> new VideoEventHub(null));
    }
}

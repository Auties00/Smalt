package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.VideoDeviceEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the comparison of device listings and the polling thread of {@link VideoDeviceWatch} against fake
 * listings, so the events a platform watch reports follow from the lists alone.
 */
@DisplayName("video device watch")
class VideoDeviceWatchTest {
    private static final CaptureDevice.Screen LEFT = new CaptureDevice.Screen("left", "Left", 0, 0, 1920, 1080, true);

    private static final CaptureDevice.Screen RIGHT = new CaptureDevice.Screen("right", "Right", 1920, 0, 1280, 1024, false);

    private static VideoDeviceWatch.Snapshot snapshot(List<String> cameras, CaptureDevice.Screen... screens) {
        return VideoDeviceWatch.Snapshot.of(cameras, List.of(screens));
    }

    @Nested
    @DisplayName("diff")
    class Diff {
        @Test
        @DisplayName("identical listings report nothing")
        void identical() {
            var listing = snapshot(List.of("a", "b"), LEFT, RIGHT);
            assertEquals(List.of(), VideoDeviceWatch.diff(listing, listing));
        }

        @Test
        @DisplayName("a camera plugged in after the default reports only its arrival")
        void cameraAdded() {
            assertEquals(List.of(new VideoDeviceEvent.CameraAdded("b")),
                    VideoDeviceWatch.diff(snapshot(List.of("a"), LEFT), snapshot(List.of("a", "b"), LEFT)));
        }

        @Test
        @DisplayName("the first camera of an empty system is added and becomes the default")
        void firstCamera() {
            assertEquals(List.of(new VideoDeviceEvent.CameraAdded("a"), new VideoDeviceEvent.DefaultCameraChanged(Optional.of("a"))),
                    VideoDeviceWatch.diff(snapshot(List.of(), LEFT), snapshot(List.of("a"), LEFT)));
        }

        @Test
        @DisplayName("removing the default camera reports the removal, then the new default")
        void defaultRemoved() {
            assertEquals(List.of(new VideoDeviceEvent.CameraRemoved("a"), new VideoDeviceEvent.DefaultCameraChanged(Optional.of("b"))),
                    VideoDeviceWatch.diff(snapshot(List.of("a", "b")), snapshot(List.of("b"))));
            assertEquals(List.of(new VideoDeviceEvent.CameraRemoved("b"), new VideoDeviceEvent.DefaultCameraChanged(Optional.empty())),
                    VideoDeviceWatch.diff(snapshot(List.of("b")), snapshot(List.of())));
        }

        @Test
        @DisplayName("a reordering that changes the first camera reports only a new default")
        void reordered() {
            assertEquals(List.of(new VideoDeviceEvent.DefaultCameraChanged(Optional.of("b"))),
                    VideoDeviceWatch.diff(snapshot(List.of("a", "b")), snapshot(List.of("b", "a"))));
        }

        @Test
        @DisplayName("screens report removals, then arrivals, then changes of the screens that stay")
        void screens() {
            var moved = new CaptureDevice.Screen("left", "Left", 0, 0, 2560, 1440, true);
            var third = new CaptureDevice.Screen("third", "Third", -1024, 0, 1024, 768, false);
            assertEquals(List.of(new VideoDeviceEvent.ScreenRemoved("right"), new VideoDeviceEvent.ScreenAdded("third"),
                            new VideoDeviceEvent.ScreenChanged("left")),
                    VideoDeviceWatch.diff(snapshot(List.of(), LEFT, RIGHT), snapshot(List.of(), moved, third)));
        }

        @Test
        @DisplayName("a primary flag that moves to another screen changes both screens")
        void primaryMoved() {
            var left = new CaptureDevice.Screen("left", "Left", 0, 0, 1920, 1080, false);
            var right = new CaptureDevice.Screen("right", "Right", 1920, 0, 1280, 1024, true);
            assertEquals(List.of(new VideoDeviceEvent.ScreenChanged("right"), new VideoDeviceEvent.ScreenChanged("left")),
                    VideoDeviceWatch.diff(snapshot(List.of(), LEFT, RIGHT), snapshot(List.of(), right, left)));
        }

        @Test
        @DisplayName("an unknown side of a kind reports nothing for that kind")
        void unknown() {
            var known = snapshot(List.of("a"), LEFT);
            assertEquals(List.of(), VideoDeviceWatch.diff(new VideoDeviceWatch.Snapshot(null, List.of(LEFT)), known));
            assertEquals(List.of(new VideoDeviceEvent.ScreenAdded("left")),
                    VideoDeviceWatch.diff(new VideoDeviceWatch.Snapshot(List.of("a"), List.of()),
                            new VideoDeviceWatch.Snapshot(List.of("a"), List.of(LEFT))));
            assertEquals(List.of(), VideoDeviceWatch.diff(known, new VideoDeviceWatch.Snapshot(List.of("a"), null)));
        }
    }

    @Nested
    @DisplayName("polling")
    class Polling {
        @Test
        @DisplayName("a poke reports a change at once, the initial listing reports nothing, and close stops the thread")
        void poke() throws InterruptedException {
            var cameras = new AtomicReference<List<String>>(List.of("a"));
            var screens = new AtomicReference<List<CaptureDevice.Screen>>(List.of(LEFT));
            var events = new LinkedBlockingQueue<VideoDeviceEvent>();
            var watch = new VideoDeviceWatch(cameras::get, screens::get, events::add, Duration.ofHours(1), 1);
            try {
                assertNull(events.poll(200, TimeUnit.MILLISECONDS));
                cameras.set(List.of("a", "b"));
                screens.set(List.of(LEFT, RIGHT));
                watch.poke();
                assertEquals(new VideoDeviceEvent.CameraAdded("b"), events.poll(5, TimeUnit.SECONDS));
                assertEquals(new VideoDeviceEvent.ScreenAdded("right"), events.poll(5, TimeUnit.SECONDS));
                assertNull(events.poll(200, TimeUnit.MILLISECONDS));
            } finally {
                watch.close();
            }
            assertFalse(watchThreadAlive());
            cameras.set(List.of());
            assertNull(events.poll(300, TimeUnit.MILLISECONDS));
        }

        @Test
        @DisplayName("without a poke the screens are listed every interval and the cameras every few intervals")
        void cadence() throws InterruptedException {
            var cameraCalls = new AtomicInteger();
            var screenCalls = new AtomicInteger();
            var watch = new VideoDeviceWatch(() -> {
                cameraCalls.incrementAndGet();
                return List.of();
            }, () -> {
                screenCalls.incrementAndGet();
                return List.of(LEFT);
            }, event -> {
            }, Duration.ofMillis(20), 4);
            try {
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (screenCalls.get() < 41 && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
            } finally {
                watch.close();
            }
            var screensListed = screenCalls.get() - 1;
            var camerasListed = cameraCalls.get() - 1;
            assertTrue(screensListed >= 40, "screens listed " + screensListed + " times");
            // one camera listing every fourth tick, give or take the tick that was running when the count was read
            assertTrue(Math.abs(camerasListed - screensListed / 4) <= 1,
                    "cameras listed " + camerasListed + " times for " + screensListed + " screen listings");
        }

        @Test
        @DisplayName("a failing listing is skipped and the comparison resumes from the last good one")
        void failures() throws InterruptedException {
            var fail = new AtomicReference<>(false);
            var cameras = new AtomicReference<List<String>>(List.of("a"));
            var events = new LinkedBlockingQueue<VideoDeviceEvent>();
            var watch = new VideoDeviceWatch(() -> {
                if (fail.get()) {
                    throw new IllegalStateException("listing failed");
                }
                return cameras.get();
            }, () -> List.of(LEFT), events::add, Duration.ofHours(1), 1);
            try {
                fail.set(true);
                cameras.set(List.of());
                watch.poke();
                assertNull(events.poll(300, TimeUnit.MILLISECONDS));
                fail.set(false);
                cameras.set(List.of("a", "c"));
                watch.poke();
                assertEquals(new VideoDeviceEvent.CameraAdded("c"), events.poll(5, TimeUnit.SECONDS));
                assertNull(events.poll(200, TimeUnit.MILLISECONDS));
            } finally {
                watch.close();
            }
        }

        @Test
        @DisplayName("a sink that throws does not stop later listings")
        void throwingSink() throws InterruptedException {
            var cameras = new AtomicReference<List<String>>(List.of());
            var seen = new CopyOnWriteArrayList<VideoDeviceEvent>();
            var watch = new VideoDeviceWatch(cameras::get, () -> List.of(LEFT), event -> {
                seen.add(event);
                if (seen.size() == 1) {
                    throw new IllegalStateException("sink failed");
                }
            }, Duration.ofHours(1), 1);
            try {
                cameras.set(List.of("a"));
                watch.poke();
                waitFor(() -> seen.size() >= 1);
                cameras.set(List.of("a", "b"));
                watch.poke();
                waitFor(() -> seen.contains(new VideoDeviceEvent.CameraAdded("b")));
            } finally {
                watch.close();
            }
            assertTrue(seen.contains(new VideoDeviceEvent.CameraAdded("b")), String.valueOf(seen));
        }
    }

    private static boolean watchThreadAlive() {
        var threads = new ArrayList<Thread>(Thread.getAllStackTraces().keySet());
        return threads.stream().anyMatch(thread -> thread.getName().equals(VideoDeviceWatch.THREAD_NAME) && thread.isAlive());
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}

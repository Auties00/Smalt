package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.VideoDeviceEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reports camera and screen changes by listing the devices again and comparing the lists.
 *
 * <p>A thread of the watch ({@value #THREAD_NAME}) lists the screens at a fixed interval, the cameras
 * every {@code cameraEvery} intervals, and both whenever {@link #poke()} asks it to, which a backend calls
 * from the operating system's own change notifications so the report follows within milliseconds. Each
 * listing is compared with the previous one by {@link #diff(Snapshot, Snapshot)}. A listing that fails is
 * skipped and the previous one kept, and an event the sink fails on is not delivered again.
 */
public final class VideoDeviceWatch implements AutoCloseable {
    /**
     * The name of the watch thread.
     */
    static final String THREAD_NAME = "smalt-video-watch";

    private final Supplier<List<String>> cameras;

    private final Supplier<List<CaptureDevice.Screen>> screens;

    private final Consumer<VideoDeviceEvent> sink;

    /**
     * The interval between two screen listings without a poke, in milliseconds.
     */
    private final long intervalMillis;

    /**
     * The number of intervals between two camera listings without a poke, at least 1.
     */
    private final int cameraEvery;

    /**
     * Guards {@link #poked} and {@link #closed}.
     */
    private final Object lock = new Object();

    private final Thread thread;

    private boolean poked;

    private boolean closed;

    /**
     * Starts a watch; the first listing is taken before this returns, so only later changes are reported.
     *
     * @param cameras lists the camera identifiers, the default camera first
     * @param screens lists the screens
     * @param sink receives the events, on the watch thread
     * @param interval the interval between two screen listings without a poke
     * @param cameraEvery the number of intervals between two camera listings without a poke, at least 1
     */
    public VideoDeviceWatch(Supplier<List<String>> cameras, Supplier<List<CaptureDevice.Screen>> screens,
                            Consumer<VideoDeviceEvent> sink, Duration interval, int cameraEvery) {
        this.cameras = Objects.requireNonNull(cameras, "cameras");
        this.screens = Objects.requireNonNull(screens, "screens");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.intervalMillis = Math.max(1, interval.toMillis());
        this.cameraEvery = Math.max(1, cameraEvery);
        var first = new Snapshot(listCameras(), listScreens());
        this.thread = Thread.ofPlatform().name(THREAD_NAME).daemon().unstarted(() -> run(first));
        this.thread.start();
    }

    /**
     * Asks for a listing of every device now; callable from any thread, also one of the operating system.
     */
    public void poke() {
        synchronized (lock) {
            poked = true;
            lock.notifyAll();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            lock.notifyAll();
        }
        if (Thread.currentThread() != thread) {
            PumpThread.join(thread, Duration.ofSeconds(2));
        }
    }

    /**
     * Lists and compares until closed.
     *
     * @param first the listing taken when the watch started
     */
    private void run(Snapshot first) {
        var previous = first;
        var sinceCameras = 0;
        while (true) {
            boolean full;
            synchronized (lock) {
                var deadline = System.nanoTime() + intervalMillis * 1_000_000L;
                while (!closed && !poked) {
                    var left = (deadline - System.nanoTime()) / 1_000_000L;
                    if (left <= 0) {
                        break;
                    }
                    try {
                        lock.wait(left);
                    } catch (InterruptedException exception) {
                        return;
                    }
                }
                if (closed) {
                    return;
                }
                full = poked;
                poked = false;
            }
            sinceCameras++;
            List<String> cameraIds = previous.cameras();
            if (full || sinceCameras >= cameraEvery || previous.cameras() == null) {
                sinceCameras = 0;
                var listed = listCameras();
                if (listed != null) {
                    cameraIds = listed;
                }
            }
            var screenList = listScreens();
            if (screenList == null) {
                screenList = previous.screens();
            }
            var current = new Snapshot(cameraIds, screenList);
            for (var event : diff(previous, current)) {
                synchronized (lock) {
                    if (closed) {
                        return;
                    }
                }
                try {
                    sink.accept(event);
                } catch (RuntimeException ignored) {
                    // a failing sink loses this event only; the watch keeps reporting the later ones
                }
            }
            previous = current;
        }
    }

    /**
     * Lists the camera identifiers.
     *
     * @return the identifiers, or {@code null} if the listing failed
     */
    private List<String> listCameras() {
        try {
            return List.copyOf(cameras.get());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Lists the screens.
     *
     * @return the screens, or {@code null} if the listing failed
     */
    private List<CaptureDevice.Screen> listScreens() {
        try {
            return List.copyOf(screens.get());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Computes the events that lead from one listing to the next.
     *
     * <p>In order: the removed cameras, the added cameras, a different first camera, the removed screens,
     * the added screens, then the screens whose description changed. A {@code null} side of a kind (a
     * failed listing) reports nothing for that kind.
     *
     * @param previous the older listing
     * @param current the newer listing
     * @return the events, empty when nothing changed
     */
    public static List<VideoDeviceEvent> diff(Snapshot previous, Snapshot current) {
        var events = new ArrayList<VideoDeviceEvent>();
        if (previous.cameras() != null && current.cameras() != null) {
            var before = new LinkedHashSet<>(previous.cameras());
            var after = new LinkedHashSet<>(current.cameras());
            for (var id : before) {
                if (!after.contains(id)) {
                    events.add(new VideoDeviceEvent.CameraRemoved(id));
                }
            }
            for (var id : after) {
                if (!before.contains(id)) {
                    events.add(new VideoDeviceEvent.CameraAdded(id));
                }
            }
            var oldDefault = previous.cameras().stream().findFirst();
            var newDefault = current.cameras().stream().findFirst();
            if (!oldDefault.equals(newDefault)) {
                events.add(new VideoDeviceEvent.DefaultCameraChanged(newDefault));
            }
        }
        if (previous.screens() != null && current.screens() != null) {
            var before = byId(previous.screens());
            var after = byId(current.screens());
            for (var id : before.keySet()) {
                if (!after.containsKey(id)) {
                    events.add(new VideoDeviceEvent.ScreenRemoved(id));
                }
            }
            for (var id : after.keySet()) {
                if (!before.containsKey(id)) {
                    events.add(new VideoDeviceEvent.ScreenAdded(id));
                }
            }
            for (var entry : after.entrySet()) {
                var old = before.get(entry.getKey());
                if (old != null && !old.equals(entry.getValue())) {
                    events.add(new VideoDeviceEvent.ScreenChanged(entry.getKey()));
                }
            }
        }
        return List.copyOf(events);
    }

    /**
     * Indexes screens by identifier, keeping the first of duplicates.
     */
    private static Map<String, CaptureDevice.Screen> byId(List<CaptureDevice.Screen> screens) {
        var map = new LinkedHashMap<String, CaptureDevice.Screen>();
        for (var screen : screens) {
            map.putIfAbsent(screen.id(), screen);
        }
        return map;
    }

    /**
     * Holds one listing.
     *
     * @param cameras the camera identifiers, the default first, or {@code null} when unknown
     * @param screens the screens, or {@code null} when unknown
     */
    public record Snapshot(List<String> cameras, List<CaptureDevice.Screen> screens) {
        /**
         * Returns the listing of a set of devices.
         *
         * @param cameras the camera identifiers, the default first
         * @param screens the screens, the primary one first
         * @return the listing
         */
        public static Snapshot of(List<String> cameras, List<CaptureDevice.Screen> screens) {
            return new Snapshot(List.copyOf(cameras), List.copyOf(screens));
        }
    }
}

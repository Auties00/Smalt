package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.internal.PumpThread;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Calls a callback whenever a {@code videoN} node appears in or disappears from {@code /dev}.
 *
 * <p>The directory is watched with the file system's {@link WatchService}, which inotify backs on Linux;
 * a thread of the watch ({@value #THREAD_NAME}) takes its events. udev creates and removes the nodes as
 * cameras are plugged and unplugged, so the callback follows a change within milliseconds.
 */
final class DevWatch implements AutoCloseable {
    /**
     * The name of the watch thread.
     */
    static final String THREAD_NAME = "smalt-dev-watch";

    private static final Pattern NODE = Pattern.compile("video\\d+");

    private final WatchService service;

    private final Thread thread;

    private DevWatch(WatchService service, Runnable callback) {
        this.service = service;
        this.thread = Thread.ofPlatform().name(THREAD_NAME).daemon().unstarted(() -> run(callback));
        this.thread.start();
    }

    /**
     * Starts watching {@code /dev}.
     *
     * @param callback called on the watch thread after a node changed
     * @return the watch, or {@code null} when {@code /dev} cannot be watched
     */
    static DevWatch start(Runnable callback) {
        return start(Path.of("/dev"), callback);
    }

    /**
     * Starts watching a directory of device nodes.
     *
     * @param directory the directory holding the nodes
     * @param callback called on the watch thread after a node changed
     * @return the watch, or {@code null} when the directory cannot be watched
     */
    static DevWatch start(Path directory, Runnable callback) {
        WatchService service = null;
        try {
            service = FileSystems.getDefault().newWatchService();
            directory.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
            return new DevWatch(service, callback);
        } catch (IOException | RuntimeException exception) {
            if (service != null) {
                try {
                    service.close();
                } catch (IOException ignored) {
                    // nothing was watched; the polling of the caller still reports the changes
                }
            }
            return null;
        }
    }

    /**
     * Takes events until the service is closed.
     */
    private void run(Runnable callback) {
        while (true) {
            try {
                var key = service.take();
                var relevant = false;
                for (var event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        relevant = true;
                    } else if (event.context() instanceof Path name && NODE.matcher(name.toString()).matches()) {
                        relevant = true;
                    }
                }
                key.reset();
                if (relevant) {
                    try {
                        callback.run();
                    } catch (RuntimeException ignored) {
                        // the callback only pokes the device watch; a failure there must not stop this one
                    }
                }
            } catch (InterruptedException | ClosedWatchServiceException exception) {
                return;
            }
        }
    }

    @Override
    public void close() {
        try {
            service.close();
        } catch (IOException ignored) {
            // the service is closed either way
        }
        if (Thread.currentThread() != thread) {
            PumpThread.join(thread, Duration.ofSeconds(2));
        }
    }
}

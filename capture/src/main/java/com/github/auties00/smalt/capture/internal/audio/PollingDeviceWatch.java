package com.github.auties00.smalt.capture.internal.audio;

import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceEvent;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reports device changes by listing the devices again and comparing the lists.
 *
 * <p>A thread of the watch ({@value #THREAD_NAME}) lists the devices at a fixed interval and
 * whenever {@link #poke()} asks it to, which a backend calls from the operating system's own
 * change notifications so the report follows within milliseconds. Each listing is compared with
 * the previous one: identifiers that disappeared are reported as
 * {@link AudioDeviceEvent.DeviceRemoved}, new ones as {@link AudioDeviceEvent.DeviceAdded}, and a
 * different default of a direction as {@link AudioDeviceEvent.DefaultChanged}. A listing that
 * fails is skipped.
 */
public final class PollingDeviceWatch implements AudioBackend.DeviceWatch {
    /**
     * The name of the watch thread.
     */
    private static final String THREAD_NAME = "smalt-audio-watch";

    /**
     * Lists the inputs.
     */
    private final Supplier<List<? extends AudioDevice>> inputs;

    /**
     * Lists the outputs.
     */
    private final Supplier<List<? extends AudioDevice>> outputs;

    /**
     * Receives the events.
     */
    private final Consumer<AudioDeviceEvent> sink;

    /**
     * The interval between two listings without a poke, in milliseconds.
     */
    private final long intervalMillis;

    /**
     * Guards {@link #poked} and {@link #closed}.
     */
    private final Object lock = new Object();

    /**
     * The watch thread.
     */
    private final Thread thread;

    /**
     * Whether a listing was asked for.
     */
    private boolean poked;

    /**
     * Whether the watch was closed.
     */
    private boolean closed;

    /**
     * Starts a watch; the first listing is taken before this returns, so only later changes are
     * reported.
     *
     * @param inputs lists the inputs
     * @param outputs lists the outputs
     * @param sink receives the events
     * @param interval the interval between two listings without a poke
     */
    public PollingDeviceWatch(Supplier<List<? extends AudioDevice>> inputs, Supplier<List<? extends AudioDevice>> outputs,
                              Consumer<AudioDeviceEvent> sink, Duration interval) {
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.outputs = Objects.requireNonNull(outputs, "outputs");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.intervalMillis = Math.max(1, interval.toMillis());
        var first = snapshot();
        this.thread = Thread.ofPlatform().name(THREAD_NAME).daemon().unstarted(() -> run(first));
        this.thread.start();
    }

    /**
     * Asks for a listing now.
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
            var interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join(2_000);
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Lists and compares until closed.
     *
     * @param first the listing taken when the watch started, or {@code null} if it failed
     */
    private void run(Snapshot first) {
        var previous = first;
        while (true) {
            synchronized (lock) {
                var deadline = System.currentTimeMillis() + intervalMillis;
                while (!closed && !poked) {
                    var left = deadline - System.currentTimeMillis();
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
                poked = false;
            }
            var current = snapshot();
            if (current == null) {
                continue;
            }
            if (previous != null) {
                report(previous, current);
            }
            previous = current;
        }
    }

    /**
     * Reports the differences between two listings.
     *
     * @param previous the older listing
     * @param current the newer listing
     */
    private void report(Snapshot previous, Snapshot current) {
        for (var id : previous.ids()) {
            if (!current.ids().contains(id)) {
                emit(new AudioDeviceEvent.DeviceRemoved(id));
            }
        }
        for (var id : current.ids()) {
            if (!previous.ids().contains(id)) {
                emit(new AudioDeviceEvent.DeviceAdded(id));
            }
        }
        if (!previous.defaultInput().equals(current.defaultInput())) {
            emit(new AudioDeviceEvent.DefaultChanged(AudioDevice.Direction.INPUT, current.defaultInput()));
        }
        if (!previous.defaultOutput().equals(current.defaultOutput())) {
            emit(new AudioDeviceEvent.DefaultChanged(AudioDevice.Direction.OUTPUT, current.defaultOutput()));
        }
    }

    /**
     * Hands an event to the sink unless the watch was closed.
     */
    private void emit(AudioDeviceEvent event) {
        synchronized (lock) {
            if (closed) {
                return;
            }
        }
        sink.accept(event);
    }

    /**
     * Lists the devices.
     *
     * @return the listing, or {@code null} if it failed
     */
    private Snapshot snapshot() {
        try {
            var in = inputs.get();
            var out = outputs.get();
            var ids = new LinkedHashSet<String>();
            in.forEach(device -> ids.add(device.id()));
            out.forEach(device -> ids.add(device.id()));
            return new Snapshot(ids, defaultId(in), defaultId(out));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Returns the identifier of the default device of a listing.
     *
     * @return the identifier, or empty when none is the default
     */
    private static Optional<String> defaultId(List<? extends AudioDevice> devices) {
        return devices.stream().filter(AudioDevice::defaultDevice).map(AudioDevice::id).findFirst();
    }

    /**
     * Holds one listing.
     *
     * @param ids the identifiers of every device
     * @param defaultInput the default input's identifier
     * @param defaultOutput the default output's identifier
     */
    private record Snapshot(Set<String> ids, Optional<String> defaultInput, Optional<String> defaultOutput) {
    }
}

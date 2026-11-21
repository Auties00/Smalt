package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * A connection to the PulseAudio-protocol sound server of the session: PulseAudio, or PipeWire
 * through {@code pipewire-pulse}.
 *
 * <p>The connection runs a {@code pa_threaded_mainloop}, whose thread runs the callbacks; every
 * request is issued with the loop's lock held and waits, releasing the lock, until its callback
 * signals. A connection either lists the server's sinks, sources and defaults
 * ({@link #snapshot()}), or reports their changes ({@link #subscribe(Runnable)}), or both. The
 * server is never spawned: without a running server {@link #connect()} returns {@code null}.
 * Instances are thread-safe; {@link #close()} stops the loop before it frees the callbacks.
 */
final class PulseServer implements AutoCloseable {
    /**
     * The client name the server shows.
     */
    private static final String CLIENT_NAME = "Smalt";

    /**
     * The arena of the callback stubs, closed after the loop stopped.
     */
    private final Arena arena = Arena.ofShared();

    /**
     * The main loop.
     */
    private final MemorySegment mainloop;

    /**
     * The context.
     */
    private final MemorySegment context;

    /**
     * The callback of the sink and source lists.
     */
    private final MemorySegment infoCallback;

    /**
     * The callback of the server information.
     */
    private final MemorySegment serverCallback;

    /**
     * The callback of subscribed events.
     */
    private final MemorySegment subscribeCallback;

    /**
     * The callback of an accepted subscription.
     */
    private final MemorySegment successCallback;

    /**
     * Serializes the requests and the close, so that no request runs on a freed loop.
     */
    private final Object lock = new Object();

    /**
     * The devices of the list being received; guarded by the loop's lock.
     */
    private List<Device> received;

    /**
     * Whether the list being received is of sources; guarded by the loop's lock.
     */
    private boolean receivingSources;

    /**
     * The default sink of the last server information; guarded by the loop's lock.
     */
    private String defaultSink;

    /**
     * The default source of the last server information; guarded by the loop's lock.
     */
    private String defaultSource;

    /**
     * The server's name; guarded by the loop's lock.
     */
    private String serverName;

    /**
     * The listener of subscribed changes, or {@code null}.
     */
    private volatile Runnable listener;

    /**
     * Whether the connection is closed; guarded by {@link #lock}.
     */
    private boolean closed;

    /**
     * Creates the loop and context, without connecting.
     */
    private PulseServer() {
        var lookup = MethodHandles.lookup();
        try {
            var state = lookup.findVirtual(PulseServer.class, "onState", MethodType.methodType(void.class,
                    MemorySegment.class, MemorySegment.class)).bindTo(this);
            var info = lookup.findVirtual(PulseServer.class, "onInfo", MethodType.methodType(void.class,
                    MemorySegment.class, MemorySegment.class, int.class, MemorySegment.class)).bindTo(this);
            var server = lookup.findVirtual(PulseServer.class, "onServer", MethodType.methodType(void.class,
                    MemorySegment.class, MemorySegment.class, MemorySegment.class)).bindTo(this);
            var subscribe = lookup.findVirtual(PulseServer.class, "onEvent", MethodType.methodType(void.class,
                    MemorySegment.class, int.class, int.class, MemorySegment.class)).bindTo(this);
            var success = lookup.findVirtual(PulseServer.class, "onSuccess", MethodType.methodType(void.class,
                    MemorySegment.class, int.class, MemorySegment.class)).bindTo(this);
            this.mainloop = Pulse.mainloopNew();
            if (mainloop.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("pa_threaded_mainloop_new failed");
            }
            this.context = Pulse.contextNew(Pulse.api(mainloop), CLIENT_NAME);
            if (context.equals(MemorySegment.NULL)) {
                Pulse.mainloopFree(mainloop);
                throw new IllegalStateException("pa_context_new failed");
            }
            var linker = Native.LINKER;
            Pulse.setStateCallback(context, linker.upcallStub(state, Pulse.NOTIFY_CALLBACK, arena));
            this.infoCallback = linker.upcallStub(info, Pulse.INFO_CALLBACK, arena);
            this.serverCallback = linker.upcallStub(server, Pulse.SERVER_CALLBACK, arena);
            this.subscribeCallback = linker.upcallStub(subscribe, Pulse.SUBSCRIBE_CALLBACK, arena);
            this.successCallback = linker.upcallStub(success, Pulse.SUCCESS_CALLBACK, arena);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * Connects to the session's server.
     *
     * @return the connection, or {@code null} when the libraries are missing or no server answers
     */
    static PulseServer connect() {
        if (!Pulse.available()) {
            return null;
        }
        PulseServer server;
        try {
            server = new PulseServer();
        } catch (RuntimeException exception) {
            return null;
        }
        if (!server.open()) {
            server.close();
            return null;
        }
        return server;
    }

    /**
     * Connects the context and waits until it is ready or failed.
     *
     * @return whether the context is ready
     */
    private boolean open() {
        if (Pulse.connect(context) < 0 || Pulse.mainloopStart(mainloop) < 0) {
            return false;
        }
        Pulse.lock(mainloop);
        try {
            while (true) {
                var state = Pulse.state(context);
                if (state == Pulse.CONTEXT_READY) {
                    return true;
                }
                if (state == Pulse.CONTEXT_FAILED || state == Pulse.CONTEXT_TERMINATED) {
                    return false;
                }
                Pulse.await(mainloop);
            }
        } finally {
            Pulse.unlock(mainloop);
        }
    }

    /**
     * Returns whether the connection still works.
     *
     * @return whether the context is ready
     */
    boolean ready() {
        synchronized (lock) {
            if (closed) {
                return false;
            }
            Pulse.lock(mainloop);
            try {
                return Pulse.state(context) == Pulse.CONTEXT_READY;
            } finally {
                Pulse.unlock(mainloop);
            }
        }
    }

    /**
     * Lists the server's sinks, sources and defaults.
     *
     * @return the snapshot, or {@code null} when the connection failed
     */
    Snapshot snapshot() {
        synchronized (lock) {
            if (closed) {
                return null;
            }
            Pulse.lock(mainloop);
            try {
                if (Pulse.state(context) != Pulse.CONTEXT_READY) {
                    return null;
                }
                var sinks = list(false);
                var sources = list(true);
                if (sinks == null || sources == null || !run(Pulse.serverInfo(context, serverCallback))) {
                    return null;
                }
                return new Snapshot(serverName == null ? "PulseAudio" : serverName, sinks, sources, defaultSink, defaultSource);
            } finally {
                Pulse.unlock(mainloop);
            }
        }
    }

    /**
     * Lists the sinks or the sources; called with the lock held.
     *
     * @param sources whether to list the sources
     * @return the devices, or {@code null} when the request failed
     */
    private List<Device> list(boolean sources) {
        received = new ArrayList<>();
        receivingSources = sources;
        var operation = sources ? Pulse.sources(context, infoCallback) : Pulse.sinks(context, infoCallback);
        if (!run(operation)) {
            return null;
        }
        var devices = List.copyOf(received);
        received = null;
        return devices;
    }

    /**
     * Waits for an operation to finish and releases it; called with the lock held.
     *
     * @param operation the operation, or {@link MemorySegment#NULL} when the request failed
     * @return whether the operation ran
     */
    private boolean run(MemorySegment operation) {
        if (operation.equals(MemorySegment.NULL)) {
            return false;
        }
        try {
            while (Pulse.operationState(operation) == Pulse.OPERATION_RUNNING) {
                if (Pulse.state(context) != Pulse.CONTEXT_READY) {
                    return false;
                }
                Pulse.await(mainloop);
            }
            return true;
        } finally {
            Pulse.operationUnref(operation);
        }
    }

    /**
     * Subscribes to the changes of the sinks, sources, cards and server defaults.
     *
     * @param listener called on the loop's thread after every change, also when the connection
     * fails; it must return quickly and must not call this connection
     * @return whether the subscription was accepted
     */
    boolean subscribe(Runnable listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (closed) {
                return false;
            }
            Pulse.lock(mainloop);
            try {
                return run(Pulse.subscribe(context, subscribeCallback, successCallback));
            } finally {
                Pulse.unlock(mainloop);
            }
        }
    }

    /**
     * Receives a context state change on the loop's thread.
     */
    private void onState(MemorySegment context, MemorySegment userdata) {
        try {
            Pulse.signal(mainloop);
            var state = Pulse.state(context);
            var current = listener;
            if (current != null && (state == Pulse.CONTEXT_FAILED || state == Pulse.CONTEXT_TERMINATED)) {
                current.run();
            }
        } catch (Throwable ignored) {
            // a failure must not unwind into the native loop
        }
    }

    /**
     * Receives one entry of a sink or source list on the loop's thread.
     */
    private void onInfo(MemorySegment context, MemorySegment info, int eol, MemorySegment userdata) {
        try {
            if (eol == 0 && !info.equals(MemorySegment.NULL) && received != null) {
                var view = info.reinterpret(Pulse.DEVICE_INFO.byteSize());
                var name = Pulse.string(view.get(ADDRESS, Pulse.INFO_NAME));
                if (name != null) {
                    var proplist = view.get(ADDRESS, Pulse.INFO_PROPLIST);
                    var card = Pulse.property(proplist, "alsa.card");
                    if (card == null) {
                        card = Pulse.property(proplist, "api.alsa.card");
                    }
                    var device = Pulse.property(proplist, "alsa.device");
                    if (device == null) {
                        device = Pulse.property(proplist, "api.alsa.pcm.device");
                    }
                    received.add(new Device(name, Objects.requireNonNullElse(Pulse.string(view.get(ADDRESS, Pulse.INFO_DESCRIPTION)), name),
                            view.get(JAVA_INT, Pulse.INFO_RATE), Byte.toUnsignedInt(view.get(JAVA_BYTE, Pulse.INFO_CHANNELS)),
                            Pulse.string(view.get(ADDRESS, Pulse.INFO_MONITOR_NAME)), card, device, receivingSources));
                }
            }
            if (eol != 0) {
                Pulse.signal(mainloop);
            }
        } catch (Throwable ignored) {
            // a failure must not unwind into the native loop
        }
    }

    /**
     * Receives the server information on the loop's thread.
     */
    private void onServer(MemorySegment context, MemorySegment info, MemorySegment userdata) {
        try {
            if (!info.equals(MemorySegment.NULL)) {
                var view = info.reinterpret(Pulse.SERVER_INFO.byteSize());
                serverName = Pulse.string(view.get(ADDRESS, Pulse.SERVER_NAME));
                defaultSink = Pulse.string(view.get(ADDRESS, Pulse.SERVER_DEFAULT_SINK));
                defaultSource = Pulse.string(view.get(ADDRESS, Pulse.SERVER_DEFAULT_SOURCE));
            }
            Pulse.signal(mainloop);
        } catch (Throwable ignored) {
            // a failure must not unwind into the native loop
        }
    }

    /**
     * Receives the server's answer to the subscription on the loop's thread.
     */
    private void onSuccess(MemorySegment context, int success, MemorySegment userdata) {
        try {
            Pulse.signal(mainloop);
        } catch (Throwable ignored) {
            // a failure must not unwind into the native loop
        }
    }

    /**
     * Receives a subscribed event on the loop's thread.
     */
    private void onEvent(MemorySegment context, int type, int index, MemorySegment userdata) {
        try {
            var current = listener;
            if (current != null) {
                current.run();
            }
        } catch (Throwable ignored) {
            // a failure must not unwind into the native loop
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            listener = null;
            Pulse.mainloopStop(mainloop);
            Pulse.contextFree(context);
            Pulse.mainloopFree(mainloop);
            arena.close();
        }
    }

    /**
     * Describes a sink or a source.
     *
     * @param name the server's name of the device
     * @param description the name shown to users
     * @param sampleRate the device's sample rate
     * @param channels the device's channel count
     * @param monitor the name of the sink's monitor source, or of the sink a source monitors, or
     * {@code null}
     * @param alsaCard the number of the ALSA card behind the device, or {@code null}
     * @param alsaDevice the number of the ALSA PCM device behind the device, or {@code null}
     * @param source whether the device is a source
     */
    record Device(String name, String description, int sampleRate, int channels, String monitor, String alsaCard,
                  String alsaDevice, boolean source) {
    }

    /**
     * Holds the server's devices and defaults at one moment.
     *
     * @param server the server's name, such as {@code pulseaudio} or {@code PulseAudio (on PipeWire 1.0.5)}
     * @param sinks the playback devices
     * @param sources the sources, monitors included
     * @param defaultSink the default sink's name, or {@code null}
     * @param defaultSource the default source's name, or {@code null}
     */
    record Snapshot(String server, List<Device> sinks, List<Device> sources, String defaultSink, String defaultSource) {
    }
}

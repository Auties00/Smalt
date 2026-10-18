package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceEvent;
import com.github.auties00.smalt.capture.internal.Native;
import com.github.auties00.smalt.capture.internal.audio.AudioBackend;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Reports endpoint changes through an {@code IMMNotificationClient} implemented in Java.
 *
 * <p>The notification client is a COM object built in native memory: a pointer to a virtual
 * function table of eight upcall stubs ({@code IUnknown} and the five notification methods),
 * shared by every watch and created once. {@code AddRef} and {@code Release} do not count, since
 * the object lives until its watch unregisters it. The MMDevice API calls the methods on threads
 * of its own; each upcall resolves the watch from the object's address, turns the notification
 * into an {@link AudioDeviceEvent} and hands it to the watch's sink without blocking, as the API
 * requires.
 *
 * <p>Only the {@code eConsole} role's default changes are reported, the one
 * {@link AudioDevice#defaultDevice()} describes, and state changes are reported as additions
 * (to {@code DEVICE_STATE_ACTIVE}) or removals (to any other state), which covers plugging,
 * unplugging, enabling and disabling. A watch owns a platform thread in the COM multithreaded
 * apartment that registers the client and keeps the apartment alive until the watch closes.
 */
final class WasapiNotifications implements AudioBackend.DeviceWatch, Runnable {
    /**
     * The sinks of the open watches, by the address of their notification object.
     */
    private static final ConcurrentHashMap<Long, Consumer<AudioDeviceEvent>> SINKS = new ConcurrentHashMap<>();

    /**
     * The shared virtual function table of the notification objects.
     */
    private static final MemorySegment VTABLE = vtable();

    /**
     * The longest wait for the watch thread to register.
     */
    private static final long START_TIMEOUT_SECONDS = 10;

    /**
     * The sink of this watch.
     */
    private final Consumer<AudioDeviceEvent> sink;

    /**
     * The notification object of this watch: one pointer to {@link #VTABLE}.
     */
    private final MemorySegment object;

    /**
     * Counted down once the client is registered or failed to register.
     */
    private final CountDownLatch started = new CountDownLatch(1);

    /**
     * Counted down by {@link #close()}.
     */
    private final CountDownLatch closing = new CountDownLatch(1);

    /**
     * The watch thread.
     */
    private final Thread thread;

    /**
     * The failure to register, published before {@link #started} is counted down.
     */
    private volatile MediaException failure;

    /**
     * Creates a watch whose thread is not started.
     *
     * @param sink the sink of the events
     */
    private WasapiNotifications(Consumer<AudioDeviceEvent> sink) {
        this.sink = sink;
        this.object = Arena.global().allocate(ADDRESS);
        this.object.set(ADDRESS, 0, VTABLE);
        this.thread = Thread.ofPlatform().name("smalt-audio-notifications").daemon().unstarted(this);
    }

    /**
     * Starts a watch.
     *
     * @param sink the sink of the events
     * @return the watch
     * @throws MediaException.Io if the notification client cannot be registered
     */
    static WasapiNotifications start(Consumer<AudioDeviceEvent> sink) {
        var watch = new WasapiNotifications(sink);
        SINKS.put(watch.object.address(), sink);
        watch.thread.start();
        var interrupted = false;
        while (true) {
            try {
                if (!watch.started.await(START_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                    watch.close();
                    var message = "timed out registering for audio device notifications";
                    throw new MediaException.Io(message, new IOException(message));
                }
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (watch.failure != null) {
            SINKS.remove(watch.object.address());
            throw watch.failure;
        }
        return watch;
    }

    @Override
    public void run() {
        var initialized = false;
        var enumerator = MemorySegment.NULL;
        var registered = false;
        try (var arena = Arena.ofConfined()) {
            try {
                initialized = Com.initialize();
                enumerator = Wasapi.createEnumerator(arena);
                Wasapi.check(Com.call(enumerator, Wasapi.REGISTER_ENDPOINT_NOTIFICATION, object),
                        "IMMDeviceEnumerator::RegisterEndpointNotificationCallback");
                registered = true;
            } catch (MediaException exception) {
                failure = exception;
            } catch (RuntimeException exception) {
                var message = "cannot register for audio device notifications";
                failure = new MediaException.Io(message, new IOException(message, exception));
            } finally {
                started.countDown();
            }
            if (registered) {
                while (true) {
                    try {
                        closing.await();
                        break;
                    } catch (InterruptedException exception) {
                        // the watch ends only through close()
                    }
                }
                Com.call(enumerator, Wasapi.UNREGISTER_ENDPOINT_NOTIFICATION, object);
            }
        } finally {
            Com.release(enumerator);
            if (initialized) {
                Com.uninitialize();
            }
        }
    }

    @Override
    public void close() {
        SINKS.remove(object.address());
        closing.countDown();
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
     * Builds the virtual function table of the notification objects.
     *
     * @return the table of eight function pointers, in process-lifetime memory
     */
    private static MemorySegment vtable() {
        var lookup = MethodHandles.lookup();
        var table = Arena.global().allocate(ADDRESS, 8);
        try {
            var hresult = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
            table.setAtIndex(ADDRESS, 0, stub(lookup, "queryInterface", MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class), hresult));
            var count = FunctionDescriptor.of(JAVA_INT, ADDRESS);
            table.setAtIndex(ADDRESS, 1, stub(lookup, "addRef", MethodType.methodType(int.class, MemorySegment.class), count));
            table.setAtIndex(ADDRESS, 2, stub(lookup, "release", MethodType.methodType(int.class, MemorySegment.class), count));
            table.setAtIndex(ADDRESS, 3, stub(lookup, "onDeviceStateChanged", MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, int.class),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)));
            var single = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
            table.setAtIndex(ADDRESS, 4, stub(lookup, "onDeviceAdded", MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class), single));
            table.setAtIndex(ADDRESS, 5, stub(lookup, "onDeviceRemoved", MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class), single));
            table.setAtIndex(ADDRESS, 6, stub(lookup, "onDefaultDeviceChanged", MethodType.methodType(int.class, MemorySegment.class, int.class, int.class, MemorySegment.class),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS)));
            table.setAtIndex(ADDRESS, 7, stub(lookup, "onPropertyValueChanged", MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, Wasapi.PROPERTYKEY)));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return table;
    }

    /**
     * Creates the upcall stub of a static method of this class.
     *
     * @param lookup the lookup of this class
     * @param name the method name
     * @param type the method type
     * @param descriptor the native signature
     * @return the stub, in process-lifetime memory
     * @throws ReflectiveOperationException if the method is missing
     */
    private static MemorySegment stub(MethodHandles.Lookup lookup, String name, MethodType type, FunctionDescriptor descriptor)
            throws ReflectiveOperationException {
        return Native.LINKER.upcallStub(lookup.findStatic(WasapiNotifications.class, name, type), descriptor, Arena.global());
    }

    /**
     * Implements {@code IUnknown::QueryInterface}: the object is an {@code IUnknown} and an
     * {@code IMMNotificationClient}.
     *
     * @param self the object
     * @param iid the requested interface
     * @param result receives the interface pointer
     * @return {@code S_OK} or {@code E_NOINTERFACE}
     */
    private static int queryInterface(MemorySegment self, MemorySegment iid, MemorySegment result) {
        try {
            var slot = result.reinterpret(ADDRESS.byteSize());
            var requested = iid.reinterpret(16);
            if (Com.sameGuid(requested, Wasapi.IID_UNKNOWN) || Com.sameGuid(requested, Wasapi.IID_MM_NOTIFICATION_CLIENT)) {
                slot.set(ADDRESS, 0, self);
                return 0;
            }
            slot.set(ADDRESS, 0, MemorySegment.NULL);
            return Com.E_NOINTERFACE;
        } catch (Throwable throwable) {
            return Com.E_NOINTERFACE;
        }
    }

    /**
     * Implements {@code IUnknown::AddRef}; the object's lifetime is its watch's.
     *
     * @param self the object
     * @return a constant positive count
     */
    private static int addRef(MemorySegment self) {
        return 1;
    }

    /**
     * Implements {@code IUnknown::Release}; the object's lifetime is its watch's.
     *
     * @param self the object
     * @return a constant positive count
     */
    private static int release(MemorySegment self) {
        return 1;
    }

    /**
     * Implements {@code IMMNotificationClient::OnDeviceStateChanged}.
     *
     * @param self the object
     * @param id the endpoint identifier
     * @param state the new {@code DEVICE_STATE_*}
     * @return {@code S_OK}
     */
    private static int onDeviceStateChanged(MemorySegment self, MemorySegment id, int state) {
        try {
            var device = Wasapi.readWide(id);
            emit(self, (state & Wasapi.DEVICE_STATE_ACTIVE) != 0
                    ? new AudioDeviceEvent.DeviceAdded(device)
                    : new AudioDeviceEvent.DeviceRemoved(device));
        } catch (Throwable ignored) {
            // an upcall must never throw into the native caller
        }
        return 0;
    }

    /**
     * Implements {@code IMMNotificationClient::OnDeviceAdded}; the new endpoint is reported by its
     * state change once it becomes active.
     *
     * @param self the object
     * @param id the endpoint identifier
     * @return {@code S_OK}
     */
    private static int onDeviceAdded(MemorySegment self, MemorySegment id) {
        return 0;
    }

    /**
     * Implements {@code IMMNotificationClient::OnDeviceRemoved}; an active endpoint's removal is
     * reported by its state change.
     *
     * @param self the object
     * @param id the endpoint identifier
     * @return {@code S_OK}
     */
    private static int onDeviceRemoved(MemorySegment self, MemorySegment id) {
        return 0;
    }

    /**
     * Implements {@code IMMNotificationClient::OnDefaultDeviceChanged}.
     *
     * @param self the object
     * @param flow the data flow, {@code eRender} or {@code eCapture}
     * @param role the role whose default changed
     * @param id the new default endpoint, or {@code NULL} when the flow has none
     * @return {@code S_OK}
     */
    private static int onDefaultDeviceChanged(MemorySegment self, int flow, int role, MemorySegment id) {
        try {
            if (role == Wasapi.E_CONSOLE && (flow == Wasapi.E_RENDER || flow == Wasapi.E_CAPTURE)) {
                var direction = flow == Wasapi.E_RENDER ? AudioDevice.Direction.OUTPUT : AudioDevice.Direction.INPUT;
                var device = id.equals(MemorySegment.NULL) ? Optional.<String>empty() : Optional.of(Wasapi.readWide(id));
                emit(self, new AudioDeviceEvent.DefaultChanged(direction, device));
            }
        } catch (Throwable ignored) {
            // an upcall must never throw into the native caller
        }
        return 0;
    }

    /**
     * Implements {@code IMMNotificationClient::OnPropertyValueChanged}; property changes are not
     * reported.
     *
     * @param self the object
     * @param id the endpoint identifier
     * @param key the changed property
     * @return {@code S_OK}
     */
    private static int onPropertyValueChanged(MemorySegment self, MemorySegment id, MemorySegment key) {
        return 0;
    }

    /**
     * Hands an event to the sink of the watch owning an object.
     *
     * @param self the object
     */
    private static void emit(MemorySegment self, AudioDeviceEvent event) {
        var sink = SINKS.get(self.address());
        if (sink != null) {
            sink.accept(event);
        }
    }
}

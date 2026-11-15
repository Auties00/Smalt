package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Calls a callback whenever a camera or a monitor arrives or is removed, through the configuration
 * manager's device interface notifications ({@code CM_Register_Notification} of {@code cfgmgr32.dll},
 * Windows 8 and later).
 *
 * <p>One registration watches each interface class: {@code KSCATEGORY_VIDEO_CAMERA} (the cameras Media
 * Foundation enumerates), {@code KSCATEGORY_VIDEO} (older capture drivers) and
 * {@code GUID_DEVINTERFACE_MONITOR}. The system calls the notification callback on a thread of its own pool;
 * the callback only hands the change to the Java callback, which must not block. Closing unregisters every
 * registration, which waits for a callback in progress.
 */
final class DeviceNotifications implements AutoCloseable {
    /**
     * {@code KSCATEGORY_VIDEO_CAMERA}.
     */
    static final String VIDEO_CAMERA = "E5323777-F976-4F5B-9B55-B94699C46E44";

    /**
     * {@code KSCATEGORY_VIDEO}.
     */
    static final String VIDEO = "6994AD05-93EF-11D0-A3CC-00A0C9223196";

    /**
     * {@code GUID_DEVINTERFACE_MONITOR}.
     */
    static final String MONITOR = "E6F07B5F-EE97-4A90-B076-33F57BF4EAA7";

    /**
     * The size of {@code CM_NOTIFY_FILTER}: four 32-bit fields and a union whose largest member is a
     * 200-character instance identifier.
     */
    static final long FILTER_SIZE = 416;

    /**
     * The offset of {@code CM_NOTIFY_FILTER.u.DeviceInterface.ClassGuid}.
     */
    static final long FILTER_CLASS_GUID = 16;

    /**
     * The offset of {@code CM_NOTIFY_FILTER.FilterType}.
     */
    static final long FILTER_TYPE = 8;

    /**
     * {@code CM_NOTIFY_FILTER_TYPE_DEVICEINTERFACE}.
     */
    static final int TYPE_DEVICE_INTERFACE = 0;

    /**
     * {@code CM_NOTIFY_ACTION_DEVICEINTERFACEARRIVAL}.
     */
    static final int ACTION_ARRIVAL = 0;

    /**
     * {@code CM_NOTIFY_ACTION_DEVICEINTERFACEREMOVAL}.
     */
    static final int ACTION_REMOVAL = 1;

    /**
     * {@code CR_SUCCESS}.
     */
    private static final int CR_SUCCESS = 0;

    /**
     * The signature of {@code PCM_NOTIFY_CALLBACK}.
     */
    static final FunctionDescriptor CALLBACK = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT);

    private static final MethodHandle ON_NOTIFICATION;

    static {
        try {
            ON_NOTIFICATION = MethodHandles.lookup().findVirtual(DeviceNotifications.class, "onNotification",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, int.class,
                            MemorySegment.class, int.class));
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final Functions FUNCTIONS = Functions.load();

    private final Runnable callback;

    private final Arena arena;

    private final List<MemorySegment> registrations = new ArrayList<>();

    private boolean closed;

    private DeviceNotifications(Runnable callback) {
        this.callback = callback;
        this.arena = Arena.ofShared();
    }

    /**
     * The linked functions.
     */
    private record Functions(MethodHandle register, MethodHandle unregister) {
        static Functions load() {
            var library = Native.library("cfgmgr32").orElse(null);
            if (library == null) {
                return null;
            }
            var register = Native.optionalFunction(library, "CM_Register_Notification",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)).orElse(null);
            var unregister = Native.optionalFunction(library, "CM_Unregister_Notification",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS)).orElse(null);
            return register == null || unregister == null ? null : new Functions(register, unregister);
        }
    }

    /**
     * Registers for the arrivals and removals of cameras and monitors.
     *
     * @param callback called on a system thread after a change; must return quickly and not throw
     * @return the registrations, or {@code null} when the system offers none of them
     */
    static DeviceNotifications start(Runnable callback) {
        return start(callback, VIDEO_CAMERA, VIDEO, MONITOR);
    }

    /**
     * Registers for the arrivals and removals of the interfaces of some classes.
     *
     * @param callback called on a system thread after a change; must return quickly and not throw
     * @param classes the interface class identifiers
     * @return the registrations, or {@code null} when none succeeded
     */
    static DeviceNotifications start(Runnable callback, String... classes) {
        Objects.requireNonNull(callback, "callback");
        if (FUNCTIONS == null) {
            return null;
        }
        var notifications = new DeviceNotifications(callback);
        try {
            var stub = Native.LINKER.upcallStub(ON_NOTIFICATION.bindTo(notifications), CALLBACK, notifications.arena);
            for (var interfaceClass : classes) {
                var filter = notifications.arena.allocate(FILTER_SIZE, 8);
                filter.set(JAVA_INT, 0, (int) FILTER_SIZE);
                filter.set(JAVA_INT, FILTER_TYPE, TYPE_DEVICE_INTERFACE);
                MemorySegment.copy(Com.guid(interfaceClass), 0, filter, FILTER_CLASS_GUID, 16);
                var handle = notifications.arena.allocate(ADDRESS);
                int result;
                try {
                    result = (int) FUNCTIONS.register().invokeExact(filter, MemorySegment.NULL, stub, handle);
                } catch (Throwable throwable) {
                    throw Native.rethrow(throwable);
                }
                if (result == CR_SUCCESS) {
                    notifications.registrations.add(handle.get(ADDRESS, 0));
                }
            }
        } catch (RuntimeException exception) {
            notifications.close();
            return null;
        }
        if (notifications.registrations.isEmpty()) {
            notifications.close();
            return null;
        }
        return notifications;
    }

    /**
     * {@return the number of interface classes registered}
     */
    int registered() {
        return registrations.size();
    }

    /**
     * Receives a notification on a system thread.
     *
     * @return {@code ERROR_SUCCESS}
     */
    private int onNotification(MemorySegment handle, MemorySegment context, int action, MemorySegment data, int size) {
        if (action == ACTION_ARRIVAL || action == ACTION_REMOVAL) {
            try {
                callback.run();
            } catch (Throwable ignored) {
                // an exception must not unwind into the system's thread
            }
        }
        return 0;
    }

    @Override
    public void close() {
        synchronized (registrations) {
            if (closed) {
                return;
            }
            closed = true;
        }
        for (var registration : registrations) {
            try {
                var ignored = (int) FUNCTIONS.unregister().invokeExact(registration);
            } catch (Throwable throwable) {
                // the registration ends with the process at the latest
            }
        }
        registrations.clear();
        arena.close();
    }
}

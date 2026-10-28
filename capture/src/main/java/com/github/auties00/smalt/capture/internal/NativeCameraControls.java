package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CameraControl;
import com.github.auties00.smalt.capture.CameraControls;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Implements the checks of {@link CameraControls} over a platform's control calls.
 *
 * <p>The capabilities are fixed when the camera opens. Every call is checked against them and runs under
 * one lock, so a platform implementation sees one request at a time and never after {@link #close()}.
 */
public abstract class NativeCameraControls implements CameraControls {
    private final List<Capability> capabilities;

    private final Map<CameraControl, Capability> byControl;

    private final Object lock = new Object();

    private boolean closed;

    /**
     * Creates the controls of an open camera.
     *
     * @param capabilities what the camera offers, at most one per control
     * @throws IllegalArgumentException if two capabilities describe the same control
     */
    protected NativeCameraControls(List<Capability> capabilities) {
        var map = new EnumMap<CameraControl, Capability>(CameraControl.class);
        for (var capability : capabilities) {
            if (map.put(capability.control(), capability) != null) {
                throw new IllegalArgumentException("duplicate capability of " + capability.control());
            }
        }
        this.byControl = map;
        this.capabilities = capabilities.stream().sorted(Comparator.comparing(Capability::control)).toList();
    }

    /**
     * Returns controls that offer nothing, for a platform or a camera without controls.
     *
     * @return the controls
     */
    public static NativeCameraControls none() {
        return new NativeCameraControls(List.of()) {
            @Override
            protected State read(Capability capability) {
                throw new AssertionError();
            }

            @Override
            protected void write(Capability capability, long value) {
                throw new AssertionError();
            }

            @Override
            protected void writeAutomatic(Capability capability, boolean automatic) {
                throw new AssertionError();
            }
        };
    }

    @Override
    public final List<Capability> capabilities() {
        return capabilities;
    }

    @Override
    public final Optional<Capability> capability(CameraControl control) {
        return Optional.ofNullable(byControl.get(Objects.requireNonNull(control, "control")));
    }

    @Override
    public final State get(CameraControl control) {
        synchronized (lock) {
            return read(require(control));
        }
    }

    @Override
    public final void set(CameraControl control, long value) {
        synchronized (lock) {
            var capability = require(control);
            var range = capability.range().orElseThrow(() -> new MediaException.Unsupported("camera control",
                    "the camera offers no manual value for " + control));
            if (!range.accepts(value)) {
                throw new IllegalArgumentException(control + " " + value + " is outside " + range);
            }
            write(capability, value);
        }
    }

    @Override
    public final void setAutomatic(CameraControl control, boolean automatic) {
        synchronized (lock) {
            var capability = require(control);
            if (!capability.automatic()) {
                throw new MediaException.Unsupported("camera control", "the camera has no automatic mode for " + control);
            }
            writeAutomatic(capability, automatic);
        }
    }

    /**
     * Stops the controls; later requests throw {@link IllegalStateException}. Waits for a request in progress.
     */
    public final void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            release();
        }
    }

    /**
     * Returns the capability of a control, checking the state.
     *
     * @throws IllegalStateException if the controls are closed
     * @throws MediaException.Unsupported if the camera does not offer the control
     */
    private Capability require(CameraControl control) {
        Objects.requireNonNull(control, "control");
        if (closed) {
            throw new IllegalStateException("the camera session is closed");
        }
        var capability = byControl.get(control);
        if (capability == null) {
            throw new MediaException.Unsupported("camera control", "the camera does not offer " + control);
        }
        return capability;
    }

    /**
     * Reads a control the camera offers; called with the lock held.
     *
     * @throws MediaException.Io if the camera refuses the request
     */
    protected abstract State read(Capability capability);

    /**
     * Writes a manual value the capability's range accepts; called with the lock held.
     *
     * @throws MediaException.Io if the camera refuses the request
     */
    protected abstract void write(Capability capability, long value);

    /**
     * Switches the automatic mode of a control that has one; called with the lock held.
     *
     * @throws MediaException.Io if the camera refuses the request
     */
    protected abstract void writeAutomatic(Capability capability, boolean automatic);

    /**
     * Releases the platform's resources; called once, with the lock held.
     *
     * @implSpec The default implementation does nothing.
     */
    protected void release() {
    }
}

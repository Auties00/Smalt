package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exposes the Linux backend's protocol parsers to the fuzz targets of another package: D-Bus
 * messages, SPA format pods and Wayland event streams.
 */
public final class LinuxParsers {
    private LinuxParsers() {
    }

    /**
     * Decodes the D-Bus messages a byte stream holds, as the session bus connection would, and reads
     * the screen cast streams of every portal response among them.
     *
     * @param data the bytes received from the bus
     * @return the number of messages decoded
     * @throws MediaException.Malformed if a message is malformed
     */
    public static int dbus(byte[] data) {
        var offset = 0;
        var count = 0;
        while (offset < data.length) {
            var length = DBusMessage.length(data, offset, data.length - offset);
            if (length < 0 || length > data.length - offset) {
                throw new MediaException.Malformed("a truncated D-Bus message");
            }
            var message = DBusMessage.decode(data, offset, length, fds -> new int[fds]);
            if (message.signature().equals("ua{sv}")) {
                ScreenCastPortal.streams((Map<?, ?>) message.body().get(1));
            }
            message.encode(message.serial() == 0 ? 1 : message.serial());
            offset += length;
            count++;
        }
        return count;
    }

    /**
     * Reads a negotiated video format.
     *
     * @param data the pod
     * @return the format, or empty when the pod is not a raw video format
     * @throws MediaException.Malformed if the pod is malformed
     */
    public static Optional<String> spa(byte[] data) {
        return Optional.ofNullable(SpaPod.readVideoFormat(data)).map(Object::toString);
    }

    /**
     * Lists the outputs a recorded stream of compositor events describes.
     *
     * @param data the events
     * @return the screens
     * @throws MediaException.Malformed if an event is malformed
     * @throws MediaException.Io if the events end before the listing completes
     */
    public static List<CaptureDevice.Screen> wayland(byte[] data) {
        try {
            return WaylandOutputs.replay(data);
        } catch (IOException exception) {
            throw new MediaException.Io(exception.getMessage(), exception);
        }
    }
}

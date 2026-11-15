package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Lists the outputs of a simulated Wayland compositor that answers the client's requests with the
 * events of the Wayland protocol (wayland.xml) and of xdg-output-unstable-v1, encoded by hand.
 */
@DisplayName("Wayland outputs")
@Timeout(30)
class WaylandOutputsTest {
    @TempDir
    Path directory;

    private int sockets;

    /** A monitor the simulated compositor advertises. */
    private record Monitor(String name, String description, int x, int y, int modeWidth, int modeHeight, int scale,
                           int logicalWidth, int logicalHeight) {
    }

    /** Builds one event. */
    private static ByteBuffer event(int object, int opcode, Object... arguments) {
        var body = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        for (var argument : arguments) {
            if (argument instanceof Integer value) {
                body.putInt(value);
            } else {
                var bytes = ((String) argument).getBytes(StandardCharsets.UTF_8);
                body.putInt(bytes.length + 1).put(bytes).put((byte) 0);
                while (body.position() % 4 != 0) {
                    body.put((byte) 0);
                }
            }
        }
        var message = ByteBuffer.allocate(8 + body.position()).order(ByteOrder.LITTLE_ENDIAN);
        message.putInt(object).putInt((8 + body.position()) << 16 | opcode).put(body.flip()).flip();
        return message;
    }

    /** Serves one client: answers the registry, binds, xdg-output requests and syncs. */
    private static void serve(SocketChannel client, List<Monitor> monitors, boolean xdg, boolean error) throws IOException {
        var input = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        var outputs = new HashMap<Integer, Monitor>();
        int registry = -1;
        int manager = -1;
        while (client.read(input) >= 0) {
            input.flip();
            while (input.remaining() >= 8) {
                var start = input.position();
                var object = input.getInt(start);
                var word = input.getInt(start + 4);
                var size = word >>> 16;
                var opcode = word & 0xFFFF;
                if (input.remaining() < size) {
                    break;
                }
                var args = input.slice(start + 8, size - 8).order(ByteOrder.LITTLE_ENDIAN);
                input.position(start + size);
                if (object == 1 && opcode == 1) {
                    registry = args.getInt();
                    var name = 1;
                    for (var ignored : monitors) {
                        write(client, event(registry, 0, name++, "wl_output", 4));
                    }
                    write(client, event(registry, 0, 90, "wl_seat", 7));
                    if (xdg) {
                        write(client, event(registry, 0, 91, "zxdg_output_manager_v1", 3));
                    }
                } else if (object == 1 && opcode == 0) {
                    var callback = args.getInt();
                    if (error) {
                        write(client, event(1, 0, 1, 1, "invalid object"));
                    }
                    write(client, event(callback, 0, 0));
                    write(client, event(1, 1, callback));
                } else if (object == registry && opcode == 0) {
                    var global = args.getInt();
                    var length = args.getInt();
                    var bytes = new byte[length];
                    args.get(bytes);
                    args.position(args.position() + ((length + 3) & ~3) - length);
                    var iface = new String(bytes, 0, length - 1, StandardCharsets.UTF_8);
                    args.getInt();
                    var id = args.getInt();
                    if (iface.equals("zxdg_output_manager_v1")) {
                        manager = id;
                    } else if (iface.equals("wl_output")) {
                        var monitor = monitors.get(global - 1);
                        outputs.put(id, monitor);
                        write(client, event(id, 0, monitor.x(), monitor.y(), 300, 200, 0, "Make", "Model " + global, 0));
                        write(client, event(id, 1, 0, 640, 480, 60_000));
                        write(client, event(id, 1, 1, monitor.modeWidth(), monitor.modeHeight(), 60_000));
                        write(client, event(id, 3, monitor.scale()));
                        if (monitor.name() != null) {
                            write(client, event(id, 4, monitor.name()));
                        }
                        if (monitor.description() != null) {
                            write(client, event(id, 5, monitor.description()));
                        }
                        write(client, event(id, 2));
                    }
                } else if (object == manager && opcode == 1) {
                    var xdgId = args.getInt();
                    var monitor = outputs.get(args.getInt());
                    write(client, event(xdgId, 0, monitor.x(), monitor.y()));
                    write(client, event(xdgId, 1, monitor.logicalWidth(), monitor.logicalHeight()));
                    write(client, event(xdgId, 2));
                }
            }
            input.compact();
        }
    }

    private static void write(SocketChannel client, ByteBuffer message) throws IOException {
        while (message.hasRemaining()) {
            client.write(message);
        }
    }

    /** Lists the outputs of a simulated compositor. */
    private List<CaptureDevice.Screen> list(List<Monitor> monitors, boolean xdg, boolean error) throws Exception {
        var socket = directory.resolve("w" + sockets++ + ".sock");
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            var failure = new AtomicReference<Throwable>();
            var thread = Thread.ofPlatform().daemon().start(() -> {
                try (var client = server.accept()) {
                    serve(client, monitors, xdg, error);
                } catch (IOException ignored) {
                    // the client closed the connection
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            try {
                return WaylandOutputs.list(socket, Duration.ofSeconds(10));
            } finally {
                thread.join(5_000);
                if (failure.get() != null) {
                    throw new AssertionError(failure.get());
                }
            }
        }
    }

    private static final Monitor LAPTOP = new Monitor("eDP-1", "Built-in display", 0, 0, 2880, 1800, 2, 1440, 900);
    private static final Monitor EXTERNAL = new Monitor("DP-2", null, 1440, 0, 1920, 1080, 1, 1920, 1080);

    @Test
    @DisplayName("outputs take the logical position and size of xdg-output, the first standing for the primary")
    void xdgOutput() throws Exception {
        var screens = list(List.of(LAPTOP, EXTERNAL), true, false);
        assertEquals(List.of(new CaptureDevice.Screen("wayland:eDP-1", "Built-in display", 0, 0, 1440, 900, true),
                new CaptureDevice.Screen("wayland:DP-2", "Make Model 2", 1440, 0, 1920, 1080, false)), screens);
    }

    @Test
    @DisplayName("without xdg-output an output's size is its current mode divided by its scale")
    void modes() throws Exception {
        var unnamed = new Monitor(null, null, 1440, 0, 1920, 1080, 1, 0, 0);
        var screens = list(List.of(LAPTOP, unnamed), false, false);
        assertEquals(List.of(new CaptureDevice.Screen("wayland:eDP-1", "Built-in display", 0, 0, 1440, 900, true),
                new CaptureDevice.Screen("wayland:output-1", "Make Model 2", 1440, 0, 1920, 1080, false)), screens);
    }

    @Test
    @DisplayName("a compositor without outputs lists none, and a protocol error is malformed")
    void edges() throws Exception {
        assertEquals(List.of(), list(List.of(), true, false));
        assertThrows(MediaException.Malformed.class, () -> list(List.of(LAPTOP), true, true));
    }

    @Test
    @DisplayName("a string length near the int limit is malformed, never a huge allocation")
    void hugeString() {
        // a wl_registry.global whose interface string claims 0x7FFFFFFE bytes; its padded length overflows
        var global = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(2).putInt(20 << 16).putInt(1).putInt(0x7FFFFFFE).putInt(0);
        assertThrows(MediaException.Malformed.class, () -> WaylandOutputs.replay(global.array()));
    }

    @Test
    @DisplayName("a compositor that never answers times out")
    void silent() throws Exception {
        var socket = directory.resolve("s.sock");
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            assertThrows(IOException.class, () -> WaylandOutputs.list(socket, Duration.ofMillis(300)));
        }
    }

    @Test
    @DisplayName("the socket is WAYLAND_DISPLAY, absolute or in XDG_RUNTIME_DIR")
    void socketPath() {
        var display = System.getenv("WAYLAND_DISPLAY");
        var runtime = System.getenv("XDG_RUNTIME_DIR");
        var expected = display == null || display.isBlank() ? null : display.startsWith("/") ? Path.of(display)
                : runtime == null || runtime.isBlank() ? null : Path.of(runtime, display);
        assertEquals(expected, WaylandOutputs.socket());
    }
}

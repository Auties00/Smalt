package com.github.auties00.smalt.capture.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.capture.internal.linux.LinuxParsers;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;

/**
 * Fuzz targets of the protocol parsers of the Linux capture backend: the D-Bus messages of the
 * session bus and the screen cast portal's responses, the SPA format pods PipeWire negotiates, and
 * the Wayland events a compositor answers an output listing with. Each input is one byte stream as
 * the backend would receive it; decoded D-Bus messages are encoded again.
 */
class CaptureFuzzTest {
    @FuzzTest
    void dbus(byte[] data) {
        var run = Fuzz.start("dbus");
        if (run.value("decode", () -> LinuxParsers.dbus(data)).isPresent()) {
            run.output();
        }
    }

    @FuzzTest
    void spa(byte[] data) {
        var run = Fuzz.start("spa");
        var format = run.value("read", () -> LinuxParsers.spa(data));
        if (format.isPresent() && format.get().isPresent()) {
            run.output();
        }
    }

    @FuzzTest
    void wayland(byte[] data) {
        var run = Fuzz.start("wayland");
        var screens = run.value("replay", () -> LinuxParsers.wayland(data));
        if (screens.isPresent() && !screens.get().isEmpty()) {
            run.output();
        }
    }
}

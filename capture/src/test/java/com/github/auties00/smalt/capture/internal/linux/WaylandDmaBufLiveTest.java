package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Captures the sources of {@code .github/scripts/dmabuf-screencast-source.c}, which share real kernel
 * DMA-BUFs and send the pointer as {@code SPA_META_Cursor} metadata, through CI's scripted portal and
 * a real PipeWire daemon, and headless mutter, a real compositor that sends the pointer as metadata.
 * The picture and the pointer's four phases are documented in the source's header.
 */
@DisplayName("Wayland screen capture from DMA-BUFs with a metadata pointer (live)")
@EnabledOnOs(value = OS.LINUX, disabledReason = "[platform:linux] the Linux backend runs only on Linux")
class WaylandDmaBufLiveTest {
    private static final Duration CALL = Duration.ofSeconds(10);
    private static final String TEST_INTERFACE = "org.smalt.test.ScreenCast";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;
    private static final int[] RED = {255, 0, 0};
    private static final int[] BLUE = {0, 0, 255};
    private static final int[] MAGENTA = {255, 0, 255};
    private static final int[] GREEN = {0, 255, 0};
    private static final int DMA_BUF_SIZE = 16 * 4096;

    private enum Pointer { MAGENTA_AT_A, MAGENTA_AT_B, NONE, GREEN_AT_A }

    private static CaptureDevice.Screen device() {
        Skip.unlessDevice(!Skip.setting("smalt.test.waylandDmaBuf", "SMALT_TEST_WAYLAND_DMABUF").isEmpty(), "wayland-dmabuf",
                "set SMALT_TEST_WAYLAND_DMABUF where the portal has the DMA-BUF profiles");
        return new CaptureDevice.Screen("wayland:test", "the source", 0, 0, WIDTH, HEIGHT, true);
    }

    private static long portal(String method, String signature, Object... arguments) {
        try (var bus = DBusConnection.session(CALL)) {
            var reply = bus.call(DBusMessage.call(ScreenCastPortal.PORTAL, ScreenCastPortal.PORTAL_PATH, TEST_INTERFACE, method, signature,
                    arguments), CALL);
            return reply.body().isEmpty() ? 0 : (Long) reply.body().getFirst();
        }
    }

    private static void share(String profile) {
        portal("Share", "s", profile);
    }

    private static long lastCursorMode() {
        return portal("LastCursorMode", "");
    }

    @AfterEach
    void restore() {
        if (!Skip.setting("smalt.test.waylandDmaBuf", "SMALT_TEST_WAYLAND_DMABUF").isEmpty()) {
            share("default");
        }
    }

    private static boolean near(byte[] pixels, int stride, int x, int y, int[] color) {
        var at = y * stride + x * 4;
        for (var channel = 0; channel < 3; channel++) {
            if (Math.abs((pixels[at + channel] & 0xFF) - color[channel]) > 24) {
                return false;
            }
        }
        return true;
    }

    private static String sample(byte[] pixels, int stride, int x, int y) {
        var at = y * stride + x * 4;
        return (pixels[at] & 0xFF) + "," + (pixels[at + 1] & 0xFF) + "," + (pixels[at + 2] & 0xFF);
    }

    /**
     * Names the pointer a frame shows and fails on anything but exactly one phase's picture, so a mark
     * left by an earlier position, the transparent half drawn, or a wrong hot spot all fail.
     */
    private static Pointer classify(VideoCaptureSession.Frame frame, int originX, int originY) {
        var rgba = ImageOps.toRgba(frame.picture());
        var pixels = rgba.pixels();
        var stride = rgba.stride();
        var magentaAtA = near(pixels, stride, 104 - originX, 98 - originY, MAGENTA);
        var magentaAtB = near(pixels, stride, 404 - originX, 198 - originY, MAGENTA);
        var greenAtA = near(pixels, stride, 108 - originX, 104 - originY, GREEN);
        var pointer = magentaAtA && !magentaAtB && !greenAtA ? Pointer.MAGENTA_AT_A
                : magentaAtB && !magentaAtA && !greenAtA ? Pointer.MAGENTA_AT_B
                : greenAtA && !magentaAtA && !magentaAtB ? Pointer.GREEN_AT_A
                : !magentaAtA && !magentaAtB && !greenAtA ? Pointer.NONE : null;
        assertTrue(pointer != null, "several pointers: " + sample(pixels, stride, 104 - originX, 98 - originY) + " "
                + sample(pixels, stride, 404 - originX, 198 - originY) + " " + sample(pixels, stride, 108 - originX, 104 - originY));
        var left = new ArrayList<int[]>(List.of(new int[]{20, 20}, new int[]{300, 300}));
        var right = new ArrayList<int[]>(List.of(new int[]{340, 20}, new int[]{620, 340}));
        if (pointer == Pointer.MAGENTA_AT_A) {
            left.add(new int[]{104, 106});
        } else {
            left.add(new int[]{104, 98});
        }
        if (pointer == Pointer.GREEN_AT_A) {
            left.add(new int[]{108, 111});
        } else {
            left.add(new int[]{108, 104});
        }
        if (pointer == Pointer.MAGENTA_AT_B) {
            right.add(new int[]{404, 206});
        } else {
            right.add(new int[]{404, 198});
        }
        for (var point : left) {
            if (point[0] >= originX && point[1] >= originY && point[0] - originX < frame.picture().width() && point[1] - originY < frame.picture().height()) {
                assertTrue(near(pixels, stride, point[0] - originX, point[1] - originY, RED), pointer + " background at " + point[0] + "," + point[1]
                        + " is " + sample(pixels, stride, point[0] - originX, point[1] - originY));
            }
        }
        for (var point : right) {
            if (point[0] >= originX && point[1] >= originY && point[0] - originX < frame.picture().width() && point[1] - originY < frame.picture().height()) {
                assertTrue(near(pixels, stride, point[0] - originX, point[1] - originY, BLUE), pointer + " background at " + point[0] + "," + point[1]
                        + " is " + sample(pixels, stride, point[0] - originX, point[1] - originY));
            }
        }
        return pointer;
    }

    /** Reads until every phase was seen, with a deadline. */
    private static void assertEveryPhase(VideoCaptureSession session) throws InterruptedException {
        var seen = EnumSet.noneOf(Pointer.class);
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (seen.size() < Pointer.values().length && System.nanoTime() < deadline) {
            seen.add(classify(session.read(), 0, 0));
        }
        assertEquals(EnumSet.allOf(Pointer.class), seen);
    }

    private static void capture(String profile) {
        var device = device();
        share(profile);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var options = ScreenCaptureOptions.builder().frameRate(30).build();
            try (var session = PortalScreenSession.open(device, ScreenCastPortal.SOURCE_MONITOR, options)) {
                assertTrue(session.dmaBuf(), "the source shares only DMA-BUFs");
                assertEquals(ScreenCastPortal.CURSOR_METADATA, session.cursorMode());
                assertEquals(ScreenCastPortal.CURSOR_METADATA, lastCursorMode(), "what the portal was asked for");
                assertEquals(WIDTH, session.width());
                assertEquals(HEIGHT, session.height());
                assertEveryPhase(session);
            }
        });
    }

    @Test
    @DisplayName("linear DMA-BUFs are read, padded and offset, the pointer drawn from metadata in every phase, moved over the last pixels")
    void dmaBuf() {
        capture("dmabuf");
    }

    @Test
    @DisplayName("a DMA-BUF whose data and chunk sizes stay zero is read through the descriptor's size")
    void zeroSizes() {
        capture("zero");
    }

    @Test
    @DisplayName("given both, shared memory is preferred, and the pointer metadata reaches it too")
    void preferSharedMemory() {
        var device = device();
        share("both");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var options = ScreenCaptureOptions.builder().frameRate(30).build();
            try (var session = PortalScreenSession.open(device, ScreenCastPortal.SOURCE_MONITOR, options)) {
                assertFalse(session.dmaBuf());
                assertEveryPhase(session);
            }
        });
    }

    @Test
    @DisplayName("a producer with only tiled DMA-BUFs cannot be negotiated, which fails instead of showing garbage")
    void tiledRefused() {
        var device = device();
        share("tiled");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var options = ScreenCaptureOptions.builder().frameRate(30).build();
            var failure = assertThrows(MediaException.class, () -> PortalScreenSession.open(device, ScreenCastPortal.SOURCE_MONITOR, options).close());
            assertTrue(failure instanceof MediaException.Unsupported || failure.getMessage().contains("did not negotiate"), failure.toString());
        });
    }

    @Test
    @DisplayName("a pointer left out asks for the hidden mode and draws nothing")
    void hidden() {
        var device = device();
        share("dmabuf");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var options = ScreenCaptureOptions.builder().frameRate(30).cursor(false).build();
            try (var session = PortalScreenSession.open(device, ScreenCastPortal.SOURCE_MONITOR, options)) {
                assertEquals(ScreenCastPortal.CURSOR_HIDDEN, session.cursorMode());
                assertEquals(ScreenCastPortal.CURSOR_HIDDEN, lastCursorMode());
                assertTrue(session.dmaBuf());
                for (var index = 0; index < 45; index++) {
                    assertEquals(Pointer.NONE, classify(session.read(), 0, 0));
                }
            }
        });
    }

    @Test
    @DisplayName("a region shows the pointer at its place in the region, and a session can follow another")
    void region() {
        var device = device();
        share("dmabuf");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (var attempt = 0; attempt < 2; attempt++) {
                var options = ScreenCaptureOptions.builder().frameRate(30).region(60, 60, 400, 200).build();
                try (var session = PortalScreenSession.open(device, ScreenCastPortal.SOURCE_MONITOR, options)) {
                    assertEquals(400, session.width());
                    assertEquals(200, session.height());
                    var seen = EnumSet.noneOf(Pointer.class);
                    var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                    while (seen.size() < Pointer.values().length && System.nanoTime() < deadline) {
                        seen.add(classify(session.read(), 60, 60));
                    }
                    assertEquals(EnumSet.allOf(Pointer.class), seen);
                }
            }
        });
    }

    @Test
    @DisplayName("a producer that renegotiates its size brings new DMA-BUFs, which are mapped afresh and scaled to the session's size")
    void renegotiated() {
        var device = device();
        share("resize");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var options = ScreenCaptureOptions.builder().frameRate(30).build();
            try (var session = PortalScreenSession.open(device, ScreenCastPortal.SOURCE_MONITOR, options)) {
                assertTrue(session.dmaBuf());
                assertEquals(WIDTH, session.width());
                assertEquals(HEIGHT, session.height());
                // The left half is green only in the buffers of the second size, so a mapping kept across the renegotiation
                // would go on showing red.
                var red = 0;
                var green = 0;
                var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while ((red < 2 || green < 2) && System.nanoTime() < deadline) {
                    var frame = session.read();
                    assertEquals(WIDTH, frame.picture().width());
                    assertEquals(HEIGHT, frame.picture().height());
                    var rgba = ImageOps.toRgba(frame.picture());
                    var pixels = rgba.pixels();
                    var stride = rgba.stride();
                    assertTrue(near(pixels, stride, 620, 340, BLUE), "the right half is " + sample(pixels, stride, 620, 340));
                    if (near(pixels, stride, 20, 20, RED)) {
                        red++;
                    } else if (near(pixels, stride, 20, 20, GREEN)) {
                        green++;
                    } else {
                        fail("the left half is " + sample(pixels, stride, 20, 20));
                    }
                }
                assertTrue(red >= 2 && green >= 2, red + " red and " + green + " green frames");
            }
        });
    }

    @Test
    @DisplayName("the kernel accepts the start and end of a read of a real DMA-BUF, sized and mapped through its descriptor")
    void kernelSync() throws Throwable {
        device();
        var posix = new Posix();
        try (var arena = Arena.ofConfined()) {
            var fd = realDmaBuf(posix, arena);
            try {
                var writable = posix.mmap(fd, DMA_BUF_SIZE, 0);
                assertNotNull(writable);
                writable.set(JAVA_BYTE, DMA_BUF_SIZE - 1, (byte) 0x5A);
                posix.munmap(writable);
                var mapper = new DmaBufMapper(posix, arena);
                var mapped = mapper.map(1, fd, 0);
                assertEquals(DMA_BUF_SIZE, mapped.byteSize());
                assertEquals(0, mapper.beginRead(fd));
                assertEquals((byte) 0x5A, mapped.get(JAVA_BYTE, DMA_BUF_SIZE - 1));
                assertEquals(0, mapper.endRead(fd));
                var invalid = arena.allocate(JAVA_LONG);
                invalid.set(JAVA_LONG, 0, 0xFFL);
                assertNotEquals(0, posix.ioctl(fd, DmaBufMapper.IOCTL_SYNC, invalid), "the kernel checks the flags of this request");
                mapper.close();
                assertEquals(0, mapper.mapped());
            } finally {
                posix.close(fd);
            }
        }
    }

    /** Allocates a DMA-BUF of DMA_BUF_SIZE bytes, as the source of the live tests does. */
    private static int realDmaBuf(Posix posix, Arena arena) throws Throwable {
        var heap = posix.open("/dev/dma_heap/system", 0);
        if (heap >= 0) {
            try {
                // struct dma_heap_allocation_data: len, fd, fd_flags (O_RDWR | O_CLOEXEC), heap_flags
                var allocation = arena.allocate(24, 8);
                allocation.set(JAVA_LONG, 0, DMA_BUF_SIZE);
                allocation.set(JAVA_INT, 12, 0x80002);
                assertEquals(0, posix.ioctl(heap, 0xC0184800L, allocation), "DMA_HEAP_IOCTL_ALLOC");
                return allocation.get(JAVA_INT, 8);
            } finally {
                posix.close(heap);
            }
        }
        var linker = Linker.nativeLinker();
        var lookup = linker.defaultLookup();
        var memfdCreate = linker.downcallHandle(lookup.find("memfd_create").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        var ftruncate = linker.downcallHandle(lookup.find("ftruncate").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG));
        var fcntl = linker.downcallHandle(lookup.find("fcntl").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
                Linker.Option.firstVariadicArg(2));
        var ioctl = linker.downcallHandle(lookup.find("ioctl").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS),
                Linker.Option.firstVariadicArg(2));
        // MFD_ALLOW_SEALING | MFD_CLOEXEC; the pages udmabuf pins must not shrink
        var memory = (int) memfdCreate.invokeExact(arena.allocateFrom("smalt-test"), 3);
        assertTrue(memory >= 0, "memfd_create");
        try {
            assertEquals(0, (int) ftruncate.invokeExact(memory, (long) DMA_BUF_SIZE));
            // F_ADD_SEALS, F_SEAL_SHRINK
            assertEquals(0, (int) fcntl.invokeExact(memory, 1033, 2));
            var device = posix.open("/dev/udmabuf", 2);
            assertTrue(device >= 0, "neither /dev/dma_heap/system nor /dev/udmabuf can be opened");
            try {
                // struct udmabuf_create: memfd, flags (UDMABUF_FLAGS_CLOEXEC), offset, size
                var create = arena.allocate(24, 8);
                create.set(JAVA_INT, 0, memory);
                create.set(JAVA_INT, 4, 1);
                create.set(JAVA_LONG, 16, DMA_BUF_SIZE);
                // UDMABUF_CREATE, _IOW('u', 0x42, struct udmabuf_create)
                var fd = (int) ioctl.invokeExact(device, 0x40187542L, create);
                assertTrue(fd >= 0, "UDMABUF_CREATE");
                return fd;
            } finally {
                posix.close(device);
            }
        } finally {
            posix.close(memory);
        }
    }

    @Test
    @DisplayName("headless mutter's metadata pointer is drawn, and moves with the virtual pointer")
    void mutter() {
        Skip.unlessDevice(!Skip.setting("smalt.test.waylandMutter", "SMALT_TEST_WAYLAND_MUTTER").isEmpty(), "wayland-mutter",
                "set SMALT_TEST_WAYLAND_MUTTER where the portal has a stream of headless mutter");
        share("mutter");
        var device = new CaptureDevice.Screen("wayland:mutter", "mutter", 0, 0, 1280, 720, true);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            var options = ScreenCaptureOptions.builder().frameRate(15).build();
            try (var session = PortalScreenSession.open(device, ScreenCastPortal.SOURCE_MONITOR, options)) {
                assertEquals(ScreenCastPortal.CURSOR_METADATA, session.cursorMode());
                // The virtual pointer alternates between (640,360) and (660,380), and nothing else moves on this desktop, so
                // two frames differ only where the arrow was and is. Its tip is its hot spot, so the rectangle of the
                // differences starts at the first place, unless the image is misplaced.
                byte[] previous = null;
                int[] changed = null;
                var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (changed == null && System.nanoTime() < deadline) {
                    var rgba = ImageOps.toRgba(session.read().picture());
                    if (previous != null) {
                        changed = differing(previous, rgba.pixels(), rgba.stride(), 1280, 720);
                    }
                    previous = rgba.pixels().clone();
                }
                assertTrue(changed != null, "the picture never changed, so no pointer was drawn");
                var box = changed[0] + "," + changed[1] + ".." + changed[2] + "," + changed[3];
                assertTrue(changed[0] >= 638 && changed[0] <= 642 && changed[1] >= 358 && changed[1] <= 362, "the changes start at " + box);
                assertTrue(changed[2] >= 665 && changed[2] <= 720 && changed[3] >= 385 && changed[3] <= 440, "the changes end at " + box);
            }
        });
    }

    /** Returns the rectangle of the pixels that differ between two frames, left, top, right and bottom, or null. */
    private static int[] differing(byte[] first, byte[] second, int stride, int width, int height) {
        var left = width;
        var top = height;
        var right = -1;
        var bottom = -1;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * stride + x * 4;
                for (var channel = 0; channel < 3; channel++) {
                    if (Math.abs((first[at + channel] & 0xFF) - (second[at + channel] & 0xFF)) > 24) {
                        left = Math.min(left, x);
                        right = Math.max(right, x);
                        top = Math.min(top, y);
                        bottom = Math.max(bottom, y);
                    }
                }
            }
        }
        return right < 0 ? null : new int[]{left, top, right, bottom};
    }
}

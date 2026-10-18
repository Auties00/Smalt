package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Enumerates the displays CoreGraphics reports as active.
 *
 * <p>A screen's id is its {@code CGDirectDisplayID} in decimal. Its position is the origin of
 * {@code CGDisplayBounds}, in the global display space, which macOS measures in points with the
 * main display's top-left corner at the origin; its size is the pixel size of the display's
 * current mode, which is twice the size in points on a Retina display. Displays that mirror
 * another one in software are skipped, as they show the same content. The main display (the one
 * with the menu bar) comes first.
 */
final class MacDisplays {
    /**
     * The name of a built-in display.
     */
    private static final String BUILT_IN = "Built-in Display";

    /**
     * The name of the first external display; later ones are numbered from 2.
     */
    private static final String EXTERNAL = "External Display";

    /**
     * The failure message of a missing Screen Recording permission.
     */
    static final String PERMISSION_MESSAGE = "the Screen Recording permission is not granted to this application;"
            + " allow it in System Settings > Privacy & Security > Screen Recording (Screen & System Audio"
            + " Recording on macOS 15 and later), then restart the application";

    /**
     * Prevents instantiation.
     */
    private MacDisplays() {
        throw new AssertionError();
    }

    /**
     * Describes one display as CoreGraphics reports it.
     *
     * @param id the {@code CGDirectDisplayID}
     * @param x the left edge in the global display space
     * @param y the top edge in the global display space
     * @param width the width in pixels
     * @param height the height in pixels
     * @param builtin whether the display is built into the computer
     */
    record Display(int id, int x, int y, int width, int height, boolean builtin) {
    }

    /**
     * Lists the screens.
     *
     * <p>Runs on a platform thread; needs no permission.
     *
     * @return the screens, the main one first
     */
    static List<CaptureDevice.Screen> list() {
        var ids = CoreGraphics.activeDisplays();
        var displays = new ArrayList<Display>(ids.length);
        for (var id : ids) {
            if (CoreGraphics.isMirror(id)) {
                continue;
            }
            var bounds = CoreGraphics.bounds(id);
            var size = CoreGraphics.pixelSize(id);
            displays.add(new Display(id, (int) Math.round(bounds[0]), (int) Math.round(bounds[1]),
                    (int) Math.min(size[0], Integer.MAX_VALUE), (int) Math.min(size[1], Integer.MAX_VALUE),
                    CoreGraphics.isBuiltin(id)));
        }
        return screens(displays, CoreGraphics.mainDisplay());
    }

    /**
     * Turns display descriptions into screens.
     *
     * <p>Displays without a positive size are dropped. The main display is moved first and
     * marked primary; built-in displays are named {@code "Built-in Display"}, the others
     * {@code "External Display"}, {@code "External Display 2"} and so on in list order.
     *
     * @param displays the displays, in CoreGraphics order
     * @param mainId the main display's identifier
     * @return the screens
     */
    static List<CaptureDevice.Screen> screens(List<Display> displays, int mainId) {
        Objects.requireNonNull(displays, "displays");
        var ordered = new ArrayList<Display>(displays.size());
        for (var display : displays) {
            if (display.width() <= 0 || display.height() <= 0) {
                continue;
            }
            if (display.id() == mainId) {
                ordered.addFirst(display);
            } else {
                ordered.add(display);
            }
        }
        var screens = new ArrayList<CaptureDevice.Screen>(ordered.size());
        var externals = 0;
        for (var display : ordered) {
            String name;
            if (display.builtin()) {
                name = BUILT_IN;
            } else {
                externals++;
                name = externals == 1 ? EXTERNAL : EXTERNAL + " " + externals;
            }
            screens.add(new CaptureDevice.Screen(Integer.toUnsignedString(display.id()), name, display.x(), display.y(),
                    display.width(), display.height(), display.id() == mainId));
        }
        return List.copyOf(screens);
    }

    /**
     * Parses a screen id back into a display identifier.
     *
     * @return the {@code CGDirectDisplayID}
     * @throws NumberFormatException if the id is not an unsigned 32-bit decimal number
     */
    static int displayId(CaptureDevice.Screen screen) {
        return Integer.parseUnsignedInt(screen.id());
    }

    /**
     * Checks that this process may record the screen, asking the user when it may not.
     *
     * <p>The system prompt is shown only the first time; a permission granted from it takes
     * effect after the application restarts, so a missing permission always fails.
     *
     * @throws MediaException.Io if the Screen Recording permission is not granted
     */
    static void requireCapturePermission() {
        if (CoreGraphics.screenCaptureAllowed()) {
            return;
        }
        CoreGraphics.requestScreenCapture();
        throw permissionDenied();
    }

    /**
     * Builds the failure raised when the Screen Recording permission is missing.
     *
     * @return the failure
     */
    static MediaException.Io permissionDenied() {
        return new MediaException.Io(PERMISSION_MESSAGE, new IOException("screen capture access is not granted"));
    }
}

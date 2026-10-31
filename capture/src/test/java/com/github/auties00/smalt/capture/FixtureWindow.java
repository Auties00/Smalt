package com.github.auties00.smalt.capture;

import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Skip;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A top-level window of known content shown by a child JVM through AWT, for the window and screen capture
 * tests of every platform.
 *
 * <p>The child shows an undecorated window at a requested place, filled left half red and right half blue,
 * or with one solid color, prints {@code READY} once it is painted, moves the pointer when asked ({@code MOVE x y}
 * on its standard input), and exits when its standard input closes. A machine that cannot show a window (no
 * display, a headless JDK) skips the calling test with {@code device:window}.
 */
public final class FixtureWindow implements AutoCloseable {
    /**
     * The fully saturated red of the left half.
     */
    public static final int RED = 0xFF0000;

    /**
     * The fully saturated blue of the right half.
     */
    public static final int BLUE = 0x0000FF;

    /**
     * The solid green of an occluding window.
     */
    public static final int GREEN = 0x00FF00;

    private static final String SOURCE = """
            import java.awt.*;
            public class SmaltFixtureWindow {
                public static void main(String[] args) throws Exception {
                    var title = args[0];
                    int x = Integer.parseInt(args[1]), y = Integer.parseInt(args[2]);
                    int w = Integer.parseInt(args[3]), h = Integer.parseInt(args[4]);
                    var fill = args[5];
                    var top = Boolean.parseBoolean(args[6]);
                    try {
                        EventQueue.invokeAndWait(() -> {
                            var frame = new Frame(title);
                            frame.setUndecorated(true);
                            if (fill.equals("split")) {
                                frame.setLayout(new GridLayout(1, 2, 0, 0));
                                var left = new Panel();
                                left.setBackground(new Color(0xFF0000));
                                var right = new Panel();
                                right.setBackground(new Color(0x0000FF));
                                frame.add(left);
                                frame.add(right);
                            } else {
                                frame.setBackground(new Color(Integer.parseInt(fill, 16)));
                            }
                            frame.setBounds(x, y, w, h);
                            frame.setAlwaysOnTop(top);
                            frame.setVisible(true);
                            frame.toFront();
                        });
                    } catch (Throwable failure) {
                        System.out.println("UNAVAILABLE " + failure);
                        System.out.flush();
                        System.exit(3);
                    }
                    Thread.sleep(1500);
                    Robot robot = null;
                    try {
                        robot = new Robot();
                    } catch (Throwable ignored) {
                    }
                    System.out.println("READY");
                    System.out.flush();
                    var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                    for (var line = in.readLine(); line != null; line = in.readLine()) {
                        if (line.startsWith("MOVE ") && robot != null) {
                            var parts = line.split(" ");
                            robot.mouseMove(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                            robot.waitForIdle();
                            System.out.println("MOVED");
                            System.out.flush();
                        }
                    }
                    System.exit(0);
                }
            }
            """;

    private final Process process;

    private final Writer input;

    private final BufferedReader output;

    private final String title;

    private FixtureWindow(Process process, String title) {
        this.process = process;
        this.title = title;
        this.input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        this.output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /**
     * Shows a window split into a red and a blue half.
     *
     * @param x the left edge on the desktop, in the platform's window units
     * @param y the top edge
     * @param width the width
     * @param height the height
     * @return the window, shown and painted
     */
    public static FixtureWindow split(int x, int y, int width, int height) {
        return show("split", x, y, width, height, false);
    }

    /**
     * Shows a window of one color that stays above the other windows.
     *
     * @param color the color, {@code 0xRRGGBB}
     * @return the window, shown and painted
     */
    public static FixtureWindow solidOnTop(int color, int x, int y, int width, int height) {
        return show(String.format("%06x", color), x, y, width, height, true);
    }

    private static FixtureWindow show(String fill, int x, int y, int width, int height, boolean top) {
        var title = "smalt-fixture-" + UUID.randomUUID().toString().substring(0, 8);
        Process process;
        try {
            var directory = Scratch.directory("fixture-window");
            Files.createDirectories(directory);
            var source = directory.resolve("SmaltFixtureWindow.java");
            if (!Files.exists(source)) {
                Files.writeString(source, SOURCE, StandardCharsets.UTF_8);
            }
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-Dsun.java2d.uiScale=1");
            command.add("-Djava.awt.headless=false");
            command.add(source.toString());
            command.addAll(List.of(title, Integer.toString(x), Integer.toString(y), Integer.toString(width),
                    Integer.toString(height), fill, Boolean.toString(top)));
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException exception) {
            throw Skip.now("device:window", "cannot start the fixture window: " + exception);
        }
        var window = new FixtureWindow(process, title);
        var line = window.readLine(Duration.ofSeconds(60));
        if (!"READY".equals(line)) {
            window.close();
            Skip.unlessDevice(false, "window", "the fixture window cannot be shown: " + line);
        }
        return window;
    }

    /**
     * {@return the window's title, unique to this fixture}
     */
    public String title() {
        return title;
    }

    /**
     * Waits until the capture API lists the window.
     *
     * @param timeout the longest wait
     * @return the window as listed
     */
    public CaptureDevice.Window find(Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<CaptureDevice.Window> found = VideoDevices.windows().stream()
                    .filter(window -> window.name().equals(title)).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the fixture window " + title + " is not listed among " + VideoDevices.windows());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
    }

    /**
     * Moves the pointer through the child's {@code java.awt.Robot}.
     *
     * @param x the horizontal position on the desktop
     * @param y the vertical position on the desktop
     * @return whether the child confirmed the move
     */
    public boolean movePointer(int x, int y) {
        try {
            input.write("MOVE " + x + " " + y + "\n");
            input.flush();
        } catch (IOException exception) {
            return false;
        }
        return "MOVED".equals(readLine(Duration.ofSeconds(10)));
    }

    /**
     * {@return whether the child process still runs}
     */
    public boolean alive() {
        return process.isAlive();
    }

    private String readLine(Duration timeout) {
        var line = CompletableFuture.supplyAsync(() -> {
            try {
                return output.readLine();
            } catch (IOException exception) {
                return "failed: " + exception;
            }
        });
        try {
            return line.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            return "no answer: " + exception;
        }
    }

    /**
     * Closes the window and waits for the child to exit.
     */
    @Override
    public void close() {
        try {
            input.close();
        } catch (IOException ignored) {
            // the child is destroyed below if it does not exit
        }
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}

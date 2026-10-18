package com.github.auties00.smalt.capture.internal;

import com.github.auties00.smalt.MediaException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The platform daemon thread that opens a device and then pumps its frames or samples, with the
 * hand-shake that lets the opener wait for the device to open.
 *
 * <p>The opener constructs the pump, starts it with the session's work and calls
 * {@link #awaitOpen(Duration, String, Runnable)}; the work calls {@link #failOpen(MediaException)}
 * when the device cannot be opened and {@link #markOpen()} once opening ended either way. Closing
 * joins the thread for a bounded time with {@link #join(Duration)}. This class is thread-safe.
 */
public final class PumpThread {
    private final String name;
    private final int priority;
    private final CountDownLatch opened = new CountDownLatch(1);
    private volatile MediaException openFailure;
    private volatile Thread thread;

    /**
     * Constructs a pump that is not started.
     *
     * @param name the name of the thread
     * @param priority the priority of the thread, from {@link Thread#MIN_PRIORITY} to
     * {@link Thread#MAX_PRIORITY}
     */
    public PumpThread(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    /**
     * Starts the thread.
     *
     * @param work the work: open the device, then pump until the session stops
     * @throws IllegalStateException if the pump was already started
     */
    public void start(Runnable work) {
        if (thread != null) {
            throw new IllegalStateException("the pump " + name + " was already started");
        }
        thread = Thread.ofPlatform().name(name).daemon().priority(priority).start(work);
    }

    /**
     * Records why the device could not be opened; called by the work before {@link #markOpen()}.
     *
     * @param failure the failure the opener throws
     */
    public void failOpen(MediaException failure) {
        openFailure = failure;
    }

    /**
     * Releases the opener; called by the work once opening succeeded or failed.
     */
    public void markOpen() {
        opened.countDown();
    }

    /**
     * Waits until the work opened the device.
     *
     * <p>When the wait is interrupted, times out or the opening failed, {@code abandon} runs before
     * the failure is thrown, so the work releases the device as soon as it gets it. The interrupt
     * status of an interrupted caller is restored.
     *
     * @param timeout the longest wait
     * @param device the device, as named in failure messages (for example "the camera Front")
     * @param abandon makes the work give up and release the device
     * @throws MediaException.Io if the wait is interrupted or times out
     * @throws MediaException the failure the work recorded with {@link #failOpen(MediaException)}
     */
    public void awaitOpen(Duration timeout, String device, Runnable abandon) {
        boolean done;
        try {
            done = opened.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            abandon.run();
            var message = "interrupted while opening " + device;
            throw new MediaException.Io(message, new IOException(message, exception));
        }
        if (!done) {
            abandon.run();
            var message = "timed out opening " + device;
            throw new MediaException.Io(message, new IOException(message));
        }
        var failure = openFailure;
        if (failure != null) {
            abandon.run();
            throw failure;
        }
    }

    /**
     * {@return whether the calling thread is this pump's thread}
     */
    public boolean isCurrentThread() {
        return Thread.currentThread() == thread;
    }

    /**
     * Waits for the thread to terminate, for at most a timeout; returns at once when called from the
     * thread itself or before it was started.
     *
     * @param timeout the longest wait
     */
    public void join(Duration timeout) {
        var current = thread;
        if (current != null && current != Thread.currentThread()) {
            join(current, timeout);
        }
    }

    /**
     * Waits for a thread to terminate, for at most a timeout, without being cut short by an interrupt
     * of the caller, whose interrupt status is restored afterwards.
     *
     * @param timeout the longest wait
     */
    public static void join(Thread thread, Duration timeout) {
        var interrupted = false;
        var deadline = System.nanoTime() + timeout.toNanos();
        while (thread.isAlive()) {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                thread.join(Duration.ofNanos(remaining));
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

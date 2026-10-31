package com.github.auties00.smalt.capture;

/**
 * Receives the changes of the cameras and screens: a camera plugged in or removed, a new default camera,
 * a monitor connected, disconnected or rearranged.
 *
 * <p>Listeners are registered with {@link VideoDevices#addListener(VideoDeviceListener)}. Events are
 * delivered in the order they are detected, one at a time, on a dedicated thread of this module, never on
 * a thread of the operating system; a listener may therefore block, list devices and open or close
 * sessions, at the cost of delaying the events that follow.
 */
@FunctionalInterface
public interface VideoDeviceListener {
    /**
     * Handles one change.
     *
     * <p>An exception thrown by this method is ignored, and later events are still delivered.
     *
     * @param event the change
     */
    void onEvent(VideoDeviceEvent event);
}

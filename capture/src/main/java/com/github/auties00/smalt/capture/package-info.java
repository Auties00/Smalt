/**
 * Camera and screen capture through the operating system's own APIs: {@link VideoDevices} lists and
 * opens the {@link CaptureDevice}s, each {@link VideoCaptureSession} delivers planar 4:2:0 frames, and
 * a camera's {@link CaptureMode}s describe the sizes and frame rates it captures at.
 *
 * <p>Pictures converted from R'G'B' (every screen, and the cameras that deliver R'G'B') are BT.709 at
 * limited range; pictures a camera delivers as Y'CbCr keep the matrix and range the camera reports.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.capture;

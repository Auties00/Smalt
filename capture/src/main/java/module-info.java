/**
 * Camera, screen, window, microphone and speaker access of Smalt, through the operating systems' own APIs
 * called with the Foreign Function and Memory API: Media Foundation, DXGI or GDI, Windows Graphics Capture
 * and WASAPI on Windows, AVFoundation, ScreenCaptureKit and CoreAudio on macOS, Video4Linux2, X11 and ALSA
 * on Linux.
 *
 * <p>This is the only Smalt module that calls native code, so it is the only one that needs
 * {@code --enable-native-access=com.github.auties00.smalt.capture}. The other launch requirements
 * and the threading model are those of the core module ({@code com.github.auties00.smalt}).
 */
module com.github.auties00.smalt.capture {
    // Smalt core: the model and the codec service-provider interfaces
    requires transitive com.github.auties00.smalt;
    requires com.github.auties00.smalt.image;

    // Vector API: optional, selected at runtime by the core's internal.simd.Simd
    requires static jdk.incubator.vector;

    // Camera and screen capture through the operating system's APIs
    exports com.github.auties00.smalt.capture;

    // Microphone capture and speaker playback through the operating system's APIs
    exports com.github.auties00.smalt.capture.audio;

    // The training round of the conversion kernels, run by the ahead-of-time cache training workloads
    exports com.github.auties00.smalt.capture.internal.convert to com.github.auties00.smalt.aot;
}

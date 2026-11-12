<div align="center">

# smalt-capture

**Cameras, screens, windows, microphones and speakers**

![Artifact](https://img.shields.io/badge/artifact-smalt--capture-2f6fe6?style=flat-square&labelColor=0b2a6f)
![Module](https://img.shields.io/badge/module-com.github.auties00.smalt.capture-2f6fe6?style=flat-square&labelColor=0b2a6f)

[Smalt](../README.md) | [core](../core/README.md) | [image](../image/README.md) | [audio](../audio/README.md) | [video](../video/README.md) | [mpeg](../mpeg/README.md) | **capture** | [raw](../raw/README.md) | [desktop](../desktop/README.md) | [aot](../aot/README.md) | [bom](../bom/README.md)

</div>

`smalt-capture` reads cameras, screens, windows and microphones and plays to speakers. It calls the operating
system through the Foreign Function and Memory API, so there is still no native library to ship. It depends on
[`smalt-core`](../core/README.md) and [`smalt-image`](../image/README.md).

**Contents:** [Install](#install) | [Guide](#guide) | [Backends](#backends) | [Known limits](#known-limits)

> [!IMPORTANT]
> This is the only Smalt artifact that needs a JVM flag:
> `--enable-native-access=com.github.auties00.smalt.capture` on the module path, or
> `--enable-native-access=ALL-UNNAMED` on the class path.

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-capture</artifactId>
</dependency>
```

The version comes from the [BOM](../bom/README.md); Gradle coordinates and JVM flags are in the
[root README](../README.md#install).

## Guide

### Capture the camera

```java
var camera = VideoDevices.defaultCamera().orElseThrow();
try (var session = VideoDevices.openCapture(camera)) {
    var frame = session.read(); // blocks until the next frame
    var picture = frame.picture();
}
```

> [!IMPORTANT]
> Every operation is a plain blocking call: there is no `CompletableFuture` anywhere in the
> API. Run long work on your own (virtual) threads; Smalt parallelizes each operation internally on one
> bounded pool, sized by the `smalt.threads` system property (`MediaRuntime.parallelism()` reports it). Options
> that take a thread count use `0` for that shared pool and `1` for the calling thread only.

### Capture a window or a part of a screen

```java
var window = VideoDevices.windows().getFirst();            // front to back
var options = ScreenCaptureOptions.builder()
        .frameRate(30)
        .region(0, 0, window.width() / 2, window.height())  // the left half, in the window's pixels
        .cursor(false)                                      // leave the pointer out
        .build();
try (var session = VideoDevices.openCapture(window, options)) {
    var frame = session.read();
}
```

A camera session also adjusts the camera (`session.controls()`: exposure in microseconds, white balance in
kelvin, power-line frequency in hertz, focus, zoom, pan, tilt and the picture controls, each with its range and
automatic mode), and `VideoDevices.addListener` reports cameras and screens as they are plugged in, removed or
rearranged, on a thread of its own.

### Capture the microphone and play to the speaker

```java
var options = AudioStreamOptions.forVoice(); // 16 kHz mono, 10 ms periods
try (var microphone = AudioDevices.openCapture(AudioDevices.defaultInput().orElseThrow(), options);
     var speaker = AudioDevices.openPlayback(AudioDevices.defaultOutput().orElseThrow(), options)) {
    var frame = new short[160];
    while (microphone.read(frame, 0, 160) == 160) { // blocks until 10 ms were captured
        speaker.write(frame, 0, 160);              // blocks only while the queue is full
    }
}
```

`AudioDevices` lists inputs and outputs (id, name, default flag, native formats), opens them in the shared
or exclusive mode at any rate and channel count, captures what an output plays (`openLoopback`, Windows, macOS 14.2 and Linux), and
reports new default devices and devices plugged in or removed to an `AudioDeviceListener`. Each session runs
its own high-priority device thread; `read` and `write` move float or 16-bit samples through a bounded queue,
and `delayMicros()` on both sides gives the stream delay of an echo canceller such as `VoiceProcessor`. An
unplugged device fails the next `read` or `write` with `MediaException.Io`.

## Backends

What each operating system provides, in package `capture` of `com.github.auties00.smalt`:

- **Cameras, screens and windows**
  - *Artifact:* `smalt-capture`
  - *Package:* `capture`
  - *Backends:* Windows: Media Foundation (camera controls through the source's `IAMCameraControl` and `IAMVideoProcAmp`), DXGI desktop duplication or GDI (screens), Windows Graphics Capture or `PrintWindow` (windows), configuration manager notifications of cameras and monitors. macOS: AVFoundation (focus, exposure and white balance modes, zoom), ScreenCaptureKit (screens and windows; `CGDisplayCreateImage` screens before 12.3). Linux: V4L2 single-planar and multi-planar cameras (with their controls; `/dev` watched for cameras) in every field order (interlaced, sequential and alternating fields woven, single fields line-doubled) and in planar, semi-planar and packed 4:2:0, 4:2:2 and 4:4:4 Y'CbCr, 16-, 24- and 32-bit R'G'B', 8- to 16-bit luma and Motion-JPEG; in a Wayland session, screens (the compositor's outputs) through the `xdg-desktop-portal` screen cast portal and PipeWire, in shared memory or linear DMA-BUFs, the pointer embedded by the compositor or drawn from the stream's metadata; X11 with MIT-SHM, Composite and XFixes (screens and windows of TrueColor visuals of 16, 24 or 32 bits per pixel, pointer). Screen and window regions, the pointer drawn or left out (`ScreenCaptureOptions`), exposure, focus, zoom, white balance, power-line frequency and the picture controls of a camera (`CameraControls`), and a listener of cameras and screens plugged in, removed or rearranged (`VideoDevices.addListener`). Called with FFM, so it needs `--enable-native-access=com.github.auties00.smalt.capture`.
- **Microphones and speakers**
  - *Artifact:* `smalt-capture`
  - *Package:* `capture.audio`
  - *Backends:* Windows: WASAPI (shared mode with the engine's PCM converter and `IAudioClient3` short periods, exclusive mode, loopback of an output, endpoint notifications). macOS: CoreAudio HAL output unit (shared mode, exclusive mode through the device's hog mode, loopback of an output through a process tap from macOS 14.2). Linux: ALSA, whose `default` device reaches PulseAudio or PipeWire (shared mode; exclusive mode opens a device without software resampling), plus the sinks and sources of a PulseAudio or PipeWire (`pipewire-pulse`) sound server, whose defaults are then the default devices; loopback of an output through the monitor source of the sink it plays to; changes reported as they happen through the server's subscription and `/dev/snd`. Float or 16-bit samples, any rate and channel count in the shared mode, capture and playback delays for echo cancellation. Run on the CI runners of all three systems against loopback devices.

## Known limits

> [!WARNING]
> Capture depends on the operating system, its version and the permissions the user grants. Open an entry
> to see what a platform cannot do.

<details>
<summary><b>Tested devices</b></summary>

The capture backends run on the Windows, macOS and Linux CI runners against virtual devices (loopback audio
pairs, the runners' displays, `v4l2loopback` and `vivid` cameras on Linux), except the Windows and macOS
cameras: no camera can be provisioned on those runners, so the camera capture of those two systems is not
run there.

</details>

<details>
<summary><b>Camera controls on macOS</b></summary>

Camera controls on macOS are the focus, exposure and white balance modes (continuous or locked) and, where
the device offers it, the zoom: AVFoundation has no manual values for the others on macOS, and no
power-line frequency. Windows camera controls are those the driver exposes through `IAMCameraControl` and
`IAMVideoProcAmp`; the exposure of a Windows camera steps in powers of two. macOS reports cameras and screens
plugged in or removed by listing them every second (cameras every five seconds), so its events follow a change
within that interval. The Linux camera controls and camera hotplug are tested against the `vivid` and
`v4l2loopback` drivers (and `v4l2-ctl`); the Windows camera controls only against simulated `IAMCameraControl`
and `IAMVideoProcAmp` interfaces, and the macOS ones not at all, since no camera can be provisioned on those CI
runners, where cameras plugged in or removed are not tested either.

</details>

<details>
<summary><b>Window and screen capture</b></summary>

Window capture on X11 without the Composite extension reads the window itself, so the parts other windows
cover hold whatever the X server returns; window capture on macOS needs ScreenCaptureKit (macOS 12.3), and the
screen snapshots of older macOS versions never show the pointer. On Wayland a screen is captured through the
screen cast portal, so the desktop may ask the user which monitor to share, and its choice is what the session
captures; a compositor that offers only DMA-BUF buffers is captured when one of them is linear (tiled buffers need a
graphics API to read, so such a compositor fails to negotiate), and reading them is slower than reading shared memory,
which is preferred when offered. The pointer is drawn from the stream's metadata when the portal cannot embed it, in
the 4-byte R'G'B' bitmap formats, taken as premultiplied, and a portal offering neither mode shows no pointer. Wayland
windows are not listed (Wayland has no protocol to list other clients'
windows; the portal shares one only through the desktop's own picker); the X11 windows of a Wayland session
(XWayland) are captured as X11 windows. Packed 24-bit X11 pixmaps are converted but tested only against synthetic
images, since no current X server offers that format (X.Org removed it in 1.20).

</details>

<details>
<summary><b>macOS loopback capture</b></summary>

macOS loopback capture needs macOS 14.2 and the user's System Audio Recording permission, and taps only the first
output stream of a device that has several. It reads the refusal through the private `TCC.framework`, the only query
macOS has, and throws `MediaException.Unsupported`; before the user answers, opening (for a time the system bounds) and
reads wait. An exclusive stream runs at
the device's channel count, and the hog mode belongs to the process and the device, so an exclusive input and output
of one device share a rate. A shared capture stops delivering while its device runs at another rate than it opened
at, for example under an exclusive stream of the same process, and resumes when the rate returns; a loopback follows
the change. Process taps and hog mode ran on the CI runners against BlackHole only, not on audio hardware or a device
with several streams.

</details>

<details>
<summary><b>Linux loopback capture</b></summary>

Linux loopback capture needs a PulseAudio or PipeWire sound server (bare ALSA has none); an ALSA device naming a
card maps to the server's sink of that card through its `alsa.card` property, which the CI runners, without an
ALSA card, test only against simulated server listings.

</details>

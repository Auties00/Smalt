# smalt-capture

> Camera, screen, window and microphone capture, and speaker playback.

[← Back to Smalt](../README.md) · [Javadoc](https://javadoc.io/doc/com.github.auties00/smalt-capture)

## At a glance

- `VideoDevices` lists and opens cameras, screens and application windows. Every frame is an 8-bit YUV 4:2:0 `YuvPicture`.
- `AudioDevices` records microphones, plays to speakers, and records what a speaker plays (loopback).
- Adjust 15 camera controls, such as exposure, focus, zoom and white balance, and listen for devices coming and going.
- Runs on Windows 10+, macOS 12+ (audio from 11) and Linux on x86-64, AArch64 or RISC-V 64. No native library is bundled.
- Depends on [smalt-core](../core/README.md#at-a-glance), which supplies `YuvPicture` and `AudioBuffer`, and [smalt-image](../image/README.md#at-a-glance), which decodes MJPEG cameras.
- The only Smalt module that calls native code: launch with `--enable-native-access=com.github.auties00.smalt.capture` (see [Install](#install)).

## Install

```xml
<dependency>
    <groupId>com.github.auties00</groupId>
    <artifactId>smalt-capture</artifactId>
</dependency>
```

The version comes from the BOM: see [Install](../README.md#install) for the BOM import and Gradle.
On the module path, add `requires com.github.auties00.smalt.capture;` to your `module-info.java`.

Launch with `--enable-native-access=com.github.auties00.smalt.capture` on the module path, or `--enable-native-access=ALL-UNNAMED` on the class path.
The module reaches the OS through Java's Foreign Function & Memory API (`java.lang.foreign`).

- Without the flag, the JVM prints a warning once and capture still works.
- With `--illegal-native-access=deny`, Windows reports no devices; device calls throw `MediaException.Io` on macOS and `IllegalCallerException` on Linux.

### Before you run

| OS | What you need |
|---|---|
| Windows | N editions: the Media Feature Pack, or there are no cameras |
| macOS | Camera and Screen Recording permission for the app that launches Java |
| macOS loopback | macOS 14.2+ and the System Audio Recording permission |
| Linux | The client libraries below; a missing one turns off only its feature |

<details>
<summary><b>Linux libraries</b></summary>

| Library | Needed for |
|---|---|
| `libX11.so.6` | X11 screens and windows |
| `libXext.so.6` | Faster X11 screen reads (MIT-SHM) |
| `libXrandr.so.2` | X11 monitors |
| `libXcomposite.so.1` | Windows covered by other windows |
| `libXfixes.so.3` | The pointer in X11 captures |
| `libpipewire-0.3.so.0` | Wayland screens |
| `libasound.so.2` | ALSA audio devices |
| `libpulse.so.0`, `libpulse-simple.so.0` | PulseAudio or PipeWire devices, and loopback |

</details>

## Examples

### List cameras, screens and microphones

Each device has an `id()` to open it later and a `name()` to show.

```java
for (var camera : VideoDevices.cameras()) {
    System.out.println("camera: " + camera.name());
}
for (var screen : VideoDevices.screens()) {
    System.out.println("screen: " + screen.name() + " " + screen.width() + "x" + screen.height());
}
for (var microphone : AudioDevices.inputs()) {
    System.out.println("microphone: " + microphone.name());
}
```

### Take a photo with the default camera

`read()` blocks until the next frame and returns null once the device stops.

```java
var camera = VideoDevices.defaultCamera().orElseThrow();
try (var session = VideoDevices.openCapture(camera)) {
    var frame = Objects.requireNonNull(session.read(), "the camera stopped");
    Files.write(Path.of("photo.png"), Images.encode(frame.picture(), ImageFormat.PNG));
}
```

### Take a screenshot

Open the primary screen at 1 frame per second and keep the first frame.

```java
var screen = VideoDevices.primaryScreen().orElseThrow();
try (var session = VideoDevices.openCapture(screen, 1)) {
    var frame = Objects.requireNonNull(session.read(), "the screen share ended");
    Files.write(Path.of("screen.png"), Images.encode(frame.picture(), ImageFormat.PNG));
}
```

### Capture part of a window

`ScreenCaptureOptions` picks the frame rate, a region in window pixels, and whether to draw the pointer.

```java
var window = VideoDevices.windows().stream().findFirst().orElseThrow(); // front to back
var options = ScreenCaptureOptions.builder()
        .frameRate(30)
        .region(0, 0, Math.max(2, window.width() / 2), window.height())
        .cursor(false)
        .build();
try (var session = VideoDevices.openCapture(window, options)) {
    for (int i = 0; i < 90; i++) { // 3 seconds
        var frame = session.read();
        if (frame == null) break; // the window closed
        if (i % 30 != 0) continue;
        Files.write(Path.of("window-" + i + ".png"), Images.encode(frame.picture(), ImageFormat.PNG));
    }
}
```

### Set a camera mode and white balance

Pick one of the camera's own modes: opening a mode it does not list throws `MediaException.Unsupported`.

```java
var camera = VideoDevices.defaultCamera().orElseThrow();
var mode = camera.modes().stream()
        .filter(m -> m.height() == 720)
        .findFirst()
        .orElseThrow();
try (var session = VideoDevices.openCapture(camera, mode)) {
    var controls = session.controls();
    controls.capability(CameraControl.WHITE_BALANCE) // macOS: automatic or locked only, no range
            .flatMap(CameraControls.Capability::range)
            .filter(range -> range.accepts(5000))
            .ifPresent(range -> controls.set(CameraControl.WHITE_BALANCE, 5000)); // kelvin
}
```

### Record the microphone to WAV

Capture sessions hand out core `AudioBuffer`s, so core's `AudioWriter` can store them directly.

```java
var microphone = AudioDevices.defaultInput().orElseThrow();
try (var session = AudioDevices.openCapture(microphone);
     var output = MediaSink.of(Path.of("memo.wav"));
     var writer = AudioWriter.open(output, OutputContainer.WAV,
             AudioWriterOptions.of(session.sampleRate(), session.layout()))) {
    long limit = session.sampleRate() * 5L; // 5 seconds
    long frames = 0;
    for (var chunk = session.read(); chunk != null && frames < limit; chunk = session.read()) {
        writer.write(chunk);
        frames += chunk.frames();
    }
    writer.finish();
}
```

### Record what the speakers play

A loopback session captures the mix of every app on an output; its reads wait while nothing plays.

```java
var speakers = AudioDevices.defaultOutput().orElseThrow();
try (var loopback = AudioDevices.openLoopback(speakers);
     var output = MediaSink.of(Path.of("system.wav"));
     var writer = AudioWriter.open(output, OutputContainer.WAV,
             AudioWriterOptions.of(loopback.sampleRate(), loopback.layout()))) {
    long limit = loopback.sampleRate() * 5L; // 5 seconds of sound
    long frames = 0;
    for (var chunk = loopback.read(); chunk != null && frames < limit; chunk = loopback.read()) {
        writer.write(chunk);
        frames += chunk.frames();
    }
    writer.finish();
}
```

### Play a tone to the speakers

`write` takes interleaved samples in [-1, 1] and blocks only while the queue is full; `drain()` waits for playback.

```java
var speaker = AudioDevices.defaultOutput().orElseThrow();
try (var session = AudioDevices.openPlayback(speaker)) {
    int rate = session.sampleRate(), channels = session.channels();
    var samples = new float[rate * channels]; // 1 second
    for (int i = 0; i < rate; i++) {
        var value = (float) (0.2 * StrictMath.sin(2 * StrictMath.PI * 440 * i / rate));
        Arrays.fill(samples, i * channels, (i + 1) * channels, value);
    }
    session.write(samples, 0, rate);
    session.drain();
}
```

## Formats

Video sessions always deliver 8-bit YUV 4:2:0 pictures with even sizes; audio sessions read and write float or 16-bit PCM.

| Device | Capture | Playback | Notes |
|---|:-:|:-:|---|
| Camera | ✅ | ➖ | Size and frame rate from a `CaptureMode`; 15 controls |
| Screen | ✅ | ➖ | Integer or fractional rate, region, pointer on or off |
| Application window | ✅ | ➖ | Region; keeps capturing when moved; X11 only on Linux |
| Microphone | ✅ | ➖ | 1000-768000 Hz, 1-32 channels; converted in shared mode |
| Speaker | ➖ | ✅ | Blocking writes; `drain()` waits for playback |
| Speaker loopback | ✅ | ➖ | Mix of every app; shared mode only |

✅ supported · ❌ not supported · ➖ not applicable

Shared mode mixes with other apps; exclusive mode takes the device, at one of its `formats()` only.

<details>
<summary><b>Video sessions in detail</b></summary>

- **Frames:** `read()` returns a `Frame(picture, timestampMicros)`, or null once the session ends.
- **Colors:** cameras that send YUV keep their reported matrix and range; RGB sources become BT.709 limited range.
- **Camera modes:** `camera.modes()` lists width, height and a `Rational` frame rate, largest first; the list may be empty.
- **Screen rate:** `openCapture(screen, fps)` or `ScreenCaptureOptions`; the default is 30 fps, the cap 1000 fps.
- **Repeats:** when nothing changes on screen, the last picture comes again with a new timestamp.
- **Region:** `ScreenCaptureOptions.region(x, y, width, height)`, in screen or window pixels, cropped to even sizes.
- **Window resizes:** the size is fixed at open; later sizes are rescaled to it.
- **Slow readers:** a reader that falls behind gets the newest frame and skips older ones.
- **Exact conversion:** RGB sources convert to 4:2:0 bit for bit as core's `ImageOps.toYuv` does.

</details>

<details>
<summary><b>Camera controls</b></summary>

`session.controls()` reports each control's range, step or discrete values, default, and whether it has an automatic mode.

| Control | Unit |
|---|---|
| `BRIGHTNESS`, `CONTRAST`, `HUE`, `SATURATION`, `SHARPNESS`, `GAMMA`, `GAIN`, `BACKLIGHT_COMPENSATION`, `FOCUS`, `ZOOM` | Device units |
| `WHITE_BALANCE` | Kelvin |
| `POWER_LINE_FREQUENCY` | 0, 50 or 60 Hz |
| `EXPOSURE` | Microseconds |
| `PAN`, `TILT` | Arc seconds |

- `set(control, value)` sets a manual value; `setAutomatic(control, true)` hands it back to the camera.
- Which controls exist depends on the OS and the camera: see [Known limits](#known-limits).

</details>

<details>
<summary><b>Audio sessions in detail</b></summary>

| Preset | Rate and channels | Period | Device latency | Queue |
|---|---|---|---|---|
| `AudioStreamOptions.defaults()` | The device's | 10 ms | 40 ms | 200 ms |
| `AudioStreamOptions.forRealtime()` | The device's | 10 ms | 20 ms | 60 ms |
| `AudioStreamOptions.forVoice()` | 16 kHz mono | 10 ms | 20 ms | 60 ms |

- **Device formats:** `device.formats()` lists rate, channels and encoding (`PCM_S16`, `PCM_S24`, `PCM_S32`, `FLOAT32`), the current one first.
- **Reads:** `read()` returns one period as a planar float `AudioBuffer`; `read(float[]...)` and `read(short[]...)` fill interleaved arrays.
- **Writes:** `write(AudioBuffer)`, `write(float[]...)` or `write(short[]...)`; a write blocks only while the queue is full.
- **Builder:** set `sampleRate`, `channels`, `period`, `latency`, `bufferDuration` and `shareMode`; durations go from 1 ms to 10 s.
- **Timing:** `timestampMicros()` and `delayMicros()` on capture, `delayMicros()` on playback, for echo cancellation.
- **Loss:** a full capture queue drops the oldest audio (`overruns()`); an empty playback queue plays silence (`underruns()`).
- **Unplug:** the next read, write or drain throws `MediaException.Io` once; then reads report the end and writes discard audio.

</details>

## Known limits

- **Other systems:** every list is empty and every `openCapture` throws `MediaException.Unsupported`.
- **Pixel format:** you cannot choose the format a camera sends; it is always converted to 4:2:0.
- **Frame lifetime:** a frame's planes are reused, so encode or process it before the next `read()` or `close()`.
- **Threads:** read a session from one thread; `close()` from any thread unblocks a pending `read()`.
- **End of stream:** `read()` returns null when the session closes, the device stops, the window closes or a Wayland share is revoked.
- **Region:** must overlap the source, else `IllegalArgumentException`; an area under 2x2 throws `Unsupported`.
- **Listing is slow:** `cameras()` asks every camera for its modes; `defaultCamera()` and `camera(id)` ask only one.
- **Controls persist:** a control change applies to the device, so it can outlive the session and affect other apps.
- **Loopback:** shared mode only, on every OS.
- **Silent loopback:** reads wait while the output plays nothing; macOS and Linux may deliver silence while the device stays running.

<details>
<summary><b>Windows (OS) limits</b></summary>

- N editions without the Media Feature Pack have no cameras; opening one throws `Unsupported`.
- Camera exposure takes only power-of-two fractions of a second (1/2 s, 1/4 s, ...), listed in microseconds.
- A user can forbid exclusive mode per audio device in the sound settings.
- Window lists skip untitled, cloaked, tool and owned windows.
- Window capture with the pointer off falls back to `PrintWindow` before Windows 10 2004.
- The first capture call keeps COM's multithreaded apartment and its libraries loaded until the process exits.

</details>

<details>
<summary><b>macOS limits</b></summary>

- Camera controls: focus, exposure and white balance only as automatic or locked; zoom where the camera offers it.
- No power-line frequency or picture controls (brightness, contrast and the like).
- Before 12.3 there is no window capture (empty list) and screen snapshots never show the pointer.
- A refused System Audio Recording permission makes loopback throw `Unsupported`.
- Loopback open and reads wait until the user answers the permission prompt; only the first output stream is tapped.
- Exclusive mode (hog mode) uses the device's channel count; input and output of one device share a rate.
- A shared capture stops delivering while its device runs at another rate, and resumes when the rate returns.

</details>

<details>
<summary><b>Linux limits</b></summary>

- Wayland screens: the portal picks the monitor and may ask the user; declining throws `MediaException.Io`.
- Wayland screens need `libpipewire-0.3.so.0`; producers that offer only tiled DMA-BUF fail.
- Wayland: native Wayland windows are not listed; `windows()` needs an X11 display (XWayland windows work).
- X11 without the Composite extension: covered parts of a window hold whatever the server returns.
- The pointer is missing when the Wayland portal offers neither an embedded nor a metadata cursor.
- V4L2: Bayer formats and compressed formats other than MJPEG and JPEG are not supported.
- Exclusive audio works on ALSA devices only, not on `pulse:` devices.
- Loopback needs a PulseAudio or PipeWire server; bare ALSA has none.

</details>

## Devices and events

| Call | Returns |
|---|---|
| `VideoDevices.cameras()`, `defaultCamera()`, `camera(id)` | Cameras, default first |
| `VideoDevices.screens()`, `primaryScreen()`, `screen(id)` | Screens, primary first |
| `VideoDevices.windows()`, `window(id)` | Windows, front to back where the OS knows the order |
| `AudioDevices.inputs()`, `defaultInput()`, `input(id)` | Microphones and other inputs, default first |
| `AudioDevices.outputs()`, `defaultOutput()`, `output(id)` | Speakers and other outputs, default first |

| Listener | Events |
|---|---|
| `VideoDevices.addListener` | `CameraAdded`, `CameraRemoved`, `DefaultCameraChanged`, `ScreenAdded`, `ScreenRemoved`, `ScreenChanged` |
| `AudioDevices.addListener` | `DefaultChanged`, `DeviceAdded`, `DeviceRemoved` |

Listeners run on a dedicated dispatch thread, one event at a time, so a slow listener delays the next events.

### React when a camera is plugged in

```java
VideoDevices.addListener(event -> System.out.println(switch (event) {
    case VideoDeviceEvent.CameraAdded added -> "plugged in: " + added.cameraId();
    case VideoDeviceEvent.CameraRemoved removed -> "unplugged: " + removed.cameraId();
    default -> "other change: " + event;
}));
```

## Backends

Which OS API serves each device:

| Device | Windows | macOS | Linux |
|---|---|---|---|
| Cameras | Media Foundation | AVFoundation | V4L2 |
| Screens | DXGI duplication, GDI fallback | ScreenCaptureKit (12.3+), else CoreGraphics snapshots | Wayland portal + PipeWire, or X11 |
| App windows | Windows Graphics Capture, `PrintWindow` fallback | ScreenCaptureKit (12.3+) | X11 (Composite when present) |
| Camera controls | `IAMCameraControl`, `IAMVideoProcAmp` | AVFoundation | V4L2 controls |
| Audio | WASAPI | CoreAudio | ALSA + PulseAudio protocol |
| Loopback | WASAPI loopback | CoreAudio process tap (14.2+) | Pulse or PipeWire monitor source |
| Video device events | OS notifications + polling | Polling | `/dev` watch + polling |
| Video polling | Screens 1 s, cameras 5 s | Screens 1 s, cameras 5 s | Screens 1 s, cameras 10 s |
| Audio device events | `IMMNotificationClient` | CoreAudio listeners + 5 s polling | Sound-server events + `/dev/snd` watch |

### Camera pixel formats

| OS | Formats | Preference |
|---|---|---|
| Windows | NV12, I420, IYUV, YV12, YUY2, YUYV, UYVY, MJPG, RGB32, ARGB32, RGB24 | 4:2:0, then 4:2:2, MJPG, RGB |
| macOS | NV12, BGRA | NV12 |
| Linux | See the V4L2 table below | ➖ |

<details>
<summary><b>V4L2 pixel formats</b></summary>

| Family | Formats |
|---|---|
| 4:2:0 planar and semi-planar | YU12, YV12, NV12, NV21 and their M variants |
| 4:2:2 packed | YUYV, YVYU, UYVY, VYUY |
| 4:2:2 semi-planar and planar | NV16, NV61 (and M), 422P, YM16, YM61 |
| 4:4:4 semi-planar | NV24, NV42 |
| RGB | 32-bit in 8 orders, RGB24, BGR24, RGB565 |
| Compressed | MJPG, JPEG |
| Grayscale | GREY, Y10, Y12, Y16, Y16 big-endian |

</details>

<details>
<summary><b>Windows (OS) backends</b></summary>

- **App windows:** Windows Graphics Capture from Windows 10 1903, with the yellow border off where allowed; `PrintWindow` otherwise.
- **Audio:** shared mode converts rate and channels in the audio engine; exclusive mode tries float, 32-, 24- and 16-bit.
- **Audio thread:** each session's device thread joins the MMCSS "Pro Audio" class.

</details>

<details>
<summary><b>macOS backends</b></summary>

- **Cameras:** one mode per format and frame-rate range, at its fastest rate.
- **Screens:** sizes are in pixels and positions in points.
- **Audio:** HAL output units; callbacks run on CoreAudio's I/O thread, not a session thread.
- **Exclusive audio:** hog mode, at the stream's rate.
- **Loopback:** a process tap of the device's first output stream.

</details>

<details>
<summary><b>Linux backends</b></summary>

- **Cameras:** `/dev/videoN`, single- and multi-planar, memory-mapped streaming.
- **Interlaced cameras:** sequential and alternate fields are woven; single-field streams are line-doubled.
- **Wayland screens:** chosen when `WAYLAND_DISPLAY` is set; shared memory preferred, linear DMA-BUF accepted.
- **X11 screens:** RandR monitors of `DISPLAY`, read with MIT-SHM or `XGetImage`; pointer drawn through XFixes.
- **Audio:** ALSA devices plus sound-server devices prefixed `pulse:`; the server's defaults become the defaults.
- **Exclusive audio:** an ALSA device with no software resampling.

</details>

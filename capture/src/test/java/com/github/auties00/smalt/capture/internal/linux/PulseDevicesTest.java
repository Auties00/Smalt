package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceFormat;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maps a synthetic sound-server snapshot (two ALSA cards, a Bluetooth sink and a null sink) to audio
 * devices, defaults and loopback sources.
 */
@DisplayName("sound-server devices")
class PulseDevicesTest {
    private static final PulseServer.Device ANALOG = new PulseServer.Device("alsa_output.pci-0000_00_1f.3.analog-stereo",
            "Built-in Audio Analog Stereo", 48_000, 2, "alsa_output.pci-0000_00_1f.3.analog-stereo.monitor", "0", "0", false);
    private static final PulseServer.Device HDMI = new PulseServer.Device("alsa_output.pci-0000_01_00.1.hdmi-stereo",
            "HDMI Audio", 48_000, 2, "alsa_output.pci-0000_01_00.1.hdmi-stereo.monitor", "1", "3", false);
    private static final PulseServer.Device BLUETOOTH = new PulseServer.Device("bluez_output.00_11_22_33_44_55.1",
            "Headphones", 44_100, 2, null, null, null, false);
    private static final PulseServer.Device NULL_SINK = new PulseServer.Device("smalt", "Smalt", 44_100, 2, "smalt.monitor",
            null, null, false);
    private static final PulseServer.Device MIC = new PulseServer.Device("alsa_input.pci-0000_00_1f.3.analog-stereo",
            "Built-in Audio Analog Stereo", 48_000, 2, null, "0", "0", true);
    private static final PulseServer.Device MONITOR = new PulseServer.Device("smalt.monitor", "Monitor of Smalt", 44_100, 2,
            "smalt", null, null, true);
    private static final PulseServer.Device BROKEN = new PulseServer.Device("odd", "Odd", 0, 0, null, null, null, true);

    private static final PulseServer.Snapshot SNAPSHOT = new PulseServer.Snapshot("pulseaudio",
            List.of(ANALOG, HDMI, BLUETOOTH, NULL_SINK), List.of(MIC, MONITOR, BROKEN), "smalt", "smalt.monitor");

    private static final Map<String, Integer> CARDS = Map.of("PCH", 0, "NVidia", 1);

    private static Optional<String> monitor(String id) {
        return PulseDevices.monitorOf(id, SNAPSHOT, card -> Optional.ofNullable(CARDS.get(card)));
    }

    @Test
    @DisplayName("sinks and sources become prefixed devices with the server's defaults and native formats")
    void devices() {
        var outputs = PulseDevices.outputs(SNAPSHOT);
        assertEquals(List.of("pulse:alsa_output.pci-0000_00_1f.3.analog-stereo", "pulse:alsa_output.pci-0000_01_00.1.hdmi-stereo",
                "pulse:bluez_output.00_11_22_33_44_55.1", "pulse:smalt"), outputs.stream().map(AudioDevice::id).toList());
        assertEquals(List.of(false, false, false, true), outputs.stream().map(AudioDevice::defaultDevice).toList());
        assertEquals(List.of(new AudioDeviceFormat(44_100, 2, AudioSampleFormat.FLOAT32)), outputs.get(2).formats());
        assertEquals("Headphones", outputs.get(2).name());
        var inputs = PulseDevices.inputs(SNAPSHOT);
        assertEquals(List.of("pulse:alsa_input.pci-0000_00_1f.3.analog-stereo", "pulse:smalt.monitor", "pulse:odd"),
                inputs.stream().map(AudioDevice::id).toList());
        assertTrue(inputs.get(1).defaultDevice());
        assertEquals(List.of(), inputs.get(2).formats(), "a device without a rate has no format");
        assertTrue(PulseDevices.owns("pulse:smalt"));
        assertFalse(PulseDevices.owns("pulse"));
        assertEquals("smalt.monitor", PulseDevices.serverName("pulse:smalt.monitor"));
    }

    @Test
    @DisplayName("the ALSA default loses its flag only when the server has a default")
    void merge() {
        var alsa = List.of(new AudioDevice.Output("default", "Default", true, List.of()),
                new AudioDevice.Output("hw:CARD=PCH,DEV=0", "PCH", false, List.of()));
        var unflag = (java.util.function.Function<AudioDevice.Output, AudioDevice.Output>)
                device -> new AudioDevice.Output(device.id(), device.name(), false, device.formats());
        var merged = PulseDevices.merge(alsa, PulseDevices.outputs(SNAPSHOT), unflag);
        assertEquals(6, merged.size());
        assertEquals(List.of("pulse:smalt"), merged.stream().filter(AudioDevice::defaultDevice).map(AudioDevice::id).toList());
        var noDefault = new PulseServer.Snapshot("pulseaudio", List.of(ANALOG), List.of(), null, null);
        var kept = PulseDevices.merge(alsa, PulseDevices.outputs(noDefault), unflag);
        assertEquals(List.of("default"), kept.stream().filter(AudioDevice::defaultDevice).map(AudioDevice::id).toList());
    }

    @Test
    @DisplayName("the loopback of an output is the monitor of the sink it plays to")
    void loopback() {
        assertEquals(Optional.of("smalt.monitor"), monitor("pulse:smalt"));
        assertEquals(Optional.of("bluez_output.00_11_22_33_44_55.1.monitor"), monitor("pulse:bluez_output.00_11_22_33_44_55.1"),
                "a sink without a reported monitor uses the server's naming");
        assertEquals(Optional.empty(), monitor("pulse:gone"));
        for (var alias : List.of("default", "pulse", "pipewire")) {
            assertEquals(Optional.of(Pulse.DEFAULT_MONITOR), monitor(alias), alias);
        }
        assertEquals(Optional.of(ANALOG.monitor()), monitor("hw:CARD=PCH,DEV=0"));
        assertEquals(Optional.of(ANALOG.monitor()), monitor("plughw:CARD=PCH,DEV=0"));
        assertEquals(Optional.of(ANALOG.monitor()), monitor("sysdefault:CARD=PCH"));
        assertEquals(Optional.of(ANALOG.monitor()), monitor("hw:CARD=0,DEV=0"), "a numeric card");
        assertEquals(Optional.empty(), monitor("hw:CARD=PCH,DEV=1"), "no sink drives device 1");
        assertEquals(Optional.of(HDMI.monitor()), monitor("hdmi:CARD=NVidia,DEV=0"), "an HDMI index is not a PCM number");
        assertEquals(Optional.empty(), monitor("hw:CARD=USB,DEV=0"), "an unknown card");
        assertEquals(Optional.empty(), monitor("null"));
        assertEquals(Optional.empty(), monitor("hw:CARD=99999999999999,DEV=0"), "a card number beyond int");
    }
}

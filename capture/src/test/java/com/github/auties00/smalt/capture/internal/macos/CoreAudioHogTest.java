package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the hog mode bookkeeping against a simulated device that has the property's documented toggle
 * semantics, on every system.
 */
@DisplayName("CoreAudio hog mode bookkeeping")
class CoreAudioHogTest {
    private static final int DEVICE = 77;

    private static final long PID = 1000;

    private static final int FOREIGN = 2000;

    private static class FakeDevice implements CoreAudioHog.Hardware {
        private int owner = CoreAudioHog.NO_OWNER;

        private int rate = 48_000;

        private int toggles;

        private int toggleStatus;

        private boolean toggleIgnored;

        private boolean rateRefused;

        private final List<Integer> rates = new ArrayList<>();

        @Override
        public int owner(int object) {
            assertEquals(DEVICE, object);
            return owner;
        }

        @Override
        public int toggle(int object) {
            toggles++;
            if (toggleStatus != 0) {
                return toggleStatus;
            }
            if (toggleIgnored) {
                return 0;
            }
            if (owner == PID) {
                owner = CoreAudioHog.NO_OWNER;
            } else if (owner == CoreAudioHog.NO_OWNER) {
                owner = (int) PID;
            }
            return 0;
        }

        @Override
        public int nominalRate(int object) {
            return rate;
        }

        @Override
        public boolean setNominalRate(int object, int rate) {
            if (rateRefused) {
                return false;
            }
            rates.add(rate);
            this.rate = rate;
            return true;
        }
    }

    private final FakeDevice device = new FakeDevice();

    private final CoreAudioHog hog = new CoreAudioHog(device, PID);

    @Test
    @DisplayName("takes a free device and frees it on release")
    void takeAndRelease() {
        var hold = hog.acquire(DEVICE, "d", false, 48_000);
        assertEquals(PID, device.owner);
        hog.release(hold);
        assertEquals(CoreAudioHog.NO_OWNER, device.owner);
        assertEquals(2, device.toggles);
    }

    @Test
    @DisplayName("refuses a device another process owns without writing the property")
    void foreignOwner() {
        device.owner = FOREIGN;
        var failure = assertThrows(MediaException.Io.class, () -> hog.acquire(DEVICE, "d", false, 48_000));
        assertTrue(failure.getMessage().contains("another application (process 2000)"), failure.getMessage());
        assertEquals(0, device.toggles);
        assertEquals(FOREIGN, device.owner);
    }

    @Test
    @DisplayName("never toggles a device this process owns outside the bookkeeping, which would free it")
    void ownedElsewhereInProcess() {
        device.owner = (int) PID;
        assertThrows(MediaException.Io.class, () -> hog.acquire(DEVICE, "d", true, 48_000));
        assertEquals(0, device.toggles);
        assertEquals(PID, device.owner);
    }

    @Test
    @DisplayName("a second stream of one direction is refused and leaves the first holding the device")
    void sameDirection() {
        var hold = hog.acquire(DEVICE, "d", true, 48_000);
        assertThrows(MediaException.Io.class, () -> hog.acquire(DEVICE, "d", true, 48_000));
        assertEquals(1, device.toggles);
        assertEquals(PID, device.owner);
        hog.release(hold);
        assertEquals(CoreAudioHog.NO_OWNER, device.owner);
    }

    @Test
    @DisplayName("an input and an output stream share a device until the last one leaves")
    void bothDirections() {
        var input = hog.acquire(DEVICE, "d", false, 48_000);
        var output = hog.acquire(DEVICE, "d", true, 48_000);
        assertEquals(1, device.toggles);
        hog.release(input);
        assertEquals(PID, device.owner);
        hog.release(output);
        assertEquals(CoreAudioHog.NO_OWNER, device.owner);
        assertEquals(2, device.toggles);
    }

    @Test
    @DisplayName("the second direction must run at the first one's rate")
    void secondDirectionRate() {
        var input = hog.acquire(DEVICE, "d", false, 96_000);
        assertThrows(MediaException.Unsupported.class, () -> hog.acquire(DEVICE, "d", true, 44_100));
        assertEquals(96_000, device.rate);
        var output = hog.acquire(DEVICE, "d", true, 96_000);
        hog.release(input);
        hog.release(output);
    }

    @Test
    @DisplayName("restores the nominal rate before freeing the device")
    void restoresRate() {
        var hold = hog.acquire(DEVICE, "d", false, 96_000);
        assertEquals(96_000, device.rate);
        hog.release(hold);
        assertEquals(48_000, device.rate);
        assertEquals(List.of(96_000, 48_000), device.rates);
        assertEquals(CoreAudioHog.NO_OWNER, device.owner);
    }

    @Test
    @DisplayName("leaves the rate alone when the stream runs at the device's own")
    void keepsRate() {
        hog.release(hog.acquire(DEVICE, "d", false, 48_000));
        assertTrue(device.rates.isEmpty());
    }

    @Test
    @DisplayName("frees the device again when it refuses the rate")
    void refusedRate() {
        device.rateRefused = true;
        assertThrows(MediaException.Unsupported.class, () -> hog.acquire(DEVICE, "d", false, 96_000));
        assertEquals(CoreAudioHog.NO_OWNER, device.owner);
        device.rateRefused = false;
        hog.release(hog.acquire(DEVICE, "d", false, 96_000));
        assertEquals(CoreAudioHog.NO_OWNER, device.owner);
    }

    @Test
    @DisplayName("takes back a rate the device applies too late to be confirmed")
    void lateRate() {
        var late = new FakeDevice() {
            @Override
            public boolean setNominalRate(int object, int rate) {
                super.setNominalRate(object, rate);
                return rate != 96_000;
            }
        };
        var lateHog = new CoreAudioHog(late, PID);
        assertThrows(MediaException.Unsupported.class, () -> lateHog.acquire(DEVICE, "d", false, 96_000));
        assertEquals(48_000, late.nominalRate(DEVICE));
        assertEquals(CoreAudioHog.NO_OWNER, late.owner(DEVICE));
    }

    @Test
    @DisplayName("never writes back a nominal rate it could not read")
    void unreadableRate() {
        device.rate = 0;
        hog.release(hog.acquire(DEVICE, "d", false, 48_000));
        assertEquals(List.of(48_000), device.rates);
        assertEquals(CoreAudioHog.NO_OWNER, device.owner);
    }

    @Test
    @DisplayName("reports a device that ignores the write as not allowing the exclusive mode")
    void ignoredToggle() {
        device.toggleIgnored = true;
        var failure = assertThrows(MediaException.Unsupported.class, () -> hog.acquire(DEVICE, "d", false, 48_000));
        assertTrue(failure.getMessage().contains("does not allow exclusive mode"), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, -50, 1852797029})
    @DisplayName("reports a refused write as not allowing the exclusive mode")
    void refusedToggle(int status) {
        device.toggleStatus = status;
        assertThrows(MediaException.Unsupported.class, () -> hog.acquire(DEVICE, "d", false, 48_000));
        assertEquals(CoreAudioHog.NO_OWNER, device.owner);
    }

    @Test
    @DisplayName("reports the permissions error as a device in use")
    void permissionsError() {
        device.toggleStatus = FourCc.of("!hog");
        var failure = assertThrows(MediaException.Io.class, () -> hog.acquire(DEVICE, "d", false, 48_000));
        assertTrue(failure.getMessage().contains("uses the device exclusively"), failure.getMessage());
    }

    @Test
    @DisplayName("does not toggle on release when another process owns the device by then")
    void releaseAfterLoss() {
        var hold = hog.acquire(DEVICE, "d", false, 48_000);
        device.owner = FOREIGN;
        var toggles = device.toggles;
        hog.release(hold);
        assertEquals(toggles, device.toggles);
        assertEquals(FOREIGN, device.owner);
    }

    @Test
    @DisplayName("a device can be taken again after a release")
    void reacquire() {
        for (var i = 0; i < 3; i++) {
            var hold = hog.acquire(DEVICE, "d", false, 48_000);
            assertEquals(PID, device.owner);
            hog.release(hold);
            assertEquals(CoreAudioHog.NO_OWNER, device.owner);
        }
    }

    @Test
    @DisplayName("a system without process taps is reported as unsupported, naming the version")
    void belowProcessTaps() {
        var failure = assertThrows(MediaException.Unsupported.class, () -> CoreAudioBackend.requireProcessTaps(false));
        assertTrue(failure.getMessage().contains("14.2"), failure.getMessage());
        CoreAudioBackend.requireProcessTaps(true);
    }

    @Test
    @DisplayName("a refused permission is reported as unsupported; a granted or unasked one opens")
    void refusedPermission() {
        var failure = assertThrows(MediaException.Unsupported.class, () -> CoreAudioBackend.requireTapPermission(1));
        assertTrue(failure.getMessage().contains("System Audio Recording"), failure.getMessage());
        CoreAudioBackend.requireTapPermission(0);
        CoreAudioBackend.requireTapPermission(2);
        CoreAudioBackend.requireTapPermission(-1);
    }
}

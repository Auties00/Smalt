package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.testkit.ValueContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the value semantics of {@link EncoderLayout}: its arrays are compared by content, and every component,
 * the program config element included, takes part in {@code equals}.
 */
@DisplayName("AAC encoder layout values")
class EncoderLayoutValueTest {
    @Test
    @DisplayName("compare every component, the arrays by content")
    void contract() {
        var pce = EncoderLayout.of(new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
                Speaker.BACK_CENTER)), 48000).orElseThrow().programConfig();
        assertNotNull(pce, "a layout no channel configuration expresses is coded with a program config element");
        ValueContract.check(() -> new EncoderLayout(2, null, new int[]{1}, new int[]{0}, new int[]{0, 1},
                new boolean[]{false, false}), List.of(
                new EncoderLayout(1, null, new int[]{1}, new int[]{0}, new int[]{0, 1}, new boolean[]{false, false}),
                new EncoderLayout(2, pce, new int[]{1}, new int[]{0}, new int[]{0, 1}, new boolean[]{false, false}),
                new EncoderLayout(2, null, new int[]{0}, new int[]{0}, new int[]{0, 1}, new boolean[]{false, false}),
                new EncoderLayout(2, null, new int[]{1}, new int[]{1}, new int[]{0, 1}, new boolean[]{false, false}),
                new EncoderLayout(2, null, new int[]{1}, new int[]{0}, new int[]{1, 0}, new boolean[]{false, false}),
                new EncoderLayout(2, null, new int[]{1}, new int[]{0}, new int[]{0, 1}, new boolean[]{false, true})));
        assertTrue(new EncoderLayout(2, null, new int[]{1}, new int[]{0}, new int[]{0, 1}, new boolean[]{false, false})
                .toString().contains("channelConfiguration=2"));
    }
}

package com.github.auties00.smalt.image.psd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Tells whether the layer effects blocks of a Photoshop layer record draw anything.
 */
final class PsdEffects {
    private PsdEffects() {
        throw new AssertionError();
    }

    /**
     * Returns whether an object-based effects block ({@code lfx2} or {@code lmfx}) has an enabled effect under an
     * enabled master switch.
     *
     * @param data the document
     * @param position the offset of the block body
     * @param end the end of the block body
     * @return whether an effect is drawn
     */
    static boolean enabled(byte[] data, int position, int end) {
        if (position + 8 > end) {
            throw new MediaException.Malformed("a Photoshop effects block is truncated");
        }
        var items = PsdDescriptor.versioned(data, position + 4, end);
        if (!PsdDescriptor.bool(items, "masterFXSwitch", true)) {
            return false;
        }
        for (var entry : items.entrySet()) {
            if (drawn(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean drawn(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            var effect = (Map<String, Object>) map;
            return effect.containsKey("enab") && PsdDescriptor.bool(effect, "enab", false)
                    && PsdDescriptor.bool(effect, "present", true);
        }
        if (value instanceof List<?> list) {
            for (var item : list) {
                if (drawn(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns whether a legacy effects block ({@code lrFX}) has an enabled effect under a visible common state.
     *
     * @param data the document
     * @param position the offset of the block body
     * @param end the end of the block body
     * @return whether an effect is drawn
     */
    static boolean legacyEnabled(byte[] data, int position, int end) {
        if (position + 4 > end) {
            throw new MediaException.Malformed("a Photoshop effects block is truncated");
        }
        var count = ByteArrays.u16be(data, position + 2);
        var at = position + 4;
        var visible = true;
        var any = false;
        for (var index = 0; index < count; index++) {
            if (at + 12 > end) {
                throw new MediaException.Malformed("a Photoshop effects block is truncated");
            }
            var key = new String(data, at + 4, 4, StandardCharsets.ISO_8859_1);
            var size = ByteArrays.u32be(data, at + 8);
            var body = at + 12;
            if (body + size > end) {
                throw new MediaException.Malformed("a Photoshop effect runs past its block");
            }
            // The offsets of the enabled byte in each effect's fixed layout.
            var enabledAt = switch (key) {
                case "cmnS" -> 4;
                case "dsdw", "isdw" -> 38;
                case "oglw", "iglw" -> 30;
                case "bevl" -> 55;
                case "sofi" -> 23;
                default -> -1;
            };
            if (enabledAt >= 0 && enabledAt < size) {
                var on = data[body + enabledAt] != 0;
                if (key.equals("cmnS")) {
                    visible = on;
                } else {
                    any |= on;
                }
            }
            at = (int) (body + size);
        }
        return visible && any;
    }
}

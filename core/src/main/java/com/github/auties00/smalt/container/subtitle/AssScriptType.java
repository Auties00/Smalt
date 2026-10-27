package com.github.auties00.smalt.container.subtitle;

/**
 * A version of the SubStation Alpha script format, the {@code ScriptType} of a script's
 * {@code [Script Info]} section.
 */
public enum AssScriptType {
    /**
     * SubStation Alpha, {@code ScriptType: v4.00}: a {@code [V4 Styles]} section and events with a
     * {@code Marked} field.
     */
    V4,

    /**
     * Advanced SubStation Alpha, {@code ScriptType: v4.00+}: a {@code [V4+ Styles]} section with underline,
     * strikeout, scaling, spacing and angle, and events with a {@code Layer} field.
     */
    V4_PLUS
}

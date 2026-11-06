package com.github.auties00.smalt.lint;

/**
 * A rule violation at a place in a class file.
 *
 * @param rule the rule
 * @param owner the binary name of the class
 * @param method the method name, or an empty string for a finding about the class
 * @param file the source file name, or {@code null} if the class file does not say
 * @param line the source line, or {@code -1} if unknown
 * @param detail what the finding is about, for example the call
 */
public record Finding(Rule rule, String owner, String method, String file, int line, String detail) {
    /** {@return the key of the method in suppressions and the baseline, {@code binary.Name#method}} */
    public String methodKey() {
        return owner + "#" + method;
    }

    /** {@return the finding as {@code rule Owner.method(File.java:line): detail problem; fix}} */
    public String format() {
        var simple = owner.substring(owner.lastIndexOf('.') + 1);
        var place = file == null ? "Unknown Source" : line < 0 ? file : file + ":" + line;
        return rule.id() + " " + simple + (method.isEmpty() ? "" : "." + method) + "(" + place + "): "
                + (detail.isEmpty() ? "" : detail + " ") + rule.problem() + "; " + rule.fix();
    }
}

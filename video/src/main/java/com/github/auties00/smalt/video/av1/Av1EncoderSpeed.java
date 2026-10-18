package com.github.auties00.smalt.video.av1;

/**
 * The trade between compression and encoding time of an {@link Av1Encoder}.
 *
 * <p>Every speed codes the same stream format; faster speeds search fewer references, modes,
 * partitions and motion vector precisions, so they spend more bits for the same quality. What a
 * speed searches depends on the {@link Av1EncoderUsage}: {@link #level(Av1EncoderUsage)} names the
 * equivalent libaom {@code cpu-used} level of each usage. Real-time coding codes the speeds slower
 * than {@link #BALANCED} as {@link #BALANCED}; good-quality coding codes the speeds faster than
 * {@link #FAST} as {@link #FAST}.
 */
public enum Av1EncoderSpeed {
    /**
     * The slowest good-quality search: every reference pair compound mode, the full transform
     * search when measuring the mode candidates and loop restoration on key frames.
     */
    SLOWEST(7, 2, 2),

    /**
     * A slower good-quality search, with loop restoration on key frames.
     */
    SLOWER(7, 3, 4),

    /**
     * The default good-quality speed.
     */
    SLOW(7, 4, 5),

    /**
     * Searches the three references and every motion vector refinement in real time; the best
     * real-time compression.
     */
    BALANCED(7, 5, 6),

    /**
     * Prunes the golden and altref references and the large-block searches in real time; the
     * fastest good-quality speed.
     */
    FAST(8, 6, 8),

    /**
     * Searches mostly the previous frame and prefers large blocks; the real-time default.
     */
    FASTER(9, 6, 9),

    /**
     * Searches only the previous frame with the most aggressive early terminations.
     */
    FASTEST(10, 6, 10);

    /**
     * The equivalent libaom real-time {@code cpu-used} level.
     */
    private final int level;

    /**
     * The equivalent libaom good-quality {@code cpu-used} level.
     */
    private final int goodLevel;

    /**
     * The equivalent libaom all-intra {@code cpu-used} level.
     */
    private final int intraLevel;

    /**
     * Creates a speed.
     *
     * @param level      the equivalent libaom real-time level
     * @param goodLevel  the equivalent libaom good-quality level
     * @param intraLevel the equivalent libaom all-intra level
     */
    Av1EncoderSpeed(int level, int goodLevel, int intraLevel) {
        this.level = level;
        this.goodLevel = goodLevel;
        this.intraLevel = intraLevel;
    }

    /**
     * Returns the libaom real-time {@code cpu-used} level whose speed features this speed uses.
     *
     * @return the level, 7 to 10
     */
    public int level() {
        return level;
    }

    /**
     * Returns the libaom {@code cpu-used} level this speed corresponds to in a usage.
     *
     * @param usage the coding mode whose level scale applies
     * @return the level: 7 to 10 in real time, 2 to 6 in good quality, 2 to 10 in all intra
     * @throws NullPointerException if {@code usage} is {@code null}
     */
    public int level(Av1EncoderUsage usage) {
        return switch (usage) {
            case REALTIME -> level;
            case GOOD_QUALITY -> goodLevel;
            case ALL_INTRA -> intraLevel;
        };
    }
}

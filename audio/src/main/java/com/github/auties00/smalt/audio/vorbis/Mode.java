package com.github.auties00.smalt.audio.vorbis;

/**
 * Describes one Vorbis mode: the block size of the packets that select it and the mapping they
 * are decoded with (Vorbis I section 4.2.4, "Modes").
 *
 * @param longBlock whether packets of this mode use the long block size
 * @param mapping   the mapping number
 */
record Mode(boolean longBlock, int mapping) {
}

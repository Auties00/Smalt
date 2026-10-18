package com.github.auties00.smalt.audio.vorbis;

/**
 * Describes one Vorbis mapping (type 0): the submaps grouping channels onto floors and residues,
 * and the square polar coupling steps (Vorbis I section 4.2.4, "Mappings").
 *
 * @param submaps       the number of submaps
 * @param mux           the submap of each channel
 * @param submapFloor   the floor number of each submap
 * @param submapResidue the residue number of each submap
 * @param magnitude     the magnitude channel of each coupling step
 * @param angle         the angle channel of each coupling step
 */
record Mapping(int submaps, int[] mux, int[] submapFloor, int[] submapResidue, int[] magnitude, int[] angle) {
}

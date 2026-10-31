/* Ports VVdeC 3.3.0 (source/Lib/CommonLib/AdaptiveLoopFilter.cpp: the fixed filter sets). Copyright (c)
 * 2018-2026, Fraunhofer-Gesellschaft zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD
 * License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

/**
 * Holds the fixed adaptive loop filter sets of ITU-T H.266 7.4.3.18: the 64 fixed luma filters and the filter each
 * of the 25 classes uses in each of the 16 fixed filter sets.
 */
final class VvcAlfTables {
    /**
     * The 12 coefficients of each fixed filter, {@code FIXED_COEFF[filter * 12 + k]}.
     */
    static final int[] FIXED_COEFF = decode(""
            + "000002fd01fc0107ff01ff050000000000ff00010000ff0200000000000000010000000000000000000000000000ff010202f9fd"
            + "00fb0d160cfdfd11ff0006f801fb01170002fb0a0000ffff00ff02010000ff04000003f50100ff230502f709000008f8fef90404"
            + "0201ff19000001ff00fd0103ff01ff03000003fd00fa05ff0201fc15f9010504fd050b0d0cf80b0cfbfd06fefd080e0f02f90b10"
            + "02fffafbfefe140efc00fd190301f8fc00f81605fd02f61d0201f9ff02f517fb0002f61dfafd0809fc0809070efe08090201fcf9"
            + "00f8111601fffc170300fbf900f90f12fb00fb1b020000f901f60d0dfc02f9180303f304fefb091519fefd0cfbfe07fdf9090809"
            + "10fe0f0c00ff00f9fb040b0b08fa0c1503fefdf8fcff100ffefd031a0201fbfcfff81004fe01f9210201fcfe01f611fe0002f521"
            + "01fe07f1f00a0808140b0e0b020203f3f304080c02fd1018010400f9f8fc0909fefe081d010102fcfffa0603fffffd1ef903020a"
            + "fe03070b13f9080a00fefbfdfe04140ffffdff1603fff8fcfffc1608fc02f81c0003f2030001131108fdf9140002fff803fa0515"
            + "0101f70dfcfe0814fe0203051504060102fefdf7fc020e1003fa0818020105f0f902030b0ffd0b16010203f5fefb040809fdfe1a"
            + "00ff0af7fff802030400001d010200fb01f709030001f914fe08fafc03f7f82d0e02f30701ff10edf8fcfd021300041e0101fd00"
            + "02f50ffb0102f7180001fe0001fc04000001fc07000102fb01fa040afe01fc0a0300fdfafefa0e08fffffd1f000100fe01fa0501"
            + "0001fb0d030109edeb0907060d050f15020403f4f301070803000c1a0301f8fe00fa1202fe03f6170101fcff01fb0801ff02fb0a"
            + "0001ff0000fe02000001fe030101fef901f90e120000f915000100fe00f90801fe00fd18000101fe02f60a00fe01f917000202f5"
            + "02fcfd270701f60901000df0fbfaff080600061d010301fafcf90906fdfe03210400efffff051a08fe03f11e0001fe0002f80cfa"
            + "0101fa10000000ff01fc04000000fd0b000102f802fa050f0002f90901ff0cf1f9fe030606ff071e", 64 * 12);

    /**
     * The fixed filter of each class in each fixed filter set, {@code CLASS_TO_FILTER[set * 25 + class]}.
     */
    static final int[] CLASS_TO_FILTER = decode(""
            + "080202020304350909340404050902080a09010327270a09340b0c0d0e0f1e0b11121310141404351516170e191a1a1b1c0a100c"
            + "1f200e101e21352223101404071015241213151a252627230b0d0e2b231004223e23231e3807231526182810153039270b1f202b"
            + "2c100411222d1e1414070515162e282f1a303f3a0a0c0d3233340b11352d091e043513001617192b2c251b1c0a371e213e332c14"
            + "2938222d14292938051e3826282f0b252a3908230b17200e231404111215141414041015242e19291a30313a0c1f3b3b0321213b"
            + "3b340421113b3716243b3b3c16243b19371f190f3c3c16111337371414351337162e192b3c251c0a37340c1f2032330b2135132d"
            + "1004043505162412192b1a1b1b1c0a05022c340304352d09030438050002050a2f34033f270a09340c222c2c0338383e2d093838"
            + "0705001626282f343039270a09230b170e33231429383e101429380710152618281a1a2a392721223333342929223e0029293807"
            + "05382626282c252a39270a101f200f3c1e04111319161404351315162e19371a303f3a37", 16 * 25);

    /**
     * Prevents instantiation.
     */
    private VvcAlfTables() {
    }

    /**
     * Decodes signed bytes written in hexadecimal.
     *
     * @param hex the hexadecimal digits, two per value
     * @param count the number of values
     * @return the values
     */
    private static int[] decode(String hex, int count) {
        var values = new int[count];
        for (var i = 0; i < count; i++) {
            values[i] = (byte) Integer.parseInt(hex, 2 * i, 2 * i + 2, 16);
        }
        return values;
    }
}

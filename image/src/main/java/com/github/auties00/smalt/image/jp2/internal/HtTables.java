/* Ports OpenJPH 0.21.3 (src/core/coding/table0.h, table1.h, ojph_block_common.cpp, ojph_block_encoder.cpp: the
   CxtVLC tables of ITU-T T.814 Annex C and the lookup tables built from them). Copyright (c) 2019, Aous Naman;
   Copyright (c) 2019, Kakadu Software Pty Ltd, Australia; Copyright (c) 2019, The University of New South Wales,
   Sydney, Australia; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jp2.internal;

/**
 * Holds the tables of the high-throughput block coder (ISO/IEC 15444-15): the context-adaptive VLC codes of Annex C
 * and the decoding and encoding lookup tables built from them at class initialization.
 *
 * <p>A CxtVLC entry packs, from the most significant field, the quad context {@code c_q} (bits 24-26), the
 * significance pattern {@code rho} (bits 20-23), {@code u_off} (bit 19), the EMB pattern {@code e_k} (bits 15-18) and
 * {@code e_1} (bits 11-14), the codeword length (bits 7-9) and the codeword (bits 0-6, first bit lowest).
 */
final class HtTables {
    /**
     * The CxtVLC codes of the initial quad row (Annex C, {@code CxtVLC_table_0}).
     */
    static final int[] CXT_VLC_0 = {
            0x0100206, 0x0188BBF, 0x0200180, 0x02913FF, 0x0300291, 0x03913DF, 0x0398B9F, 0x0400182,
            0x04A2313, 0x050028E, 0x05A2323, 0x05A8B8F, 0x0600303, 0x06803EF, 0x07003AF, 0x07913CF,
            0x079030D, 0x0800184, 0x08C433D, 0x090031D, 0x098032D, 0x0A00281, 0x0AC4335, 0x0AD13F7,
            0x0B003B7, 0x0B88BD7, 0x0B88309, 0x0C0029E, 0x0CE6397, 0x0CE2315, 0x0CE4325, 0x0D003E7,
            0x0D88BA7, 0x0DAA3C7, 0x0DEC387, 0x0E003FB, 0x0E913CB, 0x0ED4305, 0x0EF23BB, 0x0F003DB,
            0x0FCCB9B, 0x0FDD3EB, 0x0FFE3AB, 0x0FFC339, 0x0FF33F3, 0x0FF1319, 0x0FFAB8B, 0x0FFA329,
            0x0FF8BB3, 0x1000100, 0x110020E, 0x1188B9F, 0x1200206, 0x129133B, 0x130031B, 0x138033D,
            0x140020A, 0x14A232B, 0x150030B, 0x15A2333, 0x15A8BFF, 0x1600313, 0x1680323, 0x17003BF,
            0x17913DF, 0x1790303, 0x1800202, 0x18C431D, 0x190032D, 0x198030D, 0x1A00335, 0x1AC4315,
            0x1AD13EF, 0x1B003AF, 0x1B88BCF, 0x1B88311, 0x1C00281, 0x1CC4325, 0x1CE2305, 0x1D0038F,
            0x1D88B97, 0x1DAA339, 0x1DEC3F7, 0x1E003B7, 0x1E913D7, 0x1ED4319, 0x1EF23E7, 0x1F00387,
            0x1FDC329, 0x1FC43A7, 0x1FD1309, 0x1FF2331, 0x1FF8BC7, 0x2000100, 0x210020E, 0x2188B1B,
            0x2200206, 0x22913BF, 0x230032B, 0x2388B33, 0x23993FF, 0x240020A, 0x24A230B, 0x2500281,
            0x25AABAF, 0x25A8B13, 0x25AA323, 0x2600303, 0x26803DF, 0x270039F, 0x27913EF, 0x2798B11,
            0x27BA3B7, 0x2800202, 0x28C43CF, 0x290033D, 0x298031D, 0x2A0032D, 0x2A8030D, 0x2B0038F,
            0x2B913F7, 0x2B90335, 0x2C00315, 0x2CA2325, 0x2CE43D7, 0x2D00397, 0x2DC4305, 0x2DE2339,
            0x2DE8BE7, 0x2E003A7, 0x2E913FB, 0x2E90319, 0x2F003C7, 0x2FF8B29, 0x2F88B09, 0x2F99387,
            0x2FBA331, 0x2FFC3BB, 0x3000180, 0x3100204, 0x3188B3D, 0x320028C, 0x32913CF, 0x330031D,
            0x3388B05, 0x33993FF, 0x3400296, 0x34A232D, 0x3500286, 0x35AAA9A, 0x35A8B0D, 0x35AA335,
            0x36003BF, 0x36A23DF, 0x36B139F, 0x37003EF, 0x37B33AF, 0x37B2315, 0x37B9BF7, 0x37B8B25,
            0x37B938F, 0x380028A, 0x38C4387, 0x3900339, 0x3988BB7, 0x39CC3D7, 0x3A00319, 0x3AC4329,
            0x3AD1397, 0x3B003E7, 0x3BD8BA7, 0x3B88BC7, 0x3B99309, 0x3BDC3FB, 0x3C00331, 0x3CA2311,
            0x3CE43BB, 0x3D003DB, 0x3DCCB9B, 0x3DEABAB, 0x3DE8B21, 0x3DEE3EB, 0x3DEA301, 0x3DEC3CB,
            0x3E0038B, 0x3EF23F3, 0x3EA2393, 0x3EE433E, 0x3EF13B3, 0x3F003D3, 0x3FD530E, 0x3FDCBE3,
            0x3FFE383, 0x3FFC292, 0x3FF33A3, 0x3FFAB1E, 0x3FFA282, 0x3FF9BC3, 0x3FF8A9C, 0x3FF932E,
            0x4000100, 0x410020E, 0x4188BBF, 0x4200206, 0x429131B, 0x430032B, 0x439133D, 0x4398BFF,
            0x440020A, 0x44A23DF, 0x450030B, 0x4580333, 0x4600313, 0x4680323, 0x470039F, 0x47A23EF,
            0x47A0303, 0x4800202, 0x48C431D, 0x4900311, 0x49803F7, 0x4A00281, 0x4AD53AF, 0x4AD132D,
            0x4AD430D, 0x4B003CF, 0x4BD938F, 0x4B80335, 0x4C00315, 0x4CC4325, 0x4CE23B7, 0x4D003D7,
            0x4D88B87, 0x4D88305, 0x4E00397, 0x4EA2339, 0x4EE4319, 0x4EF13E7, 0x4F003A7, 0x4FCCBC7,
            0x4FC8B29, 0x4FBB3FB, 0x4FB9309, 0x4FDC331, 0x4FFA3BB, 0x5000180, 0x510029A, 0x5188BFF,
            0x520028A, 0x529131D, 0x530032D, 0x5399BDF, 0x5399339, 0x5398BBF, 0x5400292, 0x54A239F,
            0x550030D, 0x55A2335, 0x55A8BEF, 0x5600315, 0x5691325, 0x56B23AF, 0x57003CF, 0x57B33D7,
            0x57B2305, 0x57B9B8F, 0x57B93F7, 0x57B8BB7, 0x5800282, 0x58C4319, 0x5900326, 0x59C4397,
            0x59C8BE7, 0x5A0029C, 0x5AD5329, 0x5AD1309, 0x5AD4331, 0x5B003A7, 0x5BCCB87, 0x5BCC311,
            0x5BD9BC7, 0x5BD9321, 0x5BD8BFB, 0x5C00301, 0x5CC433E, 0x5CE23BB, 0x5D003DB, 0x5DCCBEB,
            0x5DCC31E, 0x5DEAB9B, 0x5DEA32E, 0x5DE8BAB, 0x5E003CB, 0x5EB338B, 0x5EF53B3, 0x5EF130E,
            0x5EF63F3, 0x5EF4336, 0x5EF23D3, 0x5F00393, 0x5FBBBC3, 0x5FBB316, 0x5FBABE3, 0x5FFE3A3,
            0x5FFA28C, 0x5FECB83, 0x5FFD3BD, 0x5FFC294, 0x5FF9BFD, 0x5FF9284, 0x5FF8B06, 0x6000180,
            0x6100204, 0x6188B03, 0x620028C, 0x629130D, 0x630029A, 0x6399B3D, 0x6398B1D, 0x639932D,
            0x640028A, 0x64A23BF, 0x6500335, 0x6588B15, 0x65AA3FF, 0x6600325, 0x66913DF, 0x66B239F,
            0x67003EF, 0x67B33CF, 0x67B2305, 0x67B9BAF, 0x67B8B36, 0x67B93F7, 0x6800292, 0x68C438F,
            0x6900339, 0x6988BB7, 0x69CC3D7, 0x6A00319, 0x6A91329, 0x6AD4397, 0x6B003E7, 0x6BCCBC7,
            0x6BC8B09, 0x6BDD3A7, 0x6BD9331, 0x6BDC3FB, 0x6C00311, 0x6CE6387, 0x6CE4321, 0x6CE23BB,
            0x6D003DB, 0x6DAABB3, 0x6DAA301, 0x6DE439B, 0x6DE8BEB, 0x6E003AB, 0x6EF13CB, 0x6E9138B,
            0x6EF63F3, 0x6EF433E, 0x6EF23D3, 0x6F00393, 0x6FB331E, 0x6FF532E, 0x6FF9B0E, 0x6FF9282,
            0x6FDCBE3, 0x6FFE316, 0x6FFC306, 0x6FFABA3, 0x6FF8A9C, 0x6FFA326, 0x7000292, 0x7100305,
            0x7188BFF, 0x7200339, 0x72913BF, 0x73003DF, 0x7399B9F, 0x73993EF, 0x7398BAF, 0x74003CF,
            0x74A238F, 0x75003D7, 0x7588B19, 0x75AA3F7, 0x76003B7, 0x7680329, 0x7700397, 0x77B33E7,
            0x77B9BA7, 0x77B93C7, 0x77BAB9B, 0x77B8B09, 0x77BA387, 0x78003FB, 0x78C43BB, 0x79003DB,
            0x7980331, 0x7A003D3, 0x7A91311, 0x7AD43EB, 0x7B003AB, 0x7BCCBCB, 0x7BD9B8B, 0x7BD8BF3,
            0x7BDD3B3, 0x7BD9321, 0x7BDC393, 0x7C003E3, 0x7CC43A3, 0x7CE23C3, 0x7D00383, 0x7DCCBFD,
            0x7DEABDD, 0x7DE8B01, 0x7DEE3BD, 0x7DEA33E, 0x7DEC39D, 0x7E003ED, 0x7EB33AD, 0x7EF538D,
            0x7EF131E, 0x7EF63CD, 0x7EF430E, 0x7EF23F5, 0x7F00395, 0x7FFFA86, 0x7FFEBB5, 0x7FFBBD5,
            0x7FFAA9A, 0x7FFDBA5, 0x7FF9A8A, 0x7FFCB2E, 0x7FF8A00, 0x7FFF3E5, 0x7FFB336, 0x7FFD282,
            0x7FF920C, 0x7FFE316, 0x7FFC204, 0x7FFA208
    };

    /**
     * The CxtVLC codes of the non-initial quad rows (Annex C, {@code CxtVLC_table_1}).
     */
    static final int[] CXT_VLC_1 = {
            0x0100180, 0x0188B27, 0x0200186, 0x0291317, 0x030028D, 0x038033B, 0x0400182, 0x04A2307,
            0x0500295, 0x058032B, 0x0600281, 0x06803FF, 0x070039F, 0x078031B, 0x0800184, 0x08C4285,
            0x0900299, 0x0980313, 0x0A00289, 0x0AC430B, 0x0AD13BF, 0x0B003DF, 0x0B80333, 0x0C00291,
            0x0CC4323, 0x0CE23EF, 0x0D0038F, 0x0D80303, 0x0E003AF, 0x0EA23CF, 0x0EA033D, 0x0F003F7,
            0x0F88BB7, 0x0F8831D, 0x1000080, 0x1100205, 0x1188BFF, 0x1200209, 0x129139F, 0x130029D,
            0x1388BBF, 0x13993DF, 0x140028D, 0x14A23B7, 0x1500303, 0x15803EF, 0x16003AF, 0x16803CF,
            0x170038F, 0x17803F7, 0x1800201, 0x18C4397, 0x190030B, 0x19803D7, 0x1A00333, 0x1A803E7,
            0x1B003A7, 0x1B803AB, 0x1C00313, 0x1C803C7, 0x1D00387, 0x1D803FB, 0x1E003BB, 0x1E803DB,
            0x1F0039B, 0x1FA23EB, 0x1FA0323, 0x2000080, 0x2100209, 0x2188BFF, 0x2200201, 0x2291323,
            0x230033D, 0x23913BF, 0x2398B9F, 0x2400295, 0x24A23DF, 0x2500303, 0x25803EF, 0x26003AF,
            0x26803CF, 0x270038F, 0x2780397, 0x2800285, 0x28C43F7, 0x29003B7, 0x29803D7, 0x2A0031D,
            0x2AD53FB, 0x2AD132D, 0x2AD43E7, 0x2B003A7, 0x2BD93C7, 0x2B80387, 0x2C0030D, 0x2C803BB,
            0x2D003DB, 0x2D8039B, 0x2E003EB, 0x2EA23AB, 0x2EA03CB, 0x2F0038B, 0x2FA23F3, 0x2FA8BB3,
            0x2FB93D3, 0x2FFC393, 0x3000100, 0x310020A, 0x3188B0B, 0x3200202, 0x3291323, 0x330028E,
            0x3399BFF, 0x3399333, 0x3398B13, 0x3400296, 0x34A23BF, 0x3500303, 0x3588B3D, 0x35AA39F,
            0x360031D, 0x36803DF, 0x370032D, 0x37A23AF, 0x37A8B1E, 0x37B93EF, 0x3800286, 0x38C43CF,
            0x390030D, 0x3980335, 0x3A00315, 0x3A91325, 0x3AD438F, 0x3B00305, 0x3BC4339, 0x3BD9B97,
            0x3BD9319, 0x3BD8BF7, 0x3C00329, 0x3C80309, 0x3D003B7, 0x3DA23D7, 0x3DA0331, 0x3E003E7,
            0x3EA23A7, 0x3EE43C7, 0x3EF13EB, 0x3F00311, 0x3FB3387, 0x3FB9BFB, 0x3FFD3BB, 0x3FF9321,
            0x3FFC301, 0x3FD43DB, 0x3FFAB9B, 0x3FF8B3E, 0x3FFA3AB, 0x4000080, 0x410028D, 0x4188BFF,
            0x4200295, 0x42913BF, 0x43003DF, 0x43803EF, 0x4400209, 0x44A2323, 0x4500333, 0x458039F,
            0x4600313, 0x46803AF, 0x47003CF, 0x47803D7, 0x4800201, 0x48C438F, 0x49003F7, 0x49803B7,
            0x4A0031D, 0x4A80397, 0x4B003E7, 0x4B803EB, 0x4C00285, 0x4CE63A7, 0x4CE43C7, 0x4CE2387,
            0x4D003FB, 0x4D803BB, 0x4E003DB, 0x4E9139B, 0x4E90303, 0x4F003AB, 0x4F88BCB, 0x4F9938B,
            0x4F9833D, 0x5000100, 0x510029E, 0x5188B3B, 0x520028A, 0x52913BF, 0x530031B, 0x538030B,
            0x5400202, 0x54A232B, 0x550028E, 0x55A2333, 0x55A8BFF, 0x5600313, 0x56803EF, 0x5700323,
            0x57913DF, 0x5790315, 0x5800296, 0x58C4303, 0x590033D, 0x598039F, 0x5A0031D, 0x5A8032D,
            0x5B0030D, 0x5B88BCF, 0x5B88335, 0x5C00286, 0x5CA2325, 0x5CE43AF, 0x5D00305, 0x5D88BF7,
            0x5DAA339, 0x5DEC38F, 0x5E00319, 0x5E913D7, 0x5ED4301, 0x5EF23B7, 0x5F0029A, 0x5FCCB97,
            0x5FEABE7, 0x5FF9B87, 0x5FF8B29, 0x5FBB3A7, 0x5FFE309, 0x5FFA331, 0x5FFD3C7, 0x5FFC311,
            0x5FF9321, 0x6000180, 0x6100202, 0x6188B03, 0x620020C, 0x629133D, 0x630031D, 0x639130D,
            0x6398BFF, 0x6400204, 0x64A232D, 0x650028A, 0x65A2335, 0x65A8BAF, 0x6600315, 0x66913BF,
            0x66B23DF, 0x6700325, 0x6791329, 0x6798B9F, 0x67BA3EF, 0x6800296, 0x68C4305, 0x6900339,
            0x6980319, 0x6A00286, 0x6AD538F, 0x6AD1309, 0x6AD43CF, 0x6B0030E, 0x6BD93F7, 0x6B913B7,
            0x6BD43D7, 0x6BD8BC7, 0x6C0029A, 0x6CE6397, 0x6CE43E7, 0x6CE23A7, 0x6D00331, 0x6DEA387,
            0x6DA23FB, 0x6DE43BB, 0x6DE8BAB, 0x6E00311, 0x6EF23DB, 0x6EA239B, 0x6EF53EB, 0x6EF4321,
            0x6EF13B3, 0x6F00301, 0x6F99BCB, 0x6FBB38B, 0x6FFD3F3, 0x6FF933E, 0x6FDCBD3, 0x6FFE3E3,
            0x6FFC31E, 0x6FFAB93, 0x6FFA32E, 0x6FF8BA3, 0x7000204, 0x7100333, 0x7188B13, 0x7200323,
            0x72913FF, 0x7300303, 0x7388BBF, 0x73993EF, 0x740032D, 0x74A23DF, 0x7500296, 0x7588B3D,
            0x75AA39F, 0x760031D, 0x76803F7, 0x7700286, 0x77BA3AF, 0x77A23CF, 0x77B9B8F, 0x77B8B0D,
            0x77B93D7, 0x7800335, 0x78C43B7, 0x7900315, 0x79803A7, 0x7A00325, 0x7A80329, 0x7B0029A,
            0x7BD8B97, 0x7B88BE7, 0x7B99305, 0x7BDC3FB, 0x7C00339, 0x7C80319, 0x7D0028C, 0x7DE8BC7,
            0x7D88B87, 0x7DAA309, 0x7DEC39B, 0x7E00331, 0x7EF13BB, 0x7E913DB, 0x7ED433E, 0x7EF238B,
            0x7F00180, 0x7FFFBEB, 0x7FFBBAB, 0x7FFDBCB, 0x7FF9B11, 0x7FBB321, 0x7FFD301, 0x7FF928A,
            0x7FDCB1E, 0x7FFE30E, 0x7FFC292, 0x7FFAB2E, 0x7FF8A82, 0x7FFA29C
    };

    /**
     * The decoding table of the initial quad row, indexed by {@code (c_q << 7) | next seven VLC bits}: the codeword
     * length in bits 0-2, {@code u_off} in bit 3, {@code rho} in bits 4-7, {@code e_1} in bits 8-11 and {@code e_k}
     * in bits 12-15.
     */
    static final int[] DECODE_0 = decodeTable(CXT_VLC_0);

    /**
     * The decoding table of the non-initial quad rows, laid out as {@link #DECODE_0}.
     */
    static final int[] DECODE_1 = decodeTable(CXT_VLC_1);

    /**
     * The encoding table of the initial quad row, indexed by {@code (c_q << 8) | (rho << 4) | eps}: the codeword in
     * bits 8-15, its length in bits 4-6 and the {@code e_k} pattern it signals in bits 0-3; {@code eps} marks the
     * samples whose exponent equals the quad's bound.
     */
    static final int[] ENCODE_0 = encodeTable(CXT_VLC_0);

    /**
     * The encoding table of the non-initial quad rows, laid out as {@link #ENCODE_0}.
     */
    static final int[] ENCODE_1 = encodeTable(CXT_VLC_1);

    /**
     * The U-VLC code of each {@code u} value from 0 to 74: the prefix in bits 0-2, its length in bits 3-4, the
     * suffix in bits 5-9, its length in bits 10-12, the extension in bits 13-16 and its length in bits 17-19.
     */
    static final int[] UVLC = uvlcTable();

    /**
     * Prevents instantiation.
     */
    private HtTables() {
        throw new AssertionError();
    }

    /**
     * Builds a decoding table from CxtVLC entries.
     *
     * @param source the packed CxtVLC entries
     * @return the table
     */
    private static int[] decodeTable(int[] source) {
        var table = new int[1024];
        for (var i = 0; i < 1024; i++) {
            var codeword = i & 0x7F;
            var context = i >> 7;
            for (var entry : source) {
                var length = (entry >> 7) & 0x7;
                if ((entry >> 24) == context && (entry & 0x7F) == (codeword & ((1 << length) - 1))) {
                    table[i] = (((entry >> 20) & 0xF) << 4) | (((entry >> 19) & 1) << 3) | (((entry >> 15) & 0xF) << 12)
                            | (((entry >> 11) & 0xF) << 8) | length;
                }
            }
        }
        return table;
    }

    /**
     * Builds an encoding table from CxtVLC entries, choosing for each significance and EMB pattern the entry that
     * signals the most {@code e_k} bits.
     *
     * @param source the packed CxtVLC entries
     * @return the table
     */
    private static int[] encodeTable(int[] source) {
        var table = new int[2048];
        for (var i = 0; i < 2048; i++) {
            var context = i >> 8;
            var rho = (i >> 4) & 0xF;
            var emb = i & 0xF;
            if ((emb & rho) != emb || (rho == 0 && context == 0)) {
                continue;
            }
            var best = -1;
            var bestOnes = -1;
            for (var entry : source) {
                if ((entry >> 24) != context || ((entry >> 20) & 0xF) != rho) {
                    continue;
                }
                var uOff = (entry >> 19) & 1;
                var ek = (entry >> 15) & 0xF;
                var e1 = (entry >> 11) & 0xF;
                if (emb != 0) {
                    if (uOff == 1 && (emb & ek) == e1 && Integer.bitCount(ek) >= bestOnes) {
                        best = entry;
                        bestOnes = Integer.bitCount(ek);
                    }
                } else if (uOff == 0) {
                    best = entry;
                    break;
                }
            }
            if (best < 0) {
                throw new AssertionError("no CxtVLC code for index " + i);
            }
            table[i] = ((best & 0x7F) << 8) | (((best >> 7) & 0x7) << 4) | ((best >> 15) & 0xF);
        }
        return table;
    }

    /**
     * Builds the U-VLC code table (Table 3 of ISO/IEC 15444-15).
     *
     * @return the table
     */
    private static int[] uvlcTable() {
        var table = new int[75];
        table[0] = 0;
        table[1] = uvlc(1, 1, 0, 0, 0, 0);
        table[2] = uvlc(2, 2, 0, 0, 0, 0);
        table[3] = uvlc(4, 3, 0, 1, 0, 0);
        table[4] = uvlc(4, 3, 1, 1, 0, 0);
        for (var i = 5; i < 33; i++) {
            table[i] = uvlc(0, 3, i - 5, 5, 0, 0);
        }
        for (var i = 33; i < 75; i++) {
            table[i] = uvlc(0, 3, 28 + (i - 33) % 4, 5, (i - 33) / 4, 4);
        }
        return table;
    }

    /**
     * Packs one U-VLC code.
     *
     * @param prefix the prefix bits, first bit lowest
     * @param prefixLength the prefix length
     * @param suffix the suffix bits
     * @param suffixLength the suffix length
     * @param extension the extension bits
     * @param extensionLength the extension length
     * @return the packed code
     */
    private static int uvlc(int prefix, int prefixLength, int suffix, int suffixLength, int extension,
                            int extensionLength) {
        return prefix | (prefixLength << 3) | (suffix << 5) | (suffixLength << 10) | (extension << 13)
                | (extensionLength << 17);
    }
}

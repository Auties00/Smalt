package com.github.auties00.smalt.video.vc1.internal;

/**
 * Holds the block level tables of SMPTE ST 421 section 11: coded block patterns, DC differentials, macroblock
 * modes, block patterns and zigzag scans.
 *
 * <p>Each entry of a code table packs the code length in bits 27 to 31 and the code in bits 0 to 26, and the
 * entries are indexed by the symbol the code decodes to. The values are those of the standard.
 */
final class Vc1BlockTables {
    /**
     * Prevents instantiation.
     */
    private Vc1BlockTables() {
    }

    /**
     * The I picture coded block pattern code table (Table 168).
     */
    static final int[] I_CBPCY = {
            0x08000001, 0x30000017, 0x28000009, 0x28000005, 0x28000006, 0x48000047,
            0x38000020, 0x38000010, 0x28000002, 0x4800007C, 0x3800003A, 0x3800001D,
            0x30000002, 0x480000EC, 0x40000077, 0x40000000, 0x28000003, 0x480000B7,
            0x3800002C, 0x38000013, 0x30000001, 0x50000168, 0x40000046, 0x4000003F,
            0x3000001E, 0x68000712, 0x480000B5, 0x40000042, 0x38000022, 0x580001C5,
            0x5000011E, 0x48000087, 0x20000006, 0x48000003, 0x3800001E, 0x3000001C,
            0x38000012, 0x60000388, 0x48000044, 0x48000070, 0x3000001F, 0x5800023E,
            0x40000039, 0x4800008E, 0x38000001, 0x580001C6, 0x480000B6, 0x48000045,
            0x30000014, 0x5800023F, 0x4800007D, 0x48000018, 0x38000007, 0x580001C7,
            0x48000086, 0x48000019, 0x30000015, 0x500001DB, 0x48000002, 0x48000046,
            0x4000000D, 0x68000713, 0x500001DA, 0x50000169
    };

    /**
     * The P and B picture coded block pattern code table 0 (Table 169).
     */
    static final int[] P_CBPCY_0 = {
            0x68000000, 0x68000006, 0x3800000F, 0x6800000D, 0x3800000D, 0x6800000B,
            0x68000003, 0x6000000D, 0x30000005, 0x68000008, 0x38000031, 0x6000000A,
            0x3000000C, 0x40000072, 0x40000066, 0x40000077, 0x28000001, 0x38000036,
            0x40000060, 0x60000008, 0x3000000A, 0x4000006F, 0x68000005, 0x6000000F,
            0x3800000C, 0x6800000A, 0x68000002, 0x6000000C, 0x3000000D, 0x40000073,
            0x38000035, 0x3800003F, 0x30000001, 0x68000007, 0x40000001, 0x60000007,
            0x3800000E, 0x6800000C, 0x68000004, 0x6000000E, 0x38000001, 0x68000009,
            0x40000061, 0x6000000B, 0x28000007, 0x3800003A, 0x38000034, 0x3800003E,
            0x30000004, 0x40000067, 0x68000001, 0x60000009, 0x3000000B, 0x38000038,
            0x40000065, 0x40000076, 0x28000004, 0x4000006E, 0x40000064, 0x3000001E,
            0x18000002, 0x18000005, 0x18000004, 0x10000003
    };

    /**
     * The P and B picture coded block pattern code table 1 (Table 170).
     */
    static final int[] P_CBPCY_1 = {
            0x70000000, 0x68000009, 0x40000001, 0x68000012, 0x18000005, 0x6800000E,
            0x400000ED, 0x6800001A, 0x18000003, 0x38000079, 0x40000003, 0x68000016,
            0x2000000D, 0x68000010, 0x68000006, 0x6800001E, 0x18000002, 0x6800000A,
            0x68000001, 0x68000014, 0x2000000C, 0x400000F1, 0x68000005, 0x6800001C,
            0x28000010, 0x6800000C, 0x68000003, 0x68000018, 0x2800001C, 0x3800007C,
            0x400000EF, 0x400000F7, 0x18000001, 0x400000F0, 0x70000001, 0x68000013,
            0x28000012, 0x6800000F, 0x68000004, 0x6800001B, 0x20000001, 0x3800007A,
            0x68000002, 0x68000017, 0x30000001, 0x68000011, 0x68000007, 0x6800001F,
            0x28000001, 0x6800000B, 0x40000002, 0x68000015, 0x28000013, 0x400000F6,
            0x400000EE, 0x6800001D, 0x28000011, 0x6800000D, 0x400000EC, 0x68000019,
            0x3000003A, 0x3000003F, 0x68000008, 0x3800007D
    };

    /**
     * The P and B picture coded block pattern code table 2 (Table 171).
     */
    static final int[] P_CBPCY_2 = {
            0x68000000, 0x400000C9, 0x30000019, 0x400000E7, 0x20000005, 0x400000DD,
            0x68000001, 0x60000003, 0x20000002, 0x4800019E, 0x40000002, 0x400000F1,
            0x28000010, 0x400000E1, 0x400000C3, 0x480001EC, 0x28000002, 0x4800019C,
            0x50000001, 0x400000F0, 0x20000007, 0x400000E0, 0x38000062, 0x400000F5,
            0x30000001, 0x400000DC, 0x38000060, 0x68000005, 0x20000009, 0x400000E6,
            0x38000065, 0x400000F7, 0x28000001, 0x38000066, 0x40000001, 0x4800019F,
            0x30000018, 0x68000003, 0x68000002, 0x400000F4, 0x20000003, 0x30000036,
            0x40000003, 0x480001E4, 0x28000011, 0x38000072, 0x400000C8, 0x480001ED,
            0x28000003, 0x4800019D, 0x48000001, 0x68000004, 0x2800000D, 0x38000071,
            0x38000063, 0x480001E5, 0x20000004, 0x3800006F, 0x400000C2, 0x400000F3,
            0x18000005, 0x2800001D, 0x2800001A, 0x2800001F
    };

    /**
     * The P and B picture coded block pattern code table 3 (Table 172).
     */
    static final int[] P_CBPCY_3 = {
            0x48000000, 0x4800001C, 0x4800000C, 0x4800002C, 0x10000003, 0x48000024,
            0x48000014, 0x48000034, 0x10000002, 0x48000020, 0x48000010, 0x48000030,
            0x48000008, 0x48000028, 0x48000018, 0x4000001C, 0x18000001, 0x4800001E,
            0x4800000E, 0x4800002E, 0x48000006, 0x48000026, 0x48000016, 0x48000036,
            0x48000003, 0x48000022, 0x48000012, 0x48000032, 0x4800000A, 0x4800002A,
            0x4800001A, 0x4000001E, 0x10000001, 0x4800001D, 0x4800000D, 0x4800002D,
            0x48000005, 0x48000025, 0x48000015, 0x48000035, 0x48000002, 0x48000021,
            0x48000011, 0x48000031, 0x48000009, 0x48000029, 0x48000019, 0x4000001D,
            0x48000001, 0x4800001F, 0x4800000F, 0x4800002F, 0x48000007, 0x48000027,
            0x48000017, 0x48000037, 0x48000004, 0x48000023, 0x48000013, 0x48000033,
            0x4800000B, 0x4800002B, 0x4800001B, 0x4000001F
    };

    /**
     * The interlaced coded block pattern code table 0 (Table 124), indexed by the pattern minus 1.
     */
    static final int[] INTERLACED_CBPCY_0 = {
            0x78002F1A, 0x78002F1B, 0x7000178C, 0x48000090, 0x580002A8, 0x580002A9,
            0x68000BC7, 0x48000091, 0x580002AA, 0x580002AB, 0x600005E0, 0x4000004A,
            0x48000096, 0x48000097, 0x480000BD, 0x48000092, 0x580002AC, 0x580002AD,
            0x600005E1, 0x48000098, 0x50000132, 0x50000133, 0x50000179, 0x50000134,
            0x5800026A, 0x5800026B, 0x580002FC, 0x4000004E, 0x40000040, 0x40000041,
            0x3800002B, 0x48000093, 0x580002AE, 0x580002AF, 0x600005E2, 0x50000136,
            0x5800026E, 0x5800026F, 0x580002FD, 0x4800009E, 0x5000013E, 0x5000013F,
            0x5000017F, 0x40000050, 0x40000042, 0x40000043, 0x3800002C, 0x40000051,
            0x480000A4, 0x480000A5, 0x480000BE, 0x40000053, 0x40000044, 0x40000045,
            0x3800002D, 0x40000054, 0x40000046, 0x40000047, 0x3800002E, 0x18000003,
            0x18000000, 0x18000001, 0x08000001
    };

    /**
     * The interlaced coded block pattern code table 1 (Table 125), indexed by the pattern minus 1.
     */
    static final int[] INTERLACED_CBPCY_1 = {
            0x38000041, 0x38000042, 0x48000100, 0x38000043, 0x40000088, 0x40000089,
            0x48000101, 0x38000045, 0x4000008C, 0x4000008D, 0x48000102, 0x30000010,
            0x38000022, 0x38000023, 0x38000024, 0x38000047, 0x38000010, 0x38000011,
            0x48000103, 0x38000025, 0x40000058, 0x40000059, 0x4000005A, 0x4000005B,
            0x4800005A, 0x4800005B, 0x4800005C, 0x3000000C, 0x38000030, 0x38000031,
            0x30000019, 0x30000009, 0x38000014, 0x38000015, 0x4000002C, 0x4000005C,
            0x4800005D, 0x4800005E, 0x4800005F, 0x38000026, 0x4000005D, 0x4000005E,
            0x4000005F, 0x3000000D, 0x38000034, 0x38000035, 0x3000001B, 0x30000014,
            0x38000027, 0x3800002A, 0x3800002B, 0x3000000E, 0x38000038, 0x38000039,
            0x3000001D, 0x3000000F, 0x3800003C, 0x3800003D, 0x3000001F, 0x18000005,
            0x20000009, 0x18000000, 0x10000003
    };

    /**
     * The interlaced coded block pattern code table 2 (Table 126), indexed by the pattern minus 1.
     */
    static final int[] INTERLACED_CBPCY_2 = {
            0x30000032, 0x30000033, 0x2800001A, 0x30000026, 0x400000E4, 0x400000E5,
            0x480001E6, 0x30000027, 0x400000E6, 0x400000E7, 0x480001E7, 0x2800000E,
            0x38000063, 0x3800006C, 0x38000077, 0x30000028, 0x400000E8, 0x400000E9,
            0x480001E8, 0x3800007B, 0x400000DA, 0x400000DB, 0x400000EC, 0x400000F5,
            0x480001B8, 0x480001B9, 0x480001DA, 0x30000021, 0x3800004B, 0x38000054,
            0x3000002B, 0x30000029, 0x400000EA, 0x400000EB, 0x480001E9, 0x3800004A,
            0x480001BA, 0x480001BB, 0x480001DB, 0x30000020, 0x400000DE, 0x400000DF,
            0x400000F2, 0x30000022, 0x38000055, 0x38000058, 0x3000002D, 0x2800000F,
            0x38000070, 0x38000071, 0x38000078, 0x30000023, 0x38000059, 0x3800005C,
            0x3000002F, 0x30000024, 0x3800005D, 0x38000062, 0x30000030, 0x18000002,
            0x2800001F, 0x20000006, 0x10000000
    };

    /**
     * The interlaced coded block pattern code table 3 (Table 127), indexed by the pattern minus 1.
     */
    static final int[] INTERLACED_CBPCY_3 = {
            0x30000028, 0x30000029, 0x4000009D, 0x20000000, 0x480001EA, 0x480001EB,
            0x480001EC, 0x20000001, 0x480001ED, 0x480001EE, 0x480001EF, 0x20000005,
            0x400000F0, 0x400000F1, 0x3800003B, 0x20000002, 0x480001F0, 0x480001F1,
            0x480001F2, 0x3000003F, 0x4800015C, 0x4800015D, 0x40000099, 0x30000010,
            0x500003D0, 0x500003D1, 0x48000130, 0x2800000F, 0x4000009E, 0x4000009F,
            0x400000FB, 0x20000003, 0x480001F3, 0x480001F4, 0x480001F5, 0x30000011,
            0x500003D2, 0x500003D3, 0x48000131, 0x28000009, 0x4800015E, 0x4800015F,
            0x4000009C, 0x28000010, 0x400000A8, 0x400000A9, 0x38000038, 0x20000006,
            0x400000F2, 0x400000F3, 0x3800004D, 0x28000011, 0x400000AA, 0x400000AB,
            0x38000039, 0x28000012, 0x400000AC, 0x400000AD, 0x3800003A, 0x18000006,
            0x28000016, 0x28000017, 0x2000000E
    };

    /**
     * The interlaced coded block pattern code table 4 (Table 128), indexed by the pattern minus 1.
     */
    static final int[] INTERLACED_CBPCY_4 = {
            0x3000003C, 0x3000003D, 0x2800001F, 0x2800000A, 0x38000061, 0x38000062,
            0x38000002, 0x2800000B, 0x38000063, 0x38000064, 0x38000003, 0x28000007,
            0x30000003, 0x30000004, 0x3000000B, 0x2800000C, 0x38000065, 0x38000066,
            0x38000004, 0x30000012, 0x3800000A, 0x3800000B, 0x38000014, 0x3800001B,
            0x40000018, 0x40000019, 0x40000034, 0x3000002C, 0x38000067, 0x38000068,
            0x30000035, 0x2800000D, 0x38000069, 0x3800006C, 0x38000005, 0x38000060,
            0x4000001A, 0x4000001B, 0x40000035, 0x30000013, 0x3800000E, 0x3800000F,
            0x38000015, 0x3000002D, 0x3800006D, 0x3800006E, 0x30000038, 0x28000008,
            0x30000008, 0x30000009, 0x3000000C, 0x3000002E, 0x3800006F, 0x38000072,
            0x3000003A, 0x3000002F, 0x38000073, 0x30000000, 0x3000003B, 0x20000007,
            0x28000014, 0x28000015, 0x18000004
    };

    /**
     * The interlaced coded block pattern code table 5 (Table 129), indexed by the pattern minus 1.
     */
    static final int[] INTERLACED_CBPCY_5 = {
            0x30000038, 0x30000039, 0x4000009D, 0x2000000A, 0x40000091, 0x40000092,
            0x40000093, 0x2000000B, 0x40000094, 0x40000095, 0x40000096, 0x20000003,
            0x400000EE, 0x400000EF, 0x38000036, 0x2000000C, 0x40000097, 0x40000098,
            0x40000099, 0x28000008, 0x480001E4, 0x480001E5, 0x4000006A, 0x30000018,
            0x500003CC, 0x500003CD, 0x480000D6, 0x2800000E, 0x4000009E, 0x4000009F,
            0x400000F5, 0x2000000D, 0x4000009A, 0x4000009B, 0x4000009C, 0x30000019,
            0x500003CE, 0x500003CF, 0x480000D7, 0x28000009, 0x480001E8, 0x480001E9,
            0x40000090, 0x2800000F, 0x400000E8, 0x400000E9, 0x400000F6, 0x20000005,
            0x400000F0, 0x400000F1, 0x38000037, 0x28000010, 0x400000EA, 0x400000EB,
            0x400000F7, 0x28000011, 0x400000EC, 0x400000ED, 0x38000034, 0x18000000,
            0x3000003E, 0x3000003F, 0x20000002
    };

    /**
     * The interlaced coded block pattern code table 6 (Table 130), indexed by the pattern minus 1.
     */
    static final int[] INTERLACED_CBPCY_6 = {
            0x3000003C, 0x3000003D, 0x480001CF, 0x18000000, 0x400000BF, 0x400000E0,
            0x480001FC, 0x18000001, 0x400000E1, 0x400000E2, 0x480001FD, 0x20000009,
            0x480001F1, 0x480001F2, 0x480001F3, 0x18000002, 0x400000E3, 0x400000E4,
            0x480001FE, 0x28000011, 0x500003EE, 0x500003EF, 0x500003F0, 0x30000021,
            0x580007E2, 0x580007E3, 0x580007E4, 0x28000018, 0x500003F7, 0x500003FE,
            0x500003FF, 0x18000003, 0x400000E5, 0x400000E6, 0x40000080, 0x3000002E,
            0x580007E5, 0x580007E6, 0x580007E7, 0x28000016, 0x500003F4, 0x500003F5,
            0x500003F6, 0x28000019, 0x48000102, 0x48000103, 0x48000104, 0x2000000A,
            0x480001F4, 0x480001F5, 0x480001F6, 0x2800001A, 0x48000105, 0x48000106,
            0x48000107, 0x2800001B, 0x48000178, 0x48000179, 0x480001CE, 0x2800001D,
            0x400000BD, 0x400000BE, 0x480001F0
    };

    /**
     * The interlaced coded block pattern code table 7 (Table 131), indexed by the pattern minus 1.
     */
    static final int[] INTERLACED_CBPCY_7 = {
            0x30000003, 0x30000004, 0x500001B6, 0x18000004, 0x3800002E, 0x3800002F,
            0x3800000E, 0x18000005, 0x38000030, 0x38000031, 0x3800000F, 0x20000003,
            0x4000000A, 0x4000000B, 0x40000014, 0x18000006, 0x38000032, 0x38000033,
            0x38000010, 0x28000005, 0x48000030, 0x48000031, 0x48000032, 0x30000009,
            0x50000066, 0x50000067, 0x50000068, 0x3000001D, 0x500001B7, 0x500001B8,
            0x500001B9, 0x18000007, 0x38000034, 0x38000035, 0x38000011, 0x30000016,
            0x50000069, 0x5000006A, 0x5000006B, 0x2800000A, 0x48000036, 0x48000037,
            0x480000D8, 0x3000001E, 0x500001BA, 0x500001BB, 0x500001BC, 0x20000004,
            0x40000015, 0x40000016, 0x40000017, 0x3000001F, 0x500001BD, 0x500001BE,
            0x500001BF, 0x28000000, 0x48000010, 0x48000011, 0x48000012, 0x3000001C,
            0x480000D9, 0x480000DA, 0x48000013
    };

    /**
     * The low-motion luma DC differential code table (Table 173); the last entry is the escape code.
     */
    static final int[] LOW_MOTION_LUMA_DC = {
            0x08000001, 0x10000001, 0x20000001, 0x28000001, 0x28000005, 0x28000007,
            0x30000008, 0x3000000C, 0x38000000, 0x38000002, 0x38000012, 0x3800001A,
            0x40000003, 0x40000007, 0x40000027, 0x40000037, 0x48000005, 0x4800004C,
            0x4800006C, 0x4800006D, 0x50000008, 0x50000019, 0x5000009B, 0x5000001B,
            0x5000009A, 0x58000013, 0x58000034, 0x58000035, 0x60000061, 0x68000048,
            0x680000C4, 0x6800004A, 0x680000C6, 0x680000C7, 0x70000092, 0x7000018B,
            0x70000093, 0x70000183, 0x70000182, 0x70000096, 0x70000097, 0x70000180,
            0x78000314, 0x78000315, 0x80000605, 0x80000604, 0x80000606, 0x88000C0E,
            0xB80303CD, 0xB80303C9, 0xB80303C8, 0xB80303CA, 0xB80303CB, 0xB80303CC,
            0xB80303CE, 0xB80303CF, 0xB80303D0, 0xB80303D1, 0xB80303D2, 0xB80303D3,
            0xB80303D4, 0xB80303D5, 0xB80303D6, 0xB80303D7, 0xB80303D8, 0xB80303D9,
            0xB80303DA, 0xB80303DB, 0xB80303DC, 0xB80303DD, 0xB80303DE, 0xB80303DF,
            0xB80303E0, 0xB80303E1, 0xB80303E2, 0xB80303E3, 0xB80303E4, 0xB80303E5,
            0xB80303E6, 0xB80303E7, 0xB80303E8, 0xB80303E9, 0xB80303EA, 0xB80303EB,
            0xB80303EC, 0xB80303ED, 0xB80303EE, 0xB80303EF, 0xB80303F0, 0xB80303F1,
            0xB80303F2, 0xB80303F3, 0xB80303F4, 0xB80303F5, 0xB80303F6, 0xB80303F7,
            0xB80303F8, 0xB80303F9, 0xB80303FA, 0xB80303FB, 0xB80303FC, 0xB80303FD,
            0xB80303FE, 0xB80303FF, 0xC0060780, 0xC0060781, 0xC0060782, 0xC0060783,
            0xC0060784, 0xC0060785, 0xC0060786, 0xC0060787, 0xC0060788, 0xC0060789,
            0xC006078A, 0xC006078B, 0xC006078C, 0xC006078D, 0xC006078E, 0xC006078F
    };

    /**
     * The low-motion color-difference DC differential code table (Table 174); the last entry is the escape code.
     */
    static final int[] LOW_MOTION_CHROMA_DC = {
            0x10000000, 0x10000001, 0x18000005, 0x20000009, 0x2000000D, 0x28000011,
            0x2800001D, 0x2800001F, 0x30000021, 0x30000031, 0x30000038, 0x30000033,
            0x30000039, 0x3000003D, 0x38000061, 0x38000079, 0x40000080, 0x400000C8,
            0x400000CA, 0x400000F0, 0x40000081, 0x400000C0, 0x400000C9, 0x48000107,
            0x48000106, 0x48000196, 0x48000183, 0x480001E3, 0x480001E2, 0x5000020A,
            0x5000020B, 0x58000609, 0x58000412, 0x58000413, 0x5800060B, 0x58000411,
            0x5800060A, 0x5800065F, 0x58000410, 0x5800065D, 0x5800065E, 0x60000CB8,
            0x60000C10, 0x60000CB9, 0x68001823, 0x70003045, 0x78006089, 0x8000C110,
            0xB0304448, 0xB0304449, 0xB030444A, 0xB030444B, 0xB030444C, 0xB030444D,
            0xB030444E, 0xB030444F, 0xB0304450, 0xB0304451, 0xB0304452, 0xB0304453,
            0xB0304454, 0xB0304455, 0xB0304456, 0xB0304457, 0xB0304458, 0xB0304459,
            0xB030445A, 0xB030445B, 0xB030445C, 0xB030445D, 0xB030445E, 0xB030445F,
            0xB0304460, 0xB0304461, 0xB0304462, 0xB0304463, 0xB0304464, 0xB0304465,
            0xB0304466, 0xB0304467, 0xB0304468, 0xB0304469, 0xB030446A, 0xB030446B,
            0xB030446C, 0xB030446D, 0xB030446E, 0xB030446F, 0xB0304470, 0xB0304471,
            0xB0304472, 0xB0304473, 0xB0304474, 0xB0304475, 0xB0304476, 0xB0304477,
            0xB0304478, 0xB0304479, 0xB030447A, 0xB030447B, 0xB030447C, 0xB030447D,
            0xB030447E, 0xB030447F, 0xB8608880, 0xB8608881, 0xB8608882, 0xB8608883,
            0xB8608884, 0xB8608885, 0xB8608886, 0xB8608887, 0xB8608888, 0xB8608889,
            0xB860888A, 0xB860888B, 0xB860888C, 0xB860888D, 0xB860888E, 0xB860888F
    };

    /**
     * The high-motion luma DC differential code table (Table 175); the last entry is the escape code.
     */
    static final int[] HIGH_MOTION_LUMA_DC = {
            0x10000002, 0x10000003, 0x18000003, 0x20000002, 0x20000005, 0x28000001,
            0x28000003, 0x28000008, 0x30000000, 0x30000005, 0x3000000D, 0x3000000F,
            0x30000013, 0x38000008, 0x38000018, 0x3800001C, 0x38000024, 0x40000004,
            0x40000006, 0x40000012, 0x40000032, 0x4000003B, 0x4000004A, 0x4000004B,
            0x4800000B, 0x48000026, 0x48000027, 0x48000066, 0x48000074, 0x48000075,
            0x50000014, 0x5000001C, 0x5000001F, 0x5000001D, 0x5800002B, 0x5800003D,
            0x5800019D, 0x5800019F, 0x60000054, 0x60000339, 0x60000338, 0x6000033D,
            0x680000AB, 0x680000F1, 0x68000678, 0x680000F2, 0x700001E0, 0x700001E1,
            0x70000154, 0x70000CF2, 0x780003CC, 0x780002AB, 0x780019E7, 0x780003CE,
            0x780019E6, 0x80000554, 0x8000079F, 0x80000555, 0x88000F3D, 0x88000F37,
            0x88000F3C, 0x88000F35, 0x90001E6D, 0x90001E68, 0x98003CD8, 0x98003CD3,
            0x98003CD9, 0xA00079A4, 0xC80F34BA, 0xC80F34B4, 0xC80F34B5, 0xC80F34B6,
            0xC80F34B7, 0xC80F34B8, 0xC80F34B9, 0xC80F34BB, 0xC80F34BC, 0xC80F34BD,
            0xC80F34BE, 0xC80F34BF, 0xD01E6940, 0xD01E6941, 0xD01E6942, 0xD01E6943,
            0xD01E6944, 0xD01E6945, 0xD01E6946, 0xD01E6947, 0xD01E6948, 0xD01E6949,
            0xD01E694A, 0xD01E694B, 0xD01E694C, 0xD01E694D, 0xD01E694E, 0xD01E694F,
            0xD01E6950, 0xD01E6951, 0xD01E6952, 0xD01E6953, 0xD01E6954, 0xD01E6955,
            0xD01E6956, 0xD01E6957, 0xD01E6958, 0xD01E6959, 0xD01E695A, 0xD01E695B,
            0xD01E695C, 0xD01E695D, 0xD01E695E, 0xD01E695F, 0xD01E6960, 0xD01E6961,
            0xD01E6962, 0xD01E6963, 0xD01E6964, 0xD01E6965, 0xD01E6966, 0xD01E6967
    };

    /**
     * The high-motion color-difference DC differential code table (Table 176); the last entry is the escape code.
     */
    static final int[] HIGH_MOTION_CHROMA_DC = {
            0x10000000, 0x10000001, 0x18000004, 0x18000007, 0x2000000B, 0x2000000D,
            0x28000015, 0x30000028, 0x30000030, 0x30000032, 0x38000052, 0x38000062,
            0x38000066, 0x400000A6, 0x400000C6, 0x400000CF, 0x4800014F, 0x4800018E,
            0x4800019C, 0x5000029D, 0x5000033A, 0x58000538, 0x5800063C, 0x5800063E,
            0x5800063F, 0x58000676, 0x60000A73, 0x60000C7A, 0x60000CEF, 0x680014E5,
            0x680019DD, 0x700029C8, 0x700029C9, 0x780063DD, 0x700033B8, 0x700033B9,
            0x8000C7B6, 0x780063D8, 0x780063DF, 0x8000C7B3, 0x8000C7B4, 0x8000C7B5,
            0x780063DE, 0x8000C7B7, 0x8000C7B8, 0x8000C7B9, 0x88018F65, 0x90031EC8,
            0xC0C7B248, 0xC0C7B249, 0xC0C7B24A, 0xC0C7B24B, 0xC0C7B24C, 0xC0C7B24D,
            0xC0C7B24E, 0xC0C7B24F, 0xC0C7B250, 0xC0C7B251, 0xC0C7B252, 0xC0C7B253,
            0xC0C7B254, 0xC0C7B255, 0xC0C7B256, 0xC0C7B257, 0xC0C7B258, 0xC0C7B259,
            0xC0C7B25A, 0xC0C7B25B, 0xC0C7B25C, 0xC0C7B25D, 0xC0C7B25E, 0xC0C7B25F,
            0xC0C7B260, 0xC0C7B261, 0xC0C7B262, 0xC0C7B263, 0xC0C7B264, 0xC0C7B265,
            0xC0C7B266, 0xC0C7B267, 0xC0C7B268, 0xC0C7B269, 0xC0C7B26A, 0xC0C7B26B,
            0xC0C7B26C, 0xC0C7B26D, 0xC0C7B26E, 0xC0C7B26F, 0xC0C7B270, 0xC0C7B271,
            0xC0C7B272, 0xC0C7B273, 0xC0C7B274, 0xC0C7B275, 0xC0C7B276, 0xC0C7B277,
            0xC0C7B278, 0xC0C7B279, 0xC0C7B27A, 0xC0C7B27B, 0xC0C7B27C, 0xC0C7B27D,
            0xC0C7B27E, 0xC0C7B27F, 0xC98F6480, 0xC98F6481, 0xC98F6482, 0xC98F6483,
            0xC98F6484, 0xC98F6485, 0xC98F6486, 0xC98F6487, 0xC98F6488, 0xC98F6489,
            0xC98F648A, 0xC98F648B, 0xC98F648C, 0xC98F648D, 0xC98F648E, 0xC98F648F
    };

    /**
     * The 4-MV block pattern code table 0 (Table 116).
     */
    static final int[] FOUR_MV_BLOCK_PATTERN_0 = {
            0x2800000E, 0x3000003A, 0x3000003B, 0x28000019, 0x2800000C, 0x2800001A,
            0x2800000F, 0x2000000F, 0x2800000D, 0x28000018, 0x2800001B, 0x18000000,
            0x2800001C, 0x18000001, 0x18000002, 0x10000002
    };

    /**
     * The 4-MV block pattern code table 1 (Table 117).
     */
    static final int[] FOUR_MV_BLOCK_PATTERN_1 = {
            0x20000008, 0x28000012, 0x28000013, 0x20000004, 0x28000014, 0x20000005,
            0x2800001E, 0x2000000B, 0x28000015, 0x2800001F, 0x20000006, 0x2000000C,
            0x20000007, 0x2000000D, 0x2000000E, 0x10000000
    };

    /**
     * The 4-MV block pattern code table 2 (Table 118).
     */
    static final int[] FOUR_MV_BLOCK_PATTERN_2 = {
            0x2000000F, 0x20000006, 0x20000007, 0x20000002, 0x20000008, 0x20000003,
            0x2800001C, 0x20000009, 0x2000000A, 0x2800001D, 0x20000004, 0x2000000B,
            0x20000005, 0x2000000C, 0x2000000D, 0x18000000
    };

    /**
     * The 4-MV block pattern code table 3 (Table 119).
     */
    static final int[] FOUR_MV_BLOCK_PATTERN_3 = {
            0x10000000, 0x2000000B, 0x2000000C, 0x20000004, 0x2000000D, 0x20000005,
            0x2800001E, 0x28000010, 0x2000000E, 0x2800001F, 0x20000006, 0x28000011,
            0x20000007, 0x28000012, 0x28000013, 0x2000000A
    };

    /**
     * The interlace frame 2-MV block pattern code table 0 (Table 120), indexed by the top bit times 2 plus the
     * bottom bit.
     */
    static final int[] TWO_MV_BLOCK_PATTERN_0 = {
            0x10000002, 0x10000001, 0x10000000, 0x10000003
    };

    /**
     * The interlace frame 2-MV block pattern code table 1 (Table 121), indexed by the top bit times 2 plus the
     * bottom bit.
     */
    static final int[] TWO_MV_BLOCK_PATTERN_1 = {
            0x08000001, 0x10000000, 0x18000002, 0x18000003
    };

    /**
     * The interlace frame 2-MV block pattern code table 2 (Table 122), indexed by the top bit times 2 plus the
     * bottom bit.
     */
    static final int[] TWO_MV_BLOCK_PATTERN_2 = {
            0x18000002, 0x10000000, 0x18000003, 0x08000001
    };

    /**
     * The interlace frame 2-MV block pattern code table 3 (Table 123), indexed by the top bit times 2 plus the
     * bottom bit.
     */
    static final int[] TWO_MV_BLOCK_PATTERN_3 = {
            0x08000001, 0x18000003, 0x18000002, 0x10000000
    };

    /**
     * The interlace field mixed-MV macroblock mode code table 0 (Table 144).
     */
    static final int[] FIELD_MIXED_MV_MBMODE_0 = {
            0x30000010, 0x30000011, 0x10000003, 0x18000003, 0x10000000, 0x20000005,
            0x28000009, 0x10000002
    };

    /**
     * The interlace field mixed-MV macroblock mode code table 1 (Table 145).
     */
    static final int[] FIELD_MIXED_MV_MBMODE_1 = {
            0x28000008, 0x28000009, 0x18000003, 0x18000006, 0x18000007, 0x10000000,
            0x20000005, 0x10000002
    };

    /**
     * The interlace field mixed-MV macroblock mode code table 2 (Table 146).
     */
    static final int[] FIELD_MIXED_MV_MBMODE_2 = {
            0x30000010, 0x30000011, 0x20000005, 0x18000003, 0x10000000, 0x10000003,
            0x28000009, 0x10000002
    };

    /**
     * The interlace field mixed-MV macroblock mode code table 3 (Table 147).
     */
    static final int[] FIELD_MIXED_MV_MBMODE_3 = {
            0x30000038, 0x30000039, 0x2000000F, 0x18000004, 0x18000005, 0x18000006,
            0x2800001D, 0x08000000
    };

    /**
     * The interlace field mixed-MV macroblock mode code table 4 (Table 148).
     */
    static final int[] FIELD_MIXED_MV_MBMODE_4 = {
            0x30000034, 0x30000035, 0x2800001B, 0x2000000E, 0x2000000F, 0x10000002,
            0x2000000C, 0x08000000
    };

    /**
     * The interlace field mixed-MV macroblock mode code table 5 (Table 149).
     */
    static final int[] FIELD_MIXED_MV_MBMODE_5 = {
            0x30000038, 0x30000039, 0x2800001D, 0x18000005, 0x18000006, 0x08000000,
            0x2000000F, 0x18000004
    };

    /**
     * The interlace field mixed-MV macroblock mode code table 6 (Table 150).
     */
    static final int[] FIELD_MIXED_MV_MBMODE_6 = {
            0x28000010, 0x28000011, 0x18000006, 0x18000007, 0x10000000, 0x10000001,
            0x20000009, 0x18000005
    };

    /**
     * The interlace field mixed-MV macroblock mode code table 7 (Table 151).
     */
    static final int[] FIELD_MIXED_MV_MBMODE_7 = {
            0x30000038, 0x30000039, 0x08000000, 0x18000005, 0x18000006, 0x2800001D,
            0x18000004, 0x2000000F
    };

    /**
     * The interlace field 1-MV macroblock mode code table 0 (Table 152).
     */
    static final int[] FIELD_ONE_MV_MBMODE_0 = {
            0x28000000, 0x28000001, 0x08000001, 0x18000001, 0x10000001, 0x20000001
    };

    /**
     * The interlace field 1-MV macroblock mode code table 1 (Table 153).
     */
    static final int[] FIELD_ONE_MV_MBMODE_1 = {
            0x28000000, 0x28000001, 0x08000001, 0x10000001, 0x18000001, 0x20000001
    };

    /**
     * The interlace field 1-MV macroblock mode code table 2 (Table 154).
     */
    static final int[] FIELD_ONE_MV_MBMODE_2 = {
            0x28000010, 0x28000011, 0x10000003, 0x08000000, 0x20000009, 0x18000005
    };

    /**
     * The interlace field 1-MV macroblock mode code table 3 (Table 155).
     */
    static final int[] FIELD_ONE_MV_MBMODE_3 = {
            0x28000014, 0x28000015, 0x10000003, 0x2000000B, 0x08000000, 0x18000004
    };

    /**
     * The interlace field 1-MV macroblock mode code table 4 (Table 156).
     */
    static final int[] FIELD_ONE_MV_MBMODE_4 = {
            0x20000004, 0x20000005, 0x10000002, 0x18000003, 0x10000003, 0x10000000
    };

    /**
     * The interlace field 1-MV macroblock mode code table 5 (Table 157).
     */
    static final int[] FIELD_ONE_MV_MBMODE_5 = {
            0x20000004, 0x20000005, 0x18000003, 0x10000002, 0x10000000, 0x10000003
    };

    /**
     * The interlace field 1-MV macroblock mode code table 6 (Table 158).
     */
    static final int[] FIELD_ONE_MV_MBMODE_6 = {
            0x28000000, 0x28000001, 0x18000001, 0x20000001, 0x08000001, 0x10000001
    };

    /**
     * The interlace field 1-MV macroblock mode code table 7 (Table 159).
     */
    static final int[] FIELD_ONE_MV_MBMODE_7 = {
            0x28000010, 0x28000011, 0x20000009, 0x18000005, 0x10000003, 0x08000000
    };

    /**
     * The interlace frame 4-MV macroblock mode code table 0 (Table 160), the symbols in the row order of the
     * table.
     */
    static final int[] FRAME_FOUR_MV_MBMODE_0 = {
            0x28000016, 0x28000011, 0x10000000, 0x3000002F, 0x30000020, 0x2000000A,
            0x10000001, 0x10000003, 0x38000043, 0x40000085, 0x40000084, 0x3800005C,
            0x28000013, 0x3800005D, 0x28000012
    };

    /**
     * The interlace frame 4-MV macroblock mode code table 1 (Table 161), the symbols in the row order of the
     * table.
     */
    static final int[] FRAME_FOUR_MV_MBMODE_1 = {
            0x18000003, 0x3000002D, 0x18000000, 0x18000007, 0x28000017, 0x18000006,
            0x18000001, 0x18000002, 0x2000000A, 0x30000027, 0x3000002C, 0x20000008,
            0x28000012, 0x3800004D, 0x3800004C
    };

    /**
     * The interlace frame 4-MV macroblock mode code table 2 (Table 162), the symbols in the row order of the
     * table.
     */
    static final int[] FRAME_FOUR_MV_MBMODE_2 = {
            0x2000000F, 0x18000006, 0x2800001C, 0x28000009, 0x38000029, 0x20000006,
            0x10000002, 0x2800000F, 0x2800000E, 0x28000008, 0x38000028, 0x2800001D,
            0x10000000, 0x30000015, 0x2800000B
    };

    /**
     * The interlace frame 4-MV macroblock mode code table 3 (Table 163), the symbols in the row order of the
     * table.
     */
    static final int[] FRAME_FOUR_MV_MBMODE_3 = {
            0x20000007, 0x480000C6, 0x08000001, 0x18000002, 0x480000C1, 0x2800000D,
            0x30000019, 0x10000000, 0x40000061, 0x6000063F, 0x40000062, 0x5000018E,
            0x5800031E, 0x480000C0, 0x6000063E
    };

    /**
     * The interlace frame non 4-MV macroblock mode code table 0 (Table 164), the symbols in the row order of the
     * table.
     */
    static final int[] FRAME_MBMODE_0 = {
            0x20000009, 0x28000016, 0x10000000, 0x28000011, 0x28000010, 0x2000000A,
            0x10000001, 0x10000003, 0x28000017
    };

    /**
     * The interlace frame non 4-MV macroblock mode code table 1 (Table 165), the symbols in the row order of the
     * table.
     */
    static final int[] FRAME_MBMODE_1 = {
            0x18000007, 0x20000000, 0x30000005, 0x10000002, 0x18000001, 0x10000001,
            0x18000006, 0x28000003, 0x30000004
    };

    /**
     * The interlace frame non 4-MV macroblock mode code table 2 (Table 166), the symbols in the row order of the
     * table.
     */
    static final int[] FRAME_MBMODE_2 = {
            0x10000001, 0x10000000, 0x2000000A, 0x28000017, 0x3000002C, 0x20000008,
            0x10000003, 0x20000009, 0x3000002D
    };

    /**
     * The interlace frame non 4-MV macroblock mode code table 3 (Table 167), the symbols in the row order of the
     * table.
     */
    static final int[] FRAME_MBMODE_3 = {
            0x20000007, 0x40000061, 0x08000001, 0x18000002, 0x38000031, 0x2800000D,
            0x30000019, 0x10000000, 0x40000060
    };

    /**
     * The intra normal scan (Table 233). Each entry is a position in the block, its row times the block width plus
     * its column.
     */
    static final int[] INTRA_NORMAL_SCAN = {
            0, 8, 1, 2, 9, 16, 24, 17, 10, 3, 4, 11, 18, 25, 32, 40,
            33, 48, 26, 19, 12, 5, 6, 13, 20, 27, 34, 41, 56, 49, 57, 42,
            35, 28, 21, 14, 7, 15, 22, 29, 36, 43, 50, 58, 51, 59, 44, 37,
            30, 23, 31, 38, 45, 52, 60, 53, 61, 46, 39, 47, 54, 62, 55, 63
    };

    /**
     * The intra horizontal scan (Table 234). Each entry is a position in the block, its row times the block width
     * plus its column.
     */
    static final int[] INTRA_HORIZONTAL_SCAN = {
            0, 1, 8, 2, 3, 9, 16, 24, 17, 10, 4, 5, 11, 18, 25, 32,
            40, 48, 33, 26, 19, 12, 6, 7, 13, 20, 27, 34, 41, 56, 49, 57,
            42, 35, 28, 21, 14, 15, 22, 29, 36, 43, 50, 58, 51, 44, 37, 30,
            23, 31, 38, 45, 52, 59, 60, 53, 46, 39, 47, 54, 61, 62, 55, 63
    };

    /**
     * The intra vertical scan (Table 235). Each entry is a position in the block, its row times the block width
     * plus its column.
     */
    static final int[] INTRA_VERTICAL_SCAN = {
            0, 8, 16, 1, 24, 32, 40, 9, 2, 3, 10, 17, 25, 48, 56, 41,
            33, 26, 18, 11, 4, 5, 12, 19, 27, 34, 49, 57, 50, 42, 35, 28,
            20, 13, 6, 7, 14, 21, 29, 36, 43, 51, 58, 59, 52, 44, 37, 30,
            22, 15, 23, 31, 38, 45, 60, 53, 46, 39, 47, 54, 61, 62, 55, 63
    };

    /**
     * The inter 8x8 scan of the simple and main profiles and of advanced profile progressive pictures (Table 236).
     * Each entry is a position in the block, its row times the block width plus its column.
     */
    static final int[] INTER_8X8_SCAN = {
            0, 8, 1, 2, 9, 16, 24, 17, 10, 3, 4, 11, 18, 25, 32, 40,
            48, 56, 41, 33, 26, 19, 12, 5, 6, 13, 20, 27, 34, 49, 57, 58,
            50, 42, 35, 28, 21, 14, 7, 15, 22, 29, 36, 43, 51, 59, 60, 52,
            44, 37, 30, 23, 31, 38, 45, 53, 61, 62, 54, 46, 39, 47, 55, 63
    };

    /**
     * The inter 8x4 scan of the simple and main profiles (Table 237). Each entry is a position in the block, its
     * row times the block width plus its column.
     */
    static final int[] SIMPLE_8X4_SCAN = {
            0, 1, 2, 8, 3, 9, 10, 16, 4, 11, 17, 24, 18, 12, 5, 19,
            25, 13, 20, 26, 27, 6, 21, 28, 14, 22, 29, 7, 30, 15, 23, 31
    };

    /**
     * The inter 4x8 scan of the simple and main profiles (Table 238). Each entry is a position in the block, its
     * row times the block width plus its column.
     */
    static final int[] SIMPLE_4X8_SCAN = {
            0, 4, 1, 8, 5, 12, 9, 2, 16, 6, 13, 20, 10, 24, 17, 14,
            28, 21, 18, 3, 25, 29, 7, 22, 11, 26, 15, 30, 19, 23, 27, 31
    };

    /**
     * The inter 4x4 scan of the simple and main profiles and of advanced profile progressive pictures (Table 239).
     * Each entry is a position in the block, its row times the block width plus its column.
     */
    static final int[] INTER_4X4_SCAN = {
            0, 4, 8, 1, 5, 12, 9, 2, 6, 10, 13, 3, 7, 14, 11, 15
    };

    /**
     * The inter 8x4 scan of advanced profile progressive pictures (Table 240). Each entry is a position in the
     * block, its row times the block width plus its column.
     */
    static final int[] PROGRESSIVE_8X4_SCAN = {
            0, 8, 1, 16, 2, 9, 10, 3, 24, 17, 4, 11, 18, 12, 5, 19,
            25, 13, 20, 26, 27, 6, 21, 28, 14, 22, 29, 7, 30, 15, 23, 31
    };

    /**
     * The inter 4x8 scan of advanced profile progressive pictures (Table 241). Each entry is a position in the
     * block, its row times the block width plus its column.
     */
    static final int[] PROGRESSIVE_4X8_SCAN = {
            0, 1, 4, 2, 5, 8, 9, 12, 6, 16, 13, 10, 20, 3, 17, 14,
            24, 7, 28, 21, 18, 25, 29, 22, 11, 26, 15, 30, 19, 23, 27, 31
    };

    /**
     * The inter 8x8 scan of advanced profile interlace pictures, also the intra scan of interlace frame pictures
     * (Table 242). Each entry is a position in the block, its row times the block width plus its column.
     */
    static final int[] INTERLACE_8X8_SCAN = {
            0, 8, 1, 16, 24, 9, 2, 32, 40, 48, 56, 17, 10, 3, 25, 18,
            11, 4, 33, 41, 49, 57, 26, 34, 42, 50, 58, 19, 12, 5, 27, 20,
            13, 6, 35, 28, 21, 14, 7, 15, 22, 29, 36, 43, 51, 59, 60, 52,
            44, 37, 30, 23, 31, 38, 45, 53, 61, 62, 54, 46, 39, 47, 55, 63
    };

    /**
     * The inter 8x4 scan of advanced profile interlace pictures (Table 243). Each entry is a position in the
     * block, its row times the block width plus its column.
     */
    static final int[] INTERLACE_8X4_SCAN = {
            0, 8, 16, 24, 1, 9, 2, 17, 25, 10, 3, 18, 26, 4, 11, 19,
            12, 5, 13, 20, 27, 6, 21, 28, 14, 22, 29, 7, 30, 15, 23, 31
    };

    /**
     * The inter 4x8 scan of advanced profile interlace pictures (Table 244). Each entry is a position in the
     * block, its row times the block width plus its column.
     */
    static final int[] INTERLACE_4X8_SCAN = {
            0, 1, 2, 4, 8, 5, 12, 9, 6, 3, 16, 20, 24, 28, 13, 10,
            17, 14, 21, 18, 25, 29, 7, 22, 11, 26, 15, 30, 19, 23, 27, 31
    };

    /**
     * The inter 4x4 scan of advanced profile interlace pictures (Table 245). Each entry is a position in the
     * block, its row times the block width plus its column.
     */
    static final int[] INTERLACE_4X4_SCAN = {
            0, 4, 8, 12, 1, 5, 9, 2, 13, 6, 10, 3, 14, 7, 11, 15
    };
}

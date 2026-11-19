/* Ports musl 1.2.5 (src/math/exp.c, exp_data.c, log.c, log_data.c, pow.c, pow_data.c, Copyright (c) 2018, Arm
   Limited, MIT License; src/math/log10.c, sin.c, __sin.c, __cos.c, __rem_pio2.c, Copyright (C) 1993 by Sun
   Microsystems, Inc., with changes by the FreeBSD and musl authors). Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal;

/**
 * Computes the double-precision elementary functions with the algorithms and tables of musl's
 * {@code libm}, so the results match a reference built against musl bit for bit.
 *
 * <p>The functions round to nearest like their C counterparts and handle the special values
 * (zeros, infinities, NaN, subnormals) the same way; floating-point exceptions are not raised.
 *
 * @implNote This implementation ports {@code exp}, {@code log} and {@code pow} (the Arm
 * optimized routines with 128-entry tables), {@code log10}, and {@code sin} with its kernels
 * and the argument reduction for {@code |x| < 2^20 pi / 2}, for the evaluation without fused
 * multiply-add that a WebAssembly build uses.
 */
public final class DoubleMath {
    private static final int TABLE_BITS = 7;
    private static final int N = 1 << TABLE_BITS;

    private static final double INV_LN2_N = 0x1.71547652b82fep0 * N;
    private static final double NEG_LN2_HI_N = -0x1.62e42fefa0000p-8;
    private static final double NEG_LN2_LO_N = -0x1.cf79abc9e3b3ap-47;
    private static final double SHIFT = 0x1.8p52;
    private static final double[] EXP_POLY = {
            0x1.ffffffffffdbdp-2,
            0x1.555555555543cp-3,
            0x1.55555cf172b91p-5,
            0x1.1111167a4d017p-7
    };
    private static final long[] EXP_TAB = {
            0x0L, 0x3ff0000000000000L,
            0x3c9b3b4f1a88bf6eL, 0x3feff63da9fb3335L,
            0xbc7160139cd8dc5dL, 0x3fefec9a3e778061L,
            0xbc905e7a108766d1L, 0x3fefe315e86e7f85L,
            0x3c8cd2523567f613L, 0x3fefd9b0d3158574L,
            0xbc8bce8023f98efaL, 0x3fefd06b29ddf6deL,
            0x3c60f74e61e6c861L, 0x3fefc74518759bc8L,
            0x3c90a3e45b33d399L, 0x3fefbe3ecac6f383L,
            0x3c979aa65d837b6dL, 0x3fefb5586cf9890fL,
            0x3c8eb51a92fdeffcL, 0x3fefac922b7247f7L,
            0x3c3ebe3d702f9cd1L, 0x3fefa3ec32d3d1a2L,
            0xbc6a033489906e0bL, 0x3fef9b66affed31bL,
            0xbc9556522a2fbd0eL, 0x3fef9301d0125b51L,
            0xbc5080ef8c4eea55L, 0x3fef8abdc06c31ccL,
            0xbc91c923b9d5f416L, 0x3fef829aaea92de0L,
            0x3c80d3e3e95c55afL, 0x3fef7a98c8a58e51L,
            0xbc801b15eaa59348L, 0x3fef72b83c7d517bL,
            0xbc8f1ff055de323dL, 0x3fef6af9388c8deaL,
            0x3c8b898c3f1353bfL, 0x3fef635beb6fcb75L,
            0xbc96d99c7611eb26L, 0x3fef5be084045cd4L,
            0x3c9aecf73e3a2f60L, 0x3fef54873168b9aaL,
            0xbc8fe782cb86389dL, 0x3fef4d5022fcd91dL,
            0x3c8a6f4144a6c38dL, 0x3fef463b88628cd6L,
            0x3c807a05b0e4047dL, 0x3fef3f49917ddc96L,
            0x3c968efde3a8a894L, 0x3fef387a6e756238L,
            0x3c875e18f274487dL, 0x3fef31ce4fb2a63fL,
            0x3c80472b981fe7f2L, 0x3fef2b4565e27cddL,
            0xbc96b87b3f71085eL, 0x3fef24dfe1f56381L,
            0x3c82f7e16d09ab31L, 0x3fef1e9df51fdee1L,
            0xbc3d219b1a6fbffaL, 0x3fef187fd0dad990L,
            0x3c8b3782720c0ab4L, 0x3fef1285a6e4030bL,
            0x3c6e149289cecb8fL, 0x3fef0cafa93e2f56L,
            0x3c834d754db0abb6L, 0x3fef06fe0a31b715L,
            0x3c864201e2ac744cL, 0x3fef0170fc4cd831L,
            0x3c8fdd395dd3f84aL, 0x3feefc08b26416ffL,
            0xbc86a3803b8e5b04L, 0x3feef6c55f929ff1L,
            0xbc924aedcc4b5068L, 0x3feef1a7373aa9cbL,
            0xbc9907f81b512d8eL, 0x3feeecae6d05d866L,
            0xbc71d1e83e9436d2L, 0x3feee7db34e59ff7L,
            0xbc991919b3ce1b15L, 0x3feee32dc313a8e5L,
            0x3c859f48a72a4c6dL, 0x3feedea64c123422L,
            0xbc9312607a28698aL, 0x3feeda4504ac801cL,
            0xbc58a78f4817895bL, 0x3feed60a21f72e2aL,
            0xbc7c2c9b67499a1bL, 0x3feed1f5d950a897L,
            0x3c4363ed60c2ac11L, 0x3feece086061892dL,
            0x3c9666093b0664efL, 0x3feeca41ed1d0057L,
            0x3c6ecce1daa10379L, 0x3feec6a2b5c13cd0L,
            0x3c93ff8e3f0f1230L, 0x3feec32af0d7d3deL,
            0x3c7690cebb7aafb0L, 0x3feebfdad5362a27L,
            0x3c931dbdeb54e077L, 0x3feebcb299fddd0dL,
            0xbc8f94340071a38eL, 0x3feeb9b2769d2ca7L,
            0xbc87deccdc93a349L, 0x3feeb6daa2cf6642L,
            0xbc78dec6bd0f385fL, 0x3feeb42b569d4f82L,
            0xbc861246ec7b5cf6L, 0x3feeb1a4ca5d920fL,
            0x3c93350518fdd78eL, 0x3feeaf4736b527daL,
            0x3c7b98b72f8a9b05L, 0x3feead12d497c7fdL,
            0x3c9063e1e21c5409L, 0x3feeab07dd485429L,
            0x3c34c7855019c6eaL, 0x3feea9268a5946b7L,
            0x3c9432e62b64c035L, 0x3feea76f15ad2148L,
            0xbc8ce44a6199769fL, 0x3feea5e1b976dc09L,
            0xbc8c33c53bef4da8L, 0x3feea47eb03a5585L,
            0xbc845378892be9aeL, 0x3feea34634ccc320L,
            0xbc93cedd78565858L, 0x3feea23882552225L,
            0x3c5710aa807e1964L, 0x3feea155d44ca973L,
            0xbc93b3efbf5e2228L, 0x3feea09e667f3bcdL,
            0xbc6a12ad8734b982L, 0x3feea012750bdabfL,
            0xbc6367efb86da9eeL, 0x3fee9fb23c651a2fL,
            0xbc80dc3d54e08851L, 0x3fee9f7df9519484L,
            0xbc781f647e5a3ecfL, 0x3fee9f75e8ec5f74L,
            0xbc86ee4ac08b7db0L, 0x3fee9f9a48a58174L,
            0xbc8619321e55e68aL, 0x3fee9feb564267c9L,
            0x3c909ccb5e09d4d3L, 0x3feea0694fde5d3fL,
            0xbc7b32dcb94da51dL, 0x3feea11473eb0187L,
            0x3c94ecfd5467c06bL, 0x3feea1ed0130c132L,
            0x3c65ebe1abd66c55L, 0x3feea2f336cf4e62L,
            0xbc88a1c52fb3cf42L, 0x3feea427543e1a12L,
            0xbc9369b6f13b3734L, 0x3feea589994cce13L,
            0xbc805e843a19ff1eL, 0x3feea71a4623c7adL,
            0xbc94d450d872576eL, 0x3feea8d99b4492edL,
            0x3c90ad675b0e8a00L, 0x3feeaac7d98a6699L,
            0x3c8db72fc1f0eab4L, 0x3feeace5422aa0dbL,
            0xbc65b6609cc5e7ffL, 0x3feeaf3216b5448cL,
            0x3c7bf68359f35f44L, 0x3feeb1ae99157736L,
            0xbc93091fa71e3d83L, 0x3feeb45b0b91ffc6L,
            0xbc5da9b88b6c1e29L, 0x3feeb737b0cdc5e5L,
            0xbc6c23f97c90b959L, 0x3feeba44cbc8520fL,
            0xbc92434322f4f9aaL, 0x3feebd829fde4e50L,
            0xbc85ca6cd7668e4bL, 0x3feec0f170ca07baL,
            0x3c71affc2b91ce27L, 0x3feec49182a3f090L,
            0x3c6dd235e10a73bbL, 0x3feec86319e32323L,
            0xbc87c50422622263L, 0x3feecc667b5de565L,
            0x3c8b1c86e3e231d5L, 0x3feed09bec4a2d33L,
            0xbc91bbd1d3bcbb15L, 0x3feed503b23e255dL,
            0x3c90cc319cee31d2L, 0x3feed99e1330b358L,
            0x3c8469846e735ab3L, 0x3feede6b5579fdbfL,
            0xbc82dfcd978e9db4L, 0x3feee36bbfd3f37aL,
            0x3c8c1a7792cb3387L, 0x3feee89f995ad3adL,
            0xbc907b8f4ad1d9faL, 0x3feeee07298db666L,
            0xbc55c3d956dcaebaL, 0x3feef3a2b84f15fbL,
            0xbc90a40e3da6f640L, 0x3feef9728de5593aL,
            0xbc68d6f438ad9334L, 0x3feeff76f2fb5e47L,
            0xbc91eee26b588a35L, 0x3fef05b030a1064aL,
            0x3c74ffd70a5fddcdL, 0x3fef0c1e904bc1d2L,
            0xbc91bdfbfa9298acL, 0x3fef12c25bd71e09L,
            0x3c736eae30af0cb3L, 0x3fef199bdd85529cL,
            0x3c8ee3325c9ffd94L, 0x3fef20ab5fffd07aL,
            0x3c84e08fd10959acL, 0x3fef27f12e57d14bL,
            0x3c63cdaf384e1a67L, 0x3fef2f6d9406e7b5L,
            0x3c676b2c6c921968L, 0x3fef3720dcef9069L,
            0xbc808a1883ccb5d2L, 0x3fef3f0b555dc3faL,
            0xbc8fad5d3ffffa6fL, 0x3fef472d4a07897cL,
            0xbc900dae3875a949L, 0x3fef4f87080d89f2L,
            0x3c74a385a63d07a7L, 0x3fef5818dcfba487L,
            0xbc82919e2040220fL, 0x3fef60e316c98398L,
            0x3c8e5a50d5c192acL, 0x3fef69e603db3285L,
            0x3c843a59ac016b4bL, 0x3fef7321f301b460L,
            0xbc82d52107b43e1fL, 0x3fef7c97337b9b5fL,
            0xbc892ab93b470dc9L, 0x3fef864614f5a129L,
            0x3c74b604603a88d3L, 0x3fef902ee78b3ff6L,
            0x3c83c5ec519d7271L, 0x3fef9a51fbc74c83L,
            0xbc8ff7128fd391f0L, 0x3fefa4afa2a490daL,
            0xbc8dae98e223747dL, 0x3fefaf482d8e67f1L,
            0x3c8ec3bc41aa2008L, 0x3fefba1bee615a27L,
            0x3c842b94c3a9eb32L, 0x3fefc52b376bba97L,
            0x3c8a64a931d185eeL, 0x3fefd0765b6e4540L,
            0xbc8e37bae43be3edL, 0x3fefdbfdad9cbe14L,
            0x3c77893b4d91cd9dL, 0x3fefe7c1819e90d8L,
            0x3c5305c14160cc89L, 0x3feff3c22b8f71f1L
    };

    private static final double LN2_HI = 0x1.62e42fefa3800p-1;
    private static final double LN2_LO = 0x1.ef35793c76730p-45;
    private static final double[] LOG_POLY = {
            -0x1.0000000000001p-1,
            0x1.555555551305bp-2,
            -0x1.fffffffeb459p-3,
            0x1.999b324f10111p-3,
            -0x1.55575e506c89fp-3
    };
    private static final double[] LOG_POLY1 = {
            -0x1p-1,
            0x1.5555555555577p-2,
            -0x1.ffffffffffdcbp-3,
            0x1.999999995dd0cp-3,
            -0x1.55555556745a7p-3,
            0x1.24924a344de3p-3,
            -0x1.fffffa4423d65p-4,
            0x1.c7184282ad6cap-4,
            -0x1.999eb43b068ffp-4,
            0x1.78182f7afd085p-4,
            -0x1.5521375d145cdp-4
    };
    private static final double[] LOG_INVC = {
            0x1.734f0c3e0de9fp+0, 0x1.713786a2ce91fp+0,
            0x1.6f26008fab5a0p+0, 0x1.6d1a61f138c7dp+0,
            0x1.6b1490bc5b4d1p+0, 0x1.69147332f0cbap+0,
            0x1.6719f18224223p+0, 0x1.6524f99a51ed9p+0,
            0x1.63356aa8f24c4p+0, 0x1.614b36b9ddc14p+0,
            0x1.5f66452c65c4cp+0, 0x1.5d867b5912c4fp+0,
            0x1.5babccb5b90dep+0, 0x1.59d61f2d91a78p+0,
            0x1.5805612465687p+0, 0x1.56397cee76bd3p+0,
            0x1.54725e2a77f93p+0, 0x1.52aff42064583p+0,
            0x1.50f22dbb2bddfp+0, 0x1.4f38f4734ded7p+0,
            0x1.4d843cfde2840p+0, 0x1.4bd3ec078a3c8p+0,
            0x1.4a27fc3e0258ap+0, 0x1.4880524d48434p+0,
            0x1.46dce1b192d0bp+0, 0x1.453d9d3391854p+0,
            0x1.43a2744b4845ap+0, 0x1.420b54115f8fbp+0,
            0x1.40782da3ef4b1p+0, 0x1.3ee8f5d57fe8fp+0,
            0x1.3d5d9a00b4ce9p+0, 0x1.3bd60c010c12bp+0,
            0x1.3a5242b75dab8p+0, 0x1.38d22cd9fd002p+0,
            0x1.3755bc5847a1cp+0, 0x1.35dce49ad36e2p+0,
            0x1.34679984dd440p+0, 0x1.32f5cceffcb24p+0,
            0x1.3187775a10d49p+0, 0x1.301c8373e3990p+0,
            0x1.2eb4ebb95f841p+0, 0x1.2d50a0219a9d1p+0,
            0x1.2bef9a8b7fd2ap+0, 0x1.2a91c7a0c1babp+0,
            0x1.293726014b530p+0, 0x1.27dfa5757a1f5p+0,
            0x1.268b39b1d3bbfp+0, 0x1.2539d838ff5bdp+0,
            0x1.23eb7aac9083bp+0, 0x1.22a012ba940b6p+0,
            0x1.2157996cc4132p+0, 0x1.201201dd2fc9bp+0,
            0x1.1ecf4494d480bp+0, 0x1.1d8f5528f6569p+0,
            0x1.1c52311577e7cp+0, 0x1.1b17c74cb26e9p+0,
            0x1.19e010c2c1ab6p+0, 0x1.18ab07bb670bdp+0,
            0x1.1778a25efbcb6p+0, 0x1.1648d354c31dap+0,
            0x1.151b990275fddp+0, 0x1.13f0ea432d24cp+0,
            0x1.12c8b7210f9dap+0, 0x1.11a3028ecb531p+0,
            0x1.107fbda8434afp+0, 0x1.0f5ee0f4e6bb3p+0,
            0x1.0e4065d2a9fcep+0, 0x1.0d244632ca521p+0,
            0x1.0c0a77ce2981ap+0, 0x1.0af2f83c636d1p+0,
            0x1.09ddb98a01339p+0, 0x1.08cabaf52e7dfp+0,
            0x1.07b9f2f4e28fbp+0, 0x1.06ab58c358f19p+0,
            0x1.059eea5ecf92cp+0, 0x1.04949cdd12c90p+0,
            0x1.038c6c6f0ada9p+0, 0x1.02865137932a9p+0,
            0x1.0182427ea7348p+0, 0x1.008040614b195p+0,
            0x1.fe01ff726fa1ap-1, 0x1.fa11cc261ea74p-1,
            0x1.f6310b081992ep-1, 0x1.f25f63ceeadcdp-1,
            0x1.ee9c8039113e7p-1, 0x1.eae8078cbb1abp-1,
            0x1.e741aa29d0c9bp-1, 0x1.e3a91830a99b5p-1,
            0x1.e01e009609a56p-1, 0x1.dca01e577bb98p-1,
            0x1.d92f20b7c9103p-1, 0x1.d5cac66fb5ccep-1,
            0x1.d272caa5ede9dp-1, 0x1.cf26e3e6b2ccdp-1,
            0x1.cbe6da2a77902p-1, 0x1.c8b266d37086dp-1,
            0x1.c5894bd5d5804p-1, 0x1.c26b533bb9f8cp-1,
            0x1.bf583eeece73fp-1, 0x1.bc4fd75db96c1p-1,
            0x1.b951e0c864a28p-1, 0x1.b65e2c5ef3e2cp-1,
            0x1.b374867c9888bp-1, 0x1.b094b211d304ap-1,
            0x1.adbe885f2ef7ep-1, 0x1.aaf1d31603da2p-1,
            0x1.a82e63fd358a7p-1, 0x1.a5740ef09738bp-1,
            0x1.a2c2a90ab4b27p-1, 0x1.a01a01393f2d1p-1,
            0x1.9d79f24db3c1bp-1, 0x1.9ae2505c7b190p-1,
            0x1.9852ef297ce2fp-1, 0x1.95cbaeea44b75p-1,
            0x1.934c69de74838p-1, 0x1.90d4f2f6752e6p-1,
            0x1.8e6528effd79dp-1, 0x1.8bfce9fcc007cp-1,
            0x1.899c0dabec30ep-1, 0x1.87427aa2317fbp-1,
            0x1.84f00acb39a08p-1, 0x1.82a49e8653e55p-1,
            0x1.8060195f40260p-1, 0x1.7e22563e0a329p-1,
            0x1.7beb377dcb5adp-1, 0x1.79baa679725c2p-1,
            0x1.77907f2170657p-1, 0x1.756cadbd6130cp-1
    };
    private static final double[] LOG_LOGC = {
            -0x1.7cc7f79e69000p-2, -0x1.76feec20d0000p-2,
            -0x1.713e31351e000p-2, -0x1.6b85b38287800p-2,
            -0x1.65d5590807800p-2, -0x1.602d076180000p-2,
            -0x1.5a8ca86909000p-2, -0x1.54f4356035000p-2,
            -0x1.4f637c36b4000p-2, -0x1.49da7fda85000p-2,
            -0x1.445923989a800p-2, -0x1.3edf439b0b800p-2,
            -0x1.396ce448f7000p-2, -0x1.3401e17bda000p-2,
            -0x1.2e9e2ef468000p-2, -0x1.2941b3830e000p-2,
            -0x1.23ec58cda8800p-2, -0x1.1e9e129279000p-2,
            -0x1.1956d2b48f800p-2, -0x1.141679ab9f800p-2,
            -0x1.0edd094ef9800p-2, -0x1.09aa518db1000p-2,
            -0x1.047e65263b800p-2, -0x1.feb224586f000p-3,
            -0x1.f474a7517b000p-3, -0x1.ea4443d103000p-3,
            -0x1.e020d44e9b000p-3, -0x1.d60a22977f000p-3,
            -0x1.cc00104959000p-3, -0x1.c202956891000p-3,
            -0x1.b81178d811000p-3, -0x1.ae2c9ccd3d000p-3,
            -0x1.a45402e129000p-3, -0x1.9a877681df000p-3,
            -0x1.90c6d69483000p-3, -0x1.87120a645c000p-3,
            -0x1.7d68fb4143000p-3, -0x1.73cb83c627000p-3,
            -0x1.6a39a9b376000p-3, -0x1.60b3154b7a000p-3,
            -0x1.5737d76243000p-3, -0x1.4dc7b8fc23000p-3,
            -0x1.4462c51d20000p-3, -0x1.3b08abc830000p-3,
            -0x1.31b996b490000p-3, -0x1.2875490a44000p-3,
            -0x1.1f3b9f879a000p-3, -0x1.160c8252ca000p-3,
            -0x1.0ce7f57f72000p-3, -0x1.03cdc49fea000p-3,
            -0x1.f57bdbc4b8000p-4, -0x1.e370896404000p-4,
            -0x1.d17983ef94000p-4, -0x1.bf9674ed8a000p-4,
            -0x1.adc79202f6000p-4, -0x1.9c0c3e7288000p-4,
            -0x1.8a646b372c000p-4, -0x1.78d01b3ac0000p-4,
            -0x1.674f145380000p-4, -0x1.55e0e6d878000p-4,
            -0x1.4485cdea1e000p-4, -0x1.333d94d6aa000p-4,
            -0x1.22079f8c56000p-4, -0x1.10e4698622000p-4,
            -0x1.ffa6c6ad20000p-5, -0x1.dda8d4a774000p-5,
            -0x1.bbcece4850000p-5, -0x1.9a1894012c000p-5,
            -0x1.788583302c000p-5, -0x1.5715e67d68000p-5,
            -0x1.35c8a49658000p-5, -0x1.149e364154000p-5,
            -0x1.e72c082eb8000p-6, -0x1.a55f152528000p-6,
            -0x1.63d62cf818000p-6, -0x1.228fb8caa0000p-6,
            -0x1.c317b20f90000p-7, -0x1.419355daa0000p-7,
            -0x1.81203c2ec0000p-8, -0x1.0040979240000p-9,
            0x1.feff384900000p-9, 0x1.7dc41353d0000p-7,
            0x1.3cea3c4c28000p-6, 0x1.b9fc114890000p-6,
            0x1.1b0d8ce110000p-5, 0x1.58a5bd001c000p-5,
            0x1.95c8340d88000p-5, 0x1.d276aef578000p-5,
            0x1.07598e598c000p-4, 0x1.253f5e30d2000p-4,
            0x1.42edd8b380000p-4, 0x1.606598757c000p-4,
            0x1.7da76356a0000p-4, 0x1.9ab434e1c6000p-4,
            0x1.b78c7bb0d6000p-4, 0x1.d431332e72000p-4,
            0x1.f0a3171de6000p-4, 0x1.067152b914000p-3,
            0x1.147858292b000p-3, 0x1.2266ecdca3000p-3,
            0x1.303d7a6c55000p-3, 0x1.3dfc33c331000p-3,
            0x1.4ba366b7a8000p-3, 0x1.5933928d1f000p-3,
            0x1.66acd2418f000p-3, 0x1.740f8ec669000p-3,
            0x1.815c0f51af000p-3, 0x1.8e92954f68000p-3,
            0x1.9bb3602f84000p-3, 0x1.a8bed1c2c0000p-3,
            0x1.b5b515c01d000p-3, 0x1.c2967ccbcc000p-3,
            0x1.cf635d5486000p-3, 0x1.dc1bd3446c000p-3,
            0x1.e8c01b8cfe000p-3, 0x1.f5509c0179000p-3,
            0x1.00e6c121fb800p-2, 0x1.071b80e93d000p-2,
            0x1.0d46b9e867000p-2, 0x1.13687334bd000p-2,
            0x1.1980d67234800p-2, 0x1.1f8ffe0cc8000p-2,
            0x1.2595fd7636800p-2, 0x1.2b9300914a800p-2,
            0x1.3187210436000p-2, 0x1.377266dec1800p-2,
            0x1.3d54ffbaf3000p-2, 0x1.432eee32fe000p-2
    };
    private static final double[] LOG_CHI = {
            0x1.61000014fb66bp-1, 0x1.63000034db495p-1,
            0x1.650000d94d478p-1, 0x1.67000074e6fadp-1,
            0x1.68ffffedf0faep-1, 0x1.6b0000763c5bcp-1,
            0x1.6d0001e5cc1f6p-1, 0x1.6efffeb05f63ep-1,
            0x1.710000e86978p-1, 0x1.72ffffc67e912p-1,
            0x1.74fffdf81116ap-1, 0x1.770000f679c9p-1,
            0x1.78ffffa7ec835p-1, 0x1.7affffe20c2e6p-1,
            0x1.7cfffed3fc9p-1, 0x1.7efffe9261a76p-1,
            0x1.81000049ca3e8p-1, 0x1.8300017932c8fp-1,
            0x1.850000633739cp-1, 0x1.87000204289c6p-1,
            0x1.88fffebf57904p-1, 0x1.8b00022bc04dfp-1,
            0x1.8cfffe50c1b8ap-1, 0x1.8effffc918e43p-1,
            0x1.910001efa5fc7p-1, 0x1.9300013467bb9p-1,
            0x1.94fffe6ee076fp-1, 0x1.96fffde3c12d1p-1,
            0x1.98ffff4458a0dp-1, 0x1.9afffdd982e3ep-1,
            0x1.9cfffed49fb66p-1, 0x1.9f00020f19c51p-1,
            0x1.a10001145b006p-1, 0x1.a300007bbf6fap-1,
            0x1.a500010971d79p-1, 0x1.a70001df52e48p-1,
            0x1.a90001c593352p-1, 0x1.ab0002a4f3e4bp-1,
            0x1.acfffd7ae1ed1p-1, 0x1.aefffee510478p-1,
            0x1.b0fffdb650d5bp-1, 0x1.b2ffffeaaca57p-1,
            0x1.b4fffd995badcp-1, 0x1.b7000249e659cp-1,
            0x1.b8ffff987164p-1, 0x1.bafffd204cb4fp-1,
            0x1.bcfffd2415c45p-1, 0x1.beffff86309dfp-1,
            0x1.c0fffe1b57653p-1, 0x1.c2ffff1fa57e3p-1,
            0x1.c4fffdcbfe424p-1, 0x1.c6fffed54b9f7p-1,
            0x1.c8fffeb998fd5p-1, 0x1.cb0002125219ap-1,
            0x1.ccfffdd94469cp-1, 0x1.cefffeafdc476p-1,
            0x1.d1000169af82bp-1, 0x1.d30000d0ff71dp-1,
            0x1.d4fffea790fc4p-1, 0x1.d70002edc87e5p-1,
            0x1.d900021dc82aap-1, 0x1.dafffd86b0283p-1,
            0x1.dd000296c4739p-1, 0x1.defffe54490f5p-1,
            0x1.e0fffcdabf694p-1, 0x1.e2fffdb52c8ddp-1,
            0x1.e4ffff24216efp-1, 0x1.e6fffe88a5e11p-1,
            0x1.e9000119eff0dp-1, 0x1.eafffdfa51744p-1,
            0x1.ed0001a127fa1p-1, 0x1.ef00007babcc4p-1,
            0x1.f0ffff57a8d02p-1, 0x1.f30001ee58ac7p-1,
            0x1.f4ffff5823494p-1, 0x1.f6ffffca94c6bp-1,
            0x1.f8fffe1f9c441p-1, 0x1.fafffd2e0e37ep-1,
            0x1.fd0001c77e49ep-1, 0x1.feffff7e0c331p-1,
            0x1.00ffff465606ep+0, 0x1.02ffff3867a58p+0,
            0x1.04ffffdfc0d17p+0, 0x1.0700003cd4d82p+0,
            0x1.08ffff9f2cbe8p+0, 0x1.0b000010cda65p+0,
            0x1.0d00001a4d338p+0, 0x1.0effffadafdfdp+0,
            0x1.110000bbafd96p+0, 0x1.12ffffae5f45dp+0,
            0x1.150000dd59ad9p+0, 0x1.170000f21559ap+0,
            0x1.18ffffc275426p+0, 0x1.1b000123d3c59p+0,
            0x1.1cffff8299eb7p+0, 0x1.1effff48ad4p+0,
            0x1.210000c8b86a4p+0, 0x1.2300003854303p+0,
            0x1.24fffffbcf684p+0, 0x1.26ffff52921d9p+0,
            0x1.2900014933a3cp+0, 0x1.2b00014556313p+0,
            0x1.2cfffebfe523bp+0, 0x1.2f0000bb8ad96p+0,
            0x1.30ffffb7ae2afp+0, 0x1.32ffffeac5f7fp+0,
            0x1.350000ca66756p+0, 0x1.3700011fbf721p+0,
            0x1.38ffff9592fb9p+0, 0x1.3b00004ddd242p+0,
            0x1.3cffff5b2c957p+0, 0x1.3efffeab0b418p+0,
            0x1.410001532aff4p+0, 0x1.4300017478b29p+0,
            0x1.44fffe795b463p+0, 0x1.46fffe80475ep+0,
            0x1.48fffef6fc1e7p+0, 0x1.4afffe5bea704p+0,
            0x1.4d000171027dep+0, 0x1.4f0000ff03ee2p+0,
            0x1.5100012dc4bd1p+0, 0x1.530001605277ap+0,
            0x1.54fffecdb704cp+0, 0x1.56fffef5f54a9p+0,
            0x1.5900017e61012p+0, 0x1.5b00003c93e92p+0,
            0x1.5d0001d4919bcp+0, 0x1.5efffe7b87a89p+0
    };
    private static final double[] LOG_CLO = {
            0x1.e026c91425b3cp-56, 0x1.dbfea48005d41p-55,
            0x1.e7fa786d6a5b7p-55, 0x1.1fcea6b54254cp-57,
            -0x1.c7e274c590efdp-56, -0x1.ac16848dcda01p-55,
            0x1.33f1c9d499311p-55, -0x1.e80041ae22d53p-56,
            0x1.bff6671097952p-56, 0x1.c00e226bd8724p-55,
            -0x1.e02916ef101d2p-57, -0x1.7fc71cd549c74p-57,
            0x1.1bec19ef50483p-55, -0x1.07e1729cc6465p-56,
            -0x1.08072087b8b1cp-55, 0x1.dc0286d9df9aep-55,
            0x1.97fd251e54c33p-55, -0x1.afee9b630f381p-55,
            0x1.9bfbf6b6535bcp-55, -0x1.bbf65f3117b75p-55,
            -0x1.9006ea23dcb57p-55, -0x1.d00df38e04b0ap-56,
            -0x1.8007146ff9f05p-55, 0x1.3817bd07a7038p-55,
            0x1.93e9176dfb403p-55, 0x1.f804e4b980276p-56,
            -0x1.f7ef0d9ff622ep-55, -0x1.082aa962638bap-56,
            -0x1.7801b9164a8efp-55, -0x1.740e08a5a9337p-55,
            0x1.fce08c19bep-60, -0x1.a3faa27885b0ap-55,
            0x1.4ff489958da56p-56, 0x1.cbeab8a2b6d18p-55,
            0x1.8fecadd78793p-55, -0x1.f41763dd8abdbp-55,
            -0x1.ebf0284c27612p-55, -0x1.9fd043cff3f5fp-57,
            -0x1.23ee7129070b4p-55, 0x1.a063ee00edea3p-57,
            0x1.a06c8381f0ab9p-58, -0x1.9011e74233c1dp-56,
            -0x1.9ff1068862a9fp-56, 0x1.aff45d0864f3ep-55,
            0x1.cfe7796c2c3f9p-56, -0x1.3ff27eef22bc4p-57,
            -0x1.cffb7ee3bea21p-57, -0x1.14103972e0b5cp-55,
            0x1.bc16494b76a19p-55, -0x1.4feef8d30c6edp-57,
            -0x1.43f68bcec4775p-55, 0x1.47ea3f053e0ecp-55,
            0x1.383068df992f1p-56, -0x1.8fd8e64180e04p-57,
            0x1.e7ebe1cc7ea72p-55, 0x1.ebe39ad9f88fep-55,
            0x1.57d91a8b95a71p-56, 0x1.9c1906970c7dap-55,
            -0x1.80e37c558fe0cp-58, -0x1.f80d64dc10f44p-56,
            -0x1.47c8f94fd5c5cp-56, 0x1.c7f1dc521617ep-55,
            0x1.8019eb2ffb153p-55, 0x1.e00d2c652cc89p-57,
            -0x1.f8340202d69d2p-56, 0x1.b00c1ca1b0864p-56,
            0x1.2ffa8b094ab51p-56, -0x1.7f673b1efbe59p-58,
            -0x1.4808d5e0bc801p-55, 0x1.80006d54320b5p-56,
            -0x1.002f860565c92p-58, -0x1.540445d35e611p-55,
            -0x1.ffb3139ef9105p-59, 0x1.a81acf2731155p-55,
            0x1.a3f41d4d7c743p-55, -0x1.202f41c987875p-57,
            0x1.77dd1f477e74bp-56, -0x1.f01199a7ca331p-57,
            0x1.181ee4bceacb1p-56, -0x1.e05370170875ap-57,
            -0x1.a7ead491c0adap-55, -0x1.77f69c3fcb2ep-54,
            0x1.7bffe34cb945bp-54, 0x1.20083c0e456cbp-55,
            -0x1.dffdfbe37751ap-57, -0x1.13f7faee626ebp-54,
            0x1.07dfa79489ff7p-55, -0x1.7040570d66bcp-56,
            0x1.e80d4846d0b62p-55, 0x1.dbffa64fd36efp-54,
            0x1.a0077701250aep-54, 0x1.dfdf9e2e3deeep-55,
            0x1.10030dc3b7273p-54, 0x1.97f7980030188p-54,
            -0x1.5f932ab9f8c67p-57, 0x1.37fbf9da75bebp-54,
            0x1.f806b91fd5b22p-54, 0x1.3ffc2eb9fbf33p-54,
            0x1.601e77e2e2e72p-56, 0x1.ffcbb767f0c61p-56,
            -0x1.202ca3c02412bp-56, -0x1.2808233f21f02p-54,
            -0x1.8ff7e384fdcf2p-55, -0x1.5ff51503041c5p-55,
            -0x1.10071885e289dp-55, -0x1.1ff5d3fb7b715p-54,
            0x1.57f82228b82bdp-54, 0x1.000bac40dd5ccp-55,
            -0x1.43f9d2db2a751p-54, 0x1.57f6b707638e1p-55,
            0x1.a023a10bf1231p-56, 0x1.87f6d66b152bp-54,
            0x1.7f8375f198524p-57, 0x1.301e672dc5143p-55,
            0x1.9ff69b8b2895ap-55, -0x1.5c0b19bc2f254p-54,
            0x1.b4009f23a2a72p-54, -0x1.4ffb7bf0d7d45p-54,
            -0x1.9c06471dc6a3dp-54, 0x1.77f890b85531cp-54,
            0x1.004657166a436p-57, -0x1.6bfcece233209p-54,
            -0x1.902720505a1d7p-55, 0x1.bbfe60ec96412p-54,
            0x1.87ec581afef9p-55, -0x1.f41080abf0ccp-54,
            -0x1.8812afb254729p-54, -0x1.47eb780ed6904p-54
    };

    private static final double[] POW_POLY = {
            -0x1p-1,
            0x1.555555555556p-2 * -2,
            -0x1.0000000000006p-2 * -2,
            0x1.999999959554ep-3 * 4,
            -0x1.555555529a47ap-3 * 4,
            0x1.2495b9b4845e9p-3 * -8,
            -0x1.0002b8b263fc3p-3 * -8
    };
    private static final double[] POW_INVC = {
            0x1.6a00000000000p+0, 0x1.6800000000000p+0,
            0x1.6600000000000p+0, 0x1.6400000000000p+0,
            0x1.6200000000000p+0, 0x1.6000000000000p+0,
            0x1.5e00000000000p+0, 0x1.5c00000000000p+0,
            0x1.5a00000000000p+0, 0x1.5800000000000p+0,
            0x1.5600000000000p+0, 0x1.5600000000000p+0,
            0x1.5400000000000p+0, 0x1.5200000000000p+0,
            0x1.5000000000000p+0, 0x1.4e00000000000p+0,
            0x1.4c00000000000p+0, 0x1.4a00000000000p+0,
            0x1.4a00000000000p+0, 0x1.4800000000000p+0,
            0x1.4600000000000p+0, 0x1.4400000000000p+0,
            0x1.4200000000000p+0, 0x1.4000000000000p+0,
            0x1.4000000000000p+0, 0x1.3e00000000000p+0,
            0x1.3c00000000000p+0, 0x1.3a00000000000p+0,
            0x1.3a00000000000p+0, 0x1.3800000000000p+0,
            0x1.3600000000000p+0, 0x1.3400000000000p+0,
            0x1.3400000000000p+0, 0x1.3200000000000p+0,
            0x1.3000000000000p+0, 0x1.3000000000000p+0,
            0x1.2e00000000000p+0, 0x1.2c00000000000p+0,
            0x1.2c00000000000p+0, 0x1.2a00000000000p+0,
            0x1.2800000000000p+0, 0x1.2600000000000p+0,
            0x1.2600000000000p+0, 0x1.2400000000000p+0,
            0x1.2400000000000p+0, 0x1.2200000000000p+0,
            0x1.2000000000000p+0, 0x1.2000000000000p+0,
            0x1.1e00000000000p+0, 0x1.1c00000000000p+0,
            0x1.1c00000000000p+0, 0x1.1a00000000000p+0,
            0x1.1a00000000000p+0, 0x1.1800000000000p+0,
            0x1.1600000000000p+0, 0x1.1600000000000p+0,
            0x1.1400000000000p+0, 0x1.1400000000000p+0,
            0x1.1200000000000p+0, 0x1.1000000000000p+0,
            0x1.1000000000000p+0, 0x1.0e00000000000p+0,
            0x1.0e00000000000p+0, 0x1.0c00000000000p+0,
            0x1.0c00000000000p+0, 0x1.0a00000000000p+0,
            0x1.0a00000000000p+0, 0x1.0800000000000p+0,
            0x1.0800000000000p+0, 0x1.0600000000000p+0,
            0x1.0400000000000p+0, 0x1.0400000000000p+0,
            0x1.0200000000000p+0, 0x1.0200000000000p+0,
            0x1.0000000000000p+0, 0x1.0000000000000p+0,
            0x1.fc00000000000p-1, 0x1.f800000000000p-1,
            0x1.f400000000000p-1, 0x1.f000000000000p-1,
            0x1.ec00000000000p-1, 0x1.e800000000000p-1,
            0x1.e400000000000p-1, 0x1.e200000000000p-1,
            0x1.de00000000000p-1, 0x1.da00000000000p-1,
            0x1.d600000000000p-1, 0x1.d400000000000p-1,
            0x1.d000000000000p-1, 0x1.cc00000000000p-1,
            0x1.ca00000000000p-1, 0x1.c600000000000p-1,
            0x1.c400000000000p-1, 0x1.c000000000000p-1,
            0x1.be00000000000p-1, 0x1.ba00000000000p-1,
            0x1.b800000000000p-1, 0x1.b400000000000p-1,
            0x1.b200000000000p-1, 0x1.ae00000000000p-1,
            0x1.ac00000000000p-1, 0x1.aa00000000000p-1,
            0x1.a600000000000p-1, 0x1.a400000000000p-1,
            0x1.a000000000000p-1, 0x1.9e00000000000p-1,
            0x1.9c00000000000p-1, 0x1.9a00000000000p-1,
            0x1.9600000000000p-1, 0x1.9400000000000p-1,
            0x1.9200000000000p-1, 0x1.9000000000000p-1,
            0x1.8c00000000000p-1, 0x1.8a00000000000p-1,
            0x1.8800000000000p-1, 0x1.8600000000000p-1,
            0x1.8400000000000p-1, 0x1.8200000000000p-1,
            0x1.7e00000000000p-1, 0x1.7c00000000000p-1,
            0x1.7a00000000000p-1, 0x1.7800000000000p-1,
            0x1.7600000000000p-1, 0x1.7400000000000p-1,
            0x1.7200000000000p-1, 0x1.7000000000000p-1,
            0x1.6e00000000000p-1, 0x1.6c00000000000p-1
    };
    private static final double[] POW_LOGC = {
            -0x1.62c82f2b9c800p-2, -0x1.5d1bdbf580800p-2,
            -0x1.5767717455800p-2, -0x1.51aad872df800p-2,
            -0x1.4be5f95777800p-2, -0x1.4618bc21c6000p-2,
            -0x1.404308686a800p-2, -0x1.3a64c55694800p-2,
            -0x1.347dd9a988000p-2, -0x1.2e8e2bae12000p-2,
            -0x1.2895a13de8800p-2, -0x1.2895a13de8800p-2,
            -0x1.22941fbcf7800p-2, -0x1.1c898c1699800p-2,
            -0x1.1675cababa800p-2, -0x1.1058bf9ae4800p-2,
            -0x1.0a324e2739000p-2, -0x1.0402594b4d000p-2,
            -0x1.0402594b4d000p-2, -0x1.fb9186d5e4000p-3,
            -0x1.ef0adcbdc6000p-3, -0x1.e27076e2af000p-3,
            -0x1.d5c216b4fc000p-3, -0x1.c8ff7c79aa000p-3,
            -0x1.c8ff7c79aa000p-3, -0x1.bc286742d9000p-3,
            -0x1.af3c94e80c000p-3, -0x1.a23bc1fe2b000p-3,
            -0x1.a23bc1fe2b000p-3, -0x1.9525a9cf45000p-3,
            -0x1.87fa06520d000p-3, -0x1.7ab890210e000p-3,
            -0x1.7ab890210e000p-3, -0x1.6d60fe719d000p-3,
            -0x1.5ff3070a79000p-3, -0x1.5ff3070a79000p-3,
            -0x1.526e5e3a1b000p-3, -0x1.44d2b6ccb8000p-3,
            -0x1.44d2b6ccb8000p-3, -0x1.371fc201e9000p-3,
            -0x1.29552f81ff000p-3, -0x1.1b72ad52f6000p-3,
            -0x1.1b72ad52f6000p-3, -0x1.0d77e7cd09000p-3,
            -0x1.0d77e7cd09000p-3, -0x1.fec9131dbe000p-4,
            -0x1.e27076e2b0000p-4, -0x1.e27076e2b0000p-4,
            -0x1.c5e548f5bc000p-4, -0x1.a926d3a4ae000p-4,
            -0x1.a926d3a4ae000p-4, -0x1.8c345d631a000p-4,
            -0x1.8c345d631a000p-4, -0x1.6f0d28ae56000p-4,
            -0x1.51b073f062000p-4, -0x1.51b073f062000p-4,
            -0x1.341d7961be000p-4, -0x1.341d7961be000p-4,
            -0x1.16536eea38000p-4, -0x1.f0a30c0118000p-5,
            -0x1.f0a30c0118000p-5, -0x1.b42dd71198000p-5,
            -0x1.b42dd71198000p-5, -0x1.77458f632c000p-5,
            -0x1.77458f632c000p-5, -0x1.39e87b9fec000p-5,
            -0x1.39e87b9fec000p-5, -0x1.f829b0e780000p-6,
            -0x1.f829b0e780000p-6, -0x1.7b91b07d58000p-6,
            -0x1.fc0a8b0fc0000p-7, -0x1.fc0a8b0fc0000p-7,
            -0x1.fe02a6b100000p-8, -0x1.fe02a6b100000p-8,
            0x0.0000000000000p+0, 0x0.0000000000000p+0,
            0x1.0101575890000p-7, 0x1.0205658938000p-6,
            0x1.8492528c90000p-6, 0x1.0415d89e74000p-5,
            0x1.466aed42e0000p-5, 0x1.894aa149fc000p-5,
            0x1.ccb73cdddc000p-5, 0x1.eea31c006c000p-5,
            0x1.1973bd1466000p-4, 0x1.3bdf5a7d1e000p-4,
            0x1.5e95a4d97a000p-4, 0x1.700d30aeac000p-4,
            0x1.9335e5d594000p-4, 0x1.b6ac88dad6000p-4,
            0x1.c885801bc4000p-4, 0x1.ec739830a2000p-4,
            0x1.fe89139dbe000p-4, 0x1.1178e8227e000p-3,
            0x1.1aa2b7e23f000p-3, 0x1.2d1610c868000p-3,
            0x1.365fcb0159000p-3, 0x1.4913d8333b000p-3,
            0x1.527e5e4a1b000p-3, 0x1.6574ebe8c1000p-3,
            0x1.6f0128b757000p-3, 0x1.7898d85445000p-3,
            0x1.8beafeb390000p-3, 0x1.95a5adcf70000p-3,
            0x1.a93ed3c8ae000p-3, 0x1.b31d8575bd000p-3,
            0x1.bd087383be000p-3, 0x1.c6ffbc6f01000p-3,
            0x1.db13db0d49000p-3, 0x1.e530effe71000p-3,
            0x1.ef5ade4dd0000p-3, 0x1.f991c6cb3b000p-3,
            0x1.07138604d5800p-2, 0x1.0c42d67616000p-2,
            0x1.1178e8227e800p-2, 0x1.16b5ccbacf800p-2,
            0x1.1bf99635a6800p-2, 0x1.214456d0eb800p-2,
            0x1.2bef07cdc9000p-2, 0x1.314f1e1d36000p-2,
            0x1.36b6776be1000p-2, 0x1.3c25277333000p-2,
            0x1.419b423d5e800p-2, 0x1.4718dc271c800p-2,
            0x1.4c9e09e173000p-2, 0x1.522ae0738a000p-2,
            0x1.57bf753c8d000p-2, 0x1.5d5bddf596000p-2
    };
    private static final double[] POW_LOGCTAIL = {
            0x1.ab42428375680p-48, -0x1.ca508d8e0f720p-46,
            -0x1.362a4d5b6506dp-45, -0x1.684e49eb067d5p-49,
            -0x1.41b6993293ee0p-47, 0x1.3d82f484c84ccp-46,
            0x1.c42f3ed820b3ap-50, 0x1.0b1c686519460p-45,
            0x1.5594dd4c58092p-45, 0x1.67b1e99b72bd8p-45,
            0x1.5ca14b6cfb03fp-46, 0x1.5ca14b6cfb03fp-46,
            -0x1.65a242853da76p-46, -0x1.fafbc68e75404p-46,
            0x1.f1fc63382a8f0p-46, -0x1.6a8c4fd055a66p-45,
            -0x1.c6bee7ef4030ep-47, -0x1.036b89ef42d7fp-48,
            -0x1.036b89ef42d7fp-48, 0x1.d572aab993c87p-47,
            0x1.b26b79c86af24p-45, -0x1.72f4f543fff10p-46,
            0x1.1ba91bbca681bp-45, 0x1.7794f689f8434p-45,
            0x1.7794f689f8434p-45, 0x1.94eb0318bb78fp-46,
            0x1.a4e633fcd9066p-52, -0x1.58c64dc46c1eap-45,
            -0x1.58c64dc46c1eap-45, -0x1.ad1d904c1d4e3p-45,
            0x1.bbdbf7fdbfa09p-45, 0x1.bdb9072534a58p-45,
            0x1.bdb9072534a58p-45, -0x1.0e46aa3b2e266p-46,
            -0x1.e9e439f105039p-46, -0x1.e9e439f105039p-46,
            -0x1.0de8b90075b8fp-45, 0x1.70cc16135783cp-46,
            0x1.70cc16135783cp-46, 0x1.178864d27543ap-48,
            -0x1.48d301771c408p-45, -0x1.e80a41811a396p-45,
            -0x1.e80a41811a396p-45, 0x1.a699688e85bf4p-47,
            0x1.a699688e85bf4p-47, -0x1.575545ca333f2p-45,
            0x1.a342c2af0003cp-45, 0x1.a342c2af0003cp-45,
            -0x1.d0c57585fbe06p-46, 0x1.53935e85baac8p-45,
            0x1.53935e85baac8p-45, 0x1.37c294d2f5668p-46,
            0x1.37c294d2f5668p-46, -0x1.69737c93373dap-45,
            0x1.f025b61c65e57p-46, 0x1.f025b61c65e57p-46,
            0x1.c5edaccf913dfp-45, 0x1.c5edaccf913dfp-45,
            0x1.47c5e768fa309p-46, 0x1.d599e83368e91p-45,
            0x1.d599e83368e91p-45, 0x1.c827ae5d6704cp-46,
            0x1.c827ae5d6704cp-46, -0x1.cfc4634f2a1eep-45,
            -0x1.cfc4634f2a1eep-45, 0x1.502b7f526feaap-48,
            0x1.502b7f526feaap-48, -0x1.980267c7e09e4p-45,
            -0x1.980267c7e09e4p-45, -0x1.88d5493faa639p-45,
            -0x1.f1e7cf6d3a69cp-50, -0x1.f1e7cf6d3a69cp-50,
            -0x1.9e23f0dda40e4p-46, -0x1.9e23f0dda40e4p-46,
            0x0.0000000000000p+0, 0x0.0000000000000p+0,
            -0x1.0c76b999d2be8p-46, -0x1.3dc5b06e2f7d2p-45,
            -0x1.aa0ba325a0c34p-45, 0x1.111c05cf1d753p-47,
            -0x1.c167375bdfd28p-45, -0x1.97995d05a267dp-46,
            -0x1.a68f247d82807p-46, -0x1.e113e4fc93b7bp-47,
            -0x1.5325d560d9e9bp-45, 0x1.cc85ea5db4ed7p-45,
            -0x1.c69063c5d1d1ep-45, 0x1.c1e8da99ded32p-49,
            0x1.3115c3abd47dap-45, -0x1.390802bf768e5p-46,
            0x1.646d1c65aacd3p-45, -0x1.dc068afe645e0p-45,
            -0x1.534d64fa10afdp-45, 0x1.1ef78ce2d07f2p-45,
            0x1.ca78e44389934p-45, 0x1.39d6ccb81b4a1p-47,
            0x1.62fa8234b7289p-51, 0x1.5837954fdb678p-45,
            0x1.633e8e5697dc7p-45, 0x1.9cf8b2c3c2e78p-46,
            -0x1.5118de59c21e1p-45, -0x1.c661070914305p-46,
            -0x1.73d54aae92cd1p-47, 0x1.7f22858a0ff6fp-47,
            -0x1.8724350562169p-45, -0x1.c358d4eace1aap-47,
            -0x1.d4bc4595412b6p-45, -0x1.1ec72c5962bd2p-48,
            -0x1.aff2af715b035p-45, 0x1.212276041f430p-51,
            -0x1.a211565bb8e11p-51, 0x1.bcbecca0cdf30p-46,
            0x1.89cdb16ed4e91p-48, 0x1.7188b163ceae9p-45,
            -0x1.c210e63a5f01cp-45, 0x1.b9acdf7a51681p-45,
            0x1.ca6ed5147bdb7p-45, 0x1.a87deba46baeap-47,
            0x1.a9cfa4a5004f4p-45, -0x1.8e27ad3213cb8p-45,
            0x1.16ecdb0f177c8p-46, 0x1.83b54b606bd5cp-46,
            0x1.8e436ec90e09dp-47, -0x1.f27ce0967d675p-45,
            -0x1.e20891b0ad8a4p-45, 0x1.ebe708164c759p-45,
            0x1.fadedee5d40efp-46, -0x1.a0b2a08a465dcp-47
    };

    private static final long SIGN_BIAS = 0x800L << TABLE_BITS;

    private DoubleMath() {
    }

    private static long bits(double x) {
        return Double.doubleToRawLongBits(x);
    }

    private static double fromBits(long bits) {
        return Double.longBitsToDouble(bits);
    }

    private static int top12(double x) {
        return (int) (bits(x) >>> 52);
    }

    /**
     * Returns {@code e} raised to {@code x} ({@code exp}).
     *
     * @param x the exponent
     * @return {@code e^x}
     */
    public static double exp(double x) {
        var abstop = top12(x) & 0x7ff;
        if (Integer.compareUnsigned(abstop - top12(0x1p-54), top12(512.0) - top12(0x1p-54)) >= 0) {
            if (Integer.compareUnsigned(abstop - top12(0x1p-54), 0x80000000) >= 0) {
                return 1.0 + x;
            }
            if (abstop >= top12(1024.0)) {
                if (bits(x) == bits(Double.NEGATIVE_INFINITY)) {
                    return 0.0;
                }
                if (abstop >= top12(Double.POSITIVE_INFINITY)) {
                    return 1.0 + x;
                }
                return bits(x) < 0 ? 0x1p-767 * 0x1p-767 : 0x1p769 * 0x1p769;
            }
            abstop = 0;
        }
        var z = INV_LN2_N * x;
        var kd = z + SHIFT;
        var ki = bits(kd);
        kd -= SHIFT;
        var r = x + kd * NEG_LN2_HI_N + kd * NEG_LN2_LO_N;
        var idx = (int) (2 * (ki & (N - 1)));
        var top = ki << (52 - TABLE_BITS);
        var tail = fromBits(EXP_TAB[idx]);
        var sbits = EXP_TAB[idx + 1] + top;
        var r2 = r * r;
        var tmp = tail + r + r2 * (EXP_POLY[0] + r * EXP_POLY[1]) + r2 * r2 * (EXP_POLY[2] + r * EXP_POLY[3]);
        if (abstop == 0) {
            return expSpecialCase(tmp, sbits, ki, false);
        }
        var scale = fromBits(sbits);
        return scale + scale * tmp;
    }

    private static double expSpecialCase(double tmp, long sbits, long ki, boolean signed) {
        if ((ki & 0x80000000L) == 0) {
            sbits -= 1009L << 52;
            var scale = fromBits(sbits);
            return 0x1p1009 * (scale + scale * tmp);
        }
        sbits += 1022L << 52;
        var scale = fromBits(sbits);
        var y = scale + scale * tmp;
        if (signed ? Math.abs(y) < 1.0 : y < 1.0) {
            var one = signed && y < 0.0 ? -1.0 : 1.0;
            var lo = scale - y + scale * tmp;
            var hi = one + y;
            lo = one - hi + y + lo;
            y = (hi + lo) - one;
            if (y == 0.0) {
                y = signed ? fromBits(sbits & 0x8000000000000000L) : 0.0;
            }
        }
        return 0x1p-1022 * y;
    }

    /**
     * Returns the natural logarithm of {@code x} ({@code log}).
     *
     * @param x the argument
     * @return {@code ln x}
     */
    public static double log(double x) {
        var ix = bits(x);
        var top = (int) (ix >>> 48);
        var lo1 = bits(1.0 - 0x1p-4);
        var hi1 = bits(1.0 + 0x1.09p-4);
        if (Long.compareUnsigned(ix - lo1, hi1 - lo1) < 0) {
            if (ix == bits(1.0)) {
                return 0;
            }
            var r = x - 1.0;
            var r2 = r * r;
            var r3 = r * r2;
            var b = LOG_POLY1;
            var y = r3 * (b[1] + r * b[2] + r2 * b[3]
                    + r3 * (b[4] + r * b[5] + r2 * b[6]
                    + r3 * (b[7] + r * b[8] + r2 * b[9] + r3 * b[10])));
            var w = r * 0x1p27;
            var rhi = r + w - w;
            var rlo = r - rhi;
            w = rhi * rhi * b[0];
            var hi = r + w;
            var lo = r - hi + w;
            lo += b[0] * rlo * (rhi + r);
            y += lo;
            y += hi;
            return y;
        }
        if (Integer.compareUnsigned(top - 0x0010, 0x7ff0 - 0x0010) >= 0) {
            if (ix * 2 == 0) {
                return Double.NEGATIVE_INFINITY;
            }
            if (ix == bits(Double.POSITIVE_INFINITY)) {
                return x;
            }
            if ((top & 0x8000) != 0 || (top & 0x7ff0) == 0x7ff0) {
                return Double.NaN;
            }
            ix = bits(x * 0x1p52);
            ix -= 52L << 52;
        }
        var tmp = ix - 0x3fe6000000000000L;
        var i = (int) ((tmp >>> (52 - TABLE_BITS)) & (N - 1));
        var k = (int) (tmp >> 52);
        var iz = ix - (tmp & (0xfffL << 52));
        var invc = LOG_INVC[i];
        var logc = LOG_LOGC[i];
        var z = fromBits(iz);
        var r = (z - LOG_CHI[i] - LOG_CLO[i]) * invc;
        var kd = (double) k;
        var w = kd * LN2_HI + logc;
        var hi = w + r;
        var lo = w - hi + r + kd * LN2_LO;
        var r2 = r * r;
        var a = LOG_POLY;
        return lo + r2 * a[0] + r * r2 * (a[1] + r * a[2] + r2 * (a[3] + r * a[4])) + hi;
    }

    /**
     * Returns {@code x} raised to {@code y} ({@code pow}).
     *
     * @param x the base
     * @param y the exponent
     * @return {@code x^y}
     */
    public static double pow(double x, double y) {
        long signBias = 0;
        var ix = bits(x);
        var iy = bits(y);
        var topx = top12(x);
        var topy = top12(y);
        if (Integer.compareUnsigned(topx - 0x001, 0x7ff - 0x001) >= 0
                || Integer.compareUnsigned((topy & 0x7ff) - 0x3be, 0x43e - 0x3be) >= 0) {
            if (zeroInfNan(iy)) {
                if (2 * iy == 0) {
                    return 1.0;
                }
                if (ix == bits(1.0)) {
                    return 1.0;
                }
                if (Long.compareUnsigned(2 * ix, 2 * bits(Double.POSITIVE_INFINITY)) > 0
                        || Long.compareUnsigned(2 * iy, 2 * bits(Double.POSITIVE_INFINITY)) > 0) {
                    return x + y;
                }
                if (2 * ix == 2 * bits(1.0)) {
                    return 1.0;
                }
                if ((Long.compareUnsigned(2 * ix, 2 * bits(1.0)) < 0) == (iy >= 0)) {
                    return 0.0;
                }
                return y * y;
            }
            if (zeroInfNan(ix)) {
                var x2 = x * x;
                if (ix < 0 && checkInt(iy) == 1) {
                    x2 = -x2;
                }
                return iy < 0 ? 1 / x2 : x2;
            }
            if (ix < 0) {
                var yint = checkInt(iy);
                if (yint == 0) {
                    return Double.NaN;
                }
                if (yint == 1) {
                    signBias = SIGN_BIAS;
                }
                ix &= 0x7fffffffffffffffL;
                topx &= 0x7ff;
            }
            if (Integer.compareUnsigned((topy & 0x7ff) - 0x3be, 0x43e - 0x3be) >= 0) {
                if (ix == bits(1.0)) {
                    return 1.0;
                }
                if ((topy & 0x7ff) < 0x3be) {
                    return Long.compareUnsigned(ix, bits(1.0)) > 0 ? 1.0 + y : 1.0 - y;
                }
                return (Long.compareUnsigned(ix, bits(1.0)) > 0) == (topy < 0x800)
                        ? 0x1p769 * 0x1p769 : 0x1p-767 * 0x1p-767;
            }
            if (topx == 0) {
                ix = bits(x * 0x1p52);
                ix &= 0x7fffffffffffffffL;
                ix -= 52L << 52;
            }
        }
        var tmp = ix - 0x3fe6955500000000L;
        var i = (int) ((tmp >>> (52 - TABLE_BITS)) & (N - 1));
        var k = (int) (tmp >> 52);
        var iz = ix - (tmp & (0xfffL << 52));
        var z = fromBits(iz);
        var kd = (double) k;
        var invc = POW_INVC[i];
        var logc = POW_LOGC[i];
        var logctail = POW_LOGCTAIL[i];
        var zhi = fromBits((iz + (1L << 31)) & (-1L << 32));
        var zlo = z - zhi;
        var rhi = zhi * invc - 1.0;
        var rlo = zlo * invc;
        var r = rhi + rlo;
        var t1 = kd * LN2_HI + logc;
        var t2 = t1 + r;
        var lo1 = kd * LN2_LO + logctail;
        var lo2 = t1 - t2 + r;
        var a = POW_POLY;
        var ar = a[0] * r;
        var ar2 = r * ar;
        var ar3 = r * ar2;
        var arhi = a[0] * rhi;
        var arhi2 = rhi * arhi;
        var hi = t2 + arhi2;
        var lo3 = rlo * (ar + arhi);
        var lo4 = t2 - hi + arhi2;
        var p = ar3 * (a[1] + r * a[2] + ar2 * (a[3] + r * a[4] + ar2 * (a[5] + r * a[6])));
        var lo = lo1 + lo2 + lo3 + lo4 + p;
        var logHi = hi + lo;
        var logLo = hi - logHi + lo;
        var yhi = fromBits(iy & (-1L << 27));
        var ylo = y - yhi;
        var lhi = fromBits(bits(logHi) & (-1L << 27));
        var llo = logHi - lhi + logLo;
        var ehi = yhi * lhi;
        var elo = ylo * lhi + y * llo;
        return expInline(ehi, elo, signBias);
    }

    private static double expInline(double x, double xtail, long signBias) {
        var abstop = top12(x) & 0x7ff;
        if (Integer.compareUnsigned(abstop - top12(0x1p-54), top12(512.0) - top12(0x1p-54)) >= 0) {
            if (Integer.compareUnsigned(abstop - top12(0x1p-54), 0x80000000) >= 0) {
                var one = 1.0 + x;
                return signBias != 0 ? -one : one;
            }
            if (abstop >= top12(1024.0)) {
                var huge = bits(x) < 0 ? 0x1p-767 : 0x1p769;
                return (signBias != 0 ? -huge : huge) * huge;
            }
            abstop = 0;
        }
        var z = INV_LN2_N * x;
        var kd = z + SHIFT;
        var ki = bits(kd);
        kd -= SHIFT;
        var r = x + kd * NEG_LN2_HI_N + kd * NEG_LN2_LO_N;
        r += xtail;
        var idx = (int) (2 * (ki & (N - 1)));
        var top = (ki + signBias) << (52 - TABLE_BITS);
        var tail = fromBits(EXP_TAB[idx]);
        var sbits = EXP_TAB[idx + 1] + top;
        var r2 = r * r;
        var tmp = tail + r + r2 * (EXP_POLY[0] + r * EXP_POLY[1]) + r2 * r2 * (EXP_POLY[2] + r * EXP_POLY[3]);
        if (abstop == 0) {
            return expSpecialCase(tmp, sbits, ki, true);
        }
        var scale = fromBits(sbits);
        return scale + scale * tmp;
    }

    private static int checkInt(long iy) {
        var e = (int) (iy >>> 52) & 0x7ff;
        if (e < 0x3ff) {
            return 0;
        }
        if (e > 0x3ff + 52) {
            return 2;
        }
        if ((iy & ((1L << (0x3ff + 52 - e)) - 1)) != 0) {
            return 0;
        }
        if ((iy & (1L << (0x3ff + 52 - e))) != 0) {
            return 1;
        }
        return 2;
    }

    private static boolean zeroInfNan(long i) {
        return Long.compareUnsigned(2 * i - 1, 2 * bits(Double.POSITIVE_INFINITY) - 1) >= 0;
    }

    private static final double IVLN10_HI = 4.34294481878168880939e-01;
    private static final double IVLN10_LO = 2.50829467116452752298e-11;
    private static final double LOG10_2_HI = 3.01029995663611771306e-01;
    private static final double LOG10_2_LO = 3.69423907715893078616e-13;
    private static final double LG1 = 6.666666666666735130e-01;
    private static final double LG2 = 3.999999999940941908e-01;
    private static final double LG3 = 2.857142874366239149e-01;
    private static final double LG4 = 2.222219843214978396e-01;
    private static final double LG5 = 1.818357216161805012e-01;
    private static final double LG6 = 1.531383769920937332e-01;
    private static final double LG7 = 1.479819860511658591e-01;

    /**
     * Returns the base-10 logarithm of {@code x} ({@code log10}).
     *
     * @param x the argument
     * @return {@code log10 x}
     */
    public static double log10(double x) {
        var ui = bits(x);
        var hx = (int) (ui >>> 32);
        var k = 0;
        if (Integer.compareUnsigned(hx, 0x00100000) < 0 || hx < 0) {
            if (ui << 1 == 0) {
                return Double.NEGATIVE_INFINITY;
            }
            if (hx < 0) {
                return Double.NaN;
            }
            k -= 54;
            x *= 0x1p54;
            ui = bits(x);
            hx = (int) (ui >>> 32);
        } else if (Integer.compareUnsigned(hx, 0x7ff00000) >= 0) {
            return x;
        } else if (hx == 0x3ff00000 && ui << 32 == 0) {
            return 0;
        }
        hx += 0x3ff00000 - 0x3fe6a09e;
        k += (hx >>> 20) - 0x3ff;
        hx = (hx & 0x000fffff) + 0x3fe6a09e;
        ui = ((long) hx << 32) | (ui & 0xffffffffL);
        x = fromBits(ui);
        var f = x - 1.0;
        var hfsq = 0.5 * f * f;
        var s = f / (2.0 + f);
        var z = s * s;
        var w = z * z;
        var t1 = w * (LG2 + w * (LG4 + w * LG6));
        var t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)));
        var rr = t2 + t1;
        var hi = f - hfsq;
        hi = fromBits(bits(hi) & (-1L << 32));
        var lo = f - hi - hfsq + s * (hfsq + rr);
        var valHi = hi * IVLN10_HI;
        var dk = (double) k;
        var y = dk * LOG10_2_HI;
        var valLo = dk * LOG10_2_LO + (lo + hi) * IVLN10_LO + lo * IVLN10_HI;
        w = y + valHi;
        valLo += (y - w) + valHi;
        valHi = w;
        return valLo + valHi;
    }

    private static final double S1 = -1.66666666666666324348e-01;
    private static final double S2 = 8.33333333332248946124e-03;
    private static final double S3 = -1.98412698298579493134e-04;
    private static final double S4 = 2.75573137070700676789e-06;
    private static final double S5 = -2.50507602534068634195e-08;
    private static final double S6 = 1.58969099521155010221e-10;
    private static final double C1 = 4.16666666666666019037e-02;
    private static final double C2 = -1.38888888888741095749e-03;
    private static final double C3 = 2.48015872894767294178e-05;
    private static final double C4 = -2.75573143513906633035e-07;
    private static final double C5 = 2.08757232129817482790e-09;
    private static final double C6 = -1.13596475577881948265e-11;

    /**
     * Returns the sine of {@code x} ({@code sin}).
     *
     * @param x the angle in radians, with {@code |x| < 2^20 pi / 2}
     * @return {@code sin x}
     * @throws IllegalArgumentException if {@code |x|} is too large for the ported reduction
     */
    public static double sin(double x) {
        var ix = (int) (bits(x) >>> 32) & 0x7fffffff;
        if (ix <= 0x3fe921fb) {
            if (ix < 0x3e500000) {
                return x;
            }
            return sinKernel(x, 0.0, false);
        }
        if (ix >= 0x7ff00000) {
            return x - x;
        }
        var y = new double[2];
        var n = remPio2(x, ix, y);
        return switch (n & 3) {
            case 0 -> sinKernel(y[0], y[1], true);
            case 1 -> cosKernel(y[0], y[1]);
            case 2 -> -sinKernel(y[0], y[1], true);
            default -> -cosKernel(y[0], y[1]);
        };
    }

    private static double sinKernel(double x, double y, boolean iy) {
        var z = x * x;
        var w = z * z;
        var r = S2 + z * (S3 + z * S4) + z * w * (S5 + z * S6);
        var v = z * x;
        if (!iy) {
            return x + v * (S1 + z * r);
        }
        return x - ((z * (0.5 * y - v * r) - y) - v * S1);
    }

    private static double cosKernel(double x, double y) {
        var z = x * x;
        var w = z * z;
        var r = z * (C1 + z * (C2 + z * C3)) + w * w * (C4 + z * (C5 + z * C6));
        var hz = 0.5 * z;
        w = 1.0 - hz;
        return w + (((1.0 - w) - hz) + (z * r - x * y));
    }

    private static final double TOINT = 1.5 / 0x1p-52;
    private static final double PIO4 = 0x1.921fb54442d18p-1;
    private static final double INVPIO2 = 6.36619772367581382433e-01;
    private static final double PIO2_1 = 1.57079632673412561417e+00;
    private static final double PIO2_1T = 6.07710050650619224932e-11;
    private static final double PIO2_2 = 6.07710050630396597660e-11;
    private static final double PIO2_2T = 2.02226624879595063154e-21;
    private static final double PIO2_3 = 2.02226624871116645580e-21;
    private static final double PIO2_3T = 8.47842766036889956997e-32;

    private static int remPio2(double x, int ix, double[] y) {
        var sign = x < 0 || bits(x) < 0;
        if (ix <= 0x400f6a7a && (ix & 0xfffff) != 0x921fb) {
            var m = ix <= 0x4002d97c ? 1 : 2;
            if (!sign) {
                var z = x - m * PIO2_1;
                y[0] = z - m * PIO2_1T;
                y[1] = (z - y[0]) - m * PIO2_1T;
                return m;
            }
            var z = x + m * PIO2_1;
            y[0] = z + m * PIO2_1T;
            y[1] = (z - y[0]) + m * PIO2_1T;
            return -m;
        }
        if (ix > 0x400f6a7a && ix <= 0x401c463b) {
            var m = ix <= 0x4015fdbc ? 3 : 4;
            if (!(m == 3 && ix == 0x4012d97c) && !(m == 4 && ix == 0x401921fb)) {
                if (!sign) {
                    var z = x - m * PIO2_1;
                    y[0] = z - m * PIO2_1T;
                    y[1] = (z - y[0]) - m * PIO2_1T;
                    return m;
                }
                var z = x + m * PIO2_1;
                y[0] = z + m * PIO2_1T;
                y[1] = (z - y[0]) + m * PIO2_1T;
                return -m;
            }
        }
        if (ix >= 0x413921fb) {
            throw new IllegalArgumentException("argument too large: " + x);
        }
        var fn = x * INVPIO2 + TOINT - TOINT;
        var n = (int) fn;
        var r = x - fn * PIO2_1;
        var w = fn * PIO2_1T;
        if (r - w < -PIO4) {
            n--;
            fn--;
            r = x - fn * PIO2_1;
            w = fn * PIO2_1T;
        } else if (r - w > PIO4) {
            n++;
            fn++;
            r = x - fn * PIO2_1;
            w = fn * PIO2_1T;
        }
        y[0] = r - w;
        var ey = (int) (bits(y[0]) >>> 52) & 0x7ff;
        var ex = ix >>> 20;
        if (ex - ey > 16) {
            var t = r;
            w = fn * PIO2_2;
            r = t - w;
            w = fn * PIO2_2T - ((t - r) - w);
            y[0] = r - w;
            ey = (int) (bits(y[0]) >>> 52) & 0x7ff;
            if (ex - ey > 49) {
                t = r;
                w = fn * PIO2_3;
                r = t - w;
                w = fn * PIO2_3T - ((t - r) - w);
                y[0] = r - w;
            }
        }
        y[1] = (r - y[0]) - w;
        return n;
    }
}

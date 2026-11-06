package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Holds the tables and the arithmetic of the adaptive hybrid transform of E-AC-3 (ATSC A/52 Annex
 * E section 3.4): the vector quantization tables, the gain-adaptive quantizer and the inverse DCT
 * that spreads the six mantissas of one bin over the six blocks of a frame.
 */
public final class Aht {
    /**
     * The 4 vectors of high-efficiency bit allocation pointer 1 (ATSC A/52 table E4.1), six 16-bit
     * two's complement values per vector, four hex digits per value.
     */
    private static final String HEBAP_1 =
            "1bff1283045210ad28ac12d4e9baf38dc76dfa90f8150351027918371b61ce15f6fef5b4fa44e4891da82979e8c6f40a";

    /**
     * The 8 vectors of high-efficiency bit allocation pointer 2 (ATSC A/52 table E4.2), six 16-bit
     * two's complement values per vector, four hex digits per value.
     */
    private static final String HEBAP_2 =
            "d0d70260e495024e0fa003651a243d49e7dedbe9ffb60085073ffc235074f498ee8500e1fb56f0c3fccbe65afc95b0b6"
            + "f536f393f002ea09bdcf2625060b1ab707bc4f09fbd1ec86184dba05ea74187a0166048a0ea9fbd610bbf3653e3827ca";

    /**
     * The 16 vectors of high-efficiency bit allocation pointer 3 (ATSC A/52 table E4.3), six 16-bit
     * two's complement values per vector, four hex digits per value.
     */
    private static final String HEBAP_3 =
            "d8d4512b2ae6ee30031effbc2b2a500ae627eb22f8fbf9a10f89fde21bcefb72499c3956ef20ffa0e381fe14a9deef4b"
            + "0a8416e0159a5566e3d4eb33ff79a4a103c21fb3fd7c017ef9e50d48f31d1255e514577e0dcf0bd61c8018464ffcd0bd"
            + "0039e5590738a8b3e8e11aa7fccbf1b9fe7de793f939a89be8620632b636c7c823fe02c1e9ac0108b9d4391a1ef1feaf"
            + "ff92006c0008004affa7ffce19d4fa1354b7f986e0f3ff0a54a3e741df9eff9bfabbffeaaa0de6b41f2602880806feb5";

    /**
     * The 32 vectors of high-efficiency bit allocation pointer 4 (ATSC A/52 table E4.4), six 16-bit
     * two's complement values per vector, four hex digits per value.
     *
     * @implNote This implementation reads the rows in the order A/52 and every edition of ETSI
     * TS 102 366 print them, which reference decoders used as black boxes confirm; some widely
     * deployed decoders read index {@code i} as row {@code i + 1}, so a maintainer must not shift
     * the table to match them.
     */
    private static final String HEBAP_4 =
            "590315c0e9e6ff64fe06ffdf19ecee0f375dbc6fbf7503600e4a580c0068f91dffac0006544cba69e38ef9d9f7e2fec0"
            + "f7472721f5583a5acab8bb05f9e4bab6b52735a70ac50b8711a815861ce12a2f4b1fca36018f0ba0fbb51395fb79564f"
            + "0e28f6c91248f74258ae0eb5ef97dfa3e566cf9ab8123c16ebb2e52bd8c1df54c16aafaeff72a771fe901127fe30fff3"
            + "032efba2fbbfa9fd004a0611f9ae4b16bb16cb4e034af6fb1251406a514dc3e5efbcf080f3142bcecb1a351fb3ef35ca"
            + "0719035652e9fc3af995fef4f5e5ff95b14601780496fed0f49901c5eaf202eea9eefc2eb5bc41c72710f20408a305b3"
            + "0553f59effdff01d048daa1fde70f538bb90c18f3a31052b028cdb8d0cb5c6e22f954cece727168dc3dd438b40cef496"
            + "fd6bfda70649585203e0fbeb136123932bd91e953fc048c3aa90fa67008a05bef89dff3cb3d5b8e52b30fdfc09eff737"
            + "fb54bb5a4eb62cc6fe6f0a3b121ee0262e73c27144cfc595ffad011601430037ff6600e81e6c05b647db3bc0c26dfb95";

    /**
     * The 128 vectors of high-efficiency bit allocation pointer 5 (ATSC A/52 table E4.5), six 16-bit
     * two's complement values per vector, four hex digits per value.
     */
    private static final String HEBAP_5 =
            "f2beb2ee0b93257612344cd9c2cfe6fb45070f14dfd8b41b1173019cba2fe09b02b3bc650dfc093b1ae60eb318ebafd6"
            + "bcb2c8cbfa8ca27d20b5cf07e077ac23c2ea0c8e1fa9e8b310f714310a7bbe4aebe6bf5218ccd65432c39c64a9b60ffb"
            + "f4c0df52e8b0bcfaf5b25a5cec19c837a89d54ed0e690b91f675bab562430a93063a0007b835233210ae02dbfe56fd80"
            + "a371609c160a0264feccfc3cfd0104f400e1066300ad0394154f195d132629405a01bd0c4343adc2b6e41348f2a40d1d"
            + "fa923c80aa46c6ed053b021ee52ef732d0daf3fdb1f3af72f8f52dff053f22d502b55fb1ab9624f612492426e1793e20"
            + "ea49f436dc2ffabda7ed3244fe9213d4f9414fcbfee5f495f8a2e757fc55f7dffa890db9f3a7fde7ec2d2c044bc403dd"
            + "092910391689ef4f00e9fe71aa7afb8ebfa6170e1570f3752717cf0e498d51c4fb7a06fefb731396fb51190fdf1eadd2"
            + "0764f2320ee7e92a402b4f40f598d295efcdb879a74a3a00436828b31e542f084a0c09ddac55b703d56f1110e47511bb"
            + "f9da0802168060b43e6f450efde6a6ad2b3b283d01810210deeff42fc01ba53b406b0e4616d0023f2e72079b624519fd"
            + "19e1f244f8540f0afe7aff8c465551a437f3e23bd5562e1aed07f48ccbeae179547608dbfdbddb29fd14acb7304f2049"
            + "df83055fba490b692366561e47de21bbfa21f68eb889c672f4553b19f2fd571c3636cab916f2b5ae3ce32c56aefe07b3"
            + "062de4d5ac4009970041019e0203ee8cfd67edc0007db4ea53f7b0b3f8b0f87aff2dfc021445d026f911a402ee3e16b5"
            + "0141e74539361b3ff9130363ca0a0c6c1ef701bc4c600c4ae5fc2fdcf84c4400a128cd64fd173814fbad5cbeda61b858"
            + "476ce11be2954aae1e29ce8d07863afdcdd00869547f0748f7ae5b7842a0c313f9f80057207ad1d038f5af911ed3f7cd"
            + "4c90591ebc68f808011df0e9dfeab86e29e4ca50cb63f97e380f1310b1be03c4ef83ff4c9feabf054d0c172512a9113e"
            + "f6415bc5c0f30b66fbf2f8264a1ef614341f057de7cefb9009b93566586ee371ff7ff518c976418759e402d80d4500a2"
            + "0bde03e12246aa2fe62f038ecf64a88ef5beeb514c402690f889b89eb7b6c58e12981bcf206af45e651e1dece12703fc"
            + "17f43b174945a0cee67fe61d1ef42f5ddb0da266157e03a9bd60eb0309da01470469fe7a3d9e4df3d7742ba4f3dd3a05"
            + "d180e065b9f8a8f1bcabe56dcdc2f784e693172730aaa8adfe0f0142040ee60deae44f57043ba638ded22f62fd060a3f"
            + "13cb4d00f893ffe2febb005503dbe93a1074dcba23a19e32e1441c74cffc3272aba851cdf9a2e1f2f775deb3ea1c9d94"
            + "e5f40184a7f905f6237a00c1e145a8dc142b016a03b0fefd0ef0d1b61da7a57862fe5cdbd6f8101bad8952b557a7fcba"
            + "ed8d55231828ff86066afd335fb84daff80503da0007ffc9954fff7909850103005901335f7ef0dfeaf1fcccf6ad0169"
            + "1599169848fa00f2aa78f05d5720118302d2d02e1d923c5821e10bc14fd55274ad94f3f8fb940a91f8df152cfcef4864"
            + "4224cb33bf83c5f6b099c87308ab056453e2fb9801470053f77f540df0f0c89cff34f77103b9db2e3e02d62af3615226"
            + "fe5bfa9f0280dfd1ae10087e10d54852dc74b871c3620e78e8c901c1df3d0433a93eec800b8931f4476d05963a5954e3"
            + "f49f0191ed7db17706a3fb850d79147922955676e28505abf796218846c8c3024b77e8990dad0b19170918fd21b659ea"
            + "09a30b8c017b1647a9e1f773bdbdfdae4986eb51066803060b50fa700e02f70c4dc6f8e2b77152e3c94fcee34052027b"
            + "f832b48ebf712fb0bf40e152da3603f4ab730b43ce580911fc1301d7f1d31f6dd4b163bd102dac20f58f02f4fd69fdf5"
            + "195a21534b594a0517ccdb7d4245601736c82758fde8d73ae02d0861a60cbd4f154beecfc5e75028b881da0bd193ba59"
            + "f70ec8d8081657c3068702d5dea639250dc1af9f1a1120084f18113afaaafdb704cdf66f1d2be4143563dfca5778bc58"
            + "f8740f23dc98f11c03be0109eed10b8fc1d94c8e135afbaf4659d93db927f0ea2baa16bdc6fcfb3525bc54732bdcd237"
            + "fd2ff95c006df7a2003de58c9fd5a80815e8f85bf91ffc0ca350ee9df580c6a9ef5626bf212ffc824fd6ca04bc8d008b";

    /**
     * The 256 vectors of high-efficiency bit allocation pointer 6 (ATSC A/52 table E4.6), six 16-bit
     * two's complement values per vector, four hex digits per value.
     */
    private static final String HEBAP_6 =
            "27aa1cc541dd48f9a693f1ccf5c5f134ea67ebb8dccfb0b6ea31a6f053311b6402e902d001acfa4d006df3f60169df2d"
            + "1fe1578100f106dbfc96f4f804743163090256f79dc6bb6bf5cf0d332861b2eec394a278f038ce049b5433280f410523"
            + "1210a309352862e5fb69087dff9f35b3ebfe5ad71076a97f1adefebe4758fcaad174fd234380ce83da235c0bc090fae3"
            + "16aaec984c46ad369fd2ff4916dbc0f73b7ddae8f9fe0179371061e1346b20625b1841b2e3a30076c2054a992635feeb"
            + "ef405455cc18c07d40f9ed02132db4ef5b733971fd2e007d4c06ed84f878d2f9512215319456f4bfef150180f7c90557"
            + "fef6dc29154166ddf87c107df466b136aac8154ae2fe14e0ff23e5d8025bdc4c051c948e2595df44f85124bbf98d5921"
            + "1d8eeb7eefbb0569fc220230fb1260a2b58f29f51da1e44601c34ea2d923e881f774fa4e56e924a423882acff6c3f174"
            + "48ecfd76fb2e2b541dfe17514b07fa33fbccfd25fd54002bec9334764eab003c01dcfc59b1c3220609c303f8fb7a014f"
            + "98d348a6f767fd630d510319ed8a22ab9fe1da520e3bfee533f7ac64f195fb60f84e064c00ad003c039704cd1b1efd67"
            + "3ff9425f14ddc941f700b05a62f6d68f2eabe21be72536ea5d79cc35e3c6fc5700eaff4518a7f8ab30daf8a9493fa4d3"
            + "026d192d0d1aa12e20d614c3f31fec56eda0ec289b7e14e3fb05cc11fc3ba4ea04be6693e7942733b1773bc5c1371410"
            + "255af0b9b3ca128956feefb51f2ab37036c8e98fae8922eb0007f03903dfe84f0034b4210d9d0b991e341e6a62e0183e"
            + "fc41cdf4f8d0a7291c9c2a4eedb2068ed844ebab10c6fb090f3105161d1a027e4f96f3c3cf30dc5d481fcfc9e3ba4878"
            + "e7d721c9e509fc8142d540dcd9586fa30b1d06680b6dfed63a789a7c3a1ea2340717e6b665fb142e52e93e01547139e9"
            + "ab4c40365018c7f6e436efbe6fe7005af9dc0315fc7affb5fa3909a7f0230e1cf7402aa221a844534367bbd0427ec01b"
            + "af0eb75b62ba4538f20b069ffc1b17f1e7612bf2d3a1b2e5ffb6f05ff9d0344800a2ff70fdef524c1ef3d37c01a6ffe6"
            + "1bbecb25b1a90a45ff4efe5323f10558a9220a3fafed6139fe501a13fef6221300506d784c25f3dcdbd30776aaef14e1"
            + "36ffd31f313c17bf4da505232ac3266db74c3d7e12b8025df90f0eaef00954c01788fdc00deff2063ffb0a78f92802cc"
            + "ec47fa89ee3afd74bac7f2daf1cdeeece686a9781cd605b22fd24af6160ee179b0bf5360e2ac4df05bf6d9e71625f83a"
            + "f71d3c4e2a9bba291961350ec1eac2e2ed9417835ebae7ddf7ffe538fb4803964547ffbbf177238bc13fa3bb175df6d8"
            + "1eb6dd2a5de163a4d4e7fd1bced44c0c39393c5bad1604930836047b0ae510000883222eb8dabaa2d782ebadfbd6f22b"
            + "f4fdb20ad16f1790207b2886dc8af7cc4be7ffef02dcfd4fc750b4e8e4494927074e597a0f48029363fdf05a2593036d"
            + "0a3858a7e97646000ee44efc0a0168dfeb83d56408d0fdfbec9200c6aa21f1e8569eb614533cfb454ac84133f9cc2c7e"
            + "f9020f77f2601b5be43d518de824b9ddb6de60bb407c0c8b4fee9e65b0771184ec09fe22e716f832d80bfdcfa9e9a8bd"
            + "0be7b65e1da23997b26a18cfec49057dda38041faa872ba20d99da1d197ebef1591d5593b776445d3948050b13a24cdc"
            + "3f06b29ebdc4b9edbddb16a8dfe0132c22e708e0fb8ca54f06240ac1f9c2085ff2eeaa5ad998fbdc935604be1c790096"
            + "0062060202174415a562fc7b535cb14e0ce1f930dff1ac2aefbaede7ba121566050500884919520b60f22c9d0502edf6"
            + "f2311dd4fef7085dfcc3f80df3904d010ad7fffe04420068e58db1270b7af7b3ffdc04f4255824d62572565436031898"
            + "fde9b1ce10b4f8b4fe40bce1a0e037a4cab1add008df2d23f5aa3c4dee1348ceef35fd92b1a0104946c3fa84359af8df"
            + "c019237802e85605007d2a2a25acc6f1b7d1c6862ba6aeeefebaa32e18001ee5025a0604e60619eace7553945131e549"
            + "109cadcd15fc48ff5d34208846421648eb83b953fdd50c9317cb3798ec03bbd0b404d27fabae2c263c4a63f61a7997c5"
            + "536bdfcc16f5f22c17bff5f90a2bf669152dd002b56415c6f94798e7a3905978fea30ecb088dfb4d14dc0cb1a7a70068"
            + "f980d4f4f4d7af0da20f4dbc5959e34fb7cfc6e8df30cd5b0ec10f76202f500ee4b1fb4fff60f9b3fce7de17023d0308"
            + "10c9f1364f9517c2eb37b8204939099f3102e1bbe1caf7792b42ed9056670721a0e10ff005dfb516f9df000dfec70177"
            + "013efdc109f000b200660028c18496ef13900cf802ae0487649b6906023ee8d6f0b4057fdc44e20ff4c5df40b7196720"
            + "e2ebb988b8242262f734aa821eab2dfd6b5dcdd1fa7e4c8608c0173b2bef3e6ce69d5ed854a9b7ad262b1996f556014e"
            + "efcb0628d4fe0059a093e9b21e2805c653a49e3fdf3f0009f67027eace2cc1310489acdcddcbc7a3a67ac6240a453614"
            + "e3ac0b1bda590b42c6df5fb1fd5ee67e019ea4dbaca101c60838e7582a8746a7fb5100affe13fdcef54d0076fbce005d"
            + "d8e5f015925956a43ae5fd84ede3bfe8dcd5b03ed2a8ae3c12cf3e145eaecabef3fefbdde5bc1202b6acc44dbed35db4"
            + "3bf5fd5ef19e54af117bd0c812940a2114ea17713ad7677aa2f9bc9d1b20017a02b6029e5b60dd79c6871d78fc942b50"
            + "0e380d085841f259f6e8ff8f011c1b020c1927bb19eeb74309a817582b2ed160fda5fd693f2f4039336cf035123b1d07"
            + "4b8a3caee67b0691ed07429842830214b588fa5febf6043dceb7bb37080e9d0c4a41c1072748adf8cabef47b3c074dde"
            + "fd78f9bb273ef9c833f04d60fbe229f8021a616a259edca4d88d0be29e0ca20c3693006419931afb1b77286c5cdfba22"
            + "a6f7f840fa8ff2fe243337edc7f6f0810be23f7ebc6925aeac6f5c4c418502cc0a670072b5b8f4220626ff0b05b7fce7"
            + "578a5b91c6d3fdee439e3531d2c21effc97e5ba99fcc67b6fbeb0e5ff756294c5207f18ac36700c5414e9fe513510005"
            + "2a1d10ef68a6dc9dc0e8f4e83ecba1dcf0a3e54f3165e48b08309c1cdf4e1a9e000b049ad1b8fdb9dd47afc1d719fe84"
            + "f64960c9ab79b473067cfd240909356f0ff55fe56073ad45f6c2fe08efded6b65c7407a94f9b4591dade0e95b5f6e76c"
            + "f0f041a2fc5fb0aabab51a8d308f17bed3f8c78e1b015bb41dd4f98959e929dfdf9c0346de91fb2db9500f393edd05d2"
            + "f1fe20543b3df131ad6306cdee6f54eb093efeeaed483cbda5aeca741df43f685e383ab1b1b53215b1404133d279c12f"
            + "cec74f0f0da8f60be5a7d1b611591e84512f42b82d03da5560be212ea4fef3422b5de430d885e239a978b8816815254e"
            + "9c3301dd1ec2f9fe0463ff5801d6266afea55d89d773db05f000da1ae538abd8516d1c0614fa2614a32bfb5a0200f9fe"
            + "fc12d8c2ce974b22f902fc863b045c44c2e2f626fb4dfad3e312f5d30447ff09fe2700b11f9900043088a8f428a5e1d0"
            + "56b42a17ec4d02b20216ff2cf3affa76be3d47fa3dcd59ac1631f74b0c7cf2aaaac7c62900130313040800aadf99fd7b"
            + "fc8ef6f1961f01b0eed805dbfab6d1d5ffb4b064d7cb2c4000d3ed6fedbde4ebcb1e388f179b148cfe35fe32008fffbf"
            + "f5f41c58f30b23fca570d8fa9ecedac449ba17d5097dc76e207a08e537700db8651955f000d04efafee79f36ffc1fb61"
            + "0447e86e0a92aa51f5a102330017e8d600f3dce314e1504ec396319b10402b4f508dd7505203ffabdeec00c203ebdad5"
            + "b34bf2f9c8ff0df6a4abfd65f7e40da1f388b459021bfa061cb8c49358444ba9041340f3f8b0fe6304d3eb64f222558f"
            + "1efbf8284248e57172d1f655caeb20c5a3aca9b5c89ec827d2c9b1863eb8f8c83d6911940f09bf3b4ec1ad5d1e622e58"
            + "e66dfb07b66bd42e2d74041409e0e5ddba03d39eece2fc1004d910a4090f17df0d9d4ef10bc6f41814c4ee45515f21fe"
            + "f902c6a501163684d8afd6cda734e0ebfb7e35fdfa34fb21e36bfd99332649ef26a905ac09f8f6de0d60edea2b74b380"
            + "d48bafb7d599d5e1aee11ab103d8c509168f62251501b2a9020533d8e2def9515084e883ac5733c3aec5348943813330"
            + "c23dc0885a35f03bdffd03670246311bad77c652dc1d163510def9102ca1ba9dd93f0241177d41be44f79b5aeed0f222"
            + "ca50bf630e34f2fead9dc1f219a5d47521c9ccc65b31cb03f612dcaae27a72380e75fe81d68c61a30765dfee51b8c0ae"
            + "149c415629a34de4ed41b484fdecdbac6cd01365ff0f0218fd03af1ef2ac49b60acd058cf40d0a94b5b2feb50dd10074";

    /**
     * The 512 vectors of high-efficiency bit allocation pointer 7 (ATSC A/52 table E4.7), six 16-bit
     * two's complement values per vector, four hex digits per value.
     */
    private static final String HEBAP_7 =
            "ad4b55852896354e29dedc27a809dfff4798e61b63aed5a01a90ca42cc225792394bae36092b29140465f28115c16a00"
            + "e6272e4ba03459994f8ae87daaf829b9b361c553dee2f7dff54721ecece16c5b1d82d147fc04099cfc461292fd8dc010"
            + "b30a5a39004bca8cf5ac083c0fd1f4c816dbee9556863110acc8bd17fd261cfbd276d6e52c440700682a5bdeb397fe15"
            + "ba5dbe77cadac7cba9f3f6600443e8b1e0d9bdafb030aa5547d4fbb1078d341ebbc946c2587643c1d91245ff476202ba"
            + "05cc4f49e986986d134da909f5d511ebe92e4820223ff5f8f513f9be54d10c1b9bad0c98b5ad1255ec7117ac07b4c509"
            + "f773252cfdee50bdedcadf93a8cbdd4909e1d3a8156403e65654ec440673f59f1207090f5177f3faf2fe1009349e0bfd"
            + "005543892818c66000d6005a9903b65fb4684b2cd81626b5d9f55011e64de4b90b4bfd1e505fc20ca67f1ad6004c0147"
            + "2228cdb3a65ff6bcb420d9d5cdaa3f37525c0eed02edca20c18547df0957bb034c1ce87e058f2dd60fd34b5a1ac9b31f"
            + "ebb026264746099f494ced0cfdab4c2a052bdc78feccfbb0f3e59b7dc2cf62f4121a0a4b4ca7f6b0e1172e14db8bc8fc"
            + "0a526755ad9dd78ef963f951560f54792d3ca67defd30081e8160dd60393fefbffeffe8106a01a30fa6f51660359eec0"
            + "058fc450de9ada3d145a1637ee58fd9bd25d15f21086026b03a9ec9dc8eabd30e506e8c0c524fe1fe3bbc5d249bc54a9"
            + "9bc60b5e0477feb9fe36fc1dda48fccd9ebc0af44f01043bfba9f19ef904b3dc03c603351c7dab012a26e46da503f945"
            + "fee6d4ab00aaae2a8f02314746120e81f9e503750005023417e458a808c2e4d926f7e80c10f268b8f18707b8fbc9f5f6"
            + "fd6be123f594c4a6453a1117efb2d4d302cda816061a2fdce6fb479e17d71b4717444713267b14fa5c34e533e657ffc2"
            + "55cc342ffd550ec9087800d1f20ffb99b2f4f9f8051cfcddf3f53eb1ca21f3fb10c65ca1d8f126d7c2003286a3b154c3"
            + "25fa59352fa03af3159d12e508c3083304db0ff9128c329c0fa7f65c0d19f3ec228b428010ea17ef15ad24212bda6fb0"
            + "da8edd8700ec03f101c7fc3c1aad4b5afc0600c8071d0242144c03bd28840d02ce00ff81f432dfffc723562d1720041d"
            + "2ae66556a01ea512d17fe57b588bd4fe16680a075c99d7f3f2f1ef77eaae50bbd5a5f1ebefddf1e411dffcc3fea2fcb1"
            + "f3190d7be31ad2ac0bcf01c70c80dab50c82a6932bb0989ec8f3efeb3c1637d7d55db0670edfd4f856243822c420e1cb"
            + "e76dbac9f9e82f10b2a3fe45e7fdef76ff6020ab586e2e874afd04971cfed96defd81260ffb8e21cff90d14df3626a27"
            + "0cca174b1d4dbd8503629c9402e10745072907e609501293eb9f1d580cfb0a9b0bf9f9ba1097023515fd09c14663ecc8"
            + "f4efba00e0823d7afc0608580beab3e54222748bd8123b31d3ae00769b3cca3f3bd8fe2ced281360ef590627d69f4c69"
            + "dff5fa7ffd05fb8dfda10580f765d36907e5e70cf5d802c7fe63f631ff28f241919506b7c792429a365d34bb9b5ec107"
            + "4b1c1cadcff302aaf131ff39ef9f510ac2dd2c5516e4fcc8ac0ef226fffdf957f08923fd3c55f8ac07dcb3553f64ed13"
            + "f4cdf16be346ff51b1692ba6f20d9ff54cf419fe03d3fd72553ce2fae611d5f1df563cb739eb4639e3dcf2af0772bc78"
            + "0dc5f095fa79f51244f00822e64cc469ba0705393bea52a6184225e23b339fa6a815f061f934fdaf0447e19d61e215e1"
            + "53a7fe50f986e50efa62c78ae4e102bcd095fd17a18557c2188f0cd32afe0f044af039bda81a3baa1543f508fc36f2f1"
            + "0cb9f1841288df93591ed8205f1aae164d8603dbd14ae77b0f42b30b3304f9b748d11d2a98d7a7eb3fb107de2adf4670"
            + "e481122fc61e49333dad0510245ef96f394bf30267a7d1b31660171d34582724f7449f8006cde5b93197aa070ff0154a"
            + "f5c324b152979aaef3a6f61f50c049cec98d1b4edfbc3dc3a2f62bafcab92e5c3ead0a4647b9d814033d0358fc0e009d"
            + "3840edba1421cc1694d6d4ec546d2bf8442d1db4334afe1c000704d4023d107615c8f3f70394dc7c0505dd0204a18fe5"
            + "54535c8f4aacf4bbc836df0a5b76e7ef32b20bf5db7908bcf402e350b154169c0246fdd9f067013be1a32020924ecf4f"
            + "35c6c4034b05af7032f3b4d10ec1ff4f1f5dfc174594142ae374ef19b950fd94faba3a5439a4fb3bcdedc5b6fddd69f5"
            + "08ba06ac0acc15281f329db50b390e340f9814e0279e530b0486150301fcd6ee0122f9b1045a60d540bf9db0fed6f4f0"
            + "fbade800f882e191f465a5140fb02a2943a5ef0aae0af2c9ee72ff31d921f2091f0b0482e2681fb5c921425698a7946c"
            + "c4c43ee0be34dd4aa3583e22615a1630f8ae01a400840075fe06b492ff3a019cfec902f0f88e0f8de1f840b6b4a5c67e"
            + "fe71fd27f121ef9ccf951dd70d28091a23845c86d7cef957f3b40a24e0ce390aed3940f31f7905c90031433561251d32"
            + "b498fdff2e81092a15d40d25ec39aacc2c6a2a901311010512ba506113f5e877e08ffa0f1fbdc65c509fc5ba5d85f1be"
            + "30a305650e1d21efa23e12f01a462993276662819db9fbd719a136990b5f54e940519a3ef9100a0fb36abe600bd81a17"
            + "3aa4ba0adf0aabce96192e200d78fc64c1d7fb911406af7b1e2808b24437153a710e449004de3cfed221602abb7d0cc8"
            + "0c8f461e0adffd2ea770175be9d2f3909a1965b219b70ce64f56f21df565fe44fa3105f6af60aa2ed0519b3f229ffbf4"
            + "45e0023ac11a2089f6073babf58b26def8a9405dce268eb1ff88f75300db0061016d002304f6fd3205c8f57f078ae299"
            + "0768222e0772473bce6ce7e2f16b3591d966c1a8faa0e416d69821303e5fdda81d6c4fd70be1cb6f040896b8169b6198"
            + "ee12dfe4db96e820bca56491ba701b3a0ea80272ff8e08821161ed021b8eeae41282f4f5133afd7549fbd9760350075e"
            + "feb0eade1c424fdcda91fda8030db3eece9819ea058601c2f2b9be7e2b633390ea86549ff33f12fbe8b71d6ad5ab6db6"
            + "286ecd9b64636428fd81015f048b494beaa6c511ff6ffa9fc7736a5d8569807353bff4b23c3c498756704bc658373513"
            + "d64e29d613e1ed6c038daee8cd6caf4c1cf20aa20d632d41fbaf47c64d13da4e57aa4cb2fed8e572c6ab54634d545397"
            + "b46ae2b363663358218c9d2e0c14d68651a02421f3020704fcd505a90c22128c2f29c84aaf1037c3ef149b12e96bad63"
            + "ebf4293ac93ca97a0b18fdd663bd44f03a26adae099b6236db66f904cdc2e9129b2dd4f11a2a0333284900a66bbd020b"
            + "0065b4440d5525a600400326f54ab9f5f5f05922216904660b9c3b630700635ae9a0bc8ffa750644112e2cbc06c35ceb"
            + "ebf01211d6636d4d26a9f632d6e0927f0bb7fa06fcc0fcc2d483cf2156bee3b5a3e6ab3e4227aa7c0745da7a24d84a52"
            + "2825252c68bf07daecb1dc8815abf75e37bec43cb48c071eed0efcf1dd01f3fcb1a8f3832028f516baa833fc0c9dfc21"
            + "d033e64b284bdab008d4af58e4a81599e27fe2bed79ad7e60e394c17e8acb567b77632050503efbc106690c7f63e074a"
            + "3eaf68cacd03e75403d9f9c3fe6d3570193961ee69f4af1ab96af9029e661741fc4667e8a160c3e960d407a1fb9000bb"
            + "f70f30d9aefefc784794530a0a62e8043f335704fdd4086ae839367e9bae93bf0fd1ed45ac3467694bebdc5c037f012f"
            + "a94899bfe8766099a672dcbac83cc1925cb4a6bd2434f0ff732a55a3e7bb068ff7f5fba0fe4d264af0cd3047ef40b5e5"
            + "4d38ffaa09a307c6fc03eb1651faddb1eb2fa3f6ed860a71ec1915e5edeb4ace65b5d01d03cc1aca11c76d2df047f720"
            + "17bbf344ec83fe8bf9ddf16ee3a8cd40dd8cec0b5a0e13be03980a371ee8e347ecd74edaff06154e0c441b10b6ddf7fd"
            + "d7c5eeec4c98130ffd6bf8a339e0de65b29917f7ad26371cd157f751139a2e7458d50196cc80f5cbcc38a85fcf3edf44"
            + "42aa62b3f71f13c0feaa009120d1baed4aa82977b40342bb5155e9bc300a9c0228971e0c11c63da343bab44ded6004b6"
            + "e1d52a5495e4d3511ab3f91009ee0c7f115a4469f181fc6e51e0be7ae94a2b4fffba59b10ce90b671870ed40ae1af362"
            + "1724bf5d08870aad0d76a4f6e853ff3ec9e4d5254c2004051173e8b4b5c405effe990357f9d3e2495636d2c4d8d042ce"
            + "cf8409f910e457e416772f8a9dd9464ce710049c049e25965ba6dee9eed8f5931dd6be3dea79f4b9d5fbae6d1c4e041d"
            + "0a8faf86e27e1d5ce1c416ec50be558d01c93a79bb07d16f0e13f9c5f77fff63ffd5025d09d122fa291f581fc11cc157"
            + "177213571a8bed02a88049a11da6f9639f90f2b4375904beeed2e5f9e52ad89d9fec242528e44557e1bc0093e7561143"
            + "3f3bbf53efe910ce1dc915210ce70aaf1d22b242f732f18af7e354693a163101e83ff91c12462ddc0b2b1b29077ff0e1"
            + "0dc2aaa3f65bd72b49cdd60a0eafd831ecfef59dba59fb263a8f24872e5ef9dbed105815252595f029ee517399b7ba2a"
            + "e3fefa90b3d931ca2006f83c075b6dfefcb2e3bd00f900e9e3e0029dfe8df47c5ac2e9fd0c4501200c97fb16ff9e9429"
            + "43dda53d13f6d441f5f2d321ecc005eeeab0029eb89a079f285eb267edd70169ff60fc65492c37b8f3ade2c3f3001747"
            + "f1e252551c6c0dd01fb9fa08df1a01f4b51249f16718fbf13d17644420b7076f0799d135f5640d3d68e2ee15070b0016"
            + "0499fd7104d1f7b01ea406e7fd072011b4a6ee0f0783fea9fd4ff236f33df124f53f4886f7c207aafab741030acda5c2"
            + "fe4f1329012e32d83e3de8ef0c83101e2badea88f61ffb78fbbde6bbfa791632fef40247db43b38c1848067a03e1ffb5"
            + "f961ee68f70ff008e664bf3f1298fc84d56a19745e87e885ff0303e8003fffafff8dfe82facb5ea0fd46edc5f50fb538"
            + "fc948f3eaa8f3185e7380ca341cf529999c4f391fe7400e64778e192cdc7fd59fa3f0005d7082ca564cdfb9e0579fe4a"
            + "0ec6e2fb6860449f4b3930fe18bcfd1631f52464a7f2eb160d5aa7386962477f043403bc954df454039800eb08b90051"
            + "183714b03edd39b0df13fba8e6e04b2c26c1f34b04fefc465e950801a66d0a19f696ef88244637cab2e9f06ff6d80404"
            + "b1604649db0e59e4bda921b1e510af060eb2440757454a50034a5e7561e6e931ffb203a9fd934d0aa174f856c5faffc8"
            + "58eeec0143d55d3cb3e8e662f7924452ac450d0ccdedb0b9da6b43ad02cb08d9efe5fe1423c432936aa7ad49e848db0f"
            + "cc94a51bc94aefa81b42000203aacbbb0dc0a11759764c85ecd1b2c24d34dba2ce960eebeaaaef67e4b5e78cc9899dc2"
            + "247d2881c9dae5d0581cfdf919fb4950ed09311a398ad81ffcc946c7018ef9d2ff8cfe95e4e9ce6a911821681b31ff11"
            + "f5d6eda0fc0307df14095c76cef1e0029e3c48703763067f0ee5522cda6cec45f8f8fbc1a9d741233a7024f30ae2425f"
            + "9a48b48ae6f2045016a6b989f93760f928b1d4b10380eb67f8c12d8df50d60e9ac45b2b0a44fbea7e96a160b0a4c134c"
            + "f944112497cfca81294a9ad93bfeb3d86682b7c306c81f761634519a0ffbb564c704d71c436cc05d3a0bbad1b51a3093"
            + "95cfcee31a57fdce03d0feff306bde56a918b27d2b051e520ed72e4d941adee70441fa29102df77a97a0fd21fcfa05bd"
            + "0c3535c211fe72494953d91abbc7db1bbb66f61ee6ddf172f93210ffe547b2c3259bd6621c530dc52a5315e1626ea4cc"
            + "d7c4ba5a02772d7807fcae72fc97decafbd9c2c6d63b3a56c1ab6de9149401ddfbe30486fa29dd92e97c9e7b65841ee3"
            + "fbf2ff8ef6fcfad9e6b005c0131fba179b0614b5ff44062d0c80434910fa5655b791560cd7f60221d54c08e4925a1fb6"
            + "3bef0919246450393a3c521d18b917f2a0440345de43e92c1cdafe0b29074ea32cabed6df5475e6edbc63ba9df3be935"
            + "0bb0f4d017a0e2cf2da7b1e4fc8dd14ed908aabbeeac95d60d824caa05000a254d891487eb3d4abdc74add0e35b5fab8"
            + "48d244f72af91aa1b80e18c0f95f08c4ede00f6ccda6eb674fcc292e104afc0c4bef54bbf481b2e9ef900528038ddd3f"
            + "2487e07ef5c6cd7b67d60db325e9a79c20771fe7cc1315e80c960ea5fa1c00a5ffccff3c0066a728dd800387d363c6ba"
            + "ff88176e4d3534590e2c144d215016c3fbd60306ffd9ff5a24c3dafc256dcd345f88614445d608bbab794ffe126ce3ea"
            + "f64e2527064baa493796faf72448f70d5aaff284d5a6000b25180be1140af0cead1da7c337b6d9924ee336c3005bbcd0"
            + "b76103d4001103350078fdc22ffdb4bb35ae3ff5ff5f1789f2dc05faf05b0996d588a2e1006913ddfefc169efdb44ae2"
            + "10191049347f393451a31d0aff51332df1885ac10f43277ae82b5bab1454fac3063f3376f36ff25a3b0ddf3dd20eed72"
            + "047a1243b44e3a45ec1d00f9abfe2798bfa7cc0747cede670274098f0d100c3aec05007745eca86abb1f55cfc05be204"
            + "41df5e9615ecf0eefcd70eeef70d276bf6c89debfb3601380b8d2bf86879cc2ef281fb98b2cef56c11fc18d30666639d"
            + "b377e1b70c57ffabfe17f8c1032e30de4a85edb7f5cefa3ea490b5ad1fc94da61ee8fee60347b33c2e976a8ef37508da"
            + "0fb4fbaa2022fb0651ba61e467d00145de0bff18f756fd45d3e3ef98070de5efa664fac5f82bc1f2fbe993d9cc4d3822"
            + "a9c7079d3377c2d8f8ca1f770bdf2ef91bdc9fc8019df6d5a210ff3230abe602fe5fd8954703a378afddbff41c3e02fb"
            + "161bec233636a34fd4bbb37d2c4c01f561d01dc0b336064597e622ae293001a105130105387c2c69f3412706200246bf"
            + "054bae9adc14c144de91fd26f882ae37b88bf663f5a510dcf5065fc9d50c9b870134fb2edc8dbc80f8d78d62a16bbf09"
            + "f4e527b5eb25f4b85562aca4c2283a01a31c14402781af61b3b1d39f20dd05cea396e981e2a80403aec635a44db4aa52"
            + "d09ce492b519dd78566dbf960791145ae752a31433552b4aff331794fe8421d2ff176d74ea6d1d351dd35c2b2623f5e2"
            + "549a9167f3f2fed4fbf806d0a8b0410600d01a09bc08f42c48322478f54fb4540197eedbeccfbc264983bb0a34683b80"
            + "1e4518e05a5fb9021da0ef68fa2fe685024ad8533a7463e00f04e7f41321cd0ba802160fded5f7c79f3a0389db9205b0"
            + "f420fa3c048eeeb42be423f40d45fa55351ec21f5fdc16bb2123f44f542bbdec1e3d5dd2c5aca332eb2ce5f8ee6f33d3"
            + "4bb33274f7a2fd1f526ca9ab0d41edeb1667b61fe4c70a7f047cc0edac479241fd8ac78f1c8402a04862bbd4d85b015f"
            + "2c5cd522433c12100091457ffd39f269f7423e0f07eb000092700702fdaff53aaaa42d0fb31d134955f45413f3b406fe"
            + "032d20270a492ecdf41d56b922f89f48fd4e3a19f6c2eb0420d6eac1feeefd7eff6f030ae6331c5a512ca42db73f58fe"
            + "a6909c702724f9b205e4fa8f1db701979f9abffff8f4eda5d6a0b53d28def15d2211e4f932d214ace7aaececae58f8fb"
            + "41fbca36fe2f4b8fd60bcd616269c631e9cffdf7febffb451b05f3eb4ed796e9d106050f013107c84c01fc270019fdf7"
            + "1a33f18e20adde1155a195e2123c176d1bcd2db05f51d5d602e8db384db507ab1ef2d9a00d665322f9382a5c22756987"
            + "dd9305f1a21a06731e9efb480f47d42b0cc9cf031c0047e2548a239dd2f0eb781ba5094e00640ee9e5c704dc05eefebf"
            + "1f0ab71229abecfe02d70308c1f5e02af7d958d3061ff266111cf5512115e48fd3600525695a11291df14499fd36028a"
            + "c0c1fcbd20ad0703c8163fa91198d8c01dee97bebbec0a140030f0700234e9110a62b71e31239a60c2e60a70fabdfc89"
            + "ecaa1070e5650a09af73de2ef8d4c61eea3da4e6c630650b153a9215f6cbf4bdfdc6097f3328f52d61a2cf309f6dfbff"
            + "e9d4ef0d077448c4acb543d66c0c9307c3cf059ce438f73f1f530f075ff8fe2b25ca29bbfc79d85b0709acf412bbddd1"
            + "0462da920a41590703bc03721ec44a83d954a1361d48243d03d49774eaf61514043e067070a6fb0afe410005fe53ffec"
            + "c44d17f4591c04e4d91501ff03531ef5fe37d04e10a51d9bee4e2104fb2238a59e89e980ba6ad619269fa287cb880756"
            + "c53727b5406bc6f5d240ad5cf30b0348e9cd578d07ca024a5a76e964c53dd77cdbc9cb2dfcfbdadbf067a138210f16ac"
            + "de9ffd41cf68f06f9dde920dbeed3e810aba064b13e9fbed0029e3f34dbf7b4382133667e9e6034dce1a16494137ffaa"
            + "14a23a1b699252843da0d71339784cc0d321cbcfb11cc4832195dc4efd8e2a8be88118cafa30fb08fa39fae9f188ea93"
            + "f2d645cfe6346162651ef3c920e06c87fa9714e6ef5c4e191638016a435e0ee1f3520440ff978c590abb3b77ff590e8a"
            + "0daef385219a1e5cf9e2fc6dfe150cb9f6891592507eff9cc984d398c3f1aa96deb82fbdfd980978f819112ef1231fac"
            + "e3dc523352dbdb4db4410380e997c4c8acce42aafc12fe9218750ca8d15fc0abc23419b5f3ad60dc0aadfb17fc95f9c3"
            + "b00b2b565e07dce5373808acc8e62eb7a8211027fbe1ead40321f5a1003ceb34fcea1731e334f91ca85f9a3454cb1052"
            + "e9ade608c5c4052da21405d5e878cf385d7a0b86064104954a7b44de4609d6622ab0eca20c9ff32c6ac8104ef96d01f1";

    /**
     * The decoded vector tables by high-efficiency bit allocation pointer 1 to 7: six fractions
     * per vector.
     */
    private static final float[][] VECTORS = {null, decode(HEBAP_1), decode(HEBAP_2), decode(HEBAP_3),
            decode(HEBAP_4), decode(HEBAP_5), decode(HEBAP_6), decode(HEBAP_7)};

    /**
     * The inverse DCT matrix: {@code IDCT[m * 6 + j] = sqrt(2) R_j cos(j (2m + 1) pi / 12)} with
     * {@code R_0 = 1 / sqrt(2)} and {@code R_j = 1} otherwise (A/52 Annex E section 3.4.5).
     */
    private static final float[] IDCT = idct();

    /**
     * Prevents instantiation.
     */
    private Aht() {
        throw new AssertionError();
    }

    /**
     * Decodes a hex-encoded vector table.
     *
     * @param hex the table, four hex digits per 16-bit two's complement value
     * @return the values as fractions of {@code 2^15}
     */
    private static float[] decode(String hex) {
        var values = new float[hex.length() / 4];
        for (var i = 0; i < values.length; i++) {
            values[i] = (short) Integer.parseInt(hex, 4 * i, 4 * i + 4, 16) * 0x1p-15f;
        }
        return values;
    }

    /**
     * Computes the inverse DCT matrix.
     *
     * @return the 36 coefficients, by output block then transform index
     */
    private static float[] idct() {
        var matrix = new float[36];
        for (var m = 0; m < 6; m++) {
            for (var j = 0; j < 6; j++) {
                var weight = j == 0 ? Math.sqrt(0.5) : 1.0;
                matrix[m * 6 + j] = (float) (Math.sqrt(2) * weight * StrictMath.cos(j * (2 * m + 1) * Math.PI / 12));
            }
        }
        return matrix;
    }

    /**
     * Returns the six values of one vector of a vector quantizer.
     *
     * @param hebap the high-efficiency bit allocation pointer, from 1 to 7
     * @param index the vector index, below {@code 2^VQ_BITS[hebap]}
     * @param out receives the six fractions at {@code out[0..5]}
     */
    public static void vector(int hebap, int index, float[] out) {
        var table = VECTORS[hebap];
        var base = index * 6;
        for (var n = 0; n < 6; n++) {
            out[n] = table[base + n];
        }
    }

    /**
     * Applies the inverse DCT to the six transform-domain mantissas of one bin.
     *
     * @param in the six mantissas {@code X(k, j)}
     * @param out receives the six block mantissas {@code C(k, m)}
     */
    public static void inverse(float[] in, float[] out) {
        for (var m = 0; m < 6; m++) {
            var sum = 0.0f;
            for (var j = 0; j < 6; j++) {
                sum += IDCT[m * 6 + j] * in[j];
            }
            out[m] = sum;
        }
    }

    /**
     * Remaps a large mantissa codeword or a gain-1 codeword of the gain-adaptive quantizer (A/52
     * Annex E table E3.6): {@code y = x + a x + b}.
     *
     * @param hebap the high-efficiency bit allocation pointer, from 8 to 19
     * @param gain the gain index: 0 for gain 1, 1 for gain 2, 2 for gain 4
     * @param x the codeword as a fraction
     * @return the reconstructed mantissa
     */
    public static float remap(int hebap, int gain, float x) {
        var row = Ac3Tables.GAQ_REMAP[hebap - 8];
        var a = (short) row[gain] * 0x1p-15f;
        var b = gain == 0 ? 0.0f : (short) row[x >= 0 ? 2 + gain : 4 + gain] * 0x1p-15f;
        return x + a * x + b;
    }
}

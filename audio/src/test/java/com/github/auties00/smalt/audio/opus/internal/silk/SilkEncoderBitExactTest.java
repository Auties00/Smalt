package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the SILK encoder output to golden SHA-256 hashes produced by the pure-C libopus 1.5.2
 * reference (float build, no SIMD) through {@code ref/harness-silkenc/silkenc.c}: the Java encoder
 * must be bit-exact with libopus for every configuration. The input is synthesized
 * deterministically by {@link SilkEncoderHarness#speechLike(int, int, double, long)}.
 */
class SilkEncoderBitExactTest {
    private static SilkEncoderHarness.Config c(int apiFs, int chApi, int chInt, int maxInt, int minInt, int desiredInt,
                                               int payloadMs, int bitrate, int complexity, int useCbr, int useDtx,
                                               int useFec, int loss, int prefill, int activity) {
        return new SilkEncoderHarness.Config(apiFs, chApi, chInt, maxInt, minInt, desiredInt, payloadMs, bitrate,
                complexity, useCbr, useDtx, useFec, loss, prefill, activity, 0, 0);
    }

    private static SilkEncoderHarness.Config d(SilkEncoderHarness.Config base, int schedule, int signal) {
        return new SilkEncoderHarness.Config(base.apiFs(), base.chApi(), base.chInt(), base.maxInt(), base.minInt(),
                base.desiredInt(), base.payloadMs(), base.bitrate(), base.complexity(), base.useCbr(), base.useDtx(),
                base.useFec(), base.loss(), base.prefillFirst(), base.activityMode(), schedule, signal);
    }

    static final List<SilkEncoderHarness.Config> CONFIGS = buildConfigs();

    private static List<SilkEncoderHarness.Config> buildConfigs() {
        var list = new ArrayList<SilkEncoderHarness.Config>();
        list.add(c(16000, 1, 1, 16000, 8000, 16000, 20, 16000, 10, 0, 0, 0, 0, 0, 0));
        list.add(c(16000, 1, 1, 16000, 8000, 16000, 20, 16000, 10, 0, 0, 0, 0, 0, 1));
        for (var complexity = 0; complexity <= 9; complexity++) {
            list.add(c(16000, 1, 1, 16000, 8000, 16000, 20, 16000, complexity, 0, 0, 0, 0, 0, 0));
        }
        list.add(c(8000, 1, 1, 8000, 8000, 8000, 20, 8000, 10, 0, 0, 0, 0, 0, 0));
        list.add(c(12000, 1, 1, 12000, 8000, 12000, 20, 12000, 5, 0, 0, 0, 0, 0, 0));
        list.add(c(16000, 1, 1, 16000, 8000, 16000, 10, 16000, 10, 0, 0, 0, 0, 0, 0));
        list.add(c(16000, 1, 1, 16000, 8000, 16000, 40, 16000, 10, 0, 0, 0, 0, 0, 0));
        list.add(c(16000, 1, 1, 16000, 8000, 16000, 60, 16000, 10, 0, 0, 0, 0, 0, 0));
        list.add(c(12000, 1, 1, 12000, 8000, 12000, 10, 10000, 7, 0, 0, 0, 0, 0, 0));
        list.add(c(24000, 1, 1, 16000, 8000, 16000, 40, 20000, 8, 0, 0, 0, 0, 0, 0));
        list.add(c(24000, 1, 1, 16000, 8000, 12000, 20, 14000, 10, 0, 0, 0, 0, 0, 0));
        list.add(c(24000, 2, 2, 16000, 8000, 16000, 20, 24000, 6, 0, 0, 0, 0, 0, 0));
        list.add(c(48000, 1, 1, 16000, 8000, 16000, 20, 24000, 8, 0, 0, 0, 0, 0, 0));
        list.add(c(48000, 2, 2, 16000, 8000, 16000, 20, 32000, 10, 0, 0, 0, 0, 0, 0));
        list.add(c(48000, 2, 1, 16000, 8000, 16000, 20, 20000, 10, 0, 0, 0, 0, 0, 0));
        list.add(c(16000, 2, 2, 16000, 8000, 16000, 40, 24000, 4, 0, 0, 0, 0, 0, 0));
        list.add(c(16000, 1, 1, 16000, 8000, 16000, 20, 16000, 10, 1, 0, 0, 0, 0, 0));
        list.add(c(16000, 1, 1, 16000, 8000, 16000, 20, 6000, 10, 0, 0, 0, 0, 0, 0));
        list.add(c(16000, 1, 1, 16000, 8000, 16000, 20, 40000, 10, 0, 0, 0, 0, 0, 0));
        list.add(c(16000, 1, 1, 16000, 8000, 16000, 20, 16000, 10, 0, 1, 0, 0, 0, 1));
        list.add(c(16000, 1, 1, 16000, 8000, 16000, 20, 20000, 10, 0, 0, 1, 10, 0, 0));
        list.add(c(48000, 2, 2, 16000, 8000, 16000, 60, 40000, 9, 0, 0, 1, 20, 0, 0));
        list.add(c(16000, 1, 1, 16000, 8000, 16000, 20, 16000, 10, 0, 0, 0, 0, 1, 0));
        list.add(c(8000, 1, 1, 8000, 8000, 8000, 60, 7000, 2, 1, 0, 0, 0, 0, 0));
        var voiceNote = c(16000, 1, 1, 16000, 8000, 16000, 20, 16000, 10, 0, 0, 0, 0, 0, 0);
        for (var signal = 1; signal <= 4; signal++) {
            list.add(d(voiceNote, 0, signal));
        }
        list.add(d(c(48000, 2, 2, 16000, 8000, 16000, 20, 24000, 10, 0, 0, 1, 5, 0, 1), 1, 0));
        list.add(d(c(16000, 1, 1, 16000, 8000, 16000, 20, 16000, 8, 0, 1, 1, 10, 0, 1), 1, 0));
        list.add(d(c(48000, 2, 2, 16000, 8000, 16000, 40, 32000, 7, 1, 0, 0, 0, 0, 0), 1, 1));
        list.add(d(c(24000, 1, 1, 16000, 8000, 16000, 10, 20000, 3, 0, 0, 0, 0, 0, 0), 1, 4));
        list.add(d(c(48000, 2, 1, 16000, 8000, 16000, 20, 12000, 10, 0, 0, 0, 0, 0, 0), 0, 2));
        list.add(d(c(8000, 1, 1, 8000, 8000, 8000, 20, 6000, 10, 1, 0, 0, 0, 0, 0), 0, 3));
        for (var signal = 5; signal <= 8; signal++) {
            list.add(d(voiceNote, 0, signal));
        }
        list.add(d(c(48000, 2, 2, 16000, 8000, 16000, 20, 32000, 10, 0, 1, 1, 10, 0, 0), 0, 5));
        list.add(d(c(24000, 1, 1, 16000, 8000, 16000, 60, 12000, 10, 1, 0, 0, 0, 0, 0), 0, 7));
        list.add(d(c(16000, 1, 1, 16000, 8000, 16000, 20, 16000, 10, 0, 1, 0, 0, 0, 0), 0, 8));
        return List.copyOf(list);
    }

    /** Golden SHA-256 of the C reference output, keyed by {@link SilkEncoderHarness.Config#name()}. */
    private static final Map<String, String> GOLDEN = Map.ofEntries(
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10_sig5",
                    "977e2f91ce1d13e296054be8719d38474b32e0a39633455cd8c8fd3e48cb8885"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10_sig6",
                    "2bd3e791c5c4b52b59cac261e12577e6530471713651489492a71a86b89674a8"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10_sig7",
                    "353d2bd24de4a27f302952ff6fc3a8753a4576f44d4acdb422dd5864be8d98bb"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10_sig8",
                    "615f74410b0600184c5d2a93939c12714670df0ecb5a6cb37a59a194ff41446b"),
            Map.entry("48000Hz_2x2_int16000-8000-16000_20ms_32000bps_c10_dtx_fec10_sig5",
                    "2e7233abd90e858e74b80f54c6ffa18a63f374da920434c309c9960c564db79b"),
            Map.entry("24000Hz_1x1_int16000-8000-16000_60ms_12000bps_c10_cbr_sig7",
                    "879457787adb72309464d95d2066e688b9274061dffe433eb74c2c43873fba51"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10_dtx_sig8",
                    "c02a884511592aea37e5e11905573ef92f276089d396dae137c88602c6b3b15b"),
            Map.entry("12000Hz_1x1_int12000-8000-12000_10ms_10000bps_c7",
                    "88eede46faf1e3ea32e0d7e0f15adf2414b3142bc995c597e98c4b31c1418090"),
            Map.entry("12000Hz_1x1_int12000-8000-12000_20ms_12000bps_c5",
                    "7aee487573ed1504d399663c9fbe550db438eefbdb942c95c9bb5029c3a4f03d"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_10ms_16000bps_c10",
                    "0c8881e6479416e9905dc1575b04ee968106e953844b857ffcfdd2ebdc343d9c"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c0",
                    "8dca6c571f5b2967b51cf89e9a815ad45b8af07ef0afdfcde5d2b4ab4bc6ffe3"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c1",
                    "871305c60f666a1e72487a33e4ae75518afefc4c59503a0dcf7998ca4d9f3f4f"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10",
                    "87d4d5dcf3ca0219210ce7d40701b2f69ae583416296b83057fbdd741032d086"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10_act",
                    "024b302f2f6be7adcaeb9a78c8159590609f321e68f8443b803f5c71fd00fe46"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10_cbr",
                    "0e955a4f4d80167c8ed46d4f7b4cc04749617cad5881c46a331f3d1fa6d91cb6"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10_dtx_act",
                    "ef0a8c6cadd45aca0b6b54f0457bc32dd2c52b8d1b7bf2ada379c65048c38b21"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10_prefill",
                    "ac6cb6397c8eed5919194239e84048b3e9c47f0148260cbaccf272b034255a28"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10_sig1",
                    "7aae0ea3d7a94697700d51e6bf3cd9f6b7bce974278cbf0a23ceea0e2e860113"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10_sig2",
                    "61664598ac3e58d4ba11c8d0b4ef88e2e2fdaa1ba021dfa95ce8423102577223"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10_sig3",
                    "5bb0c164a5c29bb4f785122aea47e6e73d06aaf117cbb33dc3e0183c45d2fa16"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c10_sig4",
                    "a9b746d21fc33a7855bd96fc8d34a13e5963c1dc43bdaf949651946f117166b4"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c2",
                    "2e3b4feca582d511d99c5cac0c3e3e1be287a8b4f3d4a336e1f58cd3b4ae002c"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c3",
                    "a0c18b01c17775cb1d0e4aa758583a107eac07956087c442e3cf33fbb26bfc6b"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c4",
                    "ca4de7e401ce4005970aca6b97f857f06fa98f3a67f72edfe23aa6c0e20cabcd"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c5",
                    "ca4de7e401ce4005970aca6b97f857f06fa98f3a67f72edfe23aa6c0e20cabcd"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c6",
                    "1c25c59b737c65fed65f1a54ff09cb187431c5ed48678071e7cb2a013a5cc053"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c7",
                    "1c25c59b737c65fed65f1a54ff09cb187431c5ed48678071e7cb2a013a5cc053"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c8",
                    "87d4d5dcf3ca0219210ce7d40701b2f69ae583416296b83057fbdd741032d086"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c8_dtx_fec10_act_sched",
                    "5bc4dfc820c03d09088b50bb9bb9566fb6df56f2254f233b868995f3cc663b02"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_16000bps_c9",
                    "87d4d5dcf3ca0219210ce7d40701b2f69ae583416296b83057fbdd741032d086"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_20000bps_c10_fec10",
                    "aa363e7b97d2caf852d1b3c799a806c151bfe72f119ef4d38796faa4bdc5417c"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_40000bps_c10",
                    "4abdb622c2cd1b93ff0578b5d21114cdac2217665fe794cb9040401fbeeb65a6"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_20ms_6000bps_c10",
                    "50e215a01e9a96820e811c8f9f8a4f8cd8f82c1ba17c30f73ebcc29f3ead90fe"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_40ms_16000bps_c10",
                    "600fe5ad4da6e367cdd122358de812fd4d45397b61b6d960e5e492472cfb55eb"),
            Map.entry("16000Hz_1x1_int16000-8000-16000_60ms_16000bps_c10",
                    "6751e69720133a456e14d0a37d5bae3f0d4a6d073954e101ac1fe944e11146d1"),
            Map.entry("16000Hz_2x2_int16000-8000-16000_40ms_24000bps_c4",
                    "759eb9e4478fa3bd521289dad5ba89496e67fb40b7c11470b2029fa7dc06c0d7"),
            Map.entry("24000Hz_1x1_int16000-8000-12000_20ms_14000bps_c10",
                    "bedaeb678a33a7cc2cf435ef60638170f55acc7072d2d7866f54d1ef5a18c2c4"),
            Map.entry("24000Hz_1x1_int16000-8000-16000_10ms_20000bps_c3_sched_sig4",
                    "f86c776b3d73fdf29def4c72f5f1c800c261e9df9b042202127f8cc9821d6ffb"),
            Map.entry("24000Hz_1x1_int16000-8000-16000_40ms_20000bps_c8",
                    "e9104da2a7ee067c866dad1f68b8e9ba0fea98793007268009c9380cd46c407e"),
            Map.entry("24000Hz_2x2_int16000-8000-16000_20ms_24000bps_c6",
                    "57646a106abbfa1be049863c091028540ddf2c7c7139bc39a0d4499970a95cd9"),
            Map.entry("48000Hz_1x1_int16000-8000-16000_20ms_24000bps_c8",
                    "6960175197e8d38873eeeefb3ef952dfeeea9a97695821c50f8aa42cd8ef0473"),
            Map.entry("48000Hz_2x1_int16000-8000-16000_20ms_12000bps_c10_sig2",
                    "d2d7402f64fa5dad967ac70c3ac88977973ee62ac13b682ad65dc9695e817fd0"),
            Map.entry("48000Hz_2x1_int16000-8000-16000_20ms_20000bps_c10",
                    "6fc5b7b1e7e1f6c15a3705fee87c736bee53ba45481707b2ddc98d2fa74c7fca"),
            Map.entry("48000Hz_2x2_int16000-8000-16000_20ms_24000bps_c10_fec5_act_sched",
                    "ac715966b2ddc1d35b4d5f210ab29cb2966e34045d9c8b9c75314fbf4914d0ef"),
            Map.entry("48000Hz_2x2_int16000-8000-16000_20ms_32000bps_c10",
                    "93da429f8c1f0f4e362dcdf0508ff3e3238f0c96dc81ec71808f345b97b911b7"),
            Map.entry("48000Hz_2x2_int16000-8000-16000_40ms_32000bps_c7_cbr_sched_sig1",
                    "b2562884df9009f17aa06be40be6b3a8686779b94eccbe1c7c76b9f87eb1f51f"),
            Map.entry("48000Hz_2x2_int16000-8000-16000_60ms_40000bps_c9_fec20",
                    "c1030d2fc699107e04b513a3d492bd54fd438f449e2100fa03e602f5144c19be"),
            Map.entry("8000Hz_1x1_int8000-8000-8000_20ms_6000bps_c10_cbr_sig3",
                    "276aa6c806cfd117c4b4a4c6002277dd9eeef9ed866f2ed7bb23842574a9d042"),
            Map.entry("8000Hz_1x1_int8000-8000-8000_20ms_8000bps_c10",
                    "505ede45ca30a84dc69cc1eae78873a86ac180e1f25655b80a284048d0b87d5a"),
            Map.entry("8000Hz_1x1_int8000-8000-8000_60ms_7000bps_c2_cbr",
                    "64e664c35c5af269481daaaf2d2a5ef6127a3ed6bca23604d8ded3f589e3f856"));

    static short[] input(SilkEncoderHarness.Config config) {
        return SilkEncoderHarness.signal(config.signal(), config.apiFs(), config.chApi(), config.schedule() != 0 ? 6.0 : 3.0,
                0x5EED1234L);
    }

    static List<SilkEncoderHarness.Config> configs() {
        return CONFIGS;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configs")
    @DisplayName("encodes bit-exactly like libopus 1.5.2")
    void bitExact(SilkEncoderHarness.Config config) {
        var golden = GOLDEN.get(config.name());
        Skip.unless(golden != null, "input:no-golden", "no golden hash for " + config.name());
        assertEquals(golden, SilkEncoderHarness.sha256(SilkEncoderHarness.encode(input(config), config)));
    }
}

package com.github.auties00.smalt.image.webp.internal.lossless;

/**
 * Computes the fixed-point entropy estimates the lossless encoder uses to compare coding choices.
 *
 * <p>Costs are in bits scaled by {@code 2^23} ({@link #LOG_2_PRECISION_BITS}). {@code log2(v)}
 * and {@code v * log2(v)} are tabulated for {@code v < 256} and approximated with a linear
 * correction up to 65535; larger values use floating point.
 *
 * @implNote This implementation follows libwebp's {@code src/dsp/lossless_enc.c} and
 * {@code src/enc/histogram_enc.c} (BSD-3): the tables {@code kLog2Table} and
 * {@code kSLog2Table}, {@code BitsEntropyRefine}, {@code FinalHuffmanCost} and the streak
 * statistics. The encoder does not need to reproduce libwebp's output bit for bit, but using the
 * same cost model makes it take the same decisions.
 */
public final class EntropyMath {
    /**
     * The number of fractional bits of every cost.
     */
    public static final int LOG_2_PRECISION_BITS = 23;

    /**
     * {@code 2^23 / ln(2)} rounded down, the fixed-point reciprocal of {@code ln(2)}.
     */
    private static final long LOG_2_RECIPROCAL_FIXED = 12102203L;

    /**
     * {@code 2^23 / ln(2)}.
     */
    private static final double LOG_2_RECIPROCAL_FIXED_DOUBLE = 12102203.161561485379934310913085937500;

    /**
     * The bound below which logarithms use the table with a linear correction.
     */
    private static final int APPROX_LOG_WITH_CORRECTION_MAX = 65536;

    /**
     * The bound above which {@link #fastLog2(int)} adds the linear correction.
     */
    private static final int APPROX_LOG_MAX = 4096;

    /**
     * The symbol marking a histogram with more than one used symbol.
     */
    public static final int NON_TRIVIAL_SYM = 0xFFFF;

    /**
     * {@code round(log2(v) * 2^23)} for {@code v} from 0 to 255 (0 for {@code v = 0}), from
     * libwebp's {@code kLog2Table}.
     */
    private static final int[] LOG2 = {
            0, 0, 8388608, 13295629, 16777216, 19477745, 21684237, 23549800,
            25165824, 26591258, 27866353, 29019816, 30072845, 31041538, 31938408, 32773374,
            33554432, 34288123, 34979866, 35634199, 36254961, 36845429, 37408424, 37946388,
            38461453, 38955489, 39430146, 39886887, 40327016, 40751698, 41161982, 41558811,
            41943040, 42315445, 42676731, 43027545, 43368474, 43700062, 44022807, 44337167,
            44643569, 44942404, 45234037, 45518808, 45797032, 46069003, 46334996, 46595268,
            46850061, 47099600, 47344097, 47583753, 47818754, 48049279, 48275495, 48497560,
            48715624, 48929828, 49140306, 49347187, 49550590, 49750631, 49947419, 50141058,
            50331648, 50519283, 50704053, 50886044, 51065339, 51242017, 51416153, 51587818,
            51757082, 51924012, 52088670, 52251118, 52411415, 52569616, 52725775, 52879946,
            53032177, 53182516, 53331012, 53477707, 53622645, 53765868, 53907416, 54047327,
            54185640, 54322389, 54457611, 54591338, 54723604, 54854440, 54983876, 55111943,
            55238669, 55364082, 55488208, 55611074, 55732705, 55853126, 55972361, 56090432,
            56207362, 56323174, 56437887, 56551524, 56664103, 56775645, 56886168, 56995691,
            57104232, 57211808, 57318436, 57424133, 57528914, 57632796, 57735795, 57837923,
            57939198, 58039632, 58139239, 58238033, 58336027, 58433234, 58529666, 58625336,
            58720256, 58814437, 58907891, 59000628, 59092661, 59183999, 59274652, 59364632,
            59453947, 59542609, 59630625, 59718006, 59804761, 59890898, 59976426, 60061354,
            60145690, 60229443, 60312620, 60395229, 60477278, 60558775, 60639726, 60720140,
            60800023, 60879382, 60958224, 61036555, 61114383, 61191714, 61268554, 61344908,
            61420785, 61496188, 61571124, 61645600, 61719620, 61793189, 61866315, 61939001,
            62011253, 62083076, 62154476, 62225457, 62296024, 62366182, 62435935, 62505289,
            62574248, 62642816, 62710997, 62778797, 62846219, 62913267, 62979946, 63046260,
            63112212, 63177807, 63243048, 63307939, 63372484, 63436687, 63500551, 63564080,
            63627277, 63690146, 63752690, 63814912, 63876816, 63938405, 63999682, 64060650,
            64121313, 64181673, 64241734, 64301498, 64360969, 64420148, 64479040, 64537646,
            64595970, 64654014, 64711782, 64769274, 64826495, 64883447, 64940132, 64996553,
            65052711, 65108611, 65164253, 65219641, 65274776, 65329662, 65384299, 65438691,
            65492840, 65546747, 65600416, 65653847, 65707044, 65760008, 65812741, 65865245,
            65917522, 65969575, 66021404, 66073013, 66124403, 66175575, 66226531, 66277275,
            66327806, 66378127, 66428240, 66478146, 66527847, 66577345, 66626641, 66675737,
            66724635, 66773336, 66821842, 66870154, 66918274, 66966204, 67013944, 67061497
    };

    /**
     * {@code round(v * log2(v) * 2^23)} for {@code v} from 0 to 255, from libwebp's
     * {@code kSLog2Table}.
     */
    private static final long[] SLOG2 = {
            0L, 0L, 16777216L, 39886887L, 67108864L, 97388723L,
            130105423L, 164848600L, 201326592L, 239321324L, 278663526L, 319217973L,
            360874141L, 403539997L, 447137711L, 491600606L, 536870912L, 582898099L,
            629637592L, 677049776L, 725099212L, 773754010L, 822985323L, 872766924L,
            923074875L, 973887230L, 1025183802L, 1076945958L, 1129156447L, 1181799249L,
            1234859451L, 1288323135L, 1342177280L, 1396409681L, 1451008871L, 1505964059L,
            1561265072L, 1616902301L, 1672866655L, 1729149526L, 1785742744L, 1842638548L,
            1899829557L, 1957308741L, 2015069397L, 2073105127L, 2131409817L, 2189977618L,
            2248802933L, 2307880396L, 2367204859L, 2426771383L, 2486575220L, 2546611805L,
            2606876748L, 2667365819L, 2728074942L, 2789000187L, 2850137762L, 2911484006L,
            2973035382L, 3034788471L, 3096739966L, 3158886666L, 3221225472L, 3283753383L,
            3346467489L, 3409364969L, 3472443085L, 3535699182L, 3599130679L, 3662735070L,
            3726509920L, 3790452862L, 3854561593L, 3918833872L, 3983267519L, 4047860410L,
            4112610476L, 4177515704L, 4242574127L, 4307783833L, 4373142952L, 4438649662L,
            4504302186L, 4570098787L, 4636037770L, 4702117480L, 4768336298L, 4834692645L,
            4901184974L, 4967811774L, 5034571569L, 5101462912L, 5168484389L, 5235634615L,
            5302912235L, 5370315922L, 5437844376L, 5505496324L, 5573270518L, 5641165737L,
            5709180782L, 5777314477L, 5845565671L, 5913933235L, 5982416059L, 6051013057L,
            6119723161L, 6188545324L, 6257478518L, 6326521733L, 6395673979L, 6464934282L,
            6534301685L, 6603775250L, 6673354052L, 6743037185L, 6812823756L, 6882712890L,
            6952703725L, 7022795412L, 7092987118L, 7163278025L, 7233667324L, 7304154222L,
            7374737939L, 7445417707L, 7516192768L, 7587062379L, 7658025806L, 7729082328L,
            7800231234L, 7871471825L, 7942803410L, 8014225311L, 8085736859L, 8157337394L,
            8229026267L, 8300802839L, 8372666477L, 8444616560L, 8516652476L, 8588773618L,
            8660979393L, 8733269211L, 8805642493L, 8878098667L, 8950637170L, 9023257446L,
            9095958945L, 9168741125L, 9241603454L, 9314545403L, 9387566451L, 9460666086L,
            9533843800L, 9607099093L, 9680431471L, 9753840445L, 9827325535L, 9900886263L,
            9974522161L, 10048232765L, 10122017615L, 10195876260L, 10269808253L, 10343813150L,
            10417890516L, 10492039919L, 10566260934L, 10640553138L, 10714916116L, 10789349456L,
            10863852751L, 10938425600L, 11013067604L, 11087778372L, 11162557513L, 11237404645L,
            11312319387L, 11387301364L, 11462350205L, 11537465541L, 11612647010L, 11687894253L,
            11763206912L, 11838584638L, 11914027082L, 11989533899L, 12065104750L, 12140739296L,
            12216437206L, 12292198148L, 12368021795L, 12443907826L, 12519855920L, 12595865759L,
            12671937032L, 12748069427L, 12824262637L, 12900516358L, 12976830290L, 13053204134L,
            13129637595L, 13206130381L, 13282682202L, 13359292772L, 13435961806L, 13512689025L,
            13589474149L, 13666316903L, 13743217014L, 13820174211L, 13897188225L, 13974258793L,
            14051385649L, 14128568535L, 14205807192L, 14283101363L, 14360450796L, 14437855239L,
            14515314443L, 14592828162L, 14670396151L, 14748018167L, 14825693972L, 14903423326L,
            14981205995L, 15059041743L, 15136930339L, 15214871554L, 15292865160L, 15370910930L,
            15449008641L, 15527158071L, 15605359001L, 15683611210L, 15761914485L, 15840268608L,
            15918673369L, 15997128556L, 16075633960L, 16154189373L, 16232794589L, 16311449405L,
            16390153617L, 16468907026L, 16547709431L, 16626560636L, 16705460444L, 16784408661L,
            16863405094L, 16942449552L, 17021541845L, 17100681785L
    };

    /**
     * The fixed cost of storing a prefix code: 19 code-length codes of 3 bits minus a bias of
     * 9.1 bits.
     */
    private static final long INITIAL_HUFFMAN_COST = ((19L * 3) << LOG_2_PRECISION_BITS)
            - divRound(91L << LOG_2_PRECISION_BITS, 10);

    /**
     * Prevents instantiation.
     */
    private EntropyMath() {
        throw new AssertionError();
    }

    /**
     * Divides rounding half away from zero.
     *
     * @param a the dividend
     * @param b the divisor, non-zero
     * @return the rounded quotient
     */
    public static long divRound(long a, long b) {
        return ((a < 0) == (b < 0)) ? ((a + b / 2) / b) : ((a - b / 2) / b);
    }

    /**
     * Returns {@code floor(log2(v))}.
     *
     * @param v a positive value
     * @return the index of the highest set bit
     */
    public static int bitsLog2Floor(int v) {
        return 31 - Integer.numberOfLeadingZeros(v);
    }

    /**
     * Returns {@code log2(v) * 2^23}, approximately.
     *
     * @param v a non-negative value
     * @return the fixed-point logarithm, {@code 0} for {@code v <= 1}
     */
    public static int fastLog2(int v) {
        if (v < 256) {
            return LOG2[v];
        }
        if (v < APPROX_LOG_WITH_CORRECTION_MAX) {
            var origV = v;
            var logCnt = bitsLog2Floor(v) - 7;
            var y = 1 << logCnt;
            v >>>= logCnt;
            var log2 = LOG2[v] + (logCnt << LOG_2_PRECISION_BITS);
            if (origV >= APPROX_LOG_MAX) {
                var correction = LOG_2_RECIPROCAL_FIXED * (origV & (y - 1));
                log2 += (int) divRound(correction, origV);
            }
            return log2;
        }
        return (int) (LOG_2_RECIPROCAL_FIXED_DOUBLE * StrictMath.log(v & 0xFFFFFFFFL) + 0.5);
    }

    /**
     * Returns {@code v * log2(v) * 2^23}, approximately.
     *
     * @param v a non-negative value
     * @return the fixed-point value, {@code 0} for {@code v <= 1}
     */
    public static long fastSLog2(int v) {
        if (v < 256) {
            return SLOG2[v];
        }
        if (v < APPROX_LOG_WITH_CORRECTION_MAX) {
            long origV = v;
            var logCnt = bitsLog2Floor(v) - 7;
            var y = 1 << logCnt;
            v >>>= logCnt;
            var correction = LOG_2_RECIPROCAL_FIXED * (origV & (y - 1));
            return origV * (LOG2[v] + ((long) logCnt << LOG_2_PRECISION_BITS)) + correction;
        }
        var value = v & 0xFFFFFFFFL;
        return (long) (LOG_2_RECIPROCAL_FIXED_DOUBLE * value * StrictMath.log(value) + 0.5);
    }

    /**
     * Returns the Shannon entropy estimate of a distribution: {@code S(sum) - sum S(x)}, where
     * {@code S(v) = v log2 v}.
     *
     * @param x      the counts
     * @param offset the index of the first count
     * @param n      the number of counts
     * @return the fixed-point entropy in bits
     */
    public static long shannonEntropy(int[] x, int offset, int n) {
        long retval = 0;
        var sumX = 0;
        for (var i = 0; i < n; i++) {
            var value = x[offset + i];
            if (value != 0) {
                sumX += value;
                retval += fastSLog2(value);
            }
        }
        return fastSLog2(sumX) - retval;
    }

    /**
     * Returns the entropy of {@code X} plus the entropy of {@code X + Y}, for 256-symbol
     * distributions.
     *
     * @param x    the first counts
     * @param xOff the index of the first count of {@code x}
     * @param y    the second counts
     * @param yOff the index of the first count of {@code y}
     * @return the fixed-point combined entropy
     */
    public static long combinedShannonEntropy(int[] x, int xOff, int[] y, int yOff) {
        long retval = 0;
        var sumX = 0;
        var sumXY = 0;
        for (var i = 0; i < 256; i++) {
            var xv = x[xOff + i];
            if (xv != 0) {
                var xy = xv + y[yOff + i];
                sumX += xv;
                retval += fastSLog2(xv);
                sumXY += xy;
                retval += fastSLog2(xy);
            } else if (y[yOff + i] != 0) {
                sumXY += y[yOff + i];
                retval += fastSLog2(y[yOff + i]);
            }
        }
        return fastSLog2(sumX) + fastSLog2(sumXY) - retval;
    }

    /**
     * Returns the cost of the extra bits of the length or distance prefix codes of a histogram.
     *
     * @param population the prefix code counts
     * @param offset     the index of the first count
     * @param length     the number of prefix codes, even
     * @return the number of extra bits (not fixed point)
     */
    public static long extraCost(int[] population, int offset, int length) {
        long cost = population[offset + 4] + population[offset + 5];
        for (var i = 2; i < length / 2 - 1; i++) {
            cost += (long) i * (population[offset + 2 * i + 2] + population[offset + 2 * i + 3]);
        }
        return cost;
    }

    /**
     * Refines a raw entropy estimate to account for the minimum cost of prefix codes.
     *
     * @param stats the statistics of the distribution
     * @return the refined fixed-point cost
     */
    static long bitsEntropyRefine(Stats stats) {
        long mix;
        if (stats.nonzeros < 5) {
            if (stats.nonzeros <= 1) {
                return 0;
            }
            if (stats.nonzeros == 2) {
                return divRound(99 * ((long) stats.sum << LOG_2_PRECISION_BITS) + stats.entropy, 100);
            }
            mix = stats.nonzeros == 3 ? 950 : 700;
        } else {
            mix = 627;
        }
        var minLimit = (2L * stats.sum - stats.maxVal) << LOG_2_PRECISION_BITS;
        minLimit = divRound(mix * minLimit + (1000 - mix) * stats.entropy, 1000);
        return Math.max(stats.entropy, minLimit);
    }

    /**
     * Returns the refined entropy of a distribution.
     *
     * @param array  the counts
     * @param offset the index of the first count
     * @param n      the number of counts
     * @return the fixed-point cost
     */
    public static long bitsEntropy(int[] array, int offset, int n) {
        var stats = new Stats();
        stats.reset();
        for (var i = 0; i < n; i++) {
            var v = array[offset + i];
            if (v != 0) {
                stats.sum += v;
                stats.nonzeroCode = i;
                ++stats.nonzeros;
                stats.entropy += fastSLog2(v);
                if (stats.maxVal < v) {
                    stats.maxVal = v;
                }
            }
        }
        stats.entropy = fastSLog2(stats.sum) - stats.entropy;
        return bitsEntropyRefine(stats);
    }

    /**
     * Returns the estimated cost of storing a prefix code with run-length coded code lengths.
     *
     * @param stats the streak statistics
     * @return the fixed-point cost
     */
    static long finalHuffmanCost(Stats stats) {
        long extra = stats.counts0 * 1600L + 240L * stats.streaks01;
        extra += stats.counts1 * 2640L + 720L * stats.streaks11;
        extra += 1840L * stats.streaks00;
        extra += 3360L * stats.streaks10;
        return INITIAL_HUFFMAN_COST + (extra << (LOG_2_PRECISION_BITS - 10));
    }

    /**
     * Gathers the entropy and streak statistics of one distribution or of the sum of two.
     *
     * @param x      the first counts
     * @param y      the second counts, or {@code null}
     * @param length the number of counts
     * @param stats  the destination
     */
    static void entropyUnrefined(int[] x, int[] y, int length, Stats stats) {
        stats.reset();
        var iPrev = 0;
        var xPrev = y == null ? x[0] : x[0] + y[0];
        int i;
        for (i = 1; i < length; i++) {
            var value = y == null ? x[i] : x[i] + y[i];
            if (value != xPrev) {
                stats.streak(xPrev, i - iPrev, iPrev);
                xPrev = value;
                iPrev = i;
            }
        }
        stats.streak(xPrev, i - iPrev, iPrev);
        stats.entropy = fastSLog2(stats.sum) - stats.entropy;
    }

    /**
     * Returns the estimated cost of a distribution coded with its own prefix code.
     *
     * @param population the counts
     * @param length     the number of counts
     * @param stats      scratch statistics, holding the trivial symbol and usage on return
     * @return the fixed-point cost
     */
    static long populationCost(int[] population, int length, Stats stats) {
        LosslessDsp.INSTANCE.entropyUnrefined(population, null, length, stats);
        return bitsEntropyRefine(stats) + finalHuffmanCost(stats);
    }

    /**
     * Holds the entropy and streak statistics of a distribution.
     */
    static final class Stats {
        /**
         * The raw entropy estimate.
         */
        long entropy;

        /**
         * The sum of the counts.
         */
        int sum;

        /**
         * The number of non-zero counts.
         */
        int nonzeros;

        /**
         * The largest count.
         */
        int maxVal;

        /**
         * The index of the last non-zero count.
         */
        int nonzeroCode;

        /**
         * The number of zero streaks longer than 3.
         */
        int counts0;

        /**
         * The number of non-zero streaks longer than 3.
         */
        int counts1;

        /**
         * The total length of zero streaks of at most 3.
         */
        int streaks00;

        /**
         * The total length of zero streaks longer than 3.
         */
        int streaks01;

        /**
         * The total length of non-zero streaks of at most 3.
         */
        int streaks10;

        /**
         * The total length of non-zero streaks longer than 3.
         */
        int streaks11;

        /**
         * Clears the statistics.
         */
        void reset() {
            entropy = 0;
            sum = 0;
            nonzeros = 0;
            maxVal = 0;
            nonzeroCode = NON_TRIVIAL_SYM;
            counts0 = 0;
            counts1 = 0;
            streaks00 = 0;
            streaks01 = 0;
            streaks10 = 0;
            streaks11 = 0;
        }

        /**
         * Accounts one streak of equal counts.
         *
         * @param value  the repeated count
         * @param streak the streak length
         * @param first  the index of the first count of the streak
         */
        void streak(int value, int streak, int first) {
            if (value != 0) {
                sum += value * streak;
                nonzeros += streak;
                nonzeroCode = first;
                entropy += fastSLog2(value) * streak;
                if (maxVal < value) {
                    maxVal = value;
                }
                if (streak > 3) {
                    ++counts1;
                    streaks11 += streak;
                } else {
                    streaks10 += streak;
                }
            } else if (streak > 3) {
                ++counts0;
                streaks01 += streak;
            } else {
                streaks00 += streak;
            }
        }

        /**
         * Returns whether the distribution has a non-zero count.
         *
         * @return whether a non-zero streak was seen
         */
        boolean used() {
            return streaks10 != 0 || streaks11 != 0;
        }
    }
}

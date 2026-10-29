/* The inverse DCT of Smalt's MPEG video decoders (smalt-mpeg's ScalarMpeg4Dsp.transform), in C, for the build of the
 * ISO/IEC 13818-5 reference decoder that tools/testdata/mpeg2-oracle/build.py makes: it replaces the decoder's own
 * integer transform (idct.c), so that the reference decoder, whose every other step is the normative decoding
 * process, reconstructs the same samples as Smalt.
 *
 * The transform is libjpeg-turbo's accurate integer inverse DCT (jidctint.c, jpeg_idct_islow; Copyright (C)
 * 1991-1998, Thomas G. Lane; libjpeg-turbo Modifications Copyright (C) 2015, 2020, 2022, D. R. Commander; IJG
 * License): the Loeffler-Ligtenberg-Moschytz algorithm with 13-bit constants and two extra bits of precision between
 * the passes, the rows transformed first, without the JPEG level shift and range limit. The shortcuts of Smalt's
 * Java code (zero rows, rows with only a DC term, blocks whose only nonzero row is the first) compute exactly what
 * the general passes below compute, so they are left out.
 *
 * The reference decoder adds the transformed block to the prediction, or to 128 for an intra block (it predicts
 * intra DC terms from 0 instead of from 2^(7 + intra_dc_precision), which shifts the DC coefficient by 1024 and every
 * sample of the exact transform by 128, exactly as it does in this one), through a clipping table that covers -384 to
 * 639; the samples are therefore limited to -384..384, which leaves every reconstructed sample unchanged.
 */

#define CONST_BITS 13
#define PASS1_BITS 2

#define FIX_0_298631336 2446
#define FIX_0_390180644 3196
#define FIX_0_541196100 4433
#define FIX_0_765366865 6270
#define FIX_0_899976223 7373
#define FIX_1_175875602 9633
#define FIX_1_501321110 12299
#define FIX_1_847759065 15137
#define FIX_1_961570560 16069
#define FIX_2_053119869 16819
#define FIX_2_562915447 20995
#define FIX_3_072711026 25172

void Initialize_Fast_IDCT(void)
{
}

/* One pass of the 8-point inverse transform over in[0], in[step], ..., in[7 * step], written to out with the same
 * step, descaled by shift bits with rounding. Multiplications stand for the left shifts of the Java code, which C
 * leaves undefined for negative values. */
static void pass(const int *in, int *out, int step, int shift)
{
  int d0 = in[0], d1 = in[step], d2 = in[2 * step], d3 = in[3 * step];
  int d4 = in[4 * step], d5 = in[5 * step], d6 = in[6 * step], d7 = in[7 * step];
  int z1, z2, z3, z4, z5;
  int tmp0, tmp1, tmp2, tmp3, tmp10, tmp11, tmp12, tmp13;
  int round = 1 << (shift - 1);

  z1 = (d2 + d6) * FIX_0_541196100;
  tmp2 = z1 + d6 * -FIX_1_847759065;
  tmp3 = z1 + d2 * FIX_0_765366865;
  tmp0 = (d0 + d4) * (1 << CONST_BITS);
  tmp1 = (d0 - d4) * (1 << CONST_BITS);
  tmp10 = tmp0 + tmp3;
  tmp13 = tmp0 - tmp3;
  tmp11 = tmp1 + tmp2;
  tmp12 = tmp1 - tmp2;

  z1 = d7 + d1;
  z2 = d5 + d3;
  z3 = d7 + d3;
  z4 = d5 + d1;
  z5 = (z3 + z4) * FIX_1_175875602;
  tmp0 = d7 * FIX_0_298631336;
  tmp1 = d5 * FIX_2_053119869;
  tmp2 = d3 * FIX_3_072711026;
  tmp3 = d1 * FIX_1_501321110;
  z1 = z1 * -FIX_0_899976223;
  z2 = z2 * -FIX_2_562915447;
  z3 = z3 * -FIX_1_961570560 + z5;
  z4 = z4 * -FIX_0_390180644 + z5;
  tmp0 += z1 + z3;
  tmp1 += z2 + z4;
  tmp2 += z2 + z3;
  tmp3 += z1 + z4;

  out[0] = (tmp10 + tmp3 + round) >> shift;
  out[7 * step] = (tmp10 - tmp3 + round) >> shift;
  out[step] = (tmp11 + tmp2 + round) >> shift;
  out[6 * step] = (tmp11 - tmp2 + round) >> shift;
  out[2 * step] = (tmp12 + tmp1 + round) >> shift;
  out[5 * step] = (tmp12 - tmp1 + round) >> shift;
  out[3 * step] = (tmp13 + tmp0 + round) >> shift;
  out[4 * step] = (tmp13 - tmp0 + round) >> shift;
}

/* Transforms a block of coefficients in raster order (horizontal frequency fastest) into samples, in place. */
void Fast_IDCT(short *block)
{
  int coefficients[64], work[64], samples[64];
  int i;

  for (i = 0; i < 64; i++)
    coefficients[i] = block[i];
  for (i = 0; i < 8; i++)
    pass(coefficients + 8 * i, work + 8 * i, 1, CONST_BITS - PASS1_BITS);
  for (i = 0; i < 8; i++)
    pass(work + i, samples + i, 8, CONST_BITS + PASS1_BITS + 3);
  for (i = 0; i < 64; i++)
  {
    int value = samples[i];
    block[i] = (short) (value < -384 ? -384 : value > 384 ? 384 : value);
  }
}

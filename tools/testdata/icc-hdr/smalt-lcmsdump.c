/*
 * Reads and writes high-dynamic-range ICC profiles with Little CMS (2.16 or later, for the cicp tag), for the
 * HDR color management tests of smalt-core.
 *
 *   smalt-lcmsdump info <profile>          prints "version <hex>", "space <signature>" and "cicp <p> <t> <m> <f>"
 *                                          (or "cicp none") as Little CMS reads them
 *   smalt-lcmsdump gray <profile>          transforms the gray ramp (k * 257, k * 257, k * 257), k = 0..255, from
 *                                          the profile to Little CMS's built-in sRGB, 16 bits in and out, perceptual
 *                                          intent, default flags, and prints "k r g b" per line
 *   smalt-lcmsdump lab                     reads "L a b" triplets (D50 CIE L*a*b*) from standard input and prints
 *                                          "r g b" per line: the 16-bit sRGB of Little CMS's built-in profiles,
 *                                          relative colorimetric intent, unoptimized
 *   smalt-lcmsdump write <pq|hlg> <out>    writes a version 4.4 RGB profile with the BT.2020 primaries, a D65 white,
 *                                          tone curves of 1024 entries holding the PQ EOTF over 10000 cd/m2 or the
 *                                          HLG inverse OETF, and a cicp tag (9, 16 or 18, 0, 1)
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lcms2.h"

static double pq_eotf(double e) {
    const double m1 = 2610.0 / 16384.0, m2 = 2523.0 / 4096.0 * 128.0;
    const double c1 = 3424.0 / 4096.0, c2 = 2413.0 / 4096.0 * 32.0, c3 = 2392.0 / 4096.0 * 32.0;
    double p, n;
    if (e <= 0) {
        return 0;
    }
    p = pow(e, 1.0 / m2);
    n = p - c1 > 0 ? p - c1 : 0;
    return pow(n / (c2 - c3 * p), 1.0 / m1);
}

static double hlg_inverse_oetf(double e) {
    const double a = 0.17883277, b = 1 - 4 * a, c = 0.5 - a * log(4 * a);
    if (e <= 0) {
        return 0;
    }
    if (e <= 0.5) {
        return e * e / 3.0;
    }
    return (exp((e - c) / a) + b) / 12.0;
}

static int info(const char *path) {
    cmsHPROFILE profile = cmsOpenProfileFromFile(path, "r");
    cmsVideoSignalType *cicp;
    cmsUInt32Number space;
    if (profile == NULL) {
        fprintf(stderr, "cannot open %s\n", path);
        return 1;
    }
    space = cmsGetColorSpace(profile);
    printf("version %08x\n", (unsigned) cmsGetEncodedICCversion(profile));
    printf("space %c%c%c%c\n", (char) (space >> 24), (char) (space >> 16), (char) (space >> 8), (char) space);
    cicp = (cmsVideoSignalType *) cmsReadTag(profile, cmsSigcicpTag);
    if (cicp != NULL) {
        printf("cicp %u %u %u %u\n", cicp->ColourPrimaries, cicp->TransferCharacteristics, cicp->MatrixCoefficients,
               cicp->VideoFullRangeFlag);
    } else {
        printf("cicp none\n");
    }
    cmsCloseProfile(profile);
    return 0;
}

static int gray(const char *path) {
    cmsHPROFILE profile = cmsOpenProfileFromFile(path, "r");
    cmsHPROFILE srgb = cmsCreate_sRGBProfile();
    cmsHTRANSFORM transform;
    cmsUInt16Number in[256 * 3], out[256 * 3];
    int k;
    if (profile == NULL) {
        fprintf(stderr, "cannot open %s\n", path);
        return 1;
    }
    transform = cmsCreateTransform(profile, TYPE_RGB_16, srgb, TYPE_RGB_16, INTENT_PERCEPTUAL, 0);
    if (transform == NULL) {
        fprintf(stderr, "cannot link %s\n", path);
        return 1;
    }
    for (k = 0; k < 256; k++) {
        in[k * 3] = in[k * 3 + 1] = in[k * 3 + 2] = (cmsUInt16Number) (k * 257);
    }
    cmsDoTransform(transform, in, out, 256);
    for (k = 0; k < 256; k++) {
        printf("%d %u %u %u\n", k, out[k * 3], out[k * 3 + 1], out[k * 3 + 2]);
    }
    cmsDeleteTransform(transform);
    cmsCloseProfile(srgb);
    cmsCloseProfile(profile);
    return 0;
}

static int write_profile(const char *kind, const char *path) {
    cmsCIExyY white = {0.3127, 0.3290, 1.0};
    cmsCIExyYTRIPLE primaries = {{0.708, 0.292, 1.0}, {0.170, 0.797, 1.0}, {0.131, 0.046, 1.0}};
    cmsUInt16Number table[1024];
    cmsToneCurve *curve, *curves[3];
    cmsHPROFILE profile;
    cmsVideoSignalType cicp;
    cmsMLU *description;
    int hlg = strcmp(kind, "hlg") == 0;
    int i;
    for (i = 0; i < 1024; i++) {
        double e = i / 1023.0;
        double v = hlg ? hlg_inverse_oetf(e) : pq_eotf(e);
        table[i] = (cmsUInt16Number) floor(v * 65535.0 + 0.5);
    }
    curve = cmsBuildTabulatedToneCurve16(NULL, 1024, table);
    curves[0] = curves[1] = curves[2] = curve;
    profile = cmsCreateRGBProfile(&white, &primaries, curves);
    if (profile == NULL) {
        return 1;
    }
    cmsSetProfileVersion(profile, 4.4);
    description = cmsMLUalloc(NULL, 1);
    cmsMLUsetASCII(description, "en", "US", hlg ? "Little CMS BT.2100 HLG" : "Little CMS BT.2100 PQ");
    cmsWriteTag(profile, cmsSigProfileDescriptionTag, description);
    cicp.ColourPrimaries = 9;
    cicp.TransferCharacteristics = hlg ? 18 : 16;
    cicp.MatrixCoefficients = 0;
    cicp.VideoFullRangeFlag = 1;
    cmsWriteTag(profile, cmsSigcicpTag, &cicp);
    if (!cmsSaveProfileToFile(profile, path)) {
        return 1;
    }
    cmsMLUfree(description);
    cmsFreeToneCurve(curve);
    cmsCloseProfile(profile);
    {
        /* clear the creation date so that the output depends only on the Little CMS version */
        static const unsigned char zeros[12] = {0};
        FILE *file = fopen(path, "r+b");
        if (file == NULL || fseek(file, 24, SEEK_SET) != 0 || fwrite(zeros, 1, 12, file) != 12) {
            return 1;
        }
        fclose(file);
    }
    return 0;
}

static int lab(void) {
    cmsHPROFILE profile = cmsCreateLab4Profile(NULL);
    cmsHPROFILE srgb = cmsCreate_sRGBProfile();
    cmsHTRANSFORM transform;
    double in[3];
    cmsUInt16Number out[3];
    transform = cmsCreateTransform(profile, TYPE_Lab_DBL, srgb, TYPE_RGB_16, INTENT_RELATIVE_COLORIMETRIC,
                                   cmsFLAGS_NOCACHE | cmsFLAGS_NOOPTIMIZE);
    if (transform == NULL) {
        fprintf(stderr, "cannot link the Lab profile\n");
        return 1;
    }
    while (scanf("%lf %lf %lf", &in[0], &in[1], &in[2]) == 3) {
        cmsDoTransform(transform, in, out, 1);
        printf("%u %u %u\n", out[0], out[1], out[2]);
    }
    cmsDeleteTransform(transform);
    cmsCloseProfile(srgb);
    cmsCloseProfile(profile);
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "lab") == 0) {
        return lab();
    }
    if (argc == 3 && strcmp(argv[1], "info") == 0) {
        return info(argv[2]);
    }
    if (argc == 3 && strcmp(argv[1], "gray") == 0) {
        return gray(argv[2]);
    }
    if (argc == 4 && strcmp(argv[1], "write") == 0) {
        return write_profile(argv[2], argv[3]);
    }
    fprintf(stderr, "usage: smalt-lcmsdump info|gray <profile> | lab | write pq|hlg <out>\n");
    return 2;
}

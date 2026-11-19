/*
 * Writes the multi-ink (nCLR) ICC profiles and the Little CMS reference outputs of
 * smalt-core's MultiInkDifferentialTest with Little CMS (2.16 or later; checked with the 2.17 release tarball, MIT).
 *
 *   smalt-lcmsinks <directory>
 *
 * The directory receives:
 *   <profile>.icc          the profiles below, with the creation date cleared so that each depends only on the Little
 *                          CMS version
 *   cases.tsv              one transform per line: name, profiles (comma separated), intent (P, R, S, A), black
 *                          point compensation (0, 1), input channels
 *   <case>.8, <case>.16    the outputs of the case for the 8-bit and the 16-bit input sets (16-bit samples
 *                          little-endian)
 *   <case>.x16             the outputs for the 16-bit input set with cmsFLAGS_NOOPTIMIZE: the linked pipeline
 *                          evaluated on every pixel, without lookup tables
 *   <case>.f32             the outputs of the floating-point transform for the float input set, little-endian, in
 *                          Smalt's units (device values 0..1, ink included; Little CMS reads and writes five inks
 *                          and more in 0..100, which this program scales)
 *   colorants.tsv          the colorant tables as Little CMS reads them: profile, tag (clrt or clot), index, name and
 *                          the three PCS words
 *
 * Profiles (output class, perceptual header intent, D50 media white), each modelling subtractive inks whose
 * transmittances multiply in linear light, converted to XYZ with the D50-adapted sRGB matrix (the absorption table
 * below), after a per-ink gamma (the dot gain of the input curves):
 *   ink2.icc        2CLR, v4.3, Lab PCS: AToB0 lutAtoBType (A curves, CLUT of 17 points, B curves), BToA0
 *                   lutBtoAType (B curves, CLUT of 17 points, A curves), clrt
 *   ink3-lut8.icc   3CLR, v2.1, Lab PCS: AToB0 and BToA0 lut8Type (CLUT of 9 points), no clrt
 *   ink5.icc        5CLR, v4.3, Lab PCS: AToB0 lutAtoBType with A curves, CLUT of 7 points, M curves, a matrix
 *                   with offsets and B curves; BToA0 lutBtoAType with 13 points; clrt
 *   ink6-xyz.icc    6CLR, v2.1, XYZ PCS: AToB0 and BToA0 lut16Type (5 and 13 points), clrt in PCSXYZ
 *   ink7.icc        7CLR, v4.3, Lab PCS: lutAtoBType with 4 points, lutBtoAType with 13 points, clrt
 *   ink8.icc        8CLR, v2.4, Lab PCS: lut16Type with 3 and 13 points, clrt; its channels hold the inks in the
 *                   order of PERMUTATION8 (black first), so that the colorant names differ from the canonical order
 *   ink12.icc       CCLR, v4.3, Lab PCS: lutAtoBType with 2 points, lutBtoAType with 9 points, clrt
 *   ink15.icc       FCLR, v4.3, Lab PCS: lutAtoBType with an 8-bit CLUT of 2 points, lutBtoAType with 9 points,
 *                   clrt
 *   link-ink5-ink7.icc  the device link of ink5 to ink7 (perceptual), v4.3, 5 grid points, clrt and clot as Little
 *                   CMS's cmsTransform2DeviceLink copies them
 *   lcms-srgb.icc, lcms-lab4.icc, lcms-xyz.icc  Little CMS's built-in sRGB, v4 L*a*b* and XYZ identity profiles
 *
 * The input sets are those of the Java test:
 *   8-bit, n channels  : a lattice of L levels round(i * 255 / (L - 1)) per channel, the first channel slowest
 *                        (L = 17 for 2 channels, 9 for 3 and 4, 4 for 5 and 6, 3 for 7 and 8), or for 9 channels
 *                        and more the n + 2 corners (all zero, each channel alone at 255, all 255); then 1024 * n
 *                        bytes of noise
 *   16-bit, n channels : a lattice of M levels round(i * 65535 / (M - 1)) (M = 9 for 2 and 3, 5 for 4, 3 for 5 and
 *                        6, 2 for 7 and 8) or the corners for 9 and more; then 1024 * n samples of 16-bit noise
 *   float, n channels  : a lattice of F levels i / (F - 1) (F = 17, 6, 6, 4, 3 for 2 to 6 channels, 2 for 7 and 8)
 *                        or the corners for 9 and more; then 256 points u * 1.4 - 0.2, u uniform in [0, 1)
 * Noise comes from the 64-bit LCG x = x * 6364136223846793005 + 1442695040888963407: bytes are the top 8 bits of
 * each state from the seed 0x9E3779B97F4A7C15, 16-bit samples the top 16 bits from 0xD1B54A32D192ED03, and u the
 * top 24 bits divided by 2^24 from 0x2545F4914F6CDD1D.
 *
 * The BToA tables are a numerical inverse of the AToB model (a projected gradient descent on the device values
 * that minimizes the squared CIE76 difference plus a small penalty on the total ink); they only need to be
 * plausible separations, since the test compares Smalt with Little CMS on the tables as written.
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lcms2.h"

#define MAX_INKS 15

static const double ABSORPTION[MAX_INKS][3] = {
    {0.90, 0.12, 0.05},  /* Cyan */
    {0.10, 0.85, 0.20},  /* Magenta */
    {0.02, 0.10, 0.90},  /* Yellow */
    {0.95, 0.95, 0.93},  /* Black */
    {0.05, 0.55, 0.95},  /* Orange */
    {0.85, 0.10, 0.75},  /* Green */
    {0.60, 0.80, 0.10},  /* Violet */
    {0.08, 0.80, 0.85},  /* Red */
    {0.85, 0.70, 0.05},  /* Blue */
    {0.45, 0.06, 0.03},  /* Light Cyan */
    {0.05, 0.42, 0.10},  /* Light Magenta */
    {0.50, 0.50, 0.48},  /* Gray */
    {0.25, 0.25, 0.24},  /* Light Gray */
    {0.10, 0.30, 0.80},  /* Gold */
    {0.30, 0.28, 0.25},  /* Silver */
};

static const double GAMMA[MAX_INKS] = {
    0.80, 0.85, 0.90, 0.75, 0.95, 1.10, 1.20, 0.70, 1.00, 0.88, 0.92, 1.05, 0.82, 1.15, 0.97,
};

static const char *const NAMES[MAX_INKS] = {
    "Cyan", "Magenta", "Yellow", "Black", "Orange", "Green", "Violet", "Red", "Blue", "Light Cyan",
    "Light Magenta", "Gray", "Light Gray", "Gold", "Silver",
};

static const int PERMUTATION8[8] = {3, 0, 1, 2, 4, 5, 6, 7};

/* the near-identity matrix of ink5's lutAtoBType, in normalized PCS units, and its offsets */
static const double MATRIX5[9] = {0.97, 0, 0, 0, 0.98, 0, 0, 0, 0.98};
static const double OFFSET5[3] = {0.02, 0.01, 0.01};

enum encoding { LAB4, LAB2, XYZ };

typedef struct {
    int inks;
    int order[MAX_INKS];
    int curves;
    int tabulated;
    enum encoding encoding;
    int matrix;
} Model;

static const char *directory;
static FILE *cases_file;
static FILE *colorants_file;

static void fail(const char *what) {
    fprintf(stderr, "smalt-lcmsinks: %s\n", what);
    exit(1);
}

static void error_handler(cmsContext context, cmsUInt32Number code, const char *text) {
    (void) context;
    fprintf(stderr, "lcms error %u: %s\n", (unsigned) code, text);
}

static char *path_of(const char *name) {
    static char path[4096];
    snprintf(path, sizeof(path), "%s/%s", directory, name);
    return path;
}

static void save(cmsHPROFILE profile, const char *name) {
    static const unsigned char zeros[12] = {0};
    FILE *file;
    if (!cmsSaveProfileToFile(profile, path_of(name))) {
        fail(name);
    }
    cmsCloseProfile(profile);
    file = fopen(path_of(name), "r+b");
    if (file == NULL || fseek(file, 24, SEEK_SET) != 0 || fwrite(zeros, 1, 12, file) != 12) {
        fail(name);
    }
    fclose(file);
}

static cmsHPROFILE load(const char *name) {
    cmsHPROFILE profile = cmsOpenProfileFromFile(path_of(name), "r");
    if (profile == NULL) {
        fail(name);
    }
    return profile;
}

static void describe(cmsHPROFILE profile, const char *text) {
    cmsMLU *mlu = cmsMLUalloc(NULL, 1);
    cmsMLUsetASCII(mlu, "en", "US", text);
    cmsWriteTag(profile, cmsSigProfileDescriptionTag, mlu);
    cmsMLUsetASCII(mlu, "en", "US", "No copyright, use freely");
    cmsWriteTag(profile, cmsSigCopyrightTag, mlu);
    cmsMLUfree(mlu);
}

static cmsHPROFILE placeholder(double version, cmsColorSpaceSignature space, cmsColorSpaceSignature pcs,
                               const char *text) {
    cmsHPROFILE profile = cmsCreateProfilePlaceholder(NULL);
    cmsSetProfileVersion(profile, version);
    cmsSetDeviceClass(profile, cmsSigOutputClass);
    cmsSetColorSpace(profile, space);
    cmsSetPCS(profile, pcs);
    cmsSetHeaderRenderingIntent(profile, INTENT_PERCEPTUAL);
    describe(profile, text);
    cmsWriteTag(profile, cmsSigMediaWhitePointTag, cmsD50_XYZ());
    return profile;
}

static unsigned long long lcg(unsigned long long *state) {
    *state = *state * 6364136223846793005ULL + 1442695040888963407ULL;
    return *state;
}

/* ---- the ink model ---- */

/* the color of device values 0..1 (before the input curves when curves is set) */
static void model_xyz(const Model *m, const double *device, cmsCIEXYZ *xyz) {
    double rgb[3] = {1, 1, 1};
    int i, k;
    for (i = 0; i < m->inks; i++) {
        int ink = m->order[i];
        double value = device[i] < 0 ? 0 : device[i] > 1 ? 1 : device[i];
        double amount = m->curves ? pow(value, GAMMA[ink]) : value;
        for (k = 0; k < 3; k++) {
            rgb[k] *= 1 - amount * ABSORPTION[ink][k];
        }
    }
    xyz->X = 0.4360747 * rgb[0] + 0.3850649 * rgb[1] + 0.1430804 * rgb[2];
    xyz->Y = 0.2225045 * rgb[0] + 0.7168786 * rgb[1] + 0.0606169 * rgb[2];
    xyz->Z = 0.0139322 * rgb[0] + 0.0971045 * rgb[1] + 0.7141733 * rgb[2];
}

static void model_lab(const Model *m, const double *device, cmsCIELab *lab) {
    cmsCIEXYZ xyz;
    model_xyz(m, device, &xyz);
    cmsXYZ2Lab(cmsD50_XYZ(), lab, &xyz);
}

static void encode_pcs(enum encoding encoding, const cmsCIEXYZ *xyz, cmsUInt16Number out[3]) {
    cmsCIELab lab;
    if (encoding == XYZ) {
        cmsFloat2XYZEncoded(out, xyz);
        return;
    }
    cmsXYZ2Lab(cmsD50_XYZ(), &lab, xyz);
    if (encoding == LAB4) {
        cmsFloat2LabEncoded(out, &lab);
    } else {
        cmsFloat2LabEncodedV2(out, &lab);
    }
}

/* AToB CLUT sampler: the CLUT sees the curved device values, so it evaluates the model without curves */
static int sample_forward(CMSREGISTER const cmsUInt16Number in[], CMSREGISTER cmsUInt16Number out[],
                          CMSREGISTER void *cargo) {
    const Model *m = (const Model *) cargo;
    Model flat = *m;
    double device[MAX_INKS];
    cmsCIEXYZ xyz;
    int i;
    flat.curves = 0;
    for (i = 0; i < m->inks; i++) {
        device[i] = in[i] / 65535.0;
    }
    model_xyz(&flat, device, &xyz);
    encode_pcs(m->encoding, &xyz, out);
    if (m->matrix) {
        /* undo the matrix and offsets that follow the CLUT */
        for (i = 0; i < 3; i++) {
            double value = (out[i] / 65535.0 - OFFSET5[i]) / MATRIX5[i * 4];
            value = value < 0 ? 0 : value > 1 ? 1 : value;
            out[i] = (cmsUInt16Number) floor(value * 65535.0 + 0.5);
        }
    }
    return 1;
}

static double objective(const Model *m, const double *device, const cmsCIELab *target) {
    cmsCIELab lab;
    double total = 0, dl, da, db;
    int i;
    model_lab(m, device, &lab);
    dl = lab.L - target->L;
    da = lab.a - target->a;
    db = lab.b - target->b;
    for (i = 0; i < m->inks; i++) {
        total += device[i];
    }
    return dl * dl + da * da + db * db + 0.5 * total;
}

/* the device values whose color is closest to target */
static void invert(const Model *m, const cmsCIELab *target, double *device) {
    double gradient[MAX_INKS], trial[MAX_INKS], value, step = 0.01;
    int iteration, i;
    for (i = 0; i < m->inks; i++) {
        device[i] = 0.2;
    }
    value = objective(m, device, target);
    for (iteration = 0; iteration < 80; iteration++) {
        double norm = 0;
        for (i = 0; i < m->inks; i++) {
            double saved = device[i], up, down;
            device[i] = saved + 1e-4;
            up = objective(m, device, target);
            device[i] = saved - 1e-4;
            down = objective(m, device, target);
            device[i] = saved;
            gradient[i] = (up - down) / 2e-4;
            norm += gradient[i] * gradient[i];
        }
        if (norm < 1e-12) {
            break;
        }
        step *= 2;
        for (;;) {
            double candidate;
            for (i = 0; i < m->inks; i++) {
                double next = device[i] - step * gradient[i];
                trial[i] = next < 0 ? 0 : next > 1 ? 1 : next;
            }
            candidate = objective(m, trial, target);
            if (candidate < value || step < 1e-9) {
                if (candidate < value) {
                    memcpy(device, trial, sizeof(double) * m->inks);
                    value = candidate;
                }
                break;
            }
            step /= 2;
        }
    }
}

/* BToA CLUT sampler: PCS words in, device words out */
static int sample_inverse(CMSREGISTER const cmsUInt16Number in[], CMSREGISTER cmsUInt16Number out[],
                          CMSREGISTER void *cargo) {
    const Model *m = (const Model *) cargo;
    double device[MAX_INKS];
    cmsCIELab lab;
    int i;
    if (m->encoding == XYZ) {
        cmsCIEXYZ xyz;
        cmsXYZEncoded2Float(&xyz, in);
        cmsXYZ2Lab(cmsD50_XYZ(), &lab, &xyz);
    } else if (m->encoding == LAB4) {
        cmsLabEncoded2Float(&lab, in);
    } else {
        cmsLabEncoded2FloatV2(&lab, in);
    }
    invert(m, &lab, device);
    for (i = 0; i < m->inks; i++) {
        out[i] = (cmsUInt16Number) floor(device[i] * 65535.0 + 0.5);
    }
    return 1;
}

/* a gamma curve, tabulated with 256 entries for lut8Type, which stores no other form */
static cmsToneCurve *gamma_curve(double gamma, int tabulated) {
    cmsUInt16Number table[256];
    int i;
    if (!tabulated) {
        return cmsBuildGamma(NULL, gamma);
    }
    for (i = 0; i < 256; i++) {
        table[i] = (cmsUInt16Number) floor(pow(i / 255.0, gamma) * 65535.0 + 0.5);
    }
    return cmsBuildTabulatedToneCurve16(NULL, 256, table);
}

static cmsStage *gamma_curves(const Model *m, int inverse) {
    cmsToneCurve *curves[MAX_INKS];
    cmsStage *stage;
    int i;
    for (i = 0; i < m->inks; i++) {
        curves[i] = gamma_curve(inverse ? 1.0 : GAMMA[m->order[i]], m->tabulated);
    }
    stage = cmsStageAllocToneCurves(NULL, (cmsUInt32Number) m->inks, curves);
    for (i = 0; i < m->inks; i++) {
        cmsFreeToneCurve(curves[i]);
    }
    return stage;
}

static cmsStage *identity_curves(int channels, int tabulated) {
    cmsToneCurve *curves[3];
    cmsStage *stage;
    int i;
    if (!tabulated) {
        return cmsStageAllocToneCurves(NULL, (cmsUInt32Number) channels, NULL);
    }
    for (i = 0; i < channels; i++) {
        curves[i] = gamma_curve(1.0, 1);
    }
    stage = cmsStageAllocToneCurves(NULL, (cmsUInt32Number) channels, curves);
    for (i = 0; i < channels; i++) {
        cmsFreeToneCurve(curves[i]);
    }
    return stage;
}

static void add(cmsPipeline *lut, cmsStage *stage) {
    if (stage == NULL || !cmsPipelineInsertStage(lut, cmsAT_END, stage)) {
        fail("stage");
    }
}

static cmsStage *clut(int inputs, int outputs, int points, cmsSAMPLER16 sampler, Model *m) {
    cmsStage *stage = cmsStageAllocCLut16bit(NULL, (cmsUInt32Number) points, (cmsUInt32Number) inputs,
                                             (cmsUInt32Number) outputs, NULL);
    if (stage == NULL || !cmsStageSampleCLut16bit(stage, sampler, m, 0)) {
        fail("clut");
    }
    return stage;
}

static void write_colorants(cmsHPROFILE profile, const Model *m) {
    cmsNAMEDCOLORLIST *list = cmsAllocNamedColorList(NULL, (cmsUInt32Number) m->inks, 0, "", "");
    Model flat = *m;
    int i;
    flat.curves = 0;
    for (i = 0; i < m->inks; i++) {
        double device[MAX_INKS] = {0};
        cmsCIEXYZ xyz;
        cmsUInt16Number pcs[3];
        device[i] = 1;
        model_xyz(&flat, device, &xyz);
        /* colorant tables use the 16-bit PCS encoding of the profile's version, never the lut16 legacy one */
        encode_pcs(m->encoding == XYZ ? XYZ : LAB4, &xyz, pcs);
        if (!cmsAppendNamedColor(list, NAMES[m->order[i]], pcs, NULL)) {
            fail("colorant");
        }
    }
    if (!cmsWriteTag(profile, cmsSigColorantTableTag, list)) {
        fail("clrt");
    }
    cmsFreeNamedColorList(list);
}

static cmsColorSpaceSignature space_of(int inks) {
    static const cmsColorSpaceSignature spaces[] = {
        cmsSig2colorData, cmsSig3colorData, cmsSig4colorData, cmsSig5colorData, cmsSig6colorData, cmsSig7colorData,
        cmsSig8colorData, cmsSig9colorData, cmsSig10colorData, cmsSig11colorData, cmsSig12colorData,
        cmsSig13colorData, cmsSig14colorData, cmsSig15colorData,
    };
    return spaces[inks - 2];
}

/*
 * Writes one profile. kind: 'v' lutAtoB/lutBtoA, 'b' the same with an 8-bit AToB CLUT, 'm' lutAtoB with M curves
 * and matrix, '8' lut8, '6' lut16.
 */
static void write_inks(const char *name, int inks, double version, enum encoding encoding, char kind, int points,
                       int inverse_points, int colorants, const int *order) {
    char text[96];
    Model m;
    cmsHPROFILE profile;
    cmsPipeline *lut;
    int i;
    memset(&m, 0, sizeof(m));
    m.inks = inks;
    m.curves = 1;
    m.encoding = encoding;
    m.matrix = kind == 'm';
    m.tabulated = kind == '8';
    for (i = 0; i < inks; i++) {
        m.order[i] = order == NULL ? i : order[i];
    }
    snprintf(text, sizeof(text), "Smalt %d inks, %s PCS", inks, encoding == XYZ ? "XYZ" : "Lab");
    profile = placeholder(version, space_of(inks), encoding == XYZ ? cmsSigXYZData : cmsSigLabData, text);

    lut = cmsPipelineAlloc(NULL, (cmsUInt32Number) inks, 3);
    add(lut, gamma_curves(&m, 0));
    add(lut, clut(inks, 3, points, sample_forward, &m));
    add(lut, identity_curves(3, m.tabulated));
    if (kind == 'm') {
        add(lut, cmsStageAllocMatrix(NULL, 3, 3, MATRIX5, OFFSET5));
        add(lut, identity_curves(3, m.tabulated));
    }
    if (kind == '8' || kind == 'b') {
        cmsPipelineSetSaveAs8bitsFlag(lut, TRUE);
    }
    if (!cmsWriteTag(profile, cmsSigAToB0Tag, lut)) {
        fail("AToB0");
    }
    cmsPipelineFree(lut);

    lut = cmsPipelineAlloc(NULL, 3, (cmsUInt32Number) inks);
    add(lut, identity_curves(3, m.tabulated));
    add(lut, clut(3, inks, inverse_points, sample_inverse, &m));
    add(lut, gamma_curves(&m, 1));
    if (kind == '8') {
        cmsPipelineSetSaveAs8bitsFlag(lut, TRUE);
    }
    if (!cmsWriteTag(profile, cmsSigBToA0Tag, lut)) {
        fail("BToA0");
    }
    cmsPipelineFree(lut);

    if (colorants) {
        write_colorants(profile, &m);
    }
    save(profile, name);
    printf("%s\n", name);
}

static void write_link(void) {
    cmsHPROFILE profiles[2], link;
    cmsHTRANSFORM transform;
    profiles[0] = load("ink5.icc");
    profiles[1] = load("ink7.icc");
    transform = cmsCreateMultiprofileTransform(profiles, 2, COLORSPACE_SH(PT_MCH5) | CHANNELS_SH(5) | BYTES_SH(2),
                                               COLORSPACE_SH(PT_MCH7) | CHANNELS_SH(7) | BYTES_SH(2),
                                               INTENT_PERCEPTUAL, cmsFLAGS_NOCACHE | cmsFLAGS_NOOPTIMIZE);
    if (transform == NULL) {
        fail("link transform");
    }
    link = cmsTransform2DeviceLink(transform, 4.3, cmsFLAGS_GRIDPOINTS(5));
    if (link == NULL) {
        fail("link");
    }
    describe(link, "Smalt 5 inks to 7 inks link");
    save(link, "link-ink5-ink7.icc");
    cmsDeleteTransform(transform);
    cmsCloseProfile(profiles[0]);
    cmsCloseProfile(profiles[1]);
}

static void write_builtins(void) {
    cmsHPROFILE profile = cmsCreate_sRGBProfile();
    describe(profile, "Little CMS sRGB");
    save(profile, "lcms-srgb.icc");
    profile = cmsCreateLab4Profile(NULL);
    describe(profile, "Little CMS Lab v4");
    save(profile, "lcms-lab4.icc");
    profile = cmsCreateXYZProfile();
    describe(profile, "Little CMS XYZ");
    save(profile, "lcms-xyz.icc");
}

/* ---- input sets ---- */

static int levels8(int channels) {
    switch (channels) {
        case 2: return 17;
        case 3: case 4: return 9;
        case 5: case 6: return 4;
        case 7: case 8: return 3;
        default: return 0;
    }
}

static int levels16(int channels) {
    switch (channels) {
        case 2: case 3: return 9;
        case 4: return 5;
        case 5: case 6: return 3;
        case 7: case 8: return 2;
        default: return 0;
    }
}

static int levels_float(int channels) {
    static const int levels[] = {0, 0, 17, 6, 6, 4, 3, 2, 2};
    return channels <= 8 ? levels[channels] : 0;
}

/* the lattice (or the corners when levels is 0) as fractions of full scale, the first channel slowest */
static int lattice(int channels, int levels, double *out) {
    int total = 1, index, c;
    if (levels == 0) {
        total = channels + 2;
        if (out != NULL) {
            for (index = 0; index < total; index++) {
                for (c = 0; c < channels; c++) {
                    out[index * channels + c] = index == channels + 1 ? 1.0 : index == c + 1 ? 1.0 : 0.0;
                }
            }
        }
        return total;
    }
    for (c = 0; c < channels; c++) {
        total *= levels;
    }
    if (out != NULL) {
        for (index = 0; index < total; index++) {
            int rest = index;
            for (c = channels - 1; c >= 0; c--) {
                out[index * channels + c] = (double) (rest % levels) / (levels - 1);
                rest /= levels;
            }
        }
    }
    return total;
}

static unsigned char *inputs8(int channels, int *pixels) {
    int total = lattice(channels, levels8(channels), NULL), i;
    double *grid = malloc(sizeof(double) * (size_t) total * channels);
    unsigned char *out;
    unsigned long long state = 0x9E3779B97F4A7C15ULL;
    lattice(channels, levels8(channels), grid);
    *pixels = total + 1024;
    out = malloc((size_t) *pixels * channels);
    for (i = 0; i < total * channels; i++) {
        out[i] = (unsigned char) floor(grid[i] * 255 + 0.5);
    }
    for (; i < *pixels * channels; i++) {
        out[i] = (unsigned char) (lcg(&state) >> 56);
    }
    free(grid);
    return out;
}

static cmsUInt16Number *inputs16(int channels, int *pixels) {
    int total = lattice(channels, levels16(channels), NULL), i;
    double *grid = malloc(sizeof(double) * (size_t) total * channels);
    cmsUInt16Number *out;
    unsigned long long state = 0xD1B54A32D192ED03ULL;
    lattice(channels, levels16(channels), grid);
    *pixels = total + 1024;
    out = malloc((size_t) *pixels * channels * 2);
    for (i = 0; i < total * channels; i++) {
        out[i] = (cmsUInt16Number) floor(grid[i] * 65535 + 0.5);
    }
    for (; i < *pixels * channels; i++) {
        out[i] = (cmsUInt16Number) (lcg(&state) >> 48);
    }
    free(grid);
    return out;
}

static cmsFloat32Number *inputs_float(int channels, int *pixels) {
    int total = lattice(channels, levels_float(channels), NULL), i;
    double *grid = malloc(sizeof(double) * (size_t) total * channels);
    cmsFloat32Number *out;
    unsigned long long state = 0x2545F4914F6CDD1DULL;
    lattice(channels, levels_float(channels), grid);
    *pixels = total + 256;
    out = malloc((size_t) *pixels * channels * sizeof(cmsFloat32Number));
    for (i = 0; i < total * channels; i++) {
        out[i] = (cmsFloat32Number) grid[i];
    }
    for (; i < *pixels * channels; i++) {
        double u = (double) (lcg(&state) >> 40) / 16777216.0;
        out[i] = (cmsFloat32Number) (u * 1.4 - 0.2);
    }
    free(grid);
    return out;
}

/* ---- outputs ---- */

static void write_bytes(const char *name, const void *data, size_t size) {
    FILE *file = fopen(path_of(name), "wb");
    if (file == NULL || fwrite(data, 1, size, file) != size) {
        fail(name);
    }
    fclose(file);
}

static void write_words(const char *name, const cmsUInt16Number *data, size_t count) {
    unsigned char *bytes = malloc(count * 2);
    size_t i;
    for (i = 0; i < count; i++) {
        bytes[i * 2] = (unsigned char) data[i];
        bytes[i * 2 + 1] = (unsigned char) (data[i] >> 8);
    }
    write_bytes(name, bytes, count * 2);
    free(bytes);
}

static void write_floats(const char *name, const cmsFloat32Number *data, size_t count) {
    unsigned char *bytes = malloc(count * 4);
    size_t i;
    for (i = 0; i < count; i++) {
        cmsUInt32Number bits;
        memcpy(&bits, &data[i], 4);
        bytes[i * 4] = (unsigned char) bits;
        bytes[i * 4 + 1] = (unsigned char) (bits >> 8);
        bytes[i * 4 + 2] = (unsigned char) (bits >> 16);
        bytes[i * 4 + 3] = (unsigned char) (bits >> 24);
    }
    write_bytes(name, bytes, count * 4);
    free(bytes);
}

/* ---- transforms ---- */

static int is_pcs(cmsColorSpaceSignature space) {
    return space == cmsSigXYZData || space == cmsSigLabData;
}

/* whether Little CMS's float formatters read and write the space in percents (IsInkSpace, cmspack.c) */
static int is_percent(cmsColorSpaceSignature space) {
    int type = _cmsLCMScolorSpace(space);
    return type == PT_CMY || type == PT_CMYK || (type >= PT_MCH5 && type <= PT_MCH15);
}

/* the entry and exit color spaces, as GetXFormColorSpaces (cmsxform.c) finds them */
static void spaces(cmsHPROFILE *profiles, int count, cmsColorSpaceSignature *entry,
                   cmsColorSpaceSignature *exit_space) {
    cmsColorSpaceSignature post = cmsGetColorSpace(profiles[0]);
    int i;
    *entry = post;
    for (i = 0; i < count; i++) {
        cmsProfileClassSignature cls = cmsGetDeviceClass(profiles[i]);
        cmsColorSpaceSignature in, out;
        if (!is_pcs(post) || cls == cmsSigLinkClass) {
            in = cmsGetColorSpace(profiles[i]);
            out = cmsGetPCS(profiles[i]);
        } else {
            in = cmsGetPCS(profiles[i]);
            out = cmsGetColorSpace(profiles[i]);
        }
        if (i == 0) {
            *entry = in;
        }
        post = out;
    }
    *exit_space = post;
}

static cmsUInt32Number format(cmsColorSpaceSignature space, int bytes) {
    cmsUInt32Number base = COLORSPACE_SH(_cmsLCMScolorSpace(space))
            | CHANNELS_SH(cmsChannelsOfColorSpace(space));
    return bytes == 4 ? base | BYTES_SH(4) | FLOAT_SH(1) : base | BYTES_SH(bytes);
}

static cmsUInt32Number intent_code(char intent) {
    switch (intent) {
        case 'P': return INTENT_PERCEPTUAL;
        case 'R': return INTENT_RELATIVE_COLORIMETRIC;
        case 'S': return INTENT_SATURATION;
        default: return INTENT_ABSOLUTE_COLORIMETRIC;
    }
}

static void run(const char *name, const char *names, char intent, int bpc) {
    cmsHPROFILE profiles[8];
    char list[512], *token, file[256];
    int count = 0, channels, outputs, pixels8, pixels16, pixels_float, i;
    cmsColorSpaceSignature entry, exit_space;
    cmsUInt32Number flags = bpc ? cmsFLAGS_BLACKPOINTCOMPENSATION : 0;
    cmsHTRANSFORM transform;
    unsigned char *in8, *out8;
    cmsUInt16Number *in16, *out16;
    cmsFloat32Number *in_float, *out_float;
    strncpy(list, names, sizeof(list) - 1);
    list[sizeof(list) - 1] = 0;
    for (token = strtok(list, ","); token != NULL; token = strtok(NULL, ",")) {
        snprintf(file, sizeof(file), "%s.icc", token);
        profiles[count++] = load(file);
    }
    spaces(profiles, count, &entry, &exit_space);
    channels = cmsChannelsOfColorSpace(entry);
    outputs = cmsChannelsOfColorSpace(exit_space);

    in8 = inputs8(channels, &pixels8);
    out8 = malloc((size_t) pixels8 * outputs);
    transform = cmsCreateMultiprofileTransform(profiles, count, format(entry, 1), format(exit_space, 1),
                                               intent_code(intent), flags);
    if (transform == NULL) {
        fail(name);
    }
    cmsDoTransform(transform, in8, out8, (cmsUInt32Number) pixels8);
    cmsDeleteTransform(transform);
    snprintf(file, sizeof(file), "%s.8", name);
    write_bytes(file, out8, (size_t) pixels8 * outputs);

    in16 = inputs16(channels, &pixels16);
    out16 = malloc((size_t) pixels16 * outputs * 2);
    transform = cmsCreateMultiprofileTransform(profiles, count, format(entry, 2), format(exit_space, 2),
                                               intent_code(intent), flags);
    if (transform == NULL) {
        fail(name);
    }
    cmsDoTransform(transform, in16, out16, (cmsUInt32Number) pixels16);
    cmsDeleteTransform(transform);
    snprintf(file, sizeof(file), "%s.16", name);
    write_words(file, out16, (size_t) pixels16 * outputs);
    transform = cmsCreateMultiprofileTransform(profiles, count, format(entry, 2), format(exit_space, 2),
                                               intent_code(intent), flags | cmsFLAGS_NOOPTIMIZE);
    if (transform == NULL) {
        fail(name);
    }
    cmsDoTransform(transform, in16, out16, (cmsUInt32Number) pixels16);
    cmsDeleteTransform(transform);
    snprintf(file, sizeof(file), "%s.x16", name);
    write_words(file, out16, (size_t) pixels16 * outputs);

    in_float = inputs_float(channels, &pixels_float);
    out_float = malloc((size_t) pixels_float * outputs * sizeof(cmsFloat32Number));
    if (is_percent(entry)) {
        for (i = 0; i < pixels_float * channels; i++) {
            in_float[i] *= 100;
        }
    }
    transform = cmsCreateMultiprofileTransform(profiles, count, format(entry, 4), format(exit_space, 4),
                                               intent_code(intent), flags);
    if (transform == NULL) {
        fail(name);
    }
    cmsDoTransform(transform, in_float, out_float, (cmsUInt32Number) pixels_float);
    cmsDeleteTransform(transform);
    if (is_percent(exit_space)) {
        for (i = 0; i < pixels_float * outputs; i++) {
            out_float[i] /= 100;
        }
    }
    snprintf(file, sizeof(file), "%s.f32", name);
    write_floats(file, out_float, (size_t) pixels_float * outputs);

    fprintf(cases_file, "%s\t%s\t%c\t%d\t%d\n", name, names, intent, bpc, channels);
    free(in8);
    free(out8);
    free(in16);
    free(out16);
    free(in_float);
    free(out_float);
    while (count > 0) {
        cmsCloseProfile(profiles[--count]);
    }
    printf("%s\n", name);
}

static void dump_colorants(const char *profile_name, cmsTagSignature tag, const char *tag_name) {
    char file[256], name[256];
    cmsHPROFILE profile;
    cmsNAMEDCOLORLIST *colors;
    cmsUInt16Number pcs[3];
    cmsUInt32Number i, count;
    snprintf(file, sizeof(file), "%s.icc", profile_name);
    profile = load(file);
    colors = cmsReadTag(profile, tag);
    if (colors == NULL) {
        fail(tag_name);
    }
    count = cmsNamedColorCount(colors);
    for (i = 0; i < count; i++) {
        cmsNamedColorInfo(colors, i, name, NULL, NULL, pcs, NULL);
        fprintf(colorants_file, "%s\t%s\t%u\t%s\t%u,%u,%u\n", profile_name, tag_name, (unsigned) i, name, pcs[0],
                pcs[1], pcs[2]);
    }
    cmsCloseProfile(profile);
}

int main(int argc, char **argv) {
    char file[4096];
    if (argc != 2) {
        fprintf(stderr, "usage: smalt-lcmsinks <directory>\n");
        return 2;
    }
    directory = argv[1];
    cmsSetLogErrorHandler(error_handler);
    write_builtins();
    write_inks("ink2.icc", 2, 4.3, LAB4, 'v', 17, 17, 1, NULL);
    write_inks("ink3-lut8.icc", 3, 2.1, LAB2, '8', 9, 9, 0, NULL);
    write_inks("ink5.icc", 5, 4.3, LAB4, 'm', 7, 13, 1, NULL);
    write_inks("ink6-xyz.icc", 6, 2.1, XYZ, '6', 5, 13, 1, NULL);
    write_inks("ink7.icc", 7, 4.3, LAB4, 'v', 4, 13, 1, NULL);
    write_inks("ink8.icc", 8, 2.4, LAB2, '6', 3, 13, 1, PERMUTATION8);
    write_inks("ink12.icc", 12, 4.3, LAB4, 'v', 2, 9, 1, NULL);
    write_inks("ink15.icc", 15, 4.3, LAB4, 'b', 2, 9, 1, NULL);
    write_link();

    snprintf(file, sizeof(file), "%s/cases.tsv", directory);
    cases_file = fopen(file, "wb");
    snprintf(file, sizeof(file), "%s/colorants.tsv", directory);
    colorants_file = fopen(file, "wb");
    if (cases_file == NULL || colorants_file == NULL) {
        fail("manifests");
    }

    run("ink2-to-srgb", "ink2,lcms-srgb", 'P', 0);
    run("ink3-lut8-to-srgb", "ink3-lut8,lcms-srgb", 'P', 0);
    run("ink5-to-srgb", "ink5,lcms-srgb", 'P', 0);
    run("ink5-to-srgb-relbpc", "ink5,lcms-srgb", 'R', 1);
    run("ink5-to-srgb-abs", "ink5,lcms-srgb", 'A', 0);
    run("ink5-to-lab", "ink5,lcms-lab4", 'P', 0);
    run("ink6-xyz-to-srgb", "ink6-xyz,lcms-srgb", 'P', 0);
    run("ink6-xyz-to-xyz", "ink6-xyz,lcms-xyz", 'R', 0);
    run("ink7-to-srgb", "ink7,lcms-srgb", 'P', 0);
    run("ink8-to-srgb", "ink8,lcms-srgb", 'P', 0);
    run("ink8-to-lab-rel", "ink8,lcms-lab4", 'R', 0);
    run("ink12-to-srgb", "ink12,lcms-srgb", 'P', 0);
    run("ink15-to-lab", "ink15,lcms-lab4", 'P', 0);

    run("srgb-to-ink2", "lcms-srgb,ink2", 'P', 0);
    run("srgb-to-ink3-lut8", "lcms-srgb,ink3-lut8", 'P', 0);
    run("srgb-to-ink5", "lcms-srgb,ink5", 'P', 0);
    run("srgb-to-ink6-xyz", "lcms-srgb,ink6-xyz", 'P', 0);
    run("srgb-to-ink7", "lcms-srgb,ink7", 'P', 0);
    run("srgb-to-ink8", "lcms-srgb,ink8", 'P', 0);
    run("srgb-to-ink8-relbpc", "lcms-srgb,ink8", 'R', 1);
    run("srgb-to-ink12", "lcms-srgb,ink12", 'P', 0);
    run("srgb-to-ink15", "lcms-srgb,ink15", 'P', 0);

    run("ink5-to-ink8", "ink5,ink8", 'P', 0);
    run("ink7-to-ink6-xyz", "ink7,ink6-xyz", 'R', 0);
    run("link-ink5-ink7", "link-ink5-ink7", 'P', 0);
    run("link-ink5-ink7-to-srgb", "link-ink5-ink7,ink7,lcms-srgb", 'P', 0);

    dump_colorants("ink2", cmsSigColorantTableTag, "clrt");
    dump_colorants("ink5", cmsSigColorantTableTag, "clrt");
    dump_colorants("ink6-xyz", cmsSigColorantTableTag, "clrt");
    dump_colorants("ink7", cmsSigColorantTableTag, "clrt");
    dump_colorants("ink8", cmsSigColorantTableTag, "clrt");
    dump_colorants("ink12", cmsSigColorantTableTag, "clrt");
    dump_colorants("ink15", cmsSigColorantTableTag, "clrt");
    dump_colorants("link-ink5-ink7", cmsSigColorantTableTag, "clrt");
    dump_colorants("link-ink5-ink7", cmsSigColorantTableOutTag, "clot");
    fclose(cases_file);
    fclose(colorants_file);
    return 0;
}

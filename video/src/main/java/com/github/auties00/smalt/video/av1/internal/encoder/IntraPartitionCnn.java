package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.TableText;

/**
 * Predicts from the source samples of a 64x64 superblock whether its partition nodes are better
 * split or left whole, so the partition search can skip the unsplit block of nodes that will
 * certainly split and the split of nodes that certainly will not.
 *
 * <p>A five-layer convolutional network reads the superblock's 65x65 luma neighborhood once and
 * yields feature maps at four scales; for each 64x64, 32x32, 16x16 or 8x8 node a small dense
 * network turns the maps covering it and the quantizer into a split score, which is compared with
 * thresholds that depend on the picture's resolution.
 *
 * @implNote This implementation ports libaom's {@code intra_mode_cnn_partition}
 * ({@code av1/encoder/partition_strategy.c}) with the convolution of
 * {@code av1_cnn_convolve_no_maxpool_padding_valid_c} ({@code av1/encoder/cnn.c}), the
 * dense layers of {@code av1_nn_predict_c} and its output rounding
 * ({@code av1/encoder/ml.c}), and the trained weights and thresholds of
 * {@code av1/encoder/partition_cnn_weights.h}, copied unchanged and packed as base-64
 * text of their IEEE bits.
 */
final class IntraPartitionCnn {
    /**
     * The decision that a node is only split: neither left whole nor split in halves.
     */
    static final int SPLIT_ONLY = 1;

    /**
     * The decision that a node is not split into quarters.
     */
    static final int NO_SPLIT = -1;

    /**
     * The decision that leaves every partition of a node to the search.
     */
    static final int UNDECIDED = 0;

    /**
     * The split score above which a node is only split, by block level (64x64 to 8x8 from 1) and
     * resolution (720p and up, 480p and up, smaller).
     */
    private static final float[][] SPLIT_THRESHOLDS = {
            {100.000000f, 4.750139f, 1.655964f, 3.711212f, 0.963839f},
            {100.000000f, 3.218737f, 2.657764f, 0.868458f, 2.454447f},
            {100.000000f, 1.890757f, 2.658417f, 1.450626f, 1.833180f},
    };

    /**
     * The split score below which a node is not split, by block level and resolution.
     */
    private static final float[][] NO_SPLIT_THRESHOLDS = {
            {-100.000000f, -2.404842f, -3.858223f, -2.041206f, -1.573735f},
            {-100.000000f, -3.842426f, -4.005076f, -3.642994f, -2.467197f},
            {-100.000000f, -4.100921f, -4.564202f, -5.695176f, -1.483546f},
    };

    /**
     * The mean of the logarithmic quantizer feature.
     */
    private static final float LOG_Q_MEAN = 1.191922f;

    /**
     * The standard deviation of the logarithmic quantizer feature.
     */
    private static final float LOG_Q_STD = 1.730044f;

    /**
     * The raster index of each 32x32 quarter in z-order.
     */
    private static final int[] QUAD_TO_LINEAR_1 = {0, 1, 2, 3};

    /**
     * The raster index of each 16x16 cell in z-order.
     */
    private static final int[] QUAD_TO_LINEAR_2 = {0, 1, 4, 5, 2, 3, 6, 7, 8, 9, 12, 13, 10, 11, 14, 15};

    /**
     * The raster index of each 8x8 cell in z-order.
     */
    private static final int[] QUAD_TO_LINEAR_3 = {
            0, 1, 8, 9, 2, 3, 10, 11, 16, 17, 24, 25, 18, 19, 26, 27,
            4, 5, 12, 13, 6, 7, 14, 15, 20, 21, 28, 29, 22, 23, 30, 31,
            32, 33, 40, 41, 34, 35, 42, 43, 48, 49, 56, 57, 50, 51, 58, 59,
            36, 37, 44, 45, 38, 39, 46, 47, 52, 53, 60, 61, 54, 55, 62, 63
    };

    /**
     * The side of the network's input window.
     */
    private static final int INPUT = 65;

    /**
     * The packed {@code av1_intra_mode_cnn_partition_cnn_layer_0_kernel} weights, 500 values.
     */
    private static final String CNN_0_KERNEL =
            "A+Bw85C/F/H6C+WguiC7EC+4C+yzzmA+k1RcC/azMzC8G73MA/Td/bC+ylzEA/BEApA/lQq7A+5D3NC+J0T2A/PxnWC+rYWd"
            + "C/EAn2A+9np0A/QWB0C+TZIyA9oKBGA92GKyA8qHzBC/BiqyC+88lpC+JRMwC/JposA+DMX8A+klmfA/Aj6fC/JLTyA+aISE"
            + "C/ZkYXC+06bOA/IafBA+y+FDC/C11GA/Uz1cA/K/4qC+f8YRA+pXAeA+AF2WC/ODVZC9kmbwC+LIPtC+Wl0pC/LK2sA+qQkp"
            + "C+DbxmA+UYfoC/BMESC+GognC/GujiC+5cAzC+84G2A/PO2RC+qDSPA/QUn5A/eCuEC+pF85C+bDdhA+0S+yC+wXC1C+jEZS"
            + "A/iMFVA+v3i8C+5o5hA+4OZtC+w4FSC+iN+cA+nL0SC+yZG8A/H1gZC/B115C+q4PxA+hsE8C+zeLOA/JDQaA/SwUyC8vWC0"
            + "C/L0sfC+ljBmC/PM2FC+7OD8A+8wKTA+EMpgC+HDCQA+sRcNC+//7SA65BjEA+rL39C+BkxzA+2tI1C+6jbBC/YS5iC+2hCd"
            + "C+hr57A9DU0lA/ig/kC7iIgkA8qyxpA+b4TLA/RBZIA9LYqYC98MAnC+fjHXA+/zjFC/WFqBA+ZQP7C/VrWiA/FVm0A+nrKO"
            + "C9gATgC/Hp9aA/a0DEA+t29MC/KyAhA/BM72C+1oJRC+U8A8A+ut8NC9Ijy7A/H9K3C+DgHeC+Ed8zA+kPEsA/ZSTAC+XOPe"
            + "A+4MfjA/KMoMC+u2ehC+07iiA/KqRVC+iCaJA+8mrKC/iVTKC/BtXxA+kU48A+vDxcC/HMePA9+SbIA/DDfWC9gmpLC+qP2g"
            + "C+nPszC8ExWRA/MDChA9wXWcA+pJyyA9MyfgC/D150C/RLGJA9aecKC9LyU3C9M1NJC/UPMTC+yqoZA+lmQsA+Xt5VC/JXxO"
            + "A+B6BzC+3yH3C9ZBIuA8s0mkA+snHeC+MRxtA/K0dSA/I3uNC9y+XnC8xWtEC+hlBhC/DImgA+tz3iA+4KE4C/MDAaC+2d2g"
            + "A+Q5HVC9mSU1A+lqWUC/DIk7C/Oy/LA9FByCC+87I1C+nLXdC95TsIC+b8P4A/HNfgA/YdtmC/H3XqA+hl1bA+7jQzA+e0Xx"
            + "C/T56/A/AHXVC/bQYlA+h1vmA+nZOBC+iZWrA953qzC+IrAhA9kbYzA+YY/FC9jyjiC8NlvdC+3uqnC/OFLWA/F+6RC/ds4U"
            + "A/QDyfA+gT7mA/dt52C+g4jsC+1+sHC+zsWPC+QJhOA/MarnC/gDv3A/TIYWA9dwoIA+WvIPA/PVkuC8pprUA/OcbSC+hTwV"
            + "A8o3GKC+pRhMA+ouuiC+xJEqA/PbHIC+h8pDC/C5/9C/A8lHC+6rMDA/BQIlA9UI+aA9FNIEC+lgrIA+oqtpC+p6yCC9rB03"
            + "A+owotC/HpEyA/JB69C+k63BC+hTb4A+OW3SC+LVtoA+ldWRA+D8ZUA9aqdZC/Iy57C90ihWA+k/HpA/UTt+A971GtA/IrI6"
            + "A+kREnC+chB7C+nC2MC+XpTuC9B0EHC+6WY4A+4T11C91RJmA+vyskA+nw6yA+xw9aA+iF3ZA9mNfuC+2zv7C+T49HC+sfvn"
            + "A7v++EA+u372A+YroXA/BNO/A/EowVC+wAlOC+eXJHC/AwuNC/hL8KA/LnPWC95HEuC+O/fwA94LmZC+2GUOC/B593A/P1pk"
            + "C+ilqrC96jBVA+wgM+A+10YCA+cgDBA+/DdhC9iI+UA9QugSA/Z/XYC+1EZ0C8qKgqC/Jz2wA+CpVCA/Czl+C+nj0+A987EH"
            + "C/I6CEC/OFGHC+YPqcC/JUgTC/VDyOC8O//2A/VaicA9GtfYA93RiPA/DAOKC/LKUnC/WtVJC/knhsA+ZMZhC+pxPwA+4cbz"
            + "A+xGTcC+/XumA+j2gWC/B/oaA9CMZdC+tWvsC9N/lsC/CfUXC/QNvgA+7ZDBA6fkViC+kP+XA/Fle4A/WmReC+9sa9C+DYTT"
            + "A+OVUuC+srXlC+1ccEA+6UM5A+kx0uA+gMQVA+w0j1A+reQ/C9qRvgA+tJo1A+JZPmC+o7gbC+hBqLA/KzikC/DaVEC/FC04"
            + "C+wGjcA+k/fPA+fU4JA73y8QA+15lfC+Q54oC+2gtfC9/b9IA9XVtbC9r3vvA+9lunC86HDNC+SLjxA+bHcUC+FMZhA/BZnt"
            + "A7ze83C/GsQvC/EBjXA+o3juA+pcj8A+6Gr0A+itFsC9VRKBA/L0AMC+yJdCC+zymRA+CVC5C/U1LKA9uy1AA+95LzA+Iubr"
            + "A+mnAqA+2qFzA+SNxVA+65EMC/OwbVC9+3QVA+YvwmA/LLQHC/H5S4C9m/VsA/I1KXC+7974A/BvuPC/GM4MC+sYk3C+gKKp"
            + "A+1kn1C+9BGAC/ZrlvC8EJL1C9/mHQC/KQp4C8tfVQC+8RmLC/jb71C+4h9LA+ylJpC8QS1cA+ghxHA77Mf/C/gORlA++miy"
            + "C9+nGbC+wdjoC+raRqA/LrXlA+nHhkA+6EHuC+/5sTC/SphnC+T2AYA+bbYcC+3lCkC/Rz2OC/CbtaA+FUR4A+5H7xC+rbyI"
            + "C+Sz3RC+PCBPC/Wv4uC92KIjA/EzeXC+PmHQC+sjlgA/PZjhA9Ki9JC94Jb/A9MffKA+eyC4C/nGfgA/L5bhC+VRwZC9fGbu"
            + "C/gLThA+nvNNC+PNHIA+7vP1C9yZkGC/AZm7A+FFnaA+ts2vA/LNtJC/UnnMC/Bvx6A/fcASA+3WZrA+xFkyC+vqqPA+toh7"
            + "C+geOoA/KQNkC98SRKC+Edn1C/OV+aC/gHKwC/F37lA/ED3eC+kKfnC/GZjhA/AeaKC/SGp/A/QUmVC/QUUxC/HinHA/EORU"
            + "C/U65HC+6cKgA8rc1jA+8JJoC+FFc6A+Zyp8C+jtzkA+V5KOC+4V+rA/b6I4A+LrB1A+0b71A+56frC/E3UhA/wOQRA/JigT"
            + "A9wnjRC+K7sfC+hEVnA/F7qI";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_cnn_layer_0_bias} weights, 20 values.
     */
    private static final String CNN_0_BIAS =
            "A7m7fsC7bXU5C7UBq1A+QqUeA9R/HsC8258tC7jaIqA8N+BBA9gFaqC7KGoXA69MbnC7cUN6C60JF9C5tB0pA+n3SsA5DxBD"
            + "A9iKl6A8zdf6A7FHlJC6J1aK";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_cnn_layer_1_kernel} weights, 1600 values.
     */
    private static final String CNN_1_KERNEL =
            "A+aeJ6A+d71JA+OWzGA90FbFA+jpGnC+Y2hqA+xnCgA9rYncC9lvCoA+h8WsC/inUlA9JYtaC/TiGGA+rFOxC/Cp9aA+fCyh"
            + "A97spoC/Qb3oC/agTRA+0g5BA+05tFA9oT0yA+5mihC+h1hcA/DJF9A/hM5wC+UG1QA/zhphA+v44ZA+zhZyDAFx0VA97nFH"
            + "C+MEntC+bQmeC/Z95RC8lfZBC/E2QXA94f4iC+iTmoA+D8TBA+2rlvA9kyeQA+feLvC+p0AcA+GgOCC/KPRiC+IOtoA8B18P"
            + "C/LeT3C+Bri3C/hiwTA/h7rcC+VOM3A/AppOA+9+1jA+Dk6tC9pCz5C+PNeMA9l8daC89SvGA95eolA+wpirC8l8W6C9qC3n"
            + "A/azY3C/E3uvA/F89PA+9XnyA9NIMPA+pQ7cA+NQaaC+FqREA9wzmZC/IumaA9j8p4A+Bh9cA+lnkDC8N7Q3C9JULlC+JDAr"
            + "A+poCdC/FjioA/Gg6mC++Wt2C+6uTSA+7XvIA+FGutA7XsyqC/R6vaA9b7zyC/mpH7C+jR1HA+la5gC+B/e+A+rQAuC+tyFx"
            + "A/vhgWA+MQ6IC+GDxtA+eQU6C/AN5MC9ohumA82WgbC90XFcA+Wmm+A9hre4A+IMOPA9toLeA992idA+hccmC+r6D5C9oj/u"
            + "C92fAcA+CRdQC952KVC6xhV5C9g9svA9O/sjC9OXBJC6wTqdA+P5ckC8qwsRA9mgRmA9or/OA+K7CjA+SsfaC+uNb1C9h4U9"
            + "C92pAEC9nnESC/lEuyC/BDWcC+pTkyA+IHC5A7+IQLC+IYQ8A+irbQC+d1klA8ioY2C82Dr1C+m2uxC+dPqLA98TzvC+DS4O"
            + "A9+fdzC/Fl6aA950wKA9sZ2CC+juTiC+UIJJC/H0gsA+2jrSA/K7NCA+Q2fkC+nCY1C/EfBvC+dnnMA+3/gjA/IgV5C/EFg2"
            + "C8g21zC9kVsUA/URDDA+ij1PC+t6v8A+RkqdA/mMEBC98h7mC+ugFHC/OT7mC/y0vHA8tpBKA/RRUFA+pTqjA9r6PvC/K3Uh"
            + "C/isUZA/l8yOA++OrAC/BUkOA9sIH5C+CWrwA+hES/A+ZLSNA/Mph4C/EpaSA/ClHCA/ZIRRA+/sHCC+kvugC/+smwC9aLSK"
            + "C+gbUgA7GPSuC+RWn1C96++NC9x9AbA+jk/9A6OLB6C+obuMA+noGqA+twS8A9wF5EC9lORDA+3bgTC/CXTFA/CNaAA+PQPa"
            + "C9Kg5BC9958jC/oY/FA+0yyEC96v1UA+lLZBC+4iK0C8xqdPC+VHBlA+Dlr/C+2GfxC+FkDqC/MFcIC7XnI8A9KqOtA+1ckd"
            + "A+z7SiA/EAQyA8sZzGA83lLRA+XGR3A+qAeaC/0nUlC/U+0xA97IaMA9wtr2C+CP1dC9Rc6sA+UA2iA+pVoYC/A2TxA9ipsv"
            + "C9s8y2A+ZdWyA+52boA+oOR1A+nWPtA+tosaA86oTZBAEaJzA/Eb5/C9SDF3DAHzzeA+jJJ5C+flYEC/IrjpC+QCq7A+OxW1"
            + "C+0buMC85ddgC+VBcBC+VTBJC+0g6mC8kL6UA+ChexA+3n14A+KSpzA/FajfC+x62wC9ta8pA98deZA7Cjf9C/idp7C/BV4Y"
            + "C+S4MGA+QtI1C+JorXA8SSDbA+6+qSC/L0QcC+GRmsA+K5PNC+lw6yC9hVFDA9seZvC/MPxyA9t+ZxA9fjN3A9WZ2wA8S6FU"
            + "A+G5EuA/wV2iC92M0hC8/3wXA96ur7A9Z4IKC+I3cYC+2RTTC+aFdtA9d/PCC+ufqYC+EZ4wC+gSbIA9iHXwA+oiCbA74S4u"
            + "A+BjmTC+A1wYC+lkFwA/qpXqA/CtKqC9dh7WC/3gxKA9Xh/2C+aPuHC+wIOZC+Fo6CA8uvvqA9aUhjC9ox9iA9wAUIC9hzMI"
            + "C/eq4ZC+wTBZA9VosMA+70HRA9qM/oA+39G7A+hvtuA9BWYhA+m274A+tPszC+EckuC+yiLVA/GOEeA97o4dC9oEZpA+uqTb"
            + "C+m6ClA9876pC7Ev2eC9NWuHC8NRgQA+by08A6f9IiA+5QizC9Azv1A/CqbjA+EbxWA+J9a2A/WT5wA+TFQ3C+PxG2C+46kq"
            + "C+3QQdA+ItqYC+BRixC+ES5BA+fL3cC+SkeqC/M36RA+PeZYA+zPC3C9D6otA+dXr/C+lLzxA9jwqHC+OLP2C/QnSOC9aRk0"
            + "C/AKE4C9yg8YC/ZGGVA+nQyiC+CSbIA+kj1xA+/a5gC/L6w/C+LLHNC9va1aA+tBt2C8sHNlA+uPI5A9hpUrA+iVVQC/HcXF"
            + "A9Z075C+lojfC/FohqA8v9pAC+2o3KC/AuF6C/OMXrA+14CZA9yj3BA+nmV8A/BgYxA/h8YAC+qRp2A9p440A8VZJuA8lC20"
            + "A9Ac0qC+1U5uC/EPvsC+Jv87A+kfapC9Wt7eA+xV+7A+GrGSC+VUtJC+M90SA9Q0IkA+Qv+PC/JLVGA+989fC+1/rjC+SwyJ"
            + "C+aQkpC9sgCZA/jKXOA+sLTAC+cmngC+w4z8C/uzoVA+7oxIA/HEGJA/MKG+C/MESNA/+aRqA+14Y8A8511yA+F17qA/K11X"
            + "A+27EHC+qCIUC+GgyuC+1pPiC/U/5+A+frZ8A+fFrEA8mHqAC+xMV2C+AVM3C+1YnOA+iv/SC8UnZaC/YaA4A+HD4yA+Pgm7"
            + "C/Pu6FA9YdYuA+AiIMA/b94eC8uJlvA/E2ugA/RHVxC+ED9wC/QzRhA+2ErYC9eqoFA9UzThC+E6DHC+mQcQA+ne6aA8qvhn"
            + "A+WwFDC+hHVgA/kODKA+oM99C+sfA9C+DRMOA/J2l3C+Xxy5C/D6WPC/XVpsA++ki2A+iUi7C/DHU+C/Q+XaA978iwA+p1IR"
            + "C/LaGIA+9KA8C/o066A+S6+WC/NdxyA/sgr6C+0ro4C/DRrKA/AsunC+lw1BA+e+L4C9xAhuC+n76YA8kCv9A/BkCWC+AWl/"
            + "A9/1vmC/xQyzC+r4rSA/A5o5A+zUCrC7vmpJC+YJdCA9yES1A+rNN8C9cAmzA+TSXuA9MBsAA/LUL2C+jBNVC+MZjyC/FON6"
            + "A+Z7F9C9wclWC9dh7WC8JSpcC8tjTbC+Y4MnC8+Y2LC+EMhHC+Q1XOC7rIeIC/RnLaC9qRozA+neb/A+hoysC/GhJRA9oIBL"
            + "A+SkSFA9kjyuA9INIiC+xvpAA+MK6XC8td6pA+gRK6A9lTpaA+McyWA+RYs/C8HutIA9WSTKC7+uyFA+4WZaC9wTFLA95geB"
            + "A9s/cgC/PTB7A+xUayA+CIi1C+yxvfA+NhXsC9ke6QA/BFURA90UCaA+L7KrC+WCI2A+l3JxC+uVVxA+N2KVC/WIKuC+eFWX"
            + "C/vtA+A+i5+ZA/FxFzC+0P1+C9lcXdA+cEZBA/FvLHC9zmSyA/BFTvC/Mz1LA+ogNgA+uOtGA9G8N1A+9c/MC9x8edA/cQMA"
            + "A+bUQTC+Gs90C/mf2LA+kKplC9LyucC76wEAC/V5LiC+tvn9A/C6syC+lNh3A/PdLQA/gHuJA+970GA+GgPFA9quh2C9zW9U"
            + "A+2R2bC8txV2C9jq8zC+CbTdC/P0pFA+2CumA9/alDC+yQYDC89aJ5C9GhFZC+yPp7C9DIocA+mLDRC+wHXVA/MXA/A+juTi"
            + "C/CN2DA91/rjC82wpIA/AmgKC+0reZA+50ulC+smvXC9S7NdC+tTQWC+GCVbA+d6RhA+qX3yA/QbD/C9j7M/C/U8eTC+vEyd"
            + "A+5PeIA/VallC7ovGdC9ZEORA9YaP0C+maisC+E0j1C/ighbC92uxKA+LYRNA8i4eIA93rprA+q/orC9vSznA+5oHLC9khXi"
            + "C8vudnC8VNTZC/tAzIC9zAaEA/NhOQA+zSqmA+aVcEBAAPWZA/EqtHA+Cu3dC9C3OcC+ois5A+eGRFC/M2WIC+R6omA+n7/o"
            + "C+N+jNA+AzBRA9lK4aC+Rne0A+tEwWA9kKw7C+74SIC+zE/jA9z5XVA/K+xGC/DdCmA92IhyA+V26kA9hTfjA+fieeC/O079"
            + "A/FrauC+ndE9A+ev4eA7x4a9A+LwH8A+fBVNA+GRwIA92TvBA+JBeHA98qlQC91TNuA/X3FUA+aO/+A8lZx/C967CjA8aWHD"
            + "A+nas7C/YopyA9nRplA9KZdzC+5WMCC9I+tHC9nMOFC+0uifC+TGo8A+qq+bA+MNBoC9MN8wA+Q2fkC9m29qC/g5+pC9KcoZ"
            + "A/GvnKC8mihrC+Yf8uA/pme2C+U/keC9CKZHA+aJizA91yksC9QebbC+5cLSC9DCU9C8yIzMA+YZ3tA9AHJmC9uL6oC9leb/"
            + "A/Yod/A+nNQkC+jR0mC+8+cIA73jN9A+4lGgA9+fTTA98cABA+ioKEC+7NHIA9iDJBC+hUAlA+VjPOA+Jjd6A9gQKvC+esYE"
            + "C9oYLtA/J9RrC9T4KlC++WjXA98zsQC+sCtBA9vpcHC+uzQeC9iV/7A+43irA+ks7NA+FiBoA/L89PC9lzrlC9zX8PC/ba4d"
            + "C+ML5RC9xIVeC8LI5pA+jhrnA+k1FZC+o0SiA/NmkFA+jBRiC+xARDC8K1r4C/JdQwA+gsUZA+R0vHA+h8fnC/QD15C9hTm5"
            + "C/ESzxA9kJ2MA+Vo9LA+3AGCA+Bc/uA/A9FnA/HaOhA/GDNyC9s/lHA+z9YPC+m1UEA9ue4rC+o9/0A8YLnqC+m1pkA/GB3z"
            + "C+cjNZC/MVABC/fXTFC812VCA+Y7ZGA/AN+cA+YYJmA/LUdrC+Gp6yA+wnIiA/V2H+C/h6fbA+hRdAC/SCBxA9DeraA97SPV"
            + "C+gOAiC8DuT1A+yob5C9rg7fC/mcWCC+oLx7A/Sh9tA+I2/BC/SDdhC+a0SiA+iIszC9ER0pC+m7L/A9CrKsC+x642C+kTak"
            + "C+305EA/RwB6A/WvZRA/J0EpC9HNc8C+UXyiA/kbMpA+Ya86DAk3kUA/E2CvA9OaBQA/4gF5A+7qtYC+u3KkA/GD9gA+jsrN"
            + "C/QCxNC+qlzEC/cTrWA+j4u9A+kbH7A+VlmOA+dBE8C8S6vQC+4QFLC9FDfFA+KfseA+v9roC+9lkIC/IFzdA8tyvmA9IR/W"
            + "C+woSdA+WBxxA9ovt4A/XQTVC+rtJGA+184QA+0ioLC976rOC/Atf5A/ELB9C9gjdIC9AGfPC9maK5A+xqA0A/CpBpA9vF2M"
            + "A/hnPWA+hqh2A+k/iYA9NBlCC/nLjQC+fcesA+hZ13A+tiqiA/GYWMC/MbXIA+vuBMC+JZzQA9k5LVA9IabJA9+2/jC+6ofC"
            + "C+vdzoC3YaUqC+vy7PA8ySVDDAFjiGDAJ1OkA+h5MUA9dmhyC/d/X5C+SLn+C/OiEQA+K1zAA/BHlwA7Z9jCA/RvfTA/DZnM"
            + "A/OiqAA/GzAOC+/b/PBAGoZZA+7jCIC9NXRWA+EmdBA+ENqhC+NfmcA8f8g3A+LVOLA9L1FcC+KMhpC+dmg8A+XHS4C+Wj/N"
            + "C+PJU6A8hCd3C+TQ5WC8/0adA7P2+XC8WCOuA9M0RmC91qpMC+BxJeA9qIDlA+cwCbC+sfRNC/n87ZA+gdn1C+4mpEA9UpDh"
            + "A+rNTMC8cexBC+CBPLC/JNF0A+5CIlC8b44tA+cSnMA/CSwoC/JAE/A85Wl8A7zg5rA+E1aXA+wpl0C/CtbtA+uehgA9hJLy"
            + "C+8/8mC/UprlA+9wQ2C9P2qvC+gZQYA97w4LA+onl5A/DFJQC+cEyMA/FHXVC+12PDA/GHoYC+j0pWC/QNH6C/BE/jC+34ah"
            + "C99yggC+jtEYA/QJBPC+O9h7C+FdTpC9DO4lA+ArOJC+ZqagA++pF1A+FJBgC/PJmvA/euWKC9Fn3xA+0AktC/TXE7A+trs0"
            + "A+u3rlA/nonKC/enctC/L1cuC9slFkC+tQ67A/Mh8qC+enaWC/8eGxC+g/7BA/L9saA+z3B6C+ALZ0A+hCJoA/EhHLC+4QOn"
            + "C/GRU4A9mLi8C+cM5PA+CD28C+eN2lA9KjMqC6m6DbA8IKW9C+mEidC+FHZsA+7RqHA9xbjqC+rVDsC/Jqz7A/UUy6C9FWk5"
            + "C+xuTiA96AOKA+bL8rA+oeYEC/F5p8A/B8a5C+3OTrA8pKWcA91ZzQC+BdeIA+3/LlA+uYTkA/NNdJA+TS3LA+nkYGC++BT5"
            + "C/l6PsA/A45+A+ThDPC8t3fRA+N/MGC+uPrfC9os4RC/hgVoC85keSA9Sq3ZA7ALj4A9gBkQC+aoegA99iDvA/Cum8C+Y8Lb"
            + "C8SgyUC9ynMuA/Re/pC+RNTMDATEKFA+mekIA+7O2zBAEv7FA+DHlwA+B7hOA+Kd1/A+A+jeC+H5i3C/DvxZC+bU3GC+ej33"
            + "C+4oHcA+gHwgA9rjb6C8Bl8fA+yFZgA+Mrf+A+nKxtC/CBBSC/H9wFA/EIiUC+sBqcC85FJaA+WcSXC+Czt1C8k1jYC/MBld"
            + "A+vfw7C9W/eSA9TJlRA9yNgWC9M6NLA+PzZpA+Onm8A+MGNaC+GQhfC8yt0+A+F6cBA/mpaSC+C/GmA8IRK1A8lJZaC8cb6J"
            + "C9rWAtC/U7e3A7B8aIA9Q+B6A9haRcA9t/SLC8uElSC84KmOA8iGUpC8rW/bC/AMAnA9xOfGA+gJ4TC9f3COA/PddFA8AIfl"
            + "DAP3a0C+P7zkA9mEO2A/u2yLA9lyBrA8WsxqA+Vj3FC+NvWIC9WHhnC/BtbtA85x3nC9FMRjA+v4TLC+m3kQC9iNADA+B2kj"
            + "A/dkleC+zKnOA+RF4LC+57v5C+so0AA+488LA+HLKWA9oxXyA+iC8wC+o6eYA8496XC+qt8vA+Z4jbA+F3HJC9/hZRC+vwG4"
            + "A+SBmPA+TPqtC9cv4kA9CMKXC86OFrA+7MufA9jJexC+qjTsC+39Q5A+mwfyA9zej3A82RqDA/RGgBC+D/70A97LU1C/NLHu"
            + "C5NpwJA8AfCxC9SRKzA9dRRJA++EGrC+BWBCA/KQyiA+FwsHC+7O+qA9rNCuA+X7yhA+nzEKC+Ji3HA+V3QlC/Se67A9jw4l"
            + "C++DeTA8/y9VC+ndIHA+1agVA9czBeA+Vev7A+5yLAA+tYcOC9j4q3C+kDgZC+n1KXA8FdtJC+voHLA+CkbhA+PdeqA+7DWt"
            + "C96fcvC+zgBtC/MTxYA9GhK2C+TRLLA/EOLzC9gIAIA+2/6gA+Lps5A+s31BA/MxREC/crhjC+unkUA+sqAjC+DSPVC9oiE1"
            + "C9PVslC+gpmVC+zAlfA+DBDYC8htz+C/DjNqA/ML3LA+F0KaA6CSBQA+GE9MA/BlmOA+JuKoA/TC9zA+jhDxC/AC2dA+Qnma"
            + "C+qqd6A7yRRcC/igF5A9QLe2A+V9MLA+I5o5C+5UKiC+gYUGC/3RseA9hs+7C+5cIrBAEPeQA+swV1A+IQFtA/dPOZA/W08e"
            + "A+GM72C/IIBBC/ERfnC9yOCUA+tA3UC+ubkwC83w2tC96LflA+u2ZRC8AW8hC+6tHQC+n4YaC+OqGUC/dDe1A9bwkCA+KKyf"
            + "C/Zm1qC97KFJA9hfqAA/fZVXC+fe1sA+9w98A/K5H3A9yebvC+WfapA+nsHCA9tzelA+G5c1A99QANC+iOIZA+JiazC8+oav"
            + "A/Lnh9C+a10DA/gf3fA+lSflA/PXgMA9kCqSA/CbNKC+PwG4C8zruFC+1wwTA+Sl/IC+NN48A73Rp4A+mkJsC+Jrk9A/IuBM"
            + "C+rscAA+Sgf2C/FvmpC/P9ZBC+jezEC/ou63C9r7MWC+FyIYC9w9mBC9324/A9kvR/A+t9kSA+mliSA++U9qC+hZvlC+Ad1d"
            + "A/x14gA+9HbRC/uhCMA/tgNxC+uMDwC8rZuvC8l4RLC94rnUC+G8mKA91m6HA+iQTVA+AnMMA+MSXdA+hENOA+iYMOC+3Lwn"
            + "C96lSCA8JcKdC+SF37A9ojTsC+GyQgC9gzrNA9RIREC+EUmEA9+NDMC+DnieC+sk4WA+rp0wC/ELvkC9ogzDC/R/VBA8ZXMh"
            + "A/EjM3C8D19kA+XlfaC+TzOHA+IPxyA+ae18A+u7SRC/B61cA+2EYgC/CmCzC9Lk7IC+VMK1C9h/mhA+wbhnC9hSuRC9qbdv"
            + "C9qxscC/S6atA+8Mb4A+HtKJC/MK77C97xLCA+IWWQA/DlQ/C+lizcA+e1F6C9rtaFC+5gfEC9YfRhC6tCGFA/NZ3LC+6eEq"
            + "A+BztDC9OBvQC+ngCwA+OPFODAD3xGA+18v3C/JBUJC+67koC/a65HC+PWNFC9Ksu7A/Jf2LC+aYiQA+0w+uC+76gNC/OUW3"
            + "C+sgV5C/seg+A/IcKgA9ekjRA88Q5gC+AypJC+Uoy9A+tfAsC+iRNAC/JRPoA+3yuIA/BHlfC+rR51C+IYzjA+p73PC+KnLr"
            + "A+Yk+pC9M4THC9n0cwA94P5IA/cQkYC/CtMgC+MHKPC+g2CvC8lJhzA+Yer/C+3WrfA8hmjQA+gB2fC/BceKA/SHnVA+S3jN"
            + "C/SNiIC9vmNIA+p1pTA+8Mo+A+4qd6A+izO5C93bVSA9RhC7C+zmnPA+uUHIC+iU+tA+iqz7C/MmMwC+KgWaC+tZW8A+z9w4"
            + "C+8melA/JlIFC9sMQIC+FtrLA9sitGA+o7vHA+/G3nC/iYsvA+taHsA96rYHC99xXGC+dAjZA87lmwC+tenAC+6FnJA9pnmJ"
            + "C8k8/BA8DR5MC/ZJfqC8UWOgC/1P1MA+lL0SA+AeA/BABTsQC9Ok4qC+JmFrA/iYFaA+ag01A+j6FeC/CaHLC+M36zC98bBH"
            + "A/JixWC+symAC8m4leC+nmHQC+ufJ4C99WU9C+Z/9YA++BIXC+MW1uA+1vkzC+5aQFC9wqfLA+9wLFA9fCQ+A+2ANHC/CVC5"
            + "A9oCXbA+WZa3A91XYEC9ol3DA+zduYC/LSLZC+aKceC+J7iRA+hTG6C9TrU6C9qUiMC/sFGYA8AY5VA9rD4/C9iXvfC7Rb7n"
            + "C+xbzLA+QuDjA9j+aFA9g+J4A+CPNnC8Z+x4C9FaL/A/XsqsA+VOIqC+17AtA/MHMVA+FISUC+iqXGA+WbTdC+sM6SA79I0F"
            + "C/3KUnA9iaU9C+i9q2A/pws5C/gbHZA7OpQQA/Wfk4A+NRdhA+nnQqC/EknkC9dGgBC947cPA+BX1bC+mLjxA9aHw7C9tUrD"
            + "C/WPxhA+dFnaC+JknUA+55tWC8nd7YA+aBnSA9nD8WC9txo7A+qlfrA+2VX4A9NYmfC+Jj3FC/Gd1/A+j6E8C+DKigA+iF5f"
            + "A/OrxzC/Yx5cA+HJ0XC/EtQLC8pPkTC/ELGaA9CY1HA+mb7TC+E/B4A/Ig3+A+ngivA9hb/VA+okDZA9k7pCA+RN21C/VUob"
            + "A+RvR7C9sWsSC+QfJWC+NoUSA95FPSA/BcG1C/J6wdA+6gJUA+yAPNA9VjHQC+y1NQC+lmITC+v9LpC984o7C/WmMwC9qeXH"
            + "C+MYKqC8syd1A+vGZNA+9PnkA9uyYeA/BO2iA/TbPhC+i4c4C+rsKtA9t8OTA+fZi/A6a5XMA+oFs6A6GiKaC+oN1hC+5hze"
            + "C87uNCC+tmtAC/ORA9C9RQAaC/BhUBC/E35OA+CPbPA/He6JC9rxd6A++ZflA/PsDGA/fka/A+g1stA9qH9uA9KL5KA+Dru6";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_cnn_layer_1_bias} weights, 20 values.
     */
    private static final String CNN_1_BIAS =
            "A7ksjaA8pep1A8Hw+wC7lwTqA9wPsHC9WwPHA8B/c7C80QneC9pbZmA63apAA8kVkkA81H1mA87nNFC82zeUA9zVz7C7fyzG"
            + "A9ATp4A82cfyC5WKPXA895RW";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_cnn_layer_2_kernel} weights, 1600 values.
     */
    private static final String CNN_2_KERNEL =
            "A+HLidA9lJEBA9jRv4C+84LkA9luCdC+CUs4A+asq8A8soVZA9N/6PC/gACoA+GLZSA9S61IA986/7A9l+nMA9HeUjA+ZpXZ"
            + "A8VG9YA+H1PFDARJeNC9g0mJA9u54GA81C1JC+Vwm3C+1zFNA9cHygA7ZyyfA9ONuZC9SkbhA969kCA9qkKvA91FOPC8mAv7"
            + "A9f0hKC9y4PdA9N7bxA8W5LOC+luzhC+gPwvA9kfKZC9nIc6C+7XK9C88axKC/FERbA/EugIC7w38GA8wsZOC9TNe1C9xoHt"
            + "A+H06HA9Uxm4C+NrylC+HbRGC+jn01C+KmpEA8dP6wC9j2iIA+dQenC/mQSBC9mH/MA+EnhLA+EUdrA9sL8aA9q8CGC+6Iom"
            + "A99mQKC9t6XZA+goNvA9klJjA7ml6RC/XpcpA+Vm7KA+DZqVA9pz+CA9iHOHA9rci4A+MXj3A9jnc0A+kkLyDASdiEA6t9x7"
            + "C/eEZjC9Aoj3C7n7W9A+0ziTA81+91C+f9HdA9t91RA8qxQVC9yUQkC+JFd+C+Aj4YC+PqJNA90ZaWA+jECvC9tYP2C+R9Ap"
            + "C8Xlm9C+Rsk6C9qtzgA+2m5UC9kHmwA9BiJZA9pAQ8A+9R+jC8Cx5MC/Dg6mC9SGwVC+PHIZC+aOLjA+I9QoC9isygC9uJ01"
            + "A7Sk6YC+tFkyA+jrO8A+nN0OC9nh3dC8SRX0C9jgUeA9mEytC+iPBBC9/ShKC+/e2vA/DXd1C91ORDC9+WMTA+OEkCC+wYJF"
            + "C/rWd3A+HPkaC+4/xEC9t+/hC7rZtvA+ghJzC9GwDYA96rHVC+Enw5A9pvcAC9150KA8CJDoC98KgJA+BCY1A9kLxuC/spFT"
            + "A8TFv5A94O8TC9mGc1A+Ofh/A9oVxWA88FBgA+/WdVA8I2HlC/FfxdC9rOEkC8DZRoC94SnMA9kgu2A+GGbkA9ehU6A+UQtj"
            + "C7f8uoA8l1ucC95B7eA+M6cRC9rBN3C+NxNqC9jg0/C+gk6+C+RCVsA/AGZNC9r+wUC+KOENA8zWDcC9u6eRA+fQPaA+Dhm5"
            + "C+1scAC+lxLCC+WvGIC91Da5A+DY+TA9siUxA96LhrC+6yXVA9/bTMC+DqOcA+gfEGA9LLJ7A9iDSPC/ei8nA+TLmqA96+rV"
            + "A9ytgbA9Ww55A9ttoBA9pvy3A+GoXLA+gcU/DAT+NUC8QZkoC/C2idA8pVXFC9i7b7A/Qk+pA7nS/RC7zP+4A9bZi/C9piGq"
            + "C96rxRC9zII/A+CILxA8ybugC8iSWuA+SNMoA94OriA94DvFA+8wmFC/Q3MZA9p63RA9o5brC9y8GtC6tREZC93lZHA/MoGI"
            + "C91GG3C+nLcsC9zCAMC+Egp0C+BiH7A9ppCAC9ujgyC93OW0C85bqlA+U9eQC/2p8XA/BougA/D1UEC+Kh6CC+aQkpC+GpPz"
            + "A9revmA7ovgSC+LLcLC+kK2sA7sDpaC8mAVFA9eLITC9Wx1dC8sMOCC+F87ZC8htJMC9eqqLA9DlyEA+kubJA+aAlOA+GRW+"
            + "C+9MmnC+e+j/A9h9TTC9+UyIA+iL+hA9pGOaC+PSy/C/DuqWA9MwWxA+DXnRC9kZmuA9lJ9sA+rgG9A+JO9GA+nBgNA93Xd1"
            + "A+RgmaA9tpnjC9j8HSC+OmgrC+51vmC+b38GA9NisaA9Ms8QC+e8ujC9k7BMA+njb0A+jtiQA81HtNA849UnA9jKavC+it+T"
            + "A+cLlWC9n2SMC+6b++C939pAC9mLWJC+0InCC+JhVmC/TUQDA+vq/NC/DyAQC9IISlA9Q7D6A9s0Y7A9j1hIA97rLRC+nc6e"
            + "A9C+wXC+EW2xA+CsT4A9AdTRA+HPnkC/Qn0kA+A5LiA96fDlA9puPdA+jQF+A9JXf0A9Em7oA+WWPcA9igjoDAJkk9C9QpGQ"
            + "C9c5lwA9hv8gA9QGVmC9F72nC+A7eVC+w3PSC9NktmC92tCBC9tFtRA7wbGRA+cSqVC+KSA6C9jqxDA97Ek0A+e3OOA+RKh+"
            + "C+xvpAC+9z/7A+HgQ6C+JA1OA+APNFA9+e+VA9ki9dC+JR0mA+AyfuC+cEpzA9i08RA9dOnSA+VWxRC+J4zKA+SZkTA+IFyJ"
            + "A+PJlrC+joFnC9RSJ/A9qZsEA+fO6eA9u4LeC9uZiAC+UKZ2A+C98JA9l57uC8XRPXC+tcjaA9uAJ+C9xQgSA+PkNnC8botj"
            + "A9xfUaC/BApsA9+Qp4A9Az9QA94y9FC9ZSOLA9EZszA+RL00A9TSnqA+CNkwC/3c0MC9f9X0A9+u3dA9mxfpC9x5ugC+Rkvs"
            + "A9ZdMuA8gwnVA9CP/uC8ik6jC9/apQC95jvvA+SDZlA8/LmPA9XDf0C8lEj4A9IKNeA9PSqzC/BrcCC+c/hVA9qEv/C+WXsx"
            + "A95zikA9xLyuA9XUayA9nm4HA+EnpjC9z/n4A+kJkYA8kMr0C7IENXC/R7gsC+gkRjDAuk8OA92mUGA98I7eA9R2cXA+KILx"
            + "A8+Dj3A+ax/vDAGsfjC9792pA9OP9bA8wnbTC+Vb2UA9k7BZA9m+LdC9aTuYC8CBIoC9OV5bA+FNahC8mwK7C+kRT1DASevZ"
            + "A9xuTOC9ExmDA8BT96A8mf0BC+uSAZC+mqiHA98bFTC9w9GxC/GLidA8LMIAC++vovC9pD2/A9BAg4C85Wb4A9Y1f0C95Fd+"
            + "C9y6oiA8kdW2A9Esj7A/iQpnC9qqBtA9fnZdA9gn1gA9zKM5A+RJUYC/o/sWA9C/PaA+F7x/A9xAvxA94Rf4A9Z+F2A9b+AS"
            + "A+OEHuC91cD9A+nkEtA8jaxbA8xzb2C/b2ytC+l67/DAusNMA9uCqMA+6SM+A+BJz1A+YX9BA8r/cJA+kdGRDAPIjOC9pPLW"
            + "C/glQNA7rv4FC9ak2xA9sgn7A8qguiC9/u7ZC9PstNA9tySPA+pOEeA9r9H4A+gOlPA9vupkA9tfShA+br+pC+5I5HC8AaK4"
            + "A9ukllC9uThzA+Bv/BA+84FzC9/SH/A8vb6nA+De2vC9yCcxC852kwC/DJF9C9O1zNC9t2EdC+loKVA+KuB+A+7zOHA+ceyS"
            + "A9fE9WA+/5vcA/ClVtA+zdeIA9eFmuC9TMVAC8fkbCA9y6dbC+F0aIC+O9XcC/HLIgC+qxxlC94xN7C+EuIzA9bi6yC+ox20"
            + "C+sD+SA+TGIbA/AtbLC+gSr6C87DVIA9fBOSA9f3eVA+V82KC+ch9tA+KkULA+ApOOC9xuSZC+UUtaA+VRtQA9Gm26A+iy44"
            + "C8DNM4A9oz4AC7vr9YA+D2DhC+xQ0GC9rr6CC/FjO+C9iBZcC/IaXKC+e7pWC9hLBOC+MAmzC9pDB8A9ldWlC98CDFA+kB3i"
            + "C8+Zw5A9FOJ7C9RUjFC+pxSYC8f2xcC+20w8C9yp9TC+iza9C7LOiBA+u3pfA+pugIA+KW8iA9iOlIC+EZ1nA+1wkxA+KO4o"
            + "C/A/ZvC+QBZIC+G20BC9i8T6A9umg5A978rJA92kYXA9xPtVA+JgogC+BMGpA+miHJA5p/O2C9ZJi2C/WCO4C+YCxNDAuDoq"
            + "A9yT0rA+dLCfA9kBcSA9xofZA924y5A+pKbKDAQGFSC9cKkVC/HriVA9VXV9A+A4FSC+Ne/pC7X8+0C+Fkc0C9KbNzC9tvCo"
            + "C9b4aGC+BKlIA+ArEtA+B++eC9x1cnC+XMq0A9lFanA8J33zA+NmmMC/cYD1C+L5SFA+nCJGC9xWTEC9DeaNC+Df65C+b83u"
            + "A9DPewC+o6GQC9yuisC+JIFvA+vSYxA+R5bAA+ugjAC+tkbQC78T5cA+d5dGDADaOYA/NG7SA/Mm/WA+BElWC+B/x2C98unN"
            + "A9HQ/AA8yvunC+Aeo2A80irbA9Rky2A72HLeA9O5TMC8UgbIA97YywC95ML4C+lQPaC/JWlNA9pqDBA95YFJA+REZ0A993hX"
            + "C+43b3C+rkbxA9IIl4C+GsJZA+PNhWA9//70C+EEV4A+WzmfA6jkJOA99PadA8wh8qA9knJ5C+Z2vCC8T1fCC+6x4ZC++xt6"
            + "A+MeKbA+tizcC+C1XvC+Q61cC/B7OnC+zSQYA8N9hJA915fMC963qiC8QmE5A+uo9+A9AucgC9WH/ZA9EPUAA9ISiKC+lBN3"
            + "C+shDgC9Kt4VA/C7yyA/PeWfC9lgeBC/pf/WC9MWjDC/uIcBA+o0YTC+wWzoA9/XEqC7kZqHA9xASMA9twM3A9urHVA9vktF"
            + "A+Ar7PC+I8RuA+QvytC89BQ5C6rczXC/PiVCC9FSnjDAgAjZA9oLhkA+ZGayA9MC89A8k9wgA+WfNBA9++CcDANUp3C9g18w"
            + "C+MWepA9zrzHA9pNcoA8jWtYA9s7FzC+tNmEA8MWBgC9OTBTC97dP+A+iUt7C+B8TjC+jLPlC9Ac7XA9ehIwA+4R8uA8HlZi"
            + "C/SiSJC+2YJmC8r5uZC96SgXA9zmyPA+AYR/A9KiEGA9L06HA+EifQC+oaAGA94Xl9A7yb77A9A0QiC+uB1+C+AFaCC/pOlP"
            + "A+Mt17C+cZLqA7olhwA9jm7RA+iHE/A9SbhFA9sd/HC9jHyIA90ulHA9vV23C84ZEVA9kUKLA+H4O+C+Q4bTA+QMqjC8Ewqp"
            + "A9xXmHC+857xC+VmYjDAoBSlA90/UwA94rWiA734IGA+VZeSA9SIepA9xeOKC/twFlC9hB9JA9U1ZvA9af8oC9yzozA8AbQg"
            + "A8D4nhA9eMcMA9AVASA8lixcA9mti8C87+Z4C+WgrZC/SEIgA9uE78A90khSA9i38XA9N2d1C+9sSkC+YsYmA9orVfC+aqgi"
            + "A+DBBSA9qneVA88/aoC7PDxcDAv1O5C9xLocA+gHvdC6rrzBC95i/gC/W189A97Rv4A9/wy7A9uzoYC+JKI0A+A8W9A92o73"
            + "A9zil0A+Du3MC/XIVeC+QuQIA9tToJA9FQfqC+SxHHA9+QnyA82KPQC7tsmAA9k4E3C8ZZefC+KtFKC+iE00A7pATCA8cLBs"
            + "A+AQ9SA9pl+wA9fE1zA9nOPEC+1x6wC+AS9NC86OXSC95m03C/ESdwC8JEVaC/G4mDA+Ra+wA73UXBC9mmXqA6r/0zC9UdEz"
            + "C+JYp2A9iMDcC96NyYC9kqYrC+meNlA+jY6oC+HwuvC9UdctA9t5duC/fOe8A9NJaQA+3KaHA+M6MjA9z6LwA8+MKJA9icJk"
            + "DA0/z4C91ocrA+abobC7nf+aC9eVQvC/ZVWCA+QHzIA+JhCuA9mRjWA+YTtcA9i8YUA+EhbGA9zWNFA+mpDvC//RoiC+lwpf"
            + "C/iWHlC9ZngEC9d4TsA8+Ri7A+Ru/DC+eXByA9lOrsA9ekCJC9jc59C88w6AA9Y99uA8nZ2gA92MMrA90WEcC9mw3zC73aO1"
            + "A8J6LcC+hBM0C9gsvCA+6RFJC963PSA9vKg0A+ZSrHA+jdxiC9ezNHC/EI+4C+GvcKC+IdPtA+afmLA+BRbbC+RT99C+AFot"
            + "A9yV8JC/bmOEA/HmQbA+jCokC+FQUIC9y6J7C99NcoA9h+GsC+JUsGC+TzvrC/C57gA9u+8HA9LRv4C+L0cwC+NuiwC+rIhA"
            + "A/XeEJA9GmMjC+o7NjC+Ana4A9dYH4A+iaCuC9B+xeA+te1KC91MqSA9LnaGA7rDMkC+COU/C9+0eEA+FRNzA+DlltC7rtLn"
            + "A9AG/HA+p5M2C8QVHaA+byDqA+JkkKC9onXGA+6LncA9NSKMC/Ph/RC+FFvyA9szJPC9kNi+A8tB3fA+TpHJA8p46FA9oY7T"
            + "C9GVM+A9lE9QA9mEmwC8nolYC+oIkaC+1ne0C9pxEBC+SeAeA+nC/oA+qQ9zC+BiZwC6lHAPA9mbGdC/CrEMA+mZEVA+nfXP"
            + "C+Uma6C+qoegA86ieGA+Q7wKA+JDiPA9lGh6A9Lp+FA+HLgXDA0xcFC9pl+WA+YGxEA8cPo+C8M/T6C/gedDA+LiLmA+J06p"
            + "A92e8PA+GcriA9jRTkA9Xb2bA+IA7xA+NyLiC/wVlgC+GRCAC/ASccA8knTiC+Oi9JC9zLrsA9RniKC+pOuaA9JpozA65wpm"
            + "A8tLW1A9uGzDC/D8aoA927UYC9sHCDC9sJ1XA9p0YCA+LBsRA+GaodC/URtxC+CvJSA/DrvtC+Hv3KA8zefqC939rHC9R4An"
            + "C7Y7qlC+6NvwC9IYjMC+W79RC+ft8eA+krBCC+fzpYC+DkT6A7gEHEC9+vhZDACZh0A/FsTnA/Co4dA8fGP/C+uDAaA9VUpl"
            + "A9iJ0NA9K0eSC+B7a7C+PTF3A9X7CfC+BljFA94JeFC9kl3DC96c5KC+eOo6C9JeypA9DGipA9pDa5C+iWzGA+RNHpA9o/Dx"
            + "C/FidrC98ftAC9UE9TC+RqSHA+heByA92IM0C+AF6iC9w9R5A8v4fVA+is1bA8ObdGA9unWlA7zdztC9kuL8A+W4kNA9mQZU"
            + "A+VMBZC9oD8gC8eJJNC+STibC+5V2iC+ahVVA9YOLpC99zltC+Roh7C9nWF9A+uOJdA9+8XfC9COSQC+T1zyC9OKNKC+i3DO"
            + "A/KY3eA937zkC+X0I1C9iRcbC9sRc8C/IifhC+IcyWC/jcu8A+s9HuC/Cpz+C8jKmRA+GTJRA92U2UA9tEukA9xlbvA95/mU"
            + "DAoJ+pC92epXA+M8q4C89iDvC82u4tC/EE9/A+Mg7pA+BekYA9CQTcA9nbpoA9IYqvC87dXGA+YUh3A+OT4dC/z56uC+G4Cq"
            + "C+Tz5HC73kj2A866e5C+RtfpC+dZcLC+9+OwA73BwgC9+svJA9pazAC+hwy7C8dtdiC9MBLtC949mpC+bW/bA+YO5KC9Myn5"
            + "C/CV/MC+9KZEA9LM7mC9pxYXA99QTFA9oQhmC6LzYXA8+lILC9sc4cC+NptaA9o8UPA85iHTC+WHfuC+7d0JA+Xh4uA9nA19"
            + "A+JCKKC+1S62C90qhEC9lkeFA+haYeA9Sjf9A+EpKBC+OExqA+IE6lA9t+fOC802MfC+HIU8DAxPa+C984rCA+aLzgC9Uo1R"
            + "C9oQ+wC/BgMdA+KFh5A9sfiuA9yYo9C+20iRA9ojMxA+EU9IA9hYtoA91d7fC/Rm6YC9+Mn7A9rF4FA8XlbNC+VeAvC9gO0j"
            + "A9OrF3A9Un4cC8cevWA9JE8aA9hy8mA8rIfpC8eLX0A9Zj2cA+D6zmC8oOLPA8vQhpC9Caw2C+2EZBC+SbONC9JjHDC+kR16"
            + "A+Hko4A9s3cmA9AKFFA+J9LIDA4HF2C97+o+A+SgHvC9JB8vA8a9ltC/dskqC9fYJcA9JqmdA9o60ZC+E5dXA97muDA9Bd9z"
            + "A91EG8A+L+2mC/hposC+OvicA975j6A9WWewC+WY2LC8iQcQC9Z9SQA9kf8TA9NnZ/C44HZeC9+LpBC+Gx2TA8tIA6A9nBAB"
            + "A9+vECA9fvZKA9qy0lA9PoNDC+xwlTC+sXwcC9k5s4C90AmzC/IWy1C9Uf7rC/BUItA9WSMCC8yO4bC+Q79yC8bKkLC9d5j0"
            + "A+P766C8rtNSC+Xwz+A9UQA4C+SrJbA9eavzC9fNpGA9l+UGA98NONC/vT2GC9upeDA+t+xXA+FIH1A+ApLFA9fRgJA+EIqt"
            + "DA9HARC9pYEhA+jLg5A74yVpC85+0ZC/WpdKC+ISWaA9r+dOA9mNaDA94RmLA+Rmz0A9jvg7A+Mla9A+hOSGC/v9pRC+IHeK"
            + "C/hUO/C9BvtnC9SUwqC8+SOjA9UuZXC+eiWWA+AduHC9HoxiC+HNliC9aCPWA+lpW4C8i7OSA90a0hA+5SydC9ezG1C9gm5H"
            + "C9DmBmC+hOafC9hroUA+6KDTC+LYUWC66UqHA+CHpKC9sO7rC+CaYvC+80E6C7kx2qC+W8HwA95CyAA+RwrEC9kZRVA9Zlb8"
            + "A9JFQjA+fo+wA/CdLhA+5iDNC+yD+SA8TOS1A9N9U/C75+p1C+KNn5C+opF1C/D/OMC+il/IA+AhB7C996ToC9tIyMC+pddF"
            + "A/X1HwA9076pA+iTN/C+Hs9kC8X/EBA+nnkDA99Y6oA+iujAC+QPxyA+PZBcC+EjQiC94mAtC+JwWFA+M7bMA9pDvqA+dkVf"
            + "A9syaRA+nikPC7EdH6A+RgG9A930TUC9niNRC/BUr1A81IbVC/PJYlC+l9k0A9Mh5gC+BAbAA9KxPFA+AMPSA+IasIA95yGS"
            + "C9c/2uA9eqYkC9LItEC+imm+A99ehPC+u8aoC9DoZ2C99NDdA9st2wA+l8XvC9jlaKC9HTqJA9uxrLC+0J/oA+b+YMA+qkIp"
            + "C/O20BC+n+SbA+DRCyA+aQ4kA+EQyzA+BCpnA90mY0A+CxPgDA63uJC9z/CJA+gxX5C8aRu4C9ZggiC/jKu0C92jCYA9esey"
            + "A9y7/XC9eDXsA+CYjTA9VeruA+Ex7BA+YTkAC/vNmUC90IszC+3CY1A8K6vrC8WUKRA906/7A82wgvC+rX+3A8E8OXA9pefJ"
            + "C9/rSmC8Wy25A/GH2RC61JmgC+WYrsC+ht78A9ux3jC92ajOA+1DtPC/NbwSC7AlMBA+nE61C+QQ+VC9Gp3bC+BxMnC97cVQ"
            + "C+coLHC+uiSJA8kwJxC+Tv6TC7gZmeA+NZ6lA9gSm+C+FKg7C9ZNGLC+5cfOC/x0eEA+8mE5A/AVv/C9hmVnC+DH1fA9hIUb"
            + "A9+ganA9A4buC+Kxs3A9JokcC9oocUC9nyVtA9A6MIC8MlBKC9hZiDC90sasA8klyOA9oCCqA9eymHC9KxcFA+I2VFA+Gvjf"
            + "C+5FmWC+xRJFA9wuZXC+Mz9CA+gvEkA+NUvPC+Gj9GA+TVWkA7/x1sA+bb1zC8+ZIoA9qk0rC+AGeMC8pojLC+dHhCA9D0r3"
            + "A+bUMHC+GA3DC+USyuC+SxGEC+uttiC+aekIA9e411C9J9ZzC+aUB5C9t/4+A+iwqzC+Vjd6A8uX4GC9urggA9rBuKC+mUxm"
            + "C/B3h5A9fuQMA+T1WbA8fug9C9qjesA9/nhbC+aKm9C/g0iAA+Rp22C/CvmHC8CyjpA949HMA9y3mjA99wglA9xINgA+C4tp"
            + "DAp3l9C93AXVA+HwNLC8XuSSC8yicAC/IZV5C9SkgjA9Nv09A9cP1JA+JJ/YC9ESf2C9ibziA+eZfEA+PnfVC/mdrPC+G4RV"
            + "C9m6rsC7/f4IC8SEG4A9tos0C+wXj3C+8JokC+CfK7C9xoZ9A+WaScA9snsfA+KJbdC9RvpAC+K8e0C/A9pzA+DYbsA9Uy2k"
            + "C+3IOIC+yhicA+E2bYC729AgA+BLvkA+D6qLA9Cga0A+HTrnC+s6y0C+WpvQA9DTvYA9JkCMA9mZffC+vRu1C94AwPA9D6A2"
            + "A+Q3l0C+kXZXC+PdM1A8LWlaA+hwKjA+JEeAA9zG7mC+AtiAA+D+5wA93yqdC8SPxeA90rAhDAtxhVC+JSiuA+CzKLC8am8K"
            + "A92joIC/JlolC9/AEdC8MludA90sasC+mKVqA9BsODA9zlzVA99iDvA9zmbLC/JeWwC945PuA9/TG6A8raGOC+ZpsXA9T0Qm"
            + "A8iVCsA9eVkCC8yTuzA9TjlkC9xzgQC9KgwoC7vYTbC9yDXsA9/7pFA8XNYvC9Ge8qC7vnVcC+i2nbC+gDODA8yMtLC+PVQR";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_cnn_layer_2_bias} weights, 20 values.
     */
    private static final String CNN_2_BIAS =
            "A+Otp3A8ttjaA+UQJ5A9avsGA9457xA9pAeXC9FS62A+RPznA7o0U/A+mubrA8Wgo4A99S88A+czj4C+iI2PA+BIiDA9joLz"
            + "A+YfnwA+bTodA+z8jSC9xy6F";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_cnn_layer_3_kernel} weights, 320 values.
     */
    private static final String CNN_3_KERNEL =
            "C9IVTkC+ijN7C9kAWPC8wbpKA+LpHJA+e3WnA+NC+lA91WOqC9SOjCC+IWM1C84WpIC8itqEC+gF8HA+XF4tC78GdxA8NlnE"
            + "A+UophA/bZRsA+GwX7A9w67UC8d6ToC+Aa60A9xB4VC+D4rSC8yjuNA9xtrLA97ifhA+NgMMA9aXsJC9VHQrC9NuEIC8TVIu"
            + "A8PjSEA90cdYA9uARGA9skCPC/V+DOA+xEFXC90nRcC8ye2KA9AAXEA+R2SuA95IBjA9vH8MC9Y5TFA+pyXlA+YJ8fC+CO+7"
            + "C98nstC9enFLC+LgJkA9/We6C+UYR/A9qSX/A9BF/8A+TnTzC7ftkOC9W3nZA82i4UC9wu0NC9EsCyC9hQcTA8Q2aHC98ejm"
            + "A+VuMuC9FfqpC8dE1EC+DWkKC+DwQUC+VF9bA8UxrFC+TqjaA9uDN1A+r8YRC8Ha89C+WbUgC9uWb0C9dJPxC9YllcC9hdE9"
            + "A9B13CC+jboKC9y5c1C9P83hA+FTXKA+iNCJA+Pch1A+OB1+C9M1tBC+TTXrC9IxGYC9ltXdC+KldkA+UDISA9kYpfA9fTw2"
            + "A+j/BnA/O7sOA+XKjjA6vusNC+DlS1C9qr6SA93Mr3C9174SC9iadxA90JfIA+CrS3A+CBe5A8/5q1C9g85xC84Oh4C9jMsE"
            + "C8pb+tA94CQtA9nqKrA+DthNC/TPNFA+ps0IC9X+0gC9vGwSC9nl8+A+QjlgA9d6vTA9wGTtA/LfNiA96xZ/C+JQVLA+A4Mn"
            + "C9DXg+C+Cd9VC+HbDeA9qI/JC9DxJXA89Rc5C9eJMkA+DOPeA9OD15C9Y33OC7tVJyC99zuGC+UZ0kC9l4OCA9b0DmC91rKv"
            + "C9404zA8CoSTA9gSrzC+F2jgA82/34C+XZzxA7rlI4C+U5mzA+gZP3A+05DrC9cghbC9wIIuC97z5HC9OK90C9Yb6XC75F7P"
            + "C9524eC+lBD5C8EPn4C9FczxA9JJzoA+dFlTA+GLsKA+M/gSC+ITQFC+DkIYC842iFC86ji5A90C3gA+Vo+OA9sJauA80hDZ"
            + "A/H4pMC9wMHHA+UFUyC+CA7QA8GzSPC+FxIbA91NL2C+Ja6jA+AJRgA9349HA97G70A+CuHOC8lNJVC9lncgC8fYIMC9r9Ga"
            + "C54DAsA96K51A9nvzmA95StOC+XBM0A+yVX4C+B2V3A8qkEcC9mWY4A+XrJKA93PHDA94C+2A+FiCrA+olXSA9mFQEC9TwkC"
            + "C9TlhTC9iwy+C+YUh3A960UoA+ND0EC715y1C9s01CA9hk/bA7HGBzC9gdU9A88e25C+Ai1RA9zGrCC97HIZA8Li06C+N/Fz"
            + "C9Ms/mA8RRdhA9haz2C+BqsVC9p1mrC+UkquC9GZO6C+SW2PA/Ljm0C8IoAdC8rdpkC+PrprC+3V5sC87qKrC9QvZvC9kjFA"
            + "C8PrxpC+kK0EC9ghqMC9bCc7C+j20RA+krPMA+TMwDA+RXq8A+pczqC+PXI2C9RD1HC9tKwBC9S4c4A+lhXKA+AI1MA9DANE"
            + "A/flLfA9FzcEA9Od/2A9BxbFA+IXUIC+HN8mA9+qPwC+Jd7fC+op71A9103gA+H2i+A+HCYUA9jF+XC9dBVhC82l/IC9w3Kx"
            + "C9E0ZJA92E26A9qPk4A9zS/kC+Yo9cA+w9gXC8yuWiC97Sw4C9gwdEA+Ng2IA9Z+sBA9S1IpA/qFy8A9Rcx4C98y+IA+QzUJ"
            + "A+MQQMC93hjwC+TP2PA9+wcHA9t80sA8fn1JC9G7ykA+ACo1C+ER/WC8vbPAA83dftC90NDuA9/ReDC9sWi2A7SQn1C+Bu0k"
            + "C+7BepC8A7eFA9t2XIC+HZlGA+VJsgC+fxPPC8opm+C+I9pzA/T7VKC94TwlC9tErAC9m+2cC9ms3vC9HaAJC9YzyWC9mZwe";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_cnn_layer_3_bias} weights, 4 values.
     */
    private static final String CNN_3_BIAS =
            "A8Lv7xA+C/rTA+khTwA+yp84";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_cnn_layer_4_kernel} weights, 320 values.
     */
    private static final String CNN_4_KERNEL =
            "C8g+giC9tgIAA9PcqcC+Sso2A9HCXQA8XfmwC+SVxCC+3ffGA/ByksC+EypJA/DwIuA+63aSA+pU5uC++6e5C+Q385C9nMuL"
            + "A+V2B8A/MHvMC9zaZhC/8HyFA+ZCWvA+ledkC/dHBDC9oYnmC+Bo4/C+L6gvA9TIeYC9XasnC/B9DAC/KonKA+SC8OA+X8sM"
            + "C+oTnrA9mXxzC+Q++uA+su2aA8BOo1C/QpmmA9kOHqC9k09AC+h2vjC+3TBJC+3YkFA+H3nIC8pXQuA+RWgfC/KIikC+i9lm"
            + "C+knjyA+eT5gA+/tKqA8RkBWC/KpMpA85sj3C/DEM9C98L4OA+pODbA9s97aC9BnxRA+n6ZZA9wsUnC/jqEOC9KwCUA9/NxE"
            + "C9uoHpC8fLs9A+n1W9C8f9lqC/GbY9A84JauC+MxrSC/pYVJC+NvGZA/GLflC+1+xXC+lAnEA/B4ACA96y2QC8f2e/C+ndCW"
            + "C8uxLGA+PG70A+DYywA+Fdv9C98WRBA+fk+5C+kNExC+OOn3A+sluWC+1N21A9hpaIA+3Qt0C+bJaJC+PzoVA+DLLZC+kI1t"
            + "C+I4KhA/QRdQA9Gu1XC/17T2C+lJHRA+lEThC+P1BdC+mcwQA+kiZfC7fUSPA9Zs2vC+wMn7A+I7yQC/MoMKA86PiyA+3+a0"
            + "A+yylfC+voGIC+bh2oA+ygzQA7TpnXC+xHmSA8/wJ0C/H9WZA+/wS8C+r3GGC+AQBgA+P0e2A+QDp1A+GOtGC/U8f3C9l8Bh"
            + "C/KLbIA+2nm8C+5c2zA+GksSA/VKuCC+br5ZC/JzBAC9tAGCC+0MrFC/AwcHC9l1oXA/AZ00C+P/MoA86XIfC/Un4xA+uZyd"
            + "C+iq7RA9BHG0A/D3c6C7fnEKC+5IXCA+JxACC+0btrC+vsInA+nS4wA/Dz1cC+eXeFC9vlW0C/BTJAC+41baA+Por4C9wPbW"
            + "A/H8GtC+2/fPC/XpljA+z33YC99qveA/CaW5C+yD5CA+EY2sA9uyXbC7dmTMA+w/vfA/GuSPC+ixA0C9gR0BC+Gj1xC8TCQw"
            + "A8WbUTA+DcxkC92aDxC+1UobC/BLbYA+PZHvC+7eLvC8KKHgA9H5EQA+0BjGC9Ka67C+u3yaC/Awm3C/JncdA+jncMA83WAt"
            + "A+howmC88X6TA+AYvWA+19OzA89nIXA9l//5A+kFxGC9+f9QC+5nloC+n9l3C+ziXqC9sctiA+QwDeC+GTFEC+MzrNA8E3Wz"
            + "C+iH9FA+XmxMC+evX9C/jIqcA+o4mUC+rQY2A+t48mA9ngNjA+5OiWC+nGtqC+kiwCA+xI4EA+FW92C+h6a9C98qjKA9jOmj"
            + "C+RuR+C/5UpNA+uhCdC+MoYvC+ayPdA+B1xLA+VId3C/YFLWA+iAEuA+Dq0MA+cd/8C8bd6VA/EBfsA/BtALC9CJrZC+2AFQ"
            + "A+jAY6C94xYaA+2eKbA/A2fTC+2DqXA9ZqsVC9J89tA+R3FDC+1vOyC924kNC/mJtSA++JYUA+B/gBC8GLbxC+ZuVHC+22JB"
            + "C9INfnA/QC2uA+rNvgA+IkeIA+07QtA+puuzC/QdHEC+PGCJA8EOTKA+AYsNC9mezbC+nhitC91Xo1C/NDFIC++83/C+USct"
            + "C+ommcA9pwKWA+l7emA+YOc2C9I0MxA/DmbcA+NF8YC9mbvjC92fI0A7gzK6C+MGaAA5O3JUA+Fgx8A+gqnWC+2r6MC+OpId"
            + "C+g1twA+hUAlC8auM4A9secDC/EHrAC9s5lHA/Oe4TA8gxKkC+w1wYC/I2H+C/TcYJA+hNahC+vjrBC+1tdBA+dFgEA+vmEH"
            + "C+aDg7C/ZQSjA+v8jzC+tbD/A8zDyEA+Ecp9A+3hy9C8srxLC+qjrSA925e7A+yBn0C83hd4C/Heu3C/GuA7A/BGu+C+lk8z";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_cnn_layer_4_bias} weights, 20 values.
     */
    private static final String CNN_4_BIAS =
            "C+lKuCC9ZXzHC98fV7C+ionbA+zYK6C+ngrpA+Gli0C9BOQ2C+CzMSA9s1GpA8qSi5C+WclPC82lhxC+pz91A8l6NVC/gdRB"
            + "C7Tnj0C84Gj2C818KUC+L9Km";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_0_dnn_layer_0_kernel} weights, 592 values.
     */
    private static final String DNN_0_0_KERNEL =
            "A/GrcTC+cavSA+rzErA/CAmiC/Ir0bC/F3FUA/VXijA+/FIeC9zW5IA+Ptu2A+T6VMA+hvOyA+qPXkC+HwmWA/YSxrC7n+UN"
            + "A++0mdA+eBFvC+lZyuC/MmjTA/PyVfA+BHnVC8SJxRA+SPGRC+gNJfC+xW+XA+IX3yA/MzDGA9MSqwC95CrtC9EMhVC9FRzj"
            + "C96JAMC/BVXnA+bMxHA+bCxeC+pc/uC/iwzdC9QCvVC+9rE+A+u9IxA+Qi2VC9goLiC++EcLC94QWfA+laAXA+vMaCC+PE88"
            + "C9309RC/nPHUC/YV7yC/0Z+AA+rMMaC/cOT8A+04FzC+wfDDC+9gdfC/GEYPC8jW/1C+/7YTC+mMc7C/VxYaC9m17ZC/Raeg"
            + "C+bk/9C/UONHA+JKTTC9nR2KA70oPGC/Cl2eA+1ea8C+xMfSC+/qgzA/LyPNA8AqopA+hnbqA+UJ8fA/M7laA9fPIvA9s92L"
            + "C+oUvgC8ixScA+ztdBA/UNz9A+CqMnA9r9cOA+LTttC/I2LYC/04sFC9QvyfC/e5E/A+6gUUC9bI8lA+vBKMA+NL/TC+toEk"
            + "A9yITHC+jhFWA+g7kGC+qroXA8xdWXA9iXJiA+YoZyA92TibA/CYK6A80s3NA+h/dZC+sltTA+kjYaC9ehC4C/y/KRC/0bKB"
            + "C+KNsFC+u2roC++oYOA/Oq+KA8oeVjA+TRkVC+bQ/pC+gu0UC9vu18C/OpIuA+78SgC/jEcbC9o2l3C/4fWuC+O0swDAA8Yq"
            + "C+vmjkA+VBhQA+gpMpA+PvoeC7vjffA60q0MC+NnuiC+tJyRC+KLHdC/Dr3CC/D0f6C776/mA+GOjqA+0cjJA+YiFCC+qkbh"
            + "C+MkdFC9VJXvC+iGXYA+los7DAHH/MA+rgDzC/EuEVDAv0piC9rFKkA+T7qIC/E3c6C9435vA9z1XeDAurJXC//iCbA+pUWE"
            + "C/GgmrA+WYKIA+fRu1A/GouxC/CydnC/MYU4A+gF8HDAijx1A9v61fA+35XVC+Dw8XC+IjFAA+eiwjA/HoS+C+nklvC+CpbV"
            + "A+854GC+lORDC9309RC9v+s9A9HhFIC+lQSBC9dMxAC+5wH8C/wlS1A+ESyuA/Unv2C/OssQC94RdyA+Nn6EC/QBI4A96PwN"
            + "C9xJrdA9iFqSC91WuHC/c42CA/tFBIC/kN0vC/i6bfC/wIhRC+OqXGC/5pCsC+G8lHA+tr+pA+UoZyA+O4smC+/1HOC+O21E"
            + "C+NKmLA+ypn6C/FwQ2C+ovUSC+16KMA/KKTjC+PzvrA9hmaPA+ffQ8A+lEjPC+6o2GC+Be2NC/CwFDA+z8WLC+tRBNC+n7Tl"
            + "A+X5RCA+nlFuC+BXmvC80vqvA9KUK6C+QsUZC+eDL9A/UeQNC+gqPwC+OZF5C+lPE9C+mp8XC9QIHQA/M/OdA+ViQUA8v+RR"
            + "A/GUBGA8+gQ9A9LXKvC/HmmcA8nFu2A+11bJC+ZiVjC+0fWdA+AuwpA+yl+mC+yZEVC+kM4MA87SlkA9JoIwC/AvOyC+/sbe"
            + "A8zwYtA9pmj4A/TVMmA/pTo/A/Q9U0A/D66bC+cKE4A+XtD2C/MOEeC+iDpUA+t0XhA+yIDYA+5Y5lA+6svJC/SkIYA/OE88"
            + "C+76SDA/n3YMA+YgB+A+kW43A+EL0BA+fJuEA+F1+zC+zp8GC71GaKA+y8sdC9zcASC/RWTYC+QMrmC+YIHDC9B1JUA+vDQa"
            + "C+Ya4uA+nhKlA+YrZrA+rjrBA9Gf9QA/GyhBA/Kmb9C/QWpZC/dbtaC+eYVqA+b1FpC+6MUAC94zrNA/RqwAA/Oyu7C+D8rJ"
            + "A+RTuOA+tc3VA/eZtOA+Q4S6A/RrVfA/e5eJA/DjnFA+2P7vC+0cbzA+gEV4C+iuuBA/XCUYC8rMUlA8kO35C/EBnjC9oUQQ"
            + "C/XGyYC+rjFRC/Em5EA+Ul3RA+saXKA+wQp4C/itwKA+mBFNC/girDA+QgKXC+92t/A+FpZfA8gqo0A9uh4xC+8HIIA+MjNZ"
            + "C++z2OC+xFo+C9T+xyA8E9CCC+T1rZA+UffXA9Zk1XC+Es/ZA+YMrmC+onRcC9a9RkC9dAzIC6I+nDC+jsdDC9U7YrC+ZqpM"
            + "A+26vrC9ww5KA9o6mwC9+6oVC+hk08C+9kZrC9PuxvC+zj6vA+GjWTC9vFNTC+tqnnC+TADaA+YKeDC+W5lOC+SRLcC9xBzF"
            + "C97VhDC+EzpHA/DMKDC9539rA/Q9ApC8PBulC9jBghC/ev7FC8i8iLA+faoNC+P6wdC+w1yeA+GmBOC/BrjHA/gRbbA/RtF8"
            + "A/2UB5C+myprA+anZTC+1AlfA9r8PCC+kiljA+3OgyA+oXFcA/FnxbA+q+W4C8teT0A+Qf8uA+jErHA/G/ipA+o/VzC+W0a7"
            + "C889mBA9M9VPC/BzjFC8ULDyA+3SAIA/AkO7C+qIt2C+KsfaC/cYFaC+t7WNA+VaX8C/BJq7C+A1tPA/MYbbC+efAcC9iRh4"
            + "C+0hEjA9Nev7C+h0J4A+E1ZUA+jb48A+/3RHC/LrwOC+XmknC/UyylC+zxViC97EpBA+1ftyA/J/LlC7usoAC+u0KJC+vDlY"
            + "A/Bt3wC8hxjDC+gxIrA+fTodA9rV7eA939m6C+myJLA+JGrkA8uuK/A9EDlpC+cqNIA+/NN8A/r8NhC+OrlvA+cBBBC+g4kv"
            + "A+ADxLA/KsfrC+4D8MC9k9S8A+m6K+C9KU/wC+FWU9C/O9ZzA+30K7A+vLGJC+GKZVC+lJ+2A9weSZC9bUcrA+h3GoA+LiEQ"
            + "A+hlq8A9S3w1A+viQUA/QruZA+9t1IC8oTRjA+hZMdA/cug6A+LlBAC+CaiLC/cTZQC/nbqyC+v3gUC9HF/NC+N8b8A8G1Hr"
            + "A8Me4kA+x3bFC+wsJZC9sVOHC+YZ2qC+MAbRC+xMY/C9oU22A+yQ0XA9/LTyC9t3b6A7nZOkC+JtP6A+W3rlC6wR7EA+UMrm"
            + "C7P5cvC+GCkHA9Ll2RC+nSF5C+rzgdC+5tm+C+POGCC8F1QwC/BaFVC+qz7eA+IEDAC9r3qsC+jimVC9k66rC++kGBC/AnFp"
            + "C8T3yqA9wKBUC+mO3lA9MhyYC8kpd9C9V1ATC+mzomA/NqhlC+DLX+C+LmA9C+El9jC+ocsEA+ap++C+jh+fA8TNe1A/AiD/"
            + "C/mmLMC/akXlC/0SNPC+6MHbC/ZNn5C+5RICC/QF1CA/sb2lA/HilSA/HIiDC/YlkpC/RyR0C/G8/EC+KLaVC/IcCpC+NSOz"
            + "C+6zFdC+P5wgA+MXEZC+9FJhC+IH9nA+Ow8oA/GU5/C++tmMC+10rcC+JePaA+sFeOA+rThHC+P/ZNC+xwZwC9liY6C+DirD"
            + "A+oJ0oA9gDKzC+lT3qA96ab4C+trQgA+Sj8DA9A2D4A/lW8AC+uLwXA/0SwoA+SydGA/kdPtC+os0YA/B/DcA/BsniA+PkYG"
            + "A9vSebA+RXyRC+cADJA+OUB5C+alQ/A+y1/lA/FN1AA+kZm7C+BZB+A+TZQIA+eHxjC9RPcVA+BuhsA+ubVjA8cI0xA+84wz";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_0_dnn_layer_0_bias} weights, 16 values.
     */
    private static final String DNN_0_0_BIAS =
            "A+9GgBA+xTvRA+xzsAA/Ch0RC830eOA/FOQRC/TQvIA+EaR7A98L2IC9lHfyA90RcvC/NpOfC8hRDhC+CAvtA/Cxz8C/IGjL";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_0_dnn_layer_1_kernel} weights, 384 values.
     */
    private static final String DNN_0_1_KERNEL =
            "A+SCBgC/H5QhA/o7iRA+ika/C/LXf7A82gXQA+i8V6C+2dQPA/ZFLWC+mYQbC/HKCQC9BADGA+Pbj+A+dF3IC+GpOvA9qv9t"
            + "C+8bfhA/EtFKA/lRNAC+HqptA/JABlC/zB4mDBHRDLC+amIkC+QnhLA+aIBSC/CnEEA9rDX2DAQr9dC8rALzDAw4NmDAvbJC"
            + "A+k7HyC+LYseA9WD9cC/myN5C+j9QXA+lOv/C/GhScA9dUMOC/F5Y6C/CRxMC9McYWC/JzmfA7XkBoC+CHi4A+jxXGA+9779"
            + "A+7WVeC+ESRKC/yCIUA/D176C/uSNPA/LGxVC93gpCC/NRJ3A+/h73C9A+EbA+0qzaC+w62fC/dysTC9T+98A/ClijC/ejAj"
            + "A+vaK2C+wFmGA9rPWkDAAANHC+sUzLA/HwpOC++s5GA+Q9eQC/E531C+tyhjA8vci4A+OSHNC+1fJFA9cIoMA+K9oOA9erLv"
            + "C/Nky2A+H1lHA/ZmjCC+9dLQA+FZ+yA+7xwRA+0zZ6A+oB1cA/DUR4C+N+lTA/NM2FA73SwrC+/Y2bA+EDV6C9fZ9NC9ju6w"
            + "A/AxBFC9AlmRC+kmeEC+knkUA9tx7LA+b/m1C+i26TC+s2ORC+yQOGA+kxtYA+nvAoA+lkjHA+tyH3C+IagmA+GT5gC+hMs6"
            + "A+RZJTC/WZ/kA+6btJA+xpUxC++3zdC9XgAWC+j/bTA+RqWUC9w+y/A9izqeA9C7jRA+ETrWA+iwcHC+khIwC/L8jzA+nOJM"
            + "A/GcYJA+/VaPC/jz2wA/BlyBA+GswMA+8rkKC+3mr9C+aqMnA9DYKFA/LtG/C+8WzoC9OzbYDAF4BeA+5I91A+UNrkC/lt78"
            + "A/ULuhC/kcnFA+y2AHC/GAMEDA3pBYA/JpPzA/RYtQA+G+yrC/UxOtA/HiuuC+lziCC9z0WeA/GdpqC/DPeYC/OO1OA+lYHP"
            + "C+515CA/C6H1A+tWPtA8Xz6/A+RJxvA+hDyfC9NbfCA9vheuA/KvNNA+dU6xA9VpWJA/N0KaA+lcrRA+uZbZA+vzU7A+gFOg"
            + "C+2JhOC++SA6C/HpDNA+sBIXA/eZezA/Ev/zA+jEhJC+Vv61C+jHVgA9YtKTA+hyBDA/O9vkA9HpGGC9oZXnA+lYLcA95PuY"
            + "C+Sj9GA/F/qgC8yPrmA9su0NA/B+9rA/HudPA+nxOtC/xjjaA/kIf9A9ywcVC/F5Z9A+2Dr7C8gWGUA+ww8oA/IFvyA+iT+v"
            + "C/HX/HC/t0KaA9vhBxA9wlxLC+8RthC9satmC9HCRZA+NqyoC/kZVXA+Aw0wA+hDSgC/TalDA+NcQiA/Cu+RA/KSDAA+FJK8"
            + "A+O3MIC+8l7dA+8EZjA93dbgA9y8FcC+zjEvA+IQ/YA/Bef7C+piPyA+r+w1A/KjLkC9GdAFC+jvhIC9k+8NA+ymqHC+lCE6"
            + "A81Gq7C/V97oC+I9yMA/Pc5KC/sZG8C+mANHC+djaxA+fa0yC/hTuOC+j5erC+cr5JC/QlyzA9Ou3CC/Jb97C/gwlCC+TTRZ"
            + "C/C/2kC91XRxC+HGR3C/ER0VC9ZTzrC+2dpZC/HFV1C/fLJTC/Ezw+C9tdusA+7WMCC/hT99C+/P1MC+M4FSC+7TUiC/dIZ7"
            + "A+Ns3yC/jbA2A+OlvZC+QooeA+aUkfA/MDBNC/jV5KA/DJJYC+L/kvC+e4I9A99wm3A/pdnTA/G2v0A8BLLNA91TJhC/nVXF"
            + "C+Wg8KA+3EhJC/yWWAA+9s/IA+jUNsA/cJXAC/gqzaC+tgiNA+y2/BC9cuyhC+dVp9A+83KCC/f8UVC+D/OuA/hiCbC/F4wR"
            + "C+nS2qC/YQbuC/fqWkA9DI65A+X8DTA+sa86A9idEfC+NzxPA+UgMdC+uFX8A+JRxcC+pkjpC+uBRyC9hpIuC/XUrDC+48VY"
            + "A/GNs4C/ct1qA+dcLSC/SH0cC/EcWkA/eWWAC/hc9XA+sRvFC/LnqWC+e/O/A+gyk9A+/MHsA/LmtAA+H1kEA/RfeUC/JaOQ"
            + "C+W+XaC+8sqbC/KT1TC/gvboA9aoi6C+38k2C99YCAC+7gRcC/FKbKC+X0L/A+o5eaC+wgoPC9jxe+A/DgBcC/CbItA+AXab"
            + "C+FJiBC/GCTVC+X0DmC/NB0ZA9k5F4A+hajfC/UHs1A+f+tKA+KUNbC9odlvC+pTH9A+Ca9cA9x7B+A/CeFMC+gBCEA+Eblz"
            + "A/BsfJA9Wii7C+xEQYC+w0rLC90BluA/BRAKC+joW6C+bnV5C+lTESA+ZOUMA+8bUgA+u4b0A/Pb5uA+1yMlA9ionvC+a4c4";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_0_dnn_layer_1_bias} weights, 24 values.
     */
    private static final String DNN_0_1_BIAS =
            "C++NjtC/FBw/A+g+lCA+63n7A+Nm6HC/lVQiA/VDIzA/HstkA+05ZsC/gKZ2C/Ev1lC/mmW/C+wXPaC9E5enA/LnklA+DHtG"
            + "C+6t0WA+xjYJA8vvchA+ARHxA+uPzWA/J8LbA+04ZNA/RRs/";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_0_logits_kernel} weights, 24 values.
     */
    private static final String DNN_0_LOGITS_KERNEL =
            "A/LCUHDAJd/jA/ARaHC/7rIYA/lFcqC/odonA/QtY0A/JSZjC/5JmvC/X2l3C+RKnOC/6WtmC/D5lwA/VsYEA/AJRgC/pRD1"
            + "C+Unv2A/IrtWC/kCb+C/12UCA/VWd3A/yULEA/lEYgA+tPdm";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_0_logits_bias} weights, 1 values.
     */
    private static final String DNN_0_LOGITS_BIAS =
            "A/koM8";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_1_dnn_layer_0_kernel} weights, 400 values.
     */
    private static final String DNN_1_0_KERNEL =
            "A+uq5tA+c5OrC+RXLeA9/Tb4A/Ba86C+UrlOC/ANd7C+2l6aC9qiItA8hrNDA+PLNfC+2iPiA9kwu5C+hiCrA+NKVZC9z0+U"
            + "A9I/TtC+qgKXA+sFM7A+Qh1UA+4d/8A+qTJRC/OEiMC/kjABA/Ds4DA91opQA9ZjITC+7o12C+sG5dC+RCa7C9hoayC/I+Go"
            + "A+1roGA+0ZDRC/19lVC/jdxyA92AiFC+FRrKC/cdpZA91ke7C+FlxwA/CfBOC+HNUwA+pGS7C/43lTC9ZRZwA+pfUnC+/nnM"
            + "C/lDcNC/PXoUC9tfWFA8JM6BC+PFrEC+cAfdC92M0hA+BhnbC90MgEC+PvVmC+5OObC/ZmgbA+A4bTA+zasZA+A5fdA9/X9S"
            + "A9fqPfC+pL0SC9jaLrA+gkmhC96/RFC/WVCHA/USc9A9fEDeA+HQsxA/A4wAA/SgqmA+tlunA+vkQPA+igFHA+iU35A+YcXF"
            + "C931xsC7y2xQC9CQ+pA+ce/6C8wMC6C9o+ROA88P7bA9QQxvC+ZWyUA9tmxGC+tmwJA9yqgVA+LAWSA+/xy5A/HNIcC+vuxb"
            + "C/QFHsA/RugpA/jTWoC9nJs0A82ibzA+nnr6A+7DArA8nohMC9wFcWC+jWRSC90lu4C9qaQ+A+zNC7A9+vdNA9sZpqC+LtXx"
            + "A9rjYkC+BeZ5A9qJl8C96/dqC8yEl7A9MeboA+XaXbA+4zD5C+MdjoC+JX7tA+oeYlC/MI1MC/wpGnC+14+sA+3JYlC78I6e"
            + "A+kanaC8qkylA9ak0QC/YQJYA+xsqKA8nPMgC9/MEBC9POfoC9Ng0dC7ex1tA9wauPC9dJ7zC+v/VBA8lTIsA/BejEA7qj7D"
            + "A/Th0AC+Yr9VC/pygPC+1XYlC/N9owA/H3zdA/cRmsA9Q5lHA8+NmbA+iUGFA+1F3IA+yeT3A+mXabC/FU0fA+nmbtA+1Uob"
            + "A89beoC+0Xt0C+JW8zC+WzhQC8IMIXA+YzzmA922mYA9jdPjA7oOB1A93GQ0C+vJ9zC/DEofA+VUXIA/DOvuA/JMgVC/1Eyt"
            + "A9xHskC9Md3jC9oleGA/Vo5QC8YytJC/Bw8GC9/N7jC+cUXYC/LWonC+AOXmC+M9GJC+UBKdA96GcnC+ac2BC/CQ9SA+Ca9c"
            + "A9Wxg6C+Wh4/C+DgHeDACZ1eA+PsY3A++KhtC/nIuXC+dsP8C++UKRC9+C2+C+BokBC9AzAbA+jsKLA+r2bHC+OqGUC+uvqU"
            + "C+Z8iOA93xWlC9zSApA+5Y7JC9nXr4A9gckgC91poLA9OwuKA9xck4A+ICgcC+IRzzA+WVpsA+osjnA9/ljmC+qLDyA+U9U0"
            + "DACjslC93fj0C+yyVOC9AfAVC+iwUyC+PSE2A/KaEjA+02sJC8siUWC86dxyC981sMA8zwRKC+DGGVA+S7+UA+qLVnC9qmL7"
            + "C/HwljA9t4mXA/TiWWC8oS13A/dmNBA+nWfcA/ZGUfC/CZ9NDAHYhmA+AJ7cA/a6VMA+YLqmA+leVLC/P4yHC9XA2YC+ZbW3"
            + "A/AWfsA+NKkEA9DI/GA+KxcFC/FNYsA/c6RRC/lr7gC/D9mZC/xDVpA/XVRDC/mSZCC/nuaGC+q1w6C/Hz0ZA/KrhjC+Sbuu"
            + "C/GbrUC/WhexA+/A6dA+nx6OC9lsGgA8/fGdC4fLD5C9d4m/C80EWTA+DpA2A+qp3HA+u6pYC+RJbuC8f727A+4npjC8nke0"
            + "A9wEFGC8gLApC99qQBA8ErvPA9Pjf5A9WV/TC/H34cC/neqJC/CeTFC/j9uhA+iOtGA/DHc2A7sSluC+YPnTA+oTnJC/Es55"
            + "C+d1oyA+4jpLA+2FC+A+gfAcC+xPUsA9F56dA+aBvrA+e3spA+XG5uC9+mm+A9SZ6VA6LKVLC+oDFqA+33s5C+arMlC+Mo7W"
            + "A/Z34cC/WxioC/YJZGC/J3JxA+oKm9A+7Go8C/PN48C+agHvA+Oo2oA+q0Z4A9gPUoC+GraNA+NxbTC+ptBwC+qdDqA9sVLL"
            + "C9jCEYA8znHoC9qBCzA9RV7/A+VoAXC8xl6/C9uBk/C+J8KHC+KDmKA/FPadA/USAZA9eOc9A+us5GC+sWoFC/Q6DoA/GHaw"
            + "A+mxIKA+U1BMA92vdNC/eOslA+NTHbA/JDlYC+BxACC+H4qPA+XYdyC+r12aA9/Wz4C9++7EA8qEuuA+G18cC+c7jlC/gH7Q"
            + "A/Jr1vA+cyM2C/Cv7nA/iHKwC+JPk8C/F/KRA+YJt0C+Nl5GA9ocpVA+205VA+c/0NC+p6O6A+1RDUA7gVHlA9VS4wC+dAII"
            + "A+z9ehA6uhntA+JOGkA9nexfA9qMCLA9yPOPA94SzxC9L4OxA+r/AkC/ORUWC+wCNCC95KBeC+iwUQA/Nku6A/L1XeC/EoRJ";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_1_dnn_layer_0_bias} weights, 16 values.
     */
    private static final String DNN_1_0_BIAS =
            "A/FVeKC+TtojC+VJvpA+0yzHC+hEFXA8+dF9C90dWAA+5nHOC+wPKdC+rWpZA+q6K+A/Y5o5A/AJ4CA/JjdYA+HSH/C8NA25";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_1_dnn_layer_1_kernel} weights, 384 values.
     */
    private static final String DNN_1_1_KERNEL =
            "A8sAgGC/bzDXC+MX67A/VhE0C/Q6TXA/BnEVA+0cqfA/UlBhC/SL6DC+6UN8A+5GcXA+0EJTA+9V81C/UGTLC+1rKvA+qGGE"
            + "C+rnEmA+CGqQA9AzbRA+gHSnC+pluFC9jCMxA9GrzKC+hm29A/LGe+C87sJVC+1f52C/gPXDC+9owFA/bA8TC6B1Q2C+zPld"
            + "A+d+BpC+3RJFA+o9haA+EwueC+sLpiA+AR6GA+GBUaC/j42kC/hGWVA/DTufC+nrhzC+pXmvC/GFpPC+ypfAA/PL2YC/Oq6f"
            + "A/Fz7uC/FwuvC/gdOZC/BWqtC/HhmoA/iVVxC/HPCmC/HnzhA+q6g/A+8o/BC/S4bkA/n4hmC/DmKqA+dtTDC9ERKSC+tVur"
            + "A/N30PC7A49sC/oDgJC+4ad+C/Q5AhC/Fq9oC+PFCLC+ysfaA+aFlDA/a+WGA/jWUsA+VAW0A/E6nSC/DkJKA+cR7JC/a+bF"
            + "A/IDT0A+l9LIC/UVgQC+naOhC/tPi2C+C3lTC+uwpwC/BEkjA/P4BWA/HjHGA9x1HBA/VvB8A/B9ncC+aHsUC+5ekYC/hgxK"
            + "C+ejpLC+yzTnC+Fas7A+uDZEA9MtS5A/m7SiC+rBE8A9Wv2yC7A0l2A/KJbMC+H+OwC/iVGDA9wsSTA+cHkcC+wtQsA9KySn"
            + "C+id4nA9HhxmC+6UL2C+MoWpA9DWoxA+L0SRC/AfTTA+V4D+A/PW/KC9M7kAA/EfUGC981MuA9YLthA+HSQYC/ZhxYA+/MbF"
            + "A+kRNiA/SRKpC/FgQ6C9fGHLC/Nt4zC+FXGPC+5jlQA/WaFFA+j1vEA+UQrdC+oIrwA/cnJUC+Cwu/A/P0AMA8bydrC/UGt7"
            + "A+3K02C+jTjOC+r5RCC9WGFWC/F5lOA+3iobC+gOWjC+n6Y3C/vNedC/t+gUA9jwopA/gbdrA+nDE4C+gQaKC+25q/C6iIPz"
            + "C+vI1+C+tYQKC+jyJ9C/RYvGA+bamGC+22OyA+2ZNPC+r7u2A+SZ/kC/QtZnC8NLQ9C+q7RwA/ElbNC+2NONC/BxlQA8MQSF"
            + "C/ATUBC/kinHC+zklvC+JJrdC+qIrOA+wDEFC/g1VHC/GfNzA/IbPyA9mA+mC+7fWuA+71TzA+dlXjC+w6PsC+6wKTA/NEYw"
            + "A8rvTgA/XFxoC/lWpqC+9UNKC+Dv7WC+419fA+pQfqC+glRhA/X9idA+9JSSA9PkD+A/mnE3A9y3B3A737YJC90CNkC+vlf7"
            + "C/ISfEC/Ld/KC+RpbmA+8rauC+OsHTA+goSdC+tMRIC+wFAWA99UqAC+g7p3C/EKv3A+JpIuC+tlZpA/Xs9TA+d6a9C+h4hV"
            + "C+xMXaA+otoSA+WLMtC+ORr8A/Q9NfA+vL8rA+PpPiA/gyVwC+/Q6ZC+93zuC/Mx0uA7iDPWC+QevIC/LVafC8+F+NA/KsY3"
            + "A/T5p8C++fozC+xlNlC/OgEVC+nAnlA/jU1AC/RqZuC+fa0yA/CEsbA/ntV8A+U5R1C/K6pYC/imv4C+r1yOA+QmkWC+TaqT"
            + "A/SMGYA/JB/rC/AmSAA9pOsGC/szUrA+pOgyC/UuCPC/kJhfC+XNgSA9zCFcA+2hg3A/RY3vA+rXumA+sNONC+mHymC+NPzn"
            + "C+mDeTC/5m/nC+NqDcA+16a9C+RCWvA+1dadA+v7a7C+CujiC/ZVX4A+YfFKA+vI0ZA+vQwcA+w/IsA9o/HVA/CsP8A+o0ZW"
            + "C+xWVeA+oGmDC/gX4TC+Y7dSC/xL8KA/qXsgC/KlqKC90ZvUA9KCR3C/Heg+A/p8hLA+z9JjA/o434A9gSNmC+MNc4A+JiI+"
            + "C/jPEsDAVAx0C+eGM5A/VNgSA/atqIA+n52RC+dFb3A+4EJ0C+0G34C8pe7dC/6ZpBC/aHBlC+mZ2qC/Hd4FC/RJZ4A+kmP6"
            + "C95Wo4A+gV7hA+s6geA5YKawA+ob+cA+hUvPA+p5v+C8rQaUC+0ydnA9K1u0A9052hC8gcsRC+bAmBC8sDitA+iJIEC+cABD"
            + "C+tIXCA9kUruC/aA+7C/CS0CA/m0rLC/hpWWC/e9KVC+QoPTA/ki+tC8mmmjC+gnfmC+7SpjC/NW5IA9NzvWC+YiGGA+8912"
            + "A90gKXA/jwOwC+vVe8C/UGj9A91C5VC+fdHlA9eiSkC+tpZfC+E40eA/1R1pA+OIfsC/hZyNC/KKkVA87jMZC/H2saA/QvMs"
            + "C/aLowC9C2gTA/KrmhC8voaDA/dm/GDAIBDgC/2UYLC9RY1YA/RZVoC/qUIcC/R1pTC+UDA8A+yrAhC+L+98A+gfFKA+3ZfV";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_1_dnn_layer_1_bias} weights, 24 values.
     */
    private static final String DNN_1_1_BIAS =
            "C+G8+SA/DWadC/Tlp5A+aV7hA+q/qPA+OR+0A/QLWJA/e3H8A+0dRSC+Nc+rC+AF+vA/PPUKA+Ty/cA/QHHWC+uJTmA+GNYK"
            + "A9rjrBC+W7DmA/B5wwC9DtvrDACGx2C/ZLFoC+DD0lC/Nmul";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_1_logits_kernel} weights, 24 values.
     */
    private static final String DNN_1_LOGITS_KERNEL =
            "C/IdRBA/PR02C/QDBNC/bntwC/5hi9DAE/AcA/aajfA/YUdaC/gMVDC/gyhjA9W+M7A/4UtJC/ZgccA/iOhTA/zWcjC/3Zf2"
            + "C/YI4VC/LmIkC/yVN6C/lIdVDAg53xDARBCMDAQZ+ADAOv/B";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_1_logits_bias} weights, 1 values.
     */
    private static final String DNN_1_LOGITS_BIAS =
            "A/qoFF";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_2_dnn_layer_0_kernel} weights, 400 values.
     */
    private static final String DNN_2_0_KERNEL =
            "A9K9kcA9vUBNC+HMINA+RD3NA9joMbC/B9lmA+3SrpA57fEbA/SwUhA8hI98C+88AaC+IGW2C+YFLWA+vtSiC9g4r3A+IJOW"
            + "C92LeiC9VMFYA+GYiQA+l1GLA+RKVZC9OHoVA/VCvYC9USqwC/nNzbC9zWZrC+No12A8+WL4C9iAHPC9M8nHA/csBiA+2dlN"
            + "C+c+xGA/r+xXA+rH3DA9m841C/FWDIA9lqJhC976pHA9TXRdA+qhexA+TFHbA+2XqrA+U6xPA+r4xlA/F7lrC9xNfWC+Qqz7"
            + "C+Hr9mA8AWmXC9UuDIC+1RIjC+lKNiA+0Kk3A++H58A+AZn+A9kVGyC9QF/XA/PENfC+u1+BA+GebvC/AGBXA8rytnA/Q+A3"
            + "A8DHH+C+1TBJA/nIDHA9qnu6A+vFH8C99gF5A/UrwfC9V3ApC+pqtYC9yVMwA+vYFrA+vNB4A+Dk5qC9IwUDA+0nYyC9JAFn"
            + "A/FqPPC+mWkaC93SrpC/4E39C+jHx0A+3F/AA+/YSQA+kPvsC+4eCDA+SAbyC+31M/C+gVZ+A+NG7SC+AZP3C+8x1QC9k8jI"
            + "C+/kI5C9eToTA9CJTfC9fo9tA9rNKEA9gqHrC+FMcqA+GFSLA+OgZkC/LQGOA+lv2HA+miwjC+Z2FGA8LYBsC+C7OFC/DlEa"
            + "C+IqpcC+XftQA9et9KC923WnA+tHR1A+D+irC8ct8bA+Qep5C9unZTC9Qw/qA+u2tuC9lCetA+x56/C8rtOIA85P+UA+R4C7"
            + "C+iwJPC+KngpA9jWyoA9b3qSA8ooqkC+4ntwA+IN7TC96F9KC+LEHdC9bdaCC+CaLGC7ErV6A+gMdcC+JFiKC9mlORA9t8PJ"
            + "A+DTodA+BPO6A9p0mTC/IFRpA9g78UC/mZntA9r19OC+SEvkA+gKMOA9A8lOA9SgF2C+YekpA/OVz7C8CDw6BAADREA+0Nx3"
            + "A9kZJXC+Kh11A8T3CBC+ayfMC9yZWeA/Ic1wC9wcUKA+lzOHA9zrZ8A+PVH4C+cddWA+Id/8A+SHk+A+07vHA/SjBmA/RIqt"
            + "A+KkP+C+slCUC+xKVZC+lVLjC99Y2bA9FuC3A9mgHHA/hWXUC+ItFsDALdGiC/SdtEC/iAXmA/CTsIA9a+D6A/iLfUA99m+T"
            + "A+S1gIC9K7PjA9gU7qA/H4X5C+EaOxA/qme2A9scdLC868lHA+DrkbC+EnhLA+nWpZA9FRbOC9x/XNA9JhF3A83KH3C9rSnP"
            + "A9ii2pA91gSfC9D9VFA+KxSpA8IbSQA90p3HA89cXqA+RTkyA9Iq8HA8zecUC8FZIjC9OXolA8hqP0C84WbtA+R4W2A9wUA8"
            + "A/zvFWA8S7oTA/BhboA9IreFC+MDW9C+F1r7C/ni+tA/LSeRA/DU97A8vug9A8PrHsC+7vQXA+HnA7C+VFkQA/G04zA+fTiL"
            + "A+n9NvA+Me90C+iTufA+Zc7hA+oRmsA+9r57C+Qy2yC8U554A+ioXLA+CnCPA9vgAIC+Z4Z/A/CfnwA905OrC80sGkC8yiiu"
            + "A+4k+HC+N4xDC/g02+A+Ux/NC+pxlQA+yILPC91BGAA97NdJA83KItC/FaLoC+czO5A+Icm0A9N0IGC+JN0vC+ARkFC+GIrO"
            + "C+1StvA8wbzOC/kCSzA8RcUGC8IHYCA9T7gEC+MGVzA9jsH4C+WW4WA+q+TvC89uAxC/Vu7pC+dEORA9gmglC9P19BC9GGtn"
            + "C+FBX0C+JafSC/JfMwA+If5lC9UtNBC9fDvWC9Lt98C9mLCAA+t8uSA+t3K0A9aOEaC/kmkFC91OZcA/OmrsC+Zlf7C+ohgW"
            + "C+UV1wA+L+KkC+7mvGA/ZjQzC+vaexC9tz/tA96xHHC+q398A9t5snA+05WjA+uAyVA/tT6BA/Dt52A+HStvA83aQgC8knhz"
            + "A9//cWC9+1neC/hcfOC+AGJOA+vZyuC+VnL8C+vH94C/KSg5C8lBbZC/IQD3C+NtO3A9DhDqA9Asn6C+8LXuA+MOjJA9o5qK"
            + "C+hPxAC/JwevC+xcbjC+8uq4C/bI7OC8fVFkC/HRw7C+DWGzC+rPmgA+XyavC9kbIcA509wtC/NCoCA9FV/JA+GcbzA8Ttir"
            + "A+LrAyA9GFJXC9sKSgC+cCtBC8bCn1A+KOfeA+6W0JA97PWyA9jtojC8RH5KA9JLLtA75JVWA9pjflA+xP43A+rxecA9eVLS"
            + "A9cbU0A8BSY6A9+D15C9ergtA+K+EiA+SHrRC/z5E/A+rC53C9njZ0C+nx9YA+QMZxC9BXMHC+ddxAA+Io7WC+h9CvBATIoe"
            + "C+5gQ6C/ql7dC+9zjnC+3xgiA+mgOCA+sWjXBAKx82C+HAk+A+YJXyC+FaA4C9b4lbC84tUXC+YNG3C+9VbzC/MIYFA+HHdX";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_2_dnn_layer_0_bias} weights, 16 values.
     */
    private static final String DNN_2_0_BIAS =
            "C+l9iuA9zv7WC+VW5pA95IatA/MxXXC+wku6A+oGGEC/PxIKA/XiVTA+6iWWC8nGumC+lNyHA+6W5ZC+JFDwC/Il3AA9qe4d";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_2_dnn_layer_1_kernel} weights, 384 values.
     */
    private static final String DNN_2_1_KERNEL =
            "A+jNs4DACRHxA/oPdmC/DgWKA/Fun/A+aclPA9mps8A/iQnAA+li5vA/JtCRC+i0WuC/queCC/G1SPC/FAmBA+vNRFC/vkuI"
            + "A+JlZpA9qEcEA+ZvKmC/S8ZlA9BngEA/ebjMC+QAOsBAHh/JC/yCqZC+7EzgA7SzPFA/k+qzC+komYA7i5k7A/FflZA/Zmbc"
            + "A+xKLbA+QftAC+IRDkC9kYZ9C9h/v9C/NfUnC/K0x/C+wkzoC/obcXC+wUrTA/UvfTA+n+bWA+AGB4C+w/YbA/IxmTA/Hntf"
            + "C/Pb48A7KI6qC8d/XbC/K3m3C+4TStA/EJokC9cNwLC/sMwlC/jmmtA/M6syA+S3RYA+iKKpA+ilP8C/MUExA+i4lyC/2sUZ"
            + "A/KR+0A9tZZ4C+B2gWA+ioMKC+9YukA/NE9/C+nTQ3C95PM0DAA66SC+bU+cA+md7OA+xDtxC+Bf9hC+v3WXA/mul5A/NGRF"
            + "C/FuRcA+iMx5C93115C+SLHdA+TvCuC9io1yC+PFcZA+KNn5A9s8xzA/EOGkC9egwAC/XRD1C/TvvSC+NFZxC/uZ7YDAEpwk"
            + "C9/wZwC+DqX4C+QF3ZC8zOx4C/EpI+A+Pci4C/MVgQC+3FdcC+ngIhC/QRcNC+77qqC+JFa0C/REXtC/IuxKC/Nc0MA/E3zu"
            + "C+dj9XC/dF3IC91mlJA+H0pWC/VxjsC/L3ocC9DhnvC/JRb9C/9l41C+oQvpA+7VjqA+parWC/fXjEC+WskqA+0HpKA/mVcV"
            + "DAmKQrC9ez1zDAGdIgC9cmXJC+9IrfC+m2uxA91SZSC/eWB0C9EBCEA/vD3uA+b1YhC9mm5AA+Ilu4A9e6RsC/sb9IA/MNOe"
            + "A/EYZEA+Us6JC8wgd3C9vKL2C/CfjkA+rFiKC+2q7RA/SrBkC/6NT+C+zxG2A/K5T7C9DNlHC+vmr9A83jfEA/fPgfC/DB6s"
            + "A/bOC5A/DE7oC+3FXrC+Wz9kA+d9JCA/sfReA+wMH9C/0U+LC+Iq0/C/EqGlC/RE0SDAJ7NTA+9LsbC/j2RaC97Bl+A/nJtS"
            + "C+hjYqC+GueSA+x+1jC+/jLkA/h+R6C+O3H8A/C+V1A/WPTYA8vALmA/Jz9CC8qd6LC/oyPNA+97x/C+u7ZGC/BAaOC/p+5O"
            + "A+EBPsC+PAsTA52QJUA+mhvBC/WXq8C/GP7vC+WhqTC/bvECC/s+QmC9vGEPA+1Xp4A+hyuIC+hw98C9ly/wA+3jwQA/lUUP"
            + "C/I75mC+FfrsA9J+joC/ZnJUA/PDQaA/TkVfC+VRUFA/3066C9cspyA/H/cnC/H41QC+5INmA+GiN9A9V7wvA+v544C/KHWC"
            + "A/g1uBC/cRcNA+wy7gC+uAmRA+I+RpA/DnPWA+dSCOA/Qi22A+4ArhC9+k8BA/PwLFA+X4ZdC+2nw5A9EPVrC/VJyAC/YKl6"
            + "A++eU7C+B4fPC+ORgaA/PHUuC+T83uC/Ye3AA+KkvcA+O4RVA9dbOsA+4qffA9Vi7hC/lrn1C/hMc7A+yOPOA/LDc/C/uTib"
            + "A+uKOUA+x9UkC+WxptA+LduYC/N7BwC/VLteC+4912A+SX6ZC/OwrUC/jCf6A+sCJXC+leolC/Lf4zA+DdbgC+uT4+A/b9Cv"
            + "C+Qlt1A+SoYOC+vCnhC+llmwA+5UCrC+0YiQC/MAR1C+3NN8C+it8vC/Fd2gC/b7jUC+ysbvC+lndwC9vqL7A8kuodA+hmGN"
            + "C+KpirA8jHcHC+qMa1A+yW+oA/KE5RC/FDzAC+n/ipC9u5KLC+3YChC+fZanA++LQYC/Gj33C+siSrA+kx/vA+k9EkA+LHBD"
            + "A/EXdkC+/LdOA/hVGDC9yJ0oA/KK+BC/D54oA9O4IHBACCf6A+OxFBC/MLZSA+O7OFA8/YqlC/OOVxA9h0J/C+qqNIA+S7QL"
            + "C/OV7AC+z6ppA/HWf9C+lfICA/YvKEA+EcT8C/vqKxC/eQTVA+THmzA/Bam5A9s7UeC+MLw4C/y1aXA959CvC+31K5A/JBGy"
            + "C/ATJyA9huRwC+uPxyA/GrTqA+oWFfC+2JY1C+pZ+yC/ECtzA987CBC+zxusC9YbADC9EySgC+AZydC+tu50C9SoxLA/hlj3"
            + "C+ogcMC9GkouC+mfseC+CkGiA90lJIC+yN/fC/lmcOA/AVaPA+rNr1C+spn6C+13UQA+T6hyC+HfdiC/EE26A9wPW8A+tS1m"
            + "A8+KJzC+B/smA+12wFA+EvlEA+6WGAC/pEkTC/raW5C/WcawC/4b35C/N7EYA+4t9yA+aJtSC/XQGwC+53DfC+jmnPA/7mDU";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_2_dnn_layer_1_bias} weights, 24 values.
     */
    private static final String DNN_2_1_BIAS =
            "C902P1C+uBvKA/HqJuC+8m4zC/Jll9A/XrMUC97Ks/A+q5QQC+kfFKC/F8uSA+etlqA/HJC0A+xXfZA+DTkRC+j/hEC8MJTz"
            + "A++Xb/C+hlRxA/MmwaA9+PcSA+oAFxA+3KEGA/MbLEA+yYoi";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_2_logits_kernel} weights, 24 values.
     */
    private static final String DNN_2_LOGITS_KERNEL =
            "DAGzKLC/npeNA/VSoPA/yNyHDAJ4UfC/tmPHC+n24eC/+J+VA/Cmv4DAIEAQA+tFQ3C/dzltA/vFglDAAwJgC/j9YPC/NY03"
            + "A/T+7UA+nutPA/SgaWC/JwERA/4pVCA+jDhMA/mPe6A/iPz4";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_2_logits_bias} weights, 1 values.
     */
    private static final String DNN_2_LOGITS_BIAS =
            "A/dBOY";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_3_dnn_layer_0_kernel} weights, 656 values.
     */
    private static final String DNN_3_0_KERNEL =
            "A9RrgWA9S0agA827uzC9b408C+Ku8sA9AbunC9t1C0C9MXTSC8EmnwC9tYHPC9DZEYC+cQtjC+WuJUC7CzJ5A8s0mkC7tCsV"
            + "C8y1rdA9p5JLC9EbKwC9ou/8C92yAyC9dEbEA9D6QYC+CRNiC7vwOLA963G5A+Ro9LC7iVNmA9MFg9C+A2XLA9e8mKC6ec5j"
            + "C9DZuUC94CwKA9l/hEC9sS3IA9hPKTC9wQHLC9z0xuC9QsTWC/o52yC8TYwPC8oZAIC+G7UYA+j4RFC9+Ef1C9Z59MA9xHET"
            + "A9d11/A9O2D/A/d7PqA92N3oC98lTnA+S7UYA+J1wHC9aMBIC8+C56C9ltsOC/LnCwC9oBEKA+gu9OC9kdbQA+NiazA/NY6G"
            + "C+bgk1A/q0aIC9BpOzA9amLFC/gb35A+Bc/uC+UhUzA/ebOvA+tmwJA9nPOLC+mERrC81d2CC9vzDbA9aQBaC8SZQzC9MZPB"
            + "A+BiWnA/ZAU+A8Co0iA7s17SA6zp0xA7nRR4C9UVfhA89cmxC7xj5+C8WrrMC5j9AxA+BNdrC7rqA8A9ry0HA+DCuVA8j9IF"
            + "C8So9wC6BkcbC8ozeoC9AZFlA+2I/JA9PI26A8i/P0C6Y4cjC9hiwMA861zbC9Gd0hC9BtiyC837NZA8xca0C9L71dA8tYds"
            + "C9gtQLC9DkqWC1LPakA9Q1ooC8dd8VA8b93eC9hcEyA8MFlkA8S0a7A7oFmnA/wT99A+pP69A+Nxn4A9wUj+C9zf52C+u+pP"
            + "C+IEgXC9Iq1ZA9miuQC7D4nLC/YzBRA8e1rqC8cslLC/EHk+A+J+6RC8tRAYC8Pdg9C9WMtzA+IJokC/ka4+C+VNoKC7nulP"
            + "A+CvnsA+BiXqA9KzAVA+oQoTA9+UfQC9z+cZA+iTDgA+YQAdA9I1tqA9kiApA2dZA8C72ESlA+9GZNC+A4IbA9hOPYA95SNw"
            + "A+ZfD2A9fOXPA98SK4A9AIUrA9cGFBC+6yh0A9zTttC+EKkVA/H9l3A8cCxNC/tYNRC+w9DAC/MiiJC900HhA+FU6xA+qsPr"
            + "C+g0KJA9xlUZA90dWAC+OZLJA/OrGSA+Gs4lA/u9ApA942sJDAiThcA8kiDKA+8+wDDA0mkaA9gbT+A+/m4iC+Hl5oC4vkYx"
            + "A/WVPvDArNiEA+wFnJA+cYVJC+KpbVC8nP+AC9I0ZWDAUA89A+LIPtC+hUhFDAsH7QA9a6gkA+qG/vA95rHmA+bOQhC9+ys0"
            + "A9aGD4A/mQMyC9HqiXA9gSRYA+NOh1A9mxJAC/VUZOA+COCHA/e5AyA8/2miA9uhKbC9ziGWC9HlnYC+bSUkC/Vrj6C+sb1i"
            + "A+ZvETC/peuFA9pT1XA+vUKBC/0RwZA98cfeC+n7emA/c0GeC+talDC/Jg5jC/3DiwA+hVLBA/amHQA9bH2iC+JehPC+6DbK"
            + "C/2hi9C+n447C/O0muC/LZc+A91cUuC+lnuBA+FFc6A9IJzDA87PI8C9jFetA/InSfC9o45gC99B2PC7qY1tC9PpY+C+AqEw"
            + "C9bqbCA9lpcAC9wejLA8DdFEA8PaQ7A8n55hA9OqPVA+OVmjC9qODXA9sf2BA+h4+KC9mbBAA+CmEXA+og27A+zEuyA9y7yX"
            + "C+KvNeC90SdwA+6mxdA+mdmQA+72boA/VIrfC9UXxEC/BdxAC+O3EyA+hnOCA98nJDA+d3lkA+gDRMC9xVOEC/VRJFC+aSnt"
            + "C+CIN3A+bUd8C9j2qHA/ZCz1A+ZiLEC/EqVhA+zjuKC+RCyAA/Pa0QC7dScJA/KH5aC+VmOEC+1R/mA+KknDA8HJfmC9s2ud"
            + "A+2ZOSA+h8u0C+BHJ+C+UAWBC+R07sC/UXHBC+KBd2C+vOiWA+i/eMA/GZTRC+S1stA9wHDGC9/yPNC/QHcDC+myJsC+DoQn"
            + "C+vtdiA+qlguC+Uv12C7wpBzC+55wgC+inHeC+S55JA9x/KoC+XU+cA94YelC+YaFVA7+ijRC833UQC+QvboC9Ezb6C9IhpX"
            + "A6drhQC/v/KRA9uzwkA+5Y2bC+10NCC8J0ivC9mzvhC8kY+qC8vaNkC+EYk3A/qKu0A83KqQA+CcurA+G7qZC+D0qZA+DpFD"
            + "C+W509C+RxzaC/Dk8BA+itpVC942ycA9teBkC+SuA7A9sobeA9zg2qA+3AxjC+weT3A9/DR/C+AGOdA+cVWTA+DpQlA+ZK+S"
            + "A/W6nSC7tchOA+kff5A9w4TiA9PnWhA9znNgC8rRgxA9+S8rA+eErYA9pfw7C/VZhbA99QANA95AUtC+aJO4A+Ix5+C9b3yq"
            + "C93boKC9ujVeC+XrWzA9vlKOC+NQ7+A+NlcyC7KcVhA+osGQA+oEdvA+iRU4C/Ft5EC/mXLvC+ki7DC94DYAA+nm8NC9d5g4"
            + "A/cozOC+EcIaC/aJoCC/UJXyA+PQSjC9lsw3A+jW7wA+BnjAC+yURnC+w3PzC8SRAUA8yZsfC9vIAZA96A8TC94gHNA8CvJi"
            + "A9vrg3C9gsvqC4NLg1C+mcm0A9SICiA+7cs2C9PvkfC8Yaj9A8IpixC+ZVjqA+QYBOC/JzZZC+tWmxA+QcdYC/H9CNC/uyi3"
            + "C9me4DC/BCPZA+0eJYA+lRVIC/+bZwC9ED5WA+SREGC+q/qxA9r4WHA/GvYwC/kIqcC/I21VA/tZdOA9w70xC8FaoBA+ocI7"
            + "C/K9VFC/Wfe2C+IT4+C+l/FSA+OqbSC+DqcFA+bmA9A/XpudA+ft4SA+eIi1A+SCumA92Wa+A+t79AA+K3YuA/e6UJA91pAV"
            + "C9CvTxC815nlA7lHPAA9k/a1C94/TtA6qPJSA+z/ACC8GtHzA9+QU6A/C9lFC65knLA/Rs55A+dJQMA9hAhTA+iqjrA+idLh"
            + "A88TewA/H6bfC+jrRjC9R+PfA+aoVEA+Hin6A9NqPLA+syu7A9kZzGA9qBL0A85Oy0A+MPlNC9+iztC+KKP5A8707YA9SzUd"
            + "C/PeQdA9igUiA93YJ3C9wP0TC9Zo6PA/Dug6A9YkM8A+WHdoA9GlggC+jTQWA9YKOvA+WdZrA+J4eLA9h9tTC8zrJKA+KHtX"
            + "A9qS7NA89uXbA+sQA/C9Gtj/C+Du5SA/ppt8A/niUxC+5LTyA/LrqMC9qjiQC8RzikA/wKV6A9q9SoC/dBLgA9qCNCC+AJHB"
            + "A/MyXVC+D9LpA+LHsDC940IkA+MfozA96t7sA+B66bC9OcUDC+CEpRA/Aw71C+OP4mC89J4CA+lTc7C84Us/C+azdDC9ToW6"
            + "A/D0NjA+xOpsA942CNC9nmzFC9cmGyC9sWTiC+QfvGC+hlIVC9GHIcA+GYXwA+Hlr/C+QNExA+cqLCC+TBykC+wgBcC/Lhze"
            + "A+jesQC+MO5KA+PSZ0C+dLnLA9kdTsA8wNwzA+2FHsC+4gqWA+NbFCC+mLssA+l2V3A+MZ+AA+YdXDC/LhwVA7LnjPC9J04I"
            + "C+HpOfA+eTC+A+cvPDC86AjIA+ScI7C9zNr1A9Y0OBC/g/GVA+G2MLA/CeCDA+vaDgA+SWdFA8tfaSC9qkVbC9qo7dC+ks/6"
            + "C/L53lA9y8beA+GHmBC9MpnLC+og1WA7gGWBC+ae9SA/cKekA/BSHNC+qx20A/JvLHC9mtKnA9xag+C/e150A+ycbzC+10dS"
            + "A+p5HFC+CeH0A/WWuHC/Bfu1A8KTKUA+CNfgA+mKMOC+g8h+A9GhKAC+Sw6iA9lsfrA/G9roA87xJyC+lHuJC+ELUkA8lzk3"
            + "A9YxjvA9fpL+A++HYZA/HffGC9vWa7C8Rfl0A9p3JdA9zamGC8e5TMC+CziTC8l9DXC+yqaxC+UcdYA/DEQ5C+4FDfA8vD4J"
            + "A+0F/QA/S6tlDAAcOLC/VeNUC+xgk1A8HqhMA/m3tKC/PamXC/l5I6C/dHWCC/CohIA9dB9yA924XoC+0stkC/du+RA/AwxG";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_3_dnn_layer_0_bias} weights, 16 values.
     */
    private static final String DNN_3_0_BIAS =
            "C++JlbA+BrWRA/S/0/C+IV0tC9wi0BA8Bh8eC++JuVA+w+QmC9q+TbA+LxoaA+jyCGC+kwRoA+PWz4C9k22bA+SjgyA+zuwY";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_3_dnn_layer_1_kernel} weights, 384 values.
     */
    private static final String DNN_3_1_KERNEL =
            "A/pp7DA/OF5OA+hfpEC+7rHmA/m96UA+/bV0A/H70nA+5C7cC+qXfqC+iiL3A/Vc0cC+lvm6A+P3fyA8hRfNA+vD0lA/KJFc"
            + "A/iqfwA/gf6HA+AuXeC+j6hQBADhsJA9CicAC+zqsVC+bHMmC+tCmeA+4BvKC91D4QC/JVuaA/lkftA+ISmJA+WYlZA/tZlG"
            + "A+kYgrC+/HhCA/iP+XA7zyG8A/l1L8C+hSKnA+jbgTC/UrpaA+l28qC+IT8HC+OUMYA8wmz3A+Uqb0C+l2M9A+KWnkC/INbk"
            + "A/gQd0A+uPNnA/JWpqC86rWAA/0lNlA+qyLyC/wOmjC/wigkC++h+OA+LcREC/F9piC+vtAtC/wEkTA+s5bRC+fFLnC+cuGs"
            + "A+QxL1C/crylC+m1ArA+5vP1C/S1u0DAE/FBA+ztojDAKw1aC/cSZjC+6B7NA9hlicC+k61cA92TouA+ku7IA9+KIjA+7Owg"
            + "A8VXhtA+dl8PDAE9RBC+6BgeC+GOtGA/Wz6aC+Pw3HA/RRwqC9hpLPC/eed1A/eRShC/CGDdC/MweOC+LNqyA+uVDbA/c2WZ"
            + "A/xLXdC+ZUXIA/lnXNC+kB6KA/CJtBA+oXTFA9rwKqA+7e1KA+sZNxA+iyiVC8l96uA94EpzA+vkp7C93j+8A+efNBA+++IN"
            + "C/QBJqA/tZZTDAYMgMA+mKziC9ofZfC+CWC3C+2SGKA+QiSrC+dBToC+0NSZC+u4FzC/G1gZC+bAerA+xkVfC90mY0C+Qx33"
            + "C/t97oA+ELrpC9M8fJA+UYoBA/yQCTA9FkwiC+Ft2tA9RWtEC9+yX2A+O8EnA9humhC+eTjeA8ykBgC/RM/yC9g+b6A/Rmsv"
            + "A/0S0jA++7YkA+YDxLC+t8eCBAVAjZA8oH6QA+cdeZA/B9w4A+D3yqA9luRjC+jBacC/GU1RC/gh+fA+uTlDA7lWrhC+sMDw"
            + "A/1/nGA/Q5rhA+RjNIA+pUOvA/mVhkA/CfelC9IRFCC8y2abC+GsvJA+9bKzC+VZaFA+aXOXA+q8MeC7QaEgA+DuNQA+zTd+"
            + "A+9OSoC+QevIA/CkLyA+7qD9A/a5RCA+zCwbC+8d7OC9MO+MC/FOf/C/fXRxA+hJr+C8c/CTA+iscRA+rkBCA+ZPEbC9JfeR"
            + "C/npOfC/ueSkC/uQPaC+wJY1A+B8iOC+0BOpC/FWeIC+v8oAA9hw0FA+prvcC+rUobA+ge3AC8lGjYA/LoX9A+Y163A/VmYj"
            + "A/jHX3A+IBRyA+NX1bC/M+RHA+8oddA+pOynA+3eqJA+oYF8C+0OLBA9picDA/IaV2A+uU7CA9lCB+A8hsG7A88LgvA+ICjl"
            + "C/jWE9A/VoWpC/hGorC+smKJA/3BmPC+zbQkA/JIlMC+OtEHC+b5GjA+uqx1A+idGRC/FBldA+y1/lA9LiFGC+hHf/A/tUZf"
            + "C/MFMaA9wXraA+gZ6VC/fTGHA/3RbbA/lHMEC9PKqrA/CMBqC+DBj4A+/I3jA97QsxC/Ig+yC+3iI+C/gmYTC+pefqA++p4K"
            + "A/iyGBA/Vhy9C+2NH6C+17XQA/iWfLA+pYMfA/N7TlA+u5uqA+qN96A+rFSbA/P9GaC+V7jUA+xjIaC/FVghA9IDHqC/iaW5"
            + "A/aiKjC/A1XvA/kzq9C9GyYsA+hpljC/V1QqA+1RbbC/K3G5C94UUPA/glYEC+R4eLA990YCA/IL1VC7MG/KA/QEt/C/B6U8"
            + "C/wE39A+PPMTA/S6OHA92UpvA/6mPHA9tOafA+nRqoC9iYYmC+LV6NC+W7mMC9+QyRC+cJqqA94MWoC+t3RpC9oatLA+/pFk"
            + "DAKFQNA/UMuwC+Rj0+C/H3evA+rYmtA9xthGC/B/tIA/OS00A+s4XGA+OwfRC+g5c1A/SPDHC+XyUcC9o0zJC+zKZEC++KDT"
            + "A/naNuA+sSamA+pVmSC+LDuSC+MXj3A+kH0sA+5v/BA/azBRC9PR7aC9svijA/D5+pC++LaVA/C9OzA+go73A+yeEqA/G3AE"
            + "A/KuNhC+sBFvA+v7GeC/CAxjBAEcfOC/kPdmA/AYCAC/A8YiA/f6JZC/zg89C+MQWfC9d2/ZA/OEQ5C/O+Y/A+sqOtA/se4k"
            + "C/ZTUzC/LVQRC/682KA9McYWC/COhCA9MhMoC+iHymA+iURGC+eMP0A62fVUA82BiQC+ZjBmC96G8FC+chIOA9tDX9A/AsoE"
            + "C/AwSbC/vqveDAMhduC9SxK4C9DN1DA+4YPYC+unmaA/VU4aC9cWR3A67cnIA+6TcqA90GiYC/lQXRA+inKGA+YEowDAnSdS";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_3_dnn_layer_1_bias} weights, 24 values.
     */
    private static final String DNN_3_1_BIAS =
            "C+zWI5C+vqOcC+XV/dC+dcY7C+rvzfC+sGDdC953/xA+Sz4UA+90ZFC/IUxmC/OoxYC+MBcSA/BRcNA+gYmcC9+RLcC9aWu6"
            + "C++JxeC+Fx5tA/CIatC/FlYVC993lkC+98iwA+5CsPC+ACOn";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_3_logits_kernel} weights, 24 values.
     */
    private static final String DNN_3_LOGITS_KERNEL =
            "C/yYL9C/i5u7A/1wjuC/K3GXA+/+a0DAQgNHC/YM11A/I0gKC/ysa9DAcws5A/IKceC+5K7JA/JzyjC/Kvh7DADKfGDAYiSz"
            + "BAJxW1C/L5aeC/oVGDC/ZaZhC/Ny5aA+w6/7BABiCbA+8FNd";

    /**
     * The packed {@code av1_intra_mode_cnn_partition_branch_3_logits_bias} weights, 1 values.
     */
    private static final String DNN_3_LOGITS_BIAS =
            "C8uqvN";

    /**
     * The convolution kernels of the five layers, indexed
     * {@code ((row * side + column) * inChannels + k) * outChannels + c}.
     */
    private static final float[][] CNN_KERNELS = {
            decode(CNN_0_KERNEL), decode(CNN_1_KERNEL), decode(CNN_2_KERNEL), decode(CNN_3_KERNEL),
            decode(CNN_4_KERNEL)
    };

    /**
     * The convolution biases of the five layers.
     */
    private static final float[][] CNN_BIASES = {
            decode(CNN_0_BIAS), decode(CNN_1_BIAS), decode(CNN_2_BIAS), decode(CNN_3_BIAS), decode(CNN_4_BIAS)
    };

    /**
     * The dense layer weights of each head, from the 64x64 head: hidden, hidden, logits.
     */
    private static final float[][][] DNN_KERNELS = {
            {decode(DNN_0_0_KERNEL), decode(DNN_0_1_KERNEL), decode(DNN_0_LOGITS_KERNEL)},
            {decode(DNN_1_0_KERNEL), decode(DNN_1_1_KERNEL), decode(DNN_1_LOGITS_KERNEL)},
            {decode(DNN_2_0_KERNEL), decode(DNN_2_1_KERNEL), decode(DNN_2_LOGITS_KERNEL)},
            {decode(DNN_3_0_KERNEL), decode(DNN_3_1_KERNEL), decode(DNN_3_LOGITS_KERNEL)},
    };

    /**
     * The dense layer biases of each head.
     */
    private static final float[][][] DNN_BIASES = {
            {decode(DNN_0_0_BIAS), decode(DNN_0_1_BIAS), decode(DNN_0_LOGITS_BIAS)},
            {decode(DNN_1_0_BIAS), decode(DNN_1_1_BIAS), decode(DNN_1_LOGITS_BIAS)},
            {decode(DNN_2_0_BIAS), decode(DNN_2_1_BIAS), decode(DNN_2_LOGITS_BIAS)},
            {decode(DNN_3_0_BIAS), decode(DNN_3_1_BIAS), decode(DNN_3_LOGITS_BIAS)},
    };

    /**
     * The input window, normalized to 0 to 1.
     */
    private final float[] input = new float[INPUT * INPUT];

    /**
     * The first layer's output: 20 channels of 16x16.
     */
    private final float[] layer0 = new float[20 * 16 * 16];

    /**
     * The 8x8 feature map: 20 channels.
     */
    private final float[] branch3 = new float[20 * 8 * 8];

    /**
     * The 4x4 feature map: 20 channels.
     */
    private final float[] branch2 = new float[20 * 4 * 4];

    /**
     * The 2x2 feature map: 4 channels.
     */
    private final float[] branch1 = new float[4 * 2 * 2];

    /**
     * The 1x1 feature map: 20 channels.
     */
    private final float[] branch0 = new float[20];

    /**
     * The per-channel sums of one output position of a convolution.
     */
    private final float[] acc = new float[20];

    /**
     * The dense network's input features.
     */
    private final float[] features = new float[48];

    /**
     * The dense network's hidden activations.
     */
    private final float[][] hidden = {new float[24], new float[24]};

    /**
     * The normalized logarithm of the squared quantizer step.
     */
    private float logQ;

    /**
     * Creates a predictor.
     */
    IntraPartitionCnn() {
    }

    /**
     * Runs the convolutional network on the neighborhood of a superblock.
     *
     * @param src the luma source plane
     * @param stride the plane stride
     * @param width the plane width
     * @param height the plane height
     * @param x0 the superblock's left column
     * @param y0 the superblock's top row
     * @param bitDepth the bit depth
     * @param dcQuant the luma DC quantizer step at the 8-bit scale
     */
    void prepare(short[] src, int stride, int width, int height, int x0, int y0, int bitDepth, int dcQuant) {
        var max = (float) ((1 << bitDepth) - 1);
        for (var y = 0; y < INPUT; y++) {
            var sy = Math.clamp(y0 - 1 + y, 0, height - 1);
            for (var x = 0; x < INPUT; x++) {
                var sx = Math.clamp(x0 - 1 + x, 0, width - 1);
                input[y * INPUT + x] = src[sy * stride + sx] / max;
            }
        }
        convolve(input, INPUT, 1, CNN_KERNELS[0], CNN_BIASES[0], 5, 4, 20, layer0);
        convolve(layer0, 16, 20, CNN_KERNELS[1], CNN_BIASES[1], 2, 2, 20, branch3);
        convolve(branch3, 8, 20, CNN_KERNELS[2], CNN_BIASES[2], 2, 2, 20, branch2);
        convolve(branch2, 4, 20, CNN_KERNELS[3], CNN_BIASES[3], 2, 2, 4, branch1);
        convolve(branch1, 2, 4, CNN_KERNELS[4], CNN_BIASES[4], 2, 2, 20, branch0);
        logQ = ((float) StrictMath.log1p((float) (dcQuant * dcQuant) / 256.0f) - LOG_Q_MEAN) / LOG_Q_STD;
    }

    /**
     * Decides the partitions of a node of the prepared superblock.
     *
     * @param level the block level: 1 for 64x64 to 4 for 8x8
     * @param quad the node's index in the quad tree: 0 for the superblock, {@code 4 * p + i + 1}
     * for child {@code i} of node {@code p}
     * @param resolution 0 for pictures whose shorter side is at least 720, 1 for at least 480, 2
     * otherwise
     * @return {@link #SPLIT_ONLY}, {@link #NO_SPLIT} or {@link #UNDECIDED}
     */
    int decide(int level, int quad, int resolution) {
        var f = features;
        var n = 0;
        switch (level) {
            case 1 -> {
                for (var c = 0; c < 20; c++) {
                    f[n++] = branch0[c];
                }
                for (var i = 0; i < 4; i++) {
                    for (var c = 0; c < 4; c++) {
                        f[n++] = branch1[i + c * 4];
                    }
                }
            }
            case 2 -> {
                for (var c = 0; c < 20; c++) {
                    f[n++] = branch0[c];
                }
                var cell = QUAD_TO_LINEAR_1[quad - 1];
                for (var c = 0; c < 4; c++) {
                    f[n++] = branch1[cell + c * 4];
                }
            }
            case 3 -> {
                var parent = QUAD_TO_LINEAR_1[(quad - 1) / 4 - 1];
                for (var c = 0; c < 4; c++) {
                    f[n++] = branch1[parent + c * 4];
                }
                var cell = QUAD_TO_LINEAR_2[quad - 5];
                for (var c = 0; c < 20; c++) {
                    f[n++] = branch2[cell + c * 16];
                }
            }
            default -> {
                var parent = QUAD_TO_LINEAR_2[(quad - 1) / 4 - 5];
                for (var c = 0; c < 20; c++) {
                    f[n++] = branch2[parent + c * 16];
                }
                var cell = QUAD_TO_LINEAR_3[quad - 21];
                for (var c = 0; c < 20; c++) {
                    f[n++] = branch3[cell + c * 64];
                }
            }
        }
        f[n++] = logQ;
        var logit = dense(level - 1, f, n);
        if (logit > SPLIT_THRESHOLDS[resolution][level]) {
            return SPLIT_ONLY;
        }
        if (logit < NO_SPLIT_THRESHOLDS[resolution][level]) {
            return NO_SPLIT;
        }
        return UNDECIDED;
    }

    /**
     * Applies a strided valid convolution followed by a rectified linear unit
     * ({@code av1_cnn_convolve_no_maxpool_padding_valid_c}).
     *
     * @param in the input, channel-major with square planes
     * @param inSide the side of the input planes
     * @param inChannels the input channels
     * @param kernel the kernel, indexed {@code ((row * side + column) * inChannels + k) * outChannels + c}
     * @param bias the bias of each output channel
     * @param filter the kernel side
     * @param step the stride
     * @param outChannels the output channels
     * @param out receives the output, channel-major
     */
    private void convolve(float[] in, int inSide, int inChannels, float[] kernel, float[] bias, int filter,
                                 int step, int outChannels, float[] out) {
        var outSide = (inSide - filter + step) / step;
        var planeSize = outSide * outSide;
        var acc = this.acc;
        for (var oy = 0; oy < outSide; oy++) {
            var h = oy * step;
            for (var ox = 0; ox < outSide; ox++) {
                var w = ox * step;
                System.arraycopy(bias, 0, acc, 0, outChannels);
                for (var k = 0; k < inChannels; k++) {
                    var plane = k * inSide * inSide;
                    for (var ii = 0; ii < filter; ii++) {
                        var row = plane + (h + ii) * inSide + w;
                        for (var jj = 0; jj < filter; jj++) {
                            var v = in[row + jj];
                            var off = ((ii * filter + jj) * inChannels + k) * outChannels;
                            for (var c = 0; c < outChannels; c++) {
                                acc[c] += kernel[off + c] * v;
                            }
                        }
                    }
                }
                var pos = oy * outSide + ox;
                for (var c = 0; c < outChannels; c++) {
                    var sum = acc[c];
                    out[c * planeSize + pos] = sum < 0 ? 0 : sum;
                }
            }
        }
    }

    /**
     * Runs a head's dense network and returns its rounded split score ({@code av1_nn_predict_c}
     * with output rounding).
     *
     * @param head the head, 0 for 64x64 to 3 for 8x8
     * @param input the features
     * @param inputs the number of features
     * @return the score
     */
    private float dense(int head, float[] input, int inputs) {
        var kernels = DNN_KERNELS[head];
        var biases = DNN_BIASES[head];
        var in = input;
        var count = inputs;
        for (var layer = 0; layer < 2; layer++) {
            var weights = kernels[layer];
            var bias = biases[layer];
            var nodes = bias.length;
            var out = hidden[layer];
            for (var node = 0; node < nodes; node++) {
                var val = bias[node];
                for (var i = 0; i < count; i++) {
                    val += weights[node * count + i] * in[i];
                }
                out[node] = val > 0.0f ? val : 0.0f;
            }
            in = out;
            count = nodes;
        }
        var val = biases[2][0];
        var weights = kernels[2];
        for (var i = 0; i < count; i++) {
            val += weights[i] * in[i];
        }
        return ((int) ((double) (val * 512) + 0.5)) * (1.0f / 512);
    }

    /**
     * Decodes packed weights: six base-64 digits of the IEEE bits of each value, most significant
     * first, of which the low 32 bits count.
     *
     * @param text the packed text
     * @return the values
     */
    private static float[] decode(String text) {
        var bits = TableText.decode(text, 6);
        var values = new float[bits.length];
        for (var i = 0; i < bits.length; i++) {
            values[i] = Float.intBitsToFloat(bits[i]);
        }
        return values;
    }
}

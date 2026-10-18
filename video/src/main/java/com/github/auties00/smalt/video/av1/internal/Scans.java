package com.github.auties00.smalt.video.av1.internal;


/**
 * Holds the coefficient scan orders of the AV1 transform sizes.
 *
 * <p>Each scan lists, in coding order, the coefficient positions of a transform of at most 32x32
 * coded coefficients.
 *
 * @implNote This implementation copies dav1d's {@code src/scan.c}, which stores the scans of the AV1
 *           specification section 7.13.3 for dav1d's transposed coefficient layout.
 */
public final class Scans {
    /**
     * Holds scan order 0, of 16 positions.
     */
    private static final String SCAN_0 =
            "AAAEABACAFAIAMAJAGADAHAKANAOALAP";

    /**
     * Holds scan order 1, of 64 positions.
     */
    private static final String SCAN_1 =
            "AAAIABACAJAQAYARAKADAEALASAZAgAoAhAaATAMAFAGANAUAbAiApAwA4AxAqAjAcAVAOAHAPAWAdAkArAyA5A6AzAsAlAe"
                    + "AXAfAmAtA0A7A8A1AuAnAvA2A9A+A3A/";

    /**
     * Holds scan order 2, of 256 positions.
     */
    private static final String SCAN_2 =
            "AAAQABACARAgAwAhASADAEATAiAxBABQBBAyAjAUAFAGAVAkAzBCBRBgBwBhBSBDA0AlAWAHAIAXAmA1BEBTBiBxCACQCBBy"
                    + "BjBUBFA2AnAYAJAKAZAoA3BGBVBkBzCCCRCgCwChCSCDB0BlBWBHA4ApAaALAMAbAqA5BIBXBmB1CECTCiCxDADQDBCyCjCU"
                    + "CFB2BnBYBJA6ArAcANAOAdAsA7BKBZBoB3CGCVCkCzDCDRDgDwDhDSDDC0ClCWCHB4BpBaBLA8AtAeAPAfAuA9BMBbBqB5CI"
                    + "CXCmC1DEDTDiDxDyDjDUDFC2CnCYCJB6BrBcBNA+AvA/BOBdBsB7CKCZCoC3DGDVDkDzD0DlDWDHC4CpCaCLB8BtBeBPBfBu"
                    + "B9CMCbCqC5DIDXDmD1D2DnDYDJC6CrCcCNB+BvB/COCdCsC7DKDZDoD3D4DpDaDLC8CtCeCPCfCuC9DMDbDqD5D6DrDcDNC+"
                    + "CvC/DODdDsD7D8DtDeDPDfDuD9D+DvD/";

    /**
     * Holds scan order 3, of 1024 positions.
     */
    private static final String SCAN_3 =
            "AAAgABACAhBABgBBAiADAEAjBCBhCACgCBBiBDAkAFAGAlBEBjCCChDADgDBCiCDBkBFAmAHAIAnBGBlCECjDCDhEAEgEBDi"
                    + "DDCkCFBmBHAoAJAKApBIBnCGClDEDjECEhFAFgFBEiEDDkDFCmCHBoBJAqALAMArBKBpCICnDGDlEEEjFCFhGAGgGBFiFDEk"
                    + "EFDmDHCoCJBqBLAsANAOAtBMBrCKCpDIDnEGElFEFjGCGhHAHgHBGiGDFkFFEmEHDoDJCqCLBsBNAuAPAQAvBOBtCMCrDKDp"
                    + "EIEnFGFlGEGjHCHhIAIgIBHiHDGkGFFmFHEoEJDqDLCsCNBuBPAwARASAxBQBvCOCtDMDrEKEpFIFnGGGlHEHjICIhJAJgJB"
                    + "IiIDHkHFGmGHFoFJEqELDsDNCuCPBwBRAyATAUAzBSBxCQCvDODtEMErFKFpGIGnHGHlIEIjJCJhKAKgKBJiJDIkIFHmHHGo"
                    + "GJFqFLEsENDuDPCwCRByBTA0AVAWA1BUBzCSCxDQDvEOEtFMFrGKGpHIHnIGIlJEJjKCKhLALgLBKiKDJkJFImIHHoHJGqGL"
                    + "FsFNEuEPDwDRCyCTB0BVA2AXAYA3BWB1CUCzDSDxEQEvFOFtGMGrHKHpIIInJGJlKEKjLCLhMAMgMBLiLDKkKFJmJHIoIJHq"
                    + "HLGsGNFuFPEwERDyDTC0CVB2BXA4AZAaA5BYB3CWC1DUDzESExFQFvGOGtHMHrIKIpJIJnKGKlLELjMCMhNANgNBMiMDLkLF"
                    + "KmKHJoJJIqILHsHNGuGPFwFREyETD0DVC2CXB4BZA6AbAcA7BaB5CYC3DWD1EUEzFSFxGQGvHOHtIMIrJKJpKIKnLGLlMEMj"
                    + "NCNhOAOgOBNiNDMkMFLmLHKoKJJqJLIsINHuHPGwGRFyFTE0EVD2DXC4CZB6BbA8AdAeA9BcB7CaC5DYD3EWE1FUFzGSGxHQ"
                    + "HvIOItJMJrKKKpLILnMGMlNENjOCOhPAPgPBOiODNkNFMmMHLoLJKqKLJsJNIuIPHwHRGyGTF0FVE2EXD4DZC6CbB8BdA+Af"
                    + "A/BeB9CcC7DaD5EYE3FWF1GUGzHSHxIQIvJOJtKMKrLKLpMIMnNGNlOEOjPCPhPiPDOkOFNmNHMoMJLqLLKsKNJuJPIwIRHy"
                    + "HTG0GVF2FXE4EZD6DbC8CdB+BfB/CeC9DcD7EaE5FYF3GWG1HUHzISIxJQJvKOKtLMLrMKMpNINnOGOlPEPjPkPFOmOHNoNJ"
                    + "MqMLLsLNKuKPJwJRIyITH0HVG2GXF4FZE6EbD8DdC+CfC/DeD9EcE7FaF5GYG3HWH1IUIzJSJxKQKvLOLtMMMrNKNpOIOnPG"
                    + "PlPmPHOoOJNqNLMsMNLuLPKwKRJyJTI0IVH2HXG4GZF6FbE8EdD+DfD/EeE9FcF7GaG5HYH3IWI1JUJzKSKxLQLvMOMtNMNr"
                    + "OKOpPIPnPoPJOqOLNsNNMuMPLwLRKyKTJ0JVI2IXH4HZG6GbF8FdE+EfE/FeF9GcG7HaH5IYI3JWJ1KUKzLSLxMQMvNONtOM"
                    + "OrPKPpPqPLOsONNuNPMwMRLyLTK0KVJ2JXI4IZH6HbG8GdF+FfF/GeG9HcH7IaI5JYJ3KWK1LULzMSMxNQNvOOOtPMPrPsPN"
                    + "OuOPNwNRMyMTL0LVK2KXJ4JZI6IbH8HdG+GfG/HeH9IcI7JaJ5KYK3LWL1MUMzNSNxOQOvPOPtPuPPOwORNyNTM0MVL2LXK4"
                    + "KZJ6JbI8IdH+HfH/IeI9JcJ7KaK5LYL3MWM1NUNzOSOxPQPvPwPROyOTN0NVM2MXL4LZK6KbJ8JdI+IfI/JeJ9KcK7LaL5MY"
                    + "M3NWN1OUOzPSPxPyPTO0OVN2NXM4MZL6LbK8KdJ+JfJ/KeK9LcL7MaM5NYN3OWO1PUPzP0PVO2OXN4NZM6MbL8LdK+KfK/Le"
                    + "L9McM7NaN5OYO3PWP1P2PXO4OZN6NbM8MdL+LfL/MeM9NcN7OaO5PYP3P4PZO6ObN8NdM+MfM/NeN9OcO7PaP5P6PbO8OdN+"
                    + "NfN/OeO9PcP7P8PdO+OfO/PeP9P+PfP/";

    /**
     * Holds scan order 4, of 32 positions.
     */
    private static final String SCAN_4 =
            "AAAIABAQAJACAYARAKADAZASALAEAaATAMAFAbAUANAGAcAVAOAHAdAWAPAeAXAf";

    /**
     * Holds scan order 5, of 32 positions.
     */
    private static final String SCAN_5 =
            "AAABAEACAFAIADAGAJAMAHAKANAQALAOARAUAPASAVAYATAWAZAcAXAaAdAbAeAf";

    /**
     * Holds scan order 6, of 128 positions.
     */
    private static final String SCAN_6 =
            "AAAQABAgARACAwAhASADBAAxAiATAEBQBBAyAjAUAFBgBRBCAzAkAVAGBwBhBSBDA0AlAWAHBxBiBTBEA1AmAXAIByBjBUBF"
                    + "A2AnAYAJBzBkBVBGA3AoAZAKB0BlBWBHA4ApAaALB1BmBXBIA5AqAbAMB2BnBYBJA6ArAcANB3BoBZBKA7AsAdAOB4BpBaBL"
                    + "A8AtAeAPB5BqBbBMA9AuAfB6BrBcBNA+AvB7BsBdBOA/B8BtBeBPB9BuBfB+BvB/";

    /**
     * Holds scan order 7, of 128 positions.
     */
    private static final String SCAN_7 =
            "AAABAIACAJAQADAKARAYAEALASAZAgAFAMATAaAhAoAGANAUAbAiApAwAHAOAVAcAjAqAxA4APAWAdAkArAyA5BAAXAeAlAs"
                    + "AzA6BBBIAfAmAtA0A7BCBJBQAnAuA1A8BDBKBRBYAvA2A9BEBLBSBZBgA3A+BFBMBTBaBhBoA/BGBNBUBbBiBpBwBHBOBVBc"
                    + "BjBqBxB4BPBWBdBkBrByB5BXBeBlBsBzB6BfBmBtB0B7BnBuB1B8BvB2B9B3B+B/";

    /**
     * Holds scan order 8, of 512 positions.
     */
    private static final String SCAN_8 =
            "AAAgABBAAhACBgBBAiADCABhBCAjAECgCBBiBDAkAFDAChCCBjBEAlAGDgDBCiCDBkBFAmAHEADhDCCjCEBlBGAnAIEgEBDi"
                    + "DDCkCFBmBHAoAJFAEhECDjDEClCGBnBIApAKFgFBEiEDDkDFCmCHBoBJAqALGAFhFCEjEEDlDGCnCIBpBKArAMGgGBFiFDEk"
                    + "EFDmDHCoCJBqBLAsANHAGhGCFjFEElEGDnDICpCKBrBMAtAOHgHBGiGDFkFFEmEHDoDJCqCLBsBNAuAPHhHCGjGEFlFGEnEI"
                    + "DpDKCrCMBtBOAvAQHiHDGkGFFmFHEoEJDqDLCsCNBuBPAwARHjHEGlGGFnFIEpEKDrDMCtCOBvBQAxASHkHFGmGHFoFJEqEL"
                    + "DsDNCuCPBwBRAyATHlHGGnGIFpFKErEMDtDOCvCQBxBSAzAUHmHHGoGJFqFLEsENDuDPCwCRByBTA0AVHnHIGpGKFrFMEtEO"
                    + "DvDQCxCSBzBUA1AWHoHJGqGLFsFNEuEPDwDRCyCTB0BVA2AXHpHKGrGMFtFOEvEQDxDSCzCUB1BWA3AYHqHLGsGNFuFPEwER"
                    + "DyDTC0CVB2BXA4AZHrHMGtGOFvFQExESDzDUC1CWB3BYA5AaHsHNGuGPFwFREyETD0DVC2CXB4BZA6AbHtHOGvGQFxFSEzEU"
                    + "D1DWC3CYB5BaA7AcHuHPGwGRFyFTE0EVD2DXC4CZB6BbA8AdHvHQGxGSFzFUE1EWD3DYC5CaB7BcA9AeHwHRGyGTF0FVE2EX"
                    + "D4DZC6CbB8BdA+AfHxHSGzGUF1FWE3EYD5DaC7CcB9BeA/HyHTG0GVF2FXE4EZD6DbC8CdB+BfHzHUG1GWF3FYE5EaD7DcC9"
                    + "CeB/H0HVG2GXF4FZE6EbD8DdC+CfH1HWG3GYF5FaE7EcD9DeC/H2HXG4GZF6FbE8EdD+DfH3HYG5GaF7FcE9EeD/H4HZG6Gb"
                    + "F8FdE+EfH5HaG7GcF9FeE/H6HbG8GdF+FfH7HcG9GeF/H8HdG+GfH9HeG/H+HfH/";

    /**
     * Holds scan order 9, of 512 positions.
     */
    private static final String SCAN_9 =
            "AAABAQACARAgADASAhAwAEATAiAxBAAFAUAjAyBBBQAGAVAkAzBCBRBgAHAWAlA0BDBSBhBwAIAXAmA1BEBTBiBxCAAJAYAn"
                    + "A2BFBUBjByCBCQAKAZAoA3BGBVBkBzCCCRCgALAaApA4BHBWBlB0CDCSChCwAMAbAqA5BIBXBmB1CECTCiCxDAANAcArA6BJ"
                    + "BYBnB2CFCUCjCyDBDQAOAdAsA7BKBZBoB3CGCVCkCzDCDRDgAPAeAtA8BLBaBpB4CHCWClC0DDDSDhDwAfAuA9BMBbBqB5CI"
                    + "CXCmC1DEDTDiDxEAAvA+BNBcBrB6CJCYCnC2DFDUDjDyEBEQA/BOBdBsB7CKCZCoC3DGDVDkDzECEREgBPBeBtB8CLCaCpC4"
                    + "DHDWDlD0EDESEhEwBfBuB9CMCbCqC5DIDXDmD1EEETEiExFABvB+CNCcCrC6DJDYDnD2EFEUEjEyFBFQB/COCdCsC7DKDZDo"
                    + "D3EGEVEkEzFCFRFgCPCeCtC8DLDaDpD4EHEWElE0FDFSFhFwCfCuC9DMDbDqD5EIEXEmE1FEFTFiFxGACvC+DNDcDrD6EJEY"
                    + "EnE2FFFUFjFyGBGQC/DODdDsD7EKEZEoE3FGFVFkFzGCGRGgDPDeDtD8ELEaEpE4FHFWFlF0GDGSGhGwDfDuD9EMEbEqE5FI"
                    + "FXFmF1GEGTGiGxHADvD+ENEcErE6FJFYFnF2GFGUGjGyHBHQD/EOEdEsE7FKFZFoF3GGGVGkGzHCHRHgEPEeEtE8FLFaFpF4"
                    + "GHGWGlG0HDHSHhHwEfEuE9FMFbFqF5GIGXGmG1HEHTHiHxEvE+FNFcFrF6GJGYGnG2HFHUHjHyE/FOFdFsF7GKGZGoG3HGHV"
                    + "HkHzFPFeFtF8GLGaGpG4HHHWHlH0FfFuF9GMGbGqG5HIHXHmH1FvF+GNGcGrG6HJHYHnH2F/GOGdGsG7HKHZHoH3GPGeGtG8"
                    + "HLHaHpH4GfGuG9HMHbHqH5GvG+HNHcHrH6G/HOHdHsH7HPHeHtH8HfHuH9HvH+H/";

    /**
     * Holds scan order 10, of 64 positions.
     */
    private static final String SCAN_10 =
            "AAAQABAgARACAwAhASADAxAiATAEAyAjAUAFAzAkAVAGA0AlAWAHA1AmAXAIA2AnAYAJA3AoAZAKA4ApAaALA5AqAbAMA6Ar"
                    + "AcANA7AsAdAOA8AtAeAPA9AuAfA+AvA/";

    /**
     * Holds scan order 11, of 64 positions.
     */
    private static final String SCAN_11 =
            "AAABAEACAFAIADAGAJAMAHAKANAQALAOARAUAPASAVAYATAWAZAcAXAaAdAgAbAeAhAkAfAiAlAoAjAmApAsAnAqAtAwArAu"
                    + "AxA0AvAyA1A4AzA2A5A8A3A6A9A7A+A/";

    /**
     * Holds scan order 12, of 256 positions.
     */
    private static final String SCAN_12 =
            "AAAgABBAAhACBgBBAiADCABhBCAjAECgCBBiBDAkAFDAChCCBjBEAlAGDgDBCiCDBkBFAmAHDhDCCjCEBlBGAnAIDiDDCkCF"
                    + "BmBHAoAJDjDEClCGBnBIApAKDkDFCmCHBoBJAqALDlDGCnCIBpBKArAMDmDHCoCJBqBLAsANDnDICpCKBrBMAtAODoDJCqCL"
                    + "BsBNAuAPDpDKCrCMBtBOAvAQDqDLCsCNBuBPAwARDrDMCtCOBvBQAxASDsDNCuCPBwBRAyATDtDOCvCQBxBSAzAUDuDPCwCR"
                    + "ByBTA0AVDvDQCxCSBzBUA1AWDwDRCyCTB0BVA2AXDxDSCzCUB1BWA3AYDyDTC0CVB2BXA4AZDzDUC1CWB3BYA5AaD0DVC2CX"
                    + "B4BZA6AbD1DWC3CYB5BaA7AcD2DXC4CZB6BbA8AdD3DYC5CaB7BcA9AeD4DZC6CbB8BdA+AfD5DaC7CcB9BeA/D6DbC8CdB+"
                    + "BfD7DcC9CeB/D8DdC+CfD9DeC/D+DfD/";

    /**
     * Holds scan order 13, of 256 positions.
     */
    private static final String SCAN_13 =
            "AAABAIACAJAQADAKARAYAEALASAZAgAFAMATAaAhAoAGANAUAbAiApAwAHAOAVAcAjAqAxA4APAWAdAkArAyA5BAAXAeAlAs"
                    + "AzA6BBBIAfAmAtA0A7BCBJBQAnAuA1A8BDBKBRBYAvA2A9BEBLBSBZBgA3A+BFBMBTBaBhBoA/BGBNBUBbBiBpBwBHBOBVBc"
                    + "BjBqBxB4BPBWBdBkBrByB5CABXBeBlBsBzB6CBCIBfBmBtB0B7CCCJCQBnBuB1B8CDCKCRCYBvB2B9CECLCSCZCgB3B+CFCM"
                    + "CTCaChCoB/CGCNCUCbCiCpCwCHCOCVCcCjCqCxC4CPCWCdCkCrCyC5DACXCeClCsCzC6DBDICfCmCtC0C7DCDJDQCnCuC1C8"
                    + "DDDKDRDYCvC2C9DEDLDSDZDgC3C+DFDMDTDaDhDoC/DGDNDUDbDiDpDwDHDODVDcDjDqDxD4DPDWDdDkDrDyD5DXDeDlDsDz"
                    + "D6DfDmDtD0D7DnDuD1D8DvD2D9D3D+D/";

    /**
     * Holds the scan of each transform size ({@code RectTxfmSize}); sizes with a 64-sample side
     * share the scan of their 32-sample counterpart.
     */
    public static final int[][] SCANS = new int[19][];

    static {
        var scan0 = TableText.decode(SCAN_0, 2);
        var scan1 = TableText.decode(SCAN_1, 2);
        var scan2 = TableText.decode(SCAN_2, 2);
        var scan3 = TableText.decode(SCAN_3, 2);
        var scan4 = TableText.decode(SCAN_4, 2);
        var scan5 = TableText.decode(SCAN_5, 2);
        var scan6 = TableText.decode(SCAN_6, 2);
        var scan7 = TableText.decode(SCAN_7, 2);
        var scan8 = TableText.decode(SCAN_8, 2);
        var scan9 = TableText.decode(SCAN_9, 2);
        var scan10 = TableText.decode(SCAN_10, 2);
        var scan11 = TableText.decode(SCAN_11, 2);
        var scan12 = TableText.decode(SCAN_12, 2);
        var scan13 = TableText.decode(SCAN_13, 2);
        SCANS[0] = scan0;
        SCANS[1] = scan1;
        SCANS[2] = scan2;
        SCANS[3] = scan3;
        SCANS[4] = scan3;
        SCANS[5] = scan4;
        SCANS[6] = scan5;
        SCANS[7] = scan6;
        SCANS[8] = scan7;
        SCANS[9] = scan8;
        SCANS[10] = scan9;
        SCANS[11] = scan3;
        SCANS[12] = scan3;
        SCANS[13] = scan10;
        SCANS[14] = scan11;
        SCANS[15] = scan12;
        SCANS[16] = scan13;
        SCANS[17] = scan8;
        SCANS[18] = scan9;
    }

    /**
     * Prevents instantiation.
     */
    private Scans() {
        throw new AssertionError();
    }
}

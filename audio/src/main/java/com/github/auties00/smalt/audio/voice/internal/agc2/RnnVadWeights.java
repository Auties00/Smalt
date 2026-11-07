/* Ports rnnoise (third_party/rnnoise/src/rnn_vad_weights.cc of webrtc-audio-processing 2.1). Copyright (c) 2017,
   Mozilla, (c) 2007-2017, Jean-Marc Valin, (c) 2005-2017, Xiph.Org Foundation, (c) 2003-2004, Mark Borgerding; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.agc2;

import java.util.Base64;

/**
 * Holds the quantized weights of the voice activity detector's network, in units of 1/256, Base64
 * encoded as signed bytes.
 */
final class RnnVadWeights {
    /**
     * The weights of the input layer, 42 inputs by 24 outputs, input-major.
     */
    static final byte[] INPUT_DENSE_WEIGHTS = decode(
            "9gD9Afj6A/MBAP35+/0G//oA+vz//gEB+QIVCvvsGBclCP4h+hYN/jIIDQHxHvYeAAMFGwEE/Sk4I/4x8wsN/tEF8MTxTe8a/Q7r" +
            "E/vt8wAKDgkf89f2BBYS0Pr2Pv3u8gwa5AMOGfPtBgUk/b/0AB/592X8GhAR9PQO3P0F8RUCHv0m/AH6B/kOJuri/fkD2bqCGSJe" +
            "verfU9GKBEYhGT6AtIqPMfSc7o7fKyA9KPeWAiSc2PsUtT3N937lzAXo64KO9A9q/kmDMg2IIyMEwx2EBsu7g0CnJJWZ+Rt5RU3d" +
            "I1+Dz2HT1ekX5L+KAgiCG59cBTdSEceNJQiW0in+FdQIt8bZIlmhX4t4xh97AeCTkjyI1bYFWxoVclKtgnsW8L0ZrS4w3oeEwd33" +
            "H1J7Bv11Xf7z3HyQ+pr73/Esu4Hp2N6rRFP/KAhUdsbJmnvJ8oUswfIVIxAYgvOOIxTcPfdhIhPgk0yYY4ktg83k+Lv4fdOjcWfX" +
            "rjQHfgDYaDfGEYSjxgjTATiFbNHpc38RvPN0rtQtQ4ib8YN4jxHQt37AqortcP++5cJ5qsYyWdq1X5EMjwK8AqKHW/sATyv57k8j" +
            "2i8B01POZiA3oA+Guy3lW8LiLqEWuJ//DoYcfz2CeQlEiDHEWgMrRDYi9hwV6MoWj/RS/u/3fwh0pAC633tCdLb8Srjq0QGtxIQB" +
            "esfVMSiCgPjjHOiFh7qj24ILg9sL4c2EdIAI521L9AcICnV8gIAd5mUVgFcI2ReAf4FKyUpwfwQ3LKR7IqMv66QRMYdcB4KDfLYD" +
            "xRKlA/cJOHQH4yFX64DzOUoJ48Of66H0jhBSffkK6AlNgJrnA4IKDe4zGn+xIzMMzugB+RZRQXji2lV6/Jb1GzUpCJi+2oQKDEx1" +
            "kwkLAu4DcfCx2YXsgAIN38YKVJgNQG0BNvQcGD+Cdq4u9PEO1TwW4O3SW5UYohrRfQY68bXm2t1n8O/zP/4t07fpRqkz7zVMDu7h" +
            "8mcIFeTf7NEGJyjiB7Q3H+zrxQEZ9REF89kAtDLf487w9fT/0ij2Qe0V1+Ct7fwxxHbo0glm7AjtGR/92wAZBx0C2X/A7EBz4iRk" +
            "I3p/f4F/gRN/p7HgJ4F9sH6BGghi+Memzn49f4Iolrxog4kLCoFCyPSCmBtLJoSCg1SF046Af2ebhH/16YVchRh+Kf7Z5aIokNB/" +
            "Og4mtcBJdWSJ9QYggvIjefY2xFn9RefsK6reGBsHr53p8OYNI59Q4/OH9L+iRqeCoVghYB2mRXKyQVrR0VkB9AMIHgUC4v8G+Qr8" +
            "LuXYFvrvLRj3F/LB5vTHGxk3tNEVIiEaEQ4GCRoZ5+fu");

    /**
     * The biases of the input layer.
     */
    static final byte[] INPUT_DENSE_BIAS = decode(
            "Jvp/f3/VgU5/BX97f3+AtIIcf33if6fs");

    /**
     * The input weights of the GRU layer, 24 inputs by 3 gates of 24 units, input-major.
     */
    static final byte[] HIDDEN_GRU_WEIGHTS = decode(
            "hBeF36H8CKwEZYl0/HtnzR2Ejs8fCUuAAM8lzi7rwZg2UiEVRn/3sdnpgWt6oNLu2Q3k0A44zDH/hxnu3MzH4jaE5tEKJwwCCYGA" +
            "ZhULwLlZj5E2H1552B4ok0n3bKQCgXR/f4Zff9uBHFkKGJjCvfImDrkW1xTOJz9Wf+5PBM0CIXWPsjilJSLT1OoV8DgerLEmtn8J" +
            "5wJSPRnmGgt1vwzGKsKjCwt8hVCDC6YqXgST/1XMLeblTfseWgBf+TUdrhb3SgL0t3JhwHqzK1tWfmpIWtUuYM0VFkQWKU9L0pcX" +
            "jH+FZjlVCuMifX58UfE2YIAnhGdKfn/OuYbAXbVHaXp7fnqBIcG2fLkhKcgTBkEpWoz90kvzYrbWSqGgURgg7YVKN21zACAhDOwJ" +
            "f3/DT9DKz2X3G5ZKd01Xguh/fB8ifygDpn8XOct/u6jffxPS94MNgo9/1y5qwgP2bzHe6OyQC2XO3jJBwJZG0DwJhtMPkOb8AScX" +
            "OtOwf1I6HqKJM6dflR5/fTrM1trshnMn5gVJDdkr6eyDFyM1w75I7CEIIwQHEhMQ0865H+PX5QoOGwnpYgaiXH+OO+acwoHvq8R+" +
            "1vohiOaCgd2O4RmCnILA0uEeGbaRn6+Yju33jLsWHjsIzRCfEvynUM4DJL04ReZr9jrk/Me4kQC1iQ61z77PCIcWynkeNuaChTgF" +
            "MBWB9RcZrgbnd04EmBs90CXzzDLOLP/q1cWyveDmCf0oEBMD9xT62xwnEe32AQbFSi8DiQCAlefqu+mR1qOIWqvKikyxfGWzte+5" +
            "jkQ3T/+F7H+/hYCpewmN8gf8f7GNfeRZrTFZd7v7DM88OeidkkytfUlRCwjTAVMNuv5hcJ8196J8LM/oNEySuo70SPyOK9VRZqzl" +
            "Ptg0OnzdzYXVOLXe3ZZd1Q7wLj6fFR7LFfXf7KEEggwtFGxVCxTYYwTn7un0gsns1M1bgX/UB39OJn36oplJfoISO9JqTHThS/xc" +
            "ZiDhSSrr5Dl/+JVzfKL8gB3HRq4y89QmQ6MG2dI4RBs9GhK4fxYS4X89v9oBvf8Ity6MojrPR9jBruzEXUxFliLhBOdr7i0Ew342" +
            "goMpEywgnn3ofaCDD1f8phLYHLtDFiknB9DUDEXzAizab/mC6vdKgNz5hfGxpduBhmgeB2Lbb4zRf9N2kYWIs8CDfE1vTRKPdfdD" +
            "s34x7IQnKYTecqmCYuw77+h9azYjIdQM43255MGOHO953H9Zhs/u0BEYE8CADVYtDc83VDBQ2WOBRt8eMn6/i/Ps6H9zuJg/ftY5" +
            "ES4Vd26cxJA+3xwa6sTfyk4ZII5WLBorTHkTYf79t7wGjAbVny6AiOGJ4xAQgoCC0vf9XOG0gv2V9Om7BTMb1he6gOMWHYLJMrn9" +
            "fyzlusG+umhWcx2kKaYs9eQU9cHwKx8Rt+H/7/XZOBJ8SPIcRYeDIn8/VrCCg4TRfE187Rf5zmCAo2bL3Kl3g1yCdmZI/n0KYXyD" +
            "fUfs0YyH/PfgT4TcIYC2fRd/443gfKcglSvvGBgSHfPx3D6lBNdfHOkGLlRCTUS6/+n6QUbrCU30AooECZRUNAI0DfY6khJCoelG" +
            "H/04OP35AeXQwyn8CsIg+egJ0MT8T+zatETPnwDxBZzPoZ2N99gKaA04f+WTooqa1Ks0f/wOPnmG5rHW3gEZ2rHG4eGm4oUgyH1C" +
            "fP8DW5n5F07uCUW7TNrf/p4SalQ3V9EjhEAp8i4Z/njrUhOx2/348BUT++SQJ/riNbs1Ln97ThQc+UlIEdgpbzkgoR0c2b827MEd" +
            "vQMsx9ELPerUPTCcFH1g6PADu4JKgwktvYXFuHZFLTLHQw2+li8+Fv/q59iDA30gZsjnteJ6PPMktwesfCiKEamK+APlb9gozX99" +
            "0+LKLlD/4mXvEho2B/QBgXuG5bVAChnx1H+BBayv+RPmfg90gg60LD6ShH3jqf27Ulo5hXtk7c3gRSXHgIS48zP507cFY+aLoJME" +
            "4fQAH9blDK92J1MOKYJrrl6MhtGTrIDdyEIIvxMq0riTKSuBjzp/KrX/QXXJj4V8K6CN7UQPXgNLACIJKm7QXLRj7xsgDX0y7zgE" +
            "NSL4Y1CC67/10iyv/Yd7Qq+sd39UaS2+1ukg5wxvf1h9HhiB98p/jIlYRl6II6MPFusZkoXTCJN9hqqCCPKI09NFg4YGUVZ9XzZN" +
            "NoV+q4s4CwDDpfT+j/3xhsGlClSRfV0VPrKMDccchH5uDA9fD+2DnzT5ZQkUg+bISE0MghbjLz5fcEUgYa34+0PBhU87APrvBJHM" +
            "G0EA");

    /**
     * The recurrent weights of the GRU layer, 24 states by 3 gates of 24 units, state-major.
     */
    static final byte[] HIDDEN_GRU_RECURRENT_WEIGHTS = decode(
            "QVMjOBje5P59Eyr3fMsYqQsjr92D4XvrIaVxoy36NSakCOVXBCsrCoCA0n/a0xmpEwU0oOnjeYLo7P5FzgZHr4NaogHaJFkRxEfQ" +
            "EvEs7jsLcs0gbgEEbeh/GzxYGC3FS9wIOeDnDX6nw7R/EsK8F48FfiuoGrISSxUJthQpforxCXR+gSL6foDLysmHRn/0vFLnaIJ+" +
            "6+Z8tYGIDT3AlMG/1N3D2W22cf1s4n14J32AoZ1vCRlytaTK9ODaCh8KPzMonUoEMoDc3fXkgvlCxoLqrcOBMX74Bz4k9eDUP3Qp" +
            "QYF+P+KgSqR/Ju6ARPtl/FU6TwDGCHe6/7G8cuSm+pACf/gKN8WCf31QSCPKX4SETxfSw4GcY7MIqQX+MVUHuVI11xbqo5kGNMgO" +
            "+JFVEDYgiug9y2C6+++9rPmulaAVrcYyDIL/5CKCcxFbAYFIC36vBmD4TQ/6P+UUhZNVse9+pALDFA4ReXseOXh/OSp1YkMn7Lpk" +
            "B316KBCxfVMplscYNxu+kdT51b55KoDTIw+BIt3e2O76P28fdH8TGLnZIgsT2BsMavY4rpb+zsxygt7VvApMOYqAJZhMfQO0f+NU" +
            "ovE3fU9/x4NovH5+szMtIZNz9QFfh/v3go4nRIKVzdYY+DPl1ULTPp6TRUMAg4AxH36GAsm9grqAg7MZEPiaC7VSJvsFEyIvgaMV" +
            "GJ/uHyci7BZ7B7Ov0vcBFyeB1fjOCus79/zz5Sx/NNFG1TRlzxstMSGDN3IU/0zooGkYfkvrlw3WKH7i2aF9wQsGfX3yBSrD/DFY" +
            "BpXkE+MvfgbSp+5b7Pp26+oncwvWNknJsz7lxZ30gdg4/YSlR5EG7VLo3WbWB4KCgxJizH9pzCitfoZtBX8wBgWDZPAdVacIBCk+" +
            "gT56VXqVCINdgX9mExO+KdZyf9CL4/q3mv3tAFgqV4vsAnocP0dCeF181TFnH1ql6oIa6OszgleZu/a+6RRhJBmBHuzBHjOMFyjZ" +
            "JK2z585uDg2Tfb/JqXyC4LiUf3+DhD15ZoCBEGR/hLxIo4Aro+2Dn4/fU3/Uf7V/ECwyhhd2LhMagAoEY/Ku8x59OUE8uSNiHAcB" +
            "K1lGS3nFUoLL8Iy/NMwAUCMtwy4Iaxvmilo59gfxANn8DB3/dFRPd33FHPrn1QJaT0NnrgL6fRNJAJdw72hrfGoTONQ3kAbZrX6j" +
            "njmI6doC4dBqf39FEG5HaD706irboiL/4PSE0fM8tb46gf5ATJZJz+F/fh8Qf5Jr8MsURfKDO9QPeH19KwYTxn9/KxBSYYF/o9dY" +
            "AE3xdBCE4f1f2ILKgq34xQZD4wR89nDk+FXrLVQG+AtIIFTCTQLcSx/OdH53qMny234olPr6OUDktB6Lox+k1MBeOkFyKS9HKuZj" +
            "gjn7Su2P/0PrfgH9ITyuJdBZctp/jiM6+xXSeYXVf3N7ept+f1E0WYFmKnX3/n1/bmB4QkZ8N1TaxneB8LF7EoHO2nirAQfIbLP+" +
            "FSUBDZe7HKkhmM1+KQOHHEc6Vvh/Xsl9KO1/36npB5G8CVSJN65O2+z36TXzD9J0foE4gn35/y0afXkdL6oeCkyD+Rdc9NnuXJ/4" +
            "q9cxznvbguIOT8+/CdzaoFXo8yXn+8CBN8Tuw8F/OEMPfEh4fyj2chjpLk7LfVZ8VgAmXRV/e0u4DTAhU80P4M/feEAHCUE8FevD" +
            "y49Un2UljuUpSX72Oz3xRvNS/EU4XqXOXLbQNfmVfxwe5uvDTVJApYN6mH97entMgn/6sAcovr82/hdgwEoCy/SFJzzsEO+fF/zL" +
            "hiDwyqErR/+93ykSSBytH5yl5QqAlgJM8yoicO0sKPf1QVzVgwIv4Bl64wxl+ILpKwd97IRS/g23lnMfdOnUuVQDL1t/f/FfB10F" +
            "cc42Cw2BEUgr6QW6FA/lY0WThqIQfwB0aC1s3ldI8nYuKm3mX11/PH+jyoYilzg3Z325zl+4f2sVSX49f38YwlpJWtKyhEh71jKV" +
            "EeDCp3wBUP51d7+BoYfMZ0JL/cKBf7Z8TzEoab25uit/d/xCKxdbgg8/iXBnD50fgUV00r0CguMeHrue0am6gRe3HvlezL9i02E1" +
            "F/fqzNEG/6vxw/JEbvaH593xooUbSzC+yNRdbUPcGEaCCIF+NAvgePPm5IN/as58JIL0AOlMuYL0764MfDkhBE3SR95IfYB86IBL" +
            "iEXTNyF/3wSX18WleyyBf700GYO/ZOd7BguFpN9+7/wdIX9gA1fQ7rp7OoH9zP/c138zzOUurTkJfn9eT9uB2EM0Ur5687d/+LAu" +
            "0ATK");

    /**
     * The biases of the GRU layer, 3 gates of 24 units.
     */
    static final byte[] HIDDEN_GRU_BIAS = decode(
            "fH3HgjV7f7VEZv50fH98fX578DB9fk5VC37i4sD9l+PvRT8C4PbCccxwk3AH2Ek1Pgb+AABk8BroOBr23ylGbeN/Ir4xNRs+");

    /**
     * The weights of the output layer, 24 inputs.
     */
    static final byte[] OUTPUT_DENSE_WEIGHTS = decode(
            "f39/f38Uf4KCyg59goJ/g4J/gYHH4n9Q");

    /**
     * The bias of the output layer.
     */
    static final byte[] OUTPUT_DENSE_BIAS = decode(
            "zg==");

    private RnnVadWeights() {
    }

    private static byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64);
    }
}

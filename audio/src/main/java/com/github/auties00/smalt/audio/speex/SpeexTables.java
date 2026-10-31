/* Ports libspeex 1.2.1 (libspeex/gain_table.c, gain_table_lbr.c, exc_*_table.c, hexc_table.c, hexc_10_32_table.c,
   lsp_tables_nb.c, high_lsp_tables.c). Copyright 2002-2008 Xiph.org Foundation, Jean-Marc Valin; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.speex;

import java.util.Base64;

/**
 * The codebooks of the Speex decoder: pitch gains, excitation shapes and line spectral pair vectors, as signed
 * bytes.
 */
final class SpeexTables {
    /**
     * The {@code gain_cdbk_nb} codebook, 512 entries.
     */
    static final byte[] GAIN_NB = decode(
            "4ODgAOS9+yHW+uASx/bKI/Ab1yoT7dgk0xjrKPjy7hwBDsY17qjZJ9oV7iXtFNUmChHQNszG8yHU//Ug9PXeFg4A0i7b3d4F5yzi" +
            "Kwb8wTHhK9cr6R7VKdUa8izfAfMb8xLbJdK30yLcGOci3PXsE+cM7iHcu8Ui0wYILury6BL/DdQs2dDmD+Af2yLfD9If6B7cJdcf" +
            "6SnOFvwy6gLrHO8e3ij5xOQd2irkKtT1FSvwCNQi2cnVFfXdGin3AN4d+HmvcQfw6iHbIeEk5fncEd5Gx0Hb9dAV2BH/LN8G+iH3" +
            "AOwi60XfOeMh4SPJDP8x3xvqI87f0RHONjNe//vUI/wW2C3ZvucY3wHmFOjp5wz1FdMs59PtEdVp8FIF6wEp8AvfHvOd/DnbIfEs" +
            "5yXBNtwY4R/LyNoa1/wEJd8N4hgxNKJy++LxFwEm2DjpDNwd7yjRM9vX2QvPIgA67vn8IvAR5SMeBcJBBDC8TNUL9SbuE/Ep6cLZ" +
            "F9YK/inr8/MZ9w3RKunC6BjUPOs67v3MIOoW3CK1ORBa7QMKLeMX2iD7ws0mzSjuNdYN6CDeDuweyLXmJeYgDzvmEeMd+RzMNfTi" +
            "BR770PsjAgLVKBUQEEvn0+AK1RL2KgkA/zT/B+IkE9D8MOQZ4yDqAOEW4BH2JMDXwiTMDxA64urgBvkJ2iQ=");

    /**
     * The {@code gain_cdbk_lbr} codebook, 128 entries.
     */
    static final byte[] GAIN_LBR = decode(
            "4ODgAOHG8BbX6NUOyOrJHfMh1y/82fcd1w/0Jvjx9B8BAtQo6r7WG9oc6SbrDtsfABXONMu55SHb/+0Z7fvkFgZB1Erf0N8J2Dny" +
            "Ou8E0yDhJt8k6RzYJ9Ud9C7eDekc8A/lIvKu8SvhGeAd6wX7JtHBzSHSDAMv5O/jC/YO2CY=");

    /**
     * The {@code exc_5_256_table} codebook, 1280 entries.
     */
    static final byte[] EXC_5_256 = decode(
            "+NsF1QVJPScM/cPgAioe/RHlCSIU//sCF/nSGjXRFP7fp83AGwsP3vvIGff/4wEoQ+nwECETBw5VFvb29Pn/NFkdC+zb0vER6OQY" +
            "AgEAF5sXDv/p7gkF8yYB5OQEGzPmItgjLzYmyub6KucN4twSKfzfF+D5/DP9Ecw40ST+6yQKCN8fEwn72Ar36xMSsu77AObc0c3U" +
            "Eigb/h0x5gIgyh63NgP7JBY1Cv+sy+P7A9Q1zQQWR93/Ifvl+SQR6dkQ98nx7CfdBtnyEjDA7/EJJ1ElvCUv6/qYDQYJ/iMI6RIq" +
            "LRUh+88J+tXIJwLw51cB/fcR5/X3/woC8vIE//Yc6SjgGvcaBOXpAyrEATH9GwrM2P4SLekR1AP9EdI02NEZSx/PNR7i4Nwm+vHw" +
            "NuXQAybj4Ory/OnzINkJCNPzIvAxKCAfHBcXIC87vAg+LBny6L/wJEPn2usE3/4qBcEoCxrW6cNP4RfsCuA159wK5vsDALkF9tsB" +
            "6BXK7wHj5/HlIEQt8Nvu+wEAs0f6A+xHvR3dCuITBBARBQDyEwIcGjsDAhgnN87T7u8h3Q7/AQhX3eMA5Q35F/Ml2DLdDhP58jE2" +
            "+xb+4/jlJg0bMAzX6/EcB/Do7ewL7AkCDRfsCxvlR7sIAvoWDBAQCfD47wEZASjb30JeNQTq59fWGSPw8Tkf4+AVEMQtD/8HOebR" +
            "4wsIDxOX+DYbCu8G9P/2BAAX9h8NCwoMwBf9+O0QNBjYEAooBQkA8/nr+Pr56zsQyxLEC9EO7hnz6ATZEOQ2Gr0eG+zMFPQ3DBLw" +
            "J/L65jioyQwZGtsGSwDerzbiAfkx6fIVCsLGx9HeD/wish8Z9Qcy9irBDtz8OTc5NSrW/w8oJQ8Z9QYBH/76//nAIhwe/wMVAKj0" +
            "yBnkKAjk8gkMAvrvFjH65g4c7AT0MiMoDdrG4xEeFjwaytn0OuTBCuv49BrCBvb16vr5BAESAroLDgQNE+jeGEMRM+sNFzbiMAHz" +
            "UBrw/g38BuId6EnGHuUU/uspLR7l/fvu7M/93Qoq7b3L9QkN8d/N4g8HGeIEHOreNuMn0hQQIvwvSwHUyegH/wnWMvjcKUQA/Pbp" +
            "8c5AJPflDBna0dsgzzPcAvxF5hMHLUMuDcEuD9EE1w36BeslGsn5If/kCu/A8gDc7139974s6wP0Jvrz9BMNK9X29Ab7Cc8g+wIE" +
            "BQ/wCusIwvhACE//vs/uBSj74tMB+hXgXe7i6yAV7hYIBdfKUBb2+fjpwEI48uLX0vLj2xvyKv734yIOIfIWBAoaGhwgF7jgAwDy" +
            "I9ay4AYd7tP7B9/T/ereCPgEzef3O7IV++fQQvHv6M/zGenA+ijo7fU53/gBCszKHCcxIvXD19UKD/EzHg/NIN7+3g4SEAEB/f0B" +
            "Ae4GEDAM+9YHJDAH7PYHDAI2J9olNgT1+NL2BfbeLvQd2yck9Rg4EQ4UGQDn5Df5+xsDCeb4Buj24uHeEgQWFSj/49v461zjC/0L" +
            "SRcWBwTU9/UV8wsJsv8vcvTb7fv16hMM4gcmLev49zfTOOsHES7HqfobHx8HyPQuFfv0JAMD6ysTDPkJ8gD336UHGgP1QFPh0hkC" +
            "CQUCAv8U7wr75fgUCO0Q6/PhBQUqGAki7BzDFgvZQOz/4vfsGOfo4xbEBvsp96kOIg/HNEUP/Zo6EAMGPLXgGgfH5eDo6+PwPtIf" +
            "HuXxBw8=");

    /**
     * The {@code exc_5_64_table} codebook, 320 entries.
     */
    static final byte[] EXC_5_64 = decode(
            "AQXxMb7Q/DLUByUQ7hnm5vETE+XRHDkF7+DXRBX+QDgI8PPm9/ALBtkZ7RbhFNM31QrwL9go7M0D7/Lx6DXs0i4bvCAD7vsJ4RD3" +
            "9v/pMF8vGdfg/Q/nySQp5RQFDQ7qBQLpEi7xEe7e+/gbyUkQAv/vKLIhAAITBDXw8fDk/fMxCPnjG/MgFCDDEA4pLCgYFAcEMMSz" +
            "EfrQQfEg4rn2/foK/vnjyEPiB/tW+vYABeE8Itr9GAr+HhcY1wxG1Q/vBg0Q8wge8fgFF96e/PMN0OFGDB8ZGOga+SHwCAX18vi/" +
            "DQr+9wD9vAUjBwDh/+/39xDb7v9F0OQW6/UFMTcXqtwQAg0/zR71DRju+g7tASkJ+xvc1N7b6+Yf2Q8rBfgdFPjszOT/DRre9vcb" +
            "+AgbvgQM6jEKsyDuA9oM/f8CAgA=");

    /**
     * The {@code exc_8_128_table} codebook, 1024 entries.
     */
    static final byte[] EXC_8_128 = decode(
            "8gkN4AL2H/b4+Ab8/wrAFwYUDQYI6hAiByrP5AUaBPEpIikgIRgXDggoIgTo1+3xDfMhyhgb1CEb8fEY7Q7cDvcY9Pwl+xDeBQoh" +
            "8crwDBkMAQIAA//8/AsCyDYb7A360tff9fsHDA7y+wgUBgME+PvWCwjyGf4CDQvqJ/cJBdP3B/cM+SLvmgcC1hIj994L+/4DFi7M" +
            "5/eiCAv7+/sE+d35NgXgAxj36ghBJf/06fr35DffDv0CEsQp7wjwEfUA9R3kJQnLIfL3B+f59Rrg+BjrFu0T9h3yAAAAAAAAAAD7" +
            "zAopBuL8ECAW5eog/eT9A90GERcVCAIE0+8OF/zh9f0OARP1Aj34CfQH9gz96GPQFzLb++kACPIjwPsu5w3/z+3xCSIyGQv69/Ds" +
            "4N/g5Qr4DPE48uAhA/cBQff39v766QkRA+QN4AT+9gTwTAzMBg0h+gTy9/0B8fAcAfELEAkE69vY+hYM8eny7/D39vcN2SkF9xDa" +
            "GS7RBDHyEf4GEgX63+osMv4BA/oHB/3rJu4i8tc88wYQ6CMT89wYA+/y9iQs1OP9A8r4DDcaBP77AvUW6QIWAefZQs8V+P4K8sQZ" +
            "Bgob5xAF/vca8+w6/gc09wIF/PEX/9oXCBv6AOX5J/byGgvT9An7IgTdCivq9Tj5FAEKAeYJXgvl8vMB9QAO+/r2/PH41xX7AeT4" +
            "Fvch6fz89CcE+QPEUAjvAvoM+wEJDxsfHhsXPS8aCvv49PMF7hnx/PH1DP7+8P76GAwL/AkB9w7TOQwU3RoLwCD29ir89/AgGAcK" +
            "NPXHHQAIAPoR78jYBxQSDPoQBQf/CQEKHQwQDf4XBwn9/PsSwA035wn3GA7nD/XY4iUB7Rb74Q3+AAf8EL0MQtwY+BLx6RMA0/kE" +
            "A/MNIwUNIQobFwD59Su2JPQCBfgG3wvw8vv5/RHeG/AL9w8h4QjwB/r5P8nvC/8U0iLiBgkTHPcF6Pjp/h/t8Pvx7gAaEiX78f4R" +
            "BeUV3ywM5fcRCxnr4fkNIfjn+Qf2BPr3MK7p+AYL6QP9MeMZHwQOEAn87grmAwXU9wnRyQ8JHAEE/S4G+trj4fH6AwAO+gjKziH7" +
            "AfIh0Br8+/37/fvk6k03/wIKCvfyvs8L3PrsCvYQDAT/8C3Uzh/+GSoX4OoACxTY3djc4Obr8zTqBujsEfv4JOf1FeYGIvgHFP0F" +
            "5/gS+/f8AfcUFCcw6AkFvxYdBAPV9SD6CRPl9tHyGAr53Pn//Pv7EDUZ5uP89C3G3iH7Av8b0B/xFvsEBwfn/QvqEPQI/Qf1LQ63" +
            "7TjSGOwc9P7/3P3fE/oHAvEF4dMIIw0UAPcw89X98wL7SLzlAgH++QUkIdj0/PsXEw==");

    /**
     * The {@code exc_10_32_table} codebook, 320 entries.
     */
    static final byte[] EXC_10_32 = decode(
            "BxERGxkWDAT9ABzcJ+jxA/cP+wof5Asf6wn19f755w7qHwTyE/QO+wT5BPsJAP4q0fABCAAJF8cAHPUG4TfTA/sEAv4E+f0G/gf9" +
            "DAUINvYI+fjo5+Xy+wgFLBcF9/X18/f0+OP46gbxA/T/+/0i/x3wEfwMAgEE/vwC/wv9zBwe9+AZLOzoBAb/AAAAAAAAAAAAAAAA" +
            "5/YWHQ3z6vP8APzwCg/c6BwZ//1C3/XxBgADBP4FGOzRHRP+/P8A//4DAQj1BQXHHBwA8AT8DPr/Auw99xjq1h0GEQgEAr8PCAoF" +
            "BgUDAv79BfcE+xcNF/3BA/v8+gD9F9zSCQUFCAQJ+wH9CgH6CvUY0R8W9A72Bgv5+QfhM/T6BwbvCfXsNO0D+vr4+xfXJQHrCvII" +
            "BwXx8Rcn5t8HAuDi6/gEDBEPDgs=");

    /**
     * The {@code exc_10_16_table} codebook, 160 entries.
     */
    static final byte[] EXC_10_16 = decode(
            "FicOLAsj/hf8Bi7kDeXpDAQU+wkl7ukXAAn67AT/7/v8EQABCf4BAgL0COcnDwkQyfUJCwUK/sQIDfoL8BvR9AsBEPkJ/eMJ8hnt" +
            "IiQMKPb96PLb693+3AP6QxwG7/308PHv+cXc8wEHAQIKAgsNCgj+BwMFBAIC/fgE+wYH1g8j/tImHOz3AQf9AP4AAAAAAAAAAAAA" +
            "8eQ0IAX77+z2/w==");

    /**
     * The {@code exc_20_32_table} codebook, 640 entries.
     */
    static final byte[] EXC_20_32 = decode(
            "DCAZLiQhCQ79BgH4APb7+fn5+/sf5Rjg/Ar1Ff0TF/cWGPb/9vP59SrfHxP4APbwAevvCvgOCAQL/gX+3wvwIQv8CfwLAgb7CPsL" +
            "/Poa3PAABP74DAb/ItLqCQkVCQW++xoCCg0CEwkMrwMNDQDyFt0G+fwG+gr64SbfAPb1BfQM7wUA+g33CggZIQL0CPoK/hUHESsF" +
            "C/n37Nzs6fz8/Rv398/Z2vX3BgUXGQUDAwQBAv3/VycR6/ft9/Hz8u/19vX4+v/9/f/K3uX49fz7AAAECAYJBwkHBgUFBTAKE/YM" +
            "/wn9AgX9Av7+AP7mBgn58PcCBwf71QsW9fciJfHz+gH/AQHAODT15QUEAwECAQP//Pz2+fz8Av/5+fT28ff7+/Xw8wYQBPPw9vwC" +
            "0fMZLxPy7PjvAP3zAQbv8g8BCgboAPYTu/gOMRH7IeMD/AAC+AX6AnjI9NEX9wb7AQL7AfYE//8E/wD9Hsy9HhYL//wDAAcCAAH2" +
            "/PjzBQEB/wUN9/32whYw/PoCAwUBAQQBDQPsCvcN/vwJ7Cz/FOC9EwAcCwgC9Q/tyx8CIgoG/MYICg0OAQwCAACAJfgs9xr9EgIG" +
            "C/8JAQUDAAEBAgwD/v0HGQkS+tsD+PAD9vkR3tQLEfH98P/zC9K//ggNAgQEBQ8FCQYAAAAAAAAAAAAAAAAAAAAAAAAAAPcT9Azk" +
            "Jh3/DAIFF/YDBPEV/AMDBhH3/PjsGgX2BgHtEvH0L/r++ff/7/7+8h7yAvn8//QL5xD99Av5B+8BE+Qf+fYH9gMMBfAGGCnjygAB" +
            "B/8F+g0K/PgI9+XL2v8KExEQDAwAA/n8DQzh8gb7AwURKzIZCgH6/g==");

    /**
     * The {@code hexc_table} codebook, 1024 entries.
     */
    static final byte[] HEXC = decode(
            "6BXsBfv5DvYC5RDsAOAaEwj11x8c5eAiKiLvFvYN4xL05ugLFgX7+za81TnnGAQEGvj07zYe0wEK8RLXC0S9JfDo8CbqBuMeQuUF" +
            "B/ANAvT5/ewkBOQJAyAwGicDAAfr8wWu+UnsIvf7Af8K+/b/CQH3CgDyC//+/wsUYK/q9PfGCRjiGt0b9A3uOMUP+Rfx/wbnDurs" +
            "L/UQAibp7eL3KPUFBPoIGuv1fwQBBvcC+f79B/sK7QeWW/0J/BX4GrAIAf727+/lIEcG4wvpNtod6idX4fTsA/7+AhQA/90bCfr0" +
            "A/T6DQEO6sXx7+cN+QcDAAH5Bv092+np4ybhGwH4AuUX5iTeBRjo+gcDxU7CLPABBgARCC0AkgYO/iCzyD79A/ME8Gbx3P8JjwYX" +
            "AAkJBfj/8gX0ecvl+PcW8wMC/QH+uV8m7Q/w+0cKAuDz+w///vKrHh0GAwIAAAAAAAAAAAK/yPcSEhfy/gAM4xr0AQL0wFr6BAEF" +
            "+5L94RbjCQAI2PsV+/sNCu4oASPsHuQL+hMHDhLACfoQM0QIEAz4APcU6hkH/PMp3V3uygv/AfcEvkLhFOoZ6QsKCRMPC/vh9unk" +
            "+vr9/AUD5Bb11hnn8CkiL/oCKu3qBdkgBt0WEeII5vX1A/QhIdsV/wb8AwD7BQz0ORvD/RTvAgAEAP7fxlHpJ/b7Agb5BQT9/vPp" +
            "uGsP+wD5/foF/A8vDOEZ8AgW58LI7g4cDAL1Sr4p7PkQ7BD4APAE7VwMxfLZMefwF+UT/d8TVeMG+fYQ+fQB+gIE/kAK5yn+4Q8A" +
            "bjJFIxwT9gLVz8jx8AoDDP/4ARr0/wf15SkZAfXuFvn/0fgX/e/5EoM7+wMSAQIDG91ByzLSJevkBw7b+/sMBfhO7RX68Aj5BQIH" +
            "Agr6DMQsC9zgHwAC/gIB/Qf2EesKBv4T/jvaqiYI1+LT3wcPHB35GNgHBwX+CRjp7gbjHgIcMfXSCivz9//9+fnv+mHf6wMFAQzV" +
            "+BwH1fkR7BP/AvMJNiIJ5PX3727FLOYAA/TRSd7VJt8Q+9L8+v7nE+Mc8wUOG9jVBCDz/t38cNYJ9CXkEQ7tI9kXA/L/x/te9wPZ" +
            "BR724Crz8p/BHvcB+QwFFBH33OIZL/fxDOpi+M4P5RXw9QIM9gr9ISSgAO8f9wkD7A31CPwK9gkBcLrlBesCx/3jChPrFfa+/Vvd" +
            "HvQA+TvkGgIO7gEBCxEUysUbBB0gBRMM/AEH9gX+CgAX+xyYLgsQAx0B+PIBB85YwhoI7/IyACD0/eUS+PsIA+z1JfQJIS6b//wB" +
            "Bv8c1vEQBf/+yVUm9/wL/vf6A+z2s1kY/ZjH5uHs+vcOFOku8eEcAfH+Bv4fLbQX5w==");

    /**
     * The {@code hexc_10_32_table} codebook, 320 entries.
     */
    static final byte[] HEXC_10_32 = decode(
            "/f7/APwFI9j3DdQF5f/5BvUH+AcT8g/8CfYK+Ar3/wEAAAIF7hbLMgHpMtwPA/MO9gYBBf0E/gXgGQX+//wBC+Ma+vEe7gAP7yjX" +
            "Awn+/gP9//sCFfrw6xcCPA8Q8PcOCf8H9wABAQD/+hHkNtP/Af/6+gILGuP+LusiDOkg6RD2A0IT7BgHC/0A/f/O0gLu/QT//gP9" +
            "7SncCQvoFfAJ/ef9ChL3/vv/+wb8/QLmFe0j8QfzEe0n1TDhEPcH/vsD/AntG8k/3Qoa1P4JBAH6CPcF+P/98C3WBQ/wCgAAAAAA" +
            "AAAAAADwGMkv2hvtB/0BEBsU7RIF+QH7AvoI6gD9/Qj/B/gB/QUAEdA6zB35/gP2BuY64QH6A13jJwMRBQb///8bDQoT+d4MCvwJ" +
            "tAkI5P71Av8DAa0m2QTw+v77Bf4=");

    /**
     * The {@code cdbk_nb} codebook, 640 entries.
     */
    static final byte[] LSP_NB = decode(
            "HhMmIiggLis6KwXu59jfycwUIhzswZ+kPTUvMTVL8suzsQD9+xMWGvfLyUJaSFVESjT818bh7uEbIB4SGAMIBfT9GhxKP/7ZvbOW" +
            "tjs7SUEsKEdIUlNiWFk8+uHR0PPZ9wcCT//ZxO9XUUEyLRPrvaWp184HEidKCuHkJxgNFwU4LR0K+/P13e749vjnubPrAhAyP1dX" +
            "BeDYzbwADAY2IgX0IDREQEU7QS0O8OHYv70pMS8l9cy1rPw5MCoqIfXNvPoNAAj4GiDpywAkOExhaW9h/+TZ2NXK1NjuIxDs7eTW" +
            "HS8mSi0D49DCsJjfODs7ChEuSFRldXt7avnfz826veXhRkPwwqvsUkdWUFVK7ca10+Pf7uctOfTW+wwcJDRAUVIN9+XkFgMCFhoG" +
            "+tTNAg8KMCsxIu3CrKea6AgRPUQnGBcTEPsMDxsP+NTPxO7g5DQ2PvjQs7pCZVM/PSX0zrXAIRENGQ9NAdbjSEAuMR89LPjRytLi" +
            "ExT/8AAQ9O735uX26jUt9tG1rpeTCBkxTTJBcnV8dnNgWj3308HEtccICxQdAN3P1SgvIyg3Jui0mZDlAxciNEsI49UMPyYjHRgI" +
            "GQsB8e7V+SUoFezI7e38/gsdMz/+1MK1px45M0ozMi5EQEE0PzdBKxL35t3JuwMGCBHxw6qfAVZdSk5D/9q+0DAnHRkR/w0NHScy" +
            "M0VSYWL+3NLl8OLz/Pn8Gfv1+ufrIQwfHfjazMG8p9//Ckr+8TtbaWllV1Q++d/O3crRGRFSUfPIrRU6HyoZSEHovqXICf4VCkVL" +
            "AugLFhkcJiIwIQfj5hEP/w4A/gD6170G/vcTAlVK6r2suc4DC/cCPg==");

    /**
     * The {@code cdbk_nb_low1} codebook, 320 entries.
     */
    static final byte[] LSP_NB_LOW1 = decode(
            "3szxLQIXFTQY3/f/CdTX8+8sFu/6/P8WJhoQAjIb3d731wYA8N4zCPLhzw/fLTEh9dvCyi0L+7gL//T1GBv11S4rIfT3/wH86ce5" +
            "CwgQEfjs4dc1MPADQej46eDb4M/27wYmBffv0gg0AwYtKCf5+t62HwgB8CtE9e3hBAYA+u/w2vDiAgnZ8P8r9jADA/Dh/T5EKw0D" +
            "9ggUyAwM/u4W8djcAQcpAAEu+sL89P71rfP+WyH2AAT18E8gJQ4JM+vkyN4AFQnmCxzWyun+8R8eCNm+2dwf5NjSIygWGCEwF94O" +
            "KCARG/0ZGvPD7wsEHzz65tfADRDmNh/16ff13rnr3t03Mh3q5c7aOSEqOTAaCwDP4Rr88gVOJREAz/TpGg4CAtXv9Ar4/AgSDPoU" +
            "9Prz5yIPKDEHCA0UFO3q/vgCM80=");

    /**
     * The {@code cdbk_nb_low2} codebook, 320 entries.
     */
    static final byte[] LSP_NB_LOW2 = decode(
            "+jXr6AQaEfzbGRHc8x8D+hsP9h8cGvb22BD5Dw0p9wD8Mvr5DiYWANACAfPtIP3EC+//6N7/I/vlHCwNGQ8q9Q8zI9wUCPz04xPR" +
            "MfH8EOPZDuIEGff7zfL92OAmBff4/P/qR/0OGu7qGNfn6AYXE/Yn5uVBLQL55vgW9BAPEN37Iev4ABchIgYVJAb56gjb8h8mC/z9" +
            "2eD4IOn69BAU5PwXDcz/Fgbf2PoEwg0F5iMnCwI59Qns5N80+/r+FvLw0CMBxhQNIf+2OO7q4QwG8gT+99EK/R3v+z0OL/QCSNnv" +
            "XEDLzfHi2tfj5BsJJAnd1lHrFBnw++/dFQ/kMAL+Ce0d2B7u7hLwxw/s9PHb8SHZFerzIwsN2sEdF+UgEgPmKiHAvu8QOAIkAx8V" +
            "19kIxw4l/hPc7enj8AH9+PYfQL8=");

    /**
     * The {@code cdbk_nb_high1} codebook, 320 entries.
     */
    static final byte[] LSP_NB_HIGH1 = decode(
            "5vgdFQQT2SH53Dg2MCgd/OjWvtXEE/4lKfbbxMAS6k1JKBkEE+2+/gsFFQ4a56r8EgEa2wol/xj0xfUU+iLw8CoT5M01IAQKPhX0" +
            "3hsE0NDOzx/569bn/NXqOwIbDPf68PjgxvDj+ykX4t/S8/baNDQB7/cKGuf6Iew1Nxng+9YXFUIF5BQJSx351tkPA+kVBgsB4w4/" +
            "CjYa6M3PB+nND74BPBkKAOL88RETOygE+yEG6sa6+xf6PCzj8NHjNO0yHBAjHyQA6wYVGxYqB77Y+AcTLgD8PCQt+eP64NkCBvch" +
            "FM3eEvoTBgsF7eP+KvXT68k5JQLyvfDl2kUwEwLvFOzw3u/nwwpJLRDYwO/j6jgR2Qj1COfu8+0INjkk7+b8BusoKvwUHzUK3ssf" +
            "7yMAD/rswbcWGR0RCOPZuxIP8fs=");

    /**
     * The {@code cdbk_nb_high2} codebook, 320 entries.
     */
    static final byte[] LSP_NB_HIGH2 = decode(
            "Cy8Q99LgGsAi+yb5LxQCt5390xRGzA/6+a4fFS8zJ/0JANf58coCABvhCdPq2ujoCN8XBTLc7+7N/g0TKwzx9D0mJgcNAAb/Az4J" +
            "GxbfJt33HtX34P8E/AH79fgmHwv21uvbASsP893t7g8X5jsB6zUI187y5AQVGeTYBdjXBDPf+PgBEcQMGdcRIisTLQfbGPE4/iP2" +
            "MATR/gX7ygX93/Ye/tTo2gn3KgQGyCzwCdjmEuwKHNfr/A3uIOL9JQ8WHDLYA+PABzPt9RHl2MAY9PnlAyUw/wL32t4uARv6E/Ma" +
            "CiIUGSgy+vkeCegA6UfDFjre/ALP3xke+PrwTQIm+N364jhOHyHsDdkUFgQV+AT6Cq3XCefVD/n03tnb3xMeEN8q5xm8LPH1/Bcy" +
            "DgTZ1RTiPAnsBxAT3yUdEN0HJuU=");

    /**
     * The {@code high_lsp_cdbk} codebook, 512 entries.
     */
    static final byte[] LSP_HIGH = decode(
            "Jwzy7OPDvbTgub1ETS4iBfPQ0rivrMTG2ORSXUQtHQPt0eTV3eL489mlpYWgCgr67snEpcjc5fDQtSgc9uQjCSUTAezh1+7n3byw" +
            "LRv/Lw0A493HzrG32u0FIw726RD4BejYwunl6vDu0rizKxUhAbC6usDIzNnf4drt7fEgIf4H8fHo6d/XyOjHBVlAKRsF99HEn5+E" +
            "7PfUtx8d/EAwB93HAP3m0f362LSx0AxRNwoJ6NW3x7sQBeTLEh0UAPz1BvMXB+/d29vivMEGGPfyAxXz5cfPsOjX+/D7AS0ZDPkD" +
            "8frw8fgG89avsKkOAfb91bvS6OTjJAbVyPQMNk8rCTYWAgj01dLM2run+0smIQXzy8Kpp4+dyd7bPjchEBX+79Lj2trQ2dbctbio" +
            "0OIVAvHHwJ6stBkB0rD0EvkDIgYmHxcE/xQO8dWypegO/TYQAOXk1MitpKf9IjgpJBYU+Pnd1sLPAwz2zqmgvlxGJgm6ucLW2dX1" +
            "+c6xxs7hIB/6/OcH79q6xuXVreQ7JBQfAuW5sJOetd/g4f4hD/orIfsA6vbl3s/17NelnIfZOSkK7c7axcS67uz44fjxAfLm5yEV" +
            "IBEB7e3mxq/d6i0eC/UD5tCpva3GA//m7CwKGScF993l2gcKBPfWq5qBNCwcCtHD2Nnv//bf1rbQFfxGNAo=");

    /**
     * The {@code high_lsp_cdbk2} codebook, 512 entries.
     */
    static final byte[] LSP_HIGH2 = decode(
            "3MIG9/byyBcB5hfQ7wwI+Rcd3OT64+/7KBcKCtLzJAYE4uM+IOD/FvIB/OrTAjYE4sfF9Bv94Qj3BQryIEITCQLn2xfxEtrhBffr" +
            "DwAWPh4P9PLSTRUhAyId7TICCwna9Ns+AfE2IAYC6BQj6wITGPM3BAkn7R7/60k2IQgSAw8G7dEG/dDOARoUCOnOQfLJ7+Hb5DX/" +
            "78sBOQv45+LbQAXM0w8XHw8O5xgh/tTI7gbr1QT0EdsU9iIPAg83FfXh+i4ZEPfn+MIcERTg4xoeGe0C8O8azQIyKhO+Fx3+AxPt" +
            "2yAPBh7eDQv7KB8K1gT3Gve6Ef7pFOrJM+jhFuoP8wP25PA4BMEL7vHu2t0Q+SL/68/RCdsHCEU3FAbf0/b3BvcMRw/91vnoIN3+" +
            "1u/7AP7fyg303i8XEzcH+EofDhDp5hMM7s/k4ewC8uzRTigN6fUV+hIBLwUmIyAuFggNEPISMxMoJwvm/+8vAsvxH+omFfHwBd81" +
            "D9pWC/3oMQ389e4cFPTl5iPn3f3swx4KyfTqzMryE+D0LQ/40PcL4Ajw3vMzEib+4O8W/u7kujsb5O327Pf3+OsV+CP+Lf33DAAe" +
            "B9krG9qlHhoTyfw/Du8NCQ0CBwQGPUj/7x3/6u8I5Ns/LCkDAg4J+kv4+fTx9A0J/B7qvw8A0wT8AQUWCxc=");

    private SpeexTables() {
    }

    private static byte[] decode(String data) {
        return Base64.getDecoder().decode(data);
    }
}

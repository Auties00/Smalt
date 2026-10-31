"""Regenerates the libopus 1.5.2 DNN models and tables that Smalt's Opus deep PLC, DRED and OSCE port use.

Usage: python -I tools/codegen/opus-dnn/regen.py <work directory>

Downloads opus-1.5.2.tar.gz (pinned by SHA-256) into <work directory>/download and unpacks it there, builds
dump.c against it with gcc and runs it, then writes:

- audio/src/main/resources/com/github/auties00/smalt/audio/opus/internal/dnn/<model>.bin.gz: the weight blob
  of each model (pitchdnn, fargan, plc, rdovae-enc, rdovae-dec, lace, nolace), gzipped with mtime 0;
- one Java class per model in the dnn package (PitchDnnModel, FarganModel, PlcModel, RdovaeEncModel,
  RdovaeDecModel, LaceModel, NolaceModel): the constants of the model header and one field per layer,
  initialized from the blob as the model's init_* function does;
- LpcnetTables.java (dnn/lpcnet_tables.c) and DredStats.java (dnn/dred_rdovae_stats_data.c).

The outputs depend only on the tarball, so a rerun must leave the committed files unchanged.
"""
import gzip
import hashlib
import os
import re
import subprocess
import sys
import tarfile
import urllib.request

URL = "https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz"
SHA256 = "65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
PACKAGE = "com.github.auties00.smalt.audio.opus.internal.dnn"
JAVA = os.path.join(ROOT, "audio", "src", "main", "java", *PACKAGE.split("."))
RESOURCES = os.path.join(ROOT, "audio", "src", "main", "resources", *PACKAGE.split("."))
HEADER = ("/* Generated from tools/codegen/opus-dnn; edit the generator.\n"
          "   Ports libopus 1.5.2 (%s).\n"
          "   Copyright Amazon, Mozilla and Xiph.Org Foundation; BSD 3-Clause License.\n"
          "   Modified for Smalt. */\n")

# blob file, data file, init function, Java class, struct name, summary sentence
MODELS = [
    ("pitchdnn", "pitchdnn_data", "init_pitchdnn", "PitchDnnModel", "PitchDNN",
     "The layers of the neural pitch estimator"),
    ("fargan", "fargan_data", "init_fargan", "FarganModel", "FARGAN",
     "The layers of the FARGAN vocoder"),
    ("plc", "plc_data", "init_plcmodel", "PlcModel", "PLCModel",
     "The layers of the deep PLC feature predictor"),
    ("rdovae-enc", "dred_rdovae_enc_data", "init_rdovaeenc", "RdovaeEncModel", "RDOVAEEnc",
     "The layers of the DRED encoder"),
    ("rdovae-dec", "dred_rdovae_dec_data", "init_rdovaedec", "RdovaeDecModel", "RDOVAEDec",
     "The layers of the DRED decoder"),
    ("lace", "lace_data", "init_lacelayers", "LaceModel", "LACELayers",
     "The layers of the LACE speech enhancer"),
    ("nolace", "nolace_data", "init_nolacelayers", "NolaceModel", "NOLACELayers",
     "The layers of the NoLACE speech enhancer"),
]


def fetch(work):
    download = os.path.join(work, "download")
    os.makedirs(download, exist_ok=True)
    tarball = os.path.join(download, "opus-1.5.2.tar.gz")
    if not os.path.exists(tarball):
        urllib.request.urlretrieve(URL, tarball)
    digest = hashlib.sha256(open(tarball, "rb").read()).hexdigest()
    if digest != SHA256:
        raise SystemExit("opus-1.5.2.tar.gz has SHA-256 " + digest + ", expected " + SHA256)
    src = os.path.join(download, "opus-1.5.2")
    if not os.path.isdir(src):
        with tarfile.open(tarball) as archive:
            archive.extractall(download, filter="data")
    return src


def build_blobs(src, work):
    out = os.path.join(work, "blobs")
    os.makedirs(out, exist_ok=True)
    exe = os.path.join(work, "dump.exe" if os.name == "nt" else "dump")
    dnn = os.path.join(src, "dnn")
    subprocess.check_call(["gcc", "-O1", "-w", "-DOPUS_BUILD", "-I" + dnn, "-I" + os.path.join(src, "include"),
                           "-I" + os.path.join(src, "celt"), "-I" + src, os.path.join(HERE, "dump.c"),
                           os.path.join(dnn, "parse_lpcnet_weights.c"), "-o", exe])
    subprocess.check_call([exe, out])
    os.makedirs(RESOURCES, exist_ok=True)
    for blob, *_ in MODELS:
        data = open(os.path.join(out, blob + ".bin"), "rb").read()
        with open(os.path.join(RESOURCES, blob + ".bin.gz"), "wb") as f:
            with gzip.GzipFile(filename="", mode="wb", fileobj=f, compresslevel=9, mtime=0) as z:
                z.write(data)


def evaluate(expr):
    expr = expr.strip()
    if re.fullmatch(r"[-+]?[0-9]*\.[0-9]+(?:[eE][-+]?[0-9]+)?f", expr):
        return "float", expr
    value = eval(expr, {"__builtins__": {}})
    if not isinstance(value, int):
        raise ValueError("unsupported constant " + expr)
    return "int", str(value)


def constants(header):
    out = []
    for name, expr in re.findall(r"^#define\s+(\w+)\s+(.+?)\s*$", header, re.M):
        if name.endswith("_H") or name.endswith("_H_"):
            continue
        kind, value = evaluate(expr)
        out.append((kind, name, value))
    return out


def string_or_null(token):
    token = token.strip()
    return "null" if token == "NULL" else token


def layers(data, init, struct):
    body = re.search(r"^int " + init + r"\(" + struct + r" \*model,const WeightArray \*arrays\) \{\n(.*?)^return 0;",
                     data, re.S | re.M).group(1)
    out = []
    for kind, field, args in re.findall(r"if \((linear|conv2d)_init\(&model->(\w+),arrays,(.*?)\)\) return 1;", body):
        out.append((kind, field, [string_or_null(a) for a in args.split(",")]))
    return out


def wrap(start, args, end):
    out = []
    line = start
    for i, arg in enumerate(args):
        piece = " " + arg + ("," if i < len(args) - 1 else end)
        if len(line) + len(piece) > 114:
            out.append(line + "\n")
            line = "                " + piece.lstrip()
        else:
            line += piece
    out.append(line + "\n")
    return "".join(out)


def write_model(src, blob, data_name, init, cls, struct, summary):
    data = open(os.path.join(src, "dnn", data_name + ".c"), encoding="utf-8").read()
    header = open(os.path.join(src, "dnn", data_name + ".h"), encoding="utf-8").read()
    lines = [HEADER % ("dnn/" + data_name + ".c, dnn/" + data_name + ".h"),
             "package " + PACKAGE + ";\n", "\n",
             "/**\n", " * " + summary + " ({@code " + struct + "}), read from {@code " + blob + ".bin.gz}.\n",
             " *\n", " * <p>Instances are immutable and thread-safe.\n", " */\n",
             "final class " + cls + " {\n"]
    for kind, name, value in constants(header):
        lines.append("    static final " + kind + " " + name + " = " + value + ";\n\n")
    fields = layers(data, init, struct)
    for kind, field, _ in fields:
        lines.append("    final " + ("LinearLayer" if kind == "linear" else "Conv2dLayer") + " " + field + ";\n\n")
    lines.append("    /**\n     * Initializes the layers from a weight blob ({@code " + init + "}).\n     *\n"
                 "     * @param blob the parsed weight blob\n"
                 "     * @throws IllegalStateException if an array is missing or has the wrong size\n     */\n")
    lines.append("    " + cls + "(WeightBlob blob) {\n")
    for kind, field, args in fields:
        call = "        " + field + " = " + ("LinearLayer" if kind == "linear" else "Conv2dLayer") + ".of(blob,"
        lines.append(wrap(call, args, ");"))
    lines.append("    }\n\n")
    lines.append("    /**\n     * {@return the shared instance, loaded on first use}\n     */\n"
                 "    static " + cls + " get() {\n        return Holder.INSTANCE;\n    }\n\n"
                 "    /**\n     * Holds the shared instance.\n     */\n"
                 "    private static final class Holder {\n"
                 "        private static final " + cls + " INSTANCE = new " + cls + "(WeightBlob.load(\""
                 + blob + ".bin.gz\"));\n    }\n}\n")
    write(os.path.join(JAVA, cls + ".java"), "".join(lines))


def array(text, name):
    body = re.search(r"\b" + name + r"\[\w*\]\s*=\s*\{(.*?)\};", text, re.S).group(1)
    return re.findall(r"[-+]?(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][-+]?[0-9]+)?f?", body)


def java_floats(tokens):
    return [t if t.endswith("f") else "(float) " + t for t in tokens]


def java_array(kind, name, values, doc):
    lines = ["    /**\n     * " + doc + "\n     */\n", "    static final " + kind + "[] " + name + " = {\n"]
    for i in range(0, len(values), 6):
        lines.append("        " + ", ".join(values[i:i + 6]) + ",\n")
    lines.append("    };\n\n")
    return "".join(lines)


def write_tables(src):
    text = open(os.path.join(src, "dnn", "lpcnet_tables.c"), encoding="utf-8").read()
    kfft = re.search(r"const kiss_fft_state kfft = \{\n(\d+), /\* nfft \*/\n([-0-9.ef]+), /\* scale \*/\n"
                     r"(-?\d+), /\* shift \*/\n\{([^}]*)\}", text)
    factors = [f.strip() for f in kfft.group(4).split(",") if f.strip()]
    lines = [HEADER % "dnn/lpcnet_tables.c", "package " + PACKAGE + ";\n", "\n",
             "/**\n", " * The constant tables of the LPCNet feature analysis.\n", " */\n",
             "final class LpcnetTables {\n",
             "    /**\n     * The size of the analysis FFT.\n     */\n",
             "    static final int FFT_SIZE = " + kfft.group(1) + ";\n\n",
             "    /**\n     * The forward input scale of the analysis FFT, {@code 1 / FFT_SIZE}.\n     */\n",
             "    static final float FFT_SCALE = " + kfft.group(2) + ";\n\n",
             java_array("short", "FFT_FACTORS", factors,
                        "The radix and stage length pairs of the analysis FFT, zero padded."),
             java_array("short", "FFT_BITREV", array(text, "fft_bitrev"),
                        "The bit-reversal permutation of the analysis FFT."),
             java_array("float", "FFT_TWIDDLES", java_floats(array(text, "fft_twiddles")),
                        "The twiddles of the analysis FFT, interleaved real, imaginary."),
             java_array("float", "HALF_WINDOW", java_floats(array(text, "half_window")),
                        "The rising half of the analysis window."),
             java_array("float", "DCT_TABLE", java_floats(array(text, "dct_table")),
                        "The DCT-II basis, indexed {@code [band * NB_BANDS + coefficient]}."),
             "    private LpcnetTables() {\n    }\n}\n"]
    write(os.path.join(JAVA, "LpcnetTables.java"), "".join(lines))
    stats = open(os.path.join(src, "dnn", "dred_rdovae_stats_data.c"), encoding="utf-8").read()
    lines = [HEADER % "dnn/dred_rdovae_stats_data.c", "package " + PACKAGE + ";\n", "\n",
             "/**\n", " * The quantization statistics of the DRED latents and initial state, indexed\n",
             " * {@code [quantizer * dimension + component]}; every value is an unsigned Q8 number.\n", " */\n",
             "final class DredStats {\n"]
    for name, doc in (("dred_latent_quant_scales_q8", "The quantization scales of the latents."),
                      ("dred_latent_dead_zone_q8", "The dead zones of the latents."),
                      ("dred_latent_r_q8", "The Laplace decay parameters of the latents."),
                      ("dred_latent_p0_q8", "The probabilities of zero of the latents."),
                      ("dred_state_quant_scales_q8", "The quantization scales of the initial state."),
                      ("dred_state_dead_zone_q8", "The dead zones of the initial state."),
                      ("dred_state_r_q8", "The Laplace decay parameters of the initial state."),
                      ("dred_state_p0_q8", "The probabilities of zero of the initial state.")):
        lines.append(java_array("short", name.upper(), array(stats, name), doc))
    lines.append("    private DredStats() {\n    }\n}\n")
    write(os.path.join(JAVA, "DredStats.java"), "".join(lines))


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def main():
    work = os.path.abspath(sys.argv[1])
    src = fetch(work)
    build_blobs(src, work)
    for model in MODELS:
        write_model(src, *model)
    write_tables(src)


if __name__ == "__main__":
    main()

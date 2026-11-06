"""Builds the WebRTC APM oracle of the voice processor tests: webrtc-audio-processing 2.1 behind
the extern-C shim next to this script (smalt_apm_shim.h), compiled to wasm32-wasip1
(audio/src/test/resources/.../audio/voice/apm.wasm).

The module is the bit-exact reference VoiceProcessorOracleTest and MobileEchoCancellerOracleTest
run through Chicory. WebAssembly fixes the floating-point semantics the reference is compiled to:
IEEE single precision, no fused multiply-add contraction, no SIMD, musl's libm. The shim exports
smalt_apm_create, which takes every setting of the configurations the voice processor supports
(sample rate, channel layout, echo canceller, noise suppression, gain controller 1 with its
analog controller and clipping predictor, gain controller 2), the process and level calls, and
some of the APM's building blocks (resampler, band splitting filter, AGC2 voice activity detector,
the voice activity detector of the analog gain controller, legacy mobile echo canceller) and the
libm functions the ports of musl are checked against. The checked-in module was built with wasi-sdk
34 and -O2 from these sources; its SHA-256 is
f06df17d6540a447743b4d37a2174538e517bfa5caa79388915be21217e2fb8d.

Steps:

1. Download webrtc-audio-processing v2.1
   (https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing/-/archive/v2.1/webrtc-audio-processing-v2.1.tar.gz)
   and wasi-sdk 34 (https://github.com/WebAssembly/wasi-sdk/releases/tag/wasi-sdk-34).
2. Configure it natively with meson, only to resolve the sources, include paths and generated
   headers of every target (abseil-cpp comes from its wrap):
       meson setup build-native <apm-src> --buildtype=release --default-library=static
       meson introspect --targets build-native > targets.json
3. Run this script:
       python build_wasm.py --wasi-sdk <wasi-sdk-34 dir> --apm-src <apm-src> \\
           --targets targets.json --out out
   It recompiles every source of the APM, its base libraries and abseil with wasi-sdk's clang
   (skipping poison.cc and platform_thread.cc, which need threads), with two upstream files
   patched for wasm32 (see PATCHES), archives each target, compiles the shim next to this script
   and links a reactor module exporting the shim, malloc and free. An object file newer than its
   source is reused, so a rebuild after a shim change recompiles only the shim.
"""
import argparse
import concurrent.futures
import json
import os
import subprocess

# Upstream file -> (original line, wasm32 line); the patched copies shadow the originals.
PATCHES = {
    "rtc_base/system/arch.h": (
        "#elif defined(__EMSCRIPTEN__)",
        "#elif defined(__EMSCRIPTEN__) || defined(__wasm32__)",
    ),
    "rtc_base/platform_thread_types.cc": (
        "  return reinterpret_cast<PlatformThreadId>(pthread_self());",
        "  return static_cast<PlatformThreadId>(reinterpret_cast<uintptr_t>((void*)pthread_self()));",
    ),
}

TARGETS = {"libbase", "libapi", "system_wrappers", "common_audio", "libpffft", "librnnoise", "libfft",
           "webrtc-audio-processing-2"}

EXPORTS = ["smalt_apm_create", "smalt_apm_process_reverse", "smalt_apm_process", "smalt_apm_set_stream_delay_ms",
           "smalt_apm_set_stream_analog_level", "smalt_apm_recommended_stream_analog_level", "smalt_apm_destroy",
           "smalt_resampler_create", "smalt_resampler_resample", "smalt_resampler_destroy", "smalt_split_create",
           "smalt_split_analysis", "smalt_split_synthesis", "smalt_split_destroy", "smalt_vad_create",
           "smalt_vad_analyze", "smalt_vad_destroy", "smalt_aecm_create", "smalt_aecm_buffer_farend",
           "smalt_aecm_process", "smalt_aecm_destroy", "smalt_libm", "smalt_avad_create",
           "smalt_avad_process", "smalt_avad_destroy", "malloc", "free"]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--wasi-sdk", required=True)
    parser.add_argument("--apm-src", required=True)
    parser.add_argument("--targets", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--opt", default="O2")
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 2)
    args = parser.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    exe = ".exe" if os.name == "nt" else ""
    bin_dir = os.path.join(args.wasi_sdk, "bin")
    clang = os.path.join(bin_dir, "clang" + exe)
    clangxx = os.path.join(bin_dir, "clang++" + exe)
    webrtc = os.path.join(args.apm_src, "webrtc")
    out = os.path.abspath(args.out)
    overrides = os.path.join(out, "overrides")

    for relative, (original, patched) in PATCHES.items():
        text = open(os.path.join(webrtc, relative), encoding="utf-8").read()
        if original not in text:
            raise SystemExit("unexpected upstream content in " + relative)
        target = os.path.join(overrides, relative)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        open(target, "w", encoding="utf-8").write(text.replace(original, patched))

    base = ["--target=wasm32-wasip1", "-" + args.opt, "-DNDEBUG", "-DWEBRTC_POSIX", "-DWEBRTC_LIBRARY_IMPL",
            "-D_WASI_EMULATED_SIGNAL", "-D_WASI_EMULATED_MMAN", "-D_WASI_EMULATED_PROCESS_CLOCKS",
            "-fno-exceptions", "-Wno-everything", "-D_GNU_SOURCE", "-I" + overrides]
    targets = json.load(open(args.targets, encoding="utf-8"))
    jobs = []
    for target in targets:
        if not (target["name"] in TARGETS or target["name"].startswith("absl_")):
            continue
        for sources in target["target_sources"]:
            if "sources" not in sources:
                continue
            parameters = sources["parameters"]
            includes = [p for p in parameters if p.startswith("-I") and not p.endswith(".p")]
            defines = [p for p in parameters if p.startswith("-DWEBRTC_APM_DEBUG_DUMP")]
            standard = [p for p in parameters if p.startswith("-std=")]
            cpp = sources["language"] == "cpp"
            for source in sources["sources"]:
                if os.path.basename(source) in ("poison.cc", "platform_thread.cc"):
                    continue
                normalized = source.replace("\\", "/")
                if "/webrtc/" in normalized:
                    relative = normalized.split("/webrtc/", 1)[1]
                    if relative in PATCHES and relative.endswith(".cc"):
                        source = os.path.join(overrides, relative)
                obj = os.path.join(out, "obj", target["name"], os.path.basename(source) + ".o")
                command = [clangxx if cpp else clang] + base + standard + defines + includes
                if cpp and not target["name"].startswith("absl_"):
                    command.append("-fno-rtti")
                jobs.append((target["name"], obj, command + ["-c", source, "-o", obj]))

    def compile_one(job):
        os.makedirs(os.path.dirname(job[1]), exist_ok=True)
        source = job[2][-3]
        if os.path.exists(job[1]) and os.path.getmtime(job[1]) >= os.path.getmtime(source):
            return job, 0, ""
        result = subprocess.run(job[2], capture_output=True, text=True)
        return job, result.returncode, result.stderr

    archives = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as executor:
        for job, code, err in executor.map(compile_one, jobs):
            if code != 0:
                raise SystemExit("compile failed: " + job[2][-3] + "\n" + err[-2000:])
            archives.setdefault(job[0], []).append(job[1])
    libraries = []
    for name, objects in archives.items():
        archive = os.path.join(out, "lib" + name + ".a")
        if os.path.exists(archive):
            os.remove(archive)
        response = archive + ".rsp"
        open(response, "w", encoding="utf-8").write("\n".join('"' + o.replace("\\", "/") + '"' for o in objects))
        subprocess.run([os.path.join(bin_dir, "llvm-ar" + exe), "rcs", archive, "@" + response], check=True)
        libraries.append(archive)

    absl_includes = [p for t in targets if t["name"] == "webrtc-audio-processing-2" for s in t["target_sources"]
                     if "sources" in s for p in s["parameters"] if p.startswith("-I") and not p.endswith(".p")]
    shim = os.path.join(out, "shim.o")
    subprocess.run([clangxx] + base + ["-std=c++17", "-fno-rtti", "-I" + here, "-I" + webrtc] + absl_includes
                   + ["-include", "cstdint", "-include", "cstddef", "-c",
                      os.path.join(here, "smalt_apm_shim.cpp"), "-o", shim], check=True)
    ordered = [a for a in libraries if "webrtc-audio" in a] + [a for a in libraries if "webrtc-audio" not in a]
    link = [clangxx, "--target=wasm32-wasip1", "-" + args.opt, "-mexec-model=reactor", shim] + ordered \
        + ["-Wl,--export=" + e for e in EXPORTS] \
        + ["-lwasi-emulated-signal", "-lwasi-emulated-mman", "-lwasi-emulated-process-clocks",
           "-Wl,-z,stack-size=1048576", "-o", os.path.join(out, "apm.wasm")]
    subprocess.run(link, check=True)
    print("wrote", os.path.join(out, "apm.wasm"))


if __name__ == "__main__":
    main()

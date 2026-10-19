# Smalt

Smalt is a pure-Java (Java 26) media library: image, audio and video codecs, containers, pixel and sample
processing, voice processing and camera/screen capture, with no native code for coding. It started as
Cobalt's `cobalt-media` module and is consumed by Cobalt (`../Cobalt1`), which builds it in its reactor with
`mvn -Plocal-smalt`. Smalt never references Cobalt or WhatsApp; WhatsApp-specific recipes stay in Cobalt.

## Build

- Maven, Java 26 only (the incubating Vector API is not binary compatible across JDK feature releases).
- `mvn install -DskipTests` from the root; tests per module, one at a time (memory):
  `mvn -f <module>/pom.xml test`.
- Modules: `build` (`smalt-build`: metadata, plugin versions and the release profiles, inherited by
  `smalt-parent` and `smalt-bom`), `bom`, `core`, `image`, `audio`, `video`, `mpeg`, `capture`, `raw`,
  `desktop`, `aot`, `it` (JIT guard and cross-artifact tests, not published). Parent of the code modules:
  `smalt-parent` (the root `pom.xml`), which also gives every module JUnit and JMH for its tests.
- The test kit (`Oracle`, `Fixtures`, `Conformance`, `Quality`, `Dumps`, `HeifBuilder`, `Bytes`, `Packets`, and the
  helpers in `testkit.audio`, `testkit.image`, `testkit.container`, `testkit.io` and `testkit.fuzz`) lives in core's
  `src/test/java` under `testkit` and reaches the other modules through core's test jar (`smalt-core` with
  `<type>test-jar</type>`, test scope), which holds only `testkit/**`.
- Every demuxer, reader and image decoder runs through the I/O behavior harnesses (`DemuxerAsserts`,
  `ReaderAsserts`, `ImageDecoderAsserts`) with golden dumps under each module's `src/test/resources/dumps`
  (`-Dsmalt.test.updateDumps=true` rewrites them); a new container or codec adds its inputs as the `testkit.io`
  package documentation describes, over samples made by `tools/testdata/behavior` and pinned in its
  `samples.tsv`.
- `tools/codegen`: the generators of the VP9, AV1 and transposition vector kernels and of the Vorbis setup data
  (edit the generator or its template, never the generated file; `python tools/codegen/check.py` fails when a
  generated file differs from its generator's output); `tools/lint`: the comment lint (section 11 of
  Documentation); `tools/notices`: the third-party notices; `tools/testdata`: the test vector manifests and
  oracle scripts; `tools/fuzz`: the fuzzing runner. Every decoder, demuxer and parser has a Jazzer `@FuzzTest`
  target in its module's `fuzz` test package (the contract and the workflow are in `tools/fuzz/README.md`); a new
  one gets a target with `valid-*` seeds, and a crash a fuzzer finds gets its input saved under
  `src/test/resources/fuzz-regressions`, which every build replays.

The README's Install table is the single source of these descriptions; the pom `<description>`s copy it.

| Artifact | JPMS module | Contents |
|---|---|---|
| `smalt-core` | `com.github.auties00.smalt` | The media model, the codec registries, every container, image and audio processing, loudness, subtitles, PCM, tag editing and XMP (`MediaSource`/`MediaSink`, the codec SPI in `spi`, `MediaRuntime`, `metadata`, `audio.loudness`, `subtitle`) |
| `smalt-image` | `...smalt.image` | JPEG (and its lossless transforms), JPEG 2000, PNG, GIF, WebP, BMP, TIFF, ICO, JPEG XL, EXIF and image metadata editing, Motion-JPEG, and VP8 (lossy WebP is VP8) |
| `smalt-audio` | `...smalt.audio` | Opus, Vorbis, FLAC, ALAC, MP3, and voice processing |
| `smalt-video` | `...smalt.video` | VP9, AV1, AVIF, ProRes and DNxHD/DNxHR |
| `smalt-mpeg` | `...smalt.mpeg` | H.264, HEVC, HEIC, AAC, AMR, MPEG-4 Part 2, H.263, AC-3 and E-AC-3, the codecs licensed through patent pools or by Dolby |
| `smalt-capture` | `...smalt.capture` | Camera, screen and microphone capture, and speaker playback |
| `smalt-raw` | `...smalt.raw` | DNG and camera raw decoding: Canon, Nikon, Sony, Fujifilm, Olympus, Panasonic, Pentax, Minolta and Sigma raw files, demosaicing and camera color rendering |
| `smalt-desktop` | `...smalt.desktop` | ImageIO readers and writers and Java Sound providers for the formats the other artifacts handle |
| `smalt-aot` | `...smalt.aot` | Training workloads for JDK ahead-of-time caches, used only in the training run (`SmaltTraining`, `TrainingOptions`, `TrainingFamily`, `TrainingMode`, `TrainingWorkload`, `TrainingReport`, `TrainingMedia`) |

Every artifact depends only on core (capture and raw also on image; desktop also on `java.desktop`, the only
artifact that may use it; aot on every codec artifact, capture, whose conversion kernels it runs through
`capture.internal.convert.CaptureTrainingRound`, and raw). Codecs are found at run time through the `spi`
providers (`module-info` `provides` plus `META-INF/services`), so core never names a codec. Internal
packages (`internal.*`) are exported only to the artifacts that need them.

## Rules

- **Pure Java.** No native code for coding. FFM only in `capture` (`--enable-native-access`).
- **Vector API behind scalar twins.** Every vector kernel has a bit-identical scalar twin and an
  equivalence test; `internal.simd.Simd` decides availability once per JVM and a kernel family's selector
  is `Simd.ENABLED ? vector : scalar`. There is no runtime warm-up: the start-up cost of the Vector API is
  for an application's AOT cache, trained with `smalt-aot`, to remove; a kernel that falls back to the
  allocating path costs the codec thread directly. Every codec and kernel gets a workload in its artifact's
  `TrainingFamily` (`aot.internal.<Artifact>Workloads`), on synthetic content, in the coding modes, thread
  counts and options that select different kernels; workloads never model a particular application.
  Follow the JIT-robustness rules enforced by `it`'s `JitRobustnessTest` (no vectors across loop back edges
  or branch merges, kernels under about 2000 bytecodes, per-shape `lanewise`).
- **Correctness first.** Normative decoders stay bit-exact (conformance suites); encoders keep their output
  byte-identical unless a change is measured to be quality-neutral or better.
- **Performance claims need JMH A/B** in the same session (3+ forks) against a frozen baseline.
- **Licensing.** Port only from permissive sources; never read or port GPL/LGPL code (FFmpeg, x264, x265,
  libde265). Name the source of every port in a `/* Ports ... */` header above `package` (section 5 of Documentation), and
  record it in `tools/notices` when its license requires attribution in copies (see its README); thank every project Smalt learns from in the
  README's Acknowledgments.
- **Measuring.** Effects under about 5% need a quiet machine. On Windows, read thread CPU time from the
  cycle counter, not `getCurrentThreadCpuTime` (15.6 ms ticks). Run ffmpeg's `psnr` and `ssim` filters in
  separate single-threaded runs. JFR misattributes Vector API time to neighboring instructions: confirm a
  hot spot by disabling it before optimizing it. `-Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0` is an
  upper-bound experiment only, never shipped.
- **Threads.** Codecs parallelize on the single bounded pool `internal.task.ParallelBands`
  (`smalt.threads`); never start ad-hoc threads.

## Documentation

A comment states the contract and whatever the signature does not show; it never retells the code in words.
This follows the JDK's own practice (the OpenJDK sources, Oracle's "How to Write Doc Comments"); where a rule
is a house rule, it says so.

### 1. What gets a comment

| Member | Rule |
|---|---|
| Public or protected, in an exported package | Required, with every applicable `@param`, `@return` and `@throws`. |
| Top-level type in any package | Required. For an internal type, one sentence is enough. |
| Package-private, private, or in an `internal` package | Optional, 1-3 lines. Write one only for a unit, range, sentinel, ownership rule, unchecked precondition, invariant or upstream name. |
| Override that keeps the parent contract | None, except carried `@throws` (section 6). |
| `equals`/`hashCode`/`toString` | None, unless the contract is narrowed (equality is not field-wise). |
| Private or enum constructor; backing field of a documented accessor; Builder field; record accessor | None. |
| Exported package | `package-info.java` carrying the policy sentences of section 4. |
| Module | `module-info.java` (section 8). |

### 2. Summary sentence

- One standalone sentence, third person, present tense, ending with a period. It is what the index shows.
- Methods start with a verb phrase ("Decodes", "Returns"). Types, fields and constants start with a noun phrase
  ("A frame of planar YUV samples."). Exception types start "Signals ..." (as `IOException` does). Constructors
  start "Constructs"; factory methods start "Returns" or, when they open a resource, "Creates".
- It adds something beyond the name: "Sets the speed." is not enough; say what the speed trades off.
- Overloads say how they differ ("Decodes into 16-bit samples, soft-clipped into range.").
- A pure accessor uses `{@return ...}`. Never write `Returns X.` followed by `@return X`.
- House rules: write "for example" and "that is", never "e.g." or "i.e." (their period can end the summary
  early); American spelling ("color", "license", "behavior", "canceling") to match the identifiers.

### 3. Body: contract only

- State what a caller can rely on: results for edge inputs, sentinels, blocking, side effects, ownership and
  buffer lifetime, required caller buffer sizes, idempotence, determinism ("the output does not depend on the
  thread count"), and what is left unspecified. List special cases (`<ul>`, a formula or a `{@snippet}`); do
  not walk through the algorithm.
- Cost: state complexity and cost relative to sibling options ("the most expensive mode", "linear in the track
  length"). Leave out measured numbers: benchmarks, timings, test clips, BD-rate or PSNR deltas.
- Leave out internal buffer sizes, read-ahead windows, cache and scan thresholds, lane layouts, scheduling and
  helper-thread mechanics. If a maintainer needs one, it goes in `@implNote`.
- No history ("started as", "previously", "now handles"), no rejected alternatives, no comparison with other
  encoders, no reference to a consumer of Smalt.
- Another library's behavior never stands in for the rule ("as libtiff does"); state the rule. A type may make
  one verifiable compatibility promise in its body ("Decoded planes are bit-identical to libjpeg-turbo's
  `djpeg -dct int`."). A bit-exactness guarantee is contract, so it goes here, not in `@implNote`.
- An exported doc never links or names an internal or package-private type, and never names an upstream
  function. Upstream function names are allowed on non-exported members only, in parentheses in the summary
  (`({@code silk_noise_shape_quantizer_del_dec})`).
- Do not restate what the member summary table shows. Do document what each case, constant or record component
  means.

### 4. Policy sentences (stated once, never per member)

- **Null** (`java/time/package-info.java`, in every exported `package-info.java`): "Unless otherwise noted,
  passing a null argument to a constructor or method in any class or interface in this package will cause a
  `NullPointerException` to be thrown. The `@param` definition summarizes the null behavior; `@throws
  NullPointerException` is not documented on each method. Methods that take an object and return a boolean
  return `false` for null." Members then document only nullable parameters ("or `{@code null}` for none").
  Write `@throws NullPointerException` only when null is rejected inside a container argument (an array or list
  element) or when a parameter documented as nullable is rejected in some state.
- **Thread safety**, one class-body sentence in a fixed form: "Instances are immutable and thread-safe." /
  "Instances are not thread-safe." / "This class is thread-safe." No reasons in terms of fields. Stateless enums
  say nothing. (`java.time` puts this in `@implSpec`; Smalt keeps it in the body because it is a caller
  guarantee.)
- **Evolution of enums and sealed types:** closed by default; new constants or permitted subtypes come only in a
  major release, so callers may switch exhaustively. A type that may grow in a minor release says so in its
  class doc, in exactly this form: "This enum may gain constants in later releases; a `switch` over it needs a
  `default` branch." (or "This sealed type may gain subtypes ..."). Do not list permitted subtypes in prose
  except to say what each case means.
- **Unchecked internals:** a hot-path internal class says once: "Arguments are not checked; values outside the
  documented ranges give unspecified results."
- **Shared concepts** are defined in one place and linked: the threading model and `smalt.threads` in the core
  module doc and `MediaRuntime`; the Vector API kernel rules in `internal.simd.Simd`; 8-bit narrowing in
  `ImageOps.toEightBit`; the codec-to-artifact table in the README. A class writes one sentence and a link.
  Long matrices and conversion tables go in `package-info` or `doc-files/`.

### 5. Tags

- Order: `@apiNote`, `@implSpec`, `@implNote`, `@param`, `@return`, `@throws`, `@see`. One blank line before the
  first tag. Tag text starts on the tag line; continuation lines are flush with ` * `. No column padding in new
  code; remove existing padding in any paragraph you edit.
- `@apiNote` (public API only): non-normative guidance: when to prefer an alternative, usage examples, the
  rationale for a surprising default, generic interop advice. Never a guarantee, never a versioned third-party
  bug.
- `@implSpec`: the behavior of a method body that subclasses inherit: every `default` method ("The default
  implementation returns `false`.") and every overridable concrete method. Obligations on implementers of an
  abstract method (SPI and kernel interfaces) go in the body as "Implementations must ..." (as
  `Comparable.compareTo` does).
- `@implNote`: starts "This implementation ...". A non-obvious choice a maintainer might otherwise "fix", a
  deliberate divergence from upstream, or a constant's origin ("This implementation rounds at 30/128 where
  libvpx rounds at 48/128, chosen by measurement."). Not a design document; aim for 3 sentences or fewer.
- `@param`: a lowercase noun phrase, no final period, giving the role plus any unit, range, sentinel, ownership
  or in/out rule (`@param bits the field width, from 0 to 32`). "`@param filter the filter`" is banned.
  `@param <T>` on every public generic.
- `@return`: a noun phrase that includes special values (`the frame, valid until the next call, or
  {@code null} once the session is closed`).
- `@throws`: an "if" clause stating the caller-visible condition. House order (the JDK has none): NPE (only as
  section 4 allows), `IllegalArgumentException`, `IllegalStateException`, `IndexOutOfBoundsException`, then
  `MediaException.Malformed`, `Unsupported`, `LimitExceeded`, `Io`. Never an implementation-tied exception
  (`ArrayIndexOutOfBoundsException`).
- Licensing is not a tag. A ported file carries a `/* */` header above `package`, which the doclet does not
  render, and an entry in `THIRD-PARTY-NOTICES` shipped in each jar's `META-INF`:
  `/* Ports vo-amrwbenc 0.1.3 (voAMRWBEnc.c). Copyright VisualOn; Apache License 2.0. Modified for Smalt. */`

### 6. Overrides, twins and SPI providers

- Contract unchanged: no comment. Since JDK 21 the doclet inherits a `@throws` only for an exception named in the
  override's own `throws` clause, by exact type. An override of a method documenting `MediaException` subtypes
  therefore writes `/** @throws MediaException.Malformed {@inheritDoc} ... */`, one line per subtype, and nothing
  else.
- Contract gains a fact: `{@inheritDoc}`, then the added sentence or tag.
- Contract diverges (stricter precondition, other null or return semantics): write the comment from scratch.
- Never copy a parent's `@implSpec` or `@implNote`; write a new one only about the overriding implementation.
- Scalar and vector twins: the interface carries the contract; the scalar class doc says it is the reference
  implementation; the vector class doc is one sentence plus links; neither writes per-method docs.
- SPI providers registered with `ServiceLoader`: one sentence plus the `@provides` entry in the module doc. The
  provider interface states the no-argument constructor requirement and whether implementations must be
  thread-safe.

### 7. `{@code}`, `{@link}`, `{@value}`, `{@snippet}`, tables

- `{@code}` for identifiers, keywords and expressions (`{@code null}`, `{@code index < 0}`). Plain text for
  numbers in prose.
- `{@link}` the first useful mention of a name in each comment; later mentions use `{@code}`. Do not link
  `String`, `Object`, boxed primitives or `Math`. Use the simple name when the type is imported; otherwise a
  fully qualified link with a short label. Never add an import only for javadoc. Refer to all overloads as
  `{@code decode}`, or link the primary overload. `{@linkplain Comparable natural ordering}` links a phrase.
- `{@value}` instead of retyping a constant's value.
- `{@snippet}` (never `<pre>`) for usage examples, C struct layouts, bit-field formats and invariants as code.
- Tables: `<table class="striped">` with a `<caption>` and `<th scope="col">` header cells.
- ASCII only. Lines at most 120 characters; when an edit pushes a line over, reflow only the affected lines.

### 8. Modules, records, constants, options, exceptions, generated code

- **Module doc** (`module-info.java`): what the module contains, launch requirements stated once
  (`--add-modules jdk.incubator.vector`, capture's `--enable-native-access`), and `@uses`/`@provides` for the
  codec SPIs.
- **Records:** `@param` per component in the record comment, with unit, range and sentinel. Document a compact
  constructor only for its `@throws`.
- **Enum constants and constants:** one noun phrase carrying the identifying fact (wire code, magic bytes, spec
  name): `JPEG (JFIF, EXIF, Adobe), magic {@code FF D8 FF}.`
- **Options:** options types are immutable and built only through their Builder (no `withX` copy methods). This
  builders-only rule covers configuration types (options, settings, requests); immutable value types (for
  example `ColorInfo`, `Packet`, `SubtitleCue`, `ExifData`, `XmpPacket`, `IptcData`) keep their
  `withX` methods and get no Builder. The accessor is the only place that states a setting's meaning, range and
  default. The class doc is "Holds the settings of a `{@link X}`. Instances are immutable and thread-safe;
  create them with `{@link #builder()}` or a preset such as `{@link #defaults()}`." and does not list the
  settings. `toBuilder()`: "Returns a builder initialized with these options." Builder setter: "Sets the X; see
  `{@link Options#x()}`." with `@param x the value`, `@return this builder`; the Builder class doc states once
  when values are validated, and `build()` carries the validation `@throws` (the one other place a range
  appears).
- **Exceptions** are `Serializable`: they hold no fields beyond `Throwable`'s, or document each with `@serial`.
  The module doc states once that serialized forms are not compatible across releases.
- **Generated code:** generated sources say "Generated from `tools/codegen/<x>`; edit the template." A template that
  emits public types emits section-1-compliant docs, because doclint checks them like hand-written code.

### 9. Inline comments and tests

- Default none. Write one only for a hidden constraint, a numeric-constant origin, a workaround, or a lane or bit
  trick at the statement that performs it. Loop mechanics go at the loop, not in the method doc. A longer
  maintainer note goes in one `/* ... */` block in the class body.
- `// TODO:` marks a missing feature, `// FIXME:` known-wrong behavior. No section dividers.
- Tests use `@DisplayName` and `@Nested`; no javadoc beyond an optional one-paragraph class note.

### 10. Examples

**Public method** (`AudioReader.seek`):
```
Positions the reader so that the next {@link #read()} starts at the sample presented at {@code micros}.

<p>From {@code micros} on, the output equals a decode from the start of the track, except that Opus output is
not sample-exact and AAC noise-substituted bands may differ. A time before zero seeks to the start; a time at or
past the end makes the next {@code read()} return {@code null}.

@implNote This implementation pre-rolls by {@link AudioStreamDecoder.Timing#prerollMicros()} and decodes from
the start of the track when container timestamps are coarser than a sample.
@param micros the presentation time, in microseconds
@throws MediaException.Unsupported if the container cannot seek ({@link #isSeekable()} is {@code false})
@throws MediaException.Malformed if the index needed to seek is corrupt
@throws MediaException.Io if reading the source fails
```

**Options** (`MatroskaMuxerOptions`): accessor and Builder setter.
```
{@return the largest cluster size in bytes, at least 4096; {@link #DEFAULT_MAX_CLUSTER_BYTES} by default}

Sets the largest cluster size; see {@link MatroskaMuxerOptions#maxClusterBytes()}.
@param bytes the value
@return this builder
```

**Internal kernel** (`VectorH264Dsp`, replacing 15 to 25 lines of JIT notes repeated in 22 classes):
```
Implements {@link H264Dsp} with the Vector API; the output equals {@link ScalarH264Dsp} sample for sample.

@implNote This implementation follows the kernel rules of {@link Simd}; vertical edge filters transpose,
filter and write back in separate methods to stay under the JIT node budget.
```

**Override** (`GifAnimationReader.close`):
```
{@inheritDoc}
<p>The source is not closed. May block until a read-ahead already running on a helper thread finishes.
```


### 11. Enforcement

- **Javadoc tool** (`-Pjavadoc-check`, CI job `javadoc`). `mvn -Pjavadoc-check compile javadoc:javadoc-no-fork`
  runs doclint `reference` on every member, private included, and fails on a `{@link}`, `@see`, `@param` or
  `@throws` that does not resolve. `javadoc:javadoc-no-fork@doclint-all` runs doclint `all` (missing comments and
  tags included) at protected visibility; the javadoc tool documents only unqualified-exported packages, so it
  covers the exported API and never the `internal` packages. That run never fails the build: the comment lint
  gates its warnings with the baseline. The profile first deletes the plugin's record of its previous run, which
  would otherwise skip a module whose sources changed only in content. The release profile builds the javadoc
  jars with `all,-missing` and fails on errors.
- **Comment lint** (`tools/lint/doclint.py`, CI job `lint`), over every `*/src/main/java` file:
  - `@param x the x` (a parameter described by its own name);
  - a `Returns X.` summary followed by `@return X`;
  - non-ASCII characters in comments;
  - comment lines over 120 characters;
  - an `@implNote` not starting "This implementation";
  - history phrases (`started as`, `now handles`, `originally`, and `previously` as a sentence adverb);
  - `e.g.` and `i.e.` in comments;
  - `<pre>` in comments;
  - a dangling doc comment (one followed by another doc comment);
  - a package named in `tools/notices` with no file carrying a `/* Ports ... */` header;
  - with `--javadoc-log <log of the doclint-all run>`, every doclint warning or error of the exported API.
- **Baseline.** `tools/lint/baseline.tsv` lists the accepted findings per rule and file. The lint fails when a file
  has more findings of a rule than the baseline allows; `python tools/lint/doclint.py --update` lowers the counts
  after a fix and refuses to raise any, and CI fails a pull request whose baseline grew (`--shrink-only`). A
  change never adds entries, and a change that edits a file removes that file's entries.
- **javac** (`-Xlint:dangling-doc-comments,exports` in the parent pom) warns about doc comments the doclet would
  drop and about exported API that names a type its module does not export.
- Review only (not mechanically checkable): the summary adds something beyond the name; the body holds only
  contract; first-useful-mention linking; `@implNote` length; tag classification (`@apiNote`, `@implSpec`,
  `@implNote`).

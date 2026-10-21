/**
 * The I/O behavior harnesses: every demuxer, reader and image decoder of Smalt must give the same result
 * however a valid {@link com.github.auties00.smalt.io.MediaSource} delivers its bytes, or fail cleanly.
 *
 * <p>Three harnesses run one input under every {@link com.github.auties00.smalt.testkit.io.IoVariant} (whole
 * reads, one-byte reads, pseudo-random chunk sizes, a hidden length, and sources that read their input once),
 * twice over one source, over {@link com.github.auties00.smalt.testkit.io.SimulatedSource sources that
 * fail} once or from a read index on (with whole reads and with reads cut to random sizes) and over a
 * {@link com.github.auties00.smalt.testkit.io.SimulatedStream stream read once} that fails from a read on,
 * and compare a readable text dump with its golden file
 * ({@link com.github.auties00.smalt.testkit.Dumps}):
 * <ul>
 *   <li>{@link com.github.auties00.smalt.testkit.container.DemuxerAsserts} drives a
 *       {@link com.github.auties00.smalt.container.Demuxer}: container recognition, every packet
 *       ({@link com.github.auties00.smalt.testkit.container.DemuxDump}: per-track header, then pts, dts,
 *       duration, flags, size, side data and CRC-32 per packet), and seeks to 0, a third, two thirds and the
 *       end, before zero and chained, checked against the seek contract;</li>
 *   <li>{@link com.github.auties00.smalt.testkit.container.ReaderAsserts} decodes the first video or audio
 *       track with {@code VideoReader} or {@code AudioReader} and dumps one CRC line per frame or block;</li>
 *   <li>{@link com.github.auties00.smalt.testkit.image.ImageDecoderAsserts} runs detection, probing, the full
 *       and a reduced-size decode, the animation and page readers and any extra decode (a region or sensor
 *       decoder) and dumps one CRC line per picture ({@link com.github.auties00.smalt.testkit.image.PictureDump}).</li>
 * </ul>
 * A run fails with every behavior that broke: a result that differs from the reference, an injected fault that
 * was swallowed or surfaced as anything but a {@link com.github.auties00.smalt.MediaException.Io} caused by it,
 * a source closed by the parser, a seek that lands elsewhere than the contract says or leaves a failed seek
 * moved, and a parser that does not finish within its time limit.
 *
 * <h2>Adding a container or a codec</h2>
 * A package that adds or changes a demuxer, a decoder or a parser covers it with these harnesses:
 * <ol>
 *   <li>Add small samples (a few seconds or a few dozen pixels, at most a few hundred kilobytes) made from
 *       synthetic sources by a script in {@code tools/testdata/behavior} ({@code make-samples.sh} for
 *       containers and the codecs read from them, {@code make-image-samples.sh} for images,
 *       {@code make-wav-samples.py} for files the encoder tools cannot write directly), commit them under
 *       {@code core/src/test/resources/samples} or {@code image/src/test/resources/samples}, and pin them with
 *       {@code python -I tools/testdata/behavior/pin-samples.py}. Smoke-tier vectors of
 *       {@code tools/testdata/vectors.tsv} may be used as well, through
 *       {@link com.github.auties00.smalt.testkit.Conformance#fetchListed(String)}.</li>
 *   <li>A sample under {@code core/src/test/resources/samples} is picked up by {@code DemuxerBehaviorTest}
 *       and an image sample by {@code ImageDecoderBehaviorTest}; a codec's module adds its inputs to its own
 *       behavior test ({@code AudioDecoderBehaviorTest}, {@code VideoDecoderBehaviorTest},
 *       {@code MpegDecoderBehaviorTest}, {@code Vp8BehaviorTest}, {@code RawDecoderBehaviorTest}), or writes
 *       one that calls {@code DemuxerAsserts.of(name, data).run()}, {@code ReaderAsserts.video(name, data)},
 *       {@code ReaderAsserts.audio(name, data)} or {@code ImageDecoderAsserts.of(name, data)}.</li>
 *   <li>Run the test once with {@code -Dsmalt.test.updateDumps=true}, which writes the golden dumps under the
 *       module's {@code src/test/resources/dumps}, review them (a dump records the source variants over which
 *       the header differs or a read-once source refuses, so a degradation is visible) and commit them. Later
 *       runs compare against them, and a behavior change is a one-line diff of the dump.</li>
 *   <li>A demuxer whose index is coarser than its packets uses
 *       {@link com.github.auties00.smalt.testkit.container.DemuxerAsserts.SeekPrecision#AT_OR_BEFORE}, and one
 *       that estimates positions {@code ESTIMATED}; {@code EXACT} is the contract and the default. A demuxer
 *       that cannot be made to pass is fixed, not excused.</li>
 * </ol>
 * The harnesses check themselves against deliberately broken parsers and sources in
 * {@code DemuxerAssertsTest}, {@code ImageDecoderAssertsTest} and {@code ReaderAssertsTest}.
 */
package com.github.auties00.smalt.testkit.io;

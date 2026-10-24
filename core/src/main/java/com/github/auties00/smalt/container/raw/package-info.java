/**
 * Reading and writing raw elementary streams: {@link RawMuxer} writes them; {@link AnnexBDemuxer} reads H.264 and
 * HEVC Annex B streams, {@link ObuDemuxer} AV1 OBU streams, {@link Mpeg4VideoDemuxer} MPEG-4 Part 2 and H.263
 * streams, and {@link PcmDemuxer} headerless PCM.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.container.raw;

# The ClusterFuzzLite build image of Smalt's fuzz targets (tools/fuzz/README.md). The OSS-Fuzz JVM base image
# brings an older JDK, so the build installs the Java 26 JDK the targets need and bundles it with the targets.
FROM gcr.io/oss-fuzz-base/base-builder-jvm
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz \
    | tar -xz -C /opt
RUN mkdir -p /opt/jdk-26 \
    && curl -fsSL https://api.adoptium.net/v3/binary/latest/26/ga/linux/x64/jdk/hotspot/normal/eclipse \
    | tar -xz -C /opt/jdk-26 --strip-components=1
ENV SMALT_JDK=/opt/jdk-26
ENV SMALT_MAVEN=/opt/apache-maven-3.9.9
COPY . $SRC/smalt
WORKDIR $SRC/smalt
COPY .clusterfuzzlite/build.sh $SRC/

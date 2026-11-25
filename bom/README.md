# smalt-bom

> The Smalt bill of materials: it keeps every Smalt artifact on the same version.

[← Back to Smalt](../README.md)

## At a glance

- Smalt is a pure-Java image, audio and video library; every artifact needs Java 26.
- Import the BOM once, then declare each Smalt artifact without a version.
- It holds no code and adds no dependency: you still list every artifact you want.
- It pins every Smalt library artifact to one version, the BOM's own.
- It pins only Smalt artifacts; it never changes your JUnit or other third-party versions.
- Do not set a `<version>` on Smalt artifacts: they must all match, and an explicit version overrides the BOM.
- Not sure which artifacts you need? See [What it pins](#what-it-pins).

## Install

The snippets use `smalt-image` as the example; swap in the artifacts you need.

> [!NOTE]
> No release yet. Run `mvn install -DskipTests` at the repo root with JDK 26 to put `0.1.0-SNAPSHOT` in your local Maven repository.

### Maven

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.github.auties00</groupId>
            <artifactId>smalt-bom</artifactId>
            <version>0.1.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.github.auties00</groupId>
        <artifactId>smalt-image</artifactId>
    </dependency>
</dependencies>
```

<details>
<summary><b>Gradle (Kotlin and Groovy)</b></summary>

Kotlin DSL:

```kotlin
repositories { mavenLocal() } // needed only for the local snapshot

dependencies {
    implementation(platform("com.github.auties00:smalt-bom:0.1.0-SNAPSHOT"))
    implementation("com.github.auties00:smalt-image")
}
```

Groovy DSL:

```groovy
repositories { mavenLocal() } // needed only for the local snapshot

dependencies {
    implementation platform('com.github.auties00:smalt-bom:0.1.0-SNAPSHOT')
    implementation 'com.github.auties00:smalt-image'
}
```

</details>

For the full setup, see the [root install guide](../README.md#install). For runtime flags, see [JVM flags](../README.md#jvm-flags).

## What it pins

"Pulls in" lists the Smalt artifacts each one adds to your build automatically.

| Artifact | What it adds | Pulls in |
|---|---|---|
| [`smalt-core`](../core/README.md#at-a-glance) | Media model, every container, image and audio processing | none |
| [`smalt-image`](../image/README.md#at-a-glance) | Image codecs, Motion-JPEG, HAP, VP8 | core |
| [`smalt-audio`](../audio/README.md#at-a-glance) | Audio codecs, voice processing | core |
| [`smalt-video`](../video/README.md#at-a-glance) | VP9, AV1, AVIF, ProRes, DNxHD, more | core |
| [`smalt-mpeg`](../mpeg/README.md#at-a-glance) | H.264, HEVC, HEIC, AAC, AC-3, more (patent-licensed) | core |
| [`smalt-capture`](../capture/README.md#at-a-glance) | Camera, screen, mic capture, speaker playback | core, image |
| [`smalt-raw`](../raw/README.md#at-a-glance) | DNG and camera raw | core, image |
| [`smalt-desktop`](../desktop/README.md#at-a-glance) | ImageIO and Java Sound providers | core |
| [`smalt-aot`](../aot/README.md#at-a-glance) | Training run for a JDK AOT cache (optional) | core, image, audio, video, mpeg, capture, raw |

Short names drop the `smalt-` prefix. Each artifact name links to its full description.

- `smalt-desktop` brings no codecs: add the codec artifacts whose formats you want ImageIO and Java Sound to see.
- `smalt-aot` serves only the run that trains the AOT cache, but keep its jar in production so the class path matches ([smalt-aot](../aot/README.md)).
- Not pinned: the build poms (`smalt-build`, `smalt-parent`), the `smalt-core` test jar, and the unpublished `smalt-it` and `smalt-kernel-lint`.

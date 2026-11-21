<div align="center">

# smalt-bom

**The bill of materials that pins every Smalt artifact**

![Artifact](https://img.shields.io/badge/artifact-smalt--bom-2f6fe6?style=flat-square&labelColor=0b2a6f)

[Smalt](../README.md) | [core](../core/README.md) | [image](../image/README.md) | [audio](../audio/README.md) | [video](../video/README.md) | [mpeg](../mpeg/README.md) | [capture](../capture/README.md) | [raw](../raw/README.md) | [desktop](../desktop/README.md) | [aot](../aot/README.md) | **bom**

</div>

`smalt-bom` is a Maven bill of materials: import it once, and every Smalt artifact you depend on gets the same
version without a `<version>` of its own. It contains no code.

## Use it

**Maven**

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.github.auties00</groupId>
            <artifactId>smalt-bom</artifactId>
            <version>0.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**Gradle (Kotlin DSL)**

```kotlin
implementation(platform("com.github.auties00:smalt-bom:0.1.0"))
```

**Gradle (Groovy DSL)**

```groovy
implementation platform('com.github.auties00:smalt-bom:0.1.0')
```

> [!NOTE]
> Smalt is not on Maven Central yet. Until the first release, build it from source with
> `mvn install -DskipTests` and use version `0.1.0-SNAPSHOT`.

## What it pins

- [`smalt-core`](../core/README.md)
- [`smalt-image`](../image/README.md)
- [`smalt-audio`](../audio/README.md)
- [`smalt-video`](../video/README.md)
- [`smalt-mpeg`](../mpeg/README.md)
- [`smalt-capture`](../capture/README.md)
- [`smalt-raw`](../raw/README.md)
- [`smalt-desktop`](../desktop/README.md)
- [`smalt-aot`](../aot/README.md)

Which artifact to pick for which need is in the [root README](../README.md#install).

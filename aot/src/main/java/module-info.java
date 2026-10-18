/**
 * Smalt's training workloads for JDK ahead-of-time caches.
 *
 * <p>An application records an AOT cache from a training run ({@code -XX:AOTCacheOutput}) and
 * starts production with it ({@code -XX:AOTCache}), so that production starts with the classes,
 * profiles and, on JDKs that support it, compiled code of the training run instead of in the
 * interpreter. {@link com.github.auties00.smalt.aot.SmaltTraining} is that training run's media
 * work: it exercises every Smalt codec family an application selects, together with the
 * application's own workloads, until the JIT compiler has compiled what they run.
 *
 * <p>This module is not needed at run time; a class-path application keeps its jar on the class
 * path of production only because the class path of production must start with the class path of
 * training.
 *
 * <p>The launch requirements (the optional {@code --add-modules jdk.incubator.vector}) and the threading
 * model are those of the core module ({@code com.github.auties00.smalt}).
 */
module com.github.auties00.smalt.aot {
    // Smalt core: TrainingMedia returns its picture type
    requires transitive com.github.auties00.smalt;
    requires com.github.auties00.smalt.image;
    requires com.github.auties00.smalt.audio;
    requires com.github.auties00.smalt.video;
    requires com.github.auties00.smalt.mpeg;
    requires com.github.auties00.smalt.capture;
    requires com.github.auties00.smalt.raw;

    // Compiler idleness (CompilationMXBean) and the JVM's arguments (RuntimeMXBean)
    requires java.management;

    // The compilations a training cycle triggered (jdk.Compilation events)
    requires jdk.jfr;

    exports com.github.auties00.smalt.aot;
}

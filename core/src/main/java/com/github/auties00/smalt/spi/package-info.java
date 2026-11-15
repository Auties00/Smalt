/**
 * The service-provider interfaces through which the registries find codecs and containers: an artifact adds image,
 * audio or video codecs by providing an {@link ImageCodecProvider}, {@link AudioCodecProvider} or
 * {@link VideoCodecProvider}, and a container by providing a {@link ContainerProvider}, whose tag edits it
 * describes with a {@link TagRewrite}.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.spi;

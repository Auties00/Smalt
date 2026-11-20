/**
 * Sample processing: resampling ({@link Resampler}), channel mixing ({@link ChannelMixer}) and buffering
 * ({@link AudioFifo}).
 *
 * <p>The ambisonic rules of {@link ChannelMixer} place each speaker at a nominal direction, the azimuth in
 * degrees counterclockwise from the front and the elevation in degrees above the horizontal plane:
 * <table class="striped">
 * <caption>Nominal speaker directions</caption>
 * <thead><tr><th scope="col">Speakers</th><th scope="col">Azimuth</th><th scope="col">Elevation</th></tr></thead>
 * <tbody>
 * <tr><td>front center; top front center; top center; bottom front center</td><td>0</td><td>0; 45; 90;
 * -30</td></tr>
 * <tr><td>front left and right of center</td><td>15, -15</td><td>0</td></tr>
 * <tr><td>front left and right; top front left and right</td><td>30, -30</td><td>0; 45</td></tr>
 * <tr><td>bottom front left and right</td><td>45, -45</td><td>-30</td></tr>
 * <tr><td>wide left and right</td><td>60, -60</td><td>0</td></tr>
 * <tr><td>side and surround direct left and right; top side left and right</td><td>90, -90</td>
 * <td>0; 45</td></tr>
 * <tr><td>back left and right; top back left and right</td><td>110, -110</td><td>0; 45</td></tr>
 * <tr><td>back center; top back center</td><td>180</td><td>0; 45</td></tr>
 * </tbody>
 * </table>
 * The low-frequency channels and {@link com.github.auties00.smalt.audio.Speaker#UNKNOWN} have no direction.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.audio.process;

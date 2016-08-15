import net.e175.klaus.solarpositioning.AzimuthZenithAngle;
import net.e175.klaus.solarpositioning.DeltaT;
import net.e175.klaus.solarpositioning.SPA;

import java.util.GregorianCalendar;

/**
 * Created by Jaap on 25-7-2016.
 */
public class Sun {

    private final Properties prop;
    private final static double MIN_AZIMUTH = 95.0;
    private final static double MAX_AZIMUTH = 300.0;
    private final static double MAX_ZENITH = 70.0;

    public Sun() {
        prop = new Properties();
    }

    public AzimuthZenithAngle position() {
        final GregorianCalendar dateTime = new GregorianCalendar();
        AzimuthZenithAngle position = SPA.calculateSolarPosition(
                dateTime,
                prop.latitude, prop.longitude, prop.elevation,
                DeltaT.estimate(dateTime),
                1010, // avg. air pressure (hPa)
                11); // avg. air temperature (°C)
        return position;
    }

    public boolean shining() {
        AzimuthZenithAngle position = position();
        return position.getAzimuth() < MAX_AZIMUTH
                && position.getAzimuth() > MIN_AZIMUTH
                && position.getZenithAngle() < MAX_ZENITH;
    }

    @Override
    public String toString() {
        AzimuthZenithAngle position = position();
        return "azimuth: " + position.getAzimuth() + " [" + MIN_AZIMUTH + " <> " + MAX_AZIMUTH + "]" +
                ", zenith angle: " + position.getZenithAngle() + " [ < " + MAX_ZENITH + "]";
    }
}

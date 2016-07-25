import net.e175.klaus.solarpositioning.AzimuthZenithAngle;
import net.e175.klaus.solarpositioning.DeltaT;
import net.e175.klaus.solarpositioning.SPA;

import java.util.GregorianCalendar;

/**
 * Created by Jaap on 25-7-2016.
 */
public class Sun {
    final Properties prop;
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
        final GregorianCalendar dateTime = new GregorianCalendar();
        AzimuthZenithAngle position = position();
        boolean sun = position.getAzimuth() < prop.maxAzimuth
                && position.getAzimuth() > prop.minAzimuth
                && position.getZenithAngle() < prop.maxZenith;
        return sun;
    }
}

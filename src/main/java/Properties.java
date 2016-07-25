import org.apache.commons.io.IOUtils;

import java.io.FileNotFoundException;
import java.io.InputStream;

public class Properties {

    public static final int redisExpireSeconds = 5*60;
    public int elevation;
    public double latitude, longitude, minAzimuth, maxAzimuth, maxZenith;
    public final java.util.Properties prop;

    public Properties()  {
        InputStream inputStream = null;
        prop = new java.util.Properties();
         try {
            inputStream = getClass().getClassLoader().getResourceAsStream("iot.properties");

            if (inputStream != null) {
                prop.load(inputStream);
            } else {
                throw new FileNotFoundException("property file 'iot.properties' not found in the classpath");
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IOUtils.closeQuietly(inputStream);
        }

        latitude = Double.parseDouble(prop.getProperty("location.latitude"));
        longitude = Double.parseDouble(prop.getProperty("location.longitude"));
        elevation = Integer.parseInt(prop.getProperty("location.elevation"));
        minAzimuth = Double.parseDouble(prop.getProperty("sun.minAzimuth"));
        maxAzimuth = Double.parseDouble(prop.getProperty("sun.maxAzimuth"));
        maxZenith = Double.parseDouble(prop.getProperty("sun.maxZenith"));

    }
}

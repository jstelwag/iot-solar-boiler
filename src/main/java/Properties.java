import org.apache.commons.io.IOUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class Properties {

    public static final int redisExpireSeconds = 5*60;
    public final int elevation;
    public final double latitude, longitude;
    public final java.util.Properties prop;

    public Properties()  {
        InputStream inputStream = null;
        prop = new java.util.Properties();
        try {
            inputStream = new FileInputStream("/etc/iot.conf");
            prop.load(inputStream);
        } catch (IOException e) {
            LogstashLogger.INSTANCE.message("ERROR: can not load /etc/iot.conf " + e.toString());
        } finally {
            IOUtils.closeQuietly(inputStream);
        }

        latitude = Double.parseDouble(prop.getProperty("location.latitude"));
        longitude = Double.parseDouble(prop.getProperty("location.longitude"));
        elevation = Integer.parseInt(prop.getProperty("location.elevation"));
    }
}

import org.apache.commons.io.IOUtils;
import redis.clients.jedis.Jedis;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

/**
 * Created by Jaap on 24-7-2016.
 */
public class ReadTemperatures {

    public ReadTemperatures() {
        Properties prop = new Properties();
        Jedis jedis = new Jedis("localhost");
        for (String sensorLocation : TemperatureSensor.sensors.keySet()) {
            for (String sensorPosition : TemperatureSensor.sensors.get(sensorLocation)) {
                String key = sensorLocation + '.' + sensorPosition;
                try {
                    double t = readDeviceTemperature(prop.prop.getProperty(key));
                    jedis.setex(key, Properties.redisExpireSeconds, String.valueOf(t));
                } catch (IOException e) {
                    //TODO log it
                    System.out.println(e.toString() + " at sensor " + key);
                    e.printStackTrace();
                }
            }
        }
        jedis.close();
        if (!checkSlaveTemperatures()) {
            // TODO do some logging
            try {
                new SerialSlaveController().run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    double readDeviceTemperature(String deviceId) throws IOException {
        String file = "/sys/bus/w1/devices/" + deviceId + "/w1_slave";

        try (FileInputStream in = new FileInputStream(file)) {
            List<String> lines = IOUtils.readLines(in, "utf8");
            for (String line : lines) {
                if (line.contains("t=")) {
                    return Double.parseDouble(line.split("t=")[1])/1000;
                }
            }
        } catch (java.io.IOException e) {
            throw new IOException("Did not find temperature file " + file);
        }

        throw new IOException("Did not find a temperature in file " + file);
    }

    boolean checkSlaveTemperatures() {
        Jedis jedis = new Jedis("localhost");
        boolean retVal = jedis.exists("boiler200.Ttop");
        jedis.close();
        return retVal;
    }
}

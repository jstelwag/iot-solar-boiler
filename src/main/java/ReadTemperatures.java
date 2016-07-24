import org.apache.commons.io.IOUtils;

import java.io.FileInputStream;
import java.util.List;

/**
 * Created by Jaap on 24-7-2016.
 */
public class ReadTemperatures {

    public static void main(String[] args) throws InterruptedException {
      while (true) {
          String deviceId = "28-0000058ddbb6";
          double t = readDeviceTemperature(deviceId);
          System.out.println("It is " + t + "C");
          Thread.sleep(200);
      }
    }

    public static double readDeviceTemperature(String deviceId) {

        try (FileInputStream in = new FileInputStream("/sys/bus/w1/devices/" + deviceId + "/w1_slave")) {
            List<String> lines = IOUtils.readLines(in, "utf8");
            for (String line : lines) {
                if (line.contains("t=")) {
                    return Double.parseDouble(line.split("t=")[1])/1000;
                }
            }
        } catch (java.io.IOException e) {
            //Log to somewhere
        }

        return 0.0; //todo better throw an exception
    }
}

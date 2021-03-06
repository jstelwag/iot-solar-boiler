import net.e175.klaus.solarpositioning.AzimuthZenithAngle;
import org.apache.commons.lang3.StringUtils;
import redis.clients.jedis.Jedis;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;

/**
 * Created by Jaap on 25-7-2016.
 */
public class FluxLogger implements Closeable {

    private final InetAddress host;
    private final int port;
    private Jedis jedis;
    private final DatagramSocket socket;

    public FluxLogger() throws SocketException, UnknownHostException {
        final Properties properties = new Properties();
        if (StringUtils.isEmpty(properties.prop.getProperty("influx.ip"))) {
            LogstashLogger.INSTANCE.error("Influx.ip setting missing from properties");
            throw new UnknownHostException("Please set up influx.ip and port in iot.conf");
        }

        try {
            host = InetAddress.getByName(properties.prop.getProperty("influx.ip"));
            port = Integer.parseInt(properties.prop.getProperty("influx.port"));
        } catch (UnknownHostException e) {
            LogstashLogger.INSTANCE.error("Trying to set up InluxDB client for unknown host " + e.toString());
            throw e;
        }
        try {
            socket = new DatagramSocket();
        } catch (SocketException e) {
            System.out.println("Socket error " + e.toString());
            LogstashLogger.INSTANCE.error("Unable to open socket to connect to InfluxDB @" + host + ":" + port
                    + " " + e.getMessage());
            throw e;
        }
    }

    public FluxLogger log() {
        jedis = new Jedis("localhost");
        logTemperatures();
        sunLogger();
        logControl();
        jedis.close();
        return this;
    }

    @Deprecated
    private void logTemperatures() {
        for (String sensorLocation : TemperatureSensor.sensors.keySet()) {
            for (String sensorPosition : TemperatureSensor.sensors.get(sensorLocation)) {
                String key = sensorLocation + '.' + sensorPosition;
                if (jedis.exists(key)) {
                    String line;
                    if (sensorLocation.startsWith("boiler")) {
                        line = "boiler,name=" + sensorLocation + ",position=" + sensorPosition
                                + " temperature=" + jedis.get(key);
                    } else {
                        line = sensorLocation + ".temperature " + sensorPosition + "=" + jedis.get(key);
                    }
                    send(line);
                } else {
                    LogstashLogger.INSTANCE.warn("No temperature for " + key);
                }
            }
        }
        if (jedis.exists("pipe.Tslope")) {
            send("pipe.velocity slope=" + jedis.get("pipe.Tslope") + ",deviation=" + jedis.get("pipe.TstandardDeviation"));
        }
    }

    private void logControl() {
        String line = "solarstate,controlstate="
                + (jedis.exists("solarState") ? jedis.get("solarState") : "unavailable");
        line += ",realstate=" + (jedis.exists("solarStateReal") ? jedis.get("solarStateReal") : "unavailable");
        line += " startTflowOut=" + jedis.get("stateStartTflowOut");
        line += ",value=1";

        send(line);
    }

    private void sunLogger() {
        Sun sun = new Sun();
        AzimuthZenithAngle position = sun.position();
        String line = "sun azimuth=" + position.getAzimuth()
                    + ",zenithAngle=" + position.getZenithAngle()
                    + ",power=" + (sun.shining() ? "1" : "0");
        send(line);
    }

    public FluxLogger send(String line) {
        byte[] data = line.getBytes();
        try {
            DatagramPacket packet = new DatagramPacket(data, data.length, host, port);
            socket.send(packet);
        } catch (IOException e) {
            LogstashLogger.INSTANCE.error("Faulty UDP connection " + socket.isConnected() + ", @"
                    + host.getHostAddress() + ":" + port + ", socket " + socket.isBound());
        }

        return this;
    }

    @Override
    public void close() {
        if (socket != null)
            socket.close();
    }
}
import net.e175.klaus.solarpositioning.AzimuthZenithAngle;
import org.apache.commons.lang3.StringUtils;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.net.*;

/**
 * Created by Jaap on 25-7-2016.
 */
public class FluxLogger {

    private final InetAddress host;
    private final int port;
    private final DatagramSocket socket;
    private final Jedis jedis;

    public FluxLogger() throws SocketException, UnknownHostException {
        final Properties properties = new Properties();
        if (StringUtils.isEmpty(properties.prop.getProperty("influx.ip"))) {
            throw new UnknownHostException("Please set up influx.ip and port in iot.conf");
        }
        try {
            host = InetAddress.getByName(properties.prop.getProperty("influx.ip"));
            port = Integer.parseInt(properties.prop.getProperty("influx.port"));
            socket = new DatagramSocket();
        } catch (UnknownHostException e) {
            LogstashLogger.INSTANCE.message("ERROR: trying to set up InluxDB client for unknown host " + e.toString());
            throw e;
        }

        jedis = new Jedis("localhost");
        logState();
        logTemperatures();
        sunLogger();
        jedis.close();
        socket.close();
    }

    private void logTemperatures() {
        for (String sensorLocation : TemperatureSensor.sensors.keySet()) {
            String line = sensorLocation + '.' + "temperature ";
            for (String sensorPosition : TemperatureSensor.sensors.get(sensorLocation)) {
                String key = sensorLocation + '.' + sensorPosition;
                if (jedis.exists(key)) {
                    line += (line.contains("=") ? "," : "") + sensorPosition + '=' + jedis.get(key);
                } else {
                    LogstashLogger.INSTANCE.message("WARN: no temperature for " + key);
                }
            }
            if (line.contains("=")) {
                send(line);
                LogstashLogger.INSTANCE.message(line);
            }
        }
    }

    private void logState() {
        if (jedis.exists("solarState")) {
            SolarState state = SolarState.valueOf(jedis.get("solarState"));
            String line = "solarstate,circuit=boiler500 value=";
            line += state == SolarState.boiler500 ? "1" : "0";
            send(line);

            line = "solarstate,circuit=boiler200 value=";
            line += state == SolarState.boiler200 ? "1" : "0";
            send(line);

            line = "solarstate,circuit=recycle value=";
            line += state == SolarState.recycle ? "1" : "0";
            send(line);

            if (jedis.exists("boiler200.state")) {
                line = "boiler200.state value=" + jedis.get("boiler200.state");
                send(line);
            }
        } else {
            LogstashLogger.INSTANCE.message("ERROR: there is no SolarState in Redis");
        }
    }

    private void sunLogger() {
        Sun sun = new Sun();
        AzimuthZenithAngle position = sun.position();
        String line = "sun azimuth=" + position.getAzimuth()
                    + ",zenithAngle=" + position.getZenithAngle()
                    + ",power=" + (sun.shining() ? "1" : "0");
        send(line);
    }

    private void send(String line) {
        byte[] data = line.getBytes();
        try {
            DatagramPacket packet = new DatagramPacket(data, data.length, host, port);
            socket.send(packet);
        } catch (IOException e) {
            LogstashLogger.INSTANCE.message("ERROR: for UDP connection " + socket.isConnected() + ", @"
                    + host.getHostAddress() + ":" + port + ", socket " + socket.isBound());
        }
    }
}
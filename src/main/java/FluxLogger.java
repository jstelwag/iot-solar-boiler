import net.e175.klaus.solarpositioning.AzimuthZenithAngle;
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
        try {
            host = InetAddress.getByName(properties.prop.getProperty("influx.ip"));
            port = Integer.parseInt(properties.prop.getProperty("influx.port"));
            socket = new DatagramSocket();
        } catch (UnknownHostException e) {
            e.printStackTrace();
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
                    line += line.contains("=") ? "," : "" + sensorPosition + '=' + jedis.get(key);
                } else {
                    LogstashLogger.INSTANCE.message("WARN: no temperature for " + key);
                }
            }
            if (line.contains("=")) {
                send(line);
            }
        }
    }

    private void logState() {
        String solarState = jedis.get("solarState");
        boolean solarOff = "solarpumpOff".equals(solarState) || "error".equals(solarState);
        String line = "solarstate,circuit=boiler500 value=";
        line += solarOff ? "0" : "boiler500".equals(solarState) ? "1" : "0";
        send(line);
        line = "solarstate,circuit=boiler200 value=";
        line += solarOff ? "0" : "boiler200".equals(solarState) ? "1" : "0";
        send(line);
        line = "solarstate,circuit=recycle value=";
        line += solarOff ? "0" : "recycle".equals(solarState) ? "1" : "0";
        send(line);
        if (jedis.exists("boiler200.state")) {
            line = "boiler200.state value=" + jedis.get("boiler200.state");
            send(line);
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
            System.out.println("ERROR for UDP connection " + socket.isConnected() + ", @"
                    + host.getHostAddress() + ":" + port + ", socket " + socket.isBound());
        }
    }
}
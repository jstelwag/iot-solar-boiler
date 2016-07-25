import net.e175.klaus.solarpositioning.AzimuthZenithAngle;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.net.*;

/**
 * Created by Jaap on 25-7-2016.
 */
public class StateLogger {

    InetAddress host;
    final int port;
    DatagramSocket socket;
    Jedis jedis;

    public StateLogger() {
        final Properties properties = new Properties();
        try {
            host = InetAddress.getByName(properties.prop.getProperty("influx.ip"));
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        port = Integer.parseInt(properties.prop.getProperty("influx.port"));
        try {
            socket = new DatagramSocket();
        } catch (SocketException e) {
            e.printStackTrace();
        }
        jedis = new Jedis("localhost");
        logState();
        logTemperatures();
        sunLogger();
    }

    public void logTemperatures() {
        for (String sensorLocation : TemperatureSensor.sensors.keySet()) {
            String line = sensorLocation + '.' + "temperature ";
            for (String sensorPosition : TemperatureSensor.sensors.get(sensorLocation)) {
                String key = sensorLocation + '.' + sensorPosition;
                if (jedis.exists(key)) {
                    line += line.contains("=") ? "" : "," + sensorPosition + '=' + jedis.get(key);
                }
            }
            if (line.contains("=")) {
                send(line);
            }
        }
    }

    public void logState() {
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
    }

    public void sunLogger() {
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
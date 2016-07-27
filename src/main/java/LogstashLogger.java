import java.io.Closeable;
import java.io.IOException;
import java.net.*;

/**
 * Created by Jaap on 26-5-2016.
 */
public class LogstashLogger implements Closeable {

    public final static LogstashLogger INSTANCE = new LogstashLogger();

    InetAddress host;
    final int port;
    DatagramSocket socket;

    private LogstashLogger() {
        final Properties properties = new Properties();
        port = Integer.parseInt(properties.prop.getProperty("logstash.port"));
        try {
            socket = new DatagramSocket();
        } catch (SocketException e) {
            e.printStackTrace();
        }
        try {
            host = InetAddress.getByName(properties.prop.getProperty("logstash.ip"));
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        addShutdownHook();
    }

    public void message(String line) {
        send("iot-solar-boiler: " + line);
    }

    private void send(String message) {
        if (socket != null) {
            byte[] data = message.getBytes();
            try {
                DatagramPacket packet = new DatagramPacket(data, data.length, host, port);
                socket.send(packet);
            } catch (IOException e) {
                System.out.println("ERROR for UDP connection " + socket.isConnected() + ", @"
                        + host.getHostAddress() + ":" + port + ", socket " + socket.isBound() + ". For " + message);
            }
        } else {
            System.out.println("Socket for LogStash failed to initialize, cannot send message " + message);
        }
    }

    @Override
    public void close() {
        socket.close();
    }

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                close();
            }
        });
    }
}

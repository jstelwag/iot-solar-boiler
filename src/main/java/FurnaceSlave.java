import gnu.io.CommPortIdentifier;
import gnu.io.SerialPort;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;
import org.apache.commons.lang3.StringUtils;
import redis.clients.jedis.Jedis;

import java.io.*;
import java.util.Date;
import java.util.Enumeration;


/**
 * Created by Jaap on 25-7-2016.
 */
public class FurnaceSlave implements SerialPortEventListener {

    private final static int TTL = 60;
    private final String startTime;
    public final static String STARTTIME = "furnaceslave.starttime";
    /**
     * A BufferedReader which will be fed by a InputStreamReader
     * converting the bytes into characters
     * making the displayed results codepage independent
     */
    private BufferedReader input;
    private PrintWriter output;
    private SerialPort serialPort;

    /** Milliseconds to block while waiting for port open */
    private static final int TIME_OUT = 2000;
    /** Default bits per second for COM port. */
    private static final int DATA_RATE = 9600;

    public FurnaceSlave() {
        startTime = String.valueOf(new Date().getTime());
        Jedis jedis = new Jedis("localhost");
        if (jedis.exists(STARTTIME)) {
            LogstashLogger.INSTANCE.message("Exiting redundant FurnaceSlave");
            System.exit(0);
        }

        jedis.setex(STARTTIME, TTL, startTime);

        Properties prop = new Properties();
        // the next line is for Raspberry Pi and
        // gets us into the while loop and was suggested here was suggested http://www.raspberrypi.org/phpBB3/viewtopic.php?f=81&t=32186
        System.setProperty("gnu.io.rxtx.SerialPorts", prop.prop.getProperty("usb.furnace"));

        CommPortIdentifier portId = null;
        Enumeration portEnum = CommPortIdentifier.getPortIdentifiers();

        while (portEnum.hasMoreElements()) {
            CommPortIdentifier currPortId = (CommPortIdentifier) portEnum.nextElement();
            if (currPortId.getName().equals(prop.prop.getProperty("usb.furnace"))) {
                portId = currPortId;
                break;
            }
        }
        if (portId == null) {
            LogstashLogger.INSTANCE.message("ERROR: could not find USB at " + prop.prop.getProperty("usb.furnace"));
            return;
        }

        try {
            // open serial port, and use class name for the appName.
            serialPort = (SerialPort) portId.open(this.getClass().getName(), TIME_OUT);

            // set port parameters
            serialPort.setSerialPortParams(DATA_RATE,
                    SerialPort.DATABITS_8,
                    SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE);

            // open the streams
            input = new BufferedReader(new InputStreamReader(serialPort.getInputStream()));
            output = new PrintWriter(serialPort.getOutputStream());

            // add event listeners
            serialPort.addEventListener(this);
            serialPort.notifyOnDataAvailable(true);
        } catch (Exception e) {
            System.err.println(e.toString());
        }
        addShutdownHook();
    }

    /**
     * This should be called when you stop using the port.
     * This will prevent port locking on platforms like Linux.
     */
    public synchronized void close() {
        Jedis jedis = new Jedis("localhost");
        jedis.del(STARTTIME);
        jedis.close();
        if (serialPort != null) {
            serialPort.removeEventListener();
            serialPort.close();
        }
    }

    /**
     * Handle an event on the serial port. Read the data and print it.
     */
    public synchronized void serialEvent(SerialPortEvent oEvent) {
        Jedis jedis = new Jedis("localhost");
        if (jedis.exists(STARTTIME) && !jedis.get(STARTTIME).equals(startTime)) {
            LogstashLogger.INSTANCE.message("Connection hijack, exiting SolarSlave");
            System.exit(0);
        }
        jedis.setex(STARTTIME, TTL, startTime);
        if (oEvent.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
            try {
                String inputLine = input.readLine();
                if (inputLine.startsWith("log:")) {
                    LogstashLogger.INSTANCE.message("iot-furnace-controller", inputLine.substring(4).trim());
                } else if (StringUtils.countMatches(inputLine, ":") == 1) {
                    jedis.setex("boiler200.state", Properties.redisExpireSeconds, inputLine.split(":")[0]);
                    jedis.setex("boiler200.Ttop", Properties.redisExpireSeconds, inputLine.split(":")[1]);

                    if (jedis.exists(FurnaceMonitor.JEDIS_KEY) && "ON".equals(jedis.get(FurnaceMonitor.JEDIS_KEY))) {
                        output.println("F:1");
                    } else {
                        output.println("F:0");
                    }
                    output.flush();
                } else {
                    LogstashLogger.INSTANCE.message("ERROR: received garbage from the Furnace micro controller: " + inputLine);
                }
            } catch (IOException e) {
                LogstashLogger.INSTANCE.message("ERROR: problem reading serial input from USB (ignoring this) " + e.toString());
            }
        }
        jedis.close();
    }

    public void run() {
        LogstashLogger.INSTANCE.message("Starting FurnaceSlave");
        Thread t = new Thread() {
            public void run() {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ie) {
                }
            }
        };
        t.start();
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

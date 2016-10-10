import gnu.io.CommPortIdentifier;
import gnu.io.SerialPort;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;
import org.apache.commons.lang3.StringUtils;
import redis.clients.jedis.Jedis;

import java.io.*;
import java.util.Calendar;
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

    Jedis jedis;

    /** Milliseconds to block while waiting for port open */
    private static final int TIME_OUT = 2000;
    /** Default bits per second for COM port. */
    private static final int DATA_RATE = 9600;

    public FurnaceSlave() {
        startTime = String.valueOf(new Date().getTime());
        jedis = new Jedis("localhost");
        if (jedis.exists(STARTTIME)) {
            LogstashLogger.INSTANCE.message("Exiting redundant FurnaceSlave");
            jedis.close();
            System.exit(0);
        }

        jedis.setex(STARTTIME, TTL, startTime);
        jedis.close();

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
            close();
            System.exit(0);
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
        if (jedis == null || !jedis.isConnected()) {
            jedis = new Jedis("localhost");
        }
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
                    if (!TemperatureSensor.isOutlier(inputLine.split(":")[1])) {
                        jedis.setex("boiler200.Ttop", Properties.redisExpireSeconds, inputLine.split(":")[1]);
                    }

                    boolean furnaceState;
                    if (jedis.exists(FurnaceMonitor.FURNACE_KEY)) {
                        furnaceState = "ON".equals(jedis.get(FurnaceMonitor.FURNACE_KEY));
                    } else {
                        Calendar now = Calendar.getInstance();
                        LogstashLogger.INSTANCE.message("No iot-monitor furnace state available, using month based default");
                        furnaceState = now.get(Calendar.MONTH) < 4 || now.get(Calendar.MONTH) > 9;
                    }

                    boolean pumpState = false;
                    if (jedis.exists(FurnaceMonitor.PUMP_KEY)) {
                        pumpState = "ON".equals(jedis.get(FurnaceMonitor.PUMP_KEY));
                    } else {
                        LogstashLogger.INSTANCE.message("No iot-monitor pump state available");
                    }

                    output.println((furnaceState ? "T" : "F") + (pumpState ? "T" : "F"));
                    output.flush();
                    try (FluxLogger flux = new FluxLogger()) {
                        flux.send("koetshuis_kelder state=" + (furnaceState ? "1i" : "0i"));
                        flux.send("koetshuis_kelder pumpState=" + (pumpState ? "1i" : "0i"));
                    }
                } else {
                    LogstashLogger.INSTANCE.message("ERROR: received garbage from the Furnace micro controller: " + inputLine);
                }
            } catch (IOException e) {
                LogstashLogger.INSTANCE.message("ERROR: problem reading serial input from USB, exiting " + e.toString());
                close();
                System.exit(0);
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

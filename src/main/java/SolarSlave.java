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
public class SolarSlave implements SerialPortEventListener {

    private final static int TTL = 60;
    private final String startTime;
    private static final String STARTTIME = "solarslave.starttime";
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

    public static final int T_SET_LENGTH = 50;

    public SolarSlave() {
        startTime = String.valueOf(new Date().getTime());
        jedis = new Jedis("localhost");

        if (jedis.exists(STARTTIME)) {
            LogstashLogger.INSTANCE.message("Exiting redundant SolarSlave");
            jedis.close();
            System.exit(0);
        }

        jedis.setex(STARTTIME, TTL, startTime);
        jedis.close();

        Properties prop = new Properties();
        // the next line is for Raspberry Pi and
        // gets us into the while loop and was suggested here was suggested http://www.raspberrypi.org/phpBB3/viewtopic.php?f=81&t=32186
        System.setProperty("gnu.io.rxtx.SerialPorts", prop.prop.getProperty("usb.solar"));

        CommPortIdentifier portId = null;
        Enumeration portEnum = CommPortIdentifier.getPortIdentifiers();

        while (portEnum.hasMoreElements()) {
            CommPortIdentifier currPortId = (CommPortIdentifier) portEnum.nextElement();
            if (currPortId.getName().equals(prop.prop.getProperty("usb.solar"))) {
                portId = currPortId;
                break;
            }
        }
        if (portId == null) {
            LogstashLogger.INSTANCE.message("ERROR: could not find USB at " + prop.prop.getProperty("usb.solar"));
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
    private synchronized void close() {
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
        jedis = new Jedis("localhost");
        if (jedis.exists(STARTTIME) && !jedis.get(STARTTIME).equals(startTime)) {
            LogstashLogger.INSTANCE.message("Connection hijack, exiting SolarSlave");
            System.exit(0);
        }
        jedis.setex(STARTTIME, TTL, startTime);
        if (oEvent.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
            try {
                String inputLine = input.readLine();
                if (StringUtils.countMatches(inputLine, ":") == 4) {
                    //Format: Ttop:Tmiddle:Tbottom:TflowIn:TflowOut
                    if (!TemperatureSensor.isOutlier(inputLine.split(":")[0])) {
                        jedis.setex("boiler500.Ttop", Properties.redisExpireSeconds, inputLine.split(":")[0]);
                    }
                    if (!TemperatureSensor.isOutlier(inputLine.split(":")[1])) {
                        jedis.setex("boiler500.Tmiddle", Properties.redisExpireSeconds, inputLine.split(":")[1]);
                    }
                    if (!TemperatureSensor.isOutlier(inputLine.split(":")[2])) {
                        jedis.setex("boiler500.Tbottom", Properties.redisExpireSeconds, inputLine.split(":")[2]);
                    }
                    if (!TemperatureSensor.isOutlier(inputLine.split(":")[3])) {
                        jedis.setex("pipe.TflowIn", Properties.redisExpireSeconds, inputLine.split(":")[3]);
                    }
                    if (!TemperatureSensor.isOutlier(inputLine.split(":")[4])) {
                        jedis.setex("pipe.TflowOut", Properties.redisExpireSeconds, inputLine.split(":")[4]);
                    }

                    jedis.lpush("pipe.TflowSet", Double.toString(((double)new Date().getTime())/(60*60*1000))
                            + ":" + inputLine.split(":")[4]);
                    jedis.ltrim("pipe.TflowSet", 0, T_SET_LENGTH);

                    //Response format: [ValveI][ValveII][SolarPump]
                    if (jedis.exists("solarState")) {
                        SolarState state = SolarState.valueOf(jedis.get("solarState"));
                        output.println(state.line());
                    } else {
                        output.println(SolarState.error.line());
                    }
                    output.flush();
                } else if (inputLine.startsWith("log:")) {
                    LogstashLogger.INSTANCE.message("iot-solar-controller", inputLine.substring(4).trim());
                } else {
                    LogstashLogger.INSTANCE.message("ERROR: received garbage from the Solar micro controller: " + inputLine);
                }
            } catch (IOException e) {
                LogstashLogger.INSTANCE.message("ERROR: problem reading serial input from USB,i will kill myself" + e.toString());
                close();
                System.exit(0);
            }
        }
        jedis.close();
    }

    public void run() {
        LogstashLogger.INSTANCE.message("Starting SolarSlave");
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

import gnu.io.CommPortIdentifier;
import gnu.io.SerialPort;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;
import org.apache.commons.lang3.StringUtils;
import redis.clients.jedis.Jedis;

import java.io.*;
import java.util.Enumeration;


/**
 * Created by Jaap on 25-7-2016.
 */
public class SolarSlave implements SerialPortEventListener {

    private final boolean isRunning;
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

    public static final int T_SET_LENGTH = 30;

    public SolarSlave() {
        Jedis jedis = new Jedis("localhost");
        isRunning = jedis.exists("boiler200.state");
        if (!isRunning) {
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
    }

    /**
     * This should be called when you stop using the port.
     * This will prevent port locking on platforms like Linux.
     */
    private synchronized void close() {
        if (serialPort != null) {
            serialPort.removeEventListener();
            serialPort.close();
        }
    }

    /**
     * Handle an event on the serial port. Read the data and print it.
     */
    public synchronized void serialEvent(SerialPortEvent oEvent) {
        if (oEvent.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
            try {
                String inputLine = input.readLine();
                if (StringUtils.countMatches(inputLine, ":") == 4) {
                    //Format: Ttop:Tmiddle:Tbottom:TflowIn:TflowOut
                    Jedis jedis = new Jedis("localhost");
                    jedis.setex("boiler500.Ttop", Properties.redisExpireSeconds, inputLine.split(":")[0]);
                    jedis.setex("boiler500.Tmiddle", Properties.redisExpireSeconds, inputLine.split(":")[1]);
                    jedis.setex("boiler500.Tbottom", Properties.redisExpireSeconds, inputLine.split(":")[2]);
                    jedis.setex("pipe.TflowIn", Properties.redisExpireSeconds, inputLine.split(":")[3]);
                    jedis.setex("pipe.TflowOut", Properties.redisExpireSeconds, inputLine.split(":")[4]);

                    jedis.lpush("pipe.TflowSet", inputLine.split(":")[4]);
                    jedis.ltrim("pipe.TflowSet", 0, T_SET_LENGTH);

                    //Response format: [ValveI][ValveII][SolarPump]
                    if (jedis.exists("solarState")) {
                        SolarState state = SolarState.valueOf(jedis.get("solarState"));
                        output.println(state.line());
                    } else {
                        output.println(SolarState.error.line());
                    }
                    output.flush();
                    jedis.close();
                } else if (inputLine.startsWith("log:")) {
                    LogstashLogger.INSTANCE.message("iot-solar-controller", inputLine.substring(4).trim());
                } else {
                    LogstashLogger.INSTANCE.message("ERROR: received garbage from the Solar micro controller: " + inputLine);
                }
            } catch (IOException e) {
                LogstashLogger.INSTANCE.message("ERROR: problem reading serial input from USB (ignoring this) " + e.toString());
            }
        }
    }

    public void run() {
        if (!isRunning) {
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

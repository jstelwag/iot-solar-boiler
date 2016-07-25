import com.pi4j.io.gpio.*;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.util.Date;

/**
 * Solar boiler control
 *
 * The solar is controlled mainly by the inflow and outflow temperatures. This is more accurate than boiler temperature sensors. A
 * secondary control is the sun location indicator.
 *
 * Why two boilers?
 * One might wonder, why not a single boiler?
 * Well, for a larger solar collector a single 500 liter boiler does not suffice. And a larger boiler than 500 liter would not fit in my cellar.
 * Further you will need a boiler with auxiliary heating coil in case of a rainy day. Finally, in off season, the smaller
 * boiler is large enough to harvest solar energy.
 *
 * Setup
 * The large 500 liter boiler (boiler L) is fitted with one (solar) coil. The small 200 liter boiler (boiler S)
 * is fitted with a solar coil and a furnace coil in the top. The latter is controlled by another controller (FurnaceController).
 *
 * The solar system is connected with two three way valves. The first valve (valve I) controls the flow to boiler L (B) or the second valve (A).
 * By default the flow is to boiler L. The second valve controls the flow to boiler S (B) or the return flow (A). By default in this valve is
 * the small boiler.
 * When both valves are activated the solar flow is returned to the collectors bypassing both boilers. This allows for precise temperature switching.
 * (Without the return flow, the classical setup is a sensor in the solar collector to switch on the solar pump.)
 *
 * The small and large boilers have 1 and 3 thermometers respectively (Tsl and Tll, Tlm, Tlh). There are thermometers on the input
 * and output flow (Tin, Tout).
 *
 * Control
 * The main purpose of the small boiler is to ensure there is always warm water with the central heater coil. However, the lower solar coil can heat up
 * the entire boiler and save gas. Boiler S is not allowed to exceed 70C, when it does exceed this the recycle pump is switched on permanently.
 * The central heating / solar heating mode for boiler S work independantly. Solar will heat this boiler until the max temperature of 70C
 * is reached. The lower thermometer Tsl is used to control the solar coil.
 * Boiler L is used to accumulate excess heat from the collectors either because the sun is too strong and the small boiler over heats or
 * beacause the sun is not strong enough to reach the required temperature. The boiler is not allowed to exceed 95C, in this case the valves
 * are switched to recycle mode. The solar pump is switched off when Tin exceeds 120C.
 *
 * Solar pump control
 * todo
 */
public class Controller {
    private final Jedis jedis;
    private final GpioController gpio;

    private double TflowIn, TflowOut, stateStartTflowOut;

    final int STATE_CHANGE_GRACE_MILLISECONDS = 60*1000;

    private final static Pin SOLAR_PUMP_PIN = RaspiPin.GPIO_05;
    private final static Pin RECYCLE_PUMP_PIN = RaspiPin.GPIO_06; // hot clean water recycle pump
    private final static Pin VALVE_I_PIN = RaspiPin.GPIO_07; // Large Boiler (off) | Valve II (On)
    private final static Pin VALVE_II_PIN = RaspiPin.GPIO_08; // Small Boiler (off) | Recycle (On)

    public Controller() throws IOException {
        jedis = new Jedis("localhost");
        gpio = GpioFactory.getInstance();
        readTemperatures();
        control();
    }

    void control() {
        Sun sun = new Sun();
        if (sun.shining()) {
            controlOn();
        } else {
            statePowerOff();
        }
    }

    void controlOn() {
        long lastStateChange = 0;
        if (jedis.exists("lastStateChange")) {
            lastStateChange = new Date().getTime() - Long.valueOf(jedis.get("lastStateChange"));
        }
        if (lastStateChange == 0) {
            stateStartup();
        } else if (lastStateChange < STATE_CHANGE_GRACE_MILLISECONDS) {
            stateUnchanged();
        } else {
            if ("startup".equals(jedis.get("solarState"))) {
                stateRecycle();
            } else if ("recycle".equals(jedis.get("solarState"))) {
                if (TflowOut > stateStartTflowOut + 5.0) {
                    stateLargeBoiler();
                }
            } else if (TflowIn > TflowOut) {
                stateUnchanged();
            } else {
                if ("boiler200".equals(jedis.get("solarState"))) {
                    stateSmallBoiler();
                } else if ("boiler200".equals(jedis.get("solarState"))) {
                    stateRecycle();
                } else {
                    //todo  log error
                }
            }
        }

    }

    void readTemperatures() throws IOException {
        if (jedis.exists("pipe.TflowIn") && jedis.exists("pipe.TflowOut")) {
            TflowIn = Double.parseDouble(jedis.get("pipe.TflowIn"));
            TflowOut = Double.parseDouble(jedis.get("pipe.TflowOut"));
        } else {
            stateSystemFailed(); //avoid overheating the pump, shut everything down
            //TODO do logging
            throw new IOException("No control temperature available");
        }
    }

    void stateUnchanged() {
        if ("startup".equals(jedis.get("solarState"))) {
            pin(SOLAR_PUMP_PIN, PinState.HIGH);
            pin(VALVE_I_PIN, PinState.HIGH);
            pin(VALVE_II_PIN, PinState.HIGH);
        } else if ("recycle".equals(jedis.get("solarState"))) {
            pin(SOLAR_PUMP_PIN, PinState.HIGH);
            pin(VALVE_I_PIN, PinState.HIGH);
            pin(VALVE_II_PIN, PinState.HIGH);
        } else if ("boiler500".equals(jedis.get("solarState"))) {
            pin(SOLAR_PUMP_PIN, PinState.HIGH);
            pin(VALVE_I_PIN, PinState.LOW);
            pin(VALVE_II_PIN, PinState.LOW);
        } else if ("boiler200".equals(jedis.get("solarState"))) {
            pin(SOLAR_PUMP_PIN, PinState.HIGH);
            pin(VALVE_I_PIN, PinState.HIGH);
            pin(VALVE_II_PIN, PinState.LOW);
        } else if ("solarpumpOff".equals(jedis.get("solarState"))) {
            pin(SOLAR_PUMP_PIN, PinState.LOW);
            pin(VALVE_I_PIN, PinState.LOW);
            pin(VALVE_II_PIN, PinState.LOW);
        } else  {
            //TODO log error
        }

    }

    void stateStartup() {
        pin(SOLAR_PUMP_PIN, PinState.HIGH);
        pin(VALVE_I_PIN, PinState.HIGH);
        pin(VALVE_II_PIN, PinState.HIGH);
        jedis.set("solarState", "startup");
        jedis.set("lastStateChange", String.valueOf(new Date().getTime()));
    }

    void stateRecycle() {
        pin(SOLAR_PUMP_PIN, PinState.HIGH);
        pin(VALVE_I_PIN, PinState.HIGH);
        pin(VALVE_II_PIN, PinState.HIGH);
        jedis.set("solarState", "recycle");
        jedis.set("lastStateChange", String.valueOf(new Date().getTime()));
        jedis.set("stateStartTflowOut", String.valueOf(TflowOut));
    }

    void stateLargeBoiler() {
        pin(SOLAR_PUMP_PIN, PinState.HIGH);
        pin(VALVE_I_PIN, PinState.LOW);
        pin(VALVE_II_PIN, PinState.LOW);
        jedis.set("solarState", "boiler500");
        jedis.set("lastStateChange", String.valueOf(new Date().getTime()));
        jedis.set("stateStartTflowOut", String.valueOf(TflowOut));
    }

    void stateSmallBoiler() {
        pin(SOLAR_PUMP_PIN, PinState.HIGH);
        pin(VALVE_I_PIN, PinState.HIGH);
        pin(VALVE_II_PIN, PinState.LOW);
        jedis.set("solarState", "boiler200");
        jedis.set("lastStateChange", String.valueOf(new Date().getTime()));
        jedis.set("stateStartTflowOut", String.valueOf(TflowOut));
    }

    void stateSystemFailed() {
        pin(SOLAR_PUMP_PIN, PinState.LOW);
        pin(VALVE_I_PIN, PinState.LOW);
        pin(VALVE_II_PIN, PinState.LOW);
        jedis.set("solarState", "error");
        if (jedis.exists("lastStateChange")) {
            jedis.del("lastStateChange"); //this will force system to startup at new state change
        }
        if (jedis.exists("stateStartTflowOut")) {
            jedis.del("stateStartTflowOut");
        }
    }

    void statePowerOff() {
        pin(SOLAR_PUMP_PIN, PinState.LOW);
        pin(VALVE_I_PIN, PinState.LOW);
        pin(VALVE_II_PIN, PinState.LOW);
        jedis.set("solarState", "solarpumpOff");
        if (jedis.exists("lastStateChange")) {
            jedis.del("lastStateChange"); //this will force system to startup at new state change
        }
        if (jedis.exists("stateStartTflowOut")) {
            jedis.del("stateStartTflowOut");
        }
    }

    void pin(Pin pin, PinState state) {
        final GpioPinDigitalOutput p = gpio.provisionDigitalOutputPin(pin, state);
        p.setShutdownOptions(true, state);
    }

/*
    float setpoint = 0.0;
    float STAGE_ONE_TEMP = 40.0;
    float STAGE_TWO_TEMP = 65.0;
    float STAGE_THREE_TEMP = 80.0;
    float BALANCED_INCREASE_TEMP = 5.0;
    const float COOLING_DOWN_MARGIN = 0.5;

    // ===== North range, VCC: green/white, GND: green
// Tlh, boiler L, high thermometer
    DeviceAddress sensorAddressTlh = {0x28, 0x39, 0x28, 0x8E, 0x5, 0x0, 0x0, 0x5A};
    // Tlm, boiler L, middle thermometer
    DeviceAddress sensorAddressTlm = {0x28, 0xF4, 0x13, 0x8D, 0x5, 0x0, 0x0, 0xF3};
    // Tll, boiler L, low thermometer
    DeviceAddress sensorAddressTll = {0x28, 0xFA, 0x5F, 0x8D, 0x5, 0x0, 0x0, 0x28};
    float sensorTlh, sensorTlm, sensorTll;

// ===== South range, VCC: orange/white, GND: orange

    // Tsl, boiler S, low thermometer
    DeviceAddress sensorAddressTsl = {0x28, 0xC0, 0x60, 0x8D, 0x5, 0x0, 0x0, 0xE1};
    float sensorTsl;
    // Tubes
// Tin, incoming (hot) tube from collector
    DeviceAddress sensorAddressTin = {0x28, 0x2D, 0xA6, 0x8D, 0x5, 0x0, 0x0, 0x3C};
    // Tin, outgoing (cold) tube to collector
    DeviceAddress sensorAddressTout = {0x28, 0x6, 0xBE, 0x8D, 0x5, 0x0, 0x0, 0x30};
    float sensorTin, sensorTout;

/** Normally filter out the 85C readings, unless the last temperature was already near 85
    const float MAX_TEMP_CHANGE_THRESHOLD_85 = 0.2;

// Temperature and control variables
    const float MAX_SOLAR_TEMP = 100.0; //Max temp and the pump will be switched off
    const float MAX_SOLAR_THRESHOLD = 5.0; //Threshold temp for the pump to switch back on when the boiler had reached max temp
    uint32_t noSunPumpStopTime = 0;
    byte maybeSunRetryCycles = 0;
    const byte SUN_RETRY_THRESHOLD = 25;
    const uint32_t PUMP_OFF_NO_SUN_MS = 2800000;

    //Recycle to prevent cooling down boilers
    long recycleStartTime = 0;
    float recycleStartTemperature;
    const uint32_t COOLING_COUNT_TIMEOUT_MS = 2400000;
//const float MIN_IN_OUT_BIAS = 0.1;
    const float MIN_RECYCLE_TEMP_RISE = 5.0;

    boolean solarPumpState = false;
    boolean recyclePumpState = false;
    boolean solarValveIstate = false;
    boolean solarValveIIstate = false;



    void switchSunset(boolean newSunset) {
        if (sunset != newSunset) {
            sunset = newSunset;
            logger.writeUdp("received sunset change");
            if (sunset) {
                logger.writeUdp(", turning solar to sleep");
                maybeSunRetryCycles = 0;
                noSunPumpStopTime = 0;
//        recycleStart = 0;
                setpoint = 0.0;
                switchSolarPump(FALSE);
                delay(1000);
                recycleOn();
            } else {
                logger.writeUdp(", solar awake");
            }
            logger.postUdp();
        }
    }

    /**
     * From start first the small boiler is heated up to STAGE_ONE_TEMP. When this temperature is reached
     * the large boiler is heated to STAGE_ONE_TEMP. Then each  boiler is sequentially increased with BALANCED_INCREASE_TEMP
     * temperature steps. The main consideration is the efficiency of the solar system decreases with temperature
     * rising.
     * TODO: add a low capacity parameter where the large boiler is avoided as long as possible
     * TODO: add weather forecasting to set the STAGE_ONE_TEMP
     *
     * The outflow temperature is used as control sensor because at this point an almost instant reaction to valve changes
     * can be expected.
     *
     * When STAGE_TWO_TEMP is reached the system stops heating the small boiler alltogether. Further heat will be accumulated in
     * the large boiler until it reaches STAGE_THREE_TEMP and it will go into recycle mode. The pump stops when the STOP_TEMP is
     * reached.
     * There is a security override using the large boiler top sensor, when that sensor reaches MAXIMUM_BOILER_TEMP it will switch
     * to recycle for a set time.
     *
     * The heat extraction stops when the temperatre is dropping. This is simply the difference between flowIn and flowOut. If this is
     * the case for both boilers, the valves are set to recycle mode. Once solar starts to generate heat again it will lead to a rise in
     * temperature (either flowIn and flowOut, we use flowIn here) the recycling is stopped.
     *
     * When the sun has set, or the security override is activated, the solar valve control is disabled.
     *
     * The details
     *

    void solarValveControl() {
        if (sunset) {
            // Solar is turned off
            // do nothing
        } else if (securityOverride) {
            // System is stopped to avoid overheating
        } else if (isSmallBoilerState()) {
            // Consider
            // - delta Temp is OK
            //    - and still belongs to small boiler
            //    - or exceeds some threshold
            // - delta Temp is not OK
            if (sensorTin < sensorTout + COOLING_DOWN_MARGIN) {
                logger.postUdp("small boiler cooling down");
                if (sensorTout > sensorTlm) {
                    largeBoilerOn();
                    setpoint = 0.0;
                } else {
                    recycleOn();
                    setpoint = 0.0;
                }
            } else {
                // Boiler is heating up
                // First determine the setpoint
                if (setpoint == 0.0) {
                    if (sensorTout > STAGE_TWO_TEMP) {
                        // break out from small boiler mode
                        logger.postUdp("small boiler reached max");
                        setpoint = 0.0;
                    } else if (sensorTout < STAGE_ONE_TEMP) {
                        setpoint = STAGE_ONE_TEMP;
                    } else {
                        setpoint = sensorTout + BALANCED_INCREASE_TEMP;
                    }
                    logger.writeUdp("setpoint: ");
                    logger.writeUdp(setpoint);
                    logger.postUdp();
                }
                // And perform the control action
                if (sensorTout > setpoint) {
                    logger.postUdp("small boiler reached setpoint");
                    largeBoilerOn();
                    setpoint = 0.0;
                }
                // else: keep heating up this boiler
            }
        } else if (isLargeBoilerState()) {
            if (sensorTin < sensorTout + COOLING_DOWN_MARGIN) {
                // In a cooling down situation do not switch back to the small boiler to avoid switching
                // back and forth small and large until both are the same low temperature
                logger.postUdp("large boiler cooling down");
                recycleOn();
                setpoint = 0.0;
            } else {
                // Boiler is heating up
                // First determine the setpoint
                if (setpoint == 0.0) {
                    if (sensorTout < STAGE_ONE_TEMP) {
                        setpoint = STAGE_ONE_TEMP;
                    } else if (sensorTout > STAGE_TWO_TEMP - BALANCED_INCREASE_TEMP) {
                        setpoint = STAGE_THREE_TEMP;
                    } else {
                        setpoint = sensorTout + BALANCED_INCREASE_TEMP;
                    }
                    logger.writeUdp("setpoint: ");
                    logger.writeUdp(setpoint);
                    logger.postUdp();
                }

                // And perform the control action
                if (sensorTout > STAGE_THREE_TEMP) {
                    logger.postUdp("large boiler reached max");
                    recycleOn();
                    setpoint = 0.0;
                } else if (sensorTout > setpoint) {
                    logger.postUdp("large boiler reached setpoint");
                    smallBoilerOn();
                    setpoint = 0.0;
                }
                // else: keep heating up this boiler
            }
        } else if (isRecycleState()) {
            // When the temperature is rising, switch be to small boiler
            if (sensorTout > recycleStartTemperature + MIN_RECYCLE_TEMP_RISE) {
                logger.postUdp("Recycle temp has been rising");
                largeBoilerOn();
            } else if (millis() > recycleStartTime + COOLING_COUNT_TIMEOUT_MS || millis() < recycleStartTime) {
                logger.postUdp("Recycle time has passed");
                largeBoilerOn();
            }
//Todo add max temp thingy
        } else {
            logger.postUdp("FAILURE: entered unknown mode");
        }
    }

    void setValves() {
        digitalWrite(SOLAR_VALVE_I_RELAY_PIN, !solarValveIstate);
        digitalWrite(SOLAR_VALVE_II_RELAY_PIN, !solarValveIIstate);
    }

    void smallBoilerOn() {
        if (isSmallBoilerState()) {
            //Valves are already switched to the small boiler
        } else {
            solarValveIstate = true;
            solarValveIIstate = false;
            setValves();
            logger.postUdp("valve: small boiler");
            delay(30000); // Let things settle before doing the next reading
        }
    }
    boolean isSmallBoilerState() {
        return solarValveIstate && !solarValveIIstate;
    }

    void largeBoilerOn() {
        if (isLargeBoilerState()) {
            //Valves are already switched to the large boiler
        } else {
            solarValveIstate = false;
            //solarValveIIstate has no function here, so keeping it as it is.
            setValves();
            logger.postUdp("valve: large boiler");
            delay(30000); // Let things settle before doing the next reading
        }
    }
    boolean isLargeBoilerState() {
        return !solarValveIstate;
    }

    void recycleOn() {
        if (isRecycleState()) {
            //Valves are already switched to recycle mode
        } else {
            solarValveIstate = true;
            solarValveIIstate = true;
            maybeSunRetryCycles = 1;
            setValves();
            logger.postUdp("valve: recycle");
            recycleStartTime = millis();
            recycleStartTemperature = sensorTin;
        }
    }
    boolean isRecycleState() {
        return solarValveIstate && solarValveIIstate;
    }

    /**
     * Switches off the solar pump when the temperature gets too high
     * TODO add logic to turn off pump when the sun is not shining

    void solarPumpControl() {
        if (sensorTin > MAX_SOLAR_TEMP) {
            if (solarPumpState) {
                logger.postUdp("max temp: pump off");
                switchSolarPump(FALSE);
            }
        } else {
            if (solarPumpState) {
                if (maybeSunRetryCycles != 0) {
                    if (maybeSunRetryCycles == SUN_RETRY_THRESHOLD) {
                        maybeSunRetryCycles = 0;
                    } else {
                        maybeSunRetryCycles++;
                        logger.postUdp("maybe sun: flush pipe");
                    }
                }

                //When in recycle mode turn off the pump
                if (maybeSunRetryCycles == 0 && isRecycleState()) {
                    logger.postUdp("no sun: pump off");
                    switchSolarPump(FALSE);
                    noSunPumpStopTime = millis();
                }
            } else {
                if (noSunPumpStopTime > 0) {
                    // Turn the pump back on after some time
                    if (noSunPumpStopTime > millis() || noSunPumpStopTime + PUMP_OFF_NO_SUN_MS < millis()) {
                        noSunPumpStopTime = 0;
                        switchSolarPump(TRUE);
                        maybeSunRetryCycles = 1;
                        logger.postUdp("maybe sun: pump on");
                    }
                } else if (sensorTin < (MAX_SOLAR_TEMP - MAX_SOLAR_THRESHOLD)) {
                    logger.postUdp("max temp: pump on");
                    switchSolarPump(TRUE);
                }
            }
        }
    }


    /**
     * Filters typical sensor failure at 85C and -127C

    float filterSensorTemp(float rawSensorTemp, float currentTemp) {
        if (rawSensorTemp == 85.0 && (abs(rawSensorTemp - 85) > MAX_TEMP_CHANGE_THRESHOLD_85)) {
            logger.postUdp("warn: 85.0 C sensor");
            return currentTemp;
        } else if (rawSensorTemp == -127.0) {
            logger.postUdp("warn: -127.0 C sensor");
            return currentTemp;
        } else {
            return rawSensorTemp;
        }
    }

    /**
     * Switches the pin state to the current solarPumpState to the relay pin
     * Before that it will post data to emon.

    void switchSolarPump(boolean state) {
        if (solarPumpState != state) {
            solarPumpState = state;
            digitalWrite(SOLAR_PUMP_RELAY_PIN, !solarPumpState);
            logger.writeUdp("switched solar pump ");
            if (state) {
                logger.writeUdp("on");
            } else {
                logger.writeUdp("off");
            }
            logger.postUdp();
        }
    }

    void setupRelays() {
        // Initialize the relays
        pinMode(SOLAR_PUMP_RELAY_PIN, OUTPUT);
        digitalWrite(SOLAR_PUMP_RELAY_PIN, !solarPumpState);
        pinMode(RECYCLE_PUMP_RELAY_PIN, OUTPUT);
        digitalWrite(RECYCLE_PUMP_RELAY_PIN, !recyclePumpState);
        pinMode(SOLAR_VALVE_I_RELAY_PIN, OUTPUT);
        digitalWrite(SOLAR_VALVE_I_RELAY_PIN, !solarValveIstate);
        pinMode(SOLAR_VALVE_II_RELAY_PIN, OUTPUT);
        digitalWrite(SOLAR_VALVE_II_RELAY_PIN, !solarValveIIstate);
    }
    */
}
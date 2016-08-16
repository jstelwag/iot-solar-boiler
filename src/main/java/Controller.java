import org.apache.commons.math3.stat.regression.SimpleRegression;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.util.Date;
import java.util.List;

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
 */
public class Controller {
    private final Jedis jedis;

    private double TflowIn, TflowOut, stateStartTflowOut, Tslope;

    private final static int STATE_CHANGE_GRACE_MILLISECONDS = 60*1000;

    private final static double MAX_FLOWOUT_TEMP = 95.0;
    private final static long OVERHEAT_TIMEOUT_MS = 30*60*1000; //Set to 30 minutes

    private final static double RECYCLE_MAX_TEMP = 40.0;
    private final static long RECYCLE_TIMEOUT_ON = 10*60*1000;
    private final static long RECYCLE_TIMEOUT_OFF = 20*60*1000;

    private final static double CONTROL_SWAP_BOILER_TEMP_RISE = 10.0;
    private final static double MIN_FLOW_DELTA = 0.5;

    private final static int SLOPE_WINDOW_MS = 10 * 60 * 1000;
    private final static int MIN_OBSERVATIONS = 5;

    private SolarState currentState;

    public Controller() throws IOException {
        jedis = new Jedis("localhost");
        if (jedis.exists("solarState")) {
            currentState = SolarState.valueOf(jedis.get("solarState"));
        }
        readTemperatures();
        pipeTSlope();
        overheatCheck();
        control();
    }

    private void control() {
        Sun sun = new Sun();
        if (!sun.shining()) {
            stateSunset();
        } else if (currentState == SolarState.overheat) {
            overheatControl();
        } else {
            controlOn();
        }
    }

    private void controlOn() {
        long lastStateChange = 0;
        if (jedis.exists("lastStateChange")) {
            lastStateChange = new Date().getTime() - Long.valueOf(jedis.get("lastStateChange"));
        }
        if (lastStateChange == 0) {
            stateStartup();
        } else if (lastStateChange < STATE_CHANGE_GRACE_MILLISECONDS) {
            // Do nothing! After a state change, allow for the system to settle in
        } else {
            // Grace time has passed. Let's see what we can do now
            if (currentState == SolarState.startup) {
                stateLargeBoiler();
            } else if (currentState == SolarState.recycle) {
                if (TflowOut > stateStartTflowOut + 5.0) {
                    // Recycle is heating up, try again
                    stateLargeBoiler();
                } else if (lastStateChange > RECYCLE_TIMEOUT_ON && TflowOut < RECYCLE_MAX_TEMP) {
                    stateRecycleTimeout();
                }
            } else if (currentState == SolarState.recycleTimeout) {
                if (lastStateChange > RECYCLE_TIMEOUT_OFF) {
                    stateRecycle();
                }
            } else if (TflowIn > TflowOut + MIN_FLOW_DELTA) {
                if (stateStartTflowOut + CONTROL_SWAP_BOILER_TEMP_RISE < TflowOut) {
                    //Time to switch to another boiler
                    if (currentState == SolarState.boiler500) {
                        stateSmallBoiler();
                    } else {
                        stateLargeBoiler();
                    }
                }
                // Do nothing while heat is exchanged
            } else {
                if (currentState == SolarState.boiler500) {
                    // Large boiler is not heating up, try the smaller boiler
                    stateSmallBoiler();
                } else if (currentState == SolarState.boiler200) {
                    stateRecycle();
                } else {
                    LogstashLogger.INSTANCE.message("ERROR: Unexpected solar state " + currentState
                            + " I will go into recycle mode");
                    stateRecycle();
                }
            }
        }
    }

    private void overheatCheck() {
        if (TflowOut > MAX_FLOWOUT_TEMP) {
            stateOverheat();
        }
    }

    private void overheatControl() {
        if (new Date().getTime() - Long.valueOf(jedis.get("lastStateChange")) > OVERHEAT_TIMEOUT_MS) {
            LogstashLogger.INSTANCE.message("Ending overheat status, switching to boiler500");
            stateLargeBoiler();
        }
    }

    private void readTemperatures() throws IOException {
        if (jedis.exists("pipe.TflowIn") && jedis.exists("pipe.TflowOut")) {
            TflowIn = Double.parseDouble(jedis.get("pipe.TflowIn"));
            TflowOut = Double.parseDouble(jedis.get("pipe.TflowOut"));
        } else {
            stateError(); //avoid overheating the pump, shut everything down
            LogstashLogger.INSTANCE.message("ERROR: no temperature readings available, going into error state");
            throw new IOException("No control temperature available");
        }
        if (jedis.exists("stateStartTflowOut")) {
            stateStartTflowOut = Double.parseDouble(jedis.get("stateStartTflowOut"));
        }
    }

    private void stateStartup() {
        jedis.set("solarState", SolarState.startup.name());
        jedis.set("lastStateChange", String.valueOf(new Date().getTime()));
        LogstashLogger.INSTANCE.message("Going into startup state");
    }

    private void stateRecycle() {
        jedis.set("solarState", SolarState.recycle.name());
        jedis.set("lastStateChange", String.valueOf(new Date().getTime()));
        jedis.set("stateStartTflowOut", String.valueOf(TflowOut));
        LogstashLogger.INSTANCE.message("Going into recycle state");
    }

    private void stateRecycleTimeout() {
        jedis.set("solarState", SolarState.recycleTimeout.name());
        jedis.set("lastStateChange", String.valueOf(new Date().getTime()));
        jedis.set("stateStartTflowOut", String.valueOf(TflowOut));
        LogstashLogger.INSTANCE.message("Going into recycle timeout state");
    }

    private void stateLargeBoiler() {
        jedis.set("solarState", SolarState.boiler500.name());
        jedis.set("lastStateChange", String.valueOf(new Date().getTime()));
        jedis.set("stateStartTflowOut", String.valueOf(TflowOut));
        LogstashLogger.INSTANCE.message("Switching to boiler500");
    }

    private void stateSmallBoiler() {
        jedis.set("solarState", SolarState.boiler200.name());
        jedis.set("lastStateChange", String.valueOf(new Date().getTime()));
        jedis.set("stateStartTflowOut", String.valueOf(TflowOut));
        LogstashLogger.INSTANCE.message("Switching to boiler200");
    }

    private void stateError() {
        if (currentState != SolarState.error) {
            jedis.set("solarState", SolarState.error.name());
            if (jedis.exists("lastStateChange")) {
                jedis.del("lastStateChange"); //this will force system to startup at new state change
            }
            if (jedis.exists("stateStartTflowOut")) {
                jedis.del("stateStartTflowOut");
            }
            LogstashLogger.INSTANCE.message("Going into error state");
        }
    }

    private void stateOverheat() {
        if (currentState != SolarState.overheat) {
            jedis.set("solarState", SolarState.overheat.name());
            jedis.set("lastStateChange", String.valueOf(new Date().getTime()));
            jedis.set("stateStartTflowOut", String.valueOf(TflowOut));
            LogstashLogger.INSTANCE.message("Going into overheat state");
        }
    }

    private void stateSunset() {
        if (currentState != SolarState.sunset) {
            jedis.set("solarState", SolarState.sunset.name());
            LogstashLogger.INSTANCE.message("Going into sunset state, " + new Sun());
            if (jedis.exists("lastStateChange")) {
                jedis.del("lastStateChange"); //this will force system to startup at new state change
            }
            if (jedis.exists("stateStartTflowOut")) {
                jedis.del("stateStartTflowOut");
            }
        }
    }

    private void pipeTSlope() {
        if (jedis.llen("pipe.TflowSetMS") >= MIN_OBSERVATIONS) {
            SimpleRegression regression = new SimpleRegression();
            List<String> pipeTemperatures = jedis.lrange("pipe.TflowSetMS", 0, SolarSlave.T_SET_LENGTH);
            for (String pipeTemperature : pipeTemperatures) {
                long time = (long)Double.parseDouble(pipeTemperature.split(":")[0]);
                if (time > new Date().getTime() - SLOPE_WINDOW_MS) {
                    regression.addData((double) time, Double.parseDouble(pipeTemperature.split(":")[1]));
                }
            }
            if (regression.getN() >= MIN_OBSERVATIONS) {
                Tslope = regression.getSlope()/(60*60*1000);
                jedis.setex("pipe.Tslope_per_hr", Properties.redisExpireSeconds, String.valueOf(Tslope));
                jedis.setex("pipe.TstandardDeviation", Properties.redisExpireSeconds
                        , String.valueOf(regression.getSlopeStdErr()/(60*60*1000)));
            } else {
                LogstashLogger.INSTANCE.message("Not enough recent observations (" + regression.getN()
                        + ") for slope calculation");
            }
        } else {
            LogstashLogger.INSTANCE.message("Not yet enough observations for slope calculation");
        }
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
    */
}

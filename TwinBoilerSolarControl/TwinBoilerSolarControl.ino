/*
    Copyright 2016 Jaap Stelwagen
    
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
#include <SPI.h>
#include <Ethernet.h>
#include <EEPROM.h>
#include <UdpLogger.h>
#include <OneWire.h>
#include <DallasTemperature.h>

  /**
  * Solar boiler control
  *
  * This sketch controls a solar heat collector with two boilers.
  * The controller divides the heat among the two boilers, updates every 30 seconds through a home monitor the temperatures
  * to a InfluxDB time series database. And is uses UDP logging of events to a logstash endpoint that is connect to Elastic 
  * Search (AKA the ELK stack).
  * 
  * The solar is controlled mainly by the inflow and outflow temperatures. This is more accurate than boiler temperature sensors. A
  * secondary control (available through the home monitor) is the sun location indicator.
  * 
  * With InfluxDB and Grafana you can visualize the solar system performance. And with the ELK stack you can monitor the solar systems
  * events.
  *
  * Why two boilers?
  * One might wonder, why not a single boiler?
  * Well, for a larger solar collector a single 500 liter boiler does not suffice. And a larger boiler than 500 liter would not fit in my cellar.
  * Further you will need a boiler with auxiliary heating coil in case of a rainy day. When the auxilary heater it makes sense to have just a
  * small boiler because a 20 kW+ heater can keep up with common domestic water use.
  *
  * Setup
  * 
  * 
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
  *
  * Data logging
  * Every 30 seconds state and temperatures are logged to an instance of InfluxDB. The interface is a straightforward 'line protocol', a sort of CSV
  * interface transported by TCP and HTTP to the server in a fire-and-forget style. Details on this server have to be stored in the Arduino's EEPROM 
  * memory (see the Properties struct)/
  */

// Pin configuration
// The ethernet shield uses pins 10, 11, 12, and 13 for SPI communication
// Pin 4 is used to communicate with the SD card (unused)
const byte ONE_WIRE_NORTH_PIN = 2;
const byte ONE_WIRE_SOUTH_PIN = 9;
const byte SOLAR_PUMP_RELAY_PIN = 3; //the relay output pin
const byte RECYCLE_PUMP_RELAY_PIN = 4; //the relay output pin for the hot water recycle pump
const byte SOLAR_VALVE_I_RELAY_PIN = 5;   //Valve I, the first solar three way valve
const byte SOLAR_VALVE_II_RELAY_PIN = 6;  //Valve II, the second solar three way valve

#define flash_props // Writes property setting into eeprom memory

struct SolarProperties {
  char name[20];
  uint8_t macAddress[6];
  IPAddress ip;
  IPAddress gateway;
  IPAddress masterIP;
  uint16_t masterPort;
};

EthernetUDP udp;
UdpLogger logger(sizeof(SolarProperties) + 1);

const int POSTING_INTERVAL = 30000; // delay between updates, in milliseconds
unsigned long lastPostTime;
EthernetClient client;

// Control variables
boolean sunset = false;
const long DISCONNECT_TIMOUT = 1800000;
long lastConnectTime;
boolean securityOverride = false; //todo implement
float setpoint = 0.0;
float STAGE_ONE_TEMP = 40.0;
float STAGE_TWO_TEMP = 65.0;
float STAGE_THREE_TEMP = 80.0;
float BALANCED_INCREASE_TEMP = 5.0;
const float COOLING_DOWN_MARGIN = 0.5;

// Thermometer devices DALLAS DS18B20+ with the OneWire protocol
// It seems the one wire cannot be too long. So i have two wires to devide the load.
OneWire oneWireNorth(ONE_WIRE_NORTH_PIN);
OneWire oneWireSouth(ONE_WIRE_SOUTH_PIN);
DallasTemperature northernSensors(&oneWireNorth);
DallasTemperature southernSensors(&oneWireSouth);

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

/** Normally filter out the 85C readings, unless the last temperature was already near 85 */
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

void setup() {
  #ifdef flash_props
    IPAddress ip(10, 0, 30, 21);
    IPAddress gateway(10, 0, 30, 1);
    IPAddress masterController(192, 168, 178, 18);
    SolarProperties spIn = {"koetshuis", {0xAA, 0xAA, 0xDE, 0x1E, 0xAE, 0x11}, ip, gateway, masterController, 9999};
    EEPROM.put(0, spIn);
  #endif

  SolarProperties sp;
  EEPROM.get(0, sp);
  IPAddress dns(8, 8, 8, 8);
  Ethernet.begin(sp.macAddress, sp.ip, dns, sp.gateway);

  #ifdef has_log
    #ifdef flash_props
      LogProperties lp = {"SolarControl", "koetshuis", IPAddress(192, 168, 178, 101), 9000};
      logger.flash(lp);
    #endif
    logger.setup();
    logger.postUdp("start");
  #endif
  logger.setup();

  setupRelays();

  // Start with the small boiler to settle things at startup
  logger.postUdp("start");
  recycleOn();
  switchSolarPump(TRUE);
  lastConnectTime = millis(); //assume connection has been successful
  delay(30000);
}

void loop() {
  readSensors();
  if (!sunset) {
    solarPumpControl();
  }
  
  solarValveControl();
  
  if (millis() > lastPostTime + POSTING_INTERVAL || millis() < lastPostTime) {
    request();
    lastPostTime = millis();
    //delay(200); //todo maybe remove
  }
  receive();
  if (millis() > lastConnectTime) {
    lastConnectTime = millis();
  } else if (millis() > lastConnectTime + DISCONNECT_TIMOUT) {
    sunset = false;
    logger.postUdp("Controller lost connection with master");
  }
}

void request() {
  client.stop();
  SolarProperties prop;
  EEPROM.get(0, prop);
  
  if (client.connect(prop.masterIP, prop.masterPort)) {
    client.print("POST /solar/");
    client.print(prop.name);
    client.println(" HTTP/1.1");
    client.println("Host: iot");
    client.println("Content-Length: 211");
    client.println(); //This line is mandatory (for a Jetty server at least)

    client.print("boiler500.temperature Tbottom=");   //30
    client.print(sensorTll);                          //5
    client.print(",Tmiddle=");                        //9
    client.print(sensorTlm);                          //5
    client.print(",Ttop=");                           //6
    client.println(sensorTlh);                        //5
    
    client.print("pipe_temperature TflowIn=");        //25
    client.print(sensorTin);                          //5
    client.print(",TflowOut=");                       //10
    client.println(sensorTout);                       //5
   
    client.print("solarstate,circuit=boiler500 value=");  //35
    if (solarPumpState && isLargeBoilerState()) {     //1
      client.println("1");
    } else {
      client.println("0");
    }
    
    client.print("solarstate,circuit=boiler200 value=");  //35
    client.println((solarPumpState && isSmallBoilerState()) ? "1" : "0"); //1
    
    client.print("solarstate,circuit=recycle value=");    //33
    client.print((solarPumpState && isRecycleState()) ? "1" : "0");  //1
                                                       //Total 211                                
    for (int s = 1; s < 50; s++) {      //fill up with space, the body is truncated in the server by content length
      client.print(" ");
    }
  } else {
    client.stop();
    logger.postUdp("request failed");
  }
}

void receive() {
  boolean startReading = false;
  boolean received = false;
  boolean _sunset = false;
  while (client.available()) {
    char c = client.read();
    if (startReading) {
      if (c == '}') {
        client.flush();
        received = true;
        lastConnectTime = millis();
      } else {
        _sunset = (c == 48);
      } 
    } else if (c == '{') {
      startReading = true;
    }
  }
  if (received) {
    switchSunset(_sunset);
  }
}

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
*/
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
*/ 
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
* Set the senso readings in their global variables.
* Remove the 85C error sensors sometimes return.
*/
void readSensors() {
  northernSensors.begin();
  northernSensors.requestTemperatures();
  
  // Boilter L
  sensorTlh = filterSensorTemp(northernSensors.getTempC(sensorAddressTlh), sensorTlh);
  sensorTlm = filterSensorTemp(northernSensors.getTempC(sensorAddressTlm), sensorTlm);
  sensorTll = filterSensorTemp(northernSensors.getTempC(sensorAddressTll), sensorTll);

  southernSensors.begin();
  southernSensors.requestTemperatures();
  // Boilter S
  //sensorTsl = filterSensorTemp(southernSensors.getTempC(sensorAddressTsl), sensorTsl);
//todo fix below dummy
sensorTsl = 99.0;
  // Tubes
  sensorTin = filterSensorTemp(southernSensors.getTempC(sensorAddressTin), sensorTin);
  sensorTout = filterSensorTemp(southernSensors.getTempC(sensorAddressTout), sensorTout);
}

/**
* Filters typical sensor failure at 85C and -127C
*/
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
*/
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

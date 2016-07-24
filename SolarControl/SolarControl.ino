/*
    Copyright 2015 Jaap Stelwagen
    
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
  * This sketch controls a solar heat collector with two boilers and after heater connected to the central heating furnace.
  * This sketch also controls the solar pump, the hot water recycle pump and pushes data to the cloud.
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

#define has_log // remove his to disable udp logging events to elastic search
#define ntest_relays // remove the NO to test the relays
#define flash_props // Writes property setting into eeprom memory

struct SolarProperties {
  char name[20];
  uint8_t macAddress[6];
  IPAddress masterIP;
  uint16_t masterPort;
};

#ifdef has_log
UdpLogger logger(sizeof(SolarProperties) + 1);
#endif

const int POSTING_INTERVAL = 30000; // delay between updates, in milliseconds
unsigned long lastPostTime;
boolean received = false;
EthernetClient client;
char receiveBuffer[3];
byte bufferPosition;

byte logCount;

boolean sunset = false;

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
boolean solarPumpState = true;
const float MAX_SOLAR_TEMP = 100.0; //Max temp and the pump will be switched off
const float MAX_SOLAR_THRESHOLD = 5.0; //Threshold temp for the pump to switch back on when the boiler had reached max temp
uint32_t noSunPumpStopTime = 0;
byte maybeSunRetryCycles = 0;
const byte SUN_RETRY_THRESHOLD = 7;
const uint32_t PUMP_OFF_NO_SUN_MS = 2800000;

// Boundaries for boiler temperature
const float MAX_BOILER_TEMP = 70.0;
const float MAX_SUPER_BOILER_TEMP = 90.0;

//Prevent valves from switching on and off al the time
const float VALVE_ON_THRESHOLD_C = 2.0; 
const float VALVE_OFF_THRESHOLD_C = 0.5;

//Recycle to prevent cooling down boilers
long recycleStart = 0;
float lastInTemperature;
const uint32_t COOLING_COUNT_TIMEOUT_MS = 2400000;
const float MIN_IN_OUT_BIAS = 0.1;
const float MIN_SOLAR_TEMP_RISE = 3.0;

boolean recyclePumpState = false;
boolean solarValveIstate = false;
boolean solarValveIIstate = false;

#ifdef has_log
EthernetUDP udp;
#endif

void setup() {
  Serial.begin(9600);

  #ifdef flash_props
    IPAddress masterIP(192, 168, 178, 18);
    SolarProperties spIn = {"solar_koetshuis", {0xAA, 0xAA, 0xDE, 0x1E, 0xAE, 0x11}, masterIP, 8888};
    EEPROM.put(0, spIn);
  #endif

  SolarProperties sp;
  EEPROM.get(0, sp);
  dhcp(sp.macAddress);

  #ifdef has_log
    #ifdef flash_props
      LogProperties lp = {"SolarControl", "koetshuis", IPAddress(192, 168, 178, 101), 9000};
      logger.flash(lp);
    #endif
    logger.setup();
    logger.postUdp("start");
  #endif

  testSensors();

  setupRelays();
  
  #ifdef test_relays
    testRelays();
  #endif
}

void loop() {
  readSensors();
  if (!sunset) {
    solarPumpControl();
    //smallBoilerSolarOn();
    //  digitalWrite(SOLAR_VALVE_I_RELAY_PIN, !solarValveIstate);
    //  digitalWrite(SOLAR_VALVE_II_RELAY_PIN, !solarValveIIstate);

    solarValveControl();
  }
  
  if (millis() > lastPostTime + POSTING_INTERVAL || millis() < lastPostTime) {
    request();
    //delay(100); //todo maybe remove
  }

  receive();

  //todo remove
  delay(15000); // control 'debouncer', give valves and temperatures a little time to settle
}


void request() {
  Serial.print(F("post"));
#ifdef has_log
  logger.postUdp("Logging to master");
#endif
  
  client.stop();
  bufferPosition = 0;
  for (byte b = 0; b < sizeof(receiveBuffer); b++) {
    receiveBuffer[b] = '\0';
  }
  
  SolarProperties prop;
  EEPROM.get(0, prop);

  if (client.connect(prop.masterIP, prop.masterPort)) {
    client.print(prop.name);
    client.print(":");
    client.print("boiler500.temperature Tbottom=");
    client.print(sensorTll);
    client.print(",Tmiddle=");
    client.print(sensorTlm);
    client.print(",Ttop=");
    client.print(sensorTlh);
/*
    client.print("\npipe_temperature TflowIn=");
    client.print(sensorTin);
    client.print(",TflowOut=");
    client.print(sensorTout);
    
    client.print("\nsolarstate,circuit=boiler500 value=");
    if (!solarPumpState) {
      client.print("0");
    } else if (!solarValveIstate) {
      client.print("1");
    } else if (!solarValveIIstate) {
      client.print("0");
    } else {
      client.print("0");
    }
    
    client.print("\nsolarstate,circuit=boiler200 value=");
    if (!solarPumpState) {
      client.print("0");
    } else if (!solarValveIstate) {
      client.print("0");
    } else if (!solarValveIIstate) {
      client.print("1");
    } else {
      client.print("0");
    }
    */
    client.print("solarstate,circuit=recycle value=");
    if (!solarPumpState) {
      client.println("0");
    } else if (!solarValveIstate) {
      client.println("0");
    } else if (!solarValveIIstate) {
      client.println("0");
    } else {
      client.println("1");
    }
    Serial.println(F("ed"));
  } else {
    Serial.println(F(" failed"));
    client.stop();
    Ethernet.maintain();
#ifdef has_log
    logger.postUdp("Post failed");
#endif
  }

  lastPostTime = millis();
}

void receive() {
  Serial.println(F("r"));
  if (client.available()) {
    Serial.print(F("receive"));
    while (client.available()) {
      char c = client.read();
      if (c == 'E') {
        client.flush();
        received = true;
        Serial.println(F("d"));
      } else {
        receiveBuffer[bufferPosition] = c;
        bufferPosition++;
      }
    }
  }

  if (received) {
    if (sunset != (receiveBuffer[0] - 48)) {
      sunset = !sunset;
#ifdef has_log
      logger.writeUdp("received sunset change, sunset value: ");
      logger.writeUdp(receiveBuffer[0]-48);
#endif
      if (!sunset) {
#ifdef has_log
        logger.writeUdp(", turning solar to sleep");
#endif
        maybeSunRetryCycles = 0;
        noSunPumpStopTime = 0;
        recycleStart = 0;
        solarPumpState = FALSE;
        switchSolarPump();
      } else {
#ifdef has_log
        logger.writeUdp(", solar awake");
#endif
      }
#ifdef has_log
      logger.postUdp();
#endif
    }
    received = false;
  }
}


/**
* The small boiler is controlled by the low temperature sensor for solar, the high
* sensor is used for the furnace heating cooil
* The large boiler is controlled with the middle temperature sensor, this is located at the
* top of the coil. 
*/
void solarValveControl() {
  if (!solarPumpState) {
    // Solar is turned off
    // do nothing
  } else if (recycleStart > 0 || sensorTin + MIN_IN_OUT_BIAS < sensorTout) {
    // In recycle mode OR inflow temperature is lower then outflow (cooling down)
    if (recycleStart == 0) {
      // I will go in recycle mode now, remember the last out temperature
      Serial.println(F("recycle ccoldown"));
#ifdef has_log
      logger.writeUdp("switch: recycle, reason: flow in larger than out, value: ");
      logger.writeUdp(sensorTin - sensorTout);
      logger.postUdp();
#endif
      lastInTemperature = sensorTin;
      recycleStart = millis();
    } else if (millis() - recycleStart > COOLING_COUNT_TIMEOUT_MS || millis() < recycleStart) {
      // Don't recycle too long, give it another try
      Serial.print(F("timeout recycle "));
      Serial.println(millis() - recycleStart);
#ifdef has_log
      logger.writeUdp("switch: recycle-off, reason: timeout, value: ");
      logger.writeUdp(millis() - recycleStart);
      logger.postUdp();
#endif
      recycleStart = 0;
    } else if (sensorTin > lastInTemperature + MIN_SOLAR_TEMP_RISE) {
      // if the temperature is going up, stop recycling
      Serial.println(F("Solar temp rise"));
#ifdef has_log
      logger.writeUdp("switch: recycle-off, reason: temp rise, value: ");
      logger.writeUdp(sensorTin - lastInTemperature);
      logger.postUdp();
#endif
      recycleStart = 0;
    }
    recycleSolarOn();
  } else if (!solarValveIstate) {
    // The large boiler is being warmed now
    // Strategy
    // Keep heating this boiler unless: it is actually cooling off or it is over heating
    // For over heating there is a dual approach, first both boilers can reach normal max 
    // temperature, after that, the large boiler is used as heat overflow... until the finally
    // the system will turn off and go in recycle mode
    
    if (sensorTin < sensorTll + VALVE_OFF_THRESHOLD_C) {
      // Boiler is cooling down. Either switch to the small boiler or recycle
      if (sensorTin > sensorTsl + VALVE_ON_THRESHOLD_C && sensorTsl < MAX_BOILER_TEMP) {
        smallBoilerSolarOn();
      } else {
        recycleSolarOn();
      }
    } else if (sensorTlh > MAX_BOILER_TEMP) {
      if (sensorTin > sensorTsl + VALVE_ON_THRESHOLD_C && sensorTsl < MAX_BOILER_TEMP) {
        smallBoilerSolarOn();
      } else if (sensorTlm > MAX_SUPER_BOILER_TEMP) {
        recycleSolarOn();
      }
    }
  } else if (!solarValveIIstate) {
    // The small boiler is currently being warmed
    // Strategy
    // Keep heating this boiler unless: it is actually cooling off or it is over heating
    if (sensorTin < sensorTsl + VALVE_OFF_THRESHOLD_C) {
      // Boiler is cooling down. Either switch to the large boiler or recycle
      if (sensorTin > sensorTll + VALVE_ON_THRESHOLD_C && sensorTlh < MAX_SUPER_BOILER_TEMP) {
        largeBoilerSolarOn();
      } else {
        recycleSolarOn();
      }
    } else if (sensorTsl > MAX_BOILER_TEMP) {
      if (sensorTin > sensorTll + VALVE_ON_THRESHOLD_C && sensorTlh < MAX_SUPER_BOILER_TEMP) {
        largeBoilerSolarOn();
      } else {
        recycleSolarOn();
      }
    }
  } else {
    // Running in recycle mode now
    // Strategy
    // Try first the small boiler, then the large boiler if it can be heated
    if (sensorTin > sensorTsl + VALVE_ON_THRESHOLD_C && sensorTsl < MAX_BOILER_TEMP) {
      smallBoilerSolarOn();
    } else if (sensorTin > sensorTll + VALVE_ON_THRESHOLD_C && sensorTlh < MAX_SUPER_BOILER_TEMP) {
      largeBoilerSolarOn();
    }
  }
  digitalWrite(SOLAR_VALVE_I_RELAY_PIN, !solarValveIstate);
  digitalWrite(SOLAR_VALVE_II_RELAY_PIN, !solarValveIIstate);
}

void smallBoilerSolarOn() {
  if (solarValveIstate && !solarValveIIstate) {
    //Valves are already switched to the small boiler
  } else {
    solarValveIstate = true;
    solarValveIIstate = false;
#ifdef has_log
    logger.postUdp("valve: small boiler");
#endif
    Serial.println(F("small boiler"));    
  }
}

void largeBoilerSolarOn() {
  if (!solarValveIstate) {
    //Valves are already switched to the large boiler
  } else {
    solarValveIstate = false;
    //solarValveIIstate has no function here, so keeping it as it is.
#ifdef has_log
    logger.postUdp("valve: large boiler");
#endif
    Serial.println(F("large boiler"));    
  }
}

void recycleSolarOn() {
  if (solarValveIstate && solarValveIIstate) {
    //Valves are already switched to recycle mode
  } else {
    solarValveIstate = true;
    solarValveIIstate = true;
    maybeSunRetryCycles = 1;
#ifdef has_log
    logger.postUdp("valve: recycle");
#endif    
    Serial.println(F("recycle"));    
  }
}

/**
* Switches off the solar pump when the temperature gets too high
* TODO add logic to turn off pump when the sun is not shining
*/ 
void solarPumpControl() {
  if (sensorTin > MAX_SOLAR_TEMP) {
    if (solarPumpState) {
      solarPumpState = FALSE;
#ifdef has_log
      logger.postUdp("max temp: pump off");
#endif
      switchSolarPump();
    }
  } else {
    if (solarPumpState) {
      if (maybeSunRetryCycles != 0) {
        if (maybeSunRetryCycles == SUN_RETRY_THRESHOLD) {
          maybeSunRetryCycles = 0;
        } else {
          maybeSunRetryCycles++;
#ifdef has_log
          logger.postUdp("maybe sun: flush pipe");  
#endif
        }
      }

      //When in recycle mode turn off the pump
      if (maybeSunRetryCycles == 0 && solarValveIstate && solarValveIIstate) {
        solarPumpState = FALSE;
#ifdef has_log
        logger.postUdp("no sun: pump off");
#endif
        switchSolarPump();
        noSunPumpStopTime = millis();
      }
    } else {
      if (noSunPumpStopTime > 0) {
        // Turn the pump back on after some time
        if (noSunPumpStopTime > millis() || noSunPumpStopTime + PUMP_OFF_NO_SUN_MS < millis()) {
          solarPumpState = TRUE;
          noSunPumpStopTime = 0;
          switchSolarPump(); 
          maybeSunRetryCycles = 1;
#ifdef has_log
          logger.postUdp("maybe sun: pump on");       
#endif
        }
      } else if (sensorTin < (MAX_SOLAR_TEMP - MAX_SOLAR_THRESHOLD)) {
        solarPumpState = TRUE;
#ifdef has_log
        logger.postUdp("max temp: pump on");
#endif
        switchSolarPump();
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
  if (++logCount > 5) {
    logTemp();
    logCount = 1;
  }
}

/**
* Filters typical sensor failure at 85C and -127C
*/
float filterSensorTemp(float rawSensorTemp, float currentTemp) {
  if (rawSensorTemp == 85.0 && (abs(rawSensorTemp - 85) > MAX_TEMP_CHANGE_THRESHOLD_85)) {
#ifdef has_log
    logger.postUdp("warn: 85.0 C sensor");
#endif
    Serial.println(F("Ignoring 85.0 C"));
    return currentTemp;
  } else if (rawSensorTemp == -127.0) {
#ifdef has_log
    logger.postUdp("warn: -127.0 C sensor");
#endif
    Serial.println(F("Ignoring -127.0 C"));
    return currentTemp;
  } else {
    return rawSensorTemp;
  }
}

void logTemp() {
  Serial.print(F("Tlh:"));
  Serial.print(sensorTlh);
  Serial.print(F(",Tlm:"));
  Serial.print(sensorTlm);
  Serial.print(F(",Tll:"));
  Serial.print(sensorTll); 
  Serial.print(F(",Tsl:"));
  Serial.print(sensorTsl); 
  Serial.print(F(",Tin:"));
  Serial.print(sensorTin);
  Serial.print(F(",Tout:"));
  Serial.println(sensorTout); 
}

/**
* Switches the pin state to the current solarPumpState to the relay pin
* Before that it will post data to emon.
*/
void switchSolarPump() {
  digitalWrite(SOLAR_PUMP_RELAY_PIN, !solarPumpState);   
  Serial.println(F("solar pump"));
}

void dhcp(uint8_t *macAddress) {
  Serial.print(F("Connecting dhcp"));
  delay(1000); // give the ethernet module time to boot up
  if (Ethernet.begin(macAddress) == 0) {
    Serial.println(F(" failed"));
  }
  else {
    Serial.println(F(" success"));
  }
}

void setupRelays() {
  // Initialize the relays
  pinMode(SOLAR_PUMP_RELAY_PIN, OUTPUT);
  pinMode(RECYCLE_PUMP_RELAY_PIN, OUTPUT);
  pinMode(SOLAR_VALVE_I_RELAY_PIN, OUTPUT);
  pinMode(SOLAR_VALVE_II_RELAY_PIN, OUTPUT);
}

#ifdef test_relays
void testRelays() {
  digitalWrite(SOLAR_PUMP_RELAY_PIN, false);
  delay(000);
  digitalWrite(SOLAR_PUMP_RELAY_PIN, true);

  digitalWrite(RECYCLE_PUMP_RELAY_PIN, false);
  delay(1000);
  digitalWrite(RECYCLE_PUMP_RELAY_PIN, true);

  digitalWrite(SOLAR_VALVE_I_RELAY_PIN, false);
  delay(1000);
  digitalWrite(SOLAR_VALVE_I_RELAY_PIN, true);

  digitalWrite(SOLAR_VALVE_II_RELAY_PIN, false);
  delay(1000);
  digitalWrite(SOLAR_VALVE_II_RELAY_PIN, true);

  delay(1000);

  digitalWrite(SOLAR_PUMP_RELAY_PIN, !solarPumpState);
  digitalWrite(RECYCLE_PUMP_RELAY_PIN, !recyclePumpState);
  digitalWrite(SOLAR_VALVE_I_RELAY_PIN, !solarValveIstate);
  digitalWrite(SOLAR_VALVE_II_RELAY_PIN, !solarValveIIstate);
}
#endif

void testSensors() {
  readSensors();
  logTemp();
}

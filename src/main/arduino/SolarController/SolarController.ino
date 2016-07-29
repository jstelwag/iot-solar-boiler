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
#include <OneWire.h>
#include <DallasTemperature.h>

  /**
  * Solar boiler slave control
  * This controller functions as a bridge between the sensors / actuators and the master control (iot-solar-boiler). The connection is by Serial.
  * If the connection is lost, the slave will fall back to a native control algoritm.
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

// Control variables
const long DISCONNECT_TIMOUT = 1800000;
long lastConnectTime;
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
  Serial.begin(9600);
  setupRelays();
  lastConnectTime = millis(); //assume connection has been successful
  Serial.println("log: solar microcontroller has started");
}

void loop() {
  readSensors();
  uploadToMaster();
  receiveFromMaster();

  // Override when connection with master is lost
  if (lastConnectTime + DISCONNECT_TIMOUT < millis()) {
    //Connection lost, go to native mode
    solarValveControl();
    Serial.println(F("log: lost connection, going native control"));
  }

  // Override when the temperature is too high
  if (sensorTout > MAX_SOLAR_TEMP) {
      solarPumpState = false;
      setState();
      Serial.println(F("log: native shutdown from high temperature"));
  }
}

void uploadToMaster() {
  Serial.print(sensorTlh);
  Serial.print(':');
  Serial.print(sensorTlm);
  Serial.print(':');
  Serial.print(sensorTll);
  Serial.print(':');
  Serial.print(sensorTin);
  Serial.print(':');
  Serial.println(sensorTout);
}

void receiveFromMaster() {
  //line format: [T|F] [valveI][valveII][solarPump]
  boolean receivedStates[3];
  short i = 0;
  while (Serial.available()) {
    char c = Serial.read();
    if (i < 3) {
      receivedStates[i] = (c == 'T');
    }
    i++;
  }

  if (i == 4) {
    lastConnectTime = millis();
    solarPumpState = receivedStates[2];
    solarValveIstate = receivedStates[0];
    solarValveIIstate = receivedStates[1];
    setState();
  } else {
    Serial.println(F("log: received unexpected master command"));
  }
}

/**
 * Native control, activated when the master is not connected
 * Either the system is in recycle mode or it is heating up the large boiler
 * 
*/
void solarValveControl() {
  if (isLargeBoilerState()) {
    if (sensorTin < sensorTout + COOLING_DOWN_MARGIN) {
      // In a cooling down situation do not switch back to the small boiler to avoid switching
      // back and forth small and large until both are the same low temperature
      recycleOn();
    }
  } else if (isRecycleState()) {
    // When the temperature is rising, switch be to small boiler
    if (sensorTout > recycleStartTemperature + MIN_RECYCLE_TEMP_RISE) {
      largeBoilerOn();
    } else if (millis() > recycleStartTime + COOLING_COUNT_TIMEOUT_MS || millis() < recycleStartTime) {
      largeBoilerOn();      
    }
  }
}

void setState() {
  digitalWrite(SOLAR_VALVE_I_RELAY_PIN, !solarValveIstate);
  digitalWrite(SOLAR_VALVE_II_RELAY_PIN, !solarValveIIstate);
  digitalWrite(SOLAR_PUMP_RELAY_PIN, !solarPumpState);
}

void largeBoilerOn() {
  if (isLargeBoilerState()) {
    //Valves are already switched to the large boiler
  } else {
    solarValveIstate = false;
    solarValveIIstate = false;
    solarPumpState = true;
    setState();
    Serial.println("log: turned native large boiler on");
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
    solarPumpState = true;
    maybeSunRetryCycles = 1;
    setState();
    recycleStartTime = millis();
    recycleStartTemperature = sensorTin;
    Serial.println("log: turned native recycle on");
  }
}
boolean isRecycleState() {
  return solarValveIstate && solarValveIIstate;
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

  // Tubes
  sensorTin = filterSensorTemp(southernSensors.getTempC(sensorAddressTin), sensorTin);
  sensorTout = filterSensorTemp(southernSensors.getTempC(sensorAddressTout), sensorTout);
}

/**
* Filters typical sensor failure at 85C and -127C
*/
float filterSensorTemp(float rawSensorTemp, float currentTemp) {
  if (rawSensorTemp == 85.0 && (abs(rawSensorTemp - 85) > MAX_TEMP_CHANGE_THRESHOLD_85)) {
    return currentTemp;
  } else if (rawSensorTemp == -127.0) {
    return currentTemp;
  } else {
    return rawSensorTemp;
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

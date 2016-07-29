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
#include <EEPROM.h>

/**
* Simple boiler controller. Turns the furnace on and off when the preset temperature is reached.
*
* The setup assumes you have a boiler, a (gas) furnace and a three-way valve. All is connected together with
* an Arduino, a temperature sensor and two relays (one for the furnace and one for the three-way valve).
*
* It is a slave. It needs to be leashed through Serial to the master which is probably a Raspberry Pi running
* iot-solar-boiler/FurnaceSlave
*/

// Pin configuration
// The ethernet shield uses pins 10, 11, 12, and 13 for SPI communication
// Pin 4 is used to communicate with the SD card (unused)
const byte ONE_WIRE_PIN = 2;
const byte FLOW_VALVE_RELAY_PIN = 5;   // a three way valve
const byte FURNACE_BOILER_RELAY_PIN = 6;  // relay to set the furnace in boiler mode
const byte FURNACE_HEATING_RELAY_PIN = 7;  // relay to set the furnace in heating mode

const float MAX_TEMP_CHANGE_THRESHOLD_85 = 0.2;

//Thermometer devices DALLAS DS18B20+ with the OneWire protocol
OneWire oneWire(ONE_WIRE_PIN);
DallasTemperature sensors(&oneWire);
boolean sensorsReady = false;

DeviceAddress boilerSensorAddress = {0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0};
double Tboiler;

boolean furnaceState = false;  // state == false is normal heating mode
boolean flowValveState = false; // valve == false is normal heating mode

const float BOILER_START_TEMP = 48.0;
const float BOILER_STOP_TEMP = 53.0; // Depends on the furnace setting, Nefit is set to 60C, take a lower value for the boiler temperature.

void setup() {
  Serial.begin(9600);
  pinMode(FLOW_VALVE_RELAY_PIN, OUTPUT);
  pinMode(FURNACE_BOILER_RELAY_PIN, OUTPUT);
  digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceState);
  digitalWrite(FLOW_VALVE_RELAY_PIN, !flowValveState);
  Serial.println(F("log: furnace controller has started"));
}

void loop() {
  setupSensors();
  readSensors();
  furnaceControl();
  logMaster();
}

void furnaceControl() {
  if (Tboiler < BOILER_START_TEMP) {
    if (furnaceState) {
      //Furnace is already on
    } else {
      flowValveState = true;
      digitalWrite(FLOW_VALVE_RELAY_PIN, !flowValveState);
      delay(2000); // wait for the valve to switch
      furnaceState = true;
      digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceState);
      Serial.println(F("log: switched furnace on"));
    }
  } else if (Tboiler < BOILER_STOP_TEMP && furnaceState) {
    //Keep the furnace buring
  } else {
    if (!furnaceState) {
      //Furnace us already off
    } else {
      furnaceState = false;
      digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceState);
      delay(60000); //Let the last heat flow out
      flowValveState = false;
      digitalWrite(FLOW_VALVE_RELAY_PIN, !flowValveState);
      Serial.println(F("log: switched furnace off"));
    }
  }
}

/**
* Set the senso readings in their global variables.
* Remove the 85C error sensors sometimes return.
*/
void readSensors() {
  sensors.begin();
  sensors.requestTemperatures();
  
  Tboiler = filterSensorTemp(sensors.getTempC(boilerSensorAddress), Tboiler);
}

void logMaster() {
  Serial.print(furnaceState ? "1:" : "0:");
  Serial.println(Tboiler);
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

// ######################################## SETUP

void setupSensors() {
  if (!sensorsReady) {
    byte addr[8];
    byte sensorCount = 0;
    while (oneWire.search(addr)) {
      for (byte i = 0; i < 8; i++) {
        boilerSensorAddress[i] = addr[i];
      }
      sensorCount++;
    }
    if (sensorCount != 1) {
      Serial.println("log: ERROR: unexpected amount of sensors");
    }
  }
}

// ######################################## /SETUP

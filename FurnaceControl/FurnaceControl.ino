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
#include <OneWire.h>
#include <DallasTemperature.h>
#include <Ethernet.h>
#include <EEPROM.h>
#include <InfluxDB.h>

/**
* Simple boiler controller. Turns the furnace on and off when the preset temperature is reached.
*
* The setup assumes you have a boiler, a (gas) furnace and a three-way valve. All is connected together with
* an Arduino, a temperature sensor and two relays (one for the furnace and one for the three-way valve).
*
* Oh, and this controller is connected to the Net. It will upload data every 30 seconds to a InfluxDB database.
* This makes it easier to be compliant to Legionella regulations and it is course also
* nice for your boiler to have an internet connection.
* 
* Setup
* A onewire (Dallas) temperature sensor measures the boiler. You will need one of those, Further you need two
* relays: one to switch the furnace into boiler mode and the other switches the furnace three-way valve: it will
* let the furnace water flow got the boiler. The ethernet shield is used with dhcp to retrieve the gateway and ip.
*/

const byte romPosition = 200;
struct BoilerProperties {
  char name[13];
  char sensorPosition[7];
};

/**
* Add folloing in EEPROM memory. That would be something like:

BoilerProperties boilerProp = {
  "my boiler name",
  "bottom|middle|top", // top
  };  
  EEPROM.put(0, solarProp);
  
InfluxProperties influxProp = {
    macAddress,
    influxServerIP,
    database,
    userName,
    password
  };
  EEPROM.put(romPosition, influxProp);
*/

// Pin configuration
// The ethernet shield uses pins 10, 11, 12, and 13 for SPI communication
// Pin 4 is used to communicate with the SD card (unused)
#define ONE_WIRE_PIN 2
#define FLOW_VALVE_RELAY_PIN 3   // a three way valve
#define FURNACE_RELAY_PIN 4  // relay to set the furnace in boiler heating mode
#define RELAY_TEST_MS 500

const float MAX_TEMP_CHANGE_THRESHOLD_85 = 0.2;

//InfluxDB settings
const int POSTING_INTERVAL = 30000; // delay between updates, in milliseconds
unsigned long lastPostTime;
InfluxDB influx(romPosition);

//Thermometer devices DALLAS DS18B20+ with the OneWire protocol
OneWire oneWire(ONE_WIRE_PIN);
DallasTemperature sensors(&oneWire);
boolean sensorsReady = false;

DeviceAddress boilerSensorAddress = {0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0};
float Tboiler;

boolean furnaceState = false;  // state == false is normal heating mode
boolean flowValveState = false; // valve == false is normal heating mode

const float BOILER_START_TEMP = 50.0;
const float BOILER_STOP_TEMP = 55.0; //This is about the max a Nefit 65 kW furnace can do. Set this to an appropriate value for a different furnace (60 is best).

void setup() {
  Serial.begin(9600);
  Serial.println(F("Stelwagen Industries BV boiler control appliance"));

  // Initialize the relays
  pinMode(FLOW_VALVE_RELAY_PIN, OUTPUT);
  pinMode(FURNACE_RELAY_PIN, OUTPUT);
  testRelays();
  testSensors();

  influx.setup();
  Serial.println(F("Controller is ready"));
}

void loop() {
  setupSensors();
  readSensors();
  furnaceControl();
  logTemp();
  if (millis() > lastPostTime + POSTING_INTERVAL || millis() < lastPostTime) {
    postData();
  }
}

void furnaceControl() {
  if (Tboiler < BOILER_START_TEMP) {
    if (furnaceState) {
      //Furnace us already on
    } else {
      furnaceState = true;
      flowValveState = true;
      Serial.println(F("Furnace on."));
    }
  } else if (Tboiler < BOILER_STOP_TEMP && furnaceState) {
    //Keep the furnace buring
  } else {
    if (!furnaceState) {
      //Furnace us already off
    } else {
      furnaceState = false;
      flowValveState = false;
      Serial.println(F("Furnace off."));
    }
  }
  digitalWrite(FURNACE_RELAY_PIN, !furnaceState);
  digitalWrite(FLOW_VALVE_RELAY_PIN, !flowValveState);
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

void logTemp() {
  Serial.print(F("Tboiler: "));
  Serial.println(Tboiler);
}

/**
* Upload measurements and system state to the influxbd server.
*/
void postData() {
  BoilerProperties prop;
  EEPROM.get(0, prop);
  String data = "boiler_temperature,boiler=" + String(prop.name) + ",position=" + String(prop.sensorPosition) + ",control=furnace value=" + Tboiler;
  data += "\nstate,boiler=" + String(prop.name) + ",valve=furnace value=" + (int)furnaceState;
  Serial.print(F("Posting data to influx..."));
  Serial.print(data.length());
  if (influx.post(data, F("Boiler controller"))) {
    Serial.println(F("ok"));
  } else {
    Serial.println(F("failed"));
  }

  lastPostTime = millis();
}

/**
* Filters typical sensor failure at 85C and -127C
*/
float filterSensorTemp(float rawSensorTemp, float currentTemp) {
  if (rawSensorTemp == 85.0 && (abs(rawSensorTemp - 85) > MAX_TEMP_CHANGE_THRESHOLD_85)) {
    Serial.println(F("Ignoring 85.0 C reading from sensor"));
    return currentTemp;
  } else if (rawSensorTemp == -127.0) {
    Serial.println(F("Ignoring -127.0 C reading from sensor"));
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
      Serial.print(sensorCount);
      Serial.print(" - ");
      for (byte i = 0; i < 8; i++) {
        Serial.write(' ');
        Serial.print(addr[i], HEX);
        boilerSensorAddress[i] = addr[i];
      }
      sensorCount++;
      Serial.println();
    }
    Serial.print(sensorCount);
    Serial.println(F(" sensors found"));
    if (sensorCount != 1) {
      Serial.println(F("======FAILURE ========="));
      Serial.print(F("Expected one sensor but found "));
      Serial.print(sensorCount);
    } else {
      sensorsReady = true;
    }
  }
}

// ######################################## /SETUP


//######################################### TESTING

void testRelays() {
  digitalWrite(FLOW_VALVE_RELAY_PIN, false);
  delay(RELAY_TEST_MS);
  digitalWrite(FLOW_VALVE_RELAY_PIN, true);

  digitalWrite(FURNACE_RELAY_PIN, false);
  delay(RELAY_TEST_MS);
  digitalWrite(FURNACE_RELAY_PIN, true);
}

void testSensors() {
  readSensors();
  Serial.println(F("Sensor readings:"));
  Serial.print(F("- Tboiler: "));
  Serial.println(Tboiler);
}

// ######################################## /TESTING

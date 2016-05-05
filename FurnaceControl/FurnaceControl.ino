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
#include <UdpLogger.h>

/**
* Simple boiler controller. Turns the furnace on and off when the preset temperature is reached.
*
* The setup assumes you have a boiler, a (gas) furnace and a three-way valve. All is connected together with
* an Arduino, a temperature sensor and two relays (one for the furnace and one for the three-way valve).
*
* Oh, and this controller is connected to the Net. It will upload data every 30 seconds to a InfluxDB database.
* This makes it easier to be compliant to Legionella regulations and it is course also
* nice for your boiler to have an internet connection.
*/

struct FurnaceProperties {
  char name[20];
  char sensorPosition[7];
  uint8_t macAddress[6];
};

#define has_log // remove his to disable udp logging events to elastic search
#define nhas_influx // remove this to disable posting the state to InfluxDB
#define NOtest_relays // remove the NO to test the relays
#define flash_props // Writes property setting into eeprom memory

#ifdef has_log
  UdpLogger logger(sizeof(FurnaceProperties) + 1);
#endif
#ifdef has_influx
  const int POSTING_INTERVAL = 30000; // delay between updates, in milliseconds
  unsigned long lastPostTime;
  InfluxDB influx(sizeof(BoilerProperties) + sizeof(LogProperties) + 2);
#endif

// Pin configuration
// The ethernet shield uses pins 10, 11, 12, and 13 for SPI communication
// Pin 4 is used to communicate with the SD card (unused)
const byte ONE_WIRE_PIN = 2;
const byte FLOW_VALVE_RELAY_PIN = 5;   // a three way valve
const byte FURNACE_BOILER_RELAY_PIN = 6;  // relay to set the furnace in boiler mode
const byte FURNACE_HEATING_RELAY_PIN = 7;  // relay to set the furnace in heating mode

byte logCount;

const float MAX_TEMP_CHANGE_THRESHOLD_85 = 0.2;

//Thermometer devices DALLAS DS18B20+ with the OneWire protocol
OneWire oneWire(ONE_WIRE_PIN);
DallasTemperature sensors(&oneWire);
boolean sensorsReady = false;

DeviceAddress boilerSensorAddress = {0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0};
double Tboiler;

boolean furnaceState = false;  // state == false is normal heating mode
boolean flowValveState = false; // valve == false is normal heating mode

const float BOILER_START_TEMP = 50.0;
const float BOILER_STOP_TEMP = 55.0; //This is about the max a Nefit 65 kW furnace can do. Set this to an appropriate value for a different furnace (60 is best).

#if defined(has_influx) || defined(has_log)
  EthernetUDP udp;
#endif

void setup() {
  Serial.begin(9600);
  Serial.println("start");

  #ifdef flash_props
    FurnaceProperties bpIn = {"koetshuis_kelder", "top", {0xAE, 0xAE, 0xDE, 0x1E, 0xAE, 0x11}};
    EEPROM.put(0, bpIn);
  #endif

  #if defined(has_influx) || defined(has_log)
    FurnaceProperties bp;
    EEPROM.get(0, bp);
    dhcp(bp.macAddress);
  #endif
  
  // Init logging of state and events
  #ifdef has_influx
    Serial.print("influx: ");
    #ifdef flash_props
      InfluxProperties inp = {IPAddress(52, 16, 190, 185), 8087};
      influx.flash(inp);
      Serial.print("flashed influx at: ");
      Serial.println(influx.romPosition);
    #endif
    
    Serial.println(influx.setup(udp));
  #endif
  
  #ifdef has_log
    Serial.println("log");
    #ifdef flash_props
      LogProperties lp = {"FurnaceControl", "koetshuis200", IPAddress(52, 16, 201, 71), 9000};
      logger.flash(lp);
      Serial.print("flashed log at: ");
      Serial.println(logger.romPosition);
    #endif
    logger.setup();
    logger.postUdp("start");
  #endif
  
  Serial.println("pins");
  pinMode(FLOW_VALVE_RELAY_PIN, OUTPUT);
  pinMode(FURNACE_BOILER_RELAY_PIN, OUTPUT);

  #ifdef test_relays
    Serial.println("test relays");
    testRelays();
  #endif
  Serial.println("test sensors");
  testSensors();
  Serial.println(F("ready"));
}

void loop() {
  setupSensors();
  readSensors();
  furnaceControl();
  #ifdef has_influx
    if (millis() > lastPostTime + POSTING_INTERVAL || millis() < lastPostTime) {
      postData();
    }
  #endif
}

void furnaceControl() {
  if (Tboiler < BOILER_START_TEMP) {
    if (furnaceState) {
      //Furnace is already on
    } else {
      Serial.print(F("furnace"));
      furnaceState = true;
      flowValveState = true;
      #ifdef has_log
        logger.writeUdp("switch: furnace on, value: ");
        logger.writeUdp(Tboiler);
        logger.postUdp();
      #endif
      Serial.println(F(" on"));
    }
  } else if (Tboiler < BOILER_STOP_TEMP && furnaceState) {
    //Keep the furnace buring
  } else {
    if (!furnaceState) {
      //Furnace us already off
    } else {
      Serial.print(F("furnace"));
      furnaceState = false;
      flowValveState = false;
      #ifdef has_log
        logger.writeUdp("switch: furnace off, value: ");
        logger.writeUdp(Tboiler);
        logger.postUdp();
      #endif
      Serial.println(F(" off"));
    }
  }
  digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceState);
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
  if (++logCount > 4) {
    logTemp();
    logCount = 1;
  }
}

void logTemp() {
  Serial.print(F("Tboiler: "));
  Serial.println(Tboiler);
}

/**
* Upload measurements and system state to the influxbd server.
*/
#ifdef has_influx
void postData() {
  FurnaceProperties prop;
  EEPROM.get(0, prop);
  Serial.print("post"); 
  influx.writeUdp("boiler_temperature,boiler=");
  influx.writeUdp(prop.name);
  influx.writeUdp(",position=");
  influx.writeUdp(prop.sensorPosition);
  influx.writeUdp(",control=furnace value=");
  influx.writeUdp(Tboiler);
  influx.postUdp();
  influx.writeUdp("state,boiler=");
  influx.writeUdp(prop.name);
  influx.writeUdp(",valve=furnace value=");
  influx.writeUdp(furnaceState ? "t" : "f");
  influx.postUdp();
  Serial.println(F("ed influx"));

  lastPostTime = millis();
}
#endif

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
      Serial.println(F("FAILURE Expected one sensor but found "));
      Serial.print(sensorCount);
      #ifdef has_log
        logger.postUdp("error: sensor count failure");
      #endif
    } else {
      sensorsReady = true;
    }
  }
}

#if defined(has_influx) || defined(has_log)
void dhcp(uint8_t *macAddress) {
  Serial.print(F("Connecting to network... "));
  delay(1000); // give the ethernet module time to boot up
  if (Ethernet.begin(macAddress) == 0) {
    Serial.println(F(" failed to configure Ethernet using DHCP"));
  }
  else {
    Serial.print(F(" success! My IP is now "));
    Serial.println(Ethernet.localIP());
  }
}
#endif

// ######################################## /SETUP


//######################################### TESTING

#ifdef test_relays
void testRelays() {
  digitalWrite(FLOW_VALVE_RELAY_PIN, false);
  delay(1000);
  digitalWrite(FLOW_VALVE_RELAY_PIN, true);

  digitalWrite(FURNACE_RELAY_PIN, false);
  delay(1000);
  digitalWrite(FURNACE_RELAY_PIN, true);
}
#endif

void testSensors() {
  readSensors();
  logTemp();
}

// ######################################## /TESTING

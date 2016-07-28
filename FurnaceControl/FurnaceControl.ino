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
#include <OneWire.h>
#include <DallasTemperature.h>
#include <Ethernet.h>
#include <EEPROM.h>
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
  IPAddress ip;
  IPAddress gateway;
  IPAddress masterIP;
  uint16_t masterPort;
};

#define nflash_props // Writes property setting into eeprom memory

EthernetClient client;
EthernetUDP udp;
UdpLogger logger(sizeof(FurnaceProperties) + 1);
const int POSTING_INTERVAL = 30000; // delay between updates, in milliseconds
unsigned long lastPostTime;

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
  pinMode(FLOW_VALVE_RELAY_PIN, OUTPUT);
  pinMode(FURNACE_BOILER_RELAY_PIN, OUTPUT);
  digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceState);
  digitalWrite(FLOW_VALVE_RELAY_PIN, !flowValveState);

  #ifdef flash_props
    IPAddress ip(10, 0, 30, 22);
    IPAddress gateway(10, 0, 30, 1);
    IPAddress masterController(192, 168, 178, 18);
    FurnaceProperties bpIn = {"boiler200", "top", {0xAE, 0xAE, 0xDE, 0x1E, 0xAE, 0x11}, ip, gateway, masterController, 9999};
    EEPROM.put(0, bpIn);
  #endif

  FurnaceProperties bp;
  EEPROM.get(0, bp);
  IPAddress dns(8, 8, 8, 8);
  Ethernet.begin(bp.macAddress, bp.ip, dns, bp.gateway);
  
  // Init logging of state and events
  #ifdef flash_props
    IPAddress logstash(192, 168, 178, 101);
    LogProperties lp = {"FurnaceControl", "koetshuis200", logstash, 9000};
    logger.flash(lp);
  #endif
  logger.setup();
  logger.postUdp("start");
}

void loop() {
  setupSensors();
  readSensors();
  furnaceControl();
  if (millis() > lastPostTime + POSTING_INTERVAL || millis() < lastPostTime) {
    postData();
    lastPostTime = millis();
    delay(200);
  }
  receive();
}

void furnaceControl() {
  if (Tboiler < BOILER_START_TEMP) {
    if (furnaceState) {
      //Furnace is already on
    } else {
      //logger.postUdp("switch: furnace on");
      flowValveState = true;
      digitalWrite(FLOW_VALVE_RELAY_PIN, !flowValveState);
      delay(2000); // wait for the valve to switch
      furnaceState = true;
      digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceState);
    }
  } else if (Tboiler < BOILER_STOP_TEMP && furnaceState) {
    //Keep the furnace buring
  } else {
    if (!furnaceState) {
      //Furnace us already off
    } else {
      logger.postUdp("switch: furnace off");
      furnaceState = false;
      digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceState);
      delay(60000); //Let the last heat flow out
      flowValveState = false;
      digitalWrite(FLOW_VALVE_RELAY_PIN, !flowValveState);
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

/**
* Upload measurements and system state to the influxbd server.
*/
void postData() {
  client.stop();
  FurnaceProperties prop;
  EEPROM.get(0, prop);
  if (client.connect(prop.masterIP, prop.masterPort)) {
    client.print("POST /furnace/");
    client.print(prop.name);
    client.println(" HTTP/1.1");
    client.println("Host: iot");
    client.println("Content-Length: 96");
    client.println(); //This line is mandatory (for a Jetty server at least)
    client.print(prop.name);            //20
    client.print(".temperature T");     //14
    client.print(prop.sensorPosition);  //7
    client.print("=");                  //1
    client.println(Tboiler);            //5
    client.print("furnacestate,circuit="); //21
    client.print(prop.name);            //20
    client.print(" value=");            //7
    client.print(furnaceState ? "1" : "0"); //1
                                        //total 96
    for (int s = 1; s < 50; s++) {      //fill up with space, the body is truncated in the server by content length
      client.print(" ");
    }
  } else {
    client.stop();
    logger.postUdp("request failed");
  }
}

void receive() {
  if (client.available()) {
    boolean startResponse = false;
    while (client.available()) {
      char c = client.read();
      if (c == '}') {
        client.flush();
//      } else {
//        receiveBuffer[bufferPosition] = c;
//        bufferPosition++;
      }
    }
  }
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
      logger.writeUdp("FAILURE expected one sensor but found ");
      logger.writeUdp(sensorCount);
      logger.postUdp();
    } else {
      sensorsReady = true;
      logger.postUdp("Temperature sensor is initialized");
    }
  }
}

// ######################################## /SETUP

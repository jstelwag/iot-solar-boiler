Arduino IoT super efficient solar controller and monitoring system
============

A collection of Arduino sketches for a solar controller that squeezes a lot more heat out of your photons than a commercial controller does.

The Arduino's send data 'to the cloud' meaning it will post it to a InfluxDB / Grafana server to visualize the boiler's performance.

Solar controller
-------
The controller is created on a larger then average domestic solar system. Hence, beside the sport it was worth the effort to make a custom controller.
Also this controller can handle a large solar fluid  very well.
The trick is the system does not measure collector temperature instead it will use the (solar) coil in and out flow temperatures. Further a three-way valve can set the flow in recycle mode avoiding heat loss in the boiler.

Furnace controller
--------
A separate controller is used for the furnace to keep the hot water minimum temperature level at 55 C. This works as good as the controller in the furnace itself but this one can post data to the cloud.

InfluxDB and Grafana
--------
Both controllers post data to InfluxDB twice a minute. The set up of this was pretty much out of the box. You have to create a database and create the user.
No need to create tables, that is done automatically in InfluxDB once measurement data is flowing in.

  
Monitoring
--------
The boilers are monitored to see if they keep a minimum temperature. It sends email through a SMTP server. In the beginning the Arduinos would crash often (in the end it still is GPL/Open Source free stuff and worse: created by me). But they seem to be a lot more stable nowadays, but don't blame me when you had a cold shower in the morning.

Arduino
--------
I do not enjoy soldering things nor spending money on it. The whole setup (as controller, not the boilers) is comfortably below €20 available on Taobao.com (2 Arduinos + ethernet, some temperature sensors and a handful relays). You do need some compute space to run InfluxDB and the monitor.

See also
---------
http://influxdb.com/
http://grafana.org/
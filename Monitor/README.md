The monitor is in Java: install Java 1.7+ and install Apache Maven on your laptop. The server only needs the Java runtime. Compile with:
   mvn clean install

Copy the 'with-dependencies jar' that is located in the target directory to your server. Add a boiler.properties file in the jar directory, like:
   influxUrl = my.influxserver.com
   influxDB = db name
   influxUser = user name
   influxPassword = password
   mandrillUser = your email
   mandrillKey = a usable key
   alarmThreshold = 45.0

Run with, better even create a cronjob (*/15 * * * *):
   java -jar boiler-monitor-1.0-SNAPSHOT-jar-with-dependencies.jar

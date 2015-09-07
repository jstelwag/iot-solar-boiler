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
import org.apache.commons.mail.DefaultAuthenticator;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.apache.http.client.fluent.Request;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Monitors the boiler. If the temperature drops below the threshold it will send an alert email.
 *
 * Schedule it with something like cron.
 *
 * The boiler temperature is retrieved from InfluxDB. So this monitor assues you have set up your boiler to sent
 * temperature measurments to InfluxDB. You can find an Arduino sketch in this project that does exactly that.
 */
public class Boiler {
    static final String BOILERS[] = {"koetshuis200", "kasteel120"};
    static final String POSITION[] = {"top", "bottom"};
    static final String SELECT = "SELECT mean(value) FROM boiler_temperature WHERE boiler = 'theboiler' AND position = 'theposition' AND time > now() - 1h GROUP BY time(14m)";
    static final int MINIMUM_T_POINTS = 2;
    /**
     Add a properties file (boiler.properties) in the jar directory with following:
     influxUrl =
     influxDB =
     influxUser =
     influxPassword =
     mandrillUser =
     mandrillKey =
     alarmThreshold =
     */
    static final BoilerProperties prop = new BoilerProperties();

    public static void main(String[] args) {
        System.out.print("Starting boiler monitor for alarm threshold " + prop.alarmThreshold);
        for (int i = 0; i <= 1; i++) {
            System.out.print(" - " + BOILERS[i]);
            try {
                String influxResponse = pollInfluxDB(i);
                List<Double> temperatures = parseInfluxResponse(influxResponse);
                if (validateTemperatures(temperatures)) {
                    System.out.println("Ok");
                } else {
                    Thread.sleep(1000);
                    // Try again
                    temperatures = parseInfluxResponse(influxResponse);
                    if (validateTemperatures(temperatures)) {
                        System.out.println("Ok");
                    } else {
                        mandrillMail(BOILERS[i], influxResponse);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                mandrillMail(BOILERS[i], e.toString());
            }
        }
        System.out.println(" ..ready");
    }

    static String pollInfluxDB(int i) throws IOException {
        return Request.Get("http://" + prop.influxUrl + ":8086/query?db=" + prop.influxDB
                + "&u="+ prop.influxUser + "&p=" + prop.influxPassword + "&q="
                + URLEncoder.encode(SELECT.replace("theboiler", BOILERS[i]).replace("theposition", POSITION[i]), "UTF-8"))
                .addHeader("User-Agent", "Boiler monitor")
                .execute().returnContent().asString();
    }

    static List<Double> parseInfluxResponse(String response) {
        List<Double> retVal = new ArrayList<>();
        JSONArray values = new JSONObject(response).getJSONArray("results").getJSONObject(0).getJSONArray("series")
                .getJSONObject(0).getJSONArray("values");
        for (int i = 0; i < values.length(); i++) {
            if (!values.getJSONArray(i).isNull(1)) {
                retVal.add(values.getJSONArray(i).getDouble(1));
            }
        }
        return retVal;
    }

    static boolean validateTemperatures(List<Double> temperatures) {
        if (temperatures.size() <= MINIMUM_T_POINTS) {
            //too many null values, or no values at all
            return false;
        }

        for (Double temperature : temperatures) {
            if (temperature.compareTo(prop.alarmThreshold) > 0) {
                return true;
            }
        }

        return false;
    }

    static void mandrillMail(String boilerName, String message)  {
        try {
            Email email = new SimpleEmail();
            email.setHostName("smtp.mandrillapp.com");
            email.setSmtpPort(587);
            email.setAuthenticator(new DefaultAuthenticator(prop.mandrillUser, prop.mandrillKey));
            email.setSSLOnConnect(true);
            email.setFrom("jaap@kasteelnijswiller.nl", "My boiler");
            email.setSubject("Boiler " + boilerName + " is failing");
            email.setMsg("Hey Jaap, \r\nWe are getting cold feet. This is the response i got:\r\n" + message);
            email.addTo("jaapstelwagen@gmail.com");
            email.send();
        } catch (EmailException e) {
            e.printStackTrace();
        }
    }
}

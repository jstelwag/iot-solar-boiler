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
import org.apache.commons.io.IOUtils;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Created by Jaap on 26-8-2015.
 */
public class BoilerProperties {
    public final String influxUrl, influxDB, influxUser, influxPassword, mandrillUser, mandrillKey;
    public double alarmThreshold;

    public BoilerProperties()  {
        InputStream inputStream = null;
        Properties prop = new Properties();
         try {
            inputStream = getClass().getClassLoader().getResourceAsStream("boiler.properties");

            if (inputStream != null) {
                prop.load(inputStream);
            } else {
                throw new FileNotFoundException("property file 'boiler.properties' not found in the classpath");
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IOUtils.closeQuietly(inputStream);
        }

        influxUrl = prop.getProperty("influxUrl");
        influxDB = prop.getProperty("influxDB");
        influxUser = prop.getProperty("influxUser");
        influxPassword = prop.getProperty("influxPassword");
        mandrillUser = prop.getProperty("mandrillUser");
        mandrillKey = prop.getProperty("mandrillKey");
        alarmThreshold = Double.parseDouble(prop.getProperty("alarmThreshold"));;
    }
}

import java.util.HashMap;
import java.util.Map;

/**
 * Created by Jaap on 25-7-2016.
 */
public class TemperatureSensor {

    public final static Map<String, String[]> sensors = new HashMap<>();

    public static final String SOLAR_STATE = "solar.state";

    static {
        sensors.put("boiler500", new String[]{"Ttop", "Tmiddle", "Tbottom"});
        sensors.put("boiler200", new String[]{"Ttop"});
        sensors.put("pipe", new String[]{"TflowIn", "TflowOut"});
    }
}

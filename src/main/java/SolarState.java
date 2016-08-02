/**
 * Created by Jaap on 29-7-2016.
 */
public enum SolarState {
    sunset(false, false, false),
    startup(true, true, true),
    recycle(true, true, true),
    recycleTimeout(true, true, false),
    boiler500(false, false, true),
    boiler200(true, false, true),
    overheat(false, false, false),
    error(false, false, false);

    public boolean valveOne, valveTwo, solarPump;

    SolarState(boolean valveOne, boolean valveTwo, boolean solarPump) {
        this.valveOne = valveOne;
        this.valveTwo = valveTwo;
        this.solarPump = solarPump;
    }

    public String line() {
        return (valveOne ? "T" : "F") + (valveTwo ? "T" : "F") + (solarPump ? "T" : "F");
    }
}

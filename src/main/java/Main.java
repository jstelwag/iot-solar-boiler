/**
 * Created by Jaap on 25-7-2016.
 */
public class Main {

    public static void main(String[] args) {
        try {
            if (args[0].equals("ReadTemperatures")) {
                new ReadTemperatures();
            } else if (args[0].equals("FluxLogger")) {
                new FluxLogger();
            } else if (args[0].equals("Controller")) {
                new Controller();
            } else {
                //TODO error log
            }
        } catch (Exception e) {
            //todo log
        }
    }
}

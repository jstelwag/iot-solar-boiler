import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;


/**
 * Created by Jaap on 25-7-2016.
 */
public class SerialHeater {

    public SerialHeater() throws IOException {
        while (true) {
            File usb = new File("dev/ttyACM0");
            List<String> list = IOUtils.readLines(new FileReader(usb));

            System.out.print("Read " + list.size());
            if (!list.isEmpty()) {
                System.out.println("with " + list.get(list.size() - 1));
            }
        }
    }
}

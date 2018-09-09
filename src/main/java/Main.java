import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ErrorHandler;

/**
 * Created by Jaap on 25-7-2016.
 */
public class Main {
    public static void main(String[] args) {
        try {
            switch (args[0]) {
                case "http":
                    startHttp(9000);
                case "FluxLogger":
                    new FluxLogger().log().close();
                    break;
                case "Controller":
                    new Controller();
                    break;
                case "SolarSlave":
                    new SolarSlave().run();
                    break;
                default:
                    LogstashLogger.INSTANCE.message("ERROR: unknown parameter for Main " + args[0]);
                    break;
            }
        } catch (Exception e) {
            LogstashLogger.INSTANCE.message("ERROR: " + args[0] + " has finished with unhandled exception " + e.toString());
        }
    }

    private static void startHttp(int port) {
        LogstashLogger.INSTANCE.message("start http");

        Server httpServer = new Server(port);
        httpServer.setHandler(contexts());
        ErrorHandler errorHandler = new ErrorHandler();
        errorHandler.setShowStacks(true);
        httpServer.addBean(errorHandler);

        try {
            httpServer.start();
            httpServer.join();
        } catch (Exception e) {
            LogstashLogger.INSTANCE.message("FATAL: failed to start http listener " + e.toString());
            System.out.println(e.toString());
            System.exit(0);
        }

        while (true) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
            }
            try {
                //hello
            } catch (RuntimeException e) {
                LogstashLogger.INSTANCE.message("ERROR: exception occurred at the regular speaker scheduling " + e.toString());
                e.printStackTrace();
            }
        }
    }

    private static ContextHandlerCollection contexts() {
        ContextHandler redisContext = new ContextHandler("/redis");
        redisContext.setHandler(new RedisHandler());

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.setHandlers(new Handler[] { redisContext });
        return contexts;
    }
}

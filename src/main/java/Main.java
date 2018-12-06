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
                    startHttp(8080);
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
                    LogstashLogger.INSTANCE.error("Unknown parameter for Main " + args[0]);
                    break;
            }
        } catch (Exception e) {
            LogstashLogger.INSTANCE.error(args[0] + " has finished with unhandled exception " + e.toString());
        }
    }

    private static void startHttp(int port) {
        LogstashLogger.INSTANCE.info("Starting http");

        Server httpServer = new Server(port);
        httpServer.setHandler(contexts());
        ErrorHandler errorHandler = new ErrorHandler();
        errorHandler.setShowStacks(true);
        httpServer.addBean(errorHandler);

        try {
            httpServer.start();
            httpServer.join();
        } catch (Exception e) {
            LogstashLogger.INSTANCE.fatal("Failed to start http listener " + e.toString());
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
                LogstashLogger.INSTANCE.error("Exception occurred at the regular speaker scheduling " + e.toString());
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

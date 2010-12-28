package ros.rosjava;

import java.io.IOException;
import java.util.logging.Logger;
import java.util.logging.Level;

public class TransportFactory {
    private static final Logger logger =
	Logger.getLogger(TransportFactory.class.getCanonicalName());

    private final static Object[][] allTransports = {{"TCPROS"}};
    public static Object[][] allTransportArgs() {
	return allTransports;
    }

    public static RosTransport newTransport(JavaNodeHandle node, Object[] args) throws IOException {
	try {
	    String protoName = (String) args[0];
	    if (protoName.equals("TCPROS")) {
		return new TcpRosTransport(node, (String)args[1], (Integer)args[2]);
	    } else if (protoName.equals("UDPROS")) {
		// FIXME
	    }
	    logger.warning("Unknown transport: " + protoName);
	    throw new IllegalArgumentException("Unknown transport: " + protoName);
	} catch (ClassCastException e) {
	    logger.log(Level.WARNING, "Bad transport argument type received", e);
	    throw new IllegalArgumentException("Bad transport argument type", e);
	}
    }
}

package ros.rosjava;

import ros.RosException;
import ros.ServiceServer;
import ros.communication.Message;
import ros.communication.Service;

import java.io.IOException;
import java.util.logging.Logger;
import java.util.logging.Level;

public class ServicePublication<Q extends Message, A extends Message, S extends Service<Q,A>> implements ServiceServer<Q,A,S> {
    private static final Logger logger =
	Logger.getLogger(ServicePublication.class.getCanonicalName());

    private final JavaNodeHandle handle;
    private final String name;
    private final S serviceTemplate;
    private final Callback<Q, A> callback;
    private final RosTransportServer transportServer;
    private boolean isValid;

    public ServicePublication(JavaNodeHandle handle, String name, S serviceTemplate, Callback<Q, A> callback, RosTransportServer transportServer) throws RosException {
	this.handle = handle;
	this.name = name;
	this.serviceTemplate = serviceTemplate;
	this.callback = callback;
	this.transportServer = transportServer;
	isValid = true;

	handle.masterExecute("registerService", handle.getName(), name, transportServer.getUri().toString(), handle.getServerUri().toString());
    }

    public final String getService() { return name; }
    public final S getServiceTemplate() { return serviceTemplate; }

    public boolean isValid() { return isValid; }

    protected void finalize() { shutdown(); }

    public synchronized void shutdown() {
	if (!isValid()) return;
	isValid = false;

	try {
	    handle.masterExecute("unregisterService", handle.getName(), name, transportServer.getUri().toString());
	} catch (RosException e) {
	    logger.log(Level.WARNING,
		       String.format("Failed to unregister service for [%s]",
				     name), e);
	}
    }

    public void handleRequest(RosTransport transport) throws IOException {
	Q request = serviceTemplate.createRequest();
	transport.readMessage(request);
	A reply;
	int success;
	try {
	    reply = callback.call(request);
	    success = 1;
	} catch (Exception e) {
	    logger.log(Level.SEVERE, "Exception during ServiceServer call", e);
	    reply = serviceTemplate.createResponse();  // null response
	    success = 0;
	}
	transport.writeByte(success);
	transport.writeMessage(reply);
    }
}

package ros.rosjava;

import ros.RosException;
import ros.ServiceServer;
import ros.communication.Message;
import ros.communication.Service;

import java.io.IOException;

public class ServiceServerImpl<Q extends Message, A extends Message, S extends Service<Q,A>> implements ServiceServer<Q,A,S> {
    private final String name;
    private final S serviceTemplate;
    private final Callback<Q, A> callback;
    private final RosTransportServer transportServer;
    private boolean isValid;

    public ServiceServerImpl(JavaNodeHandle handle, String name, S serviceTemplate, Callback<Q, A> callback) throws RosException {
	this.name = name;
	this.serviceTemplate = serviceTemplate;
	this.callback = callback;
	this.transportServer = handle.getTransportServer("TCPROS");
	isValid = true;

	handle.masterExecute("registerService", handle.getName(), name, transportServer.getUri().toString(), handle.getServerUri().toString());
    }

    public final String getService() { return name; }

    public boolean isValid() { return isValid; }

    public void shutdown() {
	if (!isValid()) return;
	isValid = false;
    }

    public void handleRequest(RosTransport transport) throws IOException {
	Q request = serviceTemplate.createRequest();
	transport.readMessage(request);
	A reply = callback.call(request);
	transport.writeMessage(reply);
    }
}

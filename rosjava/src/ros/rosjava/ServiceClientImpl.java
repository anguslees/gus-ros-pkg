package ros.rosjava;

import ros.RosException;
import ros.ServiceClient;
import ros.communication.Message;
import ros.communication.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.logging.Logger;

public class ServiceClientImpl<Q extends Message, A extends Message, S extends Service<Q,A>> implements ServiceClient<Q,A,S> {
    private static final Logger logger =
	Logger.getLogger(ServiceClientImpl.class.getCanonicalName());

    private final JavaNodeHandle handle;
    private final String name;
    private final S serviceTemplate;
    private final boolean isPersistent;
    private final Map<String,String> headerValues;
    private RosTransport persistentTransport;
    private boolean isValid;
    private ExecutorService threadPool = null;

    public ServiceClientImpl(JavaNodeHandle handle, String serviceName, S serviceTemplate, boolean isPersistent, Map<String,String> headerValues) {
	this.name = serviceName;
	this.serviceTemplate = serviceTemplate;
	this.handle = handle;
	this.isPersistent = isPersistent;
	this.headerValues = headerValues;
	isValid = true;
    }

    public final String getService() { return name; }

    public A call(Q request) throws RosException {
	assert serviceTemplate.createRequest().getClass() == request.getClass();

	try {
	    RosTransport transport;

	    if (persistentTransport != null)
		transport = persistentTransport;
	    else {
		String uriString = (String) handle.masterExecute("lookupService", handle.getName(), getService());
		URI uri;
		try {
		    uri = new URI(uriString);
		} catch (URISyntaxException e) {
		    throw new RosException("lookupService returned an invalid URI: " + uriString, e);
		}

		// Services don't do the normal transport negotiation?
		Object[] transportArgs;
		if (uri.getScheme().equals("rosrpc")) {
		    transportArgs = new Object[3];
		    transportArgs[0] = "TCPROS";
		    transportArgs[1] = uri.getHost();
		    transportArgs[2] = uri.getPort();
		} else
		    throw new RosException(String.format("Unknown service API scheme \"%s\"", uri.getScheme()));

		transport = TransportFactory.newTransport(handle, transportArgs);
		Map<String, String> reqHeader = new HashMap<String,String>(7);
		reqHeader.put("callerid", handle.getName());
		reqHeader.put("service", getService());
		reqHeader.put("md5sum", serviceTemplate.getMD5Sum());
		reqHeader.put("type", serviceTemplate.getDataType());
		reqHeader.put("persistent", isPersistent ? "1" : "0");
		reqHeader.putAll(headerValues);
		transport.writeHeader(reqHeader);
		transport.flush();

		Map<String, String> header = transport.readHeader();
		logger.info(String.format("Connected to service [%s] at [%s]",
					  getService(), header.get("callerid")));
	    }

	    transport.writeMessage(request);
	    transport.flush();

	    A response = serviceTemplate.createResponse();
	    int success = transport.readByte();
	    transport.readMessage(response);

	    if (isPersistent && persistentTransport == null)
		persistentTransport = transport;

	    if (success != 1)
		throw new RosException("Service call returned " + success);

	    return response;
	} catch (IOException e) {
	    throw new RosException("IO error during service request", e);
	}
    }

    public FutureTask<A> callAsync(final Q request) throws RosException {
	if (threadPool == null)
	    threadPool = Executors.newCachedThreadPool();
	FutureTask<A> f = new FutureTask<A>(new Callable<A>() {
		public A call() throws Exception {
		    return (A) ServiceClientImpl.this.call(request);
		}
	    });
	threadPool.submit(f);
	return f;
    }

    public A callLocal(Q request) throws RosException {
	return call(request);
    }

    public boolean isValid() { return isValid; }

    protected void finalize() { shutdown(); }

    public synchronized void shutdown() {
	if (!isValid()) return;

	if (threadPool != null)
	    threadPool.shutdownNow();

	if (persistentTransport != null)
	    persistentTransport.shutdown();

	isValid = false;
    }
}

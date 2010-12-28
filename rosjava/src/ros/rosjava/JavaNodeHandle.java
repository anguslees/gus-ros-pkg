package ros.rosjava;

import java.io.IOException;
import java.lang.StringBuilder;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Collections;

import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.server.XmlRpcServer;
import org.apache.xmlrpc.server.XmlRpcServerConfigImpl;
import org.apache.xmlrpc.webserver.WebServer;

import ros.Topic;
import ros.NodeHandle;
import ros.RosException;
import ros.Publisher;
import ros.Subscriber;
import ros.ServiceClient;
import ros.ServiceServer;
import ros.communication.Service;
import ros.communication.Message;
import ros.communication.Time;

import ros.pkg.roslib.msg.Clock;

public class JavaNodeHandle extends NodeHandle {
    private static final Logger logger =
	Logger.getLogger(JavaNodeHandle.class.getCanonicalName());

    private final RosJava ros;
    private final String ns;
    private String name;
    private boolean isValid;
    private Map<String, String> remappings;
    private URI masterUri;
    private WebServer webServer;
    private URI webServerUri;
    private int masterRetryTimeout = 3000;  // milliseconds
    private Map<String, Object> subscribedParams = new HashMap<String,Object>();
    private XmlRpcServer xmlRpcServer;
    private XmlRpcManager xmlRpcManager = new XmlRpcManager();

    private Map<String, RosTransportServer> transports = new HashMap<String, RosTransportServer>();
    private Map<String, AdvertisedTopic<? extends Message>> advertisedTopics = new HashMap<String, AdvertisedTopic<? extends Message>>();
    private Map<String, Subscription<? extends Message>> subscriptions = new HashMap<String, Subscription<? extends Message>>();
    private Map<String, ServicePublication<? extends Message, ? extends Message, ? extends Service<?,?>>> serviceServers = new HashMap<String, ServicePublication<? extends Message, ? extends Message, ? extends Service<?,?>>>();

    private int commonPrefixLength(byte[] a, byte[] b) {
	// FIXME: count sub-byte bits too
	int len = 0;
	for (int i = 0; i < a.length && i < b.length; i++) {
	    if (a[i] != b[i]) break;
	    len++;
	}
	return len;
    }

    public InetAddress getLocalAddress() throws UnknownHostException {
	InetAddress bestAddr = InetAddress.getLocalHost();
	try {
	    // Try to use the address "closest" to the master
	    try {
		return new Socket(getMasterHost(), getMasterPort()).getLocalAddress();
	    } catch (IOException e) {
	    }

	    // ... failed.  Try to find a non-host/link-local address.
	    for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
		for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
		    logger.fine(String.format("Interface %s has address %s",
					      iface, addr));
		    if (!addr.isLoopbackAddress() && !addr.isLinkLocalAddress())
			bestAddr = addr;
		}
	    }
	} catch (SocketException e) {
	    logger.log(Level.WARNING, "Failed to enumerate local addresses", e);
	}

	return bestAddr;
    }

    public JavaNodeHandle(RosJava ros, String name, String ns, Map<String, String> remappings) {
	this.ros = ros;
	this.ns = ns;
	this.name = name;

	this.remappings = new HashMap<String,String>(remappings.size());
	for (Map.Entry<String,String> map : remappings.entrySet()) {
	    this.remappings.put(resolveName(map.getKey(), false),
				resolveName(map.getValue(), false));
	}

	String master = remappings.get("__master");
	if (master == null) {
	    master = System.getenv("ROS_MASTER_URI");
	}
	if (master == null) {
	    logFatal("ROS_MASTER_URI is not defined in the environment");
	    throw new RuntimeException("ROS_MASTER_URI is not defined in the environment");
	}
	try {
	    masterUri = new URI(master);
	} catch (URISyntaxException e) {
	    throw new RuntimeException("Failed to parse ROS_MASTER_URI", e);
	}
	logger.info("Using ROS master at " + masterUri);

	try {
	    addTransport(new TcpRosTransportServer(this));
	} catch (IOException e) {
	    throw new RuntimeException("Failed to open TCPROS server socket", e);
	}

	// XML-RPC server
	webServer = new WebServer(0);
	xmlRpcServer = webServer.getXmlRpcServer();
	XmlRpcHandlerMapping handlers = new XmlRpcHandlerMapping();
	registerHandlers(handlers);
	xmlRpcServer.setHandlerMapping(handlers);

	XmlRpcServerConfigImpl serverConfig =
	    (XmlRpcServerConfigImpl) xmlRpcServer.getConfig();
	serverConfig.setEnabledForExtensions(false);
	serverConfig.setContentLengthOptional(false);

	try {
	    webServer.start();
	} catch (IOException e) {
	    throw new RuntimeException("Failed to start XMLRPC server", e);
	}

	try {
	    String localAddr = getLocalAddress().getHostAddress();
	    webServerUri = new URI("http", null, localAddr, webServer.getPort(),
				   "/", null, null);
	} catch (UnknownHostException e) {
	    throw new RuntimeException("Failed to get local address", e);
	} catch (URISyntaxException e) {
	    throw new RuntimeException("Failed to build webserver URI", e);
	}

	isValid = true;

	logger.info("XMLRPC webserver being advertised at " + webServerUri);
    }

    protected void finalize() { shutdown(); }

    public synchronized void shutdown() {
	if (!isValid()) return;

	logger.fine("NodeHandle shutting down");

	synchronized (advertisedTopics) {
	    for (AdvertisedTopic<? extends Message> pub : advertisedTopics.values())
		pub.shutdown();
	    advertisedTopics.clear();
	}
	synchronized (subscriptions) {
	    for (Subscription<? extends Message> sub : subscriptions.values())
		sub.shutdown();
	    subscriptions.clear();
	}

	synchronized (transports) {
	    for (RosTransportServer transport : transports.values())
		transport.shutdown();
	    transports.clear();
	}

	webServer.shutdown();

	isValid = false;
    }

    @Override
    public NodeHandle copy() {
	return new JavaNodeHandle(ros, name, ns, remappings);
    }

    @Override
    final public String getNamespace() {
	return ns;
    }

    @Override
    final public String getName() {
	return name;
    }

    @Override
    public boolean isValid() {
	return isValid;
    }

    @Override
    public boolean ok() {
	return isValid;
    }

    @Override
    public boolean checkMaster() {
	try {
	    masterExecute("getPid", getName());
	} catch (RosException e) {
	    return false;
	}
	return true;
    }

    @Override
    final public String getMasterHost() {
	return masterUri.getHost();
    }

    @Override
    final public int getMasterPort() {
	return masterUri.getPort();
    }

    final public URI getMasterUri() {
	return masterUri;
    }

    final public URI getServerUri() {
	return webServerUri;
    }

    @Override
    public void setMasterRetryTimeout(int ms) {
	masterRetryTimeout = ms;
    }

    protected String remapName(String name) {
	String ret = remappings.get(name);
	return ret != null ? ret : name;
    }

    @Override
    public String resolveName(String name) {
	return resolveName(name, true);
    }

    public String resolveName(String name, boolean remap) {
	if (name.equals(""))
	    return getNamespace();
	if (name.startsWith("~"))
	    name = getName() + name.substring(1);
	if (!name.startsWith("/"))
	    name = "/" + getNamespace() + name;
	if (remap)
	    name = remapName(name);
	return name;
    }

    @Override
    public boolean hasParam(String param) {
	try {
	    return (Boolean) masterExecute("hasParam", getName(),
					   resolveName(param));
	} catch (RosException e) {
	    logger.log(Level.FINER, "Error from hasParam", e);
	    return false;
	}
    }

    private Object getParam(String param, boolean useCache) throws RosException {
	String mapped = resolveName(param);
	Object value;

	if (useCache) {
	    synchronized (subscribedParams) {
		value = subscribedParams.get(mapped);
	    }
	    if (value != null) {
		logger.fine("Using cached parameter value for key " + mapped);
		return value;
	    } else {
		value = masterExecute("subscribeParam", getName(), getServerUri().toString(), mapped);
		logger.fine("Subscribed to parameter " + mapped);
		synchronized (subscribedParams) {
		    subscribedParams.put(mapped, value);
		}
		return value;
	    }
	}

	return masterExecute("getParam", getName(), mapped);
    }

    @Override
    public boolean getBooleanParam(String param, boolean useCache) throws RosException {
	return (Boolean) getParam(param, useCache);
    }

    @Override
    public int getIntParam(String param, boolean useCache) throws RosException {
	return (Integer) getParam(param, useCache);
    }

    @Override
    public double getDoubleParam(String param, boolean useCache) throws RosException {
	return (Double) getParam(param, useCache);
    }

    @Override
    public String getStringParam(String param, boolean useCache) throws RosException {
	return (String) getParam(param, useCache);
    }

    private void setParam(String param, Object value) throws RosException {
	String mapped = resolveName(param);
	masterExecute("setParam", getName(), mapped, value);
	synchronized (subscribedParams) {
	    if (subscribedParams.containsKey(mapped))
		subscribedParams.put(mapped, value);
	}
    }

    @Override
    public void setParam(String param, boolean value) throws RosException {
	setParam(param, (Boolean)value);
    }

    @Override
    public void setParam(String param, int value) throws RosException {
	setParam(param, (Integer)value);
    }

    @Override
    public void setParam(String param, double value) throws RosException {
	setParam(param, (Double)value);
    }

    @Override
    public void setParam(String param, String value) throws RosException {
	setParam(param, (Object)value);
    }

    @Override
    public Collection<Topic> getTopics() {
	String[][] result;
	try {
	    result = (String[][])masterExecute("getPublishedTopics", getName(), "");
	} catch (RosException e) {
	    logger.log(Level.WARNING, "getPublishedTopics failed", e);
	    result = new String[0][0];
	}
	Collection<Topic> topics = new ArrayList<Topic>(result.length);
	for (String[] pair : result)
	    // FIXME: find md5sum somewhere
	    topics.add(new Topic(pair[0], pair[1], "(md5sum unknown)"));
	return topics;
    }

    @Override
    public Collection<String> getAdvertisedTopics() {
	synchronized (advertisedTopics) {
	    return advertisedTopics.keySet();
	}
    }

    @Override
    public Collection<String> getSubscribedTopics() {
	synchronized (subscriptions) {
	    return subscriptions.keySet();
	}
    }

    public ServicePublication<? extends Message, ? extends Message, ? extends Service<?,?>> getServiceServer(String serviceName) {
	synchronized (serviceServers) {
	    return serviceServers.get(serviceName);
	}
    }

    public Object xmlRpcExecute(URI uri, String command, Object... args) throws RosException {
	// FIXME: these should be configurable somewhere
	int connectionTimeout = 3000;
	int replyTimeout = 3000;
	return xmlRpcExecute(uri, connectionTimeout, replyTimeout, command, args);
    }

    private Object xmlRpcExecute(URI uri, int connectionTimeout, int replyTimeout, String command, Object... args) throws RosException {
	try {
	    XmlRpcClient client =
		xmlRpcManager.getXmlRpcClient(uri, connectionTimeout, replyTimeout);

	    if (logger.isLoggable(Level.FINE)) {
		StringBuilder debug = new StringBuilder("Running: ");
		debug.append(command).append("(");
		boolean first = true;
		for (Object arg : args) {
		    if (!first) debug.append(", ");
		    first = false;
		    debug.append(arg);
		}
		debug.append(")");
		logger.fine(debug.toString());
	    }

	    Object[] result = (Object[]) client.execute(command, args);

	    int statusCode = (Integer)result[0];
	    String statusMsg = (String)result[1];
	    Object value = result[2];

	    logger.fine(String.format("Returned: %s (%d, %s)", value, statusCode, statusMsg));

	    // FIXME: should differentiate between these two statusCodes
	    if (statusCode == -1 || statusCode == 0)
		throw new RosException(statusMsg);

	    return value;
	} catch (MalformedURLException e) {
	    throw new RosException("Bad URI", e);
	} catch (XmlRpcException e) {
	    throw new RosException("XMLRPC error", e);
	} catch (ClassCastException e) {
	    throw new RosException("Invalid argument type", e);
	}
    }

    public Object masterExecute(String command, Object... args) throws RosException {
	return xmlRpcExecute(getMasterUri(),
			     masterRetryTimeout, masterRetryTimeout,
			     command, args);
    }

    private void addTransport(RosTransportServer transport) {
	synchronized (transports) {
	    transports.put(transport.getName(), transport);
	}
    }

    public RosTransportServer getTransportServer(String name) {
	synchronized (transports) {
	    return transports.get(name);
	}
    }

    public AdvertisedTopic<? extends Message> getAdvertisedTopic(String topic) {
	synchronized (advertisedTopics) {
	    return advertisedTopics.get(topic);
	}
    }

    @Override
    public <M extends Message> Publisher<M> advertise(String topic, M messageTemplate, int queueSize, boolean latch) throws RosException {
	topic = resolveName(topic);

	AdvertisedTopic<M> pub;
	synchronized (advertisedTopics) {
	    @SuppressWarnings("unchecked")
	    AdvertisedTopic<M> tmp = (AdvertisedTopic<M>)advertisedTopics.get(topic);
	    pub = tmp;  // avoid silly unchecked exception warning
	    if (pub == null) {
		pub = new AdvertisedTopic<M>(this, topic, messageTemplate);
		advertisedTopics.put(topic, pub);
	    }
	}

	return pub.addPublisher(messageTemplate, queueSize, latch);
    }

    @Override
    public <M extends Message> Subscriber<M> subscribe(String topic, M messageTemplate, Subscriber.Callback<M> callback, int queueSize) throws RosException {
	topic = resolveName(topic);

	Subscription<M> sub;
	synchronized (subscriptions) {
	    @SuppressWarnings("unchecked")
	    Subscription<M> tmp = (Subscription<M>)subscriptions.get(topic);
	    sub = tmp;  // avoid silly unchecked exception warning
	    if (sub == null) {
		sub = new Subscription<M>(this, topic, messageTemplate);
		subscriptions.put(topic, sub);
	    }
	}
	return sub.addSubscriber(messageTemplate, callback, queueSize);
    }

    @Override
    public <Q extends Message, A extends Message, S extends Service<Q,A>> ServiceServer<Q,A,S> advertiseService(String serviceName, S serviceTemplate, ServiceServer.Callback<Q,A> callback) throws RosException {
	serviceName = resolveName(serviceName);

	synchronized (serviceServers) {
	    if (serviceServers.containsKey(serviceName))
		throw new RosException(String.format("Service %s is already advertised.", serviceName));

	    ServicePublication<Q,A,S> serviceServer =
		new ServicePublication<Q,A,S>(this, serviceName, serviceTemplate, callback, getTransportServer("TCPROS"));
	    serviceServers.put(serviceName, serviceServer);

	    return serviceServer;
	}
    }

    @Override
    public <Q extends Message, A extends Message, S extends Service<Q,A>> ServiceClient<Q,A,S> serviceClient(String serviceName, S serviceTemplate, boolean isPersistent, Map<String,String> headerValues) {
	serviceName = resolveName(serviceName);

	ServiceClient<Q,A,S> serviceClient =
	    new ServiceClientImpl<Q,A,S>(this, serviceName, serviceTemplate, isPersistent, headerValues);

	return serviceClient;
    }

    public void spin() {
	assert isValid();
	logger.fine("entering spin()");
	try {
	    while (isValid())
		Thread.sleep(100000);
	} catch (InterruptedException e) {
	}
    }

    // It might be better if spinOnce just went away ...
    public void spinOnce() {
    }


    private Object handlers = new Object() {
	    @SuppressWarnings("unused")
	    public void publisherUpdate(String callerId, String topic, Object[] publishers) {
		Subscription<? extends Message> sub;
		synchronized (subscriptions) {
		    sub = subscriptions.get(topic);
		}
		if (sub == null) {
		    logger.warning("got a request for updating publishers of topic " + topic + ", but I don't have any subscribers to that topic.");
		    return;
		}

		String[] pubs = new String[publishers.length];
		for (int i = 0; i < publishers.length; i++)
		    pubs[i] = (String) publishers[i];

		sub.publisherUpdate(pubs);
	    }

	    @SuppressWarnings("unused")
	    public Object[] requestTopic(String callerId, String topic, Object[] protocols) {
		logger.fine(String.format("requestTopic(%s, %s, [%d protos])",
					  callerId, topic, protocols.length));
		for (Object protoObj : protocols) {
		    Object[] proto = (Object[]) protoObj;
		    String protoName = (String) proto[0];
		    RosTransportServer transport = getTransportServer(protoName);
		    if (transport != null) {
			logger.fine(String.format("Selected protocol %s for [%s]",
						  protoName, callerId));
			return transport.getArguments();
		    }
		}
		throw new IllegalArgumentException("No acceptable transports found");
	    }

	    @SuppressWarnings("unused")
	    public Object[][] getBusStats(String callerId) {
		// FIXME
		return new Object[0][0];
	    }

	    @SuppressWarnings("unused")
	    public Object[][] getBusInfo(String callerId) {
		// FIXME
		return new Object[0][0];
	    }
	};
    public void registerHandlers(XmlRpcHandlerMapping mapping) {
	mapping.registerPublicMethods(handlers);
    }


    public Time now() { return ros.now(); }

    public void logDebug(String message) {
	logger.fine(message);
    }
    public void logInfo(String message) {
	logger.info(message);
    }
    public void logWarn(String message) {
	logger.warning(message);
    }
    public void logError(String message) {
	logger.severe(message);
    }
    public void logFatal(String message) {
	logger.severe(message);
    }
}

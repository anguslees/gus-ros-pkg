package ros.rosjava;

import ros.RosException;
import ros.Subscriber;
import ros.communication.Message;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Subscription<M extends Message> {
    private static final Logger logger =
	Logger.getLogger(Subscription.class.getCanonicalName());

    private final JavaNodeHandle handle;
    private final M messageTemplate;
    private final String topic;
    private boolean isValid;
    // FIXME: ideally, subscribers would be weak refs
    private Set<SubscriberImpl> subscribers = new HashSet<SubscriberImpl>();
    private Set<Connection> connections = new HashSet<Connection>();

    private class SubscriberImpl implements Subscriber<M>, Runnable {
	private final BlockingQueue<M> queue;
	private final Callback<M> callback;
	private final Thread callbackThread;
	private boolean isValid;

	public SubscriberImpl(Callback<M> callback, int queueSize) {
	    this.callback = callback;
	    queue = new ArrayBlockingQueue<M>(queueSize);
	    callbackThread = new Thread(this);
	    callbackThread.start();
	    isValid = true;
	}

	// Runs in callbackThread
	public void run() {
	    try {
		while (true) {
		    final M msg = queue.take();
		    callback.call(msg);
		}
	    } catch (InterruptedException e) {
	    }
	}

	public boolean isValid() {
	    return isValid;
	}

	public String getTopic() {
	    return topic;
	}

	public void call(M message) {
	    queue.offer(message);
	}

	protected void finalize() { shutdown(); }

	public synchronized void shutdown() {
	    if (!isValid()) return;
	    isValid = false;
	    callbackThread.interrupt();
	    removeSubscriber(this);
	}
    }

    private class Connection extends Thread {
	private RosTransport transport;
	final private URI uri;
	private boolean isValid;

	public Connection(URI uri) {
	    this.uri = uri;
	    isValid = true;
	}

	public final URI getUri() { return uri; }

	public Object[] getBusInfo() {
	    Object[] ret = {
		this.toString(),
		uri.toString(),
		"i",   // or 'o', 'b' (in/out/both)
		transport.getName(),
		topic,
		true,  // "connected"
	    };
	    return ret;
	}

	protected void finalize() { shutdown(); }

	public synchronized void shutdown() {
	    if (!isValid) return;
	    isValid = false;
	    interrupt();
	    try {
		join();
	    } catch (InterruptedException e) {
		logger.log(Level.WARNING, "Interrupted while waiting for transport thread to exit", e);
	    }
	    synchronized (connections) {
		connections.remove(this);
	    }
	    if (transport != null) transport.shutdown();
	}

	public void run() {
	    Object[] transportArgs;
	    try {
		transportArgs =
		    (Object[])handle.xmlRpcExecute(uri, "requestTopic", handle.getName(), topic, TransportFactory.allTransportArgs());
	    } catch (RosException e) {
		logger.log(Level.WARNING,
			   String.format("Error requesting topic %s from %s",
					 topic, uri), e);
		return;
	    }

	    M msg;
	    try {
		@SuppressWarnings("unchecked")
		M newMsg = (M)(messageTemplate.getClass().newInstance());
		msg = newMsg;
	    } catch (IllegalAccessException e) {
		logger.log(Level.SEVERE,
			   "messageTemplate doesn't have public null constructor", e);
		return;
	    } catch (InstantiationException e) {
		logger.log(Level.SEVERE, "error instantiating message object", e);
		return;
	    }

	    try {
		while (true) {
		    try {
			transport = TransportFactory.newTransport(handle, transportArgs);

			Map<String, String> request = new HashMap<String, String>(6);
			request.put("callerid", handle.getName());
			request.put("topic", topic);
			request.put("md5sum", messageTemplate.getMD5Sum());
			request.put("type", messageTemplate.getDataType());
			// optional:
			request.put("message_definition", messageTemplate.getMessageDefinition());

			transport.writeHeader(request);
			transport.flush();

			Map<String, String> header = transport.readHeader();
			logger.fine("Received connection header from " + header.get("callerid"));

			if (!messageTemplate.getMD5Sum().equals(header.get("md5sum")))
			    logger.warning(String.format("Received md5sum (%s) != our md5sum (%s)",
							 header.get("md5sum"), messageTemplate.getMD5Sum()));

			while (true) {
			    transport.readMessage(msg);
			    processMessage(msg);
			}
		    } catch (IOException e) {
			logger.log(Level.WARNING,
				   String.format("IO Error talking to %s. Reconnecting...", getUri()), e);
		    }
		    transport.shutdown();
		    Thread.sleep(1000);
		}
	    } catch (RosException e) {
		logger.log(Level.WARNING,
			   String.format("ROS error talking to %s. Dropping", getUri()), e);
	    } catch (InterruptedException e) {
		logger.log(Level.FINE, "Subscription thread interrupted", e);
	    }
	    transport.shutdown();
	}
    }

    public Subscription(JavaNodeHandle handle, String topic, M messageTemplate) throws RosException {
	this.handle = handle;
	this.topic = topic;
	this.messageTemplate = messageTemplate;
	isValid = true;

	Object[] ret = (Object[])handle.masterExecute("registerSubscriber", handle.getName(), topic, messageTemplate.getDataType(), handle.getServerUri().toString());

	// Can't directly cast Object[] -> String[]
	String[] publishers = new String[ret.length];
	for (int i = 0; i < ret.length; i++)
	    publishers[i] = (String)(ret[i]);

	publisherUpdate(publishers);
    }

    public boolean isValid() { return isValid; }

    public void publisherUpdate(String[] newPublishers) {
	Set<URI> pubUris = new HashSet<URI>(newPublishers.length);
	for (String pubUri : newPublishers) {
	    try {
		pubUris.add(new URI(pubUri));
	    } catch (URISyntaxException e) {
		logger.log(Level.WARNING, "Ignoring bad publisher URI " + pubUri, e);
	    }
	}

	publisherUpdate(pubUris);
    }

    public void publisherUpdate(Set<URI> newPublishers) {
	synchronized (connections) {
	    for (Connection conn : connections) {
		if (!newPublishers.remove(conn.getUri()))
		    // Failed to remove it, so conn doesn't exist in newPubs
		    conn.shutdown();
	    }
	    // Anything left in newPublishers must be new
	    for (URI newPub : newPublishers) {
		Connection conn = new Connection(newPub);
		conn.start();
		connections.add(conn);
	    }
	}
    }

    public Subscriber<M> addSubscriber(M messageTemplate, Subscriber.Callback<M> callback, int queueSize) {
	assert isValid();
	assert messageTemplate.getClass().equals(this.messageTemplate.getClass());
	SubscriberImpl sub = new SubscriberImpl(callback, queueSize);
	synchronized (subscribers) {
	    subscribers.add(sub);
	}
	return sub;
    }

    public void removeSubscriber(SubscriberImpl sub) {
	synchronized (subscribers) {
	    subscribers.remove(sub);
	    if (subscribers.isEmpty())
		shutdown();
	}
    }

    private void processMessage(M message) {
	synchronized (subscribers) {
	    for (SubscriberImpl sub : subscribers)
		sub.call(message);
	}
    }

    protected void finalize() { shutdown(); }

    public synchronized void shutdown() {
	if (!isValid) return;
	isValid = false;

	try {
	    handle.masterExecute("unregisterSubscriber", handle.getName(), topic, handle.getServerUri().toString());
	} catch (RosException e) {
	    logger.log(Level.WARNING, "Error unregistering subscriber for " + topic, e);
	}

	synchronized (subscribers) {
	    for (SubscriberImpl sub : subscribers)
		sub.shutdown();
	    assert subscribers.isEmpty();
	}
	synchronized (connections) {
	    for (Connection conn : connections)
		conn.shutdown();
	    assert connections.isEmpty();
	}
    }

    public Object[] getStats() {
	Object[] ret = new Object[2];
	ret[0] = topic;
	// FIXME
	return ret;
    }

    public Object[] getInfo() {
	ArrayList<Object[]> ret = new ArrayList<Object[]>();
	synchronized (connections) {
	    for (Connection conn : connections)
		ret.add(conn.getBusInfo());
	}
	return ret.toArray();
    }
}

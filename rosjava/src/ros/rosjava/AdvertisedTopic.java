package ros.rosjava;

import ros.Publisher;
import ros.RosException;
import ros.communication.Message;

import java.io.IOException;
import java.util.Set;
import java.util.HashSet;
import java.util.logging.Logger;
import java.util.logging.Level;

public class AdvertisedTopic<M extends Message> {
    private static final Logger logger =
	Logger.getLogger(AdvertisedTopic.class.getCanonicalName());

    private final JavaNodeHandle handle;
    private final String topic;
    private final M messageTemplate;
    private Set<RosTransport> transports = new HashSet<RosTransport>();
    private Set<PublisherImpl> publishers = new HashSet<PublisherImpl>();
    private boolean isValid;

    public AdvertisedTopic(JavaNodeHandle handle, String topic,
			   M messageTemplate) throws RosException {
	this.handle = handle;
	this.topic = topic;
	this.messageTemplate = messageTemplate;
	isValid = true;

	handle.masterExecute("registerPublisher", handle.getName(), topic, messageTemplate.getDataType(), handle.getServerUri().toString());
    }

    public final String getTopic() {
	return topic;
    }

    public boolean isValid() { return isValid; }

    protected void finalize() { shutdown(); }

    public synchronized void shutdown() {
	if (!isValid) return;
	isValid = false;

	try {
	    handle.masterExecute("unregisterPublisher", handle.getName(), topic, handle.getServerUri().toString());
	} catch (RosException e) {
	    logger.log(Level.WARNING,
		       String.format("Failed to unregister publisher for [%s]",
				     topic), e);
	}

	synchronized (transports) {
	    for (RosTransport transport : transports)
		transport.shutdown();
	    transports.clear();
	}
    }

    public final M getMessageTemplate() { return messageTemplate; }

    private class PublisherImpl implements Publisher<M> {
	private boolean useLatching;
	private boolean isValid;
	private M latchedMessage = null;

	public PublisherImpl(int queueSize, boolean useLatching) {
	    // FIXME: queueSize is ignored atm
	    this.useLatching = useLatching;
	    isValid = true;
	}

	public String getTopic() { return topic; }

	public boolean isValid() { return isValid; }

	protected void finalize() { shutdown(); }

	public synchronized void shutdown() {
	    if (!isValid()) return;
	    isValid = false;

	    removePublisher(this);
	}

	public int getNumSubscribers() {
	    synchronized (transports) {
		return transports.size();
	    }
	}

	public void publish(M m) {
	    assert m.getClass().equals(messageTemplate.getClass());
	    if (useLatching) latchedMessage = m;
	    processMessage(m);
	}

	public M getLatchedMessage() {
	    return latchedMessage;
	}
    }

    public Publisher<M> addPublisher(M messageTemplate, int queueSize, boolean useLatching) {
	assert messageTemplate.getClass().equals(this.messageTemplate.getClass());

	return new PublisherImpl(queueSize, useLatching);
    }

    public void removePublisher(PublisherImpl publisher) {
	synchronized (publishers) {
	    publishers.remove(publisher);

	    if (publishers.isEmpty())
		shutdown();
	}
    }

    public void processMessage(M m) {
	assert isValid();
	assert m.getClass().equals(messageTemplate.getClass());

	synchronized (transports) {
	    for (RosTransport transport : transports.toArray(new RosTransport[transports.size()])) {
		try {
		    transport.writeMessage(m);
		    transport.flush();
		} catch (IOException e) {
		    logger.log(Level.INFO,
			       String.format("Error writing to %s, dropping",
					     transport), e);
		    transports.remove(transport);
		    transport.shutdown();
		}
	    }
	}
    }

    public boolean isLatching() {
	synchronized (publishers) {
	    for (PublisherImpl pub : publishers) {
		M msg = pub.getLatchedMessage();
		if (msg != null)
		    return true;
	    }
	}
	return false;
    }

    public void addTransport(RosTransport transport) throws IOException {
	synchronized (transports) {
	    transports.add(transport);
	}

	synchronized (publishers) {
	    for (PublisherImpl pub : publishers) {
		M msg = pub.getLatchedMessage();
		if (msg != null)
		    processMessage(msg);
	    }
	}
    }
}

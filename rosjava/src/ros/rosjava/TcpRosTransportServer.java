package ros.rosjava;

import ros.RosException;
import ros.communication.Message;
import ros.communication.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TcpRosTransportServer implements RosTransportServer, Runnable {
    private static final Logger logger =
	Logger.getLogger(TcpRosTransportServer.class.getCanonicalName());

    private static final String NAME = "TCPROS";

    private final JavaNodeHandle handle;
    private ServerSocket socket;
    private Thread acceptor;

    public TcpRosTransportServer(JavaNodeHandle handle) throws IOException {
	this.handle = handle;

	socket = new ServerSocket();
	socket.bind(null);

	acceptor = new Thread(this);
	acceptor.setDaemon(true);
	acceptor.start();

	logger.info("TCPROS transport listening on " +
		    socket.getLocalSocketAddress());
    }

    public String getName() {
	return NAME;
    }

    public URI getUri() {
	try {
	    InetAddress addr = handle.getLocalAddress();
	    return new URI("rosrpc", null,
			   addr.getHostAddress(), socket.getLocalPort(),
			   null, null, null);
	} catch (UnknownHostException e) {
	    throw new RuntimeException("Unable to find local address", e);
	} catch (URISyntaxException e) {
	    throw new RuntimeException("Unable to build rosrpc URI", e);
	}
    }

    public Object[] getArguments() {
	URI uri = getUri();
	Object[] args = {getName(), uri.getHost(), uri.getPort()};
	return args;
    }

    public void shutdown() {
	try {
	    socket.close();
	} catch (IOException e) {
	}
    }

    private class Handler extends Thread {
	private TcpRosTransport transport;
	Map<String, String> header;

	public Handler(Socket s) throws IOException {
	    transport = new TcpRosTransport(handle, s);
	}

	public void run() {
	    try {
		header = transport.readHeader();
		logger.info("Connection from " + header.get("callerid"));

		if (logger.isLoggable(Level.FINE)) {
		    for (Map.Entry<String,String> kv : header.entrySet())
			logger.fine(String.format("%s=%s", kv.getKey(), kv.getValue()));
		}

		if (header.containsKey("topic")) {
		    handleSubscription();
		    return;  // Don't want to shutdown transport in this case
		} else if (header.containsKey("service"))
		    handleService();
		else
		    throw new IllegalArgumentException("Neither a subscription nor a service client?  Dropping.");
	    } catch (IOException e) {
		String callerId =
		    header != null ? header.get("callerid") : "(unknown)";
		logger.log(Level.INFO,
			   String.format("IO error from %s, dropping", callerId), e);
	    } catch (RosException e) {
		logger.log(Level.WARNING,
			   String.format("Encountered error while handling connection from %s, dropping",
					 header.get("callerid")), e);
	    } catch (Exception e) {
		String callerId =
		    header != null ? header.get("callerid") : "(unknown)";
		logger.log(Level.WARNING,
			   String.format("Encountered error while handling connection from %s, dropping",
					 callerId), e);
		// This may be half way through another protocol
		// message, but we do what we can ...
		Map<String, String> error = new HashMap<String, String>(2);
		error.put("callerid", handle.getName());
		error.put("error", e.getMessage() != null ? e.getMessage()
			  : e.getClass().getCanonicalName());
		try {
		    transport.writeHeader(error);
		} catch (IOException e1) {
		}
	    }

	    transport.shutdown();
	}

	private void handleSubscription() throws IOException {
	    logger.info(String.format("Subscription request for [%s] from [%s]",
				      header.get("topic"), header.get("callerid")));
	    AdvertisedTopic<? extends Message> advertisedTopic = handle.getAdvertisedTopic(header.get("topic"));
	    if (advertisedTopic == null)
		throw new IllegalArgumentException("No such topic: " + header.get("topic"));

	    final Message topic = advertisedTopic.getMessageTemplate();
	    if (!topic.getDataType().equals( header.get("type")) ||
		(!header.get("md5sum").equals("*") &&
		 !topic.getMD5Sum().equals(header.get("md5sum"))))
		throw new IllegalArgumentException(String.format("Message type:md5sum mismatch [%s:%s != %s:%s]",
								 header.get("type"), header.get("md5sum"), topic.getDataType(), topic.getMD5Sum()));

	    boolean latching = advertisedTopic.isLatching();

	    Map<String, String> response = new HashMap<String, String>(4);
	    response.put("md5sum", topic.getMD5Sum());
	    response.put("type", topic.getDataType());
	    response.put("callerid", handle.getName());
	    response.put("latching", latching ? "1" : "0");
	    transport.writeHeader(response);
	    transport.flush();

	    // publisher will call our writeMessage for new messages
	    advertisedTopic.addTransport(transport);
	}

	private void handleService() throws IOException {
	    ServicePublication<? extends Message, ? extends Message, ? extends Service<?,?>> serviceServer =
		handle.getServiceServer(header.get("service"));

	    final Service<? extends Message, ? extends Message> service = serviceServer.getServiceTemplate();
	    if (!header.get("md5sum").equals("*") &&
		!service.getMD5Sum().equals(header.get("md5sum")))
		throw new IllegalArgumentException(String.format("Service md5sum mismatch [%s != %s]",
								 header.get("md5sum"), service.getMD5Sum()));

	    boolean persistent = header.containsKey("persistent") &&
		header.get("persistent").equals("1");

	    Map<String, String> response = new HashMap<String, String>(3);
	    response.put("callerid", handle.getName());
	    response.put("md5sum", service.getMD5Sum());
	    response.put("type", service.getDataType());
	    // Not documented, but roscpp adds these next two:
	    //reqHeader.put("request_type", serviceTemplate.createRequest().getDataType());
	    //reqHeader.put("response_type", serviceTemplate.createResponse().getDataType());
	    transport.writeHeader(response);
	    transport.flush();

	    do {
		serviceServer.handleRequest(transport);
		transport.flush();
	    } while (persistent);
	}
    }

    public void run() {
	try {
	    while (true) {
		Socket s = socket.accept();
		logger.fine("Accepted connection from " +
			    s.getRemoteSocketAddress());
		Handler handler = new Handler(s);
		handler.setDaemon(true);
		handler.start();
	    }
	} catch (IOException e) {
	    logger.log(Level.SEVERE, "Failure while accepting a client connection", e);
	}
    }
}

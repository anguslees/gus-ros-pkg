package ros.rosjava;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;
import org.apache.xmlrpc.client.XmlRpcCommonsTransportFactory;

public class XmlRpcManager {
    private Map<URI, XmlRpcClient> clients = new HashMap<URI, XmlRpcClient>();

    public XmlRpcClient getXmlRpcClient(URI uri, int connectionTimeout,
					int replyTimeout) throws MalformedURLException {
	synchronized (clients) {
	    XmlRpcClient ret = clients.get(uri);
	    if (ret == null) {
		XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
		config.setServerURL(uri.toURL());
		config.setEnabledForExtensions(false);
		config.setConnectionTimeout(connectionTimeout);
		config.setReplyTimeout(replyTimeout);

		ret = new XmlRpcClient();
		ret.setTransportFactory(new XmlRpcCommonsTransportFactory(ret));
		ret.setConfig(config);

		clients.put(uri, ret);
	    }

	    return ret;
	}
    }
}

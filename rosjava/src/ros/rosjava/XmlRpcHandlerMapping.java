package ros.rosjava;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.XmlRpcHandler;
import org.apache.xmlrpc.XmlRpcRequest;
import org.apache.xmlrpc.metadata.Util;
import org.apache.xmlrpc.metadata.XmlRpcListableHandlerMapping;
import org.apache.xmlrpc.server.XmlRpcNoSuchHandlerException;

public class XmlRpcHandlerMapping implements XmlRpcListableHandlerMapping {
    private static final Logger logger =
	Logger.getLogger(XmlRpcHandlerMapping.class.getCanonicalName());

    private Map<String, Handler> handlerMap = new HashMap<String, Handler>();

    private class Handler implements XmlRpcHandler {
	final private Method method;
	final private Object object;

	public Handler(Object obj, Method method) {
	    this.object = obj;
	    this.method = method;
	}

	public Object execute(XmlRpcRequest request) throws XmlRpcException {
	    logger.fine(String.format("Executing XML-RPC request for %s.%s: %s",
				      object, method, request));
	    int count = request.getParameterCount();
	    Object[] args = new Object[count];
	    for (int i = 0; i < count; i++)
		args[i] = request.getParameter(i);

	    if (logger.isLoggable(Level.FINER)) {
		StringBuilder debug = new StringBuilder("Args: ");
		boolean first = true;
		for (int i = 0; i < count; i++) {
		    if (!first) debug.append(", ");
		    first = false;
		    debug.append(args[i]);
		}
		logger.finer(debug.toString());
	    }

	    Object[] returnValue = new Object[3];
	    try {
		returnValue[2] = method.invoke(object, args);
		if (returnValue[2] == null)
		    // Function returned void, which means the return
		    // value is 'ignore' (or a function returned an
		    // explicit null where it shouldn't have).  XmlRpc
		    // gets upset by null, so convert it to something
		    // else.
		    returnValue[2] = "";
		returnValue[0] = 1;  // SUCCESS
		returnValue[1] = "Success";
		logger.fine("XML-RPC request returning successfully");
	    } catch (IllegalArgumentException e) {
		logger.log(Level.WARNING,
			   String.format("Illegal argument to XML-RPC request [%s]", method), e);
		returnValue[0] = 0;  // FAILURE (on the client side)
		returnValue[1] = e.getMessage();
		returnValue[2] = "";
	    } catch (Exception e) {
		logger.log(Level.SEVERE,
			   String.format("Exception while handling XML-RPC request [%s]", method), e);
		returnValue[0] = -1; // ERROR (on the server side)
		returnValue[1] = e.getMessage();
		returnValue[2] = "";
	    }
	    return returnValue;
	}

	final public Method getMethod() { return method; }
    }

    public void registerPublicMethods(Object obj) {
	for (Method method : obj.getClass().getMethods()) {
	    if (!Modifier.isPublic(method.getModifiers()) ||
		method.getDeclaringClass() == Object.class)
		continue;
	    String name = method.getName();
	    handlerMap.put(name, new Handler(obj, method));
	}
    }

    public XmlRpcHandler getHandler(String handlerName) {
	return handlerMap.get(handlerName);
    }

    public String[] getListMethods() {
	return handlerMap.keySet().toArray(new String[handlerMap.size()]);
    }

    public String getMethodHelp(String handlerName) {
	return "";
    }

    public String[][] getMethodSignature(String handlerName) throws XmlRpcException {
	Handler handler = handlerMap.get(handlerName);
	if (handler == null)
	    throw new XmlRpcNoSuchHandlerException("No metadata available for method: " + handlerName);
	String[][] list = new String[1][];
	list[0] = Util.getSignature(handler.getMethod());
	return list;
    }
}

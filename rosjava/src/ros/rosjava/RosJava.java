package ros.rosjava;

import ros.Ros;
import ros.NodeHandle;
import ros.communication.Time;

import java.util.Map;
import java.util.HashMap;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.logging.Logger;
import java.util.logging.Level;

public class RosJava extends Ros implements Runnable {
    static {
	// enable verbose logging for ROS (for now)
	Logger.getLogger("ros").setLevel(Level.FINE);
    }
    private static final Logger logger =
	Logger.getLogger(RosJava.class.getCanonicalName());

    private static class SingletonHolder {
	private static final RosJava instance = new RosJava();
    }

    private String name;
    private boolean isInitialized;
    private Map<String, String> globalRemappings;
    private NodeHandle handle = null;

    private RosJava() {}

    public static RosJava getInstance() { return SingletonHolder.instance; }

    @Override
    public void init(String name, boolean noSigintHandler, boolean anonymousName, boolean noRosout, String[] args) {
	if (isInitialized)
	    throw new IllegalArgumentException("ROS has already been initialized.");
	if (args == null) args = new String[0];

	Map<String, String> map = new HashMap<String,String>();
	for (String arg : args) {
	    int pos = arg.indexOf(":=");
	    if (pos != -1)
		map.put(arg.substring(0, pos), arg.substring(pos + 2));
	}
	this.globalRemappings = map;

	if (anonymousName)
	    name += "_" + System.nanoTime();
	this.name = name;

	isInitialized = true;
    }

    @Override
    public boolean isInitialized() {
	return isInitialized;
    }

    @Override
    public boolean ok() {
	return isInitialized;
    }

    @Override
    public NodeHandle createNodeHandle(String ns, Map<String, String> remappings) {
	assert isInitialized();
	for (Map.Entry<String,String> entry : globalRemappings.entrySet())
	    if (!remappings.containsKey(entry.getKey()))
		remappings.put(entry.getKey(), entry.getValue());
	return handle = new JavaNodeHandle(this, name, ns, remappings);
    }

    public Time now() {
	// FIXME: subscribe to /clock
	long nanotime = System.nanoTime();
	long secs = nanotime / 1000000000;
	long nsecs = nanotime - secs;
	return new Time((int)secs, (int)nsecs);
    }

    @Override
    public void logDebug(String message) {
	assert isInitialized();
	assert handle != null;
	handle.logDebug(message);
    }

    @Override
    public void logInfo(String message) {
	assert isInitialized();
	assert handle != null;
	handle.logInfo(message);
    }

    @Override
    public void logWarn(String message) {
	assert isInitialized();
	assert handle != null;
	handle.logWarn(message);
    }

    @Override
    public void logError(String message) {
	assert isInitialized();
	assert handle != null;
	handle.logError(message);
    }

    @Override
    public void logFatal(String message) {
	assert isInitialized();
	assert handle != null;
	handle.logFatal(message);
    }

    @Override
    public void spin() {
	assert isInitialized();
	assert handle != null;
	handle.spin();
    }

    public void run() {
	spin();
    }

    @Override
    public void spinOnce() {
	assert isInitialized();
	assert handle != null;
	handle.spinOnce();
    }

    @Override
    public String getPackageLocation(String pkgName) {
	String[] cmd = {"rospack", "find", pkgName};
	try {
	    Process rospack = Runtime.getRuntime().exec(cmd);

	    BufferedReader reader =
		new BufferedReader(new InputStreamReader(rospack.getInputStream()), 200);
	    String line = reader.readLine();
	    int result = rospack.waitFor();
	    if (result != 0) {
		logError(String.format("[rospack] rospack find %s exited with %d",
				       pkgName, result));
		// Fatal?
	    }
	    return line;
	} catch (InterruptedException e) {
	    logger.log(Level.SEVERE, "Interrupted while reading rospack output", e);
	} catch (IOException e) {
	    logger.log(Level.SEVERE, "IOException reading rospack output", e);
	}
	return null;
    }
}

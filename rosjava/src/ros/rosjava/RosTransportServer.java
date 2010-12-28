package ros.rosjava;

import java.net.URI;

public interface RosTransportServer {
    public String getName();
    public Object[] getArguments();
    public URI getUri();
    public void shutdown();
}

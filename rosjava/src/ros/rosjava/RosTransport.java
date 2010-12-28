package ros.rosjava;

import java.io.IOException;
import java.util.Map;

import ros.RosException;
import ros.communication.Message;

public interface RosTransport {
    public String getName();

    public void shutdown();
    public void flush() throws IOException;

    public Map<String, String> readHeader() throws IOException, RosException;
    public void writeHeader(Map<String, String> header) throws IOException;

    public int readByte() throws IOException;
    public void writeByte(int b) throws IOException;

    public void readMessage(Message message) throws IOException;
    public void writeMessage(Message message) throws IOException;
}

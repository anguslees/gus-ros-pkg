package ros.rosjava;

import ros.RosException;
import ros.communication.Message;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StreamCorruptedException;
import java.net.Socket;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpRosTransport implements RosTransport {
    private final static String NAME = "TCPROS";
    private static final Logger logger =
	Logger.getLogger(TcpRosTransport.class.getCanonicalName());
    static {
	// Detailed logging in this class tends to lead to recursive
	// logging explosions, when the logs get sent to rosout.
	logger.setLevel(Level.INFO);
    }

    private Socket socket;
    private AtomicInteger sequenceNumber = new AtomicInteger(0);

    public static class TcpRosInputStream extends FilterInputStream {
	public TcpRosInputStream(InputStream in) {
	    super(in);
	}

	public void readFully(byte[] buffer) throws IOException {
	    int start = 0;
	    while (start < buffer.length) {
		int ret = read(buffer, start, buffer.length - start);
		if (ret == -1)
		    throw new EOFException("Encountered eof while reading buffer");
		start += ret;
	    }
	}

	public int readByte0() throws IOException {
	    int b = read();
	    if (b < 0)
		throw new EOFException();
	    return b;
	}

	// Little-endian 32bit int
	public int readInt() throws IOException {
	    return
		readByte0() |
		(readByte0() << 8) |
		(readByte0() << 16) |
		(readByte0() << 24);
	}

	public String readString() throws IOException {
	    int len = readInt();
	    byte[] bytes = new byte[len];
	    readFully(bytes);
	    return new String(bytes);
	}

	public Map<String,String> readHeader() throws IOException {
	    Map<String,String> header = new TreeMap<String,String>();
	    int remaining = readInt();
	    while (remaining > 0) {
		String field = readString();
		int offset = field.indexOf('=');
		header.put(field.substring(0, offset),
			   field.substring(offset + 1));
		remaining -= field.length() + 4;
	    }

	    if (logger.isLoggable(Level.FINE)) {
		for (Map.Entry<String,String> kv : header.entrySet())
		    logger.fine(String.format("Read header %s=%s", kv.getKey(), kv.getValue()));
	    }

	    return header;
	}
    }

    public static class TcpRosOutputStream extends FilterOutputStream {
	public TcpRosOutputStream(OutputStream out) {
	    super(out);
	}

	// ConnectionHeader is Little-endian
	public void writeInt(int i) throws IOException {
	    write(i & 0xff);
	    write(i >>> 8 & 0xff);
	    write(i >>> 16 & 0xff);
	    write(i >>> 24 & 0xff);
	}

	public void writeString(String string) throws IOException {
	    byte[] bytes = string.getBytes();
	    writeInt(bytes.length);
	    write(bytes);
	}

	public void writeHeader(Map<String,String> fields) throws IOException {
	    int totalLength = 0;
	    for (Map.Entry<String,String> field : fields.entrySet()) {
		// 4-byte length + '=' char + key + value
		// (this assumes each char is a single UTF-8 byte)
		totalLength += 4 + 1 +
		    field.getKey().length() + field.getValue().length();
	    }

	    writeInt(totalLength);

	    for (Map.Entry<String,String> field : fields.entrySet())
		writeString(field.getKey() + "=" + field.getValue());
	}
    }

    TcpRosInputStream in;
    TcpRosOutputStream out;

    public TcpRosTransport(JavaNodeHandle handle, Socket socket) throws IOException {
	this.socket = socket;
	in = new TcpRosInputStream(socket.getInputStream());
	out = new TcpRosOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1500));
    }

    public TcpRosTransport(JavaNodeHandle handle, String host, int port) throws IOException {
	this(handle, new Socket(host, port));
    }

    public String getName() { return NAME; }

    public String toString() {
	return socket.toString();
    }

    protected void finalize() { shutdown(); }

    public void shutdown() {
	try {
	    socket.close();
	} catch (IOException e) {
	}
    }

    public void flush() throws IOException {
	out.flush();
    }

    public void writeHeader(Map<String, String> header) throws IOException {
	//header.put("tcp_nodelay", tcpNoDelay ? "1" : "0");
	out.writeHeader(header);
    }

    public Map<String, String> readHeader() throws IOException, RosException {
	Map<String, String> header = in.readHeader();
	if (logger.isLoggable(Level.FINE)) {
	    for (Map.Entry<String,String> kv : header.entrySet())
		logger.fine(String.format("%s=%s", kv.getKey(), kv.getValue()));
	}
	if (header.containsKey("error")) {
	    throw new RosException(header.get("error"));
	}
	if (header.containsKey("tcp_nodelay")) {
	    int arg = Integer.parseInt(header.get("tcp_nodelay"));
	    socket.setTcpNoDelay(arg == 1);
	}
	return header;
    }

    public int readByte() throws IOException {
	return in.readByte0();
    }

    public void writeByte(int b) throws IOException {
	assert b >= 0 && b < 256;
	out.write(b);
    }

    public void readMessage(Message message) throws IOException {
	int len = in.readInt();
	if (len > 10E08) {
	    logger.warning("A message of 1GB+ bytes was predicted in tcpros. " +
			   "Assuming corruption and dropping connection.");
	    throw new StreamCorruptedException("Infeasible object size encountered.");
	}

	byte[] buffer = new byte[len];
	in.readFully(buffer);

	message.deserialize(buffer);
	logger.finer(String.format("Read %s from %s",
				   message.getDataType(), socket));
    }

    private int getSequenceNum() {
	return sequenceNumber.getAndIncrement();
    }

    public void writeMessage(Message message) throws IOException {
	byte[] buf = message.serialize(getSequenceNum());
	logger.finer(String.format("Writing %d bytes of %s to %s",
				   buf.length, message.getDataType(), socket));
	out.writeInt(buf.length);
	out.write(buf);
    }
}

import java.net.DatagramPacket;
import java.io.IOException;

public interface GUDPSocketAPI {

    public void send(DatagramPacket packet) throws IOException, InterruptedException;
    public void receive(DatagramPacket packet) throws IOException, InterruptedException;
    public void finish() throws IOException;
    public void close() throws IOException;
}


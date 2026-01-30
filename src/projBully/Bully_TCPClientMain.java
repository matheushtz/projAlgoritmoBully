import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;

public class Bully_TCPClientMain {

    public Bully_TCPClientMain(String serverAddress, int serverPort, BullyClient1 caller) throws UnknownHostException, IOException {
        this.socket = new Socket(serverAddress, serverPort);
        handler = new Bully_TCPClientHandler(socket, caller);
        this.handler.start();
        this.output = new PrintWriter(this.socket.getOutputStream(), true);
    }

    public void writeMessage(String outMessage) {
        this.output.println(outMessage);
    }

    public void closeConnection() throws IOException {
        this.output.close();
        this.socket.close();
        this.handler.interrupt();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            this.closeConnection();
        } finally {
            super.finalize();
        }
    }

    private Bully_TCPClientHandler handler;
    private Socket socket;
    private PrintWriter output;
}

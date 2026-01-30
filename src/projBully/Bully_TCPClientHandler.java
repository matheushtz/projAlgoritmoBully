import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class Bully_TCPClientHandler extends Thread {

    private final Socket socket;
    private final BullyClient1 caller;
    private final BufferedReader input;

    public Bully_TCPClientHandler(Socket socket, BullyClient1 caller) throws IOException {
        this.socket = socket;
        this.caller = caller;
        this.input = new BufferedReader(new InputStreamReader(this.socket.getInputStream()));
        setName("client-reader-" + (caller != null ? caller.getMyId() : "unknown"));
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            String message;
            while ((message = input.readLine()) != null) {
                message = message.trim();
                if (message.isEmpty()) {
                    continue;
                }

                String[] parts = message.split("\\|");
                String type = parts[0];

                switch (type) {
                    case "ACTIVE":
                    case "UPDATE_CLIENTS": {
                        String csv = (parts.length > 1) ? parts[1] : "";
                        List<Integer> ids = new ArrayList<>();
                        if (!csv.isEmpty()) {
                            String[] idStrings = csv.split(",");
                            for (String idStr : idStrings) {
                                try {
                                    ids.add(Integer.parseInt(idStr.trim()));
                                } catch (NumberFormatException ignore) {
                                }
                            }
                        }
                        caller.atualizarListaCliente(ids);
                        break;
                    }

                    case "ELECTION": {
                        int senderId = Integer.parseInt(parts[1]);
                        caller.receberEleicaoInfo(senderId);
                        break;
                    }
                    case "ALIVE": {
                        int senderId = Integer.parseInt(parts[1]);
                        caller.receberAlive(senderId);
                        break;
                    }
                    case "COORDINATOR": {
                        int winnerId = Integer.parseInt(parts[1]);
                        caller.recebeMensagemCoord(winnerId);
                        break;
                    }

                    case "PING": {
                        long ts = Long.parseLong(parts[2]);
                        int coordId = Integer.parseInt(parts[3]);
                        caller.recebePingServer(ts, coordId);
                        break;
                    }

                    case "TRIGGER_ELECTION": {
                        caller.onServerTriggerElection();
                        break;
                    }
                    case "STATE": {
                        double volume = Double.parseDouble(parts[1]);
                        double pH = Double.parseDouble(parts[2]);
                        double temp = Double.parseDouble(parts[3]);
                        caller.updateServerState(volume, pH, temp);
                        break;
                    }
                    case "ERROR": {
                        String code = (parts.length > 1) ? parts[1] : "";
                        String detail = (parts.length > 2) ? parts[2] : "";
                        caller.onServerError(code, detail);
                        try {
                            socket.close();
                        } catch (IOException ignore) {
                        }
                        return;
                    }

                    default: {
                        break;
                    }
                }
            }
        } catch (Exception ex) {
            System.out.println("[CLIENT] Conexão encerrada: " + ex.getMessage());
        } finally {
            try {
                input.close();
            } catch (IOException ignore) {
            }
            try {
                socket.close();
            } catch (IOException ignore) {
            }
        }
    }
}

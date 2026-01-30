import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

public class Bully_TCPServerAtivosHandler extends Thread {

    private final Bully_TCPServerConnection cliente;
    private final Bully_TCPServerAtivosMain main;
    private final BullyServer caller;

    public Bully_TCPServerAtivosHandler(Bully_TCPServerConnection cliente, Bully_TCPServerAtivosMain main, BullyServer caller) throws IOException {
        this.cliente = cliente;
        this.main = main;
        this.caller = caller;
        setName("server-handler-" + cliente.getSocket().getRemoteSocketAddress());
        setDaemon(true);
    }

    @Override
    protected void finalize() throws Throwable {
        encerrar();
    }

    private void encerrar() {
        this.main.removerCliente(this.cliente);
    }

    public synchronized void messageDispatcher(String message) throws IOException {
        List<Bully_TCPServerConnection> clientes = this.main.getClientes();
        for (Bully_TCPServerConnection cli : clientes) {
            if (cli.getSocket() != null && cli.getSocket().isConnected() && cli.getOutput() != null) {
                cli.getOutput().println(message);
                cli.getOutput().flush();
            }
        }
    }

    private synchronized void broadcastClientList() throws IOException {
        List<Bully_TCPServerConnection> clientes = this.main.getClientes();
        List<Integer> ids = new ArrayList<>();
        for (Bully_TCPServerConnection cli : clientes) {
            if (cli.getClientId() >= 0) {
                ids.add(cli.getClientId());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        String message = "UPDATE_CLIENTS|" + sb + "|";
        messageDispatcher(message);
        caller.adicionarMensagemTextArea("BROADCAST: Lista de clientes: " + sb);
    }

    @Override
    public void run() {
        String message = null;
        try {
            while ((message = this.cliente.getInput().readLine()) != null) {
                message = message.trim();
                if (message.isEmpty()) {
                    continue;
                }

                if (!message.contains("|")) {
                    caller.adicionarMensagemTextArea("Mensagem desconhecida: " + message);
                    continue;
                }

                String[] parts = message.split("\\|");
                String type = parts.length > 0 ? parts[0] : "";
                String arg1 = parts.length > 1 ? parts[1] : "";
                String arg2 = parts.length > 2 ? parts[2] : "";

                switch (type) {
                    case "JOIN": {
                        try {
                            int id = Integer.parseInt(arg1);

                            int already = this.cliente.getClientId();
                            if (already == id) {
                                break;
                            }

                            if (!main.tryRegisterClientId(this.cliente, id)) {
                                caller.adicionarMensagemTextArea("JOIN rejeitado: ID duplicado " + id);
                                main.closeWithError(this.cliente, "DUPLICATE_ID|ID " + id + " já está em uso");
                                break;
                            }

                            caller.addClient(id);
                            caller.adicionarMensagemTextArea("JOIN ok. Cliente " + id + " conectado.");

                            broadcastClientList();
                            main.sendStateTo(id);

                            messageDispatcher("JOIN|" + id + "|");

                        } catch (NumberFormatException nfe) {
                            caller.adicionarMensagemTextArea("JOIN inválido: " + message);
                        }
                        break;
                    }

                    case "ELECTION": {
                        caller.adicionarMensagemTextArea("RELAY (ELEIÇÃO): " + message);
                        messageDispatcher(message);
                        break;
                    }

                    case "ALIVE": {
                        caller.adicionarMensagemTextArea("RELAY (VIVO): " + message);
                        messageDispatcher(message);
                        break;
                    }

                    case "COORDINATOR": {
                        caller.adicionarMensagemTextArea("RELAY (COORDENADOR): " + message);
                        try {
                            int winnerId = Integer.parseInt(arg1);
                            main.setCoordinatorId(winnerId);
                        } catch (Exception ignore) {
                        }
                        messageDispatcher(message);
                        break;
                    }

                    case "PONG": {
                        try {
                            int pongId = Integer.parseInt(arg1);
                            long ts = Long.parseLong(arg2);
                            main.markCoordinatorPong(ts);
                            caller.adicionarMensagemTextArea("PONG de " + pongId + " (ts=" + ts + ")");
                        } catch (Exception e) {
                            caller.adicionarMensagemTextArea("PONG inválido: " + message + " (" + e.getMessage() + ")");
                        }
                        break;
                    }

                    case "TRIGGER_ELECTION": {
                        caller.adicionarMensagemTextArea("RELAY (TRIGGER_ELECTION): " + message);
                        messageDispatcher(message);
                        break;
                    }
                    case "ADD_WATER": {
                        double litros = Double.parseDouble(arg1);
                        main.applyAddWater(litros);

                        break;
                    }
                    case "ADD_LIME": {
                        double gramas = Double.parseDouble(arg1);
                        main.applyAddLime(gramas);
                        break;
                    }

                    default: {
                        messageDispatcher(message);
                        break;
                    }
                }
            }
        } catch (IOException ioe) {
            caller.adicionarMensagemTextArea("Erro de leitura do cliente: " + ioe.getMessage());
        } finally {
            int id = this.cliente.getClientId();
            if (id >= 0) {
                caller.adicionarMensagemTextArea("Cliente " + id + " desconectado.");
                caller.removeClient(id);
                this.main.removerCliente(this.cliente);
                try {
                    broadcastClientList();
                } catch (IOException e) {
                    caller.adicionarMensagemTextArea("Erro ao notificar desconexão: " + e.getMessage());
                }
            } else {
                this.main.removerCliente(this.cliente);
            }
        }
    }
}

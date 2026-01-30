import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Bully_TCPServerAtivosMain extends Thread {

    private static final long HEARTBEAT_INTERVAL_MS = 5000L;
    private static final long HEARTBEAT_TIMEOUT_MS = 3000L;

    private final List<Bully_TCPServerConnection> clientes;
    private final ServerSocket server;
    private final BullyServer caller;

    private volatile int coordinatorId = -1;
    private volatile long lastPingSentAt = 0L;
    private volatile long lastCoordinatorPongAt = 0L;
    private volatile boolean awaitingPong = false;

    private volatile double stateVolumeL = 1000.0;
    private volatile double statePH = 5.0;
    private volatile double stateTemperatura = 30.0;

    private static final double WATER_TEMPERATURE = 15.0;
    private static final double CAOH2_PER_G_ACID = 0.617;
    private static final double CAL_TEMP_COEFF = 0.005;

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private String fmt3(double v) {
        return String.format(java.util.Locale.US, "%.3f", v);
    }

    private String fmt1(double v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    private final ScheduledExecutorService simExec
            = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "state-sim");
                t.setDaemon(true);
                return t;
            });

    private volatile double simAcidez = Math.max(0.0, 7.0 - statePH);

    private static final double TEMP_DRIFT_PER_SEC = 0.02;
    private static final double ACIDEZ_DRIFT_PER_SEC = 0.001;

    private volatile int riscoCounter = 0;
    private static final int RISCO_LIMIT = 60;

    private boolean checkTempRed(double v) {
        return (v < 20.0) || (v > 45.0);
    }

    private boolean checkPHRed(double v) {
        return (v < 3.8) || (v > 6.0);
    }

    public Bully_TCPServerAtivosMain(int porta, BullyServer caller) throws IOException {
        this.server = new ServerSocket(porta);
        System.out.println(this.getClass().getSimpleName() + " rodando na porta: " + server.getLocalPort());
        this.clientes = new ArrayList<>();
        this.caller = caller;

        startHeartbeatTimer();
        startStateSimulation();
    }

    @Override
    public void run() {
        while (true) {
            try {
                Socket socket = this.server.accept();
                Bully_TCPServerConnection cliente = new Bully_TCPServerConnection(socket);
                novoCliente(cliente);
                (new Bully_TCPServerAtivosHandler(cliente, this, caller)).start();
            } catch (IOException ex) {
                System.out.println("Erro 4: " + ex.getMessage());
                if (caller != null) {
                    caller.adicionarMensagemTextArea("Erro accept(): " + ex.getMessage());
                }
            }
        }
    }

    public synchronized void novoCliente(Bully_TCPServerConnection cliente) throws IOException {
        clientes.add(cliente);
    }

    public synchronized void removerCliente(Bully_TCPServerConnection cliente) {
        clientes.remove(cliente);
        try {
            cliente.getInput().close();
        } catch (IOException ex) {
            System.out.println(ex.getMessage());
        }
        cliente.getOutput().close();
        try {
            cliente.getSocket().close();
        } catch (IOException ex) {
            System.out.println(ex.getMessage());
        }
    }

    public synchronized List<Bully_TCPServerConnection> getClientes() {
        return new ArrayList<>(clientes);
    }

    public void broadcast(String message) {
        List<Bully_TCPServerConnection> snapshot = getClientes();
        for (Bully_TCPServerConnection cli : snapshot) {
            try {
                if (cli.getSocket() != null && cli.getSocket().isConnected() && cli.getOutput() != null) {
                    cli.getOutput().println(message);
                    cli.getOutput().flush();
                }
            } catch (Exception e) {
            }
        }
        if (caller != null && !message.startsWith("STATE|")) {
            caller.adicionarMensagemTextArea("BROADCAST: " + message);
        }
    }

    public void sendTo(int clientId, String message) {
        Bully_TCPServerConnection target = findById(clientId);
        if (target == null) {
            return;
        }
        if (target.getSocket() != null && target.getSocket().isConnected() && target.getOutput() != null) {
            target.getOutput().println(message);
            target.getOutput().flush();
        }

    }

    private Bully_TCPServerConnection findById(int id) {
        List<Bully_TCPServerConnection> snapshot = getClientes();
        for (Bully_TCPServerConnection c : snapshot) {
            if (c.getClientId() == id) {
                return c;
            }
        }
        return null;
    }

    private void startHeartbeatTimer() {
        Timer t = new Timer("heartbeat-coordinator", true);
        t.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    heartbeatTick();
                } catch (Exception ignore) {
                }
            }
        }, 2000L, HEARTBEAT_INTERVAL_MS);
    }

    private void heartbeatTick() {
        final int coord = getCoordinatorId();
        if (coord < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (awaitingPong && (now - lastPingSentAt) > HEARTBEAT_TIMEOUT_MS && lastCoordinatorPongAt < lastPingSentAt) {
            log("[SERVER] Coordenador " + coord + " não respondeu ao heartbeat. Disparando eleição.");
            awaitingPong = false;
            triggerElectionFromServer();
            return;
        }

        if (findById(coord) == null) {
            log("[SERVER] Coordenador " + coord + " desconectado. Disparando eleição.");
            triggerElectionFromServer();
            return;
        }

        String ping = "PING|server|" + now + "|" + coord + "|";
        sendTo(coord, ping);
        lastPingSentAt = now;
        awaitingPong = true;
    }

    public void broadcastState() {
        long ts = System.currentTimeMillis();
        String msg = "STATE|" + stateVolumeL + "|" + statePH + "|" + stateTemperatura + "|" + ts + "|";
        broadcast(msg);
    }

    private void startStateSimulation() {
        simExec.scheduleAtFixedRate(() -> {
            try {
                stateTemperatura += TEMP_DRIFT_PER_SEC;
                simAcidez += ACIDEZ_DRIFT_PER_SEC;

                statePH = Math.max(2.0, Math.min(7.0, 7.0 - simAcidez));

                boolean tempRed = checkTempRed(stateTemperatura);
                boolean phRed = checkPHRed(statePH);

                if (tempRed || phRed) {
                    riscoCounter++;
                    if (riscoCounter == 1) {
                        log(timeNow() + " ALERTA: LED vermelho detectado (iniciando contador)");
                    }
                    if (riscoCounter == RISCO_LIMIT) {
                        log(timeNow() + " ⚠️ Danos ao Mosto! (contador chegou a " + RISCO_LIMIT + " s)");
                    }
                } else {
                    if (riscoCounter > 0) {
                        log(timeNow() + " Alerta cessou (contador zera após " + riscoCounter + " s)");
                    }
                    riscoCounter = 0;
                }

                broadcastState();

            } catch (Throwable ignore) {
                /* não derrubar o agendador */ }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public synchronized void applyAddWater(double aguaL) {
        if (aguaL <= 0) {
            return;
        }

        double oldVolume = stateVolumeL;
        double oldTemp = stateTemperatura;
        double oldAcidezTotal_g = simAcidez * oldVolume;

        double newVolume = oldVolume + aguaL;

        double newTemp = (oldVolume * oldTemp + aguaL * WATER_TEMPERATURE) / newVolume;

        double newAcidez_g_per_L = (newVolume > 0) ? (oldAcidezTotal_g / newVolume) : 0.0;
        simAcidez = newAcidez_g_per_L;

        double newPH = clamp(7.0 - newAcidez_g_per_L, 2.0, 7.0);

        stateVolumeL = Math.round(newVolume * 1000.0) / 1000.0;
        stateTemperatura = Math.round(newTemp * 10.0) / 10.0;
        statePH = Math.round(newPH * 1000.0) / 1000.0;

        broadcastState();
    }

    public synchronized void applyAddLime(double calGramas) {
        if (calGramas <= 0) {
            return;
        }

        if (stateVolumeL <= 0.000001) {
            return;
        }

        double deltaAcidez_g_per_L = calGramas / (CAOH2_PER_G_ACID * stateVolumeL);
        simAcidez = Math.max(0.0, simAcidez - deltaAcidez_g_per_L);

        double newPH = clamp(7.0 - simAcidez, 2.0, 7.0);

        double tempDecrease = CAL_TEMP_COEFF * (calGramas / Math.max(1.0, stateVolumeL));
        double newTemp = stateTemperatura - tempDecrease;

        statePH = Math.round(newPH * 1000.0) / 1000.0;
        stateTemperatura = Math.round(newTemp * 10.0) / 10.0;
        stateVolumeL = Math.round(stateVolumeL * 1000.0) / 1000.0;

        broadcastState();
    }

    private String timeNow() {
        return java.time.LocalTime.now().withNano(0).toString();
    }

    public synchronized boolean isIdInUse(int id) {
        for (Bully_TCPServerConnection c : clientes) {
            if (c.getClientId() == id) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean tryRegisterClientId(Bully_TCPServerConnection conn, int id) {
        for (Bully_TCPServerConnection c : clientes) {
            if (c == conn) {
                continue;
            }
            if (c.getClientId() == id) {
                return false;
            }
        }
        conn.setClientId(id);
        return true;
    }

    public void closeWithError(Bully_TCPServerConnection conn, String error) {
        try {
            if (conn.getOutput() != null) {
                String[] p = error.split("\\|", 2);
                String code = p[0];
                String detail = (p.length > 1) ? p[1] : "";
                conn.getOutput().println("ERROR|" + code + "|" + detail + "|");
                conn.getOutput().flush();
            }
        } catch (Exception ignore) {
        }
        removerCliente(conn);
    }

    public void sendStateTo(int clientId) {
        long ts = System.currentTimeMillis();
        String msg = "STATE|" + stateVolumeL + "|" + statePH + "|" + stateTemperatura + "|" + ts + "|";
        sendTo(clientId, msg);
    }

    private void log(String s) {
        System.out.println(s);
        if (caller != null) {
            caller.adicionarMensagemTextArea(s);
        }
    }

    public synchronized void setCoordinatorId(int id) {
        this.coordinatorId = id;
        this.awaitingPong = false;
        log("[SERVER] Novo coordenador registrado: " + id);
    }

    public synchronized void markCoordinatorPong(long ts) {
        this.lastCoordinatorPongAt = ts;
        this.awaitingPong = false;
    }

    public synchronized int getCoordinatorId() {
        return coordinatorId;
    }

    public void triggerElectionFromServer() {
        broadcast("TRIGGER_ELECTION|");
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        this.server.close();
    }
}

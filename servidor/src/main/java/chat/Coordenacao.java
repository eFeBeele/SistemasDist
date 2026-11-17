package chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

public class Coordenacao implements Runnable {
    private final ZContext contexto;
    private final Mensagens mensagens;
    private final String nome;
    private final String referencia = System.getenv().getOrDefault("REFERENCIA", "tcp://referencia:5559");
    private final AtomicLong recebidas = new AtomicLong();
    private volatile int rank;

    public Coordenacao(ZContext contexto, Mensagens mensagens, String nome) {
        this.contexto = contexto;
        this.mensagens = mensagens;
        this.nome = nome;
    }

    public void recebeuCliente() {
        recebidas.incrementAndGet();
    }

    private ObjectNode consultar(String endereco, ObjectNode pedido) {
        try (ZMQ.Socket socket = contexto.createSocket(SocketType.REQ)) {
            socket.setLinger(0);
            socket.setReceiveTimeOut(1000);
            socket.setSendTimeOut(1000);
            socket.connect(endereco);
            socket.send(mensagens.codificar(mensagens.preparar(pedido)));
            byte[] bytes = socket.recv();
            return bytes == null ? null : mensagens.receber(bytes);
        } catch (IOException | RuntimeException erro) {
            System.out.println("[" + nome + "] Sem resposta de " + endereco + ": " + erro.getMessage());
            return null;
        }
    }

    private void heartbeat() {
        long inicio = System.nanoTime();
        ObjectNode resposta = consultar(referencia, Mensagens.nova(rank == 0 ? "registrar" : "heartbeat")
                .put("endereco", "tcp://" + nome + ":5560"));
        if (resposta != null && resposta.path("status").asText().equals("OK")) {
            rank = resposta.path("rank").asInt();
            long metade = (System.nanoTime() - inicio) / 2_000_000;
            mensagens.ajustar(resposta.path("hora").asLong() + metade);
        }
    }

    public void run() {
        long ultimoHeartbeat = 0;
        long proximoHeartbeat = 10;
        while (!Thread.currentThread().isInterrupted()) {
            long agora = System.nanoTime();
            if (recebidas.get() >= proximoHeartbeat || agora - ultimoHeartbeat >= 5_000_000_000L) {
                heartbeat();
                ultimoHeartbeat = agora;
                if (recebidas.get() >= proximoHeartbeat) {
                    proximoHeartbeat += 10;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException erro) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

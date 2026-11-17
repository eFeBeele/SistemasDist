package chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class Coordenacao implements Runnable {
    private final ZContext contexto;
    private final Mensagens mensagens;
    private final String nome;
    private final String referencia = System.getenv().getOrDefault("REFERENCIA", "tcp://referencia:5559");
    private final AtomicLong recebidas = new AtomicLong();
    private final AtomicBoolean eleicao = new AtomicBoolean(true);
    private volatile int rank;
    private volatile String coordenador = "";
    private volatile int rankCoordenador;
    private List<JsonNode> servidores = List.of();
    private long ultimaEleicao;

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
        ObjectNode resposta = consultar(referencia, Mensagens.nova(rank == 0 ? "registrar" : "heartbeat")
                .put("endereco", "tcp://" + nome + ":5560"));
        if (resposta != null && resposta.path("status").asText().equals("OK")) {
            int anterior = rank;
            rank = resposta.path("rank").asInt();
            if (anterior != rank) {
                eleicao.set(true);
            }
        }
    }

    private void atualizarServidores() {
        ObjectNode resposta = consultar(referencia, Mensagens.nova("servidores"));
        if (resposta != null && resposta.has("servidores")) {
            List<JsonNode> lista = new ArrayList<>();
            resposta.path("servidores").forEach(lista::add);
            servidores = lista;
        }
    }

    private ObjectNode atender(ObjectNode pedido) {
        ObjectNode resposta = Mensagens.nova("resposta").put("pedido_id", pedido.path("id").asText())
                .put("status", "OK").put("rank", rank).put("coordenador", coordenador);
        switch (pedido.path("tipo").asText()) {
            case "eleicao" -> eleicao.set(true);
            case "hora", "estado" -> resposta.put("hora", mensagens.agora());
            case "ajuste" -> {
                if (pedido.path("origem").asText().equals(coordenador) && !coordenador.isBlank()) {
                    mensagens.ajustar(mensagens.agora() + pedido.path("delta").asLong());
                } else {
                    resposta.put("status", "COORDENADOR_INVALIDO");
                }
            }
            default -> resposta.put("status", "OPERACAO_INVALIDA");
        }
        return resposta;
    }

    private void ouvirServidores() {
        try (ZMQ.Socket socket = contexto.createSocket(SocketType.REP)) {
            socket.setLinger(0);
            socket.bind("tcp://*:5560");
            while (!Thread.currentThread().isInterrupted()) {
                byte[] bytes = socket.recv();
                if (bytes == null) {
                    break;
                }
                ObjectNode resposta;
                try {
                    resposta = atender(mensagens.receber(bytes));
                } catch (IOException | RuntimeException erro) {
                    resposta = Mensagens.nova("resposta").put("status", "MENSAGEM_INVALIDA");
                }
                socket.send(mensagens.codificar(mensagens.preparar(resposta)));
            }
        } catch (IOException | RuntimeException erro) {
            System.out.println("[" + nome + "] Atendimento entre servidores encerrado: " + erro.getMessage());
        }
    }

    private void anunciar(ZMQ.Socket publicador) throws IOException {
        publicador.sendMore("servers");
        publicador.send(mensagens.codificar(mensagens.preparar(Mensagens.nova("coordenador")
                .put("nome", nome).put("rank", rank))));
    }

    private void eleger(ZMQ.Socket publicador) throws IOException {
        if (rank == 0) {
            return;
        }
        ultimaEleicao = System.nanoTime();
        boolean respondeu = false;
        for (JsonNode servidor : servidores) {
            if (servidor.path("rank").asInt() > rank) {
                ObjectNode resposta = consultar(servidor.path("endereco").asText(), Mensagens.nova("eleicao"));
                respondeu |= resposta != null && resposta.path("status").asText().equals("OK");
            }
        }
        if (respondeu) {
            coordenador = "";
            rankCoordenador = 0;
        } else {
            coordenador = nome;
            rankCoordenador = rank;
            System.out.println("[" + nome + "] Eleito coordenador com rank " + rank);
            anunciar(publicador);
        }
    }

    private void receberAnuncios(ZMQ.Socket inscricoes) throws IOException {
        byte[] topico;
        while ((topico = inscricoes.recv(ZMQ.DONTWAIT)) != null) {
            ObjectNode aviso = mensagens.receber(inscricoes.recv());
            int recebido = aviso.path("rank").asInt();
            if (recebido < rank) {
                eleicao.set(true);
            } else if (recebido >= rankCoordenador) {
                coordenador = aviso.path("nome").asText();
                rankCoordenador = recebido;
            }
        }
    }

    private String enderecoCoordenador() {
        for (JsonNode servidor : servidores) {
            if (servidor.path("nome").asText().equals(coordenador)) {
                return servidor.path("endereco").asText();
            }
        }
        return null;
    }

    private void sincronizar() {
        String endereco = enderecoCoordenador();
        long inicio = System.nanoTime();
        ObjectNode resposta = endereco == null ? null : consultar(endereco, Mensagens.nova("hora"));
        if (resposta == null || !resposta.path("coordenador").asText().equals(coordenador)) {
            coordenador = "";
            rankCoordenador = 0;
            eleicao.set(true);
        } else {
            mensagens.ajustar(resposta.path("hora").asLong() + (System.nanoTime() - inicio) / 2_000_000);
        }
    }

    private void berkeley() {
        Map<String, Long> diferencas = new LinkedHashMap<>();
        for (JsonNode servidor : servidores) {
            if (servidor.path("nome").asText().equals(nome)) {
                continue;
            }
            String endereco = servidor.path("endereco").asText();
            long inicio = System.nanoTime();
            ObjectNode resposta = consultar(endereco, Mensagens.nova("hora"));
            if (resposta != null) {
                long metade = (System.nanoTime() - inicio) / 2_000_000;
                diferencas.put(endereco, resposta.path("hora").asLong() + metade - mensagens.agora());
            }
        }
        long media = Math.round(diferencas.values().stream().mapToLong(Long::longValue).sum()
                / (double) (diferencas.size() + 1));
        mensagens.ajustar(mensagens.agora() + media);
        for (Map.Entry<String, Long> diferenca : diferencas.entrySet()) {
            consultar(diferenca.getKey(), Mensagens.nova("ajuste").put("delta", media - diferenca.getValue()));
        }
        System.out.println("[" + nome + "] Berkeley: média de " + media + " ms entre "
                + (diferencas.size() + 1) + " servidores");
    }

    public void run() {
        Thread atendimento = new Thread(this::ouvirServidores, "servidores");
        atendimento.setDaemon(true);
        atendimento.start();
        try (ZMQ.Socket publicador = contexto.createSocket(SocketType.PUB);
             ZMQ.Socket inscricoes = contexto.createSocket(SocketType.SUB)) {
            publicador.setLinger(0);
            publicador.connect(System.getenv().getOrDefault("PUBLICADOR", "tcp://proxy:5557"));
            inscricoes.setLinger(0);
            inscricoes.subscribe("servers");
            inscricoes.connect(System.getenv().getOrDefault("ASSINANTE", "tcp://proxy:5558"));
            long ultimoHeartbeat = 0;
            long ultimaLista = 0;
            long ultimoAnuncio = 0;
            long ultimaSincronizacao = 0;
            long proximoHeartbeat = 10;
            long proximaSincronizacao = 15;
            while (!Thread.currentThread().isInterrupted()) {
                long agora = System.nanoTime();
                if (recebidas.get() >= proximoHeartbeat || agora - ultimoHeartbeat >= 5_000_000_000L) {
                    heartbeat();
                    ultimoHeartbeat = agora;
                    if (recebidas.get() >= proximoHeartbeat) {
                        proximoHeartbeat += 10;
                    }
                }
                if (agora - ultimaLista >= 5_000_000_000L) {
                    atualizarServidores();
                    ultimaLista = agora;
                }
                receberAnuncios(inscricoes);
                if (eleicao.getAndSet(false) || (coordenador.isBlank() && agora - ultimaEleicao >= 2_000_000_000L)) {
                    eleger(publicador);
                }
                if (nome.equals(coordenador) && agora - ultimoAnuncio >= 2_000_000_000L) {
                    anunciar(publicador);
                    ultimoAnuncio = agora;
                }
                if (!coordenador.isBlank() && (recebidas.get() >= proximaSincronizacao
                        || agora - ultimaSincronizacao >= 5_000_000_000L)) {
                    if (nome.equals(coordenador)) {
                        berkeley();
                    } else {
                        sincronizar();
                    }
                    ultimaSincronizacao = agora;
                    if (recebidas.get() >= proximaSincronizacao) {
                        proximaSincronizacao += 15;
                    }
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException erro) {
            throw new IllegalStateException("Falha na coordenação", erro);
        }
    }
}

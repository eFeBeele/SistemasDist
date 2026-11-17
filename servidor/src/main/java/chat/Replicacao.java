package chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Replicacao implements Runnable {
    private final ZContext contexto;
    private final Mensagens mensagens;
    private final String nome;
    private final Armazenamento dados;
    private final Map<String, ObjectNode> posicoes = new HashMap<>();

    public Replicacao(ZContext contexto, Mensagens mensagens, String nome, Armazenamento dados) {
        this.contexto = contexto;
        this.mensagens = mensagens;
        this.nome = nome;
        this.dados = dados;
    }

    private ObjectNode consultar(String endereco, ObjectNode pedido) throws IOException {
        try (ZMQ.Socket socket = contexto.createSocket(SocketType.REQ)) {
            socket.setLinger(0);
            socket.setReceiveTimeOut(1000);
            socket.setSendTimeOut(1000);
            socket.connect(endereco);
            socket.send(mensagens.codificar(mensagens.preparar(pedido)));
            byte[] bytes = socket.recv();
            return bytes == null ? null : mensagens.receber(bytes);
        }
    }

    private void copiar(JsonNode servidor) throws IOException {
        String outro = servidor.path("nome").asText();
        ObjectNode posicao = posicoes.computeIfAbsent(outro,
                chave -> Mensagens.JSON.createObjectNode().put("cursor", 0).put("sessao", ""));
        for (int lote = 0; lote < 5; lote++) {
            ObjectNode pedido = Mensagens.nova("registros").put("cursor", posicao.path("cursor").asInt())
                    .put("sessao", posicao.path("sessao").asText());
            ObjectNode resposta = consultar(servidor.path("endereco").asText(), pedido);
            if (resposta == null || !resposta.path("status").asText().equals("OK")) {
                return;
            }
            for (JsonNode registro : resposta.path("registros")) {
                dados.guardar((ObjectNode) registro);
            }
            posicao.put("cursor", resposta.path("cursor").asInt()).put("sessao", resposta.path("sessao").asText());
            if (resposta.path("cursor").asInt() >= resposta.path("total").asInt()) {
                return;
            }
        }
    }

    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ObjectNode lista = consultar(System.getenv().getOrDefault("REFERENCIA", "tcp://referencia:5559"),
                        Mensagens.nova("servidores"));
                if (lista != null) {
                    for (JsonNode servidor : lista.path("servidores")) {
                        if (!servidor.path("nome").asText().equals(nome)) {
                            copiar(servidor);
                        }
                    }
                }
            } catch (IOException | RuntimeException erro) {
                System.out.println("[" + nome + "] Replicação será retomada: " + erro.getMessage());
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException erro) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

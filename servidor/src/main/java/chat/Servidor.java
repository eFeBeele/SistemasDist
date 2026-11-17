package chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;

public class Servidor {
    private final String nome = System.getenv().getOrDefault("NOME", "servidor");
    private final Mensagens mensagens = new Mensagens(nome);
    private final Armazenamento dados;

    public Servidor() throws IOException {
        dados = new Armazenamento(System.getenv().getOrDefault("DADOS", "/dados"));
    }

    private boolean nomeValido(String valor) {
        return valor.matches("[a-zA-Z0-9_-]{1,32}");
    }

    private ObjectNode responder(ObjectNode pedido) {
        ObjectNode resposta = Mensagens.nova("resposta").put("pedido_id", pedido.path("id").asText());
        String tipo = pedido.path("tipo").asText();
        String canal = pedido.path("canal").asText();
        String status = "OK";
        if (!nomeValido(pedido.path("usuario").asText())) {
            status = "USUARIO_INVALIDO";
        } else {
            switch (tipo) {
                case "login" -> resposta.put("usuario", pedido.path("usuario").asText());
                case "listar" -> resposta.set("canais", Mensagens.JSON.valueToTree(dados.canais()));
                case "criar" -> {
                    if (!nomeValido(canal)) {
                        status = "CANAL_INVALIDO";
                    } else if (dados.canais().contains(canal)) {
                        status = "CANAL_EXISTENTE";
                    }
                }
                default -> status = "OPERACAO_INVALIDA";
            }
        }
        return resposta.put("status", status);
    }

    public void executar() throws IOException {
        try (ZContext contexto = new ZContext(); ZMQ.Socket socket = contexto.createSocket(SocketType.REP)) {
            socket.setLinger(0);
            socket.connect(System.getenv().getOrDefault("BROKER", "tcp://broker:5556"));
            while (!Thread.currentThread().isInterrupted()) {
                byte[] bytes = socket.recv();
                if (bytes == null) {
                    break;
                }
                ObjectNode pedido;
                try {
                    pedido = mensagens.receber(bytes);
                } catch (IOException erro) {
                    socket.send(mensagens.codificar(mensagens.preparar(
                            Mensagens.nova("resposta").put("status", "MENSAGEM_INVALIDA"))));
                    continue;
                }
                if (pedido.path("id").asText().isBlank() || !pedido.path("timestamp").isNumber()) {
                    socket.send(mensagens.codificar(mensagens.preparar(
                            Mensagens.nova("resposta").put("status", "MENSAGEM_INVALIDA"))));
                    continue;
                }
                ObjectNode registro = dados.encontrar(pedido.path("id").asText());
                ObjectNode resposta;
                if (registro != null) {
                    resposta = mensagens.preparar((ObjectNode) registro.path("resposta"));
                } else {
                    resposta = mensagens.preparar(responder(pedido));
                    registro = Mensagens.JSON.createObjectNode().put("id", pedido.path("id").asText());
                    registro.set("pedido", pedido);
                    registro.set("resposta", resposta);
                    dados.guardar(registro);
                }
                socket.send(mensagens.codificar(resposta));
            }
        }
    }

    public static void main(String[] args) throws IOException {
        new Servidor().executar();
    }
}

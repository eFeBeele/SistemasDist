package chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.msgpack.jackson.dataformat.MessagePackFactory;

import java.io.IOException;
import java.util.UUID;

public class Mensagens {
    public static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper BINARIO = new ObjectMapper(new MessagePackFactory());
    private final String nome;
    private long logico;
    private long ajuste = Long.parseLong(System.getenv().getOrDefault("DESVIO_MS", "0"));

    public Mensagens(String nome) {
        this.nome = nome;
    }

    public static ObjectNode nova(String tipo) {
        return JSON.createObjectNode().put("tipo", tipo).put("id", UUID.randomUUID().toString());
    }

    public synchronized ObjectNode preparar(ObjectNode mensagem) {
        return mensagem.deepCopy().put("origem", nome).put("timestamp", agora()).put("logico", ++logico);
    }

    public synchronized long agora() {
        return System.currentTimeMillis() + ajuste;
    }

    public synchronized void ajustar(long hora) {
        long diferenca = hora - agora();
        ajuste += diferenca;
        System.out.println("[" + nome + "] Ajuste do relógio: " + diferenca + " ms");
    }

    public synchronized byte[] codificar(ObjectNode mensagem) throws IOException {
        System.out.println("[" + nome + "] ENVIO " + mensagem);
        return BINARIO.writeValueAsBytes(mensagem);
    }

    public synchronized ObjectNode receber(byte[] bytes) throws IOException {
        JsonNode mensagem = BINARIO.readTree(bytes);
        if (!(mensagem instanceof ObjectNode objeto)) {
            throw new IOException("A mensagem deve ser um mapa MessagePack");
        }
        logico = Math.max(logico, objeto.path("logico").asLong());
        System.out.println("[" + nome + "] RECEBIMENTO " + System.currentTimeMillis() + " " + objeto);
        return objeto;
    }
}

package chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class Armazenamento {
    private final Map<String, ObjectNode> registros = new LinkedHashMap<>();
    private final String sessao = java.util.UUID.randomUUID().toString();
    private final RandomAccessFile arquivo;

    public Armazenamento(String pasta) throws IOException {
        Files.createDirectories(Path.of(pasta));
        arquivo = new RandomAccessFile(Path.of(pasta, "registros.jsonl").toFile(), "rw");
        long inicio = 0;
        while (arquivo.getFilePointer() < arquivo.length()) {
            String linha = arquivo.readLine();
            long fim = arquivo.getFilePointer();
            arquivo.seek(fim - 1);
            boolean completa = arquivo.read() == '\n';
            if (!completa) {
                arquivo.setLength(inicio);
                break;
            }
            ObjectNode registro = (ObjectNode) Mensagens.JSON.readTree(
                    new String(linha.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            registros.put(registro.path("id").asText(), registro);
            inicio = fim;
        }
        arquivo.seek(arquivo.length());
    }

    public synchronized void guardar(ObjectNode registro) throws IOException {
        String id = registro.path("id").asText();
        if (registros.containsKey(id)) {
            return;
        }
        arquivo.write((registro + "\n").getBytes(StandardCharsets.UTF_8));
        arquivo.getFD().sync();
        registros.put(id, registro.deepCopy());
    }

    public synchronized ObjectNode encontrar(String id) {
        return registros.values().stream()
                .filter(r -> r.path("pedido").path("id").asText().equals(id))
                .min(java.util.Comparator.comparing((ObjectNode r) -> !r.path("resposta").path("status").asText().equals("OK"))
                        .thenComparing(r -> r.path("id").asText()))
                .map(ObjectNode::deepCopy).orElse(null);
    }

    public synchronized List<String> canais() {
        TreeSet<String> canais = new TreeSet<>();
        for (ObjectNode registro : registros.values()) {
            JsonNode pedido = registro.path("pedido");
            if (pedido.path("tipo").asText().equals("criar")
                    && registro.path("resposta").path("status").asText().equals("OK")) {
                canais.add(pedido.path("canal").asText());
            }
        }
        return new ArrayList<>(canais);
    }

    public synchronized List<JsonNode> historico(String canal) {
        Map<String, ObjectNode> unicos = new LinkedHashMap<>();
        for (ObjectNode registro : registros.values()) {
            JsonNode pedido = registro.path("pedido");
            if (pedido.path("tipo").asText().equals("publicar")
                    && pedido.path("canal").asText().equals(canal)
                    && registro.path("resposta").path("status").asText().equals("OK")) {
                String id = pedido.path("id").asText();
                ObjectNode anterior = unicos.get(id);
                if (anterior == null || registro.path("id").asText().compareTo(anterior.path("id").asText()) < 0) {
                    unicos.put(id, registro);
                }
            }
        }
        List<JsonNode> publicacoes = new ArrayList<>();
        unicos.values().forEach(r -> publicacoes.add(r.has("publicacao")
                ? r.path("publicacao").deepCopy() : r.path("pedido").deepCopy()));
        publicacoes.sort(java.util.Comparator
                .comparingLong((JsonNode p) -> p.path("timestamp").asLong())
                .thenComparing(p -> p.path("id").asText()));
        return publicacoes;
    }

    public synchronized ObjectNode pagina(ObjectNode pedido) {
        int inicio = sessao.equals(pedido.path("sessao").asText()) ? pedido.path("cursor").asInt() : 0;
        inicio = Math.max(0, Math.min(inicio, registros.size()));
        List<ObjectNode> todos = new ArrayList<>(registros.values());
        int fim = Math.min(inicio + 100, todos.size());
        ObjectNode pagina = Mensagens.nova("resposta").put("pedido_id", pedido.path("id").asText())
                .put("status", "OK").put("sessao", sessao).put("cursor", fim).put("total", todos.size());
        pagina.set("registros", Mensagens.JSON.valueToTree(todos.subList(inicio, fim)));
        return pagina;
    }
}

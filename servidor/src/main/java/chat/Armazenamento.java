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
        ObjectNode registro = registros.get(id);
        return registro == null ? null : registro.deepCopy();
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
}

# Sistema distribuído de mensagens

**Fernando Bordin Lopes**

R.A. 22.126.082-1

Fiz este projeto individual para a disciplina de Sistemas Distribuídos (CC7261). A comunicação usa ZeroMQ. Os bots fazem login sem senha, criam canais, publicam mensagens e consultam o histórico. Usei Java 21 nos servidores e Python 3.12 nos bots, no broker, no proxy e no serviço de referência.

## Como rodar

Com Docker e Docker Compose instalados, rode na pasta do projeto:

```bash
docker compose up --build
```

Na versão final, sobem dois servidores, dois bots, o broker, o proxy e a referência. Os bots começam a trocar mensagens sozinhos. Os logs mostram o conteúdo completo das mensagens enviadas e recebidas.

```bash
docker compose logs -f
docker compose down
```

## Como funciona

- **Login e canais.** Os bots usam REQ/REP pelo broker, com ROUTER na porta 5555 e DEALER na 5556. As mensagens usam MessagePack e levam identificador, tipo, origem e timestamp em milissegundos. Cada servidor grava pedidos e respostas no próprio arquivo `dados/servidor-N/registros.jsonl` antes de responder.
- **Publicação e histórico.** O proxy usa XSUB na porta 5557 e XPUB na 5558. Cada publicação leva o canal como tópico e a mensagem em MessagePack. O bot assina até três canais e, a cada rodada, envia dez mensagens com intervalo de um segundo. O histórico vem do disco e é ordenado por horário e identificador.
- **Relógios e heartbeat.** O relógio lógico aumenta a cada envio e, ao receber uma mensagem, fica com o maior valor entre o local e o recebido. A referência, na porta 5559, cadastra os servidores e guarda seus ranks. O heartbeat sai a cada dez pedidos ou cinco segundos, e a referência remove da lista quem ficar trinta segundos sem contato. Na parte 3, a hora da referência é usada para ajustar os relógios.
- **Eleição e Berkeley.** A partir da parte 4, o algoritmo do valentão elege o servidor disponível de maior rank, que anuncia o resultado no tópico `servers`. A troca entre servidores usa a porta 5560. O coordenador consulta os relógios, calcula a média dos desvios e distribui os ajustes a cada quinze pedidos ou cinco segundos.
- **Replicação.** Escolhi a cópia de dados por pull vista em aula. Como o broker distribui os pedidos, adaptei o método para todos os servidores aceitarem escritas. A cada dois segundos, cada servidor busca em lotes os registros dos demais e usa o identificador de cada registro para evitar duplicatas. Quem entra ou volta depois de uma queda busca os dados que faltam. A consistência é eventual, e a confirmação da escrita depende do disco local. Se esse disco for perdido antes da cópia, o registro pode ser perdido.

## Testar queda e recuperação

Confira nos logs qual servidor foi eleito. Se for o `servidor-1`, rode os comandos abaixo e acompanhe a eleição antes de iniciá-lo novamente. Se for o outro, troque pelo nome dele.

```bash
docker compose stop servidor-1
docker compose start servidor-1
```

Para subir um terceiro servidor com armazenamento próprio:

```bash
docker compose run -d --no-deps --name servidor-3 -e NOME=servidor-3 -v "$(pwd)/dados/servidor-3:/dados" servidor-1
docker stop servidor-3
docker rm servidor-3
```

## Entregas

A `main` tem a versão final. Cada entrega tem sua própria branch e seu ZIP.

| Etapa | Branch | Arquivo |
|---|---|---|
| Login e canais | [parte1](https://github.com/eFeBeele/SistemasDist/tree/parte1) | [ZIP](https://github.com/eFeBeele/SistemasDist/archive/refs/heads/parte1.zip) |
| Publicação | [parte2](https://github.com/eFeBeele/SistemasDist/tree/parte2) | [ZIP](https://github.com/eFeBeele/SistemasDist/archive/refs/heads/parte2.zip) |
| Relógios e heartbeat | [parte3](https://github.com/eFeBeele/SistemasDist/tree/parte3) | [ZIP](https://github.com/eFeBeele/SistemasDist/archive/refs/heads/parte3.zip) |
| Eleição | [parte4](https://github.com/eFeBeele/SistemasDist/tree/parte4) | [ZIP](https://github.com/eFeBeele/SistemasDist/archive/refs/heads/parte4.zip) |
| Replicação | [parte5](https://github.com/eFeBeele/SistemasDist/tree/parte5) | [ZIP](https://github.com/eFeBeele/SistemasDist/archive/refs/heads/parte5.zip) |

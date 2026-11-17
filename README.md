# Troca de mensagens

Projeto individual de Sistemas Distribuídos. Os servidores usam Java 21 e os bots e serviços auxiliares usam Python 3.12. A comunicação passa pelo ZeroMQ, usando JeroMQ no Java e PyZMQ no Python.

## Execução

Com Docker e Docker Compose instalados, execute na pasta do projeto:

```
docker compose up --build
```

O Compose inicia dois servidores, dois bots e o broker. Os bots fazem login, consultam os canais e criam canais automaticamente. `docker compose up` também faz a primeira construção das imagens. Para encerrar, use `docker compose down`.

## Parte 1

O broker recebe os pedidos em um ROUTER na porta 5555 e distribui entre os servidores por um DEALER na porta 5556. Os bots usam REQ e os servidores usam REP. Todas as conexões usam a rede interna do Compose.

As mensagens usam MessagePack, um formato binário disponível nas duas linguagens. Cada mensagem contém `id`, `tipo`, `origem` e `timestamp` em milissegundos UTC. Pedidos de clientes também levam `usuario`. As respostas contêm `pedido_id` e `status`. JSON aparece apenas no disco e nos logs; não é usado como serialização na rede.

| Pedido | Campos adicionais | Resposta |
|---|---|---|
| `login` | nenhum | usuário e status |
| `listar` | nenhum | lista `canais` |
| `criar` | `canal` | status |

Usuários e canais aceitam de 1 a 32 letras sem acento, números, `_` ou `-`. Os erros são `USUARIO_INVALIDO`, `CANAL_INVALIDO`, `CANAL_EXISTENTE`, `OPERACAO_INVALIDA` e `MENSAGEM_INVALIDA`. Login não usa senha. O nome identifica o bot em cada pedido, pois o broker pode encaminhar o próximo pedido a outro servidor. Se o login falhar, o bot repete. Sem resposta em três segundos, ele abre outro socket e tenta novamente com o mesmo identificador.

Cada servidor grava os pedidos e respostas em `dados/servidor-N/registros.jsonl`, incluindo logins, criação e listagem de canais. A gravação é confirmada em disco antes da resposta. Os arquivos são separados e carregados na inicialização. Uma última linha incompleta, causada por interrupção da escrita, é descartada ao reiniciar. Identificadores já gravados não geram outra operação no mesmo servidor.

Nesta etapa, cada servidor conhece apenas seus próprios registros. A replicação entre eles entra na parte 5.

Os logs mostram o conteúdo completo de cada mensagem enviada e recebida. Para acompanhar: `docker compose logs -f`.

## Parte 2

O proxy usa XSUB na porta 5557 e XPUB na 5558. O servidor recebe `publicar` com `canal` e `texto`, grava a interação, publica no canal e responde. O texto deve ter entre 1 e 280 caracteres e o canal precisa existir naquele servidor; os erros adicionais são `TEXTO_INVALIDO` e `CANAL_INEXISTENTE`.

Cada publicação possui dois frames: o nome do canal terminado em byte zero e a mensagem em MessagePack. O byte zero impede que a inscrição em `jogos` também receba `jogos2`. Os bots imprimem o conteúdo completo, com canal, timestamp de envio e instante de recebimento.

A cada rodada, o bot cria um canal se encontrar menos de cinco e assina mais um canal aleatório se tiver menos de três inscrições. Depois escolhe um canal, envia dez mensagens com intervalo de um segundo e consulta o histórico. Se o canal ainda não existir no servidor que recebeu o pedido, tenta criá-lo. Até a parte 5, os históricos e canais continuam locais a cada servidor.

O pedido `historico`, com o campo `canal`, retorna a lista `publicacoes` ordenada por timestamp e identificador. PUB/SUB não confirma entrega: uma inscrição recente pode perder publicações, que continuam disponíveis no histórico persistido. A resposta `OK` confirma gravação e envio ao socket PUB, não o recebimento por todos os assinantes.

## Parte 3

Todas as mensagens passam a conter `logico`. O contador começa em zero, aumenta antes de cada envio e recebe o máximo entre o valor local e o recebido, conforme a regra do enunciado. Reenvios usam o mesmo identificador de operação e novos timestamps e contadores. Broker e proxy apenas encaminham os frames.

A referência atende em REP na porta 5559. `registrar` e `heartbeat` recebem o nome em `origem` e o endereço do servidor em `endereco`; retornam `rank` e `hora`. `servidores` retorna os nomes, ranks e endereços ativos. Os ranks são únicos por nome e ficam em `dados/referencia/ranks.json`, inclusive após reiniciar a referência.

O servidor manda heartbeat a cada dez pedidos de clientes. Também manda a cada cinco segundos para não ser removido quando os bots estiverem ociosos. A referência remove da lista ativa quem ficar trinta segundos sem contato. Os intervalos usam relógio monotônico, para que os ajustes físicos não alterem a detecção de falha.

O relógio físico é representado por um deslocamento sobre a hora do sistema; não exige permissão para alterar o relógio do container. Na resposta do heartbeat, o servidor usa a hora da referência mais metade do tempo da requisição. O Compose começa os servidores com desvios de -3000 e +4000 ms para mostrar o ajuste nos logs.

## Parte 4

A partir desta etapa, a referência deixa de fornecer `hora` no heartbeat. O timestamp obrigatório continua no cabeçalho, mas não é usado para ajustar os servidores.

O algoritmo do valentão escolhe o maior rank disponível. Quem inicia uma eleição envia `eleicao` aos ranks maiores. Quem responde também inicia sua eleição. Sem resposta de um rank maior, o servidor se declara coordenador e publica nome e rank no tópico `servers`. Todos mantêm o nome do coordenador; quem perdeu o anúncio pode recebê-lo novamente, pois ele se repete a cada dois segundos.

Cada servidor atende os demais em REP na porta 5560, numa thread própria. A coordenação consulta a lista da referência e verifica o coordenador periodicamente. Se ele não responder em um segundo, começa outra eleição. O atendimento dos bots continua em outra thread. Um novo servidor de rank maior inicia a eleição ao entrar.

A cada quinze pedidos de clientes, o servidor sincroniza o relógio. O coordenador executa Berkeley: consulta os relógios dos servidores ativos, estima o atraso como metade do tempo de ida e volta, calcula a média dos desvios incluindo seu próprio desvio zero e envia a cada servidor a correção `média - desvio`. Ele também aplica a média ao próprio relógio. Servidores sem resposta não entram no cálculo. Os seguidores pedem `hora` ao coordenador e ajustam seu deslocamento. A sincronização também ocorre a cada cinco segundos para funcionar sem tráfego de clientes.

Para testar a eleição, observe nos logs quem foi eleito e pare esse serviço com `docker compose stop servidor-1` ou `docker compose stop servidor-2`. O outro assume a coordenação. Use `docker compose start` com o mesmo nome para trazê-lo de volta. O rank depende da ordem do primeiro registro, portanto o servidor de maior número no nome não é necessariamente o coordenador.

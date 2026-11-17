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

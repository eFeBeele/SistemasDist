import json
import os
import time
from pathlib import Path

import zmq

from mensagens import Mensagens


arquivo = Path(os.environ.get("DADOS", "/dados")) / "ranks.json"
arquivo.parent.mkdir(parents=True, exist_ok=True)
ranks = json.loads(arquivo.read_text()) if arquivo.exists() else {}
ativos = {}
mensagens = Mensagens("referencia")

with zmq.Context() as contexto:
    with contexto.socket(zmq.REP) as socket:
        socket.bind("tcp://*:5559")
        while True:
            pedido = mensagens.receber(socket)
            agora = time.monotonic()
            ativos = {nome: dados for nome, dados in ativos.items() if agora - dados["visto"] < 30}
            tipo = pedido.get("tipo")
            nome = pedido.get("origem", "")
            resposta = {"tipo": "resposta", "pedido_id": pedido["id"], "status": "OK"}
            if tipo in {"registrar", "heartbeat"}:
                if nome not in ranks:
                    ranks[nome] = max(ranks.values(), default=0) + 1
                    temporario = arquivo.with_suffix(".tmp")
                    with temporario.open("w") as destino:
                        json.dump(ranks, destino)
                        destino.flush()
                        os.fsync(destino.fileno())
                    temporario.replace(arquivo)
                ativos[nome] = {"nome": nome, "rank": ranks[nome], "endereco": pedido["endereco"], "visto": agora}
                resposta["rank"] = ranks[nome]
                resposta["hora"] = time.time_ns() // 1_000_000
            elif tipo == "servidores":
                resposta["servidores"] = [{k: v for k, v in dados.items() if k != "visto"} for dados in ativos.values()]
            else:
                resposta["status"] = "OPERACAO_INVALIDA"
            mensagens.enviar(socket, resposta)

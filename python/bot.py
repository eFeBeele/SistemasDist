import os
import random
import time

import msgpack
import zmq

from mensagens import Mensagens


nome = os.environ.get("NOME", "bot")
endereco = os.environ.get("BROKER", "tcp://broker:5555")
mensagens = Mensagens(nome)
nomes_canais = ["geral", "jogos", "musica", "filmes", "estudos"]

with zmq.Context() as contexto:
    inscricoes = contexto.socket(zmq.SUB)
    inscricoes.linger = 0
    inscricoes.connect(os.environ.get("PROXY", "tcp://proxy:5558"))
    assinados = set()

    def pedir(tipo, **campos):
        return mensagens.pedir(contexto, endereco, {"tipo": tipo, "usuario": nome, **campos})

    def aguardar(segundos):
        limite = time.monotonic() + segundos
        while time.monotonic() < limite:
            espera = max(1, int((limite - time.monotonic()) * 1000))
            if inscricoes.poll(espera):
                topico, conteudo = inscricoes.recv_multipart()
                mensagem = msgpack.unpackb(conteudo, raw=False)
                if mensagem.get("canal") in assinados:
                    mensagens.registrar(mensagem)

    while pedir("login")["status"] != "OK":
        time.sleep(1)

    while True:
        canais = pedir("listar")["canais"]
        faltando = list(set(nomes_canais) - set(canais))
        if len(canais) < 5 and faltando:
            novo = random.choice(faltando)
            if pedir("criar", canal=novo)["status"] in {"OK", "CANAL_EXISTENTE"}:
                canais = sorted(set(canais) | {novo})
        disponiveis = list(set(canais) - assinados)
        if len(assinados) < 3 and disponiveis:
            canal = random.choice(disponiveis)
            inscricoes.subscribe(canal.encode() + b"\x00")
            assinados.add(canal)
            print(f"[{nome}] Inscrito em {canal}", flush=True)
        if not canais:
            aguardar(1)
            continue
        canal = random.choice(canais)
        for numero in range(1, 11):
            texto = random.choice(["Alguém por aqui?", "Boa conversa!", "Como foi o dia?", "Tudo certo por aqui."])
            resposta = pedir("publicar", canal=canal, texto=f"{texto} ({numero}/10)")
            if resposta["status"] == "CANAL_INEXISTENTE":
                pedir("criar", canal=canal)
            aguardar(1)
        pedir("historico", canal=canal)

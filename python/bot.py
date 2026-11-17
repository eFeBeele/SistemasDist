import os
import random
import time

import zmq

from mensagens import Mensagens


nome = os.environ.get("NOME", "bot")
endereco = os.environ.get("BROKER", "tcp://broker:5555")
mensagens = Mensagens(nome)
nomes_canais = ["geral", "jogos", "musica", "filmes", "estudos"]

with zmq.Context() as contexto:
    def pedir(tipo, **campos):
        return mensagens.pedir(contexto, endereco, {"tipo": tipo, "usuario": nome, **campos})

    while pedir("login")["status"] != "OK":
        time.sleep(1)

    while True:
        canais = pedir("listar")["canais"]
        faltando = list(set(nomes_canais) - set(canais))
        if len(canais) < 5 and faltando:
            pedir("criar", canal=random.choice(faltando))
        time.sleep(1)

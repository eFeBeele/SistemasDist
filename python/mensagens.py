import json
import time
import uuid

import msgpack
import zmq


class Mensagens:
    def __init__(self, nome):
        self.nome = nome

    def preparar(self, mensagem):
        mensagem = dict(mensagem)
        mensagem.setdefault("id", str(uuid.uuid4()))
        mensagem["origem"] = self.nome
        mensagem["timestamp"] = time.time_ns() // 1_000_000
        return mensagem

    def enviar(self, socket, mensagem):
        mensagem = self.preparar(mensagem)
        print(f"[{self.nome}] ENVIO {json.dumps(mensagem, ensure_ascii=False)}", flush=True)
        socket.send(msgpack.packb(mensagem, use_bin_type=True))

    def receber(self, socket):
        mensagem = msgpack.unpackb(socket.recv(), raw=False)
        self.registrar(mensagem)
        return mensagem

    def registrar(self, mensagem):
        agora = time.time_ns() // 1_000_000
        print(f"[{self.nome}] RECEBIMENTO {agora} {json.dumps(mensagem, ensure_ascii=False)}", flush=True)

    def pedir(self, contexto, endereco, mensagem):
        mensagem = dict(mensagem)
        mensagem.setdefault("id", str(uuid.uuid4()))
        while True:
            with contexto.socket(zmq.REQ) as socket:
                socket.linger = 0
                socket.connect(endereco)
                self.enviar(socket, mensagem)
                if socket.poll(3000):
                    return self.receber(socket)
            print(f"[{self.nome}] Sem resposta, tentando novamente", flush=True)
            time.sleep(0.5)

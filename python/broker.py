import zmq


with zmq.Context() as contexto:
    with contexto.socket(zmq.ROUTER) as clientes, contexto.socket(zmq.DEALER) as servidores:
        clientes.bind("tcp://*:5555")
        servidores.bind("tcp://*:5556")
        print("Broker pronto: clientes em 5555 e servidores em 5556", flush=True)
        zmq.proxy(clientes, servidores)

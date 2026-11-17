import zmq


with zmq.Context() as contexto:
    with contexto.socket(zmq.XSUB) as entrada, contexto.socket(zmq.XPUB) as saida:
        entrada.bind("tcp://*:5557")
        saida.bind("tcp://*:5558")
        print("Proxy pronto: publicações em 5557 e inscrições em 5558", flush=True)
        zmq.proxy(entrada, saida)

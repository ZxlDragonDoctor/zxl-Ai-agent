import net from 'node:net'

/** An owned loopback transport fixture. Never stop a shared MCP process to inject a fault. */
export async function startTransportCut(targetPort, listenPort = 0) {
  for (const port of [targetPort, listenPort]) {
    if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('Loopback TCP ports required')
  }
  if (!targetPort || targetPort === listenPort) throw new Error('Distinct target TCP port required')
  let cut = false, forwarded = 0
  const sockets = new Set()
  const own = socket => {
    sockets.add(socket)
    socket.on('error', () => socket.destroy())
    socket.on('close', () => sockets.delete(socket))
    return socket
  }
  const server = net.createServer(incoming => {
    own(incoming)
    if (cut) { incoming.destroy(); return }
    forwarded++
    const outgoing = own(net.connect(targetPort, '127.0.0.1'))
    incoming.on('close', () => outgoing.destroy())
    outgoing.on('close', () => incoming.destroy())
    incoming.pipe(outgoing).pipe(incoming)
  })
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(listenPort, '127.0.0.1', () => { server.removeListener('error', reject); resolve() })
  })
  const disconnect = () => { cut = true; for (const socket of sockets) socket.destroy(); sockets.clear() }
  return {
    port: server.address().port,
    cut: disconnect,
    snapshot: () => ({ cut, forwarded, active: sockets.size }),
    close: async () => { disconnect(); await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())) },
  }
}

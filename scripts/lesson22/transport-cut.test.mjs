import assert from 'node:assert/strict'
import test from 'node:test'
import net from 'node:net'
import { once } from 'node:events'
import { startTransportCut } from './transport-cut.mjs'

test('owned proxy preserves discovery traffic until cut and rejects later exchanges', async () => {
  const backend = net.createServer(socket => socket.pipe(socket))
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening')
  let proxy
  try {
    proxy = await startTransportCut(backend.address().port)
    const client = net.connect(proxy.port, '127.0.0.1')
    client.on('error', () => {})
    await once(client, 'connect')
    client.write('discovery-ready')
    assert.equal((await once(client, 'data'))[0].toString(), 'discovery-ready')
    const closed = new Promise(resolve => client.once('close', resolve))
    proxy.cut(); await closed
    assert.equal(proxy.snapshot().cut, true)
    assert.equal(proxy.snapshot().active, 0)
    const later = net.connect(proxy.port, '127.0.0.1')
    later.on('error', () => {})
    await new Promise(resolve => later.once('close', resolve))
    assert.equal(proxy.snapshot().forwarded, 1)
  } finally {
    if (proxy) await proxy.close()
    await new Promise(resolve => backend.close(resolve))
  }
})

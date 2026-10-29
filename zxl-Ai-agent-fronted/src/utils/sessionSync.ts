/** Identity notifications carry no identity, CSRF token, cookie or credentials. */
export type SessionSignalAction = 'invalidate' | 'refresh'
interface SessionSignal { version: 1; sender: string; sequence: number; action: SessionSignalAction }
export interface SessionSignalTransport {
  send(value: SessionSignal): void
  listen(receive: (value: unknown) => void): () => void
}
export function createSessionSync(options: {
  sender: string; transport: SessionSignalTransport
  invalidate: () => void; refresh: () => Promise<void>
}) {
  let sequence = 0, closed = false
  const seen = new Map<string, number>()
  const unsubscribe = options.transport.listen(raw => {
    if (closed || !raw || typeof raw !== 'object') return
    const value = raw as SessionSignal
    if (Object.keys(value).sort().join(',') !== 'action,sender,sequence,version' || value.version !== 1
      || typeof value.sender !== 'string' || !value.sender || value.sender.length > 100
      || value.sender === options.sender || !Number.isSafeInteger(value.sequence) || value.sequence < 1
      || !['invalidate', 'refresh'].includes(value.action)) return
    if (value.sequence <= (seen.get(value.sender) || 0)) return
    seen.set(value.sender, value.sequence)
    // This runs before any asynchronous status read: private requests and views become stale now.
    options.invalidate()
    if (value.action === 'refresh') void options.refresh().catch(() => undefined)
  })
  return {
    publish(action: SessionSignalAction) {
      if (closed) return
      try { options.transport.send({ version: 1, sender: options.sender, sequence: ++sequence, action }) }
      catch { /* Local state stays fail-closed even if the browser disables cross-tab messaging. */ }
    },
    close() { if (!closed) { closed = true; unsubscribe(); seen.clear() } },
  }
}

const channelName = 'zxl-auth-session-v1'
export function browserSessionTransport(): SessionSignalTransport | null {
  if (typeof window === 'undefined') return null
  try {
    if (typeof window.BroadcastChannel === 'function') {
      const channel = new window.BroadcastChannel(channelName)
      return {
        send: value => channel.postMessage(value),
        listen: receive => {
          const listener = (event: MessageEvent) => receive(event.data)
          channel.addEventListener('message', listener)
          return () => { channel.removeEventListener('message', listener); channel.close() }
        },
      }
    }
  } catch { /* Use the storage event only when BroadcastChannel cannot be opened. */ }
  return {
    send: value => window.localStorage.setItem(channelName, JSON.stringify(value)),
    listen: receive => {
      const listener = (event: StorageEvent) => {
        if (event.storageArea !== window.localStorage || event.key !== channelName || !event.newValue) return
        try { receive(JSON.parse(event.newValue)) } catch { /* Ignore foreign malformed values. */ }
      }
      window.addEventListener('storage', listener)
      return () => window.removeEventListener('storage', listener)
    },
  }
}

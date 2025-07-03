// const BASE_URL = 'http://localhost:8123/api'
// 根据环境变量设置 API 基础 URL
const  BASE_URL = process.env.NODE_ENV === 'production' 
 ? '/api' // 生产环境使用相对路径，适用于前后端部署在同一域名下
 : 'http://localhost:8123/api' // 开发环境指向本地后端服务

export function chatWithLoveAppSse(message, chatId, onMessage) {
  const url = `${BASE_URL}/ai/love_app/chat/sse?message=${encodeURIComponent(message)}&chatId=${chatId}`
  const eventSource = new EventSource(url)

  eventSource.onmessage = (event) => {
    onMessage(event.data)
  }

  eventSource.onerror = (error) => {
    console.error('SSE Error:', error)
    eventSource.close()
  }

  return eventSource
}

export function chatWithManusSse(message, onMessage) {
  const url = `${BASE_URL}/ai/manus/chat?message=${encodeURIComponent(message)}`
  const eventSource = new EventSource(url)

  eventSource.onmessage = (event) => {
    onMessage(event.data)
  }

  eventSource.onerror = (error) => {
    console.error('SSE Error:', error)
    eventSource.close()
  }

  return eventSource
} 
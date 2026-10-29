type ConnectionState = { state?: string; lastError?: string | null }
export function mcpConnectionPresentation(status: ConnectionState | null | undefined) {
  const titles: Record<string,{zh:string;en:string}> = {
    READY:{zh:'服务已就绪',en:'Ready'}, UNAVAILABLE:{zh:'服务不可用',en:'Unavailable'},
    CLOSED:{zh:'连接已关闭',en:'Closed'}, CONNECTING:{zh:'正在连接',en:'Connecting'},
    DISCOVERING:{zh:'发现工具中',en:'Discovering'}, NEW:{zh:'尚未连接',en:'Not connected'},
  }
  const undiscovered = status?.lastError === 'NOT_DISCOVERED'
  return {
    title: undiscovered ? {zh:'尚未发现工具',en:'Tools not yet discovered'}
      : titles[status?.state?.toUpperCase() || ''] || {zh:'正在读取状态',en:'Loading status'},
    showError: !!status?.lastError && !undiscovered,
  }
}

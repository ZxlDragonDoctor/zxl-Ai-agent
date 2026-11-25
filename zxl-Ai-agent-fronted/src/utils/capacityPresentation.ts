/** Fixed public capacity codes only; never infer a tenant from request or model text. */
export function capacityMessage(code: string | undefined, zh: boolean): string | undefined {
  if (code === 'AGENT_TENANT_CAPACITY_EXCEEDED') return zh
    ? '当前工作空间的运行占用已达上限，请等待运行和清理完成后重试。'
    : 'Your workspace has reached its run occupancy limit. Wait for runs and cleanup to finish before retrying.'
  if (code === 'AGENT_CAPACITY_EXCEEDED') return zh
    ? '服务执行容量已满，请稍后重试。'
    : 'Execution capacity is full. Try again later.'
  if (code === 'CAPACITY_QUEUE_TIMEOUT') return zh
    ? '排队时间已达上限，本次运行未继续执行。'
    : 'The queue wait limit was reached. This run did not continue.'
  return undefined
}

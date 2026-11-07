/** A local view generation: cancellation, selection and identity changes invalidate older requests. */
export function createRunRevision() {
  let version = 0
  return {
    current: () => version,
    next: () => ++version,
    isCurrent: (candidate: number) => candidate === version,
  }
}

/** A terminal business outcome can precede the end of outstanding model/tool work. */
export function shouldPollRun(status: string | undefined, cleanupPending: boolean | undefined): boolean {
  return !!cleanupPending || !['completed', 'failed', 'cancelled'].includes(status || '')
}

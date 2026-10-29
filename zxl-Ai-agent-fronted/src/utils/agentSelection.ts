/** Discovery is authoritative for defaults; an absent legacy type must not become an implicit run. */
export function selectAvailableAgentType(catalog: ReadonlyArray<{id: string}>, current?: string | null, requested?: string | null): string {
  if (requested && catalog.some(agent => agent.id === requested)) return requested
  if (current && catalog.some(agent => agent.id === current)) return current
  return catalog[0]?.id || ''
}

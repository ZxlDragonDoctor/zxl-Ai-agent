type BilingualText = { zh: string; en: string }
export interface KnowledgeStorageInfo {
  available?: boolean; scope?: string; postgresVersion?: string; vectorVersion?: string
  tenantId?: string; embeddingMode?: string; dimensions?: number
}
/** Formal workspace identity comes from authentication, never from a storage response label. */
export function knowledgeStoragePresentation(status: KnowledgeStorageInfo | null | undefined, trustedTenant?: string) {
  const formal = !!trustedTenant || status?.scope === 'authenticated'
  const databaseDetail = !status || status.available === false
    ? { zh:'数据库连接尚未确认', en:'Database connection unconfirmed' }
    : status.postgresVersion && status.vectorVersion
      ? { zh:`PostgreSQL ${status.postgresVersion} · pgvector ${status.vectorVersion}`, en:`PostgreSQL ${status.postgresVersion} · pgvector ${status.vectorVersion}` }
      : { zh:'数据库连接已确认；版本由服务端管理', en:'Connected; versions managed by the service' }
  const dimension = typeof status?.dimensions === 'number' && Number.isInteger(status.dimensions) && status.dimensions > 0 ? ` · ${status.dimensions}D` : ''
  return {
    databaseDetail,
    embeddingDetail: status?.embeddingMode ? `${status.embeddingMode}${dimension}` : '—',
    workspace: trustedTenant || (formal ? '—' : status?.tenantId || '—'),
    scopeDetail: formal
      ? { zh:'当前租户知识范围 · 事实记忆属于当前用户', en:'Current tenant knowledge · personal confirmed facts' }
      : { zh:'课堂演示范围', en:'Classroom demonstration scope' },
  }
}
export interface EmbeddingPresentation {
  kind: 'unconfirmed' | 'demo' | 'http'
  title: BilingualText
  warning: BilingualText
}

/** Provider identity comes from the service; unknown modes never acquire an inferred quality claim. */
export function embeddingPresentation(identity: string | null | undefined): EmbeddingPresentation {
  if (identity?.startsWith('deterministic-demo-')) return {
    kind: 'demo',
    title: { zh: '演示向量 · 非语义模型', en: 'Demo vectors · not semantic' },
    warning: {
      zh: '当前使用确定性演示 Embedding 验证持久化与向量查询，距离仅供工程演示，不能据此评价中文语义召回质量。',
      en: 'Deterministic demo embeddings verify storage and vector queries. Distances do not demonstrate Chinese semantic retrieval quality.',
    },
  }
  if (identity?.startsWith('http:')) return {
    kind: 'http',
    title: { zh: '真实服务 Embedding', en: 'Live-service embedding' },
    warning: {
      zh: '服务端已配置 HTTP Embedding 模型；实际调用失败不会回退演示向量。连接与检索成功不代表召回质量已经验收，仍需固定评测集验证。',
      en: 'The service is configured with an HTTP embedding model. Failed calls do not fall back to demo vectors. Connectivity and retrieval do not establish recall quality; evaluate a fixed dataset separately.',
    },
  }
  return {
    kind: 'unconfirmed',
    title: { zh: 'Embedding 模式待确认', en: 'Embedding mode unconfirmed' },
    warning: {
      zh: '请先连接服务并核对 Embedding 模式与维度。尚未确认的模式不代表已完成向量生成或语义检索验收。',
      en: 'Connect to the service and verify the embedding mode and dimensions. An unconfirmed mode does not establish vector generation or semantic retrieval acceptance.',
    },
  }
}

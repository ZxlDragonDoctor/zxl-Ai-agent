type BilingualText = { zh: string; en: string }
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

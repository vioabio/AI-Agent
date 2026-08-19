export const queryKeys = {
  me: ["me"] as const,
  batches: ["batches"] as const,
  queue: (search: string) => ["review-queue", search] as const,
  candidate: (fingerprint: string) => ["candidate", fingerprint] as const,
  dashboard: (batch?: string, job?: string, reviewer?: string) => ["dashboard", batch, job, reviewer] as const,
  report: (batch?: string, job?: string) => ["live-report", batch, job] as const,
};

export const queryKeys = {
  me: ["me"] as const,
  analysisTasks: (page: number) => ["analysis-tasks", page] as const,
  analysisTask: (jobId: string) => ["analysis-task", jobId] as const,
  batches: ["batches"] as const,
  queue: (search: string) => ["review-queue", search] as const,
  candidate: (fingerprint: string) => ["candidate", fingerprint] as const,
  evidenceSearch: (fingerprint: string, runId: string, snapshotId: string, dimension: string) =>
    ["evidence-search", fingerprint, runId, snapshotId, dimension] as const,
  dashboard: (batch?: string, job?: string, reviewer?: string) => ["dashboard", batch, job, reviewer] as const,
  report: (batch?: string, job?: string) => ["live-report", batch, job] as const,
};

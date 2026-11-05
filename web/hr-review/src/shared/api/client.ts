import type {
  ApiErrorBody,
  BatchList,
  CandidateDetail,
  CandidatePage,
  Dashboard,
  EvidenceSearch,
  LiveReport,
  LoginResponse,
  SubmitReview,
  User,
  AnalysisTaskPage,
  AnalysisTaskDetail,
} from "./types";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: ApiErrorBody,
  ) {
    super(body.message);
  }
}

export interface QueueParams {
  batchId?: string;
  jobProfileId?: string;
  matchResult?: string;
  minScore?: number;
  maxScore?: number;
  reviewStatus?: string;
  reviewerName?: string;
  communicationStatus?: string;
  hasShareLink?: boolean;
  keyword?: string;
  scope: string;
  page: number;
  pageSize: number;
  sortBy: string;
  sortOrder: "asc" | "desc";
}

export const api = {
  login: (username: string, password: string) =>
    request<LoginResponse>("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),
  logout: () => request<{ message: string }>("/api/v1/auth/logout", { method: "POST" }),
  me: () => request<User>("/api/v1/me"),
  analysisTasks: (page: number) => request<AnalysisTaskPage>(`/api/v1/analysis-tasks?${new URLSearchParams({ page: String(page), page_size: "20" })}`),
  analysisTask: (jobId: string) => request<AnalysisTaskDetail>(`/api/v1/analysis-tasks/${encodeURIComponent(jobId)}`),
  retryAnalysisTask: (jobId: string, leaseToken: number) => request<AnalysisTaskDetail>(`/api/v1/analysis-tasks/${encodeURIComponent(jobId)}/retry`, {
    method: "POST", body: JSON.stringify({ lease_token: leaseToken }),
  }),
  batches: () => request<BatchList>("/api/v1/batches"),
  queue: (params: QueueParams) =>
    request<CandidatePage>(`/api/v1/review-queue?${queueSearchParams(params).toString()}`),
  candidate: (fingerprint: string) =>
    request<CandidateDetail>(`/api/v1/candidates/${encodeURIComponent(fingerprint)}`),
  evidenceSearch: (fingerprint: string, dimensionId: string) =>
    request<EvidenceSearch>(`/api/v1/candidates/${encodeURIComponent(fingerprint)}/evidence-search?${new URLSearchParams({ dimension_id: dimensionId })}`),
  submitReview: (fingerprint: string, payload: SubmitReview) =>
    request<CandidateDetail>(`/api/v1/candidates/${encodeURIComponent(fingerprint)}/review`, {
      method: "PATCH",
      body: JSON.stringify(payload),
    }),
  dashboard: (batchId?: string, jobProfileId?: string, reviewerName?: string) =>
    request<Dashboard>(`/api/v1/dashboard/summary?${filterSearchParams(batchId, jobProfileId, reviewerName)}`),
  liveReport: (batchId?: string, jobProfileId?: string) =>
    request<LiveReport>(`/api/v1/reports/live-preview?${filterSearchParams(batchId, jobProfileId)}`),
};

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const cookiePrefix = import.meta.env.VITE_HR_COOKIE_PREFIX ||
    (import.meta.env.VITE_HR_DEMO === "true" ? "hr_demo" : "hr_review");
  const csrf = readCookie(`${cookiePrefix}_csrf`);
  const response = await fetch(path, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(csrf ? { "X-CSRF-Token": csrf } : {}),
      ...init.headers,
    },
  });
  if (!response.ok) {
    const body = (await response.json()) as ApiErrorBody;
    throw new ApiError(response.status, body);
  }
  return (await response.json()) as T;
}

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`;
  return document.cookie
    .split(";")
    .map((item) => item.trim())
    .find((item) => item.startsWith(prefix))
    ?.slice(prefix.length);
}

function queueSearchParams(params: QueueParams): URLSearchParams {
  const search = new URLSearchParams({
    scope: params.scope,
    page: String(params.page),
    page_size: String(params.pageSize),
    sort_by: params.sortBy,
    sort_order: params.sortOrder,
  });
  appendOptional(search, "batch_id", params.batchId);
  appendOptional(search, "job_profile_id", params.jobProfileId);
  appendOptional(search, "match_result", params.matchResult);
  appendOptional(search, "min_score", params.minScore);
  appendOptional(search, "max_score", params.maxScore);
  appendOptional(search, "review_status", params.reviewStatus);
  appendOptional(search, "reviewer_name", params.reviewerName);
  appendOptional(search, "communication_status", params.communicationStatus);
  appendOptional(search, "has_share_link", params.hasShareLink);
  appendOptional(search, "keyword", params.keyword);
  return search;
}

function filterSearchParams(batchId?: string, jobProfileId?: string, reviewerName?: string): string {
  const search = new URLSearchParams();
  appendOptional(search, "batch_id", batchId);
  appendOptional(search, "job_profile_id", jobProfileId);
  appendOptional(search, "reviewer_name", reviewerName);
  return search.toString();
}

function appendOptional(
  search: URLSearchParams,
  key: string,
  value: string | number | boolean | undefined,
): void {
  if (value !== undefined && value !== "") search.set(key, String(value));
}

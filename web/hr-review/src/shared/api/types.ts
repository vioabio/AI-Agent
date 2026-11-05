import type { components } from "./schema";

export type User = components["schemas"]["UserResponse"];
export type LoginResponse = components["schemas"]["LoginResponse"];
export type CandidateSummary = components["schemas"]["CandidateSummaryResponse"];
export type CandidatePage = components["schemas"]["CandidatePageResponse"];
export type CandidateDetail = components["schemas"]["CandidateDetailResponse"];
export type AnalysisSnapshot = components["schemas"]["AnalysisSnapshotResponse"];
export type EvidenceReference = components["schemas"]["EvidenceReferenceResponse"];
export type EvidenceSearch = components["schemas"]["EvidenceSearchResponse"];
export type EvidenceSearchHit = components["schemas"]["EvidenceSearchHitResponse"];
export type SubmitReview = components["schemas"]["SubmitReviewRequest"];
export type Dashboard = components["schemas"]["DashboardResponse"];
export type LiveReport = components["schemas"]["LiveReportResponse"];
export type BatchList = components["schemas"]["BatchListResponse"];
export type ApiErrorBody = components["schemas"]["ErrorResponse"];
export type AnalysisTask = components["schemas"]["AnalysisTaskResponse"];
export type AnalysisTaskPage = components["schemas"]["AnalysisTaskPageResponse"];
export type AnalysisTaskDetail = components["schemas"]["AnalysisTaskDetailResponse"];

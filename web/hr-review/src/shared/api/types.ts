import type { components } from "./schema";

export type User = components["schemas"]["UserResponse"];
export type LoginResponse = components["schemas"]["LoginResponse"];
export type CandidateSummary = components["schemas"]["CandidateSummaryResponse"];
export type CandidatePage = components["schemas"]["CandidatePageResponse"];
export type CandidateDetail = components["schemas"]["CandidateDetailResponse"];
export type SubmitReview = components["schemas"]["SubmitReviewRequest"];
export type Dashboard = components["schemas"]["DashboardResponse"];
export type LiveReport = components["schemas"]["LiveReportResponse"];
export type BatchList = components["schemas"]["BatchListResponse"];
export type ApiErrorBody = components["schemas"]["ErrorResponse"];

import { useQuery } from "@tanstack/react-query";
import { lazy } from "react";
import { Navigate, Route, Routes } from "react-router";

import { AsyncContent } from "../shared/components/AsyncContent";
import { api } from "../shared/api/client";
import { queryKeys } from "../shared/api/queryKeys";
import { LoadingState } from "../shared/components/LoadingState";
const LoginPage = lazy(() => import("../features/auth/LoginPage").then((module) => ({ default: module.LoginPage })));
const WorkbenchPage = lazy(() => import("./WorkbenchPage").then((module) => ({ default: module.WorkbenchPage })));
const AnalysisTasksPage = lazy(() => import("../features/analysis-tasks/AnalysisTasksPage").then((module) => ({ default: module.AnalysisTasksPage })));

export function App() {
  const session = useQuery({ queryKey: queryKeys.me, queryFn: api.me, retry: false });
  // A window-focus refetch without cached user data becomes `isLoading` again.
  // Keep the current route mounted after the first session check so an
  // unauthenticated user's in-progress login form is not discarded on focus.
  if (!session.isFetched) return <LoadingState label="正在验证登录状态" />;

  return (
    <Routes>
      <Route path="/login" element={session.data ? <Navigate to="/" replace /> : <AsyncContent key="login" label="登录页面"><LoginPage /></AsyncContent>} />
      <Route path="/" element={session.data ? <AsyncContent key="workbench" label="工作台"><WorkbenchPage user={session.data} /></AsyncContent> : <Navigate to="/login" replace />} />
      <Route path="/analysis-tasks" element={session.data ? <AsyncContent key="tasks" label="影子分析任务"><AnalysisTasksPage /></AsyncContent> : <Navigate to="/login" replace />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

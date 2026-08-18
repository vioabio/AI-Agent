import { useQuery } from "@tanstack/react-query";
import { Navigate, Route, Routes } from "react-router";

import { LoginPage } from "../features/auth/LoginPage";
import { api } from "../shared/api/client";
import { queryKeys } from "../shared/api/queryKeys";
import { LoadingState } from "../shared/components/LoadingState";
import { WorkbenchPage } from "./WorkbenchPage";

export function App() {
  const session = useQuery({ queryKey: queryKeys.me, queryFn: api.me, retry: false });
  if (session.isLoading) return <LoadingState label="正在验证登录状态" />;

  return (
    <Routes>
      <Route path="/login" element={session.data ? <Navigate to="/" replace /> : <LoginPage />} />
      <Route path="/" element={session.data ? <WorkbenchPage user={session.data} /> : <Navigate to="/login" replace />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

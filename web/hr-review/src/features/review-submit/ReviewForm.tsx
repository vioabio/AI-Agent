import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Form, Modal, Select, Space, Typography } from "antd";
import TextArea from "antd/es/input/TextArea";
import { Controller, useForm } from "react-hook-form";
import { z } from "zod";

import { ApiError, api } from "../../shared/api/client";
import { queryKeys } from "../../shared/api/queryKeys";
import type { CandidateDetail } from "../../shared/api/types";
import { reviewStatusOptions } from "../review-queue/reviewStatuses";

const schema = z.object({
  review_status: z.enum(["pending_review", "recommended_contact", "contacted", "interviewing", "on_hold", "resume_rejected", "interview_rejected", "hired"]),
  feedback: z.string().trim().min(2, "请填写至少 2 个字的反馈").max(4000, "反馈不能超过 4000 字"),
});
type Values = z.infer<typeof schema>;
const terminalStatuses = new Set<Values["review_status"]>(["resume_rejected", "interview_rejected", "hired"]);

export function ReviewForm({ detail, canSubmit }: { detail: CandidateDetail; canSubmit: boolean }) {
  const queryClient = useQueryClient();
  const { control, handleSubmit, formState: { errors }, reset } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { review_status: detail.summary.review_status as Values["review_status"], feedback: "" },
  });
  const mutation = useMutation({
    mutationFn: (values: Values) => api.submitReview(detail.summary.candidate_fingerprint, { ...values, revision: detail.summary.revision, assignee_id: null }),
    onSuccess: async (result) => {
      queryClient.setQueryData(queryKeys.candidate(detail.summary.candidate_fingerprint), result);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["review-queue"] }),
        queryClient.invalidateQueries({ queryKey: ["dashboard"] }),
        queryClient.invalidateQueries({ queryKey: ["live-report"] }),
      ]);
      reset({ review_status: result.summary.review_status as Values["review_status"], feedback: "" });
    },
  });
  const conflict = mutation.error instanceof ApiError && mutation.error.status === 409;
  const submit = (values: Values) => {
    if (!terminalStatuses.has(values.review_status)) return mutation.mutate(values);
    Modal.confirm({
      title: "确认结束该候选人的招聘流程？",
      content: "结束状态会将候选人移出待审核和推进中列表，但历史记录仍会永久保留。",
      okText: "确认提交",
      okButtonProps: { danger: values.review_status !== "hired" },
      onOk: async () => { await mutation.mutateAsync(values); },
    });
  };

  if (!canSubmit) return <Alert type="info" showIcon message="当前账号为只读权限，不能修改 HR 状态。" />;
  return (
    <Form layout="vertical" onFinish={() => { void handleSubmit(submit)(); }}>
      {conflict ? <Alert type="warning" showIcon message="数据已被其他人修改" description={<Button type="link" onClick={() => { void queryClient.invalidateQueries({ queryKey: queryKeys.candidate(detail.summary.candidate_fingerprint) }); }}>重新加载最新数据</Button>} /> : null}
      {mutation.isError && !conflict ? <Alert type="error" showIcon message={mutation.error instanceof Error ? mutation.error.message : "提交失败"} /> : null}
      <Controller control={control} name="review_status" render={({ field }) => (
        <Form.Item label="新状态" htmlFor="review-status" required validateStatus={errors.review_status ? "error" : undefined} help={errors.review_status?.message}>
          <Select {...field} id="review-status" options={reviewStatusOptions} />
        </Form.Item>
      )} />
      <Controller control={control} name="feedback" render={({ field }) => (
        <Form.Item label="反馈备注" htmlFor="review-feedback" required validateStatus={errors.feedback ? "error" : undefined} help={errors.feedback?.message}>
          <TextArea {...field} id="review-feedback" rows={4} showCount maxLength={4000} placeholder="记录判断依据、沟通结果或下一步安排" />
        </Form.Item>
      )} />
      <Space><Button type="primary" htmlType="submit" loading={mutation.isPending} disabled={mutation.isPending}>提交审核</Button><Typography.Text type="secondary">当前版本 {detail.summary.revision}</Typography.Text></Space>
    </Form>
  );
}

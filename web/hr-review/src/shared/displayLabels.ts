export const reviewStatusLabels: Record<string, string> = {
  pending_review: "待审核",
  recommended_contact: "建议沟通",
  contacted: "已联系",
  interviewing: "面试中",
  on_hold: "暂缓观察",
  resume_rejected: "简历淘汰",
  interview_rejected: "面试淘汰",
  hired: "已录用",
};

export const matchResultLabels: Record<string, string> = {
  relevant: "匹配",
  needs_review: "待复核",
  not_relevant: "不匹配",
};

export const communicationStatusLabels: Record<string, string> = {
  sent: "已发送",
  previously_contacted: "历史已沟通",
  failed: "发送失败",
  not_sent: "未发送",
  hit: "待沟通",
  reviewed: "已浏览",
};

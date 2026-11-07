import { Alert, Descriptions, Empty, Typography } from "antd";

import type { AnalysisTaskDetail } from "../../shared/api/types";

const states: Record<string, string> = {
  running: "进行中", succeeded: "完成", failed: "失败", abandoned: "已放弃", unknown: "待核验",
  started: "已开始，尚无完成回执", received: "已收到响应", http_error: "HTTP 异常",
  timeout: "请求超时", connection_error: "连接异常", invalid_response: "响应无效", not_sent: "未发送",
  reserved: "预算已预留", error: "请求异常",
};
const errors: Record<string, string> = {
  timeout: "调用超时", rate_limited: "供应商限流", provider_unavailable: "服务暂不可用",
  worker_error: "执行异常", invalid_output: "输出校验失败", authentication: "认证失败",
  budget_exhausted: "预算不足", cancelled: "已取消", lease_expired: "租约已过期",
  deadline_exceeded: "超过任务时限", model_mismatch: "模型身份不符", usage_invalid: "用量无效",
  quota_exhausted: "供应商额度不足", retry_delay_exceeded: "等待超出允许时限",
};
const known = (value: number | null) => value === null ? "未知" : value.toLocaleString("zh-CN");
const timestamp = (value: string | null) => value === null ? "尚未结束" : new Date(value).toLocaleString("zh-CN", { hour12: false });

export function ExecutionDetails({ execution }: { execution: AnalysisTaskDetail["execution"] }) {
  if (!execution) return null;
  return <section aria-label="任务运行记录">
    <Typography.Title level={5}>尝试记录与预算</Typography.Title>
    <Alert type="info" message="以下为合成运行记录，预算占用包含保守预留，不是实际账单。"
      description="缺失用量显示未知；没有请求回执不代表从未发送请求。收到响应也不等于结果校验成功。" />
    <Descriptions column={1} size="small" items={[
      { key: "tokens", label: "合成 token 单位占用 / 上限", children: `${known(execution.charged_tokens)} / ${known(execution.max_tokens)}` },
      { key: "cost", label: "合成费用微单位占用 / 上限", children: `${known(execution.charged_cost_micros)} / ${known(execution.max_cost_micros)}` },
      { key: "price", label: "合成计价口径", children: `${execution.currency} · ${execution.price_version}` },
    ]} />
    {execution.history_truncated ? <Alert type="warning" message="记录数量超出展示上限，仅展示前 10 次，请核验数据。" /> : null}
    {!execution.attempts.length ? <Empty description="尚无领取尝试记录" image={Empty.PRESENTED_IMAGE_SIMPLE} /> :
      <ol>{execution.attempts.map((attempt) => <li key={attempt.sequence}>
        <Typography.Text strong>第 {attempt.sequence} 次尝试 · {states[attempt.status]}</Typography.Text>
        <Typography.Paragraph>{timestamp(attempt.started_at)} — {timestamp(attempt.finished_at)}</Typography.Paragraph>
        {attempt.error_code ? <Typography.Paragraph>原因：{errors[attempt.error_code] ?? "需要核验"}</Typography.Paragraph> : null}
        <Descriptions column={1} size="small" items={[
          { key: "reserved", label: "预留 token / 费用微单位", children: `${known(attempt.reserved_tokens)} / ${known(attempt.reserved_cost_micros)}` },
          { key: "reported", label: "报告用量 / 估算费用微单位", children: `${known(attempt.reported_tokens)} / ${known(attempt.reported_cost_micros)}` },
        ]} />
        {attempt.request ? <details>
          <summary>请求回执 · {states[attempt.request.state]}</summary>
          <Descriptions column={1} size="small" items={[
            { key: "http", label: "HTTP 状态", children: attempt.request.http_status ?? "未知" },
            { key: "time", label: "本地请求耗时（毫秒）", children: known(attempt.request.latency_ms) },
            { key: "usage", label: "输入 / 输出用量", children: `${known(attempt.request.input_tokens)} / ${known(attempt.request.output_tokens)}` },
            { key: "end", label: "回执完成时间", children: timestamp(attempt.request.completed_at) },
          ]} />
          {attempt.request.error_code ? <p>请求说明：{errors[attempt.request.error_code] ?? "需要核验"}</p> : null}
        </details> : <Typography.Paragraph>{execution.tool_rounds?.length ? "工具请求回执见下方回合记录" : "未记录请求回执"}</Typography.Paragraph>}
      </li>)}</ol>}
    {execution.tool_rounds?.length ? <section aria-label="工具请求回合">
      <Typography.Title level={5}>工具请求回合</Typography.Title>
      <Typography.Paragraph>回放已保存的响应不会重复请求；开始后缺失响应的回合需人工核验。每回合沿用任务共享预算。</Typography.Paragraph>
      <ol>{execution.tool_rounds.map((round) => <li key={round.sequence}>
        <Typography.Text strong>第 {round.sequence} 回合 · {states[round.state]}</Typography.Text>
        <p>所属领取尝试：{round.attempt_sequence ?? "待核验"}</p>
        <Descriptions column={1} size="small" items={[
          { key: "reserve", label: "预留 token / 费用微单位", children: `${known(round.reserved_tokens)} / ${known(round.reserved_cost_micros)}` },
          { key: "usage", label: "输入 / 输出用量", children: `${known(round.input_tokens)} / ${known(round.output_tokens)}` },
          { key: "cost", label: "估算费用微单位", children: known(round.reported_cost_micros) },
          { key: "time", label: "回合开始 / 结束", children: `${timestamp(round.started_at)} / ${timestamp(round.finished_at)}` },
        ]} />
      </li>)}</ol>
    </section> : null}
  </section>;
}

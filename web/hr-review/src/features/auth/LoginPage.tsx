import { LockOutlined, SafetyCertificateOutlined, UserOutlined } from "@ant-design/icons";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Card, Form, Input, Typography } from "antd";
import { useNavigate } from "react-router";

import { ApiError, api } from "../../shared/api/client";
import { queryKeys } from "../../shared/api/queryKeys";

interface LoginValues {
  username: string;
  password: string;
}

export function LoginPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const login = useMutation({
    mutationFn: (values: LoginValues) => api.login(values.username, values.password),
    onSuccess: async (result) => {
      queryClient.setQueryData(queryKeys.me, result.user);
      await navigate("/");
    },
  });
  const error = login.error instanceof ApiError ? login.error.message : undefined;

  return (
    <main className="login-shell">
      <section className="login-intro">
        <div className="brand-mark"><SafetyCertificateOutlined /></div>
        <Typography.Title>候选人审核与招聘进度工作台</Typography.Title>
        <Typography.Paragraph>
          将 AI 初筛事实、HR 人工判断与招聘推进状态汇聚在一个可审计的工作空间。
        </Typography.Paragraph>
        <div className="login-note">内部系统 · 所有敏感访问和状态修改均会记录</div>
      </section>
      <Card className="login-card" variant="borderless">
        <Typography.Title level={3}>欢迎回来</Typography.Title>
        <Typography.Paragraph type="secondary">使用内部账号登录</Typography.Paragraph>
        {error ? <Alert type="error" message={error} showIcon closable /> : null}
        <Form<LoginValues> layout="vertical" onFinish={(values) => { login.mutate(values); }} requiredMark={false}>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: "请输入用户名" }]}>
            <Input prefix={<UserOutlined />} size="large" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, min: 8, message: "请输入至少 8 位密码" }]}>
            <Input.Password prefix={<LockOutlined />} size="large" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" size="large" block loading={login.isPending}>
            登录工作台
          </Button>
        </Form>
      </Card>
    </main>
  );
}

import { Flex, Spin, Typography } from "antd";

export function LoadingState({ label }: { label: string }) {
  return (
    <Flex className="full-state" vertical align="center" justify="center" gap={14}>
      <Spin size="large" />
      <Typography.Text type="secondary">{label}</Typography.Text>
    </Flex>
  );
}

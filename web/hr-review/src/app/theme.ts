import type { ThemeConfig } from "antd";

export const theme: ThemeConfig = {
  token: {
    colorPrimary: "#1f6f78",
    colorInfo: "#1f6f78",
    colorSuccess: "#287a55",
    colorWarning: "#c27a19",
    colorError: "#b84b4b",
    borderRadius: 8,
    fontFamily: 'Inter, "PingFang SC", "Microsoft YaHei", sans-serif',
    colorBgLayout: "#f3f6f8",
    colorText: "#142239",
  },
  components: {
    Table: { headerBg: "#eef3f5", headerColor: "#40536a", cellPaddingBlockSM: 10 },
    Card: { paddingLG: 18 },
    Layout: { headerBg: "#10233f", siderBg: "#ffffff" },
  },
};

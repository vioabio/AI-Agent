import { Component, Suspense, type ReactNode } from "react";

export class AsyncContent extends Component<{ label: string; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    const { label, children } = this.props;
    if (this.state.failed) return (
      <section className="async-content" role="alert">
        <p>{label}加载失败，请刷新后重试。</p>
        <p>刷新会清除尚未提交的内容。</p>
        <button type="button" onClick={() => window.location.reload()}>刷新页面</button>
      </section>
    );
    return <Suspense fallback={<p className="async-content" role="status">正在加载{label}…</p>}>{children}</Suspense>;
  }
}

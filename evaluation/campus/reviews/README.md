# 回答与引用审核台

将固定三组开发对照中的问题、实际回答、原文与审核项放在一个离线页面中。页面按文字展示模型输出，保存草稿并导出逐项结论；导入时重新核对题目、来源、回答观测摘要和审核身份类型。人工身份是自述，不提供身份认证；摘要用于内容绑定，不是数字签名或来源真实性认证。

## 打开已有材料

仓库保留[当前修复后的审核包](../../../document/秋招升级/S2/evidence/review-workbench-v2/bundle.json)。在项目根目录运行，下方输出目录必须尚不存在：

```powershell
node tools/campus/review-workbench.mjs render document/秋招升级/S2/evidence/review-workbench-v2/bundle.json artifacts/campus/reviews/my-review
```

用浏览器打开生成的 `artifacts/campus/reviews/my-review/review.html`。它不连接模型或后端，文件中包含本批问题、回答和项目原创来源。原始回答中的 Markdown 按文字保留，防止审核材料中的 HTML 或命令被当作页面代码。

操作顺序：

1. 选择一条回答，对照右侧原文核对结论。原文匹配只说明文字存在；“真实但不切题”应单列为引用问题。
2. 为每项选择通过、不通过或未知，填写理由；通过项需选择依据。每段引用单独判断是否支持且相关。
3. 填写审核者、总说明和类型。人工审核需勾选实际核对声明，再点击“将当前条加入导出”。可只审核部分回答。
4. 点击“导出已确认审核”，保存 JSON。修改已确认条目会取消其确认；导入草稿也必须重新确认。
5. 导入工具生成新报告，不覆盖旧报告。草稿不能直接作为完成审核提交。

```powershell
# 第三个参数替换成浏览器实际下载的审核 JSON 路径
node tools/campus/review-workbench.mjs report artifacts/campus/reviews/my-review/bundle.json artifacts/campus/reviews/my-reviewed ./vio-submitted-reviews.json
```

只有完整、绑定正确、自述为人工且实际勾选声明的结论，才可能计为人工通过；未知保持待审核。AI辅助意见单列，即使全部填“通过”也不计入人工通过数。原文未匹配、工程失败不能被人工通过覆盖。工具校验格式和绑定关系，不声称能认证操作人。

## 从原始采集构建

```powershell
node tools/campus/review-workbench.mjs build artifacts/campus/project-guide/verified/model-capture-2026-10-26/capture.json artifacts/campus/project-guide/verified/guides-capture-2026-10-26/capture.json artifacts/campus/project-guide/verified/tools-capture-2026-10-26/capture.json artifacts/campus/reviews/from-raw
```

构建先重读事件和历史，核对原始摘要、问题、流式回答、工具边界、模型调用及用量；三组配置身份不同会拒绝。历史版本材料允许在后续代码版本中审核，但不能冒充新运行；输出保留原输入身份，另用 receipt 记录本次工具源码和生成物摘要。

`render` 和 `report` 核对冻结问题、审核项和全部来源内容。它们根据已构建审核包工作；没有原始文件时不能重新证明原始采集过程，也不把包摘要当真实性认证。重新构建时仍需原始采集文件。

## 验证

```powershell
node --test tools/reference-cache.test.mjs tools/campus/*.test.mjs
# 需要现有前端 Playwright 依赖和本机 Edge；只启动独立无头浏览器
node tools/campus/review-workbench-browser.mjs artifacts/campus/reviews/my-review artifacts/campus/reviews/my-browser-fixture
```

浏览器测试导出明确标记为AI辅助的合成测试记录，不能当作真实语义审核。首批实际状态和边界见[实施记录](../../../document/秋招升级/S2/回答审核台与问题收集_2026-10-26.md)。

原始AI辅助初筛发现缺段后已做[完整性修复](../../../document/秋招升级/S2/项目说明完整性修复_2026-10-26.md)。修复前包保留在`review-workbench-v1`，新回答使用`review-workbench-v2`；不同观测不能复用审核结论，当前新版六条均待审核。

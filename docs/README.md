# 项目文档

这里只保留经当前代码验证仍有效的文档。旧需求、历史计划和代码审计位于 `archive/`，不能作为当前实现依据。

## 用户文档

- [`user-guide.md`](user-guide.md)：敲敲记账用户使用文档（安装、记账、手势、AI、资产预算、备份迁移、FAQ）

## 备份与恢复

- [`backup-restore-rules.md`](backup-restore-rules.md)：恢复判定规则、共享账本冲突面、覆盖恢复的依赖与不变量

## 账单搜索

- [`bill-search.md`](bill-search.md)：对标钱迹的搜索页实现（过滤面板、汇总口径、分组排序、弹窗复用、与钱迹的差异）

## 需求与设计

- [`pending-expense-capture.md`](pending-expense-capture.md)：独立 Xposed 插件捕获微信/支付宝支出，插件内维护待记收件箱，通过桥接接口写入敲敲记账（需求确认稿，未实现）
- [`pending-expense-capture-handoff.md`](pending-expense-capture-handoff.md)：上述方案的技术交接（两份源码摘录、token 鉴权、App AI 代理、ID 导入契约、hook 选型、风险与评审问题）

## 运维与发布

- [`release-regression-checklist.md`](operations/release-regression-checklist.md)
- [`capture-plugin-p0-test.md`](operations/capture-plugin-p0-test.md)：支付捕获插件 P0 的安装、闭环测试与微信/支付宝探针步骤
- [`capture-plugin-handoff-2026-11-02.md`](operations/capture-plugin-handoff-2026-11-02.md)：支付捕获插件当前进度、已拉取的 AutoAccounting 规则、样本解析与下一步交接
- [`autoaccounting-wechat-alipay-hooks.md`](operations/autoaccounting-wechat-alipay-hooks.md)：AutoAccounting 微信/支付宝 hook 接入代码参考

## 代码审阅

- [`shared-book-review-2026-08-29.md`](reviews/shared-book-review-2026-08-29.md)：共享账本 1–5 人实现、兼容策略、已修问题、剩余风险与坚果云验收清单

## 历史归档

- `archive/plans/`：已完成或已过时的需求与实施计划
- `archive/2026-06-code-audit/`：基于 2026 年 6 月代码快照的审计记录

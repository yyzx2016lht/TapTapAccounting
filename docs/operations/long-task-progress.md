# 长任务进度

> 文档：`C:\Users\tao\.opencode\plan\flipaccounting-ai-long-task.md`
> 编排者 session：`ses_e58a13a2dffe1cW2WQTkPXVACV`
> 分支：`tap`

| ID | 功能 | 状态 | 子代理 session | 最近 commit | APK 目录 | 备注 |
|---|---|---|---|---|---|---|
| F01 | 文案与来源注册表 | DONE | ses_e589b0cf5ffeWniCQ36L46jkV0 | 91f4eba | dist/long-task/F01-copy-source | 编译通过；capture-contract 测试 4 项通过；grep 无旧文案 |
| F02 | 分类语义重构 | DONE | ses_e5894643efferHVaaqFBhgRa9Z | 16acb2f | dist/long-task/F02-classification-semantics | 编译通过；插件单测 6 项通过；DB v7 迁移加 categorySource，历史分类保留 |
| F03 | 轻量智能归类与无备注跳过 | DONE | ses_e588dc85fffeA6q1rkhUdCrwyk | 7fe07db | dist/long-task/F03-smart-classification | 子代理网络中断未回报，编排者复核 diff+编译通过；app 单测 6 项通过；缺分类弹窗补分类 |
| F04 | 气泡三态 + 直接记账 + 不计/恢复 | DONE | ses_e586187f4ffevuy5fdv0rQEJBx | 待记录 | dist/long-task/F04-bubble-three-states | 编译通过；插件单测 7 项通过；已不计可恢复，✕ 不改状态 |
| F05 | AI 规则学习 | TODO | - | - | - | |
| F06 | 文字拆分记账 + 支付组合并 | TODO | - | - | - | |
| F07 | 悬浮窗待记上下文条 + 待记/新账切换 | TODO | - | - | - | |
| F08 | AI 面板缩小常驻小圆 | TODO | - | - | - | |
| F09 | 规则页 + 数据规则 + 本机同步 + 自定义规则 | TODO | - | - | - | |
| F10 | 图片记账 + 拆单合并 | TODO | - | - | - | |
| F11 | 最终回归 | TODO | - | - | - | |

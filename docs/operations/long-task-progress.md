# 长任务进度

> 文档：`C:\Users\tao\.opencode\plan\flipaccounting-ai-long-task.md`
> 编排者 session：`ses_e58a13a2dffe1cW2WQTkPXVACV`
> 分支：`tap`

| ID | 功能 | 状态 | 子代理 session | 最近 commit | APK 目录 | 备注 |
|---|---|---|---|---|---|---|
| F01 | 文案与来源注册表 | DONE | ses_e589b0cf5ffeWniCQ36L46jkV0 | 91f4eba | dist/long-task/F01-copy-source | 编译通过；capture-contract 测试 4 项通过；grep 无旧文案 |
| F02 | 分类语义重构 | DONE | ses_e5894643efferHVaaqFBhgRa9Z | 16acb2f | dist/long-task/F02-classification-semantics | 编译通过；插件单测 6 项通过；DB v7 迁移加 categorySource，历史分类保留 |
| F03 | 轻量智能归类与无备注跳过 | DONE | ses_e588dc85fffeA6q1rkhUdCrwyk | 7fe07db | dist/long-task/F03-smart-classification | 子代理网络中断未回报，编排者复核 diff+编译通过；app 单测 6 项通过；缺分类弹窗补分类 |
| F04 | 气泡三态 + 直接记账 + 不计/恢复 | DONE | ses_e586187f4ffevuy5fdv0rQEJBx | 503b0af | dist/long-task/F04-bubble-three-states | 编译通过；插件单测 7 项通过；已不计可恢复，✕ 不改状态 |
| F05 | AI 规则学习 | DONE | ses_e585864e1ffedSmJhGBR6mS74Z | 409e177 | dist/long-task/F05-ai-rule-learning | 编译通过；app 586 项、插件 25 项测试通过；DB v8 加规则来源与学习任务表 |
| F06 | 文字拆分记账 + 支付组合并 | DONE | ses_e584a64edffeXhme3ysgRUSr7L | c4ed69f | dist/long-task/F06-text-split-payment-group | 编译通过；632 项测试通过；App DB v41 + 插件 DB v9；importBillGroup 原子幂等 |
| F07 | 悬浮窗待记上下文条 + 待记/新账切换 | DONE | ses_e583596f8ffejBnFDJ8vJWAj5r | 28012b1 | dist/long-task/F07-overlay-capture-context | 编译通过；632 项测试通过；细条替代双层卡片，双模式草稿互不覆盖 |
| F08 | AI 面板缩小常驻小圆 | DONE | ses_e58271a01ffeBWgC3WHqpxXmMF | d14fdcf | dist/long-task/F08-ai-panel-mini-bubble | 编译通过；596 项 app 测试通过；dialog.hide 保留输入，小圆可拖可吸附，X 才关闭 |
| F09 | 规则页 + 数据规则 + 本机同步 + 自定义规则 | DONE | ses_e5811ebcdffeoT5GcemJLXoO14 | a84a243 | dist/long-task/F09-rule-page-sync | 编译通过；插件 37 项测试通过；bundle 原子写、用户规则优先、本机 52045 同步 |
| F10 | 图片记账 + 拆单合并 | DONE | ses_e57fea097ffejmyMirinfPo8fB | 待记录 | dist/long-task/F10-image-accounting-split | 编译通过；ImageAccountingCapture 9 项测试通过；复用 importBillGroup 原子幂等 |
| F11 | 最终回归 | TODO | - | - | - | |

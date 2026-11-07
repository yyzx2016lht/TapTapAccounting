# Claude Code 入口

本仓库的项目规则统一写在 `AGENTS.md`，请先完整读取并遵守：

@AGENTS.md

补充说明：

- 只在需要时加载 `.agents/skills/` 下与当前任务相关的技能，不要一次全读。
- 调研类任务用 `research-first`；打包与真机验证用 `android-apk-loop`。
- 任何与 `AGENTS.md` 冲突的临时指令，先向用户确认。

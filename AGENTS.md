# 删除必须可恢复

任何 agent 在本仓库删除文件前，先确认它是否能找回：

1. **未被 Git 跟踪 / 被 .gitignore 忽略**（Git 里没有副本）→ 必须留一份：
   - `trash "<path>"`（进回收站，支持文件、目录、多个参数、通配符）
   - 没有 `trash` 就 `Move-Item "<path>" "$env:TEMP\deleted-<日期>/"`
2. **已被 Git 跟踪且没有未提交修改** → 可以 `rm`，`git restore "<path>"` 能还原。
3. **已被 Git 跟踪但有未提交修改** → 用 `trash`，`git restore` 救不回这些改动。
4. 拿不准 → 一律 `trash`。

禁止：`git clean -fdx`、对未跟踪路径 `rm -rf` / `Remove-Item -Recurse -Force`、清空回收站、先删再重建。

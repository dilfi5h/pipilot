## Plan: 拉取 pi.dev 上游代码到本地（不提交）

用户已确认：上游 = `https://github.com/earendil-works/pi`（用户给的是 tree 路径 `.../tree/main/packages/coding-agent`，clone 用仓库根 `https://github.com/earendil-works/pi.git`，重点看 `packages/coding-agent` 子目录）；目录 = `vendor/pi-upstream`；浅克隆 `--depth 1`。

### 背景（已调查清楚）
- 仓库顶层无 `vendor/`，无重名冲突；磁盘 C: 可用 82G，充足。
- `github.com` 命中 `~/.zcode/gfwdomains.txt`，按 AGENTS.md 后续 clone **必须走 `socks5h://localhost:1140`**，禁止裸连；网络任务超 1 分钟无进展输出就 kill，不许死等。

### 步骤 1：`.gitignore` 加忽略（唯一改动，1 行 + 注释）
沿用现有"分类注释 + glob"风格，在文件末尾追加：
```
# Upstream pi.dev source (reference only, never commit)
vendor/
```

### 步骤 2：走代理浅克隆 + 稀疏检出（只取需要的子目录）
用户指向的是 `packages/coding-agent`，整仓 clone 浪费流量，用 sparse：
```
git -c http.proxy=socks5h://localhost:1140 -c https.proxy=socks5h://localhost:1140 clone --depth 1 --progress --filter=blob:none --sparse https://github.com/earendil-works/pi.git vendor/pi-upstream
git -C vendor/pi-upstream sparse-checkout set packages/coding-agent
```
- Bash timeout 设 300000（5 分钟），`--progress` 保证有持续输出；若 1 分钟无输出按规则 kill，排查代理后再试，不裸连重试。
- Fallback：若 `--filter=blob:none` 被服务端拒绝，改普通 `clone --depth 1` 整仓（空间足够）。

### 步骤 3：只读验证（确认零污染）
1. `git -C vendor/pi-upstream log --oneline -1` —— clone 成功；
2. `ls vendor/pi-upstream/packages/coding-agent/docs/rpc.md` —— 目标文档在位，可与本地 `docs/rpc.md` 对照；
3. `git status --short` 为空 + `git check-ignore -v vendor/pi-upstream/README.md` —— 确认忽略生效、工作区干净，无任何待提交内容。

### 步骤 4（本计划之后）：用上游代码回答权限弹框问题
对照 `packages/coding-agent/src` 的 TUI 审批/confirm 组件和 `docs/rpc.md` 上游版本，确认上游是否有 TUI 侧工具审批、触发条件是什么。属下一步调查，不在本计划内执行。
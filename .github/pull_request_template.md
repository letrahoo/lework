## Summary

<!-- 简述本次改动目标与核心设计 -->

## Parallel PR & Conflict Pre-flight Check

- **Base commit (`origin/main`)**: `<!-- git rev-parse --short origin/main -->`
- **Open PR overlap audit (`gh pr list --state open`)**:
  - [ ] 已检查所有 OPEN PR，确认无功能重复 / 重复造轮子（若有重合，已对比取优并聚合处理）
  - [ ] 已对文件有交集的 OPEN PR 执行 `git merge-tree --write-tree` 预检，无未沟通的核心逻辑冲突
  - [ ] 保持 PR 原子性，或在存在强耦合冲突时已说明聚合/串行合入顺序

## Verification

- [ ] `node scripts/resolve-version.mjs --check`
- [ ] `pnpm --filter @lework/protocol gen`（0 drift）& `pnpm -r check`
- [ ] `pnpm -r test`
- [ ] `./app/gradlew -p ./app :shared:jvmTest :desktopApp:compileKotlinJvm`

# 项目规则

## 飞书文档默认目录

- 本项目（`guahao`）的飞书文档默认输出到：https://bytedance.larkoffice.com/drive/folder/BMr7fkzTilwtaDd9exsc0T9ynCd
- 当用户未另行指定目录时，本项目的调研、设计、开发与验证文档均使用该目录；用户在具体任务中指定其他目录时，以该次指定为准。
- 此项目默认值覆盖全局的默认飞书文档目录；其余全局规则继续适用。

## Git 推送规则

- 本项目无需在开发、修改或推送前检查是否有开放的 PR/MR；此规则覆盖全局“修改前检查待合并 MR”的要求。
- 本项目（`/Users/bytedance/Code/guahao`）需要推送时，直接推送到 `origin/main`，使用 `git push origin HEAD:main`；此为持续授权，无需逐次确认，也不以创建或合并 MR 为前提。
- 开发和提交仍在独立工作分支进行；本规则指定远端推送目标，不改变本地工作分支约定。
- 整合分支或同步上游时优先使用 rebase，尽量不使用 merge。
- 仅允许 fast-forward（快进）推送。推送前完成相关验证、获取远端最新状态并确认远端目标提交是本地待推送提交的祖先；不满足时先 rebase、解决冲突并重新验证。禁止 force push，包括 `--force`、`--force-with-lease` 和 `+` refspec。
- 推送后回读远端 `main` 的提交 SHA，确认与本次交付提交一致后才算推送完成。本规则仅适用于本项目。

# Motiquies Open Source

Motiquies 的定制修复、兼容层、数据迁移和构建工具。目标是让服务器的全部定制修改可以从公开源码和明确的上游依赖复现，并遵守每个组件的许可证。

服务器的大量内容来自 **[Mechanomania（MoePus）](https://www.curseforge.com/minecraft/modpacks/mechanomania)**。上游整合包、自带修改和 Motiquies 新增补丁分别追溯，不能因文件位于服务器或本仓库就认定为 Motiquies 原创。[上游归属与许可边界](docs/UPSTREAM-MECHANOMANIA.md)记录当前复核范围。

**完整覆盖仍在补齐。源码公开与构建通过，不代表 3.3.0 已在生产上线。** 当前以逐文件审计、固定依赖 SHA-256、重复构建和原有运行时 class 对比记录进度；来源或授权未确认的组件不会被标成已复现。

完整范围与逐项验收条件见[全部定制复现标准](docs/COMPLETE-REPRODUCTION.md)，当前不足见[缺口清单](docs/REPRODUCTION-GAPS.md)。

## 构建

每个模块有自己的 README、许可证、依赖锁与构建入口。原始 Minecraft、NeoForge 和第三方模组由使用者从合法来源获得；本仓库不托管游戏、整合包、第三方 JAR、世界或玩家数据。

通常的独立编译入口：

```text
python modules/<module>/build.py --sdk-root <existing-sdk> --java-home <jdk21> --output <outside-this-repository>
```

具体模块可能还需要固定版本的原始模组或上游源码，必须按该模块 README 提供，不能用同名文件代替锁定的输入。构建脚本应在依赖不匹配时停止。当前 SDK 获取与历史组件覆盖仍有缺口，详见覆盖清单；这里没有承诺一个命令从空电脑重建整服。

## 范围

- `modules/`：按组件分开的源码、配置、构建脚本、测试和原许可。
- `modules/server-mods/`：运行源码重现；部分组件仅编译完整代码，素材与完整模组打包另列缺口。
- `modules/legacy/`：历史诊断与迁移工具；其中一些会写 NBT、创建实体或执行其他测试操作，**不是生产默认安装项**。
- `registry/`：公开来源、构建状态与未闭合项，区分当前生产、候选和历史工具。
- `tools/publication_check.py`：逐文件指纹、未审文件、二进制及明显隐私内容检查。

Nostalgia 修改版及其重建配方没有取得作者的分发授权，因此不在此仓库中；这部分仍是完整公开复现目标的授权缺口。其他组件保持自己的 MIT、BSD、GPL 等许可，不能以根目录的说明替代其条款。

旧仓库只有在源码、全部 Git 引用、发布物和外部链接均经过核对后才归档。原 Release 下载链接、历史和必要回滚资料需要保留。

## 检查源码快照

```text
python -B -m unittest discover -s tests -v
python -B tools/publication_check.py
```

CI 的仓库检查不下载游戏、不启动服务器，也不自动部署。

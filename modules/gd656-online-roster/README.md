# Motiquies GD656 战绩页在线人数

面向 Minecraft 1.21.1、NeoForge 21.1.241 和 GD656Killicon 1.1.0.020。按住默认 Tab 键时，GD656 打开原来的快速战绩页并在原分数副标题的末尾显示 `在线人数 N`。松开 Tab 按原有逻辑关闭。统计来源是当前连接由服务器列出的在线玩家，独立于战绩页的离线筛选和历史战绩记录。副标题超出可用宽度时按当前字体缩放，比分和人数同时可见。

战绩页右侧主表默认显示当前在线玩家。玩家按 UUID 与该连接收到的 GD656 战绩匹配，使用当前玩家名和实际分数。尚未收到战绩的数据列显示 `—`。点「显示下线玩家」可查看原来完整的战绩榜。

0.2.0 延续 0.1.1 的模组 ID `motiquies_tab_count`。将客户端 `mods` 下的 `motiquies-tab-online-count-0.1.1.jar` 或更早的同 ID JAR 换成 `motiquies-gd656-online-count-0.2.0.jar`。GD656 的按键继续由游戏的按键绑定设置管理。

构建使用 JDK 21.0.10、NeoForge 21.1.241 客户端 SDK 和 MinecraftGD656 发布的 GD656Killicon 1.1.0.020。`dependencies.lock.json` 固定完整构建类路径、GD656 JAR 和 NeoForge 内嵌的 MixinExtras。GD656 的许可证为 MIT，其源码、JAR 与资源由作者分发。此模块的 MIT 许可覆盖 Motiquies 编写的适配层与翻译。 [GD656 官方源码](https://github.com/MinecraftGD656/gd656killicon) · [锁定的官方版本](https://modrinth.com/mod/gd656killicon/version/a2uttm7Y)

```text
python build.py --java-home <jdk21> --sdk-root <external-sdk> --gd656 <official-gd656.jar> --output <external-empty-output>
```

SDK 的库目录布局见仓库根目录的构建说明和依赖锁。脚本编译模块、运行列表和标题单元测试并生成 JAR。两次独立构建与隔离游戏验证使用的扩展 JAR 均得到 SHA-256 `e1d0a77353ee54eb8b07881725fdb7fbd45032bd8287054ffbc502fc733a466d`。隔离验收覆盖实际本地登录和零分，以及模拟玩家的有分、未收到战绩和离线榜切换。两名真实玩家的击杀与重连行为另行验证。

回退时从 `mods` 移出 0.2.0，恢复由 GD656 独立提供战绩页。

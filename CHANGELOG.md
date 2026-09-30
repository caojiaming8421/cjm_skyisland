# 版本更新记录

> 本文件记录 `cjm_skyisland`（空岛纪元）模组每个版本的开发内容，遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/) 规范。
>
> 分类约定：
> - **环境** 构建、工具链、依赖、开发机配置
> - **新增** 新功能 / 新内容
> - **变更** 对已有内容的修改
> - **修复** Bug 修复 / 环境问题修复

---

## [1.1.0] - 2026-09-30

首个玩法功能落地：空岛主世界。

### 新增
- 主世界清空：自定义 `VoidChunkGenerator`（实现原版 `ChunkGenerator`），不生成任何地形，主世界变为虚空
- 每玩家独立空岛：`IslandSpawner` 在玩家首次进服时，按其 UUID 派生固定岛格坐标，放置 10×10（草/泥/石三层）平台，设置重生点并传送到岛上，避免掉入虚空；平台持久化在存档，重启后仅在确实缺失时重建
- 空岛群系随机：`VoidIslandBiomeSource` 把主世界切成 320 格的岛格，岛格中心 5 格半径内返回确定性随机群系（平原 / 沙漠 / 雪原 / 森林 / 针叶林 / 热带草原 / 丛林 / 积雪针叶林 / 白桦林 / 向日葵平原），其余区域返回 `the_void`（无天气天空）
- 世界预设数据包：`data/cjm_skyisland/worldgen/world_preset/skyblock.json`，主世界用自定义虚空生成器，下界 / 末地沿用原版 normal 预设

### 变更
- 适配 MC 26.3 包重构：原 `fabric-worldgen-v1` 已移除，改为使用原版注册表 API 与数据包驱动的世界生成
- 自定义生成器 / 群系源改用 `RecordCodecBuilder` codec（读取 `biome_source` / `biomes` / `sky_biome` 字段），以在反序列化时从注册表解析群系 `Holder`（`MapCodec.unit` 无法访问数据驱动注册表，会导致群系为空）

### 修复
- 修正 26.3 中已重命名的类路径：`Blender`（→ `...levelgen.blending.Blender`）、`LevelData`（→ `...level.storage.LevelData`）、`ResourceKey#location()`（→ `identifier()`）
- 修正 `BuiltInRegistries` 在 26.3 不再暴露 `BIOME` / `DIMENSION_TYPE` / `WORLD_PRESET` 等数据驱动注册表的问题：不再在 Java 侧注册 WorldPreset，改为纯数据包 JSON

---

## [1.0.0] - 2026-09-29

项目初始化版本：开发骨架搭建完成，命令行构建已验证通过。

### 环境
- 确定目标平台：Minecraft 26.3 / Fabric Loader 0.19.5 / Loom 1.18-SNAPSHOT / Fabric API 0.161.0+26.3
- 安装并配置 JDK 25（`D:\Java\jdk-25`），与既有 JDK 21 并存，互不干扰
- Gradle 发行版源改为腾讯云镜像（修改 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl`），解决官方源 `services.gradle.org` 国内下载超时问题
- 锁定 Gradle JVM 为 JDK 25：在 `gradle.properties` 写入 `org.gradle.java.home=D:/Java/jdk-25`，避免 IDEA 默认用 JDK 21 导致编译报错
- 验证命令行构建通过（`gradlew classes` → BUILD SUCCESSFUL），MC 26.3 本体、Loom 映射、Fabric API 等依赖已全部下载到本地缓存

### 新增
- 基于 Fabric 官方模板生成模组骨架：`modid = cjm_skyisland`、包名 `com.cjm.skyisland`、主类 `Cjm_skyisland`
- 拆分 `main` / `client` 两套源集（`src/main` 双端公共代码、`src/client` 仅客户端代码），适配后续开服务器的需求
- 开启 Loom 数据生成（datagen），便于用代码批量生成方块 / 物品 / 配方 / 战利品表
- 初始化本地 Git 仓库，初始提交 21 个文件，并清理误入 `src/` 的 `.lnk` 快捷方式，保持仓库干净

### 待办（发布前）
- `fabric.mod.json` 的 `name` 仍为 `cjm_skyisland`，需改为「空岛纪元」
- `description` / `authors` / `contact` 仍为模板占位（示例文案、作者 `Me!`、指向 fabricmc.net），需补全为真实信息
- 默认分支为 `master`，(可选) 可改为 `main`

---

## 记录规范（给后续版本）

每发布一个版本，在上方 `## [1.0.0]` 之前新增一节，格式如下：

```markdown
## [x.y.z] - YYYY-MM-DD

### 新增
- 本次新增了什么

### 变更
- 修改了什么

### 修复
- 修了什么问题
```

版本号语义参考：首位=大版本（玩法大改）/ 中位=新功能 / 末位=Bug 修复与小调整。

# 版本更新记录

> 本文件记录 `cjm_skyisland`（空岛纪元）模组每个版本的开发内容，遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/) 规范。
>
> 分类约定：
> - **环境** 构建、工具链、依赖、开发机配置
> - **新增** 新功能 / 新内容
> - **变更** 对已有内容的修改
> - **修复** Bug 修复 / 环境问题修复

---

## [1.6.0] - 2026-10-02

新增「主岛」系统：200×200 浮空主城岛，含主城城堡、十字主干道、环形街道、8 个专营店铺，每店一名空岛村民。

### 新增
- **主岛（MainIsland）**：固定生成在 `(-200000, -200000)`，200×200 浮空平台，中世纪石砖小镇风格
  - 主城城堡（中央 28×28 石砖城堡，四角塔、深色橡木屋顶、萤石照明），内有**城主村民**（综合交易）
  - 8 个专营店铺：铁匠铺、武器店、护甲店、建材店、农夫/食物店、药水店、附魔店、杂货收购站
  - 每个店铺生成一座 13×13 石砖小屋，门楣上有对应颜色的羊毛招牌，屋内柜台后站着专营村民
  - 主岛四周围墙 3 格高，不会自然刷怪（落在 the_void 群系上）
- **主城传送碑（town_portal）**：新方块，金色传送纹石碑
  - 玩家自己的空岛上自动生成一座，右键 → 传送到主岛
  - 主城正门外也有一座，右键 → 传送到自己空岛
  - 同一个方块按玩家当前位置自动判断方向
- **店铺村民系统**：给 `CjmVillager` 增加 `ShopType`，按类型生成不同交易表
  - 村民头顶显示店铺名（如「铁匠」），交易面板标题同步变化
  - 店铺村民 `setNoAi(true)` 站在柜台后不动，避免串店或掉出主岛
  - 新增 `com.cjm.skyisland.shop.ShopType` 与 `ShopTrades`，统一维护 9 套交易表
  - 所有交易仍用「空岛硬币」结算，收购/出售均为无限次、0 经验、不涨价
- 服务端启动时一次性生成主岛（`ServerLifecycleEvents.SERVER_STARTED`），避免玩家第一次传送时卡住
- 玩家在主岛上时，每 2 秒（40 tick）补检一次店铺村民，缺失就补齐

### 变更
- `CjmVillager`：移除原 `createTradeOffers()` 硬编码交易，改由 `ShopTrades.build(shopType)` 统一生成
- `IslandSpawner.ensureIsland`：玩家进服建岛时额外生成「主城传送碑」
- `Cjm_skyisland.onInitialize`：注册 `MainIsland`
- 创造模式标签新增「主城传送碑」物品

### 说明
- 主岛与玩家空岛相距约二十万格，靠传送碑往返，不是坐船或走过去的距离
- 交易价格是初始平衡，后续可按实际体验调整
- mod 版本号 1.5.15 → 1.6.0（新的大系统，按约定进中位）

---

## [1.5.15] - 2026-10-01

修复「每次进入游戏都会多生成一只空岛村民」的问题。

### 修复
- 根因：和 1.5.14 掉落物清理是**同一个坑**。`IslandSpawner.ensureIslandVillager` 在进服时用
  `getEntitiesOfClass(CjmVillager.class, ...)` 判断岛上有没有村民，但那一刻玩家往往并不在自己的岛上
  （上次退出位置可能在副本里、或有床停在别处），岛区块没有被真正加载 ——
  **区块里的实体还没挂进世界** —— 扫描恒为空，于是每次进服都误判「没有村民」并补生成一只
- `ensureIslandVillager` 增加 `isOnOwnIsland(player)` 前置判定（维度 + XZ 半径 32 + 高度范围）：
  玩家不在自己岛上时直接跳过，不做任何生成
- 新增 `ServerTickEvents.END_SERVER_TICK` 周期性补检（`VILLAGER_CHECK_INTERVAL = 40` tick / 2 秒）：
  玩家回到岛上、岛区块真正加载后再检查，缺了就补、村民被杀也能自动补齐
- 顺带收敛旧档：扫描到岛上有多只村民时只保留一只，其余 `discard()`，并打印日志
  （老存档里已经堆出来的多余村民，进服回到岛上就会自动收成一只，不用手动 `kill`）
- 新增日志：生成村民 / 收敛多余村民时各打印一行

### 说明
- 玩家不在岛上时完全不生成，所以「在副本里反复进出」「下线重连」都不会再堆村民
- mod 版本号 1.5.14 → 1.5.15

---

## [1.5.14] - 2026-10-01

修复 1.5.13 之后实测掉落物仍未清理的问题：进入副本时改为「传送后再持续补扫若干 tick」，等平台区块真正加载完再清。

### 修复
- 根因：进入时玩家还在自己的空岛（离副本可能几十万格），副本区块此时并未真正加载；
  `getChunkSource().getChunk(..., ChunkStatus.FULL, true)` 只把区块拉到 FULL，
  **区块里的实体并没有挂进世界**，所以 `getEntitiesOfClass(ItemEntity.class, ...)` 一个都扫不到
- 新增 `Dungeon.clearDrops(world)` 抽出清掉落物逻辑并返回清理数量
- 新增 `pendingDropCleanup` / `deferredDropRemoved`：`enterDungeon` 传送玩家后开启
  `DROP_CLEANUP_TICKS`（200 tick / 10 秒）的补扫窗口，`onEndTick` 每 tick 扫一次并累加，
  此时玩家已在副本内，平台区块会逐步加载完，掉落物才清得掉
- 日志补全：进入时打印「即时阶段」清掉多少，补扫结束时再打印「补扫阶段」共清掉多少

### 说明
- 保留进入时的即时清理与区块预载（区块预载仍是刷怪 / 填箱能落地的前提，只是拿不到实体）
- mod 版本号 1.5.13 → 1.5.14

---

## [1.5.13] - 2026-10-01

修复 1.5.12 进入副本时掉落物未被实际清理的问题：清理前先加载副本平台所有区块，确保能扫到全部掉落物与怪物。

### 修复
- `Dungeon.resetDungeon` 在清怪/清掉落物之前，先强制加载平台范围内的所有区块（`ChunkStatus.FULL`）
- 新增日志输出本次清理了多少只旧怪、多少个地面掉落物

### 说明
- mod 版本号 1.5.12 → 1.5.13

---

## [1.5.12] - 2026-10-01

进入副本时自动清理副本内残留的地面掉落物（实际未生效：未加载平台区块导致 getEntitiesOfClass 扫不到掉落物）。

### 新增
- `Dungeon.resetDungeon` 在清怪之后、刷怪之前，额外清除副本范围内的所有 `ItemEntity`（玩家死亡掉落 / 怪物掉落 / 箱子散落物）
- 掉落物清理复用 `regionBox()` 范围（与清怪范围一致）

### 说明
- 每次进入副本都会清理，保证每轮都是干净的掉落物环境
- mod 版本号 1.5.11 → 1.5.12

---

## [1.5.11] - 2026-10-01

修复 `副本入口石碑` 物品在物品栏 / 交易界面显示为紫黑缺失纹理的问题（根因是缺少 `items/dungeon_core.json` 这个新版物品模型注册文件）。

### 修复
- 新增 `items/dungeon_core.json`，类型为 `minecraft:model`，指向 `cjm_skyisland:item/dungeon_core`

### 说明
- 该版本同时保留 1.5.10 的 `textures/item/dungeon_core.png` 与 `models/item/dungeon_core.json`
- mod 版本号 1.5.10 → 1.5.11

---

## [1.5.10] - 2026-10-01

修复 `副本入口石碑` 物品在物品栏 / 交易界面显示为紫黑缺失纹理的问题（尝试失败：只加了 `textures/item/` 贴图与 `models/item/` 模型，但缺少新版 `items/` 注册文件）。

### 修复
- 新增 `textures/item/dungeon_core.png` 专用物品图标
- `models/item/dungeon_core.json` 改为 parent `minecraft:item/generated`，`layer0` 指向 `cjm_skyisland:item/dungeon_core`

### 说明
- mod 版本号 1.5.9 → 1.5.10

---

## [1.5.9] - 2026-10-01

修复 `副本入口石碑` 物品在物品栏 / 交易界面显示为紫黑缺失纹理的问题（尝试失败：layer0 指向 block 图集不生效）。

### 修复
- 物品模型 `models/item/dungeon_core.json` 之前直接 parent 方块模型 `dungeon_core_mid`，导致物品栏渲染失败回退成紫黑
- 改为 parent `minecraft:item/generated`，用 `layer0` 指向 `dungeon_core_mid.png`，以平面图标方式显示

### 说明
- mod 版本号 1.5.8 → 1.5.9

---

## [1.5.8] - 2026-10-01

修复副本入口石碑只有部分节数 / 顶部缺少贴图的问题：碑顶改用专门的顶面贴图，并让旧存档在进服时自动补齐缺失的中、上两节。

### 修复
- **碑顶顶面缺少贴图**：`dungeon_core_top.json` 里碑顶的上/下表面之前复用侧面石纹，改为专用 `dungeon_core_cap.png`（俯视角度的石质顶盖，中心带金色圆）
- **石碑只有部分节数**：旧存档（1.5.6 及更早）里已生成的单节石碑之前会被跳过，导致只显示底座

### 变更
- `Dungeon.ensureEntrance` 改为分别检查下/中/上三节，只有三节都在才跳过；缺失的节会补上（老存档进服即自动补全，共 3 格高）
- 新增贴图 `textures/block/dungeon_core_cap.png`
- `IslandSpawner.ensureIsland` 注释同步为「副本入口石碑」

### 说明
- 三节均不可破坏，右键任意一节都能打开难度选择 GUI
- mod 版本号 1.5.7 → 1.5.8

---

## [1.5.7] - 2026-10-01

修复 1.5.6 中副本入口石碑显示为紫黑缺失纹理的问题：MC 方块模型元素坐标必须限制在 0–16 内，之前的单文件 1×3 模型高度 48 导致加载失败。现把石碑拆成 `section=bottom/middle/top` 三节堆叠，每节单独模型与贴图。

### 修复
- 修复 `dungeon_core` 石碑紫黑缺失纹理：模型元素坐标超出 0–16 导致游戏回退成缺失纹理
- 新增 `DungeonCoreBlock.Section` 属性（`bottom / middle / top`），石碑改为三格高多方块结构

### 变更
- `Dungeon.ensureEntrance` 现在会依次放置石碑的下、中、上三节
- 玩家手持 `dungeon_core` 放置时会自动向上补 middle 与 top（需要上方两格为空）
- 模型与贴图拆分：
  - `models/block/dungeon_core_bottom.json` + `textures/block/dungeon_core_bottom.png`（底座）
  - `models/block/dungeon_core_mid.json` + `textures/block/dungeon_core_mid.png`（碑身带金币浮雕）
  - `models/block/dungeon_core_top.json` + `textures/block/dungeon_core_top.png`（碑顶）
  - `textures/block/dungeon_core_side.png`（三节共用石质侧面）
- 移除旧的单文件模型 `dungeon_core.json` 与单张贴图 `dungeon_core.png`
- 物品手持模型使用中间节 `dungeon_core_mid`

### 说明
- 右键石碑任意一节都能打开难度选择 GUI；三节均不可破坏
- mod 版本号 1.5.6 → 1.5.7

---

## [1.5.6] - 2026-10-01

去掉玩家空岛上的 4×4 副本传送阵，保留并改为「副本入口石碑」（`dungeon_core`）作为唯一入口：1×3 高石碑造型。

### 变更
- **移除玩家空岛上的 4×4 副本传送阵**：`Dungeon.ensureEntrance`（原 `ensureHomePortal`）不再铺设萤石/陶釉/传送门，只在空岛固定位置生成 `dungeon_core` 石碑
  - 旧存档里已建好的空岛传送门会留在原地，但不再触发进入；它们仍是不可破坏方块
- **副本入口石碑成为唯一入口**：右键石碑打开难度 GUI → 选难度 → 扣硬币 → 直接进副本，流程与 1.5.5 的 `requestEnter` 一致
  - 删除 `Dungeon.selectedDiff` 与 `isHomePortalFootprint`，`handlePlayer` 只负责「副本中央传送阵 → 回家」的回程逻辑
- **石碑音效改为石头**：`SoundType.METAL` → `SoundType.STONE`
- 中英文 lang：`block/item.cjm_skyisland.dungeon_core` 改为「副本入口石碑」/ "Dungeon Entrance Stele"

### 说明
- 副本中央的 4×4 回程传送阵保留不变，玩家仍站上去回空岛
- 停留限制（最短 5 分钟 / 最长 20 分钟强制淘汰）保持不变
- mod 版本号 1.5.5 → 1.5.6

## [1.5.5] - 2026-10-01

副本进入改为「硬币解锁 + 三档难度」，并移除 30 分钟进入冷却。

### 新增
- **副本难度入口方块「硬币祭坛」** `cjm_skyisland:dungeon_core`（`DungeonCoreBlock`）
  - 放在每个玩家空岛传送阵中心**正上方 2 格**，挖不动（强度 -1）、不可破坏
  - 右键打开「副本难度选择」GUI（`client/DifficultyScreen`）：简单 / 普通 / 困难 三档按钮，各显示「花费硬币 / 怪物数 / 装备材质」
  - 客户端通过 `UseBlockCallback` 拦截右键直接开界面（common 端不依赖 client 类）；选区用 `SelectDifficultyC2S` 网络包发到服务端
- **难度选择网络包** `network/SelectDifficultyC2S`（`CustomPacketPayload` + `StreamCodec.composite`）
  - 服务端 `PayloadTypeRegistry.serverboundPlay().register` + `ServerPlayNetworking.registerGlobalReceiver` 接收
  - 收到后调用 `Dungeon.requestEnter(player, diff)`：检查硬币 → 扣费 → 进入
- **难度三档配置** 集中在 `world/DungeonConfig.java`
  - 战利品池拆成 `STONE_LOOT`（石质 5 件）/ `IRON_LOOT`（铁质 9 件）/ `DIAMOND_LOOT`（钻石 9 件）
  - `Difficulty[] DIFFICULTIES`：简单 `cost=20` / `mobCount=20` / 石质；普通 `cost=50` / `mobCount=50` / 铁质；困难 `cost=100` / `mobCount=100` / 钻石
- 方块模型 `blockstates/dungeon_core.json` + `models/block/dungeon_core.json`（石英基座 + 金块硬币，多层渲染）+ 物品模型 + 中英文 lang

### 变更
- **进入副本必须先消耗空岛硬币解锁**：原 `tryEnter` 改为 `enterDungeon`，去掉 30 分钟冷却检查；改为在 `requestEnter` 里 `countCoins` 统计背包硬币，`removeCoins` 扣费（不足则拦截并提示）
- **移除 30 分钟进入冷却**：删除 `DungeonConfig.COOLDOWN_MS`；`DungeonData` 不再记录 `last_entry`
- 进入时从 `selectedDiff` 取难度；**未选难度踩传送门**会提示「请先右键头顶的硬币祭坛选择难度」
- 选难度即扣币，踩传送门免费复用已付费难度（`selectedDiff` 进入后清除），避免重复扣费
- 副本内的怪物数量 / 奖励箱装备材质随所选难度变化（`resetDungeon` 接收 `diff` 参数，驱动 `spawnMobs` / `refillChests`）
- 传送门判定补 `feet.above()` 检查，确保 `noCollision()` 方块仍能触发

### 说明
- **停留限制保留**：最短停留 5 分钟（`MIN_STAY_MS`）+ 最长停留 20 分钟强制淘汰（`MAX_STAY_MS`），仅去掉 30 分钟冷却
- mod 版本号 1.5.4 → 1.5.5

## [1.5.4] - 2026-10-01

修正传送门视觉：星海面应齐着框口，而不是沉在池底。

### 变更
- 模型 `models/block/portal.json` 由「贴地薄片」改回**整格立方体**（`cube_all`）
  - 依据：反编译确认原版末地传送门渲染的是 1×1×1 完整立方体（`AbstractEndPortalRenderer` 的 `FROM=(0,0,0)` / `TO=(1,1,1)`，六面同一贴图），靠着色器做出深邃感
  - 方块本体保持 `noCollision()` 无碰撞不变，只是视觉从「池底薄片」变回「齐着萤石框顶的星海面」
- mod 版本号 1.5.3 → 1.5.4

## [1.5.3] - 2026-10-01

传送门改为「无实体」：和原版末地传送门一样没有碰撞，站进去即触发。

### 变更
- 传送门方块 `Cjm_skyisland.PORTAL_BLOCK` 加 `.noCollision()` + `.noOcclusion()`
  - 原本是普通实心方块，玩家踩在上面；现在没有碰撞，玩家踩的是它下面那格地面、身体处在传送门格里，手感与末地传送门一致
  - `noOcclusion()` 保证相邻的萤石 / 陶釉正常渲染，不会因为这个方块被整体剔除
- 传送判定 `Dungeon.isOnPortal` 由「检查脚下方块」改为**检查玩家所在格**
  （无碰撞后脚下方块是地面而非传送门，不改就永远触发不了；保留脚下判断兼容旧存档）
- 模型 `models/block/portal.json` 由 `cube_all`（实心方块）改为**贴地薄片**（1/16 格厚），
  加上四周一圈萤石，观感是一个「传送门池子」

### 说明
- 不可破坏、触发防抖、冷却等规则均不变；mod 版本号 1.5.2 → 1.5.3

## [1.5.2] - 2026-10-01

副本平台从 50x50 扩大为 **100x100**，并支持改尺寸后自动重建。

### 变更
- 副本平台尺寸 `DungeonConfig.SIZE` **50 → 100**（面积 4 倍）：地板、围墙、进出区域判定、刷怪 / 落点范围均随 `SIZE` 自动派生，无需另行修改
- mod 版本号 1.5.1 → 1.5.2

### 新增
- **改尺寸自动重建**：`DungeonData` 新增 `built_size` 字段，记录平台建造时的边长
  - `ensureBuilt` 判定改为「已建造 **且** 尺寸一致 **且** 箱子数量一致」，否则触发重建
  - 重建前先调新增的 `clearPlatform()`，把旧平台范围（`Y-1 ~ Y+WALL_HEIGHT`）清成空气再按新尺寸铺设 —— 避免旧玻璃围墙残留在放大后的平台中间
  - `built_size` 在存档 codec 里用 `optionalFieldOf("built_size", 0)`，**旧存档向后兼容**，不会崩档

### 说明
- 已有的开发存档不用删：下次有人进入副本时会自动推倒重建，日志输出「副本尺寸由 50 改为 100，正在重建平台」
- 新增项目文档《副本说明.md》（副本玩法与实现全解，与《物品交易说明.md》同级）

## [1.5.1] - 2026-09-30

修复 1.5.0 启动即崩溃与传送阵贴图缺失的问题。

### 修复
- **启动崩溃** `java.lang.ExceptionInInitializerError` → `java.lang.NullPointerException: Block id not set`
  - 原因：26.x 的 `BlockBehaviour.Properties` 和 `Item.Properties` 一样，必须在构造方块前显式 `setId(ResourceKey<Block>)`
  - 修复：为传送门方块补上 `PORTAL_ID`（`ResourceKey<Block>`）并 `.setId(PORTAL_ID)`，注册时复用同一个 key
- **传送阵贴图缺失**：模型原本引用 `minecraft:block/end_portal`，但末地传送门在 26.3 里是专用渲染器、**没有方块贴图**，导致日志报 `Missing textures in model cjm_skyisland:block/portal`
  - 修复：新增自定义贴图 `assets/cjm_skyisland/textures/block/portal.png`（16x16 深紫星海，模拟末地传送门观感），模型改为引用 `cjm_skyisland:block/portal`

## [1.5.0] - 2026-09-30

新增「副本空岛」（打怪副本）与配套的传送阵系统。

### 新增
- 副本空岛：一张固定的 **50x50** 浮空石台（两层石地板 + 3 格高玻璃围墙，防掉落）
  - 位置固定在世界负坐标，且刻意避开岛格中心，确保整图落在 `the_void` 群系内，不会有原版自然刷怪干扰数量
  - 副本内**玩家不能放置、不能破坏**任何方块（`PlayerBlockBreakEvents.BEFORE` + `UseBlock/UseItemCallback`，只拦「手持方块」的右键，开箱子 / 吃食物不受影响）
- 怪物：每次开启清空旧怪后**固定刷新 100 只**敌对生物，按约 1:1:1 混合 **僵尸 / 小僵尸 / 骷髅弓箭手**
  - 全部挂永久抗火（`FIRE_RESISTANCE`），原版 `isSunBurnTick()` 会跳过有抗火的生物，因此**白天不会自燃 = 不畏惧阳光**；另每 5 秒兜底补一次效果
  - 设置常驻（`setPersistenceRequired`）不消失，并有 100 只数量上限兜底
- 奖励箱：副本内随机散布 **4 个奖励箱**，每次开启重新随机填充
  - 战利品为**铁制武器与工具**（剑 / 斧 / 镐 / 锹 / 锄 + 铁头盔 / 胸甲 / 护腿 / 靴子），打乱后平均分配，保证 4 个箱子内容互不相同
- 传送阵：图案 `#AA# / A**A / A**A / #AA#`（A=萤石块，#=淡蓝陶釉，*=传送门，复用末地传送门贴图）
  - **每个玩家自己的空岛上固定生成一座**（进服建岛时补建，老存档也会补），站上去进入副本
  - 副本正中还有一座同样的传送阵，站上往返回自己空岛
  - 传送门方块与基岩同级（挖不动），配合事件层拦截做到**传送阵不可被破坏**
- 规则：
  - 进入后**随机落点**（避开中央传送阵和奖励箱）
  - 每次进入都会**重置**副本内的怪物数量与奖励箱内容
  - 同一玩家 **30 分钟冷却**，冷却期间站上传送阵会提示剩余时间
  - 进入后**最少停留 5 分钟**才能通过中央传送阵离开，未到时间会提示还需多久
  - **最多停留 20 分钟**，超时自动死亡（走原版死亡重生流程，回到自己空岛）

---

## [1.4.2] - 2026-09-30

修复「每次进服都重复发放初始物资」的 Bug。

- 修复：初始物资（橡树树苗 ×1 + 骨粉 ×4）改为**每个玩家仅发放一次**，用玩家持久数据（`player.getPersistentData()`）做标记，跨进服 / 重生保留，重复进入世界不再重复给
- 修复（根因）：进服时岛所在区块尚未加载，`getBlockState(center)` 在未加载区块上误报空气，导致 `buildIsland` 每次都判定「岛缺失」而重建岛 + 发物资；现判定前先 `getChunk(..., ChunkStatus.FULL, true)` 强制加载岛所在区块，避免误判与重复重建

---

## [1.4.1] - 2026-09-30

空岛商人开放「硬币 → 树苗 / 种子」反向交易。

- 新增：与空岛商人交易时，可用 **10 个空岛硬币** 换 **1 个树苗**（覆盖全部 10 种：橡树 / 云杉 / 白桦 / 丛林 / 金合欢 / 深色橡木 / 红树 / 樱花 / 苍白橡木 / 白杨）
- 新增：可用 **10 个空岛硬币** 换 **1 个种子**（覆盖全部 6 种：小麦 / 西瓜 / 南瓜 / 甜菜 / 火把花 / 猪笼草）
- 说明：与原有「原木 / 石头 → 硬币」形成闭环，方便空岛开局补种与农业起步；全部交易无限次、不涨价

---

## [1.4.0] - 2026-09-30

每个玩家首次生成空岛时，初始背包发放固定起步物资。

### 新增
- 初始物资：玩家第一次进入世界、专属空岛刚建成时，自动获得 **1 个橡树树苗（`oak_sapling`）+ 4 个骨粉（`bone_meal`）**
- 通过 `ServerPlayerEvents.JOIN` → `IslandSpawner.ensureIsland` 触发，仅在「新建空岛」那一刻发放一次，之后进服不再重复给，避免死亡/重进刷物资
- 发放后向玩家发送一条系统提示「你收到了初始物资：橡树树苗 ×1、骨粉 ×4」

---

## [1.3.7] - 2026-09-30

空岛村民开放交易，并新增货币「空岛硬币」。

### 新增
- 新增物品「空岛硬币」（`cjm_skyisland:coin`）：16x16 圆形金币贴图（金色同心圆 + 左上反光 +
  中心菱形刻印），已加入「空岛村民」创造栏，中英文名称已补
- 空岛村民实现原版 `Merchant` 接口，右键直接打开原版村民交易面板（`MerchantMenu`）
- 初期交易表：各树种原木（橡木 / 云杉 / 白桦 / 丛林 / 金合欢 / 深色橡木 / 红树 / 樱花 /
  苍白橡木 / 杨木）及下界两种菌柄，外加石头与圆石，每 1 个换 1 枚硬币，无限次不锁死

### 变更
- 村民交易期间会停下脚步，避免乱走导致超出距离而中断交易

### 环境
- 26.x 交易 API 已重构：`Merchant` 位于 `world.item.trading` 包；`MerchantOffer` 的代价用
  `ItemCost`（record）而非 ItemStack；`MerchantOffers` 直接继承 `ArrayList`
- `Merchant.openTradingScreen()` 是 default 方法且内部自带 `sendMerchantOffers` 同步，
  所以自定义生物只要实现 `Merchant` 就能复用原版交易面板，不需要 mixin
- `InteractionResult.sidedSuccess(boolean)` 在 26.x 已移除（改为带挥手源的 record），改用 `SUCCESS`

## [1.3.6] - 2026-09-30

为对外发布补全模组元数据。

### 变更
- `fabric.mod.json` 补齐真实信息：显示名改为「空岛纪元」，补上中文简介、作者 `caojiaming8421`，
  homepage / sources / issues 指向本项目仓库（此前是 fabric-example-mod 的模板默认值）
- 许可证保持 CC0-1.0（与仓库根目录 LICENSE 一致）

### 环境
- 对外分发的产物为 `build/libs/cjm_skyisland-<版本>.jar`（`build` 任务 remap 后的成品，非 `-dev.jar`）；
  玩家侧需自备 Minecraft 26.3 + Fabric Loader ≥0.19.5 + **Fabric API**（`depends` 已声明）

## [1.3.5] - 2026-09-30

空岛村民外观改为「穿西装的黄种人商人」，刷怪蛋配色同步。

### 变更
- 村民肤色由冷色调改回黄种人暖肤色（色相 30°、饱和 0.30），鼻子等偏暗肤色像素一并纳入（亮度阈值需放到 0.50，0.62 会漏掉鼻子）
- 村民改穿西装：深炭蓝外套 `#2B3648` + 翻领 `#333E52` + 白衬衫 V 领 + 酒红领带 `#8E2F3E` + 腰线金扣 `#C9A227`；西裤 `#232D3D`、皮鞋 `#1A1E26`；手臂为西装袖并露出手
- 头顶加黑发，眉眼重画为黑眉 + 深棕瞳（原版村民是绿瞳，降饱和后会发灰，必须显式覆盖）；唇上短须改回暖棕 `#6B5245`（原棕须降饱和后成冷灰蓝，像灰胡茬）
- 帽子改为配套的深炭黑礼帽（帽冠 `#2C3340` + 帽檐 `#39404E`），保留青色帽徽作为空岛元素
- 刷怪蛋配色改为西装深炭蓝底 + 黄种人暖黄斑，与原冰蓝色系告别

### 修复
- 修复帽子遮住眼睛：`hat` 层是「整个头」的副本 + `CubeDeformation(0.51)`，画到哪盖到哪。帽檐原先画到头顶贴图 v14，正好压住眉毛(v13)和眼睛(v14)；现收到 v11（帽冠 v8..10 + 帽檐 v11），v12 额头留作缓冲
- 修复胸口白衬衫面积过大：V 领只在最上 3 行露白，此前 4x7 的白块在身上像贴了块补丁

### 环境
- 原版 `villager.png` / 刷怪蛋 PNG 均为 `TYPE_BYTE_INDEXED`（索引色），直接 `setRGB` 会被吸附到最近索引色导致改色完全不生效，须先转 `TYPE_INT_ARGB`
- 刷怪蛋不能复用村民的「皮肤识别」规则（按色相认皮肤），蛋的米黄斑点会被误判成皮肤导致配色错乱；改按亮度 0.48 分层分别套色

## [1.3.4] - 2026-09-30

空岛村民专属外观：冷色调 + 商人帽，并补上刷怪蛋缺失的贴图与模型定义。

### 新增
- `assets/cjm_skyisland/textures/entity/cjm_villager.png`（64x64）：由原版 `villager.png` 改色而来
  - 全图色相压到 **184°–227°**（青→蓝）；亮部降饱和成淡青灰（皮肤），暗部保留饱和度成深靛蓝（袍子）
  - `hat` 层（UV 32–64, 0–18）画一顶**商人帽**：帽冠（侧面 v8–13）+ 帽檐（v13–15，比头大 0.51 所以略微外扩）+ 帽顶高光 + 额前青色宝石
  - 帽子只覆盖头的上半部，v15 以下留空，不遮脸
- `assets/cjm_skyisland/textures/item/villager_spawn_egg.png`（16x16）：原版蛋冷色化，深青底 + 亮青斑
- `assets/cjm_skyisland/items/villager_spawn_egg.json` + `models/item/villager_spawn_egg.json`：26.x 物品模型定义
  （此前缺失，创造栏里那颗蛋没有模型，会渲染成缺失纹理紫黑块）

### 变更
- `CjmVillagerRenderer.TEXTURE` 从原版 `minecraft:textures/entity/villager/villager.png`
  改为 `cjm_skyisland:textures/entity/cjm_villager.png`。UV 布局未变，仍复用 `ModelLayers.VILLAGER`

### 环境
- **索引色 PNG 陷阱**：原版贴图是 `TYPE_BYTE_INDEXED`（24 色），直接 `setRGB` 会被吸附到最近的索引色、改色不生效。
  必须先 `new BufferedImage(w, h, TYPE_INT_ARGB)` 并 `drawImage` 转一次再逐像素处理

---

## [1.3.3] - 2026-09-30

修复「把床设为重生点后又拆掉床，重生位置变成随机」。

### 修复
- 根因：睡床后原版把重生点改成床的位置（`forced=false`）；床被拆后该点失效，原版退回**世界默认出生点**，而虚空世界里那是个随机坐标 —— 于是重生「随机」。原先只挂了 `AFTER_RESPAWN` 兜底，它发生在原版已经把玩家丢到出生点**之后**，时序上慢了一步
- 改为三层保险，任一层生效都能把重生点拉回空岛：
  1. `PlayerBlockBreakEvents.AFTER`：床 / 重生锚被拆的那一刻就修好重生点
  2. `ServerLivingEntityEvents.AFTER_DEATH`：死亡瞬间再校验一次，原版随后的重生流程读到的就是已修好的空岛点（玩家直接回岛，不会再出现「你的床或已充能的重生锚不存在」提示）
  3. `ServerPlayerEvents.AFTER_RESPAWN`：兜底传送
- 新增 `isIslandRespawn()`：重生点已是本玩家空岛点时跳过修正，避免每次重生都重复写数据 / 传送
- 进服逻辑改保守：`ensureIsland` 只在玩家**没有有效锚点**时才传送并设重生点，不再无条件覆盖 —— 顺带修掉「睡了床之后退出重进，重生点被冲掉」的问题
- 加诊断日志（前缀 `[skyisland]`），记录重生点恢复的目标坐标与触发原因

### 环境
- `ServerPlayer` 没有 `getServer()`，统一改为从所在维度取（`player.level()` → `ServerLevel.getServer()`）

---

## [1.3.2] - 2026-09-30

修复进世界时被踢出：`Internal Exception: java.nio.channels.ClosedChannelException`。

### 修复
- `CjmVillager` 新增 `createAttributes()`（`Mob.createMobAttributes().add(Attributes.MOVEMENT_SPEED, 0.5)`）
- 主类 `onInitialize` 用 `FabricDefaultAttributeRegistry.register(CJM_VILLAGER, CjmVillager.createAttributes())` 注册属性
- 根因：26.x 的 `DefaultAttributes.SUPPLIERS` 是 `ImmutableMap`，mod 生物无法写入；未注册属性的生物创建时 `AttributeSupplier` 为 null，抛 `NullPointerException`。异常发生在 `PlayerList.placeNewPlayer` → 玩家放置中断 → 服务端断开连接，客户端显示「连接已丢失」
- 因为先前进入世界并不会生成村民，这个缺陷在 1.3.0 才被暴露

---

## [1.3.1] - 2026-09-30

修复游戏启动时崩溃：`ExceptionInInitializerError` / `NullPointerException: Item id not set`。

### 修复
- 刷怪蛋的物品 id 改为显式声明：`new Item.Properties().spawnEgg(CJM_VILLAGER).setId(CJM_VILLAGER_EGG_ID)`，并以同一个 `ResourceKey<Item>` 注册
- 根因：26.x 的 `Item.Properties` 必须调用 `setId(ResourceKey<Item>)` 才能在构造 `Item` 时确定物品 id，否则构造过程抛 `Item id not set`；原版 `Items.registerItem` 也是先 `properties.setId(id)` 再构造

---

## [1.3.0] - 2026-09-30

每个空岛生成时默认附带一只「空岛村民」。

### 新增
- `IslandSpawner.ensureIslandVillager`：进服时检查所属空岛范围内是否已存在空岛村民，没有则生成一只，站在草方块上并略微偏离岛心（避免与玩家落点重叠）
- 生成的村民设置为 `setPersistenceRequired()`，不会因玩家远离而消失

### 变更
- `buildIsland` 改为返回 boolean（表示本次是否新建岛），便于区分「新建岛」与「岛已存在」
- `ensureIsland` 在放好平台后统一补齐村民：岛已存在但村民被击杀或丢失时也会补一只，保证每个空岛恒定一只

---

## [1.2.0] - 2026-09-30

新增自定义「空岛村民」生物，可在创造模式直接刷出来。

### 新增
- `CjmVillager` 实体（id `cjm_skyisland:villager`）：继承 `PathfinderMob`，被动生物，会随机闲逛并看向附近玩家
- `CjmVillagerModel`：村民外形模型（头 / 帽子 / 鼻子 / 身体 / 双臂 / 双腿，64×64 贴图），几何与原版 `VillagerModel` 一致，便于后续改成专属外观与 UV
- `CjmVillagerRenderer`：绑定模型与贴图，复用原版 `ModelLayers.VILLAGER` 烘焙（无需注册自定义模型层），贴图暂用原版 `villager.png`
- 刷怪蛋 `cjm_skyisland:villager_spawn_egg`（`new Item.Properties().spawnEgg(CJM_VILLAGER)`）
- 自定义创造模式标签 `cjm_skyisland:villager_tab`，创造栏里可直接找到刷怪蛋

### 变更
- 主类 `Cjm_skyisland` 新增实体类型、刷怪蛋、创造栏标签的注册
- 客户端入口 `Cjm_skyislandClient` 注册渲染器（原先为空模板）

### 修复
- 修正 26.3 的模型 / 渲染器新 API：`EntityModel<T extends EntityRenderState>`（不再以 `Entity` 为泛型）、`MobRenderer<T, S extends LivingEntityRenderState, M>`、`getTextureLocation(S state)` 接收渲染态而非实体
- 修正源码集归属：模型与渲染器必须放在 `src/client` 源码集（`src/main` 编译期看不到 `net.minecraft.client.*` 包）
- 修正 `Registries` 包路径为 `net.minecraft.core.registries.Registries`；`SPAWN_EGGS` 位于 `CreativeModeTabs`（不存在 `ItemGroups` 类）
- 规避本版本 fabric-api 缺少 item-group 模块（`ItemGroupEvents` 不可用）的问题：改用原版 `CreativeModeTab` API 自建创造栏，不依赖 fabric 物品组扩展

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

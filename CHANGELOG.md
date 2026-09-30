# 版本更新记录

> 本文件记录 `cjm_skyisland`（空岛纪元）模组每个版本的开发内容，遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/) 规范。
>
> 分类约定：
> - **环境** 构建、工具链、依赖、开发机配置
> - **新增** 新功能 / 新内容
> - **变更** 对已有内容的修改
> - **修复** Bug 修复 / 环境问题修复

---

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

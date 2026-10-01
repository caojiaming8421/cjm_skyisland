package com.cjm.skyisland;

import com.cjm.skyisland.world.DungeonConfig;
import com.cjm.skyisland.world.SkyblockConfig;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 副本空岛（打怪副本）。
 *
 * <p>玩法要点：
 * <ul>
 *   <li>玩家自己的空岛上固定生成一座<b>副本入口石碑</b>（{@code cjm_skyisland:dungeon_core}）。
 *       右键石碑打开难度界面，消耗硬币选难度后立即进入副本；石碑造型为 1×3 高石碑，正面有金币浮雕。
 *       副本正中仍有一座 4×4 传送阵，站上去回自己的空岛。</li>
 *   <li>三档难度：简单（20 硬币 / 20 怪 / 石质装备）、普通（50 硬币 / 50 怪 / 铁质装备）、
 *       困难（100 硬币 / 100 怪 / 钻石装备）。每次进入都会<b>重置</b>副本：清掉旧怪后按难度刷满，
 *       并重新随机填充 4 个奖励箱。</li>
 *   <li>进入不再有冷却（靠硬币门槛限制）；进入后必须待满 5 分钟才能走；超过 20 分钟不走就自动死亡。</li>
 *   <li>副本内玩家不能放置、不能破坏任何方块（中央传送阵与石碑本身也拆不掉）。</li>
 * </ul>
 *
 * <p>「怪物不畏惧阳光」的实现：原版 {@code isSunBurnTick()} 会检测
 * {@code !hasEffect(MobEffects.FIRE_RESISTANCE)}，所以给副本内所有怪物挂上永久抗火即可，
 * 白天不会自燃。此外每 5 秒兜底补一次效果，防止被其它途径清掉。
 */
public final class Dungeon {
	private static final Logger LOGGER = LogUtils.getLogger();

	// ==================== 传送阵图形 ====================
	/** 传送阵边长（4x4）。 */
	private static final int PORTAL_SIZE = 4;
	/** 传送阵图案：A=萤石块，#=陶釉，*=传送门。按 [z][x] 排列。 */
	private static final String[] PORTAL_PATTERN = {
			"#AA#",
			"A**A",
			"A**A",
			"#AA#"
	};
	/** 陶釉用哪一种：淡蓝色陶釉（26.x 里陶釉是 ColorCollection，按颜色取）。 */
	private static final BlockState GLAZED_STATE = Blocks.GLAZED_TERRACOTTA.lightBlue().defaultBlockState();
	/** 玩家空岛上副本入口石碑相对岛心的偏移（放在西南角，避开玩家落点和村民）。 */
	private static final int ENTRANCE_OFF_X = -4;
	private static final int ENTRANCE_OFF_Z = -4;

	/** 传送阵触发的防抖间隔（毫秒），避免一次传送被反复判定。 */
	private static final long PORTAL_DEBOUNCE_MS = 1500L;
	/** 同一条提示消息的节流间隔（毫秒），避免站在传送阵上被刷屏。 */
	private static final long DENY_MESSAGE_THROTTLE_MS = 10_000L;
	/** 每多少 tick 做一次怪物维护（补抗火 / 数量兜底）。5 秒一次。 */
	private static final int MOB_MAINTENANCE_INTERVAL = 100;
	/** 进入副本后连续补扫地面掉落物的 tick 数（10 秒），覆盖平台区块逐步加载完成的过程。 */
	private static final int DROP_CLEANUP_TICKS = 200;

	// 奖励池（石/铁/钻石）已迁移到 DungeonConfig.STONE_LOOT / IRON_LOOT / DIAMOND_LOOT，按难度选用。

	// ==================== 存档数据 ====================

	/** 副本的持久化数据：进入冷却记录、奖励箱位置、平台是否已建造。 */
	private static final class DungeonData extends SavedData {
		/** 玩家 UUID -> 上次进入副本的时间戳（毫秒），用于 30 分钟冷却。 */
		final Map<UUID, Long> lastEntry = new HashMap<>();
		/** 4 个奖励箱的位置（建造时随机散布，之后固定，每次进入只换内容）。 */
		List<BlockPos> chests = new ArrayList<>();
		/** 平台是否已经建造过。 */
		boolean built = false;
		/** 建造平台时用的边长。与 {@link DungeonConfig#SIZE} 不一致就说明尺寸改过，需要重建。 */
		int builtSize = 0;

		/** {@code Map<UUID, Long>} 的编解码（用字符串形式的 UUID 做 key，避免依赖额外 codec）。 */
		static final Codec<Map<UUID, Long>> UUID_LONG_MAP = Codec.unboundedMap(Codec.STRING, Codec.LONG).xmap(
				raw -> {
					final Map<UUID, Long> out = new HashMap<>();
					raw.forEach((k, v) -> out.put(UUID.fromString(k), v));
					return out;
				},
				typed -> {
					final Map<String, Long> out = new HashMap<>();
					typed.forEach((k, v) -> out.put(k.toString(), v));
					return out;
				}
		);

		static final Codec<DungeonData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
						UUID_LONG_MAP.optionalFieldOf("last_entry", new HashMap<>()).forGetter(d -> d.lastEntry),
						BlockPos.CODEC.listOf().optionalFieldOf("chests", new ArrayList<>()).forGetter(d -> d.chests),
						Codec.BOOL.optionalFieldOf("built", Boolean.FALSE).forGetter(d -> d.built),
						Codec.INT.optionalFieldOf("built_size", Integer.valueOf(0)).forGetter(d -> Integer.valueOf(d.builtSize))
				).apply(instance, DungeonData::new)
		);

		DungeonData(final Map<UUID, Long> lastEntry, final List<BlockPos> chests, final Boolean built, final Integer builtSize) {
			this.lastEntry.putAll(lastEntry);
			this.chests = new ArrayList<>(chests);
			this.built = Boolean.TRUE.equals(built);
			this.builtSize = builtSize == null ? 0 : builtSize.intValue();
		}

		DungeonData() {
			this(new HashMap<>(), new ArrayList<>(), Boolean.FALSE, Integer.valueOf(0));
		}
	}

	private static final SavedDataType<DungeonData> DUNGEON_TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath(Cjm_skyisland.MOD_ID, "dungeon"),
			DungeonData::new,
			DungeonData.CODEC,
			DataFixTypes.PLAYER
	);

	// ==================== 运行时（内存）状态 ====================

	/** 玩家 UUID -> 本次进入副本的时间戳，用于「最少 5 分钟 / 最多 20 分钟」判定。 */
	private static final Map<UUID, Long> inDungeonSince = new ConcurrentHashMap<>();
	/** 玩家 UUID -> 上次触发传送阵的时间戳（防抖）。 */
	private static final Map<UUID, Long> lastPortalUse = new ConcurrentHashMap<>();
	/** 玩家 UUID -> 上次发送拒绝提示的时间戳（节流）。 */
	private static final Map<UUID, Long> lastDenyMessage = new ConcurrentHashMap<>();
	/** tick 计数，用于周期性维护。 */
	private static int tickCounter = 0;
	/** 当前副本平台对应的怪物目标数量（由最近一次进入的难度决定），用于周期性维护。 */
	private static int currentMobTarget = DungeonConfig.MOB_COUNT;
	/**
	 * 进入副本后还需继续补扫掉落物的剩余 tick 数。
	 *
	 * <p>平台跨 7×7 个区块，进入时强制预载区块拿不到实体（ProtoChunk 不带实体），
	 * 所以改成「玩家已在副本内、区块真正加载完」之后，接下来若干 tick 反复补扫，确保清干净。
	 */
	private static int pendingDropCleanup = 0;
	/** 延时补扫阶段累计清掉的掉落物数量，用于日志。 */
	private static int deferredDropRemoved = 0;

	private Dungeon() {
	}

	// ==================== 区域判定 ====================

	/** 坐标是否落在副本范围内（只看水平范围，掉出平台的玩家仍算在副本里）。 */
	private static boolean isInDungeon(final int x, final int z) {
		return x >= DungeonConfig.MIN_X && x <= DungeonConfig.MAX_X
				&& z >= DungeonConfig.MIN_Z && z <= DungeonConfig.MAX_Z;
	}

	private static boolean isInDungeon(final BlockPos pos) {
		return isInDungeon(pos.getX(), pos.getZ());
	}

	/** 副本的实体检索框（略大于平台，含围墙与上方空间）。 */
	private static AABB regionBox() {
		return new AABB(
				DungeonConfig.MIN_X - 1, DungeonConfig.Y - 2, DungeonConfig.MIN_Z - 1,
				DungeonConfig.MAX_X + 2, DungeonConfig.Y + 12, DungeonConfig.MAX_Z + 2
		);
	}

	// ==================== 建造 ====================

	/** 玩家进服建岛后调用：在他的空岛上补一座 1×3 副本入口石碑（已存在则跳过，旧档缺中/上节时补齐）。 */
	public static void ensureEntrance(final ServerPlayer player, final ServerLevel world) {
		final BlockPos center = IslandSpawner.islandCenter(player);
		final BlockPos bottom = new BlockPos(
				center.getX() + ENTRANCE_OFF_X,
				SkyblockConfig.ISLAND_Y + 1,
				center.getZ() + ENTRANCE_OFF_Z
		);
		final BlockPos middle = bottom.above();
		final BlockPos top = middle.above();
		loadChunkAt(world, bottom);
		loadChunkAt(world, top);
		final boolean hasBottom = world.getBlockState(bottom).is(Cjm_skyisland.DUNGEON_CORE_BLOCK);
		final boolean hasMiddle = world.getBlockState(middle).is(Cjm_skyisland.DUNGEON_CORE_BLOCK);
		final boolean hasTop = world.getBlockState(top).is(Cjm_skyisland.DUNGEON_CORE_BLOCK);
		if (hasBottom && hasMiddle && hasTop) {
			return;
		}
		world.setBlockAndUpdate(bottom, Cjm_skyisland.DUNGEON_CORE_BLOCK.defaultBlockState()
				.setValue(DungeonCoreBlock.SECTION, DungeonCoreBlock.Section.BOTTOM));
		world.setBlockAndUpdate(middle, Cjm_skyisland.DUNGEON_CORE_BLOCK.defaultBlockState()
				.setValue(DungeonCoreBlock.SECTION, DungeonCoreBlock.Section.MIDDLE));
		world.setBlockAndUpdate(top, Cjm_skyisland.DUNGEON_CORE_BLOCK.defaultBlockState()
				.setValue(DungeonCoreBlock.SECTION, DungeonCoreBlock.Section.TOP));
		LOGGER.info("[skyisland] 已在 {} 的空岛上生成副本入口石碑 {}", player.getName().getString(), bottom.toShortString());
	}

	/** 确保某个坐标所在区块已加载（未加载时 getBlockState 会误报空气）。 */
	private static void loadChunkAt(final ServerLevel world, final BlockPos pos) {
		world.getChunkSource().getChunk(
				SectionPos.blockToSectionCoord(pos.getX()),
				SectionPos.blockToSectionCoord(pos.getZ()),
				ChunkStatus.FULL, true);
	}

	/** 按图案铺一座 4x4 传送阵（平铺，origin 为西北角）。 */
	private static void placePortal(final ServerLevel world, final BlockPos origin) {
		for (int lz = 0; lz < PORTAL_SIZE; lz++) {
			final String row = PORTAL_PATTERN[lz];
			for (int lx = 0; lx < PORTAL_SIZE; lx++) {
				final BlockState state = switch (row.charAt(lx)) {
					case 'A' -> Blocks.GLOWSTONE.defaultBlockState();
					case '#' -> GLAZED_STATE;
					case '*' -> Cjm_skyisland.PORTAL_BLOCK.defaultBlockState();
					default -> null;
				};
				if (state == null) {
					continue;
				}
				world.setBlockAndUpdate(origin.offset(lx, 0, lz), state);
			}
		}
	}

	/** 首次进入时建造副本平台（只建一次，之后靠存档标记跳过）。 */
	private static void ensureBuilt(final ServerLevel world, final DungeonData data) {
		// builtSize 与当前配置不一致 = 改过 SIZE，需要推倒重建
		final boolean sizeChanged = data.built && data.builtSize != DungeonConfig.SIZE;
		if (data.built && !sizeChanged && data.chests.size() == DungeonConfig.CHEST_COUNT) {
			return;
		}
		if (sizeChanged) {
			LOGGER.info("[skyisland] 副本尺寸由 {} 改为 {}，正在重建平台", data.builtSize, DungeonConfig.SIZE);
			clearPlatform(world, data.builtSize);
		}
		// 强制加载副本所在区块，否则 setBlock / getBlockState 会作用在空气上
		for (int sx = SectionPos.blockToSectionCoord(DungeonConfig.MIN_X); sx <= SectionPos.blockToSectionCoord(DungeonConfig.MAX_X); sx++) {
			for (int sz = SectionPos.blockToSectionCoord(DungeonConfig.MIN_Z); sz <= SectionPos.blockToSectionCoord(DungeonConfig.MAX_Z); sz++) {
				world.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, true);
			}
		}
		final BlockState floor = Blocks.STONE.defaultBlockState();
		final BlockState wall = Blocks.GLASS.defaultBlockState();
		// 地板：50x50，铺两层厚
		for (int x = DungeonConfig.MIN_X; x <= DungeonConfig.MAX_X; x++) {
			for (int z = DungeonConfig.MIN_Z; z <= DungeonConfig.MAX_Z; z++) {
				world.setBlockAndUpdate(new BlockPos(x, DungeonConfig.Y, z), floor);
				world.setBlockAndUpdate(new BlockPos(x, DungeonConfig.Y - 1, z), floor);
			}
		}
		// 围墙：沿最外圈铺 3 格高玻璃，防止玩家和怪物掉进虚空
		for (int x = DungeonConfig.MIN_X; x <= DungeonConfig.MAX_X; x++) {
			for (int z = DungeonConfig.MIN_Z; z <= DungeonConfig.MAX_Z; z++) {
				final boolean edge = x == DungeonConfig.MIN_X || x == DungeonConfig.MAX_X
						|| z == DungeonConfig.MIN_Z || z == DungeonConfig.MAX_Z;
				if (!edge) {
					continue;
				}
				for (int h = 1; h <= DungeonConfig.WALL_HEIGHT; h++) {
					world.setBlockAndUpdate(new BlockPos(x, DungeonConfig.Y + h, z), wall);
				}
			}
		}
		// 中央传送阵（回程用）
		placePortal(world, new BlockPos(DungeonConfig.CENTER_X - 2, DungeonConfig.Y + 1, DungeonConfig.CENTER_Z - 2));
		// 4 个奖励箱，随机散布
		data.chests = pickChestPositions(world);
		for (final BlockPos pos : data.chests) {
			world.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
		}
		data.built = true;
		data.builtSize = DungeonConfig.SIZE;
		data.setDirty();
		LOGGER.info("[skyisland] 副本空岛已建造于 ({}, {})，{}x{}，奖励箱 {} 个",
				DungeonConfig.CENTER_X, DungeonConfig.CENTER_Z,
				DungeonConfig.SIZE, DungeonConfig.SIZE, data.chests.size());
	}

	/**
	 * 清掉一座旧平台的地板、围墙、传送阵与奖励箱。
	 * 改了 {@link DungeonConfig#SIZE} 重建时必须先清，否则旧的玻璃围墙会留在放大后的平台中间。
	 */
	private static void clearPlatform(final ServerLevel world, final int oldSize) {
		final int half = Math.max(oldSize, 1) / 2;
		final int minX = DungeonConfig.CENTER_X - half;
		final int maxX = DungeonConfig.CENTER_X + half - 1;
		final int minZ = DungeonConfig.CENTER_Z - half;
		final int maxZ = DungeonConfig.CENTER_Z + half - 1;
		for (int sx = SectionPos.blockToSectionCoord(minX); sx <= SectionPos.blockToSectionCoord(maxX); sx++) {
			for (int sz = SectionPos.blockToSectionCoord(minZ); sz <= SectionPos.blockToSectionCoord(maxZ); sz++) {
				world.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, true);
			}
		}
		final BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int h = -1; h <= DungeonConfig.WALL_HEIGHT; h++) {
					world.setBlockAndUpdate(new BlockPos(x, DungeonConfig.Y + h, z), air);
				}
			}
		}
	}

	/** 随机挑 4 个互不重叠、且避开中央传送阵与围墙的奖励箱位置。 */
	private static List<BlockPos> pickChestPositions(final ServerLevel world) {
		final List<BlockPos> picked = new ArrayList<>();
		final RandomSource rand = world.getRandom();
		final int minX = DungeonConfig.MIN_X + 2;
		final int maxX = DungeonConfig.MAX_X - 2;
		final int minZ = DungeonConfig.MIN_Z + 2;
		final int maxZ = DungeonConfig.MAX_Z - 2;
		for (int tries = 0; tries < 400 && picked.size() < DungeonConfig.CHEST_COUNT; tries++) {
			final int x = minX + rand.nextInt(maxX - minX + 1);
			final int z = minZ + rand.nextInt(maxZ - minZ + 1);
			if (isNearCentralPortal(x, z)) {
				continue; // 别压在中央传送阵上
			}
			boolean tooClose = false;
			for (final BlockPos pos : picked) {
				if (Math.abs(pos.getX() - x) <= 4 && Math.abs(pos.getZ() - z) <= 4) {
					tooClose = true;
					break;
				}
			}
			if (tooClose) {
				continue;
			}
			picked.add(new BlockPos(x, DungeonConfig.Y + 1, z));
		}
		return picked;
	}

	/** 是否落在中央传送阵（含 1 格余量）范围内。 */
	private static boolean isNearCentralPortal(final int x, final int z) {
		return x >= DungeonConfig.CENTER_X - 3 && x <= DungeonConfig.CENTER_X + 2
				&& z >= DungeonConfig.CENTER_Z - 3 && z <= DungeonConfig.CENTER_Z + 2;
	}

	// ==================== 重置：怪物与奖励箱 ====================

	/** 每次开启：加载平台区块 -> 清掉旧怪与地面掉落物 -> 按难度刷怪 -> 按难度重填奖励箱。 */
	private static void resetDungeon(final ServerLevel world, final DungeonData data, final int diff) {
		final DungeonConfig.Difficulty d = DungeonConfig.DIFFICULTIES[diff];
		// 先把平台区块拉到 FULL，保证后面的刷怪 / 填箱能落地（区块没加载时 addFreshEntity 会静默丢弃）
		// 注意：这样拿到的区块里的实体还没挂进世界，所以掉落物清不掉，靠进入后的延时补扫兜底
		for (int sx = SectionPos.blockToSectionCoord(DungeonConfig.MIN_X); sx <= SectionPos.blockToSectionCoord(DungeonConfig.MAX_X); sx++) {
			for (int sz = SectionPos.blockToSectionCoord(DungeonConfig.MIN_Z); sz <= SectionPos.blockToSectionCoord(DungeonConfig.MAX_Z); sz++) {
				world.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, true);
			}
		}
		final AABB box = regionBox();
		// 清掉上一轮残留的怪物
		int mobRemoved = 0;
		for (final Monster old : world.getEntitiesOfClass(Monster.class, box, mob -> mob.isAlive())) {
			old.discard();
			mobRemoved++;
		}
		// 清掉上一轮残留的地面掉落物（玩家死亡掉落 / 怪物掉落 / 箱子被掏后的散落物）
		final int dropRemoved = clearDrops(world);
		LOGGER.info("[skyisland] 副本重置：清掉 {} 只旧怪、{} 个地面掉落物（即时阶段）", mobRemoved, dropRemoved);
		currentMobTarget = d.mobCount;
		spawnMobs(world, d.mobCount);
		refillChests(world, data, d.loot);
	}

	/**
	 * 清掉副本范围内的所有地面掉落物，返回清掉的数量。
	 *
	 * <p>注意：只能扫到<b>已加载且实体已入世界</b>的区块，所以进入副本时会即时清一次，
	 * 之后再靠 {@link #pendingDropCleanup} 在若干 tick 内补扫，覆盖区块逐步加载完成的过程。
	 */
	private static int clearDrops(final ServerLevel world) {
		int removed = 0;
		for (final ItemEntity drop : world.getEntitiesOfClass(ItemEntity.class, regionBox(), e -> e.isAlive())) {
			drop.discard();
			removed++;
		}
		return removed;
	}

	/** 按给定数量刷怪：僵尸 / 小僵尸 / 骷髅弓箭手，各约三分之一。 */
	private static void spawnMobs(final ServerLevel world, final int mobCount) {
		final RandomSource rand = world.getRandom();
		for (int i = 0; i < mobCount; i++) {
			final BlockPos spot = randomFloorPos(rand);
			final int kind = i % 3;
			if (kind == 2) {
				final Skeleton skeleton = EntityTypes.SKELETON.spawn(world, spot, EntitySpawnReason.EVENT);
				if (skeleton != null) {
					applyDungeonBuffs(skeleton);
				}
			} else {
				final Zombie zombie = EntityTypes.ZOMBIE.spawn(world, spot, EntitySpawnReason.EVENT);
				if (zombie != null) {
					if (kind == 1) {
						zombie.setBaby(true); // 小僵尸
					}
					applyDungeonBuffs(zombie);
				}
			}
		}
	}

	/** 副本怪物通用设置：常驻不消失 + 永久抗火（白天不自燃 = 不畏惧阳光）。 */
	private static void applyDungeonBuffs(final Mob mob) {
		mob.setPersistenceRequired();
		mob.addEffect(new MobEffectInstance(
				MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, false));
	}

	/** 平台内的随机落点（避开围墙）。 */
	private static BlockPos randomFloorPos(final RandomSource rand) {
		final int x = DungeonConfig.MIN_X + 2 + rand.nextInt(DungeonConfig.SIZE - 4);
		final int z = DungeonConfig.MIN_Z + 2 + rand.nextInt(DungeonConfig.SIZE - 4);
		return new BlockPos(x, DungeonConfig.Y + 1, z);
	}

	/** 玩家进入时的随机落点：避开中央传送阵和奖励箱，免得一落地就被传走 / 卡在箱子里。 */
	private static BlockPos randomSpawnPos(final ServerLevel world, final DungeonData data) {
		final RandomSource rand = world.getRandom();
		for (int tries = 0; tries < 64; tries++) {
			final BlockPos pos = randomFloorPos(rand);
			if (isNearCentralPortal(pos.getX(), pos.getZ())) {
				continue;
			}
			boolean nearChest = false;
			for (final BlockPos chest : data.chests) {
				if (Math.abs(chest.getX() - pos.getX()) <= 1 && Math.abs(chest.getZ() - pos.getZ()) <= 1) {
					nearChest = true;
					break;
				}
			}
			if (!nearChest) {
				return pos;
			}
		}
		return randomFloorPos(rand);
	}

	/**
	 * 重填 4 个奖励箱：打乱铁制装备池后平均分配，保证每个箱子的战利品互不相同。
	 * 池子只有 9 件、箱子 4 个，所以按「每个 2 件、最后一个拿剩下的 3 件」分配。
	 */
	private static void refillChests(final ServerLevel world, final DungeonData data, final List<Item> loot) {
		if (data.chests.isEmpty()) {
			return;
		}
		final List<Item> pool = new ArrayList<>(loot);
		Collections.shuffle(pool);
		final int perChest = pool.size() / data.chests.size();
		int cursor = 0;
		for (int i = 0; i < data.chests.size(); i++) {
			final BlockEntity blockEntity = world.getBlockEntity(data.chests.get(i));
			if (!(blockEntity instanceof ChestBlockEntity chest)) {
				continue;
			}
			final int count = (i == data.chests.size() - 1) ? (pool.size() - cursor) : perChest;
			chest.clearContent();
			for (int slot = 0; slot < count && cursor < pool.size(); slot++, cursor++) {
				chest.setItem(slot, new ItemStack(pool.get(cursor)));
			}
			chest.setChanged();
		}
	}

	/** 周期性维护：补抗火效果 + 数量兜底（the_void 群系本就不会自然刷怪，这里只是保险）。 */
	private static void maintainMobs(final ServerLevel world) {
		final List<Monster> mobs = world.getEntitiesOfClass(Monster.class, regionBox(), mob -> mob.isAlive());
		for (int i = 0; i < mobs.size(); i++) {
			final Monster mob = mobs.get(i);
			if (i >= currentMobTarget) {
				mob.discard(); // 超出目标数量的部分清掉，保持固定数量
				continue;
			}
			if (!mob.hasEffect(MobEffects.FIRE_RESISTANCE)) {
				mob.addEffect(new MobEffectInstance(
						MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, false));
			}
		}
	}

	// ==================== 进出副本 ====================

	/** 服务端入口：校验并扣除硬币后，按所选难度把玩家送入副本。 */
	static void requestEnter(final ServerPlayer player, final int diff) {
		if (diff < 0 || diff >= DungeonConfig.DIFFICULTIES.length) {
			deny(player, "未知的难度档位");
			return;
		}
		final MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		final ServerLevel world = server.getLevel(Level.OVERWORLD);
		if (world == null) {
			return;
		}
		final DungeonData data = world.getDataStorage().computeIfAbsent(DUNGEON_TYPE);
		final DungeonConfig.Difficulty d = DungeonConfig.DIFFICULTIES[diff];
		// 校验硬币
		final int have = countCoins(player);
		if (have < d.cost) {
			deny(player, "硬币不足：需要 " + d.cost + " 个，你只有 " + have + " 个");
			return;
		}
		// 扣除硬币（选难度即进入，没有独立传送阵再扣费的问题）
		removeCoins(player, d.cost);
		enterDungeon(player, world, System.currentTimeMillis(), diff);
		player.sendSystemMessage(Component.literal("已扣除 " + d.cost + " 硬币，进入「" + d.nameZh + "」难度。"));
	}

	/** 统计玩家背包（主背包 + 副手）里的硬币数量。 */
	private static int countCoins(final ServerPlayer player) {
		int n = 0;
		for (final ItemStack s : player.getInventory().getNonEquipmentItems()) {
			if (s.is(Cjm_skyisland.COIN)) {
				n += s.getCount();
			}
		}
		return n;
	}

	/** 从玩家背包（主背包 + 副手）移除指定数量的硬币。 */
	private static void removeCoins(final ServerPlayer player, final int amount) {
		int remaining = amount;
		for (final ItemStack s : player.getInventory().getNonEquipmentItems()) {
			if (remaining <= 0) {
				break;
			}
			if (s.is(Cjm_skyisland.COIN)) {
				final int take = Math.min(s.getCount(), remaining);
				s.shrink(take);
				remaining -= take;
			}
		}
		player.inventoryMenu.broadcastChanges();
	}

	/** 真正执行进入：确保平台、按难度重置、传送、记录停留时间。 */
	private static void enterDungeon(final ServerPlayer player, final ServerLevel world, final long now, final int diff) {
		final DungeonData data = world.getDataStorage().computeIfAbsent(DUNGEON_TYPE);
		ensureBuilt(world, data);
		resetDungeon(world, data, diff);
		data.lastEntry.put(player.getUUID(), now);
		data.setDirty();
		inDungeonSince.put(player.getUUID(), now);

		final DungeonConfig.Difficulty d = DungeonConfig.DIFFICULTIES[diff];
		final BlockPos floor = randomSpawnPos(world, data);
		// randomSpawnPos 返回的是「地板上方那一格」，玩家脚部正好落在这一格，直接踩在石地板上
		player.teleportTo(world, floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5,
				Set.of(), player.getYRot(), player.getXRot(), false);
		// 传送之后再排一轮延时补扫：玩家已在副本内，平台区块会在这几秒里加载完，届时掉落物才扫得到
		pendingDropCleanup = DROP_CLEANUP_TICKS;
		deferredDropRemoved = 0;
		player.sendSystemMessage(Component.literal("你已进入副本空岛（" + d.nameZh + "）：" + d.mobCount + " 只怪物、4 个奖励箱。"));
		player.sendSystemMessage(Component.literal("最少停留 5 分钟才能从中央传送阵离开，20 分钟后会被强制淘汰。"));
		LOGGER.info("[skyisland] {} 进入副本空岛（{} 难度，落点 {}）", player.getName().getString(), d.nameZh, floor.toShortString());
	}

	private static void tryReturn(final ServerPlayer player, final ServerLevel world, final long now) {
		final Long since = inDungeonSince.get(player.getUUID());
		if (since != null) {
			final long elapsed = now - since;
			if (elapsed < DungeonConfig.MIN_STAY_MS) {
				final long remainSec = (DungeonConfig.MIN_STAY_MS - elapsed) / 1000L;
				deny(player, "还需停留 " + remainSec / 60 + " 分 " + remainSec % 60 + " 秒才能离开副本");
				return;
			}
		}
		inDungeonSince.remove(player.getUUID());
		teleportHome(player, world);
	}

	/** 把玩家送回自己的空岛。 */
	private static void teleportHome(final ServerPlayer player, final ServerLevel world) {
		final BlockPos spawn = IslandSpawner.islandSpawn(player);
		player.teleportTo(world, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5,
				Set.of(), 0.0F, 0.0F, false);
		player.sendSystemMessage(Component.literal("你已离开副本，回到自己的空岛。"));
		LOGGER.info("[skyisland] {} 已离开副本空岛", player.getName().getString());
	}

	// ==================== 提示 / 判定辅助 ====================

	/** 带节流的拒绝提示，避免站在传送阵上每 tick 刷屏。 */
	private static void deny(final ServerPlayer player, final String message) {
		final long now = System.currentTimeMillis();
		final Long last = lastDenyMessage.get(player.getUUID());
		if (last != null && now - last < DENY_MESSAGE_THROTTLE_MS) {
			return;
		}
		lastDenyMessage.put(player.getUUID(), now);
		player.sendSystemMessage(Component.literal(message));
	}

	/**
	 * 玩家是不是站在传送门里。
	 *
	 * <p>传送门方块是 {@code noCollision()} 的（像末地传送门），玩家踩的是它下面那一格地面，
	 * 身体则处在传送门方块所在格 —— 所以要检查脚下、脚下上方（身体所在）两格。
	 * 保留脚下那一格的判断是为了兼容仍在旧存档里的老传送阵。
	 */
	private static boolean isOnPortal(final ServerLevel world, final ServerPlayer player) {
		final BlockPos feet = player.blockPosition();
		return world.getBlockState(feet).is(Cjm_skyisland.PORTAL_BLOCK)
				|| world.getBlockState(feet.below()).is(Cjm_skyisland.PORTAL_BLOCK)
				|| world.getBlockState(feet.above()).is(Cjm_skyisland.PORTAL_BLOCK);
	}

	// ==================== 每 tick 处理 ====================

	private static void onEndTick(final MinecraftServer server) {
		final ServerLevel world = server.getLevel(Level.OVERWORLD);
		if (world == null) {
			return;
		}
		final long now = System.currentTimeMillis();
		for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
			handlePlayer(player, world, now);
		}
		tickCounter++;
		if (pendingDropCleanup > 0) {
			pendingDropCleanup--;
			deferredDropRemoved += clearDrops(world);
			if (pendingDropCleanup == 0) {
				LOGGER.info("[skyisland] 副本掉落物补扫结束：{} tick 内共清掉 {} 个地面掉落物",
						DROP_CLEANUP_TICKS, deferredDropRemoved);
			}
		}
		if (tickCounter % MOB_MAINTENANCE_INTERVAL == 0) {
			maintainMobs(world);
		}
	}

	private static void handlePlayer(final ServerPlayer player, final ServerLevel world, final long now) {
		final UUID id = player.getUUID();
		// 1) 超时强制淘汰
		final Long since = inDungeonSince.get(id);
		if (since != null && now - since >= DungeonConfig.MAX_STAY_MS) {
			inDungeonSince.remove(id);
			player.sendSystemMessage(Component.literal("副本停留已超过 20 分钟，你被强制淘汰了！"));
			player.hurt(player.damageSources().generic(), Float.MAX_VALUE);
			return;
		}
		// 2) 副本中央传送阵 -> 回家（玩家岛上已无传送阵，进入副本靠右键石碑选择难度）
		if (!isOnPortal(world, player)) {
			return;
		}
		final Long last = lastPortalUse.get(id);
		if (last != null && now - last < PORTAL_DEBOUNCE_MS) {
			return;
		}
		lastPortalUse.put(id, now);
		if (isInDungeon(player.getBlockX(), player.getBlockZ())) {
			tryReturn(player, world, now);
		}
	}

	// ==================== 事件注册 ====================

	/** 注册传送阵检测、副本保护、重生清理。 */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(Dungeon::onEndTick);

		// 副本内禁止破坏；传送阵任何地方都拆不掉
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			if (!(player instanceof ServerPlayer serverPlayer)) {
				return true;
			}
			if (isInDungeon(pos)) {
				deny(serverPlayer, "副本空岛内不能破坏方块");
				return false;
			}
			if (state.is(Cjm_skyisland.PORTAL_BLOCK) || state.is(Cjm_skyisland.DUNGEON_CORE_BLOCK)) {
				deny(serverPlayer, "副本入口石碑与中央传送阵无法被破坏");
				return false;
			}
			return true;
		});

		// 副本内禁止放置方块（只拦「手持方块」的右键，开箱子 / 吃食物不受影响）
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> shouldBlockPlacement(player, hand));
		UseItemCallback.EVENT.register((player, level, hand) -> shouldBlockPlacement(player, hand));

		// 死在副本里（含 20 分钟淘汰）后重生：清掉停留记录，避免再次被判定超时
		ServerPlayerEvents.AFTER_RESPAWN.register((player, oldPlayer, alive) -> {
			inDungeonSince.remove(player.getUUID());
		});
	}

	/** 副本内手持方块右键时拦截放置，其它交互（开箱、吃东西等）照常。 */
	private static InteractionResult shouldBlockPlacement(final Player player, final InteractionHand hand) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (!isInDungeon(serverPlayer.getBlockX(), serverPlayer.getBlockZ())) {
			return InteractionResult.PASS;
		}
		final ItemStack stack = player.getItemInHand(hand);
		if (!(stack.getItem() instanceof BlockItem)) {
			return InteractionResult.PASS;
		}
		deny(serverPlayer, "副本空岛内不能放置方块");
		return InteractionResult.FAIL;
	}
}

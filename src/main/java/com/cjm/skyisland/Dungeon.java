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
 *   <li>玩家自己的空岛上固定生成一座 4x4 传送阵（{@code #AA# / A**A / A**A / #AA#}），
 *       站上去进入副本；副本正中还有一座同样的传送阵，站上去回自己的空岛。</li>
 *   <li>每次进入都会<b>重置</b>副本：清掉旧怪后重新刷满 100 只（僵尸 / 小僵尸 / 骷髅弓箭手），
 *       并重新随机填充 4 个奖励箱的战利品（铁制武器与工具）。</li>
 *   <li>同一玩家 30 分钟冷却；进入后必须待满 5 分钟才能走；超过 20 分钟不走就自动死亡。</li>
 *   <li>副本内玩家不能放置、不能破坏任何方块（传送阵本身也拆不掉）。</li>
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
	/** 玩家空岛上传送阵相对岛心的偏移（放在西南角，避开玩家落点和村民）。 */
	private static final int HOME_PORTAL_OFF_X = -4;
	private static final int HOME_PORTAL_OFF_Z = -4;

	/** 传送阵触发的防抖间隔（毫秒），避免一次传送被反复判定。 */
	private static final long PORTAL_DEBOUNCE_MS = 1500L;
	/** 同一条提示消息的节流间隔（毫秒），避免站在传送阵上被刷屏。 */
	private static final long DENY_MESSAGE_THROTTLE_MS = 10_000L;
	/** 每多少 tick 做一次怪物维护（补抗火 / 数量兜底）。5 秒一次。 */
	private static final int MOB_MAINTENANCE_INTERVAL = 100;

	/** 奖励池：铁制武器与工具（外加铁盔甲，保证 4 个箱子能分出不同组合）。 */
	private static final List<Item> IRON_LOOT = List.of(
			Items.IRON_SWORD,
			Items.IRON_AXE,
			Items.IRON_PICKAXE,
			Items.IRON_SHOVEL,
			Items.IRON_HOE,
			Items.IRON_HELMET,
			Items.IRON_CHESTPLATE,
			Items.IRON_LEGGINGS,
			Items.IRON_BOOTS
	);

	// ==================== 存档数据 ====================

	/** 副本的持久化数据：进入冷却记录、奖励箱位置、平台是否已建造。 */
	private static final class DungeonData extends SavedData {
		/** 玩家 UUID -> 上次进入副本的时间戳（毫秒），用于 30 分钟冷却。 */
		final Map<UUID, Long> lastEntry = new HashMap<>();
		/** 4 个奖励箱的位置（建造时随机散布，之后固定，每次进入只换内容）。 */
		List<BlockPos> chests = new ArrayList<>();
		/** 平台是否已经建造过。 */
		boolean built = false;

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
						Codec.BOOL.optionalFieldOf("built", Boolean.FALSE).forGetter(d -> d.built)
				).apply(instance, DungeonData::new)
		);

		DungeonData(final Map<UUID, Long> lastEntry, final List<BlockPos> chests, final Boolean built) {
			this.lastEntry.putAll(lastEntry);
			this.chests = new ArrayList<>(chests);
			this.built = Boolean.TRUE.equals(built);
		}

		DungeonData() {
			this(new HashMap<>(), new ArrayList<>(), Boolean.FALSE);
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

	/** 玩家进服建岛后调用：在他的空岛上补一座传送阵（已存在则跳过）。 */
	public static void ensureHomePortal(final ServerPlayer player, final ServerLevel world) {
		final BlockPos center = IslandSpawner.islandCenter(player);
		final BlockPos origin = new BlockPos(
				center.getX() + HOME_PORTAL_OFF_X,
				SkyblockConfig.ISLAND_Y + 1,
				center.getZ() + HOME_PORTAL_OFF_Z
		);
		// 传送阵可能跨到相邻区块，先加载再判定：未加载时 getBlockState 会误报空气而重复铺设
		loadChunkAt(world, origin);
		loadChunkAt(world, origin.offset(PORTAL_SIZE - 1, 0, PORTAL_SIZE - 1));
		// 中心 2x2 已经是传送门方块就说明建过了
		if (world.getBlockState(origin.offset(1, 0, 1)).is(Cjm_skyisland.PORTAL_BLOCK)) {
			return;
		}
		placePortal(world, origin);
		LOGGER.info("[skyisland] 已在 {} 的空岛上生成副本传送阵 {}", player.getName().getString(), origin.toShortString());
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
		if (data.built && data.chests.size() == DungeonConfig.CHEST_COUNT) {
			return;
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
		data.setDirty();
		LOGGER.info("[skyisland] 副本空岛已建造于 ({}, {})，奖励箱 {} 个",
				DungeonConfig.CENTER_X, DungeonConfig.CENTER_Z, data.chests.size());
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

	/** 每次开启：清掉旧怪 -> 刷满 100 只 -> 重填奖励箱。 */
	private static void resetDungeon(final ServerLevel world, final DungeonData data) {
		final AABB box = regionBox();
		// 清掉上一轮残留的怪物
		for (final Monster old : world.getEntitiesOfClass(Monster.class, box, mob -> mob.isAlive())) {
			old.discard();
		}
		spawnMobs(world);
		refillChests(world, data);
	}

	/** 固定刷 100 只：僵尸 / 小僵尸 / 骷髅弓箭手，各约三分之一。 */
	private static void spawnMobs(final ServerLevel world) {
		final RandomSource rand = world.getRandom();
		for (int i = 0; i < DungeonConfig.MOB_COUNT; i++) {
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
	private static void refillChests(final ServerLevel world, final DungeonData data) {
		if (data.chests.isEmpty()) {
			return;
		}
		final List<Item> pool = new ArrayList<>(IRON_LOOT);
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
			if (i >= DungeonConfig.MOB_COUNT) {
				mob.discard(); // 超出 100 只的部分清掉，保持固定数量
				continue;
			}
			if (!mob.hasEffect(MobEffects.FIRE_RESISTANCE)) {
				mob.addEffect(new MobEffectInstance(
						MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, false));
			}
		}
	}

	// ==================== 进出副本 ====================

	private static void tryEnter(final ServerPlayer player, final ServerLevel world, final long now) {
		final DungeonData data = world.getDataStorage().computeIfAbsent(DUNGEON_TYPE);
		final Long last = data.lastEntry.get(player.getUUID());
		if (last != null && now - last < DungeonConfig.COOLDOWN_MS) {
			final long remainSec = (DungeonConfig.COOLDOWN_MS - (now - last)) / 1000L;
			deny(player, "副本冷却中，还需 " + remainSec / 60 + " 分 " + remainSec % 60 + " 秒才能再次进入");
			return;
		}
		ensureBuilt(world, data);
		resetDungeon(world, data);
		data.lastEntry.put(player.getUUID(), now);
		data.setDirty();
		inDungeonSince.put(player.getUUID(), now);

		final BlockPos floor = randomSpawnPos(world, data);
		// randomSpawnPos 返回的是「地板上方那一格」，玩家脚部正好落在这一格，直接踩在石地板上
		player.teleportTo(world, floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5,
				Set.of(), player.getYRot(), player.getXRot(), false);
		player.sendSystemMessage(Component.literal("你已进入副本空岛：100 只怪物、4 个奖励箱。"));
		player.sendSystemMessage(Component.literal("最少停留 5 分钟才能从中央传送阵离开，20 分钟后会被强制淘汰。"));
		LOGGER.info("[skyisland] {} 进入副本空岛（落点 {}）", player.getName().getString(), floor.toShortString());
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

	/** 玩家脚下踩的是不是传送门方块。 */
	private static boolean isOnPortal(final ServerLevel world, final ServerPlayer player) {
		final BlockPos below = new BlockPos(player.getBlockX(), player.getBlockY() - 1, player.getBlockZ());
		return world.getBlockState(below).is(Cjm_skyisland.PORTAL_BLOCK);
	}

	/** 该坐标是否属于「玩家自己岛上那座传送阵」的 4x4 范围。 */
	private static boolean isHomePortalFootprint(final ServerPlayer player, final BlockPos pos) {
		if (pos.getY() != SkyblockConfig.ISLAND_Y + 1) {
			return false;
		}
		final BlockPos center = IslandSpawner.islandCenter(player);
		final int ox = center.getX() + HOME_PORTAL_OFF_X;
		final int oz = center.getZ() + HOME_PORTAL_OFF_Z;
		return pos.getX() >= ox && pos.getX() <= ox + PORTAL_SIZE - 1
				&& pos.getZ() >= oz && pos.getZ() <= oz + PORTAL_SIZE - 1;
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
		// 2) 传送阵
		if (!isOnPortal(world, player)) {
			return;
		}
		final Long last = lastPortalUse.get(id);
		if (last != null && now - last < PORTAL_DEBOUNCE_MS) {
			return;
		}
		lastPortalUse.put(id, now);
		if (isInDungeon(player.getBlockX(), player.getBlockZ())) {
			tryReturn(player, world, now); // 副本中央传送阵 -> 回家
		} else {
			tryEnter(player, world, now); // 自己岛上的传送阵 -> 进副本
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
			if (state.is(Cjm_skyisland.PORTAL_BLOCK) || isHomePortalFootprint(serverPlayer, pos)) {
				deny(serverPlayer, "传送阵无法被破坏");
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

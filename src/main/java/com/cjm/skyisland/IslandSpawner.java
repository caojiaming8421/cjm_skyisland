package com.cjm.skyisland;

import com.cjm.skyisland.entity.CjmVillager;
import com.cjm.skyisland.world.SkyblockConfig;
import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.SectionPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 负责给每个玩家生成专属空岛，并保证「没有有效重生锚点时必回空岛」。
 *
 * <p>进服时按 UUID 派生固定岛格坐标，在格中心生成 10x10 平台（草/泥/石三层），
 * 把重生点设到岛上并 {@code forced=true} —— 这样玩家没床时死亡一定回到自己的岛，不会掉进虚空。
 *
 * <p>玩家睡床后，原版 {@code startSleepInBed} 会把重生点覆盖成床的位置（forced=false），
 * 因此「有床走床」的行为自然保留。但床一旦被拆，原版会退回「世界默认出生点」，
 * 在虚空世界里那是个随机位置 —— 表现为「床拆了之后重生就随机了」。
 *
 * <p>为此做三层保险，任何一层生效都能把重生点拉回空岛：
 * <ol>
 *   <li><b>拆床瞬间</b>（{@code PlayerBlockBreakEvents.AFTER}）：床/重生锚被拆，立刻把失效的重生点改回空岛。</li>
 *   <li><b>死亡瞬间</b>（{@code ServerLivingEntityEvents.AFTER_DEATH}）：再校验一次。此时改重生点，
 *       原版随后的重生流程读到的就是有效的空岛点，玩家直接回岛，不会再落到世界出生点。</li>
 *   <li><b>重生之后</b>（{@code ServerPlayerEvents.AFTER_RESPAWN}）：兜底传送，万一前两层都没赶上。</li>
 * </ol>
 *
 * <p>「中心点是否为空气」用于判断岛是否存在：平台方块持久化在存档里，重启后照样在，
 * 只在真正缺失时才重建，避免重复生成。判定前会先加载岛所在区块——否则未加载区块上
 * getBlockState 误报空气，会每次进服都重建岛 + 重复发初始物资。
 */
public final class IslandSpawner {
	private static final Logger LOGGER = LogUtils.getLogger();

	/**
	 * 记录「哪些玩家已领取过初始物资」的存档数据（按玩家 UUID 存储），跨进服 / 重生 / 重启都保留，
	 * 用于保证初始物资每个玩家只发一次，避免重复进入世界时反复给。
	 */
	private static final class StarterKitData extends SavedData {
		private final Set<UUID> received = new HashSet<>();

		boolean has(final UUID id) {
			return received.contains(id);
		}

		void mark(final UUID id) {
			received.add(id);
			setDirty();
		}

		static final com.mojang.serialization.Codec<StarterKitData> CODEC = UUIDUtil.CODEC_SET.xmap(
				set -> {
					final StarterKitData d = new StarterKitData();
					d.received.addAll(set);
					return d;
				},
				d -> d.received
		);
	}

	private static final SavedDataType<StarterKitData> STARTER_KIT_TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("cjm_skyisland", "starter_kit"),
			StarterKitData::new,
			StarterKitData.CODEC,
			DataFixTypes.PLAYER
	);

	private IslandSpawner() {
	}

	/** 按玩家 UUID 派生稳定的空岛中心坐标。 */
	public static BlockPos islandCenter(final ServerPlayer player) {
		final long h = player.getUUID().getMostSignificantBits() ^ player.getUUID().getLeastSignificantBits();
		final int gx = (int) Long.remainderUnsigned(h, 2000);
		final int gz = (int) Long.remainderUnsigned(h >>> 21, 2000);
		final int centerX = gx * SkyblockConfig.GRID + SkyblockConfig.GRID / 2;
		final int centerZ = gz * SkyblockConfig.GRID + SkyblockConfig.GRID / 2;
		return new BlockPos(centerX, SkyblockConfig.ISLAND_Y, centerZ);
	}

	/** 空岛重生点：草方块上方的空气格，原版 forced 逻辑才能在此落脚（站在草上）。 */
	public static BlockPos islandSpawn(final ServerPlayer player) {
		return islandCenter(player).above();
	}

	/** 取主世界（服务端）。 */
	private static ServerLevel overworld(final MinecraftServer server) {
		return server == null ? null : server.getLevel(Level.OVERWORLD);
	}

	/** ServerPlayer 本身没有 getServer()，要从它所在的维度拿。 */
	private static MinecraftServer serverOf(final ServerPlayer player) {
		return player.level() instanceof ServerLevel sl ? sl.getServer() : null;
	}

	/**
	 * 若岛平台缺失则生成（已存在则跳过）。
	 *
	 * @return true 表示这次是「新建岛」（需要连带生成村民）；false 表示岛本来就存在。
	 */
	private static boolean buildIsland(final ServerPlayer player, final ServerLevel world) {
		final BlockPos center = islandCenter(player);
		// 先确保岛所在区块已加载：未加载时 getBlockState 会误报空气，导致每次进服都判定「岛缺失」而重建 + 重复发物资
		world.getChunkSource().getChunk(
				SectionPos.blockToSectionCoord(center.getX()),
				SectionPos.blockToSectionCoord(center.getZ()),
				ChunkStatus.FULL, true);
		if (!world.getBlockState(center).isAir()) {
			return false; // 平台已存在
		}
		final int y = SkyblockConfig.ISLAND_Y;
		final int cx = center.getX();
		final int cz = center.getZ();
		final BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
		final BlockState dirt = Blocks.DIRT.defaultBlockState();
		final BlockState stone = Blocks.STONE.defaultBlockState();
		for (int dx = -SkyblockConfig.ISLAND_HALF; dx <= SkyblockConfig.ISLAND_HALF; dx++) {
			for (int dz = -SkyblockConfig.ISLAND_HALF; dz <= SkyblockConfig.ISLAND_HALF; dz++) {
				final BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
				world.setBlockAndUpdate(pos, grass);
				world.setBlockAndUpdate(pos.below(1), dirt);
				world.setBlockAndUpdate(pos.below(2), stone);
			}
		}
		return true;
	}

	/** 在空岛上生成一只「空岛村民」（每个岛固定一只，已存在则跳过）。 */
	private static void ensureIslandVillager(final ServerPlayer player, final ServerLevel world) {
		final BlockPos center = islandCenter(player);
		final int r = SkyblockConfig.ISLAND_HALF + 2;
		final AABB box = new AABB(
				center.getX() - r, center.getY() - 4, center.getZ() - r,
				center.getX() + r + 1, center.getY() + 6, center.getZ() + r + 1
		);
		final boolean exists = !world.getEntitiesOfClass(CjmVillager.class, box, e -> e.isAlive()).isEmpty();
		if (exists) {
			return;
		}
		// 站在草方块上，稍微偏离岛心，避免和玩家落点重叠
		final BlockPos spot = center.offset(2, 1, 0);
		final CjmVillager villager = Cjm_skyisland.CJM_VILLAGER.spawn(world, spot, EntitySpawnReason.EVENT);
		if (villager == null) {
			return;
		}
		villager.setYRot(world.getRandom().nextFloat() * 360.0F);
		villager.setPersistenceRequired(); // 不因距离过远而消失
	}

	/** 发放初始物资：1 个橡树树苗 + 4 个骨粉（是否只发一次由调用方用 StarterKitData 标记控制）。 */
	private static void giveStarterKit(final ServerPlayer player) {
		player.getInventory().add(new ItemStack(Items.OAK_SAPLING, 1));
		player.getInventory().add(new ItemStack(Items.BONE_MEAL, 4));
		player.inventoryMenu.broadcastChanges();
		player.sendSystemMessage(Component.literal("你收到了初始物资：橡树树苗 ×1、骨粉 ×4"));
		LOGGER.info("[skyisland] 向 {} 发放初始物资（橡树树苗 ×1、骨粉 ×4）", player.getName().getString());
	}

	/** 把重生点设为自己的空岛（forced=true，没床时必回此处）。只写数据，不传送。 */
	private static void setIslandRespawn(final ServerPlayer player, final ServerLevel world) {
		final BlockPos spawn = islandSpawn(player);
		player.setRespawnPosition(new ServerPlayer.RespawnConfig(
				LevelData.RespawnData.of(world.dimension(), spawn, player.getYRot(), player.getXRot()),
				true
		), false);
	}

	/** 把玩家传送上空岛，并把重生点设为岛（forced=true）。 */
	private static void teleportToIsland(final ServerPlayer player, final ServerLevel world) {
		final BlockPos spawn = islandSpawn(player);
		setIslandRespawn(player, world);
		player.teleportTo(world, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5, Set.of(), 0.0f, 0.0f, false);
	}

	/** 玩家是否有有效的重生锚点（床 / 重生锚）。 */
	private static boolean hasValidAnchor(final ServerPlayer player) {
		final MinecraftServer server = serverOf(player);
		if (server == null) {
			return false;
		}
		final ServerPlayer.RespawnConfig rc = player.getRespawnConfig();
		if (rc == null) {
			return false;
		}
		final LevelData.RespawnData data = rc.respawnData();
		final ResourceKey<Level> dim = data.dimension();
		final ServerLevel level = server.getLevel(dim);
		if (level == null) {
			return false;
		}
		final BlockState bs = level.getBlockState(data.pos());
		return bs.getBlock() instanceof AbstractBedBlock || bs.is(Blocks.RESPAWN_ANCHOR);
	}

	/** 重生点是否已经指向自己的空岛（forced 的岛点）。用于避免无意义的重复修正/传送。 */
	private static boolean isIslandRespawn(final ServerPlayer player) {
		final ServerPlayer.RespawnConfig rc = player.getRespawnConfig();
		if (rc == null || !rc.forced()) {
			return false;
		}
		final LevelData.RespawnData data = rc.respawnData();
		return data.dimension().equals(Level.OVERWORLD) && data.pos().equals(islandSpawn(player));
	}

	/**
	 * 把失效的重生点恢复成空岛。
	 *
	 * @param reason 触发原因，写进日志便于定位是哪一层保险生效。
	 */
	private static void restoreIslandRespawn(final ServerPlayer player, final String reason) {
		if (hasValidAnchor(player) || isIslandRespawn(player)) {
			return; // 有床走床；或者已经就是空岛点，不用重复写
		}
		final ServerLevel world = overworld(serverOf(player));
		if (world == null) {
			return;
		}
		buildIsland(player, world);
		setIslandRespawn(player, world);
		LOGGER.info("[skyisland] {} 的重生点已恢复为空岛 {}", player.getName().getString(), islandSpawn(player));
		LOGGER.info("[skyisland] 触发原因：{}", reason);
	}

	/** 进服时：确保岛存在、岛上有一只空岛村民；每个玩家仅发放一次初始物资（橡树树苗×1 + 骨粉×4）；没有有效重生点时才把玩家放到岛上并设重生点。 */
	public static void ensureIsland(final ServerPlayer player) {
		final MinecraftServer server = serverOf(player);
		if (server == null) {
			return;
		}
		final ServerLevel world = overworld(server);
		if (world == null) {
			return;
		}
		buildIsland(player, world);
		// 岛已存在但村民被杀掉/丢失时也会补齐，保证「每个空岛默认一只」
		ensureIslandVillager(player, world);
		// 空岛上的副本入口石碑（老存档进来时也会补建 / 补齐缺失的中上节）
		Dungeon.ensureEntrance(player, world);
		// 初始物资：每个玩家仅发放一次（用存档数据按 UUID 标记，跨进服 / 重生 / 重启保留）
		final StarterKitData kitData = world.getDataStorage().computeIfAbsent(STARTER_KIT_TYPE);
		if (!kitData.has(player.getUUID())) {
			kitData.mark(player.getUUID());
			giveStarterKit(player);
		}
		// 已有有效锚点（比如上次睡的床还在）就不动他，避免每次进服都把重生点冲掉
		if (!hasValidAnchor(player)) {
			teleportToIsland(player, world);
		}
	}

	/** 注册进服、拆床、死亡、重生四类事件。 */
	public static void register() {
		// 进服：首次生成专属空岛 + 设重生点
		ServerPlayerEvents.JOIN.register(IslandSpawner::ensureIsland);

		// 保险①：床/重生锚被拆掉的那一刻就修好重生点
		PlayerBlockBreakEvents.AFTER.register((world, breaker, pos, state, blockEntity) -> {
			if (world.isClientSide()) {
				return;
			}
			final boolean anchorBlock = state.getBlock() instanceof AbstractBedBlock || state.is(Blocks.RESPAWN_ANCHOR);
			if (!anchorBlock) {
				return;
			}
			final MinecraftServer server = world.getServer();
			if (server == null) {
				return;
			}
			for (final ServerPlayer p : server.getPlayerList().getPlayers()) {
				restoreIslandRespawn(p, "床或重生锚被拆除于 " + pos.toShortString());
			}
		});

		// 保险②：死亡瞬间再修一次 —— 原版随后的重生流程读到的是已修好的空岛点
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayer player) {
				restoreIslandRespawn(player, "死亡时重生锚点失效");
			}
		});

		// 保险③：重生后兜底，前两层都没赶上就直接把人传回空岛
		ServerPlayerEvents.AFTER_RESPAWN.register((player, oldPlayer, alive) -> {
			if (hasValidAnchor(player) || isIslandRespawn(player)) {
				return;
			}
			final ServerLevel world = overworld(serverOf(player));
			if (world == null) {
				return;
			}
			buildIsland(player, world);
			teleportToIsland(player, world);
			LOGGER.info("[skyisland] {} 重生后未落在有效锚点，已传送回空岛", player.getName().getString());
		});
	}
}

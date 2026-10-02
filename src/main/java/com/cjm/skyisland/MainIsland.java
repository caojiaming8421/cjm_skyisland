package com.cjm.skyisland;

import com.cjm.skyisland.entity.CjmVillager;
import com.cjm.skyisland.shop.ShopType;
import com.cjm.skyisland.world.MainIslandConfig;
import com.cjm.skyisland.world.SkyblockConfig;
import com.mojang.logging.LogUtils;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;

/**
 * 主岛：200x200 的浮空主城岛，上面有主城、街道与 8 个专营店铺，每个店铺一名空岛村民。
 *
 * <p>生成时机：服务器启动时一次性生成（{@code SERVER_STARTED}），避免玩家第一次传送时卡住。
 * 中心方块存在即视为已生成，不会重复建造。
 *
 * <p>进出方式：玩家自己的空岛上有一座「主城传送碑」（{@code town_portal}），右键即到主岛；
 * 主城正门外也有同一座碑，右键回自己的空岛。同一个方块按玩家当时在哪来决定方向。
 *
 * <p>店铺村民只在<b>玩家真的在主岛上</b>时才补检 —— 和 1.5.15 修村民重复生成时踩的坑一样：
 * 主岛区块没被真正加载时，{@code getEntitiesOfClass} 扫不到已有村民，会误判成「没人」而重复生成。
 */
public final class MainIsland {
	private static final Logger LOGGER = LogUtils.getLogger();

	/** 店铺定义：类型 + 相对主岛中心的偏移 + 招牌颜色。 */
	private record ShopDef(ShopType type, int offX, int offZ, DyeColor sign) {
	}

	/** 8 个店铺沿十字主干道与环形街道分布，各占一个方位。 */
	private static final List<ShopDef> SHOPS = List.of(
			new ShopDef(ShopType.BLACKSMITH, -45, -45, DyeColor.RED),
			new ShopDef(ShopType.WEAPONS, 45, -45, DyeColor.ORANGE),
			new ShopDef(ShopType.ARMOR, -45, 45, DyeColor.BLUE),
			new ShopDef(ShopType.BUILDER, 45, 45, DyeColor.YELLOW),
			new ShopDef(ShopType.FARMER, 0, -55, DyeColor.GREEN),
			new ShopDef(ShopType.POTION, 0, 55, DyeColor.PURPLE),
			new ShopDef(ShopType.ENCHANTER, -55, 0, DyeColor.CYAN),
			new ShopDef(ShopType.GENERAL, 55, 0, DyeColor.WHITE)
	);

	/** 店铺村民补检间隔（tick）。 */
	private static final int VILLAGER_CHECK_INTERVAL = 40;
	private static int tickCounter = 0;

	private MainIsland() {
	}

	// ==================== 注册 ====================

	public static void register() {
		// 服务器启动时把主岛一次性造好
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			final ServerLevel world = server.getLevel(Level.OVERWORLD);
			if (world != null) {
				ensureBuilt(world);
			}
		});
		// 玩家在主岛上时定期补检店铺村民
		ServerTickEvents.END_SERVER_TICK.register(MainIsland::onEndTick);
	}

	// ==================== 进出 ====================

	/** 玩家此刻是否在主岛范围内（主世界 + XZ 命中）。 */
	public static boolean isOnMainIsland(final ServerPlayer player) {
		if (!player.level().dimension().equals(Level.OVERWORLD)) {
			return false;
		}
		return player.getBlockX() >= MainIslandConfig.MIN_X && player.getBlockX() <= MainIslandConfig.MAX_X
				&& player.getBlockZ() >= MainIslandConfig.MIN_Z && player.getBlockZ() <= MainIslandConfig.MAX_Z;
	}

	/** 右键传送碑：在主岛就回自己的空岛，否则去主岛。 */
	public static void handlePortalUse(final ServerPlayer player) {
		final MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		final ServerLevel world = server.getLevel(Level.OVERWORLD);
		if (world == null) {
			return;
		}
		if (isOnMainIsland(player)) {
			teleportHome(player, world);
		} else {
			teleportToMain(player, world);
		}
	}

	/** 传送到主城正门外的广场。 */
	private static void teleportToMain(final ServerPlayer player, final ServerLevel world) {
		ensureBuilt(world);
		final BlockPos spot = portalPos();
		player.teleportTo(world, spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5,
				Set.of(), 180.0F, 0.0F, false);
		player.sendSystemMessage(Component.literal("你已抵达主岛：主城正门外。右键石碑可回自己的空岛。"));
		LOGGER.info("[skyisland] {} 前往主岛", player.getName().getString());
	}

	/** 回自己的空岛（和副本回程一致：站在岛心上方）。 */
	private static void teleportHome(final ServerPlayer player, final ServerLevel world) {
		final BlockPos spawn = IslandSpawner.islandSpawn(player);
		player.teleportTo(world, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5,
				Set.of(), 0.0F, 0.0F, false);
		player.sendSystemMessage(Component.literal("你已离开主岛，回到自己的空岛。"));
		LOGGER.info("[skyisland] {} 离开主岛回空岛", player.getName().getString());
	}

	/** 主岛上那座回程传送碑的坐标（主城正门外）。 */
	private static BlockPos portalPos() {
		return new BlockPos(MainIslandConfig.CENTER_X, MainIslandConfig.Y + 1,
				MainIslandConfig.CENTER_Z + MainIslandConfig.PORTAL_OFF_Z);
	}

	/** 在玩家自己的空岛上补一座「主城传送碑」（已存在或缺失时补齐）。 */
	public static void ensurePortal(final ServerPlayer player, final ServerLevel world) {
		final BlockPos center = IslandSpawner.islandCenter(player);
		final BlockPos pos = new BlockPos(center.getX() + PORTAL_OFF_X, SkyblockConfig.ISLAND_Y + 1,
				center.getZ() + PORTAL_OFF_Z);
		loadChunkAt(world, pos);
		if (world.getBlockState(pos).is(Cjm_skyisland.TOWN_PORTAL_BLOCK)) {
			return;
		}
		world.setBlockAndUpdate(pos, Cjm_skyisland.TOWN_PORTAL_BLOCK.defaultBlockState());
		LOGGER.info("[skyisland] 已在 {} 的空岛上生成主城传送碑 {}", player.getName().getString(), pos.toShortString());
	}

	/** 玩家空岛上主城传送碑相对岛心的偏移（东北角，避开落点、村民和副本石碑）。 */
	private static final int PORTAL_OFF_X = 4;
	private static final int PORTAL_OFF_Z = 4;

	// ==================== 每 tick：补检店铺村民 ====================

	private static void onEndTick(final MinecraftServer server) {
		tickCounter++;
		if (tickCounter % VILLAGER_CHECK_INTERVAL != 0) {
			return;
		}
		final ServerLevel world = server.getLevel(Level.OVERWORLD);
		if (world == null) {
			return;
		}
		boolean anyOnIsland = false;
		for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isOnMainIsland(player)) {
				anyOnIsland = true;
				break;
			}
		}
		if (!anyOnIsland) {
			return; // 没人在主岛 → 区块没加载 → 扫不到实体，不做任何判定
		}
		ensureVillagers(world);
	}

	// ==================== 生成 ====================

	/** 主岛尚未建造时完整生成一次。 */
	public static void ensureBuilt(final ServerLevel world) {
		loadAllChunks(world);
		final BlockPos center = new BlockPos(MainIslandConfig.CENTER_X, MainIslandConfig.Y, MainIslandConfig.CENTER_Z);
		if (!world.getBlockState(center).isAir()) {
			return; // 已存在
		}
		LOGGER.info("[skyisland] 开始生成主岛 {}x{}，中心 {}", MainIslandConfig.SIZE, MainIslandConfig.SIZE, center.toShortString());
		buildPlatform(world);
		buildWalls(world);
		buildStreets(world);
		buildCastle(world);
		for (final ShopDef shop : SHOPS) {
			buildShop(world, shop);
		}
		world.setBlockAndUpdate(portalPos(), Cjm_skyisland.TOWN_PORTAL_BLOCK.defaultBlockState());
		ensureVillagers(world);
		LOGGER.info("[skyisland] 主岛生成完成");
	}

	/** 200x200 平台：草 / 土 / 石三层。地形量大，用 flags=0 直接写，不触发邻居更新。 */
	private static void buildPlatform(final ServerLevel world) {
		final BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
		final BlockState dirt = Blocks.DIRT.defaultBlockState();
		final BlockState stone = Blocks.STONE.defaultBlockState();
		for (int x = MainIslandConfig.MIN_X; x <= MainIslandConfig.MAX_X; x++) {
			for (int z = MainIslandConfig.MIN_Z; z <= MainIslandConfig.MAX_Z; z++) {
				world.setBlock(new BlockPos(x, MainIslandConfig.Y, z), grass, 0);
				world.setBlock(new BlockPos(x, MainIslandConfig.Y - 1, z), dirt, 0);
				world.setBlock(new BlockPos(x, MainIslandConfig.Y - 2, z), stone, 0);
			}
		}
	}

	/** 四周围墙：石砖，3 格高。 */
	private static void buildWalls(final ServerLevel world) {
		final BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
		for (int x = MainIslandConfig.MIN_X; x <= MainIslandConfig.MAX_X; x++) {
			fillColumn(world, x, MainIslandConfig.MIN_Z, wall);
			fillColumn(world, x, MainIslandConfig.MAX_Z, wall);
		}
		for (int z = MainIslandConfig.MIN_Z; z <= MainIslandConfig.MAX_Z; z++) {
			fillColumn(world, MainIslandConfig.MIN_X, z, wall);
			fillColumn(world, MainIslandConfig.MAX_X, z, wall);
		}
	}

	private static void fillColumn(final ServerLevel world, final int x, final int z, final BlockState state) {
		for (int dy = 1; dy <= MainIslandConfig.WALL_HEIGHT; dy++) {
			world.setBlock(new BlockPos(x, MainIslandConfig.Y + dy, z), state, 0);
		}
	}

	/** 十字主干道 + 一条环形街道，材质为石砖。 */
	private static void buildStreets(final ServerLevel world) {
		final BlockState road = Blocks.STONE_BRICKS.defaultBlockState();
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		for (int x = MainIslandConfig.MIN_X; x <= MainIslandConfig.MAX_X; x++) {
			for (int dz = -MainIslandConfig.STREET_HALF; dz <= MainIslandConfig.STREET_HALF; dz++) {
				world.setBlock(new BlockPos(x, MainIslandConfig.Y, cz + dz), road, 0);
			}
		}
		for (int z = MainIslandConfig.MIN_Z; z <= MainIslandConfig.MAX_Z; z++) {
			for (int dx = -MainIslandConfig.STREET_HALF; dx <= MainIslandConfig.STREET_HALF; dx++) {
				world.setBlock(new BlockPos(cx + dx, MainIslandConfig.Y, z), road, 0);
			}
		}
		final int inner = MainIslandConfig.RING_RADIUS - MainIslandConfig.RING_HALF;
		final int outer = MainIslandConfig.RING_RADIUS + MainIslandConfig.RING_HALF;
		for (int d = inner; d <= outer; d++) {
			for (int t = -outer; t <= outer; t++) {
				for (final int[] pair : new int[][]{{d, t}, {-d, t}, {t, d}, {t, -d}}) {
					world.setBlock(new BlockPos(cx + pair[0], MainIslandConfig.Y, cz + pair[1]), road, 0);
				}
			}
		}
	}

	/** 主城：石砖城堡，四角塔，深色橡木屋顶，内部大厅里站着城主。 */
	private static void buildCastle(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		final int y = MainIslandConfig.Y;
		final int h = MainIslandConfig.CASTLE_HALF;
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		final BlockState plank = Blocks.DARK_OAK_PLANKS.defaultBlockState();

		// 门前广场
		for (int dx = -h - 4; dx <= h + 4; dx++) {
			for (int dz = -h - 4; dz <= h + 4; dz++) {
				world.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), brick);
			}
		}
		// 四面墙
		final int top = y + MainIslandConfig.CASTLE_HEIGHT;
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				final boolean edge = dx == -h || dx == h || dz == -h || dz == h;
				if (!edge) {
					continue;
				}
				for (int dy = 1; dy <= MainIslandConfig.CASTLE_HEIGHT; dy++) {
					world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), brick);
				}
			}
		}
		// 南面正门（dz = +h），中间开 2 宽 3 高
		for (int dx = -1; dx <= 0; dx++) {
			for (int dy = 1; dy <= 3; dy++) {
				world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + h), Blocks.AIR.defaultBlockState());
			}
		}
		// 四角塔
		for (final int[] corner : new int[][]{{-h, -h}, {h, -h}, {-h, h}, {h, h}}) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (int dy = 1; dy <= MainIslandConfig.CASTLE_HEIGHT + MainIslandConfig.CASTLE_TOWER_EXTRA; dy++) {
						world.setBlockAndUpdate(new BlockPos(cx + corner[0] + dx, y + dy, cz + corner[1] + dz), brick);
					}
				}
			}
		}
		// 屋顶
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				world.setBlockAndUpdate(new BlockPos(cx + dx, top + 1, cz + dz), plank);
			}
		}
		// 室内照明：屋顶下挂萤石
		for (final int[] lamp : new int[][]{{-6, -6}, {6, -6}, {-6, 6}, {6, 6}, {0, 0}}) {
			world.setBlockAndUpdate(new BlockPos(cx + lamp[0], top, cz + lamp[1]), Blocks.GLOWSTONE.defaultBlockState());
		}
	}

	/** 单个店铺：13x13 石砖小屋，南面开门，屋内有柜台、招牌、萤石照明。 */
	private static void buildShop(final ServerLevel world, final ShopDef shop) {
		final int cx = MainIslandConfig.CENTER_X + shop.offX();
		final int cz = MainIslandConfig.CENTER_Z + shop.offZ();
		final int y = MainIslandConfig.Y;
		final int h = MainIslandConfig.SHOP_HALF;
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		final BlockState plank = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		final BlockState glass = Blocks.GLASS.defaultBlockState();

		// 地基
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				world.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), brick);
			}
		}
		// 四面墙（南墙中间留 2 宽 3 高的门）
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				final boolean edge = dx == -h || dx == h || dz == -h || dz == h;
				if (!edge) {
					continue;
				}
				final boolean doorway = dz == h && dx >= -1 && dx <= 0;
				for (int dy = 1; dy <= MainIslandConfig.SHOP_HEIGHT; dy++) {
					if (doorway && dy <= 3) {
						continue; // 门洞
					}
					world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), brick);
				}
				// 侧墙开窗
				if ((dx == -h || dx == h) && dz >= -2 && dz <= 2) {
					world.setBlockAndUpdate(new BlockPos(cx + dx, y + 2, cz + dz), glass);
				}
			}
		}
		// 屋顶
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				world.setBlockAndUpdate(new BlockPos(cx + dx, y + MainIslandConfig.SHOP_HEIGHT + 1, cz + dz), plank);
			}
		}
		// 招牌：门楣上一块彩色羊毛
		world.setBlockAndUpdate(new BlockPos(cx, y + 4, cz + h + 1), Blocks.WOOL.pick(shop.sign()).defaultBlockState());
		// 柜台：村民前面一排深色橡木栅栏
		for (int dx = -2; dx <= 2; dx++) {
			world.setBlockAndUpdate(new BlockPos(cx + dx, y + 1, cz - 1), Blocks.DARK_OAK_FENCE.defaultBlockState());
		}
		// 照明
		for (final int[] lamp : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
			world.setBlockAndUpdate(new BlockPos(cx + lamp[0], y + MainIslandConfig.SHOP_HEIGHT, cz + lamp[1]),
					Blocks.GLOWSTONE.defaultBlockState());
		}
	}

	// ==================== 店铺村民 ====================

	/** 保证主城城主与 8 个店铺各有一名村民（只在玩家在主岛上时调用）。 */
	private static void ensureVillagers(final ServerLevel world) {
		spawnIfMissing(world, new BlockPos(MainIslandConfig.CENTER_X, MainIslandConfig.Y + 1, MainIslandConfig.CENTER_Z),
				ShopType.TOWN_HALL);
		for (final ShopDef shop : SHOPS) {
			final BlockPos spot = new BlockPos(MainIslandConfig.CENTER_X + shop.offX(), MainIslandConfig.Y + 1,
					MainIslandConfig.CENTER_Z + shop.offZ() - 3);
			spawnIfMissing(world, spot, shop.type());
		}
	}

	/** 指定位置附近没有该类型的村民时才生成一只（多余的不处理，店铺是固定的）。 */
	private static void spawnIfMissing(final ServerLevel world, final BlockPos spot, final ShopType type) {
		final AABB box = new AABB(spot.getX() - 6, spot.getY() - 4, spot.getZ() - 6,
				spot.getX() + 7, spot.getY() + 6, spot.getZ() + 7);
		for (final CjmVillager existing : world.getEntitiesOfClass(CjmVillager.class, box, e -> e.isAlive())) {
			if (existing.getShopType() == type) {
				return;
			}
		}
		final CjmVillager villager = Cjm_skyisland.CJM_VILLAGER.spawn(world, spot, EntitySpawnReason.EVENT);
		if (villager == null) {
			LOGGER.warn("[skyisland] 主岛村民生成失败：{} @ {}", type.nameZh, spot.toShortString());
			return;
		}
		villager.setShopType(type);
		LOGGER.info("[skyisland] 主岛生成「{}」村民 @ {}", type.nameZh, spot.toShortString());
	}

	// ==================== 区块加载 ====================

	private static void loadAllChunks(final ServerLevel world) {
		for (int sx = SectionPos.blockToSectionCoord(MainIslandConfig.MIN_X);
			 sx <= SectionPos.blockToSectionCoord(MainIslandConfig.MAX_X); sx++) {
			for (int sz = SectionPos.blockToSectionCoord(MainIslandConfig.MIN_Z);
				 sz <= SectionPos.blockToSectionCoord(MainIslandConfig.MAX_Z); sz++) {
				world.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, true);
			}
		}
	}

	private static void loadChunkAt(final ServerLevel world, final BlockPos pos) {
		world.getChunkSource().getChunk(SectionPos.blockToSectionCoord(pos.getX()),
				SectionPos.blockToSectionCoord(pos.getZ()), ChunkStatus.FULL, true);
	}
}

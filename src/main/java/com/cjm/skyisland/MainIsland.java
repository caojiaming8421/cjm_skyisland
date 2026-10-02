package com.cjm.skyisland;

import com.cjm.skyisland.entity.CjmVillager;
import com.cjm.skyisland.shop.ShopType;
import com.cjm.skyisland.world.MainIslandConfig;
import com.cjm.skyisland.world.SkyblockConfig;
import com.mojang.logging.LogUtils;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * <p><b>村民去重（修复 1.6.1 的重叠 bug）</b>：1.6.0 里周期性补检用「小范围 AABB + 按类型计数」，
 * 但 8 个店铺分散在中心四周 ±45~60 格，玩家刚到主岛时远端店铺区块还没加载，
 * {@code getEntitiesOfClass} 扫不到已有村民，于是又补生成一只，表现就是「每种村民都叠了两个」。
 * 现在改成：
 * <ol>
 *   <li>启动时全岛强制加载完再统一扫描，每种类型有重叠就只留一只、缺了就补一只（顺手修旧档的重叠）；</li>
 *   <li>周期性补检只在<b>玩家真的靠近某店铺</b>（该店铺区块已加载、扫描准确）时才对那一只动手，
 *       远端未加载的店铺一律不碰，从根上杜绝「误判没人→重生成」。</li>
 * </ol>
 */
public final class MainIsland {
	private static final Logger LOGGER = LogUtils.getLogger();

	/** 店铺定义：类型 + 相对主岛中心的偏移 + 招牌颜色。 */
	private record ShopDef(ShopType type, int offX, int offZ, DyeColor sign) {
	}

	/** 8 个店铺沿十字主干道与环形街道分布，各占一个方位。 */
	private static final List<ShopDef> SHOPS = List.of(
			new ShopDef(ShopType.BLACKSMITH, -48, -48, DyeColor.RED),
			new ShopDef(ShopType.WEAPONS, 48, -48, DyeColor.ORANGE),
			new ShopDef(ShopType.ARMOR, -48, 48, DyeColor.BLUE),
			new ShopDef(ShopType.BUILDER, 48, 48, DyeColor.YELLOW),
			new ShopDef(ShopType.FARMER, 0, -60, DyeColor.GREEN),
			new ShopDef(ShopType.POTION, 0, 60, DyeColor.PURPLE),
			new ShopDef(ShopType.ENCHANTER, -60, 0, DyeColor.CYAN),
			new ShopDef(ShopType.GENERAL, 60, 0, DyeColor.WHITE)
	);

	/** 店铺村民补检间隔（tick）。 */
	private static final int VILLAGER_CHECK_INTERVAL = 40;
	/** 玩家离店铺多远才允许补检/清理该店铺村民（店铺区块已加载，扫描才准确）。 */
	private static final double HEAL_RADIUS = 44.0D;
	private static final double HEAL_RADIUS_SQR = HEAL_RADIUS * HEAL_RADIUS;
	private static int tickCounter = 0;

	private MainIsland() {
	}

	// ==================== 注册 ====================

	public static void register() {
		// 服务器启动时把主岛一次性造好（含村民去重校正）
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			final ServerLevel world = server.getLevel(Level.OVERWORLD);
			if (world != null) {
				ensureBuilt(world);
			}
		});
		// 玩家在主岛上时定期补检店铺村民（仅靠近的店铺才动手）
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
		// 首次保险：万一主岛还没生成（理论上启动时已生成），这里兜底造一次
		final BlockPos center = new BlockPos(MainIslandConfig.CENTER_X, MainIslandConfig.Y, MainIslandConfig.CENTER_Z);
		if (world.getBlockState(center).isAir()) {
			ensureBuilt(world);
		}
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

	// ==================== 每 tick：靠近玩家时才补检店铺村民 ====================

	private static void onEndTick(final MinecraftServer server) {
		tickCounter++;
		if (tickCounter % VILLAGER_CHECK_INTERVAL != 0) {
			return;
		}
		final ServerLevel world = server.getLevel(Level.OVERWORLD);
		if (world == null) {
			return;
		}
		for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isOnMainIsland(player)) {
				healAroundPlayer(world, player);
			}
		}
	}

	/** 只对玩家附近的店铺/城主动手：区块已加载，扫描准确，不会误判重复生成。 */
	private static void healAroundPlayer(final ServerLevel world, final ServerPlayer player) {
		healIfNear(world, player, ShopType.TOWN_HALL, townHallSpot());
		for (final ShopDef shop : SHOPS) {
			healIfNear(world, player, shop.type(), shopSpot(shop));
		}
	}

	private static void healIfNear(final ServerLevel world, final ServerPlayer player,
			final ShopType type, final BlockPos spot) {
		if (player.distanceToSqr(Vec3.atCenterOf(spot)) > HEAL_RADIUS_SQR) {
			return; // 该店铺区块还没加载，不操作，避免误生成
		}
		heal(world, type, spot);
	}

	// ==================== 生成 ====================

	/** 主岛尚未建造时完整生成一次；无论是否新建造，都做一次村民去重校正（启动时节区块全加载，扫描准确）。 */
	public static void ensureBuilt(final ServerLevel world) {
		loadAllChunks(world);
		final BlockPos center = new BlockPos(MainIslandConfig.CENTER_X, MainIslandConfig.Y, MainIslandConfig.CENTER_Z);
		final boolean firstBuild = world.getBlockState(center).isAir();
		if (firstBuild) {
			LOGGER.info("[skyisland] 开始生成主岛 {}x{}，中心 {}", MainIslandConfig.SIZE, MainIslandConfig.SIZE, center.toShortString());
			buildPlatform(world);
			buildStreets(world);
			buildWalls(world);
			buildCastle(world);
			for (final ShopDef shop : SHOPS) {
				buildShop(world, shop);
			}
			buildHouses(world);
			buildPlaza(world);
			buildWell(world);
			buildStreetLamps(world);
			buildGrove(world);
			world.setBlockAndUpdate(portalPos(), Cjm_skyisland.TOWN_PORTAL_BLOCK.defaultBlockState());
			LOGGER.info("[skyisland] 主岛建筑生成完成");
		}
		// 无论新建造还是旧档（已存在），都校正一次村民（去重叠 + 补缺）
		ensureVillagers(world);
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

	/** 四周围墙：石砖，3 格高 + 城垛；四角塔；四面带铁栅的城门（不破防坠落）。 */
	private static void buildWalls(final ServerLevel world) {
		final BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		final int y = MainIslandConfig.Y;
		final int top = y + MainIslandConfig.WALL_HEIGHT; // 墙顶块所在层
		// 四面墙（实体）
		for (int x = MainIslandConfig.MIN_X; x <= MainIslandConfig.MAX_X; x++) {
			fillColumn(world, x, MainIslandConfig.MIN_Z, wall, top);
			fillColumn(world, x, MainIslandConfig.MAX_Z, wall, top);
		}
		for (int z = MainIslandConfig.MIN_Z; z <= MainIslandConfig.MAX_Z; z++) {
			fillColumn(world, MainIslandConfig.MIN_X, z, wall, top);
			fillColumn(world, MainIslandConfig.MAX_X, z, wall, top);
		}
		// 城垛：墙顶之上加一层，隔一个放一块石砖（齿），形成 crenellation
		for (int x = MainIslandConfig.MIN_X; x <= MainIslandConfig.MAX_X; x++) {
			if ((x - MainIslandConfig.MIN_X) % 2 == 0) {
				set(world, x, top + 1, MainIslandConfig.MIN_Z, wall);
				set(world, x, top + 1, MainIslandConfig.MAX_Z, wall);
			}
		}
		for (int z = MainIslandConfig.MIN_Z; z <= MainIslandConfig.MAX_Z; z++) {
			if ((z - MainIslandConfig.MIN_Z) % 2 == 0) {
				set(world, MainIslandConfig.MIN_X, top + 1, z, wall);
				set(world, MainIslandConfig.MAX_X, top + 1, z, wall);
			}
		}
		// 四角塔（带阶梯金字塔顶 + 灯笼）
		for (final int[] corner : new int[][]{
				{MainIslandConfig.MIN_X, MainIslandConfig.MIN_Z},
				{MainIslandConfig.MAX_X, MainIslandConfig.MIN_Z},
				{MainIslandConfig.MIN_X, MainIslandConfig.MAX_Z},
				{MainIslandConfig.MAX_X, MainIslandConfig.MAX_Z}}) {
			buildCornerTower(world, corner[0], corner[1], top);
		}
		// 四面城门（铁栅 + 门楼），位置在每条边的中点
		buildGate(world, cx, MainIslandConfig.MIN_Z, Direction.NORTH);
		buildGate(world, cx, MainIslandConfig.MAX_Z, Direction.SOUTH);
		buildGate(world, MainIslandConfig.MIN_X, cz, Direction.WEST);
		buildGate(world, MainIslandConfig.MAX_X, cz, Direction.EAST);
	}

	/** 墙角 3x3 塔：石砖，比墙高 3 格，顶部阶梯金字塔 + 灯笼。 */
	private static void buildCornerTower(final ServerLevel world, final int x, final int z, final int wallTop) {
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		final int towerTop = wallTop + 3;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = wallTop + 1; dy <= towerTop; dy++) {
					set(world, x + dx, dy, z + dz, brick);
				}
			}
		}
		// 塔顶阶梯金字塔（石砖），每升一层缩 1 格
		int layer = 0;
		for (int r = 1; r >= 0; r--) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					set(world, x + dx, towerTop + 1 + layer, z + dz, brick);
				}
			}
			layer++;
		}
		set(world, x, towerTop + 1 + layer, z, Blocks.LANTERN.defaultBlockState());
	}

	/** 城门：在围墙中点开 3 宽 2 高的铁栅（不破防），两侧原木门柱，上方石砖门楼。 */
	private static void buildGate(final ServerLevel world, final int gx, final int gz, final Direction facing) {
		final BlockState bars = Blocks.IRON_BARS.defaultBlockState();
		final BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		final int y = MainIslandConfig.Y;
		final int top = y + MainIslandConfig.WALL_HEIGHT;
		// 门洞沿墙展开的方向：南北墙上的门洞沿 X，东西墙上的门洞沿 Z
		final boolean alongX = facing == Direction.NORTH || facing == Direction.SOUTH;
		// 铁栅闸门（3 宽 × 2 高），玩家无法穿过，不会掉出岛
		for (int s = -1; s <= 1; s++) {
			final int px = alongX ? gx + s : gx;
			final int pz = alongX ? gz : gz + s;
			set(world, px, y + 1, pz, bars);
			set(world, px, y + 2, pz, bars);
		}
		// 门洞两侧门柱（原木，3 格高）
		for (final int s : new int[]{-2, 2}) {
			final int px = alongX ? gx + s : gx;
			final int pz = alongX ? gz : gz + s;
			for (int dy = 1; dy <= top; dy++) {
				set(world, px, y + dy, pz, log);
			}
		}
		// 门楼（闸门上方石砖过梁，直到墙顶再封一层）
		for (int s = -1; s <= 1; s++) {
			final int px = alongX ? gx + s : gx;
			final int pz = alongX ? gz : gz + s;
			for (int dy = 3; dy <= top; dy++) {
				set(world, px, y + dy, pz, brick);
			}
			set(world, px, top + 1, pz, brick);
		}
	}

	private static void fillColumn(final ServerLevel world, final int x, final int z, final BlockState state, final int top) {
		for (int dy = 1; dy <= MainIslandConfig.WALL_HEIGHT; dy++) {
			world.setBlock(new BlockPos(x, MainIslandConfig.Y + dy, z), state, 0);
		}
	}

	/** 主城：石砖城堡 + 城垛 + 四角塔（阶梯金字塔顶 + 灯笼）+ 南门铁栅门楼 + 内壁灯笼。 */
	private static void buildCastle(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		final int y = MainIslandConfig.Y;
		final int h = MainIslandConfig.CASTLE_HALF;
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		final BlockState mossy = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();

		// 门前广场（石砖地坪，覆盖 ±(h+4)）
		for (int dx = -h - 4; dx <= h + 4; dx++) {
			for (int dz = -h - 4; dz <= h + 4; dz++) {
				world.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), brick);
			}
		}
		// 四面墙（基座用苔石砖点缀，主体石砖）
		final int top = y + MainIslandConfig.CASTLE_HEIGHT;
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				final boolean edge = dx == -h || dx == h || dz == -h || dz == h;
				if (!edge) {
					continue;
				}
				for (int dy = 1; dy <= MainIslandConfig.CASTLE_HEIGHT; dy++) {
					final BlockState mat = (dy == 1 || dy == 2) ? mossy : brick;
					world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), mat);
				}
			}
		}
		// 城垛：墙顶之上隔块加石砖
		for (int dx = -h; dx <= h; dx++) {
			if ((dx + h) % 2 == 0) {
				set(world, cx + dx, top + 1, cz - h, brick);
				set(world, cx + dx, top + 1, cz + h, brick);
			}
		}
		for (int dz = -h; dz <= h; dz++) {
			if ((dz + h) % 2 == 0) {
				set(world, cx - h, top + 1, cz + dz, brick);
				set(world, cx + h, top + 1, cz + dz, brick);
			}
		}
		// 南面正门（dz = +h），中间开 2 宽 3 高，两侧原木门柱 + 铁栅
		for (int dx = -1; dx <= 0; dx++) {
			for (int dy = 1; dy <= 3; dy++) {
				world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + h), Blocks.AIR.defaultBlockState());
			}
		}
		for (int dx = -2; dx <= 1; dx++) {
			if (dx == -1 || dx == 0) {
				continue; // 门洞
			}
			for (int dy = 1; dy <= MainIslandConfig.CASTLE_HEIGHT; dy++) {
				set(world, cx + dx, y + dy, cz + h, Blocks.DARK_OAK_LOG.defaultBlockState());
			}
		}
		for (int dx = -1; dx <= 0; dx++) {
			set(world, cx + dx, y + 4, cz + h, Blocks.IRON_BARS.defaultBlockState());
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
			int layer = 0;
			for (int r = 1; r >= 0; r--) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						set(world, cx + corner[0] + dx, y + MainIslandConfig.CASTLE_HEIGHT + MainIslandConfig.CASTLE_TOWER_EXTRA + 1 + layer,
								cz + corner[1] + dz, brick);
					}
				}
				layer++;
			}
			set(world, cx + corner[0], y + MainIslandConfig.CASTLE_HEIGHT + MainIslandConfig.CASTLE_TOWER_EXTRA + 1 + layer,
					cz + corner[1], Blocks.LANTERN.defaultBlockState());
		}
		// 室内照明：屋顶下挂灯笼
		for (final int[] lamp : new int[][]{{-6, -6}, {6, -6}, {-6, 6}, {6, 6}, {0, 0}}) {
			world.setBlockAndUpdate(new BlockPos(cx + lamp[0], top, cz + lamp[1]), Blocks.LANTERN.defaultBlockState());
		}
	}

	/** 单个店铺：中世纪木筋墙小屋（山墙屋顶 + 原木框架 + 彩色玻璃 + 烟囱灯笼 + 悬挂招牌）。 */
	private static void buildShop(final ServerLevel world, final ShopDef shop) {
		final int cx = MainIslandConfig.CENTER_X + shop.offX();
		final int cz = MainIslandConfig.CENTER_Z + shop.offZ();
		final int y = MainIslandConfig.Y;
		final int h = MainIslandConfig.SHOP_HALF;
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		final BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
		final BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
		final BlockState plank = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		final BlockState pane = stainedPane(shop.sign());

		// 地基（圆石）
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				world.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), cobble);
			}
		}
		// 墙体：石砖，下两格用圆石打底（木筋墙感），四角原木立柱
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				final boolean edge = dx == -h || dx == h || dz == -h || dz == h;
				if (!edge) {
					continue;
				}
				final boolean corner = (dx == -h || dx == h) && (dz == -h || dz == h);
				for (int dy = 1; dy <= MainIslandConfig.SHOP_HEIGHT; dy++) {
					if (corner) {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), log); // 角柱
					} else if (dy <= 2) {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), cobble); // 下部圆石裙
					} else {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), brick);
					}
				}
				// 南面开门洞（dz = +h，中间 2 宽 3 高），门楣用原木
				final boolean doorway = dz == h && dx >= -1 && dx <= 0;
				if (doorway) {
					for (int dy = 1; dy <= 3; dy++) {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());
					}
					world.setBlockAndUpdate(new BlockPos(cx + dx, y + 4, cz + dz), log);
				}
				// 东西墙开窗（彩色玻璃）
				if ((dx == -h || dx == h) && dz >= -1 && dz <= 1 && !doorway) {
					world.setBlockAndUpdate(new BlockPos(cx + dx, y + 2, cz + dz), pane);
				}
			}
		}
		// 山墙屋顶（暗橡木楼梯，南北向屋脊）
		final int roofBase = y + MainIslandConfig.SHOP_HEIGHT + 1;
		final int rise = 4;
		for (int dz = -h; dz <= h; dz++) {
			for (int dd = 0; dd <= h; dd++) {
				if (dd == h) {
					if (dz > -h && dz < h) { // 屋檐（顶面 slab 出挑）
						set(world, cx + dd, roofBase, cz + dz, slab(Blocks.DARK_OAK_SLAB, SlabType.TOP));
						set(world, cx - dd, roofBase, cz + dz, slab(Blocks.DARK_OAK_SLAB, SlabType.TOP));
					}
					continue;
				}
				final int ry = roofBase + Math.min(h - dd, rise);
				if (dd == 0) {
					if (dz > -h && dz < h) {
						set(world, cx, ry, cz + dz, plank); // 屋脊
					}
					continue;
				}
				set(world, cx + dd, ry, cz + dz, stair(Blocks.DARK_OAK_STAIRS, Direction.EAST));
				set(world, cx - dd, ry, cz + dz, stair(Blocks.DARK_OAK_STAIRS, Direction.WEST));
			}
		}
		// 山墙封三角（z = ±h 两端）：石砖填到屋顶高度
		for (final int end : new int[]{-h, h}) {
			for (int dd = 0; dd <= h; dd++) {
				final int ry = roofBase + Math.min(h - dd, rise);
				for (int yy = roofBase; yy < ry; yy++) {
					set(world, cx + dd, yy, cz + end, brick);
					set(world, cx - dd, yy, cz + end, brick);
				}
			}
		}
		// 烟囱（东侧屋檐外 1 格的砖柱 + 灯笼顶）
		for (int dy = roofBase; dy <= roofBase + 3; dy++) {
			set(world, cx + h, dy, cz, brick);
		}
		set(world, cx + h, roofBase + 4, cz, Blocks.LANTERN.defaultBlockState());
		// 悬挂招牌：门楣上方一块招牌色羊毛 + 原木托
		set(world, cx, y + MainIslandConfig.SHOP_HEIGHT + 2, cz + h + 1, log);
		set(world, cx, y + MainIslandConfig.SHOP_HEIGHT + 3, cz + h + 1, Blocks.WOOL.pick(shop.sign()).defaultBlockState());
		// 内部：柜台（原木栅栏）、货架（木桶）、吊灯
		for (int dx = -2; dx <= 2; dx++) {
			world.setBlockAndUpdate(new BlockPos(cx + dx, y + 1, cz + 1), Blocks.DARK_OAK_FENCE.defaultBlockState());
		}
		for (int dx = -3; dx <= 3; dx += 6) {
			world.setBlockAndUpdate(new BlockPos(cx + dx, y + 1, cz - 1), Blocks.BARREL.defaultBlockState());
		}
		world.setBlockAndUpdate(new BlockPos(cx, y + MainIslandConfig.SHOP_HEIGHT, cz), Blocks.LANTERN.defaultBlockState());
	}

	/** 几栋民居（无村民），丰富街区层次，风格与店铺一致（更小、单色）。 */
	private static void buildHouses(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		// 沿环形街道外侧摆 4 栋小屋
		buildHouse(world, cx - 80, cz - 80);
		buildHouse(world, cx + 80, cz - 80);
		buildHouse(world, cx - 80, cz + 80);
		buildHouse(world, cx + 80, cz + 80);
	}

	/** 一栋简易中世纪民居（9x9，山墙屋顶 + 原木角柱 + 门窗 + 灯笼）。 */
	private static void buildHouse(final ServerLevel world, final int cx, final int cz) {
		final int y = MainIslandConfig.Y;
		final int h = 4;
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		final BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
		final BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
		final BlockState plank = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				world.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), cobble);
			}
		}
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				final boolean edge = dx == -h || dx == h || dz == -h || dz == h;
				if (!edge) {
					continue;
				}
				final boolean corner = (dx == -h || dx == h) && (dz == -h || dz == h);
				final boolean doorway = dz == h && dx == 0;
				for (int dy = 1; dy <= 4; dy++) {
					if (corner) {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), log);
					} else if (dy <= 2) {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), cobble);
					} else {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), brick);
					}
				}
				if (doorway) {
					for (int dy = 1; dy <= 3; dy++) {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());
					}
				} else if ((dx == -h || dx == h) && dz == 0) {
					world.setBlockAndUpdate(new BlockPos(cx + dx, y + 2, cz + dz), Blocks.GLASS_PANE.defaultBlockState());
				}
			}
		}
		final int roofBase = y + 5;
		for (int dz = -h; dz <= h; dz++) {
			for (int dd = 0; dd <= h; dd++) {
				if (dd == h) {
					if (dz > -h && dz < h) {
						set(world, cx + dd, roofBase, cz + dz, slab(Blocks.DARK_OAK_SLAB, SlabType.TOP));
						set(world, cx - dd, roofBase, cz + dz, slab(Blocks.DARK_OAK_SLAB, SlabType.TOP));
					}
					continue;
				}
				final int ry = roofBase + Math.min(h - dd, 4);
				if (dd == 0) {
					if (dz > -h && dz < h) {
						set(world, cx, ry, cz + dz, plank);
					}
					continue;
				}
				set(world, cx + dd, ry, cz + dz, stair(Blocks.DARK_OAK_STAIRS, Direction.EAST));
				set(world, cx - dd, ry, cz + dz, stair(Blocks.DARK_OAK_STAIRS, Direction.WEST));
			}
		}
		for (final int end : new int[]{-h, h}) {
			for (int dd = 0; dd <= h; dd++) {
				final int ry = roofBase + Math.min(h - dd, 4);
				for (int yy = roofBase; yy < ry; yy++) {
					set(world, cx + dd, yy, cz + end, brick);
					set(world, cx - dd, yy, cz + end, brick);
				}
			}
		}
		set(world, cx, roofBase + 5, cz, Blocks.LANTERN.defaultBlockState());
	}

	/** 中央喷泉广场（城堡正门外，传送碑以北）：石砖盆 + 水源 + 中柱。 */
	private static void buildPlaza(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z + 17;
		final int y = MainIslandConfig.Y;
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		// 3x3 盆（边缘抬高 1 格）
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				set(world, cx + dx, y + 1, cz + dz, brick);
			}
		}
		set(world, cx, y, cz, water());
		set(world, cx, y + 1, cz, water());
		// 中柱
		set(world, cx, y + 2, cz, Blocks.DARK_OAK_LOG.defaultBlockState());
		set(world, cx, y + 3, cz, water());
		set(world, cx, y + 4, cz, Blocks.LANTERN.defaultBlockState());
	}

	/** 水井：圆石圈 + 原木井架 + 灯笼。 */
	private static void buildWell(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X - 30;
		final int cz = MainIslandConfig.CENTER_Z - 30;
		final int y = MainIslandConfig.Y;
		final BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					set(world, cx, y, cz, water());
					set(world, cx, y - 1, cz, water());
				} else {
					set(world, cx + dx, y, cz + dz, cobble);
					set(world, cx + dx, y + 1, cz + dz, cobble);
				}
			}
		}
		// 井架
		set(world, cx - 1, y + 2, cz, Blocks.DARK_OAK_LOG.defaultBlockState());
		set(world, cx + 1, y + 2, cz, Blocks.DARK_OAK_LOG.defaultBlockState());
		set(world, cx - 1, y + 3, cz, Blocks.DARK_OAK_LOG.defaultBlockState());
		set(world, cx + 1, y + 3, cz, Blocks.DARK_OAK_LOG.defaultBlockState());
		set(world, cx, y + 4, cz, Blocks.LANTERN.defaultBlockState());
	}

	/** 沿十字主干道摆灯笼柱。 */
	private static void buildStreetLamps(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		final int y = MainIslandConfig.Y;
		for (int d = -80; d <= 80; d += 20) {
			if (d == 0) {
				continue;
			}
			lampPost(world, cx + d, cz + 8);
			lampPost(world, cx + 8, cz + d);
			lampPost(world, cx + d, cz - 8);
			lampPost(world, cx - 8, cz + d);
		}
	}

	private static void lampPost(final ServerLevel world, final int x, final int z) {
		final int y = MainIslandConfig.Y;
		for (int dy = 1; dy <= 3; dy++) {
			set(world, x, y + dy, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		}
		set(world, x, y + 4, z, Blocks.LANTERN.defaultBlockState());
	}

	/** 在空地撒几棵树与花丛，打破单调。 */
	private static void buildGrove(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		final int y = MainIslandConfig.Y;
		final int[][] spots = new int[][]{
				{cx - 70, cz}, {cx + 70, cz}, {cx, cz - 70}, {cx, cz + 70},
				{cx - 40, cz - 40}, {cx + 40, cz + 40}, {cx - 40, cz + 40}, {cx + 40, cz - 40},
				{cx - 85, cz + 20}, {cx + 20, cz - 85}
		};
		for (final int[] s : spots) {
			plantTree(world, s[0], y, s[1]);
		}
		// 花丛点缀（在远离建筑的道路两侧）
		for (int d = -90; d <= 90; d += 15) {
			if (Math.abs(d) < 25) {
				continue;
			}
			set(world, cx + d, y + 1, cz + MainIslandConfig.STREET_HALF + 2, Blocks.DANDELION.defaultBlockState());
			set(world, cx + d, y + 1, cz - MainIslandConfig.STREET_HALF - 2, Blocks.POPPY.defaultBlockState());
			set(world, cx + MainIslandConfig.STREET_HALF + 2, y + 1, cz + d, Blocks.DANDELION.defaultBlockState());
			set(world, cx - MainIslandConfig.STREET_HALF - 2, y + 1, cz + d, Blocks.POPPY.defaultBlockState());
		}
	}

	private static void plantTree(final ServerLevel world, final int x, final int y, final int z) {
		final BlockState log = Blocks.OAK_LOG.defaultBlockState();
		final BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		for (int dy = 1; dy <= 4; dy++) {
			set(world, x, y + dy, z, log);
		}
		for (int dy = 3; dy <= 5; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (Math.abs(dx) == 2 && Math.abs(dz) == 2 && dy < 5) {
						continue; // 削角
					}
					if (dx == 0 && dz == 0 && dy < 5) {
						continue; // 留树干
					}
					set(world, x + dx, y + dy, z + dz, leaves);
				}
			}
		}
		set(world, x, y + 6, z, leaves);
	}

	// ==================== 店铺村民：去重 + 补缺 ====================

	/** 城主坐标（主城正中央）。 */
	private static BlockPos townHallSpot() {
		return new BlockPos(MainIslandConfig.CENTER_X, MainIslandConfig.Y + 1, MainIslandConfig.CENTER_Z);
	}

	/** 店铺村民站在屋内柜台北侧（店内中心）。 */
	private static BlockPos shopSpot(final ShopDef shop) {
		return new BlockPos(MainIslandConfig.CENTER_X + shop.offX(), MainIslandConfig.Y + 1,
				MainIslandConfig.CENTER_Z + shop.offZ());
	}

	/** 全岛统一校正：扫描所有村民按类型计数，重叠只留一只、缺了补一只（启动时调用，区块已全加载，扫描准确）。 */
	private static void ensureVillagers(final ServerLevel world) {
		final AABB islandBox = new AABB(
				MainIslandConfig.MIN_X - 2, MainIslandConfig.Y - 4, MainIslandConfig.MIN_Z - 2,
				MainIslandConfig.MAX_X + 2, MainIslandConfig.Y + 14, MainIslandConfig.MAX_Z + 2);
		final List<CjmVillager> all = world.getEntitiesOfClass(CjmVillager.class, islandBox, e -> e.isAlive());
		final Map<ShopType, List<CjmVillager>> byType = new EnumMap<>(ShopType.class);
		for (final CjmVillager v : all) {
			byType.computeIfAbsent(v.getShopType(), k -> new ArrayList<>()).add(v);
		}
		// 收敛：每种类型只留一只，多余的清除（修旧档重叠）
		int removed = 0;
		for (final List<CjmVillager> list : byType.values()) {
			for (int i = 1; i < list.size(); i++) {
				list.get(i).discard();
				removed++;
			}
		}
		if (removed > 0) {
			LOGGER.info("[skyisland] 主岛校正：清掉 {} 只重叠村民", removed);
		}
		// 缺少的补齐
		heal(world, ShopType.TOWN_HALL, townHallSpot());
		for (final ShopDef shop : SHOPS) {
			heal(world, shop.type(), shopSpot(shop));
		}
	}

	/** 指定类型在指定点附近有重叠就清、缺失就补（区块需已加载以保证扫描准确）。 */
	private static void heal(final ServerLevel world, final ShopType type, final BlockPos spot) {
		final AABB box = new AABB(spot.getX() - 6, spot.getY() - 4, spot.getZ() - 6,
				spot.getX() + 7, spot.getY() + 6, spot.getZ() + 7);
		final List<CjmVillager> found = world.getEntitiesOfClass(CjmVillager.class, box,
				e -> e.isAlive() && e.getShopType() == type);
		for (int i = 1; i < found.size(); i++) {
			found.get(i).discard(); // 重叠的清掉
		}
		if (found.isEmpty()) {
			final CjmVillager villager = Cjm_skyisland.CJM_VILLAGER.spawn(world, spot, EntitySpawnReason.EVENT);
			if (villager == null) {
				LOGGER.warn("[skyisland] 主岛村民生成失败：{} @ {}", type.nameZh, spot.toShortString());
				return;
			}
			villager.setShopType(type);
			LOGGER.info("[skyisland] 主岛生成「{}」村民 @ {}", type.nameZh, spot.toShortString());
		}
	}

	// ==================== 小工具 ====================

	private static void set(final ServerLevel world, final int x, final int y, final int z, final BlockState state) {
		world.setBlockAndUpdate(new BlockPos(x, y, z), state);
	}

	private static BlockState stair(final net.minecraft.world.level.block.Block block, final Direction facing) {
		return block.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, Half.BOTTOM);
	}

	private static BlockState slab(final net.minecraft.world.level.block.Block block, final SlabType type) {
		return block.defaultBlockState().setValue(SlabBlock.TYPE, type);
	}

	private static BlockState water() {
		// 静水（LEVEL=0 的水源），默认状态即是，直接返回即可
		return Blocks.WATER.defaultBlockState();
	}

	/** 招牌颜色 → 染色玻璃板（26.x 中染色玻璃板是 ColorCollection，用 pick 取对应颜色）。 */
	private static BlockState stainedPane(final DyeColor color) {
		return Blocks.STAINED_GLASS_PANE.pick(color).defaultBlockState();
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

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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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

	/** 主岛尚未建造时完整生成一次；若旧档构建版本不匹配则整岛翻新；无论哪种，都做一次村民去重校正。 */
	public static void ensureBuilt(final ServerLevel world) {
		loadAllChunks(world);
		final BlockPos center = new BlockPos(MainIslandConfig.CENTER_X, MainIslandConfig.Y, MainIslandConfig.CENTER_Z);
		final BlockPos marker = markerPos();
		final boolean fresh = world.getBlockState(center).isAir();
		final boolean markerOk = world.getBlockState(marker).getBlock() == buildMarker().getBlock();
		final boolean rebuild = fresh || !markerOk;
		if (rebuild) {
			if (!fresh) {
				LOGGER.info("[skyisland] 主岛构建版本不匹配（旧档），整岛翻新");
				clearIsland(world);
			}
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
			buildStatue(world);
			buildWell(world);
			buildMarket(world);
			buildWindmill(world);
			buildClockTower(world);
			buildGarden(world);
			buildDocks(world);
			buildStreetLamps(world);
			buildFlowerBoxes(world);
			buildTrainingGround(world);
			buildLibrary(world);
			buildTavern(world);
			buildFarmFields(world);
			buildPark(world);
			buildPlazaBenches(world);
			buildBraziers(world);
			buildGrove(world);
			world.setBlockAndUpdate(portalPos(), Cjm_skyisland.TOWN_PORTAL_BLOCK.defaultBlockState());
			LOGGER.info("[skyisland] 主岛建筑生成完成（构建版本 {}）", MainIslandConfig.BUILD_VERSION);
		}
		// 写入/刷新构建版本标记（无论首建还是翻新，保证下次版本升级能触发翻新）
		world.setBlockAndUpdate(marker, buildMarker());
		// 无论新建造还是旧档（已存在），都校正一次村民（去重叠 + 补缺）
		ensureVillagers(world);
	}

	/** 构建版本标记埋在中心正下方，孤立方块，不随平台/翻新被清掉。 */
	private static BlockPos markerPos() {
		return new BlockPos(MainIslandConfig.CENTER_X, MainIslandConfig.Y - 4, MainIslandConfig.CENTER_Z);
	}

	/**
	 * 构建版本标记方块。<b>升级 {@code BUILD_VERSION} 时必须同步更换这里的方块类型</b>
	 * （用另一种石砖变体），否则旧档检测不到版本变化、不会翻新。
	 */
	private static BlockState buildMarker() {
		return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
	}

	/** 整岛清空：清掉平台以上所有建筑（保留 Y-4 的版本标记），供旧档翻新时重建。 */
	private static void clearIsland(final ServerLevel world) {
		final int yTop = MainIslandConfig.Y + 24;
		for (int x = MainIslandConfig.MIN_X; x <= MainIslandConfig.MAX_X; x++) {
			for (int z = MainIslandConfig.MIN_Z; z <= MainIslandConfig.MAX_Z; z++) {
				for (int y = MainIslandConfig.Y - 2; y <= yTop; y++) {
					world.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 0);
				}
			}
		}
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

	/**
	 * 单个店铺：小而精致的中世纪木筋墙小屋（9x9）。
	 * 细节：四角原木立柱 + 下部圆石裙墙 + 中部原木腰线（木筋感）、石阶门廊、东西窗带原木窗框与窗台花盆、
	 * 出挑屋檐、陡山墙、烟囱灯笼、门上方悬挂「原木托 + 招牌色羊毛 + 灯笼」、室内染色地毯 + 精致柜台货架吊灯。
	 */
	private static void buildShop(final ServerLevel world, final ShopDef shop) {
		final int cx = MainIslandConfig.CENTER_X + shop.offX();
		final int cz = MainIslandConfig.CENTER_Z + shop.offZ();
		final int y = MainIslandConfig.Y;
		final int h = MainIslandConfig.SHOP_HALF;
		final int H = MainIslandConfig.SHOP_HEIGHT;
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		final BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
		final BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
		final BlockState plank = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		final BlockState pane = stainedPane(shop.sign());
		final BlockState carpet = Blocks.WOOL.pick(shop.sign()).defaultBlockState();

		// 地基（圆石，四角石砖）
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				final boolean corner = (dx == -h || dx == h) && (dz == -h || dz == h);
				world.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), corner ? brick : cobble);
			}
		}
		// 墙体 + 木筋
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				final boolean edge = dx == -h || dx == h || dz == -h || dz == h;
				if (!edge) {
					continue;
				}
				final boolean corner = (dx == -h || dx == h) && (dz == -h || dz == h);
				final boolean doorway = dz == h && dx >= -1 && dx <= 0; // 南门 2 宽
				final boolean window = (dx == -h || dx == h) && dz == 0; // 东西窗
				for (int dy = 1; dy <= H; dy++) {
					if (doorway && dy <= 3) {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());
						continue;
					}
					if (window && dy == 3) {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), pane); // 彩色玻璃
						continue;
					}
					final BlockState mat;
					if (corner) {
						mat = log; // 角柱
					} else if (window && (dy == 2 || dy == 4)) {
						mat = log; // 窗框上下横木
					} else if (dy <= 2) {
						mat = cobble; // 下部圆石裙墙
					} else if (dy == 3) {
						mat = log; // 木筋腰线（横梁）
					} else {
						mat = brick;
					}
					world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), mat);
				}
				// 南门门楣（dy=4 原木）
				if (doorway) {
					world.setBlockAndUpdate(new BlockPos(cx + dx, y + 4, cz + dz), log);
				}
			}
		}
		// 门口石阶（南门外，抬高一阶）
		for (int dx = -1; dx <= 0; dx++) {
			set(world, cx + dx, y, cz + h + 1, slab(Blocks.STONE_BRICK_SLAB, SlabType.TOP));
		}
		// 窗台花盆（东西窗外）
		set(world, cx - h - 1, y + 1, cz, Blocks.POTTED_DANDELION.defaultBlockState());
		set(world, cx + h + 1, y + 1, cz, Blocks.POTTED_POPPY.defaultBlockState());
		// 屋顶：陡山墙（暗橡木楼梯，南北向屋脊），屋檐 slab 出挑
		final int roofBase = y + H + 1;
		final int rise = h;
		for (int dz = -h; dz <= h; dz++) {
			for (int dd = 0; dd <= h; dd++) {
				if (dd == h) {
					if (dz > -h && dz < h) {
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
		// 山墙封三角（z = ±h 两端）
		for (final int end : new int[]{-h, h}) {
			for (int dd = 0; dd <= h; dd++) {
				final int ry = roofBase + Math.min(h - dd, rise);
				for (int yy = roofBase; yy < ry; yy++) {
					set(world, cx + dd, yy, cz + end, brick);
					set(world, cx - dd, yy, cz + end, brick);
				}
			}
		}
		// 烟囱（东墙外、避开窗的格 + 灯笼顶）
		for (int dy = roofBase; dy <= roofBase + 3; dy++) {
			set(world, cx + h, dy, cz + 3, brick);
		}
		set(world, cx + h, roofBase + 4, cz + 3, Blocks.LANTERN.defaultBlockState());
		// 悬挂招牌：门上方灯笼（照亮门口）+ 原木托 + 招牌色羊毛
		set(world, cx, y + H + 1, cz + h + 1, Blocks.LANTERN.defaultBlockState());
		set(world, cx, y + H + 2, cz + h + 1, log);
		set(world, cx, y + H + 3, cz + h + 1, carpet);
		// 室内：染色地毯 + 精致柜台（栅栏 + 台面）+ 吊灯（货架按职业在 decorateShop 里细化）
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				set(world, cx + dx, y + 1, cz + dz, carpet);
			}
		}
		for (int dx = -2; dx <= 2; dx++) {
			world.setBlockAndUpdate(new BlockPos(cx + dx, y + 1, cz + 2), Blocks.DARK_OAK_FENCE.defaultBlockState());
			world.setBlockAndUpdate(new BlockPos(cx + dx, y + 2, cz + 2), slab(Blocks.DARK_OAK_SLAB, SlabType.BOTTOM));
		}
		set(world, cx, y + H, cz, Blocks.LANTERN.defaultBlockState());
		// 按职业做差异化装修（内饰 + 外观）
		decorateShop(world, shop);
	}

	/** 沿环形街道外侧错落摆 8 栋民居（无村民），四角稍大、四边中点更小，丰富街区层次。 */
	private static void buildHouses(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		buildHouse(world, cx - 82, cz - 82, 4);
		buildHouse(world, cx + 82, cz - 82, 4);
		buildHouse(world, cx - 82, cz + 82, 4);
		buildHouse(world, cx + 82, cz + 82, 4);
		buildHouse(world, cx - 88, cz, 3);
		buildHouse(world, cx + 88, cz, 3);
		buildHouse(world, cx, cz - 88, 3);
		buildHouse(world, cx, cz + 88, 3);
	}

	/** 一栋中世纪民居（半宽 h，山墙屋顶 + 原木角柱 + 木筋腰线 + 门窗 + 灯笼），h 越小越精致。 */
	private static void buildHouse(final ServerLevel world, final int cx, final int cz, final int h) {
		final int y = MainIslandConfig.Y;
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		final BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
		final BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
		final BlockState plank = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		final int H = h;
		// 地基（圆石，四角石砖）
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				final boolean corner = (dx == -h || dx == h) && (dz == -h || dz == h);
				world.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), corner ? brick : cobble);
			}
		}
		// 墙体
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				final boolean edge = dx == -h || dx == h || dz == -h || dz == h;
				if (!edge) {
					continue;
				}
				final boolean corner = (dx == -h || dx == h) && (dz == -h || dz == h);
				final boolean doorway = dz == h && dx == 0;
				final boolean window = (dx == -h || dx == h) && dz == 0;
				for (int dy = 1; dy <= H; dy++) {
					if (doorway && dy <= 3) {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());
						continue;
					}
					if (window && dy == (H / 2 + 1)) {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.GLASS_PANE.defaultBlockState());
						continue;
					}
					final BlockState mat;
					if (corner) {
						mat = log;
					} else if (dy <= 2) {
						mat = cobble;
					} else if (dy == 3) {
						mat = log; // 木筋腰线
					} else {
						mat = brick;
					}
					world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), mat);
				}
			}
		}
		// 屋顶
		final int roofBase = y + H + 1;
		for (int dz = -h; dz <= h; dz++) {
			for (int dd = 0; dd <= h; dd++) {
				if (dd == h) {
					if (dz > -h && dz < h) {
						set(world, cx + dd, roofBase, cz + dz, slab(Blocks.DARK_OAK_SLAB, SlabType.TOP));
						set(world, cx - dd, roofBase, cz + dz, slab(Blocks.DARK_OAK_SLAB, SlabType.TOP));
					}
					continue;
				}
				final int ry = roofBase + Math.min(h - dd, h);
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
				final int ry = roofBase + Math.min(h - dd, h);
				for (int yy = roofBase; yy < ry; yy++) {
					set(world, cx + dd, yy, cz + end, brick);
					set(world, cx - dd, yy, cz + end, brick);
				}
			}
		}
		set(world, cx, roofBase + h, cz, Blocks.LANTERN.defaultBlockState());
	}

	/** 中央广场（城堡正门外，传送碑以北）：干喷泉（无水的石砖盆 + 中心石柱灯笼），避免传送点附近出现水。 */
	private static void buildPlaza(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z + 17;
		final int y = MainIslandConfig.Y;
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		// 3x3 石砖盆（边缘抬高 1 格，盆底铺石砖），中央为干泉眼
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				final boolean edge = dx != 0 || dz != 0;
				set(world, cx + dx, y + 1, cz + dz, brick);
				if (edge) {
					set(world, cx + dx, y, cz + dz, brick); // 盆底
				}
			}
		}
		// 中心石柱 + 灯笼（代替原来的水柱）
		set(world, cx, y + 1, cz, Blocks.DARK_OAK_LOG.defaultBlockState());
		set(world, cx, y + 2, cz, Blocks.LANTERN.defaultBlockState());
		// 四角小花盆点缀
		set(world, cx - 1, y + 1, cz - 1, Blocks.POTTED_DANDELION.defaultBlockState());
		set(world, cx + 1, y + 1, cz - 1, Blocks.POTTED_POPPY.defaultBlockState());
		set(world, cx - 1, y + 1, cz + 1, Blocks.POTTED_BLUE_ORCHID.defaultBlockState());
		set(world, cx + 1, y + 1, cz + 1, Blocks.POTTED_ALLIUM.defaultBlockState());
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

	/** 沿十字主干道摆路灯柱（柱顶加彩色羊毛旗，点缀街区）。 */
	private static void buildStreetLamps(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		final DyeColor[] cols = {DyeColor.RED, DyeColor.YELLOW, DyeColor.LIME, DyeColor.CYAN,
				DyeColor.BLUE, DyeColor.PURPLE, DyeColor.ORANGE, DyeColor.GREEN};
		int i = 0;
		for (int d = -80; d <= 80; d += 20) {
			if (d == 0) {
				continue;
			}
			i = lampPost(world, cx + d, cz + 8, i, cols);
			i = lampPost(world, cx + 8, cz + d, i, cols);
			i = lampPost(world, cx + d, cz - 8, i, cols);
			i = lampPost(world, cx - 8, cz + d, i, cols);
		}
	}

	private static int lampPost(final ServerLevel world, final int x, final int z, int idx, final DyeColor[] cols) {
		final int y = MainIslandConfig.Y;
		for (int dy = 1; dy <= 3; dy++) {
			set(world, x, y + dy, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		}
		set(world, x, y + 4, z, Blocks.LANTERN.defaultBlockState());
		set(world, x, y + 5, z, Blocks.WOOL.pick(cols[idx % cols.length]).defaultBlockState());
		return idx + 1;
	}

	/** 在空地撒几棵树与花丛，打破单调。 */
	private static void buildGrove(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		final int y = MainIslandConfig.Y;
		final int[][] spots = new int[][]{
				{cx - 70, cz}, {cx + 70, cz}, {cx, cz - 70}, {cx, cz + 70},
				{cx - 40, cz - 40}, {cx + 40, cz + 40}, {cx - 40, cz + 40}, {cx + 40, cz - 40},
				{cx - 85, cz + 20}, {cx + 20, cz - 85},
				{cx + 85, cz - 20}, {cx - 20, cz + 85}, {cx + 70, cz + 30}, {cx - 70, cz - 30},
				{cx + 30, cz + 70}, {cx - 30, cz - 70}
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

	/** 中央广场纪念碑：石英底座 + 石英立柱 + 金顶 + 灯笼，纪念空岛纪元。 */
	private static void buildStatue(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z + 30;
		final int y = MainIslandConfig.Y;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				set(world, cx + dx, y, cz + dz, Blocks.QUARTZ_BLOCK.defaultBlockState());
				if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
					set(world, cx + dx, y + 1, cz + dz, Blocks.QUARTZ_BLOCK.defaultBlockState());
				}
			}
		}
		for (int dy = 2; dy <= 6; dy++) {
			set(world, cx, y + dy, cz, Blocks.QUARTZ_PILLAR.defaultBlockState());
		}
		set(world, cx, y + 7, cz, Blocks.GOLD_BLOCK.defaultBlockState());
		set(world, cx, y + 8, cz, Blocks.LANTERN.defaultBlockState());
	}

	/** 市集：城门前广场北侧摆一排带条纹顶棚的木摊（原木柱 + 彩色羊毛顶 + 货架）。 */
	private static void buildMarket(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z - 24;
		final int y = MainIslandConfig.Y;
		final DyeColor[] tops = {DyeColor.RED, DyeColor.YELLOW, DyeColor.LIME, DyeColor.CYAN};
		int i = 0;
		for (int ox = -24; ox <= 24; ox += 16) {
			final int bx = cx + ox;
			final BlockState top = Blocks.WOOL.pick(tops[i % tops.length]).defaultBlockState();
			i++;
			for (final int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
				for (int dy = 1; dy <= 3; dy++) {
					set(world, bx + c[0], y + dy, cz + c[1], Blocks.DARK_OAK_LOG.defaultBlockState());
				}
			}
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					set(world, bx + dx, y + 4, cz + dz, top);
				}
			}
			set(world, bx - 1, y + 1, cz + 2, Blocks.BARREL.defaultBlockState());
			set(world, bx + 1, y + 1, cz + 2, Blocks.CHEST.defaultBlockState());
		}
	}

	/** 风车地标：石砖塔 + 阶梯锥顶 + 顶灯 + 十字木架（四向原木臂）。 */
	private static void buildWindmill(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X - 70;
		final int cz = MainIslandConfig.CENTER_Z - 40;
		final int y = MainIslandConfig.Y;
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = 1; dy <= 10; dy++) {
					set(world, cx + dx, y + dy, cz + dz, brick);
				}
			}
		}
		for (int r = 1; r >= 0; r--) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					set(world, cx + dx, y + 11 + (1 - r), cz + dz, brick);
				}
			}
		}
		set(world, cx, y + 13, cz, Blocks.LANTERN.defaultBlockState());
		final int hub = y + 8;
		for (int a = -4; a <= 4; a++) {
			set(world, cx + a, hub, cz + 2, Blocks.DARK_OAK_LOG.defaultBlockState());
			set(world, cx + a, hub, cz - 2, Blocks.DARK_OAK_LOG.defaultBlockState());
			set(world, cx, hub, cz + 2 + a, Blocks.DARK_OAK_LOG.defaultBlockState());
			set(world, cx, hub, cz - 2 - a, Blocks.DARK_OAK_LOG.defaultBlockState());
		}
	}

	/** 钟楼地标：高石砖塔（中空）+ 顶钟（金块）+ 灯笼。 */
	private static void buildClockTower(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X + 70;
		final int cz = MainIslandConfig.CENTER_Z + 40;
		final int y = MainIslandConfig.Y;
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				final boolean edge = dx == -2 || dx == 2 || dz == -2 || dz == 2;
				final boolean corner = (dx == -2 || dx == 2) && (dz == -2 || dz == 2);
				if (!edge) {
					continue;
				}
				for (int dy = 1; dy <= 12; dy++) {
					set(world, cx + dx, y + dy, cz + dz, corner ? Blocks.DARK_OAK_LOG.defaultBlockState() : brick);
				}
			}
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				set(world, cx + dx, y + 13, cz + dz, brick);
			}
		}
		set(world, cx, y + 12, cz, Blocks.GOLD_BLOCK.defaultBlockState());
		set(world, cx, y + 14, cz, Blocks.LANTERN.defaultBlockState());
	}

	/** 香草园：树篱围合 + 十字石砖小径 + 四角花圃 + 中心日晷（石英柱 + 金块）。 */
	private static void buildGarden(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X + 75;
		final int cz = MainIslandConfig.CENTER_Z - 75;
		final int y = MainIslandConfig.Y;
		final int r = 9;
		final BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		for (int d = -r; d <= r; d++) {
			for (final int[] e : new int[][]{{d, -r}, {d, r}, {-r, d}, {r, d}}) {
				set(world, cx + e[0], y + 1, cz + e[1], leaf);
				set(world, cx + e[0], y + 2, cz + e[1], leaf);
			}
		}
		for (int d = -r + 1; d <= r - 1; d++) {
			set(world, cx + d, y, cz, Blocks.STONE_BRICKS.defaultBlockState());
			set(world, cx, y, cz + d, Blocks.STONE_BRICKS.defaultBlockState());
		}
		final BlockState[] flowers = {Blocks.DANDELION.defaultBlockState(), Blocks.POPPY.defaultBlockState(),
				Blocks.BLUE_ORCHID.defaultBlockState(), Blocks.ALLIUM.defaultBlockState()};
		int fi = 0;
		for (final int[] q : new int[][]{{-6, -6}, {6, -6}, {-6, 6}, {6, 6}}) {
			set(world, cx + q[0], y + 1, cz + q[1], flowers[fi % flowers.length]);
			fi++;
		}
		set(world, cx, y + 1, cz, Blocks.QUARTZ_PILLAR.defaultBlockState());
		set(world, cx, y + 2, cz, Blocks.GOLD_BLOCK.defaultBlockState());
	}

	/** 码头观景台：从北墙根伸出一道木栈道 + 两侧护栏 + 尽头护栏与灯笼，可临边俯瞰虚空。 */
	private static void buildDocks(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z - 94;
		final int y = MainIslandConfig.Y;
		for (int dz = -6; dz <= 2; dz++) {
			for (int dx = -2; dx <= 2; dx++) {
				set(world, cx + dx, y, cz + dz, Blocks.DARK_OAK_PLANKS.defaultBlockState());
			}
			if (dz <= 1 || dz == -6) {
				set(world, cx - 2, y + 1, cz + dz, Blocks.DARK_OAK_FENCE.defaultBlockState());
				set(world, cx + 2, y + 1, cz + dz, Blocks.DARK_OAK_FENCE.defaultBlockState());
			}
		}
		set(world, cx, y + 1, cz - 6, Blocks.LANTERN.defaultBlockState());
	}

	/** 沿主街两侧摆花盆（栽花），点缀街区。 */
	private static void buildFlowerBoxes(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		final int y = MainIslandConfig.Y;
		final BlockState[] pots = {Blocks.POTTED_DANDELION.defaultBlockState(), Blocks.POTTED_POPPY.defaultBlockState(),
				Blocks.POTTED_BLUE_ORCHID.defaultBlockState(), Blocks.POTTED_ALLIUM.defaultBlockState(),
				Blocks.POTTED_RED_TULIP.defaultBlockState(), Blocks.POTTED_ORANGE_TULIP.defaultBlockState()};
		int i = 0;
		for (int d = -70; d <= 70; d += 14) {
			final BlockState p = pots[i++ % pots.length];
			set(world, cx + d, y + 1, cz + MainIslandConfig.STREET_HALF + 1, p);
			set(world, cx + d, y + 1, cz - MainIslandConfig.STREET_HALF - 1, p);
			set(world, cx + MainIslandConfig.STREET_HALF + 1, y + 1, cz + d, p);
			set(world, cx - MainIslandConfig.STREET_HALF - 1, y + 1, cz + d, p);
		}
	}

	// ==================== 店铺：按职业差异化装修 ====================

	/** 按职业给 8 种店铺做不同内饰与外观，让每条街的店都各有特色。 */
	private static void decorateShop(final ServerLevel world, final ShopDef shop) {
		final int cx = MainIslandConfig.CENTER_X + shop.offX();
		final int cz = MainIslandConfig.CENTER_Z + shop.offZ();
		final int y = MainIslandConfig.Y;
		final int h = MainIslandConfig.SHOP_HALF;
		switch (shop.type()) {
			case BLACKSMITH -> { // 铁匠：铁砧 + 点亮熔炉 + 炉火，烟囱顶营火冒烟
				set(world, cx, y + 2, cz - 1, Blocks.ANVIL.defaultBlockState());
				set(world, cx - 3, y + 2, cz - 3, lit(Blocks.FURNACE));
				set(world, cx + 3, y + 2, cz - 3, lit(Blocks.CAMPFIRE));
				set(world, cx + h, y + MainIslandConfig.SHOP_HEIGHT + 5, cz + 3, lit(Blocks.CAMPFIRE));
			}
			case WEAPONS -> { // 武器：标靶 + 铁/金块陈列 + 铁砧 + 门前红旗
				set(world, cx, y + 2, cz - 3, Blocks.TARGET.defaultBlockState());
				set(world, cx - 3, y + 2, cz, Blocks.IRON_BLOCK.defaultBlockState());
				set(world, cx + 3, y + 2, cz, Blocks.GOLD_BLOCK.defaultBlockState());
				set(world, cx, y + 2, cz - 1, Blocks.ANVIL.defaultBlockState());
				flag(world, cx, cz + h + 2, shop.sign());
			}
			case ARMOR -> { // 护甲：铁/金块 + 两侧盔甲箱
				set(world, cx - 3, y + 2, cz - 2, Blocks.IRON_BLOCK.defaultBlockState());
				set(world, cx + 3, y + 2, cz - 2, Blocks.GOLD_BLOCK.defaultBlockState());
				set(world, cx - 3, y + 1, cz - 3, Blocks.CHEST.defaultBlockState());
				set(world, cx + 3, y + 1, cz - 3, Blocks.CHEST.defaultBlockState());
				flag(world, cx, cz + h + 2, shop.sign());
			}
			case BUILDER -> { // 建材：背面材料样品墙 + 工作台 + 两侧桶
				final net.minecraft.world.level.block.Block[] mats = {Blocks.OAK_PLANKS, Blocks.COBBLESTONE,
						Blocks.STONE_BRICKS, Blocks.GLASS_PANE, Blocks.SANDSTONE, Blocks.BRICKS, Blocks.DARK_OAK_PLANKS};
				int mi = 0;
				for (int dx = -3; dx <= 3; dx++) {
					set(world, cx + dx, y + 2, cz - 3, mats[mi++ % mats.length].defaultBlockState());
				}
				set(world, cx, y + 2, cz - 1, Blocks.CRAFTING_TABLE.defaultBlockState());
				set(world, cx - 3, y + 1, cz + 3, Blocks.BARREL.defaultBlockState());
				set(world, cx + 3, y + 1, cz + 3, Blocks.BARREL.defaultBlockState());
			}
			case FARMER -> { // 农夫：干草/南瓜/甜瓜 + 堆肥桶 + 室外菜园
				set(world, cx, y + 2, cz - 1, Blocks.HAY_BLOCK.defaultBlockState());
				set(world, cx - 3, y + 2, cz - 3, Blocks.PUMPKIN.defaultBlockState());
				set(world, cx + 3, y + 2, cz - 3, Blocks.MELON.defaultBlockState());
				set(world, cx, y + 2, cz - 3, Blocks.COMPOSTER.defaultBlockState());
				buildFarmPlot(world, cx, cz + h + 5);
			}
			case POTION -> { // 药水：酿造台 + 锅 + 紫晶/荧石发光陈列
				set(world, cx, y + 2, cz - 1, Blocks.BREWING_STAND.defaultBlockState());
				set(world, cx - 3, y + 2, cz - 3, Blocks.CAULDRON.defaultBlockState());
				set(world, cx + 3, y + 2, cz - 3, Blocks.AMETHYST_BLOCK.defaultBlockState());
				set(world, cx - 3, y + 2, cz + 1, Blocks.GLOWSTONE.defaultBlockState());
				set(world, cx + 3, y + 2, cz + 1, Blocks.GLOWSTONE.defaultBlockState());
				flag(world, cx, cz + h + 2, shop.sign());
			}
			case ENCHANTER -> { // 附魔：附魔台 + 书架阵 + 紫晶发光
				set(world, cx, y + 2, cz - 1, Blocks.ENCHANTING_TABLE.defaultBlockState());
				set(world, cx - 2, y + 2, cz - 2, Blocks.BOOKSHELF.defaultBlockState());
				set(world, cx + 2, y + 2, cz - 2, Blocks.BOOKSHELF.defaultBlockState());
				set(world, cx - 2, y + 2, cz + 1, Blocks.BOOKSHELF.defaultBlockState());
				set(world, cx + 2, y + 2, cz + 1, Blocks.BOOKSHELF.defaultBlockState());
				set(world, cx - 3, y + 2, cz, Blocks.AMETHYST_BLOCK.defaultBlockState());
				set(world, cx + 3, y + 2, cz, Blocks.AMETHYST_BLOCK.defaultBlockState());
			}
			case GENERAL -> { // 杂货：满满储物（多桶多箱）+ 工作台
				set(world, cx - 3, y + 1, cz - 3, Blocks.BARREL.defaultBlockState());
				set(world, cx + 3, y + 1, cz - 3, Blocks.CHEST.defaultBlockState());
				set(world, cx - 3, y + 1, cz + 3, Blocks.CHEST.defaultBlockState());
				set(world, cx + 3, y + 1, cz + 3, Blocks.BARREL.defaultBlockState());
				set(world, cx, y + 2, cz - 2, Blocks.CRAFTING_TABLE.defaultBlockState());
				flag(world, cx, cz + h + 2, shop.sign());
			}
			default -> { }
		}
	}

	// ==================== 主城新增街区（大幅丰富） ====================

	/** 训练场：围栏竞技场 + 四面标靶 + 中央铁砧与铁块武器架。 */
	private static void buildTrainingGround(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z + 40;
		final int y = MainIslandConfig.Y;
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				final boolean edge = Math.abs(dx) == 4 || Math.abs(dz) == 4;
				if (edge) {
					set(world, cx + dx, y + 1, cz + dz, Blocks.DARK_OAK_FENCE.defaultBlockState());
					set(world, cx + dx, y + 2, cz + dz, Blocks.DARK_OAK_FENCE.defaultBlockState());
				}
			}
		}
		set(world, cx, y + 2, cz - 3, Blocks.TARGET.defaultBlockState());
		set(world, cx, y + 2, cz + 3, Blocks.TARGET.defaultBlockState());
		set(world, cx - 3, y + 2, cz, Blocks.TARGET.defaultBlockState());
		set(world, cx + 3, y + 2, cz, Blocks.TARGET.defaultBlockState());
		set(world, cx, y + 2, cz, Blocks.ANVIL.defaultBlockState());
		set(world, cx - 2, y + 2, cz - 2, Blocks.IRON_BLOCK.defaultBlockState());
		set(world, cx + 2, y + 2, cz + 2, Blocks.IRON_BLOCK.defaultBlockState());
	}

	/** 图书馆：石砖小楼，室内三面书架墙 + 中央阅览桌。 */
	private static void buildLibrary(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X - 30;
		final int cz = MainIslandConfig.CENTER_Z - 30;
		final int y = MainIslandConfig.Y;
		final int h = 4;
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				final boolean edge = Math.abs(dx) == h || Math.abs(dz) == h;
				if (!edge) {
					continue;
				}
				for (int dy = 1; dy <= 5; dy++) {
					world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), brick);
				}
				if (dz == h && dx == 0) {
					for (int dy = 1; dy <= 3; dy++) {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());
					}
				}
			}
		}
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				set(world, cx + dx, y + 6, cz + dz, brick);
			}
		}
		set(world, cx, y + 7, cz, Blocks.LANTERN.defaultBlockState());
		for (int dx = -3; dx <= 3; dx++) {
			set(world, cx + dx, y + 2, cz - 3, Blocks.BOOKSHELF.defaultBlockState());
			set(world, cx + dx, y + 3, cz - 3, Blocks.BOOKSHELF.defaultBlockState());
			set(world, cx + dx, y + 2, cz + 3, Blocks.BOOKSHELF.defaultBlockState());
			set(world, cx + dx, y + 3, cz + 3, Blocks.BOOKSHELF.defaultBlockState());
		}
		for (int dz = -2; dz <= 2; dz++) {
			set(world, cx - 3, y + 2, cz + dz, Blocks.BOOKSHELF.defaultBlockState());
			set(world, cx + 3, y + 2, cz + dz, Blocks.BOOKSHELF.defaultBlockState());
		}
		set(world, cx, y + 2, cz - 1, Blocks.CRAFTING_TABLE.defaultBlockState());
	}

	/** 酒馆：石砖小楼，室内木桶酒桌 + 中央石围壁炉（营火）。 */
	private static void buildTavern(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X + 30;
		final int cz = MainIslandConfig.CENTER_Z + 30;
		final int y = MainIslandConfig.Y;
		final int h = 4;
		final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				final boolean edge = Math.abs(dx) == h || Math.abs(dz) == h;
				if (!edge) {
					continue;
				}
				for (int dy = 1; dy <= 5; dy++) {
					world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), brick);
				}
				if (dz == h && dx == 0) {
					for (int dy = 1; dy <= 3; dy++) {
						world.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());
					}
				}
			}
		}
		for (int dx = -h; dx <= h; dx++) {
			for (int dz = -h; dz <= h; dz++) {
				set(world, cx + dx, y + 6, cz + dz, brick);
			}
		}
		set(world, cx, y + 7, cz, Blocks.LANTERN.defaultBlockState());
		set(world, cx - 2, y + 2, cz - 2, Blocks.BARREL.defaultBlockState());
		set(world, cx + 2, y + 2, cz - 2, Blocks.BARREL.defaultBlockState());
		set(world, cx - 2, y + 2, cz + 2, Blocks.BARREL.defaultBlockState());
		set(world, cx + 2, y + 2, cz + 2, Blocks.BARREL.defaultBlockState());
		// 壁炉：营火用圆石四面围住，只冒光不蔓延
		set(world, cx, y + 1, cz - 1, Blocks.COBBLESTONE.defaultBlockState());
		set(world, cx, y + 3, cz - 1, Blocks.COBBLESTONE.defaultBlockState());
		set(world, cx - 1, y + 2, cz - 1, Blocks.COBBLESTONE.defaultBlockState());
		set(world, cx + 1, y + 2, cz - 1, Blocks.COBBLESTONE.defaultBlockState());
		set(world, cx, y + 2, cz - 1, lit(Blocks.CAMPFIRE));
	}

	/** 四块农田：分布在四条主街外延的开阔地，围栏 + 中央水源 + 成熟作物。 */
	private static void buildFarmFields(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		buildFarmPlot(world, cx, cz - 72);
		buildFarmPlot(world, cx, cz + 72);
		buildFarmPlot(world, cx - 72, cz);
		buildFarmPlot(world, cx + 72, cz);
	}

	/** 单块 5x5 农田：橡木围栏 + 中央水源（湿润耕地）+ 四周成熟作物。 */
	private static void buildFarmPlot(final ServerLevel world, final int fx, final int fz) {
		final int y = MainIslandConfig.Y;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				final boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				if (edge) {
					set(world, fx + dx, y + 1, fz + dz, Blocks.OAK_FENCE.defaultBlockState());
					set(world, fx + dx, y + 2, fz + dz, Blocks.OAK_FENCE.defaultBlockState());
				}
			}
		}
		set(world, fx, y, fz, water());
		final BlockState[] c = {crop(Blocks.WHEAT), crop(Blocks.CARROTS), crop(Blocks.POTATOES), crop(Blocks.BEETROOTS)};
		int i = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				set(world, fx + dx, y, fz + dz, Blocks.FARMLAND.defaultBlockState());
				set(world, fx + dx, y + 1, fz + dz, c[i++ % c.length]);
			}
		}
	}

	/** 公园（远东北角）：5x5 睡莲池塘 + 岸边长椅 + 树木，远离传送点。 */
	private static void buildPark(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X + 86;
		final int cz = MainIslandConfig.CENTER_Z - 55;
		final int y = MainIslandConfig.Y;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				set(world, cx + dx, y, cz + dz, water());
				if ((dx + dz) % 2 == 0) {
					set(world, cx + dx, y + 1, cz + dz, Blocks.LILY_PAD.defaultBlockState());
				}
			}
		}
		bench(world, cx - 4, cz - 2);
		bench(world, cx + 4, cz + 2);
		plantTree(world, cx - 5, y, cz + 4);
		plantTree(world, cx + 5, y, cz - 4);
	}

	/** 广场长椅：喷泉广场与纪念碑四周摆几张木 stair 长椅。 */
	private static void buildPlazaBenches(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		bench(world, cx - 4, cz + 14);
		bench(world, cx + 4, cz + 20);
		bench(world, cx - 4, cz + 33);
		bench(world, cx + 4, cz + 27);
	}

	/** 沿途火盆：十字主街外延摆一圈「圆石座 + 点亮营火 + 铁栅笼」的照明火盆。 */
	private static void buildBraziers(final ServerLevel world) {
		final int cx = MainIslandConfig.CENTER_X;
		final int cz = MainIslandConfig.CENTER_Z;
		final int y = MainIslandConfig.Y;
		final int[][] spots = {{40, 0}, {-40, 0}, {0, 40}, {0, -40}, {70, 0}, {-70, 0}, {0, 70}, {0, -70},
				{40, 40}, {-40, -40}, {40, -40}, {-40, 40}};
		for (final int[] s : spots) {
			final int x = cx + s[0];
			final int z = cz + s[1];
			set(world, x, y + 1, z, Blocks.COBBLESTONE.defaultBlockState());
			set(world, x, y + 2, z, lit(Blocks.CAMPFIRE));
			set(world, x - 1, y + 2, z, Blocks.IRON_BARS.defaultBlockState());
			set(world, x + 1, y + 2, z, Blocks.IRON_BARS.defaultBlockState());
			set(world, x, y + 2, z - 1, Blocks.IRON_BARS.defaultBlockState());
			set(world, x, y + 2, z + 1, Blocks.IRON_BARS.defaultBlockState());
			set(world, x, y + 3, z, Blocks.IRON_BARS.defaultBlockState());
		}
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

	/** 点燃方块（熔炉 / 营火），用于炉火与照明。 */
	private static BlockState lit(final net.minecraft.world.level.block.Block block) {
		return block.defaultBlockState().setValue(BlockStateProperties.LIT, true);
	}

	/** 成熟作物（小麦 / 胡萝卜 / 土豆 / 甜菜）。注意：甜菜用 AGE_3，其它作物用 AGE_7，属不同属性实例，不能混用。 */
	private static BlockState crop(final net.minecraft.world.level.block.Block block) {
		if (block == Blocks.BEETROOTS) {
			return block.defaultBlockState().setValue(BlockStateProperties.AGE_3, 3);
		}
		return block.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7);
	}

	/** 门前旗帜：原木杆 + 招牌色羊毛，点缀店铺外观。 */
	private static void flag(final ServerLevel world, final int x, final int z, final DyeColor color) {
		final int y = MainIslandConfig.Y;
		set(world, x, y + 1, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		set(world, x, y + 2, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
		set(world, x, y + 3, z, Blocks.WOOL.pick(color).defaultBlockState());
	}

	/** 长椅：两片相向楼梯，简单的木坐凳。 */
	private static void bench(final ServerLevel world, final int x, final int z) {
		final int y = MainIslandConfig.Y;
		set(world, x, y + 1, z, stair(Blocks.DARK_OAK_STAIRS, Direction.NORTH));
		set(world, x, y + 1, z + 1, stair(Blocks.DARK_OAK_STAIRS, Direction.SOUTH));
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

package com.cjm.skyisland;

import com.cjm.skyisland.world.SkyblockConfig;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;

import java.util.Set;

/**
 * 负责给每个玩家生成专属空岛，并保证「没有重生锚点时必回空岛」。
 *
 * <p>进服时按 UUID 派生固定岛格坐标，在格中心生成 10x10 平台（草/泥/石三层），
 * 把重生点设到岛上并 {@code forced=true} —— 这样玩家没床时死亡一定回到自己的岛，不会掉进虚空。
 *
 * <p>玩家睡床后，原版 {@code startSleepInBed} 会把重生点覆盖成床的位置（forced=false），
 * 因此「有床走床」的行为自然保留。另外挂 {@code AFTER_RESPAWN} 兜底：万一床被拆、重生点失效，
 * 死后会自动拉回空岛。
 *
 * <p>「中心点是否为空气」用于判断岛是否存在：平台方块持久化在存档里，重启后照样在，
 * 只在真正缺失时才重建，避免重复生成。
 */
public final class IslandSpawner {
	private IslandSpawner() {
	}

	/** 按玩家 UUID 派生稳定的空岛中心坐标。 */
	private static BlockPos islandCenter(final ServerPlayer player) {
		final long h = player.getUUID().getMostSignificantBits() ^ player.getUUID().getLeastSignificantBits();
		final int gx = (int) Long.remainderUnsigned(h, 2000);
		final int gz = (int) Long.remainderUnsigned(h >>> 21, 2000);
		final int centerX = gx * SkyblockConfig.GRID + SkyblockConfig.GRID / 2;
		final int centerZ = gz * SkyblockConfig.GRID + SkyblockConfig.GRID / 2;
		return new BlockPos(centerX, SkyblockConfig.ISLAND_Y, centerZ);
	}

	/** 若岛平台缺失则生成（已存在则跳过）。 */
	private static void buildIsland(final ServerPlayer player, final ServerLevel world) {
		final BlockPos center = islandCenter(player);
		if (!world.getBlockState(center).isAir()) {
			return; // 平台已存在
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
	}

	/** 把玩家传送上空岛，并把重生点设为岛中心（forced=true，没床时必回此处）。 */
	private static void teleportToIsland(final ServerPlayer player, final ServerLevel world) {
		final BlockPos center = islandCenter(player);
		final int cx = center.getX();
		final int cz = center.getZ();
		final int y = SkyblockConfig.ISLAND_Y;
		// 重生点设在草方块上方的空气格，原版 forced 逻辑才能在此落脚（站在草上）
		final BlockPos spawn = new BlockPos(cx, y + 1, cz);
		player.setRespawnPosition(new ServerPlayer.RespawnConfig(
				LevelData.RespawnData.of(world.dimension(), spawn, player.getYRot(), player.getXRot()),
				true
		), false);
		player.teleportTo(world, cx + 0.5, y + 2, cz + 0.5, Set.of(), 0.0f, 0.0f, false);
	}

	/** 进服时：确保岛存在并把玩家放到岛上、设好重生点。 */
	public static void ensureIsland(final ServerPlayer player) {
		final ServerLevel world = (ServerLevel) player.level();
		buildIsland(player, world);
		teleportToIsland(player, world);
	}

	/** 玩家是否有有效的重生锚点（床 / 重生锚）。 */
	private static boolean hasValidAnchor(final ServerPlayer player) {
		final ServerPlayer.RespawnConfig rc = player.getRespawnConfig();
		if (rc == null) {
			return false;
		}
		final ResourceKey<Level> dim = rc.respawnData().dimension();
		final ServerLevel level = player.level().getServer().getLevel(dim);
		if (level == null) {
			return false;
		}
		final BlockPos pos = rc.respawnData().pos();
		final BlockState bs = level.getBlockState(pos);
		return bs.getBlock() instanceof AbstractBedBlock || bs.is(Blocks.RESPAWN_ANCHOR);
	}

	/** 注册进服与重生事件。 */
	public static void register() {
		// 进服：首次生成专属空岛 + 设重生点
		ServerPlayerEvents.JOIN.register(IslandSpawner::ensureIsland);
		// 重生兜底：没床时把玩家拉回空岛（覆盖「床被拆导致重生点失效」的情况）
		ServerPlayerEvents.AFTER_RESPAWN.register((player, oldPlayer, alive) -> {
			if (hasValidAnchor(player)) {
				return;
			}
			final ServerLevel world = player.level().getServer().getLevel(Level.OVERWORLD);
			if (world == null) {
				return;
			}
			buildIsland(player, world);
			teleportToIsland(player, world);
		});
	}
}

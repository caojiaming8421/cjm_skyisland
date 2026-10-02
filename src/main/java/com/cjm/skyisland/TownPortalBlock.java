package com.cjm.skyisland;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.server.level.ServerPlayer;

/**
 * 主城传送碑（id: cjm_skyisland:town_portal）。
 *
 * <p>一座带金色传送纹的石碑，玩家自己的空岛上有一座、主城正门外也有一座。
 * 右键时由 {@link MainIsland#handlePortalUse(ServerPlayer)} 判断方向：
 * 人在主岛就回自己的空岛，人在别处就去主岛。
 *
 * <p>是<b>实体方块</b>（可挡视线、可碰撞），和末地传送门那种无碰撞方块不同，
 * 石碑就是要能稳稳立在地上让人右键。挖不动（和基岩同级）。
 */
public class TownPortalBlock extends Block {
	public TownPortalBlock(final BlockBehaviour.Properties properties) {
		super(properties);
	}

	/** 空手右键：触发传送。客户端也返回 SUCCESS，避免在石碑上误放方块。 */
	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos,
											   final Player player, final BlockHitResult hit) {
		if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
			MainIsland.handlePortalUse(serverPlayer);
		}
		return InteractionResult.SUCCESS;
	}

	/** 手持物品右键：同样只触发传送，不在石碑上放置方块。 */
	@Override
	protected InteractionResult useItemOn(final ItemStack stack, final BlockState state, final Level level,
										  final BlockPos pos, final Player player, final InteractionHand hand,
										  final BlockHitResult hit) {
		if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
			MainIsland.handlePortalUse(serverPlayer);
		}
		return InteractionResult.SUCCESS;
	}
}

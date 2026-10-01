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

/**
 * 副本难度入口（硬币祭坛，id: cjm_skyisland:dungeon_core）。
 *
 * <p>实体方块（实心、可右键），放在玩家空岛传送阵正上方 2 格。右键它打开难度选择界面，
 * 选难度并消耗硬币后进入副本。
 *
 * <p>打开 GUI 的逻辑在客户端（{@code Cjm_skyislandClient} 里的 {@code UseBlockCallback}）处理，
 * 这里两个 use 重载都返回 SUCCESS，阻止在祭坛上放置方块，并与客户端拦截保持一致。
 */
public class DungeonCoreBlock extends Block {
	public DungeonCoreBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		return InteractionResult.SUCCESS;
	}
}

package com.cjm.skyisland;

import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

/**
 * 副本入口石碑（id: cjm_skyisland:dungeon_core）。
 *
 * <p>实体方块（实心、可右键），立在玩家空岛地面上，造型为 1×3 高石碑，正面有金币浮雕。
 * 由 {@code section=bottom/middle/top} 三节堆叠而成；右键任意一节打开难度选择界面，
 * 选难度并消耗硬币后立即进入副本。
 *
 * <p>打开 GUI 的逻辑在客户端（{@code Cjm_skyislandClient} 里的 {@code UseBlockCallback}）处理，
 * 这里两个 use 重载都返回 SUCCESS，阻止在石碑上放置方块，并与客户端拦截保持一致。
 */
public class DungeonCoreBlock extends Block {
	/** 石碑的三节：bottom（底座）、middle（碑身，带浮雕）、top（碑顶）。 */
	public enum Section implements StringRepresentable {
		BOTTOM("bottom"),
		MIDDLE("middle"),
		TOP("top");

		private final String name;

		Section(String name) {
			this.name = name;
		}

		@Override
		@NotNull
		public String getSerializedName() {
			return name;
		}
	}

	public static final EnumProperty<Section> SECTION = EnumProperty.create("section", Section.class);

	public DungeonCoreBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(SECTION, Section.BOTTOM));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(SECTION);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(SECTION, Section.BOTTOM);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		// 玩家放置时自动向上补 middle 与 top（需要上方两格是空气）
		if (level.isClientSide()) {
			return;
		}
		BlockPos up1 = pos.above();
		BlockPos up2 = up1.above();
		if (level.isEmptyBlock(up1) && level.isEmptyBlock(up2)) {
			level.setBlock(up1, defaultBlockState().setValue(SECTION, Section.MIDDLE), 3);
			level.setBlock(up2, defaultBlockState().setValue(SECTION, Section.TOP), 3);
		}
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

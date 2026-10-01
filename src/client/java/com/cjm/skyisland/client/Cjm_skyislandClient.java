package com.cjm.skyisland.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import com.cjm.skyisland.Cjm_skyisland;
import com.cjm.skyisland.entity.CjmVillagerRenderer;

public class Cjm_skyislandClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 注册自定义村民的渲染器（id: cjm_skyisland:villager）
		EntityRenderers.register(Cjm_skyisland.CJM_VILLAGER, CjmVillagerRenderer::new);

		// 右键难度入口方块 -> 打开难度选择界面。common 的方块不能依赖 client 类，所以 GUI 在这里拦截打开。
		UseBlockCallback.EVENT.register((Player player, Level level, InteractionHand hand, BlockHitResult hitResult) -> {
			if (level.isClientSide()
					&& level.getBlockState(hitResult.getBlockPos()).is(Cjm_skyisland.DUNGEON_CORE_BLOCK)) {
				Minecraft.getInstance().setScreenAndShow(new DifficultyScreen());
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
	}
}

package com.cjm.skyisland.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

import com.cjm.skyisland.Cjm_skyisland;
import com.cjm.skyisland.entity.CjmVillagerRenderer;

public class Cjm_skyislandClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 注册自定义村民的渲染器（id: cjm_skyisland:villager）
		EntityRenderers.register(Cjm_skyisland.CJM_VILLAGER, CjmVillagerRenderer::new);
	}
}
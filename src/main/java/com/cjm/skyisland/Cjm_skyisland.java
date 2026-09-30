package com.cjm.skyisland;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

import com.cjm.skyisland.world.SkyblockWorldPreset;

public class Cjm_skyisland implements ModInitializer {
	public static final String MOD_ID = "cjm_skyisland";

	@Override
	public void onInitialize() {
		// 注册空岛世界预设（主世界虚空 + 自定义群系）
		SkyblockWorldPreset.register();
		// 玩家进服生成专属空岛；并保证「没床时死亡必回空岛」
		IslandSpawner.register();
	}

	// Makes a new Identifier with the mod's namespace.
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}

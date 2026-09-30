package com.cjm.skyisland;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;

import com.cjm.skyisland.entity.CjmVillager;
import com.cjm.skyisland.world.SkyblockWorldPreset;

public class Cjm_skyisland implements ModInitializer {
	public static final String MOD_ID = "cjm_skyisland";

	/** 自定义村民实体类型（id: cjm_skyisland:villager） */
	public static final EntityType<CjmVillager> CJM_VILLAGER = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		id("villager"),
		EntityType.Builder.of(CjmVillager::new, MobCategory.CREATURE)
			.sized(0.6F, 1.95F)
			.build(ResourceKey.create(Registries.ENTITY_TYPE, id("villager")))
	);

	/** 对应的刷怪蛋（id: cjm_skyisland:villager_spawn_egg） */
	public static final Item CJM_VILLAGER_EGG = Registry.register(
		BuiltInRegistries.ITEM,
		id("villager_spawn_egg"),
		new SpawnEggItem(new Item.Properties().spawnEgg(CJM_VILLAGER))
	);

	/**
	 * 自定义创造模式标签（id: cjm_skyisland:villager_tab）。
	 * 本版本的 fabric-api 里没有 item-group 模块，无法往原版标签追加条目，
	 * 所以直接用原版 CreativeModeTab API 建一个自己的标签来放刷怪蛋。
	 */
	public static final CreativeModeTab CJM_TAB = Registry.register(
		BuiltInRegistries.CREATIVE_MODE_TAB,
		id("villager_tab"),
		CreativeModeTab.builder(CreativeModeTab.Row.TOP, 7)
			.title(Component.translatable("itemGroup.cjm_skyisland.villager"))
			.icon(() -> new ItemStack(CJM_VILLAGER_EGG))
			.displayItems((parameters, output) -> output.accept(CJM_VILLAGER_EGG))
			.build()
	);

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

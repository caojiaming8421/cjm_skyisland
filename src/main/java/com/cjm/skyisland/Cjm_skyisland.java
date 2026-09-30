package com.cjm.skyisland;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

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

	/** 刷怪蛋物品 id：26.x 的 Item.Properties 必须显式 setId，否则构造 Item 时抛 "Item id not set" */
	private static final ResourceKey<Item> CJM_VILLAGER_EGG_ID = ResourceKey.create(Registries.ITEM, id("villager_spawn_egg"));

	/** 对应的刷怪蛋（id: cjm_skyisland:villager_spawn_egg） */
	public static final Item CJM_VILLAGER_EGG = Registry.register(
		BuiltInRegistries.ITEM,
		CJM_VILLAGER_EGG_ID,
		new SpawnEggItem(new Item.Properties().spawnEgg(CJM_VILLAGER).setId(CJM_VILLAGER_EGG_ID))
	);

	/** 硬币物品 id：26.x 的 Item.Properties 必须显式 setId，否则构造 Item 时抛 "Item id not set" */
	private static final ResourceKey<Item> COIN_ID = ResourceKey.create(Registries.ITEM, id("coin"));

	/** 空岛硬币（id: cjm_skyisland:coin）：村民交易产出的通用货币 */
	public static final Item COIN = Registry.register(
		BuiltInRegistries.ITEM,
		COIN_ID,
		new Item(new Item.Properties().setId(COIN_ID))
	);

	/**
	 * 副本传送门方块（id: cjm_skyisland:portal）。
	 *
	 * <p>外观复用原版末地传送门贴图（见 assets 下的 blockstate / model），但本身是普通实心方块：
	 * 玩家站上去触发传送的判定写在 {@link Dungeon} 的每 tick 检测里，不走原版传送门逻辑。
	 * {@code strength(-1, 3600000)} 让它和基岩一样挖不动，配合事件层拦截做到「传送阵不可被破坏」。
	 */
	public static final Block PORTAL_BLOCK = Registry.register(
		BuiltInRegistries.BLOCK,
		id("portal"),
		new Block(BlockBehaviour.Properties.of()
			.strength(-1.0F, 3600000.0F)
			.sound(SoundType.GLASS))
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
			.displayItems((parameters, output) -> {
				output.accept(CJM_VILLAGER_EGG);
				output.accept(COIN);
			})
			.build()
	);

	@Override
	public void onInitialize() {
		// 注册村民的属性（26.x 的 DefaultAttributes 是 ImmutableMap，只能走 Fabric 的属性注册器）
		FabricDefaultAttributeRegistry.register(CJM_VILLAGER, CjmVillager.createAttributes());
		// 注册空岛世界预设（主世界虚空 + 自定义群系）
		SkyblockWorldPreset.register();
		// 玩家进服生成专属空岛；并保证「没床时死亡必回空岛」
		IslandSpawner.register();
		// 副本空岛：传送阵进出、刷怪、奖励箱、规则限制
		Dungeon.register();
	}

	// Makes a new Identifier with the mod's namespace.
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}

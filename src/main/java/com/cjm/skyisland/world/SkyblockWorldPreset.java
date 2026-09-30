package com.cjm.skyisland.world;

import com.cjm.skyisland.Cjm_skyisland;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 注册自定义世界生成相关的 codec。
 *
 * <p>26.x 中 {@code BuiltInRegistries} 不再暴露数据驱动的世界生成注册表
 * （BIOME / DIMENSION_TYPE / WORLD_PRESET 均已改为数据包驱动），但 {@code BIOME_SOURCE} 与
 * {@code CHUNK_GENERATOR} 两个 codec 注册表仍然存在。把自定义生成器 / 群系源 codec 注册进去后，
 * 数据包里的世界预设 JSON（{@code data/cjm_skyisland/worldgen/world_preset/skyblock.json}）
 * 即可通过 {@code "type": "cjm_skyisland:void"} / {@code "type": "cjm_skyisland:void_island"} 引用它们。
 *
 * <p>注：原 Fabric {@code fabric-worldgen-v1} 的 WorldPresets / FabricChunkGenerator 已在 26.3 移除，
 * 故世界预设本身完全由数据包 JSON 提供，Java 侧只需注册 codec。
 */
public class SkyblockWorldPreset {
	public static void register() {
		// 注册自定义 codec（供世界预设 JSON 序列化引用）
		Registry.register(BuiltInRegistries.BIOME_SOURCE, id("void_island"), VoidIslandBiomeSource.CODEC);
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("void"), VoidChunkGenerator.CODEC);
	}

	private static String id(final String path) {
		return Cjm_skyisland.MOD_ID + ":" + path;
	}
}

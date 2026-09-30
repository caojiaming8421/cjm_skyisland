package com.cjm.skyisland.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.List;
import java.util.stream.Stream;

/**
 * 空岛世界的生物群系源。
 *
 * <p>整个主世界被切成 {@link SkyblockConfig#GRID} 大小的「岛格」，每个格子中心
 * {@link SkyblockConfig#ISLAND_HALF} 半径内返回一个确定性随机群系（雪原 / 沙漠 / 丛林 等），
 * 其余世界返回 {@code the_void}（标准天空，无天气）。坐标确定性哈希保证同一岛格永远映射到同一群系。
 *
 * <p>群系列表与天空群系由世界预设 JSON 以 {@code Holder<Biome>} 形式注入（反序列化时从注册表解析），
 * 因此本类不再直接访问 {@code BuiltInRegistries.BIOME}（26.x 中 BIOME 已是数据驱动注册表，未暴露在
 * BuiltInRegistries 上）。
 *
 * <p>注意 BiomeSource 解析群系用的是「四分之一坐标」（quart），需先用 {@link QuartPos#toBlock} 转回方块坐标。
 */
public class VoidIslandBiomeSource extends BiomeSource implements BiomeResolver {
	public static final MapCodec<VoidIslandBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
			i -> i.group(
							Biome.CODEC.listOf().fieldOf("biomes").forGetter(s -> s.islandBiomes),
							Biome.CODEC.fieldOf("sky_biome").forGetter(s -> s.skyBiome)
					)
					.apply(i, VoidIslandBiomeSource::new)
	);

	/** 空岛随机群系白名单（适合空岛生存的环境主题），由 JSON 注入。 */
	private final List<Holder<Biome>> islandBiomes;
	/** 非岛区域的群系（虚空天空）。 */
	private final Holder<Biome> skyBiome;

	public VoidIslandBiomeSource(final List<Holder<Biome>> islandBiomes, final Holder<Biome> skyBiome) {
		this.islandBiomes = islandBiomes;
		this.skyBiome = skyBiome;
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		return CODEC;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return Stream.concat(this.islandBiomes.stream(), Stream.of(this.skyBiome));
	}

	@Override
	public BiomeResolver createResolver(final Climate.Sampler sampler) {
		return this;
	}

	@Override
	public Holder<Biome> getNoiseBiome(final int quartX, final int quartY, final int quartZ) {
		final int x = QuartPos.toBlock(quartX);
		final int z = QuartPos.toBlock(quartZ);
		final int gridX = Math.floorDiv(x, SkyblockConfig.GRID);
		final int gridZ = Math.floorDiv(z, SkyblockConfig.GRID);
		final int centerX = gridX * SkyblockConfig.GRID + SkyblockConfig.GRID / 2;
		final int centerZ = gridZ * SkyblockConfig.GRID + SkyblockConfig.GRID / 2;
		if (Math.abs(x - centerX) <= SkyblockConfig.ISLAND_HALF && Math.abs(z - centerZ) <= SkyblockConfig.ISLAND_HALF) {
			final long seed = hash(gridX, gridZ);
			final int idx = (int) Math.floorMod(seed, this.islandBiomes.size());
			return this.islandBiomes.get(idx);
		}
		return this.skyBiome;
	}

	/** 对 (gridX, gridZ) 做确定性哈希，保证同一个岛格永远映射到同一个群系。 */
	private static long hash(final int a, final int b) {
		long h = 31L * a + b;
		h ^= (h >>> 33);
		h *= 0xff51afd7ed558ccdL;
		h ^= (h >>> 33);
		h *= 0xc4ceb9fe1a85ec53L;
		h ^= (h >>> 33);
		return h;
	}
}

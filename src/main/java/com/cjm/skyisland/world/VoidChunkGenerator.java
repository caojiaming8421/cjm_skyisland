package com.cjm.skyisland.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 虚空世界生成器：不生成任何地形（全虚空），仅承载 {@link VoidIslandBiomeSource} 提供群系。
 *
 * <p>主世界被切成「岛格」，每个潜在岛格中心由 BiomeSource 给出对应群系，其余区域为 the_void。
 * 真正的 10x10 平台由 {@link com.cjm.skyisland.IslandSpawner} 在玩家进服时按坐标放置。
 *
 * <p>26.x 中 fabric-worldgen-v1 已移除，自定义生成器直接实现原版 {@link ChunkGenerator}；
 * 其 codec 必须读取 {@code biome_source} 字段，才能在反序列化时从注册表解析出群系 Holder
 * （{@code MapCodec.unit} 无法拿到注册表，会导致群系永远为空）。
 */
public class VoidChunkGenerator extends ChunkGenerator {
	public static final MapCodec<VoidChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
			i -> i.group(
							BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource)
					)
					.apply(i, VoidChunkGenerator::new)
	);

	public VoidChunkGenerator(final BiomeSource biomeSource) {
		super(biomeSource);
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public void spawnOriginalMobs(final WorldGenRegion region) {
		// 虚空世界不自然生成生物
	}

	@Override
	public int getGenDepth() {
		return 384;
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(
			final ChunkAccess chunk,
			final Blender blender,
			final RandomState randomState,
			final StructureManager structureManager,
			final BiomeManager biomeManager,
			final @Nullable WorldGenRegion carverBiomeRegion,
			final Set<Holder<Biome>> possibleBiomes
	) {
		// 不填充任何方块 -> 虚空
		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public int getSeaLevel() {
		return 63;
	}

	@Override
	public int getMinY() {
		return -64;
	}

	@Override
	public int getBaseHeight(final int x, final int z, final Heightmap.Types type, final LevelHeightAccessor heightAccessor, final RandomState randomState) {
		return heightAccessor.getMinY();
	}

	@Override
	public NoiseColumn getBaseColumn(final int x, final int z, final LevelHeightAccessor heightAccessor, final RandomState randomState) {
		return new NoiseColumn(heightAccessor.getMinY(), new BlockState[0]);
	}

	@Override
	public void addDebugScreenInfo(final List<String> result, final RandomState randomState, final BlockPos feetPos, final SamplerContext samplerContext) {
		result.add("VoidChunkGenerator (cjm_skyisland)");
	}
}

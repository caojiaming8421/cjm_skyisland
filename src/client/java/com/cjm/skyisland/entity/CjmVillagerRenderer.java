package com.cjm.skyisland.entity;

import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;

/**
 * 自定义村民渲染器。
 * 模型骨骼与原版 VillagerModel 完全一致，所以直接复用原版 ModelLayers.VILLAGER 烘焙，
 * 省去自定义模型层注册。
 *
 * 贴图用专属的 cjm_skyisland:textures/entity/cjm_villager.png（冷色调 + 商人帽），
 * 由原版 villager.png 改色而来，UV 布局与原版完全一致，因此可以继续复用 ModelLayers.VILLAGER。
 *
 * 客户端专用。
 */
public class CjmVillagerRenderer extends MobRenderer<CjmVillager, LivingEntityRenderState, CjmVillagerModel> {
	private static final Identifier TEXTURE =
		Identifier.fromNamespaceAndPath("cjm_skyisland", "textures/entity/cjm_villager.png");

	public CjmVillagerRenderer(final EntityRendererProvider.Context context) {
		super(context, new CjmVillagerModel(context.bakeLayer(ModelLayers.VILLAGER)), 0.5F);
	}

	@Override
	public LivingEntityRenderState createRenderState() {
		return new LivingEntityRenderState();
	}

	@Override
	public Identifier getTextureLocation(final LivingEntityRenderState state) {
		return TEXTURE;
	}
}

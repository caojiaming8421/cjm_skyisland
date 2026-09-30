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
 * 贴图先用原版 villager.png（minecraft 命名空间），保证 UV 正确、直接能看；
 * 之后想做专属外观，把 getTextureLocation 换成自己的贴图、并把模型层改为自定义即可。
 *
 * 客户端专用。
 */
public class CjmVillagerRenderer extends MobRenderer<CjmVillager, LivingEntityRenderState, CjmVillagerModel> {
	private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/villager/villager.png");

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

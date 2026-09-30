package com.cjm.skyisland.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * 自定义「空岛村民」生物。
 * 继承自 PathfinderMob，行为上是一个被动、会四处闲逛并看向玩家的生物。
 * 仅在服务端/公共逻辑侧使用，不引用任何客户端（client）包，保证专用服务端也能加载。
 */
public class CjmVillager extends PathfinderMob {
	public CjmVillager(EntityType<? extends CjmVillager> type, Level level) {
		super(type, level);
	}

	/**
	 * 生物属性。26.x 里 {@code DefaultAttributes} 的映射表是 ImmutableMap，mod 生物加不进去，
	 * 必须在主类里用 Fabric 的 {@code FabricDefaultAttributeRegistry} 注册这个 builder，
	 * 否则生物创建时 AttributeSupplier 为 null，直接 NPE。
	 */
	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MOVEMENT_SPEED, 0.5);
	}

	@Override
	protected void registerGoals() {
		// 偶尔看向附近的玩家
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 6.0F));
		// 随机闲逛
		this.goalSelector.addGoal(7, new RandomStrollGoal(this, 1.0D));
	}
}

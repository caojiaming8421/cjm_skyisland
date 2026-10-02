package com.cjm.skyisland.entity;

import com.cjm.skyisland.Cjm_skyisland;
import com.cjm.skyisland.shop.ShopTrades;
import com.cjm.skyisland.shop.ShopType;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 自定义「空岛村民」生物。
 *
 * <p>实现 {@link Merchant} 接口，从而直接复用原版村民交易面板（{@code MerchantMenu}）。
 * 26.x 的 {@code Merchant} 提供了 {@code openTradingScreen()} 默认实现，
 * 内部会自己发 {@code sendMerchantOffers} 同步交易列表到客户端，所以不需要任何 mixin。
 *
 * <p>仅使用服务端/公共逻辑，不引用任何客户端（client）包，保证专用服务端也能加载。
 */
public class CjmVillager extends PathfinderMob implements Merchant {
	/** 交易次数上限。设成极大值＝无限次，木头/石头随时能换硬币，不会被锁死。 */
	private static final int TRADE_MAX_USES = Integer.MAX_VALUE;
	/** 用硬币换树苗 / 种子时，每笔交易消耗的硬币数量。 */
	private static final int COIN_COST = 10;
	/** 交易有效距离的平方（8 格）。超出则判定交易中断、面板自动关闭。 */
	private static final double TRADE_RANGE_SQR = 64.0D;
	/** 村民等级，固定 1（本模组没有升级体系）。 */
	private static final int TRADE_LEVEL = 1;
	/** 同步到客户端的店铺类型 id（见 {@link ShopType}），决定这个村民经营什么。 */
	private static final EntityDataAccessor<String> DATA_SHOP_TYPE =
			SynchedEntityData.defineId(CjmVillager.class, EntityDataSerializers.STRING);
	/** 存档里的字段名。 */
	private static final String TAG_SHOP_TYPE = "ShopType";

	private Player tradingPlayer;
	private MerchantOffers offers;

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

	@Override
	public void tick() {
		super.tick();
		// 交易期间站着不动，否则村民乱走会被判定为超出距离而中断交易
		if (this.isTrading()) {
			this.getNavigation().stop();
		}
	}

	// ==================== 店铺类型 ====================

	@Override
	protected void defineSynchedData(final SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_SHOP_TYPE, ShopType.GENERAL.id);
	}

	@Override
	protected void addAdditionalSaveData(final ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString(TAG_SHOP_TYPE, this.entityData.get(DATA_SHOP_TYPE));
	}

	@Override
	protected void readAdditionalSaveData(final ValueInput input) {
		super.readAdditionalSaveData(input);
		final String id = input.getStringOr(TAG_SHOP_TYPE, ShopType.GENERAL.id);
		this.entityData.set(DATA_SHOP_TYPE, id);
		this.offers = null; // 读档后按类型重建交易表
	}

	/** 这个村民经营的店铺类型。 */
	public ShopType getShopType() {
		return ShopType.fromId(this.entityData.get(DATA_SHOP_TYPE));
	}

	/**
	 * 把村民设为某个店铺的店主：写入类型、挂上店铺名的自定义名、设为不消失。
	 *
	 * <p>店铺村民还会 {@code setNoAi(true)} —— 站柜台不动，既不会乱跑串店，
	 * 也不会走出小屋掉下主岛（交易面板照常能开，右键交互不依赖 AI）。
	 * 玩家空岛上那只「杂货商人」不调用这个方法，保留闲逛行为。
	 */
	public void setShopType(final ShopType type) {
		this.entityData.set(DATA_SHOP_TYPE, type.id);
		this.offers = null;
		this.setCustomName(Component.literal(type.nameZh));
		this.setCustomNameVisible(true);
		this.setPersistenceRequired();
		this.setNoAi(true);
	}

	// ==================== 交易：物品 -> 硬币 ====================

	@Override
	public MerchantOffers getOffers() {
		if (this.offers == null) {
			this.offers = ShopTrades.build(this.getShopType());
		}
		return this.offers;
	}

	@Override
	public void setTradingPlayer(Player player) {
		this.tradingPlayer = player;
	}

	@Override
	public Player getTradingPlayer() {
		return this.tradingPlayer;
	}

	public boolean isTrading() {
		return this.tradingPlayer != null;
	}

	@Override
	public void overrideOffers(MerchantOffers offers) {
		this.offers = offers;
	}

	@Override
	public void notifyTrade(MerchantOffer offer) {
		offer.increaseUses();
		this.playSound(this.getNotifyTradeSound(), this.getSoundVolume(), this.getVoicePitch());
	}

	@Override
	public void notifyTradeUpdated(ItemStack stack) {
		if (!stack.isEmpty()) {
			this.playSound(SoundEvents.VILLAGER_YES, this.getSoundVolume(), this.getVoicePitch());
		}
	}

	@Override
	public int getVillagerXp() {
		return 0;
	}

	@Override
	public void overrideXp(int xp) {
		// 本模组没有村民等级/经验体系，忽略
	}

	@Override
	public boolean showProgressBar() {
		// 没有升级进度条
		return false;
	}

	@Override
	public SoundEvent getNotifyTradeSound() {
		return SoundEvents.VILLAGER_TRADE;
	}

	@Override
	public boolean isClientSide() {
		return this.level().isClientSide();
	}

	@Override
	public boolean stillValid(Player player) {
		return this.tradingPlayer == player && this.isAlive() && this.distanceToSqr(player) <= TRADE_RANGE_SQR;
	}

	// ==================== 右键交互 ====================

	@Override
	public InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (this.isAlive() && !this.isTrading()) {
			if (!this.level().isClientSide()) {
				this.setTradingPlayer(player);
				// Merchant 接口的默认实现：开面板并自动同步交易列表到客户端
				final Component title = this.getDisplayName();
				this.openTradingScreen(player, title, TRADE_LEVEL);
			}
			return InteractionResult.SUCCESS;
		}
		return super.mobInteract(player, hand);
	}
}

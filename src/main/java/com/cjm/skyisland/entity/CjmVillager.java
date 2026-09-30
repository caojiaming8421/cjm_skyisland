package com.cjm.skyisland.entity;

import com.cjm.skyisland.Cjm_skyisland;

import net.minecraft.network.chat.Component;
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
	/** 交易有效距离的平方（8 格）。超出则判定交易中断、面板自动关闭。 */
	private static final double TRADE_RANGE_SQR = 64.0D;
	/** 村民等级，固定 1（本模组没有升级体系）。 */
	private static final int TRADE_LEVEL = 1;

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

	// ==================== 交易：物品 -> 硬币 ====================

	@Override
	public MerchantOffers getOffers() {
		if (this.offers == null) {
			this.offers = createTradeOffers();
		}
		return this.offers;
	}

	/**
	 * 初期交易表：各树种原木（含下界菌柄）以及石头、圆石，每 1 个换 1 枚硬币。
	 * 后续要扩展交易（比如用硬币换工具/食物），在这个方法里继续 addOffer 即可。
	 */
	private static MerchantOffers createTradeOffers() {
		final MerchantOffers offers = new MerchantOffers();
		final ItemStack coin = new ItemStack(Cjm_skyisland.COIN);

		// 主世界各树种原木
		addOffer(offers, Items.OAK_LOG, coin);
		addOffer(offers, Items.SPRUCE_LOG, coin);
		addOffer(offers, Items.BIRCH_LOG, coin);
		addOffer(offers, Items.JUNGLE_LOG, coin);
		addOffer(offers, Items.ACACIA_LOG, coin);
		addOffer(offers, Items.DARK_OAK_LOG, coin);
		addOffer(offers, Items.MANGROVE_LOG, coin);
		addOffer(offers, Items.CHERRY_LOG, coin);
		addOffer(offers, Items.PALE_OAK_LOG, coin);
		addOffer(offers, Items.POPLAR_LOG, coin);
		// 下界菌柄
		addOffer(offers, Items.CRIMSON_STEM, coin);
		addOffer(offers, Items.WARPED_STEM, coin);
		// 石头
		addOffer(offers, Items.STONE, coin);
		addOffer(offers, Items.COBBLESTONE, coin);

		return offers;
	}

	/** 单条交易：{@code 1 个 cost -> 1 个 result}，无限次、0 经验、不随声望涨价。 */
	private static void addOffer(MerchantOffers offers, ItemLike cost, ItemStack result) {
		offers.add(new MerchantOffer(new ItemCost(cost), result.copy(), TRADE_MAX_USES, 0, 0.0F));
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

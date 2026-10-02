package com.cjm.skyisland.shop;

import com.cjm.skyisland.Cjm_skyisland;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;

/**
 * 主岛各店铺的交易表。
 *
 * <p>统一用「空岛硬币」结算：
 * <ul>
 *   <li>{@code buyXxx} = 玩家把物品卖给店铺，换回硬币（收购）；</li>
 *   <li>{@code sellXxx} = 玩家花硬币向店铺买东西（出售）。</li>
 * </ul>
 * 所有交易都是无限次、0 经验、不随声望涨价，和空岛原本的村民交易保持一致。
 */
public final class ShopTrades {
	/** 交易次数上限。设成极大值＝无限次，随时都能买卖，不会被锁死。 */
	private static final int MAX_USES = Integer.MAX_VALUE;

	private ShopTrades() {
	}

	/** 按店铺类型构建交易表。 */
	public static MerchantOffers build(final ShopType type) {
		final MerchantOffers offers = new MerchantOffers();
		switch (type) {
			case TOWN_HALL -> townHall(offers);
			case BLACKSMITH -> blacksmith(offers);
			case WEAPONS -> weapons(offers);
			case ARMOR -> armor(offers);
			case BUILDER -> builder(offers);
			case FARMER -> farmer(offers);
			case POTION -> potion(offers);
			case ENCHANTER -> enchanter(offers);
			default -> general(offers);
		}
		return offers;
	}

	// ==================== 各店铺 ====================

	/** 杂货 / 收购站：原木与石头换硬币（空岛开局的资金入口）+ 最基础的木石工具。 */
	private static void general(final MerchantOffers offers) {
		buy(offers, Items.OAK_LOG, 1);
		buy(offers, Items.SPRUCE_LOG, 1);
		buy(offers, Items.BIRCH_LOG, 1);
		buy(offers, Items.JUNGLE_LOG, 1);
		buy(offers, Items.ACACIA_LOG, 1);
		buy(offers, Items.DARK_OAK_LOG, 1);
		buy(offers, Items.MANGROVE_LOG, 1);
		buy(offers, Items.CHERRY_LOG, 1);
		buy(offers, Items.PALE_OAK_LOG, 1);
		buy(offers, Items.POPLAR_LOG, 1);
		buy(offers, Items.CRIMSON_STEM, 1);
		buy(offers, Items.WARPED_STEM, 1);
		buy(offers, Items.STONE, 1);
		buy(offers, Items.COBBLESTONE, 1);
		buy(offers, Items.COAL, 2);
		buy(offers, Items.IRON_INGOT, 4);
		buy(offers, Items.GOLD_INGOT, 8);
		buy(offers, Items.DIAMOND, 30);
		buy(offers, Items.EMERALD, 10);

		sell(offers, Items.WOODEN_PICKAXE, 2);
		sell(offers, Items.WOODEN_AXE, 2);
		sell(offers, Items.WOODEN_SHOVEL, 2);
		sell(offers, Items.WOODEN_HOE, 2);
		sell(offers, Items.STONE_PICKAXE, 5);
		sell(offers, Items.STONE_AXE, 5);
		sell(offers, Items.STONE_SHOVEL, 5);
		sell(offers, Items.STONE_HOE, 5);
		sell(offers, Items.TORCH, 2, 8);
		sell(offers, Items.BREAD, 2);
		sell(offers, Items.CRAFTING_TABLE, 3);
		sell(offers, Items.FURNACE, 4);
		sell(offers, Items.CHEST, 4);
		// 反向交易（沿用空岛原本的价格：10 硬币换 1 个树苗 / 种子），方便开局补种
		sell(offers, Items.OAK_SAPLING, 10);
		sell(offers, Items.SPRUCE_SAPLING, 10);
		sell(offers, Items.BIRCH_SAPLING, 10);
		sell(offers, Items.JUNGLE_SAPLING, 10);
		sell(offers, Items.ACACIA_SAPLING, 10);
		sell(offers, Items.DARK_OAK_SAPLING, 10);
		sell(offers, Items.MANGROVE_PROPAGULE, 10);
		sell(offers, Items.CHERRY_SAPLING, 10);
		sell(offers, Items.PALE_OAK_SAPLING, 10);
		sell(offers, Items.POPLAR_SAPLING, 10);
		sell(offers, Items.WHEAT_SEEDS, 10);
		sell(offers, Items.MELON_SEEDS, 10);
		sell(offers, Items.PUMPKIN_SEEDS, 10);
		sell(offers, Items.BEETROOT_SEEDS, 10);
		sell(offers, Items.TORCHFLOWER_SEEDS, 10);
		sell(offers, Items.PITCHER_POD, 10);
	}

	/** 主城城主：综合经营，主要是「硬币换树苗 / 种子」这类空岛刚需。 */
	private static void townHall(final MerchantOffers offers) {
		buy(offers, Items.OAK_LOG, 1);
		buy(offers, Items.SPRUCE_LOG, 1);
		buy(offers, Items.BIRCH_LOG, 1);
		buy(offers, Items.JUNGLE_LOG, 1);
		buy(offers, Items.ACACIA_LOG, 1);
		buy(offers, Items.DARK_OAK_LOG, 1);
		buy(offers, Items.STONE, 1);
		buy(offers, Items.COBBLESTONE, 1);
		buy(offers, Items.DIAMOND, 30);

		sell(offers, Items.OAK_SAPLING, 5);
		sell(offers, Items.SPRUCE_SAPLING, 5);
		sell(offers, Items.BIRCH_SAPLING, 5);
		sell(offers, Items.JUNGLE_SAPLING, 5);
		sell(offers, Items.ACACIA_SAPLING, 5);
		sell(offers, Items.DARK_OAK_SAPLING, 5);
		sell(offers, Items.MANGROVE_PROPAGULE, 5);
		sell(offers, Items.CHERRY_SAPLING, 5);
		sell(offers, Items.WHEAT_SEEDS, 2);
		sell(offers, Items.MELON_SEEDS, 2);
		sell(offers, Items.PUMPKIN_SEEDS, 2);
		sell(offers, Items.BEETROOT_SEEDS, 2);
		sell(offers, Blocks.BED.red().asItem(), 10);
		sell(offers, Items.COMPASS, 12);
		sell(offers, Items.CLOCK, 12);
		sell(offers, Items.BUCKET, 6);
	}

	/** 铁匠铺：铁质工具与铁盔甲，收购矿物与燃料。 */
	private static void blacksmith(final MerchantOffers offers) {
		buy(offers, Items.RAW_IRON, 3);
		buy(offers, Items.IRON_INGOT, 4);
		buy(offers, Items.COAL, 2);
		buy(offers, Items.COBBLESTONE, 1);

		sell(offers, Items.IRON_PICKAXE, 15);
		sell(offers, Items.IRON_AXE, 15);
		sell(offers, Items.IRON_SHOVEL, 12);
		sell(offers, Items.IRON_HOE, 12);
		sell(offers, Items.IRON_HELMET, 20);
		sell(offers, Items.IRON_CHESTPLATE, 24);
		sell(offers, Items.IRON_LEGGINGS, 22);
		sell(offers, Items.IRON_BOOTS, 18);
		sell(offers, Items.ANVIL, 40);
		sell(offers, Items.GRINDSTONE, 20);
		sell(offers, Items.FURNACE, 4);
		sell(offers, Items.BLAST_FURNACE, 20);
		sell(offers, Items.BUCKET, 6);
		sell(offers, Items.SHEARS, 8);
		sell(offers, Items.FLINT_AND_STEEL, 8);
	}

	/** 武器店：石 / 铁 / 钻石剑，弓弩箭盾。 */
	private static void weapons(final MerchantOffers offers) {
		buy(offers, Items.FLINT, 1);
		buy(offers, Items.BONE, 1);
		buy(offers, Items.STRING, 1);
		buy(offers, Items.LEATHER, 2);
		buy(offers, Items.FEATHER, 1);

		sell(offers, Items.STONE_SWORD, 5);
		sell(offers, Items.IRON_SWORD, 20);
		sell(offers, Items.DIAMOND_SWORD, 80);
		sell(offers, Items.STONE_AXE, 5);
		sell(offers, Items.IRON_AXE, 18);
		sell(offers, Items.DIAMOND_AXE, 70);
		sell(offers, Items.BOW, 10);
		sell(offers, Items.CROSSBOW, 25);
		sell(offers, Items.ARROW, 3, 8);
		sell(offers, Items.SHIELD, 12);
		sell(offers, Items.TRIDENT, 120);
	}

	/** 护甲店：皮革 / 铁 / 金 / 钻石全套护甲。 */
	private static void armor(final MerchantOffers offers) {
		buy(offers, Items.LEATHER, 2);
		buy(offers, Items.IRON_INGOT, 4);
		buy(offers, Items.GOLD_INGOT, 8);
		buy(offers, Items.DIAMOND, 30);

		sell(offers, Items.LEATHER_HELMET, 8);
		sell(offers, Items.LEATHER_CHESTPLATE, 12);
		sell(offers, Items.LEATHER_LEGGINGS, 10);
		sell(offers, Items.LEATHER_BOOTS, 8);
		sell(offers, Items.IRON_HELMET, 20);
		sell(offers, Items.IRON_CHESTPLATE, 24);
		sell(offers, Items.IRON_LEGGINGS, 22);
		sell(offers, Items.IRON_BOOTS, 18);
		sell(offers, Items.GOLDEN_HELMET, 24);
		sell(offers, Items.GOLDEN_CHESTPLATE, 30);
		sell(offers, Items.DIAMOND_HELMET, 80);
		sell(offers, Items.DIAMOND_CHESTPLATE, 96);
		sell(offers, Items.DIAMOND_LEGGINGS, 88);
		sell(offers, Items.DIAMOND_BOOTS, 72);
		sell(offers, Items.TURTLE_HELMET, 40);
	}

	/** 建材店：木板、石砖、玻璃、羊毛、照明。 */
	private static void builder(final MerchantOffers offers) {
		buy(offers, Items.COBBLESTONE, 1);
		buy(offers, Items.GRAVEL, 1);
		buy(offers, Items.SAND, 1);
		buy(offers, Items.CLAY_BALL, 1);

		sell(offers, Items.OAK_PLANKS, 2, 8);
		sell(offers, Items.SPRUCE_PLANKS, 2, 8);
		sell(offers, Items.BIRCH_PLANKS, 2, 8);
		sell(offers, Items.DARK_OAK_PLANKS, 3, 8);
		sell(offers, Items.OAK_LOG, 2, 8);
		sell(offers, Items.STONE_BRICKS, 3, 8);
		sell(offers, Items.STONE, 2, 8);
		sell(offers, Items.SMOOTH_STONE, 3, 8);
		sell(offers, Items.GLASS, 3, 8);
		sell(offers, Items.GLASS_PANE, 2, 8);
		sell(offers, Blocks.WOOL.white().asItem(), 3, 4);
		sell(offers, Blocks.WOOL.red().asItem(), 3, 4);
		sell(offers, Blocks.WOOL.blue().asItem(), 3, 4);
		sell(offers, Items.TORCH, 2, 8);
		sell(offers, Items.LANTERN, 6);
		sell(offers, Items.LADDER, 2, 4);
		sell(offers, Items.SCAFFOLDING, 2, 8);
		sell(offers, Items.BARREL, 6);
	}

	/** 农夫与食物店：种子、树苗、农作物、熟食。 */
	private static void farmer(final MerchantOffers offers) {
		buy(offers, Items.WHEAT, 1);
		buy(offers, Items.CARROT, 1);
		buy(offers, Items.POTATO, 1);
		buy(offers, Items.BEETROOT, 1);
		buy(offers, Items.PUMPKIN, 2);
		buy(offers, Items.MELON_SLICE, 1);
		buy(offers, Items.APPLE, 2);
		buy(offers, Items.EGG, 1);
		buy(offers, Items.HONEYCOMB, 4);

		sell(offers, Items.WHEAT_SEEDS, 2);
		sell(offers, Items.MELON_SEEDS, 2);
		sell(offers, Items.PUMPKIN_SEEDS, 2);
		sell(offers, Items.BEETROOT_SEEDS, 2);
		sell(offers, Items.OAK_SAPLING, 4);
		sell(offers, Items.SPRUCE_SAPLING, 4);
		sell(offers, Items.BIRCH_SAPLING, 4);
		sell(offers, Items.BREAD, 2);
		sell(offers, Items.COOKED_BEEF, 5);
		sell(offers, Items.COOKED_PORKCHOP, 5);
		sell(offers, Items.COOKED_CHICKEN, 4);
		sell(offers, Items.COOKED_MUTTON, 5);
		sell(offers, Items.CAKE, 15);
		sell(offers, Items.HAY_BLOCK, 2, 4);
		sell(offers, Items.BONE_MEAL, 3, 4);
		sell(offers, Items.COMPOSTER, 8);
	}

	/** 药水店：成品药水与酿造材料。 */
	private static void potion(final MerchantOffers offers) {
		buy(offers, Items.NETHER_WART, 2);
		buy(offers, Items.SPIDER_EYE, 1);
		buy(offers, Items.SUGAR, 1);
		buy(offers, Items.BLAZE_ROD, 5);
		buy(offers, Items.MAGMA_CREAM, 4);
		buy(offers, Items.GHAST_TEAR, 6);
		buy(offers, Items.GOLDEN_CARROT, 4);
		buy(offers, Items.PUFFERFISH, 2);
		buy(offers, Items.RABBIT_FOOT, 4);

		sell(offers, Items.GLASS_BOTTLE, 1, 4);
		sell(offers, Items.BREWING_STAND, 25);
		sell(offers, Items.CAULDRON, 12);
		sell(offers, Items.BLAZE_POWDER, 4, 2);
		sell(offers, Items.GLOWSTONE_DUST, 3, 4);
		sell(offers, Items.REDSTONE, 2, 4);
		sell(offers, Items.FERMENTED_SPIDER_EYE, 4);
		sell(offers, Items.NETHER_WART, 3, 4);
		sell(offers, Items.MILK_BUCKET, 3);

		sellStack(offers, potion(Potions.STRONG_HEALING), 10);
		sellStack(offers, potion(Potions.STRONG_STRENGTH), 12);
		sellStack(offers, potion(Potions.LONG_SWIFTNESS), 10);
		sellStack(offers, potion(Potions.LONG_NIGHT_VISION), 10);
		sellStack(offers, potion(Potions.LONG_FIRE_RESISTANCE), 10);
		sellStack(offers, potion(Potions.LONG_REGENERATION), 12);
		sellStack(offers, potion(Potions.STRONG_LEAPING), 8);
		sellStack(offers, potion(Potions.LONG_WATER_BREATHING), 10);
	}

	/** 附魔店：附魔台、铁砧、砂轮、附魔材料。 */
	private static void enchanter(final MerchantOffers offers) {
		buy(offers, Items.LAPIS_LAZULI, 3);
		buy(offers, Items.BOOK, 2);
		buy(offers, Items.DIAMOND, 30);
		buy(offers, Items.ENDER_PEARL, 6);
		buy(offers, Items.BLAZE_ROD, 5);
		buy(offers, Items.AMETHYST_SHARD, 4);

		sell(offers, Items.ENCHANTING_TABLE, 60);
		sell(offers, Items.ANVIL, 40);
		sell(offers, Items.GRINDSTONE, 20);
		sell(offers, Items.SMITHING_TABLE, 15);
		sell(offers, Items.BOOKSHELF, 10);
		sell(offers, Items.BOOK, 3);
		sell(offers, Items.LAPIS_LAZULI, 6, 4);
		sell(offers, Items.EXPERIENCE_BOTTLE, 10);
		sell(offers, Items.ENDER_CHEST, 80);
		sell(offers, Items.ENCHANTED_BOOK, 30);
	}

	// ==================== 组装辅助 ====================

	/** 收购：玩家给 {@code count} 个 item，换 {@code price} 枚硬币。 */
	private static void buy(final MerchantOffers offers, final ItemLike item, final int price) {
		buy(offers, item, price, 1);
	}

	private static void buy(final MerchantOffers offers, final ItemLike item, final int price, final int count) {
		offers.add(new MerchantOffer(
				new ItemCost(item, count),
				new ItemStack(Cjm_skyisland.COIN, price),
				MAX_USES, 0, 0.0F));
	}

	/** 出售：玩家花 {@code price} 枚硬币，买 1 个 item。 */
	private static void sell(final MerchantOffers offers, final ItemLike item, final int price) {
		sell(offers, item, price, 1);
	}

	private static void sell(final MerchantOffers offers, final ItemLike item, final int price, final int count) {
		offers.add(new MerchantOffer(
				new ItemCost(Cjm_skyisland.COIN, price),
				new ItemStack(item, count),
				MAX_USES, 0, 0.0F));
	}

	/** 出售：已经是完整 ItemStack（比如带药水成分的瓶子）。 */
	private static void sellStack(final MerchantOffers offers, final ItemStack stack, final int price) {
		offers.add(new MerchantOffer(
				new ItemCost(Cjm_skyisland.COIN, price),
				stack,
				MAX_USES, 0, 0.0F));
	}

	/** 造一瓶指定效果的药水（{@code Potions} 里的常量是 {@code Holder<Potion>}）。 */
	private static ItemStack potion(final Holder<Potion> potion) {
		final ItemStack stack = new ItemStack(Items.POTION);
		stack.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withPotion(potion));
		return stack;
	}

	/** 仅供文档/调试：列出某店铺出售的物品数量。 */
	public static int offerCount(final ShopType type) {
		return build(type).size();
	}

	/** 让编译器保留对 Item 的引用（避免误删 import）。 */
	private static Item unused() {
		return Items.POTION;
	}
}

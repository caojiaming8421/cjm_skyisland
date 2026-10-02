package com.cjm.skyisland.shop;

import net.minecraft.network.chat.Component;

/**
 * 主岛上各个店铺的类型（每种对应一名专营的空岛村民）。
 *
 * <p>每个店铺村民只经营自己那一类：既<b>收购</b>对应的原材料（给玩家空岛硬币），
 * 也<b>出售</b>对应的成品（收玩家硬币）。这样「刷副本得到装备、种田得到作物」都能换成硬币，
 * 再用硬币在主岛买想要的专精装备。
 */
public enum ShopType {
	/** 杂货 / 收购站：原木、石头换硬币（空岛开局的资金入口）。 */
	GENERAL("general", "杂货商人"),
	/** 主城城主：综合经营，树苗 / 种子 / 基础物资。 */
	TOWN_HALL("town_hall", "城主"),
	/** 铁匠铺：铁质工具与铁盔甲。 */
	BLACKSMITH("blacksmith", "铁匠"),
	/** 武器店：剑、弓、箭、盾、弩。 */
	WEAPONS("weapons", "武器商"),
	/** 护甲店：皮革 / 铁 / 钻石全套护甲。 */
	ARMOR("armor", "护甲商"),
	/** 建材店：木板、石砖、玻璃、羊毛、照明。 */
	BUILDER("builder", "建材商"),
	/** 农夫与食物店：种子、树苗、农作物、熟食。 */
	FARMER("farmer", "农夫"),
	/** 药水店：成品药水与酿造材料。 */
	POTION("potion", "药剂师"),
	/** 附魔店：附魔台、铁砧、附魔材料。 */
	ENCHANTER("enchanter", "附魔师");

	/** 存进实体 NBT 的 id（改中文名不会让老存档的村民失效）。 */
	public final String id;
	/** 店铺 / 村民显示名（中文）。 */
	public final String nameZh;

	ShopType(final String id, final String nameZh) {
		this.id = id;
		this.nameZh = nameZh;
	}

	/** 村民头顶与交易面板标题显示的名字，如「铁匠」。 */
	public String title() {
		return nameZh;
	}

	/** 交易面板标题，如「铁匠的交易」。 */
	public Component tradeTitle() {
		return Component.literal(nameZh + "·" + "专营");
	}

	/** 按 NBT id 反查类型，未知则返回 {@link #GENERAL}。 */
	public static ShopType fromId(final String id) {
		for (final ShopType type : values()) {
			if (type.id.equals(id)) {
				return type;
			}
		}
		return GENERAL;
	}
}

package com.cjm.skyisland.world;

/**
 * 主岛（空岛纪元的主城岛）的全局常量。
 *
 * <p>主岛是一张 200x200 的浮空人工岛，坐标<b>固定</b>在负坐标深处：
 * <ul>
 *   <li>玩家的专属空岛坐标由 UUID 派生，且 {@code Long.remainderUnsigned} 结果非负，
 *       所以玩家岛一定在正坐标区，主岛取负坐标永不冲突；</li>
 *   <li>副本空岛在 {@code (-99950, -99950)}，主岛在 {@code (-200000, -200000)}，两者相距十万格以上。</li>
 * </ul>
 *
 * <p>整座岛都落在 {@code the_void} 群系上（{@link VoidIslandBiomeSource} 只在岛格中心附近返回普通群系），
 * 所以主岛不会自然刷怪，夜里也安全。
 */
public final class MainIslandConfig {
	/** 主岛边长（200x200）。 */
	public static final int SIZE = 200;
	/** 半边长。 */
	public static final int HALF = SIZE / 2;
	/** 主岛中心 X。负坐标，远离所有玩家空岛与副本空岛。 */
	public static final int CENTER_X = -200000;
	/** 主岛中心 Z。 */
	public static final int CENTER_Z = -200000;
	/** 地面（草方块）所在高度，玩家站在 Y+1。 */
	public static final int Y = 100;
	/** 四周围墙高度（3 格，防止玩家和村民掉下去）。 */
	public static final int WALL_HEIGHT = 3;

	/** X 最小边界（含）。 */
	public static final int MIN_X = CENTER_X - HALF;
	/** X 最大边界（含）。 */
	public static final int MAX_X = CENTER_X + HALF - 1;
	/** Z 最小边界（含）。 */
	public static final int MIN_Z = CENTER_Z - HALF;
	/** Z 最大边界（含）。 */
	public static final int MAX_Z = CENTER_Z + HALF - 1;

	/** 十字主干道的半宽（总宽 5）。 */
	public static final int STREET_HALF = 2;
	/** 环形街道的中心半径。 */
	public static final int RING_RADIUS = 55;
	/** 环形街道的半宽（总宽 7）。 */
	public static final int RING_HALF = 3;

	/** 主城（城堡）的半边长（28x28）。 */
	public static final int CASTLE_HALF = 14;
	/** 主城城墙高度。 */
	public static final int CASTLE_HEIGHT = 6;
	/** 主城四角塔的额外高度。 */
	public static final int CASTLE_TOWER_EXTRA = 3;

	/** 店铺的半边长（9x9，小而精致）。 */
	public static final int SHOP_HALF = 4;
	/** 店铺层高。 */
	public static final int SHOP_HEIGHT = 5;

	/**
	 * 主岛「构建版本」。每次主岛结构大改就 +1，并在中心正下方埋一块
	 * 标记方块作为版本号（当前为 {@code END_STONE_BRICKS}）。玩家旧档进服时若检测到
	 * 标记版本不匹配，主岛会整体翻新（先清空再重建），保证每次更新都能看到最新的主岛外观。
	 */
	public static final int BUILD_VERSION = 5;

	/** 主城正门外的传送碑位置（相对中心）：dz 为正＝南侧。 */
	public static final int PORTAL_OFF_Z = 20;

	private MainIslandConfig() {
	}
}

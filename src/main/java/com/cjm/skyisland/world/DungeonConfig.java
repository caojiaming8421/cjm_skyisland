package com.cjm.skyisland.world;

/**
 * 副本空岛（打怪副本）的全局常量。
 *
 * <p>副本是一张 50x50 的浮空石台，位置<b>固定</b>在世界的一个负坐标区块上：
 * <ul>
 *   <li>玩家空岛的中心坐标全部由 {@code UUID} 派生，且 {@code Long.remainderUnsigned} 结果非负，
 *       所以副本用负坐标可以保证永远不会和任何玩家的空岛重叠。</li>
 *   <li>坐标还刻意<b>避开岛格中心</b>：{@link VoidIslandBiomeSource} 只在岛格中心
 *       {@code ±ISLAND_HALF} 范围内返回普通群系，其余一律返回 {@code the_void}。
 *       副本落在 the_void 群系上，就不会有原版自然刷怪来破坏「固定 100 只」的数量。</li>
 * </ul>
 */
public final class DungeonConfig {
	/** 副本平台边长（50x50）。 */
	public static final int SIZE = 50;
	/** 半边长。 */
	public static final int HALF = SIZE / 2;
	/**
	 * 副本中心 X。取负坐标远离所有玩家空岛，且相对最近的岛格中心（-100000）偏移 50 格，
	 * 确保整张图都落在 the_void 群系内。
	 */
	public static final int CENTER_X = -99950;
	/** 副本中心 Z（同 {@link #CENTER_X} 的取法）。 */
	public static final int CENTER_Z = -99950;
	/** 副本平台所在高度。 */
	public static final int Y = 70;
	/** 围墙高度（3 格，防止玩家和怪物掉出平台）。 */
	public static final int WALL_HEIGHT = 3;
	/** 每次开启固定刷新的敌对生物数量。 */
	public static final int MOB_COUNT = 100;
	/** 奖励箱数量。 */
	public static final int CHEST_COUNT = 4;
	/** 进入冷却：30 分钟（同一玩家半小时内只能进一次）。 */
	public static final long COOLDOWN_MS = 30L * 60L * 1000L;
	/** 最短停留：5 分钟（满 5 分钟后中央传送阵才生效）。 */
	public static final long MIN_STAY_MS = 5L * 60L * 1000L;
	/** 最长停留：20 分钟（超时自动死亡）。 */
	public static final long MAX_STAY_MS = 20L * 60L * 1000L;

	/** X 最小边界（含）。 */
	public static final int MIN_X = CENTER_X - HALF;
	/** X 最大边界（含）。 */
	public static final int MAX_X = CENTER_X + HALF - 1;
	/** Z 最小边界（含）。 */
	public static final int MIN_Z = CENTER_Z - HALF;
	/** Z 最大边界（含）。 */
	public static final int MAX_Z = CENTER_Z + HALF - 1;

	private DungeonConfig() {
	}
}

package com.cjm.skyisland.world;

/** 空岛世界的全局常量。BiomeSource 与 IslandSpawner 共用，保证「岛格坐标」两侧一致。 */
public final class SkyblockConfig {
    /** 空岛平台半边长（方块）。10x10 平台即中心 ±5。 */
    public static final int ISLAND_HALF = 5;
    /** 相邻潜在岛格中心的间距（方块）。留大间距避免不同玩家的岛连在一起。 */
    public static final int GRID = 320;
    /** 空岛所在的高度。 */
    public static final int ISLAND_Y = 64;

    private SkyblockConfig() {
    }
}

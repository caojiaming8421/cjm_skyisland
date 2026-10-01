package com.cjm.skyisland.client;

import com.cjm.skyisland.Cjm_skyisland;
import com.cjm.skyisland.network.SelectDifficultyC2S;
import com.cjm.skyisland.world.DungeonConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 副本难度选择界面（客户端）。
 *
 * <p>列出三档难度，展示各自消耗的硬币、怪物数量与奖励装备材质。点击任意按钮即把所选难度
 * 通过 {@link SelectDifficultyC2S} 发给服务端，由服务端校验硬币、扣费并把玩家送入副本。
 */
public class DifficultyScreen extends Screen {
	private static final int BUTTON_W = 260;
	private static final int BUTTON_H = 28;

	public DifficultyScreen() {
		super(Component.literal("副本难度选择"));
	}

	@Override
	protected void init() {
		final int cx = this.width / 2;
		final int startY = this.height / 2 - 50;
		for (int i = 0; i < DungeonConfig.DIFFICULTIES.length; i++) {
			final int diff = i;
			final DungeonConfig.Difficulty d = DungeonConfig.DIFFICULTIES[i];
			final Component label = Component.literal(
					d.nameZh + "　" + d.cost + "硬币 / " + d.mobCount + "怪 / " + materialZh(d) + "装备");
			this.addRenderableWidget(Button.builder(label, btn -> {
				ClientPlayNetworking.send(new SelectDifficultyC2S(diff));
				Minecraft.getInstance().setScreenAndShow(null);
			}).bounds(cx - BUTTON_W / 2, startY + i * (BUTTON_H + 10), BUTTON_W, BUTTON_H).build());
		}
	}

	private static String materialZh(final DungeonConfig.Difficulty d) {
		if (d.loot == DungeonConfig.STONE_LOOT) {
			return "石质";
		}
		if (d.loot == DungeonConfig.DIAMOND_LOOT) {
			return "钻石";
		}
		return "铁质";
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

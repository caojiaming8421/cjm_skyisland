package com.cjm.skyisland.network;

import com.cjm.skyisland.Cjm_skyisland;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 -> 服务端：玩家在难度界面点选了某一档难度（0=简单 / 1=普通 / 2=困难）。
 * 服务端收到后校验并扣除硬币，再按所选难度把玩家送入副本。
 */
public record SelectDifficultyC2S(int difficulty) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SelectDifficultyC2S> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Cjm_skyisland.MOD_ID, "select_difficulty"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SelectDifficultyC2S> STREAM_CODEC =
			StreamCodec.composite(ByteBufCodecs.VAR_INT, SelectDifficultyC2S::difficulty, SelectDifficultyC2S::new);

	@Override
	public CustomPacketPayload.Type<SelectDifficultyC2S> type() {
		return TYPE;
	}
}

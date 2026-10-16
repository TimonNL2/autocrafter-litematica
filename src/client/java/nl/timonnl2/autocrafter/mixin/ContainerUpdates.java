package nl.timonnl2.autocrafter.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import nl.timonnl2.autocrafter.WorkshopClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ContainerUpdates {
    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void workshop$content(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        WorkshopClient.receivedFull(packet.containerId());
    }
    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void workshop$slot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        WorkshopClient.received(packet.getContainerId());
    }
}

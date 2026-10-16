package nl.timonnl2.autocrafter.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import nl.timonnl2.autocrafter.WorkshopClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Consume selection presses before vanilla starts mining, using items or opening storage. */
@Mixin(MouseHandler.class)
public class SelectionMouse {
    @Inject(method="onButton",at=@At("HEAD"),cancellable=true)
    private void workshop$select(long handle,MouseButtonInfo button,int action,CallbackInfo ci) {
        var mc=Minecraft.getInstance();
        if(action==1&&handle==mc.getWindow().handle()&&WorkshopClient.INSTANCE!=null
            &&WorkshopClient.INSTANCE.selectWithMouse(mc,button.button()))ci.cancel();
    }
}

package nl.timonnl2.autocrafter.mixin;

import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.gizmos.*;
import net.minecraft.world.phys.AABB;
import nl.timonnl2.autocrafter.WorkshopClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugRenderer.class)
public class RegionOverlay {
    @Inject(method="emitGizmos",at=@At("TAIL"))
    private void workshop$regions(Frustum frustum,double x,double y,double z,float tick,CallbackInfo ci) {
        var app=WorkshopClient.INSTANCE;
        if(app==null || !app.config.showRegions) return;
        for(var region:app.config.regions) {
            int color=switch(region.kind()) { case "Input" -> 0xff50dbc4; case "Output" -> 0xfff0bc69; default -> 0xff99aaff; };
            var a=region.min(); var b=region.max();
            Gizmos.cuboid(new AABB(a.getX(),a.getY(),a.getZ(),b.getX()+1,b.getY()+1,b.getZ()+1),GizmoStyle.stroke(color,2),true);
            Gizmos.billboardTextOverBlock(region.kind(),a,0,color,0.7f);
        }
        if(app.corner!=null) Gizmos.cuboid(app.corner,GizmoStyle.stroke(0xffffffff,3));
        if(app.config.staging!=null) { Gizmos.cuboid(app.config.staging.pos(),GizmoStyle.stroke(0xffde9af5,2)); Gizmos.billboardTextOverBlock("Shulker staging",app.config.staging.pos(),0,0xffde9af5,0.6f); }
    }
}

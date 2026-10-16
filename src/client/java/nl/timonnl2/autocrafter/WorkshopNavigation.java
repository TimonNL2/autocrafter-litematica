package nl.timonnl2.autocrafter;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** A bounded Baritone session. Container interaction remains owned by the workshop. */
public final class WorkshopNavigation {
    /** Baritone 1.20.0 keeps cache workers alive forever; they must not prevent JVM shutdown. */
    public static void configureCacheWorkers(){
        var implementation=BaritoneAPI.getProvider().getPrimaryBaritone().getClass();
        // The release obfuscates method names, but preserves this public accessor's return type.
        var accessors=Arrays.stream(implementation.getMethods()).filter(m->java.lang.reflect.Modifier.isStatic(m.getModifiers())&&m.getParameterCount()==0&&m.getReturnType()==java.util.concurrent.Executor.class).toList();
        if(accessors.size()!=1)throw new IllegalStateException("Unsupported Baritone executor API. Install the official 1.20.0 Fabric API build.");
        try{
            var executor=(java.util.concurrent.ThreadPoolExecutor)accessors.getFirst().invoke(null);
            var factory=executor.getThreadFactory();
            executor.setThreadFactory(task->{var thread=factory.newThread(task);thread.setDaemon(true);return thread;});
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot configure Baritone cache worker lifecycle",e);}
    }
    private final Minecraft mc=Minecraft.getInstance();
    private final Map<Settings.Setting<?>,Object> saved=new LinkedHashMap<>();
    private IBaritone baritone;
    private BlockPos target;
    private Runnable arrived;
    private net.minecraft.world.phys.BlockHitResult interaction;
    private boolean walking, positionOnly;
    private int ticks, aiming;
    public String detail="";
    public boolean busy(){return target!=null;}
    private <T> void temporarily(Settings.Setting<T> setting,T value){saved.put(setting,setting.value);setting.value=value;}
    private void begin(BlockPos destination,boolean position,Runnable callback){
        if(busy())throw new IllegalStateException("Another workshop movement is still running.");
        if(mc.player==null||mc.level==null||!mc.level.hasChunkAt(destination))throw new IllegalStateException("Destination is not loaded.");
        baritone=BaritoneAPI.getProvider().getPrimaryBaritone();
        if(baritone.getPathingBehavior().isPathing()||baritone.getCustomGoalProcess().isActive()||baritone.getPathingControlManager().mostRecentInControl().filter(p->p.isActive()).isPresent())
            throw new IllegalStateException("Stop the current Baritone task before using the workshop.");
        var settings=BaritoneAPI.getSettings();
        temporarily(settings.allowBreak,false);temporarily(settings.allowBreakAnyway,List.of());temporarily(settings.allowPlace,false);
        temporarily(settings.allowInventory,false);temporarily(settings.autoTool,false);temporarily(settings.allowParkour,false);
        temporarily(settings.allowParkourPlace,false);temporarily(settings.allowSprint,false);temporarily(settings.allowWaterBucketFall,false);
        temporarily(settings.maxFallHeightNoWater,1);temporarily(settings.rightClickContainerOnArrival,false);
        temporarily(settings.freeLook,false);temporarily(settings.smoothLook,false);temporarily(settings.blockFreeLook,false);
        temporarily(settings.disconnectOnArrival,false);
        target=destination.immutable();positionOnly=position;arrived=callback;ticks=0;aiming=0;walking=false;interaction=null;
        if(mc.player.containerMenu!=mc.player.inventoryMenu)mc.player.closeContainer();
        mc.gui.setScreen(null);WorkshopClient.clearContentReceipts();
    }
    public void open(BlockPos pos){begin(pos,false,()->{});}
    public void walkTo(BlockPos pos,Runnable callback){begin(pos,true,callback);}
    public void interactAt(net.minecraft.world.phys.BlockHitResult hit){begin(hit.getBlockPos(),false,()->{});interaction=hit;}
    public void tick(){
        if(!busy())return;
        if(mc.player==null||mc.level==null||mc.player.isDeadOrDying())throw new IllegalStateException("Navigation stopped: player unavailable.");
        if(mc.gui.screen()!=null||mc.player.isShiftKeyDown())throw new IllegalStateException("Navigation interrupted by a screen or sneaking.");
        if(++ticks>1200)throw new IllegalStateException("Could not reach "+target.toShortString()+" within 60 seconds. Check the walkway.");
        if(positionOnly&&mc.player.blockPosition().equals(target)){
            if(walking){baritone.getPathingBehavior().cancelEverything();walking=false;}
            Vec3 delta=Vec3.atBottomCenterOf(target).subtract(mc.player.position());
            if(Math.hypot(delta.x,delta.z)>0.08){
                aiming=0;detail="Positioning beside Staging";
                float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
                baritone.getLookBehavior().updateTarget(new Rotation(yaw,0),true);
                Vec3 view=mc.player.getViewVector(1);
                boolean facing=new Vec3(view.x,0,view.z).normalize().dot(new Vec3(delta.x,0,delta.z).normalize())>0.995;
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD,facing);
                return;
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            if(++aiming<5)return;
            Runnable done=arrived;cancel();done.run();return;
        }
        var hit=positionOnly?null:interaction!=null?interaction:StorageScanner.reachable(mc,target);
        boolean reached=!positionOnly&&hit!=null;
        if(!reached){
            aiming=0;detail="Walking to "+target.toShortString();
            if(!walking){baritone.getCustomGoalProcess().setGoalAndPath(positionOnly?new GoalBlock(target):new GoalGetToBlock(target));walking=true;}
            if(ticks>60&&!baritone.getCustomGoalProcess().isActive()&&!baritone.getPathingBehavior().isPathing())throw new IllegalStateException("No walkable route to "+target.toShortString()+". Clear a walkway; the workshop will not break blocks.");
        } else {
            if(walking){baritone.getPathingBehavior().cancelEverything();baritone.getInputOverrideHandler().clearAllKeys();walking=false;}
            if(positionOnly){Runnable done=arrived;cancel();done.run();return;}
            Vec3 delta=hit.getLocation().subtract(mc.player.getEyePosition());
            float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
            float pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
            baritone.getLookBehavior().updateTarget(new Rotation(yaw,pitch),true);
            detail="Looking at "+target.toShortString();
            if(++aiming>=4&&mc.player.getViewVector(1).dot(delta.normalize())>0.995){
                var result=mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
                if(interaction!=null&&!result.consumesAction())throw new IllegalStateException("Placement was refused at "+target.toShortString()+"; player "+mc.player.position()+", held "+mc.player.getMainHandItem()+". Clear the staging area.");
                cancel();
            }
        }
        if(ticks%20==0)mc.gui.hud.setOverlayMessage(Component.literal(detail),false);
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    public void cancel(){
        if(target!=null&&baritone!=null){baritone.getPathingBehavior().cancelEverything();baritone.getInputOverrideHandler().clearAllKeys();}
        target=null;walking=false;
        saved.forEach((setting,value)->((Settings.Setting)setting).value=value);saved.clear();
    }
}

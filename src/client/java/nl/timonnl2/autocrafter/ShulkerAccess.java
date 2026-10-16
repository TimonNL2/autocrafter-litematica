package nl.timonnl2.autocrafter;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Owns exactly one staged box. Every chest transaction is verified through a full reopen. */
public final class ShulkerAccess {
    private enum Stage { IDLE, RECOVER_HOME, OPEN_HOME, FREE_HAND, TAKE, VERIFY_TAKE, PLACE, WAIT_PLACE, OPEN_BOX, ACTIVE,
        PREPARE_RETURN, EMPTY_HAND, MINE, COLLECT, RETURN_POSITION, OPEN_RETURN, MOVE_TO_HAND, VERIFY_HAND, PUT_BACK, VERIFY_RETURN, FAILED }
    private final WorkshopClient app;
    private final Minecraft mc=Minecraft.getInstance();
    private final Map<String,List<ItemStack>> snapshots;
    private Stage state=Stage.IDLE;
    private StorageScanner.Container active, requested;
    private Runnable callback;
    private Consumer<String> failure;
    private int ticks, hand, oldHand;
    private List<ItemStack> expectedHome, beforePickup;
    private ItemStack expectedBox;
    private boolean walking;
    private Vec3 standingPosition;
    private float standingYaw;
    public ShulkerAccess(WorkshopClient app,Map<String,List<ItemStack>> snapshots) {this.app=app;this.snapshots=snapshots;}
    public boolean busy() {return state!=Stage.IDLE && state!=Stage.ACTIVE && state!=Stage.FAILED;}
    public boolean ownsBox() {return active!=null;}
    public void snapshotChanged(StorageScanner.Container container){if(active!=null&&active.key().equals(container.key())){expectedBox=boxWithContents(active.box(),snapshots.get(active.key()));persist("Verified updated shulker contents");}}
    public BlockPos location(StorageScanner.Container container) {return container.packed()?app.config.staging.pos():container.pos();}
    public static boolean isBox(ItemStack item) {return item.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock;}
    public static List<ItemStack> copy(List<ItemStack> items) {return items.stream().map(ItemStack::copy).toList();}
    public static boolean same(List<ItemStack> a,List<ItemStack> b) {if(a==null||b==null||a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!ItemStack.matches(a.get(i),b.get(i)))return false;return true;}
    private String parentKey() {return active.pos().toShortString();}
    private List<ItemStack> menuItems() {var menu=mc.player.containerMenu;return menu.slots.subList(0,menu.slots.size()-36).stream().map(s->s.getItem().copy()).toList();}
    private List<ItemStack> inventory() {List<ItemStack> items=new ArrayList<>();for(int i=0;i<36;i++)items.add(mc.player.getInventory().getItem(i).copy());return items;}

    public List<String> stagingProblems() {
        List<String> errors=new ArrayList<>();
        if(app.config.staging==null) return List.of("Select a shulker staging position in Storage first.");
        BlockPos pos=app.config.staging.pos();
        if(!mc.level.hasChunkAt(pos))return List.of("Shulker staging area is not loaded.");
        if(!mc.level.getBlockState(pos).isAir() || !mc.level.getBlockState(pos.above()).isAir())errors.add("The staging block and the space above it must be empty.");
        var support=mc.level.getBlockState(pos.below());
        if(!support.isCollisionShapeFullBlock(mc.level,pos.below()) || support.getBlock() instanceof net.minecraft.world.level.block.EntityBlock)errors.add("Use a solid, non-interactive support block below the staging position.");
        Vec3 player=mc.player.position(),center=Vec3.atCenterOf(pos);
        if(mc.player.getBoundingBox().intersects(new AABB(pos)))errors.add("Step outside the staging block so the shulker can be placed.");
        if(Math.hypot(player.x-center.x,player.z-center.z)>1.6 || Math.abs(player.y-pos.getY())>0.5)errors.add("Stand beside the shulker staging position, on the same floor, within 1.6 blocks of its centre.");
        for(BlockPos nearby:BlockPos.betweenClosed(pos.offset(-1,-1,-1),pos.offset(1,1,1))) {
            var block=mc.level.getBlockState(nearby);
            if(!block.getFluidState().isEmpty() || block.getBlock() instanceof HopperBlock || block.is(Blocks.FIRE) || block.is(Blocks.SOUL_FIRE) || block.is(Blocks.CACTUS)) {errors.add("Keep fluids, hoppers, fire and cactus away from the staging area.");break;}
        }
        return errors;
    }
    public void ensure(StorageScanner.Container container,Runnable done,Consumer<String> onFailure) {
        requested=container!=null&&container.packed()?container:null;callback=done;failure=onFailure;
        if(active!=null && requested!=null && active.key().equals(requested.key()) && state==Stage.ACTIVE) {done.run();return;}
        if(active!=null) {
            expectedBox=boxWithContents(active.box(),snapshots.get(active.key()));
            persist("Return staged shulker to original chest slot");
            open(location(active),Stage.PREPARE_RETURN);return;
        }
        acquireRequested();
    }
    private void acquireRequested() {
        if(requested==null) {state=Stage.IDLE;callback.run();return;}
        var errors=stagingProblems();if(!errors.isEmpty()){fail(errors.getFirst());return;}
        active=requested;oldHand=mc.player.getInventory().getSelectedSlot();
        standingPosition=mc.player.position();standingYaw=mc.player.getYRot();
        expectedHome=copy(snapshots.get(parentKey()));
        if(active.boxSlot()>=expectedHome.size() || !isBox(expectedHome.get(active.boxSlot()))) {fail("The shulker reservation no longer matches the source snapshot");return;}
        expectedBox=expectedHome.get(active.boxSlot()).copy();
        persist("Reserved shulker; source still owns it");open(active.pos(),Stage.OPEN_HOME);
    }
    private void open(BlockPos pos,Stage next) {
        if(!StorageScanner.open(mc,pos)){fail("Cannot reach shulker container at "+pos.toShortString());return;}
        state=next;ticks=0;
    }
    private boolean confirmed() {return mc.player.containerMenu!=mc.player.inventoryMenu && WorkshopClient.contentReceived(mc.player.containerMenu.containerId) && ticks>=app.config.delayTicks;}
    private void click(int slot,int button,ContainerInput input) {
        var menu=mc.player.containerMenu;if(!menu.getCarried().isEmpty() || slot<0 || slot>=menu.slots.size())throw new IllegalStateException("Unsafe shulker inventory action");
        persist("Before "+input+" slot "+slot);app.journal("SHULKER INTENT "+input+" at "+active.label()+" slot "+slot);
        mc.gameMode.handleContainerInput(menu.containerId,slot,button,input,mc.player);
    }
    private int emptyHand() {for(int i=0;i<9;i++)if(mc.player.getInventory().getItem(i).isEmpty())return i;return -1;}
    private boolean prepareHand(Stage verifyStage,BlockPos reopen) {
        hand=emptyHand();if(hand>=0)return true;
        int free=-1;for(int i=9;i<36;i++)if(mc.player.getInventory().getItem(i).isEmpty()){free=i;break;}
        if(free<0){fail("No empty inventory slot remains to handle a shulker");return false;}
        hand=8;
        int menuSlot=mc.player.containerMenu.findSlot(mc.player.getInventory(),free).orElseThrow();
        click(menuSlot,hand,ContainerInput.SWAP);open(reopen,verifyStage);return false;
    }
    public void tick() {
        if(!busy())return;
        if(mc.player==null || mc.level==null || mc.player.isDeadOrDying()){fail("Disconnected while handling a shulker. Recovery record preserved.");return;}
        if(++ticks>400){fail("Shulker operation timed out; inspect the recovery record before continuing.");return;}
        try {
            var menu=mc.player.containerMenu;
            switch(state) {
                case RECOVER_HOME -> {
                    if(!confirmed())return;
                    if(!(menu instanceof ChestMenu)){fail("Recovery requires the original source chest");return;}
                    expectedHome=menuItems();snapshots.put(parentKey(),expectedHome);
                    var homeItem=menu.getSlot(active.boxSlot()).getItem();
                    if(boxMatches(homeItem,expectedBox) && mc.level.getBlockState(location(active)).isAir()) {active=null;clearRecovery();state=Stage.IDLE;callback.run();return;}
                    if(!homeItem.isEmpty()){fail("Original slot is occupied. Resolve it manually before retrying recovery.");return;}
                    if(mc.level.getBlockState(location(active)).getBlock() instanceof ShulkerBoxBlock)open(location(active),Stage.PREPARE_RETURN);
                    else if(findRecovered()>=0)open(active.pos(),Stage.OPEN_RETURN);
                    else {beforePickup=inventory();state=Stage.COLLECT;ticks=0;}
                }
                case OPEN_HOME, FREE_HAND -> {
                    if(!confirmed())return;
                    if(!(menu instanceof ChestMenu) || !same(menuItems(),expectedHome)){fail("Shulker source chest changed");return;}
                    if(!prepareHand(Stage.FREE_HAND,active.pos()))return;
                    click(active.boxSlot(),hand,ContainerInput.SWAP);
                    expectedHome=menuItems();snapshots.put(parentKey(),expectedHome);open(active.pos(),Stage.VERIFY_TAKE);
                }
                case VERIFY_TAKE -> {
                    if(!confirmed())return;
                    if(!same(menuItems(),expectedHome) || !ItemStack.matches(mc.player.getInventory().getItem(hand),expectedBox)){fail("Server did not confirm taking the reserved shulker");return;}
                    mc.player.closeContainer();mc.gui.setScreen(null);mc.player.getInventory().setSelectedSlot(hand);
                    persist("Shulker in inventory; about to place at staging position");state=Stage.PLACE;ticks=0;
                }
                case PLACE -> {
                    if(ticks<app.config.delayTicks)return;
                    var errors=stagingProblems();if(!errors.isEmpty()){fail(errors.getFirst());return;}
                    BlockPos support=app.config.staging.pos().below();
                    var hit=new BlockHitResult(Vec3.atLowerCornerWithOffset(support,0.5,1,0.5),Direction.UP,support,false);
                    mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
                    state=Stage.WAIT_PLACE;ticks=0;
                }
                case WAIT_PLACE -> {
                    if(ticks<app.config.delayTicks || !mc.player.getInventory().getItem(hand).isEmpty())return;
                    if(!(mc.level.getBlockState(app.config.staging.pos()).getBlock() instanceof ShulkerBoxBlock))return;
                    mc.player.getInventory().setSelectedSlot(oldHand);persist("Shulker placed at staging position");open(location(active),Stage.OPEN_BOX);
                }
                case OPEN_BOX -> {
                    if(!confirmed())return;
                    if(!(menu instanceof ShulkerBoxMenu)){fail("Expected the staged shulker screen");return;}
                    var actual=menuItems();
                    if(active.known() && !same(actual,snapshots.get(active.key()))){fail("Opened shulker contents differ from the reviewed scan");return;}
                    snapshots.put(active.key(),actual);expectedBox=boxWithContents(active.box(),actual);persist("Shulker opened and contents confirmed");state=Stage.ACTIVE;callback.run();
                }
                case PREPARE_RETURN, EMPTY_HAND -> {
                    if(!confirmed())return;
                    if(!(menu instanceof ShulkerBoxMenu) || !same(menuItems(),snapshots.get(active.key()))){fail("Staged shulker changed before recovery");return;}
                    if(!prepareHand(Stage.EMPTY_HAND,location(active)))return;
                    beforePickup=inventory();expectedBox=boxWithContents(active.box(),menuItems());persist("About to recover the staged shulker; contents verified");
                    mc.player.closeContainer();mc.gui.setScreen(null);mc.player.getInventory().setSelectedSlot(hand);
                    var hit=StorageScanner.reachable(mc,location(active));if(hit==null){fail("Cannot reach staged shulker for recovery");return;}
                    mc.gameMode.startDestroyBlock(location(active),hit.getDirection());state=Stage.MINE;ticks=0;
                }
                case MINE -> {
                    BlockPos pos=location(active);
                    if(mc.level.getBlockState(pos).isAir()) {mc.gameMode.stopDestroyBlock();state=Stage.COLLECT;ticks=0;persist("Waiting for dropped shulker to enter inventory");return;}
                    if(mc.level.getBlockState(pos).getBlock()!=((BlockItem)active.box().getItem()).getBlock()){fail("Staging block changed; refusing to break it");return;}
                    if(!mc.player.getInventory().getItem(hand).isEmpty()){fail("Mining hand changed; recovery interrupted");return;}
                    var hit=StorageScanner.reachable(mc,pos);if(hit==null){fail("Move back within reach of the staging position");return;}
                    mc.gameMode.continueDestroyBlock(pos,hit.getDirection());
                }
                case COLLECT -> {
                    int slot=findRecovered();
                    if(slot>=0) {stopWalking();mc.player.getInventory().setSelectedSlot(oldHand);persist("Shulker collected; returning to standing position");state=Stage.RETURN_POSITION;ticks=0;return;}
                    if(ticks>12)walkToStaging();
                }
                case RETURN_POSITION -> {
                    if(findRecovered()<0){fail("Recovered box changed while returning to the workshop position");return;}
                    Vec3 player=mc.player.position();
                    if(standingPosition==null||Math.hypot(player.x-standingPosition.x,player.z-standingPosition.z)<0.15){
                        stopWalking();if(ticks<app.config.delayTicks)return;
                        mc.player.setYRot(standingYaw);open(active.pos(),Stage.OPEN_RETURN);return;
                    }
                    walkTo(standingPosition);
                }
                case OPEN_RETURN, VERIFY_HAND -> {
                    if(!confirmed())return;
                    if(!(menu instanceof ChestMenu) || !same(menuItems(),expectedHome)){fail("Original shulker chest changed; recovered box remains in your inventory");return;}
                    int slot=findRecovered();if(slot<0){fail("Recovered shulker is missing or has different contents");return;}
                    if(slot<9)hand=slot;
                    else {
                        if(!prepareHand(Stage.OPEN_RETURN,active.pos()))return;
                        click(menu.findSlot(mc.player.getInventory(),slot).orElseThrow(),hand,ContainerInput.SWAP);
                        open(active.pos(),Stage.VERIFY_HAND);return;
                    }
                    if(!menu.getSlot(active.boxSlot()).getItem().isEmpty()){fail("Original shulker slot is occupied");return;}
                    click(active.boxSlot(),hand,ContainerInput.SWAP);expectedHome=menuItems();open(active.pos(),Stage.VERIFY_RETURN);
                }
                case VERIFY_RETURN -> {
                    if(!confirmed())return;
                    if(!same(menuItems(),expectedHome) || !boxMatches(menu.getSlot(active.boxSlot()).getItem(),expectedBox)){fail("Server did not confirm returning the shulker");return;}
                    snapshots.put(parentKey(),expectedHome);app.journal("SHULKER RETURNED "+active.label());
                    active=null;clearRecovery();state=Stage.IDLE;acquireRequested();
                }
                default -> {}
            }
        } catch(RuntimeException e) {fail(e.getMessage());}
    }
    private int findRecovered() {
        for(int i=0;i<36;i++) {
            var item=mc.player.getInventory().getItem(i);
            if(boxMatches(item,expectedBox) && (beforePickup==null || beforePickup.get(i).isEmpty() || !ItemStack.matches(beforePickup.get(i),item)))return i;
        }
        return -1;
    }
    private static ItemStack boxWithContents(ItemStack original,List<ItemStack> contents) {var box=original.copy();box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(contents));return box;}
    private static boolean boxMatches(ItemStack actual,ItemStack expected) {
        if(!isBox(actual)||actual.getCount()!=1||actual.getItem()!=expected.getItem())return false;
        // The server may omit empty CONTAINER components; normalize that representation only.
        ItemStack a=actual.copy(),b=expected.copy();
        if(a.get(DataComponents.CONTAINER)==null)a.set(DataComponents.CONTAINER,ItemContainerContents.EMPTY);
        if(b.get(DataComponents.CONTAINER)==null)b.set(DataComponents.CONTAINER,ItemContainerContents.EMPTY);
        return ItemStack.isSameItemSameComponents(a,b);
    }
    private void walkToStaging() {
        walkTo(Vec3.atBottomCenterOf(location(active)));
    }
    private void walkTo(Vec3 target) {
        Vec3 player=mc.player.position();BlockPos support=BlockPos.containing(target).below();
        if(player.distanceTo(target)>2 || Math.abs(player.y-target.y)>0.6 || !mc.level.getBlockState(support).isCollisionShapeFullBlock(mc.level,support)){fail("Shulker pickup needs manual recovery; workshop floor is no longer safely reachable");return;}
        if(Math.hypot(player.x-target.x,player.z-target.z)<0.15){stopWalking();return;}
        mc.player.setYRot((float)Math.toDegrees(Math.atan2(-(target.x-player.x),target.z-player.z)));
        mc.options.keyUp.setDown(true);walking=true;
    }
    private void stopWalking(){if(walking){mc.options.keyUp.setDown(false);walking=false;}}
    public void abort(String reason){if(state==Stage.FAILED)return;stopWalking();if(mc.gameMode!=null)mc.gameMode.stopDestroyBlock();if(active!=null && mc.level!=null)try{persist("INTERRUPTED: "+reason);}catch(RuntimeException e){app.log("Recovery record could not be updated; inspect the previous record.");}state=Stage.FAILED;}
    private void fail(String reason){String stage=state.name();abort(reason);if(failure!=null)failure.accept("Shulker handling stopped during "+stage+": "+reason);}
    public static Path recoveryPath(WorkshopClient app){return WorkshopConfig.directory().resolve(app.world+"-shulker-recovery.json");}
    public void recover(Runnable done,Consumer<String> onFailure) {
        callback=done;failure=onFailure;requested=null;
        try {
            if(mc.player==null||mc.level==null||mc.player.isCreative()||mc.player.isSpectator()||mc.player.isShiftKeyDown())throw new IllegalArgumentException("Use survival mode and release sneak before recovery");
            var json=JsonParser.parseString(Files.readString(recoveryPath(app))).getAsJsonObject();
            var gson=new Gson();var home=gson.fromJson(json.get("home"),WorkshopConfig.Point.class);
            var staging=gson.fromJson(json.get("staging"),WorkshopConfig.Point.class);
            if(app.config.staging==null || !app.config.staging.equals(staging))throw new IllegalArgumentException("Restore the recorded staging position before recovery");
            expectedBox=ItemStack.CODEC.parse(mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE),json.get("box")).getOrThrow();
            List<ItemStack> contents=new ArrayList<>(Collections.nCopies(27,ItemStack.EMPTY));
            var component=expectedBox.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY);var supplied=component.itemCopies().toList();
            for(int i=0;i<Math.min(27,supplied.size());i++)contents.set(i,supplied.get(i));
            active=new StorageScanner.Container(home.pos(),"Recovery",contents,json.get("slot").getAsInt(),expectedBox.copy(),true);
            standingPosition=mc.player.position();standingYaw=mc.player.getYRot();
            snapshots.put(active.key(),contents);oldHand=mc.player.getInventory().getSelectedSlot();beforePickup=null;
            open(active.pos(),Stage.RECOVER_HOME);
        } catch(Exception e){fail("Cannot restore recovery record: "+e.getMessage());}
    }
    private void persist(String instruction) {
        if(active==null)return;
        JsonObject json=new JsonObject();json.addProperty("instruction",instruction);json.addProperty("state",state.name());
        json.add("home",new Gson().toJsonTree(WorkshopConfig.Point.of(active.pos())));json.addProperty("slot",active.boxSlot());
        json.add("staging",new Gson().toJsonTree(app.config.staging));
        var ops=mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        json.add("box",ItemStack.CODEC.encodeStart(ops,expectedBox==null?active.box():expectedBox).getOrThrow());
        try {Files.createDirectories(WorkshopConfig.directory());var path=recoveryPath(app);var temp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(json));Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(Exception e){throw new IllegalStateException("Cannot persist shulker recovery record; movement stopped",e);}
    }
    private void clearRecovery(){try{Files.deleteIfExists(recoveryPath(app));}catch(Exception e){app.log("Returned shulker; could not remove its recovery record.");}}
}

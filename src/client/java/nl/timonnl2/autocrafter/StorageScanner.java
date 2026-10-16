package nl.timonnl2.autocrafter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.*;
import java.util.*;

public final class StorageScanner {
    public record Container(BlockPos pos, String kind, List<ItemStack> items, int boxSlot, ItemStack box, boolean known) {
        public Container(BlockPos pos,String kind,List<ItemStack> items) {this(pos,kind,items,-1,ItemStack.EMPTY,true);}
        public boolean packed() {return boxSlot>=0;}
        public String key() {return pos.toShortString()+(packed()?"/box/"+boxSlot:"");}
        public String label() {return pos.toShortString()+(packed()?" [shulker slot "+(boxSlot+1)+"]":"");}
    }
    public final List<Container> containers = new ArrayList<>();
    public final List<BlockPos> tables = new ArrayList<>();
    public final Map<String, Integer> stock = new TreeMap<>();
    public final List<String> shulkers = new ArrayList<>();
    private final WorkshopClient app;
    private final Minecraft mc = Minecraft.getInstance();
    private final Deque<Map.Entry<BlockPos,String>> pending = new ArrayDeque<>();
    private Map.Entry<BlockPos,String> current;
    private int wait;
    private int previousMenu;
    public boolean running;
    public boolean complete;
    private boolean detailed;
    private boolean cancelDetailed;
    private ShulkerAccess boxAccess;
    private final Map<String,List<ItemStack>> boxSnapshots=new HashMap<>();
    private final Deque<Container> pendingBoxes=new ArrayDeque<>();
    public StorageScanner(WorkshopClient app) { this.app = app; }

    public void begin() {
        if(running||app.recovering())throw new IllegalArgumentException("Finish or stop the current storage operation first.");
        if (mc.player == null || mc.level == null || app.executor.busy()) return;
        containers.clear(); tables.clear(); stock.clear(); shulkers.clear(); pending.clear(); complete=false; current=null;
        Map<BlockPos,String> found = new LinkedHashMap<>();
        for (var region : app.config.regions) {
            if (region.volume() > 32768) throw new IllegalArgumentException("A region cannot exceed 32,768 blocks");
            for (BlockPos mutable : BlockPos.betweenClosed(region.min(), region.max())) {
                BlockPos pos = mutable.immutable();
                if (!mc.level.hasChunkAt(pos)) throw new IllegalArgumentException("Region contains unloaded chunks. Move closer and scan again.");
                var state = mc.level.getBlockState(pos);
                if (region.kind().equals("Work")) { if (state.is(Blocks.CRAFTING_TABLE) && !tables.contains(pos)) tables.add(pos); continue; }
                if (!(state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock || state.getBlock() instanceof ShulkerBoxBlock)) continue;
                if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
                    if (!app.config.regions.stream().filter(r -> r.kind().equals(region.kind())).anyMatch(r -> r.contains(partner))) throw new IllegalArgumentException("Select both halves of the double chest at " + pos.toShortString());
                    if (state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) continue;
                }
                String old = found.putIfAbsent(pos, region.kind());
                if (old != null && !old.equals(region.kind())) throw new IllegalArgumentException("Input and output storage overlap");
            }
        }
        found.forEach((pos,kind) -> pending.add(Map.entry(pos,kind)));
        if (pending.isEmpty()) throw new IllegalArgumentException("No storage found. Select Input and Output regions first.");
        if(!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalArgumentException("Clear the cursor stack before scanning.");
        if(mc.player.isShiftKeyDown())throw new IllegalArgumentException("Release sneak before scanning.");
        if(mc.player.containerMenu!=mc.player.inventoryMenu)mc.player.closeContainer();
        mc.gui.setScreen(null); running=true; app.plan=null;
        app.log("Scan started: " + pending.size() + " containers. No items will be moved.");
    }
    public void tick() {
        if (!running) return;
        if (mc.player == null || mc.level == null) { stop("Disconnected. Scan discarded."); return; }
        if(detailed){boxAccess.tick();return;}
        if (current == null) {
            if (pending.isEmpty()) { running=false; complete=true; app.log("Scan complete: " + containers.size() + " containers, " + stock.size() + " available item types."); app.open(); return; }
            current = pending.peek(); wait=0; previousMenu=mc.player.containerMenu.containerId;
            if (!open(mc, current.getKey())) { stop("Cannot reach " + current.getKey().toShortString() + ". Move closer and scan again."); return; }
        }
        wait++;
        var menu = mc.player.containerMenu;
        if (menu.containerId != previousMenu && WorkshopClient.contentReceived(menu.containerId) && wait >= app.config.delayTicks) {
            if (!(menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu)) { stop("Unexpected screen. Scan paused without moving items."); return; }
            int size = menu.slots.size()-36;
            List<ItemStack> items = new ArrayList<>();
            List<Container> boxes=new ArrayList<>();
            for (int i=0;i<size;i++) {
                ItemStack stack=menu.getSlot(i).getItem().copy(); items.add(stack);
                if (stack.isEmpty()) continue;
                if (stack.getItem() instanceof BlockItem b && b.getBlock() instanceof ShulkerBoxBlock) {
                    var contents = stack.get(DataComponents.CONTAINER);
                    String detail = contents == null ? "unknown contents; physical scan required" : "server-supplied contents";
                    shulkers.add(current.getValue() + " " + current.getKey().toShortString() + ": " + detail);
                    List<ItemStack> boxItems=new ArrayList<>(Collections.nCopies(27,ItemStack.EMPTY));
                    if(contents!=null) {var supplied=contents.itemCopies().toList();for(int slot=0;slot<Math.min(27,supplied.size());slot++)boxItems.set(slot,supplied.get(slot).copy());}
                    boxes.add(new Container(current.getKey(),current.getValue(),List.copyOf(boxItems),i,stack.copy(),contents!=null));
                    if(contents!=null && app.config.inspectShulkers) contents.nonEmptyItemCopyStream().forEach(item->shulkers.add("  Packed: "+item.getCount()+" "+RecipeCatalog.id(item)));
                } else if (current.getValue().equals("Input") && RecipeCatalog.plain(stack)) stock.merge(RecipeCatalog.id(stack), stack.getCount(), Integer::sum);
            }
            containers.add(new Container(current.getKey(),current.getValue(),List.copyOf(items)));
            containers.addAll(boxes);
            rebuildStock();mc.player.closeContainer(); pending.remove(); current=null;
        } else if (wait > 120) stop("No confirmed container contents received within 6 seconds. Scan stopped.");
    }
    public void stop(String reason) { running=false; complete=false; if(boxAccess!=null)boxAccess.abort(reason);detailed=false;app.log(reason); }
    public void requestStop(){if(detailed){cancelDetailed=true;app.log("Stopping scan after returning the current shulker.");}else stop("Scan stopped.");}
    public void beginBoxScan() {
        if(running||app.recovering())throw new IllegalArgumentException("Finish the current storage operation first.");
        if(!complete || app.executor.busy())throw new IllegalArgumentException("Complete the chest scan first and stop any active task.");
        if(mc.player.isCreative()||mc.player.isSpectator())throw new IllegalArgumentException("Use survival mode for physical shulker inspection.");
        if(mc.player.isShiftKeyDown()||!mc.player.getOffhandItem().isEmpty()||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalArgumentException("Release sneak and clear your offhand and cursor before inspecting shulkers.");
        if(java.nio.file.Files.exists(ShulkerAccess.recoveryPath(app)))throw new IllegalArgumentException("Recover the interrupted shulker operation in Activity first.");
        for(int i=0;i<36;i++)if(!mc.player.getInventory().getItem(i).isEmpty())throw new IllegalArgumentException("Empty your main inventory before physically scanning shulkers.");
        boxSnapshots.clear();containers.forEach(c->boxSnapshots.put(c.key(),ShulkerAccess.copy(c.items())));
        boxAccess=new ShulkerAccess(app,boxSnapshots);var problems=boxAccess.stagingProblems();if(!problems.isEmpty())throw new IllegalArgumentException(problems.getFirst());
        pendingBoxes.clear();containers.stream().filter(Container::packed).forEach(pendingBoxes::add);
        detailed=true;running=true;complete=false;cancelDetailed=false;app.plan=null;nextBox();
    }
    private void nextBox() {
        if(cancelDetailed||pendingBoxes.isEmpty()){running=false;detailed=false;complete=!cancelDetailed;rebuildStock();app.log(cancelDetailed?"Shulker scan stopped; handled boxes returned.":"Physical shulker scan completed; all boxes returned.");app.open();return;}
        var box=pendingBoxes.remove();
        boxAccess.ensure(new Container(box.pos(),box.kind(),box.items(),box.boxSlot(),box.box(),false),()->{
            var verified=ShulkerAccess.copy(boxSnapshots.get(box.key()));
            boxAccess.ensure(null,()->{
                for(int i=0;i<containers.size();i++) {
                    var old=containers.get(i);
                    if(old.key().equals(box.key()))containers.set(i,new Container(box.pos(),box.kind(),verified,box.boxSlot(),boxSnapshots.get(box.pos().toShortString()).get(box.boxSlot()).copy(),true));
                    else if(!old.packed() && old.pos().equals(box.pos()))containers.set(i,new Container(old.pos(),old.kind(),ShulkerAccess.copy(boxSnapshots.get(old.key()))));
                }
                nextBox();
            },this::stop);
        },this::stop);
    }
    public void rebuildStock() {
        stock.clear();for(var c:containers)if(c.kind().equals("Input") && c.known() && (!c.packed() || app.config.handleShulkers))
            for(var item:c.items())if(!item.isEmpty() && !ShulkerAccess.isBox(item) && RecipeCatalog.plain(item))stock.merge(RecipeCatalog.id(item),item.getCount(),Integer::sum);
    }
    public static BlockHitResult reachable(Minecraft mc, BlockPos pos) {
        if (mc.player==null || mc.level==null || !mc.level.hasChunkAt(pos)) return null;
        Vec3 eye=mc.player.getEyePosition(), center=Vec3.atCenterOf(pos);
        BlockHitResult hit=mc.level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (!hit.getBlockPos().equals(pos) || hit.getType()!=HitResult.Type.BLOCK || eye.distanceTo(hit.getLocation()) > mc.player.blockInteractionRange()) return null;
        return hit;
    }
    public static boolean open(Minecraft mc, BlockPos pos) {
        BlockHitResult hit=reachable(mc,pos);
        if (hit==null || mc.gameMode==null || !mc.player.containerMenu.getCarried().isEmpty()) return false;
        if (mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        WorkshopClient.clearContentReceipts();
        mc.gui.setScreen(null);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        return true;
    }
}

package nl.timonnl2.autocrafter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import nl.timonnl2.autocrafter.core.Planner;
import java.util.*;

/** One inventory transaction per authoritative container reopen. Never clicks outside a slot. */
public final class WorkshopExecutor {
    public enum State { IDLE, OPENING, READY, RECIPE_WAIT, SETTLING, VERIFYING, PAUSED, COMPLETE, STOPPED }
    public record Pickup(StorageScanner.Container source, int slot, int hotbar) {}
    private final WorkshopClient app;
    private final Minecraft mc=Minecraft.getInstance();
    public State state=State.IDLE;
    public String detail="No active task";
    public int completedCrafts;
    private List<Pickup> pickups=List.of();
    private int pickupIndex;
    private StorageScanner.Container output;
    private BlockPos table, position;
    private int ticks;
    private boolean pauseRequested;
    private int phase; // 0 collect, 1 craft, 2 store
    private Map<String,Integer> expectedInventory;
    private final Map<BlockPos,List<ItemStack>> snapshots=new HashMap<>();
    private List<Planner.Step> steps=List.of();
    private final Set<Integer> validatedMenus=new HashSet<>();
    public WorkshopExecutor(WorkshopClient app) { this.app=app; }
    public boolean busy() { return state!=State.IDLE && state!=State.COMPLETE && state!=State.STOPPED; }

    public List<String> preflight() {
        if(busy()) return List.of("Stop the active task before reviewing a new task.");
        List<String> errors=new ArrayList<>();
        if(mc.player==null || mc.level==null || app.plan==null || !app.scanner.complete) return List.of("Create a plan from a completed scan first.");
        if(mc.player.isCreative() || mc.player.isSpectator()) errors.add("Use survival mode for inventory-safe execution.");
        for(int i=0;i<36;i++) if(!mc.player.getInventory().getItem(i).isEmpty()) { errors.add("Empty your 36 main inventory slots. This alpha reserves them for the job and never consumes personal stacks."); break; }
        if(!mc.player.getOffhandItem().isEmpty()) errors.add("Empty your offhand before starting.");
        if(!mc.player.containerMenu.getCarried().isEmpty()) errors.add("Clear the cursor stack first.");
        if(mc.player.isShiftKeyDown()) errors.add("Release sneak before starting.");
        Map<String,Integer> needed=requiredInputs(app.plan);
        List<Pickup> selected=new ArrayList<>();
        Map<String,Integer> gathered=new HashMap<>();
        for(var container:app.scanner.containers) if(container.kind().equals("Input")) {
            for(int i=0;i<container.items().size();i++) {
                var stack=container.items().get(i); if(stack.isEmpty() || !RecipeCatalog.plain(stack)) continue;
                String id=RecipeCatalog.id(stack);
                if(needed.getOrDefault(id,0)<=0) continue;
                selected.add(new Pickup(container,i,selected.size()));
                needed.merge(id,-stack.getCount(),Integer::sum); gathered.merge(id,stack.getCount(),Integer::sum);
            }
        }
        if(needed.values().stream().anyMatch(n->n>0)) errors.add("The scan no longer covers the plan's required ingredients.");
        if(selected.isEmpty()) errors.add("No achievable products in this plan.");
        if(selected.size()>9) errors.add("This alpha supports at most nine source stacks per job. Split this material list into smaller batches.");
        int maximum=stackCount(gathered);
        for(var step:app.plan.steps()) {
            step.ingredients().forEach(id->gathered.merge(id,-1,Integer::sum));
            gathered.merge(step.output(),step.count(),Integer::sum);
            var layout=app.catalog.layouts.get(step.recipe());
            if(layout==null) { errors.add("Recipe book changed. Scan and plan again."); break; }
            layout.recipe().remainders().forEach((id,n)->gathered.merge(id,n,Integer::sum));
            maximum=Math.max(maximum,stackCount(gathered));
        }
        if(maximum>27) errors.add("Not enough reserved inventory space for products and remainders. Use a smaller batch.");
        int finalStacks=stackCount(gathered);
        boolean includesShulker=gathered.entrySet().stream().filter(e->e.getValue()>0).map(e->RecipeCatalog.stack(e.getKey()).getItem()).anyMatch(item->item instanceof net.minecraft.world.item.BlockItem block && block.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock);
        output=app.scanner.containers.stream().filter(c->c.kind().equals("Output") && c.items().stream().allMatch(ItemStack::isEmpty) && c.items().size()>=finalStacks
            && (!includesShulker || !(mc.level.getBlockState(c.pos()).getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock))).findFirst().orElse(null);
        if(output==null) errors.add("Select and scan one completely empty output chest, barrel or placed shulker with enough slots.");
        table=app.scanner.tables.stream().filter(p->StorageScanner.reachable(mc,p)!=null).findFirst().orElse(null);
        if(!app.plan.steps().isEmpty() && table==null) errors.add("No reachable crafting table in the Work region.");
        for(var pick:selected) if(StorageScanner.reachable(mc,pick.source.pos())==null) { errors.add("Input is out of reach: "+pick.source.pos().toShortString()); break; }
        if(output!=null && StorageScanner.reachable(mc,output.pos())==null) errors.add("Output is out of reach: "+output.pos().toShortString());
        pickups=List.copyOf(selected);
        return errors;
    }
    private static int stackCount(Map<String,Integer> inventory) {
        int slots=0;
        for(var e:inventory.entrySet()) if(e.getValue()>0) { int max=RecipeCatalog.stack(e.getKey()).getMaxStackSize(); slots+=(e.getValue()+max-1)/max; }
        return slots;
    }
    public Map<String,Integer> requiredInputs(Planner.Plan plan) {
        Map<String,Integer> buffer=new LinkedHashMap<>(), needed=new LinkedHashMap<>();
        for(var step:plan.steps()) {
            for(String item:step.ingredients()) consume(item,1,buffer,needed);
            buffer.merge(step.output(),step.count(),Integer::sum);
            var layout=app.catalog.layouts.get(step.recipe());
            if(layout!=null) layout.recipe().remainders().forEach((item,n)->buffer.merge(item,n,Integer::sum));
        }
        for(var target:plan.targets()) consume(target.item(),target.available(),buffer,needed);
        return needed;
    }
    private static void consume(String item,int count,Map<String,Integer> buffer,Map<String,Integer> needed) {
        int used=Math.min(count,buffer.getOrDefault(item,0)); buffer.merge(item,-used,Integer::sum);
        if(used<count) needed.merge(item,count-used,Integer::sum);
    }
    public List<String> movements() {
        List<String> lines=new ArrayList<>();
        for(var pick:pickups) { var stack=pick.source.items().get(pick.slot); lines.add(stack.getCount()+" "+RecipeCatalog.id(stack)+" from "+pick.source.pos().toShortString()); }
        if(output!=null) lines.add("All products, surplus and remainders -> "+output.pos().toShortString());
        lines.add("Source transfers use whole stacks. Your inventory must be empty.");
        return lines;
    }
    public void start() {
        if(busy()) throw new IllegalArgumentException("Stop the current task before starting another.");
        List<String> errors=preflight(); if(!errors.isEmpty()) throw new IllegalArgumentException(errors.getFirst());
        snapshots.clear(); app.scanner.containers.forEach(c->snapshots.put(c.pos(),copy(c.items())));
        validatedMenus.clear();
        steps=app.plan.steps(); completedCrafts=0; pickupIndex=0; phase=0; pauseRequested=false;
        expectedInventory=Map.of();
        app.journal("START reviewed plan: "+pickups.size()+" source stacks, "+steps.size()+" crafts; output "+output.pos().toShortString());
        next();
    }
    public void pause() { if(busy() && state!=State.PAUSED) { pauseRequested=true; detail="Pausing after the current verified transaction"; } }
    public void resume() {
        if(state!=State.PAUSED) return;
        if(!inventory().equals(expectedInventory)) throw new IllegalArgumentException("Inventory changed while paused. Stop and inspect recovery log.");
        pauseRequested=false; next();
    }
    public void stop(String reason) {
        if(!busy()) return;
        state=State.STOPPED; detail=reason; pauseRequested=false; app.scanner.complete=false;
        // No cursor actions are ever used. Leave the current menu visible for recovery.
        try {app.journal("STOP: "+reason+" Current craft grid may contain job ingredients; inspect it before closing.");}
        catch(RuntimeException e) {app.log("Recovery log unavailable. Inspect the current inventory and crafting grid manually.");}
    }
    private void next() {
        expectedInventory=inventory();
        if(pauseRequested) { state=State.PAUSED; detail="Paused at a verified checkpoint"; app.journal(detail); app.open(); return; }
        if(phase==0 && pickupIndex>=pickups.size()) phase=1;
        if(phase==1 && completedCrafts>=steps.size()) phase=2;
        if(phase==2 && inventory().isEmpty()) { state=State.COMPLETE; detail="Completed; products and surplus are in the output container"; app.scanner.complete=false; app.journal(detail); mc.player.closeContainer(); app.open(); return; }
        position=phase==0?pickups.get(pickupIndex).source.pos():phase==1?table:output.pos();
        detail=phase==0?"Collecting source stack "+(pickupIndex+1)+" / "+pickups.size():phase==1?"Crafting "+(completedCrafts+1)+" / "+steps.size():"Storing products and surplus";
        open(State.OPENING);
    }
    private void open(State waiting) {
        if(!StorageScanner.open(mc,position)) { stop("Cannot reach "+position.toShortString()+". Items remain in the job inventory."); return; }
        ticks=0; state=waiting;
    }
    public void tick() {
        if(!busy() || state==State.PAUSED) return;
        if(mc.player==null || mc.level==null || mc.player.isDeadOrDying()) { stop("Disconnected or player unavailable"); return; }
        if(mc.player.isShiftKeyDown()) { stop("Sneaking interrupted the task"); return; }
        ticks++;
        if(ticks>160) { stop("Server confirmation timed out. No automatic retry was made."); return; }
        var menu=mc.player.containerMenu;
        if(state!=State.OPENING && state!=State.VERIFYING && !(mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) {stop("Unexpected screen interrupted the task");return;}
        if(state==State.OPENING || state==State.VERIFYING) {
            if(menu==mc.player.inventoryMenu || !WorkshopClient.contentReceived(menu.containerId) || ticks<app.config.delayTicks) return;
            if(!(phase==1?menu instanceof CraftingMenu:menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu)) { stop("Unexpected container screen"); return; }
            if(!inventory().equals(expectedInventory)) { stop("Server inventory differs from the expected job inventory"); return; }
            if(phase!=1 && !same(containerItems(menu),snapshots.get(position))) { stop("Storage changed since the reviewed snapshot: "+position.toShortString()); return; }
            if(phase==1 && !gridEmpty(menu)) { stop("Crafting grid was not empty after reopening"); return; }
            validatedMenus.add(menu.containerId);
            if(state==State.VERIFYING) {
                app.journal("CONFIRMED "+detail+" at "+position.toShortString());
                if(phase==0) pickupIndex++; else if(phase==1) completedCrafts++;
                next(); return;
            }
            state=State.READY; ticks=0;
        }
        if(state==State.READY && ticks>=app.config.delayTicks) {
            if(!validatedMenus.contains(menu.containerId)) { stop("The active menu changed unexpectedly"); return; }
            if(!inventory().equals(expectedInventory) || phase!=1 && !same(containerItems(menu),snapshots.get(position))) {stop("Items changed before the next action");return;}
            if(phase==1 && !gridEmpty(menu)) {stop("Crafting grid changed before recipe placement");return;}
            if(phase==0) {
                var pick=pickups.get(pickupIndex);
                if(!mc.player.getInventory().getItem(pick.hotbar).isEmpty()) { stop("Reserved hotbar slot is occupied"); return; }
                click(pick.slot,pick.hotbar,ContainerInput.SWAP);
                checkpoint();
            } else if(phase==1) {
                var step=steps.get(completedCrafts); var layout=app.catalog.layouts.get(step.recipe());
                app.journal("INTENT place recipe "+step.output());
                WorkshopClient.clearContentReceipts();
                mc.gameMode.handlePlaceRecipe(menu.containerId,layout.displayId(),false);
                state=State.RECIPE_WAIT; ticks=0;
            } else {
                int slot=-1;
                for(int i=menu.slots.size()-36;i<menu.slots.size();i++) if(!menu.getSlot(i).getItem().isEmpty()) { slot=i; break; }
                if(slot<0) { next(); return; }
                click(slot,0,ContainerInput.QUICK_MOVE); checkpoint();
            }
        } else if(state==State.RECIPE_WAIT && ticks>=app.config.delayTicks && WorkshopClient.updateReceived(menu.containerId)) {
            if(!(menu instanceof CraftingMenu)) { stop("Crafting screen changed"); return; }
            var step=steps.get(completedCrafts); var layout=app.catalog.layouts.get(step.recipe());
            for(int grid=1;grid<=9;grid++) {
                int index=layout.gridSlots().indexOf(grid); ItemStack stack=menu.getSlot(grid).getItem();
                if(index<0?!stack.isEmpty():stack.isEmpty() || stack.getCount()!=1 || !RecipeCatalog.id(stack).equals(step.ingredients().get(index)) || !RecipeCatalog.plain(stack)) { stop("Server chose different ingredients. Inspect the crafting grid; no result was taken."); return; }
            }
            ItemStack result=menu.getSlot(0).getItem();
            if(result.isEmpty()) return;
            Map<String,Integer> staged=new HashMap<>(inventory());
            for(int i=1;i<=9;i++){var stack=menu.getSlot(i).getItem();if(!stack.isEmpty())staged.merge(RecipeCatalog.id(stack),stack.getCount(),Integer::sum);}
            if(!staged.equals(expectedInventory)) {stop("Job inventory changed during recipe placement");return;}
            if(!RecipeCatalog.id(result).equals(step.output()) || result.getCount()!=step.count() || !RecipeCatalog.plain(result)) { stop("Recipe result differs from the approved plan"); return; }
            int room=0;for(int i=0;i<36;i++){var stack=mc.player.getInventory().getItem(i);if(stack.isEmpty())room+=result.getMaxStackSize();else if(ItemStack.isSameItemSameComponents(stack,result))room+=stack.getMaxStackSize()-stack.getCount();}
            if(room<result.getCount()) {stop("No room for the entire crafting result");return;}
            click(0,0,ContainerInput.QUICK_MOVE); checkpoint();
        } else if(state==State.SETTLING && ticks>=app.config.delayTicks) {
            if(!validatedMenus.contains(menu.containerId)) { stop("Menu changed before verification"); return; }
            // Reopening requests a full server snapshot, even when vanilla optimistically suppresses click echoes.
            open(State.VERIFYING);
        }
    }
    private void click(int slot,int button,ContainerInput input) {
        var menu=mc.player.containerMenu;
        if(slot<0 || slot>=menu.slots.size() || !menu.getCarried().isEmpty()) throw new IllegalStateException("Invalid or occupied cursor");
        app.journal("INTENT "+input+" slot "+slot+" at "+position.toShortString());
        var before=copy(menu.getItems());
        mc.gameMode.handleContainerInput(menu.containerId,slot,button,input,mc.player);
        if(same(before,menu.getItems()))throw new IllegalStateException("The inventory action made no progress. Check capacity and slot restrictions.");
    }
    private void checkpoint() {
        var menu=mc.player.containerMenu;
        if(!menu.getCarried().isEmpty()) { stop("Unexpected cursor stack. Keep this screen open for recovery."); return; }
        expectedInventory=new HashMap<>(inventory());
        if(phase==1) for(int i=1;i<=9;i++) { var stack=menu.getSlot(i).getItem(); if(!stack.isEmpty()) expectedInventory.merge(RecipeCatalog.id(stack),stack.getCount(),Integer::sum); }
        else snapshots.put(position,containerItems(menu));
        state=State.SETTLING; ticks=0;
    }
    private Map<String,Integer> inventory() {
        Map<String,Integer> counts=new TreeMap<>();
        if(mc.player!=null) for(int i=0;i<36;i++) { var stack=mc.player.getInventory().getItem(i); if(!stack.isEmpty()) counts.merge(RecipeCatalog.id(stack)+(RecipeCatalog.plain(stack)?"":"#modified"),stack.getCount(),Integer::sum); }
        return counts;
    }
    private static boolean gridEmpty(AbstractContainerMenu menu) { for(int i=0;i<=9;i++) if(!menu.getSlot(i).getItem().isEmpty()) return false; return true; }
    private static List<ItemStack> containerItems(AbstractContainerMenu menu) { return menu.slots.subList(0,menu.slots.size()-36).stream().map(s->s.getItem().copy()).toList(); }
    private static List<ItemStack> copy(List<ItemStack> items) { return items.stream().map(ItemStack::copy).toList(); }
    private static boolean same(List<ItemStack> a,List<ItemStack> b) { if(b==null || a.size()!=b.size()) return false; for(int i=0;i<a.size();i++) if(!ItemStack.matches(a.get(i),b.get(i))) return false; return true; }
}

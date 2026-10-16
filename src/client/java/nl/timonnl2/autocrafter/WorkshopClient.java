package nl.timonnl2.autocrafter;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import nl.timonnl2.autocrafter.core.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class WorkshopClient implements ClientModInitializer {
    public static WorkshopClient INSTANCE;
    private static final Set<Integer> RECEIVED = new HashSet<>();
    private static final Set<Integer> FULL_CONTENT = new HashSet<>();
    public static void received(int id) { RECEIVED.add(id); }
    public static void receivedFull(int id) { FULL_CONTENT.add(id); RECEIVED.add(id); }
    public static boolean contentReceived(int id) { return FULL_CONTENT.contains(id); }
    public static boolean updateReceived(int id) { return RECEIVED.contains(id); }
    public static void clearContentReceipts() { RECEIVED.clear(); FULL_CONTENT.clear(); }
    public WorkshopConfig config = new WorkshopConfig();
    public String world = "offline";
    public final List<String> log = new ArrayList<>();
    public StorageScanner scanner = new StorageScanner(this);
    public WorkshopExecutor executor = new WorkshopExecutor(this);
    public RecipeCatalog catalog = new RecipeCatalog();
    public Planner.Plan plan;
    public String selection;
    public BlockPos corner;
    public List<String> lastReport=new ArrayList<>();
    private ShulkerAccess recovery;
    private KeyMapping openKey, selectKey, stopKey;
    @Override public void onInitializeClient() {
        INSTANCE=this;
        var category=KeyMapping.Category.register(Identifier.fromNamespaceAndPath("autocrafter_litematica","workshop"));
        openKey=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autocrafter.open",InputConstants.KEY_K,category));
        selectKey=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autocrafter.select",InputConstants.KEY_U,category));
        stopKey=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autocrafter.stop",InputConstants.KEY_O,category));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }
    private void tick(Minecraft mc) {
        String key=WorkshopConfig.worldKey(mc);
        if (!world.equals(key)) {
            if(recovery!=null)recovery.abort("World changed");recovery=null;lastReport=new ArrayList<>();
            scanner.stop("World changed. Scan discarded."); executor.stop("World changed. Review recovery log before continuing.");
            world=key; plan=null; selection=null; corner=null; clearContentReceipts();
            try { config=WorkshopConfig.load(key); }
            catch (Exception e) { config=new WorkshopConfig(); log("Could not load configuration. Existing file was preserved."); }
            scanner=new StorageScanner(this); executor=new WorkshopExecutor(this);
            try{var report=WorkshopConfig.directory().resolve(world+"-last-report.txt");if(Files.exists(report))lastReport=new ArrayList<>(Files.readAllLines(report));}catch(Exception e){log("Previous result report could not be loaded.");}
        }
        while (openKey.consumeClick()) if (mc.player!=null) {
            if(executor.busy() && executor.state!=WorkshopExecutor.State.PAUSED) executor.pause();
            else {if(scanner.running) scanner.requestStop();else open();}
        }
        while (stopKey.consumeClick()) { scanner.requestStop(); executor.requestStop(); }
        while (selectKey.consumeClick()) select(mc);
        try { if(recovery!=null&&recovery.busy())recovery.tick();else {scanner.tick(); executor.tick();} }
        catch (RuntimeException e) { scanner.stop("Operation stopped: " + e.getMessage()); executor.stop("Operation stopped: " + e.getMessage()); }
    }
    public void open() { Minecraft.getInstance().gui.setScreen(new WorkshopScreen(this)); }
    public void log(String message) {
        log.add(message); if(log.size()>200) log.removeFirst();
    }
    public void journal(String message) {
        try {
            Files.createDirectories(WorkshopConfig.directory());
            Files.writeString(WorkshopConfig.directory().resolve(world+"-recovery.log"), Instant.now()+" "+message+System.lineSeparator(), StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch (Exception e) { throw new IllegalStateException("Cannot write recovery log. No further item actions are allowed."); }
        log(message);
    }
    public void save() { try { config.save(world); } catch(Exception e) { log("Could not save configuration: " + e.getMessage()); } }
    public void select(Minecraft mc) {
        if(selection==null || mc.player==null || !(mc.hitResult instanceof BlockHitResult hit) || executor.busy() || scanner.running || recovering()) return;
        BlockPos pos=hit.getBlockPos();
        if(selection.equals("Staging")) { config.staging=WorkshopConfig.Point.of(pos.above()); selection=null; save(); log("Shulker staging position saved above the selected block."); return; }
        if(corner==null) { corner=pos.immutable(); mc.gui.hud.setOverlayMessage(Component.literal("First corner saved. Aim at the other corner and press U."),false); return; }
        var region=new WorkshopConfig.Region(selection,WorkshopConfig.Point.of(corner),WorkshopConfig.Point.of(pos));
        if(region.volume()>32768) { log("Selection too large. Maximum: 32,768 blocks."); corner=null; return; }
        if(config.regions.size()>=64) { log("Maximum 64 regions. Remove a region first."); return; }
        config.regions.add(region); selection=null; corner=null; plan=null; scanner.complete=false; save();
        mc.gui.hud.setOverlayMessage(Component.literal("Workshop region saved. Press K to open the workshop."),false);
    }
    public void makePlan() {
        if(executor.busy()) throw new IllegalArgumentException("Stop the active task before building another plan.");
        if(!scanner.complete) throw new IllegalArgumentException("Complete a fresh storage scan first.");
        var parsed=MaterialList.parse(config.materials,RecipeCatalog::resolve);
        if(!parsed.errors().isEmpty()) throw new IllegalArgumentException(parsed.errors().getFirst());
        if(parsed.entries().isEmpty()) throw new IllegalArgumentException("Add at least one material.");
        catalog=RecipeCatalog.read(Minecraft.getInstance());
        plan=new Planner(catalog.recipes()).plan(parsed.entries(),scanner.stock);
        log("Plan ready: " + plan.steps().size()+" crafts using "+catalog.layouts.size()+" known recipes. Review before starting.");
    }
    public void finishReport(Planner.Plan completedPlan) {
        lastReport=new ArrayList<>();lastReport.add("TASK RESULTS");
        Map<String,Integer> missing=new TreeMap<>();
        for(var target:completedPlan.targets()) {
            lastReport.add(target.item()+": requested "+target.requested()+", delivered "+target.available()+", not completed "+(target.requested()-target.available()));
            target.missing().forEach((id,n)->{lastReport.add("  Still needed: "+n+" "+id);missing.merge(id,n,Integer::sum);});
        }
        lastReport.add("RAW MATERIALS STILL NEEDED (shared stock and recipe batches counted once)");
        lastReport.add("For the selected known crafting recipes. Items without a usable recipe are listed as required supplies.");
        missing.forEach((id,n)->lastReport.add(n+" "+id));
        if(missing.isEmpty())lastReport.add("None");
        lastReport.forEach(this::log);
        try{Files.writeString(WorkshopConfig.directory().resolve(world+"-last-report.txt"),String.join(System.lineSeparator(),lastReport));}
        catch(Exception e){log("Could not save the result report; it is still available in Activity.");}
    }
    public void recoverShulker() {
        if(executor.busy()||scanner.running||recovering())throw new IllegalArgumentException("Stop the current operation before recovery.");
        recovery=new ShulkerAccess(this,new HashMap<>());
        recovery.recover(()->{log("Interrupted shulker operation recovered. Scan storage again before planning.");scanner.complete=false;open();},reason->{log(reason);open();});
    }
    public boolean recovering(){return recovery!=null&&recovery.busy();}
}

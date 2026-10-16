package nl.timonnl2.autocrafter;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;

public class WorkshopGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.setScreen(()->new WorkshopScreen(WorkshopClient.INSTANCE));
        context.waitTicks(5);
        context.takeScreenshot("workshop-materials");
        click(context,"Storage");context.waitTicks(2);context.takeScreenshot("workshop-storage");
        click(context,"Plan");context.waitTicks(2);context.takeScreenshot("workshop-plan");
        click(context,"Settings");context.waitTicks(2);context.takeScreenshot("workshop-settings");
        context.runOnClient(mc->mc.gui.setScreen(null));
        try(var world=context.worldBuilder().create()) {
            world.getConnection().waitForChunksDownload();
            var server=world.getServer();
            server.runCommand("fill -4 64 -4 4 64 4 minecraft:stone");
            server.runCommand("fill -4 65 -4 4 69 4 minecraft:air");
            server.runCommand("setblock 0 65 2 minecraft:chest");
            server.runCommand("setblock 2 65 0 minecraft:chest");
            server.runCommand("setblock -2 65 0 minecraft:crafting_table");
            server.runCommand("tp @a 0.5 65 0.5");
            server.runCommand("gamemode survival @a");
            server.runCommand("clear @a");
            server.runCommand("recipe give @a *");
            server.runOnServer(s->((ChestBlockEntity)s.overworld().getBlockEntity(new BlockPos(0,65,2))).setItem(0,new ItemStack(Items.IRON_BLOCK)));
            context.waitTicks(30);
            context.runOnClient(mc->{
                var app=WorkshopClient.INSTANCE;app.config=new WorkshopConfig();app.selection="Input";app.corner=null;
                mc.gui.setScreen(null);mc.mouseHandler.grabMouse();
                var a=new BlockPos(0,65,2);var b=new BlockPos(2,65,0);
                mc.hitResult=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(a),net.minecraft.core.Direction.UP,a,false);
                mc.mouseHandler.onButton(mc.getWindow().handle(),new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT,0),1);
                if(app.corner!=null||!app.config.regions.isEmpty())throw new AssertionError("Right click must wait for the first corner");
                mc.mouseHandler.onButton(mc.getWindow().handle(),new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0),1);
                if(!a.equals(app.corner))throw new AssertionError("Left click did not set first corner");
                mc.hitResult=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(b),net.minecraft.core.Direction.UP,b,false);
                mc.mouseHandler.onButton(mc.getWindow().handle(),new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT,0),1);
                if(app.config.regions.size()!=1||app.selection!=null||!app.config.regions.getFirst().contains(a)||!app.config.regions.getFirst().contains(b))throw new AssertionError("Right click did not save the selected box");
                if(mc.options.keyAttack.isDown()||mc.options.keyUse.isDown())throw new AssertionError("Selection click reached vanilla block interaction");
                mc.mouseHandler.onButton(mc.getWindow().handle(),new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0),0);
                mc.mouseHandler.onButton(mc.getWindow().handle(),new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT,0),0);
            });
            context.runOnClient(mc->{
                var app=WorkshopClient.INSTANCE;
                app.config=new WorkshopConfig();app.config.materials="minecraft:iron_ingot 9";
                app.config.regions.add(region("Input",0,65,2));app.config.regions.add(region("Output",2,65,0));app.config.regions.add(region("Work",-2,65,0));
                app.scanner.begin();
            });
            context.waitFor(mc->!WorkshopClient.INSTANCE.scanner.running,400);
            context.runOnClient(mc->{
                var app=WorkshopClient.INSTANCE;
                if(!app.scanner.complete)throw new AssertionError(String.join("\n",app.log));
                app.makePlan();
                if(app.plan.targets().getFirst().available()!=9)throw new AssertionError("Iron block unpacking was not planned");
                app.executor.start();
            });
            context.waitFor(mc->!WorkshopClient.INSTANCE.executor.busy(),1200);
            context.runOnClient(mc->{if(WorkshopClient.INSTANCE.executor.state!=WorkshopExecutor.State.COMPLETE)throw new AssertionError(String.join("\n",WorkshopClient.INSTANCE.log));});
            server.runOnServer(s->{
                var chest=(ChestBlockEntity)s.overworld().getBlockEntity(new BlockPos(2,65,0));
                int total=0;for(int i=0;i<chest.getContainerSize();i++)if(chest.getItem(i).is(Items.IRON_INGOT))total+=chest.getItem(i).getCount();
                if(total!=9)throw new AssertionError("Expected 9 iron ingots in output; got "+total);
            });
            context.takeScreenshot("workshop-complete");
            // A centered shaped recipe, recursively crafted from a log, into a placed shulker.
            server.runOnServer(s->((Container)s.overworld().getBlockEntity(new BlockPos(2,65,0))).clearContent());
            server.runCommand("setblock 2 65 0 minecraft:shulker_box");
            server.runOnServer(s->((ChestBlockEntity)s.overworld().getBlockEntity(new BlockPos(0,65,2))).setItem(0,new ItemStack(Items.OAK_LOG)));
            context.waitTicks(12);
            runJob(context,"minecraft:stick 8");
            assertOutput(world.getServer(),Items.STICK,8);
            // Fixed crafting remainders must survive the grid close/reopen checkpoint.
            server.runOnServer(s->((Container)s.overworld().getBlockEntity(new BlockPos(2,65,0))).clearContent());
            server.runOnServer(s->{
                var chest=(ChestBlockEntity)s.overworld().getBlockEntity(new BlockPos(0,65,2));
                chest.clearContent();chest.setItem(0,new ItemStack(Items.MILK_BUCKET));chest.setItem(1,new ItemStack(Items.MILK_BUCKET));chest.setItem(2,new ItemStack(Items.MILK_BUCKET));
                chest.setItem(3,new ItemStack(Items.WHEAT,3));chest.setItem(4,new ItemStack(Items.SUGAR,2));chest.setItem(5,new ItemStack(Items.EGG));
            });
            context.waitTicks(12);runJob(context,"minecraft:cake 1");assertOutput(server,Items.CAKE,1);assertOutput(server,Items.BUCKET,3);
            // A personal stack must block a new task before it moves anything.
            context.runOnClient(mc->WorkshopClient.INSTANCE.scanner.begin());
            context.waitFor(mc->!WorkshopClient.INSTANCE.scanner.running,400);
            context.runOnClient(mc->WorkshopClient.INSTANCE.makePlan());
            server.runCommand("give @a minecraft:diamond 1");context.waitTicks(5);
            context.runOnClient(mc->{
                var errors=WorkshopClient.INSTANCE.executor.preflight();
                if(errors.stream().noneMatch(e->e.contains("inventory")))throw new AssertionError("Personal inventory did not block execution");
            });
            server.runCommand("clear @a");
            server.runOnServer(s->{((Container)s.overworld().getBlockEntity(new BlockPos(2,65,0))).clearContent();((Container)s.overworld().getBlockEntity(new BlockPos(0,65,2))).setItem(0,new ItemStack(Items.IRON_BLOCK));});
            context.waitTicks(10);
            context.runOnClient(mc->{WorkshopClient.INSTANCE.config.materials="minecraft:iron_ingot 9";WorkshopClient.INSTANCE.scanner.begin();});
            context.waitFor(mc->!WorkshopClient.INSTANCE.scanner.running,400);
            context.runOnClient(mc->WorkshopClient.INSTANCE.makePlan());
            server.runOnServer(s->((Container)s.overworld().getBlockEntity(new BlockPos(0,65,2))).clearContent());
            context.runOnClient(mc->WorkshopClient.INSTANCE.executor.start());
            context.waitFor(mc->!WorkshopClient.INSTANCE.executor.busy(),400);
            context.runOnClient(mc->{
                if(WorkshopClient.INSTANCE.executor.state!=WorkshopExecutor.State.STOPPED || !WorkshopClient.INSTANCE.executor.detail.contains("Storage changed"))throw new AssertionError("Changed source was not rejected");
                for(int i=0;i<36;i++)if(!mc.player.getInventory().getItem(i).isEmpty())throw new AssertionError("Changed storage moved items");
            });
            // Packed source and output boxes must both return to their original chest slots.
            server.runCommand("setblock 2 65 0 minecraft:chest");
            server.runCommand("tp @a 0.5 65 0.5");
            server.runOnServer(s->{
                var input=(Container)s.overworld().getBlockEntity(new BlockPos(0,65,2));input.clearContent();
                var source=new ItemStack(Items.SHULKER_BOX);
                source.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.IRON_BLOCK))));
                input.setItem(3,source);
                var output=(Container)s.overworld().getBlockEntity(new BlockPos(2,65,0));output.clearContent();output.setItem(5,new ItemStack(Items.SHULKER_BOX));
            });
            context.waitTicks(12);
            context.runOnClient(mc->{var app=WorkshopClient.INSTANCE;app.config.staging=new WorkshopConfig.Point(0,65,-1);app.config.materials="minecraft:iron_ingot 12";app.scanner.begin();});
            context.waitFor(mc->!WorkshopClient.INSTANCE.scanner.running,400);
            context.runOnClient(mc->WorkshopClient.INSTANCE.scanner.beginBoxScan());
            context.waitFor(mc->!WorkshopClient.INSTANCE.scanner.running,2000);
            context.runOnClient(mc->{var app=WorkshopClient.INSTANCE;if(!app.scanner.complete)throw new AssertionError(String.join("\n",app.log));app.makePlan();app.executor.start();});
            context.waitFor(mc->!WorkshopClient.INSTANCE.executor.busy(),2400);
            context.runOnClient(mc->{
                var app=WorkshopClient.INSTANCE;if(app.executor.state!=WorkshopExecutor.State.COMPLETE)throw new AssertionError(String.join("\n",app.log));
                if(app.lastReport.stream().noneMatch(line->line.contains("requested 12, delivered 9, not completed 3")))throw new AssertionError("Missing partial completion report: "+app.lastReport);
                if(java.nio.file.Files.exists(ShulkerAccess.recoveryPath(app)))throw new AssertionError("Recovery record remained after success");
                for(int i=0;i<36;i++)if(!mc.player.getInventory().getItem(i).isEmpty())throw new AssertionError("Job inventory not empty after shulker return");
            });
            server.runOnServer(s->{
                var input=(Container)s.overworld().getBlockEntity(new BlockPos(0,65,2));var output=(Container)s.overworld().getBlockEntity(new BlockPos(2,65,0));
                if(!input.getItem(3).is(Items.SHULKER_BOX)||!output.getItem(5).is(Items.SHULKER_BOX))throw new AssertionError("Shulkers not returned to original slots");
                var contents=output.getItem(5).get(net.minecraft.core.component.DataComponents.CONTAINER);
                if(contents==null||contents.nonEmptyItemCopyStream().filter(item->item.is(Items.IRON_INGOT)).mapToInt(ItemStack::getCount).sum()!=9)throw new AssertionError("Output shulker must contain 9 ingots");
                if(!s.overworld().getBlockState(new BlockPos(0,65,-1)).isAir())throw new AssertionError("Staged box not recovered");
            });
            // One item per box: two different targets use different reserved output slots.
            server.runCommand("tp @a 0.5 65 0.5");
            server.runOnServer(s->{
                var input=(Container)s.overworld().getBlockEntity(new BlockPos(0,65,2));input.clearContent();input.setItem(0,new ItemStack(Items.IRON_INGOT,2));input.setItem(1,new ItemStack(Items.GOLD_INGOT,3));
                var output=(Container)s.overworld().getBlockEntity(new BlockPos(2,65,0));output.clearContent();output.setItem(4,new ItemStack(Items.SHULKER_BOX));output.setItem(7,new ItemStack(Items.SHULKER_BOX));
            });
            context.waitTicks(12);
            context.runOnClient(mc->{var app=WorkshopClient.INSTANCE;app.config.oneItemPerBox=true;app.config.materials="minecraft:iron_ingot 2\nminecraft:gold_ingot 3";app.scanner.begin();});
            context.waitFor(mc->!WorkshopClient.INSTANCE.scanner.running,400);
            context.runOnClient(mc->WorkshopClient.INSTANCE.scanner.beginBoxScan());
            context.waitFor(mc->!WorkshopClient.INSTANCE.scanner.running,2000);
            context.runOnClient(mc->{var app=WorkshopClient.INSTANCE;if(!app.scanner.complete)throw new AssertionError(String.join("\n",app.log));app.makePlan();app.executor.start();});
            context.waitFor(mc->!WorkshopClient.INSTANCE.executor.busy(),2000);
            context.runOnClient(mc->{if(WorkshopClient.INSTANCE.executor.state!=WorkshopExecutor.State.COMPLETE)throw new AssertionError(String.join("\n",WorkshopClient.INSTANCE.log));});
            server.runOnServer(s->{
                var output=(Container)s.overworld().getBlockEntity(new BlockPos(2,65,0));int iron=0,gold=0;
                for(int slot:new int[]{4,7}) {
                    var box=output.getItem(slot);if(!box.is(Items.SHULKER_BOX))throw new AssertionError("Sorted box missing");
                    var contents=box.get(net.minecraft.core.component.DataComponents.CONTAINER).nonEmptyItemCopyStream().toList();
                    if(contents.size()!=1)throw new AssertionError("Mixed items in a single-item box");
                    if(contents.getFirst().is(Items.IRON_INGOT))iron+=contents.getFirst().getCount();
                    if(contents.getFirst().is(Items.GOLD_INGOT))gold+=contents.getFirst().getCount();
                }
                if(iron!=2||gold!=3)throw new AssertionError("Sorted output quantities incorrect");
            });
            // Interrupt a physical scan with a box placed, then recover from the durable record.
            server.runCommand("tp @a 0.5 65 0.5");context.waitTicks(10);
            context.runOnClient(mc->WorkshopClient.INSTANCE.scanner.begin());
            context.waitFor(mc->!WorkshopClient.INSTANCE.scanner.running,400);
            context.runOnClient(mc->WorkshopClient.INSTANCE.scanner.beginBoxScan());
            context.waitFor(mc->mc.player.containerMenu instanceof net.minecraft.world.inventory.ShulkerBoxMenu && WorkshopClient.contentReceived(mc.player.containerMenu.containerId),400);
            context.runOnClient(mc->{var app=WorkshopClient.INSTANCE;app.scanner.stop("Simulated interrupted inspection");app.recoverShulker();});
            context.waitFor(mc->!WorkshopClient.INSTANCE.recovering(),1200);
            context.runOnClient(mc->{var app=WorkshopClient.INSTANCE;if(java.nio.file.Files.exists(ShulkerAccess.recoveryPath(app)))throw new AssertionError("Interrupted box recovery failed: "+String.join("\n",app.log));});
            server.runOnServer(s->{
                var output=(Container)s.overworld().getBlockEntity(new BlockPos(2,65,0));
                if(!output.getItem(4).is(Items.SHULKER_BOX)||!output.getItem(7).is(Items.SHULKER_BOX)||!s.overworld().getBlockState(new BlockPos(0,65,-1)).isAir())throw new AssertionError("Recovery did not restore original box slots");
            });
        }
    }
    private static void runJob(ClientGameTestContext context,String materials) {
        context.runOnClient(mc->{var app=WorkshopClient.INSTANCE;app.config.materials=materials;app.scanner.begin();});
        context.waitFor(mc->!WorkshopClient.INSTANCE.scanner.running,400);
        context.runOnClient(mc->{var app=WorkshopClient.INSTANCE;if(!app.scanner.complete)throw new AssertionError(String.join("\n",app.log));app.makePlan();});
        click(context,"Plan");context.waitTicks(2);context.takeScreenshot("workshop-targets");
        context.runOnClient(mc->WorkshopClient.INSTANCE.executor.start());
        context.waitFor(mc->!WorkshopClient.INSTANCE.executor.busy(),1600);
        context.runOnClient(mc->{if(WorkshopClient.INSTANCE.executor.state!=WorkshopExecutor.State.COMPLETE)throw new AssertionError(String.join("\n",WorkshopClient.INSTANCE.log));});
    }
    private static void assertOutput(net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext server,net.minecraft.world.item.Item item,int expected) {
        server.runOnServer(s->{var container=(Container)s.overworld().getBlockEntity(new BlockPos(2,65,0));int total=0;
            for(int i=0;i<container.getContainerSize();i++)if(container.getItem(i).is(item))total+=container.getItem(i).getCount();
            if(total!=expected)throw new AssertionError("Wrong output for "+item+": "+total+" instead of "+expected);
        });
    }
    private static WorkshopConfig.Region region(String kind,int x,int y,int z) {var p=new WorkshopConfig.Point(x,y,z);return new WorkshopConfig.Region(kind,p,p);}
    private static void click(ClientGameTestContext context,String label) {
        context.runOnClient(mc->{
            var button=mc.gui.screen().children().stream().filter(c->c instanceof AbstractButton b && b.getMessage().getString().equals(label)).findFirst().orElseThrow();
            var widget=(AbstractButton)button;
            var event=new MouseButtonEvent(widget.getX()+widget.getWidth()/2.0,widget.getY()+widget.getHeight()/2.0,new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));
            mc.gui.screen().mouseClicked(event,false);
        });
    }
}

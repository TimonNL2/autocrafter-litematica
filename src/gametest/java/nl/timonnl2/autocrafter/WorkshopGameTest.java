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

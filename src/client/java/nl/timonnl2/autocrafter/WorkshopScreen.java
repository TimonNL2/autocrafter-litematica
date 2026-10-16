package nl.timonnl2.autocrafter;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import nl.timonnl2.autocrafter.core.MaterialList;
import java.util.*;

/** A compact workshop dashboard with purpose-built navigation, cards and tables. */
public final class WorkshopScreen extends Screen {
    private static final int BG=0xff0c121c, PANEL=0xff141f2d, EDGE=0xff26384a, TEXT=0xffedf4fb, MUTED=0xff9bb0c6, MINT=0xff60dfc2, AMBER=0xffffcc80;
    private static final String[] TABS={"Materials","Storage","Plan","Activity","Settings"};
    private final WorkshopClient app;
    private int tab, scroll;
    private boolean preview;
    private boolean listView,planDetails;
    private MultiLineEditBox editor;
    private int left, top, contentWidth, bottom;
    private String notice="";
    private List<String> checks=List.of();
    public WorkshopScreen(WorkshopClient app) { super(Component.literal("Autocrafter Workshop")); this.app=app; }
    @Override protected void init() {
        left=16; contentWidth=width-32; top=108; bottom=height-44;
        if(width<340 || height<240) return;
        int navWidth=(contentWidth-16)/5;
        for(int i=0;i<TABS.length;i++) {
            final int index=i;
            button(left+i*(navWidth+4),43,navWidth,23,TABS[i],"Open "+TABS[i].toLowerCase(Locale.ROOT),()->{saveEditor();tab=index;scroll=0;preview=false;rebuildWidgets();},tab==i);
        }
        if(tab==0 && !listView) {
            editor=MultiLineEditBox.builder().setX(left+10).setY(top+26).setShowBackground(false).setShowDecorations(false).setTextColor(TEXT)
                .setPlaceholder(Component.literal("minecraft:repeater 64\nminecraft:comparator 32"))
                .build(font,contentWidth-20,Math.max(30,bottom-top-72),Component.literal("Material list: item identifier and quantity per line"));
            editor.setCharacterLimit(100_000); editor.setValue(app.config.materials); addRenderableWidget(editor);
            button(left+8,bottom-32,94,24,"Import clipboard","Paste item IDs, English names or a Litematica text/CSV export",()->{
                editor.setValue(minecraft.keyboardHandler.getClipboard()); validate();
            },false);
            button(left+108,bottom-32,80,24,"Save list","Validate every line before saving",this::validate,true);
            button(left+196,bottom-32,80,24,"List view","Show item icons, requested quantities and planned availability",()->{saveEditor();listView=true;rebuildWidgets();},false);
        } else if(tab==0) {
            button(left+8,bottom-32,80,24,"Edit list","Return to material text input",()->{listView=false;rebuildWidgets();},true);
        } else if(tab==1) {
            int bw=(contentWidth-24)/4;
            String[] kinds={"Input","Output","Work","Staging"};
            for(int i=0;i<4;i++) { String kind=kinds[i]; button(left+i*(bw+8),top,bw,23,"+ "+kind,"Aim at blocks and press U to select corners",()->{
                if(app.executor.busy()) {notice="Stop the active task before editing regions.";return;}
                app.selection=kind;app.corner=null;onClose();
                minecraft.gui.hud.setOverlayMessage(Component.literal(kind.equals("Staging")?"Aim at a support block and press U.":"Aim at the first corner block and press U, then the second corner."),false);
            },false); }
            button(left,bottom-28,88,24,"Scan storage","Opens selected containers; does not move items",()->safe(()->app.scanner.begin()),true);
            button(left+96,bottom-28,106,24,"Remove last region","Remove the most recently added region",()->{
                if(app.executor.busy()) {notice="Stop the active task before editing regions.";return;}
                if(!app.config.regions.isEmpty()) {app.config.regions.removeLast();app.save();app.plan=null;app.scanner.complete=false;}
            },false);
        } else if(tab==2) {
            button(left,bottom-28,80,24,"Build plan","Compute an achievable plan from the latest scan",()->safe(()->{saveEditor();app.makePlan();preview=false;}),true);
            button(left+88,bottom-28,88,24,preview?"Confirm start":"Review moves","Review all source transfers and preflight checks before starting",()->safe(()->{
                if(preview) {app.executor.start();return;}
                checks=app.executor.preflight();preview=true;scroll=0;rebuildWidgets();
            }),preview);
            if(preview) button(left+184,bottom-28,66,24,"Back","Return to the recipe plan",()->{preview=false;scroll=0;rebuildWidgets();},false);
            else button(left+184,bottom-28,84,24,planDetails?"Targets":"Details","Switch between target quantities and detailed recipes/stock",()->{planDetails=!planDetails;scroll=0;rebuildWidgets();},false);
        } else if(tab==3) {
            button(left,bottom-28,70,24,"Pause","Pause after the current server-verified transaction",()->app.executor.pause(),false);
            button(left+78,bottom-28,72,24,"Resume","Revalidate job inventory and continue",()->safe(()->app.executor.resume()),true);
            button(left+158,bottom-28,82,24,"Safe stop","Stop issuing actions; keep job items available for recovery",()->{app.scanner.stop("Scan stopped.");app.executor.stop("Stopped by user");},false);
            button(left+248,bottom-28,64,24,"Copy log","Copy the activity log to the clipboard",()->minecraft.keyboardHandler.setClipboard(String.join("\n",app.log)),false);
        } else {
            int settingsWidth=(contentWidth-32)/2;
            button(left+12,top+24,settingsWidth,24,"Outlines: "+(app.config.showRegions?"on":"off"),"Show selected region borders, corners and labels",()->{app.config.showRegions=!app.config.showRegions;app.save();rebuildWidgets();},false);
            button(left+20+settingsWidth,top+24,settingsWidth,24,"Delay: "+app.config.delayTicks+" ticks","Cycle between 8, 12, 20 and 40 ticks",()->{
                app.config.delayTicks=app.config.delayTicks==8?12:app.config.delayTicks==12?20:app.config.delayTicks==20?40:8;app.save();rebuildWidgets();
            },false);
        }
    }
    private void validate() {
        var parsed=MaterialList.parse(editor.getValue(),RecipeCatalog::resolve);
        if(!parsed.errors().isEmpty()) {notice=parsed.errors().getFirst();return;}
        app.config.materials=editor.getValue();app.plan=null;app.save();notice="Saved "+parsed.entries().size()+" material types.";
    }
    private void saveEditor() {
        if(tab==0 && editor!=null && !editor.getValue().equals(app.config.materials)) {
            var parsed=MaterialList.parse(editor.getValue(),RecipeCatalog::resolve);
            if(parsed.errors().isEmpty()) {app.config.materials=editor.getValue();app.plan=null;app.save();}
            else notice=parsed.errors().getFirst();
        }
        editor=null;
    }
    private void safe(Runnable action) { try {action.run();notice="";} catch(RuntimeException e) {notice=e.getMessage();app.log(notice);} }
    private void button(int x,int y,int w,int h,String label,String tip,Runnable action,boolean accent) {
        var button=new WorkshopButton(x,y,w,h,label,action,accent);button.setTooltip(Tooltip.create(Component.literal(tip)));addRenderableWidget(button);
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta) {g.fill(0,0,width,height,BG);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta) {
        if(width<340 || height<240) { g.textWithWordWrap(font,Component.literal("Autocrafter Workshop needs more space. Reduce GUI Scale in Minecraft Video Settings, then reopen with K."),16,30,width-32,TEXT);return; }
        g.fill(0,0,width,2,MINT);
        g.text(font,"AUTOCRAFTER",left,15,TEXT,false);
        g.text(font,"WORKSHOP / 26.3",left+94,16,MUTED,false);
        String badge="ALPHA 0.1";g.text(font,badge,width-16-font.width(badge),16,AMBER,false);
        int card=(contentWidth-16)/3;
        stat(g,left,76,card,"STORAGE",app.scanner.complete?app.scanner.containers.size()+" scanned":"Scan required");
        stat(g,left+card+8,76,card,"RECIPES",app.catalog.layouts.size()+" known");
        stat(g,left+2*(card+8),76,card,"TASK",app.executor.state.toString().replace('_',' '));
        if(tab==0) {
            panel(g,left,top,contentWidth,bottom-top);
            g.text(font,"MATERIAL LIST",left+10,top+10,MINT,false);
            if(listView) renderTargets(g,top+18,bottom-32);
        } else if(tab==1) renderStorage(g);
        else if(tab==2) renderPlan(g);
        else if(tab==3) renderActivity(g);
        else renderSettings(g);
        g.fill(0,height-34,width,height,0xff101b28);
        String status=notice.isEmpty()?app.executor.busy()?app.executor.detail:app.log.isEmpty()?"K  Workshop    U  Select corner    O  Safe stop":app.log.getLast():notice;
        g.text(font,font.plainSubstrByWidth(status,width-32),left,height-23,notice.isEmpty()?MUTED:AMBER,false);
        super.extractRenderState(g,mouseX,mouseY,delta);
    }
    private void stat(GuiGraphicsExtractor g,int x,int y,int w,String name,String value) {
        g.fill(x,y,x+w,y+25,PANEL);g.fill(x,y,x+2,y+25,EDGE);
        g.text(font,name,x+8,y+3,MUTED,false);g.text(font,font.plainSubstrByWidth(value,w-16),x+8,y+14,TEXT,false);
    }
    private void panel(GuiGraphicsExtractor g,int x,int y,int w,int h) {g.fill(x,y,x+w,y+h,PANEL);g.outline(x,y,w,h,EDGE);}
    private void renderStorage(GuiGraphicsExtractor g) {
        List<String> lines=new ArrayList<>();
        lines.add("REGIONS  /  select corners with U");
        for(var r:app.config.regions) lines.add(r.kind()+"   "+r.min().toShortString()+"  ->  "+r.max().toShortString());
        if(app.config.regions.isEmpty()) lines.add("Add Input, Output and Work regions to begin.");
        lines.add("");lines.add("CONTAINERS  /  server-confirmed contents");
        for(var c:app.scanner.containers) lines.add(c.kind()+"   "+c.pos().toShortString()+"   "+c.items().stream().filter(s->!s.isEmpty()).count()+" occupied slots");
        lines.addAll(app.scanner.shulkers);lines.add("");
        lines.add("Packed shulkers are inspection-only. Place them in a selected region to use their contents.");
        lines(g,lines,top+34,bottom-36);
    }
    private void renderPlan(GuiGraphicsExtractor g) {
        if(preview) {
            List<String> lines=new ArrayList<>();lines.add("REVIEW ITEM MOVEMENTS");lines.addAll(app.executor.movements());lines.add("");
            if(checks.isEmpty()) lines.add("Ready. Confirm start authorizes the movements listed above.");
            else {lines.add("START BLOCKED");lines.addAll(checks);}
            lines(g,lines,top,bottom-36);return;
        }
        if(app.plan==null) {lines(g,List.of("PRODUCTION PLAN","Import materials, scan storage, then build a plan.","Only recipes received from this server are used.","Targets are processed in list order."),top,bottom-36);return;}
        if(!planDetails) {renderTargets(g,top,bottom-36);return;}
        List<String> lines=new ArrayList<>();
        lines.add("TARGET                         READY / REQUESTED");
        for(var t:app.plan.targets()) {
            lines.add(t.item()+"   "+t.available()+" / "+t.requested());
            t.missing().forEach((id,n)->lines.add("  Missing: "+n+" "+id));
        }
        lines.add("");lines.add("CRAFTING STEPS  /  "+app.plan.steps().size());
        for(var s:app.plan.steps()) lines.add("+"+s.count()+" "+s.output()+"  <-  "+String.join(", ",s.ingredients()));
        lines.add("");lines.add("INPUT STOCK BEFORE / UNRESERVED AFTER");
        app.plan.before().forEach((id,n)->lines.add(id+"   "+n+" / "+app.plan.after().getOrDefault(id,0)));
        lines(g,lines,top,bottom-36);
    }
    private void renderActivity(GuiGraphicsExtractor g) {
        List<String> lines=new ArrayList<>();lines.add(app.executor.detail);lines.add("Verified crafts: "+app.executor.completedCrafts);lines.add("");
        List<String> reversed=new ArrayList<>(app.log);Collections.reverse(reversed);lines.addAll(reversed);
        lines(g,lines,top,bottom-36);
    }
    private void renderTargets(GuiGraphicsExtractor g,int y,int end) {
        var entries=MaterialList.parse(app.config.materials,RecipeCatalog::resolve).entries();
        int visible=Math.max(1,(end-y)/42);scroll=Math.clamp(scroll,0,Math.max(0,entries.size()-visible));
        g.enableScissor(left,y,left+contentWidth,end);
        for(int index=scroll;index<Math.min(entries.size(),scroll+visible);index++) {
            var entry=entries.get(index);int row=y+(index-scroll)*42;
            var target=app.plan==null?null:app.plan.targets().stream().filter(t->t.item().equals(entry.item())).findFirst().orElse(null);
            g.fill(left,row,left+contentWidth,row+37,PANEL);
            g.fill(left,row,left+2,row+37,target!=null&&target.available()<target.requested()?AMBER:MINT);
            g.item(RecipeCatalog.stack(entry.item()),left+9,row+9);
            String quantity=(target==null?"—":Integer.toString(target.available()))+" / "+entry.count();
            int qtyWidth=font.width(quantity);
            g.text(font,font.plainSubstrByWidth(RecipeCatalog.stack(entry.item()).getHoverName().getString(),contentWidth-qtyWidth-64),left+34,row+6,TEXT,false);
            g.text(font,quantity,left+contentWidth-qtyWidth-10,row+6,target!=null&&target.available()<target.requested()?AMBER:MINT,false);
            String sub=target==null?entry.item():target.missing().isEmpty()?"Ready to collect / craft":target.missing().entrySet().stream().map(e->e.getValue()+" "+e.getKey().replace("minecraft:","")).collect(java.util.stream.Collectors.joining(", ","Missing: ",""));
            g.text(font,font.plainSubstrByWidth(sub,contentWidth-46),left+34,row+20,MUTED,false);
            if(target!=null)g.fill(left+34,row+33,left+34+(contentWidth-44)*target.available()/Math.max(1,target.requested()),row+35,MINT);
        }
        g.disableScissor();
        if(entries.isEmpty())g.text(font,"No materials saved yet. Import a list to begin.",left+8,y+4,MUTED,false);
    }
    private void renderSettings(GuiGraphicsExtractor g) {
        panel(g,left,top,contentWidth,bottom-top);
        g.text(font,"WORKSHOP PREFERENCES",left+12,top+12,MINT,false);
        List<String> lines=List.of("Personal inventory: protected; empty job buffer required.","Shulkers: inspect packed boxes; use placed boxes as storage.","Automatic box placement / mining: unavailable in this alpha.","Output: products, surplus and remainders go to the reviewed output.","Configuration and recovery logs stay on this computer.","No telemetry. No server mod. No Litematica dependency.");
        lines(g,lines,top+56,bottom-4);
    }
    private void lines(GuiGraphicsExtractor g,List<String> lines,int y,int end) {
        g.enableScissor(left,y,left+contentWidth,end);
        List<String> wrapped=new ArrayList<>();
        for(String text:lines) {
            if(text.isEmpty()) {wrapped.add("");continue;}
            while(!text.isEmpty()) {String part=font.plainSubstrByWidth(text,contentWidth-28);if(part.isEmpty())break;wrapped.add(part);text=text.substring(part.length());}
        }
        int count=Math.max(1,(end-y)/16);scroll=Math.clamp(scroll,0,Math.max(0,wrapped.size()-count));
        for(int i=scroll;i<Math.min(wrapped.size(),scroll+count);i++) {
            int rowY=y+(i-scroll)*16;
            if((i&1)==0)g.fill(left,rowY,left+contentWidth-6,rowY+15,PANEL);
            String line=wrapped.get(i);g.text(font,line,left+8,rowY+3,line.startsWith("  Missing")||line.equals("START BLOCKED")?AMBER:TEXT,false);
        }
        if(wrapped.size()>count) {int h=Math.max(10,(end-y)*count/wrapped.size());int offset=(end-y-h)*scroll/Math.max(1,wrapped.size()-count);g.fill(left+contentWidth-3,y+offset,left+contentWidth,y+offset+h,MINT);}
        g.disableScissor();
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(tab==0&&!listView)return super.mouseScrolled(x,y,horizontal,vertical);scroll=Math.max(0,scroll-(int)(vertical*3));return true;
    }
    @Override public void onClose() {saveEditor();super.onClose();}
    @Override public boolean isPauseScreen() {return false;}

    private static final class WorkshopButton extends AbstractButton {
        private final Runnable action; private final boolean accent;
        WorkshopButton(int x,int y,int w,int h,String label,Runnable action,boolean accent) {super(x,y,w,h,Component.literal(label));this.action=action;this.accent=accent;}
        @Override public void onPress(InputWithModifiers input) {action.run();}
        @Override protected void extractContents(GuiGraphicsExtractor g,int x,int y,float delta) {
            int color=isHoveredOrFocused()?0xff2d4657:accent?0xff234a47:PANEL;
            g.fill(getX(),getY(),getRight(),getBottom(),color);
            g.outline(getX(),getY(),getWidth(),getHeight(),isFocused()?TEXT:accent?MINT:EDGE);
            var font=net.minecraft.client.Minecraft.getInstance().font;
            String label=font.plainSubstrByWidth(getMessage().getString(),getWidth()-10);
            g.text(font,label,getX()+(getWidth()-font.width(label))/2,getY()+(getHeight()-8)/2,accent?MINT:TEXT,false);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) {defaultButtonNarrationText(output);}
    }
}

package nl.timonnl2.autocrafter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.recipebook.PlaceRecipeHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.display.*;
import nl.timonnl2.autocrafter.core.Planner;
import java.util.*;

public final class RecipeCatalog {
    private record DisplayItem(ItemStack stack,ItemStack remainder,boolean supported) {}
    private static final class DisplayReader implements DisplayContentsFactory.ForStacks<DisplayItem>,DisplayContentsFactory.ForRemainders<DisplayItem> {
        public DisplayItem forStack(ItemStack stack){
            var template=stack.getItem().getCraftingRemainder();
            ItemStack remainder=template==null?ItemStack.EMPTY:template.create();
            return new DisplayItem(stack,remainder,remainder.isEmpty()||plain(remainder));
        }
        public DisplayItem addRemainder(DisplayItem input,List<DisplayItem> remainders){
            if(remainders.size()!=1)return new DisplayItem(input.stack(),ItemStack.EMPTY,false);
            var remainder=remainders.getFirst();
            return new DisplayItem(input.stack(),remainder.stack(),input.supported()&&remainder.supported()&&remainder.remainder().isEmpty()&&(remainder.stack().isEmpty()||plain(remainder.stack())));
        }
    }
    public record Layout(RecipeDisplayId displayId, List<Integer> gridSlots, Planner.Recipe recipe) {}
    public final Map<String, Layout> layouts = new LinkedHashMap<>();
    public final List<String> excluded = new ArrayList<>();
    public static String id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    public static ItemStack stack(String id) { return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id))); }
    public static String resolve(String name) {
        String normalized = name.strip().toLowerCase(Locale.ROOT);
        Identifier identifier = Identifier.tryParse(normalized.contains(":") ? normalized : "minecraft:" + normalized.replace(' ', '_'));
        if (identifier != null && BuiltInRegistries.ITEM.containsKey(identifier)) return identifier.toString();
        String result = null;
        for (Item item : BuiltInRegistries.ITEM) if (new ItemStack(item).getHoverName().getString().equalsIgnoreCase(name)) {
            if (result != null) return null;
            result = BuiltInRegistries.ITEM.getKey(item).toString();
        }
        return result;
    }
    public static RecipeCatalog read(Minecraft mc) {
        RecipeCatalog catalog = new RecipeCatalog();
        if (mc.player == null || mc.level == null) return catalog;
        var context = SlotDisplayContext.fromLevel(mc.level);
        var entries = mc.player.getRecipeBook().getCollections().stream().flatMap(c -> c.getRecipes().stream()).sorted(Comparator.comparingInt(e -> e.id().index())).toList();
        for (var entry : entries) {
            List<SlotDisplay> displays; int width,height;
            if (entry.display() instanceof ShapedCraftingRecipeDisplay shaped) { displays = shaped.ingredients(); width = shaped.width(); height=shaped.height(); }
            else if (entry.display() instanceof ShapelessCraftingRecipeDisplay shapeless) { displays = shapeless.ingredients(); width = 3; height=3; }
            else continue;
            List<ItemStack> outputs = entry.display().result().resolveForStacks(context);
            if (outputs.size() != 1 || outputs.getFirst().isEmpty()) continue;
            ItemStack output = outputs.getFirst();
            List<Planner.Ingredient> ingredients = new ArrayList<>(); List<Integer> slots = new ArrayList<>();
            Map<String, Integer> remainders = new HashMap<>(); boolean supported = true;
            Map<Integer,Integer> positions=new HashMap<>();
            PlaceRecipeHelper.placeRecipe(3,3,width,height,java.util.stream.IntStream.range(0,displays.size()).boxed().toList(),(index,grid,x,y)->positions.put(index,grid+1));
            for (int i=0; i<displays.size(); i++) {
                SlotDisplay display = displays.get(i);
                if (display instanceof SlotDisplay.Empty) continue;
                var resolved=display.resolve(context,new DisplayReader()).toList();
                if(resolved.isEmpty()||resolved.stream().anyMatch(option->!option.supported()||!ItemStack.matches(option.remainder(),resolved.getFirst().remainder()))){supported=false;break;}
                var remainder=resolved.getFirst().remainder();
                if(!remainder.isEmpty())remainders.merge(id(remainder),remainder.getCount(),Integer::sum);
                List<ItemStack> options = resolved.stream().map(DisplayItem::stack).toList();
                if (options.isEmpty() || options.stream().anyMatch(s -> s.isEmpty() || !plain(s))) { supported=false; break; }
                ingredients.add(new Planner.Ingredient(options.stream().map(RecipeCatalog::id).distinct().toList()));
                slots.add(positions.get(i));
            }
            if (!supported || ingredients.isEmpty() || !plain(output)) { catalog.excluded.add(id(output)); continue; }
            String key = Integer.toString(entry.id().index());
            Planner.Recipe recipe = new Planner.Recipe(key, id(output), output.getCount(), ingredients, remainders);
            catalog.layouts.put(key, new Layout(entry.id(), List.copyOf(slots), recipe));
        }
        return catalog;
    }
    /** Component-specific stacks must not be collapsed into a plain item-ID stock. */
    public static boolean plain(ItemStack stack) { return ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem())); }
    public List<Planner.Recipe> recipes() { return layouts.values().stream().map(Layout::recipe).toList(); }
}

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
                if (display instanceof SlotDisplay.WithRemainder remainder) {
                    var rests = remainder.remainder().resolveForStacks(context);
                    if (rests.size() != 1) { supported = false; break; }
                    if (!rests.getFirst().isEmpty()) remainders.merge(id(rests.getFirst()), rests.getFirst().getCount(), Integer::sum);
                    display = remainder.input();
                }
                List<ItemStack> options = display.resolveForStacks(context);
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

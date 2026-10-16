package nl.timonnl2.autocrafter.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static nl.timonnl2.autocrafter.core.Planner.Ingredient.of;

class PlannerTest {
    @Test void shortagesDoNotReuseIngredientsAllocatedToLaterTargets() {
        var p=new Planner(List.of(recipe("a","a",1,of("iron"),of("diamond")),recipe("b","b",1,of("iron"))));
        var plan=p.plan(List.of(request("a",1),request("b",1)),Map.of("iron",1));
        assertEquals(Map.of("iron",1,"diamond",1),plan.targets().getFirst().missing());
        assertEquals(1,plan.targets().get(1).available());
    }
    @Test void shortageShoppingListSharesBatchSurplusAcrossTargets() {
        var p=new Planner(List.of(recipe("planks","plank",4,of("log")),recipe("sticks","stick",4,of("plank"),of("plank"))));
        var plan=p.plan(List.of(request("stick",4),request("plank",2)),Map.of());
        assertEquals(Map.of("log",1),plan.targets().getFirst().missing());
        assertTrue(plan.targets().get(1).missing().isEmpty());
        assertEquals(0,plan.targets().get(1).available());
    }
    private Planner.Recipe recipe(String id,String result,int count,Planner.Ingredient... slots) {return new Planner.Recipe(id,result,count,List.of(slots),Map.of());}
    private MaterialList.Entry request(String item,int count) {return new MaterialList.Entry(item,count);}
    @Test void recursiveCraftingUsesBatchRemainders() {
        var p=new Planner(List.of(recipe("planks","plank",4,of("log")),recipe("sticks","stick",4,of("plank"),of("plank"))));
        var plan=p.plan(List.of(request("stick",5)),Map.of("log",1));
        assertEquals(5,plan.targets().getFirst().available());assertEquals(3,plan.after().get("stick"));assertEquals(3,plan.steps().size());
    }
    @Test void sharedStockCannotBeSpentTwice() {
        var p=new Planner(List.of(recipe("a","a",1,of("iron")),recipe("b","b",1,of("iron"))));
        var plan=p.plan(List.of(request("a",2),request("b",2)),Map.of("iron",3));
        assertEquals(2,plan.targets().get(0).available());assertEquals(1,plan.targets().get(1).available());assertEquals(Map.of("iron",1),plan.targets().get(1).missing());
    }
    @Test void failedRecipeDoesNotConsumeIngredients() {
        var p=new Planner(List.of(recipe("first","a",1,of("iron"),of("diamond")),recipe("second","b",1,of("iron"))));
        var plan=p.plan(List.of(request("a",1),request("b",1)),Map.of("iron",1));
        assertEquals(0,plan.targets().get(0).available());assertEquals(1,plan.targets().get(1).available());assertEquals(1,plan.steps().size());
    }
    @Test void alternativeRecipesAndIngredientsBacktrack() {
        var p=new Planner(List.of(recipe("bad","a",1,of("diamond")),recipe("good","a",1,of("oak","birch"),of("oak"))));
        var plan=p.plan(List.of(request("a",1)),Map.of("oak",1,"birch",1));
        assertEquals(1,plan.targets().getFirst().available());assertEquals(List.of("birch","oak"),plan.steps().getFirst().ingredients());
    }
    @Test void unpackingAndCyclesConserveIron() {
        var pack=recipe("pack","block",1,Collections.nCopies(9,of("iron")).toArray(Planner.Ingredient[]::new));
        var unpack=recipe("unpack","iron",9,of("block"));
        var p=new Planner(List.of(pack,unpack));
        var plan=p.plan(List.of(request("iron",10)),Map.of("block",1));
        assertEquals(9,plan.targets().getFirst().available());assertEquals(Map.of("iron",1),plan.targets().getFirst().missing());
        assertEquals(1,plan.steps().size());
    }
    @Test void recursiveShortagesAreRawAndBatchAware() {
        var p=new Planner(List.of(recipe("planks","plank",4,of("log")),recipe("sticks","stick",4,of("plank"),of("plank"))));
        var plan=p.plan(List.of(request("stick",8)),Map.of());
        assertEquals(Map.of("log",1),plan.targets().getFirst().missing());
    }
    @Test void returnedContainersCanBeUsedByLaterTargets() {
        var r=new Planner.Recipe("cake","cake",1,List.of(of("milk")),Map.of("bucket",1));
        var plan=new Planner(List.of(r)).plan(List.of(request("cake",1),request("bucket",1)),Map.of("milk",1));
        assertEquals(1,plan.targets().get(1).available());
    }
    @Test void existingFinishedItemsNeedNoCrafts() {
        var plan=new Planner(List.of()).plan(List.of(request("repeater",4)),Map.of("repeater",6));
        assertEquals(4,plan.targets().getFirst().available());assertTrue(plan.steps().isEmpty());assertEquals(2,plan.after().get("repeater"));
    }
    @Test void emptyCyclicGraphTerminates() {
        var plan=new Planner(List.of(recipe("a","a",1,of("b")),recipe("b","b",1,of("a")))).plan(List.of(request("a",1)),Map.of());
        assertEquals(0,plan.targets().getFirst().available());assertFalse(plan.targets().getFirst().missing().isEmpty());
    }
}

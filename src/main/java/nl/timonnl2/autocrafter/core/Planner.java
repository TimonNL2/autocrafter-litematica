package nl.timonnl2.autocrafter.core;

import java.util.*;

/** Deterministic, inventory-conserving planner. Target order is the explicit priority. */
public final class Planner {
    public record Ingredient(List<String> alternatives) {
        public Ingredient { alternatives = List.copyOf(alternatives); if (alternatives.isEmpty()) throw new IllegalArgumentException("Empty ingredient"); }
        public static Ingredient of(String... items) { return new Ingredient(List.of(items)); }
    }
    public record Recipe(String id, String output, int count, List<Ingredient> slots, Map<String, Integer> remainders) {
        public Recipe {
            if (count < 1 || slots.isEmpty() || slots.size() > 9) throw new IllegalArgumentException("Invalid crafting recipe");
            slots = List.copyOf(slots); remainders = Map.copyOf(remainders);
        }
    }
    public record Step(String recipe, String output, int count, List<String> ingredients) {}
    public record Target(String item, int requested, int available, Map<String, Integer> missing) {}
    public record Plan(List<Target> targets, List<Step> steps, Map<String, Integer> before, Map<String, Integer> after) {}
    private final Map<String, List<Recipe>> recipes = new LinkedHashMap<>();
    private int budget;
    public Planner(Collection<Recipe> recipes) { for (Recipe r : recipes) this.recipes.computeIfAbsent(r.output, k -> new ArrayList<>()).add(r); }

    private static final class Stock {
        Map<String, Integer> items; List<Step> steps;
        Stock(Map<String, Integer> items) { this.items = new LinkedHashMap<>(items); steps = new ArrayList<>(); }
        Stock copy() { Stock s = new Stock(items); s.steps.addAll(steps); return s; }
        void adopt(Stock s) { items = s.items; steps = s.steps; }
    }
    public Plan plan(List<MaterialList.Entry> requests, Map<String, Integer> inventory) {
        budget = 200_000;
        Stock stock = new Stock(inventory);
        List<Target> targets = new ArrayList<>();
        for (var request : requests) {
            int done = 0;
            while (done < request.count()) {
                Stock attempt = stock.copy();
                if (!take(request.item(), attempt, new HashSet<>(), 0)) break;
                stock.adopt(attempt); done++;
            }
            targets.add(new Target(request.item(), request.count(), done, Map.of()));
        }
        // Explain the remaining job against the stock left AFTER every achievable target.
        // Share virtual stock so ingredients and recipe batch surplus are counted only once.
        Map<String,Integer> virtual = new HashMap<>(stock.items);
        for (int i=0;i<targets.size();i++) {
            Target target=targets.get(i);
            Map<String,Integer> missing=new TreeMap<>();
            shortage(target.item(),target.requested()-target.available(),virtual,missing,new HashSet<>(),0);
            targets.set(i,new Target(target.item(),target.requested(),target.available(),Map.copyOf(missing)));
        }
        return new Plan(List.copyOf(targets), List.copyOf(stock.steps), Map.copyOf(inventory), Map.copyOf(stock.items));
    }
    private boolean take(String item, Stock stock, Set<String> path, int depth) {
        if (--budget < 0) throw new IllegalArgumentException("Plan is too complex. Split the material list into smaller jobs.");
        int count = stock.items.getOrDefault(item, 0);
        if (count > 0) { stock.items.put(item, count - 1); return true; }
        if (depth > 48 || !path.add(item)) return false;
        try {
            for (Recipe recipe : recipes.getOrDefault(item, List.of())) {
                Stock attempt = stock.copy();
                List<String> chosen = new ArrayList<>();
                if (!ingredients(recipe, 0, attempt, chosen, path, depth)) continue;
                attempt.items.merge(item, recipe.count, Math::addExact);
                recipe.remainders.forEach((key, n) -> attempt.items.merge(key, n, Math::addExact));
                attempt.steps.add(new Step(recipe.id, item, recipe.count, List.copyOf(chosen)));
                attempt.items.merge(item, -1, Integer::sum);
                stock.adopt(attempt); return true;
            }
            return false;
        } finally { path.remove(item); }
    }
    private boolean ingredients(Recipe recipe, int index, Stock stock, List<String> chosen, Set<String> path, int depth) {
        if (index == recipe.slots.size()) return true;
        // Prefer stocked alternatives but backtrack when a choice prevents a later slot.
        List<String> options = new ArrayList<>(recipe.slots.get(index).alternatives);
        options.sort(Comparator.comparingInt((String s) -> stock.items.getOrDefault(s, 0)).reversed());
        for (String option : options) {
            Stock trial = stock.copy(); List<String> selection = new ArrayList<>(chosen);
            if (!take(option, trial, path, depth + 1)) continue;
            selection.add(option);
            if (ingredients(recipe, index + 1, trial, selection, path, depth)) {
                stock.adopt(trial); chosen.clear(); chosen.addAll(selection); return true;
            }
        }
        return false;
    }
    private void shortage(String item, int quantity, Map<String, Integer> stock, Map<String, Integer> missing, Set<String> path, int depth) {
        if (--budget < 0) throw new IllegalArgumentException("Shortage calculation is too complex");
        int used = Math.min(quantity, stock.getOrDefault(item, 0));
        stock.merge(item, -used, Integer::sum); quantity -= used;
        if (quantity == 0) return;
        List<Recipe> candidates = recipes.getOrDefault(item, List.of());
        if (depth > 48 || path.contains(item) || candidates.isEmpty()) { missing.merge(item, quantity, Math::addExact); return; }
        path.add(item);
        Map<String, Integer> bestMissing = null, bestStock = null;
        long bestCost = Long.MAX_VALUE;
        for (Recipe recipe : candidates) {
            if (recipe.count > 1 && recipe.slots.size() == 1 && recipe.slots.getFirst().alternatives.stream().allMatch(source ->
                recipes.getOrDefault(source, List.of()).stream().anyMatch(reverse -> reverse.slots.size() == recipe.count
                    && reverse.slots.stream().allMatch(slot -> slot.alternatives.equals(List.of(item)))))) continue;
            // Do not explain an unpacking/packing loop as a source of raw materials.
            if (recipe.slots.stream().anyMatch(i -> i.alternatives.stream().allMatch(path::contains))) continue;
            Map<String, Integer> trialStock = new HashMap<>(stock), trialMissing = new TreeMap<>();
            int runs = (quantity + recipe.count - 1) / recipe.count;
            for (Ingredient ingredient : recipe.slots) {
                String choice = ingredient.alternatives.stream().max(Comparator.comparingInt(i -> trialStock.getOrDefault(i, 0))).orElseThrow();
                shortage(choice, runs, trialStock, trialMissing, path, depth + 1);
            }
            trialStock.merge(item, runs * recipe.count - quantity, Math::addExact);
            recipe.remainders.forEach((id,n)->trialStock.merge(id,Math.multiplyExact(runs,n),Math::addExact));
            long cost = trialMissing.values().stream().mapToLong(Integer::longValue).sum();
            if (cost < bestCost) { bestCost = cost; bestMissing = trialMissing; bestStock = trialStock; }
        }
        path.remove(item);
        if (bestMissing == null) { missing.merge(item, quantity, Math::addExact); return; }
        bestMissing.forEach((key, n) -> missing.merge(key, n, Math::addExact)); stock.clear(); stock.putAll(bestStock);
    }
}

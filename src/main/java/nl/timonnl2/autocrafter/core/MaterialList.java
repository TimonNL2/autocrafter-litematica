package nl.timonnl2.autocrafter.core;

import java.util.*;
import java.util.function.Function;

/** Parses plain lists and Litematica CSV/table exports without a Litematica dependency. */
public final class MaterialList {
    public record Entry(String item, int count) {}
    public record Result(List<Entry> entries, List<String> errors) {}

    public static Result parse(String text, Function<String, String> resolve) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int lineNumber = 0;
        for (String line : text.split("\\R")) {
            lineNumber++;
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#") || line.matches("[+|\\-= ]+")
                    || line.toLowerCase(Locale.ROOT).contains("total") && line.toLowerCase(Locale.ROOT).contains("item")) continue;
            try {
                String[] columns = columns(line);
                columns = Arrays.stream(columns).filter(s -> !s.isBlank()).toArray(String[]::new);
                String name;
                String amount;
                if (columns.length >= 2) { name = columns[0].strip(); amount = columns[1].strip(); }
                else {
                    int split = line.lastIndexOf(' ');
                    if (split < 1) throw new IllegalArgumentException("Use item ID followed by quantity");
                    name = line.substring(0, split).strip(); amount = line.substring(split + 1);
                }
                String item = resolve.apply(name);
                if (item == null) throw new IllegalArgumentException("Unknown or ambiguous item: " + name);
                int count = Integer.parseInt(amount.replace(",", ""));
                if (count <= 0 || count > 1_000_000) throw new IllegalArgumentException("Quantity must be 1–1,000,000");
                totals.merge(item, count, Math::addExact);
            } catch (RuntimeException e) { errors.add("Line " + lineNumber + ": " + e.getMessage()); }
        }
        return new Result(totals.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue())).toList(), List.copyOf(errors));
    }
    private static String[] columns(String line) {
        char delimiter=line.indexOf('|')>=0?'|':line.indexOf('\t')>=0?'\t':line.indexOf(';')>=0?';':',';
        List<String> result=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++) {
            char c=line.charAt(i);
            if(c=='"') {
                if(quoted && i+1<line.length() && line.charAt(i+1)=='"') {cell.append('"');i++;}
                else quoted=!quoted;
            } else if(c==delimiter && !quoted) {result.add(cell.toString().strip());cell.setLength(0);}
            else cell.append(c);
        }
        if(quoted)throw new IllegalArgumentException("Unclosed quoted field");
        result.add(cell.toString().strip());return result.toArray(String[]::new);
    }
}

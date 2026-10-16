package nl.timonnl2.autocrafter.core;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MaterialListTest {
    private String resolve(String name) {return Map.of("minecraft:stone","minecraft:stone","Stone","minecraft:stone").get(name);}
    @Test void mergesPlainAndCsvEntries() {
        var result=MaterialList.parse("# build\nminecraft:stone 4\nStone,12",this::resolve);
        assertTrue(result.errors().isEmpty());assertEquals(16,result.entries().getFirst().count());
    }
    @Test void handlesLitematicaTable() {
        var result=MaterialList.parse("+------+-------+\n| Item | Total | Missing |\n| Stone | 64 | 32 |",this::resolve);
        assertTrue(result.errors().isEmpty());assertEquals(64,result.entries().getFirst().count());
    }
    @Test void invalidUnknownAndNegativeValuesProduceErrors() {
        var result=MaterialList.parse("Stone -1\nUnknown 4\nStone nope",this::resolve);
        assertEquals(3,result.errors().size());assertTrue(result.entries().isEmpty());
    }
    @Test void quotedThousandsAreNotSilentlyTruncated() {
        var result=MaterialList.parse("\"Stone\",\"1,024\",512,512",this::resolve);
        assertTrue(result.errors().isEmpty());assertEquals(1024,result.entries().getFirst().count());
    }
}

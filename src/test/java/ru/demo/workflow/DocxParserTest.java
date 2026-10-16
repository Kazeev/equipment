package ru.demo.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class DocxParserTest {
    @TempDir Path folder;
    private final DocxParser parser = new DocxParser();

    @Test void trimsValuesSkipsBlankRowsAndPreservesDuplicateUids() throws Exception {
        var path = Documents.write(folder.resolve("valid.docx"), new String[]{"uid", "name"},
                new String[]{"  SN-001  ", " Ноутбук "}, new String[]{" ", " "},
                new String[]{"SN-001", "Монитор"});
        assertThat(parser.parse(path)).containsExactly(
                new DocxParser.Item("SN-001", "Ноутбук"), new DocxParser.Item("SN-001", "Монитор"));
    }

    static Stream<String[][]> invalidTables() {
        return Stream.of(new String[0][], new String[][]{{"uid", "name"}},
                new String[][]{{"uid", "name"}, {"", ""}},
                new String[][]{{"uid"}, {"1"}},
                new String[][]{{"uid", "name"}, {"1", "name", "extra"}},
                new String[][]{{"uid", "name"}, {"", "name"}},
                new String[][]{{"uid", "name"}, {"1", ""}},
                new String[][]{{"uid", "name"}, {"x".repeat(256), "name"}},
                new String[][]{{"uid", "name"}, {"1", "x".repeat(1001)}});
    }

    @ParameterizedTest @MethodSource("invalidTables")
    void rejectsInvalidTables(String[][] rows) throws Exception {
        var path = Documents.write(folder.resolve("invalid.docx"), rows);
        assertThatThrownBy(() -> parser.parse(path)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsCorruptedDocx() throws Exception {
        var path = Files.writeString(folder.resolve("broken.docx"), "not a zip document");
        assertThatThrownBy(() -> parser.parse(path)).isInstanceOf(Exception.class);
    }

    @Test void acceptsMaximumFieldLengths() throws Exception {
        var path = Documents.write(folder.resolve("limits.docx"), new String[]{"uid", "name"},
                new String[]{"x".repeat(255), "n".repeat(1000)});
        assertThat(parser.parse(path)).hasSize(1);
    }
}

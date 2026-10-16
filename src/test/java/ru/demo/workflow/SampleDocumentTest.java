package ru.demo.workflow;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;

class SampleDocumentTest {
    @Test void createExample() throws Exception {
        Files.createDirectories(Path.of("examples"));
        try (var doc = new XWPFDocument(); var out = Files.newOutputStream(Path.of("examples/equipment.docx"))) {
            doc.createParagraph().createRun().setText("Оборудование для индексации");
            var table = doc.createTable(3, 2);
            String[][] values = {{"uid", "name"}, {"SN-001", "Ноутбук"}, {"SN-002", "Монитор"}};
            for (int row = 0; row < values.length; row++)
                for (int col = 0; col < 2; col++) table.getRow(row).getCell(col).setText(values[row][col]);
            doc.write(out);
        }
    }
}

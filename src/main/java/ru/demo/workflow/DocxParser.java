package ru.demo.workflow;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class DocxParser {
    public record Item(String uid, String name) {}

    public List<Item> parse(Path path) throws Exception {
        try (var stream = Files.newInputStream(path); var doc = new XWPFDocument(stream)) {
            if (doc.getTables().isEmpty()) throw new IllegalArgumentException("В документе нет таблицы");
            var table = doc.getTables().get(0);
            if (table.getRows().isEmpty() || table.getRow(0).getTableCells().size() != 2)
                throw new IllegalArgumentException("Нужна таблица из двух столбцов: uid, name");
            var result = new ArrayList<Item>();
            for (int i = 1; i < table.getNumberOfRows(); i++) {
                var row = table.getRow(i);
                if (row.getTableCells().size() != 2) throw new IllegalArgumentException("Строка " + (i + 1) + ": нужны два столбца");
                String uid = row.getCell(0).getText().strip(), name = row.getCell(1).getText().strip();
                if (uid.isEmpty() && name.isEmpty()) continue;
                if (uid.isEmpty() || name.isEmpty() || uid.length() > 255 || name.length() > 1000)
                    throw new IllegalArgumentException("Строка " + (i + 1) + ": некорректные uid или name");
                result.add(new Item(uid, name));
            }
            if (result.isEmpty()) throw new IllegalArgumentException("Таблица оборудования пуста");
            return result;
        }
    }
}

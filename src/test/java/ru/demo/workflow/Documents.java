package ru.demo.workflow;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.nio.file.Files;
import java.nio.file.Path;

final class Documents {
    static Path write(Path path, String[]... rows) throws Exception {
        try (var doc = new XWPFDocument(); var out = Files.newOutputStream(path)) {
            if (rows.length > 0) {
                var table = doc.createTable();
                table.removeRow(0);
                for (var values : rows) {
                    var row = table.createRow();
                    while (row.getTableCells().size() < values.length) row.createCell();
                    while (row.getTableCells().size() > values.length) row.removeCell(0);
                    for (int i = 0; i < values.length; i++) row.getCell(i).setText(values[i]);
                }
            }
            doc.write(out);
        }
        return path;
    }

    static Path valid(Path path) throws Exception {
        return write(path, new String[]{"uid", "name"}, new String[]{"SN-001", "Ноутбук"});
    }
}

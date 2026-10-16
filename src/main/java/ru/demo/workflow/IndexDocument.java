package ru.demo.workflow;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.util.UUID;

@Component("indexDocument")
public class IndexDocument implements JavaDelegate {
    private final DocxParser parser;
    private final JdbcTemplate jdbc;
    public IndexDocument(DocxParser parser, JdbcTemplate jdbc) { this.parser = parser; this.jdbc = jdbc; }

    @Override public void execute(DelegateExecution execution) throws Exception {
        var items = parser.parse(Path.of((String) execution.getVariable("filePath")));
        String id = execution.getBusinessKey();
        jdbc.update("INSERT INTO requests(id, file_name) VALUES (?, ?)", id, execution.getVariable("fileName"));
        for (var item : items)
            jdbc.update("INSERT INTO equipment(id, request_id, uid, name) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID().toString(), id, item.uid(), item.name());
        execution.setVariable("requestId", id);
    }
}

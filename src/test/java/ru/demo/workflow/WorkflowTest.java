package ru.demo.workflow;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        "app.input-directory=target/test-input", "app.poll-delay=3600000"})
class WorkflowTest {
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired TaskController controller;
    @Autowired JdbcTemplate jdbc;

    @Test void importClaimCompleteAndSkipDuplicate() throws Exception {
        var path = Files.createTempFile("equipment-", ".docx");
        try {
            try (var doc = new XWPFDocument(); var output = Files.newOutputStream(path)) {
                var table = doc.createTable(3, 2);
                table.getRow(0).getCell(0).setText("uid"); table.getRow(0).getCell(1).setText("name");
                table.getRow(1).getCell(0).setText("SN-001"); table.getRow(1).getCell(1).setText("Ноутбук");
                table.getRow(2).getCell(0).setText("SN-002"); table.getRow(2).getCell(1).setText("Монитор");
                doc.write(output);
            }
            String requestId = UUID.randomUUID().toString();
            var process = runtime.startProcessInstanceByKey("documentReview", requestId,
                    Map.of("filePath", path.toString(), "fileName", "example.docx"));
            var task = tasks.createTaskQuery().processInstanceId(process.getId()).singleResult();
            assertThat(task.getAssignee()).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM equipment WHERE request_id=?", Integer.class, requestId)).isEqualTo(2);
            assertThat(controller.open(task.getId()).get("assignee")).isEqualTo("demo");
            assertThat(controller.open(task.getId()).get("requestId")).isEqualTo(requestId);
            // A duplicate must not create a second process, request or equipment rows.
            var folder = Files.createTempDirectory("import-");
            try {
                Files.copy(path, folder.resolve("example.docx"));
                var importer = new FolderImporter(runtime, jdbc, folder.toString());
                importer.scan(); importer.scan();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests", Integer.class)).isEqualTo(1);
                assertThat(runtime.createProcessInstanceQuery().count()).isEqualTo(1);
            } finally {
                try (var paths = Files.walk(folder)) { for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p); }
            }
            controller.complete(task.getId());
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(process.getId()).count()).isZero();
            var completed = (java.util.List<?>) controller.completed().get("tasks");
            assertThat(completed).hasSize(1);
            var detail = controller.completedDetail(task.getId());
            assertThat(detail.get("requestId")).isEqualTo(requestId);
            assertThat(detail.get("assignee")).isEqualTo("demo");
            assertThat(detail.get("completedAt")).isNotNull();
            assertThat((java.util.List<?>) detail.get("equipment")).hasSize(2);
        } finally { Files.deleteIfExists(path); }
    }
}

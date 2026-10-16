package ru.demo.workflow;

import org.camunda.bpm.engine.*;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:system;DB_CLOSE_DELAY=-1",
        "app.input-directory=target/system-input", "app.poll-delay=3600000"})
@AutoConfigureMockMvc
class SystemTest {
    @Autowired RuntimeService runtime;
    @Autowired TaskService tasks;
    @Autowired HistoryService history;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @TempDir Path folder;

    @BeforeEach void reset() {
        for (var p : runtime.createProcessInstanceQuery().list()) runtime.deleteProcessInstance(p.getId(), "test reset");
        for (var p : history.createHistoricProcessInstanceQuery().list()) history.deleteHistoricProcessInstance(p.getId());
        jdbc.update("DELETE FROM equipment"); jdbc.update("DELETE FROM requests");
    }

    private Task start(String name) throws Exception {
        var path = Documents.valid(folder.resolve(name));
        var process = runtime.startProcessInstanceByKey("documentReview", UUID.randomUUID().toString(),
                Map.of("filePath", path.toString(), "fileName", name));
        return tasks.createTaskQuery().processInstanceId(process.getId()).singleResult();
    }

    private void assertCounts(int requests, int equipment, int processes) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests", Integer.class)).isEqualTo(requests);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM equipment", Integer.class)).isEqualTo(equipment);
        assertThat(runtime.createProcessInstanceQuery().count()).isEqualTo(processes);
    }

    @Test void folderWaitsForStableFileAndArchivesExactlyOnceAcrossRestarts() throws Exception {
        var path = Documents.valid(folder.resolve("equipment.DOCX"));
        var importer = new FolderImporter(runtime, jdbc, folder.toString());
        importer.scan(); assertCounts(0, 0, 0);
        Files.setLastModifiedTime(path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 5000));
        importer.scan(); assertCounts(0, 0, 0);
        importer.scan(); assertCounts(1, 1, 1);
        assertThat(folder.resolve("archive/equipment.DOCX")).exists();
        Documents.valid(path);
        var restarted = new FolderImporter(runtime, jdbc, folder.toString());
        restarted.scan(); restarted.scan(); restarted.scan();
        assertCounts(1, 1, 1);
        try (var archived = Files.list(folder.resolve("archive"))) { assertThat(archived.count()).isEqualTo(2); }
    }

    @Test void ignoresTemporaryAndNonDocxFiles() throws Exception {
        Documents.valid(folder.resolve("~$equipment.docx"));
        Files.writeString(folder.resolve("notes.txt"), "text");
        Files.createDirectory(folder.resolve("directory.docx"));
        var importer = new FolderImporter(runtime, jdbc, folder.toString());
        importer.scan(); importer.scan(); assertCounts(0, 0, 0);
        assertThat(folder.resolve("~$equipment.docx")).exists();
    }

    @Test void failedFileGoesToErrorAndCanBeCorrectedAndRetried() throws Exception {
        var path = Files.writeString(folder.resolve("retry.docx"), "broken");
        var importer = new FolderImporter(runtime, jdbc, folder.toString());
        importer.scan(); importer.scan(); assertCounts(0, 0, 0);
        assertThat(folder.resolve("error/retry.docx")).exists();
        assertThat(Files.readString(folder.resolve("error/retry.docx.txt"))).isNotBlank();
        Documents.valid(path); importer.scan(); importer.scan(); assertCounts(1, 1, 1);
    }

    @Test void databaseFailureRollsBackRequestEquipmentAndProcess() throws Exception {
        start("same.docx");
        var path = Documents.valid(folder.resolve("second.docx"));
        assertThatThrownBy(() -> runtime.startProcessInstanceByKey("documentReview", UUID.randomUUID().toString(),
                Map.of("filePath", path.toString(), "fileName", "same.docx"))).isInstanceOf(Exception.class);
        assertCounts(1, 1, 1);
        assertThat(history.createHistoricProcessInstanceQuery().count()).isEqualTo(1);
    }

    @Test void activeListIncludesFreeAndMineButExcludesOthers() throws Exception {
        var free = start("free.docx"); var mine = start("mine.docx"); var other = start("other.docx");
        tasks.claim(mine.getId(), "demo"); tasks.claim(other.getId(), "other");
        mvc.perform(get("/api/tasks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andExpect(jsonPath("$.tasks[*].id", org.hamcrest.Matchers.containsInAnyOrder(free.getId(), mine.getId())));
        mvc.perform(post("/api/tasks/" + other.getId() + "/open")).andExpect(status().isConflict());
        mvc.perform(post("/api/tasks/" + other.getId() + "/complete")).andExpect(status().isConflict());
        assertThat(tasks.createTaskQuery().taskId(other.getId()).singleResult().getAssignee()).isEqualTo("other");
    }

    @Test void failureAfterFirstEquipmentRowRollsBackAllBusinessData() throws Exception {
        var path = Documents.write(folder.resolve("partial.docx"), new String[]{"uid", "name"},
                new String[]{"OK", "Первое оборудование"}, new String[]{"REJECT", "Второе оборудование"});
        // Force an actual database failure after the request and first equipment row have been inserted.
        jdbc.execute("ALTER TABLE equipment ADD CONSTRAINT test_reject_uid CHECK (uid <> 'REJECT')");
        try {
            assertThatThrownBy(() -> runtime.startProcessInstanceByKey("documentReview", UUID.randomUUID().toString(),
                    Map.of("filePath", path.toString(), "fileName", "partial.docx"))).isInstanceOf(Exception.class);
            assertCounts(0, 0, 0);
            assertThat(history.createHistoricProcessInstanceQuery().count()).isZero();
            assertThat(tasks.createTaskQuery().count()).isZero();
        } finally { jdbc.execute("ALTER TABLE equipment DROP CONSTRAINT test_reject_uid"); }
    }

    @Test void openIsIdempotentAndCompletionRequiresClaim() throws Exception {
        var task = start("claim.docx"); String base = "/api/tasks/" + task.getId();
        mvc.perform(post(base + "/complete")).andExpect(status().isConflict());
        for (int i = 0; i < 2; i++) mvc.perform(post(base + "/open"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignee").value("demo"))
                .andExpect(jsonPath("$.equipment[0].uid").value("SN-001"));
        mvc.perform(post(base + "/complete")).andExpect(status().isNoContent());
        mvc.perform(post(base + "/complete")).andExpect(status().isNotFound());
        mvc.perform(post(base + "/open")).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks")).andExpect(jsonPath("$.tasks").isEmpty());
        assertCounts(1, 1, 0);
    }

    @Test void historyIncludesOnlyNormallyCompletedByMeAndDetailIsReadOnly() throws Exception {
        var mine = start("done.docx"); tasks.claim(mine.getId(), "demo"); tasks.complete(mine.getId());
        var other = start("other.docx"); tasks.claim(other.getId(), "other"); tasks.complete(other.getId());
        var cancelled = start("cancelled.docx"); tasks.claim(cancelled.getId(), "demo");
        runtime.deleteProcessInstance(cancelled.getProcessInstanceId(), "cancelled");
        var active = start("active.docx"); tasks.claim(active.getId(), "demo");
        mvc.perform(get("/api/tasks/completed")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].id").value(mine.getId()));
        mvc.perform(get("/api/tasks/completed/" + mine.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("done.docx"))
                .andExpect(jsonPath("$.assignee").value("demo"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.equipment[0].name").value("Ноутбук"));
        for (String id : List.of(other.getId(), cancelled.getId(), active.getId(), "missing"))
            mvc.perform(get("/api/tasks/completed/" + id)).andExpect(status().isNotFound());
        assertThat(tasks.createTaskQuery().taskId(active.getId()).singleResult().getAssignee()).isEqualTo("demo");
    }

    @Test void missingActiveTaskReturns404() throws Exception {
        mvc.perform(post("/api/tasks/missing/open")).andExpect(status().isNotFound());
        mvc.perform(post("/api/tasks/missing/complete")).andExpect(status().isNotFound());
    }
}

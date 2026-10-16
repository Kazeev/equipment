package ru.demo.workflow;

import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.history.HistoricTaskInstance;
import org.camunda.bpm.engine.TaskAlreadyClaimedException;
import org.camunda.bpm.engine.OptimisticLockingException;
import org.camunda.bpm.engine.task.Task;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class TaskController {
    private static final String USER = "demo";
    private final TaskService tasks;
    private final JdbcTemplate jdbc;
    private final HistoryService history;
    public TaskController(TaskService tasks, JdbcTemplate jdbc, HistoryService history) {
        this.tasks = tasks; this.jdbc = jdbc; this.history = history;
    }

    @GetMapping("/tasks/completed")
    public Map<String, Object> completed() {
        var result = history.createHistoricTaskInstanceQuery().finished().taskAssignee(USER)
                .taskDefinitionKey("review").processDefinitionKey("documentReview")
                .orderByHistoricTaskInstanceEndTime().desc().list().stream()
                .filter(this::normallyCompleted).map(this::historicSummary).toList();
        return Map.of("user", USER, "tasks", result);
    }

    @GetMapping("/tasks/completed/{id}")
    public Map<String, Object> completedDetail(@PathVariable String id) {
        var task = history.createHistoricTaskInstanceQuery().taskId(id).finished().taskAssignee(USER)
                .taskDefinitionKey("review").processDefinitionKey("documentReview").singleResult();
        if (task == null || !normallyCompleted(task))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Завершённая заявка не найдена");
        var result = new LinkedHashMap<>(historicSummary(task));
        result.put("equipment", jdbc.queryForList(
                "SELECT uid AS \"uid\", name AS \"name\" FROM equipment WHERE request_id = ? ORDER BY uid, id",
                result.get("requestId")));
        return result;
    }

    private Map<String, Object> historicSummary(HistoricTaskInstance task) {
        var process = history.createHistoricProcessInstanceQuery().processInstanceId(task.getProcessInstanceId()).singleResult();
        String requestId = process == null ? null : process.getBusinessKey();
        var files = jdbc.queryForList("SELECT file_name FROM requests WHERE id = ?", String.class, requestId);
        var result = new LinkedHashMap<String, Object>();
        result.put("id", task.getId()); result.put("name", task.getName());
        result.put("assignee", task.getAssignee()); result.put("created", task.getStartTime());
        result.put("completedAt", task.getEndTime()); result.put("processInstanceId", task.getProcessInstanceId());
        result.put("requestId", requestId); result.put("fileName", files.isEmpty() ? null : files.get(0));
        return result;
    }

    private boolean normallyCompleted(HistoricTaskInstance task) {
        return task.getDeleteReason() == null || "completed".equals(task.getDeleteReason());
    }

    @GetMapping("/tasks")
    public Map<String, Object> list() {
        var result = tasks.createTaskQuery().active().or().taskUnassigned().taskAssignee(USER).endOr()
                .orderByTaskCreateTime().desc().list().stream().map(this::summary).toList();
        return Map.of("user", USER, "tasks", result);
    }

    @PostMapping("/tasks/{id}/open")
    @Transactional
    public Map<String, Object> open(@PathVariable String id) {
        Task task = accessible(id);
        if (task.getAssignee() == null) {
            try { tasks.claim(id, USER); }
            catch (TaskAlreadyClaimedException | OptimisticLockingException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Задача уже занята; обновите список");
            }
        }
        var result = new LinkedHashMap<>(summary(accessible(id)));
        Object requestId = tasks.getVariable(id, "requestId");
        result.put("requestId", requestId);
        result.put("equipment", requestId == null ? List.of() : jdbc.queryForList(
                "SELECT uid AS \"uid\", name AS \"name\" FROM equipment WHERE request_id = ? ORDER BY uid, id", requestId));
        return result;
    }

    @PostMapping("/tasks/{id}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void complete(@PathVariable String id) {
        if (!USER.equals(accessible(id).getAssignee()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала откройте задачу");
        tasks.complete(id);
    }

    private Task accessible(String id) {
        Task task = tasks.createTaskQuery().taskId(id).active().singleResult();
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Активная задача не найдена");
        if (task.getAssignee() != null && !USER.equals(task.getAssignee()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Задача занята другим пользователем");
        return task;
    }

    private Map<String, Object> summary(Task task) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", task.getId()); result.put("name", task.getName());
        result.put("assignee", task.getAssignee()); result.put("created", task.getCreateTime());
        result.put("processInstanceId", task.getProcessInstanceId());
        result.put("fileName", tasks.getVariable(task.getId(), "fileName"));
        result.put("requestId", tasks.getVariable(task.getId(), "requestId"));
        return result;
    }
}

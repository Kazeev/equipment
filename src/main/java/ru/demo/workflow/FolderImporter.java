package ru.demo.workflow;

import org.camunda.bpm.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;

@Component
public class FolderImporter {
    private static final Logger log = LoggerFactory.getLogger(FolderImporter.class);
    private final RuntimeService runtime;
    private final JdbcTemplate jdbc;
    private final Path input;
    private final Map<Path, String> observed = new HashMap<>();

    public FolderImporter(RuntimeService runtime, JdbcTemplate jdbc,
                          @Value("${app.input-directory}") String input) {
        this.runtime = runtime; this.jdbc = jdbc; this.input = Path.of(input).toAbsolutePath();
    }

    @Scheduled(fixedDelayString = "${app.poll-delay}")
    public void scan() {
        try {
            Files.createDirectories(input);
            try (var files = Files.list(input)) {
                for (Path path : files.filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".docx"))
                        .filter(p -> !p.getFileName().toString().startsWith("~$")).toList()) {
                    String stamp = Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis();
                    if (!stamp.equals(observed.put(path, stamp))) continue;
                    importFile(path);
                }
            }
            observed.keySet().removeIf(p -> !Files.exists(p));
        } catch (Exception e) { log.error("Не удалось проверить папку {}", input, e); }
    }

    private void importFile(Path path) {
        String name = path.getFileName().toString();
        try {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM requests WHERE file_name = ?", Integer.class, name) == 0) {
                runtime.startProcessInstanceByKey("documentReview", UUID.randomUUID().toString(),
                        Map.of("filePath", path.toString(), "fileName", name));
                log.info("Проиндексирован {}", name);
            } else log.info("Повторный файл пропущен: {}", name);
        } catch (Exception e) {
            log.error("Ошибка индексации {}", name, e);
            try {
                Path failed = move(path, "error");
                Files.writeString(failed.resolveSibling(failed.getFileName() + ".txt"),
                        "Ошибка обработки: " + e.getMessage());
            } catch (Exception moveError) { log.error("Не удалось перенести ошибочный файл", moveError); }
            return;
        }
        try { move(path, "archive"); }
        catch (Exception e) { log.warn("Данные сохранены, перенос {} будет повторён", name, e); }
    }

    private Path move(Path source, String folder) throws Exception {
        Path destination = input.resolve(folder);
        Files.createDirectories(destination);
        Path target = destination.resolve(source.getFileName());
        if (Files.exists(target)) target = destination.resolve(UUID.randomUUID() + "_" + source.getFileName());
        return Files.move(source, target);
    }
}

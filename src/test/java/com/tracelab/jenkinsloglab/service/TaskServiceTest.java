package com.tracelab.jenkinsloglab.service;

import com.tracelab.jenkinsloglab.dto.TaskRequest;
import com.tracelab.jenkinsloglab.exception.TaskNotFoundException;
import com.tracelab.jenkinsloglab.model.Task;
import com.tracelab.jenkinsloglab.model.TaskStatus;
import com.tracelab.jenkinsloglab.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskServiceTest {

    private TaskRepository repository;
    private TaskService service;

    @BeforeEach
    void setUp() {
        repository = new TaskRepository();
        service = new TaskService(repository);
    }

    @Test
    void createAssignsIdAndDefaultStatus() {
        TaskRequest request = new TaskRequest();
        request.setTitle("Write Jenkinsfile");
        request.setDescription("Upload console logs to the ingest API");

        Task created = service.create(request);

        assertEquals(1L, created.getId());
        assertEquals("Write Jenkinsfile", created.getTitle());
        assertEquals(TaskStatus.TODO, created.getStatus());
        assertEquals(1, service.list().size());
    }

    @Test
    void updateReplacesFields() {
        TaskRequest create = new TaskRequest();
        create.setTitle("Old title");
        Task saved = service.create(create);

        TaskRequest update = new TaskRequest();
        update.setTitle("New title");
        update.setStatus(TaskStatus.DONE);
        Task result = service.update(saved.getId(), update);

        assertEquals("New title", result.getTitle());
        assertEquals(TaskStatus.DONE, result.getStatus());
    }

    @Test
    void getMissingTaskThrowsNotFound() {
        assertThrows(TaskNotFoundException.class, () -> service.getById(99L));
    }

    @Test
    void deleteMissingTaskThrowsNotFound() {
        assertThrows(TaskNotFoundException.class, () -> service.delete(42L));
    }
}

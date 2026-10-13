package com.tracelab.jenkinsloglab.service;

import com.tracelab.jenkinsloglab.dto.TaskRequest;
import com.tracelab.jenkinsloglab.exception.TaskNotFoundException;
import com.tracelab.jenkinsloglab.model.Task;
import com.tracelab.jenkinsloglab.model.TaskStatus;
import com.tracelab.jenkinsloglab.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class TaskService {

    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public Task create(TaskRequest request) {
        Instant now = Instant.now();
        Task task = new Task();
        task.setTitle(request.getTitle().trim());
        task.setDescription(normalizeDescription(request.getDescription()));
        task.setStatus(request.getStatus() == null ? TaskStatus.TODO : request.getStatus());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return taskRepository.save(task);
    }

    public Task update(Long id, TaskRequest request) {
        Task existing = getById(id);
        existing.setTitle(request.getTitle().trim());
        existing.setDescription(normalizeDescription(request.getDescription()));
        if (request.getStatus() != null) {
            existing.setStatus(request.getStatus());
        }
        existing.setUpdatedAt(Instant.now());
        return taskRepository.save(existing);
    }

    public Task getById(Long id) {
        return taskRepository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
    }

    public List<Task> list() {
        return taskRepository.findAll();
    }

    public void delete(Long id) {
        if (!taskRepository.existsById(id)) {
            throw new TaskNotFoundException(id);
        }
        taskRepository.deleteById(id);
    }

    private String normalizeDescription(String description) {
        return description == null ? null : description.trim();
    }
}

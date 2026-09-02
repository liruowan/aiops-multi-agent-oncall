package org.example.controller;

import org.example.agent.tool.ToolInvocationRecord;
import org.example.agent.tool.ToolInvocationRecorder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tool-invocations")
public class ToolInvocationController {

    private final ToolInvocationRecorder recorder;

    public ToolInvocationController(ToolInvocationRecorder recorder) {
        this.recorder = recorder;
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<List<ToolInvocationRecord>> getInvocations(@PathVariable String sessionId) {
        return ResponseEntity.ok(recorder.getRecords(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> clearInvocations(@PathVariable String sessionId) {
        recorder.clearSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}

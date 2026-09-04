package com.example.infra.controller;

import com.example.infra.entity.InfraEnvironment;
import com.example.infra.service.InfraEnvironmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/environments")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class InfraEnvironmentController {

    private final InfraEnvironmentService infraEnvironmentService;

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<InfraEnvironment>> getEnvironments(@PathVariable String customerId) {
        return ResponseEntity.ok(infraEnvironmentService.getEnvironmentsByCustomer(customerId));
    }

    @PostMapping
    public ResponseEntity<InfraEnvironment> createEnvironment(@RequestBody InfraEnvironment environment) {
        return ResponseEntity.ok(infraEnvironmentService.saveEnvironment(environment));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEnvironment(@PathVariable String id) {
        infraEnvironmentService.deleteEnvironment(id);
        return ResponseEntity.noContent().build();
    }
}

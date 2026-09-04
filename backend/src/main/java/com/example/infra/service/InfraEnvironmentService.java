package com.example.infra.service;

import com.example.infra.entity.InfraEnvironment;
import com.example.infra.repository.InfraEnvironmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


import java.util.List;

@Service
@RequiredArgsConstructor
public class InfraEnvironmentService {

    private final InfraEnvironmentRepository infraEnvironmentRepository;
    private final EncryptionService encryptionService;

    public InfraEnvironment saveEnvironment(InfraEnvironment environment) {
        // Encrypt secret before saving
        if (environment.getEncryptedSecret() != null && !environment.getEncryptedSecret().isEmpty()) {
            environment.setEncryptedSecret(encryptionService.encrypt(environment.getEncryptedSecret()));
        }
        return infraEnvironmentRepository.save(environment);
    }

    public List<InfraEnvironment> getEnvironmentsByCustomer(String customerId) {
        List<InfraEnvironment> list = infraEnvironmentRepository.findAllByCustomerId(customerId);
        // Clear secrets for UI list (security)
        list.forEach(env -> env.setEncryptedSecret("********"));
        return list;
    }

    public List<InfraEnvironment> getAllEnvironments() {
        return infraEnvironmentRepository.findAll();
    }

    public String getDecryptedSecret(String id) {
        InfraEnvironment env = getEnvironment(id);
        return env != null ? encryptionService.decrypt(env.getEncryptedSecret()) : null;
    }

    public InfraEnvironment getEnvironment(String id) {
        return infraEnvironmentRepository.findById(id).orElse(null);
    }

    public void deleteEnvironment(String id) {
        infraEnvironmentRepository.deleteById(id);
    }
}

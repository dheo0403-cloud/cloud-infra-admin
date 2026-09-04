package com.example.infra.service;

import com.example.infra.entity.InfraCustomer;
import com.example.infra.repository.InfraCustomerRepository;
import com.example.infra.repository.InfraEnvironmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InfraCustomerService {

    private final InfraCustomerRepository infraCustomerRepository;
    private final InfraEnvironmentRepository infraEnvironmentRepository;
    private final EncryptionService encryptionService;

    public List<InfraCustomer> getAllCustomers() {
        return infraCustomerRepository.findAll();
    }

    public InfraCustomer saveCustomer(InfraCustomer customer) {
        boolean isUpdate = (customer.getId() != null);
        if (isUpdate) {
            List<com.example.infra.entity.InfraEnvironment> existingEnvs = infraEnvironmentRepository.findAllByCustomerId(customer.getId());
            List<String> newEnvIds = customer.getEnvironments() != null ? customer.getEnvironments().stream()
                .map(com.example.infra.entity.InfraEnvironment::getId)
                .filter(id -> id != null)
                .toList() : java.util.List.of();
            
            existingEnvs.stream()
                .filter(env -> !newEnvIds.contains(env.getId()))
                .forEach(env -> infraEnvironmentRepository.deleteById(env.getId()));
        }

        // 1. 고객사 먼저 저장 (id 확정)
        InfraCustomer saved = infraCustomerRepository.save(customer);

        // 2. 연결된 환경들을 infra_environment 테이블에 저장
        if (customer.getEnvironments() != null) {
            customer.getEnvironments().forEach(env -> {
                // 비밀값 암호화 (이미 암호화되거나 마스킹된 경우 제외)
                if (env.getEncryptedSecret() != null
                        && !env.getEncryptedSecret().isEmpty()
                        && !env.getEncryptedSecret().startsWith("ENC(")
                        && !"********".equals(env.getEncryptedSecret())) {
                    env.setEncryptedSecret(encryptionService.encrypt(env.getEncryptedSecret()));
                } else if ("********".equals(env.getEncryptedSecret()) && env.getId() != null) {
                    // UI에서 변경하지 않아 마스킹된 값을 그대로 보낸 경우, 기존의 암호화된 비밀키를 유지
                    infraEnvironmentRepository.findById(env.getId()).ifPresent(existingEnv -> {
                        env.setEncryptedSecret(existingEnv.getEncryptedSecret());
                    });
                }
                env.setCustomer(saved);
                infraEnvironmentRepository.save(env);
            });
        }
        return saved;
    }

    public InfraCustomer getCustomerWithEnvironments(String id) {
        return infraCustomerRepository.findById(id).map(customer -> {
            customer.getEnvironments().forEach(env -> env.setEncryptedSecret("********"));
            return customer;
        }).orElse(null);
    }

    public void deleteCustomer(String id) {
        // 고객사 삭제 시 연결된 환경도 함께 삭제
        List<com.example.infra.entity.InfraEnvironment> envs =
                infraEnvironmentRepository.findAllByCustomerId(id);
        envs.forEach(env -> infraEnvironmentRepository.deleteById(env.getId()));
        infraCustomerRepository.deleteById(id);
    }
}

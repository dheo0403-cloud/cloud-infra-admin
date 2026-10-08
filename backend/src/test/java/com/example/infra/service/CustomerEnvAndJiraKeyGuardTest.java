package com.example.infra.service;

import com.example.infra.entity.InfraCustomer;
import com.example.infra.entity.InfraEnvironment;
import com.example.infra.repository.InfraCustomerRepository;
import com.example.infra.repository.InfraEnvironmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// 고객사 저장 시 환경 삭제 조건, Jira 키 형식 검사
class CustomerEnvAndJiraKeyGuardTest {

    private final InfraCustomerRepository customerRepo = mock(InfraCustomerRepository.class);
    private final InfraEnvironmentRepository envRepo = mock(InfraEnvironmentRepository.class);
    private final InfraCustomerService service = new InfraCustomerService(customerRepo, envRepo, mock(EncryptionService.class));

    private InfraCustomer existingCustomer(List<InfraEnvironment> envs) {
        InfraEnvironment old = new InfraEnvironment();
        old.setId("env-1");
        when(envRepo.findAllByCustomerId("cust-1")).thenReturn(List.of(old));
        when(customerRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        InfraCustomer c = new InfraCustomer();
        c.setId("cust-1");
        c.setEnvironments(envs);
        return c;
    }

    @Test
    void 환경_목록이_null이면_기존_환경을_지우지_않음() {
        service.saveCustomer(existingCustomer(null));
        verify(envRepo, never()).deleteById(anyString());
    }

    @Test
    void 빈_배열이면_기존_환경을_삭제() {
        service.saveCustomer(existingCustomer(List.of()));
        verify(envRepo).deleteById("env-1");
    }

    @Test
    void 잘못된_Jira_키는_Jira를_호출하지_않음() {
        JiraClientService client = new JiraClientService();
        RestTemplate rest = mock(RestTemplate.class);
        ReflectionTestUtils.setField(client, "restTemplate", rest);
        ReflectionTestUtils.setField(client, "apiToken", "token");

        assertTrue(client.fetchAllIssuesByProject("A' OR project != 'X").isEmpty());
        assertTrue(client.fetchIssuesByProjectAndDays("aaa032", 7).isEmpty()); // 소문자는 호출부에서 대문자로 바꿔 넘김
        verifyNoInteractions(rest);
    }
}

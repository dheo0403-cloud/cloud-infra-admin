package com.example.infra.service;

import com.google.cloud.compute.v1.AccessConfig;
import com.google.cloud.compute.v1.Instance;
import com.google.cloud.compute.v1.NetworkInterface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// VM 외부 IP 보유 판정: accessConfigs 중 natIP가 있는지
class VmExternalIpTest {

    private Instance vm(AccessConfig... configs) {
        return Instance.newBuilder().addNetworkInterfaces(
                NetworkInterface.newBuilder().setNetworkIP("10.0.0.2").addAllAccessConfigs(java.util.List.of(configs))).build();
    }

    @Test
    void natIP가_있으면_외부_IP_VM() {
        assertTrue(BigQueryBatchService.hasExternalIp(vm(AccessConfig.newBuilder().setName("External NAT").setNatIP("34.64.1.2").build())));
    }

    @Test
    void 내부_IP만_있거나_natIP가_비면_아님() {
        assertFalse(BigQueryBatchService.hasExternalIp(vm()));
        assertFalse(BigQueryBatchService.hasExternalIp(vm(AccessConfig.newBuilder().setName("External NAT").build())));
    }
}

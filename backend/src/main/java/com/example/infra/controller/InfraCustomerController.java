package com.example.infra.controller;

import com.example.infra.entity.InfraCustomer;
import com.example.infra.service.InfraCustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class InfraCustomerController {

    private final InfraCustomerService infraCustomerService;

    @GetMapping
    public ResponseEntity<List<InfraCustomer>> getAllCustomers() {
        return ResponseEntity.ok(infraCustomerService.getAllCustomers());
    }

    @PostMapping
    public ResponseEntity<InfraCustomer> saveCustomer(@RequestBody InfraCustomer customer) {
        return ResponseEntity.ok(infraCustomerService.saveCustomer(customer));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCustomer(@PathVariable String id) {
        infraCustomerService.deleteCustomer(id);
        return ResponseEntity.noContent().build();
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<InfraCustomer> getCustomer(@PathVariable String id) {
        InfraCustomer customer = infraCustomerService.getCustomerWithEnvironments(id);
        if (customer != null) {
            return ResponseEntity.ok(customer);
        }
        return ResponseEntity.notFound().build();
    }
}

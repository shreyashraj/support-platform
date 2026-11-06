package com.example.support.web;

import com.example.support.security.Caller;
import com.example.support.service.CustomerService;
import com.example.support.service.Views.CustomerView;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CustomerController {
    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping("/v1/customers/{id}")
    ResponseEntity<CustomerView> get(@PathVariable String id) {
        CustomerView view = customers.get(Ids.account(id), Caller.current());
        return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePrivate()).body(view);
    }
}

package com.oliverke.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaymentReconciliationApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentReconciliationApplication.class, args);
    }
}

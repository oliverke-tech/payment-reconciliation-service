package com.oliverke.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Binds every @ConfigurationProperties record in the application, so that which
// beans happen to be active in a given profile cannot decide whether config is
// available. Registering them from whichever component used them first made the
// generator profile depend on a bean it never runs.
@ConfigurationPropertiesScan
@EnableScheduling
public class PaymentReconciliationApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentReconciliationApplication.class, args);
    }
}

package com.starticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StarTicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarTicketApplication.class, args);
    }
}

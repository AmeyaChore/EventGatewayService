package com.ameya.eventgatewayservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class EventGatewayApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventGatewayApiApplication.class, args);
    }

}

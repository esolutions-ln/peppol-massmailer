package com.esolutions.forwarder;

import com.esolutions.forwarder.config.ForwarderProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(ForwarderProperties.class)
@EnableScheduling
public class ForwarderApplication {
    public static void main(String[] args) {
        SpringApplication.run(ForwarderApplication.class, args);
    }
}

package com.brainboosterlab.channel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.brainboosterlab.channel.content.GenerationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GenerationProperties.class)
public class BrainBoosterLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrainBoosterLabApplication.class, args);
    }
}

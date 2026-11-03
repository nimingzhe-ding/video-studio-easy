package com.videostudio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VideoStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoStudioApplication.class, args);
    }
}

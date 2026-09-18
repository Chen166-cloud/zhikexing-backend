package com.chy.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class IiipApplication {

	public static void main(String[] args) {
		SpringApplication.run(IiipApplication.class, args);
	}

}

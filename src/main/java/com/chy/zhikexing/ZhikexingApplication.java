package com.chy.zhikexing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class ZhikexingApplication {

	public static void main(String[] args) {
		SpringApplication.run(ZhikexingApplication.class, args);
	}

}

package com.chat.talkMe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class TalkMeApplication {

	public static void main(String[] args) {
		SpringApplication.run(TalkMeApplication.class, args);
	}

}

package com.eap.eap_wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EapWalletApplication {

	public static void main(String[] args) {
		SpringApplication.run(EapWalletApplication.class, args);
	}

}

// ar/edu/utn/dds/k3003/model/Application.java
package ar.edu.utn.dds.k3003.model;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EntityScan(basePackages = "ar.edu.utn.dds.k3003.model")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    @ConditionalOnBean(BotRunner.class)   // sólo si existe BotRunner
    CommandLineRunner startBot(BotRunner runner) {
        return args -> runner.start();
    }
}

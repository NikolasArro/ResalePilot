package ee.nikolas.resalepilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ResalePilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResalePilotApplication.class, args);
    }
}

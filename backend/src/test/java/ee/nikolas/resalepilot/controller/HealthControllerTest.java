package ee.nikolas.resalepilot.controller;

import ee.nikolas.resalepilot.dto.HealthResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    private final HealthController healthController = new HealthController();

    @Test
    void shouldReturnUpStatus() {
        HealthResponse response = healthController.health();

        assertThat(response.application()).isEqualTo("ResalePilot");
        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.timestamp()).isNotNull();
    }
}

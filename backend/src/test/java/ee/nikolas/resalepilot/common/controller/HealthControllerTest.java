package ee.nikolas.resalepilot.common.controller;

import ee.nikolas.resalepilot.common.dto.HealthResponse;
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

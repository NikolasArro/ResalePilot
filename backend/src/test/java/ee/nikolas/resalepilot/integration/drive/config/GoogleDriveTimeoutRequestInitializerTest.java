package ee.nikolas.resalepilot.integration.drive.config;

import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class GoogleDriveTimeoutRequestInitializerTest {

    @Test
    void appliesFiniteTimeoutsAfterDelegateInitialization()
            throws IOException {

        HttpRequestInitializer delegate =
                mock(HttpRequestInitializer.class);
        HttpRequest request = mock(HttpRequest.class);

        new GoogleDriveTimeoutRequestInitializer(delegate)
                .initialize(request);

        var order = inOrder(delegate, request);
        order.verify(delegate).initialize(request);
        order.verify(request).setConnectTimeout(
                Math.toIntExact(
                        GoogleDriveTimeoutRequestInitializer
                                .CONNECT_TIMEOUT
                                .toMillis()
                )
        );
        order.verify(request).setReadTimeout(
                Math.toIntExact(
                        GoogleDriveTimeoutRequestInitializer
                                .READ_TIMEOUT
                                .toMillis()
                )
        );
    }
}

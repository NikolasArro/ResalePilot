package ee.nikolas.resalepilot.integration.drive.config;

import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;

import java.io.IOException;
import java.time.Duration;

class GoogleDriveTimeoutRequestInitializer
        implements HttpRequestInitializer {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    static final Duration READ_TIMEOUT = Duration.ofMinutes(2);

    private final HttpRequestInitializer delegate;

    GoogleDriveTimeoutRequestInitializer(HttpRequestInitializer delegate) {
        this.delegate = delegate;
    }

    @Override
    public void initialize(HttpRequest request) throws IOException {
        if (delegate != null) {
            delegate.initialize(request);
        }
        request.setConnectTimeout(Math.toIntExact(
                CONNECT_TIMEOUT.toMillis()
        ));
        request.setReadTimeout(Math.toIntExact(
                READ_TIMEOUT.toMillis()
        ));
    }
}

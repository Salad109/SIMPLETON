package io.salad109.conjunctiondetector.spacetrack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class SpaceTrackClient {

    private static final String SPACE_TRACK_LOGIN_URL = "/ajaxauth/login";
    // DECAY_DATE/null-val keeps only active (non-decayed) objects; EPOCH/>now-10 keeps only recently updated
    private static final String SPACE_TRACK_QUERY_URL = "/basicspacedata/query/class/gp/DECAY_DATE/null-val/EPOCH/>now-10/orderby/NORAD_CAT_ID/format/json";
    private static final Logger log = LoggerFactory.getLogger(SpaceTrackClient.class);
    private final RestClient restClient;
    @Value("${spacetrack.username}")
    private String username;
    @Value("${spacetrack.password}")
    private String password;

    public SpaceTrackClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Authenticate with Space-Track. Session cookies are stored automatically.
     */
    private void login() {
        log.debug("Authenticating with Space-Track...");
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("SPACETRACK_USERNAME not configured");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("SPACETRACK_PASSWORD not configured");
        }

        // retrieve() throws RestClientResponseException on a non-2xx status, so reaching the next line means success
        restClient.post()
                .uri(SPACE_TRACK_LOGIN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("identity=" + URLEncoder.encode(username, StandardCharsets.UTF_8) +
                        "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8))
                .retrieve()
                .toBodilessEntity();

        log.debug("Successfully authenticated with Space-Track");
    }

    /**
     * Fetch the GP catalog (current TLEs for all objects).
     */
    public List<GpRecord> fetchCatalog() throws IOException {
        login();

        log.debug("Fetching full GP catalog from Space-Track...");

        var response = restClient.get()
                .uri(SPACE_TRACK_QUERY_URL)
                .retrieve()
                .toEntity(new ParameterizedTypeReference<List<GpRecord>>() {
                });

        List<GpRecord> body = response.getBody();
        if (body == null) {
            throw new IOException("Catalog fetch returned no data");
        }
        log.debug("Fetched {} objects from Space-Track", body.size());
        return body;
    }
}

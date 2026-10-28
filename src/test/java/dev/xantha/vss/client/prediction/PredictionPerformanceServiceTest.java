package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PredictionPerformanceServiceTest {
    @TempDir Path directory;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void loopbackQueriesReadDetachedSnapshotsAndRejectWritesOrMissingCredentials() throws Exception {
        try (var service = new PredictionPerformanceService()) {
            Path discovery = service.start(directory).get(5, TimeUnit.SECONDS);
            var session = JsonParser.parseString(Files.readString(discovery)).getAsJsonObject();
            service.publish(Map.of("capturing", true, "sampling", true, "latest", Map.of("fps", 25),
                    "samples", List.of(Map.of("fps", 20), Map.of("fps", 25))));
            assertEquals("127.0.0.1", session.get("host").getAsString());
            assertEquals(401, query(session, "/status", "GET", false).statusCode());
            assertEquals(405, query(session, "/status", "POST", true).statusCode());
            var reply = query(session, "/samples?limit=1", "GET", true);
            assertEquals(200, reply.statusCode());
            var rows = JsonParser.parseString(reply.body()).getAsJsonObject().getAsJsonArray("samples");
            assertEquals(1, rows.size()); assertEquals(25, rows.get(0).getAsJsonObject().get("fps").getAsInt());
            assertEquals(400, query(session, "/samples?limit=0", "GET", true).statusCode());
            assertEquals(400, query(session, "/samples?limit=601", "GET", true).statusCode());
            assertEquals(404, query(session, "/change-workers", "GET", true).statusCode());
            assertFalse(query(session, "/report", "GET", true).body().contains(session.get("token").getAsString()));
        }
    }

    @Test void restartRotatesCredentialsAndStopRemovesOnlyItsOwnDiscovery() throws Exception {
        try (var service = new PredictionPerformanceService()) {
            Path path = service.start(directory).get(5, TimeUnit.SECONDS);
            var old = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            service.start(directory).get(5, TimeUnit.SECONDS);
            var current = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            assertNotEquals(old.get("token"), current.get("token"));
            var wrong = current.deepCopy(); wrong.add("token", old.get("token"));
            assertEquals(401, query(wrong, "/status", "GET", true).statusCode());
            service.stop();
            // A queued restart is a lifecycle fence: it must finish after stop releases the old listener.
            service.start(directory).get(5, TimeUnit.SECONDS);
            assertEquals(200, query(JsonParser.parseString(Files.readString(path)).getAsJsonObject(), "/status", "GET", true).statusCode());
            Files.writeString(path, "{\"token\":\"another-game\"}");
            service.stop(); service.start(directory.resolve("other")).get(5, TimeUnit.SECONDS);
            assertTrue(Files.readString(path).contains("another-game"));
        }
    }

    private static HttpResponse<String> query(JsonObject session, String path, String method, boolean authorized) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + session.get("port").getAsInt() + path))
                .timeout(Duration.ofSeconds(3)).method(method, HttpRequest.BodyPublishers.noBody());
        if (authorized) request.header("Authorization", "Bearer " + session.get("token").getAsString());
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

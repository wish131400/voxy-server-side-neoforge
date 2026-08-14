package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientConnectionIdentityTest {
    @TempDir
    Path tempDirectory;

    @AfterEach
    void clearIdentity() {
        ClientConnectionIdentity.endSession();
    }

    @Test
    void differentServersUseDifferentPresenceScopesAndVoxyFolders() {
        ClientConnectionIdentity.beginSession("alpha.example:25565", false);
        String alphaScope = ClientConnectionIdentity.currentPresenceScope();
        Path alphaPath = ClientConnectionIdentity.currentVoxyStoragePath(Path.of("game"));

        ClientConnectionIdentity.beginSession("beta.example:25566", false);

        assertEquals("server:alpha.example:25565", alphaScope);
        assertEquals("alpha.example_25565", alphaPath.getFileName().toString());
        assertEquals("server:beta.example:25566", ClientConnectionIdentity.currentPresenceScope());
        assertEquals(
                "beta.example_25566",
                ClientConnectionIdentity.currentVoxyStoragePath(Path.of("game")).getFileName().toString());
    }

    @Test
    void unresolvedConnectionNeverReusesThePreviousServer() {
        ClientConnectionIdentity.beginSession("alpha.example:25565", false);
        String alphaScope = ClientConnectionIdentity.currentPresenceScope();

        ClientConnectionIdentity.beginSession((String) null, false);
        String unresolvedScope = ClientConnectionIdentity.currentPresenceScope();

        assertNotEquals(alphaScope, unresolvedScope);
        assertNull(ClientConnectionIdentity.currentVoxyStoragePath(Path.of("game")));
    }

    @Test
    void missingConnectionSnapshotGetsAUniqueTemporaryScope() {
        String firstScope = ClientConnectionIdentity.ensurePresenceScope();

        ClientConnectionIdentity.endSession();
        String secondScope = ClientConnectionIdentity.ensurePresenceScope();

        assertNotEquals(firstScope, secondScope);
    }

    @Test
    void lateServerDataReplacesTheTemporaryConnectionScope() {
        ClientConnectionIdentity.beginSession((String) null, false);
        String unresolvedScope = ClientConnectionIdentity.currentPresenceScope();

        ClientConnectionIdentity.updateSession("Example.COM:25565", false);

        assertNotEquals(unresolvedScope, ClientConnectionIdentity.currentPresenceScope());
        assertEquals("server:example.com:25565", ClientConnectionIdentity.currentPresenceScope());
        assertEquals(
                "Example.COM_25565",
                ClientConnectionIdentity.currentVoxyStoragePath(Path.of("game")).getFileName().toString());
    }

    @Test
    void disconnectClearsTheCapturedServerIdentity() {
        ClientConnectionIdentity.beginSession("alpha.example:25565", false);

        ClientConnectionIdentity.endSession();

        assertNull(ClientConnectionIdentity.currentPresenceScope());
        assertNull(ClientConnectionIdentity.currentVoxyStoragePath(Path.of("game")));
    }

    @Test
    void realmsKeepVoxysExistingSharedDirectoryConvention() {
        ClientConnectionIdentity.beginSession("realm.example", true);

        assertEquals("realm:realm.example", ClientConnectionIdentity.currentPresenceScope());
        assertEquals(
                "realms",
                ClientConnectionIdentity.currentVoxyStoragePath(Path.of("game")).getFileName().toString());
    }

    @Test
    void loginIdentityArrivingBeforePlaySessionIsPreserved() {
        ClientConnectionIdentity.acceptServerIdentity("7k4m9pxa", true, "node-a");
        ClientConnectionIdentity.beginSession("2001:db8::25:25565", false);

        assertEquals("vss:7k4m9pxa", ClientConnectionIdentity.currentPresenceScope());
        assertEquals(
                "vss-7K4M9PXA-2001_db8__25_25565",
                ClientConnectionIdentity.currentVoxyStoragePath(tempDirectory).getFileName().toString());
    }

    @Test
    void changingEndpointRenamesAndReusesTheStableCache() throws IOException {
        ClientConnectionIdentity.acceptServerIdentity("7K4M9PXA", true, "");
        ClientConnectionIdentity.beginSession("alpha.example:25565", false);
        Path oldPath = ClientConnectionIdentity.currentVoxyStoragePath(tempDirectory);
        Files.writeString(oldPath.resolve("lod-marker"), "cached");

        ClientConnectionIdentity.endSession();
        ClientConnectionIdentity.acceptServerIdentity("7K4M9PXA", true, "");
        ClientConnectionIdentity.beginSession("beta.example:25566", false);
        Path newPath = ClientConnectionIdentity.currentVoxyStoragePath(tempDirectory);

        assertEquals("vss-7K4M9PXA-beta.example_25566", newPath.getFileName().toString());
        assertFalse(Files.exists(oldPath));
        assertEquals("cached", Files.readString(newPath.resolve("lod-marker")));
    }

    @Test
    void sharedWorldIgnoresNodeIdentityButIndependentNodesStaySeparate() {
        ClientConnectionIdentity.acceptServerIdentity("7K4M9PXA", true, "node-a");
        ClientConnectionIdentity.beginSession("alpha.example:25565", false);
        assertEquals(
                "vss-7K4M9PXA-alpha.example_25565",
                ClientConnectionIdentity.currentVoxyStoragePath(tempDirectory).getFileName().toString());

        ClientConnectionIdentity.endSession();
        ClientConnectionIdentity.acceptServerIdentity("7K4M9PXA", false, "node-a");
        ClientConnectionIdentity.beginSession("alpha.example:25565", false);
        Path nodeA = ClientConnectionIdentity.currentVoxyStoragePath(tempDirectory);

        ClientConnectionIdentity.endSession();
        ClientConnectionIdentity.acceptServerIdentity("7K4M9PXA", false, "node-b");
        ClientConnectionIdentity.beginSession("beta.example:25566", false);
        Path nodeB = ClientConnectionIdentity.currentVoxyStoragePath(tempDirectory);

        assertEquals("vss-7K4M9PXA-node-a-alpha.example_25565", nodeA.getFileName().toString());
        assertEquals("vss-7K4M9PXA-node-b-beta.example_25566", nodeB.getFileName().toString());
        assertNotEquals(nodeA, nodeB);
    }

}

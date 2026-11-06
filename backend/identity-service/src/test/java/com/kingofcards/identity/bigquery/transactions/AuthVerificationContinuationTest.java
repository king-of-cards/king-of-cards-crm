package com.kingofcards.identity.bigquery.transactions;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthVerificationContinuationTest {
    @TempDir Path directory;
    @Test void changedOrInventedManifestIsRejectedBeforeCloudAccess() throws Exception {
        var path=directory.resolve("manifest.json"); Files.writeString(path,"{}");
        assertThrows(IllegalArgumentException.class,()->AuthVerificationContinuation.manifest(path,AuthVerificationContinuation.RUN));
        assertThrows(IllegalArgumentException.class,()->AuthVerificationContinuation.manifest(path,java.util.UUID.randomUUID().toString()));
    }
    @Test void bootstrapAndCompletedScenariosCannotRunInContinuation() {
        for(int order=1;order<=6;order++){int selected=order;assertThrows(IllegalStateException.class,()->AuthVerificationContinuation.requireScenario(selected));}
        for(int order=7;order<=12;order++){int selected=order;assertDoesNotThrow(()->AuthVerificationContinuation.requireScenario(selected));}
        assertThrows(IllegalStateException.class,()->AuthVerificationContinuation.requireScenario(13));
    }
}

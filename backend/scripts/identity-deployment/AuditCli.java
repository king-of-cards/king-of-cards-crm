package com.kingofcards.identity.maintenance;

import java.nio.file.*;
import com.google.cloud.bigquery.*;
import com.google.auth.oauth2.*;

/** Read-only standalone checks for preflight and rollback; no auth/session API calls. */
public final class AuditCli {
    public static void main(String[] args)throws Exception {
        Safety.require(args.length==3,"Bundle, bundle hash and new output required");
        var bundle=RuntimeControl.verifyBundle(Path.of(args[0]),args[1]);
        var credentials=GoogleCredentials.fromStream(Files.newInputStream(Path.of(bundle.getProperty("credentials.path"))));
        var defaults=BigQueryOptions.newBuilder().setProjectId("king-of-cards-509610").setCredentials(credentials).build();
        var client=defaults.toBuilder().setRetrySettings(defaults.getRetrySettings().toBuilder().setTotalTimeoutDuration(java.time.Duration.ofSeconds(30)).build()).build().getService();
        var p=ReadAudit.inspect(client,bundle.getProperty("principalSha256"));Safety.save(p,Path.of(args[2]));
        System.out.println("IDENTITY_READ_AUDIT_COMPLETE accounts="+p.getProperty("accounts")+" sessions="+p.getProperty("sessions")+" orphans="+p.getProperty("orphans")+" activeAuthJobs="+p.getProperty("activeAuthJobs"));
    }
}

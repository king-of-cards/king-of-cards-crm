package com.kingofcards.identity.ops;
import java.time.*;
/** Policy validation is not evidence of live enforcement. No certificate is issued here. */
public final class IdentityDrainPolicy {
 public record Evidence(Instant observedAt,boolean liveGateVerified,boolean allClosed,int httpActive,int mutations,int queued,
  boolean preInstallRequestsAccounted,boolean asyncAccounted,boolean ownPrincipalVerified,boolean jobPagesComplete,int activeAuthJobs,
  int unresolvedSubmissions,boolean accountsStable,boolean sessionsStable,boolean artifactPinsMatch,boolean rollbackVerified){}
 public static void requireFreshClosed(Evidence e,Instant now){
  if(e==null||e.observedAt()==null||e.observedAt().isAfter(now)||e.observedAt().isBefore(now.minusSeconds(30))
   ||!e.liveGateVerified()||!e.allClosed()||e.httpActive()!=0||e.mutations()!=0||e.queued()!=0||!e.preInstallRequestsAccounted()
   ||!e.asyncAccounted()||!e.ownPrincipalVerified()||!e.jobPagesComplete()||e.activeAuthJobs()!=0||e.unresolvedSubmissions()!=0
   ||!e.accountsStable()||!e.sessionsStable()||!e.artifactPinsMatch()||!e.rollbackVerified())throw new IllegalStateException("Identity drain evidence is incomplete, stale or unsafe; no stop/start permitted");
 }
}

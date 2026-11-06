package com.kingofcards.identity.bigquery.transactions;

/** Fixed Auth verification allowlist. Isolation is by approved synthetic run, not separate tables.
 * This does not activate production authentication mutations or authorize a cloud test.
 */
public final class IsolatedAuthTarget {
    public static final String PROJECT = "king-of-cards-509610";
    public static final String DATASET = "shared_rds_data";
    public static final String LOCATION = "asia-south1";
    public static final String USERS = "identity_user";
    public static final String SESSIONS = "identity_refresh_session";
    private IsolatedAuthTarget() {}
    public static String users() { return quote(USERS); }
    public static String sessions() { return quote(SESSIONS); }
    private static String quote(String name) { return "`" + PROJECT + "." + DATASET + "." + name + "`"; }
}

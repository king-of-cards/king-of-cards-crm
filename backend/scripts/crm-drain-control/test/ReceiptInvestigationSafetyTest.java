import com.google.gson.*;
/** No credentials/network/CRM: verify the diagnostic cannot send mutation requests. */
class ReceiptInvestigationSafetyTest {
 static int checks;
 static void check(boolean condition){if(!condition)throw new AssertionError();checks++;}
 static void rejected(String method,String path,JsonObject body)throws Exception{
  try{CommitReceiptInvestigation.request(method,path,body);throw new AssertionError("Forbidden request reached transport");}catch(IllegalArgumentException expected){checks++;}
 }
 public static void main(String[]args)throws Exception{
  check(CommitReceiptInvestigation.val(null,"value")==null);
  var nullable=JsonParser.parseString("{\"value\":null}").getAsJsonObject();check(CommitReceiptInvestigation.val(nullable,"value")==null);
  var original=JsonParser.parseString("{\"configuration\":{\"query\":{\"queryParameters\":[{\"name\":\"batch\",\"parameterType\":{\"type\":\"STRING\"}}]}}}").getAsJsonObject();check(CommitReceiptInvestigation.params(original).containsKey("batch")&&CommitReceiptInvestigation.params(original).get("batch")==null);
  rejected("DELETE","/jobs/an-original-job",null);rejected("POST","/jobs",new JsonObject());
  for(String sql:new String[]{"INSERT INTO table SELECT 1","UPDATE table SET x=1","ALTER TABLE table ADD COLUMN x STRING","SELECT 1; DELETE FROM table WHERE TRUE","SELECT 1; ALTER TABLE table ADD COLUMN x STRING"}){var body=new JsonObject();body.addProperty("query",sql);rejected("POST","/queries",body);}
  check(CommitReceiptInvestigation.calls==0);System.out.println("PASS "+checks+" diagnostic safety checks; no network, credentials, CRM or BigQuery operations");
 }
}

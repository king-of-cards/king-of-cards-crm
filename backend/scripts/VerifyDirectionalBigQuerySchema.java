import com.google.cloud.bigquery.*;
import com.kingofcards.crm.config.BigQueryProperties;
import com.kingofcards.crm.directional.DirectionalSchemaInitializer;

/** REST metadata reads only. Never submits SQL jobs, DDL, DML or row reads. */
class VerifyDirectionalBigQuerySchema {
    public static void main(String[] args) throws Exception {
        var properties=new BigQueryProperties(true,"king-of-cards-509610","shared_rds_data","asia-south1","crm_leads",false,false,false);
        var client=BigQueryOptions.newBuilder().setProjectId(properties.projectId()).setLocation(properties.location()).build().getService();
        try {
            if(args.length==1 && args[0].equals("jobs")){
                int count=0;
                for(var summary:client.listJobs(BigQuery.JobListOption.pageSize(30)).getValues()){
                    if(!summary.getJobId().getJob().startsWith("koc_direction_v1_"))continue;
                    var job=client.getJob(summary.getJobId());if(job==null || job.getStatus()==null)continue;
                    var error=job.getStatus().getError();
                    System.out.println("Directional job state: "+job.getStatus().getState()+"; error reason: "+(error==null?"none":error.getReason()));
                    if(error!=null){String message=error.getMessage().replaceAll("'[^']*'|\"[^\"]*\"|`[^`]*`","[redacted]").replaceAll("[0-9]{7,}","[number]");System.out.println("Sanitized SQL error: "+message);}
                    if(++count==5)break;
                }
                System.out.println("Recent directional jobs inspected: "+count+". No query or mutation submitted.");return;
            }
            var dataset=client.getDataset(DatasetId.of(properties.projectId(),properties.dataset()));
            if(dataset==null || !properties.location().equalsIgnoreCase(dataset.getLocation()))throw new IllegalStateException("Dataset missing or location mismatch");
            System.out.println("BigQuery authentication/metadata: PASS; dataset shared_rds_data; location "+dataset.getLocation());
            for(var name:DirectionalSchemaInitializer.schemas().keySet()){
                var table=client.getTable(TableId.of(properties.projectId(),properties.dataset(),name));
                System.out.println("TABLE "+name+": "+(table==null?"MISSING":"FOUND"));
                if(table!=null && table.getDefinition().getSchema()!=null)for(var field:table.getDefinition().getSchema().getFields())System.out.println("  "+field.getName()+" "+field.getType()+" "+(field.getMode()==null?Field.Mode.NULLABLE:field.getMode()));
            }
            var plan=new DirectionalSchemaInitializer(client,properties).plan();
            System.out.println("Required additive DDL statements: "+plan.size()+" (NOT EXECUTED)");
            for(var statement:plan)System.out.println(statement+";");
        } catch(BigQueryException error){System.err.println("BigQuery metadata failed: HTTP "+error.getCode()+"; verify datasets.get/tables.get permissions. No changes submitted.");System.exit(2);}
    }
}

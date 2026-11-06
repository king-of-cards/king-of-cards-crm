package com.kingofcards.crm.directional;

import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.crm.bigquery.CrmLeadSchema;
import com.kingofcards.crm.config.BigQueryProperties;

/** Explicitly opt-in additive schema provisioning. No dataset creation or row DML. */
public final class DirectionalSchemaInitializer {
    private final BigQuery client;
    private final BigQueryProperties properties;
    public DirectionalSchemaInitializer(BigQuery client,BigQueryProperties properties){this.client=client;this.properties=properties;}

    public static Map<String,Schema> schemas(){
        var master=new ArrayList<Field>();CrmLeadSchema.definition().getFields().forEach(master::add);
        for(String name:List.of("lead_direction","import_batch_id","updated_by_subject_id"))master.add(Field.of(name,StandardSQLTypeName.STRING));
        master.add(Field.of("revision",StandardSQLTypeName.INT64));
        var result=new LinkedHashMap<String,Schema>();result.put("crm_leads",Schema.of(master));
        for(var direction:LeadDirection.values())result.put(direction.table(),DirectionalSchema.detail(direction));
        return Collections.unmodifiableMap(result);
    }

    /** Preflight ALL tables before the first DDL. Missing required columns on an
     * existing table cannot safely be repaired and always block provisioning. */
    public List<String> plan(){
        properties.validateForSchemaInitialization();
        var dataset=client.getDataset(DatasetId.of(properties.projectId(),properties.dataset()));
        if(dataset==null || !properties.location().equalsIgnoreCase(dataset.getLocation()))throw new IllegalStateException("Existing BigQuery dataset/location is unavailable or incompatible; no schema changes attempted.");
        var statements=new ArrayList<String>();
        for(var entry:schemas().entrySet()){
            String table=entry.getKey(),qualified="`"+properties.projectId()+"."+properties.dataset()+"."+table+"`";
            var existing=client.getTable(TableId.of(properties.projectId(),properties.dataset(),table));
            if(existing==null){statements.add("CREATE TABLE IF NOT EXISTS "+qualified+" ("+String.join(", ",entry.getValue().getFields().stream().map(DirectionalSchemaInitializer::column).toList())+")");continue;}
            var definition=existing.getDefinition();
            if(definition==null || definition.getType()!=TableDefinition.Type.TABLE || definition.getSchema()==null)throw mismatch(table,"standard table required");
            var actual=definition.getSchema().getFields();
            for(var expected:entry.getValue().getFields()){
                var found=actual.stream().filter(f->f.getName().equals(expected.getName())).findFirst().orElse(null);
                if(found==null){if(mode(expected)==Field.Mode.REQUIRED)throw mismatch(table,"missing required column "+expected.getName());statements.add("ALTER TABLE "+qualified+" ADD COLUMN IF NOT EXISTS "+column(expected));}
                else if(!found.getType().equals(expected.getType()) || mode(found)!=mode(expected))throw mismatch(table,"type/mode mismatch for "+expected.getName());
            }
            for(var found:actual)if(mode(found)==Field.Mode.REQUIRED && entry.getValue().getFields().stream().noneMatch(f->f.getName().equals(found.getName())))throw mismatch(table,"unexpected required column "+found.getName());
        }
        return List.copyOf(statements);
    }
    public void initialize(){
        try{
            var statements=plan();
            for(String sql:statements){
                var query=QueryJobConfiguration.newBuilder(sql).setUseLegacySql(false).setUseQueryCache(false).setMaximumBytesBilled(10_000_000L).setJobTimeoutMs(20_000L).build();
                client.query(query,JobId.newBuilder().setProject(properties.projectId()).setLocation(properties.location()).setJob("koc_direction_schema_"+UUID.randomUUID().toString().replace("-","")).build());
            }
            DirectionalSchema.validate(client,properties);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("BigQuery schema initialization interrupted; outcome may be partial. Inspect metadata before restarting. No row DML was submitted.");}
        catch(BigQueryException e){throw new IllegalStateException("BigQuery schema initialization failed (HTTP "+e.getCode()+"). Check ADC, bigquery.jobs.create, datasets.get, tables.get/create/update permissions. Changes may be partial; inspect metadata before restarting. No row DML was submitted.");}
    }
    private static Field.Mode mode(Field field){return field.getMode()==null?Field.Mode.NULLABLE:field.getMode();}
    private static String column(Field field){return field.getName()+" "+field.getType().getStandardType().name()+(mode(field)==Field.Mode.REQUIRED?" NOT NULL":"");}
    private static IllegalStateException mismatch(String table,String issue){return new IllegalStateException("Incompatible BigQuery schema "+table+": "+issue+". No automatic type changes or backfill are allowed.");}
}

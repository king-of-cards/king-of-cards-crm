package com.kingofcards.crm.directional;
import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.crm.config.BigQueryProperties;
import com.kingofcards.crm.exception.LeadApiException;
import org.springframework.http.HttpStatus;

/** Read-only validation. Never creates/repairs tables or classifies legacy rows. */
public final class DirectionalSchema {
    private DirectionalSchema(){}
    public static Schema detail(LeadDirection direction){var fields=new ArrayList<Field>();
        fields.add(Field.newBuilder("lead_id",StandardSQLTypeName.STRING).setMode(Field.Mode.REQUIRED).build());
        fields.add(Field.of("vendor_name",StandardSQLTypeName.STRING));
        for(var column:direction.detailColumns())fields.add(Field.of(column.column(),StandardSQLTypeName.valueOf(column.type())));
        fields.addAll(sourceFields(direction));return Schema.of(fields);}
    public static List<Field> sourceFields(LeadDirection direction){return direction!=LeadDirection.KOC_TO_VENDOR?List.of():List.of(
        Field.of("sheet_date",StandardSQLTypeName.DATE),Field.of("sheet_time_supplied",StandardSQLTypeName.BOOL),Field.of("sheet_source_value",StandardSQLTypeName.STRING));}
    public static void validate(BigQuery client,BigQueryProperties properties){
        properties.validateForSchemaInitialization();
        try{
            var dataset=client.getDataset(DatasetId.of(properties.projectId(),properties.dataset()));
            if(dataset==null || !properties.location().equalsIgnoreCase(dataset.getLocation()))throw unavailable("Dataset or location mismatch.");
            var master=client.getTable(TableId.of(properties.projectId(),properties.dataset(),"crm_leads"));
            if(master==null)throw unavailable("crm_leads is missing.");
            if(!com.kingofcards.crm.bigquery.CrmLeadSchema.mismatches(master.getDefinition()).isEmpty())throw unavailable("Existing master columns are incompatible with the repository.");
            var schema=master.getDefinition().getSchema();
            require(schema,"lead_direction",StandardSQLTypeName.STRING,Field.Mode.NULLABLE);
            require(schema,"import_batch_id",StandardSQLTypeName.STRING,Field.Mode.NULLABLE);
            require(schema,"updated_by_subject_id",StandardSQLTypeName.STRING,Field.Mode.NULLABLE);
            require(schema,"revision",StandardSQLTypeName.INT64,Field.Mode.NULLABLE);
            for(var direction:LeadDirection.values()){
                var table=client.getTable(TableId.of(properties.projectId(),properties.dataset(),direction.table()));
                if(table==null || table.getDefinition().getType()!=TableDefinition.Type.TABLE)throw unavailable("Required directional detail table is missing.");
                for(var field:detail(direction).getFields())require(table.getDefinition().getSchema(),field.getName(),field.getType().getStandardType(),field.getMode());
                for(var field:table.getDefinition().getSchema().getFields())if(field.getMode()==Field.Mode.REQUIRED && !field.getName().equals("lead_id"))throw unavailable("Unexpected required detail column: "+field.getName());
            }
        }catch(BigQueryException exception){throw unavailable("BigQuery metadata validation is unavailable.");}
    }
    private static void require(Schema schema,String name,StandardSQLTypeName type,Field.Mode mode){
        var field=schema==null?null:schema.getFields().stream().filter(f->f.getName().equals(name)).findFirst().orElse(null);
        if(field==null || field.getType().getStandardType()!=type || (field.getMode()==null?Field.Mode.NULLABLE:field.getMode())!=(mode==null?Field.Mode.NULLABLE:mode))throw unavailable("Missing or incompatible column: "+name+". Apply the reviewed migration externally.");
    }
    private static LeadApiException unavailable(String message){return new LeadApiException(HttpStatus.SERVICE_UNAVAILABLE,"DIRECTIONAL_SCHEMA_REQUIRED",message);}
}

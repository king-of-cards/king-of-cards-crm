package com.kingofcards.crm.directional;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import com.google.cloud.bigquery.*;
import com.kingofcards.crm.config.BigQueryProperties;
import com.kingofcards.crm.directional.DirectionalModels.*;
import com.kingofcards.crm.exception.LeadApiException;
import com.kingofcards.crm.model.LeadStage;
import org.springframework.http.HttpStatus;

/** Three-table production adapter. All writes are explicit parameterized transactions;
 * SDK results are reconciled under the original job ID. No automatic DDL or cloud fallback.
 * Requires reviewed live-engine verification before enabling either write flag.
 */
public final class BigQueryDirectionalRepository implements DirectionalRepository {
    private final Supplier<BigQuery> clients;private final BigQueryProperties bigquery;private final DirectionalProperties settings;private final Clock clock;
    public BigQueryDirectionalRepository(Supplier<BigQuery> clients,BigQueryProperties bigquery,DirectionalProperties settings,Clock clock){this.clients=clients;this.bigquery=bigquery;this.settings=settings;this.clock=clock;}
    @Override public boolean writesAvailable(){return settings.enabled()&&settings.writesEnabled()&&bigquery.enabled()&&bigquery.writesEnabled();}
    private BigQuery ready(boolean write){
        if(!settings.enabled() || !bigquery.enabled())throw unavailable("DIRECTIONAL_DISABLED");
        if(write && (!settings.writesEnabled() || !bigquery.writesEnabled()))throw unavailable("DIRECTIONAL_WRITES_DISABLED");
        bigquery.validateForSchemaInitialization();var client=clients.get();if(client==null)throw unavailable("DIRECTIONAL_UNAVAILABLE");
        DirectionalSchema.validate(client,bigquery);return client;
    }
    private String table(String name){if(!name.equals("crm_leads") && Arrays.stream(LeadDirection.values()).noneMatch(d->d.table().equals(name)))throw new IllegalArgumentException("Table is not allowlisted.");return "`"+bigquery.projectId()+"."+bigquery.dataset()+"."+name+"`";}
    private QueryJobConfiguration.Builder scoped(UUID vendor,LeadDirection direction){return QueryJobConfiguration.newBuilder("SELECT 1")
        .addNamedParameter("vendor",QueryParameterValue.string(vendor.toString())).addNamedParameter("direction",QueryParameterValue.string(direction.name()));}
    private String projection(LeadDirection direction){
        var sql=new StringBuilder("m.lead_id,m.vendor_subject_id,m.lead_direction,m.vendor_name AS master_vendor_name,d.vendor_name AS detail_vendor_name,m.service,m.stage,m.source,m.revision,m.created_by_subject_id,m.updated_by_subject_id,m.created_at,m.updated_at,m.import_batch_id,d.lead_id AS detail_id");
        for(var c:direction.columns())sql.append(',').append(c.master()?"m.":"d.").append(c.column());for(var field:DirectionalSchema.sourceFields(direction))sql.append(",d.").append(field.getName());return sql.toString();
    }
    QueryJobConfiguration.Builder reportQuery(UUID vendor){
        return QueryJobConfiguration.newBuilder("SELECT lead_direction,COUNT(*) AS total,COUNTIF(stage=@new_stage) AS new_leads,COUNTIF(stage=@contacted_stage) AS contacted,COUNTIF(stage=@interested_stage) AS interested,COUNTIF(stage=@converted_stage) AS converted FROM "+table("crm_leads")+" WHERE vendor_subject_id=@vendor AND lead_direction IN UNNEST(@directions) GROUP BY lead_direction")
            .addNamedParameter("vendor",QueryParameterValue.string(vendor.toString()))
            .addNamedParameter("directions",QueryParameterValue.array(Arrays.stream(LeadDirection.values()).map(Enum::name).toArray(String[]::new),String.class))
            .addNamedParameter("new_stage",QueryParameterValue.string(LeadStage.NEW.name()))
            .addNamedParameter("contacted_stage",QueryParameterValue.string(LeadStage.CONTACTED.name()))
            .addNamedParameter("interested_stage",QueryParameterValue.string(LeadStage.INTERESTED.name()))
            .addNamedParameter("converted_stage",QueryParameterValue.string(LeadStage.CONVERTED.name()));
    }
    public Map<LeadDirection,StageCounts> report(UUID vendor){
        var result=new EnumMap<LeadDirection,StageCounts>(LeadDirection.class);
        for(var direction:LeadDirection.values())result.put(direction,StageCounts.empty());
        var seen=new HashSet<LeadDirection>();
        for(var row:read(ready(false),reportQuery(vendor))){
            try {
                var direction=LeadDirection.valueOf(row.get("lead_direction").getStringValue());
                if(!seen.add(direction))throw new IllegalArgumentException("Duplicate direction");
                long total=row.get("total").getLongValue(),fresh=row.get("new_leads").getLongValue(),contacted=row.get("contacted").getLongValue(),interested=row.get("interested").getLongValue(),converted=row.get("converted").getLongValue();
                result.put(direction,new StageCounts(total,fresh,contacted,interested,converted,total-fresh-contacted-interested-converted));
            }catch(RuntimeException invalid){throw unavailable("DIRECTIONAL_REPORT_INVALID");}
        }
        return Map.copyOf(result);
    }
    private String base(LeadDirection direction){return " FROM "+table("crm_leads")+" m LEFT JOIN "+table(direction.table())+" d ON m.lead_id=d.lead_id WHERE m.vendor_subject_id=@vendor AND m.lead_direction=@direction";}
    public List<Lead> list(UUID vendor,LeadDirection direction,Search search,int limit,boolean export){
        var client=ready(false);var query=scoped(vendor,direction);var sql=new StringBuilder("SELECT "+projection(direction)+base(direction));
        if(search.q()!=null){query.addNamedParameter("q",QueryParameterValue.string(search.q()));sql.append(" AND (");
            sql.append(String.join(" OR ",direction.columns().stream().map(c->"STRPOS(LOWER(COALESCE(CAST("+(c.master()?"m.":"d.")+c.column()+" AS STRING),'')),LOWER(@q))>0").toList())).append(')');}
        if(search.filterField()!=null){String column=switch(search.filterField()){case "__source"->"m.source";case "__service"->"m.service";case "__stage"->"m.stage";default->{var c=direction.columns().stream().filter(f->f.header().equals(search.filterField())&&f.type().equals("STRING")).findFirst().orElseThrow(()->LeadApiException.badRequest("Unknown text filter."));yield(c.master()?"m.":"d.")+c.column();}};
            sql.append(" AND ").append(column).append("=@filter");query.addNamedParameter("filter",QueryParameterValue.string(search.filterValue()));}
        query.addNamedParameter("limit",QueryParameterValue.int64(limit));query.addNamedParameter("offset",QueryParameterValue.int64(export?0:(long)search.page()*search.size()));
        sql.append(" ORDER BY m.created_at DESC,m.lead_id LIMIT @limit OFFSET @offset");
        var result=new ArrayList<Lead>();var ids=new HashSet<UUID>();long bytes=0;for(var row:read(client,query.setQuery(sql.toString()))){var value=lead(direction,row);if(!value.vendorSubjectId().equals(vendor)||!ids.add(value.id()))throw unavailable("DIRECTIONAL_INTEGRITY_ERROR");if(export){bytes+=value.fields().values().stream().filter(Objects::nonNull).mapToLong(s->s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();if(bytes>20_971_520)throw new LeadApiException(HttpStatus.PAYLOAD_TOO_LARGE,"DIRECTIONAL_EXPORT_TOO_LARGE","Complete export exceeds the safe byte limit.");}result.add(value);}return result;
    }
    public Optional<Lead> get(UUID vendor,LeadDirection direction,UUID id){var query=scoped(vendor,direction)
        .addNamedParameter("id",QueryParameterValue.string(id.toString())).setQuery("SELECT "+projection(direction)+base(direction)+" AND m.lead_id=@id LIMIT 2");
        var result=new ArrayList<Lead>();for(var row:read(ready(false),query))result.add(lead(direction,row));
        if(result.size()>1 || result.stream().anyMatch(l->!l.id().equals(id)||!l.vendorSubjectId().equals(vendor)))throw unavailable("DIRECTIONAL_INTEGRITY_ERROR");return result.stream().findFirst();}
    /** Read-only duplicate lookup for preview. Uses the same normalized Draft values and exact
     * master/detail predicates as the final transactional duplicate check in plan(). */
    @Override public Set<Integer> exactExistingRows(UUID vendor,LeadDirection direction,List<Draft> drafts){
        if(drafts==null||drafts.isEmpty())return Set.of();var matches=new LinkedHashSet<Integer>();var client=ready(false);
        final int chunkSize=200;
        for(int offset=0;offset<drafts.size();offset+=chunkSize){
            int end=Math.min(offset+chunkSize,drafts.size());var query=exactMatchQuery(vendor,direction,drafts,offset,end);
            for(var row:read(client,query)){int index=Math.toIntExact(row.get("row_index").getLongValue());long count=row.get("exact_count").getLongValue();if(index<offset||index>=end||count<1)throw unavailable("DIRECTIONAL_INTEGRITY_ERROR");if(count>1)throw unavailable("DIRECTIONAL_INTEGRITY_ERROR");matches.add(index);}
        }
        return Set.copyOf(matches);
    }
    QueryJobConfiguration.Builder exactMatchQuery(UUID vendor,LeadDirection direction,List<Draft> drafts,int offset,int end){
        var query=scoped(vendor,direction);var incoming=new ArrayList<String>();
        for(int index=offset;index<end;index++){
                var draft=drafts.get(index);String suffix="_"+index;var columns=new ArrayList<String>();columns.add(index+" AS row_index");
                for(var column:direction.columns()){
                    columns.add("@"+column.column()+suffix+" AS "+column.column());
                    query.addNamedParameter(column.column()+suffix,parameter(draft.fields().get(column.header()),column.type()));
                }
                addSourceParameters(query,columns,draft,suffix,direction==LeadDirection.KOC_TO_VENDOR);
                for(var field:List.of("service","stage","source")){
                    String value=field.equals("service")?draft.metadata().service():field.equals("stage")?draft.metadata().stage().name():draft.metadata().source();
                    columns.add("@"+field+suffix+" AS "+field);query.addNamedParameter(field+suffix,QueryParameterValue.string(value));
                }
                incoming.add("SELECT "+String.join(",",columns));
        }
        String sql="WITH incoming AS ("+String.join(" UNION ALL ",incoming)+") SELECT i.row_index,COUNT(DISTINCT m.lead_id) AS exact_count FROM incoming i JOIN "+table("crm_leads")+" m ON m.vendor_subject_id=@vendor AND m.lead_direction=@direction AND "+sameMaster()+" JOIN "+table(direction.table())+" d ON d.lead_id=m.lead_id WHERE "+sameDetail(direction,direction==LeadDirection.KOC_TO_VENDOR)+" GROUP BY i.row_index";
        return query.setQuery(sql).setUseLegacySql(false).setUseQueryCache(false).setMaximumBytesBilled(100_000_000L).setJobTimeoutMs(20_000L);
    }
    private static String sameMaster(){return "m.customer_name IS NOT DISTINCT FROM i.customer_name AND m.phone IS NOT DISTINCT FROM i.phone AND m.service=i.service AND m.stage=i.stage AND m.source=i.source";}
    private static String sameDetail(LeadDirection direction,boolean precision){
        String result=String.join(" AND ",direction.detailColumns().stream().map(c->"d."+c.column()+" IS NOT DISTINCT FROM i."+c.column()).toList());
        // Old rows have no provenance columns: derive only from a real stored timestamp.
        // Original cell text is provenance, not a separate business identity.
        return precision?result+" AND COALESCE(d.sheet_date,DATE(d.sheet_timestamp,'Asia/Kolkata')) IS NOT DISTINCT FROM i.sheet_date AND COALESCE(d.sheet_time_supplied,d.sheet_timestamp IS NOT NULL) IS NOT DISTINCT FROM COALESCE(i.sheet_time_supplied,FALSE)":result;
    }
    private static void addSourceParameters(QueryJobConfiguration.Builder query,List<String> columns,Draft draft,String suffix,boolean precision){
        if(!precision)return;
        var source=draft.sourceDateTime()!=null?draft.sourceDateTime():SourceDateTime.fromTimestamp(draft.fields().get("Timestamp"));
        if(source!=null)source.validate(LeadDirection.KOC_TO_VENDOR,draft.fields().get("Timestamp"));
        for(String name:List.of("sheet_date","sheet_time_supplied","sheet_source_value"))columns.add("@"+name+suffix+" AS "+name);
        query.addNamedParameter("sheet_date"+suffix,QueryParameterValue.date(source==null?null:source.date().toString()));
        query.addNamedParameter("sheet_time_supplied"+suffix,QueryParameterValue.of(source==null?null:source.timeSupplied(),StandardSQLTypeName.BOOL));
        query.addNamedParameter("sheet_source_value"+suffix,QueryParameterValue.string(source==null?null:source.originalValue()));
    }
    private static boolean precision(Job job){return job.getConfiguration() instanceof QueryJobConfiguration q && q.getNamedParameters().containsKey("sheet_date_0");}
    private static SourceDateTime sourceDateTime(Map<String,QueryParameterValue> p,int index){
        var date=p.get("sheet_date_"+index);if(date==null||date.getValue()==null)return null;
        return new SourceDateTime(LocalDate.parse(date.getValue()),Boolean.parseBoolean(p.get("sheet_time_supplied_"+index).getValue()),p.get("sheet_source_value_"+index).getValue());
    }
    private Iterable<FieldValueList> read(BigQuery client,QueryJobConfiguration.Builder query){try{return client.query(query.setUseLegacySql(false).setUseQueryCache(false).setMaximumBytesBilled(100_000_000L).setJobTimeoutMs(20_000L).build(),JobId.newBuilder().setProject(bigquery.projectId()).setLocation(bigquery.location()).setJob("koc_direction_read_"+UUID.randomUUID().toString().replace("-","")).build()).iterateAll();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable("DIRECTIONAL_UNAVAILABLE");}catch(BigQueryException e){throw unavailable("DIRECTIONAL_UNAVAILABLE");}}
    private Lead lead(LeadDirection direction,FieldValueList row){try{
        String id=row.get("lead_id").getStringValue();String masterVendorName=nullableString(row.get("master_vendor_name")),detailVendorName=nullableString(row.get("detail_vendor_name"));if(!direction.name().equals(row.get("lead_direction").getStringValue()) || row.get("revision").isNull() || row.get("revision").getLongValue()<1 || row.get("detail_id").isNull() || !id.equals(row.get("detail_id").getStringValue()) || !Objects.equals(masterVendorName,detailVendorName))throw unavailable("DIRECTIONAL_INTEGRITY_ERROR");
        var fields=new LinkedHashMap<String,String>();for(var c:direction.columns()){var v=row.get(c.column());String value=null;if(!v.isNull())value=c.type().equals("TIMESTAMP")?instant(v).toString():c.type().equals("NUMERIC")?v.getNumericValue().stripTrailingZeros().toPlainString():v.getStringValue();fields.put(c.header(),value);}
        return new Lead(UUID.fromString(id),UUID.fromString(row.get("vendor_subject_id").getStringValue()),direction,masterVendorName,fields,
            new Metadata(row.get("service").getStringValue(),LeadStage.valueOf(row.get("stage").getStringValue()),row.get("source").getStringValue()),
            row.get("revision").getLongValue(),UUID.fromString(row.get("created_by_subject_id").getStringValue()),UUID.fromString(row.get("updated_by_subject_id").getStringValue()),
            instant(row.get("created_at")),instant(row.get("updated_at")),row.get("import_batch_id").isNull()?null:UUID.fromString(row.get("import_batch_id").getStringValue()),readSourceDateTime(direction,row,fields.get("Timestamp")));
    }catch(LeadApiException e){throw e;}catch(RuntimeException e){throw unavailable("DIRECTIONAL_INTEGRITY_ERROR");}}
    private static SourceDateTime readSourceDateTime(LeadDirection direction,FieldValueList row,String timestamp){
        if(direction!=LeadDirection.KOC_TO_VENDOR)return null;
        var date=nullableString(row.get("sheet_date"));var supplied=row.get("sheet_time_supplied");var original=nullableString(row.get("sheet_source_value"));
        if(date==null&&supplied.isNull()&&original==null)return null; // Historical row; no backfill.
        if(date==null||supplied.isNull()||original==null)throw unavailable("DIRECTIONAL_INTEGRITY_ERROR");
        return new SourceDateTime(LocalDate.parse(date),supplied.getBooleanValue(),original).validate(direction,timestamp);
    }
    private static String nullableString(FieldValue value){return value==null||value.isNull()?null:value.getStringValue();}
    private static Instant instant(FieldValue value){long micros=value.getTimestampValue();return Instant.ofEpochSecond(Math.floorDiv(micros,1_000_000),Math.floorMod(micros,1_000_000)*1000);}
    private JobId job(UUID actor,UUID op){return JobId.newBuilder().setProject(bigquery.projectId()).setLocation(bigquery.location()).setJob("koc_direction_v1_"+actor.toString().replace("-","")+"_"+op.toString().replace("-","")).build();}
    public Receipt write(UUID vendor,LeadDirection direction,UUID actor,UUID operation,Instant submittedAt,UUID editId,long expected,List<Draft> drafts,boolean importing,String trustedVendorName){
        return writeBound(vendor,direction,actor,operation,submittedAt,editId,expected,drafts,importing,trustedVendorName,null);
    }
    @Override public Receipt writeScopedImport(UUID vendor,LeadDirection direction,UUID actor,UUID operation,Instant submittedAt,List<Draft> drafts,String trustedVendorName,String requestScope){
        return writeBound(vendor,direction,actor,operation,submittedAt,null,0,drafts,true,trustedVendorName,requestScope);
    }
    static QueryJobConfiguration bindSelection(QueryJobConfiguration query,String requestScope){
        if(requestScope==null)return query;
        if(!requestScope.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid import request binding");
        var parameters=new LinkedHashMap<>(query.getNamedParameters());parameters.put("selection_scope",QueryParameterValue.string(requestScope));
        return query.toBuilder().setNamedParameters(parameters).setQuery("DECLARE scoped_import_request STRING DEFAULT @selection_scope;\n"+query.getQuery()).build();
    }
    private Receipt writeBound(UUID vendor,LeadDirection direction,UUID actor,UUID operation,Instant submittedAt,UUID editId,long expected,List<Draft> drafts,boolean importing,String trustedVendorName,String requestScope){
        String vendorName=trustedName(trustedVendorName);var client=ready(true);var query=bindSelection(plan(vendor,direction,actor,operation,submittedAt,editId,expected,drafts,importing,vendorName),requestScope);var id=job(actor,operation);
        try{
            Job existing=client.getJob(id);
            if(existing==null){if(!submittedAt.plusSeconds(300).isAfter(clock.instant()))throw conflict("Operation expired; reconcile before resubmission.");
                try{existing=client.create(JobInfo.newBuilder(query).setJobId(id).build());}catch(BigQueryException e){existing=client.getJob(id);if(existing==null)throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");}}
            query=bindSelection(plan(vendor,direction,actor,operation,submittedAt,editId,expected,drafts,importing,operationVendorName(existing,drafts.size()),precision(existing)),requestScope);
            bind(existing,query,id);var finished=await(client,existing);bind(finished,query,id);return receipt(finished,vendor,direction,actor,operation);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");}catch(LeadApiException e){throw e;}catch(RuntimeException e){throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");}
    }
    public Receipt operation(UUID vendor,LeadDirection direction,UUID actor,UUID operation){
        try{var job=ready(false).getJob(job(actor,operation));if(job==null)return new Receipt(operation,"NOT_FOUND",0,0,0,List.of());
            if(job.getStatus()==null || job.getStatus().getState()!=JobStatus.State.DONE)return new Receipt(operation,"RUNNING",0,0,0,List.of());
            return receipt(job,vendor,direction,actor,operation);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");}catch(LeadApiException e){throw e;}catch(RuntimeException e){throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");}
    }
    private Job await(BigQuery client,Job job) throws InterruptedException {
        var id=job.getJobId();for(int poll=0;poll<100 && (job.getStatus()==null || job.getStatus().getState()!=JobStatus.State.DONE);poll++){Thread.sleep(500);job=client.getJob(id);if(job==null)throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");}
        if(job.getStatus()==null || job.getStatus().getState()!=JobStatus.State.DONE)throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");return job;
    }
    private static void bind(Job job,QueryJobConfiguration query,JobId id){
        if(job==null || !id.equals(job.getJobId()) || !(job.getConfiguration() instanceof QueryJobConfiguration actual)
            || !query.getQuery().equals(actual.getQuery()) || !query.getNamedParameters().equals(actual.getNamedParameters())
            || !Objects.equals(query.getMaximumBytesBilled(),actual.getMaximumBytesBilled()) || !Objects.equals(query.getJobTimeoutMs(),actual.getJobTimeoutMs())
            || !Boolean.FALSE.equals(actual.useLegacySql()) || !Boolean.FALSE.equals(actual.useQueryCache()))throw conflict("Operation ID belongs to a different request.");
    }
    private Receipt receipt(Job job,UUID vendor,LeadDirection direction,UUID actor,UUID op) throws InterruptedException {
        if(!(job.getConfiguration() instanceof QueryJobConfiguration query))throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");var p=query.getNamedParameters();
        if(!Objects.equals(p.get("vendor"),QueryParameterValue.string(vendor.toString())) || !Objects.equals(p.get("direction"),QueryParameterValue.string(direction.name()))
            || !Objects.equals(p.get("actor"),QueryParameterValue.string(actor.toString())))throw conflict("Operation scope mismatch.");
        // Reconstruct the allowlisted request from the immutable original job. Reconciliation
        // must never accept arbitrary SQL or a job that only happens to share scope parameters.
        var drafts=new ArrayList<Draft>();
        for(int i=0;p.containsKey("id_"+i);i++){
            if(i>=settings.importLimit())throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");
            var fields=new LinkedHashMap<String,String>();
            for(var c:direction.columns()){var parameter=p.get(c.column()+"_"+i);if(parameter==null)throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");String value=parameter.getValue();if(value!=null && c.type().equals("TIMESTAMP"))value=parameterInstant(value).toString();fields.put(c.header(),value);}
            drafts.add(new Draft(direction.normalize(fields),new Metadata(p.get("service_"+i).getValue(),LeadStage.valueOf(p.get("stage_"+i).getValue()),p.get("source_"+i).getValue()),sourceDateTime(p,i)));
        }
        boolean editing=Boolean.parseBoolean(p.get("edit").getValue()), importing=p.get("batch").getValue()!=null;
        if(drafts.isEmpty() || (importing&&!op.toString().equals(p.get("batch").getValue())))throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");
        UUID editId=editing?UUID.fromString(p.get("id_0").getValue()):null;
        var trusted=plan(vendor,direction,actor,op,parameterInstant(p.get("deadline").getValue()).minusSeconds(300),editId,Long.parseLong(p.get("expected").getValue()),drafts,importing,operationVendorName(job,drafts.size()),precision(job));
        if(p.containsKey("selection_scope")){
            var selection=p.get("selection_scope");
            if(!importing||editing||selection==null||selection.getValue()==null)throw conflict("Operation scope mismatch.");
            // Rebuild the exact allowlisted wrapper from the original job. Omitting
            // it would report a false 409 even after a successful committed import.
            trusted=bindSelection(trusted,selection.getValue());
        }
        bind(job,trusted,job(actor,op));
        if(job.getStatus().getError()!=null)throw unavailable("DIRECTIONAL_JOB_FAILED_RECONCILE_REQUIRED");
        var rows=new ArrayList<RowResult>();int imported=0,skipped=0,failed=0;
        for(var row:job.getQueryResults().iterateAll()){
            String status=row.get("status").getStringValue();if(status.equals("IMPORTED") || status.equals("UPDATED"))imported++;else if(status.equals("SKIPPED"))skipped++;else if(status.equals("FAILED"))failed++;else throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");
            int index=Math.toIntExact(row.get("row_index").getLongValue());UUID id=UUID.fromString(row.get("lead_id").getStringValue());
            if(index!=rows.size()+1 || index>drafts.size() || (!status.equals("SKIPPED")&&!id.toString().equals(p.get("id_"+(index-1)).getValue())) || (status.equals("UPDATED")!=editing && !status.equals("FAILED")))throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");
            rows.add(new RowResult(index,id,status,row.get("error").isNull()?null:row.get("error").getStringValue()));
        }
        if(rows.size()!=drafts.size())throw unavailable("DIRECTIONAL_COMMIT_UNCERTAIN");return new Receipt(op,"COMMITTED",imported,skipped,failed,rows);
    }
    private static Instant parameterInstant(String value){try{return Instant.parse(value.replace(' ','T'));}catch(java.time.format.DateTimeParseException e){return Instant.ofEpochSecond(0,new java.math.BigDecimal(value).movePointRight(9).longValueExact());}}
    private static String operationVendorName(Job job,int rows){
        if(!(job.getConfiguration() instanceof QueryJobConfiguration query))throw conflict("Operation ID belongs to a different request.");var parameters=query.getNamedParameters();String name=null;
        for(int i=0;i<rows;i++){var value=parameters.get("vendor_name_"+i);if(value==null || value.getValue()==null)throw conflict("Operation ID belongs to a different request.");String current=value.getValue();if(current.isBlank()||current.strip().length()>120||(name!=null&&!name.equals(current)))throw conflict("Operation ID belongs to a different request.");name=current;}
        if(name==null)throw conflict("Operation ID belongs to a different request.");return name;
    }
    private static String trustedName(String value){if(value==null||value.isBlank()||value.strip().length()>120)throw new LeadApiException(HttpStatus.FORBIDDEN,"VENDOR_DISPLAY_NAME_REQUIRED","A verified vendor display name is required.");return value.strip();}
    QueryJobConfiguration plan(UUID vendor,LeadDirection direction,UUID actor,UUID operation,Instant submittedAt,UUID editId,long expected,List<Draft> drafts,boolean importing,String vendorName){
        return plan(vendor,direction,actor,operation,submittedAt,editId,expected,drafts,importing,vendorName,direction==LeadDirection.KOC_TO_VENDOR);
    }
    QueryJobConfiguration plan(UUID vendor,LeadDirection direction,UUID actor,UUID operation,Instant submittedAt,UUID editId,long expected,List<Draft> drafts,boolean importing,String vendorName,boolean precision){
        if(precision&&direction!=LeadDirection.KOC_TO_VENDOR)throw new IllegalArgumentException("Invalid precision scope");
        if(!precision&&drafts.stream().anyMatch(d->d.sourceDateTime()!=null))throw conflict("Operation ID belongs to a different request.");
        vendorName=trustedName(vendorName);
        if(drafts.isEmpty() || drafts.size()>settings.importLimit() || (editId!=null && drafts.size()!=1))throw LeadApiException.badRequest("Invalid mutation batch size.");
        var query=scoped(vendor,direction).addNamedParameter("actor",QueryParameterValue.string(actor.toString()))
            .addNamedParameter("deadline",QueryParameterValue.timestamp(micros(submittedAt.plusSeconds(300))))
            .addNamedParameter("batch",QueryParameterValue.of(importing?operation.toString():null,StandardSQLTypeName.STRING))
            .addNamedParameter("edit",QueryParameterValue.bool(editId!=null)).addNamedParameter("expected",QueryParameterValue.int64(expected));
        var incoming=new ArrayList<String>();
        for(int i=0;i<drafts.size();i++){
            var draft=drafts.get(i);String suffix="_"+i;UUID id=editId==null?DirectionalKeys.leadId(vendor,direction,draft):editId;
            var columns=new ArrayList<String>();columns.add((i+1)+" AS row_index");columns.add("@id"+suffix+" AS lead_id");query.addNamedParameter("id"+suffix,QueryParameterValue.string(id.toString()));
            for(var c:direction.columns()){columns.add("@"+c.column()+suffix+" AS "+c.column());query.addNamedParameter(c.column()+suffix,parameter(draft.fields().get(c.header()),c.type()));}
            addSourceParameters(query,columns,draft,suffix,precision);
            for(var c:List.of("service","stage","source")){columns.add("@"+c+suffix+" AS "+c);String value=c.equals("service")?draft.metadata().service():c.equals("stage")?draft.metadata().stage().name():draft.metadata().source();query.addNamedParameter(c+suffix,QueryParameterValue.string(value));}
            columns.add("@vendor_name"+suffix+" AS vendor_name");query.addNamedParameter("vendor_name"+suffix,QueryParameterValue.string(vendorName));
            incoming.add("SELECT "+String.join(",",columns));
        }
        String master=table("crm_leads"),detail=table(direction.table());
        String sameMaster=sameMaster();
        String sameDetail=sameDetail(direction,precision);
        var storedDetails=new ArrayList<String>(direction.detailColumns().stream().map(LeadDirection.Column::column).toList());
        if(precision)storedDetails.addAll(DirectionalSchema.sourceFields(direction).stream().map(Field::getName).toList());
        String detailColumns=String.join(",",java.util.stream.Stream.concat(storedDetails.stream(),java.util.stream.Stream.of("vendor_name")).toList());
        String detailSelect=String.join(",",java.util.stream.Stream.concat(storedDetails.stream().map(c->"i."+c),java.util.stream.Stream.of("i.vendor_name")).toList());
        String updateDetail=String.join(",",java.util.stream.Stream.concat(storedDetails.stream().map(c->c+"=i."+c),java.util.stream.Stream.of("vendor_name=i.vendor_name")).toList());
        String sql="DECLARE now_ts TIMESTAMP; ASSERT CURRENT_TIMESTAMP()<@deadline AS 'DIRECTIONAL_OPERATION_EXPIRED';\nBEGIN TRANSACTION;\n"
            +"DELETE FROM "+master+" WHERE FALSE; ASSERT @@row_count=0 AS 'MASTER_LOCK';\nDELETE FROM "+detail+" WHERE FALSE; ASSERT @@row_count=0 AS 'DETAIL_LOCK'; SET now_ts=CURRENT_TIMESTAMP();\n"
            +"CREATE TEMP TABLE incoming AS "+String.join(" UNION ALL ",incoming)+";\n"
            +"ASSERT NOT EXISTS(SELECT lead_id FROM "+master+" GROUP BY lead_id HAVING COUNT(*)>1) AS 'MASTER_ID_INTEGRITY';\n"
            +"ASSERT NOT EXISTS(SELECT lead_id FROM "+detail+" GROUP BY lead_id HAVING COUNT(*)>1) AS 'DETAIL_ID_INTEGRITY';\n"
            +"ASSERT NOT EXISTS(SELECT m.lead_id FROM "+master+" m JOIN "+detail+" d USING(lead_id) WHERE m.lead_direction=@direction AND m.vendor_name IS DISTINCT FROM d.vendor_name) AS 'VENDOR_NAME_INTEGRITY';\n"
            +"CREATE TEMP TABLE exact_matches AS SELECT i.row_index,m.lead_id AS matched_id FROM incoming i JOIN "+master+" m ON m.vendor_subject_id=@vendor AND m.lead_direction=@direction AND "+sameMaster+" JOIN "+detail+" d ON d.lead_id=m.lead_id WHERE "+sameDetail+";\n"
            +"ASSERT NOT EXISTS(SELECT row_index FROM exact_matches GROUP BY row_index HAVING COUNT(*)>1) AS 'EXACT_DUPLICATE_INTEGRITY';\n"
            +"CREATE TEMP TABLE outcomes AS SELECT i.row_index,IF(NOT @edit AND e.matched_id IS NOT NULL,e.matched_id,i.lead_id) AS lead_id,CASE WHEN @edit THEN CASE WHEN m.lead_id IS NULL OR m.vendor_subject_id!=@vendor OR m.lead_direction IS NULL OR m.lead_direction!=@direction OR d.lead_id IS NULL OR m.revision IS NULL OR m.revision!=@expected OR (e.matched_id IS NOT NULL AND e.matched_id!=i.lead_id) THEN 'FAILED' ELSE 'UPDATED' END\n"
            +" WHEN ROW_NUMBER() OVER(PARTITION BY i.lead_id ORDER BY i.row_index)>1 OR e.matched_id IS NOT NULL THEN 'SKIPPED' WHEN m.lead_id IS NULL AND d.lead_id IS NULL THEN 'IMPORTED' ELSE 'FAILED' END AS status\n"
            +"FROM incoming i LEFT JOIN "+master+" m ON m.lead_id=i.lead_id LEFT JOIN "+detail+" d ON d.lead_id=i.lead_id LEFT JOIN exact_matches e USING(row_index);\n"
            +"INSERT INTO "+master+" (lead_id,customer_name,phone,service,stage,source,vendor_subject_id,created_by_subject_id,updated_by_subject_id,created_at,updated_at,lead_direction,import_batch_id,revision,vendor_name)\n"
            +"SELECT i.lead_id,i.customer_name,i.phone,i.service,i.stage,i.source,@vendor,@actor,@actor,now_ts,now_ts,@direction,@batch,1,i.vendor_name FROM incoming i JOIN outcomes o USING(row_index) WHERE o.status='IMPORTED';\n"
            +"ASSERT @@row_count=(SELECT COUNT(*) FROM outcomes WHERE status='IMPORTED') AS 'MASTER_INSERT_ROWS';\n"
            +"INSERT INTO "+detail+" (lead_id,"+detailColumns+") SELECT i.lead_id,"+detailSelect+" FROM incoming i JOIN outcomes o USING(row_index) WHERE o.status='IMPORTED';\n"
            +"ASSERT @@row_count=(SELECT COUNT(*) FROM outcomes WHERE status='IMPORTED') AS 'DETAIL_INSERT_ROWS';\n"
            +"UPDATE "+master+" m SET customer_name=i.customer_name,phone=i.phone,service=i.service,stage=i.stage,source=i.source,vendor_name=i.vendor_name,updated_by_subject_id=@actor,updated_at=now_ts,revision=m.revision+1\n"
            +"FROM incoming i JOIN outcomes o USING(row_index) WHERE m.lead_id=i.lead_id AND m.vendor_subject_id=@vendor AND m.lead_direction=@direction AND o.status='UPDATED';\n"
            +"ASSERT @@row_count=(SELECT COUNT(*) FROM outcomes WHERE status='UPDATED') AS 'MASTER_UPDATE_ROWS';\n"
            +"UPDATE "+detail+" d SET "+updateDetail+" FROM incoming i JOIN outcomes o USING(row_index) WHERE d.lead_id=i.lead_id AND o.status='UPDATED';\n"
            +"ASSERT @@row_count=(SELECT COUNT(*) FROM outcomes WHERE status='UPDATED') AS 'DETAIL_UPDATE_ROWS';\nCOMMIT TRANSACTION;\n"
            +"SELECT row_index,lead_id,status,IF(status='FAILED','Existing lead changed, is incomplete, or revision does not match; no overwrite was performed.',NULL) AS error FROM outcomes ORDER BY row_index;";
        return query.setQuery(sql).setUseLegacySql(false).setUseQueryCache(false).setMaximumBytesBilled(536_870_912L).setJobTimeoutMs(45_000L).build();
    }
    private static long micros(Instant value){return Math.addExact(Math.multiplyExact(value.getEpochSecond(),1_000_000L),value.getNano()/1000);}
    private static QueryParameterValue parameter(String value,String type){
        if(value==null)return QueryParameterValue.of(null,StandardSQLTypeName.valueOf(type));
        return switch(type){case "TIMESTAMP"->QueryParameterValue.timestamp(micros(Instant.parse(value)));case "DATE"->QueryParameterValue.date(value);case "INT64"->QueryParameterValue.int64(Long.parseLong(value));case "NUMERIC"->QueryParameterValue.numeric(new java.math.BigDecimal(value));default->QueryParameterValue.string(value);};
    }
    private static LeadApiException unavailable(String code){return new LeadApiException(HttpStatus.SERVICE_UNAVAILABLE,code,"Directional storage is unavailable or the commit is uncertain. Reconcile the original operation; do not blindly retry.");}
    private static LeadApiException conflict(String message){return new LeadApiException(HttpStatus.CONFLICT,"DIRECTIONAL_CONFLICT",message);}
}

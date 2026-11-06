package com.kingofcards.crm.directional;

import java.util.*;import java.nio.charset.StandardCharsets;
import com.kingofcards.crm.directional.DirectionalModels.*;
import com.kingofcards.crm.exception.LeadApiException;
import org.junit.jupiter.api.*;import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

class DirectionalImportSelectionTest {
 final DirectionalWorkflowTest f=new DirectionalWorkflowTest();final LeadDirection d=LeadDirection.KOC_TO_VENDOR;
 final Metadata metadata=new Metadata("Existing service",null,null);
 final ImportSelection auto=new ImportSelection("AUTO",List.of());
 MockMultipartFile file(String...names){StringBuilder csv=new StringBuilder("Name,Phone\r\n");for(String name:names)csv.append(name).append(",001234567890\r\n");return new MockMultipartFile("file","synthetic-selection.csv","text/csv",csv.toString().getBytes(StandardCharsets.UTF_8));}
 Preview preview(MockMultipartFile file,ImportSelection selection){return f.service.preview(f.vendor,d,file,metadata,false,selection,f.auth(f.admin,"CRM_ADMIN"));}
 Receipt commit(MockMultipartFile file,Preview p){return f.service.importFile(f.vendor,d,file,metadata,false,p.operationId(),p.submittedAt(),p.fingerprint(),p.selection(),f.auth(f.admin,"CRM_ADMIN"));}
 @Test void autoExcludesOnlyVerifiedDuplicatesAndNeverSendsThemToPersistence() throws Exception {
  f.create(d,f.draft(d,"Synthetic A"));var file=file("Synthetic A","Synthetic A","Synthetic B");byte[] original=file.getBytes();var p=preview(file,auto);
  assertThat(p.selection().excludedRows()).containsExactly(2,3);assertThat(p.rows()).extracting(PreviewRow::classification).containsExactly("EXISTING_IN_BIGQUERY","DUPLICATE_IN_FILE","NEW");
  assertThat(p.rows().get(1).duplicateOfRow()).isEqualTo(2);var receipt=commit(file,p);assertThat(receipt.imported()).isEqualTo(1);assertThat(receipt.skipped()).isZero();assertThat(receipt.rows()).hasSize(1);assertThat(file.getBytes()).isEqualTo(original);
 }
 @Test void restoredDuplicatesKeepFinalTransactionalDuplicateProtection(){
  var file=file("Synthetic A","Synthetic A");var p=preview(file,new ImportSelection("EXPLICIT",List.of()));var r=commit(file,p);assertThat(r.imported()).isEqualTo(1);assertThat(r.skipped()).isEqualTo(1);
 }
 @Test void newOrInvalidRowsCannotBeExcludedToBypassAllOrNothingValidation(){
  assertThatThrownBy(()->preview(file("Synthetic A","Synthetic B"),new ImportSelection("EXPLICIT",List.of(2)))).isInstanceOf(LeadApiException.class).hasMessageContaining("Only verified duplicate");
  var bad=new MockMultipartFile("file","invalid.csv","text/csv","Name,Phone\nSynthetic A,001234567890\nSynthetic A,001234567890\nInvalid,not-a-phone\n".getBytes(StandardCharsets.UTF_8));
  var p=preview(bad,auto);assertThat(p.rows().get(2).errors()).isNotEmpty();assertThatThrownBy(()->commit(bad,p)).isInstanceOf(LeadApiException.class).hasMessageContaining("all validation errors");
  assertThat(f.repo.list(f.vendor,d,new Search(0,20,null,null,null),20,false)).isEmpty();
 }
 @Test void duplicateAppearingBetweenPreviewAndConfirmIsStillSkipped(){
  var file=file("Synthetic A","Synthetic B");var p=preview(file,auto);f.create(d,f.draft(d,"Synthetic A"));var r=commit(file,p);assertThat(r.imported()).isEqualTo(1);assertThat(r.skipped()).isEqualTo(1);
 }
 @Test void committedRetryUsesOriginalOperationAndFrozenSelectionEvenAfterRowsNowExist(){
  f.create(d,f.draft(d,"Synthetic A"));var file=file("Synthetic A","Synthetic B");var p=preview(file,auto);var first=commit(file,p);assertThat(commit(file,p)).isEqualTo(first);
  assertThat(f.service.operation(f.vendor,d,p.operationId(),f.auth(f.admin,"CRM_ADMIN"))).isEqualTo(first);
 }
 @Test void allExistingRowsDoNotCreateAnImportJob(){
  f.create(d,f.draft(d,"Synthetic A"));var file=file("Synthetic A");var p=preview(file,auto);
  assertThatThrownBy(()->commit(file,p)).isInstanceOf(LeadApiException.class).hasMessageContaining("All records already imported");
  assertThat(f.service.operation(f.vendor,d,p.operationId(),f.auth(f.admin,"CRM_ADMIN")).state()).isEqualTo("NOT_FOUND");
 }
 @Test void changingPhysicalSelectionConflictsEvenWhenSelectedBusinessValuesAreIdentical(){
  f.create(d,f.draft(d,"Synthetic A"));var file=file("Synthetic A","Synthetic A","Synthetic A","Synthetic B");
  var original=preview(file,new ImportSelection("EXPLICIT",List.of(2,4)));commit(file,original);
  var changed=preview(file,new ImportSelection("EXPLICIT",List.of(3,4)));
  assertThatThrownBy(()->f.service.importFile(f.vendor,d,file,metadata,false,original.operationId(),original.submittedAt(),changed.fingerprint(),changed.selection(),f.auth(f.admin,"CRM_ADMIN"))).isInstanceOf(LeadApiException.class).hasMessageContaining("different content");
 }
 @Test void selectedScopeRetainsVendorAuthorizationAndValidatesRowNumbers(){
  assertThatThrownBy(()->f.service.preview(f.vendor,d,file("Synthetic A"),metadata,false,auto,f.auth(UUID.randomUUID(),"CRM_VENDOR"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  assertThatThrownBy(()->new ImportSelection("EXPLICIT",List.of(2,2))).isInstanceOf(LeadApiException.class);
  assertThatThrownBy(()->new ImportSelection(null,List.of())).isInstanceOf(LeadApiException.class);
  assertThatThrownBy(()->preview(file("Synthetic A"),new ImportSelection("EXPLICIT",List.of(99)))).isInstanceOf(LeadApiException.class);
 }
 @Test void bigQuerySelectionBindingDoesNotChangeTheExistingTransactionOrDuplicatePredicate(){
  var fixture=new DirectionalBigQuerySafetyTest();var base=fixture.repo(false).plan(fixture.vendor,d,fixture.actor,fixture.op,f.now,null,0,List.of(f.draft(d,"Synthetic A")),true,"Trusted vendor");
  var bound=BigQueryDirectionalRepository.bindSelection(base,"a".repeat(64));assertThat(bound.getQuery()).endsWith(base.getQuery());
  assertThat(bound.getNamedParameters()).containsAllEntriesOf(base.getNamedParameters());assertThat(bound.getNamedParameters().get("selection_scope").getValue()).isEqualTo("a".repeat(64));
  assertThatThrownBy(()->BigQueryDirectionalRepository.bindSelection(base,"invalid;SELECT" )).isInstanceOf(IllegalArgumentException.class);
 }
}

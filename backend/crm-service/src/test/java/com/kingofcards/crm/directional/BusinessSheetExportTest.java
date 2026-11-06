package com.kingofcards.crm.directional;

import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.kingofcards.crm.directional.DirectionalModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

class BusinessSheetExportTest {
    final DirectionalWorkflowTest fixture = new DirectionalWorkflowTest();
    @Test void kocCsvAndXlsxRoundTripBusinessFieldsSourcePrecisionAndReadonlyReferences() {
        for (boolean timed : List.of(false, true)) {
            var fields = new HashMap<>(fixture.draft(LeadDirection.KOC_TO_VENDOR, "Synthetic export").fields());
            fields.put("Phone", "+91 00123456789"); fields.put("occasion", "Wedding"); fields.put("occasion_date", "2026-12-20");
            fields.put("Quoted amount", "4000.25"); fields.put("Relation", "historical optional value");
            fields.put("Timestamp", timed ? "2026-10-07T07:11:17.123456Z" : null);
            var source = new SourceDateTime(LocalDate.parse(timed ? "2026-10-07" : "2026-10-13"), timed, timed ? "2026-10-07T12:41:17.123456+05:30" : "13-10-2026");
            var lead = new Lead(UUID.randomUUID(), fixture.vendor, LeadDirection.KOC_TO_VENDOR, "Trusted vendor", fields,
                new Metadata("photography", null, null), 1, fixture.admin, fixture.admin, fixture.now, fixture.now, null, source);
            for (String format : List.of("csv", "xlsx")) {
                var bytes = SheetFiles.export(LeadDirection.KOC_TO_VENDOR, List.of(lead), format);
                var preview = fixture.service.preview(fixture.vendor, LeadDirection.KOC_TO_VENDOR,
                    new MockMultipartFile("file", "export." + format, "application/octet-stream", bytes), lead.metadata(), format.equals("csv"), fixture.auth(fixture.admin, "CRM_ADMIN"));
                assertThat(preview.rows()).hasSize(1); var row = preview.rows().getFirst(); assertThat(row.errors()).isEmpty();
                assertThat(row.fields()).containsEntry("Phone", "+91 00123456789").containsEntry("Relation", "historical optional value").containsEntry("occasion_date", "2026-12-20").containsEntry("Quoted amount", "4000.25");
                assertThat(row.fields()).doesNotContainKeys("Vendor Name", "Lead ID", "vendor_name", "lead_id");
                assertThat(row.sourceDateTime().timeSupplied()).isEqualTo(timed); assertThat(row.sourceDateTime().date()).isEqualTo(source.date());
                assertThat(row.fields().get("Timestamp")).isEqualTo(fields.get("Timestamp"));
                if (format.equals("csv")) assertThat(new String(bytes, StandardCharsets.UTF_8).split("\r\n")[0]).startsWith("\uFEFF\"Date & Time\",\"Branch\",\"Name\",\"Phone\",\"Occasion / Event\",\"Event Date\",\"Updates\",\"Additional Comments (Optional)\",\"Quoted Amount\",\"Date Quoted\",\"Vendor Name\",\"Lead ID\"");
            }
        }
    }
    @Test void trustedExportReferenceCannotOverrideVendorAndMalformedIdIsReportedWithoutDiscardingRow() {
        var bytes = "Name,Phone,Vendor Name,Lead ID\nSynthetic,001234567890,Untrusted,not-a-uuid\n".getBytes(StandardCharsets.UTF_8);
        var preview = fixture.service.preview(fixture.vendor, LeadDirection.KOC_TO_VENDOR, new MockMultipartFile("file", "references.csv", "text/csv", bytes), new Metadata("photography", null, null), false, fixture.auth(fixture.admin, "CRM_ADMIN"));
        assertThat(preview.rows()).hasSize(1); assertThat(preview.rows().getFirst().errors()).anyMatch(error -> error.contains("Lead ID"));
        assertThat(preview.rows().getFirst().fields()).doesNotContainKey("Vendor Name");
    }
}

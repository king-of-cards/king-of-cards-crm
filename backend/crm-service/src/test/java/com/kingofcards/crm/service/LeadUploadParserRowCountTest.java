package com.kingofcards.crm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.kingofcards.crm.directional.DirectionalModels.*;
import com.kingofcards.crm.directional.LeadDirection;
import com.kingofcards.crm.directional.DirectionalProperties;
import com.kingofcards.crm.directional.DirectionalRepository;
import com.kingofcards.crm.directional.DirectionalService;

class LeadUploadParserRowCountTest {
    private static final int MAX_BYTES = 2_097_152;
    private static final int DEFAULT_ROWS = 2_000;

    private LeadUploadParser parser(int maxRows) {
        var direction = LeadDirection.KOC_TO_VENDOR;
        return LeadUploadParser.forTypedHeaders(direction.headers(),
                direction.columns().stream().map(LeadDirection.Column::type).toList(),
                maxRows, MAX_BYTES, ZoneOffset.UTC);
    }

    @Test
    void csvAndXlsxAcceptVariablePopulatedRowCountsThroughConfiguredLimit() throws Exception {
        for (int count : List.of(50, 367, 800, 2_000)) {
            assertThat(parser(DEFAULT_ROWS).parseRows(csv(count))).hasSize(count);
            assertThat(parser(DEFAULT_ROWS).parseRows(xlsx(count))).hasSize(count);
        }
    }

    @Test
    void emptyTrailingRowsDoNotCountAndOriginalRowNumbersAreKept() throws Exception {
        var csv = parser(2).parseRows(csv("Name,Phone\nFirst,1111111111\n\nInvalid,\n,,,\n"));
        assertThat(csv).extracting(LeadUploadParser.ParsedRow::rowNumber).containsExactly(2, 4);
        assertThat(csv.get(1).cells()).contains("Invalid", "");

        var xlsx = parser(2).parseRows(xlsxWithRows("""
                <row r="1"><c r="A1" t="inlineStr"><is><t>Name</t></is></c><c r="B1" t="inlineStr"><is><t>Phone</t></is></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>First</t></is></c><c r="B2" t="inlineStr"><is><t>1111111111</t></is></c></row>
                <row r="4"><c r="A4" t="inlineStr"><is><t>Invalid</t></is></c></row>
                <row r="1000000" s="1" customFormat="1"><c r="A1000000" s="1" t="inlineStr"><is><t></t></is></c></row>
                """));
        assertThat(xlsx).extracting(LeadUploadParser.ParsedRow::rowNumber).containsExactly(2, 4);
        assertThat(xlsx.get(1).cells()).contains("Invalid", "");
    }

    @Test
    void actualPopulatedRowsOverConfiguredLimitFailClearlyForCsvAndXlsx() throws Exception {
        for (var file : List.of(csv(4), xlsx(4))) {
            assertThatThrownBy(() -> parser(3).parseRows(file))
                    .hasMessageContaining("maximum supported limit of 3 populated data rows");
        }
    }

    @Test
    void optionalExistingWorkbookRunsThroughPreviewOnlyFlow() throws Exception {
        var pathValue = System.getProperty("crm.preview.workbook");
        Assumptions.assumeTrue(pathValue != null && !pathValue.isBlank(), "Set crm.preview.workbook to run the local workbook preview.");
        var path = Path.of(pathValue);
        long size = Files.size(path);
        Assumptions.assumeTrue(size > 0 && size <= MAX_BYTES, "Workbook is outside the 2 MiB preview limit.");
        var vendor = UUID.fromString("00000000-0000-4000-8000-000000000001");
        var repository = new DirectionalRepository() {
            @Override public java.util.Map<LeadDirection, StageCounts> report(UUID id) { throw new AssertionError("Parser preview must not load dashboard metrics."); }
            @Override public List<Lead> list(UUID id, LeadDirection direction, Search search, int limit, boolean export) { throw new AssertionError("Preview must not read or write lead storage."); }
            @Override public Optional<Lead> get(UUID id, LeadDirection direction, UUID leadId) { throw new AssertionError("Preview must not read or write lead storage."); }
            @Override public Receipt write(UUID id, LeadDirection direction, UUID actor, UUID operation, java.time.Instant submittedAt,
                    UUID editId, long revision, List<Draft> drafts, boolean importing, String vendorName) { throw new AssertionError("Preview must not write leads."); }
            @Override public Receipt operation(UUID id, LeadDirection direction, UUID actor, UUID operation) { throw new AssertionError("Preview must not access operation storage."); }
        };
        var identity = (com.kingofcards.crm.directional.DashboardIdentity)
                (id, admin, token) -> new Vendor(id, "Preview-only vendor", true, List.of("CRM_VENDOR"));
        var service = new DirectionalService(repository, identity,
                new DirectionalProperties(true, false, 10_000, DEFAULT_ROWS, "UTC"), Clock.systemUTC());
        var authentication = new JwtAuthenticationToken(Jwt.withTokenValue("synthetic-preview-only")
                .header("alg", "RS256").subject(vendor.toString()).build(),
                List.of(new SimpleGrantedAuthority("ROLE_CRM_ADMIN")));
        var file = new MockMultipartFile("file", path.getFileName().toString(), "application/octet-stream", Files.readAllBytes(path));
        var preview = service.preview(vendor, LeadDirection.KOC_TO_VENDOR, file,
                new Metadata("photography", null, null), false, authentication);
        long valid = preview.rows().stream().filter(row -> row.errors().isEmpty() && !row.duplicate()).count();
        long invalid = preview.rows().stream().filter(row -> !row.errors().isEmpty()).count();
        long duplicate = preview.rows().stream().filter(PreviewRow::duplicate).count();
        System.out.printf("Read-only workbook preview: rows=%d valid=%d invalid=%d duplicate=%d%n",
                preview.rows().size(), valid, invalid, duplicate);
        var categories = new java.util.TreeMap<String, List<Integer>>();
        for (var row : preview.rows()) for (String error : row.errors()) {
            String category=error.replaceFirst("(?i)^(?:Excel )?row "+row.row()+": ", "");
            categories.computeIfAbsent(category, ignored -> new java.util.ArrayList<>()).add(row.row());
        }
        categories.forEach((category, numbers) -> System.out.printf("Workbook validation: %s; original rows=%s%n", category, numbers));
        if (path.getFileName().toString().equalsIgnoreCase("sen_import_ready.xlsx")) {
            var blankTimestampRows = List.of(2, 3, 4, 356, 357, 358, 359, 360, 361, 362, 363, 364, 365, 366, 367);
            assertThat(preview.rows()).hasSize(366);
            assertThat(valid).isEqualTo(366);
            assertThat(invalid).isZero();
            assertThat(duplicate).isZero();
            for (int rowNumber : blankTimestampRows) {
                var row = preview.rows().stream().filter(candidate -> candidate.row() == rowNumber).findFirst().orElseThrow();
                assertThat(row.fields()).containsEntry("Timestamp", null);
            }
            System.out.printf("Blank optional sheet_timestamp rows verified: %d%n", blankTimestampRows.size());
        }
        var errorRows = new java.util.TreeMap<String, List<Integer>>();
        for(var row:preview.rows())for(String error:row.errors()){
            String category=error.replaceFirst("^(?:Excel )?row "+row.row()+": ","");
            errorRows.computeIfAbsent(category,ignored->new java.util.ArrayList<>()).add(row.row());
        }
        errorRows.forEach((category,rowNumbers)->System.out.printf("Validation error: %s; Excel rows=%s%n",category,rowNumbers));
    }

    private MockMultipartFile csv(int count) {
        var text = new StringBuilder("Name,Phone\n");
        for (int row = 1; row <= count; row++) {
            text.append("Customer ").append(row).append(",").append(1_000_000_000L + row).append('\n');
        }
        return csv(text.toString());
    }

    private MockMultipartFile csv(String text) {
        return new MockMultipartFile("file", "leads.csv", "text/csv", text.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile xlsx(int count) throws Exception {
        var rows = new StringBuilder("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Name</t></is></c><c r=\"B1\" t=\"inlineStr\"><is><t>Phone</t></is></c></row>");
        for (int row = 2; row <= count + 1; row++) {
            rows.append("<row r=\"").append(row).append("\"><c r=\"A").append(row)
                    .append("\" t=\"inlineStr\"><is><t>Customer ").append(row - 1)
                    .append("</t></is></c><c r=\"B").append(row).append("\" t=\"inlineStr\"><is><t>")
                    .append(1_000_000_000L + row).append("</t></is></c></row>");
        }
        return xlsxWithRows(rows.toString());
    }

    private MockMultipartFile xlsxWithRows(String rows) throws Exception {
        var parts = Map.of(
                "xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Leads\" sheetId=\"1\" r:id=\"r1\"/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"r1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
                "xl/worksheets/sheet1.xml", "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" + rows + "</sheetData></worksheet>");
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", "leads.xlsx", "application/octet-stream", output.toByteArray());
    }
}

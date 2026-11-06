package com.kingofcards.crm.directional;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import com.kingofcards.crm.service.LeadUploadParser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
class DirectionalHeaderMappingTest {
    LeadUploadParser parser(){var d=LeadDirection.KOC_TO_VENDOR;return LeadUploadParser.forTypedHeaders(d.headers(),d.columns().stream().map(LeadDirection.Column::type).toList(),200,2097152,ZoneOffset.UTC);}
    List<List<String>> parse(String csv){return parser().parse(new MockMultipartFile("file","sheet.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8)));}
    MockMultipartFile xlsxRows(String rows) throws Exception {
        var parts=Map.of(
          "xl/workbook.xml","<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='Leads' sheetId='1' r:id='r1'/></sheets></workbook>",
          "xl/_rels/workbook.xml.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='r1' Target='worksheets/sheet1.xml'/></Relationships>",
          "xl/worksheets/sheet1.xml","<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData><row r='1'><c r='A1' t='inlineStr'><is><t>Name</t></is></c><c r='B1' t='inlineStr'><is><t>Phone</t></is></c><c r='C1' t='inlineStr'><is><t>Timestamp</t></is></c><c r='D1' t='inlineStr'><is><t>occasion_date</t></is></c></row>"+rows+"</sheetData></worksheet>");
        var output=new java.io.ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(output)){for(var part:parts.entrySet()){zip.putNextEntry(new java.util.zip.ZipEntry(part.getKey()));zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
        return new MockMultipartFile("file","leads.xlsx","application/octet-stream",output.toByteArray());
    }
    @Test void reorderedHeadersAndOptionalColumns(){var rows=parse("PHONE,additional COMMENTS,NAME,Occasion Date,Quoted Amount\n001234567890,Notes,Customer,2026-11-02,123.45\n");assertThat(rows.getFirst()).hasSize(12);assertThat(rows.getFirst().get(2)).isEqualTo("Customer");assertThat(rows.getFirst().get(3)).isEqualTo("001234567890");assertThat(rows.getFirst().get(6)).isEqualTo("2026-11-02");assertThat(rows.getFirst().get(9)).isEqualTo("Notes");assertThat(rows.getFirst().get(10)).isEqualTo("123.45");}
    @Test void unknownColumnsAreNeverDiscarded(){assertThatThrownBy(()->parse("Name,Phone,Unknown\nCustomer,001234567890,Private\n")).hasMessageContaining("Unrecognized header in column 3");}
    @Test void duplicateAliasesAreRejected(){assertThatThrownBy(()->parse("Name,Phone,PHONE\nCustomer,001234567890,001234567890\n")).hasMessageContaining("Duplicate or ambiguous header for Phone");}
    @Test void requiredHeadersAreValidated(){assertThatThrownBy(()->parse("Name,Branch\nCustomer,Branch\n")).hasMessageContaining("Required column is missing: Phone");}
    @Test void dataWithoutHeaderIsNotLost(){assertThatThrownBy(()->parse("Name,Phone,\nCustomer,001234567890,Unknown data\n")).hasMessageContaining("Row 2, column 3");}
    @Test void slNoPunctuationAndCampaignCaseAreMapped(){var d=LeadDirection.VENDOR_TO_KOC;var parser=LeadUploadParser.forTypedHeaders(d.headers(),d.columns().stream().map(LeadDirection.Column::type).toList(),200,2097152,ZoneOffset.UTC);var file=new MockMultipartFile("file","sheet.csv","text/csv","NAME,PHONE,Sl. No.,Campaign Sent,2nd Campaign Sent,3rd Campaign Sent\nCustomer,001234567890,12,Yes,No,Later\n".getBytes(StandardCharsets.UTF_8));var row=parser.parse(file).getFirst();assertThat(row.get(0)).isEqualTo("12");assertThat(row.get(9)).isEqualTo("Yes");assertThat(row.get(11)).isEqualTo("Later");}
    @Test void excelSerialDatesFollowReorderedHeaders() throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(bytes)){
            var parts=Map.of("xl/workbook.xml","<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='Leads' sheetId='1' r:id='r1'/></sheets></workbook>",
              "xl/_rels/workbook.xml.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='r1' Target='worksheets/sheet1.xml'/></Relationships>",
              "xl/worksheets/sheet1.xml","<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData><row r='1'><c r='A1' t='inlineStr'><is><t>date</t></is></c><c r='B1' t='inlineStr'><is><t>name</t></is></c><c r='C1' t='inlineStr'><is><t>phone</t></is></c></row><row r='2'><c r='A2'><v>61</v></c><c r='B2' t='inlineStr'><is><t>Customer</t></is></c><c r='C2' t='inlineStr'><is><t>001234567890</t></is></c></row></sheetData></worksheet>");
            for(var part:parts.entrySet()){zip.putNextEntry(new java.util.zip.ZipEntry(part.getKey()));zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }
        var d=LeadDirection.VENDOR_TO_KOC;var parser=LeadUploadParser.forTypedHeaders(d.headers(),d.columns().stream().map(LeadDirection.Column::type).toList(),200,2097152,ZoneOffset.UTC);
        var row=parser.parse(new MockMultipartFile("file","dates.xlsx","application/octet-stream",bytes.toByteArray())).getFirst();assertThat(row.get(1)).isEqualTo("1900-03-01");assertThat(row.get(5)).isEqualTo("001234567890");
    }
    @Test void numericExcelPhonesExpandExactlyAndTextPhonesKeepLeadingZeros() throws Exception {
        var file=xlsxRows("<row r='2'><c r='A2' t='inlineStr'><is><t>Numeric phone</t></is></c><c r='B2'><v>1.234567890E+9</v></c><c r='C2'><v>45200.5</v></c></row>"
                +"<row r='3'><c r='A3' t='inlineStr'><is><t>Text phone</t></is></c><c r='B3' t='inlineStr'><is><t>001234567890</t></is></c><c r='C3'><v>45201.5</v></c></row>");
        var rows=parser().parseRows(file);assertThat(rows).extracting(LeadUploadParser.ParsedRow::rowNumber).containsExactly(2,3);
        assertThat(rows).allMatch(row->row.errors().isEmpty());assertThat(rows.get(0).cells().get(3)).isEqualTo("1234567890");
        assertThat(rows.get(1).cells().get(3)).isEqualTo("001234567890");
        assertThat(rows.get(0).cells().get(1)).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
    }
    @Test void fractionalNegativeAndOverPrecisionNumericPhonesAreRejectedWithExcelRows() throws Exception {
        var file=xlsxRows("<row r='2'><c r='A2' t='inlineStr'><is><t>Fractional</t></is></c><c r='B2'><v>123456789.5</v></c></row>"
                +"<row r='4'><c r='A4' t='inlineStr'><is><t>Negative</t></is></c><c r='B4'><v>-123456789</v></c></row>"
                +"<row r='7'><c r='A7' t='inlineStr'><is><t>Over precision</t></is></c><c r='B7'><v>1234567890123456</v></c></row>");
        var rows=parser().parseRows(file);assertThat(rows).extracting(LeadUploadParser.ParsedRow::rowNumber).containsExactly(2,4,7);
        assertThat(rows).allMatch(row->row.errors().size()==1&&row.errors().getFirst().contains("Excel row "+row.rowNumber())&&row.errors().getFirst().contains("exact whole number"));
    }
    @Test void rawNumericScientificTrailingZerosAreExactButTextAndUnsafeExponentsRemainRejected() throws Exception {
        var file=xlsxRows("<row r='2'><c r='A2' t='inlineStr'><is><t>Exact numeric</t></is></c><c r='B2'><v>1.23456789E9</v></c></row>"
            +"<row r='3'><c r='A3' t='inlineStr'><is><t>Scientific text</t></is></c><c r='B3' t='inlineStr'><is><t>1.23456789E9</t></is></c></row>"
            +"<row r='4'><c r='A4' t='inlineStr'><is><t>Oversized exponent</t></is></c><c r='B4'><v>1e1000000000</v></c></row>"
            +"<row r='5'><c r='A5' t='inlineStr'><is><t>Fractional</t></is></c><c r='B5'><v>1.2345678905E9</v></c></row>");
        var rows=parser().parseRows(file);
        assertThat(rows.getFirst().errors()).isEmpty();assertThat(rows.getFirst().cells().get(3)).isEqualTo("1234567890");
        assertThat(rows.get(1).errors()).anyMatch(error->error.contains("Row 3")&&error.contains("insufficient verified precision"));
        assertThat(rows.get(2).errors()).anyMatch(error->error.contains("Excel row 4")&&error.contains("exact whole number"));
        assertThat(rows.get(3).errors()).anyMatch(error->error.contains("Excel row 5")&&error.contains("exact whole number"));
    }
    @Test void timestampPreservesDateOnlyPrecisionAndRequiresOffsetForLocalTimes() throws Exception {
        var file=xlsxRows("<row r='2'><c r='A2' t='inlineStr'><is><t>Date only numeric</t></is></c><c r='B2' t='inlineStr'><is><t>001234567890</t></is></c><c r='C2'><v>45200</v></c></row>"
                +"<row r='3'><c r='A3' t='inlineStr'><is><t>Local date-time</t></is></c><c r='B3' t='inlineStr'><is><t>001234567891</t></is></c><c r='C3' t='d'><v>2026-11-04T10:00:00</v></c></row>"
                +"<row r='4'><c r='A4' t='inlineStr'><is><t>ISO offset</t></is></c><c r='B4' t='inlineStr'><is><t>001234567892</t></is></c><c r='C4' t='inlineStr'><is><t>2026-11-04T10:00:00+05:30</t></is></c></row>"
                +"<row r='5'><c r='A5' t='inlineStr'><is><t>Date-only text</t></is></c><c r='B5' t='inlineStr'><is><t>001234567893</t></is></c><c r='C5' t='inlineStr'><is><t>2026-11-04</t></is></c></row>");
        var rows=parser().parseRows(file);assertThat(rows).extracting(LeadUploadParser.ParsedRow::rowNumber).containsExactly(2,3,4,5);
        assertThat(rows.get(0).errors()).isEmpty();assertThat(rows.get(0).sourceDateTime().timeSupplied()).isFalse();assertThat(rows.get(0).sourceDateTime().originalValue()).isEqualTo("45200");assertThat(rows.get(0).cells().get(1)).isEmpty();
        assertThat(rows.get(1).errors().getFirst()).contains("Excel row 3").contains("explicit timezone offset");
        assertThat(rows.get(2).errors()).isEmpty();assertThat(rows.get(2).cells().get(1)).isEqualTo("2026-11-04T04:30:00Z");
        assertThat(rows.get(3).errors()).isEmpty();assertThat(rows.get(3).sourceDateTime().timeSupplied()).isFalse();assertThat(rows.get(3).sourceDateTime().date()).isEqualTo(java.time.LocalDate.of(2026,11,4));
    }
    @Test void dateTextNormalizesOnlyWhenDayAndMonthOrderIsUnambiguous() throws Exception {
        var file=xlsxRows("<row r='2'><c r='A2' t='inlineStr'><is><t>Day first</t></is></c><c r='B2' t='inlineStr'><is><t>001234567890</t></is></c><c r='C2'><v>45200.5</v></c><c r='D2' t='inlineStr'><is><t>18-11-2026</t></is></c></row>"
                +"<row r='3'><c r='A3' t='inlineStr'><is><t>Month first</t></is></c><c r='B3' t='inlineStr'><is><t>001234567891</t></is></c><c r='C3'><v>45201.5</v></c><c r='D3' t='inlineStr'><is><t>11/18/2026</t></is></c></row>"
                +"<row r='4'><c r='A4' t='inlineStr'><is><t>Ambiguous</t></is></c><c r='B4' t='inlineStr'><is><t>001234567892</t></is></c><c r='C4'><v>45202.5</v></c><c r='D4' t='inlineStr'><is><t>11-12-2026</t></is></c></row>");
        var rows=parser().parseRows(file);assertThat(rows.get(0).cells().get(6)).isEqualTo("2026-11-18");
        assertThat(rows.get(1).cells().get(6)).isEqualTo("2026-11-18");
        assertThat(rows.get(2).errors().getFirst()).contains("Excel row 4").contains("ambiguous day/month order");
    }
}

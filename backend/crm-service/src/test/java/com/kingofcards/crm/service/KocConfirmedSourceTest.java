package com.kingofcards.crm.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneOffset;
import java.util.*;
import com.kingofcards.crm.directional.LeadDirection;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

class KocConfirmedSourceTest {
    LeadUploadParser parser(){var d=LeadDirection.KOC_TO_VENDOR;return LeadUploadParser.forTypedHeaders(d.headers(),d.columns().stream().map(LeadDirection.Column::type).toList(),2000,2097152,ZoneOffset.UTC);}
    @Test void firstBlockUsesConfirmedMonthFirstAndIstIncludingAmPm(){
        assertThat(KocConfirmedSource.timestamp("10-7-2026 12:41:17 PM",2)).isEqualTo("2026-10-07T07:11:17Z");
        assertThat(KocConfirmedSource.timestamp("10-8-2026 1:36:36 PM",18)).isEqualTo("2026-10-08T08:06:36Z");
        assertThat(KocConfirmedSource.timestamp("10-9-2026 00:00",28)).isEqualTo("2026-10-08T18:30:00Z");
        assertThat(KocConfirmedSource.timestamp("10-12-2026 20:41",144)).isEqualTo("2026-10-12T15:11:00Z");
    }
    @Test void finalBlockUsesDayFirstNotMonthFirst(){
        assertThat(KocConfirmedSource.timestamp("30-10-2026 20:09",610)).isEqualTo("2026-10-30T14:39:00Z");
        assertThat(KocConfirmedSource.timestamp("01-11-2026 13:13",614)).isEqualTo("2026-11-01T07:43:00Z");
        assertThat(KocConfirmedSource.timestamp("02-11-2026 7:10 PM",616)).isEqualTo("2026-11-02T13:40:00Z");
    }
    @Test void dateOnlyBlockNeverInventsMidnightOrCurrentTime(){
        for(var row:Map.of(145,"13-10-2026",609,"29-10-2026").entrySet()){
            assertThat(KocConfirmedSource.timestamp(row.getValue(),row.getKey())).isNull();var source=KocConfirmedSource.sourceDateTime(row.getValue(),row.getKey());
            assertThat(source.timeSupplied()).isFalse();assertThat(source.originalValue()).isEqualTo(row.getValue());assertThat(source.date().getYear()).isEqualTo(2026);
        }
        assertThatThrownBy(()->KocConfirmedSource.timestamp("13-10-2026 10:00",145)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidDatesAndUnexpectedBoundariesFailClosed(){
        assertThatThrownBy(()->KocConfirmedSource.timestamp("02-29-2026 10:00",2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->KocConfirmedSource.timestamp("31-11-2026 10:00",610)).isInstanceOf(IllegalArgumentException.class);
        for(int row:List.of(1,617))assertThatThrownBy(()->KocConfirmedSource.timestamp("10-7-2026 12:41",row)).hasMessageContaining("outside");
    }
    @Test void separateDateFieldsAreStrictDayFirstAndFourDigitYears(){
        assertThat(KocConfirmedSource.date("11-12-2026")).isEqualTo("2026-12-11");
        assertThat(KocConfirmedSource.date("03-01-2027")).isEqualTo("2027-01-03");
        for(String raw:List.of("22-Nov-26","11-12-26","2026-11-18","31-11-2026"))assertThatThrownBy(()->KocConfirmedSource.date(raw)).hasMessageContaining("DD-MM-YYYY");
    }
    @Test void unrelatedUploadWithSameFilenameRemainsOnStrictDefaultRules(){
        byte[] bytes="Date & time,Name,Phone,Event date\n10-7-2026 12:41:17 PM,Synthetic,0000000000,11-12-2026\n".getBytes(StandardCharsets.UTF_8);
        assertThat(KocConfirmedSource.matches(bytes)).isFalse();
        var rows=parser().parseRows(new MockMultipartFile("file","15th date undelivered final.csv","text/csv",bytes));
        assertThat(rows.getFirst().errors()).isNotEmpty();assertThat(rows.getFirst().cells().get(1)).isEqualTo("10-7-2026 12:41:17 PM");
    }
    @Test void scientificPhonesExpandOnlyWithSufficientExplicitPrecision(){
        String csv="Name,Phone\nExact,1.23456789012E11\nRounded,1.23457E11\nText,+91 0012345678\nZeros,001234567890\n";
        var rows=parser().parseRows(new MockMultipartFile("file","phones.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8)));
        assertThat(rows.get(0).cells().get(3)).isEqualTo("123456789012");assertThat(rows.get(0).errors()).isEmpty();
        assertThat(rows.get(1).cells().get(3)).isEqualTo("1.23457E11");assertThat(rows.get(1).errors().getFirst()).contains("Row 3", "insufficient verified precision");
        assertThat(rows.get(2).cells().get(3)).isEqualTo("+91 0012345678");assertThat(rows.get(3).cells().get(3)).isEqualTo("001234567890");
    }
    @Test void actualConfirmedFileValidatesAllBoundariesWithoutChangingOriginal() throws Exception {
        String source=System.getProperty("crm.confirmed-source.csv");Assumptions.assumeTrue(source!=null&&!source.isBlank(),"Opt in to the local read-only actual-source test");
        Path path=Path.of(source);byte[] before=Files.readAllBytes(path);assertThat(KocConfirmedSource.matches(before)).isTrue();
        var rows=parser().parseRows(new MockMultipartFile("file",path.getFileName().toString(),"text/csv",before));assertThat(rows).hasSize(615);
        assertThat(rows.stream().filter(row->row.rowNumber()>=2&&row.rowNumber()<=144).allMatch(row->row.errors().isEmpty())).as("timed month-first block").isTrue();
        assertThat(rows.stream().filter(row->row.rowNumber()>=145&&row.rowNumber()<=609).allMatch(row->row.errors().stream().allMatch(error->error.contains("Phone"))&&row.sourceDateTime()!=null&&!row.sourceDateTime().timeSupplied()&&row.cells().get(1).isEmpty())).as("date-only block").isTrue();
        assertThat(rows.stream().filter(row->row.rowNumber()>=610&&row.rowNumber()<=616).allMatch(row->row.errors().stream().anyMatch(error->error.contains("four-digit year")))).as("nonconforming event dates").isTrue();
        assertThat(rows.getFirst().cells().get(1)).isEqualTo("2026-10-07T07:11:00Z");
        assertThat(rows.stream().filter(row->row.rowNumber()==145).findFirst().orElseThrow().sourceDateTime().originalValue()).isEqualTo("13-10-2026");
        assertThat(rows.stream().filter(row->row.rowNumber()==610).findFirst().orElseThrow().cells().get(1)).isEqualTo("2026-10-30T14:39:00Z");
        // Any edit, including casing of an otherwise accepted header, loses this source approval.
        byte[] changed=new String(before,StandardCharsets.UTF_8).replace("Date & time","Date & Time").getBytes(StandardCharsets.UTF_8);
        assertThat(KocConfirmedSource.matches(changed)).isFalse();
        assertThat(parser().parseRows(new MockMultipartFile("file",path.getFileName().toString(),"text/csv",changed)).getFirst().errors()).isNotEmpty();
        assertThat(java.security.MessageDigest.isEqual(Files.readAllBytes(path),before)).as("source bytes unchanged").isTrue();
    }
}

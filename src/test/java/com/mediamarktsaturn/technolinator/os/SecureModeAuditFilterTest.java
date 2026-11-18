package com.mediamarktsaturn.technolinator.os;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecureModeAuditFilterTest {

    private static final List<String> AUDIT_TABLE = List.of(
        "+------------------------+------------+--------------------------+----------------------------------------------------+",
        "|                                           SECURE MODE: Environment audit                                            |",
        "|                                                        5 low                                                        |",
        "+------------------------+------------+--------------------------+----------------------------------------------------+",
        "| Category               | Severity   | Variable(s)              | Details                                            |",
        "+------------------------+------------+--------------------------+----------------------------------------------------+",
        "| Credential Exposure    | LOW        | GITHUB_TOKEN             | Credential-like environment variables are set. Bui |",
        "|                        |            | GITHUB_USER              | ld tools or install scripts invoked during SBOM ge |",
        "|                        |            | QUARKUS_GITHUB_APP_PRIVA | neration may read inherited environment variables. |",
        "|                        |            | TE_KEY                   | Mitigation: Unset unneeded secrets when scanning u |",
        "+------------------------+------------+--------------------------+----------------------------------------------------+"
    );

    private static final List<String> OTHER_TABLE = List.of(
        "+-------+-------+",
        "| Name  | Value |",
        "+-------+-------+",
        "| foo   | bar   |",
        "+-------+-------+"
    );

    @Test
    void removesAuditTable() {
        var input = new ArrayList<String>();
        input.add("before");
        input.addAll(AUDIT_TABLE);
        input.add("after");

        assertThat(filter(input)).containsExactly("before", "after");
    }

    @Test
    void removesAuditTableAtEndOfOutput() {
        var input = new ArrayList<String>();
        input.add("before");
        input.addAll(AUDIT_TABLE);

        assertThat(filter(input)).containsExactly("before");
    }

    @Test
    void removesAuditTableWithAnsiColors() {
        var input = new ArrayList<String>();
        input.add("before");
        AUDIT_TABLE.forEach(line -> input.add("\u001B[33m" + line + "\u001B[39m"));
        input.add("after");

        assertThat(filter(input)).containsExactly("before", "after");
    }

    @Test
    void removesAuditTableWithChangedTitleAndBoxDrawing() {
        var input = List.of(
            "before",
            "┏━━━━━━━━━━┳━━━━━━━━━━┓",
            "┃   Secure Mode environment audit   ┃",
            "┣━━━━━━━━━━╋━━━━━━━━━━┫",
            "┃ Category ┃ Severity ┃",
            "╠══════════╬══════════╣",
            "║ Foo      ║ LOW      ║",
            "┗━━━━━━━━━━┻━━━━━━━━━━┛",
            "after"
        );

        assertThat(filter(input)).containsExactly("before", "after");
    }

    @Test
    void keepsOtherTables() {
        var input = new ArrayList<String>();
        input.add("before");
        input.addAll(OTHER_TABLE);
        input.add("after");

        assertThat(filter(input)).containsExactlyElementsOf(input);
    }

    @Test
    void keepsTableBeforeAuditTableAndPendingBorderAtEnd() {
        var input = new ArrayList<String>();
        input.addAll(OTHER_TABLE);
        input.addAll(AUDIT_TABLE);
        input.add("middle");
        input.add(OTHER_TABLE.getFirst());

        var expected = new ArrayList<>(OTHER_TABLE);
        expected.add("middle");
        expected.add(OTHER_TABLE.getFirst());
        assertThat(filter(input)).containsExactlyElementsOf(expected);
    }

    @Test
    void removesFollowingReportTablesButKeepsAbortMessage() {
        var findings = List.of(
            "+-------+----------+",
            "|   Findings (1)     |",
            "+-------+----------+",
            "| 1     | HIGH     |",
            "+-------+----------+"
        );
        var abort = List.of(
            "+----------------+",
            "| \uD83D\uDEAB SECURE MODE: High-risk configuration detected. Aborting SBOM generation. |",
            "+----------------+"
        );
        var input = new ArrayList<String>();
        input.add("before");
        input.addAll(AUDIT_TABLE);
        input.addAll(findings);
        input.addAll(abort);
        input.add("after");

        var expected = new ArrayList<String>();
        expected.add("before");
        expected.addAll(abort);
        expected.add("after");
        assertThat(filter(input)).containsExactlyElementsOf(expected);
    }

    @Test
    void keepsOtherTableDirectlyFollowingAuditTable() {
        var input = new ArrayList<String>();
        input.addAll(AUDIT_TABLE);
        input.addAll(OTHER_TABLE);
        input.add("after");

        var expected = new ArrayList<>(OTHER_TABLE);
        expected.add("after");
        assertThat(filter(input)).containsExactlyElementsOf(expected);
    }

    @Test
    void removesAuditTableWithPrivateModeAnsiSequences() {
        var input = new ArrayList<String>();
        input.add("before");
        AUDIT_TABLE.forEach(line -> input.add("\u001B[?25l\u001B[33m" + line + "\u001B[39m"));
        input.add("after");

        assertThat(filter(input)).containsExactly("before", "after");
    }

    @Test
    void removesDebugActivitySummaryTable() {
        var summary = List.of(
            "+----------------+----------------+--------------+",
            "|                cdxgen debug activity summary                |",
            "|              47 completed   0 blocked   0 failed             |",
            "+----------------+----------------+--------------+",
            "| Identifier     | Type           | Activity     |",
            "+----------------+----------------+--------------+",
            "| ACT-0001       |                | env          |",
            "|                |                | Read environment variable CD |",
            "+----------------+----------------+--------------+",
            "| ACT-0002       |                | execute      |",
            "+----------------+----------------+--------------+"
        );
        var input = new ArrayList<String>();
        input.add("before");
        input.addAll(summary);
        input.add("after");

        assertThat(filter(input)).containsExactly("before", "after");
    }

    @Test
    void keepsPlainOutput() {
        var input = List.of("a", "| not a table", "+ plus", "", "SECURE MODE: mentioned in text", "+---+", "no title row", "| SECURE MODE |");

        assertThat(filter(input)).containsExactlyElementsOf(input);
    }

    private static List<String> filter(List<String> lines) {
        var filter = new SecureModeAuditFilter();
        var result = new ArrayList<String>();
        lines.forEach(line -> filter.accept(line, result::add));
        filter.flush(result::add);
        return result;
    }
}

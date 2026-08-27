package com.oliverke.payments.recon;

import com.oliverke.payments.order.OrderStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a channel statement file into {@link ReconRecord}s.
 *
 * <p>The parser is hand-written rather than pulled from a library. The format is
 * fixed, the file is machine-generated, and the whole surface is seven columns -
 * a dependency would buy escaping rules this reader can simply implement. It
 * does implement them properly, including quoted fields and doubled quotes,
 * because "we control the format" is exactly the assumption that stops being
 * true the day a merchant name contains a comma.
 *
 * <p>Everything malformed throws. A statement is a financial document: a line
 * that cannot be understood must stop the run, not be skipped with a warning
 * that nobody reads. Silently dropping one line would turn a parse bug into a
 * missing payment that reconciliation then reports as a genuine discrepancy.
 */
@Component
public class StatementReader {

    private static final String EXPECTED_HEADER =
            "channel_ref,order_no,merchant_id,amount,currency,status,settled_at";

    private static final int CHANNEL_REF = 0;
    private static final int ORDER_NO = 1;
    private static final int MERCHANT_ID = 2;
    private static final int AMOUNT = 3;
    private static final int STATUS = 5;
    private static final int COLUMNS = 7;

    public List<ReconRecord> read(Path statementFile) {
        List<String> lines = readAllLines(statementFile);

        if (lines.isEmpty()) {
            throw new IllegalArgumentException("statement file is empty: " + statementFile);
        }

        String header = lines.get(0);
        if (!EXPECTED_HEADER.equals(header.strip())) {
            // Pinning the header means a channel silently reordering its columns
            // fails here, rather than reconciling amounts against currencies.
            throw new IllegalArgumentException("""
                    unexpected statement header in %s
                      expected: %s
                      actual  : %s""".formatted(statementFile, EXPECTED_HEADER, header));
        }

        List<ReconRecord> records = new ArrayList<>(lines.size() - 1);

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            records.add(parse(line, i + 1, statementFile));
        }

        return records;
    }

    private static ReconRecord parse(String line, int lineNumber, Path file) {
        String[] fields = splitCsv(line);

        if (fields.length != COLUMNS) {
            throw new IllegalArgumentException(
                    "%s line %d: expected %d columns, found %d"
                            .formatted(file, lineNumber, COLUMNS, fields.length));
        }

        try {
            return new ReconRecord(
                    required(fields[CHANNEL_REF], "channel_ref", lineNumber, file),
                    fields[ORDER_NO],
                    fields[MERCHANT_ID],
                    new BigDecimal(required(fields[AMOUNT], "amount", lineNumber, file)),
                    OrderStatus.valueOf(required(fields[STATUS], "status", lineNumber, file)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "%s line %d: amount '%s' is not a number".formatted(file, lineNumber, fields[AMOUNT]), e);
        } catch (IllegalArgumentException e) {
            // valueOf on an unknown status lands here. A channel reporting a state
            // we have never heard of is a change we need to notice, not absorb.
            throw new IllegalArgumentException(
                    "%s line %d: %s".formatted(file, lineNumber, e.getMessage()), e);
        }
    }

    private static String required(String value, String column, int lineNumber, Path file) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "%s line %d: %s must not be empty".formatted(file, lineNumber, column));
        }
        return value;
    }

    /**
     * Splits one CSV line: commas separate, double quotes group, and a doubled
     * quote inside a quoted field is a literal quote. Enough of RFC 4180 to be
     * correct for anything this format can contain.
     */
    static String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    boolean escapedQuote = i + 1 < line.length() && line.charAt(i + 1) == '"';
                    if (escapedQuote) {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }

        if (inQuotes) {
            throw new IllegalArgumentException("unterminated quote in: " + line);
        }

        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }

    private static List<String> readAllLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read statement " + file, e);
        }
    }
}

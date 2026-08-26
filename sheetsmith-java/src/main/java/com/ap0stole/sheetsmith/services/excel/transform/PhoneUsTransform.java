package com.ap0stole.sheetsmith.services.excel.transform;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Every US phone spelling collapsed to one. The digits are the only part that survives. */
@Component
public class PhoneUsTransform implements ColumnTransform {

    private static final Pattern NON_DIGIT = Pattern.compile("\\D");

    @Override
    public String getType() {
        return "PHONE_US";
    }

    @Override
    public Optional<String> apply(String value, Map<String, Object> options) {
        if (value == null) return Optional.empty();

        String digits = NON_DIGIT.matcher(value).replaceAll("");
        // A leading country code is the one digit that is not part of the number itself.
        if (digits.length() == 11 && digits.charAt(0) == '1') {
            digits = digits.substring(1);
        }
        if (digits.length() != 10) return Optional.empty();

        return Optional.of("+1 (" + digits.substring(0, 3) + ") "
                + digits.substring(3, 6) + "-" + digits.substring(6));
    }

    @Override
    public String promptSpec() {
        return """
                PHONE_US — no extra keys. Any US spelling ("555.123.4567", "1-555-123-4567",
                     "(555) 123-4567", or a bare 5551234567 stored as a number) becomes
                     "+1 (555) 123-4567". Anything that does not hold exactly 10 digits is left alone.""";
    }

    @Override
    public String describe(Map<String, Object> options) {
        return "as +1 (XXX) XXX-XXXX phone numbers";
    }
}

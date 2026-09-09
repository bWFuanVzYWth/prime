// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Compact, single-line failure details for logs and bounded user diagnostics. */
final class RuntimeFailureSummary {
    private static final int MAX_DETAIL_CODE_POINTS = 160;
    private static final int MAX_CONTEXT_CODE_POINTS = 96;
    private static final int MAX_VERSION_CODE_POINTS = 32;

    private RuntimeFailureSummary() {
    }

    static String title(String version, RuntimeState state) {
        String resolvedVersion = clean(version);
        if (resolvedVersion.isEmpty()) {
            resolvedVersion = "unknown";
        }
        return "Prime " + state.name() + " | "
                + abbreviate(resolvedVersion, MAX_VERSION_CODE_POINTS);
    }

    static String describe(Throwable failure) {
        return describe(failure, null);
    }

    static String describe(Throwable failure, String operation) {
        Objects.requireNonNull(failure, "failure");
        Throwable root = rootCause(failure);
        String detail = throwableDetail(root);
        String operationContext = abbreviate(clean(operation), MAX_CONTEXT_CODE_POINTS);
        String wrapperContext = root == failure
                ? ""
                : abbreviate(clean(failure.getMessage()), MAX_CONTEXT_CODE_POINTS);
        if (wrapperContext.equals(clean(root.toString()))) {
            wrapperContext = "";
        }

        StringBuilder summary = new StringBuilder(detail);
        appendDistinct(summary, operationContext, detail);
        appendDistinct(summary, wrapperContext, detail);
        return summary.toString();
    }

    static String clean(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder clean = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = clean.length() > 0;
                continue;
            }
            if (Character.isISOControl(codePoint) || type == Character.FORMAT) {
                continue;
            }
            if (pendingSpace) {
                clean.append(' ');
                pendingSpace = false;
            }
            clean.appendCodePoint(codePoint);
        }
        return clean.toString();
    }

    static String abbreviate(String value, int maximumCodePoints) {
        if (value.codePointCount(0, value.length()) <= maximumCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maximumCodePoints - 1);
        return value.substring(0, end).stripTrailing() + '…';
    }

    private static Throwable rootCause(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable root = failure;
        visited.add(root);
        while (root.getCause() != null && visited.add(root.getCause())) {
            root = root.getCause();
        }
        return root;
    }

    private static String throwableDetail(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        if (type.isEmpty()) {
            type = failure.getClass().getName();
        }
        String message = clean(failure.getMessage());
        return abbreviate(message.isEmpty() ? type : type + ": " + message,
                MAX_DETAIL_CODE_POINTS);
    }

    private static void appendDistinct(
            StringBuilder summary, String context, String detail) {
        if (!context.isEmpty() && !context.equals(detail) && summary.indexOf(context) < 0) {
            summary.append(" | ").append(context);
        }
    }
}

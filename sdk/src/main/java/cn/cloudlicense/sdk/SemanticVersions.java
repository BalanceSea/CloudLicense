package cn.cloudlicense.sdk;

final class SemanticVersions {
    private SemanticVersions() {
    }

    static int compare(String left, String right) {
        Version leftVersion = parse(left);
        Version rightVersion = parse(right);
        String[] leftParts = leftVersion.core().split("\\.");
        String[] rightParts = rightVersion.core().split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            String leftPart = i < leftParts.length ? leftParts[i] : "0";
            String rightPart = i < rightParts.length ? rightParts[i] : "0";
            int comparison = comparePart(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        if (leftVersion.preRelease() == null && rightVersion.preRelease() == null) return 0;
        if (leftVersion.preRelease() == null) return 1;
        if (rightVersion.preRelease() == null) return -1;
        return comparePreRelease(leftVersion.preRelease(), rightVersion.preRelease());
    }

    private static int comparePart(String left, String right) {
        if (isNumeric(left) && isNumeric(right)) {
            return compareNumericIdentifier(left, right);
        }
        return left.compareToIgnoreCase(right);
    }

    private static int comparePreRelease(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int i = 0; i < Math.max(leftParts.length, rightParts.length); i++) {
            if (i >= leftParts.length) return -1;
            if (i >= rightParts.length) return 1;
            boolean leftNumeric = isNumeric(leftParts[i]);
            boolean rightNumeric = isNumeric(rightParts[i]);
            if (leftNumeric && rightNumeric) {
                int result = compareNumericIdentifier(leftParts[i], rightParts[i]);
                if (result != 0) return result;
            } else if (leftNumeric != rightNumeric) {
                return leftNumeric ? -1 : 1;
            } else {
                int result = leftParts[i].compareToIgnoreCase(rightParts[i]);
                if (result != 0) return result;
            }
        }
        return 0;
    }

    private static boolean isNumeric(String value) {
        return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }

    private static int compareNumericIdentifier(String left, String right) {
        String normalizedLeft = stripLeadingZeros(left);
        String normalizedRight = stripLeadingZeros(right);
        int lengthComparison = Integer.compare(normalizedLeft.length(), normalizedRight.length());
        return lengthComparison != 0 ? lengthComparison : normalizedLeft.compareTo(normalizedRight);
    }

    private static String stripLeadingZeros(String value) {
        int firstNonZero = 0;
        while (firstNonZero < value.length() - 1 && value.charAt(firstNonZero) == '0') {
            firstNonZero++;
        }
        return value.substring(firstNonZero);
    }

    private static Version parse(String version) {
        String normalized = version == null ? "0" : version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        int metadata = normalized.indexOf('+');
        normalized = metadata >= 0 ? normalized.substring(0, metadata) : normalized;
        int separator = normalized.indexOf('-');
        return separator < 0 ? new Version(normalized, null)
                : new Version(normalized.substring(0, separator), normalized.substring(separator + 1));
    }

    private record Version(String core, String preRelease) {
    }
}

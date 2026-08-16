/* 公共数据对象共用的轻量合同校验，避免各对象产生不一致的边界。 */
package io.github.fongmi.adaudio.probe;

final class ApiValidation {
    private ApiValidation() {
    }

    static String requireId(String value, String label, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength) {
            throw new IllegalArgumentException(label + "长度必须在 1 到 " + maxLength + " 之间");
        }
        boolean hasVisibleContent = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException(label + "不能包含控制字符");
            }
            if (!Character.isWhitespace(character)) hasVisibleContent = true;
        }
        if (!hasVisibleContent) throw new IllegalArgumentException(label + "不能为空");
        return value;
    }

    static String requireMessage(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("错误信息不能为空");
        }
        return value;
    }

    static void requireNonNegative(long value, String label) {
        if (value < 0L) throw new IllegalArgumentException(label + "不能为负数");
    }
}

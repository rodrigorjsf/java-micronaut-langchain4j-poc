package io.github.rodrigorjsf.agenticchat.testsupport;

/** HTML escaping shared by the self-contained test reports, so the two pages cannot drift. */
public final class Html {

    private Html() {
    }

    /** Escapes text for an element body or a quoted attribute value; {@code null} renders as nothing. */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        var escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}

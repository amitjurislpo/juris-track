package com.jurislpo.juristrack.service;

/** Email bodies: a plain-text part and a short HTML part. */
public final class MailTemplates {
    private MailTemplates() {}

    /** One email's subject, text and HTML. */
    public record MailContent(String subject, String text, String html) {}

    static String escapeHtml(String text) {
        StringBuilder escaped = new StringBuilder();
        for (char character : text.toCharArray()) {
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    /** The emailed sign-in code. */
    public static MailContent signInCode(String name, String code, int validMinutes) {
        String safeName = escapeHtml(name);
        return new MailContent(
                "Your JurisTrack sign-in code: " + code,
                "Hi " + name + ",\n\nYour JurisTrack sign-in code is " + code + ". It works once and expires in " + validMinutes + " minutes."
                        + "\n\nIf you didn't try to sign in, you can ignore this email.",
                "<!doctype html><html><body style=\"font-family:Arial,sans-serif;color:#121216\">"
                        + "<p>Hi " + safeName + ",</p>"
                        + "<p>Use this code to sign in to JurisTrack. It works once and expires in " + validMinutes + " minutes.</p>"
                        + "<p style=\"font-size:28px;letter-spacing:6px;font-weight:600\">" + escapeHtml(code) + "</p>"
                        + "<p style=\"color:#5D5F6D;font-size:13px\">If you didn't try to sign in, ignore this email.</p>"
                        + "</body></html>");
    }
}

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/**
 * Seal CI helper: prints a per-test skip report and per-module totals from the
 * surefire XML reports, so the test summary is the last output of the build log.
 * Usage: java .ci/SkipReport.java module1 module2 ...
 */
public class SkipReport {
    static final Pattern DISABLED = Pattern.compile("@Disabled\\s*(\\(\\s*(?:value\\s*=\\s*)?\"(.*)\"\\s*\\))?");

    public static void main(String[] args) throws Exception {
        long tTests = 0, tFail = 0, tErr = 0, tSkip = 0;
        List<String> skipLines = new ArrayList<>();
        List<String> moduleLines = new ArrayList<>();
        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        for (String module : args) {
            Path dir = Paths.get(module, "target", "surefire-reports");
            long mTests = 0, mFail = 0, mErr = 0, mSkip = 0;
            if (Files.isDirectory(dir)) {
                List<Path> files;
                try (Stream<Path> s = Files.list(dir)) {
                    files = s.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml"))
                            .sorted().collect(Collectors.toList());
                }
                for (Path f : files) {
                    Element suite = db.parse(f.toFile()).getDocumentElement();
                    mTests += num(suite, "tests");
                    mFail += num(suite, "failures");
                    mErr += num(suite, "errors");
                    mSkip += num(suite, "skipped");
                    NodeList cases = suite.getElementsByTagName("testcase");
                    for (int i = 0; i < cases.getLength(); i++) {
                        Element tc = (Element) cases.item(i);
                        NodeList sk = tc.getElementsByTagName("skipped");
                        if (sk.getLength() == 0) continue;
                        String cls = tc.getAttribute("classname");
                        String method = tc.getAttribute("name").replaceAll("\\(.*$", "").trim();
                        if (method.isEmpty()) method = "*";
                        String msg = ((Element) sk.item(0)).getAttribute("message");
                        String reason = reason(module, cls, method, msg);
                        skipLines.add("SKIPPED " + module + " " + cls + "#" + method + ": " + reason);
                    }
                }
            }
            moduleLines.add(String.format("MODULE %s: tests=%d passed=%d failures=%d errors=%d skipped=%d",
                    module, mTests, mTests - mFail - mErr - mSkip, mFail, mErr, mSkip));
            tTests += mTests; tFail += mFail; tErr += mErr; tSkip += mSkip;
        }
        System.out.println("==================== SEAL TEST REPORT (surefire XML) ====================");
        skipLines.forEach(System.out::println);
        moduleLines.forEach(System.out::println);
        System.out.println(String.format("TOTAL: tests=%d passed=%d failures=%d errors=%d skipped=%d",
                tTests, tTests - tFail - tErr - tSkip, tFail, tErr, tSkip));
    }

    static long num(Element e, String attr) {
        String v = e.getAttribute(attr);
        if (v == null || v.isEmpty()) return 0;
        return (long) Double.parseDouble(v);
    }

    static String reason(String module, String cls, String method, String msg) {
        String src = null;
        try {
            src = sourceReason(module, cls, method);
        } catch (Exception ignored) {
        }
        boolean genericMsg = msg == null || msg.isBlank() || msg.trim().endsWith("is @Disabled");
        if (!genericMsg) {
            return clip(msg.replaceAll("\\s+", " ").trim());
        }
        if (src != null) return src;
        return msg == null || msg.isBlank() ? "skipped (no message from runner)" : clip(msg.trim());
    }

    static String sourceReason(String module, String cls, String method) throws Exception {
        String outer = cls.replaceAll("\\$.*$", "");
        Path p = Paths.get(module, "src", "test", "java", outer.replace('.', '/') + ".java");
        if (!Files.exists(p)) return null;
        List<String> lines = Files.readAllLines(p);
        Pattern decl = Pattern.compile("\\b(void|[A-Za-z_][\\w<>\\[\\]]*)\\s+" + Pattern.quote(method) + "\\s*\\(");
        for (int i = 0; i < lines.size(); i++) {
            if (!decl.matcher(lines.get(i)).find()) continue;
            String annotation = null;
            List<String> comments = new ArrayList<>();
            for (int j = i - 1; j >= 0 && j >= i - 25; j--) {
                String t = lines.get(j).trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("@")) {
                    Matcher m = DISABLED.matcher(t);
                    if (m.find()) annotation = m.group(2) != null ? m.group(2) : "";
                    continue;
                }
                if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.endsWith("*/")) {
                    String c = t.replaceAll("^(//+|/\\*+|\\*+/?)", "").replaceAll("\\*+/$", "").trim();
                    if (!c.isEmpty()) comments.add(0, c);
                    continue;
                }
                break; // previous member reached
            }
            if (annotation != null) {
                String why = !annotation.isEmpty() ? annotation : String.join(" ", comments);
                if (why.isBlank()) why = "bare @Disabled annotation, upstream gives no reason (also never runs in upstream CI)";
                return clip("@Disabled upstream - " + why);
            }
            for (int j = i + 1; j < lines.size() && j <= i + 15; j++) {
                String t = lines.get(j).trim();
                if (t.matches(".*\\bassume\\w*\\(.*")) {
                    return clip("JUnit assumption not met on this JVM: " + t);
                }
            }
            break;
        }
        // class-level @Disabled
        for (String l : lines) {
            String t = l.trim();
            Matcher m = DISABLED.matcher(t);
            if (t.startsWith("@Disabled") && m.find()) {
                return clip("whole class @Disabled upstream" + (m.group(2) != null ? " - " + m.group(2) : " (bare annotation, upstream gives no reason; also never runs in upstream CI)"));
            }
            if (t.matches(".*\\bclass\\s+\\w+.*")) break;
        }
        return null;
    }

    static String clip(String s) {
        return s.length() > 220 ? s.substring(0, 217) + "..." : s;
    }
}

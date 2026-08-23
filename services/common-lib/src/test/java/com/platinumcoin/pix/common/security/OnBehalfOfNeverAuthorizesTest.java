package com.platinumcoin.pix.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The structural half of "the on-behalf-of header is evidence, never authority" (ADR-0017 decision 6):
 * across every service's <b>main</b> source, the header is written and logged, and never <b>branched
 * on</b>.
 *
 * <p>{@link OnBehalfOfHeaderTest} proves the header changes no decision <i>today</i>, by driving the
 * filter. That is a test of behaviour, and behaviour tests only cover the paths someone thought to
 * write. This one covers the paths nobody has written yet: it walks every service's {@code src/main},
 * strips the comments, and fails if the header appears in an {@code if}/{@code while}/{@code switch},
 * a ternary, or a comparison. A value that is never compared cannot decide anything — so the property
 * holds for code that does not exist yet, which is the only way a rule like this survives a codebase
 * that keeps growing.
 *
 * <p><b>Reading it is fine; deciding with it is not.</b> That distinction is why this is not a blunt
 * "the header appears nowhere" check: the filter deliberately reads it into a log line, which is the
 * entire purpose of carrying it. Forbidding the read would forbid the feature. Forbidding the branch
 * forbids the misuse.
 *
 * <p><b>Why a source scan and not ArchUnit.</b> ArchUnit reasons about bytecode on <i>this module's</i>
 * classpath, and common-lib cannot see the services that depend on it — the dependency points the other
 * way. Repeating the rule in all eight services would be eight copies able to rot independently.
 * Reading the sources from the repo is the cheap, honest way to ask a whole-repository question from
 * one place; the cost is that this test knows where the repo is, made explicit below rather than clever.
 *
 * <p>If this fails, the fix is <b>not</b> an exemption. Whatever the new code needs the user for, it
 * needs it from the service token's claims or from the request body — not from an unsigned header any
 * caller can set to any value.
 */
class OnBehalfOfNeverAuthorizesTest {

    /** From {@code services/common-lib} up to {@code services/} — where every module lives. */
    private static final Path SERVICES_ROOT = Path.of("..").toAbsolutePath().normalize();

    /** Either spelling of the header: the constant, or the literal name someone typed by hand. */
    private static final Pattern MENTION =
            Pattern.compile("OnBehalfOf\\.HEADER|\"X-PlatinumCoin-On-Behalf-Of\"");

    /** The shapes in which a value participates in a decision. */
    private static final Pattern DECIDES = Pattern.compile(
            "\\b(if|while|switch)\\s*\\(|\\?|\\.equals\\(|\\.equalsIgnoreCase\\(|\\.contains\\("
                    + "|==|!=|\\bassert\\b");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    @Test
    void noServiceBranchesOnTheOnBehalfOfHeader() {
        List<String> offenders = new ArrayList<>();

        forEachMainSource((path, code) -> {
            for (String line : code.split("\n")) {
                if (MENTION.matcher(line).find() && DECIDES.matcher(line).find()) {
                    offenders.add(SERVICES_ROOT.relativize(path) + ": " + line.strip());
                }
            }
        });

        assertThat(offenders)
                .as("X-PlatinumCoin-On-Behalf-Of is evidence, never authority (ADR-0017): it may be "
                        + "written and logged, never compared or branched on. Take the caller's "
                        + "identity from the service token's claims instead of an unsigned header.")
                .isEmpty();
    }

    @Test
    void theScanReallySeesTheHeaderInTheSourcesItWalks() {
        // Without this, a scan that quietly found nothing to read would pass forever while proving
        // nothing — the classic way a whole-repository assertion rots into decoration. The header IS
        // used (the issuer writes it, the filter logs it), so a zero here means the walk is broken.
        List<String> mentions = new ArrayList<>();
        forEachMainSource((path, code) -> {
            if (MENTION.matcher(code).find()) {
                mentions.add(SERVICES_ROOT.relativize(path).toString());
            }
        });

        assertThat(mentions)
                .as("files mentioning the header under %s", SERVICES_ROOT)
                .isNotEmpty();
    }

    @Test
    void theDetectorWouldCatchABranchIfSomeoneWroteOne() {
        // A negative control for the regex itself: the rule above is only as good as its ability to
        // recognise the misuse, and a pattern that matches nothing passes every scan.
        String misuse = "if (request.getHeader(OnBehalfOf.HEADER).equals(ownerId)) { allow(); }";

        assertThat(MENTION.matcher(misuse).find()).isTrue();
        assertThat(DECIDES.matcher(misuse).find()).isTrue();

        // ...and would not flag the two legitimate uses.
        String write = "headers.set(OnBehalfOf.HEADER, userId);";
        String logIt = "log.debug(\"...onBehalfOf={}\", request.getHeader(OnBehalfOf.HEADER));";
        assertThat(DECIDES.matcher(write).find()).isFalse();
        assertThat(DECIDES.matcher(logIt).find()).isFalse();
    }

    /** Walk every {@code src/main/java} source in the repo, comments stripped. */
    private void forEachMainSource(SourceVisitor visitor) {
        try (Stream<Path> paths = Files.walk(SERVICES_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .forEach(p -> visitor.visit(p, stripComments(read(p))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Javadoc explains the rule at length; the scan must not read the explanation as a violation. */
    private static String stripComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface SourceVisitor {
        void visit(Path path, String strippedSource);
    }
}

package com.mediamarktsaturn.technolinator.report;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes secrets from texts which are about to be published in a GitHub repository.
 * <p>
 * Logs of build tools are written by code of the analysed repository, so they are untrusted and might print anything
 * the process has access to. Therefore the exact values of all secrets known to the runtime are replaced first,
 * then well-known secret patterns are removed as a second line of defense.
 */
@ApplicationScoped
public class SecretRedactor {

    public static final String MASK = "[REDACTED]";

    private static final int MIN_SECRET_LENGTH = 6;
    private static final int MIN_SECRET_LINE_LENGTH = 12;

    private static final Pattern SENSITIVE_ENV_NAME = Pattern.compile("(?i).*(TOKEN|SECRET|PASSWORD|PASSWD|APIKEY|API_KEY|PRIVATE_KEY|CREDENTIAL).*");

    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)(authorization\\s*[:=]\\s*)(?:(?:bearer|basic|token)\\s+)?\\S+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/\\s:@]+:[^/\\s@]+@");
    private static final Pattern URL_LONG_USER = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/\\s:@]{20,}@");
    private static final Pattern NPM_AUTH = Pattern.compile("(?i)(_auth(?:token)?|_password)(\\s*=\\s*)\\S+");
    private static final Pattern KEY_VALUE = Pattern.compile("(?i)\\b([\\w.-]*(?:password|passwd|secret|token|api[_-]?key|apikey|credentials?))(\\s*[=:]\\s*)(?!\\[REDACTED])[^\\s,;&\"']+");
    private static final Pattern GITHUB_TOKEN = Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,}\\b");
    private static final Pattern PRIVATE_KEY_BEGIN = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern PRIVATE_KEY_END = Pattern.compile("-----END [A-Z ]*PRIVATE KEY-----");

    /**
     * Secret values, longest first, so that a secret containing another one is replaced completely.
     */
    private final List<String> secrets;

    public SecretRedactor() {
        this(collectRuntimeSecrets());
    }

    public SecretRedactor(Collection<String> secrets) {
        var values = new ArrayList<String>();
        for (var secret : secrets) {
            if (secret == null) {
                continue;
            }
            var trimmed = secret.trim();
            if (trimmed.length() >= MIN_SECRET_LENGTH) {
                values.add(trimmed);
            }
            // multi-line secrets like private keys show up line by line in logs
            if (trimmed.contains("\n")) {
                Arrays.stream(trimmed.split("\\R")).map(String::trim)
                    .filter(line -> line.length() >= MIN_SECRET_LINE_LENGTH)
                    .forEach(values::add);
            }
        }
        this.secrets = values.stream().distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        var result = text;
        for (var secret : secrets) {
            result = result.replace(secret, MASK);
        }
        result = replaceKeepingPrefix(AUTHORIZATION, result, 1, "");
        result = replaceKeepingPrefix(URL_CREDENTIALS, result, 1, "@");
        result = replaceKeepingPrefix(URL_LONG_USER, result, 1, "@");
        result = replaceKeepingPrefix(NPM_AUTH, result, 1, 2);
        result = replaceKeepingPrefix(KEY_VALUE, result, 1, 2);
        result = GITHUB_TOKEN.matcher(result).replaceAll(Matcher.quoteReplacement(MASK));
        result = JWT.matcher(result).replaceAll(Matcher.quoteReplacement(MASK));
        return result;
    }

    /**
     * Redacts line by line, and removes whole private key blocks.
     */
    public List<String> redactLines(List<String> lines) {
        var result = new ArrayList<String>(lines.size());
        var insideKey = false;
        for (var line : lines) {
            if (insideKey) {
                if (PRIVATE_KEY_END.matcher(line).find()) {
                    insideKey = false;
                }
                continue;
            }
            if (PRIVATE_KEY_BEGIN.matcher(line).find()) {
                result.add(MASK + " (private key)");
                insideKey = !PRIVATE_KEY_END.matcher(line).find();
                continue;
            }
            result.add(redact(line));
        }
        return result;
    }

    private static String replaceKeepingPrefix(Pattern pattern, String text, int prefixGroup, String suffix) {
        return pattern.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(prefixGroup) + MASK + suffix));
    }

    private static String replaceKeepingPrefix(Pattern pattern, String text, int prefixGroup, int separatorGroup) {
        return pattern.matcher(text).replaceAll(m ->
            Matcher.quoteReplacement(m.group(prefixGroup) + m.group(separatorGroup) + MASK));
    }

    static List<String> collectRuntimeSecrets() {
        var values = new ArrayList<String>();
        var config = ConfigProvider.getConfig();
        for (var key : List.of("github.token", "dtrack.apikey", "quarkus.github-app.private-key", "quarkus.github-app.webhook-secret")) {
            try {
                config.getOptionalValue(key, String.class).ifPresent(values::add);
            } catch (RuntimeException e) {
                Log.debugf("Config %s not available as secret", key);
            }
        }

        var env = System.getenv();
        env.forEach((name, value) -> {
            if (SENSITIVE_ENV_NAME.matcher(name).matches()) {
                values.add(value);
            }
        });
        // env explicitly named as sensitive, or being exposable to repository configuration, may carry secrets as well
        var names = new ArrayList<String>();
        names.addAll(split(env.get("SENSITIVE_ENV_VARS")));
        names.addAll(split(env.get("ALLOWED_ENV_SUBSTITUTIONS")));
        names.forEach(name -> values.add(env.get(name)));
        return values;
    }

    private static List<String> split(String commaSeparated) {
        if (commaSeparated == null) {
            return List.of();
        }
        return Arrays.stream(commaSeparated.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}

package com.mediamarktsaturn.technolinator.os;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides which part of Technolinators own environment is handed down to the processes run on behalf of a repository,
 * i.e. cdxgen and the build tools it starts.
 * <p>
 * Technolinators environment holds secrets (GitHub app key, Dependency-Track api key, ...) that must neither be readable
 * by the build of an analysed repository, nor be reported by cdxgens environment audit.
 * Hence only the variables the tools need to work are inherited; everything else has to be passed explicitly.
 */
public final class ChildEnvironment {

    /**
     * Names (exact) or prefixes (trailing `*`) of variables inherited by default.
     */
    static final List<String> DEFAULT_INHERITED = List.of(
        // process basics
        "PATH", "HOME", "USER", "LOGNAME", "HOSTNAME", "TERM", "TZ", "TMPDIR", "TMP", "TEMP",
        "LANG", "LANGUAGE", "LC_*",
        "XDG_*",
        // Technolinators directory layout and tool locations, see Dockerfile
        "APP_DIR", "APP_DATA", "SDK_DIR", "GIT_ASKPASS",
        "JAVA*_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
        "MAVEN_HOME", "MAVEN_OPTS", "MAVEN_ARGS", "MVN_CMD",
        "GRADLE_HOME", "GRADLE_CMD", "GRADLE_OPTS", "GRADLE_USER_HOME",
        "ANDROID_HOME", "ANDROID_SDK_ROOT",
        "NODE_HOME", "NODE_EXTRA_CA_CERTS",
        "GO_HOME", "GOROOT", "GOPATH", "GOCACHE", "GOPROXY", "GOPRIVATE", "GONOSUMDB",
        // network
        "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
        "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE",
        // cdxgen settings of the operator
        "CDXGEN_*"
    );

    /**
     * Inherited by prefix, but not wanted: the debug mode of cdxgen is switched on solely by `CDXGEN_DEBUG=true`,
     * see {@link com.mediamarktsaturn.technolinator.sbom.CdxgenClient}.
     */
    static final List<String> NOT_INHERITED_BY_DEFAULT = List.of("CDXGEN_DEBUG_MODE");

    private static final Pattern SECRET_NAME = Pattern.compile("(?i)TOKEN|SECRET|PASSWORD|PASSWD|PASSPHRASE|APIKEY|API_KEY|PRIVATE_KEY|ACCESS_KEY|CREDENTIAL");

    private ChildEnvironment() {
    }

    /**
     * Whether the name of an environment variable suggests it holds a secret, like the ones of Technolinators own configuration.
     * Same convention as the redaction of reports.
     */
    public static boolean isSecretName(String name) {
        return SECRET_NAME.matcher(name).find();
    }

    /**
     * @param env variables inherited from Technolinators environment
     * @param sensitiveNames names of variables to hide explicitly, e.g. the content of {@code SENSITIVE_ENV_VARS}
     * @return a copy of [env] without the variables that are named sensitive, or look like holding a secret
     */
    public static Map<String, String> withoutSecrets(Map<String, String> env, Collection<String> sensitiveNames) {
        var result = new HashMap<String, String>();
        env.forEach((name, value) -> {
            if (!isSecretName(name) && !sensitiveNames.contains(name)) {
                result.put(name, value);
            }
        });
        return result;
    }

    /**
     * @param parentEnv the environment of Technolinator
     * @param additional operator configured names or prefixes (trailing `*`) to inherit in addition to the defaults
     * @return the variables to hand down, as a new mutable map
     */
    public static Map<String, String> inherited(Map<String, String> parentEnv, Collection<String> additional) {
        var inherited = new HashMap<String, String>();
        parentEnv.forEach((name, value) -> {
            if ((matches(name, DEFAULT_INHERITED) && !NOT_INHERITED_BY_DEFAULT.contains(name)) || matches(name, additional)) {
                inherited.put(name, value);
            }
        });
        return inherited;
    }

    private static boolean matches(String name, Collection<String> patterns) {
        return patterns.stream().anyMatch(pattern -> matches(name, pattern));
    }

    /**
     * Exact match, or glob with `*` as wildcard for any sequence of characters (`JAVA*_HOME`, `LC_*`).
     */
    static boolean matches(String name, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        var parts = pattern.trim().split("\\*", -1);
        if (parts.length == 1) {
            return name.equals(parts[0]);
        }
        if (!name.startsWith(parts[0]) || !name.endsWith(parts[parts.length - 1])
            || name.length() < parts[0].length() + parts[parts.length - 1].length()) {
            return false;
        }
        var position = parts[0].length();
        var end = name.length() - parts[parts.length - 1].length();
        for (int i = 1; i < parts.length - 1; i++) {
            var found = name.indexOf(parts[i], position);
            if (found < 0 || found + parts[i].length() > end) {
                return false;
            }
            position = found + parts[i].length();
        }
        return true;
    }
}

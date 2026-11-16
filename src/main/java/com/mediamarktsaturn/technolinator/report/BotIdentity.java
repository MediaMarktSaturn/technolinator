package com.mediamarktsaturn.technolinator.report;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * The login under which the GitHub app writes issues and comments, like {@code technolinator[bot]}.
 * It narrows down the issues to look at, and tells which issues and comments are the ones of the app.
 * If it can't be determined, any bot is taken for the app.
 */
@ApplicationScoped
public class BotIdentity {

    private final GitHubClientProvider clientProvider;
    private final Optional<String> configuredLogin;
    private volatile Optional<String> discoveredLogin;

    public BotIdentity(
        GitHubClientProvider clientProvider,
        @ConfigProperty(name = "app.failure_reporting.bot_login")
        Optional<String> configuredLogin
    ) {
        this.clientProvider = clientProvider;
        this.configuredLogin = configuredLogin.map(String::trim).filter(login -> !login.isEmpty());
    }

    /**
     * Anyone can copy the markers of Technolinator into their own issues and comments, so only content
     * written by the app is to be updated, closed or deleted.
     *
     * @param author   login of the author, may be null
     * @param appLogin login of the app, if known
     */
    public static boolean isWrittenByApp(String author, Optional<String> appLogin) {
        var login = author == null ? "" : author;
        return appLogin.map(login::equalsIgnoreCase).orElse(login.endsWith("[bot]"));
    }

    public Optional<String> login() {
        if (configuredLogin.isPresent()) {
            return configuredLogin;
        }
        var known = discoveredLogin;
        if (known == null) {
            try {
                var slug = clientProvider.getApplicationClient().getApp().getSlug();
                known = Optional.ofNullable(slug).filter(s -> !s.isBlank()).map(s -> s + "[bot]");
                discoveredLogin = known;
            } catch (Exception e) {
                Log.debugf(e, "Could not determine the login of the GitHub app, looking at all issues instead");
                return Optional.empty();
            }
        }
        return known;
    }
}

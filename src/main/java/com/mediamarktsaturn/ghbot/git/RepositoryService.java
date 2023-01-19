package com.mediamarktsaturn.ghbot.git;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

import javax.enterprise.context.ApplicationScoped;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mediamarktsaturn.ghbot.os.Util;
import io.quarkus.logging.Log;

@ApplicationScoped
public class RepositoryService {

    private final CredentialsProvider credentials;

    public RepositoryService(
        @ConfigProperty(name = "github.token")
        String githubToken
    ) {
        this.credentials = new UsernamePasswordCredentialsProvider(githubToken.trim(), "");
    }

    public CompletableFuture<CheckoutResult> checkoutBranch(URL repoUrl, String branch) {
        return CompletableFuture.supplyAsync(() -> {
            Log.infof("Fetching branch %s from %s", branch, repoUrl);
            File dir = null;
            try {
                dir = createTempDir(repoUrl);
                Git repo = Git.cloneRepository()
                    .setCredentialsProvider(credentials)
                    .setDirectory(dir)
                    .setURI(repoUrl.toString())
                    .setDepth(1)
                    .call();
                if (!repo.getRepository().getBranch().equals(branch)) {
                    repo.checkout()
                        .setName(branch)
                        .setCreateBranch(true)
                        .setStartPoint("origin/" + branch)
                        .call();
                }
                Log.infof("Branch %s of %s available at %s", branch, repoUrl, dir.getAbsolutePath());
                return new CheckoutResult.Success(new LocalRepository(repo, dir));
            } catch (Exception e) {
                Log.errorf(e, "Failed to provide branch %s of %s", branch, repoUrl);
                removeTempDir(dir);
                return new CheckoutResult.Failure(e);
            }
        });
    }

    private File createTempDir(URL repoUrl) throws Exception {
        var path = repoUrl.getPath();
        var repoName = path.substring(path.lastIndexOf('/') + 1).replace(".git", "");
        return Files.createTempDirectory(repoName).toFile();
    }

    private void removeTempDir(File dir) {
        if (dir != null) {
            Util.removeAsync(dir);
        }
    }

    public sealed interface CheckoutResult {
        record Success(
            LocalRepository repo
        ) implements CheckoutResult {
        }

        record Failure(
            Throwable cause
        ) implements CheckoutResult {
        }
    }

}

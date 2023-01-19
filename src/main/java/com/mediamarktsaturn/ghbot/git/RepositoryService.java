package com.mediamarktsaturn.ghbot.git;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.enterprise.context.ApplicationScoped;

import com.mediamarktsaturn.ghbot.events.PushEvent;
import com.mediamarktsaturn.ghbot.os.ProcessHandler;
import com.mediamarktsaturn.ghbot.os.Util;
import io.quarkus.logging.Log;

@ApplicationScoped
public class RepositoryService {

    private static final String DOWNLOAD = "download.zip";
    private static final String UNZIP_CMD = "unzip -q " + DOWNLOAD;

    public CompletableFuture<CheckoutResult> checkoutBranch(PushEvent event) {
        return CompletableFuture.supplyAsync(() -> downloadBranch(event))
            .thenCompose(this::unzipDownloaded);
    }

    private CheckoutResult downloadBranch(PushEvent event) {
        var ref = event.pushRef();
        var branch = event.getBranch();
        var repoUrl = event.repoUrl();
        File dir = null;
        try {
            dir = createTempDir(repoUrl);
            final var tmpDir = dir;
            event.pushPayload().getRepository().readZip(zis -> {
                try (FileOutputStream fos = new FileOutputStream(new File(tmpDir, DOWNLOAD)); zis) {
                    byte[] buffer = new byte[1048576];
                    int read;
                    while ((read = zis.read(buffer, 0, 1048576)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                    fos.flush();
                }
                return null;
            }, ref);
            Log.infof("Branch %s of %s downloaded to %s", branch, repoUrl, dir.getAbsolutePath());
            return new CheckoutResult.Success(new LocalRepository(dir));
        } catch (Exception e) {
            Log.errorf(e, "Failed to provide ref %s of %s", ref, repoUrl);
            removeTempDir(dir);
            return new CheckoutResult.Failure(e);
        }
    }

    private CompletableFuture<CheckoutResult> unzipDownloaded(CheckoutResult result) {
        if (result instanceof CheckoutResult.Success) {
            var repo = ((CheckoutResult.Success) result).repo();
            var dir = repo.dir();
            return ProcessHandler.run(UNZIP_CMD, dir, Map.of())
                .thenCompose(processResult -> {
                    if (processResult instanceof ProcessHandler.ProcessResult.Success) {
                        return ProcessHandler.run("rm -f " + DOWNLOAD, dir, Map.of())
                            .thenApply(deleteResult -> {
                                if (deleteResult instanceof ProcessHandler.ProcessResult.Success) {
                                    var subdir = dir.listFiles()[0];
                                    return new CheckoutResult.Success(new LocalRepository(subdir));
                                } else {
                                    return new CheckoutResult.Failure(((ProcessHandler.ProcessResult.Failure) deleteResult).cause());
                                }
                            });
                    } else {
                        return CompletableFuture.completedFuture((CheckoutResult) new CheckoutResult.Failure(((ProcessHandler.ProcessResult.Failure) processResult).cause()));
                    }
                }).whenComplete((unzipResult, unzipFailure) -> {
                    if (unzipFailure != null || unzipResult instanceof CheckoutResult.Failure) {
                        repo.close();
                    }
                });
        } else {
            return CompletableFuture.completedFuture(result);
        }
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
